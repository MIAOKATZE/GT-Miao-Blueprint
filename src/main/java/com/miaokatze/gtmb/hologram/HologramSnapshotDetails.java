package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Exact revision-bound report pages. A page is information, never material/payment/world authorization. */
final class HologramSnapshotDetails {

    static final int ROWS = 64;
    private static final int PAGE_BYTES = 256 * 1024;
    private static final String[] DOMAINS = { "relations", "nbtKeys", "nbtPaths", "resourceReview", "resourceBlockers",
        "nbtEntries", "cellNbtEntries" };
    private static final Map<UUID, Store> stores = new LinkedHashMap<>();
    private static final Map<UUID, Integer> pending = new LinkedHashMap<>();
    private static final java.util.ArrayDeque<OwnerRequest> ownerRequests = new java.util.ArrayDeque<>();

    private HologramSnapshotDetails() {}

    static void clear() {
        while (!ownerRequests.isEmpty()) ownerRequests.removeFirst()
            .close();
        for (Store store : stores.values()) store.close();
        stores.clear();
        pending.clear();
    }

    static NBTTagCompound publication(HologramSession session, NBTTagCompound full) {
        return publication(session, full, HologramSnapshotCodec.retainedEstimate(full));
    }

    static NBTTagCompound publication(HologramSession session, NBTTagCompound full, long weight) {
        return publication(session, full, weight, null, null);
    }

    static NBTTagCompound publication(HologramSession session, NBTTagCompound full, long weight,
        HologramPresets.Preset source, HologramBlueprintProcessing.Policy policy) {
        if (!HologramNetwork.segmentedPeer(session.player)) return full;
        UUID actor = session.player.getUniqueID();
        Store old = stores.remove(actor);
        if (old != null) old.close();
        while (stores.size() >= 32) {
            UUID first = stores.keySet()
                .iterator()
                .next();
            stores.remove(first)
                .close();
        }
        HologramProcessGovernor.Lease lease = HologramProcessGovernor.tryRetain("snapshot.details", weight);
        Store store = lease == null ? null : new Store(session, full, lease.transfer(), source, policy);
        if (store != null) stores.put(actor, store);
        NBTTagCompound summary = new NBTTagCompound();
        for (Object keyObject : full.func_150296_c()) {
            String key = (String) keyObject;
            if (detailKey(key)) continue;
            if ("processing".equals(key)) {
                NBTTagCompound processing = new NBTTagCompound(), original = full.getCompoundTag(key);
                for (Object childObject : original.func_150296_c()) {
                    String child = (String) childObject;
                    if (!detailKey(child)) processing.setTag(
                        child,
                        original.getTag(child)
                            .copy());
                }
                summary.setTag(key, processing);
            } else summary.setTag(
                key,
                full.getTag(key)
                    .copy());
        }
        NBTTagCompound manifest = new NBTTagCompound();
        manifest.setLong("revision", session.planRevision);
        manifest.setLong("generation", session.localPlanGeneration);
        manifest.setBoolean("available", store != null);
        for (String domain : DOMAINS) manifest.setInteger(domain, list(full, domain).tagCount());
        NBTTagCompound processing = full.getCompoundTag("processing");
        manifest.setInteger(
            "resourceBlockers",
            (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(list(full, "resourceBlockers").tagCount(), processing.getLong("resourceBlockingTotal"))));
        manifest.setInteger(
            "resourceReview",
            (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(list(full, "resourceReview").tagCount(), processing.getLong("resourceReviewTotal"))));
        summary.setTag("detailManifest", manifest);
        return summary;
    }

    static void rootEntries(HologramSession session, NBTTagCompound state, NBTTagList roots) {
        if (!HologramNetwork.segmentedPeer(session.player)) {
            state.setTag("nbtEntries", roots);
            return;
        }
        Store store = stores.get(session.player.getUniqueID());
        if (store == null || !store.current(session)) return;
        HologramProcessGovernor.Lease lease = HologramProcessGovernor
            .tryRetain("snapshot.rootDetails", HologramSnapshotNbt.estimate(roots));
        NBTTagCompound manifest = state.getCompoundTag("detailManifest");
        manifest.setInteger("nbtEntries", roots.tagCount());
        if (lease == null) {
            manifest.setBoolean("available", false);
            return;
        }
        store.rootsLease = lease.transfer();
        store.roots = roots;
    }

    private static boolean detailKey(String key) {
        return "processingRelations".equals(key) || "processingNbtKeys".equals(key)
            || "processingNbtPaths".equals(key)
            || "selectedCellNbtEntries".equals(key)
            || "cellNbtOverrides".equals(key)
            || "relations".equals(key)
            || "nbtKeys".equals(key)
            || "nbtPaths".equals(key)
            || "resourceReview".equals(key)
            || "resourceBlockers".equals(key)
            || "nbtEntries".equals(key)
            || "cellNbtEntries".equals(key);
    }

    private static NBTTagList list(NBTTagCompound full, String domain) {
        int type = "nbtKeys".equals(domain) || "nbtPaths".equals(domain) ? 8 : 10;
        String alias = "relations".equals(domain) ? "processingRelations"
            : "nbtKeys".equals(domain) ? "processingNbtKeys"
                : "nbtPaths".equals(domain) ? "processingNbtPaths" : domain;
        if (full.hasKey(alias, 9)) return full.getTagList(alias, type);
        return full.getCompoundTag("processing")
            .getTagList(domain, type);
    }

    static void request(HologramSession session, NBTTagCompound request) {
        UUID actor = session.player.getUniqueID();
        Store source = stores.get(actor);
        if (source == null || !source.current(session)
            || request.getLong("planRevision") != session.planRevision
            || request.getString("requestId")
                .length() > 64
            || request.getString("domain")
                .length() > 64
            || pending.getOrDefault(actor, 0) >= 4) {
            NBTTagCompound failure = envelope(session, request);
            failure.setString("detailError", source == null ? "unavailable" : "busy");
            HologramNetwork.sendDetail(session.player, failure);
            return;
        }
        long inputBytes = HologramSnapshotNbt.estimate(request.getCompoundTag("policy"));
        HologramProcessGovernor.Lease input = HologramProcessGovernor.tryRetain("snapshot.detailRequest", inputBytes);
        HologramProcessGovernor.Lease output = HologramProcessGovernor
            .tryRetain("snapshot.detailPage", 2L * PAGE_BYTES);
        if (input == null || output == null) {
            if (input != null) input.close();
            if (output != null) output.close();
            NBTTagCompound failure = envelope(session, request);
            failure.setString("detailError", "busy");
            HologramNetwork.sendDetail(session.player, failure);
            return;
        }
        NBTTagCompound frozen = new NBTTagCompound();
        frozen.setString("requestId", request.getString("requestId"));
        frozen.setString("domain", request.getString("domain"));
        frozen.setInteger("offset", request.getInteger("offset"));
        frozen.setInteger("cell", request.getInteger("cell"));
        frozen.setLong("planRevision", request.getLong("planRevision"));
        frozen.setTag(
            "policy",
            request.getCompoundTag("policy")
                .copy());
        input.transfer();
        source.retain();
        HologramSnapshotResponseLease response = new HologramSnapshotResponseLease(output);
        pending.put(actor, pending.getOrDefault(actor, 0) + 1);
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable cleanup = () -> {
            if (cleaned.compareAndSet(false, true)) {
                input.close();
                source.close();
            }
        };
        String requested = frozen.getString("domain");
        if (source.raw != null && ("cell".equals(requested) || "resourceReview".equals(requested)
            || "resourceBlockers".equals(requested)
            || "resourceReady".equals(requested))) {
            ownerRequests.addLast(new OwnerRequest(session, source, frozen, response, cleanup));
            return;
        }
        final String sessionId = session.id;
        final long revision = session.planRevision;
        java.util.concurrent.FutureTask<NBTTagCompound> work = new java.util.concurrent.FutureTask<NBTTagCompound>(
            () -> {
                try (HologramProcessGovernor.Lease cpu = HologramProcessGovernor.acquireHeavyCpu("snapshot.details")) {
                    return page(sessionId, revision, frozen, source);
                } finally {
                    cleanup.run();
                }
            }) {

            public void run() {
                if (!response.start()) {
                    cancel(false);
                    return;
                }
                started.set(true);
                try {
                    super.run();
                } finally {
                    response.finish();
                }
            }

            protected void done() {
                if (!started.get()) cleanup.run();
                if (response.retired()) return;
                com.miaokatze.gtmb.util.ServerTaskScheduler.scheduleServerTask(() -> {
                    pending.computeIfPresent(actor, (key, count) -> count <= 1 ? null : count - 1);
                    if (response.retired() || isCancelled()
                        || !source.current(session)
                        || HologramWorkspace.find(actor) != session) {
                        response.close();
                        return;
                    }
                    try {
                        HologramNetwork.sendDetail(session.player, get(), response);
                    } catch (Exception failed) {
                        response.close();
                    }
                });
            }
        };
        if (!HologramAreaPreparation.execute(work)) work.cancel(false);
    }

    /** Native and AE readers are owner continuations; no Future is awaited on this thread. */
    static void tick() {
        long deadline = System.nanoTime() + 1_000_000L;
        int turns = ownerRequests.size();
        while (turns-- > 0 && !ownerRequests.isEmpty() && System.nanoTime() < deadline) {
            OwnerRequest request = ownerRequests.removeFirst();
            if (request.closed) continue;
            if (!request.source.current(request.session) || HologramWorkspace.find(request.actor) != request.session) {
                request.close();
                continue;
            }
            try {
                if (!request.step(deadline)) ownerRequests.addLast(request);
            } catch (IOException | RuntimeException failed) {
                NBTTagCompound error = envelope(request.session, request.request);
                error.setLong("detailGeneration", request.source.generation);
                error.setString(
                    "detailError",
                    failed instanceof HologramSnapshotCodec.BusyException ? "busy" : "unavailable");
                HologramNetwork.sendDetail(request.session.player, error);
                request.close();
            }
        }
    }

    private static final class OwnerRequest implements AutoCloseable {

        final HologramSession session;
        final UUID actor;
        final Store source;
        final NBTTagCompound request;
        final HologramSnapshotResponseLease response;
        final Runnable cleanup;
        HologramProcessingDetails.Task task;
        int offset;
        boolean closed, transferred;

        OwnerRequest(HologramSession session, Store source, NBTTagCompound request,
            HologramSnapshotResponseLease response, Runnable cleanup) {
            this.session = session;
            actor = session.player.getUniqueID();
            this.source = source;
            this.request = request;
            this.response = response;
            this.cleanup = cleanup;
            offset = request.getInteger("offset");
        }

        boolean step(long deadline) throws IOException {
            String domain = request.getString("domain");
            boolean readiness = "resourceReady".equals(domain);
            if (task == null) task = "cell".equals(domain)
                ? HologramProcessingDetails.cell(source.raw, source.policy, request.getInteger("cell"), offset, ROWS)
                : HologramProcessingDetails.resource(source.raw, readiness ? "resourceReview" : domain, offset, ROWS);
            if (!response.start()) {
                close();
                return true;
            }
            boolean done;
            try {
                done = task.step(Integer.MAX_VALUE, deadline);
            } finally {
                response.finish();
            }
            if (!done) return false;
            NBTTagCompound value = task.result();
            NBTTagCompound reply = envelope(session, request);
            reply.setLong("detailGeneration", source.generation);
            if (!value.getString("detailError")
                .isEmpty()) {
                reply.setString("detailError", value.getString("detailError"));
            } else if (readiness) {
                NBTTagList rows = value.getTagList("rows", 10);
                boolean complete = value.getBoolean("complete");
                boolean selected = resourcesReady(request.getCompoundTag("policy"), rows);
                long total = value.getLong("total");
                if (complete && selected && rows.tagCount() > 0 && (long) offset + rows.tagCount() < total) {
                    offset += rows.tagCount();
                    task.close();
                    task = null;
                    return false;
                }
                reply.setBoolean("ready", complete && selected && (long) offset + rows.tagCount() >= total);
                reply.setBoolean("detailTruncated", !complete);
            } else {
                for (Object keyObject : value.func_150296_c()) {
                    String key = (String) keyObject;
                    if ("total".equals(key))
                        reply.setInteger(key, (int) Math.min(Integer.MAX_VALUE, value.getLong(key)));
                    else reply.setTag(key, value.getTag(key));
                }
                if ("cell".equals(domain)) reply.setInteger("cell", request.getInteger("cell"));
            }
            transferred = true;
            HologramNetwork.sendDetail(session.player, reply, this);
            pending.computeIfPresent(actor, (key, count) -> count <= 1 ? null : count - 1);
            return true;
        }

        public void close() {
            if (closed) return;
            closed = true;
            if (task != null) task.close();
            task = null;
            cleanup.run();
            response.close();
            if (!transferred) pending.computeIfPresent(actor, (key, count) -> count <= 1 ? null : count - 1);
        }
    }

    private static NBTTagCompound envelope(HologramSession session, NBTTagCompound request) {
        return envelope(session.id, session.planRevision, request);
    }

    private static NBTTagCompound envelope(String session, long revision, NBTTagCompound request) {
        NBTTagCompound reply = new NBTTagCompound();
        reply.setBoolean("detailOnly", true);
        reply.setString("session", session);
        reply.setLong("planRevision", revision);
        reply.setString("requestId", request.getString("requestId"));
        reply.setString("domain", request.getString("domain"));
        reply.setInteger("offset", request.getInteger("offset"));
        return reply;
    }

    private static NBTTagCompound page(String sessionId, long revision, NBTTagCompound request, Store store) {
        NBTTagCompound reply = envelope(sessionId, revision, request);
        reply.setLong("detailGeneration", store.generation);
        String domain = request.getString("domain");
        if ("resourceReady".equals(domain)) {
            NBTTagCompound processing = store.full.getCompoundTag("processing");
            boolean truncated = processing.getBoolean("resourceReviewTruncated")
                || processing.hasKey("resourceSummaryComplete") && !processing.getBoolean("resourceSummaryComplete");
            reply.setBoolean("detailTruncated", truncated);
            reply.setBoolean(
                "ready",
                !truncated && resourcesReady(request.getCompoundTag("policy"), store.list("resourceReview")));
        } else {
            boolean cell = "cell".equals(domain);
            NBTTagCompound cellRow = new NBTTagCompound();
            NBTTagList original;
            if (cell) {
                int selected = request.getInteger("cell");
                NBTTagList rows = store.list("cellNbtEntries");
                for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
                    .getInteger("index") == selected) {
                        cellRow = rows.getCompoundTagAt(i);
                        break;
                    }
                if (!cellRow.hasKey("index", 3)) {
                    reply.setString("detailError", "unavailable");
                    return reply;
                }
                original = cellRow.getTagList("entries", 10);
                NBTTagCompound meta = new NBTTagCompound();
                for (Object keyObject : cellRow.func_150296_c()) {
                    String key = (String) keyObject;
                    if (!"entries".equals(key)) meta.setTag(
                        key,
                        cellRow.getTag(key)
                            .copy());
                }
                meta.setInteger("index", selected);
                if (!meta.hasKey("all")) meta.setBoolean("all", true);
                reply.setInteger("cell", selected);
                reply.setTag("cellMeta", meta);
            } else {
                boolean allowed = false;
                for (String known : DOMAINS)
                    if (known.equals(domain) && !"cellNbtEntries".equals(domain)) allowed = true;
                if (!allowed) {
                    reply.setString("detailError", "domain");
                    return reply;
                }
                original = store.list(domain);
            }
            int offset = request.getInteger("offset");
            NBTTagCompound processing = store.full.getCompoundTag("processing");
            long total = "resourceReview".equals(domain) ? processing.getLong("resourceReviewTotal")
                : "resourceBlockers".equals(domain) ? processing.getLong("resourceBlockingTotal") : original.tagCount();
            total = Math.max(total, original.tagCount());
            boolean complete = total == original.tagCount()
                && (!("resourceReview".equals(domain) || "resourceBlockers".equals(domain))
                    || !processing.hasKey("resourceSummaryComplete")
                    || processing.getBoolean("resourceSummaryComplete"));
            reply.setInteger("total", (int) Math.min(Integer.MAX_VALUE, total));
            reply.setBoolean("complete", complete);
            reply.setBoolean("detailTruncated", !complete);
            if (offset < 0 || offset > original.tagCount()) reply.setString("detailError", "offset");
            else {
                NBTTagList page = new NBTTagList();
                long bytes = 0;
                for (int i = offset; i < Math.min(original.tagCount(), offset + ROWS); i++) {
                    NBTBase value = "nbtKeys".equals(domain) || "nbtPaths".equals(domain)
                        ? new net.minecraft.nbt.NBTTagString(original.getStringTagAt(i))
                        : original.getCompoundTagAt(i);
                    long charge = HologramSnapshotNbt.estimate(value);
                    if (charge > PAGE_BYTES - bytes) break;
                    page.appendTag(value.copy());
                    bytes += charge;
                }
                if (page.tagCount() == 0 && offset < original.tagCount()) reply.setString("detailError", "rowTooLarge");
                reply.setTag("rows", page);
            }
        }
        return reply;
    }

    static boolean resourcesReady(NBTTagCompound policy, NBTTagList resources) {
        java.util.Map<String, String> decisions = new java.util.HashMap<>();
        NBTTagList chosen = policy.getTagList("resourceDecisions", 10),
            ignored = policy.getTagList("ignoredNbtKeys", 8);
        java.util.Set<String> roots = new java.util.HashSet<>();
        for (int i = 0; i < ignored.tagCount(); i++) roots.add(ignored.getStringTagAt(i));
        for (int i = 0; i < chosen.tagCount(); i++) {
            NBTTagCompound row = chosen.getCompoundTagAt(i);
            decisions.put(resourceKey(row), row.getString("action"));
        }
        for (int i = 0; i < resources.tagCount(); i++) {
            NBTTagCompound row = resources.getCompoundTagAt(i);
            if (roots.contains(row.getString("rootKey"))) continue;
            String action = decisions.get(resourceKey(row));
            if (action == null && row.getBoolean("preserved")) continue;
            if ("remove".equals(action) || "preserve".equals(action) && row.getBoolean("canPreserve")
                || "cancel".equals(action) && row.getBoolean("canCancel")
                || "migrate".equals(action) && row.getBoolean("canMigrate")) continue;
            return false;
        }
        return true;
    }

    private static String resourceKey(NBTTagCompound row) {
        return row.getInteger(
            "x") + "," + row.getInteger("y") + "," + row.getInteger("z") + ":" + row.getString("path");
    }

    private static final class Store implements AutoCloseable {

        final String session;
        final long revision, generation, resources, nativeProvider;
        final NBTTagCompound full;
        final HologramPresets.Preset raw;
        final HologramBlueprintProcessing.Policy policy;
        volatile NBTTagList roots;
        HologramProcessGovernor.Lease rootsLease;
        private final java.util.concurrent.atomic.AtomicInteger references = new java.util.concurrent.atomic.AtomicInteger(
            1);
        final HologramProcessGovernor.Lease lease;

        Store(HologramSession owner, NBTTagCompound full, HologramProcessGovernor.Lease lease,
            HologramPresets.Preset raw, HologramBlueprintProcessing.Policy policy) {
            this.raw = raw;
            this.policy = policy;
            session = owner.id;
            revision = owner.planRevision;
            generation = owner.localPlanGeneration;
            resources = com.miaokatze.gtmb.api.ResourceContainers.generation();
            nativeProvider = HologramNativePlacement.generation();
            this.full = full;
            this.lease = lease;
        }

        boolean current(HologramSession owner) {
            return session.equals(owner.id) && revision == owner.planRevision
                && generation == owner.localPlanGeneration
                && resources == com.miaokatze.gtmb.api.ResourceContainers.generation()
                && nativeProvider == HologramNativePlacement.generation();
        }

        NBTTagList list(String domain) {
            return "nbtEntries".equals(domain) && roots != null ? roots : HologramSnapshotDetails.list(full, domain);
        }

        void retain() {
            references.incrementAndGet();
        }

        public void close() {
            if (references.decrementAndGet() == 0) {
                lease.close();
                if (rootsLease != null) rootsLease.close();
            }
        }
    }
}
