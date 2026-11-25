// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramArchiveTileMesh.ALL_LAYERS;
import static com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.BODY;

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
import java.util.concurrent.atomic.AtomicReference;

import com.miaokatze.gtmb.client.hologram.HologramArchiveCameraTiles.Frame;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Budget;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Region;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Views;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Backend;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Report;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Slice;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Status;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Same submission scheduler with a recording backend: proves work bounds/ownership, not OpenGL or pixels. */
public final class HologramArchiveTileSubmissionRegression {

    private static final Region A = new Region(0, 0, 0), B = new Region(1, 0, 0);
    private static final int EAST = 5, WEST = 4, PROVED_STONE_FOR_FIXTURE = 1 << 1;

    private HologramArchiveTileSubmissionRegression() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        run();
    }

    public static void run() throws IOException, InterruptedException {
        seamsAndRetirement();
        capacity();
        sharedFrameBudgetAndReleaseFairness();
        revokesAtPhysicalBoundaries();
        String file = System.getProperty("gtmb.archiveRegion.five");
        if (file != null && !file.isEmpty()) five(Paths.get(file));
    }

    private static void seamsAndRetirement() throws IOException, InterruptedException {
        byte[] sample = fixture(new int[][] { { 15 << 24 }, { 0, 15 << 24 }, { 0 } });
        try (var rig = new Rig(new Memory(sample), manifest(sample, 3))) {
            List<Region> chosen = Arrays.asList(A, B);
            HologramEditorCameraFrame initial = pose(7.5, 0, 0, 0);
            Report result = null;
            boolean earlyCap = false;
            for (int i = 0; i < 7; i++) {
                Frame f = rig.plan(initial, chosen, 1, ALL_LAYERS, PROVED_STONE_FOR_FIXTURE);
                result = rig.draw(f, 1);
                rig.assertCharged(result);
                if (result.handles < 5 && rig.backend.drew(A, EAST)) earlyCap = true;
            }
            SimpleAssert.that(earlyCap, "CPU-ready but partially uploaded neighbor does not open the seam");
            SimpleAssert.eq(5, result.handles, "two bodies plus three independently uploaded caps");
            SimpleAssert.eq(Status.SUBMITTED, result.status, "submitted commands, not pixels");
            SimpleAssert.that(!rig.backend.drew(A, EAST) && !rig.backend.drew(B, WEST) && rig.backend.drew(B, EAST),
                "only exact same-frame drawn neighbor can suppress; absent third tile retains east cap");

            rig.backend.fail(B, BODY);
            Frame both = rig.plan(initial, chosen, 1, ALL_LAYERS, PROVED_STONE_FOR_FIXTURE);
            result = rig.draw(both, 1);
            SimpleAssert.eq(Status.BACKEND_REFUSED, result.status, "false body submission is not drawn");
            SimpleAssert.that(rig.backend.drew(A, EAST) && !rig.backend.drew(B, BODY),
                "failed body preserves neighboring cap");

            rig.backend.fail(B, EAST);
            result = rig.draw(both, 1);
            SimpleAssert.eq(Status.BACKEND_REFUSED, result.status, "false cap also disqualifies its tile");
            SimpleAssert.that(rig.backend.drew(B, BODY) && !rig.backend.drew(B, EAST) && rig.backend.drew(A, EAST),
                "cap failure rechecks earlier suppressions within worst-case reservation");
            rig.assertCharged(result);
            rig.backend.fail(null, BODY);

            Frame turned = rig.plan(pose(8, 0, 0, .2), chosen, 1, ALL_LAYERS, PROVED_STONE_FOR_FIXTURE);
            int polls = rig.polls;
            SimpleAssert.eq(Status.STALE, rig.draw(both, 1).status, "old pose cannot acquire backend");
            SimpleAssert.eq(polls, rig.polls, "no backend lookup for stale frame");
            result = rig.draw(turned, 1);
            SimpleAssert.that(result.uploadCalls == 0 && result.handles == 5 && turned.pose == rig.backend.lastPose,
                "pose-only draw reuses handles with exact live transform");
            var wrongThread = new AtomicReference<Throwable>();
            Thread offOwner = new Thread(() -> {
                try {
                    rig.submission.submit(rig.stamp, turned, () -> rig.textureEpoch);
                } catch (Throwable failure) {
                    wrongThread.set(failure);
                }
            });
            polls = rig.polls;
            offOwner.start();
            offOwner.join();
            SimpleAssert.that(wrongThread.get() instanceof IllegalStateException && rig.polls == polls,
                "wrong thread never obtains backend");

            Fake old = rig.backend;
            rig.backend = null;
            result = rig.draw(turned, 1);
            SimpleAssert.that(result.status == Status.UNSUPPORTED && result.handles == 5 && result.debt == 0
                && result.uploadCalls == 0 && result.drawCalls == 0 && result.deleteCalls == 0,
                "temporary unsupported draw has no backend work or false context loss");
            rig.backend = old;
            polls = rig.polls;
            result = rig.draw(turned, 2);
            SimpleAssert.that(result.status == Status.STALE && result.debt == 5 && polls == rig.polls,
                "texture change retires old handles without issuing any GL-like call");
            rig.backend = null;
            result = rig.pump();
            SimpleAssert.that(result.status == Status.UNSUPPORTED && result.deleteCalls == 0 && result.debt == 5,
                "no calls against unavailable context");
            rig.backend = new Fake();
            result = rig.pump();
            SimpleAssert.that(result.status == Status.DELETION_DEFERRED && result.deleteCalls == 0 && result.debt == 5,
                "other supported context is not confirmed destruction of old context");
            rig.backend = old;
            result = rig.pump();
            SimpleAssert.that(result.deleteCalls == 2 && result.debt == 3,
                "when exact context returns, bounded release only");
            rig.submission.forgetDestroyedContext(old.context);
            result = rig.pump();
            SimpleAssert.that(result.handles == 0 && result.debt == 0 && old.deleted == 2,
                "independent confirmed teardown forgets remaining handles without unauthorized release");

            rig.backend = new Fake();
            Frame fresh = rig.plan(initial, chosen, 2, ALL_LAYERS, PROVED_STONE_FOR_FIXTURE);
            for (int i = 0; i < 3; i++) {
                fresh = rig.plan(initial, chosen, 2, ALL_LAYERS, PROVED_STONE_FOR_FIXTURE);
                rig.draw(fresh, 2);
            }
            var foreign = new HologramArchiveCameraTiles(new Views());
            try {
                Frame rogue = foreign.plan(initial, chosen, 2, ALL_LAYERS, PROVED_STONE_FOR_FIXTURE);
                polls = rig.polls;
                SimpleAssert.that(rig.draw(rogue, 2).status == Status.STALE && polls == rig.polls, "foreign source denied");
            } finally {
                foreign.close();
            }
            Fake previous = rig.backend;
            rig.backend = new Fake();
            result = rig.draw(fresh, 2);
            SimpleAssert.that(result.debt > 0 && result.deleteCalls == 0 && previous.deleted == 0
                && result.uploadCalls == 1 && result.drawCalls == 0,
                "foreign current context does not draw/delete previous handles or publish incomplete replacements");
            rig.views.retire();
            result = rig.draw(fresh, 2);
            SimpleAssert.that(result.status == Status.STALE && result.debt > 0 && result.drawCalls == 0,
                "retired exact source cannot submit even resident GPU state");
        }
    }

    private static void capacity() throws IOException {
        int[] full = new int[4_096];
        for (int i = 0; i < full.length; i++) full[i] = i << 16;
        byte[] sample = fixture(new int[][] { full });
        try (var rig = new Rig(new Memory(sample), manifest(sample, 1))) {
            HologramEditorCameraFrame pose = pose(7.5, 0, 0, 0);
            List<Region> only = Collections.singletonList(A);
            rig.plan(pose, only, 1, ALL_LAYERS, 0);
            Frame tooMany = rig.plan(pose, only, 1, ALL_LAYERS, 0);
            for (int i = 0; i < 2; i++) {
                Report result = rig.draw(tooMany, 1);
                SimpleAssert.that(result.status == Status.CAPACITY_LIMITED && result.uploadCalls == 0
                    && result.drawCalls == 0 && result.handles == 0,
                    "oversized unproved full view fails closed instead of never-ending partial upload");
            }
            rig.plan(pose, only, 1, 0, 0);
            Frame oneLayer = rig.plan(pose, only, 1, 0, 0);
            for (int i = 0; i < 3; i++) {
                Report result = rig.draw(oneLayer, 1);
                SimpleAssert.eq(1, result.uploadCalls, "one slice at most in each frame");
                SimpleAssert.eq(512, result.uploadQuads, "exact slice bound");
                if (i < 2) SimpleAssert.eq(0, result.drawCalls, "no draw from incomplete resident tile");
                else {
                    SimpleAssert.eq(3, result.drawCalls, "complete layer only");
                    SimpleAssert.eq(1536, result.drawQuads, "exact body/slice work, remains under total frame cap");
                }
                rig.assertCharged(result);
            }
        }
    }

    private static void sharedFrameBudgetAndReleaseFairness() throws IOException {
        byte[] data = oneDenseTile();
        try (var rig = new Rig(new Memory(data), manifest(data, 1))) {
            Frame frame = fillThree(rig);
            rig.submission.bind(null);
            rig.submission.bind(rig.planner);
            rig.backend.refuseReleaseBefore = 1024;
            long same = ++rig.stamp;
            Report first = rig.submission.pumpRetired(same);
            SimpleAssert.that(first.deleteCalls == 2 && first.debt == 3 && rig.backend.deleted == 0,
                "first two handles refuse exact-context release without losing debt");
            Report submitted = rig.drawAt(same, frame, 1);
            SimpleAssert.that(submitted.uploadCalls == 1 && submitted.deleteCalls == 0 && submitted.debt == 3,
                "submit shares the already charged cleanup budget with pump");
            int polls = rig.polls;
            Report duplicate = rig.drawAt(same, frame, 1);
            SimpleAssert.that(duplicate.status == Status.FRAME_SPENT && duplicate.uploadCalls == 0
                && duplicate.drawCalls == 0 && rig.polls == polls, "same physical stamp cannot submit twice");
            Report samePump = rig.submission.pumpRetired(same);
            SimpleAssert.that(samePump.deleteCalls == 0 && samePump.debt == 3 && rig.polls == polls,
                "duplicate pump cannot increase physical delete budget");
            SimpleAssert.eq(Status.STALE, rig.drawAt(same - 1, frame, 1).status, "old physical stamp rejected");

            Report after = rig.pump();
            SimpleAssert.that(after.deleteCalls == 2 && rig.backend.deleted == 1 && after.debt == 2,
                "rotated refused items cannot starve a later healthy handle on next physical frame");
            SimpleAssert.eq(1024, rig.backend.deletedAt, "the later healthy part really released");
        }
    }

    private static void revokesAtPhysicalBoundaries() throws IOException {
        byte[] data = oneDenseTile();
        try (var rig = new Rig(new Memory(data), manifest(data, 1))) {
            HologramEditorCameraFrame pose = pose(7.5, 0, 0, 0);
            List<Region> only = Collections.singletonList(A);
            rig.plan(pose, only, 1, 0, 0);
            Frame frame = rig.plan(pose, only, 1, 0, 0);
            Fake old = rig.backend;
            Fake foreign = new Fake();
            old.afterUpload = () -> rig.backend = foreign;
            Report uploadLoss = rig.draw(frame, 1);
            SimpleAssert.that(uploadLoss.status == Status.UNSUPPORTED && uploadLoss.uploadCalls == 1
                && uploadLoss.drawCalls == 0 && uploadLoss.debt == 1 && foreign.uploads == 0,
                "returned upload is old context debt, no draw or new-context work");
            SimpleAssert.that(rig.pump().deleteCalls == 0 && foreign.deleted == 0,
                "replacement context cannot release old upload");
            int probes = rig.polls;
            SimpleAssert.that(rig.submission.pumpRetired(rig.stamp).deleteCalls == 0 && rig.polls == probes,
                "duplicate foreign-context pump cannot spin context probes in one physical frame");
            rig.backend = old;
            SimpleAssert.that(rig.pump().deleteCalls == 1 && old.deleted == 1,
                "exact context returns and releases the accepted upload");

            rig.failSupplier = true;
            SimpleAssert.that(rig.draw(frame, 1).status == Status.UNSUPPORTED, "throwing supplier fails closed");
            rig.failSupplier = false;
            old.throwContext = true;
            SimpleAssert.that(rig.draw(frame, 1).status == Status.UNSUPPORTED, "throwing context probe fails closed");
            old.throwContext = false;

            frame = fillThree(rig);
            int priorEnds = old.ends;
            old.afterBegin = () -> {
                rig.backend = foreign;
                old.drawing = false; // The fake backend itself abandons the revoked draw scope.
            };
            Report beginLoss = rig.draw(frame, 1);
            SimpleAssert.that(beginLoss.status == Status.UNSUPPORTED && beginLoss.drawCalls == 0
                && old.ends == priorEnds && beginLoss.debt == 3, "revoked begin cannot draw or end in foreign context");
            rig.backend = old;
            old.afterRelease = () -> rig.backend = foreign;
            Report deleteLoss = rig.pump();
            SimpleAssert.that(deleteLoss.status == Status.UNSUPPORTED && deleteLoss.deleteCalls == 1
                && deleteLoss.debt == 2 && foreign.deleted == 0, "loss in first release forbids a second release");
        }

        try (var rig = new Rig(new Memory(data), manifest(data, 1))) {
            Frame frame = fillThree(rig);
            rig.backend.afterDraw = () -> rig.textureEpoch = 2;
            Report stale = rig.draw(frame, 1);
            SimpleAssert.that(stale.status == Status.STALE && stale.drawCalls == 1 && stale.submittedQuads == 0
                && stale.debt == 3, "fresh atlas generation is checked after every physical draw");
        }

        try (var rig = new Rig(new Memory(data), manifest(data, 1))) {
            Frame frame = fillThree(rig);
            Fake same = rig.backend;
            int priorEnds = same.ends;
            same.afterDraw = rig.views::retire;
            Report stale = rig.draw(frame, 1);
            SimpleAssert.that(stale.status == Status.STALE && stale.drawCalls == 1 && stale.submittedQuads == 0
                && same.ends == priorEnds + 1 && stale.debt == 3,
                "owner invalidation stops body and caps; only restoration in the exact legal context is allowed");
        }
    }

    private static byte[] oneDenseTile() {
        int[] full = new int[4096];
        for (int i = 0; i < full.length; i++) full[i] = i << 16;
        return fixture(new int[][] { full });
    }

    private static Frame fillThree(Rig rig) throws IOException {
        HologramEditorCameraFrame pose = pose(7.5, 0, 0, 0);
        List<Region> only = Collections.singletonList(A);
        rig.plan(pose, only, 1, 0, 0);
        Frame frame = rig.plan(pose, only, 1, 0, 0);
        Report last = null;
        for (int i = 0; i < 3; i++) last = rig.draw(frame, 1);
        SimpleAssert.that(last.status == Status.SUBMITTED && last.handles == 3, "three full bounded slices resident");
        return frame;
    }

    private static void five(Path existing) throws IOException {
        SimpleAssert.eq(11_341_112L, Files.size(existing), "original Five size");
        MessageDigest hash = digest();
        try (InputStream in = Files.newInputStream(existing)) {
            byte[] bytes = new byte[8192];
            for (int n; (n = in.read(bytes)) >= 0;) hash.update(bytes, 0, n);
        }
        SimpleAssert.that(MessageDigest.isEqual(hash.digest(),
            hex("7a0db465717b4c1003e0e9e0faa40a7fc67621e837b94aaef61c23f7ae36880d")), "no substitute Five");
        byte[] manifest = hex("07665fd32a1bd5dd667a7997f21e6ba0345bd18b6c888599a824e1bf525b1163");
        Region a = new Region(-13, -1, -9), b = new Region(-12, -1, -9);
        List<Region> chosen = Arrays.asList(a, b);
        try (var rig = new Rig(Files.newByteChannel(existing), manifest)) {
            HologramEditorCameraFrame pose = pose(-200.5, -8.5, -136.5, 0);
            // Positivity here is fake fixture proof, expressly not runtime atlas-alpha verification.
            int fixtureOpaque = 0b1110;
            Report result = null;
            for (int i = 0; i < 28; i++) {
                Frame f = rig.plan(pose, chosen, 1, ALL_LAYERS, fixtureOpaque);
                result = rig.draw(f, 1);
                rig.assertCharged(result);
            }
            SimpleAssert.that(result.submittedQuads > 0 && rig.backend.drew(a, BODY) && rig.backend.drew(b, BODY)
                && rig.reads == 10 && rig.rows == 37_184,
                "both exact Five bodies accepted by the recorder; work charged only to ten verified pages");
            Frame otherPose = rig.plan(pose(-197.5, -8.5, -136.5, .1), chosen, 1, ALL_LAYERS, fixtureOpaque);
            result = rig.draw(otherPose, 1);
            SimpleAssert.that(result.uploadCalls == 0 && rig.reads == 10 && result.submittedQuads > 0,
                "pose-only change on resident real geometry has no upload or file reads");
        }
    }

    private static final class Rig implements AutoCloseable {

        final Views views = new Views();
        final HologramArchiveRegionSource source;
        final HologramArchiveCameraTiles planner = new HologramArchiveCameraTiles(views);
        Fake backend = new Fake();
        int polls, reads, rows;
        long stamp, textureEpoch;
        boolean failSupplier;
        final HologramArchiveTileSubmission<Handle> submission = new HologramArchiveTileSubmission<>(() -> {
            polls++;
            if (failSupplier) throw new IllegalStateException("fixture supplier unavailable");
            return backend;
        });

        Rig(SeekableByteChannel owned, byte[] manifest) throws IOException {
            source = HologramArchiveRegionSource.take(views, owned, manifest);
            submission.bind(planner);
        }

        Frame plan(HologramEditorCameraFrame pose, List<Region> chosen, long textures, int layer, int opaque)
            throws IOException {
            Frame frame = planner.plan(pose, chosen, textures, layer, opaque);
            if (frame.enqueue != null) {
                Budget budget = new Budget(HologramArchiveRegionSource.MAX_PAGE_READS,
                    HologramArchiveRegionSource.MAX_ROW_VISITS);
                var window = source.read(frame.enqueue.view, budget).window;
                SimpleAssert.that(planner.install(frame.enqueue.build(window)), "fixture CPU tile installed");
                reads += budget.pageReads();
                rows += budget.rowVisits();
            }
            return frame;
        }

        Report draw(Frame frame, long textures) {
            return drawAt(++stamp, frame, textures);
        }

        Report drawAt(long frameStamp, Frame frame, long textures) {
            if (backend != null) backend.reset();
            textureEpoch = textures;
            return submission.submit(frameStamp, frame, () -> textureEpoch);
        }

        Report pump() {
            return submission.pumpRetired(++stamp);
        }

        void assertCharged(Report report) {
            SimpleAssert.that(report.uploadCalls <= 1 && report.uploadQuads <= 512 && report.drawCalls <= 12
                && report.drawQuads <= 6144 && report.deleteCalls <= 2 && report.handles <= 24
                && report.submittedQuads <= report.drawQuads && report.debt <= report.handles,
                "strict upload/draw/deletion/bounded resident limits");
            SimpleAssert.eq(backend.uploads, report.uploadCalls, "upload uses recorder exactly");
            SimpleAssert.eq(backend.draws, report.drawCalls, "false draws are charged too");
        }

        @Override
        public void close() throws IOException {
            submission.bind(null);
            planner.close();
            source.close();
        }
    }

    private static final class Handle {

        final Object context;
        final Slice slice;
        boolean deleted;

        Handle(Object context, Slice slice) {
            this.context = context;
            this.slice = slice;
        }
    }

    private static final class Fake implements Backend<Handle> {

        final Object context = new Object();
        final List<Slice> presented = new ArrayList<>();
        Region failRegion;
        int failStream, uploads, draws, deleted, ends, deletedAt, refuseReleaseBefore;
        boolean drawing, throwContext;
        Runnable afterUpload, afterBegin, afterDraw, afterRelease;
        HologramEditorCameraFrame lastPose;

        void reset() {
            SimpleAssert.that(!drawing, "last begin/end balanced");
            presented.clear();
            uploads = draws = 0;
        }

        void fail(Region region, int stream) {
            failRegion = region;
            failStream = stream;
        }

        boolean drew(Region region, int stream) {
            return presented.stream().anyMatch(s -> s.tile.region.equals(region) && s.stream == stream);
        }

        @Override
        public Object context() {
            if (throwContext) throw new IllegalStateException("fixture context unavailable");
            return context;
        }

        @Override
        public Handle upload(Slice slice) {
            SimpleAssert.that(!drawing && slice.quads >= 1 && slice.quads <= 512, "one safe nonempty GL-like upload");
            slice.quad(slice.quads - 1);
            uploads++;
            Runnable after = afterUpload;
            afterUpload = null;
            if (after != null) after.run();
            return new Handle(context, slice);
        }

        @Override
        public boolean begin(HologramEditorCameraFrame pose) {
            SimpleAssert.that(!drawing, "begin once");
            lastPose = pose;
            drawing = true;
            Runnable after = afterBegin;
            afterBegin = null;
            if (after != null) after.run();
            return true;
        }

        @Override
        public boolean draw(Handle handle, Slice slice) {
            SimpleAssert.that(drawing && handle.context == context && !handle.deleted && handle.slice == slice,
                "exact live handle and current scope");
            draws++;
            if (slice.tile.region.equals(failRegion) && slice.stream == failStream) return false;
            presented.add(slice);
            Runnable after = afterDraw;
            afterDraw = null;
            if (after != null) after.run();
            return true;
        }

        @Override
        public void end() {
            SimpleAssert.that(drawing, "end after begun only");
            drawing = false;
            ends++;
        }

        @Override
        public boolean release(Handle handle) {
            SimpleAssert.that(!drawing && handle.context == context && !handle.deleted,
                "retirement only in the exactly matching context");
            if (handle.slice.first < refuseReleaseBefore) return false;
            handle.deleted = true;
            deleted++;
            deletedAt = handle.slice.first;
            Runnable after = afterRelease;
            afterRelease = null;
            if (after != null) after.run();
            return true;
        }
    }

    private static HologramEditorCameraFrame pose(double x, double y, double z, double yaw) {
        return new HologramEditorCameraFrame(0, 0, 320, 210, yaw, .38, 1, x, y, z, 128);
    }

    private static byte[] fixture(int[][] rows) {
        int total = 0, next = 56 + rows.length * 56;
        for (int[] page : rows) total += page.length;
        byte[] palette = hex("48548e482e250fa98f1cf26a21714f4d14545ebd95d4fd0175b96405eadf338a");
        ByteBuffer data = ByteBuffer.allocate(next + total * 4)
            .putInt(0x47545047).putInt(1).putLong(total).putInt(rows.length).putInt(3).put(palette);
        for (int i = 0; i < rows.length; i++) {
            ByteBuffer page = ByteBuffer.allocate(rows[i].length * 4);
            for (int packed : rows[i]) page.putInt(packed);
            data.putInt(i).putInt(0).putInt(0).putInt(rows[i].length).putLong(next).put(digest().digest(page.array()));
            System.arraycopy(page.array(), 0, data.array(), next, page.capacity());
            next += page.capacity();
        }
        return data.array();
    }

    private static byte[] manifest(byte[] bytes, int pages) {
        return digest().digest(Arrays.copyOf(bytes, 56 + 56 * pages));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static final class Memory implements SeekableByteChannel {

        private final byte[] bytes;
        private int position;
        private boolean open = true;

        Memory(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!open) throw new ClosedChannelException();
            if (position == bytes.length) return -1;
            int n = Math.min(dst.remaining(), bytes.length - position);
            dst.put(bytes, position, n);
            position += n;
            return n;
        }

        @Override
        public int write(ByteBuffer src) { throw new NonWritableChannelException(); }

        @Override
        public long position() { return position; }

        @Override
        public SeekableByteChannel position(long requested) throws IOException {
            if (!open || requested < 0 || requested > bytes.length) throw new IOException("Invalid fixture seek");
            position = (int) requested;
            return this;
        }

        @Override
        public long size() { return bytes.length; }

        @Override
        public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }

        @Override
        public boolean isOpen() { return open; }

        @Override
        public void close() { open = false; }
    }
}
