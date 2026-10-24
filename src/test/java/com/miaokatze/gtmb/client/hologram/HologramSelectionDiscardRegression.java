package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Confirmation ownership, dismiss semantics and independent persistence without a client or GL. */
public final class HologramSelectionDiscardRegression {

    private HologramSelectionDiscardRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        answers();
        deferredSelectionAnswers();
        worldDeleteEdges();
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

    private static void deferredSelectionAnswers() {
        try {
            HologramAreaSelection selection = new HologramAreaSelection();
            selection.worldPoint(1, 0, 64, 0);
            selection.worldPoint(2, 1, 64, 1);
            selection.beginNext("add");
            selection.worldPoint(1, 5, 64, 5);
            selection.worldPoint(2, 6, 64, 6);
            HologramState captured = selectionOwner(selection);
            HologramState[] current = { captured };
            List<String> calls = new ArrayList<>();
            HologramUiDeferredAction deferred = new HologramUiDeferredAction();
            HologramSelectionDiscardDecision question = new HologramSelectionDiscardDecision(
                () -> HologramClient.selectionDiscardSnapshotMatches(captured, current[0]),
                () -> calls.add("close"),
                () -> calls.add("remember"),
                () -> calls.add("discard"));
            deferred.offer(() -> question.deleteLatest(selection::deleteLatest));
            NBTTagCompound decoration = new NBTTagCompound();
            decoration.setString("status", "a later frame's status");
            current[0] = current[0].withMetadata(decoration);
            SimpleAssert.that(current[0] != captured, "metadata publishes a new snapshot during modal input deferral");
            deferred.run(true);
            deferred.run(true);
            SimpleAssert.eq(Arrays.asList("close"), calls, "Delete closes the modal without discarding the session");
            SimpleAssert.eq(
                1,
                selection.regionSnapshot()
                    .tagCount(),
                "deferred Delete removes only the most recent region once");
            selection.undoRegion();
            SimpleAssert.eq(
                2,
                selection.regionSnapshot()
                    .tagCount(),
                "Delete preserves usable selection history after returning");
            selection.redoRegion();
            SimpleAssert.eq(
                1,
                selection.regionSnapshot()
                    .tagCount(),
                "redo remains available after the modal edit");

            for (String key : new String[] { "session", "toolId", "dimension", "planRevision", "uiSequence",
                "areaSelectionSequence", "areaSelecting", "areaImporting" }) {
                NBTTagCompound replacement = new NBTTagCompound();
                if ("session".equals(key) || "toolId".equals(key)) replacement.setString(key, "replacement");
                else if ("dimension".equals(key)) replacement.setInteger(key, 1);
                else if ("areaSelecting".equals(key)) replacement.setBoolean(key, false);
                else if ("areaImporting".equals(key)) replacement.setBoolean(key, true);
                else replacement.setLong(key, captured.data.getLong(key) + 1);
                if ("session".equals(key)) {
                    // Metadata decorators retain their original session by contract. A new session
                    // must arrive through the same complete-baseline path used by network publication.
                    NBTTagCompound baseline = (NBTTagCompound) captured.data.copy();
                    baseline.setString("session", "replacement");
                    current[0] = HologramState.apply(captured, baseline);
                    SimpleAssert.eq("replacement", current[0].session, "session replacement fixture changes its owner");
                } else current[0] = captured.withMetadata(replacement);
                int before = selection.regionSnapshot()
                    .tagCount();
                HologramSelectionDiscardDecision stale = new HologramSelectionDiscardDecision(
                    () -> HologramClient.selectionDiscardSnapshotMatches(captured, current[0]),
                    () -> {},
                    () -> calls.add("remember-stale"),
                    () -> calls.add("discard-stale"));
                deferred.offer(() -> stale.deleteLatest(selection::deleteLatest));
                deferred.run(true);
                SimpleAssert.eq(
                    before,
                    selection.regionSnapshot()
                        .tagCount(),
                    key + " replacement cannot edit another selection");
            }
            current[0] = captured.withMetadata(decoration);
            calls.clear();
            HologramSelectionDiscardDecision yes = new HologramSelectionDiscardDecision(
                () -> HologramClient.selectionDiscardSnapshotMatches(captured, current[0]),
                () -> calls.add("close"),
                () -> calls.add("remember"),
                () -> calls.add("discard"));
            deferred.offer(() -> yes.answer(true, true));
            current[0] = current[0].withMetadata(new NBTTagCompound());
            deferred.run(true);
            SimpleAssert.eq(
                Arrays.asList("close", "remember", "discard"),
                calls,
                "Yes and persistent Yes remain usable across unrelated metadata publications");
            NBTTagCompound unidentified = new NBTTagCompound();
            unidentified.setString("toolId", "");
            SimpleAssert.that(
                HologramClient.selectionDiscardSnapshotMatches(captured.withMetadata(unidentified), current[0]),
                "first server tool UUID registration preserves the captured selection owner");
        } catch (java.io.IOException invalid) {
            throw new AssertionError("Cannot prepare modal selection history", invalid);
        }
    }

    private static HologramState selectionOwner(HologramAreaSelection selection) {
        NBTTagCompound data = selection.snapshot();
        data.setString("session", "selection-owner");
        data.setString("toolId", "projector-owner");
        data.setInteger("dimension", 0);
        data.setLong("planRevision", 7);
        data.setLong("uiSequence", 12);
        data.setLong("areaSelectionSequence", 3);
        data.setBoolean("areaSelecting", true);
        return new HologramState(data);
    }

    private static void worldDeleteEdges() {
        try {
            HologramAreaSelection selection = new HologramAreaSelection();
            for (int i = 0; i < 4; i++) {
                if (i > 0) selection.beginNext("add");
                selection.worldPoint(1, i * 3, 64, 0);
                selection.worldPoint(2, i * 3 + 1, 64, 1);
            }
            HologramSelectionInput input = new HologramSelectionInput();
            input.deleteTick(selection, false, false, true);
            SimpleAssert.that(
                input.deleteKey(selection, true, true, false),
                "fresh Delete immediately after No is admitted before the first active tick");
            input.deleteKey(selection, true, false, false);
            input.deleteTick(selection, true, false, true);
            SimpleAssert.eq(
                3,
                selection.regionSnapshot()
                    .tagCount(),
                "a complete press and release between ticks still deletes exactly one latest region");
            SimpleAssert.that(input.deleteKey(selection, true, true, false), "the next genuine press is usable");
            SimpleAssert.that(
                !input.deleteTick(selection, true, true, false),
                "polling the same event press cannot delete a second region");
            SimpleAssert
                .that(!input.deleteKey(selection, true, true, true), "keyboard repeat cannot delete another region");
            input.deleteKey(selection, true, false, false);
            SimpleAssert.eq(
                2,
                selection.regionSnapshot()
                    .tagCount(),
                "held press and repeat retain earlier regions");

            input.deleteTick(selection, false, true, true);
            SimpleAssert.that(
                !input.deleteTick(selection, true, true, true),
                "a Delete held across a screen change cannot become a fresh world press");
            SimpleAssert.that(
                !input.deleteKey(selection, true, true, true),
                "a repeat arriving after the modal closes cannot become a fresh press");
            input.deleteKey(selection, true, false, false);
            SimpleAssert.that(
                input.deleteTick(selection, true, true, false),
                "tick polling remains a compatible press route after release");
            SimpleAssert.that(
                !input.deleteKey(selection, true, true, false),
                "a key event for an already polled edge cannot double delete");
            input.deleteKey(selection, true, false, false);
            SimpleAssert.eq(
                1,
                selection.regionSnapshot()
                    .tagCount(),
                "both input routes share one physical press edge");
            SimpleAssert.that(
                !input.deleteKey(selection, false, true, false),
                "disabled selection input does not edit geometry");
            SimpleAssert.that(
                !input.deleteTick(selection, true, true, false),
                "re-enabling a held disabled press does not edit geometry");
            input.deleteKey(selection, true, false, false);
            SimpleAssert.that(input.deleteKey(selection, true, true, false), "release restores the next valid Delete");
            SimpleAssert.that(!selection.hasSelectionContent(), "the final fresh press deletes the final region");
            selection.undoRegion();
            SimpleAssert.that(selection.complete(), "event input retains normal undo history");
        } catch (java.io.IOException invalid) {
            throw new AssertionError("Cannot prepare world Delete history", invalid);
        }
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
