package com.miaokatze.gtmb.main;

import com.miaokatze.gtmb.client.hologram.HologramClient;
import com.miaokatze.gtmb.client.hologram.HologramInteractionClientSmoke;

public final class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        super.init();
        HologramClient.install();
        HologramInteractionClientSmoke.install();
        com.miaokatze.gtmb.client.hologram.HologramProductionPreviewSmoke.install();
        com.miaokatze.gtmb.client.hologram.HologramLibrarySocketClientProbe.install();
    }
}
