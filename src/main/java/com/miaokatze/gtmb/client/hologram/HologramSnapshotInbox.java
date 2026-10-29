package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.Deque;

/** One pure decoder, bounded reservations, and FIFO publication on the calling client thread. */
final class HologramSnapshotInbox<T> implements AutoCloseable {

    static final class AdmissionException extends java.io.IOException {

        AdmissionException(String message) {
            super(message);
        }
    }

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
        final long preparationBytes;

        Decoded(T value, AutoCloseable retained, long retainedBytes) {
            this(value, retained, retainedBytes, 0);
        }

        Decoded(T value, AutoCloseable retained, long retainedBytes, long preparationBytes) {
            this.value = value;
            this.retained = retained;
            this.retainedBytes = retainedBytes;
            this.preparationBytes = preparationBytes;
        }
    }

    static final class Result<T> implements AutoCloseable {

        final T value;
        final Exception failure;
        final long transportSequence;
        final long retainedBytes;
        private AutoCloseable retained;
        private java.util.function.BooleanSupplier current = () -> true;

        boolean current() {
            return current.getAsBoolean();
        }

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

        /** Work admission ends at publication, even when a view keeps the immutable model alive. */
        void publish(java.util.function.Consumer<? super T> consumer) {
            try {
                if (current()) consumer.accept(value);
            } finally {
                close();
            }
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

    /** Owner ingress never waits. It may publish ready FIFO entries to make bounded room. */
    boolean offer(byte[] bytes, long sequence, AutoCloseable reservation, long expectedEpoch,
        java.util.function.Consumer<Result<T>> readyConsumer) {
        if (bytes == null || bytes.length == 0 || bytes.length > byteBudget) {
            close(reservation);
            throw new IllegalArgumentException("snapshot byte budget");
        }
        boolean accepted = false;
        try {
            for (int remaining = capacity;; remaining--) {
                Result<T> ready;
                synchronized (this) {
                    if (closed || expectedEpoch != epoch) return false;
                    if (offer(bytes, sequence)) {
                        entries.getLast().reservation = reservation;
                        accepted = true;
                        return true;
                    }
                    ready = readyConsumer != null && remaining > 0 ? poll() : null;
                }
                if (ready == null) return false;
                readyConsumer.accept(ready);
            }
        } finally {
            if (!accepted) close(reservation);
        }
    }

    /** Small legacy/control messages cannot overtake earlier compressed ACKs. */
    synchronized boolean offerValue(T value) {
        if (!room(0)) return false;
        entries.addLast(new Entry<>(epoch, null, value, -1));
        notifyAll();
        return true;
    }

    /** Legacy controls use the same bounded FIFO without waiting on their own consumer thread. */
    boolean offerValue(T value, long expectedEpoch, java.util.function.Consumer<Result<T>> readyConsumer) {
        for (int remaining = capacity;; remaining--) {
            Result<T> ready;
            synchronized (this) {
                if (closed || expectedEpoch != epoch) return false;
                if (offerValue(value)) return true;
                ready = readyConsumer != null && remaining > 0 ? poll() : null;
            }
            if (ready == null) return false;
            readyConsumer.accept(ready);
        }
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

    /** Background ingress may wait; the Minecraft thread uses the bounded nonblocking offer overload. */
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
        return !closed && entry.epoch == epoch
            && (!(entry.reservation instanceof com.miaokatze.gtmb.hologram.HologramSnapshotAssembly.Complete)
                || ((com.miaokatze.gtmb.hologram.HologramSnapshotAssembly.Complete) entry.reservation).current());
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
            boolean retry = false;
            try (com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease ignored = cpu) {
                Entry<T> decoding = entry;
                Decoded<T> decoded = decoder.decode(entry.bytes, () -> current(decoding));
                long limit = com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedByteLimit();
                long inputBytes = entry.reservation instanceof com.miaokatze.gtmb.hologram.HologramSnapshotAssembly.Complete
                    ? entry.cost
                    : 0;
                if (decoded.preparationBytes > limit - decoded.retainedBytes - inputBytes) {
                    result = new Result<>(
                        decoded.value,
                        new AdmissionException("Snapshot preparation exceeds the shared work-memory budget"),
                        entry.transportSequence,
                        decoded.retained,
                        decoded.retainedBytes);
                } else {
                    com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease preparation = decoded.preparationBytes
                        == 0 ? null
                            : com.miaokatze.gtmb.hologram.HologramProcessGovernor
                                .tryRetain("snapshot.materialized", decoded.preparationBytes);
                    if (decoded.preparationBytes != 0 && preparation == null) {
                        close(decoded.retained);
                        result = null;
                        retry = true;
                    } else {
                        result = new Result<>(
                            decoded.value,
                            null,
                            entry.transportSequence,
                            decoded.retained,
                            decoded.retainedBytes);
                        result.materialized = preparation == null ? null : preparation.transfer();
                    }
                }
            } catch (com.miaokatze.gtmb.hologram.HologramSnapshotCodec.BusyException busy) {
                // Quota contention is not a terminal decode failure. Keep the exact compressed ACK and its
                // reservation at the FIFO head; a single-player server may still own the shared byte budget.
                long inputBytes = entry.reservation instanceof com.miaokatze.gtmb.hologram.HologramSnapshotAssembly.Complete
                    ? entry.cost
                    : 0;
                if (busy.requestedBytes
                    > com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedByteLimit() - inputBytes) {
                    result = new Result<>(null, new AdmissionException(busy.getMessage()), entry.transportSequence);
                } else {
                    result = null;
                    retry = true;
                }
            } catch (Exception e) {
                result = new Result<>(null, e, entry.transportSequence);
            }
            synchronized (this) {
                if (retry && !closed && entry.epoch == epoch) {
                    active = null;
                    // The CPU lease has already closed. Only this decoder worker waits, never the owner tick.
                    try {
                        wait(25);
                    } catch (InterruptedException ignored) {}
                    continue;
                }
                entry.bytes = null;
                if (result != null) {
                    final long admittedEpoch = entry.epoch;
                    final java.util.function.BooleanSupplier context = entry.reservation instanceof com.miaokatze.gtmb.hologram.HologramSnapshotAssembly.Complete
                        ? ((com.miaokatze.gtmb.hologram.HologramSnapshotAssembly.Complete) entry.reservation)
                            .detachedContext()
                        : null;
                    result.current = () -> epoch() == admittedEpoch && (context == null || context.getAsBoolean());
                }
                if (entry.epoch == epoch) entry.result = result;
                else {
                    release(entry);
                    reservedBytes -= entry.cost;
                    if (result != null) result.close();
                }
                active = null;
                notifyAll();
            }
        }
    }
}
