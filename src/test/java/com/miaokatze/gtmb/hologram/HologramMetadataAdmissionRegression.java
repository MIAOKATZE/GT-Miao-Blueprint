package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual worker rejection and scheduler eligibility; no World, native report execution or timing benchmark. */
public final class HologramMetadataAdmissionRegression {

    private HologramMetadataAdmissionRegression() {}

    public static void run() throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
        HologramSession session = (HologramSession) unsafe.allocateInstance(HologramSession.class);
        HologramSnapshotMetadataTask task = new HologramSnapshotMetadataTask(session, new NBTTagCompound());
        Field stage = field(HologramSnapshotMetadataTask.class, "stage");
        // The source-free worker-admission boundary needs no synthetic world or initialized editor.
        stage.setInt(task, 3);
        HologramSnapshotDataContext context = (HologramSnapshotDataContext) field(
            HologramSnapshotMetadataTask.class,
            "dataContext").get(task);
        Field references = field(HologramSnapshotDataContext.class, "references");
        Field report = field(HologramSnapshotMetadataTask.class, "report");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FutureTask<Void> blocker = new FutureTask<>(() -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker release timed out");
            return null;
        });
        FutureTask<Void> first = new FutureTask<>(() -> null), second = new FutureTask<>(() -> null);
        long before = HologramProcessGovernor.retainedBytes();
        try {
            SimpleAssert.that(HologramAreaPreparation.execute(blocker), "worker admits the active blocker");
            SimpleAssert.that(entered.await(5, TimeUnit.SECONDS), "the real worker is occupied");
            SimpleAssert.that(HologramAreaPreparation.execute(first), "first queue position is occupied");
            SimpleAssert.that(HologramAreaPreparation.execute(second), "second queue position is occupied");
            HologramWorkBudget.startServerTick();
            SimpleAssert.eq(1, round(task), "rejected metadata gets no repeated spare-time admission attempts");
            SimpleAssert.that(task.waitingForBudget() && !task.canAdvance(), "rejection blocks this actual tick");
            SimpleAssert.that(report.get(task) == null, "a rejected candidate is never published as the report");
            SimpleAssert.eq(1, references.getInt(context), "rejected candidate releases its frozen input retain");
            HologramAreaPreparation.cancel(second);
            SimpleAssert.that(!task.step(1, Long.MAX_VALUE), "direct same-tick retry remains deferred");
            SimpleAssert.that(report.get(task) == null, "freeing a slot cannot bypass the same-tick guard");
            SimpleAssert.eq(1, references.getInt(context), "same-tick deferral does not retain another input");
            HologramWorkBudget.endServerTick();
            SimpleAssert.that(task.canAdvance(), "standalone callers are not stranded by an earlier tick");
            HologramWorkBudget.startServerTick();
            SimpleAssert.that(task.canAdvance(), "the next actual tick retries transient queue pressure");
            SimpleAssert.eq(1, round(task), "admitted pending worker is not polled in spare rounds");
            SimpleAssert.that(
                report.get(task) != null && task.waitingForWorker(),
                "next-tick admission uses the freed slot");
            SimpleAssert.eq(2, references.getInt(context), "accepted queued worker owns one frozen input retain");
            task.cancel();
            SimpleAssert.eq(
                0,
                references.getInt(context),
                "queued cancellation releases worker and owner exactly once");
        } finally {
            HologramWorkBudget.endServerTick();
            task.cancel();
            HologramAreaPreparation.cancel(first);
            HologramAreaPreparation.cancel(second);
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
        }
        SimpleAssert.eq(
            before,
            HologramProcessGovernor.retainedBytes(),
            "admission and cancellation leave no lease charge");
    }

    private static int round(HologramSnapshotMetadataTask task) {
        AtomicLong clock = new AtomicLong();
        int[] calls = { 0 };
        java.util.function.Consumer<HologramSnapshotMetadataTask> advance = current -> {
            current.step(1, Long.MAX_VALUE);
            calls[0]++;
            clock.incrementAndGet();
        };
        HologramWorkspaceScheduler.runRound(
            Collections.singletonList(task),
            advance,
            advance,
            HologramSnapshotMetadataTask::canAdvance,
            clock::get,
            8);
        return calls[0];
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
