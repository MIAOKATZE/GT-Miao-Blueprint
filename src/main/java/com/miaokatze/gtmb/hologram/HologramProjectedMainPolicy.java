// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

/** Shared exact source selection for ordinary direct construction. */
public final class HologramProjectedMainPolicy {

    private HologramProjectedMainPolicy() {}

    /** Use the same explicit stock choices and priority as the existing material transaction. */
    public static boolean eligible(HologramMaterials.Settings configured) {
        return configured != null && configured.main && configured.priority >= 0 && configured.priority <= 2
            && (configured.priority != 1 || !configured.containers);
    }

    /** The ordinary projected path supports all explicitly selected existing providers. */
    public static boolean eligibleSelected(HologramMaterials.Settings configured) {
        return configured != null && (configured.main || configured.containers || configured.me)
            && configured.priority >= 0 && configured.priority <= 2;
    }

    static Snapshot captureSelected(HologramMaterials.Settings configured) {
        return eligibleSelected(configured) ? new Snapshot(configured) : null;
    }

    static Snapshot capture(HologramMaterials.Settings configured) {
        return eligible(configured) ? new Snapshot(configured) : null;
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
            return value != null && value.main == main && value.containers == containers && value.me == me
                && value.priority == priority && value.recoveryDestination == recoveryDestination;
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

        /** Existing stock-native profile remains explicitly main-only. */
        HologramMaterials.Settings mainScope() {
            var settings = selectedScope();
            settings.main = true;
            settings.containers = settings.me = false;
            return settings;
        }

        boolean selectedScopeMatches(HologramMaterials.Settings value) {
            return configuredMatches(value);
        }
    }
}
