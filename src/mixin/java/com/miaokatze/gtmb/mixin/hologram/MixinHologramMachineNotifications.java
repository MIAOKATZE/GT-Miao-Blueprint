// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.mixin.hologram;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtmb.client.hologram.HologramClient;

import gregtech.api.metatileentity.GregTechTileClientEvents;

/** Receives native GT state after application, including its initial tile stream. Client configuration only. */
@Mixin(targets = "gregtech.api.metatileentity.BaseMetaTileEntity", remap = false)
public abstract class MixinHologramMachineNotifications {

    // This Minecraft override has an MCP name in development and an SRG name in runtime GT jars.
    @Inject(
        method = { "receiveClientEvent(II)Z", "func_145842_c(II)Z" },
        at = @At("RETURN"),
        remap = false,
        require = 1)
    private void gtmb$machineChanged(int event, int value, CallbackInfoReturnable<Boolean> callback) {
        if (event == GregTechTileClientEvents.CHANGE_COMMON_DATA || event == GregTechTileClientEvents.CHANGE_CUSTOM_DATA
            || event == GregTechTileClientEvents.CHANGE_COLOR)
            HologramClient.nativeTileChanged((TileEntity) (Object) this);
    }
}
