package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual relation candidate counts, cross-page reverse references and shared facts without mutable aliases. */
public final class HologramSegmentAnalysisRegression {

    private HologramSegmentAnalysisRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramSegmentAnalysisRegression.class, args)) return;
        run();
        System.out.println("Segment analysis regression passed");
    }

    public static void run() throws Exception {
        linearRelationsAndDirtyEncoding();
        nativeOrderAndClearGuards();
        quantumFactsRetainOrderAndOwnership();
        exactFactsAndProviderEpoch();
        frozenSizeAndPayloadStayOnOwnerBoundary();
        stagedNbtEditsPublishOnlyDirtyResults();
        persistentDomainsAndReverseClosure();
        componentMergeAndSplitStayLocal();
        detachedDomainAnalysisNeverCallsNativeProviders();
        repeatedFrozenResourceFactsStayBounded();
        boundedFreezeRebuildsExactNativePatch();
        unchangedSanitizersKeepOriginalEncoding();
        detailPagesReadBeyondMetadataSamples();
        detailPageByteBudgetAndOwnership();
    }

    private static void linearRelationsAndDirtyEncoding() throws Exception {
        for (int count : new int[] { 256, 512, 1024 }) {
            HologramPresets.Preset preset = extenders(count, 32);
            List<HologramCoordinatePolicies.Relation> relations = HologramCoordinatePolicies
                .inspect(preset, 7, 100, 64, 200);
            HologramRelationIndex index = new HologramRelationIndex(relations);
            SimpleAssert.eq(32, index.size(), "each inspected relation is indexed once");
            SimpleAssert.eq(
                32,
                index.sourcesTargeting(7, 100 + count - 1, 64, 200)
                    .size(),
                "targets on another logical page retain every source reference");
            SimpleAssert.that(
                index.sourcesTargeting(8, 100 + count - 1, 64, 200)
                    .isEmpty(),
                "a different dimension cannot borrow a reverse reference");
            String untouched = preset.cells.get(count - 1).tileNbt;
            HologramCoordinatePolicies.ApplyWork work = HologramCoordinatePolicies
                .applyIndexed(preset, index, HologramBlueprintProcessing.defaults("self", 0));
            SimpleAssert.eq(2L * count, work.payloads, "payload visits scale only with cell count");
            SimpleAssert.eq(32L, work.relations, "actual relation candidates scale with R rather than N times R");
            SimpleAssert.eq(32L, work.encoded, "only affected payloads are encoded");
            SimpleAssert.that(
                untouched == preset.cells.get(count - 1).tileNbt,
                "unmodified payload preserves the exact original encoded object");
            HologramRelationIndex.Source first = new HologramRelationIndex.Source(0, 0, 0, "tile");
            index.replace(first, Collections.emptyList());
            SimpleAssert.eq(
                31,
                index.sourcesTargeting(7, 100 + count - 1, 64, 200)
                    .size(),
                "staged source removal updates the reverse index without losing other pages");
        }
        HologramPresets.Preset plain = extenders(257, 0);
        String original = plain.cells.get(0).tileNbt;
        HologramCoordinatePolicies.ApplyWork zero = HologramCoordinatePolicies.applyIndexed(
            plain,
            new HologramRelationIndex(Collections.emptyList()),
            HologramBlueprintProcessing.defaults("self", 0));
        SimpleAssert.eq(0L, zero.encoded, "R zero does not re-encode ordinary payloads");
        SimpleAssert.that(original == plain.cells.get(0).tileNbt, "R zero keeps source identity");
        NBTTagCompound old = HologramBlueprintNbt.decode(original);
        old.setTag(HologramCoordinatePolicies.RELATIONS, new NBTTagList());
        plain.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCanonical(old);
        HologramCoordinatePolicies.ApplyWork cleanup = HologramCoordinatePolicies.applyIndexed(
            plain,
            new HologramRelationIndex(Collections.emptyList()),
            HologramBlueprintProcessing.defaults("self", 0));
        SimpleAssert.eq(1L, cleanup.encoded, "retiring a stale empty descriptor is a real mutation");
        SimpleAssert.that(
            !HologramBlueprintNbt.decode(plain.cells.get(0).tileNbt)
                .hasKey(HologramCoordinatePolicies.RELATIONS),
            "stale descriptors are actually removed");
    }

    private static void nativeOrderAndClearGuards() throws Exception {
        HologramPresets.Preset preset = extenders(258, 0);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "fixture:hub");
        NBTTagList bindings = new NBTTagList();
        for (int x : new int[] { 356, 357 }) {
            NBTTagCompound binding = new NBTTagCompound();
            binding.setInteger("x", x);
            binding.setInteger("y", 64);
            binding.setInteger("z", 200);
            binding.setInteger("dim", 7);
            binding.setBoolean("miner", false);
            binding.setBoolean("active", true);
            bindings.appendTag(binding);
        }
        tag.setTag(HologramNativeHubData.NODES, bindings);
        preset.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        List<HologramCoordinatePolicies.Relation> inspected = HologramCoordinatePolicies
            .inspect(preset, 7, 100, 64, 200);
        Collections.reverse(inspected);
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
        HologramCoordinatePolicies.apply(preset, inspected, policy);
        NBTTagList rows = HologramBlueprintNbt.decode(preset.cells.get(0).tileNbt)
            .getTagList(HologramCoordinatePolicies.RELATIONS, 10);
        SimpleAssert.eq(
            1,
            rows.getCompoundTagAt(0)
                .getInteger("slot"),
            "same-source order follows inspected facts");
        SimpleAssert.eq(
            0,
            rows.getCompoundTagAt(1)
                .getInteger("slot"),
            "indexing never sorts native list choices");
        String original = preset.cells.get(0).tileNbt;
        HologramCoordinatePolicies.ApplyWork repeated = HologramCoordinatePolicies
            .applyIndexed(preset, new HologramRelationIndex(inspected), policy);
        SimpleAssert.eq(0L, repeated.encoded, "an identical native decision keeps its encoded payload");
        SimpleAssert
            .that(original == preset.cells.get(0).tileNbt, "identical decision does not publish replacement data");
        policy.coordinateOverrides.put(inspected.get(0).id, "clear");
        rejects(
            () -> HologramCoordinatePolicies.apply(preset, inspected, policy),
            "native hub list cannot mix clear with preserve");
        SimpleAssert.that(original == preset.cells.get(0).tileNbt, "failed payload decision publishes no partial tag");
        HologramBlueprintProcessing.Policy sharing = HologramBlueprintProcessing.defaults("share", 0);
        HologramCoordinatePolicies.apply(preset, inspected, sharing);
        List<HologramCoordinatePolicies.Relation> cleared = HologramCoordinatePolicies.inspect(preset, 7, 100, 64, 200);
        SimpleAssert.that(
            new HologramRelationIndex(cleared).sourcesTargeting(7, 356, 64, 200)
                .isEmpty(),
            "cleared bindings cannot retain a fabricated reverse target");
        HologramPresets.Preset nc = extenders(1, 1);
        List<HologramCoordinatePolicies.Relation> relation = HologramCoordinatePolicies.inspect(nc, 7, 100, 64, 200);
        HologramBlueprintProcessing.Policy clear = HologramBlueprintProcessing.defaults("self", 0);
        clear.coordinateDefault = "clear";
        HologramCoordinatePolicies.apply(nc, relation, clear);
        relation = HologramCoordinatePolicies.inspect(nc, 7, 100, 64, 200);
        HologramBlueprintProcessing.Policy restore = HologramBlueprintProcessing.defaults("self", 0);
        restore.coordinateDefault = "absolute";
        List<HologramCoordinatePolicies.Relation> finalRelations = relation;
        rejects(
            () -> HologramCoordinatePolicies.apply(nc, finalRelations, restore),
            "clear cannot be restored from missing coordinates");
    }

    private static void quantumFactsRetainOrderAndOwnership() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        for (int x : new int[] { 2, 257, 10 }) {
            HologramPresets.Cell cell = cell(x);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString(HologramNetworkBindings.GROUP, "q1");
            tag.setString(HologramNetworkBindings.ROLE, "join");
            tag.setString(HologramNetworkBindings.HINT, "seed");
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
            source.cells.add(cell);
        }
        NBTTagList groups = new NBTTagList();
        groups.appendTag(group("q2", "second", 2, 10));
        groups.appendTag(group("q1", "first", 257));
        HologramPresets.Preset configured = HologramQuantumGroups.configure(source, groups);
        HologramAnalysisFacts facts = new HologramAnalysisFacts(true);
        HologramQuantumGroups.Facts quantum = HologramQuantumGroups.analyze(configured, facts);
        SimpleAssert.that(quantum.confirmed(), "the existing complete fingerprint remains valid");
        long decoded = facts.decoded;
        NBTTagList assigned = quantum.groups();
        SimpleAssert.eq(
            "q1",
            assigned.getCompoundTagAt(0)
                .getString("id"),
            "group ID ordering remains exact");
        SimpleAssert.eq(
            10,
            assigned.getCompoundTagAt(1)
                .getTagList("cells", 10)
                .getCompoundTagAt(0)
                .getInteger("x"),
            "legacy lexical coordinate order is preserved in the fingerprint");
        assigned.getCompoundTagAt(0)
            .setString("name", "mutated client copy");
        NBTTagList nodes = quantum.nodes();
        SimpleAssert.eq(
            2,
            nodes.getCompoundTagAt(0)
                .getInteger("x"),
            "node presentation remains source order");
        nodes.getCompoundTagAt(0)
            .setString("group", "q999");
        SimpleAssert.eq(
            "first",
            quantum.groups()
                .getCompoundTagAt(0)
                .getString("name"),
            "returned group NBT never aliases the retained fact");
        SimpleAssert.eq(
            "q2",
            quantum.nodes()
                .getCompoundTagAt(0)
                .getString("group"),
            "returned node NBT never aliases the retained fact");
        SimpleAssert.eq(decoded, facts.decoded, "groups nodes and confirmed do not parse the same membership again");
        HologramQuantumGroups.Facts uncached = HologramQuantumGroups
            .analyze(configured, new HologramAnalysisFacts(false));
        SimpleAssert.eq(quantum.groups(), uncached.groups(), "cache off retains group data and ordering");
        SimpleAssert.eq(quantum.nodes(), uncached.nodes(), "cache off retains node data and ordering");
        configured.cells.remove(1);
        SimpleAssert.that(
            !HologramQuantumGroups.confirmed(configured),
            "removing a member on another logical page invalidates complete classification");
    }

    private static void exactFactsAndProviderEpoch() throws Exception {
        HologramAnalysisFacts facts = new HologramAnalysisFacts(true);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("setting", 1);
        String encoded = HologramBlueprintNbt.encodeCanonical(tag);
        NBTTagCompound edit = facts.copyForEdit(encoded);
        edit.setInteger("setting", 2);
        SimpleAssert.eq(
            1,
            facts.read(encoded)
                .getInteger("setting"),
            "an edit cannot mutate shared exact input");
        SimpleAssert.eq(1L, facts.decoded, "the same exact blob has one operation-owned decoded input");
        HologramAeBlueprint.Analysis first = facts.ae(Blocks.stone, 0, encoded, facts.read(encoded));
        facts.ae(Blocks.stone, 0, encoded, facts.read(encoded));
        SimpleAssert.eq(1L, facts.analyses, "identical block metadata and payload reuse static analysis");
        first.payload()
            .setInteger("setting", 9);
        SimpleAssert.eq(
            1,
            facts.ae(Blocks.stone, 0, encoded, facts.read(encoded))
                .payload()
                .getInteger("setting"),
            "analysis payload access returns a disposable copy");
        facts.ae(Blocks.stone, 1, encoded, facts.read(encoded));
        facts.ae(Blocks.dirt, 0, encoded, facts.read(encoded));
        SimpleAssert.eq(3L, facts.analyses, "metadata and real block identity remain analysis inputs");
        GTMBExtensions.Registration registration = ResourceContainers
            .register("gtmb:test_analysis_epoch", context -> null);
        try {
            facts.ae(Blocks.stone, 0, encoded, facts.read(encoded));
            SimpleAssert.eq(4L, facts.analyses, "a provider epoch change cannot reuse old analysis");
        } finally {
            registration.close();
        }
        String changed = HologramBlueprintNbt.encodeCanonical(edit);
        facts.ae(Blocks.stone, 0, changed, facts.read(changed));
        SimpleAssert.eq(5L, facts.analyses, "full payload changes cannot borrow an old static fact");
    }

    private static void frozenSizeAndPayloadStayOnOwnerBoundary() throws Exception {
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("setting", 1);
        String encoded = HologramBlueprintNbt.encodeCanonical(tag);
        context.freeze(encoded);
        HologramSnapshotDataContext.enter(context);
        try {
            HologramAnalysisFacts facts = new HologramAnalysisFacts(true);
            facts.copyForEdit(encoded)
                .setInteger("setting", 7);
            SimpleAssert.eq(
                1,
                facts.read(encoded)
                    .getInteger("setting"),
                "detached readers own their cache copies");
            SimpleAssert.that(
                HologramSnapshotDataContext.expandedBytes(encoded) > 0,
                "detached admission reads frozen size without registry resolution");
            NBTTagCompound other = new NBTTagCompound();
            other.setInteger("setting", 2);
            String unknown = HologramBlueprintNbt.encodeData(other);
            rejects(() -> facts.read(unknown), "an unfrozen worker payload cannot resolve new native identities");
        } finally {
            HologramSnapshotDataContext.exit();
            context.close();
        }
    }

    private static void stagedNbtEditsPublishOnlyDirtyResults() throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        HologramPresets.Cell first = cell(0);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "gtswn.fixture");
        tag.setInteger("currentTab", 4);
        tag.setInteger("removable", 1);
        first.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        preset.cells.add(first);
        preset.cells.add(cell(256));
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", 1);
        receipt.setTag(
            "policy",
            HologramBlueprintProcessing.defaults("self", 0)
                .write());
        preset.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
        String original = first.tileNbt, originalReceipt = preset.processingNbt;
        HologramBlueprintProcessing.editNbtValue(preset, new int[] { 0 }, "tile:/currentTab", "int", "4");
        SimpleAssert.that(
            original == first.tileNbt && originalReceipt == preset.processingNbt,
            "no-op configuration edit encodes nothing and does not retire an unchanged receipt");
        HologramBlueprintProcessing.removeNbtPath(preset, Collections.singletonList(0), "tile:/absent");
        SimpleAssert.that(
            original == first.tileNbt && originalReceipt == preset.processingNbt,
            "removing an absent path preserves the exact source and receipt");
        rejects(
            () -> HologramBlueprintProcessing.editNbtValue(preset, new int[] { 0, 1 }, "tile:/currentTab", "int", "5"),
            "all selected inputs must be valid before an edit is published");
        SimpleAssert.that(
            original == first.tileNbt && originalReceipt == preset.processingNbt,
            "a later invalid cell discards all staged value edits");
        rejects(
            () -> HologramBlueprintProcessing.removeNbtPath(preset, java.util.Arrays.asList(0, 99), "tile:/removable"),
            "a later invalid index rejects a multi-cell path removal");
        SimpleAssert.that(
            original == first.tileNbt && originalReceipt == preset.processingNbt,
            "a failed removal cannot publish a partial delta or receipt");
        HologramBlueprintProcessing.editNbtValue(preset, new int[] { 0 }, "tile:/currentTab", "int", "5");
        SimpleAssert.eq(
            5,
            HologramBlueprintNbt.decode(first.tileNbt)
                .getInteger("currentTab"),
            "a fully staged real edit publishes its actual value");
    }

    private static void persistentDomainsAndReverseClosure() throws Exception {
        HologramPresets.Preset source = extenders(520, 32);
        source.cells.get(300).tileNbt = source.cells.get(0).tileNbt;
        HologramAnalysisFacts cold = new HologramAnalysisFacts(true);
        NBTTagCompound baseline = HologramBlueprintProcessing
            .state(source, HologramBlueprintProcessing.defaults("self", 0), 7, 100, 64, 200, cold);
        cold.publish();
        cold.close();
        SimpleAssert.that(
            source.analysisDocument != null && source.analysisDocument.pages.size() == 3,
            "analysis certificates are retained on stable logical pages");
        HologramAnalysisFacts warm = new HologramAnalysisFacts(true);
        NBTTagCompound repeated = HologramBlueprintProcessing
            .state(source, HologramBlueprintProcessing.defaults("self", 0), 7, 100, 64, 200, warm);
        warm.publish();
        warm.close();
        SimpleAssert.eq(baseline, repeated, "persisted domain certificates retain exact reports and ordering");
        SimpleAssert.eq(0L, warm.decoded, "unchanged pages perform zero decoded-input reconstruction");
        SimpleAssert.eq(
            0L,
            source.analysisDocument.computed.getOrDefault(HologramSegmentContract.Domain.REPORT, 0L),
            "unchanged report domains perform zero field reclassification");
        SimpleAssert.that(
            source.analysisDocument.relationAffectedPages.isEmpty(),
            "unchanged reverse relationship dependencies have no dirty segments");
        source.cells.get(519).x = 900;
        HologramAnalysisFacts moved = new HologramAnalysisFacts(true);
        HologramBlueprintProcessing
            .state(source, HologramBlueprintProcessing.defaults("self", 0), 7, 100, 64, 200, moved);
        moved.publish();
        moved.close();
        SimpleAssert.that(
            source.analysisDocument.relationAffectedPages.contains(0L)
                && source.analysisDocument.relationAffectedPages.contains(1L),
            "a moved target invalidates every referring source segment across pages");
        SimpleAssert.eq(
            33,
            source.analysisDocument.relations.sourcesTargeting(7, 619, 64, 200)
                .size(),
            "the reverse index retains original external targets after a target leaves the selection");
        HologramPresets.Preset detached = HologramPresets.copy(source);
        HologramAnalysisFacts off = new HologramAnalysisFacts(false);
        NBTTagCompound exact = HologramBlueprintProcessing
            .state(detached, HologramBlueprintProcessing.defaults("self", 0), 7, 100, 64, 200, off);
        off.publish();
        off.close();
        NBTTagCompound cached = HologramBlueprintProcessing
            .state(source, HologramBlueprintProcessing.defaults("self", 0), 7, 100, 64, 200);
        SimpleAssert.eq(exact, cached, "disabling persistent domains retains exact state and relation semantics");
    }

    private static void componentMergeAndSplitStayLocal() throws Exception {
        java.util.Map<Long, HologramAnalysisComponents.Node> nodes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            HologramPresets.Cell cell = cell(i == 3 ? 100 : i);
            cell.segmentCellId = 1L + 256L * i;
            HologramSegmentDocument.Record record = new HologramSegmentDocument.Record(cell.segmentCellId, cell, true);
            nodes.put(cell.segmentCellId, new HologramAnalysisComponents.Node(cell, record, true, false, "", ""));
        }
        HologramAnalysisComponents cold = new HologramAnalysisComponents(null, nodes);
        HologramAnalysisComponents warm = new HologramAnalysisComponents(cold, nodes);
        SimpleAssert.that(warm.affectedPages.isEmpty(), "unchanged connectivity reuses all component versions");
        HologramAnalysisComponents.Component unrelated = warm.membership.get(769L);
        HologramAnalysisComponents.Component joined = warm.membership.get(1L);
        SimpleAssert.that(joined == warm.membership.get(513L), "AE adjacency connects both ends of the bridge");
        java.util.Map<Long, HologramAnalysisComponents.Node> splitNodes = new java.util.LinkedHashMap<>(nodes);
        splitNodes.remove(257L);
        HologramAnalysisComponents split = new HologramAnalysisComponents(warm, splitNodes);
        SimpleAssert.that(
            split.membership.get(1L) != split.membership.get(513L),
            "removing an edge-bearing member splits the complete old connected component");
        SimpleAssert.that(
            split.affectedPages.containsAll(java.util.Arrays.asList(0L, 1L, 2L)) && !split.affectedPages.contains(3L)
                && split.membership.get(769L) == unrelated,
            "split invalidates all affected pages while the unrelated component stays unchanged");
        HologramAnalysisComponents merged = new HologramAnalysisComponents(split, nodes);
        SimpleAssert.that(
            merged.membership.get(1L) == merged.membership.get(513L) && merged.membership.get(769L) == unrelated,
            "restoring the bridge merges both components without rebuilding the unrelated network");
    }

    private static void detachedDomainAnalysisNeverCallsNativeProviders() throws Exception {
        int[] ownerCalls = { 0 };
        HologramNativePlacement.Adapter guard = new HologramNativePlacement.Adapter() {

            @Override
            public boolean supports(net.minecraft.block.Block block) {
                if (HologramSnapshotDataContext.active())
                    throw new AssertionError("native provider ran on detached reader");
                ownerCalls[0]++;
                return false;
            }

            @Override
            public net.minecraft.tileentity.TileEntity create(net.minecraft.block.Block block, int metadata,
                NBTTagCompound tag, net.minecraft.world.World world) {
                throw new AssertionError("unused native factory");
            }

            @Override
            public List<ItemStack> materials(net.minecraft.block.Block block, int metadata,
                net.minecraft.tileentity.TileEntity tile) {
                throw new AssertionError("unused native material callback");
            }
        };
        HologramNativePlacement.register("gtmb:test_frozen_domain", guard);
        Thread owner = Thread.currentThread();
        int[] codecCalls = { 0 };
        GTMBExtensions.Registration codec = ResourceContainers.register("gtmb:test_frozen_resource_review", value -> {
            if (Thread.currentThread() != owner || HologramSnapshotDataContext.active())
                throw new AssertionError("resource codec ran on a detached metadata reader");
            codecCalls[0]++;
            return null;
        });
        HologramSnapshotDataContext context;
        try {
            context = new HologramSnapshotDataContext();
            HologramPresets.Preset preset = extenders(2, 1);
            NBTTagCompound payload = HologramBlueprintNbt.decode(preset.cells.get(0).tileNbt);
            NBTTagCompound nested = new NBTTagCompound();
            nested.setInteger("configuration", 7);
            payload.setTag("extensionConfig", nested);
            preset.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
            for (HologramPresets.Cell cell : preset.cells) context.freezeBlock(cell);
            SimpleAssert.that(codecCalls[0] > 0, "resource codecs are evaluated while freezing on the owner");
            int frozenCalls = codecCalls[0];
            SimpleAssert.that(ownerCalls[0] > 0, "native identity classification runs on the freezing owner");
            java.util.concurrent.FutureTask<Boolean> worker = new java.util.concurrent.FutureTask<>(() -> {
                HologramSnapshotDataContext.enter(context);
                try (HologramAnalysisFacts facts = new HologramAnalysisFacts(true)) {
                    HologramBlueprintProcessing
                        .state(preset, HologramBlueprintProcessing.defaults("self", 0), 7, 100, 64, 200, facts);
                    HologramBlueprintProcessing.nbtEntries(preset.cells.get(0), facts);
                    facts.publish();
                    return true;
                } finally {
                    HologramSnapshotDataContext.exit();
                }
            });
            new Thread(worker, "GTMB-frozen-domain-test-worker").start();
            try {
                SimpleAssert.that(
                    worker.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    "frozen metadata runs on an actual worker");
            } finally {
                context.close();
            }
            SimpleAssert
                .eq(frozenCalls, codecCalls[0], "detached reports reuse owner resource facts without a codec callback");
            SimpleAssert.that(context.currentProviders(), "the frozen provider baseline is still current");
        } finally {
            HologramNativePlacement.unregister("gtmb:test_frozen_domain", guard);
            codec.close();
        }
        SimpleAssert.that(!context.currentProviders(), "provider replacement retires an unpublished frozen result");
    }

    private static void repeatedFrozenResourceFactsStayBounded() throws Exception {
        Thread owner = Thread.currentThread();
        int[] calls = { 0 };
        GTMBExtensions.Registration codec = ResourceContainers.register("gtmb:test_resource_fact_intern", value -> {
            if (Thread.currentThread() != owner || HologramSnapshotDataContext.active())
                throw new AssertionError("resource fact interning invoked a codec off its owner");
            calls[0]++;
            return null;
        });
        try (HologramSnapshotDataContext context = new HologramSnapshotDataContext()) {
            NBTTagCompound payload = new NBTTagCompound();
            payload.setString("id", "fixture:ordinary");
            NBTTagCompound nested = new NBTTagCompound();
            nested.setInteger("setting", 7);
            payload.setTag("extensionConfig", nested);
            NBTTagList inventory = new NBTTagList();
            inventory.appendTag(new ItemStack(net.minecraft.init.Items.diamond, 3).writeToNBT(new NBTTagCompound()));
            payload.setTag("unknownInventory", inventory);
            String encoded = HologramBlueprintNbt.encodeCaptured(payload);
            HologramPresets.Preset source = new HologramPresets.Preset();
            HologramPresets.Cell first = cell(0);
            first.tileNbt = encoded;
            context.freezeBlock(first);
            source.cells.add(first);
            int initialCalls = calls[0];
            long initialBytes = context.retainedResourceBytes();
            SimpleAssert
                .that(initialCalls > 0 && initialBytes > 0, "the first exact input performs owner resource analysis");
            for (int i = 1; i < 1024; i++) {
                HologramPresets.Cell repeated = cell(i);
                repeated.tileNbt = encoded;
                context.freezeBlock(repeated);
                source.cells.add(repeated);
            }
            SimpleAssert
                .eq(initialCalls, calls[0], "1024 logical references invoke codecs only for the first exact input");
            SimpleAssert.eq(
                initialBytes,
                context.retainedResourceBytes(),
                "identical input adds no retained row or summary bytes");
            SimpleAssert.eq(
                1,
                context.retainedResourceFacts(),
                "exact resource facts are independent of cell count and coordinates");
            java.util.concurrent.FutureTask<NBTTagCompound> worker = new java.util.concurrent.FutureTask<>(() -> {
                HologramSnapshotDataContext.enter(context);
                try {
                    return HologramBlueprintProcessing
                        .state(source, HologramBlueprintProcessing.defaults("self", 0), 0, 0, 64, 0);
                } finally {
                    HologramSnapshotDataContext.exit();
                }
            });
            new Thread(worker, "GTMB-resource-intern-test-worker").start();
            NBTTagCompound state = worker.get(10, java.util.concurrent.TimeUnit.SECONDS);
            SimpleAssert.eq(
                1024L,
                state.getLong("resourceReviewTotal"),
                "interning still charges every logical report reference");
            SimpleAssert.eq(
                1023,
                state.getTagList("resourceReview", 10)
                    .getCompoundTagAt(1023)
                    .getInteger("x"),
                "shared facts receive each consuming cell's actual coordinates");
            SimpleAssert.eq(initialCalls, calls[0], "worker aggregation cannot invoke the interned native codec");
            HologramPresets.Cell metadata = cell(2048);
            metadata.tileNbt = encoded;
            metadata.metadata = 1;
            context.freezeBlock(metadata);
            HologramPresets.Cell damage = cell(2049);
            damage.tileNbt = encoded;
            damage.damage = 1;
            context.freezeBlock(damage);
            HologramPresets.Cell block = cell(2050);
            block.tileNbt = encoded;
            block.block = "minecraft:dirt";
            context.freezeBlock(block);
            HologramPresets.Cell item = cell(2051);
            item.itemNbt = encoded;
            context.freezeBlock(item);
            nested.setInteger("setting", 8);
            payload.setTag("extensionConfig", nested);
            HologramPresets.Cell changed = cell(2052);
            changed.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
            context.freezeBlock(changed);
            SimpleAssert.eq(
                6,
                context.retainedResourceFacts(),
                "block, metadata, damage, scope and full payload remain exact inputs");
            codec.close();
            SimpleAssert.that(!context.currentProviders(), "changing providers retires the entire frozen context");
            try {
                context.freezeBlock(first);
                throw new AssertionError("retired providers reused frozen resource facts");
            } catch (IllegalStateException expected) { /* The caller must create a fresh frozen task. */ }
        } finally {
            codec.close();
        }
    }

    private static void boundedFreezeRebuildsExactNativePatch() throws Exception {
        NBTTagCompound payload = new NBTTagCompound();
        NBTTagCompound stack = new ItemStack(Blocks.diamond_block, 3).writeToNBT(new NBTTagCompound());
        stack.setString(
            HologramBlueprintNbt.ITEM_REGISTRY,
            String.valueOf(
                net.minecraft.item.Item.itemRegistry
                    .getNameForObject(net.minecraft.item.Item.getItemFromBlock(Blocks.diamond_block))));
        stack.setShort("id", (short) 1);
        payload.setTag("stack", stack);
        NBTTagCompound missing = (NBTTagCompound) stack.copy();
        missing.setString(HologramBlueprintNbt.ITEM_REGISTRY, "gtmb:missing_snapshot_fixture");
        payload.setTag("missing", missing);
        NBTTagList covers = new NBTTagList();
        NBTTagCompound cover = new NBTTagCompound();
        cover.setInteger("id", 1);
        cover.setString(HologramBlueprintNbt.COVER_REGISTRY, stack.getString(HologramBlueprintNbt.ITEM_REGISTRY));
        cover.setInteger(HologramBlueprintNbt.COVER_DAMAGE, 7);
        covers.appendTag(cover);
        payload.setTag("gt.covers", covers);
        String encoded = HologramBlueprintNbt.encodeData(payload);
        NBTTagCompound expected = HologramBlueprintNbt.decode(encoded);
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        try {
            SimpleAssert.eq(
                expected,
                context.freeze(encoded),
                "owner-frozen patch matches exact native item and cover resolution");
            for (int i = 0; i < 300; i++) {
                NBTTagCompound unique = new NBTTagCompound();
                unique.setInteger("entry", i);
                unique.setByteArray("data", new byte[512]);
                context.freeze(HologramBlueprintNbt.encodeData(unique));
            }
            SimpleAssert.that(
                context.retainedDecodedBytes() <= 4L * 1024 * 1024,
                "freezing many unique source blobs never pins a whole decoded blueprint");
            AutoCloseable input = context.retain();
            java.util.concurrent.FutureTask<NBTTagCompound> task = new java.util.concurrent.FutureTask<>(() -> {
                HologramSnapshotDataContext.enter(context);
                try {
                    return HologramBlueprintNbt.decode(encoded);
                } finally {
                    HologramSnapshotDataContext.exit();
                    input.close();
                }
            });
            Thread worker = new Thread(task, "gtmb-frozen-patch-regression");
            worker.start();
            SimpleAssert.eq(
                expected,
                task.get(10, java.util.concurrent.TimeUnit.SECONDS),
                "an evicted frozen blob is rebuilt on a worker without changing native IDs or missing markers");
        } finally {
            context.close();
        }
        SimpleAssert.eq(0L, context.retainedDecodedBytes(), "final source owner releases the decoded LRU");
    }

    private static void unchangedSanitizersKeepOriginalEncoding() throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        HologramPresets.Cell cell = cell(0);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "fixture:ordinary");
        tag.setInteger("setting", 7);
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        cell.itemNbt = cell.tileNbt;
        preset.cells.add(cell);
        String tile = cell.tileNbt, item = cell.itemNbt;
        HologramNetworkBindings.sanitize(preset, null);
        SimpleAssert.that(
            tile == cell.tileNbt && item == cell.itemNbt,
            "network sanitation preserves original encodings when no binding changes");
        HologramDeidentification.applyOwned(preset, 1, null, HologramAePolicy.PreparationProgress.NONE);
        SimpleAssert.that(
            tile == cell.tileNbt && item == cell.itemNbt,
            "identity projection preserves exact encodings when no reviewed identity changes");
    }

    private static void detailPagesReadBeyondMetadataSamples() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "fixture:ordinary");
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(new ItemStack(net.minecraft.init.Items.diamond, 3).writeToNBT(new NBTTagCompound()));
        tag.setTag("unknownInventory", inventory);
        String encoded = HologramBlueprintNbt.encodeCaptured(tag);
        for (int i = 0; i < 8300; i++) {
            HologramPresets.Cell cell = cell(i);
            cell.tileNbt = encoded;
            source.cells.add(cell);
        }
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
        NBTTagCompound summary = HologramBlueprintProcessing.state(source, policy, 0, 0, 64, 0);
        SimpleAssert.eq(
            8192,
            summary.getTagList("resourceReview", 10)
                .tagCount(),
            "legacy metadata sample retains its existing limit");
        SimpleAssert
            .eq(8300L, summary.getLong("resourceReviewTotal"), "every logical resource report reference is counted");
        HologramProcessingDetails.summarize(source);
        try (HologramProcessingDetails.Task task = HologramProcessingDetails
            .resource(source, "resourceReview", 8250, 32)) {
            int slices = 0;
            while (!task.step(17, Long.MAX_VALUE))
                SimpleAssert.that(++slices < 100, "resource page continuation makes progress");
            NBTTagCompound page = task.result();
            SimpleAssert.eq(8300L, page.getLong("total"), "on-demand page exposes the complete report total");
            SimpleAssert.eq(
                32,
                page.getTagList("rows", 10)
                    .tagCount(),
                "tail pages are generated from the immutable source");
            SimpleAssert.eq(
                8250,
                page.getTagList("rows", 10)
                    .getCompoundTagAt(0)
                    .getInteger("index"),
                "a page beyond the metadata sample retains actual cell identity");
        }
        try (HologramProcessingDetails.Task task = HologramProcessingDetails
            .resource(source, "resourceReview", 8290, 64)) {
            while (!task.step(17, Long.MAX_VALUE)) {}
            SimpleAssert.eq(
                10,
                task.result()
                    .getTagList("rows", 10)
                    .tagCount(),
                "the last resource page ends at the exact tail");
        }
        try (HologramProcessingDetails.Task task = HologramProcessingDetails
            .resource(source, "resourceReview", 8301, 64)) {
            while (!task.step(17, Long.MAX_VALUE)) {}
            SimpleAssert.eq(
                "offset",
                task.result()
                    .getString("detailError"),
                "resource offsets beyond the tail fail explicitly");
        }
        try (HologramProcessingDetails.Task task = HologramProcessingDetails.cell(source, policy, 8299, 0, 64)) {
            SimpleAssert
                .that(task.step(1, Long.MAX_VALUE), "one selected cell detail completes through owner admission");
            SimpleAssert.that(
                task.result()
                    .getInteger("total") > 0,
                "missing metadata samples are not represented as empty cells");
            SimpleAssert.eq(
                8299,
                task.result()
                    .getCompoundTag("cellMeta")
                    .getInteger("index"),
                "selected tail cell remains exact");
        }
    }

    private static void detailPageByteBudgetAndOwnership() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        HologramPresets.Cell cell = cell(0);
        NBTTagCompound tag = new NBTTagCompound();
        String value = String.join("", Collections.nCopies(4096, "v"));
        for (int i = 0; i < 130; i++) tag.setString("field" + i, value);
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        source.cells.add(cell);
        NBTTagList expected = HologramBlueprintProcessing.nbtEntries(cell);
        int offset = 0, pages = 0;
        while (offset < expected.tagCount()) {
            long before = HologramProcessGovernor.retainedBytes();
            try (HologramProcessingDetails.Task task = HologramProcessingDetails.cell(source, null, 0, offset, 64)) {
                SimpleAssert.eq(
                    before + 256L * 1024,
                    HologramProcessGovernor.retainedBytes(),
                    "an owner detail task reserves its result before producing rows");
                SimpleAssert.that(task.step(1, Long.MAX_VALUE), "bounded cell page finishes on its owner");
                NBTTagCompound page = task.result();
                SimpleAssert.that(
                    HologramSnapshotNbt.estimate(page) <= 256L * 1024,
                    "rows and cell metadata together stay inside the result lease");
                NBTTagList rows = page.getTagList("rows", 10);
                SimpleAssert.that(
                    rows.tagCount() > 0 && rows.tagCount() < 64,
                    "bytes split a row-count page without dropping it");
                SimpleAssert
                    .eq(expected.tagCount(), page.getInteger("total"), "streamed pages retain the exact full count");
                for (int i = 0; i < rows.tagCount(); i++) SimpleAssert.eq(
                    expected.getCompoundTagAt(offset + i)
                        .getString("path"),
                    rows.getCompoundTagAt(i)
                        .getString("path"),
                    "successive byte-limited pages preserve every row in native iteration order");
                offset += rows.tagCount();
                pages++;
            }
        }
        SimpleAssert.that(pages > 1, "all cell rows remain accessible after the first byte-limited page");
        try (HologramProcessingDetails.Task task = HologramProcessingDetails.cell(source, null, 0, offset + 1, 64)) {
            task.step(1, Long.MAX_VALUE);
            SimpleAssert.eq(
                "offset",
                task.result()
                    .getString("detailError"),
                "an offset beyond the tail is rejected");
        }
        NBTTagCompound oversized = new NBTTagCompound();
        oversized.setInteger(String.join("", Collections.nCopies(60000, "k")), 1);
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(oversized);
        try (HologramProcessingDetails.Task task = HologramProcessingDetails.cell(source, null, 0, 0, 64)) {
            java.util.concurrent.FutureTask<Boolean> worker = new java.util.concurrent.FutureTask<>(() -> {
                try {
                    task.step(1, Long.MAX_VALUE);
                    return false;
                } catch (IllegalStateException ownerRequired) {
                    return true;
                }
            });
            new Thread(worker, "GTMB-detail-test-worker").start();
            SimpleAssert.that(
                worker.get(10, java.util.concurrent.TimeUnit.SECONDS),
                "detached workers cannot invoke owner detail readers");
            task.step(1, Long.MAX_VALUE);
            SimpleAssert.eq(
                "rowTooLarge",
                task.result()
                    .getString("detailError"),
                "a single over-budget row has an explicit failure");
            SimpleAssert.eq(
                1,
                task.result()
                    .getInteger("total"),
                "the rejected row is still counted rather than silently lost");
        }
    }

    private static HologramPresets.Preset extenders(int count, int relations) throws IOException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        NBTTagCompound plain = new NBTTagCompound();
        plain.setString("id", "IC2NCInfoPanelExtender");
        plain.setBoolean("partOfScreen", false);
        String common = HologramBlueprintNbt.encodeCanonical(plain);
        for (int i = 0; i < count; i++) {
            HologramPresets.Cell cell = cell(i);
            cell.tileNbt = common;
            if (i < relations) {
                NBTTagCompound tag = (NBTTagCompound) plain.copy();
                tag.setBoolean("partOfScreen", true);
                tag.setInteger("coreX", 100 + count - 1);
                tag.setInteger("coreY", 64);
                tag.setInteger("coreZ", 200);
                cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
            }
            preset.cells.add(cell);
        }
        return preset;
    }

    private static HologramPresets.Cell cell(int x) {
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), x, 0, 0, -1, false);
        cell.block = String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(Blocks.stone));
        return cell;
    }

    private static NBTTagCompound group(String id, String name, int... positions) {
        NBTTagCompound group = new NBTTagCompound();
        group.setString("id", id);
        group.setString("name", name);
        NBTTagList cells = new NBTTagList();
        for (int x : positions) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", x);
            row.setInteger("y", 0);
            row.setInteger("z", 0);
            cells.appendTag(row);
        }
        group.setTag("cells", cells);
        return group;
    }

    private interface Checked {

        void run() throws Exception;
    }

    private static void rejects(Checked operation, String message) throws Exception {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
