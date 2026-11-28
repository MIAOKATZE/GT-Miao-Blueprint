// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;

import gregtech.api.threads.RunnableMachineUpdate;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Native updates are suppressed per slice and published from the owning job's terminal boundary. */
public final class HologramMachineUpdateBatch implements AutoCloseable {

    private static final ThreadLocal<HologramMachineUpdateBatch> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Drain> DRAIN = new ThreadLocal<>();
    static long requestedSeeds, coalescedSeeds;
    private final HologramMachineUpdateBatch previous;
    private final Updates updates;
    private final boolean enabled, temporary;
    private boolean closed;

    static final class Updates {

        private final Map<World, LongOpenHashSet> seeds = new IdentityHashMap<>();
        private boolean finishing;

        HologramMachineUpdateBatch open() {
            return new HologramMachineUpdateBatch(this, false);
        }

        void changed(World world, int x, int y, int z) {
            if (!RunnableMachineUpdate.isEnabled()) return;
            var scope = CURRENT.get();
            if (scope != null && !scope.enabled) return;
            if (machineBlock(world, x, y, z)) add(world, x, y, z);
        }

        void removed(World world, int x, int y, int z, boolean wasMachine) {
            if (!wasMachine || !RunnableMachineUpdate.isEnabled()) return;
            var scope = CURRENT.get();
            if (scope != null && !scope.enabled) return;
            var recorded = seeds.get(world);
            if (recorded != null && recorded.contains(CoordinatePacker.pack(x, y, z))) return;
            add(world, x, y, z);
            for (var side : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS) {
                int nx = x + side.offsetX, ny = y + side.offsetY, nz = z + side.offsetZ;
                if (machineBlock(world, nx, ny, nz)) add(world, nx, ny, nz);
            }
        }

        private void add(World world, int x, int y, int z) {
            requestedSeeds++;
            if (!seeds.computeIfAbsent(world, ignored -> new LongOpenHashSet())
                .add(CoordinatePacker.pack(x, y, z))) coalescedSeeds++;
        }

        void finish() {
            finishing = true;
            if (CURRENT.get() == null) flush();
        }

        private void flush() {
            if (!RunnableMachineUpdate.isEnabled() || !RunnableMachineUpdate.isCurrentThreadEnabled()) return;
            for (var entry : seeds.entrySet()) {
                var positions = entry.getValue()
                    .iterator();
                while (positions.hasNext()) {
                    long p = positions.nextLong();
                    int x = CoordinatePacker.unpackX(p), y = CoordinatePacker.unpackY(p),
                        z = CoordinatePacker.unpackZ(p);
                    if (entry.getKey()
                        .blockExists(x, y, z)) RunnableMachineUpdate.setMachineUpdateValues(entry.getKey(), x, y, z);
                }
            }
            seeds.clear();
        }
    }

    static boolean machineBlock(World world, int x, int y, int z) {
        return world.blockExists(x, y, z)
            && (world.getTileEntity(x, y, z) instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity
                || gregtech.api.GregTechAPI.isMachineBlock(world.getBlock(x, y, z), world.getBlockMetadata(x, y, z)));
    }

    private HologramMachineUpdateBatch(Updates updates, boolean temporary) {
        previous = CURRENT.get();
        this.updates = previous == null ? updates : previous.updates;
        this.temporary = temporary && previous == null;
        enabled = previous == null ? RunnableMachineUpdate.isCurrentThreadEnabled() : previous.enabled;
        CURRENT.set(this);
        if (DRAIN.get() == null) RunnableMachineUpdate.setCurrentThreadEnabled(false);
    }

    static HologramMachineUpdateBatch open() {
        return new HologramMachineUpdateBatch(new Updates(), true);
    }

    public static boolean suppressDuplicate(World world, int x, int y, int z) {
        var scope = CURRENT.get();
        if (scope == null || DRAIN.get() != null) return false;
        if (scope.enabled && RunnableMachineUpdate.isEnabled()) scope.updates.add(world, x, y, z);
        return true;
    }

    public static Drain pauseForDrain() {
        return new Drain();
    }

    public static final class Drain implements AutoCloseable {

        private final Drain previous = DRAIN.get();
        private final boolean enabled = RunnableMachineUpdate.isCurrentThreadEnabled();
        private boolean closed;

        private Drain() {
            DRAIN.set(this);
            var scope = CURRENT.get();
            if (scope != null && scope.enabled) {
                RunnableMachineUpdate.setCurrentThreadEnabled(true);
                scope.updates.flush();
            }
        }

        public void close() {
            if (closed) return;
            closed = true;
            RunnableMachineUpdate.setCurrentThreadEnabled(enabled);
            if (previous == null) DRAIN.remove();
            else DRAIN.set(previous);
        }
    }

    public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Machine update scopes must close in order");
        closed = true;
        if (previous == null) {
            CURRENT.remove();
            RunnableMachineUpdate.setCurrentThreadEnabled(enabled);
            if (temporary || updates.finishing) updates.flush();
        } else CURRENT.set(previous);
    }
}
