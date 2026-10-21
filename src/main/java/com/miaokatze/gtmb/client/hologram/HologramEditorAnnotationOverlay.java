package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Viewport-local annotation geometry plus an independently laid out native pinned-note panel. */
final class HologramEditorAnnotationOverlay {

    private final HologramUiModules ui;
    private final List<String> tooltip = new ArrayList<>();
    private IPanelHandler handler;
    private NoteDialog note;
    private HologramState drawnState;

    HologramEditorAnnotationOverlay(HologramUiModules ui) {
        this.ui = ui;
    }

    void attach(ModularPanel host) {
        if (handler != null) return;
        note = new NoteDialog();
        handler = IPanelHandler.simple(host, (parent, player) -> note, true);
        ui.shell.registerTarget("annotation", note);
    }

    void refresh() {
        if (handler == null) return;
        HologramAnnotationMarkers.Marker pinned = pinned();
        if (!ui.annotations.pinnedAnnotation()
            .isEmpty() && pinned == null) ui.annotations.pinnedAnnotation("");
        boolean show = pinned != null && !ui.library.browsePreviewActive()
            && ui.annotations.annotationInteractive()
            && !ui.shell.hasModalPanel();
        if (show) {
            note.refresh(pinned);
            if (!handler.isPanelOpen()) handler.openPanel();
        } else if (handler.isPanelOpen()) note.closeIfOpen();
    }

    private HologramAnnotationMarkers.Marker pinned() {
        String key = ui.annotations.pinnedAnnotation();
        if (!key.isEmpty()) for (HologramAnnotationMarkers.Marker marker : ui.annotations.currentMarkers())
            if (key.equals(marker.key)) return marker;
        return null;
    }

    /** Invoked after model drawing while Canvas.nativeSceneSpace and the widget's local transform are active. */
    void drawScene(int mx, int my) {
        tooltip.clear();
        List<HologramAnnotationMarkers.Marker> shown = ui.annotations.visibleMarkers();
        shown.clear();
        drawnState = ui.session.state();
        if (ui.library.browsePreviewActive()) return;
        int width = (int) ui.canvas.viewport()
            .width(),
            height = (int) ui.canvas.viewport()
                .height();
        boolean inside = mx >= 0 && my >= 0 && mx < width && my < height;
        if (inside && ui.canvas.tooltip() != null) tooltip.addAll(ui.canvas.tooltip());
        if (inside) collectSceneTooltip(mx, my);
        if (!ui.annotations.annotationInteractive() || width < 32 || height < 32) return;
        int globalSlot = 0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            for (HologramAnnotationMarkers.Marker marker : ui.annotations.currentMarkers()) {
                if (marker.bounds == null) {
                    marker.x = width - 18;
                    marker.y = 16 + 15 * globalSlot++;
                } else {
                    int[] bounds = marker.bounds;
                    if (!ui.preview.allLayers() && (ui.preview.layer() < bounds[1] || ui.preview.layer() > bounds[4]))
                        continue;
                    double[] point = HologramRenderer.project(
                        (bounds[0] + (double) bounds[3]) / 2,
                        (bounds[1] + (double) bounds[4]) / 2,
                        (bounds[2] + (double) bounds[5]) / 2);
                    marker.x = (int) point[0];
                    marker.y = (int) point[1];
                }
                if (!place(marker, shown, width, height)) continue;
                shown.add(marker);
                paintMarker(marker);
                if (inside && marker.contains(mx, my)) {
                    tooltip.add(marker.title + " · 点击固定 / 再次点击收起");
                    tooltip.addAll(
                        ui.host.font()
                            .listFormattedStringToWidth(marker.prompt, 250));
                }
                if (shown.size() >= 64) break;
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /** Full icon and hit geometry remain inside the actual local viewport after collision shifts. */
    static boolean place(HologramAnnotationMarkers.Marker marker, List<HologramAnnotationMarkers.Marker> shown,
        int width, int height) {
        if (marker.x - 6 < 0 || marker.x + 8 > width || marker.y - 7 < 0) return false;
        while (marker.y + 7 <= height) {
            boolean collision = false;
            for (HologramAnnotationMarkers.Marker previous : shown)
                if (Math.abs(marker.x - previous.x) < 14 && Math.abs(marker.y - previous.y) < 14) {
                    marker.y = previous.y + 15;
                    collision = true;
                    break;
                }
            if (!collision) return true;
        }
        return false;
    }

    private void paintMarker(HologramAnnotationMarkers.Marker marker) {
        int x = marker.x, y = marker.y;
        int color = marker.blocker ? 0xffff3b35
            : marker.key.equals(ui.annotations.pinnedAnnotation()) ? 0xff7ce1ec
                : marker.key.startsWith("ae:") ? 0xffffb65e : 0xffe6c980;
        Gui.drawRect(x - 6, y - 5, x + 5, y + 6, 0xff514634);
        Gui.drawRect(x - 4, y - 7, x + 7, y + 4, color);
        Gui.drawRect(x + 5, y - 5, x + 8, y + 6, 0xff9e8353);
        if (marker.blocker) {
            for (int stroke = 0; stroke < 7; stroke++) {
                Gui.drawRect(x - 2 + stroke, y - 5 + stroke, x + stroke, y - 3 + stroke, 0xff000000);
                Gui.drawRect(x + 4 - stroke, y - 5 + stroke, x + 6 - stroke, y - 3 + stroke, 0xff000000);
            }
        } else ui.host.font()
            .drawString(marker.key.startsWith("ae:") ? "!" : "?", x - 1, y - 5, 0xff172f36);
    }

    boolean consumeSceneMouse(int mx, int my, int button) {
        if (button != 0 || drawnState != ui.session.state()
            || ui.library.browsePreviewActive()
            || !ui.annotations.annotationInteractive()
            || ui.shell.hasModalPanel()) return false;
        List<HologramAnnotationMarkers.Marker> shown = ui.annotations.visibleMarkers();
        for (int i = shown.size() - 1; i >= 0; i--) {
            HologramAnnotationMarkers.Marker marker = shown.get(i);
            if (!marker.contains(mx, my)) continue;
            ui.annotations.pinnedAnnotation(marker.key.equals(ui.annotations.pinnedAnnotation()) ? "" : marker.key);
            return true;
        }
        return false;
    }

    /** Read by SceneWidget's native tooltip builder; MUI positions/clamps it using the real located widget. */
    List<String> tooltipLines() {
        return Collections.unmodifiableList(tooltip);
    }

    private void collectSceneTooltip(int mx, int my) {
        HologramState state = ui.session.state();
        int hovered = ui.preview.hoveredCellIndex();
        if (hovered >= 0 && hovered < state.cells.size()) {
            HologramState.Cell cell = state.cells.get(hovered);
            if (cell.missingBlock) tooltip.add("缺失方块：" + cell.wantId + "（点击查看来源版本）");
            if (cell.sourceMissing) {
                tooltip.addAll(
                    ui.host.font()
                        .listFormattedStringToWidth("来源结构缺失：" + cell.sourceWarning, 260));
                if (!cell.sourceClass.isEmpty()) tooltip.addAll(
                    ui.host.font()
                        .listFormattedStringToWidth(cell.sourceClass, 260));
            }
            if (cell.excluded) tooltip.add("已排除施工");
        }
        String hint = HologramRenderer.hintAt(mx, my);
        if (!hint.isEmpty()) tooltip.addAll(
            ui.host.font()
                .listFormattedStringToWidth("提示区 " + hint, 260));
        String structure = HologramRenderer.pickStructure(mx, my);
        if (structure.isEmpty()) return;
        NBTTagList structures = state.data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            NBTTagCompound row = structures.getCompoundTagAt(i);
            if (!structure.equals(row.getString("id"))) continue;
            tooltip.add(row.getString("name") + " · 点击边缘整选");
            if (row.getBoolean("sourceMissing")) {
                tooltip.addAll(
                    ui.host.font()
                        .listFormattedStringToWidth("来源结构缺失：" + row.getString("sourceWarning"), 260));
                tooltip.addAll(
                    ui.host.font()
                        .listFormattedStringToWidth(row.getString("sourceClass"), 260));
            }
            break;
        }
    }

    private final class NoteDialog extends Dialog<Void> {

        private final ListWidget<IWidget, ?> lines = new ListWidget<>();
        private final ButtonWidget<?> details, processing;
        private HologramAnnotationMarkers.Marker marker;
        private String lastKey = "", lastPrompt = "";
        private int width = -1, height = -1;

        NoteDialog() {
            super(ui.panelName("gtmb_editor_pinned_annotation"));
            setDisablePanelsBelow(false);
            setCloseOnOutOfBoundsClick(false);
            setDraggable(true);
            background(HologramEditorNativeTheme.PANEL);
            Flow heading = Flow.row()
                .pos(8, 6)
                .height(22)
                .widthRelOffset(1f, -16)
                .childPadding(3);
            heading.child(
                HologramEditorNativeTheme
                    .label(() -> marker == null ? "提示" : marker.title, HologramEditorNativeTheme.TITLE)
                    .widthRel(1f)
                    .expanded()
                    .height(22));
            heading.child(
                HologramEditorNativeTheme.button(() -> "×", () -> ui.annotations.pinnedAnnotation(""))
                    .size(22));
            child(heading);
            lines.pos(8, 32)
                .background(IDrawable.NONE);
            child(lines);
            Flow actions = Flow.row()
                .left(8)
                .bottom(8)
                .height(22)
                .widthRelOffset(1f, -16)
                .childPadding(3);
            actions.child(
                ui.shell.nativeAction(146, () -> "所选提示", ui.session::editable, () -> "编辑当前选择的提示词；未选择方块时编辑全局提示。")
                    .widthRel(1f)
                    .expanded()
                    .height(22));
            actions.child(
                ui.shell.nativeAction(150, () -> "来源版本", () -> true, () -> "查看蓝图来源与 Mod 版本。")
                    .widthRel(1f)
                    .expanded()
                    .height(22));
            details = ui.shell.nativeAction(
                184,
                () -> "AE 全部详情",
                () -> marker != null && marker.key.startsWith("ae:"),
                () -> "查看全部 AE 提示与材料信息。");
            actions.child(
                details.widthRel(1f)
                    .expanded()
                    .height(22));
            processing = ui.shell.nativeAction(
                297,
                () -> "处理阻塞",
                () -> marker != null && marker.key.startsWith("resource:") && ui.session.editable(),
                () -> "通过原蓝图处理流程修正资源阻塞。");
            actions.child(
                processing.widthRel(1f)
                    .expanded()
                    .height(22));
            actions.collapseDisabledChild(true);
            child(actions);
        }

        void refresh(HologramAnnotationMarkers.Marker marker) {
            this.marker = marker;
            details.setEnabled(marker.key.startsWith("ae:"));
            processing.setEnabled(marker.key.startsWith("resource:"));
            int nextWidth = Math.max(96, Math.min(384, ui.host.screenWidth() - 16));
            int nextHeight = Math.max(96, Math.min(186, ui.host.screenHeight() - 16));
            boolean resized = width != nextWidth || height != nextHeight;
            if (resized) {
                width = nextWidth;
                height = nextHeight;
                size(width, height);
                int[] viewport = ui.shell.targetBounds("viewport");
                int x = viewport == null ? 8 : viewport[0] + 8;
                int y = viewport == null ? 8 : viewport[1] + viewport[3] - height - 8;
                pos(
                    Math.max(0, Math.min(ui.host.screenWidth() - width, x)),
                    Math.max(0, Math.min(ui.host.screenHeight() - height, y)));
                lines.size(width - 16, height - 70);
                scheduleResize();
            }
            if (!resized && lastKey.equals(marker.key) && lastPrompt.equals(marker.prompt)) return;
            lastKey = marker.key;
            lastPrompt = marker.prompt;
            lines.removeAll();
            for (String line : ui.host.font()
                .listFormattedStringToWidth(marker.prompt, Math.max(1, width - 28)))
                lines.child(
                    HologramEditorNativeTheme.label(() -> line)
                        .widthRel(1f)
                        .height(12));
        }

        @Override
        public boolean onKeyPressed(char character, int key) {
            if (key == Keyboard.KEY_ESCAPE) {
                ui.annotations.pinnedAnnotation("");
                return true;
            }
            return super.onKeyPressed(character, key);
        }
    }
}
