package com.miaokatze.gtmb.main;

import com.miaokatze.gtmb.client.hologram.HologramClient;
import com.miaokatze.gtmb.client.hologram.HologramInteractionClientSmoke;

public final class ClientProxy extends CommonProxy {

    @Override
    public boolean hologramTaskBusy() {
        return com.miaokatze.gtmb.client.hologram.HologramBackgroundTasks.busy();
    }

    @Override
    public void testLabClientReady() {
        com.miaokatze.gtmb.client.testlab.TestLabClientLifecycle.install();
    }

    @Override
    public void preInit() {
        super.preInit();
        com.miaokatze.gtmb.client.hologram.HologramEditorNativeTheme.install();
        com.miaokatze.gtmb.hologram.HologramLibraryFacade
            .configureClientGameDirectory(net.minecraft.client.Minecraft.getMinecraft().mcDataDir.toPath());
    }

    @Override
    public void init() {
        super.init();
        HologramClient.install();
        com.miaokatze.gtmb.client.hologram.HologramV010GuiSmoke.install();
        HologramInteractionClientSmoke.install();
        com.miaokatze.gtmb.client.hologram.HologramV011AreaClientSmoke.install();
        com.miaokatze.gtmb.client.hologram.HologramV011ProcessingClientProbe.install();
        com.miaokatze.gtmb.client.hologram.HologramV011WorkbenchClientSmoke.install();
        com.miaokatze.gtmb.client.hologram.HologramSulfurLineSmokeClientProbe.install();
        com.miaokatze.gtmb.client.hologram.HologramProductionPreviewSmoke.install();
        com.miaokatze.gtmb.client.hologram.HologramLibrarySocketClientProbe.install();
    }
}
