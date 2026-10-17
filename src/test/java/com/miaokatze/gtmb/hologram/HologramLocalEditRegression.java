package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Collections;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the production sparse history, local material difference and dirty-cell encoder. */
final class HologramLocalEditRegression {

    private HologramLocalEditRegression() {}

    static void run() {
        try {
            int small = edit(4096), large = edit(32768);
            SimpleAssert.that(
                Math.abs(large - small) < 256,
                "one-cell payload grows with numeric totals, not blueprint geometry");
        } catch (Exception invalid) {
            throw new AssertionError("Local edit regression", invalid);
        }
    }

    private static int edit(int count) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        CountingWorld world = (CountingWorld) unsafe.allocateInstance(CountingWorld.class);
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone, 64);
        player.inventory.mainInventory[1] = new ItemStack(Blocks.dirt, 64);
        for (Field profile : EntityPlayer.class.getDeclaredFields())
            if (profile.getType() == com.mojang.authlib.GameProfile.class) {
                profile.setAccessible(true);
                profile.set(player, new com.mojang.authlib.GameProfile(new java.util.UUID(901, 902), "local-edit"));
            }
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
        s.virtual = true;
        s.virtualPlacement = true;
        s.cellDeltaProtocol = true;
        s.allocationsFrozen = true;
        s.selected = 0;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.schema = 2;
        s.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        s.materialSettings.main = true;
        s.materialSettings.containers = false;
        s.materialSettings.me = false;
        s.materialContext = new HologramMaterials.Context(player, s.materialSettings);
        for (int i = 0; i < count; i++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(
                StructureUtility.ofBlock(Blocks.stone, 0),
                i % 128,
                64,
                i / 128);
            cell.block = Blocks.stone;
            cell.blueprintBlock = "minecraft:stone";
            cell.structureId = i < 16 ? "small-module" : "background";
            s.capture.cells.add(cell);
            s.capture.positions.put(HologramValues.position(cell), cell);
            s.cellIndices.put(cell, i);
            s.virtualPreset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), cell.x, 0, cell.z, -1, false));
        }
        s.indexedCapture = s.capture;
        HologramResourcePlanner.refreshPlan(s);
        s.processingSource = HologramBlueprintProjection.blueprint(s, "fixture");
        HologramLocalPlanRefresh.snapshotBegin(s);
        for (int i = 0; i < count; i++) {
            HologramLocalPlanRefresh.snapshotSeedDefaults(s, i);
            HologramLocalPlanRefresh.snapshotSeedSource(s, i);
            HologramLocalPlanRefresh.snapshotCell(s, i, new NBTTagCompound());
        }
        for (int i = 0; i < s.processingSource.palette.size(); i++) HologramLocalPlanRefresh.snapshotSeedPalette(s, i);
        NBTTagCompound baseline = new NBTTagCompound();
        baseline.setInteger("total", count);
        baseline.setInteger("workTotal", count);
        HologramLocalPlanRefresh.snapshotComplete(s, baseline);
        SimpleAssert.eq(
            16,
            s.localEdits.memberships.get("small-module")
                .size(),
            "membership index excludes unrelated cells");
        HologramCapture.Cell untouched = s.capture.cells.get(count - 1);
        HologramPresets.Cell untouchedSource = s.processingSource.cells.get(count - 1);
        HologramEditState before = new HologramEditState(s, Collections.singleton(1));
        SimpleAssert.eq(1, before.geometry.size(), "single-cell undo stores one geometry row");
        world.reads = 0;
        long revision = s.planRevision;
        HologramCapture.Cell target = s.capture.cells.get(1);
        s.pins.put(1, new ItemStack(Blocks.dirt));
        s.coordinatePins.put(HologramValues.position(target), new ItemStack(Blocks.dirt));
        s.selected = 1;
        SimpleAssert.that(
            HologramLocalPlanRefresh.refresh(s, Collections.singleton(1)),
            "stable simple edit uses local planner");
        SimpleAssert.that(
            HologramLocalPlanRefresh.editedSource(s, Collections.singleton(1)),
            "processing baseline updates one indexed cell");
        SimpleAssert.that(world.reads < 256, "single-cell plan does not read the remaining world geometry");
        SimpleAssert.eq(revision + 1, s.planRevision, "local edit advances authoritative plan revision");
        SimpleAssert
            .that(s.capture.cells.get(count - 1) == untouched, "unaffected captured cell identity is preserved");
        SimpleAssert.that(
            s.processingSource.cells.get(count - 1) == untouchedSource,
            "unaffected author source identity is preserved");
        SimpleAssert.that(
            HologramValues.same(new ItemStack(Blocks.dirt), s.planTargets.get(1)),
            "edited plan target is authoritative");
        SimpleAssert.eq(count - 1, amount(s, Blocks.stone), "old shell count is subtracted");
        SimpleAssert.eq(1, amount(s, Blocks.dirt), "new shell count is added");
        NBTTagCompound delta = HologramLocalPlanRefresh.delta(s);
        SimpleAssert
            .that(delta != null && delta.getBoolean("partial"), "established capable client gets dirty-cell DTO");
        SimpleAssert.eq(revision, delta.getLong("baseRevision"), "delta names its full baseline");
        SimpleAssert.eq(count, delta.getInteger("cellCount"), "delta preserves total geometry identity");
        SimpleAssert.eq(
            2,
            delta.getTagList("cells", 10)
                .tagCount(),
            "only edited and formerly selected candidate rows are serialized");
        int bytes = HologramSnapshotCodec.compress(delta).length;
        SimpleAssert.that(bytes < 8192, "single edit wire payload is bounded independently of full geometry");
        annotations(s, world);
        before.apply(s);
        SimpleAssert.that(
            !s.pins.containsKey(1) && s.capture.cells.get(count - 1) == untouched,
            "sparse undo restores only its positions");
        s.cellDeltaProtocol = false;
        HologramLocalPlanRefresh.pending(s, Collections.singleton(1));
        SimpleAssert
            .that(HologramLocalPlanRefresh.delta(s) == null, "legacy client never receives a partial geometry packet");
        return bytes;
    }

    private static void annotations(HologramSession s, CountingWorld world) throws Exception {
        HologramPresets.Preset source = s.processingSource;
        HologramPresets.Cell raw = source.cells.get(1);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("owner", "raw-author-source");
        raw.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
        raw.itemNbt = raw.tileNbt;
        String tile = raw.tileNbt, item = raw.itemNbt;
        s.processingPolicy = HologramBlueprintProcessing.defaults("self", 0);
        s.processingPolicy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        s.processingReviewed = true;
        s.blueprintSaveReceipt = "old-save";
        long revision = s.planRevision;
        world.reads = 0;
        NBTTagCompound action = new NBTTagCompound();
        action.setIntArray("indices", new int[] { 1 });
        action.setString("prompt", "edited cell prompt");
        HologramAuthoringWorkflow.blueprintAction(s, action, "editCellFlags");
        SimpleAssert.eq(revision, s.planRevision, "prompt edit retains the material/world plan revision");
        SimpleAssert.that(
            s.processingSource == source && source.cells.get(1) == raw,
            "prompt edit retains the indexed raw author source");
        SimpleAssert.eq(tile, raw.tileNbt, "prompt edit retains raw tile NBT");
        SimpleAssert.eq(item, raw.itemNbt, "prompt edit retains raw item NBT");
        SimpleAssert.eq("edited cell prompt", raw.prompt, "prompt edit updates its indexed source row");
        SimpleAssert.eq(
            "edited cell prompt",
            s.virtualPreset.cells.get(1).prompt,
            "prompt edit updates its indexed reloadable default");
        SimpleAssert.eq(5, s.processingPolicy.confirmedStages.size(), "prompt edit invalidates only the hints stage");
        NBTTagCompound delta = HologramLocalPlanRefresh.delta(s);
        SimpleAssert.that(
            delta != null && !delta.hasKey("materials") && !delta.hasKey("blueprintResources"),
            "annotation delta reuses unchanged quotes");
        SimpleAssert.eq(
            1,
            delta.getTagList("cells", 10)
                .tagCount(),
            "annotation sends only its edited row");
        SimpleAssert.that(
            delta.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .getBoolean("annotationOnly"),
            "annotation row carries no repeated geometry or candidates");
        SimpleAssert.that(
            !delta.getBoolean("blueprintSavePending") && s.ae.review == null,
            "annotation edit retires old save and AE review authority");
        action.setString("prompt", "edited region prompt");
        HologramAuthoringWorkflow.blueprintAction(s, action, "editHintArea");
        NBTTagCompound hintDelta = HologramLocalPlanRefresh.delta(s);
        SimpleAssert.eq(
            0,
            hintDelta.getTagList("cells", 10)
                .tagCount(),
            "region prompt changes metadata only");
        SimpleAssert.eq(
            "edited region prompt",
            source.hintAreas.get(0).prompt,
            "region prompt updates the raw author source metadata");
        SimpleAssert.eq(0, world.reads, "prompt and region edits never match the world or rebuild candidate quotes");
    }

    private static int amount(HologramSession s, Block block) {
        for (ItemStack item : s.blueprintTotalResources.items)
            if (item.getItem() == net.minecraft.item.Item.getItemFromBlock(block)) return item.stackSize;
        return 0;
    }

    static final class CountingWorld extends WorldServer {

        int reads;

        private CountingWorld() {
            super(null, null, "fixture", 0, null, null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            reads++;
            return true;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            reads++;
            return Blocks.air;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            reads++;
            return 0;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            reads++;
            return null;
        }

        @Override
        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
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
            throw new AssertionError("Editor cannot write world");
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            throw new AssertionError("Editor cannot write tiles");
        }
    }
}
