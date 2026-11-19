// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** Correlates an immediate client screen with both synchronous redirects and deferred controller scans. */
final class HologramControllerOpen {

    private static final ThreadLocal<HologramControllerOpen> CURRENT = new ThreadLocal<>();
    private final EntityPlayerMP player;
    private final long id;
    private boolean answered;

    private HologramControllerOpen(EntityPlayerMP player, long id) {
        this.player = player;
        this.id = id;
    }

    static void dispatch(EntityPlayerMP player, NBTTagCompound request, Runnable action) {
        long id = request.getLong("controllerOpenId");
        if (id <= 0 || !"useBlock".equals(request.getString("action"))) {
            action.run();
            return;
        }
        HologramControllerOpen opening = new HologramControllerOpen(player, id);
        CURRENT.set(opening);
        try {
            action.run();
        } finally {
            CURRENT.remove();
            if (!opening.answered) HologramNetwork.controllerOpenUnavailable(player, id);
        }
    }

    static long claim(HologramSession s) {
        HologramControllerOpen current = CURRENT.get();
        if (current == null || current.player != s.player) return 0;
        current.answered = true;
        return current.id;
    }

    static long pendingId(HologramSession s) {
        return "controllerStructure".equals(s.openPurpose) ? s.controllerOpenId : 0;
    }

    static void writePurpose(HologramSession s, NBTTagCompound state) {
        state.setString("openPurpose", s.openPurpose);
        long id = claim(s);
        if (id == 0) id = pendingId(s);
        if (id > 0) state.setLong("controllerOpenId", id);
    }
}
