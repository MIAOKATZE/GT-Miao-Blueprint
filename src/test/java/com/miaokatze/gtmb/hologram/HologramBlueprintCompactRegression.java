package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real serialized byte comparisons, including hostile compact graphs; no world or rendering required. */
public final class HologramBlueprintCompactRegression {

    private static final Gson GSON = new Gson();

    private HologramBlueprintCompactRegression() {}

    public static void run() {
        try {
            roundtripAndMeasurements();
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
