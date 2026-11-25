// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

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
import java.util.Arrays;

import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Budget;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Region;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Result;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.State;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Views;
import com.miaokatze.gtmb.hologram.HologramArchiveGeometryReader;
import com.miaokatze.gtmb.hologram.HologramArchiveGeometryReader.Material;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** No game, assets, or UI. The mandatory input is a tiny in-memory archive; Five must already exist if configured. */
public final class HologramArchiveRegionSourceRegression {

    private static final int HEADER = 56, ENTRY = 56;
    private static final String PALETTE = "48548e482e250fa98f1cf26a21714f4d14545ebd95d4fd0175b96405eadf338a";
    private static final String FIVE = "7a0db465717b4c1003e0e9e0faa40a7fc67621e837b94aaef61c23f7ae36880d";
    private static final String FIVE_MANIFEST = "07665fd32a1bd5dd667a7997f21e6ba0345bd18b6c888599a824e1bf525b1163";

    private HologramArchiveRegionSourceRegression() {}

    public static void main(String[] args) throws IOException {
        run();
    }

    public static void run() throws IOException {
        exactSmallWork();
        staleAndCorruptWork();
        String existingFive = System.getProperty("gtmb.archiveRegion.five");
        if (existingFive != null && !existingFive.isEmpty()) realFive(Paths.get(existingFive));
    }

    private static void exactSmallWork() throws IOException {
        Memory input = new Memory(small());
        Views owner = new Views();
        Region center = new Region(0, 0, 0), east = new Region(1, 0, 0);
        var a = owner.request(center, 1);
        try (var source = HologramArchiveRegionSource.take(owner, input, manifest(input.bytes, 3))) {
            SimpleAssert.eq(0, input.payloadReads, "only trusted header and directory open");
            Budget first = new Budget(1, 2);
            SimpleAssert.eq(State.WAITING_BUDGET, source.read(a, first).state, "no incomplete halo is published");
            counted(first, 1, 2);
            Budget second = new Budget(1, 1);
            SimpleAssert.eq(State.WAITING_BUDGET, source.read(a, second).state, "continue without rereading center");
            counted(second, 1, 1);
            Budget third = new Budget(1, 1);
            Result complete = source.read(a, third);
            counted(third, 1, 1);
            SimpleAssert.that(
                complete.state == State.READY && owner.accepts(complete.window),
                "only the fully proved center+faces is current");
            SimpleAssert.eq(2, complete.window.centerRows(), "center renders only its two rows, not the halo");
            SimpleAssert.eq(
                Material.GLOWSTONE,
                HologramArchiveGeometryReader.material(complete.window.code(2, 0, 2)),
                "trusted exact palette order");
            SimpleAssert.eq(1, complete.window.code(-1, 1, 2), "negative X halo uses the left section's right face");
            SimpleAssert.eq(2, complete.window.code(16, 1, 2), "positive X halo uses the right section's left face");
            SimpleAssert.eq(0, complete.window.code(4, 16, 5), "directory-proved absent upper neighbor");
            SimpleAssert.eq(3, input.payloadReads, "physical test channel sees one read for each occupied page");
            SimpleAssert.that(
                owner.request(center, 1) == a,
                "camera-only motion that stays in the same region " + "does not create another geometry request");
            Budget zero = new Budget(0, 0);
            SimpleAssert.that(source.read(a, zero).window == complete.window, "same view does zero geometry work");
            counted(zero, 0, 0);

            var b = owner.request(east, 1);
            SimpleAssert.that(
                !owner.accepts(complete.window) && source.read(a, zero).state == State.STALE,
                "old spatial request does not publish");
            Result reused = source.read(b, zero);
            SimpleAssert.that(
                reused.state == State.READY && reused.window.code(-1, 1, 2) == 1,
                "adjacent center and authenticated halo can reuse raw pages");
            var refreshed = owner.request(east, 2);
            SimpleAssert.that(
                !owner.accepts(reused.window) && owner.request(east, 1) == null,
                "texture reload rejects earlier results and cannot regress");
            Result afterReload = source.read(refreshed, zero);
            SimpleAssert.that(
                owner.accepts(afterReload.window) && !new Views().accepts(afterReload.window),
                "same raw geometry is allowed only in the current exact texture/source owner");
            SimpleAssert.eq(3, input.payloadReads, "texture reload needs no raw page I/O");
            var empty = owner.request(new Region(10, 0, 0), 2);
            Result gap = source.read(empty, zero);
            SimpleAssert.that(
                owner.accepts(gap.window) && gap.window.centerRows() == 0 && gap.window.code(0, 0, 0) == 0,
                "empty region does not schedule adjacent payloads");
            owner.retire();
            SimpleAssert.that(
                !owner.accepts(gap.window) && source.read(empty, zero).state == State.STALE
                    && owner.request(center, 3) == null,
                "pane/source retirement is irreversible");
        }
        SimpleAssert.that(!input.isOpen(), "worker closes its exact owned handle");
    }

    private static void staleAndCorruptWork() throws IOException {
        Memory input = new Memory(small());
        Views owner = new Views();
        var a = owner.request(new Region(0, 0, 0), 1);
        try (var source = HologramArchiveRegionSource.take(owner, input, manifest(input.bytes, 3))) {
            input.nextPayload = () -> owner.request(new Region(1, 0, 0), 2);
            Budget work = fullBudget();
            SimpleAssert.eq(State.STALE, source.read(a, work).state, "request changes inside an actual page read");
            counted(work, 1, 2);
            SimpleAssert.eq(1, input.payloadReads, "an old request starts no subsequent halo reads");
        }

        Memory corrupt = new Memory(small());
        byte[] trusted = manifest(corrupt.bytes, 3);
        corrupt.bytes[corrupt.bytes.length - 1] ^= 1; // Damage actual halo, not its trusted directory entry.
        Views rejected = new Views();
        var view = rejected.request(new Region(0, 0, 0), 1);
        try (var source = HologramArchiveRegionSource.take(rejected, corrupt, trusted)) {
            Budget work = fullBudget();
            expectIo(() -> source.read(view, work));
            counted(work, 3, 4);
            SimpleAssert.that(
                !corrupt.isOpen() && !rejected.current(view),
                "failed halo is not air, no partially accepted result, exact handle retired");
        }

        Memory wrong = new Memory(small());
        byte[] digest = manifest(wrong.bytes, 3);
        digest[0] ^= 1;
        expectIo(() -> HologramArchiveGeometryReader.take(wrong, digest));
        SimpleAssert.that(!wrong.isOpen() && wrong.payloadReads == 0, "bad trusted manifest closes before any payload");
        Memory otherPalette = new Memory(small());
        otherPalette.bytes[24] ^= 1;
        expectIo(() -> HologramArchiveGeometryReader.take(otherPalette, manifest(otherPalette.bytes, 3)));
        SimpleAssert.that(!otherPalette.isOpen() && otherPalette.payloadReads == 0, "unreviewed palette is refused");
    }

    private static void realFive(Path existing) throws IOException {
        SimpleAssert.eq(11_341_112L, Files.size(existing), "fixture preflight, not provider work");
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(existing)) {
            byte[] bytes = new byte[16384];
            for (int n; (n = in.read(bytes)) != -1;) digest.update(bytes, 0, n);
        }
        SimpleAssert.that(MessageDigest.isEqual(hex(FIVE), digest.digest()), "no generated or replacement Five");
        Views owner = new Views();
        try (var source = HologramArchiveRegionSource.take(owner, Files.newByteChannel(existing), hex(FIVE_MANIFEST))) {
            Budget cold = fullBudget();
            Result a = source.read(owner.request(new Region(-13, -1, -9), 1), cold);
            SimpleAssert.that(owner.accepts(a.window) && a.window.centerRows() == 4096, "exact Five first region");
            counted(cold, 6, 23_552);
            Budget adjacent = fullBudget();
            Result b = source.read(owner.request(new Region(-12, -1, -9), 1), adjacent);
            SimpleAssert.that(
                owner.accepts(b.window) && b.window.centerRows() == 4096 && b.window.code(-1, 0, 0) == 1,
                "same tower shares the solid bottom face");
            counted(adjacent, 4, 13_632);
            Budget gap = new Budget(0, 0);
            SimpleAssert.eq(
                0,
                source.read(owner.request(new Region(-8, -1, -9), 1), gap).window.centerRows(),
                "real module gap does not read body rows");
            counted(gap, 0, 0);
        }
        try (var source = HologramArchiveRegionSource
            .take(owner = new Views(), Files.newByteChannel(existing), hex(FIVE_MANIFEST))) {
            Budget edge = fullBudget();
            Result border = source.read(owner.request(new Region(-9, -1, -9), 1), edge);
            SimpleAssert.that(
                owner.accepts(border.window) && border.window.centerRows() == 4096
                    && border.window.code(15, 0, 0) == 1
                    && border.window.code(16, 0, 0) == 0,
                "last occupied tower row exposes its face to the proved module gap");
            counted(edge, 5, 18_944);
        }
    }

    private static Budget fullBudget() {
        return new Budget(HologramArchiveRegionSource.MAX_PAGE_READS, HologramArchiveRegionSource.MAX_ROW_VISITS);
    }

    private static void counted(Budget b, int pages, int rows) {
        SimpleAssert.eq(pages, b.pageReads(), "charged page reads");
        SimpleAssert.eq(rows, b.rowVisits(), "charged row visits");
    }

    @FunctionalInterface
    private interface Work {

        void run() throws IOException;
    }

    private static void expectIo(Work work) {
        try {
            work.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected archive refusal");
    }

    private static byte[] small() {
        int[][] rows = { { row(15, 1, 2, 0) }, { row(2, 0, 2, 2), row(15, 1, 2, 0) }, { row(0, 1, 2, 1) } };
        int start = HEADER + ENTRY * rows.length;
        ByteBuffer archive = ByteBuffer.allocate(start + 4 * Integer.BYTES);
        archive.putInt(0x47545047)
            .putInt(1)
            .putLong(4)
            .putInt(3)
            .putInt(3)
            .put(hex(PALETTE));
        int next = start;
        for (int i = 0; i < rows.length; i++) {
            ByteBuffer page = ByteBuffer.allocate(rows[i].length * Integer.BYTES);
            for (int packed : rows[i]) page.putInt(packed);
            archive.putInt(i - 1)
                .putInt(0)
                .putInt(0)
                .putInt(rows[i].length)
                .putLong(next)
                .put(sha256().digest(page.array()));
            System.arraycopy(page.array(), 0, archive.array(), next, page.capacity());
            next += page.capacity();
        }
        return archive.array();
    }

    private static int row(int x, int y, int z, int material) {
        return (x << 24) | (y << 20) | (z << 16) | material;
    }

    private static byte[] manifest(byte[] bytes, int pages) {
        return sha256().digest(Arrays.copyOf(bytes, HEADER + ENTRY * pages));
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class Memory implements SeekableByteChannel {

        private final byte[] bytes;
        private boolean open = true;
        private int position, payloadReads;
        private Runnable nextPayload;

        Memory(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) throw new ClosedChannelException();
            if (position >= bytes.length) return -1;
            boolean payload = position >= HEADER + 3 * ENTRY;
            int count = Math.min(target.remaining(), bytes.length - position);
            target.put(bytes, position, count);
            position += count;
            if (payload) {
                payloadReads++;
                Runnable action = nextPayload;
                nextPayload = null;
                if (action != null) action.run();
            }
            return count;
        }

        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long offset) throws IOException {
            if (!open) throw new ClosedChannelException();
            position = Math.toIntExact(offset);
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
    }
}
