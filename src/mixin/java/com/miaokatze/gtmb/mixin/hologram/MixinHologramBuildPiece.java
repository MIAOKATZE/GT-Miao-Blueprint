// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.mixin.hologram;

import java.util.List;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtmb.hologram.HologramCapture;
import com.miaokatze.gtmb.hologram.HologramNativeSiteCapture;

@Mixin(targets = "gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase", remap = false)
public abstract class MixinHologramBuildPiece {

    @Inject(method = "checkPiece(Ljava/lang/String;III)Z", at = @At("HEAD"), remap = false)
    private void gtmb$sitePieceHead(String piece, int a, int b, int c, CallbackInfoReturnable<Boolean> cir) {
        HologramNativeSiteCapture.pieceHead(this, piece, a, b, c);
    }

    @Inject(method = "checkPiece(Ljava/lang/String;III)Z", at = @At("RETURN"), remap = false)
    private void gtmb$sitePieceReturn(String piece, int a, int b, int c, CallbackInfoReturnable<Boolean> cir) {
        HologramNativeSiteCapture.pieceReturn(this, piece, a, b, c, cir.getReturnValueZ());
    }

    @Inject(method = "checkPiece(Ljava/lang/String;IIILjava/util/List;)Z", at = @At("HEAD"), remap = false)
    private void gtmb$sitePieceErrorsHead(String piece, int a, int b, int c, List<?> errors,
        CallbackInfoReturnable<Boolean> cir) {
        HologramNativeSiteCapture.pieceHead(this, piece, a, b, c);
    }

    @Inject(method = "checkPiece(Ljava/lang/String;IIILjava/util/List;)Z", at = @At("RETURN"), remap = false)
    private void gtmb$sitePieceErrorsReturn(String piece, int a, int b, int c, List<?> errors,
        CallbackInfoReturnable<Boolean> cir) {
        HologramNativeSiteCapture.pieceReturn(this, piece, a, b, c, cir.getReturnValueZ());
    }

    @Inject(
        method = "buildPiece(Ljava/lang/String;Lnet/minecraft/item/ItemStack;ZIII)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private void gtit$capture(String name, ItemStack trigger, boolean hints, int a, int b, int c,
        CallbackInfoReturnable<Boolean> cir) {
        if (HologramCapture.piece(this, name, trigger, hints, a, b, c)) cir.setReturnValue(true);
    }
}
