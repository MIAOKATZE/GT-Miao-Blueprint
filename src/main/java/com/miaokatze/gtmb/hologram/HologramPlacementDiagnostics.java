package com.miaokatze.gtmb.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Bounded server observations of the existing transaction; never serializes device or player payloads. */
final class HologramPlacementDiagnostics {

    private HologramPlacementDiagnostics() {}

    static final class Limits {

        private final Map<String, Integer> counts = new LinkedHashMap<>();

        boolean admit(String kind) {
            Integer count = counts.get(kind);
            if (count == null && counts.size() >= 16 || count != null && count >= 3) return false;
            counts.put(kind, count == null ? 1 : count + 1);
            return true;
        }

        void clear() {
            counts.clear();
        }
    }

    static void failure(HologramSession s, HologramCapture.Cell cell, int index, String stage, Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++)
            cause = cause.getCause();
        String kind = cause.getClass()
            .getName();
        if (!s.placementDiagnostics.admit(kind)) return;
        StackTraceElement[] trace = cause.getStackTrace();
        // Third-party exception messages can contain inventory/owner data. Record only type and code location.
        observe(s, cell, index, stage, kind + (trace.length == 0 ? "" : " at " + trace[0]));
    }

    static void rejected(HologramSession s, HologramCapture.Cell cell, int index, String result, boolean cancelled,
        boolean reservationUsed) {
        if (!s.placementDiagnostics.admit("native-result:" + result + ":" + cancelled)) return;
        observe(s, cell, index, "native-result", result + ", cancelled=" + cancelled + ", reserved=" + reservationUsed);
    }

    private static void observe(HologramSession s, HologramCapture.Cell cell, int index, String stage, String failure) {
        try {
            Block actual = s.player.worldObj.getBlock(cell.x, cell.y, cell.z);
            int metadata = s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z);
            TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            int machineId = tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getMetaTileID() : -1;
            GTMiaoBlueprint.LOG.warn(
                "Hologram placement diagnostic stage={} index={} pos={},{},{} actual={}:{} machineId={} failure={}"
                    + " (at most 3 observations per failure kind and 16 kinds per job)",
                stage,
                index,
                cell.x,
                cell.y,
                cell.z,
                Block.blockRegistry.getNameForObject(actual),
                metadata,
                machineId,
                failure);
        } catch (RuntimeException | LinkageError unavailable) {
            // Diagnostic observations must never interrupt the authoritative refund/rollback path.
            GTMiaoBlueprint.LOG.warn(
                "Hologram placement diagnostic stage={} index={} world observation unavailable; failure={}",
                stage,
                index,
                failure);
        }
    }
}
