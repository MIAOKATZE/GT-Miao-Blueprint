// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;

/** Ordered modal input ownership: a visible layer consumes even an inert or disabled click. */
final class HologramUiInputChain {

    private HologramUiInputChain() {}

    static boolean consume(BooleanSupplier... layers) {
        for (BooleanSupplier layer : layers) if (layer.getAsBoolean()) return true;
        return false;
    }
}
