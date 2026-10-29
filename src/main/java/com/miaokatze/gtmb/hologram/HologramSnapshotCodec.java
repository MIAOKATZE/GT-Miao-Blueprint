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
    // Existing blueprint payload envelope, shared by disk/native and disposable client consumers.
    public static final int BLUEPRINT_NBT_MAX_COMPRESSED = 8 * 1024 * 1024;
    public static final int BLUEPRINT_NBT_MAX_EXPANDED = 8 * 1024 * 1024;
    public static final long BLUEPRINT_NBT_MAX_TRACKED_BYTES = 16L * 1024 * 1024;

    private HologramSnapshotCodec() {}

    public static byte[] compress(NBTTagCompound tag) throws IOException {
        return compress(tag, () -> true, written -> {});
    }

    /** Checkpoints share the serialization pass; large NBT arrays cannot monopolize an obsolete worker. */
    static byte[] compress(NBTTagCompound tag, java.util.function.BooleanSupplier current,
        java.util.function.LongConsumer progress) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(
            new LimitedOutput(
                new BufferedOutputStream(
                    new GZIPOutputStream(new LimitedOutput(bytes, HologramSnapshotAssembly.MAX_BYTES))),
                RAW_BYTES,
                current,
                progress))) {
            CompressedStreamTools.write(tag, output);
        }
        return bytes.toByteArray();
    }

    public static long retainedEstimate(NBTTagCompound tag) {
        return HologramSnapshotNbt.estimate(tag);
    }

    static HologramSnapshotBytes compressOwned(NBTTagCompound tag, java.util.function.BooleanSupplier current,
        java.util.function.LongConsumer progress) throws IOException {
        try (HologramSnapshotBytes.Output bytes = new HologramSnapshotBytes.Output()) {
            try (DataOutputStream output = new DataOutputStream(
                new LimitedOutput(new BufferedOutputStream(new GZIPOutputStream(new java.io.FilterOutputStream(bytes) {

                    public void write(byte[] data, int offset, int length) throws IOException {
                        out.write(data, offset, length);
                    }

                    public void close() throws IOException {
                        flush();
                    }
                })), RAW_BYTES, current, progress))) {
                CompressedStreamTools.write(tag, output);
            }
            return bytes.finish();
        }
    }

    public static NBTTagCompound decode(byte[] bytes) throws IOException {
        return decode(bytes, () -> true);
    }

    public static NBTTagCompound decode(byte[] bytes, java.util.function.BooleanSupplier current) throws IOException {
        return decodeMeasured(bytes, RAW_BYTES, 512L * 1024 * 1024, current).tag;
    }

    /** Bounded native payload consumer; does not use snapshot transport or construct an authority publication. */
    public static NBTTagCompound decodeBounded(byte[] bytes, int rawLimit, long nbtLimit) throws IOException {
        if (rawLimit < 1 || nbtLimit < 1) throw new IllegalArgumentException("NBT consumer budget");
        return decodeMeasured(bytes, rawLimit, nbtLimit).tag;
    }

    /** Admission observes the existing safety-pass tree estimate before any NBT tree is allocated. */
    public static NBTTagCompound decodeBounded(byte[] bytes, int rawLimit, long nbtLimit,
        java.util.function.LongPredicate admission) throws IOException {
        if (rawLimit < 1 || nbtLimit < 1 || admission == null) throw new IllegalArgumentException("NBT consumer budget");
        return decodeMeasured(bytes, rawLimit, nbtLimit, () -> true, false, admission).tag;
    }

    static NBTTagCompound decode(byte[] bytes, int rawLimit, long nbtLimit) throws IOException {
        return decodeMeasured(bytes, rawLimit, nbtLimit).tag;
    }

    static final class Decoded {

        final NBTTagCompound tag;
        final int rawBytes;
        final long retainedEstimate;
        HologramProcessGovernor.Lease lease;
        boolean compactRows;
        int cellCount = -1;

        Decoded(NBTTagCompound tag, int rawBytes, long retainedEstimate, HologramProcessGovernor.Lease lease) {
            this.tag = tag;
            this.rawBytes = rawBytes;
            this.retainedEstimate = retainedEstimate;
            this.lease = lease;
        }
    }

    public static final class BusyException extends IOException {

        public final long requestedBytes;

        BusyException(String message) {
            this(message, 0);
        }

        BusyException(String message, long requestedBytes) {
            super(message);
            this.requestedBytes = requestedBytes;
        }
    }

    /** Transferred immutable input ownership. The consumer closes after preparation or publication retires it. */
    public static final class OwnedSnapshot implements AutoCloseable {

        public final NBTTagCompound value;
        public long retainedEstimate;
        private final java.util.List<HologramProcessGovernor.Lease> leases = new java.util.ArrayList<>();

        OwnedSnapshot(NBTTagCompound value) {
            this.value = value;
        }

        void add(HologramProcessGovernor.Lease lease, long estimate) {
            if (lease != null) leases.add(lease.transfer());
            retainedEstimate += estimate;
        }

        public void close() {
            for (HologramProcessGovernor.Lease lease : leases) lease.close();
            leases.clear();
        }
    }

    public static OwnedSnapshot decodeOwned(byte[] bytes, java.util.function.BooleanSupplier current)
        throws IOException {
        return decodeOwned(bytes, RAW_BYTES, 512L * 1024 * 1024, current);
    }

    static OwnedSnapshot decodeOwned(byte[] bytes, int rawLimit, long nbtLimit,
        java.util.function.BooleanSupplier current) throws IOException {
        Decoded decoded = decodeMeasured(bytes, rawLimit, nbtLimit, current, true);
        OwnedSnapshot owner = new OwnedSnapshot(decoded.tag);
        owner.add(decoded.lease, decoded.retainedEstimate);
        return owner;
    }

    /** Retains the existing first safety pass while exposing its exact expanded-byte charge. */
    static Decoded decodeMeasured(byte[] bytes, int rawLimit, long nbtLimit) throws IOException {
        return decodeMeasured(bytes, rawLimit, nbtLimit, () -> true);
    }

    static Decoded decodeMeasured(byte[] bytes, int rawLimit, long nbtLimit, java.util.function.BooleanSupplier current)
        throws IOException {
        return decodeMeasured(bytes, rawLimit, nbtLimit, current, false);
    }

    static Decoded decodeMeasured(byte[] bytes, int rawLimit, long nbtLimit, java.util.function.BooleanSupplier current,
        boolean retained) throws IOException {
        return decodeMeasured(bytes, rawLimit, nbtLimit, current, retained, null);
    }

    private static Decoded decodeMeasured(byte[] bytes, int rawLimit, long nbtLimit,
        java.util.function.BooleanSupplier current, boolean retained, java.util.function.LongPredicate admission)
        throws IOException {
        Decoded measured = measure(bytes, 0, bytes.length, rawLimit, current);
        int expanded = measured.rawBytes;
        long estimate = measured.retainedEstimate;
        if (admission != null && !admission.test(estimate))
            throw new BusyException("Native payload working-memory quota busy", estimate);
        HologramProcessGovernor.Lease lease = retained
            ? HologramProcessGovernor.tryRetain("snapshot.expanded", estimate)
            : null;
        if (retained && lease == null)
            throw new BusyException("Snapshot expanded-memory quota is busy; retry the current view", estimate);
        try (DataInputStream input = input(bytes, current)) {
            NBTSizeTracker tracker = new NBTSizeTracker(nbtLimit) {

                public void func_152450_a(long size) {
                    checkpoint(current);
                    super.func_152450_a(size);
                }
            };
            Decoded decoded = new Decoded(
                CompressedStreamTools.func_152456_a(input, tracker),
                expanded,
                estimate,
                lease);
            lease = null;
            return decoded;
        } finally {
            if (lease != null) lease.close();
        }
    }

    /** Safety-pass accounting without materializing an NBT tree or copying a compressed page. */
    static Decoded measure(byte[] bytes, int offset, int length, int rawLimit,
        java.util.function.BooleanSupplier current) throws IOException {
        // Vanilla 1.7 array-size accounting multiplies ints before casting to long. Validate lengths first.
        try (DataInputStream input = input(bytes, offset, length, current)) {
            Validator validator = new Validator(input, rawLimit);
            if (validator.byteValue() != 10) throw new IOException("snapshot root is not a compound");
            validator.string();
            validator.payload(10, 0);
            if (input.read() != -1) throw new IOException("snapshot trailing bytes");
            Decoded measured = new Decoded(null, validator.consumed, validator.retainedEstimate, null);
            measured.compactRows = validator.compactRows;
            measured.cellCount = validator.cellCount;
            return measured;
        }
    }

    private static DataInputStream input(byte[] bytes, java.util.function.BooleanSupplier current) throws IOException {
        return input(bytes, 0, bytes.length, current);
    }

    private static DataInputStream input(byte[] bytes, int offset, int length,
        java.util.function.BooleanSupplier current) throws IOException {
        checkpoint(current);
        return new DataInputStream(
            new BufferedInputStream(
                new java.io.FilterInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes, offset, length))) {

                    public int read() throws IOException {
                        checkpoint(current);
                        return in.read();
                    }

                    public int read(byte[] target, int offset, int length) throws IOException {
                        checkpoint(current);
                        return in.read(target, offset, Math.min(length, 8192));
                    }

                    public long skip(long bytes) throws IOException {
                        checkpoint(current);
                        return in.skip(Math.min(bytes, 8192));
                    }
                }));
    }

    private static void checkpoint(java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException("snapshot decode retired");
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
        private long retainedEstimate = 256;
        private boolean compactRows;
        private int cellCount = -1;
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

        int string() throws IOException {
            reserve(2);
            int length = input.readUnsignedShort();
            reserve(length);
            input.readFully(utfBytes, 0, length);
            retainedEstimate += 64L + 2L * length;
            // Validate modified UTF before vanilla parsing can construct a CrashReport and call mod hooks.
            for (int i = 0; i < length;) {
                int first = utfBytes[i++] & 255;
                if (first < 128) continue;
                int tails = (first & 224) == 192 ? 1 : (first & 240) == 224 ? 2 : -1;
                if (tails < 0 || tails > length - i) throw new IOException("snapshot NBT UTF");
                while (tails-- > 0) if ((utfBytes[i++] & 192) != 128) throw new IOException("snapshot NBT UTF");
            }
            return length;
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
            payload(type, depth, false);
        }

        void payload(int type, int depth, boolean cellsField) throws IOException {
            // Conservative Java object/map-entry projection, separately charged from expanded serialized bytes.
            retainedEstimate += 64;
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
                    int byteCount = length();
                    retainedEstimate += 24L + byteCount;
                    skip(byteCount);
                    break;
                case 8:
                    string();
                    break;
                case 9:
                    int child = byteValue(), count = length();
                    if (cellsField) cellCount = count;
                    retainedEstimate += 64L + 16L * count;
                    if ((child <= 0 && count != 0) || child > 11) throw new IOException("snapshot list type");
                    for (int i = 0; i < count; i++) payload(child, depth + 1);
                    break;
                case 10:
                    retainedEstimate += 64;
                    int member;
                    while ((member = byteValue()) != 0) {
                        int nameLength = string();
                        if (depth == 0 && nameLength == HologramSnapshotRows.PALETTE.length()) {
                            boolean palette = true;
                            for (int i = 0; i < nameLength; i++)
                                palette &= utfBytes[i] == HologramSnapshotRows.PALETTE.charAt(i);
                            compactRows |= palette;
                        }
                        boolean cells = depth == 0 && member == 9
                            && nameLength == 5
                            && utfBytes[0] == 'c'
                            && utfBytes[1] == 'e'
                            && utfBytes[2] == 'l'
                            && utfBytes[3] == 'l'
                            && utfBytes[4] == 's';
                        payload(member, depth + 1, cells);
                    }
                    break;
                case 11:
                    int intCount = length();
                    retainedEstimate += 24L + 4L * intCount;
                    skip(4L * intCount);
                    break;
                default:
                    throw new IOException("snapshot NBT type");
            }
        }
    }
}
