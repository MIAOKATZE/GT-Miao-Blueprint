// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.compat.botania;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import com.miaokatze.gtmb.client.compat.NativeModelTexture;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.client.preview.ModelRendererMesh;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModMetadata;

/** Actual Botania model and image, without a GL context or any native tile/TESR lifecycle. */
public final class BotaniaModelRegression {

    private BotaniaModelRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(BotaniaModelRegression.class, args)) return;
        Constructor<?> constructor = BotaniaClientCompatibility.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BotaniaClientCompatibility module = (BotaniaClientCompatibility) constructor.newInstance();
        List<?> parts = (List<?>) field(module, "parts");
        SimpleAssert.eq(9, parts.size(), "all native claw parts present");
        int[] count = { 0 };
        for (Object part : parts) {
            Field compiled = ModelRenderer.class.getDeclaredField("compiled");
            compiled.setAccessible(true);
            ModelRendererMesh.emit((ModelRenderer) part, 1f / 16, (x, y, z, u, v, nx, ny, nz) -> {
                SimpleAssert.that(u >= 0 && u <= 1 && v >= 0 && v <= 1, "original model UVs stay in source image");
                count[0]++;
            });
            SimpleAssert.that(!compiled.getBoolean(part), "CPU extraction never compiles a native display list");
        }
        SimpleAssert.eq(216, count[0], "nine actual six-face boxes");
        Object incomplete = Class.forName("vazkii.botania.client.model.ModelSpawnerClaw")
            .getConstructor()
            .newInstance();
        Field claw = incomplete.getClass()
            .getDeclaredField("Claw8");
        claw.setAccessible(true);
        claw.set(incomplete, null);
        java.lang.reflect.Method readPart = BotaniaClientCompatibility.class
            .getDeclaredMethod("readPart", Object.class, String.class);
        readPart.setAccessible(true);
        try {
            readPart.invoke(null, incomplete, "Claw8");
            throw new AssertionError("incomplete native model must fail explicitly");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            SimpleAssert.that(
                expected.getCause() instanceof NullPointerException && expected.getCause()
                    .getMessage()
                    .contains("Claw8"),
                "missing component has a specific failure");
        }
        NativeModelTexture texture = (NativeModelTexture) field(module, "texture");
        texture.load(resources(false), new ResourceLocation("gtmb", "unused"));
        SimpleAssert.eq(64, texture.getIconWidth(), "native 64x32 image is padded to 64x64");
        SimpleAssert.eq(64, texture.getIconHeight(), "square block atlas entry");
        try (InputStream stream = asset()) {
            BufferedImage original = ImageIO.read(stream);
            int[] padded = texture.getFrameTextureData(0)[0];
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                if (y >= 32) SimpleAssert.eq(0, padded[y * 64 + x], "padding remains transparent");
                else if ((original.getRGB(x, y) >>> 24) == 255) SimpleAssert
                    .eq(original.getRGB(x, y), padded[y * 64 + x], "native opaque texture pixels preserved");
            }
        }
        texture.initSprite(256, 256, 64, 96, false);
        near(texture.getInterpolatedU(16), texture.u(1), "horizontal native UV extent");
        near(texture.getInterpolatedV(8), texture.v(1), "rectangular native UV ends above padding");
        // Capture the production renderer's actual vertices; no Tessellator.draw/GL calls occur.
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        try {
            net.minecraft.block.Block block = nativeBlock();
            module.renderWorldBlock(null, 19, 27, 35, block, 0, null);
            SimpleAssert.eq(216, field(tess, "vertexCount"), "production renderer emits every native part");
            int[] raw = (int[]) field(tess, "rawBuffer");
            double[] min = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
            double[] max = { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
            for (int i = 0; i < 216; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    float value = Float.intBitsToFloat(raw[i * 8 + axis]);
                    min[axis] = Math.min(min[axis], value);
                    max[axis] = Math.max(max[axis], value);
                }
                double u = Float.intBitsToFloat(raw[i * 8 + 3]), v = Float.intBitsToFloat(raw[i * 8 + 4]);
                SimpleAssert.that(u >= texture.u(0) - 1e-6 && u <= texture.u(1) + 1e-6, "atlas U bounds");
                SimpleAssert
                    .that(v >= texture.v(0) - 1e-6 && v <= texture.v(1) + 1e-6, "atlas V excludes transparent padding");
            }
            near(18.9375, min[0], "native claw left extent");
            near(20.0625, max[0], "native claw right extent");
            near(26.6875, min[1], "native lower claws retain their real depth");
            near(27.0625, max[1], "native plate top");
            near(34.9375, min[2], "native claw back extent");
            near(36.0625, max[2], "native claw front extent");
        } finally {
            Field drawing = Tessellator.class.getDeclaredField("isDrawing");
            drawing.setAccessible(true);
            drawing.setBoolean(tess, false);
        }
        try {
            texture.load(resources(true), new ResourceLocation("gtmb", "unused"));
            throw new AssertionError("missing native texture must fail explicitly");
        } catch (IllegalStateException expected) {
            SimpleAssert.that(expected.getCause() instanceof IOException, "native resource failure is retained");
        }
        System.out.println("Botania real claw model passed: 9 parts, 216 vertices, native pose/texture/atlas UVs");
    }

    private static void near(double expected, double actual, String label) {
        SimpleAssert.that(Math.abs(expected - actual) < 1e-6, label + ": " + actual);
    }

    private static net.minecraft.block.Block nativeBlock() throws Exception {
        Loader loader = Loader.instance();
        Field controllerField = Loader.class.getDeclaredField("modController");
        controllerField.setAccessible(true);
        Object previous = controllerField.get(loader);
        LoadController controller = new LoadController(loader);
        Field active = LoadController.class.getDeclaredField("activeContainer");
        active.setAccessible(true);
        ModMetadata metadata = new ModMetadata();
        metadata.modId = "Botania";
        active.set(controller, new DummyModContainer(metadata));
        controllerField.set(loader, controller);
        try {
            return (net.minecraft.block.Block) Class.forName("vazkii.botania.common.block.mana.BlockSpawnerClaw")
                .getConstructor()
                .newInstance();
        } finally {
            controllerField.set(loader, previous);
        }
    }

    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static InputStream asset() throws IOException {
        InputStream stream = BotaniaModelRegression.class.getClassLoader()
            .getResourceAsStream("assets/botania/textures/model/spawnerClaw.png");
        if (stream == null) throw new IOException("Missing original Botania spawner claw texture");
        return stream;
    }

    private static IResourceManager resources(boolean missing) {
        return (IResourceManager) Proxy.newProxyInstance(
            IResourceManager.class.getClassLoader(),
            new Class<?>[] { IResourceManager.class },
            (proxy, method, args) -> {
                if (!method.getName()
                    .equals("getResource")) throw new UnsupportedOperationException(method.getName());
                if (missing) throw new IOException("missing test resource");
                return Proxy.newProxyInstance(
                    IResource.class.getClassLoader(),
                    new Class<?>[] { IResource.class },
                    (resource, operation, arguments) -> {
                        if (operation.getName()
                            .equals("getInputStream")) return asset();
                        throw new UnsupportedOperationException(operation.getName());
                    });
            });
    }
}
