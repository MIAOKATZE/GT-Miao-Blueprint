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
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramLibraryFacade;

/** Client-owned files, independent of world saves and server identities. Call from the library worker only. */
final class HologramClientLibrary {

    static final int MAX_BYTES = 8 * 1024 * 1024, MAX_ENTRIES = 128;
    private final Path directory;
    private final Path metadataFile;

    HologramClientLibrary(Path gameDirectory) {
        directory = gameDirectory.resolve("MiaoBlueprints")
            .toAbsolutePath()
            .normalize();
        metadataFile = gameDirectory.resolve("config/gtmb/client-blueprint-library.properties")
            .toAbsolutePath()
            .normalize();
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
        Files.createDirectories(directory);
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                String id = file.getFileName()
                    .toString()
                    .replaceFirst("\\.json$", "");
                if (!id.matches("local_[a-fA-F0-9-]{36}")) continue;
                try {
                    byte[] data = read(id);
                    entries.add(new Entry(id, blueprint(data), data.length));
                } catch (IOException | RuntimeException invalid) {
                    // A damaged individual file must not hide every other portable blueprint.
                }
                if (entries.size() >= MAX_ENTRIES) break;
            }
        }
        entries.sort((a, b) -> {
            int time = Long.compare(b.createdAt, a.createdAt);
            return time == 0 ? a.id.compareTo(b.id) : time;
        });
        return entries;
    }

    String save(byte[] data, byte[] preview, String replaceId) throws IOException {
        return save(data, preview, replaceId, () -> true);
    }

    String save(byte[] data, byte[] preview, String replaceId, java.util.function.BooleanSupplier stillCurrent)
        throws IOException {
        JsonObject blueprint = blueprint(data);
        String id = replaceId == null || replaceId.isEmpty() ? "local_" + UUID.randomUUID() : replaceId;
        Path target = file(id);
        if (!Files.exists(target) && list().size() >= MAX_ENTRIES) throw new IOException("客户端蓝图库已满（128 项）");
        if (string(blueprint, "authorName").isEmpty() || string(blueprint, "authorUuid").isEmpty())
            throw new IOException("蓝图缺少服务器确认的作者信息");
        HologramBlueprintArtifact.writePair(target, data, preview, stillCurrent);
        return id;
    }

    /** A JSON-only save can never acknowledge a completed native artifact. */
    String save(byte[] data, String replaceId, String playerName, String playerUuid) throws IOException {
        parse(data);
        throw new IOException("蓝图缺少真实结构预览 PNG");
    }

    HologramBlueprintArtifact.PairWrite savePending(byte[] data, byte[] preview, String id,
        java.util.function.BooleanSupplier stillCurrent) throws IOException {
        JsonObject blueprint = blueprint(data);
        Path target = file(id);
        if (!Files.exists(target) && list().size() >= MAX_ENTRIES) throw new IOException("客户端蓝图库已满（128 项）");
        if (string(blueprint, "authorName").isEmpty() || string(blueprint, "authorUuid").isEmpty())
            throw new IOException("蓝图缺少服务器确认的作者信息");
        return HologramBlueprintArtifact.writePairPending(target, data, preview, stillCurrent);
    }

    /** Resolve only this local integrated save if shutdown outpaces its final network reply. */
    boolean logicalSaveCommitted(String id, String expectedJsonHash) throws IOException {
        HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(file(id));
        if (!artifact.jsonSha256.equals(expectedJsonHash)) return false;
        Path catalog = directory.resolve("hologram-presets.json");
        if (!Files.isRegularFile(catalog)) return false;
        if (Files.size(catalog) > 64L * 1024 * 1024) throw new IOException("蓝图库超过64MiB上限");
        JsonObject expected = blueprint(artifact.json);
        try {
            JsonObject collection = new JsonParser()
                .parse(new String(Files.readAllBytes(catalog), StandardCharsets.UTF_8))
                .getAsJsonObject();
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

    Path savedPath(String id) throws IOException {
        return file(id);
    }

    String savedName(String id) throws IOException {
        return string(blueprint(read(id)), "name");
    }

    void delete(String id) throws IOException {
        HologramBlueprintArtifact.deletePair(file(id));
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
        Path path = directory.resolve(id + ".json")
            .normalize();
        if (!path.getParent()
            .equals(directory)) throw new IOException("客户端蓝图路径无效");
        return path;
    }

    private static JsonObject parse(byte[] data) throws IOException {
        if (data == null || data.length > MAX_BYTES) throw new IOException("客户端蓝图超过 8 MiB");
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
            JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonObject()) throw new IOException("蓝图 JSON 无效");
            JsonObject object = root.getAsJsonObject();
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
