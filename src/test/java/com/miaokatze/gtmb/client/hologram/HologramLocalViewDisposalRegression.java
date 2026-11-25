// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramLocalViewDisposalRegression {

    private HologramLocalViewDisposalRegression() {}

    public static void run() {
        var release = new CountDownLatch(1);
        var drained = new CountDownLatch(1);
        try {
            // Substitute the Minecraft admission and file-read boundaries; keep the real close and worker gates.
            var workspaceField = HologramUiLibrary.class.getDeclaredField("localWorkspace");
            workspaceField.setAccessible(true);
            var oldLibrary = new HologramUiLibrary(null);
            var newLibrary = new HologramUiLibrary(null);
            var saveLibrary = new HologramUiLibrary(null);
            var old = (HologramLocalBlueprintWorkspace) workspaceField.get(oldLibrary);
            var next = (HologramLocalBlueprintWorkspace) workspaceField.get(newLibrary);
            var saving = (HologramLocalBlueprintWorkspace) workspaceField.get(saveLibrary);

            Class<?> type = HologramLocalBlueprintWorkspace.class;
            Class<?> taskType = Class.forName(type.getName() + "$Task");
            Class<?> fileWorkType = Class.forName(type.getName() + "$FileWork");
            var constructor = taskType.getDeclaredConstructor(
                HologramState.class,
                HologramLocalBlueprintWorkspace.PresentationOwner.class,
                HologramLocalBlueprintDocument.class,
                HologramPrivateEditPerformance.Request.class,
                boolean.class);
            constructor.setAccessible(true);
            var taskField = type.getDeclaredField("task");
            taskField.setAccessible(true);
            var staleOpen = constructor.newInstance(null, null, null, null, true);
            var nextOpen = constructor.newInstance(null, null, null, null, true);
            var durableSave = constructor.newInstance(null, null, null, null, false);
            taskField.set(old, staleOpen);
            taskField.set(next, nextOpen);
            taskField.set(saving, durableSave);

            var workerField = type.getDeclaredField("IO");
            workerField.setAccessible(true);
            var worker = (ThreadPoolExecutor) workerField.get(null);
            var enqueue = type.getDeclaredMethod("work", taskType, fileWorkType);
            enqueue.setAccessible(true);
            var oldCalls = new AtomicInteger();
            var newCalls = new AtomicInteger();
            var saveCalls = new AtomicInteger();
            worker.execute(() -> await(release));
            enqueue.invoke(old, staleOpen, fileWork(fileWorkType, oldCalls));
            SimpleAssert.that(old.busy(), "an ordinary open stays owned until final disposal");
            oldLibrary.retireLocalView();
            SimpleAssert.that(!old.busy(), "final disposal immediately retires ordinary open, not just resume");

            // A read that returns after disposal must not reach the decoder even if it was already executing.
            var prepare = type.getDeclaredMethod(
                "prepareOpen",
                taskType,
                HologramClientLibrary.EditorSource.class,
                String.class,
                String.class);
            prepare.setAccessible(true);
            prepare.invoke(old, staleOpen, null, "", "");

            int queued = worker.getQueue()
                .size();
            enqueue.invoke(old, staleOpen, fileWork(fileWorkType, oldCalls));
            SimpleAssert.eq(
                queued,
                worker.getQueue()
                    .size(),
                "a late open callback cannot requeue old work");
            enqueue.invoke(next, nextOpen, fileWork(fileWorkType, newCalls));
            enqueue.invoke(saving, durableSave, fileWork(fileWorkType, saveCalls));
            saveLibrary.retireLocalView();
            SimpleAssert.that(saving.busy(), "closing the save's own window keeps its durable work alive");
            worker.execute(drained::countDown);
            release.countDown();
            await(drained);
            SimpleAssert.eq(0, oldCalls.get(), "queued disposed open never starts reading");
            SimpleAssert.eq(1, newCalls.get(), "successor work takes the same worker");
            SimpleAssert.eq(1, saveCalls.get(), "already admitted save still reaches its worker");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Local close/open lifecycle regression", failure);
        } finally {
            release.countDown();
        }
    }

    private static Object fileWork(Class<?> type, AtomicInteger calls) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, arguments) -> {
            calls.incrementAndGet();
            return null;
        });
    }

    private static void await(CountDownLatch gate) {
        try {
            SimpleAssert.that(gate.await(5, TimeUnit.SECONDS), "local file worker rendezvous");
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new AssertionError("Interrupted local file worker rendezvous", interrupted);
        }
    }
}
