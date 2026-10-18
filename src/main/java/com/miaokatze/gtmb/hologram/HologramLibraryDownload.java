package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.nbt.NBTTagCompound;

/** Whole-file compression runs only on the library workers; packet and decoded limits remain independent. */
public final class HologramLibraryDownload {

    private HologramLibraryDownload() {}

    /** A late header or error from a cancelled task cannot occupy or complete its successor. */
    public static boolean taskMatches(long currentToken, NBTTagCompound packet) {
        return currentToken > 0 && packet != null
            && packet.hasKey("clientTaskToken", 4)
            && currentToken == packet.getLong("clientTaskToken");
    }

    static final class Encoded {

        final byte[] bytes;
        final String encoding, hash;
        final int decodedTotal;

        Encoded(byte[] bytes, String encoding, byte[] source) {
            this.bytes = bytes;
            this.encoding = encoding;
            decodedTotal = source.length;
            hash = HologramLibraryProtocol.hex(
                HologramLibraryProtocol.digest()
                    .digest(source));
        }
    }

    static Encoded encode(byte[] source) throws IOException {
        return encode(source, () -> true);
    }

    static Encoded encode(byte[] source, java.util.function.BooleanSupplier current) throws IOException {
        if (source == null || source.length == 0 || source.length > HologramLibraryProtocol.MAX_BYTES)
            throw new IOException("Invalid download source size");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(source.length, 65536));
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            for (int offset = 0; offset < source.length; offset += 8192) {
                if (!current.getAsBoolean()) throw new java.io.InterruptedIOException("Blueprint download retired");
                gzip.write(source, offset, Math.min(8192, source.length - offset));
            }
        }
        return output.size() < source.length ? new Encoded(output.toByteArray(), "gzip", source)
            : new Encoded(source, "identity", source);
    }

    public static void header(String transfer, int total, String encoding, int decodedTotal, String hash)
        throws IOException {
        HologramLibraryProtocol.header(transfer, total, hash);
        if (decodedTotal <= 0 || decodedTotal > HologramLibraryProtocol.MAX_BYTES
            || !("identity".equals(encoding) && total == decodedTotal
                || "gzip".equals(encoding) && total < decodedTotal))
            throw new IOException("Invalid download encoding/decoded size");
    }

    /** The exact decoded bytes and their final SHA are checked before parsing or committing any file. */
    public static byte[] decode(byte[] bytes, String encoding, int decodedTotal, String hash) throws IOException {
        if (bytes == null) throw new IOException("Missing download bytes");
        header("00000000-0000-0000-0000-000000000000", bytes.length, encoding, decodedTotal, hash);
        byte[] decoded;
        MessageDigest digest = HologramLibraryProtocol.digest();
        if ("gzip".equals(encoding)) {
            decoded = new byte[decodedTotal];
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                int offset = 0;
                while (offset < decoded.length) {
                    int count = input.read(decoded, offset, decoded.length - offset);
                    if (count < 0) throw new IOException("Truncated decoded download");
                    digest.update(decoded, offset, count);
                    offset += count;
                }
                // Also consumes and validates the gzip trailer; reject expansion past the declared bound.
                if (input.read() != -1) throw new IOException("Decoded download exceeds declared size");
            }
        } else {
            decoded = bytes;
            digest.update(decoded);
        }
        if (!HologramLibraryProtocol.hex(digest.digest())
            .equals(hash)) throw new IOException("Download hash mismatch");
        return decoded;
    }

    /** TCP delivers in order. Duplicates, gaps and incomplete completion fail closed without growing buffers. */
    public static final class Assembly {

        private byte[] bytes;
        private int offset, index;

        public Assembly(int total) throws IOException {
            if (total <= 0 || total > HologramLibraryProtocol.MAX_BYTES) throw new IOException("Invalid download size");
            bytes = new byte[total];
        }

        public int size() {
            return offset;
        }

        public int index() {
            return index;
        }

        public void append(int nextIndex, byte[] chunk) throws IOException {
            if (bytes == null || chunk == null) throw new IOException("Download is no longer active");
            HologramLibraryProtocol.chunk(bytes.length, offset, index, nextIndex, chunk.length);
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            offset += chunk.length;
            index++;
        }

        public byte[] finish() throws IOException {
            if (bytes == null || offset != bytes.length) throw new IOException("Incomplete download");
            byte[] complete = bytes;
            bytes = null;
            return complete;
        }

        public void cancel() {
            bytes = null;
        }
    }
}
