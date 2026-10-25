package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;

/** Internal identity workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiDeidentification {

    private final HologramUiModules ui;

    HologramUiDeidentification(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean deidentificationDrawer;

    private boolean deidentificationPlacement;

    private int deidentificationLevel = 1;

    private long offeredDeidentificationNonce = -1;

    boolean hasPlacementIdentityChoices() {
        NBTTagCompound state = ui.session.state().data;
        return state.getBoolean("virtual") && HologramDeidentificationMemory.minimum(state) < 10
            && (state.getCompoundTag("processing")
                .getInteger("requiredStages") & (1 << 1)) != 0;
    }

    int deidentificationChoice(boolean placement) {
        if (!placement && (ui.session.state().data.getBoolean("blueprintProcessingComplete")
            || ui.session.state().data.getBoolean("blueprintDirectSave")
            || authoredIdentityChosen(ui.session.state().data)))
            return ui.session.state().data.getInteger("blueprintDeidentificationLevel");
        HologramDeidentificationMemory memory = HologramDeidentificationMemory.client();
        return HologramDeidentificationMemory.level(
            ui.session.state().data,
            placement,
            memory.choice(HologramDeidentificationMemory.source(ui.session.state().data)));
    }

    /** A geometry review does not undo the author's already selected identity level, including zero. */
    private static boolean authoredIdentityChosen(NBTTagCompound state) {
        return state.getBoolean("deidentificationChosen") && state.hasKey("blueprintDeidentificationLevel", 3);
    }

    void offerPlacementDeidentification() {
        if (ui.session.state().data.getBoolean("placementValidating")) return;
        if (!ui.review.placementReviewActive()
            || !ui.session.state().data.getBoolean("placementDeidentificationRecommended")
            || ui.session.state().data.getBoolean("placementDeidentificationChosen")
            || ui.ae.aeReview() != null
            || HologramDeidentificationMemory.minimum(ui.session.state().data) > 0
            || HologramDeidentificationMemory.client()
                .choice(HologramDeidentificationMemory.source(ui.session.state().data)) != null)
            return;
        long nonce = ui.session.state().data.getLong("placementReviewNonce");
        if (this.offeredDeidentificationNonce == nonce) return;
        this.offeredDeidentificationNonce = nonce;
        ui.identity.openDeidentification(true);
    }

    void drawDeidentificationLibraryControl(int mx, int my) {
        ui.canvas.button(
            211,
            35,
            284,
            407,
            17,
            "保存去标识等级 " + ui.identity.deidentificationChoice(false)
                + " · 推荐 "
                + ui.session.state().data.getInteger("deidentificationRecommendedLevel")
                + " · 点击选择 0–10",
            ui.session.editable() && !ui.session.state().cells.isEmpty(),
            mx,
            my);
    }

    void openDeidentification(boolean placement) {
        this.deidentificationPlacement = placement;
        this.deidentificationLevel = ui.identity.deidentificationChoice(placement);
        this.deidentificationDrawer = true;
        ui.preview.dragging(ui.preview.boxSelecting(ui.preview.inventoryDragging(false)));
        ui.preview.gizmo()
            .cancel();
        if (ui.library.presetNameField() != null) ui.library.presetNameField()
            .setFocused(false);
    }

    void drawDeidentification(int mx, int my) {
        drawRect(40, 47, 650, 310, 0xff172f36);
        ui.canvas.text(this.deidentificationPlacement ? "本次放置校验与去标识" : "保存校验与去标识", 54, 59, 580, 0xf5e4b9);
        int actual = ui.session.state().data.getInteger(
            this.deidentificationPlacement ? "placementDeidentificationLevel" : "blueprintDeidentificationLevel");
        ui.canvas.text(
            "当前等级 " + actual
                + " · 选择等级 "
                + this.deidentificationLevel
                + " · 推荐等级 "
                + ui.session.state().data.getInteger("deidentificationRecommendedLevel"),
            54,
            82,
            580,
            0x8bdddf);
        int minimum = HologramDeidentificationMemory.minimum(ui.session.state().data);
        ui.canvas.text(
            minimum > 0 ? "已处理的数据无法还原；这份蓝图最低可选等级为 " + minimum + "。" : "等级 0 保留原始身份；等级越高，清除的外部身份与运行数据越多。",
            54,
            101,
            580,
            0xe5c28d);
        for (int level = 0; level <= 10; level++) ui.canvas.button(
            22000 + level,
            54 + level * 53,
            122,
            48,
            20,
            (level == this.deidentificationLevel ? "✓ " : "") + level,
            level >= minimum,
            mx,
            my);
        ui.canvas.text("1 身份与网络 · 2 外部坐标 · 3 频率 · 4 安全凭据 · 5 连接记录 · 6 屏幕 / 核心运行缓存", 54, 155, 580, 0xa9d6cf);
        ui.canvas.text("7 监控身份 / 图表缓存 · 8 私人界面页 · 9 停机诊断 · 10 瞬时运行状态；配方数据保留", 54, 172, 580, 0xa9d6cf);
        ui.canvas.text("记住你对这份蓝图的选择（含 0）；下次放置及 Alt 快捷预览沿用。", 54, 190, 580, 0x94afb4);
        ui.canvas.text(
            this.deidentificationPlacement ? "请先修正保存数据或清理阻塞物，再重新核对材料并确认放置。" : "保存仍会检查全部数据；同类相邻提示合并展示，可展开查看每个位置。",
            54,
            208,
            580,
            0xe5c28d);
        ui.canvas.button(
            214,
            54,
            233,
            278,
            20,
            "一键去标识处理 · 等级 " + Math.max(1, this.deidentificationLevel),
            ui.session.editable() && ui.session.state().data.getBoolean("virtual"),
            mx,
            my);
        ui.canvas.button(
            215,
            342,
            233,
            290,
            20,
            "一键移除放置阻塞物",
            ui.session.editable() && ui.review.placementReviewActive() && ui.review.materialBlockers() > 0,
            mx,
            my);
        ui.canvas.button(212, 54, 274, 278, 20, this.deidentificationPlacement ? "无需去标识" : "取消", true, mx, my);
        ui.canvas.button(
            213,
            342,
            274,
            290,
            20,
            this.deidentificationPlacement ? "记住并应用本次选择" : "记住此蓝图的保存选择",
            ui.session.editable(),
            mx,
            my);
    }

    boolean activateDeidentification(int id) {
        if (id == 211) {
            ui.identity.openDeidentification(
                ui.review.confirmation() && ui.review.placementReviewActive()
                    || ui.nbt.nbtDrawer() && ui.nbt.nbtPlacement());
            return true;
        }
        if (!this.deidentificationDrawer && id != 214 && id != 215 && id != 216) return false;
        if (id >= 22000 && id <= 22010) {
            int level = id - 22000;
            if (level >= HologramDeidentificationMemory.minimum(ui.session.state().data))
                this.deidentificationLevel = level;
            return true;
        }
        if (id == 212) {
            if (!this.deidentificationPlacement) {
                this.deidentificationDrawer = false;
                return true;
            }
            this.deidentificationLevel = 0;
            id = 213;
        }
        if (id != 213 && id != 214 && id != 215 && id != 216) return false;
        if (!ui.session.editable()) return true;
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", ui.session.revision());
        request.setLong("placementReviewNonce", ui.session.state().data.getLong("placementReviewNonce"));
        if (id == 216) {
            if (ui.ae.aeReview() == null || ui.ae.aeReview().blockerCount == 0) return true;
            if (ui.ae.aeReview().review) ui.ae.activateAeReview(182);
            request.setBoolean("confirmDiscard", true);
            ui.session.send("excludeBlueprintBlockers", request, true);
            return true;
        }
        if (id == 215) {
            if (!ui.review.placementReviewActive() || ui.review.materialBlockers() == 0) return true;
            ui.session.send("removeBlueprintObstructions", request, true);
            this.deidentificationDrawer = false;
            return true;
        }
        int level = id == 214 ? Math.max(1, this.deidentificationLevel) : this.deidentificationLevel;
        if (this.deidentificationPlacement && ui.session.state().data.getBoolean("placementDeidentificationChosen")
            && level == ui.session.state().data.getInteger("placementDeidentificationLevel")) {
            this.deidentificationDrawer = false;
            return true;
        }
        HologramDeidentificationMemory.client()
            .remember(HologramDeidentificationMemory.source(ui.session.state().data), level);
        request.setInteger("deidentificationLevel", level);
        request.setInteger("level", level);
        request.setBoolean("deidentificationChosen", true);
        if (this.deidentificationPlacement)
            HologramClient.placementDeidentificationRequested(ui.session.state(), level);
        if (id == 214) {
            if (ui.ae.aeReview() != null && ui.ae.aeReview().review) {
                // Explicitly cancel the exact old save review; processing never confirms that nonce.
                ui.ae.activateAeReview(182);
            }
            ui.session.send(
                this.deidentificationPlacement ? "configurePlacementDeidentification" : "deidentifyBlueprint",
                request,
                true);
        } else if (this.deidentificationPlacement) ui.session.send("configurePlacementDeidentification", request, true);
        else ui.session.notice("已记住保存去标识等级 " + level + "，保存时将重新校验");
        this.deidentificationDrawer = false;
        return true;
    }

    void deidentificationSaveOptions(NBTTagCompound options) {
        boolean chosen = ui.session.state().data.getBoolean("blueprintProcessingComplete")
            || authoredIdentityChosen(ui.session.state().data);
        Integer remembered = chosen ? null
            : HologramDeidentificationMemory.client()
                .choice(HologramDeidentificationMemory.source(ui.session.state().data));
        options.setInteger("deidentificationLevel", ui.identity.deidentificationChoice(false));
        options.setBoolean("deidentificationChosen", chosen || remembered != null);
        if (ui.session.state().data.getBoolean("blueprintProcessingComplete")) {
            options.setBoolean("retainNbt", ui.session.state().data.getBoolean("blueprintRetainNbt"));
            options.setBoolean("retainCovers", ui.session.state().data.getBoolean("blueprintRetainCovers"));
        }
        options.setLong("planRevision", ui.session.revision());
    }

    boolean consumeIdentityMouse(int mx, int my, int button) {
        if (this.deidentificationDrawer) {
            if (button == 0)
                for (Control control : ui.canvas.controls()) if (control.enabled && control.contains(mx, my)) {
                    ui.identity.activateDeidentification(control.id);
                    return true;
                }
            return true;
        }
        return false;
    }

    boolean consumeIdentityKey(char c, int key) {
        if (this.deidentificationDrawer) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) ui.identity.activateDeidentification(212);
            else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER)
                ui.identity.activateDeidentification(213);
            return true;
        }
        return false;
    }

    Integer prepareSnapshot(boolean sourceChanged) {
        Integer choice = HologramDeidentificationMemory.client()
            .choice(HologramDeidentificationMemory.source(ui.session.state().data));
        if (sourceChanged) deidentificationDrawer = false;
        return choice;
    }

    void resetSession() {
        offeredDeidentificationNonce = -1;
    }

    void acknowledgeSnapshot(HologramState next, Integer previousChoice, boolean canonicalSaveAck) {
        deidentificationLevel = Math.max(deidentificationLevel, HologramDeidentificationMemory.minimum(next.data));
        if (canonicalSaveAck && previousChoice != null) HologramDeidentificationMemory.client()
            .remember(
                HologramDeidentificationMemory.source(next.data),
                Math.max(previousChoice, HologramDeidentificationMemory.minimum(next.data)));
    }

    boolean deidentificationDrawer() {
        return this.deidentificationDrawer;
    }

    int deidentificationLevel(int value) {
        this.deidentificationLevel = value;
        return value;
    }
}
