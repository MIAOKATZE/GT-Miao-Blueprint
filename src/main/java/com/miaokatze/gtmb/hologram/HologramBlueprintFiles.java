package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.google.gson.JsonObject;

/** Physical names are presentation only. Logical IDs and old share aliases live in a bounded local index. */
public final class HologramBlueprintFiles {

    private static final String INDEX = ".gtmb-blueprint-files.properties";
    private static final int MAX_INDEX_BYTES = 1024 * 1024;

    private HologramBlueprintFiles() {}

    /** An absent ID resolves to its legacy path, so existing existence checks remain meaningful. */
    public static synchronized Path resolve(Path directory, String id) throws IOException {
        validId(id);
        Path root = directory.toAbsolutePath()
            .normalize();
        String member = index(root).getProperty(id);
        return member == null ? child(root, id + ".json") : child(root, member);
    }

    public static Map<String, Path> managed(Path directory) throws IOException {
        adoptPortablePairs(
            directory.toAbsolutePath()
                .normalize());
        return managed(directory, "local_");
    }

    public static synchronized Map<String, Path> managedPublic(Path directory) throws IOException {
        return managed(directory, "public_");
    }

    private static synchronized Map<String, Path> managed(Path directory, String prefix) throws IOException {
        Path root = directory.toAbsolutePath()
            .normalize();
        Files.createDirectories(root);
        Map<String, Path> result = new LinkedHashMap<>();
        Properties index = index(root);
        for (String id : index.stringPropertyNames()) if (id.matches(prefix + "[a-fA-F0-9-]{36}")) {
            Path path = child(root, index.getProperty(id));
            if (result.containsValue(path)) throw new IOException("Duplicate blueprint filename mapping");
            result.put(id, path);
        }
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(root, prefix + "*.json")) {
            for (Path file : files) {
                String name = file.getFileName()
                    .toString();
                String id = name.substring(0, name.length() - 5);
                if (id.matches(prefix + "[a-fA-F0-9-]{36}")) result.putIfAbsent(id, file);
            }
        }
        return result;
    }

    public static synchronized Path target(Path directory, String id, byte[] json) throws IOException {
        validId(id);
        Path root = directory.toAbsolutePath()
            .normalize();
        JsonObject document = HologramBlueprintCompactCodec.parse(json);
        JsonObject blueprint = document.has("blueprint") ? document.getAsJsonObject("blueprint") : document;
        String name = blueprint.has("name") ? blueprint.get("name")
            .getAsString() : "蓝图";
        JsonObject content = new JsonObject();
        content.add("blueprint", blueprint);
        String hash = HologramBlueprintContentHash.of(
            content.toString()
                .getBytes(StandardCharsets.UTF_8));
        String stem = safeName(name) + "_" + hash;
        Path target = child(root, stem + ".json");
        Properties index = index(root);
        String current = index.getProperty(id, id + ".json");
        boolean occupied = false;
        for (String key : index.stringPropertyNames()) if (!key.equals(id) && target.getFileName()
            .toString()
            .equals(index.getProperty(key))) occupied = true;
        if (occupied && !target.getFileName()
            .toString()
            .equals(current) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !target.getFileName()
                    .toString()
                    .equals(current)) {
            // A descriptive rename or identical content never merges distinct logical blueprints.
            target = child(
                root,
                stem + "_" + HologramBlueprintArtifact.hash(id.getBytes(StandardCharsets.UTF_8)) + ".json");
            for (String key : index.stringPropertyNames()) if (!target.getFileName()
                .toString()
                .equals(current) && !key.equals(id)
                && target.getFileName()
                    .toString()
                    .equals(index.getProperty(key)))
                throw new IOException("Blueprint filename identity collision");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !target.getFileName()
                .toString()
                .equals(current)) throw new IOException("Blueprint filename already exists outside this logical entry");
        }
        return target;
    }

    /** Publish a verified pending pair; failed acknowledgement restores both the pair and its filename mapping. */
    public static void track(Path directory, String id, HologramBlueprintArtifact.PairWrite receipt)
        throws IOException {
        track(directory, id, receipt, HologramBlueprintFiles::writeIndex);
    }

    interface IndexWriteOperation {

        void write(Path directory, Properties values) throws IOException;
    }

    static void track(Path directory, String id, HologramBlueprintArtifact.PairWrite receipt,
        IndexWriteOperation publish) throws IOException {
        Path root = directory.toAbsolutePath()
            .normalize();
        Path target = child(
            root,
            receipt.path.getFileName()
                .toString());
        if (!target.equals(receipt.path)) throw new IOException("Blueprint pair is outside its library");
        final Properties before;
        synchronized (HologramBlueprintFiles.class) {
            before = index(root);
        }
        final String previous = before.getProperty(id);
        Path old = previous == null ? child(root, id + ".json") : child(root, previous);
        String oldJsonHash = old.equals(target) ? null
            : memberHashOrAbsent(old, HologramBlueprintArtifact.MAX_JSON_BYTES);
        String oldPngHash = old.equals(target) ? null
            : memberHashOrAbsent(HologramBlueprintArtifact.previewPath(old), HologramBlueprintArtifact.MAX_PNG_BYTES);
        String member = target.getFileName()
            .toString();
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(id, previous);
        for (String key : before.stringPropertyNames()) if (old.getFileName()
            .toString()
            .equals(before.getProperty(key))) changes.put(key, before.getProperty(key));
        // Install recovery first, without holding the filename-index monitor while acquiring a receipt lock.
        receipt.onCompletion(() -> {
            if (!old.equals(target)) {
                synchronized (HologramBlueprintFiles.class) {
                    if (!member.equals(index(root).getProperty(id))) throw new IOException("蓝图映射已由较新的保存替换，保留旧文件");
                }
                if (!java.util.Objects
                    .equals(oldJsonHash, memberHashOrAbsent(old, HologramBlueprintArtifact.MAX_JSON_BYTES))
                    || !java.util.Objects.equals(
                        oldPngHash,
                        memberHashOrAbsent(
                            HologramBlueprintArtifact.previewPath(old),
                            HologramBlueprintArtifact.MAX_PNG_BYTES)))
                    throw new IOException("旧蓝图在保存期间已变化，保留旧文件");
                HologramBlueprintArtifact.deletePair(old);
            }
        }, () -> {
            synchronized (HologramBlueprintFiles.class) {
                Properties actual = index(root);
                for (Map.Entry<String, String> change : changes.entrySet()) {
                    if (!member.equals(actual.getProperty(change.getKey()))) continue;
                    if (change.getValue() == null) actual.remove(change.getKey());
                    else actual.setProperty(change.getKey(), change.getValue());
                }
                writeIndex(root, actual);
            }
        });
        synchronized (HologramBlueprintFiles.class) {
            Properties actual = index(root);
            if (!java.util.Objects.equals(previous, actual.getProperty(id)))
                throw new IOException("Blueprint filename mapping changed during save");
            for (String key : changes.keySet()) actual.setProperty(key, member);
            publish.write(root, actual);
        }
    }

    public static synchronized void forget(Path directory, String id) throws IOException {
        Properties index = index(directory);
        index.remove(id);
        writeIndex(directory, index);
    }

    /** Remove a logical entry and its pair; a failed pair deletion restores every old filename alias. */
    public static void delete(Path directory, String id) throws IOException {
        Path root = directory.toAbsolutePath()
            .normalize();
        final Path file;
        final Map<String, String> removed = new LinkedHashMap<>();
        synchronized (HologramBlueprintFiles.class) {
            file = resolve(root, id);
            Properties actual = index(root);
            for (String key : actual.stringPropertyNames()) if (file.getFileName()
                .toString()
                .equals(actual.getProperty(key))) removed.put(key, actual.getProperty(key));
            for (String key : removed.keySet()) actual.remove(key);
            writeIndex(root, actual);
        }
        try {
            HologramBlueprintArtifact.deletePair(file);
        } catch (IOException | RuntimeException failure) {
            try {
                synchronized (HologramBlueprintFiles.class) {
                    Properties actual = index(root);
                    for (Map.Entry<String, String> previous : removed.entrySet()) {
                        if (actual.containsKey(previous.getKey()))
                            throw new IOException("Blueprint deletion mapping changed during rollback");
                        actual.setProperty(previous.getKey(), previous.getValue());
                    }
                    writeIndex(root, actual);
                }
            } catch (IOException restore) {
                failure.addSuppressed(restore);
            }
            throw failure;
        }
    }

    /** Best effort by entry: damaged or ambiguous originals are retained and reported, never guessed or deleted. */
    public static String upgrade(Path directory) throws IOException {
        return upgrade(directory, () -> true, (done, total) -> {});
    }

    public static String upgrade(Path directory, java.util.function.BooleanSupplier currentOperation,
        java.util.function.BiConsumer<Integer, Integer> progress) throws IOException {
        Path root = directory.toAbsolutePath()
            .normalize();
        Files.createDirectories(root);
        int upgraded = 0, current = 0, skipped = 0;
        List<String> reasons = new ArrayList<>();
        List<Path> jsonFiles = new ArrayList<>(), pngFiles = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                String name = file.getFileName()
                    .toString();
                if (name.endsWith(".json")) jsonFiles.add(file);
                else if (name.endsWith(".png")) pngFiles.add(file);
                if (jsonFiles.size() > 4096 || pngFiles.size() > 4096) throw new IOException("蓝图升级目录超过4096组文件上限，请分批整理");
            }
        }
        jsonFiles.sort(
            java.util.Comparator.comparing(
                path -> path.getFileName()
                    .toString()));
        int done = 0;
        for (Path source : jsonFiles) {
            requireCurrent(currentOperation);
            progress.accept(done, jsonFiles.size());
            String name = source.getFileName()
                .toString();
            try {
                if (name.equals("hologram-presets.json")) {
                    byte[] old = read(source, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES);
                    byte[] compact = HologramBlueprintCompactCodec
                        .recompress(old, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES, currentOperation);
                    progress.accept(done, jsonFiles.size());
                    if (java.util.Arrays.equals(old, compact)) current++;
                    else {
                        requireCurrent(currentOperation);
                        HologramPresets.replaceCompressedCatalog(source, old, compact);
                        upgraded++;
                    }
                    done++;
                    progress.accept(done, jsonFiles.size());
                    continue;
                }
                byte[] old = read(source, HologramBlueprintArtifact.MAX_JSON_BYTES);
                JsonObject document = HologramBlueprintCompactCodec.parse(old);
                if (!document.has("blueprint")) throw new IOException("缺少独立蓝图容器");
                JsonObject blueprint = document.getAsJsonObject("blueprint");
                if (!blueprint.has("name") || !blueprint.has("cells")) throw new IOException("缺少名称或方块列表");
                Path pngPath = HologramBlueprintArtifact.previewPath(source);
                byte[] png;
                if (Files.isRegularFile(pngPath, LinkOption.NOFOLLOW_LINKS)) {
                    png = read(pngPath, HologramBlueprintArtifact.MAX_PNG_BYTES);
                    HologramBlueprintArtifact.validatePng(png);
                    // An already bound sibling must match. Unbound legacy siblings retain their explicit pairing.
                    byte[] rebound = HologramBlueprintArtifact.bindPreview(old, png);
                    if (!java.util.Arrays.equals(png, rebound) && HologramBlueprintArtifact.previewHasBinding(png))
                        throw new IOException("同名预览摘要与JSON不匹配");
                } else {
                    png = null;
                    Path match = null;
                    for (Path candidate : pngFiles) {
                        try {
                            byte[] candidateBytes = read(candidate, HologramBlueprintArtifact.MAX_PNG_BYTES);
                            if (!HologramBlueprintArtifact.previewMatches(old, candidateBytes)) continue;
                            if (match != null) throw new IOException("存在多个摘要匹配的PNG");
                            match = candidate;
                            png = candidateBytes;
                        } catch (IOException invalidPng) {
                            if ("存在多个摘要匹配的PNG".equals(invalidPng.getMessage())) throw invalidPng;
                        }
                    }
                    if (match == null) throw new IOException("缺少同名或唯一摘要匹配PNG");
                    pngPath = match;
                }
                String id = adopt(root, source, document);
                byte[] compact = HologramBlueprintCompactCodec
                    .recompress(old, HologramBlueprintArtifact.MAX_JSON_BYTES, currentOperation);
                progress.accept(done, jsonFiles.size());
                Path target = target(root, id, compact);
                byte[] bound = HologramBlueprintArtifact.bindPreview(compact, png);
                if (source.equals(target) && java.util.Arrays.equals(old, compact)
                    && java.util.Arrays.equals(png, bound)) {
                    current++;
                    done++;
                    progress.accept(done, jsonFiles.size());
                    continue;
                }
                HologramBlueprintArtifact.PairWrite receipt = HologramBlueprintArtifact
                    .writePairPending(target, compact, png, currentOperation);
                try {
                    HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.readPair(target);
                    if (!java.util.Arrays.equals(actual.json, compact)) throw new IOException("升级回读校验失败");
                    requireCurrent(currentOperation);
                    track(root, id, receipt);
                    requireCurrent(currentOperation);
                } catch (IOException | RuntimeException failure) {
                    try {
                        receipt.rollback();
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
                receipt.commit();
                if (!receipt.cleanupWarning()
                    .isEmpty()) reasons.add(name + "：升级完成，旧文件清理待重试：" + receipt.cleanupWarning());
                upgraded++;
                if (!pngPath.equals(HologramBlueprintArtifact.previewPath(source))
                    && !pngPath.equals(HologramBlueprintArtifact.previewPath(target))) {
                    try {
                        Files.deleteIfExists(pngPath);
                    } catch (IOException cleanup) {
                        reasons.add(name + "：升级完成，旧预览清理失败：" + cleanup.getMessage());
                    }
                }
            } catch (IOException | RuntimeException failure) {
                requireCurrent(currentOperation);
                skipped++;
                reasons.add(name + "：" + failure.getMessage());
            }
            done++;
            progress.accept(done, jsonFiles.size());
        }
        return "旧蓝图升级：升级 " + upgraded
            + "，已最新 "
            + current
            + "，跳过 "
            + skipped
            + (reasons.isEmpty() ? "" : "；" + String.join("；", reasons));
    }

    private static void requireCurrent(java.util.function.BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw new IOException("蓝图升级已取消；已完成的完整蓝图仍然有效");
    }

    private static void adoptPortablePairs(Path root) throws IOException {
        Files.createDirectories(root);
        Properties mappings = index(root);
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName()
                    .toString();
                if (name.equals("hologram-presets.json") || name.matches("local_[a-fA-F0-9-]{36}\\.json")) continue;
                boolean known = false;
                for (String key : mappings.stringPropertyNames())
                    if (key.matches("local_[a-fA-F0-9-]{36}") && name.equals(mappings.getProperty(key))) known = true;
                if (known) continue;
                try {
                    HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact.readPair(file);
                    adopt(root, file, HologramBlueprintCompactCodec.parse(pair.json));
                    mappings = index(root);
                } catch (IOException | RuntimeException invalid) {
                    // Displaying the library cannot rewrite or remove an invalid transferred pair.
                }
            }
        }
    }

    private static synchronized String adopt(Path root, Path source, JsonObject document) throws IOException {
        String member = source.getFileName()
            .toString();
        Properties mappings = index(root);
        for (String key : mappings.stringPropertyNames())
            if (key.matches("local_[a-fA-F0-9-]{36}") && member.equals(mappings.getProperty(key))) return key;
        String basename = member.substring(0, member.length() - 5);
        if (!document.has("blueprint") || !document.get("blueprint")
            .isJsonObject()) throw new IOException("缺少独立蓝图容器");
        JsonObject blueprint = document.getAsJsonObject("blueprint");
        if (!blueprint.has("name") || !blueprint.has("cells")) throw new IOException("缺少名称或方块列表");
        String candidate = basename;
        if (!candidate.matches("local_[a-fA-F0-9-]{36}")) {
            String nativeId = blueprint.has("id") ? blueprint.get("id")
                .getAsString() : "";
            if (nativeId.matches("[a-fA-F0-9-]{36}")) candidate = "local_" + nativeId;
            else if (nativeId.matches("local_[a-fA-F0-9-]{36}")) candidate = nativeId;
            else candidate = "local_"
                + java.util.UUID.nameUUIDFromBytes(("gtmb-portable:" + basename).getBytes(StandardCharsets.UTF_8));
        }
        String existing = mappings.getProperty(candidate);
        Path legacy = child(root, candidate + ".json");
        if (existing != null && !member.equals(existing)
            || existing == null && !source.equals(legacy) && Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
            candidate = "local_"
                + java.util.UUID.nameUUIDFromBytes(("gtmb-portable:" + basename).getBytes(StandardCharsets.UTF_8));
            existing = mappings.getProperty(candidate);
            if (existing != null && !member.equals(existing)) throw new IOException("蓝图逻辑身份冲突");
        }
        validId(basename);
        mappings.setProperty(candidate, member);
        if (!basename.matches("(?:local_|public_)[a-fA-F0-9-]{36}")) mappings.setProperty(basename, member);
        writeIndex(root, mappings);
        return candidate;
    }

    static synchronized String logicalAlias(Path root, Path source, String fallback) throws IOException {
        for (String key : index(root).stringPropertyNames()) if (source.toAbsolutePath()
            .normalize()
            .equals(resolve(root, key))) return key;
        return fallback;
    }

    private static String safeName(String value) {
        StringBuilder name = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int character = value.codePointAt(offset);
            offset += Character.charCount(character);
            if (character < 32 || character == 127 || "<>:\"/\\|?*".indexOf(character) >= 0) character = '_';
            String part = new String(Character.toChars(character));
            int width = part.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > 80) break;
            name.append(part);
            bytes += width;
        }
        String result = name.toString()
            .trim()
            .replaceAll("[. ]+$", "");
        if (result.isEmpty()) result = "蓝图";
        if (result.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) result = "_" + result;
        return result;
    }

    private static void validId(String id) throws IOException {
        if (id == null || id.isEmpty()
            || id.length() > 255
            || id.equals(".")
            || id.equals("..")
            || id.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")) throw new IOException("Invalid blueprint logical ID");
    }

    private static Path child(Path root, String name) throws IOException {
        if (name == null || !name.endsWith(".json") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            throw new IOException("Invalid blueprint filename mapping");
        Path path = root.resolve(name)
            .normalize();
        if (!root.equals(path.getParent())) throw new IOException("Blueprint path outside its library");
        return path;
    }

    private static Properties index(Path directory) throws IOException {
        Properties result = new Properties();
        Path file = directory.resolve(INDEX);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_INDEX_BYTES)
                throw new IOException("Invalid blueprint filename index");
            try (InputStream input = Files.newInputStream(file)) {
                result.load(input);
            }
            if (result.size() > 4096) throw new IOException("Blueprint filename index entry budget");
            for (String key : result.stringPropertyNames()) {
                validId(key);
                child(
                    directory.toAbsolutePath()
                        .normalize(),
                    result.getProperty(key));
            }
        }
        return result;
    }

    static void writeIndex(Path root, Properties values) throws IOException {
        if (values.size() > 4096) throw new IOException("Blueprint filename index entry budget");
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, "gtmb-filenames-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "GTMB logical blueprint filenames");
            }
            if (Files.size(temporary) > MAX_INDEX_BYTES) throw new IOException("Blueprint filename index byte budget");
            move(temporary, root.resolve(INDEX));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] read(Path file, int limit) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("不是普通文件");
        return HologramBlueprintSharing.readBytes(file, limit);
    }

    private static String memberHashOrAbsent(Path file, int limit) throws IOException {
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? HologramBlueprintArtifact.hash(read(file, limit)) : null;
    }

    static void replace(Path file, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "gtmb-upgrade-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (!java.util.Arrays.equals(bytes, read(temporary, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES)))
                throw new IOException("升级回读校验失败");
            move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
