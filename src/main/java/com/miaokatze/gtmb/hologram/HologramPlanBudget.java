// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.function.LongSupplier;

/** Cooperative main-thread slice: both work count and elapsed time must permit the next operation. */
final class HologramPlanBudget {

    private final int limit;
    private final long nanos, started;
    private final LongSupplier clock;
    private int used;

    HologramPlanBudget(int limit, long nanos) {
        this(limit, nanos, System::nanoTime);
    }

    HologramPlanBudget(int limit, long nanos, LongSupplier clock) {
        this.limit = Math.max(0, limit);
        this.nanos = Math.max(0, nanos);
        this.clock = clock;
        started = clock.getAsLong();
    }

    boolean take() {
        if (used >= limit || clock.getAsLong() - started >= nanos) return false;
        used++;
        return true;
    }

    int used() {
        return used;
    }
}
