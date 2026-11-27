// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;

import gregtech.api.threads.RunnableMachineUpdate;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Coalesces repeated GT update seeds during one synchronous construction slice. */
public final class HologramMachineUpdateBatch implements AutoCloseable {

    private static final ThreadLocal<HologramMachineUpdateBatch> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Drain> DRAIN = new ThreadLocal<>();
    // Passive main-thread diagnostics; testlab reads differences without resetting production state.
    static long requestedSeeds, coalescedSeeds;

    private final HologramMachineUpdateBatch previous;
    private final Map<World, LongOpenHashSet> seeds;
    private boolean closed;

    private HologramMachineUpdateBatch(HologramMachineUpdateBatch previous) {
        this.previous = previous;
        seeds = previous == null ? new IdentityHashMap<>() : previous.seeds;
    }

    static HologramMachineUpdateBatch open() {
        var batch = new HologramMachineUpdateBatch(CURRENT.get());
        CURRENT.set(batch);
        return batch;
    }

    /**
     * Called only by the GT seed-entry Mixin. The first request still executes immediately in GT. Keep every
     * distinct seed, including arbitrary callback destinations: one controller cannot reach every component
     * after demolition. GT already merges propagation by world at endTick; this removes duplicate initial
     * queue entries, not a supposed full machine traversal per placed block.
     */
    public static boolean suppressDuplicate(World world, int x, int y, int z) {
        var batch = CURRENT.get();
        if (batch == null || DRAIN.get() != null
            || !RunnableMachineUpdate.isEnabled()
            || !RunnableMachineUpdate.isCurrentThreadEnabled()) return false;
        requestedSeeds++;
        boolean duplicate = !batch.seeds.computeIfAbsent(world, ignored -> new LongOpenHashSet())
            .add(CoordinatePacker.pack(x, y, z));
        if (duplicate) coalescedSeeds++;
        return duplicate;
    }

    /** The GT queue can also be drained synchronously by a native callback inside a construction slice. */
    public static Drain pauseForDrain() {
        var drain = new Drain(DRAIN.get());
        clearCurrentSeeds();
        DRAIN.set(drain);
        return drain;
    }

    private static void clearCurrentSeeds() {
        var batch = CURRENT.get();
        if (batch != null) batch.seeds.clear();
    }

    /** Keeps drain callbacks, including newly opened nested batches, on GT's ordinary unfiltered path. */
    public static final class Drain implements AutoCloseable {

        private final Drain previous;
        private boolean closed;

        private Drain(Drain previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            if (DRAIN.get() != this) throw new IllegalStateException("Machine update drains must close in scope order");
            closed = true;
            try {
                // Events emitted during the drain may already have been processed, or the drain may have
                // failed after clearing its queue. Never let an earlier seen-set suppress a subsequent seed.
                clearCurrentSeeds();
            } finally {
                if (previous == null) DRAIN.remove();
                else DRAIN.set(previous);
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Machine update batches must close in scope order");
        closed = true;
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
        // No replay or flag manipulation: even a partial/failed transaction already queued its first seeds.
    }
}
