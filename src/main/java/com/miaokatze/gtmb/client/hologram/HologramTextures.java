package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.interfaces.IBlockContainer;
import gregtech.api.interfaces.IBlockWithTextures;
import gregtech.api.interfaces.IColorModulationContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.covers.Cover;
import gregtech.common.render.GTMultiTextureRender;
import gregtech.common.render.GTRenderedTexture;
import gregtech.common.render.GTSidedTextureRender;
import gregtech.common.render.IIconTexture;

/** Native GT texture callbacks and static geometry, resolved once per snapshot without live tiles. */
final class HologramTextures {

    static final class Sample {

        final IIcon icon;
        final int color;

        Sample(IIcon icon, int color) {
            this.icon = icon;
            this.color = color;
        }
    }

    static final class Material {

        final List<Sample>[] sides;
        final List<Sample>[] covers;
        final List<HologramGeometry.Surface> geometry;
        final boolean gregTech, fallback, pipe, partial;

        Material(List<Sample>[] sides, List<Sample>[] covers, List<HologramGeometry.Surface> geometry, boolean gregTech,
            boolean fallback, boolean pipe, boolean partial) {
            this.sides = sides;
            this.covers = covers;
            this.geometry = geometry;
            this.gregTech = gregTech;
            this.fallback = fallback;
            this.pipe = pipe;
            this.partial = partial;
        }

        List<Sample> samples(HologramGeometry.Surface face) {
            return face.coverSide >= 0 ? covers[face.coverSide] : sides[face.side];
        }
    }

    // Only the optional cross-cell dedup index is bounded here. Live per-cell materials, native tiles,
    // and GL geometry have separate owners; these limits are not a whole-preview heap bound.
    private static final MaterialCache CACHE = new MaterialCache(2048, 2L * 1024 * 1024);
    private static final Map<HologramState.Cell, Material[]> RESOLVED = new IdentityHashMap<>();
    private static HologramState snapshot;
    private static final Field MULTI = texturesField(GTMultiTextureRender.class);
    private static final Field SIDED = texturesField(GTSidedTextureRender.class);
    private static boolean reloadInstalled;

    private HologramTextures() {}

    /** Preserve existing string-key equality; eviction sacrifices reuse, never a cell's resolved material. */
    static final class MaterialCache {

        private final int maxEntries;
        private final long maxKeyBytes;
        private final LinkedHashMap<String, Material> entries = new LinkedHashMap<>(16, .75f, true);
        private long keyBytes;

        MaterialCache(int maxEntries, long maxKeyBytes) {
            if (maxEntries < 1 || maxKeyBytes < 0) throw new IllegalArgumentException("Material cache limits");
            this.maxEntries = maxEntries;
            this.maxKeyBytes = maxKeyBytes;
        }

        Material get(String key) {
            return entries.get(key);
        }

        void put(String key, Material material) {
            if (key == null || material == null) throw new IllegalArgumentException("Material cache entry");
            // Charge UTF-16 character storage conservatively, even on compact-string JVMs.
            long cost = 2L * key.length();
            if (cost > maxKeyBytes) return;
            if (entries.remove(key) != null) keyBytes -= cost;
            while (!entries.isEmpty() && (entries.size() >= maxEntries || keyBytes + cost > maxKeyBytes)) {
                java.util.Iterator<String> oldest = entries.keySet()
                    .iterator();
                keyBytes -= 2L * oldest.next()
                    .length();
                oldest.remove();
            }
            entries.put(key, material);
            keyBytes += cost;
        }

        void clear() {
            entries.clear();
            keyBytes = 0;
        }

        int size() {
            return entries.size();
        }

        long retainedKeyBytes() {
            return keyBytes;
        }
    }

    static void clear() {
        CACHE.clear();
        RESOLVED.clear();
        snapshot = null;
    }

    static void begin(HologramState state) {
        installReloadListener();
        if (snapshot != state) {
            CACHE.clear();
            RESOLVED.clear();
            snapshot = state;
        }
    }

    static void advance(HologramState previous, HologramState next) {
        if (snapshot != previous) return;
        for (HologramState.Cell cell : next.changedPreviousCells) RESOLVED.remove(cell);
        snapshot = next;
    }

    static void installReloadListener() {
        if (!reloadInstalled) {
            reloadInstalled = true;
            if (Minecraft.getMinecraft()
                .getResourceManager() instanceof IReloadableResourceManager)
                ((IReloadableResourceManager) Minecraft.getMinecraft()
                    .getResourceManager()).registerReloadListener(manager -> HologramRenderer.release());
        }
    }

    static Material material(HologramState.Cell cell, boolean current) {
        installReloadListener();
        current = current || cell.anchor || (cell.status.equals("protected") && cell.actualStack != null);
        Material[] pair = RESOLVED.get(cell);
        if (pair == null) {
            pair = new Material[2];
            RESOLVED.put(cell, pair);
        }
        int index = current ? 1 : 0;
        if (pair[index] != null) return pair[index];
        String key = cacheKey(cell, current);
        Material material = CACHE.get(key);
        if (material == null) {
            material = resolve(cell, current);
            CACHE.put(key, material);
        }
        pair[index] = material;
        return material;
    }

    /** Pure cache identity; material resolution and native rendering remain separate. */
    static String cacheKey(HologramState.Cell cell, boolean current) {
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        ItemStack stack = current ? cell.actualStack : cell.targetStack;
        String key = (current ? cell.id : cell.wantId) + ":"
            + (current ? cell.meta : cell.wantMeta)
            + ":"
            + (stack == null ? "" : stack.getItem() + ":" + stack.getItemDamage() + ":" + stack.getTagCompound())
            + ":"
            + cell.iconOnly
            + ":"
            + Arrays.toString(cell.hintIcons)
            + ":"
            + Arrays.toString(cell.hintTint);
        key += ":" + (current ? cell.actualFacing : cell.targetFacing);
        key += ":" + (current ? cell.actualDisplay : cell.targetDisplay);
        // Untinted icon hints use semantic cell colors, not only icon/stack/display identity.
        if (cell.iconOnly && cell.hintTint.length < 3) key += ":hintColor=" + cell.textureColor();
        return key;
    }

    @SuppressWarnings("unchecked")
    private static Material resolve(HologramState.Cell cell, boolean current) {
        List<Sample>[] sides = (List<Sample>[]) new List<?>[6];
        List<Sample>[] covers = (List<Sample>[]) new List<?>[6];
        for (int side = 0; side < 6; side++) sides[side] = new ArrayList<>();
        for (int side = 0; side < 6; side++) covers[side] = new ArrayList<>();
        current = current || cell.anchor || (cell.status.equals("protected") && cell.actualStack != null);
        Block block = cell.block(current);
        int meta = current || cell.anchor ? cell.meta : cell.wantMeta;
        ItemStack stack = current || cell.anchor ? cell.actualStack : cell.targetStack;
        boolean gt = stack != null && stack.getItem() instanceof ItemMachines;
        NBTTagCompound display = current ? cell.actualDisplay : cell.targetDisplay;
        boolean pipe = false;
        float thickness = 1;
        ITexture[][] textures = null;
        int front = current ? cell.actualFacing : cell.targetFacing;
        if (front < 0 && display.hasKey("facing")) front = display.getInteger("facing");
        int[] textureSides = { 0, 1, 2, 3, 4, 5 };
        try {
            if (gt) {
                int id = stack.getItemDamage();
                IMetaTileEntity machine = id >= 0 && id < GregTechAPI.METATILEENTITIES.length
                    ? GregTechAPI.METATILEENTITIES[id]
                    : null;
                if (machine != null) {
                    pipe = machine instanceof MetaPipeEntity;
                    if (pipe) thickness = ((MetaPipeEntity) machine).getCollisionThickness();
                    textures = HologramMachineTextures.resolve(machine, front, textureSides, display);
                }
            } else if (block instanceof IBlockWithTextures) {
                textures = ((IBlockWithTextures) block).getInventoryTextures(meta);
            }
        } catch (RuntimeException ignored) {
            // Custom textures may require a world/base tile; fall back to their ordinary block icon.
        }
        boolean fallback = false;
        TextureMap atlas = Minecraft.getMinecraft()
            .getTextureMapBlocks();
        for (int side = 0; side < 6; side++) {
            try {
                int sourceSide = textureSides[side];
                if (textures != null && side < textures.length && textures[side] != null)
                    for (ITexture texture : textures[side]) append(sides[side], texture, sourceSide, 0);
                if (gt && sides[side].isEmpty()) fallback = true;
                if (sides[side].isEmpty() && cell.iconOnly && !cell.hintIcons[side].isEmpty())
                    sides[side].add(new Sample(atlas.getAtlasSprite(cell.hintIcons[side]), cell.textureColor()));
                if (sides[side].isEmpty() && block != null && block != Blocks.air) {
                    IIcon icon = block.getIcon(side, meta);
                    if (icon != null) sides[side].add(new Sample(icon, block.getRenderColor(meta)));
                }
            } catch (RuntimeException ignored) {
                // A broken optional icon must not hide the rest of the schematic.
            }
            if (sides[side].isEmpty()) {
                fallback = true;
                sides[side].add(new Sample(atlas.getAtlasSprite("missingno"), 0xffffff));
            }
        }
        int coverMask = 0;
        NBTTagList rows = display.getTagList("gt.covers", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            int side = row.getByte("s");
            if (side < 0 || side >= 6) continue;
            if (HologramLinkNodePreview.enhanced(row)) {
                coverMask |= 1 << side;
                continue;
            }
            try {
                ITexture cover = coverTexture(row, side, pipe);
                append(covers[side], cover, side, 0);
            } catch (RuntimeException requiresTile) {}
            if (covers[side].isEmpty()) {
                fallback = true;
                covers[side].add(new Sample(atlas.getAtlasSprite("missingno"), 0xffffff));
            }
            coverMask |= 1 << side;
            if (!pipe || thickness >= 1) sides[side].addAll(covers[side]);
        }
        List<HologramGeometry.Surface> geometry = pipe
            ? HologramGeometry.pipe(thickness, display.getInteger("connections"), coverMask)
            : HologramGeometry.cube();
        // GT coil blocks also supply layered GT textures; the diagnostic flag includes them.
        return new Material(
            sides,
            covers,
            geometry,
            gt || block instanceof IBlockWithTextures,
            fallback,
            pipe,
            pipe && thickness < 1);
    }

    static ITexture coverTexture(NBTTagCompound row, int side, boolean pipe) {
        Cover cover = CoverRegistry
            .buildCoverFromNbt((NBTTagCompound) row.copy(), ForgeDirection.getOrientation(side), null);
        if (cover == null || !cover.isValid()) return null;
        ITexture texture = pipe ? cover.getSpecialFaceTexture() : cover.getOverlayTexture();
        return texture != null ? texture : CoverRegistry.getCoverTexture(cover.asItemStack());
    }

    static int inventorySide(int front, int side) {
        return sourceSide(side, front);
    }

    static int sourceSide(int side, int front) {
        // GT CommonMetaTileEntity inventory textures face WEST. Reorder the six immutable
        // atlas samples to the planned/world facing without mutating the shared prototype.
        return HologramMachineTextures.inventorySide(front, side);
    }

    private static void append(List<Sample> samples, ITexture texture, int side, int depth) {
        if (texture == null || depth > 8) return;
        Field field = texture instanceof GTMultiTextureRender ? MULTI
            : texture instanceof GTSidedTextureRender ? SIDED : null;
        if (field != null) {
            try {
                ITexture[] children = (ITexture[]) field.get(texture);
                if (texture instanceof GTSidedTextureRender) {
                    if (side < children.length) append(samples, children[side], side, depth + 1);
                } else for (ITexture child : children) append(samples, child, side, depth + 1);
            } catch (IllegalAccessException ignored) {}
        } else if (texture instanceof IIconTexture) {
            IIcon icon = ((IIconTexture) texture).getIcon(side, null);
            int color = 0xffffff;
            if (texture instanceof IColorModulationContainer) {
                short[] rgba = ((IColorModulationContainer) texture).getRGBA();
                if (rgba != null && rgba.length >= 3)
                    color = (rgba[0] & 255) << 16 | (rgba[1] & 255) << 8 | (rgba[2] & 255);
            }
            if (icon != null) samples.add(new Sample(icon, color));
            if (texture instanceof GTRenderedTexture) {
                IIcon overlay = ((GTRenderedTexture) texture).getIcon(ForgeDirection.getOrientation(side), true, null);
                if (overlay != null) samples.add(new Sample(overlay, 0xffffff));
            }
        } else if (texture instanceof IBlockContainer) {
            // Native facade/CTM textures still identify their copied block and metadata.
            // Sample that block in isolation; a connected-texture world is intentionally not fabricated.
            IBlockContainer copied = (IBlockContainer) texture;
            Block block = copied.getBlock();
            if (block != null) {
                int copiedSide = side;
                try {
                    Field direction = texture.getClass()
                        .getDeclaredField("mSide");
                    direction.setAccessible(true);
                    int configured = ((Number) direction.get(texture)).intValue();
                    if (configured >= 0 && configured < 6) copiedSide = configured;
                } catch (ReflectiveOperationException | SecurityException ignored) {}
                IIcon icon = block.getIcon(copiedSide, copied.getMeta());
                if (icon != null) samples.add(new Sample(icon, block.getRenderColor(copied.getMeta())));
            }
        }
    }

    private static Field texturesField(Class<?> type) {
        try {
            Field field = type.getDeclaredField("mTextures");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }
}
