package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/** Internal ae workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiAeReview {

    private final HologramUiModules ui;

    HologramUiAeReview(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean aePreviewTransition;

    private boolean awaitingAePreview;

    private static final HologramAeReviewModel.Gate AE_REVIEW_GATE = new HologramAeReviewModel.Gate();

    private HologramAeReviewModel aeReview;

    private HologramAeReviewNavigation aeReviewNavigation;

    private int aeReviewTab;

    private int aeReviewOffset;

    private int aeReviewGroup = -1;

    private int aeReviewSelected = -1;

    private int aeDetailOffset;

    void receiveAeReview() {
        if (ui.session.areaImporting() || this.aeReviewNavigation != null) {
            this.aeReview = null;
            return;
        }
        if (this.aeReview != null && !this.aeReview.review) {
            this.aeReview = HologramAeReviewModel.inspection(ui.session.state());
            return;
        }
        NBTTagCompound dto = ui.session.state().data.getCompoundTag("aeSaveReview");
        if (this.aeReview != null && (!this.aeReview.matches(ui.session.state().session, ui.session.revision())
            || !this.aeReview.transactionId.equals(dto.getString("transactionId")))) this.aeReview = null;
        if (dto.getString("transactionId")
            .isEmpty() || !ui.session.state().session.equals(dto.getString("session"))
            || dto.getLong("planRevision") != ui.session.revision()) return;
        HologramAeReviewModel next = new HologramAeReviewModel(dto, true);
        if (HologramUiAeReview.AE_REVIEW_GATE.first(next)) {
            this.aeReview = next;
            this.aeReviewTab = this.aeReviewOffset = this.aeDetailOffset = 0;
            this.aeReviewGroup = this.aeReviewSelected = -1;
            ui.preview.dragging(ui.preview.boxSelecting(ui.preview.inventoryDragging(false)));
            ui.preview.gizmo()
                .cancel();
        }
    }

    List<HologramAeReviewModel.Issue> aeIssueRows() {
        if (this.aeReviewGroup >= 0 && this.aeReviewGroup < this.aeReview.groups.size())
            return this.aeReview.groups.get(this.aeReviewGroup).rows;
        return this.aeReview.issues;
    }

    int aeRowCount() {
        return this.aeReviewTab == 0 ? this.aeReview.groups.size()
            : this.aeReviewTab == 1 ? ui.ae.aeIssueRows()
                .size() : this.aeReview.materials.size();
    }

    void aePage(int delta) {
        this.aeReviewOffset = Math.max(0, Math.min(Math.max(0, ui.ae.aeRowCount() - 5), this.aeReviewOffset + delta));
    }

    void drawAeReview(int mx, int my) {
        ui.canvas.controls()
            .clear();
        ui.canvas.tooltip(null);
        GL11.glDepthMask(true);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        drawRect(30, 42, 650, 302, 0xfc172f36);
        ui.canvas.text(this.aeReview.review ? "AE 保存综合告知 · " + this.aeReview.name : "AE 预览综合提示", 42, 49, 598, 0xffe59b);
        ui.canvas.text("支持的存储元件仅保留同类型空元件；其中物品 / 流体 / 外置内容不保存。", 42, 63, 598, 0xe5c28d);
        ui.canvas.text(
            "保存等级 " + ui.identity.deidentificationChoice(false)
                + " · 当前 "
                + ui.session.state().data.getInteger("blueprintDeidentificationLevel")
                + " · 推荐 "
                + ui.session.state().data.getInteger("deidentificationRecommendedLevel")
                + "；源世界、源元件和源库存不改变。",
            42,
            75,
            598,
            0xe5c28d);
        ui.canvas.text("编码配方、升级卡、空元件需要施工物料；ghost 配置免费；缺料仍可保存。", 42, 87, 598, 0xa9d6cf);
        ui.canvas.text(
            "完整报告 " + this.aeReview.issues.size()
                + " 项 · 注意 "
                + this.aeReview.warningCount
                + " · 阻塞 "
                + this.aeReview.blockerCount
                + " · 物料 "
                + this.aeReview.materials.size()
                + " 行",
            42,
            100,
            598,
            0xf2dca5);
        ui.canvas.button(185, 42, 114, 120, 17, "同类相邻汇总", true, mx, my);
        ui.canvas.button(186, 168, 114, 120, 17, "全部问题", true, mx, my);
        ui.canvas.button(187, 294, 114, 120, 17, "施工物料", true, mx, my);
        ui.canvas.button(188, 528, 114, 49, 17, "上一页", this.aeReviewOffset > 0, mx, my);
        ui.canvas.button(189, 583, 114, 55, 17, "下一页", this.aeReviewOffset + 5 < ui.ae.aeRowCount(), mx, my);
        ui.ae.aePage(0);
        ui.canvas.text(
            (this.aeReviewTab == 0 ? "同类相邻问题归组；点击展开每条位置与详情"
                : this.aeReviewTab == 1 ? (this.aeReviewGroup >= 0 ? "该类全部问题 · 点击「全部问题」解除筛选" : "全部问题 · 点击条目查看完整路径与说明")
                    : "服务端精确物料 · 尚缺材料仅作提醒")
                + " · "
                + Math.min(this.aeReviewOffset + 1, ui.ae.aeRowCount())
                + "—"
                + Math.min(this.aeReviewOffset + 5, ui.ae.aeRowCount())
                + "/"
                + ui.ae.aeRowCount(),
            42,
            136,
            596,
            0x8bdddf);
        for (int i = 0; i < 5 && this.aeReviewOffset + i < ui.ae.aeRowCount(); i++) {
            int row = this.aeReviewOffset + i, y = 151 + i * 17;
            if (this.aeReviewTab == 0) {
                HologramAeReviewModel.Group group = this.aeReview.groups.get(row);
                ui.canvas.button(
                    40000 + row,
                    42,
                    y,
                    596,
                    16,
                    ("blocker".equals(group.severity) ? "阻塞 · " : "注意 / 说明 · ") + group
                        .title() + " × " + group.count + " · " + group.rows.size() + " 条记录（展开）",
                    true,
                    mx,
                    my);
            } else if (this.aeReviewTab == 1) {
                HologramAeReviewModel.Issue issue = ui.ae.aeIssueRows()
                    .get(row);
                ui.canvas.button(
                    50000 + row,
                    42,
                    y,
                    596,
                    16,
                    (row == this.aeReviewSelected ? "▶ " : "") + issue
                        .title() + " × " + issue.count + " · " + issue.x + "," + issue.y + "," + issue.z,
                    true,
                    mx,
                    my);
            } else {
                HologramAeReviewModel.Material material = this.aeReview.materials.get(row);
                ui.canvas.text(material.line(), 46, y + 3, 588, "0".equals(material.missing) ? 0xa9d6cf : 0xffcf88);
                if (mx >= 42 && mx < 638 && my >= y && my < y + 16) {
                    ui.canvas.tooltip(
                        new ArrayList<>(
                            ui.host.font()
                                .listFormattedStringToWidth(material.line(), 400)));
                    ui.canvas.tooltip()
                        .add("类型 " + material.type + " · " + material.key);
                }
            }
        }
        List<String> detail = new ArrayList<>();
        HologramAeReviewModel.Issue selectedIssue = null;
        if (this.aeReviewTab == 1 && this.aeReviewSelected >= 0
            && this.aeReviewSelected < ui.ae.aeIssueRows()
                .size()) {
            selectedIssue = ui.ae.aeIssueRows()
                .get(this.aeReviewSelected);
            detail.addAll(
                ui.host.font()
                    .listFormattedStringToWidth(
                        "[" + HologramUiText.severity(selectedIssue.severity)
                            + "] "
                            + selectedIssue.title()
                            + " · "
                            + selectedIssue.x
                            + ","
                            + selectedIssue.y
                            + ","
                            + selectedIssue.z
                            + " · 数量 "
                            + selectedIssue.count,
                        488));
            detail.addAll(
                ui.host.font()
                    .listFormattedStringToWidth(selectedIssue.explanation(), 488));
            detail.addAll(
                ui.host.font()
                    .listFormattedStringToWidth(selectedIssue.guidance(), 488));
            detail.addAll(
                ui.host.font()
                    .listFormattedStringToWidth("技术路径：" + selectedIssue.path + " · 槽 " + selectedIssue.slotId, 488));
            detail.addAll(
                ui.host.font()
                    .listFormattedStringToWidth("技术诊断：" + selectedIssue.code + " · " + selectedIssue.message, 488));
        } else detail.add(
            this.aeReview.valid
                ? this.aeReview.blockerCount > 0 ? "存在阻塞：展开问题并打开相关容器修正 / 清除，然后重新保存。" : "请查看汇总、完整问题和物料后确认；取消不会写入蓝图库。"
                : "完整报告不符合协议，继续保存已禁用；取消并重新请求保存。");
        this.aeDetailOffset = Math.max(0, Math.min(Math.max(0, detail.size() - 3), this.aeDetailOffset));
        for (int i = 0; i < 3 && i + this.aeDetailOffset < detail.size(); i++)
            ui.canvas.text(detail.get(i + this.aeDetailOffset), 42, 241 + i * 10, 488, 0xe5c28d);
        ui.canvas.button(190, 536, 238, 44, 15, "上文", this.aeDetailOffset > 0, mx, my);
        ui.canvas.button(191, 586, 238, 52, 15, "下文", this.aeDetailOffset + 3 < detail.size(), mx, my);
        ui.canvas.button(
            192,
            536,
            258,
            102,
            15,
            selectedIssue != null && "replaceCell".equals(selectedIssue.resolution) ? "返回替换 / 删除" : "打开相关容器",
            selectedIssue != null && selectedIssue.location
                && ui.session.editable()
                && ui.session.pendingSequence() < 0,
            mx,
            my);
        ui.canvas.button(182, 42, 279, 170, 17, this.aeReview.review ? "取消保存" : "关闭详情", true, mx, my);
        if (this.aeReview.review) ui.canvas.button(
            181,
            224,
            279,
            198,
            17,
            this.aeReview.blockerCount > 0 ? "请先解除阻塞" : "已核对 · 继续保存",
            this.aeReview.canConfirm && this.aeReview.matches(ui.session.state().session, ui.session.revision())
                && ui.session.pendingSequence() < 0,
            mx,
            my);
        ui.canvas.button(
            216,
            426,
            279,
            102,
            17,
            "移除阻塞方块",
            ui.session.editable() && ui.session.state().data.getBoolean("virtual") && this.aeReview.blockerCount > 0,
            mx,
            my);
        ui.canvas.button(
            214,
            536,
            279,
            102,
            17,
            "一键去标识",
            ui.session.editable() && ui.session.state().data.getBoolean("virtual"),
            mx,
            my);
    }

    void activateAeReview(int id) {
        if (this.aeReview == null) return;
        if (id == 214 || id == 216) {
            ui.identity.deidentificationLevel(ui.identity.deidentificationChoice(false));
            ui.identity.activateDeidentification(id);
            return;
        }
        if (id == 181) {
            if (!this.aeReview.canConfirm || !this.aeReview.matches(ui.session.state().session, ui.session.revision())
                || ui.session.pendingSequence() >= 0) return;
            NBTTagCompound exact = this.aeReview.action();
            this.aeReview = null;
            ui.library.presetSaveSequence(ui.session.sequence() + 1);
            ui.session.send("confirmAeSave", exact, true);
        } else if (id == 182) {
            HologramAeReviewModel closed = this.aeReview;
            this.aeReview = null;
            if (closed.review && HologramLibraryClient.dedicated()) HologramLibraryClient.INSTANCE.cancelDraftReview();
            if (closed.review) ui.session.send("cancelAeSave", closed.action(), false);
        } else if (id >= 185 && id <= 187) {
            this.aeReviewTab = id - 185;
            this.aeReviewGroup = this.aeReviewSelected = -1;
            this.aeReviewOffset = this.aeDetailOffset = 0;
        } else if (id == 188 || id == 189) ui.ae.aePage(id == 188 ? -5 : 5);
        else if (id == 190 || id == 191) this.aeDetailOffset = Math.max(0, this.aeDetailOffset + (id == 190 ? -3 : 3));
        else if (id == 192 && this.aeReviewTab == 1
            && this.aeReviewSelected >= 0
            && this.aeReviewSelected < ui.ae.aeIssueRows()
                .size()) {
                    HologramAeReviewModel.Issue issue = ui.ae.aeIssueRows()
                        .get(this.aeReviewSelected);
                    if (!issue.location || !ui.session.editable() || ui.session.pendingSequence() >= 0) return;
                    if (!this.aeReview.matches(ui.session.state().session, ui.session.revision())) {
                        ui.session.notice("报告已更新，请重新查看当前问题后定位。");
                        return;
                    }
                    ui.ae.activateAeReview(182);
                    ui.ae.beginAeReviewNavigation(issue);
                } else
            if (id >= 40000 && id < 48192 && this.aeReviewTab == 0) {
                int row = id - 40000;
                if (row >= this.aeReview.groups.size()) return;
                this.aeReviewGroup = row;
                this.aeReviewTab = 1;
                this.aeReviewOffset = this.aeDetailOffset = 0;
                this.aeReviewSelected = -1;
            } else if (id >= 50000 && id < 58192 && this.aeReviewTab == 1) {
                int row = id - 50000;
                if (row >= ui.ae.aeIssueRows()
                    .size()) return;
                this.aeReviewSelected = row;
                this.aeDetailOffset = 0;
            }
    }

    void beginAeReviewNavigation(HologramAeReviewModel.Issue issue) {
        boolean replace = "replaceCell".equals(issue.resolution);
        if (!HologramAeReviewNavigation
            .needsSelection(ui.session.state().data.getString("activeStructure"), issue.structureId)) {
            ui.ae.finishAeReviewNavigation(issue.x, issue.y, issue.z, issue.structureId, replace);
            return;
        }
        ui.preview.cancelQueuedEdits("正在定位相关结构，已取消等待中的编辑");
        this.aeReviewNavigation = new HologramAeReviewNavigation(
            ui.session.state().session,
            ui.session.sequence() + 1,
            ui.session.revision(),
            issue.structureId,
            issue.x,
            issue.y,
            issue.z,
            replace);
        NBTTagCompound request = new NBTTagCompound();
        request.setString("structureId", issue.structureId);
        request.setLong("planRevision", ui.session.revision());
        request.setBoolean("virtualOnly", true);
        ui.session.send("selectStructure", request, true);
        ui.session.notice("正在切换到相关结构，请稍候…");
    }

    boolean hasPendingAeReviewNavigation() {
        return this.aeReviewNavigation != null;
    }

    void completeAeReviewNavigation() {
        if (this.aeReviewNavigation == null) return;
        HologramAeReviewNavigation pending = this.aeReviewNavigation;
        HologramAeReviewNavigation.Result result = pending.observe(
            ui.session.state().session,
            ui.session.state().data.getLong("uiSequence"),
            ui.session.revision(),
            ui.session.state().data.getString("activeStructure"),
            ui.session.areaImporting(),
            ui.session.editable() && ui.session.state().data.getBoolean("virtual"));
        if (result == HologramAeReviewNavigation.Result.WAITING) return;
        this.aeReviewNavigation = null;
        if (result == HologramAeReviewNavigation.Result.READY)
            ui.ae.finishAeReviewNavigation(pending.x, pending.y, pending.z, pending.structureId, pending.replaceCell);
        else ui.session.notice(pending.failure() + " " + ui.session.state().data.getString("status"));
    }

    void finishAeReviewNavigation(int x, int y, int z, String structureId, boolean replace) {
        HologramState.Cell target = null;
        for (HologramState.Cell cell : ui.session.state().cells) if (cell.x == x && cell.y == y
            && cell.z == z
            && (structureId.isEmpty() || cell.structureIds.isEmpty() || cell.belongsToStructure(structureId))) {
                target = cell;
                break;
            }
        if (target == null || !ui.session.state().data.getString("activeStructure")
            .equals(structureId) || !ui.session.editable()) {
            ui.session.notice("相关方块或结构已变化，请重新查看当前报告后定位。");
            return;
        }
        ui.preview.selection()
            .load(new int[] { target.index }, target.index, ui.session.state().cells.size());
        ui.preview.selected(target.index);
        ui.config.syncTargetSelectors();
        if (replace) ui.session.notice("请替换或删除 " + x + "," + y + "," + z + " 的蓝图方块，再重新保存。");
        else ui.ae.openAePreview(x, y, z, structureId);
    }

    void cancelAeReviewNavigation() {
        if (this.aeReviewNavigation == null) return;
        this.aeReviewNavigation.cancel();
        this.aeReviewNavigation = null;
        ui.session.notice("已取消定位。");
    }

    void drawAeReviewNavigation(int mx, int my) {
        ui.canvas.controls()
            .clear();
        ui.canvas.tooltip(null);
        drawRect(90, 96, 590, 248, 0xfc172f36);
        ui.canvas.text("正在定位相关蓝图结构…", 110, 114, 460, 0xffe59b);
        ui.canvas.text("结构：" + this.aeReviewNavigation.structureId, 110, 139, 460, 0x8bdddf);
        ui.canvas.text("切换完成后，将自动定位到对应方块。", 110, 161, 460, 0xa9d6cf);
        ui.canvas.button(194, 220, 205, 240, 20, "取消定位 · Esc / E", true, mx, my);
    }

    boolean canOpenAePreview() {
        HologramState.Cell cell = ui.session.selectedCell();
        return ui.session.editable() && ui.session.pendingSequence() < 0
            && ui.preview.selection()
                .indices().length == 1
            && cell != null
            && com.miaokatze.gtmb.hologram.HologramAeBlueprint
                .supports(cell.block(false), ui.ae.aePreviewPayload(cell));
    }

    NBTTagCompound aePreviewPayload(HologramState.Cell cell) {
        // Read only the existing server-issued display snapshot. Recognition must not run native analysis.
        if (cell.targetDisplay.hasKey("nativeTile", 10)) return cell.targetDisplay.getCompoundTag("nativeTile");
        if (cell.wantId.equals(cell.id) && cell.wantMeta == cell.meta)
            return cell.actualDisplay.getCompoundTag("nativeTile");
        return new NBTTagCompound();
    }

    void openAePreview() {
        if (!ui.ae.canOpenAePreview()) return;
        HologramState.Cell cell = ui.session.selectedCell();
        ui.ae.openAePreview(cell.x, cell.y, cell.z, ui.config.selectedStructureId());
    }

    void openAePreview(int x, int y, int z, String structureId) {
        if (!ui.session.editable() || ui.session.pendingSequence() >= 0) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", ui.session.revision());
        request.setInteger("x", x);
        request.setInteger("y", y);
        request.setInteger("z", z);
        request.setString("structureId", structureId);
        this.awaitingAePreview = true;
        ui.session.send("aePreviewOpen", request, true);
    }

    boolean consumeNavigationControl(int id) {
        if (this.aeReviewNavigation != null) {
            if (id == 194) ui.ae.cancelAeReviewNavigation();
            return true;
        }
        return false;
    }

    boolean consumeReviewControl(int id) {
        if (this.aeReview != null) {
            ui.ae.activateAeReview(id);
            return true;
        }
        return false;
    }

    boolean inspectControl(int id) {
        if (id == 184) {
            this.aeReview = HologramAeReviewModel.inspection(ui.session.state());
            this.aeReviewTab = this.aeReviewOffset = this.aeDetailOffset = 0;
            this.aeReviewGroup = this.aeReviewSelected = -1;
            return true;
        }
        return false;
    }

    boolean openPreviewControl(int id) {
        if (id == 179) {
            ui.ae.openAePreview();
            return true;
        }
        return false;
    }

    boolean consumeNavigationMouse(int mx, int my, int button) {
        if (this.aeReviewNavigation != null) {
            if (button == 0) for (Control control : ui.canvas.controls())
                if (control.id == 194 && control.enabled && control.contains(mx, my)) ui.ae.cancelAeReviewNavigation();
            return true;
        }
        return false;
    }

    boolean consumeReviewMouse(int mx, int my, int button) {
        if (this.aeReview != null) {
            if (button == 0) for (Control control : ui.canvas.controls()) if (control.contains(mx, my)) {
                if (control.enabled) ui.ae.activateAeReview(control.id);
                return true;
            }
            return true;
        }
        return false;
    }

    boolean consumeNavigationKey(char c, int key) {
        if (this.aeReviewNavigation != null) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) ui.ae.cancelAeReviewNavigation();
            return true;
        }
        return false;
    }

    boolean consumeReviewKey(char c, int key) {
        if (this.aeReview != null) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) ui.ae.activateAeReview(182);
            return true;
        }
        return false;
    }

    boolean consumeReviewWheel(int wheel) {
        if (this.aeReview != null) {
            int modalY = ui.canvas.localY(
                ui.host.screenHeight() - Mouse.getEventY() * ui.host.screenHeight() / ui.host.minecraft().displayHeight
                    - 1);
            if (modalY >= 238) this.aeDetailOffset = Math.max(0, this.aeDetailOffset + (wheel > 0 ? -1 : 1));
            else ui.ae.aePage(wheel > 0 ? -1 : 1);
            return true;
        }
        return false;
    }

    void pauseForImport() {
        awaitingAePreview = false;
    }

    void acknowledgePreview(HologramState next) {
        if (awaitingAePreview && ui.session.pendingSequence() < 0) {
            awaitingAePreview = false;
            NBTTagCompound preview = next.data.getCompoundTag("aePreview");
            if (!preview.getString("token")
                .isEmpty() && next.session.equals(preview.getString("session"))) {
                aePreviewTransition = true;
                ui.host.minecraft()
                    .displayGuiScreen(new HologramAeContainerScreen(ui.host.parent(), preview));
            } else ui.session.notice(next.data.getString("status"));
        }
    }

    int aeDetailOffset() {
        return this.aeDetailOffset;
    }

    boolean aePreviewTransition() {
        return this.aePreviewTransition;
    }

    boolean aePreviewTransition(boolean value) {
        this.aePreviewTransition = value;
        return value;
    }

    HologramAeReviewModel aeReview() {
        return this.aeReview;
    }

    int aeReviewGroup() {
        return this.aeReviewGroup;
    }

    HologramAeReviewNavigation aeReviewNavigation() {
        return this.aeReviewNavigation;
    }

    int aeReviewOffset() {
        return this.aeReviewOffset;
    }

    int aeReviewSelected() {
        return this.aeReviewSelected;
    }

    int aeReviewTab() {
        return this.aeReviewTab;
    }

    boolean awaitingAePreview() {
        return this.awaitingAePreview;
    }

    boolean commandAllowed(String op) {
        return aeReviewNavigation == null || "selectStructure".equals(op)
            || "close".equals(op)
            || "cancelAeSave".equals(op)
            || "aePreviewClose".equals(op);
    }
}
