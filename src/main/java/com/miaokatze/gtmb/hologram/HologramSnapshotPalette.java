package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Snapshot-only exact descriptors. References never contain or invoke game objects. */
public final class HologramSnapshotPalette {

    public static final int VERSION = 1;
    public static final String VERSION_KEY = "snapshotPaletteVersion";
    public static final String PALETTE_KEY = "snapshotPalette";
    public static final String REFERENCE_KEY = "snapshotDescriptor";
    private static final int MAX_DESCRIPTORS = 65536;
    private static final long MAX_DESCRIPTOR_BYTES = 16L * 1024 * 1024;
    private static final int RAW_BYTES = 128 * 1024 * 1024;
    private static final long NBT_BYTES = 512L * 1024 * 1024;

    private HologramSnapshotPalette() {}

    /** Wire provenance, rather than coincidentally equal legacy extension field names. */
    public static boolean isPacked(NBTTagCompound root) {
        return root instanceof Packed;
    }

    public static boolean metadataKey(String key) {
        return VERSION_KEY.equals(key) || PALETTE_KEY.equals(key);
    }

    public static boolean coordinateKey(String key) {
        return "x".equals(key) || "y"
            .equals(key) || "z".equals(key) || "dx".equals(key) || "dy".equals(key) || "dz".equals(key);
    }

    /** The decoder has already validated every reference. Values are shared read-only by the client. */
    public static NBTTagCompound descriptor(NBTTagCompound root, NBTTagCompound row) {
        return isPacked(root) ? root.getTagList(PALETTE_KEY, 10)
            .getCompoundTagAt(row.getInteger(REFERENCE_KEY)) : row;
    }

    /** Frozen input remains intact for the owner's later baseline callback. Nested tags are only read. */
    static NBTTagCompound pack(NBTTagCompound source, BooleanSupplier current) throws IOException {
        return pack(source, current, MAX_DESCRIPTORS, MAX_DESCRIPTOR_BYTES);
    }

    static NBTTagCompound pack(NBTTagCompound source, BooleanSupplier current, int maxDescriptors, long dictionaryBytes)
        throws IOException {
        if (!source.hasKey("cells", 9) || source.getBoolean("partial")
            || "cellDelta".equals(source.getString("snapshotKind"))
            || source.getBoolean("areaSelectionOnly")
            || source.hasKey(VERSION_KEY)
            || source.hasKey(PALETTE_KEY)) return source;
        NBTTagList rows = source.getTagList("cells", 10);
        if (rows.tagCount() < 2 || rows.tagCount() > HologramBlueprintLimits.MAX_CELLS) return source;
        Packed result = new Packed();
        for (Object name : source.func_150296_c()) {
            String key = (String) name;
            if (!"cells".equals(key)) result.setTag(key, source.getTag(key));
        }
        Size logical = baseline(result, current);
        Map<NBTTagCompound, Integer> indices = new HashMap<>();
        List<Size> sizes = new ArrayList<>();
        NBTTagList palette = new NBTTagList(), positions = new NBTTagList();
        long retainedBytes = 0;
        for (int i = 0; i < rows.tagCount(); i++) {
            checkpoint(current);
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.hasKey(REFERENCE_KEY)) return source;
            NBTTagCompound descriptor = new NBTTagCompound(), position = new NBTTagCompound();
            for (Object name : row.func_150296_c()) {
                String key = (String) name;
                (coordinateKey(key) ? position : descriptor).setTag(key, row.getTag(key));
            }
            Integer index = indices.get(descriptor);
            if (index == null) {
                Size size = measure(descriptor, 2, current);
                if (indices.size() >= maxDescriptors || size.bytes > dictionaryBytes - retainedBytes) return source;
                index = indices.size();
                indices.put(descriptor, index);
                sizes.add(size);
                palette.appendTag(descriptor);
                retainedBytes += size.bytes;
            }
            Size positionSize = measure(position, 2, current);
            logical.add(sizes.get(index));
            logical.add(positionSize);
            logical.bytes--; // Merge the two compound payloads, retaining only one end marker/allocation.
            logical.tags--;
            logical.check(RAW_BYTES, NBT_BYTES);
            position.setInteger(REFERENCE_KEY, index);
            positions.appendTag(position);
        }
        result.setInteger(VERSION_KEY, VERSION);
        result.setTag(PALETTE_KEY, palette);
        result.setTag("cells", positions);
        // Unique or tiny rows need not pay an envelope/dictionary overhead.
        return 3 + measure(result, 0, current).bytes < logical.bytes ? result : source;
    }

    static NBTTagCompound admit(NBTTagCompound root, int rawLimit, long nbtLimit) throws IOException {
        Packed result = new Packed();
        for (Object name : root.func_150296_c()) {
            String key = (String) name;
            result.setTag(key, root.getTag(key));
        }
        validate(result, rawLimit, nbtLimit);
        return result;
    }

    public static void validate(NBTTagCompound root) throws IOException {
        if (isPacked(root)) validate(root, RAW_BYTES, NBT_BYTES);
    }

    private static void validate(NBTTagCompound root, int rawLimit, long nbtLimit) throws IOException {
        if (!root.hasKey(VERSION_KEY, 3) || root.getInteger(VERSION_KEY) != VERSION
            || !compoundList(root, PALETTE_KEY)
            || !compoundList(root, "cells")
            || root.getBoolean("partial")) throw new IOException("snapshot palette envelope");
        NBTTagList palette = root.getTagList(PALETTE_KEY, 10), rows = root.getTagList("cells", 10);
        if (palette.tagCount() > MAX_DESCRIPTORS || rows.tagCount() > HologramBlueprintLimits.MAX_CELLS)
            throw new IOException("snapshot palette count budget");
        List<Size> sizes = new ArrayList<>();
        long dictionaryBytes = 0;
        for (int i = 0; i < palette.tagCount(); i++) {
            NBTTagCompound descriptor = palette.getCompoundTagAt(i);
            for (Object key : descriptor.func_150296_c()) if (coordinateKey((String) key) || REFERENCE_KEY.equals(key))
                throw new IOException("snapshot palette descriptor field");
            Size size = measure(descriptor, 2, () -> true);
            dictionaryBytes += size.bytes;
            if (dictionaryBytes > MAX_DESCRIPTOR_BYTES) throw new IOException("snapshot palette dictionary budget");
            sizes.add(size);
        }
        NBTTagCompound metadata = new NBTTagCompound();
        for (Object name : root.func_150296_c()) {
            String key = (String) name;
            if (!metadataKey(key) && !"cells".equals(key)) metadata.setTag(key, root.getTag(key));
        }
        Size logical = baseline(metadata, () -> true);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (!row.hasKey(REFERENCE_KEY, 3)) throw new IOException("snapshot palette reference type");
            int index = row.getInteger(REFERENCE_KEY);
            if (index < 0 || index >= sizes.size()) throw new IOException("snapshot palette reference range");
            Size position = new Size(1, 1);
            for (Object name : row.func_150296_c()) {
                String key = (String) name;
                if (REFERENCE_KEY.equals(key)) continue;
                if (!coordinateKey(key)) throw new IOException("snapshot palette position field");
                position.add(measure(row.getTag(key), 3, () -> true));
                position.bytes += 1 + utf(key);
            }
            logical.add(sizes.get(index));
            logical.add(position);
            logical.bytes--;
            logical.tags--;
            logical.check(rawLimit, nbtLimit);
        }
        logical.check(rawLimit, nbtLimit);
    }

    private static boolean compoundList(NBTTagCompound root, String key) {
        if (!root.hasKey(key, 9)) return false;
        NBTTagList rows = (NBTTagList) root.getTag(key);
        return rows.tagCount() == 0 || rows.func_150303_d() == 10;
    }

    private static Size baseline(NBTTagCompound metadata, BooleanSupplier current) throws IOException {
        Size size = measure(metadata, 0, current);
        size.bytes += 3 + 1 + utf("cells") + 5; // Named root and named compound-list header.
        size.tags++; // The cells list itself; row compounds are counted below.
        return size;
    }

    private static Size measure(NBTBase value, int depth, BooleanSupplier current) throws IOException {
        checkpoint(current);
        if (depth > 512) throw new IOException("snapshot NBT depth");
        Size size = new Size(0, 1);
        switch (value.getId()) {
            case 1:
                size.bytes = 1;
                break;
            case 2:
                size.bytes = 2;
                break;
            case 3:
            case 5:
                size.bytes = 4;
                break;
            case 4:
            case 6:
                size.bytes = 8;
                break;
            case 7:
                size.bytes = 4L + ((NBTTagByteArray) value).func_150292_c().length;
                break;
            case 8:
                size.bytes = utf(((NBTTagString) value).func_150285_a_());
                break;
            case 9:
                NBTTagList list = (NBTTagList) value;
                size.bytes = 5;
                for (NBTBase entry : HologramBlueprintNbt.listEntries(list))
                    size.add(measure(entry, depth + 1, current));
                break;
            case 10:
                NBTTagCompound compound = (NBTTagCompound) value;
                size.bytes = 1;
                for (Object name : compound.func_150296_c()) {
                    String key = (String) name;
                    size.add(measure(compound.getTag(key), depth + 1, current));
                    size.bytes += 1 + utf(key);
                }
                break;
            case 11:
                size.bytes = 4L + 4L * ((NBTTagIntArray) value).func_150302_c().length;
                break;
            default:
                throw new IOException("snapshot NBT type");
        }
        return size;
    }

    /** Modified UTF matches DataOutput.writeUTF, including nulls and UTF-16 surrogate code units. */
    private static int utf(String value) throws IOException {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            int c = value.charAt(i);
            bytes += c >= 1 && c <= 127 ? 1 : c > 2047 ? 3 : 2;
            if (bytes > 65535) throw new IOException("snapshot NBT UTF length");
        }
        return 2 + bytes;
    }

    private static void checkpoint(BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw new java.io.InterruptedIOException("snapshot palette encoding retired");
    }

    private static final class Size {

        long bytes, tags;

        Size(long bytes, long tags) {
            this.bytes = bytes;
            this.tags = tags;
        }

        void add(Size value) {
            bytes += value.bytes;
            tags += value.tags;
        }

        void check(long raw, long tracked) throws IOException {
            // Forge 1.7 counts serialized bytes plus four bytes per allocated non-END tag.
            if (bytes > raw || bytes + 4 * tags > tracked) throw new IOException("snapshot logical byte budget");
        }
    }

    private static final class Packed extends NBTTagCompound {

        @Override
        public NBTTagCompound copy() {
            Packed result = new Packed();
            for (Object name : func_150296_c()) {
                String key = (String) name;
                result.setTag(key, getTag(key).copy());
            }
            return result;
        }
    }
}
