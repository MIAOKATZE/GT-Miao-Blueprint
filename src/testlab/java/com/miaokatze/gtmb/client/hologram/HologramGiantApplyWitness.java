// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** Identity captured at the native press, independent of the worker's later revision changes. */
final class HologramGiantApplyWitness {

    enum Progress {
        ORIGINAL_REVISION,
        DRAFT_ADVANCED,
        TASK_ADVANCED
    }

    private final Object source, draft;
    private final long revision, sequence;
    private final boolean packed;

    HologramGiantApplyWitness(Object source, Object draft, long revision, long sequence, boolean packed) {
        this.source = source;
        this.draft = draft;
        this.revision = revision;
        this.sequence = sequence;
        this.packed = packed;
    }

    Progress observe(Object taskSource, Object taskDraft, String operation, long taskSequence,
        boolean ownedDeferredDispatch, boolean directRoute, boolean observedPacked, long taskRevision,
        long draftRevision) {
        if (!ownedDeferredDispatch || !directRoute
            || source != taskSource
            || draft != taskDraft
            || !"editBlueprintPrompt".equals(operation)
            || taskSequence != sequence
            || observedPacked != packed) return null;
        // Task.revision is read first. The worker publishes the draft's revision before updating the task.
        if (draftRevision == revision && taskRevision == revision) return Progress.ORIGINAL_REVISION;
        if (draftRevision == revision + 1) {
            if (taskRevision == revision) return Progress.DRAFT_ADVANCED;
            if (taskRevision == draftRevision) return Progress.TASK_ADVANCED;
        }
        return null;
    }
}
