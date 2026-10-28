package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramAuthorPortableCompiler;
import com.miaokatze.gtmb.hologram.HologramLocalAuthorSemantics;

/** Explicit native analysis boundary. Ordinary author reads, edits and persistence never enter this class. */
final class HologramLocalAuthorNativeActions {

    private HologramLocalAuthorNativeActions() {}

    static boolean handles(String operation) {
        return "beginBlueprintReprocessing".equals(operation) || "processBlueprint".equals(operation)
            || "rotateBlueprint".equals(operation)
            || "configureBlueprintQuantumGroups".equals(operation)
            || "saveProcessingPreset".equals(operation)
            || "updateProcessingPreset".equals(operation)
            || "deleteProcessingPreset".equals(operation)
            || "loadProcessingPreset".equals(operation);
    }

    static boolean execute(HologramLocalAuthorRuntime runtime, HologramUiModules ui, String operation,
        NBTTagCompound request) {
        try {
            long revision = runtime.document()
                .revision();
            if (request.hasKey("planRevision", 4) && request.getLong("planRevision") != revision)
                throw new IOException("蓝图已修改，请重新核对当前操作");
            if ("beginBlueprintReprocessing".equals(operation)) {
                JsonObject source = settled(
                    runtime,
                    HologramLocalAuthorSemantics.policyChoices(
                        runtime.document()
                            .headerCopy()));
                NBTTagCompound report = HologramLocalAuthorSemantics.processingState(source, null, 0, 0, 0, 0);
                NBTTagCompound policy = report.getCompoundTag("policy");
                policy.setTag("confirmedStages", new NBTTagList());
                policy.setTag("resourceDecisions", new NBTTagList());
                policy.setTag("coordinateOverrides", new NBTTagList());
                policy.setTag("cellNbtOverrides", new NBTTagList());
                report.setBoolean("complete", false);
                report.setTag("processingPresets", HologramLocalAuthorSemantics.processingPresets());
                runtime.setProcessing(report, false);
                return true;
            }
            if ("processBlueprint".equals(operation)) {
                JsonObject processed = HologramLocalAuthorSemantics.process(
                    settled(runtime, request.getCompoundTag("policy")),
                    request.getCompoundTag("policy"),
                    0,
                    0,
                    0,
                    0);
                NBTTagCompound report = HologramLocalAuthorSemantics
                    .processingState(processed, request.getCompoundTag("policy"), 0, 0, 0, 0);
                runtime.commitResolvedSource(revision, processed);
                runtime.setProcessing(report, true);
                ui.session.notice("本地处理已完成；实际放置仍由服务端核验");
                return true;
            }
            if ("rotateBlueprint".equals(operation)) {
                HologramAuthorPortableCompiler.requireKnownTransformExtensions(runtime.document());
                JsonObject source = settled(
                    runtime,
                    HologramLocalAuthorSemantics.policyChoices(
                        runtime.document()
                            .headerCopy()));
                runtime.commitResolvedSource(revision, HologramLocalAuthorSemantics.rotateBlueprint(source));
                return true;
            }
            if ("configureBlueprintQuantumGroups".equals(operation)) {
                if (!request.hasKey("groups", 9)) throw new IOException("量子网络分类缺少完整类群列表");
                JsonObject configured = HologramLocalAuthorSemantics
                    .configureQuantumGroups(runtime.sourceJson(), request.getTagList("groups", 10));
                NBTTagCompound report = HologramLocalAuthorSemantics.processingState(configured, null, 0, 0, 0, 0);
                runtime.commitSource(revision, configured);
                runtime.setProcessing(report, false);
                ui.processing.quantumGroupsSaved();
                return true;
            }
            if ("saveProcessingPreset".equals(operation) || "updateProcessingPreset".equals(operation)
                || "deleteProcessingPreset".equals(operation)
                || "loadProcessingPreset".equals(operation)) {
                NBTTagCompound result = HologramLocalAuthorSemantics.processingPreset(operation, request);
                boolean load = "loadProcessingPreset".equals(operation);
                NBTTagCompound report = load
                    ? HologramLocalAuthorSemantics.processingState(settled(runtime, result), result, 0, 0, 0, 0)
                    : (NBTTagCompound) runtime.view().data.getCompoundTag("processing")
                        .copy();
                report.setTag("processingPresets", HologramLocalAuthorSemantics.processingPresets());
                runtime.setProcessing(report, !load && runtime.processingCurrent());
                return true;
            }
            return false;
        } catch (IOException | RuntimeException failed) {
            ui.session.notice("本地作者操作失败，原稿保留：" + failed.getMessage());
            return false;
        }
    }

    private static JsonObject settled(HologramLocalAuthorRuntime runtime, NBTTagCompound policy) throws IOException {
        JsonObject root = HologramAuthorPortableCompiler.settle(runtime.document(), policy, 0, 0, 0, 0);
        return root.has("blueprint") ? root.getAsJsonObject("blueprint") : root;
    }
}
