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
        SimpleAssert.eq(2, queued(handlers, world), "ordinary GT callers retain duplicate events outside job scope");
        handlers.clear();
        try (var outer = HologramMachineUpdateBatch.open()) {
            seed(world, 0, 64, 0);
            SimpleAssert.eq(1, queued(handlers, world), "first event is queued immediately, before batch close");
            for (int repeat = 0; repeat < 1000; repeat++) seed(world, 0, 64, 0);
            try (var inner = HologramMachineUpdateBatch.open()) {
                seed(world, 0, 64, 0);
                seed(world, 100, 64, 0);
            }
            seed(world, 100, 64, 0);
            SimpleAssert.eq(2, queued(handlers, world), "nested synchronous slices share exact distinct seeds");
        }
        try (var nextSlice = HologramMachineUpdateBatch.open()) {
            seed(world, 0, 64, 0);
        }
        SimpleAssert.eq(3, queued(handlers, world), "later slices retain their own native events");
        handlers.clear();
    }

    private static void admissionAndCleanup(World world, World peer, Map<World, RunnableMachineUpdate> handlers)
        throws Exception {
        try {
            try (var ignored = HologramMachineUpdateBatch.open()) {
                RunnableMachineUpdate.setCurrentThreadEnabled(false);
                seed(world, 0, 64, 0);
                SimpleAssert
                    .that(!RunnableMachineUpdate.isCurrentThreadEnabled(), "batch leaves disabled thread alone");
                RunnableMachineUpdate.setCurrentThreadEnabled(true);
                seed(world, 0, 64, 0);
                seed(peer, 0, 64, 0);
                RunnableMachineUpdate.setEnabled(false);
                seed(world, 100, 64, 0);
                RunnableMachineUpdate.setEnabled(true);
                seed(world, 100, 64, 0);
                throw new IllegalArgumentException("callback after partial commit");
            }
        } catch (IllegalArgumentException expected) {
            SimpleAssert.eq("callback after partial commit", expected.getMessage(), "callback failure is unchanged");
        }
        SimpleAssert.eq(2, queued(handlers, world), "disabled requests do not poison later enabled admission");
        SimpleAssert.eq(1, queued(handlers, peer), "same coordinates in another world remain independent");
        seed(world, 0, 64, 0);
        SimpleAssert.eq(3, queued(handlers, world), "exception restores pass-through and retains committed seeds");
        SimpleAssert.that(RunnableMachineUpdate.isEnabled(), "batch does not change global flag");
        SimpleAssert.that(RunnableMachineUpdate.isCurrentThreadEnabled(), "batch does not change thread flag");
        // A pre-disabled caller stays disabled even when no mutation succeeds.
        RunnableMachineUpdate.setCurrentThreadEnabled(false);
        try (var ignored = HologramMachineUpdateBatch.open()) {
            seed(world, 500, 64, 0);
        }
        SimpleAssert.that(!RunnableMachineUpdate.isCurrentThreadEnabled(), "close preserves pre-disabled thread");
        RunnableMachineUpdate.setCurrentThreadEnabled(true);
        RunnableMachineUpdate.setEnabled(false);
        try (var ignored = HologramMachineUpdateBatch.open()) {
            seed(world, 500, 64, 0);
        }
        SimpleAssert.that(!RunnableMachineUpdate.isEnabled(), "close preserves pre-disabled global flag");
        RunnableMachineUpdate.setEnabled(true);
        handlers.clear();
    }

    private static void threadIsolation(World world) throws InterruptedException {
        var failures = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var ignored = HologramMachineUpdateBatch.open()) {
            SimpleAssert.that(!HologramMachineUpdateBatch.suppressDuplicate(world, 0, 64, 0), "first parent seed");
            var peerThread = new Thread(() -> {
                try {
                    SimpleAssert.that(
                        !HologramMachineUpdateBatch.suppressDuplicate(world, 0, 64, 0),
                        "an unrelated thread cannot inherit a construction scope");
                } catch (Throwable failure) {
                    failures.set(failure);
                }
            });
            peerThread.start();
            peerThread.join();
            if (failures.get() != null) throw new AssertionError("thread-local batch isolation", failures.get());
            SimpleAssert
                .that(HologramMachineUpdateBatch.suppressDuplicate(world, 0, 64, 0), "parent scope remains active");
        }
    }

    private static void disconnectedNeighbors(HologramServiceEditRegression.ReadOnlyWorld world,
        HologramServiceEditRegression.ReadOnlyWorld peer, Map<World, RunnableMachineUpdate> handlers) {
        var left = new Updateable();
        var right = new Updateable();
        var otherWorld = new Updateable();
        world.occupants.put("-1,64,0", left);
        world.occupants.put("101,64,0", right);
        peer.occupants.put("1,64,0", otherWorld);
        try (var ignored = HologramMachineUpdateBatch.open()) {
            // Air at two removal sites separates their surviving components. No chosen controller connects them.
            seed(world, 0, 64, 0);
            seed(world, 100, 64, 0);
            seed(world, 0, 64, 0);
            seed(peer, 0, 64, 0);
            seed(peer, 0, 64, 0);
        }
        RunnableMachineUpdate.endTick();
        SimpleAssert.eq(1, left.updates, "first removed site's surviving neighbor is updated");
        SimpleAssert.eq(1, right.updates, "disconnected surviving component is also updated");
        SimpleAssert.eq(1, otherWorld.updates, "callback destination in a second world is also updated");
        SimpleAssert.that(handlers.isEmpty(), "real GT endTick drains every world normally");
    }

    private static void drainWithinSlice(HologramServiceEditRegression.ReadOnlyWorld world,
        Map<World, RunnableMachineUpdate> handlers) throws Exception {
        var trigger = new Updateable();
        var duringDrain = new Updateable();
        world.occupants.put("-1,64,0", trigger);
        world.occupants.put("100,64,0", duringDrain);
        trigger.action = () -> {
            try (var nested = HologramMachineUpdateBatch.open()) {
                seed(world, 100, 64, 0);
                seed(world, 100, 64, 0);
            }
        };
        try {
            try (var slice = HologramMachineUpdateBatch.open()) {
                seed(world, 0, 64, 0);
                drain();
                SimpleAssert.that(handlers.isEmpty(), "synchronous endTick clears the real queue inside a slice");
                SimpleAssert.eq(2, duringDrain.updates, "drain callbacks retain duplicates even in a new nested batch");
                seed(world, 0, 64, 0);
                seed(world, 0, 64, 0);
                SimpleAssert.eq(1, queued(handlers, world), "same coordinate is admitted anew after synchronous drain");
                drain();
                SimpleAssert.eq(2, trigger.updates, "post-drain seed reaches the same surviving machine again");
            }
        } finally {
            world.occupants.clear();
            handlers.clear();
        }
    }

    private static void drainFailureAndNesting(World world, Map<World, RunnableMachineUpdate> handlers)
        throws Exception {
        try (var slice = HologramMachineUpdateBatch.open()) {
            seed(world, 0, 64, 0);
            try {
                try (var paused = HologramMachineUpdateBatch.pauseForDrain()) {
                    try (var nested = HologramMachineUpdateBatch.pauseForDrain();
                        var callback = HologramMachineUpdateBatch.open()) {
                        seed(world, 0, 64, 0);
                        seed(world, 0, 64, 0);
                    }
                    seed(world, 0, 64, 0);
                    SimpleAssert.eq(4, queued(handlers, world), "closing a nested drain leaves its parent paused");
                    handlers.clear();
                    throw new IllegalStateException("failed native drain after queue clear");
                }
            } catch (IllegalStateException expected) {
                SimpleAssert
                    .eq("failed native drain after queue clear", expected.getMessage(), "drain error propagates");
            }
            seed(world, 0, 64, 0);
            seed(world, 0, 64, 0);
            SimpleAssert.eq(1, queued(handlers, world), "failed drain clears stale seeds and restores deduplication");
            RunnableMachineUpdate.setCurrentThreadEnabled(false);
            RunnableMachineUpdate.setEnabled(false);
            try (var paused = HologramMachineUpdateBatch.pauseForDrain()) {
                seed(world, 100, 64, 0);
            }
            SimpleAssert.that(!RunnableMachineUpdate.isEnabled(), "drain preserves disabled global state");
            SimpleAssert.that(!RunnableMachineUpdate.isCurrentThreadEnabled(), "drain preserves disabled thread state");
            RunnableMachineUpdate.setEnabled(true);
            RunnableMachineUpdate.setCurrentThreadEnabled(true);
        }
        seed(world, 0, 64, 0);
        SimpleAssert.eq(2, queued(handlers, world), "failed drain and outer close leave no pause or batch behind");
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
