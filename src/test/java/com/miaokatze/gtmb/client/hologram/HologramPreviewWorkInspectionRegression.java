package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Scalar observation-window contracts only; no native rendering or performance acceptance claim. */
public final class HologramPreviewWorkInspectionRegression {

    private HologramPreviewWorkInspectionRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramPreviewWorkInspectionRegression.class, args)) return;
        run();
        System.out.println("HologramPreviewWorkInspectionRegression PASS");
    }

    public static void run() {
        HologramRenderer.release();
        long epoch = HologramRenderer.previewWorkInspection()
            .getLong("releaseEpoch");
        try {
            HologramRenderer.recordPreviewModelPublication(3);
            HologramRenderer.recordPreviewModelPublication(2);
            HologramRenderer.recordPreviewNativeWake(2);
            HologramRenderer.recordPreviewCellWork(false);
            HologramRenderer.recordPreviewCellWork(false);
            HologramRenderer.recordPreviewCellWork(true);
            HologramRenderer.recordPreviewCursorReset(2, 1);
            HologramRenderer.recordPreviewCursorReset(0, 0);
            NBTTagCompound first = HologramRenderer.previewWorkInspection();
            SimpleAssert.eq(epoch, first.getLong("releaseEpoch"), "events do not change the observation epoch");
            check(first, "modelPublications", 2);
            check(first, "modelPublicationDirtyRegions", 5);
            check(first, "nativeWakes", 1);
            check(first, "nativeWakeDirtyRegions", 2);
            check(first, "cheapVisitCalls", 2);
            check(first, "detailVisitCalls", 1);
            check(first, "cursorResets", 2);
            check(first, "discardedCheapVisits", 2);
            check(first, "discardedDetailVisits", 1);
            first.setLong("releaseEpoch", -1);
            first.setLong("cheapVisitCalls", -1);
            NBTTagCompound second = HologramRenderer.previewWorkInspection();
            SimpleAssert.that(first != second, "inspection returns a detached scalar snapshot");
            check(second, "releaseEpoch", epoch);
            check(second, "cheapVisitCalls", 2);
            check(second, "frames", 0);
        } finally {
            HologramRenderer.release();
        }
        NBTTagCompound reset = HologramRenderer.previewWorkInspection();
        check(reset, "releaseEpoch", epoch + 1);
        for (String key : new String[] { "modelPublications", "modelPublicationDirtyRegions", "nativeWakes",
            "nativeWakeDirtyRegions", "cheapVisitCalls", "detailVisitCalls", "cursorResets", "discardedCheapVisits",
            "discardedDetailVisits", "frames", "preparationNanos", "projectionNanos", "drawNanos" }) {
            check(reset, key, 0);
        }
    }

    private static void check(NBTTagCompound value, String key, long expected) {
        SimpleAssert.eq(expected, value.getLong(key), "scalar inspection " + key);
    }
}
