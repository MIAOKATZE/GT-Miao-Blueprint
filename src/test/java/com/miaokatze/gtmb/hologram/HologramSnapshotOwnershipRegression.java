package com.miaokatze.gtmb.hologram;

import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Actual safety pass, governor admission, copied segment ownership and encoder CPU scope. */
public final class HologramSnapshotOwnershipRegression {

    public static void main(String[] args) throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "owner");
        source.setByteArray("payload", new byte[32768]);
        byte[] compressed = HologramSnapshotCodec.compress(source);
        HologramSnapshotCodec.OwnedSnapshot owned = HologramSnapshotCodec.decodeOwned(compressed, () -> true);
        check(owned.value.equals(source), "owned decode preserves the complete NBT");
        check(HologramProcessGovernor.retainedBytes() > before, "expanded objects retain a process lease");
        owned.close();
        owned.close();
        check(HologramProcessGovernor.retainedBytes() == before, "expanded owner releases exactly once");
        HologramProcessGovernor.Lease occupied = HologramProcessGovernor
            .retain("fixture", HologramProcessGovernor.retainedByteLimit() - before);
        try {
            boolean busy = false;
            try {
                HologramSnapshotCodec.decodeOwned(compressed, () -> true);
            } catch (HologramSnapshotCodec.BusyException expected) {
                busy = true;
            }
            check(busy, "valid data beyond transient quota is busy, never malformed");
        } finally {
            occupied.close();
        }
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 300; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", i);
            cell.setString("config", "exact configuration " + i);
            cells.appendTag(cell);
        }
        source.removeTag("payload");
        source.setTag("cells", cells);
        HologramSnapshotSegments.Encoded cold = HologramSnapshotSegments
            .encode(source, Collections.emptySet(), () -> true, n -> {});
        try (HologramSnapshotSegments.Decoder decoder = new HologramSnapshotSegments.Decoder()) {
            owned = decoder.decodeOwned(cold.bytes, () -> true);
            long withProjection = HologramProcessGovernor.retainedBytes();
            owned.close();
            check(
                HologramProcessGovernor.retainedBytes() < withProjection,
                "projection lease is independent from cached segment ownership");
            check(HologramProcessGovernor.retainedBytes() > before, "exact cache retains its own bounded charge");
        }
        check(HologramProcessGovernor.retainedBytes() == before, "cache eviction returns every process lease");
        HologramSnapshotEncoding.Job job = HologramSnapshotEncoding.submit(
            source,
            Runnable::run,
            bytes -> check(
                HologramProcessGovernor.activeHeavyCpu() == 1,
                "actual encoder codec runs under the shared CPU permit"));
        check(job.done && job.failure == null, "CPU-scoped encoding completes");
        check(HologramProcessGovernor.activeHeavyCpu() == 0, "encoder releases CPU before publication");
        System.out.println("HologramSnapshotOwnershipRegression passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
