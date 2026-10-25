package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises production move/delete, payload generation and the subsequent complete validation boundary. */
final class HologramGeometryEditRegression {

    private HologramGeometryEditRegression() {}

    static void run() {
        try {
            HologramSession s = fixture(4096);
            HologramLocalEditRegression.CountingWorld world = (HologramLocalEditRegression.CountingWorld) s.player.worldObj;
            HologramCapture.Cell distant = s.capture.cells.get(100);
            HologramPresets.Preset oldSource = s.processingSource;
            long revision = s.planRevision;
            world.reads = 0;
            NBTTagCompound action = new NBTTagCompound();
            action.setIntArray("indices", new int[] { 0 });
            action.setInteger("dx", -17);
            action.setLong("planRevision", revision);
            HologramBlueprintEditor.moveBlueprintCells(s, action);
            HologramAuthoringWorkflow.blueprintEdited(s, "geometry");
            SimpleAssert.eq(0, world.reads, "author move does not observe the whole world");
            SimpleAssert.eq(0L, s.geometryEdits.fullValidations, "move defers complete semantic validation");
            SimpleAssert.that(s.processingSource == oldSource, "move does not project the whole processing source");
            SimpleAssert.that(s.capture.cells.get(100) == distant, "distant geometry retains its index and identity");
            NBTTagCompound delta = HologramGeometryEdits.delta(s);
            SimpleAssert.that(delta != null && delta.getBoolean("topology"), "move emits an explicit topology delta");
            SimpleAssert.eq(revision, delta.getLong("baseRevision"), "delta names the exact original revision");
            SimpleAssert.eq(
                1,
                delta.getTagList("cells", 10)
                    .tagCount(),
                "4096-cell move serializes one changed cell");
            SimpleAssert.eq(
                -17,
                delta.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .getInteger("x"),
                "delta contains destination");

            HologramCapture.Cell tail = s.capture.cells.get(4095);
            action = new NBTTagCompound();
            action.setIntArray("indices", new int[] { 3 });
            action.setLong("planRevision", s.planRevision);
            HologramBlueprintEditor.deleteBlueprintCells(s, action);
            SimpleAssert.eq(4095, s.capture.cells.size(), "delete removes one physical draft cell");
            SimpleAssert.that(s.capture.cells.get(3) == tail, "tail fills only the removed index");
            SimpleAssert.that(s.capture.cells.get(100) == distant, "delete does not reindex distant cells");
            SimpleAssert.eq(-1, s.selected, "delete clears selection rather than selecting the swapped tail");
            delta = HologramGeometryEdits.delta(s);
            SimpleAssert.eq(4096, delta.getInteger("baseCellCount"), "deletion patch retains the old index domain");
            SimpleAssert.eq(4095, delta.getInteger("cellCount"), "deletion patch names the compacted size");
            SimpleAssert.eq(
                1,
                delta.getTagList("cells", 10)
                    .tagCount(),
                "delete serializes only the swapped tail");
            SimpleAssert.eq(0L, s.geometryEdits.fullValidations, "delete still performs no full validation");
            s.capture.cells.get(100).prompt = "after geometry";
            HologramBlueprintEditor.refreshEdited(s, java.util.Collections.singleton(100));
            HologramAuthoringWorkflow.blueprintEdited(s, "prompt");
            delta = HologramGeometryEdits.delta(s);
            SimpleAssert.eq(
                1,
                delta.getTagList("cells", 10)
                    .tagCount(),
                "subsequent ordinary edits remain local while geometry is pending");
            SimpleAssert
                .eq(0L, s.geometryEdits.fullValidations, "annotation after move cannot trigger full validation");
            HologramPresets.Preset current = HologramAuthoringQueries.processingSource(s);
            SimpleAssert.eq(1L, s.geometryEdits.fullValidations, "next processing source request validates once");
            SimpleAssert.eq(4095, current.cells.size(), "next workflow receives current geometry");
            SimpleAssert.eq(-17, current.cells.get(0).x, "validated source contains the moved origin");
            SimpleAssert.that(!s.geometryEdits.validationPending, "only successful validation retires the obligation");
            HologramAuthoringQueries.processingSource(s);
            SimpleAssert
                .eq(1L, s.geometryEdits.fullValidations, "unchanged next-step queries reuse validated geometry");
            HologramSession quickSave = fixture(3);
            NBTTagCompound quickMove = new NBTTagCompound();
            quickMove.setInteger("selected", 0);
            quickMove.setInteger("dx", -17);
            quickMove.setLong("planRevision", quickSave.planRevision);
            HologramBlueprintEditor.moveBlueprintCells(quickSave, quickMove);
            HologramAuthoringWorkflow.blueprintEdited(quickSave, "geometry");
            NBTTagCompound quickRequest = new NBTTagCompound();
            quickRequest.setBoolean("saveCurrent", true);
            HologramAuthoringWorkflow.validateProcessedSaveOptions(quickSave, quickRequest);
            SimpleAssert.eq(
                1L,
                quickSave.geometryEdits.fullValidations,
                "quick save validates the complete current geometry exactly once at the actual server save gate");
            SimpleAssert.that(
                quickSave.processingReviewed,
                "plain secondary edits pass the existing automatic processing validation at save time");
            SimpleAssert.that(
                !quickSave.geometryEdits.validationPending,
                "only the successful save validation retires the geometry obligation");
            action = new NBTTagCompound();
            action.setIntArray("indices", new int[] { 100 });
            action.setBoolean("copy", true);
            action.setInteger("dx", 1000);
            action.setLong("planRevision", s.planRevision);
            HologramBlueprintEditor.moveBlueprintCells(s, action);
            HologramAuthoringWorkflow.blueprintEdited(s, "geometry");
            delta = HologramGeometryEdits.delta(s);
            SimpleAssert.eq(4096, delta.getInteger("cellCount"), "copy appends one draft cell");
            SimpleAssert.eq(
                1,
                delta.getTagList("cells", 10)
                    .tagCount(),
                "copy does not resend its unchanged source");
            SimpleAssert.that(s.capture.cells.get(100) == distant, "copy keeps its original source identity");

            HologramSession invalid = fixture(2);
            invalid.capture.cells.get(1).block = Blocks.wool;
            invalid.capture.cells.get(1).meta = 14;
            invalid.capture.cells.get(1).blueprintItem = "minecraft:stone";
            action = new NBTTagCompound();
            action.setInteger("selected", 0);
            action.setInteger("dx", -17);
            action.setLong("planRevision", invalid.planRevision);
            HologramBlueprintEditor.moveBlueprintCells(invalid, action);
            HologramAuthoringWorkflow.blueprintEdited(invalid, "geometry");
            SimpleAssert.that(
                !HologramGeometryEdits.requirePlan(invalid),
                "material planning rejects an unrelated invalid source material variant");
            SimpleAssert.that(invalid.geometryEdits.validationPending, "failed validation remains pending");
            SimpleAssert.that(invalid.status.contains("完整校验失败"), "failed next step explains the actual failure");
            SimpleAssert
                .that(!HologramPlanPolicy.constructionAllowed(invalid), "construction cannot bypass failed validation");
            try {
                HologramAuthoringWorkflow.validateProcessedSaveOptions(invalid, new NBTTagCompound());
                throw new AssertionError("save must validate the current geometry before evaluating save options");
            } catch (java.io.IOException expected) {
                SimpleAssert.that(invalid.geometryEdits.validationPending, "failed save cannot retire validation");
            }
            com.miaokatze.gtmb.client.hologram.HologramGeometryDeltaRegression.run();
            HologramBlueprintQuickSaveRegression.run();
        } catch (Exception failure) {
            throw new AssertionError("Geometry edit regression", failure);
        }
    }

    private static HologramSession fixture(int count) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        HologramLocalEditRegression.CountingWorld world = (HologramLocalEditRegression.CountingWorld) unsafe
            .allocateInstance(HologramLocalEditRegression.CountingWorld.class);
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone, 64);
        for (Field profile : EntityPlayer.class.getDeclaredFields())
            if (profile.getType() == com.mojang.authlib.GameProfile.class) {
                profile.setAccessible(true);
                profile.set(player, new com.mojang.authlib.GameProfile(new UUID(701, 702), "geometry-edit"));
            }
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
        s.virtual = true;
        s.cellDeltaProtocol = true;
        s.allocationsFrozen = true;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.name = "geometry";
        s.virtualPreset.schema = 2;
        s.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        s.materialSettings.containers = s.materialSettings.me = false;
        for (int index = 0; index < count; index++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(
                StructureUtility.ofBlock(Blocks.stone, 0),
                index % 128,
                64,
                index / 128);
            cell.block = Blocks.stone;
            cell.blueprintBlock = "minecraft:stone";
            cell.actualStack = new ItemStack(Blocks.stone);
            s.capture.cells.add(cell);
            s.capture.positions.put(HologramValues.position(cell), cell);
            s.virtualPreset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), cell.x, 0, cell.z, -1, false));
        }
        HologramResourcePlanner.refreshPlan(s);
        s.processingSource = HologramBlueprintProjection.blueprint(s, "geometry");
        s.processingPolicy = HologramBlueprintProcessing.defaults("self", 0);
        HologramLocalPlanRefresh.snapshotBegin(s);
        for (int index = 0; index < count; index++)
            HologramLocalPlanRefresh.snapshotCell(s, index, new NBTTagCompound());
        NBTTagCompound baseline = new NBTTagCompound();
        baseline.setInteger("total", count);
        HologramLocalPlanRefresh.snapshotComplete(s, baseline);
        return s;
    }
}
