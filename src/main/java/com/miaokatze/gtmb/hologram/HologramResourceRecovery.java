package com.miaokatze.gtmb.hologram;

import java.util.function.Predicate;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Fair bounded retries for persisted fluid/essentia receipts. Resource identity and settlement stay in the adapter. */
final class HologramResourceRecovery {

    private HologramResourceRecovery() {}

    /** The callback returns whether its updated receipt must remain queued, including unknown outcomes. */
    static NBTTagList retry(NBTTagList stored, int limit, Predicate<NBTTagCompound> attempt) {
        NBTTagList remaining = new NBTTagList();
        int visits = Math.min(stored.tagCount(), Math.max(0, limit));
        // Untouched receipts precede refused attempts on the next call, including after a reconnect.
        for (int i = visits; i < stored.tagCount(); i++) remaining.appendTag(
            stored.getCompoundTagAt(i)
                .copy());
        for (int i = 0; i < visits; i++) {
            NBTTagCompound receipt = stored.getCompoundTagAt(i);
            if (attempt.test(receipt)) remaining.appendTag(receipt.copy());
        }
        return remaining;
    }
}
