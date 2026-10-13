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
        explicitRootCloseFence();
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

    private static void explicitRootCloseFence() {
        HologramRootCloseFence fence = new HologramRootCloseFence();
        SimpleAssert
            .that(!fence.suppress("session", 7, true, false), "child return and resize have no explicit close fence");
        fence.close("session", 8);
        SimpleAssert
            .that(fence.suppress("session", 7, true, false), "ordinary queued open cannot undo explicit root exit");
        SimpleAssert
            .that(fence.suppress("session", 7, false, false), "older closed snapshot cannot acknowledge a newer exit");
        SimpleAssert.that(fence.suppress("session", 8, true, false), "same-sequence queued open is still suppressed");
        SimpleAssert.that(
            fence.suppress("session", 9, false, true),
            "import progress cannot acknowledge close while still busy");
        SimpleAssert
            .that(fence.suppress("session", 8, false, false), "exact close ACK retires fence without routing GUI");
        SimpleAssert.that(
            !fence.suppress("session", 8, true, false),
            "later actual right-click can reopen at the same server UI sequence");
        SimpleAssert
            .that(!fence.suppress("session", 9, true, false), "later higher-sequence open remains normal after ACK");
        fence.close("session", 10);
        fence.close("session", 12);
        SimpleAssert
            .that(fence.suppress("session", 10, false, false), "first close ACK cannot retire a later explicit exit");
        SimpleAssert.that(fence.suppress("session", 12, true, false), "later exit still blocks queued open");
        SimpleAssert
            .that(!fence.suppress("new-session-uuid", 0, true, false), "a new server UUID is never permanently hidden");
        fence.close("new-session-uuid", 1);
        fence.clear();
        SimpleAssert.that(
            !fence.suppress("new-session-uuid", 1, true, false),
            "disconnect/clear projection releases old fence");

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (HologramSnapshotInbox<Opening> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> {
            entered.countDown();
            waitFor(release);
            return new Opening(7, true);
        })) {
            fence.close("session", 8);
            inbox.offer(new byte[] { 1 });
            waitFor(entered);
            inbox.offerValue(new Opening(8, false));
            SimpleAssert.that(inbox.poll() == null, "close ACK cannot overtake a queued compressed old opening");
            release.countDown();
            Opening old = inbox.awaitNext().value;
            SimpleAssert.that(
                fence.suppress("session", old.sequence, old.open, false),
                "actual FIFO-decoded old open is suppressed");
            Opening ack = inbox.poll().value;
            SimpleAssert.that(fence.suppress("session", ack.sequence, ack.open, false), "FIFO close ACK retires fence");
            inbox.offerValue(new Opening(8, true));
            Opening deliberate = inbox.poll().value;
            SimpleAssert.that(
                !fence.suppress("session", deliberate.sequence, deliberate.open, false),
                "actual later right-click snapshot follows the retained ACK despite unchanged sequence");
        } finally {
            release.countDown();
        }
    }

    private static final class Opening {

        final long sequence;
        final boolean open;

        Opening(long sequence, boolean open) {
            this.sequence = sequence;
            this.open = open;
        }
    }
}
