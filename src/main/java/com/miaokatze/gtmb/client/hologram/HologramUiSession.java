package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.client.hologram.HologramUiMemory.UiMemory;
import com.miaokatze.gtmb.hologram.HologramNetwork;

/** Internal session workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiSession {

    private final HologramUiModules ui;

    HologramUiSession(HologramUiModules ui) {
        this.ui = ui;
    }

    private HologramState state;
    private HologramLocalAuthorRuntime author;
    private boolean authorSourceRequired, authorSourceAttempted;

    HologramLocalAuthorRuntime author() {
        return author;
    }

    boolean isLocalAuthor() {
        return author != null;
    }

    boolean backgroundBusy() {
        return author == null && HologramBackgroundTasks.busy();
    }

    /** Local binding deliberately bypasses all command tickets, source ACKs and server sequence gates. */
    void initializeLocal(HologramLocalAuthorRuntime runtime) {
        author = runtime;
        state = runtime.view();
        runtime.attach(this);
        ui.bindings.initializeSnapshot(state);
        ui.processing.initializeSnapshot(state);
        ui.library.initializeBrowsing(state);
        ui.memory.initializeTabStore();
        ui.library.initializeTab(state);
        ui.preview.initializeSelection(state);
        ui.preview.initializeLayer(state);
        ui.preview.resetCamera();
        if (runtime.previewContext() != null) ui.preview.restoreBackgroundUiState(runtime.previewContext());
        ui.config.syncTargetSelectors();
    }

    void localDocumentChanged(HologramState next) {
        if (author == null) throw new IllegalStateException("No local document owner");
        ui.library.syncLocalName(state, next);
        state = next;
        ui.preview.initializeSelection(next);
        ui.preview.syncLayerAndSelection(next);
        ui.config.syncTargetSelectors();
    }

    void localProjectionChanged(HologramState next) {
        if (author != null) state = next;
    }

    void localTick() {
        if (author == null && authorSourceRequired
            && !authorSourceAttempted
            && !ui.workspaces.isCut()
            && !state.data.getBoolean("capturePending")
            && !areaImporting()
            && !state.data.getBoolean("snapshotPending")
            && state.completeBaseline()
            && state.data.getInteger("job") != 1
            && state.data.getInteger("job") != 2
            && !HologramLibraryClient.INSTANCE.busy()
            && !HologramBackgroundTasks.busy()) {
            authorSourceAttempted = true;
            HologramLibraryClient.INSTANCE.acquireAuthorSource(ui, false);
        }
        if (author != null) {
            author.select(
                ui.preview.selection()
                    .indices());
            author.tick();
        }
    }

    void reattachLocal() {
        if (author != null) {
            author.attach(this);
            localDocumentChanged(author.view());
        }
    }

    void detachLocal() {
        if (author != null) {
            author.select(
                ui.preview.selection()
                    .indices());
            author.rememberPreview(ui.preview.backgroundUiState());
            author.detach(this);
        }
    }

    private java.util.function.BiConsumer<String, NBTTagCompound> tutorialSink;
    private final HologramTutorialGeneration tutorialGeneration = new HologramTutorialGeneration();

    Runnable guardTutorialAction(Runnable action) {
        return isTutorial() ? tutorialGeneration.guard(action) : action;
    }

    void invalidateTutorialActions() {
        if (isTutorial()) tutorialGeneration.invalidate();
    }

    /** Detached teaching sessions never enter production command preparation or transport. */
    boolean isTutorial() {
        return tutorialSink != null;
    }

    void initializeIsolated(HologramState snapshot, java.util.function.BiConsumer<String, NBTTagCompound> sink) {
        if (sink == null) throw new IllegalArgumentException("A tutorial requires an isolated command sink");
        tutorialSink = sink;
        state = snapshot;
        ui.bindings.initializeSnapshot(snapshot);
        ui.processing.initializeSnapshot(snapshot);
        ui.library.initializeBrowsing(snapshot);
        ui.preview.initializeSelection(snapshot);
        ui.preview.initializeLayer(snapshot);
        ui.preview.resetCamera();
        ui.config.syncTargetSelectors();
    }

    void isolatedSnapshot(HologramState snapshot) {
        if (!isTutorial()) throw new IllegalStateException("Production snapshots require the normal ACK workflow");
        state = snapshot;
        // Local receipts use the same request-matched attachment lifecycle as received production snapshots.
        ui.textBoxes.acknowledge(snapshot);
        ui.config.syncTargetSelectors();
    }

    private boolean awaitingScan;

    private String notice = "";

    private long sequence;

    private final HologramUiTicket pendingTicket = new HologramUiTicket();
    private final HologramProgress.Tracker commandProgress = new HologramProgress.Tracker();
    private String pendingOperation = "";

    private String openedPurpose = "";

    private long materialReviewSequence = -1, materialReviewRevision = -1;
    private Boolean desiredVoucher;
    private Boolean submittedVoucher;
    private boolean materialRefreshQueued;
    private long libraryTaskToken, libraryTaskSequence = -1, libraryTaskPresetSequence = -1;
    private String libraryTaskSession = "";

    void initialize(HologramState snapshot) {
        state = snapshot;
        authorSourceRequired = needsAuthorSource(snapshot);
        ui.bindings.initializeSnapshot(snapshot);
        ui.processing.initializeSnapshot(snapshot);
        ui.library.initializeBrowsing(snapshot);
        ui.memory.initializeTabStore();
        ui.library.initializeTab(snapshot);
        ui.preview.initializeSelection(snapshot);
        UiMemory memory = ui.memory.sessionMemory(snapshot.session);
        if (memory != null) {
            ui.library.restoreInitialUi(memory);
            ui.review.restoreInitialUi(memory);
            ui.preview.restoreInitialUi(memory);
        }
        ui.preview.initializeLayer(snapshot);
        sequence = Math.max(snapshot.data.getLong("uiSequence"), snapshot.data.getLong("materialQuoteSequence"));
        ui.preview.resetCamera();
        ui.config.syncTargetSelectors();
        if (authorSourceRequired) return;
        ui.library.applyOpeningPurpose();
        ui.ae.receiveAeReview();
        ui.identity.offerPlacementDeidentification();
        ui.library.acceptSourceSwitchProposal();
    }

    static boolean needsAuthorSource(HologramState snapshot) {
        // Cut entry uses an already complete blueprint as a UI host, not permission to take over its workflow.
        // HologramClient has already matched the opening request/tool/world before installing this marker.
        return !"cutWorkspace".equals(snapshot.data.getString("openPurpose"))
            && snapshot.data.getString("cutWorkspaceRequestId")
                .isEmpty()
            && snapshot.data.getBoolean("virtual")
            && !snapshot.data.getBoolean("virtualPlacement")
            && HologramGuidance.authorWorkspace(snapshot)
            && !snapshot.data.getBoolean("settingsOnly")
            && !snapshot.data.getBoolean("areaSelectionShell");
    }

    void updateState(HologramState next) {
        if (author != null) return; // Projection snapshots cannot replace an open local source.
        if (needsAuthorSource(next)) {
            authorSourceRequired = true;
            state = next;
            ui.preview.retainSelection(next);
            ui.preview.syncLayerAndSelection(next);
            return;
        }
        authorSourceRequired = false;
        ui.workspaces.acknowledged();
        HologramUiNetworkBindings.SnapshotTransition bindings = ui.bindings.captureSnapshotTransition();
        HologramUiLibrary.SnapshotTransition library = ui.library.prepareSnapshot(next);
        HologramState.Cell previousTarget = selectedCell();
        int previousSelection = ui.preview.selected();
        Integer previousChoice = ui.identity.prepareSnapshot(library.sourceChanged);
        boolean newSession = !state.session.equals(next.session);
        if (newSession) {
            ui.textBoxes.reset();
            ui.identity.resetSession();
            ui.memory.rememberUi();
            ui.library.restoreSession(next, library);
            ui.annotations.resetSession();
            ui.preview.resetSession(next);
            pendingTicket.clear();
            commandProgress.clear();
            clearMaterialReviewAction();
            libraryTaskToken = 0;
            libraryTaskSequence = -1;
            libraryTaskPresetSequence = -1;
            libraryTaskSession = "";
        }
        state = next;
        ui.textBoxes.acknowledge(next);
        if (materialReviewSequence >= 0 && revision() != materialReviewRevision) clearMaterialReviewAction();
        ui.library.acceptSourceSwitchProposal();
        ui.config.acknowledgeConfiguration(next);
        ui.processing.acknowledgePreset(next);
        ui.identity.acknowledgeSnapshot(next, previousChoice, library.canonicalSaveAck);
        ui.bindings.acknowledgeSnapshot(next, bindings, library.sourceChanged);
        if (areaImporting()) {
            ui.preview.pauseForImport();
            ui.ae.pauseForImport();
            awaitingScan = false;
            ui.review.pauseForImport();
        }
        // Retain selection before opening-purpose callbacks can inspect cells or persist memory.
        ui.preview.retainSelection(next);
        ui.library.applyOpeningPurpose();
        ui.preview.acknowledgeSelection(next);
        ui.library.acknowledgeSnapshot(next, library, newSession);
        if (!next.data.getCompoundTag("textPlacementAck")
            .getBoolean("pending") && pendingTicket.acknowledge(next.session, next.data.getLong("uiSequence")))
            commandProgress.clear();
        ui.preview.acknowledgeEdits(next);
        ui.review.acknowledgeReview(next);
        ui.preview.syncLayerAndSelection(next);
        ui.config.acknowledgeSelection(next, previousSelection, previousTarget);
        ui.review.acknowledgeScan(next);
        ui.ae.receiveAeReview();
        ui.identity.offerPlacementDeidentification();
        ui.ae.completeAeReviewNavigation();
        ui.preview.drainQueuedEdits();
        ui.ae.acknowledgePreview(next);
        acknowledgeMaterialReview(next.data.getLong("uiSequence"));
    }

    /** A model-domain attachment is not a command receipt and cannot acknowledge selection or material review. */
    void modelOnly(HologramState previous, HologramState next) {
        if (state == previous && next.modelOnly && next.data == previous.data && next.follows(previous)) state = next;
    }

    static boolean sameTarget(HologramState.Cell before, HologramState.Cell after) {
        if (before == null || after == null) return before == after;
        if (before.targetFacing != after.targetFacing || before.wantMeta != after.wantMeta
            || !before.wantId.equals(after.wantId)) return false;
        if (before.targetStack == null || after.targetStack == null) return before.targetStack == after.targetStack;
        return before.targetStack.isItemEqual(after.targetStack)
            && ItemStack.areItemStackTagsEqual(before.targetStack, after.targetStack);
    }

    /** An order heartbeat carries no geometry, source selection, material quote or unrelated command ACK. */
    void materialOrderMetadata(HologramState next, long orderCommand) {
        if (!state.session.equals(next.session) || state.cells != next.cells
            || state.data.getLong("planRevision") != next.data.getLong("planRevision")) return;
        state = next;
        if ("orderMissingMaterials".equals(pendingOperation) && pendingTicket.sequence() == orderCommand) {
            if (pendingTicket.acknowledge(next.session, orderCommand)) commandProgress.clear();
        }
        ui.review.acknowledgeReview(next);
    }

    /** Voucher intent is coalesced separately; unrelated controls and the open MUI dialog remain usable. */
    void selectMaterialVoucher(boolean selected) {
        if (!editable()) return;
        desiredVoucher = selected;
        drainMaterialReviewAction();
    }

    boolean materialVoucherSelected() {
        return desiredVoucher != null ? desiredVoucher
            : state.data.getCompoundTag("decorativeVoucher")
                .getBoolean("selected");
    }

    boolean materialReviewPending() {
        return materialReviewSequence >= 0;
    }

    void refreshMaterialReview() {
        if (!editable()) return;
        materialRefreshQueued = true;
        drainMaterialReviewAction();
    }

    void materialQuoteMetadata(HologramState next, long receiptSequence) {
        if (!state.session.equals(next.session) || state.cells != next.cells
            || revision() != next.data.getLong("planRevision")) return;
        state = next;
        if (("orderMissingMaterials".equals(pendingOperation) || "cancelMaterialOrder".equals(pendingOperation))
            && pendingTicket.sequence() == receiptSequence) {
            if (pendingTicket.acknowledge(next.session, receiptSequence)) commandProgress.clear();
        }
        ui.review.acknowledgeMaterialQuote(next);
        acknowledgeMaterialReview(receiptSequence);
    }

    void saveSourceMetadata(HologramState next) {
        if (state.session.equals(next.session) && state.cells == next.cells
            && revision() == next.data.getLong("planRevision")) state = next;
    }

    private void acknowledgeMaterialReview(long receiptSequence) {
        if (materialReviewSequence < 0 || receiptSequence < materialReviewSequence) return;
        materialReviewSequence = -1;
        // A refused intent is reported by the server, not retried forever. Only a newer click is queued.
        if (desiredVoucher != null && desiredVoucher.equals(submittedVoucher)) desiredVoucher = null;
        submittedVoucher = null;
        drainMaterialReviewAction();
    }

    private void drainMaterialReviewAction() {
        if (materialReviewSequence >= 0 || !editable()) return;
        boolean serverSelected = state.data.getCompoundTag("decorativeVoucher")
            .getBoolean("selected");
        String op;
        NBTTagCompound request = new NBTTagCompound();
        if (desiredVoucher != null && desiredVoucher != serverSelected) {
            op = "setDecorativeVoucher";
            request.setBoolean("enabled", desiredVoucher);
        } else {
            desiredVoucher = null;
            if (!materialRefreshQueued) return;
            op = "refreshMaterialQuote";
        }
        materialRefreshQueued = false;
        request.setLong("planRevision", revision());
        request.setLong("placementReviewNonce", state.data.getLong("placementReviewNonce"));
        long before = sequence;
        send(op, request, false);
        if (sequence > before) {
            materialReviewSequence = sequence;
            materialReviewRevision = revision();
            submittedVoucher = "setDecorativeVoucher".equals(op) ? request.getBoolean("enabled") : null;
            notice = "";
        }
    }

    private void clearMaterialReviewAction() {
        materialReviewSequence = -1;
        desiredVoucher = null;
        submittedVoucher = null;
        materialRefreshQueued = false;
    }

    boolean areaImporting() {
        return this.state.data.getBoolean("areaImporting");
    }

    boolean editable() {
        if (author != null) return author.editable();
        if (authorSourceRequired) return false;
        if (HologramBackgroundTasks.busy()) return false;
        int job = this.state.data.getInteger("job");
        return !ui.session.areaImporting() && !this.state.data.getBoolean("targetClosed")
            && !this.state.data.getBoolean("areaSelectionShell")
            && !this.state.data.getBoolean("attachmentPending")
            && job != 1
            && job != 2
            && pendingTicket.sequence() < 0
            && !this.state.data.getBoolean("capturePending");
    }

    long revision() {
        return this.state.data.getLong("planRevision");
    }

    HologramState.Cell selectedCell() {
        return ui.preview.selected() >= 0 && ui.preview.selected() < this.state.cells.size()
            ? this.state.cells.get(ui.preview.selected())
            : null;
    }

    NBTTagCompound cellTag() {
        if (author != null) return ui.config.selectedNativeDetails();
        HologramState.Cell cell = selectedCell();
        return cell == null ? new NBTTagCompound() : cell.tag();
    }

    String editingReason() {
        if (author != null) return author.diagnostic();
        if (authorSourceRequired) return "正在取得完整作者源；失败时原源保留，可关闭并重新打开重试";
        if (state.data.getBoolean("attachmentPending")) return "正在生成和校验文字或图片；可按 Esc 取消后再编辑。";
        if (this.state.data.getBoolean("targetClosed")) return "控制器已拆除；保留最终统计，重新右键新控制器开启任务。";
        if (pendingTicket.sequence() >= 0) return "等待服务端回执后可操作。";
        if (this.state.data.getBoolean("capturePending")) return "正在采集结构，请稍候。";
        if (this.state.data.getInteger("job") == 1 || this.state.data.getInteger("job") == 2)
            return "施工任务中；取消任务后可修改蓝图。";
        return "当前目标不支持此操作。";
    }

    String phaseName() {
        String phase = this.state.data.getString("phase");
        if (phase.equals("PREPARE")) return "等待服务端执行";
        if (phase.equals("ACK")) return "已收到施工结果";
        if (phase.equals("GAP")) return "等待服务端执行";
        return phase;
    }

    void requestAuthorSource(long taskToken) {
        if (!authorSourceRequired || author != null)
            throw new IllegalStateException("Not an author-source acquisition");
        NBTTagCompound request = new NBTTagCompound();
        request.setString("op", "acquireAuthorSource");
        request.setString("session", state.session);
        request.setLong("planRevision", revision());
        sequence = Math.max(sequence, state.data.getLong("uiSequence"));
        request.setLong("uiSequence", ++sequence);
        request.setLong("editorWorkspaceEpoch", state.data.getLong("editorWorkspaceEpoch"));
        request.setLong("clientTaskToken", taskToken);
        HologramBackgroundTasks.command(state.session, sequence, "acquireAuthorSource");
        HologramNetwork.sendAction(request);
    }

    /** Explicit author-widget port. This method never prepares or sends a wire command. */
    void localAction(String operation, NBTTagCompound supplied) {
        if (author == null) throw new IllegalStateException("No local author document");
        NBTTagCompound request = supplied == null ? new NBTTagCompound() : supplied;
        if (HologramLocalAuthorAttachmentActions.execute(author, ui, operation, request)) return;
        if (HologramLocalAuthorActions.execute(author, ui, operation, request)) return;
        if (HologramLocalAuthorConfigurationActions.execute(author, ui, operation, request)) return;
        if (HologramLocalAuthorNativeActions.handles(operation)) {
            HologramLocalAuthorNativeActions.execute(author, ui, operation, request);
            return;
        }
        notice = "此操作需要显式的世界、审核或导出边界：" + operation;
    }

    void send(String op, NBTTagCompound request, boolean pending) {
        if (tutorialSink != null) {
            tutorialSink.accept(op, request == null ? new NBTTagCompound() : (NBTTagCompound) request.copy());
            return;
        }
        if (author != null) {
            // This is a safety fence for remaining legacy widgets, not a local wire/ACK emulator.
            // Ordinary edit widgets call the typed local runtime directly.
            if ("close".equals(op) || "dismissPresetBrowse".equals(op)) return;
            localAction(op, request);
            return;
        }
        if (authorSourceRequired && !"close".equals(op) && !"cancelSourceSwitch".equals(op)) {
            notice = editingReason();
            return;
        }
        if (!ui.diagnostics.captureRenderSmokeAction() && !HologramBackgroundTasks.commandAllowed(op)) return;
        sequence = Math.max(sequence, HologramBackgroundTasks.commandSequence(state.session));
        if ("switchEditorWorkspace".equals(op)) sequence = Math.max(sequence, HologramClient.editorWorkspaceSequence());
        sequence = Math
            .max(sequence, Math.max(state.data.getLong("uiSequence"), state.data.getLong("materialQuoteSequence")));
        if (areaImporting() && !"close".equals(op) && !"cancelAeSave".equals(op) && !"aePreviewClose".equals(op))
            return;
        if (!ui.ae.commandAllowed(op)) return;
        ui.preview.prepareCommand(op);
        NBTTagCompound n = request == null ? new NBTTagCompound() : request;
        ui.processing.prepareCommand(op, n);
        ui.nbt.prepareCommand(op, n);
        ui.library.prepareCommand(n);
        ui.config.prepareCommand(op, n, sequence + 1);
        buildOutgoingRequest(
            op,
            n,
            ui.preview.selected(),
            () -> ui.preview.selection()
                .indices(),
            state.data.getString("activeStructure"),
            state.session);
        if (HologramBackgroundTasks.libraryTask() && (op.startsWith("saveBlueprint") || "savePreset".equals(op)
            || "updatePreset".equals(op)
            || "exportDraft".equals(op)
            || "confirmAeSave".equals(op))) n.setLong("clientTaskToken", HologramBackgroundTasks.token());
        n.setLong("uiSequence", ++sequence);
        n.setLong("editorWorkspaceEpoch", state.data.getLong("editorWorkspaceEpoch"));
        ui.preview.recordSelectionCommand(op, sequence);
        if (pending) {
            notice = "";
            pendingSequence(sequence);
            pendingOperation = op;
            if (HologramLibraryClient.saveOperation(op) || "confirmAeSave".equals(op)) {
                libraryTaskToken = HologramLibraryClient.INSTANCE.backgroundTaskToken();
                libraryTaskSequence = sequence;
                libraryTaskSession = state.session;
                if (HologramLibraryClient.saveOperation(op)) libraryTaskPresetSequence = sequence;
            }
            String phase = "scan".equals(op) || "processBlueprint".equals(op) ? "analysis"
                : "configure".equals(op) || "settings".equals(op) ? "settings"
                    : op.startsWith("saveBlueprint") ? "library.prepare" : "ack";
            commandProgress.update(state.session + ":" + sequence, phase, 0, 0, "", false, System.nanoTime());
            if (!"orderMissingMaterials".equals(op) && !"cancelMaterialOrder".equals(op)
                && !"refreshMaterialQuote".equals(op)
                && !"setDecorativeVoucher".equals(op)) ui.review.commandPending();
        }
        if (ui.diagnostics.captureRenderSmokeAction())
            ui.diagnostics.capturedRenderSmokeAction((NBTTagCompound) n.copy());
        else {
            HologramBackgroundTasks.command(state.session, sequence, op);
            HologramNetwork.sendAction(n);
        }
    }

    /** Selection arrays are materialized only for server operations that consume them. */
    static NBTTagCompound buildOutgoingRequest(String op, NBTTagCompound request, int selected,
        java.util.function.Supplier<int[]> selection, String structure, String session) {
        if (!request.hasKey("selected")) request.setInteger("selected", selected);
        if (!request.hasKey("selectedIndices") && selectionConsumer(op))
            request.setIntArray("selectedIndices", selection.get());
        if (!request.hasKey("structureId")) request.setString("structureId", structure);
        request.setString("session", session);
        request.setString("op", op);
        return request;
    }

    private static boolean selectionConsumer(String op) {
        // Dispatcher planning/configuration scope, BlueprintEditor geometry, and AuthoringWorkflow NBT/flags.
        return switch (op) {
            case "configure", "build", "start", "scan", "editCopy", "editCut", "editPaste", "editMove", "restoreCell", "rotateCell", "clearPin", "customPin", "sourcePin", "pin", "hatchFacing", "hatchPreset", "clearHatchPreset", "editCellFlags", "editHintArea", "configureBlueprintCellNbt", "editBlueprintCellNbt" -> true;
            default -> false;
        };
    }

    HologramProgress progress() {
        if (author != null) return author.progress();
        if (HologramBackgroundTasks.busy()) return HologramBackgroundTasks.progress();
        HologramProgress server = HologramClientProgress.view("server", state.session);
        if (server != null && !server.complete) return server;
        HologramProgress transport = HologramClientProgress.view("snapshot", "");
        if (pendingTicket.sequence() >= 0 && transport != null && !transport.complete) return transport;
        HologramProgress command = commandProgress.view(System.nanoTime());
        // A transport ACK is not a successful world operation. Its authoritative status wins immediately.
        return command != null && !command.complete ? command : null;
    }

    void transportRejected(NBTTagCompound event) {
        rejectTransport(event, HologramProgressView.phase("busy"));
    }

    void transportFailed(NBTTagCompound event, String message) {
        rejectTransport(event, message);
    }

    /** A failed presentation transfer releases its own UI request, never a world/save/order task owner. */
    boolean presentationTransportFailed(NBTTagCompound event, String message) {
        if (!event.getBoolean("transportError") || !(event.getString("errorCode")
            .startsWith("snapshot.") || "attachment.upload".equals(event.getString("errorCode")))
            || !event.hasKey("uiSequence", 4)
            || event.getLong("clientTaskToken") != 0
            || HologramBackgroundTasks.busy()
            || event.getString("session")
                .isEmpty()
            || !state.session.equals(event.getString("session"))
            || pendingTicket.sequence() < 0
            || pendingTicket.sequence() != event.getLong("uiSequence")
            || event.hasKey("planRevision", 4) && event.getLong("planRevision") != revision()) return false;
        int presentation = switch (pendingOperation) {
            case "previewPreset", "editPreset", "choosePlacementPreset", "loadPreset", "switchEditorWorkspace", "confirmProjectionSource" -> 1;
            case "lockVirtual", "unlockVirtual" -> 2;
            case "placeTextBox" -> 3;
            default -> 0;
        };
        if (presentation == 0) return false;
        rejectTransport(event, message);
        if (pendingTicket.sequence() >= 0) return false;
        if (presentation == 1) ui.library.cancelLibraryNavigation();
        if (presentation == 3) ui.textBoxes.uploadFailed();
        pendingOperation = "";
        return true;
    }

    /** Exact terminal owner notification; never sends another cancellation or touches a newer command. */
    void libraryTaskFinished(String taskSession, long taskSequence, long taskToken, String detail) {
        if (taskToken == 0 || taskToken != libraryTaskToken
            || taskSequence != libraryTaskSequence
            || !libraryTaskSession.equals(taskSession)
            || !state.session.equals(taskSession)) return;
        boolean cleared = false;
        if (pendingTicket.sequence() == taskSequence
            && (HologramLibraryClient.saveOperation(pendingOperation) || "confirmAeSave".equals(pendingOperation))) {
            pendingTicket.clear();
            commandProgress.clear();
            pendingOperation = "";
            cleared = true;
        }
        if (libraryTaskPresetSequence >= 0 && ui.library.presetSaveSequence() == libraryTaskPresetSequence) {
            ui.library.rejectCommand(libraryTaskPresetSequence);
            cleared = true;
        }
        libraryTaskToken = 0;
        libraryTaskSequence = -1;
        libraryTaskPresetSequence = -1;
        libraryTaskSession = "";
        if (cleared) {
            notice = detail;
            ui.library.libraryResult(detail);
        }
    }

    private void rejectTransport(NBTTagCompound event, String message) {
        if (!event.hasKey("uiSequence", 4)) return;
        long rejected = event.getLong("uiSequence");
        if (state.session.equals(event.getString("session")) && rejected == materialReviewSequence) {
            clearMaterialReviewAction();
            notice = message;
            return;
        }
        if (!pendingTicket.rejectExact(event.getString("session"), rejected)) return;
        if ("switchEditorWorkspace".equals(pendingOperation)) ui.workspaces.acknowledged();
        commandProgress.clear();
        ui.preview.rejectCommand(rejected, message);
        ui.config.rejectCommand(rejected);
        ui.processing.rejectCommand(rejected);
        ui.library.rejectCommand(rejected);
        awaitingScan = false;
        if (pendingOperation.startsWith("saveBlueprint")) HologramLibraryClient.INSTANCE.failDraftReview(message);
        notice = message;
    }

    boolean awaitingScan() {
        return this.awaitingScan;
    }

    boolean awaitingScan(boolean value) {
        this.awaitingScan = value;
        return value;
    }

    String notice() {
        return this.notice;
    }

    String notice(String value) {
        this.notice = value;
        return value;
    }

    String openedPurpose() {
        return this.openedPurpose;
    }

    String openedPurpose(String value) {
        this.openedPurpose = value;
        return value;
    }

    long pendingSequence() {
        return pendingTicket.sequence();
    }

    long pendingSequence(long value) {
        if (value < 0) pendingTicket.clear();
        else pendingTicket.begin(state.session, value);
        return value;
    }

    long sequence() {
        return this.sequence;
    }

    HologramState state() {
        return this.state;
    }

    NBTTagCompound backgroundUiState() {
        NBTTagCompound state = new NBTTagCompound();
        state.setLong("sequence", sequence);
        state.setString("openedPurpose", openedPurpose);
        return state;
    }

    void restoreBackgroundUiState(NBTTagCompound saved) {
        sequence = Math.max(sequence, saved.getLong("sequence"));
        // Do not restore pending tickets: ownership and progress live outside the disposable screen.
        // No command is issued here, including when the saved ticket already completed while closed.
    }
}
