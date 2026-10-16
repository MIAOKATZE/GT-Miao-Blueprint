package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

/** Raw, server-owned block snapshot, including every air cell. No blueprint adapters are involved. */
final class HologramCutEntry {

    private HologramCutEntry() {}

    static int volume(int sx, int sy, int sz) throws IOException {
        long count = (long) sx * sy * sz;
        if (sx <= 0 || sy <= 0
            || sz <= 0
            || sx > HologramCutProtocol.MAX_SIDE
            || sy > HologramCutProtocol.MAX_SIDE
            || sz > HologramCutProtocol.MAX_SIDE
            || count > HologramCutProtocol.MAX_CELLS) throw new IOException("剪切范围超过边界");
        return (int) count;
    }

    static void bounds(int x, int y, int z, int sx, int sy, int sz) throws IOException {
        volume(sx, sy, sz);
        if (x < -30000000 || z < -30000000
            || (long) x + sx > 30000000
            || (long) z + sz > 30000000
            || y < 0
            || (long) y + sy > 256) throw new IOException("剪切范围在世界边界之外");
    }

    static NBTTagCompound capture(WorldServer world, int x, int y, int z, int sx, int sy, int sz) throws IOException {
        bounds(x, y, z, sx, sy, sz);
        HologramCutChunks.loadWithLightNeighbors(world, x, z, sx, sz);
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString(
            "id",
            UUID.randomUUID()
                .toString());
        entry.setInteger("dimension", world.provider.dimensionId);
        entry.setInteger("x", x);
        entry.setInteger("y", y);
        entry.setInteger("z", z);
        entry.setInteger("sizeX", sx);
        entry.setInteger("sizeY", sy);
        entry.setInteger("sizeZ", sz);
        NBTTagList cells = new NBTTagList();
        int count = 0;
        for (int dy = 0; dy < sy; dy++) for (int dz = 0; dz < sz; dz++) for (int dx = 0; dx < sx; dx++) {
            Block block = world.getBlock(x + dx, y + dy, z + dz);
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", dx);
            cell.setInteger("y", dy);
            cell.setInteger("z", dz);
            cell.setString("block", Block.blockRegistry.getNameForObject(block));
            cell.setInteger("meta", world.getBlockMetadata(x + dx, y + dy, z + dz));
            TileEntity tile = world.getTileEntity(x + dx, y + dy, z + dz);
            if (tile != null) {
                NBTTagCompound nbt = new NBTTagCompound();
                tile.writeToNBT(nbt);
                cell.setTag("tile", nbt.copy());
            }
            if (!world.isAirBlock(x + dx, y + dy, z + dz)) count++;
            cells.appendTag(cell);
        }
        entry.setInteger("blocks", count);
        entry.setTag("cells", cells);
        return entry;
    }

    static NBTTagCompound relocatedTile(NBTTagCompound cell, int x, int y, int z) {
        NBTTagCompound tile = (NBTTagCompound) cell.getCompoundTag("tile")
            .copy();
        tile.setInteger("x", x);
        tile.setInteger("y", y);
        tile.setInteger("z", z);
        return tile;
    }

    static NBTTagCompound directory(NBTTagCompound entry) {
        NBTTagCompound result = new NBTTagCompound();
        for (Object value : entry.func_150296_c()) {
            String key = String.valueOf(value);
            if (!"cells".equals(key)) result.setTag(
                key,
                entry.getTag(key)
                    .copy());
        }
        if (result.getString("name")
            .isEmpty()) result.setString("name", "剪切 #" + entry.getLong("sequence"));
        return result;
    }

    static NBTTagCompound preview(NBTTagCompound entry) {
        // Only this authenticated owner's selected private cache is sent. Nothing is filtered from raw preview.
        return (NBTTagCompound) entry.copy();
    }
}
