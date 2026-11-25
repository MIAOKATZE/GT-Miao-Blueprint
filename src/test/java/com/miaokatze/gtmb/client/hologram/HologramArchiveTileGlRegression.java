// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;

import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Budget;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Region;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Views;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileGl.Face;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileGl.Palette;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileGl.Target;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileGl.Transform;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Backend;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Slice;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Status;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exact bridge transform and encoder; recorder commands are not a real OpenGL, atlas, or pixel witness. */
public final class HologramArchiveTileGlRegression {

    private static final int[] NORMAL_AXES = { 1, 1, 2, 2, 0, 0 };

    private HologramArchiveTileGlRegression() {}

    public static void main(String[] args) throws IOException {
        run();
    }

    public static void run() throws IOException {
        vertexOrientationAndTint();
        cameraRelativeTransform();
        exactScopeBeforeRaw();
        String existing = System.getProperty("gtmb.archiveRegion.five");
        if (existing != null && !existing.isEmpty()) realFiveLayer(Paths.get(existing));
    }

    private static void vertexOrientationAndTint() {
        Palette palette = samplePalette();
        for (int side = 0; side < 6; side++) {
            double[][] vertices = new double[4][];
            float[] tint = new float[4];
            int[] n = { 0 };
            HologramArchiveTileGl.encodeQuad(15 << 8 | 2 << 4 | 3 | side << 12 | 1 << 15, palette, (r, g, b, a) -> {
                tint[0] = r;
                tint[1] = g;
                tint[2] = b;
                tint[3] = a;
            }, (x, y, z, u, v) -> vertices[n[0]++] = new double[] { x, y, z, u, v });
            SimpleAssert.eq(4, n[0], "one retained indexed quad per side");
            int axis = NORMAL_AXES[side];
            double center = axis == 0 ? 15 : axis == 1 ? 2 : 3;
            for (double[] point : vertices) {
                for (double component : point)
                    SimpleAssert.that(Double.isFinite(component), "nonfinite positions/UV cannot be accepted");
                near(center + (side % 2 == 0 ? -.5 : .5), point[axis], "voxel-centered signed face plane");
                SimpleAssert.that(
                    point[3] >= .125 && point[3] <= .25 && point[4] >= .5 && point[4] <= .75,
                    "UV stays inside detached atlas sample");
            }
            near(1, tint[3], "tint encoding never claims texel alpha proof");
            near(0.8 * (side == 1 ? 1 : side == 0 ? .65 : .85), tint[0], "matching normal-based shade");
            if (side == 0) {
                near(14.5, vertices[0][0], "bottom first corner X");
                near(2.5, vertices[0][2], "bottom first corner Z");
                near(.125, vertices[0][3], "bottom first U");
                near(.5, vertices[0][4], "bottom first V");
                near(.25, vertices[2][3], "bottom opposite U");
                near(.75, vertices[2][4], "bottom opposite V");
            }
        }
    }

    private static void exactScopeBeforeRaw() {
        Object context = new Object();
        var before = new Target(context, 4, 12, 23, 1720, 960, 51, 62, 600, 420);
        SimpleAssert.that(
            before.same(new Target(context, 4, 12, 23, 1720, 960, 51, 62, 600, 420)),
            "same exact observed viewport permits the next proof step");
        SimpleAssert.that(
            !before.same(null) && !before.same(new Target(new Object(), 4, 12, 23, 1720, 960, 51, 62, 600, 420))
                && !before.same(new Target(context, 5, 12, 23, 1720, 960, 51, 62, 600, 420))
                && !before.same(new Target(context, 4, 12, 23, 1721, 960, 51, 62, 600, 420))
                && !before.same(new Target(context, 4, 12, 23, 1720, 960, 52, 62, 600, 420)),
            "missing proof or changed context, framebuffer, viewport or scissor fails closed");

        try {
            new Face(Double.NaN, .25, .5, .75, 0xffffff);
        } catch (IllegalArgumentException nonfinite) {
            return;
        }
        throw new AssertionError("Nonfinite atlas UV must refuse before encoding");
    }

    private static void cameraRelativeTransform() {
        Region far = new Region(-3_000_000, -1, 2_000_000);
        double x = 16.0 * far.x, y = 16.0 * far.y, z = 16.0 * far.z;
        var first = new HologramEditorCameraFrame(11, 17, 301, 231, .71, -.46, 1.2, x + 3.75, y + 9.5, z + 1.25, 128);
        var next = new HologramEditorCameraFrame(11, 17, 301, 231, -.57, .31, .85, x + 9.125, y - 3.5, z + .25, 128);
        parity(first, far, -.5, 12.5, 15.5);
        parity(next, far, -.5, 12.5, 15.5);
        near(-3.75, new Transform(first, far, 128).x, "subtract large camera origin before mesh float encoding");
        near(-9.125, new Transform(next, far, 128).x, "same tile vertices survive pan with subblock precision");
    }

    private static void parity(HologramEditorCameraFrame camera, Region r, double x, double y, double z) {
        var t = new Transform(camera, r, 128);
        double yaw = Math.toRadians(t.yaw), pitch = Math.toRadians(t.pitch);
        double dx = t.x + x, dy = t.y + y, dz = t.z + z;
        double glX = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        double rz = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
        double glY = dy * Math.cos(pitch) - rz * Math.sin(pitch);
        double glZ = dy * Math.sin(pitch) + rz * Math.cos(pitch);
        double[] expected = camera.project(16.0 * r.x + x, 16.0 * r.y + y, 16.0 * r.z + z);
        near(expected[0], t.screenX + glX * t.scale, "shared camera X");
        near(expected[1], t.screenY - glY * t.scale, "shared flipped camera Y");
        near(expected[2] * t.depthScale, glZ * t.depthScale, "shared signed depth");
    }

    private static void realFiveLayer(Path existing) throws IOException {
        SimpleAssert.eq(11_341_112L, Files.size(existing), "original Five, not substitute input");
        MessageDigest hash = digest();
        try (InputStream in = Files.newInputStream(existing)) {
            byte[] scratch = new byte[8192];
            for (int n; (n = in.read(scratch)) != -1;) hash.update(scratch, 0, n);
        }
        SimpleAssert.that(
            MessageDigest
                .isEqual(hash.digest(), hex("7a0db465717b4c1003e0e9e0faa40a7fc67621e837b94aaef61c23f7ae36880d")),
            "original Five digest");
        byte[] manifest = hex("07665fd32a1bd5dd667a7997f21e6ba0345bd18b6c888599a824e1bf525b1163");
        Views owner = new Views();
        Region tile = new Region(-13, -1, -9);
        try (var source = HologramArchiveRegionSource.take(owner, Files.newByteChannel(existing), manifest);
            var planner = new HologramArchiveCameraTiles(owner)) {
            var camera = new HologramEditorCameraFrame(0, 0, 320, 210, .2, .38, 1, -200.5, -8.5, -136.5, 128);
            var candidates = Collections.singletonList(tile);
            var announced = planner.plan(camera, candidates, 1, -16, 0);
            Budget work = new Budget(
                HologramArchiveRegionSource.MAX_PAGE_READS,
                HologramArchiveRegionSource.MAX_ROW_VISITS);
            var exact = source.read(announced.enqueue.view, work).window;
            SimpleAssert.that(planner.install(announced.enqueue.build(exact)), "exact Five physical geometry source");
            SimpleAssert.eq(6, work.pageReads(), "only center plus existing halo objects");
            SimpleAssert.eq(23_552, work.rowVisits(), "no scan of whole original archive");
            var ready = planner.plan(camera, candidates, 1, -16, 0);
            Recorder backend = new Recorder(samplePalette());
            HologramArchiveTileSubmission<Slice> submit = new HologramArchiveTileSubmission<>(() -> backend);
            submit.bind(planner);
            for (int stamp = 1; stamp <= 3; stamp++) {
                var report = submit.submit(stamp, ready, () -> 1);
                SimpleAssert.eq(1, report.uploadCalls, "one real encoder slice per host frame");
                SimpleAssert.eq(512, report.uploadQuads, "exact encoder work per frame");
                if (stamp < 3) SimpleAssert.eq(0, report.drawCalls, "unallocated body does not submit");
                else SimpleAssert.that(
                    report.status == Status.SUBMITTED && report.drawCalls == 3 && report.submittedQuads == 1536,
                    "unproved alpha never hides any selected Five face");
            }
            SimpleAssert.eq(6144, backend.vertices, "actual production encoder emitted three bounded streams");
            parity(camera, tile, 15.5, -.5, .5);
        }
    }

    private static final class Recorder implements Backend<Slice> {

        final Object token = new Object();
        final Palette palette;
        int vertices;

        Recorder(Palette palette) {
            this.palette = palette;
        }

        @Override
        public Object context() {
            return token;
        }

        @Override
        public Slice upload(Slice slice) {
            HologramArchiveTileGl.encode(slice, palette, (r, g, b, a) -> {}, (x, y, z, u, v) -> {
                SimpleAssert.that(
                    x >= -.5 && x <= 15.5
                        && y >= -.5
                        && y <= .5
                        && z >= -.5
                        && z <= 15.5
                        && u >= .125
                        && u <= .25
                        && v >= .5
                        && v <= .75,
                    "finite tile-local geometry/UV stays in its exact selected plane and sample");
                vertices++;
            });
            return slice;
        }

        @Override
        public boolean begin(HologramEditorCameraFrame pose) {
            return true;
        }

        @Override
        public boolean draw(Slice handle, Slice slice) {
            return handle == slice;
        }

        @Override
        public void end() {}

        @Override
        public boolean release(Slice handle) {
            return true;
        }
    }

    private static Palette samplePalette() {
        Face[][] faces = new Face[3][6];
        for (Face[] material : faces) Arrays.fill(material, new Face(.125, .25, .5, .75, 0xcc8844));
        return new Palette(faces);
    }

    private static void near(double expected, double actual, String meaning) {
        if (!Double.isFinite(expected) || !Double.isFinite(actual) || Math.abs(expected - actual) > 1e-5)
            throw new AssertionError(meaning + ": expected " + expected + ", " + actual);
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }
}
