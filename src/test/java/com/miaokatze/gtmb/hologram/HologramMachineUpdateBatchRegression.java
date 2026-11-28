// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.interfaces.tileentity.IMachineBlockUpdateable;
import gregtech.api.threads.RunnableMachineUpdate;

/** Real GT seed queue and propagation; the detached test invokes the Mixin's admission hook explicitly. */
public final class HologramMachineUpdateBatchRegression {

    private HologramMachineUpdateBatchRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramMachineUpdateBatchRegression.class, args)) return;
        run();
        System.out.println("HologramMachineUpdateBatchRegression PASS");
    }

    static void run() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var world = world(unsafe);
        var peer = world(unsafe);
        boolean global = RunnableMachineUpdate.isEnabled();
        boolean thread = RunnableMachineUpdate.isCurrentThreadEnabled();
        // Isolate existing suite events. Private queue reflection is test evidence only, never production ABI.
        Field handlersField = RunnableMachineUpdate.class.getDeclaredField("perWorldHandler");
        handlersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var handlers = (Map<World, RunnableMachineUpdate>) handlersField.get(null);
        var previousHandlers = new HashMap<>(handlers);
        handlers.clear();
        try {
            RunnableMachineUpdate.setEnabled(true);
            RunnableMachineUpdate.setCurrentThreadEnabled(true);
            passThrough(world, handlers);
            threadIsolation(world);
            handlers.clear(); // thread-isolation terminal flush is a separate fixture, not the next test's work.
            admissionAndCleanup(world, peer, handlers);
            drainWithinSlice(world, handlers);
            drainFailureAndNesting(world, handlers);
            disconnectedNeighbors(world, peer, handlers);
        } finally {
            handlers.clear();
            handlers.putAll(previousHandlers);
            RunnableMachineUpdate.setEnabled(global);
            RunnableMachineUpdate.setCurrentThreadEnabled(thread);
        }
    }

    private static void passThrough(World world, Map<World, RunnableMachineUpdate> handlers) throws Exception {
        seed(world, 0, 64, 0);
        seed(world, 0, 64, 0);
        SimpleAssert.eq(2, queued(handlers, world), "unrelated GT callers retain native queue semantics");
        handlers.clear();
        var updates = new HologramMachineUpdateBatch.Updates();
        try (var outer = updates.open()) {
            for (int repeat = 0; repeat < 1000; repeat++) seed(world, 0, 64, 0);
            try (var inner = updates.open()) {
                seed(world, 100, 64, 0);
            }
            SimpleAssert.eq(0, queued(handlers, world), "no construction seed enters GT traversal within a slice");
        }
        try (var later = updates.open()) {
            seed(world, 0, 64, 0);
            seed(world, 100, 64, 0);
        }
        SimpleAssert.eq(0, queued(handlers, world), "yielding does not flush a partly built machine");
        updates.finish();
        updates.finish();
        SimpleAssert.eq(2, queued(handlers, world), "terminal flush publishes each job-local seed once across slices");
        handlers.clear();
    }

    private static void admissionAndCleanup(World world, World peer, Map<World, RunnableMachineUpdate> handlers)
        throws Exception {
        var updates = new HologramMachineUpdateBatch.Updates();
        try {
            try (var ignored = updates.open()) {
                seed(world, 0, 64, 0);
                seed(peer, 0, 64, 0);
                throw new IllegalArgumentException("callback after partial commit");
            }
        } catch (IllegalArgumentException expected) {
            updates.finish();
        }
        SimpleAssert.eq(1, queued(handlers, world), "exception terminal flush retains the original world's seed");
        SimpleAssert.eq(1, queued(handlers, peer), "other callback worlds retain independent seeds");
        SimpleAssert.that(RunnableMachineUpdate.isCurrentThreadEnabled(), "scope finally restores enabled thread");
        handlers.clear();
        RunnableMachineUpdate.setCurrentThreadEnabled(false);
        var disabled = new HologramMachineUpdateBatch.Updates();
        try (var ignored = disabled.open()) {
            seed(world, 0, 64, 0);
        }
        disabled.finish();
        SimpleAssert.that(
            !RunnableMachineUpdate.isCurrentThreadEnabled() && handlers.isEmpty(),
            "originally disabled updates remain disabled with no invented requests");
        RunnableMachineUpdate.setCurrentThreadEnabled(true);
    }

    private static void threadIsolation(World world) throws InterruptedException {
        var failures = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var updates = new HologramMachineUpdateBatch.Updates();
        try (var ignored = updates.open()) {
            SimpleAssert.that(
                HologramMachineUpdateBatch.suppressDuplicate(world, 0, 64, 0),
                "scope captures its first native request");
            var peerThread = new Thread(() -> {
                try {
                    SimpleAssert.that(
                        !HologramMachineUpdateBatch.suppressDuplicate(world, 0, 64, 0),
                        "unrelated thread cannot inherit construction suppression");
                } catch (Throwable failure) {
                    failures.set(failure);
                }
            });
            peerThread.start();
            peerThread.join();
            if (failures.get() != null) throw new AssertionError("thread-local isolation", failures.get());
        }
        updates.finish();
    }

    private static void disconnectedNeighbors(HologramServiceEditRegression.ReadOnlyWorld world,
        HologramServiceEditRegression.ReadOnlyWorld peer, Map<World, RunnableMachineUpdate> handlers) {
        var left = new Updateable();
        var right = new Updateable();
        var otherWorld = new Updateable();
        world.occupants.put("-1,64,0", left);
        world.occupants.put("101,64,0", right);
        peer.occupants.put("1,64,0", otherWorld);
        var updates = new HologramMachineUpdateBatch.Updates();
        try (var ignored = updates.open()) {
            seed(world, 0, 64, 0);
            seed(world, 100, 64, 0);
            seed(peer, 0, 64, 0);
        }
        updates.finish();
        RunnableMachineUpdate.endTick();
        SimpleAssert.eq(1, left.updates, "first broken component receives native propagation");
        SimpleAssert.eq(1, right.updates, "disconnected second component receives native propagation");
        SimpleAssert.eq(1, otherWorld.updates, "all callback worlds are flushed");
        SimpleAssert.that(handlers.isEmpty(), "native endTick drains its own complete queue");
        world.occupants.clear();
        peer.occupants.clear();
    }

    private static void drainWithinSlice(HologramServiceEditRegression.ReadOnlyWorld world,
        Map<World, RunnableMachineUpdate> handlers) throws Exception {
        var trigger = new Updateable();
        var duringDrain = new Updateable();
        world.occupants.put("-1,64,0", trigger);
        world.occupants.put("100,64,0", duringDrain);
        trigger.action = () -> {
            try (var callback = HologramMachineUpdateBatch.open()) {
                seed(world, 100, 64, 0);
                seed(world, 100, 64, 0);
            }
        };
        var updates = new HologramMachineUpdateBatch.Updates();
        try {
            try (var slice = updates.open()) {
                seed(world, 0, 64, 0);
                drain();
                SimpleAssert
                    .eq(2, duringDrain.updates, "explicit native drains keep callback updates outside suppression");
                SimpleAssert
                    .that(!RunnableMachineUpdate.isCurrentThreadEnabled(), "drain restores the enclosing slice switch");
                seed(world, 0, 64, 0);
            }
            updates.finish();
            RunnableMachineUpdate.endTick();
            SimpleAssert.eq(2, trigger.updates, "post-drain construction requests remain deliverable at terminal");
        } finally {
            world.occupants.clear();
            handlers.clear();
        }
    }

    private static void drainFailureAndNesting(World world, Map<World, RunnableMachineUpdate> handlers)
        throws Exception {
        var updates = new HologramMachineUpdateBatch.Updates();
        try (var slice = updates.open()) {
            seed(world, 0, 64, 0);
            try {
                try (var paused = HologramMachineUpdateBatch.pauseForDrain();
                    var nested = HologramMachineUpdateBatch.pauseForDrain()) {
                    SimpleAssert.that(
                        RunnableMachineUpdate.isCurrentThreadEnabled(),
                        "nested drain preserves native callback execution");
                    handlers.clear();
                    throw new IllegalStateException("failed native drain");
                }
            } catch (IllegalStateException expected) {}
            SimpleAssert.that(!RunnableMachineUpdate.isCurrentThreadEnabled(), "failed drain restores enclosing scope");
            seed(world, 100, 64, 0);
        }
        updates.finish();
        SimpleAssert.eq(1, queued(handlers, world), "failure does not discard later native requests");
        SimpleAssert.that(RunnableMachineUpdate.isCurrentThreadEnabled(), "outer finally restores native switch");
        handlers.clear();
    }

    /** Detached bootstrap has no Mixin transformer; execute its exact wrap boundary around real GT endTick. */
    private static void drain() {
        try (var paused = HologramMachineUpdateBatch.pauseForDrain()) {
            RunnableMachineUpdate.endTick();
        }
    }

    private static HologramServiceEditRegression.ReadOnlyWorld world(sun.misc.Unsafe unsafe) throws Exception {
        var world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        world.occupants = new HashMap<>();
        return world;
    }

    private static int queued(Map<World, RunnableMachineUpdate> handlers, World world) throws Exception {
        Field queue = RunnableMachineUpdate.class.getDeclaredField("tQueue");
        queue.setAccessible(true);
        if (!handlers.containsKey(world)) return 0;
        Object value = queue.get(handlers.get(world));
        return ((Number) value.getClass()
            .getMethod("size")
            .invoke(value)).intValue();
    }

    private static void seed(World world, int x, int y, int z) {
        if (!HologramMachineUpdateBatch.suppressDuplicate(world, x, y, z))
            RunnableMachineUpdate.setMachineUpdateValues(world, x, y, z);
    }

    private static final class Updateable extends TileEntity implements IMachineBlockUpdateable {

        int updates;
        Runnable action;

        @Override
        public void onMachineBlockUpdate() {
            updates++;
            Runnable callback = action;
            action = null;
            if (callback != null) callback.run();
        }
    }
}
