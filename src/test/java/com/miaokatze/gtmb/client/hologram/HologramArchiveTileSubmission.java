// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.miaokatze.gtmb.client.hologram.HologramArchiveCameraTiles.Frame;
import com.miaokatze.gtmb.client.hologram.HologramArchiveCameraTiles.FrameScope;
import com.miaokatze.gtmb.client.hologram.HologramArchiveCameraTiles.Tile;

/** Bounded, render-thread-only submission planner. The injected boundary is the only access to a backend. */
final class HologramArchiveTileSubmission<H> {

    static final int UPLOAD_QUADS = 512, MAX_DRAWS = 12, MAX_DRAW_QUADS = 6144;
    static final int MAX_HANDLES = 24, MAX_DELETES = 2;
    static final int BODY = -1;
    private static final int ALL_CAPS = (1 << HologramArchiveTileMesh.SIDES) - 1;

    /**
     * One stable render-phase capability. The supplier must return the same adapter reference while the phase is
     * live. A callback may refuse but cannot reenter submission or issue GL outside its validated current context.
     * On actual phase revocation the backend owns any scope it already began and must not require foreign GL cleanup.
     * Upload transfers only a positively returned handle; the backend cleans its own failed/partial upload.
     */
    interface Backend<H> {

        /** Fresh current context query: exact supported lifetime, or null while absent/unsupported. */
        Object context();

        H upload(Slice slice);

        boolean begin(HologramEditorCameraFrame pose);

        boolean draw(H handle, Slice slice);

        void end();

        /** True only if retired in this exact context. Retry of the same handle must be safe. */
        boolean release(H handle);
    }

    enum Status {
        EMPTY,
        AWAITING_TILES,
        SUBMITTED,
        CAPACITY_LIMITED,
        UNSUPPORTED,
        BACKEND_REFUSED,
        DELETION_DEFERRED,
        FRAME_SPENT,
        STALE
    }

    static final class Slice {

        final Tile tile;
        final int stream, first, quads;

        private Slice(Tile tile, int stream, int first, int quads) {
            this.tile = tile;
            this.stream = stream;
            this.first = first;
            this.quads = quads;
        }

        int quad(int at) {
            if (at < 0 || at >= quads) throw new IndexOutOfBoundsException("Archive mesh slice");
            return stream == BODY ? tile.mesh.bodyQuad(first + at) : tile.mesh.capQuad(stream, first + at);
        }
    }

    static final class Report {

        final Status status;
        final int uploadCalls, uploadQuads, drawCalls, drawQuads, submittedQuads, deleteCalls, handles, debt;

        private Report(Status status, Work work, int handles, int debt) {
            this.status = status;
            this.uploadCalls = work.uploadCalls;
            this.uploadQuads = work.uploadQuads;
            this.drawCalls = work.drawCalls;
            this.drawQuads = work.drawQuads;
            this.submittedQuads = work.submittedQuads;
            this.deleteCalls = work.deleteCalls;
            this.handles = handles;
            this.debt = debt;
        }
    }

    private static final class Work {

        int uploadCalls, uploadQuads, drawCalls, drawQuads, submittedQuads, deleteCalls;
        boolean refused, capacity;
    }

    private static final class Part<H> {

        final Slice slice;
        H handle;

        Part(Slice slice) {
            this.slice = slice;
        }
    }

    private static final class Resident<H> {

        final Tile tile;
        final List<Part<H>> parts = new ArrayList<>();
        int quads, uploaded;
        boolean refused;

        Resident(Tile tile) {
            this.tile = tile;
            slice(BODY, tile.mesh.bodyCount());
            for (int side = 0; side < HologramArchiveTileMesh.SIDES; side++) slice(side, tile.mesh.capCount(side));
        }

        private void slice(int stream, int count) {
            quads += count;
            // Above this there is already enough evidence to refuse; do not allocate more per-tile descriptors.
            for (int first = 0; first < count && parts.size() <= MAX_DRAWS; first += UPLOAD_QUADS)
                parts.add(new Part<>(new Slice(tile, stream, first, Math.min(UPLOAD_QUADS, count - first))));
        }

        boolean fits() {
            return parts.size() <= MAX_DRAWS && quads <= MAX_DRAW_QUADS;
        }

        boolean complete() {
            return !refused && uploaded == parts.size();
        }
    }

    private static final class Retired<H> {

        final Object context;
        final H handle;

        Retired(Object context, H handle) {
            this.context = context;
            this.handle = handle;
        }
    }

    private static final class DrawTile<H> {

        final Resident<H> resident;
        int attemptedCaps;
        boolean failed;

        DrawTile(Resident<H> resident) {
            this.resident = resident;
        }
    }

    private final Thread render = Thread.currentThread();
    private final Supplier<Backend<H>> current;
    private final Map<Tile, Resident<H>> residents = new IdentityHashMap<>();
    private final ArrayDeque<Retired<H>> retired = new ArrayDeque<>();
    private HologramArchiveCameraTiles source;
    private Resident<H> filling;
    private Object context;
    private long textures = -1, frameStamp = -1;
    private int handles, deleteAttempts;
    private boolean submitted, pumped;

    HologramArchiveTileSubmission(Supplier<Backend<H>> current) {
        this.current = Objects.requireNonNull(current);
    }

    /** Retiring a source never uses GL. The normal render phase may later drain debt. */
    void bind(HologramArchiveCameraTiles exactSource) {
        requireRender();
        if (source == exactSource) return;
        retireAll();
        source = exactSource;
        textures = -1;
    }

    /**
     * The render owner supplies one monotonic stamp per authoritative physical render frame. The same stamp must be
     * used for submit and all cleanup pumps in that frame; the CPU Frame can span multiple physical frames.
     * Texture supplier must be a fresh read of the real resource generation, not a cached copy of the Frame stamp.
     */
    Report submit(long physicalFrame, Frame frame, LongSupplier currentTextures) {
        requireRender();
        Work work = new Work();
        if (!admitStamp(physicalFrame) || frame == null || source == null) return report(Status.STALE, work);
        long liveTextures = textureGeneration(currentTextures);
        if (liveTextures < 0 || liveTextures < textures) return report(Status.STALE, work);
        FrameScope scope = frame.scope(source, liveTextures);
        if (scope == FrameScope.FOREIGN) return report(Status.STALE, work);
        if (scope == FrameScope.RETIRED || liveTextures != textures) {
            retireAll();
            textures = liveTextures;
        }
        if (scope != FrameScope.CURRENT) return report(Status.STALE, work);
        if (submitted) return report(Status.FRAME_SPENT, work);
        submitted = true;

        Phase phase = acquire(frame, currentTextures);
        if (phase == null) return report(Status.UNSUPPORTED, work);
        if (!phase.live()) return interrupted(phase, work);
        if (context != phase.token) {
            retireAll();
            context = phase.token;
        }
        removeInvisible(frame);
        drain(phase, work);
        if (!phase.live()) return interrupted(phase, work);
        work.capacity = frame.capacityLimited;
        uploadOne(frame, phase, work);
        if (!phase.live()) return interrupted(phase, work);
        draw(frame, phase, work);
        if (!phase.live()) return interrupted(phase, work);

        boolean pending = frame.ready < frame.requested;
        for (Resident<H> resident : residents.values()) {
            if (!resident.fits()) work.capacity = true;
            else if (resident.refused) work.refused = true;
            else if (!resident.complete()) pending = true;
        }
        Status status = work.refused ? Status.BACKEND_REFUSED
            : work.capacity ? Status.CAPACITY_LIMITED
                : pending ? Status.AWAITING_TILES : work.submittedQuads > 0 ? Status.SUBMITTED : Status.EMPTY;
        return report(status, work);
    }

    /** Uses the same frame stamp/delete budget as submit, even after pane close. */
    Report pumpRetired(long physicalFrame) {
        requireRender();
        Work work = new Work();
        if (!admitStamp(physicalFrame)) return report(Status.STALE, work);
        if (retired.isEmpty()) return report(Status.EMPTY, work);
        if (pumped || deleteAttempts == MAX_DELETES) return report(Status.DELETION_DEFERRED, work);
        pumped = true;
        Phase phase = acquire(null, null);
        if (phase == null) return report(Status.UNSUPPORTED, work);
        drain(phase, work);
        if (!phase.live()) return interrupted(phase, work);
        return report(
            work.refused ? Status.BACKEND_REFUSED : retired.isEmpty() ? Status.EMPTY : Status.DELETION_DEFERRED,
            work);
    }

    private boolean admitStamp(long stamp) {
        if (stamp < 0 || stamp < frameStamp) return false;
        if (stamp != frameStamp) {
            frameStamp = stamp;
            submitted = pumped = false;
            deleteAttempts = 0;
        }
        return true;
    }

    private Backend<H> available() {
        try {
            return current.get();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private Object currentContext(Backend<H> backend) {
        try {
            return backend == null ? null : backend.context();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static long textureGeneration(LongSupplier query) {
        if (query == null) return -1;
        try {
            return query.getAsLong();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private Phase acquire(Frame frame, LongSupplier currentTextures) {
        Backend<H> backend = available();
        Object token = currentContext(backend);
        return token == null ? null : new Phase(backend, token, frame, currentTextures);
    }

    private Report interrupted(Phase phase, Work work) {
        if (phase.lostContext && context == phase.token) {
            retireAll(); // Successfully returned uploads are debt in the pre-call context, never the new one.
            context = null;
        } else if (phase.textureChanged
            || phase.frame != null && phase.frame.scope(source, textures) == FrameScope.RETIRED) retireAll();
        return report(phase.stale ? Status.STALE : Status.UNSUPPORTED, work);
    }

    private final class Phase {

        final Backend<H> backend;
        final Object token;
        final Frame frame;
        final LongSupplier liveTextures;
        boolean stale, lostContext, textureChanged;

        Phase(Backend<H> backend, Object token, Frame frame, LongSupplier liveTextures) {
            this.backend = backend;
            this.token = token;
            this.frame = frame;
            this.liveTextures = liveTextures;
        }

        boolean inContext() {
            if (!lostContext && (available() != backend || currentContext(backend) != token || available() != backend))
                lostContext = true;
            return !lostContext;
        }

        boolean live() {
            if (stale || lostContext || !currentFrame()) return false;
            return inContext() && currentFrame();
        }

        private boolean currentFrame() {
            if (frame != null) {
                textureChanged = textureGeneration(liveTextures) != textures;
                if (textureChanged || frame.scope(source, textures) != FrameScope.CURRENT) stale = true;
            }
            return !stale;
        }
    }

    /**
     * A provider may call this only after independently proving actual context teardown, not an unsupported phase,
     * foreign bound context, or a failed lookup. Releasing a destroyed context must not call its dead GL API.
     */
    void forgetDestroyedContext(Object destroyed) {
        requireRender();
        if (destroyed == null) return;
        if (context == destroyed) {
            retireAll();
            context = null;
        }
        for (Iterator<Retired<H>> it = retired.iterator(); it.hasNext();) {
            if (it.next().context != destroyed) continue;
            it.remove();
            handles--;
        }
    }

    private void removeInvisible(Frame frame) {
        for (Iterator<Map.Entry<Tile, Resident<H>>> it = residents.entrySet()
            .iterator(); it.hasNext();) {
            var entry = it.next();
            boolean keep = false;
            for (int i = 0; i < frame.ready; i++) keep |= frame.tile(i) == entry.getKey();
            if (!keep) {
                retire(entry.getValue());
                it.remove();
            }
        }
    }

    private void uploadOne(Frame frame, Phase phase, Work work) {
        for (int i = 0; i < frame.ready; i++) residents.computeIfAbsent(frame.tile(i), Resident::new);
        if (filling == null) {
            for (int i = 0; i < frame.ready; i++) {
                Resident<H> resident = residents.get(frame.tile(i));
                if (!resident.fits() || resident.refused || resident.complete()) continue;
                // One partially allocated tile at a time, with room for every remaining slice reserved.
                if (handles + resident.parts.size() > MAX_HANDLES) {
                    work.capacity = true;
                    continue;
                }
                filling = resident;
                break;
            }
        }
        Resident<H> resident = filling;
        if (resident == null) return;
        Part<H> next = resident.parts.get(resident.uploaded);
        if (!phase.live()) return;
        work.uploadCalls++;
        work.uploadQuads += next.slice.quads;
        try {
            next.handle = phase.backend.upload(next.slice);
        } catch (RuntimeException failure) {
            // The adapter must clean its own partially created object when it cannot return its handle.
            next.handle = null;
        }
        if (next.handle == null) {
            resident.refused = true;
            work.refused = true;
            retire(resident);
        } else {
            handles++;
            resident.uploaded++;
            if (resident.complete()) filling = null;
        }
    }

    private void draw(Frame frame, Phase phase, Work work) {
        List<DrawTile<H>> included = new ArrayList<>(frame.ready);
        int calls = 0, quads = 0;
        for (int i = 0; i < frame.ready; i++) {
            Resident<H> resident = residents.get(frame.tile(i));
            if (resident == null || !resident.fits() || !resident.complete() || resident.parts.isEmpty()) continue;
            // Every cap is charged up front: uncertain/failed neighbors may require them in a later pass.
            if (calls + resident.parts.size() > MAX_DRAWS || quads + resident.quads > MAX_DRAW_QUADS) {
                work.capacity = true;
                continue;
            }
            calls += resident.parts.size();
            quads += resident.quads;
            included.add(new DrawTile<>(resident));
        }
        if (included.isEmpty() || !phase.live()) return;
        boolean begun;
        try {
            begun = phase.backend.begin(frame.pose);
        } catch (RuntimeException invalid) {
            begun = false;
        }
        if (!begun) {
            work.refused = true;
            return;
        }
        try {
            if (!phase.live()) return;
            for (DrawTile<H> tile : included) {
                for (Part<H> part : tile.resident.parts) {
                    if (part.slice.stream != BODY) break;
                    if (!phase.live()) return;
                    if (!drawPart(phase, part, work)) {
                        tile.failed = true;
                        break;
                    }
                }
            }
            boolean removed;
            do {
                removed = false;
                if (!phase.live()) return;
                var witness = frame.beginDraws();
                if (witness == null) return;
                for (DrawTile<H> tile : included)
                    if (!tile.failed && tile.resident.tile.mesh.bodyCount() != 0) witness.record(tile.resident.tile);
                for (DrawTile<H> tile : included) {
                    if (tile.failed) continue;
                    int needs = tile.resident.tile.mesh.bodyCount() == 0 ? ALL_CAPS
                        : witness.neededCaps(tile.resident.tile);
                    int todo = needs & ~tile.attemptedCaps;
                    for (Part<H> part : tile.resident.parts) {
                        if (part.slice.stream == BODY || (todo & 1 << part.slice.stream) == 0) continue;
                        if (!phase.live()) return;
                        tile.attemptedCaps |= 1 << part.slice.stream;
                        if (!drawPart(phase, part, work)) {
                            tile.failed = true;
                            removed = true;
                            break;
                        }
                    }
                }
                // A failed cap cannot turn its still-resident tile into an occluder. Earlier skips are reconsidered.
            } while (removed);
        } finally {
            // Closing a stale source scope is restoration, but never touch foreign/unsupported GL.
            if (phase.inContext()) try {
                phase.backend.end();
            } catch (RuntimeException failure) {
                work.refused = true;
            }
        }
    }

    private boolean drawPart(Phase phase, Part<H> part, Work work) {
        work.drawCalls++;
        work.drawQuads += part.slice.quads;
        boolean success;
        try {
            success = phase.backend.draw(part.handle, part.slice) && phase.live();
        } catch (RuntimeException failure) {
            success = false;
        }
        if (success) work.submittedQuads += part.slice.quads;
        else work.refused = true;
        return success;
    }

    private void retire(Resident<H> resident) {
        if (filling == resident) filling = null;
        for (Part<H> part : resident.parts) {
            if (part.handle == null) continue;
            retired.addLast(new Retired<>(context, part.handle));
            part.handle = null;
        }
        resident.uploaded = 0;
    }

    private void retireAll() {
        for (Resident<H> resident : residents.values()) retire(resident);
        residents.clear();
    }

    private void drain(Phase phase, Work work) {
        // Scan at most the starting debt length; each foreign/refused item rotates instead of starving later ones.
        for (int remaining = retired.size(); remaining > 0 && deleteAttempts < MAX_DELETES; remaining--) {
            Retired<H> old = retired.removeFirst();
            if (old.context != phase.token) {
                retired.addLast(old);
                continue;
            }
            if (!phase.live()) {
                retired.addFirst(old);
                return;
            }
            deleteAttempts++;
            work.deleteCalls++;
            boolean released;
            try {
                released = phase.backend.release(old.handle);
            } catch (RuntimeException failure) {
                released = false;
            }
            if (released) handles--;
            else {
                retired.addLast(old);
                work.refused = true;
            }
            if (!phase.live()) return;
        }
    }

    private Report report(Status status, Work work) {
        return new Report(status, work, handles, retired.size());
    }

    private void requireRender() {
        if (Thread.currentThread() != render)
            throw new IllegalStateException("Archive submission belongs to render thread");
    }
}
