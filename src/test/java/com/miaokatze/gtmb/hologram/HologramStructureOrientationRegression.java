package com.miaokatze.gtmb.hologram;

import java.util.Collections;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.Gson;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Saved member roll/flip, explicit overrides and prior direction fallbacks share both author reconstruction paths. */
final class HologramStructureOrientationRegression {

    private HologramStructureOrientationRegression() {}

    static void run() {
        int id = 32748;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        String name = Thread.currentThread()
            .getName();
        try {
            Thread.currentThread()
                .setName("Server thread");
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = new HologramDynamicDefinitionRegression.DynamicController();
            ExtendedFacing expected = ExtendedFacing
                .of(ExtendedFacing.DEFAULT.getDirection(), Rotation.byIndex(1), Flip.byIndex(1));
            NBTTagCompound state = new NBTTagCompound();
            state.setInteger("mID", id);
            state.setShort(
                "mFacing",
                (short) expected.getDirection()
                    .ordinal());
            state.setByte(
                "eRotation",
                (byte) expected.getRotation()
                    .getIndex());
            state.setByte(
                "eFlip",
                (byte) expected.getFlip()
                    .getIndex());
            HologramDynamicDefinitionRegression.DynamicController nativeMachine = (HologramDynamicDefinitionRegression.DynamicController) HologramDetachedController
                .create(id, ExtendedFacing.DEFAULT, state);
            nativeMachine.loadNBTData((NBTTagCompound) state.copy());
            // Meta-tile load reads rotation/flip, but obtains front from its already loaded base tile, not mFacing
            // in this method's argument. The later author reconstruction must still honor its explicit cell front.
            ExtendedFacing loaded = expected.with(
                nativeMachine.getBaseMetaTileEntity()
                    .getFrontFacing());
            SimpleAssert.eq(loaded, nativeMachine.getExtendedFacing(), "native saved alignment fixture is valid");

            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.id = "member_alignment";
            preset.name = "member alignment";
            preset.controllerType = "area";
            HologramPresets.Structure group = new HologramPresets.Structure();
            group.id = "selected";
            group.controllerId = id;
            group.sourceClass = HologramDynamicDefinitionRegression.DynamicController.class.getName();
            preset.structures.add(group);
            HologramPresets.Cell cell = HologramPresets.cell(
                new ItemStack(Blocks.stone),
                0,
                0,
                0,
                expected.getDirection()
                    .ordinal(),
                true);
            cell.structureId = group.id;
            cell.structureIds = Collections.singletonList(group.id);
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(state);
            preset.cells.add(cell);
            String originalPayload = cell.tileNbt;
            assertBoth(preset, group, expected, "saved member roll/flip without override");
            cell.facing = net.minecraftforge.common.util.ForgeDirection.EAST.ordinal();
            assertBoth(
                preset,
                group,
                expected.with(net.minecraftforge.common.util.ForgeDirection.EAST),
                "explicit cell front overrides direction but keeps saved roll/flip");
            cell.facing = -1;
            assertBoth(
                preset,
                group,
                expected.with(ExtendedFacing.DEFAULT.getDirection()),
                "missing cell front keeps prior direction fallback, not detached base front");
            cell.facing = 99;
            SimpleAssert.eq(
                expected.with(ExtendedFacing.DEFAULT.getDirection()),
                HologramVirtualStructureEditing.controller(preset, group)
                    .getExtendedFacing(),
                "invalid legacy front keeps prior direction fallback");
            cell.facing = net.minecraftforge.common.util.ForgeDirection.EAST.ordinal();
            ExtendedFacing override = ExtendedFacing
                .of(ExtendedFacing.DEFAULT.getDirection(), Rotation.byIndex(2), Flip.NONE);
            preset.controllerFacing = override.ordinal();
            assertBoth(
                preset,
                group,
                override.with(net.minecraftforge.common.util.ForgeDirection.EAST),
                "explicit full preset override wins over saved roll/flip");
            SimpleAssert.eq(originalPayload, cell.tileNbt, "reconstruction does not alter saved payload");
            SimpleAssert.eq(
                override,
                HologramDetachedController.create(id, override, state)
                    .getExtendedFacing(),
                "existing detached controller overload keeps explicit orientation semantics");
            SimpleAssert.eq(
                override,
                HologramDetachedController.create(id, override, new NBTTagCompound(), true)
                    .getExtendedFacing(),
                "empty saved state retains full fallback orientation");
            SimpleAssert.eq(
                override,
                HologramDetachedController.create(id, override, null, true)
                    .getExtendedFacing(),
                "missing saved state retains full fallback orientation");
            preset.controllerFacing = null;
            cell.tileNbt = "";
            assertBoth(
                preset,
                group,
                ExtendedFacing.of(net.minecraftforge.common.util.ForgeDirection.EAST),
                "no payload retains previous cell-front fallback");
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(new NBTTagCompound());
            assertBoth(
                preset,
                group,
                ExtendedFacing.of(net.minecraftforge.common.util.ForgeDirection.EAST),
                "empty payload retains previous cell-front fallback");
        } catch (Exception failure) {
            throw new AssertionError("structure member alignment", failure);
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
            Thread.currentThread()
                .setName(name);
        }
    }

    private static void assertBoth(HologramPresets.Preset preset, HologramPresets.Structure group,
        ExtendedFacing expected, String message) throws Exception {
        ExtendedFacing server = HologramVirtualStructureEditing.controller(preset, group)
            .getExtendedFacing();
        AuthorDocument document = AuthorDocumentIO.readExpanded(
            new Gson().toJsonTree(preset)
                .getAsJsonObject(),
            AuthorDocumentIO.Limits.defaults());
        int local;
        try (
            GTMBExtensions.Registration source = StructureSources.register(
                "gtmb_test:member-alignment",
                HologramDynamicDefinitionRegression.DynamicController.class,
                (controller, signal) -> new StructureSources.Plan(
                    StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
                        .addShape("asymmetric", new String[][] { { "AAA", "A  " } })
                        .addElement('A', StructureUtility.ofBlock(Blocks.stone, 0))
                        .build(),
                    Collections.singletonList(new StructureSources.Piece("asymmetric", 0, 0, 0, signal))),
                true);
            HologramLocalAuthorStructures task = new HologramLocalAuthorStructures(
                document,
                group.id,
                1,
                new NBTTagCompound(),
                false,
                new ItemStack(Blocks.stone),
                true)) {
            int slices = 0;
            while (!task.tick() && slices++ < 1000) {}
            SimpleAssert.that(task.done(), "local native member inspection completes");
            local = task.facingOrdinal();
        }
        SimpleAssert.that(
            server == expected && local == expected.ordinal(),
            message + ": expected=" + expected.ordinal() + " server=" + server.ordinal() + " local=" + local);
    }

}
