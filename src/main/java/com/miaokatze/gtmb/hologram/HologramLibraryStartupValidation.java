package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One exclusively owned persisted DTO. Native passes yield; detached data passes stay on library IO. */
final class HologramLibraryStartupValidation implements AutoCloseable {

    private final HologramPresets.Preset source;
    private final BooleanSupplier current;
    private HologramPreparedBlueprintValidation validation;
    private HologramAePolicy.Sanitation sanitation;
    private HologramPresets.Preset result;
    private Thread owner;
    private int kind, cursor;
    private long nativeGeneration, codecGeneration;
    // A volatile phase transfers exclusive DTO ownership between heartbeat and the sole library worker.
    private volatile int phase;
    private volatile boolean cancelled;

    HologramLibraryStartupValidation(HologramPresets.Preset source, BooleanSupplier current) {
        this.source = source;
        this.current = current;
    }

    void advanceOwner() throws IOException {
        checkCurrent();
        if (!HologramWorkBudget.isServerOwnerThread())
            throw new IllegalStateException("Library startup validation requires the server owner thread");
        if (owner == null) owner = Thread.currentThread();
        if (owner != Thread.currentThread()) throw new IllegalStateException("Library startup owner changed");
        try (AutoCloseable scope = HologramExtensionExecution.serverReview()) {
            long deadline = System.nanoTime() + 2_000_000L;
            for (int work = 0; work < 128 && (work == 0 || System.nanoTime() < deadline); work++) {
                checkCurrent();
                if (phase == 0) {
                    nativeGeneration = HologramNativePlacement.generation();
                    codecGeneration = com.miaokatze.gtmb.api.ResourceContainers.generation();
                    if (source.schema == 2) validation = new HologramPreparedBlueprintValidation(source);
                    else {
                        // Legacy schema-1 has no staged validator. Preserve its complete per-entry contract;
                        // neither this pass nor an individual third-party callback promises bounded latency.
                        HologramPresets.validate(source);
                    }
                    phase = 1;
                } else if (phase == 1) {
                    if (validation != null && !validation.done()) validation.advance();
                    else phase = 2;
                } else if (phase == 2) {
                    List<HologramPresets.Cell> cells = kind == 0 ? source.cells : source.palette;
                    if (cursor < cells.size()) {
                        HologramPresets.Cell cell = cells.get(cursor++);
                        HologramServerLibrary.rejectDropPayload(HologramBlueprintNbt.decode(cell.tileNbt));
                        HologramServerLibrary.rejectDropPayload(HologramBlueprintNbt.decode(cell.itemNbt));
                    } else if (kind++ == 0) cursor = 0;
                    else {
                        sanitation = new HologramAePolicy.Sanitation(
                            source,
                            Collections.emptyMap(),
                            Collections.emptyMap(),
                            (step, done, total) -> checkCurrent());
                        phase = 3;
                    }
                } else if (phase == 3) {
                    if (!sanitation.identityDone()) sanitation.identity();
                    else {
                        phase = 4;
                        return;
                    }
                } else if (phase == 5) {
                    if (!sanitation.aeDone()) sanitation.ae();
                    else {
                        phase = 6;
                        return;
                    }
                } else return;
            }
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Library startup validation failed", failure);
        }
    }

    void advanceData() throws IOException {
        checkCurrent();
        if (phase == 4) {
            sanitation.prepareData();
            checkCurrent();
            phase = 5;
        } else if (phase == 6) {
            HologramAePolicy.Draft draft = sanitation.finishData();
            if (draft.blocking)
                throw new IOException("Blueprint contains unresolved AE payloads; repair before upload");
            checkCurrent();
            result = draft.preset;
            phase = 7;
        }
    }

    boolean done() {
        return phase == 7;
    }

    HologramPresets.Preset result() throws IOException {
        checkCurrent();
        if (!done()) throw new IOException("Library startup validation is incomplete");
        return result;
    }

    private void checkCurrent() throws IOException {
        if (cancelled || !current.getAsBoolean()) throw new IOException("Library startup was retired");
        if (phase != 0 && (nativeGeneration != HologramNativePlacement.generation()
            || codecGeneration != com.miaokatze.gtmb.api.ResourceContainers.generation()))
            throw new IOException(
                "Native or resource descriptors changed during library startup; reload before using it");
    }

    /** Heartbeat/stop serialize the owner pass; a running data pass retires at its progress checkpoint. */
    @Override
    public void close() {
        cancelled = true;
        if (validation != null) validation.close();
    }
}
