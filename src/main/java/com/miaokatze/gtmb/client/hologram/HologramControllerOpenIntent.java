// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** Esc can retire a click before any server session exists on the client. */
final class HologramControllerOpenIntent {

    private long latest;
    private boolean dismissed;
    private boolean tracksTarget;
    private int dimension, x, y, z;

    long begin(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        tracksTarget = true;
        dismissed = false;
        return ++latest;
    }

    void forgetTarget() {
        tracksTarget = false;
        dismissed = true;
    }

    void installed() {
        tracksTarget = false;
    }

    boolean acceptsLegacy(int dimension, int x, int y, int z) {
        return !tracksTarget || !dismissed && this.dimension == dimension && this.x == x && this.y == y && this.z == z;
    }

    void dismiss(long id) {
        if (id == latest) dismissed = true;
    }

    boolean accepts(long id) {
        return id > 0 && id == latest && !dismissed;
    }
}
