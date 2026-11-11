// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

/** Retains per-cell results until a complete snapshot publishes them, independently of work slices. */
final class HologramJobPublication {

    private long publishedTick = Long.MIN_VALUE, revision = Long.MIN_VALUE, uiSequence = Long.MIN_VALUE;
    final List<Integer> indices = new ArrayList<>(), successes = new ArrayList<>();
    final List<String> statuses = new ArrayList<>();

    boolean due(long tick, long currentRevision, long currentUiSequence) {
        return publishedTick == Long.MIN_VALUE || tick < publishedTick
            || tick - publishedTick >= 5
            || revision != currentRevision
            || uiSequence != currentUiSequence;
    }

    void published(long tick, long currentRevision, long currentUiSequence) {
        publishedTick = tick;
        revision = currentRevision;
        uiSequence = currentUiSequence;
        indices.clear();
        successes.clear();
        statuses.clear();
    }

    void clear() {
        publishedTick = revision = uiSequence = Long.MIN_VALUE;
        indices.clear();
        successes.clear();
        statuses.clear();
    }
}
