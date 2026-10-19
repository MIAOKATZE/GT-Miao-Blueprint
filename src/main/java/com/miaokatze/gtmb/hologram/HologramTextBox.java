package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.nbt.NBTTagCompound;

/** Editable text definition and bounded, deterministic 1/16-block LittleTiles geometry. No world access. */
public final class HologramTextBox {

    public static final String PREFIX = "gtmb_text_";
    public static final int MAX_VOXELS = 262144, MAX_BLOCKS = 2048;
    public String id = "", text = "猫猫蓝图", font = "minecraft", material = "minecraft:wool";
    public int metadata, color = 0xffffff, width, height, pixelX = 1, pixelY = 1, thickness = 1;
    public int x, y, z;
    public double yaw, pitch, roll, arc, bend, twist, shear;
    public boolean bold, underline, vertical;
    public byte[] bitmap = new byte[0];
    public int[] turns = new int[0];

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id);
        tag.setString("text", text);
        tag.setString("font", font);
        tag.setString("material", material);
        tag.setInteger("metadata", metadata);
        tag.setInteger("color", color);
        tag.setInteger("width", width);
        tag.setIntArray("turns", turns);
        tag.setInteger("height", height);
        tag.setByteArray("bitmap", bitmap);
        tag.setInteger("pixelX", pixelX);
        tag.setInteger("pixelY", pixelY);
        tag.setInteger("thickness", thickness);
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setDouble("yaw", yaw);
        tag.setDouble("pitch", pitch);
        tag.setDouble("roll", roll);
        tag.setDouble("arc", arc);
        tag.setDouble("bend", bend);
        tag.setDouble("twist", twist);
        tag.setDouble("shear", shear);
        tag.setBoolean("bold", bold);
        tag.setBoolean("underline", underline);
        tag.setBoolean("vertical", vertical);
        return tag;
    }

    public static HologramTextBox read(NBTTagCompound tag) throws IOException {
        HologramTextBox result = new HologramTextBox();
        result.id = tag.getString("id");
        result.text = tag.getString("text");
        result.font = tag.getString("font");
        result.material = tag.getString("material");
        result.metadata = tag.getInteger("metadata");
        result.color = tag.getInteger("color") & 0xffffff;
        result.width = tag.getInteger("width");
        result.turns = tag.getIntArray("turns");
        result.height = tag.getInteger("height");
        result.bitmap = tag.getByteArray("bitmap");
        result.pixelX = tag.getInteger("pixelX");
        result.pixelY = tag.getInteger("pixelY");
        result.thickness = tag.getInteger("thickness");
        result.x = tag.getInteger("x");
        result.y = tag.getInteger("y");
        result.z = tag.getInteger("z");
        result.yaw = tag.getDouble("yaw");
        result.pitch = tag.getDouble("pitch");
        result.roll = tag.getDouble("roll");
        result.arc = tag.getDouble("arc");
        result.bend = tag.getDouble("bend");
        result.twist = tag.getDouble("twist");
        result.shear = tag.getDouble("shear");
        result.bold = tag.getBoolean("bold");
        result.underline = tag.getBoolean("underline");
        result.vertical = tag.getBoolean("vertical");
        result.validate();
        return result;
    }

    public void validate() throws IOException {
        if (turns.length > 96) throw new IOException("文字旋转历史过长，请重建文本框");
        for (int turn : turns) if (turn != 1 && turn != 2) throw new IOException("文字旋转记录无效");
        if ((!id.isEmpty() && !id.matches(PREFIX + "[a-f0-9-]{36}")) || text.length() > 512
            || text.trim()
                .isEmpty()
            || !font.matches("minecraft|miao|fusion|dejavu|dejavu_serif")
            || !material.matches("[A-Za-z0-9_.|+-]+:[A-Za-z0-9_./-]+")
            || metadata < 0
            || metadata > 32767
            || width < 1
            || width > 1024
            || height < 1
            || height > 128
            || bitmap.length != (width * height + 7) / 8
            || pixelX < 1
            || pixelX > 16
            || pixelY < 1
            || pixelY > 16
            || thickness < 1
            || thickness > 16
            || Math.abs((long) x) > 30000000
            || Math.abs((long) y) > 256
            || Math.abs((long) z) > 30000000) throw new IOException("文字内容、像素大小、厚度、材质或栅格超出范围");
        for (double value : new double[] { yaw, pitch, roll, arc, bend, twist, shear })
            if (!Double.isFinite(value) || Math.abs(value) > 360) throw new IOException("文字形变参数超出范围");
    }

    public boolean pixel(int px, int py) {
        int bit = py * width + px;
        return (bitmap[bit >>> 3] & (1 << (bit & 7))) != 0;
    }

    public static final class Cell {

        public final int x, y, z;
        public final NBTTagCompound tile;

        Cell(int x, int y, int z, NBTTagCompound tile) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.tile = tile;
        }
    }

    /** Column sampling plus Euler rotation; overlapping samples of the same text are unioned before packing. */
    public List<Cell> generate() throws IOException {
        validate();
        Map<String, TreeSet<Integer>> blocks = new LinkedHashMap<>();
        Map<String, int[]> positions = new LinkedHashMap<>();
        int voxels = 0, attempts = 0;
        double span = Math.max(1, width * pixelX - 1), verticalSpan = height * pixelY;
        for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) {
            if (!pixel(px, py)) continue;
            for (int sx = 0; sx < pixelX; sx++)
                for (int sy = 0; sy < pixelY; sy++) for (int sz = 0; sz < thickness; sz++) {
                    if (++attempts > MAX_VOXELS * 4) throw new IOException("文字采样超过上限，请减小文字、像素大小或厚度");
                    double xx = px * pixelX + sx, yy = (height - py - 1) * pixelY + sy, zz = sz;
                    if (Math.abs(arc) >= .001) {
                        double angle = Math.toRadians(arc), radius = span / angle, at = xx / span * angle;
                        xx = Math.sin(at) * (radius + yy);
                        yy = Math.cos(at) * (radius + yy) - radius;
                    }
                    if (Math.abs(bend) >= .001) {
                        double angle = Math.toRadians(bend), radius = span / angle,
                            at = (px * pixelX + sx) / span * angle;
                        double before = xx;
                        xx = Math.sin(at) * (radius + zz);
                        zz = Math.cos(at) * (radius + zz) - radius;
                        // Arc and spatial curvature remain independently editable.
                        if (Math.abs(arc) >= .001) xx += before - px * pixelX - sx;
                    }
                    if (twist != 0) {
                        double at = Math.toRadians(twist) * (px * pixelX + sx) / span;
                        double oldY = yy - verticalSpan / 2;
                        yy = Math.cos(at) * oldY - Math.sin(at) * zz + verticalSpan / 2;
                        zz = Math.sin(at) * oldY + Math.cos(at) * zz;
                    }
                    xx += shear * yy / 100;
                    if (vertical) {
                        double previous = xx;
                        xx = -yy;
                        yy = previous;
                    }
                    double[] turned = rotate(xx, yy, zz, yaw, pitch, roll);
                    int vx = (int) Math.round(turned[0]), vy = (int) Math.round(turned[1]),
                        vz = (int) Math.round(turned[2]);
                    for (int turn : turns) {
                        int previousZ = vz;
                        if (turn == 1) {
                            vz = vx;
                            vx = 15 - previousZ;
                        } else {
                            vz = vy;
                            vy = 15 - previousZ;
                        }
                    }
                    int bx = Math.floorDiv(vx, 16), by = Math.floorDiv(vy, 16), bz = Math.floorDiv(vz, 16);
                    String key = bx + "," + by + "," + bz;
                    TreeSet<Integer> occupied = blocks.computeIfAbsent(key, ignored -> new TreeSet<>());
                    positions.putIfAbsent(key, new int[] { bx, by, bz });
                    if (occupied.add(
                        (Math.floorMod(vy, 16) << 8) | (Math.floorMod(vz, 16) << 4) | Math.floorMod(vx, 16))) voxels++;
                    if (voxels > MAX_VOXELS || blocks.size() > MAX_BLOCKS) throw new IOException("文字瓷砖或方块数量超过上限");
                }
        }
        if (blocks.isEmpty()) throw new IOException("文字没有可见像素");
        List<Cell> result = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> entry : blocks.entrySet()) {
            NBTTagCompound tile = new NBTTagCompound();
            tile.setString("id", "LittleTilesTileEntity");
            int count = 0;
            java.util.Iterator<Integer> iterator = entry.getValue()
                .iterator();
            Integer pending = iterator.hasNext() ? iterator.next() : null;
            while (pending != null) {
                int first = pending, last = first;
                pending = null;
                while (iterator.hasNext()) {
                    int next = iterator.next();
                    if (next == last + 1 && (next >>> 4) == (first >>> 4)) last = next;
                    else {
                        pending = next;
                        break;
                    }
                }
                NBTTagCompound box = new NBTTagCompound();
                box.setString("tID", color == 0xffffff ? "BlockTileBlock" : "BlockTileColored");
                box.setString("block", material);
                box.setInteger("meta", metadata);
                box.setInteger("color", color);
                box.setInteger("bSize", 1);
                int minX = first & 15, minY = first >>> 8, minZ = (first >>> 4) & 15;
                box.setInteger("cVecx", minX);
                box.setInteger("cVecy", minY);
                box.setInteger("cVecz", minZ);
                box.setInteger("bBox0minX", minX);
                box.setInteger("bBox0minY", minY);
                box.setInteger("bBox0minZ", minZ);
                box.setInteger("bBox0maxX", (last & 15) + 1);
                box.setInteger("bBox0maxY", minY + 1);
                box.setInteger("bBox0maxZ", minZ + 1);
                tile.setTag("t" + count++, box);
            }
            tile.setInteger("tilesCount", count);
            int[] pos = positions.get(entry.getKey());
            result.add(new Cell(pos[0] + x, pos[1] + y, pos[2] + z, tile));
        }
        return result;
    }

    private static double[] rotate(double x, double y, double z, double yaw, double pitch, double roll) {
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch), c = Math.toRadians(roll);
        double xx = x * Math.cos(a) - z * Math.sin(a), zz = x * Math.sin(a) + z * Math.cos(a);
        double yy = y * Math.cos(b) - zz * Math.sin(b);
        zz = y * Math.sin(b) + zz * Math.cos(b);
        return new double[] { xx * Math.cos(c) - yy * Math.sin(c), xx * Math.sin(c) + yy * Math.cos(c), zz };
    }
}
