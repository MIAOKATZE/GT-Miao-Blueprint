package com.miaokatze.gtmb.client.hologram;

/** Render-time color sampling only; never owns widgets, layout or synchronized state. */
final class HologramUiSpectrum {

    static final long PERIOD_NANOS = 12_000_000_000L;
    static final long BREATH_NANOS = 2_400_000_000L;
    private static final double TAU = Math.PI * 2;

    private HologramUiSpectrum() {}

    static int color(long nanos, double offset) {
        double phase = (Math.floorMod(nanos, PERIOD_NANOS) / (double) PERIOD_NANOS + offset) * TAU;
        return 0xff000000 | channel(phase) << 16 | channel(phase - TAU / 3) << 8 | channel(phase + TAU / 3);
    }

    static int breathe(int color, long nanos) {
        double phase = Math.floorMod(nanos, BREATH_NANOS) / (double) BREATH_NANOS * TAU;
        double brightness = .86 - .14 * Math.cos(phase);
        int r = (int) Math.round((color >> 16 & 255) * brightness);
        int g = (int) Math.round((color >> 8 & 255) * brightness);
        int b = (int) Math.round((color & 255) * brightness);
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    private static int channel(double phase) {
        // Bright enough for the projector's dark cloth; cosine also joins smoothly at the cycle seam.
        return (int) Math.round(176 + 79 * Math.cos(phase));
    }
}
