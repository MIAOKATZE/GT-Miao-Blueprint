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

    private boolean awaitingScan;

    private String notice = "";

    private long sequence;

    private final HologramUiTicket pendingTicket = new HologramUiTicket();

    private String openedPurpose = "";

    void initialize(HologramState snapshot) {
        state = snapshot;
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
        sequence = snapshot.data.getLong("uiSequence");
        ui.preview.resetCamera();
        ui.config.syncTargetSelectors();
        ui.library.applyOpeningPurpose();
        ui.ae.receiveAeReview();
        ui.identity.offerPlacementDeidentification();
        ui.library.acceptSourceSwitchProposal();
    }

    void updateState(HologramState next) {
        HologramUiNetworkBindings.SnapshotTransition bindings = ui.bindings.captureSnapshotTransition();
        HologramUiLibrary.SnapshotTransition library = ui.library.prepareSnapshot(next);
        HologramState.Cell previousTarget = selectedCell();
        int previousSelection = ui.preview.selected();
        Integer previousChoice = ui.identity.prepareSnapshot(library.sourceChanged);
        boolean newSession = !state.session.equals(next.session);
        if (newSession) {
            ui.identity.resetSession();
            ui.memory.rememberUi();
            ui.library.restoreSession(next, library);
            ui.annotations.resetSession();
            ui.preview.resetSession(next);
            pendingTicket.clear();
        }
        state = next;
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
        pendingTicket.acknowledge(next.session, next.data.getLong("uiSequence"));
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
    }

    static boolean sameTarget(HologramState.Cell before, HologramState.Cell after) {
        if (before == null || after == null) return before == after;
        if (before.targetFacing != after.targetFacing || before.wantMeta != after.wantMeta
            || !before.wantId.equals(after.wantId)) return false;
        if (before.targetStack == null || after.targetStack == null) return before.targetStack == after.targetStack;
        return before.targetStack.isItemEqual(after.targetStack)
            && ItemStack.areItemStackTagsEqual(before.targetStack, after.targetStack);
    }

    boolean areaImporting() {
        return this.state.data.getBoolean("areaImporting");
    }

    boolean editable() {
        int job = this.state.data.getInteger("job");
        return !ui.session.areaImporting() && !this.state.data.getBoolean("targetClosed")
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
        return ui.preview.selected() < 0 ? new NBTTagCompound()
            : this.state.data.getTagList("cells", 10)
                .getCompoundTagAt(ui.preview.selected());
    }

    String editingReason() {
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

    void send(String op, NBTTagCompound request, boolean pending) {
        if (areaImporting() && !"close".equals(op) && !"cancelAeSave".equals(op) && !"aePreviewClose".equals(op))
            return;
        if (!ui.ae.commandAllowed(op)) return;
        ui.review.prepareCommand(op);
        ui.preview.prepareCommand(op);
        NBTTagCompound n = request == null ? new NBTTagCompound() : request;
        ui.processing.prepareCommand(op, n);
        ui.nbt.prepareCommand(op, n);
        ui.library.prepareCommand(n);
        ui.config.prepareCommand(op, n, sequence + 1);
        if (!n.hasKey("selected")) n.setInteger("selected", ui.preview.selected());
        if (!n.hasKey("selectedIndices")) n.setIntArray(
            "selectedIndices",
            ui.preview.selection()
                .indices());
        if (!n.hasKey("structureId")) n.setString("structureId", state.data.getString("activeStructure"));
        n.setString("session", state.session);
        n.setString("op", op);
        n.setLong("uiSequence", ++sequence);
        ui.preview.recordSelectionCommand(op, sequence);
        if (pending) {
            pendingSequence(sequence);
            ui.review.commandPending();
        }
        if (ui.diagnostics.captureRenderSmokeAction())
            ui.diagnostics.capturedRenderSmokeAction((NBTTagCompound) n.copy());
        else HologramNetwork.sendAction(n);
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
}
