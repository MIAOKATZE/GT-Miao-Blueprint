package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.util.IIcon;

/** Render-thread block/meta hints. No tile construction, native bake, cover or attachment decoding. */
final class HologramLightPreview {

    private static final Map<Block, Map<Integer, HologramTextures.Material>> MATERIALS = new IdentityHashMap<>();

    private HologramLightPreview() {}

    static void clear() {
        MATERIALS.clear();
    }

    static HologramTextures.Material material(HologramState.Cell cell, boolean current) {
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        Block block = cell.block(current);
        int meta = current ? cell.meta : cell.wantMeta;
        return MATERIALS.computeIfAbsent(block, unused -> new HashMap<>())
            .computeIfAbsent(meta, unused -> resolve(block, meta));
    }

    @SuppressWarnings("unchecked")
    private static HologramTextures.Material resolve(Block block, int meta) {
        List<HologramTextures.Sample>[] sides = (List<HologramTextures.Sample>[]) new List<?>[6];
        List<HologramTextures.Sample>[] covers = (List<HologramTextures.Sample>[]) new List<?>[6];
        IIcon missing = Minecraft.getMinecraft()
            .getTextureMapBlocks()
            .getAtlasSprite("missingno");
        for (int side = 0; side < 6; side++) {
            IIcon icon = null;
            try {
                if (block != null) icon = block.getIcon(side, meta);
            } catch (RuntimeException unavailable) {
                // A hint is provisional; the detailed path retains its material/model diagnostics.
            }
            sides[side] = Collections
                .singletonList(new HologramTextures.Sample(icon == null ? missing : icon, 0xffffff));
            covers[side] = Collections.emptyList();
        }
        return new HologramTextures.Material(sides, covers, HologramGeometry.cube(), false, false, false, false);
    }
}
