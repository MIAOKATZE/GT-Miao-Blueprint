package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Detached canonicalization uses its own bounded worker; library transfer workers remain independent. */
final class HologramAreaPreparation {

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1,
        1,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2),
        runnable -> {
            Thread thread = new Thread(runnable, "gtmb-area-draft");
            thread.setDaemon(true);
            return thread;
        });

    static {
        WORKER.allowCoreThreadTimeOut(true);
    }

    static final class Progress {

        final String phase;
        final long done, total;

        Progress(String phase, long done, long total) {
            this.phase = phase;
            this.done = done;
            this.total = total;
        }
    }

    private final Map<String, String> networks = new HashMap<>();
    private final Map<String, HologramPresets.Target> nativeShells = new HashMap<>();
    private final Map<Object, String> identities = new HashMap<>();
    private int networkCursor;
    private FutureTask<HologramPresets.Preset> task;
    private volatile Progress progress = new Progress("queued", 0, 1);

    /** Only this method reads source networks. It runs on the server thread with the world's normal tick budget. */
    boolean captureNetworks(HologramPresets.Preset source, int budget, long deadline) throws IOException {
        for (int work = 0; networkCursor < source.cells.size() && work < budget; work++) {
            if (work > 0 && System.nanoTime() >= deadline) break;
            HologramPresets.Cell cell = source.cells.get(networkCursor);
            HologramNetworkBindings.captureNetwork(cell, networks, identities);
            HologramAePolicy.captureNativeShell(cell, nativeShells);
            networkCursor++;
        }
        if (networkCursor < source.cells.size()) return false;
        identities.clear(); // Never retain live grids in a queued worker or its completed result.
        return true;
    }

    int networksDone() {
        return networkCursor;
    }

    /** Ownership transfers only on accepted admission. A full queue is retried by a later ordinary server tick. */
    boolean start(HologramPresets.Preset source) {
        if (task != null) return true;
        FutureTask<HologramPresets.Preset> candidate = new FutureTask<>(
            () -> HologramAePolicy.sanitizeOwned(
                source,
                Collections.unmodifiableMap(networks),
                Collections.unmodifiableMap(nativeShells),
                this::update).preset);
        if (execute(candidate)) {
            task = candidate;
            return true;
        }
        return false;
    }

    static boolean execute(FutureTask<?> task) {
        try {
            WORKER.execute(task);
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    static void cancel(FutureTask<?> task) {
        task.cancel(true);
        WORKER.remove(task);
    }

    private void update(String phase, long done, long total) throws IOException {
        if (Thread.currentThread()
            .isInterrupted()) throw new IOException("Area draft preparation cancelled");
        Progress previous = progress;
        if (!previous.phase.equals(phase) || done == total || (done & 63) == 0)
            progress = new Progress(phase, done, total);
    }

    Progress progress() {
        return progress;
    }

    boolean ready() {
        return task != null && task.isDone();
    }

    HologramPresets.Preset result() throws IOException {
        if (!ready()) throw new IllegalStateException("Area draft result is not ready");
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("Area draft result interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw new IOException("Area draft preparation failed", failed.getCause());
        }
    }

    void cancel() {
        if (task != null) {
            cancel(task);
        }
        identities.clear();
    }
}
