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
