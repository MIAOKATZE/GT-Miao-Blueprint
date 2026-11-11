// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

/** Stable persisted tool keys. Refactoring must not change on-disk names. */
final class HologramSessionKeys {

    private HologramSessionKeys() {}

    static final String TOOL_ID = "gtitHologramToolId";
    static final String DRAFT = "gtitHologramDraft";
    static final String SELECTED_PRESET = "gtitHologramPreset";

}
