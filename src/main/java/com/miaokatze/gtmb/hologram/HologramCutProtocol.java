package com.miaokatze.gtmb.hologram;

/**
 * Independent owner/save clipboard. Requests contain IDs, coordinates, pose and optional naming/confirmation choices,
 * never block data.
 * Selected replies contain the complete detached raw entry; rotation/turnX/turnZ/mirror default to identity.
 */
public final class HologramCutProtocol {

    public static final int VERSION = 1;
    public static final int MAX_CELLS = 262144;
    public static final int MAX_SIDE = 256;
    public static final int REQUEST_BYTES = 4096;
    public static final int MAX_ENTRIES = 64;

    private HologramCutProtocol() {}
}
