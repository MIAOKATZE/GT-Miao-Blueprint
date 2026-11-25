// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;

import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Binding;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.CommitReceipt;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Outcome;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Phase;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** No Minecraft instance, fake percentages, clocks or network calls. */
public final class HologramOwnedPlacementProgressRegression {

    private HologramOwnedPlacementProgressRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        Object world = new Object(), connection = new Object(), player = new Object(), tool = new Object();
        Binding own = new Binding(world, connection, player, tool, 1);
        Binding changedTool = new Binding(world, connection, player, new Object(), 1);
        Binding changedSlot = new Binding(world, connection, player, tool, 2);
        Binding changedActor = new Binding(world, connection, new Object(), tool, 1);
        Binding changedConnection = new Binding(world, new Object(), player, tool, 1);
        Binding changedWorld = new Binding(new Object(), connection, player, tool, 1);
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        HologramOwnedPlacementProgress progress = new HologramOwnedPlacementProgress();
        SimpleAssert.eq(
            null,
            progress.begin(id, new Binding(null, connection, player, tool, 1), 1, null, 1),
            "no world means no task");
        var start = progress.begin(id, own, 1, () -> true, 2);
        SimpleAssert.eq(id, start.request(), "opaque ticket names the exact request");
        SimpleAssert.that(progress.cancellation(start, own) != null, "exact active tool owns cancellation route");
        SimpleAssert.eq(null, progress.cancellation(start, changedTool), "replacement tool cannot cancel");
        SimpleAssert.that(
            !progress.acceptCommit(start, own, new CommitReceipt(id, 1, Outcome.SUCCESS, 0, 0, 0), 3),
            "even an authoritative zero-target receipt is not successful placement");
        SimpleAssert.that(
            !progress.finish(start, own, 1, Outcome.SUCCESS, 1, 0, 1, 3),
            "a preparation acknowledgment cannot look like placed work even with complete counts");
        SimpleAssert.eq(
            -1,
            progress.view(own, 3)
                .percent(),
            "initial preparation has no invented denominator");
        SimpleAssert.eq(null, progress.begin(id, own, 2, null, 3), "another request cannot replace the active owner");
        for (Binding stale : new Binding[] { changedActor, changedConnection, changedWorld }) {
            SimpleAssert.eq(null, progress.view(stale, 4), "foreign client context cannot render");
            SimpleAssert.that(
                !progress.observe(start, stale, Phase.PREFLIGHT, 1, 0, 0, 4),
                "foreign client context cannot publish");
        }
        SimpleAssert.eq(null, progress.view(changedTool, 4), "replacement tool cannot render or reopen");
        SimpleAssert.eq(null, progress.view(changedSlot, 4), "another slot cannot render or reopen");
        SimpleAssert
            .that(progress.observe(start, own, Phase.PREFLIGHT, 10, 18, 0, 5), "real work count without known total");
        SimpleAssert.eq(
            -1,
            progress.view(own, 5)
                .percent(),
            "unknown total retains indeterminate bar style");
        SimpleAssert.that(
            progress.observe(start, own, Phase.ADMISSION, 1, 0, 0, 6),
            "a later producer has its own phase-local sequence");
        SimpleAssert.that(
            !progress.observe(start, own, Phase.PREFLIGHT, 11, 99, 100, 7),
            "a delayed earlier phase never moves the display backwards");
        SimpleAssert.that(
            progress.observe(start, own, Phase.EXECUTING, 1, 40, 123369, 8),
            "actual executed or already-satisfied cells are reported against the real denominator");
        SimpleAssert.that(
            !progress.observe(start, own, Phase.EXECUTING, 1, 41, 123369, 9)
                && !progress.observe(start, own, Phase.EXECUTING, 2, 39, 123369, 9)
                && !progress.observe(start, own, Phase.EXECUTING, 2, 40, 123370, 9),
            "replays, counter regressions and replaced denominators do not change the displayed owner");
        SimpleAssert.that(
            !progress.acceptCommit(start, own, new CommitReceipt(id, 2, Outcome.SUCCESS, 40, 0, 123369), 10),
            "a typed commit still needs exact physically settled counts");
        SimpleAssert.that(
            progress.observe(start, own, Phase.EXECUTING, 2, 123369, 123369, 11),
            "full counts can arrive before the owned terminal");
        SimpleAssert.eq(
            99,
            progress.view(own, 11)
                .percent(),
            "do not show 100 before authoritative completion");
        SimpleAssert.that(
            !progress.finish(start, own, 3, Outcome.SUCCESS, 123368, 1, 123369, 12),
            "even exact counts through the generic callback are not committed evidence");
        SimpleAssert.that(
            !progress.acceptCommit(start, own, new CommitReceipt(id, 3, Outcome.SUCCESS, 0, 123369, 123369), 12),
            "all already satisfied is no new construction, not placed work");
        SimpleAssert.that(
            !progress.acceptCommit(start, own, new CommitReceipt(id, 2, Outcome.SUCCESS, 123368, 1, 123369), 12),
            "commit must be newer than any observed authoritative execution sequence");
        SimpleAssert.that(
            progress.acceptCommit(start, changedTool, new CommitReceipt(id, 3, Outcome.SUCCESS, 123368, 1, 123369), 12),
            "actual placed and verified satisfied counts certify the result even if the player changed tools");
        SimpleAssert.eq(null, progress.cancellation(start, own), "the terminal no longer owns a cancellation closure");
        SimpleAssert.eq(null, progress.view(changedTool, 12), "terminal is not shown on a replacement tool");
        SimpleAssert.eq(
            100,
            progress.view(own, 12)
                .percent(),
            "owned terminal and exact counts are complete");
        progress.expireTerminal(own, 2_000_000_013L);
        SimpleAssert
            .eq(null, progress.view(own, 2_000_000_013L), "existing completion bar expires without a new panel");
        SimpleAssert
            .eq(null, progress.ticket(), "terminal expiry releases the ticket and its context, not just the bar");
        var retry = progress.begin(id, own, 2, null, 2_000_000_014L);
        SimpleAssert.that(
            !progress.observe(start, own, Phase.PREFLIGHT, 99, 99, 0, 2_000_000_015L),
            "a stale handle is not sufficient even when the raw request ID is the same");
        SimpleAssert
            .that(progress.observe(retry, own, Phase.EXECUTING, 1, 3, 10, 2_000_000_015L), "new owner progresses");
        SimpleAssert.that(
            progress.finish(retry, own, 2, Outcome.CANCELLED, 0, 0, 0, 2_000_000_016L),
            "only the owning terminal retires a stopped partial task; cancellation may omit counters");
        SimpleAssert
            .eq(null, progress.view(own, 2_000_000_016L), "cancelled task is neither a spinner nor a green 100%");
        SimpleAssert.that(
            !progress.acceptCommit(retry, own, new CommitReceipt(id, 3, Outcome.SUCCESS, 10, 0, 10), 2_000_000_017L),
            "late success cannot resurrect a cancelled task");
        progress.expireTerminal(changedWorld, 2_000_000_018L);
        SimpleAssert.eq(null, progress.ticket(), "foreign world retires the old terminal without waiting for expiry");
        var noWork = progress.begin(id, own, 3, () -> true, 2_000_000_019L);
        SimpleAssert.that(
            !progress.finish(noWork, own, 1, Outcome.NO_WORK, 0, 0, 0, 2_000_000_020L),
            "an untyped empty response cannot resolve the task");
        SimpleAssert.that(
            progress.acceptCommit(noWork, own, new CommitReceipt(id, 1, Outcome.NO_WORK, 0, 0, 0), 2_000_000_020L),
            "an authoritative empty operation may finish explicitly as no work");
        SimpleAssert.eq(Outcome.NO_WORK, progress.terminal(), "authoritative no work is a distinct terminal");
        SimpleAssert.eq(null, progress.view(own, 2_000_000_020L), "no work does not render 100% or an active spinner");
        SimpleAssert.eq(null, progress.cancellation(noWork, own), "no work also releases its cancellation callback");
        progress.expireTerminal(changedTool, 2_000_000_021L);
        SimpleAssert.eq(null, progress.ticket(), "terminal owned by a different tool no longer retains its ticket");

        // The authoritative result can be the first execution message after coalescing or lost updates.
        UUID nextId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var fast = progress.begin(nextId, own, 4, null, 2_000_000_022L);
        SimpleAssert.that(
            progress.observe(fast, own, Phase.PREFLIGHT, 500, 19, 0, 2_000_000_023L),
            "client-side work already has an unrelated sequence");
        SimpleAssert.that(
            !progress.acceptCommit(fast, own, new CommitReceipt(id, 1, Outcome.SUCCESS, 1, 0, 1), 2_000_000_024L),
            "a committed result from another request cannot end this ticket");
        SimpleAssert.that(
            progress.acceptCommit(fast, own, new CommitReceipt(nextId, 1, Outcome.SUCCESS, 1, 0, 1), 2_000_000_024L),
            "matching authoritative commit completes even before the first EXECUTING progress update");
        SimpleAssert.eq(
            100,
            progress.view(own, 2_000_000_024L)
                .percent(),
            "the fast committed result is truthful");
        SimpleAssert.that(
            !progress.observe(fast, own, Phase.EXECUTING, 99, 1, 1, 2_000_000_025L),
            "late executing counters cannot reopen the terminal");
    }
}
