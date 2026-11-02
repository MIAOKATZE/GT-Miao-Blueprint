package com.miaokatze.gtmb.mixin.hologram;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.miaokatze.gtmb.hologram.HologramCapture;

/** Interface mixin: StructureLib declares this implementation as a Java 8 default method. */
@Mixin(value = IStructureDefinition.class, remap = false)
public interface MixinHologramBuildOrHints {

    @Inject(
        method = "buildOrHints(Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Ljava/lang/String;Lnet/minecraft/world/World;Lcom/gtnewhorizon/structurelib/alignment/enumerable/ExtendedFacing;IIIIIIZ)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    default void gtmb$capture(Object controller, ItemStack trigger, String piece, World world, ExtendedFacing facing,
        int x, int y, int z, int a, int b, int c, boolean hints, CallbackInfoReturnable<Boolean> cir) {
        if (HologramCapture.piece(
            (IStructureDefinition<?>) (Object) this,
            controller,
            trigger,
            piece,
            world,
            facing,
            x,
            y,
            z,
            a,
            b,
            c,
            hints)) cir.setReturnValue(true);
    }
}
