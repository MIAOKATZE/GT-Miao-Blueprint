// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramArchiveTileMesh.ALL_LAYERS;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtmb.client.hologram.HologramArchiveCameraTiles.Built;
import com.miaokatze.gtmb.client.hologram.HologramArchiveCameraTiles.Frame;
import com.miaokatze.gtmb.client.hologram.HologramArchiveCameraTiles.Tile;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Budget;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Region;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.State;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Views;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the actual future renderer's pure builder/planner; no assets, GL, product admission, or game. */
public final class HologramArchiveTileMeshRegression {

    private static final int EAST = 5, WEST = 4, UP = 1, ADMIT_ONLY_STONE = 1 << 1;

    private HologramArchiveTileMeshRegression() {}

    public static void main(String[] args) throws IOException {
        run();
    }

    public static void run() throws IOException {
        seamsAndCamera();
        candidateBudget();
        String existing = System.getProperty("gtmb.archiveRegion.five");
        if (existing != null && !existing.isEmpty()) five(Paths.get(existing));
    }

    private static void seamsAndCamera() throws IOException {
        byte[] data = tiny();
        Memory input = new Memory(data);
        Region a = new Region(0, 0, 0), b = new Region(1, 0, 0), gap = new Region(2, 0, 0);
        List<Region> both = Arrays.asList(a, b);
        Views owner = new Views();
        try (var source = HologramArchiveRegionSource.take(owner, input, sha256().digest(Arrays.copyOf(data, 168)));
            var planner = new HologramArchiveCameraTiles(owner)) {
            HologramEditorCameraFrame original = pose(7.5, 7.5, 7.5, .0, 1, 128);
            Frame pendingA = planner.plan(original, both, 1, ALL_LAYERS, ADMIT_ONLY_STONE);
            SimpleAssert.eq(a, pendingA.enqueue.view.region, "camera picks closest visible region");
            SimpleAssert.eq(0, input.pageReads, "camera planning itself performs no file reads");
            Frame sameWhilePending = planner
                .plan(pose(7.5, 7.5, 7.5, .2, 1.1, 128), both, 1, ALL_LAYERS, ADMIT_ONLY_STONE);
            SimpleAssert.that(sameWhilePending.enqueue == null, "pose update does not restart a wanted tile");

            var result = source.read(pendingA.enqueue.view, budget());
            SimpleAssert.eq(State.READY, result.state, "the region provider proves center+halo");
            SimpleAssert.that(planner.install(pendingA.enqueue.build(result.window)), "exact verified ticket installs");
            var conservative = HologramArchiveTileMesh.build(result.window, ALL_LAYERS, 0, () -> true);
            SimpleAssert.eq(18, conservative.bodyCount(), "mere occupied palette codes never occlude");
            SimpleAssert.eq(0, conservative.capCount(EAST), "ordinary body for unproved halo");

            Frame pendingB = planner.plan(original, both, 1, ALL_LAYERS, ADMIT_ONLY_STONE);
            Tile left = tile(pendingB, a);
            SimpleAssert.eq(14, left.mesh.bodyCount(), "only same-layer proved opaque interior suppresses");
            SimpleAssert.eq(1, left.mesh.capCount(EAST), "proved opaque halo is still an optional seam cap");
            int seam = left.mesh.capQuad(EAST, 0);
            SimpleAssert.that(
                HologramArchiveTileMesh.x(seam) == 15 && HologramArchiveTileMesh.y(seam) == 0
                    && HologramArchiveTileMesh.z(seam) == 0
                    && HologramArchiveTileMesh.code(seam) == 1,
                "cap stays in its own center tile and uses its own material");
            SimpleAssert.that(has(left.mesh, 15, 0, 0, WEST, 1), "unproved neighboring quartz does not hide stone");
            SimpleAssert.that(!has(left.mesh, 15, 0, 0, UP, 1), "real opaque same-scene top neighbor culls");
            var drawA = pendingB.beginDraws();
            SimpleAssert.that(drawA.record(left), "render sink confirms left tile this exact frame");
            SimpleAssert.eq(1 << EAST, drawA.neededCaps(left), "pending neighbor cannot open a hole");

            SimpleAssert
                .that(planner.install(build(source, pendingB, budget())), "matching neighbor becomes CPU-ready");
            Frame bothReady = planner.plan(original, both, 1, ALL_LAYERS, ADMIT_ONLY_STONE);
            var draws = bothReady.beginDraws();
            SimpleAssert.that(draws.record(left), "left identity remains resident");
            SimpleAssert.eq(1 << EAST, draws.neededCaps(left), "CPU-ready but not drawn neighbor cannot suppress cap");
            SimpleAssert.that(draws.record(tile(bothReady, b)), "renderer accepts the same-frame right tile");
            SimpleAssert.eq(0, draws.neededCaps(left), "only matching complete drawn neighbor suppresses cap");
            SimpleAssert.that(!drawA.record(left), "old pose frame cannot mint a current draw");

            int physical = input.pageReads;
            Frame changedPose = planner
                .plan(pose(23.5, 7.5, 7.5, -.42, .9, 128), both, 1, ALL_LAYERS, ADMIT_ONLY_STONE);
            SimpleAssert.that(
                changedPose.pose != bothReady.pose && changedPose.enqueue == null
                    && tile(changedPose, a) == left
                    && tile(changedPose, b) == tile(bothReady, b),
                "rotation/pan/zoom changes matrix only, reuses the exact resident meshes");
            SimpleAssert.eq(physical, input.pageReads, "resident pose-only frame performs zero I/O");

            Frame layerWork = planner.plan(original, Collections.singletonList(a), 1, 0, ADMIT_ONLY_STONE);
            SimpleAssert.that(
                planner.install(build(source, layerWork, new Budget(0, 0))),
                "layer change reuses raw pages for a new CPU mesh");
            Frame layer = planner.plan(original, Collections.singletonList(a), 1, 0, ADMIT_ONLY_STONE);
            var sliced = tile(layer, a).mesh;
            SimpleAssert.eq(10, sliced.bodyCount(), "only chosen Y cells participate");
            SimpleAssert.eq(256, sliced.cellChecks, "only one horizontal plane tested");
            SimpleAssert.eq(8, sliced.neighborChecks, "hidden vertical neighbors are never queried");
            SimpleAssert.that(has(sliced, 15, 0, 0, UP, 1), "same stone above must not hide filtered face");

            Frame oldTexture = planner.plan(original, both, 2, ALL_LAYERS, ADMIT_ONLY_STONE);
            Built oldBuilt = build(source, oldTexture, new Budget(0, 0));
            Frame newTexture = planner.plan(original, both, 3, ALL_LAYERS, ADMIT_ONLY_STONE);
            SimpleAssert.that(
                !planner.install(oldBuilt) && oldTexture.beginDraws() == null && newTexture.ready == 0,
                "texture change cannot accept an old CPU mesh, even reusing same block rows");
            try (var other = new HologramArchiveCameraTiles(new Views())) {
                SimpleAssert.eq(
                    a,
                    other.plan(original, both, 2, ALL_LAYERS, ADMIT_ONLY_STONE).enqueue.view.region,
                    "foreign source can have identical region and texture values");
                SimpleAssert.that(!other.install(oldBuilt), "strict source identity, never only by coordinates");
            }

            Frame retarget = planner.plan(original, Collections.singletonList(gap), 3, ALL_LAYERS, ADMIT_ONLY_STONE);
            SimpleAssert.that(
                retarget.enqueue != null && !planner.install(build(source, newTexture, new Budget(0, 0))),
                "retarget drops the previous outstanding worker");
            SimpleAssert.that(
                planner.install(build(source, retarget, new Budget(0, 0))),
                "directory-proved gap is known without raw page work");
            Frame gapReady = planner.plan(original, Collections.singletonList(gap), 3, ALL_LAYERS, ADMIT_ONLY_STONE);
            SimpleAssert.eq(0, tile(gapReady, gap).mesh.cellChecks, "no fake geometry or halo for absent tile");
            owner.retire();
            SimpleAssert.that(
                gapReady.beginDraws() == null && planner.plan(original, both, 3, ALL_LAYERS, ADMIT_ONLY_STONE) == null,
                "retired exact owner cannot present a resident tile");
        }
        SimpleAssert.that(!input.isOpen(), "trusted reader closes exact source");
    }

    private static void candidateBudget() {
        try (var plan = new HologramArchiveCameraTiles(new Views())) {
            HologramEditorCameraFrame pose = pose(7.5, 0, 0, 0, 1, 8192);
            List<Region> candidates = new ArrayList<>();
            for (int x = 0; x < HologramArchiveCameraTiles.MAX_CANDIDATES; x++) candidates.add(new Region(x, 0, 0));
            Frame frame = plan.plan(pose, candidates, 1, ALL_LAYERS, 0);
            SimpleAssert.that(
                frame.capacityLimited && frame.ready == 0 && frame.enqueue != null,
                "bounded viewport can truthfully be capacity-limited, with just one pending tile");
            SimpleAssert.eq(HologramArchiveCameraTiles.MAX_RESIDENT, frame.requested, "deterministic resident cap");
            Frame offscreen = plan.plan(
                pose(7.5, 0, 0, 0, 1, 128),
                Collections.singletonList(new Region(10_000, 0, 0)),
                1,
                ALL_LAYERS,
                0);
            SimpleAssert.that(
                offscreen.enqueue == null && offscreen.requested == 0,
                "outside transformed viewport has no file or mesh work");
            Frame filtered = plan.plan(pose, Collections.singletonList(new Region(0, -1, 0)), 1, 0, 0);
            SimpleAssert.that(
                filtered.enqueue == null && filtered.requested == 0,
                "tile outside exact Y layer is not scheduled");
        }
    }

    private static void five(Path existing) throws IOException {
        SimpleAssert.eq(11_341_112L, Files.size(existing), "exact existing Five bytes");
        MessageDigest raw = sha256();
        try (InputStream in = Files.newInputStream(existing)) {
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) != -1;) raw.update(buffer, 0, n);
        }
        SimpleAssert.that(
            MessageDigest
                .isEqual(hex("7a0db465717b4c1003e0e9e0faa40a7fc67621e837b94aaef61c23f7ae36880d"), raw.digest()),
            "no substitute archive");
        byte[] manifest = hex("07665fd32a1bd5dd667a7997f21e6ba0345bd18b6c888599a824e1bf525b1163");
        Region a = new Region(-13, -1, -9), b = new Region(-12, -1, -9), gap = new Region(-8, -1, -9);
        List<Region> both = Arrays.asList(a, b);
        Views owner = new Views();
        try (var source = HologramArchiveRegionSource.take(owner, Files.newByteChannel(existing), manifest);
            var planner = new HologramArchiveCameraTiles(owner)) {
            HologramEditorCameraFrame pose = pose(-200.5, -8.5, -136.5, 0, 1, 128);
            // Positive opacity bits here simulate a future renderer's verified per-material proof; no assets in test.
            int hypotheticalFullOpaque = 0b1110;
            Frame aa = planner.plan(pose, both, 1, ALL_LAYERS, hypotheticalFullOpaque);
            SimpleAssert.eq(a, aa.enqueue.view.region, "negative world coordinates choose the actual Five section");
            Budget cold = budget();
            SimpleAssert.that(planner.install(build(source, aa, cold)), "Five center + halo mesh installed");
            SimpleAssert.eq(6, cold.pageReads(), "only six existing pages for first real tile");
            SimpleAssert.eq(23_552, cold.rowVisits(), "no whole Five builder scan");

            Frame bb = planner.plan(pose, both, 1, ALL_LAYERS, hypotheticalFullOpaque);
            var aTile = tile(bb, a);
            SimpleAssert.eq(4096, aTile.mesh.cellChecks, "fixed-size Five CPU lattice");
            SimpleAssert.eq(24_576, aTile.mesh.neighborChecks, "bounded Five neighbor topology");
            SimpleAssert.that(aTile.mesh.capCount(EAST) > 0, "verified adjacent Five tile provides the east seam");
            Budget near = budget();
            SimpleAssert.that(planner.install(build(source, bb, near)), "Five adjacent tile is eligible");
            SimpleAssert.eq(4, near.pageReads(), "adjacent Five retains overlapping pages");
            SimpleAssert.eq(13_632, near.rowVisits(), "adjacent Five row bound");
            Frame motion = planner
                .plan(pose(-197.5, -8.5, -136.5, .13, 1.07, 128), both, 1, ALL_LAYERS, hypotheticalFullOpaque);
            SimpleAssert.that(motion.enqueue == null && tile(motion, a) == aTile, "Five pose-only avoids rebuilding");

            Frame missing = planner.plan(pose, Collections.singletonList(gap), 1, ALL_LAYERS, hypotheticalFullOpaque);
            SimpleAssert
                .that(planner.install(build(source, missing, new Budget(0, 0))), "actual module gap uses no bytes");
            Frame clear = planner.plan(pose, Collections.singletonList(gap), 1, ALL_LAYERS, hypotheticalFullOpaque);
            SimpleAssert.eq(0, tile(clear, gap).mesh.bodyCount(), "no fake face in an actual Five module gap");
        }
    }

    private static HologramEditorCameraFrame pose(double x, double y, double z, double yaw, double zoom,
        double extent) {
        return new HologramEditorCameraFrame(0, 0, 320, 210, yaw, .38, zoom, x, y, z, extent);
    }

    private static Budget budget() {
        return new Budget(HologramArchiveRegionSource.MAX_PAGE_READS, HologramArchiveRegionSource.MAX_ROW_VISITS);
    }

    private static Built build(HologramArchiveRegionSource source, Frame frame, Budget budget) throws IOException {
        return frame.enqueue.build(source.read(frame.enqueue.view, budget).window);
    }

    private static Tile tile(Frame frame, Region region) {
        for (int i = 0; i < frame.ready; i++) if (frame.tile(i).region.equals(region)) return frame.tile(i);
        throw new AssertionError("Archive tile absent from accepted frame");
    }

    private static boolean has(HologramArchiveTileMesh mesh, int x, int y, int z, int side, int code) {
        for (int i = 0; i < mesh.bodyCount(); i++) {
            int quad = mesh.bodyQuad(i);
            if (HologramArchiveTileMesh.x(quad) == x && HologramArchiveTileMesh.y(quad) == y
                && HologramArchiveTileMesh.z(quad) == z
                && HologramArchiveTileMesh.side(quad) == side
                && HologramArchiveTileMesh.code(quad) == code) return true;
        }
        return false;
    }

    private static byte[] tiny() {
        byte[] left = ByteBuffer.allocate(12)
            .putInt(14 << 24 | 1)
            .putInt(15 << 24)
            .putInt(15 << 24 | 1 << 20)
            .array();
        byte[] right = new byte[4];
        byte[] palette = hex("48548e482e250fa98f1cf26a21714f4d14545ebd95d4fd0175b96405eadf338a");
        return ByteBuffer.allocate(184)
            .putInt(0x47545047)
            .putInt(1)
            .putLong(4)
            .putInt(2)
            .putInt(3)
            .put(palette)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .putInt(3)
            .putLong(168)
            .put(sha256().digest(left))
            .putInt(1)
            .putInt(0)
            .putInt(0)
            .putInt(1)
            .putLong(180)
            .put(sha256().digest(right))
            .put(left)
            .put(right)
            .array();
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++)
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class Memory implements SeekableByteChannel {

        final byte[] bytes;
        private int position;
        private boolean open = true;
        int pageReads;

        Memory(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            requireOpen();
            if (position == bytes.length) return -1;
            int count = Math.min(dst.remaining(), bytes.length - position);
            if (position >= 168) pageReads++;
            dst.put(bytes, position, count);
            position += count;
            return count;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long next) throws IOException {
            requireOpen();
            if (next < 0 || next > bytes.length) throw new IOException("Invalid fixture seek");
            position = (int) next;
            return this;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        private void requireOpen() throws IOException {
            if (!open) throw new ClosedChannelException();
        }
    }
}
