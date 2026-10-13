package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.interfaces.IHeatingCoil;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.GlassTier;
import gregtech.common.blocks.BlockFrameBox;

/** Exact factory provenance plus enumerated old states. A texture or coincident block ID is never a family. */
final class HologramReplacementFamily {

    private HologramReplacementFamily() {}

    static Family resolve(IStructureElement<?> element, Block target, int targetMeta) {
        if (element == null || target == null || target.hasTileEntity(targetMeta)) return null;
        Family found = null;
        for (IStructureElement<?> part : HologramElementCatalog.parts(element)) {
            if (HologramIndustrialConstraints.lcrCoil(part) && target instanceof IHeatingCoil)
                return new Family("coil", null, true);
            Method method = part.getClass()
                .getEnclosingMethod();
            if (method == null) continue;
            Family candidate = null;
            if (method.getDeclaringClass() == GTStructureUtility.class && "ofCoil".equals(method.getName())
                && target instanceof IHeatingCoil) {
                candidate = new Family("coil", null, true);
            } else
                if (method.getDeclaringClass() == StructureUtility.class && "ofBlocksTiered".equals(method.getName())) {
                    candidate = tiered(part);
                } else
                    if (method.getDeclaringClass() == StructureUtility.class && "ofBlocksFlat".equals(method.getName()))
                        candidate = flat(part);
                    else if (method.getDeclaringClass() == StructureUtility.class
                        && "ofBlockAnyMeta".equals(method.getName())) candidate = wildcard(part);
            if (candidate == null || !candidate.contains(target, targetMeta)) continue;
            if (found != null && !found.id.equals(candidate.id)) return null;
            found = candidate;
        }
        return found;
    }

    private static Family flat(IStructureElement<?> part) {
        try {
            Object raw = HologramDeclarationWrappers.captured(part, "val$blocsMap");
            if (!(raw instanceof Map) || ((Map<?, ?>) raw).isEmpty() || ((Map<?, ?>) raw).size() > 128) return null;
            List<Pair<Block, Integer>> states = new ArrayList<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (!(entry.getKey() instanceof Block) || !(entry.getValue() instanceof Integer)) return null;
                states.add(Pair.of((Block) entry.getKey(), (Integer) entry.getValue()));
            }
            return registeredFamily("declared:", states);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    static Family wildcard(IStructureElement<?> part) {
        try {
            Object raw = HologramDeclarationWrappers.captured(part, "val$block");
            if (!(raw instanceof Block)) return null;
            Block block = (Block) raw;
            Item item = Item.getItemFromBlock(block);
            if (!(item instanceof ItemBlock)) return null;
            List<Pair<Block, Integer>> states = new ArrayList<>();
            List<ItemStack> targets = new ArrayList<>();
            // The declaration accepts every world metadata nibble. Only the server placement
            // metadata mapping grants an inventory variant; creative enumeration is client-only.
            for (int damage = 0; damage < 16; damage++) {
                int meta = item.getMetadata(damage);
                if (meta != damage || block.hasTileEntity(meta)) continue;
                Pair<Block, Integer> state = Pair.of(block, meta);
                if (!states.contains(state)) states.add(state);
                targets.add(new ItemStack(item, 1, damage));
            }
            Family family = registeredFamily("wildcard:", states);
            if (family != null) family.registeredTargets = targets;
            return family;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Family registeredFamily(String prefix, List<Pair<Block, Integer>> states) {
        if (states.isEmpty()) return null;
        List<String> keys = new ArrayList<>();
        for (Pair<Block, Integer> state : states) {
            Block block = state.getLeft();
            int meta = state.getRight();
            if (meta < 0 || meta > 15 || block.hasTileEntity(meta)) return null;
            String identity = HologramBlockIdentity.key(block, meta);
            if (identity.isEmpty()) return null;
            keys.add(identity);
        }
        java.util.Collections.sort(keys);
        return new Family(prefix + String.join(";", keys) + ";", states, false);
    }

    private static Family tiered(IStructureElement<?> part) {
        try {
            Field field = part.getClass()
                .getDeclaredField("val$hints");
            if (!field.isSynthetic() || !List.class.isAssignableFrom(field.getType())) return null;
            field.setAccessible(true);
            List<?> raw = (List<?>) field.get(part);
            if (raw == null || raw.isEmpty() || raw.size() > 256) return null;
            List<Pair<Block, Integer>> states = new ArrayList<>();
            boolean glasses = true, frames = true;
            StringBuilder identity = new StringBuilder();
            for (Object value : raw) {
                if (!(value instanceof Pair)) return null;
                Pair<?, ?> pair = (Pair<?, ?>) value;
                if (!(pair.getLeft() instanceof Block) || !(pair.getRight() instanceof Integer)) return null;
                Block block = (Block) pair.getLeft();
                int meta = (Integer) pair.getRight();
                if (meta < 0 || block.hasTileEntity(meta)) return null;
                if (block instanceof BlockFrameBox) {
                    if (meta > 0xFFF) return null;
                } else if (meta > 15) return null;
                frames &= block instanceof BlockFrameBox;
                if (block instanceof BlockFrameBox) glasses = false;
                else glasses &= GlassTier.getGlassBlockTier(block, meta) != null;
                states.add(Pair.of(block, meta));
                identity.append(Block.blockRegistry.getNameForObject(block))
                    .append('@')
                    .append(meta)
                    .append(';');
            }
            return new Family((frames ? "frame:" : glasses ? "glass:" : "tiered:") + identity, states, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    static final class Family {

        final String id;
        private final List<Pair<Block, Integer>> states;
        private final boolean coil;
        private List<ItemStack> registeredTargets;

        Family(String id, List<Pair<Block, Integer>> states, boolean coil) {
            this.id = id;
            this.states = states;
            this.coil = coil;
        }

        boolean contains(Block block, int meta) {
            if (block == null || block.hasTileEntity(meta)) return false;
            if (coil) return block instanceof IHeatingCoil
                && ((IHeatingCoil) block).getCoilHeat(meta) != gregtech.api.enums.HeatingCoilLevel.None;
            for (Pair<Block, Integer> state : states)
                if (state.getLeft() == block && state.getRight() == meta) return true;
            return false;
        }

        boolean canTarget(ItemStack stack) {
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) return false;
            ItemBlock item = (ItemBlock) stack.getItem();
            return contains(Block.getBlockFromItem(item), item.getMetadata(stack.getItemDamage()));
        }

        /** Registered coil blocks expose their legal world states through the server IHeatingCoil contract. */
        List<ItemStack> targets() {
            List<ItemStack> result = new ArrayList<>();
            if (registeredTargets != null) {
                for (ItemStack stack : registeredTargets) result.add(stack.copy());
                return result;
            }
            if (!coil) {
                for (Pair<Block, Integer> state : states) {
                    ItemStack stack = new ItemStack(state.getLeft(), 1, state.getRight());
                    if (stack.getItem() != null) result.add(stack);
                }
                return result;
            }
            for (Object registered : Block.blockRegistry) {
                if (!(registered instanceof IHeatingCoil) || !(registered instanceof Block)) continue;
                Block block = (Block) registered;
                Item item = Item.getItemFromBlock(block);
                if (!(item instanceof ItemBlock)) continue;
                for (int meta = 0; meta < 16; meta++) {
                    ItemStack clean = new ItemStack(item, 1, meta);
                    if (item.getMetadata(meta) != meta || !canTarget(clean)) continue;
                    boolean duplicate = false;
                    for (ItemStack old : result) if (old.isItemEqual(clean)) duplicate = true;
                    if (!duplicate && result.size() < 512) result.add(clean);
                }
            }
            return result;
        }
    }
}
