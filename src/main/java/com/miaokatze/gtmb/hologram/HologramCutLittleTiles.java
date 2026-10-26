package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.CutTransforms;

/** Complete raw LittleTiles rotations rebuild neighboring voxel references before every quarter turn. */
final class HologramCutLittleTiles {

    private final NBTTagList cells = new NBTTagList();
    private final Map<Integer, NBTTagCompound> rows = new LinkedHashMap<>();
    private int sx, sy, sz;
    private boolean unsupported;

    private HologramCutLittleTiles(NBTTagCompound entry) {
        sx = entry.getInteger("sizeX");
        sy = entry.getInteger("sizeY");
        sz = entry.getInteger("sizeZ");
        NBTTagList source = entry.getTagList("cells", 10);
        for (int i = 0; i < source.tagCount(); i++) {
            NBTTagCompound cell = source.getCompoundTagAt(i);
            if (!"LittleTilesTileEntity".equals(
                cell.getCompoundTag("tile")
                    .getString("id")))
                continue;
            NBTTagCompound copy = (NBTTagCompound) cell.copy();
            cells.appendTag(copy);
            rows.put(i, copy);
        }
    }

    static HologramCutLittleTiles rotate(NBTTagCompound entry, CutTransforms.Transform pose) {
        HologramCutLittleTiles result = new HologramCutLittleTiles(entry);
        if (result.rows.isEmpty() || pose.identity()) return result;
        if (pose.mirror != 0) {
            result.unsupported = true;
            return result;
        }
        try {
            result.turn(true, pose.turnX);
            result.turn(false, pose.rotation);
            for (int i = 0; i < pose.turnZ; i++) {
                result.turn(false, 3);
                result.turn(true, 1);
                result.turn(false, 1);
            }
        } catch (IOException | RuntimeException | LinkageError unsupportedSchema) {
            // A partial graph rotation cannot be mixed with original references from another LittleTiles cell.
            result.unsupported = true;
        }
        return result;
    }

    boolean contains(int index) {
        return rows.containsKey(index);
    }

    NBTTagCompound tile(int index) throws IOException {
        if (unsupported) throw new IOException("LittleTiles structure cannot represent this clipboard transform");
        return (NBTTagCompound) rows.get(index)
            .getCompoundTag("tile")
            .copy();
    }

    private void turn(boolean vertical, int count) throws IOException {
        CutTransforms.Transform quarter = new CutTransforms.Transform(vertical ? 0 : 1, vertical ? 1 : 0, 0, 0);
        for (int n = 0; n < count; n++) {
            Map<String, int[]> references = HologramBlockTransformLittleTiles.rawReferences(cells, vertical);
            for (NBTTagCompound row : rows.values()) {
                int x = row.getInteger("x"), y = row.getInteger("y"), z = row.getInteger("z");
                HologramBlockTransformApi.RotationResult rotated = HologramBlockTransformApi.rotate(
                    Block.getBlockFromName(row.getString("block")),
                    row.getInteger("meta"),
                    row.getCompoundTag("tile"),
                    null,
                    vertical,
                    1,
                    references,
                    x,
                    y,
                    z);
                row.setTag("tile", rotated.tile);
                int[] point = quarter.point(x, y, z, sx, sy, sz);
                row.setInteger("x", point[0]);
                row.setInteger("y", point[1]);
                row.setInteger("z", point[2]);
            }
            int[] size = quarter.size(sx, sy, sz);
            sx = size[0];
            sy = size[1];
            sz = size[2];
        }
    }
}
