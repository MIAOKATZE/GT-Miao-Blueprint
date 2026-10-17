package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.client.hologram.HologramUiMemory.UiMemory;
import com.miaokatze.gtmb.hologram.HologramRecovery;

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

    private boolean pendingBuildReview;

    private long pendingBuildReviewRevision = -1;

    private String lastConfirmationTitle = "";

    private String lastConfirmationAction = "";

    private int[] lastConfirmationBounds = new int[0];

    private String lastCacheReviewSummary = "";

    private boolean lastEmptyCacheReview;

    private NBTTagCompound reviewedCacheQuote;

    private String lastFooterScope = "";

    private String lastReviewedScope = "";

    private List<HologramState.Cell> countedCells;

    private int countedOperations;

    boolean retainsPlacementReview(HologramState next) {
        return this.placementReviewContext.retain(
            next.session,
            next.data.getLong("placementReviewNonce"),
            next.data.getBoolean("virtualPlacement") && next.data.getBoolean("virtualLocked"),
            next.data.getBoolean("moving"));
    }

    void drawConfirmation(int mx, int my) {
        this.lastConfirmationBounds = HologramPlacementConfirmation.modalBounds(ui.session.state().data);
        drawRect(
            this.lastConfirmationBounds[0],
            this.lastConfirmationBounds[1],
            this.lastConfirmationBounds[2],
            this.lastConfirmationBounds[3],
            0xff172f36);
        if (ui.session.state().data.getBoolean("localPlanPending")) {
            ui.canvas.text("材料与改动 · 等待规划更新", 50, 68, 315, HologramUiAccent.SUGGESTION);
            ui.canvas.drawLocalPlanProgress(50, 96, 309, mx, my);
            ui.canvas.text("当前材料统计尚未完成；可返回预览继续编辑。", 50, 140, 315, HologramMuiTheme.TEXT);
            ui.canvas.button(54, 50, 232, 315, 17, "返回", true, mx, my);
            return;
        }
        boolean placement = ui.review.placementReviewActive();
        NBTTagList cacheRows = HologramPlacementConfirmation.cacheResources(ui.session.state().data);
        this.lastCacheReviewSummary = HologramPlacementConfirmation.cacheSummary(ui.session.state().data);
        this.lastEmptyCacheReview = HologramPlacementConfirmation.emptyCacheFallback(ui.session.state().data);
        this.reviewedCacheQuote = HologramPlacementConfirmation.cacheReviewSnapshot(ui.session.state().data);
        int reviewRows = cacheRows.tagCount() > 0 ? 2 : 3;
        this.lastConfirmationTitle = HologramPlacementConfirmation
            .forceReview(ui.session.state().data, this.confirmedRevision) ? "缺料 · 再次确认仅放已有"
                : placement ? "蓝图放置预检 · " + ui.session.state().data.getString("virtualPresetName") : "材料与改动";
        ui.canvas.text(this.lastConfirmationTitle, 50, 68, 315, 0xf5e4b9);
        int operations = operationCount(), recovery = 0;
        NBTTagList recovered = ui.session.state().data.getTagList("recovery", 10);
        for (int i = 0; i < recovered.tagCount(); i++) recovery += recovered.getCompoundTagAt(i)
            .getInteger("expected");
        if (ui.session.state().data.hasKey("recoveryTotal"))
            recovery = ui.session.state().data.getInteger("recoveryTotal");
        this.lastReviewedScope = ui.review.constructionScopeLabel(true);
        ui.canvas.text(this.lastReviewedScope + " · 差分 " + operations + " 格", 50, 86, 315, 0xcce5d8);
        if (mx >= 50 && mx < 365 && my >= 84 && my < 96) {
            ui.canvas.tooltip(new ArrayList<>(Collections.singletonList(this.lastReviewedScope)));
            if (!placement) ui.canvas.tooltip()
                .add("返回预览可用 Ctrl+A 全选，右键预览清选后恢复全部结构");
        }
        HologramState.Cell selected = ui.session.selectedCell();
        if (HologramPlacementConfirmation.forceReview(ui.session.state().data, this.confirmedRevision)) {
            ui.canvas.text(ui.session.state().data.getString("status"), 50, 100, 315, 0xff9292);
            if (mx >= 50 && mx < 365 && my >= 98 && my < 111) ui.canvas.tooltip(
                ui.host.font()
                    .listFormattedStringToWidth(ui.session.state().data.getString("status"), 300));
        } else if (placement) ui.canvas.text("位置固定：" + ui.review.placementPosition(), 50, 100, 315, 0xe4d3a9);
        else if (ui.session.state().data.getBoolean("creativeMaterials"))
            ui.canvas.text("创造：普通材料无限；封存设备仍消耗真实物品", 50, 100, 315, 0xe4d3a9);
        else if (selected != null) ui.canvas.text(
            ui.preview.cellName(selected, true) + " → " + ui.preview.cellName(selected, false),
            50,
            100,
            315,
            0xe4d3a9);
        NBTTagList materialRows = ui.session.state().data.getTagList("materials", 10);
        if (!this.resourceReview) for (int i = this.materialOffset; i
            < Math.min(this.materialOffset + reviewRows, ui.session.state().materials.size()); i++) {
                HologramState.Material m = ui.session.state().materials.get(i);
                int y = 119 + (i - this.materialOffset) * 27;
                String available = ui.session.state().data.getBoolean("creativeMaterials")
                    && !HologramRecovery.hasSeal(m.stack) ? "无限" : Integer.toString(m.available);
                ui.canvas.text(
                    (m.stack == null ? "未知材料" : m.stack.getDisplayName()) + " × " + m.required + " / 可用来源 " + available,
                    50,
                    y,
                    315,
                    m.available < m.required ? 0xff9292 : 0xa2e8b8);
                NBTTagCompound material = materialRows.getCompoundTagAt(i);
                int controllerAvailable = material.getInteger("controllerAvailable");
                String counts = "主背包 " + material.getInteger("mainAvailable")
                    + " · 控制器 "
                    + (controllerAvailable < 0 ? "不可访问" : Integer.toString(controllerAvailable))
                    + "（仅查看）";
                ui.canvas.text(counts, 50, y + 11, 315, 0x94afb4);
                if (mx >= 50 && mx < 365 && my >= y && my < y + 24) {
                    ui.canvas.tooltip(new ArrayList<>());
                    ui.canvas.tooltip()
                        .add(m.stack == null ? "未知材料" : m.stack.getDisplayName());
                    ui.canvas.tooltip()
                        .add("需求 " + m.required + " · 已启用来源可用 " + available);
                    ui.canvas.tooltip()
                        .add(counts);
                    ui.canvas.tooltip()
                        .add("控制器库存仅查看，不参与本次取料；滚轮查看更多。");
                }
            }
        if (this.resourceReview) {
            NBTTagList resources = ui.session.state().data.getTagList("blueprintResources", 10);
            for (int i = this.materialOffset; i
                < Math.min(this.materialOffset + reviewRows, resources.tagCount()); i++) {
                NBTTagCompound resource = resources.getCompoundTagAt(i);
                String type = resource.getString("type");
                String source = "energy".equals(type) ? "无线电网 EU" : "fluid".equals(type) ? "AE 流体 mB" : "背包 / AE 库存";
                int y = 119 + (i - this.materialOffset) * 27;
                String missing = resource.getString("missing");
                ui.canvas.text(resource.getString("name") + " · " + source, 50, y, 315, 0xb9d5d4);
                String counts = "需求 " + resource
                    .getString("required") + " / 可用 " + resource.getString("available") + " / 缺口 " + missing;
                ui.canvas.text(counts, 50, y + 11, 315, missing.isEmpty() || "0".equals(missing) ? 0xa2e8b8 : 0xff9292);
                if (mx >= 50 && mx < 365 && my >= y && my < y + 24) {
                    ui.canvas.tooltip(new ArrayList<>());
                    ui.canvas.tooltip()
                        .add(resource.getString("name") + " · " + source);
                    ui.canvas.tooltip()
                        .add(counts);
                    ui.canvas.tooltip()
                        .add("NBT 根条目：" + resource.getString("key"));
                    if (!HologramUiText.reason(resource.getString("reason"))
                        .isEmpty())
                        ui.canvas.tooltip()
                            .add(HologramUiText.reason(resource.getString("reason")));
                    ui.canvas.tooltip()
                        .add(
                            resource.getBoolean("optionalCache") ? "缓存余额不足时可明确确认以空缓存放置；不会凭空补充能量。"
                                : "可在本次 NBT 匹配中取消对应条目降级；原蓝图保存选项保持不变。");
                }
            }
            if (resources.tagCount() == 0) ui.canvas.text("当前所选 NBT 没有额外设备资源需求", 50, 128, 315, 0x94afb4);
        }
        if (cacheRows.tagCount() > 0) {
            drawRect(48, 172, 367, 199, this.lastEmptyCacheReview ? 0xff49362a : 0xff193b3d);
            ui.canvas.text(this.lastCacheReviewSummary, 50, 175, 315, this.lastEmptyCacheReview ? 0xffce86 : 0xa2e8b8);
            ui.canvas.text(
                this.lastEmptyCacheReview ? "缓存不足或无法付取，将空缓存放置。" : "缓存将按设备资源清单扣取；悬停查看完整数值。",
                50,
                187,
                315,
                this.lastEmptyCacheReview ? 0xffce86 : 0x94afb4);
            if (mx >= 48 && mx < 367 && my >= 172 && my < 199) {
                ui.canvas.tooltip(new ArrayList<>());
                for (int i = 0; i < cacheRows.tagCount(); i++) {
                    NBTTagCompound row = cacheRows.getCompoundTagAt(i);
                    ui.canvas.tooltip()
                        .addAll(
                            ui.host.font()
                                .listFormattedStringToWidth(
                                    row.getString("name") + "：需求 "
                                        + row.getString("required")
                                        + " / 可用 "
                                        + row.getString("available"),
                                    380));
                    if (row.getBoolean("emptyFallback")) ui.canvas.tooltip()
                        .add("余额不足或无法付取：本次将以空缓存放置。");
                }
                if (this.lastEmptyCacheReview) ui.canvas.tooltip()
                    .addAll(
                        ui.host.font()
                            .listFormattedStringToWidth("点击“确认空缓存放置 / 施工”表示接受本次空缓存结果；需要保留缓存时，请先补足所选能源来源再核对。", 380));
            }
        }
        boolean decorativeNotice = ui.session.state().data.getBoolean("decorativeMaterialsNotice");
        ui.canvas.text(
            decorativeNotice ? "装饰材料：优先成品，缺少时按比例合计原料"
                : "预计回收 " + recovery + " · " + ui.session.state().data.getString("recoveryDestinationLabel"),
            50,
            202,
            315,
            0x94afb4);
        if (mx >= 50 && mx < 365 && my >= 200 && my < 214) ui.canvas.tooltip(
            decorativeNotice ? ui.host.font()
                .listFormattedStringToWidth(
                    HologramUiText.localized(
                        ui.session.state().data.getString("decorativeMaterialsNoticeKey"),
                        "本图含装饰形状：优先成品，缺少时同材质原始方块按施工比例合计后向上取整。染色和特殊微块按成品核算。"),
                    390)
                : Collections.singletonList("回收不会提前抵扣建造材料；所选去向未能接收的余量持久暂存，等待再次投递。"));
        ui.canvas.button(
            162,
            50,
            215,
            146,
            14,
            ui.session.state().data.getBoolean("materialOrderingAvailable") ? "AE 补齐缺料" : "AE 下单接口（预留）",
            ui.session.editable() && ui.session.state().data.getBoolean("materialOrderingAvailable")
                && this.confirmedRevision == ui.session.revision(),
            mx,
            my);
        ui.canvas.button(
            168,
            203,
            215,
            78,
            14,
            "本次 NBT",
            ui.session.editable() && ui.session.state().data.getBoolean("virtual"),
            mx,
            my);
        ui.canvas.button(169, 286, 215, 79, 14, this.resourceReview ? "方块材料" : "设备资源", true, mx, my);
        if (ui.bindings.networkGroups()
            .tagCount() > 0)
            ui.canvas.button(
                196,
                50,
                253,
                315,
                17,
                "量子网络绑定 · " + ui.bindings.networkGroups()
                    .tagCount() + " 组",
                ui.session.editable(),
                mx,
                my);
        ui.canvas.button(54, 50, 232, 146, 17, placement ? "取消放置" : "返回", true, mx, my);
        this.lastConfirmationAction = HologramPlacementConfirmation
            .confirmationAction(ui.session.state().data, this.confirmedRevision, placement);
        ui.canvas.button(55, 203, 232, 162, 17, this.lastConfirmationAction, confirmationAllowed(operations), mx, my);
        if (placement) {
            int x = HologramScreenLayout.COMPACT_PANEL_X;
            drawRect(x, 60, x + HologramScreenLayout.COMPACT_PANEL_WIDTH, 314, 0xff172f36);
            ui.canvas.text("蓝图放置预检", x + 8, 70, 204, 0x81dce9);
            ui.canvas.text(ui.session.state().data.getString("virtualPresetName"), x + 8, 92, 204, 0xf5e4b9);
            ui.canvas.text("位置已固定", x + 8, 117, 204, 0x9eedc1);
            ui.canvas.text(ui.review.placementPosition(), x + 8, 132, 204, 0xb9d5d4);
            ui.canvas.text("本次 NBT 与设备资源", x + 8, 160, 204, 0xf5e4b9);
            ui.canvas.text("点击左侧“本次 NBT”调整", x + 8, 177, 204, 0x94afb4);
            ui.canvas.text("取消 NBT 编辑保留位置和蓝图", x + 8, 193, 204, 0x94afb4);
            ui.canvas.text(this.lastEmptyCacheReview ? "点击“确认空缓存放置”接受" : "材料核对后点击“确认放置”", x + 8, 232, 204, 0x9eedc1);
            ui.canvas.text(this.lastEmptyCacheReview ? "或先补足能源，再重新核对" : "缺料时会要求再次明确确认", x + 8, 249, 204, 0xe4b85a);
            ui.canvas.button(
                211,
                x + 8,
                208,
                204,
                17,
                "去标识 " + ui.identity.deidentificationChoice(true)
                    + " · 推荐 "
                    + ui.session.state().data.getInteger("deidentificationRecommendedLevel"),
                ui.session.editable(),
                mx,
                my);
            ui.canvas.button(215, x + 8, 267, 204, 17, "一键移除阻塞物", ui.session.editable(), mx, my);
            ui.canvas.button(
                184,
                x + 8,
                287,
                204,
                17,
                "数据校验详情 · " + ui.session.state().data.getTagList("aeIssues", 10)
                    .tagCount() + " 项",
                true,
                mx,
                my);
        }
    }

    int operationCount() {
        List<HologramState.Cell> cells = ui.session.state().cells;
        // Parsed cell pages are immutable; metadata-only updates and rendered frames reuse this count.
        if (countedCells == cells) return countedOperations;
        int operations = 0;
        // Geometry lives in the parsed cell pages, not in the metadata-only NBT view.
        for (HologramState.Cell cell : cells) if (cell.inScope
            && ("PLACE".equals(cell.operation) || "REPLACE".equals(cell.operation) || "REMOVE".equals(cell.operation))
            && ("pending".equals(cell.status) || "missing".equals(cell.status))) operations++;
        countedCells = cells;
        return countedOperations = operations;
    }

    boolean confirmationAllowed(int operations) {
        return ui.session.editable()
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
                if (ui.session.editable() && ui.session.state().data.getBoolean("materialOrderingAvailable")
                    && this.confirmedRevision == ui.session.revision()) {
                    NBTTagCompound ordering = new NBTTagCompound();
                    ordering.setLong("planRevision", this.confirmedRevision);
                    ui.session.awaitingScan(true);
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
                if (ui.session.editable() && this.confirmation
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
                    if (!ui.session.state().data.getBoolean("blueprintAwaitingConfirmation")) {
                        this.pendingBuildReview = true;
                        this.pendingBuildReviewRevision = this.confirmedRevision;
                    }
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
        pendingBuildReview = false;
        confirmedRevision = -1;
    }

    void pauseForImport() {
        pendingBuildReview = false;
        confirmation = false;
    }

    void acknowledgeReview(HologramState next) {
        if (confirmation && confirmedRevision != ui.session.revision()) confirmation = false;
        if (pendingBuildReview && ui.session.pendingSequence() < 0) {
            pendingBuildReview = false;
            if (HologramPlacementConfirmation.forceReview(next.data, pendingBuildReviewRevision)) {
                confirmedRevision = pendingBuildReviewRevision;
                materialOffset = 0;
                confirmation = true;
            }
        }
        if (next.data.getBoolean("blueprintAwaitingConfirmation")
            && !next.data.getBoolean("presetBrowseForPlacement")) {
            confirmedRevision = ui.session.revision();
            materialOffset = 0;
            confirmation = true;
        }
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

    boolean pendingBuildReview(boolean value) {
        this.pendingBuildReview = value;
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

    void prepareCommand(String op) {
        if (!"build".equals(op)) pendingBuildReview = false;
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
