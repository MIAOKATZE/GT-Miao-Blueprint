package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Tests the production storage transaction without initializing the GT machine registry. */
final class HologramPresetPersistenceRegression {

    private HologramPresetPersistenceRegression() {}

    static void run() {
        HologramBlueprintValidationCacheRegression.run();
        Path directory = null;
        Object previousCache = null;
        Field cache = null;
        AssertionError regressionFailure = null;
        try {
            cache = HologramPresets.class.getDeclaredField("cached");
            cache.setAccessible(true);
            previousCache = cache.get(null);
            Method put = HologramPresets.class
                .getDeclaredMethod("put", List.class, HologramPresets.Preset.class, boolean.class);
            Method write = HologramPresets.class.getDeclaredMethod("write", Path.class, List.class);
            put.setAccessible(true);
            write.setAccessible(true);
            directory = Files.createTempDirectory("gtmb-preset-storage-");
            Path file = directory.resolve("presets.json");
            List<HologramPresets.Preset> initial = new ArrayList<>();
            for (int i = 0; i < 32; i++) initial.add(preset("preset_" + i, "Original " + i));
            write.invoke(null, file, initial);
            byte[] originalBytes = Files.readAllBytes(file);
            String initialHash = HologramPresets.contentHash("preset_5");
            Object initialSource = contentSource("preset_5");
            Object initialBase = field(initialSource, "base");
            HologramPresets.labels();
            HologramPresets.get("preset_5");
            SimpleAssert
                .eq(initialHash, HologramPresets.contentHash("preset_5"), "same catalog reuses content identity");
            SimpleAssert.that(
                initialSource == contentSource("preset_5") && initialBase == field(initialSource, "base"),
                "labels and repeated gets preserve the private canonical source without reprocessing it");

            List<HologramPresets.Preset> updated = HologramPresets.all();
            HologramPresets.Preset edit = preset("preset_5", "Updated");
            HologramPresets.Cell target = new HologramPresets.Cell();
            target.item = "minecraft:stone";
            target.block = "minecraft:stone";
            target.x = 2;
            target.facing = 5;
            edit.cells.clear();
            edit.cells.add(target);
            put.invoke(null, updated, edit, true);
            SimpleAssert.eq(32, updated.size(), "updating a full library does not add a row");
            SimpleAssert.eq("preset_5", updated.get(5).id, "update preserves preset ID and row");
            SimpleAssert.eq("Original 5", HologramPresets.get("preset_5").name, "staging does not change cache");
            expectIo(put, updated, preset("missing", "Missing"), true);
            expectIo(put, updated, edit, false);

            // A nonempty directory cannot be atomically replaced by the JSON file.
            Path blocked = directory.resolve("blocked");
            Files.createDirectory(blocked);
            Files.write(blocked.resolve("keep"), new byte[] { 1 });
            expectIo(write, blocked, updated);
            SimpleAssert.that(
                initialSource == contentSource("preset_5"),
                "failed catalog writes do not retire the current admitted source");
            SimpleAssert.eq("Original 5", HologramPresets.get("preset_5").name, "failed IO leaves cache unchanged");
            SimpleAssert.that(
                java.util.Arrays.equals(originalBytes, Files.readAllBytes(file)),
                "failed IO leaves prior file unchanged");
            write.invoke(null, file, updated);
            SimpleAssert.that(
                !initialHash.equals(HologramPresets.contentHash("preset_5"))
                    && initialSource != contentSource("preset_5"),
                "committed content edits retire the old source and its hash");
            try {
                HologramPresets.admitted("preset_5", initialHash);
                throw new AssertionError("An old content reference cannot activate a newer saved generation");
            } catch (IOException expectedChangedSource) {
                SimpleAssert.that(
                    expectedChangedSource.getMessage()
                        .contains("changed"),
                    "a stale admitted reference asks for preparation of the current source");
            }
            SimpleAssert.eq("Updated", HologramPresets.get("preset_5").name, "successful update publishes cache");
            SimpleAssert
                .eq(5, HologramPresets.get("preset_5").cells.get(0).facing, "updated target facing survives storage");
            edit.name = "Caller mutation";
            HologramPresets.get("preset_5").name = "Reader mutation";
            SimpleAssert.eq("Updated", HologramPresets.get("preset_5").name, "cache owns detached snapshots");
            String policyHash = HologramPresets.contentHash("preset_5");
            Object policySource = contentSource("preset_5");
            long policyGeneration = (Long) field(HologramPresets.class, "contentGeneration");
            HologramPresets.clearContentCache();
            SimpleAssert.that(
                policySource != contentSource("preset_5")
                    && policyGeneration < (Long) field(HologramPresets.class, "contentGeneration")
                    && policyHash.equals(HologramPresets.contentHash("preset_5")),
                "runtime policy reset retires derived identities while retaining unchanged catalog semantics");

            List<HologramPresets.Preset> copies = HologramPresets.all();
            copies.remove(31);
            HologramPresets.Preset saved = (HologramPresets.Preset) put.invoke(null, copies, edit, false);
            UUID.fromString(saved.id);
            SimpleAssert.that(!saved.id.equals(edit.id), "save copy gets a fresh UUID");
            SimpleAssert.eq("preset_5", edit.id, "staging a copy does not alter the original identity");
            write.invoke(null, file, copies);
            SimpleAssert.eq(
                32,
                HologramPresets.all()
                    .size(),
                "save copy adds exactly one row");
            SimpleAssert.eq("Updated", HologramPresets.get("preset_5").name, "save copy preserves original contents");
            Files.delete(blocked.resolve("keep"));
            Files.delete(blocked);
            Files.delete(file);
        } catch (Exception failure) {
            regressionFailure = new AssertionError("preset persistence transaction regression", failure);
            throw regressionFailure;
        } catch (AssertionError failure) {
            regressionFailure = failure;
            throw failure;
        } finally {
            try {
                if (cache != null) cache.set(null, previousCache);
                if (directory != null) {
                    // These exact paths belong to this fixture, including failures before normal cleanup.
                    Files.deleteIfExists(
                        directory.resolve("blocked")
                            .resolve("keep"));
                    Files.deleteIfExists(directory.resolve("blocked"));
                    Files.deleteIfExists(directory.resolve("presets.json"));
                    Files.deleteIfExists(directory);
                }
            } catch (Exception failure) {
                if (regressionFailure != null) regressionFailure.addSuppressed(failure);
                else throw new AssertionError("preset fixture cleanup", failure);
            }
        }
        directoryLayout();
    }

    private static void directoryLayout() {
        Path game = null;
        Field root = null, cache = null;
        Object previousRoot = null, previousCache = null;
        try {
            root = HologramLibraryFacade.class.getDeclaredField("clientGameDirectory");
            cache = HologramPresets.class.getDeclaredField("cached");
            root.setAccessible(true);
            cache.setAccessible(true);
            previousRoot = root.get(null);
            previousCache = cache.get(null);
            game = Files.createTempDirectory("gtmb-blueprint-directory-");
            Path oldLibrary = game.resolve("config/gtmb/hologram-presets.json");
            Files.createDirectories(oldLibrary.getParent());
            HologramPresets.Preset oldPreset = preset("old-folder-only", "Old directory only");
            byte[] oldBytes = ("{\"schema\":2,\"presets\":[" + new com.google.gson.Gson().toJson(oldPreset) + "]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(oldLibrary, oldBytes);
            HologramLibraryFacade.configureClientGameDirectory(game);
            Path expected = game.resolve("MiaoBlueprints/hologram-presets.json");
            SimpleAssert.eq(expected, HologramPresets.libraryPath(), "integrated collection uses actual game root");
            SimpleAssert.eq(
                0,
                HologramPresets.all()
                    .size(),
                "old integrated collection is not read or migrated");
            SimpleAssert.that(!Files.exists(expected), "loading a new library does not copy old blueprint files");
            HologramPresets.Preset saved = preset("unsaved", "New directory fixture");
            HologramPresets.save(saved);
            SimpleAssert.that(Files.isRegularFile(expected), "integrated save writes the new exact collection path");
            HologramPresets.clearCache();
            SimpleAssert.eq(
                1,
                HologramPresets.all()
                    .size(),
                "new integrated collection survives disk reload");
            SimpleAssert.eq(
                saved.id,
                HologramPresets.all()
                    .get(0).id,
                "reload retains the saved identity");
            SimpleAssert.that(
                java.util.Arrays.equals(oldBytes, Files.readAllBytes(oldLibrary)),
                "new integrated saves leave old blueprint collection bytes untouched");
            SimpleAssert.eq(
                java.nio.file.Paths.get("MiaoBlueprints")
                    .toAbsolutePath()
                    .normalize(),
                HologramLibraryFacade.blueprintDirectory(game, true),
                "dedicated storage remains at the server run root rather than a configured client root");

            Path oldShare = game.resolve("config/gtmb/hologram-blueprints/shared_fixture.json");
            Files.createDirectories(oldShare.getParent());
            byte[] preview = com.miaokatze.gtmb.testutil.BlueprintArtifactTestData.previewPng();
            Path sourcePair = game.resolve("MiaoBlueprints/local_" + saved.id + ".json");
            HologramBlueprintSharing.write(sourcePair, saved, preview);
            HologramBlueprintSharing.write(oldShare, saved, preview);
            String stableHash = HologramPresets.contentHash(saved.id);
            Object stableSource = contentSource(saved.id);
            HologramPresets.refreshLocalArtifacts();
            HologramPresets.refreshLocalArtifacts();
            SimpleAssert.that(
                stableSource == contentSource(saved.id) && stableHash.equals(HologramPresets.contentHash(saved.id)),
                "unchanged explicit library refreshes reuse the catalog and admitted identity");
            byte[] originalCatalog = Files.readAllBytes(expected);
            com.google.gson.JsonObject legacyCatalog = HologramBlueprintCompactCodec
                .parse(originalCatalog, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES);
            com.google.gson.JsonObject legacyEntry = legacyCatalog.getAsJsonArray("presets")
                .get(0)
                .getAsJsonObject();
            legacyEntry.remove("retainCovers");
            Files.write(
                expected,
                legacyCatalog.toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HologramPresets.refreshLocalArtifacts();
            HologramPresets.labels();
            SimpleAssert.that(
                ((java.util.Map<?, ?>) field(HologramPresets.class, "contentSources")).isEmpty(),
                "ordinary labels create neither canonical nor raw semantic identities");
            Object legacySource = contentSource(saved.id);
            SimpleAssert.that(field(legacySource, "rawHash") == null, "labels never calculate raw content identity");
            String rawHash = HologramBlueprintContentHash.ofBlueprint(legacyEntry);
            SimpleAssert.that(
                !rawHash.equals(HologramPresets.contentHash(saved.id))
                    && HologramPresets.matchesContent(saved.id, rawHash),
                "omitted legacy defaults keep an exact raw identity tied to the admitted canonical base");
            SimpleAssert
                .eq(saved.id, HologramPresets.admitted(saved.id, rawHash).id, "raw identity activates its source");
            NBTTagCompound pairHint = new NBTTagCompound();
            pairHint.setString("localId", "local_" + saved.id);
            SimpleAssert.eq(
                saved.id,
                HologramServerLibrary.integratedContentId(pairHint),
                "a local pair selects only its corresponding integrated catalog source");
            SimpleAssert.that(
                HologramPresets.matching(HologramServerLibrary.integratedContentId(pairHint), rawHash) != null
                    && HologramPresets.matching(saved.id, "different-content") == null,
                "existing integrated content is reusable without trusting a client filename hint");
            Object memoizedRawHash = field(legacySource, "rawHash");
            HologramPresets.matchesContent(saved.id, rawHash);
            SimpleAssert.that(
                memoizedRawHash == field(legacySource, "rawHash"),
                "repeated admission reuses the same raw identity without retaining an expanded JSON tree");
            Files.write(expected, originalCatalog);
            HologramPresets.refreshLocalArtifacts();
            SimpleAssert.that(
                legacySource != contentSource(saved.id) && !HologramPresets.matchesContent(saved.id, rawHash),
                "external catalog replacement retires the prior raw identity mapping");
            byte[] oldShareBytes = Files.readAllBytes(oldShare);
            byte[] oldPreviewBytes = Files.readAllBytes(HologramBlueprintArtifact.previewPath(oldShare));
            Method importFile = HologramBlueprintSharing.class.getDeclaredMethod("importFile", String.class);
            Method resolve = HologramBlueprintSharing.class.getDeclaredMethod("resolve", String.class);
            importFile.setAccessible(true);
            resolve.setAccessible(true);
            expectIo(importFile, "shared_fixture");
            HologramPresets.exportBlueprint("shared_fixture", saved);
            SimpleAssert.that(
                HologramBlueprintSharing.resolve("shared_fixture")
                    .getParent()
                    .equals(game.resolve("MiaoBlueprints"))
                    && HologramBlueprintSharing.resolve("shared_fixture")
                        .getFileName()
                        .toString()
                        .startsWith("New directory fixture_"),
                "share aliases resolve the new descriptive filename in the same blueprint directory");
            SimpleAssert.eq(
                saved.id,
                HologramPresets.importBlueprint("shared_fixture").id,
                "new share survives an actual disk round trip");
            SimpleAssert.that(
                java.util.Arrays.equals(oldShareBytes, Files.readAllBytes(oldShare)),
                "share export leaves the old sharing directory untouched");
            SimpleAssert.that(
                java.util.Arrays
                    .equals(oldPreviewBytes, Files.readAllBytes(HologramBlueprintArtifact.previewPath(oldShare))),
                "share export leaves the old preview bytes untouched");
            SimpleAssert.that(
                java.util.Arrays.equals(
                    HologramBlueprintArtifact.readPair(sourcePair).png,
                    HologramBlueprintArtifact.readPair(HologramBlueprintSharing.resolve("shared_fixture")).png),
                "manual share carries the matching source preview without changing its pixels or source digest");
            expectIo(resolve, "hologram-presets");
            expectIo(resolve, "Hologram-Presets");
            expectIo(resolve, "local_11111111-1111-1111-1111-111111111111");
            expectIo(resolve, "public_11111111-1111-1111-1111-111111111111");

            byte[] survivingJson = Files.readAllBytes(sourcePair);
            Files.delete(HologramBlueprintArtifact.previewPath(sourcePair));
            HologramPresets.refreshLocalArtifacts();
            SimpleAssert.eq(
                0,
                HologramPresets.labels()
                    .tagCount(),
                "an explicit refresh hides externally deleted native pair members from the cached catalog");
            SimpleAssert.that(
                java.util.Arrays.equals(survivingJson, Files.readAllBytes(sourcePair)),
                "refresh does not delete or rewrite the surviving user artifact");
            com.google.gson.JsonObject wrongIdentity = new com.google.gson.JsonParser()
                .parse(new String(survivingJson, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
            wrongIdentity.getAsJsonObject("blueprint")
                .addProperty(
                    "id",
                    UUID.randomUUID()
                        .toString());
            byte[] unrelatedJson = wrongIdentity.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] unchangedIndex = Files.readAllBytes(expected);
            Files.write(sourcePair, unrelatedJson);
            try {
                HologramPresets.delete(saved.id);
                throw new AssertionError("a missing PNG must not authorize deleting another blueprint's JSON");
            } catch (IOException expectedMismatch) {
                SimpleAssert.that(
                    java.util.Arrays.equals(unrelatedJson, Files.readAllBytes(sourcePair))
                        && java.util.Arrays.equals(unchangedIndex, Files.readAllBytes(expected)),
                    "mismatched identity preserves both the surviving artifact and the logical index");
            }
            Files.write(sourcePair, survivingJson);
            HologramPresets.delete(saved.id);
            SimpleAssert.that(
                !Files.exists(sourcePair),
                "explicit deletion removes the surviving JSON after PNG was externally deleted");
            HologramPresets.clearCache();
            SimpleAssert.eq(
                0,
                HologramPresets.all()
                    .size(),
                "the missing pair's stale logical index can be removed even after a refresh hid it");

            HologramPresets.Preset removedJson = preset("unsaved", "Externally removed JSON");
            HologramPresets.save(removedJson);
            Path orphan = game.resolve("MiaoBlueprints/local_" + removedJson.id + ".json");
            HologramBlueprintSharing.write(orphan, removedJson, preview);
            Files.delete(orphan);
            HologramPresets.delete(removedJson.id);
            SimpleAssert.that(
                !Files.exists(HologramBlueprintArtifact.previewPath(orphan)),
                "explicit deletion also removes the owned preview when JSON was externally deleted");
        } catch (Exception failure) {
            throw new AssertionError("blueprint directory regression", failure);
        } finally {
            try {
                if (root != null) root.set(null, previousRoot);
                if (cache != null) cache.set(null, previousCache);
                if (game != null) {
                    try (java.util.stream.Stream<Path> paths = Files.walk(game)) {
                        for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                            Files.deleteIfExists(path);
                    }
                }
            } catch (Exception failure) {
                throw new AssertionError("blueprint directory fixture cleanup", failure);
            }
        }
    }

    private static HologramPresets.Preset preset(String id, String name) {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = id;
        preset.name = name;
        preset.controllerType = "storage-only-fixture";
        preset.schema = 2;
        HologramPresets.Cell stone = new HologramPresets.Cell();
        stone.item = "minecraft:stone";
        stone.block = "minecraft:stone";
        preset.cells.add(stone);
        preset.palette.add(stone);
        return preset;
    }

    private static Object contentSource(String id) throws Exception {
        HologramPresets.contentHash(id);
        return ((java.util.Map<?, ?>) field(HologramPresets.class, "contentSources")).get(id);
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class<?> ? null : target);
    }

    private static void expectIo(Method method, Object... arguments) throws Exception {
        try {
            method.invoke(null, arguments);
            throw new AssertionError("Expected an IOException from " + method.getName());
        } catch (InvocationTargetException failure) {
            if (!(failure.getCause() instanceof IOException)) throw failure;
        }
    }
}
