package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Client-local shortcuts, shared across worlds just like the remembered workspace tab. */
final class HologramConfigurationPresets {

    private final Path file;
    private NBTTagCompound data = new NBTTagCompound();

    HologramConfigurationPresets(File file) {
        this.file = file.toPath();
        if (!Files.isRegularFile(this.file)) return;
        try (InputStream input = Files.newInputStream(this.file)) {
            if (Files.size(this.file) <= 1024 * 1024) data = CompressedStreamTools.readCompressed(input);
        } catch (IOException | RuntimeException ignored) {
            // A damaged local preference cannot block construction or replace authoritative server state.
        }
    }

    List<String> names() {
        List<String> names = new ArrayList<>();
        NBTTagList rows = data.getTagList("hatches", 10);
        for (int i = 0; i < Math.min(64, rows.tagCount()); i++) names.add(
            rows.getCompoundTagAt(i)
                .getString("name"));
        return names;
    }

    NBTTagCompound get(String name) {
        NBTTagList rows = data.getTagList("hatches", 10);
        for (int i = 0; i < rows.tagCount(); i++) if (name.equals(
            rows.getCompoundTagAt(i)
                .getString("name")))
            return (NBTTagCompound) rows.getCompoundTagAt(i)
                .getCompoundTag("profile")
                .copy();
        return null;
    }

    void save(String name, NBTTagCompound profile, boolean overwrite) throws IOException {
        name = name.trim();
        if (name.isEmpty() || name.length() > 32) throw new IOException("名称须为1–32个字符");
        NBTTagList entries = profile.getTagList("entries", 10);
        if (entries.tagCount() == 0) throw new IOException("当前没有可保存的仓室目标，请先配置仓室");
        int machines = 0;
        for (int i = 0; i < entries.tagCount(); i++) {
            int count = entries.getCompoundTagAt(i)
                .getInteger("count");
            if (count < 1 || count > 512) throw new IOException("仓室搭配的数量无效或超出上限");
            machines += count;
        }
        if (entries.tagCount() > 128 || machines > 512) throw new IOException("每套搭配最多保存128种、512个仓室目标");
        NBTTagList rows = data.getTagList("hatches", 10);
        NBTTagCompound found = null;
        for (int i = 0; i < rows.tagCount(); i++) if (name.equals(
            rows.getCompoundTagAt(i)
                .getString("name")))
            found = rows.getCompoundTagAt(i);
        if (found != null && !overwrite) throw new IOException("此名称已存在；请选择该搭配后点击覆盖");
        if (found == null && rows.tagCount() >= 64) throw new IOException("最多保存64套仓室搭配");
        if (found == null) {
            found = new NBTTagCompound();
            found.setString("name", name);
            rows.appendTag(found);
        }
        found.setTag("profile", profile.copy());
        data.setTag("hatches", rows);
        persist();
    }

    void delete(String name) throws IOException {
        NBTTagList rows = data.getTagList("hatches", 10);
        for (int i = rows.tagCount() - 1; i >= 0; i--) if (name.equals(
            rows.getCompoundTagAt(i)
                .getString("name")))
            rows.removeTag(i);
        data.setTag("hatches", rows);
        persist();
    }

    NBTTagCompound last(boolean hatch) {
        String key = hatch ? "lastHatch" : "lastSources";
        return data.hasKey(key, 10) ? (NBTTagCompound) data.getCompoundTag(key)
            .copy() : null;
    }

    void accepted(boolean hatch, NBTTagCompound value) throws IOException {
        data.setTag(hatch ? "lastHatch" : "lastSources", value.copy());
        persist();
    }

    private void persist() throws IOException {
        Files.createDirectories(
            file.toAbsolutePath()
                .getParent());
        Path pending = file.resolveSibling(
            file.getFileName()
                .toString() + ".pending");
        try {
            try (OutputStream output = Files.newOutputStream(pending)) {
                CompressedStreamTools.writeCompressed(data, output);
            }
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    /** Tracks one explicit command; snapshots can acknowledge it but can never generate another command. */
    static final class Pending {

        private long sequence = -1;
        private String session = "";
        private boolean hatch;
        private NBTTagCompound value;

        void begin(String session, long sequence, boolean hatch, NBTTagCompound value) {
            this.session = session;
            this.sequence = sequence;
            this.hatch = hatch;
            this.value = (NBTTagCompound) value.copy();
        }

        void reject(long sequence) {
            if (this.sequence == sequence) this.sequence = -1;
        }

        void acknowledge(String session, NBTTagCompound snapshot, HologramConfigurationPresets store)
            throws IOException {
            if (sequence < 0 || !this.session.equals(session) || snapshot.getLong("uiSequence") < sequence) return;
            boolean accepted = snapshot.getLong("uiSequence") == sequence
                && snapshot.hasKey("configurationAcceptedSequence")
                && snapshot.getLong("configurationAcceptedSequence") == sequence;
            sequence = -1;
            if (accepted) store.accepted(hatch, value);
        }
    }
}
