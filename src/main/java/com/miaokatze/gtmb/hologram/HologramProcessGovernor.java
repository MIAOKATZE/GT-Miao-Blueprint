package com.miaokatze.gtmb.hologram;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import com.miaokatze.miaokatzecore.api.work.WorkGovernor;

/** GTMB configuration, owner identity and diagnostics over Core's explicit process-shared work governor. */
public final class HologramProcessGovernor {

    private static final WorkGovernor CORE = WorkGovernor.processShared(
        (int) property("gtmb.heavy.cpuPermits", 1, 1, 2),
        property("gtmb.heavy.retainedBytes", 256L << 20, 1L << 20, 1L << 30),
        HologramProcessGovernor::isOwnerThread,
        new WorkGovernor.Observer() {

            @Override
            public void cpuState(int active, int queued) {
                HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.HEAVY_ACTIVE, active);
                HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.HEAVY_QUEUED, queued);
            }

            @Override
            public void retainedBytes(long bytes) {
                HologramRuntimeMetrics.gauge(HologramRuntimeMetrics.Counter.HEAVY_BYTES, bytes);
            }

            @Override
            public void rejected() {
                HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.HEAVY_REJECTED, 1);
            }

            @Override
            public void cpuFinished(String owner, long started, long elapsed) {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.HEAVY_WORK, started);
            }
        });

    private HologramProcessGovernor() {}

    public interface SharedOutput extends WorkGovernor.SharedOutput {
    }

    public static Lease tryHeavyCpu(String owner) {
        return wrap(CORE.tryHeavyCpu(owner));
    }

    public static Lease acquireHeavyCpu(String owner) throws InterruptedException {
        return wrap(CORE.acquireHeavyCpu(owner));
    }

    public static Lease tryRetain(String owner, long bytes) {
        return wrap(CORE.tryRetain(owner, bytes));
    }

    public static Lease retain(String owner, long bytes) {
        return wrap(CORE.retain(owner, bytes));
    }

    public static long retainedByteLimit() {
        return CORE.retainedByteLimit();
    }

    public static long retainedBytes() {
        return CORE.retainedBytes();
    }

    public static int activeHeavyCpu() {
        return CORE.activeHeavyCpu();
    }

    public static int queuedHeavyCpu() {
        return CORE.queuedHeavyCpu();
    }

    public static int heavyCpuPermitLimit() {
        return CORE.heavyCpuPermitLimit();
    }

    public static boolean currentFlightCancelled() {
        return CORE.currentFlightCancelled();
    }

    public static void cancelEpoch(long epoch) {
        CORE.cancelEpoch(epoch);
    }

    public static void registerRetiredReaper(Runnable reaper) {
        CORE.registerRetiredReaper(reaper);
    }

    public static <K, V> Subscription<V> singleFlight(K exactKey, long epoch, Callable<V> work, Executor executor) {
        return new Subscription<>(CORE.singleFlight(exactKey, epoch, work, executor));
    }

    public static <K, V> Subscription<V> singleFlight(K exactKey, long epoch, Callable<V> work, Executor executor,
        BiConsumer<? super V, ? super Throwable> onReady) {
        return new Subscription<>(CORE.singleFlight(exactKey, epoch, work, executor, onReady));
    }

    private static Lease wrap(WorkGovernor.Lease lease) {
        return lease == null ? null : new Lease(lease);
    }

    public static final class Lease implements AutoCloseable {

        private final WorkGovernor.Lease delegate;

        private Lease(WorkGovernor.Lease delegate) {
            this.delegate = delegate;
        }

        public long bytes() {
            return delegate.bytes();
        }

        public String owner() {
            return delegate.owner();
        }

        public Lease transfer() {
            delegate.transfer();
            return this;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    public static final class Subscription<V> implements AutoCloseable {

        private final WorkGovernor.Subscription<V> delegate;

        private Subscription(WorkGovernor.Subscription<V> delegate) {
            this.delegate = delegate;
        }

        public CompletableFuture<V> future() {
            return delegate.future();
        }

        public boolean cancel() {
            return delegate.cancel();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static boolean isOwnerThread() {
        String thread = Thread.currentThread()
            .getName();
        return HologramWorkBudget.isOwnerThread() || "Server thread".equals(thread)
            || "Client thread".equals(thread)
            || "Render thread".equals(thread);
    }

    private static long property(String name, long fallback, long minimum, long maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Long.parseLong(System.getProperty(name, ""))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
