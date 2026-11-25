// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

/** Decoder policy, independent of the v1 byte layout. No declared count is an allocation instruction. */
public final class NativeBlueprintLimits {

    public static final int FILE_BYTES = 128 * 1024 * 1024, CELLS = 4_000_000, SECTIONS = 64;
    public static final int INDEX_BYTES = 64 * 1024 * 1024, JSON_BYTES = 64 * 1024 * 1024;
    public static final int RECORD_BYTES = 8 * 1024 * 1024, NBT_BYTES = 8 * 1024 * 1024;
    public static final long NBT_TOTAL = 256L * 1024 * 1024, LOGICAL_BYTES = 512L * 1024 * 1024;
    public static final int DEPTH = 512, NBT_NODES = 16_000_000, PAGE_BYTES = 65536, PAGE_CELLS = 32768;

    private NativeBlueprintLimits() {}
}
