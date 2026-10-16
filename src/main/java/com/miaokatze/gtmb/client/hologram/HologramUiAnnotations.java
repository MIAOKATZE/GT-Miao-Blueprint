package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

/** Internal annotations workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiAnnotations {

    private final HologramUiModules ui;

    HologramUiAnnotations(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean promptDrawer;

    private boolean versionsDrawer;

    private int promptScope;

    private int versionsOffset;

    private HologramMuiTextField promptField;

    private HologramState annotationState;

    private HologramState promptSummaryState;

    private List<HologramAnnotationMarkers.Marker> annotationMarkers = new ArrayList<>();

    private final List<HologramAnnotationMarkers.Marker> visibleMarkers = new ArrayList<>();

    private String pinnedAnnotation = "";

    private int pinnedAnnotationOffset;

    private int summaryScope = -1;

    private String promptScopeSummary = "";

    private String promptSavedSummary = "";

    boolean annotationInteractive() {
        return ui.ae.aeReviewNavigation() == null && ui.ae.aeReview() == null
            && !ui.identity.deidentificationDrawer()
            && !ui.preview.inventory()
            && (ui.preview.previewInteractive() || ui.library.libraryDrawer() && !ui.review.confirmation()
                && !ui.config.resetReview()
                && ui.library.deletePresetId()
                    .isEmpty()
                && !ui.nbt.nbtDrawer()
                && !this.promptDrawer
                && !this.versionsDrawer
                && !ui.shortcuts.shortcutsDrawer());
    }

    void drawPromptEditor(int mx, int my) {
        drawRect(50, 77, 428, 268, 0xff172f36);
        ui.canvas.text(
            "提示词与施工排除 · 已选 " + ui.preview.selection()
                .size() + " 格",
            62,
            87,
            354,
            0xf5e4b9);
        String[] scopes = { "全局", "方块", "提示区" };
        for (int i = 0; i < 3; i++) ui.canvas.button(
            152 + i,
            62 + i * 118,
            105,
            112,
            17,
            (this.promptScope == i ? "›" : "") + scopes[i],
            i == 0 || ui.preview.selected() >= 0,
            mx,
            my);
        this.promptField.drawTextBox();
        if (this.promptSummaryState != ui.session.state() || this.summaryScope != this.promptScope) {
            this.promptSummaryState = ui.session.state();
            this.summaryScope = this.promptScope;
            int[] indices = ui.preview.selection()
                .indices();
            this.promptScopeSummary = HologramAnnotations.scope(ui.session.state(), indices, this.promptScope);
            this.promptSavedSummary = HologramAnnotations.saved(ui.session.state(), indices, this.promptScope);
        }
        ui.canvas.text(this.promptScopeSummary, 62, 155, 354, 0x94afb4);
        List<String> saved = ui.host.font()
            .listFormattedStringToWidth("已存：" + this.promptSavedSummary, 354);
        for (int i = 0; i < Math.min(3, saved.size()); i++)
            ui.canvas.text(saved.get(i), 62, 170 + i * 11, 354, 0xffe59b);
        ui.canvas.button(155, 62, 210, 171, 17, "排除所选施工", ui.preview.selected() >= 0 && ui.session.editable(), mx, my);
        ui.canvas.button(156, 245, 210, 171, 17, "恢复所选施工", ui.preview.selected() >= 0 && ui.session.editable(), mx, my);
        ui.canvas.button(151, 62, 240, 171, 17, "取消", true, mx, my);
        ui.canvas.button(
            158,
            245,
            240,
            171,
            17,
            "应用此范围提示",
            ui.session.editable() && (this.promptScope == 0 || ui.preview.selection()
                .size() > 0),
            mx,
            my);
    }

    void drawAnnotations(int mx, int my) {
        if (this.annotationState != ui.session.state()) {
            this.annotationState = ui.session.state();
            this.annotationMarkers = HologramAnnotationMarkers.collect(ui.session.state());
        }
        this.visibleMarkers.clear();
        int globalMarkerSlot = 0;
        for (HologramAnnotationMarkers.Marker marker : this.annotationMarkers) {
            if (marker.bounds == null) {
                marker.x = HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 18;
                marker.y = 64 + 15 * globalMarkerSlot++;
            } else {
                int[] b = marker.bounds;
                if (!ui.preview.allLayers() && (ui.preview.layer() < b[1] || ui.preview.layer() > b[4])) continue;
                double[] p = HologramRenderer.project((b[0] + b[3]) / 2D, (b[1] + b[4]) / 2D, (b[2] + b[5]) / 2D);
                marker.x = (int) p[0];
                marker.y = (int) p[1];
                if (marker.x < HologramUiCanvas.viewportX + 8
                    || marker.x >= HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 8
                    || marker.y < 80
                    || marker.y >= 261) continue;
                // Keep nearby markers independently clickable without covering the annotated voxel.
                for (HologramAnnotationMarkers.Marker shown : this.visibleMarkers)
                    if (Math.abs(marker.x - shown.x) < 14 && Math.abs(marker.y - shown.y) < 14) marker.y += 15;
                if (marker.y >= 261) continue;
            }
            this.visibleMarkers.add(marker);
            int x = marker.x, y = marker.y;
            int color = marker.key.equals(this.pinnedAnnotation) ? 0xff7ce1ec
                : marker.key.startsWith("ae:") ? 0xffffb65e : 0xffe6c980;
            drawRect(x - 6, y - 5, x + 5, y + 6, 0xff514634);
            drawRect(x - 4, y - 7, x + 7, y + 4, color);
            drawRect(x + 5, y - 5, x + 8, y + 6, 0xff9e8353);
            ui.canvas.text(marker.key.startsWith("ae:") ? "!" : "?", x - 1, y - 5, 8, 0xff172f36);
            if (marker.contains(mx, my)) {
                if (ui.canvas.tooltip() == null) ui.canvas.tooltip(new ArrayList<>());
                else ui.canvas.tooltip(new ArrayList<>(ui.canvas.tooltip()));
                ui.canvas.tooltip()
                    .add(marker.title + " · 点击固定 / 再次点击收起");
                ui.canvas.tooltip()
                    .addAll(
                        ui.host.font()
                            .listFormattedStringToWidth(marker.prompt, 250));
            }
            if (this.visibleMarkers.size() >= 64) break;
        }
        if (!this.pinnedAnnotation.isEmpty()) {
            HologramAnnotationMarkers.Marker pinned = null;
            for (HologramAnnotationMarkers.Marker marker : this.annotationMarkers)
                if (marker.key.equals(this.pinnedAnnotation)) pinned = marker;
            if (pinned == null) {
                this.pinnedAnnotation = "";
                return;
            }
            List<String> lines = ui.host.font()
                .listFormattedStringToWidth(pinned.prompt, HologramUiCanvas.viewportWidth - 20);
            this.pinnedAnnotationOffset = Math
                .max(0, Math.min(this.pinnedAnnotationOffset, Math.max(0, lines.size() - 4)));
            drawRect(
                HologramUiCanvas.viewportX + 1,
                219,
                HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 1,
                279,
                0xf0172f36);
            ui.canvas.text(
                pinned.title + " · 滚轮查看 · Esc 收起",
                HologramUiCanvas.viewportX + 8,
                224,
                HologramUiCanvas.viewportWidth - (pinned.key.startsWith("ae:") ? 108 : 32),
                0xffe59b);
            if (pinned.key.startsWith("ae:")) ui.canvas.button(
                184,
                HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 104,
                221,
                78,
                15,
                "AE 全部详情",
                true,
                mx,
                my);
            drawRect(
                HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 20,
                221,
                HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 5,
                234,
                0xff344d51);
            ui.canvas.text("×", HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 16, 224, 9, 0xffe59b);
            for (int i = 0; i < 4 && i + this.pinnedAnnotationOffset < lines.size(); i++) ui.canvas.text(
                lines.get(i + this.pinnedAnnotationOffset),
                HologramUiCanvas.viewportX + 8,
                237 + i * 10,
                HologramUiCanvas.viewportWidth - 16,
                0xb9d5d4);
        }
    }

    boolean toggleAnnotationAt(int mx, int my) {
        for (Control control : ui.canvas.controls()) if (control.id == 184 && control.contains(mx, my)) {
            ui.router.activate(184);
            return true;
        }
        if (!this.pinnedAnnotation.isEmpty() && mx >= HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 20
            && mx < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 5
            && my >= 221
            && my < 234) {
            this.pinnedAnnotation = "";
            return true;
        }
        for (int i = this.visibleMarkers.size() - 1; i >= 0; i--) {
            HologramAnnotationMarkers.Marker marker = this.visibleMarkers.get(i);
            if (!marker.contains(mx, my)) continue;
            this.pinnedAnnotation = marker.key.equals(this.pinnedAnnotation) ? "" : marker.key;
            this.pinnedAnnotationOffset = 0;
            return true;
        }
        return false;
    }

    void drawVersions(int mx, int my) {
        drawRect(50, 58, 428, 279, 0xff172f36);
        ui.canvas.text("蓝图来源与 Mod 版本", 62, 68, 354, 0xf5e4b9);
        ui.canvas.text("缺失 红 · 过时 橙 · 超前 青", 62, 85, 354, 0x94afb4);
        if (ui.session.state().data.getBoolean("sourceMissing")) {
            String warning = "来源结构缺失：" + HologramUiText.reason(ui.session.state().data.getString("sourceWarning"));
            ui.canvas.text(warning, 62, 96, 354, 0xff7272);
            if (mx >= 62 && mx < 416 && my >= 94 && my < 106) {
                ui.canvas.tooltip(
                    new ArrayList<>(
                        ui.host.font()
                            .listFormattedStringToWidth(warning, 300)));
                ui.canvas.tooltip()
                    .addAll(
                        ui.host.font()
                            .listFormattedStringToWidth(ui.session.state().data.getString("sourceClass"), 300));
            }
        }
        NBTTagList versions = ui.session.state().data.getTagList("modVersions", 10);
        this.versionsOffset = Math.max(0, Math.min(this.versionsOffset, Math.max(0, versions.tagCount() - 12)));
        for (int i = this.versionsOffset; i < Math.min(versions.tagCount(), this.versionsOffset + 12); i++) {
            NBTTagCompound row = versions.getCompoundTagAt(i);
            String condition = row.getString("state");
            int color = "missing".equals(condition) ? 0xff7272
                : "outdated".equals(condition) ? 0xffbd72 : "ahead".equals(condition) ? 0x69d7ed : 0xa2e8b8;
            int y = 106 + (i - this.versionsOffset) * 11;
            String label = row.getString("modId") + " · 蓝图 "
                + row.getString("saved")
                + " → 当前 "
                + row.getString("current");
            ui.canvas.text(label, 62, y, 354, color);
            if (mx >= 62 && mx < 416 && my >= y && my < y + 11) ui.canvas.tooltip(
                ui.host.font()
                    .listFormattedStringToWidth(label, 300));
        }
        HologramState.Cell cell = ui.session.selectedCell();
        if (cell != null && (cell.missingBlock || cell.sourceMissing)) {
            String warning = cell.sourceMissing ? "来源结构缺失：" + HologramUiText.reason(cell.sourceWarning)
                : "缺失方块 " + cell.wantId + " · " + cell.sourceMod + " " + cell.sourceVersion;
            ui.canvas.text(warning, 62, 240, 354, 0xff7272);
            if (mx >= 62 && mx < 416 && my >= 238 && my < 252) {
                ui.canvas.tooltip(
                    new ArrayList<>(
                        ui.host.font()
                            .listFormattedStringToWidth(warning, 300)));
                if (!cell.sourceClass.isEmpty()) ui.canvas.tooltip()
                    .addAll(
                        ui.host.font()
                            .listFormattedStringToWidth(cell.sourceClass, 300));
            }
        }
        ui.canvas.button(151, 62, 255, 171, 17, "返回", true, mx, my);
        ui.canvas.button(160, 245, 255, 171, 17, "校准为当前版本", ui.session.editable(), mx, my);
    }

    boolean activateControl(int id) {
        switch (id) {
            case 227:
            case 146:
                this.promptDrawer = true;
                this.promptSummaryState = null;
                this.promptScope = id == 227 ? 0 : ui.preview.selected() >= 0 ? 1 : 0;
                this.promptField.setText(
                    HologramAnnotations.input(
                        ui.session.state(),
                        ui.preview.selection()
                            .indices(),
                        this.promptScope));
                return true;
            case 150:
                this.versionsDrawer = true;
                return true;
            case 151:
                this.promptDrawer = this.versionsDrawer = false;
                this.promptField.setFocused(false);
                return true;
            case 152:
            case 153:
            case 154:
                this.promptScope = id - 152;
                this.promptField.setText(
                    HologramAnnotations.input(
                        ui.session.state(),
                        ui.preview.selection()
                            .indices(),
                        this.promptScope));
                return true;
            case 155:
            case 156:
                if (ui.session.editable() && ui.preview.selection()
                    .size() > 0) {
                    NBTTagCompound flags = new NBTTagCompound();
                    flags.setIntArray(
                        "indices",
                        ui.preview.selection()
                            .indices());
                    flags.setBoolean("excluded", id == 155);
                    ui.session.send("editCellFlags", flags, true);
                }
                return true;
            case 158:
                if (ui.session.editable()) {
                    NBTTagCompound prompt = new NBTTagCompound();
                    prompt.setString("prompt", this.promptField.getText());
                    prompt.setIntArray(
                        "indices",
                        ui.preview.selection()
                            .indices());
                    ui.session.send(
                        this.promptScope == 0 ? "editBlueprintPrompt"
                            : this.promptScope == 1 ? "editCellFlags" : "editHintArea",
                        prompt,
                        true);
                    ui.session.notice(
                        "提示已提交 · " + HologramAnnotations.scope(
                            ui.session.state(),
                            ui.preview.selection()
                                .indices(),
                            this.promptScope));
                    this.promptDrawer = false;
                    this.promptField.setFocused(false);
                }
                return true;
            case 160:
                if (ui.session.editable()) ui.session.send("calibrateBlueprintVersions", null, true);
                return true;
            default:
                return false;
        }
    }

    boolean consumeAnnotationModalMouse(int mx, int my, int button) {
        if (this.promptDrawer || this.versionsDrawer) {
            if (this.promptDrawer) this.promptField.mouseClicked(mx, my, button);
            if (button == 0) for (Control control : ui.canvas.controls()) if (control.contains(mx, my)) {
                if (control.enabled) ui.router.activate(control.id);
                return true;
            }
            return true;
        }
        return false;
    }

    boolean consumeAnnotationModalKey(char c, int key) {
        if (this.promptDrawer || this.versionsDrawer) {
            if (key == Keyboard.KEY_ESCAPE) {
                this.promptDrawer = this.versionsDrawer = false;
                return true;
            }
            if (this.promptDrawer) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) ui.router.activate(158);
                else this.promptField.textboxKeyTyped(c, key);
            }
            return true;
        }
        return false;
    }

    boolean consumeVersionsWheel(int wheel) {
        if (this.versionsDrawer) {
            this.versionsOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        ui.session.state().data.getTagList("modVersions", 10)
                            .tagCount() - 12),
                    this.versionsOffset + (wheel > 0 ? -1 : 1)));
            return true;
        }
        return false;
    }

    boolean consumePinnedAnnotationWheel(int wheel, int mx, int my) {
        if (!this.pinnedAnnotation.isEmpty() && ui.canvas.inViewport(mx, my) && my >= 219 && my < 279) {
            this.pinnedAnnotationOffset = Math.max(0, this.pinnedAnnotationOffset + (wheel > 0 ? -1 : 1));
            return true;
        }
        return false;
    }

    void resetSession() {
        pinnedAnnotation = "";
    }

    List<HologramAnnotationMarkers.Marker> annotationMarkers() {
        return this.annotationMarkers;
    }

    String pinnedAnnotation() {
        return this.pinnedAnnotation;
    }

    String pinnedAnnotation(String value) {
        this.pinnedAnnotation = value;
        return value;
    }

    boolean promptDrawer() {
        return this.promptDrawer;
    }

    boolean promptDrawer(boolean value) {
        this.promptDrawer = value;
        return value;
    }

    HologramMuiTextField promptField() {
        return this.promptField;
    }

    HologramMuiTextField promptField(HologramMuiTextField value) {
        this.promptField = value;
        return value;
    }

    int promptScope(int value) {
        this.promptScope = value;
        return value;
    }

    boolean versionsDrawer() {
        return this.versionsDrawer;
    }

    boolean versionsDrawer(boolean value) {
        this.versionsDrawer = value;
        return value;
    }

    List<HologramAnnotationMarkers.Marker> visibleMarkers() {
        return this.visibleMarkers;
    }

    void initializeFields() {
        ui.annotations.promptField(new HologramMuiTextField(ui.host.parent(), 62, 129, 354, 18));
        ui.annotations.promptField()
            .setMaxStringLength(1024);
        ui.annotations.promptField()
            .setText(ui.session.state().data.getString("blueprintPrompt"));
    }

    void tickField() {
        if (ui.annotations.promptField() != null) ui.annotations.promptField()
            .updateCursorCounter();
    }
}
