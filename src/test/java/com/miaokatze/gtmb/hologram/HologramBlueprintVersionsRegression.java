package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.testutil.BlueprintArtifactTestData;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Provenance and raw JSON gates without requiring GTNHCore in the isolated registry fixture. */
public final class HologramBlueprintVersionsRegression {

    private static final Gson GSON = new Gson();

    private HologramBlueprintVersionsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramBlueprintVersionsRegression.class, args)) return;
        run();
        System.out.println("Hologram blueprint version metadata regressions passed");
    }

    public static void run() {
        try {
            releaseAndPackAreIndependent();
            originalJsonIsRequired();
            transferAndAuthorSave();
            collectionRejectsOldFiles();
            preparedCommitPreservesReviewedIdentity();
            deletionKeepsCollectionAndArtifactTogether();
        } catch (Exception failure) {
            throw new AssertionError("blueprint version metadata regression", failure);
        }
    }

    private static void releaseAndPackAreIndependent() throws Exception {
        SimpleAssert.eq("0.1.40", Tags.RELEASE_VERSION, "blueprint release is the explicit semantic release property");
        HologramPresets.Preset client = blueprint();
        client.nativeBlueprint = HologramBlueprintVersions.current("2.9.0-RC-2");
        HologramBlueprintVersions.validate(client);
        SimpleAssert.eq("0.1.40", client.nativeBlueprint.gtmbVersion, "GTMB release does not become the pack version");
        SimpleAssert.eq("2.9.0-RC-2", client.nativeBlueprint.gtnhVersion, "exact RC-2 environment is preserved");
        HologramPresets.Preset server = blueprint();
        server.nativeBlueprint = HologramBlueprintVersions.current("2.9.0-RC-1");
        HologramBlueprintVersions.validate(server);
        SimpleAssert.eq("2.9.0-RC-1", server.nativeBlueprint.gtnhVersion, "server version is not assumed from client");
        for (String unavailable : Arrays.asList(null, "", "2.9.x", "GTNH", " 2.9.0-RC-2 ")) {
            HologramPresets.Preset fixture = blueprint();
            fixture.nativeBlueprint = HologramBlueprintVersions.current(unavailable);
            HologramBlueprintVersions.validate(fixture);
            SimpleAssert.eq("unknown", fixture.nativeBlueprint.gtnhVersion, "absent exact pack is explicitly unknown");
            SimpleAssert
                .eq("unavailable", fixture.nativeBlueprint.gtnhVersionSource, "unknown records unavailable source");
        }
        for (String release : Arrays.asList("0.1.0-rc.2", "1.2.3+build.17", "1.2.3-rc.1+build.17")) {
            client.nativeBlueprint.gtmbVersion = release;
            HologramBlueprintVersions.validate(client);
        }
        for (String invalid : Arrays.asList("v0.0.10-master+deadbeef-dirty", "01.0.0", "1.2", "1.2.3-01")) {
            client.nativeBlueprint.gtmbVersion = invalid;
            expectIo(() -> HologramBlueprintVersions.validate(client));
        }
    }

    private static void originalJsonIsRequired() throws Exception {
        HologramPresets.Preset internal = blueprint();
        HologramPresets.validate(internal);
        SimpleAssert.that(internal.nativeBlueprint == null, "internal templates do not manufacture file metadata");
        HologramPresets.Preset old = GSON.fromJson(GSON.toJson(internal), HologramPresets.Preset.class);
        SimpleAssert.that(old.nativeBlueprint == null, "Gson does not upgrade old missing metadata by initialization");
        expectIo(() -> HologramBlueprintVersions.validate(old));
        expectIo(() -> HologramBlueprintVersions.validateJson(GSON.toJsonTree(old)));

        HologramBlueprintVersions.stamp(internal);
        JsonObject valid = GSON.toJsonTree(internal)
            .getAsJsonObject();
        HologramBlueprintVersions.validateJson(valid);
        for (String key : Arrays.asList("formatVersion", "gtmbVersion", "gtnhVersion", "gtnhVersionSource")) {
            JsonObject absent = cloneJson(valid);
            absent.getAsJsonObject("nativeBlueprint")
                .remove(key);
            expectIo(() -> HologramBlueprintVersions.validateJson(absent));
            JsonObject nullValue = cloneJson(valid);
            nullValue.getAsJsonObject("nativeBlueprint")
                .add(key, com.google.gson.JsonNull.INSTANCE);
            expectIo(() -> HologramBlueprintVersions.validateJson(nullValue));
        }
        for (String invalidFormat : Arrays.asList("\"1\"", "1.0", "1e0", "0", "2", "true")) {
            JsonObject invalid = cloneJson(valid);
            invalid.getAsJsonObject("nativeBlueprint")
                .add("formatVersion", new JsonParser().parse(invalidFormat));
            expectIo(() -> HologramBlueprintVersions.validateJson(invalid));
        }
        for (String key : Arrays.asList("gtmbVersion", "gtnhVersion", "gtnhVersionSource")) {
            JsonObject invalid = cloneJson(valid);
            invalid.getAsJsonObject("nativeBlueprint")
                .addProperty(key, 1);
            expectIo(() -> HologramBlueprintVersions.validateJson(invalid));
        }
        JsonObject inconsistent = cloneJson(valid);
        inconsistent.getAsJsonObject("nativeBlueprint")
            .addProperty("gtnhVersion", "2.9.0-RC-2");
        inconsistent.getAsJsonObject("nativeBlueprint")
            .addProperty("gtnhVersionSource", "unavailable");
        expectIo(() -> HologramBlueprintVersions.validateJson(inconsistent));
        inconsistent.getAsJsonObject("nativeBlueprint")
            .addProperty("gtnhVersion", "2.9.x");
        inconsistent.getAsJsonObject("nativeBlueprint")
            .addProperty("gtnhVersionSource", HologramBlueprintVersions.PACK_SOURCE);
        expectIo(() -> HologramBlueprintVersions.validateJson(inconsistent));
    }

    private static void transferAndAuthorSave() throws Exception {
        HologramPresets.Preset source = blueprint();
        source.nativeBlueprint = HologramBlueprintVersions.current("2.7.2");
        source.nativeBlueprint.gtmbVersion = "0.0.10";
        HologramPresets.Preset received = HologramPresets.copy(source);
        HologramBlueprintVersions.stamp(received);
        SimpleAssert.eq("0.0.10", received.nativeBlueprint.gtmbVersion, "transfer preserves source GTMB version");
        SimpleAssert.eq("2.7.2", received.nativeBlueprint.gtnhVersion, "transfer preserves source pack version");
        received.nativeBlueprint.gtnhVersion = "2.8.0";
        SimpleAssert.eq("2.7.2", source.nativeBlueprint.gtnhVersion, "copy owns detached version metadata");
        HologramBlueprintVersions.Metadata current = HologramBlueprintVersions.current();
        HologramBlueprintVersions.stampSaved(received);
        SimpleAssert
            .eq(Tags.RELEASE_VERSION, received.nativeBlueprint.gtmbVersion, "author save records current release");
        SimpleAssert
            .eq(current.gtnhVersion, received.nativeBlueprint.gtnhVersion, "author save records current exact pack");
        SimpleAssert.eq(
            current.gtnhVersionSource,
            received.nativeBlueprint.gtnhVersionSource,
            "author save records detection source");
        SimpleAssert
            .eq("0.0.10", source.nativeBlueprint.gtmbVersion, "author save does not mutate original source metadata");
    }

    private static void collectionRejectsOldFiles() throws Exception {
        Method decode = HologramPresets.class.getDeclaredMethod("decode", String.class);
        decode.setAccessible(true);
        HologramPresets.Preset internal = blueprint();
        String old = "{\"schema\":2,\"presets\":[" + GSON.toJson(internal) + "]}";
        expectIo(() -> invokeDecode(decode, old));
        HologramBlueprintVersions.stamp(internal);
        String nativeFile = "{\"schema\":2,\"presets\":[" + GSON.toJson(internal) + "]}";
        invokeDecode(decode, nativeFile);
        expectIo(() -> invokeDecode(decode, nativeFile.replace("\"formatVersion\":1", "\"formatVersion\":\"1\"")));
    }

    private static void preparedCommitPreservesReviewedIdentity() throws Exception {
        Field cache = HologramPresets.class.getDeclaredField("cached");
        Field gameRoot = HologramLibraryFacade.class.getDeclaredField("clientGameDirectory");
        cache.setAccessible(true);
        gameRoot.setAccessible(true);
        Object previousCache = cache.get(null), previousRoot = gameRoot.get(null);
        Path game = Files.createTempDirectory("gtmb-version-prepared-");
        try {
            HologramLibraryFacade.configureClientGameDirectory(game);
            HologramPresets.clearCache();
            HologramPresets.Preset source = blueprint();
            HologramPresets.Preset prepared = HologramPresets.prepareSaved(source, false);
            java.util.UUID.fromString(prepared.id);
            SimpleAssert.eq("version_metadata_fixture", source.id, "prepare leaves caller identity unchanged");
            SimpleAssert.that(source.nativeBlueprint == null, "prepare leaves caller provenance unchanged");
            SimpleAssert.that(!Files.exists(HologramPresets.libraryPath()), "prepare does not write the library");
            SimpleAssert.eq(
                0,
                HologramPresets.all()
                    .size(),
                "prepare does not publish a cached entry");
            String reviewed = GSON.toJson(prepared);
            HologramPresets.commitSaved(prepared, false);
            SimpleAssert.eq(
                reviewed,
                GSON.toJson(
                    HologramPresets.all()
                        .get(0)),
                "commit preserves exact reviewed model");
            byte[] committed = Files.readAllBytes(HologramPresets.libraryPath());
            expectIo(() -> HologramPresets.commitSaved(prepared, false));
            SimpleAssert.that(
                Arrays.equals(committed, Files.readAllBytes(HologramPresets.libraryPath())),
                "duplicate commit leaves prior bytes intact");
            prepared.name = "Updated version fixture";
            HologramPresets.Preset update = HologramPresets.prepareSaved(prepared, true);
            update.nativeBlueprint = HologramBlueprintVersions.current("2.9.0-RC-2");
            String reviewedUpdate = GSON.toJson(update);
            HologramPresets.commitSaved(update, true);
            SimpleAssert.eq(
                reviewedUpdate,
                GSON.toJson(
                    HologramPresets.all()
                        .get(0)),
                "update commit preserves prepared pack and identity");
            committed = Files.readAllBytes(HologramPresets.libraryPath());
            update.nativeBlueprint.formatVersion = 2;
            expectIo(() -> HologramPresets.commitSaved(update, true));
            SimpleAssert.that(
                Arrays.equals(committed, Files.readAllBytes(HologramPresets.libraryPath())),
                "invalid staged metadata leaves bytes intact");
        } finally {
            cache.set(null, previousCache);
            gameRoot.set(null, previousRoot);
            Files.deleteIfExists(game.resolve("MiaoBlueprints/hologram-presets.json"));
            Files.deleteIfExists(game.resolve("MiaoBlueprints"));
            Files.deleteIfExists(game);
        }
    }

    private static void invokeDecode(Method decode, String json) throws Exception {
        try {
            decode.invoke(null, json);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof IOException) throw (IOException) failure.getCause();
            throw failure;
        }
    }

    private static void deletionKeepsCollectionAndArtifactTogether() throws Exception {
        Field cache = HologramPresets.class.getDeclaredField("cached");
        Field gameRoot = HologramLibraryFacade.class.getDeclaredField("clientGameDirectory");
        cache.setAccessible(true);
        gameRoot.setAccessible(true);
        Object previousCache = cache.get(null), previousRoot = gameRoot.get(null);
        Path game = Files.createTempDirectory("gtmb-version-delete-");
        Path json = null, png = null;
        try {
            HologramLibraryFacade.configureClientGameDirectory(game);
            HologramPresets.clearCache();
            HologramPresets.Preset prepared = HologramPresets.prepareSaved(blueprint(), false);
            HologramPresets.commitSaved(prepared, false);
            Path library = HologramPresets.libraryPath();
            json = library.getParent()
                .resolve("local_" + prepared.id + ".json");
            png = HologramBlueprintArtifact.previewPath(json);
            byte[] libraryBytes = Files.readAllBytes(library);
            String cachedBlueprint = GSON.toJson(
                HologramPresets.all()
                    .get(0));
            // Explicit deletion must also clean the stale logical row after the player removes both files.
            HologramPresets.delete(prepared.id);
            SimpleAssert.eq(
                0,
                HologramPresets.all()
                    .size(),
                "explicit delete removes an externally deleted pair's stale row");
            HologramPresets.clearCache();
            SimpleAssert.eq(
                0,
                HologramPresets.all()
                    .size(),
                "missing-pair cleanup survives catalog reload");
            HologramPresets.commitSaved(prepared, false);
            SimpleAssert.that(
                Arrays.equals(libraryBytes, Files.readAllBytes(library)),
                "recreating the reviewed fixture restores the exact collection before pair-failure checks");
            HologramBlueprintSharing.write(json, prepared, BlueprintArtifactTestData.previewPng());
            byte[] jsonBytes = Files.readAllBytes(json), pngBytes = Files.readAllBytes(png);

            byte[] corruptJson = "{corrupt-native-artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(json, corruptJson);
            expectIo(() -> HologramPresets.delete(prepared.id));
            SimpleAssert.that(
                Arrays.equals(libraryBytes, Files.readAllBytes(library))
                    && Arrays.equals(corruptJson, Files.readAllBytes(json))
                    && Arrays.equals(pngBytes, Files.readAllBytes(png)),
                "a present but corrupt complete pair cannot authorize deleting its logical source or user files");
            Files.write(json, jsonBytes);

            HologramPresets.Preset unrelated = HologramPresets.copy(prepared);
            unrelated.id = java.util.UUID.randomUUID()
                .toString();
            HologramBlueprintSharing.write(json, unrelated, BlueprintArtifactTestData.previewPng());
            byte[] unrelatedJson = Files.readAllBytes(json), unrelatedPng = Files.readAllBytes(png);
            expectIo(() -> HologramPresets.delete(prepared.id));
            SimpleAssert.that(
                Arrays.equals(libraryBytes, Files.readAllBytes(library))
                    && Arrays.equals(unrelatedJson, Files.readAllBytes(json))
                    && Arrays.equals(unrelatedPng, Files.readAllBytes(png)),
                "a complete pair with another blueprint ID preserves the catalog and both surviving user files");
            Files.write(json, jsonBytes);
            Files.write(png, pngBytes);
            int[] moves = { 0 };
            expectIo(
                () -> HologramPresets
                    .delete(prepared.id, pair -> HologramBlueprintArtifact.deletePair(pair, (from, to) -> {
                        if (++moves[0] == 2) throw new IOException("fixture second artifact member delete failure");
                        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    })));
            SimpleAssert.eq(2, moves[0], "delete failure occurs after JSON member has already moved");
            SimpleAssert.that(
                Arrays.equals(libraryBytes, Files.readAllBytes(library)),
                "failed artifact delete restores exact collection bytes");
            SimpleAssert.that(
                Arrays.equals(jsonBytes, Files.readAllBytes(json)),
                "failed artifact delete restores JSON source bytes");
            SimpleAssert.that(
                Arrays.equals(pngBytes, Files.readAllBytes(png)),
                "failed artifact delete restores matching PNG bytes");
            SimpleAssert.eq(
                cachedBlueprint,
                GSON.toJson(
                    HologramPresets.all()
                        .get(0)),
                "failed artifact delete restores detached native cache");
            try (java.util.stream.Stream<Path> files = Files.list(library.getParent())) {
                SimpleAssert.eq(
                    3,
                    (int) files.filter(
                        path -> !path.getFileName()
                            .toString()
                            .equals(".gtmb-blueprint-files.properties"))
                        .count(),
                    "failed three-member deletion leaves no temporary files alongside the filename index");
            }
            HologramPresets.delete(prepared.id);
            SimpleAssert.that(
                !Files.exists(json) && !Files.exists(png),
                "successful logical deletion removes both artifact members");
            SimpleAssert.eq(
                0,
                HologramPresets.all()
                    .size(),
                "successful artifact deletion publishes the empty collection");
            HologramPresets.clearCache();
            SimpleAssert.eq(
                0,
                HologramPresets.all()
                    .size(),
                "deleted logical entry stays absent after disk reload");
        } finally {
            cache.set(null, previousCache);
            gameRoot.set(null, previousRoot);
            if (json != null) Files.deleteIfExists(json);
            if (png != null) Files.deleteIfExists(png);
            Files.deleteIfExists(game.resolve("MiaoBlueprints/hologram-presets.json"));
            Files.deleteIfExists(game.resolve("MiaoBlueprints/.gtmb-blueprint-files.properties"));
            Files.deleteIfExists(game.resolve("MiaoBlueprints"));
            Files.deleteIfExists(game);
        }
    }

    private static JsonObject cloneJson(JsonElement json) {
        return new JsonParser().parse(json.toString())
            .getAsJsonObject();
    }

    private static HologramPresets.Preset blueprint() {
        HologramPresets.Preset result = new HologramPresets.Preset();
        result.schema = 2;
        result.id = "version_metadata_fixture";
        result.name = "Version metadata fixture";
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        cell.block = "minecraft:stone";
        result.cells.add(cell);
        return result;
    }

    private static void expectIo(Checked action) throws Exception {
        boolean rejected = false;
        try {
            action.run();
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "invalid or older file metadata is rejected");
    }

    private interface Checked {

        void run() throws Exception;
    }
}
