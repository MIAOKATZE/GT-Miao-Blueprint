// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramDraftSectionReviewRegression {

    private static final String LEFT = "{\"x\":-17,\"y\":1,\"z\":0,\"block\":\"minecraft:stone\"}";
    private static final String EDGE = "{\"x\":-16,\"y\":1,\"z\":0,\"block\":\"minecraft:glass\"}";
    private static final String RIGHT = "{\"x\":32,\"y\":2,\"z\":0,\"block\":\"minecraft:stone\"}";

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramDraftSectionReviewRegression.class, args)) return;
        run();
        System.out.println("PASS local draft stage, canonical root, three section routes, no partial or stale read");
    }

    static void run() throws Exception {
        byte[] original = data(EDGE, RIGHT, LEFT);
        var draft = HologramLocalBlueprintDocument.open(original, "");
        var frozen = draft.freeze();
        var view = new HologramState(draft.snapshot());
        var review = HologramDraftSectionReview.stage(frozen, view, () -> true);
        SimpleAssert.eq(3, review.pages(), "sparse draft emits exactly three pages, no empty intervening sections");
        SimpleAssert.eq(3, review.cells, "actual row count preserved");
        var first = review.read(0, () -> true);
        var last = review.read(2, () -> true);
        var next = review.read(1, () -> true);
        SimpleAssert.eq(-2, first.getInteger("sectionX"), "negative section floor");
        SimpleAssert.eq(2, last.getInteger("sectionX"), "last section");
        SimpleAssert.eq(-1, next.getInteger("sectionX"), "middle section");
        var row = first.getTagList("cells", 10).getCompoundTagAt(0);
        SimpleAssert.eq(15, row.getInteger("x"), "page-local slot at negative boundary");
        SimpleAssert.eq("minecraft:stone", row.getString("wantId"), "material preserved");
        SimpleAssert.eq("minecraft:glass", next.getTagList("cells", 10).getCompoundTagAt(0).getString("wantId"),
            "different page uses its exact material");
        var preparation = new HologramState.Preparation(first, true, null);
        while (!preparation.step(5, Long.MAX_VALUE)) {}
        SimpleAssert.eq(1, preparation.result().cells.size(), "real page enters normal detached display preparation");
        first.getTagList("cells", 10).getCompoundTagAt(0).setString("wantId", "test:changed");
        SimpleAssert.eq("minecraft:stone",
            review.read(0, () -> true).getTagList("cells", 10).getCompoundTagAt(0).getString("wantId"),
            "page output cannot mutate source or future page");
        SimpleAssert.that(Arrays.equals(original, frozen.artifact()), "nothing writes the original");

        var reordered = HologramLocalBlueprintDocument.open(data(LEFT, EDGE, RIGHT), "");
        var changed = HologramLocalBlueprintDocument.open(data(LEFT, EDGE.replace("glass", "stone"), RIGHT), "");
        SimpleAssert.that(review.sameIdentity(HologramDraftSectionReview.stage(reordered.freeze(),
            new HologramState(reordered.snapshot()), () -> true)), "canonical digest ignores input row order");
        SimpleAssert.that(!review.sameIdentity(HologramDraftSectionReview.stage(changed.freeze(),
            new HologramState(changed.snapshot()), () -> true)), "same counts do not make different geometry equal");
        denied(() -> HologramDraftSectionReview.stage(frozen, new HologramState(changed.snapshot()), () -> true), false);
        var excluded = HologramLocalBlueprintDocument.open(data(LEFT.replace("stone\"}", "stone\",\"excluded\":true}"),
            EDGE), "");
        denied(() -> HologramDraftSectionReview.stage(excluded.freeze(),
            new HologramState(excluded.snapshot()), () -> true), false);
        var stateful = HologramLocalBlueprintDocument.open(data(LEFT.replace("stone\"}", "stone\",\"metadata\":1}"),
            EDGE), "");
        denied(() -> HologramDraftSectionReview.stage(stateful.freeze(),
            new HologramState(stateful.snapshot()), () -> true), false);
        var tile = HologramLocalBlueprintDocument.open(data(LEFT.replace("stone", "chest"), EDGE), "");
        denied(() -> HologramDraftSectionReview.stage(tile.freeze(), new HologramState(tile.snapshot()), () -> true),
            false);
        String[] scattered = new String[HologramDraftSectionReview.SECTIONS + 1];
        for (int i = 0; i < scattered.length; i++)
            scattered[i] = LEFT.replace("-17", String.valueOf(i * 16));
        var tooWide = HologramLocalBlueprintDocument.open(data(scattered), "");
        denied(() -> HologramDraftSectionReview.stage(tooWide.freeze(), new HologramState(tooWide.snapshot()),
            () -> true), false);
        denied(() -> review.read(3, () -> true), false);
        denied(() -> review.read(0, () -> false), true);
        denied(() -> HologramDraftSectionReview.stage(frozen, view, () -> false), true);
    }

    private static byte[] data(String... rows) {
        return ("{\"blueprint\":{\"schema\":1,\"cells\":[" + String.join(",", rows) + "]}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static void denied(Checked action, boolean cancelled) throws Exception {
        try {
            action.run();
        } catch (IOException rejected) {
            SimpleAssert.eq(cancelled, rejected instanceof InterruptedIOException, "exact rejection class");
            return;
        }
        throw new AssertionError("unexpected acceptance");
    }

    private interface Checked {

        void run() throws Exception;
    }
}
