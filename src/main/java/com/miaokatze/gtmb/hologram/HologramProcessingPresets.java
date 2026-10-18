package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Independent bounded NBT policy library. It stores choices, never device payloads or terminal secrets. */
final class HologramProcessingPresets {

    private static final Path FILE = Paths.get("config", "gtmb", "hologram-processing-presets.nbt");
    private static final int LIMIT = 64;
    private static List<Entry> cached;

    private HologramProcessingPresets() {}

    static final class Entry {

        String id, name;
        HologramBlueprintProcessing.Policy policy;

        NBTTagCompound write() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", id);
            tag.setString("name", name);
            tag.setTag("policy", policy.write());
            return tag;
        }
    }

    static synchronized List<Entry> all() throws IOException {
        if (cached == null) cached = read(FILE);
        return copies(cached);
    }

    static synchronized Entry get(String id) throws IOException {
        for (Entry entry : all()) if (entry.id.equals(id)) return entry;
        return null;
    }

    static synchronized Entry save(String name, HologramBlueprintProcessing.Policy policy) throws IOException {
        List<Entry> entries = all();
        if (entries.size() >= LIMIT) throw new IOException("最多保存64个处理流程预设");
        Entry entry = entry(
            UUID.randomUUID()
                .toString(),
            name,
            policy,
            true);
        entries.add(entry);
        write(FILE, entries);
        return copy(entry);
    }

    static synchronized Entry update(String id, String name, HologramBlueprintProcessing.Policy policy)
        throws IOException {
        List<Entry> entries = all();
        int index = index(entries, id);
        if (index < 0) throw new IOException("处理流程预设不存在");
        Entry entry = entry(id, name, policy, true);
        entries.set(index, entry);
        write(FILE, entries);
        return copy(entry);
    }

    static synchronized void delete(String id) throws IOException {
        List<Entry> entries = all();
        int index = index(entries, id);
        if (index < 0) throw new IOException("处理流程预设不存在");
        entries.remove(index);
        write(FILE, entries);
    }

    static synchronized NBTTagList labels() throws IOException {
        NBTTagList list = new NBTTagList();
        for (Entry entry : all()) list.appendTag(entry.write());
        return list;
    }

    private static Entry entry(String id, String name, HologramBlueprintProcessing.Policy policy, boolean complete)
        throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("处理流程预设标识无效");
        if (name == null || name.trim()
            .isEmpty()
            || name.length() > 64
            || name.chars()
                .anyMatch(c -> c < 32 || c == 167))
            throw new IOException("处理流程预设名称须为1至64个普通字符");
        if (policy == null) throw new IOException("处理流程预设缺少配置");
        HologramBlueprintProcessing.Policy detached = HologramBlueprintProcessing.Policy.read(policy.write());
        if (complete && !detached.complete()) throw new IOException("完成全部处理阶段后才能保存流程预设");
        // Loading a reusable flow always starts a fresh walk through its stages.
        detached.confirmedStages.clear();
        // Per-cell asset selections belong to their blueprint rather than reusable policy defaults.
        detached.cellNbtOverrides.clear();
        detached.resourceDecisions.clear();
        Entry entry = new Entry();
        entry.id = id;
        entry.name = name.trim();
        entry.policy = detached;
        return entry;
    }

    private static List<Entry> read(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(file)) return entries;
        if (Files.size(file) > HologramBlueprintNbt.MAX_COMPRESSED) throw new IOException("处理流程预设库超过1MiB上限");
        NBTTagCompound root = HologramBlueprintNbt.decodeData(
            Base64.getEncoder()
                .encodeToString(Files.readAllBytes(file)));
        if (root == null || !root.hasKey("version", 3) || root.getInteger("version") != 1 || !root.hasKey("presets", 9))
            throw new IOException("处理流程预设库格式无效");
        NBTTagList rows = root.getTagList("presets", 10);
        if (rows.tagCount() > LIMIT) throw new IOException("处理流程预设数量超出上限");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            Entry entry = entry(
                row.getString("id"),
                row.getString("name"),
                HologramBlueprintProcessing.Policy.read(row.getCompoundTag("policy")),
                false);
            if (!ids.add(entry.id)) throw new IOException("处理流程预设标识重复");
            entries.add(entry);
        }
        return entries;
    }

    private static void write(Path file, List<Entry> entries) throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", 1);
        NBTTagList list = new NBTTagList();
        for (Entry entry : entries) list.appendTag(entry.write());
        root.setTag("presets", list);
        byte[] bytes = Base64.getDecoder()
            .decode(HologramBlueprintNbt.encodeCanonical(root));
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "hologram-processing-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            cached = copies(entries);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static int index(List<Entry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(id)) return i;
        return -1;
    }

    private static Entry copy(Entry source) throws IOException {
        return entry(source.id, source.name, source.policy, false);
    }

    private static List<Entry> copies(List<Entry> entries) throws IOException {
        List<Entry> copies = new ArrayList<>();
        for (Entry entry : entries) copies.add(copy(entry));
        return copies;
    }
}
