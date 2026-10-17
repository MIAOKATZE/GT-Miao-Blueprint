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
