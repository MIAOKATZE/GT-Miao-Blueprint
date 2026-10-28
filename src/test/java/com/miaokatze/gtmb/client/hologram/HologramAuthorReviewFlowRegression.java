package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** A server extension report supplies evidence, never six silently confirmed user choices. */
public final class HologramAuthorReviewFlowRegression {

    private HologramAuthorReviewFlowRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("Author server review flow regression passed");
    }

    public static void run() {
        NBTTagCompound report = new NBTTagCompound();
        NBTTagCompound policy = new NBTTagCompound();
        NBTTagList confirmed = new NBTTagList();
        confirmed.appendTag(new NBTTagString("resources"));
        policy.setTag("confirmedStages", confirmed);
        String[] decisions = { "resourceDecisions", "coordinateOverrides", "cellNbtOverrides" };
        for (String key : decisions) {
            NBTTagList previous = new NBTTagList();
            previous.appendTag(new NBTTagCompound());
            policy.setTag(key, previous);
        }
        policy.setInteger("deidentificationLevel", 3);
        report.setTag("policy", policy);
        report.setBoolean("complete", true);
        report.setInteger("requiredStages", 63);
        NBTTagList resources = new NBTTagList();
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setString("codec", "fixture:server-only");
        legacy.setBoolean("canPreserve", true);
        resources.appendTag(legacy);
        report.setTag("resourceReview", resources);
        HologramLocalAuthorNativeActions.resetReview(report);
        check(!report.getBoolean("complete"), "Inspection cannot mark processing complete");
        check(
            policy.getTagList("confirmedStages", 8)
                .tagCount() == 0,
            "All stages need new user confirmation");
        for (String key : decisions) check(
            policy.getTagList(key, 10)
                .tagCount() == 0,
            "Old per-source decisions are discarded: " + key);
        check(policy.getInteger("deidentificationLevel") == 3, "Policy recommendations remain available");
        check(
            report.getTagList("resourceReview", 10)
                .tagCount() == 1,
            "Server resource evidence is not discarded");
        HologramProcessingFlow flow = new HologramProcessingFlow(policy, false, true);
        flow.requirements(report.getInteger("requiredStages"));
        for (int stage = 0; stage < 6; stage++) {
            check(flow.stage == stage && !flow.next(), "Review must await choice at stage " + stage);
            flow.choose();
            flow.next();
        }
        check(flow.complete(), "All six explicit choices complete the flow");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
