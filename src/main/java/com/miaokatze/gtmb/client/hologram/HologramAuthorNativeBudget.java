package com.miaokatze.gtmb.client.hologram;

/** Client decoded-row policy only; independent of Core's process-wide heavy-work reservations and GL memory. */
final class HologramAuthorNativeBudget {

    static final String PROPERTY = "gtmb.preview.nativeResidentMiB";
    private static final long MIB = 1L << 20;

    private HologramAuthorNativeBudget() {}

    static long configuredBytes() {
        String value;
        try {
            value = System.getProperty(PROPERTY);
        } catch (SecurityException unavailable) {
            value = null;
        }
        return bytes(
            Runtime.getRuntime()
                .maxMemory(),
            value);
    }

    /** Zero/invalid selects automatic. The heap fraction cap wins even on an unusually small test JVM. */
    static long bytes(long maximumHeap, String configuredMiB) {
        long heap = Math.max(1L, maximumHeap);
        long ceiling = Math.max(1L, Math.min(512L * MIB, heap / 8));
        long automatic = Math.min(256L * MIB, Math.max(32L * MIB, heap / 32));
        long requested = 0;
        try {
            if (configuredMiB != null) requested = Long.parseLong(configuredMiB.trim());
        } catch (NumberFormatException invalid) {
            // An invalid local tuning property must not prevent opening a blueprint.
        }
        long bytes = requested <= 0 ? automatic : Math.max(8L, Math.min(512L, requested)) * MIB;
        return Math.min(ceiling, bytes);
    }
}
