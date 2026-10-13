package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.interfaces.IHeatingCoil;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor;

/** Read-only adapters for constraints verified in GT5U 5.09.54.205 machine checks. */
final class HologramIndustrialConstraints {

    private static final String LCR_COIL = MTELargeChemicalReactor.class.getName() + "$CoilStructureElement";

    private HologramIndustrialConstraints() {}

    static boolean lcrCoil(IStructureElement<?> element) {
        return element != null && element.getClass()
            .getName()
            .equals(LCR_COIL)
            && element.getClass()
                .isEnum();
    }

    static boolean coilSlot(IStructureElement<?> element) {
        for (IStructureElement<?> part : HologramElementCatalog.parts(element)) if (lcrCoil(part)) return true;
        return false;
    }

    static ItemStack coil(ItemStack trigger) {
        int tier = Math.min(HeatingCoilLevel.getMaxTier(), Math.max(0, trigger.stackSize - 1));
        return new ItemStack(
            GregTechAPI.sBlockCasings5,
            1,
            BlockCasings5.getMetaFromCoilHeat(HeatingCoilLevel.getFromTier((byte) tier)));
    }

    static boolean heatingCoil(ItemStack stack) {
        if (stack == null) return false;
        Block block = Block.getBlockFromItem(stack.getItem());
        return block instanceof IHeatingCoil && ((IHeatingCoil) block).getCoilHeat(
            stack.getItem()
                .getMetadata(stack.getItemDamage()))
            != HeatingCoilLevel.None;
    }

    /**
     * Hint chains display casings at every x; the real LCR check requires exactly one of those five slots to be a coil.
     */
    static void bind(HologramCapture capture, World world, Map<String, ItemStack> pins, boolean explicitMain) {
        if (!(capture.context instanceof MTELargeChemicalReactor) || capture.incomplete) return;
        List<HologramCapture.Cell> slots = new ArrayList<>();
        HologramCapture.Cell chosen = null;
        int existing = 0;
        for (HologramCapture.Cell cell : capture.cells) {
            if (!coilSlot(cell.element)) continue;
            slots.add(cell);
            Block actual = world.getBlock(cell.x, cell.y, cell.z);
            ItemStack explicit = pins.get(position(cell));
            boolean plannedCoil = explicit != null ? heatingCoil(explicit)
                : actual instanceof IHeatingCoil
                    && ((IHeatingCoil) actual).getCoilHeat(world.getBlockMetadata(cell.x, cell.y, cell.z))
                        != HeatingCoilLevel.None;
            if (plannedCoil) {
                chosen = cell;
                existing++;
            }
        }
        if (slots.isEmpty()) return;
        if (existing > 1) {
            capture.incomplete = true;
            capture.failure = "LCR_REQUIRES_EXACTLY_ONE_COIL";
            return;
        }
        if (chosen == null) for (HologramCapture.Cell cell : slots) {
            if (world.getTileEntity(cell.x, cell.y, cell.z) != null || pins.containsKey(position(cell))) continue;
            Block actual = world.getBlock(cell.x, cell.y, cell.z);
            if (actual == Blocks.air || !actual.hasTileEntity(world.getBlockMetadata(cell.x, cell.y, cell.z))) {
                chosen = cell;
                break;
            }
        }
        if (chosen == null) {
            capture.incomplete = true;
            capture.failure = "LCR_NO_FREE_COIL_POSITION";
            return;
        }
        ItemStack target = coil(ChannelDataAccessor.withChannel(capture.trigger, "coil"));
        Block installed = world.getBlock(chosen.x, chosen.y, chosen.z);
        boolean explicitCoil = capture.trigger.hasTagCompound() && capture.trigger.getTagCompound()
            .getCompoundTag("channels")
            .hasKey("coil", 3);
        if (!explicitMain && !explicitCoil && installed instanceof IHeatingCoil)
            target = new ItemStack(installed, 1, installed.getDamageValue(world, chosen.x, chosen.y, chosen.z));
        chosen.block = Block.getBlockFromItem(target.getItem());
        chosen.meta = target.getItemDamage();
        chosen.unknown = false;
        chosen.role = "block";
        chosen.family = "coil";
    }

    static int minimumCasings(Object context) {
        if (context != null && context.getClass() == MTELargeChemicalReactor.class) return 8;
        String type = context == null ? ""
            : context.getClass()
                .getName();
        // checkMachine/checkCasingMin in these exact upstream classes; other machines retain their declaration
        // boundary.
        if (type.equals("gregtech.common.tileentities.machines.multi.MTEIndustrialElectrolyzer")
            || type.equals("gregtech.common.tileentities.machines.multi.MTEIndustrialCentrifuge")) return 6;
        if (type.equals("gregtech.common.tileentities.machines.multi.MTEIndustrialExtractor")) return 45;
        return 0;
    }

    static boolean casing(HologramCapture.Cell cell, ItemStack target) {
        if (target == null || !"hatch".equals(cell.role)) return false;
        HologramCasingFallback.Target fallback = HologramCasingFallback.resolve(cell.element, cell.elementTrigger);
        return fallback != null && Block.getBlockFromItem(target.getItem()) == fallback.block
            && target.getItem()
                .getMetadata(target.getItemDamage()) == fallback.meta;
    }

    private static String position(HologramCapture.Cell cell) {
        return cell.x + "," + cell.y + "," + cell.z;
    }
}
