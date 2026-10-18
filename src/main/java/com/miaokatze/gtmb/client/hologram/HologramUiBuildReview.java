package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.client.hologram.HologramUiMemory.UiMemory;

/** Internal review workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiBuildReview {

    private final HologramUiModules ui;

    HologramUiBuildReview(HologramUiModules ui) {
        this.ui = ui;
    }

    private int materialOffset;

    private boolean resourceReview;

    private boolean confirmation;

    private long confirmedRevision = -1;

    private long seenPlacementReviewNonce;

    private final HologramPlacementReviewContext placementReviewContext = new HologramPlacementReviewContext();

    private String lastConfirmationTitle = "";

    private String lastConfirmationAction = "";

    private int[] lastConfirmationBounds = new int[0];

    private String lastCacheReviewSummary = "";
    private int lastCacheReviewColor = HologramMuiTheme.TEXT;

    private boolean lastEmptyCacheReview;

    private NBTTagCompound reviewedCacheQuote;

    private String lastFooterScope = "";

    private String lastReviewedScope = "";

    private List<HologramState.Cell> countedCells;

    private int countedOperations;

    private int countedBlockers;

    boolean retainsPlacementReview(HologramState next) {
        return this.placementReviewContext.retain(
            next.session,
            next.data.getLong("placementReviewNonce"),
            next.data.getBoolean("virtualPlacement") && next.data.getBoolean("virtualLocked"),
            next.data.getBoolean("moving"));
    }

    private HologramMaterialConfirmationDialog materialDialog;
    private NBTTagCompound presentedMaterialState;
    private long presentedMaterialRevision = -1;

    void tickMaterialDialog() {
        updateMaterialReviewPresentation();
        if (ui.host == null || ui.host.parent() == null) return;
        boolean show = confirmation && !ui.nbt.nbtDrawer()
            && !ui.bindings.networkDrawer()
            && !ui.identity.deidentificationDrawer()
            && ui.ae.aeReview() == null
            && ui.ae.aeReviewNavigation() == null
            && !ui.library.discardReview()
            && !ui.library.overwriteReview()
            && !ui.processing.processingPresetSaveReview();
        if (materialDialog == null && show) materialDialog = new HologramMaterialConfirmationDialog(ui);
        if (materialDialog != null) materialDialog.tick(show);
    }

    boolean materialDialogOpen() {
        return materialDialog != null && materialDialog.isOpen();
    }

    boolean consumeMaterialDialogKey(int key) {
        return materialDialog != null && materialDialog.consumeKey(key);
    }

    int[] materialControlPoint(int id) {
        return materialDialog == null ? null : materialDialog.controlPoint(id);
    }

    boolean materialControlEnabled(int id) {
        return materialDialog != null && materialDialog.controlEnabled(id);
    }

    NBTTagCompound materialDialogProbeState() {
        return materialDialog == null ? new NBTTagCompound() : materialDialog.probeState();
    }

    private void updateMaterialReviewPresentation() {
        if (!confirmation) {
            presentedMaterialState = null;
            return;
        }
        NBTTagCompound state = ui.session.state().data;
        if (presentedMaterialState == state && presentedMaterialRevision == confirmedRevision) return;
        presentedMaterialState = state;
        presentedMaterialRevision = confirmedRevision;
        lastConfirmationBounds = HologramPlacementConfirmation.modalBounds(state);
        lastCacheReviewSummary = HologramPlacementConfirmation.cacheSummary(state);
        lastCacheReviewColor = HologramPlacementConfirmation.cacheSummaryColor(state);
        lastEmptyCacheReview = HologramPlacementConfirmation.emptyCacheFallback(state);
        reviewedCacheQuote = HologramPlacementConfirmation.cacheReviewSnapshot(state);
        lastReviewedScope = constructionScopeLabel(true);
        lastConfirmationTitle = HologramPlacementConfirmation.forceReview(state, confirmedRevision) ? "缺料 · 确认仅放已有"
            : placementReviewActive() ? "蓝图放置预检 · " + state.getString("virtualPresetName") : "材料与改动";
        lastConfirmationAction = HologramPlacementConfirmation
            .confirmationAction(state, confirmedRevision, placementReviewActive());
    }

    void drawConfirmation(int mx, int my) {
        updateMaterialReviewPresentation();
        // The independent MUI child owns rendering, scroll clipping and all review input.
        // No material names are resolved from the legacy numeric-id material list.
        if (!materialDialogOpen()) ui.canvas.text("正在打开材料确认…", 50, 68, 315, HologramUiAccent.SUGGESTION);
    }

    int operationCount() {
        List<HologramState.Cell> cells = ui.session.state().cells;
        // Parsed cell pages are immutable; metadata-only updates and rendered frames reuse this count.
        if (countedCells == cells) return countedOperations;
        int operations = 0;
        countedBlockers = 0;
        // Geometry lives in the parsed cell pages, not in the metadata-only NBT view.
        for (HologramState.Cell cell : cells) if (cell.inScope) {
            if ("blocked".equals(cell.status)) countedBlockers++;
            if (("PLACE".equals(cell.operation) || "REPLACE".equals(cell.operation) || "REMOVE".equals(cell.operation))
                && ("pending".equals(cell.status) || "missing".equals(cell.status))) operations++;
        }
        countedCells = cells;
        return countedOperations = operations;
    }

    boolean confirmationAllowed(int operations) {
        return ui.session.editable() && !ui.session.materialReviewPending()
            && (ui.session.state().data.getBoolean("blueprintAwaitingConfirmation") || operations > 0)
            && this.confirmedRevision == ui.session.revision()
            && HologramPlacementConfirmation.cacheReviewMatches(ui.session.state().data, this.reviewedCacheQuote)
            && HologramPlacementConfirmation.networksReady(ui.session.state().data)
            && (ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")
                || HologramPlacementConfirmation.canBuild(ui.session.state().data, this.confirmedRevision))
            && (ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")
                || !ui.session.state().data.getBoolean("virtual")
                || ui.session.state().data.getBoolean("virtualPlacement")
                    && ui.session.state().data.getBoolean("virtualLocked"));
    }

    int materialBlockers() {
        operationCount();
        return countedBlockers;
    }

    boolean placementReviewActive() {
        return ui.session.state().data.getBoolean("virtualPlacement")
            && ui.session.state().data.getBoolean("virtualLocked")
            && ui.session.state().data.getBoolean("blueprintAwaitingConfirmation");
    }

    String placementPosition() {
        return ui.session.state().data.getInteger("x") + ", "
            + ui.session.state().data.getInteger("y")
            + ", "
            + ui.session.state().data.getInteger("z");
    }

    String constructionScopeLabel(boolean reviewed) {
        if (ui.session.state().data.getBoolean("wholeBlueprintPlacement")
            || ui.session.state().data.getBoolean("virtualPlacement"))
            return "施工范围：整份蓝图（" + ui.session.state().cells.size() + " 格）";
        int count = ui.preview.selection()
            .size();
        if (reviewed) {
            int scope = ui.session.state().data.getInteger("scope");
            count = scope < 2 ? 0 : ui.session.state().data.getIntArray("selectedIndices").length;
            if (scope >= 2 && count == 0 && ui.session.state().data.getInteger("selected") >= 0) count = 1;
        }
        if (count > 0) return "施工范围：已选 " + count + " 格";
        return ui.session.state().data.getBoolean("virtual") ? "施工范围：整份蓝图" : "施工范围：全部结构";
    }

    String constructionScopeHint() {
        if (ui.session.state().data.getBoolean("wholeBlueprintPlacement")
            || ui.session.state().data.getBoolean("virtualPlacement")) return "确认放置默认全蓝图";
        return ui.preview.selection()
            .size() > 0 ? "只施工所选 · Ctrl+A 全选 · 右键预览清选" : "选择方块可限定范围";
    }

    String completionSummary() {
        String summary = ui.session.state().data.getBoolean("jobPartialPlacement")
            && ui.session.state().data.getInteger("job") >= 3
                ? "部分完成：放置 " + ui.session.state().data.getInteger("completed")
                    + " · 缺料 "
                    + ui.session.state().data.getInteger("jobMissingCells")
                    + " · 保护 "
                    + ui.session.state().data.getInteger("jobProtectedCells")
                    + " · 不支持 "
                    + ui.session.state().data.getInteger("jobUnsupportedCells")
                : HologramUiText.reason(ui.session.state().data.getString("status"));
        String energyNotice = ui.session.state().data.getString("blueprintEnergyNotice");
        return energyNotice.isEmpty() || summary.contains(energyNotice) ? summary : summary + " · " + energyNotice;
    }

    boolean activateControl(int id) {
        if (ui.canvas.rejectLocalPlanAction(id)) return true;
        switch (id) {
            case 162:
                if (ui.session.editable() && !ui.session.materialReviewPending()
                    && ui.session.state().data.getBoolean("materialOrderingAvailable")
                    && this.confirmedRevision == ui.session.revision()) {
                    NBTTagCompound ordering = new NBTTagCompound();
                    ordering.setLong("planRevision", this.confirmedRevision);
                    ordering.setLong("placementReviewNonce", ui.session.state().data.getLong("placementReviewNonce"));
                    ui.session.send("orderMissingMaterials", ordering, true);
                }
                return true;
            case 169:
                this.resourceReview = !this.resourceReview;
                this.materialOffset = 0;
                return true;
            case 135:
                if (ui.session.editable() && ui.session.state().data.getBoolean("virtualPlacement")
                    && !ui.session.state().data.getBoolean("virtualLocked")) ui.session.send("lockVirtual", null, true);
                return true;
            case 50:
                if (ui.session.editable() && (!ui.session.state().data.getBoolean("virtual")
                    || ui.session.state().data.getBoolean("virtualPlacement")
                        && ui.session.state().data.getBoolean("virtualLocked"))) {
                    if (ui.review.placementReviewActive()) {
                        this.confirmedRevision = ui.session.revision();
                        this.confirmation = true;
                    } else
                        if (HologramPlacementConfirmation.forceReview(ui.session.state().data, this.confirmedRevision))
                            this.confirmation = true;
                        else {
                            ui.session.awaitingScan(true);
                            ui.session.send("scan", ui.config.configuration(), true);
                        }
                }
                return true;
            case 51:
                ui.session.send(ui.session.state().data.getInteger("job") == 2 ? "resume" : "pause", null, true);
                return true;
            case 52:
                ui.session.send("cancel", null, true);
                return true;
            case 54:
                this.confirmation = false;
                this.reviewedCacheQuote = null;
                if (ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")) {
                    ui.library.libraryDrawer(true);
                    ui.session.send("cancelBlueprintPlacement", null, true);
                }
                return true;
            case 55:
                if (this.confirmation && !HologramPlacementConfirmation
                    .cacheReviewMatches(ui.session.state().data, this.reviewedCacheQuote)) {
                    ui.session.notice("缓存报价已变化，请重新核对当前确认信息后再放置");
                    return true;
                }
                if (ui.session.editable() && !ui.session.materialReviewPending()
                    && this.confirmation
                    && this.confirmedRevision == ui.session.revision()
                    && HologramPlacementConfirmation.networksReady(ui.session.state().data)
                    && (ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")
                        || HologramPlacementConfirmation.canBuild(ui.session.state().data, this.confirmedRevision))) {
                    NBTTagCompound n = new NBTTagCompound();
                    n.setLong("planRevision", this.confirmedRevision);
                    n.setLong("placementReviewNonce", ui.session.state().data.getLong("placementReviewNonce"));
                    n.setBoolean(
                        "forceExplicit",
                        HologramPlacementConfirmation.forceReview(ui.session.state().data, this.confirmedRevision));
                    n.setBoolean(
                        "allowEmptyCache",
                        HologramPlacementConfirmation.emptyCacheFallback(ui.session.state().data));
                    this.confirmation = false;
                    this.reviewedCacheQuote = null;
                    ui.session.send(
                        ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")
                            ? "confirmBlueprintPlacement"
                            : "build",
                        n,
                        true);
                }
                return true;
            default:
                return false;
        }
    }

    boolean consumeConfirmationMouse(int mx, int my, int button) {
        if (this.confirmation && !ui.nbt.nbtDrawer()) {
            if (button == 0) for (int i = ui.canvas.controls()
                .size() - 1; i >= 0; i--) {
                    Control control = ui.canvas.controls()
                        .get(i);
                    if (control.contains(mx, my)) {
                        if (control.enabled && (control.id == 54 || control.id == 55
                            || control.id == 162
                            || control.id == 168
                            || control.id == 196
                            || control.id == 211
                            || control.id == 215
                            || control.id == 184
                            || control.id == 169)) ui.router.activate(control.id);
                        return true;
                    }
                }
            return true;
        }
        return false;
    }

    void resetSession(long nonce) {
        seenPlacementReviewNonce = nonce;
        confirmedRevision = -1;
    }

    void pauseForImport() {
        confirmation = false;
    }

    void acknowledgeReview(HologramState next) {
        if (confirmation && confirmedRevision != ui.session.revision()) confirmation = false;
        if (next.data.getBoolean("blueprintAwaitingConfirmation")
            && !next.data.getBoolean("presetBrowseForPlacement")) {
            confirmedRevision = ui.session.revision();
            materialOffset = 0;
            confirmation = true;
        }
    }

    void acknowledgeMaterialQuote(HologramState next) {
        // A quote receipt changes the reviewed nonce and amounts while retaining the current presentation.
        // It never opens a review that the player has already dismissed.
        if (confirmation && confirmedRevision == next.data.getLong("planRevision")) updateMaterialReviewPresentation();
    }

    void acknowledgeScan(HologramState next) {
        if (ui.session.awaitingScan() && ui.session.pendingSequence() < 0 && !next.data.getBoolean("capturePending")) {
            ui.session.awaitingScan(false);
            if (next.data.getBoolean("supported")) {
                confirmedRevision = ui.session.revision();
                materialOffset = 0;
                confirmation = true;
            }
        }
    }

    boolean confirmation() {
        return this.confirmation;
    }

    boolean confirmation(boolean value) {
        this.confirmation = value;
        return value;
    }

    long confirmedRevision() {
        return this.confirmedRevision;
    }

    long confirmedRevision(long value) {
        this.confirmedRevision = value;
        return value;
    }

    String lastCacheReviewSummary() {
        return this.lastCacheReviewSummary;
    }

    int lastCacheReviewColor() {
        return lastCacheReviewColor;
    }

    String lastConfirmationAction() {
        return this.lastConfirmationAction;
    }

    int[] lastConfirmationBounds() {
        return this.lastConfirmationBounds;
    }

    String lastConfirmationTitle() {
        return this.lastConfirmationTitle;
    }

    boolean lastEmptyCacheReview() {
        return this.lastEmptyCacheReview;
    }

    String lastFooterScope() {
        return this.lastFooterScope;
    }

    String lastFooterScope(String value) {
        this.lastFooterScope = value;
        return value;
    }

    String lastReviewedScope() {
        return this.lastReviewedScope;
    }

    int materialOffset() {
        return this.materialOffset;
    }

    int materialOffset(int value) {
        this.materialOffset = value;
        return value;
    }

    HologramPlacementReviewContext placementReviewContext() {
        return this.placementReviewContext;
    }

    boolean resourceReview() {
        return this.resourceReview;
    }

    boolean resourceReview(boolean value) {
        this.resourceReview = value;
        return value;
    }

    NBTTagCompound reviewedCacheQuote() {
        return this.reviewedCacheQuote;
    }

    long seenPlacementReviewNonce() {
        return this.seenPlacementReviewNonce;
    }

    long seenPlacementReviewNonce(long value) {
        this.seenPlacementReviewNonce = value;
        return value;
    }

    void initializeReview() {
        if (ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")
            && !ui.session.state().data.getBoolean("presetBrowseForPlacement")) {
            ui.review.confirmation(true);
            ui.review.confirmedRevision(ui.session.revision());
        }
    }

    void commandPending() {
        confirmation = false;
    }

    void restoreInitialUi(UiMemory memory) {
        seenPlacementReviewNonce = memory.reviewNonce;
    }

    NBTTagCompound backgroundUiState() {
        NBTTagCompound state = new NBTTagCompound();
        state.setLong("revision", ui.session.revision());
        state.setLong("nonce", seenPlacementReviewNonce);
        state.setInteger("materialOffset", materialOffset);
        state.setBoolean("resourceReview", resourceReview);
        state.setBoolean("confirmation", confirmation);
        return state;
    }

    void restoreBackgroundUiState(NBTTagCompound state) {
        materialOffset = Math.max(0, state.getInteger("materialOffset"));
        // A fresh server review or completed job must win over the closed presentation.
        if (state.getLong("revision") != ui.session.revision() || state.getLong("nonce") != seenPlacementReviewNonce)
            return;
        int job = ui.session.state().data.getInteger("job");
        if (job == 0) {
            resourceReview = state.getBoolean("resourceReview");
            confirmation |= state.getBoolean("confirmation");
        }
    }
}
