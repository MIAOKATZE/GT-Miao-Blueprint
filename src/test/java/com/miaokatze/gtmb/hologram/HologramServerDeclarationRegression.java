package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** A server-only synthetic entry is not a client bypass and never emits world-capture completion. */
final class HologramServerDeclarationRegression {

    private HologramServerDeclarationRegression() {}

    static void run() {
        int id = 32749;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        String name = Thread.currentThread()
            .getName();
        HologramWorkBudget.startServerTick();
        HologramWorkBudget.endServerTick();
        AtomicInteger sources = new AtomicInteger(), captures = new AtomicInteger();
        Thread owner = Thread.currentThread();
        HologramCapture synthetic = null, actual = null;
        try {
            Thread.currentThread()
                .setName("Server thread");
            HologramDynamicDefinitionRegression.DynamicController prototype = new HologramDynamicDefinitionRegression.DynamicController();
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = prototype;
            HologramDynamicDefinitionRegression.DynamicController machine = (HologramDynamicDefinitionRegression.DynamicController) HologramDetachedController
                .create(id, ExtendedFacing.DEFAULT);
            ItemStack trigger = new ItemStack(Blocks.stone);
            try (GTMBExtensions.Registration source = StructureSources.register(
                "gtmb_test:server-declaration-source",
                HologramDynamicDefinitionRegression.DynamicController.class,
                (controller, signal) -> {
                    SimpleAssert.that(Thread.currentThread() == owner, "legacy source runs only on server owner");
                    sources.incrementAndGet();
                    return new StructureSources.Plan(
                        StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
                            .addShape("main", new String[][] { { "AA" } })
                            .addElement('A', StructureUtility.ofBlock(Blocks.stone, 0))
                            .build(),
                        Collections.singletonList(new StructureSources.Piece("main", 0, 0, 0, signal)));
                });
                GTMBExtensions.Registration observer = BlueprintSnapshots.register(
                    "gtmb_test:server-declaration-completion",
                    BlueprintSnapshots.Stage.CAPTURE_COMPLETE,
                    snapshot -> {
                        captures.incrementAndGet();
                        return null;
                    })) {
                try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
                    try {
                        HologramCapture.collectServerDeclaration(
                            machine,
                            trigger,
                            machine.getExtendedFacing(),
                            null,
                            System.nanoTime() + 100_000_000L);
                        throw new AssertionError("server entry bypassed local author scope");
                    } catch (IllegalStateException expected) {}
                }
                FutureTask<Boolean> foreign = new FutureTask<>(() -> {
                    try {
                        HologramCapture.collectServerDeclaration(
                            machine,
                            trigger,
                            machine.getExtendedFacing(),
                            null,
                            System.nanoTime() + 100_000_000L);
                        return false;
                    } catch (IllegalStateException expected) {
                        return true;
                    }
                });
                Thread thread = new Thread(foreign, "gtmb-declaration-owner-probe");
                thread.setDaemon(true);
                thread.start();
                try {
                    SimpleAssert.that(foreign.get(5, TimeUnit.SECONDS), "foreign thread cannot use server entry");
                } finally {
                    foreign.cancel(true);
                    thread.join(5000);
                }
                SimpleAssert.eq(0, sources.get(), "wrong scopes reject before legacy describe");
                long deadline = System.nanoTime() + 5_000_000_000L;
                do {
                    synthetic = HologramCapture.collectServerDeclaration(
                        machine,
                        trigger,
                        machine.getExtendedFacing(),
                        synthetic,
                        System.nanoTime() + 2_000_000L);
                } while (synthetic.pending && System.nanoTime() < deadline);
                SimpleAssert.that(!synthetic.pending && !synthetic.incomplete, "server synthetic capture finishes");
                SimpleAssert.eq(1, sources.get(), "synthetic continuations retain the legacy source graph");
                SimpleAssert.eq(0, captures.get(), "server synthetic capture never emits CAPTURE_COMPLETE");
                deadline = System.nanoTime() + 5_000_000_000L;
                do {
                    actual = HologramCapture.collect(machine, trigger, machine.getExtendedFacing(), actual);
                } while (actual.pending && System.nanoTime() < deadline);
                SimpleAssert.that(!actual.pending && !actual.incomplete, "ordinary capture still finishes");
                SimpleAssert.eq(1, captures.get(), "ordinary capture completion contract remains intact");
            }
        } catch (Exception failure) {
            throw new AssertionError("server synthetic declaration boundary", failure);
        } finally {
            if (synthetic != null) synthetic.cancelPending();
            if (actual != null) actual.cancelPending();
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
            Thread.currentThread()
                .setName(name);
        }
    }
}
