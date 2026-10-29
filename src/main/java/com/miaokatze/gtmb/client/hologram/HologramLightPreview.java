package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

/** Render-thread static materials and provisional hints. No tiles, native bake or payload decoding. */
final class HologramLightPreview {

    private static final Map<Block, Map<Integer, HologramTextures.Material>> MATERIALS = new IdentityHashMap<>();

    private static final Map<Block, Map<Integer, HologramTextures.Material>> STATIC_MATERIALS = new IdentityHashMap<>();

    private HologramLightPreview() {}

    static void clear() {
        MATERIALS.clear();
        STATIC_MATERIALS.clear();
    }

    static HologramTextures.Material material(HologramState.Cell cell, boolean current) {
        HologramTextures.Material complete = staticMaterial(cell, current);
        if (complete != null) return complete;
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        Block block = cell.block(current);
        int meta = current ? cell.meta : cell.wantMeta;
        return MATERIALS.computeIfAbsent(block, unused -> new HashMap<>())
            .computeIfAbsent(meta, unused -> resolve(block, meta));
    }

    /**
     * Complete, metadata-only material for audited ordinary cubes. Null keeps the deferred/provisional path.
     * This does not certify that the cell has been presented, or that any native model is ready.
     */
    static HologramTextures.Material staticMaterial(HologramState.Cell cell, boolean current) {
        int kind = staticCubeKind(cell, current);
        // Chisel's geometry is known but its connected material still belongs to its native adapter.
        if (kind == 0 || kind == 2) return null;
        current = effectiveCurrent(cell, current);
        Block block = cell.block(current);
        int meta = current ? cell.meta : cell.wantMeta;
        return cachedStaticMaterial(block, meta);
    }

    private static HologramTextures.Material cachedStaticMaterial(Block block, int meta) {
        Map<Integer, HologramTextures.Material> materials = STATIC_MATERIALS
            .computeIfAbsent(block, unused -> new HashMap<>());
        if (!materials.containsKey(meta)) materials.put(meta, HologramTextures.staticBlockMaterial(block, meta));
        return materials.get(meta);
    }

    /** Only an occlusion certificate for provisional hints, never native-model readiness or presentation. */
    static boolean staticOpaqueCube(HologramState.Cell cell, boolean current) {
        int kind = staticCubeKind(cell, current);
        // A GT background icon alone is not enough: its complete layered material must be available.
        if (kind == 0) return false;
        if (kind != 3) return true;
        current = effectiveCurrent(cell, current);
        return cachedStaticMaterial(cell.block(current), current ? cell.meta : cell.wantMeta) != null;
    }

    private static boolean effectiveCurrent(HologramState.Cell cell, boolean current) {
        return current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
    }

    /** 1 = exact vanilla cube, 2 = native Chisel cube, 3 = exact metadata-only GT layered cube. */
    private static int staticCubeKind(HologramState.Cell cell, boolean current) {
        if (cell == null || cell.iconOnly || cell.missingBlock || cell.sourceMissing) return 0;
        current = effectiveCurrent(cell, current);
        if (cell.modelPending(current) || "localBudget".equals(cell.renderKind)
            || "localFallback".equals(cell.renderKind)) return 0;
        Block block = cell.block(current);
        if (block == null) return 0;
        Class<?> type = block.getClass();
        boolean vanilla = type == Block.class || type == net.minecraft.block.BlockStone.class
            || type == net.minecraft.block.BlockDirt.class
            || type == net.minecraft.block.BlockOre.class
            || type == net.minecraft.block.BlockCompressed.class;
        boolean chiselCandidate = HologramChiselNativeRender.INSTANCE.supports(block);
        // Pinned GT BlockCasings5.getInventoryTextures delegates to metadata-only getTextures.
        // Do NOT admit arbitrary IBlockWithTextures: frames, ores and custom subclasses differ.
        boolean layeredGt = type == gregtech.common.blocks.BlockCasings5.class;
        if (!vanilla && !chiselCandidate && !layeredGt) return 0;
        // Check the selected adapter before any block callback; an extension owns its own appearance.
        if (!chiselCandidate && HologramNativeTiles.supported(block)) return 0;
        if (chiselCandidate && !HologramChiselNativeRender.selected(block)) return 0;
        int meta = current ? cell.meta : cell.wantMeta;
        ItemStack stack = current ? cell.actualStack : cell.targetStack;
        if (stack == null || !(stack.getItem() instanceof ItemBlock)
            || stack.hasTagCompound()
            || Block.getBlockFromItem(stack.getItem()) != block
            || ((ItemBlock) stack.getItem()).getMetadata(stack.getItemDamage()) != meta
            || block.hasTileEntity(meta)) return 0;
        NBTTagCompound display = current ? cell.actualDisplay : cell.targetDisplay;
        // Cold source descriptors add only color/facing. Any other appearance stays outside this proof.
        for (Object key : display.func_150296_c()) if (!"color".equals(key) && !"facing".equals(key)) return 0;
        if (!block.isOpaqueCube() || !block.renderAsNormalBlock()
            || block.getRenderBlockPass() != 0
            || (!chiselCandidate && !layeredGt && block.getRenderType() != 0)
            || block.getBlockBoundsMinX() != 0
            || block.getBlockBoundsMinY() != 0
            || block.getBlockBoundsMinZ() != 0
            || block.getBlockBoundsMaxX() != 1
            || block.getBlockBoundsMaxY() != 1
            || block.getBlockBoundsMaxZ() != 1) return 0;
        return layeredGt ? 3 : chiselCandidate ? 2 : 1;
    }

    static boolean hideInteriorFace(boolean hidden, int moveX, int moveY, int moveZ) {
        return hidden && moveX == 0 && moveY == 0 && moveZ == 0;
    }

    @SuppressWarnings("unchecked")
    private static HologramTextures.Material resolve(Block block, int meta) {
        List<HologramTextures.Sample>[] sides = (List<HologramTextures.Sample>[]) new List<?>[6];
        List<HologramTextures.Sample>[] covers = (List<HologramTextures.Sample>[]) new List<?>[6];
        IIcon missing = Minecraft.getMinecraft()
            .getTextureMapBlocks()
            .getAtlasSprite("missingno");
        // A known layered GT block reaches this path only when complete static material was inadmissible
        // or unavailable. Its background getIcon alone is still provisional, even when non-null.
        boolean fallback = block != null && block.getClass() == gregtech.common.blocks.BlockCasings5.class;
        for (int side = 0; side < 6; side++) {
            IIcon icon = null;
            try {
                if (block != null) icon = block.getIcon(side, meta);
            } catch (RuntimeException unavailable) {
                // A hint is provisional; the detailed path retains its material/model diagnostics.
            }
            if (icon == null) fallback = true;
            sides[side] = Collections
                .singletonList(new HologramTextures.Sample(icon == null ? missing : icon, 0xffffff));
            covers[side] = Collections.emptyList();
        }
        return new HologramTextures.Material(sides, covers, HologramGeometry.cube(), false, fallback, false, false);
    }
}
