package com.miaokatze.gtmb.hologram;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Drives construction exclusively on the server thread. */
public final class HologramLifecycle {

    private boolean cutFirst;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) HologramWorkBudget.startServerTick();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) HologramCutService.startupRecovery();
        if (event.phase == TickEvent.Phase.END) try {
            HologramAeCraftingOrders.tick();
            HologramWorkBudget.begin(System.nanoTime() + HologramWorkBudget.tickNanos());
            try {
                // Both domain continuations spend one allowance; alternate the first atomic unit.
                cutFirst = !cutFirst;
                if (cutFirst) {
                    HologramCutService.tick();
                    HologramService.tick();
                } else {
                    HologramService.tick();
                    if (HologramWorkBudget.remainingNanos(0) > 0) HologramCutService.tick();
                }
            } finally {
                HologramWorkBudget.end();
            }
        } finally {
            HologramWorkBudget.endServerTick();
        }
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof net.minecraft.entity.player.EntityPlayerMP) HologramAeCraftingOrders
            .cancel((net.minecraft.entity.player.EntityPlayerMP) event.player, "玩家已离线，AE 缺料订单已取消");
    }

    @SubscribeEvent
    public void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof net.minecraft.entity.player.EntityPlayerMP)
            HologramAeCraftingOrders.recoverOrphans((net.minecraft.entity.player.EntityPlayerMP) event.player);
    }
}
