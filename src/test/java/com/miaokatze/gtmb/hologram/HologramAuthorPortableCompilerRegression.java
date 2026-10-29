package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Detached source/native boundary evidence, not a GL capture or live-world permission test. */
public final class HologramAuthorPortableCompilerRegression {

    private HologramAuthorPortableCompilerRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorPortableCompilerRegression.class, args)) return;
        run();
        System.out.println("Author portable compiler regression passed");
    }

    public static void run() throws Exception {
        opaqueNoop();
        plainGeometry();
        relativeCoordinates();
        indexedCopiedEndpoints();
        littleTiles();
        unsupportedAndSealed();
        freshProcessing();
        explicitNullSourceBoundaries();
        captureSourceFrames();
        crossDimensionSettlement();
    }

    private static void opaqueNoop() throws Exception {
        JsonObject cell = cell(0);
        cell.addProperty("vendorExtension", "exact");
        NBTTagCompound opaque = new NBTTagCompound();
        opaque.setString("id", "unknownNative");
        opaque.setString("opaque", "keep");
        cell.addProperty("tileNbt", HologramBlueprintNbt.encodeCaptured(opaque));
        JsonObject source = source(cell);
        source.addProperty("unknownHeader", "retained");
        AuthorDocument document = read(source);
        SimpleAssert.eq(source, settle(document), "no-op boundary preserves raw unknown members and encoded NBT");
        SimpleAssert.eq(source, document.sourceCopy(), "no-op never mutates source");
        reject(() -> {
            HologramAuthorPortableCompiler.requireKnownTransformExtensions(document);
            return null;
        }, "whole orientation cannot bypass unresolved extension guard when no translation is pending");
        SimpleAssert.eq(source, document.sourceCopy(), "rejected orientation leaves opaque source exact");
        JsonObject annotated = source(cell(0));
        JsonArray hints = new JsonArray();
        JsonObject hint = new JsonObject();
        hint.addProperty("x1", 0);
        hint.addProperty("y1", 0);
        hint.addProperty("z1", 0);
        hint.addProperty("x2", 1);
        hint.addProperty("y2", 1);
        hint.addProperty("z2", 1);
        hint.addProperty("futureSpatialHint", "retain");
        hints.add(hint);
        annotated.add("hintAreas", hints);
        AuthorDocument withHint = read(annotated);
        reject(() -> {
            HologramAuthorPortableCompiler.requireKnownTransformExtensions(withHint);
            return null;
        }, "whole orientation also guards transformed hint-area extensions");
    }

    private static void plainGeometry() throws Exception {
        JsonObject declared = cell(0);
        declared.addProperty("authorDeclarationComplete", true);
        AuthorEditor editor = new AuthorEditor(read(source(declared)), 8);
        HologramAuthorPortableCompiler.requireKnownTransformExtensions(editor.current());
        editor.select(Collections.singleton(1L));
        editor.move(2, 0, 0);
        editor.copy(3, 0, 0);
        AuthorEditor.Clipboard clipboard = editor.clipboard();
        editor.paste(clipboard, 9, 0, 0);
        AuthorDocument reopened = AuthorDocumentIO.read(
            AuthorDocumentIO.writeDraft(editor.current(), AuthorDocumentIO.Limits.defaults()),
            AuthorDocumentIO.Limits.defaults());
        JsonObject result = settle(reopened);
        SimpleAssert.eq(
            3,
            result.getAsJsonArray("cells")
                .size(),
            "moved/copy/paste records all survive");
        SimpleAssert.that(
            result.getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject()
                .get("authorDeclarationComplete")
                .getAsBoolean(),
            "own nonspatial declaration fact does not block geometry");
        SimpleAssert.eq(
            9,
            result.getAsJsonArray("cells")
                .get(2)
                .getAsJsonObject()
                .get("x")
                .getAsInt(),
            "clipboard final geometry not displaced again");
        SimpleAssert.eq(
            0,
            reopened.cell(3)
                .payloadX(),
            "clipboard preserves inherited payload origin across recovery");
        SimpleAssert.that(reopened.hasPendingObligations(), "compiler cannot clear authoritative draft obligations");
    }

    private static void relativeCoordinates() throws Exception {
        NBTTagCompound tile = new NBTTagCompound();
        tile.setString("id", "IC2NCInfoPanelExtender");
        tile.setBoolean("partOfScreen", true);
        tile.setInteger("coreX", 1);
        tile.setInteger("coreY", 0);
        tile.setInteger("coreZ", 0);
        HologramCoordinatePolicies.Relation relation = new HologramCoordinatePolicies.Relation();
        relation.id = "0,0,0:tile:nc.extender:-1";
        relation.label = "core";
        relation.adapter = "nc.extender";
        relation.slot = -1;
        relation.dx = 1;
        relation.internal = true;
        NBTTagList relations = new NBTTagList();
        relations.appendTag(relation.write("relative"));
        tile.setTag(HologramCoordinatePolicies.RELATIONS, relations);
        JsonObject host = cell(0);
        host.addProperty("tileNbt", HologramBlueprintNbt.encodeCanonical(tile));
        AuthorEditor editor = new AuthorEditor(read(source(host, cell(1))), 8);
        editor.select(Collections.singleton(1L));
        editor.move(5, 0, 0);
        String original = editor.current()
            .cell(1)
            .tileNbt();
        NBTTagCompound settled = HologramBlueprintNbt.decode(
            settle(editor.current()).getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject()
                .get("tileNbt")
                .getAsString());
        NBTTagCompound row = settled.getTagList(HologramCoordinatePolicies.RELATIONS, 10)
            .getCompoundTagAt(0);
        SimpleAssert.eq(-4, row.getInteger("dx"), "moving host alone retains actual internal endpoint");
        SimpleAssert.eq(5, row.getInteger("x"), "native relation host moves to final coordinates");
        SimpleAssert.eq("5,0,0:tile:nc.extender:-1", row.getString("id"), "relation identity follows final known host");
        SimpleAssert.eq(
            original,
            editor.current()
                .cell(1)
                .tileNbt(),
            "native coordinate resolution stays detached");
        AuthorEditor endpoint = new AuthorEditor(read(source(host, cell(1))), 8);
        endpoint.select(Collections.singleton(2L));
        endpoint.move(7, 0, 0);
        SimpleAssert.eq(
            8,
            relation(settle(endpoint.current())).getInteger("dx"),
            "stationary host follows moved internal endpoint");
        endpoint.copy(3, 0, 0);
        reject(() -> settle(endpoint.current()), "two moved copies without original endpoint cannot be guessed");
        AuthorEditor copiedEndpoint = new AuthorEditor(read(source(host, cell(1))), 8);
        copiedEndpoint.select(Collections.singleton(2L));
        copiedEndpoint.copy(3, 0, 0);
        SimpleAssert.eq(
            1,
            relation(settle(copiedEndpoint.current())).getInteger("dx"),
            "stationary host retains original endpoint when a copy is added");
        AuthorEditor external = new AuthorEditor(read(source(host)), 8);
        external.select(Collections.singleton(1L));
        external.move(5, 0, 0);
        SimpleAssert.eq(
            -4,
            relation(settle(external.current())).getInteger("dx"),
            "external relative reference retains original endpoint instead of following nonexistent target");
        AuthorEditor motif = new AuthorEditor(read(source(host, cell(1))), 8);
        motif.select(Arrays.asList(1L, 2L));
        motif.copy(5, 2, -3);
        JsonObject copiedMotif = settle(motif.current());
        SimpleAssert.eq(
            1,
            relationAt(copiedMotif, 2).getInteger("dx"),
            "copied linked motif retains its internal endpoint delta");
        SimpleAssert
            .eq(0, relationAt(copiedMotif, 2).getInteger("dy"), "copied linked motif uses the matching y translation");
        SimpleAssert
            .eq(0, relationAt(copiedMotif, 2).getInteger("dz"), "copied linked motif uses the matching z translation");
        AuthorEditor excludedEndpoint = new AuthorEditor(read(source(host, cell(1))), 8);
        excludedEndpoint.select(Collections.singleton(2L));
        excludedEndpoint.move(7, 0, 0);
        excludedEndpoint.setExcluded(true);
        SimpleAssert.eq(
            1,
            relation(settle(excludedEndpoint.current())).getInteger("dx"),
            "excluded moved endpoint cannot redirect the retained external reference");
        relation.targetX = 1;
        relations = new NBTTagList();
        relations.appendTag(relation.write("absolute"));
        tile.setTag(HologramCoordinatePolicies.RELATIONS, relations);
        JsonObject absoluteHost = cell(0);
        absoluteHost.addProperty("tileNbt", HologramBlueprintNbt.encodeCanonical(tile));
        AuthorEditor absolute = new AuthorEditor(read(source(absoluteHost, cell(1))), 8);
        absolute.select(Collections.singleton(2L));
        absolute.move(7, 0, 0);
        absolute.copy(3, 0, 0);
        SimpleAssert.eq(
            1,
            relation(settle(absolute.current())).getInteger("targetX"),
            "absolute endpoint never guesses between moved/copied former targets");
        relations = new NBTTagList();
        relations.appendTag(relation.write("clear"));
        tile.setTag(HologramCoordinatePolicies.RELATIONS, relations);
        JsonObject clearedHost = cell(2);
        clearedHost.addProperty("tileNbt", HologramBlueprintNbt.encodeCanonical(tile));
        AuthorEditor cleared = new AuthorEditor(read(source(clearedHost, cell(0))), 8);
        cleared.select(Collections.singleton(2L));
        cleared.move(7, 0, 0);
        cleared.copy(3, 0, 0);
        SimpleAssert.eq(
            "clear",
            relation(settle(cleared.current())).getString("mode"),
            "cleared endpoint ignores unrelated origin-zero copy ambiguity");
    }

    private static void indexedCopiedEndpoints() throws Exception {
        int copies = 4096;
        JsonObject[] rows = new JsonObject[copies * 2];
        for (int i = 0; i < copies; i++) {
            rows[i * 2] = cell(i * 3);
            rows[i * 2 + 1] = cell(i * 3 + 1);
            // Distinct three-dimensional translations, not just the common x-only case.
            rows[i * 2].addProperty("y", i % 17);
            rows[i * 2 + 1].addProperty("y", i % 17);
            rows[i * 2].addProperty("z", -i);
            rows[i * 2 + 1].addProperty("z", -i);
        }
        JsonObject draft = new com.google.gson.JsonParser()
            .parse(
                new String(
                    AuthorDocumentIO.writeDraft(read(source(rows)), AuthorDocumentIO.Limits.defaults()),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonArray origins = new JsonArray();
        for (int i = 0; i < rows.length; i++) {
            JsonArray origin = new JsonArray();
            origin.add(new com.google.gson.JsonPrimitive(i % 2));
            origin.add(new com.google.gson.JsonPrimitive(0));
            origin.add(new com.google.gson.JsonPrimitive(0));
            origins.add(origin);
        }
        draft.add("payloadOrigins", origins);
        java.util.List<AuthorDocument.Cell> cells = new java.util.ArrayList<>();
        for (AuthorDocument.Cell cell : read(draft).cells()) cells.add(cell);
        final int[] sourceReads = { 0 };
        java.util.List<AuthorDocument.Cell> counted = new java.util.AbstractList<AuthorDocument.Cell>() {

            @Override
            public AuthorDocument.Cell get(int index) {
                sourceReads[0]++;
                return cells.get(index);
            }

            @Override
            public int size() {
                return cells.size();
            }
        };
        HologramAuthorPortableCompiler.EndpointIndex index = new HologramAuthorPortableCompiler.EndpointIndex(
            counted,
            Collections.emptySet());
        SimpleAssert.eq(cells.size(), sourceReads[0], "endpoint index visits each source cell exactly once");
        for (int i = 0; i < copies; i++) SimpleAssert.eq(
            Integer.valueOf(i * 2 + 1),
            index.target(1, 0, 0, cells.get(i * 2)),
            "each copied host resolves only its matching translated endpoint");
        SimpleAssert.eq(
            cells.size(),
            sourceReads[0],
            "thousands of endpoint lookups never revisit source candidates (no quadratic source-list scan)");
        SimpleAssert.eq(null, index.target(2, 0, 0, cells.get(0)), "missing payload origin stays external");
        HologramAuthorPortableCompiler.EndpointIndex ambiguous = new HologramAuthorPortableCompiler.EndpointIndex(
            Arrays.asList(cells.get(1), cells.get(1)),
            Collections.emptySet());
        reject(() -> ambiguous.target(1, 0, 0, cells.get(0)), "duplicate same-shift endpoint stays ambiguous");
        HologramAuthorPortableCompiler.EndpointIndex unrelatedDuplicate = new HologramAuthorPortableCompiler.EndpointIndex(
            Arrays.asList(cells.get(1), cells.get(1), cells.get(3)),
            Collections.emptySet());
        SimpleAssert.eq(
            Integer.valueOf(2),
            unrelatedDuplicate.target(1, 0, 0, cells.get(2)),
            "duplicate at another shift cannot invalidate a unique matching endpoint");
        HologramAuthorPortableCompiler.EndpointIndex fallback = new HologramAuthorPortableCompiler.EndpointIndex(
            Collections.singletonList(cells.get(3)),
            Collections.emptySet());
        SimpleAssert.eq(
            Integer.valueOf(0),
            fallback.target(1, 0, 0, cells.get(0)),
            "stationary host follows a sole moved endpoint");
        JsonObject excluded = cell(1), disabled = cell(2), enabled = cell(3);
        excluded.addProperty("excluded", true);
        disabled.addProperty("structureId", "disabled");
        enabled.addProperty("structureId", "enabled");
        JsonObject filteredSource = source(excluded, disabled, enabled);
        for (String id : new String[] { "disabled", "enabled" }) {
            JsonObject structure = new JsonObject();
            structure.addProperty("id", id);
            structure.addProperty("enabled", "enabled".equals(id));
            filteredSource.getAsJsonArray("structures")
                .add(structure);
        }
        java.util.List<AuthorDocument.Cell> filtered = new java.util.ArrayList<>();
        for (AuthorDocument.Cell cell : read(filteredSource).cells()) filtered.add(cell);
        HologramAuthorPortableCompiler.EndpointIndex eligible = new HologramAuthorPortableCompiler.EndpointIndex(
            filtered,
            Collections.singleton("enabled"));
        SimpleAssert.eq(null, eligible.target(1, 0, 0, cells.get(0)), "excluded endpoint is not indexed");
        SimpleAssert.eq(null, eligible.target(2, 0, 0, cells.get(0)), "disabled-membership endpoint is not indexed");
        SimpleAssert.eq(
            Integer.valueOf(2),
            eligible.target(3, 0, 0, cells.get(0)),
            "enabled-membership endpoint remains eligible");
    }

    private static void littleTiles() throws Exception {
        JsonObject main = little(0, true), child = little(1, false);
        AuthorEditor editor = new AuthorEditor(read(source(main, child)), 8);
        editor.select(Arrays.asList(1L, 2L));
        editor.move(10, 0, 0);
        JsonObject result = settle(editor.current());
        SimpleAssert.eq(
            main.get("tileNbt"),
            result.getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject()
                .get("tileNbt"),
            "complete LT translation keeps exact encoded references");
        editor.select(Collections.singleton(1L));
        editor.move(2, 0, 0);
        reject(() -> settle(editor.current()), "incomplete LT graph is an explicit failure");
        SimpleAssert.eq(
            12,
            editor.current()
                .cell(1)
                .x(),
            "failed native boundary retains editable geometry intent");
    }

    private static void unsupportedAndSealed() throws Exception {
        JsonObject extended = cell(0);
        extended.addProperty("unknownSpatialExtension", "opaque");
        AuthorEditor editor = new AuthorEditor(read(source(extended)), 8);
        editor.select(Collections.singleton(1L));
        editor.copy(2, 0, 0);
        String before = editor.current()
            .sourceCopy()
            .toString();
        reject(() -> settle(editor.current()), "copy of unresolved extension cannot claim portable correctness");
        SimpleAssert.eq(
            before,
            editor.current()
                .sourceCopy()
                .toString(),
            "unsupported copy retains all source data");
        JsonObject sealed = cell(0);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setTag(HologramRecovery.TAG, new NBTTagCompound());
        sealed.addProperty("itemNbt", HologramBlueprintNbt.encodeCaptured(payload));
        AuthorEditor seal = new AuthorEditor(read(source(sealed)), 8);
        seal.select(Collections.singleton(1L));
        seal.copy(2, 0, 0);
        reject(() -> settle(seal.current()), "copy cannot duplicate physical sealed inventory");
    }

    private static void freshProcessing() throws Exception {
        AuthorDocument document = read(source(cell(0)));
        reject(
            () -> HologramAuthorPortableCompiler.compile(document, null, 0, 0, 0, 0),
            "missing user policy is never auto-confirmed");
        HologramBlueprintProcessing.Policy policy = policy();
        policy.confirmedStages.clear();
        reject(
            () -> HologramAuthorPortableCompiler.compile(document, policy.write(), 0, 0, 0, 0),
            "unconfirmed stages cannot export");
        byte[] bytes = HologramAuthorPortableCompiler.compile(document, policy().write(), 0, 0, 0, 0);
        JsonObject wrapped = HologramBlueprintCompactCodec.parse(bytes);
        SimpleAssert.that(wrapped.has("blueprint"), "portable bytes are actual wrapped source, not display NBT");
        SimpleAssert.that(
            !wrapped.getAsJsonObject("blueprint")
                .has("authorUuid"),
            "compiler never invents server actor identity");
        SimpleAssert.that(
            !document.sourceCopy()
                .has("processingNbt"),
            "fresh processed candidate does not overwrite source draft");
    }

    private static void explicitNullSourceBoundaries() throws Exception {
        JsonObject row = cell(0);
        addNullExtensions(row);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("vendor", "opaque source payload");
        row.addProperty("itemNbt", HologramBlueprintNbt.encodeCaptured(payload));
        JsonObject raw = source(row);
        addNullExtensions(raw);
        JsonObject envelope = new JsonObject();
        addNullExtensions(envelope);
        envelope.add("blueprint", raw);
        AuthorDocument document = read(envelope);
        byte[] original = AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults());
        String originalSource = document.sourceCopy()
            .toString();
        String originalPayload = document.cell(1)
            .itemNbt();

        JsonObject local = HologramLocalAuthorSemantics.process(raw, policy().write(), 0, 0, 0, 0);
        assertNullBlueprint(local, "local processing");
        JsonObject candidate = HologramBlueprintCompactCodec
            .parse(HologramAuthorPortableCompiler.compile(document, policy().write(), 0, 0, 0, 0));
        assertNullExtensions(candidate, "offline envelope");
        assertNullBlueprint(candidate.getAsJsonObject("blueprint"), "offline candidate");
        SimpleAssert.that(
            !candidate.getAsJsonObject("blueprint")
                .has("authorUuid"),
            "raw source encoding does not invent actor identity");

        // Selected context is read-only; test its fidelity separately from the destructive process-install path.
        JsonObject selected = AuthorDocumentIO
            .read(HologramAuthorReviewProtocol.selectedCellSource(document, 1), HologramAuthorReviewProtocol.limits())
            .sourceCopy();
        assertNullBlueprint(selected, "selected inspector context");
        SimpleAssert
            .that(!selected.has("blueprint"), "selected inspector remains bare and limited to selected context");

        java.lang.reflect.Field owner = HologramWorkBudget.class.getDeclaredField("serverOwner");
        owner.setAccessible(true);
        Object previousOwner = owner.get(null);
        HologramWorkBudget.startServerTick();
        HologramWorkBudget.endServerTick();
        try {
            HologramAuthorServerReview.Result reviewed = HologramAuthorServerReview
                .review(document, "process", policy().write());
            NBTTagCompound response = HologramAuthorReviewProtocol
                .decode(HologramAuthorReviewProtocol.encode(HologramAuthorReviewService.resultPayload(reviewed, null)));
            JsonObject processed = AuthorDocumentIO
                .read(response.getByteArray("source"), HologramAuthorReviewProtocol.limits())
                .sourceCopy();
            assertNullBlueprint(processed, "server process wire source");
            SimpleAssert
                .eq(local.get("vendor"), processed.get("vendor"), "local and server unknown nested values agree");
            JsonObject replacement = document.sourceCopy();
            replacement.add("blueprint", processed);
            AuthorEditor editor = new AuthorEditor(document, 8);
            editor.replaceResolvedSource(document.revision(), replacement);
            assertNullBlueprint(
                editor.current()
                    .sourceCopy()
                    .getAsJsonObject("blueprint"),
                "installed server process");
            AuthorDocument reopened = AuthorDocumentIO.read(
                AuthorDocumentIO.writeDraft(editor.current(), AuthorDocumentIO.Limits.defaults()),
                AuthorDocumentIO.Limits.defaults());
            assertNullExtensions(reopened.sourceCopy(), "processed recovery envelope");
            assertNullBlueprint(
                reopened.sourceCopy()
                    .getAsJsonObject("blueprint"),
                "processed recovery source");
            editor.undo();
            SimpleAssert.eq(
                originalSource,
                editor.current()
                    .sourceCopy()
                    .toString(),
                "undo restores exact original source");
            SimpleAssert.eq(
                originalPayload,
                editor.current()
                    .cell(1)
                    .itemNbt(),
                "undo restores opaque original payload");
            editor.redo();
            assertNullBlueprint(
                editor.current()
                    .sourceCopy()
                    .getAsJsonObject("blueprint"),
                "redo retains null source fields");

            HologramAuthorServerReview.Result compiled = HologramAuthorServerReview
                .review(document, "compile", policy().write());
            JsonObject serverCandidate = HologramBlueprintCompactCodec.parse(compiled.compiled);
            assertNullExtensions(serverCandidate, "server compiled envelope");
            assertNullBlueprint(serverCandidate.getAsJsonObject("blueprint"), "server compiled candidate");
        } finally {
            owner.set(null, previousOwner);
        }
        SimpleAssert.eq(
            originalSource,
            document.sourceCopy()
                .toString(),
            "compile/review never mutate frozen raw source");
        SimpleAssert.eq(
            originalPayload,
            document.cell(1)
                .itemNbt(),
            "compile/review never mutate opaque source payload");
        SimpleAssert.that(
            Arrays.equals(original, AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults())),
            "compile/review retain exact original recovery bytes");
    }

    private static void addNullExtensions(JsonObject object) {
        object.add("vendorNull", com.google.gson.JsonNull.INSTANCE);
        JsonObject vendor = new JsonObject();
        vendor.add("nested", com.google.gson.JsonNull.INSTANCE);
        JsonArray array = new JsonArray();
        array.add(com.google.gson.JsonNull.INSTANCE);
        array.add(new com.google.gson.JsonPrimitive("middle"));
        JsonObject tail = new JsonObject();
        tail.add("last", com.google.gson.JsonNull.INSTANCE);
        array.add(tail);
        vendor.add("array", array);
        object.add("vendor", vendor);
    }

    private static void assertNullBlueprint(JsonObject object, String label) {
        assertNullExtensions(object, label + " header");
        assertNullExtensions(
            object.getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject(),
            label + " cell");
    }

    private static void assertNullExtensions(JsonObject object, String label) {
        SimpleAssert.that(
            object.has("vendorNull") && object.get("vendorNull")
                .isJsonNull(),
            label + " explicit null");
        SimpleAssert.that(!object.has("vendorAbsent"), label + " absent member stays absent");
        JsonObject vendor = object.getAsJsonObject("vendor");
        SimpleAssert.that(
            vendor.has("nested") && vendor.get("nested")
                .isJsonNull(),
            label + " nested null");
        JsonArray array = vendor.getAsJsonArray("array");
        SimpleAssert.that(
            array.size() == 3 && array.get(0)
                .isJsonNull()
                && "middle".equals(
                    array.get(1)
                        .getAsString())
                && array.get(2)
                    .getAsJsonObject()
                    .has("last")
                && array.get(2)
                    .getAsJsonObject()
                    .get("last")
                    .isJsonNull(),
            label + " ordered array and object nulls");
    }

    private static void captureSourceFrames() throws Exception {
        for (int originX : new int[] { 100, -100 }) {
            AuthorDocument.SourceFrame frame = AuthorDocument.SourceFrame.captured(7, originX, 64, 200);
            NBTTagCompound tile = new NBTTagCompound();
            tile.setString("id", "IC2NCInfoPanelExtender");
            tile.setBoolean("partOfScreen", true);
            tile.setInteger("coreX", originX + 1);
            tile.setInteger("coreY", 64);
            tile.setInteger("coreZ", 200);
            JsonObject host = cell(0);
            String opaque = HologramBlueprintNbt.encodeCaptured(tile);
            host.addProperty("tileNbt", opaque);
            JsonObject raw = source(host, cell(1));
            HologramBlueprintProcessing.Policy policy = policy();
            policy.coordinateDefault = "auto";
            policy.tendency = "share";
            AuthorDocument unknown = read(raw);
            reject(
                () -> HologramAuthorPortableCompiler.compile(unknown, policy.write()),
                "old raw world links without provenance must not silently clear at zero");
            SimpleAssert.eq(
                opaque,
                unknown.cell(1)
                    .tileNbt(),
                "missing provenance refusal keeps exact source");
            AuthorDocument captured = unknown.withSourceFrame(frame);
            byte[] transport = HologramBlueprintCompactCodec
                .encodeAuthorSource(HologramLocalAuthorSemantics.preset(raw), frame, (done, total) -> {});
            AuthorDocument transferred = read(HologramBlueprintCompactCodec.parse(transport));
            SimpleAssert.eq(frame, transferred.sourceFrame(), "compact capture handoff retains source frame");
            SimpleAssert.eq(
                opaque,
                transferred.cell(1)
                    .tileNbt(),
                "handoff does not eagerly normalize native payload");
            JsonObject direct = HologramLocalAuthorSemantics.process(raw, policy.write(), frame);
            SimpleAssert.eq("relative", relation(direct).getString("mode"), "nonzero captured link remains internal");
            SimpleAssert.eq(1, relation(direct).getInteger("dx"), "source origin subtracted exactly once");
            JsonObject turned = rotateCoordinateFixture(raw, frame);
            int[] expectedTurn = HologramVirtualTransform.turn(1, 0, 0, false);
            SimpleAssert.eq(
                "relative",
                relation(turned).getString("mode"),
                "raw capture rotation declares its internal link before transforming geometry");
            SimpleAssert.eq(expectedTurn[0], relation(turned).getInteger("dx"), "rotated raw link x");
            SimpleAssert.eq(expectedTurn[2], relation(turned).getInteger("dz"), "rotated raw link z");
            SimpleAssert.that(!turned.has("processingNbt"), "rotation cannot manufacture processing approval");
            AuthorEditor rotatedEditor = new AuthorEditor(captured, 8);
            rotatedEditor.replaceResolvedSource(captured.revision(), turned);
            SimpleAssert.eq(
                frame,
                rotatedEditor.current()
                    .sourceFrame(),
                "whole rotation retains capture frame");
            JsonObject rotatedPortable = HologramBlueprintCompactCodec
                .parse(HologramAuthorPortableCompiler.compile(rotatedEditor.current(), policy.write()))
                .getAsJsonObject("blueprint");
            SimpleAssert.eq(
                expectedTurn[2],
                relation(rotatedPortable).getInteger("dz"),
                "rotation then compile does not restore the unrotated raw endpoint");
            AuthorEditor editor = new AuthorEditor(captured, 8);
            editor.select(Arrays.asList(1L, 2L));
            editor.move(3, 0, 0);
            editor.copy(10, 0, 0);
            AuthorDocument reopened = AuthorDocumentIO.read(
                AuthorDocumentIO.writeDraft(editor.current(), AuthorDocumentIO.Limits.defaults()),
                AuthorDocumentIO.Limits.defaults());
            JsonObject settled = HologramAuthorPortableCompiler.settle(reopened, policy.write());
            for (int index : new int[] { 0, 2 }) {
                NBTTagCompound descriptor = relationAt(settled, index);
                SimpleAssert.eq("relative", descriptor.getString("mode"), "move/copy retains captured internal links");
                SimpleAssert.eq(1, descriptor.getInteger("dx"), "copy points to its matching copied endpoint");
            }
            JsonObject processed = HologramLocalAuthorSemantics.process(settled, policy.write(), frame);
            AuthorEditor resolved = new AuthorEditor(reopened, 8);
            resolved.replaceResolvedSource(reopened.revision(), processed);
            SimpleAssert.eq(
                frame,
                resolved.current()
                    .sourceFrame(),
                "processing retains world frame");
            SimpleAssert.eq(
                3,
                resolved.current()
                    .cell(1)
                    .payloadX(),
                "processing rebases only local payload origin");
            byte[] compiled = HologramAuthorPortableCompiler.compile(resolved.current(), policy.write());
            JsonObject portable = HologramBlueprintCompactCodec.parse(compiled)
                .getAsJsonObject("blueprint");
            SimpleAssert.eq(
                relationAt(processed, 0),
                relationAt(portable, 0),
                "postprocess compile does not double-offset captured links");
            SimpleAssert.eq(
                relationAt(processed, 2),
                relationAt(portable, 2),
                "copied link remains stable through postprocess compile");
            // Portable relative descriptors remain usable without capture provenance on import.
            HologramAuthorPortableCompiler.compile(read(portable), policy.write());
            HologramCoordinatePolicies.Relation absolute = new HologramCoordinatePolicies.Relation();
            absolute.id = "0,0,0:tile:nc.extender:-1";
            absolute.label = "core";
            absolute.adapter = "nc.extender";
            absolute.slot = -1;
            absolute.targetX = originX + 1;
            absolute.targetY = 64;
            absolute.targetZ = 200;
            NBTTagList declarations = new NBTTagList();
            declarations.appendTag(absolute.write("absolute"));
            tile.setTag(HologramCoordinatePolicies.RELATIONS, declarations);
            JsonObject fixed = cell(0);
            fixed.addProperty("tileNbt", HologramBlueprintNbt.encodeCanonical(tile));
            AuthorEditor absoluteEditor = new AuthorEditor(read(source(fixed, cell(1))).withSourceFrame(frame), 8);
            absoluteEditor.select(Collections.singleton(1L));
            absoluteEditor.move(6, 0, 0);
            SimpleAssert.eq(
                originX + 1,
                relation(HologramAuthorPortableCompiler.settle(absoluteEditor.current(), policy().write()))
                    .getInteger("targetX"),
                "absolute world endpoint never shifted with capture origin");
            JsonObject rotatedAbsolute = rotateCoordinateFixture(source(fixed, cell(1)), frame);
            SimpleAssert.eq(
                "absolute",
                relation(rotatedAbsolute).getString("mode"),
                "rotation preserves explicitly absolute mode");
            SimpleAssert.eq(
                originX + 1,
                relation(rotatedAbsolute).getInteger("targetX"),
                "rotation preserves exact absolute world endpoint");
            declarations = new NBTTagList();
            declarations.appendTag(absolute.write("clear"));
            tile.setTag(HologramCoordinatePolicies.RELATIONS, declarations);
            fixed.addProperty("tileNbt", HologramBlueprintNbt.encodeCanonical(tile));
            SimpleAssert.eq(
                "clear",
                relation(rotateCoordinateFixture(source(fixed, cell(1)), frame)).getString("mode"),
                "rotation cannot resurrect an explicitly cleared endpoint");
        }
        AuthorDocument.SourceFrame frame = AuthorDocument.SourceFrame.captured(7, 100, 64, 200);
        for (int targetDimension : new int[] { 7, 8 }) {
            NBTTagCompound tile = new NBTTagCompound(), target = new NBTTagCompound();
            target.setInteger("x", 101);
            target.setInteger("y", 64);
            target.setInteger("z", 200);
            target.setInteger("dim", targetDimension);
            target.setString("type", "water");
            tile.setTag("gtsr.hubPos", target);
            JsonObject host = cell(0);
            host.addProperty("tileNbt", HologramBlueprintNbt.encodeCaptured(tile));
            try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
                HologramCoordinatePolicies.Relation relation = HologramCoordinatePolicies
                    .inspect(
                        HologramLocalAuthorSemantics.preset(source(host, cell(1))),
                        frame.dimension,
                        frame.x,
                        frame.y,
                        frame.z,
                        facts)
                    .get(0);
                SimpleAssert.eq(
                    targetDimension == 7,
                    relation.internal,
                    "capture dimension distinguishes internal hub from cross-dimension world endpoint");
            }
        }
    }

    private static void crossDimensionSettlement() throws Exception {
        AuthorDocument.SourceFrame frame = AuthorDocument.SourceFrame.captured(7, 100, 64, 200);
        for (int targetDimension : new int[] { 7, 8 }) {
            for (String tendency : new String[] { "self", "share" }) {
                for (boolean copied : new boolean[] { false, true }) {
                    NBTTagCompound tile = new NBTTagCompound(), target = new NBTTagCompound();
                    target.setInteger("x", 101);
                    target.setInteger("y", 64);
                    target.setInteger("z", 200);
                    target.setInteger("dim", targetDimension);
                    target.setString("type", "water");
                    tile.setTag("gtsr.hubPos", target);
                    JsonObject host = cell(0);
                    host.addProperty("tileNbt", HologramBlueprintNbt.encodeCaptured(tile));
                    AuthorEditor editor = new AuthorEditor(read(source(host, cell(1))).withSourceFrame(frame), 8);
                    editor.select(Collections.singleton(2L));
                    editor.move(5, 0, 0);
                    if (copied) editor.copy(3, 0, 0);
                    AuthorDocument document = editor.current();
                    JsonObject before = document.sourceCopy();
                    HologramBlueprintProcessing.Policy policy = policy();
                    policy.tendency = tendency;
                    if (targetDimension == 7 && copied) {
                        try {
                            HologramAuthorPortableCompiler.settle(document, policy.write());
                            throw new AssertionError("same-dimension ambiguous endpoint was accepted");
                        } catch (IOException expected) {
                            SimpleAssert.that(
                                expected.getMessage()
                                    .contains("Ambiguous"),
                                "same-dimension control rejects endpoint ambiguity, not an unrelated failure");
                        }
                    } else {
                        JsonObject settled = HologramAuthorPortableCompiler.settle(document, policy.write());
                        NBTTagCompound descriptor = relationAt(settled, 0);
                        SimpleAssert.eq(
                            targetDimension == 7,
                            descriptor.getBoolean("internal"),
                            "only same-dimension moved endpoints can become internal");
                        if (targetDimension == 8) {
                            NBTTagCompound payload = HologramBlueprintNbt.decode(
                                settled.getAsJsonArray("cells")
                                    .get(0)
                                    .getAsJsonObject()
                                    .get("tileNbt")
                                    .getAsString());
                            if ("self".equals(tendency)) {
                                SimpleAssert.eq(
                                    "absolute",
                                    descriptor.getString("mode"),
                                    "self preserves foreign endpoint as absolute despite unrelated local copies");
                                SimpleAssert
                                    .eq(8, descriptor.getInteger("targetDimension"), "foreign dimension remains exact");
                                SimpleAssert
                                    .eq(101, descriptor.getInteger("targetX"), "foreign target x remains exact");
                                SimpleAssert.eq(64, descriptor.getInteger("targetY"), "foreign target y remains exact");
                                SimpleAssert
                                    .eq(200, descriptor.getInteger("targetZ"), "foreign target z remains exact");
                                SimpleAssert.eq(
                                    target,
                                    payload.getCompoundTag("gtsr.hubPos"),
                                    "self retains exact foreign native binding");
                            } else {
                                SimpleAssert.eq(
                                    "clear",
                                    descriptor.getString("mode"),
                                    "share clears foreign binding instead of resolving unrelated local copies");
                                SimpleAssert
                                    .that(!payload.hasKey("gtsr.hubPos"), "share removes foreign native binding");
                            }
                        }
                    }
                    SimpleAssert.eq(
                        before,
                        document.sourceCopy(),
                        "successful and rejected dimension-aware settlement leave authoritative source unchanged");
                    SimpleAssert.eq(frame, document.sourceFrame(), "settlement leaves capture provenance unchanged");
                }
            }
        }
    }

    /** Isolates coordinate provenance from real mod orientation support; no claim of native NC pose support. */
    private static JsonObject rotateCoordinateFixture(JsonObject source, AuthorDocument.SourceFrame frame)
        throws IOException {
        HologramBlockTransforms.Adapter fixture = new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.id("IC2NCInfoPanelExtender");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) { /* orientation-neutral fixture */ }
        };
        HologramBlockTransforms.register(fixture);
        try {
            return HologramLocalAuthorSemantics.rotateBlueprint(source, frame);
        } finally {
            HologramBlockTransforms.unregister(fixture);
        }
    }

    private static NBTTagCompound relationAt(JsonObject source, int index) throws IOException {
        return HologramBlueprintNbt.decode(
            source.getAsJsonArray("cells")
                .get(index)
                .getAsJsonObject()
                .get("tileNbt")
                .getAsString())
            .getTagList(HologramCoordinatePolicies.RELATIONS, 10)
            .getCompoundTagAt(0);
    }

    private static NBTTagCompound relation(JsonObject settled) throws IOException {
        return HologramBlueprintNbt.decode(
            settled.getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject()
                .get("tileNbt")
                .getAsString())
            .getTagList(HologramCoordinatePolicies.RELATIONS, 10)
            .getCompoundTagAt(0);
    }

    private static JsonObject little(int x, boolean main) throws IOException {
        JsonObject cell = cell(x);
        NBTTagCompound root = new NBTTagCompound(), tile = new NBTTagCompound();
        root.setString("id", "LittleTilesTileEntity");
        root.setInteger("tilesCount", 1);
        tile.setInteger("bSize", 1);
        tile.setBoolean("isStructure", true);
        tile.setBoolean("main", main);
        tile.setString("id", "fixed");
        tile.setInteger("count", 1);
        for (String axis : new String[] { "X", "Y", "Z" }) {
            tile.setInteger("bBox0min" + axis, 0);
            tile.setInteger("bBox0max" + axis, 16);
        }
        String prefix = main ? "i0" : "";
        tile.setInteger(prefix + "coordX", main ? 1 : -1);
        tile.setInteger(prefix + "coordY", 0);
        tile.setInteger(prefix + "coordZ", 0);
        tile.setInteger(prefix + "posx", 0);
        tile.setInteger(prefix + "posy", 0);
        tile.setInteger(prefix + "posz", 0);
        root.setTag("t0", tile);
        cell.addProperty("tileNbt", HologramBlueprintNbt.encodeCaptured(root));
        return cell;
    }

    private static HologramBlueprintProcessing.Policy policy() {
        HologramBlueprintProcessing.Policy policy = new HologramBlueprintProcessing.Policy();
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        policy.autoHints = "none";
        return policy;
    }

    private static JsonObject settle(AuthorDocument document) throws IOException {
        return HologramAuthorPortableCompiler.settle(document, policy().write(), 0, 0, 0, 0);
    }

    private static AuthorDocument read(JsonObject source) throws IOException {
        return AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
    }

    private static JsonObject cell(int x) {
        JsonObject row = new JsonObject();
        row.addProperty("x", x);
        row.addProperty("y", 0);
        row.addProperty("z", 0);
        row.addProperty("block", "minecraft:stone");
        row.addProperty("item", "minecraft:stone");
        return row;
    }

    private static JsonObject source(JsonObject... cells) {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        source.addProperty("name", "candidate");
        JsonArray rows = new JsonArray();
        for (JsonObject cell : cells) rows.add(cell);
        source.add("cells", rows);
        source.add("structures", new JsonArray());
        return source;
    }

    private interface Checked {

        Object run() throws Exception;
    }

    private static void reject(Checked action, String message) throws Exception {
        boolean failed = false;
        try {
            action.run();
        } catch (IOException expected) {
            failed = true;
        }
        SimpleAssert.that(failed, message);
    }
}
