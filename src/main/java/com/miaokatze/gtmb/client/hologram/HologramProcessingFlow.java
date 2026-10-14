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
        if (!explicit[2]) policy.setString("quantum", share ? "clear" : "rebind");
        if (!explicit[3]) policy.setString("coordinateDefault", "auto");
        if (!explicit[5]) policy.setString("autoHints", share ? "full" : "light");
    }

    void preset(NBTTagCompound value) {
        String tendency = policy.getString("tendency");
        policy = (NBTTagCompound) value.copy();
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
}
