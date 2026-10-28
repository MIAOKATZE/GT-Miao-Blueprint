package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramProcessGovernor;

/** Appearance-only demand. Replies never enter the command/snapshot acknowledgement consumer. */
final class HologramSnapshotModelsClient {

    private static final HologramSnapshotModelCache CACHE = new HologramSnapshotModelCache();
    private static final Map<Integer, Demand> queued = new LinkedHashMap<>();
    private static final Map<String, Pending> pending = new LinkedHashMap<>();
    private static final ArrayDeque<Reply> replies = new ArrayDeque<>();
    private static final Map<Long, Set<HologramRegionCache.Key>> dependencies = new LinkedHashMap<>();
    static Consumer<NBTTagCompound> sender = HologramNetwork::sendAction;
    private static String session = "";
    private static HologramProcessGovernor.Lease index;
    private static long replyBytes;
    private static Batch preparing;
    private static final long RETRY_BASE_NANOS = 500_000_000L;

    private HologramSnapshotModelsClient() {}

    private static boolean context(HologramState state) {
        if (state == null || !state.data.getCompoundTag("modelManifest")
            .getBoolean("available")) return false;
        if (!session.equals(state.session)) {
            clear();
            session = state.session;
        }
        if (index == null) {
            index = HologramProcessGovernor.tryRetain("snapshot.modelDemand", 256L << 10);
            if (index == null) return false;
            index.transfer();
        }
        return true;
    }

    static void clear() {
        if (preparing != null) preparing.close();
        preparing = null;
        for (Reply reply : replies) reply.close();
        replies.clear();
        pending.clear();
        queued.clear();
        dependencies.clear();
        CACHE.clear();
        if (index != null) index.close();
        index = null;
        session = "";
        replyBytes = 0;
    }

    static void requestVisible(HologramState state, HologramState.Cell cell, boolean current) {
        if (state != null && state.authorProjection != null) {
            state.authorProjection.request(state, cell);
            return;
        }
        HologramState live = HologramClient.state;
        if (!context(live) || !live.session.equals(state.session) || cell.index < 0 || cell.index >= live.cells.size())
            return;
        HologramState.Cell now = live.cells.get(cell.index);
        if (now.nativeModelId != cell.nativeModelId || now.nativeModelId <= 0 || !now.modelPending(current)) return;
        current = current || now.anchor || now.status.equals("protected") && now.actualStack != null;
        int mask = current ? 1 : 2;
        Demand old = queued.get(now.index);
        if (old != null && old.id == now.nativeModelId) old.mask |= mask;
        else if (queued.size() < 512) queued.put(now.index, new Demand(now.index, now.nativeModelId, mask));
    }

    static boolean ready(HologramState state, HologramState.Cell cell, boolean current) {
        boolean budget = resumableBudget(state, cell);
        if (!budget && cell.nativeModelId <= 0 && cell.nativeModelPending == 0) return true;
        boolean ready = !cell.modelPending(current) && !budget;
        if (!ready) requestVisible(state, cell, current);
        // Native connected models may inspect diagonal neighbors; block/meta are already in the cold index.
        if (HologramNativeTiles.supported(cell.block(current || cell.anchor))) {
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                HologramState.Cell neighbor = state.regions.at(cell.x + x, cell.y + y, cell.z + z);
                if (neighbor != null && (neighbor.modelPending(current) || resumableBudget(state, neighbor))) {
                    requestVisible(state, neighbor, current);
                    if (state.authorProjection != null)
                        state.authorProjection.dependency(cell, neighbor.x, neighbor.y, neighbor.z);
                    else dependency(cell, neighbor);
                    ready = false;
                }
            }
        }
        return ready;
    }

    /** A local budget shell is unknown appearance, not a terminal unsupported/known-empty tile. */
    static boolean resumableBudget(HologramState state, HologramState.Cell cell) {
        return state != null && state.authorProjection != null && cell != null && "localBudget".equals(cell.renderKind);
    }

    static void dependency(HologramState.Cell reader, HologramState.Cell input) {
        if (reader == null || input.nativeModelId <= 0) return;
        if (dependencies.size() >= 4096 && !dependencies.containsKey(input.nativeModelId)) return;
        Set<HologramRegionCache.Key> regions = dependencies
            .computeIfAbsent(input.nativeModelId, ignored -> new HashSet<>());
        if (regions.size() < 64) regions.add(HologramRegionCache.key(reader));
    }

    static void receive(NBTTagCompound reply, AutoCloseable ownership, long retainedBytes) {
        boolean accepted = false;
        try {
            Pending request = pending.remove(reply.getString("requestId"));
            if (request == null) return;
            if (!session.equals(reply.getString("session")) || request.generation != reply.getLong("modelGeneration")
                || request.revision != reply.getLong("planRevision")
                || !reply.getString("modelError")
                    .isEmpty()) {
                retry(request);
                return;
            }
            NBTTagList rows = reply.getTagList("rows", 10);
            if (rows.tagCount() > 32 || replies.size() >= 4 || retainedBytes > (32L << 20) - replyBytes) {
                retry(request);
                return;
            }
            replies.addLast(new Reply(reply, ownership, Math.max(0, retainedBytes), request));
            replyBytes += Math.max(0, retainedBytes);
            accepted = true;
        } finally {
            if (!accepted) close(ownership);
        }
    }

    static void tick() {
        HologramState state = HologramClient.state;
        if (!context(state)) {
            if (!session.isEmpty()) clear();
            return;
        }
        long now = System.nanoTime();
        java.util.Iterator<Pending> expired = pending.values()
            .iterator();
        while (expired.hasNext()) {
            Pending request = expired.next();
            if (now - request.started > 5_000_000_000L) {
                expired.remove();
                retry(request);
            }
        }
        if (preparing != null) {
            try {
                if (preparing.step()) {
                    preparing.close();
                    preparing = null;
                }
            } catch (IllegalStateException busy) {
                preparing.close();
                preparing = null;
            }
        }
        parseReply();
        if (preparing == null) startCached();
        flush();
    }

    private static void parseReply() {
        Reply reply = replies.peekFirst();
        if (reply == null) return;
        NBTTagList rows = reply.value.getTagList("rows", 10);
        long deadline = System.nanoTime() + 1_000_000L;
        int work = 0;
        while (reply.position < rows.tagCount() && work++ < 16 && System.nanoTime() < deadline) {
            NBTTagCompound row = rows.getCompoundTagAt(reply.position);
            int at = row.getInteger("index");
            long id = row.getLong("nativeModelId");
            if (!reply.request.contains(at, id)) {
                reply.position++;
                continue;
            }
            NBTTagCompound actual = nativeTag(row, "actualDisplay"), target = nativeTag(row, "targetDisplay");
            if ((actual != null || target != null) && !CACHE.put(id, actual, target)) break;
            reply.position++;
            HologramState state = HologramClient.state;
            if (at >= 0 && at < state.cells.size() && state.cells.get(at).nativeModelId == id) {
                Demand demand = queued.get(at);
                if (demand == null || demand.id != id)
                    queued.put(at, new Demand(at, id, state.cells.get(at).nativeModelPending));
                else demand.failures = 0;
            }
        }
        if (reply.position == rows.tagCount() || System.nanoTime() - reply.started > 2_000_000_000L) {
            replies.removeFirst();
            replyBytes -= reply.bytes;
            reply.close();
        }
    }

    private static NBTTagCompound nativeTag(NBTTagCompound row, String side) {
        NBTTagCompound display = row.getCompoundTag(side);
        return display.hasKey("nativeTile", 10) ? display.getCompoundTag("nativeTile") : null;
    }

    private static void startCached() {
        HologramState state = HologramClient.state;
        List<HologramState.ModelRow> rows = new ArrayList<>();
        List<HologramSnapshotModelCache.View> views = new ArrayList<>();
        java.util.Iterator<Demand> iterator = queued.values()
            .iterator();
        while (iterator.hasNext() && rows.size() < 32) {
            Demand demand = iterator.next();
            if (demand.at >= state.cells.size() || state.cells.get(demand.at).nativeModelId != demand.id) {
                iterator.remove();
                continue;
            }
            int mask = demand.mask & state.cells.get(demand.at).nativeModelPending;
            if (mask == 0) {
                iterator.remove();
                continue;
            }
            HologramSnapshotModelCache.View view = CACHE.get(demand.id, mask);
            if (view == null) continue;
            views.add(view);
            rows.add(new HologramState.ModelRow(demand.at, demand.id, view.actual(), view.target(), false));
        }
        if (rows.isEmpty()) return;
        long headerBytes = 8192L + ((state.cells.size() + 255L) >> 8) * 16L
            + 512L * rows.size()
            + HologramRegionCache.modelRegionCount(state.regions) * 128L;
        HologramProcessGovernor.Lease header = HologramProcessGovernor.tryRetain("snapshot.modelHeader", headerBytes);
        long workingBytes = 4096;
        Set<HologramRegionCache.Key> regions = new HashSet<>();
        for (HologramState.ModelRow row : rows) {
            HologramRegionCache.Key key = HologramRegionCache.key(state.cells.get(row.index));
            if (regions.add(key)) workingBytes += state.regions.regions.get(key)
                .size() * 16L + 512;
        }
        HologramProcessGovernor.Lease working = header == null ? null
            : HologramProcessGovernor.tryRetain("snapshot.modelIndexWorking", workingBytes);
        if (working == null) {
            if (header != null) header.close();
            for (HologramSnapshotModelCache.View view : views) view.close();
            return;
        }
        try {
            preparing = new Batch(state, rows, views, header.transfer(), working.transfer());
            for (HologramState.ModelRow row : rows) queued.remove(row.index);
        } catch (RuntimeException | Error failure) {
            header.close();
            working.close();
            for (HologramSnapshotModelCache.View view : views) view.close();
            throw failure;
        }
    }

    private static void flush() {
        if (pending.size() >= 4) return;
        HologramState state = HologramClient.state;
        List<Demand> requests = new ArrayList<>();
        int mask = 0;
        long now = System.nanoTime();
        for (Demand demand : queued.values()) {
            if (demand.at >= state.cells.size() || state.cells.get(demand.at).nativeModelId != demand.id) continue;
            int needed = demand.mask & state.cells.get(demand.at).nativeModelPending;
            if (needed == 0 || now < demand.retryAt || inFlight(demand.at, demand.id)) continue;
            HologramSnapshotModelCache.View cached = CACHE.get(demand.id, needed);
            if (cached != null) {
                cached.close();
                continue;
            }
            if (mask == 0) mask = (needed & 1) != 0 ? 1 : 2;
            if ((needed & mask) != 0) requests.add(demand);
            if (requests.size() == 32) break;
        }
        if (requests.isEmpty()) return;
        NBTTagCompound request = new NBTTagCompound();
        int[] indices = new int[requests.size()];
        long[] ids = new long[indices.length];
        NBTTagList modelIds = new NBTTagList();
        for (int i = 0; i < indices.length; i++) {
            indices[i] = requests.get(i).at;
            ids[i] = requests.get(i).id;
            NBTTagCompound value = new NBTTagCompound();
            value.setLong("value", ids[i]);
            modelIds.appendTag(value);
        }
        String id = UUID.randomUUID()
            .toString();
        long generation = state.data.getCompoundTag("modelManifest")
            .getLong("generation");
        long revision = state.data.getLong("planRevision");
        request.setString("op", "requestSnapshotModel");
        request.setString("session", session);
        request.setString("requestId", id);
        request.setLong("planRevision", revision);
        request.setLong("modelGeneration", generation);
        request.setInteger("mask", mask);
        request.setIntArray("indices", indices);
        request.setTag("modelIds", modelIds);
        Pending waiting = new Pending(revision, generation, indices, ids);
        pending.put(id, waiting);
        // A valid reply may intentionally contain fewer rows than requested. Give omitted rows a quiet retry window.
        for (Demand demand : requests) demand.retryAt = now + RETRY_BASE_NANOS;
        try {
            sender.accept(request);
        } catch (RuntimeException failure) {
            pending.remove(id);
            retry(waiting);
        }
    }

    private static void retry(Pending request) {
        long now = System.nanoTime();
        for (int i = 0; i < request.indices.length; i++) {
            Demand demand = queued.get(request.indices[i]);
            if (demand == null || demand.id != request.ids[i]) continue;
            demand.failures = Math.min(5, demand.failures + 1);
            demand.retryAt = now + (RETRY_BASE_NANOS << (demand.failures - 1));
        }
    }

    private static boolean inFlight(int at, long id) {
        for (Pending request : pending.values()) if (request.contains(at, id)) return true;
        for (Reply reply : replies) if (reply.request.contains(at, id)) return true;
        return false;
    }

    private static final class Batch implements AutoCloseable {

        final List<HologramState.ModelRow> rows;
        final List<HologramSnapshotModelCache.View> views;
        final HologramState.ModelPreparation preparation;
        HologramProcessGovernor.Lease header, working;

        Batch(HologramState base, List<HologramState.ModelRow> rows, List<HologramSnapshotModelCache.View> views,
            HologramProcessGovernor.Lease header, HologramProcessGovernor.Lease working) {
            this.rows = rows;
            this.views = views;
            this.header = header;
            this.working = working;
            preparation = new HologramState.ModelPreparation(base, rows);
        }

        boolean step() {
            if (!preparation.step(16, 1_000_000L)) return false;
            HologramState previous = HologramClient.state;
            HologramState next = preparation.result(previous);
            if (next != null && next != previous) {
                Set<HologramRegionCache.Key> dirty = new HashSet<>();
                for (HologramState.ModelRow row : rows) {
                    Set<HologramRegionCache.Key> readers = dependencies.remove(row.id);
                    if (readers != null) dirty.addAll(readers);
                }
                HologramJobVisuals.modelOnly(previous, next);
                HologramRenderer.modelSnapshotChanged(previous, next, dirty);
                HologramClient.state = next;
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.currentScreen instanceof HologramScreen)
                    ((HologramScreen) mc.currentScreen).ui.session.modelOnly(previous, next);
                header.close();
                header = null;
                preparation.published();
                next.changedPreviousCells = java.util.Collections.emptyList();
            } else if (next == previous) preparation.published();
            return true;
        }

        public void close() {
            preparation.close();
            if (header != null) header.close();
            if (working != null) working.close();
            header = working = null;
            for (HologramSnapshotModelCache.View view : views) view.close();
        }
    }

    private static final class Demand {

        final int at;
        final long id;
        int mask, failures;
        long retryAt;

        Demand(int at, long id, int mask) {
            this.at = at;
            this.id = id;
            this.mask = mask;
        }
    }

    private static final class Pending {

        final long revision, generation, started = System.nanoTime();
        final int[] indices;
        final long[] ids;

        Pending(long revision, long generation, int[] indices, long[] ids) {
            this.revision = revision;
            this.generation = generation;
            this.indices = indices;
            this.ids = ids;
        }

        boolean contains(int at, long id) {
            for (int i = 0; i < indices.length; i++) if (indices[i] == at && ids[i] == id) return true;
            return false;
        }
    }

    private static final class Reply implements AutoCloseable {

        final NBTTagCompound value;
        final AutoCloseable owner;
        final long bytes, started = System.nanoTime();
        final Pending request;
        int position;

        Reply(NBTTagCompound value, AutoCloseable owner, long bytes, Pending request) {
            this.value = value;
            this.owner = owner;
            this.bytes = bytes;
            this.request = request;
        }

        public void close() {
            HologramSnapshotModelsClient.close(owner);
        }
    }

    private static void close(AutoCloseable owner) {
        if (owner != null) try {
            owner.close();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
