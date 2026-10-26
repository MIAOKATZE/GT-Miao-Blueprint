package com.miaokatze.gtmb.hologram;

/**
 * Independent owner/save clipboard. Requests contain IDs, coordinates, pose and optional naming/confirmation choices,
 * never block data.
 * Selected replies contain the complete detached raw entry; rotation/turnX/turnZ/mirror default to identity.
 * Preview purposes: storage returns the original; local is a source-local moving sketch; placement binds a locked
 * origin and target dimension. Receipts echo previewGeneration and originX/Y/Z; a target-dependent payload needs a
 * new receipt after an origin change. Confirmed origin-invariant payloads may nudge locally. Moving the local sketch
 * alone never requests another full payload.
 */
public final class HologramCutProtocol {

    public static final int VERSION = 1;
    public static final int MAX_CELLS = 262144;
    public static final int MAX_SIDE = 256;
    // Includes a bounded (128-box) constructive area snapshot, never client block data.
    public static final int REQUEST_BYTES = 65536;
    public static final int MAX_ENTRIES = 64;

    private HologramCutProtocol() {}
}
