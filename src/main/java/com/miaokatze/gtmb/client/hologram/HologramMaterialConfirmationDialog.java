// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.IParentWidget;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Platform;
import com.cleanroommc.modularui.widget.AbstractScrollWidget;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.github.bsideup.jabel.Desugar;

/** Independent MUI2 material review, with framework scrolling/clipping and bounded visible-row rendering. */
final class HologramMaterialConfirmationDialog {

    private static final int WIDTH = 632, HEIGHT = 446, ROW_HEIGHT = 44, LIST_HEIGHT = 198;
    private static final int LIST_WIDTH = 604, ROW_INSET = 7, ROW_WIDTH = LIST_WIDTH - ROW_INSET * 2 - 6;

    enum LocalAction {
        BACK,
        REFRESH,
        ORDER,
        SUBMIT,
        VOUCHER,
        BIND_GROUP,
        BIND_SLOT,
        RECOVERY_DESTINATION
    }

    /** The port lends its current detached rows for read-only rendering. */
    @Desugar
    record LocalFrame(NBTTagCompound rows, String scope, String status, String emptyLabel, boolean ready,
        List<HologramLibraryMaterialDescription.Row> description) {

        LocalFrame(NBTTagCompound rows, String scope, String status, String emptyLabel, boolean ready) {
            this(rows, scope, status, emptyLabel, ready, null);
        }
    }

    /** Only detached rendering data and explicit user actions cross this boundary. */
    interface LocalView {

        LocalFrame localFrame(); // null if the owning screen/source is no longer current

        boolean localSubmitAvailable();

        default String localSubmitBlocker() {
            return localSubmitAvailable() ? "" : projectorOr("native.ui.cannot_submit_now", "当前无法提交。");
        }

        default boolean localOrderAvailable() {
            return false;
        }

        default String localOrderLabel() {
            return projectorOr("stock.order_ae", "AE 缺料下单");
        }

        default boolean localHatchDelta() {
            return false;
        }

        default void localSubmitDenied() {}

        default String localSubmitLabel() {
            return projectorOr("native.ui.submit_construction_for_verification", "提交施工并核验…");
        }

        default boolean localRecoveryAvailable() {
            return false;
        }

        default String localRecoveryDestinationLabel() {
            return "";
        }

        default boolean hasBindingChoices() {
            return false;
        }

        default String bindingGroupLabel() {
            return "";
        }

        default String bindingSlotLabel() {
            return "";
        }

        default boolean localVoucherAvailable() {
            return false;
        }

        default String localVoucherBlocker() {
            return projectorOr("native.ui.this_operation_does_not_use_decorative_vouchers", "此操作不使用装饰券");
        }

        default boolean localVoucherSelected() {
            return false;
        }

        default String localSourcesLabel() {
            return projectorOr("native.ui.material_sources_verified_by_the_server_during", "材料来源：执行时由服务端核验");
        }

        default String localVoucherLabel() {
            return projectorOr("native.ui.decorative_discount", "装饰抵扣");
        }

        void localAction(LocalAction action); // Revalidate deferred callbacks before using local or server data.
    }

    private final ModularPanel parent;
    private final HologramUiModules ui;
    private final LocalView localView;
    private final boolean local;
    private LocalFrame localFrame;
    private final HologramMaterialReview materials = new HologramMaterialReview();
    private final HologramMaterialRowPresentation descriptionRows = new HologramMaterialRowPresentation();
    private final IPanelHandler handler;
    private final VerticalScrollData scroll = new VerticalScrollData();
    private MaterialList list;
    private RowsCanvas canvas;
    private int hoveredRow = -1;
    private long drawnFrames;
    private int panelBuilds, listResizes;
    private final RenderItem iconRenderer = new RenderItem();
    private final Map<Integer, BooleanSupplier> enabledControls = new HashMap<>();
    private final Map<Integer, IntSupplier> controlAccents = new HashMap<>();
    private final Map<Integer, ButtonWidget<?>> controlWidgets = new HashMap<>();
    private Runnable lessonClose;
    private MaterialPanel livePanel;

    /** Reuses the actual review tree; the isolated lesson replaces only its command boundary. */
    ModularPanel buildLesson(Runnable closeLesson) {
        lessonClose = closeLesson;
        materials.update(state());
        ModularPanel panel = build();
        ui.shell.registerTarget("materials", panel);
        return panel;
    }

    boolean controlEnabled(int id) {
        BooleanSupplier enabled = enabledControls.get(id);
        return isOpen() && drawnFrames > 0 && enabled != null && enabled.getAsBoolean();
    }

    HologramMaterialConfirmationDialog(HologramUiModules ui) {
        this(
            ui.host.parent()
                .panel(),
            ui,
            null);
    }

    HologramMaterialConfirmationDialog(ModularPanel parent, LocalView localView) {
        this(parent, null, java.util.Objects.requireNonNull(localView));
    }

    HologramMaterialConfirmationDialog(ModularPanel parent, HologramUiModules ui, LocalView localView) {
        this.parent = parent;
        this.ui = ui;
        this.localView = localView;
        local = localView != null;
        handler = IPanelHandler.simple(parent, (panel, player) -> build(), true);
        scroll.setScrollSpeed(ROW_HEIGHT);
    }

    void tick(boolean show) {
        int previousRows = rowCount();
        boolean wasDescriptive = descriptive();
        if (local) {
            localFrame = show ? localView.localFrame() : null;
            if (localFrame == null) {
                close();
                return;
            }
        }
        boolean dataResized = !descriptive()
            && (local ? materials.updateLocal(localFrame.rows()) : materials.update(state()));
        boolean resized = dataResized || previousRows != rowCount() || wasDescriptive != descriptive();
        if (resized && canvas != null) {
            listResizes++;
            canvas.height(Math.max(LIST_HEIGHT, rowCount() * ROW_HEIGHT))
                .scheduleResize();
            scroll.setScrollSize(Math.max(LIST_HEIGHT, rowCount() * ROW_HEIGHT));
            scroll.clamp(list.getScrollArea());
            list.scheduleResize();
        }
        if (show && !handler.isPanelOpen()) handler.openPanel();
        else if (!show && handler.isPanelOpen()) handler.closePanel();
    }

    boolean isOpen() {
        return handler.isPanelOpen();
    }

    void close() {
        if (isOpen()) handler.closePanel();
    }

    int[] controlPoint(int id) {
        if (!isOpen() || drawnFrames == 0) return null;
        if (ui != null) {
            int[] bounds = ui.shell.nativeWidgetBounds(controlWidgets.get(id));
            return bounds == null ? null : new int[] { bounds[0] + bounds[2] / 2, bounds[1] + bounds[3] / 2 };
        }
        int[] bounds = switch (id) {
            case 54 -> new int[] { 14, 402, 160 };
            case 55 -> new int[] { 184, 402, 434 };
            case 162 -> new int[] { 234, 340, 160 };
            case 168 -> new int[] { 14, 340, 92 };
            case 196 -> new int[] { 112, 340, 116 };
            case 211 -> new int[] { 14, 369, 102 };
            case 184 -> new int[] { 230, 369, 102 };
            case 245 -> new int[] { 400, 340, 218 };
            case 246 -> new int[] { 476, 369, 142 };
            case 247 -> new int[] { 338, 369, 132 };
            case 252 -> new int[] { 165, 369, 165 };
            default -> null;
        };
        if (bounds == null) return null;
        int w = parent.getScreen()
            .getScreenArea().width;
        int h = parent.getScreen()
            .getScreenArea().height;
        float scale = Math.min(1f, Math.min(w / (float) (WIDTH + 12), h / (float) (HEIGHT + 12)));
        return new int[] { Math.round((w - WIDTH * scale) / 2f + (bounds[0] + bounds[2] / 2f) * scale),
            Math.round((h - HEIGHT * scale) / 2f + (bounds[1] + 11.5f) * scale) };
    }

    NBTTagCompound probeState(HologramUiModules graph) {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("open", isOpen());
        result.setInteger("rows", rowCount());
        result.setInteger("scroll", scroll.getScroll());
        result.setLong("drawnFrames", drawnFrames);
        result.setInteger("drawRowBound", LIST_HEIGHT / ROW_HEIGHT + 2);
        result.setInteger("panelBuilds", panelBuilds);
        result.setInteger("listResizes", listResizes);
        if (local) {
            result.setBoolean("submitAvailable", localView.localSubmitAvailable());
            result.setString("submitBlocker", localView.localSubmitBlocker());
            result.setString("localStatus", localFrame == null ? "" : localFrame.status());
        }
        if (graph != null) {
            result.setString("uiNotice", graph.session.notice());
            int[] submit = graph.shell.nativeWidgetBounds(controlWidgets.get(55));
            if (submit != null) result.setIntArray("submitBounds", submit);
        }
        result.setInteger("listIdentity", System.identityHashCode(list));
        result.setInteger("canvasIdentity", System.identityHashCode(canvas));
        result.setInteger(
            "firstRowIdentity",
            System.identityHashCode(descriptive() ? descriptionRow(0) : materials.row(0)));
        if (local) {
            result.setBoolean("voucherSelected", localView.localVoucherSelected());
            result.setBoolean("voucherEnabled", localView.localVoucherAvailable());
            result.setString("materialSources", localView.localSourcesLabel());
        }
        if (graph != null) {
            HologramState state = graph.session.state();
            result.setBoolean(
                "orderActive",
                state.data.getCompoundTag("materialOrder")
                    .getBoolean("active") || HologramBackgroundTasks.materialTask());
            result.setBoolean(
                "voucherEnabled",
                state.data.getCompoundTag("decorativeVoucher")
                    .getBoolean("enabled"));
            result.setBoolean("voucherSelected", graph.session.materialVoucherSelected());
            result.setBoolean("quotePending", graph.session.materialReviewPending());
            result.setInteger("cellsIdentity", System.identityHashCode(state.cells));
            result.setInteger("cacheSummaryColor", graph.review.lastCacheReviewColor());
            result.setString("cacheSummary", graph.review.lastCacheReviewSummary());
        }
        for (Map.Entry<Integer, IntSupplier> entry : controlAccents.entrySet()) result.setInteger(
            "accent" + entry.getKey(),
            entry.getValue()
                .getAsInt());
        return result;
    }

    boolean consumeKey(int key) {
        if (!isOpen()) return false;
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) back();
        // Enter never silently confirms a material transaction; the green button is explicit.
        return true;
    }

    boolean consumeBack(ModularPanel panel) {
        if (panel != livePanel || !isOpen()) return false;
        back();
        return true;
    }

    private void back() {
        if (local) localView.localAction(LocalAction.BACK);
        else if (orderActive()) ui.router.closeRoot();
        else action(54);
    }

    private NBTTagCompound state() {
        return ui.session.state().data;
    }

    private boolean orderActive() {
        return state().getCompoundTag("materialOrder")
            .getBoolean("active") || HologramBackgroundTasks.materialTask();
    }

    private boolean editable() {
        return ui.session.editable() && !state().getBoolean("localPlanPending") && !orderActive();
    }

    private boolean voucherEnabled() {
        return state().getCompoundTag("decorativeVoucher")
            .getBoolean("enabled");
    }

    private boolean voucherSelected() {
        return ui.session.materialVoucherSelected();
    }

    private void action(int id) {
        close();
        ui.router.activate(id);
    }

    private ModularPanel build() {
        panelBuilds++;
        MaterialPanel panel = new MaterialPanel(
            local ? "gtmb_local_material_review"
                : lessonClose == null ? "gtmb_material_confirmation"
                    : ui.panelName(HologramEditorMaterialLesson.PANEL_NAME),
            lessonClose != null);
        if (lessonClose == null) livePanel = panel;
        panel.size(WIDTH, HEIGHT)
            .background(HologramMuiTheme.CLOTH);
        HologramMuiTheme.header(
            panel,
            () -> descriptive() ? projectorOr("native.ui.view_materials_local_record", "查看材料 · 本地记录")
                : local ? projectorOr("native.ui.view_materials_local_estimate", "查看材料 · 本地估算")
                    : lessonClose == null ? ui.review.lastConfirmationTitle()
                        : projectorOr("native.ui.materials_and_changes_tutorial_plan", "材料与改动 · 教学规划"),
            WIDTH);
        panel.child(HologramMuiTheme.label(this::scopeLabel, 14, 38, 604, 15, HologramMuiTheme.MUTED));
        panel.child(
            HologramMuiTheme.label(this::reviewHint, 14, 55, 604, 15, HologramMuiTheme.MUTED)
                .tooltipBuilder(t -> {
                    if (descriptive()) {
                        t.addLine(
                            projectorOr(
                                "native.ui.recorded_blueprint_cells_are_not_construction_demand",
                                "原图记录格数不是施工需求；背包同物品数量不代表可扣料。"));
                        return;
                    }
                    if (local && localView.localHatchDelta()) {
                        t.addLine(
                            projectorOr(
                                "native.ui.calculates_changes_across_the_complete_structure_the",
                                "核算完整结构的改动；服务端复核现场与归属。"));
                        return;
                    }
                    if (local) {
                        t.addLine(
                            projectorOr(
                                "native.ui.estimated_target_demand_containers_ae_permissions_and",
                                "需求按目标估算，未扣除现场已有方块；容器、AE、权限及抵扣待执行时核验。"));
                        return;
                    }
                    t.addLine(
                        projectorOr("native.ui.controller_inventory_is_view_only_and_excluded", "控制器库存仅查看，不计入已有。"));
                    t.addLine(
                        state().getCompoundTag("materialQuote")
                            .getString("validation"));
                })
                .tooltipAutoUpdate(true));
        panel.child(
            HologramMuiTheme.label(
                () -> descriptive() ? projectorOr("native.ui.recorded_item_identity", "原图物品身份")
                    : projectorOr("native.ui.materials_exact_nbt_entries", "材料 / 精确 NBT 条目"),
                42 + ROW_INSET,
                77,
                220,
                15,
                HologramMuiTheme.TITLE));
        panel.child(
            HologramMuiTheme.label(
                () -> descriptive() ? projectorOr("native.ui.recorded_blueprint_cells", "蓝图记录格数")
                    : projectorOr("native.ui.required", "需求"),
                270 + ROW_INSET,
                77,
                110,
                15,
                HologramMuiTheme.TITLE));
        panel.child(
            HologramMuiTheme.label(
                () -> descriptive() ? projectorOr("native.ui.matching_local_inventory_items", "本机背包同物品")
                    : local ? projectorOr("native.ui.known_inventory", "背包已知")
                        : projectorOr("native.ui.available", "已有"),
                384 + ROW_INSET,
                77,
                110,
                15,
                HologramMuiTheme.TITLE));
        panel.child(
            HologramMuiTheme.label(
                () -> descriptive() ? ""
                    : local ? projectorOr("native.ui.inventory_shortfall", "背包差额")
                        : projectorOr("native.ui.missing_93", "缺失"),
                498 + ROW_INSET,
                77,
                110,
                15,
                HologramUiAccent.WARNING));
        list = new MaterialList().pos(14, 96)
            .size(LIST_WIDTH, LIST_HEIGHT)
            .background(HologramMuiTheme.PANEL);
        // Center the rows inside the frame; the right gutter also accommodates MUI's scrollbar.
        canvas = new RowsCanvas().pos(ROW_INSET, 0)
            .size(ROW_WIDTH, Math.max(LIST_HEIGHT, rowCount() * ROW_HEIGHT));
        canvas.tooltipBuilder(t -> {
            if (descriptive()) {
                HologramLibraryMaterialDescription.Row row = descriptionRow(getHoveredRow());
                if (row != null) {
                    HologramMaterialRowPresentation.Sample sample = descriptionMaterial(row);
                    t.addLine(sample.name);
                    t.addLine(sample.identity);
                    t.addLine(projectorOr("native.ui.construction_demand_not_calculated", "施工用量未核算"));
                }
                return;
            }
            HologramMaterialReview.Row row = materials.row(getHoveredRow());
            if (row == null) return;
            t.addLine(row.name);
            if (row.data.hasKey("hatchChange", 8)) t.addLine(row.data.getString("hatchChange"));
            t.addLine(
                (projectorOr("native.ui.required_95", "需求") + " ") + row.required
                    + (local ? (" " + projectorOr("native.ui.known_inventory_96", "/ 背包已知") + " ")
                        : (" " + projectorOr("native.ui.available_97", "/ 已有") + " "))
                    + row.available
                    + (local ? (" " + projectorOr("native.ui.inventory_shortfall_98", "/ 背包差额") + " ")
                        : (" " + projectorOr("native.ui.missing_99", "/ 缺失") + " "))
                    + row.missing
                    + " "
                    + row.unit);
            t.addLine(row.identity());
            if (!local && row.item != null)
                for (Object line : row.item.getTooltip(Minecraft.getMinecraft().thePlayer, false))
                    t.addLine(String.valueOf(line));
            t.addLine(
                projectorOr("native.ui.list_entry", "清单条目 #") + (row.index + 1)
                    + (row.tagged ? (" " + projectorOr("native.ui.exact_nbt_billed_separately", "· 精确 NBT，独立计费"))
                        : ""));
            if (row.data.getBoolean("voucherEligible")) t.addLine(
                projectorOr(
                    "native.ui.selected_decorative_vouchers_apply_first_existing_stock",
                    "选用猫猫装饰抵用券后优先抵扣；已有库存不会为此消耗"));
            if (!row.data.getString("nbtKey")
                .isEmpty())
                t.addLine((projectorOr("native.ui.device_resource", "设备资源：") + " ") + row.data.getString("nbtKey"));
        })
            .tooltipAutoUpdate(true);
        list.child(canvas);
        panel.child(list);
        if (local) {
            panel.child(HologramMuiTheme.label(this::status, 14, 299, 604, 16, HologramMuiTheme.TEXT));
            panel.child(
                HologramMuiTheme.label(
                    () -> localView.localHatchDelta()
                        ? projectorOr(
                            "native.ui.processes_the_complete_structure_the_server_verifies",
                            "处理完整结构；现场与库存由服务器确认。")
                        : descriptive() ? projectorOr("native.ui.view_only_no_materials_consumed", "仅供查看，未扣料。")
                            : projectorOr("native.ui.local_estimate_no_materials_consumed", "本地估算，未扣料。"),
                    14,
                    316,
                    604,
                    16,
                    HologramMuiTheme.MUTED));
            panel.child(HologramMuiTheme.label(localView::localSourcesLabel, 14, 340, 210, 16, HologramMuiTheme.MUTED));
            if (localView.hasBindingChoices()) {
                panel.child(
                    button(
                        250,
                        localView::bindingGroupLabel,
                        234,
                        340,
                        160,
                        () -> localView.localAction(LocalAction.BIND_GROUP),
                        () -> true,
                        () -> 0));
                panel.child(
                    button(
                        251,
                        localView::bindingSlotLabel,
                        165,
                        369,
                        165,
                        () -> localView.localAction(LocalAction.BIND_SLOT),
                        () -> true,
                        () -> 0));
            }
            panel.child(
                button(
                    245,
                    localView::localVoucherLabel,
                    400,
                    340,
                    218,
                    () -> { if (localView.localVoucherAvailable()) localView.localAction(LocalAction.VOUCHER); },
                    localView::localVoucherAvailable,
                    () -> localView.localVoucherSelected() ? HologramUiAccent.SUGGESTION : 0));
            // Repair, replacement and demolition share the recovery destination. New construction hides this action.
            ParentWidget<?> recovery = new ParentWidget<>().name("material_recovery_destination")
                .pos(165, 369)
                .size(165, 23)
                .child(
                    button(
                        252,
                        localView::localRecoveryDestinationLabel,
                        0,
                        0,
                        165,
                        () -> {
                            if (localView.localRecoveryAvailable())
                                localView.localAction(LocalAction.RECOVERY_DESTINATION);
                        },
                        localView::localRecoveryAvailable,
                        () -> 0));
            recovery.setEnabled(localView.localRecoveryAvailable());
            panel.child(recovery.setEnabledIf(widget -> localView.localRecoveryAvailable()));
            panel.child(
                button(
                    247,
                    () -> projectorOr("stock.refresh_sources", "刷新材料来源"),
                    338,
                    369,
                    132,
                    () -> localView.localAction(LocalAction.REFRESH),
                    () -> localFrame != null && localFrame.ready(),
                    () -> 0));
            panel.child(
                button(
                    253,
                    localView::localOrderLabel,
                    478,
                    369,
                    140,
                    () -> { if (localView.localOrderAvailable()) localView.localAction(LocalAction.ORDER); },
                    localView::localOrderAvailable,
                    () -> 0));
            panel.child(
                button(
                    54,
                    () -> projectorOr("native.ui.back_to_preview", "返回预览"),
                    14,
                    402,
                    160,
                    () -> localView.localAction(LocalAction.BACK),
                    () -> true,
                    () -> 0));
            panel.child(button(55, localView::localSubmitLabel, 184, 402, 434, () -> {
                if (localView.localSubmitAvailable()) localView.localAction(LocalAction.SUBMIT);
                else localView.localSubmitDenied();
            }, localView::localSubmitAvailable, () -> HologramUiAccent.INFORMATION));
            return panel;
        }
        panel.child(HologramMuiTheme.label(this::status, 14, 299, 604, 16, HologramMuiTheme.TEXT));
        panel.child(
            HologramMuiTheme.label(ui.review::lastCacheReviewSummary, 14, 316, 604, 16, HologramMuiTheme.TEXT)
                .color(ui.review::lastCacheReviewColor)
                .tooltipBuilder(t -> {
                    net.minecraft.nbt.NBTTagList rows = HologramPlacementConfirmation.cacheResources(state());
                    for (int i = 0; i < rows.tagCount(); i++) {
                        NBTTagCompound row = rows.getCompoundTagAt(i);
                        t.addLine(
                            row.getString("name") + (projectorOr("native.ui.required_109", "：需求") + " ")
                                + row.getString("required")
                                + (" " + projectorOr("native.ui.available_97", "/ 已有") + " ")
                                + row.getString("available"));
                    }
                    if (HologramPlacementConfirmation.emptyCacheFallback(state())) t.addLine(
                        projectorOr("native.ui.confirming_accepts_placement_with_empty_caches", "确认表示接受空缓存放置结果。"));
                })
                .tooltipAutoUpdate(true));
        panel.child(
            new OrderProgress().pos(14, 333)
                .size(604, 4));
        panel.child(
            button(
                168,
                () -> projectorOr("native.ui.placement_nbt", "本次 NBT"),
                14,
                340,
                92,
                () -> action(168),
                () -> editable() && state().getBoolean("virtual") && ui.nbt.hasPlacementNbtChoices(),
                () -> 0));
        panel.child(
            button(
                196,
                () -> projectorOr("native.ui.quantum_network_bindings", "量子网络绑定"),
                112,
                340,
                116,
                () -> action(196),
                () -> editable() && ui.bindings.networkGroups()
                    .tagCount() > 0,
                () -> HologramPlacementConfirmation.networksReady(state()) ? 0 : HologramUiAccent.WARNING));
        panel.child(
            button(
                162,
                () -> orderActive() ? projectorOr("native.ui.cancel_this_ae_order", "取消本次 AE 订单")
                    : projectorOr("native.ui.ae_craft_missing_materials", "AE 补齐本次缺料"),
                234,
                340,
                160,
                this::order,
                () -> orderActive()
                    ? HologramMaterialOrderLifecycle.cancellable(
                        state().getCompoundTag("materialOrder"),
                        ui.review.confirmedRevision(),
                        state().getLong("placementReviewNonce"))
                    : editable() && !ui.session.materialReviewPending()
                        && state().getBoolean("materialOrderingAvailable")
                        && materials.hasItemShortage(),
                () -> orderActive() ? HologramUiAccent.WARNING : recommendOrder() ? HologramUiAccent.SUGGESTION : 0)
                    .tooltipBuilder(t -> {
                        t.addLine(
                            orderActive()
                                ? projectorOr(
                                    "native.ui.cancels_this_order_only_the_server_cancels",
                                    "只取消本次订单；已提交的 AE 合成由服务器按能力撤销。")
                                : projectorOr(
                                    "native.ui.orders_craftable_shortages_in_this_list_only",
                                    "仅对本次清单中可合成的缺料下单；不可合成项会明确报告。"));
                    }));
        panel.child(
            button(
                245,
                this::voucherLabel,
                400,
                340,
                218,
                this::voucher,
                () -> editable() && HologramPlacementConfirmation
                    .voucherChoiceAvailable(state(), hasVoucherDemand(), voucherSelected()),
                () -> voucherEnabled() && (hasVoucherDemand() || voucherSelected()) ? HologramUiAccent.SUGGESTION : 0)
                    .tooltipBuilder(t -> {
                        if (voucherEnabled() && (hasVoucherDemand() || voucherSelected())) {
                            if (state().getCompoundTag("materialQuote")
                                .getBoolean("creativePaymentsFree"))
                                t.addLine(
                                    projectorOr("native.ui.creative_mode_does_not_consume_vouchers", "创造模式不消耗抵用券"));
                            else t.addLine(
                                (projectorOr("native.ui.main_inventory", "主物品栏：") + " ")
                                    + state().getCompoundTag("decorativeVoucher")
                                        .getLong("available")
                                    + (" " + projectorOr("native.ui.vouchers", "张")));
                        }
                    }));
        panel.child(
            button(
                211,
                () -> projectorOr("native.ui.identity_settings", "去标识设置"),
                14,
                369,
                102,
                () -> action(211),
                () -> editable() && ui.identity.hasPlacementIdentityChoices(),
                () -> 0));
        panel.child(
            button(
                184,
                () -> projectorOr("native.ui.validation_details", "数据校验详情"),
                230,
                369,
                102,
                () -> action(184),
                () -> !orderActive(),
                () -> 0));
        panel.child(
            button(
                247,
                () -> projectorOr("native.ui.refresh_materials", "刷新材料"),
                338,
                369,
                132,
                ui.session::refreshMaterialReview,
                this::editable,
                () -> 0));
        panel.child(
            button(
                246,
                () -> projectorOr("native.ui.return_to_world_continue_in_background", "返回世界 · 后台继续"),
                476,
                369,
                142,
                () -> ui.router.closeRoot(),
                this::orderActive,
                () -> 0));
        panel.child(
            button(
                54,
                () -> ui.review.placementReviewActive() ? projectorOr("native.ui.cancel_placement", "取消放置")
                    : projectorOr("native.ui.back_to_preview", "返回预览"),
                14,
                402,
                160,
                () -> action(54),
                () -> !orderActive(),
                () -> ui.review.placementReviewActive() ? HologramUiAccent.WARNING : 0));
        panel.child(
            button(
                55,
                () -> lessonClose != null
                    ? projectorOr("native.ui.final_confirmation_tutorial_isolation", "最终确认 · 教学隔离")
                    : ui.review.lastConfirmationAction(),
                184,
                402,
                434,
                () -> action(55),
                () -> materials.ready() && !orderActive() && ui.review.confirmationAllowed(ui.review.operationCount()),
                () -> HologramPlacementConfirmation.forceReview(state(), ui.review.confirmedRevision())
                    ? HologramUiAccent.WARNING
                    : HologramUiAccent.NEXT));
        return panel;
    }

    private String scopeLabel() {
        if (!local) return ui.review.constructionScopeLabel(true);
        return localFrame == null ? "" : localFrame.scope();
    }

    private String status() {
        if (local) return localFrame == null ? "" : localFrame.status();
        if (!ui.session.notice()
            .isEmpty()) return ui.session.notice();
        if (state().getBoolean("localPlanPending"))
            return projectorOr("native.ui.updating_the_material_plan_review_the_list", "材料规划更新中；完成后再核对清单。");
        if (ui.session.materialReviewPending()) return projectorOr(
            "native.ui.updating_this_material_quote_voucher_selection_remains",
            "正在更新本次材料报价…可继续切换抵用券。");
        if (!materials.ready())
            return projectorOr("native.ui.waiting_for_the_server_s_exact_material", "等待服务器的精确材料清单…");
        if (!HologramPlacementConfirmation.voucherPaymentReady(state())) return projectorOr(
            "native.ui.discount_shown_add_one_decorative_voucher_to",
            "已展示抵扣方案；生存施工前请在主背包补充 1 张猫猫装饰抵用券，或取消勾选。");
        NBTTagCompound order = state().getCompoundTag("materialOrder");
        if (orderActive()) return (projectorOr("native.ui.this_ae_order", "AE 本次订单") + " ") + order.getLong("done")
            + " / "
            + order.getLong("total")
            + " · "
            + order.getString("status")
            + (" " + projectorOr("native.ui.you_may_close_this_screen_work_continues", "· 可关闭界面，后台继续"));
        if (!order.getString("status")
            .isEmpty()) return order.getString("status");
        if (HologramPlacementConfirmation.forceReview(state(), ui.review.confirmedRevision()))
            return state().getString("status")
                .contains("请重新核对")
                    ? state().getString("status")
                    : projectorOr(
                        "native.ui.only_cells_with_sufficient_materials_will_be",
                        "确认后仅放置材料足够的格；缺料格跳过，权限与占位保护仍有效。");
        return materials.size() + (" " + projectorOr(
            "native.ui.exact_materials_hover_icons_for_full_values",
            "项精确材料 · 图标悬停查看完整数值与身份 · 普通滚轮 / 拖动右侧滚动条"));
    }

    private boolean descriptive() {
        return localFrame != null && localFrame.description() != null;
    }

    private int rowCount() {
        return descriptive() ? localFrame.description()
            .size() : materials.size();
    }

    private HologramLibraryMaterialDescription.Row descriptionRow(int index) {
        return index >= 0 && index < rowCount() ? localFrame.description()
            .get(index) : null;
    }

    HologramMaterialRowPresentation.Sample descriptionMaterial(HologramLibraryMaterialDescription.Row row) {
        return descriptionRows.describe(row);
    }

    private String localEmptyLabel() {
        if (descriptive()) return localFrame.emptyLabel();
        return localFrame == null || !localFrame.ready() ? projectorOr("native.ui.calculating_locally", "正在本地统计…")
            : localFrame.emptyLabel();
    }

    private String reviewHint() {
        if (descriptive()) return projectorOr(
            "native.ui.shows_recorded_items_and_local_inventory_construction",
            "查看原图记录和本机背包；施工用量、容器及 AE 均未核验。");
        if (local && localView.localHatchDelta())
            return projectorOr("native.ui.demand_includes_replacement_items_only_turning_and", "需求仅计入换入物品；转向、拆除不扣料。");
        if (local) return projectorOr(
            "native.ui.estimated_demand_and_local_inventory_ae_and",
            "理论需求与本机背包；AE / 容器库存未知，不查询服务器。");
        if (lessonClose != null) return projectorOr(
            "native.ui.exact_demand_for_this_plan_tutorial_stock",
            "同一规划的精确材料需求 · 教学库存为 0 · 此处不查询玩家库存或执行施工");
        boolean creative = state().getCompoundTag("materialQuote")
            .getBoolean("creativePaymentsFree");
        if (ui.review.placementReviewActive())
            return (projectorOr("native.ui.position_fixed", "位置已固定：") + " ") + ui.review.placementPosition()
                + (creative ? (" " + projectorOr("native.ui.creative_mode_complete_material_demand", "· 创造模式 · 完整材料需求"))
                    : (" " + projectorOr("native.ui.available_stock_from_the_latest_check", "· 已有以最近检查为准")));
        return creative
            ? projectorOr("native.ui.creative_mode_still_shows_complete_demand_actual", "创造模式仍展示完整需求，实际消耗按施工规则。")
            : projectorOr("native.ui.available_stock_is_from_the_latest_check", "已有按最近检查；确认施工或提交 AE 时核对实际库存。");
    }

    private String voucherLabel() {
        return voucherSelected() ? projectorOr("native.ui.decorative_discount_146", "✓ 装饰抵扣")
            : projectorOr("native.ui.decorative_discount", "装饰抵扣");
    }

    private void voucher() {
        if (!editable() || !voucherEnabled()) return;
        ui.session.selectMaterialVoucher(!voucherSelected());
    }

    private boolean recommendOrder() {
        return materials.hasItemShortage() && ui.review.materialBlockers() == 0
            && HologramPlacementConfirmation.networksReady(state());
    }

    private String unavailableReason(int id) {
        if (local) {
            if (id == 55) return localView.localSubmitBlocker();
            if (id == 245 && !localView.localVoucherAvailable()) return localView.localVoucherBlocker();
            return "";
        }
        if (id == 168 && !ui.nbt.hasPlacementNbtChoices())
            return projectorOr("native.ui.this_blueprint_has_no_adjustable_nbt_entries", "本次蓝图没有可调整的 NBT 条目。");
        if (id == 196 && ui.bindings.networkGroups()
            .tagCount() == 0)
            return projectorOr("native.ui.this_blueprint_has_no_quantum_networks_to", "本图没有需要绑定的量子网络。");
        if (id == 211 && !ui.identity.hasPlacementIdentityChoices())
            return projectorOr("native.ui.this_blueprint_has_no_adjustable_identity_or", "本图没有可调整的身份与外部绑定数据。");
        if (id == 162 && !orderActive()) {
            if (!materials.ready() || ui.session.materialReviewPending())
                return projectorOr("native.ui.checking_materials_wait_for_the_quote_update", "正在核对本次材料，请等待报价更新。");
            if (!materials.hasItemShortage())
                return projectorOr("native.ui.no_items_are_missing_for_this_request", "本次没有缺少的物品。");
            if (!state().getBoolean("materialOrderingAvailable")) return projectorOr(
                "native.ui.enable_me_materials_and_use_a_connected",
                "请启用 ME 材料来源，使用已连接且在范围内的无线终端，并确认有合成、提取和存入权限。");
        }
        if (id == 245 && !voucherSelected()) {
            if (!voucherEnabled()) return projectorOr(
                "native.ui.decorative_vouchers_are_disabled_by_server_configuration",
                "服务器配置已禁用猫猫装饰抵用券。");
            if (!hasVoucherDemand())
                return projectorOr("native.ui.this_request_has_no_eligible_building_material", "本次没有可抵扣的建筑材料需求。");
        }
        return "";
    }

    private boolean hasVoucherDemand() {
        NBTTagCompound voucher = state().getCompoundTag("decorativeVoucher");
        return materials.ready() && (voucher.hasKey("eligibleDemand", 4) ? voucher.getLong("eligibleDemand") > 0
            : materials.hasVoucherDemand());
    }

    private void order() {
        if (!orderActive()) {
            if (!materials.hasItemShortage()) return;
            ui.review.activateControl(162);
            return;
        }
        NBTTagCompound request = new NBTTagCompound();
        if (HologramMaterialOrderLifecycle.writeCancel(
            request,
            state().getCompoundTag("materialOrder"),
            ui.review.confirmedRevision(),
            state().getLong("placementReviewNonce"))) ui.session.send("cancelMaterialOrder", request, true);
    }

    private ButtonWidget<?> button(int id, Supplier<String> text, int x, int y, int width, Runnable action,
        BooleanSupplier enabled, IntSupplier accent) {
        if (lessonClose != null) {
            ButtonWidget<?> result = nativeButton(
                id,
                text,
                x,
                y,
                width,
                id == 54 ? lessonClose : () -> {},
                () -> id == 54,
                accent);
            if (id == 54) ui.shell.registerTarget("materials.cancel", result);
            return result;
        }
        return nativeButton(id, text, x, y, width, action, enabled, accent);
    }

    private ButtonWidget<?> nativeButton(int id, Supplier<String> text, int x, int y, int width, Runnable action,
        BooleanSupplier enabled, IntSupplier accent) {
        enabledControls.put(id, enabled);
        controlAccents.put(id, accent);
        ButtonWidget<?> result = HologramMuiTheme.button(text, x, y, width, 23, action)
            .background(HologramMuiTheme.guidanceBackground(false, accent, enabled))
            .hoverBackground(HologramMuiTheme.guidanceBackground(true, accent, enabled))
            .overlay(
                IKey.dynamic(text)
                    .color(() -> materialButtonInk(accent.getAsInt(), enabled.getAsBoolean()))
                    .shadow(false))
            .tooltipBuilder(t -> {
                String reason = unavailableReason(id);
                if (!reason.isEmpty()) t.addLine(reason);
            })
            .tooltipAutoUpdate(true)
            // Keep the grey local confirm target explainable on click; the guarded callback cannot dispatch.
            .setEnabledIf(widget -> local && id == 55 || enabled.getAsBoolean());
        controlWidgets.put(id, result);
        if (ui != null && lessonClose == null) ui.shell.registerTarget("materials.control." + id, result);
        return result;
    }

    static int materialButtonInk(int accent, boolean enabled) {
        // The disabled button texture is pale on this paper panel; its text still needs dark ink.
        return enabled ? HologramUiAccent.textColor(accent, true) : HologramEditorTheme.TEXT;
    }

    private int getHoveredRow() {
        // RichTooltip builds in the foreground's transform. Keep only the current draw's local hit index.
        return hoveredRow;
    }

    private final class MaterialList extends AbstractScrollWidget<IWidget, MaterialList>
        implements IParentWidget<IWidget, MaterialList> {

        MaterialList() {
            super(null, scroll);
            showScrollShadows(false);
        }

        @Override
        public void postDraw(ModularGuiContext context, boolean transformed) {
            super.postDraw(context, transformed);
            if (!transformed) HologramScrollEdges.draw(0, 0, getArea().width, getArea().height);
        }

        @Override
        public boolean addChild(IWidget child, int index) {
            return super.addChild(child, index);
        }

        @Override
        public void beforeResize(boolean onOpen) {
            scroll.setScrollSize(Math.max(LIST_HEIGHT, rowCount() * ROW_HEIGHT));
            super.beforeResize(onOpen);
        }
    }

    private final class OrderProgress extends Widget<OrderProgress> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            NBTTagCompound order = state().getCompoundTag("materialOrder");
            long total = order.getLong("total");
            if (total <= 0) return;
            long done = Math.min(total, Math.max(0, order.getLong("done")));
            GuiDraw.drawRect(0, 0, 604, 4, 0xff14252e);
            int fill = (int) Math.floor(604d * done / total);
            GuiDraw.drawRect(0, 0, fill, 4, orderActive() ? HologramUiAccent.SUGGESTION : HologramUiAccent.NEXT);
        }
    }

    /** One framework-clipped child; draw work and decoded item cache are independent of total row count. */
    private final class RowsCanvas extends Widget<RowsCanvas> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            drawnFrames++;
            hoveredRow = isHovering() && context.getMouseY() >= 0 ? context.getMouseY() / ROW_HEIGHT : -1;
            int first = Math.max(0, scroll.getScroll() / ROW_HEIGHT);
            int last = Math.min(rowCount(), first + LIST_HEIGHT / ROW_HEIGHT + 2);
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            try {
                for (int i = first; i < last; i++) {
                    int y = i * ROW_HEIGHT;
                    GuiDraw.drawRect(0, y, ROW_WIDTH, ROW_HEIGHT - 1, (i & 1) == 0 ? 0xff24323a : 0xff293941);
                    if (descriptive()) {
                        HologramLibraryMaterialDescription.Row row = descriptionRow(i);
                        HologramMaterialRowPresentation.Sample sample = descriptionMaterial(row);
                        if (sample.icon != null) drawItem(sample.icon, 5, y + 8, context.getCurrentDrawingZ());
                        else font.drawString("?", 7, y + 15, HologramUiAccent.INFORMATION);
                        font.drawString(font.trimStringToWidth(sample.name, 220), 31, y + 8, HologramMuiTheme.TEXT);
                        font.drawString(
                            font.trimStringToWidth(sample.identity, 220),
                            31,
                            y + 24,
                            HologramMuiTheme.MUTED);
                        number(font, Long.toString(row.blueprintCells()), 256, y + 16, HologramMuiTheme.TEXT);
                        number(
                            font,
                            row.visibleBackpackCount() == null ? projectorOr("native.ui.unknown", "未知")
                                : row.visibleBackpackCount()
                                    .toString(),
                            370,
                            y + 16,
                            HologramMuiTheme.TEXT);
                        continue;
                    }
                    HologramMaterialReview.Row row = materials.row(i);
                    if (row.item != null) drawItem(row.item, 5, y + 8, context.getCurrentDrawingZ());
                    else if (row.fluid != null)
                        GuiDraw.drawFluidTexture(row.fluid, 7, y + 9, 18, 22, context.getCurrentDrawingZ());
                    else font.drawString(
                        "energy".equals(row.data.getString("kind")) ? "EU"
                            : "essentia".equals(row.data.getString("kind")) ? "Vis" : "?",
                        7,
                        y + 15,
                        HologramUiAccent.INFORMATION);
                    font.drawString(font.trimStringToWidth(row.name, 220), 31, y + 8, HologramMuiTheme.TEXT);
                    String note = row.data.hasKey("hatchChange", 8) ? row.data.getString("hatchChange")
                        : "#" + (i + 1)
                            + " · "
                            + (row.tagged ? projectorOr("native.ui.exact_nbt", "精确 NBT") : row.unit);
                    font.drawString(font.trimStringToWidth(note, 220), 31, y + 24, HologramMuiTheme.MUTED);
                    if (row.data.getBoolean("voucherEligible")) {
                        String badge = projectorOr("native.ui.voucher_eligible", "可用抵用券");
                        int badgeX = 151;
                        GuiDraw.drawRect(badgeX - 3, y + 21, font.getStringWidth(badge) + 6, 15, 0xff51432a);
                        font.drawString(badge, badgeX, y + 24, 0xffffd36b);
                    }
                    number(font, row.required, 256, y + 16, HologramMuiTheme.TEXT);
                    number(font, row.available, 370, y + 16, HologramMuiTheme.TEXT);
                    number(
                        font,
                        row.missing,
                        484,
                        y + 16,
                        row.shortage ? HologramUiAccent.WARNING : HologramMuiTheme.MUTED);
                }
                if (rowCount() == 0) font.drawString(
                    local ? localEmptyLabel()
                        : materials.ready() ? projectorOr("native.ui.no_material_demand_for_this_request", "本次没有材料需求")
                            : projectorOr("native.ui.waiting_for_the_exact_material_list", "等待精确材料清单…"),
                    12,
                    16,
                    HologramMuiTheme.MUTED);
            } finally {
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }

        private void number(FontRenderer font, String value, int x, int y, int color) {
            int width = Math.max(1, font.getStringWidth(value));
            float scale = Math.min(1f, 106f / width);
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(x, y, 0);
                GL11.glScalef(scale, scale, 1);
                font.drawString(value, 0, 0, color);
            } finally {
                GL11.glPopMatrix();
            }
        }

        private void drawItem(ItemStack item, int x, int y, int z) {
            // Keep item-renderer zLevel private, and restore attributes/matrices even when an item renderer rejects
            // NBT.
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            float previousZ = iconRenderer.zLevel;
            try {
                Platform.setupDrawItem();
                GL11.glTranslatef(x, y, 0);
                GL11.glScalef(22f / 16f, 22f / 16f, 1);
                iconRenderer.zLevel = z + 100;
                Minecraft mc = Minecraft.getMinecraft();
                iconRenderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), item, 0, 0);
                GuiDraw.afterRenderItemAndEffectIntoGUI(item);
            } finally {
                iconRenderer.zLevel = previousZ;
                Platform.endDrawItem();
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }
    }

    private static final class MaterialPanel extends Dialog<Void> {

        private final boolean lesson;

        MaterialPanel(String name, boolean lesson) {
            super(name);
            this.lesson = lesson;
            setDisablePanelsBelow(!lesson);
            setCloseOnOutOfBoundsClick(false);
            setDraggable(false);
        }

        @Override
        public void transform(IViewportStack stack) {
            int w = getScreen().getScreenArea().width,
                h = getScreen().getScreenArea().height - (lesson ? HologramEditorTutorialOverlay.RESERVED_HEIGHT : 0);
            float scale = Math.min(1f, Math.min(w / (float) (WIDTH + 12), h / (float) (HEIGHT + 12)));
            stack.translate((w - WIDTH * scale) / 2f, (h - HEIGHT * scale) / 2f);
            stack.scale(scale, scale);
        }
    }
}
