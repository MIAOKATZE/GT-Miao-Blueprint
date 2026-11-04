package com.miaokatze.gtmb.client.compat.floodlights;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.client.compat.NativeModelTexture;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real 1.5.6 native models/assets and detached visual hydration; no TESR, GL draw or tick. */
public final class FloodLightsModelRegression {

    private FloodLightsModelRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(FloodLightsModelRegression.class, args)) return;
        java.lang.reflect.Constructor<?> constructor = FloodLightsClientCompatibility.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        FloodLightsClientCompatibility module = (FloodLightsClientCompatibility) constructor.newInstance();
        NativeModelTexture[][] textures = (NativeModelTexture[][]) field(module, "textures");
        for (NativeModelTexture[] pair : textures) for (NativeModelTexture texture : pair) {
            texture.load(resources(), new ResourceLocation("gtmb", "unused"));
            texture.initSprite(256, 256, 64, 96, false);
            SimpleAssert.that(texture.getFrameTextureData(0)[0].length > 0, "actual native image loaded");
            SimpleAssert.that(texture.v(1) < texture.getMaxV(), "rectangular UVs exclude padded rows");
        }
        for (NativeModelTexture[] pair : textures) SimpleAssert.that(
            !java.util.Arrays.equals(pair[0].getFrameTextureData(0)[0], pair[1].getFrameTextureData(0)[0]),
            "on/off use distinct actual native texture pixels");
        ModelRenderer small = (ModelRenderer) field(module, "small"), square = (ModelRenderer) field(module, "square");
        int cases = 0;
        for (int type = 0; type < 3; type++) for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
            for (boolean rotation : new boolean[] { false, true }) for (int active = 0; active < 2; active++) {
                ModelRenderer model = type == 1 ? small : square;
                Tessellator tess = Tessellator.instance;
                tess.startDrawingQuads();
                try {
                    module.emit(model, textures[type][active], direction, type == 0, rotation, 16, 0, 0, 0);
                    SimpleAssert.eq(24, field(tess, "vertexCount"), "real native model emits six faces");
                    int[] raw = (int[]) field(tess, "rawBuffer");
                    double[] min = { 9, 9, 9 }, max = { -9, -9, -9 };
                    for (int v = 0; v < 24; v++) {
                        for (int axis = 0; axis < 3; axis++) {
                            double value = Float.intBitsToFloat(raw[v * 8 + axis]);
                            min[axis] = Math.min(min[axis], value);
                            max[axis] = Math.max(max[axis], value);
                        }
                        double u = Float.intBitsToFloat(raw[v * 8 + 3]), w = Float.intBitsToFloat(raw[v * 8 + 4]);
                        SimpleAssert.that(
                            u >= textures[type][active].u(0) - 1e-6 && u <= textures[type][active].u(1) + 1e-6,
                            "native atlas U bounds");
                        SimpleAssert.that(
                            w >= textures[type][active].v(0) - 1e-6 && w <= textures[type][active].v(1) + 1e-6,
                            "native atlas V excludes padding");
                    }
                    // The native boxes are 16x3x16 or 16x3x6: rotations preserve their volume/extents product.
                    double volume = (max[0] - min[0]) * (max[1] - min[1]) * (max[2] - min[2]);
                    near(type == 1 ? 18.0 / 256 : 3.0 / 16, volume, "actual native box volume");
                    near(0, model.rotateAngleX, "model rotation X restored");
                    near(0, model.rotateAngleY, "model rotation Y restored");
                    near(0, model.rotateAngleZ, "model rotation Z restored");
                    cases++;
                } finally {
                    drawing(false);
                }
            }
        Tessellator colored = Tessellator.instance;
        colored.startDrawingQuads();
        try {
            module.emit(small, textures[1][1], ForgeDirection.UP, false, false, 7, 0, 0, 0);
            int expectedColor = 0xff000000;
            Class<?> palette = Class.forName("de.keridos.floodlights.util.RenderUtil");
            for (int channel = 0; channel < 3; channel++) {
                float value = ((float[]) palette.getField(new String[] { "r", "g", "b" }[channel])
                    .get(null))[7];
                expectedColor |= (int) (Math.min(1, value * 1.21342f) * 255) << (channel * 8);
            }
            SimpleAssert.eq(
                expectedColor,
                ((int[]) field(colored, "rawBuffer"))[5],
                "native TESR palette gain/clamping reaches vertex color");
        } finally {
            drawing(false);
        }
        TileEntity tile = (TileEntity) Class.forName("de.keridos.floodlights.tileentity.TileEntitySmallFloodlight")
            .getConstructor()
            .newInstance();
        NBTTagCompound payload = new NBTTagCompound();
        payload.setByte("teDirection", (byte) ForgeDirection.WEST.ordinal());
        payload.setInteger("color", 7);
        payload.setBoolean("wasActive", true);
        payload.setBoolean("teRotationState", true);
        payload.setDouble("storageEU", 99999);
        payload.setString("owner", "unrelated-owner");
        NBTTagCompound original = (NBTTagCompound) payload.copy();
        module.loadTile(tile, payload);
        SimpleAssert.eq(original, payload, "full persistence payload unchanged");
        SimpleAssert.eq(
            ForgeDirection.WEST,
            tile.getClass()
                .getMethod("getOrientation")
                .invoke(tile),
            "visual direction decoded");
        SimpleAssert.eq(
            7,
            tile.getClass()
                .getMethod("getColor")
                .invoke(tile),
            "native color decoded");
        SimpleAssert.eq(
            true,
            tile.getClass()
                .getMethod("getWasActive")
                .invoke(tile),
            "on/off decoded");
        SimpleAssert.eq(
            true,
            tile.getClass()
                .getMethod("getRotationState")
                .invoke(tile),
            "visual rotation decoded");
        SimpleAssert.that(tile.getWorldObj() == null, "visual load needs no world");
        SimpleAssert.eq(0.0, inherited(tile, "storageEU"), "visual decoder ignores energy persistence");
        SimpleAssert.that(!"unrelated-owner".equals(inherited(tile, "owner")), "visual decoder ignores ownership");
        payload.setByte("teDirection", (byte) 6);
        try {
            module.loadTile(tile, payload);
            throw new AssertionError("invalid direction accepted");
        } catch (IllegalArgumentException expected) { /* explicit invalid visual payload */ }
        System.out.println("FloodLights native model/UV/pose passed: " + cases + " cases, 2 models, 6 actual textures");
    }

    private static void near(double expected, double actual, String label) {
        SimpleAssert.that(Math.abs(expected - actual) < 1e-5, label + ": " + actual);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object inherited(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException absent) { /* exact field in an audited native superclass */ }
        }
        throw new NoSuchFieldException(name);
    }

    private static void drawing(boolean value) throws Exception {
        Field field = Tessellator.class.getDeclaredField("isDrawing");
        field.setAccessible(true);
        field.setBoolean(Tessellator.instance, value);
    }

    private static IResourceManager resources() {
        return (IResourceManager) Proxy.newProxyInstance(
            IResourceManager.class.getClassLoader(),
            new Class<?>[] { IResourceManager.class },
            (proxy, method, args) -> {
                if (!method.getName()
                    .equals("getResource")) throw new UnsupportedOperationException(method.getName());
                ResourceLocation location = (ResourceLocation) args[0];
                return Proxy.newProxyInstance(
                    IResource.class.getClassLoader(),
                    new Class<?>[] { IResource.class },
                    (p, m, a) -> {
                        if (!m.getName()
                            .equals("getInputStream")) throw new UnsupportedOperationException(m.getName());
                        java.io.InputStream input = FloodLightsModelRegression.class.getClassLoader()
                            .getResourceAsStream(
                                "assets/" + location.getResourceDomain() + "/" + location.getResourcePath());
                        if (input == null) throw new IOException("Missing actual FloodLights image " + location);
                        return input;
                    });
            });
    }
}
