package com.miaokatze.gtmb.mixin.hologram;

import java.util.function.BiConsumer;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.hologram.HologramGregTechDeclarations;

/** Record factory provenance without forcing lazy declaration initialization during mod registration. */
@Mixin(targets = "gregtech.api.util.GTStructureUtility", remap = false)
public abstract class MixinHologramGregTechDeclarations {

    @Inject(
        method = "chainAllGlasses(ILjava/util/function/BiConsumer;Ljava/util/function/Function;)Lcom/gtnewhorizon/structurelib/structure/IStructureElement;",
        at = @At("RETURN"),
        remap = false)
    private static void gtmb$glass(int unset, BiConsumer<?, ?> setter, Function<?, ?> getter,
        CallbackInfoReturnable<IStructureElement<?>> cir) {
        HologramGregTechDeclarations.glass(cir.getReturnValue());
    }

    @Inject(
        method = "chainAllCasings(ILjava/util/function/BiConsumer;Ljava/util/function/Function;)Lcom/gtnewhorizon/structurelib/structure/IStructureElement;",
        at = @At("RETURN"),
        remap = false)
    private static void gtmb$casings(int unset, BiConsumer<?, ?> setter, Function<?, ?> getter,
        CallbackInfoReturnable<IStructureElement<?>> cir) {
        HologramGregTechDeclarations.casings(cir.getReturnValue());
    }

    @Inject(
        method = "chainItemPipeCasings(ILjava/util/function/BiConsumer;Ljava/util/function/Function;)Lcom/gtnewhorizon/structurelib/structure/IStructureElement;",
        at = @At("RETURN"),
        remap = false)
    private static void gtmb$itemPipes(int unset, BiConsumer<?, ?> setter, Function<?, ?> getter,
        CallbackInfoReturnable<IStructureElement<?>> cir) {
        HologramGregTechDeclarations.itemPipes(cir.getReturnValue());
    }
}
