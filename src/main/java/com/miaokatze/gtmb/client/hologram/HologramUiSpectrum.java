package com.miaokatze.gtmb.client.hologram;

/** Render-time color sampling only; never owns widgets, layout or synchronized state. */
final class HologramUiSpectrum {

    static final long PERIOD_NANOS = 12_000_000_000L;
    private static final double TAU = Math.PI * 2;

    private HologramUiSpectrum() {}

    static int color(long nanos, double offset) {
        double phase = (Math.floorMod(nanos, PERIOD_NANOS) / (double) PERIOD_NANOS + offset) * TAU;
        return 0xff000000 | channel(phase) << 16 | channel(phase - TAU / 3) << 8 | channel(phase + TAU / 3);
    }

    private static int channel(double phase) {
        // Bright enough for the projector's dark cloth; cosine also joins smoothly at the cycle seam.
        return (int) Math.round(176 + 79 * Math.cos(phase));
    }
}
