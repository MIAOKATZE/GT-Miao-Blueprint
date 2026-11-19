// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** Esc can retire a click before any server session exists on the client. */
final class HologramControllerOpenIntent {

    private long latest;
    private boolean dismissed;
    private boolean tracksTarget;
    private int dimension, x, y, z;
    private String legacySession = "";
    private final HologramProgress.Tracker waiting = new HologramProgress.Tracker();

    long begin(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        tracksTarget = true;
        dismissed = false;
        legacySession = "";
        ++latest;
        waiting.update("controller:" + latest, "ack", 0, 0, "", false, System.nanoTime());
        return latest;
    }

    void forgetTarget() {
        tracksTarget = false;
        dismissed = true;
        legacySession = "";
    }

    void installed() {
        tracksTarget = false;
        legacySession = "";
    }

    boolean acceptsLegacy(int dimension, int x, int y, int z) {
        return !tracksTarget || !dismissed && matchesTarget(dimension, x, y, z);
    }

    private boolean matchesTarget(int dimension, int x, int y, int z) {
        return this.dimension == dimension && this.x == x && this.y == y && this.z == z;
    }

    boolean answers(NBTTagCompound snapshot, long id) {
        if (!accepts(id)) return false;
        long request = snapshot.getLong("controllerOpenId");
        if (request > 0) return request == id;
        return tracksTarget
            && matchesTarget(
                snapshot.getInteger("dimension"),
                snapshot.getInteger("x"),
                snapshot.getInteger("y"),
                snapshot.getInteger("z"))
            && (snapshot.getBoolean("open") && "controllerStructure".equals(snapshot.getString("openPurpose"))
                || followsLegacyOpening(snapshot));
    }

    void expectLegacyBaseline(NBTTagCompound snapshot) {
        if (snapshot.getLong("controllerOpenId") == 0) legacySession = snapshot.getString("session");
    }

    boolean hidesLegacyBaseline(NBTTagCompound snapshot) {
        return dismissed && followsLegacyOpening(snapshot);
    }

    private boolean followsLegacyOpening(NBTTagCompound snapshot) {
        return !legacySession.isEmpty() && legacySession.equals(snapshot.getString("session"))
            && HologramState.fullBaseline(snapshot);
    }

    HologramProgress progress() {
        HologramProgress current = HologramClientProgress.controllerOpening(latest);
        return current == null ? waiting.view(System.nanoTime()) : current;
    }

    void dismiss(long id) {
        if (id == latest) dismissed = true;
    }

    boolean canOpenWhileScanning() {
        return tracksTarget && dismissed && HologramBackgroundTasks.scanningControllerOpening(latest);
    }

    boolean accepts(long id) {
        return id > 0 && id == latest && !dismissed;
    }
}
