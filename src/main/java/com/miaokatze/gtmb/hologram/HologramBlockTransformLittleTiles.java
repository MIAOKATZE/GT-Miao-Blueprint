package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** LittleTiles 1.6.59: voxel boxes, cutout basis and inter-cell structure references are one transform. */
final class HologramBlockTransformLittleTiles {

    private HologramBlockTransformLittleTiles() {}

    static Map<String, int[]> presetReferences(List<HologramPresets.Cell> cells, boolean vertical) throws IOException {
        Map<String, int[]> result = new HashMap<>();
        for (HologramPresets.Cell cell : cells)
            references(result, HologramBlueprintNbt.decode(cell.tileNbt), cell.x, cell.y, cell.z, vertical);
        return result;
    }

    static Map<String, int[]> captureReferences(List<HologramCapture.Cell> cells, boolean vertical) throws IOException {
        Map<String, int[]> result = new HashMap<>();
        for (HologramCapture.Cell cell : cells)
            references(result, HologramBlueprintNbt.decode(cell.tileNbt), cell.x, cell.y, cell.z, vertical);
        return result;
    }

    /** Raw clipboard cells use the same local cell/corner lookup as blueprint rotations. */
    static Map<String, int[]> rawReferences(NBTTagList cells, boolean vertical) throws IOException {
        Map<String, int[]> result = new HashMap<>();
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            NBTTagCompound tile = cell.getCompoundTag("tile");
            if (!"LittleTilesTileEntity".equals(tile.getString("id"))) continue;
            references(result, tile, cell.getInteger("x"), cell.getInteger("y"), cell.getInteger("z"), vertical);
        }
        return result;
    }

    private static void references(Map<String, int[]> map, NBTTagCompound tag, int x, int y, int z, boolean vertical)
        throws IOException {
        if (tag == null || !tag.hasKey("tilesCount")) return;
        int count = bounded(tag.getInteger("tilesCount"));
        for (int i = 0; i < count; i++) {
            NBTTagCompound tile = tag.getCompoundTag("t" + i);
            if (tile.getInteger("bSize") <= 0) continue;
            int[] old = box(tile, "bBox0"), next = rotateBox(old, vertical);
            map.put(key(x, y, z, old[0], old[1], old[2]), new int[] { next[0], next[1], next[2] });
        }
    }

    static void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
        if (data.tile == null) return;
        int count = bounded(data.tile.getInteger("tilesCount"));
        for (int i = 0; i < count; i++) {
            NBTTagCompound tile = data.tile.getCompoundTag("t" + i);
            int boxes = bounded(tile.getInteger("bSize"));
            if (boxes > 1) throw new IOException("Unsupported legacy LittleTiles multi-box tile");
            int[] old = boxes == 1 ? box(tile, "bBox0") : null;
            if (old != null) {
                int[] next = rotateBox(old, vertical);
                writeBox(tile, "bBox0", next);
                writeVec(tile, "cVec", false, new int[] { next[0], next[1], next[2] });
                cutout(tile, old, vertical);
            }
            if (tile.getBoolean("isStructure")) {
                if (tile.getBoolean("main")) {
                    String type = tile.getString("id");
                    if (!("fixed".equals(type) || "chair".equals(type) || "ladder".equals(type) || "bed".equals(type)))
                        throw new IOException("Unsupported LittleTiles structure transform: " + type);
                    int references = bounded(tile.getInteger("count"));
                    for (int n = 0; n < references; n++) reference(data, tile, "i" + n, vertical);
                } else reference(data, tile, "", vertical);
            }
        }
        // Item/dropStack is an inventory representation; it is never rewritten as a placed tile.
    }

    private static int bounded(int count) throws IOException {
        if (count < 0 || count > 65536) throw new IOException("LittleTiles entry count exceeds blueprint bound");
        return count;
    }

    private static void reference(HologramBlockTransforms.Data data, NBTTagCompound tile, String prefix,
        boolean vertical) throws IOException {
        if (tile.hasKey(prefix + "coX"))
            throw new IOException("Legacy LittleTiles absolute references require recapture");
        int[] offset = vec(tile, prefix + "coord", true), corner = vec(tile, prefix + "pos", false);
        int[] nextCorner = data.littleReferences == null ? null
            : data.littleReferences
                .get(key(data.x + offset[0], data.y + offset[1], data.z + offset[2], corner[0], corner[1], corner[2]));
        if (nextCorner == null) throw new IOException("LittleTiles referenced tile is outside the captured blueprint");
        writeVec(
            tile,
            prefix + "coord",
            true,
            HologramVirtualTransform.turn(offset[0], offset[1], offset[2], vertical));
        writeVec(tile, prefix + "pos", false, nextCorner);
    }

    private static String key(int x, int y, int z, int a, int b, int c) {
        return x + "," + y + "," + z + ":" + a + "," + b + "," + c;
    }

    static int[] rotateBox(int[] old, boolean vertical) {
        int[] a = point(old[0], old[1], old[2], vertical), b = point(old[3], old[4], old[5], vertical);
        return new int[] { Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]),
            Math.max(a[1], b[1]), Math.max(a[2], b[2]) };
    }

    static int[] point(int x, int y, int z, boolean vertical) {
        return vertical ? new int[] { x, 16 - z, y } : new int[] { 16 - z, y, x };
    }

    private static int[] box(NBTTagCompound tag, String key) {
        return new int[] { tag.getInteger(key + "minX"), tag.getInteger(key + "minY"), tag.getInteger(key + "minZ"),
            tag.getInteger(key + "maxX"), tag.getInteger(key + "maxY"), tag.getInteger(key + "maxZ") };
    }

    private static void writeBox(NBTTagCompound tag, String key, int[] box) {
        String[] fields = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
        for (int i = 0; i < 6; i++) tag.setInteger(key + fields[i], box[i]);
    }

    private static int[] vec(NBTTagCompound tag, String key, boolean uppercase) {
        return new int[] { tag.getInteger(key + (uppercase ? "X" : "x")), tag.getInteger(key + (uppercase ? "Y" : "y")),
            tag.getInteger(key + (uppercase ? "Z" : "z")) };
    }

    private static void writeVec(NBTTagCompound tag, String key, boolean uppercase, int[] vector) {
        String[] fields = uppercase ? new String[] { "X", "Y", "Z" } : new String[] { "x", "y", "z" };
        for (int i = 0; i < 3; i++) tag.setInteger(key + fields[i], vector[i]);
    }

    private static void cutout(NBTTagCompound tile, int[] oldBox, boolean vertical) throws IOException {
        if (!tile.hasKey("cutoutType")) return;
        int orientation = tile.getByte("cutoutOrientation") & 255;
        tile.setByte("cutoutOrientation", (byte) orientation(orientation, vertical));
        int[] size = vec(tile, "cutoutSize", true), pos = vec(tile, "cutoutPos", true);
        int[] max = { pos[0] + size[0] - oldBox[3] + oldBox[0], pos[1] + size[1] - oldBox[4] + oldBox[1],
            pos[2] + size[2] - oldBox[5] + oldBox[2] };
        int[] a = HologramVirtualTransform.turn(pos[0], pos[1], pos[2], vertical);
        int[] b = HologramVirtualTransform.turn(max[0], max[1], max[2], vertical);
        writeVec(
            tile,
            "cutoutPos",
            true,
            new int[] { Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]) });
        int[] rotatedSize = HologramVirtualTransform.turn(size[0], size[1], size[2], vertical);
        writeVec(
            tile,
            "cutoutSize",
            true,
            new int[] { Math.abs(rotatedSize[0]), Math.abs(rotatedSize[1]), Math.abs(rotatedSize[2]) });
        if (tile.getByte("cutoutType") == 2) {
            for (String field : new String[] { "cutoutFaceStart", "cutoutFaceEnd" })
                tile.setByte(field, (byte) HologramVirtualTransform.front(tile.getByte(field), vertical));
            int[] neg = HologramVirtualTransform.turn(
                tile.getBoolean("cutoutNegX") ? -1 : 1,
                tile.getBoolean("cutoutNegY") ? -1 : 1,
                tile.getBoolean("cutoutNegZ") ? -1 : 1,
                vertical);
            tile.setBoolean("cutoutNegX", neg[0] < 0);
            tile.setBoolean("cutoutNegY", neg[1] < 0);
            tile.setBoolean("cutoutNegZ", neg[2] < 0);
        }
        // DEFORMED_BOX cutoutCorner0..7 is intentionally in orientation-zero space (Mesh3dUtil); rotate only basis.
    }

    private static int orientation(int id, boolean vertical) throws IOException {
        try {
            Class<?> mapper = Class.forName("com.creativemd.littletiles.client.util3d.OrientationMapper");
            Object original = mapper.getMethod("fromId", int.class)
                .invoke(null, id);
            Class<?> matrixClass = original.getClass();
            Object rotated = matrixClass.getConstructor()
                .newInstance();
            for (int column = 0; column < 3; column++) {
                int[] vector = new int[3];
                for (int row = 0; row < 3; row++) vector[row] = Math.round(
                    matrixClass.getField("m" + column + row)
                        .getFloat(original));
                int[] next = HologramVirtualTransform.turn(vector[0], vector[1], vector[2], vertical);
                for (int row = 0; row < 3; row++) matrixClass.getField("m" + column + row)
                    .setFloat(rotated, next[row]);
            }
            return (Integer) mapper.getMethod("toId", matrixClass)
                .invoke(null, rotated);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
            throw new IOException("LittleTiles 1.6.59 cutout orientation API unavailable", invalid);
        }
    }
}
