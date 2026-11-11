// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** Passive raster observation seam; never reads pixels without an installed test variant. */
final class HologramRasterBridge {

    interface Frame {

        Object before(double[][] outline, double depthScale);

        void after(HologramState.Cell cell, Object slice);
    }

    interface Observer {

        Frame begin(HologramState state, boolean current);

        void finish(Frame frame);
    }

    static Observer observer;

    static Frame begin(HologramState state, boolean current) {
        return observer == null ? null : observer.begin(state, current);
    }

    static void finish(Frame frame) {
        if (observer != null) observer.finish(frame);
    }

    private HologramRasterBridge() {}
}
