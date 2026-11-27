// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.mixin.hologram;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.miaokatze.gtmb.hologram.HologramMachineUpdateBatch;

@Mixin(targets = "gregtech.api.threads.RunnableMachineUpdate", remap = false)
public abstract class MixinHologramMachineUpdateBatch {

    @Inject(
        method = "setMachineUpdateValues(Lnet/minecraft/world/World;III)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private static void gtmb$coalesceSeed(World world, int x, int y, int z, CallbackInfo callback) {
        if (HologramMachineUpdateBatch.suppressDuplicate(world, x, y, z)) callback.cancel();
    }

    @WrapMethod(method = "endTick", remap = false)
    private static void gtmb$preserveDrainEvents(Operation<Void> original) {
        try (var drain = HologramMachineUpdateBatch.pauseForDrain()) {
            original.call();
        }
    }
}
