package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.hologram.HologramAuthorPortableCompiler;
import com.miaokatze.gtmb.hologram.HologramAuthorReviewProtocol;
import com.miaokatze.gtmb.hologram.HologramExtensionExecution;
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
        if (HologramAuthorReviewClient.busy(runtime)) {
            ui.session.notice("正在核对当前源版本，可取消后重新选择处理方式");
            return false;
        }
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
                NBTTagCompound report = HologramLocalAuthorSemantics.processingState(
                    source,
                    null,
                    runtime.document()
                        .sourceFrame());
                resetReview(report);
                report.setTag("processingPresets", HologramLocalAuthorSemantics.processingPresets());
                runtime.setProcessing(report, false);
                return true;
            }
            if ("processBlueprint".equals(operation)) {
                JsonObject processed = HologramLocalAuthorSemantics.process(
                    settled(runtime, request.getCompoundTag("policy")),
                    request.getCompoundTag("policy"),
                    runtime.document()
                        .sourceFrame());
                NBTTagCompound report = HologramLocalAuthorSemantics.processingState(
                    processed,
                    request.getCompoundTag("policy"),
                    runtime.document()
                        .sourceFrame());
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
                runtime.commitResolvedSource(
                    revision,
                    HologramLocalAuthorSemantics.rotateBlueprint(
                        source,
                        runtime.document()
                            .sourceFrame()));
                return true;
            }
            if ("configureBlueprintQuantumGroups".equals(operation)) {
                if (!request.hasKey("groups", 9)) throw new IOException("量子网络分类缺少完整类群列表");
                JsonObject configured = HologramLocalAuthorSemantics
                    .configureQuantumGroups(runtime.sourceJson(), request.getTagList("groups", 10));
                NBTTagCompound report = HologramLocalAuthorSemantics.processingState(
                    configured,
                    null,
                    runtime.document()
                        .sourceFrame());
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
                    ? HologramLocalAuthorSemantics.processingState(
                        settled(runtime, result),
                        result,
                        runtime.document()
                            .sourceFrame())
                    : (NBTTagCompound) runtime.view().data.getCompoundTag("processing")
                        .copy();
                report.setTag("processingPresets", HologramLocalAuthorSemantics.processingPresets());
                runtime.setProcessing(report, !load && runtime.processingCurrent());
                return true;
            }
            return false;
        } catch (HologramExtensionExecution.ReviewRequired serverOnly) {
            return serverReview(runtime, ui, operation, request);
        } catch (IOException | RuntimeException failed) {
            ui.session.notice("本地作者操作失败，原稿保留：" + failed.getMessage());
            return false;
        }
    }

    /** Only an explicit native action may upload the exact recovery source for legacy extension review. */
    private static boolean serverReview(HologramLocalAuthorRuntime runtime, HologramUiModules ui, String operation,
        NBTTagCompound request) {
        final AuthorDocument frozen = runtime.document();
        final boolean process = "processBlueprint".equals(operation);
        final boolean begin = "beginBlueprintReprocessing".equals(operation);
        final boolean load = "loadProcessingPreset".equals(operation);
        final boolean groups = "configureBlueprintQuantumGroups".equals(operation);
        final boolean rotate = "rotateBlueprint".equals(operation);
        if (!process && !begin && !load && !groups && !rotate) {
            ui.session.notice("此操作需要服务器扩展核对；请先完成处理流程。原稿和未兑现义务保留");
            return false;
        }
        try {
            NBTTagCompound policy = process ? request.getCompoundTag("policy")
                : groups ? request
                    : load ? HologramLocalAuthorSemantics.processingPreset(operation, request)
                        : HologramLocalAuthorSemantics.policyChoices(frozen.headerCopy());
            ui.session.notice("正在向当前服务器核对扩展；仅分析源文档，不放置、不扣除材料，可取消");
            HologramAuthorReviewClient.request(
                runtime,
                process ? "process" : groups ? "configureQuantumGroups" : rotate ? "rotate" : "inspect",
                policy,
                () -> HologramLocalAuthorRuntime.active() == runtime && runtime.document() == frozen,
                reply -> {
                    try {
                        if (runtime.document() != frozen) return;
                        NBTTagCompound report = reply.getCompoundTag("report");
                        report.setTag("processingPresets", HologramLocalAuthorSemantics.processingPresets());
                        if (process || groups || rotate) {
                            byte[] source = reply.getByteArray("source");
                            if (source.length == 0) throw new IOException("服务器未返回处理后的源文档");
                            JsonObject processed = AuthorDocumentIO.read(source, HologramAuthorReviewProtocol.limits())
                                .sourceCopy();
                            if (groups) runtime.commitSource(frozen.revision(), processed);
                            else runtime.commitResolvedSource(frozen.revision(), processed);
                        } else if (begin) resetReview(report);
                        runtime.setProcessing(report, process);
                        ui.processing.localServerReviewReady(operation);
                        ui.session.notice(
                            process || groups || rotate ? "服务器扩展处理已完成；原草稿保留，施工仍须独立审核" : "服务器扩展分析已返回；请逐步选择处理策略，尚未改写原稿");
                    } catch (IOException | RuntimeException failure) {
                        ui.session.notice("服务器扩展结果未应用，原稿保留：" + failure.getMessage());
                    }
                },
                failure -> ui.session.notice("服务器扩展核对未完成，原稿及未兑现义务保留：" + failure.getMessage()));
        } catch (IOException | RuntimeException failure) {
            ui.session.notice("服务器扩展核对未开始，原稿保留：" + failure.getMessage());
        }
        // The report does not exist yet: synchronous callers must not open a stale processing flow.
        return false;
    }

    static void resetReview(NBTTagCompound report) {
        NBTTagCompound policy = report.getCompoundTag("policy");
        policy.setTag("confirmedStages", new NBTTagList());
        policy.setTag("resourceDecisions", new NBTTagList());
        policy.setTag("coordinateOverrides", new NBTTagList());
        policy.setTag("cellNbtOverrides", new NBTTagList());
        report.setBoolean("complete", false);
    }

    private static JsonObject settled(HologramLocalAuthorRuntime runtime, NBTTagCompound policy) throws IOException {
        JsonObject root = HologramAuthorPortableCompiler.settle(runtime.document(), policy);
        return root.has("blueprint") ? root.getAsJsonObject("blueprint") : root;
    }
}
