// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import bartworks.system.material.BWMetaGeneratedBlocksCasing;

/**
 * GT5 .265 stores BW material in a tile; .275/.277 store its full short in block metadata.
 * Remove the old representation branch only when GTNH 2.9.0 and its RAW archives are no longer supported.
 */
final class HologramBwMaterialCompatibility {

    private static final String PACKAGE = "bartworks.system.material.";
    private static final String[] IDS = { "bw.werkstoffblockTE", "bw.werkstoffblockcasingTE",
        "bw.werkstoffblockscasingadvancedTE" };
    private static final String[] BLOCKS = { "bartworks:bw.werkstoffblocks.01", "bartworks:bw.werkstoffblockscasing.01",
        "bartworks:bw.werkstoffblockscasingadvanced.01" };
    private static final String[] TILES = { "BWTileEntityMetaGeneratedWerkstoffBlock",
        "BWTileEntityMetaGeneratedBlocksCasing", "BWTileEntityMetaGeneratedBlocksCasingAdvanced" };
    private static final Set<String> RAW_FIELDS = new HashSet<>(Arrays.asList("id", "x", "y", "z", "m"));

    private HologramBwMaterialCompatibility() {}

    static int actualDamage(Block block, int metadata, TileEntity tile) {
        if (block.getClass() != BWMetaGeneratedBlocksCasing.class) return 0;
        if (!block.hasTileEntity(metadata)) return positiveMaterial(metadata);
        if (tile == null) return 0;
        String name = tile.getClass()
            .getName();
        if (!name.equals(PACKAGE + TILES[1]) && !name.equals(PACKAGE + TILES[2])) return 0;
        NBTTagCompound data = new NBTTagCompound();
        // The audited .265 writer only emits the base identity/coordinates and short m.
        tile.writeToNBT(data);
        return positiveMaterial(data.getShort("m"));
    }

    private static int positiveMaterial(int value) {
        return value > 0 && value <= Short.MAX_VALUE ? value : 0;
    }

    /** Convert a private RAW copy before selecting a legacy tile class; never alter the archive input. */
    static NBTTagCompound forRestoration(NBTTagCompound entry) throws IOException {
        NBTTagCompound result = (NBTTagCompound) entry.copy();
        NBTTagList cells = result.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (!cell.hasKey("tile", 10)) continue;
            NBTTagCompound tile = cell.getCompoundTag("tile");
            int selected = Arrays.asList(IDS)
                .indexOf(tile.getString("id"));
            if (selected < 0) continue;
            Block block = Block.getBlockFromName(cell.getString("block"));
            if (!BLOCKS[selected].equals(cell.getString("block")) || block == null)
                throw new IOException("BW RAW 方块与旧实体身份不一致，原记录已保留");
            int material = tile.hasKey("m", 2) ? positiveMaterial(tile.getShort("m")) : 0;
            if (material == 0 || !RAW_FIELDS.containsAll(tile.func_150296_c()))
                throw new IOException("BW RAW 材料或附加数据不能无损迁移，原记录已保留");
            if (block.hasTileEntity(cell.getInteger("meta"))) continue;
            String expected = selected == 0 ? "BWMetaGeneratedWerkstoffBlocks" : "BWMetaGeneratedBlocksCasing";
            if (!block.getClass()
                .getName()
                .equals(PACKAGE + expected)) throw new IOException("BW RAW 目标不是已审查的材料方块，原记录已保留");
            cell.setInteger("meta", material);
            cell.removeTag("tile");
        }
        return result;
    }
}
