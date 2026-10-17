package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** First world lock stays in the world; an opened review receives background job and missing-material updates. */
public final class HologramPlacementReviewContextRegression {

    private HologramPlacementReviewContextRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        HologramPendingPositionLockRegression.run();
        terminalWorkspaceReopening();
        HologramPlacementReviewContext context = new HologramPlacementReviewContext();
        context.accept("blueprint", 1, false, true, true);
        SimpleAssert.that(!context.retain("blueprint", 1, true, false), "first lock never invents an opened UI");
        context.accept("blueprint", 2, true, true, true);
        SimpleAssert.that(context.retain("blueprint", 2, true, false), "second right accepts the review context");
        context.accept("blueprint", 2, false, true, false);
        SimpleAssert.that(
            context.retain("blueprint", 2, true, false),
            "open=false progress and completion retain the opened review after awaitingConfirmation clears");
        context.accept("blueprint", 2, false, true, true);
        SimpleAssert.that(
            context.retain("blueprint", 2, true, false),
            "open=false missing-material confirmation keeps its visible review");
        SimpleAssert.that(!context.retain("different", 2, true, false), "another server session never inherits UI");
        SimpleAssert.that(!context.retain("blueprint", 1, true, false), "a stale review nonce cannot retain UI");
        SimpleAssert.that(!context.retain("blueprint", 3, false, true), "unlock returns to world preview");
        SimpleAssert.that(!context.retain("blueprint", 3, true, true), "a new moving placement closes review");
        HologramPlacementReviewContext reopened = new HologramPlacementReviewContext();
        reopened.accept("blueprint", 2, false, true, true);
        SimpleAssert.that(
            !reopened.retain("blueprint", 2, true, false),
            "a dismissed screen's presentation context cannot reopen from background updates");
    }

    private static void terminalWorkspaceReopening() {
        for (int job : new int[] { 3, 4, 5 }) {
            NBTTagCompound complete = placement(job);
            HologramState cached = new HologramState(complete);
            SimpleAssert.that(
                !HologramPlacementReviewContext.returnToWorld(cached.data),
                "a cached terminal placement remains a workspace when deliberately reopened: " + job);

            NBTTagCompound opening = (NBTTagCompound) complete.copy();
            opening.removeTag("cells");
            opening.setBoolean("areaSelectionOnly", true);
            opening.setBoolean("workspaceUiOnly", true);
            opening.setBoolean("workspaceHasCapture", true);
            opening.setLong("areaSelectionSequence", 1);
            opening.setBoolean("open", true);
            HologramState reopened = HologramState.apply(cached, opening);
            SimpleAssert.that(
                reopened.cells == cached.cells && !HologramPlacementReviewContext.returnToWorld(reopened.data),
                "the lightweight opening reuses terminal geometry without returning to world: " + job);

            NBTTagCompound update = (NBTTagCompound) complete.copy();
            update.removeTag("cells");
            update.setBoolean("partial", true);
            update.setLong("baseRevision", 7);
            update.setInteger("cellCount", 1);
            HologramState acknowledged = HologramState.apply(reopened, update);
            SimpleAssert.that(
                !acknowledged.data.getBoolean("open")
                    && !HologramPlacementReviewContext.returnToWorld(acknowledged.data),
                "consuming one-shot open intent cannot close the terminal UI on a later delta: " + job);
            complete.setLong("areaSelectionSequence", 1);
            HologramState refreshed = HologramState.apply(acknowledged, complete);
            SimpleAssert.that(
                !HologramPlacementReviewContext.returnToWorld(refreshed.data),
                "a later complete terminal snapshot also preserves the reopened UI: " + job);
        }
        for (int job : new int[] { 0, 1, 2 }) {
            NBTTagCompound active = placement(job);
            for (boolean locked : new boolean[] { false, true }) {
                active.setBoolean("virtualLocked", locked);
                SimpleAssert.that(
                    HologramPlacementReviewContext.returnToWorld(active),
                    "moving/locked previews and active jobs retain their existing world transition: " + job);
            }
            active.setBoolean("open", true);
            SimpleAssert.that(
                !HologramPlacementReviewContext.returnToWorld(active),
                "an explicit opening still shows an active placement: " + job);
        }
    }

    private static NBTTagCompound placement(int job) {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", "completed-placement");
        state.setString("generation", "completed-placement");
        state.setLong("planRevision", 7);
        state.setBoolean("virtual", true);
        state.setBoolean("virtualPlacement", true);
        state.setBoolean("virtualLocked", true);
        state.setBoolean("open", false);
        state.setLong("placementReviewNonce", 2);
        state.setInteger("job", job);
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("y", 64);
        cell.setString("status", "placed");
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        state.setTag("cells", cells);
        return state;
    }
}
