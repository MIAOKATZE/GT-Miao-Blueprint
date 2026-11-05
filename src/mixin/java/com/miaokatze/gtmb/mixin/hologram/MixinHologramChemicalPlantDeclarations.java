package com.miaokatze.gtmb.mixin.hologram;

import java.util.HashMap;

import net.minecraft.block.Block;

import org.apache.commons.lang3.tuple.Triple;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.hologram.HologramGtppChemicalDeclarations;

/** The native private factory is the provenance boundary; no controller class is guessed during traversal. */
@Mixin(targets = "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.chemplant.MTEChemicalPlant", remap = false)
public abstract class MixinHologramChemicalPlantDeclarations {

    @Shadow(remap = false)
    @Final
    private static HashMap<Integer, Triple<Block, Integer, Integer>> mTieredBlockRegistry;

    @ModifyArg(
        method = "getStructureDefinition",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/common/misc/GTStructureChannels;use(Lcom/gtnewhorizon/structurelib/structure/IStructureElement;)Lcom/gtnewhorizon/structurelib/structure/IStructureElement;",
            ordinal = 0),
        index = 0,
        require = 1,
        remap = false)
    private IStructureElement<?> gtmb$solidCasings(IStructureElement<?> alternatives) {
        HologramGtppChemicalDeclarations.solidCasings(alternatives, mTieredBlockRegistry);
        return alternatives;
    }
}
