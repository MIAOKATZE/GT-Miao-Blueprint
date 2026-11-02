package com.miaokatze.gtmb.main;

import com.miaokatze.gtmb.hologram.HologramInteractionSmoke;
import com.miaokatze.gtmb.hologram.HologramLifecycle;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramService;
import com.miaokatze.gtmb.hologram.HologramSmokeTest;
import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.FMLCommonHandler;

/** 专用服务器入口；客户端类只由客户端代理加载。 */
public class CommonProxy {

    /** Common item rendering never links the client task owner on a dedicated server. */
    public boolean hologramTaskBusy() {
        return false;
    }

    private static volatile boolean engineReady;

    public static boolean isEngineReady() {
        return engineReady;
    }

    public void preInit() {
        ProjectorRegistry.registerItem();
        FMLCommonHandler.instance()
            .bus()
            .register(ServerTaskScheduler.INSTANCE);
    }

    public void init() {
        ProjectorRegistry.registerRecipe();
        HologramNetwork.init();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .register(com.miaokatze.gtmb.hologram.HologramDemolition.DROPS);
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramLifecycle());
        HologramSmokeTest.registerIfEnabled();
        HologramInteractionSmoke.registerIfEnabled();
        com.miaokatze.gtmb.hologram.HologramCutNativeSmoke.install();
        com.miaokatze.gtmb.hologram.HologramCutNativeServerProbe.install();
        com.miaokatze.gtmb.hologram.HologramV011InteractionFixture.install();
        com.miaokatze.gtmb.hologram.HologramV011WorldSmoke.install();
        com.miaokatze.gtmb.hologram.HologramV011WorkbenchFixture.install();
        com.miaokatze.gtmb.hologram.HologramSulfurLineSmoke.install();
        engineReady = true;
    }

    /** Called after local testlab provider setup; dedicated servers never link client event classes. */
    public void testLabClientReady() {}

    public void serverStopping() {
        com.miaokatze.gtmb.hologram.HologramCutService.clear();
        HologramService.clear();
        ServerTaskScheduler.clear();
    }
}
