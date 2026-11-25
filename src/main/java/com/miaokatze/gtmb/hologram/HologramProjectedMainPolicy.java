// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

/** Shared eligibility for the ordinary direct profile's narrow, fully funded main-backpack lane. */
public final class HologramProjectedMainPolicy {

    private HologramProjectedMainPolicy() {}

    /** Ordinary direct historically excludes wireless ME. Preserve container-first priority instead of overriding it. */
    public static boolean eligible(HologramMaterials.Settings configured) {
        return configured != null && configured.main && configured.priority >= 0 && configured.priority <= 2
            && (configured.priority != 1 || !configured.containers);
    }

    static Snapshot capture(HologramMaterials.Settings configured) {
        return eligible(configured) ? new Snapshot(configured) : null;
    }

    /** Immutable exact original tool settings, independent of the private execution view. */
    static final class Snapshot {

        private final boolean containers, me;
        private final int priority, recoveryDestination;

        private Snapshot(HologramMaterials.Settings configured) {
            containers = configured.containers;
            me = configured.me;
            priority = configured.priority;
            recoveryDestination = configured.recoveryDestination;
        }

        boolean configuredMatches(HologramMaterials.Settings value) {
            return value != null && value.main && value.containers == containers && value.me == me
                && value.priority == priority && value.recoveryDestination == recoveryDestination;
        }

        /** Never write this view to the tool. Neither observation nor execution will open other providers. */
        HologramMaterials.Settings mainScope() {
            var settings = new HologramMaterials.Settings();
            settings.containers = settings.me = false;
            settings.priority = priority;
            settings.recoveryDestination = recoveryDestination;
            return settings;
        }

        boolean mainScopeMatches(HologramMaterials.Settings value) {
            return value != null && value.main && !value.containers && !value.me
                && value.priority == priority && value.recoveryDestination == recoveryDestination;
        }
    }
}
