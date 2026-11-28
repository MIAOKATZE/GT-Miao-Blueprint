// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalFrame;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalView;
import com.miaokatze.gtmb.client.hologram.HologramUiMemory.UiMemory;

/** Internal review workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiBuildReview implements LocalView {

    private final HologramUiModules ui;

    HologramUiBuildReview(HologramUiModules ui) {
        this.ui = ui;
    }

    private int materialOffset;

    private boolean resourceReview;

    private boolean confirmation;

    private long confirmedRevision = -1;

    private long seenPlacementReviewNonce;
    private long seenOperationReviewSequence = -1;
    private long requestedOperationReviewSequence = -1;

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

    private HologramMaterialConfirmationDialog materialDialog, localMaterialDialog;
    private String localMaterialNotice = "";
    private HologramLocalMaterialReview localMaterials;
    private HologramClientStockNative.DeclarationSite localStockSite;
    private int nativeRecoveryDestination;
    private int localMaterialSelectionVersion;
    private NBTTagCompound presentedMaterialState;
    private long presentedMaterialRevision = -1;

    boolean nativeMaterialInspectionReady() {
        HologramState state = ui.session.state();
        if (state.data.getBoolean("localStandardController")) return state.data.getBoolean("nativeTargetReady")
            || state.data.getInteger("nativeAction") == 3 && ui.host != null
                && ui.host.parent()
                    .nativeActionBlocker(
                        ui.host.parent()
                            .nativeControllerState()
                            .action())
                    .isEmpty();
        int job = state.data.getInteger("job");
        if (state.nativeViewport != null) return ui.session.localDocument() && state.nativeViewport.currentProviders()
            && !HologramBackgroundTasks.busy();
        // The sealed target supplies whole-structure materials independently from candidate enrichment.
        return state.localMaterialIndex != null && state.localMaterialIndex.current()
            && state.data.getBoolean("localStandardController")
            && state.data.getInteger("mode") == 0
            && state.data.getBoolean("target")
            && state.data.getBoolean("supported")
            && !state.data.getBoolean("localPlanPending")
            && !state.data.getBoolean("targetClosed")
            && !state.data.getBoolean("currentPlacementPending")
            && ui.session.pendingSequence() < 0
            && !HologramBackgroundTasks.busy()
            && job != 1
            && job != 2;
    }

    boolean openNativeLocalMaterials() {
        return openNativeLocalMaterials(false);
    }

    private boolean openNativeLocalMaterials(boolean construction) {
        HologramScreen root = ui.host == null ? null : ui.host.parent();
        HologramState state = ui.session.state();
        if (state.nativeViewport != null && ui.session.localDocument()) {
            closeLocalMaterials();
            localMaterials = new HologramLocalMaterialReview(state, ui.config.configuration(), ui.preview.selection());
            localMaterialSelectionVersion = ui.preview.selection()
                .version();
            return true;
        }
        if (!state.data.getBoolean("localStandardController")) return false;
        if (root == null || !root.localMaterialOwnerCurrent(ui)) {
            closeLocalMaterials();
            ui.session.notice(projectorOr("stock.owner_changed", "控制器或手持投影仪已变化，请重新打开结构。"));
            return true; // A stale local control must not fall through to the legacy query.
        }
        String blocker = HologramPlacementConfirmation.localStructureReviewBlocker(state);
        if (!blocker.isEmpty() && !nativeMaterialInspectionReady()) ui.session.notice(blocker);
        else {
            closeLocalMaterials();
            NBTTagCompound settings = ui.config.configuration();
            boolean sealedWholeTarget = HologramPlacementConfirmation
                .wholeNativeTargetReady(state, settings, root.hasLocalNativeChoices());
            localMaterials = new HologramLocalMaterialReview(
                state,
                settings,
                ui.preview.selection(),
                sealedWholeTarget);
            localStockSite = root.stockNativeSite();
            localMaterialNotice = "";
            localMaterialSelectionVersion = ui.preview.selection()
                .version();
        }
        return true;
    }

    HologramLocalMaterialReview localMaterials() {
        return localMaterials;
    }

    private boolean localMaterialsCurrent() {
        HologramScreen root = ui.host == null ? null : ui.host.parent();
        return localMaterials != null && root != null
            && (ui.session.state().nativeViewport != null ? localMaterialPaneCurrent()
                : root.localMaterialOwnerCurrent(ui))
            && localMaterials.current(ui.session.state(), ui.preview.layer())
            && (ui.session.state().data.getBoolean("localStandardController")
                || localMaterialSelectionVersion == ui.preview.selection()
                    .version()
                || localMaterials.wholeLedgerStillSelected(ui.config.configuration()));
    }

    void closeLocalMaterials() {
        if (localMaterials != null) localMaterials.close();
        localMaterials = null;
        localStockSite = null;
        localMaterialNotice = "";
        if (localMaterialDialog != null) localMaterialDialog.close();
    }

    private boolean localMaterialPaneCurrent() {
        HologramScreen root = ui.host == null ? null : ui.host.parent();
        return root != null && root.ui == ui
            && ui.host.minecraft() != null
            && ui.host.minecraft().currentScreen == root
            && (root.localControllerStandard()
                || ui.session.localDocument() && ui.session.state().nativeViewport != null);
    }

    private void retireStaleLocalMaterials() {
        if (localMaterials == null) return;
        HologramScreen root = ui.host == null ? null : ui.host.parent();
        if (localMaterialsCurrent()) return;
        if (!localMaterialPaneCurrent()) {
            closeLocalMaterials();
            return;
        }
        String blocker = HologramPlacementConfirmation.localStructureReviewBlocker(ui.session.state());
        localMaterialNotice = !root.localMaterialOwnerCurrent(ui)
            ? projectorOr("stock.owner_changed", "控制器或手持投影仪已变化，请重新打开结构。")
            : !blocker.isEmpty() ? blocker + " " + projectorOr("stock.reopen_back_tip", "返回后请重新打开材料窗口。")
                : projectorOr("stock.scope_changed", "施工范围或计划已变化，返回后请重新打开材料窗口。");
        // Retire the exact asynchronous ticket before dropping the pane's last reference.
        localMaterials.close();
        localMaterials = null;
        localStockSite = null;
    }

    void refreshLocalMaterials() {
        retireStaleLocalMaterials();
        if (localMaterials == null) return;
        net.minecraft.entity.player.EntityPlayer player = ui.host.minecraft().thePlayer;
        if (player != null) localMaterials.refresh(player.inventory.mainInventory, player.inventory.currentItem);
    }

    @Override
    public LocalFrame localFrame() {
        retireStaleLocalMaterials();
        if (localMaterials == null) return localMaterialNotice.isEmpty() || !localMaterialPaneCurrent() ? null
            : new LocalFrame(
                new NBTTagCompound(),
                projectorOr("stock.gone", "材料清单已失效"),
                localMaterialNotice,
                projectorOr("stock.reopen_back", "返回后重新打开材料窗口"),
                false);
        String status = !localMaterialNotice.isEmpty() ? localMaterialNotice : localMaterials.status();
        String scope = localStockSite == null ? localMaterials.scopeLabel()
            : localMaterials.scopeLabel() + " · " + localStockSite.description();
        if (ui.session.state().data.getBoolean("localStandardController")) {
            scope = (projectorOr("native.ui.complete_current_structure", "当前完整结构 ·") + " ")
                + HologramNativeControllerState.label(
                    ui.host.parent()
                        .nativeControllerState()
                        .action());
            if (reviewedNativeRecovery()) scope += " · " + localRecoveryDestinationLabel();
            status = localMaterials.status();
        } else if (localStockSite != null) status += " · " + projectorOr("stock.keep_done", "现场或库存变更时保留已完成方块");
        String blocker = localSubmitBlocker();
        if (!localMaterials.sourceNotice()
            .isEmpty()) status = localMaterials.sourceNotice();
        if (!localMaterialNotice.isEmpty()) status = localMaterialNotice;
        else if (!blocker.isEmpty()) status = blocker;
        else if (localHatchDelta()) status = localMaterials.status();
        return new LocalFrame(
            localMaterials.visible(),
            scope,
            status,
            localMaterials.empty() ? projectorOr("stock.no_scope_materials", "所选范围没有目标材料")
                : projectorOr("stock.no_materials", "本机没有可列出的材料"),
            localMaterials.ready());
    }

    private String localMaterialEditingReason() {
        if (ui.session.state().data.getBoolean("currentPlacementPending"))
            return projectorOr("stock.pending_current", "正在等待当前施工请求，请稍候。");
        if (HologramBackgroundTasks.busy()) return projectorOr("stock.pending_other", "另一个任务正在进行，请稍候。");
        return ui.session.editingReason();
    }

    @Override
    public boolean localSubmitAvailable() {
        return localSubmitBlocker().isEmpty();
    }

    @Override
    public boolean localHatchDelta() {
        return localMaterials != null && localMaterials.hatchDelta != null;
    }

    @Override
    public String localSubmitBlocker() {
        if (localMaterials == null)
            return localMaterialNotice.isEmpty() ? projectorOr("stock.reopen", "请重新打开材料页。") : localMaterialNotice;
        if (!localMaterialsCurrent()) return projectorOr("stock.site_changed", "施工范围或现场已变化，请重新打开材料页。");
        NBTTagCompound settings = localMaterials.settings();
        if (!ui.session.nativeConstructionEditable(settings)) return localMaterialEditingReason();
        if (ui.session.state().nativeViewport != null) {
            // Saved file placement does not carry replacement/demolition authority. Never convert a different
            // requested operation into a build just because the material panel displays a native viewport.
            if (settings.getInteger("mode") != 0 || ui.session.state().data.getInteger("mode") != 0)
                return projectorOr("stock.saved_mode_unsupported", "已保存蓝图的施工入口暂不支持换装或拆除。");
            return ui.library.savedNativePlacementReady() ? "" : projectorOr("stock.save_first_reason", "请先保存蓝图。");
        }
        HologramScreen root = ui.host == null ? null : ui.host.parent();
        if (localStockSite != null && root != null && root.stockNativePending())
            return projectorOr("stock.await_result", "正在等候本次施工结果。");
        if (ui.session.state().data.getBoolean("localStandardController"))
            return root == null ? projectorOr("native.ui.the_controller_session_expired", "控制器会话已失效。")
                : root.nativeActionBlocker(
                    root.nativeControllerState()
                        .action());
        String blocker = HologramPlacementConfirmation
            .localStructureReviewBlocker(ui.session.state(), settings, root == null || root.hasLocalNativeChoices());
        if (!blocker.isEmpty()) return blocker;
        return localHatchDelta() ? localMaterials.hatchDelta.blocker : "";
    }

    @Override
    public void localSubmitDenied() {
        String blocker = localSubmitBlocker();
        if (!blocker.isEmpty()) {
            localMaterialNotice = blocker;
            ui.session.notice(blocker);
            com.miaokatze.gtmb.hologram.HologramStockActionTrace.event("client.blocked", null, "ineligible");
        }
    }

    @Override
    public boolean localOrderAvailable() {
        return localMaterialsCurrent() && localMaterials.orderAvailable();
    }

    @Override
    public String localOrderLabel() {
        return localMaterials == null ? "AE" : localMaterials.orderLabel();
    }

    @Override
    public String localSubmitLabel() {
        if (ui.session.state().nativeViewport != null)
            return ui.library.savedNativePlacementReady() ? projectorOr("stock.position_build", "定位并施工")
                : projectorOr("stock.save_first", "请先保存蓝图");
        if (ui.session.state().data.getBoolean("localStandardController"))
            return HologramNativeControllerState.confirmationLabel(
                ui.host.parent()
                    .nativeControllerState()
                    .action());
        if (reviewedNativeMode(2)) return projectorOr("stock.confirm_remove", "确认拆除");
        if (reviewedNativeMode(1)) return projectorOr("stock.confirm_replace", "确认换装");
        return buildLabel(ui.session.state().data);
    }

    static String buildLabel(NBTTagCompound site) {
        if (site.getInteger("siteMatchingSolid") > 0 || site.getInteger("siteHatches") > 0)
            return projectorOr("stock.confirm_fill", "确认补建");
        if (!site.hasKey("siteOccupied", 3) || site.getInteger("siteUnknown") > 0)
            return projectorOr("stock.confirm_build", "确认施工");
        return projectorOr("stock.confirm_new", "确认新建");
    }

    private boolean reviewedNativeMode(int mode) {
        return localMaterials != null && ui.session.state().data.getBoolean("localStandardController")
            && ui.session.state().data.getInteger("mode") == mode;
    }

    @Override
    public boolean localRecoveryAvailable() {
        return reviewedNativeRecovery() && localMaterialsCurrent()
            && !ui.session.state().data.getBoolean("currentPlacementPending")
            && (ui.host == null || !ui.host.parent()
                .stockNativePending());
    }

    private boolean reviewedNativeRecovery() {
        int action = ui.session.state().data.getInteger("nativeAction");
        return localMaterials != null && ui.session.state().data.getBoolean("localStandardController")
            && action >= 1
            && action <= 3;
    }

    @Override
    public String localRecoveryDestinationLabel() {
        return switch (nativeRecoveryDestination) {
            case 1 -> projectorOr("stock.recover_ae", "回收至AE");
            case 2 -> projectorOr("stock.recover_backpack", "回收至背包容器");
            default -> projectorOr("stock.recover_inventory", "回收至物品栏");
        };
    }

    int nativeRecoveryDestination() {
        return nativeRecoveryDestination;
    }

    @Override
    public void localAction(LocalAction action) {
        switch (action) {
            case BACK -> closeLocalMaterials();
            case REFRESH -> {
                refreshLocalMaterials();
                if (localMaterials != null) localMaterials.refreshSources();
            }
            case ORDER -> {
                if (localMaterialsCurrent()) localMaterials.order();
            }
            case SUBMIT -> submitLocalMaterials();
            case RECOVERY_DESTINATION -> {
                if (localRecoveryAvailable()) nativeRecoveryDestination = (nativeRecoveryDestination + 1) % 3;
            }
        }
    }

    void submitLocalMaterials() {
        HologramScreen root = ui.host == null ? null : ui.host.parent();
        if (root != null) submitLocalMaterials(
            settings -> ui.session.state().nativeViewport != null ? ui.library.placeNativeSaved()
                : root.confirmStockNative(settings, localStockSite));
    }

    void stockNativeNotice(String message) {
        ui.session.notice(message);
        if (localStockSite != null && localMaterials != null) localMaterialNotice = message;
    }

    void submitLocalMaterials(java.util.function.Predicate<NBTTagCompound> dispatch) {
        try (var ignored = com.miaokatze.gtmb.hologram.HologramPlacementTiming.clientClick()) {
            retireStaleLocalMaterials();
            if (!localSubmitAvailable()) {
                if (localMaterials != null || !localMaterialNotice.isEmpty()) localSubmitDenied();
                return;
            }
            com.miaokatze.gtmb.hologram.HologramStockActionTrace.event("client.click", null, "confirm");
            ui.session.notice("");
            // True means only that this local action sent or scheduled the request, never server acceptance.
            NBTTagCompound settings = localMaterials.settings();
            if (reviewedNativeRecovery()) settings.setInteger("demolitionDestination", nativeRecoveryDestination);
            if (dispatch.test(settings)) {
                // A cold PROBE is not placement acceptance; leave its status or refusal in the existing dialog.
                HologramScreen root = ui.host == null ? null : ui.host.parent();
                if (localStockSite == null || root == null || !root.stockNativePending()) closeLocalMaterials();
            } else {
                localMaterialNotice = ui.session.notice();
                if (localMaterialNotice.isEmpty())
                    localMaterialNotice = projectorOr("stock.submit_failed", "未提交施工，请检查当前状态后重试。");
                // A dispatch error might originate in external content; do not log its arbitrary message.
                com.miaokatze.gtmb.hologram.HologramStockActionTrace
                    .event("client.not_sent", null, "dispatch_declined");
            }
        }
    }

    private HologramMaterialConfirmationDialog activeMaterialDialog() {
        return localMaterialDialog != null && localMaterialDialog.isOpen() ? localMaterialDialog : materialDialog;
    }

    void tickMaterialDialog() {
        updateMaterialReviewPresentation();
        if (ui.host == null || ui.host.parent() == null) return;
        boolean unobstructed = !ui.session.state().data.getBoolean("placementValidating") && !ui.nbt.nbtDrawer()
            && !ui.bindings.networkDrawer()
            && !ui.identity.deidentificationDrawer()
            && ui.ae.aeReview() == null
            && ui.ae.aeReviewNavigation() == null
            && !ui.library.discardReview()
            && !ui.library.overwriteReview()
            && !ui.processing.processingPresetSaveReview();
        retireStaleLocalMaterials();
        boolean local = (localMaterials != null || !localMaterialNotice.isEmpty() && localMaterialPaneCurrent())
            && !confirmation
            && unobstructed;
        net.minecraft.entity.player.EntityPlayer player = ui.host.minecraft().thePlayer;
        if (local && player != null) {
            if (localMaterials != null)
                localMaterials.tick(player.inventory.mainInventory, player.inventory.currentItem);
            if (localMaterialDialog == null) localMaterialDialog = new HologramMaterialConfirmationDialog(
                ui.host.parent()
                    .panel(),
                ui,
                this);
        }
        if (localMaterialDialog != null) localMaterialDialog.tick(local && player != null);
        boolean show = confirmation && unobstructed;
        if (materialDialog == null && show) materialDialog = new HologramMaterialConfirmationDialog(ui);
        if (materialDialog != null) materialDialog.tick(show);
    }

    boolean materialDialogOpen() {
        return activeMaterialDialog() != null && activeMaterialDialog().isOpen();
    }

    boolean consumeMaterialDialogKey(int key) {
        return activeMaterialDialog() != null && activeMaterialDialog().consumeKey(key);
    }

    boolean consumeMaterialDialogBack(ModularPanel panel) {
        return activeMaterialDialog() != null && activeMaterialDialog().consumeBack(panel);
    }

    int[] materialControlPoint(int id) {
        return activeMaterialDialog() == null ? null : activeMaterialDialog().controlPoint(id);
    }

    boolean materialControlEnabled(int id) {
        return activeMaterialDialog() != null && activeMaterialDialog().controlEnabled(id);
    }

    NBTTagCompound materialDialogProbeState() {
        return activeMaterialDialog() == null ? new NBTTagCompound() : activeMaterialDialog().probeState(ui);
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
        lastConfirmationTitle = HologramPlacementConfirmation.forceReview(state, confirmedRevision)
            ? projectorOr("native.ui.shortage_place_available_only", "缺料 · 确认仅放已有")
            : placementReviewActive()
                ? (projectorOr("native.ui.blueprint_placement_review", "蓝图放置预检 ·") + " ")
                    + state.getString("virtualPresetName")
                : projectorOr("native.ui.materials_and_changes", "材料与改动");
        lastConfirmationAction = HologramPlacementConfirmation
            .confirmationAction(state, confirmedRevision, placementReviewActive());
    }

    void drawConfirmation(int mx, int my) {
        updateMaterialReviewPresentation();
        // The independent MUI child owns rendering, scroll clipping and all review input.
        // No material names are resolved from the legacy numeric-id material list.
        if (!materialDialogOpen()) ui.canvas.text(
            projectorOr("native.ui.opening_material_confirmation", "正在打开材料确认…"),
            50,
            68,
            315,
            HologramUiAccent.SUGGESTION);
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
        if (ui.session.state().data.getBoolean("localStandardController"))
            return projectorOr("native.ui.construction_scope_complete_current_structure", "施工范围：当前完整结构");
        if (ui.session.state().data.getBoolean("wholeBlueprintPlacement")
            || ui.session.state().data.getBoolean("virtualPlacement"))
            return projectorOr("native.ui.construction_scope_entire_blueprint", "施工范围：整份蓝图（")
                + ui.session.state().cells.size()
                + (" " + projectorOr("native.ui.cells", "格）"));
        int count = ui.preview.selection()
            .size();
        if (reviewed) {
            int scope = ui.session.state().data.getInteger("scope");
            count = scope < 2 ? 0 : ui.session.state().data.getIntArray("selectedIndices").length;
            if (scope >= 2 && count == 0 && ui.session.state().data.getInteger("selected") >= 0) count = 1;
        }
        if (count > 0) return (projectorOr("native.ui.construction_scope_selected", "施工范围：已选") + " ") + count
            + (" " + projectorOr("native.ui.cells_61", "格"));
        return ui.session.state().data.getBoolean("virtual")
            ? projectorOr("native.ui.construction_scope_entire_blueprint_62", "施工范围：整份蓝图")
            : projectorOr("native.ui.construction_scope_all_structures", "施工范围：全部结构");
    }

    String constructionScopeHint() {
        if (ui.session.state().data.getBoolean("localStandardController")) return projectorOr(
            "native.ui.configuration_and_presets_update_the_complete_target",
            "配置和预设更新完整目标；施工不受预览选格限制");
        if (ui.session.state().data.getBoolean("wholeBlueprintPlacement")
            || ui.session.state().data.getBoolean("virtualPlacement"))
            return projectorOr("native.ui.placement_defaults_to_the_entire_blueprint", "确认放置默认全蓝图");
        return ui.preview.selection()
            .size() > 0 ? projectorOr("native.ui.build_selected_only_ctrl_a_selects_all", "只施工所选 · Ctrl+A 全选 · 右键预览清选")
                : projectorOr("native.ui.select_blocks_to_limit_the_scope", "选择方块可限定范围");
    }

    String completionSummary() {
        if (ui.session.state().data.getBoolean("localStandardController")) {
            HologramScreen root = ui.host == null ? null : ui.host.parent();
            String stage = root == null ? projectorOr("native.ui.reading_server_structure_state", "正在读取服务器结构状态。")
                : root.nativeStatusCaption();
            return nativeCompletionCaption(
                root != null && root.stockNativePending() ? stage : "",
                ui.session.notice(),
                stage);
        }
        String summary = ui.session.state().data.getBoolean("jobPartialPlacement")
            && ui.session.state().data.getInteger("job") >= 3
                ? (projectorOr("native.ui.partially_complete_placed", "部分完成：放置") + " ")
                    + ui.session.state().data.getInteger("completed")
                    + (" " + projectorOr("native.ui.missing", "· 缺料") + " ")
                    + ui.session.state().data.getInteger("jobMissingCells")
                    + (" " + projectorOr("native.ui.protected", "· 保护") + " ")
                    + ui.session.state().data.getInteger("jobProtectedCells")
                    + (" " + projectorOr("native.ui.unsupported", "· 不支持") + " ")
                    + ui.session.state().data.getInteger("jobUnsupportedCells")
                : HologramUiText.reason(ui.session.state().data.getString("status"));
        String energyNotice = ui.session.state().data.getString("blueprintEnergyNotice");
        return energyNotice.isEmpty() || summary.contains(energyNotice) ? summary : summary + " · " + energyNotice;
    }

    static String nativeCompletionCaption(String progress, String notice, String observedState) {
        if (!progress.isEmpty()) return progress;
        return notice.isEmpty() ? observedState : HologramNativeUiText.reason(notice);
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
                if ((nativeMaterialInspectionReady() || ui.session.editable()) && openNativeLocalMaterials(true))
                    return true;
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
                            NBTTagCompound preflight = ui.config.configuration();
                            if (!ui.session.state().data.getBoolean("virtual"))
                                preflight.setBoolean("operationPreflight", true);
                            long before = ui.session.sequence();
                            ui.session.send("scan", preflight, true);
                            if (preflight.getBoolean("operationPreflight") && ui.session.sequence() > before)
                                requestedOperationReviewSequence = ui.session.sequence();
                        }
                }
                return true;
            case 52:
                if (HologramBackgroundTasks.canCancel()) HologramBackgroundTasks.cancel();
                else if (HologramClientStockNative.OWNER.pending(ui.host.parent()))
                    HologramClientStockNative.OWNER.cancelCurrent();
                else ui.session.send("cancel", null, true);
                return true;
            case 54:
                dismissConfirmation();
                if (ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")) {
                    ui.library.libraryDrawer(true);
                    ui.session.send("cancelBlueprintPlacement", null, true);
                }
                return true;
            case 55:
                if (this.confirmation && !HologramPlacementConfirmation
                    .cacheReviewMatches(ui.session.state().data, this.reviewedCacheQuote)) {
                    ui.session.notice(
                        projectorOr(
                            "native.ui.the_cache_quote_changed_review_the_confirmation",
                            "缓存报价已变化，请重新核对当前确认信息后再放置"));
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
                    n.setBoolean("useDecorationVoucher", ui.session.materialVoucherSelected());
                    n.setBoolean(
                        "forceExplicit",
                        HologramPlacementConfirmation.forceReview(ui.session.state().data, this.confirmedRevision));
                    n.setBoolean(
                        "allowEmptyCache",
                        HologramPlacementConfirmation.emptyCacheFallback(ui.session.state().data));
                    this.confirmation = false;
                    this.reviewedCacheQuote = null;
                    long before = ui.session.sequence();
                    ui.session.send(
                        ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")
                            ? "confirmBlueprintPlacement"
                            : "build",
                        n,
                        true);
                    if (ui.workspaces.hasProjectionContent() && ui.session.sequence() > before)
                        requestedOperationReviewSequence = ui.session.sequence();
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
        closeLocalMaterials();
        seenPlacementReviewNonce = nonce;
        seenOperationReviewSequence = -1;
        requestedOperationReviewSequence = -1;
        confirmedRevision = -1;
    }

    void dismissConfirmation() {
        boolean wasConfirming = confirmation;
        confirmation = false;
        reviewedCacheQuote = null;
        if (ui.session.state().data.getBoolean("virtual") || !ui.workspaces.hasProjectionContent()) return;
        if (!wasConfirming && !ui.session.awaitingScan() && requestedOperationReviewSequence < 0) return;
        ui.session.awaitingScan(false);
        long review = Math.max(requestedOperationReviewSequence, seenOperationReviewSequence);
        if (review < 0) return;
        requestedOperationReviewSequence = -1;
        seenOperationReviewSequence = review;
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("reviewSequence", review);
        ui.session.send("dismissOperationReview", request, false);
    }

    void pauseForImport() {
        closeLocalMaterials();
        confirmation = false;
    }

    void acknowledgeReview(HologramState next) {
        if (next.data.getBoolean("placementValidating")) {
            confirmation = false;
            return;
        }
        if (confirmation && confirmedRevision != ui.session.revision()) confirmation = false;
        if (next.data.getBoolean("operationReviewRequired") && next.data.getBoolean("operationPrepared")
            && next.data.getLong("planRevision") == ui.session.revision()
            && ui.session.pendingSequence() < 0
            && requestedOperationReviewSequence >= 0
            && next.data.getLong("uiSequence") == requestedOperationReviewSequence
            && next.data.getLong("uiSequence") > seenOperationReviewSequence) {
            seenOperationReviewSequence = next.data.getLong("uiSequence");
            confirmedRevision = ui.session.revision();
            materialOffset = 0;
            confirmation = true;
        }
        if (next.data.getBoolean("blueprintAwaitingConfirmation")
            && !next.data.getBoolean("presetBrowseForPlacement")) {
            confirmedRevision = ui.session.revision();
            materialOffset = 0;
            confirmation = true;
        }
    }

    void expectOperationReview(long sequence) {
        requestedOperationReviewSequence = sequence;
    }

    void acknowledgeMaterialQuote(HologramState next) {
        // A quote receipt changes the reviewed nonce and amounts while retaining the current presentation.
        // It never opens a review that the player has already dismissed.
        if (confirmation && confirmedRevision == next.data.getLong("planRevision")) updateMaterialReviewPresentation();
    }

    void acknowledgeScan(HologramState next) {
        if (ui.session.awaitingScan() && ui.session.pendingSequence() < 0 && !next.data.getBoolean("capturePending")) {
            ui.session.awaitingScan(false);
            if (next.data.getBoolean("supported") && !ui.workspaces.hasProjectionContent()) {
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
        closeLocalMaterials();
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
            if (!ui.workspaces.hasProjectionContent()) confirmation |= state.getBoolean("confirmation");
        }
    }
}
