// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.util.GTStructureUtility;
import gregtech.common.tileentities.machines.multi.MTEIndustrialChemicalBath;
import gregtech.common.tileentities.machines.multi.MTEIndustrialRockBreaker;
import gregtech.common.tileentities.machines.multi.MTEOreWashingPlant;
import gregtech.common.tileentities.machines.multi.steam.MTESteamWasher;

final class HologramNativeSourceWaterRegression {

    private static final int[] ANCHOR = { 10, 64, 20 };

    private HologramNativeSourceWaterRegression() {}

    static void run() {
        try {
            nativeDeclaration();
            placementAndMaterialQuote();
            editableRoundTrip();
        } catch (IOException failure) {
            throw new AssertionError("native generated water fixture", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("native generated water fixture interrupted", failure);
        }
    }

    private static void nativeDeclaration() {
        SimpleAssert.that(HologramNativeSourceWater.reviewedController(MTEOreWashingPlant.class)
            && HologramNativeSourceWater.reviewedController(MTEIndustrialChemicalBath.class),
            "both exact audited machines have native source water generation");
        SimpleAssert.that(!HologramNativeSourceWater.reviewedController(MTESteamWasher.class)
            && !HologramNativeSourceWater.reviewedController(MTEIndustrialRockBreaker.class),
            "the same authored water/air declaration alone never proves automatic generation");
        var water = GTStructureUtility.<Object>ofAnyWater(false);
        var air = StructureUtility.<Object>isAir();
        SimpleAssert.that(HologramNativeSourceWater.reviewedSlot(StructureUtility.ofChain(water, air)),
            "the actual StructureLib and GT5U source-only chain is recognized");
        SimpleAssert.that(!HologramNativeSourceWater.reviewedSlot(StructureUtility.ofChain(
            GTStructureUtility.<Object>ofAnyWater(true), air)), "allowFlowing does not prove a structural source");
        SimpleAssert.that(!HologramNativeSourceWater.reviewedSlot(StructureUtility.ofChain(air, water)),
            "a reversed fallback chain does not prove the same structure");
        SimpleAssert.that(!HologramNativeSourceWater.reviewedSlot(StructureUtility.ofChain(
            StructureUtility.<Object>ofBlock(Blocks.water, 0), air)), "matching registry names do not prove origin");
        IStructureElementChain<Object> unknown = () -> {
            throw new AssertionError("unreviewed declaration callback must not run");
        };
        SimpleAssert.that(!HologramNativeSourceWater.reviewedSlot(unknown), "unknown declaration fails closed");
        SimpleAssert.that(HologramNativeSourceWater.missingFromDetachedPreview(Blocks.air)
            && !HologramNativeSourceWater.missingFromDetachedPreview(Blocks.stone)
            && !HologramNativeSourceWater.missingFromDetachedPreview(Blocks.flowing_water),
            "an occupied preview position is retained, even with the native chain");
        SimpleAssert.that(HologramNativeSourceWater.source(Blocks.water, 0)
            && !HologramNativeSourceWater.source(Blocks.water, 1)
            && !HologramNativeSourceWater.source(Blocks.flowing_water, 0), "only stationary source metadata is valid");
    }

    private static void placementAndMaterialQuote() throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();
        // The actual world observation can be solid. This plan must never touch that slot.
        HologramControllerTarget.Cell captured = waterTarget(Blocks.stone, HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER);
        NBTTagCompound water = captured.tag(), stone = new NBTTagCompound();
        stone.setInteger("x", 12);
        stone.setInteger("y", 64);
        stone.setInteger("z", 20);
        stone.setString("wantId", "minecraft:stone");
        stone.setTag("targetStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        NBTTagCompound state = snapshot(water, stone);
        state.setBoolean("supported", true);
        var plan = HologramProjectedPlacement.compileDisplayed(state, ANCHOR, 256, () -> true,
            index -> index == 0 ? captured : null);
        SimpleAssert.eq(1, plan.cells().size(), "the solid-obstructed generated slot never enters the placement plan");
        SimpleAssert.eq("minecraft:stone", plan.cells().get(0).block(), "unrelated shell placement remains ordinary");
        SimpleAssert.eq(2, plan.cells().get(0).x(), "only the casing coordinate is transmitted");
        SimpleAssert.eq(1, plan.needs().size(), "no material for generated water");
        SimpleAssert.eq("minecraft:stone", plan.needs().get(0).item(), "no bucket or fluid amount in the quote");
        NBTTagCompound wire = HologramPlacementRequest.encode(id, ANCHOR, plan, state);
        var decoded = HologramPlacementRequest.decode(HologramPlacementRequest.header(wire), wire, 256);
        SimpleAssert.eq(1, decoded.size(), "the server wire contains only the casing operation");
        var standalone = HologramPlacementRequest.materialCheck(HologramPlacementRequest.encodeMaterialCheck(id, state));
        SimpleAssert.eq(Collections.singletonMap(new HologramPlacementRequest.Material("minecraft:stone", 0), 1), standalone,
            "the ordinary material-only quote also has no water");
        requireRejection(() -> HologramProjectedPlacement.compileDisplayed(state, ANCHOR, 256, () -> true));
        requireRejection(() -> HologramProjectedPlacement.compileDisplayed(state, ANCHOR, 256, () -> true,
            index -> waterTarget(Blocks.water, HologramControllerTarget.TargetKind.MATERIAL)));
        requireRejection(() -> HologramProjectedPlacement.compileDisplayed(state, new int[] { 9, 64, 20 }, 256,
            () -> true, index -> captured));
        water.setInteger("y", 256);
        requireRejection(() -> HologramProjectedPlacement.compileDisplayed(state, ANCHOR, 256, () -> true,
            index -> captured));
        water.setInteger("y", 64);
        water.setString("wantId", "minecraft:flowing_water");
        requireRejection(() -> HologramProjectedPlacement.compileDisplayed(state, ANCHOR, 256, () -> true,
            index -> captured));
    }

    private static void editableRoundTrip() throws IOException {
        HologramPresets.Structure source = new HologramPresets.Structure();
        source.id = "owner";
        HologramControllerTarget.Cell target = waterTarget(
            Blocks.air, HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER);
        var retained = HologramLocalBlueprintStructure.declared(source, snapshot(target.tag(), null));
        var cell = retained.values().iterator().next();
        SimpleAssert.eq("minecraft:water", cell.block, "native declaration keeps the editable geometry");
        SimpleAssert.that(cell.stack() == null && cell.belongsTo(source.id),
            "editor geometry owns its native member without minting a water item");

        byte[] incoming = ("{\"schema\":2,\"blueprint\":{\"schema\":2,\"id\":\"test\",\"name\":\"Water\",\"cells\":["
            + "{\"x\":2,\"y\":0,\"z\":0,\"block\":\"minecraft:water\",\"metadata\":0,\"nativeSourceWaterPreview\":true},"
            + "{\"x\":3,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"metadata\":0}]}}")
                .getBytes(StandardCharsets.UTF_8);
        requireRejection(() -> HologramProjectedPlacement.compile(incoming, ANCHOR, new int[0], 256, () -> true));
        var document = HologramLocalBlueprintDocument.open(incoming, "");
        var rows = document.snapshot().getTagList("cells", 10);
        SimpleAssert.eq("minecraft:water", rows.getCompoundTagAt(0).getString("wantId"),
            "the area editor preserves imported source water without trusting its tag");
        NBTTagCompound select = new NBTTagCompound();
        select.setInteger("selected", 0);
        document.apply("editDelete", select);
        SimpleAssert.eq(1, document.snapshot().getTagList("cells", 10).tagCount(), "water is removable in the area editor");
        document.apply("editUndo", new NBTTagCompound());
        SimpleAssert.eq(2, document.snapshot().getTagList("cells", 10).tagCount(), "undo restores the water row");
        document.apply("editRedo", new NBTTagCompound());
        byte[] saved = document.artifact();
        var reopened = HologramLocalBlueprintDocument.open(saved, "");
        SimpleAssert.eq(1, reopened.snapshot().getTagList("cells", 10).tagCount(), "saved area retains the targeted delete");
        SimpleAssert.eq("minecraft:stone", reopened.snapshot().getTagList("cells", 10).getCompoundTagAt(0).getString("wantId"),
            "the unaffected casing survives save and reopen");
        var plan = HologramProjectedPlacement.compile(
            HologramBlueprintCompactCodec.portableDocument(saved), ANCHOR, new int[0], 256, () -> true);
        SimpleAssert.eq(1, plan.cells().size(), "the old-file workaround still compiles the unaffected casing");
    }

    private static HologramControllerTarget.Cell waterTarget(Block actual, HologramControllerTarget.TargetKind kind) {
        return new HologramControllerTarget.Cell(
            11, 64, 20, 1, 0, 0, Block.blockRegistry.getNameForObject(actual), "minecraft:water", 0, 0, actual,
            Blocks.water, null, null, null, null, false, false, false, "missing", "block", "", "block", "", -1, -1,
            null, null, -1, false, kind);
    }

    private static NBTTagCompound snapshot(NBTTagCompound first, NBTTagCompound also) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList cells = new NBTTagList();
        cells.appendTag(first);
        if (also != null) cells.appendTag(also);
        result.setTag("cells", cells);
        return result;
    }

    private static void requireRejection(Checked operation) throws IOException {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Unproven or out-of-world water was accepted for placement");
    }

    @FunctionalInterface
    private interface Checked {

        void run() throws IOException;
    }
}
