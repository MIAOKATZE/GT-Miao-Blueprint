// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import com.miaokatze.gtmb.hologram.HologramArchiveGeometryReader;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Fail-closed name handling and offline index-only work on one already-owned handle. */
public final class HologramArchiveNamedImportRegression {

    private static final int INDEX_END = 112;

    private HologramArchiveNamedImportRegression() {}

    public static void main(String[] args) throws IOException {
        run();
    }

    public static void run() throws IOException {
        SimpleAssert.that(!HologramArchiveNamedImport.archiveName("old blueprint")
            && !HologramArchiveNamedImport.archiveName("old blueprint.json"), "normal named import stays ordinary");
        for (String name : new String[] { "../a.gtpg", "/a.gtpg", "C:\\a.gtpg", ".gtpg", "a:b.gtpg" }) {
            SimpleAssert.that(HologramArchiveNamedImport.archiveName(name)
                && !HologramArchiveNamedImport.validLeaf(name)
                && HologramArchiveNamedImport.refusal(name).contains("不含路径"), "explicit invalid name never falls back");
        }
        SimpleAssert.that(HologramArchiveNamedImport.validLeaf("十二塔-1.GTPG")
            && HologramArchiveNamedImport.refusal("十二塔-1.GTPG").contains("未导入"),
            "even a valid explicit name gets truthful refusal while safe opener absent");

        byte[] bytes = sample();
        byte[] trustedDirectory = sha256().digest(Arrays.copyOf(bytes, INDEX_END));
        bytes[INDEX_END] = 1; // Intentionally damage the body, not the directory.
        CountingChannel candidate = new CountingChannel(bytes);
        var declared = HologramArchiveGeometryReader.inspectExplicitIndex(candidate, () -> true);
        SimpleAssert.eq(1, declared.declaredPages, "structurally valid declared page count");
        SimpleAssert.eq(1L, declared.declaredCells, "declared count is not verified geometry");
        SimpleAssert.eq(INDEX_END, candidate.readBytes, "read exactly header + directory, not one body byte");
        SimpleAssert.eq(INDEX_END, candidate.furthestByte, "never seek into payload");
        SimpleAssert.eq(1, candidate.closes, "candidate handle closes before detached metadata is returned");

        CountingChannel trusted = new CountingChannel(bytes);
        try (var exact = HologramArchiveGeometryReader.take(trusted, trustedDirectory)) {
            SimpleAssert.eq(INDEX_END, trusted.readBytes, "trusted region source still does not scan the body eagerly");
            expectIo(() -> exact.readPage(0, 0, 0));
        }
        SimpleAssert.eq(bytes.length, trusted.readBytes, "selected region independently checks its body digest");
        SimpleAssert.eq(1, trusted.closes, "trusted source keeps exact handle only until explicit close");

        CountingChannel retired = new CountingChannel(bytes);
        expectIo(() -> HologramArchiveGeometryReader.inspectExplicitIndex(retired, () -> false));
        SimpleAssert.eq(0, retired.readBytes, "retired before opening reads no bytes");
        SimpleAssert.eq(1, retired.closes, "retirement consumes transferred handle");
        CountingChannel changed = new CountingChannel(bytes);
        expectIo(() -> HologramArchiveGeometryReader.inspectExplicitIndex(changed, () -> changed.readBytes == 0));
        SimpleAssert.eq(56, changed.readBytes, "owner loss between header and directory stops");
        SimpleAssert.eq(1, changed.closes, "mid-inspection retirement closes exact handle");

        byte[] invalidDirectory = sample();
        ByteBuffer.wrap(invalidDirectory).putLong(72, INDEX_END + 1L);
        CountingChannel invalid = new CountingChannel(invalidDirectory);
        expectIo(() -> HologramArchiveGeometryReader.inspectExplicitIndex(invalid, () -> true));
        SimpleAssert.eq(INDEX_END, invalid.readBytes, "bad directory rejected without reading body");
        SimpleAssert.eq(1, invalid.closes, "bad directory closes exact handle");
        CountingChannel missingOwner = new CountingChannel(bytes);
        expectIo(() -> HologramArchiveGeometryReader.inspectExplicitIndex(missingOwner, null));
        SimpleAssert.eq(1, missingOwner.closes, "missing owner cannot leak owned handle");
    }

    private static byte[] sample() {
        ByteBuffer data = ByteBuffer.allocate(INDEX_END + 4);
        byte[] page = new byte[4]; // center (0,0,0), first exact palette member
        byte[] palette = sha256().digest(
            "minecraft:stonebrick\nminecraft:quartz_block\nminecraft:glowstone\n".getBytes(StandardCharsets.US_ASCII));
        return data.putInt(0x47545047).putInt(1).putLong(1).putInt(1).putInt(3).put(palette)
            .putInt(0).putInt(0).putInt(0).putInt(1).putLong(INDEX_END).put(sha256().digest(page)).put(page).array();
    }

    @FunctionalInterface
    private interface Opening {
        void run() throws IOException;
    }

    private static void expectIo(Opening work) {
        try {
            work.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected explicit archive refusal");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class CountingChannel implements SeekableByteChannel {

        final byte[] bytes;
        int pos, readBytes, furthestByte, closes;

        CountingChannel(byte[] bytes) {
            this.bytes = bytes;
        }

        public int read(ByteBuffer dst) throws IOException {
            requireOpen();
            if (pos >= bytes.length) return -1;
            int count = Math.min(dst.remaining(), bytes.length - pos);
            dst.put(bytes, pos, count);
            readBytes += count;
            pos += count;
            furthestByte = Math.max(furthestByte, pos);
            return count;
        }

        public int write(ByteBuffer src) { throw new NonWritableChannelException(); }

        public long position() throws IOException {
            requireOpen();
            return pos;
        }

        public SeekableByteChannel position(long next) throws IOException {
            requireOpen();
            if (next < 0 || next > bytes.length) throw new IOException("Invalid position");
            pos = (int) next;
            return this;
        }

        public long size() throws IOException {
            requireOpen();
            return bytes.length;
        }

        public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }

        public boolean isOpen() { return closes == 0; }

        public void close() { closes++; }

        private void requireOpen() throws IOException {
            if (!isOpen()) throw new ClosedChannelException();
        }
    }
}
