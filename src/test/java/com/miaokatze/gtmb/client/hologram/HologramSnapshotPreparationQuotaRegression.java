package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramProcessGovernor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.hologram.HologramSnapshotSegments;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** A real segmented receipt must not retry forever behind its own disposable page cache. */
public final class HologramSnapshotPreparationQuotaRegression {

    private HologramSnapshotPreparationQuotaRegression() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("HologramSnapshotPreparationQuotaRegression passed");
    }

    public static void run() throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "exact-preparation-receipt");
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
        Encoded cold = encode(source, Collections.emptySet());
        long estimate;
        try (HologramSnapshotSegments.Decoder measured = new HologramSnapshotSegments.Decoder();
            HologramSnapshotCodec.OwnedSnapshot decoded = measured.decodeOwned(cold.bytes, () -> true)) {
            estimate = decoded.retainedEstimate;
        }
        long available = estimate + com.miaokatze.gtmb.hologram.HologramSnapshotRows.preparationBytes(source)
            + estimate / 2;
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        try (
            HologramProcessGovernor.Lease competing = HologramProcessGovernor
                .retain("fixture.resident.server", HologramProcessGovernor.retainedByteLimit() - before - available);
            HologramSnapshotSegments.Decoder decoder = new HologramSnapshotSegments.Decoder();
            HologramSnapshotInbox<NBTTagCompound> inbox = new HologramSnapshotInbox<>(
                2,
                cold.bytes.length,
                1,
                (bytes, current) -> HologramClient.decodeSnapshot(decoder, bytes, current))) {
            long occupied = HologramProcessGovernor.retainedBytes();
            SimpleAssert.that(
                inbox.put(cold.bytes, 91, releases::incrementAndGet, inbox.epoch()),
                "real compressed receipt admitted");
            NBTTagCompound later = new NBTTagCompound();
            later.setString("session", "later-control");
            SimpleAssert.that(inbox.offerValue(later), "later control waits behind exact receipt");
            HologramSnapshotInbox.Result<NBTTagCompound> result = await(inbox);
            SimpleAssert
                .that(result != null, "preparation must progress while the competing resident lease stays live");
            try {
                SimpleAssert
                    .that(result.failure == null, "the full projection and preparation fit the unchanged quota");
                SimpleAssert.eq(91L, result.transportSequence, "cache retirement preserves exact transport identity");
                SimpleAssert.that(result.materialized != null, "preparation is admitted before publication");
                SimpleAssert.eq(
                    0,
                    result.value.getTagList(HologramSnapshotSegments.RECEIPT, 8)
                        .tagCount(),
                    "evicted pages are never acknowledged");
                result.value.removeTag(HologramSnapshotSegments.RECEIPT);
                SimpleAssert.eq(source, result.value, "cache retirement preserves every authoritative cell");
            } finally {
                result.close();
            }
            SimpleAssert
                .eq(occupied, HologramProcessGovernor.retainedBytes(), "projection and preparation release explicitly");
            SimpleAssert.eq(1, releases.get(), "the original frame reservation releases exactly once");
            SimpleAssert.eq(later, inbox.poll().value, "later control follows the original receipt");
            Encoded reference = encode(source, new java.util.HashSet<>(cold.hashes));
            try {
                decoder.decodeOwned(reference.bytes, () -> true);
                throw new AssertionError("a retired cache must request a complete replacement for reference-only data");
            } catch (HologramSnapshotSegments.MissingSegmentException expected) {
                SimpleAssert.eq(source.getString("session"), expected.session, "recovery keeps the exact session");
            }
        }
        SimpleAssert.eq(before, HologramProcessGovernor.retainedBytes(), "all test ownership retires without GC");
    }

    private static HologramSnapshotInbox.Result<NBTTagCompound> await(HologramSnapshotInbox<NBTTagCompound> inbox)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        HologramSnapshotInbox.Result<NBTTagCompound> result;
        while ((result = inbox.poll()) == null && System.nanoTime() < deadline) Thread.sleep(5);
        return result;
    }

    private static Encoded encode(NBTTagCompound source, Set<String> confirmed) throws Exception {
        java.lang.reflect.Method method = HologramSnapshotSegments.class.getDeclaredMethod(
            "encode",
            NBTTagCompound.class,
            Set.class,
            java.util.function.BooleanSupplier.class,
            java.util.function.LongConsumer.class);
        method.setAccessible(true);
        Object encoded = method.invoke(
            null,
            source,
            confirmed,
            (java.util.function.BooleanSupplier) () -> true,
            (java.util.function.LongConsumer) written -> {});
        java.lang.reflect.Field bytes = encoded.getClass()
            .getDeclaredField("bytes");
        java.lang.reflect.Field hashes = encoded.getClass()
            .getDeclaredField("hashes");
        bytes.setAccessible(true);
        hashes.setAccessible(true);
        return new Encoded((byte[]) bytes.get(encoded), (List<String>) hashes.get(encoded));
    }

    private static final class Encoded {

        final byte[] bytes;
        final List<String> hashes;

        Encoded(byte[] bytes, List<String> hashes) {
            this.bytes = bytes;
            this.hashes = hashes;
        }
    }
}
