// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramBlueprintFiles;
import com.miaokatze.gtmb.hologram.HologramBlueprintPreviewBinding;

/** Untimed paired-disk verification; it never writes to the managed slot or the pristine control. */
final class HologramGiantWriteArtifact {

    private static final String FIXTURE = "gtmb.testlab.giantNativeFirstWritePristineJson";
    private static final String MANAGED_INDEX = ".gtmb-blueprint-files.properties";
    private final Path source, managedPng, pristine, libraryRoot;
    private final String managedId;
    private Path savedPath;
    private final String originalJson, originalPng;
    private final int cellCount;
    private String savedJson, savedPng;

    HologramGiantWriteArtifact(Path ownedGame, Path source, String originalJson, String originalPng, int cells,
        String managedId) throws IOException {
        String external = System.getProperty(FIXTURE, "");
        require(!external.isEmpty(), "PRECONDITION: missing separately staged pristine pair path");
        pristine = Paths.get(external)
            .toAbsolutePath()
            .normalize();
        this.source = source;
        this.managedId = managedId;
        libraryRoot = ownedGame.resolve("MiaoBlueprints");
        managedPng = HologramBlueprintArtifact.previewPath(source);
        this.originalJson = originalJson;
        this.originalPng = originalPng;
        cellCount = cells;
        require(
            !pristine.startsWith(ownedGame) && source.getParent()
                .equals(libraryRoot) && source.equals(HologramBlueprintFiles.resolve(libraryRoot, managedId)),
            "PRECONDITION: pristine and leased managed paths must have different owners");
        require(
            !Files.isSameFile(source, pristine) && !Files.isSameFile(
                HologramBlueprintArtifact.previewPath(source),
                HologramBlueprintArtifact.previewPath(pristine)),
            "PRECONDITION: managed pair aliases the pristine control (including hardlinks)");
        requireHashes(read(pristine), originalJson, originalPng, "PRECONDITION: pristine control hashes");
        requireHashes(read(source), originalJson, originalPng, "PRECONDITION: owned pair hashes");
    }

    // Untimed endpoints only. Hash members independently, so a missing/unbound half does not hide its peer.
    Map<String, Object> diskWitness(boolean committed) {
        var result = new LinkedHashMap<String, Object>();
        try {
            Path target = committed ? resolveIndexedPair(libraryRoot, managedId) : source;
            result.put("viaManagedIndexAfterAck", committed);
            result.put("physicalFilenameChanged", !target.equals(source));
            result.put("managedJson", memberWitness(target, HologramBlueprintArtifact.MAX_JSON_BYTES));
            result.put(
                "managedPng",
                memberWitness(
                    committed ? HologramBlueprintArtifact.previewPath(target) : managedPng,
                    HologramBlueprintArtifact.MAX_PNG_BYTES));
        } catch (IOException failed) {
            result.put("managedIndexError", failed.getMessage());
        }
        return result;
    }

    /** Exact logical mapping only: production resolve's unindexed legacy filename fallback is not commit evidence. */
    static Path resolveIndexedPair(Path root, String id) throws IOException {
        require(root.equals(root.toRealPath()), "PAIR_OWNER: noncanonical managed directory");
        require(
            id.matches("local_[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"),
            "PAIR_OWNER: invalid logical ID");
        Path index = root.resolve(MANAGED_INDEX);
        require(
            Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS) && index.equals(index.toRealPath())
                && Files.size(index) <= 1024L * 1024L,
            "PAIR_OWNER: missing or invalid managed index");
        Properties mappings = new Properties();
        try (var stream = Files.newInputStream(index, LinkOption.NOFOLLOW_LINKS)) {
            mappings.load(stream);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("PAIR_OWNER: invalid managed index", malformed);
        }
        String leaf = mappings.getProperty(id);
        require(
            leaf != null && leaf.endsWith(".json") && !leaf.contains("/") && !leaf.contains("\\"),
            "PAIR_OWNER: index has no direct member for this logical ID");
        Path target = HologramBlueprintFiles.resolve(root, id);
        require(
            root.equals(target.getParent()) && target.equals(root.resolve(leaf)),
            "PAIR_OWNER: managed index mapping changed during read");
        Path png = HologramBlueprintArtifact.previewPath(target);
        require(
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && target.equals(target.toRealPath())
                && Files.isRegularFile(png, LinkOption.NOFOLLOW_LINKS)
                && png.equals(png.toRealPath()),
            "PAIR_OWNER: indexed pair is missing or not a direct owned file");
        return target;
    }

    private static Map<String, Object> memberWitness(Path file, int limit) {
        var result = new LinkedHashMap<String, Object>();
        try {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !file.equals(file.toRealPath()))
                throw new IOException("missing or noncanonical member");
            long size = Files.size(file);
            result.put("bytes", size);
            if (size > limit) throw new IOException("member exceeds its byte budget");
            java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
            long read = 0;
            try (var input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[32768];
                for (int n; (n = input.read(buffer)) >= 0;) {
                    read += n;
                    if (read > limit) throw new IOException("member grew beyond its byte budget");
                    hash.update(buffer, 0, n);
                }
            }
            if (read != size) throw new IOException("size changed during observation");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash.digest()) hex.append(Character.forDigit((b & 255) >>> 4, 16))
                .append(Character.forDigit(b & 15, 16));
            result.put("sha256", hex.toString());
        } catch (IOException | java.security.NoSuchAlgorithmException failed) {
            result.put("error", failed.getMessage());
        }
        return result;
    }

    Path expectedPath() {
        return savedPath == null ? source : savedPath;
    }

    String expectedJson() {
        return savedJson == null ? originalJson : savedJson;
    }

    HologramBlueprintCompactCodec.DisplayRows pristineRows() throws IOException {
        var control = read(pristine);
        requireHashes(control, originalJson, originalPng, "CELL_PAIR: pristine control hashes");
        var rows = HologramBlueprintCompactCodec.displayGeometry(control.json);
        require(rows.compact() && rows.cells() == cellCount, "CELL_PAIR: expected original compact fixture rows");
        return rows;
    }

    void verifyAfterSave(String fileId, String prompt, long priorCapture, NBTTagCompound capture,
        Map<String, Object> result, HologramGiantCellDelta delta) throws IOException {
        var original = read(pristine);
        requireHashes(original, originalJson, originalPng, "PAIR_BINDING: pristine pair modified");
        require(managedId.equals(fileId), "PAIR_OWNER: Save did not retain the owned logical ID");
        Path target = resolveIndexedPair(libraryRoot, fileId);
        Path targetPng = HologramBlueprintArtifact.previewPath(target);
        var saved = read(target);
        require(
            !Files.isSameFile(target, pristine)
                && !Files.isSameFile(targetPng, HologramBlueprintArtifact.previewPath(pristine)),
            "PAIR_OWNER: saved pair aliases the pristine control");
        String boundPng = HologramBlueprintPreviewBinding.pngSha256(saved.json);
        byte[] reviewed = HologramBlueprintPreviewBinding.sourceJson(saved.json);
        String sourceHash = HologramBlueprintArtifact.hash(reviewed);
        require(
            !saved.jsonSha256.equals(originalJson) && saved.pngSha256.equals(boundPng),
            "PAIR_BINDING: managed Save Current did not produce a new mutually bound pair");
        require(
            capture.getLong("captureGeneration") > priorCapture && capture.getBoolean("nativeRendering")
                && capture.getBoolean("renderTickEnd")
                && capture.getBoolean("readbackSucceeded")
                && sourceHash.equals(capture.getString("sourceJsonSha256")),
            "PAIR_BINDING: no fresh RenderTick.END native save readback for reviewed source bytes");
        var before = HologramBlueprintCompactCodec.displayGeometry(original.json);
        var after = HologramBlueprintCompactCodec.displayGeometry(saved.json);
        require(
            before.compact() && after.compact() && before.cells() == cellCount && after.cells() == cellCount,
            "PAIR_PAYLOAD: exact giant dictionary/order format or rows changed");
        require(
            fileId.equals(value(after.blueprint, "id")) && prompt.equals(value(after.blueprint, "prompt")),
            "PAIR_PAYLOAD: expected managed UUID normalization and edited prompt");
        for (String field : new String[] { "authorName", "authorUuid", "createdAt", "nativeBlueprint",
            "deidentificationLevel", "deidentificationVersion", "deidentificationChosen", "retainNbt", "retainCovers",
            "retainedNbtKeys", "processingNbt" })
            require(
                java.util.Objects.equals(before.blueprint.get(field), after.blueprint.get(field)),
                "PAIR_PAYLOAD: retained header changed: " + field);

        if (delta != null) delta.verify(before, after);

        // The production reader resolves dictionary NBT references without creating a DTO for each stored row.
        // Compare every original material field once per mapping; extra recognized defaults may be emitted on save.
        int[] mapping = new int[before.materials()];
        java.util.Arrays.fill(mapping, -1);
        int tileRows = 0, itemRows = 0;
        for (int row = 0; row < cellCount; row++) {
            for (int axis = 0; axis < 3; axis++) if (before.coordinate(row, axis) != after.coordinate(row, axis))
                throw new IOException("PAIR_PAYLOAD: coordinate/ordinal changed at " + row);
            int from = before.materialIndex(row), to = after.materialIndex(row);
            if (delta == null || row != delta.target) {
                if (mapping[from] < 0) {
                    HologramGiantCellDelta.sameMaterial(before.material(from), after.material(to), row, false);
                    mapping[from] = to;
                }
                if (mapping[from] != to) throw new IOException("PAIR_PAYLOAD: material split at ordinal " + row);
            }
            JsonObject old = before.material(from);
            if (present(old.get("tileNbt"))) tileRows++;
            if (present(old.get("itemNbt"))) itemRows++;
        }
        require(tileRows == 1254 && itemRows == 1179, "PAIR_PAYLOAD: native payload coverage differs");
        savedPath = target;
        savedJson = saved.jsonSha256;
        savedPng = saved.pngSha256;
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("json", savedJson);
        hashes.put("png", savedPng);
        hashes.put("reviewedSource", sourceHash);
        result.put("sha256", hashes);
        result.put("captureGeneration", capture.getLong("captureGeneration"));
        result.put("orderedRows", cellCount);
        result.put("tileNbtRowsMatched", tileRows);
        result.put("itemNbtRowsMatched", itemRows);
        if (delta != null) {
            result.put("onlyEditedCell", delta.target);
            result.put("unchangedAdjacentCell", delta.neighbor);
        }
        result.put("managedFileId", fileId);
        result.put("viaManagedIndexAfterAck", true);
        result.put("physicalFilenameChanged", !target.equals(source));
        result.put("originalEmbeddedId", value(before.blueprint, "id"));
        result.put("pristineControlHashVerified", true);
    }

    void verifyFinal() throws IOException {
        require(savedJson != null, "PAIR_BINDING: Save Current never produced a verified pair");
        require(
            savedPath.equals(resolveIndexedPair(libraryRoot, managedId)),
            "PAIR_OWNER: logical mapping changed after verified Save Current");
        requireHashes(read(savedPath), savedJson, savedPng, "PAIR_BINDING: managed pair changed after Save Current");
        requireHashes(read(pristine), originalJson, originalPng, "PAIR_BINDING: pristine control changed");
    }

    private static HologramBlueprintArtifact.Artifact read(Path file) throws IOException {
        Path png = HologramBlueprintArtifact.previewPath(file);
        require(
            file.equals(file.toRealPath()) && png.equals(png.toRealPath())
                && Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(png, java.nio.file.LinkOption.NOFOLLOW_LINKS),
            "PAIR_BINDING: noncanonical local asset path");
        return HologramBlueprintArtifact.readPair(file);
    }

    private static void requireHashes(HologramBlueprintArtifact.Artifact pair, String json, String png, String label)
        throws IOException {
        require(json.equals(pair.jsonSha256) && png.equals(pair.pngSha256), label);
    }

    private static boolean present(JsonElement element) {
        return element != null && !element.isJsonNull();
    }

    private static String value(JsonObject object, String key) {
        JsonElement field = object.get(key);
        return present(field) ? field.getAsString() : "";
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
