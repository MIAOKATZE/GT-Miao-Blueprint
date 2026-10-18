package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import gregtech.common.blocks.ItemMachines;

/** Literal captured blocks and advisory structure editing candidates are separate identities. */
final class HologramBlueprintSourceMaterial {

    private HologramBlueprintSourceMaterial() {}

    static boolean plainReplay(HologramSession session, HologramCapture.Cell cell, ItemStack sample) {
        return plainReplay(session, cell, sample, null);
    }

    static boolean plainReplay(HologramSession session, HologramCapture.Cell cell, ItemStack sample,
        HologramBlueprintValidationCache validation) {
        if (!session.virtual || session.virtualPreset == null
            || session.virtualPreset.schema < 2
            || cell.anchor
            || cell.unknown
            || "templateController".equals(cell.role)
            || sample == null
            || sample.getItem() == null
            || sample.stackSize < 1
            || !(sample.getItem() instanceof ItemBlock)
            || sample.getItem() instanceof ItemMachines
            || sample.hasTagCompound()
            || !cell.tileNbt.isEmpty()
            || !cell.itemNbt.isEmpty()
            || cell.block == null
            || cell.block == Blocks.air
            || cell.blueprintDamage != sample.getItemDamage()
            || !cell.blueprintItem.equals(Item.itemRegistry.getNameForObject(sample.getItem()))
            || !cell.blueprintBlock.equals(Block.blockRegistry.getNameForObject(cell.block))) return false;
        try {
            if (cell.block.hasTileEntity(cell.meta) || HologramNativePlacement.supported(cell.block)) return false;
            // The native inventory variant must still pay for this exact saved block state. In particular,
            // matching a registry name alone never authorizes another casing tier or frame material.
            HologramPresets.validateMaterialVariant(cell.block, cell.meta, sample, validation);
            return true;
        } catch (IOException | RuntimeException | LinkageError invalidSource) {
            return false;
        }
    }
}
