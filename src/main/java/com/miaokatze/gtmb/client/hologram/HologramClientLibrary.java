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
    static final int MAX_AUTHOR_BYTES = com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO.Limits.defaults().maxBytes;
    private final Path directory;
    private final Path metadataFile;
    private final PairIo pairIo;
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
            createdAt = blueprint.has("createdAt") ? blueprint.get("createdAt")
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

    List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        listAuthorDrafts(entries);
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
                        JsonObject display = displayMetadata(file);
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

    private void listAuthorDrafts(List<Entry> entries) throws IOException {
        Path drafts = directory.resolve("drafts");
        if (!Files.isDirectory(drafts)) return;
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(drafts, "*.gtmb-draft.json")) {
            for (Path file : files) {
                if (entries.size() >= MAX_ENTRIES) break;
                try {
                    String name = file.getFileName()
                        .toString();
                    String id = name.substring(0, name.length() - ".gtmb-draft.json".length());
                    if (!file.equals(authorDraftPath(id)) || Files.size(file) > MAX_AUTHOR_BYTES) continue;
                    JsonObject metadata = draftMetadata(file, id);
                    metadata.addProperty("sourceMod", "GTMB local recovery draft");
                    metadata.addProperty(
                        "createdAt",
                        Files.getLastModifiedTime(file)
                            .toMillis());
                    entries.add(new Entry("draft_" + id, metadata, Files.size(file)));
                } catch (IOException | RuntimeException damaged) {
                    // One damaged recovery file cannot hide other documents.
                }
            }
        }
    }

    /** Catalog summaries skip all opaque NBT and unknown values; opening still performs strict admission. */
    private static JsonObject draftMetadata(Path file, String expectedId) throws IOException {
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
            Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            JsonObject metadata = null;
            String format = "", id = "";
            int version = -1;
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if ("format".equals(key)) format = reader.nextString();
                else if ("documentId".equals(key)) id = reader.nextString();
                else if ("version".equals(key)) version = reader.nextInt();
                else if ("source".equals(key)) metadata = draftSourceMetadata(reader, true);
                else reader.skipValue();
            }
            reader.endObject();
            if (!"gtmb-author-draft".equals(format) || version != 1 || !expectedId.equals(id) || metadata == null)
                throw new IOException("Invalid recovery draft summary");
            return metadata;
        }
    }

    // A recovery source can retain historical fullyVoucherCovered=true after edits. Do not promote that
    // raw history to a current catalogue ribbon. Original formal artifacts use displayMetadata unchanged;
    // newly saved formal artifacts receive a fresh marker from the server reviewed-save coverage task.
    private static JsonObject draftSourceMetadata(com.google.gson.stream.JsonReader reader, boolean envelope)
        throws IOException {
        JsonObject metadata = new JsonObject(), wrapped = null;
        boolean invalidBare = false;
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (envelope && "blueprint".equals(key)) wrapped = draftSourceMetadata(reader, false);
            else if (wrapped != null) reader.skipValue();
            else if ("name".equals(key) || "authorName".equals(key) || "authorUuid".equals(key)) {
                if (reader.peek() == com.google.gson.stream.JsonToken.STRING)
                    metadata.addProperty(key, reader.nextString());
                else {
                    // Author identity descriptions are opaque recovery data; name is typed only in a bare source.
                    if ("name".equals(key) && reader.peek() != com.google.gson.stream.JsonToken.NULL)
                        invalidBare = true;
                    reader.skipValue();
                }
            } else if ("cells".equals(key) || "structures".equals(key)) {
                int count = 0;
                if (reader.peek() == com.google.gson.stream.JsonToken.NULL) reader.nextNull();
                else if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.skipValue();
                        count++;
                    }
                    reader.endArray();
                } else {
                    // A later blueprint member may identify these as unknown outer envelope fields.
                    invalidBare = true;
                    reader.skipValue();
                }
                metadata.addProperty("cells".equals(key) ? "displayCells" : "displayStructures", count);
            } else reader.skipValue();
        }
        reader.endObject();
        if (wrapped != null) return wrapped;
        if (invalidBare) throw new IOException("Invalid recovery blueprint summary fields");
        return metadata;
    }

    private static String stamp(Path path) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attributes = Files
            .readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES)
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
            JsonObject metadata = new JsonObject();
            reader.beginObject();
            boolean blueprint = false;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!name.equals("blueprint")) {
                    reader.skipValue();
                    continue;
                }
                blueprint = true;
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.equals("cells") || key.equals("structures")) {
                        int count = 0;
                        reader.beginArray();
                        while (reader.hasNext()) {
                            reader.skipValue();
                            count++;
                        }
                        reader.endArray();
                        metadata.addProperty(key.equals("cells") ? "displayCells" : "displayStructures", count);
                    } else if (key.equals("nativeBlueprint"))
                        metadata.add(key, new com.google.gson.JsonParser().parse(reader));
                    else if (key.equals("fullyVoucherCovered")
                        && reader.peek() == com.google.gson.stream.JsonToken.BOOLEAN)
                        metadata.addProperty(key, reader.nextBoolean());
                    else if (key.equals("createdAt")) metadata.addProperty(key, reader.nextLong());
                    else if (key.equals("id") || key.equals("name")
                        || key.equals("authorName")
                        || key.equals("authorUuid")
                        || key.equals("sourceMod")) metadata.addProperty(key, reader.nextString());
                    else reader.skipValue();
                }
                reader.endObject();
            }
            reader.endObject();
            if (!blueprint || !metadata.has("displayCells")) throw new IOException("Missing blueprint display data");
            return metadata;
        }
    }

    /** Save validation and actual readback remain separate from display-only catalog hints. */
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

    /** Explicit offline candidate export, deliberately outside the reviewed library and its actor guard. */
    Path savePortableCandidate(HologramBlueprintArtifact.Artifact artifact, java.util.function.BooleanSupplier current)
        throws IOException {
        if (!current.getAsBoolean()) throw new IOException("Author source changed before candidate export");
        // Compilation already ran the native providers on the owner thread. Disk persistence verifies only
        // the exact bounded pair; neither re-run native providers on this IO worker nor grant reviewed status.
        Path target = directory.resolve("candidates")
            .resolve("unreviewed_" + UUID.randomUUID() + ".json");
        HologramBlueprintArtifact.PairWrite receipt = pairIo.write(target, artifact.json, artifact.png, current);
        try {
            HologramBlueprintArtifact.Artifact actual = pairIo.read(target);
            if (!artifact.jsonSha256.equals(actual.jsonSha256) || !artifact.pngSha256.equals(actual.pngSha256))
                throw new IOException("Candidate artifact readback differs from exact source");
            if (!current.getAsBoolean()) throw new IOException("Author source changed during candidate export");
            receipt.commit();
            return target;
        } catch (IOException | RuntimeException failure) {
            try {
                receipt.rollback();
            } catch (IOException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    /** A recovery draft is a separate, single-file artifact: no PNG, actor or portable receipt. */
    Path saveAuthorDraft(String documentId, byte[] bytes) throws IOException {
        Path target = authorDraftPath(documentId);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_AUTHOR_BYTES)
            throw new IOException("Local draft byte budget exceeded");
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".draft-", ".tmp");
        try {
            try (java.nio.channels.FileChannel output = java.nio.channels.FileChannel.open(
                temporary,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            if (!java.util.Arrays.equals(bytes, Files.readAllBytes(temporary)))
                throw new IOException("Local draft readback mismatch");
            // Atomic rename is required; do not silently replace with a crash-vulnerable truncate/write.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Path authorDraftPath(String documentId) throws IOException {
        if (documentId == null || !documentId.matches("[a-fA-F0-9-]{36}"))
            throw new IOException("Invalid local document identity");
        return directory.resolve("drafts")
            .resolve(documentId + ".gtmb-draft.json");
    }

    /** Read original source bytes, never a display DTO and never require a thumbnail. */
    byte[] authorSource(String id) throws IOException {
        Path source = id != null && id.startsWith("draft_") ? authorDraftPath(id.substring(6)) : savedPath(id);
        int maximum = id != null && id.startsWith("draft_") ? MAX_AUTHOR_BYTES : MAX_BYTES;
        long size = Files.size(source);
        if (size > maximum) throw new IOException("Local source byte budget exceeded");
        try (java.io.InputStream input = Files.newInputStream(source);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (count > maximum - output.size()) throw new IOException("Local source byte budget exceeded");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
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

    /** Placement upload only: validate and package the original pair after a server hash miss. */
    byte[] previewArtifact(String id) throws IOException {
        HologramBlueprintArtifact.Artifact saved = HologramBlueprintArtifact.readPair(file(id));
        return HologramBlueprintArtifact.encode(saved.json, saved.png);
    }

    Path savedPath(String id) throws IOException {
        return id != null && id.startsWith("draft_") ? authorDraftPath(id.substring(6)) : file(id);
    }

    String savedName(String id) throws IOException {
        return string(blueprint(read(id)), "name");
    }

    void delete(String id) throws IOException {
        if (id != null && id.startsWith("draft_")) {
            Files.deleteIfExists(authorDraftPath(id.substring(6)));
            return;
        }
        Path selected = file(id);
        if (!selected.equals(HologramBlueprintFiles.resolve(directory, id))) {
            HologramBlueprintFiles.managed(directory);
            if (!selected.equals(HologramBlueprintFiles.resolve(directory, id)))
                throw new IOException("Blueprint selection changed before delete");
        }
        HologramBlueprintFiles.delete(directory, id);
        displayPaths.remove(id);
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
        Path indexed = HologramBlueprintFiles.resolve(directory, id);
        if (Files.isRegularFile(indexed)) return indexed;
        Path displayed = displayPaths.get(id);
        return displayed == null ? indexed : displayed;
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
