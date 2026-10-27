package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.miaokatze.gtmb.hologram.HologramProcessGovernor;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real outgoing handoff and bounded worker exits must release save work without waiting for a GC. */
public final class HologramLibraryTransferOwnershipRegression {

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLibraryTransferOwnershipRegression.class, args)) return;
        run();
        System.out.println("HologramLibraryTransferOwnershipRegression passed");
    }

    public static void run() {
        try {
            repeatedCompletedTransfers();
            cancelledOutgoing();
            cancelledQueuedWork();
            cancelledRunningWork();
            rejectedWork();
            obsoleteWork();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Library transfer ownership", failure);
        }
    }

    private static void repeatedCompletedTransfers() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        ThreadPoolExecutor worker = worker(client);
        long before = HologramProcessGovernor.retainedBytes();
        try {
            for (int i = 0; i < 100; i++) {
                CountedOwner owner = new CountedOwner();
                long charged = HologramProcessGovernor.retainedBytes();
                call(
                    client,
                    "setOutgoing",
                    new Class<?>[] { byte[].class, AutoCloseable.class },
                    new byte[32768],
                    owner);
                AutoCloseable transferred = (AutoCloseable) call(client, "takeOutgoingOwner", new Class<?>[0]);
                call(client, "clearOutgoing", new Class<?>[0]);
                SimpleAssert.eq(0, owner.closes.get(), "a verified artifact stays charged while handed to disk work");
                enqueue(
                    client,
                    () -> SimpleAssert
                        .eq(0, owner.closes.get(), "artifact decode and pair preparation retain their live work owner"),
                    transferred);
                await(owner.closed);
                SimpleAssert.eq(1, owner.closes.get(), "a completed save returns its transferred owner exactly once");
                SimpleAssert.eq(
                    charged - owner.bytes,
                    HologramProcessGovernor.retainedBytes(),
                    "success returns its actual work charge without a weak-reference retirement");
            }
            SimpleAssert.that(
                HologramProcessGovernor.retainedBytes() <= before,
                "one hundred sequential saves do not accumulate work quota without GC");
        } finally {
            stop(worker);
        }
    }

    private static void cancelledOutgoing() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        CountedOwner owner = new CountedOwner();
        try {
            call(client, "setOutgoing", new Class<?>[] { byte[].class, AutoCloseable.class }, new byte[16], owner);
            client.cancel();
            client.cancel();
            await(owner.closed);
            SimpleAssert.eq(1, owner.closes.get(), "cancelling before preview verification closes outgoing once");
            SimpleAssert.eq(null, field(client, "outgoing"), "cancel clears the associated outgoing bytes");
        } finally {
            stop(worker(client));
        }
    }

    private static void cancelledQueuedWork() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        ThreadPoolExecutor worker = worker(client);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountedOwner owner = new CountedOwner();
        AtomicBoolean ran = new AtomicBoolean();
        try {
            block(worker, entered, release);
            enqueue(client, () -> ran.set(true), owner);
            SimpleAssert.eq(
                1,
                worker.getQueue()
                    .size(),
                "verified save work is queued behind the active disk step");
            client.cancel();
            await(owner.closed);
            SimpleAssert.eq(1, owner.closes.get(), "generation cancellation releases removed queued ownership");
            SimpleAssert.that(
                !ran.get() && worker.getQueue()
                    .isEmpty(),
                "cancelled queued work never runs");
        } finally {
            release.countDown();
            stop(worker);
        }
    }

    private static void cancelledRunningWork() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        ThreadPoolExecutor worker = worker(client);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountedOwner owner = new CountedOwner();
        long charged = HologramProcessGovernor.retainedBytes();
        try {
            enqueue(client, () -> {
                entered.countDown();
                await(release);
                SimpleAssert.eq(0, owner.closes.get(), "cancel cannot release bytes still read by a running save");
            }, owner);
            await(entered);
            client.cancel();
            SimpleAssert.eq(0, owner.closes.get(), "running ownership survives generation cancellation");
            SimpleAssert
                .eq(charged, HologramProcessGovernor.retainedBytes(), "running bytes remain charged until finally");
            release.countDown();
            await(owner.closed);
            SimpleAssert.eq(1, owner.closes.get(), "the running task releases only at its safe finally boundary");
        } finally {
            release.countDown();
            stop(worker);
        }
    }

    private static void rejectedWork() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        ThreadPoolExecutor worker = worker(client);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountedOwner owner = new CountedOwner();
        AtomicBoolean ran = new AtomicBoolean();
        try {
            block(worker, entered, release);
            worker.execute(() -> {});
            worker.execute(() -> {});
            SimpleAssert.eq(
                0,
                worker.getQueue()
                    .remainingCapacity(),
                "the actual bounded save queue is saturated");
            enqueue(client, () -> ran.set(true), owner);
            await(owner.closed);
            SimpleAssert.eq(1, owner.closes.get(), "executor refusal releases work that was never queued");
            SimpleAssert.that(!ran.get(), "rejected save work never accesses the artifact");
        } finally {
            release.countDown();
            stop(worker);
        }
    }

    private static void obsoleteWork() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        CountedOwner owner = new CountedOwner();
        AtomicBoolean ran = new AtomicBoolean();
        try {
            Class<?> jobType = Class.forName(HologramLibraryClient.class.getName() + "$Job");
            Class<?> workType = Class.forName(HologramLibraryClient.class.getName() + "$QueuedWork");
            Constructor<?> constructor = workType
                .getDeclaredConstructor(HologramLibraryClient.class, jobType, boolean.class, AutoCloseable.class);
            constructor.setAccessible(true);
            Runnable queued = (Runnable) constructor
                .newInstance(client, job(jobType, () -> ran.set(true)), false, owner);
            call(client, "advanceGeneration", new Class<?>[0]);
            queued.run();
            queued.run();
            await(owner.closed);
            SimpleAssert.eq(1, owner.closes.get(), "the stale-generation early return closes ownership exactly once");
            SimpleAssert.that(!ran.get(), "obsolete save work is fenced before touching its artifact");
        } finally {
            stop(worker(client));
        }
    }

    private static void enqueue(HologramLibraryClient client, Runnable action, AutoCloseable owner)
        throws ReflectiveOperationException {
        Class<?> jobType = Class.forName(HologramLibraryClient.class.getName() + "$Job");
        call(client, "work", new Class<?>[] { jobType, AutoCloseable.class }, job(jobType, action), owner);
    }

    private static Object job(Class<?> jobType, Runnable action) {
        return Proxy.newProxyInstance(jobType.getClassLoader(), new Class<?>[] { jobType }, (proxy, method, args) -> {
            action.run();
            return null;
        });
    }

    private static void block(ThreadPoolExecutor worker, CountDownLatch entered, CountDownLatch release) {
        worker.execute(() -> {
            entered.countDown();
            await(release);
        });
        await(entered);
    }

    private static HologramLibraryClient client() throws ReflectiveOperationException {
        Constructor<HologramLibraryClient> constructor = HologramLibraryClient.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static ThreadPoolExecutor worker(HologramLibraryClient client) throws ReflectiveOperationException {
        return (ThreadPoolExecutor) field(client, "worker");
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... values)
        throws ReflectiveOperationException {
        Method method = target.getClass()
            .getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }

    private static void stop(ThreadPoolExecutor worker) {
        worker.shutdown();
        try {
            SimpleAssert
                .that(worker.awaitTermination(5, TimeUnit.SECONDS), "the owned fixture worker finishes cleanly");
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            SimpleAssert.that(gate.await(5, TimeUnit.SECONDS), "save ownership gate completes");
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class CountedOwner implements AutoCloseable {

        final long bytes = 3L * (32768 + 512);
        final HologramProcessGovernor.Lease lease = HologramProcessGovernor.retain("fixture.library.artifact", bytes)
            .transfer();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        public void close() {
            SimpleAssert
                .eq(1, closes.incrementAndGet(), "ownership must close once rather than relying on idempotence");
            lease.close();
            closed.countDown();
        }
    }
}
