package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import com.miaokatze.gtmb.api.CutTransforms;

/** Admission checks for native two-cell blocks; never expands the authorized selection. */
final class HologramCutPairs {

    private HologramCutPairs() {}

    /** Reject unrepresentable native pair poses before best-effort raw adapters can turn errors into warnings. */
    static void validatePose(NBTTagCompound entry, CutTransforms.Transform transform) throws IOException {
        NBTTagList cells = entry.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) HologramCutTransformMetadata.validatePairedPose(
            Block.getBlockFromName(
                cells.getCompoundTagAt(i)
                    .getString("block")),
            transform);
    }

    static void validateEntry(NBTTagCompound entry) throws IOException {
        HologramCutEntry.validate(entry);
        Map<Long, NBTTagCompound> selected = selected(entry);
        for (NBTTagCompound cell : selected.values()) {
            Block block = Block.getBlockFromName(cell.getString("block"));
            int metadata = cell.getInteger("meta");
            int[] offset = partner(block, metadata);
            if (offset == null) continue;
            NBTTagCompound other = selected.get(
                key(
                    cell.getInteger("x") + offset[0],
                    cell.getInteger("y") + offset[1],
                    cell.getInteger("z") + offset[2]));
            if (other == null || Block.getBlockFromName(other.getString("block")) != block
                || ((metadata ^ other.getInteger("meta")) & 8) == 0
                || (block instanceof BlockBed && (metadata & 3) != (other.getInteger("meta") & 3)))
                throw new IOException("门、床和双格植物必须完整选中两格；请调整选区后重试");
        }
    }

    /** Replacing even selected air must not leave an existing destination half outside the selected mask. */
    static void validateDestination(World world, NBTTagCompound entry, int ox, int oy, int oz) throws IOException {
        HologramCutEntry.validate(entry);
        Map<Long, NBTTagCompound> selected = selected(entry);
        for (NBTTagCompound cell : selected.values()) {
            int x = cell.getInteger("x"), y = cell.getInteger("y"), z = cell.getInteger("z");
            int[] offset = partner(
                world.getBlock(ox + x, oy + y, oz + z),
                world.getBlockMetadata(ox + x, oy + y, oz + z));
            if (offset != null && !selected.containsKey(key(x + offset[0], y + offset[1], z + offset[2])))
                throw new IOException("放置范围会拆开选区外的门、床或双格植物；请调整位置或选区");
        }
    }

    private static Map<Long, NBTTagCompound> selected(NBTTagCompound entry) {
        Map<Long, NBTTagCompound> result = new HashMap<>();
        NBTTagList cells = entry.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            result.put(key(cell.getInteger("x"), cell.getInteger("y"), cell.getInteger("z")), cell);
        }
        return result;
    }

    private static int[] partner(Block block, int metadata) {
        if (block instanceof BlockDoor || block instanceof BlockDoublePlant)
            return new int[] { 0, (metadata & 8) == 0 ? 1 : -1, 0 };
        if (block instanceof BlockBed) {
            int[] direction = BlockBed.field_149981_a[metadata & 3];
            int sign = (metadata & 8) == 0 ? 1 : -1;
            return new int[] { direction[0] * sign, 0, direction[1] * sign };
        }
        return null;
    }

    // Relative coordinates are bounded to the selection plus one native companion cell, including -1.
    private static long key(int x, int y, int z) {
        return ((long) (x + 1) << 42) | ((long) (y + 1) << 21) | (z + 1L);
    }
}
