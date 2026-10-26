package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;

/** Moving sketches are local; a placement receipt belongs to one request, pose generation and locked origin. */
final class HologramCutPreviewContext {

    private long generation;
    private String request = "", purpose = "";
    private int[] requestedOrigin, acceptedOrigin;
    private boolean independent;

    void bind(NBTTagCompound action, String purpose, int[] origin) {
        invalidate();
        request = action.getString("requestId");
        this.purpose = purpose;
        requestedOrigin = origin == null ? null : origin.clone();
        action.setString("previewPurpose", purpose);
        action.setLong("previewGeneration", generation);
        if (origin != null) {
            action.setInteger("originX", origin[0]);
            action.setInteger("originY", origin[1]);
            action.setInteger("originZ", origin[2]);
        }
    }

    boolean accept(NBTTagCompound reply, String expectedPurpose, int[] currentOrigin) {
        if (!request.equals(reply.getString("requestId")) || !purpose.equals(expectedPurpose)
            || !purpose.equals(reply.getString("previewPurpose"))
            || generation != reply.getLong("previewGeneration")) return false;
        if ("placement".equals(purpose)) {
            if (!reply.hasKey("preview", 10)) return false;
            for (String key : new String[] { "originX", "originY", "originZ" }) if (!reply.hasKey(key, 3)) return false;
            int[] received = { reply.getInteger("originX"), reply.getInteger("originY"), reply.getInteger("originZ") };
            if (!Arrays.equals(requestedOrigin, currentOrigin) || !Arrays.equals(received, currentOrigin)) return false;
            acceptedOrigin = currentOrigin.clone();
            independent = reply.hasKey("previewTargetIndependent", 1) && reply.getBoolean("previewTargetIndependent");
        }
        return true;
    }

    boolean placementMatches(int[] origin) {
        return origin != null && Arrays.equals(acceptedOrigin, origin);
    }

    /** A server-confirmed origin-invariant payload can move locally; target-aware payloads need a new receipt. */
    boolean moveTo(int[] origin) {
        if (!independent || acceptedOrigin == null || origin == null) return false;
        acceptedOrigin = origin.clone();
        return true;
    }

    void invalidate() {
        generation++;
        request = "";
        purpose = "";
        requestedOrigin = null;
        acceptedOrigin = null;
        independent = false;
    }
}
