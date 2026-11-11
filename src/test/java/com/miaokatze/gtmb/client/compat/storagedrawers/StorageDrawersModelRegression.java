// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.compat.storagedrawers;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;

import com.jaquadro.minecraft.storagedrawers.block.BlockFramingTable;
import com.jaquadro.minecraft.storagedrawers.client.renderer.FramingTableRenderer;
import com.jaquadro.minecraft.storagedrawers.util.RenderHelper;
import com.miaokatze.gtmb.api.ClientRendering;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual 2.2.31 native ISBRH, metadata and atlas UVs; no display, tile inventory or world lifecycle. */
public final class StorageDrawersModelRegression {

    private StorageDrawersModelRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(StorageDrawersModelRegression.class, args)) return;
        Constructor<StorageDrawersClientCompatibility> constructor = StorageDrawersClientCompatibility.class
            .getDeclaredConstructor();
        constructor.setAccessible(true);
        StorageDrawersClientCompatibility module = constructor.newInstance();
        BlockFramingTable block = new BlockFramingTable("gtmb_test_framing_table");
        FramingTableRenderer nativeRenderer = new FramingTableRenderer();
        SimpleAssert.that(module.supports(block), "the framing table has explicit native admission");
        SimpleAssert.that(!module.supports(Blocks.crafting_table), "ordinary workbenches are outside this adapter");
        SimpleAssert.that(!module.requiresTile(block, 2), "the table body does not require tile inventory");
        SimpleAssert.that(module.createTile(block, 2, null) == null, "no native tile or NBT reader is created");
        SimpleAssert
            .that(module.renderer(block, nativeRenderer) == nativeRenderer, "original registered ISBRH retained");
        Method supported = Class.forName("com.miaokatze.gtmb.client.hologram.HologramNativeTiles")
            .getDeclaredMethod("supported", net.minecraft.block.Block.class);
        supported.setAccessible(true);
        SimpleAssert.eq(false, supported.invoke(null, block), "unregistered table misses native capture");
        GTMBExtensions.Registration handle = ClientRendering.register("gtmb:test/framing-table", module);
        try {
            SimpleAssert.eq(true, supported.invoke(null, block), "public registration enables native capture");
        } finally {
            handle.close();
        }
        SimpleAssert.eq(false, supported.invoke(null, block), "registration cleanup restores admission");

        Map<String, IIcon> icons = new LinkedHashMap<>();
        block.registerBlockIcons(name -> {
            TextureAtlasSprite icon = new TextureAtlasSprite(name) {};
            icon.setIconWidth(16);
            icon.setIconHeight(16);
            icon.initSprite(256, 256, icons.size() * 32, 32, false);
            icons.put(name, icon);
            return icon;
        });
        SimpleAssert.eq(4, icons.size(), "four original native atlas materials");
        for (String name : icons.keySet()) {
            ResourceLocation location = new ResourceLocation(name);
            String path = "assets/" + location.getResourceDomain()
                + "/textures/blocks/"
                + location.getResourcePath()
                + ".png";
            try (InputStream input = StorageDrawersModelRegression.class.getClassLoader()
                .getResourceAsStream(path)) {
                SimpleAssert.that(input != null && ImageIO.read(input) != null, "native atlas image exists: " + path);
            }
        }
        SimpleAssert.that(block.getIcon(0, 2) == null, "ordinary block icon is absent despite valid native materials");

        Field singleton = field(Minecraft.class, "theMinecraft");
        Field unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft previous = (Minecraft) singleton.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        minecraft.gameSettings = (GameSettings) unsafe.allocateInstance(GameSettings.class);
        singleton.set(null, minecraft);
        Field worldPass = field(ForgeHooksClient.class, "worldRenderPass");
        int previousPass = worldPass.getInt(null);
        try {
            for (int direction = 2; direction <= 5; direction++) for (int half = 0; half < 2; half++) {
                int meta = direction | half * 8;
                IBlockAccess access = access(block, meta);
                for (int pass = 0; pass <= 1; pass++) {
                    worldPass.setInt(null, pass);
                    Tessellator tess = Tessellator.instance;
                    tess.startDrawingQuads();
                    try {
                        SimpleAssert.that(
                            module.renderer(block, nativeRenderer)
                                .renderWorldBlock(access, 0, 0, 0, block, 0, null),
                            "native renderer accepts each table half");
                        int count = field(Tessellator.class, "vertexCount").getInt(tess);
                        SimpleAssert.eq(pass == 0 ? 164 : 4, count, "native table body/overlay face count");
                        int[] raw = (int[]) field(Tessellator.class, "rawBuffer").get(tess);
                        boolean right = half == 0;
                        if (direction == 2 || direction == 3) right = !right;
                        int baseFaces = 0, trimFaces = 0;
                        for (int vertex = 0; vertex < count; vertex += 4) {
                            double u = 0, v = 0;
                            for (int corner = 0; corner < 4; corner++) {
                                int offset = (vertex + corner) * 8;
                                for (int axis = 0; axis < 3; axis++) {
                                    float coordinate = Float.intBitsToFloat(raw[offset + axis]);
                                    SimpleAssert.that(coordinate >= 0 && coordinate <= 1, "native half bounds");
                                    if (pass == 1 && axis == 1)
                                        SimpleAssert.eq(1f, coordinate, "overlay stays on the native tabletop");
                                }
                                u += Float.intBitsToFloat(raw[offset + 3]) / 4.0;
                                v += Float.intBitsToFloat(raw[offset + 4]) / 4.0;
                            }
                            if (pass == 1) SimpleAssert.that(
                                inside(block.getIconOverlay(!right), u, v),
                                "metadata selects the original left/right shadow material");
                            else if (inside(block.getIconBase(), u, v)) baseFaces++;
                            else if (inside(block.getIconTrim(), u, v)) trimFaces++;
                            else throw new AssertionError("native face left the original atlas materials");
                        }
                        if (pass == 0) {
                            SimpleAssert.eq(24, baseFaces, "original tabletop, feet, legs and braces");
                            SimpleAssert.eq(17, trimFaces, "original tabletop trim");
                        }
                        SimpleAssert
                            .eq(0, RenderHelper.instances.get().state.rotateTransform, "native orientation cleared");
                        for (int rotation : RenderHelper.instances.get().state.uvRotate)
                            SimpleAssert.eq(0, rotation, "native UV rotation cleared");
                    } finally {
                        // CPU capture never calls Tessellator.draw or any GL entry point.
                        field(Tessellator.class, "isDrawing").setBoolean(tess, false);
                    }
                }
            }
        } finally {
            worldPass.setInt(null, previousPass);
            singleton.set(null, previous);
        }
        System.out
            .println("StorageDrawers native framing table passed: 8 metadata states, body/overlay atlas geometry");
    }

    private static boolean inside(IIcon icon, double u, double v) {
        return u > icon.getMinU() && u < icon.getMaxU() && v > icon.getMinV() && v < icon.getMaxV();
    }

    private static IBlockAccess access(BlockFramingTable block, int metadata) {
        return (IBlockAccess) Proxy.newProxyInstance(
            IBlockAccess.class.getClassLoader(),
            new Class<?>[] { IBlockAccess.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getBlockMetadata":
                        return metadata;
                    case "getBlock":
                        return block;
                    case "getLightBrightnessForSkyBlocks":
                        return 0xf000f0;
                    case "getTileEntity":
                        throw new AssertionError("static table body must not read tile inventory");
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
