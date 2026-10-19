package com.miaokatze.gtmb.hologram;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramPictureBoxRegression {

    private HologramPictureBoxRegression() {}

    private static HologramPictureBox picture(int width, int height) {
        HologramPictureBox value = new HologramPictureBox();
        value.filename = "sample.png";
        value.sourceWidth = value.originalWidth = value.width = width;
        value.sourceHeight = value.originalHeight = value.height = height;
        value.source = new int[width * height];
        value.keepAspect = false;
        Arrays.fill(value.source, 0xffdddddd);
        return value;
    }

    public static void run() {
        try {
            SimpleAssert.eq(
                HologramPictureBox.WOOLEN_CLAY,
                new HologramTextBox().material,
                "new text defaults to Chisel woolen clay");
            HologramPictureBox composite = picture(16, 1);
            SimpleAssert
                .eq(HologramPictureBox.DEFAULT_PALETTE, composite.palette, "new picture defaults to composite palette");
            for (int meta = 0; meta < 16; meta++)
                composite.source[meta] = 0xff000000 | HologramPictureBox.woolenClayColor(meta);
            byte[] chosen = composite.rasterize();
            for (int meta = 0; meta < 16; meta++) {
                int index = (chosen[meta] & 255) - 1;
                SimpleAssert.eq(
                    HologramPictureBox.WOOLEN_CLAY,
                    composite.material(index),
                    "matching colors prefer woolen clay");
                SimpleAssert.eq(meta, composite.metadata(index), "registered sixteen-color metadata order is retained");
            }
            composite.source[0] = 0xff000000 | composite.paletteColor(16);
            SimpleAssert.eq(
                "minecraft:stained_hardened_clay",
                composite.material((composite.rasterize()[0] & 255) - 1),
                "terracotta supplements a color outside the woolen clay range");
            SimpleAssert.eq("minecraft:wool", composite.material(32), "wool remains an auxiliary fixed material");
            NBTTagCompound legacyPalette = composite.write();
            legacyPalette.setInteger("palette", 0);
            HologramPictureBox oldPalette = HologramPictureBox.read(legacyPalette);
            SimpleAssert.eq(32, oldPalette.paletteSize(), "old image palette indices keep their original meaning");
            SimpleAssert.eq("minecraft:wool", oldPalette.material(0), "old image wool is never silently changed");
            HologramPictureBox ratio = picture(20, 10);
            ratio.keepAspect = true;
            ratio.resizeShortSide(64);
            SimpleAssert.eq(128, ratio.width, "default resolution follows ratio rather than original width");
            SimpleAssert.eq(64, ratio.height, "default short side is 64 micro-pixels");
            ratio.resizeShortSide(32);
            SimpleAssert.eq(64, ratio.width, "locked dimensions scale together");
            ratio.keepAspect = false;
            ratio.width = 7;
            ratio.height = 9;
            SimpleAssert.eq(63, ratio.rasterize().length, "unlocked width and height are independent");

            HologramPictureBox ring = picture(5, 5);
            for (int y = 1; y < 4; y++) for (int x = 1; x < 4; x++) ring.source[y * 5 + x] = 0xff191616;
            ring.source[12] = 0xffdddddd;
            ring.removeBackground = true;
            byte[] cropped = ring.rasterize();
            SimpleAssert.eq(0, (int) cropped[0], "boundary background is removed before material quantization");
            SimpleAssert
                .that(cropped[12] != 0 && cropped[6] != 0, "enclosed same-color island and foreground ring survive");
            ring.source[0] = 0xffdcdcdc;
            ring.backgroundTolerance = 2;
            SimpleAssert.eq(0, (int) ring.rasterize()[0], "optional tolerance handles a nearby border shade");
            ring.source[6] = 0;
            SimpleAssert.eq(0, (int) ring.rasterize()[6], "native transparency always remains empty");

            HologramPictureBox color = picture(4, 3);
            Arrays.fill(color.source, 0xff963430);
            int[] unchanged = color.source.clone();
            int normal = color.rasterize()[0];
            color.filter = 3;
            SimpleAssert.that(color.rasterize()[0] != normal, "inversion changes material selection");
            for (int palette = 0; palette < HologramPictureBox.PALETTE_NAMES.length; palette++) {
                color.palette = palette;
                for (int filter = 0; filter < HologramPictureBox.FILTER_NAMES.length; filter++) {
                    color.filter = filter;
                    color.dither = true;
                    for (byte pixel : color.rasterize()) {
                        int index = (pixel & 255) - 1;
                        SimpleAssert.that(
                            index >= 0 && index < color.paletteSize(),
                            "all effects stay inside the fixed palette");
                        String material = color.material(index);
                        SimpleAssert.that(
                            palette == HologramPictureBox.DEFAULT_PALETTE
                                ? material.equals(HologramPictureBox.WOOLEN_CLAY)
                                    || material.equals("minecraft:stained_hardened_clay")
                                    || material.equals("minecraft:wool")
                                : palette == 2 ? material.equals("etfuturum:concrete")
                                    : material.equals("minecraft:wool")
                                        || palette == 0 && material.equals("minecraft:stained_hardened_clay"),
                            "image settings cannot introduce a custom material");
                    }
                }
            }
            SimpleAssert
                .that(Arrays.equals(unchanged, color.source), "filters never overwrite embedded source samples");
            HologramPictureBox restored = HologramPictureBox.read(color.write());
            SimpleAssert.that(
                Arrays.equals(color.rasterize(), restored.rasterize()),
                "shared blueprint re-edits without local image files");

            HologramTextBox attachment = new HologramTextBox();
            attachment.id = HologramPictureBox.PREFIX + "11111111-1111-1111-1111-111111111111";
            attachment.picture = picture(16, 16);
            attachment.thickness = 16;
            List<HologramTextBox.Cell> uniform = attachment.generate();
            SimpleAssert.eq(1, uniform.size(), "filled picture occupies one host");
            SimpleAssert
                .that(uniform.get(0).fullBlock, "single-material complete image cube may become ordinary block");
            SimpleAssert.eq(
                1,
                uniform.get(0)
                    .tile()
                    .getInteger("tilesCount"),
                "uniform micro-pixels merge into one cuboid");
            attachment.thickness = 64;
            List<HologramTextBox.Cell> thick = HologramTextBox.read(attachment.write())
                .generate();
            SimpleAssert.eq(4, thick.size(), "four-block image thickness survives save and reload");
            SimpleAssert.eq(16384, voxels(thick).size(), "thickness preserves every material voxel");
            for (HologramTextBox.Cell cell : thick) {
                SimpleAssert.that(cell.fullBlock, "thick uniform image collapses to ordinary full cubes");
                SimpleAssert.eq(
                    1,
                    cell.tile()
                        .getInteger("tilesCount"),
                    "each filled host stores one cuboid");
            }
            attachment.thickness = 65;
            try {
                attachment.validate();
                throw new AssertionError("out-of-range image thickness admitted");
            } catch (java.io.IOException expectedFailure) {}
            attachment.thickness = 16;
            for (int y = 0; y < 16; y++)
                for (int x = 8; x < 16; x++) attachment.picture.source[y * 16 + x] = 0xff963430;
            List<HologramTextBox.Cell> mixed = attachment.generate();
            SimpleAssert.that(!mixed.get(0).fullBlock, "multiple materials cannot collapse to one ordinary block");
            SimpleAssert.eq(
                2,
                mixed.get(0)
                    .tile()
                    .getInteger("tilesCount"),
                "neighboring equal pixels pack without crossing material boundaries");
            Map<String, String> original = voxels(mixed);
            SimpleAssert.eq(4096, original.size(), "mixed material packing preserves exact volume without overlaps");
            attachment.turn(false);
            Map<String, String> expected = new HashMap<>();
            for (Map.Entry<String, String> voxel : original.entrySet()) {
                String[] xyz = voxel.getKey()
                    .split(",");
                expected.put((15 - Integer.parseInt(xyz[2])) + "," + xyz[1] + "," + xyz[0], voxel.getValue());
            }
            SimpleAssert
                .eq(expected, voxels(attachment.generate()), "rigid image rotation preserves every material voxel");
            for (int i = 0; i < 3; i++) attachment.turn(false);
            SimpleAssert.eq(original, voxels(attachment.generate()), "four image turns restore the complete object");
            HologramTextBox copy = HologramTextBox.read(attachment.write());
            SimpleAssert
                .eq(original, voxels(copy.generate()), "attachment metadata survives text-compatible schema transport");
            attachment.arc = 90;
            try {
                attachment.validate();
                throw new AssertionError("image warp admitted");
            } catch (java.io.IOException expectedFailure) {}
            attachment.arc = 0;
            NBTTagCompound malformed = attachment.write();
            malformed.getCompoundTag("picture")
                .setIntArray("source", new int[0]);
            try {
                HologramTextBox.read(malformed);
                throw new AssertionError("truncated source admitted");
            } catch (java.io.IOException expectedFailure) {}
            attachment.picture.width = 1025;
            try {
                attachment.generate();
                throw new AssertionError("oversized image admitted");
            } catch (java.io.IOException expectedFailure) {}
            com.miaokatze.gtmb.client.hologram.HologramPictureFilesRegression.run();
        } catch (Exception failure) {
            throw new AssertionError("picture attachment conversion boundaries", failure);
        }
    }

    private static Map<String, String> voxels(List<HologramTextBox.Cell> cells) {
        Map<String, String> result = new HashMap<>();
        for (HologramTextBox.Cell cell : cells) for (int i = 0; i < cell.tile()
            .getInteger("tilesCount"); i++) {
                NBTTagCompound tile = cell.tile()
                    .getCompoundTag("t" + i);
                String material = tile.getString("block") + "@" + tile.getInteger("meta");
                for (int x = tile.getInteger("bBox0minX"); x < tile.getInteger("bBox0maxX"); x++)
                    for (int y = tile.getInteger("bBox0minY"); y < tile.getInteger("bBox0maxY"); y++)
                        for (int z = tile.getInteger("bBox0minZ"); z < tile.getInteger("bBox0maxZ"); z++) {
                            String key = (cell.x * 16 + x) + "," + (cell.y * 16 + y) + "," + (cell.z * 16 + z);
                            SimpleAssert.that(result.put(key, material) == null, "no overlapping material micro-tiles");
                        }
            }
        return result;
    }
}
