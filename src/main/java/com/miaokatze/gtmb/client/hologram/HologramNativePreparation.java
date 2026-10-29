package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/** One bounded, iterative dependency lane. Native callbacks never nest on the Java stack. */
final class HologramNativePreparation<T> {

    enum Outcome {
        ABSENT,
        WAITING,
        READY,
        FAILED
    }

    enum Stage {
        DATA,
        READY
    }

    static final int MAX_DEPTH = 512, MAX_ATTEMPTS = 512, MAX_CALLBACKS = 32;

    static final class Budget {

        final long deadline;
        final LongSupplier clock;
        private int remaining, used;

        Budget(long deadline, int callbacks, LongSupplier clock) {
            this.deadline = deadline;
            this.remaining = Math.max(0, Math.min(MAX_CALLBACKS, callbacks));
            this.clock = clock;
        }

        boolean available() {
            return remaining > 0 && clock.getAsLong() < deadline;
        }

        int used() {
            return used;
        }

        boolean take() {
            if (!available()) return false;
            remaining--;
            used++;
            return true;
        }
    }

    interface Backend<T> {

        Outcome outcome(T value, Stage stage);

        Attempt<T> attempt(T value, Stage stage);

        void fail(T value, String reason);

        void discard(T value);
    }

    static final class Attempt<T> {

        final T blocker;
        final Stage stage;
        final boolean appearance;

        Attempt(T blocker, Stage stage, boolean appearance) {
            this.blocker = blocker;
            this.stage = stage;
            this.appearance = appearance;
        }

        static <T> Attempt<T> complete() {
            return new Attempt<>(null, Stage.READY, false);
        }
    }

    private static final class Frame<T> {

        final T value;
        final Stage stage;
        int attempts;

        Frame(T value, Stage stage) {
            this.value = value;
            this.stage = stage;
        }
    }

    private final Backend<T> backend;
    private final Deque<Frame<T>> frames = new ArrayDeque<>();
    private boolean callback, closed;

    HologramNativePreparation(Backend<T> backend) {
        this.backend = backend;
    }

    boolean active() {
        return !frames.isEmpty();
    }

    boolean inCallback() {
        return callback;
    }

    int depth() {
        return frames.size();
    }

    /** Admission never starts a callback. Busy callers retry through the existing deferred consumer. */
    boolean request(T value, Stage stage) {
        if (closed || callback || !frames.isEmpty()) return false;
        if (backend.outcome(value, stage) != Outcome.WAITING) return false;
        frames.addLast(new Frame<>(value, stage));
        return true;
    }

    void advance(Budget budget) {
        if (closed || callback) return;
        // Both successful transitions and native attempts have a finite per-pump work bound.
        int transitions = MAX_DEPTH + MAX_CALLBACKS;
        while (!frames.isEmpty() && transitions-- > 0 && budget.available()) {
            Frame<T> frame = frames.peekLast();
            Outcome outcome = backend.outcome(frame.value, frame.stage);
            if (outcome != Outcome.WAITING) {
                frames.removeLast();
                if (outcome == Outcome.FAILED) failLane("native dependency failed");
                continue;
            }
            if (!budget.take()) return;
            if (++frame.attempts > MAX_ATTEMPTS) {
                failLane("native preparation attempt budget exceeded");
                return;
            }
            Stage step = backend.outcome(frame.value, Stage.DATA) == Outcome.READY ? Stage.READY : Stage.DATA;
            Attempt<T> attempt;
            callback = true;
            try {
                attempt = backend.attempt(frame.value, step);
            } finally {
                callback = false;
            }
            if (attempt.appearance) {
                // Payload demand belongs to the existing source queue. Do not monopolize this lane while waiting.
                while (!frames.isEmpty()) backend.discard(frames.removeLast().value);
                return;
            }
            if (attempt.blocker != null) {
                Outcome needed = backend.outcome(attempt.blocker, attempt.stage);
                if (needed == Outcome.FAILED) {
                    failLane("native dependency failed");
                    return;
                }
                if (needed != Outcome.WAITING) continue;
                for (Frame<T> ancestor : frames) if (ancestor.value == attempt.blocker) {
                    failLane("native preparation dependency cycle");
                    return;
                }
                if (frames.size() == MAX_DEPTH) {
                    failLane("native preparation dependency depth budget exceeded");
                    return;
                }
                frames.addLast(new Frame<>(attempt.blocker, attempt.stage));
            } else if (backend.outcome(frame.value, step) == Outcome.WAITING) {
                failLane("native preparation made no progress");
                return;
            }
        }
    }

    private void failLane(String reason) {
        while (!frames.isEmpty()) backend.fail(frames.removeLast().value, reason);
    }

    void close() {
        closed = true;
        while (!frames.isEmpty()) backend.discard(frames.removeLast().value);
    }
}
