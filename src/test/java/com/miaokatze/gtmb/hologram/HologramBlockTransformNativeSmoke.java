package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.Loader;

/** Call after FML mod initialization. Every entry returned represents an actual native API probe, never a fake tile. */
final class HologramBlockTransformNativeSmoke {

    private HologramBlockTransformNativeSmoke() {}

    static List<String> run() throws Exception {
        List<String> checked = new ArrayList<>();
        if (Loader.isModLoaded("ArchitectureCraft")) {
            architecture();
            checked.add("ArchitectureCraft native Matrix3 24 orientations");
        }
        if (Loader.isModLoaded("ForgeMultipart")) {
            multipart();
            checked.add("ForgeMultipart native microblock bounds, both rotation axes");
        }
        if (Loader.isModLoaded("EnderIO")) {
            enderIo();
            checked.add("EnderIO native ItemConduit serialization");
        }
        if (Loader.isModLoaded("CarpentersBlocks")) {
            carpenters();
            checked.add("CarpentersBlocks native TEBase attribute/slab serialization");
        }
        if (Loader.isModLoaded("littletiles")) {
            littleTiles();
            checked.add("LittleTiles native box and cutout basis serialization");
        }
        return checked;
    }

    private static void architecture() throws Exception {
        Class<?> matrix = Class.forName("gcewing.architecture.compat.Matrix3");
        Object[][] nativeMatrices = (Object[][]) matrix.getField("sideTurnRotations")
            .get(null);
        for (int side = 0; side < 6; side++) for (int turn = 0; turn < 4; turn++) {
            double[][] m = (double[][]) matrix.getField("m")
                .get(nativeMatrices[side][turn]);
            int[] basis = HologramBlockTransformAdapters.architectureBasis(side, turn);
            for (int column = 0; column < 3; column++) {
                int nativeDirection = HologramBlockTransformAdapters.direction(
                    (int) Math.round(m[0][column]),
                    (int) Math.round(m[1][column]),
                    (int) Math.round(m[2][column]));
                SimpleAssert.eq(nativeDirection, basis[column], "native Architecture matrix column");
            }
        }
    }

    private static void multipart() throws Exception {
        String[] names = { "FaceMicroClass", "HollowMicroClass", "EdgeMicroClass", "CornerMicroClass",
            "PostMicroClass" };
        String[] ids = { "mcr_face", "mcr_hllw", "mcr_edge", "mcr_cnr", "mcr_post" };
        int[] counts = { 6, 6, 12, 8, 3 };
        for (int type = 0; type < names.length; type++) {
            Class<?> module = Class.forName("codechicken.microblock." + names[type] + "$");
            Object instance = module.getField("MODULE$")
                .get(null);
            Object[] bounds = (Object[]) module.getMethod(type == 1 ? "occBounds" : "aBounds")
                .invoke(instance);
            for (boolean vertical : new boolean[] { false, true }) for (int slot = 0; slot < counts[type]; slot++) {
                int shape = 64 | slot;
                Object oldBox = bounds[shape];
                NBTTagCompound tag = new NBTTagCompound(), part = new NBTTagCompound();
                NBTTagList parts = new NBTTagList();
                part.setString("id", ids[type]);
                part.setByte("shape", (byte) shape);
                parts.appendTag(part);
                tag.setTag("parts", parts);
                HologramBlockTransformAdapters.multipart(tag, vertical);
                Object newBox = bounds[part.getByte("shape") & 255];
                double[] a = vector(
                    oldBox.getClass()
                        .getField("min")
                        .get(oldBox));
                double[] b = vector(
                    oldBox.getClass()
                        .getField("max")
                        .get(oldBox));
                a = point(a, vertical);
                b = point(b, vertical);
                double[] min = vector(
                    newBox.getClass()
                        .getField("min")
                        .get(newBox));
                double[] max = vector(
                    newBox.getClass()
                        .getField("max")
                        .get(newBox));
                for (int axis = 0; axis < 3; axis++) {
                    SimpleAssert
                        .that(Math.abs(Math.min(a[axis], b[axis]) - min[axis]) < 1e-8, "native FMP rotated min");
                    SimpleAssert
                        .that(Math.abs(Math.max(a[axis], b[axis]) - max[axis]) < 1e-8, "native FMP rotated max");
                }
            }
        }
    }

    private static double[] vector(Object vector) throws Exception {
        Class<?> type = vector.getClass();
        return new double[] { type.getField("x")
            .getDouble(vector),
            type.getField("y")
                .getDouble(vector),
            type.getField("z")
                .getDouble(vector) };
    }

    private static double[] point(double[] p, boolean vertical) {
        return vertical ? new double[] { p[0], 1 - p[2], p[1] } : new double[] { 1 - p[2], p[1], p[0] };
    }

    private static void enderIo() throws Exception {
        Class<?> conduit = Class.forName("crazypants.enderio.conduit.item.ItemConduit");
        Object nativeConduit = conduit.getConstructor()
            .newInstance();
        Method load = conduit.getMethod("readFromNBT", NBTTagCompound.class, short.class);
        Method save = conduit.getMethod("writeToNBT", NBTTagCompound.class);
        NBTTagCompound body = new NBTTagCompound();
        body.setIntArray("connections", new int[] { 2 });
        body.setIntArray("externalConnections", new int[] { 5 });
        body.setInteger("priority.EAST", 73);
        load.invoke(nativeConduit, body, (short) 1);
        body = new NBTTagCompound();
        save.invoke(nativeConduit, body);
        NBTTagCompound tag = new NBTTagCompound(), root = new NBTTagCompound();
        NBTTagList conduits = new NBTTagList();
        root.setString("conduitType", conduit.getName());
        root.setTag("conduit", body);
        conduits.appendTag(root);
        tag.setTag("conduits", conduits);
        HologramBlockTransformAdapters.enderIo(tag, false);
        Object reloaded = conduit.getConstructor()
            .newInstance();
        load.invoke(reloaded, body, (short) 1);
        NBTTagCompound written = new NBTTagCompound();
        save.invoke(reloaded, written);
        SimpleAssert.eq(5, written.getIntArray("connections")[0], "native EIO connection reload");
        SimpleAssert.eq(73, written.getInteger("priority.SOUTH"), "native EIO side setting reload");
    }

    private static void carpenters() throws Exception {
        Class<?> tileClass = Class.forName("com.carpentersblocks.tileentity.TEBase");
        Object tile = tileClass.getConstructor()
            .newInstance();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("cbMetadata", 5);
        NBTTagCompound cover = new NBTTagCompound();
        new ItemStack(Blocks.stone).writeToNBT(cover);
        cover.setByte("cbAttribute", (byte) 2);
        NBTTagList attributes = new NBTTagList();
        attributes.appendTag(cover);
        tag.setTag("cbAttrList", attributes);
        tileClass.getMethod("readFromNBT", NBTTagCompound.class)
            .invoke(tile, tag);
        NBTTagCompound saved = new NBTTagCompound();
        tileClass.getMethod("writeToNBT", NBTTagCompound.class)
            .invoke(tile, saved);
        Class<?> blocks = Class.forName("com.carpentersblocks.util.registry.BlockRegistry");
        Block block = (Block) blocks.getField("blockCarpentersBlock")
            .get(null);
        HologramBlockTransforms.Data data = new HologramBlockTransforms.Data(block, "", 0, saved, null);
        HologramBlockTransformCarpenters.turn(data, false);
        SimpleAssert.eq(2, data.tile.getInteger("cbMetadata"), "native Carpenter north slab becomes east");
        Object reloaded = tileClass.getConstructor()
            .newInstance();
        tileClass.getMethod("readFromNBT", NBTTagCompound.class)
            .invoke(reloaded, data.tile);
        ItemStack movedCover = (ItemStack) tileClass.getMethod("getAttribute", byte.class)
            .invoke(reloaded, (byte) 5);
        SimpleAssert.that(
            movedCover != null && movedCover.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone),
            "native Carpenter covered east slab reloaded");
    }

    private static void littleTiles() throws Exception {
        Class<?> tile = Class.forName("com.creativemd.littletiles.common.utils.small.LittleTileBox");
        NBTTagCompound tag = new NBTTagCompound();
        Object box = tile.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
            .newInstance(1, 2, 3, 5, 8, 12);
        tile.getMethod("writeToNBT", String.class, NBTTagCompound.class)
            .invoke(box, "bBox0", tag);
        NBTTagCompound child = (NBTTagCompound) tag.copy(), root = new NBTTagCompound();
        child.setInteger("bSize", 1);
        child.setByte("cutoutType", (byte) 1);
        child.setByte("cutoutOrientation", (byte) 0);
        child.setInteger("cutoutSizeX", 4);
        child.setInteger("cutoutSizeY", 6);
        child.setInteger("cutoutSizeZ", 9);
        root.setInteger("tilesCount", 1);
        root.setTag("t0", child);
        HologramBlockTransforms.Data transformed = new HologramBlockTransforms.Data(null, "", 0, root, null);
        HologramBlockTransformLittleTiles.turn(transformed, true);
        NBTTagCompound rotated = transformed.tile.getCompoundTag("t0");
        Object reloaded = tile.getConstructor(String.class, NBTTagCompound.class)
            .newInstance("bBox0", rotated);
        SimpleAssert.eq(
            4,
            tile.getField("minY")
                .getInt(reloaded),
            "native LT rotated box reload");
        Class<?> mapper = Class.forName("com.creativemd.littletiles.client.util3d.OrientationMapper");
        Object matrix = mapper.getMethod("fromId", int.class)
            .invoke(null, (int) rotated.getByte("cutoutOrientation"));
        SimpleAssert.eq(
            1.0F,
            matrix.getClass()
                .getField("m12")
                .getFloat(matrix),
            "native LT orientation Y becomes Z");
    }
}
