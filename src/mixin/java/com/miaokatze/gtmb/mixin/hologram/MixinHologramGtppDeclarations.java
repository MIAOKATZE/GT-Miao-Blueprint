package com.miaokatze.gtmb.mixin.hologram;

import java.util.function.BiPredicate;
import java.util.function.Function;

import net.minecraft.block.Block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.hologram.HologramGregTechDeclarations;

/** Record the common GT++ factory's literal block/range, without retaining or executing its machine callbacks. */
@Mixin(targets = "gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.GTPPMultiBlockBase", remap = false)
public abstract class MixinHologramGtppDeclarations {

    @Inject(
        method = "addTieredBlock(Lnet/minecraft/block/Block;Ljava/util/function/BiPredicate;Ljava/util/function/Function;II)Lcom/gtnewhorizon/structurelib/structure/IStructureElement;",
        at = @At("RETURN"),
        require = 1,
        remap = false)
    private static void gtmb$tieredBlock(Block block, BiPredicate<?, ?> setter, Function<?, ?> getter, int minMeta,
        int maxMeta, CallbackInfoReturnable<IStructureElement<?>> cir) {
        HologramGregTechDeclarations.tieredBlock(cir.getReturnValue(), block, minMeta, maxMeta);
    }
}
