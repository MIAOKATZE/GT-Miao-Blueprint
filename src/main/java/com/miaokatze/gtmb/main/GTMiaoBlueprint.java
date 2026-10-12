package com.miaokatze.gtmb.main;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.miaokatze.gtmb.Tags;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/** 猫猫蓝图：独立的结构规划、蓝图编辑与生存施工工具。 */
@Mod(
    modid = GTMiaoBlueprint.MODID,
    name = "GT-Miao-Blueprint",
    version = Tags.VERSION,
    dependencies = "required-after:gregtech;required-after:structurelib;required-after:appliedenergistics2")
public final class GTMiaoBlueprint {

    public static final String MODID = "gtmb";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.miaokatze.gtmb.main.ClientProxy", serverSide = "com.miaokatze.gtmb.main.CommonProxy")
    public static CommonProxy proxy;

    @Mod.Instance(MODID)
    public static GTMiaoBlueprint instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping();
    }
}
