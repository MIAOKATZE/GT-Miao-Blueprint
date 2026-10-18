package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintFiles;
import com.miaokatze.gtmb.hologram.HologramLibraryFacade;

/** Client-owned files, independent of world saves and server identities. Call from the library worker only. */
final class HologramClientLibrary {

    static final int MAX_BYTES = HologramBlueprintArtifact.MAX_JSON_BYTES, MAX_ENTRIES = 128;
    private final Path directory;
    private final Path metadataFile;
    private final PairIo pairIo;
    private final java.util.Map<String, CachedEntry> entryCache = new java.util.HashMap<>();

    interface PairIo {

        HologramBlueprintArtifact.PairWrite write(Path path, byte[] json, byte[] png,
            java.util.function.BooleanSupplier current) throws IOException;

        HologramBlueprintArtifact.Artifact read(Path path) throws IOException;
    }

    private static final PairIo FILE_PAIRS = new PairIo() {

        @Override
        public HologramBlueprintArtifact.PairWrite write(Path path, byte[] json, byte[] png,
            java.util.function.BooleanSupplier current) throws IOException {
            return HologramBlueprintArtifact.writePairPending(path, json, png, current);
        }

        @Override
        public HologramBlueprintArtifact.Artifact read(Path path) throws IOException {
            return HologramBlueprintArtifact.readPair(path);
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
        final String jsonHash, pngHash;

        CachedEntry(Entry entry, HologramBlueprintArtifact.Artifact actual) {
            this.entry = entry;
            jsonHash = actual.jsonSha256;
            pngHash = actual.pngSha256;
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

    static final class Entry {

        final String id, name, authorName, authorUuid, sourceMod;
        final int cells, structures;
        final long bytes, createdAt;

        Entry(String id, JsonObject blueprint, long bytes) {
            this.id = id;
            name = string(blueprint, "name");
            authorName = string(blueprint, "authorName");
            authorUuid = string(blueprint, "authorUuid");
            sourceMod = string(blueprint, "sourceMod");
            createdAt = blueprint.has("createdAt") ? blueprint.get("createdAt")
                .getAsLong() : 0;
            cells = size(blueprint, "cells");
            structures = size(blueprint, "structures");
            this.bytes = bytes;
        }
    }

    List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Files.createDirectories(directory);
        {
            for (java.util.Map.Entry<String, Path> member : HologramBlueprintFiles.managed(directory)
                .entrySet()) {
                String id = member.getKey();
                Path file = member.getValue();
                seen.add(id);
                try {
                    CachedEntry cached = entryCache.get(id);
                    if (cached == null || !cached.jsonHash.equals(memberHash(file, MAX_BYTES))
                        || !cached.pngHash.equals(
                            memberHash(
                                HologramBlueprintArtifact.previewPath(file),
                                HologramBlueprintArtifact.MAX_PNG_BYTES))) {
                        HologramBlueprintArtifact.Artifact actual = pairIo.read(file);
                        JsonObject display = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec
                            .parse(actual.json);
                        cached = new CachedEntry(new Entry(id, blueprint(display), actual.json.length), actual);
                        entryCache.put(id, cached);
                    }
                    entries.add(cached.entry);
                } catch (IOException | RuntimeException invalid) {
                    entryCache.remove(id);
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

    private static String memberHash(Path path, int maximum) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > maximum)
            throw new IOException("Invalid local artifact member");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maximum) throw new IOException("Invalid local artifact member");
        return HologramBlueprintArtifact.hash(bytes);
    }

    /** One validation before writing and one actual readback; later catalog refreshes reuse these exact hashes. */
    LocalSave prepareLocalSave(byte[] data, byte[] preview, String id, java.util.function.BooleanSupplier stillCurrent)
        throws IOException {
        JsonObject blueprint = blueprint(data);
        Path previous = file(id);
        if (!Files.exists(previous) && list().size() >= MAX_ENTRIES) throw new IOException("客户端蓝图库已满（128 项）");
        Path target = HologramBlueprintFiles.target(directory, id, data);
        if (string(blueprint, "authorName").isEmpty() || string(blueprint, "authorUuid").isEmpty())
            throw new IOException("蓝图缺少服务器确认的作者信息");
        HologramBlueprintArtifact.PairWrite written = pairIo.write(target, data, preview, stillCurrent);
        try {
            HologramBlueprintArtifact.Artifact actual = pairIo.read(target);
            if (!written.jsonSha256.equals(actual.jsonSha256) || !written.pngSha256.equals(actual.pngSha256))
                throw new IOException("Actual saved artifact differs from written bytes");
            if (!stillCurrent.getAsBoolean()) throw new IOException("Blueprint save source changed");
            HologramBlueprintFiles.track(directory, id, written);
            entryCache.put(id, new CachedEntry(new Entry(id, blueprint, actual.json.length), actual));
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

    String upgradeLegacy() throws IOException {
        return upgradeLegacy(() -> true, (done, total) -> {});
    }

    String upgradeLegacy(java.util.function.BooleanSupplier current,
        java.util.function.BiConsumer<Integer, Integer> progress) throws IOException {
        String result = HologramBlueprintFiles.upgrade(directory, current, progress);
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

    byte[] read(String id) throws IOException {
        byte[] data = HologramBlueprintArtifact.readPair(file(id)).json;
        parse(data);
        return data;
    }

    byte[] artifact(String id) throws IOException {
        HologramBlueprintArtifact.Artifact saved = HologramBlueprintArtifact.readPair(file(id));
        parse(saved.json);
        return HologramBlueprintArtifact.encode(saved.json, saved.png);
    }

    /** Display reads have byte/PNG bounds, but never run construction policy validation. */
    byte[] previewArtifact(String id) throws IOException {
        HologramBlueprintArtifact.Artifact saved = HologramBlueprintArtifact.readPair(file(id));
        return HologramBlueprintArtifact.encode(saved.json, saved.png);
    }

    Path savedPath(String id) throws IOException {
        return file(id);
    }

    String savedName(String id) throws IOException {
        return string(blueprint(read(id)), "name");
    }

    void delete(String id) throws IOException {
        file(id);
        HologramBlueprintFiles.delete(directory, id);
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

    private Path file(String id) throws IOException {
        if (id == null || !id.matches("local_[a-fA-F0-9-]{36}")) throw new IOException("客户端蓝图 ID 无效");
        return HologramBlueprintFiles.resolve(directory, id);
    }

    private static JsonObject parse(byte[] data) throws IOException {
        if (data == null || data.length > MAX_BYTES)
            throw new IOException("客户端蓝图超过 " + MAX_BYTES / (1024 * 1024) + " MiB");
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
            JsonObject object = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.parse(data);
            blueprint(object);
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
