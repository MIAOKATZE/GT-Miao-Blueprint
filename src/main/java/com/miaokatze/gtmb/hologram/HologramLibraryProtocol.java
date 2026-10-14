package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/** Independent, bounded public-library protocol. No blueprint payload is accepted in Action packets. */
public final class HologramLibraryProtocol {

    public static final int CHUNK_BYTES = 1024;
    public static final int PACKET_BYTES = 8192;
    public static final int EXPANDED_PACKET_BYTES = 16384;
    public static final int MAX_BYTES = HologramBlueprintArtifact.MAX_BYTES;
    public static final int PAGE_SIZE = 12;
    public static final long TIMEOUT_NANOS = 120L * 1000000000L;

    private HologramLibraryProtocol() {}

    static void header(String transfer, int total, String hash) throws IOException {
        try {
            if (!UUID.fromString(transfer)
                .toString()
                .equals(transfer)) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid library transfer id");
        }
        if (total <= 0 || total > MAX_BYTES || hash == null || !hash.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid library transfer size/hash");
    }

    static void chunk(int total, int offset, int nextIndex, int index, int length) throws IOException {
        if (index != nextIndex || offset < 0
            || offset >= total
            || length <= 0
            || length > CHUNK_BYTES
            || length != Math.min(CHUNK_BYTES, total - offset))
            throw new IOException("Invalid or out-of-order library chunk");
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    public static NBTTagCompound request(String op) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("op", op);
        return tag;
    }

    static NBTTagCompound reply(String op) {
        NBTTagCompound tag = request(op);
        tag.setBoolean("library", true);
        return tag;
    }

    /** Monotonic aggregate bucket; a new player cannot multiply the server's byte allowance. */
    static final class Budget {

        private long last;
        private double available;

        synchronized boolean take(int bytes, int bytesPerSecond, long now) {
            if (bytes <= 0 || bytes > CHUNK_BYTES || bytesPerSecond <= 0) return false;
            if (last == 0) last = now;
            available = Math.min(CHUNK_BYTES, available + Math.max(0L, now - last) / 1.0e9 * bytesPerSecond);
            last = now;
            if (available < bytes) return false;
            available -= bytes;
            return true;
        }
    }

    /** Both worker file downloads and tick-driven hydration wait in this same per-UUID FIFO. */
    static final class FairBudget {

        private static final long IDLE = 2L * 1000000000L;
        private final Budget budget = new Budget();
        private final java.util.LinkedHashMap<UUID, Long> waiting = new java.util.LinkedHashMap<>();

        synchronized boolean take(UUID owner, int bytes, int bytesPerSecond, long now) {
            if (owner == null || bytes <= 0 || bytes > CHUNK_BYTES) return false;
            java.util.Iterator<java.util.Map.Entry<UUID, Long>> iterator = waiting.entrySet()
                .iterator();
            while (iterator.hasNext()) if (now - iterator.next()
                .getValue() > IDLE) iterator.remove();
            if (!waiting.containsKey(owner) && waiting.size() >= 64) return false;
            // Updating an existing LinkedHashMap value leaves its FIFO position unchanged.
            waiting.put(owner, now);
            if (!waiting.keySet()
                .iterator()
                .next()
                .equals(owner) || !budget.take(bytes, bytesPerSecond, now)) return false;
            waiting.remove(owner);
            return true;
        }

        synchronized boolean waiting(UUID owner) {
            return waiting.containsKey(owner);
        }

        synchronized void cancel(UUID owner) {
            waiting.remove(owner);
        }
    }
}
