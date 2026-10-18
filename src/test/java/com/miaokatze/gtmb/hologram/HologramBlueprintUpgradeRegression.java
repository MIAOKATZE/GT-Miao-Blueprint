package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.testutil.BlueprintArtifactTestData;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Raw legacy migration, copied portable pairs and the actual pair/index rollback boundary. */
public final class HologramBlueprintUpgradeRegression {

    private HologramBlueprintUpgradeRegression() {}

    public static void run() throws Exception {
        Path root = Files.createTempDirectory("gtmb-upgrade-");
        try {
            Path library = Files.createDirectories(root.resolve("library"));
            String first = "local_11111111-1111-1111-1111-111111111111";
            String second = "local_22222222-2222-2222-2222-222222222222";
            byte[] a = legacy(first), b = legacy(second), png = BlueprintArtifactTestData.previewPng();
            Path oldA = library.resolve(first + ".json"), oldB = library.resolve("old_shared.json");
            Files.write(oldA, a);
            Files.write(HologramBlueprintArtifact.previewPath(oldA), png); // Explicit unbound legacy sibling.
            Files.write(oldB, b);
            Path misplaced = library.resolve("misplaced-preview.png");
            Files.write(misplaced, HologramBlueprintArtifact.bindPreview(b, png));
            Path broken = library.resolve("missing-preview.json");
            byte[] brokenBytes = legacy("local_33333333-3333-3333-3333-333333333333");
            Files.write(broken, brokenBytes);
            JsonObject collection = new JsonObject();
            collection.addProperty("schema", 2);
            collection.addProperty("unknownCollectionField", "retain verbatim");
            JsonArray presets = new JsonArray();
            presets.add(
                HologramBlueprintCompactCodec.parse(a)
                    .get("blueprint"));
            collection.add("presets", presets);
            Path catalog = library.resolve("hologram-presets.json");
            byte[] originalCatalog = collection.toString()
                .getBytes(StandardCharsets.UTF_8);
            Files.write(catalog, originalCatalog);

            String result = HologramBlueprintFiles.upgrade(library);
            SimpleAssert.that(
                result.contains("升级 3") && result.contains("跳过 1") && result.contains("missing-preview.json"),
                "upgrade reports converted catalog/pairs and the retained missing preview");
            Path newA = HologramBlueprintFiles.resolve(library, first),
                newB = HologramBlueprintFiles.resolve(library, second);
            SimpleAssert.that(!newA.equals(newB), "same name and semantic hash cannot merge different logical IDs");
            SimpleAssert.that(
                !Files.exists(oldA) && !Files.exists(oldB) && !Files.exists(misplaced),
                "accepted upgrades retain only the current pair, including a uniquely matched misnamed preview");
            SimpleAssert.eq(
                newB,
                HologramBlueprintFiles.resolve(library, "old_shared"),
                "legacy share alias follows its renamed pair");
            SimpleAssert.that(
                newA.getFileName()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8).length <= 215 && !newA.getFileName()
                        .toString()
                        .contains(":"),
                "Chinese names have a bounded safe filename");
            for (Map.Entry<String, byte[]> entry : java.util.Collections.singletonMap(first, a)
                .entrySet()) {
                HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact
                    .readPair(HologramBlueprintFiles.resolve(library, entry.getKey()));
                SimpleAssert.eq(
                    HologramBlueprintCompactCodec.parse(entry.getValue()),
                    HologramBlueprintCompactCodec.parse(actual.json),
                    "compression preserves unknown JSON fields and exact NBT strings");
                SimpleAssert.eq(
                    HologramBlueprintContentHash.of(entry.getValue()),
                    HologramBlueprintContentHash.of(actual.json),
                    "compression and naming preserve semantic identity");
                SimpleAssert.that(
                    HologramBlueprintArtifact.previewMatches(actual.json, actual.png),
                    "preview digest is rebound to compact bytes");
                SimpleAssert.eq(
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png))
                        .getRGB(200, 300),
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(actual.png))
                        .getRGB(200, 300),
                    "preview pixels survive rebinding");
            }
            SimpleAssert.eq(
                collection,
                HologramBlueprintCompactCodec
                    .parse(Files.readAllBytes(catalog), HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES),
                "catalog migration preserves unknown root metadata");
            SimpleAssert.that(
                Arrays.equals(brokenBytes, Files.readAllBytes(broken)),
                "a skipped pair retains exact original JSON");
            String again = HologramBlueprintFiles.upgrade(library);
            SimpleAssert.that(
                again.contains("升级 0") && again.contains("已最新 3"),
                "repeating upgrade does not grow new generations");
            Path copy = Files.createDirectories(root.resolve("copy"));
            Path copied = copy.resolve(newA.getFileName());
            Files.copy(newA, copied);
            Files.copy(HologramBlueprintArtifact.previewPath(newA), HologramBlueprintArtifact.previewPath(copied));
            SimpleAssert.eq(
                copied,
                HologramBlueprintFiles.managed(copy)
                    .get(first),
                "a copied pair is readable without transferring its old index");
            indexRollback(root.resolve("fault"), first, a, png);

            Files.write(catalog, originalCatalog);
            byte[] compactCatalog = HologramBlueprintCompactCodec
                .recompress(originalCatalog, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES);
            byte[] newer = (new String(originalCatalog, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
            Files.write(catalog, newer);
            try {
                HologramPresets.replaceCompressedCatalog(catalog, originalCatalog, compactCatalog);
                throw new AssertionError("catalog upgrade must not replace a concurrent save");
            } catch (IOException expected) {
                SimpleAssert
                    .that(Arrays.equals(newer, Files.readAllBytes(catalog)), "changed catalog source remains intact");
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void indexRollback(Path root, String id, byte[] old, byte[] png) throws Exception {
        Files.createDirectories(root);
        Path original = root.resolve(id + ".json");
        HologramBlueprintArtifact.writePair(original, old, png);
        byte[] compact = HologramBlueprintCompactCodec.recompress(old, HologramBlueprintArtifact.MAX_JSON_BYTES);
        Path target = HologramBlueprintFiles.target(root, id, compact);
        HologramBlueprintArtifact.PairWrite pending = HologramBlueprintArtifact
            .writePairPending(target, compact, png, () -> true);
        try {
            HologramBlueprintFiles.track(root, id, pending, (directory, values) -> {
                HologramBlueprintFiles.writeIndex(directory, values);
                throw new IOException("injected index publish failure after atomic replace");
            });
            throw new AssertionError("injected filename-index failure must reach the save owner");
        } catch (IOException expected) {
            pending.rollback();
        }
        SimpleAssert
            .eq(original, HologramBlueprintFiles.resolve(root, id), "late index failure restores the old logical path");
        SimpleAssert.that(
            Arrays.equals(old, HologramBlueprintArtifact.readPair(original).json),
            "late index failure retains exact original pair");
        SimpleAssert.that(
            !Files.exists(target) && !Files.exists(HologramBlueprintArtifact.previewPath(target)),
            "late index failure leaves no half or extra target pair");
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            SimpleAssert.that(
                paths.noneMatch(
                    path -> path.toString()
                        .endsWith(".tmp")),
                "rollback clears bounded transaction recovery files");
        }
    }

    private static byte[] legacy(String id) {
        JsonObject root = new JsonObject(), blueprint = new JsonObject(), cell = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("futureEnvelopeField", "preserve");
        blueprint.addProperty("id", id);
        blueprint.addProperty("name", "中文:蓝图?" + String.join("", java.util.Collections.nCopies(40, "很长")));
        blueprint.addProperty("futureOption", "preserve");
        cell.addProperty("item", "minecraft:stone");
        cell.addProperty("tileNbt", "H4sIAAAAAAAA/2NgYAAAAAMAAWwIX+AAAAA=");
        cell.addProperty("futureCellField", "preserve");
        JsonArray cells = new JsonArray();
        cells.add(cell);
        cells.add(cell);
        blueprint.add("cells", cells);
        root.add("blueprint", blueprint);
        return root.toString()
            .getBytes(StandardCharsets.UTF_8);
    }
}
