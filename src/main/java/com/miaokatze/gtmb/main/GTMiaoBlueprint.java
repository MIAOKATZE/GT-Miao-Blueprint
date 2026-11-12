// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.main;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.hologram.HologramAeCraftingOrders;
import com.miaokatze.gtmb.hologram.HologramCommand;
import com.miaokatze.gtmb.hologram.HologramLibraryCommand;
import com.miaokatze.gtmb.hologram.HologramLibraryFacade;
import com.miaokatze.gtmb.hologram.HologramStargateRules;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/** 猫猫蓝图：独立的结构规划、蓝图编辑与生存施工工具。 */
@Mod(
    modid = GTMiaoBlueprint.MODID,
    name = "GT-Miao-Blueprint",
    version = Tags.VERSION,
    dependencies = "required-after:miaokatzecore@[0.2.14,);required-after:gregtech;required-after:structurelib;required-after:appliedenergistics2;required-after:modularui2")
public final class GTMiaoBlueprint {

    public static final String MODID = "gtmb";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.miaokatze.gtmb.main.ClientProxy", serverSide = "com.miaokatze.gtmb.main.CommonProxy")
    public static CommonProxy proxy;

    @Mod.Instance(MODID)
    public static GTMiaoBlueprint instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        HologramStargateRules.load(event.getModConfigurationDirectory());
        com.miaokatze.gtmb.main.OptionalTestLab
            .call("com.miaokatze.gtmb.testlab.TestLab", "preInit", event.getModConfigurationDirectory());
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
        com.miaokatze.gtmb.main.OptionalTestLab.call("com.miaokatze.gtmb.testlab.TestLab", "init");
        proxy.testLabClientReady();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit();
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        com.miaokatze.gtmb.main.OptionalTestLab.call("com.miaokatze.gtmb.testlab.TestLab", "beforeServerWorldsLoad");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new HologramCommand());
        event.registerServerCommand(new HologramLibraryCommand());
        com.miaokatze.gtmb.main.OptionalTestLab.call("com.miaokatze.gtmb.testlab.TestLab", "serverStarting", event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        HologramAeCraftingOrders.clearAll("服务器正在关闭，AE 缺料订单已停止");
        com.miaokatze.gtmb.main.OptionalTestLab.call("com.miaokatze.gtmb.testlab.TestLab", "serverStopping");
        com.miaokatze.gtmb.main.OptionalTestLab
            .call("com.miaokatze.gtmb.hologram.HologramLibrarySocketServerProbe", "stop");
        HologramLibraryFacade.stop();
        proxy.serverStopping();
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        com.miaokatze.gtmb.main.OptionalTestLab.call("com.miaokatze.gtmb.testlab.TestLab", "serverStopped");
    }
}
