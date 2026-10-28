package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/** Independent, bounded public-library protocol. No blueprint payload is accepted in Action packets. */
public final class HologramLibraryProtocol {

    public static final int VERSION = 3;
    public static final int CHUNK_BYTES = 16 * 1024;
    public static final int PACKET_BYTES = 24 * 1024;
    public static final int EXPANDED_PACKET_BYTES = 32 * 1024;
    static final int BURST_BYTES = 2 * CHUNK_BYTES;
    public static final int MAX_UPLOAD_WINDOW = 8;
    static final int ACTIVE_UPLOADS = 4;
    static final int CONTROL_REQUESTS = 4;
    static final int INBOUND_REQUESTS = 64;
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

    static boolean uploadChunk(NBTTagCompound request) {
        String op = request.getString("op");
        return "uploadChunk".equals(op) || "privateChunk".equals(op)
            || "previewChunk".equals(op)
            || "authorReviewChunk".equals(op);
    }

    /** Optional v3 ready credit; absent on old servers. ACKs confirm only bytes actually sent. */
    public static final class UploadWindow {

        private final String transfer;
        private final long task;
        private final int chunks;
        private int sent, acknowledged, window = 1;
        private boolean ready;

        public UploadWindow(String transfer, long task, int bytes) {
            if (bytes <= 0 || bytes > MAX_BYTES) throw new IllegalArgumentException("Invalid upload byte count");
            this.transfer = transfer;
            this.task = task;
            chunks = (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
        }

        public UploadWindow(NBTTagCompound begin) {
            this(begin.getString("transfer"), begin.getLong("clientTaskToken"), begin.getInteger("total"));
        }

        private boolean matches(NBTTagCompound reply) {
            return reply.hasKey("transfer", 8) && reply.hasKey("clientTaskToken", 4)
                && transfer.equals(reply.getString("transfer"))
                && task == reply.getLong("clientTaskToken");
        }

        public boolean ready(NBTTagCompound reply) throws IOException {
            if (!matches(reply) || ready) return false;
            if (!reply.hasKey("nextIndex", 3) || reply.getInteger("nextIndex") != 0)
                throw new IOException("Upload resume is not supported");
            if (reply.hasKey("uploadWindow") && !reply.hasKey("uploadWindow", 3))
                throw new IOException("Invalid upload window type");
            int offered = reply.hasKey("uploadWindow", 3) ? reply.getInteger("uploadWindow") : 1;
            if (offered < 1 || offered > MAX_UPLOAD_WINDOW) throw new IOException("Invalid upload window");
            window = offered;
            ready = true;
            return true;
        }

        public boolean canSend() {
            return ready && sent < chunks && sent - acknowledged < window;
        }

        public int nextSent() {
            return sent;
        }

        public long task() {
            return task;
        }

        public int acknowledged() {
            return acknowledged;
        }

        public boolean inFlight() {
            return sent > acknowledged;
        }

        public void sent(int index) {
            if (!canSend() || index != sent) throw new IllegalStateException("Upload send credit exceeded");
            sent++;
        }

        public boolean acknowledge(NBTTagCompound reply) throws IOException {
            if (!matches(reply)) return false;
            if (!ready || !reply.hasKey("nextIndex", 3) || !reply.hasKey("index", 3))
                throw new IOException("Invalid cumulative upload acknowledgement");
            int next = reply.getInteger("nextIndex");
            if (next < 1 || next > sent || reply.getInteger("index") != next - 1)
                throw new IOException("Invalid cumulative upload acknowledgement");
            if (next <= acknowledged) return false;
            acknowledged = next;
            return true;
        }

        public boolean complete(NBTTagCompound reply) throws IOException {
            if (!matches(reply)) return false;
            if (!ready || sent != chunks) throw new IOException("Upload completed before all bytes were sent");
            return true;
        }
    }

    /** Separate fixed controls from admitted data; used before either mailbox can grow. */
    static final class RequestQuota {

        private final int chunkLimit, controlLimit, totalLimit;
        int chunks, controls;

        RequestQuota() {
            this(MAX_UPLOAD_WINDOW, CONTROL_REQUESTS);
        }

        RequestQuota(int chunkLimit, int controlLimit) {
            this(chunkLimit, controlLimit, chunkLimit + controlLimit);
        }

        RequestQuota(int chunkLimit, int controlLimit, int totalLimit) {
            this.chunkLimit = chunkLimit;
            this.controlLimit = controlLimit;
            this.totalLimit = totalLimit;
        }

        boolean acquire(boolean chunk, boolean admitted) {
            if (chunks + controls >= totalLimit) return false;
            if (chunk) {
                if (!admitted || chunks >= chunkLimit) return false;
                chunks++;
            } else {
                if (controls >= controlLimit) return false;
                controls++;
            }
            return true;
        }

        void release(boolean chunk) {
            if (chunk ? chunks <= 0 : controls <= 0)
                throw new IllegalArgumentException("Library request quota underflow");
            if (chunk) chunks--;
            else controls--;
        }

        boolean empty() {
            return chunks == 0 && controls == 0;
        }

        void clear() {
            chunks = controls = 0;
        }
    }

    static final class RequestQueue {

        private final java.util.ArrayDeque<NBTTagCompound> controls = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<NBTTagCompound> chunks = new java.util.ArrayDeque<>();

        boolean offer(NBTTagCompound request, boolean admitted) {
            boolean chunk = uploadChunk(request);
            java.util.ArrayDeque<NBTTagCompound> queue = chunk ? chunks : controls;
            if (chunk && !admitted || queue.size() >= (chunk ? MAX_UPLOAD_WINDOW : CONTROL_REQUESTS)) return false;
            queue.addLast((NBTTagCompound) request.copy());
            return true;
        }

        NBTTagCompound pollControl() {
            return controls.pollFirst();
        }

        NBTTagCompound peekChunk() {
            return chunks.peekFirst();
        }

        NBTTagCompound pollChunk() {
            return chunks.pollFirst();
        }

        boolean isEmpty() {
            return controls.isEmpty() && chunks.isEmpty();
        }

        void discard(String transfer, long task) {
            chunks.removeIf(
                request -> transfer.equals(request.getString("transfer"))
                    && task == request.getLong("clientTaskToken"));
        }
    }

    /** Monotonic aggregate bucket; a new player cannot multiply the server's byte allowance. */
    static final class Budget {

        private final boolean upload;
        private long last;
        private double available;

        Budget() {
            this(false);
        }

        Budget(boolean upload) {
            this.upload = upload;
        }

        synchronized boolean take(int bytes, int bytesPerSecond, long now) {
            if (bytes <= 0 || bytes > CHUNK_BYTES || bytesPerSecond <= 0) return false;
            if (last == 0) last = now;
            // Keep the remainder between 50ms polls without allowing an unbounded idle burst.
            int burst = upload
                ? Math.max(
                    BURST_BYTES,
                    (int) (((long) bytesPerSecond + 20L * CHUNK_BYTES - 1) / (20L * CHUNK_BYTES)) * CHUNK_BYTES)
                : Math.max(
                    BURST_BYTES,
                    ((int) (((long) bytesPerSecond + 20L * CHUNK_BYTES - 1) / (20L * CHUNK_BYTES)) + 1) * CHUNK_BYTES);
            available = Math.min(burst, available + Math.max(0L, now - last) / 1.0e9 * bytesPerSecond);
            last = now;
            if (available < bytes) return false;
            available -= bytes;
            return true;
        }
    }

    /** Both worker file downloads and tick-driven hydration wait in this same per-UUID FIFO. */
    static final class FairBudget {

        private static final long IDLE = 2L * 1000000000L;
        private final Budget budget;
        private final java.util.LinkedHashMap<UUID, Long> waiting = new java.util.LinkedHashMap<>();

        FairBudget() {
            this(false);
        }

        FairBudget(boolean upload) {
            budget = new Budget(upload);
        }

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
