// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Read-only comparison of stock EBF predicates after and before construction on separately owned in-memory tiles. */
public final class HologramEbfNativeCandidateContextWitness {

    private static final int ANCHOR_Y = 128;

    private HologramEbfNativeCandidateContextWitness() {}

    public static Map<String, Object> measure(ItemStack controller, int controllerMeta, ExtendedFacing facing,
        NBTTagCompound settings,
        int dx, int dy, int dz, ItemStack nativeTarget, List<ItemStack> displayedOptions) throws java.io.IOException {
        require(dy == 3, "Expected a stock EBF top output row");
        require(ItemMachines.getMetaTileEntity(nativeTarget) instanceof MTEHatchOutput,
            "Expected a real native output hatch target");
        BaseMetaTileEntity used = detached(controller, controllerMeta, facing);
        BaseMetaTileEntity pristine = detached(controller, controllerMeta, facing);
        MTEElectricBlastFurnace usedMachine = (MTEElectricBlastFurnace) used.getMetaTileEntity();
        MTEElectricBlastFurnace pristineMachine = (MTEElectricBlastFurnace) pristine.getMetaTileEntity();
        require(used != pristine && usedMachine != pristineMachine && used.getWorldObj() != pristine.getWorldObj(),
            "A/B must not share a tile, machine or world");
        ItemStack trigger = HologramLocalControllerCapture.previewTrigger(null, settings);
        ChannelDataAccessor.setChannelData(trigger, "gt_hatch", 1);
        long freshCount = HatchElement.OutputHatch.count(pristineMachine);
        long initialUsedCount = HatchElement.OutputHatch.count(usedMachine);
        require(freshCount == 0 && initialUsedCount == 0, "Detached EBF starts with no output hatches");

        HologramCapture.Cell detail;
        try (HologramNativeStructurePreview preview = new HologramNativeStructurePreview(
            used, usedMachine, usedMachine, false, trigger.copy())) {
            boolean done = false;
            for (int i = 0; i < 32 && !done; i++) done = preview.advance(System.nanoTime() + 10_000_000L);
            require(done, "The separately owned EBF preview did not converge");
            detail = preview.candidateCell(dx, ANCHOR_Y + dy, dz, "hatch");
        }
        long usedCount = HatchElement.OutputHatch.count(usedMachine);
        require(detail.element instanceof IStructureElementChain<?>, "Expected the stock output/casing chain");
        require(used.getWorldObj().getTileEntity(0, ANCHOR_Y, 0) == used
            && pristine.getWorldObj().getTileEntity(0, ANCHOR_Y, 0) == pristine
            && used.getWorldObj().getBlock(dx, ANCHOR_Y + dy, dz) == Blocks.air
            && pristine.getWorldObj().getBlock(dx, ANCHOR_Y + dy, dz) == Blocks.air,
            "A/B must query equivalent empty declaration worlds, each owning its own anchor");
        Predicate<ItemStack> post = predicate(detail.element, usedMachine, used, trigger, dx, dy, dz);
        Predicate<ItemStack> pre = predicate(detail.element, pristineMachine, pristine, trigger, dx, dy, dz);
        ItemStack casing = new ItemStack(GregTechAPI.sBlockCasings1, 1, 11);
        ItemStack alternate = alternate(pre, nativeTarget);
        boolean freshNative = accepts(pre, nativeTarget), usedNative = accepts(post, nativeTarget);
        boolean freshAlternate = accepts(pre, alternate), usedAlternate = accepts(post, alternate);
        boolean casingBoth = accepts(pre, casing) && accepts(post, casing);
        boolean controllerRejected = !accepts(pre, controller) && !accepts(post, controller);
        boolean sameCountsAfterReads = usedCount == HatchElement.OutputHatch.count(usedMachine)
            && freshCount == HatchElement.OutputHatch.count(pristineMachine);

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("relative", new int[] { dx, dy, dz });
        proof.put("facing", facing.toString());
        proof.put("trigger", trigger.writeToNBT(new NBTTagCompound()).toString());
        proof.put("nativeTarget", nativeTarget.writeToNBT(new NBTTagCompound()).toString());
        proof.put("alternate", alternate == null ? null : alternate.writeToNBT(new NBTTagCompound()).toString());
        proof.put("freshNativeAccepted", freshNative);
        proof.put("usedNativeAccepted", usedNative);
        proof.put("freshAlternateAccepted", freshAlternate);
        proof.put("usedAlternateAccepted", usedAlternate);
        proof.put("casingAcceptedByBoth", casingBoth);
        proof.put("controllerRejectedByBoth", controllerRejected);
        proof.put("countsUnchangedByRead", sameCountsAfterReads);
        proof.put("displayedCasingIndex", exactIndex(displayedOptions, casing));
        proof.put("displayedFreshNativeIndex", exactIndex(displayedOptions, nativeTarget));
        proof.put("displayedFreshAlternateIndex", exactIndex(displayedOptions, alternate));
        proof.put("pristineCount", freshCount);
        proof.put("usedCount", usedCount);
        proof.put("normalUsedCandidateDamage", detail.candidates == null ? Collections.emptyList()
            : detail.candidates.stream().map(ItemStack::getItemDamage).collect(java.util.stream.Collectors.toList()));
        proof.put("demonstrated", usedCount > 0 && freshNative && !usedNative && freshAlternate && !usedAlternate
            && casingBoth && controllerRejected && sameCountsAfterReads);
        return proof;
    }

    private static BaseMetaTileEntity detached(ItemStack controller, int meta, ExtendedFacing facing) {
        IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(controller);
        require(prototype != null && prototype.getClass() == MTEElectricBlastFurnace.class
            && !(prototype instanceof INEIPreviewModifier), "Only the unmodified stock EBF may be probed");
        HologramResourceWorld world = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, meta);
        TileEntity tile = GregTechAPI.sBlockMachines.createTileEntity(world, meta);
        require(tile instanceof BaseMetaTileEntity, "Missing detached EBF tile");
        BaseMetaTileEntity base = (BaseMetaTileEntity) tile;
        require(base.getWorldObj() == null || base.getWorldObj() == world, "Unexpected factory-owned world");
        base.setWorldObj(world);
        base.xCoord = base.zCoord = 0;
        base.yCoord = ANCHOR_Y;
        base.blockMetadata = meta;
        world.tile = base;
        base.setInitialValuesAsNBT(null, (short) controller.getItemDamage());
        IMetaTileEntity machine = base.getMetaTileEntity();
        require(machine != prototype && machine != null && machine.getClass() == MTEElectricBlastFurnace.class
            && !(machine instanceof INEIPreviewModifier) && base.getWorldObj() == world,
            "EBF must have an independent known implementation with no preview hook");
        if (base.getFrontFacing() != facing.getDirection()) base.setFrontFacing(facing.getDirection());
        MTEElectricBlastFurnace furnace = (MTEElectricBlastFurnace) machine;
        require(furnace.getExtendedFacing() == facing || furnace.checkedSetExtendedFacing(facing),
            "Cannot match the stock test controller's alignment");
        return base;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Predicate<ItemStack> predicate(IStructureElement element, MTEElectricBlastFurnace machine,
        BaseMetaTileEntity base, ItemStack trigger, int dx, int dy, int dz) {
        // The pinned stock EBF top HatchElementBuilder and casing factory do not use an actor. No live context is used.
        AutoPlaceEnvironment env = AutoPlaceEnvironment.fromLegacy(new HologramNativePreviewItems(), null, msg -> {});
        IStructureElement.BlocksToPlace blocks = element.getBlocksToPlace(
            machine, base.getWorldObj(), dx, ANCHOR_Y + dy, dz, trigger.copy(), env);
        require(blocks != null && blocks.getPredicate() != null, "Missing native output predicate");
        return blocks.getPredicate();
    }

    private static ItemStack alternate(Predicate<ItemStack> predicate, ItemStack nativeTarget) {
        int tier = HologramCellPolicy.hatchTier(nativeTarget);
        for (IMetaTileEntity machine : GregTechAPI.METATILEENTITIES) {
            if (!(machine instanceof MTEHatchOutput output) || output.mTier == tier) continue;
            ItemStack alternate = machine.getStackForm(1);
            if (accepts(predicate, alternate) && !ItemStack.areItemStacksEqual(nativeTarget, alternate))
                return alternate.copy();
        }
        return null;
    }

    private static int exactIndex(List<ItemStack> options, ItemStack target) {
        if (target == null) return -1;
        for (int i = 0; i < options.size(); i++) if (ItemStack.areItemStacksEqual(options.get(i), target)) return i;
        return -1;
    }

    private static boolean accepts(Predicate<ItemStack> predicate, ItemStack sample) {
        return sample != null && predicate.test(sample.copy());
    }

    private static void require(boolean condition, String problem) {
        if (!condition) throw new IllegalStateException(problem);
    }
}
