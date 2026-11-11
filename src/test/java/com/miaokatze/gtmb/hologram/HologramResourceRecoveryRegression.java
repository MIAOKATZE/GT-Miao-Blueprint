// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** A persisted tail must make progress despite a full batch of blocked or uncertain refunds. */
public final class HologramResourceRecoveryRegression {

    private HologramResourceRecoveryRegression() {}

    public static void main(String[] args) throws Exception {
        NBTTagList queued = new NBTTagList();
        for (int i = 0; i < 97; i++) {
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setInteger("id", i);
            receipt.setLong("amount", 100);
            receipt.setBoolean("uncertain", i == 0);
            queued.appendTag(receipt);
        }
        int[] attempts = { 0 };
        NBTTagList rotated = HologramResourceRecovery.retry(queued, 96, receipt -> {
            attempts[0]++;
            return true;
        });
        require(attempts[0] == 96 && rotated.tagCount() == 97, "Bounded attempts must retain all refusals");
        require(
            rotated.getCompoundTagAt(0)
                .getInteger("id") == 96,
            "The unvisited tail must become the head");
        require(
            rotated.getCompoundTagAt(1)
                .getBoolean("uncertain"),
            "Unknown-result evidence must survive rotation");

        NBTTagCompound owner = new NBTTagCompound();
        owner.setTag("queue", rotated);
        owner = CompressedStreamTools
            .func_152457_a(CompressedStreamTools.compress(owner), NBTSizeTracker.field_152451_a);
        attempts[0] = 0;
        NBTTagList reloaded = owner.getTagList("queue", 10);
        NBTTagList remaining = HologramResourceRecovery.retry(reloaded, 96, receipt -> {
            attempts[0]++;
            if (receipt.getInteger("id") == 96) {
                receipt.setLong("amount", 40);
                return true;
            }
            return receipt.getInteger("id") != 1;
        });
        require(attempts[0] == 96 && remaining.tagCount() == 96, "Only one confirmed full delivery leaves the ledger");
        require(
            remaining.getCompoundTagAt(1)
                .getLong("amount") == 40,
            "Partial delivery retains only the known remainder");
        require(
            reloaded.getCompoundTagAt(0)
                .getLong("amount") == 40,
            "Callbacks update the persisted source receipt");
        require(
            HologramResourceRecovery
                .retry(remaining, 0, receipt -> { throw new AssertionError("Zero budget must not call the adapter"); })
                .equals(remaining),
            "Zero budget preserves exact receipts and order");
        System.out.println("HologramResourceRecoveryRegression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
