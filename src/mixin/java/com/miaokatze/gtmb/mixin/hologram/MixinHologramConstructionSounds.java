// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.mixin.hologram;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtmb.hologram.HologramConstructionSounds;

/** The native placement scope suppresses only its synchronous target cell's World sound dispatch. */
@Mixin(World.class)
public abstract class MixinHologramConstructionSounds {

    @Inject(method = "playSoundEffect(DDDLjava/lang/String;FF)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void gtmb$constructionSound(double x, double y, double z, String sound, float volume, float pitch,
        CallbackInfo callback) {
        if (HologramConstructionSounds.suppressed((World) (Object) this, x, y, z)) callback.cancel();
    }
}
