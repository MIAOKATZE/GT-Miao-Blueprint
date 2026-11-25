// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/** One selected physical cell in, one literal blueprint cell out. Selection never calls a machine definition. */
final class HologramLiteralAreaWriter {
    private final Set<String> paletteKeys = new HashSet<>();
    private long expandedNbt;

    void append(HologramPresets.Preset draft, Block block, int meta, TileEntity tile, int x, int y, int z)
        throws IOException {
        boolean packed = draft.cells instanceof HologramLiteralCaptureRows;
        if (packed) HologramBlueprintLimits.requireLocal((long) draft.cells.size() + 1);
        else HologramBlueprintLimits.require((long) draft.cells.size() + 1);
        NBTTagCompound data = tile == null ? null : HologramNativeCapture.capture(tile);
        ItemStack item = HologramArea.serverSample(block, meta, tile, data);
        HologramPresets.Cell cell = item == null ? new HologramPresets.Cell()
            : HologramPresets.cell(item, x, y, z, -1, false);
        cell.x = x;
        cell.y = y;
        cell.z = z;
        cell.block = Block.blockRegistry.getNameForObject(block);
        cell.metadata = meta;
        if (tile instanceof IGregTechTileEntity gt) cell.facing = HologramBlockIdentity.front(gt);
        if (item != null && item.hasTagCompound()) {
            HologramBlueprintNbt.Encoded encoded = HologramBlueprintNbt
                .encodeCapturedWithSize(block, item.getTagCompound());
            cell.itemNbt = encoded.value;
            expandedNbt += encoded.expandedBytes;
        }
        if (data != null) {
            HologramBlueprintNbt.Encoded encoded = HologramAeBlueprint.supports(block, data)
                ? HologramBlueprintNbt.encodeCanonicalWithSize(block, data)
                : HologramBlueprintNbt.encodeCapturedWithSize(block, data);
            cell.tileNbt = encoded.value;
            expandedNbt += encoded.expandedBytes;
        }
        if (expandedNbt > 64L * 1024 * 1024) throw new IOException("区域设备 NBT 超过 64MiB 上限，请拆分选区");
        // Ordinary blueprints preserve this actual device and its NBT. Machine declarations
        // and tool-channel substitutions belong exclusively to standalone controller mode.
        if (packed) ((HologramLiteralCaptureRows) draft.cells).append(cell, block, tile != null);
        else draft.cells.add(cell);
        if (item != null && paletteKeys.add(cell.item + ":" + cell.damage))
            draft.palette.add(HologramPresets.cell(item, 0, 0, 0, -1, false));
    }
}
