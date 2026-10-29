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

    /** Only an occlusion certificate for provisional hints, never native-model readiness or shape admission. */
    static boolean staticOpaqueCube(HologramState.Cell cell, boolean current) {
        if (cell == null || cell.iconOnly || cell.missingBlock || cell.sourceMissing) return false;
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        if (cell.modelPending(current) || "localBudget".equals(cell.renderKind)
            || "localFallback".equals(cell.renderKind)) return false;
        Block block = cell.block(current);
        if (block == null) return false;
        Class<?> type = block.getClass();
        boolean vanilla = type == Block.class || type == net.minecraft.block.BlockStone.class
            || type == net.minecraft.block.BlockDirt.class
            || type == net.minecraft.block.BlockOre.class
            || type == net.minecraft.block.BlockCompressed.class;
        boolean chiselCandidate = HologramChiselNativeRender.INSTANCE.supports(block);
        // Do not introduce metadata/TE/shape callbacks on arbitrary mod subclasses during the cheap pass.
        if (!vanilla && !chiselCandidate) return false;
        int meta = current ? cell.meta : cell.wantMeta;
        ItemStack stack = current ? cell.actualStack : cell.targetStack;
        if (stack == null || !(stack.getItem() instanceof ItemBlock)
            || stack.hasTagCompound()
            || Block.getBlockFromItem(stack.getItem()) != block
            || ((ItemBlock) stack.getItem()).getMetadata(stack.getItemDamage()) != meta
            || block.hasTileEntity(meta)) return false;
        NBTTagCompound display = current ? cell.actualDisplay : cell.targetDisplay;
        // Cold source descriptors add only color/facing. Any other appearance remains outside this cheap proof.
        for (Object key : display.func_150296_c()) if (!"color".equals(key) && !"facing".equals(key)) return false;
        boolean chisel = chiselCandidate && HologramChiselNativeRender.opaqueCube(block);
        if (!chisel && (!vanilla || HologramNativeTiles.supported(block))) return false;
        return block.isOpaqueCube() && block.renderAsNormalBlock()
            && block.getRenderBlockPass() == 0
            && (chisel || block.getRenderType() == 0)
            && block.getBlockBoundsMinX() == 0
            && block.getBlockBoundsMinY() == 0
            && block.getBlockBoundsMinZ() == 0
            && block.getBlockBoundsMaxX() == 1
            && block.getBlockBoundsMaxY() == 1
            && block.getBlockBoundsMaxZ() == 1;
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
