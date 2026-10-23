package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Confirmation ownership, dismiss semantics and independent persistence without a client or GL. */
public final class HologramSelectionDiscardRegression {

    private HologramSelectionDiscardRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        answers();
        try {
            persistence();
        } catch (Exception failure) {
            throw new IllegalStateException("Selection discard preferences regression failed", failure);
        }
    }

    private static void answers() {
        List<String> calls = new ArrayList<>();
        boolean[] current = { true };
        HologramSelectionDiscardDecision no = question(current, calls);
        no.answer(false, true);
        no.answer(true, true);
        SimpleAssert.eq(Arrays.asList("close"), calls, "No or Escape preserves selection and never saves skip");
        calls.clear();
        HologramSelectionDiscardDecision yes = question(current, calls);
        yes.answer(true, false);
        yes.answer(true, true);
        SimpleAssert.eq(Arrays.asList("close", "discard"), calls, "Yes cancels exactly once without persisting skip");
        calls.clear();
        question(current, calls).answer(true, true);
        SimpleAssert.eq(Arrays.asList("close", "remember", "discard"), calls, "explicit skip applies to this discard");
        calls.clear();
        HologramSelectionDiscardDecision stale = question(current, calls);
        current[0] = false;
        stale.answer(true, true);
        SimpleAssert.eq(Arrays.asList("close"), calls, "world, tool or session replacement cannot cancel or save skip");
        calls.clear();
        current[0] = true;
        HologramSelectionDiscardDecision replaced = question(current, calls);
        replaced.retire();
        replaced.answer(true, true);
        SimpleAssert.that(calls.isEmpty(), "a retired screen cannot close its replacement or discard a new selection");
    }

    private static HologramSelectionDiscardDecision question(boolean[] current, List<String> calls) {
        return new HologramSelectionDiscardDecision(
            () -> current[0],
            () -> calls.add("close"),
            () -> calls.add("remember"),
            () -> calls.add("discard"));
    }

    private static void persistence() throws Exception {
        Path directory = Files.createTempDirectory("gtmb-selection-discard-");
        Path file = directory.resolve("preferences.properties");
        try {
            HologramEditorPreferences initial = new HologramEditorPreferences(file.toFile());
            SimpleAssert.that(!initial.selectionDiscardSuppressed(), "selection discard asks by default");
            initial.collapsed(true);
            initial.finish("independent-lesson", true);
            initial.suppressSelectionDiscard();
            HologramEditorPreferences restarted = new HologramEditorPreferences(file.toFile());
            SimpleAssert.that(restarted.selectionDiscardSuppressed(), "skip survives client restart");
            SimpleAssert
                .that(restarted.collapsed() && restarted.seen("independent-lesson"), "skip preserves guide data");
            restarted.collapsed(false);
            SimpleAssert.that(
                new HologramEditorPreferences(file.toFile()).selectionDiscardSuppressed(),
                "guide changes preserve selection discard preference");
            HologramEditorPreferences unwritable = new HologramEditorPreferences(directory.toFile());
            unwritable.suppressSelectionDiscard();
            SimpleAssert.that(unwritable.selectionDiscardSuppressed(), "read-only storage keeps current-run choice");
        } finally {
            Files.deleteIfExists(directory.resolveSibling(directory.getFileName() + ".pending"));
            Files.deleteIfExists(directory.resolve("preferences.properties.pending"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}
