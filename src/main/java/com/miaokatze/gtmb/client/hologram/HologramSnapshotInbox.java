package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.Deque;

/** One pure decoder, bounded reservations, and FIFO publication on the calling client thread. */
final class HologramSnapshotInbox<T> implements AutoCloseable {

    interface Decoder<T> {

        T decode(byte[] bytes) throws Exception;
    }

    static final class Result<T> {

        final T value;
        final Exception failure;
        final long transportSequence;

        Result(T value, Exception failure, long transportSequence) {
            this.value = value;
            this.failure = failure;
            this.transportSequence = transportSequence;
        }
    }

    private static final class Entry<T> {

        final long epoch;
        final int cost;
        final long transportSequence;
        byte[] bytes;
        Result<T> result;

        Entry(long epoch, byte[] bytes, T value, long transportSequence) {
            this.epoch = epoch;
            this.transportSequence = transportSequence;
            this.bytes = bytes;
            cost = bytes == null ? 0 : bytes.length;
            if (bytes == null) result = new Result<>(value, null, transportSequence);
        }
    }

    private final Decoder<T> decoder;
    private final int capacity, byteBudget;
    private final Deque<Entry<T>> entries = new ArrayDeque<>();
    private Entry<T> active;
    private Thread worker;
    private long epoch;
    private int reservedBytes;
    private boolean closed;

    HologramSnapshotInbox(int capacity, int byteBudget, Decoder<T> decoder) {
        if (capacity < 1 || byteBudget < 1 || decoder == null) throw new IllegalArgumentException("decoder budget");
        this.capacity = capacity;
        this.byteBudget = byteBudget;
        this.decoder = decoder;
    }

    /** Ownership of the bytes transfers only when accepted. Reservations include decoded, unconsumed results. */
    synchronized boolean offer(byte[] bytes) {
        return offer(bytes, -1);
    }

    synchronized boolean offer(byte[] bytes, long transportSequence) {
        if (bytes == null || bytes.length == 0 || bytes.length > byteBudget)
            throw new IllegalArgumentException("snapshot byte budget");
        if (!room(bytes.length)) return false;
        entries.addLast(new Entry<>(epoch, bytes, null, transportSequence));
        reservedBytes += bytes.length;
        if (worker == null) {
            worker = new Thread(this::decodeLoop, "GTMB snapshot decoder");
            worker.setDaemon(true);
            worker.start();
        }
        notifyAll();
        return true;
    }

    /** Small legacy/control messages cannot overtake earlier compressed ACKs. */
    synchronized boolean offerValue(T value) {
        if (!room(0)) return false;
        entries.addLast(new Entry<>(epoch, null, value, -1));
        notifyAll();
        return true;
    }

    private boolean room(int cost) {
        if (closed) throw new IllegalStateException("decoder closed");
        int count = entries.size() + (active != null && active.epoch != epoch ? 1 : 0);
        return count < capacity && cost <= byteBudget - reservedBytes;
    }

    synchronized Result<T> poll() {
        Entry<T> first = entries.peekFirst();
        if (first == null || first.result == null) return null;
        entries.removeFirst();
        reservedBytes -= first.cost;
        notifyAll();
        return first.result;
    }

    /** Saturation fallback: retain every ACK in order instead of discarding or growing a queue. */
    synchronized Result<T> awaitNext() {
        boolean interrupted = false;
        try {
            for (;;) {
                Result<T> result = poll();
                if (result != null || entries.isEmpty() && active == null || closed) return result;
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread()
                .interrupt();
        }
    }

    /** An in-flight pure decode may finish; its output is never published into the new world/epoch. */
    synchronized void clear() {
        epoch++;
        entries.clear();
        reservedBytes = active == null ? 0 : active.cost;
        notifyAll();
    }

    public synchronized void close() {
        clear();
        closed = true;
        notifyAll();
    }

    private void decodeLoop() {
        for (;;) {
            Entry<T> entry = null;
            synchronized (this) {
                while (entry == null && !closed) {
                    for (Entry<T> candidate : entries) {
                        if (candidate.result == null) {
                            entry = candidate;
                            break;
                        }
                    }
                    if (entry == null) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {
                            // No Minecraft state or thread interrupt is used for cancellation; epoch owns publication.
                        }
                    }
                }
                if (closed) return;
                active = entry;
            }
            Result<T> result;
            try {
                result = new Result<>(decoder.decode(entry.bytes), null, entry.transportSequence);
            } catch (Exception e) {
                result = new Result<>(null, e, entry.transportSequence);
            }
            synchronized (this) {
                entry.bytes = null;
                if (entry.epoch == epoch) entry.result = result;
                else reservedBytes -= entry.cost;
                active = null;
                notifyAll();
            }
        }
    }
}
