package com.miaokatze.gtmb.hologram;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;

/** Byte budgets are independent of structure dimensions and cell counts. */
public final class HologramSnapshotCodec {

    private static final int RAW_BYTES = 128 * 1024 * 1024;
    private static final int PALETTE_MAGIC = 0x47545350; // GTSP, outside the legacy GZIP stream.

    private HologramSnapshotCodec() {}

    public static byte[] compress(NBTTagCompound tag) throws IOException {
        return compress(tag, () -> true, written -> {});
    }

    /** Negotiated peers only; unprofitable dictionaries retain the original GZIP format. */
    public static byte[] compressPacked(NBTTagCompound tag) throws IOException {
        return compress(tag, () -> true, written -> {}, true);
    }

    /** Checkpoints share the serialization pass; large NBT arrays cannot monopolize an obsolete worker. */
    static byte[] compress(NBTTagCompound tag, java.util.function.BooleanSupplier current,
        java.util.function.LongConsumer progress) throws IOException {
        return compress(tag, current, progress, false);
    }

    static byte[] compress(NBTTagCompound tag, java.util.function.BooleanSupplier current,
        java.util.function.LongConsumer progress, boolean palette) throws IOException {
        if (palette) tag = HologramSnapshotPalette.pack(tag, current);
        boolean packed = HologramSnapshotPalette.isPacked(tag);
        if (packed && !palette) throw new IOException("packed snapshot requires negotiated encoding");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (packed) {
            DataOutputStream header = new DataOutputStream(bytes);
            header.writeInt(PALETTE_MAGIC);
            header.writeInt(HologramSnapshotPalette.VERSION);
        }
        try (DataOutputStream output = new DataOutputStream(
            new LimitedOutput(
                new BufferedOutputStream(
                    new GZIPOutputStream(
                        new LimitedOutput(bytes, HologramSnapshotAssembly.MAX_BYTES - (packed ? 8 : 0)))),
                RAW_BYTES,
                current,
                progress))) {
            CompressedStreamTools.write(tag, output);
        }
        byte[] encoded = bytes.toByteArray();
        if (packed && HologramRuntimeMetrics.isEnabled()) {
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.PALETTE_SNAPSHOTS_ENCODED, 1);
            HologramRuntimeMetrics.add(
                HologramRuntimeMetrics.Counter.PALETTE_ROWS_ENCODED,
                tag.getTagList("cells", 10)
                    .tagCount());
            HologramRuntimeMetrics.add(
                HologramRuntimeMetrics.Counter.PALETTE_DESCRIPTORS_ENCODED,
                tag.getTagList(HologramSnapshotPalette.PALETTE_KEY, 10)
                    .tagCount());
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.PALETTE_BYTES_ENCODED, encoded.length);
        }
        return encoded;
    }

    public static NBTTagCompound decode(byte[] bytes) throws IOException {
        return decode(bytes, RAW_BYTES, 512L * 1024 * 1024);
    }

    static NBTTagCompound decode(byte[] bytes, int rawLimit, long nbtLimit) throws IOException {
        int offset = 0;
        if (bytes.length > 0 && bytes[0] == 0x47) {
            try (DataInputStream header = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (header.readInt() != PALETTE_MAGIC || header.readInt() != HologramSnapshotPalette.VERSION)
                    throw new IOException("snapshot palette wire version");
            }
            offset = 8;
        }
        // Vanilla 1.7 array-size accounting multiplies ints before casting to long. Validate lengths first.
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(bytes, offset, bytes.length - offset))))) {
            Validator validator = new Validator(input, rawLimit);
            if (validator.byteValue() != 10) throw new IOException("snapshot root is not a compound");
            validator.string();
            validator.payload(10, 0);
            if (input.read() != -1) throw new IOException("snapshot trailing bytes");
        }
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(bytes, offset, bytes.length - offset))))) {
            NBTTagCompound root = CompressedStreamTools.func_152456_a(input, new NBTSizeTracker(nbtLimit));
            if (offset == 0) return root;
            NBTTagCompound packed = HologramSnapshotPalette.admit(root, rawLimit, nbtLimit);
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.PALETTE_SNAPSHOTS_DECODED, 1);
            return packed;
        }
    }

    private static final class LimitedOutput extends FilterOutputStream {

        private final int limit;
        private int written;
        private final java.util.function.BooleanSupplier current;
        private final java.util.function.LongConsumer progress;

        LimitedOutput(OutputStream output, int limit) {
            this(output, limit, () -> true, written -> {});
        }

        LimitedOutput(OutputStream output, int limit, java.util.function.BooleanSupplier current,
            java.util.function.LongConsumer progress) {
            super(output);
            this.limit = limit;
            this.current = current;
            this.progress = progress;
        }

        public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int count = Math.min(length, 8192);
                reserve(count);
                out.write(bytes, offset, count);
                offset += count;
                length -= count;
            }
        }

        private void reserve(int length) throws IOException {
            if (!current.getAsBoolean())
                throw new java.io.InterruptedIOException("snapshot encoding retired or timed out");
            if (length < 0 || length > limit - written) throw new IOException("snapshot byte budget");
            written += length;
            progress.accept(written);
        }
    }

    private static final class Validator {

        private final DataInputStream input;
        private final int limit;
        private int consumed;
        private final byte[] utfBytes = new byte[65535];

        Validator(DataInputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        int byteValue() throws IOException {
            reserve(1);
            return input.readUnsignedByte();
        }

        int length() throws IOException {
            reserve(4);
            int length = input.readInt();
            if (length < 0 || length > limit) throw new IOException("snapshot NBT length");
            return length;
        }

        void string() throws IOException {
            reserve(2);
            int length = input.readUnsignedShort();
            reserve(length);
            input.readFully(utfBytes, 0, length);
            // Validate modified UTF before vanilla parsing can construct a CrashReport and call mod hooks.
            for (int i = 0; i < length;) {
                int first = utfBytes[i++] & 255;
                if (first < 128) continue;
                int tails = (first & 224) == 192 ? 1 : (first & 240) == 224 ? 2 : -1;
                if (tails < 0 || tails > length - i) throw new IOException("snapshot NBT UTF");
                while (tails-- > 0) if ((utfBytes[i++] & 192) != 128) throw new IOException("snapshot NBT UTF");
            }
        }

        void skip(long bytes) throws IOException {
            if (bytes < 0 || bytes > limit - consumed) throw new IOException("snapshot expanded byte budget");
            reserve((int) bytes);
            int left = (int) bytes;
            while (left > 0) {
                int skipped = input.skipBytes(left);
                if (skipped == 0) {
                    input.readByte();
                    skipped = 1;
                }
                left -= skipped;
            }
        }

        void reserve(int bytes) throws IOException {
            if (bytes > limit - consumed) throw new IOException("snapshot expanded byte budget");
            consumed += bytes;
        }

        void payload(int type, int depth) throws IOException {
            if (depth > 512) throw new IOException("snapshot NBT depth");
            switch (type) {
                case 1:
                    skip(1);
                    break;
                case 2:
                    skip(2);
                    break;
                case 3:
                case 5:
                    skip(4);
                    break;
                case 4:
                case 6:
                    skip(8);
                    break;
                case 7:
                    skip(length());
                    break;
                case 8:
                    string();
                    break;
                case 9:
                    int child = byteValue(), count = length();
                    if ((child <= 0 && count != 0) || child > 11) throw new IOException("snapshot list type");
                    for (int i = 0; i < count; i++) payload(child, depth + 1);
                    break;
                case 10:
                    int member;
                    while ((member = byteValue()) != 0) {
                        string();
                        payload(member, depth + 1);
                    }
                    break;
                case 11:
                    skip(4L * length());
                    break;
                default:
                    throw new IOException("snapshot NBT type");
            }
        }
    }
}
