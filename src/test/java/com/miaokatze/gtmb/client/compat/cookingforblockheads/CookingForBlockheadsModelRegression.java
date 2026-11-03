package com.miaokatze.gtmb.client.compat.cookingforblockheads;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.client.preview.ModelRendererMesh;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModMetadata;

/** Real CFB dev model constructors and original assets, CPU only: no display, tessellator draw or native TESR. */
public final class CookingForBlockheadsModelRegression {

    private CookingForBlockheadsModelRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(CookingForBlockheadsModelRegression.class, args)) return;
        Constructor<?> constructor = CookingForBlockheadsClientCompatibility.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object module = constructor.newInstance();
        intentionalUpperFridge((CookingForBlockheadsClientCompatibility) module);
        Map<?, ?> models = (Map<?, ?>) field(module, "models");
        SimpleAssert.eq(11, models.size(), "ten kitchen types plus large fridge model");
        Field compiled = ModelRenderer.class.getDeclaredField("compiled");
        compiled.setAccessible(true);
        int vertices = 0;
        for (Map.Entry<?, ?> entry : models.entrySet()) {
            Object model = entry.getValue();
            TextureAtlasSprite sprite = (TextureAtlasSprite) field(model, "sprite");
            sprite.load(new ClasspathResources(), new ResourceLocation("gtmb", "unused"));
            SimpleAssert.that(
                sprite.getIconWidth() > 0 && sprite.getIconWidth() == sprite.getIconHeight(),
                "CFB entity image is padded for the block atlas: " + entry.getKey());
            List<?> parts = (List<?>) field(model, "parts");
            int[] count = { 0 };
            for (Object part : parts) for (String key : new String[] { "normal", "flipped" }) {
                ModelRenderer root = (ModelRenderer) field(part, key);
                SimpleAssert.that(!compiled.getBoolean(root), "native model starts without a GL display list");
                ModelRendererMesh.emit(root, 1f / 16, (x, y, z, u, v, nx, ny, nz) -> {
                    SimpleAssert.that(
                        Double.isFinite(x) && Double.isFinite(y)
                            && Double.isFinite(z)
                            && Float.isFinite(u)
                            && Float.isFinite(v),
                        "finite native CPU geometry");
                    count[0]++;
                });
                SimpleAssert.that(!compiled.getBoolean(root), "CPU mesh does not compile native display lists");
            }
            SimpleAssert.that(count[0] > 0 && count[0] % 4 == 0, "complete quads for " + entry.getKey());
            vertices += count[0];
        }
        System.out.println("Cooking For Blockheads real static models passed: 11 models, " + vertices + " vertices");
    }

    private static void intentionalUpperFridge(CookingForBlockheadsClientCompatibility module) throws Exception {
        Block fridge = (Block) Class.forName("net.blay09.mods.cookingforblockheads.block.BlockFridge")
            .getConstructor()
            .newInstance();
        Loader loader = Loader.instance();
        Field controllerField = Loader.class.getDeclaredField("modController");
        controllerField.setAccessible(true);
        Object previousController = controllerField.get(loader);
        LoadController controller = new LoadController(loader);
        Field active = LoadController.class.getDeclaredField("activeContainer");
        active.setAccessible(true);
        ModMetadata metadata = new ModMetadata();
        metadata.modId = "cookingforblockheads";
        active.set(controller, new DummyModContainer(metadata));
        controllerField.set(loader, controller);
        try {
            Block.blockRegistry.addObject(4094, "cookingforblockheads:fridge", fridge);
        } finally {
            controllerField.set(loader, previousController);
        }
        SimpleAssert.eq(
            "cookingforblockheads:fridge",
            Block.blockRegistry.getNameForObject(fridge),
            "empty geometry fixture uses the actual namespace");
        IBlockAccess above = (IBlockAccess) Proxy.newProxyInstance(
            IBlockAccess.class.getClassLoader(),
            new Class<?>[] { IBlockAccess.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getBlock")) return ((Integer) args[1]) == 63 ? fridge : Blocks.air;
                throw new AssertionError("Upper fridge predicate queried unrelated state: " + method);
            });
        SimpleAssert.that(
            module.allowsEmptyGeometry(fridge, 2, above, 0, 64, 0),
            "only the upper fridge has explicitly accepted empty geometry");
        SimpleAssert.that(
            !module.allowsEmptyGeometry(fridge, 2, above, 0, 63, 0)
                && !module.allowsEmptyGeometry(Blocks.stone, 2, above, 0, 64, 0),
            "lower fridges and unrelated blocks still require real geometry");
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static final class ClasspathResources implements IResourceManager {

        public Set<String> getResourceDomains() {
            return Collections.singleton("cookingforblockheads");
        }

        public IResource getResource(ResourceLocation location) throws IOException {
            String path = "assets/" + location.getResourceDomain() + "/" + location.getResourcePath();
            InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(path);
            if (stream == null) throw new IOException("Missing actual CFB texture " + path);
            return new IResource() {

                public InputStream getInputStream() {
                    return stream;
                }

                public boolean hasMetadata() {
                    return false;
                }

                public IMetadataSection getMetadata(String section) {
                    return null;
                }
            };
        }

        public List<IResource> getAllResources(ResourceLocation location) throws IOException {
            return Collections.singletonList(getResource(location));
        }
    }
}
