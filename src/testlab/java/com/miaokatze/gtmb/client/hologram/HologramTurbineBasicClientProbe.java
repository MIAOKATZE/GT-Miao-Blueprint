// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.world.chunk.EmptyChunk;

import com.miaokatze.gtmb.hologram.HologramLocalControllerCapture;
import com.miaokatze.gtmb.hologram.HologramTurbineStockWorldProbe;
import com.miaokatze.gtmb.hologram.HologramTurbineStockWorldProbe.BasicSite;

import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Reads the workbench's normal sparse capture API on the real connected WorldClient, not a fabricated client world. */
final class HologramTurbineBasicClientProbe {

    private BasicSite site;
    private HologramLocalControllerCapture capture;
    private int originalDistance = -1;
    private boolean published;

    void step(Minecraft mc, HologramTurbineStockWorldProbe server) throws Exception {
        BasicSite request = server.basicSite();
        if (request == null) return;
        if (mc.theWorld == null || !mc.theWorld.isRemote
            || mc.thePlayer == null
            || !request.actor()
                .equals(mc.thePlayer.getUniqueID()))
            throw new IllegalStateException("Basic is not on the matching live WorldClient");
        if (site != request) {
            if (capture != null) capture.close();
            capture = null;
            published = false;
            site = request;
        }
        if (published || mc.thePlayer.capabilities.isCreativeMode != (request.phase() == 1)) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !held.hasTagCompound()
            || !request.tool()
                .toString()
                .equals(
                    held.getTagCompound()
                        .getString("gtitHologramToolId")))
            return;
        if (originalDistance < 0) {
            originalDistance = mc.gameSettings.renderDistanceChunks;
            mc.gameSettings.renderDistanceChunks = Math.max(8, originalDistance);
        }
        for (int cx = request.lowChunkX(); cx <= request.highChunkX(); cx++)
            for (int cz = request.lowChunkZ(); cz <= request.highChunkZ(); cz++) {
                var chunk = mc.theWorld.getChunkProvider()
                    .provideChunk(cx, cz);
                if (chunk == null || chunk instanceof EmptyChunk || !chunk.isChunkLoaded) return;
            }
        for (var missing : request.verifiedMissing()) if (!mc.theWorld
            .isAirBlock(request.x() + missing.x(), request.y() + missing.y(), request.z() + missing.z())) return;
        if (capture == null) {
            if (!(mc.theWorld.getTileEntity(request.x(), request.y(), request.z()) instanceof BaseMetaTileEntity live)
                || live.getMetaTileID() != 14850
                || live.getMetaTileEntity() == null) return;
            var settings = HologramLocalControllerCapture.toolSettings(held);
            capture = new HologramLocalControllerCapture(live, mc.thePlayer.dimension, 2, held, settings, "");
        }
        var projection = capture.stepTarget(4096, 5_000_000L);
        if (projection == null) return;
        var basicOptions = HologramLocalControllerCapture.toolSettings(held);
        basicOptions.setInteger("hatchTier", 1);
        basicOptions.setBoolean("hatchDowngrade", false);
        var plan = capture.prepareNativePreset(basicOptions, Collections.emptyMap());
        server.basicPrepared(request, projection, plan);
        published = true;
        capture.close();
        capture = null;
    }

    void close(Minecraft mc) {
        if (capture != null) capture.close();
        capture = null;
        if (originalDistance >= 0) mc.gameSettings.renderDistanceChunks = originalDistance;
        originalDistance = -1;
    }
}
