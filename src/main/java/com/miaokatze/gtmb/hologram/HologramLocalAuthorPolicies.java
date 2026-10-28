package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/** Explicit local policy/metadata operations. No Session, world access, display reconstruction or approval receipt. */
public final class HologramLocalAuthorPolicies {

    private HologramLocalAuthorPolicies() {}

    /** Selected coordinates are source-document coordinates, never player/world anchors. */
    public static JsonObject configureCellNbtPolicy(JsonObject header, List<JsonObject> selected, NBTTagCompound action)
        throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            if (selected == null || selected.isEmpty() || selected.size() > 8192)
                throw new IOException("请先选择至多8192个 NBT 编辑格");
            if (!action.hasKey("all", 1)) throw new IOException("NBT 保留选项缺少明确选择");
            HologramPresets.Preset metadata = HologramLocalAuthorSemantics.preset(header);
            NBTTagCompound previous = HologramBlueprintNbt.decode(metadata.processingNbt);
            HologramBlueprintProcessing.Policy known = previous != null && previous.hasKey("policy", 10)
                ? HologramBlueprintProcessing.Policy.read(previous.getCompoundTag("policy"))
                : HologramBlueprintProcessing.defaults("self", HologramDeidentification.level(metadata));
            NBTTagCompound policy = known.write();
            Map<String, NBTTagCompound> byPosition = new LinkedHashMap<>();
            NBTTagList overrides = policy.getTagList("cellNbtOverrides", 10);
            for (int i = 0; i < overrides.tagCount(); i++) {
                NBTTagCompound row = overrides.getCompoundTagAt(i);
                byPosition.put(position(row), row);
            }
            NBTTagList keys = HologramSessionPolicy
                .nbtKeys(HologramAuthoringQueries.selectedNbtKeys(action.getTagList("keys", 8)));
            for (JsonObject cell : selected) {
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger(
                    "x",
                    cell.get("x")
                        .getAsInt());
                row.setInteger(
                    "y",
                    cell.get("y")
                        .getAsInt());
                row.setInteger(
                    "z",
                    cell.get("z")
                        .getAsInt());
                row.setBoolean("all", action.getBoolean("all"));
                row.setTag("keys", keys.copy());
                row.setTag(
                    "ignoredPaths",
                    action.getTagList("ignoredPaths", 8)
                        .copy());
                byPosition.put(position(row), row);
            }
            if (byPosition.size() > 8192) throw new IOException("方块 NBT 策略已达8192个上限");
            overrides = new NBTTagList();
            for (NBTTagCompound row : byPosition.values()) overrides.appendTag(row);
            policy.setTag("cellNbtOverrides", overrides);
            NBTTagList stages = new NBTTagList();
            NBTTagList original = policy.getTagList("confirmedStages", 8);
            for (int i = 0; i < original.tagCount(); i++) {
                String stage = original.getStringTagAt(i);
                if ("nbt".equals(stage) || "hints".equals(stage)) break;
                stages.appendTag(new NBTTagString(stage));
            }
            policy.setTag("confirmedStages", stages);
            HologramBlueprintProcessing.Policy checked = HologramBlueprintProcessing.Policy.read(policy);
            // Preserve unknown receipt fields, but invalidate approval explicitly. This only stores edit intent.
            NBTTagCompound receipt = previous == null ? new NBTTagCompound() : (NBTTagCompound) previous.copy();
            receipt.setInteger("version", HologramBlueprintProcessing.VERSION);
            receipt.setBoolean("complete", false);
            receipt.removeTag("extensions");
            receipt.removeTag("extensionReviewPending");
            receipt.setInteger("minimumLevel", HologramDeidentification.level(metadata));
            receipt.setTag("policy", checked.write());
            JsonObject patch = new JsonObject();
            patch.addProperty("processingNbt", HologramBlueprintNbt.encodeCanonical(receipt));
            return patch;
        }
    }

    /** Explicit native version calibration, not a compatibility certification or implicit opening cost. */
    public static JsonObject calibrateVersions(JsonObject source) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Preset detached = HologramLocalAuthorSemantics.preset(source);
            HologramBlueprintMetadata.captureVersions(detached);
            JsonObject patch = new JsonObject();
            patch.add("modVersions", new Gson().toJsonTree(detached.modVersions));
            return patch;
        }
    }

    private static String position(NBTTagCompound row) {
        return row.getInteger("x") + "," + row.getInteger("y") + "," + row.getInteger("z");
    }
}
