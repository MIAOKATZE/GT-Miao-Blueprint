package com.miaokatze.gtmb.hologram;

import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** A derived cache cannot repeatedly reserve the bytes needed by an otherwise admissible owned projection. */
public final class HologramSnapshotCacheQuotaRegression {

    public static void main(String[] args) throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "low-quota-exact-session");
        source.setLong("uiSequence", 31);
        source.setLong("planRevision", 6);
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 512; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", i);
            cell.setByteArray("payload", new byte[512]);
            cells.appendTag(cell);
        }
        source.setTag("cells", cells);
        long projectionBytes;
        try (HologramSnapshotCodec.OwnedSnapshot measured = HologramSnapshotCodec
            .decodeOwned(HologramSnapshotCodec.compress(source), () -> true)) {
            projectionBytes = measured.retainedEstimate;
        }
        long available = projectionBytes + projectionBytes / 2;
        check(available < 2L * projectionBytes, "one authoritative tree fits while its full derived copy cannot");
        HologramSnapshotSegments.Encoded cold = HologramSnapshotSegments
            .encode(source, Collections.emptySet(), () -> true, n -> {});
        check(cold.hashes.size() == 2, "the fixture contains two real independently admissible pages");
        try (HologramProcessGovernor.Lease competing = HologramProcessGovernor
            .retain("fixture.low.available.quota", HologramProcessGovernor.retainedByteLimit() - before - available);
            HologramSnapshotSegments.Decoder decoder = new HologramSnapshotSegments.Decoder()) {
            long occupied = HologramProcessGovernor.retainedBytes();
            for (int attempt = 0; attempt < 2; attempt++) {
                try (HologramSnapshotCodec.OwnedSnapshot projection = decoder.decodeOwned(cold.bytes, () -> true)) {
                    NBTTagList receipts = projection.value.getTagList(HologramSnapshotSegments.RECEIPT, 8);
                    check(receipts.tagCount() == 0, "an uncached fallback never acknowledges pages it does not retain");
                    projection.value.removeTag(HologramSnapshotSegments.RECEIPT);
                    check(
                        projection.value.equals(source),
                        "uncached fallback preserves every authoritative row and identity");
                    check(
                        HologramProcessGovernor.retainedBytes() <= occupied + available,
                        "fallback remains inside the unchanged shared quota");
                }
                check(
                    HologramProcessGovernor.retainedBytes() == occupied,
                    "closing a fallback projection returns every transferred page lease without a GC");
            }
            HologramSnapshotSegments.Encoded references = HologramSnapshotSegments
                .encode(source, new java.util.HashSet<>(cold.hashes), () -> true, n -> {});
            try {
                decoder.decodeOwned(references.bytes, () -> true);
                throw new AssertionError("uncached pages cannot satisfy a reference-only frame");
            } catch (HologramSnapshotSegments.MissingSegmentException expected) {
                check(
                    source.getString("session")
                        .equals(expected.session),
                    "missing references request only their exact view");
            }
            try {
                decoder.decodeOwned(cold.bytes, () -> false);
                throw new AssertionError("a cancelled projection must not publish");
            } catch (java.util.concurrent.CancellationException expected) {}
            check(
                HologramProcessGovernor.retainedBytes() == occupied,
                "cancellation leaves no page or projection leases");
        }
        check(HologramProcessGovernor.retainedBytes() == before, "all low-quota fixture work is returned explicitly");
        System.out.println(
            "HologramSnapshotCacheQuotaRegression passed projectionBytes=" + projectionBytes
                + " availableBytes="
                + available
                + " acknowledgedPages=0");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
