package com.miaokatze.gtmb.hologram;

/** Small public hydration slices; timeout measures inactivity, so slow large blueprints can complete. */
final class HologramLibrarySnapshotAssembly {

    private final java.util.function.LongConsumer released;

    HologramLibrarySnapshotAssembly() {
        this(null);
    }

    HologramLibrarySnapshotAssembly(java.util.function.LongConsumer released) {
        this.released = released;
    }

    private long newest = -1, activity;
    private int total, index, offset;
    private byte[] bytes;
    private HologramTransportMemory.Lease lease;
    private byte[] expectedHash;
    private java.security.MessageDigest digest;

    synchronized byte[] accept(long sequence, int size, int incomingIndex, byte[] chunk, byte[] hash, long now) {
        HologramSnapshotAssembly.Complete complete = acceptReserved(sequence, size, incomingIndex, chunk, hash, now);
        if (complete == null) return null;
        complete.close();
        return complete.bytes;
    }

    synchronized HologramSnapshotAssembly.Complete acceptReserved(long sequence, int size, int incomingIndex,
        byte[] chunk, byte[] hash, long now) {
        if (sequence < newest || sequence < 0
            || size <= 0
            || size > HologramSnapshotAssembly.MAX_BYTES
            || chunk == null
            || chunk.length == 0
            || chunk.length > HologramLibraryProtocol.CHUNK_BYTES
            || hash == null
            || hash.length != 32) return null;
        if (sequence > newest) {
            if (incomingIndex != 0) return null;
            clear();
            newest = sequence;
            total = size;
            activity = now;
            lease = HologramTransportMemory.reserve(size);
            if (lease == null) {
                if (released != null) released.accept(sequence);
                throw new HologramSnapshotAssembly.AdmissionException();
            }
            try {
                bytes = new byte[size];
            } catch (RuntimeException | Error failure) {
                clear();
                throw failure;
            }
            expectedHash = hash.clone();
            digest = HologramLibraryProtocol.digest();
        }
        if (bytes == null || now - activity > HologramLibraryProtocol.TIMEOUT_NANOS
            || size != total
            || incomingIndex != index
            || !java.util.Arrays.equals(expectedHash, hash)
            || chunk.length != Math.min(HologramLibraryProtocol.CHUNK_BYTES, total - offset)) {
            clear();
            return null;
        }
        System.arraycopy(chunk, 0, bytes, offset, chunk.length);
        digest.update(chunk);
        activity = now;
        index++;
        offset += chunk.length;
        if (offset != total) return null;
        if (!java.security.MessageDigest.isEqual(expectedHash, digest.digest())) {
            clear();
            return null;
        }
        HologramSnapshotAssembly.Complete result = new HologramSnapshotAssembly.Complete(
            bytes,
            lease,
            released,
            sequence);
        bytes = null;
        lease = null;
        clear();
        return result;
    }

    synchronized void expire(long now) {
        if (bytes != null && now - activity > HologramLibraryProtocol.TIMEOUT_NANOS) clear();
    }

    synchronized void supersede(long sequence) {
        if (sequence > newest) {
            clear();
            newest = sequence;
        }
    }

    synchronized void clear() {
        bytes = null;
        if (lease != null) {
            lease.close();
            if (released != null) released.accept(newest);
        }
        lease = null;
        expectedHash = null;
        digest = null;
        total = index = offset = 0;
    }

    synchronized void reset() {
        clear();
        newest = -1;
    }
}
