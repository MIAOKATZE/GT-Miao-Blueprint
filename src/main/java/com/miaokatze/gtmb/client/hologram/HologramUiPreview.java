package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;
import static net.minecraft.client.gui.GuiScreen.isCtrlKeyDown;
import static net.minecraft.client.gui.GuiScreen.isShiftKeyDown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.client.hologram.HologramUiMemory.UiMemory;
import com.miaokatze.miaokatzecore.api.camera.CameraBasis;
import com.miaokatze.miaokatzecore.api.camera.CameraPose;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** Internal preview workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiPreview {

    private final HologramUiModules ui;

    HologramUiPreview(HologramUiModules ui) {
        this.ui = ui;
    }

    private int selected = -1;

    private int layer;

    private int view;

    private int hovered = -1;

    int hoveredCellIndex() {
        return hovered;
    }

    private final HologramEditQueue editQueue = new HologramEditQueue();

    private final HologramSelection selection = new HologramSelection();

    private final HologramGizmo gizmo = new HologramGizmo();

    private boolean boxSelecting;

    private boolean moveHandles = true;

    private int boxStart = -1;

    private int dragX;

    private int dragY;

    private double yaw = Math.PI / 4;

    private double pitch = Math.atan(1 / Math.sqrt(2));

    private double zoom = 1;

    private final HologramPreviewCamera camera = new HologramPreviewCamera();

    private long cameraFrame;

    private boolean allLayers = true;

    private boolean dragging;

    private boolean moved;

    private int navigationButton = -1;
    private int navigationStartX, navigationStartY;
    private long navigationMotion;
    private boolean navigationDragged;

    private boolean inventory;

    private int inventorySlot = -1;

    private int sourceChoice = -1;

    private int sourceOffset;

    private boolean sourceTargets;

    private boolean inventoryDragging;

    private long queuedDispatchSequence = -1;

    private boolean gizmoCopy;

    private boolean boxAdditive;

    private int[] emptyVoxelPreview;

    private List<Integer> airBoundMembers;

    private int[] airBounds;

    private long pendingSelectionSequence = -1;
    private HologramState browseState;
    private long browseCameraEpoch = Long.MIN_VALUE;
    private double browseYaw = Math.PI / 4, browsePitch = Math.atan(1 / Math.sqrt(2)), browseZoom = 1;
    private int browseLayer;
    private boolean browseAllLayers = true, browseDragging;

    int previewHeight() {
        return (int) Math.floor(
            ui.canvas.viewport()
                .height());
    }

    private int viewportX() {
        return (int) Math.floor(
            ui.canvas.viewport()
                .x());
    }

    private int viewportY() {
        return (int) Math.floor(
            ui.canvas.viewport()
                .y());
    }

    private int viewportWidth() {
        return (int) Math.floor(
            ui.canvas.viewport()
                .width());
    }

    private void applyViewportScissor() {
        ViewportCoordinates source = ui.canvas.viewport();
        double scale = ui.canvas.scale();
        ViewportCoordinates gui = ui.canvas.nativeScene() ? ui.canvas.nativeGuiViewport()
            : new ViewportCoordinates(
                ui.canvas.originX() + source.x() * scale,
                ui.canvas.originY() + source.y() * scale,
                source.width() * scale,
                source.height() * scale);
        ViewportCoordinates.FramebufferRect framebuffer = gui.framebufferRect(
            ui.host.screenWidth(),
            ui.host.screenHeight(),
            ui.host.minecraft().displayWidth,
            ui.host.minecraft().displayHeight);
        GL11.glScissor(framebuffer.left(), framebuffer.bottom(), framebuffer.width(), framebuffer.height());
    }

    /** Only model/ghost/selection geometry. Native controls are siblings of this bounded scene widget. */
    void drawNativeScene(int mx, int my) {
        if (ui.library.browsePreviewActive()) {
            drawBrowsePreview(mx, my);
            return;
        }
        HologramRenderer.selection = selection;
        HologramRenderer.moveOffset = gizmo.offset();
        HologramRenderer.showEmptyCells = inventory || ui.position.positionDrawer() || airSelectionHeld();
        HologramRenderer.projectionLayer = allLayers ? Integer.MAX_VALUE : layer;
        HologramRenderer.selectedStructureId = ui.config.selectedStructureId();
        drawRect(0, 0, viewportWidth(), previewHeight(), 0xff292d2f);
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        applyViewportScissor();
        try {
            hovered = HologramRenderer.preview(
                ui.session.state(),
                0,
                0,
                viewportWidth(),
                previewHeight(),
                yaw,
                pitch,
                zoom,
                camera.x,
                camera.y,
                camera.z,
                allLayers ? Integer.MAX_VALUE : layer,
                view,
                selected,
                mx,
                my,
                -1);
            ui.textBoxes.draw();
            drawEmptyVoxelPreview(mx, my);
            if (moveHandles && canMoveSelection() && previewInteractive()) drawGizmo(mx, my);
        } finally {
            GL11.glPopAttrib();
        }
    }

    void scrollNativeScene(int wheel) {
        if (ui.library.browsePreviewActive()) zoom(browseZoom * (wheel > 0 ? 1.1 : 1 / 1.1));
        else if (ui.shell.sceneActive()) zoom(zoom * (wheel > 0 ? 1.1 : 1 / 1.1));
    }

    boolean viewControlEnabled(int id) {
        if (ui.library.browsePreviewActive()) {
            if (!browseMeshInteractive()) return false;
            return id == 11 || id == 12 && browseLayer > browseState.minY || id == 13 && browseLayer < browseState.maxY;
        }
        return switch (id) {
            case 10, 11, 14, 15 -> true;
            case 12 -> layer > ui.session.state().minY;
            case 13 -> layer < ui.session.state().maxY;
            default -> false;
        };
    }

    String viewControlLabel(int id) {
        boolean browse = ui.library.browsePreviewActive();
        return switch (id) {
            case 10 -> new String[] { "\u76ee\u6807", "\u73b0\u573a", "\u5dee\u5206" }[view];
            case 11 -> (browse ? browseAllLayers : allLayers) ? "\u5168\u90e8\u5c42"
                : "\u5c42 " + (browse ? browseLayer : layer);
            case 12 -> "\u4e0a\u4e00\u5c42";
            case 13 -> "\u4e0b\u4e00\u5c42";
            case 14 -> "\u590d\u4f4d\u89c6\u89d2";
            case 15 -> "\u4e16\u754c\u6295\u5f71 " + (HologramClient.worldPreview ? "\u2713" : "\u00d7");
            default -> "";
        };
    }

    void drawPreview(int mx, int my) {
        if (ui.library.browsePreviewActive()) {
            drawBrowsePreview(mx, my);
            return;
        }
        int height = previewHeight();
        HologramRenderer.selection = this.selection;
        HologramRenderer.moveOffset = this.gizmo.offset();
        HologramRenderer.showEmptyCells = this.inventory || ui.position.positionDrawer()
            || ui.preview.airSelectionHeld();
        HologramRenderer.projectionLayer = this.allLayers ? Integer.MAX_VALUE : this.layer;
        HologramRenderer.selectedStructureId = ui.config.selectedStructureId();
        drawRect(viewportX(), viewportY(), viewportX() + viewportWidth(), viewportY() + height, 0xff0c1e28);
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        applyViewportScissor();
        try {
            this.hovered = HologramRenderer.preview(
                ui.session.state(),
                viewportX(),
                viewportY(),
                viewportWidth(),
                height,
                this.yaw,
                this.pitch,
                this.zoom,
                this.camera.x,
                this.camera.y,
                this.camera.z,
                this.allLayers ? Integer.MAX_VALUE : this.layer,
                this.view,
                this.selected,
                mx,
                my,
                -1);
            ui.textBoxes.draw();
        } finally {
            GL11.glPopAttrib();
        }
        ui.canvas.button(10, 38, 55, 44, 17, new String[] { "目标", "现场", "差分" }[this.view], true, mx, my);
        ui.canvas.button(11, 84, 55, 50, 17, this.allLayers ? "全部层" : "层 " + this.layer, true, mx, my);
        ui.canvas.button(12, 136, 55, 20, 17, "−", this.layer > ui.session.state().minY, mx, my);
        ui.canvas.button(13, 158, 55, 22, 17, "+", this.layer < ui.session.state().maxY, mx, my);
        if (this.selection.size() > 0)
            ui.canvas.text("选中 " + this.selection.size() + " 格 · G 移动轴", 186, 61, 122, 0xffe59b);
        if (this.selection.canSelectRelated(ui.session.state())) ui.canvas.text("全选 T", 264, 61, 42, 0xc99bff);
        if (!ui.library.libraryDrawer()) ui.preview.drawMoveHandleControl(mx, my);
        if (!ui.library.libraryDrawer()) {
            ui.canvas.button(14, 38, 282, 66, 17, "复位视角", true, mx, my);
            ui.canvas.button(15, 106, 282, 74, 17, HologramClient.worldPreview ? "世界投影 ✓" : "世界投影 ×", true, mx, my);
        }
        if (!ui.library.libraryDrawer()) {
            ui.config.drawResetButton(184, 282, 120, mx, my);
            if (this.selected >= 0) ui.canvas.button(146, 310, 282, 132, 17, "所选格提示词", ui.session.editable(), mx, my);
        }
        if (this.moveHandles && ui.preview.canMoveSelection()
            && !ui.library.libraryDrawer()
            && ui.preview.previewInteractive()) ui.preview.drawGizmo(mx, my);
        if (this.hovered >= 0 && this.hovered < ui.session.state().cells.size()) {
            HologramState.Cell cell = ui.session.state().cells.get(this.hovered);
            if (cell.missingBlock || cell.excluded || cell.sourceMissing) {
                ui.canvas.tooltip(new ArrayList<>());
                if (cell.missingBlock) ui.canvas.tooltip()
                    .add("缺失方块：" + cell.wantId + "（点击查看来源版本）");
                if (cell.sourceMissing) {
                    ui.canvas.tooltip()
                        .addAll(
                            ui.host.font()
                                .listFormattedStringToWidth("来源结构缺失：" + cell.sourceWarning, 260));
                    if (!cell.sourceClass.isEmpty()) ui.canvas.tooltip()
                        .addAll(
                            ui.host.font()
                                .listFormattedStringToWidth(cell.sourceClass, 260));
                }
                if (cell.excluded) ui.canvas.tooltip()
                    .add("已排除施工");
            }
        }
        String hint = HologramRenderer.hintAt(mx, my);
        if (!hint.isEmpty()) {
            if (ui.canvas.tooltip() == null) ui.canvas.tooltip(new ArrayList<>());
            ui.canvas.tooltip()
                .addAll(
                    ui.host.font()
                        .listFormattedStringToWidth("提示区 " + hint, 260));
        }
        String structure = HologramRenderer.pickStructure(mx, my);
        if (!structure.isEmpty()) {
            NBTTagList labels = ui.session.state().data.getTagList("structures", 10);
            for (int i = 0; i < labels.tagCount(); i++) {
                NBTTagCompound row = labels.getCompoundTagAt(i);
                if (!structure.equals(row.getString("id"))) continue;
                if (ui.canvas.tooltip() == null) ui.canvas.tooltip(new ArrayList<>());
                ui.canvas.tooltip()
                    .add(row.getString("name") + " · 点击边缘整选");
                if (row.getBoolean("sourceMissing")) {
                    ui.canvas.tooltip()
                        .addAll(
                            ui.host.font()
                                .listFormattedStringToWidth("来源结构缺失：" + row.getString("sourceWarning"), 260));
                    ui.canvas.tooltip()
                        .addAll(
                            ui.host.font()
                                .listFormattedStringToWidth(row.getString("sourceClass"), 260));
                }
                break;
            }
        }
    }

    /** A library display has its own camera and no selection, session, or editing capability. */
    private void drawBrowsePreview(int mx, int my) {
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        HologramState display = library.browsePreviewState();
        if (browseCameraEpoch != library.browsePreviewEpoch()) {
            browseCameraEpoch = library.browsePreviewEpoch();
            if (browseState == null) {
                browseAllLayers = allLayers;
                browseYaw = yaw;
                browsePitch = pitch;
                browseZoom = zoom;
            }
            browseLayer = display == null ? 0 : Math.max(display.minY, Math.min(display.maxY, browseLayer));
            browseDragging = false;
        }
        if (display != browseState) {
            if (display != null) browseLayer = browseState == null ? display.minY
                : Math.max(display.minY, Math.min(display.maxY, browseLayer));
            browseState = display;
        }
        int height = previewHeight();
        drawRect(viewportX(), viewportY(), viewportX() + viewportWidth(), viewportY() + height, 0xff0c1e28);
        HologramRenderer.selection = null;
        HologramRenderer.showEmptyCells = false;
        HologramRenderer.selectedStructureId = "";
        HologramRenderer.projectionLayer = browseAllLayers ? Integer.MAX_VALUE : browseLayer;
        if (display == null) {
            if (drawBrowseImage(library, height)) return;
            String status = HologramLibraryClient.INSTANCE.browsePreviewStatus();
            ui.canvas.text(
                status.isEmpty() ? "在右侧选择蓝图以预览" : status,
                viewportX() + 12,
                viewportY() + 35,
                viewportWidth() - 24,
                0xb9d5d4);
            return;
        }
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        applyViewportScissor();
        try {
            HologramRenderer.preview(
                display,
                viewportX(),
                viewportY(),
                viewportWidth(),
                height,
                browseYaw,
                browsePitch,
                browseZoom,
                0,
                0,
                browseAllLayers ? Integer.MAX_VALUE : browseLayer,
                0,
                -1,
                mx,
                my,
                -1);
        } finally {
            GL11.glPopAttrib();
        }
        boolean visible = HologramRenderer.previewMeshVisible(display);
        if (!visible) drawBrowseImage(library, height);
        if (!display.data.getBoolean("displayPartial") && HologramRenderer.previewMeshReady(display))
            library.releaseBrowseImage();
        ui.canvas.text("只读预览", 38, 61, 76, 0x81dce9);
        ui.canvas.button(11, 116, 55, 50, 17, browseAllLayers ? "全部层" : "层 " + browseLayer, visible, mx, my);
        ui.canvas.button(12, 168, 55, 20, 17, "−", visible && browseLayer > display.minY, mx, my);
        ui.canvas.button(13, 190, 55, 22, 17, "+", visible && browseLayer < display.maxY, mx, my);
    }

    private boolean browseMeshInteractive() {
        return browseState != null && HologramRenderer.previewMeshVisible(browseState);
    }

    private boolean drawBrowseImage(HologramLibraryClient library, int height) {
        net.minecraft.util.ResourceLocation texture = library.browsePreviewTexture();
        java.awt.image.BufferedImage image = library.browsePreviewImage();
        if (texture == null || image == null) return false;
        double scale = Math.min((viewportWidth() - 16.0) / image.getWidth(), (height - 16.0) / image.getHeight());
        double width = image.getWidth() * scale, imageHeight = image.getHeight() * scale;
        double left = viewportX() + (viewportWidth() - width) / 2, top = viewportY() + (height - imageHeight) / 2;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            ui.host.minecraft()
                .getTextureManager()
                .bindTexture(texture);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1, 1, 1, 1);
            net.minecraft.client.renderer.Tessellator tessellator = net.minecraft.client.renderer.Tessellator.instance;
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(left, top + imageHeight, 0, 0, 1);
            tessellator.addVertexWithUV(left + width, top + imageHeight, 0, 1, 1);
            tessellator.addVertexWithUV(left + width, top, 0, 1, 0);
            tessellator.addVertexWithUV(left, top, 0, 0, 0);
            tessellator.draw();
        } finally {
            GL11.glPopAttrib();
        }
        return true;
    }

    boolean canMoveSelection() {
        return !ui.library.browsePreviewActive() && ui.session.state().data.getBoolean("geometryEditable")
            && ui.session.state().data.getBoolean("virtual")
            && ui.session.state().data.getBoolean("supported")
            && ui.session.editable()
            && this.selection.movable(ui.session.state());
    }

    void drawMoveHandleControl(int mx, int my) {
        ui.canvas.button(
            147,
            310,
            55,
            126,
            17,
            "移动轴 (G) " + (this.moveHandles ? "✓" : "×"),
            ui.preview.canMoveSelection() && ui.preview.previewInteractive(),
            mx,
            my);
    }

    String moveHandleReason() {
        if (HologramBackgroundTasks.busy()) return "后台任务正在处理当前蓝图，完成后可使用移动轴。";
        if (!ui.session.state().data.getBoolean("virtual")) return "真实控制器模式不能移动规划坐标；返回区域总览或打开蓝图后，完整选中结构再移动。";
        if (!ui.session.editable()) return ui.session.editingReason();
        if (!ui.session.state().data.getBoolean("supported")) return "结构尚未完整载入，暂不能移动。";
        if (!ui.session.state().data.getBoolean("geometryEditable")) return "当前蓝图尚未开放坐标编辑。";
        if (this.selection.size() == 0) return "先点击结构轮廓完整选中结构，或选择可独立编辑的方块。";
        if (!this.selection.movable(ui.session.state())) return "当前选区未包含完整结构；点击结构轮廓整选后可使用移动轴。";
        if (!ui.preview.previewInteractive()) return "先关闭当前编辑窗口，再使用预览中的移动轴。";
        return this.moveHandles ? "移动轴已开启；拖动彩色轴移动，按住 Ctrl 开始拖动即复制。" : "移动轴已关闭；点击此按钮或按 G 重新显示。";
    }

    int[] emptyVoxelAt(int mx, int my) {
        if (!ui.canvas.inViewport(mx, my) || ui.preview.previewBlockedAt(mx, my)) return null;
        if (!ui.canvas.nativeScene())
            for (Control control : ui.canvas.controls()) if (control.contains(mx, my)) return null;
        HologramState.Cell cell = ui.session.selectedCell();
        int planeY = !this.allLayers ? ui.session.state().data.getInteger("y") + this.layer
            : cell == null
                ? ui.session.state().data.getInteger("y") + (ui.session.state().minY + ui.session.state().maxY) / 2
                : cell.y;
        int[] voxel = HologramRenderer.pickEmptyVoxel(ui.session.state(), mx, my, planeY);
        if (voxel == null) return null;
        String structure = ui.config.selectedStructureId();
        if (!structure.isEmpty()) {
            List<Integer> members = ui.session.state().structureMembers.get(structure);
            if (airBoundMembers != members) {
                airBoundMembers = members;
                airBounds = null;
                if (members != null) for (int index : members) {
                    HologramState.Cell member = ui.session.state().cells.get(index);
                    if (airBounds == null)
                        airBounds = new int[] { member.x, member.y, member.z, member.x, member.y, member.z };
                    else {
                        airBounds[0] = Math.min(airBounds[0], member.x);
                        airBounds[1] = Math.min(airBounds[1], member.y);
                        airBounds[2] = Math.min(airBounds[2], member.z);
                        airBounds[3] = Math.max(airBounds[3], member.x);
                        airBounds[4] = Math.max(airBounds[4], member.y);
                        airBounds[5] = Math.max(airBounds[5], member.z);
                    }
                }
            }
            int[] bounds = airBounds;
            if (bounds != null && !HologramSelection.inside(
                voxel[0],
                voxel[1],
                voxel[2],
                bounds[0] - 1,
                bounds[1] - 1,
                bounds[2] - 1,
                bounds[3] + 1,
                bounds[4] + 1,
                bounds[5] + 1)) return null;
        }
        return voxel;
    }

    void drawEmptyVoxelPreview(int mx, int my) {
        this.emptyVoxelPreview = ui.preview.airSelectionHeld() && ui.preview.previewInteractive() && !this.inventory
            ? ui.preview.emptyVoxelAt(mx, my)
            : null;
        if (this.emptyVoxelPreview == null) return;
        int[] p = this.emptyVoxelPreview;
        int dx = p[0] - ui.session.state().data.getInteger("x"), dy = p[1] - ui.session.state().data.getInteger("y"),
            dz = p[2] - ui.session.state().data.getInteger("z");
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(2);
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        t.setColorOpaque_I(0x9eedc1);
        for (int axis = 0; axis < 3; axis++) for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            double[] from = { dx + a - .5, dy + b - .5, dz - .5 };
            if (axis == 0) from = new double[] { dx - .5, dy + a - .5, dz + b - .5 };
            if (axis == 1) from = new double[] { dx + a - .5, dy - .5, dz + b - .5 };
            double[] to = from.clone();
            to[axis] += 1;
            double[] start = HologramRenderer.project(from[0], from[1], from[2]);
            double[] end = HologramRenderer.project(to[0], to[1], to[2]);
            t.addVertex(start[0], start[1], 8);
            t.addVertex(end[0], end[1], 8);
        }
        t.draw();
        GL11.glPopAttrib();
        ui.canvas.text(
            "空气 " + p[0] + "," + p[1] + "," + p[2] + " · 偏移 " + dx + "," + dy + "," + dz + " · Alt+点击选中",
            viewportX() + 8,
            ui.annotations.pinnedAnnotation()
                .isEmpty() ? 263 : 80,
            viewportWidth() - 16,
            0x9eedc1);
    }

    boolean selectEmptyVoxel(int mx, int my) {
        int[] voxel = ui.preview.emptyVoxelAt(mx, my);
        if (voxel == null) {
            ui.session.notice("此光标位置没有可编辑空气；调整视角或选择工作层");
            return true;
        }
        HologramState.Cell cell = ui.session.state().regions.at(voxel[0], voxel[1], voxel[2]);
        if (cell != null) {
            this.selection.click(cell.index, ui.preview.controlSelectionHeld());
            this.selected = this.selection.first();
            ui.config.syncTargetSelectors();
            return true;
        }
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("dx", voxel[0] - ui.session.state().data.getInteger("x"));
        request.setInteger("dy", voxel[1] - ui.session.state().data.getInteger("y"));
        request.setInteger("dz", voxel[2] - ui.session.state().data.getInteger("z"));
        request.setBoolean("airOnly", true);
        request.setString("structureId", ui.config.selectedStructureId());
        ui.session.send("addPosition", request, true);
        return true;
    }

    void drawGizmo(int mx, int my) {
        double x = 0, y = 0, z = 0;
        for (int index : this.selection.indices()) {
            HologramState.Cell cell = ui.session.state().cells.get(index);
            x += cell.dx;
            y += cell.dy;
            z += cell.dz;
        }
        int count = this.selection.size();
        if (count == 0) return;
        int[] offset = gizmo.offset();
        double[] center = HologramRenderer.project(x / count + offset[0], y / count + offset[1], z / count + offset[2]);
        gizmo.project(center[0], center[1], yaw, pitch, HologramRenderer.gridScale());
        HologramGizmoRenderer.draw(gizmo, ui.canvas, mx, my);
        if (gizmo.axis() >= 0) ui.canvas.text(
            (gizmoCopy ? "复制移动 " : "移动 ") + HologramGizmo
                .label(gizmo.axis()) + " " + offset[0] + "," + offset[1] + "," + offset[2] + " 格",
            38,
            263,
            280,
            0xffffaa);
        else if (gizmo.pick(mx, my) >= 0)
            ui.canvas.tooltip(new ArrayList<>(Collections.singletonList("拖轴沿单轴移动，拖彩色小面沿平面移动；Ctrl 开始拖动即复制；松开提交规划")));
    }

    void drawInventory(int mx, int my) {
        ui.canvas.tooltip(null);
        drawRect(157, 59, 405, 257, 0xff172f36);
        ui.canvas.text("选择方块 / 仓室 · 不消耗物品", 165, 65, 205, 0xf5e4b9);
        ui.canvas.button(16, 165, 82, 74, 18, (this.sourceTargets ? "" : "› ") + "主背包", true, mx, my);
        ui.canvas.button(17, 243, 82, 74, 18, (this.sourceTargets ? "› " : "") + "材料来源", true, mx, my);
        ui.canvas.button(95, 321, 82, 75, 18, "可替换选项", ui.session.selectedCell() != null, mx, my);
        ui.canvas.button(64, 373, 62, 23, 17, "×", true, mx, my);
        HologramState.Cell cell = ui.session.selectedCell();
        ui.canvas.button(
            65,
            165,
            104,
            231,
            18,
            cell == null ? "先点击左侧预览选择目标格" : "目标格：" + ui.preview.cellName(cell, false),
            ui.session.editable() && ui.preview.canInventoryPin() && ui.preview.pickedTarget() != null,
            mx,
            my);
        ui.canvas.text("点击物品再点目标槽，或拖到左侧预览格", 165, 126, 231, 0x94afb4);
        NBTTagList choices = ui.session.state().data.getTagList("materialChoices", 10);
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) {
            int slot = row == 3 ? col : 9 + row * 9 + col;
            int choice = this.sourceOffset + row * 9 + col;
            int x = 181 + col * 22, y = 139 + row * 20;
            boolean chosen = this.sourceTargets ? this.sourceChoice == choice : this.inventorySlot == slot;
            drawRect(x, y, x + 20, y + 18, chosen ? 0xff5f858a : 0xff0c1e28);
            ItemStack stack = this.sourceTargets ? ui.preview.sourceStack(choice)
                : ui.host.minecraft().thePlayer.inventory.mainInventory[slot];
            ui.canvas.drawItem(stack, x + 2, y + 1);
            if (mx >= x && mx < x + 20 && my >= y && my < y + 18 && stack != null)
                ui.canvas.tooltip(Collections.singletonList(stack.getDisplayName() + " · 选为目标（不消耗）"));
        }
        if (this.sourceTargets && choices.tagCount() == 0) {
            ui.canvas.text("开启来源并准备物品", 183, 160, 194, 0xe4d3a9);
            ui.canvas.text("然后重新扫描或重新打开", 183, 180, 194, 0xe4d3a9);
        }
        ui.canvas.button(66, 165, 221, 105, 17, "恢复默认壳", ui.session.editable() && ui.preview.hasSelectedPin(), mx, my);
        ItemStack picked = ui.preview.pickedTarget();
        if (this.sourceTargets) ui.canvas.button(18, 277, 221, 119, 17, "刷新来源 · 滚轮翻页", ui.session.editable(), mx, my);
        else ui.canvas.text(picked == null ? "未选物品" : picked.getDisplayName(), 277, 224, 119, 0xe4d3a9);
        ui.canvas.text(
            ui.session.pendingSequence() >= 0 ? "等待服务端校验目标…" : ui.session.state().data.getString("status"),
            165,
            243,
            231,
            0xffbd91);
        if (mx >= 165 && mx < 396 && my >= 240 && my < 255) {
            ui.canvas.tooltip(
                new ArrayList<>(
                    ui.host.font()
                        .listFormattedStringToWidth(ui.session.state().data.getString("status"), 230)));
            if (ui.session.pendingSequence() >= 0) ui.canvas.tooltip()
                .add("等待服务端校验目标…");
            if (cell != null) {
                ui.canvas.tooltip()
                    .add("选中格 #" + cell.index + " · 世界坐标 " + cell.x + "," + cell.y + "," + cell.z);
                ui.canvas.tooltip()
                    .addAll(
                        ui.host.font()
                            .listFormattedStringToWidth("当前目标：" + ui.preview.cellName(cell, false), 230));
                ui.canvas.tooltip()
                    .add(cell.wantId + " @ " + cell.wantMeta);
            }
            if (picked != null) ui.canvas.tooltip()
                .addAll(
                    ui.host.font()
                        .listFormattedStringToWidth(
                            (this.sourceTargets ? "来源选择：" : "背包选择：") + picked.getDisplayName(),
                            230));
        }
        if (picked != null && this.inventoryDragging) ui.canvas.drawItem(picked, mx - 8, my - 8);
    }

    ItemStack sourceStack(int choice) {
        NBTTagList choices = ui.session.state().data.getTagList("materialChoices", 10);
        return choice >= 0 && choice < choices.tagCount()
            ? ItemStack.loadItemStackFromNBT(choices.getCompoundTagAt(choice))
            : null;
    }

    ItemStack pickedTarget() {
        return this.sourceTargets ? ui.preview.sourceStack(this.sourceChoice)
            : this.inventorySlot >= 0 ? ui.host.minecraft().thePlayer.inventory.mainInventory[this.inventorySlot]
                : null;
    }

    int inventorySlotAt(int x, int y) {
        int col = (x - 181) / 22, row = (y - 139) / 20;
        if (x < 181 || y < 139 || col >= 9 || row >= 4 || (x - 181) % 22 >= 20 || (y - 139) % 20 >= 18) return -1;
        return this.sourceTargets ? this.sourceOffset + row * 9 + col : row == 3 ? col : 9 + row * 9 + col;
    }

    void pinInventory(int target) {
        if (!ui.session.editable() || target < 0 || ui.preview.pickedTarget() == null) return;
        this.selection.click(target, false);
        this.selected = target;
        NBTTagCompound n = new NBTTagCompound();
        n.setInteger("selected", target);
        n.setInteger("index", target);
        if (this.sourceTargets) n.setInteger("choice", this.sourceChoice);
        else n.setInteger("inventorySlot", this.inventorySlot);
        n.setInteger("scope", 2);
        ui.session.send(this.sourceTargets ? "sourcePin" : "customPin", n, true);
    }

    boolean canInventoryPin() {
        HologramState.Cell cell = ui.session.selectedCell();
        return cell != null && !cell.anchor && ui.session.state().data.getInteger("mode") != 2;
    }

    boolean hasSelectedPin() {
        return ui.session.selectedCell() != null && ui.session.cellTag()
            .getBoolean("hasTargetOverride");
    }

    void clearSelection() {
        if (this.selected < 0) return;
        this.selection.clear();
        this.pendingSelectionSequence = -1;
        this.gizmo.cancel();
        this.selected = -1;
        ui.config.syncTargetSelectors();
    }

    boolean previewInteractive() {
        return !ui.session.areaImporting() && ui.ae.aeReviewNavigation() == null
            && ui.ae.aeReview() == null
            && !ui.config.drawer()
            && !ui.position.positionDrawer()
            && !ui.config.sourceDrawer()
            && !ui.config.hatchDrawer()
            && !ui.config.presetDrawer()
            && !ui.review.confirmation()
            && !ui.shortcuts.shortcutsDrawer()
            && ui.processing.processingFlow() == null
            && !ui.library.discardReview()
            && !ui.library.overwriteReview()
            && !ui.processing.processingPresetSaveReview()
            && !ui.annotations.promptDrawer()
            && !ui.annotations.versionsDrawer()
            && !ui.nbt.nbtDrawer()
            && !ui.identity.deidentificationDrawer()
            && !ui.config.resetReview()
            && ui.library.deletePresetId()
                .isEmpty()
            && !ui.config.roleDropdown()
            && ui.config.optionChannel()
                .isEmpty();
    }

    String cellName(HologramState.Cell cell, boolean current) {
        if (current && cell.actualStack != null) return cell.actualStack.getDisplayName();
        if (!current && cell.targetStack != null) return cell.targetStack.getDisplayName();
        if (!current && cell.chosenChoice >= 0 && cell.chosenChoice < cell.candidates.size())
            return cell.candidates.get(cell.chosenChoice)
                .getDisplayName();
        if (!current && !cell.candidates.isEmpty()) return cell.candidates.get(0)
            .getDisplayName();
        net.minecraft.block.Block block = cell.block(current);
        if (block != null) return block.getLocalizedName();
        return current ? "空位 / 未知现场" : "固定接口 / 未知目标";
    }

    static String statusName(String status) {
        switch (status) {
            case "blocked":
                return "被遮挡：请清空占位后补建";
            case "protected":
                return "受保护：控制器 / 未适配设备保留";
            case "unsupported":
                return "固定接口待手动补齐";
            case "missing":
                return "缺少材料";
            case "satisfied":
                return "目标已满足";
            case "pending":
                return "待施工";
            case "placed":
                return "已建造";
            case "replaced":
                return "已替换";
            case "removed":
                return "已拆除";
            default:
                return status;
        }
    }

    ItemStack displayedStack(HologramState.Cell cell, boolean current) {
        ItemStack stack = current ? cell.actualStack : cell.targetStack;
        if (stack != null) return stack;
        net.minecraft.block.Block block = cell.block(current);
        if (block == null || block == net.minecraft.init.Blocks.air) return null;
        net.minecraft.item.Item item = net.minecraft.item.Item.getItemFromBlock(block);
        return item == null ? null : new ItemStack(item, 1, current ? cell.meta : cell.wantMeta);
    }

    boolean handleEditorShortcut(int key, int modifiers) {
        if (ui.library.browsePreviewActive() || !ui.preview.previewInteractive()
            || this.inventory
            || ui.router.textFieldFocused()
            || ui.shortcuts.captureBinding() >= 0) return false;
        if (key == Keyboard.KEY_R && modifiers == 4 && ui.textBoxes.selectedDefinition() != null) {
            editAction("rotateTextVertical");
            return true;
        }
        if (key == Keyboard.KEY_A && modifiers == 1) {
            this.selection.all(ui.session.state());
            this.selected = this.selection.first();
            ui.config.syncTargetSelectors();
            return true;
        }
        if (key == Keyboard.KEY_T && (modifiers == 0 || modifiers == 1) && this.selected >= 0) {
            this.selection.selectRelated(ui.session.state(), modifiers == 1);
            this.selected = this.selection.first();
            ui.config.syncTargetSelectors();
            return true;
        }
        int binding = ui.shortcuts.keyBindings()
            .match(key, modifiers);
        if (binding >= 0) {
            ui.preview.editAction(HologramKeyBindings.ACTIONS[binding]);
            return true;
        }
        if (modifiers == 0 && (key == Keyboard.KEY_1 || key == Keyboard.KEY_2 || key == Keyboard.KEY_3)) {
            ui.router.activate(70 + key - Keyboard.KEY_1);
            return true;
        }
        return false;
    }

    boolean previewBlockedAt(int x, int y) {
        if (ui.canvas.nativeScene()) return !ui.shell.sceneActive() && !ui.library.browsePreviewActive();
        if (ui.tools.blocksViewport(x, y)) return true;
        if (ui.library.libraryDrawer()) return ui.library.sidebarContains(x, y);
        if (!ui.annotations.pinnedAnnotation()
            .isEmpty() && ui.canvas.inViewport(x, y)
            && y >= ui.annotations.pinnedAnnotationTop()
            && y < ui.annotations.pinnedAnnotationTop() + 60) return true;
        if (this.inventory) return x >= 157 && x < 405 && y >= 59 && y < 257;
        return ui.canvas.inPanel(x, y);
    }

    void editAction(String action) {
        if (ui.library.browsePreviewActive()) return;
        boolean history = "editUndo".equals(action) || "editRedo".equals(action);
        HologramState.Cell cell = ui.session.selectedCell();
        if (!history && cell == null) return;
        if (ui.session.pendingSequence() >= 0 && !ui.session.state().data.getBoolean("capturePending")
            && !ui.session.state().data.getBoolean("targetClosed")
            && ui.session.state().data.getInteger("job") != 1
            && ui.session.state().data.getInteger("job") != 2
            && !ui.session.awaitingScan()) {
            List<HologramState.Cell> frozenSelection = new ArrayList<>();
            if (!history)
                for (int index : this.selection.indices()) frozenSelection.add(ui.session.state().cells.get(index));
            if (!this.editQueue.offer(action, ui.session.state().session, history ? null : cell, frozenSelection)) {
                ui.session.notice("等待队列已满（8项），请等待校验后重试");
                return;
            }
            ui.session.notice("已排队 " + this.editQueue.size() + " 项编辑，等待服务端校验");
            return;
        }
        if (!ui.session.editable()) {
            ui.session.notice(ui.session.pendingSequence() >= 0 ? "等待服务端校验，请稍后再操作" : ui.session.editingReason());
            return;
        }
        ui.preview.dispatchEdit(action, this.selected);
    }

    void dispatchEdit(String action, int target) {
        ui.preview.dispatchEdit(action, target, this.selection.indices());
    }

    void dispatchEdit(String action, int target, int[] indices) {
        if ("editUndo".equals(action) && !ui.session.state().data.getBoolean("editCanUndo")
            || "editRedo".equals(action) && !ui.session.state().data.getBoolean("editCanRedo")
            || "editPaste".equals(action) && !ui.session.state().data.getBoolean("editCanPaste")) {
            ui.session.notice("此编辑当前不可用，未执行");
            return;
        }
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("selected", target);
        request.setIntArray("indices", indices);
        request.setIntArray("selectedIndices", indices);
        if ("rotateTextVertical".equals(action)) {
            request.setBoolean("vertical", true);
            action = "rotateCell";
        }
        if ("restoreCell".equals(action) && ui.session.state().data.getBoolean("virtual")
            && !ui.session.state().data.getBoolean("virtualPlacement")
            && ui.session.state().data.getString("activeStructure")
                .isEmpty()) {
            action = "editDelete";
            request.setLong("planRevision", ui.session.revision());
        }
        if ("rotateCell".equals(action)) request.setLong("planRevision", ui.session.revision());
        ui.session.send(action, request, true);
    }

    void cancelQueuedEdits(String reason) {
        if (this.editQueue.isEmpty()) return;
        this.editQueue.clear();
        ui.session.notice(reason);
    }

    void rejectCommand(long sequence, String reason) {
        if (queuedDispatchSequence == sequence) queuedDispatchSequence = -1;
        if (pendingSelectionSequence == sequence) pendingSelectionSequence = -1;
        cancelQueuedEdits(reason);
    }

    void drainQueuedEdits() {
        if (ui.session.state().data.getBoolean("capturePending") || ui.session.awaitingScan()
            || ui.session.state().data.getBoolean("targetClosed")
            || ui.session.state().data.getInteger("job") == 1
            || ui.session.state().data.getInteger("job") == 2) {
            ui.preview.cancelQueuedEdits("结构正在采集或不可编辑，已取消等待中的编辑");
            return;
        }
        while (ui.session.editable() && !this.editQueue.isEmpty()) {
            HologramEditQueue.Entry edit = this.editQueue.removeFirst(ui.session.state());
            if (edit == null) {
                ui.session.notice("会话或目标格已变化，已取消等待中的编辑");
                return;
            }
            ui.preview
                .dispatchEdit(edit.action, edit.selectionIn(ui.session.state()), edit.indicesIn(ui.session.state()));
            if (ui.session.pendingSequence() >= 0) {
                this.queuedDispatchSequence = ui.session.pendingSequence();
                ui.session
                    .notice(this.editQueue.isEmpty() ? "正在执行排队编辑" : "正在执行排队编辑，剩余 " + this.editQueue.size() + " 项");
            }
        }
    }

    void resetCamera() {
        this.yaw = ui.preview.frontCameraYaw();
        this.pitch = Math.atan(1 / Math.sqrt(2));
        this.zoom = 1;
        this.camera.reset();
        this.cameraFrame = 0;
    }

    void movePreviewCamera() {
        long now = System.nanoTime(), previous = this.cameraFrame;
        this.cameraFrame = now;
        if (ui.library.browsePreviewActive() || HologramBackgroundTasks.busy()
            || previous == 0
            || !org.lwjgl.opengl.Display.isActive()
            || !ui.preview.previewInteractive()
            || ui.router.textFieldFocused()
            || ui.library.libraryAdminDrawer()
            || ui.bindings.networkDrawer()
            || this.inventory
            || ui.shortcuts.captureBinding() >= 0
            || this.boxSelecting
            || this.gizmo.axis() >= 0
            || (HologramKeyBindings.modifiers() & 5) != 0) return;
        int right = (ui.tools.commands()
            .held("cameraRight") ? 1 : 0)
            - (ui.tools.commands()
                .held("cameraLeft") ? 1 : 0);
        int forward = (ui.tools.commands()
            .held("cameraForward") ? 1 : 0)
            - (ui.tools.commands()
                .held("cameraBack") ? 1 : 0);
        double speed = Math.max(2, HologramRenderer.previewExtent() * .6) / this.zoom;
        this.camera.move(this.yaw, (now - previous) / 1_000_000_000.0, right, forward, speed);
    }

    double frontCameraYaw() {
        int facing = ui.session.state().data.getInteger("facing");
        if (facing < 0 || facing >= ExtendedFacing.values().length) return Math.PI / 4;
        ForgeDirection direction = ExtendedFacing.values()[facing].getDirection();
        if (direction.offsetX == 0 && direction.offsetZ == 0) return Math.PI / 4;
        return Math.atan2(direction.offsetX, direction.offsetZ) + Math.PI / 4;
    }

    boolean sameTypeSelectionHeld() {
        return ui.diagnostics.smokeSameTypeSelection() && ui.diagnostics.captureRenderSmokeAction()
            || ui.tools.commands()
                .held("sameType")
            || ui.tools.commands()
                .held("sameTypeAdd");
    }

    boolean airSelectionHeld() {
        return ui.diagnostics.smokeAltSelection() && ui.diagnostics.captureRenderSmokeAction() || ui.tools.commands()
            .held("air");
    }

    boolean controlSelectionHeld() {
        return ui.diagnostics.smokeCtrlSelection() && ui.diagnostics.captureRenderSmokeAction() || isCtrlKeyDown();
    }

    boolean rotateCameraControl(int id) {
        if (id == 223) {
            if (ui.library.browsePreviewActive()) {
                if (browseMeshInteractive()) browseYaw += Math.PI / 2;
            } else this.yaw = this.yaw + (Math.PI / 2);
            return true;
        }
        return false;
    }

    boolean consumeImportControl(int id) {
        if (ui.session.areaImporting()) {
            if (id == 193) ui.router.closeRoot();
            return true;
        }
        return false;
    }

    boolean pinCandidateControl(int id) {
        if (id >= 10000) {
            if (!ui.session.editable() || !ui.preview.canInventoryPin()) return true;
            NBTTagCompound n = new NBTTagCompound();
            n.setInteger("selected", this.selected);
            n.setInteger("choiceindex", id - 10000);
            ui.session.send("pin", n, true);
            return true;
        }
        return false;
    }

    boolean activateControl(int id) {
        if (ui.library.browsePreviewActive() && id >= 10 && id <= 15) {
            if (!browseMeshInteractive()) return true;
            if (id == 11) browseAllLayers = !browseAllLayers;
            else if (id == 12 || id == 13) {
                browseAllLayers = false;
                if (browseState != null) browseLayer = Math
                    .max(browseState.minY, Math.min(browseState.maxY, browseLayer + (id == 12 ? -1 : 1)));
            }
            return true;
        }
        switch (id) {
            case 178:
                if (ui.session.editable()) HologramClient.beginAreaSelection();
                return true;
            case 147:
                if (ui.preview.canMoveSelection() && ui.preview.previewInteractive())
                    this.moveHandles = !this.moveHandles;
                return true;
            case 108:
                ui.preview.editAction("editUndo");
                return true;
            case 109:
                ui.preview.editAction("editRedo");
                return true;
            case 112:
                ui.preview.editAction("editCopy");
                return true;
            case 113:
                ui.preview.editAction("rotateCell");
                return true;
            case 10:
                this.view = (this.view + 1) % 3;
                return true;
            case 11:
                this.allLayers = !this.allLayers;
                ui.config.configure("layer", this.layer);
                return true;
            case 12:
                this.allLayers = false;
                this.layer--;
                ui.config.configure("layer", this.layer);
                return true;
            case 13:
                this.allLayers = false;
                this.layer++;
                ui.config.configure("layer", this.layer);
                return true;
            case 14:
                ui.preview.resetCamera();
                return true;
            case 15:
                HologramClient.worldPreview = !HologramClient.worldPreview;
                if (!HologramClient.worldPreview) HologramRenderer.release();
                return true;
            case 16:
            case 17:
                this.sourceTargets = id == 17;
                this.inventorySlot = this.sourceChoice = -1;
                this.inventoryDragging = false;
                this.sourceOffset = 0;
                return true;
            case 18:
                if (ui.session.editable()) {
                    this.sourceChoice = -1;
                    ui.session.send("scan", null, true);
                }
                return true;
            case 23:
                if (ui.session.editable()) ui.session.send("scan", null, true);
                return true;
            case 26:
                if (ui.session.editable()) {
                    ui.session.awaitingScan(true);
                    ui.session.send("scan", null, true);
                }
                return true;
            case 64:
                this.inventory = false;
                this.inventoryDragging = false;
                this.inventorySlot = this.sourceChoice = -1;
                return true;
            case 65:
                ui.preview.pinInventory(this.selected);
                return true;
            case 66:
                ui.preview.editAction("restoreCell");
                return true;
            case 69:
                ui.preview.clearSelection();
                return true;
            default:
                return false;
        }
    }

    boolean consumeImportMouse(int mx, int my, int button) {
        if (ui.session.areaImporting()) {
            if (button == 0) for (Control control : ui.canvas.controls())
                if (control.id == 193 && control.enabled && control.contains(mx, my)) ui.router.closeRoot();
            return true;
        }
        return false;
    }

    boolean consumeViewportRightMouse(int mx, int my, int button) {
        if (ui.library.browsePreviewActive())
            return button == 1 && ui.canvas.inViewport(mx, my) && !previewBlockedAt(mx, my);
        if (button == 1 && ui.preview.previewInteractive()
            && ui.canvas.inViewport(mx, my)
            && !ui.preview.previewBlockedAt(mx, my)) {
            if (blueprintCameraControls()) {
                beginNavigation(mx, my, button);
                return true;
            }
            if (this.inventoryDragging || this.inventorySlot >= 0 || this.sourceChoice >= 0) {
                this.inventoryDragging = false;
                this.inventorySlot = this.sourceChoice = -1;
            } else ui.preview.clearSelection();
            this.dragging = false;
            return true;
        }
        return false;
    }

    boolean blueprintCameraControls() {
        return !ui.library.browsePreviewActive() && ui.session.state().data.getBoolean("virtual");
    }

    /** Blueprint camera presses precede context-menu presses; a short right release opens the existing menu. */
    boolean consumeViewportNavigationMouse(int mx, int my, int button) {
        if (!blueprintCameraControls() || ui.tools.modalOpen() || ui.tools.pointerCaptured()) return false;
        return button == 1 ? consumeViewportRightMouse(mx, my, button)
            : button == 2 && consumeViewportMiddleMouse(mx, my, button);
    }

    boolean consumeViewportMiddleMouse(int mx, int my, int button) {
        if (button != 2 || !ui.preview.previewInteractive()
            || !ui.canvas.inViewport(mx, my)
            || ui.preview.previewBlockedAt(mx, my)) return false;
        if (blueprintCameraControls()) beginNavigation(mx, my, button);
        else if (!inventory) resetCamera();
        return true;
    }

    private void beginNavigation(int mx, int my, int button) {
        navigationButton = button;
        navigationStartX = mx;
        navigationStartY = my;
        navigationMotion = 0;
        navigationDragged = false;
        dragX = mx;
        dragY = my;
    }

    boolean consumeViewportLeftMouse(int mx, int my, int button) {
        if (ui.library.browsePreviewActive()) {
            if (button != 0) return false;
            if (button == 0 && browseMeshInteractive() && ui.canvas.inViewport(mx, my) && !previewBlockedAt(mx, my)) {
                browseDragging = true;
                dragX = mx;
                dragY = my;
            }
            return true;
        }
        if (button == 0) {
            if (ui.textBoxes.mouse(mx, my, button)) return true;
            if (ui.preview.previewInteractive() && ui.canvas.inViewport(mx, my)
                && !ui.preview.previewBlockedAt(mx, my)
                && ui.session.editable()) {
                int axis = !ui.preview.sameTypeSelectionHeld() && !ui.preview.airSelectionHeld()
                    && this.moveHandles
                    && ui.preview.canMoveSelection() ? this.gizmo.pick(mx, my) : -1;
                if (axis >= 0) {
                    this.gizmoCopy = ui.preview.controlSelectionHeld();
                    this.gizmo.begin(axis, mx, my);
                    return true;
                }
            }
            if (this.inventory && !ui.review.confirmation()
                && ui.config.optionChannel()
                    .isEmpty()) {
                int slot = ui.preview.inventorySlotAt(mx, my);
                if (slot >= 0) {
                    if ((this.sourceTargets ? ui.preview.sourceStack(slot)
                        : ui.host.minecraft().thePlayer.inventory.mainInventory[slot]) != null
                        && ui.session.editable()) {
                        if (this.sourceTargets) this.sourceChoice = slot;
                        else this.inventorySlot = slot;
                        this.inventoryDragging = true;
                    }
                    return true;
                }
            }
            if (!ui.canvas.nativeScene()) for (int i = ui.canvas.controls()
                .size() - 1; i >= 0; i--) {
                    Control c = ui.canvas.controls()
                        .get(i);
                    if (c.contains(mx, my)) {
                        if (ui.config.sourceDrawer() && (c.id < 84 || c.id > 88)) return true;
                        if (ui.position.positionDrawer() && c.id != 76 && c.id != 77) return true;
                        if (ui.review.confirmation() && c.id != 54
                            && c.id != 55
                            && c.id != 162
                            && c.id != 168
                            && c.id != 169) return true;
                        if (!ui.config.optionChannel()
                            .isEmpty() && c.id != 63 && c.id != 68 && c.id < 4000) return true;
                        if (this.inventory && c.id != 64
                            && c.id != 65
                            && c.id != 66
                            && c.id != 16
                            && c.id != 17
                            && c.id != 18
                            && c.id != 95
                            && !ui.canvas.inViewport(mx, my)) return true;
                        if (ui.config.drawer() && !ui.review.confirmation()
                            && ui.config.optionChannel()
                                .isEmpty()
                            && c.id < 1000
                            && c.id != 62
                            && c.id != 30
                            && c.id != 23
                            && c.id != 33
                            && c.id != 83
                            && c.id != 67
                            && c.id != 68) return true;
                        if (c.enabled) ui.router.activate(c.id);
                        return true;
                    }
                }
            if (ui.preview.previewBlockedAt(mx, my)) return true;
            if (ui.preview.previewInteractive() && ui.canvas.inViewport(mx, my)) {
                if (!this.inventory && ui.preview.airSelectionHeld() && ui.session.editable()) {
                    ui.preview.selectEmptyVoxel(mx, my);
                    return true;
                }
                if (isShiftKeyDown() && !ui.preview.sameTypeSelectionHeld()) {
                    int target = HologramRenderer.pick(mx, my);
                    this.boxStart = target;
                    this.boxAdditive = ui.preview.controlSelectionHeld();
                    this.boxSelecting = this.boxStart >= 0;
                    return true;
                }
                this.dragging = true;
                this.moved = false;
                this.dragX = mx;
                this.dragY = my;
            }
        }
        return false;
    }

    boolean consumeImportKey(char c, int key) {
        if (ui.session.areaImporting()) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) ui.router.closeRoot();
            return true;
        }
        return false;
    }

    boolean consumeGizmoKey(char c, int key) {
        if (key == Keyboard.KEY_G && !ui.router.textFieldFocused()
            && ui.preview.canMoveSelection()
            && ui.preview.previewInteractive()) {
            this.moveHandles = !this.moveHandles;
            return true;
        }
        return false;
    }

    boolean consumeCameraKey(char c, int key) {
        if (!ui.router.textFieldFocused() && ui.preview.previewInteractive()
            && HologramKeyBindings.modifiers() == 0
            && (ui.tools.commands()
                .matches("cameraForward", key, HologramKeyBindings.modifiers())
                || ui.tools.commands()
                    .matches("cameraBack", key, HologramKeyBindings.modifiers())
                || ui.tools.commands()
                    .matches("cameraLeft", key, HologramKeyBindings.modifiers())
                || ui.tools.commands()
                    .matches("cameraRight", key, HologramKeyBindings.modifiers())))
            return true;
        return false;
    }

    boolean consumeCompactCandidatesWheel(int wheel, int mx, int my) {
        if (ui.preview.previewInteractive() && !this.inventory && ui.canvas.inPanel(mx, my)) {
            ui.config.hatchOffset(
                Math.max(
                    0,
                    Math.min(
                        Math.max(
                            0,
                            ui.config.filteredHatches()
                                .size() - 2),
                        ui.config.hatchOffset() + (wheel > 0 ? -1 : 1))));
            return true;
        }
        return false;
    }

    void mouseClickMove(int x, int y, int button, long elapsed) {
        if (navigationButton >= 0 && navigationButton == button) {
            int mx = ui.canvas.localX(x), my = ui.canvas.localY(y), dx = mx - dragX, dy = my - dragY;
            navigationMotion += Math.abs((long) dx) + Math.abs((long) dy);
            if (button == 1) {
                if (!navigationDragged) {
                    if (navigationMotion < 4) {
                        dragX = mx;
                        dragY = my;
                        return;
                    }
                    navigationDragged = true;
                    dx = mx - navigationStartX;
                    dy = my - navigationStartY;
                }
                yaw -= dx * .012;
                pitch = Math.max(-1.35, Math.min(1.35, pitch + dy * .012));
            } else {
                double scale = HologramRenderer.gridScale();
                if (scale > 0) {
                    CameraBasis basis = new CameraBasis(
                        new CameraPose(0, 0, 0, -Math.toDegrees(yaw), Math.toDegrees(pitch)));
                    camera.x += (-dx * basis.rightX() + dy * basis.upX()) / scale;
                    camera.y += (-dx * basis.rightY() + dy * basis.upY()) / scale;
                    camera.z += (-dx * basis.rightZ() + dy * basis.upZ()) / scale;
                }
            }
            dragX = mx;
            dragY = my;
            return;
        }
        if (ui.textBoxes.drag(ui.canvas.localX(x), ui.canvas.localY(y), false)) return;
        if (ui.library.browsePreviewActive()) {
            if (browseDragging && browseMeshInteractive()) {
                int mx = ui.canvas.localX(x), my = ui.canvas.localY(y);
                browseYaw -= (mx - dragX) * .012;
                browsePitch = Math.max(-1.35, Math.min(1.35, browsePitch + (my - dragY) * .012));
                dragX = mx;
                dragY = my;
            }
            return;
        }
        if (ui.session.areaImporting() || ui.ae.aeReviewNavigation() != null || ui.identity.deidentificationDrawer())
            return;
        if (this.gizmo.axis() >= 0) {
            this.gizmo.update(ui.canvas.localX(x), ui.canvas.localY(y));
            return;
        }
        if (this.boxSelecting) {
            this.selection.box(
                ui.session.state(),
                this.boxStart,
                HologramRenderer.pick(ui.canvas.localX(x), ui.canvas.localY(y)),
                this.boxAdditive);
            return;
        }
        if (!this.dragging) return;
        int mx = ui.canvas.localX(x), my = ui.canvas.localY(y), dx = mx - this.dragX, dy = my - this.dragY;
        if (Math.abs(dx) + Math.abs(dy) > 1) this.moved = true;
        if (!blueprintCameraControls()) {
            this.yaw = this.yaw - (dx * .012);
            this.pitch = Math.max(-1.35, Math.min(1.35, this.pitch + dy * .012));
        }
        this.dragX = mx;
        this.dragY = my;
    }

    void mouseMovedOrUp(int x, int y, int button) {
        if (navigationButton >= 0 && navigationButton == button) {
            mouseClickMove(x, y, button, 0);
            boolean contextClick = button == 1 && !navigationDragged;
            navigationButton = -1;
            // NativeTools retains its normal deferred panel lifecycle and inventory-cancellation behavior.
            if (contextClick) ui.tools.consumeMouse(ui.canvas.localX(x), ui.canvas.localY(y), button);
            return;
        }
        if (button == 0 && ui.textBoxes.drag(ui.canvas.localX(x), ui.canvas.localY(y), true)) return;
        if (ui.library.browsePreviewActive()) {
            if (button == 0) browseDragging = false;
            return;
        }
        if (ui.session.areaImporting() || ui.ae.aeReviewNavigation() != null || ui.identity.deidentificationDrawer())
            return;
        if (ui.ae.aeReview() != null) return;
        if (button == 0 && this.gizmo.axis() >= 0) {
            this.gizmo.update(ui.canvas.localX(x), ui.canvas.localY(y));
            int[] offset = this.gizmo.offset();
            this.gizmo.cancel();
            if ((offset[0] != 0 || offset[1] != 0 || offset[2] != 0) && ui.session.editable()
                && ui.preview.canMoveSelection()) {
                NBTTagCompound move = new NBTTagCompound();
                move.setIntArray("indices", this.selection.indices());
                move.setInteger("dx", offset[0]);
                move.setInteger("dy", offset[1]);
                move.setInteger("dz", offset[2]);
                move.setBoolean("copy", this.gizmoCopy);
                ui.session.send("editMove", move, true);
            }
            return;
        }
        if (button == 0 && this.boxSelecting) {
            this.boxSelecting = false;
            this.selection.box(
                ui.session.state(),
                this.boxStart,
                HologramRenderer.pick(ui.canvas.localX(x), ui.canvas.localY(y)),
                this.boxAdditive);
            this.selected = this.selection.first();
            ui.config.syncTargetSelectors();
            return;
        }
        if (button == 0 && this.inventoryDragging) {
            int mx = ui.canvas.localX(x), my = ui.canvas.localY(y);
            this.inventoryDragging = false;
            int target = HologramRenderer.pick(mx, my);
            if (ui.canvas.inViewport(mx, my) && !ui.preview.previewBlockedAt(mx, my) && target >= 0)
                ui.preview.pinInventory(target);
            else if (mx >= 165 && mx < 396 && my >= 104 && my < 122) ui.preview.pinInventory(this.selected);
            return;
        }
        if (button == 0 && this.dragging) {
            int target = HologramRenderer.pick(ui.canvas.localX(x), ui.canvas.localY(y));
            if (!this.moved && ui.canvas.inViewport(ui.canvas.localX(x), ui.canvas.localY(y))
                && !ui.preview.previewBlockedAt(ui.canvas.localX(x), ui.canvas.localY(y))
                && ui.session.editable()) {
                String structure = HologramRenderer.pickStructure(ui.canvas.localX(x), ui.canvas.localY(y));
                boolean whole = ui.preview.sameTypeSelectionHeld();
                if (structure.isEmpty() && !whole && target < 0) {
                    ui.preview.clearSelection();
                    this.dragging = false;
                    return;
                }
                this.selection
                    .previewClick(ui.session.state(), target, structure, whole, ui.preview.controlSelectionHeld());
                this.selected = this.selection.first();
                ui.memory.cellEditHint(this.selected >= 0);
                ui.config.syncTargetSelectors();
                if (target >= 0 && (ui.session.state().cells.get(target).missingBlock
                    || ui.session.state().cells.get(target).sourceMissing)) ui.annotations.versionsDrawer(true);
            }
            this.dragging = false;
        }
    }

    void resetSession(HologramState next) {
        gizmo.cancel();
        selection.load(
            next.data.getIntArray("selectedIndices"),
            next.data.hasKey("selected") ? next.data.getInteger("selected") : -1,
            next.cells.size());
        pendingSelectionSequence = -1;
        queuedDispatchSequence = -1;
        cancelQueuedEdits("会话已变化，已取消等待中的编辑");
    }

    void pauseForImport() {
        navigationButton = -1;
        editQueue.clear();
        queuedDispatchSequence = -1;
        dragging = boxSelecting = inventoryDragging = false;
        gizmo.cancel();
    }

    void releasePointerCapture() {
        navigationButton = -1;
        dragging = browseDragging = boxSelecting = inventoryDragging = false;
        gizmo.cancel();
    }

    void retainSelection(HologramState next) {
        if (next.topologyChanged) selection
            .load(next.data.getIntArray("selectedIndices"), next.data.getInteger("selected"), next.cells.size());
        else selection.retain(next.cells.size());
        selected = selection.first();
    }

    void acknowledgeSelection(HologramState next) {
        if (pendingSelectionSequence >= 0 && next.data.getLong("uiSequence") >= pendingSelectionSequence) {
            selection
                .load(next.data.getIntArray("selectedIndices"), next.data.getInteger("selected"), next.cells.size());
            pendingSelectionSequence = -1;
        }
    }

    void acknowledgeEdits(HologramState next) {
        if (queuedDispatchSequence >= 0 && next.data.getLong("uiSequence") >= queuedDispatchSequence) {
            queuedDispatchSequence = -1;
            ui.session.notice("");
        }
    }

    void syncLayerAndSelection(HologramState next) {
        if (allLayers) layer = next.maxY;
        selected = selection.first();
    }

    boolean allLayers() {
        return this.allLayers;
    }

    boolean boxSelecting() {
        return this.boxSelecting;
    }

    boolean boxSelecting(boolean value) {
        this.boxSelecting = value;
        return value;
    }

    boolean dragging(boolean value) {
        this.dragging = value;
        return value;
    }

    HologramEditQueue editQueue() {
        return this.editQueue;
    }

    int[] emptyVoxelPreview() {
        return this.emptyVoxelPreview;
    }

    HologramGizmo gizmo() {
        return this.gizmo;
    }

    boolean inventory() {
        return this.inventory;
    }

    boolean inventory(boolean value) {
        this.inventory = value;
        return value;
    }

    boolean inventoryDragging() {
        return this.inventoryDragging;
    }

    boolean inventoryDragging(boolean value) {
        this.inventoryDragging = value;
        return value;
    }

    int inventorySlot() {
        return this.inventorySlot;
    }

    int inventorySlot(int value) {
        this.inventorySlot = value;
        return value;
    }

    int layer() {
        return this.layer;
    }

    int layer(int value) {
        this.layer = value;
        return value;
    }

    boolean moveHandles() {
        return this.moveHandles;
    }

    boolean moveHandles(boolean value) {
        this.moveHandles = value;
        return value;
    }

    long pendingSelectionSequence(long value) {
        this.pendingSelectionSequence = value;
        return value;
    }

    double pitch() {
        return this.pitch;
    }

    double pitch(double value) {
        this.pitch = value;
        return value;
    }

    long queuedDispatchSequence(long value) {
        this.queuedDispatchSequence = value;
        return value;
    }

    int selected() {
        return this.selected;
    }

    int selected(int value) {
        this.selected = value;
        return value;
    }

    HologramSelection selection() {
        return this.selection;
    }

    int sourceChoice() {
        return this.sourceChoice;
    }

    int sourceChoice(int value) {
        this.sourceChoice = value;
        return value;
    }

    int sourceOffset() {
        return this.sourceOffset;
    }

    int sourceOffset(int value) {
        this.sourceOffset = value;
        return value;
    }

    boolean sourceTargets() {
        return this.sourceTargets;
    }

    int view(int value) {
        this.view = value;
        return value;
    }

    double yaw() {
        return this.yaw;
    }

    double yaw(double value) {
        this.yaw = value;
        return value;
    }

    double zoom() {
        return ui.library.browsePreviewActive() ? browseZoom : this.zoom;
    }

    double zoom(double value) {
        value = HologramPreviewCamera.clampZoom(value);
        if (ui.library.browsePreviewActive()) {
            if (browseMeshInteractive()) browseZoom = value;
        } else this.zoom = value;
        return value;
    }

    void prepareCommand(String op) {
        if ("scan".equals(op)) cancelQueuedEdits("重新采集结构，已取消等待中的编辑");
    }

    void recordSelectionCommand(String op, long sequence) {
        if ("addPosition".equals(op) || "editPaste".equals(op)
            || "editDelete".equals(op)
            || "rotateCell".equals(op)
            || "editMove".equals(op)
            || "editUndo".equals(op)
            || "editRedo".equals(op)
            || "selectStructure".equals(op)) pendingSelectionSequence = sequence;
    }

    void initializeSelection(HologramState snapshot) {
        selected = snapshot.data.hasKey("selected") ? snapshot.data.getInteger("selected") : -1;
        selection.load(snapshot.data.getIntArray("selectedIndices"), selected, snapshot.cells.size());
    }

    void restoreInitialUi(UiMemory memory) {
        int[] restored = ui.memory.rememberedSelection(memory);
        if (restored != null) selection.load(restored, -1, ui.session.state().cells.size());
    }

    void initializeLayer(HologramState snapshot) {
        selected = selection.first();
        layer = snapshot.maxY;
    }

    NBTTagCompound backgroundUiState() {
        NBTTagCompound state = new NBTTagCompound();
        state.setLong("revision", ui.session.revision());
        state.setIntArray("selection", selection.indices());
        state.setIntArray("positions", selection.positions(ui.session.state()));
        state.setInteger("layer", layer);
        state.setBoolean("allLayers", allLayers);
        state.setInteger("view", view);
        state.setDouble("yaw", yaw);
        state.setDouble("pitch", pitch);
        state.setDouble("zoom", zoom);
        state.setDouble("cameraX", camera.x);
        state.setDouble("cameraY", camera.y);
        state.setDouble("cameraZ", camera.z);
        state.setInteger("sourceOffset", sourceOffset);
        state.setInteger("sourceChoice", sourceChoice);
        state.setBoolean("sourceTargets", sourceTargets);
        state.setBoolean("inventory", inventory);
        state.setInteger("inventorySlot", inventorySlot);
        return state;
    }

    void restoreBackgroundUiState(NBTTagCompound state) {
        int[] indices = state.getIntArray("selection");
        if (state.getLong("revision") != ui.session.revision()) {
            int[] positions = state.getIntArray("positions");
            java.util.Map<String, Integer> coordinates = new java.util.HashMap<>();
            for (HologramState.Cell cell : ui.session.state().cells)
                coordinates.put(cell.x + "," + cell.y + "," + cell.z, cell.index);
            java.util.List<Integer> retained = new java.util.ArrayList<>();
            for (int i = 0; i + 2 < positions.length; i += 3) {
                Integer index = coordinates.get(positions[i] + "," + positions[i + 1] + "," + positions[i + 2]);
                if (index != null) retained.add(index);
            }
            indices = new int[retained.size()];
            for (int i = 0; i < indices.length; i++) indices[i] = retained.get(i);
        }
        selection.load(indices, -1, ui.session.state().cells.size());
        selected = selection.first();
        allLayers = state.getBoolean("allLayers");
        layer = allLayers ? ui.session.state().maxY
            : Math.max(ui.session.state().minY, Math.min(ui.session.state().maxY, state.getInteger("layer")));
        view = state.getInteger("view");
        yaw = state.getDouble("yaw");
        pitch = state.getDouble("pitch");
        zoom = state.getDouble("zoom");
        camera.x = state.getDouble("cameraX");
        camera.y = state.getDouble("cameraY");
        camera.z = state.getDouble("cameraZ");
        sourceOffset = Math.max(0, state.getInteger("sourceOffset"));
        sourceChoice = state.getInteger("sourceChoice");
        sourceTargets = state.getBoolean("sourceTargets");
        inventory = state.getBoolean("inventory");
        inventorySlot = state.getInteger("inventorySlot");
        // Pointer gestures and queued edits are intentionally never replayed on a new widget tree.
        navigationButton = -1;
        dragging = boxSelecting = inventoryDragging = false;
        gizmo.cancel();
        ui.config.syncTargetSelectors();
    }
}
