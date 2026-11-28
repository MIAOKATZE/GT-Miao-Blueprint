// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.mixin.hologram;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtmb.client.hologram.HologramClient;

/** Native post-packet hook pattern verified against GTNL ea917993f634158bc65945b7a58d69257936712c. */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinHologramChunkNotifications {

    @Shadow
    private WorldClient clientWorldController;

    @Inject(
        method = "handleChunkData(Lnet/minecraft/network/play/server/S21PacketChunkData;)V",
        at = @At("RETURN"),
        require = 1)
    private void gtmb$chunkReceived(S21PacketChunkData packet, CallbackInfo callback) {
        HologramClient.nativeChunkReceived((NetHandlerPlayClient) (Object) this, clientWorldController, packet);
    }

    @Inject(
        method = "handleMapChunkBulk(Lnet/minecraft/network/play/server/S26PacketMapChunkBulk;)V",
        at = @At("TAIL"),
        require = 1)
    private void gtmb$chunksReceived(S26PacketMapChunkBulk packet, CallbackInfo callback) {
        HologramClient.nativeChunksReceived((NetHandlerPlayClient) (Object) this, clientWorldController, packet);
    }
}
