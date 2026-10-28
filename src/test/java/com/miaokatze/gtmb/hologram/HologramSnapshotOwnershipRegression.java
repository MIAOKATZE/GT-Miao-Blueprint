package com.miaokatze.gtmb.hologram;

import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Actual safety pass, governor admission, copied segment ownership and encoder CPU scope. */
public final class HologramSnapshotOwnershipRegression {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "copy-quota".equals(args[0])) {
            copyQuota();
            return;
        }
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
                check(expected.requestedBytes > 0, "quota rejection exposes its exact requested expanded bytes");
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
        long half = HologramProcessGovernor.retainedByteLimit() / 2 + 1;
        HologramSnapshotCodec.BusyException cumulative = HologramSnapshotSegments.Decoder
            .accumulatedQuota(half, new HologramSnapshotCodec.BusyException("next exact page", half));
        check(half <= HologramProcessGovernor.retainedByteLimit(), "each requested page can fit independently");
        check(
            cumulative.requestedBytes > HologramProcessGovernor.retainedByteLimit(),
            "already owned projection plus next page exposes a theoretically impossible combined allocation");
        com.miaokatze.gtmb.client.hologram.HologramSnapshotInboxRegression.runQuotaContention();
        com.miaokatze.gtmb.client.hologram.HologramSnapshotPreparationQuotaRegression.run();
        HologramSnapshotCacheQuotaRegression.main(args);
        System.out.println("HologramSnapshotOwnershipRegression passed");
    }

    /** Run in a fresh JVM with -Dgtmb.heavy.retainedBytes=1048576. */
    private static void copyQuota() throws Exception {
        check(HologramProcessGovernor.retainedByteLimit() == 1048576, "copy fixture requires its isolated 1 MiB quota");
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "exact-copy-quota-receipt");
        source.setByteArray("manifestPadding", new byte[800000]);
        long manifestBytes;
        try (HologramSnapshotCodec.OwnedSnapshot manifest = HologramSnapshotCodec
            .decodeOwned(HologramSnapshotCodec.compress(source), () -> true)) {
            manifestBytes = manifest.retainedEstimate;
        }
        NBTTagCompound row = new NBTTagCompound();
        byte[] payload = new byte[300000];
        new java.util.Random(71).nextBytes(payload);
        row.setByteArray("payload", payload);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(row);
        source.setTag("cells", cells);
        byte[] impossible = HologramSnapshotSegments
            .encode(source, Collections.emptySet(), () -> true, written -> {}).bytes;
        row.setByteArray("payload", java.util.Arrays.copyOf(payload, 160000));
        byte[] impossibleWorkingSet = HologramSnapshotSegments
            .encode(source, Collections.emptySet(), () -> true, written -> {}).bytes;
        row.setByteArray("payload", java.util.Arrays.copyOf(payload, 16000));
        byte[] temporary = HologramSnapshotSegments
            .encode(source, Collections.emptySet(), () -> true, written -> {}).bytes;
        com.miaokatze.gtmb.client.hologram.HologramSnapshotInboxRegression
            .runCopyQuota(impossible, impossibleWorkingSet, temporary, manifestBytes + 4096);
        System.out.println(
            "HologramSnapshotOwnershipRegression copy-quota passed manifestBytes=" + manifestBytes
                + " impossibleFrameBytes="
                + impossible.length
                + " temporaryFrameBytes="
                + temporary.length);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
