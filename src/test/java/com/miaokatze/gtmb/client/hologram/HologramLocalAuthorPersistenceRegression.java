package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;

/** Local drafts must survive without player, world, native payload decoding, preview PNG or save ACK. */
public final class HologramLocalAuthorPersistenceRegression {

    private HologramLocalAuthorPersistenceRegression() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        HologramAuthorSourceImportRegression.run();
        Path game = Files.createTempDirectory("gtmb-local-author-");
        try {
            HologramClientLibrary library = new HologramClientLibrary(game);
            AuthorDocument source = AuthorDocumentIO.read(
                ("{\"blueprint\":{\"schema\":2,\"name\":\"opaque\","
                    + "\"future\":{\"unknown\":7},\"cells\":[{\"x\":1,\"y\":2,\"z\":3,"
                    + "\"block\":\"missingmod:block\",\"tileNbt\":\"opaque-original\",\"futureCell\":true}]}}")
                        .getBytes(StandardCharsets.UTF_8),
                AuthorDocumentIO.Limits.defaults());
            AuthorEditor editor = new AuthorEditor(source, 8);
            editor.select(java.util.Collections.singleton(1L));
            editor.move(2, 0, 0);
            AuthorDocument edited = editor.current();
            byte[] bytes = AuthorDocumentIO.writeDraft(edited, AuthorDocumentIO.Limits.defaults());
            Path file = library.saveAuthorDraft(edited.documentId(), bytes);
            check(Files.isRegularFile(file), "single draft file persisted");
            check(!Files.exists(file.resolveSibling(file.getFileName() + ".png")), "no thumbnail prerequisite");
            check(
                Arrays.equals(bytes, library.authorSource("draft_" + edited.documentId())),
                "exact saved bytes reopen");
            AuthorDocument recovered = AuthorDocumentIO
                .read(library.authorSource("draft_" + edited.documentId()), AuthorDocumentIO.Limits.defaults());
            check(
                recovered.cell(1)
                    .x() == 3 && "opaque-original".equals(
                        recovered.cell(1)
                            .tileNbt()),
                "coordinates and opaque payload independently preserved");
            check(recovered.hasPendingObligations(), "recovery retains native transform obligations");
            check(
                recovered.sourceCopy()
                    .getAsJsonObject("blueprint")
                    .has("future"),
                "unknown source fields retained");
            check(
                library.list()
                    .size() == 1
                    && library.list()
                        .get(0).id.equals("draft_" + edited.documentId()),
                "restart catalog discovers JSON-only draft");
            check(
                "opaque".equals(
                    library.list()
                        .get(0).name),
                "catalog reads source title");
            AuthorDocument copy = recovered.forkCopy();
            library.saveAuthorDraft(
                copy.documentId(),
                AuthorDocumentIO.writeDraft(copy, AuthorDocumentIO.Limits.defaults()));
            check(
                library.list()
                    .size() == 2,
                "recovery copy has independent document identity");
            try {
                library.saveAuthorDraft("../escape", bytes);
                throw new AssertionError("unsafe identity accepted");
            } catch (IOException expected) { /* no path traversal */ }
            try {
                library.saveAuthorDraft(edited.documentId(), new byte[0]);
                throw new AssertionError("empty save accepted");
            } catch (IOException expected) { /* previous committed bytes remain */ }
            check(Arrays.equals(bytes, Files.readAllBytes(file)), "failed save preserves previous committed file");
            library.delete("draft_" + copy.documentId());
            check(
                library.list()
                    .size() == 1,
                "draft deletion does not require pair or server index");
            nullableDraftLists(library);
            envelopeDraftMetadata(library);
            runtimeMetadata();
            System.out.println("Local author persistence regression passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(game)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void nullableDraftLists(HologramClientLibrary library) throws Exception {
        for (boolean wrapped : new boolean[] { false, true }) {
            for (String lists : new String[] { "\"cells\":null,\"structures\":null",
                "\"cells\":[{\"x\":1}],\"structures\":null", "\"cells\":null,\"structures\":[]" }) {
                String source = "{\"schema\":2,\"name\":\"nullable lists\"," + lists + "}";
                if (wrapped) source = "{\"blueprint\":" + source + "}";
                AuthorDocument document = AuthorDocumentIO
                    .read(source.getBytes(StandardCharsets.UTF_8), AuthorDocumentIO.Limits.defaults());
                byte[] saved = AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults());
                Path path = library.saveAuthorDraft(document.documentId(), saved);
                String id = "draft_" + document.documentId();
                HologramClientLibrary.Entry entry = null;
                for (HologramClientLibrary.Entry candidate : library.list())
                    if (candidate.id.equals(id)) entry = candidate;
                check(entry != null, "catalog discovers valid nullable source lists");
                check(
                    entry.cells == document.cellCount() && entry.structures == 0,
                    "nullable source lists have correct summary counts");
                AuthorDocument reopened = AuthorDocumentIO
                    .read(library.authorSource(id), AuthorDocumentIO.Limits.defaults());
                check(
                    document.sourceCopy()
                        .toString()
                        .equals(
                            reopened.sourceCopy()
                                .toString()),
                    "listing and reopening preserve null versus empty source arrays");
                check(Arrays.equals(saved, Files.readAllBytes(path)), "catalog summary does not rewrite draft");
                if (lists.startsWith("\"cells\":null")) {
                    byte[] corrupt = new String(saved, StandardCharsets.UTF_8).replace("\"cells\":null", "\"cells\":{}")
                        .getBytes(StandardCharsets.UTF_8);
                    Files.write(path, corrupt);
                    try {
                        AuthorDocumentIO.read(corrupt, AuthorDocumentIO.Limits.defaults());
                        throw new AssertionError("corrupt actual source list passed strict admission");
                    } catch (IOException expected) { /* Actual blueprint lists are typed, unlike envelope collisions. */ }
                    for (HologramClientLibrary.Entry candidate : library.list())
                        check(!candidate.id.equals(id), "non-array non-null source list remains corrupt");
                    check(Arrays.equals(corrupt, Files.readAllBytes(path)), "corrupt draft is retained, not rewritten");
                }
                library.delete(id);
            }
        }
    }

    private static void envelopeDraftMetadata(HologramClientLibrary library) throws Exception {
        for (boolean blueprintFirst : new boolean[] { false, true }) {
            for (String outer : new String[] { "{\"name\":{},\"cells\":{},\"structures\":{}}",
                "{\"name\":\"decoy\",\"cells\":[1,2],\"structures\":[1,2]}" }) {
                JsonObject blueprint = new com.google.gson.JsonParser()
                    .parse(
                        "{\"schema\":2,\"name\":\"actual blueprint\",\"cells\":[{\"x\":1}],\"structures\":null,"
                            + "\"authorName\":{},\"authorUuid\":[]}")
                    .getAsJsonObject();
                JsonObject unknown = new com.google.gson.JsonParser().parse(outer)
                    .getAsJsonObject();
                JsonObject source = new JsonObject();
                if (blueprintFirst) source.add("blueprint", blueprint);
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> field : unknown.entrySet())
                    source.add(field.getKey(), field.getValue());
                if (!blueprintFirst) source.add("blueprint", blueprint);
                AuthorDocument document = AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
                JsonObject draft = new com.google.gson.JsonParser()
                    .parse(
                        new String(
                            AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults()),
                            StandardCharsets.UTF_8))
                    .getAsJsonObject();
                // The normal writer places blueprint last. Valid transferred drafts may use either field order.
                draft.add("source", source);
                byte[] bytes = draft.toString()
                    .getBytes(StandardCharsets.UTF_8);
                assertDraftMetadata(library, document, bytes, "actual blueprint", 1, 0);
            }
        }
        for (String descriptions : new String[] { "\"authorName\":{},\"authorUuid\":[]",
            "\"authorName\":true,\"authorUuid\":42", "\"authorName\":null,\"authorUuid\":null" }) {
            AuthorDocument document = AuthorDocumentIO.read(
                ("{\"schema\":2,\"name\":\"bare recovery\"," + descriptions + ",\"cells\":null,\"structures\":[]}")
                    .getBytes(StandardCharsets.UTF_8),
                AuthorDocumentIO.Limits.defaults());
            assertDraftMetadata(
                library,
                document,
                AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults()),
                "bare recovery",
                0,
                0);
        }
    }

    private static void assertDraftMetadata(HologramClientLibrary library, AuthorDocument document, byte[] bytes,
        String title, int cells, int structures) throws Exception {
        AuthorDocument admitted = AuthorDocumentIO.read(bytes, AuthorDocumentIO.Limits.defaults());
        Path file = library.saveAuthorDraft(document.documentId(), bytes);
        String id = "draft_" + document.documentId();
        HologramClientLibrary.Entry entry = null;
        for (HologramClientLibrary.Entry candidate : library.list()) if (candidate.id.equals(id)) entry = candidate;
        check(
            entry != null && title.equals(entry.name) && entry.cells == cells && entry.structures == structures,
            "catalog uses only actual blueprint metadata regardless of envelope field order");
        check(
            entry.authorName.isEmpty() && entry.authorUuid.isEmpty(),
            "opaque non-string attribution remains recoverable");
        AuthorDocument reopened = AuthorDocumentIO.read(library.authorSource(id), AuthorDocumentIO.Limits.defaults());
        check(
            admitted.sourceCopy()
                .toString()
                .equals(
                    reopened.sourceCopy()
                        .toString()),
            "wrapped unknown fields and opaque attribution survive strict reopen");
        check(Arrays.equals(bytes, Files.readAllBytes(file)), "metadata extraction leaves exact saved bytes unchanged");
        library.delete(id);
    }

    private static void runtimeMetadata() throws Exception {
        AuthorDocument document = AuthorDocumentIO.read(
            ("{\"schema\":2,\"name\":\"local\","
                + "\"hintAreas\":[{\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":0,\"y2\":0,\"z2\":0,\"prompt\":\"old\",\"future\":7}],"
                + "\"structures\":[{\"id\":\"a\",\"main\":3,\"mainExplicit\":true,\"noHatches\":true,\"channels\":{\"coil\":7}}],"
                + "\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"missing:block\",\"structureId\":\"a\"}]}")
                    .getBytes(StandardCharsets.UTF_8),
            AuthorDocumentIO.Limits.defaults());
        java.lang.reflect.Constructor<HologramLocalAuthorRuntime> constructor = HologramLocalAuthorRuntime.class
            .getDeclaredConstructor(AuthorDocument.class, boolean.class);
        constructor.setAccessible(true);
        HologramLocalAuthorRuntime runtime = constructor.newInstance(document, false);
        try {
            java.lang.reflect.Method hint = HologramLocalAuthorActions.class
                .getDeclaredMethod("hint", HologramLocalAuthorRuntime.class, java.util.Set.class, String.class);
            hint.setAccessible(true);
            JsonObject patch = (JsonObject) hint.invoke(null, runtime, java.util.Collections.singleton(1L), "new");
            JsonObject area = patch.getAsJsonArray("hintAreas")
                .get(0)
                .getAsJsonObject();
            check(
                area.get("future")
                    .getAsInt() == 7 && "new".equals(
                        area.get("prompt")
                            .getAsString()),
                "annotation replacement preserves unknown fields and original row position");
            check(
                "old".equals(
                    runtime.document()
                        .headerCopy()
                        .getAsJsonArray("hintAreas")
                        .get(0)
                        .getAsJsonObject()
                        .get("prompt")
                        .getAsString()),
                "annotation staging does not mutate source");
            runtime.selectStructure("a");
            net.minecraft.nbt.NBTTagCompound view = runtime.view().data;
            check(
                view.getInteger("main") == 3 && view.getBoolean("mainExplicit")
                    && view.getBoolean("noHatches")
                    && view.getCompoundTag("channels")
                        .getInteger("coil") == 7,
                "selected structure exposes exact source settings");
            runtime.selectStructure("");
            check(
                runtime.view().data.getString("activeStructure")
                    .isEmpty(),
                "overview does not auto-select the first structure");
            net.minecraft.nbt.NBTTagCompound report = new net.minecraft.nbt.NBTTagCompound();
            net.minecraft.nbt.NBTTagList presets = new net.minecraft.nbt.NBTTagList();
            net.minecraft.nbt.NBTTagCompound preset = new net.minecraft.nbt.NBTTagCompound();
            preset.setString("id", "explicit-policy");
            presets.appendTag(preset);
            report.setTag("processingPresets", presets);
            runtime.setProcessing(report, true);
            check(
                runtime.view().data.getTagList("processingPresets", 10)
                    .tagCount() == 1,
                "explicit local processing presets reach the existing UI");
            net.minecraft.nbt.NBTTagCompound camera = new net.minecraft.nbt.NBTTagCompound();
            camera.setDouble("yaw", 37);
            runtime.rememberPreview(camera);
            camera.setDouble("yaw", 90);
            check(
                runtime.previewContext()
                    .getDouble("yaw") == 37,
                "retained camera is detached from mutable UI data");
            runtime.apply(
                runtime.editor()
                    .rename("changed"));
            check(
                !runtime.view().data.getBoolean("blueprintProcessingComplete")
                    && runtime.view().data.getTagList("processingPresets", 10)
                        .tagCount() == 0,
                "source revision invalidates processing presentation rather than retaining false approval");
        } finally {
            java.lang.reflect.Field field = HologramLocalAuthorRuntime.class.getDeclaredField("projection");
            field.setAccessible(true);
            ((HologramAuthorProjection) field.get(runtime)).close();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
