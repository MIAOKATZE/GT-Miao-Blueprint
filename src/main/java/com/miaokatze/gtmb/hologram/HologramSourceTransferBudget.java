package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Owned heap only: shared capture/registry Strings are not new allocations; logical JSON is budgeted by the codec. */
final class HologramSourceTransferBudget {

    static final long LIMIT = 64L * 1024 * 1024;
    // Uncompressed 64-bit references/header plus a complete alignment word of field-layout slack.
    // Compressed-oops JVMs use less. All rows include transient fields, even though none are serialized.
    static final long CELL = shallowBound(HologramPresets.Cell.class);
    static final long TARGET = shallowBound(HologramPresets.Target.class);
    static final long INDEX_ENTRY = 80; // three-int key (32) + HashMap node (48), uncompressed
    static final long MAP_ENTRY = 80; // node plus conservative amortized backing-table capacity

    private HologramSourceTransferBudget() {}

    static long shallowBound(Class<?> type) {
        long bytes = 32; // 16-byte object header + 16 bytes of field-placement/alignment slack
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> value = field.getType();
            bytes += !value.isPrimitive() || value == long.class || value == double.class ? 8
                : value == int.class || value == float.class ? 4 : value == short.class || value == char.class ? 2 : 1;
        }
        return align(bytes);
    }

    static long align(long bytes) {
        return (bytes + 15) & ~15L;
    }

    static long list(int size, boolean growable) {
        if (size == 0) return 0; // canonical shared empty list
        long capacity = growable ? Math.max(10L, size + (size + 1L) / 2) : size;
        return 64 + align(32 + 8 * capacity); // conservative ArrayList and backing Object[]
    }

    static long string(String value) {
        return value == null ? 0 : 48 + align(32 + 2L * value.length());
    }

    static boolean fits(long owned) {
        return owned >= 0 && owned <= LIMIT;
    }
}
