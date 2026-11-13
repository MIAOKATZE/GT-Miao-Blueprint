// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Objects;

import net.minecraft.block.Block;

/** In-memory provenance minted only after server attachment generation; never serialized or inferred from group IDs. */
final class HologramAttachmentPlacementProof {

    private final Block block;
    private final int metadata, damage;
    private final String tile, item, itemName, projectedItem;

    private HologramAttachmentPlacementProof(HologramCapture.Cell cell) throws IOException {
        block = cell.block;
        metadata = cell.meta;
        damage = cell.blueprintDamage;
        tile = cell.tileNbt;
        item = cell.itemNbt;
        itemName = cell.blueprintItem;
        projectedItem = !item.isEmpty() ? item
            : cell.actualStack.hasTagCompound()
                ? HologramBlueprintNbt.encodeCaptured(block, cell.actualStack.getTagCompound())
                : null;
    }

    static HologramAttachmentPlacementProof generated(HologramCapture.Cell cell) throws IOException {
        if (!cell.userPosition || cell.actualStack == null || cell.block == null)
            throw new IOException("附加对象尚未完成服务端材质校验");
        return new HologramAttachmentPlacementProof(cell);
    }

    boolean matches(HologramCapture.Cell cell, HologramPresets.Cell projected) {
        return cell.userPosition && !cell.unknown
            && cell.block == block
            && cell.meta == metadata
            && damage == cell.blueprintDamage
            && itemName.equals(cell.blueprintItem)
            && Objects.equals(tile, cell.tileNbt)
            && Objects.equals(item, cell.itemNbt)
            && projected.block() == block
            && projected.metadata == metadata
            && projected.damage == damage
            && itemName.equals(projected.item)
            && Objects.equals(tile, projected.tileNbt)
            && Objects.equals(projectedItem, projected.itemNbt);
    }
}
