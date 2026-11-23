// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramGiantApplyWitness.Progress.DRAFT_ADVANCED;
import static com.miaokatze.gtmb.client.hologram.HologramGiantApplyWitness.Progress.ORIGINAL_REVISION;
import static com.miaokatze.gtmb.client.hologram.HologramGiantApplyWitness.Progress.TASK_ADVANCED;

/** Deterministic interleavings at the first owned tick, without forcing worker or render timing. */
public final class HologramGiantApplyWitnessRegression {

    private static final String PROMPT = "editBlueprintPrompt";

    private HologramGiantApplyWitnessRegression() {}

    public static void run() {
        Object source = new Object(), draft = new Object();
        var packed = new HologramGiantApplyWitness(source, draft, 7, 11, true);
        check(ORIGINAL_REVISION, packed.observe(source, draft, PROMPT, 11, true, true, true, 7, 7),
            "slow worker");
        check(DRAFT_ADVANCED, packed.observe(source, draft, PROMPT, 11, true, true, true, 7, 8),
            "draft advanced before task revision");
        check(TASK_ADVANCED, packed.observe(source, draft, PROMPT, 11, true, true, true, 8, 8),
            "worker already queued client ACK");
        check(null, packed.observe(null, draft, PROMPT, 11, true, true, true, 8, 8), "task fully retired");
        check(null, packed.observe(new Object(), draft, PROMPT, 11, true, true, true, 8, 8), "replaced source");
        check(null, packed.observe(source, new Object(), PROMPT, 11, true, true, true, 8, 8), "replaced draft");
        check(null, packed.observe(source, draft, "editCellFlags", 11, true, true, true, 7, 7), "non-global");
        check(null, packed.observe(source, draft, PROMPT, 12, true, true, true, 8, 8), "other sequence");
        check(null, packed.observe(source, draft, PROMPT, 11, false, true, true, 8, 8), "owner/defer lost");
        check(null, packed.observe(source, draft, PROMPT, 11, true, false, true, 7, 7), "conversion route");
        check(null, packed.observe(source, draft, PROMPT, 11, true, true, false, 8, 8), "converted draft");
        check(null, packed.observe(source, draft, PROMPT, 11, true, true, true, 8, 7), "task ahead of draft");
        check(null, packed.observe(source, draft, PROMPT, 11, true, true, true, 6, 7), "stale task");
        check(null, packed.observe(source, draft, PROMPT, 11, true, true, true, 9, 9), "unrelated revision");
        var ordinary = new HologramGiantApplyWitness(source, draft, 7, 11, false);
        check(TASK_ADVANCED, ordinary.observe(source, draft, PROMPT, 11, true, true, false, 8, 8),
            "same proof for ordinary storage");
    }

    private static void check(HologramGiantApplyWitness.Progress expected, HologramGiantApplyWitness.Progress actual,
        String fixture) {
        if (actual != expected) throw new AssertionError(fixture + ": expected " + expected + ", got " + actual);
    }
}
