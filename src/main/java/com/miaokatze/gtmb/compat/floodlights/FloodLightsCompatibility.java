package com.miaokatze.gtmb.compat.floodlights;

import com.miaokatze.gtmb.api.GTMBExtensions;

/** Native persistence is retained intact. The client decodes only the four audited visual fields. */
public final class FloodLightsCompatibility {

    private FloodLightsCompatibility() {}

    public static GTMBExtensions.Registration installDisplay() {
        return GTMBExtensions.nativeDisplay("gtmb:floodlights/display", tile -> {
            String name = tile.getClass()
                .getName();
            return name.equals("de.keridos.floodlights.tileentity.TileEntityGrowLight")
                || name.equals("de.keridos.floodlights.tileentity.TileEntitySmallFloodlight");
        });
    }
}
