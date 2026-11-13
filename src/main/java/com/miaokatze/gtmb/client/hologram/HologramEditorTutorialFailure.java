// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

/** A blocked playback retains its first cause until navigation restores the lesson. */
final class HologramEditorTutorialFailure {

    private String reason = "";

    boolean block(String reason) {
        if (blocked()) return false;
        this.reason = reason;
        return true;
    }

    boolean blocked() {
        return !reason.isEmpty();
    }

    String reason() {
        return reason;
    }

    void reset() {
        reason = "";
    }

    List<String> messageLines(int step, int count, String title) {
        return Arrays.asList("第 " + (step + 1) + "/" + count + " 步 · " + title, "原因：" + reason, "可返回上一步、从头开始或跳过教程。");
    }

    /** Only counters and fixed course identity may leave the in-memory diagnostic snapshot. */
    static NBTTagCompound redactedDiagnostic(NBTTagCompound source) {
        NBTTagCompound result = new NBTTagCompound();
        String topic = source.getString("topic");
        if (Arrays
            .asList(
                "projection",
                "blueprint",
                "structure",
                "cut",
                "text",
                "picture",
                "box-fps",
                "box-iso",
                "cut-fps",
                "cut-iso")
            .contains(topic)) result.setString("topic", topic);
        result.setString("phase", "blocked");
        for (String key : new String[] { "step", "stepCount", "action", "actionCount" })
            result.setInteger(key, source.getInteger(key));
        for (String key : new String[] { "actionPending", "actionDispatched", "actionApplied" })
            result.setBoolean(key, source.getBoolean(key));
        for (String key : new String[] { "commandResults", "isolatedCommands", "realSessionSequence" })
            result.setLong(key, source.getLong(key));
        NBTTagCompound engine = source.getCompoundTag("lessonEngine"), counts = new NBTTagCompound();
        for (String key : new String[] { "renderedCellCount", "presentedVisibleCellCount", "nativeModelCellCount",
            "modelCellCount", "modelStructureCount", "selectedCellCount", "dockPage" })
            counts.setInteger(key, engine.getInteger(key));
        for (String key : new String[] { "snapshotPresented", "isolatedCommandSink" })
            counts.setBoolean(key, engine.getBoolean(key));
        counts.setLong("renderGeneration", engine.getLong("renderGeneration"));
        result.setTag("lessonEngine", counts);
        return result;
    }
}
