package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Real NBT/codec wire projections: acknowledged references, revision changes and explicit cache misses. */
public final class HologramSnapshotSegmentsRegression {

    private HologramSnapshotSegmentsRegression() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("HologramSnapshotSegmentsRegression passed");
    }

    public static void run() throws Exception {
        NBTTagCompound source = source();
        NBTTagCompound original = (NBTTagCompound) source.copy();
        HologramSnapshotSegments.Decoder decoder = new HologramSnapshotSegments.Decoder();
        HologramSnapshotSegments.Encoded cold = HologramSnapshotSegments
            .encode(source, Collections.emptySet(), () -> true, n -> {});
        NBTTagCompound decoded = decoder.decode(cold.bytes);
        NBTTagList receipts = decoded.getTagList(HologramSnapshotSegments.RECEIPT, 8);
        decoded.removeTag(HologramSnapshotSegments.RECEIPT);
        check(decoded.equals(source), "cold manifest and segments reproduce the original complete snapshot");
        check(source.equals(original), "encoding never mutates its source or public identity");
        HologramSnapshotSegments.Peer peer = new HologramSnapshotSegments.Peer();
        peer.offered(cold.hashes);
        check(
            peer.baseline()
                .isEmpty(),
            "sending is not an acknowledgement");
        peer.acknowledge(receipts);
        HologramSnapshotSegments.Encoded hot = HologramSnapshotSegments
            .encode(source, peer.baseline(), () -> true, n -> {});
        check(hot.bytes.length < cold.bytes.length, "acknowledged pages are actual wire references");
        NBTTagCompound hotDecoded = decoder.decode(hot.bytes);
        hotDecoded.removeTag(HologramSnapshotSegments.RECEIPT);
        check(hotDecoded.equals(source), "hot references retain complete NBT");
        hotDecoded.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setString("payload", "mutated consumer");
        NBTTagCompound fresh = decoder.decode(hot.bytes);
        fresh.removeTag(HologramSnapshotSegments.RECEIPT);
        check(fresh.equals(source), "a consumer cannot mutate the cached exact page");
        source.getTagList("cells", 10)
            .getCompoundTagAt(300)
            .setInteger("meta", 7);
        HologramSnapshotSegments.Encoded changed = HologramSnapshotSegments
            .encode(source, peer.baseline(), () -> true, n -> {});
        check(
            changed.hashes.get(0)
                .equals(cold.hashes.get(0))
                && changed.hashes.get(2)
                    .equals(cold.hashes.get(2)),
            "one changed cell retains unrelated segment identities");
        check(
            !changed.hashes.get(1)
                .equals(cold.hashes.get(1)),
            "changed page has a new exact identity");
        NBTTagCompound changedDecoded = decoder.decode(changed.bytes);
        changedDecoded.removeTag(HologramSnapshotSegments.RECEIPT);
        check(changedDecoded.equals(source), "mixed references and changed pages reconstruct atomically");
        decoder.clear();
        boolean missing = false;
        try {
            decoder.decode(hot.bytes);
        } catch (HologramSnapshotSegments.MissingSegmentException expected) {
            missing = "session".equals(expected.session);
        }
        check(missing, "an evicted reference is an explicit session-bound resync, never empty NBT");
        byte[] legacy = HologramSnapshotCodec.compress(original);
        check(
            decoder.decode(legacy)
                .equals(original),
            "legacy gzip fallback remains complete");
        java.util.concurrent.atomic.AtomicInteger checkpoints = new java.util.concurrent.atomic.AtomicInteger();
        boolean cancelled = false;
        try {
            HologramSnapshotCodec.decode(legacy, () -> checkpoints.incrementAndGet() < 12);
        } catch (java.util.concurrent.CancellationException expected) {
            cancelled = true;
        }
        check(cancelled, "retired decode cancels in the existing safe codec without constructing a crash report");
        byte[] malformed = cold.bytes.clone();
        malformed[7] = 99;
        expectFailure(decoder, malformed, "unknown version rejected");
        byte[] truncated = java.util.Arrays.copyOf(cold.bytes, cold.bytes.length - 1);
        expectFailure(decoder, truncated, "truncated segment rejected before publication");
    }

    private static NBTTagCompound source() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "session");
        source.setLong("planRevision", 3);
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 600; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", i);
            cell.setString("payload", "exact inventory / material / unknown NBT " + i);
            cells.appendTag(cell);
        }
        source.setTag("cells", cells);
        return source;
    }

    private static void expectFailure(HologramSnapshotSegments.Decoder decoder, byte[] bytes, String message) {
        try {
            decoder.decode(bytes);
            throw new AssertionError(message);
        } catch (IOException expected) {}
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
