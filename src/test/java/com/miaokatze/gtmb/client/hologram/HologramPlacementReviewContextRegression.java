package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** First world lock stays in the world; an opened review receives background job and missing-material updates. */
public final class HologramPlacementReviewContextRegression {

    private HologramPlacementReviewContextRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
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
}
