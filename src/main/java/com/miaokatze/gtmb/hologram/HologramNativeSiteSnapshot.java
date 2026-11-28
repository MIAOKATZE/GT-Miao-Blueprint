// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Produces the existing frozen snapshot DTO from proved actual cells, without constructing a template. */
final class HologramNativeSiteSnapshot {

    private final BaseMetaTileEntity controller;
    private final List<HologramNativeTargetPlan.Cell> cells;
    private final NBTTagCompound metadata;
    private final NBTTagList rows = new NBTTagList();
    private int cursor;
    private boolean executable = true;

    HologramNativeSiteSnapshot(BaseMetaTileEntity controller, List<HologramNativeTargetPlan.Cell> cells,
        NBTTagCompound settings, String digest, long revision) {
        this.controller = controller;
        this.cells = cells;
        metadata = (NBTTagCompound) settings.copy();
        metadata.setString("session", "native-site");
        metadata.setString("generation", "native-site:" + revision);
        metadata.setString("editorWorkspace", "projection");
        metadata.setString("workspaceTab", "structure");
        metadata.setString("openPurpose", "controllerStructure");
        metadata.setBoolean("localStandardController", true);
        metadata.setBoolean("structureProjection", true);
        metadata.setBoolean("target", true);
        metadata.setBoolean("supported", true);
        metadata.setBoolean("keepController", true);
        metadata.setBoolean("quotesIncomplete", true);
        metadata.setBoolean("capturePending", false);
        metadata.setBoolean("projectionDataComplete", true);
        metadata.setBoolean("nativeTargetReady", true);
        metadata.setBoolean("nativeSiteSnapshot", true);
        metadata.setBoolean("nativeExecutionSupported", true);
        metadata.setBoolean("siteFormed", true);
        metadata.setString("nativeTargetDigest", digest);
        metadata.setLong("nativeTargetRevision", revision);
        metadata.setInteger("dimension", controller.getWorldObj().provider.dimensionId);
        metadata.setInteger("x", controller.xCoord);
        metadata.setInteger("y", controller.yCoord);
        metadata.setInteger("z", controller.zCoord);
        metadata.setInteger("selected", -1);
        metadata.setInteger("hatchTierMax", gregtech.api.enums.GTValues.VN.length - 1);
        var machine = (MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity();
        metadata.setString("title", machine.getLocalName());
        metadata.setString(
            "sourceClass",
            machine.getClass()
                .getName());
        metadata.setString("sourceMod", HologramSessionPolicy.sourceMod(machine.getClass()));
        metadata.setInteger(
            "facing",
            machine.getExtendedFacing()
                .ordinal());
        metadata.setTag(
            "capabilities",
            HologramCapabilities
                .describeNative(
                    machine,
                    HologramNativeTargetPlan.canonicalTrigger(
                        Math.max(1, settings.getInteger("main")),
                        settings.getCompoundTag("channels")),
                    null,
                    machine.getStructureDefinition())
                .withSavedChannels(settings.getCompoundTag("channels"))
                .toNBT());
        Block block = controller.getWorldObj()
            .getBlock(controller.xCoord, controller.yCoord, controller.zCoord);
        var anchor = new HologramNativeTargetPlan.Cell(
            0,
            0,
            0,
            block,
            controller.getBlockMetadata(),
            new ItemStack(block, 1, controller.getMetaTileID()),
            controller.getFrontFacing()
                .ordinal(),
            HologramDisplaySnapshot.projection(controller),
            null,
            null,
            null,
            false);
        rows.appendTag(row(anchor, true));
    }

    boolean advance(long deadline) {
        while (cursor < cells.size() && System.nanoTime() < deadline) {
            var cell = cells.get(cursor++);
            if (!cell.requiredAir() && (cell.material() == null || cell.material()
                .getItem() == null)) executable = false;
            if (cell.dx() != 0 || cell.dy() != 0 || cell.dz() != 0) rows.appendTag(row(cell, false));
        }
        if (cursor != cells.size()) return false;
        metadata.setTag("cells", rows);
        metadata.setBoolean("nativeExecutionSupported", executable);
        metadata.setInteger("siteOccupied", metadata.getInteger("siteNonAir"));
        metadata.setInteger("siteMatchingSolid", metadata.getInteger("siteNonAir"));
        metadata.setInteger(
            "siteHatches",
            metadata.getTagList("nativeHatchChoices", 10)
                .tagCount());
        metadata.setInteger("siteUnknown", 0);
        metadata.setInteger("siteMissing", 0);
        metadata.setInteger("siteDifferent", 0);
        return true;
    }

    NBTTagCompound result() {
        return metadata;
    }

    private NBTTagCompound row(HologramNativeTargetPlan.Cell cell, boolean anchor) {
        String id = Block.blockRegistry.getNameForObject(cell.block());
        boolean machine = cell.material() != null && cell.material()
            .getItem() instanceof ItemMachines;
        boolean hatch = machine && ItemMachines.getMetaTileEntity(cell.material()) instanceof MTEHatch;
        NBTTagCompound row = new HologramControllerTarget.Cell(
            controller.xCoord + cell.dx(),
            controller.yCoord + cell.dy(),
            controller.zCoord + cell.dz(),
            cell.dx(),
            cell.dy(),
            cell.dz(),
            id,
            id,
            cell.metadata(),
            cell.metadata(),
            cell.block(),
            cell.block(),
            cell.material(),
            cell.material(),
            cell.display(),
            cell.display(),
            anchor,
            false,
            false,
            anchor ? "protected" : "satisfied",
            anchor ? "controller" : hatch ? "hatch" : "block",
            "",
            machine ? "item" : "block",
            "",
            cell.facing(),
            cell.facing(),
            null,
            null,
            0,
            false,
            HologramControllerTarget.TargetKind.MATERIAL).tag();
        row.setBoolean("nativeRequiredAir", cell.requiredAir());
        return row;
    }
}
