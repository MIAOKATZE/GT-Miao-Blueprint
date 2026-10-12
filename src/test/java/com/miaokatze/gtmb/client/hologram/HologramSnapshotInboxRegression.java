package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual worker scheduling with controlled gates; no client world, registries or graphics context. */
public final class HologramSnapshotInboxRegression {

    private HologramSnapshotInboxRegression() {}

    public static void run() {
        fifoAndBackpressure();
        epochCancellation();
        failedDecodeRetainsOrder();
    }

    private static void fifoAndBackpressure() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> decodingThread = new AtomicReference<>();
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> {
            decodingThread.set(Thread.currentThread());
            entered.countDown();
            waitFor(release);
            return "PREPARE";
        })) {
            SimpleAssert.that(inbox.offer(new byte[3]), "first snapshot admitted");
            waitFor(entered);
            SimpleAssert.that(decodingThread.get() != Thread.currentThread(), "pure decode leaves client thread");
            SimpleAssert.that(!inbox.offer(new byte[2]), "active bytes count toward compressed budget");
            SimpleAssert.that(inbox.offerValue("ACK"), "inline ACK admitted behind compressed PREPARE");
            SimpleAssert.that(!inbox.offerValue("next"), "decoded and undecoded entries share a bounded capacity");
            SimpleAssert.that(inbox.poll() == null, "ready ACK never overtakes blocked PREPARE");
            release.countDown();
            SimpleAssert.eq("PREPARE", inbox.awaitNext().value, "PREPARE publishes first");
            SimpleAssert.eq("ACK", inbox.poll().value, "ACK is retained and publishes second");
            SimpleAssert.that(inbox.offerValue("next"), "consumption relieves backpressure for retry");
            SimpleAssert.eq("next", inbox.poll().value, "rejected input can be retried without loss");
            boolean rejected = false;
            try {
                inbox.offer(new byte[5]);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            SimpleAssert.that(rejected, "single oversized snapshot rejected before decode");
        } finally {
            release.countDown();
        }
    }

    private static void epochCancellation() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> {
            if (bytes[0] == 1) {
                entered.countDown();
                waitFor(release);
                return "old-world";
            }
            return "new-world";
        })) {
            SimpleAssert.that(inbox.offer(new byte[] { 1, 0, 0 }), "old-world snapshot admitted");
            waitFor(entered);
            SimpleAssert.that(inbox.offerValue("old queued ACK"), "old ACK queued");
            inbox.clear();
            SimpleAssert.that(!inbox.offer(new byte[2]), "cancelled active decode still reserves physical bytes");
            SimpleAssert.that(inbox.offer(new byte[] { 2 }), "new epoch fits beside cancelled active work");
            SimpleAssert.that(!inbox.offerValue("excess"), "stale active work still counts toward capacity");
            release.countDown();
            SimpleAssert.eq("new-world", inbox.awaitNext().value, "old result and queued old ACK cannot cross epoch");
            SimpleAssert.that(inbox.poll() == null, "old result never reappears");
            SimpleAssert.that(inbox.offer(new byte[4]), "cancelled reservation released after decode exits");
            SimpleAssert.eq("new-world", inbox.awaitNext().value, "worker continues after cancellation");
        } finally {
            release.countDown();
        }
    }

    private static void failedDecodeRetainsOrder() {
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(
            2,
            4,
            bytes -> { throw new IOException("malformed compressed snapshot"); })) {
            SimpleAssert.that(inbox.offer(new byte[] { 1 }), "malformed snapshot admitted for validation");
            SimpleAssert.that(inbox.offerValue("ACK"), "ACK queued after malformed snapshot");
            String preview = "previous complete preview";
            HologramSnapshotInbox.Result<String> failed = inbox.awaitNext();
            if (failed.failure == null) preview = failed.value;
            SimpleAssert.that(failed.failure instanceof IOException, "failure reported to client caller");
            SimpleAssert.eq("previous complete preview", preview, "failed decode leaves installed preview intact");
            SimpleAssert.eq("ACK", inbox.poll().value, "failure never drops the later ACK");
            inbox.offerValue("cancelled session");
            inbox.offerValue("new session");
            SimpleAssert.eq("cancelled session", inbox.poll().value, "cancellation arrives in FIFO order");
            SimpleAssert.eq("new session", inbox.poll().value, "later session survives an ordinary cancelled message");
        }
    }

    private static void waitFor(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("snapshot worker gate timed out");
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(e);
        }
    }
}
