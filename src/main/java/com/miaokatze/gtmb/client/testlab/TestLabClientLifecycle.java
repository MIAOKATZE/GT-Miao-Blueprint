package com.miaokatze.gtmb.client.testlab;

import com.miaokatze.gtmb.testlab.TestLab;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** Installed only by ClientProxy after configured provider registration; disabled clients register no handler. */
public final class TestLabClientLifecycle {

    private static boolean installed;

    private TestLabClientLifecycle() {}

    public static void install() {
        if (installed || !TestLab.clientMappingAvailable()) return;
        FMLCommonHandler.instance()
            .bus()
            .register(new TestLabClientLifecycle());
        installed = true;
    }

    @SubscribeEvent
    public void connected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        // Modded FML completion is a queued play packet handled on the main thread before later login packets.
        // Minecraft executes this immediately on that thread, and queues any other event source without blocking Netty.
        net.minecraft.client.Minecraft.getMinecraft()
            .func_152344_a(() -> TestLab.clientConnected(event.manager, event.isLocal));
    }

    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        net.minecraft.client.Minecraft.getMinecraft()
            .func_152344_a(() -> TestLab.clientDisconnected(event.manager));
    }
}
