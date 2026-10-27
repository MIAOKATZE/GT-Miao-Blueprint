package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real small leases and bounded NBT fixtures; no collection request, world mutation or performance claim. */
public final class HologramMetadataOwnershipRegression {

    private HologramMetadataOwnershipRegression() {}

    public static void run() throws Exception {
        ownerScopeIsNestedAndThreadConfined();
        scopedRetirement();
        concurrentFinalBorrow();
        tileBorrowSurvivesItemEvictionAndContextClose();
        contextCopiesEvictionAndClose();
        pressureRetiresOptionalCache();
        sourceRetryKeepsExactCellAndFacts();
    }

    private static void ownerScopeIsNestedAndThreadConfined() throws Exception {
        Method enter = HologramSnapshotDataContext.class.getDeclaredMethod("ownerFreeze");
        enter.setAccessible(true);
        try (HologramSnapshotDataContext context = new HologramSnapshotDataContext()) {
            SimpleAssert.that(!HologramSnapshotDataContext.freezingOnOwner(), "freeze scope initially absent");
            try (AutoCloseable outer = (AutoCloseable) enter.invoke(context)) {
                try (AutoCloseable inner = (AutoCloseable) enter.invoke(context)) {
                    SimpleAssert.that(HologramSnapshotDataContext.freezingOnOwner() && !HologramSnapshotDataContext.active(), "nested owner scope never impersonates a detached worker");
                }
                SimpleAssert.that(HologramSnapshotDataContext.freezingOnOwner(), "inner close restores outer scope");
                FutureTask<Boolean> wrongThread = new FutureTask<>(() -> {
                    if (HologramSnapshotDataContext.freezingOnOwner()) throw new AssertionError("owner scope leaked across threads");
                    try {
                        outer.close();
                        throw new AssertionError("scope closed on the wrong thread");
                    } catch (IllegalStateException expected) { /* The owner must still close it. */ }
                    try {
                        context.freeze(null);
                        throw new AssertionError("non-owner froze a source");
                    } catch (IllegalStateException expected) {
                        return !HologramSnapshotDataContext.freezingOnOwner();
                    }
                });
                new Thread(wrongThread, "GTMB-metadata-scope-test").start();
                SimpleAssert.that(wrongThread.get(5, TimeUnit.SECONDS), "wrong-thread failure never establishes a scope");
            }
            SimpleAssert.that(!HologramSnapshotDataContext.freezingOnOwner(), "last close removes scope");
            try {
                context.freeze("not valid base64!");
                throw new AssertionError("invalid source unexpectedly decoded");
            } catch (java.io.IOException expected) {
                SimpleAssert.that(!HologramSnapshotDataContext.freezingOnOwner(), "strict decode failure unwinds owner scope");
            }
        }
    }

    private static void scopedRetirement() {
        long before = HologramProcessGovernor.retainedBytes();
        HologramRetainedCache.Value<Object> tile = new HologramRetainedCache.Value<>(
            new Object(), HologramProcessGovernor.retain("test.metadata.tile", 128));
        HologramRetainedCache.Borrow<Object> first = tile.borrow(), second = tile.borrow();
        Object identity = first.value();
        tile.close();
        tile.close();
        SimpleAssert.eq(before + 128, HologramProcessGovernor.retainedBytes(), "evicted tile stays charged while borrowed");
        SimpleAssert.that(second.value() == identity, "eviction does not invalidate the surviving tile read");
        first.close();
        first.close();
        SimpleAssert.eq(before + 128, HologramProcessGovernor.retainedBytes(), "one of two borrowers cannot release the lease");
        second.close();
        SimpleAssert.eq(before, HologramProcessGovernor.retainedBytes(), "last borrower returns retired lease without GC");
        try {
            tile.borrow();
            throw new AssertionError("Retired value accepted a new borrow");
        } catch (IllegalStateException expected) { /* A retired entry cannot be resurrected. */ }
    }

    private static void concurrentFinalBorrow() throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        HologramRetainedCache.Value<Object> entry = new HologramRetainedCache.Value<>(
            new Object(), HologramProcessGovernor.retain("test.metadata.worker", 128));
        CountDownLatch borrowed = new CountDownLatch(1), finish = new CountDownLatch(1);
        FutureTask<Boolean> worker = new FutureTask<>(() -> {
            try (HologramRetainedCache.Borrow<Object> input = entry.borrow()) {
                borrowed.countDown();
                if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("borrow test release not signalled");
                return input.value() != null;
            }
        });
        Thread thread = new Thread(worker, "GTMB-metadata-ownership-test");
        thread.start();
        try {
            SimpleAssert.that(borrowed.await(5, TimeUnit.SECONDS), "worker holds a real borrow");
            entry.close();
            SimpleAssert.eq(before + 128, HologramProcessGovernor.retainedBytes(), "cancel cannot release a running borrower");
        } finally {
            entry.close();
            finish.countDown();
        }
        SimpleAssert.that(worker.get(5, TimeUnit.SECONDS), "retired value survives until worker finally");
        SimpleAssert.eq(before, HologramProcessGovernor.retainedBytes(), "worker finally releases last retired borrower");
    }

    private static void tileBorrowSurvivesItemEvictionAndContextClose() throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        Method freeze = HologramSnapshotDataContext.class.getDeclaredMethod("freezeBorrowed", String.class);
        Method enter = HologramSnapshotDataContext.class.getDeclaredMethod("ownerFreeze");
        freeze.setAccessible(true);
        enter.setAccessible(true);
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        try (AutoCloseable scope = (AutoCloseable) enter.invoke(context)) {
            @SuppressWarnings("unchecked")
            HologramRetainedCache.Borrow<NBTTagCompound> tile = (HologramRetainedCache.Borrow<NBTTagCompound>) freeze
                .invoke(context, payload(0));
            try {
                long tileCost = context.retainedDecodedBytes();
                for (int i = 1; i <= 256; i++) context.freeze(payload(i));
                SimpleAssert.eq(before + 128L * 2048 + context.retainedDecodedBytes() + tileCost,
                    HologramProcessGovernor.retainedBytes(), "item-side LRU eviction preserves the outstanding tile charge");
                context.close();
                SimpleAssert.eq(before + tileCost, HologramProcessGovernor.retainedBytes(), "context retirement cannot close a still-borrowed tile");
                SimpleAssert.eq(0, tile.value().getInteger("setting"), "borrowed tile remains valid after context retirement");
            } finally {
                tile.close();
            }
        } finally {
            context.close();
        }
        SimpleAssert.eq(before, HologramProcessGovernor.retainedBytes(), "tile finally returns the last retired charge without GC");
    }

    private static String payload(int value) throws Exception {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("setting", value);
        return HologramBlueprintNbt.encodeData(tag);
    }

    private static void contextCopiesEvictionAndClose() throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        String first = payload(0);
        context.freeze(first).setInteger("setting", -1);
        SimpleAssert.eq(0, context.freeze(first).getInteger("setting"), "owner callers cannot mutate cached NBT");
        for (int i = 1; i <= 300; i++) context.freeze(payload(i));
        long expected = before + 128L * 2048 + context.retainedDecodedBytes();
        SimpleAssert.eq(expected, HologramProcessGovernor.retainedBytes(), "retired decoded entries leave no phantom-only charge");
        AutoCloseable workerInput = context.retain();
        CountDownLatch entered = new CountDownLatch(1), continueRead = new CountDownLatch(1);
        FutureTask<Integer> worker = new FutureTask<>(() -> {
            HologramSnapshotDataContext.enter(context);
            try {
                entered.countDown();
                if (!continueRead.await(5, TimeUnit.SECONDS)) throw new AssertionError("context read not signalled");
                return HologramSnapshotDataContext.decode(first).getInteger("setting");
            } finally {
                HologramSnapshotDataContext.exit();
                workerInput.close();
            }
        });
        new Thread(worker, "GTMB-metadata-retained-input-test").start();
        try {
            SimpleAssert.that(entered.await(5, TimeUnit.SECONDS), "worker entered the frozen context before owner cancel");
            context.close();
            SimpleAssert.eq(expected, HologramProcessGovernor.retainedBytes(), "owner cancel retains a live frozen worker input");
        } finally {
            context.close();
            continueRead.countDown();
        }
        SimpleAssert.eq(Integer.valueOf(0), worker.get(5, TimeUnit.SECONDS), "retained worker rebuilds evicted NBT after owner cancel");
        workerInput.close();
        SimpleAssert.eq(before, HologramProcessGovernor.retainedBytes(), "final context input closes every lease without GC");
        try {
            context.freeze(first);
            throw new AssertionError("Closed owner reacquired a frozen input");
        } catch (IllegalStateException expectedClosed) { /* No resurrection after final cancellation. */ }
        try {
            context.retain();
            throw new AssertionError("Closed owner accepted a new worker");
        } catch (IllegalStateException expectedClosed) { /* Existing workers only, not new submissions. */ }
    }

    /** Executes the actual source row step with no World; this is not full import/native acceptance. */
    private static void sourceRetryKeepsExactCellAndFacts() throws Exception {
        Thread owner = Thread.currentThread();
        HologramProcessGovernor.Lease[] external = { null };
        int[] tileCalls = { 0 }, itemCalls = { 0 };
        boolean[] injected = { false };
        GTMBExtensions.Registration codec = ResourceContainers.register("gtmb:test_metadata_retry", input -> {
            SimpleAssert.that(Thread.currentThread() == owner && !HologramSnapshotDataContext.active()
                && HologramSnapshotDataContext.freezingOnOwner(), "codec remains on the native owner inside its cache scope");
            int marker = input.container().getInteger("scopeMarker");
            if (marker == 1) tileCalls[0]++;
            if (marker == 2) {
                itemCalls[0]++;
                if (!injected[0]) {
                    injected[0] = true;
                    external[0] = HologramProcessGovernor.retain("test.metadata.item-contention",
                        HologramProcessGovernor.retainedByteLimit() - HologramProcessGovernor.retainedBytes());
                }
            }
            return null;
        });
        try (HologramSnapshotDataContext context = new HologramSnapshotDataContext()) {
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.block = "minecraft:stone";
            cell.tileNbt = scopedPayload(1);
            cell.itemNbt = scopedPayload(2);
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
            HologramSession session = (HologramSession) unsafe.allocateInstance(HologramSession.class);
            session.processingSource = new HologramPresets.Preset();
            session.processingSource.cells.add(cell);
            HologramSnapshotMetadataSource source = new HologramSnapshotMetadataSource(session, context);
            Field stage = HologramSnapshotMetadataSource.class.getDeclaredField("stage");
            stage.setAccessible(true);
            stage.setInt(source, 6);
            Field pending = HologramSnapshotMetadataSource.class.getDeclaredField("pendingCell");
            pending.setAccessible(true);
            try {
                source.step(1, Long.MAX_VALUE);
                throw new AssertionError("Item-scope quota contention did not defer the source cell");
            } catch (HologramSnapshotDataContext.Busy expected) { /* Earlier tile facts remain exact and reusable. */ }
            SimpleAssert.that(!HologramSnapshotDataContext.freezingOnOwner(), "Busy unwinds owner cache scope");
            SimpleAssert.that(injected[0] && tileCalls[0] > 0 && itemCalls[0] > 0, "both real resource scopes were reached");
            SimpleAssert.eq(1, context.retainedResourceFacts(), "successful tile fact is retained before item contention");
            SimpleAssert.eq(0L, source.done(), "Busy rolls back the current source cursor");
            SimpleAssert.eq(0, source.result.cells.size(), "partial cell cannot publish as completed");
            Object candidate = pending.get(source);
            int beforeTile = tileCalls[0];
            external[0].close();
            external[0] = null;
            source.step(1, Long.MAX_VALUE);
            SimpleAssert.eq(beforeTile, tileCalls[0], "same-cell retry does not reanalyze the successful tile fact");
            SimpleAssert.eq(2, context.retainedResourceFacts(), "retry adds only the missing item-scope fact");
            SimpleAssert.eq(1, source.result.cells.size(), "one successful cell is appended exactly once");
            SimpleAssert.that(source.result.cells.get(0) == candidate && pending.get(source) == null, "retry reuses then clears its private pending cell");
        } finally {
            if (external[0] != null) external[0].close();
            codec.close();
        }
    }

    private static String scopedPayload(int scope) throws Exception {
        NBTTagCompound root = new NBTTagCompound(), value = new NBTTagCompound();
        value.setInteger("scopeMarker", scope);
        root.setTag("extensionConfig", value);
        return HologramBlueprintNbt.encodeData(root);
    }

    private static void pressureRetiresOptionalCache() throws Exception {
        Method reserve = HologramSnapshotDataContext.class.getDeclaredMethod("reserveMandatory", String.class, long.class);
        Method retain = HologramSnapshotDataContext.class.getDeclaredMethod("retainMandatory", HologramProcessGovernor.Lease.class);
        reserve.setAccessible(true);
        retain.setAccessible(true);
        long before = HologramProcessGovernor.retainedBytes();
        try (HologramSnapshotDataContext context = new HologramSnapshotDataContext()) {
            String encoded = payload(1);
            context.freeze(encoded);
            long cached = context.retainedDecodedBytes();
            HologramProcessGovernor.Lease external = HologramProcessGovernor.retain(
                "test.metadata.external", HologramProcessGovernor.retainedByteLimit() - HologramProcessGovernor.retainedBytes());
            try {
                try {
                    reserve.invoke(context, "metadata.resource-review", cached);
                    throw new AssertionError("Busy mandatory admission was not reported");
                } catch (InvocationTargetException failed) {
                    if (!(failed.getCause() instanceof HologramSnapshotDataContext.Busy)) throw failed;
                }
                SimpleAssert.eq(0L, context.retainedDecodedBytes(), "pressure retires optional decoded residency");
                context.freeze(encoded);
                SimpleAssert.eq(0L, context.retainedDecodedBytes(), "same-cell retry cannot repopulate the blocking cache");
                HologramProcessGovernor.Lease mandatory = (HologramProcessGovernor.Lease) reserve
                    .invoke(context, "metadata.resource-review", cached);
                retain.invoke(context, mandatory);
            } finally {
                external.close();
            }
        }
        SimpleAssert.eq(before, HologramProcessGovernor.retainedBytes(), "pressure, uncached retry and close return all local charges");
    }
}
