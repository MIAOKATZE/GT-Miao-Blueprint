// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.miaokatze.gtmb.testutil.SimpleAssert;

final class HologramScreenLinePrefixRegression {

    private HologramScreenLinePrefixRegression() {}

    static void run() {
        streamBytesAndLifecycle();
        boundedRecording();
    }

    private static final class FakeGpu implements HologramScreenLinePrefix.Gpu {

        Object context = new Object();
        final List<byte[]> uploaded = new ArrayList<>();
        final List<Integer> drawn = new ArrayList<>(), deleted = new ArrayList<>();

        public ByteBuffer allocate(int capacity) {
            return ByteBuffer.allocate(capacity);
        }

        public void free(ByteBuffer unused) {}

        public Object context() {
            return context;
        }

        public HologramScreenLinePrefix.Packet upload(ByteBuffer bytes, int vertices) {
            int id = uploaded.size();
            byte[] immutable = new byte[bytes.remaining()];
            SimpleAssert.eq(vertices * 16, immutable.length, "submit exactly POSITION_COLOR vertices");
            bytes.get(immutable);
            uploaded.add(immutable);
            return new HologramScreenLinePrefix.Packet() {
                public void draw() {
                    drawn.add(id);
                }

                public void delete() {
                    deleted.add(id);
                }
            };
        }
    }

    private static void boundedRecording() {
        FakeGpu gpu = new FakeGpu();
        int boxes = HologramScreenLinePrefix.BOXES;
        var stamp = new HologramScreenLinePrefix.Stamp(gpu, gpu, gpu, null, 0, -1, 1, 1, 0, 0, 0);
        try (var cache = new HologramScreenLinePrefix(gpu)) {
            cache.begin(stamp, boxes * 101);
            for (int f = 0; f < 13; f++) {
                int covered = cache.begin(stamp, boxes * 101);
                SimpleAssert.eq(boxes * Math.min(f * 8, 96), covered, "only a contiguous completed prefix can replay");
                while (cache.recording()) {
                    cache.vertex(4, 2, 1);
                    cache.vertex(8, 3, 1);
                    cache.finishPacket();
                }
                SimpleAssert.that(cache.recordings <= 8, "bounded GPU allocations per frame");
            }
            SimpleAssert.eq(96, gpu.uploaded.size(), "hard GPU packet cap leaves the rest on the normal stream");
            SimpleAssert.eq(0, cache.begin(stamp, boxes), "a truncated list invalidates the retained prefix");
            SimpleAssert.eq(96, gpu.deleted.size(), "retirement releases all packets exactly once");
        }
    }

    private static void streamBytesAndLifecycle() {
        Object state = new Object(), camera = new Object(), selection = new Object(), outlines = new ArrayList<>();
        var stamp = new HologramScreenLinePrefix.Stamp(state, camera, outlines, selection, 4, 7, .5f, 1.75, 33, -18, .125);
        int boxes = HologramScreenLinePrefix.BOXES;
        FakeGpu gpu = new FakeGpu();
        try (HologramScreenLinePrefix cache = new HologramScreenLinePrefix(gpu)) {
            cache.begin(stamp, 2 * boxes);
            SimpleAssert.that(!cache.recording(), "do not upload for an unconfirmed moving frame");
            cache.begin(stamp, 2 * boxes);
            for (int i = 0; i < 2; i++) {
                SimpleAssert.eq((i + 1) * boxes, cache.packetEnd(), "record ascending append-only batches");
                cache.color(.2f, .7f, .8f, .45f);
                cache.vertex(16777217.125 + i, -23.125, 1.75);
                cache.vertex(-31.25, .0625, -8);
                cache.finishPacket();
                ByteBuffer ref = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
                for (double[] p : new double[][] { { 16777217.125 + i, -23.125, 1.75 }, { -31.25, .0625, -8 } }) {
                    ref.putFloat((float) (p[0] + 33)).putFloat((float) (p[1] - 18)).putFloat((float) (p[2] + .125));
                    ref.put((byte) 51).put((byte) 178).put((byte) 204).put((byte) 114);
                }
                SimpleAssert.that(Arrays.equals(ref.array(), gpu.uploaded.get(i)), "offset before rounding, RGBA byte parity");
            }
            SimpleAssert.eq(Arrays.asList(0, 1), gpu.drawn, "recorded boxes appear exactly once, in order");
            gpu.drawn.clear();
            SimpleAssert.eq(2 * boxes, cache.begin(stamp, 2 * boxes + 3), "a tail is still emitted live");
            SimpleAssert.eq(Arrays.asList(0, 1), gpu.drawn, "replay the exact immutable prefix without a second upload");
            SimpleAssert.eq(4, cache.replayedVertices, "telemetry counts actual submitted vertices, not source rows");
            var moved = new HologramScreenLinePrefix.Stamp(state, new Object(), outlines, selection, 4, 7, .5f, 1.75, 33, -18, .125);
            SimpleAssert.eq(0, cache.begin(moved, 2 * boxes), "a camera change cannot replay a stale frame");
            SimpleAssert.eq(Arrays.asList(0, 1), gpu.deleted, "retiring the old camera deletes its GL resources");
            var edited = new HologramScreenLinePrefix.Stamp(state, camera, outlines, selection, 5, 7, .5f, 1.75, 33, -18, .125);
            var replaced = new HologramScreenLinePrefix.Stamp(new Object(), camera, outlines, selection, 4, 7, .5f, 1.75, 33, -18, .125);
            var offset = new HologramScreenLinePrefix.Stamp(state, camera, outlines, selection, 4, 7, .5f, 1.75, 34, -18, .125);
            var otherList = new HologramScreenLinePrefix.Stamp(state, camera, new ArrayList<>(), selection, 4, 7, .5f, 1.75, 33, -18, .125);
            SimpleAssert.that(!stamp.matches(edited) && !stamp.matches(replaced) && !stamp.matches(offset)
                && !stamp.matches(otherList), "selection, generation, offsets and list identity invalidate draws");
            cache.begin(moved, 2 * boxes);
            cache.vertex(1, 2, 3);
            cache.vertex(4, 5, 6);
            cache.finishPacket();
            gpu.context = new Object();
            SimpleAssert.eq(0, cache.begin(moved, 2 * boxes), "new GL context cannot see the previous context's packets");
            SimpleAssert.eq(Arrays.asList(0, 1), gpu.deleted, "never delete an old numeric GL name in its new context");
        }
    }
}
