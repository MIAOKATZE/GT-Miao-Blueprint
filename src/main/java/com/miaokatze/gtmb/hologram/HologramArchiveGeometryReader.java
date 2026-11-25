// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Read-only GTPG v1 geometry, for an already authorized, held local read handle. Never opens a path. */
public final class HologramArchiveGeometryReader implements AutoCloseable {

    public static final int EDGE = 16, MAX_PAGE_ROWS = EDGE * EDGE * EDGE;
    private static final int MAGIC = 0x47545047, VERSION = 1;
    private static final int HASH_BYTES = 32, HEADER_BYTES = 56, ENTRY_BYTES = 56, MAX_PAGES = 65_536;
    private static final int HORIZONTAL = 60_000_000, VERTICAL = 256, MAX_CELLS = 4_000_000;
    private static final int MAX_PAGE_BYTES = MAX_PAGE_ROWS * Integer.BYTES;
    public enum Material {
        STONE_BRICK("minecraft:stonebrick"),
        QUARTZ_BLOCK("minecraft:quartz_block"),
        GLOWSTONE("minecraft:glowstone");

        public final String registryName;

        Material(String registryName) {
            this.registryName = registryName;
        }
    }

    private static final Material[] MATERIALS = Material.values();
    // Exact independently witnessed plain palette. No arbitrary registry or NBT payload is admitted.
    private static final byte[] PLAIN_PALETTE = paletteDigest();

    public static Material material(int code) {
        if (code == 0) return null; // Air only for a proved absent cell.
        if (code < 1 || code > MATERIALS.length) throw new IllegalArgumentException("Archive material code");
        return MATERIALS[code - 1];
    }

    public static final class Page {

        private final byte[] codes;
        public final int rows;

        private Page(byte[] codes, int rows) {
            this.codes = codes;
            this.rows = rows;
        }

        public int code(int x, int y, int z) {
            if (x < 0 || x >= EDGE || y < 0 || y >= EDGE || z < 0 || z >= EDGE)
                throw new IndexOutOfBoundsException("Not a region-local cell");
            return codes[(x << 8) | (y << 4) | z];
        }
    }

    private final SeekableByteChannel file;
    private final Thread owner = Thread.currentThread();
    private final long length;
    private final ByteBuffer index;
    private final ByteBuffer body = ByteBuffer.allocate(MAX_PAGE_BYTES);
    private final MessageDigest digest = sha256();
    private final byte[] expectedPage = new byte[HASH_BYTES];
    public final int pages;
    public final long cells;

    /**
     * Transfers closure of the exact handle on entry, including failed validation. The caller provides the trusted
     * manifest from its verified private catalog, not a digest taken from the candidate file. Reader access and close
     * remain on one worker. Opening authenticates only the bounded index; each requested payload is verified separately.
     */
    public static HologramArchiveGeometryReader take(SeekableByteChannel owned, byte[] trustedManifest)
        throws IOException {
        return open(owned, trustedManifest, null);
    }

    /** Detached candidate metadata only: not a trusted manifest, verified cell count, or preview. */
    public static final class InspectedIndex {

        public final int declaredPages;
        public final long declaredCells;

        private InspectedIndex(int pages, long cells) {
            declaredPages = pages;
            declaredCells = cells;
        }
    }

    /**
     * Reads only the bounded header and directory from an already authorized held regular-file handle.
     * Consumes and closes that exact handle before returning detached metadata, including on failure.
     * This does not open a pathname, verify any body page, or serve as a renderer/source credential.
     */
    public static InspectedIndex inspectExplicitIndex(SeekableByteChannel owned, BooleanSupplier current)
        throws IOException {
        try (HologramArchiveGeometryReader exact = open(owned, null, current)) {
            if (!current.getAsBoolean()) throw stale();
            return new InspectedIndex(exact.pages, exact.cells);
        }
    }

    private static HologramArchiveGeometryReader open(SeekableByteChannel owned, byte[] trustedManifest,
        BooleanSupplier current) throws IOException {
        Objects.requireNonNull(owned);
        try {
            if (current == null && (trustedManifest == null || trustedManifest.length != HASH_BYTES))
                throw invalid("manifest identity");
            if (current != null && !current.getAsBoolean()) throw stale();
            return new HologramArchiveGeometryReader(
                owned, trustedManifest == null ? null : trustedManifest.clone(), current);
        } catch (IOException | RuntimeException | Error failed) {
            try {
                owned.close();
            } catch (IOException | RuntimeException closeFailed) {
                failed.addSuppressed(closeFailed);
            }
            throw failed;
        }
    }

    private HologramArchiveGeometryReader(SeekableByteChannel file, byte[] manifest, BooleanSupplier current)
        throws IOException {
        this.file = file;
        length = file.size();
        if (length < HEADER_BYTES || length > indexEnd(MAX_PAGES) + (long) MAX_CELLS * Integer.BYTES)
            throw invalid("length");
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        read(header, 0);
        if (header.getInt() != MAGIC || header.getInt() != VERSION) throw invalid("magic/version");
        cells = header.getLong();
        pages = header.getInt();
        if (header.getInt() != MATERIALS.length || cells < 1 || cells > MAX_CELLS || pages < 1
            || pages > MAX_PAGES || pages > cells || cells > (long) pages * MAX_PAGE_ROWS
            || indexEnd(pages) + cells * Integer.BYTES != length) throw invalid("directory bounds");
        byte[] palette = new byte[HASH_BYTES];
        header.get(palette);
        if (!MessageDigest.isEqual(PLAIN_PALETTE, palette)) throw invalid("unsupported palette");
        index = ByteBuffer.allocate(pages * ENTRY_BYTES);
        if (current != null && !current.getAsBoolean()) throw stale();
        read(index, HEADER_BYTES);
        if (manifest != null) {
            digest.update(header.array());
            if (!MessageDigest.isEqual(manifest, digest.digest(index.array()))) throw invalid("manifest digest");
        }

        long offset = indexEnd(pages), total = 0;
        int lastX = 0, lastY = 0, lastZ = 0;
        for (int i = 0; i < pages; i++) {
            if (current != null && (i & 1023) == 0 && !current.getAsBoolean()) throw stale();
            int entry = i * ENTRY_BYTES;
            int x = index.getInt(entry), y = index.getInt(entry + 4), z = index.getInt(entry + 8);
            int rows = index.getInt(entry + 12);
            if (x < (-HORIZONTAL >> 4) || x > (HORIZONTAL >> 4) || z < (-HORIZONTAL >> 4)
                || z > (HORIZONTAL >> 4) || y < (-VERTICAL >> 4) || y > (VERTICAL >> 4)
                || rows < 1 || rows > MAX_PAGE_ROWS || index.getLong(entry + 16) != offset
                || i != 0 && compare(x, y, z, lastX, lastY, lastZ) <= 0) throw invalid("directory entry");
            total += rows;
            offset += (long) rows * Integer.BYTES;
            if (total > cells || offset > length) throw invalid("directory total");
            lastX = x;
            lastY = y;
            lastZ = z;
        }
        if (total != cells || offset != length || file.size() != length) throw invalid("directory total");
    }

    /** Authenticated count, zero only for a section absent from the entire authenticated directory. No payload I/O. */
    public int rows(int x, int y, int z) {
        requireCurrentThread();
        int entry = find(x, y, z);
        return entry < 0 ? 0 : index.getInt(entry + 12);
    }

    /** Does not publish rows from a damaged page. No page object is created for an absent section. */
    public Page readPage(int x, int y, int z) throws IOException {
        requireCurrentThread();
        int entry = find(x, y, z);
        return entry < 0 ? null : page(entry);
    }

    private Page page(int entry) throws IOException {
        int x = index.getInt(entry), y = index.getInt(entry + 4), z = index.getInt(entry + 8);
        int count = index.getInt(entry + 12), size = count * Integer.BYTES;
        body.clear();
        body.limit(size);
        read(body, index.getLong(entry + 16));
        digest.update(body.array(), 0, size);
        for (int i = 0; i < HASH_BYTES; i++) expectedPage[i] = index.get(entry + 24 + i);
        if (!MessageDigest.isEqual(expectedPage, digest.digest()) || file.size() != length)
            throw invalid("page digest/length");
        byte[] codes = new byte[MAX_PAGE_ROWS];
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int packed = body.getInt(), slot = packed >>> 16, material = packed & 0xffff;
            if (slot <= previous || slot >= MAX_PAGE_ROWS || material >= MATERIALS.length
                || Math.abs((x << 4) + (slot >>> 8)) > HORIZONTAL
                || Math.abs((y << 4) + ((slot >>> 4) & 15)) > VERTICAL
                || Math.abs((z << 4) + (slot & 15)) > HORIZONTAL) throw invalid("page row");
            codes[slot] = (byte) (material + 1);
            previous = slot;
        }
        return new Page(codes, count);
    }

    private int find(int x, int y, int z) {
        int left = 0, right = pages - 1;
        while (left <= right) {
            int middle = (left + right) >>> 1, entry = middle * ENTRY_BYTES;
            int order = compare(x, y, z, index.getInt(entry), index.getInt(entry + 4), index.getInt(entry + 8));
            if (order == 0) return entry;
            if (order < 0) right = middle - 1;
            else left = middle + 1;
        }
        return -1;
    }

    private void read(ByteBuffer buffer, long offset) throws IOException {
        file.position(offset);
        while (buffer.hasRemaining()) {
            if (file.read(buffer) <= 0) throw invalid("short read");
        }
        buffer.flip();
    }

    private static long indexEnd(int pages) {
        return HEADER_BYTES + (long) pages * ENTRY_BYTES;
    }

    private static int compare(int x, int y, int z, int otherX, int otherY, int otherZ) {
        int order = Integer.compare(y, otherY);
        if (order == 0) order = Integer.compare(z, otherZ);
        return order == 0 ? Integer.compare(x, otherX) : order;
    }

    private static byte[] paletteDigest() {
        MessageDigest palette = sha256();
        for (Material material : MATERIALS) {
            palette.update(material.registryName.getBytes(StandardCharsets.US_ASCII));
            palette.update((byte) '\n');
        }
        return palette.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IOException stale() {
        return new InterruptedIOException("Local archive admission retired");
    }

    private static IOException invalid(String reason) {
        return new IOException("Unsupported or damaged local geometry archive: " + reason);
    }

    private void requireCurrentThread() {
        if (Thread.currentThread() != owner || !file.isOpen())
            throw new IllegalStateException("Archive reader is not owned by this worker");
    }

    @Override
    public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Archive reader owner");
        file.close();
    }
}
