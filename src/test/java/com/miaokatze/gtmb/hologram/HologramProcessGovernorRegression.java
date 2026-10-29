package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic admission, subscriber, epoch and permit lifecycle checks without game state. */
public final class HologramProcessGovernorRegression {

    private static final AtomicReference<HologramProcessGovernor.Lease> RETIRED = new AtomicReference<>();
    private static final AtomicInteger REAPER_CALLS = new AtomicInteger(), REAPER_FAILURES = new AtomicInteger();
    private static volatile boolean reapFixture, failReaperFixture;
    private static final Runnable BROKEN_REAPER = () -> {
        if (failReaperFixture) {
            REAPER_FAILURES.incrementAndGet();
            throw new IllegalStateException("retired fixture");
        }
    };
    private static final Runnable RETIRED_REAPER = () -> {
        if (!reapFixture) return;
        check(!Thread.holdsLock(HologramProcessGovernor.class), "retired callbacks run outside quota monitor");
        REAPER_CALLS.incrementAndGet();
        HologramProcessGovernor.Lease nested = HologramProcessGovernor.tryRetain("reaper-recursion", 0);
        check(nested != null, "reaper may close or account a lease without lock inversion");
        nested.close();
        HologramProcessGovernor.Lease retired = RETIRED.getAndSet(null);
        if (retired != null) retired.close();
        reapFixture = false;
    };

    public static void run() throws Exception {
        byteAdmission();
        nestedOwnerDeadline();
        subscriberLifecycle();
        epochAndException();
        reentry();
        ownedProducerLifecycle();
        cooperativeCancellation();
        sharedOutputDispatch();
        fairWorkers();
        crossDomainRetiredReaper();
    }

    private static void nestedOwnerDeadline() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(10);
        HologramWorkBudget.begin(100, now::get);
        HologramWorkBudget.quota(80);
        HologramWorkBudget.begin(40, now::get);
        check(HologramWorkBudget.remainingNanos(0) == 30, "nested continuation has child deadline");
        HologramWorkBudget.end();
        check(HologramWorkBudget.remainingNanos(0) == 70, "child restores lifecycle parent quota");
        HologramWorkBudget.end();
        check(HologramWorkBudget.remainingNanos(7) == 7, "lifecycle exit restores standalone behavior");
        check(HologramWorkBudget.tickNanos(0, 0) == 40_000_000L, "idle tick uses available spare time");
        check(
            HologramWorkBudget.tickNanos(0, 45_000_000L) == 1_000_000L,
            "busy tick preserves bounded minimum progress");
    }

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("HologramProcessGovernorRegression passed");
    }

    private static void byteAdmission() throws Exception {
        long baseline = HologramProcessGovernor.retainedBytes();
        HologramProcessGovernor.Lease bytes = HologramProcessGovernor.retain("test", 32);
        check(
            HologramProcessGovernor.tryRetain("oversize", HologramProcessGovernor.retainedByteLimit()) == null,
            "admission rejects before allocation");
        try {
            HologramProcessGovernor.acquireHeavyCpu("hold-and-wait");
            throw new AssertionError("holding bytes must not wait for CPU");
        } catch (IllegalStateException expected) {}
        bytes.transfer();
        try (HologramProcessGovernor.Lease ignored = HologramProcessGovernor.acquireHeavyCpu("transferred")) {
            check(HologramProcessGovernor.retainedBytes() == baseline + 32, "transfer preserves byte accounting");
        }
        bytes.close();
        bytes.close();
        check(HologramProcessGovernor.retainedBytes() == baseline, "cross-owner close is exactly once");
        HologramWorkBudget.begin(Long.MAX_VALUE);
        try {
            HologramProcessGovernor.acquireHeavyCpu("owner");
            throw new AssertionError("owner must never wait for CPU");
        } catch (IllegalStateException expected) {
            // Owner marks come from the same scheduler used in production.
        } finally {
            HologramWorkBudget.end();
        }
    }

    private static void subscriberLifecycle() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        Executor executor = queued::add;
        HologramProcessGovernor.Subscription<Integer> first = HologramProcessGovernor
            .singleFlight("same", 1, calls::incrementAndGet, executor);
        HologramProcessGovernor.Subscription<Integer> second = HologramProcessGovernor
            .singleFlight("same", 1, () -> { throw new AssertionError("duplicate producer"); }, executor);
        check(queued.size() == 1, "exact subscribers share one producer");
        first.cancel();
        queued.remove(0)
            .run();
        check(
            first.future()
                .isCancelled(),
            "one subscriber cancellation stays local");
        check(
            second.future()
                .get(1, TimeUnit.SECONDS) == 1 && calls.get() == 1,
            "remaining subscriber receives output");
        HologramProcessGovernor.Subscription<Integer> cancelled = HologramProcessGovernor
            .singleFlight("abandoned", 1, calls::incrementAndGet, executor);
        cancelled.cancel();
        queued.remove(0)
            .run();
        check(calls.get() == 1, "abandoned queued producer performs no work");
        check(HologramProcessGovernor.activeHeavyCpu() == 0, "success releases permit");
    }

    private static void epochAndException() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        HologramProcessGovernor.Subscription<Integer> old = HologramProcessGovernor
            .singleFlight("epoch", 7, () -> 7, queued::add);
        HologramProcessGovernor.Subscription<Integer> fresh = HologramProcessGovernor
            .singleFlight("epoch", 8, () -> 8, queued::add);
        HologramProcessGovernor.cancelEpoch(7);
        for (Runnable task : queued) task.run();
        check(
            old.future()
                .isCancelled()
                && fresh.future()
                    .get(1, TimeUnit.SECONDS) == 8,
            "epoch fence is independent");
        HologramProcessGovernor.Subscription<Integer> failed = HologramProcessGovernor
            .singleFlight("failure", 0, () -> { throw new IllegalArgumentException("fixture"); }, Runnable::run);
        check(
            failed.future()
                .isCompletedExceptionally(),
            "producer exception reaches subscribers");
        check(HologramProcessGovernor.activeHeavyCpu() == 0, "exception releases permit");
        HologramProcessGovernor.Subscription<Integer> retry = HologramProcessGovernor
            .singleFlight("failure", 0, () -> 9, Runnable::run);
        check(
            retry.future()
                .get(1, TimeUnit.SECONDS) == 9,
            "failure removes singleflight entry");
    }

    private static void reentry() throws Exception {
        HologramProcessGovernor.Subscription<Integer> result = HologramProcessGovernor
            .singleFlight("recursive", 0, () -> {
                try {
                    HologramProcessGovernor.singleFlight("recursive", 0, () -> 0, Runnable::run);
                    throw new AssertionError("producer cannot subscribe to itself");
                } catch (IllegalStateException expected) {
                    return 1;
                }
            }, Runnable::run);
        check(
            result.future()
                .get(1, TimeUnit.SECONDS) == 1,
            "reentry fails without deadlock");
    }

    private static void ownedProducerLifecycle() throws Exception {
        long baseline = HologramProcessGovernor.retainedBytes();
        List<Runnable> queue = new ArrayList<>();
        OwnedProducer first = new OwnedProducer(), duplicate = new OwnedProducer();
        HologramProcessGovernor.Subscription<Integer> a = HologramProcessGovernor
            .singleFlight("owned", 0, first, queue::add);
        HologramProcessGovernor.Subscription<Integer> b = HologramProcessGovernor
            .singleFlight("owned", 0, duplicate, queue::add);
        HologramProcessGovernor.Subscription<Integer> sameCallable = HologramProcessGovernor
            .singleFlight("owned", 0, first, queue::add);
        check(duplicate.closes.get() == 1 && first.closes.get() == 0, "unused duplicate retires only its own input");
        sameCallable.cancel();
        a.cancel();
        check(first.closes.get() == 0, "peer still owns shared producer");
        java.util.concurrent.CompletableFuture<Integer> callback = b.future()
            .whenComplete((value, error) -> {
                check(first.closes.get() == 1, "producer input retires before completion callback");
                check(HologramProcessGovernor.activeHeavyCpu() == 0, "CPU released before completion callback");
                check(!HologramProcessGovernor.currentFlightCancelled(), "callback is outside producer context");
            });
        queue.remove(0)
            .run();
        callback.get(1, TimeUnit.SECONDS);
        check(
            b.future()
                .get(1, TimeUnit.SECONDS) == 1 && first.closes.get() == 1,
            "accepted producer retires input once after success");
        OwnedProducer abandoned = new OwnedProducer();
        HologramProcessGovernor.Subscription<Integer> c = HologramProcessGovernor
            .singleFlight("owned-cancel", 0, abandoned, queue::add);
        c.cancel();
        check(abandoned.closes.get() == 1, "last queued subscriber releases input immediately");
        queue.remove(0)
            .run();
        check(abandoned.calls.get() == 0 && abandoned.closes.get() == 1, "retired queued producer never runs");
        OwnedProducer rejected = new OwnedProducer();
        HologramProcessGovernor.Subscription<Integer> d = HologramProcessGovernor.singleFlight(
            "owned-reject",
            0,
            rejected,
            action -> { throw new java.util.concurrent.RejectedExecutionException("fixture"); });
        check(
            d.future()
                .isCompletedExceptionally() && rejected.closes.get() == 1,
            "executor rejection retires producer input");
        OwnedProducer failed = new OwnedProducer();
        failed.fail = true;
        HologramProcessGovernor.Subscription<Integer> e = HologramProcessGovernor
            .singleFlight("owned-failure", 0, failed, Runnable::run);
        check(
            e.future()
                .isCompletedExceptionally() && failed.closes.get() == 1,
            "producer exception retires input exactly once");
        List<HologramProcessGovernor.Subscription<Integer>> saturated = new ArrayList<>();
        for (int i = 0; i < 64; i++)
            saturated.add(HologramProcessGovernor.singleFlight("full-" + i, 99, new OwnedProducer(), queue::add));
        OwnedProducer overflow = new OwnedProducer();
        try {
            HologramProcessGovernor.singleFlight("overflow", 99, overflow, queue::add);
            throw new AssertionError("bounded singleflight admission");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            check(overflow.closes.get() == 1, "flight-limit rejection retires unused input");
        }
        HologramProcessGovernor.cancelEpoch(99);
        for (Runnable task : queue) task.run();
        check(HologramProcessGovernor.retainedBytes() == baseline, "all owned inputs release to prior baseline");
    }

    private static void cooperativeCancellation() {
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger abandonedOutput = new AtomicInteger();
        AtomicReference<HologramProcessGovernor.Subscription<AutoCloseable>> owner = new AtomicReference<>();
        owner.set(HologramProcessGovernor.singleFlight("running-cancel", 0, () -> {
            check(!HologramProcessGovernor.currentFlightCancelled(), "live subscriber keeps producer active");
            owner.get()
                .cancel();
            check(HologramProcessGovernor.currentFlightCancelled(), "producer checkpoint sees last cancellation");
            return abandonedOutput::incrementAndGet;
        }, queue::add));
        queue.remove(0)
            .run();
        check(abandonedOutput.get() == 1, "unclaimed closeable output closes once");
        check(
            !HologramProcessGovernor.currentFlightCancelled() && HologramProcessGovernor.activeHeavyCpu() == 0,
            "cancelled producer clears context and CPU permit");
    }

    private static void sharedOutputDispatch() throws Exception {
        List<Runnable> queue = new ArrayList<>();
        SharedFixture shared = new SharedFixture();
        HologramProcessGovernor.Subscription<SharedFixture> first = HologramProcessGovernor
            .singleFlight("shared-output", 0, () -> shared, queue::add);
        HologramProcessGovernor.Subscription<SharedFixture> second = HologramProcessGovernor.singleFlight(
            "shared-output",
            0,
            () -> { throw new AssertionError("duplicate shared producer"); },
            queue::add);
        AtomicInteger callbacks = new AtomicInteger();
        java.util.function.BiConsumer<SharedFixture, Throwable> retainView = (value, error) -> {
            check(
                value.producerCloses.get() == 0 && value.references.get() > 0,
                "producer base stays alive throughout every synchronous subscriber callback");
            value.references.incrementAndGet();
            callbacks.incrementAndGet();
            value.references.decrementAndGet(); // Simulate immediate view cancellation and cache eviction.
        };
        java.util.concurrent.CompletableFuture<SharedFixture> firstCallback = first.future()
            .whenComplete(retainView);
        java.util.concurrent.CompletableFuture<SharedFixture> secondCallback = second.future()
            .whenComplete(retainView);
        queue.remove(0)
            .run();
        firstCallback.get(1, TimeUnit.SECONDS);
        secondCallback.get(1, TimeUnit.SECONDS);
        check(
            callbacks.get() == 2 && shared.producerCloses.get() == 1 && shared.references.get() == 0,
            "shared producer base closes once only after all callbacks release their views");
        SharedFixture inline = new SharedFixture();
        AtomicInteger earlyCallbacks = new AtomicInteger();
        HologramProcessGovernor.Subscription<SharedFixture> immediate = HologramProcessGovernor
            .singleFlight("inline-shared-output", 0, () -> inline, Runnable::run, (value, error) -> {
                check(
                    value.producerCloses.get() == 0 && value.references.get() == 1,
                    "onReady callback is installed before even an inline producer completes");
                value.references.incrementAndGet();
                earlyCallbacks.incrementAndGet();
                value.references.decrementAndGet();
            });
        check(
            immediate.future()
                .isDone() && earlyCallbacks.get() == 1
                && inline.producerCloses.get() == 1,
            "producer completed before subscribe returned without retiring its callback input early");
    }

    private static void crossDomainRetiredReaper() {
        HologramProcessGovernor.registerRetiredReaper(BROKEN_REAPER);
        HologramProcessGovernor.registerRetiredReaper(RETIRED_REAPER);
        HologramProcessGovernor.registerRetiredReaper(RETIRED_REAPER); // Identity-idempotent module registration.
        long baseline = HologramProcessGovernor.retainedBytes();
        RETIRED.set(
            HologramProcessGovernor.retain("other-domain-retired", 32)
                .transfer());
        int calls = REAPER_CALLS.get(), failures = REAPER_FAILURES.get();
        reapFixture = true;
        failReaperFixture = true;
        try {
            // A slow preceding module can use the shared soft allowance; round robin must still reach this owner.
            for (int turn = 0; turn < 32 && (reapFixture || REAPER_FAILURES.get() == failures); turn++) {
                HologramProcessGovernor.Lease admitted = HologramProcessGovernor.tryRetain("new-domain", 8);
                check(admitted != null, "one faulty reaper cannot reject unrelated domain admission");
                admitted.close();
            }
            check(!reapFixture && REAPER_CALLS.get() == calls + 1, "cross-domain cleanup progresses without recursion");
            check(REAPER_FAILURES.get() > failures, "faulty module callback was isolated");
            check(
                HologramProcessGovernor.retainedBytes() == baseline,
                "retired domain lease releases on other admission");
        } finally {
            reapFixture = false;
            failReaperFixture = false;
            HologramProcessGovernor.Lease retained = RETIRED.getAndSet(null);
            if (retained != null) retained.close();
        }
    }

    private static final class SharedFixture implements HologramProcessGovernor.SharedOutput {

        final AtomicInteger references = new AtomicInteger(1), producerCloses = new AtomicInteger();

        @Override
        public void close() {
            producerCloses.incrementAndGet();
            references.decrementAndGet();
        }
    }

    private static final class OwnedProducer implements java.util.concurrent.Callable<Integer>, AutoCloseable {

        final AtomicInteger calls = new AtomicInteger(), closes = new AtomicInteger();
        boolean fail;
        private HologramProcessGovernor.Lease input = HologramProcessGovernor.retain("owned-input", 16)
            .transfer();

        @Override
        public Integer call() {
            calls.incrementAndGet();
            if (fail) throw new IllegalArgumentException("owned producer fixture");
            return calls.get();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            HologramProcessGovernor.Lease retired = input;
            input = null;
            if (retired != null) retired.close();
        }
    }

    private static void fairWorkers() throws Exception {
        if (HologramProcessGovernor.heavyCpuPermitLimit() != 1) return;
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(2);
        HologramProcessGovernor.Lease blocker = HologramProcessGovernor.tryHeavyCpu("blocker");
        check(blocker != null, "initial CPU admission");
        Thread first = waiter("first", order, failure, finished);
        Thread second = waiter("second", order, failure, finished);
        try {
            first.start();
            awaitQueued(first, 1);
            second.start();
            awaitQueued(second, 2);
        } finally {
            blocker.close();
        }
        check(finished.await(2, TimeUnit.SECONDS), "queued workers finish");
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
        check(order.equals(Arrays.asList("first", "second")), "fair CPU wait preserves arrival order");
        check(
            HologramProcessGovernor.activeHeavyCpu() == 0 && HologramProcessGovernor.queuedHeavyCpu() == 0,
            "fair workers release all permits and queue entries");
    }

    private static Thread waiter(String name, List<String> order, AtomicReference<Throwable> failure,
        CountDownLatch finished) {
        return new Thread(() -> {
            try (HologramProcessGovernor.Lease ignored = HologramProcessGovernor.acquireHeavyCpu(name)) {
                order.add(name);
                check(HologramProcessGovernor.activeHeavyCpu() == 1, "one process-wide CPU permit");
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                finished.countDown();
            }
        }, "governor-test-" + name);
    }

    private static void awaitQueued(Thread waiter, int count) {
        // The queued counter increments before Semaphore.acquire; wait for the actual parked waiter.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((HologramProcessGovernor.queuedHeavyCpu() < count || waiter.getState() != Thread.State.WAITING)
            && System.nanoTime() < deadline) Thread.yield();
        check(
            HologramProcessGovernor.queuedHeavyCpu() >= count && waiter.getState() == Thread.State.WAITING,
            "worker enqueued without holding owner thread");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
