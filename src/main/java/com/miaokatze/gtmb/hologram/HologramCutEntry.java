package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.BitSet;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

/** Raw, server-owned selected cells, including selected air. Absent cells are outside the selection. */
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
        return capture(world, x, y, z, sx, sy, sz, null);
    }

    /** Geometry admission only: no world inspection or per-cell permission/native-schema checks. */
    static HologramAreaSelection selection(NBTTagCompound request) throws IOException {
        HologramAreaSelection selection = new HologramAreaSelection();
        if (request.hasKey("areaSelection")) {
            if (!request.hasKey("areaSelection", 10)) throw new IOException("剪切区域组合数据无效");
            selection.readSnapshot(request.getCompoundTag("areaSelection"));
            if (selection.regions.isEmpty()) throw new IOException("请先完成至少一个剪切区域");
        } else {
            for (String key : new String[] { "x1", "y1", "z1", "x2", "y2", "z2" })
                if (!request.hasKey(key, 3)) throw new IOException("剪切区域角点数据不完整");
            selection.point(1, request.getInteger("x1"), request.getInteger("y1"), request.getInteger("z1"));
            selection.point(2, request.getInteger("x2"), request.getInteger("y2"), request.getInteger("z2"));
        }
        int[] box = selection.bounds();
        long sx = (long) box[3] - box[0] + 1, sy = (long) box[4] - box[1] + 1, sz = (long) box[5] - box[2] + 1;
        if (sx > HologramCutProtocol.MAX_SIDE || sy > HologramCutProtocol.MAX_SIDE || sz > HologramCutProtocol.MAX_SIDE)
            throw new IOException("剪切范围超过边界");
        bounds(box[0], box[1], box[2], (int) sx, (int) sy, (int) sz);
        return selection;
    }

    static BitSet selectionMask(HologramAreaSelection selection, int x, int y, int z, int sx, int sy, int sz)
        throws IOException {
        return HologramCutSelection.mask(selection, x, y, z, sx, sy, sz);
    }

    static NBTTagCompound capture(WorldServer world, int x, int y, int z, int sx, int sy, int sz,
        HologramAreaSelection selection) throws IOException {
        bounds(x, y, z, sx, sy, sz);
        BitSet mask = selectionMask(selection, x, y, z, sx, sy, sz);
        HologramCutChunks.loadWithLightNeighbors(world, mask, x, z, sx, sz);
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
        // The cells list is also the mask. Selected air has a row; unselected gaps never have one.
        entry.setInteger("selectionVersion", 1);
        entry.setInteger("selectedCells", mask.cardinality());
        NBTTagList cells = new NBTTagList();
        int count = 0;
        for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1)) {
            int dx = index % sx, dz = index / sx % sz, dy = index / (sx * sz);
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

    /** Validate the complete raw selection before any world mutation; old entries remain full cuboids. */
    static void validate(NBTTagCompound entry) throws IOException {
        Validation validation = new Validation(entry);
        while (!validation.step(4096, Long.MAX_VALUE)) {}
    }

    /** Validation cursor belongs to an immutable owned entry and never publishes a partial admission. */
    static final class Validation {

        private final int sx, sy, sz;
        private final NBTTagList cells;
        private final BitSet seen;
        private int cursor;

        Validation(NBTTagCompound entry) throws IOException {
            sx = entry.getInteger("sizeX");
            sy = entry.getInteger("sizeY");
            sz = entry.getInteger("sizeZ");
            int count = volume(sx, sy, sz);
            cells = entry.getTagList("cells", 10);
            int expected = count;
            if (entry.hasKey("selectionVersion")) {
                if (!entry.hasKey("selectionVersion", 3) || entry.getInteger("selectionVersion") != 1
                    || !entry.hasKey("selectedCells", 3)) throw new IOException("??????????");
                expected = entry.getInteger("selectedCells");
            } else if (entry.hasKey("selectedCells")) throw new IOException("??????????");
            if (!entry.hasKey("cells", 9) || expected <= 0 || expected > count || cells.tagCount() != expected)
                throw new IOException("???????");
            seen = new BitSet(count);
        }

        boolean step(int maximumCells, long deadline) throws IOException {
            if (maximumCells <= 0) throw new IllegalArgumentException("maximumCells");
            int worked = 0;
            while (cursor < cells.tagCount() && worked < maximumCells
                && (worked == 0 || System.nanoTime() < deadline)) {
                NBTTagCompound cell = cells.getCompoundTagAt(cursor);
                int x = cell.getInteger("x"), y = cell.getInteger("y"), z = cell.getInteger("z");
                if (!cell.hasKey("x", 3) || !cell.hasKey("y", 3)
                    || !cell.hasKey("z", 3)
                    || x < 0
                    || x >= sx
                    || y < 0
                    || y >= sy
                    || z < 0
                    || z >= sz) throw new IOException("????????");
                int index = (y * sz + z) * sx + x;
                if (seen.get(index)) throw new IOException("????????");
                seen.set(index);
                cursor++;
                worked++;
            }
            return cursor == cells.tagCount();
        }
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
