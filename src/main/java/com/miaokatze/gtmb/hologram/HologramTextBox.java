package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

/** Editable text definition and bounded, deterministic 1/16-block LittleTiles geometry. No world access. */
public final class HologramTextBox {

    public static final String PREFIX = "gtmb_text_";
    public static final int MAX_VOXELS = 64 * 1024 * 1024, MAX_BLOCKS = 16384;
    public static final int MAX_PIXEL_SIZE = 1024, MAX_SAMPLES = 4 * 1024 * 1024;
    public String id = "", text = "猫猫蓝图", font = "minecraft", material = HologramPictureBox.WOOLEN_CLAY;
    public int metadata, color = 0xffffff, width, height, pixelX = 1, pixelY = 1, thickness = 1;
    public int x, y, z;
    public double yaw, pitch, roll, arc, bend, twist, shear;
    public boolean bold, underline, vertical;
    public int layoutVersion = 1;
    public HologramPictureBox picture;
    public byte[] bitmap = new byte[0];
    public int[] turns = new int[0];

    // Opaque extension fields belong to the source, not to the editor widget.
    private NBTTagCompound retainedSource;

    public NBTTagCompound write() {
        NBTTagCompound tag = retainedSource == null ? new NBTTagCompound() : (NBTTagCompound) retainedSource.copy();
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
        tag.setInteger("layoutVersion", layoutVersion);
        if (picture != null) tag.setTag("picture", picture.write());
        else tag.removeTag("picture");
        return tag;
    }

    public static HologramTextBox read(NBTTagCompound tag) throws IOException {
        HologramTextBox result = new HologramTextBox();
        result.retainedSource = (NBTTagCompound) tag.copy();
        for (String key : new String[] { "id", "text", "font", "material", "metadata", "color", "width", "turns",
            "height", "bitmap", "pixelX", "pixelY", "thickness", "x", "y", "z", "yaw", "pitch", "roll", "arc", "bend",
            "twist", "shear", "bold", "underline", "vertical", "layoutVersion", "picture" })
            result.retainedSource.removeTag(key);
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
        result.layoutVersion = tag.getInteger("layoutVersion");
        if (tag.hasKey("picture", 10)) result.picture = HologramPictureBox.read(tag.getCompoundTag("picture"));
        result.validate();
        return result;
    }

    public void validate() throws IOException {
        if (turns.length > 96) throw new IOException("文字旋转历史过长，请重建文本框");
        for (int turn : turns) if (turn != 1 && turn != 2) throw new IOException("文字旋转记录无效");
        if (picture != null) {
            picture.validate();
            if ((!id.isEmpty() && !id.matches(HologramPictureBox.PREFIX + "[a-f0-9-]{36}"))
                || !validPicturePixelSize(pixelX)
                || pixelY != pixelX
                || thickness < 1
                || thickness > 64
                || Math.abs((long) x) > 30000000
                || Math.abs((long) y) > 30000000
                || Math.abs((long) z) > 30000000
                || arc != 0
                || bend != 0
                || twist != 0
                || shear != 0
                || vertical) throw new IOException("图片的像素边长、厚度、姿态或标识无效");
            for (double value : new double[] { yaw, pitch, roll })
                if (!Double.isFinite(value) || Math.abs(value) > 360) throw new IOException("图片旋转参数超出范围");
            return;
        }
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
            || height > (vertical && layoutVersion == 1 ? 1024 : 128)
            || (long) width * height > 131072
            || layoutVersion < 0
            || layoutVersion > 1
            || bitmap.length != (width * height + 7) / 8
            || pixelX < 1
            || pixelX > 16
            || pixelY < 1
            || pixelY > 16
            || thickness < 1
            || thickness > 16
            || Math.abs((long) x) > 30000000
            || Math.abs((long) y) > 30000000
            || Math.abs((long) z) > 30000000) throw new IOException("文字内容、像素大小、厚度、材质或栅格超出范围");
        for (double value : new double[] { yaw, pitch, roll, arc, bend, twist, shear })
            if (!Double.isFinite(value) || Math.abs(value) > 360) throw new IOException("文字形变参数超出范围");
    }

    public boolean pixel(int px, int py) {
        int bit = py * width + px;
        return (bitmap[bit >>> 3] & (1 << (bit & 7))) != 0;
    }

    /** Upright glyph layout; explicit newlines retain a gap in a vertical column. */
    public String[] lines() {
        if (!vertical) return text.split("\n", -1);
        List<String> lines = new ArrayList<>();
        text.codePoints()
            .filter(value -> value != '\r')
            .forEach(value -> lines.add(value == '\n' ? "" : new String(Character.toChars(value))));
        return lines.toArray(new String[0]);
    }

    /** One rigid orientation for all hosts and microvoxels; the recipe stays bounded. */
    public void turn(boolean verticalTurn) {
        HologramPreviewPose pose = new HologramPreviewPose();
        for (int turn : turns) pose = pose.turn(turn == 2);
        turns = pose.turn(verticalTurn)
            .recipe();
        for (int i = 0; i < turns.length; i++) turns[i]++;
    }

    public static final class Cell implements AutoCloseable {

        public final int x, y, z;
        /** Seven integers per cuboid: paint index followed by min/max XYZ. No retained native NBT tree. */
        public final int[] boxes;
        public final String[] materials;
        public final int[] metadata, colors;
        public final boolean fullBlock;
        private String encodedTile;
        private final HologramAttachmentMemory.Retention memory = new HologramAttachmentMemory.Retention();
        private final HologramAttachmentMemory.Retention encodedMemory = new HologramAttachmentMemory.Retention();
        private boolean encodedExported;

        Cell(int x, int y, int z, int[] boxes, String[] materials, int[] metadata, int[] colors, boolean fullBlock) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.boxes = boxes;
            this.materials = materials;
            this.metadata = metadata;
            this.colors = colors;
            this.fullBlock = fullBlock;
        }

        public NBTTagCompound tile() {
            return tile(boxes.length / 7);
        }

        NBTTagCompound sampleTile() {
            return tile(1);
        }

        String encodedTile() throws IOException {
            if (encodedTile == null) {
                long count = boxes.length / 7;
                int materialLength = 0;
                for (String material : materials) materialLength = Math.max(materialLength, material.length());
                long raw = 256L + count * (1024L + 3L * materialLength);
                long compressed = Math.min(HologramBlueprintNbt.MAX_COMPRESSED, raw + raw / 100 + 1024);
                // Both NBT trees, growable gzip/base64 buffers and the final string are admitted before creation.
                try (HologramProcessGovernor.Lease working = HologramAttachmentMemory
                    .reserve("attachment tile encoding", 65536L + 8192L * count + 8L * compressed)) {
                    String encoded = HologramBlueprintNbt.encodeCaptured(tile());
                    HologramProcessGovernor.Lease result = HologramAttachmentMemory
                        .reserve("attachment encoded tile", 512L + 2L * encoded.length());
                    try {
                        HologramAttachmentMemory.retain(encoded, encodedMemory, result);
                        encodedTile = encoded;
                    } catch (IOException | RuntimeException | Error failure) {
                        result.close();
                        throw failure;
                    }
                }
            }
            return encodedTile;
        }

        String exportEncodedTile() throws IOException {
            String encoded = encodedTile();
            HologramAttachmentMemory.export(encoded, encodedMemory);
            encodedExported = true;
            return encoded;
        }

        void retain(HologramProcessGovernor.Lease lease) throws IOException {
            HologramAttachmentMemory.retain(this, memory, lease);
        }

        @Override
        public void close() {
            encodedTile = null;
            memory.close();
            if (!encodedExported) encodedMemory.close();
        }

        private NBTTagCompound tile(int count) {
            NBTTagCompound tile = new NBTTagCompound();
            tile.setString("id", "LittleTilesTileEntity");
            tile.setInteger("tilesCount", count);
            for (int i = 0; i < count; i++) {
                if ((i & 255) == 0 && HologramAttachmentGeometry.cancelled())
                    throw new java.util.concurrent.CancellationException("附加对象生成已取消");
                int at = i * 7, paint = boxes[at];
                NBTTagCompound box = new NBTTagCompound();
                box.setString("tID", colors[paint] == 0xffffff ? "BlockTileBlock" : "BlockTileColored");
                box.setString("block", materials[paint]);
                box.setInteger("meta", metadata[paint]);
                box.setInteger("color", colors[paint]);
                box.setInteger("bSize", 1);
                for (int axis = 0; axis < 3; axis++) {
                    String name = axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
                    box.setInteger("cVec" + name.toLowerCase(java.util.Locale.ROOT), boxes[at + 1 + axis]);
                    box.setInteger("bBox0min" + name, boxes[at + 1 + axis]);
                    box.setInteger("bBox0max" + name, boxes[at + 4 + axis]);
                }
                tile.setTag("t" + i, box);
            }
            return tile;
        }
    }

    public static boolean validPicturePixelSize(int size) {
        return size >= 1 && size <= MAX_PIXEL_SIZE && (size <= 16 || size % 16 == 0);
    }

    boolean axisAligned() {
        return yaw % 90 == 0 && pitch % 90 == 0 && roll % 90 == 0;
    }

    void box(HologramMicroTiles tiles, int x, int y, int z, int width, int height, int depth, String material,
        int metadata, int color) throws IOException {
        int[] a = orient(x, y, z), b = orient(x + width - 1, y + height - 1, z + depth - 1);
        tiles.addBox(
            Math.min(a[0], b[0]),
            Math.min(a[1], b[1]),
            Math.min(a[2], b[2]),
            Math.max(a[0], b[0]) + 1,
            Math.max(a[1], b[1]) + 1,
            Math.max(a[2], b[2]) + 1,
            material,
            metadata,
            color);
    }

    /** Column sampling plus Euler rotation; overlapping samples of the same text are unioned before packing. */
    public List<Cell> generate() throws IOException {
        validate();
        if (picture != null) return picture.generate(this);
        try (HologramMicroTiles tiles = new HologramMicroTiles()) {
            int attempts = 0;
            double span = Math.max(1, width * pixelX - 1), verticalSpan = height * pixelY;
            for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) {
                if (px == 0) HologramAttachmentGeometry.checkpoint("raster", py, height);
                if (!pixel(px, py)) continue;
                if (arc == 0 && bend == 0
                    && twist == 0
                    && shear == 0
                    && !(vertical && layoutVersion == 0)
                    && axisAligned()) {
                    box(
                        tiles,
                        px * pixelX,
                        (height - py - 1) * pixelY,
                        0,
                        pixelX,
                        pixelY,
                        thickness,
                        material,
                        metadata,
                        color);
                    continue;
                }
                for (int sx = 0; sx < pixelX; sx++)
                    for (int sy = 0; sy < pixelY; sy++) for (int sz = 0; sz < thickness; sz++) {
                        if (++attempts > MAX_SAMPLES) throw new IOException("文字采样超过上限，请减小文字、像素大小或厚度");
                        double xx = px * pixelX + sx, yy = (height - py - 1) * pixelY + sy, zz = sz;
                        if (layoutVersion == 1) {
                            // The running direction is the line for horizontal text and the column for vertical text.
                            // Keep its midpoint fixed; equal distances on either side receive opposite angles.
                            double centerX = (width * pixelX - 1) / 2d, centerY = (verticalSpan - 1) / 2d,
                                centerZ = (thickness - 1) / 2d;
                            double runningSpan = vertical ? Math.max(1, verticalSpan - 1) : span;
                            double originalU = vertical ? yy : xx, centerU = vertical ? centerY : centerX;
                            double at = (originalU - centerU) / runningSpan;
                            if (Math.abs(arc) >= .001) {
                                double angle = Math.toRadians(arc), radius = runningSpan / angle;
                                double cross = vertical ? xx - centerX : yy - centerY;
                                double along = Math.sin(at * angle) * (radius + cross) + centerU;
                                double across = Math.cos(at * angle) * (radius + cross) - radius;
                                if (vertical) {
                                    yy = along;
                                    xx = centerX + across;
                                } else {
                                    xx = along;
                                    yy = centerY + across;
                                }
                            }
                            if (Math.abs(bend) >= .001) {
                                double angle = Math.toRadians(bend), radius = runningSpan / angle;
                                double along = Math.sin(at * angle) * (radius + zz - centerZ) + centerU;
                                zz = Math.cos(at * angle) * (radius + zz - centerZ) - radius + centerZ;
                                if (vertical) yy += along - originalU;
                                else xx += along - originalU;
                            }
                            if (twist != 0) {
                                double angle = Math.toRadians(twist) * at,
                                    cross = vertical ? xx - centerX : yy - centerY;
                                double depth = zz - centerZ;
                                double across = Math.cos(angle) * cross - Math.sin(angle) * depth;
                                zz = Math.sin(angle) * cross + Math.cos(angle) * depth + centerZ;
                                if (vertical) xx = across + centerX;
                                else yy = across + centerY;
                            }
                        } else {
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
                        }
                        xx += shear * yy / 100;
                        if (vertical && layoutVersion == 0) {
                            double previous = xx;
                            xx = -yy;
                            yy = previous;
                        }
                        int[] voxel = orient(xx, yy, zz);
                        tiles.add(voxel[0], voxel[1], voxel[2], material, metadata, color);
                    }
            }
            return tiles.finish(x, y, z);
        }
    }

    public static boolean isAttachmentId(String id) {
        return id.startsWith(PREFIX) || id.startsWith(HologramPictureBox.PREFIX);
    }

    public String noun() {
        return picture == null ? "文字" : "图片";
    }

    int[] orient(double x, double y, double z) {
        double[] rotated = rotate(x, y, z, yaw, pitch, roll);
        int vx = (int) Math.round(rotated[0]), vy = (int) Math.round(rotated[1]), vz = (int) Math.round(rotated[2]);
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
        return new int[] { vx, vy, vz };
    }

    /** Continuous voxel boundary transform for the local image proof plane. */
    public double[] corner(double x, double y, double z) {
        double[] point = rotate(x - .5, y - .5, z - .5, yaw, pitch, roll);
        for (int turn : turns) {
            double previousZ = point[2];
            if (turn == 1) {
                point[2] = point[0];
                point[0] = 15 - previousZ;
            } else {
                point[2] = point[1];
                point[1] = 15 - previousZ;
            }
        }
        for (int axis = 0; axis < 3; axis++) point[axis] += .5;
        return point;
    }

    private static double[] rotate(double x, double y, double z, double yaw, double pitch, double roll) {
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch), c = Math.toRadians(roll);
        double xx = x * Math.cos(a) - z * Math.sin(a), zz = x * Math.sin(a) + z * Math.cos(a);
        double yy = y * Math.cos(b) - zz * Math.sin(b);
        zz = y * Math.sin(b) + zz * Math.cos(b);
        return new double[] { xx * Math.cos(c) - yy * Math.sin(c), xx * Math.sin(c) + yy * Math.cos(c), zz };
    }
}
