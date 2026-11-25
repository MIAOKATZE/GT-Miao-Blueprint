// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
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

    private HologramNativeSourceWaterRegression() {}

    static void run() {
        try {
            nativeDeclaration();
            placementAndMaterialQuote();
            editableRoundTrip();
        } catch (IOException failure) {
            throw new AssertionError("native generated water fixture", failure);
        }
    }

    private static void nativeDeclaration() {
        SimpleAssert.that(
            HologramNativeSourceWater.reviewedController(MTEOreWashingPlant.class)
                && HologramNativeSourceWater.reviewedController(MTEIndustrialChemicalBath.class),
            "both exact audited machines have native source water generation");
        SimpleAssert.that(
            !HologramNativeSourceWater.reviewedController(MTESteamWasher.class)
                && !HologramNativeSourceWater.reviewedController(MTEIndustrialRockBreaker.class),
            "the same authored water/air declaration alone never proves automatic generation");
        var water = GTStructureUtility.<Object>ofAnyWater(false);
        var air = StructureUtility.<Object>isAir();
        SimpleAssert.that(
            HologramNativeSourceWater.reviewedSlot(StructureUtility.ofChain(water, air)),
            "the actual StructureLib and GT5U source-only chain is recognized");
        SimpleAssert.that(
            !HologramNativeSourceWater
                .reviewedSlot(StructureUtility.ofChain(GTStructureUtility.<Object>ofAnyWater(true), air)),
            "allowFlowing does not prove a structural source");
        SimpleAssert.that(
            !HologramNativeSourceWater.reviewedSlot(StructureUtility.ofChain(air, water)),
            "a reversed fallback chain does not prove the same structure");
        SimpleAssert.that(
            !HologramNativeSourceWater
                .reviewedSlot(StructureUtility.ofChain(StructureUtility.<Object>ofBlock(Blocks.water, 0), air)),
            "matching registry names do not prove origin");
        IStructureElementChain<Object> unknown = () -> {
            throw new AssertionError("unreviewed declaration callback must not run");
        };
        SimpleAssert.that(!HologramNativeSourceWater.reviewedSlot(unknown), "unknown declaration fails closed");
        SimpleAssert.that(
            HologramNativeSourceWater.missingFromDetachedPreview(Blocks.air)
                && !HologramNativeSourceWater.missingFromDetachedPreview(Blocks.stone)
                && !HologramNativeSourceWater.missingFromDetachedPreview(Blocks.flowing_water),
            "an occupied preview position is retained, even with the native chain");
        SimpleAssert.that(
            HologramNativeSourceWater.source(Blocks.water, 0) && !HologramNativeSourceWater.source(Blocks.water, 1)
                && !HologramNativeSourceWater.source(Blocks.flowing_water, 0),
            "only stationary source metadata is valid");
    }

    private static void placementAndMaterialQuote() {
        HologramControllerTarget.Cell captured = waterTarget(
            Blocks.stone,
            HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER);
        NBTTagCompound row = captured.tag();
        SimpleAssert.that(
            HologramNativeSourceWater.matches(captured, row),
            "typed native source-water proof retains an occupied generated position without demanding an item");
        SimpleAssert.that(captured.targetStack() == null, "native generated water never mints a bucket or item");
        SimpleAssert.that(
            !HologramNativeSourceWater.matches(null, row),
            "a presentation tag alone cannot authorize generated water");
        SimpleAssert.that(
            !HologramNativeSourceWater
                .matches(waterTarget(Blocks.water, HologramControllerTarget.TargetKind.MATERIAL), row),
            "an ordinary target with water appearance does not acquire a source exemption");
        row.setInteger("x", row.getInteger("x") + 1);
        SimpleAssert.that(
            !HologramNativeSourceWater.matches(captured, row),
            "typed source proof is bound to the exact position");
        row = captured.tag();
        row.setString("wantId", "minecraft:flowing_water");
        SimpleAssert.that(
            !HologramNativeSourceWater.matches(captured, row),
            "flowing water does not acquire an automatic-generation exemption");
    }

    private static void editableRoundTrip() throws IOException {
        HologramPresets.Structure source = new HologramPresets.Structure();
        source.id = "owner";
        HologramControllerTarget.Cell target = waterTarget(
            Blocks.air,
            HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER);
        var retained = HologramLocalBlueprintStructure.declared(source, snapshot(target.tag(), null));
        var cell = retained.values()
            .iterator()
            .next();
        SimpleAssert.eq("minecraft:water", cell.block, "native declaration keeps the editable geometry");
        SimpleAssert.that(
            cell.stack() == null && cell.belongsTo(source.id),
            "editor geometry owns its native member without minting a water item");

        byte[] incoming = ("{\"schema\":2,\"blueprint\":{\"schema\":2,\"id\":\"test\",\"name\":\"Water\",\"cells\":["
            + "{\"x\":2,\"y\":0,\"z\":0,\"block\":\"minecraft:water\",\"metadata\":0,\"nativeSourceWaterPreview\":true},"
            + "{\"x\":3,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"metadata\":0}]}}")
                .getBytes(StandardCharsets.UTF_8);
        var document = HologramLocalBlueprintDocument.open(incoming, "");
        var rows = document.snapshot()
            .getTagList("cells", 10);
        SimpleAssert.eq(
            "minecraft:water",
            rows.getCompoundTagAt(0)
                .getString("wantId"),
            "the area editor preserves imported source water without trusting its tag");
        NBTTagCompound select = new NBTTagCompound();
        select.setInteger("selected", 0);
        document.apply("editDelete", select);
        SimpleAssert.eq(
            1,
            document.snapshot()
                .getTagList("cells", 10)
                .tagCount(),
            "water is removable in the area editor");
        document.apply("editUndo", new NBTTagCompound());
        SimpleAssert.eq(
            2,
            document.snapshot()
                .getTagList("cells", 10)
                .tagCount(),
            "undo restores the water row");
        document.apply("editRedo", new NBTTagCompound());
        byte[] saved = document.artifact();
        var reopened = HologramLocalBlueprintDocument.open(saved, "");
        SimpleAssert.eq(
            1,
            reopened.snapshot()
                .getTagList("cells", 10)
                .tagCount(),
            "saved area retains the targeted delete");
        SimpleAssert.eq(
            "minecraft:stone",
            reopened.snapshot()
                .getTagList("cells", 10)
                .getCompoundTagAt(0)
                .getString("wantId"),
            "the unaffected casing survives save and reopen");
    }

    private static HologramControllerTarget.Cell waterTarget(Block actual, HologramControllerTarget.TargetKind kind) {
        return new HologramControllerTarget.Cell(
            11,
            64,
            20,
            1,
            0,
            0,
            Block.blockRegistry.getNameForObject(actual),
            "minecraft:water",
            0,
            0,
            actual,
            Blocks.water,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            "missing",
            "block",
            "",
            "block",
            "",
            -1,
            -1,
            null,
            null,
            -1,
            false,
            kind);
    }

    private static NBTTagCompound snapshot(NBTTagCompound first, NBTTagCompound also) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList cells = new NBTTagList();
        cells.appendTag(first);
        if (also != null) cells.appendTag(also);
        result.setTag("cells", cells);
        return result;
    }

}
