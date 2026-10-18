package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Local choices only. Completion is granted by the server after applying the entire policy. */
final class HologramProcessingFlow {

    static final String[] STAGES = { "preset", "identity", "quantum", "coordinates", "nbt", "hints" };
    private NBTTagCompound policy;
    private final boolean[] chosen = new boolean[STAGES.length];
    private final boolean[] confirmed = new boolean[STAGES.length];
    private final boolean[] explicit = new boolean[STAGES.length];
    private int requiredStages = 63;
    private boolean fullReview;
    int stage, offset;

    HologramProcessingFlow(NBTTagCompound source) {
        this(source, false);
    }

    HologramProcessingFlow(NBTTagCompound source, boolean resume) {
        this(source, resume, false);
    }

    HologramProcessingFlow(NBTTagCompound source, boolean resume, boolean fullReview) {
        this.fullReview = fullReview;
        policy = (NBTTagCompound) source.copy();
        policy.setInteger("version", 1);
        if (!policy.hasKey("tendency")) recommendations(false);
        if (resume) {
            NBTTagList stages = source.getTagList("confirmedStages", 8);
            int count = 0;
            while (count < stages.tagCount() && count < STAGES.length
                && STAGES[count].equals(stages.getStringTagAt(count))) {
                chosen[count] = true;
                confirmed[count] = true;
                count++;
            }
            stage = Math.min(count, STAGES.length - 1);
        } else policy.setTag("confirmedStages", new NBTTagList());
    }

    void tendency(boolean share) {
        NBTTagCompound previous = (NBTTagCompound) policy.copy();
        recommendations(share);
        for (int i = 0; i < chosen.length; i++) chosen[i] = true;
        int earliest = STAGES.length;
        if (confirmed[1] && previous.getInteger("deidentificationLevel") != policy.getInteger("deidentificationLevel"))
            earliest = 1;
        if (confirmed[2] && !previous.getString("quantum")
            .equals(policy.getString("quantum"))) earliest = Math.min(earliest, 2);
        if (confirmed[3] && (!previous.getString("coordinateDefault")
            .equals(policy.getString("coordinateDefault"))
            || "auto".equals(policy.getString("coordinateDefault")) && !previous.getString("tendency")
                .equals(policy.getString("tendency"))))
            earliest = Math.min(earliest, 3);
        if (confirmed[5] && !previous.getString("autoHints")
            .equals(policy.getString("autoHints"))) earliest = Math.min(earliest, 5);
        if (earliest < STAGES.length) {
            for (int i = earliest; i < confirmed.length; i++) confirmed[i] = false;
            stage = Math.min(stage, earliest);
            offset = 0;
        }
    }

    private void recommendations(boolean share) {
        policy.setString("tendency", share ? "share" : "self");
        if (!explicit[1]) policy.setInteger("deidentificationLevel", share ? 6 : 0);
        if (!explicit[2]) policy.setString("quantum", share ? "rebind" : "preserve");
        if (!explicit[3]) policy.setString("coordinateDefault", "auto");
        if (!explicit[5]) policy.setString("autoHints", "light");
    }

    void preset(NBTTagCompound value) {
        String tendency = policy.getString("tendency");
        policy = (NBTTagCompound) value.copy();
        // Coordinates belong to the reviewed blueprint, never to a reusable workflow preset.
        policy.setTag("resourceDecisions", new NBTTagList());
        policy.setInteger("version", 1);
        if (!policy.hasKey("tendency")) policy.setString("tendency", tendency);
        for (int i = 0; i < chosen.length; i++) {
            chosen[i] = true;
            confirmed[i] = false;
            explicit[i] = false;
        }
        choose();
    }

    void choose() {
        chosen[stage] = true;
        explicit[stage] = true;
        for (int i = stage; i < confirmed.length; i++) confirmed[i] = false;
    }

    void clearChoice() {
        chosen[stage] = false;
        confirmed[stage] = false;
    }

    boolean chosen() {
        return chosen[stage] || optional();
    }

    boolean explicitChoice() {
        return chosen[stage];
    }

    int guidanceAccent(int id) {
        if (id == 234) return 0xef7b7b;
        if (!chosen() && (id == 232 || id == 233)) return 0x68dded;
        return chosen() && id == 231 ? 0x80efaa : 0;
    }

    void requirements(int stages) {
        requiredStages = stages & 63;
    }

    boolean optional() {
        return !fullReview && (requiredStages & 1 << stage) == 0;
    }

    int requiredStages() {
        return requiredStages;
    }

    boolean fullReview() {
        return fullReview;
    }

    void fullReview(boolean enabled) {
        if (fullReview == enabled) return;
        fullReview = enabled;
        for (int i = 0; i < confirmed.length; i++) {
            chosen[i] = confirmed[i] = false;
        }
        stage = offset = 0;
    }

    boolean next() {
        if (!chosen()) return false;
        confirmed[stage] = true;
        if (stage < STAGES.length - 1) {
            stage++;
            while (stage < STAGES.length - 1 && optional()) {
                confirmed[stage] = true;
                stage++;
            }
            offset = 0;
        }
        return true;
    }

    void previous() {
        if (stage > 0) stage--;
        offset = 0;
    }

    NBTTagCompound values() {
        return policy;
    }

    NBTTagCompound policy() {
        NBTTagCompound result = (NBTTagCompound) policy.copy();
        NBTTagList stages = new NBTTagList();
        for (int i = 0; i < confirmed.length; i++) {
            if (!confirmed[i]) break;
            stages.appendTag(new NBTTagString(STAGES[i]));
        }
        result.setTag("confirmedStages", stages);
        return result;
    }

    boolean complete() {
        for (boolean choice : confirmed) if (!choice) return false;
        return true;
    }

    NBTTagCompound backgroundUiState() {
        NBTTagCompound state = new NBTTagCompound();
        state.setTag("policy", policy.copy());
        state.setInteger("stage", stage);
        state.setInteger("offset", offset);
        state.setInteger("requiredStages", requiredStages);
        state.setBoolean("fullReview", fullReview);
        int choices = 0, confirmations = 0, explicitChoices = 0;
        for (int i = 0; i < STAGES.length; i++) {
            if (chosen[i]) choices |= 1 << i;
            if (confirmed[i]) confirmations |= 1 << i;
            if (explicit[i]) explicitChoices |= 1 << i;
        }
        state.setInteger("chosen", choices);
        state.setInteger("confirmed", confirmations);
        state.setInteger("explicit", explicitChoices);
        return state;
    }

    static HologramProcessingFlow restoreBackgroundUiState(NBTTagCompound state) {
        HologramProcessingFlow flow = new HologramProcessingFlow(state.getCompoundTag("policy"));
        flow.policy = (NBTTagCompound) state.getCompoundTag("policy")
            .copy();
        flow.stage = Math.max(0, Math.min(STAGES.length - 1, state.getInteger("stage")));
        flow.offset = Math.max(0, state.getInteger("offset"));
        flow.requiredStages = state.getInteger("requiredStages") & 63;
        flow.fullReview = state.getBoolean("fullReview");
        for (int i = 0; i < STAGES.length; i++) {
            flow.chosen[i] = (state.getInteger("chosen") & 1 << i) != 0;
            flow.confirmed[i] = (state.getInteger("confirmed") & 1 << i) != 0;
            flow.explicit[i] = (state.getInteger("explicit") & 1 << i) != 0;
        }
        return flow;
    }

    void toggle(String list, String value) {
        NBTTagList old = policy.getTagList(list, 8), next = new NBTTagList();
        boolean found = false;
        for (int i = 0; i < old.tagCount(); i++) {
            if (value.equals(old.getStringTagAt(i))) found = true;
            else next.appendTag(new NBTTagString(old.getStringTagAt(i)));
        }
        if (!found) next.appendTag(new NBTTagString(value));
        policy.setTag(list, next);
    }

    boolean contains(String list, String value) {
        NBTTagList entries = policy.getTagList(list, 8);
        for (int i = 0; i < entries.tagCount(); i++) if (value.equals(entries.getStringTagAt(i))) return true;
        return false;
    }

    void coordinate(String id, String mode) {
        NBTTagList old = policy.getTagList("coordinateOverrides", 10), next = new NBTTagList();
        for (int i = 0; i < old.tagCount(); i++) if (!id.equals(
            old.getCompoundTagAt(i)
                .getString("id")))
            next.appendTag(
                old.getCompoundTagAt(i)
                    .copy());
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("id", id);
        entry.setString("mode", mode);
        next.appendTag(entry);
        policy.setTag("coordinateOverrides", next);
    }

    String coordinate(String id) {
        NBTTagList list = policy.getTagList("coordinateOverrides", 10);
        for (int i = 0; i < list.tagCount(); i++) if (id.equals(
            list.getCompoundTagAt(i)
                .getString("id")))
            return list.getCompoundTagAt(i)
                .getString("mode");
        return policy.getString("coordinateDefault");
    }

    void resourceDecision(NBTTagCompound resource, String action) {
        if (!"remove".equals(action) && !("preserve".equals(action) && resource.getBoolean("canPreserve"))
            && !("cancel".equals(action) && resource.getBoolean("canCancelPreserve"))
            && !("migrate".equals(action) && resource.getBoolean("canMigrate"))) return;
        NBTTagList previous = policy.getTagList("resourceDecisions", 10), decisions = new NBTTagList();
        for (int i = 0; i < previous.tagCount(); i++)
            if (!sameResource(previous.getCompoundTagAt(i), resource)) decisions.appendTag(
                previous.getCompoundTagAt(i)
                    .copy());
        NBTTagCompound decision = new NBTTagCompound();
        decision.setInteger("x", resource.getInteger("x"));
        decision.setInteger("y", resource.getInteger("y"));
        decision.setInteger("z", resource.getInteger("z"));
        decision.setString("scope", resource.getString("scope"));
        decision.setString("path", resource.getString("path"));
        decision.setString("action", action);
        decisions.appendTag(decision);
        policy.setTag("resourceDecisions", decisions);
        choose();
    }

    String resourceDecision(NBTTagCompound resource) {
        NBTTagList decisions = policy.getTagList("resourceDecisions", 10);
        for (int i = 0; i < decisions.tagCount(); i++)
            if (sameResource(decisions.getCompoundTagAt(i), resource)) return decisions.getCompoundTagAt(i)
                .getString("action");
        return resource.getBoolean("preserved") ? "preserve" : "";
    }

    boolean resourceChoicesReady(NBTTagList resources) {
        java.util.Map<String, String> decisions = new java.util.HashMap<>();
        NBTTagList selected = policy.getTagList("resourceDecisions", 10),
            ignored = policy.getTagList("ignoredNbtKeys", 8);
        for (int i = 0; i < selected.tagCount(); i++) {
            NBTTagCompound decision = selected.getCompoundTagAt(i);
            decisions.put(resourceKey(decision), decision.getString("action"));
        }
        java.util.Set<String> ignoredRoots = new java.util.HashSet<>();
        for (int i = 0; i < ignored.tagCount(); i++) ignoredRoots.add(ignored.getStringTagAt(i));
        for (int i = 0; i < resources.tagCount(); i++) {
            NBTTagCompound resource = resources.getCompoundTagAt(i);
            if (ignoredRoots.contains(resource.getString("rootKey"))) continue;
            String action = decisions.get(resourceKey(resource));
            if (action == null && resource.getBoolean("preserved")) continue;
            if ("remove".equals(action) || "preserve".equals(action) && resource.getBoolean("canPreserve")
                || "cancel".equals(action) && resource.getBoolean("canCancel")
                || "migrate".equals(action) && resource.getBoolean("canMigrate")) continue;
            return false;
        }
        return true;
    }

    private static String resourceKey(NBTTagCompound resource) {
        return resource.getInteger(
            "x") + "," + resource.getInteger("y") + "," + resource.getInteger("z") + ":" + resource.getString("path");
    }

    private static boolean sameResource(NBTTagCompound left, NBTTagCompound right) {
        return left.getInteger("x") == right.getInteger("x") && left.getInteger("y") == right.getInteger("y")
            && left.getInteger("z") == right.getInteger("z")
            && left.getString("path")
                .equals(right.getString("path"));
    }
}
