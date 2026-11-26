// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Binding;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.CommitReceipt;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Outcome;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Phase;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Ticket;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramNetwork;

/** Client-thread task lifetime, independent of disposable MUI trees and transport acknowledgements. */
public final class HologramBackgroundTasks {

    private static final String OWNED_PLACEMENT = "ownedPlacement", OWNED_CONTEXT_LOST = "ownedPlacementLostContext";
    private static final HologramOwnedPlacementProgress placementProgress = new HologramOwnedPlacementProgress();

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
            boolean noWork = OWNED_PLACEMENT.equals(operation) && placementProgress.terminal() == Outcome.NO_WORK;
            succeeded = result == HologramTaskOwner.Result.SUCCESS && !noWork;
            completion = noWork ? "无需施工" + (detail == null || detail.isEmpty() ? "" : "：" + detail)
                : label
                    + (succeeded ? "已完成"
                        : result == HologramTaskOwner.Result.PARTIAL ? "部分完成"
                            : result == HologramTaskOwner.Result.REVIEW_REQUIRED ? "等待确认"
                                : result == HologramTaskOwner.Result.CANCELLED ? "已取消" : "失败")
                    + (detail == null || detail.isEmpty() ? "" : "：" + detail);
            if (OWNED_CONTEXT_LOST.equals(operation)) completion = detail;
            if ("orderMissingMaterials".equals(operation)) completion += "；右键投影仪返回材料确认，点击“刷新材料”核对后继续放置。";
            chat(completion);
            Minecraft mc = Minecraft.getMinecraft();
            if (succeeded && mc != null && mc.thePlayer != null) {
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
    private static final HologramProgress.Tracker materialProgress = new HologramProgress.Tracker();
    private static final HologramMaterialOrderLifecycle materialOrders = new HologramMaterialOrderLifecycle();
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
        if (ownedPlacementTask()) return placementProgress.view(placementBinding(), System.nanoTime());
        if (materialTask()) return materialProgress.view(System.nanoTime());
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

    static boolean materialTask() {
        return "material".equals(OWNER.kind());
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
        Ticket placement = placementProgress.ticket();
        Binding placementContext = placement == null ? null : placementBinding();
        placementProgress.expireTerminal(placementContext, System.nanoTime());
        if (ownedPlacementTask() && !placementProgress.active(placement, placementContext)) {
            // Dropping a connection retires our observation, not the server transaction.
            String detail = "原世界或连接已离开，服务器施工结果未知";
            placementProgress.clear();
            operation = OWNED_CONTEXT_LOST;
            OWNER.finish(OWNER.token(), HologramTaskOwner.Result.FAILURE, detail);
            context = null;
            presentation = null;
            restorePending = false;
            OWNER.attach();
        }
        if ((busy() || presentation != null) && (world != mc.theWorld || connection != mc.getNetHandler())) {
            if (materialTask()) materialOrders.reject();
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
            startRevision = context == null || !session.equals(context.session) ? -1
                : context.data.getLong("planRevision");
        }
        boundToken = OWNER.token();
    }

    /**
     * Client thread, only from a confirmed construction route. Reads and wire acknowledgments never begin this task.
     */
    public static Ticket beginOwnedPlacement(UUID request, String label, BooleanSupplier cancel) {
        Binding binding = placementBinding();
        if (request == null || binding == null || !binding.valid() || busy()) return null;
        long token = OWNER.begin(OWNED_PLACEMENT, request.toString(), label);
        if (token == 0) return null;
        session = "";
        command = -1;
        operation = OWNED_PLACEMENT;
        bind();
        context = null; // The status contract needs no current or retained full editor snapshot.
        return placementProgress.begin(request, binding, token, cancel, System.nanoTime());
    }

    /** Resolve only an admitted client ticket; the caller separately authenticates the original receipt. */
    public static Ticket ownedPlacementTicket(UUID request) {
        Ticket ticket = placementProgress.ticket();
        return ownedPlacementTask() && ticket != null
            && ticket.request()
                .equals(request)
            && placementProgress.active(ticket, placementBinding()) ? ticket : null;
    }

    /** Phase-local counters and serial, not a percentage guessed from timing or transport acknowledgments. */
    public static boolean observeOwnedPlacement(Ticket ticket, Phase phase, long sequence, long done, long total) {
        return ownsPlacement(ticket)
            && placementProgress.observe(ticket, placementBinding(), phase, sequence, done, total, System.nanoTime());
    }

    /** An authenticated stopped or rejected request can retire only its own presentation. */
    public static boolean finishOwnedPlacement(Ticket ticket, long sequence, Outcome outcome, long placed,
        long alreadySatisfied, long total, String message) {
        if (!ownsPlacement(ticket) || !placementProgress
            .finish(ticket, placementBinding(), sequence, outcome, placed, alreadySatisfied, total, System.nanoTime()))
            return false;
        return notifyOwnedPlacementTerminal(ticket, outcome, message);
    }

    /** Call only from the distinct authenticated committed-result decoder, not transport or preparation ACKs. */
    public static boolean acceptOwnedPlacementCommit(Ticket ticket, CommitReceipt receipt, String message) {
        if (!ownsPlacement(ticket)
            || !placementProgress.acceptCommit(ticket, placementBinding(), receipt, System.nanoTime())) return false;
        return notifyOwnedPlacementTerminal(ticket, receipt.outcome(), message);
    }

    private static boolean notifyOwnedPlacementTerminal(Ticket ticket, Outcome outcome, String message) {
        return OWNER.finish(ticket.token, switch (outcome) {
            case SUCCESS, NO_WORK -> HologramTaskOwner.Result.SUCCESS;
            case FAILED -> HologramTaskOwner.Result.FAILURE;
            case CANCELLED -> HologramTaskOwner.Result.CANCELLED;
        }, message);
    }

    private static Binding placementBinding() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return null;
        var held = mc.thePlayer.getHeldItem();
        return new Binding(
            mc.theWorld,
            mc.getNetHandler(),
            mc.thePlayer,
            ItemNekoHologramProjector.isProjector(held) ? held : null,
            mc.thePlayer.inventory.currentItem);
    }

    static boolean ownedPlacementTask() {
        return OWNED_PLACEMENT.equals(OWNER.kind());
    }

    private static boolean ownsPlacement(Ticket ticket) {
        return ticket != null && placementProgress.ticket() == ticket
            && OWNER.matches(
                OWNED_PLACEMENT,
                ticket.request()
                    .toString())
            && OWNER.token() == ticket.token;
    }

    static boolean ownedPlacementVisible() {
        return ownedPlacementTask() && placementProgress.visible(placementBinding());
    }

    /** Uses the same existing screen and progress bar for the opt-in route, without changing other tasks. */
    public static boolean showOwnedPlacement(Ticket ticket) {
        if (!ownsPlacement(ticket) || !placementProgress.visible(placementBinding())) return false;
        OWNER.attach();
        Minecraft.getMinecraft()
            .displayGuiScreen(new HologramPlacementLoadingScreen(ticket));
        return true;
    }

    static HologramProgress ownedPlacementView(Ticket ticket) {
        if (ticket == null || ticket != placementProgress.ticket()
            || !(ownsPlacement(ticket) || !busy() && completedToken == ticket.token)) return null;
        return placementProgress.view(placementBinding(), System.nanoTime());
    }

    static String ownedPlacementStatus(Ticket ticket) {
        if (ticket == null || ticket != placementProgress.ticket() || !placementProgress.visible(placementBinding()))
            return "";
        if (!busy() && completedToken == ticket.token) return completion;
        if (!ownsPlacement(ticket)) return "";
        if (cancelling) return "正在取消，请等待服务端收尾";
        HologramProgress progress = ownedPlacementView(ticket);
        if (progress == null) return "";
        String summary = HologramProgressView.summary(progress, System.nanoTime());
        return progress.total == 0 && progress.done > 0
            ? summary + " · " + progress.done + " " + HologramProgressView.phase("unit." + progress.unit)
            : summary;
    }

    static String ownedPlacementStatus() {
        return ownedPlacementStatus(placementProgress.ticket());
    }

    static boolean ownedPlacementContinuing(Ticket ticket) {
        return ownsPlacement(ticket) && placementProgress.visible(placementBinding());
    }

    /** The exact result outlives the short HUD progress window while its confirmation screen remains open. */
    static String ownedPlacementResult(Ticket ticket) {
        return ticket != null && completedToken == ticket.token ? completion : "";
    }

    static void leaveOwnedPlacement(Ticket ticket) {
        if (ownsPlacement(ticket) && placementProgress.visible(placementBinding())) OWNER.detach();
    }

    static boolean cancelOwnedPlacement(Ticket ticket) {
        if (!ownsPlacement(ticket) || cancelling) return false;
        BooleanSupplier cancel = placementProgress.cancellation(ticket, placementBinding());
        if (cancel == null) return false;
        try {
            cancelling = cancel.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
        return cancelling;
    }

    static long beginLibrary(String label) {
        String key = "library".equals(OWNER.kind()) ? OWNER.key() : Long.toString(++librarySequence);
        long token = OWNER.begin("library", key, label);
        if (token != 0) {
            operation = "library";
            bind();
        }
        return token;
    }

    static void finishLibrary(long token, HologramTaskOwner.Result result, String detail) {
        if ("library".equals(OWNER.kind())) OWNER.finish(token, result, detail);
    }

    static void command(String sessionId, long sequence, String op) {
        if ("orderMissingMaterials".equals(op)) {
            if (OWNER.begin("material", sessionId + ":" + sequence, "AE 本次缺料订单") == 0) return;
            session = sessionId;
            command = sequence;
            operation = op;
            bind();
            if (context != null) materialOrders.begin(world, connection, context, sequence);
            materialProgress
                .update(session + ":" + sequence, "material.order", 0, 0, "materials", false, System.nanoTime());
            return;
        }
        if (materialTask() && session.equals(sessionId) && "cancelMaterialOrder".equals(op)) {
            issuedSequence = Math.max(issuedSequence, sequence);
            cancelling = true;
            return;
        }
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
        if (libraryTask()) HologramLibraryClient.INSTANCE.saveCommand(sessionId, sequence, op);
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
        if ("pause".equals(op) || "resume".equals(op)) return false;
        if (ownedPlacementTask()) return false;
        if (materialTask()) return "cancelMaterialOrder".equals(op) && !cancelling;
        if ("server".equals(OWNER.kind()) && controlPending()
            && ("cancel".equals(op) || "pause".equals(op) || "resume".equals(op))) return false;
        return !busy() || "cancel".equals(op)
            || "scan".equals(operation) && "dismissOperationReview".equals(op)
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
            && HologramLibraryClient.SaveCommand.reviewPending(context.data, OWNER.token());
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
        if (materialTask()) {
            if ("rejected".equals(event.getString("outcome")) && session.equals(event.getString("session"))) {
                long rejected = event.getLong("uiSequence");
                if (rejected == issuedSequence && cancelling && rejected != command) {
                    cancelling = false;
                    chat("本次 AE 订单取消请求被拒绝，订单继续。");
                } else if (rejected == command && !materialOrders.active()) {
                    materialOrders.reject();
                    OWNER.finish(OWNER.token(), HologramTaskOwner.Result.FAILURE, event.getString("status"));
                }
            }
            return;
        }
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

    static HologramState materialOrderProgress(HologramState previous, NBTTagCompound event, Object currentWorld,
        Object currentConnection) {
        if (!materialOrders.receive(currentWorld, currentConnection, previous, event)) return null;
        HologramState next = materialOrders.apply(previous);
        context = next;
        updateMaterialOrderOwner(materialOrders.order());
        return next;
    }

    static HologramState materialQuoteReceipt(HologramState next, NBTTagCompound receipt, Object currentWorld,
        Object currentConnection) {
        if (!receipt.getBoolean("materialQuoteOnly") || !materialTask()
            || world != currentWorld
            || connection != currentConnection
            || !session.equals(receipt.getString("session"))
            || startRevision != receipt.getLong("planRevision")
            || receipt.getLong("placementReviewNonce") != next.data.getLong("placementReviewNonce")) return next;
        if (!materialOrders.owns(next)) {
            if (!materialOrders.rejectChangedReview(currentWorld, currentConnection, next, receipt)) return next;
            next = materialOrders.apply(next);
            context = next;
            materialOrders.finishOwner(OWNER);
            return next;
        }
        if (receipt.getLong("uiSequence") == command) {
            if (!materialOrders.quoteReceipt(currentWorld, currentConnection, next, receipt)) return next;
            next = materialOrders.apply(next);
            context = next;
            if (materialOrders.id()
                .isEmpty()) materialOrders.finishOwner(OWNER);
            else updateMaterialOrderOwner(materialOrders.order());
        } else if (cancelling && receipt.getLong("uiSequence") == issuedSequence) {
            NBTTagCompound order = receipt.getCompoundTag("materialOrder");
            if (!order.hasKey("orderUiSequence", 4) || order.getLong("orderUiSequence") != command
                || order.getLong("planRevision") != startRevision
                || order.getLong("placementReviewNonce") != next.data.getLong("placementReviewNonce")) return next;
            next = materialOrders.snapshot(currentWorld, currentConnection, next);
            context = next;
            updateMaterialOrderOwner(materialOrders.order());
            // A refused cancellation keeps its original order usable for a later explicit retry.
            if (materialOrders.active()) cancelling = false;
        }
        return next;
    }

    static HologramState retainMaterialOrder(HologramState next) {
        Minecraft mc = Minecraft.getMinecraft();
        return materialOrders.snapshot(mc.theWorld, mc.getNetHandler(), next);
    }

    private static void updateMaterialOrderOwner(NBTTagCompound order) {
        if (!materialTask() || materialOrders.id()
            .isEmpty()
            || !materialOrders.id()
                .equals(order.getString("orderId")))
            return;
        boolean active = order.getBoolean("active");
        materialProgress.update(
            session + ":" + materialOrders.command(),
            "material.order",
            order.getLong("done"),
            order.getLong("total"),
            "materials",
            !active,
            System.nanoTime());
        if (active) return;
        materialOrders.finishOwner(OWNER);
    }

    /** Cells live outside metadata; only a genuinely complete new-workspace baseline may transfer task ownership. */
    static boolean advancesAreaWorkspace(HologramState previous, HologramState next, String ownerSession,
        long ownerCommand) {
        if (previous == null || next == null || !next.completeBaseline()) return false;
        NBTTagCompound data = next.data;
        return previous.session.equals(ownerSession) && !ownerSession.equals(next.session)
            && ownerSession.equals(data.getString("editorWorkspacePreviousSession"))
            && "projection".equals(previous.data.getString("editorWorkspace"))
            && "blueprint".equals(data.getString("editorWorkspace"))
            && data.getLong("editorWorkspaceEpoch") > previous.data.getLong("editorWorkspaceEpoch")
            && data.getLong("uiSequence") == ownerCommand
            && "area".equals(data.getString("blueprintSource"));
    }

    static void hiddenControllerSnapshot(NBTTagCompound data) {
        if ("scan".equals(operation) && HologramState.fullBaseline(data))
            OWNER.finishScanSnapshot(data, startRevision, cancelling);
    }

    static boolean scanningControllerOpening(long id) {
        return busy() && "scan".equals(operation) && HologramClientProgress.controllerSession(id, session);
    }

    static void snapshot(HologramState next) {
        if (context != null && context.session.equals(next.session)) context = next;
        if (libraryTask() && HologramLibraryClient.INSTANCE.snapshot(next)) reviewSubmitted = false;
        NBTTagCompound data = next.data;
        if ("server".equals(OWNER.kind()) && "area".equals(operation)
            && advancesAreaWorkspace(context, next, session, command)
            && OWNER.advance("server", session + ":" + command, next.session + ":" + command)) {
            session = next.session;
            context = next;
        }
        NBTTagCompound materialOrder = data.getCompoundTag("materialOrder");
        if (!busy() && materialOrder.getBoolean("active")
            && !materialOrders.id()
                .isEmpty()
            && materialOrders.id()
                .equals(materialOrder.getString("orderId"))
            && materialOrders.active()) {
            if (materialOrders.restoreOwner(OWNER) != 0) {
                session = next.session;
                command = materialOrders.command();
                operation = "orderMissingMaterials";
                bind();
            }
        }
        if (materialTask()) {
            if (!materialOrders.owns(next)) {
                materialOrders.reject();
                OWNER.finish(OWNER.token(), HologramTaskOwner.Result.FAILURE, "材料确认会话已变化，请重新核对材料");
                return;
            }
            context = next;
            if (materialOrders.rejectSubmission(next)) {
                materialOrders.finishOwner(OWNER);
                return;
            }
            updateMaterialOrderOwner(materialOrder);
            return;
        }
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
        } else if (data.getBoolean("operationReviewRequired") && data.getBoolean("operationPrepared")
            && job != 1
            && job != 2
            && !"scan".equals(operation)) {
                OWNER.finish(OWNER.token(), HologramTaskOwner.Result.REVIEW_REQUIRED, data.getString("status"));
            } else if ("processBlueprint".equals(operation)) {
                OWNER.finish(
                    OWNER.token(),
                    "processBlueprint".equals(data.getString("processingPresetOperation"))
                        && data.getBoolean("processingPresetOperationSuccess")
                        && data.getLong("planRevision") > startRevision ? HologramTaskOwner.Result.SUCCESS
                            : HologramTaskOwner.Result.FAILURE,
                    data.getString("status"));
            } else if ("scan".equals(operation)) {
                OWNER.finishScanSnapshot(data, startRevision, cancelling);
            } else if ("area".equals(operation)) {
                OWNER.finishAreaSnapshot(data, startRevision, cancelling);
            } else if (job == 4 || job == 5) {
                OWNER.finish(
                    OWNER.token(),
                    cancelling ? HologramTaskOwner.Result.CANCELLED : HologramTaskOwner.Result.FAILURE,
                    data.getString("status"));
            } else
                if (job == 3) OWNER.finish(OWNER.token(), HologramTaskOwner.Result.SUCCESS, data.getString("status"));
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
        if (!busy() || ownedPlacementTask() || ui.diagnostics.captureRenderSmokeAction()) return;
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
        if (ownedPlacementTask()) return true; // Native construction is presented only in the HUD.
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
        if (ownedPlacementTask()) {
            cancelOwnedPlacement(placementProgress.ticket());
            return;
        }
        if (!canCancel()) return;
        cancelling = true;
        if (materialTask()) {
            sendJobControl("cancelMaterialOrder");
            return;
        }
        if ("library".equals(OWNER.kind())) {
            if (awaitingReview()) {
                NBTTagCompound request = new HologramAeReviewModel(context.data.getCompoundTag("aeSaveReview"), true)
                    .action();
                request.setString("op", "cancelAeSave");
                HologramClient.stampSemanticRevision(request);
                HologramNetwork.sendAction(request);
            }
            HologramLibraryClient.INSTANCE.cancelBackgroundTask();
            return;
        } else {
            sendJobControl(awaitingSourceReview() ? "cancelSourceSwitch" : "cancel");
        }
        // Keep server ownership until its authoritative stopped receipt arrives.
    }

    private static boolean controlPending() {
        return controlPredecessors.containsKey(command) && !acceptedControls.contains(command);
    }

    static boolean canCancel() {
        if (ownedPlacementTask()) return !cancelling
            && placementProgress.cancellation(placementProgress.ticket(), placementBinding()) != null;
        return busy() && !cancelling
            && (!materialTask() || materialOrders.active() && context != null)
            && (!"server".equals(OWNER.kind()) || !controlPending());
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
        if ("cancelMaterialOrder".equals(op) && (context == null || !materialOrders.owns(context)
            || !HologramMaterialOrderLifecycle.writeCancel(
                request,
                materialOrders.order(),
                context.data.getLong("planRevision"),
                context.data.getLong("placementReviewNonce")))) {
            cancelling = false;
            return;
        }
        command(session, sequence, op);
        HologramClient.stampSemanticRevision(request);
        HologramNetwork.sendAction(request);
    }

    static void cancelled(String sessionId) {
        if (materialTask() && session.equals(sessionId)) materialOrders.reject();
        if (busy() && ("server".equals(OWNER.kind()) || materialTask()) && session.equals(sessionId)) OWNER.finish(
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
            case "capture.prepare" -> "结构采集未完成";
            case "attachment.upload" -> "文字或图片数据上传失败";
            case "snapshot.prepare" -> "蓝图快照准备失败";
            case "snapshot.encode" -> "蓝图快照编码失败";
            case "snapshot.decode" -> "蓝图快照解码失败";
            case "snapshot.queue" -> "蓝图快照发送队列已满";
            case "snapshot.budget" -> "蓝图快照超过传输预算";
            default -> "蓝图快照传输失败";
        };
        return reason + "；保留原预览，请重试当前操作。";
    }

    private static void chat(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(message));
        else {
            if (pendingMessages.size() == 8) pendingMessages.remove(0);
            pendingMessages.add(message);
        }
    }
}
