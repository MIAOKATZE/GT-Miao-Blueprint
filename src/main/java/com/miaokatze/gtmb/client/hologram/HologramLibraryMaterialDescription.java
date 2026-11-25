// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockStone;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import com.github.bsideup.jabel.Desugar;

/** Captured blueprint descriptions and local inventory observations, not a quote or a material-readiness check. */
final class HologramLibraryMaterialDescription {

    private static final String STONE = "minecraft:stone";

    private HologramLibraryMaterialDescription() {}

    @Desugar
    record Row(HologramLibraryMaterialForecast.Identity identity, long blueprintCells, Long visibleBackpackCount) {}

    @Desugar
    record Description(List<Row> rows, long unlistedCells, int airCells) {

        Description {
            rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }
    }

    /**
     * On the client thread, observe the current owner's main inventory only. Null count means unknown, including
     * unrecognized identities or an unavailable inventory/slot. A null forecast means no captured description.
     */
    static Description describe(HologramLibraryMaterialForecast forecast, ItemStack[] mainInventory,
        int projectorSlot) {
        if (forecast == null) return null;
        List<Row> rows = new ArrayList<>(
            forecast.candidates()
                .size());
        for (HologramLibraryMaterialForecast.Need need : forecast.candidates()) {
            var identity = need.identity();
            Long observed = visiblePlain(identity, mainInventory, projectorSlot);
            rows.add(new Row(identity, need.cells(), observed));
        }
        return new Description(
            rows,
            forecast.unpriced()
                .cells(),
            forecast.airCells());
    }

    private static Long visiblePlain(HologramLibraryMaterialForecast.Identity identity, ItemStack[] mainInventory,
        int projectorSlot) {
        if (identity.damage() != 0) return null;
        String registry = identity.registry();
        Block block = switch (registry) {
            case "minecraft:stone" -> Blocks.stone;
            case "minecraft:cobblestone" -> Blocks.cobblestone;
            case "minecraft:glass" -> Blocks.glass;
            case "minecraft:stonebrick" -> Blocks.stonebrick;
            case "minecraft:quartz_block" -> Blocks.quartz_block;
            case "minecraft:glowstone" -> Blocks.glowstone;
            default -> null;
        };
        if (mainInventory == null || projectorSlot < 0
            || projectorSlot >= InventoryPlayer.getHotbarSize()
            || projectorSlot >= mainInventory.length) return null;
        if (block == null || STONE.equals(registry) && block.getClass() != BlockStone.class
            || Block.blockRegistry.getObject(registry) != block
            || !registry.equals(Block.blockRegistry.getNameForObject(block))) return null;
        Item item = Item.getItemFromBlock(block);
        if (item == null || !(item.getClass() == ItemBlock.class
            || (block == Blocks.stonebrick || block == Blocks.quartz_block)
                && item.getClass() == net.minecraft.item.ItemMultiTexture.class)
            || Item.itemRegistry.getObject(registry) != item
            || !registry.equals(Item.itemRegistry.getNameForObject(item))
            || Block.getBlockFromItem(item) != block) return null;
        ItemStack sample = new ItemStack(item, 1, 0);
        if (sample.getItem() != item) return null; // Do not count against a changed Forge delegate.
        long count = 0;
        for (int i = 0; i < mainInventory.length; i++) {
            ItemStack stack = mainInventory[i];
            // isItemEqual compares the stored item and raw damage, not the overridable Item#getDamage callback.
            if (i != projectorSlot && stack != null
                && stack.stackSize > 0
                && stack.getItem() == item
                && !stack.hasTagCompound()
                && stack.isItemEqual(sample)) count += stack.stackSize;
        }
        return count;
    }
}
