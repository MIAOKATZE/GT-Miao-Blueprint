package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

import com.miaokatze.gtmb.hologram.HologramNetwork;

/** Client-thread task lifetime, independent of disposable MUI trees and transport acknowledgements. */
public final class HologramBackgroundTasks {

    private static final HologramTaskOwner OWNER = new HologramTaskOwner(new HologramTaskOwner.Notifications() {

        @Override
        public void background(String label) {
            backgroundNotifications++;
            chat(label + "在后台进行；右键投影仪查看进度");
        }

        @Override
        public void finished(String label, HologramTaskOwner.Result result, String detail) {
            terminalNotifications++;
            completedToken = boundToken;
            succeeded = result == HologramTaskOwner.Result.SUCCESS;
            completion = label + (succeeded ? "已完成" : result == HologramTaskOwner.Result.CANCELLED ? "已取消" : "失败")
                + (detail == null || detail.isEmpty() ? "" : "：" + detail);
            chat(completion);
            Minecraft mc = Minecraft.getMinecraft();
            if (result == HologramTaskOwner.Result.SUCCESS && mc.thePlayer != null) {
                mc.thePlayer.playSound("random.levelup", 0.7f, 1f);
                levelupSounds++;
            }
        }
    });
    private static Object world, connection;
    private static String session = "", operation = "";
    private static long command = -1;
    private static long issuedSequence = -1;
    private static long startRevision;
    private static long librarySequence;
    private static HologramState context;
    private static HologramBackgroundUiState presentation;
    private static boolean restorePending;
    private static String completion = "";
    private static boolean succeeded;
    private static boolean cancelling;
    private static boolean reviewSubmitted;
    private static final java.util.List<String> pendingMessages = new java.util.ArrayList<>();
    private static final HologramProgress.Tracker displayProgress = new HologramProgress.Tracker();
    private static long boundToken, completedToken, backgroundNotifications, terminalNotifications, levelupSounds;
    private static final java.util.Map<Long, Long> controlPredecessors = new java.util.LinkedHashMap<>();
    private static final java.util.Set<Long> acceptedControls = new java.util.HashSet<>();

    private HologramBackgroundTasks() {}

    public static boolean busy() {
        return OWNER.busy();
    }

    static String label() {
        return OWNER.label();
    }

    static String status() {
        if (!busy()) return completion;
        if (cancelling) return "正在取消，请等待服务端收尾";
        if (awaitingReview()) return "保存等待人工审核；右键投影仪继续当前审核";
        if (awaitingSourceReview()) return "区域导入等待确认；右键投影仪继续当前确认";
        HologramProgress progress = progress();
        return progress == null ? label() + "进行中，请稍候"
            : HologramProgressView.phase(progress.phase) + " · "
                + percentage()
                + " · "
                + HologramProgressView.detail(progress);
    }

    static HologramProgress progress() {
        if ("server".equals(OWNER.kind()) && !HologramClientProgress.commandMatches("server", session, command)) {
            Long previous = controlPredecessors.get(command);
            if (previous == null || !HologramClientProgress.commandMatches("server", session, previous)) return null;
        }
        HologramProgress value = "library".equals(OWNER.kind()) ? HologramLibraryClient.INSTANCE.progress()
            : HologramClientProgress.view("server", session);
        if (value != null && value.complete && busy()) return displayProgress.update(
            value.operation,
            value.phase,
            value.detail,
            value.done,
            value.total,
            value.unit,
            false,
            System.nanoTime());
        return value;
    }

    static long token() {
        return OWNER.token();
    }

    static long commandSequence(String sessionId) {
        // Retain the watermark after failure/completion so a retry cannot reuse a retired command identity.
        return session.equals(sessionId) ? Math.max(command, issuedSequence) : -1;
    }

    static boolean libraryTask() {
        return "library".equals(OWNER.kind());
    }

    static boolean succeeded() {
        return !busy() && succeeded;
    }

    static NBTTagCompound actualTaskWitness() {
        NBTTagCompound result = new NBTTagCompound();
        result.setLong("token", OWNER.token());
        result.setLong("completedToken", completedToken);
        result.setLong("backgroundNotifications", backgroundNotifications);
        result.setLong("terminalNotifications", terminalNotifications);
        result.setLong("levelupSounds", levelupSounds);
        result.setBoolean("busy", busy());
        result.setBoolean("detached", detached());
        return result;
    }

    static String percentage() {
        if (!busy()) return succeeded ? "100%" : "任务已结束";
        HologramProgress progress = progress();
        return progress == null || progress.percent() < 0 ? "阶段处理中" : Math.min(99, progress.percent()) + "%";
    }

    static double fraction() {
        if (!busy()) return succeeded ? 1 : 0;
        HologramProgress progress = progress();
        return progress == null || progress.percent() < 0 ? 0 : Math.min(99, progress.percent()) / 100d;
    }

    static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if ((busy() || presentation != null) && (world != mc.theWorld || connection != mc.getNetHandler())) {
            if (busy()) OWNER.finish(OWNER.token(), HologramTaskOwner.Result.FAILURE, "世界或连接已关闭，任务未继续");
            context = null;
            presentation = null;
            restorePending = false;
            session = "";
            OWNER.attach();
        }
        if (mc.thePlayer != null && !pendingMessages.isEmpty()) {
            for (String message : pendingMessages) mc.thePlayer.addChatMessage(new ChatComponentText(message));
            pendingMessages.clear();
        }
    }

    private static void bind() {
        Minecraft mc = Minecraft.getMinecraft();
        world = mc.theWorld;
        connection = mc.getNetHandler();
        context = HologramClient.state;
        if (boundToken != OWNER.token()) {
            presentation = null;
            restorePending = false;
            cancelling = false;
            reviewSubmitted = false;
            controlPredecessors.clear();
            acceptedControls.clear();
            issuedSequence = command;
            startRevision = context == null ? -1 : context.data.getLong("planRevision");
        }
        boundToken = OWNER.token();
    }

    static long beginLibrary(String label) {
        String key = "library".equals(OWNER.kind()) ? OWNER.key() : Long.toString(++librarySequence);
        long token = OWNER.begin("library", key, label);
        if (token != 0) bind();
        return token;
    }

    static void finishLibrary(long token, boolean success, String detail) {
        if (!"library".equals(OWNER.kind())) return;
        OWNER.finish(
            token,
            success ? HologramTaskOwner.Result.SUCCESS
                : cancelling ? HologramTaskOwner.Result.CANCELLED : HologramTaskOwner.Result.FAILURE,
            detail);
    }

    static void command(String sessionId, long sequence, String op) {
        if ("server".equals(OWNER.kind()) && session.equals(sessionId)
            && ("pause".equals(op) || "resume".equals(op)
                || "cancel".equals(op)
                || "area".equals(operation) && ("importArea".equals(op) || "cancelSourceSwitch".equals(op)))) {
            if (sequence <= issuedSequence) return;
            if (OWNER.advance("server", session + ":" + command, sessionId + ":" + sequence)) {
                controlPredecessors.put(sequence, command);
                while (controlPredecessors.size() > 128) {
                    Long oldest = controlPredecessors.keySet()
                        .iterator()
                        .next();
                    controlPredecessors.remove(oldest);
                    acceptedControls.remove(oldest);
                }
                issuedSequence = sequence;
                command = sequence;
                if ("cancel".equals(op) || "cancelSourceSwitch".equals(op)) cancelling = true;
            }
            return;
        }
        if (libraryTask() && ("confirmAeSave".equals(op) || "cancelAeSave".equals(op))) reviewSubmitted = true;
        if (libraryTask() && "cancelAeSave".equals(op)) HologramLibraryClient.INSTANCE.cancelDraftReview();
        if (!("processBlueprint".equals(op) || "scan".equals(op)
            || "importArea".equals(op)
            || "build".equals(op)
            || "start".equals(op)
            || "native".equals(op)
            || "confirmBlueprintPlacement".equals(op))) return;
        if (OWNER.begin(
            "server",
            sessionId + ":" + sequence,
            "processBlueprint".equals(op) ? "蓝图处理"
                : "importArea".equals(op) ? "区域导入" : "scan".equals(op) ? "结构采集" : "施工任务")
            == 0) return;
        session = sessionId;
        command = sequence;
        operation = "importArea".equals(op) ? "area" : op;
        bind();
    }

    static boolean commandAllowed(String op) {
        if ("server".equals(OWNER.kind()) && controlPending()
            && ("cancel".equals(op) || "pause".equals(op) || "resume".equals(op))) return false;
        return !busy() || "cancel".equals(op)
            || "pause".equals(op)
            || "resume".equals(op)
            || awaitingSourceReview() && !controlPending()
                && ("importArea".equals(op) || "cancelSourceSwitch".equals(op))
            || "ackBlueprintSaved".equals(op)
            || "library".equals(OWNER.kind()) && (op.startsWith("saveBlueprint") || "savePreset".equals(op)
                || "updatePreset".equals(op)
                || "exportDraft".equals(op)
                || "confirmAeSave".equals(op)
                || "cancelAeSave".equals(op));
    }

    static boolean awaitingReview() {
        return libraryTask() && !reviewSubmitted
            && context != null
            && !context.data.getBoolean("blueprintSavePending")
            && context.data.getCompoundTag("aeSaveReview")
                .getLong("clientTaskToken") == OWNER.token()
            && context.session.equals(
                context.data.getCompoundTag("aeSaveReview")
                    .getString("session"))
            && context.data.getLong("planRevision") == context.data.getCompoundTag("aeSaveReview")
                .getLong("planRevision")
            && !context.data.getCompoundTag("aeSaveReview")
                .getString("transactionId")
                .isEmpty();
    }

    static boolean awaitingSourceReview() {
        if (!busy() || !"server".equals(OWNER.kind())
            || !"area".equals(operation)
            || context == null
            || !session.equals(context.session)
            || !context.data.getBoolean("discardConfirmationRequired")) return false;
        NBTTagCompound proposal = context.data.getCompoundTag("discardTargetAction");
        return "importArea".equals(proposal.getString("op")) && session.equals(proposal.getString("session"))
            && !proposal.getString("sourceSwitchNonce")
                .isEmpty()
            && proposal.getLong("planRevision") == context.data.getLong("planRevision");
    }

    static boolean reviewControl(int id) {
        return id == 181 || id == 182
            || id >= 185 && id <= 191
            || id >= 40000 && id < 48192
            || id >= 50000 && id < 58192;
    }

    static void progress(NBTTagCompound event) {
        if (!"server".equals(event.getString("scope"))) return;
        String phase = event.getString("phase");
        if (!busy() && !event.getBoolean("complete")
            && ("job".equals(phase) || "capture".equals(phase) || phase.startsWith("area."))) {
            long token = OWNER.begin(
                "server",
                event.getString("session") + ":" + event.getLong("uiSequence"),
                phase.startsWith("area.") ? "区域导入" : "capture".equals(phase) ? "结构采集" : "施工任务");
            if (token != 0) {
                session = event.getString("session");
                command = event.getLong("uiSequence");
                operation = phase.startsWith("area.") ? "area" : "capture".equals(phase) ? "scan" : "build";
                bind();
                if ("area".equals(operation))
                    startRevision = Math.min(startRevision, event.getLong("planRevision") - 1);
            }
        }
        if (!matches(event)) return;
        String outcome = event.getString("outcome");
        if ("rejected".equals(outcome) && controlPredecessors.containsKey(command)) {
            if (acceptedControls.contains(command)) return;
            long previous = controlPredecessors.get(command);
            if (OWNER.rejectAdvance("server", session + ":" + command, session + ":" + previous)) {
                controlPredecessors.remove(command);
                command = previous;
                cancelling = false;
                chat("任务控制请求被拒绝，原任务继续；可重新打开任务页操作");
            }
            return;
        }
        if (!"rejected".equals(outcome) && controlPredecessors.containsKey(command)) acceptedControls.add(command);
        if (!"rejected".equals(outcome)
            && ("processBlueprint".equals(operation) || "scan".equals(operation) || "area".equals(operation))) return;
        if ("rejected".equals(outcome) || "stopped".equals(outcome) || "partial".equals(outcome)) OWNER.finish(
            OWNER.token(),
            cancelling ? HologramTaskOwner.Result.CANCELLED : HologramTaskOwner.Result.FAILURE,
            event.getString("status"));
        else if ("completed".equals(outcome) && event.getBoolean("complete"))
            OWNER.finish(OWNER.token(), HologramTaskOwner.Result.SUCCESS, event.getString("status"));
    }

    private static boolean matches(NBTTagCompound value) {
        return OWNER.matches("server", value.getString("session") + ":" + value.getLong("uiSequence"));
    }

    static void snapshot(HologramState next) {
        if (context != null && context.session.equals(next.session)) context = next;
        NBTTagCompound data = next.data;
        if (!busy() && (data.getInteger("job") == 1 || data.getInteger("job") == 2)) {
            if (OWNER.begin("server", next.session + ":" + data.getLong("uiSequence"), "施工任务") != 0) {
                session = next.session;
                command = data.getLong("uiSequence");
                operation = "build";
                bind();
            }
        }
        if (data.hasKey("progress", 10) && HologramClientProgress.matches(data.getCompoundTag("progress")))
            progress(data.getCompoundTag("progress"));
        if (!matches(data)) return;
        if (controlPredecessors.containsKey(command)) acceptedControls.add(command);
        if (awaitingSourceReview()) return;
        int job = data.getInteger("job");
        if (data.getBoolean("targetClosed")) {
            OWNER.finish(
                OWNER.token(),
                cancelling ? HologramTaskOwner.Result.CANCELLED : HologramTaskOwner.Result.FAILURE,
                data.getString("status"));
        } else if ("processBlueprint".equals(operation)) {
            OWNER.finish(
                OWNER.token(),
                "processBlueprint".equals(data.getString("processingPresetOperation"))
                    && data.getBoolean("processingPresetOperationSuccess")
                    && data.getLong("planRevision") > startRevision ? HologramTaskOwner.Result.SUCCESS
                        : HologramTaskOwner.Result.FAILURE,
                data.getString("status"));
        } else if ("scan".equals(operation)) {
            if (!data.getBoolean("capturePending")) OWNER.finish(
                OWNER.token(),
                cancelling ? HologramTaskOwner.Result.CANCELLED
                    : data.getBoolean("supported") && data.getLong("planRevision") > startRevision
                        ? HologramTaskOwner.Result.SUCCESS
                        : HologramTaskOwner.Result.FAILURE,
                data.getString("status"));
        } else if ("area".equals(operation)) {
            OWNER.finishAreaSnapshot(data, startRevision, cancelling);
        } else if (job == 4 || job == 5) {
            OWNER.finish(
                OWNER.token(),
                cancelling ? HologramTaskOwner.Result.CANCELLED : HologramTaskOwner.Result.FAILURE,
                data.getString("status"));
        } else if (job == 3) OWNER.finish(OWNER.token(), HologramTaskOwner.Result.SUCCESS, data.getString("status"));
        else if (job != 1 && job != 2 && !data.getBoolean("capturePending"))
            OWNER.finish(OWNER.token(), HologramTaskOwner.Result.FAILURE, data.getString("status"));
    }

    static void detach() {
        OWNER.detach();
    }

    static boolean detached() {
        return OWNER.detached();
    }

    static void rememberUi(HologramUiModules ui) {
        if (!busy() || ui.diagnostics.captureRenderSmokeAction()) return;
        if (context != null && !context.session.equals(ui.session.state().session)) return;
        presentation = HologramBackgroundUiState.capture(ui, OWNER.token());
    }

    static void restoreUi(HologramUiModules ui) {
        if (!restorePending) return;
        restorePending = false;
        long expected = busy() ? OWNER.token() : completedToken;
        if (presentation != null && presentation.matches(ui.session.state().session, expected))
            presentation.restore(ui);
        if (!busy()) presentation = null;
    }

    private static HologramState workspaceState() {
        HologramState latest = HologramClient.state;
        return latest != null && (context == null || context.session.equals(latest.session)) ? latest : context;
    }

    static boolean reopen() {
        Minecraft mc = Minecraft.getMinecraft();
        if (world != mc.theWorld || connection != mc.getNetHandler()) return false;
        HologramState next = workspaceState();
        if (next == null || !busy() && !(detached() && presentation != null)) return false;
        OWNER.attach();
        restorePending = true;
        // Rebuild the normal workstation. Its initGui restores values into a fresh MUI tree.
        mc.displayGuiScreen(new HologramScreen(next));
        return true;
    }

    static void returnToWorkspace() {
        HologramState next = workspaceState();
        restorePending = next != null;
        Minecraft.getMinecraft()
            .displayGuiScreen(next == null ? null : new HologramScreen(next));
    }

    static void cancel() {
        if (!canCancel()) return;
        cancelling = true;
        if ("library".equals(OWNER.kind())) {
            if (awaitingReview()) {
                NBTTagCompound request = new HologramAeReviewModel(context.data.getCompoundTag("aeSaveReview"), true)
                    .action();
                request.setString("op", "cancelAeSave");
                HologramNetwork.sendAction(request);
            }
            HologramLibraryClient.INSTANCE.cancelBackgroundTask();
            return;
        } else {
            sendJobControl(awaitingSourceReview() ? "cancelSourceSwitch" : "cancel");
        }
        // Keep server ownership until its authoritative stopped receipt arrives.
    }

    static boolean pauseAvailable() {
        HologramState current = HologramClient.state;
        return busy() && "server".equals(OWNER.kind())
            && !cancelling
            && !controlPending()
            && current != null
            && session.equals(current.session)
            && (current.data.getInteger("job") == 1 || current.data.getInteger("job") == 2);
    }

    private static boolean controlPending() {
        return controlPredecessors.containsKey(command) && !acceptedControls.contains(command);
    }

    static boolean canCancel() {
        return busy() && !cancelling && (!"server".equals(OWNER.kind()) || !controlPending());
    }

    static String pauseLabel() {
        HologramState current = HologramClient.state;
        return current != null && current.data.getInteger("job") == 2 ? "继续任务" : "暂停任务";
    }

    static void togglePause() {
        if (!pauseAvailable()) return;
        sendJobControl(HologramClient.state.data.getInteger("job") == 2 ? "resume" : "pause");
    }

    private static void sendJobControl(String op) {
        HologramState current = HologramClient.state;
        long sequence = Math
            .max(Math.max(command, issuedSequence), current == null ? command : current.data.getLong("uiSequence")) + 1;
        NBTTagCompound request = "cancelSourceSwitch".equals(op) && context != null
            ? (NBTTagCompound) context.data.getCompoundTag("discardTargetAction")
                .copy()
            : new NBTTagCompound();
        request.setString("session", session);
        request.setString("op", op);
        request.setLong("uiSequence", sequence);
        command(session, sequence, op);
        HologramNetwork.sendAction(request);
    }

    static void cancelled(String sessionId) {
        if (busy() && "server".equals(OWNER.kind()) && session.equals(sessionId)) OWNER.finish(
            OWNER.token(),
            cancelling ? HologramTaskOwner.Result.CANCELLED : HologramTaskOwner.Result.FAILURE,
            "服务端任务已结束");
    }

    static boolean transportFailed(NBTTagCompound failure) {
        // A failed construction snapshot does not prove its world worker has stopped.
        if (!"area".equals(operation) && !"scan".equals(operation) && !"processBlueprint".equals(operation))
            return false;
        return OWNER.failSnapshot(failure, transportFailureMessage(failure));
    }

    static boolean retiredTransportFailure(NBTTagCompound failure) {
        return failure.hasKey("uiSequence", 4)
            && OWNER.retiredReceipt("server", failure.getString("session") + ":" + failure.getLong("uiSequence"));
    }

    static String transportFailureMessage(NBTTagCompound failure) {
        String reason = switch (failure.getString("errorCode")) {
            case "snapshot.prepare" -> "蓝图快照准备失败";
            case "snapshot.encode" -> "蓝图快照编码失败";
            case "snapshot.queue" -> "蓝图快照发送队列已满";
            case "snapshot.budget" -> "蓝图快照超过传输预算";
            default -> "蓝图快照传输失败";
        };
        return reason + "；保留原预览，请重试当前操作。";
    }

    private static void chat(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(message));
        else {
            if (pendingMessages.size() == 8) pendingMessages.remove(0);
            pendingMessages.add(message);
        }
    }
}
