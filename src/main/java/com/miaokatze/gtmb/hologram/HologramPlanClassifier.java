package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import gregtech.common.blocks.ItemMachines;

/**
 * A task-owned proof for empty ordinary vanilla cells. Position/world/permission observations are
 * always made for the current cell; only audited metadata-to-material results survive across cells.
 */
final class HologramPlanClassifier {

    private final HologramBlueprintValidationCache validation;
    private final HologramBlueprintResources.Plan emptyResources = new HologramBlueprintResources.Plan();
    long ordinaryCells, fallbackCells;

    HologramPlanClassifier(HologramSession s) {
        validation = s.virtual && s.virtualPreset != null ? new HologramBlueprintValidationCache(s.virtualPreset)
            : null;
    }

    long damageMisses() {
        return validation == null ? 0 : validation.damageMisses;
    }

    long damageHits() {
        return validation == null ? 0 : validation.damageHits;
    }

    private ItemStack target(HologramSession s, HologramCapture.Cell cell, int index) {
        return s.localPlanPending ? HologramCellPolicy.draftTarget(s, cell, index)
            : index < s.planTargets.size() ? s.planTargets.get(index) : HologramCellPolicy.draftTarget(s, cell, index);
    }

    boolean emptyOrdinary(HologramSession s, HologramCapture.Cell cell, int index) {
        return emptyOrdinary(s, cell, index, target(s, cell, index));
    }

    private boolean emptyOrdinary(HologramSession s, HologramCapture.Cell cell, int index, ItemStack target) {
        return s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && s.mode == 0
            && !cell.anchor
            && !"templateController".equals(cell.role)
            && s.pins.containsKey(index)
            && cell.tileNbt.isEmpty()
            && cell.itemNbt.isEmpty()
            && target != null
            && target.stackSize > 0
            && !cell.invalidCoverCosts
            && cell.resourceError.isEmpty()
            && !target.hasTagCompound()
            && target.getItem() instanceof ItemBlock
            && !(target.getItem() instanceof ItemMachines)
            && target.getItemDamage() >= 0
            && HologramBlueprintValidationCache.vanillaMaterial(cell.block)
            && HologramBlueprintValidationCache.vanillaMaterial(Block.getBlockFromItem(target.getItem()))
            && target.getItem() == Item.getItemFromBlock(Block.getBlockFromItem(target.getItem()))
            && !HologramNativePlacement.supported(cell.block)
            && (cell.block == Block.getBlockFromItem(target.getItem())
                || !HologramNativePlacement.supported(Block.getBlockFromItem(target.getItem())))
            && !s.facingPins.containsKey(HologramValues.position(cell))
            && (s.areaStructureId.isEmpty() || cell.structureTargets != null
                || cell.memberships()
                    .isEmpty());
    }

    HologramBlueprintResources.Plan resources(HologramSession s, HologramCapture.Cell cell, int index)
        throws IOException {
        // This proof intentionally excludes every nonempty tile/item encoding, even if it looks like config.
        return emptyOrdinary(s, cell, index) ? emptyResources : HologramCellPolicy.resourcePlan(s, cell);
    }

    String classify(HologramSession s, HologramCapture.Cell cell, int index, boolean loaded, Block actual,
        int actualMeta, TileEntity actualTile) {
        if (cell.y < 0 || cell.y >= s.player.worldObj.getHeight() || !loaded) return "unsupported";
        if (cell.anchor) return "protected";
        ItemStack target = target(s, cell, index);
        if (actualTile != null || !emptyOrdinary(s, cell, index, target)) {
            fallbackCells++;
            return HologramPlanPolicy.classify(s, cell, index);
        }
        ordinaryCells++;
        if (cell.structureTargets != null) {
            cell.candidateComplete = false;
            cell.candidateBoundary = "仅提供本格保存的结构声明候选；共享格须同时符合各成员结构";
            boolean accepted = false;
            for (ItemStack candidate : cell.structureTargets)
                if (HologramValues.sameMaterial(candidate, target) && candidate.stackSize > 0) {
                    accepted = true;
                    break;
                }
            if (!accepted) return "unsupported";
        }
        int previewMeta;
        Block previewBlock;
        try {
            if (!cell.blueprintItem.isEmpty()) {
                ItemStack original = validation.stack(cell.blueprintItem, cell.blueprintDamage);
                if (original != null && original.getItem() instanceof ItemBlock
                    && !(original.getItem() instanceof ItemMachines))
                    validation.validateMaterialVariant(cell.block, cell.meta, original);
            }
            boolean sameVariant;
            try {
                validation.validateMaterialVariant(cell.block, cell.meta, target);
                sameVariant = true;
            } catch (IOException differentVariant) {
                sameVariant = false;
            }
            previewMeta = sameVariant ? cell.meta
                : target.getItem()
                    .getMetadata(target.getItemDamage());
            previewBlock = sameVariant ? cell.block : Block.getBlockFromItem(target.getItem());
            validation.validateMaterialVariant(previewBlock, previewMeta, target);
            cell.resourceError = "";
        } catch (IOException invalid) {
            cell.resourceError = invalid.getMessage();
            return "unsupported";
        }
        boolean matches = actual == previewBlock && actualMeta == previewMeta
            && validationDamageMatches(actual, actualMeta, target);
        World world = s.player.worldObj;
        boolean empty = !s.player.capabilities.isCreativeMode
            && HologramCellPolicy.trivial(world, cell.x, cell.y, cell.z);
        if (!s.player.capabilities.isCreativeMode && !empty && !matches) return "blocked";
        if (matches) return "satisfied";
        // Permission callbacks remain per position and receive the current tool/channels.
        if (!world.canMineBlock(s.player, cell.x, cell.y, cell.z)
            || !s.player.canPlayerEdit(cell.x, cell.y, cell.z, 1, HologramSessionPolicy.trigger(s))) return "protected";
        if (empty || s.player.capabilities.isCreativeMode && HologramCellPolicy.trivial(world, cell.x, cell.y, cell.z))
            return "pending";
        if (!s.player.capabilities.isCreativeMode) return "blocked";
        return HologramCellPolicy.removable(s, cell) ? "pending" : "protected";
    }

    private boolean validationDamageMatches(Block actual, int metadata, ItemStack paid) {
        try {
            // The exact vanilla classes on this path read only metadata. Third-party subclasses never enter.
            return validation.materialDamage(actual, metadata) == paid.getItemDamage();
        } catch (IOException invalid) {
            return false;
        }
    }
}
