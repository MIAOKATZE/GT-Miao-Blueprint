package com.miaokatze.gtmb.hologram;

/** One bounded, ordered transport snapshot. Reserve the complete compressed frame before allocating it. */
public final class HologramSnapshotAssembly {

    public static final int CHUNK_BYTES = 512 * 1024;
    public static final int MAX_BYTES = 128 * 1024 * 1024;
    private static final long TIMEOUT_NS = 30_000_000_000L;
    private final int chunkBytes;

    public HologramSnapshotAssembly() {
        this(CHUNK_BYTES);
    }

    public HologramSnapshotAssembly(int chunkBytes) {
        if (chunkBytes < 1 || chunkBytes > CHUNK_BYTES) throw new IllegalArgumentException("snapshot chunk size");
        this.chunkBytes = chunkBytes;
    }

    private long newest = -1;
    private long started;
    private int total, next, received;
    private byte[] frame;
    private HologramTransportMemory.Lease lease;

    /** Compatibility projection. Production consumers transfer the reservation with acceptReserved instead. */
    public synchronized byte[] accept(long sequence, int size, int index, byte[] bytes, long now) {
        Complete complete = acceptReserved(sequence, size, index, bytes, now);
        if (complete == null) return null;
        complete.close();
        return complete.bytes;
    }

    public synchronized Complete acceptReserved(long sequence, int size, int index, byte[] bytes, long now) {
        if (sequence < 0 || size <= 0
            || size > MAX_BYTES
            || index < 0
            || bytes == null
            || bytes.length <= 0
            || bytes.length > chunkBytes) return null;
        int count = (size - 1) / chunkBytes + 1;
        if (index >= count || bytes.length != Math.min(chunkBytes, size - index * chunkBytes) || sequence < newest)
            return null;
        if (sequence > newest) {
            if (index != 0) return null;
            clear();
            newest = sequence;
            started = now;
            total = size;
            lease = HologramTransportMemory.reserve(size);
            if (lease == null) throw new AdmissionException();
            try {
                frame = new byte[size];
            } catch (RuntimeException | Error failure) {
                clear();
                throw failure;
            }
        }
        if (frame == null || now - started > TIMEOUT_NS || size != total || index != next) {
            clear();
            return null;
        }
        System.arraycopy(bytes, 0, frame, received, bytes.length);
        next++;
        received += bytes.length;
        if (received != total) return null;
        Complete complete = new Complete(frame, lease);
        frame = null;
        lease = null;
        total = next = received = 0;
        return complete;
    }

    public synchronized void expire(long now) {
        if (frame != null && now - started > TIMEOUT_NS) clear();
    }

    public synchronized void clear() {
        frame = null;
        if (lease != null) lease.close();
        lease = null;
        total = next = received = 0;
    }

    public static final class Complete implements AutoCloseable {

        public final byte[] bytes;
        private HologramTransportMemory.Lease lease;

        Complete(byte[] bytes, HologramTransportMemory.Lease lease) {
            this.bytes = bytes;
            this.lease = lease;
        }

        public synchronized void close() {
            if (lease != null) lease.close();
            lease = null;
        }
    }

    public static final class AdmissionException extends IllegalStateException {

        AdmissionException() {
            super("shared compressed snapshot budget exhausted");
        }
    }
}
