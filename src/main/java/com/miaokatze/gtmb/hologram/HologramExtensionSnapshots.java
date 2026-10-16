package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.BlueprintSnapshots.Cell;
import com.miaokatze.gtmb.api.BlueprintSnapshots.Snapshot;
import com.miaokatze.gtmb.api.BlueprintSnapshots.Stage;

/** Translation boundary: public processors receive detached data, never internal cells, sessions or world handles. */
final class HologramExtensionSnapshots {

    private HologramExtensionSnapshots() {}

    static NBTTagCompound capture(HologramCapture capture) throws IOException {
        if (!BlueprintSnapshots.hasProcessors(Stage.CAPTURE_COMPLETE)) return new NBTTagCompound();
        List<Cell> cells = new ArrayList<>();
        for (HologramCapture.Cell cell : capture.cells) cells.add(
            new Cell(
                cell.x,
                cell.y,
                cell.z,
                cell.block == null ? "" : String.valueOf(Block.blockRegistry.getNameForObject(cell.block)),
                cell.meta,
                cell.role,
                cell.unknown,
                cell.anchor,
                cell.excluded,
                HologramBlueprintNbt.decode(cell.tileNbt),
                HologramBlueprintNbt.decode(cell.itemNbt)));
        return BlueprintSnapshots.inspect(
            new Snapshot(
                Stage.CAPTURE_COMPLETE,
                capture.context == null ? ""
                    : capture.context.getClass()
                        .getName(),
                cells));
    }

    static NBTTagCompound authoring(HologramPresets.Preset preset) throws IOException {
        if (!BlueprintSnapshots.hasProcessors(Stage.AUTHORING_COMPLETE)) return new NBTTagCompound();
        List<Cell> cells = new ArrayList<>();
        for (HologramPresets.Cell cell : preset.cells) cells.add(
            new Cell(
                cell.x,
                cell.y,
                cell.z,
                cell.block,
                cell.metadata,
                "",
                false,
                cell.controller,
                cell.excluded,
                HologramBlueprintNbt.decode(cell.tileNbt),
                HologramBlueprintNbt.decode(cell.itemNbt)));
        return BlueprintSnapshots.inspect(new Snapshot(Stage.AUTHORING_COMPLETE, preset.controllerType, cells));
    }
}
