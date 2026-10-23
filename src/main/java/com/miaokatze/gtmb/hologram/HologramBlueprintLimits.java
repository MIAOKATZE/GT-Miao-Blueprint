package com.miaokatze.gtmb.hologram;

import java.io.IOException;

/** Recorded host cells, independent of empty bounding space, payload bytes and attachment microvoxels. */
public final class HologramBlueprintLimits {

    public static final int MAX_CELLS = 999_999;

    private HologramBlueprintLimits() {}

    /** Explicit air and excluded records still own coordinates and retained blueprint data. */
    public static boolean accepts(long cells) {
        return cells >= 0 && cells <= MAX_CELLS;
    }

    static void require(long cells) throws IOException {
        if (!accepts(cells)) throw new IOException("蓝图实际记录格数须小于一百万，请拆分蓝图");
    }
}
