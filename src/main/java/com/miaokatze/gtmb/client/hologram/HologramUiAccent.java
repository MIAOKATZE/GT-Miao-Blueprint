package com.miaokatze.gtmb.client.hologram;

/** Semantic guidance colors, shared by the native label and its visible frame. */
final class HologramUiAccent {

    static final int WARNING = 0xffff7777, SUGGESTION = 0xff62e8ef, INFORMATION = 0xff86baff, NEXT = 0xff8bf5a2;
    static final int WEBSITE = 0xffffb45e;
    // A presentation token, resolved independently of widget updates by the native draw callbacks.
    static final int SPECTRUM = 0x01000000;
    static final int BORDER_WIDTH = 2;

    private HologramUiAccent() {}

    static int color(int accent) {
        if (accent == SPECTRUM) return HologramUiSpectrum.color(System.nanoTime(), 0);
        return switch (accent & 0xffffff) {
            case 0 -> 0;
            case 0xef7b7b, 0xff7272, 0xffce86, 0xff7777 -> WARNING;
            case 0x80efaa, 0x8bf5a2 -> NEXT;
            case 0x68dded, 0x81dce9, 0x62e8ef -> SUGGESTION;
            case 0xffb45e -> WEBSITE;
            default -> INFORMATION;
        };
    }

    static int textColor(int accent, boolean enabled) {
        return !enabled ? HologramMuiTheme.MUTED : accent == 0 ? HologramMuiTheme.TEXT : color(accent);
    }

    static int borderColor(int accent, boolean enabled) {
        return enabled ? color(accent) : 0;
    }
}
