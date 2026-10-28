package com.miaokatze.gtmb.blueprint.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pure JVM regression suite: no Minecraft registry, server session, network, or NBT decoder. */
public final class AuthorDocumentRegression {

    private AuthorDocumentRegression() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("AuthorDocumentRegression OK");
    }

    public static void run() throws Exception {
        losslessAndImmutable();
        boundedAdmission();
        localGeometryAndHistory();
        sharedGroupsAndAttachments();
        clipboardAndNativeBoundary();
        incrementalPages();
        byteBudget();
        attachmentsAndPatches();
        regenerationAndResolution();
        annotationsAdmissionAndAdd();
        boundedOwnedCopyAndByteOracle();
        largeOpaqueAttachment();
        treeIndex();
        sourceWorldFrame();
    }

    private static void sourceWorldFrame() throws Exception {
        AuthorDocument plain = read("{\"schema\":2,\"cells\":[{\"x\":0,\"tileNbt\":\"opaque-exact\"}]}");
        check(!plain.sourceFrame().known, "old files have unknown source, not invented zero capture");
        AuthorDocument.SourceFrame frame = AuthorDocument.SourceFrame.captured(7, -100, 64, 200);
        AuthorDocument captured = plain.withSourceFrame(frame);
        check(
            captured.sourceCopy()
                .equals(plain.sourceCopy()),
            "world frame does not normalize source or NBT");
        AuthorEditor editor = new AuthorEditor(captured, 8);
        editor.select(Collections.singleton(1L));
        editor.move(4, 0, 0);
        editor.copy(5, 0, 0);
        AuthorDocument saved = AuthorDocumentIO.read(
            AuthorDocumentIO.writeDraft(editor.current(), AuthorDocumentIO.Limits.defaults()),
            AuthorDocumentIO.Limits.defaults());
        check(
            saved.sourceFrame()
                .equals(frame),
            "draft saves source dimension and origin separately");
        check(
            saved.cell(2)
                .payloadX() == 0,
            "world frame never replaces local per-cell payload origin");
        check(
            saved.forkCopy()
                .sourceFrame()
                .equals(frame),
            "save as retains capture provenance");
        editor.undo();
        check(
            editor.current()
                .sourceFrame()
                .equals(frame),
            "undo retains frame");
        editor.redo();
        check(
            editor.current()
                .sourceFrame()
                .equals(frame),
            "redo retains frame");
        AuthorEditor other = new AuthorEditor(plain, 8);
        boolean rejected = false;
        try {
            other.paste(editor.clipboard(), 20, 0, 0);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected && other.current() == plain, "cross-frame opaque paste is atomic refusal");
        AuthorEditor geometry = new AuthorEditor(read("{\"schema\":2,\"cells\":[{\"x\":0}]}"), 8);
        geometry.select(Collections.singleton(1L));
        editor.paste(geometry.clipboard(), 30, 0, 0);
        check(
            editor.current()
                .cellAt(30, 0, 0) != null,
            "plain geometry may cross source frames");
        String draft = new String(
            AuthorDocumentIO.writeDraft(plain, AuthorDocumentIO.Limits.defaults()),
            StandardCharsets.UTF_8);
        check(!draft.contains("sourceFrame"), "old plain draft serialization unchanged");
    }

    private static void losslessAndImmutable() throws Exception {
        String source = "{\"unknownEnvelope\":{\"future\":[1,null,true]},\"blueprint\":{\"schema\":2,\"name\":\"draft\","
            + "\"retainedNbtKeys\":null,\"future\":{\"x\":1},\"structures\":[],\"cells\":[{\"x\":0,\"y\":-8,\"z\":2,"
            + "\"block\":\"missing:mod\",\"tileNbt\":\"opaque!!not-base64\",\"itemNbt\":\"exact+/==\",\"allowedTargets\":null,\"unknown\":[2,3]}]}}";
        AuthorDocument document = read(source);
        check(
            new JsonParser().parse(source)
                .equals(document.sourceCopy()),
            "no-op JSON value fidelity");
        byte[] bytes = AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults());
        AuthorDocument reopened = AuthorDocumentIO.read(bytes, AuthorDocumentIO.Limits.defaults());
        check(
            document.sourceCopy()
                .equals(reopened.sourceCopy()),
            "draft source fidelity");
        check(
            document.documentId()
                .equals(reopened.documentId())
                && reopened.cell(1)
                    .id() == 1,
            "durable identity");
        JsonObject escaped = document.cell(1)
            .sourceCopy();
        escaped.addProperty("tileNbt", "mutated");
        check(
            "opaque!!not-base64".equals(
                document.cell(1)
                    .tileNbt()),
            "no mutable source alias");
        document.headerCopy()
            .addProperty("name", "mutated");
        check("draft".equals(document.title()), "header defensive copy");
        AuthorEditor editor = new AuthorEditor(document, 16);
        editor.select(Collections.singleton(1L));
        editor.move(0, -500, 0);
        check(
            editor.current()
                .cell(1)
                .y() == -508,
            "document coordinates independent of world height");
        check(
            "opaque!!not-base64".equals(
                editor.current()
                    .cell(1)
                    .tileNbt()),
            "opaque NBT stays exact on move");
        check(
            editor.current()
                .hasPendingObligations(),
            "native geometry intent not misrepresented as portable source");
        check(
            document.cell(1)
                .y() == -8,
            "previous revision stays frozen");
        check(
            editor.current()
                .sourceCopy()
                .getAsJsonObject("blueprint")
                .has("future"),
            "unknown header survives");
        AuthorDocument nullLists = read("{\"schema\":2,\"cells\":null,\"structures\":null}");
        check(
            nullLists.sourceCopy()
                .get("cells")
                .isJsonNull(),
            "null vs empty preserved");
        AuthorEditor previouslyNull = new AuthorEditor(nullLists, 4);
        JsonObject position = new JsonObject();
        position.addProperty("x", 3);
        previouslyNull.addCell(position);
        AuthorDocument nullRoundtrip = AuthorDocumentIO.read(
            AuthorDocumentIO.writeDraft(previouslyNull.current(), AuthorDocumentIO.Limits.defaults()),
            AuthorDocumentIO.Limits.defaults());
        check(nullRoundtrip.cellCount() == 1, "streamed source replaces null cells field without duplicate JSON names");
    }

    private static void boundedAdmission() throws Exception {
        rejectIO("{\"schema\":2,\"schema\":2}", "duplicate fields");
        rejectIO("{\"schema\":2,\"cells\":[{\"x\":1.5}]}", "fractional coordinate");
        rejectIO("{\"schema\":2,\"cells\":[{\"x\":2147483648}]}", "integer overflow");
        rejectIO("{\"schema\":2,\"cells\":[{},{}]}", "duplicate position");
        rejectIO("{\"schema\":2,\"cells\":[{\"structureId\":\"missing\"}]}", "unknown membership");
        rejectIO("{\"schema\":2,\"cells\":[{\"tileNbt\":5}]}", "opaque payload requires string");
        rejectIO("{\"format\":\"gtmb-author-draft\",\"version\":2}", "future draft rejected");
        rejectIO("{\"encoding\":\"gtmb-dictionary-1\"}", "compact format requires explicit adapter");
        AuthorDocument legacy = read("{\"schema\":1,\"cells\":[]}");
        check(!legacy.editable(), "legacy preserved without guessed permissions");
        reject(() -> new AuthorEditor(legacy, 4).rename("x"), "schema1 explicit migration required");
        try {
            AuthorDocumentIO.read(new byte[] { (byte) 0xc3, 0x28 }, AuthorDocumentIO.Limits.defaults());
            throw new AssertionError("malformed UTF8");
        } catch (IOException expected) { /* exact source requires valid UTF-8 */ }
        String deep = "{}";
        for (int i = 0; i < 40; i++) deep = "{\"a\":" + deep + "}";
        rejectIO(deep, "depth budget");
    }

    private static void localGeometryAndHistory() throws Exception {
        AuthorEditor editor = new AuthorEditor(read("{\"schema\":2,\"cells\":[{\"x\":0},{\"x\":1},{\"x\":5}]}"), 8);
        editor.select(Arrays.asList(1L, 2L));
        AuthorDocument before = editor.current();
        AuthorEditor.Change move = editor.move(1, 0, 0);
        check(
            editor.current()
                .cellAt(1, 0, 0)
                .id() == 1
                && editor.current()
                    .cellAt(2, 0, 0)
                    .id() == 2,
            "batch move overlap keeps both spatial-index entries");
        check(
            move.changedIds()
                .size() == 2 && move.removedIds()
                    .isEmpty(),
            "local change domain");
        long revision = editor.current()
            .revision();
        editor.undo();
        check(
            editor.current()
                .sourceCopy()
                .equals(before.sourceCopy())
                && editor.current()
                    .revision() > revision,
            "undo restores source with fresh revision");
        editor.redo();
        check(
            editor.current()
                .cell(1)
                .x() == 1,
            "redo geometry");
        AuthorDocument frozen = editor.current();
        reject(() -> editor.move(4, 0, 0), "collision atomic rejection");
        check(
            editor.current() == frozen && editor.selection()
                .size() == 2,
            "failed edit keeps revision/selection");
        editor.select(Collections.singleton(1L));
        editor.copy(10, 0, 0);
        long copied = editor.selection()
            .iterator()
            .next();
        check(copied != 1, "copy new stable ID");
        editor.undo();
        byte[] draft = AuthorDocumentIO.writeDraft(editor.current(), AuthorDocumentIO.Limits.defaults());
        AuthorEditor restart = new AuthorEditor(AuthorDocumentIO.read(draft, AuthorDocumentIO.Limits.defaults()), 8);
        restart.select(Collections.singleton(1L));
        restart.copy(20, 0, 0);
        check(
            restart.selection()
                .iterator()
                .next() > copied,
            "undo/save/reopen cannot reuse abandoned identity");
    }

    private static void sharedGroupsAndAttachments() throws Exception {
        String source = "{\"schema\":2,\"structures\":[{\"id\":\"a\",\"x\":0},{\"id\":\"b\",\"x\":0}],\"cells\":["
            + "{\"x\":0,\"structureIds\":[\"a\",\"b\"]},{\"x\":1,\"structureId\":\"a\"},{\"x\":2,\"structureId\":\"b\"}]}";
        AuthorEditor editor = new AuthorEditor(read(source), 16);
        editor.select(Arrays.asList(1L, 2L));
        editor.move(10, 0, 0);
        check(
            editor.current()
                .cell(1)
                .x() == 10 && editor.current()
                    .cell(1)
                    .memberships()
                    .equals(Collections.singleton("a")),
            "complete member follows stable identity");
        check(
            editor.current()
                .cellAt(0, 0, 0)
                .memberships()
                .equals(Collections.singleton("b")),
            "other shared membership stays at source");
        check(
            editor.current()
                .cellCount() == 4,
            "shared move creates bounded retained source cell");
        editor.undo();
        editor.select(Collections.singleton(2L));
        editor.delete();
        check(
            editor.current()
                .cell(2) == null
                && editor.current()
                    .structures()
                    .size() == 2,
            "ordinary member deletion allowed");
        AuthorEditor anchor = new AuthorEditor(read("{\"schema\":2,\"cells\":[{\"controller\":true}]}"), 4);
        anchor.select(Collections.singleton(1L));
        anchor.delete();
        check(
            anchor.current()
                .cellCount() == 0,
            "saved controller flag is not a physical world anchor");
        AuthorEditor text = new AuthorEditor(
            read(
                "{\"schema\":2,\"structures\":[{\"id\":\"t\",\"textBoxNbt\":\"opaque\"}],\"cells\":[{\"structureId\":\"t\"},{\"x\":1,\"structureId\":\"t\"}]}"),
            4);
        text.select(Collections.singleton(1L));
        reject(text::delete, "partial attachment delete rejected");
        reject(text::clipboard, "partial attachment clipboard rejected");
        text.select(Arrays.asList(1L, 2L));
        text.delete();
        check(
            text.current()
                .structures()
                .isEmpty(),
            "whole attachment deletion");
    }

    private static void clipboardAndNativeBoundary() throws Exception {
        AuthorEditor editor = new AuthorEditor(
            read("{\"schema\":2,\"cells\":[{\"x\":0,\"tileNbt\":\"a\"},{\"x\":2,\"tileNbt\":\"b\"}]}"),
            8);
        editor.select(Collections.singleton(1L));
        AuthorEditor.Clipboard clipboard = editor.clipboard();
        editor.cut();
        check(
            editor.current()
                .cell(1)
                .excluded()
                && editor.current()
                    .cellCount() == 2,
            "cut excludes instead of deletes");
        editor.replaceCellNbt(1, "changed", null);
        editor.paste(clipboard, 2, 0, 0);
        check(
            "a".equals(
                editor.current()
                    .cellAt(2, 0, 0)
                    .tileNbt()),
            "frozen clipboard survives source edit and overwrites target");
        long id = editor.current()
            .cellAt(2, 0, 0)
            .id();
        Map<Long, JsonObject> replacements = new LinkedHashMap<>();
        JsonObject patch = editor.current()
            .cell(id)
            .sourceCopy();
        patch.addProperty("tileNbt", "new");
        replacements.put(id, patch);
        long revision = editor.current()
            .revision();
        editor.replaceCells(replacements);
        check(
            editor.current()
                .revision() == revision + 1,
            "native multiple-cell result one transaction");
        patch.addProperty("tileNbt", "alias");
        check(
            "new".equals(
                editor.current()
                    .cell(id)
                    .tileNbt()),
            "native result alias detached");
        JsonObject transformed = editor.current()
            .sourceCopy();
        for (com.google.gson.JsonElement row : transformed.getAsJsonArray("cells")) row.getAsJsonObject()
            .addProperty("y", 8);
        revision = editor.current()
            .revision();
        final long pendingRevision = revision;
        reject(
            () -> editor.replaceTransformedSource(pendingRevision, transformed),
            "native transform rejects unresolved coordinate basis");
        check(
            editor.current()
                .cell(id)
                .payloadX() == 0
                && editor.current()
                    .cell(id)
                    .x() == 2,
            "paste inherits original payload basis");
        AuthorDocument roundtrip = AuthorDocumentIO.read(
            AuthorDocumentIO.writeDraft(editor.current(), AuthorDocumentIO.Limits.defaults()),
            AuthorDocumentIO.Limits.defaults());
        check(
            roundtrip.cell(id)
                .payloadX() == 0,
            "payload basis persists in draft");
        AuthorEditor clean = new AuthorEditor(read("{\"schema\":2,\"cells\":[{\"x\":0}]}"), 4);
        JsonObject rotated = clean.current()
            .sourceCopy();
        rotated.getAsJsonArray("cells")
            .get(0)
            .getAsJsonObject()
            .addProperty("y", 8);
        clean.replaceTransformedSource(
            clean.current()
                .revision(),
            rotated);
        check(
            clean.current()
                .cell(1)
                .y() == 8,
            "native transform retains stable ID");
        check(
            editor.current()
                .hasPendingObligations(),
            "native transform never silently clears old obligations");
        final long stale = revision - 1;
        reject(() -> editor.replaceTransformedSource(stale, transformed), "stale native transform rejected");
    }

    private static void incrementalPages() throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray rows = new JsonArray();
        for (int i = 0; i < 2048; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", i);
            cell.addProperty("tileNbt", "opaque");
            rows.add(cell);
        }
        source.add("cells", rows);
        AuthorDocument before = AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
        AuthorEditor editor = new AuthorEditor(before, 4);
        editor.replaceCellNbt(4, "selected-edit", null);
        AuthorDocument after = editor.current();
        int changedPages = 0;
        for (AuthorTree.Entry<Long, AuthorDocument.Cell[]> page : before.pages)
            if (after.pages.get(page.key) != page.value) changedPages++;
        check(changedPages == 1, "one-cell edit copies exactly one 64-cell page");
        check(before.cell(1000) == after.cell(1000), "untouched cell object shared");
        check(
            before.header == after.header && before.groups == after.groups,
            "untouched header and structure index shared");
    }

    private static void byteBudget() throws Exception {
        AuthorDocumentIO.Limits limits = new AuthorDocumentIO.Limits(4096, 50, 32, 10000, 2048, 1000, 10);
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        JsonObject cell = new JsonObject();
        char[] payload = new char[1500];
        Arrays.fill(payload, 'A');
        cell.addProperty("tileNbt", new String(payload));
        cells.add(cell);
        source.add("cells", cells);
        AuthorEditor editor = new AuthorEditor(AuthorDocumentIO.readExpanded(source, limits), 8);
        editor.select(Collections.singleton(1L));
        AuthorDocument before = editor.current();
        reject(() -> editor.copy(1, 0, 0), "logical occurrence budget counts shared payload twice");
        check(editor.current() == before, "budget failure atomically retains old draft");
    }

    private static void attachmentsAndPatches() throws Exception {
        AuthorEditor editor = new AuthorEditor(
            read("{\"schema\":2,\"cells\":[{\"x\":8,\"block\":\"minecraft:air\"}]}"),
            16);
        JsonObject group = new JsonObject();
        group.addProperty("id", "text");
        group.addProperty("textBoxNbt", "opaque-native-text");
        group.addProperty("unknown", "keep");
        JsonObject cell = new JsonObject();
        cell.addProperty("x", 8);
        cell.addProperty("structureId", "text");
        cell.addProperty("block", "native:text");
        editor.replaceAttachment(
            editor.current()
                .revision(),
            group,
            Collections.singleton(cell));
        long first = editor.selection()
            .iterator()
            .next();
        check(
            editor.current()
                .cell(1) == null
                && editor.current()
                    .cell(first) != null,
            "attachment overwrites ungrouped air atomically");
        JsonObject replacementGroup = new JsonObject();
        replacementGroup.addProperty("id", "text");
        replacementGroup.addProperty("textBoxNbt", "new-native-text");
        JsonObject other = new JsonObject();
        other.addProperty("x", 9);
        other.addProperty("structureId", "text");
        editor.replaceAttachment(
            editor.current()
                .revision(),
            replacementGroup,
            Arrays.asList(cell, other));
        check(
            editor.current()
                .cellCount() == 2
                && editor.current()
                    .cell(first) == null,
            "attachment reshapes once with fresh cell IDs");
        check(
            "keep".equals(
                editor.current()
                    .structures()
                    .get(0)
                    .get("unknown")
                    .getAsString()),
            "attachment unknown extension preserved");
        AuthorDocument frozen = editor.current();
        long selected = editor.selection()
            .iterator()
            .next();
        JsonObject header = new JsonObject();
        header.addProperty("name", "atomic");
        JsonObject patch = new JsonObject();
        patch.addProperty("excluded", true);
        Map<Long, JsonObject> patches = new LinkedHashMap<>();
        patches.put(selected, patch);
        editor.patchDocument(header, patches);
        check(
            editor.current()
                .revision() == frozen.revision() + 1 && editor.current()
                    .cell(selected)
                    .excluded()
                && "atomic".equals(
                    editor.current()
                        .title()),
            "header and selected descriptor one history step");
        editor.undo();
        check(
            editor.current()
                .sourceCopy()
                .equals(frozen.sourceCopy()),
            "combined source undo");
        AuthorDocument fork = editor.current()
            .forkCopy();
        check(
            !fork.documentId()
                .equals(
                    editor.current()
                        .documentId())
                && fork.cell(selected) == editor.current()
                    .cell(selected),
            "Save As forks identity not source containers");
        editor.select(
            Arrays.asList(
                editor.current()
                    .cellAt(8, 0, 0)
                    .id(),
                editor.current()
                    .cellAt(9, 0, 0)
                    .id()));
        editor.delete();
        check(
            !editor.current().pages.iterator()
                .hasNext(),
            "empty pages reclaimed, monotonic IDs do not retain tombstone pages");
    }

    private static void regenerationAndResolution() throws Exception {
        AuthorEditor editor = new AuthorEditor(
            read(
                "{\"schema\":2,\"structures\":[{\"id\":\"a\",\"unknown\":1},{\"id\":\"b\"}],\"cells\":[{\"x\":0,\"structureIds\":[\"a\",\"b\"]},{\"x\":1,\"structureId\":\"a\",\"unknown\":7}]}"),
            16);
        JsonObject group = new JsonObject();
        group.addProperty("id", "a");
        group.addProperty("main", 2);
        JsonObject keep = new JsonObject();
        keep.addProperty("x", 1);
        keep.addProperty("structureId", "a");
        JsonObject added = new JsonObject();
        added.addProperty("x", 3);
        added.addProperty("structureId", "a");
        editor.replaceStructure(
            editor.current()
                .revision(),
            group,
            Arrays.asList(keep, added));
        check(
            editor.current()
                .cell(1)
                .memberships()
                .equals(Collections.singleton("b")),
            "regeneration retains shared source outside changed group");
        check(
            editor.current()
                .cell(2)
                .x() == 1
                && editor.current()
                    .cell(2)
                    .sourceCopy()
                    .get("unknown")
                    .getAsInt() == 7,
            "regeneration retains positional identity and unknowns");
        check(
            editor.current()
                .cellAt(3, 0, 0)
                .id() > 2
                && editor.current()
                    .cellCount() == 3,
            "regeneration adds fresh bounded identities");
        AuthorEditor nativeEditor = new AuthorEditor(
            read("{\"schema\":2,\"cells\":[{\"x\":0,\"tileNbt\":\"opaque\"}]}"),
            8);
        nativeEditor.select(Collections.singleton(1L));
        nativeEditor.move(10, 0, 0);
        nativeEditor.copy(5, 0, 0);
        long copied = nativeEditor.selection()
            .iterator()
            .next();
        check(
            nativeEditor.current()
                .cell(copied)
                .payloadX() == 0,
            "copy inherits translated ancestor native basis");
        AuthorDocument pending = nativeEditor.current();
        JsonObject resolved = pending.sourceCopy();
        nativeEditor.replaceResolvedSource(pending.revision(), resolved);
        check(
            !nativeEditor.current()
                .hasPendingObligations()
                && nativeEditor.current()
                    .cell(copied)
                    .payloadX() == 15,
            "explicit successful resolver retires obligations and rebases payloads");
        nativeEditor.undo();
        check(
            nativeEditor.current()
                .hasPendingObligations()
                && nativeEditor.current()
                    .cell(copied)
                    .payloadX() == 0,
            "undo restores exact unresolved provenance, not only geometry");
    }

    private static void annotationsAdmissionAndAdd() throws Exception {
        AuthorDocumentIO.Limits limits = new AuthorDocumentIO.Limits(4096, 20, 32, 4000, 3500, 1000, 1);
        AuthorEditor editor = new AuthorEditor(
            AuthorDocumentIO.read("{\"schema\":2,\"cells\":[{}]}".getBytes(StandardCharsets.UTF_8), limits),
            4);
        for (int i = 0; i < 20; i++) {
            JsonObject patch = new JsonObject();
            patch.addProperty("prompt", "annotation " + i);
            editor.patchCell(1, patch);
        }
        check(
            !editor.current()
                .hasPendingObligations()
                && !editor.current()
                    .hasPendingSpatialObligations(),
            "annotations do not fabricate native/spatial obligations or exhaust their budget");
        editor.replaceCellNbt(1, "opaque-slot-edit", null);
        check(
            !editor.current()
                .hasPendingObligations()
                && !editor.current()
                    .hasPendingSpatialObligations(),
            "selected native payload edits preserve basis without blanket unresolved events");
        JsonObject added = new JsonObject();
        added.addProperty("x", 4);
        added.addProperty("block", "minecraft:air");
        editor.addCell(added);
        long addedId = editor.selection()
            .iterator()
            .next();
        check(
            addedId > 1 && editor.current()
                .cell(addedId)
                .x() == 4,
            "addPosition allocates and selects a stable local source cell");
        AuthorDocument beforeCollision = editor.current();
        reject(() -> editor.addCell(added), "addPosition collision");
        check(editor.current() == beforeCollision, "addPosition collision preserves revision");
        JsonObject invalidMembership = new JsonObject();
        invalidMembership.addProperty("x", 8);
        invalidMembership.addProperty("structureId", "invented");
        reject(() -> editor.addCell(invalidMembership), "addPosition cannot invent membership");
        editor.undo();
        check(
            editor.current()
                .cell(addedId) == null,
            "addPosition undo");
        editor.redo();
        editor.select(Collections.singleton(1L));
        editor.move(1, 0, 0);
        check(
            editor.current()
                .hasPendingSpatialObligations()
                && editor.current()
                    .cell(1)
                    .payloadX() == 0,
            "real move remains spatially pending");
        JsonObject annotation = new JsonObject();
        annotation.addProperty("prompt", "after move");
        editor.patchCell(1, annotation);
        check(
            editor.current()
                .obligationCount() == 1
                && editor.current()
                    .cell(1)
                    .payloadX() == 0,
            "annotation preserves pending geometry without adding another event");
        AuthorDocumentIO.read(AuthorDocumentIO.writeDraft(editor.current(), limits), limits);
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray rows = new JsonArray();
        JsonObject raw = new JsonObject();
        char[] payload = new char[3300];
        Arrays.fill(payload, 'A');
        raw.addProperty("tileNbt", new String(payload));
        rows.add(raw);
        source.add("cells", rows);
        check(
            source.toString()
                .getBytes(StandardCharsets.UTF_8).length < limits.maxBytes,
            "near-limit fixture fits bare source");
        try {
            AuthorDocumentIO.readExpanded(source, limits);
            throw new AssertionError("Unsavable near-limit source must be rejected at admission");
        } catch (IOException expected) { /* durable draft overhead reserved at admission */ }
        raw.addProperty("tileNbt", "bounded");
        AuthorDocument accepted = AuthorDocumentIO.readExpanded(source, limits);
        AuthorDocument reopened = AuthorDocumentIO.read(AuthorDocumentIO.writeDraft(accepted, limits), limits);
        check(
            accepted.sourceCopy()
                .equals(reopened.sourceCopy()),
            "admitted source immediately roundtrips a bounded draft");
    }

    private static void boundedOwnedCopyAndByteOracle() throws Exception {
        com.google.gson.Gson oracle = new com.google.gson.GsonBuilder().serializeNulls()
            .disableHtmlEscaping()
            .create();
        String[] samples = { "", "ASCII", "quote\" slash\\ backspace\b formfeed\f line\n return\r tab\t",
            "\u0000\u0001\u001f", "中文汉字", "emoji \ud83d\ude00", "\u2028\u2029", "<>&='" };
        for (String sample : samples) {
            com.google.gson.JsonPrimitive text = new com.google.gson.JsonPrimitive(sample);
            long actual = oracle.toJson(text)
                .getBytes(StandardCharsets.UTF_8).length;
            check(
                AuthorDocumentIO.measure(text) == actual && AuthorDocumentIO.quotedBytes(sample) == actual,
                "exact escaped/Unicode JSON UTF8 byte oracle");
        }
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        source.addProperty("name", "中文 \ud83d\ude00");
        JsonArray cells = new JsonArray();
        JsonObject raw = new JsonObject();
        java.util.concurrent.atomic.AtomicInteger mutable = new java.util.concurrent.atomic.AtomicInteger(7);
        raw.addProperty("metadata", mutable);
        raw.addProperty("tileNbt", "shared-exact+/==");
        JsonObject extension = new JsonObject();
        extension.addProperty("future", "unchanged");
        raw.add("extension", extension);
        cells.add(raw);
        source.add("cells", cells);
        AuthorDocument document = AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
        check(
            document.retainedBytes == oracle.toJson(document.sourceCopy())
                .getBytes(StandardCharsets.UTF_8).length,
            "one-pass aggregate accounting matches actual writer bytes");
        check(
            document.cell(1).source.get("tileNbt") == raw.get("tileNbt"),
            "owned copy shares immutable payload primitive without encode/reparse");
        mutable.set(100);
        extension.addProperty("future", "mutated");
        raw.addProperty("x", 99);
        cells.add(new JsonObject());
        check(
            document.cellCount() == 1 && document.cell(1)
                .x() == 0
                && document.cell(1)
                    .metadata() == 7,
            "caller mutable Number and containers detached");
        check(
            "unchanged".equals(
                document.cell(1)
                    .sourceCopy()
                    .getAsJsonObject("extension")
                    .get("future")
                    .getAsString()),
            "nested unknown container cannot mutate owned source");
        byte[] draft = AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults());
        check(
            document.sourceCopy()
                .equals(
                    AuthorDocumentIO.read(draft, AuthorDocumentIO.Limits.defaults())
                        .sourceCopy()),
            "streamed writer/read preserve escaped and shared source values");
        for (String invalid : new String[] { "\ud800", "\udc00", "x\ud800y" }) {
            JsonObject isolated = new JsonObject();
            isolated.addProperty("schema", 2);
            isolated.addProperty("future", invalid);
            try {
                AuthorDocumentIO.readExpanded(isolated, AuthorDocumentIO.Limits.defaults());
                throw new AssertionError("Isolated Unicode surrogate admitted");
            } catch (IOException expected) { /* cannot silently turn into UTF-8 replacement question marks */ }
        }
        rejectIO("{\"schema\":2,\"future\":\"\\uD800\"}", "escaped isolated surrogate rejected before ownership");
        JsonObject invalidKey = new JsonObject();
        invalidKey.addProperty("schema", 2);
        invalidKey.addProperty("\udc00", "value");
        try {
            AuthorDocumentIO.readExpanded(invalidKey, AuthorDocumentIO.Limits.defaults());
            throw new AssertionError("Isolated surrogate key admitted");
        } catch (IOException expected) { /* keys obey the same scalar text contract */ }
        JsonObject invalidNumber = new JsonObject();
        invalidNumber.addProperty("schema", 2);
        invalidNumber.addProperty("future", Double.NaN);
        try {
            AuthorDocumentIO.readExpanded(invalidNumber, AuthorDocumentIO.Limits.defaults());
            throw new AssertionError("Non-JSON number admitted");
        } catch (IOException expected) { /* strict scalar domain without serialized staging */ }
        JsonObject cycle = new JsonObject();
        cycle.addProperty("schema", 2);
        cycle.add("cycle", cycle);
        try {
            AuthorDocumentIO.readExpanded(cycle, AuthorDocumentIO.Limits.defaults());
            throw new AssertionError("Cyclic caller tree admitted");
        } catch (IOException expected) { /* bounded depth before copy publication */ }
        AuthorDocumentIO.Limits tiny = new AuthorDocumentIO.Limits(2048, 20, 12, 1000, 2000, 100, 2);
        JsonObject occurrence = new JsonObject();
        occurrence.addProperty("schema", 2);
        JsonArray rows = new JsonArray();
        char[] chars = new char[600];
        Arrays.fill(chars, 'A');
        com.google.gson.JsonPrimitive shared = new com.google.gson.JsonPrimitive(new String(chars));
        for (int i = 0; i < 2; i++) {
            JsonObject row = new JsonObject();
            row.addProperty("x", i);
            row.add("tileNbt", shared);
            rows.add(row);
        }
        occurrence.add("cells", rows);
        try {
            AuthorDocumentIO.readExpanded(occurrence, tiny);
            throw new AssertionError("Shared primitive bypassed occurrence quota");
        } catch (IOException expected) { /* logical per-occurrence bytes, not unique retained strings */ }
    }

    private static void largeOpaqueAttachment() throws Exception {
        AuthorDocumentIO.Limits limits = AuthorDocumentIO.Limits.defaults();
        check(limits.maxStringChars == ((8 * 1024 * 1024 + 2) / 3) * 4, "legacy compressed NBT Base64 ceiling");
        check(
            limits.maxBytes == 64 * 1024 * 1024 && limits.maxDepth == 32,
            "attachment compatibility retains aggregate and depth guards");
        String encoded = largePicturePayload();
        check(
            encoded.length() > 2 * 1024 * 1024 && encoded.length() < limits.maxStringChars,
            "maximum-size high-entropy picture exceeds the unrelated compact-dictionary string cap");
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray structures = new JsonArray();
        JsonObject picture = new JsonObject();
        picture.addProperty("id", "gtmb_picture_00000000-0000-0000-0000-000000000001");
        picture.addProperty("textBoxNbt", encoded);
        structures.add(picture);
        source.add("structures", structures);
        source.add("cells", new JsonArray());
        AuthorDocument imported = AuthorDocumentIO.readExpanded(source, limits);
        check(
            imported.groups.get(
                picture.get("id")
                    .getAsString()).source.get("textBoxNbt")
                == picture.get("textBoxNbt"),
            "large encoded attachment remains an opaque shared immutable value, never decoded by import");
        byte[] draft = AuthorDocumentIO.writeDraft(imported, limits);
        AuthorDocument reopened = AuthorDocumentIO.read(draft, limits);
        check(
            encoded.equals(
                reopened.structures()
                    .get(0)
                    .get("textBoxNbt")
                    .getAsString()),
            "large picture NBT survives draft save/reopen byte-for-byte as encoded text");
        char[] tooLarge = new char[limits.maxStringChars + 1];
        Arrays.fill(tooLarge, 'A');
        picture.addProperty("textBoxNbt", new String(tooLarge));
        try {
            AuthorDocumentIO.readExpanded(source, limits);
            throw new AssertionError("Attachment string larger than legacy compressed NBT Base64 ceiling admitted");
        } catch (IOException expected) { /* independent per-string ceiling remains enforced */ }
    }

    /** Builds a real gzip/Base64 NBT picture fixture with Java I/O only; no native NBT decoder/classes. */
    private static String largePicturePayload() throws IOException {
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream output = new java.io.DataOutputStream(
            new java.io.BufferedOutputStream(new java.util.zip.GZIPOutputStream(compressed)))) {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(8);
            output.writeUTF("id");
            output.writeUTF("gtmb_picture_00000000-0000-0000-0000-000000000001");
            nbtInteger(output, "pixelX", 1);
            nbtInteger(output, "pixelY", 1);
            nbtInteger(output, "thickness", 1);
            output.writeByte(10);
            output.writeUTF("picture");
            output.writeByte(8);
            output.writeUTF("filename");
            output.writeUTF("large-picture.png");
            for (String key : new String[] { "originalWidth", "originalHeight", "sourceWidth", "sourceHeight", "width",
                "height", "shortSide" }) nbtInteger(output, key, 1024);
            nbtInteger(output, "saturation", 100);
            output.writeByte(1);
            output.writeUTF("keepAspect");
            output.writeByte(1);
            output.writeByte(11);
            output.writeUTF("source");
            output.writeInt(1024 * 1024);
            int random = 0x13579bdf;
            for (int i = 0; i < 1024 * 1024; i++) {
                random ^= random << 13;
                random ^= random >>> 17;
                random ^= random << 5;
                output.writeInt(random);
            }
            output.writeByte(0);
            output.writeByte(0);
        }
        check(compressed.size() <= 8 * 1024 * 1024, "picture fixture within native compressed-byte budget");
        return java.util.Base64.getEncoder()
            .encodeToString(compressed.toByteArray());
    }

    private static void nbtInteger(java.io.DataOutputStream output, String key, int value) throws IOException {
        output.writeByte(3);
        output.writeUTF(key);
        output.writeInt(value);
    }

    private static void treeIndex() {
        AuthorTree<Integer, Integer> tree = new AuthorTree<>();
        for (int i = 0; i < 10000; i++) tree = tree.put(i, i * 2);
        AuthorTree<Integer, Integer> old = tree;
        for (int i = 0; i < 10000; i += 2) tree = tree.remove(i);
        for (int i = 0; i < 10000; i++) check(
            old.get(i) == i * 2 && (i % 2 == 0 ? tree.get(i) == null : tree.get(i) == i * 2),
            "persistent balanced index");
    }

    private static AuthorDocument read(String json) throws IOException {
        return AuthorDocumentIO.read(json.getBytes(StandardCharsets.UTF_8), AuthorDocumentIO.Limits.defaults());
    }

    private static void rejectIO(String json, String label) throws Exception {
        try {
            read(json);
            throw new AssertionError("Expected rejection: " + label);
        } catch (IOException expected) { /* admission */ }
    }

    private static void reject(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError("Expected rejection: " + label);
        } catch (IllegalArgumentException | IllegalStateException expected) { /* atomic rejected command */ }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
