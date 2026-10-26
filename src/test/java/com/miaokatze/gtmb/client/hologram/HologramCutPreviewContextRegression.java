package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** A local moving sketch cannot serve as a placement receipt, nor can a stale origin/pose acknowledgement. */
public final class HologramCutPreviewContextRegression {

    private HologramCutPreviewContextRegression() {}

    public static void run() {
        HologramCutPreviewContext context = new HologramCutPreviewContext();
        NBTTagCompound local = request("moving");
        context.bind(local, "local", null);
        SimpleAssert.that(context.accept(local, "local", null), "moving uses an explicit local sketch receipt");
        SimpleAssert.that(
            !context.placementMatches(new int[] { 10, 64, 20 }),
            "a moving sketch never authorizes confirmation at an arbitrary target");

        int[] locked = { 10, 64, 20 };
        NBTTagCompound placement = request("locked");
        context.bind(placement, "placement", locked);
        SimpleAssert.that(!context.placementMatches(locked), "a pending placement request cannot enable confirmation");
        NBTTagCompound derived = new NBTTagCompound();
        derived.setBoolean("transformTargetDependent", true);
        placement.setTag("preview", derived);
        SimpleAssert.that(
            !context.accept(local, "placement", locked),
            "a delayed local response cannot replace the locked target payload");
        NBTTagCompound wrongPurpose = (NBTTagCompound) placement.copy();
        wrongPurpose.setString("previewPurpose", "storage");
        SimpleAssert
            .that(!context.accept(wrongPurpose, "placement", locked), "storage purpose cannot become placement");
        NBTTagCompound wrongGeneration = (NBTTagCompound) placement.copy();
        wrongGeneration.setLong("previewGeneration", placement.getLong("previewGeneration") - 1);
        SimpleAssert.that(!context.accept(wrongGeneration, "placement", locked), "old pose generation is rejected");
        NBTTagCompound wrongOrigin = (NBTTagCompound) placement.copy();
        wrongOrigin.setInteger("originX", 11);
        SimpleAssert.that(!context.accept(wrongOrigin, "placement", locked), "a different server origin is rejected");
        SimpleAssert.that(
            !context.accept(placement, "placement", new int[] { 11, 64, 20 }),
            "changing the locked target invalidates a response in flight");
        SimpleAssert.that(context.accept(placement, "placement", locked), "the exact locked receipt is accepted");
        SimpleAssert.that(context.placementMatches(locked), "only the accepted origin enables confirmation");
        locked[0]++;
        SimpleAssert.that(!context.placementMatches(locked), "nudge cannot reuse a previously accepted origin");
        SimpleAssert.that(!context.moveTo(locked), "target-dependent payloads require a refreshed origin receipt");
        context.invalidate();
        SimpleAssert.that(
            !context.accept(placement, "placement", new int[] { 10, 64, 20 }),
            "exiting or changing pose retires the placement receipt even if the player returns to its old origin");
        SimpleAssert
            .that(!context.placementMatches(new int[] { 10, 64, 20 }), "retirement also clears accepted payload");
        NBTTagCompound independent = request("independent");
        context.bind(independent, "placement", locked);
        independent.setTag("preview", new NBTTagCompound());
        SimpleAssert
            .that(context.accept(independent, "placement", locked), "an exact origin may accept unknown dependency");
        SimpleAssert.that(
            !context.moveTo(new int[] { 12, 64, 20 }),
            "missing dependency classification never permits a local origin change");
        independent.setBoolean("previewTargetIndependent", true);
        SimpleAssert
            .that(context.accept(independent, "placement", locked), "origin-invariant payload is confirmed once");
        int[] nudged = { locked[0] + 1, locked[1], locked[2] };
        SimpleAssert.that(
            context.moveTo(nudged) && context.placementMatches(nudged),
            "origin-invariant large structures nudge locally without requesting another full payload");
    }

    private static NBTTagCompound request(String id) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("requestId", id);
        return request;
    }
}
