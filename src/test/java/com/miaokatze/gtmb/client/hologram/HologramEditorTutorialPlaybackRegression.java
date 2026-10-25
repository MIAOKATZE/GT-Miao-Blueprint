package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.client.hologram.HologramEditorLessons.Operation;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Teaching graph ownership and delayed callbacks must survive backwards navigation without replaying stale work. */
public final class HologramEditorTutorialPlaybackRegression {

    private HologramEditorTutorialPlaybackRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramEditorTutorialPlaybackRegression.class, args)) return;
        run();
        System.out.println("Tutorial playback regression: PASS");
        HologramEditorTutorialLessonsRegression.run();
        System.out.println("Tutorial lessons regression: PASS");
        HologramEditorTutorialGeometryRegression.run();
        System.out.println("Tutorial geometry regression: PASS");
    }

    public static void run() {
        retiredCallbacksCannotMutateRestoredState();
        retiredFramesCannotRecreateReleasedResources();
        emptyWorkspaceHasNoCapturedDocument();
        loadingDocumentReplacesGeometryWithoutMutatingLibrary();
        inputHintsMatchWorldControls();
        manualCoursesRememberIndependentOutcomes();
    }

    private static void manualCoursesRememberIndependentOutcomes() {
        java.nio.file.Path directory = null;
        try {
            directory = java.nio.file.Files.createTempDirectory("gtmb-tutorial-preferences-");
            java.io.File file = directory.resolve("guide.properties")
                .toFile();
            HologramEditorPreferences first = new HologramEditorPreferences(file);
            first.finish("text", false);
            HologramEditorPreferences reopened = new HologramEditorPreferences(file);
            SimpleAssert.that(
                reopened.seen("text"),
                "skipping a first manual text lesson suppresses repeated first-use prompts");
            SimpleAssert.that(
                !reopened.seen("picture") && !reopened.seen("blueprint"),
                "attachment lesson outcomes do not mark other courses as seen");
            reopened.finish("text", true);
            reopened.finish("text", false);
            reopened.finish("picture", true);
            reopened.finish("picture", false);
            HologramEditorPreferences afterReplay = new HologramEditorPreferences(file);
            SimpleAssert.eq(
                "completed",
                afterReplay.outcome("text"),
                "completing a replay upgrades a skipped course and later skipping preserves completion");
            SimpleAssert.eq(
                "completed",
                afterReplay.outcome("picture"),
                "a skipped picture replay does not downgrade its saved completion");
        } catch (java.io.IOException failure) {
            throw new AssertionError("Tutorial preferences must persist independent manual lesson outcomes", failure);
        } finally {
            if (directory != null) try {
                java.nio.file.Files.deleteIfExists(directory.resolve("guide.properties.pending"));
                java.nio.file.Files.deleteIfExists(directory.resolve("guide.properties"));
                java.nio.file.Files.deleteIfExists(directory);
            } catch (java.io.IOException failure) {
                throw new AssertionError("Tutorial preference regression cleanup failed", failure);
            }
        }
    }

    private static void retiredCallbacksCannotMutateRestoredState() {
        HologramTutorialGeneration generation = new HologramTutorialGeneration();
        HologramUiDeferredAction queue = new HologramUiDeferredAction();
        int[] applied = { 0 };
        queue.offer(generation.guard(() -> {
            applied[0]++;
            queue.offer(generation.guard(() -> applied[0] += 100));
        }));
        queue.run(true);
        generation.invalidate();
        queue.run(true);
        SimpleAssert
            .eq(1, applied[0], "a native callback deferred inside an old action cannot mutate a restored graph");
        Runnable retired = generation.guard(() -> applied[0] += 1000);
        generation.invalidate();
        queue.offer(generation.guard(() -> applied[0]++));
        queue.run(true);
        retired.run();
        SimpleAssert.eq(2, applied[0], "new playback works while callbacks retired by restart or close remain inert");
    }

    private static void retiredFramesCannotRecreateReleasedResources() {
        HologramTutorialGeneration lifetime = new HologramTutorialGeneration();
        long closingTree = lifetime.current();
        int[] allocations = { 0 };
        Runnable draw = () -> { if (lifetime.isCurrent(closingTree)) allocations[0]++; };
        draw.run();
        lifetime.invalidate();
        draw.run();
        SimpleAssert.eq(1, allocations[0], "an animated closing tree cannot recreate a retired raw renderer");
        long nextTree = lifetime.current();
        SimpleAssert.that(lifetime.isCurrent(nextTree), "the next lesson owns a fresh presentation lifetime");
        int storageBottom = 12 + HologramEditorLessonStage.storageDialogHeight(600);
        int navigationTop = 600 - 6 - 20;
        SimpleAssert.that(
            storageBottom + 12 <= navigationTop,
            "the teaching storage panel leaves the four navigation buttons and a visible gap outside its footer");
    }

    private static NBTTagCompound document(String id, int x) {
        NBTTagCompound data = new NBTTagCompound();
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        data.setTag("cells", cells);
        NBTTagCompound structure = new NBTTagCompound();
        structure.setString("id", "member-" + id);
        NBTTagList structures = new NBTTagList();
        structures.appendTag(structure);
        data.setTag("structures", structures);
        data.setString("selectedPresetId", id);
        data.setString("virtualPresetId", id);
        data.setString("blueprintSavedId", id);
        data.setString("virtualPresetName", id);
        data.setString("blueprintSource", "saved");
        data.setString("openPurpose", "blueprintEdit");
        data.setString("editorWorkspace", "blueprint");
        data.setBoolean("virtual", true);
        data.setBoolean("blueprintSaved", true);
        data.setBoolean("blueprintDirty", false);
        data.setBoolean("blueprintProcessingRequired", true);
        data.setInteger("selected", -1);
        data.setIntArray("selectedIndices", new int[0]);
        return data;
    }

    private static void emptyWorkspaceHasNoCapturedDocument() {
        NBTTagCompound captured = document("previous", 9);
        captured.setBoolean("blueprintDirty", true);
        captured.setTag("processing", new NBTTagCompound());
        NBTTagCompound before = (NBTTagCompound) captured.copy();
        NBTTagCompound empty = HologramEditorTutorialSamples.emptyBlueprint(captured);
        SimpleAssert.eq(
            0,
            empty.getTagList("cells", 10)
                .tagCount(),
            "blueprint teaching begins with an empty canvas");
        SimpleAssert.eq(
            0,
            empty.getTagList("structures", 10)
                .tagCount(),
            "no source members precede the first library load");
        SimpleAssert.eq("", empty.getString("selectedPresetId"), "an empty workspace has no loaded library identity");
        SimpleAssert.that(
            !empty.getBoolean("blueprintDirty") && !empty.getBoolean("blueprintSaved"),
            "empty geometry has neither pending edits nor a loaded saved document");
        SimpleAssert.that(
            !empty.hasKey("processing") && !empty.getBoolean("blueprintProcessingRequired"),
            "the empty editor does not inherit a captured document's processing report");
        SimpleAssert.that(before.equals(captured), "creating the workspace leaves its library source immutable");
    }

    private static void loadingDocumentReplacesGeometryWithoutMutatingLibrary() {
        NBTTagCompound saved = document("second", 42), before = (NBTTagCompound) saved.copy();
        NBTTagCompound previous = document("first", -17);
        previous.setString("session", "teaching");
        previous.setLong("planRevision", 8);
        previous.setBoolean("blueprintDirty", true);
        NBTTagList rows = new NBTTagList();
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", "second");
        rows.appendTag(row);
        previous.setTag("presets", rows);
        NBTTagCompound loaded = HologramEditorTutorialSamples.openBlueprintDocument(saved, previous);
        SimpleAssert.eq(
            42,
            loaded.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .getInteger("x"),
            "opening a library document replaces the actual geometry");
        SimpleAssert
            .eq("second", loaded.getString("selectedPresetId"), "loaded source identity follows the selected row");
        SimpleAssert
            .eq("member-second", loaded.getString("tutorialStructureId"), "selection teaching follows the new source");
        SimpleAssert.eq("teaching", loaded.getString("session"), "document loading retains the isolated session owner");
        SimpleAssert.eq(9L, loaded.getLong("planRevision"), "a loaded document advances the current editing revision");
        SimpleAssert.that(
            !loaded.getBoolean("blueprintDirty") && loaded.getBoolean("blueprintProcessingRequired"),
            "loading a clean library document does not claim processing completion");
        loaded.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setInteger("x", 99);
        SimpleAssert.that(before.equals(saved), "editing the loaded copy cannot change the teaching library payload");
        SimpleAssert.eq(
            -17,
            previous.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .getInteger("x"),
            "loading a different document does not modify the captured previous checkpoint");
    }

    private static void inputHintsMatchWorldControls() {
        SimpleAssert.eq(
            "Ctrl + 右键",
            HologramEditorTutorial.inputHint(Operation.SECOND_CORNER, true),
            "isometric second corners show the actual modifier and mouse button");
        SimpleAssert.eq(
            "Shift + 左键 · 松开 Ctrl",
            HologramEditorTutorial.inputHint(Operation.BOX_SUBMIT, true),
            "isometric submission explicitly releases Ctrl and uses Shift-left");
        SimpleAssert.eq(
            "右键",
            HologramEditorTutorial.inputHint(Operation.CUT_PLACE_REVIEW, false),
            "perspective placement review uses a second right click");
        SimpleAssert.eq(
            "左键",
            HologramEditorTutorial.inputHint(Operation.CUT_PLACE, true),
            "the final native confirm button is a normal left click");
    }
}
