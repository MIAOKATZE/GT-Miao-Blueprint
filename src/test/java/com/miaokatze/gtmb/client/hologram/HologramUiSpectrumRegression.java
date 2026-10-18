package com.miaokatze.gtmb.client.hologram;

/** Continuous color math only; the native client must verify cloth, text and GL state visually. */
public final class HologramUiSpectrumRegression {

    private HologramUiSpectrumRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        long period = HologramUiSpectrum.PERIOD_NANOS;
        check(HologramUiSpectrum.color(0, 0) == HologramUiSpectrum.color(period, 0), "cycle seam repeats exactly");
        check(
            HologramUiSpectrum.color(-1, 0) == HologramUiSpectrum.color(period - 1, 0),
            "monotonic-clock negative values retain the same cyclic phase");
        check(
            HologramUiSpectrum.color(0, .25) == HologramUiSpectrum.color(period / 4, 0),
            "spatial and temporal offsets share one continuous palette");
        int first = HologramUiSpectrum.color(0, 0), previous = HologramUiSpectrum.color(-16_666_667, 0);
        boolean changed = false;
        for (long time = 0; time <= period; time += 16_666_667) {
            int color = HologramUiSpectrum.color(time, 0);
            check((color >>> 24) == 255, "animation stays opaque");
            for (int shift = 0; shift <= 16; shift += 8) {
                int value = color >>> shift & 255;
                check(value >= 97, "animated text remains bright on the projector cloth");
                check(Math.abs(value - (previous >>> shift & 255)) <= 1, "adjacent frames and wrap never jump hues");
            }
            changed |= color != first;
            previous = color;
        }
        check(changed, "the draw palette advances with time");
        check(
            HologramUiAccent.color(0xffce86) == HologramUiAccent.WARNING,
            "existing amber warnings keep warning semantics");
        check(
            HologramUiAccent.textColor(HologramUiAccent.SPECTRUM, false) == HologramMuiTheme.MUTED,
            "disabled text stays muted");
        check(HologramUiAccent.borderColor(HologramUiAccent.SPECTRUM, false) == 0, "disabled borders do not animate");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
