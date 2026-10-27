package com.miaokatze.gtmb.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Operation-owned resource facts; only the exact empty-row schema has a compact representation. */
final class HologramResourceReviewFact {

    // Keep the existing conservative allowance for ResourceIdentity, map capacity/nodes, the
    // process lease/delegate and the owner's lease-set entry. Payload strings and blocks are shared
    // with the frozen source, not copied here. This is admission accounting, not measured heap size.
    private static final long ENTRY_BYTES = 1024;
    private static final long FACT_BYTES = 64;
    private final long total, blocking;
    private final boolean nativeComplete;
    private final NBTTagCompound full;

    private HologramResourceReviewFact(NBTTagCompound source, boolean compact) {
        total = compact ? source.getLong("total") : 0;
        blocking = compact ? source.getLong("blocking") : 0;
        nativeComplete = compact && source.getBoolean("nativeComplete");
        full = compact ? null : (NBTTagCompound) source.copy();
    }

    static HologramResourceReviewFact freeze(NBTTagCompound source) {
        return new HologramResourceReviewFact(source, exactEmptySchema(source));
    }

    private static boolean exactEmptySchema(NBTTagCompound source) {
        if (source.func_150296_c().size() != 4 || !source.hasKey("rows", 9)
            || !source.hasKey("total", 4) || !source.hasKey("blocking", 4)
            || !source.hasKey("nativeComplete", 1)) return false;
        NBTTagList rows = (NBTTagList) source.getTag("rows");
        byte complete = source.getByte("nativeComplete");
        // A typed-but-empty list is not identical to the canonical empty list we reconstruct.
        return rows.tagCount() == 0 && rows.func_150303_d() == 0 && (complete == 0 || complete == 1);
    }

    boolean compact() {
        return full == null;
    }

    long retainedBytes() {
        return ENTRY_BYTES + FACT_BYTES + (full == null ? 0 : HologramSnapshotNbt.estimate(full));
    }

    int rowCount() {
        return full == null ? 0 : full.getTagList("rows", 10).tagCount();
    }

    /** Neither source mutation nor a certificate consumer can mutate a retained fact. */
    NBTTagCompound copy() {
        if (full != null) return (NBTTagCompound) full.copy();
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("rows", new NBTTagList());
        result.setLong("total", total);
        result.setLong("blocking", blocking);
        result.setBoolean("nativeComplete", nativeComplete);
        return result;
    }
}
