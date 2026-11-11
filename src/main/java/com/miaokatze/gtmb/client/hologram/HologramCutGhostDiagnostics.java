// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** Passive observer seam. Pixel readback and test assertions exist only in the optional variant. */
class HologramCutGhostDiagnostics {

    static HologramCutGhostDiagnostics create(float opacity, float[] tint) {
        Object optional = com.miaokatze.gtmb.main.OptionalTestLab
            .call("com.miaokatze.gtmb.client.hologram.HologramCutGhostProbe", "create", opacity, tint);
        return optional == null ? new HologramCutGhostDiagnostics() : (HologramCutGhostDiagnostics) optional;
    }

    void beforeDepthCopy(int destination, int width, int height) {}

    void afterDepthCopy() {}

    void sampleLayer() {}

    void beforeComposite() {}

    void afterComposite() {}

    void finishFrame() {}

    void inspect(NBTTagCompound out) {}

    void reset() {}
}
