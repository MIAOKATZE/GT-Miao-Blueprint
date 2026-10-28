package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * A mixed frame cannot evict its own acknowledged references at the real cache boundary.
 * Run in an isolated JVM: unrelated weak-cache retirement can change process-wide lease baselines.
 */
public final class HologramSnapshotFrameReferencesRegression {

    private HologramSnapshotFrameReferencesRegression() {}

    public static void main(String[] args) throws Exception {
        if (!com.miaokatze.gtmb.testutil.MinecraftTestBootstrap
            .enter(HologramSnapshotFrameReferencesRegression.class, args)) return;
        run();
        System.out.println("HologramSnapshotFrameReferencesRegression passed");
    }

    public static void run() throws Exception {
        for (int pages : new int[] { 128, 129 }) {
            runV1(pages);
            runV2(pages);
        }
    }

    private static void runV1(int pages) throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        NBTTagCompound source = source(pages);
        try (HologramSnapshotSegments.Decoder decoder = new HologramSnapshotSegments.Decoder()) {
            HologramSnapshotSegments.Encoded cold = HologramSnapshotSegments
                .encode(source, Collections.emptySet(), () -> true, ignored -> {});
            Set<String> baseline = acknowledge(decoder, cold.bytes, cold.hashes, source);
            boundary(cold.hashes, baseline, pages);
            HologramSnapshotSegments.Encoded warm = HologramSnapshotSegments
                .encode(source, baseline, () -> true, ignored -> {});
            verifyWarm(decoder, warm.bytes, source, baseline, pages);
        }
        checkRetained(before, "v1 pages=" + pages + " cache and projections release every lease");
    }

    private static void runV2(int pages) throws Exception {
        HologramSnapshotSegmentEncoding.clear();
        HologramTransportPageLayout.clear();
        long before = HologramProcessGovernor.retainedBytes();
        NBTTagCompound source = source(pages);
        NBTTagList rows = source.getTagList("cells", 10);
        HologramCapture capture = new HologramCapture(null, null);
        for (int i = 0; i < rows.tagCount(); i++) capture.cells.add(new HologramCapture.Cell(null, i, 0, 0));
        try (
            HologramTransportPageLayout.Builder builder = new HologramTransportPageLayout.Builder(
                source.getString("session"),
                capture,
                0,
                0,
                0,
                null,
                rows.tagCount());
            HologramSnapshotSegments.Decoder decoder = new HologramSnapshotSegments.Decoder()) {
            for (int i = 0; i < rows.tagCount(); i++) builder.add(i, rows.getCompoundTagAt(i));
            try (HologramTransportPageLayout layout = builder.finish();
                HologramSnapshotSegmentEncoding.Result cold = HologramSnapshotSegmentEncoding
                    .encode(source, layout, Collections.emptySet(), () -> true, ignored -> {})) {
                Set<String> baseline = acknowledge(decoder, cold.bytes.bytes, cold.hashes, source);
                boundary(cold.hashes, baseline, pages);
                try (HologramSnapshotSegmentEncoding.Result warm = HologramSnapshotSegmentEncoding
                    .encode(source, layout, baseline, () -> true, ignored -> {})) {
                    verifyWarm(decoder, warm.bytes.bytes, source, baseline, pages);
                }
            }
        } finally {
            HologramSnapshotSegmentEncoding.clear();
            HologramTransportPageLayout.clear();
        }
        checkRetained(before, "v2 pages=" + pages + " cache and projections release every lease");
    }

    private static Set<String> acknowledge(HologramSnapshotSegments.Decoder decoder, byte[] cold, List<String> offered,
        NBTTagCompound source) throws Exception {
        HologramSnapshotSegments.Peer peer = new HologramSnapshotSegments.Peer();
        try (HologramSnapshotCodec.OwnedSnapshot first = decoder.decodeOwned(cold, () -> true)) {
            peer.offered(offered);
            peer.acknowledge(first.value.getTagList(HologramSnapshotSegments.RECEIPT, 8));
            first.value.removeTag(HologramSnapshotSegments.RECEIPT);
            check(first.value.equals(source), "cold projection preserves exact NBT");
        }
        assertCacheBudget(decoder);
        return peer.baseline();
    }

    private static void boundary(List<String> hashes, Set<String> baseline, int pages) {
        check(baseline.size() == 128, "real peer acknowledgements reach the unchanged 128-entry boundary");
        if (pages == 129) {
            check(!baseline.contains(hashes.get(0)), "P0 must be inline");
            check(baseline.contains(hashes.get(1)), "P1 must be an existing acknowledged reference");
        }
    }

    private static void verifyWarm(HologramSnapshotSegments.Decoder decoder, byte[] warm, NBTTagCompound source,
        Set<String> baseline, int pages) throws Exception {
        long cachedBytes = HologramProcessGovernor.retainedBytes();
        AtomicInteger checkpoints = new AtomicInteger();
        try (HologramSnapshotCodec.OwnedSnapshot decoded = decoder.decodeOwned(warm, () -> {
            checkpoints.incrementAndGet();
            return true;
        })) {
            NBTTagList receipts = decoded.value.getTagList(HologramSnapshotSegments.RECEIPT, 8);
            check(receipts.tagCount() == 128, "inline overflow does not increase cache capacity");
            for (int i = 0; i < receipts.tagCount(); i++) check(
                baseline.contains(receipts.getStringTagAt(i)),
                "only previously retained pages are acknowledged, never the uncached overflow");
            decoded.value.removeTag(HologramSnapshotSegments.RECEIPT);
            check(decoded.value.equals(source), "mixed frame reconstructs every row without self-eviction");
            decoded.value.getTagList("cells", 10)
                .getCompoundTagAt(256)
                .setInteger("x", -1);
        }
        checkRetained(cachedBytes, "uncached inline ownership closes with projection");
        assertCacheBudget(decoder);
        // Repeat without any acknowledgement or cache reset: the pin set is frame-local, not extra retention.
        NBTTagCompound repeated = decoder.decode(warm);
        repeated.removeTag(HologramSnapshotSegments.RECEIPT);
        check(repeated.equals(source), "consumer mutation never changes pinned cached pages");
        if (pages != 129) return;

        AtomicInteger cancelledAt = new AtomicInteger();
        int cancelAfter = checkpoints.get() - 1;
        check(cancelAfter > 129, "cancellation happens after the header-only scan and inline projection work");
        try {
            decoder.decodeOwned(warm, () -> cancelledAt.incrementAndGet() < cancelAfter);
            throw new AssertionError("cancelled mixed frame cannot publish");
        } catch (CancellationException expected) {}
        checkRetained(cachedBytes, "cancellation releases all partial projection leases");

        malformed(decoder, Arrays.copyOf(warm, warm.length - 1));
        malformed(decoder, Arrays.copyOf(warm, warm.length + 1));
        int firstPage = 24 + ByteBuffer.wrap(warm)
            .getInt(12);
        byte[] invalidLength = warm.clone();
        ByteBuffer.wrap(invalidLength)
            .putInt(firstPage + 36, -1);
        malformed(decoder, invalidLength);
        byte[] invalidRaw = warm.clone();
        ByteBuffer.wrap(invalidRaw)
            .putInt(firstPage + 32, 128 * 1024 * 1024);
        malformed(decoder, invalidRaw);
        checkRetained(cachedBytes, "malformed framing releases its manifest lease");
        assertCacheBudget(decoder);
        try (HologramSnapshotCodec.OwnedSnapshot recovered = decoder.decodeOwned(warm, () -> true)) {
            recovered.value.removeTag(HologramSnapshotSegments.RECEIPT);
            check(recovered.value.equals(source), "cancelled and malformed frames leave valid references usable");
        }

        NBTTagCompound replacement = source(pages);
        NBTTagList rows = replacement.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) rows.getCompoundTagAt(i)
            .setInteger("x", -i - 1);
        HologramSnapshotSegments.Encoded other = HologramSnapshotSegments
            .encode(replacement, Collections.emptySet(), () -> true, ignored -> {});
        try (HologramSnapshotCodec.OwnedSnapshot ignored = decoder.decodeOwned(other.bytes, () -> true)) {}
        assertCacheBudget(decoder);
        try {
            decoder.decodeOwned(warm, () -> true);
            throw new AssertionError("a genuinely evicted reference must still require resync");
        } catch (HologramSnapshotSegments.MissingSegmentException expected) {
            check(
                source.getString("session")
                    .equals(expected.session),
                "external eviction keeps exact resync session");
        }
        // The inline page may now replace an unpinned external page; all cache leases still close with decoder.
        assertCacheBudget(decoder);
    }

    private static void malformed(HologramSnapshotSegments.Decoder decoder, byte[] bytes) throws Exception {
        try {
            decoder.decodeOwned(bytes, () -> true);
            throw new AssertionError("malformed frame must fail before touching cached pages");
        } catch (IOException expected) {
            check(
                !(expected instanceof HologramSnapshotSegments.MissingSegmentException),
                "malformed framing is not mistaken for a reference miss");
        }
    }

    private static void assertCacheBudget(HologramSnapshotSegments.Decoder decoder) throws Exception {
        check(((Map<?, ?>) field(decoder, "cache")).size() == 128, "cache entry budget stays exactly 128");
        check((Integer) field(decoder, "cachedRaw") <= 8 * 1024 * 1024, "raw cache budget stays 8 MiB");
        check((Long) field(decoder, "cachedEstimate") <= 32L * 1024 * 1024, "retained cache budget stays 32 MiB");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static NBTTagCompound source(int pages) {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("session", "same-session-same-connection");
        root.setLong("planRevision", 1L);
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < pages * 256; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", i);
            row.setInteger("y", 0);
            row.setInteger("z", 0);
            rows.appendTag(row);
        }
        root.setTag("cells", rows);
        return root;
    }

    private static void checkRetained(long expected, String message) {
        long actual = HologramProcessGovernor.retainedBytes();
        check(actual == expected, message + ": expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
