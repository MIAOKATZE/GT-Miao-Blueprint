// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises detached display decoding: unknown construction identities stay outside validation and authority. */
public final class HologramLocalPreviewRegression {

    public static void run() throws Exception {
        rejectStaleAuthoringOwner();
        compactDisplay();
        compactDirectDisplay();
        packedEditorParity();
        frozenDraftParity();
        singleCellAnnotation();
        compactCoordinateCompatibility();
        compactDenseRegion();
        sharedPlainDisplay();
        sourceReuse();
        savedPrivateMaterialSource();
        indexedMaterialSourceCapture();
        HologramLibraryMaterialForecastRegression.run();
        HologramLibrarySecondUseRegression.run();
        HologramLibraryDescriptionViewRegression.run();
        earlyDisplay();
        displayPicture();
        uploadRepresentation();
        NBTTagCompound review = HologramLocalPlacement.reviewOptions();
        SimpleAssert.eq(
            true,
            review.getBoolean("openReview"),
            "explicit local submission retains access to the existing server review");
        SimpleAssert.eq(false, review.getBoolean("executeIntent"), "opening review cannot request construction");
        SimpleAssert.eq(false, review.hasKey("acknowledgedMaterials"), "display never invents a material acceptance");
        String json = "{\"blueprint\":{\"schema\":999,\"retainNbt\":false,\"retainCovers\":true,"
            + "\"retainedNbtKeys\":[\"configuration\"],\"cells\":["
            + "{\"x\":2,\"y\":-3,\"z\":4,\"block\":\"not_installed:display_only\","
            + "\"item\":\"not_installed:display_only\",\"facing\":5},"
            + "{\"x\":1,\"y\":1,\"z\":1,\"excluded\":true}]}}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8), original = bytes.clone();
        NBTTagCompound display = HologramBlueprintPreview.snapshot(bytes);
        SimpleAssert.that(Arrays.equals(original, bytes), "preview never mutates the original portable source");
        NBTTagList rows = display.getTagList("cells", 10);
        SimpleAssert.eq(1, rows.tagCount(), "excluded cells do not create display geometry");
        SimpleAssert.eq(
            "not_installed:display_only",
            rows.getCompoundTagAt(0)
                .getString("wantId"),
            "detached decode does not ask construction registry validation to approve the source");
        SimpleAssert.eq(
            -3,
            rows.getCompoundTagAt(0)
                .getInteger("dy"),
            "display preserves relative source offsets");
        SimpleAssert.eq(false, display.getBoolean("retainNbt"), "local demand receives source NBT selection");
        SimpleAssert.eq(true, display.getBoolean("retainCovers"), "local demand receives source cover selection");
        SimpleAssert.eq(
            "configuration",
            display.getTagList("retainedNbtKeys", 8)
                .getStringTagAt(0),
            "local demand receives selected NBT roots");
        SimpleAssert.eq(false, display.hasKey("planRevision"), "display decoding grants no reviewed plan revision");
        SimpleAssert
            .eq(false, display.hasKey("placementReviewNonce"), "display decoding grants no construction review");
        expectFailure(new String(new char[65]).replace('\0', '{') + new String(new char[65]).replace('\0', '}'));
        expectFailure("{\"blueprint\":{\"cells\":[{\"x\":60000001}]}}");
        expectFailure("{\"blueprint\":{\"cells\":[]}}");
        expectFailure("{\"blueprint\":{\"cells\":[{\"tileNbt\":\"not base64\"}]}}");
    }

    private static void rejectStaleAuthoringOwner() {
        Object world = new Object();
        Object connection = new Object();
        UUID actor = new UUID(0L, 1L);
        HologramLocalBlueprintWorkspace.PresentationOwner pending = new HologramLocalBlueprintWorkspace.PresentationOwner(
            world,
            connection,
            actor,
            "local-editor",
            7L);
        SimpleAssert.that(
            pending.sameSource(world, connection, new UUID(0L, 1L), "local-editor", 7L),
            "the same actor can receive the prepared view");
        SimpleAssert.that(
            !pending.sameSource(world, new Object(), actor, "local-editor", 7L),
            "a preposted view cannot commit to a replacement connection with the same world and editor revision");
        SimpleAssert.that(
            !pending.sameSource(world, connection, new UUID(0L, 2L), "local-editor", 7L),
            "a preposted view cannot commit to a different actor with the same world and editor revision");
        SimpleAssert.that(
            !pending.sameSource(world, connection, actor, "local-editor-next", 7L)
                && !pending.sameSource(world, connection, actor, "local-editor", 8L),
            "same-client completion does not override a different editor source or revision");
        SimpleAssert.that(
            !pending.sameClient(new Object(), connection, actor) && !pending.sameClient(world, null, actor)
                && !pending.sameClient(world, connection, null),
            "an absent identity or replaced world cannot receive a delayed save presentation");
    }

    private static void compactDisplay() throws Exception {
        NBTTagCompound configuration = new NBTTagCompound();
        configuration.setString("configuration", "exact local display payload");
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(configuration));
        String policy = "\"schema\":2,\"retainNbt\":true,\"retainCovers\":true,";
        String legacy = "{\"schema\":2,\"blueprint\":{" + policy
            + "\"cells\":[{\"x\":1,\"y\":2,\"z\":3,\"block\":\"missing:display\",\"itemNbt\":\""
            + encoded
            + "\"}],\"palette\":[]}}";
        String compact = "{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{" + policy
            + "\"cells\":[[0,{\"x\":1,\"y\":2,\"z\":3}]],\"palette\":[]},"
            + "\"materials\":[{\"block\":\"missing:display\",\"itemNbt\":0}],\"nbt\":[\""
            + encoded
            + "\"]}";
        SimpleAssert.eq(
            HologramBlueprintPreview.snapshot(legacy.getBytes(StandardCharsets.UTF_8)),
            HologramBlueprintPreview.snapshot(compact.getBytes(StandardCharsets.UTF_8)),
            "compact material and NBT references produce exactly the same detached display");
    }

    private static void frozenDraftParity() throws Exception {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setByteArray("extension", new byte[] { 4, -9, 0 });
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(payload));
        String json = "{\"schema\":2,\"extension\":{\"opaque\":true},\"blueprint\":{\"schema\":1,\"cells\":["
            + "{\"x\":-17,\"y\":-2,\"z\":16,\"item\":\"minecraft:stonebrick\",\"damage\":1,"
            + "\"itemNbt\":\""
            + encoded
            + "\",\"facing\":4},"
            + "{\"x\":-16,\"y\":-2,\"z\":16,\"block\":\"missing:excluded\",\"excluded\":true}]}}";
        byte[] original = json.getBytes(StandardCharsets.UTF_8);
        HologramLocalBlueprintDocument document = HologramLocalBlueprintDocument.open(original, "");
        var frozen = document.freeze();
        NBTTagCompound oldCapture = HologramBlueprintPreview.snapshot(original);
        SimpleAssert.eq(
            oldCapture,
            HologramBlueprintPreview.snapshot(frozen, () -> true),
            "typed display preserves legacy coordinates, material, facing, policy, NBT and exclusions");
        var input = HologramBlueprintPreview.displayInput(frozen, () -> true, null);
        HologramState.Preparation preparation = new HologramState.Preparation(input.snapshot, true, input.cells);
        while (!preparation.step(5, Long.MAX_VALUE)) {}
        for (int i = 0; i < oldCapture.getTagList("cells", 10)
            .tagCount(); i++)
            HologramBlueprintPreview.completeCell(
                oldCapture.getTagList("cells", 10)
                    .getCompoundTagAt(i));
        SimpleAssert.eq(
            new HologramState(oldCapture).fullData(),
            preparation.result()
                .fullData(),
            "current draft compact state matches the artifact preview's native rows");

        NBTTagCompound edit = new NBTTagCompound();
        edit.setString("prompt", "new author note");
        document.apply("editBlueprintPrompt", edit);
        NBTTagCompound options = new NBTTagCompound();
        options.setString("name", "Typed save fixture");
        String saveId = "local_11111111-1111-1111-1111-111111111111";
        var save = document.prepareSaveDraft(options, "Author", java.util.UUID.fromString(saveId.substring(6)), saveId);
        byte[] sourceJson = save.artifact();
        SimpleAssert.eq(
            HologramBlueprintPreview.snapshot(sourceJson),
            HologramBlueprintPreview.snapshot(save, () -> true),
            "save PNG captures the same original geometry as decoding the save version's exact bytes");
        document.saved(document.revision(), save, sourceJson);
        SimpleAssert.that(
            !document.dirty() && Arrays.equals(sourceJson, document.artifact()),
            "a committed frozen save installs the verified byte representation and clears dirty");
        SimpleAssert.that(
            Arrays.equals(original, frozen.artifact()),
            "the already frozen placement remains byte-exact after later edits and saves");
    }

    private static void openingCamera(HologramState prefix, HologramState full) {
        var area = new com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates(11, 23, 240, 170);
        var resized = new com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates(11, 24, 240, 170);
        var view = new HologramOpeningPreview(prefix, Math.PI / 4);
        SimpleAssert.that(
            !view.navigable(prefix, area, 7) && !view.press(20, 40, 0),
            "a decoded prefix is not a drawn camera frame");
        view.observe(full, area, 7, 8, 114);
        SimpleAssert
            .that(!view.navigable(prefix, area, 7), "an installed or retired frame cannot certify another prefix");
        view.observe(prefix, area, 7, 8, 114);
        SimpleAssert.that(
            view.navigable(prefix, area, 7) && !view.navigable(full, area, 7)
                && !view.navigable(prefix, resized, 7)
                && !view.navigable(prefix, area, 8),
            "opening navigation requires the actual source, GUI rectangle and resource generation");
        view.observe(null, area, 7, 0, 0);
        SimpleAssert.that(view.navigable(null, area, 7), "another draw of the same prefix may still be in progress");
        var start = view.pose();
        SimpleAssert.that(!view.press(20, 40, 1) && view.press(20, 40, 0), "only left/middle own opening gestures");
        view.release(21, 41, 0);
        SimpleAssert.eq(start, view.pose(), "a short left release neither rotates nor enters any edit handler");
        SimpleAssert.that(view.press(20, 40, 0), "a rendered prefix starts a left drag");
        view.drag(22, 40, 0);
        view.drag(26, 42, 0);
        view.release(28, 43, 0);
        SimpleAssert.that(
            view.pose()
                .yaw() < start.yaw()
                && view.pose()
                    .pitch() > start.pitch(),
            "successive input coordinates rotate without waiting for another draw");
        SimpleAssert.that(view.press(20, 40, 2), "a rendered prefix starts a middle drag");
        view.release(22, 49, 2);
        SimpleAssert.that(
            view.pose()
                .y() != 0,
            "middle drag pans in the same presented coordinate frame");
        view.scroll(1);
        view.fixed(0, Math.PI / 2);
        view.move(.025, 1, 1);
        SimpleAssert.that(
            view.pose()
                .zoom() > 1
                && view.pose()
                    .pitch() == Math.PI / 2,
            "wheel and camera commands change only the independent opening pose");
        SimpleAssert
            .eq(view.pose(), view.forInstalled(full), "whole-source fit and all six pose fields survive install");
        SimpleAssert.eq(null, view.forInstalled(prefix), "a partial state never becomes the installed owner");
        NBTTagCompound shifted = new NBTTagCompound();
        shifted.setInteger("x", 42);
        SimpleAssert.eq(
            null,
            view.forInstalled(full.withMetadata(shifted)),
            "another anchor cannot adopt the old coordinate frame");
        SimpleAssert.that(view.press(20, 40, 0), "a gesture may be in flight when install wins");
        var installed = view.forInstalled(full);
        view.observe(prefix, resized, 8, 8, 114);
        view.release(50, 50, 0);
        SimpleAssert.eq(installed, view.pose(), "a fresh draw after a resize or reload cannot revive the old gesture");
        SimpleAssert.that(view.press(20, 40, 0), "the new rectangle can own a new press");
        view.invalidate();
        view.release(50, 50, 0);
        SimpleAssert.that(!view.navigable(prefix, resized, 8), "a disposed scene does not keep navigation authority");
        SimpleAssert.eq(installed, view.pose(), "a retired opening press cannot transfer its release to the new view");
    }

    private static void packedEditorParity() throws Exception {
        String descriptor = "\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\","
            + "\"structureIds\":[\"controller\"],\"prompt\":\"member note\","
            + "\"allowedTargets\":[{\"item\":\"minecraft:stone\",\"damage\":0,\"futureTarget\":17}],"
            + "\"futureCell\":{\"type\":\"preserved\"}";
        String json = "{\"schema\":2,\"futureEnvelope\":37,\"blueprint\":{\"schema\":2,\"cells\":["
            + "{\"x\":-48,\"y\":-2,\"z\":3,"
            + descriptor
            + "},"
            + "{\"x\":64,\"y\":5,\"z\":-2,\"block\":\"missing:excluded\",\"excluded\":true},"
            + "{\"x\":9,\"y\":-2,\"z\":3,"
            + descriptor
            + "}],"
            + "\"structures\":[{\"id\":\"controller\",\"name\":\"Fixture\"}],\"palette\":[]}}";
        byte[] exact = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.recompress(
            json.getBytes(StandardCharsets.UTF_8),
            com.miaokatze.gtmb.hologram.HologramBlueprintArtifact.MAX_JSON_BYTES);
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            HologramLocalBlueprintDocument.openForEditor(exact, "", current::get, editor -> {
                SimpleAssert.eq(
                    3,
                    editor.rows()
                        .cells(),
                    "staged render input already owns exact whole source coordinates");
                current.set(false);
            });
            throw new AssertionError("The separately staged author binding ignored cancellation");
        } catch (IOException cancelled) {
            SimpleAssert.that(!current.get(), "validated render input publishes before editable materialization");
        }
        var opened = HologramLocalBlueprintDocument.openForEditor(exact, "", () -> true);
        SimpleAssert
            .that(opened.editor() != null, "the persisted coordinate-first compact artifact uses packed editing");
        HologramState.Preparation packed = new HologramState.Preparation(
            opened.editor()
                .metadata(),
            true,
            new HologramBlueprintPreview.CompactCells(opened.editor()));
        SimpleAssert.that(!packed.step(1, Long.MAX_VALUE), "a packed editor produces a bounded initial prefix");
        HologramState prefix = packed.firstDisplay();
        SimpleAssert.that(
            prefix.data.getBoolean("displayPartial") && prefix.cells.get(0).x == -48,
            "the prefix has the real document ordinal and stays nonauthoritative");
        while (!packed.step(5, Long.MAX_VALUE)) {}
        HologramState.Preparation expanded = new HologramState.Preparation(
            opened.document()
                .snapshot(),
            true);
        while (!expanded.step(5, Long.MAX_VALUE)) {}
        SimpleAssert.eq(
            expanded.result()
                .fullData(),
            packed.result()
                .fullData(),
            "packed and expanded editor views retain the same native appearance, annotations, disabled and missing cells");
        SimpleAssert.eq(
            expanded.result().structureMembers,
            packed.result().structureMembers,
            "rich packed layout retains exact membership rather than the library's implicit group");
        SimpleAssert.eq(
            expanded.result().regions.bounds()[3],
            prefix.regions.bounds()[3],
            "early camera fits the whole target");
        openingCamera(prefix, packed.result());

        NBTTagCompound edit = new NBTTagCompound();
        edit.setString("prompt", "new blueprint note");
        opened.document()
            .apply("editBlueprintPrompt", edit);
        var saved = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.parse(
            opened.document()
                .artifact());
        SimpleAssert.eq(
            37,
            saved.get("futureEnvelope")
                .getAsInt(),
            "the compact reader retains future source metadata");
        var row = saved.getAsJsonObject("blueprint")
            .getAsJsonArray("cells")
            .get(2)
            .getAsJsonObject();
        SimpleAssert.eq(
            "preserved",
            row.getAsJsonObject("futureCell")
                .get("type")
                .getAsString(),
            "a repeated compact descriptor keeps its per-cell opaque fields on edit");
        SimpleAssert.eq(
            17,
            row.getAsJsonArray("allowedTargets")
                .get(0)
                .getAsJsonObject()
                .get("futureTarget")
                .getAsInt(),
            "typed shared material retains opaque candidate properties on edit");
    }

    private static void singleCellAnnotation() throws Exception {
        String json = "{\"cells\":[{\"x\":0,\"block\":\"minecraft:stone\"},"
            + "{\"x\":32,\"block\":\"minecraft:stone\"}]}";
        HologramLocalBlueprintDocument document = HologramLocalBlueprintDocument
            .open(json.getBytes(StandardCharsets.UTF_8), "");
        HologramState.Preparation preparation = new HologramState.Preparation(document.snapshot(), true);
        while (!preparation.step(5, Long.MAX_VALUE)) {}
        HologramState baseline = preparation.result();
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("selected", 0);
        request.setString("prompt", "author note");
        request.setBoolean("excluded", true);
        document.apply("editCellFlags", request);
        HologramState patched = HologramLocalBlueprintWorkspace
            .applyAnnotation(baseline, document.snapshotMetadata(), document.annotationDelta());

        SimpleAssert.that(patched.follows(baseline), "the annotation update preserves the local baseline lineage");
        SimpleAssert.eq(1, patched.parsedCells, "the actual single edit parses exactly one row");
        SimpleAssert.eq("author note", patched.cells.get(0).prompt, "the edited note replaces the displayed cell note");
        SimpleAssert.that(patched.cells.get(0).excluded, "the dirty cell carries the edited exclusion");
        SimpleAssert.that(
            patched.regions.at(0, 0, 0) == patched.cells.get(0),
            "the existing coordinate resolves to the new cell");
        SimpleAssert.that(
            patched.cells.get(1) == baseline.cells.get(1)
                && patched.regions.regions.get(new HologramRegionCache.Key(2, 0, 0))
                    == baseline.regions.regions.get(new HologramRegionCache.Key(2, 0, 0)),
            "an unrelated cell and region retain their identity");
    }

    private static void compactDirectDisplay() throws Exception {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonObject blueprint = new com.google.gson.JsonObject();
        root.add("blueprint", blueprint);
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        blueprint.add("cells", rows);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setInteger("configuration", 9);
        payload.setByteArray("unknownBytes", new byte[] { 3, -9, 0, 127 });
        String tag = java.util.Base64.getEncoder()
            .encodeToString(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(payload));
        for (int i = 0; i < 128; i++) {
            com.google.gson.JsonObject row = new com.google.gson.JsonObject();
            row.addProperty("x", i - 16);
            row.addProperty("y", i % 3 - 1);
            row.addProperty("z", -16);
            row.addProperty("block", i % 2 == 0 ? "minecraft:stonebrick" : "minecraft:stained_glass");
            row.addProperty("item", i % 2 == 0 ? "minecraft:stonebrick" : "minecraft:stained_glass");
            row.addProperty("metadata", i % 2);
            row.addProperty("damage", i % 2);
            row.addProperty("facing", i % 2 == 0 ? 4 : 2);
            if (i % 4 == 1) {
                row.addProperty("tileNbt", tag);
                row.addProperty("itemNbt", tag);
                row.addProperty("unknownMaterialField", "present only in portable source");
            }
            if (i == 3 || i == 7) row.addProperty("itemNbt", tag);
            if (i == 17) row.addProperty("excluded", true);
            rows.add(row);
        }
        byte[] legacy = root.toString()
            .getBytes(StandardCharsets.UTF_8);
        byte[] exact = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec
            .recompress(legacy, com.miaokatze.gtmb.hologram.HologramBlueprintArtifact.MAX_JSON_BYTES);
        byte[] original = exact.clone();
        HologramBlueprintPreview.DisplayInput direct = HologramBlueprintPreview.displayInput(exact, null);
        SimpleAssert.that(
            direct.cells != null && !direct.snapshot.hasKey("cells"),
            "compact local display keeps position records outside NBT");
        SimpleAssert.eq(127, direct.cells.size(), "excluded rows do not enter coordinates or native preparation");
        HologramState.Preparation task = new HologramState.Preparation(direct.snapshot, true, direct.cells);
        SimpleAssert.that(!task.step(5, Long.MAX_VALUE), "compact preparation cooperatively publishes a prefix");
        HologramState early = task.firstDisplay();
        SimpleAssert.eq(5, early.cells.size(), "first compact display retains bounded early publication");
        while (!task.step(17, Long.MAX_VALUE)) {}
        HologramState rendered = task.result();
        SimpleAssert.eq(
            2,
            rendered.plainDisplayExamples.size(),
            "compact display exposes one marked topology example per ordinary appearance");
        SimpleAssert.that(
            early.plainDisplayExamples.isEmpty(),
            "partial library preview stays on the existing incremental render path");
        SimpleAssert.that(
            !early.completeBaseline() && rendered.completeBaseline(),
            "only the final compact display has complete baseline provenance");

        NBTTagCompound expanded = HologramBlueprintPreview.snapshot(exact);
        NBTTagList expandedRows = expanded.getTagList("cells", 10);
        for (int i = 0; i < expandedRows.tagCount(); i++)
            HologramBlueprintPreview.completeCell(expandedRows.getCompoundTagAt(i));
        HologramState expandedState = new HologramState(expanded);
        SimpleAssert.eq(
            expandedState.fullData(),
            rendered.fullData(),
            "direct compact display reconstructs every coordinate and native descriptor exactly");
        SimpleAssert.eq(
            expandedState.structureMembers,
            rendered.structureMembers,
            "implicit membership still exposes each accepted cell index in order");
        SimpleAssert.eq(
            Arrays.asList(0, 1, 2, 3, 4),
            early.structureMembers.get(""),
            "early publication owns only its bounded membership prefix");
        SimpleAssert.that(
            Arrays.equals(expandedState.regions.bounds(), rendered.regions.bounds()),
            "sealed compact regions give the same exact content bounds");
        SimpleAssert.eq(expandedState.minY, rendered.minY, "compact minimum relative Y");
        SimpleAssert.eq(expandedState.maxY, rendered.maxY, "compact maximum relative Y");
        SimpleAssert.eq(
            expandedState.distanceSquared(0, 0, 0),
            rendered.distanceSquared(0, 0, 0),
            "world distance includes the source anchor outside this fixture's occupied Z plane");
        SimpleAssert.eq(
            expandedState.distanceSquared(-30, 20, 40),
            rendered.distanceSquared(-30, 20, 40),
            "distance outside the merged negative and positive regions matches the expanded source");
        SimpleAssert
            .that(early.cells.get(0) == rendered.cells.get(0), "early and final states share completed cell identity");
        for (HologramState.Cell cell : rendered.cells) {
            SimpleAssert.that(
                rendered.regions.at(cell.x, cell.y, cell.z) == cell,
                "each sparse compact lookup returns the original cell at signed coordinates");
            SimpleAssert.that(
                rendered.regions.at(cell.x, cell.y, cell.z + 1) == null,
                "sparse compact lookup distinguishes unoccupied neighboring positions");
        }
        compactDisplayOwnership(early, rendered, payload);
        SimpleAssert.that(
            rendered.cells.get(1)
                .descriptorIdentity()
                == rendered.cells.get(5)
                    .descriptorIdentity(),
            "identical packed NBT data resolves once without sharing mutable tile payloads");
        rendered.cells.get(1)
            .display(false)
            .getCompoundTag("nativeTile")
            .setInteger("configuration", 123);
        rendered.cells.get(1).targetStack.getTagCompound()
            .setInteger("configuration", 456);
        SimpleAssert.eq(
            payload,
            rendered.cells.get(5)
                .display(false)
                .getCompoundTag("nativeTile"),
            "another cell retains its independent exact tile payload");
        SimpleAssert.eq(
            payload,
            rendered.cells.get(5).targetStack.getTagCompound(),
            "another cell retains its independent exact item payload");
        SimpleAssert
            .that(Arrays.equals(original, exact), "direct display retains the portable source bytes for admission");

        compactPayloadBudget();
    }

    private static void compactDisplayOwnership(HologramState early, HologramState rendered, NBTTagCompound payload)
        throws ReflectiveOperationException {
        HologramState.Cell first = early.cells.get(0), second = rendered.cells.get(2);
        SimpleAssert.that(first.descriptorIdentity() == second.descriptorIdentity(), "plain rows reuse the prototype");
        SimpleAssert.eq(4, first.displayInteger(false, "facing", -1), "lazy scalar read keeps target facing");
        SimpleAssert.that(!first.hasDisplayTag(false, "nativeTile", 10), "lazy native-route test keeps absence");
        SimpleAssert.that(!first.hasDisplayTag(true, "color", 3), "lazy scalar read keeps actual display empty");
        SimpleAssert.eq("", first.specialHint("hint"), "compact rows have no hint text");
        SimpleAssert.eq("", first.hintIcon(0), "compact rows have no hint icons");
        SimpleAssert.that(
            first.hintIcons == null && first.hintTint == second.hintTint && first.candidates == second.candidates,
            "plain compact rows allocate no per-cell hint arrays or candidate lists");
        for (String key : Arrays.asList("targetDisplay", "actualDisplay", "specialHint")) {
            java.lang.reflect.Field field = HologramState.Cell.class.getDeclaredField(key);
            field.setAccessible(true);
            SimpleAssert.that(
                field.get(first) == null && field.get(second) == null,
                "compact read-only checks create no per-cell display tree");
        }
        NBTTagCompound target = first.display(false);
        target.setInteger("color", 7);
        first.display(true)
            .setInteger("color", 19);
        SimpleAssert.that(first.display(false) == target, "promoted mutable display is stable for its own cell");
        SimpleAssert.eq(7, first.displayInteger(false, "color", 0), "the owning cell sees its mutable display");
        SimpleAssert.eq(-1, second.displayInteger(false, "color", 0), "promotion never mutates the common descriptor");
        SimpleAssert.that(!second.hasDisplayTag(true, "color", 3), "actual display promotion remains independent");
        SimpleAssert.that(first.targetStack != second.targetStack, "plain target stacks stay independently mutable");
        first.targetStack.setItemDamage(3);
        SimpleAssert.eq(0, second.targetStack.getItemDamage(), "changing a plain target stack stays local");

        HologramState.Cell itemFirst = rendered.cells.get(3), itemSecond = rendered.cells.get(7);
        SimpleAssert.that(
            !itemFirst.hasDisplayTag(false, "nativeTile", 10)
                && itemFirst.descriptorIdentity() == itemSecond.descriptorIdentity(),
            "identical item-only payloads reuse a descriptor without entering the plain route");
        itemFirst.targetStack.getTagCompound()
            .setInteger("configuration", 28);
        SimpleAssert.eq(payload, itemSecond.targetStack.getTagCompound(), "item-only payload ownership stays separate");
    }

    private static void compactDenseRegion() {
        HologramRegionCache.Builder builder = new HologramRegionCache.Builder(true);
        HologramState.Cell[] expected = new HologramState.Cell[4096];
        for (int i = 0; i < 1025; i++) {
            int local = (i * 113) & 4095;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", -16 + (local >> 8));
            row.setInteger("y", -32 + (local >> 4 & 15));
            row.setInteger("z", -48 + (local & 15));
            expected[local] = new HologramState.Cell(i, row);
            builder.add(expected[local]);
        }
        SimpleAssert.that(builder.sealNext() && !builder.sealNext(), "one dense compact region seals once");
        HologramRegionCache region = builder.finish();
        for (int local = 0; local < expected.length; local++) SimpleAssert.that(
            region.at(-16 + (local >> 8), -32 + (local >> 4 & 15), -48 + (local & 15)) == expected[local],
            "dense compact lookup preserves cell identity and empty positions");
    }

    private static void compactCoordinateCompatibility() throws Exception {
        String start = "{\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{\"cells\":[";
        String end = "]},\"materials\":[{\"block\":\"minecraft:stone\"}],\"nbt\":[]}";
        for (String position : new String[] { "{\"z\":-9,\"x\":2}", "{\"x\":8,\"x\":2,\"z\":-9}" }) {
            byte[] encoded = (start + "[0," + position + "]" + end).getBytes(StandardCharsets.UTF_8);
            HologramBlueprintPreview.CompactCells cells = HologramBlueprintPreview.displayInput(encoded, null).cells;
            SimpleAssert.eq(2, cells.coordinate(0, 0), "compact preview preserves the selected axis value");
            SimpleAssert.eq(0, cells.coordinate(0, 1), "missing historical axis defaults to zero");
            SimpleAssert.eq(-9, cells.coordinate(0, 2), "compact preview preserves signed positions");
        }
        for (String row : new String[] { "[1,{}]", "[0,{\"x\":1.0}]", "[0,{\"x\":2147483648}]", "[0,{},0]" }) {
            byte[] encoded = (start + row + end).getBytes(StandardCharsets.UTF_8);
            try {
                HologramBlueprintPreview.displayInput(encoded, null);
                throw new AssertionError("invalid compact coordinate or reference accepted: " + row);
            } catch (IOException expected) {}
        }
    }

    private static void compactPayloadBudget() throws Exception {
        NBTTagCompound large = new NBTTagCompound();
        large.setByteArray("expanded", new byte[1024 * 1024]);
        String value = java.util.Base64.getEncoder()
            .encodeToString(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(large));
        StringBuilder source = new StringBuilder(
            "{\"encoding\":\"gtmb-dictionary-1\",\"materials\":[{\"block\":\"minecraft:stone\",\"tileNbt\":0}],\"nbt\":[\"");
        source.append(value)
            .append("\"],\"blueprint\":{\"cells\":[");
        for (int i = 0; i < 65; i++) {
            if (i != 0) source.append(',');
            source.append("[0,{\"x\":")
                .append(i)
                .append("}]");
        }
        byte[] bytes = source.append("]}}")
            .toString()
            .getBytes(StandardCharsets.UTF_8);
        try {
            HologramBlueprintPreview.displayInput(bytes, null);
            throw new AssertionError("repeated compact payload bypassed cumulative expanded NBT budget");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("total NBT byte budget"),
                "every compact coordinate is charged for its expanded NBT payload");
        }
    }

    private static void sharedPlainDisplay() throws Exception {
        String source = "{\"blueprint\":{\"cells\":["
            + "{\"block\":\"minecraft:stonebrick\",\"item\":\"minecraft:stonebrick\"},"
            + "{\"x\":16,\"block\":\"minecraft:stonebrick\",\"item\":\"minecraft:stonebrick\"},"
            + "{\"x\":17,\"block\":\"minecraft:stonebrick\",\"item\":\"minecraft:stonebrick\",\"metadata\":1,\"damage\":1},"
            + "{\"x\":18,\"block\":\"minecraft:stonebrick\",\"item\":\"minecraft:stonebrick\"},"
            + "{\"x\":19,\"block\":\"minecraft:stonebrick\",\"item\":\"minecraft:stonebrick\"}]}}";
        NBTTagCompound decoded = HologramBlueprintPreview.snapshot(source.getBytes(StandardCharsets.UTF_8));
        NBTTagList rows = decoded.getTagList("cells", 10);
        NBTTagCompound nativeTile = new NBTTagCompound();
        nativeTile.setString("kept", "detached source payload");
        rows.getCompoundTagAt(3)
            .setTag("previewTile", nativeTile);
        rows.getCompoundTagAt(4)
            .setString("unknownDisplayField", "preserved");
        NBTTagCompound independent = (NBTTagCompound) decoded.copy();
        NBTTagList originals = independent.getTagList("cells", 10);
        for (int i = 0; i < originals.tagCount(); i++)
            HologramBlueprintPreview.completeCell(originals.getCompoundTagAt(i));

        HologramState.Preparation preparation = new HologramState.Preparation(decoded, true);
        while (!preparation.step(1, Long.MAX_VALUE)) {}
        HologramState shared = preparation.result();
        SimpleAssert.eq(new HologramState(independent).fullData(), shared.fullData(), "shared preview stays lossless");
        HologramState.Cell first = shared.cells.get(0), second = shared.cells.get(1);
        SimpleAssert
            .that(first.descriptorIdentity() == second.descriptorIdentity(), "plain display description reused");
        SimpleAssert.that(
            first.descriptorIdentity() != shared.cells.get(2)
                .descriptorIdentity(),
            "material metadata remains distinct");
        SimpleAssert.that(
            shared.cells.get(3)
                .descriptorIdentity() == shared.cells.get(3),
            "native payload follows the independent path");
        SimpleAssert.that(
            shared.cells.get(4)
                .descriptorIdentity() == shared.cells.get(4),
            "unrecognized descriptor fields follow the independent path");
        first.targetStack.setItemDamage(3);
        first.display(false)
            .setInteger("color", 7);
        SimpleAssert.eq(0, second.targetStack.getItemDamage(), "shared display stacks remain privately mutable");
        SimpleAssert.eq(
            -1,
            second.display(false)
                .getInteger("color"),
            "shared display NBT remains privately mutable");
        SimpleAssert.that(!HologramTextures.partial(second, false), "ordinary visibility needs no atlas access");

        java.util.Map<?, ?> entries = HologramPreviewAssets.EDITOR.linkNodes.nodes;
        int before = entries.size();
        SimpleAssert.that(
            HologramLinkNodePreview.prepare(first, false)
                .isEmpty(),
            "empty cover path");
        SimpleAssert.eq(before, entries.size(), "coverless preview creates no per-cell link cache");

        java.util.concurrent.atomic.AtomicInteger matched = new java.util.concurrent.atomic.AtomicInteger();
        HologramNativeRenderRegistry.Adapter builtin = block -> {
            matched.incrementAndGet();
            return block == Blocks.stonebrick;
        };
        HologramNativeRenderRegistry.registerBuiltin(builtin);
        try {
            SimpleAssert.eq(builtin, HologramNativeRenderRegistry.builtin(Blocks.stonebrick), "native builtin found");
            SimpleAssert
                .eq(builtin, HologramNativeRenderRegistry.builtin(Blocks.stonebrick), "native selection reused");
            SimpleAssert.eq(1, matched.get(), "builtin classification only runs once per exact block");
        } finally {
            HologramNativeRenderRegistry.unregisterBuiltin(builtin);
        }
        SimpleAssert.that(
            HologramNativeRenderRegistry.builtin(Blocks.stonebrick) != builtin,
            "unregister clears cached native builtin");
    }

    private static void sourceReuse() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("gtmb-display-only-", ".json");
        try {
            byte[] first = "{\"blueprint\":{\"cells\":[{\"x\":1,\"block\":\"missing:display\"}]}}"
                .getBytes(StandardCharsets.UTF_8);
            java.nio.file.Files.write(file, first);
            HologramLibraryClient client = new HologramLibraryClient();
            HologramLibraryClient.DisplaySource cold = client.readDisplaySource("fixture", file, "local-fixture", "");
            SimpleAssert.eq("", cold.hash, "ordinary source decoding computes no semantic content hash");
            SimpleAssert.eq(null, cold.artifact, "ordinary source decoding needs no PNG or artifact bundle");
            cold.state = new HologramState(cold.snapshot);
            cold.snapshot = null;
            client.retainDisplaySource(cold);
            HologramLibraryClient.DisplaySource warm = client.readDisplaySource("fixture", file, "local-fixture", "");
            SimpleAssert.that(
                cold == warm && cold.state == warm.state,
                "unchanged source identity reuses the exact prepared state without decoding or copying cells");
            java.nio.file.Files.write(
                file,
                "{\"blueprint\":{\"cells\":[{\"x\":42,\"block\":\"missing:changed\"}]}}"
                    .getBytes(StandardCharsets.UTF_8));
            HologramLibraryClient.DisplaySource changed = client
                .readDisplaySource("fixture", file, "local-fixture", "");
            SimpleAssert.that(
                changed != cold && changed.state == null && changed.artifact == null && changed.hash.isEmpty(),
                "actual source changes discard display, bundle and admission identity together");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    static void savedPrivateMaterialSource() throws Exception {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("fixture", "opaque-payload-not-a-material-identity");
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(data));
        String source = "{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\","
            + "\"blueprint\":{\"schema\":2,\"retainedNbtKeys\":[],\"cells\":[[0,{\"x\":1}],[0,{\"x\":2}],"
            + "[1,{\"x\":3}],[2,{\"x\":4}],[3,{\"x\":5}],[4,{\"x\":6}],[5,{\"x\":7}],"
            + "[6,{\"x\":8}],[7,{\"x\":9}],[8,{\"x\":10}],[9,{\"x\":11}],[10,{\"x\":12}]]},"
            + "\"materials\":[{\"block\":\"detached:unregistered\",\"item\":\"detached:unregistered\"},"
            + "{\"block\":\"detached:unregistered\",\"item\":\"detached:unregistered\",\"prompt\":\"fixture\"},"
            + "{\"block\":\"detached:unregistered\",\"item\":\"detached:unregistered\",\"metadata\":1,\"damage\":1},"
            + "{\"block\":\"detached:unregistered\",\"item\":\"detached:unregistered\",\"controller\":true,\"facing\":3},"
            + "{\"block\":\"minecraft:air\"},"
            + "{\"block\":\"minecraft:air\",\"itemNbt\":0},"
            + "{\"block\":\"detached:unregistered\",\"item\":\"detached:unregistered\",\"tileNbt\":0},"
            + "{\"block\":\"detached:unregistered\",\"item\":\"detached:unregistered\",\"excluded\":true},"
            + "{\"block\":\"detached:unregistered\",\"item\":\"detached:unregistered\",\"metadata\":1.2},"
            + "{\"block\":\"detached:unregistered\",\"item\":\"detached:unregistered\",\"futureMaterialField\":true},"
            + "{\"block\":\"minecraft:air\",\"metadata\":-1}],"
            + "\"nbt\":[\""
            + encoded
            + "\"]}";
        byte[] compact = source.getBytes(StandardCharsets.UTF_8);
        var regular = HologramBlueprintPreview.displayInput(compact, null);
        var privateDecode = HologramBlueprintPreview.displayInput(compact, null, true);
        var sidecar = privateDecode.savedMaterials;
        SimpleAssert.that(
            regular.savedMaterials == null && sidecar != null
                && regular.snapshot.equals(privateDecode.snapshot)
                && regular.cells.size() == privateDecode.cells.size()
                && regular.cells.size() == 11,
            "ordinary display metadata and included geometry do not acquire extra material fields");
        SimpleAssert.that(
            sidecar.variants()
                .size() == 3
                && sidecar.variants()
                    .get(0)
                    .cells() == 3
                && sidecar.variants()
                    .get(1)
                    .variant()
                    .damage() == 1
                && sidecar.variants()
                    .get(2)
                    .variant()
                    .controller()
                && sidecar.variants()
                    .get(2)
                    .variant()
                    .facing() == 3,
            "dictionary duplicates coalesce only on exact detached source identity; a saved controller is a target");
        SimpleAssert.that(
            sidecar.airCells() == 1 && sidecar.payloadCells() == 2
                && sidecar.unsupportedCells() == 3
                && sidecar.limitCells() == 0
                && sidecar.retentionNeedsReview(),
            "malformed air is an unknown descriptor, not harmless air; payload and retention uncertainty stay separate");
        SimpleAssert.that(
            !sidecar.toString()
                .contains(encoded) && sidecar.variants()
                    .stream()
                    .noneMatch(
                        row -> row.variant()
                            .toString()
                            .contains(encoded)),
            "no opaque data becomes an exposed material variant");
        byte[] expanded = ("{\"blueprint\":{\"schema\":2,\"cells\":[{\"block\":\"detached:display\","
            + "\"item\":\"detached:display\"}]}}").getBytes(StandardCharsets.UTF_8);
        var expandedMaterials = HologramBlueprintPreview.displayInput(expanded, null, true).savedMaterials;
        SimpleAssert.that(
            expandedMaterials != null && expandedMaterials.variants()
                .size() == 1
                && expandedMaterials.variants()
                    .get(0)
                    .cells() == 1,
            "expanded private source folds material facts into its existing display decode pass");
        var draft = HologramLocalBlueprintDocument.open(expanded, "")
            .freeze();
        var draftMaterials = HologramBlueprintPreview.displayInput(draft, () -> true, null).savedMaterials;
        SimpleAssert.that(
            draftMaterials != null && draftMaterials.variants()
                .equals(expandedMaterials.variants())
                && draftMaterials.retentionNeedsReview() == expandedMaterials.retentionNeedsReview(),
            "frozen editor source carries the same detached description without rereading or scanning it at open");

        java.nio.file.Path file = java.nio.file.Files.createTempFile("gtmb-private-material-fixture-", ".json");
        try {
            java.nio.file.Files.write(file, compact);
            HologramLibraryClient library = new HologramLibraryClient();
            String localId = "local_00000000-1111-2222-3333-444444444444", key = "local:" + localId;
            var saved = library.readDisplaySource(key, file, localId, "");
            SimpleAssert.that(
                saved.savedMaterials != null,
                "captured local JSON opts in without claiming managed index or pair proof");
            saved.compactCells = null;
            saved.snapshot = null;
            library.retainDisplaySource(saved);
            var warm = library.readDisplaySource(key, file, localId, "");
            SimpleAssert.that(
                warm == saved && warm.savedMaterials == saved.savedMaterials,
                "matching stamp reuses the tally on the same captured bytes; it does not prove on-disk equality");
            SimpleAssert.eq(
                null,
                library.readDisplaySource(key, file, localId, "public_fixture").savedMaterials,
                "a read tagged public cannot borrow the private tally, even with a colliding key and stamp");
            java.nio.file.Files.write(file, compactWithAnotherCell(source));
            var reread = library.readDisplaySource(key, file, localId, "");
            SimpleAssert.that(
                reread != saved && reread.savedMaterials.variants()
                    .get(0)
                    .cells() == 4,
                "a size-changing fixture invalidates the existing file stamp and gets a new captured tally");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }

        int limit = HologramSavedMaterialSource.MAX_TRACKED_DESCRIPTORS;
        com.google.gson.JsonObject schema = new com.google.gson.JsonObject();
        schema.addProperty("schema", 2);
        com.google.gson.JsonObject variant = new com.google.gson.JsonObject();
        variant.addProperty("block", "detached:bounded");
        variant.addProperty("item", "detached:bounded");
        var bounded = new HologramSavedMaterialSource.Tally(schema, 65536);
        bounded.include(limit - 1, variant);
        bounded.include(limit, variant);
        bounded.include(65535, null);
        var facts = bounded.snapshot();
        SimpleAssert.that(
            facts.variants()
                .size() == 1
                && facts.variants()
                    .get(0)
                    .cells() == 1
                && facts.limitCells() == 2,
            "high dictionary indices stay explicitly unknown without growing storage with palette width");
    }

    private static byte[] compactWithAnotherCell(String source) {
        return source.replace("[10,{\"x\":12}]", "[10,{\"x\":12}],[0,{\"x\":13}]")
            .getBytes(StandardCharsets.UTF_8);
    }

    static void indexedMaterialSourceCapture() throws Exception {
        var game = java.nio.file.Files.createTempDirectory("gtmb-indexed-material-source-");
        var libraryDir = java.nio.file.Files.createDirectory(game.resolve("MiaoBlueprints"));
        String id = "local_aabbccdd-1111-2222-3333-444444444444";
        var member = libraryDir.resolve(id + ".json");
        var index = libraryDir.resolve(".gtmb-blueprint-files.properties");
        try {
            byte[] compact = ("{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\","
                + "\"blueprint\":{\"schema\":2,\"cells\":[[0,{\"x\":1}]]},"
                + "\"materials\":[{\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\"}],\"nbt\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            java.nio.file.Files.write(member, compact);
            HologramClientLibrary store = new HologramClientLibrary(game);
            HologramLibraryClient reader = new HologramLibraryClient();
            var before = store.savedDisplayPath(id);
            var fallback = reader.readSavedDisplaySource(id, before, false, 0, 0);
            SimpleAssert.that(
                before.path()
                    .equals(store.savedPath(id)) && !before.indexed()
                    && fallback.savedMaterials != null
                    && !fallback.capturedIndexedMaterialSource()
                    && HologramLibraryMaterialForecast.fromCapture(fallback) == null,
                "compatibility filename still previews, but it has no durable index provenance");
            reader.retainDisplaySource(fallback);

            java.nio.file.Files.write(
                index,
                (id + "=" + member.getFileName() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var selection = store.savedDisplayPath(id);
            var managed = reader.readSavedDisplaySource(id, selection, false, 0, 0);
            SimpleAssert.that(
                selection.path()
                    .equals(before.path()) && selection.indexed()
                    && managed != fallback
                    && managed.capturedIndexedMaterialSource()
                    && HologramLibraryMaterialForecast.fromCapture(managed)
                        .candidates()
                        .size() == 1,
                "a chosen indexed source carries its own provenance even when the JSON stamp is unchanged");
            reader.retainDisplaySource(managed);
            SimpleAssert.that(
                managed == reader.readSavedDisplaySource(id, store.savedDisplayPath(id), false, 0, 0),
                "same indexed capture can use the existing cache without re-parsing the JSON");

            java.nio.file.Files.delete(index);
            var later = reader.readSavedDisplaySource(id, store.savedDisplayPath(id), false, 0, 0);
            SimpleAssert.that(
                later != managed && !later.capturedIndexedMaterialSource()
                    && managed.capturedIndexedMaterialSource()
                    && HologramLibraryMaterialForecast.fromCapture(managed)
                        .candidates()
                        .size() == 1,
                "removed index mapping cannot borrow indexed provenance for a fresh read; old proofs are historical");
        } finally {
            java.nio.file.Files.deleteIfExists(index);
            java.nio.file.Files.deleteIfExists(member);
            java.nio.file.Files.deleteIfExists(libraryDir);
            java.nio.file.Files.deleteIfExists(game);
        }
    }

    private static void earlyDisplay() {
        NBTTagCompound source = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < 600; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", i * 32);
            row.setInteger("dx", i * 32);
            rows.appendTag(row);
        }
        source.setTag("cells", rows);
        HologramState.Preparation preparation = new HologramState.Preparation(source);
        SimpleAssert.eq(false, preparation.step(64, Long.MAX_VALUE), "a large display remains in preparation");
        HologramState first = preparation.firstDisplay();
        SimpleAssert.eq(64, first.cells.size(), "the first completed batch can display before full preparation");
        SimpleAssert.eq(true, first.data.getBoolean("displayPartial"), "early geometry carries no full-ready claim");
        int regions = first.regions.regions.size();
        while (!preparation.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
        HologramState complete = preparation.result();
        SimpleAssert.eq(600, complete.cells.size(), "final geometry retains every cell");
        SimpleAssert.that(
            first.cells.get(0) == complete.cells.get(0),
            "early and final displays share immutable completed cells");
        SimpleAssert.eq(64, first.cells.size(), "continuing preparation never mutates published early cells");
        SimpleAssert
            .eq(regions, first.regions.regions.size(), "continuing preparation never mutates the early spatial index");
        SimpleAssert
            .that(first.regions != complete.regions, "the mutable builder is never published as an early region index");
    }

    private static void displayPicture() throws Exception {
        java.awt.image.BufferedImage original = new java.awt.image.BufferedImage(
            32,
            32,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        original.setRGB(5, 7, 0xff234567);
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(original, "png", encoded);
        byte[] png = encoded.toByteArray();
        java.awt.image.BufferedImage decoded = HologramLibraryClient.decodeDisplayImage(png);
        SimpleAssert.eq(
            original.getRGB(5, 7),
            decoded.getRGB(5, 7),
            "optional source pictures display without JSON binding or content admission");
        byte[] oversized = png.clone();
        oversized[16] = oversized[17] = oversized[19] = 0;
        oversized[18] = 8;
        try {
            HologramLibraryClient.decodeDisplayImage(oversized);
            throw new AssertionError("PNG header dimensions must be bounded before pixel allocation");
        } catch (IOException expected) {}
    }

    private static void uploadRepresentation() throws Exception {
        StringBuilder json = new StringBuilder("{\"blueprint\":{\"cells\":[");
        for (int i = 0; i < 128; i++) {
            if (i > 0) json.append(',');
            json.append("{\"x\":")
                .append(i)
                .append(",\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"metadata\":0,\"damage\":0}");
        }
        byte[] original = json.append("]}}")
            .toString()
            .getBytes(StandardCharsets.UTF_8), before = original.clone();
        byte[] compact = HologramLibraryClient.uploadJson(original, () -> true);
        SimpleAssert.that(compact.length < original.length, "a real miss uses existing lossless transport compression");
        SimpleAssert.eq(
            com.miaokatze.gtmb.hologram.HologramBlueprintContentHash.of(original),
            com.miaokatze.gtmb.hologram.HologramBlueprintContentHash.of(compact),
            "transport compression preserves the admitted semantic content identity");
        SimpleAssert.that(Arrays.equals(before, original), "upload compression never rewrites the frozen source bytes");
        SimpleAssert.that(
            compact == HologramLibraryClient.uploadJson(compact, () -> true),
            "already compact sources are reused without another compression pass");
    }

    private static void expectFailure(String json) throws Exception {
        try {
            HologramBlueprintPreview.snapshot(json.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Malformed/over-budget display data must be refused");
        } catch (IOException expected) {}
    }
}
