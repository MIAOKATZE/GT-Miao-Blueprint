package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Registered legacy fixtures exercise real local boundaries, not a physical-side or thread-name approximation. */
final class HologramExtensionExecutionRegression {

    private HologramExtensionExecutionRegression() {}

    static void run() throws Exception {
        nativePolicy();
        resourcePolicy();
        structureSourcePolicy();
        snapshotsAndAdmission();
        settledInspection();
        builtinAdmissionFastPath();
    }

    private static void nativePolicy() throws Exception {
        AtomicInteger legacy = new AtomicInteger(), safe = new AtomicInteger();
        try (GTMBExtensions.Registration old = NativePlacement.register("gtmb_test:owner-native", adapter(legacy))) {
            rejectLocal(() -> HologramNativePlacement.supported(Blocks.stone));
            SimpleAssert.eq(0, legacy.get(), "legacy supports is never probed on local author thread");
            JsonObject cell = new Gson().toJsonTree(source().cells.get(0))
                .getAsJsonObject();
            try {
                HologramLocalAuthorConfigurations.candidates(cell);
                throw new AssertionError("local candidates bypassed legacy provider");
            } catch (HologramExtensionExecution.ReviewRequired expected) {}
            SimpleAssert.eq(0, legacy.get(), "actual candidate boundary scopes legacy applicability");
            try (
                GTMBExtensions.Registration newer = NativePlacement
                    .register("gtmb_test:safe-native", adapter(safe), true);
                HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
                SimpleAssert.that(HologramNativePlacement.supported(Blocks.stone), "safe newer provider claims block");
            }
            SimpleAssert.eq(1, safe.get(), "explicit safe provider executes locally in existing precedence");
            try (GTMBExtensions.Registration ioRace = NativePlacement
                .register("gtmb_test:safe-native-io", adapter(safe), true)) {
                try (HologramExtensionExecution.Scope io = HologramExtensionExecution.builtinLibraryIo()) {
                    try {
                        HologramNativePlacement.supported(Blocks.stone);
                    } catch (RuntimeException swallowed) {}
                } catch (HologramExtensionExecution.ReviewRequired expected) {}
                SimpleAssert.that(!HologramExtensionExecution.localAuthorActive(), "IO guard restores thread scope");
                SimpleAssert.eq(1, safe.get(), "IO race guard runs before even client-safe supports");
            }
            SimpleAssert.eq(0, legacy.get(), "claimed safe provider does not probe legacy fallback");
            HologramNativePlacement.supported(Blocks.stone);
            SimpleAssert.eq(1, legacy.get(), "direct API still runs on caller after scope failure");
        }
        SimpleAssert.that(!HologramExtensionExecution.localAuthorActive(), "native scope restored");
    }

    private static NativePlacement.Adapter adapter(AtomicInteger calls) {
        return new NativePlacement.Adapter() {

            public boolean supports(Block block) {
                calls.incrementAndGet();
                return block == Blocks.stone;
            }

            public TileEntity create(Block block, int metadata, NBTTagCompound tag, World detachedWorld) {
                return new TileEntity();
            }

            public java.util.List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
                return Collections.singletonList(new ItemStack(Blocks.stone));
            }
        };
    }

    private static void resourcePolicy() throws Exception {
        AtomicInteger legacy = new AtomicInteger();
        ResourceContainers.Context context = new ResourceContainers.Context(
            "fixture",
            -1,
            "tank",
            "tank",
            "tank",
            true,
            new NBTTagCompound());
        try (GTMBExtensions.Registration old = ResourceContainers.register("gtmb_test:owner-fluid", value -> {
            legacy.incrementAndGet();
            return null;
        })) {
            rejectLocal(() -> ResourceContainers.decode(context));
            SimpleAssert.eq(0, legacy.get(), "legacy decode never probed even when it would return null");
            ResourceContainers.decode(context);
            SimpleAssert.eq(1, legacy.get(), "direct decoder contract remains caller-thread");
        }
        try (GTMBExtensions.Registration safe = ResourceContainers.register("gtmb_test:safe-fluid", value -> {
            legacy.incrementAndGet();
            return Collections.emptyList();
        }, true); HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            SimpleAssert.eq(
                0,
                ResourceContainers.decode(context)
                    .size(),
                "safe complete empty subtree accepted");
        }
        try (GTMBExtensions.Registration ioRace = ResourceContainers.register("gtmb_test:safe-fluid-io", value -> {
            legacy.incrementAndGet();
            return Collections.emptyList();
        }, true)) {
            int before = legacy.get();
            try (HologramExtensionExecution.Scope io = HologramExtensionExecution.builtinLibraryIo()) {
                ResourceContainers.decode(context);
                throw new AssertionError("client-safe codec was mistaken for IO-safe");
            } catch (HologramExtensionExecution.ReviewRequired expected) {}
            SimpleAssert.eq(before, legacy.get(), "IO race guard runs before client-safe codec");
        }
        NBTTagCompound malformed = new NBTTagCompound();
        malformed.setTag("#1", new NBTTagCompound());
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            try {
                ResourceContainers.numberedFluidSlots(malformed);
                throw new AssertionError("strict malformed native slots accepted");
            } catch (IOException expected) {}
        }
        // A provider failure swallowed by an inner inspector cannot turn into successful fallback completion.
        try (
            GTMBExtensions.Registration old = ResourceContainers.register("gtmb_test:swallowed-fluid", value -> null)) {
            boolean rejected = false;
            try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
                try {
                    ResourceContainers.decode(context);
                } catch (RuntimeException ignored) {}
            } catch (HologramExtensionExecution.ReviewRequired expected) {
                rejected = true;
            }
            SimpleAssert.that(rejected, "scope rejects swallowed legacy fallback");
        }
    }

    private static void structureSourcePolicy() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        Object controller = new Object();
        ItemStack signal = new ItemStack(Blocks.stone);
        try (GTMBExtensions.Registration legacy = StructureSources
            .register("gtmb_test:owner-source", Object.class, (value, trigger) -> {
                callbacks.incrementAndGet();
                trigger.stackSize = 7;
                return null;
            })) {
            rejectLocal(() -> StructureSources.resolve(controller, signal));
            SimpleAssert.eq(0, callbacks.get(), "matching legacy structure describe is never called locally");
            try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
                SimpleAssert.that(
                    StructureSources.resolve(new StringBuilder(), signal) == null,
                    "unrelated exact controller class is not blocked by a legacy source");
            }
            StructureSources.resolve(controller, signal);
            SimpleAssert.eq(1, callbacks.get(), "explicit source resolution retains caller-thread contract");
            SimpleAssert.eq(1, signal.stackSize, "source callback receives an independent trigger");
            HologramWorkBudget.startServerTick();
            HologramWorkBudget.endServerTick();
            try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
                StructureSources.resolve(controller, signal);
            }
            SimpleAssert.eq(2, callbacks.get(), "verified owner scope executes legacy declaration callback");
        }
        SimpleAssert.that(!StructureSources.hasSource(Object.class), "legacy source handle removes its exact entry");
        try (GTMBExtensions.Registration safe = StructureSources
            .register("gtmb_test:safe-source", Object.class, (value, trigger) -> {
                callbacks.incrementAndGet();
                return null;
            }, true)) {
            try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
                StructureSources.resolve(controller, signal);
            }
            SimpleAssert.eq(3, callbacks.get(), "client-safe source opts into detached game-thread declarations");
            try (HologramExtensionExecution.Scope io = HologramExtensionExecution.builtinLibraryIo()) {
                StructureSources.resolve(controller, signal);
                throw new AssertionError("client-safe source incorrectly ran on builtin IO");
            } catch (HologramExtensionExecution.ReviewRequired expected) {}
            SimpleAssert.eq(3, callbacks.get(), "client-safe declaration does not imply IO-safe callback");
        }
        SimpleAssert.that(!HologramExtensionExecution.localAuthorActive(), "source rejection restores local scope");
    }

    private static void snapshotsAndAdmission() throws Exception {
        HologramPresets.Preset source = source();
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        AtomicInteger calls = new AtomicInteger();
        Thread owner = Thread.currentThread();
        try (GTMBExtensions.Registration snapshot = BlueprintSnapshots
            .register("gtmb_test:owner-review", BlueprintSnapshots.Stage.AUTHORING_COMPLETE, value -> {
                SimpleAssert.that(Thread.currentThread() == owner, "automatic admission callback runs on owner");
                calls.incrementAndGet();
                NBTTagCompound report = new NBTTagCompound();
                report.setString("proof", "fresh");
                return report;
            })) {
            HologramPresets.Preset local = HologramBlueprintProcessing.processDetached(source, policy, 0, 0, 0, 0);
            SimpleAssert.eq(0, calls.get(), "detached processing never invokes automatic legacy snapshot callback");
            NBTTagCompound receipt = HologramBlueprintNbt.decode(local.processingNbt);
            SimpleAssert.that(receipt.getBoolean("extensionReviewPending"), "local receipt explicitly awaits review");
            NBTTagCompound forged = new NBTTagCompound();
            forged.setString("forged", "untrusted");
            receipt.setTag("extensions", forged);
            local.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
            String exactBefore = new Gson().toJson(local);
            HologramWorkBudget.startServerTick();
            HologramWorkBudget.endServerTick();
            HologramPresets.Preset admitted = HologramAuthorServerReview.admit(local);
            SimpleAssert.eq(1, calls.get(), "final owner admission invokes one completion callback");
            NBTTagCompound accepted = HologramBlueprintNbt.decode(admitted.processingNbt);
            SimpleAssert.that(!accepted.getBoolean("extensionReviewPending"), "server clears pending metadata");
            SimpleAssert.that(
                !accepted.getCompoundTag("extensions")
                    .hasKey("forged"),
                "forged reports replaced");
            SimpleAssert.eq(
                "fresh",
                accepted.getCompoundTag("extensions")
                    .getCompoundTag("gtmb_test:owner-review")
                    .getString("proof"),
                "fresh namespaced report retained");
            SimpleAssert.eq(exactBefore, new Gson().toJson(local), "admission does not mutate source");
            BlueprintSnapshots.inspect(
                new BlueprintSnapshots.Snapshot(
                    BlueprintSnapshots.Stage.AUTHORING_COMPLETE,
                    "external",
                    Collections.emptyList()));
            SimpleAssert.eq(2, calls.get(), "direct snapshot inspection remains synchronous caller-thread");
            try (GTMBExtensions.Registration reject = BlueprintSnapshots.register(
                "gtmb_test:reject-review",
                BlueprintSnapshots.Stage.AUTHORING_COMPLETE,
                value -> { throw new IOException("reject"); })) {
                try {
                    HologramAuthorServerReview.admit(local);
                    throw new AssertionError("server rejection ignored");
                } catch (IOException expected) {}
                SimpleAssert.eq(exactBefore, new Gson().toJson(local), "server rejection is atomic");
            }
            HologramPresets.Preset malformed = HologramPresets.copy(local);
            NBTTagCompound tank = new NBTTagCompound(), payload = new NBTTagCompound();
            tank.setTag("#1", new NBTTagCompound());
            payload.setTag("tank", tank);
            malformed.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
            int beforeMalformed = calls.get();
            try {
                HologramAuthorServerReview.admit(malformed);
                throw new AssertionError("malformed retained resource admitted");
            } catch (IOException expected) {}
            SimpleAssert
                .eq(beforeMalformed, calls.get(), "strict retained resource rejection precedes snapshot callback");
            final Throwable[] failure = new Throwable[1];
            Thread worker = new Thread(() -> {
                try {
                    HologramAuthorServerReview.admit(local);
                } catch (Throwable expected) {
                    failure[0] = expected;
                }
            });
            worker.start();
            worker.join();
            SimpleAssert.that(failure[0] instanceof IOException, "worker cannot impersonate owner review");
        }
    }

    private static void settledInspection() throws Exception {
        HologramPresets.Preset source = source();
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("id", "extension_fixture");
        payload.setInteger("custom", 7);
        source.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        com.miaokatze.gtmb.blueprint.editor.AuthorDocument document = com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO
            .readExpanded(
                new Gson().toJsonTree(source)
                    .getAsJsonObject(),
                com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO.Limits.defaults());
        com.miaokatze.gtmb.blueprint.editor.AuthorEditor editor = new com.miaokatze.gtmb.blueprint.editor.AuthorEditor(
            document,
            8);
        editor.select(Collections.singleton(1L));
        editor.move(2, 0, 0);
        editor.copy(3, 0, 0);
        com.miaokatze.gtmb.blueprint.editor.AuthorDocument frozen = editor.current();
        String before = frozen.sourceCopy()
            .toString();
        NBTTagCompound policy = HologramBlueprintProcessing.defaults("self", 0)
            .write();
        AtomicInteger callbacks = new AtomicInteger();
        try (GTMBExtensions.Registration legacy = NativePlacement
            .register("gtmb_test:inspection-native", adapter(callbacks))) {
            HologramWorkBudget.startServerTick();
            HologramWorkBudget.endServerTick();
            HologramAuthorServerReview.Result reviewed = HologramAuthorServerReview.review(frozen, "inspect", policy);
            NBTTagCompound expected;
            try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
                JsonObject settled = HologramAuthorPortableCompiler.settle(frozen, policy, 0, 0, 0, 0);
                expected = HologramLocalAuthorSemantics.processingState(settled, policy, 0, 0, 0, 0);
            }
            SimpleAssert
                .eq(expected, reviewed.report, "server inspection uses the same settled geometry as local flow");
            SimpleAssert.that(callbacks.get() > 0, "explicit inspection executes registered legacy native provider");
            SimpleAssert.eq(
                before,
                frozen.sourceCopy()
                    .toString(),
                "inspection preserves frozen source");
            SimpleAssert.that(frozen.hasPendingObligations(), "inspection never resolves author obligations");
        }
    }

    private static void builtinAdmissionFastPath() throws Exception {
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset candidate = HologramBlueprintProcessing.processDetached(source(), policy, 0, 0, 0, 0);
        HologramPresets.validate(candidate); // Mandatory authoritative precondition, not replaced by admission.
        NBTTagCompound receipt = HologramBlueprintNbt.decode(candidate.processingNbt);
        receipt.setTag("extensions", new NBTTagCompound());
        candidate.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
        Object cells = candidate.cells, cell = candidate.cells.get(0);
        HologramWorkBudget.startServerTick();
        HologramWorkBudget.endServerTick();
        SimpleAssert
            .that(!HologramAuthorServerReview.hasExternalCallbacks(), "builtins alone need no compatibility scan");
        SimpleAssert.that(
            HologramAuthorServerReview.admitValidatedOwned(candidate) == candidate,
            "validated owned default candidate does not receive another full copy");
        SimpleAssert.that(
            candidate.cells == cells && candidate.cells.get(0) == cell,
            "metadata-only admission never replaces or walks cell records");
        receipt = HologramBlueprintNbt.decode(candidate.processingNbt);
        SimpleAssert.that(
            !receipt.hasKey("extensions") && !receipt.hasKey("extensionReviewPending"),
            "default metadata-only path still strips untrusted extension metadata");
        HologramPresets.Preset invalid = HologramPresets.copy(candidate);
        invalid.cells.add(invalid.cells.get(0));
        try {
            HologramPresets.validate(invalid);
            HologramAuthorServerReview.admitValidatedOwned(invalid);
            throw new AssertionError("authoritative validation was bypassed by default admission");
        } catch (IOException expected) {}
        NBTTagCompound detail = HologramAuthorServerReview.reviewCell(
            new Gson().toJsonTree(candidate.cells.get(0))
                .getAsJsonObject(),
            new JsonObject(),
            0,
            0);
        SimpleAssert.eq(0, detail.getInteger("index"), "selected-cell helper works without any full source document");
    }

    private static HologramPresets.Preset source() {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.id = "extension_fixture";
        source.schema = 2;
        source.name = "extension fixture";
        source.controllerType = "area";
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        cell.block = "minecraft:stone";
        source.cells.add(cell);
        return source;
    }

    private interface Checked {

        Object run() throws Exception;
    }

    private static void rejectLocal(Checked action) throws Exception {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            action.run();
            throw new AssertionError("legacy callback was not deferred");
        } catch (HologramExtensionExecution.ReviewRequired expected) {}
    }
}
