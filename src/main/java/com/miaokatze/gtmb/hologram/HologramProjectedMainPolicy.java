// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

/** Shared exact source selection for ordinary direct construction. */
public final class HologramProjectedMainPolicy {

    private HologramProjectedMainPolicy() {}

    /** The ordinary projected path supports all explicitly selected existing providers. */
    public static boolean eligibleSelected(HologramMaterials.Settings configured) {
        return configured != null && (configured.main || configured.containers || configured.me)
            && configured.priority >= 0
            && configured.priority <= 2;
    }

    /** Wire-friendly equality of the settings shown at review, never a server permission. */
    public static int settingsKey(HologramMaterials.Settings s) {
        return structurePlacementKey(s, false);
    }

    /** A source-less mask is legal for new structures only when the live actor is creative. */
    public static int structurePlacementKey(HologramMaterials.Settings s, boolean creative) {
        if (s == null || !creative && !eligibleSelected(s)
            || s.priority < 0
            || s.priority > 2
            || s.recoveryDestination < 0
            || s.recoveryDestination > 1) return -1;
        return (s.main ? 1 : 0) | (s.containers ? 2 : 0)
            | (s.me ? 4 : 0)
            | (s.priority << 3)
            | (s.recoveryDestination << 5);
    }

    /** An unsigned byte outside the survival masks; only the live server mode can authorize this value. */
    public static int savedPlacementKey(HologramMaterials.Settings settings, boolean creative) {
        return creative ? 128 : settingsKey(settings);
    }

    static Snapshot captureSaved(HologramMaterials.Settings configured, boolean creative) {
        return creative && configured != null ? new Snapshot(configured) : captureSelected(configured);
    }

    static Snapshot captureSelected(HologramMaterials.Settings configured) {
        return eligibleSelected(configured) ? new Snapshot(configured) : null;
    }

    /** Immutable exact original tool settings, independent of the private execution view. */
    static final class Snapshot {

        private final boolean main, containers, me;
        private final int priority, recoveryDestination;

        private Snapshot(HologramMaterials.Settings configured) {
            main = configured.main;
            containers = configured.containers;
            me = configured.me;
            priority = configured.priority;
            recoveryDestination = configured.recoveryDestination;
        }

        boolean configuredMatches(HologramMaterials.Settings value) {
            return value != null && value.main == main
                && value.containers == containers
                && value.me == me
                && value.priority == priority
                && value.recoveryDestination == recoveryDestination;
        }

        /** Never write the execution view back to the tool. Preserve every explicitly selected provider. */
        HologramMaterials.Settings selectedScope() {
            var settings = new HologramMaterials.Settings();
            settings.main = main;
            settings.containers = containers;
            settings.me = me;
            settings.priority = priority;
            settings.recoveryDestination = recoveryDestination;
            return settings;
        }

        boolean selectedScopeMatches(HologramMaterials.Settings value) {
            return configuredMatches(value);
        }
    }
}
