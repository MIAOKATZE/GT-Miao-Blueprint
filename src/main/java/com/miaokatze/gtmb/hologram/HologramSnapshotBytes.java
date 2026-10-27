package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Array capacity and the final exact-length projection are admitted before allocation. */
final class HologramSnapshotBytes implements AutoCloseable {

    final byte[] bytes;
    private final Shared shared;
    private boolean closed;

    private HologramSnapshotBytes(byte[] bytes, HologramProcessGovernor.Lease lease) {
        this.bytes = bytes;
        shared = new Shared(lease.transfer());
    }

    private HologramSnapshotBytes(HologramSnapshotBytes owner) {
        bytes = owner.bytes;
        shared = owner.shared;
        shared.references.incrementAndGet();
    }

    synchronized HologramSnapshotBytes retain() {
        if (closed) throw new IllegalStateException("snapshot bytes retired");
        return new HologramSnapshotBytes(this);
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (shared.references.decrementAndGet() == 0) shared.lease.close();
    }

    private static final class Shared {

        final HologramProcessGovernor.Lease lease;
        final AtomicInteger references = new AtomicInteger(1);

        Shared(HologramProcessGovernor.Lease lease) {
            this.lease = lease;
        }
    }

    static final class Output extends OutputStream {

        private byte[] buffer = new byte[0];
        private int count;
        private HologramProcessGovernor.Lease capacity;

        int size() {
            return count;
        }

        public void write(int value) throws IOException {
            ensure(1);
            buffer[count++] = (byte) value;
        }

        public void write(byte[] source, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > source.length - length) throw new IndexOutOfBoundsException();
            ensure(length);
            System.arraycopy(source, offset, buffer, count, length);
            count += length;
        }

        private void ensure(int extra) throws IOException {
            if (extra < 0 || extra > HologramSnapshotAssembly.MAX_BYTES - count)
                throw new IOException("snapshot byte budget");
            int required = count + extra;
            if (required <= buffer.length) return;
            int next = (int) Math
                .min(HologramSnapshotAssembly.MAX_BYTES, Math.max(required, Math.max(8192L, 2L * buffer.length)));
            HologramProcessGovernor.Lease admitted = HologramProcessGovernor
                .tryRetain("snapshot.encode.capacity", next);
            if (admitted == null)
                throw new HologramSnapshotCodec.BusyException("Snapshot output-memory quota is busy; retry");
            try {
                byte[] replacement = Arrays.copyOf(buffer, next);
                if (capacity != null) capacity.close();
                capacity = admitted.transfer();
                buffer = replacement;
            } catch (RuntimeException | Error failure) {
                admitted.close();
                throw failure;
            }
        }

        HologramSnapshotBytes finish() throws IOException {
            if (count == buffer.length && capacity != null) {
                HologramSnapshotBytes result = new HologramSnapshotBytes(buffer, capacity);
                buffer = new byte[0];
                capacity = null;
                count = 0;
                return result;
            }
            HologramProcessGovernor.Lease admitted = HologramProcessGovernor.tryRetain("snapshot.encode.result", count);
            if (admitted == null)
                throw new HologramSnapshotCodec.BusyException("Snapshot result-memory quota is busy; retry");
            try {
                HologramSnapshotBytes result = new HologramSnapshotBytes(Arrays.copyOf(buffer, count), admitted);
                close();
                return result;
            } catch (RuntimeException | Error failure) {
                admitted.close();
                throw failure;
            }
        }

        public void close() {
            if (capacity != null) capacity.close();
            capacity = null;
            buffer = new byte[0];
            count = 0;
        }
    }
}
