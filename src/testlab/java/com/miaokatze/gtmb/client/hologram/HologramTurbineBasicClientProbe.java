// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtmb.hologram.HologramTurbineStockWorldProbe;

/** Client retirement boundary paired with the retired declared-native turbine scenario. */
final class HologramTurbineBasicClientProbe {

    void step(Minecraft mc, HologramTurbineStockWorldProbe server) {
        throw new UnsupportedOperationException(
            "UNSUPPORTED/RETIRED: declared-native turbine scenario was removed; use an explicitly implemented native target action scenario");
    }

    void close(Minecraft mc) {}
}
