// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.mark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.Stage;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramNativeBlueprintStorage;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;
import com.miaokatze.gtmb.hologram.HologramBlueprintFiles;
import com.miaokatze.gtmb.hologram.HologramLibraryFacade;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.FrozenDraft;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.LocalEditorOrigin;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.LocalSaveIntent;

/** Client-owned files, independent of world saves and server identities. Call from the library worker only. */
final class HologramClientLibrary {

    static final int MAX_BYTES = HologramBlueprintArtifact.MAX_JSON_BYTES, MAX_ENTRIES = 128;
    private static final int MAX_LOCAL_BYTES = com.miaokatze.gtmb.hologram.HologramBlueprintLimits.LOCAL_FILE_BYTES;
    private final Path directory;
    private final Path metadataFile;
    private final PairIo pairIo;
    private boolean nativeRecoveryChecked;
    private final java.util.Map<String, CachedEntry> entryCache = new java.util.HashMap<>();
    private final java.util.Map<String, Path> displayPaths = new java.util.HashMap<>();

    interface PairIo {

        HologramBlueprintArtifact.PairWrite write(Path path, byte[] json, byte[] png,
            java.util.function.BooleanSupplier current) throws IOException;

        HologramBlueprintArtifact.Artifact read(Path path) throws IOException;
    }

    private static final PairIo FILE_PAIRS = new PairIo() {

        @Override
        public HologramBlueprintArtifact.PairWrite write(Path path, byte[] json, byte[] png,
            java.util.function.BooleanSupplier current) throws IOException {
            return HologramBlueprintArtifact.writeLocalPairPending(path, json, png, current);
        }

        @Override
        public HologramBlueprintArtifact.Artifact read(Path path) throws IOException {
            return HologramBlueprintArtifact.readLocalPair(path);
        }
    };

    HologramClientLibrary(Path gameDirectory) {
        this(gameDirectory, FILE_PAIRS);
    }

    HologramClientLibrary(Path gameDirectory, PairIo pairIo) {
        this.pairIo = pairIo;
        directory = gameDirectory.resolve("MiaoBlueprints")
            .toAbsolutePath()
            .normalize();
        metadataFile = gameDirectory.resolve("config/gtmb/client-blueprint-library.properties")
            .toAbsolutePath()
            .normalize();
    }

    private static final class CachedEntry {

        final Entry entry;
        final String stamp;

        CachedEntry(Entry entry, Path path) throws IOException {
            this.entry = entry;
            stamp = stamp(path);
        }
    }

    static final class LocalSave {

        final HologramBlueprintArtifact.PairWrite write;
        final String name, path, jsonHash, pngHash;
        final int jsonBytes, pngBytes;

        LocalSave(HologramBlueprintArtifact.PairWrite write, String name, Path path,
            HologramBlueprintArtifact.Artifact readback) {
            this.write = write;
            this.name = name;
            this.path = path.toString();
            jsonHash = write.jsonSha256;
            pngHash = write.pngSha256;
            jsonBytes = readback.json.length;
            pngBytes = readback.png.length;
        }
    }

    @com.github.bsideup.jabel.Desugar
    record EditorSave(LocalSave receipt, byte[] exactJson) {}

    @com.github.bsideup.jabel.Desugar
    record EditorSource(byte[] legacyBytes, LocalEditorOrigin origin, NativeBlueprintPairStore.Receipt nativePair) {
        EditorSource(byte[] legacyBytes, LocalEditorOrigin origin) { this(legacyBytes,origin,null); }
        byte[] json() throws IOException {
            if(nativePair != null) throw new IOException("原生蓝图不提供旧 JSON；请使用原生定义入口");
            return legacyBytes;
        }
        NativeBlueprintDefinition openNative() throws IOException {
            if(nativePair == null) throw new IOException("Not a native editor source");
            return NativeBlueprintPairStore.openExact(nativePair.source,nativePair.png,nativePair.physicalHash,nativePair.pngHash);
        }
    }

    static final class Entry {

        final String id, name, authorName, authorUuid, sourceMod, gtmbVersion;
        final int cells, structures;
        final long bytes, createdAt;
        final boolean fullyVoucherCovered;

        Entry(String id, JsonObject blueprint, long bytes) {
            this.id = id;
            name = string(blueprint, "name");
            fullyVoucherCovered = blueprint.has("fullyVoucherCovered") && blueprint.get("fullyVoucherCovered")
                .isJsonPrimitive()
                && blueprint.getAsJsonPrimitive("fullyVoucherCovered")
                    .isBoolean()
                && blueprint.get("fullyVoucherCovered")
                    .getAsBoolean();
            authorName = string(blueprint, "authorName");
            authorUuid = string(blueprint, "authorUuid");
            sourceMod = string(blueprint, "sourceMod");
            gtmbVersion = blueprint.has("nativeBlueprint") && blueprint.get("nativeBlueprint")
                .isJsonObject() ? string(blueprint.getAsJsonObject("nativeBlueprint"), "gtmbVersion") : "";
            createdAt = blueprint.has("createdAt") && blueprint.get("createdAt").isJsonPrimitive() ? blueprint.get("createdAt")
                .getAsLong() : 0;
            cells = blueprint.has("displayCells") ? blueprint.get("displayCells")
                .getAsInt() : size(blueprint, "cells");
            structures = blueprint.has("displayStructures") ? blueprint.get("displayStructures")
                .getAsInt() : size(blueprint, "structures");
            this.bytes = bytes;
        }

        Entry(String id, Entry source) {
            this.id = id;
            name = source.name;
            authorName = source.authorName;
            authorUuid = source.authorUuid;
            sourceMod = source.sourceMod;
            gtmbVersion = source.gtmbVersion;
            fullyVoucherCovered = source.fullyVoucherCovered;
            cells = source.cells;
            structures = source.structures;
            bytes = source.bytes;
            createdAt = source.createdAt;
        }
    }

    private synchronized void recoverNativeOnOpen() throws IOException {
        if(nativeRecoveryChecked)return;
        NativeBlueprintPairStore.recover(directory);
        nativeRecoveryChecked=true;
    }

    synchronized List<Entry> list() throws IOException {
        recoverNativeOnOpen();
        List<Entry> entries = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Files.createDirectories(directory);
        displayPaths.clear();
        {
            for (java.util.Map.Entry<String, Path> member : HologramBlueprintFiles.displayCandidates(directory)
                .entrySet()) {
                Path file = member.getValue();
                String key = file.toString();
                seen.add(key);
                try {
                    String signature = stamp(file);
                    CachedEntry cached = entryCache.get(key);
                    if (cached == null || !cached.stamp.equals(signature)) {
                        JsonObject display = file.getFileName().toString().endsWith(".gtnb")
                            ? NativeBlueprintPairStore.display(directory,member.getKey(),file) : displayMetadata(file);
                        String id = HologramBlueprintFiles.displayId(directory, file, string(display, "id"));
                        cached = new CachedEntry(new Entry(id, display, Files.size(file)), file);
                        if (!signature.equals(cached.stamp))
                            throw new IOException("Blueprint changed during catalog read");
                        entryCache.put(key, cached);
                    }
                    Entry entry = cached.entry;
                    if (displayPaths.containsKey(entry.id) && !file.equals(displayPaths.get(entry.id)))
                        entry = new Entry(HologramBlueprintFiles.displayId(directory, file, ""), entry);
                    if (displayPaths.putIfAbsent(entry.id, file) == null) entries.add(entry);
                } catch (IOException | RuntimeException invalid) {
                    entryCache.remove(key);
                    // A damaged individual file must not hide every other portable blueprint.
                }
                if (entries.size() >= MAX_ENTRIES) break;
            }
        }
        entryCache.keySet()
            .retainAll(seen);
        entries.sort((a, b) -> {
            int time = Long.compare(b.createdAt, a.createdAt);
            return time == 0 ? a.id.compareTo(b.id) : time;
        });
        return entries;
    }

    private static String stamp(Path path) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attributes = Files
            .readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        if (!attributes.isRegularFile() || attributes.size() > MAX_LOCAL_BYTES)
            throw new IOException("Invalid local blueprint member");
        return path.toAbsolutePath()
            .normalize() + ":"
            + attributes.fileKey()
            + ":"
            + attributes.size()
            + ":"
            + attributes.lastModifiedTime();
    }

    /** Stream catalog fields and skip payloads; no DTO expansion, PNG decode, digest or construction validation. */
    private static JsonObject displayMetadata(Path path) throws IOException {
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
            Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            JsonObject root = new JsonObject(), blueprint = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("blueprint")) {
                    blueprint = new JsonObject();
                    reader.beginObject();
                    while (reader.hasNext()) readDisplayField(reader, blueprint, reader.nextName());
                    reader.endObject();
                } else {
                    readDisplayField(reader, root, name);
                }
            }
            reader.endObject();
            JsonObject metadata = blueprint == null ? root : blueprint;
            if (!metadata.has("displayCells") || !metadata.has("name"))
                throw new IOException("Missing blueprint display data");
            return metadata;
        }
    }

    private static void readDisplayField(com.google.gson.stream.JsonReader reader, JsonObject metadata, String key)
        throws IOException {
        switch (key) {
            case "cells", "structures" -> {
                int count = 0;
                if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.skipValue();
                        count++;
                    }
                    reader.endArray();
                } else reader.skipValue();
                metadata.addProperty(key.equals("cells") ? "displayCells" : "displayStructures", count);
            }
            case "nativeBlueprint" -> metadata.add(key, new com.google.gson.JsonParser().parse(reader));
            case "fullyVoucherCovered" -> {
                if (reader.peek() == com.google.gson.stream.JsonToken.BOOLEAN)
                    metadata.addProperty(key, reader.nextBoolean());
                else reader.skipValue();
            }
            case "createdAt" -> {
                if (reader.peek() == com.google.gson.stream.JsonToken.NUMBER)
                    metadata.addProperty(key, reader.nextLong());
                else reader.skipValue();
            }
            case "id", "name", "authorName", "authorUuid", "sourceMod" -> {
                if (reader.peek() == com.google.gson.stream.JsonToken.STRING)
                    metadata.addProperty(key, reader.nextString());
                else reader.skipValue();
            }
            default -> reader.skipValue();
        }
    }

    /** Save validation and actual readback remain separate from display-only catalog hints. */
    LocalSave prepareLocalSave(byte[] data, byte[] preview, String id, java.util.function.BooleanSupplier stillCurrent)
        throws IOException {
        return prepareSavePair(data, preview, id, stillCurrent, blueprint(data), null, LocalSaveIntent.STRICT);
    }

    LocalSave preparePrivateDocumentSave(byte[] data, byte[] preview, String id,
        java.util.function.BooleanSupplier stillCurrent) throws IOException {
        return preparePrivateDocumentSave(data, preview, id, stillCurrent, null);
    }

    LocalSave preparePrivateDocumentSave(byte[] data, byte[] preview, String id,
        java.util.function.BooleanSupplier stillCurrent, HologramLocalSaveTrace trace) throws IOException {
        return preparePrivateDocumentSave(data, preview, id, stillCurrent, trace, LocalSaveIntent.STRICT);
    }

    /** Native counterpart of the frozen editor save. Stage2 hands ownership to the exact accepted revision. */
    HologramNativeBlueprintStorage.Prepared prepareEditorSave(NativeBlueprintDefinition frozen, byte[] png, String id,
        java.util.function.BooleanSupplier stillCurrent) throws IOException {
        return HologramNativeBlueprintStorage.prepareSave(directory,id,frozen,png,stillCurrent);
    }

    HologramNativeBlueprintStorage.Prepared prepareEditorSave(NativeBlueprintDefinition frozen, byte[] png, String id,
        java.util.function.BooleanSupplier stillCurrent, NativeBlueprintPairStore.Receipt expected,
        boolean requireVacant) throws IOException {
        return HologramNativeBlueprintStorage.prepareSave(directory,id,frozen,png,stillCurrent,expected,requireVacant);
    }

    EditorSave prepareEditorSave(FrozenDraft prepared, byte[] freshlyCapturedPng, String id,
        java.util.function.BooleanSupplier stillCurrent, HologramLocalSaveTrace trace) throws IOException {
        HologramBlueprintArtifact.Artifact pair = prepared.bindLocalSave(id, freshlyCapturedPng);
        mark(trace, Stage.BOUND_PAIR_READY);
        LocalSave saved = preparePrivateDocumentSave(
            pair.json,
            pair.png,
            id,
            stillCurrent,
            trace,
            prepared.incompleteAuthorIntent());
        return new EditorSave(saved, pair.json);
    }

    private LocalSave preparePrivateDocumentSave(byte[] data, byte[] preview, String id,
        java.util.function.BooleanSupplier stillCurrent, HologramLocalSaveTrace trace,
        LocalSaveIntent incompleteAuthorIntent) throws IOException {
        JsonObject blueprint = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.documentBlueprint(
            com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.localMetadata(data));
        JsonObject header = blueprint(blueprint);
        mark(trace, Stage.PAIR_INPUT_READY);
        return prepareSavePair(data, preview, id, stillCurrent, header, trace, incompleteAuthorIntent);
    }

    private synchronized LocalSave prepareSavePair(byte[] data, byte[] preview, String id,
        java.util.function.BooleanSupplier stillCurrent, JsonObject blueprint, HologramLocalSaveTrace trace,
        LocalSaveIntent incompleteAuthorIntent) throws IOException {
        mark(trace, Stage.TARGET_BEGIN);
        Path previous = file(id);
        if(previous.getFileName().toString().endsWith(".gtnb"))
            throw new IOException("该蓝图已切换为原生格式，不能从旧编辑器覆盖");
        if (incompleteAuthorIntent != LocalSaveIntent.STRICT) {
            Path indexed = HologramBlueprintFiles.indexedPath(directory, id);
            if (incompleteAuthorIntent == LocalSaveIntent.SAVE_CURRENT) {
                if (!previous.equals(indexed) || !Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("本地蓝图已不在原保存槽位");
            } else if (indexed != null || Files.exists(previous, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(HologramBlueprintArtifact.previewPath(previous), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("本地蓝图新保存槽位已存在");
                }
        }
        if (!Files.exists(previous) && list().size() >= MAX_ENTRIES) throw new IOException("客户端蓝图库已满（128 项）");
        Path target = HologramBlueprintFiles.targetLocal(directory, id, data);
        if (incompleteAuthorIntent == LocalSaveIntent.STRICT
            && (string(blueprint, "authorName").isEmpty() || string(blueprint, "authorUuid").isEmpty()))
            throw new IOException("蓝图缺少完整作者信息");
        mark(trace, Stage.TARGET_READY);
        mark(trace, Stage.STAGED_WRITE_BEGIN);
        HologramBlueprintArtifact.PairWrite written = pairIo.write(target, data, preview, stillCurrent);
        mark(trace, Stage.STAGED_WRITE_DONE);
        try {
            HologramBlueprintArtifact.Artifact actual = pairIo.read(target);
            if (!written.jsonSha256.equals(actual.jsonSha256) || !written.pngSha256.equals(actual.pngSha256))
                throw new IOException("Actual saved artifact differs from written bytes");
            if (!stillCurrent.getAsBoolean()) throw new IOException("Blueprint save source changed");
            mark(trace, Stage.READBACK_READY);
            HologramBlueprintFiles.track(directory, id, written);
            mark(trace, Stage.INDEX_READY);
            entryCache.put(target.toString(), new CachedEntry(new Entry(id, blueprint, actual.json.length), target));
            return new LocalSave(written, string(blueprint, "name"), target, actual);
        } catch (IOException | RuntimeException failure) {
            try {
                written.rollback();
            } catch (IOException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    String save(byte[] data, byte[] preview, String replaceId) throws IOException {
        return save(data, preview, replaceId, () -> true);
    }

    String save(byte[] data, byte[] preview, String replaceId, java.util.function.BooleanSupplier stillCurrent)
        throws IOException {
        String id = replaceId == null || replaceId.isEmpty() ? "local_" + UUID.randomUUID() : replaceId;
        LocalSave saved = prepareLocalSave(data, preview, id, stillCurrent);
        saved.write.commit();
        return id;
    }

    /** A JSON-only save can never acknowledge a completed native artifact. */
    String save(byte[] data, String replaceId, String playerName, String playerUuid) throws IOException {
        parse(data);
        throw new IOException("蓝图缺少真实结构预览 PNG");
    }

    HologramBlueprintArtifact.PairWrite savePending(byte[] data, byte[] preview, String id,
        java.util.function.BooleanSupplier stillCurrent) throws IOException {
        return prepareLocalSave(data, preview, id, stillCurrent).write;
    }

    /** Internal staged half of explicit upgrade; activation belongs to the accepting native editor revision. */
    com.miaokatze.gtmb.hologram.HologramNativeBlueprintStorage.Prepared prepareNativeUpgrade(String id,
        java.util.function.BooleanSupplier current) throws IOException {
        return com.miaokatze.gtmb.hologram.HologramNativeBlueprintStorage.prepare(directory, id, current);
    }

    String upgradeLegacy() throws IOException {
        return upgradeLegacy(() -> true, (done, total) -> {});
    }

    synchronized String upgradeLegacy(java.util.function.BooleanSupplier current,
        java.util.function.BiConsumer<Integer, Integer> progress) throws IOException {
        String result = HologramBlueprintFiles.upgrade(directory, current, progress);
        entryCache.clear();
        return result;
    }

    synchronized String upgradeNamedHistoric(String alias, java.util.function.BooleanSupplier current)
        throws IOException {
        String basename = alias.endsWith(".json") ? alias.substring(0, alias.length() - 5) : alias;
        // Existing named-upgrade action: a paired private blueprint can now be explicitly
        // converted, without silently changing what the normal editor opens. JSON-only historic
        // shares retain the existing compact-import semantics; public IDs stay out of this path.
        if(!basename.startsWith("public_")&&!basename.equalsIgnoreCase("hologram-presets")) {
            Path old=fileOrNamed(basename);
            if(old.getFileName().toString().endsWith(".gtnb"))return "此蓝图已使用原生格式";
            if(old.getFileName().toString().endsWith(".json")&&Files.isRegularFile(old,LinkOption.NOFOLLOW_LINKS)
                &&Files.isRegularFile(HologramBlueprintArtifact.previewPath(old),LinkOption.NOFOLLOW_LINKS)) {
                prepareNativeUpgrade(basename,current);
                return "原生蓝图与预览已暂存；原件未改动，当前编辑器仍使用原件";
            }
        }
        String result = HologramBlueprintFiles.upgradeNamedHistoric(directory, basename, current);
        entryCache.clear();
        return result;
    }

    /** Resolve only this local integrated save if shutdown outpaces its final network reply. */
    boolean logicalSaveCommitted(String id, String expectedJsonHash) throws IOException {
        HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(file(id));
        if (!artifact.jsonSha256.equals(expectedJsonHash)) return false;
        Path catalog = directory.resolve("hologram-presets.json");
        if (!Files.isRegularFile(catalog)) return false;
        if (Files.size(catalog) > HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES) throw new IOException(
            "蓝图库超过 " + HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES / (1024 * 1024) + " MiB 上限");
        JsonObject expected = blueprint(artifact.json);
        try {
            JsonObject collection = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec
                .parse(Files.readAllBytes(catalog), HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES);
            JsonElement rows = collection.get("presets");
            if (rows == null || !rows.isJsonArray()) return false;
            for (JsonElement row : rows.getAsJsonArray()) if (expected.equals(row)) return true;
            return false;
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图库索引格式无效", invalid);
        }
    }

    synchronized byte[] readPrivateDocument(String id) throws IOException {
        return HologramBlueprintArtifact.readLocalJson(file(id));
    }

    EditorSource readNamedEditorSource(String alias) throws IOException {
        recoverNativeOnOpen();
        if(alias.endsWith(".json"))alias=alias.substring(0,alias.length()-5);
        Path path=HologramBlueprintFiles.resolve(directory,alias);
        return readEditorSource(alias,path,LocalEditorOrigin.NAMED_IMPORT);
    }

    synchronized EditorSource readPrivateEditorSource(String id) throws IOException {
        recoverNativeOnOpen();
        Path indexed=HologramBlueprintFiles.indexedPath(directory,id);
        if(indexed!=null && Files.isRegularFile(indexed,LinkOption.NOFOLLOW_LINKS))
            return readEditorSource(id,indexed,LocalEditorOrigin.TRACKED_PRIVATE);
        return readEditorSource(id,file(id),LocalEditorOrigin.STRICT);
    }

    private EditorSource readEditorSource(String id,Path path,LocalEditorOrigin origin)throws IOException {
        if(path.getFileName().toString().endsWith(".gtnb") || NativeBlueprintDefinition.hasMagic(path)){
            NativeBlueprintPairStore.Receipt pair=NativeBlueprintPairStore.receipt(directory,id);
            if(pair==null || !pair.source.equals(path))throw new IOException("未登记的原生蓝图，不作为旧 JSON 打开");
            return new EditorSource(null,origin,pair);
        }
        return new EditorSource(HologramBlueprintArtifact.readLocalJson(path),origin);
    }

    Path legacyCatalogPath() {
        return directory.resolve("hologram-presets.json");
    }

    List<Entry> listLegacy() throws IOException {
        if (!Files.isRegularFile(legacyCatalogPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return java.util.Collections.emptyList();
        List<Entry> entries = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JsonObject root = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec
            .readLegacyCatalog(legacyCatalogPath());
        for (JsonElement member : root.getAsJsonArray("presets")) {
            try {
                JsonObject blueprint = blueprint(member.getAsJsonObject());
                String id = string(blueprint, "id");
                if (!id.matches("[a-zA-Z0-9_-]{1,96}") || !seen.add(id)) continue;
                if (id.matches("(?:local_)?[a-fA-F0-9-]{36}")
                    && Files.isRegularFile(file(id.startsWith("local_") ? id : "local_" + id))) continue;
                entries.add(new Entry("legacy:" + id, blueprint, 0));
            } catch (IOException | RuntimeException invalid) {
                // Other readable members stay visible.
            }
            if (entries.size() >= MAX_ENTRIES) break;
        }
        return entries;
    }

    byte[] readLegacyDocument(String id) throws IOException {
        String memberId = id.startsWith("legacy:") ? id.substring("legacy:".length()) : id;
        if (!memberId.matches("[a-zA-Z0-9_-]{1,96}")) throw new IOException("旧蓝图 ID 无效");
        var catalog = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.readStoredCatalog(legacyCatalogPath());
        for (JsonElement member : catalog.root()
            .getAsJsonArray("presets")) {
            if (member.isJsonObject() && memberId.equals(string(member.getAsJsonObject(), "id")))
                return com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec
                    .legacyCatalogMember(catalog, member.getAsJsonObject());
        }
        throw new IOException("本地旧合集蓝图不存在");
    }

    byte[] read(String id) throws IOException {
        byte[] data = HologramBlueprintArtifact.readLocalPair(file(id)).json;
        parse(data);
        return data;
    }

    byte[] artifact(String id) throws IOException {
        HologramBlueprintArtifact.Artifact saved = HologramBlueprintArtifact.readPair(file(id));
        parse(saved.json);
        return HologramBlueprintArtifact.encode(saved.json, saved.png);
    }

    /** An actual publication, unlike an internal disk receipt, must originate from a compact pair. */
    byte[] publicationArtifact(String id) throws IOException {
        HologramBlueprintArtifact.Artifact saved = HologramBlueprintArtifact.readPair(file(id));
        com.miaokatze.gtmb.hologram.HologramCompactRequiredException.require(saved.json);
        return HologramBlueprintArtifact.encode(saved.json, saved.png);
    }

    /** Package an already written pair for its exact receipt. */
    byte[] previewArtifact(String id) throws IOException {
        HologramBlueprintArtifact.Artifact saved = HologramBlueprintArtifact.readPair(file(id));
        return HologramBlueprintArtifact.encode(saved.json, saved.png);
    }

    Path savedPath(String id) throws IOException {
        return file(id);
    }

    @com.github.bsideup.jabel.Desugar
    record SavedDisplayPath(Path path, boolean indexed) {}

    /** Resolve one display path using a single index read, without adopting a portable file. */
    synchronized SavedDisplayPath savedDisplayPath(String id) throws IOException {
        if (id == null || !id.matches("local_[a-fA-F0-9-]{36}")) throw new IOException("客户端蓝图 ID 无效");
        Path indexed = HologramBlueprintFiles.indexedPath(directory, id);
        if (indexed != null && Files.isRegularFile(indexed, LinkOption.NOFOLLOW_LINKS))
            return new SavedDisplayPath(indexed, true);
        Path original = indexed == null ? directory.resolve(id + ".json") : indexed;
        return new SavedDisplayPath(
            !NativeBlueprintPairStore.inactive(directory, original) && Files.isRegularFile(original) ? original : file(id),
            false);
    }

    String savedName(String id) throws IOException {
        return string(blueprint(read(id)), "name");
    }

    synchronized void delete(String id) throws IOException {
        delete(id,stage -> {});
    }

    synchronized void delete(String id, NativeBlueprintPairStore.Fault fault) throws IOException {
        recoverNativeOnOpen();
        if (id.startsWith("legacy:")) {
            HologramBlueprintFiles.deleteHistoricMember(legacyCatalogPath(), id.substring("legacy:".length()));
            return;
        }
        NativeBlueprintPairStore.Receipt expectedNative=NativeBlueprintPairStore.receipt(directory,id);
        Path selected = file(id);
        if(expectedNative!=null){
            if(!selected.equals(expectedNative.source))throw new IOException("删除前原生蓝图内容已变化");
            NativeBlueprintPairStore.delete(directory,expectedNative,fault);
            displayPaths.remove(id);
            entryCache.remove(selected.toString());
            return;
        }
        if(selected.getFileName().toString().endsWith(".gtnb"))
            throw new IOException("删除前蓝图位置已切换为原生格式，请刷新");
        if (!selected.equals(HologramBlueprintFiles.resolve(directory, id))) {
            HologramBlueprintFiles.managed(directory);
            if (!selected.equals(HologramBlueprintFiles.resolve(directory, id)))
                throw new IOException("Blueprint selection changed before delete");
        }
        HologramBlueprintFiles.deleteLegacySelection(directory,id,selected);
        displayPaths.remove(id);
        entryCache.remove(selected.toString());
    }

    String uploadedId(String server, String localId) throws IOException {
        file(localId);
        java.util.Properties links = links();
        return links.getProperty(linkKey(server, localId), "");
    }

    void uploadedId(String server, String localId, String publicId) throws IOException {
        file(localId);
        if (publicId == null || !publicId.matches("public_[a-fA-F0-9-]{36}")) throw new IOException("公库 ID 无效");
        java.util.Properties links = links();
        links.setProperty(linkKey(server, localId), publicId);
        writeLinks(links);
    }

    void reconcileUploadedId(String server, String localId, String expected, String publicId) throws IOException {
        file(localId);
        java.util.Properties links = links();
        String key = linkKey(server, localId), current = links.getProperty(key, "");
        if (publicId.equals(current)) return;
        if (!expected.equals(current)) throw new IOException("Public upload link changed during publication");
        links.setProperty(key, publicId);
        writeLinks(links);
    }

    void forgetUpload(String server, String publicId) throws IOException {
        java.util.Properties links = links();
        String prefix = UUID.nameUUIDFromBytes(server.getBytes(StandardCharsets.UTF_8)) + ".";
        links.entrySet()
            .removeIf(
                entry -> entry.getKey()
                    .toString()
                    .startsWith(prefix) && publicId.equals(entry.getValue()));
        String sourceKey = serverKey(server);
        if (publicId.equals(links.getProperty("last.public." + sourceKey))) {
            links.remove("last.public." + sourceKey);
            links.setProperty("last.source." + sourceKey, "local");
        }
        writeLinks(links);
    }

    private java.util.Properties links() throws IOException {
        java.util.Properties links = new java.util.Properties();
        Path path = metadataFile;
        if (Files.exists(path)) {
            if (Files.size(path) > 1024 * 1024) throw new IOException("上传映射文件过大");
            try (java.io.InputStream input = Files.newInputStream(path)) {
                links.load(input);
            }
        }
        return links;
    }

    private static String linkKey(String server, String localId) {
        return UUID.nameUUIDFromBytes(server.getBytes(StandardCharsets.UTF_8)) + "." + localId;
    }

    Path directory() {
        return directory;
    }

    static final class LastSource {

        final String localId, publicId;
        final boolean publicLibrary;

        LastSource(String localId, String publicId, boolean publicLibrary) {
            this.localId = localId;
            this.publicId = publicId;
            this.publicLibrary = publicLibrary;
        }
    }

    LastSource lastSource(String server) throws IOException {
        java.util.Properties preferences = links();
        String key = serverKey(server);
        return new LastSource(
            preferences.getProperty("last.local", ""),
            preferences.getProperty("last.public." + key, ""),
            "public".equals(preferences.getProperty("last.source." + key, "local")));
    }

    void rememberLocal(String server, String localId) throws IOException {
        file(localId);
        java.util.Properties preferences = links();
        preferences.setProperty("last.local", localId);
        preferences.setProperty("last.source." + serverKey(server), "local");
        writeLinks(preferences);
    }

    void rememberPublic(String server, String publicId) throws IOException {
        if (publicId == null || !publicId.matches("public_[a-fA-F0-9-]{36}")) throw new IOException("公库 ID 无效");
        java.util.Properties preferences = links();
        String key = serverKey(server);
        preferences.setProperty("last.public." + key, publicId);
        preferences.setProperty("last.source." + key, "public");
        writeLinks(preferences);
    }

    private void writeLinks(java.util.Properties preferences) throws IOException {
        Files.createDirectories(metadataFile.getParent());
        Path temporary = Files.createTempFile(metadataFile.getParent(), "library-memory-", ".tmp");
        try {
            try (java.io.OutputStream output = Files.newOutputStream(temporary)) {
                preferences.store(output, "Client library memory");
            }
            try {
                Files
                    .move(temporary, metadataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, metadataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String serverKey(String server) {
        return UUID.nameUUIDFromBytes(server.getBytes(StandardCharsets.UTF_8))
            .toString();
    }

    private Path fileOrNamed(String id) throws IOException {
        return id.matches("local_[a-fA-F0-9-]{36}")?file(id):HologramBlueprintFiles.resolve(directory,id);
    }

    private synchronized Path file(String id) throws IOException {
        if (id == null || !id.matches("local_[a-fA-F0-9-]{36}")) throw new IOException("客户端蓝图 ID 无效");
        Path indexed = HologramBlueprintFiles.resolve(directory, id);
        if (Files.isRegularFile(indexed)) return indexed;
        Path displayed = displayPaths.get(id);
        return displayed == null || !Files.isRegularFile(displayed,LinkOption.NOFOLLOW_LINKS)
            || NativeBlueprintPairStore.inactive(directory,displayed) ? indexed : displayed;
    }

    private static JsonObject parse(byte[] data) throws IOException {
        if (data == null || data.length > MAX_LOCAL_BYTES)
            throw new IOException("客户端蓝图超过 " + MAX_LOCAL_BYTES / (1024 * 1024) + " MiB");
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            int depth = 0;
            boolean string = false, escaped = false;
            for (int i = 0; i < json.length(); i++) {
                char character = json.charAt(i);
                if (string) {
                    if (escaped) escaped = false;
                    else if (character == '\\') escaped = true;
                    else if (character == '"') string = false;
                } else if (character == '"') string = true;
                else if (character == '{' || character == '[') {
                    if (++depth > 64) throw new IOException("蓝图 JSON 嵌套过深");
                } else if (character == '}' || character == ']') depth--;
            }
            JsonObject object = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.localMetadata(data);
            blueprint(object);
            if (data.length <= MAX_BYTES && blueprint(object).get("displayCells").getAsInt()
                <= com.miaokatze.gtmb.hologram.HologramBlueprintLimits.MAX_CELLS)
                HologramLibraryFacade.validateNativeJson(data);
            return object;
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图 JSON 无效", invalid);
        }
    }

    private static JsonObject blueprint(byte[] data) throws IOException {
        return blueprint(parse(data));
    }

    private static JsonObject blueprint(JsonObject root) throws IOException {
        JsonObject object = root.has("blueprint") && root.get("blueprint")
            .isJsonObject() ? root.getAsJsonObject("blueprint") : root;
        if (!object.has("cells") || !object.get("cells")
            .isJsonArray() || string(object, "name").isEmpty()) throw new IOException("蓝图缺少名称或方块列表");
        return object;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key)
            .isJsonNull() ? object.get(key)
                .getAsString() : "";
    }

    private static int size(JsonObject object, String key) {
        return object.has(key) && object.get(key)
            .isJsonArray() ? object.getAsJsonArray(key)
                .size() : 0;
    }
}
