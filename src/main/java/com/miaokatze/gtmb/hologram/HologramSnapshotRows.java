package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Lossless, negotiated display rows. Position arrays and page palettes never authorize world operations. */
public final class HologramSnapshotRows {

    static final String PALETTE = "gtmbRowPalette";
    private static final String POSITION = "position", DESCRIPTOR = "descriptor";
    private static final String[] AXES = { "x", "y", "z", "dx", "dy", "dz" };

    private HologramSnapshotRows() {}

    /** Owner-side preparation keeps one exact descriptor per material rather than fifty tags per cell. */
    static final class Builder {

        private NBTTagList palette = new NBTTagList();
        private final Map<DescriptorKey, Integer> descriptors = new HashMap<>();
        private int count;

        NBTTagCompound add(NBTTagCompound source) {
            if (count++ == HologramSegmentContract.PAGE_SIZE) {
                count = 1;
                palette = new NBTTagList();
                descriptors.clear();
            }
            NBTTagCompound row = resolve(source);
            for (String key : AXES) if (!row.hasKey(key, 3))
                throw new IllegalArgumentException("snapshot compact rows require six typed coordinates");
            if (row.hasKey(PALETTE) || row.hasKey(POSITION) || row.hasKey(DESCRIPTOR))
                throw new IllegalArgumentException("snapshot row uses a reserved transport field");
            DescriptorKey key = new DescriptorKey(row);
            Integer id = descriptors.get(key);
            if (id == null) {
                NBTTagCompound descriptor = new NBTTagCompound();
                for (Object object : row.func_150296_c()) {
                    String field = (String) object;
                    if (!axis(field)) descriptor.setTag(field, row.getTag(field));
                }
                id = palette.tagCount();
                descriptors.put(new DescriptorKey(descriptor), id);
                palette.appendTag(descriptor);
            }
            int[] position = new int[AXES.length];
            for (int i = 0; i < position.length; i++) position[i] = row.getInteger(AXES[i]);
            NBTTagCompound compact = new NBTTagCompound();
            compact.setIntArray(POSITION, position);
            compact.setInteger(DESCRIPTOR, id);
            compact.setTag(PALETTE, palette);
            return compact;
        }
    }

    static NBTTagCompound compactPage(java.util.List<NBTTagCompound> source) {
        NBTTagCompound shared = sharedPage(source);
        if (shared != null) return shared;
        for (NBTTagCompound original : source) {
            NBTTagCompound row = resolve(original);
            for (String key : AXES) if (!row.hasKey(key, 3)) return plainPage(source);
            if (row.hasKey(PALETTE) || row.hasKey(POSITION) || row.hasKey(DESCRIPTOR)) return plainPage(source);
        }
        Builder builder = new Builder();
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (NBTTagCompound original : source) {
            NBTTagCompound row = builder.add(original);
            row.removeTag(PALETTE);
            rows.appendTag(row);
        }
        root.setTag("cells", rows);
        root.setTag(PALETTE, builder.palette);
        return root;
    }

    /** Owner-built pages already proved descriptor equality. Do not expand and intern their same rows again. */
    private static NBTTagCompound sharedPage(java.util.List<NBTTagCompound> source) {
        if (source.isEmpty() || !compactRow(source.get(0))) return null;
        NBTTagList palette = source.get(0)
            .getTagList(PALETTE, 10);
        if (palette.tagCount() < 1 || palette.tagCount() > source.size()) return null;
        for (NBTTagCompound row : source) {
            if (row.getTag(PALETTE) != palette || row.func_150296_c()
                .size() != 3
                || !row.hasKey(POSITION, 11)
                || !row.hasKey(DESCRIPTOR, 3)
                || row.getIntArray(POSITION).length != AXES.length
                || row.getInteger(DESCRIPTOR) < 0
                || row.getInteger(DESCRIPTOR) >= palette.tagCount()) return null;
        }
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (NBTTagCompound original : source) {
            NBTTagCompound row = new NBTTagCompound();
            row.setTag(POSITION, original.getTag(POSITION));
            row.setTag(DESCRIPTOR, original.getTag(DESCRIPTOR));
            rows.appendTag(row);
        }
        root.setTag("cells", rows);
        root.setTag(PALETTE, palette);
        return root;
    }

    private static NBTTagCompound plainPage(java.util.List<NBTTagCompound> source) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (NBTTagCompound row : source) rows.appendTag(resolve(row));
        root.setTag("cells", rows);
        return root;
    }

    static boolean isCompactPage(NBTTagCompound root) {
        return root.hasKey(PALETTE);
    }

    static void validatePage(NBTTagCompound root, int expected) throws IOException {
        if (!root.hasKey("cells", 9) || !root.hasKey(PALETTE, 9)
            || root.func_150296_c()
                .size() != 2)
            throw new IOException("snapshot compact page shape");
        NBTTagList rows = root.getTagList("cells", 10), palette = root.getTagList(PALETTE, 10);
        if (expected < 1 || expected > HologramSegmentContract.PAGE_SIZE
            || rows.tagCount() != expected
            || palette.tagCount() < 1
            || palette.tagCount() > expected) throw new IOException("snapshot compact page count");
        for (int i = 0; i < palette.tagCount(); i++) {
            NBTTagCompound descriptor = palette.getCompoundTagAt(i);
            for (String key : AXES) if (descriptor.hasKey(key)) throw new IOException("snapshot descriptor position");
            if (descriptor.hasKey(PALETTE) || descriptor.hasKey(POSITION) || descriptor.hasKey(DESCRIPTOR))
                throw new IOException("snapshot descriptor reserved field");
        }
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.func_150296_c()
                .size() != 2 || !row.hasKey(POSITION, 11)
                || !row.hasKey(DESCRIPTOR, 3)
                || row.getIntArray(POSITION).length != AXES.length
                || row.getInteger(DESCRIPTOR) < 0
                || row.getInteger(DESCRIPTOR) >= palette.tagCount())
                throw new IOException("snapshot compact row shape");
        }
    }

    /** Cache projections clone their palette once, then share its immutable values between their cells. */
    static NBTTagList project(NBTTagCompound root, boolean copy) {
        NBTTagList palette = root.getTagList(PALETTE, 10);
        if (copy) palette = (NBTTagList) palette.copy();
        NBTTagList rows = root.getTagList("cells", 10), result = new NBTTagList();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (copy) row = (NBTTagCompound) row.copy();
            row.setTag(PALETTE, palette);
            result.appendTag(row);
        }
        return result;
    }

    static long projectionOverhead(NBTTagCompound root) {
        return projectionOverhead(
            root.getTagList("cells", 10)
                .tagCount());
    }

    static long projectionOverhead(int rowCount) {
        return 256L + 128L * rowCount;
    }

    static boolean compactRow(NBTTagCompound row) {
        return row.hasKey(PALETTE, 9);
    }

    static boolean compactSnapshot(NBTTagCompound root) {
        NBTTagList rows = root.getTagList("cells", 10);
        return rows.tagCount() > 0 && compactRow(rows.getCompoundTagAt(0));
    }

    /**
     * The transferred NBT already owns all strings, arrays and native display payloads. Preparation adds Cell fields,
     * two ItemStack shells, six hint references, small lists and spatial-map entries (1024 bytes per cell); variable
     * candidate ItemStacks and membership indices are charged separately. Mutable ItemStack NBT and specialHint are
     * deep-copied by Cell and charged at their full projected size, including repeated palette references.
     */
    public static long preparationBytes(NBTTagCompound snapshot) {
        NBTTagList rows = snapshot.getTagList("cells", 10);
        long bytes = 4096L + 1024L * rows.tagCount();
        java.util.Set<NBTTagCompound> parsed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            NBTTagCompound descriptor = compactRow(row) ? row.getTagList(PALETTE, 10)
                .getCompoundTagAt(row.getInteger(DESCRIPTOR)) : row;
            long candidatesBytes = 128L * descriptor.getTagList("candidates", 10)
                .tagCount();
            bytes += candidatesBytes;
            bytes += 64L * descriptor.getTagList("structureIds", 8)
                .tagCount();
            long nativeBytes = stackPayload(descriptor.getCompoundTag("actualStack"))
                + stackPayload(descriptor.getCompoundTag("targetStack"));
            NBTTagList candidates = descriptor.getTagList("candidates", 10);
            for (int candidate = 0; candidate < candidates.tagCount(); candidate++)
                nativeBytes += stackPayload(candidates.getCompoundTagAt(candidate));
            bytes += nativeBytes;
            long hintBytes = descriptor.hasKey("specialHint", 10)
                ? HologramSnapshotNbt.estimate(descriptor.getCompoundTag("specialHint"))
                : 0;
            if (descriptor == row) bytes += hintBytes;
            else if (parsed.add(descriptor)) bytes += 1024L + candidatesBytes + nativeBytes + hintBytes;
        }
        return bytes + 256L * snapshot.getTagList("materials", 10)
            .tagCount();
    }

    private static long stackPayload(NBTTagCompound stack) {
        return stack.hasKey("tag", 10) ? HologramSnapshotNbt.estimate(stack.getCompoundTag("tag")) : 0;
    }

    static boolean sameRow(NBTTagCompound page, int index, NBTTagCompound row) {
        NBTTagCompound frozen = page.getTagList("cells", 10)
            .getCompoundTagAt(index);
        return HologramSnapshotNbt
            .same(isCompactPage(page) ? resolve(frozen, page.getTagList(PALETTE, 10)) : frozen, resolve(row));
    }

    /** A temporary shallow view resolves registry fields without retaining another complete row map. */
    public static NBTTagCompound resolve(NBTTagCompound row) {
        return row.hasKey(PALETTE, 9) ? resolve(row, row.getTagList(PALETTE, 10)) : row;
    }

    /** Palette identity is stable for this private publication; consumers never mutate its descriptor. */
    public static NBTTagCompound descriptor(NBTTagCompound row) {
        return compactRow(row) ? row.getTagList(PALETTE, 10)
            .getCompoundTagAt(row.getInteger(DESCRIPTOR)) : null;
    }

    public static int[] position(NBTTagCompound row) {
        return row.getIntArray(POSITION);
    }

    private static NBTTagCompound resolve(NBTTagCompound row, NBTTagList palette) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagCompound descriptor = palette.getCompoundTagAt(row.getInteger(DESCRIPTOR));
        for (Object raw : descriptor.func_150296_c()) {
            String key = (String) raw;
            result.setTag(key, descriptor.getTag(key));
        }
        int[] position = row.getIntArray(POSITION);
        for (int i = 0; i < AXES.length; i++) result.setInteger(AXES[i], position[i]);
        // Model-only overlays replace a bounded subset without expanding the retained cold row.
        for (Object raw : row.func_150296_c()) {
            String key = (String) raw;
            if (!key.equals(PALETTE) && !key.equals(POSITION) && !key.equals(DESCRIPTOR))
                result.setTag(key, row.getTag(key));
        }
        return result;
    }

    /** Export retains every original field and absent/empty distinction while isolating callers' mutations. */
    public static NBTTagCompound export(NBTTagCompound row) {
        return (NBTTagCompound) resolve(row).copy();
    }

    private static boolean axis(String key) {
        for (String axis : AXES) if (axis.equals(key)) return true;
        return false;
    }

    /** Vanilla numeric equality can conflate signed zero; palette identities retain exact floating-point bits. */
    private static final class DescriptorKey {

        final NBTTagCompound value;
        final int hash, fields;

        DescriptorKey(NBTTagCompound value) {
            this.value = value;
            int hash = 0, fields = 0;
            for (Object raw : value.func_150296_c()) {
                String key = (String) raw;
                if (!axis(key)) {
                    hash += key.hashCode() ^ value.getTag(key)
                        .hashCode();
                    fields++;
                }
            }
            this.hash = hash;
            this.fields = fields;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof DescriptorKey)) return false;
            DescriptorKey key = (DescriptorKey) other;
            if (fields != key.fields) return false;
            for (Object raw : value.func_150296_c()) {
                String field = (String) raw;
                if (!axis(field) && !HologramSnapshotNbt.same(value.getTag(field), key.value.getTag(field)))
                    return false;
            }
            return true;
        }
    }
}
