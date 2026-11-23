// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.testutil.BlueprintArtifactTestData;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Compare the exact old normalized-wrapper hash and the current target name, without any client or renderer. */
public final class HologramBlueprintTargetRegression {

    private static final String SLOT = "local_00000000-0000-4000-8000-000000000035";
    private static final String OTHER = "local_00000000-0000-4000-8000-000000000036";

    private HologramBlueprintTargetRegression() {}

    public static void run() {
        try {
            representationParity();
            rejectionParity();
            syntheticPair();
        } catch (IOException failed) {
            throw new AssertionError("blueprint target normalization contract", failed);
        }
    }

    private static void representationParity() throws IOException {
        net.minecraft.nbt.NBTTagCompound nativeNbt = new net.minecraft.nbt.NBTTagCompound();
        nativeNbt.setString("opaque", "保存<&>\n");
        nativeNbt.setByte("facing", (byte) 5);
        JsonObject header = new JsonObject();
        header.addProperty("name", "Legacy Ω");
        header.addProperty("opaque", "escapes: <>&=' \" \\ \n \u2028 😀");
        JsonObject nbt = new JsonObject();
        nbt.addProperty("value", HologramBlueprintNbt.encodeCanonical(nativeNbt));
        String payload = nbt.get("value").toString();
        String material = "{\"block\":\"minecraft:stone\",\"tileNbt\":" + payload + ",\"itemNbt\":" + payload
            + ",\"other\":{\"name\":\"nested must count\",\"n\":1e+02}}";
        String expandedA = material.substring(0, material.length() - 1) + ",\"x\":-0,\"y\":1,\"z\":2}";
        String expandedB = material.substring(0, material.length() - 1) + ",\"x\":4,\"y\":1,\"z\":2}";
        String base = header.toString().substring(0, header.toString().length() - 1);
        String bare = base + ",\"cells\":[" + expandedA + "," + expandedB + "],\"palette\":[" + expandedB + "]}";
        String compactMember = base + ",\"cells\":[[0,{\"x\":-0,\"y\":1,\"z\":2}],[0,{\"x\":4,\"y\":1,\"z\":2}]],"
            + "\"palette\":[[0,{\"x\":4,\"y\":1,\"z\":2}]]}";
        String compactMaterial = "{\"block\":\"minecraft:stone\",\"tileNbt\":0,\"itemNbt\":0,"
            + "\"other\":{\"name\":\"nested must count\",\"n\":1e+02}}";
        String compact = "{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":" + compactMember
            + ",\"materials\":[" + compactMaterial + "],\"nbt\":[" + payload + "]}";
        String hash = parity(bare);
        SimpleAssert.eq(hash, parity("{\"blueprint\":" + bare + ",\"opaqueEnvelope\":17}"), "wrapper is not content");
        SimpleAssert.eq(hash, parity(compact), "exact expanded compact descriptor, numeric token and native NBT");
        SimpleAssert.that(!hash.equals(parity(base + ",\"cells\":[" + expandedB + "," + expandedA
            + "],\"palette\":[" + expandedB + "]}")), "ordered rows must not alias");
        SimpleAssert.that(!hash.equals(parity(bare.replace("nested must count", "changed"))),
            "descriptive keys are ignored only at header");
        SimpleAssert.that(!hash.equals(parity(bare.replace("1e+02", "100"))), "numeric lexemes still count");
        parity("{name:legacy,cells:[],opaque:'accepted by the first lenient parser'}");
        JsonObject unstable = selected(bytes("{\"name\":\"unicode fallback\",\"cells\":[],\"opaque\":{"
            + "\"?\":\"first\",\"\\uD800\":\"second\",\"\\uDC00\":\"third\"}}"));
        String legacy = oldExpandedHash(unstable);
        SimpleAssert.that(!legacy.equals(HologramBlueprintContentHash.ofBlueprint(unstable)),
            "malformed UTF-16 keys do collapse on the historical UTF-8 round trip");
        SimpleAssert.eq(legacy, HologramBlueprintContentHash.ofExpandedTarget(unstable),
            "unstable wrapper takes the old path instead of silently choosing another filename");
    }

    private static void rejectionParity() throws IOException {
        denied("{\"name\":\"bare\",\"cells\":{}}");
        denied("{\"name\":\"bare\",\"cells\":[],\"palette\":1}");
        denied("{\"blueprint\":{\"name\":\"wrapped\",\"cells\":1}}");
        denied("{\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{\"cells\":[[1,{}]]},\"materials\":[{}],\"nbt\":[]}");
        denied("{\"blueprint\":");
        String deep = "{\"name\":\"bare\",\"cells\":[],\"opaque\":" + repeat('[', 31) + "0" + repeat(']', 31) + "}";
        parity(deep.replace(repeat('[', 31), repeat('[', 30)).replace(repeat(']', 31), repeat(']', 30)));
        denied(deep); // bare parsed at depth 32; its synthetic wrapper increments depth to 33
        denied("{\"name\":\"palette\",\"cells\":[],\"palette\":[" + repeat("null,", 4096) + "null]}");
        String start = "{\"name\":\"byte limit\",\"cells\":[],\"opaque\":\"", end = "\"}";
        String canonicallyOver = start + repeat('x', HologramBlueprintArtifact.MAX_JSON_BYTES - start.length() - end.length())
            + end;
        denied(canonicallyOver); // the original bare document fits exactly, but its target wrapper does not
    }

    private static void syntheticPair() throws IOException {
        byte[] fixture = bytes("{\"schema\":2,\"blueprint\":{\"name\":\"Synthetic fixture\",\"cells\":[{\"x\":1}]}}");
        var pair = HologramBlueprintArtifact.prepareSave(fixture, BlueprintArtifactTestData.previewPng());
        String oldHash = oldExpandedHash(selected(pair.json));
        SimpleAssert.eq(oldHash, HologramBlueprintContentHash.ofExpandedTarget(selected(pair.json)),
            "exact same target digest");
        Path dir = Files.createTempDirectory("gtmb-test-target-naming").toRealPath();
        try {
            Path target = HologramBlueprintFiles.target(dir, SLOT, pair.json);
            SimpleAssert.eq("Synthetic fixture_" + oldHash + ".json", target.getFileName().toString(),
                "unchanged full stored filename");
            var written = HologramBlueprintArtifact.writePairPending(target, pair.json, pair.png, () -> true);
            HologramBlueprintFiles.track(dir, SLOT, written);
            written.commit();
            SimpleAssert.eq(target, HologramBlueprintFiles.resolve(dir, SLOT), "exact logical owner");
            SimpleAssert.eq(target, HologramBlueprintFiles.target(dir, SLOT, pair.json), "mapped overwrite stays in place");
            Path alias = HologramBlueprintFiles.target(dir, OTHER, pair.json);
            String base = target.getFileName().toString();
            String suffix = HologramBlueprintArtifact.hash(bytes(OTHER));
            SimpleAssert.eq(base.substring(0, base.length() - 5) + "_" + suffix + ".json",
                alias.getFileName().toString(), "same content does not alias another ID");
            var pending = HologramBlueprintArtifact.writePairPending(alias, pair.json, pair.png, () -> true);
            HologramBlueprintFiles.track(dir, OTHER, pending);
            SimpleAssert.eq(alias, HologramBlueprintFiles.resolve(dir, OTHER), "pending alias has its own index entry");
            pending.rollback();
            SimpleAssert.eq(dir.resolve(OTHER + ".json"), HologramBlueprintFiles.resolve(dir, OTHER), "rollback restores index");
            SimpleAssert.that(!Files.exists(alias) && !Files.exists(HologramBlueprintArtifact.previewPath(alias)),
                "rollback removes only its own staged pair");
            Files.createFile(alias);
            try {
                HologramBlueprintFiles.target(dir, OTHER, pair.json);
                throw new AssertionError("unindexed suffix collision was accepted");
            } catch (IOException deny) {
                SimpleAssert.eq("Blueprint filename already exists outside this logical entry", deny.getMessage(),
                    "exact collision denial preserved");
            }
            var after = HologramBlueprintArtifact.readPair(target);
            SimpleAssert.eq(pair.jsonSha256, after.jsonSha256, "stored bound JSON bytes unchanged");
            SimpleAssert.eq(pair.pngSha256, after.pngSha256, "stored PNG bytes unchanged");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    private static String parity(String raw) throws IOException {
        JsonObject selected = selected(bytes(raw));
        String expected = oldExpandedHash(selected);
        SimpleAssert.eq(expected, HologramBlueprintContentHash.ofExpandedTarget(selected), "historical target digest");
        return expected;
    }

    private static void denied(String raw) throws IOException {
        try {
            oldExpandedHash(selected(bytes(raw)));
            throw new AssertionError("historical guard unexpectedly accepted input");
        } catch (IOException expected) {
            try {
                HologramBlueprintContentHash.ofExpandedTarget(selected(bytes(raw)));
                throw new AssertionError("normalized target dropped a legacy guard");
            } catch (IOException actual) {
                SimpleAssert.eq(expected.getMessage(), actual.getMessage(), "exact structural denial");
            }
        }
    }

    static JsonObject selected(byte[] raw) throws IOException {
        JsonObject expanded = HologramBlueprintCompactCodec.parse(raw);
        return expanded.has("blueprint") ? expanded.getAsJsonObject("blueprint") : expanded;
    }

    static String oldExpandedHash(JsonObject expanded) throws IOException {
        JsonObject wrapper = new JsonObject();
        wrapper.add("blueprint", expanded);
        return HologramBlueprintContentHash.of(bytes(wrapper.toString()));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(char value, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static String repeat(String value, int length) {
        StringBuilder result = new StringBuilder(value.length() * length);
        for (int i = 0; i < length; i++) result.append(value);
        return result.toString();
    }
}
