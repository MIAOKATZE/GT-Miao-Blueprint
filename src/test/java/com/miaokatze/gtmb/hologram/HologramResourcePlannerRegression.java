package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Cooperative planner slices, exact indexed resource accounting, and sync/staged plan parity. */
public final class HologramResourcePlannerRegression {

    private HologramResourcePlannerRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramResourcePlannerRegression.class, args)) return;
        run();
        System.out.println("Hologram staged resource planner regressions passed");
    }

    static void run() {
        try {
            budget();
            exterior();
            exactAccumulator();
            stagedParity();
        } catch (Throwable failure) {
            throw new AssertionError("staged resource planner", failure);
        }
    }

    private static void budget() {
        AtomicLong clock = new AtomicLong();
        HologramPlanBudget count = new HologramPlanBudget(3, 100, clock::get);
        SimpleAssert.that(count.take() && count.take() && count.take() && !count.take(), "operation cap is strict");
        HologramPlanBudget time = new HologramPlanBudget(100, 5, clock::get);
        SimpleAssert.that(time.take(), "first operation has time");
        clock.set(5);
        SimpleAssert.that(!time.take(), "deadline blocks next operation without sleeping");
        SimpleAssert.that(!new HologramPlanBudget(0, 10, clock::get).take(), "zero count makes no progress");
        SimpleAssert.that(!new HologramPlanBudget(10, 0, clock::get).take(), "zero time makes no progress");
    }

    private static void exterior() {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < 9; x++) for (int y = 0; y < 9; y++) for (int z = 0; z < 9; z++) {
            boolean wall = x == 0 || x == 8 || y == 0 || y == 8 || z == 0 || z == 8;
            cells.add(new int[] { x, y, z, wall ? 1 : 0 });
        }
        for (boolean blocked : new boolean[] { false, true }) {
            HologramHatchGeometry.Air air = (x, y, z) -> !blocked || x >= 0;
            int[] airReads = { 0 };
            HologramExteriorTask task = new HologramExteriorTask(cells, (x, y, z) -> {
                airReads[0]++;
                return air.test(x, y, z);
            });
            int calls = 0;
            while (!task.done() && calls++ < 100000) {
                int before = airReads[0];
                task.advance();
                SimpleAssert.that(airReads[0] - before <= 1, "exterior operation performs at most one world air probe");
                SimpleAssert.that(task.completed() <= task.total(), "exterior progress is factual");
            }
            SimpleAssert.that(task.done(), "exterior proof terminates");
            SimpleAssert.eq(
                HologramHatchGeometry.exterior(cells, air),
                task.result(),
                "resumed flood preserves cavities and boundary policy");
        }
        cells.clear();
        cells.add(new int[] { -30000000, 64, -30000000 });
        cells.add(new int[] { 29999999, 64, 29999999 });
        HologramExteriorTask sparse = new HologramExteriorTask(
            cells,
            (x, y, z) -> { throw new AssertionError("huge sparse bounds must never probe the world"); });
        while (!sparse.done()) sparse.advance();
        SimpleAssert.that(
            sparse.result()
                .isEmpty(),
            "huge sparse declarations retain no exterior proof");
    }

    private static void exactAccumulator() throws Exception {
        HologramBlueprintResources.Accumulator accumulator = new HologramBlueprintResources.Accumulator();
        HologramBlueprintResources.Plan expected = new HologramBlueprintResources.Plan();
        for (int i = 0; i < 50; i++) {
            ItemStack item = new ItemStack(Items.diamond, i + 1);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("identity", i % 7);
            tag.setString("strict", "payload");
            item.setTagCompound(tag);
            HologramBlueprintResources.Plan source = new HologramBlueprintResources.Plan()
                .addNativeItems(java.util.Collections.singletonList(item));
            source.energy = java.math.BigInteger.valueOf(i);
            HologramBlueprintResources.Accumulator.Merge merge = accumulator.merge(source);
            while (!merge.done()) merge.advance();
            expected.merge(source);
        }
        assertItems(expected.items, accumulator.plan.items);
        SimpleAssert.eq(expected.energy, accumulator.plan.energy, "energy merge is exact");
        // A public source can be edited between merges. The accumulator never caches source-list identities.
        HologramBlueprintResources.Plan mutable = new HologramBlueprintResources.Plan();
        mutable.items.add(new ItemStack(Items.stick, 11));
        HologramBlueprintResources.Accumulator.Merge first = accumulator.merge(mutable);
        while (!first.done()) first.advance();
        mutable.items.clear();
        mutable.items.add(new ItemStack(Items.apple, 13));
        HologramBlueprintResources.Accumulator.Merge second = accumulator.merge(mutable);
        while (!second.done()) second.advance();
        SimpleAssert.eq(11, accumulator.plan.items.get(7).stackSize, "first externally mutable list is copied");
        SimpleAssert.eq(13, accumulator.plan.items.get(8).stackSize, "later list mutation cannot use a stale index");

        int size = 12000;
        HologramBlueprintResources.Plan distinct = new HologramBlueprintResources.Plan();
        for (int i = 0; i < size; i++) {
            ItemStack item = new ItemStack(Items.diamond);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("distinct", i);
            item.setTagCompound(tag);
            distinct.items.add(item);
        }
        HologramBlueprintResources.Accumulator stress = new HologramBlueprintResources.Accumulator();
        HologramBlueprintResources.Accumulator.Merge merge = stress.merge(distinct);
        long operations = 0;
        while (!merge.done()) {
            merge.advance();
            operations++;
        }
        SimpleAssert.eq(size, stress.plan.items.size(), "distinct payloads stay distinct");
        SimpleAssert.eq((long) size + 2, operations, "distinct-resource merge has one indexed operation per item");

        ItemStack main = new ItemStack(Items.diamond, 3);
        NBTTagCompound exact = new NBTTagCompound();
        exact.setString("identity", "native bill");
        main.setTagCompound(exact);
        List<ItemStack> components = java.util.Collections.singletonList(main);
        ItemStack paid = main.copy();
        paid.stackSize = 1;
        List<ItemStack> extra = HologramNativePlacement.additionalMaterials(Blocks.stone, components, paid);
        SimpleAssert.eq(2, extra.get(0).stackSize, "reused exact native bill pays the primary shell once");
        SimpleAssert.eq(3, main.stackSize, "subtracting a primary never mutates the original native credits");
        paid.getTagCompound()
            .setString("identity", "different full NBT");
        boolean rejected = false;
        try {
            HologramNativePlacement.additionalMaterials(Blocks.stone, components, paid);
        } catch (java.io.IOException expectedFailure) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "same item/damage with different complete NBT is never the paid primary");

        ItemStack bytesA = new ItemStack(Items.diamond), bytesB = new ItemStack(Items.diamond);
        NBTTagCompound tagA = new NBTTagCompound(), tagB = new NBTTagCompound();
        tagA.setByteArray("bytes", new byte[] { 0, 31 });
        tagB.setByteArray("bytes", new byte[] { 1, 0 });
        bytesA.setTagCompound(tagA);
        bytesB.setTagCompound(tagB);
        HologramBlueprintResources.ExactKey keyA = HologramValues.stackKey(bytesA);
        HologramBlueprintResources.ExactKey keyB = HologramValues.stackKey(bytesB);
        SimpleAssert.eq(keyA.hashCode(), keyB.hashCode(), "byte-array fixture deliberately collides by hash");
        SimpleAssert.that(!keyA.equals(keyB), "equal-length byte arrays require complete structural equality");
        java.util.Map<HologramBlueprintResources.ExactKey, Long> counts = new java.util.HashMap<>();
        HologramValues.mergeStack(counts, bytesA, 1L, Long::sum);
        HologramValues.mergeStack(counts, bytesB, 1L, Long::sum);
        ItemStack equal = bytesA.copy();
        equal.stackSize = 57;
        HologramValues.mergeStack(counts, equal, 1L, Long::sum);
        SimpleAssert.eq(2, counts.size(), "different byte payload bills stay distinct despite a hash collision");
        SimpleAssert.eq(
            Long.valueOf(2),
            counts.get(HologramValues.lookupKey(equal)),
            "equal full payload copies merge independently of stack size");
        bytesA.getTagCompound()
            .setByteArray("bytes", new byte[] { 9, 9 });
        SimpleAssert.that(keyA.matches(equal), "admitted key owns its payload after the source stack changes");
        SimpleAssert.eq(Long.valueOf(2), counts.get(keyA), "source mutation cannot corrupt material map membership");
    }

    private static void stagedParity() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.isCreativeMode = true;
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        java.util.UUID identity = new java.util.UUID(111, 222);
        for (Field entityIdentity : net.minecraft.entity.Entity.class.getDeclaredFields())
            if (entityIdentity.getType() == java.util.UUID.class
                && !java.lang.reflect.Modifier.isStatic(entityIdentity.getModifiers())) {
                    entityIdentity.setAccessible(true);
                    entityIdentity.set(player, identity);
                }
        SimpleAssert
            .eq(identity, player.getUniqueID(), "unsafe player has a real entity UUID before workspace admission");
        for (Field profile : net.minecraft.entity.player.EntityPlayer.class.getDeclaredFields())
            if (profile.getType() == com.mojang.authlib.GameProfile.class) {
                profile.setAccessible(true);
                profile.set(player, new com.mojang.authlib.GameProfile(identity, "planner"));
            }
        HologramSparseAirRegression.run(player);
        HologramSession sync = session(player, 2048);
        HologramSession staged = session(player, 2048);
        HologramResourcePlanner.refreshPlan(sync);
        HologramResourcePlanner.PlanTask task = HologramResourcePlanner.beginPlan(staged);
        SimpleAssert.eq(0, staged.planStatus.size(), "constructing a task performs no planning");
        SimpleAssert.that(!task.step(0, 1000000000L), "zero budget cannot mutate staging");
        SimpleAssert.eq(0, staged.planStatus.size(), "zero budget leaves all cells unclassified");
        Throwable[] wrongThread = { null };
        Thread worker = new Thread(() -> {
            try {
                task.step(1, 1000000000L);
            } catch (Throwable rejected) {
                wrongThread[0] = rejected;
            }
        });
        worker.start();
        worker.join();
        SimpleAssert.that(wrongThread[0] instanceof IllegalStateException, "worker cannot perform world planning");
        int slices = 0;
        java.util.Set<String> phases = new java.util.HashSet<>();
        while (!task.done() && slices++ < 20000) {
            task.step(23, 1000000000L);
            SimpleAssert.that(task.lastWork() <= 23, "entire planner chain obeys cell/edge/entry budget");
            SimpleAssert.that(task.completed() <= task.total(), "planner phase progress never exceeds total");
            phases.add(task.phase());
        }
        SimpleAssert.that(task.done() && slices > 100, "large plan completes over many bounded slices");
        SimpleAssert.that(
            phases.contains("plan.index") && phases.contains("plan.classify")
                && phases.contains("facing.cells")
                && phases.contains("resources.cells"),
            "all heavy tail stages yield");
        SimpleAssert.that(!phases.contains("facing.exterior"), "a block-only plan skips the unused exterior proof");
        SimpleAssert.eq(sync.planStatus, staged.planStatus, "staged statuses match synchronous planner");
        assertItems(sync.planTargets, staged.planTargets);
        SimpleAssert.eq(sync.planFacings, staged.planFacings, "staged facing allocation matches synchronous planner");
        SimpleAssert.eq(sync.planMissing, staged.planMissing, "material shortage parity");
        SimpleAssert.eq(sync.planRecoveryFits, staged.planRecoveryFits, "recovery audit parity");
        SimpleAssert.eq(sync.expectedBlocks, staged.expectedBlocks, "world snapshot parity");
        SimpleAssert.eq(sync.planRevision, staged.planRevision, "one plan revision per staged import");
        long revision = staged.planRevision;
        SimpleAssert.that(task.step(23, 1000000000L), "completed task remains complete");
        SimpleAssert.eq(revision, staged.planRevision, "completed task cannot repeat planning");

        player.capabilities.isCreativeMode = false;
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 40);
        for (boolean fuzzy : new boolean[] { false, true }) {
            HologramSession nativeSync = payloadSession(player, fuzzy);
            HologramSession nativeStaged = payloadSession(player, fuzzy);
            HologramResourcePlanner.refreshPlan(nativeSync);
            HologramResourcePlanner.PlanTask nativeTask = HologramResourcePlanner.beginPlan(nativeStaged);
            int ticks = 0;
            while (!nativeTask.done() && ticks++ < 20000) {
                nativeTask.step(7, 1000000000L);
                SimpleAssert.that(nativeTask.lastWork() <= 7, "schema2 material phases obey slice budget");
                SimpleAssert.that(nativeTask.completed() <= nativeTask.total(), "schema2 material progress is factual");
            }
            SimpleAssert.that(nativeTask.done(), "survival payload plan finishes");
            SimpleAssert.eq(nativeSync.planStatus, nativeStaged.planStatus, "payload status parity");
            SimpleAssert.eq(
                nativeSync.pendingAssetBudget,
                nativeStaged.pendingAssetBudget,
                "shared pending-asset reservation parity");
            SimpleAssert
                .eq(nativeSync.cellAssetBudgets, nativeStaged.cellAssetBudgets, "per-cell asset reservation parity");
            SimpleAssert.eq(nativeSync.planMissing, nativeStaged.planMissing, "exact/fuzzy shortage parity");
            SimpleAssert
                .that(nativeStaged.planMissing > 0, "shell and internal inventory cannot spend the same material");
            SimpleAssert.eq(
                nativeSync.blueprintResourceMissing,
                nativeStaged.blueprintResourceMissing,
                "shortage reason order is preserved");
            SimpleAssert
                .eq(nativeSync.planRecoveryFits, nativeStaged.planRecoveryFits, "payload recovery audit parity");
            assertItems(nativeSync.blueprintResources.items, nativeStaged.blueprintResources.items);
            assertItems(nativeSync.blueprintTotalResources.items, nativeStaged.blueprintTotalResources.items);
            SimpleAssert.eq(
                nativeSync.blueprintResources.entries(nativeSync.materialContext),
                nativeStaged.blueprintResources.entries(nativeStaged.materialContext),
                "native resource row order and totals are preserved");
        }
        deferredLifecycle(player);
        classifierReuse(player, world);
        authoringPlan(player, unsafe);
        currentCellPreparation(player);
        aeReadyReaders(player);
    }

    private static void currentCellPreparation(EntityPlayerMP player) throws Exception {
        HologramSession s = payloadSession(player, false);
        HologramCapture.Cell cell = s.capture.cells.get(0);
        NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
        payload.setLong("mStoredEnergy", 17);
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        HologramEnergyPayload.Prepared[] prepared = new HologramEnergyPayload.Prepared[1];
        HologramBlueprintResources.Plan resources = HologramCellPolicy.resourcePlan(s, cell, prepared);
        SimpleAssert.that(prepared[0] != null, "a resource miss returns its current-cell preparation");
        HologramEnergyPayload.Prepared independent = HologramCellPolicy.cachePlan(s, cell, 0);
        SimpleAssert.eq(independent.tilePaid, prepared[0].tilePaid, "reused paid tile preserves complete payload");
        SimpleAssert.eq(independent.tileEmpty, prepared[0].tileEmpty, "resource parsing preserves the empty tile");
        SimpleAssert.eq(independent.itemPaid, prepared[0].itemPaid, "reused paid item preserves complete payload");
        SimpleAssert.eq(independent.itemEmpty, prepared[0].itemEmpty, "reused empty item preserves complete payload");
        SimpleAssert
            .eq(java.math.BigInteger.valueOf(17), prepared[0].demand.eu, "reuse retains the paid energy demand");
        HologramEnergyPayload.Prepared[] hit = new HologramEnergyPayload.Prepared[1];
        SimpleAssert.that(
            HologramCellPolicy.resourcePlan(s, cell, hit) == resources && hit[0] == null,
            "a resource hit retains the original plan without retaining an expanded tree");
        cell.itemNbt = HologramBlueprintNbt.encodeCaptured(new NBTTagCompound());
        HologramEnergyPayload.Prepared[] changed = new HologramEnergyPayload.Prepared[1];
        HologramCellPolicy.resourcePlan(s, cell, changed);
        SimpleAssert.that(changed[0] != null, "changed item payload invalidates the resource key");

        cell.tileNbt = "invalid compressed NBT";
        String error;
        try {
            HologramCellPolicy.resourcePlan(s, cell, new HologramEnergyPayload.Prepared[1]);
            throw new AssertionError("invalid resource input must fail");
        } catch (java.io.IOException invalid) {
            error = invalid.getMessage();
        }
        HologramCellPolicy.ResourcePlanKey failedKey = cell.resourcePlanKey;
        HologramEnergyPayload.Prepared[] failedHit = new HologramEnergyPayload.Prepared[1];
        try {
            HologramCellPolicy.resourcePlan(s, cell, failedHit);
            throw new AssertionError("a cached resource failure must still fail");
        } catch (java.io.IOException invalid) {
            SimpleAssert.eq(error, invalid.getMessage(), "cached resource failure keeps its original message");
            SimpleAssert.that(
                cell.resourcePlanKey == failedKey && failedHit[0] == null,
                "a failed-key hit does not retry preparation");
        }

        boolean creative = player.capabilities.isCreativeMode;
        player.capabilities.isCreativeMode = true;
        try {
            for (boolean staged : new boolean[] { false, true }) {
                HologramSession failing = session(player, 1);
                failing.virtualPreset.schema = 2;
                failing.mode = 0;
                failing.materialSettings.containers = failing.materialSettings.me = false;
                failing.materialContext = new HologramMaterials.Context(player, failing.materialSettings);
                HologramCapture.Cell invalid = failing.capture.cells.get(0);
                invalid.tileNbt = "invalid compressed NBT";
                ItemStack target = HologramCellPolicy.targetFor(failing, invalid, 0);
                // Seed an exact hit to isolate a later preparation failure from resource parsing.
                invalid.resourcePlanKey = new HologramCellPolicy.ResourcePlanKey(
                    failing,
                    invalid,
                    target,
                    HologramNativePlacement.adapter(invalid.block));
                invalid.resourcePlan = new HologramBlueprintResources.Plan()
                    .addNativeItems(java.util.Collections.singletonList(new ItemStack(Items.diamond, 3)));
                if (staged) {
                    HologramResourceTask task = new HologramResourceTask(failing, new HologramPlanClassifier(failing));
                    for (int steps = 0; !task.done() && steps < 100; steps++) task.advance();
                    SimpleAssert.that(task.done(), "a failed prepared hit cannot stall the staged resource report");
                } else HologramResourcePlanner.refreshResources(failing);
                SimpleAssert.that(
                    !failing.planRecoveryFits && !failing.blueprintResourceMissing.isEmpty(),
                    "a later preparation failure remains visible in either resource report");
                SimpleAssert.eq(1, failing.blueprintResources.items.size(), "failure keeps the already merged bill");
                SimpleAssert
                    .eq(3, failing.blueprintResources.items.get(0).stackSize, "partial resource count is exact");
            }
        } finally {
            player.capabilities.isCreativeMode = creative;
        }
    }

    private static void aeReadyReaders(EntityPlayerMP player) throws Exception {
        HologramSession s = session(player, 1);
        s.virtualPreset.schema = 2;
        s.virtualPreset.deidentificationLevel = 1;
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 1);
        policy.quantum = "clear";
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("complete", true);
        receipt.setTag("policy", policy.write());
        s.virtualPreset.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
        HologramCapture.Cell cell = s.capture.cells.get(0);
        SimpleAssert.that(HologramBuildPreparation.aeReady(s), "ordinary valid payload remains ready");
        NBTTagCompound binding = new NBTTagCompound();
        binding.setString(HologramNetworkBindings.GROUP, "q1");
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(binding);
        SimpleAssert.that(!HologramBuildPreparation.aeReady(s), "an active missing network binding still blocks");
        SimpleAssert.that(s.networkBindingRequested && s.status.contains("q1"), "binding failure retains its feedback");
        cell.excluded = true;
        SimpleAssert.that(HologramBuildPreparation.aeReady(s), "excluded cells do not demand a network binding");
        cell.excluded = false;
        HologramPresets.Structure disabled = new HologramPresets.Structure();
        disabled.id = cell.structureId = "disabled";
        disabled.enabled = false;
        s.virtualPreset.structures.add(disabled);
        SimpleAssert.that(HologramBuildPreparation.aeReady(s), "disabled memberships do not demand a binding");
        cell.tileNbt = "invalid compressed NBT";
        SimpleAssert.that(!HologramBuildPreparation.aeReady(s), "disabled malformed payload is still decoded");
        SimpleAssert.eq("AE 蓝图净化报告无效，保持蓝图；请重新审查后施工", s.status, "disabled decode keeps generic failure");
        cell.excluded = true;
        SimpleAssert.that(!HologramBuildPreparation.aeReady(s), "excluded malformed payload is still decoded");
        SimpleAssert.eq("AE 蓝图净化报告无效，保持蓝图；请重新审查后施工", s.status, "excluded decode keeps generic failure");
        cell.excluded = false;
        disabled.enabled = true;
        s.networkBindingRequested = false;
        SimpleAssert.that(!HologramBuildPreparation.aeReady(s), "active malformed payload remains blocked");
        SimpleAssert.that(s.networkBindingRequested, "active decode failure retains the binding-request branch");
    }

    private static void authoringPlan(EntityPlayerMP player, sun.misc.Unsafe unsafe) throws Exception {
        net.minecraft.world.World previousWorld = player.worldObj;
        double previousY = player.posY;
        HologramLocalEditRegression.CountingWorld world = (HologramLocalEditRegression.CountingWorld) unsafe
            .allocateInstance(HologramLocalEditRegression.CountingWorld.class);
        player.worldObj = world;
        player.posY = 64;
        try {
            HologramSession sync = session(player, 128), staged = session(player, 128);
            for (HologramSession draft : new HologramSession[] { sync, staged }) {
                draft.virtualPreset.schema = 2;
                draft.virtualPlacement = false;
                // This detached fixture has no FML/AE registries; exercise real main-inventory planning.
                draft.materialSettings.containers = false;
                draft.materialSettings.me = false;
                draft.expectedBlocks.add(Blocks.dirt);
                draft.planStatus.add("pending");
                draft.replacementAuthorized = draft.placementReviewIssued = draft.forcePlacementReady = true;
                draft.blueprintResources = new HologramBlueprintResources.Plan();
                draft.blueprintResources.addItems(java.util.Collections.singletonList(new ItemStack(Blocks.dirt)));
            }
            HologramResourcePlanner.refreshPlan(sync);
            HologramResourcePlanner.PlanTask task = HologramResourcePlanner.beginPlan(staged);
            java.util.Set<String> phases = new java.util.HashSet<>();
            for (int slices = 0; !task.done() && slices < 1000; slices++) {
                task.step(17, 1000000000L);
                phases.add(task.phase());
            }
            SimpleAssert.that(task.done(), "authoring projection completes with bounded target/facing work");
            SimpleAssert.eq(0, world.reads, "author draft planning performs no live world matching");
            SimpleAssert.that(
                !phases.contains("plan.classify") && !phases.contains("resources.cells"),
                "author planning skips differential and resource phases");
            assertItems(sync.planTargets, staged.planTargets);
            SimpleAssert.eq(sync.planFacings, staged.planFacings, "author target/facing parity");
            for (HologramSession draft : new HologramSession[] { sync, staged }) {
                SimpleAssert.that(
                    draft.expectedBlocks.isEmpty() && draft.planStatus.isEmpty(),
                    "author projection cannot retain a stale world plan");
                SimpleAssert.that(
                    draft.blueprintResources.items.isEmpty() && draft.pendingAssetBudget.isEmpty()
                        && draft.materialContext == null,
                    "author projection clears material quotes and connections");
                SimpleAssert.that(
                    !draft.replacementAuthorized && !draft.placementReviewIssued
                        && !draft.forcePlacementReady
                        && !draft.localPlanPending,
                    "author mode holds no construction authorization or fabricated pending task");
            }
            sync.cellDeltaProtocol = true;
            sync.processingSource = HologramPresets.copy(sync.virtualPreset);
            sync.processingPolicy = HologramBlueprintProcessing.defaults("self", 0);
            HologramLocalPlanRefresh.snapshotBegin(sync);
            for (int i = 0; i < sync.capture.cells.size(); i++) {
                HologramLocalPlanRefresh.snapshotSeedDefaults(sync, i);
                HologramLocalPlanRefresh.snapshotSeedSource(sync, i);
                HologramLocalPlanRefresh.snapshotDraftCell(sync, i, new NBTTagCompound());
            }
            NBTTagCompound baseline = new NBTTagCompound();
            baseline.setInteger("total", sync.capture.cells.size());
            HologramLocalPlanRefresh.snapshotComplete(sync, baseline);
            sync.pins.put(1, new ItemStack(Blocks.dirt));
            SimpleAssert.that(
                HologramLocalPlanRefresh.refresh(sync, java.util.Collections.singleton(1)),
                "a stable author edit updates only its owned targets without a deferred whole plan");
            NBTTagCompound delta = HologramLocalPlanRefresh.delta(sync);
            SimpleAssert.that(
                delta != null && delta.getBoolean("quotesIncomplete")
                    && !delta.getBoolean("planIncomplete")
                    && !delta.hasKey("materials")
                    && !delta.hasKey("blueprintResources"),
                "author delta does not claim a live material quote");
            SimpleAssert.eq(0, world.reads, "sparse author edit and its cell delta perform no live world reads");
            SimpleAssert.that(
                HologramWorldPlacementWorkflow.beginVirtualPlacement(sync, sync.x, sync.y, sync.z, sync.side),
                "the real placement entry accepts the authored draft");
            SimpleAssert.that(
                world.reads > 0 && sync.expectedBlocks.size() == 128 && sync.planStatus.size() == 128,
                "placement entry performs fresh live matching before issuing a construction plan");
        } finally {
            player.worldObj = previousWorld;
            player.posY = previousY;
        }
    }

    private static void classifierReuse(EntityPlayerMP player, HologramServiceEditRegression.ReadOnlyWorld world)
        throws Exception {
        HologramSession s = session(player, 1);
        s.virtualPreset.schema = 2;
        HologramCapture.Cell cell = s.capture.cells.get(0);
        cell.userPosition = true;
        HologramPlanClassifier classifier = new HologramPlanClassifier(s);
        net.minecraft.block.Block[] types = { Blocks.stone, Blocks.wool, Blocks.stained_glass };
        boolean originalCreative = player.capabilities.isCreativeMode;
        try {
            HologramSession controller = session(player, 1);
            controller.virtual = false;
            controller.virtualPreset = null;
            HologramPlanClassifier controllerClassifier = new HologramPlanClassifier(controller);
            HologramCapture.Cell controllerCell = controller.capture.cells.get(0);
            SimpleAssert.eq(
                HologramPlanPolicy.classify(controller, controllerCell, 0),
                controllerClassifier.classify(
                    controller,
                    controllerCell,
                    0,
                    true,
                    world.getBlock(controllerCell.x, controllerCell.y, controllerCell.z),
                    0,
                    null),
                "nonvirtual classifier keeps the original policy without requiring a virtual preset");
            boolean rejectedControllerTask = false;
            try {
                HologramResourcePlanner.beginPlan(controller);
            } catch (IllegalArgumentException expected) {
                rejectedControllerTask = true;
            }
            SimpleAssert
                .that(rejectedControllerTask, "area PlanTask continues to reject nonvirtual controller allocation");
            HologramSession missingPreset = session(player, 1);
            missingPreset.virtualPreset = null;
            missingPreset.capture.cells.get(0).anchor = true;
            HologramResourcePlanner.PlanTask missingPresetTask = HologramResourcePlanner.beginPlan(missingPreset);
            int missingTicks = 0;
            while (!missingPresetTask.done() && missingTicks++ < 1000) missingPresetTask.step(32, 1000000000L);
            SimpleAssert.that(
                missingPresetTask.done(),
                "virtual anchor plan tolerates an absent optional preset without a cache NPE");
            SimpleAssert
                .eq("protected", missingPreset.planStatus.get(0), "absent-preset anchor retains original protection");
            for (int i = 0; i < 50000; i++) {
                net.minecraft.block.Block source = types[i % types.length];
                cell.block = source;
                cell.blueprintItem = String.valueOf(
                    net.minecraft.item.Item.itemRegistry
                        .getNameForObject(net.minecraft.item.Item.getItemFromBlock(source)));
                cell.blueprintDamage = 0;
                ItemStack target = new ItemStack(source);
                s.pins.put(0, target);
                s.planTargets.clear();
                s.planTargets.add(target);
                player.capabilities.isCreativeMode = i % 8 == 0;
                world.occupiedX = cell.x;
                world.occupied = i % 5 == 0 ? null : i % 7 == 0 ? Blocks.dirt : source;
                net.minecraft.block.Block actual = world.getBlock(cell.x, cell.y, cell.z);
                String result = classifier.classify(s, cell, 0, true, actual, 0, null);
                if (i < 48) SimpleAssert.eq(
                    HologramPlanPolicy.classify(s, cell, 0),
                    result,
                    "ordinary proof preserves each current-world and creative/survival result");
                String expected = actual == source ? "satisfied"
                    : actual == Blocks.air || player.capabilities.isCreativeMode ? "pending" : "blocked";
                SimpleAssert
                    .eq(expected, result, "50000 ordinary classifications retain position-specific world state");
            }
            SimpleAssert.eq(
                50000L,
                classifier.ordinaryCells,
                "large repeated identity workload uses the audited ordinary proof");
            SimpleAssert.eq(0L, classifier.fallbackCells, "ordinary identity workload needs no NBT/native fallback");
            SimpleAssert.that(
                classifier.damageMisses() <= 3 && classifier.damageHits() > 50000,
                "pure variant callbacks scale with distinct identities rather than the 50000 positions");

            String decorId = "gtmb:planner_metadata_decor";
            net.minecraft.block.Block decor = net.minecraft.block.Block.getBlockFromName(decorId);
            if (decor == null) {
                decor = new net.minecraft.block.Block(net.minecraft.block.material.Material.rock) {

                    @Override
                    public int damageDropped(int metadata) {
                        return metadata;
                    }
                };
                // Forge returns the default air block for an absent ID; query raw occupancy instead.
                // Keep away from the 4087..4094 IDs reserved by later regression fixtures.
                int free = 3997;
                while (free > 0
                    && (((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry<?>) net.minecraft.block.Block.blockRegistry)
                        .getRaw(free) != null || net.minecraft.item.Item.itemRegistry.getObjectById(free) != null))
                    free--;
                SimpleAssert.that(free > 0, "ordinary mod fixture has a free native registry identity");
                net.minecraft.block.Block.blockRegistry.addObject(free, decorId, decor);
                net.minecraft.item.ItemBlock decorItem = new net.minecraft.item.ItemBlock(decor);
                decorItem.setHasSubtypes(true);
                net.minecraft.item.Item.itemRegistry.addObject(free, decorId, decorItem);
            }
            cell.block = decor;
            cell.blueprintItem = net.minecraft.item.Item.itemRegistry
                .getNameForObject(net.minecraft.item.Item.getItemFromBlock(decor));
            cell.blueprintDamage = 0;
            ItemStack decorTarget = new ItemStack(decor);
            s.pins.put(0, decorTarget);
            s.planTargets.set(0, decorTarget);
            world.occupied = decor;
            long ordinaryBefore = classifier.ordinaryCells;
            SimpleAssert.eq(
                HologramPlanPolicy.classify(s, cell, 0),
                classifier.classify(s, cell, 0, true, decor, 0, null),
                "ordinary mod decoration preserves native physical identity classification");
            SimpleAssert.eq(
                ordinaryBefore + 1,
                classifier.ordinaryCells,
                "ordinary mod decoration reuses the audited native method descriptor");
            SimpleAssert.that(
                classifier.resources(s, cell, 0).items.isEmpty(),
                "ordinary no-NBT mod decoration has no hidden inventory bill");
            HologramSnapshots.CellRows rowContext = new HologramSnapshots.CellRows(s);
            HologramSnapshots.CellRow ordinaryRow = HologramSnapshots.cellRowData(s, 0, false, rowContext);
            SimpleAssert.eq(
                net.minecraft.block.Block.blockRegistry.getNameForObject(decor),
                ordinaryRow.row.getString("wantId"),
                "row preview preserves the physical ordinary mod block");
            SimpleAssert.eq(
                decor,
                net.minecraft.block.Block.getBlockFromName(ordinaryRow.row.getString("wantId")),
                "row preview's registered identity resolves to the exact physical ordinary mod block");
            SimpleAssert.eq(decor, ordinaryRow.current, "same-call row observation supplies its current block");
            SimpleAssert.that(
                HologramValues.same(decorTarget, ordinaryRow.target),
                "same-call row observation supplies its exact resolved target");
            for (int color : new int[] { 3, 8 }) {
                NBTTagCompound visual = new NBTTagCompound();
                visual.setByte("mColor", (byte) color);
                visual.setInteger("texturePage", color);
                NBTTagList inventory = new NBTTagList();
                inventory.appendTag(new ItemStack(Items.diamond, color).writeToNBT(new NBTTagCompound()));
                visual.setTag("Inventory", inventory);
                cell.tileNbt = HologramBlueprintNbt.encodeCaptured(visual);
                NBTTagCompound expectedPayload = HologramBlueprintNbt
                    .decode(HologramCellPolicy.targetPreviewTile(s, cell));
                SimpleAssert.eq(
                    expectedPayload,
                    rowContext.preview(cell, HologramBlueprintNbt.decode(cell.tileNbt)),
                    "in-memory snapshot selection equals the former transport round trip for exact NBT");
                HologramSnapshots.CellRow payloadRow = HologramSnapshots.cellRowData(s, 0, false, rowContext);
                SimpleAssert.eq(
                    color - 1,
                    payloadRow.row.getCompoundTag("targetDisplay")
                        .getInteger("color"),
                    "different full NBT of the same block retains its own target display");
                NBTTagCompound source = HologramBlueprintNbt.decode(cell.tileNbt);
                NBTTagCompound detached = rowContext.preview(cell, source);
                detached.setInteger("texturePage", 999);
                SimpleAssert.eq(
                    color,
                    rowContext.preview(cell, source)
                        .getInteger("texturePage"),
                    "a row's display projection cannot mutate its source payload");
            }
            cell.tileNbt = "";
            cell.itemNbt = "";

            cell.block = Blocks.stone;
            cell.blueprintItem = "minecraft:stone";
            s.pins.put(0, new ItemStack(Blocks.stone));
            s.planTargets.set(0, new ItemStack(Blocks.stone));
            player.capabilities.isCreativeMode = true;
            for (net.minecraft.block.Block occupied : new net.minecraft.block.Block[] { Blocks.bedrock, Blocks.dirt }) {
                world.occupied = occupied;
                SimpleAssert.eq(
                    HologramPlanPolicy.classify(s, cell, 0),
                    classifier.classify(s, cell, 0, true, occupied, 0, null),
                    "creative ordinary replacement preserves the original removable predicate");
            }
            world.occupant = new net.minecraft.tileentity.TileEntity();
            SimpleAssert.eq(
                HologramPlanPolicy.classify(s, cell, 0),
                classifier.classify(s, cell, 0, true, world.occupied, 0, world.occupant),
                "unrecoverable current tiles retain the exact protected policy");
            world.occupant = null;

            world.occupied = null;
            cell.block = Blocks.stone;
            cell.blueprintItem = "minecraft:stone";
            cell.blueprintDamage = 0;
            s.pins.put(0, new ItemStack(Blocks.stone));
            s.planTargets.set(0, new ItemStack(Blocks.stone));
            HologramBlueprintResources.Plan first = null;
            for (int amount : new int[] { 2, 9 }) {
                NBTTagCompound payload = new NBTTagCompound();
                NBTTagList inventory = new NBTTagList();
                inventory.appendTag(new ItemStack(Items.diamond, amount).writeToNBT(new NBTTagCompound()));
                payload.setTag("Inventory", inventory);
                cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
                SimpleAssert.that(
                    !classifier.emptyOrdinary(s, cell, 0),
                    "same block/meta with NBT never uses the empty identity proof");
                HologramBlueprintResources.Plan resources = classifier.resources(s, cell, 0);
                SimpleAssert.eq(
                    amount,
                    resources.items.get(0).stackSize,
                    "distinct complete inventory NBT retains its own exact bill");
                if (first == null) first = resources;
                else SimpleAssert.that(first != resources, "different complete NBT cannot share a material result");
            }
            for (String key : new String[] { "gt.covers", "aeUnknownConfiguration" }) {
                NBTTagCompound one = new NBTTagCompound(), two = new NBTTagCompound();
                one.setString(key, "first exact configuration");
                two.setString(key, "second exact configuration");
                for (NBTTagCompound payload : new NBTTagCompound[] { one, two }) {
                    cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
                    SimpleAssert.that(
                        !classifier.emptyOrdinary(s, cell, 0),
                        "cover and AE/unknown payload identities never alias by block/meta");
                    long before = classifier.fallbackCells;
                    classifier.classify(s, cell, 0, true, Blocks.air, 0, null);
                    SimpleAssert.eq(
                        before + 1,
                        classifier.fallbackCells,
                        "each complete cover/unknown configuration uses native policy validation");
                }
            }
            cell.tileNbt = "";
            cell.itemNbt = HologramBlueprintNbt.encodeCaptured(new NBTTagCompound());
            SimpleAssert.that(
                !classifier.emptyOrdinary(s, cell, 0),
                "even an encoded empty item payload takes the explicit payload path");
        } finally {
            world.occupied = null;
            world.occupant = null;
            player.capabilities.isCreativeMode = originalCreative;
        }
    }

    private static void deferredLifecycle(EntityPlayerMP player) throws Exception {
        java.util.UUID owner = player.getUniqueID();
        HologramSession previous = HologramWorkspace.find(owner);
        HologramSession s = payloadSession(player, false);
        HologramWorkspace.activate(owner, s);
        int[] completions = { 0 };
        java.util.function.Consumer<HologramSession> complete = ignored -> completions[0]++;
        try {
            HologramDeferredPlanRefresh.request(s, java.util.Collections.singleton(0));
            SimpleAssert
                .that(s.localPlanPending && !s.capturePending, "complex edit has an independent pending lifecycle");
            NBTTagCompound initialProgress = HologramSnapshots.progress(s);
            SimpleAssert.eq("server", initialProgress.getString("scope"), "local edit progress is server owned");
            SimpleAssert.eq(s.id, initialProgress.getString("session"), "local edit progress carries its session");
            SimpleAssert.eq(
                s.localPlanGeneration,
                initialProgress.getLong("generation"),
                "local edit progress carries its generation");
            assertLocalProgress(s);
            SimpleAssert.that(
                HologramDeferredPlanRefresh.blocksAction(s, "build")
                    && HologramDeferredPlanRefresh.blocksAction(s, "saveBlueprintAs")
                    && HologramDeferredPlanRefresh.blocksAction(s, "confirmAeSave"),
                "complete-plan consumers are fenced");
            SimpleAssert.that(
                !HologramDeferredPlanRefresh.blocksAction(s, "rotateCell")
                    && !HologramDeferredPlanRefresh.blocksAction(s, "editUndo")
                    && !HologramDeferredPlanRefresh.blocksAction(s, "selectStructure")
                    && !HologramDeferredPlanRefresh.blocksAction(s, "close"),
                "editing selection and close remain available");
            HologramBuildPreparation.directBuild(s);
            SimpleAssert.eq(0, s.job, "direct build cannot consume an incomplete plan");
            HologramPlacementJobs.start(s);
            SimpleAssert.eq(0, s.job, "placement entry cannot bypass the pending guard");
            HologramDeferredPlanRefresh.Work first = s.localPlanTask;
            for (int i = 0; i < 10; i++) {
                HologramDeferredPlanRefresh.tick(s, 1, 1000000000L, complete);
                assertLocalProgress(s);
            }
            long generation = s.localPlanGeneration;
            s.pins.put(0, new ItemStack(Blocks.dirt));
            s.planTargets.clear();
            s.planTargets.add(new ItemStack(Blocks.stone));
            HologramDeferredPlanRefresh.request(s, java.util.Collections.singleton(0));
            SimpleAssert.that(
                s.localPlanGeneration > generation && !first.current(s),
                "a later edit retires the old generation");
            SimpleAssert.that(
                HologramValues
                    .same(new ItemStack(Blocks.dirt), HologramCellPolicy.targetFor(s, s.capture.cells.get(0), 0)),
                "pending preview uses latest pin instead of partial plan targets");
            HologramDeferredPlanRefresh.Work second = s.localPlanTask;
            player.inventory.currentItem = 1;
            HologramDeferredPlanRefresh.tick(s, 17, 1000000000L, complete);
            SimpleAssert.that(
                s.localPlanPending && s.localPlanTask == null && !second.current(s),
                "tool changes invalidate and suspend continuation");
            NBTTagCompound paused = HologramSnapshots.progress(s);
            SimpleAssert.eq("localPlan.paused", paused.getString("phase"), "suspended planning is reported explicitly");
            SimpleAssert.that(!paused.getBoolean("complete"), "suspended planning is never reported complete");
            player.inventory.currentItem = 0;
            int ticks = 0;
            while (s.localPlanPending && ticks++ < 20000)
                HologramDeferredPlanRefresh.tick(s, 17, 1000000000L, complete);
            SimpleAssert
                .that(!s.localPlanPending && s.localPlanError.isEmpty(), "latest edit completes after tool returns");
            SimpleAssert.eq(1, completions[0], "only the latest generation publishes completion");
            NBTTagCompound finished = HologramSnapshots.progress(s);
            SimpleAssert.that(
                finished.getBoolean("complete") && !finished.getString("phase")
                    .startsWith("localPlan."),
                "completed planning leaves the local pending progress lifecycle");
            SimpleAssert.that(
                HologramValues.same(new ItemStack(Blocks.dirt), s.planTargets.get(0)),
                "completed plan includes the latest edit");
            HologramDeferredPlanRefresh.tick(s, 17, 1000000000L, complete);
            SimpleAssert.eq(1, completions[0], "completion is not replayed on later ticks");
            s.cellDeltaProtocol = true;
            HologramLocalPlanRefresh.snapshotBegin(s);
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramLocalPlanRefresh.snapshotSeedCapture(s, i);
                HologramLocalPlanRefresh.snapshotCell(s, i, new NBTTagCompound());
            }
            HologramLocalPlanRefresh.snapshotComplete(s, new NBTTagCompound());
            s.allocationsFrozen = false;
            int invalidIndex = s.capture.cells.size() + 1;
            s.autoTargets.put(invalidIndex, new ItemStack(Blocks.stone));
            HologramDeferredPlanRefresh.request(s, java.util.Collections.singleton(0));
            HologramDeferredPlanRefresh.tick(s, 100, 1000000000L, complete);
            SimpleAssert
                .that(s.localPlanPending && !s.localPlanError.isEmpty(), "failed planning retains its consumer fence");
            NBTTagCompound failed = HologramSnapshots.progress(s);
            SimpleAssert.eq(
                "localPlan.failed",
                failed.getString("phase"),
                "planning error has a distinct production progress phase");
            SimpleAssert.eq("failed", failed.getString("outcome"), "planning error never reports a ready outcome");
            SimpleAssert.eq(
                s.localPlanError,
                failed.getString("error"),
                "production progress carries the actual planning error");
            SimpleAssert
                .that(!failed.getBoolean("complete"), "planning error cannot masquerade as successful completion");
            SimpleAssert.eq(
                s.localPlanTask.completed(),
                failed.getLong("done"),
                "failure preserves its actual completed counter");
            SimpleAssert
                .eq(s.localPlanTask.total(), failed.getLong("total"), "failure preserves its actual total counter");
            SimpleAssert.eq(1, completions[0], "failed planning does not publish a successful completion");
            NBTTagCompound errorDelta = HologramLocalPlanRefresh.delta(s);
            SimpleAssert.that(
                errorDelta != null && errorDelta.getBoolean("partial") && errorDelta.getBoolean("localPlanPending"),
                "planning failure uses a pending metadata delta");
            SimpleAssert
                .eq(s.localPlanError, errorDelta.getString("localPlanError"), "failure delta carries the actual error");
            SimpleAssert.eq(
                "localPlan.failed",
                errorDelta.getCompoundTag("progress")
                    .getString("phase"),
                "failure metadata uses production progress");
            SimpleAssert.that(
                !errorDelta.hasKey("materials") && errorDelta.getTagList("cells", 10)
                    .tagCount() < s.capture.cells.size(),
                "failure cannot publish partial material totals or full geometry");
            s.autoTargets.remove(invalidIndex);
            HologramDeferredPlanRefresh.request(s, java.util.Collections.singleton(0));
            SimpleAssert.that(s.localPlanError.isEmpty(), "new edit clears the failed generation for retry");
            HologramDeferredPlanRefresh.Work retired = s.localPlanTask;
            HologramWorkspace.activate(owner, payloadSession(player, false));
            SimpleAssert.that(
                !s.localPlanPending && s.localPlanTask == null && !retired.current(s),
                "replacement session retires old plan ownership");
            HologramDeferredPlanRefresh.tick(s, 17, 1000000000L, complete);
            SimpleAssert.eq(1, completions[0], "retired session cannot publish a result");
        } finally {
            player.inventory.currentItem = 0;
            HologramDeferredPlanRefresh.cancel(s);
            if (previous != null) HologramWorkspace.activate(owner, previous);
            else {
                Field sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
                sessions.setAccessible(true);
                ((java.util.Map<?, ?>) sessions.get(null)).remove(owner);
            }
        }
    }

    private static void assertLocalProgress(HologramSession s) {
        NBTTagCompound progress = HologramSnapshots.progress(s);
        SimpleAssert.eq(
            "localPlan." + s.localPlanTask.phase(),
            progress.getString("phase"),
            "production progress uses the actual task phase");
        SimpleAssert.eq(
            s.localPlanTask.completed(),
            progress.getLong("done"),
            "production progress uses the actual completed counter");
        SimpleAssert.eq(
            s.localPlanTask.total(),
            progress.getLong("total"),
            "production progress uses the actual total counter");
        SimpleAssert.that(
            !progress.getBoolean("complete") && progress.getLong("done") <= progress.getLong("total"),
            "pending progress remains factual and incomplete");
    }

    private static HologramSession payloadSession(EntityPlayerMP player, boolean fuzzy) throws Exception {
        HologramSession s = session(player, 16);
        s.virtualPreset.schema = 2;
        s.virtualPreset.deidentificationLevel = fuzzy ? 1 : 0;
        s.materialSettings.containers = false;
        s.materialSettings.me = false;
        s.materialContext = new HologramMaterials.Context(player, s.materialSettings);
        NBTTagCompound payload = new NBTTagCompound();
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(new ItemStack(Blocks.stone, 2).writeToNBT(new NBTTagCompound()));
        payload.setTag("Inventory", inventory);
        for (HologramCapture.Cell cell : s.capture.cells) cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        return s;
    }

    private static HologramSession session(EntityPlayerMP player, int size) {
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
        s.virtual = true;
        s.virtualPlacement = true;
        s.scope = 0;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.palette.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
        s.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> element = StructureUtility
            .ofBlock(Blocks.stone, 0);
        for (int i = 0; i < size; i++) {
            int x = i % 64, z = i / 64;
            HologramCapture.Cell cell = new HologramCapture.Cell(element, x, 64, z);
            cell.block = Blocks.stone;
            s.capture.cells.add(cell);
            s.virtualPreset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), x, 0, z, -1, false));
        }
        return s;
    }

    private static void assertItems(List<ItemStack> expected, List<ItemStack> actual) {
        SimpleAssert.eq(expected.size(), actual.size(), "item order/identity count");
        for (int i = 0; i < expected.size(); i++) {
            ItemStack a = expected.get(i), b = actual.get(i);
            SimpleAssert.that(
                a == null ? b == null : HologramValues.same(a, b) && a.stackSize == b.stackSize,
                "strict item identity, NBT, order and count at " + i);
        }
    }
}
