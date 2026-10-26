package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Stable owner-side identities; no source stamp is treated as a dynamic world DTO certificate. */
public final class HologramTransportPageLayoutRegression {

    public static void run() {
        HologramTransportPageLayout.clear();
        try {
            stableCapturePages();
            positiveConflicts();
            sourceDomainBinding();
            leaseTransferAndCancel();
        } catch (IOException failure) {
            throw new AssertionError("transport layout source fixture rejected", failure);
        } finally {
            HologramTransportPageLayout.clear();
        }
    }

    private static void stableCapturePages() {
        HologramCapture capture = capture(600, 0, 64, 0);
        HologramTransportPageLayout.Row original;
        try (HologramTransportPageLayout layout = layout("capture", capture, null, 0, 64, 0)) {
            original = layout.row(256);
            SimpleAssert.eq(600, layout.size(), "all complete snapshot rows have a stable transport identity");
            SimpleAssert.eq(257L, original.cellId, "unbound world capture acquires monotonic CellIds");
            SimpleAssert.eq(1L, original.pageId, "logical pages use stable CellIds and 256 records");
            SimpleAssert.that(
                original.report == null && original.mesh == null,
                "world capture never manufactures a P1 source domain certificate");
        }
        capture.cells.remove(0);
        try (HologramTransportPageLayout layout = layout("capture", capture, null, 0, 64, 0)) {
            HologramTransportPageLayout.Row shifted = layout.row(255);
            SimpleAssert
                .eq(original.cellId, shifted.cellId, "deleting the first cell preserves later stable identities");
            SimpleAssert.eq(original.pageId, shifted.pageId, "deleting the first cell never moves all following pages");
            SimpleAssert
                .eq(original.authority, shifted.authority, "capture authority is independent of flat row index");
        }
        try (HologramTransportPageLayout replacement = layout("capture", capture(599, 1, 64, 0), null, 0, 64, 0)) {
            SimpleAssert.that(
                original.authority != replacement.row(255).authority,
                "a new capture with equal coordinates gets a distinct fallback authority");
        }
    }

    private static void positiveConflicts() {
        HologramCapture capture = capture(2, 0, 64, 0);
        capture.cells.get(0).segmentCellId = capture.cells.get(1).segmentCellId = 7;
        long fallback;
        long authority;
        try (HologramTransportPageLayout layout = layout("conflict", capture, null, 0, 64, 0)) {
            SimpleAssert.eq(7L, layout.row(0).cellId, "first positive identity remains bound");
            fallback = layout.row(1).cellId;
            authority = layout.row(1).authority;
            SimpleAssert.that(
                fallback < 0 && layout.row(1).pageId < 0,
                "positive conflicts use a distinct negative identity/page namespace");
            SimpleAssert.eq(
                7L,
                capture.cells.get(1).segmentCellId,
                "transport never rewrites an existing positive P1 identity to repair a conflict");
        }
        try (HologramTransportPageLayout layout = layout("conflict", capture, null, 0, 64, 0)) {
            SimpleAssert
                .eq(fallback, layout.row(1).cellId, "same conflicting capture cell keeps its fallback identity");
            SimpleAssert
                .eq(authority, layout.row(1).authority, "same fallback namespace survives subsequent snapshots");
        }
        capture.cells.remove(0);
        try (HologramTransportPageLayout layout = layout("conflict", capture, null, 0, 64, 0)) {
            SimpleAssert.eq(
                fallback,
                layout.row(0).cellId,
                "deleting a conflicting predecessor never renumbers the surviving fallback identity");
        }
    }

    private static void sourceDomainBinding() throws IOException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = "transport-layout";
        preset.name = "Transport layout";
        for (int index = 0; index < 300; index++) {
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.item = "minecraft:stone";
            cell.block = "minecraft:stone";
            cell.x = index;
            preset.cells.add(cell);
        }
        HologramPresets.validate(preset);
        HologramCapture capture = capture(300, 10, 64, 20);
        for (int index = 0; index < 300; index++)
            capture.cells.get(index).segmentCellId = preset.cells.get(index).segmentCellId;
        try (HologramTransportPageLayout layout = layout("source", capture, preset.segmentDocument, 10, 64, 20)) {
            HologramTransportPageLayout.Row row = layout.row(256);
            SimpleAssert.that(
                row.report != null && row.mesh != null,
                "matching authoritative CellId and relative source position bind actual P1 domain inputs");
            SimpleAssert.that(
                row.report.sameInput(
                    preset.segmentDocument.pages.get(1L)
                        .stamp(HologramSegmentContract.Domain.REPORT)),
                "report input stamp is the P1 domain stamp");
            SimpleAssert.that(
                row.mesh.sameInput(
                    preset.segmentDocument.pages.get(1L)
                        .stamp(HologramSegmentContract.Domain.MESH)),
                "mesh input stamp is the P1 domain stamp");
        }
        HologramCapture moved = capture(300, 11, 64, 20);
        for (int index = 0; index < 300; index++)
            moved.cells.get(index).segmentCellId = preset.cells.get(index).segmentCellId;
        try (HologramTransportPageLayout layout = layout("source", moved, preset.segmentDocument, 10, 64, 20)) {
            SimpleAssert.that(
                layout.row(0).report == null && layout.row(0).mesh == null,
                "a positive ID at a different source position never acquires the stale page input stamp");
        }
    }

    private static void leaseTransferAndCancel() {
        HologramTransportPageLayout.clear();
        long before = HologramProcessGovernor.retainedBytes();
        HologramCapture capture = capture(1, 0, 0, 0);
        HologramTransportPageLayout.Builder builder = new HologramTransportPageLayout.Builder(
            "lease",
            capture,
            0,
            0,
            0,
            null,
            1);
        builder.add(0, row(capture.cells.get(0)));
        HologramTransportPageLayout layout = builder.finish();
        builder.close();
        HologramTransportPageLayout.clear();
        SimpleAssert.that(
            HologramProcessGovernor.retainedBytes() > before,
            "completed layout retains its own metadata lease after builder and identity-cache release");
        layout.close();
        layout.close();
        SimpleAssert.eq(before, HologramProcessGovernor.retainedBytes(), "layout output lease releases exactly once");
        try (HologramTransportPageLayout.Builder cancelled = new HologramTransportPageLayout.Builder(
            "cancel",
            capture,
            0,
            0,
            0,
            null,
            1)) {
            // Cancellation before the first row must release reserved metadata without publishing a layout.
        }
        HologramTransportPageLayout.clear();
        SimpleAssert
            .eq(before, HologramProcessGovernor.retainedBytes(), "cancelled preparation leaks no metadata lease");
    }

    private static HologramTransportPageLayout layout(String session, HologramCapture capture,
        HologramSegmentDocument document, int x, int y, int z) {
        try (HologramTransportPageLayout.Builder builder = new HologramTransportPageLayout.Builder(
            session,
            capture,
            x,
            y,
            z,
            document,
            capture.cells.size())) {
            for (int index = 0; index < capture.cells.size(); index++)
                builder.add(index, row(capture.cells.get(index)));
            return builder.finish();
        }
    }

    private static HologramCapture capture(int count, int x, int y, int z) {
        HologramCapture capture = new HologramCapture(null, null);
        for (int index = 0; index < count; index++) capture.cells.add(new HologramCapture.Cell(null, x + index, y, z));
        return capture;
    }

    private static NBTTagCompound row(HologramCapture.Cell cell) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", cell.x);
        row.setInteger("y", cell.y);
        row.setInteger("z", cell.z);
        return row;
    }

    private HologramTransportPageLayoutRegression() {}
}
