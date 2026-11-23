// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.testutil.BlueprintArtifactTestData;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Raw legacy migration, copied portable pairs and the actual pair/index rollback boundary. */
public final class HologramBlueprintUpgradeRegression {

    private HologramBlueprintUpgradeRegression() {}

    public static void run() throws Exception {
        SimpleAssert
            .eq(1, HologramBlueprintUpgrade.status("0.1.29"), "0.1.29 blueprints can upgrade to the current version");
        SimpleAssert.eq(1, HologramBlueprintUpgrade.status("0.1.28"), "0.1.28 blueprints can refresh metadata");
        SimpleAssert.eq(
            0,
            HologramBlueprintUpgrade.status(Tags.RELEASE_VERSION),
            "current-version blueprints disable repeat upgrades");
        Path root = Files.createTempDirectory("gtmb-upgrade-");
        try {
            storageOnly(root.resolve("storage-only"));
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
                    HologramBlueprintCompactCodec.parse(
                        HologramBlueprintUpgrade
                            .migrate(entry.getValue(), HologramBlueprintArtifact.MAX_JSON_BYTES, () -> true)),
                    HologramBlueprintCompactCodec.parse(actual.json),
                    "compression preserves unknown JSON fields and exact NBT strings");
                SimpleAssert.eq(
                    HologramBlueprintContentHash.of(
                        HologramBlueprintUpgrade
                            .migrate(entry.getValue(), HologramBlueprintArtifact.MAX_JSON_BYTES, () -> true)),
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
                HologramBlueprintCompactCodec.parse(
                    HologramBlueprintUpgrade
                        .migrate(originalCatalog, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES, () -> true),
                    HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES),
                HologramBlueprintCompactCodec
                    .parse(Files.readAllBytes(catalog), HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES),
                "catalog migration preserves unknown root metadata");
            byte[] catalogAfter = Files.readAllBytes(catalog);
            SimpleAssert.eq(collection, HologramBlueprintCompactCodec.parse(catalogAfter,
                HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES),
                "collection-only historic rows remain exact pre-pair content; no fabricated native header hides them");
            SimpleAssert.that(catalogAfter == HologramBlueprintUpgrade.migrate(catalogAfter,
                HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES, () -> true),
                "compact catalog with pre-pair members is byte-identical on repeat");
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

    private static byte[] bytes(JsonObject object) {
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void storageOnly(Path root) throws Exception {
        final int limit = HologramBlueprintArtifact.MAX_JSON_BYTES;
        String id = "local_55555555-5555-5555-5555-555555555555";
        JsonObject document = HologramBlueprintCompactCodec.parse(legacy(id));
        JsonObject blueprint = document.getAsJsonObject("blueprint"), meta = new JsonObject(), versions = new JsonObject();
        document.addProperty("owner", "owner-extension");
        document.addProperty("uploadedBy", "uploader-extension");
        meta.addProperty("gtmbVersion", Tags.RELEASE_VERSION);
        meta.addProperty("formatVersion", 1);
        meta.addProperty("gtnhVersion", "original-pack");
        meta.addProperty("gtnhVersionSource", "original-source");
        blueprint.add("nativeBlueprint", meta);
        versions.addProperty("gregtech", "original-other-mod");
        blueprint.add("modVersions", versions); // A missing gtmb entry must not be invented in a format-only pass.
        blueprint.addProperty("authorName", "Original author");
        blueprint.addProperty("createdAt", 123456L);
        blueprint.addProperty("processingNbt", "opaque Exact+/= NBt");
        JsonObject cell = blueprint.getAsJsonArray("cells").get(0).getAsJsonObject();
        cell.addProperty("itemNbt", "raw Item+/=");
        cell.addProperty("prompt", "real operations and controls retained");
        cell.add("unknownNested", new com.google.gson.JsonParser().parse("{\"tuple\":[true,null,42,\"abc\"]}"));
        byte[] expanded = bytes(document);
        SimpleAssert.that(!HologramBlueprintCompactCodec.isCompact(expanded, limit), "version-current expanded remains distinguishable");
        byte[] compact = HologramBlueprintUpgrade.migrate(expanded, limit, () -> true);
        SimpleAssert.that(HologramBlueprintCompactCodec.isCompact(compact, limit), "explicit version-current migration writes a dictionary");
        SimpleAssert.eq(document, HologramBlueprintCompactCodec.parse(compact),
            "no restamp or DTO round-trip, even with missing UUID, unknown extensions, metadata and exact NBT");
        SimpleAssert.eq(HologramBlueprintContentHash.of(expanded), HologramBlueprintContentHash.of(compact),
            "format-only migration preserves semantic identity");
        byte[] spaced = ("  " + new String(compact, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        SimpleAssert.that(spaced == HologramBlueprintUpgrade.migrate(spaced, limit, () -> true),
            "current compact returns original bytes, not a canonical reserialization");
        Path library = Files.createDirectories(root.resolve("pairs")), original = library.resolve("named-original.json");
        byte[] png = BlueprintArtifactTestData.previewPng();
        HologramBlueprintArtifact.Artifact before = HologramBlueprintArtifact.prepareSave(expanded, png);
        HologramBlueprintArtifact.writePair(original, before.json, before.png);
        String report = HologramBlueprintFiles.upgrade(library);
        SimpleAssert.that(report.contains("升级 1") && report.contains("跳过 0"), "same-version expanded pair upgraded");
        Path target = HologramBlueprintFiles.resolve(library, id);
        SimpleAssert.eq(target, HologramBlueprintFiles.resolve(library, "named-original"), "old named alias follows conversion");
        HologramBlueprintArtifact.Artifact after = HologramBlueprintArtifact.readPair(target);
        SimpleAssert.that(HologramBlueprintArtifact.previewMatches(after.json, after.png), "both preview binding directions agree");
        SimpleAssert.eq(document, HologramBlueprintCompactCodec.parse(HologramBlueprintPreviewBinding.sourceJson(after.json)),
            "exact same-version JSON semantics survive bound-pair disk commit");
        java.awt.image.BufferedImage oldImage = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(before.png));
        java.awt.image.BufferedImage newImage = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(after.png));
        SimpleAssert.that(Arrays.equals(oldImage.getRGB(0, 0, 640, 640, null, 0, 640),
            newImage.getRGB(0, 0, 640, 640, null, 0, 640)), "every cover pixel survives storage-only rebinding");
        JsonObject server = HologramBlueprintCompactCodec.parse(expanded);
        server.addProperty("schema", 1);
        byte[] stored = HologramBlueprintUpgrade.migrate(bytes(server), limit, () -> true);
        HologramBlueprintArtifact.Artifact storedPair = HologramBlueprintArtifact.prepareSave(stored, png);
        byte[] share = HologramBlueprintCompactCodec.publicShare(storedPair.json);
        HologramBlueprintArtifact.Artifact sharedPair = HologramBlueprintArtifact.transfer(share, storedPair);
        JsonObject shared = HologramBlueprintCompactCodec.parse(HologramBlueprintPreviewBinding.sourceJson(sharedPair.json));
        SimpleAssert.that(HologramBlueprintCompactCodec.isCompact(sharedPair.json, limit)
            && HologramBlueprintArtifact.previewMatches(sharedPair.json, sharedPair.png)
            && !shared.has("owner") && !shared.has("uploadedBy"), "public transfer retains valid compact dictionaries and both preview bindings");
        SimpleAssert.eq(blueprint, shared.getAsJsonObject("blueprint"),
            "public compact transfer preserves opaque NBT strings, unknown fields, and attribution");
        byte[] portable = HologramBlueprintCompactCodec.portableDocument(storedPair.json);
        SimpleAssert.that(HologramBlueprintCompactCodec.isCompact(portable, limit), "schema 1 to portable rewrap retains NBT indices");
        SimpleAssert.eq(blueprint, HologramBlueprintCompactCodec.parse(portable).getAsJsonObject("blueprint"),
            "placement portable view returns the original referenced NBT and exact content");
        Path fresh = Files.createDirectories(root.resolve("untracked")), exact = fresh.resolve("custom-name.json");
        HologramBlueprintArtifact.writePair(exact, after.json, after.png);
        report = HologramBlueprintFiles.upgrade(fresh);
        SimpleAssert.that(report.contains("已最新 1") && Arrays.equals(after.json, Files.readAllBytes(exact))
            && Arrays.equals(after.png, Files.readAllBytes(HologramBlueprintArtifact.previewPath(exact)))
            && HologramBlueprintFiles.indexedPath(fresh, id) == null && !Files.exists(fresh.resolve(".gtmb-blueprint-files.properties")),
            "already compact/current pair keeps arbitrary name, exact bytes and an absent index");

        Path rejected = Files.createDirectories(root.resolve("rejected"));
        JsonObject reserved = HologramBlueprintCompactCodec.parse(expanded);
        reserved.addProperty("materials", "unrelated historical extension");
        SimpleAssert.that(!HologramBlueprintCompactCodec.isCompact(bytes(reserved), limit)
            && !HologramBlueprintCompactCodec.parseStored(bytes(reserved), limit).compact(),
            "reserved unknown fields in expanded originals stay visible in read-only catalogs");
        Path invalid = rejected.resolve("reserved.json");
        HologramBlueprintArtifact.writePair(invalid, bytes(reserved), png);
        byte[] exactPng = Files.readAllBytes(HologramBlueprintArtifact.previewPath(invalid));
        report = HologramBlueprintFiles.upgrade(rejected);
        SimpleAssert.that(report.contains("跳过 1") && report.contains("保留字段") && Arrays.equals(bytes(reserved), Files.readAllBytes(invalid))
            && Arrays.equals(exactPng, Files.readAllBytes(HologramBlueprintArtifact.previewPath(invalid)))
            && !Files.exists(rejected.resolve(".gtmb-blueprint-files.properties")), "failed conversion preserves index absence and pair");
        JsonObject badRef = new com.google.gson.JsonParser().parse(new String(compact, StandardCharsets.UTF_8)).getAsJsonObject();
        badRef.getAsJsonObject("blueprint").add("cells", new com.google.gson.JsonParser().parse("[[65535,{}]]"));
        reject(() -> HologramBlueprintCompactCodec.isCompact(bytes(badRef), limit), "invalid compact references are not usable compact data");
        reject(() -> HologramBlueprintUpgrade.migrate(expanded, limit, () -> false), "cancelled storage-only pass is refused");
        Path cancelled = Files.createDirectories(root.resolve("cancelled")), source = cancelled.resolve("source.json");
        HologramBlueprintArtifact.writePair(source, before.json, before.png);
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        reject(() -> HologramBlueprintFiles.upgrade(cancelled, () -> callbacks.get() < 2,
            (done, total) -> callbacks.incrementAndGet()), "cancel after migration but before mutation");
        SimpleAssert.that(Arrays.equals(before.json, Files.readAllBytes(source)) && Arrays.equals(before.png,
            Files.readAllBytes(HologramBlueprintArtifact.previewPath(source))) && !Files.exists(cancelled.resolve(".gtmb-blueprint-files.properties")),
            "cancelled untracked source stays an exact untracked pair");

        Path raced = Files.createDirectories(root.resolve("raced-index")), mapped = raced.resolve("original.json");
        HologramBlueprintArtifact.writePair(mapped, before.json, before.png);
        java.util.Properties mappings = new java.util.Properties();
        mappings.setProperty(id, "original.json");
        mappings.setProperty("original", "original.json");
        HologramBlueprintFiles.writeIndex(raced, mappings);
        java.util.concurrent.atomic.AtomicInteger raceCallbacks = new java.util.concurrent.atomic.AtomicInteger();
        report = HologramBlueprintFiles.upgrade(raced, () -> true, (done, total) -> {
            if (raceCallbacks.incrementAndGet() != 2) return;
            try {
                // Model the index step of a deletion while the original pair is still present.
                HologramBlueprintFiles.writeIndex(raced, new java.util.Properties());
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
        SimpleAssert.that(report.contains("跳过 1") && report.contains("映射已变更")
            && HologramBlueprintFiles.indexedPath(raced, id) == null
            && HologramBlueprintFiles.indexedPath(raced, "original") == null
            && Arrays.equals(before.json, Files.readAllBytes(mapped))
            && Arrays.equals(before.png, Files.readAllBytes(HologramBlueprintArtifact.previewPath(mapped))),
            "source mapping and alias CAS do not resurrect an entry removed after selection; the old pair untouched");
        try (java.util.stream.Stream<Path> paths = Files.list(raced)) {
            SimpleAssert.eq(3L, paths.count(), "mapping CAS failure leaves no target or pending pair");
        }

        Path historic = Files.createDirectories(root.resolve("historic")), named = historic.resolve("named.json");
        JsonObject bare = HologramBlueprintCompactCodec.parse(legacy(id)).getAsJsonObject("blueprint");
        Files.write(named, bytes(bare));
        HologramBlueprintFiles.upgradeNamedHistoric(historic, "named", () -> true);
        byte[] converted = Files.readAllBytes(named);
        SimpleAssert.eq(bare, HologramBlueprintCompactCodec.parse(converted).getAsJsonObject("blueprint"),
            "explicit pre-pair bare share acquires only its storage envelope");
        SimpleAssert.that(HologramBlueprintCompactCodec.isCompact(converted, limit) && !Files.exists(HologramBlueprintArtifact.previewPath(named))
            && !Files.exists(historic.resolve(".gtmb-blueprint-files.properties")), "no new PNG, alias or metadata stamp");
        Files.write(named, before.json);
        reject(() -> HologramBlueprintFiles.upgradeNamedHistoric(historic, "named", () -> true),
            "missing bound PNG cannot turn a damaged pair into a JSON-only share");
        SimpleAssert.that(Arrays.equals(before.json, Files.readAllBytes(named)), "refused named original untouched");
        Files.write(named, bytes(bare));
        Path orphan = historic.resolve("different-name.png");
        Files.write(orphan, HologramBlueprintArtifact.bindPreview(bytes(bare), png));
        reject(() -> HologramBlueprintFiles.upgradeNamedHistoric(historic, "named", () -> true),
            "a differently named matching PNG prevents JSON-only conversion");
        SimpleAssert.that(Arrays.equals(bytes(bare), Files.readAllBytes(named)) && Files.exists(orphan), "no detached cover orphaned");
    }

    private interface Attempt {
        void run() throws IOException;
    }

    private static void reject(Attempt action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Refused before publishing bytes.
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
