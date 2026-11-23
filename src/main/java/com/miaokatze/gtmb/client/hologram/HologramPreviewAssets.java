// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** The snapshot material, native model, and cover caches of one render-thread view. */
final class HologramPreviewAssets {

    static final HologramPreviewAssets EDITOR = new HologramPreviewAssets();
    private static HologramPreviewAssets active = EDITOR;

    final HologramBlockRender.Cache blocks = new HologramBlockRender.Cache();
    final HologramTextures.Cache textures = new HologramTextures.Cache();
    final HologramLinkNodePreview.Cache linkNodes = new HologramLinkNodePreview.Cache();

    static HologramPreviewAssets active() {
        return active;
    }

    Scope enter() {
        Scope scope = new Scope(active);
        active = this;
        return scope;
    }

    void clear() {
        try (Scope ignored = enter()) {
            HologramBlockRender.clear();
            HologramTextures.clear();
            HologramLinkNodePreview.begin();
        }
    }

    static final class Scope implements AutoCloseable {

        private final HologramPreviewAssets previous;

        private Scope(HologramPreviewAssets previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            active = previous;
        }
    }
}
