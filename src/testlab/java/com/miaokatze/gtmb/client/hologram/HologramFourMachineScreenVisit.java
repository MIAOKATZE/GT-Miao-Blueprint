// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** Test driver state for one real screen visit; it never changes tutorial or preference state. */
final class HologramFourMachineScreenVisit {

    private Object screen;
    private boolean tutorialExit;

    void open(Object nextScreen, Runnable opening) {
        if (nextScreen == null || opening == null) throw new IllegalArgumentException("Exact screen opening required");
        screen = nextScreen;
        tutorialExit = false;
        try {
            opening.run();
        } finally {
            tutorialExit = false;
        }
    }

    boolean takeTutorialExit(Object currentScreen, boolean active) {
        if (currentScreen != screen) throw new IllegalStateException("Tutorial exit belongs to another screen visit");
        if (!active || tutorialExit) return false;
        tutorialExit = true;
        return true;
    }
}
