package com.miaokatze.gtmb.compat;

import java.util.ArrayList;
import java.util.List;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.compat.carpenters.common.CarpentersCompatibility;
import com.miaokatze.gtmb.compat.cookingforblockheads.CookingForBlockheadsCompatibility;
import com.miaokatze.gtmb.compat.jabba.common.JabbaCompatibility;
import com.miaokatze.gtmb.compat.storagedrawers.common.StorageDrawersCompatibility;

import cpw.mods.fml.common.Loader;

/** Common FML assembly only. Each optional integration owns its own stage implementations. */
public final class BlueprintCompatibility {

    private static final List<GTMBExtensions.Registration> COMMON = new ArrayList<>();

    private BlueprintCompatibility() {}

    public static void initialize() {
        if (Loader.isModLoaded("CarpentersBlocks")) {
            COMMON.add(CarpentersCompatibility.install());
            COMMON.add(CarpentersCompatibility.installDisplay());
        }
        if (Loader.isModLoaded("cookingforblockheads")) CookingForBlockheadsCompatibility.initialize();
        if (Loader.isModLoaded("JABBA")) COMMON.addAll(JabbaCompatibility.install());
        if (Loader.isModLoaded("StorageDrawers")) COMMON.addAll(StorageDrawersCompatibility.install());
    }
}
