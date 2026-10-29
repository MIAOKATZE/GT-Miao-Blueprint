package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.gameevent.TickEvent;

/** CPU owner/cursor tests only. This does not exercise native GL geometry, framebuffer or PNG readback. */
public final class HologramCapturePreparationRegression {

    private HologramCapturePreparationRegression() {}

    public static void run() {
        try {
            queueIncludesActive();
            boundedProgressAndHandoff();
            cancellationAndFailure();
            incompleteInput();
            effectiveSideValidation();
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("capture preparation fixture", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void queueIncludesActive() throws ReflectiveOperationException {
        ArrayBlockingQueue<HologramBlueprintPreview.Request> queue = (ArrayBlockingQueue<HologramBlueprintPreview.Request>) get(
            null,
            "REQUESTS",
            HologramBlueprintPreview.class);
        SimpleAssert.that(queue.isEmpty(), "fixture requires no external capture owner");
        int[] failures = { 0 };
        HologramBlueprintPreview.Request first = request(scene(600), () -> true, () -> {}, failures);
        HologramBlueprintPreview listener = (HologramBlueprintPreview) get(
            null,
            "LISTENER",
            HologramBlueprintPreview.class);
        try {
            queue.add(first);
            for (int i = 0; i < 3; i++) queue.add(request(scene(1), () -> true, () -> {}, failures));
            listener.onRenderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.END, 0));
            SimpleAssert.that(queue.peek() == first, "multi-slice active request remains at queue head");
            SimpleAssert.eq(4, queue.size(), "active capture consumes one of the four existing queue slots");
            SimpleAssert.that(
                !queue.offer(request(scene(1), () -> true, () -> {}, failures)),
                "fifth request cannot enter while first request prepares");
            for (HologramBlueprintPreview.Request request : queue) request.cancel();
            for (int i = 0; i < 4; i++) listener.onRenderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.END, 0));
            SimpleAssert.that(queue.isEmpty(), "cancelled active and waiting requests drain without rendering");
            SimpleAssert.eq(4, failures[0], "every cancelled admitted request completes exactly once");
        } finally {
            queue.clear();
            dispose(first);
        }
    }

    private static void boundedProgressAndHandoff() throws ReflectiveOperationException, IOException {
        ProbeAdapter adapter = new ProbeAdapter();
        HologramNativeRenderRegistry.register(adapter);
        HologramBlueprintPreview.Request request = null;
        try {
            int[] progress = { 0 }, failures = { 0 };
            request = request(scene(40), () -> true, () -> progress[0]++, failures);
            SimpleAssert.that(!step(request, 0), "zero callback slice leaves native preparation pending");
            HologramBlockAccess access = access(request);
            HologramState state = (HologramState) get(request, "preparedState");
            SimpleAssert.that(access.owns(state, false), "capture access owns the exact immutable state and side");
            inertWorld(access);
            int observed = progress[0];
            SimpleAssert.that(!step(request, 0), "unchanged pending native phase remains pending");
            SimpleAssert.eq(observed, progress[0], "polling without work never extends the save idle timeout");
            HologramNativePreparation.Budget budget = new HologramNativePreparation.Budget(
                Long.MAX_VALUE,
                32,
                () -> 0L);
            SimpleAssert.that(!request.prepare(Long.MAX_VALUE, budget), "long chain yields before final capture");
            SimpleAssert.eq(32, budget.used(), "all coordinates share one 32-callback slice");
            SimpleAssert.that(progress[0] > observed, "real dependency work reports progress before root completion");
            int slices = 0;
            while (!step(request, 32))
                if (++slices > 20) throw new AssertionError("finite capture lane did not finish");
            SimpleAssert
                .eq(40, (Integer) get(request, "targetCursor"), "offscreen source coordinates are prepared too");
            invoke(request, "adoptPreparedAccesses");
            SimpleAssert.that(get(request, "target") == null, "handoff relinquishes request ownership");
            SimpleAssert.that(
                get(null, "target", HologramBlockRender.class) == access,
                "handoff adopts the exact access, not copied tile caches");
            HologramBlockRender.begin(state);
            SimpleAssert.that(
                get(null, "target", HologramBlockRender.class) == access,
                "normal begin for the same state preserves prepared access identity");
            dispose(request);
            SimpleAssert.that(access.owns(state, false), "request cleanup cannot close an adopted access");
            HologramBlockRender.clear();
            SimpleAssert.that(!access.owns(state, false), "renderer retirement closes adopted access exactly once");
        } finally {
            if (request != null) dispose(request);
            HologramBlockRender.clear();
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static void cancellationAndFailure() throws ReflectiveOperationException, IOException {
        int[] failures = { 0 };
        HologramBlueprintPreview.Request cancelled = request(scene(1), () -> true, () -> {}, failures);
        cancelled.cancel();
        expectCancelled(cancelled, "queued cancellation precedes state preparation");
        HologramBlueprintPreview.Request preparing = request(scene(600), () -> true, () -> {}, failures);
        SimpleAssert.that(!step(preparing, 0), "state preparation yields at its coordinate cap");
        preparing.cancel();
        expectCancelled(preparing, "state preparation cancellation rejects continuation");
        dispose(preparing);
        HologramBlueprintPreview.Request replaced = request(scene(1), () -> false, () -> {}, failures);
        expectCancelled(replaced, "source replacement rejects old request before preparation");

        ProbeAdapter adapter = new ProbeAdapter();
        HologramNativeRenderRegistry.register(adapter);
        HologramBlueprintPreview.Request request = request(scene(2), () -> true, () -> {}, failures);
        try {
            step(request, 0);
            HologramBlockAccess access = access(request);
            HologramState state = (HologramState) get(request, "preparedState");
            inertWorld(access);
            adapter.duringLoad = request::cancel;
            expectCancelled(request, "cancellation inside native callback is detected after callback unwinds");
            SimpleAssert
                .that(access.owns(state, false), "cancel flag does not concurrently dispose active native lane");
            fail(request, new CancellationException("cancelled"));
            fail(request, new IOException("duplicate"));
            SimpleAssert.eq(1, failures[0], "competing terminal failures notify once");
            dispose(request);
            SimpleAssert.that(!access.owns(state, false), "owner cleanup releases cancelled native access");

            adapter.duringLoad = null;
            request = request(scene(1), () -> true, () -> {}, failures);
            step(request, 0);
            AtomicLong epoch = (AtomicLong) get(null, "RESOURCE_GENERATION", HologramBlueprintPreview.class);
            epoch.incrementAndGet();
            expectCancelled(request, "resource reload invalidates request-owned accesses before handoff");
            dispose(request);

            request = request(scene(2), () -> true, () -> {}, failures);
            step(request, 0);
            inertWorld(access(request));
            adapter.fail = true;
            try {
                step(request, 32);
                throw new AssertionError("native failure was accepted as capture readiness");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("preparation failed"),
                    "native terminal failures are explicit capture errors");
            }
            dispose(request);
            adapter.fail = false;
            adapter.cycle = true;
            request = request(scene(2), () -> true, () -> {}, failures);
            step(request, 0);
            inertWorld(access(request));
            try {
                step(request, 32);
                throw new AssertionError("cycle was accepted as capture readiness");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("cycle"),
                    "cyclic input has an explicit capture error");
            }
            dispose(request);
            adapter.cycle = false;
            request = request(scene(1), () -> true, () -> {}, failures);
            step(request, 0);
            inertWorld(access(request));
            SimpleAssert.that(step(request, 32), "ready request reaches pre-handoff phase");
            request.cancel();
            expectCancelled(request, "ready request cancellation prevents final handoff");
        } finally {
            dispose(request);
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static void incompleteInput() throws IOException {
        for (boolean partial : new boolean[] { true, false }) {
            NBTTagCompound source = scene(1);
            if (partial) source.setBoolean("partial", true);
            else source.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .setInteger("nativeModelPending", 2);
            HologramBlueprintPreview.Request request = request(source, () -> true, () -> {}, new int[1]);
            try {
                step(request, 32);
                throw new AssertionError("incomplete capture source was accepted");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains(partial ? "completely prepared" : "incomplete"),
                    "partial and pending snapshots fail before native preparation");
            }
        }
    }

    private static void effectiveSideValidation() throws ReflectiveOperationException, IOException {
        ProbeAdapter adapter = new ProbeAdapter();
        HologramNativeRenderRegistry.register(adapter);
        try {
            // The actual tile is ready; injected model observations independently represent renderer failure.
            for (String role : new String[] { "mode2", "anchor", "protected", "anchorMode2", "protectedMode2" }) {
                NBTTagCompound source = sideScene(role, true);
                HologramState state = new HologramState(source);
                HologramState.Cell cell = state.cells.get(0);
                int expected = role.equals("mode2") ? 3 : 2;
                SimpleAssert.eq(
                    expected,
                    HologramBlueprintPreview.captureSides(state, cell),
                    role + " uses normalized, deduplicated sides for both preflight and validation");
                HologramBlueprintPreview.Request request = request(source, () -> true, () -> {}, new int[1]);
                Field prepared = request.getClass()
                    .getDeclaredField("preparedState");
                prepared.setAccessible(true);
                prepared.set(request, state); // Already decoded immutable input; no worker or GL involved.
                try {
                    SimpleAssert.that(!step(request, 0), role + " awaits actual native preparation");
                    SimpleAssert.that(
                        get(request, "target") == null,
                        role + " ordinary target does not allocate a native target access");
                    HologramBlockAccess actual = (HologramBlockAccess) get(request, "actual");
                    SimpleAssert.that(
                        actual != null && actual.owns(state, true),
                        role + " prehydration owns the effective actual side");
                    inertWorld(actual);
                    SimpleAssert.that(step(request, 32), role + " actual native tile prepares successfully");
                    SimpleAssert.eq(
                        HologramNativePreparation.Outcome.READY,
                        actual.outcome(cell),
                        role + " later model validation is not merely detecting tile load failure");
                    ModelInspection inspection = new ModelInspection();
                    inspection.failActual = true;
                    expectModelFailure(state, inspection, "model unavailable");
                    SimpleAssert.eq(2, inspection.readySides, role + " native support is classified from actual block");
                    SimpleAssert
                        .eq(1, inspection.readyCalls, role + " duplicate normalized actual side is checked once");
                    inspection = new ModelInspection();
                    inspection.missingActual = true;
                    expectModelFailure(state, inspection, "material unavailable");
                    SimpleAssert.eq(2, inspection.textureSides, role + " checks texture on the rendered actual side");
                    inspection = new ModelInspection();
                    HologramBlueprintPreview.validateModels(state, inspection);
                    SimpleAssert.eq(
                        role.equals("mode2") ? 1 : 0,
                        inspection.materialSides,
                        role + " retains ordinary target validation only when it is a distinct effective side");
                } finally {
                    dispose(request);
                }
            }
            HologramState targetView = new HologramState(sideScene("mode0", false));
            ModelInspection success = new ModelInspection();
            HologramBlueprintPreview.validateModels(targetView, success);
            SimpleAssert.eq(1, success.readySides, "ordinary mode-0 native target success remains unchanged");
            SimpleAssert.eq(0, success.materialSides, "mode-0 does not inspect an undrawn ordinary actual side");
            HologramState actualMaterial = new HologramState(sideScene("mode2", false));
            ModelInspection fallback = new ModelInspection();
            fallback.fallbackActual = true;
            expectModelFailure(actualMaterial, fallback, "Blueprint material unavailable");
            SimpleAssert.eq(2, fallback.materialSides, "mode-2 ordinary actual fallback is rejected too");
            ModelInspection failedTarget = new ModelInspection();
            failedTarget.failTarget = true;
            expectModelFailure(actualMaterial, failedTarget, "model unavailable");
            SimpleAssert
                .eq(1, failedTarget.readySides, "mode-2 preserves the existing distinct target-validation policy");
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static NBTTagCompound sideScene(String role, boolean nativeActual) {
        NBTTagCompound source = new NBTTagCompound(), row = new NBTTagCompound(), display = new NBTTagCompound();
        source.setInteger("mode", role.equals("mode2") || role.endsWith("Mode2") ? 2 : 0);
        row.setString("wantId", nativeActual ? "minecraft:dirt" : "minecraft:stone");
        row.setString("id", nativeActual ? "minecraft:stone" : "minecraft:dirt");
        row.setBoolean("anchor", role.startsWith("anchor"));
        if (role.startsWith("protected")) {
            row.setString("status", "protected");
            row.setTag("actualStack", new net.minecraft.item.ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        }
        display.setTag("nativeTile", new NBTTagCompound());
        row.setTag(nativeActual ? "actualDisplay" : "targetDisplay", display);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        source.setTag("cells", rows);
        return source;
    }

    private static void expectModelFailure(HologramState state, ModelInspection inspection, String reason)
        throws IOException {
        try {
            HologramBlueprintPreview.validateModels(state, inspection);
            throw new AssertionError("capture accepted a native/model/material fallback");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains(reason),
                "effective-side capture rejection: " + reason);
        }
    }

    /** Controlled post-render observations only; never creates model lists or claims native renderer execution. */
    private static final class ModelInspection implements HologramBlueprintPreview.CaptureInspection {

        boolean failActual, failTarget, missingActual, fallbackActual;
        int readySides, textureSides, materialSides, readyCalls;

        public boolean ready(HologramState.Cell cell, boolean current) {
            readySides |= current ? 2 : 1;
            readyCalls++;
            return !(current ? failActual : failTarget);
        }

        public String failure(HologramState.Cell cell, boolean current) {
            return "injected zero/failed native model";
        }

        public boolean missingTexture(HologramState.Cell cell, boolean current) {
            textureSides |= current ? 2 : 1;
            return current && missingActual;
        }

        public boolean materialFallback(HologramState.Cell cell, boolean current) {
            materialSides |= current ? 2 : 1;
            return current && fallbackActual;
        }
    }

    private static final class ProbeAdapter implements HologramNativeRenderRegistry.Adapter {

        Runnable duringLoad;
        boolean fail, cycle;

        public boolean supports(Block block) {
            return block == Blocks.stone;
        }

        public TileEntity createTile(Block block, int metadata, World world) {
            return new TileEntity();
        }

        public boolean requiresTile(Block block, int metadata) {
            return true;
        }

        public void loadTile(TileEntity tile, NBTTagCompound tag) {
            if (duringLoad != null) duringLoad.run();
            if (fail) throw new IllegalArgumentException("capture fixture native failure");
            tile.getWorldObj()
                .getTileEntity(cycle ? 1 - tile.xCoord : tile.xCoord + 1, tile.yCoord, tile.zCoord);
        }
    }

    private static NBTTagCompound scene(int count) {
        NBTTagCompound data = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (int x = 0; x < count; x++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("wantId", "minecraft:stone");
            row.setInteger("x", x);
            row.setInteger("dx", x);
            row.setTag("previewTile", new NBTTagCompound());
            rows.appendTag(row);
        }
        data.setTag("cells", rows);
        return data;
    }

    private static HologramBlueprintPreview.Request request(NBTTagCompound data,
        java.util.function.BooleanSupplier current, Runnable progress, int[] failures) {
        return new HologramBlueprintPreview.Request(
            data,
            0,
            0,
            current,
            image -> { throw new AssertionError("CPU fixture must never perform FBO capture"); },
            failure -> failures[0]++,
            progress);
    }

    private static boolean step(HologramBlueprintPreview.Request request, int callbacks) throws IOException {
        return request
            .prepare(Long.MAX_VALUE, new HologramNativePreparation.Budget(Long.MAX_VALUE, callbacks, () -> 0L));
    }

    private static void expectCancelled(HologramBlueprintPreview.Request request, String message) throws IOException {
        try {
            step(request, 32);
            throw new AssertionError(message);
        } catch (CancellationException expected) { /* No access disposal can occur inside cancel(). */ }
    }

    private static HologramBlockAccess access(HologramBlueprintPreview.Request request)
        throws ReflectiveOperationException {
        return (HologramBlockAccess) get(request, "target");
    }

    private static Object get(Object owner, String name) throws ReflectiveOperationException {
        return get(owner, name, owner.getClass());
    }

    private static Object get(Object owner, String name, Class<?> type) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void invoke(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass()
            .getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }

    private static void dispose(HologramBlueprintPreview.Request request) throws ReflectiveOperationException {
        invoke(request, "releasePreparation");
    }

    private static void fail(HologramBlueprintPreview.Request request, Throwable cause)
        throws ReflectiveOperationException {
        Method method = request.getClass()
            .getDeclaredMethod("fail", Throwable.class);
        method.setAccessible(true);
        method.invoke(request, cause);
    }

    private static void inertWorld(HologramBlockAccess access) throws ReflectiveOperationException {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        HologramRenderWorld world = (HologramRenderWorld) ((sun.misc.Unsafe) unsafeField.get(null))
            .allocateInstance(HologramRenderWorld.class);
        world.isRemote = true;
        Field field = HologramRenderWorld.class.getDeclaredField("access");
        field.setAccessible(true);
        field.set(world, access);
        field = HologramBlockAccess.class.getDeclaredField("renderWorld");
        field.setAccessible(true);
        field.set(access, world);
    }
}
