// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.github.bsideup.jabel.Desugar;

/** Prepared controller geometry, with no references to live or detached worlds. */
public final class HologramControllerTarget {

    private HologramControllerTarget() {}

    /** The capture owns the cells and stops adding to the list before publishing it. */
    @Desugar
    public record Projection(NBTTagCompound metadata, List<Cell> cells) {

        public Projection {
            cells = Collections.unmodifiableList(cells);
        }
    }

    /** Stack, display and hint payloads are capture-owned and read-only after publication. */
    @Desugar
    public record Cell(int x, int y, int z, int dx, int dy, int dz, String id, String wantId, int meta, int wantMeta,
        Block actualBlock, Block targetBlock, ItemStack actualStack, ItemStack targetStack, NBTTagCompound actualDisplay,
        NBTTagCompound targetDisplay, boolean anchor, boolean iconOnly, boolean missingBlock, String status, String role,
        String family, String renderKind, String prompt, int actualFacing, int targetFacing, String[] hintIcons,
        int[] hintTint, int hintIndex, boolean localObservationMissing) {

        /** Only serialization and edit/export operations need a materialized row. */
        public NBTTagCompound tag() {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", x);
            row.setInteger("y", y);
            row.setInteger("z", z);
            row.setInteger("dx", dx);
            row.setInteger("dy", dy);
            row.setInteger("dz", dz);
            row.setString("id", id);
            row.setInteger("meta", meta);
            row.setString("wantId", wantId);
            row.setInteger("wantMeta", wantMeta);
            row.setBoolean("anchor", anchor);
            row.setBoolean("inScope", true);
            row.setBoolean("missingBlock", missingBlock);
            row.setBoolean("iconOnly", iconOnly);
            row.setString("status", status);
            row.setString("role", role);
            row.setString("family", family);
            row.setString("renderKind", renderKind);
            row.setInteger("actualFacing", actualFacing);
            row.setInteger("targetFacing", targetFacing);
            if (actualStack != null) row.setTag("actualStack", stackTag(actualStack));
            if (targetStack != null) row.setTag("targetStack", stackTag(targetStack));
            if (actualDisplay != null) row.setTag("actualDisplay", actualDisplay.copy());
            if (targetDisplay != null) row.setTag("targetDisplay", targetDisplay.copy());
            if (!anchor) row.setString("prompt", prompt);
            if (hintIcons != null) {
                NBTTagList icons = new NBTTagList();
                for (String icon : hintIcons) icons.appendTag(new NBTTagString(icon == null ? "" : icon));
                row.setTag("hintIcons", icons);
            }
            if (hintTint != null) row.setIntArray("hintTint", hintTint.clone());
            row.setInteger("hintIndex", hintIndex);
            if (localObservationMissing) {
                row.setBoolean("localObservationMissing", true);
                row.setString("reason", "客户端尚未读到此位置的现场数据");
            }
            return row;
        }

        private static NBTTagCompound stackTag(ItemStack stack) {
            NBTTagCompound tag = stack.writeToNBT(new NBTTagCompound());
            if (tag.hasKey("tag", 10)) tag.setTag("tag", tag.getTag("tag").copy());
            return tag;
        }
    }
}
