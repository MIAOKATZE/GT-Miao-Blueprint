package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/** Owner-thread capture handoff. No fake session, native access on workers, or second capture-sized copy. */
final class HologramSourceTransfer {

    static final int MAX_WORK = 256;
    private static final java.util.concurrent.atomic.AtomicInteger RETAINED = new java.util.concurrent.atomic.AtomicInteger();
    private Lease lease;
    private final Thread owner = Thread.currentThread();
    private final HologramSession s;
    private final HologramCapture capture;
    private final HologramPresets.Preset original;
    private final World world;
    private final String session;
    private final long revision, task;
    private final int size;
    private final com.miaokatze.gtmb.blueprint.editor.AuthorDocument.SourceFrame sourceFrame;
    private final Set<String> palette = new HashSet<>();
    private HologramBlueprintProjection.Header header;
    private HologramPresets.Preset result;
    private final java.util.Map<Coordinate, HologramPresets.Cell> sourceRows = new java.util.HashMap<>();
    private final java.util.Map<java.util.List<String>, java.util.List<String>> memberships = new java.util.HashMap<>();
    private final java.util.Map<Targets, java.util.List<HologramPresets.Target>> declarations = new java.util.HashMap<>();
    private final java.util.Map<String, String> payloads = new java.util.HashMap<>();
    private long indexTableBytes, indexEntryBytes;
    private int indexed, paletteSize;
    private int cursor, sourceCursor;
    private boolean headerReady;
    private long retainedBytes = 4096, lastProgress;

    HologramSourceTransfer(HologramSession s, long task) {
        this.s = s;
        this.task = task;
        capture = s.capture;
        original = s.virtualPreset;
        world = s.player.worldObj;
        session = s.id;
        revision = s.planRevision;
        size = capture.cells.size();
        sourceFrame = com.miaokatze.gtmb.blueprint.editor.AuthorDocument.SourceFrame
            .captured(s.dimension, s.x, s.y, s.z);
        header = new HologramBlueprintProjection.Header(s, original == null ? "未命名蓝图" : original.name);
        result = header.result;
    }

    static void start(HologramSession s, long task) throws IOException {
        HologramBlueprintLimits.require(s.capture.cells.size());
        if (s.sourceTransfer != null && s.sourceTransfer.task == task && s.sourceTransfer.current()) return;
        if (s.sourceTransfer != null && task < s.sourceTransfer.task)
            throw new IOException("Older author source task cannot replace current work");
        cancel(s);
        if (RETAINED.incrementAndGet() > 2) {
            RETAINED.decrementAndGet();
            throw new IOException("作者来源队列已满，请稍后重试");
        }
        Lease lease = new Lease();
        try {
            HologramLibraryFacade.reserveAuthorSource(s.player, s.id, s.planRevision, task);
            s.sourceTransfer = new HologramSourceTransfer(s, task);
            s.sourceTransfer.lease = lease;
        } catch (IOException | RuntimeException failure) {
            lease.close();
            throw failure;
        }
        s.sourceTransfer.progress(true);
    }

    static boolean pending(HologramSession s) {
        return s.sourceTransfer != null;
    }

    static void cancel(HologramSession s) {
        HologramSourceTransfer transfer = s.sourceTransfer;
        s.sourceTransfer = null;
        if (transfer != null) {
            if (transfer.result != null)
                HologramLibraryFacade.releaseAuthorSource(s.player, transfer.session, transfer.revision, transfer.task);
            if (transfer.lease != null) transfer.lease.close();
            transfer.lease = null;
            transfer.header = null;
            transfer.result = null;
            transfer.palette.clear();
            transfer.sourceRows.clear();
            transfer.memberships.clear();
            transfer.declarations.clear();
            transfer.payloads.clear();
        }
    }

    static void cancel(EntityPlayerMP player, NBTTagCompound request) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        HologramSourceTransfer transfer = s == null ? null : s.sourceTransfer;
        if (transfer != null && s.player == player && transfer.matches(request)) cancel(s);
    }

    boolean matches(NBTTagCompound request) {
        return request.hasKey("planRevision", 4) && revision == request.getLong("planRevision")
            && session.equals(request.getString("session"))
            && task == request.getLong("clientTaskToken");
    }

    boolean sourceUnchanged() {
        return s.sourceTransfer == this && sourceFrame.dimension == s.dimension
            && sourceFrame.x == s.x
            && sourceFrame.y == s.y
            && sourceFrame.z == s.z
            && s.capture == capture
            && s.virtualPreset == original
            && capture.cells.size() == size
            && s.player.worldObj == world
            && s.libraryWorld == world
            && s.player.dimension == s.dimension
            && session.equals(s.id)
            && revision == s.planRevision;
    }

    boolean current() {
        return sourceUnchanged() && HologramWorkspace.find(s.player.getUniqueID()) == s
            && !s.cancelled
            && !s.capturePending
            && !capture.incomplete
            && !s.localPlanPending
            && s.job != 1
            && s.job != 2
            && s.attachmentTask == null
            && !s.virtualPlacement
            && s.blueprintSaveReceipt.isEmpty()
            && !HologramEditorWorkspaceRegistry.physicalProjection(s)
            && !HologramSessionPolicy.blockedByAreaImport(s)
            && HologramSessionPolicy.valid(s)
            && HologramNetwork.progressConnected(s.player);
    }

    static boolean tick(HologramSession s) {
        HologramSourceTransfer transfer = s.sourceTransfer;
        if (transfer == null) return false;
        if (!transfer.current()) {
            cancel(s);
            HologramLibraryFacade.error(s.player, "作者来源已变化，请重新获取", transfer.task);
            return true;
        }
        try {
            if (transfer.step(
                MAX_WORK,
                HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(HologramWorkBudget.SLICE_NANOS)),
                System::nanoTime)) {
                if (!transfer.current()) throw new IOException("作者来源已变化");
                transfer.progress(true);
                OwnedSource source = transfer.detach();
                cancel(s);
                HologramLibraryFacade
                    .downloadAuthorSource(s.player, source, transfer.session, transfer.revision, transfer.task);
            } else transfer.progress(false);
        } catch (IOException | RuntimeException failure) {
            cancel(s);
            HologramLibraryFacade.error(s.player, "作者来源传输失败：" + failure.getMessage(), transfer.task);
        }
        return true;
    }

    /** Every header record/cell has its own clock checkpoint, including the first operation. */
    boolean step(int maxWork, long deadline, LongSupplier clock) throws IOException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Source projection requires owner thread");
        if (result == null) throw new IllegalStateException("Source transfer retired");
        for (int work = 0; work < maxWork && clock.getAsLong() < deadline; work++) {
            if (!sourceUnchanged()) throw new IOException("Author source changed during projection");
            if (!headerReady) {
                headerReady = header.step();
                if (!HologramSourceTransferBudget.fits(header.retainedBytes))
                    throw new IOException("Author source metadata budget");
                if (headerReady) {
                    // All transient documents are mutable server-only graphs, not transport data.
                    result.segmentDocument = null;
                    result.schemaPageCache = null;
                    result.analysisDocument = null;
                }
            } else if (original != null && sourceCursor < original.cells.size()) {
                HologramPresets.Cell cell = original.cells.get(sourceCursor++);
                if (needsSource(cell)) {
                    if (sourceRows.put(new Coordinate(cell.x, cell.y, cell.z), cell) == null) {
                        indexEntryBytes += HologramSourceTransferBudget.INDEX_ENTRY;
                        // A HashMap table grows geometrically; 32 bytes per peak entry bounds its 8-byte slots.
                        indexTableBytes = 64 + 32L * ++indexed;
                    }
                }
            } else if (cursor < size) {
                HologramCapture.Cell captured = capture.cells.get(cursor);
                HologramPresets.Cell projected = HologramBlueprintProjection
                    .projectedCell(s, cursor++, result, palette, true);
                HologramPresets.Cell source = sourceRows.remove(new Coordinate(projected.x, projected.y, projected.z));
                if (source != null) indexEntryBytes -= HologramSourceTransferBudget.INDEX_ENTRY;
                HologramPresets.Cell cell = mergeSource(source, projected);
                retain(cell, captured, source);
                if (!HologramSourceTransferBudget.fits(ownedBytes()) || result.palette.size() > 4096)
                    throw new IOException("作者来源数据预算已满，请拆分蓝图");
                result.cells.add(cell);
            } else return true;
            if (!HologramSourceTransferBudget.fits(ownedBytes()))
                throw new IOException("Author source retained data budget");
        }
        return headerReady && (original == null || sourceCursor == original.cells.size()) && cursor == size;
    }

    /** Retain source-only defaults; current capture edits and resolved declarations remain authoritative. */
    static HologramPresets.Cell mergeSource(HologramPresets.Cell original, HologramPresets.Cell projected) {
        if (original == null) return projected;
        HologramPresets.Cell result = new HologramPresets.Cell();
        HologramSnapshotMetadataSource.copyScalars(original, result);
        result.item = projected.item;
        result.damage = projected.damage;
        result.block = projected.block;
        result.metadata = projected.metadata;
        result.tileNbt = projected.tileNbt;
        result.itemNbt = projected.itemNbt;
        result.prompt = projected.prompt;
        result.structureId = projected.structureId;
        result.structureIds = projected.structureIds;
        result.allowedTargets = projected.allowedTargets;
        result.excluded = projected.excluded;
        result.x = projected.x;
        result.y = projected.y;
        result.z = projected.z;
        result.facing = projected.facing;
        result.controller = projected.controller;
        if (projected.segmentCellId != 0) result.segmentCellId = projected.segmentCellId;
        if (original.allowedTargets != null)
            for (HologramPresets.Target declared : original.allowedTargets) if (declared.stack() == null) {
                if (result.allowedTargets == null) result.allowedTargets = new java.util.ArrayList<>();
                HologramPresets.Target copy = new HologramPresets.Target();
                copy.item = declared.item;
                copy.damage = declared.damage;
                result.allowedTargets.add(copy);
            }
        return result;
    }

    private static boolean needsSource(HologramPresets.Cell cell) {
        if (cell.defaultItem != null || cell.defaultDamage != 0 || cell.count != 1 || cell.segmentCellId != 0)
            return true;
        if (cell.allowedTargets != null)
            for (HologramPresets.Target target : cell.allowedTargets) if (target.stack() == null) return true;
        return false;
    }

    long ownedBytes() {
        return retainedBytes + indexEntryBytes + indexTableBytes + (header == null ? 0 : header.retainedBytes);
    }

    private void retain(HologramPresets.Cell cell, HologramCapture.Cell captured, HologramPresets.Cell source) {
        // Per-row DTO + the result ArrayList's worst-case growth slack. Strings below are either
        // immutable aliases to the capture/registry, or explicitly canonicalized newly encoded payloads.
        retainedBytes += HologramSourceTransferBudget.CELL + 16;
        cell.tileNbt = payload(cell.tileNbt, captured.tileNbt, source == null ? null : source.tileNbt);
        cell.itemNbt = payload(cell.itemNbt, captured.itemNbt, source == null ? null : source.itemNbt);
        if (cell.structureIds != null) {
            if (cell.structureIds.isEmpty()) cell.structureIds = java.util.Collections.emptyList();
            else {
                java.util.List<String> canonical = memberships.get(cell.structureIds);
                if (canonical == null) {
                    canonical = cell.structureIds;
                    memberships.put(canonical, canonical);
                    retainedBytes += HologramSourceTransferBudget.list(canonical.size(), false)
                        + HologramSourceTransferBudget.MAP_ENTRY;
                }
                cell.structureIds = canonical;
            }
        }
        if (cell.allowedTargets != null) {
            if (cell.allowedTargets.isEmpty()) cell.allowedTargets = java.util.Collections.emptyList();
            else {
                Targets key = new Targets(cell.allowedTargets);
                java.util.List<HologramPresets.Target> canonical = declarations.get(key);
                if (canonical == null) {
                    canonical = cell.allowedTargets;
                    declarations.put(key, canonical);
                    retainedBytes += 32 + HologramSourceTransferBudget.MAP_ENTRY
                        + HologramSourceTransferBudget.list(canonical.size(), true)
                        + HologramSourceTransferBudget.TARGET * canonical.size();
                }
                cell.allowedTargets = canonical;
            }
        }
        // addPalette creates unique registry-backed cells/keys; never charge it once per source cell.
        while (paletteSize < result.palette.size()) {
            HologramPresets.Cell entry = result.palette.get(paletteSize++);
            retainedBytes += HologramSourceTransferBudget.CELL + 16
                + HologramSourceTransferBudget.MAP_ENTRY
                + HologramSourceTransferBudget.string(entry.item == null ? "" : entry.item + ":" + entry.damage);
        }
    }

    private String payload(String value, String captured, String source) {
        if (value == null || value.isEmpty()) return value;
        if (value.equals(captured)) return captured;
        if (value.equals(source)) return source;
        String canonical = payloads.get(value);
        if (canonical != null) return canonical;
        payloads.put(value, value);
        retainedBytes += HologramSourceTransferBudget.MAP_ENTRY + HologramSourceTransferBudget.string(value);
        return value;
    }

    /** Read-only DTO aliases are scoped to this owned transfer, never a global/native cache. */
    private static final class Targets {

        final java.util.List<HologramPresets.Target> values;
        final int hash;

        Targets(java.util.List<HologramPresets.Target> values) {
            this.values = values;
            int hash = 1;
            for (HologramPresets.Target value : values)
                hash = 31 * hash + 31 * java.util.Objects.hashCode(value.item) + value.damage;
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Targets)) return false;
            java.util.List<HologramPresets.Target> right = ((Targets) other).values;
            if (values.size() != right.size()) return false;
            for (int i = 0; i < values.size(); i++) if (values.get(i).damage != right.get(i).damage
                || !java.util.Objects.equals(values.get(i).item, right.get(i).item)) return false;
            return true;
        }
    }

    private static final class Coordinate {

        final int x, y, z;

        Coordinate(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int hashCode() {
            return (31 * x + y) * 31 + z;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Coordinate)) return false;
            Coordinate value = (Coordinate) other;
            return x == value.x && y == value.y && z == value.z;
        }
    }

    private void progress(boolean force) {
        long now = System.nanoTime();
        if (!force && lastProgress != 0 && now - lastProgress < 250_000_000L) return;
        lastProgress = now;
        NBTTagCompound reply = HologramLibraryProtocol.reply("preparationProgress");
        reply.setString("session", session);
        reply.setLong("planRevision", revision);
        reply.setLong("clientTaskToken", task);
        reply.setString("phase", "snapshot.prepare");
        reply.setString("detail", "author_source");
        reply.setLong("done", cursor);
        reply.setLong("total", size);
        reply.setString("unit", "rows");
        HologramNetwork.sendLibraryReply(s.player, reply);
    }

    private OwnedSource detach() {
        if (!headerReady || cursor != size || !current()) throw new IllegalStateException("Source is not complete");
        OwnedSource owned = new OwnedSource(result, sourceFrame, lease);
        lease = null;
        result = null;
        header = null;
        return owned;
    }

    private static final class Lease {

        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();

        void close() {
            if (released.compareAndSet(false, true)) RETAINED.decrementAndGet();
        }
    }

    /** Unforgeable outside this producer; single-use ownership, not a boolean skip-copy flag. */
    static final class OwnedSource {

        private HologramPresets.Preset source;
        private final Lease lease;
        final com.miaokatze.gtmb.blueprint.editor.AuthorDocument.SourceFrame sourceFrame;

        private OwnedSource(HologramPresets.Preset source,
            com.miaokatze.gtmb.blueprint.editor.AuthorDocument.SourceFrame sourceFrame, Lease lease) {
            this.source = source;
            this.sourceFrame = sourceFrame;
            this.lease = lease;
        }

        void close() {
            if (lease != null) lease.close();
        }

        synchronized HologramPresets.Preset take() throws IOException {
            if (source == null) throw new IOException("Author source ownership already transferred");
            HologramPresets.Preset taken = source;
            source = null;
            return taken;
        }
    }
}
