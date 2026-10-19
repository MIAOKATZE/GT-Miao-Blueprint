package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Client-local processing recipes. Content, source images, identities and workspace coordinates are never presets. */
public final class HologramAttachmentPresets {

    private static final String[] TEXT = { "font", "material", "metadata", "color", "pixelX", "pixelY", "thickness",
        "bold", "underline", "vertical", "yaw", "pitch", "roll", "arc", "bend", "twist", "shear" };
    private static final String[] PICTURE = { "keepAspect", "shortSide", "width", "height", "palette", "filter",
        "brightness", "contrast", "saturation", "backgroundTolerance", "removeBackground", "dither" };
    private static final String[] PICTURE_SIZE = { "pixelX", "pixelY", "thickness" };
    private static final int MAX_FILE = 512 * 1024, MAX_ENTRIES = 64;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private byte[] previous;

    public HologramAttachmentPresets(Path file) throws IOException {
        this.file = file.toAbsolutePath()
            .normalize();
        if (!Files.exists(this.file)) return;
        if (Files.isSymbolicLink(this.file) || Files.size(this.file) > MAX_FILE) throw new IOException("预设文件无效或过大");
        previous = Files.readAllBytes(this.file);
        NBTTagCompound root = HologramSnapshotCodec.decode(previous, 2 * MAX_FILE, 8L * MAX_FILE);
        if (root.getInteger("schema") != 1) throw new IOException("预设文件版本不受支持，原文件保留");
        NBTTagList rows = root.getTagList("presets", 10);
        if (rows.tagCount() > MAX_ENTRIES * 2) throw new IOException("处理预设数量超出上限");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            String id = row.getString("id"), name = checkedName(row.getString("name"));
            if (!id.matches("[a-f0-9-]{36}") || !ids.add(id)) throw new IOException("处理预设标识无效");
            entries.add(new Entry(id, name, row.getBoolean("picture"), row.getCompoundTag("settings")));
        }
    }

    public static final class Entry {

        public final String id;
        public String name;
        public final boolean picture;
        public final NBTTagCompound settings;
        public boolean deleted;

        Entry(String id, String name, boolean picture, NBTTagCompound settings) {
            this.id = id;
            this.name = name;
            this.picture = picture;
            this.settings = (NBTTagCompound) settings.copy();
        }
    }

    public List<Entry> rows(boolean picture) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) if (entry.picture == picture && !entry.deleted) result.add(entry);
        return result;
    }

    public boolean matches(boolean picture, NBTTagCompound settings) {
        return rows(picture).stream()
            .anyMatch(entry -> entry.settings.equals(settings));
    }

    public Entry save(boolean picture, String name, NBTTagCompound settings) throws IOException {
        String checked = checkedName(name);
        if (rows(picture).size() >= MAX_ENTRIES) throw new IOException("本类预设最多 64 项，请先删除不需要的预设");
        if (rows(picture).stream()
            .anyMatch(row -> row.name.equals(checked))) throw new IOException("预设名称已存在，请修改名称");
        Entry entry = new Entry(
            UUID.randomUUID()
                .toString(),
            checked,
            picture,
            settings);
        List<Entry> staged = new ArrayList<>(entries);
        staged.add(entry);
        persist(staged, null, null);
        entries.add(entry);
        return entry;
    }

    public void rename(Entry entry, String name) throws IOException {
        String checked = checkedName(name);
        if (!entries.contains(entry) || entry.deleted) throw new IOException("预设已删除，请重新打开列表");
        if (rows(entry.picture).stream()
            .anyMatch(row -> row != entry && row.name.equals(checked))) throw new IOException("预设名称已存在，请修改名称");
        persist(entries, entry, checked);
        entry.name = checked;
    }

    public void delete(Entry entry) throws IOException {
        List<Entry> staged = new ArrayList<>(entries);
        if (!staged.remove(entry)) throw new IOException("预设已删除");
        persist(staged, null, null);
        entries.remove(entry);
        entry.deleted = true;
    }

    private void persist(List<Entry> rows, Entry renamed, String name) throws IOException {
        if (Files.exists(file) && Files.size(file) > MAX_FILE) throw new IOException("预设文件已变化或超出预算");
        byte[] observed = Files.exists(file) ? Files.readAllBytes(file) : null;
        if (!java.util.Arrays.equals(previous, observed)) throw new IOException("预设文件已变化，请重新打开列表后再操作");
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("schema", 1);
        NBTTagList list = new NBTTagList();
        for (Entry entry : rows) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", entry.id);
            row.setString("name", entry == renamed ? name : entry.name);
            row.setBoolean("picture", entry.picture);
            row.setTag("settings", entry.settings.copy());
            list.appendTag(row);
        }
        root.setTag("presets", list);
        byte[] bytes = CompressedStreamTools.compress(root);
        if (bytes.length > MAX_FILE) throw new IOException("处理预设文件超出预算");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "gtmb-attachment-presets-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            previous = bytes;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String checkedName(String name) throws IOException {
        String result = name.trim();
        if (result.isEmpty() || result.length() > 48
            || result.chars()
                .anyMatch(Character::isISOControl))
            throw new IOException("预设名需要 1–48 个字符");
        return result;
    }

    private static void copy(NBTTagCompound source, NBTTagCompound target, String[] keys) {
        for (String key : keys) if (source.hasKey(key)) target.setTag(
            key,
            source.getTag(key)
                .copy());
    }

    public static NBTTagCompound settings(HologramTextBox value) {
        NBTTagCompound source = value.write(), result = new NBTTagCompound();
        copy(source, result, value.picture == null ? TEXT : PICTURE_SIZE);
        if (value.picture != null) {
            NBTTagCompound picture = new NBTTagCompound();
            copy(source.getCompoundTag("picture"), picture, PICTURE);
            if (value.picture.keepAspect) {
                picture.removeTag("width");
                picture.removeTag("height");
            }
            result.setTag("picture", picture);
        }
        return result;
    }

    public static HologramTextBox apply(HologramTextBox value, NBTTagCompound settings) throws IOException {
        if ((value.picture != null) != settings.hasKey("picture", 10)) throw new IOException("预设类型不匹配");
        NBTTagCompound merged = value.write();
        copy(settings, merged, value.picture == null ? TEXT : PICTURE_SIZE);
        if (value.picture != null) {
            NBTTagCompound picture = merged.getCompoundTag("picture");
            copy(settings.getCompoundTag("picture"), picture, PICTURE);
            if (picture.getBoolean("keepAspect")) {
                int shortSide = picture.getInteger("shortSide"), width = picture.getInteger("originalWidth"),
                    height = picture.getInteger("originalHeight");
                picture.setInteger(
                    "width",
                    width <= height ? shortSide : (int) Math.round(shortSide * (double) width / height));
                picture.setInteger(
                    "height",
                    width <= height ? (int) Math.round(shortSide * (double) height / width) : shortSide);
            }
        }
        if (value.picture == null) {
            // A new font/vertical layout invalidates the old glyph mask. The editor rasterizes its content on confirm.
            merged.setInteger("width", 1);
            merged.setInteger("height", 1);
            merged.setByteArray("bitmap", new byte[] { 1 });
            if (value.text.trim()
                .isEmpty()) merged.setString("text", "预设");
        }
        HologramTextBox result = HologramTextBox.read(merged);
        result.text = value.text;
        return result;
    }
}
