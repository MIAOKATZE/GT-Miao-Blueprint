package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.BlueprintSnapshots.Cell;
import com.miaokatze.gtmb.api.BlueprintSnapshots.Snapshot;
import com.miaokatze.gtmb.api.BlueprintSnapshots.Stage;

/** Translation boundary: public processors receive detached data, never internal cells, sessions or world handles. */
final class HologramExtensionSnapshots {

    private HologramExtensionSnapshots() {}

    /**
     * Budgeted DTO conversion and worker list freezing; the documented processor callback remains on its owner thread.
     */
    static final class CaptureTask {

        private final List<Cell> cells = new ArrayList<>();
        private final String controller;
        private int cursor;
        private java.util.concurrent.FutureTask<Snapshot> result;

        CaptureTask(HologramCapture capture) {
            controller = capture.context == null ? ""
                : capture.context.getClass()
                    .getName();
        }

        boolean step(HologramCapture capture, int budget, long deadline) throws IOException {
            for (int work = 0; cursor < capture.cells.size() && work < budget; work++) {
                if (work > 0 && System.nanoTime() >= deadline) return false;
                HologramCapture.Cell cell = capture.cells.get(cursor++);
                cells.add(
                    new Cell(
                        cell.x,
                        cell.y,
                        cell.z,
                        cell.block == null ? "" : String.valueOf(Block.blockRegistry.getNameForObject(cell.block)),
                        cell.meta,
                        cell.role,
                        cell.unknown,
                        cell.anchor,
                        cell.excluded,
                        HologramBlueprintNbt.decode(cell.tileNbt),
                        HologramBlueprintNbt.decode(cell.itemNbt)));
            }
            if (cursor < capture.cells.size()) return false;
            if (result == null) {
                java.util.concurrent.FutureTask<Snapshot> candidate = new java.util.concurrent.FutureTask<>(
                    () -> new Snapshot(Stage.CAPTURE_COMPLETE, controller, cells));
                if (!HologramAreaPreparation.execute(candidate)) return false;
                result = candidate;
            }
            return result.isDone();
        }

        NBTTagCompound result() throws IOException {
            try {
                return BlueprintSnapshots.inspect(result.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                throw new IOException("Capture inspection interrupted", interrupted);
            } catch (java.util.concurrent.ExecutionException failed) {
                throw new IOException("Capture inspection failed", failed.getCause());
            }
        }

        void cancel() {
            if (result != null) HologramAreaPreparation.cancel(result);
        }
    }

    static NBTTagCompound capture(HologramCapture capture) throws IOException {
        if (!BlueprintSnapshots.hasProcessors(Stage.CAPTURE_COMPLETE)) return new NBTTagCompound();
        List<Cell> cells = new ArrayList<>();
        for (HologramCapture.Cell cell : capture.cells) cells.add(
            new Cell(
                cell.x,
                cell.y,
                cell.z,
                cell.block == null ? "" : String.valueOf(Block.blockRegistry.getNameForObject(cell.block)),
                cell.meta,
                cell.role,
                cell.unknown,
                cell.anchor,
                cell.excluded,
                HologramBlueprintNbt.decode(cell.tileNbt),
                HologramBlueprintNbt.decode(cell.itemNbt)));
        return BlueprintSnapshots.inspect(
            new Snapshot(
                Stage.CAPTURE_COMPLETE,
                capture.context == null ? ""
                    : capture.context.getClass()
                        .getName(),
                cells));
    }

    static NBTTagCompound authoring(HologramPresets.Preset preset) throws IOException {
        if (!BlueprintSnapshots.hasProcessors(Stage.AUTHORING_COMPLETE)) return new NBTTagCompound();
        List<Cell> cells = new ArrayList<>();
        for (HologramPresets.Cell cell : preset.cells) cells.add(
            new Cell(
                cell.x,
                cell.y,
                cell.z,
                cell.block,
                cell.metadata,
                "",
                false,
                cell.controller,
                cell.excluded,
                HologramBlueprintNbt.decode(cell.tileNbt),
                HologramBlueprintNbt.decode(cell.itemNbt)));
        return BlueprintSnapshots.inspect(new Snapshot(Stage.AUTHORING_COMPLETE, preset.controllerType, cells));
    }
}
