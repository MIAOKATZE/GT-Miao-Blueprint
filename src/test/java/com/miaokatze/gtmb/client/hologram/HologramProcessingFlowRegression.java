package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** The client can recommend choices, but cannot silently confirm a processing stage. */
public final class HologramProcessingFlowRegression {

    private HologramProcessingFlowRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("Processing flow choice regression passed");
    }

    public static void run() {
        explicitChoiceGuidance();
        nativeSaveStages();
        HologramProcessingFlow optional = new HologramProcessingFlow(new NBTTagCompound());
        optional.requirements((1 << 4) | (1 << 5));
        check(optional.optional() && optional.chosen(), "An empty preset stage permits Next without choosing a preset");
        optional.next();
        check(optional.stage == 4 && !optional.chosen(), "Next skips absent identity, quantum and coordinate stages");
        check(!optional.next(), "Actual NBT still needs an explicit decision");
        optional.choose();
        optional.next();
        check(optional.stage == 5 && !optional.complete(), "Hints remain reviewable after the NBT stage");
        optional.choose();
        optional.next();
        check(optional.complete(), "Skipped empty stages retain a canonical six-stage receipt");
        optional.fullReview(true);
        check(
            optional.fullReview() && optional.requiredStages() == ((1 << 4) | (1 << 5))
                && optional.stage == 0
                && !optional.chosen()
                && !optional.complete(),
            "Full review preserves server requirements and restarts explicit confirmation");
        for (int stage = 0; stage < 6; stage++) {
            check(
                optional.stage == stage && !optional.optional() && !optional.next(),
                "Full review never skips or implicitly confirms stage " + stage);
            optional.choose();
            optional.next();
        }
        check(optional.complete(), "Full review retains all six explicit confirmations");
        optional.fullReview(false);
        check(
            optional.stage == 0 && optional.optional() && !optional.complete(),
            "Returning to adaptive processing restarts with the actual server requirements");
        NBTTagCompound saved = new NBTTagCompound();
        saved.setInteger("deidentificationLevel", 4);
        saved.setString("autoHints", "none");
        HologramProcessingFlow flow = new HologramProcessingFlow(saved);
        check(!flow.chosen() && !flow.next() && flow.stage == 0, "Opening a policy cannot advance it");
        flow.tendency(true);
        check(
            "rebind".equals(
                flow.values()
                    .getString("quantum")),
            "Sharing preserves groups for a selected target-network terminal");
        check(
            flow.chosen() && !flow.complete()
                && flow.policy()
                    .getTagList("confirmedStages", 8)
                    .tagCount() == 0,
            "Sharing offers valid choices without confirming stages");
        check(
            flow.values()
                .getInteger("deidentificationLevel") == 6 && "auto".equals(
                    flow.values()
                        .getString("coordinateDefault")),
            "Sharing recommends strong cleaning and preserves relation-specific decisions");
        flow.preset(saved);
        check(flow.chosen() && flow.stage == 0, "Applying a preset only confirms its own stage");
        check(saved.getInteger("deidentificationLevel") == 4, "Preset is copied before editing");
        flow.next();
        check(
            flow.stage == 1 && flow.chosen()
                && flow.policy()
                    .getTagList("confirmedStages", 8)
                    .tagCount() == 1,
            "Preset offers identity settings but still requires the next review step");
        flow.values()
            .setInteger("deidentificationLevel", 2);
        flow.choose();
        flow.tendency(false);
        check(
            flow.values()
                .getInteger("deidentificationLevel") == 2,
            "A chosen level survives a tendency change");
        flow.next();
        check(
            flow.chosen() && flow.stage == 2
                && flow.policy()
                    .getTagList("confirmedStages", 8)
                    .tagCount() == 2,
            "The quantum recommendation remains unconfirmed before its Next click");
        flow.choose();
        flow.next();
        flow.coordinate("tile:/destination", "relative");
        flow.coordinate("tile:/destination", "clear");
        check(
            flow.values()
                .getTagList("coordinateOverrides", 10)
                .tagCount() == 1 && "clear".equals(flow.coordinate("tile:/destination")),
            "Coordinate edits replace one relation");
        flow.choose();
        flow.next();
        flow.toggle("ignoredNbtKeys", "owner");
        check(flow.contains("ignoredNbtKeys", "owner"), "Global ignore is an explicit key selection");
        flow.toggle("ignoredNbtKeys", "owner");
        check(!flow.contains("ignoredNbtKeys", "owner"), "Ignored keys can be restored");
        flow.choose();
        flow.next();
        check(!flow.complete(), "Hints require their own choice");
        flow.choose();
        check(!flow.complete(), "Selecting the final hint choice cannot complete the flow");
        flow.next();
        check(
            flow.complete() && flow.policy()
                .getTagList("confirmedStages", 8)
                .tagCount() == 6,
            "All six explicit choices are transmitted in stage order");
        NBTTagCompound reviewed = flow.policy();
        HologramProcessingFlow fresh = new HologramProcessingFlow(reviewed);
        check(fresh.stage == 0 && !fresh.chosen(), "A saved policy does not authorize a fresh session");
        net.minecraft.nbt.NBTTagList prefix = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < 4; i++)
            prefix.appendTag(new net.minecraft.nbt.NBTTagString(HologramProcessingFlow.STAGES[i]));
        reviewed.setTag("confirmedStages", prefix);
        HologramProcessingFlow resumed = new HologramProcessingFlow(reviewed, true);
        check(resumed.stage == 4 && !resumed.chosen(), "An authorized session resumes at its invalidated NBT stage");
        HologramProcessingFlow fullResumed = new HologramProcessingFlow(reviewed, true, true);
        fullResumed.requirements(0);
        check(
            fullResumed.fullReview() && fullResumed.stage == 4
                && !fullResumed.chosen()
                && fullResumed.policy()
                    .getTagList("confirmedStages", 8)
                    .tagCount() == 4,
            "The manual preference preserves only the server-authorized prefix on resume");
        flow.previous();
        flow.clearChoice();
        check(!flow.complete() && !flow.next(), "A server-rejected choice must be reviewed again");
        check(saved.getInteger("deidentificationLevel") == 4, "Client flow never mutates the saved preset");
        HologramProcessingFlow explicitlyCleared = new HologramProcessingFlow(new NBTTagCompound());
        explicitlyCleared.tendency(false);
        explicitlyCleared.next();
        explicitlyCleared.next();
        explicitlyCleared.values()
            .setString("quantum", "clear");
        explicitlyCleared.choose();
        explicitlyCleared.tendency(true);
        check(
            "clear".equals(
                explicitlyCleared.values()
                    .getString("quantum")),
            "Sharing recommendation preserves a deliberately chosen complete group clear");
        HologramProcessingFlow groups = new HologramProcessingFlow(new NBTTagCompound());
        groups.tendency(true);
        groups.next();
        groups.next();
        check(
            groups.chosen() && groups.next(),
            "A recommended quantum policy continues with the default single group without opening classification");
        HologramProcessingFlow presetGroups = new HologramProcessingFlow(new NBTTagCompound());
        presetGroups.preset(groups.values());
        presetGroups.next();
        presetGroups.next();
        check(
            presetGroups.stage == 2 && presetGroups.chosen() && presetGroups.next(),
            "A reusable preset never requires a separate per-blueprint group confirmation");
        HologramProcessingFlow selfGroups = new HologramProcessingFlow(new NBTTagCompound());
        selfGroups.tendency(false);
        selfGroups.next();
        selfGroups.next();
        check(
            selfGroups.stage == 2 && "preserve".equals(
                selfGroups.values()
                    .getString("quantum"))
                && selfGroups.chosen()
                && selfGroups.next(),
            "Self-use keeps the original binding and advances without custom classification");
        HologramProcessingFlow changed = new HologramProcessingFlow(new NBTTagCompound());
        changed.tendency(false);
        for (int i = 0; i < 5; i++) changed.next();
        changed.tendency(true);
        check(
            changed.stage == 1 && changed.policy()
                .getTagList("confirmedStages", 8)
                .tagCount() == 1 && !changed.complete(),
            "Changing recommended identity or network choices returns to the earliest affected review step");
    }

    private static void explicitChoiceGuidance() {
        NBTTagCompound defaults = new NBTTagCompound();
        HologramProcessingFlow flow = new HologramProcessingFlow(defaults);
        check(
            "self".equals(
                flow.values()
                    .getString("tendency"))
                && !flow.explicitChoice(),
            "a recommended self-use value is not an explicit approval");
        check(
            flow.guidanceAccent(232) == 0x68dded && flow.guidanceAccent(233) == 0x68dded
                && flow.guidanceAccent(231) == 0,
            "both tendency choices guide an unconfirmed stage; Next does not claim readiness");
        flow.tendency(false);
        check(
            flow.guidanceAccent(232) == 0 && flow.guidanceAccent(233) == 0
                && flow.guidanceAccent(231) == 0x80efaa
                && !flow.complete(),
            "explicitly choosing the default transfers guidance to Next without completing server processing");
        flow.next();
        flow.clearChoice();
        check(
            flow.guidanceAccent(231) == 0 && flow.guidanceAccent(232) == 0x68dded,
            "a rejected or invalidated local stage requests a fresh decision");
        flow.choose();
        check(
            flow.guidanceAccent(231) == 0x80efaa && flow.guidanceAccent(233) == 0,
            "an explicit stage-specific choice also enables the next step");
        check(
            !defaults.hasKey("tendency") && flow.policy()
                .getTagList("confirmedStages", 8)
                .tagCount() == 1,
            "guidance never mutates the source draft or invents remaining confirmations");
        HologramProcessingFlow optional = new HologramProcessingFlow(defaults);
        optional.requirements(0);
        check(
            optional.guidanceAccent(231) == 0x80efaa && !optional.explicitChoice(),
            "an empty optional stage may continue while its recommendation remains unconfirmed");
    }

    private static void nativeSaveStages() {
        long second = java.util.concurrent.TimeUnit.SECONDS.toNanos(1), begin = second;
        HologramLibraryClient.SaveProgress progress = new HologramLibraryClient.SaveProgress();
        progress.begin(begin, false);
        progress.progress(begin + 70 * second);
        progress.progress(begin + 140 * second);
        check(
            !progress.waitingExpired(begin + 150 * second) && !progress.idleExpired(begin + 150 * second),
            "A progressing download, FBO and preview upload may together exceed the source-wait limit");
        check(
            progress.idleExpired(begin + 261 * second),
            "A stalled native save is still cancelled after 120 seconds without actual progress");
        progress.begin(begin, true);
        progress.progress(begin + 70 * second);
        check(
            progress.waitingExpired(begin + 121 * second) && !progress.idleExpired(begin + 121 * second),
            "Received bytes cannot extend the fixed wait for the exact source revision");
        progress.sourceReady(begin + 121 * second);
        check(
            progress.waitingExpired(begin + 122 * second),
            "A source arriving after its fixed deadline cannot revive a cancelled wait");
        progress.begin(begin, true);
        progress.sourceReady(begin + 5 * second);
        progress.progress(begin + 100 * second);
        progress.progress(begin + 200 * second);
        check(
            !progress.waitingExpired(begin + 201 * second) && !progress.idleExpired(begin + 201 * second),
            "An exact source arriving in time ends its wait while subsequent transfer stages retain progress checks");
        progress.clear();
        check(
            !progress.waitingExpired(begin + 1000 * second) && !progress.idleExpired(begin + 1000 * second),
            "A retired save cannot expire a later unrelated operation");
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new IllegalStateException(message);
    }
}
