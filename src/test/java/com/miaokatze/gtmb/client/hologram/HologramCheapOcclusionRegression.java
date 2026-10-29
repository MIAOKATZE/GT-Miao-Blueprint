package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Cheap draw-mask boundaries using real vanilla registry blocks; no native/GL/Chisel model equivalence claim. */
public final class HologramCheapOcclusionRegression {

    private HologramCheapOcclusionRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramCheapOcclusionRegression.class, args)) return;
        run();
        System.out.println("HologramCheapOcclusionRegression PASS");
    }

    public static void run() throws Exception {
        shapeAndPayloadBoundary();
        effectiveSideBoundary();
        masksRetainSourceAndPickCells();
        SimpleAssert.eq(true, HologramLightPreview.hideInteriorFace(true, 0, 0, 0), "hidden static cheap face");
        SimpleAssert.eq(false, HologramLightPreview.hideInteriorFace(false, 0, 0, 0), "exposed face remains drawn");
        for (int axis = 0; axis < 3; axis++) for (int delta : new int[] { -1, 1 }) {
            int[] movement = new int[3];
            movement[axis] = delta;
            SimpleAssert.eq(
                false,
                HologramLightPreview.hideInteriorFace(true, movement[0], movement[1], movement[2]),
                "any active movement restores every cached cheap face");
        }
    }

    private static void shapeAndPayloadBoundary() throws Exception {
        Method register = HologramAuthorStaticFactsRegression.class
            .getDeclaredMethod("registered", String.class, Block.class, boolean.class);
        register.setAccessible(true);
        Block unknown = (Block) register.invoke(null, "cheap_unknown_callback", new net.minecraft.block.BlockStone() {

            @Override
            public boolean hasTileEntity(int metadata) {
                throw new AssertionError("cheap pass called an unknown subclass TE callback");
            }
        }, false);
        eligible(row(0, 0, 0, unknown), false, "unknown subclass is rejected before metadata/TE/shape callbacks");
        NBTTagCompound row = row(0, 0, 0, Blocks.stone);
        eligible(row, true, "exact vanilla stone");
        eligible(row(0, 0, 0, Blocks.glass), false, "transparent blocks cannot occlude cheap neighbors");
        eligible(row(0, 0, 0, Blocks.stone_slab), false, "partial blocks cannot inherit cube proof");
        eligible(row(0, 0, 0, Blocks.chest), false, "tile hosts cannot inherit cube proof");
        eligible(row(0, 0, 0, Blocks.quartz_block), false, "unreviewed exact class stays conservative even if opaque");
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", -1);
        display.setInteger("facing", 2);
        row.setTag("targetDisplay", display);
        eligible(row, true, "cold descriptor metadata is not an opaque payload");
        display.setTag("nativeTile", new NBTTagCompound());
        eligible(row, false, "even empty encoded native compound is outside cheap proof");
        display.removeTag("nativeTile");
        display.setTag("gt.covers", new NBTTagList());
        eligible(row, false, "cover appearance is outside cheap proof");
        row.removeTag("targetDisplay");
        row.setInteger("nativeModelPending", 2);
        eligible(row, false, "pending target remains unknown");
        row.setInteger("nativeModelPending", 0);
        row.setString("renderKind", "localBudget");
        eligible(row, false, "budget shell remains unknown");
        row.setString("renderKind", "localFallback");
        eligible(row, false, "failed appearance remains outside cheap proof");
        row.setString("renderKind", "");
        row.setBoolean("iconOnly", true);
        eligible(row, false, "placeholder is not a cube proof");
        row.setBoolean("iconOnly", false);
        ItemStack stack = new ItemStack(Blocks.stone);
        stack.setTagCompound(new NBTTagCompound());
        row.setTag("targetStack", stack.writeToNBT(new NBTTagCompound()));
        eligible(row, false, "even empty item compound stays outside cheap proof");
        HologramNativeRenderRegistry.Adapter external = new HologramNativeRenderRegistry.Adapter() {

            public boolean supports(Block block) {
                return block == Blocks.stone;
            }
        };
        HologramNativeRenderRegistry.register(external);
        try {
            eligible(row(0, 0, 0, Blocks.stone), false, "external adapter overrides static vanilla qualification");
        } finally {
            HologramNativeRenderRegistry.unregister(external);
        }
        eligible(row(0, 0, 0, Blocks.stone), true, "removing external adapter restores narrow qualification");
    }

    private static void effectiveSideBoundary() {
        NBTTagCompound row = row(0, 0, 0, Blocks.glass);
        row.setString("id", "minecraft:stone");
        row.setTag("actualStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        eligible(row, false, "target transparent side does not borrow actual cube facts");
        SimpleAssert.eq(
            true,
            HologramLightPreview.staticOpaqueCube(new HologramState.Cell(0, row), true),
            "actual view uses actual cube facts");
        row.setString("status", "protected");
        eligible(row, true, "protected target view uses actual cube facts");
        row.setInteger("nativeModelPending", 1);
        eligible(row, false, "protected actual pending remains unknown");
        row.setInteger("nativeModelPending", 0);
        row.setString("status", "planned");
        row.setBoolean("anchor", true);
        eligible(row, true, "anchor uses actual cube facts");
    }

    private static void masksRetainSourceAndPickCells() throws Exception {
        NBTTagCompound source = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (int x = 0; x < 3; x++)
            for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) rows.appendTag(row(x, y, z, Blocks.stone));
        source.setTag("cells", rows);
        HologramState state = new HologramState(source);
        Object region = region(state, Integer.MAX_VALUE, 0);
        List<?> visible = list(region, "visible"), cells = list(region, "cells");
        SimpleAssert.eq(27, visible.size(), "all source-visible cells remain accounted");
        SimpleAssert.eq(27, cells.size(), "every cheap pick/outline cell is retained");
        int totalDrawFaces = 0;
        for (Object preview : cells) {
            SimpleAssert.eq(63, field(preview.getClass(), "exposed").getInt(preview), "all pick faces remain cached");
            SimpleAssert.eq(
                false,
                field(preview.getClass(), "detailed").getBoolean(preview),
                "cheap mask is not detailed/native readiness");
            totalDrawFaces += Integer.bitCount(field(preview.getClass(), "cheapFaces").getInt(preview));
        }
        SimpleAssert.eq(54, totalDrawFaces, "solid 3x3x3 cheap hints emit only outer faces");
        SimpleAssert.eq(0, mask(cells.get(13)), "fully surrounded center has draw mask zero, not removed cell");
        List<?> layer = list(region(state, 1, 0), "cells");
        SimpleAssert.eq(9, layer.size(), "layer view keeps only its source-visible plane");
        SimpleAssert.eq(true, (mask(layer.get(4)) & 3) == 3, "hidden layers never occlude the plane top/bottom");
        rows.getCompoundTagAt(22)
            .setString("status", "removed"); // +X neighbor of center
        state = new HologramState(source);
        cells = list(region(state, Integer.MAX_VALUE, 0), "cells");
        SimpleAssert.eq(true, (mask(cells.get(13)) & 32) != 0, "removed neighbor cannot hide a cheap face");
        rows.getCompoundTagAt(22)
            .setString("status", "satisfied");
        cells = list(region(new HologramState(source), Integer.MAX_VALUE, 2), "cells");
        SimpleAssert.eq(true, (mask(cells.get(13)) & 32) != 0, "view-filtered neighbor cannot hide a cheap face");
        rows.getCompoundTagAt(22)
            .setString("status", "planned");
        rows.getCompoundTagAt(22)
            .setInteger("nativeModelPending", 2);
        cells = list(region(new HologramState(source), Integer.MAX_VALUE, 0), "cells");
        SimpleAssert.eq(true, (mask(cells.get(13)) & 32) != 0, "unknown appearance cannot occlude neighbor");
        rows.getCompoundTagAt(13)
            .setString("prompt", "retained prompt");
        cells = list(region(new HologramState(source), Integer.MAX_VALUE, 0), "cells");
        SimpleAssert.eq(63, mask(cells.get(13)), "prompt cell retains all cheap draw faces");
        rows.getCompoundTagAt(13)
            .setString("prompt", "");
        rows.getCompoundTagAt(13)
            .setString("role", "controller");
        cells = list(region(new HologramState(source), Integer.MAX_VALUE, 0), "cells");
        SimpleAssert.eq(63, mask(cells.get(13)), "role-bearing cell retains all cheap draw faces");
    }

    private static Object region(HologramState state, int layer, int view) throws Exception {
        Class<?> type = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object region = constructor.newInstance();
        Method cell = HologramRenderer.class.getDeclaredMethod(
            "previewCell",
            HologramState.class,
            type,
            HologramState.Cell.class,
            int.class,
            int.class,
            boolean.class,
            boolean.class,
            boolean.class);
        cell.setAccessible(true);
        for (HologramState.Cell source : state.cells)
            cell.invoke(null, state, region, source, layer, view, false, false, true);
        return region;
    }

    private static NBTTagCompound row(int x, int y, int z, Block block) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", x);
        row.setInteger("y", y);
        row.setInteger("z", z);
        row.setInteger("dx", x);
        row.setInteger("dy", y);
        row.setInteger("dz", z);
        String id = Block.blockRegistry.getNameForObject(block);
        row.setString("id", id);
        row.setString("wantId", id);
        row.setString("status", "planned");
        row.setTag("actualStack", new ItemStack(block).writeToNBT(new NBTTagCompound()));
        row.setTag("targetStack", new ItemStack(block).writeToNBT(new NBTTagCompound()));
        return row;
    }

    private static void eligible(NBTTagCompound row, boolean expected, String reason) {
        SimpleAssert.eq(expected, HologramLightPreview.staticOpaqueCube(new HologramState.Cell(0, row), false), reason);
    }

    private static int mask(Object preview) throws Exception {
        return field(preview.getClass(), "cheapFaces").getInt(preview);
    }

    private static List<?> list(Object owner, String name) throws Exception {
        return (List<?>) field(owner.getClass(), name).get(owner);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
