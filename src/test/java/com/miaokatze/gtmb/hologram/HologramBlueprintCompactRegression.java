// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real serialized byte comparisons, including hostile compact graphs; no world or rendering required. */
public final class HologramBlueprintCompactRegression {

    private static final Gson GSON = new Gson();

    private HologramBlueprintCompactRegression() {}

    public static void run() {
        try {
            roundtripAndMeasurements();
            packedGeometryParity();
            packedEditableViewAndConversion();
            localPackedMissAdmission();
            localAsciiIntegerLexing();
            expandedScalarCellsMatchGson();
            distinctions();
            malformed();
        } catch (Exception failure) {
            throw new AssertionError("compact blueprint regression", failure);
        }
    }

    private static void roundtripAndMeasurements() throws Exception {
        // 8192 repeated machine shells, 16 configurations, plus 256 coordinate-bearing unique tile payloads.
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = "compact-fixture";
        preset.name = "dictionary measurement";
        preset.schema = 2;
        String[] configs = new String[16];
        for (int i = 0; i < configs.length; i++) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("mode", i);
            tag.setString("filter", "gregtech:gt.metaitem.01");
            tag.setByte("typed", (byte) 7);
            configs[i] = HologramBlueprintNbt.encodeData(tag);
        }
        for (int i = 0; i < 8192; i++) {
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.item = "gregtech:gt.blockmachines";
            cell.block = "gregtech:gt.blockmachines";
            cell.damage = 11 + i % 16;
            cell.x = i % 128;
            cell.z = i / 128;
            cell.itemNbt = configs[i % 16];
            cell.tileNbt = configs[i % 16];
            if (i < 256) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setInteger("x", cell.x);
                tag.setInteger("z", cell.z);
                tag.setInteger("mode", i % 16);
                cell.tileNbt = HologramBlueprintNbt.encodeData(tag);
            }
            preset.cells.add(cell);
        }
        preset.processingNbt = configs[0];
        preset.quantumGroupingNbt = configs[0];
        HologramPresets.Cell palette = new HologramPresets.Cell();
        palette.item = "minecraft:stone";
        preset.palette.add(palette);
        String before = GSON.toJson(preset);
        SimpleAssert.eq(
            (long) before.getBytes(StandardCharsets.UTF_8).length,
            HologramBlueprintCompactCodec.expandedBytes(preset),
            "DTO memory accounting measures real expanded JSON bytes");
        JsonObject legacy = new JsonObject();
        legacy.addProperty("schema", 2);
        legacy.add("blueprint", GSON.toJsonTree(preset));
        byte[] raw = GSON.toJson(legacy)
            .getBytes(StandardCharsets.UTF_8);
        long start = System.nanoTime();
        byte[] compact = HologramBlueprintCompactCodec.encode(preset, (done, total) -> {});
        long encodeNanos = System.nanoTime() - start;
        start = System.nanoTime();
        JsonObject oldRead = HologramBlueprintCompactCodec.parse(raw);
        long legacyNanos = System.nanoTime() - start;
        start = System.nanoTime();
        JsonObject newRead = HologramBlueprintCompactCodec.parse(compact);
        long compactNanos = System.nanoTime() - start;
        SimpleAssert.eq(oldRead, newRead, "dictionary roundtrip preserves every blueprint JSON field");
        SimpleAssert.eq(before, GSON.toJson(preset), "encoding never mutates source or captured payloads");
        SimpleAssert.eq(
            HologramBlueprintContentHash.of(raw),
            HologramBlueprintContentHash.of(compact),
            "old and compact content identities agree");
        SimpleAssert.eq(
            HologramBlueprintContentHash.of(raw),
            HologramBlueprintContentHash.of(preset),
            "streaming DTO hash agrees");
        SimpleAssert
            .that(compact.length * 2L < raw.length, "representative repeated shells and NBT shrink by over half");
        byte[] collection = HologramBlueprintCompactCodec.encodeLibrary(Arrays.asList(preset));
        SimpleAssert.eq(
            legacy.get("blueprint"),
            HologramBlueprintCompactCodec.parse(collection, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES)
                .getAsJsonArray("presets")
                .get(0),
            "persistent catalog uses identical lossless dictionaries");
        JsonObject envelope = HologramBlueprintCompactCodec
            .parse(HologramBlueprintCompactCodec.encodeEnvelope(preset, "owner", "uploader"));
        SimpleAssert.eq(
            "owner",
            envelope.get("owner")
                .getAsString(),
            "public envelope retains authenticated owner");
        SimpleAssert.eq(
            1,
            envelope.get("schema")
                .getAsInt(),
            "public envelope retains its native schema");
        System.out.println(
            "GTMB_COMPACT_BYTES cells=8192 raw=" + raw.length
                + " compact="
                + compact.length
                + " reductionPct="
                + (100.0 * (raw.length - compact.length) / raw.length)
                + " encodeMs="
                + encodeNanos / 1000000.0
                + " legacyParseMs="
                + legacyNanos / 1000000.0
                + " compactParseMs="
                + compactNanos / 1000000.0);
    }

    private static void packedGeometryParity() throws Exception {
        NBTTagCompound typed = new NBTTagCompound();
        typed.setByte("mode", (byte) 7);
        String payload = HologramBlueprintNbt.encodeData(typed);
        String envelope = "{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"unknown\":{\"cells\":[7]},"
            + "\"blueprint\":{\"schema\":2,\"name\":\"蓝图 \\\"cells\\\"\",\"structures\":[{\"id\":\"group\",\"x\":1}]"
            + ",\"hintAreas\":[{\"x1\":1,\"prompt\":\"保留\"}],\"cells\":[";
        String footer = "],\"palette\":[[0,{}]]},\"materials\":[{\"item\":\"minecraft:stone\",\"itemNbt\":0,"
            + "\"external\":{\"keep\":true}},{\"item\":\"minecraft:dirt\"}],\"nbt\":[\""
            + payload
            + "\"]}";
        int count = 125_000;
        StringBuilder json = new StringBuilder(envelope);
        for (int i = 0; i < count; i++) {
            if (i != 0) json.append(',');
            json.append('[')
                .append(i & 1)
                .append(",{\"z\":")
                .append(i % 31 - 15)
                .append(",\"x\":")
                .append(i - 61000)
                .append(",\"y\":")
                .append(i % 10)
                .append("}]");
        }
        String direct = json.append(footer)
            .toString();
        // Escaped name is legal and forces the same original JsonReader geometry path, with identical coordinates.
        int escapeAt = direct.indexOf("\"x\":-61000");
        String compatibility = direct.substring(0, escapeAt + 1) + "\\u0078" + direct.substring(escapeAt + 2);
        byte[] bytes = direct.getBytes(StandardCharsets.UTF_8);
        byte[] fallback = compatibility.getBytes(StandardCharsets.UTF_8);
        var fast = HologramBlueprintCompactCodec.displayGeometry(bytes);
        var baseline = HologramBlueprintCompactCodec.displayGeometry(fallback);
        SimpleAssert.eq(count, fast.cells(), "giant packed row count");
        SimpleAssert.eq(fast.root, baseline.root, "same envelope, Unicode, fallback fields and typed descriptors");
        SimpleAssert.eq(
            payload,
            fast.material(0)
                .get("itemNbt")
                .getAsString(),
            "numeric dictionary retains NBT bytes");
        for (int i = 0; i < count; i++) {
            SimpleAssert.eq(baseline.materialIndex(i), fast.materialIndex(i), "packed material reference parity");
            for (int axis = 0; axis < 3; axis++)
                SimpleAssert.eq(baseline.coordinate(i, axis), fast.coordinate(i, axis), "packed coordinate parity");
        }
        var opened = HologramLocalBlueprintDocument.openForEditor(bytes, "", () -> true);
        SimpleAssert.that(
            opened.editor() != null && opened.editor()
                .rows()
                .cells() == count,
            "giant private document offers validated initial geometry without changing its editable source");
        SimpleAssert.that(
            Arrays.equals(
                bytes,
                opened.document()
                    .artifact()),
            "opening preserves exact portable bytes");
        java.util.concurrent.atomic.AtomicInteger steps = new java.util.concurrent.atomic.AtomicInteger();
        try {
            HologramLocalBlueprintDocument.openForEditor(
                bytes,
                "",
                () -> steps.incrementAndGet() < 4,
                editor -> { throw new AssertionError("cancelled decode published a view"); });
            throw new AssertionError("large packed geometry cancelled too late");
        } catch (java.io.InterruptedIOException expected) {
            SimpleAssert.eq(4, steps.get(), "cancellation during packing does not try the compatibility reader again");
        }

        char[] padding = new char[16500];
        Arrays.fill(padding, 'a');
        String prefix = envelope
            .replace("\"schema\":2,", "\"unrelated\":\"" + new String(padding) + "\",\"schema\":2,");
        String fresh = prefix + "[1,{\"x\":-0,\"y\":-2,\"z\":5}]" + footer;
        byte[] small = fresh.getBytes(StandardCharsets.UTF_8);
        var changed = HologramBlueprintCompactCodec.displayGeometry(small);
        SimpleAssert.eq(1, changed.cells(), "different same-process input does not retain giant row data");
        SimpleAssert.eq(1, changed.materialIndex(0), "different material comes from new source");
        SimpleAssert.eq(0, changed.coordinate(0, 0), "negative zero preserves legacy numeric value");
        SimpleAssert.eq(-2, changed.coordinate(0, 1), "signed coordinate from new source");
        SimpleAssert.eq(count, fast.cells(), "new input cannot mutate prior packed geometry");
        for (String rows : new String[] { "[0,{\"x\":0e0}]", "[0,{\"x\":2147483648}]", "[2,{}]", "[0,{\"q\":1}]" }) {
            try {
                HologramBlueprintCompactCodec
                    .displayGeometry((prefix + rows + footer).getBytes(StandardCharsets.UTF_8));
                throw new AssertionError("Expected rejection: " + rows);
            } catch (IOException expected) {
                // The packed preview platform and compatibility reader both enforce admissions.
            }
        }
        try {
            HologramLocalBlueprintDocument.openForEditor(
                (prefix + "[0,{}],[1,{\"x\":0,\"y\":0,\"z\":0}]" + footer).getBytes(StandardCharsets.UTF_8),
                "",
                () -> true);
            throw new AssertionError("Duplicate canonical packed positions became editable");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("重复"),
                "duplicate coordinates fail in the edit boundary");
        }
        if (Boolean.getBoolean("gtmb.profilePackedBlueprint")) profilePackedGeometry(bytes, fallback, count);
    }

    private static void packedEditableViewAndConversion() throws Exception {
        String id = "local_00000000-0000-0000-0000-000000000017";
        NBTTagCompound payload = new NBTTagCompound();
        payload.setByte("typedMode", (byte) 7);
        String nativePayload = HologramBlueprintNbt.encodeData(payload);
        JsonObject root = new JsonObject(), blueprint = new JsonObject(), material = new JsonObject(),
            opaqueTarget = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("encoding", "gtmb-dictionary-1");
        root.addProperty("opaqueEnvelope", "preserve-me");
        root.add("blueprint", blueprint);
        blueprint.addProperty("schema", 2);
        blueprint.addProperty("id", id);
        blueprint.addProperty("name", "packed editor ownership");
        blueprint.addProperty("createdAt", 1234L);
        blueprint.addProperty("opaqueHeader", "header-payload");
        JsonArray rows = new JsonArray(), materials = new JsonArray(), nbt = new JsonArray(), targets = new JsonArray();
        root.add("materials", materials);
        root.add("nbt", nbt);
        blueprint.add("cells", rows);
        materials.add(material);
        nbt.add(new JsonPrimitive(nativePayload));
        material.addProperty("block", "minecraft:stone");
        material.addProperty("item", "minecraft:stone");
        material.addProperty("tileNbt", 0);
        material.addProperty("itemNbt", 0);
        material.addProperty("opaqueRow", 769);
        opaqueTarget.addProperty("item", "minecraft:stone");
        opaqueTarget.addProperty("damage", 0);
        opaqueTarget.addProperty("opaqueChoice", true);
        targets.add(opaqueTarget);
        material.add("allowedTargets", targets);
        for (int x = -1; x <= 1; x++) {
            JsonArray row = new JsonArray();
            JsonObject position = new JsonObject();
            position.addProperty("x", x);
            position.addProperty("y", 3);
            position.addProperty("z", -5);
            row.add(new JsonPrimitive(0));
            row.add(position);
            rows.add(row);
        }
        byte[] exact = GSON.toJson(root)
            .getBytes(StandardCharsets.UTF_8);
        var eager = HologramLocalBlueprintDocument.openForEditor(exact, id, () -> true);
        var staged = HologramLocalBlueprintDocument.openForEditor(exact, id, () -> true, view -> {}, true);
        var legacy = eager.document();
        var lazy = staged.document();
        SimpleAssert.that(!legacy.packedAuthoring() && lazy.packedAuthoring(), "experiment defaults to legacy");
        var unmanaged = HologramLocalBlueprintDocument.openForEditor(exact, "", () -> true, view -> {}, true);
        SimpleAssert.that(
            !unmanaged.document()
                .packedAuthoring(),
            "named or public imports stay on the legacy writer");
        JsonObject expandedRoot = HologramBlueprintCompactCodec.parse(exact);
        NBTTagCompound packedSnapshot = stableSnapshot(legacy);
        for (JsonObject input : new JsonObject[] { expandedRoot, expandedRoot.getAsJsonObject("blueprint") }) {
            byte[] expanded = json(input);
            var ordinary = HologramLocalBlueprintDocument.openForEditor(
                expanded,
                id,
                () -> true,
                view -> { throw new AssertionError("expanded or bare legacy source staged a compact editor view"); },
                true);
            SimpleAssert.that(
                ordinary.editor() == null && !ordinary.document()
                    .packedAuthoring(),
                "expanded and bare legacy use the ordinary typed editor");
            SimpleAssert.eq(
                packedSnapshot,
                stableSnapshot(ordinary.document()),
                "materialized typed snapshot and ordinal order agree across all layouts");
            SimpleAssert.that(
                Arrays.equals(
                    expanded,
                    ordinary.document()
                        .artifact()),
                "ordinary typed opening retains exact expanded or bare bytes");
        }
        byte[] expanded = json(expandedRoot);
        var ordinaryCodec = new HologramLocalBlueprintCodec();
        var loaded = ordinaryCodec.read(expanded, () -> true);
        JsonObject reencoded = HologramBlueprintCompactCodec.parse(ordinaryCodec.write(loaded.document()));
        JsonObject reencodedBlueprint = reencoded.getAsJsonObject("blueprint");
        JsonObject reencodedCell = reencodedBlueprint.getAsJsonArray("cells")
            .get(0)
            .getAsJsonObject();
        SimpleAssert.eq(expandedRoot.get("opaqueEnvelope"), reencoded.get("opaqueEnvelope"), "opaque envelope");
        SimpleAssert.eq(blueprint.get("opaqueHeader"), reencodedBlueprint.get("opaqueHeader"), "opaque header");
        SimpleAssert.eq(material.get("opaqueRow"), reencodedCell.get("opaqueRow"), "opaque numeric row extension");
        SimpleAssert.eq(material.get("allowedTargets"), reencodedCell.get("allowedTargets"), "opaque target metadata");
        SimpleAssert.eq(
            nativePayload,
            reencodedCell.get("tileNbt")
                .getAsString(),
            "typed NBT content");
        SimpleAssert.that(
            staged.editor() != null && eager.editor() != null
                && staged.editor()
                    .rows()
                    .cells() == 3
                && Arrays.equals(
                    staged.editor()
                        .bounds(),
                    eager.editor()
                        .bounds())
                && Arrays.equals(
                    staged.editor()
                        .materials(),
                    eager.editor()
                        .materials()),
            "experimental initial view uses identical native material descriptors and order");
        var stored = HologramLocalBlueprintDocument.class.getDeclaredField("document");
        stored.setAccessible(true);
        SimpleAssert.that(
            ((HologramPresets.Preset) stored.get(lazy)).cells.isEmpty(),
            "initial experimental document retains no eager authoring cells");
        SimpleAssert.that(Arrays.equals(exact, lazy.artifact()), "unmodified packed artifact preserves exact source");
        NBTTagCompound select = new NBTTagCompound();
        select.setInteger("selected", 1);
        select.setIntArray("selectedIndices", new int[] { 2, 1, 2 });
        legacy.apply("selectCell", select);
        lazy.apply("selectCell", select);
        NBTTagCompound expected = legacy.snapshotMetadata(), actual = lazy.snapshotMetadata();
        for (String key : new String[] { "session", "generation" }) {
            expected.removeTag(key);
            actual.removeTag(key);
        }
        SimpleAssert
            .eq(expected, actual, "selection and selected typed NBT metadata agree without DTO materialization");
        SimpleAssert.that(
            actual.getTagList("nbtEntries", 10)
                .tagCount() > 0 && ((HologramPresets.Preset) stored.get(lazy)).cells.isEmpty(),
            "selecting NBT leaves the packed root intact");
        try {
            lazy.apply("editCellFlags", new NBTTagCompound());
            throw new AssertionError("a selected-cell mutation bypassed the materialization owner");
        } catch (IllegalStateException expectedGuard) {
            SimpleAssert
                .that(lazy.packedAuthoring() && !lazy.dirty(), "direct legacy writes cannot mutate packed state");
        }
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        try {
            lazy.preparePackedMaterialization(lazy.revision(), () -> checks.incrementAndGet() < 2);
            throw new AssertionError("cancelled conversion produced an installable result");
        } catch (IOException cancelled) {
            SimpleAssert
                .that(lazy.packedAuthoring() && lazy.revision() == 0, "cancelled worker leaves ownership unchanged");
        }
        var converted = lazy.preparePackedMaterialization(lazy.revision(), () -> true);
        try {
            lazy.acceptPackedMaterialization(converted, lazy.revision() + 1, () -> true);
            throw new AssertionError("wrong revision accepted converted authoring cells");
        } catch (IOException stale) {
            SimpleAssert.that(lazy.packedAuthoring(), "revision fence leaves the packed owner untouched");
        }
        try {
            lazy.acceptPackedMaterialization(converted, lazy.revision(), () -> false);
            throw new AssertionError("non-current lease committed");
        } catch (IOException stale) {
            SimpleAssert.that(lazy.packedAuthoring(), "owner fence leaves the old version installed");
        }
        lazy.acceptPackedMaterialization(converted, lazy.revision(), () -> true);
        SimpleAssert.that(
            !lazy.packedAuthoring() && !lazy.dirty() && lazy.revision() == legacy.revision(),
            "representation conversion is not an edit or history entry");
        try {
            lazy.acceptPackedMaterialization(converted, lazy.revision(), () -> true);
            throw new AssertionError("stale conversion could replay");
        } catch (IOException stale) {
            SimpleAssert.that(!lazy.packedAuthoring(), "converted root cannot be replaced by a stale token");
        }
        NBTTagCompound flags = new NBTTagCompound();
        flags.setInteger("selected", 1);
        flags.setString("prompt", "changed on the worker-owned legacy path");
        for (String op : new String[] { "editCellFlags", "editUndo", "editRedo" }) {
            legacy.apply(op, flags);
            lazy.apply(op, flags);
            SimpleAssert.eq(
                HologramBlueprintCompactCodec.parse(legacy.artifact()),
                HologramBlueprintCompactCodec.parse(lazy.artifact()),
                "converted operation preserves full source: " + op);
            SimpleAssert.that(
                legacy.dirty() == lazy.dirty() && legacy.revision() == lazy.revision(),
                "converted operation keeps dirty and revision semantics: " + op);
        }
        var author = new java.util.UUID(0L, 17L);
        byte[] legacySave = legacy.prepareSave(new NBTTagCompound(), "stage-one test", author, id);
        byte[] stagedSave = lazy.prepareSave(new NBTTagCompound(), "stage-one test", author, id);
        SimpleAssert.eq(
            HologramBlueprintCompactCodec.parse(legacySave),
            HologramBlueprintCompactCodec.parse(stagedSave),
            "save fallback retains native item/tile NBT and uninterpreted source extensions");
        JsonObject savedRoot = HologramBlueprintCompactCodec.parse(stagedSave);
        JsonObject savedBlueprint = savedRoot.getAsJsonObject("blueprint");
        JsonObject savedCell = savedBlueprint.getAsJsonArray("cells")
            .get(1)
            .getAsJsonObject();
        SimpleAssert.eq(
            "preserve-me",
            savedRoot.get("opaqueEnvelope")
                .getAsString(),
            "opaque envelope survives");
        SimpleAssert.eq(
            "header-payload",
            savedBlueprint.get("opaqueHeader")
                .getAsString(),
            "opaque header survives");
        SimpleAssert.eq(
            nativePayload,
            savedCell.get("tileNbt")
                .getAsString(),
            "native tile payload stays byte-exact");
        SimpleAssert.eq(
            nativePayload,
            savedCell.get("itemNbt")
                .getAsString(),
            "native item payload stays byte-exact");
        SimpleAssert.eq(
            769,
            savedCell.get("opaqueRow")
                .getAsInt(),
            "opaque cell extension survives");
        SimpleAssert.that(
            savedCell.getAsJsonArray("allowedTargets")
                .get(0)
                .getAsJsonObject()
                .get("opaqueChoice")
                .getAsBoolean(),
            "opaque nested candidate survives");
        packedHeaderHistory(exact, id, 3);
        var stillCurrent = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            HologramLocalBlueprintDocument
                .openForEditor(exact, id, stillCurrent::get, view -> stillCurrent.set(false), true);
            throw new AssertionError("stale owner admitted packed authoring");
        } catch (IOException cancelled) {
            SimpleAssert.that(!stillCurrent.get(), "packed owner is checked again after staging the render input");
        }
    }

    private static void packedHeaderHistory(byte[] input, String id, int count) throws Exception {
        var opened = HologramLocalBlueprintDocument.openForEditor(input, id, () -> true, view -> {}, true);
        var packed = opened.document();
        var eager = HologramLocalBlueprintDocument.open(input, id);
        var field = HologramLocalBlueprintDocument.class.getDeclaredField("document");
        field.setAccessible(true);
        var emptyRows = ((HologramPresets.Preset) field.get(packed)).cells;
        var stale = packed.preparePackedMaterialization(0, () -> true);
        SimpleAssert.that(packed.packedAuthoring() && emptyRows.isEmpty() && !eager.packedAuthoring(), "storage arms");
        NBTTagCompound edit = new NBTTagCompound();
        edit.setString("prompt", "gtmb first write");
        for (String op : new String[] { "editBlueprintPrompt", "editUndo", "editRedo" }) {
            edit.setLong("planRevision", packed.revision());
            if ("editUndo".equals(op)) try {
                packed.apply(op, edit, () -> false);
                throw new AssertionError("canceled packed Undo changed history");
            } catch (IOException canceled) {
                SimpleAssert.that(packed.dirty() && packed.revision() == 1, "canceled Undo changes no owner/revision");
            }
            packed.apply(op, edit);
            eager.apply(op, edit);
            SimpleAssert.that(
                packed.packedAuthoring() && ((HologramPresets.Preset) field.get(packed)).cells == emptyRows
                    && emptyRows.isEmpty(),
                "no DTO materialization through " + op);
            SimpleAssert.eq(eager.revision(), packed.revision(), "same revision through " + op);
            SimpleAssert.that(eager.dirty() == packed.dirty(), "same dirty/saved content through " + op);
            NBTTagCompound wanted = eager.snapshotMetadata(), got = packed.snapshotMetadata();
            wanted.removeTag("session");
            wanted.removeTag("generation");
            got.removeTag("session");
            got.removeTag("generation");
            SimpleAssert.eq(wanted, got, "same authored metadata and history through " + op);
            try {
                packed.artifact();
                throw new AssertionError("unencoded packed revision emitted stale or empty rows");
            } catch (IOException expected) {}
        }
        SimpleAssert.that(
            !packed.supportsPackedCommand("editCellFlags") && !packed.supportsPackedCommand("editHintArea")
                && !packed.supportsPackedCommand("configureBlueprintNbt"),
            "non-header mutations retain worker fallback");
        if (stale != null) try {
            packed.acceptPackedMaterialization(stale, 0, () -> true);
            throw new AssertionError("old packed conversion accepted against a newer header revision");
        } catch (IOException expected) {}
        // With both history stacks populated, all versions must rebind to the one accepted row list.
        packed.apply("editBlueprintPrompt", editWithoutRevision("second header"));
        eager.apply("editBlueprintPrompt", editWithoutRevision("second header"));
        packed.apply("editUndo", new NBTTagCompound());
        eager.apply("editUndo", new NBTTagCompound());
        var prepared = packed.preparePackedMaterialization(packed.revision(), () -> true);
        packed.acceptPackedMaterialization(prepared, packed.revision(), () -> true);
        SimpleAssert.that(
            !packed.packedAuthoring() && ((HologramPresets.Preset) field.get(packed)).cells.size() == count,
            "single Save-boundary conversion");
        NBTTagCompound options = new NBTTagCompound();
        options.setInteger(
            "deidentificationLevel",
            packed.snapshotMetadata()
                .getInteger("blueprintDeidentificationLevel"));
        java.util.UUID author = new java.util.UUID(0, 12);
        byte[] packedSave = packed.prepareSave(options, "test", author, id);
        byte[] eagerSave = eager.prepareSave(options, "test", author, id);
        SimpleAssert.that(Arrays.equals(eagerSave, packedSave), "byte-exact frozen Save against eager legacy writer");
        for (String op : new String[] { "editRedo", "editUndo", "editUndo", "editRedo" }) {
            packed.apply(op, new NBTTagCompound());
            eager.apply(op, new NBTTagCompound());
            SimpleAssert.that(Arrays.equals(eager.artifact(), packed.artifact()), "complete rows/sidecars after " + op);
        }
    }

    private static NBTTagCompound editWithoutRevision(String prompt) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("prompt", prompt);
        return request;
    }

    private static void profilePackedGeometry(byte[] fast, byte[] fallback, int count) throws Exception {
        java.lang.management.ThreadMXBean management = java.lang.management.ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocation = management instanceof com.sun.management.ThreadMXBean
            ? (com.sun.management.ThreadMXBean) management
            : null;
        if (allocation != null && allocation.isThreadAllocatedMemorySupported())
            allocation.setThreadAllocatedMemoryEnabled(true);
        else allocation = null;
        long id = Thread.currentThread()
            .getId();
        int samples = 9;
        long[][] nanos = { new long[samples], new long[samples] };
        long[][] allocated = { new long[samples], new long[samples] };
        for (int i = -4; i < samples; i++) for (int k = 0; k < 2; k++) {
            int lane = (i + k) & 1;
            long before = allocation == null ? 0 : allocation.getThreadAllocatedBytes(id);
            long start = System.nanoTime();
            var result = HologramBlueprintCompactCodec.displayGeometry(lane == 0 ? fast : fallback);
            long elapsed = System.nanoTime() - start;
            long bytes = allocation == null ? -1 : allocation.getThreadAllocatedBytes(id) - before;
            SimpleAssert.eq(count, result.cells(), "both timing lanes process all rows");
            if (i >= 0) {
                nanos[lane][i] = elapsed;
                allocated[lane][i] = bytes;
            }
        }
        for (long[] values : nanos) Arrays.sort(values);
        for (long[] values : allocated) Arrays.sort(values);
        System.out.println(
            "GTMB_PACKED_GEOMETRY cells=" + count
                + " sameJvmSamples="
                + samples
                + " fastMedianMs="
                + nanos[0][samples / 2] / 1000000.0
                + " compatibilityMedianMs="
                + nanos[1][samples / 2] / 1000000.0
                + " fastMedianAllocated="
                + allocated[0][samples / 2]
                + " compatibilityMedianAllocated="
                + allocated[1][samples / 2]);
    }

    private static NBTTagCompound stableSnapshot(HologramLocalBlueprintDocument document) throws IOException {
        NBTTagCompound snapshot = document.snapshot();
        snapshot.removeTag("session");
        snapshot.removeTag("generation");
        return snapshot;
    }

    private static void localPackedMissAdmission() throws Exception {
        String expanded = "{\"schema\":2,\"blueprint\":{\"cells\":[{\"x\":-3,\"opaque\":{\"escaped\":\"[\\\"]\","
            + "\"unicode\":\"\ud83d\ude00\",\"flags\":[true,null]}}]}}";
        byte[] bytes = expanded.getBytes(StandardCharsets.UTF_8);
        var input = HologramBlueprintCompactCodec.localDocument(expanded, () -> true);
        SimpleAssert.that(input.packed() == null, "expanded cells are not interpreted as compact references");
        SimpleAssert.eq(
            HologramBlueprintCompactCodec.parse(bytes),
            input.root(),
            "same complete general tree, including escaped punctuation and opaque typed values");
        AtomicInteger checks = new AtomicInteger();
        IOException cancelled = localRejected(bytes, () -> checks.incrementAndGet() < 1);
        SimpleAssert.that(
            cancelled instanceof InterruptedIOException && checks.get() == 1,
            "first packed-attempt checkpoint still cancels instead of falling back");

        String nested = "0";
        for (int i = 0; i < 31; i++) nested = "[" + nested + "]";
        String deepest = "{\"opaque\":" + nested + ",\"cells\":[{\"x\":2}]}";
        SimpleAssert.eq(
            HologramBlueprintCompactCodec.parse(deepest.getBytes(StandardCharsets.UTF_8)),
            HologramBlueprintCompactCodec.localDocument(deepest, () -> true)
                .root(),
            "accept depth 32");
        for (String invalid : new String[] { expanded + "{}", "{\"blueprint\":{\"cells\":[}}",
            "{\"opaque\":[" + nested + "],\"cells\":[]}",
            "{\"encoding\":\"unknown\",\"blueprint\":{\"cells\":[{\"x\":-3}]}}" }) {
            byte[] broken = invalid.getBytes(StandardCharsets.UTF_8);
            IOException expected;
            try {
                HologramBlueprintCompactCodec.parse(broken);
                throw new AssertionError("general parse unexpectedly admitted the invalid fixture");
            } catch (IOException failure) {
                expected = failure;
            }
            IOException observed = localRejected(broken, () -> true);
            SimpleAssert.eq(expected.getClass(), observed.getClass(), "general fallback exception type");
            SimpleAssert.eq(expected.getMessage(), observed.getMessage(), "general fallback exception order");
        }
        SimpleAssert.that(
            localRejected(
                new byte[] { '{', (byte) 0xff, '}' },
                () -> true) instanceof java.nio.charset.CharacterCodingException,
            "UTF-8 still fails before either structural reader");
        SimpleAssert.eq(
            "蓝图文件大小超出范围",
            localRejected(new byte[HologramBlueprintArtifact.MAX_JSON_BYTES + 1], () -> true).getMessage(),
            "local raw-byte limit precedes decode and packing");
    }

    private static void localAsciiIntegerLexing() throws Exception {
        byte[] valid = expandedIntegerFixture(
            "{\"x\":-60000000,\"y\":256,\"z\":60000000,\"metadata\":-2147483648,\"damage\":2147483647,"
                + "\"facing\":-1,\"defaultDamage\":0},{\"x\":-0,\"y\":-256},{}");
        var loaded = new HologramLocalBlueprintCodec().read(valid, () -> true);
        SimpleAssert.that(loaded.layout() == null, "fixture uses the actual expanded decoder");
        SimpleAssert.eq(1, loaded.document().schema, "absent schema retains its fallback");
        var cells = loaded.document().cells;
        SimpleAssert.eq(3, cells.size(), "valid integer forms retain all expanded rows");
        var bounds = cells.get(0);
        SimpleAssert.eq(-60000000, bounds.x, "minimum X boundary");
        SimpleAssert.eq(256, bounds.y, "maximum Y boundary");
        SimpleAssert.eq(60000000, bounds.z, "maximum Z boundary");
        SimpleAssert.eq(Integer.MIN_VALUE, bounds.metadata, "minimum integer (10 digits plus optional minus)");
        SimpleAssert.eq(Integer.MAX_VALUE, bounds.damage, "maximum integer (10 digits)");
        SimpleAssert.eq(-1, bounds.facing, "negative single digit");
        SimpleAssert.eq(0, bounds.defaultDamage, "positive zero");
        SimpleAssert.eq(0, cells.get(1).x, "negative zero");
        SimpleAssert.eq(-256, cells.get(1).y, "minimum Y boundary");
        var defaults = cells.get(2);
        SimpleAssert.that(defaults.x == 0 && defaults.y == 0 && defaults.z == 0, "absent coordinates default to zero");
        SimpleAssert.that(
            defaults.count == 1 && defaults.facing == -1 && defaults.damage == 0,
            "absent material integers retain DTO defaults");

        for (String token : new String[] { "1e0", "1E+2", "-0.0", "1.25", "+1", "\"12\"", "\"not numeric\"", "\"+1\"",
            "\"\u0661\"", "\"\uff11\"", "true", "null", "{}", "[]", "10000000000", "-10000000000" }) {
            IOException invalid = localRejected(expandedIntegerFixture("{\"damage\":" + token + "}"), () -> true);
            SimpleAssert.eq("蓝图整数无效", invalid.getMessage(), "wrong integer lexeme or shape: " + token);
            SimpleAssert.that(invalid.getCause() == null, "invalid lexeme does not reach integer overflow: " + token);
        }
        for (String token : new String[] { "2147483648", "-2147483649" }) {
            IOException invalid = localRejected(expandedIntegerFixture("{\"damage\":" + token + "}"), () -> true);
            SimpleAssert.eq("蓝图编辑数据格式无效", invalid.getMessage(), "integer overflow follows old wrapper: " + token);
            SimpleAssert.that(invalid.getCause() instanceof NumberFormatException, "overflow still uses parseInt");
        }
        for (String row : new String[] { "{\"x\":-60000001}", "{\"y\":257}", "{\"y\":-257}", "{\"z\":60000001}" }) {
            IOException invalid = localRejected(expandedIntegerFixture(row), () -> true);
            SimpleAssert.eq("蓝图坐标超出范围", invalid.getMessage(), "valid integer still needs coordinate bounds: " + row);
        }
    }

    private static byte[] expandedIntegerFixture(String rows) {
        return ("{\"blueprint\":{\"cells\":[" + rows + "]}}").getBytes(StandardCharsets.UTF_8);
    }

    private static void expandedScalarCellsMatchGson() throws Exception {
        // Freeze the old projected field set as an independent test oracle and force review on DTO shape changes.
        Set<String> known = new HashSet<>(
            Arrays.asList(
                "x",
                "y",
                "z",
                "block",
                "metadata",
                "item",
                "damage",
                "count",
                "tileNbt",
                "itemNbt",
                "facing",
                "prompt",
                "excluded",
                "controller",
                "structureId",
                "structureIds",
                "defaultItem",
                "defaultDamage",
                "allowedTargets",
                "structureRole",
                "structureFamily",
                "structureHint",
                "structureCandidatesComplete",
                "structureHatchFacings"));
        Set<String> dto = new HashSet<>();
        for (var field : HologramPresets.Cell.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(modifiers) && !java.lang.reflect.Modifier.isTransient(modifiers))
                dto.add(field.getName());
        }
        SimpleAssert.eq(known, dto, "review the optimized scalar mapping when the portable Cell DTO changes");

        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setByte("sameMode", (byte) 8);
        String tileNbt = HologramBlueprintNbt.encodeData(nbt);
        nbt.setInteger("sameMode", 8);
        String itemNbt = HologramBlueprintNbt.encodeData(nbt);
        JsonObject full = scalarFixture(
            "{\"x\":-12,\"y\":-3,\"z\":8,\"metadata\":31,\"damage\":32,"
                + "\"count\":2,\"facing\":5,\"defaultDamage\":34,\"block\":\"block\",\"item\":\"item\","
                + "\"defaultItem\":\"default\",\"prompt\":\"tip\",\"structureId\":\"main\","
                + "\"structureIds\":[],\"structureRole\":\"role\",\"structureFamily\":\"family\","
                + "\"structureHint\":\"hint\",\"excluded\":true,\"controller\":true,"
                + "\"structureCandidatesComplete\":true,\"structureHatchFacings\":null}");
        full.addProperty("tileNbt", tileNbt);
        full.addProperty("itemNbt", itemNbt);
        JsonObject future = scalarFixture("{\"desc\":\"opaque\",\"types\":[null,true,8,\"8\",{\"deep\":[]}]}");
        full.add("futureField", future);
        full.add("frozenBlockResolved", scalarFixture("{\"notADtoField\":true}"));
        full.add("nullableUnknown", JsonNull.INSTANCE);
        String pristineFuture = future.toString();
        JsonObject emptyLists = scalarFixture(
            "{\"x\":2,\"structureIds\":[],\"item\":null,"
                + "\"controller\":null,\"excluded\":false,\"structureCandidatesComplete\":null}");
        JsonObject defaults = scalarFixture("{\"x\":3,\"structureIds\":null,\"prompt\":null}");
        JsonObject coercions = scalarFixture(
            "{\"x\":4,\"block\":16,\"item\":true,\"prompt\":1e+2,"
                + "\"excluded\":\"TrUe\",\"controller\":\"yes\",\"structureCandidatesComplete\":\"FALSE\"}");
        JsonObject lists = scalarFixture(
            "{\"x\":5,\"structureIds\":[\"known\",5,false,null],"
                + "\"structureHatchFacings\":[1,\"2\",3.75],\"allowedTargets\":[{\"item\":\"legacy\","
                + "\"damage\":\"5\",\"futureTarget\":{\"nested\":[false,null]}}]}");
        JsonObject nullTargets = scalarFixture("{\"x\":6,\"allowedTargets\":null}");
        JsonObject source = scalarFixture("{\"schema\":2,\"blueprint\":{\"schema\":2,\"cells\":[],\"palette\":[]}}");
        JsonObject blueprint = source.getAsJsonObject("blueprint");
        JsonArray expanded = blueprint.getAsJsonArray("cells");
        for (JsonObject row : new JsonObject[] { full, emptyLists, defaults, coercions, lists, nullTargets })
            expanded.add(row);
        blueprint.getAsJsonArray("palette")
            .add(full);
        byte[] exact = json(source);
        byte[] untouched = exact.clone();
        byte[] compact = HologramBlueprintCompactCodec.recompress(exact, HologramBlueprintArtifact.MAX_JSON_BYTES);
        for (byte[] bytes : new byte[][] { exact, compact }) {
            var codec = new HologramLocalBlueprintCodec();
            var loaded = codec.read(bytes, () -> true);
            SimpleAssert.that((loaded.layout() != null) == (bytes == compact), "actual expanded/compact lane");
            codec.materialize(loaded, () -> true);
            var document = loaded.document();
            JsonObject reencoded = HologramBlueprintCompactCodec
                .documentBlueprint(HologramBlueprintCompactCodec.parse(codec.write(document)));
            JsonArray reencodedRows = reencoded.getAsJsonArray("cells");
            SimpleAssert.eq(expanded.size(), document.cells.size(), "all expanded ordinals are retained");
            for (int i = 0; i < expanded.size(); i++) {
                JsonObject original = expanded.get(i)
                    .getAsJsonObject();
                var legacy = gsonProjectedCell(original, known);
                assertCellMap(
                    original,
                    legacy,
                    document.cells.get(i),
                    reencodedRows.get(i)
                        .getAsJsonObject(),
                    known,
                    "cell ordinal " + i);
            }
            assertCellMap(
                full,
                gsonProjectedCell(full, known),
                document.palette.get(0),
                reencoded.getAsJsonArray("palette")
                    .get(0)
                    .getAsJsonObject(),
                known,
                "palette unchanged");
            SimpleAssert.eq(
                pristineFuture,
                reencodedRows.get(0)
                    .getAsJsonObject()
                    .get("futureField")
                    .toString(),
                "write retains the serialized structure and ordering of the opaque sentinel");
            SimpleAssert.eq(tileNbt, document.cells.get(0).tileNbt, "typed tile NBT exact bytes");
            SimpleAssert.eq(itemNbt, document.cells.get(0).itemNbt, "typed item NBT exact bytes");
            List<String> first = document.cells.get(0).structureIds;
            List<String> second = document.cells.get(1).structureIds;
            SimpleAssert.that(
                first.isEmpty() && second.isEmpty() && first != second,
                "empty structure ID lists stay independent");
            first.add("local edit");
            SimpleAssert.that(
                second.isEmpty() && document.cells.get(2).structureIds == null,
                "lists remain mutable and empty vs null distinct");
        }
        SimpleAssert.that(Arrays.equals(exact, untouched), "expanded input bytes remain untouched");
        SimpleAssert.eq(pristineFuture, future.toString(), "programmatic sentinel is not rewritten");

        for (String malformed : new String[] { "{\"item\":{}}", "{\"prompt\":[1]}", "{\"controller\":1}",
            "{\"structureCandidatesComplete\":[]}", "{\"structureIds\":{}}", "{\"structureHatchFacings\":[false]}",
            "{\"allowedTargets\":[4]}", "{\"allowedTargets\":[{\"damage\":true}]}" }) {
            JsonObject rejected = scalarFixture(malformed);
            RuntimeException legacyFailure;
            try {
                gsonProjectedCell(rejected, known);
                throw new AssertionError("fixture unexpectedly accepted by the old Gson mapping: " + malformed);
            } catch (RuntimeException old) {
                legacyFailure = old;
            }
            IOException actual = localRejected(expandedIntegerFixture(malformed), () -> true);
            SimpleAssert.eq("蓝图编辑数据格式无效", actual.getMessage(), "existing rejection wrapper");
            SimpleAssert.eq(
                legacyFailure.getClass(),
                actual.getCause()
                    .getClass(),
                "Gson rejection kind");
            SimpleAssert.eq(
                legacyFailure.getMessage(),
                actual.getCause()
                    .getMessage(),
                "Gson rejection details/order");
        }
        IOException invalidInteger = localRejected(
            expandedIntegerFixture("{\"prompt\":[],\"metadata\":\"1\"}"),
            () -> true);
        SimpleAssert.eq("蓝图整数无效", invalidInteger.getMessage(), "numeric admission still precedes Gson");
    }

    private static JsonObject scalarFixture(String text) {
        return new JsonParser().parse(text)
            .getAsJsonObject();
    }

    private static HologramPresets.Cell gsonProjectedCell(JsonObject row, Set<String> known) {
        JsonObject projected = new JsonObject();
        for (String field : known) if (row.has(field)) projected.add(field, row.get(field));
        return GSON.fromJson(projected, HologramPresets.Cell.class);
    }

    private static void assertCellMap(JsonObject source, HologramPresets.Cell legacy, HologramPresets.Cell actual,
        JsonObject stored, Set<String> known, String lane) {
        JsonObject expected = GSON.toJsonTree(legacy)
            .getAsJsonObject();
        SimpleAssert.eq(expected, GSON.toJsonTree(actual), lane + ": old Gson field values and coercions");
        for (Map.Entry<String, JsonElement> field : source.entrySet())
            if (!known.contains(field.getKey()) || "allowedTargets".equals(field.getKey()))
                expected.add(field.getKey(), field.getValue());
        SimpleAssert.eq(expected, stored, lane + ": actual reserialization retains unknown and raw target fields");
    }

    private static IOException localRejected(byte[] bytes, java.util.function.BooleanSupplier current)
        throws Exception {
        try {
            new HologramLocalBlueprintCodec().read(bytes, current);
        } catch (IOException expected) {
            return expected;
        }
        throw new AssertionError("local reader unexpectedly admitted the invalid fixture");
    }

    private static void distinctions() throws Exception {
        NBTTagCompound empty = new NBTTagCompound();
        NBTTagCompound bytes = new NBTTagCompound();
        bytes.setByte("v", (byte) 1);
        NBTTagCompound ints = new NBTTagCompound();
        ints.setInteger("v", 1);
        String[] values = { null, "", HologramBlueprintNbt.encodeData(empty), HologramBlueprintNbt.encodeData(bytes),
            HologramBlueprintNbt.encodeData(ints) };
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (String value : values) {
            JsonObject compact = minimal();
            JsonObject material = compact.getAsJsonArray("materials")
                .get(0)
                .getAsJsonObject();
            if (value == null) material.add("itemNbt", com.google.gson.JsonNull.INSTANCE);
            else {
                compact.getAsJsonArray("nbt")
                    .add(new com.google.gson.JsonPrimitive(value));
                material.addProperty("itemNbt", 0);
            }
            byte[] encoded = json(compact);
            JsonObject decoded = HologramBlueprintCompactCodec.parse(encoded);
            SimpleAssert.eq(
                HologramBlueprintContentHash.of(json(decoded)),
                HologramBlueprintContentHash.of(encoded),
                "null/empty/typed NBT exact roundtrip");
            hashes.add(HologramBlueprintContentHash.of(encoded));
        }
        SimpleAssert
            .eq(values.length, hashes.size(), "null, empty text, empty compound and typed numeric NBT remain distinct");
        JsonObject compact = minimal();
        JsonObject coordinate = compact.getAsJsonObject("blueprint")
            .getAsJsonArray("cells")
            .get(0)
            .getAsJsonArray()
            .get(1)
            .getAsJsonObject();
        coordinate.addProperty("x", Integer.MAX_VALUE);
        coordinate.addProperty("z", Integer.MIN_VALUE);
        JsonObject decoded = HologramBlueprintCompactCodec.parse(json(compact));
        SimpleAssert.eq(
            Integer.MAX_VALUE,
            decoded.getAsJsonObject("blueprint")
                .getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject()
                .get("x")
                .getAsInt(),
            "coordinates retain exact integer range");
        String noNbt = HologramBlueprintContentHash.of(json(minimal()));
        SimpleAssert.that(!hashes.contains(noNbt), "missing NBT differs from explicit null");
    }

    private static void malformed() throws Exception {
        for (String ref : new String[] { "-1", "1", "0.0", "\"0\"", "null", "{}", "[0]" }) {
            JsonObject root = minimal();
            JsonArray row = new JsonArray();
            row.add(new JsonParser().parse(ref));
            row.add(new JsonObject());
            JsonArray cells = new JsonArray();
            cells.add(row);
            root.getAsJsonObject("blueprint")
                .add("cells", cells);
            denied(root, "bad material reference " + ref);
        }
        JsonObject future = minimal();
        future.addProperty("encoding", "gtmb-dictionary-2");
        denied(future, "future encoding");
        JsonObject recursive = minimal();
        recursive.getAsJsonArray("nbt")
            .add(new JsonObject());
        denied(recursive, "recursive NBT dictionary");
        JsonObject wrongCoordinates = minimal();
        wrongCoordinates.getAsJsonObject("blueprint")
            .getAsJsonArray("cells")
            .get(0)
            .getAsJsonArray()
            .get(1)
            .getAsJsonObject()
            .addProperty("x", 0.5);
        denied(wrongCoordinates, "lossy coordinate");
        JsonObject many = minimal();
        for (int i = 0; i < HologramBlueprintCompactCodec.MAX_DICTIONARY; i++) many.getAsJsonArray("materials")
            .add(new JsonObject());
        denied(many, "unique material dictionary budget");
        JsonObject bomb = minimal();
        char[] chars = new char[1024 * 1024];
        Arrays.fill(chars, 'x');
        bomb.getAsJsonArray("nbt")
            .add(new com.google.gson.JsonPrimitive(new String(chars)));
        bomb.getAsJsonArray("materials")
            .get(0)
            .getAsJsonObject()
            .addProperty("itemNbt", 0);
        JsonArray rows = bomb.getAsJsonObject("blueprint")
            .getAsJsonArray("cells");
        for (int i = 0; i < 64; i++) rows.add(new JsonParser().parse("[0,{}]"));
        denied(bomb, "small repeated refs cannot expand beyond logical byte budget");
    }

    private static JsonObject minimal() {
        return new JsonParser().parse(
            "{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{\"schema\":2,\"cells\":[[0,{}]],\"palette\":[]},\"materials\":[{}],\"nbt\":[]}")
            .getAsJsonObject();
    }

    private static byte[] json(JsonObject value) {
        return new com.google.gson.GsonBuilder().serializeNulls()
            .create()
            .toJson(value)
            .getBytes(StandardCharsets.UTF_8);
    }

    private static void denied(JsonObject value, String message) throws Exception {
        byte[] bytes = json(value);
        String before = new String(bytes, StandardCharsets.UTF_8);
        try {
            HologramBlueprintCompactCodec.parse(bytes);
        } catch (IOException expected) {
            SimpleAssert.eq(before, new String(bytes, StandardCharsets.UTF_8), "rejection preserves original bytes");
            return;
        }
        throw new AssertionError("Expected compact admission rejection: " + message);
    }
}
