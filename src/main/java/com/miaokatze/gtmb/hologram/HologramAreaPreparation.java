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

    private final Map<String, String> networks;
    private final Map<String, HologramPresets.Target> nativeShells;
    private final Map<Object, String> identities = new HashMap<>();
    private int networkCursor;
    private FutureTask<HologramPresets.Preset> task;
    private HologramAePolicy.Sanitation sanitation;
    private HologramPresets.Preset result;
    private int stage;
    private boolean cancelled, waiting;

    HologramAreaPreparation() {
        this(new HashMap<>(), new HashMap<>());
    }

    HologramAreaPreparation(Map<String, String> networks, Map<String, HologramPresets.Target> nativeShells) {
        this.networks = networks;
        this.nativeShells = nativeShells;
    }

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

    /** Captured DTO ownership remains private until every owner/native pass and detached data pass completes. */
    boolean start(HologramPresets.Preset source) {
        if (sanitation == null && !cancelled) sanitation = new HologramAePolicy.Sanitation(
            source,
            Collections.unmodifiableMap(networks),
            Collections.unmodifiableMap(nativeShells),
            this::update);
        return !cancelled;
    }

    boolean waiting() {
        return waiting;
    }

    void advance(int budget, long deadline) throws IOException {
        waiting = false;
        for (int work = 0; !cancelled && stage < 5 && work < budget; work++) {
            if (work > 0 && System.nanoTime() >= deadline) break;
            if (stage == 0) {
                if (!sanitation.identityDone()) sanitation.identity();
                else stage++;
            } else if (stage == 1 || stage == 3) {
                if (task == null) {
                    final boolean prepare = stage == 1;
                    FutureTask<HologramPresets.Preset> candidate = new FutureTask<>(() -> {
                        if (prepare) {
                            sanitation.prepareData();
                            return null;
                        }
                        HologramPresets.Preset prepared = sanitation.finishData().preset;
                        HologramBlueprintProcessing.validateReceipt(prepared);
                        return prepared;
                    });
                    if (!execute(candidate)) {
                        waiting = true;
                        break;
                    }
                    task = candidate;
                }
                if (!task.isDone()) {
                    waiting = true;
                    break;
                }
                HologramPresets.Preset completed = completedTask();
                task = null;
                if (stage == 3) result = completed;
                stage++;
            } else if (stage == 2) {
                if (!sanitation.aeDone()) sanitation.ae();
                else stage++;
            } else stage++;
        }
    }

    private HologramPresets.Preset completedTask() throws IOException {
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
        return !cancelled && stage == 5;
    }

    HologramPresets.Preset result() throws IOException {
        if (!ready()) throw new IllegalStateException("Area draft result is not ready");
        return result;
    }

    void cancel() {
        cancelled = true;
        if (task != null) {
            cancel(task);
        }
        identities.clear();
    }
}
