// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Manifests have no cell positions; both explicit profiles share the same bounded slice/ACK codec. */
final class HologramProjectedLayerWire {

    static final int VERSION = 1;
    static final int REVIEWED_PROFILE = 2;
    static final int MAX_MANIFEST_BYTES = 256 * 1024;
    static final int MAX_REVIEWED_MANIFEST_BYTES = 24 * 1024;
    static final int MAX_REVIEWED_VARIANTS = HologramLayerStockBill.MAX_ROWS;
    private static final int MAX_REVIEWED_FIELD_TEXT = 2732; // Base64 for up to 2,048 compressed bytes.
    static final int MAX_SLICE_BYTES = 8 * 1024;
    static final int MAX_SLICE_CELLS = 512;
    static final int WINDOW = 8; // At most 64 KiB of encoded geometry per owner, not whole-blueprint buffering.
    static final int MAX_CELLS = HologramBlueprintLimits.LOCAL_CELLS;
    static final int MAX_VARIANTS = 512;
    static final int ACK_BYTES = 29;
    // New magics reject former fingerprint-bearing records rather than silently interpreting old frames.
    private static final int MANIFEST_MAGIC = 0x47544d31, SLICE_MAGIC = 0x47544d32, ACK_MAGIC = 0x47544d13;
    private static final int REVIEWED_MANIFEST_MAGIC = 0x47544d41;
    private static final int SLICE_FIXED_BYTES = 32;
    private static final int FIELDS = 3;
    private static final int TERMINAL = 2, ABORTED = 4;
    private static final Pattern REGISTRY_NAME = Pattern.compile("[a-zA-Z0-9_.:/-]{1,256}");

    private HologramProjectedLayerWire() {}

    static final class Variant {

        final String block, item;
        final int metadata, damage, count;
        private final String tile, itemState;
        private final boolean reviewed;

        Variant(String block, int metadata, String item, int damage, int facing, boolean controller, String tile,
            String itemState, int count) throws IOException {
            this(block, metadata, item, damage, facing, controller, tile, itemState, count, false);
        }

        private Variant(String block, int metadata, String item, int damage, int facing, boolean controller,
            String tile, String itemState, int count, boolean reviewed) throws IOException {
            if (block == null || !REGISTRY_NAME.matcher(block).matches()
                || item == null || !REGISTRY_NAME.matcher(item).matches()
                || metadata < 0 || metadata > 15 || damage < 0 || damage > Short.MAX_VALUE
                || count < 1 || count > MAX_CELLS || facing != -1 || controller
                || tile == null || itemState == null || (reviewed
                    ? tile.length() > MAX_REVIEWED_FIELD_TEXT || itemState.length() > MAX_REVIEWED_FIELD_TEXT
                    : !tile.isEmpty() || !itemState.isEmpty()))
                throw malformed();
            this.block = block;
            this.metadata = metadata;
            this.item = item;
            this.damage = damage;
            this.count = count;
            this.tile = tile;
            this.itemState = itemState;
            this.reviewed = reviewed;
        }

        static Variant reviewed(HologramProjectedVariantPricing.Row row) throws IOException {
            if (row == null) throw malformed();
            return new Variant(row.block, row.metadata, row.item, row.damage, row.facing, row.controller,
                row.tileNbt == null ? "" : row.tileNbt, row.itemNbt == null ? "" : row.itemNbt, row.count, true);
        }

        HologramProjectedVariantPricing.Row reviewedRow() throws IOException {
            if (!reviewed) throw malformed();
            return new HologramProjectedVariantPricing.Row(block, item, metadata, damage, -1, false,
                tile, itemState, count);
        }

        private List<Object> identity() {
            return Arrays.asList(block, metadata, item, damage, tile, itemState);
        }
    }

    static final class Header {

        final UUID request;
        final int x, y, z, minX, minY, minZ, maxX, maxY, maxZ;
        final int cells, slices, profile;
        final List<Variant> palette;

        Header(UUID request, int x, int y, int z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            int cells, int slices, List<Variant> palette) throws IOException {
            this(request, x, y, z, minX, minY, minZ, maxX, maxY, maxZ, cells, slices, palette, VERSION);
        }

        static Header reviewed(UUID request, int x, int y, int z, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ, int cells, int slices, List<Variant> palette) throws IOException {
            return new Header(request, x, y, z, minX, minY, minZ, maxX, maxY, maxZ,
                cells, slices, palette, REVIEWED_PROFILE);
        }

        private Header(UUID request, int x, int y, int z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            int cells, int slices, List<Variant> palette, int profile) throws IOException {
            boolean reviewed = profile == REVIEWED_PROFILE;
            int variantCap = reviewed ? MAX_REVIEWED_VARIANTS : MAX_VARIANTS;
            if (!validRequest(request) || !position(x, y, z) || !position(minX, minY, minZ)
                || !position(maxX, maxY, maxZ) || minX > maxX || minY > maxY || minZ > maxZ
                || cells < 1 || cells > MAX_CELLS || !fits(cells, slices) || palette == null
                || palette.isEmpty() || palette.size() > variantCap
                || !fitsEnvelope(minX, minY, minZ, maxX, maxY, maxZ, cells, slices)) throw malformed();
            long count = 0;
            Set<List<Object>> distinct = new HashSet<>();
            List<Variant> copy = new ArrayList<>(palette.size());
            for (Variant variant : palette) {
                if (copy.size() >= variantCap || variant == null || variant.reviewed != reviewed
                    || !distinct.add(variant.identity()))
                    throw malformed();
                count += variant.count;
                copy.add(variant);
            }
            if (count != cells) throw malformed();
            this.request = request;
            this.x = x;
            this.y = y;
            this.z = z;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.cells = cells;
            this.slices = slices;
            this.profile = profile;
            this.palette = Collections.unmodifiableList(copy);
        }
    }

    static final class Manifest {

        final Header header;
        Manifest(Header header) throws IOException {
            if (header == null) throw malformed();
            this.header = header;
        }
    }

    /** Global order is increasing (world Y, world Z, world X); each slice has exactly one Y. */
    static final class Slice {

        final UUID request;
        final int sequence, y;
        private final int[] operations;

        Slice(UUID request, int sequence, int y, int[] operations) throws IOException {
            if (!validRequest(request) || sequence < 0 || sequence >= MAX_CELLS
                || y < 0 || y >= 4096 || operations == null || operations.length == 0
                || operations.length % FIELDS != 0 || operations.length / FIELDS > MAX_SLICE_CELLS) throw malformed();
            this.request = request;
            this.sequence = sequence;
            this.y = y;
            this.operations = operations.clone();
            for (int i = 0; i < size(); i++) {
                if (!position(x(i), y, z(i)) || variant(i) < 0 || variant(i) >= MAX_VARIANTS
                    || i > 0 && compare(y, z(i - 1), x(i - 1), y, z(i), x(i)) >= 0) throw malformed();
            }
        }

        int size() {
            return operations.length / FIELDS;
        }

        int x(int index) {
            return operations[FIELDS * index];
        }

        int z(int index) {
            return operations[FIELDS * index + 1];
        }

        int variant(int index) {
            return operations[FIELDS * index + 2];
        }

    }

    /** Transfer only. settledCells is not a count of world mutations or payments. */
    static final class Ack {

        final UUID request;
        final int nextSlice, settledCells, credit;
        final boolean complete, aborted;

        Ack(UUID request, int nextSlice, int settledCells, int credit, boolean complete, boolean aborted)
            throws IOException {
            if (!validRequest(request) || nextSlice < 0 || nextSlice > MAX_CELLS
                || !fits(settledCells, nextSlice) || settledCells > MAX_CELLS || credit < 0 || credit > 1
                || complete && (aborted || nextSlice == 0) || (complete || aborted) && credit != 0)
                throw malformed();
            this.request = request;
            this.nextSlice = nextSlice;
            this.settledCells = settledCells;
            this.credit = credit;
            this.complete = complete;
            this.aborted = aborted;
        }
    }

    static byte[] encodeManifest(Manifest value) throws IOException {
        return encodeManifest(value, VERSION);
    }

    static byte[] encodeReviewedManifest(Manifest value) throws IOException {
        return encodeManifest(value, REVIEWED_PROFILE);
    }

    private static byte[] encodeManifest(Manifest value, int profile) throws IOException {
        if (value == null || value.header.profile != profile) throw malformed();
        return write(manifestCap(profile), out -> {
            writeHeader(out, value.header);
        });
    }

    static Manifest decodeManifest(byte[] encoded) throws IOException {
        return decodeManifest(encoded, VERSION);
    }

    static Manifest decodeReviewedManifest(byte[] encoded) throws IOException {
        return decodeManifest(encoded, REVIEWED_PROFILE);
    }

    private static Manifest decodeManifest(byte[] encoded, int profile) throws IOException {
        boolean reviewed = profile == REVIEWED_PROFILE;
        try (DataInputStream in = read(encoded, manifestCap(profile),
            reviewed ? REVIEWED_MANIFEST_MAGIC : MANIFEST_MAGIC)) {
            UUID request = readUuid(in);
            int x = in.readInt(), y = in.readInt(), z = in.readInt();
            int minX = in.readInt(), minY = in.readInt(), minZ = in.readInt();
            int maxX = in.readInt(), maxY = in.readInt(), maxZ = in.readInt();
            int cells = in.readInt(), slices = in.readInt(), variants = in.readInt();
            if (variants < 1 || variants > (reviewed ? MAX_REVIEWED_VARIANTS : MAX_VARIANTS)) throw malformed();
            List<Variant> palette = new ArrayList<>(variants);
            for (int i = 0; i < variants; i++) {
                String block = readText(in, 256), item = readText(in, 256);
                int metadata = in.readUnsignedByte(), damage = in.readUnsignedShort(), facing = in.readByte();
                int flags = in.readUnsignedByte(), count = in.readInt();
                if (facing != -1 || flags != 0) throw malformed();
                // v1 caps both fields at exactly zero before allocation; profile 2 is a separate decoder.
                String tile = "", itemState = "";
                if (reviewed) {
                    tile = readText(in, MAX_REVIEWED_FIELD_TEXT);
                    itemState = readText(in, MAX_REVIEWED_FIELD_TEXT);
                } else if (in.readInt() != 0 || in.readInt() != 0) throw malformed();
                palette.add(new Variant(block, metadata, item, damage, -1, false, tile, itemState, count, reviewed));
            }
            if (in.available() != 0) throw malformed();
            return new Manifest(new Header(request, x, y, z, minX, minY, minZ, maxX, maxY, maxZ, cells, slices,
                palette, profile));
        }
    }

    private static int manifestCap(int profile) {
        return profile == REVIEWED_PROFILE ? MAX_REVIEWED_MANIFEST_BYTES : MAX_MANIFEST_BYTES;
    }

    private static void writeHeader(DataOutputStream out, Header value) throws IOException {
        boolean reviewed = value.profile == REVIEWED_PROFILE;
        out.writeInt(reviewed ? REVIEWED_MANIFEST_MAGIC : MANIFEST_MAGIC);
        writeUuid(out, value.request);
        out.writeInt(value.x);
        out.writeInt(value.y);
        out.writeInt(value.z);
        out.writeInt(value.minX);
        out.writeInt(value.minY);
        out.writeInt(value.minZ);
        out.writeInt(value.maxX);
        out.writeInt(value.maxY);
        out.writeInt(value.maxZ);
        out.writeInt(value.cells);
        out.writeInt(value.slices);
        out.writeInt(value.palette.size());
        for (Variant variant : value.palette) {
            writeText(out, variant.block, 256);
            writeText(out, variant.item, 256);
            out.writeByte(variant.metadata);
            out.writeShort(variant.damage);
            out.writeByte(-1); // Reserved facing: absent.
            out.writeByte(0);  // Reserved controller flags: none.
            out.writeInt(variant.count);
            if (reviewed) {
                writeText(out, variant.tile, MAX_REVIEWED_FIELD_TEXT);
                writeText(out, variant.itemState, MAX_REVIEWED_FIELD_TEXT);
            } else {
                out.writeInt(0);
                out.writeInt(0);
            }
        }
    }

    static byte[] encodeSlice(Slice value) throws IOException {
        return write(MAX_SLICE_BYTES, out -> {
            out.writeInt(SLICE_MAGIC);
            writeUuid(out, value.request);
            out.writeInt(value.sequence);
            out.writeInt(value.y);
            out.writeInt(value.size());
            for (int field : value.operations) out.writeInt(field);
        });
    }

    static Slice decodeSlice(byte[] encoded) throws IOException {
        try (DataInputStream in = read(encoded, MAX_SLICE_BYTES, SLICE_MAGIC)) {
            UUID request = readUuid(in);
            int sequence = in.readInt(), y = in.readInt(), cells = in.readInt();
            if (cells < 1 || cells > MAX_SLICE_CELLS || encoded.length != SLICE_FIXED_BYTES + cells * FIELDS * 4)
                throw malformed();
            int[] operations = new int[cells * FIELDS];
            for (int i = 0; i < operations.length; i++) operations[i] = in.readInt();
            return new Slice(request, sequence, y, operations);
        }
    }

    static byte[] encodeAck(Ack value) throws IOException {
        return write(ACK_BYTES, out -> {
            out.writeInt(ACK_MAGIC);
            writeUuid(out, value.request);
            out.writeInt(value.nextSlice);
            out.writeInt(value.settledCells);
            out.writeByte(value.credit | (value.complete ? TERMINAL : 0) | (value.aborted ? ABORTED : 0));
        });
    }

    static Ack decodeAck(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length != ACK_BYTES) throw malformed();
        try (DataInputStream in = read(encoded, ACK_BYTES, ACK_MAGIC)) {
            UUID request = readUuid(in);
            int next = in.readInt(), cells = in.readInt(), flags = in.readUnsignedByte();
            if ((flags & ~7) != 0) throw malformed();
            return new Ack(request, next, cells, flags & 1, (flags & TERMINAL) != 0, (flags & ABORTED) != 0);
        }
    }

    static boolean fits(int cells, int slices) {
        return slices >= 0 && cells >= slices && cells <= (long) slices * MAX_SLICE_CELLS;
    }

    /** Full chunks first, then at most one residual chunk per Y. No 3-D volume multiplication can overflow. */
    private static boolean fitsEnvelope(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int cells,
        int slices) {
        long horizontal = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        long height = (long) maxY - minY + 1;
        // World horizontal area is at most 3.6e15; divide before multiplying by the height.
        long full = Math.min(slices, horizontal / MAX_SLICE_CELLS * height);
        long residual = Math.min(slices - full, height);
        long capacity = full * MAX_SLICE_CELLS + residual * (horizontal % MAX_SLICE_CELLS);
        return cells <= capacity;
    }

    static int compare(int y, int z, int x, int otherY, int otherZ, int otherX) {
        int order = Integer.compare(y, otherY);
        if (order == 0) order = Integer.compare(z, otherZ);
        return order == 0 ? Integer.compare(x, otherX) : order;
    }

    private static boolean position(int x, int y, int z) {
        return x >= -30_000_000 && x < 30_000_000 && z >= -30_000_000 && z < 30_000_000 && y >= 0 && y < 4096;
    }

    private static boolean validRequest(UUID request) {
        return request != null && (request.getMostSignificantBits() != 0 || request.getLeastSignificantBits() != 0);
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static String readText(DataInputStream in, int cap) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > cap || length > in.available()) throw malformed();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void writeText(DataOutputStream out, String value, int cap) throws IOException {
        if (value.length() > cap) throw malformed();
        ByteBuffer utf8 = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        if (utf8.remaining() > cap) throw malformed();
        byte[] bytes = new byte[utf8.remaining()];
        utf8.get(bytes);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static DataInputStream read(byte[] bytes, int cap, int magic) throws IOException {
        if (bytes == null || bytes.length < 4 || bytes.length > cap) throw malformed();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != magic) throw malformed();
        return in;
    }

    private interface Output {

        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(int cap, Output encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new OutputStream() {

            @Override
            public void write(int value) throws IOException {
                if (bytes.size() >= cap) throw malformed();
                bytes.write(value);
            }

            @Override
            public void write(byte[] value, int offset, int length) throws IOException {
                if (length > cap - bytes.size()) throw malformed();
                bytes.write(value, offset, length);
            }
        })) {
            encoder.write(out);
        }
        return bytes.toByteArray();
    }

    static IOException malformed() {
        return new IOException("Invalid projected-layer record");
    }
}
