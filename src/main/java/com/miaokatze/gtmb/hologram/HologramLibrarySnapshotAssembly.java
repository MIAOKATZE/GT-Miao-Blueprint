package com.miaokatze.gtmb.hologram;

/** Small public hydration slices; timeout measures inactivity, so slow large blueprints can complete. */
final class HologramLibrarySnapshotAssembly {

    private long newest = -1, activity;
    private int total, index, offset;
    private java.io.ByteArrayOutputStream bytes;
    private byte[] expectedHash;
    private java.security.MessageDigest digest;

    synchronized byte[] accept(long sequence, int size, int incomingIndex, byte[] chunk, byte[] hash, long now) {
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
            bytes = new java.io.ByteArrayOutputStream(HologramLibraryProtocol.CHUNK_BYTES);
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
        bytes.write(chunk, 0, chunk.length);
        digest.update(chunk);
        activity = now;
        index++;
        offset += chunk.length;
        if (offset != total) return null;
        byte[] result = java.security.MessageDigest.isEqual(expectedHash, digest.digest()) ? bytes.toByteArray() : null;
        clear();
        return result;
    }

    synchronized void supersede(long sequence) {
        if (sequence > newest) {
            clear();
            newest = sequence;
        }
    }

    synchronized void clear() {
        bytes = null;
        expectedHash = null;
        digest = null;
        total = index = offset = 0;
    }

    synchronized void reset() {
        clear();
        newest = -1;
    }
}
