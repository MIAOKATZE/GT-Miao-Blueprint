// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

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
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;

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
        NativeBlueprintPairStore.Receipt nativeEntry =
            id.startsWith("public_") ? null : NativeBlueprintPairStore.receipt(root, id);
        if (nativeEntry != null) return nativeEntry.source;
        String member = index(root).getProperty(id);
        Path selected = child(root, member == null ? id + ".json" : member);
        if (!id.startsWith("public_") && NativeBlueprintPairStore.inactive(root, selected))
            return child(root, ".gtmb-native-vacant-" +
                HologramBlueprintArtifact.hash(id.getBytes(StandardCharsets.UTF_8)) + ".json");
        return selected;
    }

    /** Keep all existing private names pointing at this exact source when format changes. */
    static synchronized String[] nativePublicationAliases(Path directory,Path source,String lookup) throws IOException {
        java.util.Set<String> result=new java.util.LinkedHashSet<>();
        result.add(lookup);
        String member=source.getFileName().toString();
        if(member.endsWith(".json")){
            java.util.Set<String> names=new java.util.LinkedHashSet<>();
            names.add(member.substring(0,member.length()-5));
            Properties old=index(directory);
            for(String key:old.stringPropertyNames())if(member.equals(old.getProperty(key)))names.add(key);
            for(String name:names){
                if(name.startsWith("public_")||name.equalsIgnoreCase("hologram-presets"))continue;
                try{validId(name);result.add(name);}catch(IOException invalid){/* No unsafe physical aliases. */}
            }
        }
        return result.toArray(new String[0]);
    }

    /** Existing durable mapping only; no portable catalogue adoption and no legacy filename fallback. */
    public static synchronized Path indexedPath(Path directory, String id) throws IOException {
        validId(id);
        Path root = directory.toAbsolutePath()
            .normalize();
        NativeBlueprintPairStore.Receipt nativeEntry =
            id.startsWith("public_") ? null : NativeBlueprintPairStore.receipt(root, id);
        if (nativeEntry != null) return nativeEntry.source;
        String member = index(root).getProperty(id);
        Path selected = member == null ? null : child(root, member);
        return selected != null && !id.startsWith("public_") && NativeBlueprintPairStore.inactive(root, selected)
            ? null : selected;
    }

    /** Pre-pair shares can be exported as exact JSON under their requested local name. */
    public static synchronized void exportHistoricShare(Path directory, String alias, byte[] json,
        java.util.function.BooleanSupplier current) throws IOException {
        Path target = resolve(directory, alias);
        if (Files.exists(HologramBlueprintArtifact.previewPath(target), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("同名文件已有预览，请换一个导出名称");
        requireCurrent(current);
        Files.createDirectories(target.getParent());
        replace(target, json);
    }

    /**
     * Explicit single named import, not a general recovery for a missing native PNG. Keep JSON-only sources JSON-only.
     */
    public static String upgradeNamedHistoric(Path directory, String alias, java.util.function.BooleanSupplier current)
        throws IOException {
        validId(alias);
        if (alias.equalsIgnoreCase("hologram-presets") || alias.matches("(?i)(?:local_|public_)[a-f0-9-]{36}")
            || alias.matches("(?i).+_[a-f0-9]{64}")) throw new IOException("请选择未纳入蓝图库的旧分享文件名");
        Path root = directory.toAbsolutePath()
            .normalize();
        Path source = child(root, alias + ".json");
        requireCurrent(current);
        int limit = HologramBlueprintArtifact.MAX_JSON_BYTES;
        byte[] original = read(source, limit);
        if (!HologramBlueprintPreviewBinding.pngSha256(original)
            .isEmpty()) throw new IOException("此蓝图需要原预览，不能按单 JSON 转换");
        JsonObject document = HologramBlueprintCompactCodec.storageDocument(original, limit);
        if (document.has("presets")) throw new IOException("此文件不是单张蓝图");
        JsonObject member = HologramBlueprintCompactCodec.documentBlueprint(document);
        if (member.has("nativeBlueprint") || document.has("owner") || document.has("uploadedBy"))
            throw new IOException("原生蓝图需要配对预览，已保留原文件");
        if (document.has("encoding")) return "蓝图已经紧凑化，无需转换";
        HologramBlueprintCompactCodec.requireRecompressibleEnvelope(document);
        if (member == document) {
            document = new JsonObject();
            document.addProperty("schema", 2);
            document.add("blueprint", member);
        }
        byte[] compact = HologramBlueprintCompactCodec.recompressExpanded(document, limit, current);
        String sourceHash = HologramBlueprintArtifact.hash(original);
        Map<Path, String> previews = historicPreviews(root, source, sourceHash, current);
        Path staged = Files.createTempFile(root, "gtmb-historic-", ".tmp");
        try {
            Files.write(staged, compact);
            if (!java.util.Arrays.equals(compact, read(staged, limit))) throw new IOException("升级回读校验失败");
            // Same monitor order as pair commit. Recheck source, all PNG identities, index and cancellation at publish.
            synchronized (HologramBlueprintArtifact.class) {
                synchronized (HologramBlueprintFiles.class) {
                    Properties mappings = index(root);
                    if (mappings.containsKey(alias) || mappings.containsValue(
                        source.getFileName()
                            .toString()))
                        throw new IOException("此蓝图已有管理映射，不能按单 JSON 转换");
                    if (!previews.equals(historicPreviews(root, source, null, current))
                        || !java.util.Arrays.equals(original, read(source, limit)))
                        throw new IOException("蓝图或预览在转换期间已变更");
                    requireCurrent(current);
                    Files.move(staged, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        return "旧分享蓝图已紧凑化，保留原文件名且未生成预览";
    }

    private static Map<Path, String> historicPreviews(Path root, Path source, String sourceHash,
        java.util.function.BooleanSupplier current) throws IOException {
        Map<Path, String> entries = new LinkedHashMap<>();
        Path sibling = HologramBlueprintArtifact.previewPath(source);
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.png")) {
            for (Path path : files) {
                requireCurrent(current);
                if (entries.size() >= 4096) throw new IOException("蓝图升级目录超过4096组文件上限");
                if (sibling.equals(path)) throw new IOException("已有同名预览，请使用成对升级");
                java.nio.file.attribute.BasicFileAttributes stat = Files
                    .readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!stat.isRegularFile()) throw new IOException("无法确认预览文件来源");
                entries.put(path, stat.fileKey() + ":" + stat.size() + ":" + stat.lastModifiedTime());
                if (sourceHash != null && HologramBlueprintArtifact
                    .previewReferences(sourceHash, read(path, HologramBlueprintArtifact.MAX_PNG_BYTES)))
                    throw new IOException("找到原图预览，请使用成对升级");
            }
        }
        return entries;
    }

    /** Remove only the selected row, preserving every other field and member of the historic file. */
    public static void deleteVisibleHistoricMember(Path path,String id,java.util.Set<String> mirrors)throws IOException {
        deleteHistoricMember(path,id,read(path,HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES),mirrors);
    }

    static void deleteHistoricMember(Path path, String memberId, byte[] original) throws IOException {
        deleteHistoricMember(path,memberId,original,java.util.Collections.emptySet());
    }

    private static void deleteHistoricMember(Path path,String memberId,byte[] original,java.util.Set<String> mirrors)throws IOException {
        int limit = HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES;
        var source = HologramBlueprintCompactCodec.parseStored(original, limit);
        JsonObject catalog = source.root();
        com.google.gson.JsonArray remaining = new com.google.gson.JsonArray();
        boolean removed = false;
        for (com.google.gson.JsonElement row : catalog.getAsJsonArray("presets")) {
            JsonObject member = row.getAsJsonObject();
            if (!removed && member.has("id")
                && memberId.equals(member.get("id").getAsString())
                && !com.miaokatze.gtmb.hologram.gtnb.LegacyBlueprintMirror.hidden(catalog,member,memberId,mirrors))
                removed = true;
            else remaining.add(row);
        }
        if (!removed) throw new IOException("本地旧合集蓝图不存在");
        catalog.add("presets", remaining);
        byte[] remainingJson = source.compact()
            ? HologramBlueprintCompactCodec.recompressExpanded(catalog, limit, () -> true)
            : HologramBlueprintSharing.encodeBounded(
                new com.google.gson.GsonBuilder().serializeNulls()
                    .create(),
                catalog,
                limit);
        HologramPresets.replaceCompressedCatalog(path, original, remainingJson);
    }

    public static Map<String, Path> managed(Path directory) throws IOException {
        adoptPortablePairs(
            directory.toAbsolutePath()
                .normalize());
        return managed(directory, "local_");
    }

    /** Ordinary catalog reads enumerate candidates without adopting or validating artifact pairs. */
    public static synchronized Map<String, Path> displayCandidates(Path directory) throws IOException {
        Path root = directory.toAbsolutePath()
            .normalize();
        Map<String, Path> result = managed(root, "local_");
        java.util.Set<Path> known = new java.util.HashSet<>(result.values());
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json")) {
            for (Path path : files) {
                String name = path.getFileName()
                    .toString();
                if (name.equals("hologram-presets.json") || known.contains(path)
                    || NativeBlueprintPairStore.inactive(root, path)
                    || name.matches("public_[a-fA-F0-9-]{36}\\.json")
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(HologramBlueprintArtifact.previewPath(path), LinkOption.NOFOLLOW_LINKS))
                    continue;
                result.put(name.substring(0, name.length() - 5), path);
            }
        }
        return result;
    }

    /** Mirrors portable adoption identity rules, while leaving the persistent index untouched. */
    public static synchronized String displayId(Path directory, Path source, String nativeId) throws IOException {
        Path root = directory.toAbsolutePath()
            .normalize();
        return displayId(root, source, nativeId, index(root));
    }

    private static String displayId(Path root, Path source, String nativeId, Properties mappings) throws IOException {
        String member = source.getFileName()
            .toString();
        for (Map.Entry<String, Path> entry : NativeBlueprintPairStore.active(root).entrySet())
            if (source.equals(entry.getValue())) return entry.getKey();
        for (String key : mappings.stringPropertyNames())
            if (key.matches("local_[a-fA-F0-9-]{36}") && member.equals(mappings.getProperty(key))) return key;
        String basename = member.substring(0, member.length() - 5);
        String candidate = basename;
        if (!candidate.matches("local_[a-fA-F0-9-]{36}")) {
            if (nativeId.matches("[a-fA-F0-9-]{36}")) candidate = "local_" + nativeId;
            else if (nativeId.matches("local_[a-fA-F0-9-]{36}")) candidate = nativeId;
            else candidate = "local_"
                + java.util.UUID.nameUUIDFromBytes(("gtmb-portable:" + basename).getBytes(StandardCharsets.UTF_8));
        }
        String existing = mappings.getProperty(candidate);
        Path legacy = child(root, candidate + ".json");
        if (NativeBlueprintPairStore.receipt(root, candidate) != null || existing != null && !member.equals(existing)
            || existing == null && !source.equals(legacy) && Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
            candidate = "local_"
                + java.util.UUID.nameUUIDFromBytes(("gtmb-portable:" + basename).getBytes(StandardCharsets.UTF_8));
            existing = mappings.getProperty(candidate);
            if (NativeBlueprintPairStore.receipt(root, candidate) != null || existing != null && !member.equals(existing)
                || !source.equals(child(root,candidate+".json")) && Files.exists(child(root,candidate+".json"),LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Blueprint logical identity conflict");
        }
        return candidate;
    }

    public static synchronized Map<String, Path> managedPublic(Path directory) throws IOException {
        return managed(directory, "public_");
    }

    private static synchronized Map<String, Path> managed(Path directory, String prefix) throws IOException {
        Path root = directory.toAbsolutePath()
            .normalize();
        Files.createDirectories(root);
        Map<String, Path> result = new LinkedHashMap<>();
        if ("local_".equals(prefix)) for (Map.Entry<String, Path> nativeEntry : NativeBlueprintPairStore.active(root).entrySet())
            if (nativeEntry.getKey().matches(prefix + "[a-fA-F0-9-]{36}")) result.put(nativeEntry.getKey(), nativeEntry.getValue());
        Properties index = index(root);
        for (String id : index.stringPropertyNames()) if (id.matches(prefix + "[a-fA-F0-9-]{36}")) {
            Path path = child(root, index.getProperty(id));
            if (result.containsKey(id) || NativeBlueprintPairStore.inactive(root, path)) continue;
            if (result.containsValue(path)) throw new IOException("Duplicate blueprint filename mapping");
            result.put(id, path);
        }
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(root, prefix + "*.json")) {
            for (Path file : files) {
                String name = file.getFileName()
                    .toString();
                String id = name.substring(0, name.length() - 5);
                if (id.matches(prefix + "[a-fA-F0-9-]{36}")
                    && !NativeBlueprintPairStore.inactive(root, file)) result.putIfAbsent(id, file);
            }
        }
        return result;
    }

    public static synchronized Path target(Path directory, String id, byte[] json) throws IOException {
        return target(directory, id, json, null);
    }

    /** Client-owned pair identity; large files use an exact-byte filename digest, never an expanded semantic hash. */
    public static synchronized Path targetLocal(Path directory, String id, byte[] json) throws IOException {
        return target(directory, id, json, null, true);
    }

    private static synchronized Path target(Path directory, String id, byte[] json, Path upgradeSource)
        throws IOException {
        return target(directory, id, json, upgradeSource, false);
    }

    private static synchronized Path target(Path directory, String id, byte[] json, Path upgradeSource, boolean local)
        throws IOException {
        validId(id);
        Path root = directory.toAbsolutePath()
            .normalize();
        JsonObject document;
        boolean exactLocalIdentity = false;
        try {
            document = HologramBlueprintCompactCodec.parse(json);
        } catch (IOException strictBudget) {
            if (!local) throw strictBudget;
            document = HologramBlueprintCompactCodec.localMetadata(json);
            exactLocalIdentity = true;
        }
        JsonObject blueprint = document.has("blueprint") ? document.getAsJsonObject("blueprint") : document;
        String name = blueprint.has("name") ? blueprint.get("name")
            .getAsString() : "蓝图";
        String hash = exactLocalIdentity
            ? HologramBlueprintArtifact.hash(json) : HologramBlueprintContentHash.ofExpandedTarget(blueprint);
        String stem = safeName(name) + "_" + hash;
        Path target = child(root, stem + ".json");
        Properties index = index(root);
        String current = index.getProperty(
            id,
            upgradeSource == null ? id + ".json"
                : upgradeSource.getFileName()
                    .toString());
        boolean occupied = false;
        for (String key : index.stringPropertyNames()) if (!key.equals(id) && target.getFileName()
            .toString()
            .equals(index.getProperty(key))) occupied = true;
        if (local && NativeBlueprintPairStore.inactive(root, target) || occupied && !target.getFileName()
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
            if (local && NativeBlueprintPairStore.inactive(root, target))
                throw new IOException("A retained blueprint original owns this filename");
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
        track(directory, id, receipt, publish, null, null, null, null);
    }

    private static void track(Path directory, String id, HologramBlueprintArtifact.PairWrite receipt,
        IndexWriteOperation publish, Path upgradeSource, String sourceJsonHash, String sourcePngHash, Path detachedPng)
        throws IOException {
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
        Path old = upgradeSource == null ? (previous == null ? child(root, id + ".json") : child(root, previous))
            : upgradeSource;
        if (upgradeSource != null && previous != null && !old.equals(child(root, previous)))
            throw new IOException("升级期间蓝图映射已变更");
        boolean retained = NativeBlueprintPairStore.inactive(root, old);
        if(retained&&upgradeSource!=null)throw new IOException("原生蓝图的旧原件已留档，不能就地修改");
        String oldJsonHash = upgradeSource != null ? sourceJsonHash
            : old.equals(target) ? null : memberHashOrAbsent(old, HologramBlueprintLimits.LOCAL_FILE_BYTES);
        String oldPngHash = upgradeSource != null ? (detachedPng == null ? sourcePngHash : null)
            : old.equals(target) ? null
                : memberHashOrAbsent(
                    HologramBlueprintArtifact.previewPath(old),
                    HologramBlueprintArtifact.MAX_PNG_BYTES);
        String member = target.getFileName()
            .toString();
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(id, previous);
        if (upgradeSource != null) {
            String sourceName = old.getFileName()
                .toString();
            String alias = sourceName.substring(0, sourceName.length() - 5);
            validId(alias);
            if (!alias.matches("(?:local_|public_)[a-fA-F0-9-]{36}")) {
                String existing = before.getProperty(alias);
                if (existing != null && !sourceName.equals(existing)) throw new IOException("蓝图原文件名与已有别名冲突");
                changes.put(alias, existing);
            }
        }
        for (String key : before.stringPropertyNames()) if (!retained && old.getFileName()
            .toString()
            .equals(before.getProperty(key))) changes.put(key, before.getProperty(key));
        // Install recovery first, without holding the filename-index monitor while acquiring a receipt lock.
        boolean[] publishing = { false };
        receipt.onCompletion(() -> {
            if (!retained && !old.equals(target)) {
                synchronized (HologramBlueprintFiles.class) {
                    if (!member.equals(index(root).getProperty(id))) throw new IOException("蓝图映射已由较新的保存替换，保留旧文件");
                }
                if (!java.util.Objects
                    .equals(oldJsonHash, memberHashOrAbsent(old, HologramBlueprintLimits.LOCAL_FILE_BYTES))
                    || !java.util.Objects.equals(
                        oldPngHash,
                        memberHashOrAbsent(
                            HologramBlueprintArtifact.previewPath(old),
                            HologramBlueprintArtifact.MAX_PNG_BYTES)))
                    throw new IOException("旧蓝图在保存期间已变化，保留旧文件");
                HologramBlueprintArtifact.deletePair(old);
            }
            if (detachedPng != null && !detachedPng.equals(HologramBlueprintArtifact.previewPath(target))) {
                if (!sourcePngHash.equals(memberHashOrAbsent(detachedPng, HologramBlueprintArtifact.MAX_PNG_BYTES)))
                    throw new IOException("原预览在升级期间已变更");
                Files.deleteIfExists(detachedPng);
            }
        }, () -> {
            synchronized (HologramBlueprintFiles.class) {
                if (!publishing[0]) return;
                Properties actual = index(root);
                boolean updated = false;
                for (Map.Entry<String, String> change : changes.entrySet()) {
                    if (!member.equals(actual.getProperty(change.getKey()))) continue;
                    if (change.getValue() == null) actual.remove(change.getKey());
                    else if (member.equals(change.getValue())) continue;
                    else actual.setProperty(change.getKey(), change.getValue());
                    updated = true;
                }
                if (updated) writeIndex(root, actual);
            }
        });
        synchronized (HologramBlueprintFiles.class) {
            if(!id.startsWith("public_")&&NativeBlueprintPairStore.receipt(root,id)!=null)
                throw new IOException("保存位置已切换为原生蓝图，不能提交旧格式");
            Properties actual = index(root);
            for (Map.Entry<String, String> change : changes.entrySet())
                if (!java.util.Objects.equals(change.getValue(), actual.getProperty(change.getKey())))
                    throw new IOException("Blueprint filename mapping changed during save");
            for (String key : changes.keySet()) actual.setProperty(key, member);
            publishing[0] = true;
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
        delete(directory,id,null);
    }

    public static void deleteLegacySelection(Path directory,String id,Path expected) throws IOException {
        if(expected==null||!expected.getFileName().toString().endsWith(".json"))
            throw new IOException("Invalid legacy delete selection");
        delete(directory,id,expected);
    }

    private static void delete(Path directory, String id, Path legacySelection) throws IOException {
        Path root = directory.toAbsolutePath()
            .normalize();
        synchronized (HologramBlueprintArtifact.class) {
            synchronized (HologramBlueprintFiles.class) {
                NativeBlueprintPairStore.Receipt owned = id.startsWith("public_")
                    ? null : NativeBlueprintPairStore.receipt(root,id);
                if(owned!=null){
                    if(legacySelection!=null)throw new IOException("删除前蓝图格式已变化，请刷新");
                    HologramNativeBlueprintStorage.delete(root,owned,stage -> {});
                    return;
                }
                Path file = resolve(root, id);
                if(legacySelection!=null&&!legacySelection.equals(file))
                    throw new IOException("删除前蓝图位置已变化，请刷新");
                Properties actual = index(root);
                Map<String, String> removed = new LinkedHashMap<>();
                for (String key : actual.stringPropertyNames()) if (file.getFileName()
                    .toString()
                    .equals(actual.getProperty(key))) removed.put(key, actual.getProperty(key));
                for (String key : removed.keySet()) actual.remove(key);
                writeIndex(root, actual);
                try {
                    HologramBlueprintArtifact.deletePair(file);
                } catch (IOException | RuntimeException failure) {
                    try {
                        for (Map.Entry<String, String> previous : removed.entrySet())
                            actual.setProperty(previous.getKey(), previous.getValue());
                        if (!removed.isEmpty()) writeIndex(root, actual);
                    } catch (IOException restore) {
                        failure.addSuppressed(restore);
                    }
                    throw failure;
                }
            }
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
                if (name.endsWith(".json")&&!NativeBlueprintPairStore.inactive(root,file)) jsonFiles.add(file);
                else if (name.endsWith(".png")&&!NativeBlueprintPairStore.inactive(root,previewOwner(file))
                    &&!Files.exists(root.resolve(name.substring(0,name.length()-4)+".gtnb"),LinkOption.NOFOLLOW_LINKS))
                    pngFiles.add(file);
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
                    byte[] compact = HologramBlueprintUpgrade
                        .migrate(old, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES, currentOperation);
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
                UpgradeSource original = readUpgradeSource(root, source);
                byte[] old = original.json;
                byte[] detached = HologramBlueprintPreviewBinding.sourceJson(old);
                byte[] compact = HologramBlueprintUpgrade
                    .migrate(detached, HologramBlueprintArtifact.MAX_JSON_BYTES, currentOperation);
                JsonObject blueprint = HologramBlueprintCompactCodec.displayRows(compact).blueprint;
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
                            if (Files.exists(previewOwner(candidate), LinkOption.NOFOLLOW_LINKS))
                                throw new IOException("摘要匹配的PNG已有同名JSON，保留原文件");
                            if (match != null) throw new IOException("存在多个摘要匹配的PNG");
                            match = candidate;
                            png = candidateBytes;
                        } catch (IOException invalidPng) {
                            if ("存在多个摘要匹配的PNG".equals(invalidPng.getMessage())
                                || "摘要匹配的PNG已有同名JSON，保留原文件".equals(invalidPng.getMessage())) throw invalidPng;
                        }
                    }
                    if (match == null) throw new IOException("缺少同名或唯一摘要匹配PNG");
                    pngPath = match;
                }
                requireCurrent(currentOperation);
                if (compact == detached && pngPath.equals(HologramBlueprintArtifact.previewPath(source))) {
                    current++;
                    progress.accept(++done, jsonFiles.size());
                    continue;
                }
                // Do not adopt before we have an accepted pair; a rejected item must leave no new alias.
                String nativeId = blueprint.has("id") ? blueprint.get("id")
                    .getAsString() : "";
                String id = displayId(root, source, nativeId, original.mappings);
                progress.accept(done, jsonFiles.size());
                byte[] bound;
                if (!HologramBlueprintPreviewBinding.pngSha256(old)
                    .isEmpty()) {
                    HologramBlueprintArtifact.Artifact migrated = HologramBlueprintArtifact.prepareSave(compact, png);
                    compact = migrated.json;
                    bound = migrated.png;
                } else bound = HologramBlueprintArtifact.bindPreview(compact, png);
                Path target = target(root, id, compact, source);
                HologramBlueprintArtifact.PairWrite receipt = commitUpgradePair(
                    root,
                    id,
                    nativeId,
                    source,
                    pngPath,
                    target,
                    original,
                    png,
                    compact,
                    bound,
                    currentOperation);
                if (!receipt.cleanupWarning()
                    .isEmpty()) reasons.add(name + "：升级完成，旧文件清理待重试：" + receipt.cleanupWarning());
                upgraded++;
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

    private static final class UpgradeSource {

        final byte[] json;
        final Properties mappings;

        UpgradeSource(byte[] json, Properties mappings) {
            this.json = json;
            this.mappings = mappings;
        }
    }

    private static UpgradeSource readUpgradeSource(Path root, Path source) throws IOException {
        synchronized (HologramBlueprintArtifact.class) {
            synchronized (HologramBlueprintFiles.class) {
                if (NativeBlueprintPairStore.inactive(root,source))throw new IOException("原生蓝图的旧原件已留档");
                if (HologramBlueprintArtifact.hasPendingWrite(source)) throw new IOException("蓝图仍有待确认的保存");
                return new UpgradeSource(read(source, HologramBlueprintArtifact.MAX_JSON_BYTES), index(root));
            }
        }
    }

    /** Include every old alias as well as reserved source/target slots; an unrelated mapping does not block upgrade. */
    private static Map<String, String> upgradeMappings(Properties mappings, String id, Path source, Path target) {
        String before = source.getFileName()
            .toString(),
            after = target.getFileName()
                .toString();
        Map<String, String> relevant = new LinkedHashMap<>();
        relevant.put(id, mappings.getProperty(id));
        String alias = before.substring(0, before.length() - 5);
        relevant.put(alias, mappings.getProperty(alias));
        for (String key : mappings.stringPropertyNames()) {
            String value = mappings.getProperty(key);
            if (before.equals(value) || after.equals(value)) relevant.put(key, value);
        }
        return relevant;
    }

    /** Hold both monitors only for final CAS/readback/index publication, never for compression. */
    private static HologramBlueprintArtifact.PairWrite commitUpgradePair(Path root, String id, String nativeId,
        Path source, Path pngPath, Path target, UpgradeSource original, byte[] png, byte[] compact, byte[] bound,
        java.util.function.BooleanSupplier current) throws IOException {
        synchronized (HologramBlueprintArtifact.class) {
            synchronized (HologramBlueprintFiles.class) {
                Properties live = index(root);
                if (!upgradeMappings(original.mappings, id, source, target)
                    .equals(upgradeMappings(live, id, source, target))
                    || !id.equals(displayId(root, source, nativeId, live))) throw new IOException("升级期间蓝图映射已变更");
                if (HologramBlueprintArtifact.hasPendingWrite(source)
                    || HologramBlueprintArtifact.hasPendingWrite(target)) throw new IOException("蓝图仍有待确认的保存");
                byte[] old = original.json;
                if (!java.util.Arrays.equals(old, read(source, HologramBlueprintArtifact.MAX_JSON_BYTES))
                    || !java.util.Arrays.equals(png, read(pngPath, HologramBlueprintArtifact.MAX_PNG_BYTES)))
                    throw new IOException("蓝图或预览在升级期间已变更");
                boolean sibling = pngPath.equals(HologramBlueprintArtifact.previewPath(source));
                if (!sibling && (Files.exists(HologramBlueprintArtifact.previewPath(source), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(previewOwner(pngPath), LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("蓝图预览的配对关系在升级期间已变更");
                if (!source.equals(target) && (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(HologramBlueprintArtifact.previewPath(target), LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("升级目标已有文件");
                HologramBlueprintArtifact.PairWrite receipt = HologramBlueprintArtifact
                    .writePairPending(target, compact, bound, current);
                try {
                    HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.readPair(target);
                    if (!java.util.Arrays.equals(actual.json, compact) || !java.util.Arrays.equals(actual.png, bound))
                        throw new IOException("升级回读校验失败");
                    requireCurrent(current);
                    track(
                        root,
                        id,
                        receipt,
                        HologramBlueprintFiles::writeIndex,
                        source,
                        HologramBlueprintArtifact.hash(old),
                        HologramBlueprintArtifact.hash(png),
                        sibling ? null : pngPath);
                } catch (IOException | RuntimeException failure) {
                    try {
                        receipt.rollback();
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
                receipt.commit();
                return receipt;
            }
        }
    }

    private static Path previewOwner(Path png) {
        String name = png.getFileName()
            .toString();
        return png.resolveSibling(name.substring(0, name.length() - 4) + ".json");
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
                if (name.equals("hologram-presets.json") || name.matches("local_[a-fA-F0-9-]{36}\\.json")
                    || NativeBlueprintPairStore.inactive(root, file)) continue;
                boolean known = false;
                for (String key : mappings.stringPropertyNames())
                    if (key.matches("local_[a-fA-F0-9-]{36}") && name.equals(mappings.getProperty(key))) known = true;
                if (known) continue;
                try {
                    HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact.readLocalPair(file);
                    adopt(root, file, HologramBlueprintCompactCodec.localMetadata(pair.json));
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
        if (NativeBlueprintPairStore.receipt(root, candidate) != null || existing != null && !member.equals(existing)
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
        return NativeBlueprintPairStore.portableStem(value);
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
