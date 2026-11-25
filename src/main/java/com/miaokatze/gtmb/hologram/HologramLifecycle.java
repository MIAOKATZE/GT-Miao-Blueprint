// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Drives construction exclusively on the server thread. */
public final class HologramLifecycle {

    private boolean stockFirst;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) HologramWorkBudget.startServerTick();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) HologramCutService.startupRecovery();
        if (event.phase == TickEvent.Phase.END) try {
            HologramAeCraftingOrders.tick();
            HologramDemolition.tick();
            HologramService.tick();
            // Native construction shares the remaining tick headroom, not a fixed 5 ms cap.
            // Earlier world and mod work has already been charged against the 40 ms tick target.
            long deadline = HologramWorkBudget.deadline(HologramWorkBudget.tickNanos());
            stockFirst = !stockFirst;
            if (stockFirst) HologramNetwork.tickStockNativeServer(deadline);
            HologramNativeLocalConstruction.tick(deadline);
            HologramPrivateNativeServer.tickLive();
            if (!stockFirst) HologramNetwork.tickStockNativeServer(deadline);
        } finally {
            HologramWorkBudget.endServerTick();
        }
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            HologramWorkspace.revokeActionEndpoint(player);
            HologramDemolition.cancel(player);
            HologramCutService.disconnected(player);
            HologramAeCraftingOrders.cancel(player, "玩家已离线，AE 缺料订单已取消");
        }
    }

    @SubscribeEvent
    public void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            HologramWorkspace.publishActionEndpoint(player, false);
            HologramAeCraftingOrders.recoverOrphans(player);
            HologramDemolition.resumeRecovery(player);
        }
    }

    @SubscribeEvent
    public void respawned(PlayerEvent.PlayerRespawnEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            HologramWorkspace.publishActionEndpoint(player, true);
            HologramDemolition.resumeRecovery(player);
        }
    }

    @SubscribeEvent
    public void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP player && event.fromDim != event.toDim
            && player.dimension == event.toDim) HologramWorkspace.publishActionDimension(player);
    }
}
