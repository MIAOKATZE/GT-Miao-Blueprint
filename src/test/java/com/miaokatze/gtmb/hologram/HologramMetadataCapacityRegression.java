package com.miaokatze.gtmb.hologram;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Admission arithmetic and transport error contract; not a native fixture or performance acceptance. */
public final class HologramMetadataCapacityRegression {

    private HologramMetadataCapacityRegression() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("Metadata capacity regression passed");
    }

    public static void run() throws Exception {
        HologramSnapshotDataContext.requireCapacity("metadata.resource-review", 60, 40, 100);
        rejected(60, 41, 100);
        rejected(101, 0, 100);
        rejected(0, 101, 100);
        HologramSnapshotDataContext.requireCapacity("metadata.resource-review", Long.MAX_VALUE - 1, 1, Long.MAX_VALUE);
        rejected(Long.MAX_VALUE - 1, 2, Long.MAX_VALUE);
        // The actual configured process limit, not the default 256 MiB, determines feasibility.
        long actual = HologramProcessGovernor.retainedByteLimit();
        HologramSnapshotDataContext.requireCapacity("metadata.source-directory", actual - 1, 1, actual);
        rejected(actual - 1, 2, actual);
        Method error = HologramNetwork.class.getDeclaredMethod("transportErrorMessage", String.class);
        error.setAccessible(true);
        SimpleAssert.eq(
            "Snapshot metadata exceeds the working-memory budget; the previous preview was retained.",
            error.invoke(null, "snapshot.metadata.capacity"),
            "terminal metadata failure has a distinct actionable transport result, not Busy or success");
        SimpleAssert.eq(
            "Snapshot preparation failed; the previous preview was retained.",
            error.invoke(null, "snapshot.prepare"),
            "other preparation failures preserve the existing transport contract");
        Method clientError = Class.forName("com.miaokatze.gtmb.client.hologram.HologramBackgroundTasks")
            .getDeclaredMethod("transportFailureMessage", NBTTagCompound.class);
        clientError.setAccessible(true);
        NBTTagCompound failure = new NBTTagCompound();
        failure.setString("errorCode", "snapshot.metadata.capacity");
        SimpleAssert.eq(
            "当前蓝图所需元数据超过工作预算，已保留原预览；请减小范围或调整内容后重试。",
            clientError.invoke(null, failure),
            "client distinguishes terminal working-set exhaustion from retryable contention");
        realLedgerAdmission();
        // With mandatory facts below the limit, externally occupied bytes must not make this terminal.
        // External contention is handled separately by tryReserve returning Busy, not by this proof.
        HologramSnapshotDataContext.requireCapacity("metadata.item-patch", 0, 1, actual);
    }

    /** Uses real leases without allocating a quota-sized payload or exercising a native provider. */
    private static void realLedgerAdmission() throws Exception {
        Method reserve = HologramSnapshotDataContext.class.getDeclaredMethod("reserveMandatory", String.class, long.class);
        Method retain = HologramSnapshotDataContext.class.getDeclaredMethod("retainMandatory", HologramProcessGovernor.Lease.class);
        reserve.setAccessible(true);
        retain.setAccessible(true);
        long limit = HologramProcessGovernor.retainedByteLimit();
        try (HologramSnapshotDataContext context = new HologramSnapshotDataContext()) {
            long available = limit - HologramProcessGovernor.retainedBytes();
            HologramProcessGovernor.Lease own = reserve(reserve, context, available);
            retain.invoke(context, own);
            try {
                reserve(reserve, context, limit - own.bytes() + 1);
                throw new AssertionError("Own live mandatory leases incorrectly produced a retry");
            } catch (HologramSnapshotDataContext.CapacityExceeded expected) { /* Proven impossible, not Busy. */ }
        }
        try (HologramSnapshotDataContext context = new HologramSnapshotDataContext()) {
            HologramProcessGovernor.Lease external = HologramProcessGovernor.retain(
                "test.metadata.external", limit - HologramProcessGovernor.retainedBytes());
            try {
                reserve(reserve, context, 1);
                throw new AssertionError("External quota holder was ignored");
            } catch (HologramSnapshotDataContext.Busy expected) { /* Releasing the other owner permits progress. */ }
            finally {
                external.close();
            }
            HologramProcessGovernor.Lease admitted = reserve(reserve, context, 1);
            retain.invoke(context, admitted);
        }
    }

    private static HologramProcessGovernor.Lease reserve(Method method, HologramSnapshotDataContext context, long bytes)
        throws Exception {
        try {
            return (HologramProcessGovernor.Lease) method.invoke(context, "metadata.resource-review", bytes);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failed;
        }
    }

    private static void rejected(long held, long requested, long limit) {
        try {
            HologramSnapshotDataContext.requireCapacity("metadata.resource-review", held, requested, limit);
            throw new AssertionError("Unsatisfiable metadata working set was admitted");
        } catch (HologramSnapshotDataContext.CapacityExceeded expected) {
            String message = expected.getMessage();
            SimpleAssert.that(message.contains("metadata.resource-review"), "terminal result identifies the phase");
            SimpleAssert.that(message.contains("held=" + held), "terminal result reports owned bytes");
            SimpleAssert.that(message.contains("requested=" + requested), "terminal result reports requested bytes");
            SimpleAssert.that(message.contains("limit=" + limit), "terminal result reports the actual limit");
        }
    }
}
