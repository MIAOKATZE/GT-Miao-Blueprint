// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import static com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer.ACCEPTED;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer.CLOSED;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer.OUT_OF_ORDER;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer.REPLAY;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer.STALE;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.decodeAck;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.decodeManifest;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.decodeSlice;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.encodeAck;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.encodeManifest;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.encodeSlice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Receiver;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Sender;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Ticket;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Variant;

/** Offline round trip of the production codec and intake cursor. Does not substitute for world/payment tests. */
public final class HologramProjectedLayerRegression {

    private static final UUID REQUEST = new UUID(0, 85), OTHER = new UUID(0, 86);

    private HologramProjectedLayerRegression() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("projected_layer_wire_and_cursor: passed");
    }

    public static void run() throws Exception {
        orderedTransfer();
        boundedWindowAndCumulativeStop();
        malformedAndTruncated();
        manifestBoundaries();
        largeSynthetic();
    }

    private static void orderedTransfer() throws IOException {
        Header h = header(0, 4, 3, Arrays.asList(variant("stone", 2), variant("glass", 2)));
        byte[] last = encodeSlice(slice(2, 65, 0, 0, 0));
        byte[] middle = encodeSlice(slice(1, 64, 1, 1, 1));
        byte[] first = encodeSlice(slice(0, 64, 0, 0, 0, 1, 0, 1));
        byte[] wire = encodeManifest(new Manifest(h));
        require(first.length == 32 + 2 * 3 * Integer.BYTES,
            "slice bytes contain only identity, sequence, layer, count and operations, no fingerprint");
        byte[] obsoleteManifest = wire.clone();
        ByteBuffer.wrap(obsoleteManifest).putInt(0, 0x47544d11);
        invalid(() -> decodeManifest(obsoleteManifest));
        byte[] obsoleteSlice = first.clone();
        ByteBuffer.wrap(obsoleteSlice).putInt(0, 0x47544d12);
        invalid(() -> decodeSlice(obsoleteSlice));
        invalid(() -> decodeSlice(Arrays.copyOf(first, first.length + 32)));
        Manifest offer = decodeManifest(wire);
        require(Arrays.equals(wire, encodeManifest(offer)), "canonical manifest bytes");
        Receiver receiver = new Receiver(offer);
        Sender sender = new Sender(h);
        Slice firstSlice = decodeSlice(first);
        require(!sender.claim(firstSlice), "no upload before admitted credit");
        require(receiver.offer(last) == OUT_OF_ORDER && receiver.pending() == null, "future slice stays unbuffered");
        require(sender.accept(receipt(receiver)) && sender.claim(firstSlice), "admitted first credit");
        require(receiver.offer(first) == ACCEPTED, "first authorized before the tail arrives");
        Ticket staged = receiver.pending();
        byte[] sameSequenceDifferentBody = encodeSlice(slice(0, 64, 0, 0, 1));
        require(receiver.offer(sameSequenceDifferentBody) == REPLAY && receiver.pending() == staged
            && staged.slice.size() == 2 && staged.slice.variant(0) == 0,
            "same task/sequence replay cannot replace queued work even with changed body");
        require(staged.slice.size() == 2 && staged.slice.variant(1) == 1, "exact first slice exposed");
        require(!receiver.finished() && receipt(receiver).nextSlice == 0 && receipt(receiver).credit == 1,
            "window credit does not acknowledge incomplete settlement");
        require(receiver.offer(first) == REPLAY && receiver.pending() == staged, "in-flight duplicate is idempotent");
        require(sender.accept(receipt(receiver)) && !sender.claim(firstSlice), "replay never duplicates a frame");
        require(!new Receiver(offer).settle(staged), "ticket cannot transfer between owners");
        require(receiver.settle(staged) && !receiver.settle(staged), "settled exactly once before EOF");
        require(receiver.offer(first) == REPLAY && receiver.pending() == null, "latest completed duplicate");
        require(receiver.offer(sameSequenceDifferentBody) == REPLAY && receiver.pending() == null,
            "changed-body replay after settlement cannot create another execution ticket");
        require(sender.accept(receipt(receiver)) && sender.claim(decodeSlice(middle)), "second credit");
        require(receiver.offer(middle) == ACCEPTED && receiver.settle(receiver.pending()), "second slice");
        require(receiver.offer(first) == STALE, "older slice classified stale without retaining history");
        require(receiver.offer(encodeSlice(new Slice(OTHER, 2, 65, new int[] { 0, 0, 0 }))) == STALE,
            "wrong request cannot affect current credit");
        require(sender.accept(receipt(receiver)) && sender.claim(decodeSlice(last)), "terminal slice can be sent");
        require(receiver.offer(last) == ACCEPTED && receiver.settle(receiver.pending()), "terminal slice settles");
        Ack terminal = receipt(receiver);
        require(terminal.complete && terminal.settledCells == 4 && terminal.nextSlice == 3 && terminal.credit == 0,
            "exact cumulative terminal receipt");
        require(sender.accept(terminal) && !sender.claim(decodeSlice(last)), "terminal stays closed");
        require(receiver.offer(encodeSlice(slice(3, 66, 0, 0, 0))) == CLOSED, "post-EOF rejected");
    }

    private static void boundedWindowAndCumulativeStop() throws IOException {
        int window = HologramProjectedLayerWire.WINDOW, count = window + 1;
        Header h = header(0, count, count, Collections.singletonList(variant("stone", count)));
        byte[][] frames = new byte[count][];
        for (int i = count - 1; i >= 0; i--) {
            frames[i] = encodeSlice(slice(i, 64, i, 0, 0));
        }
        Receiver receiver = new Receiver(new Manifest(h));
        Sender sender = new Sender(h);
        Ack initial = receipt(receiver);
        require(sender.accept(initial), "window opens only after admitted ACK");
        for (int i = 0; i < window; i++) {
            require(sender.claim(decodeSlice(frames[i])) && receiver.offer(frames[i]) == ACCEPTED,
                "bounded ordered window may arrive before any world settlement");
        }
        require(!sender.claim(decodeSlice(frames[window])) && receiver.offer(frames[window]) == OUT_OF_ORDER,
            "both sender and receiver reject a ninth outstanding frame");
        require(sender.accept(initial) && !sender.claim(decodeSlice(frames[window])),
            "replayed credit cannot add to a full window");
        Ticket head = receiver.pending();
        require(receiver.offer(frames[window - 1]) == REPLAY && receiver.pending() == head,
            "buffered duplicate cannot replace active cursor");
        for (int i = 0; i < 4; i++) require(receiver.settle(receiver.pending()), "ordered paid settlement");
        invalid(() -> new Ack(REQUEST, 4, 3, 1, false, false)); // Fewer cells than slices is invalid wire shape.
        require(!sender.accept(new Ack(REQUEST, 4, 5, 1, false, false)), "bad cumulative count cannot free credits");
        require(sender.accept(receipt(receiver)) && sender.claim(decodeSlice(frames[window]))
            && receiver.offer(frames[window]) == ACCEPTED, "one cumulative receipt restores bounded capacity");
        receiver.abort();
        Ack stopped = receipt(receiver);
        require(stopped.nextSlice == 4 && stopped.settledCells == 4 && stopped.aborted
            && receiver.pending() == null && receiver.offer(frames[window]) == CLOSED,
            "STOP discards all uncommitted buffered frames and retains exact paid prefix");
        require(sender.accept(stopped) && !sender.canSend(), "STOP closes the client window permanently");
    }

    private static void malformedAndTruncated() throws IOException {
        Header h = header(0, 3, 2, Arrays.asList(variant("stone", 2), variant("glass", 1)));
        byte[] last = encodeSlice(slice(1, 65, 0, 0, 1));
        byte[] first = encodeSlice(slice(0, 64, 0, 0, 0, 1, 0, 0));
        Manifest manifest = new Manifest(h);
        Receiver receiver = new Receiver(manifest);
        byte[] corrupt = first.clone();
        ByteBuffer.wrap(corrupt).putInt(40, 512); // Out-of-palette variant is rejected without a fingerprint.
        invalid(() -> receiver.offer(corrupt));
        require(receiver.pending() == null && receipt(receiver).nextSlice == 0, "corruption cannot stage work");
        Header otherAnchor = header(1, 3, 2, Arrays.asList(variant("stone", 2), variant("glass", 1)));
        require(new Receiver(new Manifest(otherAnchor)).offer(first) == ACCEPTED,
            "wire coordinates do not cryptographically bind an anchor; owner/world authority is checked by service");
        require(receiver.offer(first) == ACCEPTED && receiver.settle(receiver.pending()), "first survives retry");
        require(!receiver.finished() && receipt(receiver).credit == 1, "missing final slice is not success");
        receiver.abort(); // Actual owner timeout/stop invokes this; no timeout or world behavior is mocked here.
        Ack stopped = receipt(receiver);
        require(stopped.aborted && !stopped.complete && stopped.settledCells == 2 && stopped.credit == 0
            && receiver.offer(last) == CLOSED, "truncated stream retains settled prefix but cannot revive");

        byte[] earlyEnd = encodeSlice(slice(0, 64, 0, 0, 0, 1, 0, 0));
        require(new Receiver(new Manifest(h)).offer(earlyEnd) == ACCEPTED,
            "a first frame has no suffix promise; completion still requires the declared remaining cells");
        byte[] badInventory = encodeSlice(slice(0, 64, 0, 0, 1, 1, 0, 1));
        invalid(() -> new Receiver(new Manifest(h)).offer(badInventory));
        byte[] badBounds = encodeSlice(slice(0, 64, -1, 0, 0, 1, 0, 0));
        invalid(() -> new Receiver(new Manifest(h)).offer(badBounds));
        byte[] badOrder = encodeSlice(slice(1, 64, 1, 0, 1)); // Duplicate position at a slice boundary.
        byte[] preceding = encodeSlice(slice(0, 64, 0, 0, 0, 1, 0, 0));
        Receiver overlap = new Receiver(new Manifest(h));
        require(overlap.offer(preceding) == ACCEPTED && overlap.settle(overlap.pending()), "committed prefix");
        invalid(() -> overlap.offer(badOrder));
        require(!overlap.finished() && overlap.pending() == null, "cross-slice overlap never stages twice");
        invalid(() -> encodeSlice(slice(0, 64, 1, 0, 0, 1, 0, 1)));
        invalid(() -> decodeSlice(Arrays.copyOf(first, first.length - 1)));
        invalid(() -> decodeSlice(new byte[HologramProjectedLayerWire.MAX_SLICE_BYTES + 1]));
        invalid(() -> decodeSlice(ByteBuffer.wrap(first.clone()).putInt(28,
            HologramProjectedLayerWire.MAX_SLICE_CELLS + 1).array()));
        invalid(() -> decodeManifest(new byte[HologramProjectedLayerWire.MAX_MANIFEST_BYTES + 1]));
        invalid(() -> decodeManifest(Arrays.copyOf(encodeManifest(manifest), encodeManifest(manifest).length + 1)));
        invalid(() -> header(0, 2, 1, Arrays.asList(variant("stone", 1), variant("stone", 1))));
        invalid(() -> decodeAck(Arrays.copyOf(encodeAck(stopped), HologramProjectedLayerWire.ACK_BYTES + 1)));
        Sender sender = new Sender(h);
        require(!sender.accept(new Ack(REQUEST, 1, 2, 1, false, false)), "forged advance without an in-flight slice");
        require(!sender.accept(new Ack(OTHER, 0, 0, 1, false, false)), "ACK from wrong request");
        require(sender.accept(new Ack(REQUEST, 0, 0, 1, false, false)) && sender.claim(decodeSlice(first)),
            "initial matching ACK");
        require(!sender.accept(new Ack(REQUEST, 1, 1, 1, false, false)), "wrong cumulative payment-independent count");
    }

    private static void manifestBoundaries() throws IOException {
        List<Variant> two = Collections.singletonList(variant("stone", 2));
        invalid(() -> new Header(REQUEST, 0, 64, 0, 0, 64, 0, 0, 64, 0, 2, 1, two));
        // Enough total volume, but one slice is constrained to one physical Y.
        invalid(() -> new Header(REQUEST, 0, 64, 0, 0, 64, 0, 0, 65, 0, 2, 1, two));
        require(new Header(REQUEST, 0, 64, 0, 0, 64, 0, 0, 65, 0, 2, 2, two).cells == 2,
            "one slot on each physical layer");
        require(new Header(REQUEST, 0, 64, 0, 0, 0, 0, 512, 99, 0, 51_201, 101,
            Collections.singletonList(variant("stone", 51_201))).cells == 51_201, "exact residual capacity");
        // 513 columns × 100 levels is enough volume, but one extra slice covers only the single residual column.
        invalid(() -> new Header(REQUEST, 0, 64, 0, 0, 0, 0, 512, 99, 0, 51_202, 101,
            Collections.singletonList(variant("stone", 51_202))));
        require(new Header(REQUEST, 0, 64, 0, -30_000_000, 0, -30_000_000, 29_999_999, 4095, 29_999_999,
            2, 1, two).cells == 2, "valid non-tight envelope even where 3-D volume would overflow long");

        invalid(() -> new Variant("minecraft:stone", 0, "minecraft:stone", 0, 2, false, "", "", 1));
        invalid(() -> new Variant("minecraft:stone", 0, "minecraft:stone", 0, -1, true, "", "", 1));
        invalid(() -> new Variant("minecraft:stone", 0, "minecraft:stone", 0, -1, false, "a", "", 1));
        invalid(() -> new Variant("minecraft:stone", 0, "minecraft:stone", 0, -1, false, "", "a", 1));

        int occurrences = 700_000;
        Header h = header(0, occurrences, 1 + (occurrences - 1) / HologramProjectedLayerWire.MAX_SLICE_CELLS,
            Collections.singletonList(variant("stone", occurrences)));
        byte[] manifest = encodeManifest(new Manifest(h));
        // 68-byte header; two 4-byte-prefixed ASCII names; then metadata, damage, facing, flags, count.
        int facing = 68 + 2 * (4 + "minecraft:stone".length()) + 3;
        int tileLength = facing + 6;
        byte[] wrongFacing = manifest.clone(), controller = manifest.clone();
        wrongFacing[facing] = 2;
        controller[facing + 1] = 1;
        invalid(() -> decodeManifest(wrongFacing));
        invalid(() -> decodeManifest(controller));
        // An explicit reserved payload may not enter admission, even without a potentially huge repeated expansion.
        invalid(() -> decodeManifest(withPayload(manifest, tileLength, 1)));
        invalid(() -> decodeManifest(withPayload(manifest, tileLength + 4, 1)));
        // Even the encoded field alone would exceed 64 MiB when multiplied by its occurrences.
        invalid(() -> decodeManifest(withPayload(manifest, tileLength, 128)));
        require(decodeManifest(manifest).header.cells == occurrences, "equivalent plain manifest remains valid");
    }

    private static byte[] withPayload(byte[] plain, int at, int length) {
        byte[] encoded = new byte[plain.length + length];
        System.arraycopy(plain, 0, encoded, 0, at);
        ByteBuffer.wrap(encoded).putInt(at, length);
        Arrays.fill(encoded, at + 4, at + 4 + length, (byte) 'A');
        System.arraycopy(plain, at + 4, encoded, at + 4 + length, plain.length - at - 4);
        return encoded;
    }

    private static void largeSynthetic() throws IOException {
        // Same count as the frozen field artifact, not the real artifact's materials/NBT and not a performance claim.
        int count = 123_369, cap = HologramProjectedLayerWire.MAX_SLICE_CELLS;
        int partitions = 1 + (count - 1) / cap;
        Header h = header(0, count, partitions, Collections.singletonList(variant("stone", count)));
        byte[] manifest = encodeManifest(new Manifest(h));
        require(manifest.length < 256, "a one-variant manifest stays small regardless of cell count");
        Receiver receiver = new Receiver(decodeManifest(manifest));
        for (int index = 0; index < partitions; index++) {
            byte[] part = synthetic(index, count);
            require(part.length <= HologramProjectedLayerWire.MAX_SLICE_BYTES && receiver.offer(part) == ACCEPTED,
                "large bounded slice");
            require(receiver.settle(receiver.pending()), "large slice settled exactly once");
            require(receipt(receiver).complete == (index == partitions - 1), "no early terminal receipt");
        }
        require(receipt(receiver).settledCells == count, "exact large count");
    }

    private static byte[] synthetic(int index, int cells) throws IOException {
        int first = index * HologramProjectedLayerWire.MAX_SLICE_CELLS;
        int part = Math.min(cells - first, HologramProjectedLayerWire.MAX_SLICE_CELLS);
        int[] operations = new int[part * 3];
        for (int i = 0; i < part; i++) operations[i * 3] = first + i;
        return encodeSlice(new Slice(REQUEST, index, 64, operations));
    }

    private static Ack receipt(Receiver receiver) throws IOException {
        byte[] encoded = encodeAck(receiver.ack());
        require(encoded.length == HologramProjectedLayerWire.ACK_BYTES, "ACK wire bound");
        return decodeAck(encoded);
    }

    private static Header header(int anchor, int cells, int slices, List<Variant> palette) throws IOException {
        return new Header(REQUEST, anchor, 64, 0, 0, 64, 0, 123_369, 80, 80, cells, slices, palette);
    }

    private static Variant variant(String type, int count) throws IOException {
        return new Variant("minecraft:" + type, 0, "minecraft:" + type, 0, -1, false, "", "", count);
    }

    private static Slice slice(int sequence, int y, int... ops) throws IOException {
        return new Slice(REQUEST, sequence, y, ops);
    }

    private interface Checked {

        void run() throws IOException;
    }

    private static void invalid(Checked code) throws IOException {
        try {
            code.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected bounded admission to reject");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
