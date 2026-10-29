package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.interfaces.IColorModulationContainer;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.render.GTMultiTextureRender;
import gregtech.common.render.GTRenderedTexture;
import gregtech.common.render.GTSidedTextureRender;
import gregtech.common.render.IIconTexture;

/** Real GT coil metadata/cache and layered containers; controlled icons, not atlas/GL acceptance. */
public final class HologramStaticPreviewMaterialRegression {

    private HologramStaticPreviewMaterialRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramStaticPreviewMaterialRegression.class, args)) return;
        run();
        System.out.println("HologramStaticPreviewMaterialRegression PASS");
    }

    public static void run() throws Exception {
        Field unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Field minecraftField = field(Minecraft.class, "theMinecraft");
        Object previousMinecraft = minecraftField.get(null);
        // The registry-only bootstrap has no discovered-mod index. Real GTRenderedTexture queries Angelica;
        // supply the controlled "not installed" fixture and restore both FML and GT's cached lookup state.
        Field namedMods = field(cpw.mods.fml.common.Loader.class, "namedMods");
        Object previousMods = namedMods.get(cpw.mods.fml.common.Loader.instance());
        Field checked = field(gregtech.api.enums.Mods.class, "checked");
        Field loaded = field(gregtech.api.enums.Mods.class, "modLoaded");
        boolean previousChecked = checked.getBoolean(gregtech.api.enums.Mods.Angelica);
        boolean previousLoaded = loaded.getBoolean(gregtech.api.enums.Mods.Angelica);
        try {
            namedMods.set(cpw.mods.fml.common.Loader.instance(), new HashMap<>());
            checked.setBoolean(gregtech.api.enums.Mods.Angelica, false);
            Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
            TextureMap atlas = (TextureMap) unsafe.allocateInstance(TextureMap.class);
            field(TextureMap.class, "mapUploadedSprites").set(atlas, new HashMap<String, TextureAtlasSprite>());
            field(TextureMap.class, "missingImage").set(atlas, sprite("controlled-missing"));
            field(Minecraft.class, "textureMapBlocks").set(minecraft, atlas);
            minecraftField.set(null, minecraft);
            BlockCasings5 coil = (BlockCasings5) unsafe.allocateInstance(BlockCasings5.class);
            coil.setBlockBounds(0, 0, 0, 1, 1, 1);
            Method register = HologramAuthorStaticFactsRegression.class
                .getDeclaredMethod("registered", String.class, Block.class, boolean.class);
            register.setAccessible(true);
            register.invoke(null, "static_preview_coil", coil, true);
            AtomicReferenceArray<ITexture[][]> cache = new AtomicReferenceArray<>(64);
            field(BlockCasings5.class, "textureCache").set(coil, cache);
            IIcon base = sprite("controlled-base"), overlay = sprite("controlled-overlay");
            ITexture layered = GTMultiTextureRender
                .create(new ITexture[] { texture(base, 0x123456), texture(overlay, 0xabcdef) });
            ITexture[][] textures = new ITexture[6][];
            for (int side = 0; side < 6; side++) textures[side] = new ITexture[] { layered };
            // Actual getTextures(meta) chooses old/new coil cache banks. Populate both without GT initialization.
            cache.set(0, textures);
            cache.set(32, textures);
            HologramState.Cell cell = new HologramState.Cell(0, row(coil));
            Method resolve = HologramTextures.class
                .getDeclaredMethod("resolve", HologramState.Cell.class, boolean.class);
            resolve.setAccessible(true);
            HologramTextures.Material detailed = (HologramTextures.Material) resolve.invoke(null, cell, false);
            HologramLightPreview.clear();
            HologramTextures.Material cheap = HologramLightPreview.material(cell, false);
            for (int side = 0; side < 6; side++) {
                SimpleAssert.eq(2, detailed.sides[side].size(), "control uses existing full GT layered material");
                SimpleAssert.eq(2, cheap.sides[side].size(), "cheap static GT material retains both layers");
                for (int layer = 0; layer < 2; layer++) {
                    SimpleAssert.that(
                        cheap.sides[side].get(layer).icon == detailed.sides[side].get(layer).icon,
                        "static GT layer identity/order equals existing detailed material");
                    SimpleAssert.eq(
                        detailed.sides[side].get(layer).color,
                        cheap.sides[side].get(layer).color,
                        "static GT tint equals existing detailed material");
                }
            }
            SimpleAssert.eq(false, cheap.fallback, "complete layered material is not a missing placeholder");
            SimpleAssert.eq(true, cheap.gregTech, "cheap material reports its actual GT source");
            SimpleAssert
                .eq(true, HologramLightPreview.staticOpaqueCube(cell, false), "audited full static coil occludes");
            boundaries(coil);
            metadataAndFacing(coil, cache);
            layerAndCacheBoundaries(coil, cache, unsafe);
            subclassBoundary(unsafe, register);
        } finally {
            HologramLightPreview.clear();
            HologramTextures.clear();
            minecraftField.set(null, previousMinecraft);
            namedMods.set(cpw.mods.fml.common.Loader.instance(), previousMods);
            checked.setBoolean(gregtech.api.enums.Mods.Angelica, previousChecked);
            loaded.setBoolean(gregtech.api.enums.Mods.Angelica, previousLoaded);
        }
    }

    private static void boundaries(Block coil) {
        for (String key : new String[] { "nativeTile", "gt.covers", "unknownVisual" }) {
            NBTTagCompound source = row(coil), display = new NBTTagCompound();
            display.setTag(key, new NBTTagCompound());
            source.setTag("targetDisplay", display);
            SimpleAssert.eq(
                false,
                HologramLightPreview.staticOpaqueCube(new HologramState.Cell(0, source), false),
                "opaque display payload remains outside static proof: " + key);
            SimpleAssert.eq(
                null,
                HologramLightPreview.staticMaterial(new HologramState.Cell(0, source), false),
                "opaque display payload cannot borrow cached full material: " + key);
        }
        NBTTagCompound source = row(coil);
        source.setInteger("nativeModelPending", 2);
        SimpleAssert.eq(
            false,
            HologramLightPreview.staticOpaqueCube(new HologramState.Cell(0, source), false),
            "pending target remains deferred");
        SimpleAssert.eq(
            null,
            HologramLightPreview.staticMaterial(new HologramState.Cell(0, source), false),
            "pending target cannot borrow cached full material");
        source = row(coil);
        ItemStack tagged = new ItemStack(coil);
        tagged.setTagCompound(new NBTTagCompound());
        source.setTag("targetStack", tagged.writeToNBT(new NBTTagCompound()));
        SimpleAssert.eq(
            null,
            HologramLightPreview.staticMaterial(new HologramState.Cell(0, source), false),
            "even empty item NBT excludes static material");
        source = row(coil);
        source.setString("wantId", "minecraft:glass");
        source.setTag("targetStack", new ItemStack(Blocks.glass).writeToNBT(new NBTTagCompound()));
        SimpleAssert.eq(
            false,
            HologramLightPreview.staticOpaqueCube(new HologramState.Cell(0, source), false),
            "target glass does not borrow actual coil opacity");
        for (String effective : new String[] { "protected", "anchor" }) {
            source.setString("status", effective.equals("protected") ? "protected" : "planned");
            source.setBoolean("anchor", effective.equals("anchor"));
            SimpleAssert.eq(
                true,
                HologramLightPreview.staticOpaqueCube(new HologramState.Cell(0, source), false),
                "effective actual side retained for " + effective);
        }
        HologramNativeRenderRegistry.Adapter adapter = block -> block == coil;
        HologramNativeRenderRegistry.register(adapter);
        try {
            SimpleAssert.eq(
                false,
                HologramLightPreview.staticOpaqueCube(new HologramState.Cell(0, row(coil)), false),
                "external adapter takes precedence over cached static material");
            SimpleAssert.eq(
                null,
                HologramLightPreview.staticMaterial(new HologramState.Cell(0, row(coil)), false),
                "adapter override cannot receive cached complete static material");
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static void metadataAndFacing(Block coil, AtomicReferenceArray<ITexture[][]> cache) {
        IIcon targetIcon = sprite("metadata-one");
        ITexture[][] targetTextures = new ITexture[6][];
        for (int side = 0; side < 6; side++) targetTextures[side] = new ITexture[] { texture(targetIcon, 0x654321) };
        cache.set(1, targetTextures);
        cache.set(33, targetTextures);
        NBTTagCompound source = row(coil);
        source.setInteger("wantMeta", 1);
        source.setTag("targetStack", new ItemStack(coil, 1, 1).writeToNBT(new NBTTagCompound()));
        HologramTextures.Material actual = HologramLightPreview.staticMaterial(new HologramState.Cell(0, source), true);
        for (int facing = 0; facing < 6; facing++) {
            source.setInteger("actualFacing", 5 - facing);
            source.setInteger("targetFacing", facing);
            HologramState.Cell cell = new HologramState.Cell(0, source);
            HologramTextures.Material target = HologramLightPreview.staticMaterial(cell, false);
            SimpleAssert.that(target != null && target != actual, "actual/target metadata use independent materials");
            for (int side = 0; side < 6; side++) {
                SimpleAssert.that(
                    target.sides[side].get(0).icon == targetIcon,
                    "coil metadata textures remain side-correct for facing " + facing);
            }
            SimpleAssert.that(
                HologramLightPreview.staticMaterial(cell, true) == actual,
                "actual side does not borrow target metadata/facing");
        }
        source.setString("status", "protected");
        SimpleAssert.that(
            HologramLightPreview.staticMaterial(new HologramState.Cell(0, source), false) == actual,
            "protected material uses actual metadata");
        source.setString("status", "planned");
        source.setBoolean("anchor", true);
        SimpleAssert.that(
            HologramLightPreview.staticMaterial(new HologramState.Cell(0, source), false) == actual,
            "anchor material uses actual metadata");
        source.setBoolean("anchor", false);
        source.setTag("targetStack", new ItemStack(coil).writeToNBT(new NBTTagCompound()));
        SimpleAssert.eq(
            null,
            HologramLightPreview.staticMaterial(new HologramState.Cell(0, source), false),
            "item metadata mismatch cannot borrow target cache entry");
    }

    private static void layerAndCacheBoundaries(Block coil, AtomicReferenceArray<ITexture[][]> cache,
        sun.misc.Unsafe unsafe) throws Exception {
        IIcon base = sprite("rendered-base"), overlay = sprite("rendered-overlay");
        IIconContainer icons = (IIconContainer) Proxy.newProxyInstance(
            IIconContainer.class.getClassLoader(),
            new Class<?>[] { IIconContainer.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getIcon")) return base;
                if (method.getName()
                    .equals("getOverlayIcon")) return overlay;
                throw new AssertionError("Unexpected icon-container callback: " + method);
            });
        Constructor<GTRenderedTexture> constructor = GTRenderedTexture.class
            .getDeclaredConstructor(IIconContainer.class, short[].class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        ITexture rendered = constructor.newInstance(icons, new short[] { 18, 52, 86, 0 }, false, false);
        GTSidedTextureRender sided = (GTSidedTextureRender) unsafe.allocateInstance(GTSidedTextureRender.class);
        ITexture[] sideLayers = new ITexture[6];
        for (int side = 0; side < 6; side++) sideLayers[side] = texture(sprite("side-" + side), side);
        field(GTSidedTextureRender.class, "mTextures").set(sided, sideLayers);
        ITexture[][] textures = new ITexture[6][];
        for (int side = 0; side < 6; side++)
            textures[side] = new ITexture[] { GTMultiTextureRender.create(new ITexture[] { rendered, sided }) };
        cache.set(0, textures);
        cache.set(32, textures);
        HologramState.Cell cell = new HologramState.Cell(0, row(coil));
        HologramTextures.Material previous = HologramLightPreview.staticMaterial(cell, false);
        SimpleAssert.that(
            previous == HologramLightPreview.staticMaterial(new HologramState.Cell(1, row(coil)), false),
            "same safe block/meta shares bounded static texture work across source cells");
        HologramLightPreview.clear();
        HologramTextures.Material resolved = HologramLightPreview.staticMaterial(cell, false);
        SimpleAssert.that(
            resolved != null && resolved != previous,
            "release/resource reload rebuilds complete static material");
        for (int side = 0; side < 6; side++) {
            SimpleAssert.eq(3, resolved.sides[side].size(), "real rendered overlay and sided child retained");
            SimpleAssert.that(resolved.sides[side].get(0).icon == base, "real GT base icon retained");
            SimpleAssert.that(resolved.sides[side].get(1).icon == overlay, "real GT overlay icon retained");
            SimpleAssert.eq(0x123456, resolved.sides[side].get(0).color, "real GT base tint retained");
            SimpleAssert.eq(0xffffff, resolved.sides[side].get(1).color, "overlay stays untinted");
            SimpleAssert.eq(side, resolved.sides[side].get(2).color, "sided branch uses its matching world side");
        }
        ITexture unknown = (ITexture) Proxy.newProxyInstance(
            ITexture.class.getClassLoader(),
            new Class<?>[] { ITexture.class },
            (proxy, method, args) -> {
                throw new AssertionError("Unknown texture must not invoke rendering: " + method);
            });
        textures[2] = new ITexture[] { texture(base, 0xffffff), unknown };
        HologramLightPreview.clear();
        SimpleAssert.eq(
            null,
            HologramLightPreview.staticMaterial(cell, false),
            "a recognized base cannot hide an unsupported overlay");
        SimpleAssert.eq(
            false,
            HologramLightPreview.staticOpaqueCube(cell, false),
            "incomplete layered material cannot acquire opacity certificate");
        textures[2] = new ITexture[] { texture(base, 0xffffff) };
        HologramLightPreview.clear();
        SimpleAssert.that(
            HologramLightPreview.staticMaterial(cell, false) != null,
            "resource clear permits material recovery after unavailable icons/layers");
    }

    private static void subclassBoundary(sun.misc.Unsafe unsafe, Method register) throws Exception {
        Block unknown = (Block) unsafe.allocateInstance(UnreviewedCoil.class);
        register.invoke(null, "static_preview_unknown_coil", unknown, true);
        SimpleAssert.eq(
            null,
            HologramLightPreview.staticMaterial(new HologramState.Cell(0, row(unknown)), false),
            "an unreviewed subclass is rejected before texture callbacks");
    }

    public static final class UnreviewedCoil extends BlockCasings5 {

        @Override
        public ITexture[][] getTextures(int metadata) {
            throw new AssertionError("Unknown subclass texture callback entered cheap path");
        }
    }

    private static NBTTagCompound row(Block block) {
        NBTTagCompound row = new NBTTagCompound();
        String id = Block.blockRegistry.getNameForObject(block);
        row.setString("id", id);
        row.setString("wantId", id);
        row.setString("status", "planned");
        row.setTag("actualStack", new ItemStack(block).writeToNBT(new NBTTagCompound()));
        row.setTag("targetStack", new ItemStack(block).writeToNBT(new NBTTagCompound()));
        return row;
    }

    private static ITexture texture(IIcon icon, int color) {
        return (ITexture) Proxy.newProxyInstance(
            ITexture.class.getClassLoader(),
            new Class<?>[] { ITexture.class, IIconTexture.class, IColorModulationContainer.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getIcon")) return icon;
                if (method.getName()
                    .equals("getRGBA"))
                    return new short[] { (short) (color >> 16 & 255), (short) (color >> 8 & 255), (short) (color & 255),
                        0 };
                if (method.getName()
                    .equals("isValidTexture")) return true;
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("toString")) return "controlled texture";
                throw new AssertionError("Static material must not invoke native rendering: " + method);
            });
    }

    private static TextureAtlasSprite sprite(String name) {
        return new TextureAtlasSprite(name) {};
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
