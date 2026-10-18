package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** A 64k sparse declaration must never observe the 63k geometry-only air positions in the world. */
final class HologramSparseAirRegression {

    private static final int AIR = 63000, SOLID = 1000;

    private HologramSparseAirRegression() {}

    static void run(EntityPlayerMP player) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        SparseWorld world = (SparseWorld) ((sun.misc.Unsafe) field.get(null)).allocateInstance(SparseWorld.class);
        World originalWorld = player.worldObj;
        boolean creative = player.capabilities.isCreativeMode;
        ItemStack[] originalStock = player.inventory.mainInventory.clone();
        try {
            player.worldObj = world;
            player.capabilities.isCreativeMode = false;
            int remaining = SOLID;
            for (int slot = 1; remaining > 0; slot++) {
                int amount = Math.min(64, remaining);
                player.inventory.mainInventory[slot] = new ItemStack(Blocks.stone, amount);
                remaining -= amount;
            }
            HologramSession control = session(player, false), sparse = session(player, true);
            world.reads = 0;
            HologramResourcePlanner.refreshPlan(control);
            long ordinaryReads = world.reads;
            world.reads = 0;
            HologramResourcePlanner.refreshPlan(sparse);
            SimpleAssert.eq(
                ordinaryReads,
                world.reads,
                "63k saved air cells add zero world observations to the same 1k solid synchronous plan");
            SimpleAssert.eq(AIR + SOLID, sparse.planStatus.size(), "all stable geometry indices remain present");
            SimpleAssert.eq(AIR + SOLID, sparse.expectedBlocks.size(), "expected-state placeholders preserve indices");
            for (int i = 0; i < AIR; i++) {
                SimpleAssert.eq("satisfied", sparse.planStatus.get(i), "plain air is an ignored satisfied declaration");
                SimpleAssert.that(HologramPlanPolicy.expectedCurrent(sparse, i), "air has no expected world state");
            }
            world.reads = 0;
            HologramResourcePlanner.PlanTask smallTask = HologramResourcePlanner.beginPlan(control);
            while (!smallTask.step(127, Long.MAX_VALUE)) {}
            long stagedReads = world.reads;
            world.reads = 0;
            HologramResourcePlanner.PlanTask largeTask = HologramResourcePlanner.beginPlan(sparse);
            while (!largeTask.step(127, Long.MAX_VALUE)) {}
            SimpleAssert.eq(
                stagedReads,
                world.reads,
                "63k saved air cells add zero world observations to the same 1k solid sliced plan");
            SimpleAssert.that(HologramPlanPolicy.planCurrent(sparse), "review checks only non-air expected positions");
            snapshot(sparse, world);
            world.reads = 0;
            SimpleAssert.that(
                HologramWorldTransactions.operateChecked(sparse, sparse.capture.cells.get(0), 0),
                "a defensive direct air operation is a no-op before native/world/payment admission");
            SimpleAssert.eq(0L, world.reads, "direct air operation cannot observe the world");
            HologramPlacementJobs.start(sparse);
            SimpleAssert.eq(SOLID, sparse.work.size(), "only the 1k non-air targets enter the actual work queue");
            for (int index : sparse.work) SimpleAssert.that(index >= AIR, "air geometry never becomes work");
            sparse.job = 0;
            safetyAndEditing(sparse, world);
        } finally {
            player.worldObj = originalWorld;
            player.capabilities.isCreativeMode = creative;
            System.arraycopy(originalStock, 0, player.inventory.mainInventory, 0, originalStock.length);
        }
    }

    private static HologramSession session(EntityPlayerMP player, boolean air) {
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 1);
        s.virtual = s.virtualPlacement = s.virtualLocked = true;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.schema = 2;
        s.materialSettings.main = true;
        s.materialSettings.containers = s.materialSettings.me = false;
        s.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        for (int x = air ? 0 : AIR; x < AIR + SOLID; x++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(null, x, 64, 0);
            cell.userPosition = true;
            cell.role = "prefab";
            cell.block = x < AIR ? Blocks.air : Blocks.stone;
            cell.blueprintBlock = x < AIR ? "minecraft:air" : "minecraft:stone";
            cell.blueprintItem = x < AIR ? "" : "minecraft:stone";
            int index = s.capture.cells.size();
            cell.stableIndex = index;
            s.capture.cells.add(cell);
            s.capture.positions.put(HologramValues.position(cell), cell);
            if (x >= AIR) s.pins.put(index, new ItemStack(Blocks.stone));
        }
        return s;
    }

    private static void snapshot(HologramSession s, SparseWorld world) throws Exception {
        HologramLocalPlanRefresh.snapshotBegin(s);
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSnapshotPreparation$Task");
        Constructor<?> constructor = type
            .getDeclaredConstructor(HologramSession.class, HologramNetwork.SnapshotTicket.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance(s, null);
        Method add = type.getDeclaredMethod("addCell", int.class);
        add.setAccessible(true);
        s.materialContext = null;
        world.reads = 0;
        for (int i = 0; i < AIR; i++) add.invoke(task, i);
        SimpleAssert.eq(0L, world.reads, "real DTO preparation never observes occupied blueprint air positions");
        SimpleAssert.that(s.materialContext == null, "air DTO collection never opens an inventory source");
        for (int i = AIR; i < AIR + SOLID; i++) add.invoke(task, i);
        Field requirements = type.getDeclaredField("requirements");
        requirements.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<HologramBlueprintResources.ExactKey, NBTTagCompound> rows = (Map<HologramBlueprintResources.ExactKey, NBTTagCompound>) requirements
            .get(task);
        SimpleAssert.eq(1, rows.size(), "sparse quote aggregates only the actual stone identity");
        NBTTagList materials = new NBTTagList();
        for (NBTTagCompound row : rows.values()) materials.appendTag(row);
        NBTTagCompound quote = HologramMaterialQuote.snapshot(s, materials, new NBTTagList());
        SimpleAssert.eq(
            (long) SOLID,
            quote.getTagList("rows", 10)
                .getCompoundTagAt(0)
                .getLong("required"),
            "63k air placeholders add no payable material or stock demand");
    }

    private static void safetyAndEditing(HologramSession s, SparseWorld world) {
        HologramCapture.Cell cell = s.capture.cells.get(0);
        s.planTargets.clear();
        s.localPlanPending = true;
        SimpleAssert.that(
            HologramSessionPolicy.ignoredPlacementAir(s, cell, 0),
            "air recognition remains pure while plan targets are missing and a local plan is pending");
        SimpleAssert.that(
            HologramSessionPolicy.ignoredPlacementAirAt(s, cell.x, cell.y, cell.z),
            "exterior lookup uses the same pure known-air policy");
        cell.unknown = true;
        SimpleAssert.that(!HologramSessionPolicy.ignoredPlacementAir(s, cell, 0), "unknown identity is never air");
        cell.unknown = false;
        cell.blueprintBlock = "";
        SimpleAssert.that(!HologramSessionPolicy.ignoredPlacementAir(s, cell, 0), "blank identity is never air");
        cell.blueprintBlock = "minecraft:air";
        HologramCapture.Cell declared = new HologramCapture.Cell(
            com.gtnewhorizon.structurelib.structure.StructureUtility.isAir(),
            0,
            64,
            1);
        declared.block = Blocks.air;
        declared.userPosition = true;
        declared.role = "prefab";
        SimpleAssert.that(
            !HologramSessionPolicy.ignoredPlacementAir(s, declared, -1),
            "a recaptured cell needs an explicit known air identity before it can be ignored");
        HologramVirtualStructureEditing.identifyDeclaredAir(declared);
        SimpleAssert.that(
            HologramSessionPolicy.ignoredPlacementAir(s, declared, -1),
            "a canonical recaptured StructureLib air declaration retains geometry and a safe air identity");
        cell.block = null;
        SimpleAssert.that(!HologramSessionPolicy.ignoredPlacementAir(s, cell, 0), "null block identity is never air");
        cell.block = Blocks.air;
        cell.tileNbt = "invalid";
        SimpleAssert.that(!HologramSessionPolicy.ignoredPlacementAir(s, cell, 0), "air cannot hide unparsed NBT");
        cell.tileNbt = "";
        cell.role = "templateController";
        SimpleAssert.that(!HologramSessionPolicy.ignoredPlacementAir(s, cell, 0), "controller identity is never air");
        cell.role = "prefab";
        s.mode = 2;
        SimpleAssert.that(!HologramSessionPolicy.ignoredPlacementAir(s, cell, 0), "demolition rules remain separate");
        s.mode = 0;
        s.virtual = false;
        SimpleAssert
            .that(!HologramSessionPolicy.ignoredPlacementAir(s, cell, 0), "non-virtual/cut paths keep their rules");
        s.virtual = true;
        s.virtualPlacement = false;
        SimpleAssert.that(!HologramSessionPolicy.ignoredPlacementAir(s, cell, 0), "author geometry remains editable");
        s.virtualPlacement = true;
        s.pins.put(0, new ItemStack(Blocks.stone));
        SimpleAssert.that(
            !HologramSessionPolicy.ignoredPlacementAir(s, cell, 0),
            "an explicit non-air pin on an air declaration restores normal placement validation");
        SimpleAssert.that(
            !HologramSessionPolicy.ignoredPlacementAirAt(s, cell.x, cell.y, cell.z),
            "exterior lookup cannot bypass an explicit non-air pin");
        s.localPlanPending = false;
        world.allowFirstAirTarget = true;
        world.reads = 0;
        HologramResourcePlanner.refreshPlan(s);
        SimpleAssert.eq("pending", s.planStatus.get(0), "air-to-stone edit becomes real pending work");
        SimpleAssert.that(world.editedReads > 0, "explicit air-to-stone target observes its destination normally");
    }

    private static final class SparseWorld extends WorldServer {

        long reads, editedReads;
        boolean allowFirstAirTarget;

        private SparseWorld() {
            super(null, null, "sparse-air", 0, null, null);
        }

        private void observed(int x) {
            if (x >= 0 && x < AIR) {
                if (x != 0 || !allowFirstAirTarget) throw new AssertionError("world access at blueprint air: " + x);
                editedReads++;
            }
            reads++;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            observed(x);
            return true;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            observed(x);
            return Blocks.air;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            observed(x);
            return 0;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            observed(x);
            return null;
        }

        @Override
        public boolean isAirBlock(int x, int y, int z) {
            observed(x);
            return true;
        }

        @Override
        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            observed(x);
            return true;
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public long getTotalWorldTime() {
            return 0;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("air planning cannot write blocks");
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            throw new AssertionError("air planning cannot write tiles");
        }
    }
}
