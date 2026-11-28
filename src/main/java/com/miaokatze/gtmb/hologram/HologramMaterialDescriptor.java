// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Optional planned projections; actual world samples always use the block's native damage getter. */
public final class HologramMaterialDescriptor {

    private static final Map<Class<?>, Map<Class<?>, ExactRule>> EXACT = new HashMap<>();
    private static long generation;

    static {
        HologramMaterialDescriptorAdapters.register();
    }

    private HologramMaterialDescriptor() {}

    /** Internal bridge for the scoped public API. Matching never calls a pick implementation or a tile factory. */
    public static AutoCloseable registerExact(Class<? extends Block> blockType, Class<? extends Item> itemType,
        Class<? extends Block> nativePickDeclaration, IntUnaryOperator plannedDamage) {
        return registerExact(blockType, itemType, nativePickDeclaration, plannedDamage, null);
    }

    /** Actual readers belong only to built-in reviewed material types, never to the public planned projection. */
    static AutoCloseable registerExact(Class<? extends Block> blockType, Class<? extends Item> itemType,
        Class<? extends Block> nativePickDeclaration, IntUnaryOperator plannedDamage, ActualDamage actualDamage) {
        if (blockType == null || itemType == null || nativePickDeclaration == null || plannedDamage == null)
            throw new IllegalArgumentException("Complete material identity and projection required");
        ExactRule rule = new ExactRule(nativePickDeclaration, plannedDamage, actualDamage);
        synchronized (HologramMaterialDescriptor.class) {
            Map<Class<?>, ExactRule> items = EXACT.computeIfAbsent(blockType, ignored -> new HashMap<>());
            if (items.putIfAbsent(itemType, rule) != null)
                throw new IllegalArgumentException("Material identity already registered");
            generation++;
        }
        return () -> {
            synchronized (HologramMaterialDescriptor.class) {
                Map<Class<?>, ExactRule> items = EXACT.get(blockType);
                if (items != null && items.remove(itemType, rule)) {
                    if (items.isEmpty()) EXACT.remove(blockType);
                    generation++;
                }
            }
        };
    }

    /** Include in caches whose validation depends on registered planned material projections. */
    public static synchronized long generation() {
        return generation;
    }

    static synchronized boolean hasExact(Block block) {
        return block != null && EXACT.containsKey(block.getClass());
    }

    static synchronized int kind(Block block) {
        if (block == null) return 0;
        if (exactRule(block) != null) return 3;
        return 0; // No declaring-class whitelist stands between a block and its public native getter.
    }

    static int damage(Block block, int metadata) {
        ExactRule exact;
        synchronized (HologramMaterialDescriptor.class) {
            exact = exactRule(block);
        }
        if (exact != null) return exact.plannedDamage.applyAsInt(metadata);
        return block.getDamageValue(HologramResourceWorld.create(block, metadata), 0, 64, 0);
    }

    /** A source-backed actual value for read-only snapshots, or null when no audited native reader is available. */
    static Integer actualDamage(Block block, int metadata, TileEntity tile) {
        if (block == null) return null;
        ExactRule exact;
        synchronized (HologramMaterialDescriptor.class) {
            exact = exactRule(block);
        }
        if (exact != null) return exact.actualDamage == null ? null : exact.actualDamage.read(block, metadata, tile);
        return tile == null || tile.getWorldObj() == null ? null
            : actualDamage(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
    }

    static Integer actualDamage(World world, int x, int y, int z) {
        if (world == null || !world.blockExists(x, y, z)) return null;
        try {
            int value = world.getBlock(x, y, z)
                .getDamageValue(world, x, y, z);
            return value < 0 ? null : value;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static ExactRule exactRule(Block block) {
        Map<Class<?>, ExactRule> items = EXACT.get(block.getClass());
        if (items == null) return null;
        Item item = Item.getItemFromBlock(block);
        ExactRule rule = item == null ? null : items.get(item.getClass());
        return rule;
    }

    @FunctionalInterface
    interface ActualDamage {

        int read(Block block, int metadata, TileEntity tile);
    }

    private static final class ExactRule {

        final Class<?> nativePickDeclaration;
        final IntUnaryOperator plannedDamage;
        final ActualDamage actualDamage;

        ExactRule(Class<?> nativePickDeclaration, IntUnaryOperator plannedDamage, ActualDamage actualDamage) {
            this.nativePickDeclaration = nativePickDeclaration;
            this.plannedDamage = plannedDamage;
            this.actualDamage = actualDamage;
        }
    }

}
