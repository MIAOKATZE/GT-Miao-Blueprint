package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.Deque;

/** One pure decoder, bounded reservations, and FIFO publication on the calling client thread. */
final class HologramSnapshotInbox<T> implements AutoCloseable {

    interface Decoder<T> {

        T decode(byte[] bytes) throws Exception;
    }

    interface CancellableDecoder<T> {

        Decoded<T> decode(byte[] bytes, java.util.function.BooleanSupplier current) throws Exception;
    }

    static final class Decoded<T> {

        final T value;
        final AutoCloseable retained;
        final long retainedBytes;

        Decoded(T value, AutoCloseable retained, long retainedBytes) {
            this.value = value;
            this.retained = retained;
            this.retainedBytes = retainedBytes;
        }
    }

    static final class Result<T> implements AutoCloseable {

        final T value;
        final Exception failure;
        final long transportSequence;
        final long retainedBytes;
        private AutoCloseable retained;
        com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease materialized;

        Result(T value, Exception failure, long transportSequence) {
            this(value, failure, transportSequence, null, 0);
        }

        Result(T value, Exception failure, long transportSequence, AutoCloseable retained, long retainedBytes) {
            this.value = value;
            this.failure = failure;
            this.transportSequence = transportSequence;
            this.retained = retained;
            this.retainedBytes = retainedBytes;
        }

        public void close() {
            HologramSnapshotInbox.close(retained);
            retained = null;
            if (materialized != null) materialized.close();
            materialized = null;
        }
    }

    private static final class Entry<T> {

        final long epoch;
        final int cost;
        final long transportSequence;
        byte[] bytes;
        AutoCloseable reservation;
        Result<T> result;

        Entry(long epoch, byte[] bytes, T value, long transportSequence) {
            this.epoch = epoch;
            this.transportSequence = transportSequence;
            this.bytes = bytes;
            cost = bytes == null ? 0 : bytes.length;
            if (bytes == null) result = new Result<>(value, null, transportSequence);
        }
    }

    private final CancellableDecoder<T> decoder;
    private final int capacity, byteBudget, dataCapacity;
    private final Deque<Entry<T>> entries = new ArrayDeque<>();
    private Entry<T> active;
    private Thread worker;
    private long epoch;
    private int reservedBytes;
    private boolean closed;

    HologramSnapshotInbox(int capacity, int byteBudget, Decoder<T> decoder) {
        this(capacity, byteBudget, decoder, capacity);
    }

    HologramSnapshotInbox(int capacity, int byteBudget, Decoder<T> decoder, int dataCapacity) {
        this(capacity, byteBudget, dataCapacity, adapt(decoder));
    }

    HologramSnapshotInbox(int capacity, int byteBudget, int dataCapacity, CancellableDecoder<T> decoder) {
        if (capacity < 1 || byteBudget < 1 || decoder == null || dataCapacity < 1 || dataCapacity > capacity)
            throw new IllegalArgumentException("decoder budget");
        this.capacity = capacity;
        this.dataCapacity = dataCapacity;
        this.byteBudget = byteBudget;
        this.decoder = decoder;
    }

    private static <T> CancellableDecoder<T> adapt(Decoder<T> decoder) {
        if (decoder == null) throw new IllegalArgumentException("decoder budget");
        return (bytes, current) -> new Decoded<>(decoder.decode(bytes), null, 0);
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
        int dataCount = active != null && active.epoch != epoch ? 1 : 0;
        if (cost > 0) for (Entry<T> entry : entries) if (entry.cost > 0) dataCount++;
        return count < capacity && (cost == 0 || dataCount < dataCapacity) && cost <= byteBudget - reservedBytes;
    }

    synchronized Result<T> poll() {
        Entry<T> first = entries.peekFirst();
        if (first == null || first.result == null) return null;
        entries.removeFirst();
        reservedBytes -= first.cost;
        release(first);
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
        for (Entry<T> entry : entries) if (entry != active) {
            release(entry);
            entry.bytes = null;
            if (entry.result != null) entry.result.close();
        }
        entries.clear();
        reservedBytes = active == null ? 0 : active.cost;
        notifyAll();
    }

    public synchronized void close() {
        clear();
        closed = true;
        notifyAll();
    }

    synchronized long epoch() {
        return epoch;
    }

    /** Network ingress may wait for bounded capacity; the Minecraft thread only calls poll/clear. */
    synchronized boolean put(byte[] bytes, long sequence, AutoCloseable reservation, long expectedEpoch) {
        if (bytes == null || bytes.length == 0 || bytes.length > byteBudget) {
            close(reservation);
            throw new IllegalArgumentException("snapshot byte budget");
        }
        boolean interrupted = false;
        try {
            while (!closed && expectedEpoch == epoch && !room(bytes.length)) {
                try {
                    wait();
                } catch (InterruptedException problem) {
                    interrupted = true;
                }
            }
            if (closed || expectedEpoch != epoch) {
                close(reservation);
                return false;
            }
            offer(bytes, sequence);
            entries.getLast().reservation = reservation;
            return true;
        } finally {
            if (interrupted) Thread.currentThread()
                .interrupt();
        }
    }

    synchronized boolean putValue(T value, long expectedEpoch) {
        boolean interrupted = false;
        try {
            while (!closed && expectedEpoch == epoch && !room(0)) {
                try {
                    wait();
                } catch (InterruptedException problem) {
                    interrupted = true;
                }
            }
            return !closed && expectedEpoch == epoch && offerValue(value);
        } finally {
            if (interrupted) Thread.currentThread()
                .interrupt();
        }
    }

    private static void release(Entry<?> entry) {
        close(entry.reservation);
        entry.reservation = null;
    }

    private static void close(AutoCloseable reservation) {
        if (reservation == null) return;
        try {
            reservation.close();
        } catch (Exception failure) {
            throw new IllegalStateException("snapshot reservation release", failure);
        }
    }

    private synchronized boolean current(Entry<T> entry) {
        return !closed && entry.epoch == epoch;
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
            }
            com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease cpu = com.miaokatze.gtmb.hologram.HologramProcessGovernor
                .tryHeavyCpu("snapshot.decode");
            if (cpu == null) {
                synchronized (this) {
                    try {
                        wait(5);
                    } catch (InterruptedException ignored) {}
                }
                continue;
            }
            synchronized (this) {
                if (entry.epoch != epoch || !entries.contains(entry)) {
                    cpu.close();
                    continue;
                }
                active = entry;
            }
            Result<T> result;
            try (com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease ignored = cpu) {
                Entry<T> decoding = entry;
                Decoded<T> decoded = decoder.decode(entry.bytes, () -> current(decoding));
                result = new Result<>(
                    decoded.value,
                    null,
                    entry.transportSequence,
                    decoded.retained,
                    decoded.retainedBytes);
            } catch (Exception e) {
                result = new Result<>(null, e, entry.transportSequence);
            }
            synchronized (this) {
                entry.bytes = null;
                release(entry);
                if (entry.epoch == epoch) entry.result = result;
                else {
                    reservedBytes -= entry.cost;
                    result.close();
                }
                active = null;
                notifyAll();
            }
        }
    }
}
