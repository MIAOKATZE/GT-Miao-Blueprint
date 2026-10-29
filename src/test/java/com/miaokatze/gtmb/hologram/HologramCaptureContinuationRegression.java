package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Deterministic owner retry lifecycle with a real queued capture DTO, not a running game/world. */
final class HologramCaptureContinuationRegression {

    private HologramCaptureContinuationRegression() {}

    static void run() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FutureTask<Void> blocker = new FutureTask<>(() -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Capture test worker timed out");
            return null;
        });
        java.util.List<HologramCapture> captures = new java.util.ArrayList<>();
        AtomicInteger callbacks = new AtomicInteger();
        Thread owner = Thread.currentThread();
        try (GTMBExtensions.Registration observer = BlueprintSnapshots
            .register("gtmb_test:capture-continuation", BlueprintSnapshots.Stage.CAPTURE_COMPLETE, snapshot -> {
                SimpleAssert.that(Thread.currentThread() == owner, "capture callback remains on the owner thread");
                SimpleAssert.that(
                    snapshot.cells.size() == 1 && snapshot.cells.get(0).controller,
                    "completion includes the protected controller");
                callbacks.incrementAndGet();
                return new NBTTagCompound();
            })) {
            SimpleAssert.that(HologramAreaPreparation.execute(blocker), "test blocker admitted");
            SimpleAssert.that(entered.await(5, TimeUnit.SECONDS), "test owns the area worker before capture queues");
            HologramCapture retained = queuedCapture(captures);
            FutureTask<?> retainedFuture = future(retained);
            HologramSession session = session(retained);
            session.captureAttempts = 7;
            // This is the exact reset used by ControllerCapture.collect(s, false) before its next native slice.
            HologramSession.cancelCaptureRetry(session, retained);
            SimpleAssert.that(!retainedFuture.isCancelled(), "normal continuation must not cancel its own DTO");
            SimpleAssert.eq(7, session.captureAttempts, "retry reset does not change attempt accounting");
            assertReset(session);
            HologramCapture.collect(null, retained.trigger, ExtendedFacing.DEFAULT, retained);
            SimpleAssert.that(retained.pending && !retainedFuture.isCancelled(), "queued continuation stays pending");
            SimpleAssert.eq(0, callbacks.get(), "pending DTO does not publish a completion callback");

            HologramCapture cancelled = queuedCapture(captures);
            HologramSession.cancelCaptureRetry(session(cancelled));
            SimpleAssert.that(future(cancelled).isCancelled(), "explicit cancellation retires the queued DTO");
            HologramCapture replaced = queuedCapture(captures);
            HologramSession replacement = session(replaced);
            HologramSession.cancelCaptureRetry(replacement, retained);
            SimpleAssert.that(future(replaced).isCancelled(), "different capture identity cannot exempt old work");
            assertReset(replacement);
            SimpleAssert.that(!retainedFuture.isCancelled(), "retiring another capture leaves continuation untouched");

            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            retainedFuture.get(5, TimeUnit.SECONDS);
            HologramCapture.collect(null, retained.trigger, ExtendedFacing.DEFAULT, retained);
            SimpleAssert.that(!retained.pending && !retained.incomplete, "retained DTO finishes after worker release");
            SimpleAssert.eq(1, callbacks.get(), "only the retained capture completes exactly once");
            HologramCapture.collect(null, retained.trigger, ExtendedFacing.DEFAULT, retained);
            SimpleAssert.eq(1, callbacks.get(), "completed continuation cannot repeat callback");
        } catch (Exception failure) {
            throw new AssertionError("capture continuation ownership", failure);
        } finally {
            release.countDown();
            HologramAreaPreparation.cancel(blocker);
            for (HologramCapture capture : captures) capture.cancelPending();
        }
    }

    private static HologramCapture queuedCapture(java.util.List<HologramCapture> captures) throws Exception {
        HologramCapture capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        captures.add(capture);
        HologramCapture.Cell cell = new HologramCapture.Cell(null, 0, 64, 0);
        cell.block = Blocks.stone;
        cell.anchor = true;
        capture.cells.add(cell);
        capture.walkingComplete = true;
        HologramCapture.collect(null, capture.trigger, ExtendedFacing.DEFAULT, capture);
        SimpleAssert.that(capture.pending && future(capture) != null, "completed traversal queues actual DTO future");
        return capture;
    }

    private static FutureTask<?> future(HologramCapture capture) throws Exception {
        Field field = HologramExtensionSnapshots.CaptureTask.class.getDeclaredField("result");
        field.setAccessible(true);
        return (FutureTask<?>) field.get(capture.extensionTask);
    }

    private static HologramSession session(HologramCapture capture) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        HologramSession session = (HologramSession) ((sun.misc.Unsafe) field.get(null))
            .allocateInstance(HologramSession.class);
        session.capture = capture;
        session.capturePending = session.buildAfterCapture = session.calibratePending = true;
        session.captureRetryDue = 123;
        session.retryChannels = new NBTTagCompound();
        return session;
    }

    private static void assertReset(HologramSession session) {
        SimpleAssert.that(
            !session.capturePending && !session.buildAfterCapture
                && !session.calibratePending
                && session.captureRetryDue == 0
                && session.retryChannels == null,
            "retry flags keep prior reset semantics");
    }
}
