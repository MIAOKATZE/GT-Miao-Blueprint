package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;

/** Exact local revision handed to existing server processing/save operations; no new wire authority. */
final class HologramAuthorSaveHandoff {

    final HologramLocalAuthorRuntime owner;
    final AuthorDocument document;
    final NBTTagCompound policy;
    final HologramAuthorLibraryOrigin origin;
    final boolean finalized;
    final BooleanSupplier current;
    final String localId, replace, integratedId;
    final long task;
    final double yaw, pitch;
    String session = "";
    long processSequence = -1;
    boolean saving, imported;

    HologramAuthorSaveHandoff(HologramLocalAuthorRuntime owner, AuthorDocument document, NBTTagCompound policy,
        String localId, String replace, String integratedId, long task, BooleanSupplier current, double yaw, double pitch) {
        this.owner = owner;
        this.document = document;
        this.policy = policy == null ? new NBTTagCompound() : (NBTTagCompound) policy.copy();
        this.origin = owner == null ? null : owner.libraryOrigin();
        this.finalized = owner != null && owner.finishedSourceCurrent();
        this.localId = localId;
        this.replace = replace;
        this.integratedId = integratedId;
        this.task = task;
        this.current = current;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    void requireReconciliable(byte[] json) throws java.io.IOException {
        com.google.gson.JsonObject reviewed = HologramLibraryClient.readAuthorSource(json).sourceCopy();
        com.google.gson.JsonObject bare = reviewed.has("blueprint") ? reviewed.getAsJsonObject("blueprint") : reviewed;
        com.google.gson.JsonObject source = document.sourceCopy();
        if (source.has("blueprint")) source.add("blueprint", bare);
        else source = bare;
        try {
            new com.miaokatze.gtmb.blueprint.editor.AuthorEditor(document, 1)
                .replaceResolvedSource(document.revision(), source);
        } catch (RuntimeException incompatible) {
            throw new java.io.IOException("审核后的蓝图不能无损合并当前编辑历史；未覆盖原成品，请导出候选后显式打开", incompatible);
        }
    }

    boolean activation(NBTTagCompound state) {
        return session.isEmpty() && task == state.getLong("blueprintClientTaskToken")
            && (integratedId.isEmpty() ? localId.equals(state.getString("libraryLocalId"))
                : integratedId.equals(state.getString("selectedPresetId")))
            && "blueprintEdit".equals(state.getString("openPurpose"))
            && !state.getBoolean("snapshotPending") && !state.getBoolean("capturePending");
    }

    boolean owns(NBTTagCompound state) {
        return !session.isEmpty() && session.equals(state.getString("session"));
    }

    boolean readyToSave(NBTTagCompound state) {
        return finalized ? imported && owns(state) && !saving : processed(state);
    }

    boolean processed(NBTTagCompound state) {
        return owns(state) && !saving && processSequence >= 0
            && processSequence == state.getLong("uiSequence")
            && "processBlueprint".equals(state.getString("processingPresetOperation"));
    }
}
