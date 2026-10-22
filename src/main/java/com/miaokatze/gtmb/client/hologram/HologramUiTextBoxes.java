package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtmb.hologram.HologramPictureBox;
import com.miaokatze.gtmb.hologram.HologramTextBox;

/** Local text placement intent, including pixel ghost and axis/keyboard positioning. */
final class HologramUiTextBoxes {

    private final HologramUiModules ui;
    private final HologramGizmo gizmo = new HologramGizmo();
    private HologramTextBox draft;
    private List<HologramTextBox.Cell> ghost = Collections.emptyList();
    private int originalX, originalY, originalZ;
    private int[] dragOrigin;
    private boolean chosen, pending;
    private long request;
    private com.miaokatze.gtmb.hologram.HologramAttachmentGeometry.Job localJob;
    private final HologramProgress.Tracker taskProgress = new HologramProgress.Tracker();
    private byte[] picturePixels;
    private final HologramPictureTexture pictureTexture = new HologramPictureTexture();
    private static final int[][] FACES = { { 0, 1, 3, 2 }, { 4, 6, 7, 5 }, { 0, 4, 5, 1 }, { 2, 3, 7, 6 },
        { 0, 2, 6, 4 }, { 1, 5, 7, 3 } };

    HologramUiTextBoxes(HologramUiModules ui) {
        this.ui = ui;
    }

    boolean allowed() {
        NBTTagCompound state = ui.session.state().data;
        return ui.workspaces.blueprint() && ui.session.editable()
            && !state.getBoolean("virtualPlacement")
            && !state.getBoolean("presetBrowseForPlacement")
            && !state.getBoolean("snapshotPending")
            && !state.getBoolean("localPlanPending")
            && !ui.library.browsePreviewActive()
            && cpw.mods.fml.common.Loader.isModLoaded("littletiles");
    }

    boolean active() {
        return draft != null;
    }

    HologramProgress progress() {
        if (draft == null || localJob == null && !pending) {
            taskProgress.clear();
            return null;
        }
        long now = System.nanoTime();
        if (localJob != null) return taskProgress.update(
            "attachment:" + request,
            "attachment.generate",
            localJob.completed,
            localJob.total,
            "cells",
            false,
            now);
        NBTTagCompound upload = com.miaokatze.gtmb.hologram.HologramAttachmentUpload.clientProgressState();
        if (upload.getBoolean("pending")) return taskProgress.update(
            "attachment:" + request,
            upload.getString("phase"),
            upload.getLong("done"),
            upload.getLong("total"),
            "bytes",
            false,
            now);
        NBTTagCompound ack = ui.session.state().data.getCompoundTag("textPlacementAck");
        return taskProgress.update(
            "attachment:" + request,
            "attachment.validate",
            ack.getBoolean("pending") ? ack.getLong("done") : 0,
            ack.getBoolean("pending") ? ack.getLong("total") : 0,
            "cells",
            false,
            now);
    }

    boolean choosingPosition() {
        return draft != null && !chosen && !pending;
    }

    void reset() {
        if (pending) com.miaokatze.gtmb.hologram.HologramNetwork.clearAttachmentClientTransport();
        if (localJob != null) localJob.cancel();
        localJob = null;
        picturePixels = null;
        pictureTexture.clear();
        draft = null;
        ghost = Collections.emptyList();
        pending = chosen = false;
        gizmo.cancel();
    }

    boolean active(boolean picture) {
        return draft != null && (draft.picture != null) == picture;
    }

    boolean allowed(boolean picture) {
        return allowed() && (draft == null || active(picture));
    }

    String label() {
        return label(false);
    }

    String label(boolean picture) {
        String noun = picture ? "图片" : "文字";
        if (active(picture) && localJob != null) return "生成" + noun + "…";
        if (active(picture) && pending) {
            String transfer = com.miaokatze.gtmb.hologram.HologramAttachmentUpload.clientProgress();
            NBTTagCompound progress = ui.session.state().data.getCompoundTag("textPlacementAck");
            return !transfer.isEmpty() ? transfer
                : progress.getBoolean("pending") ? "处理 " + progress.getLong("done") + "/" + progress.getLong("total")
                    : "校验" + noun + "…";
        }
        return active(picture) ? pending ? "校验" + noun + "…" : chosen ? "确认放置" : "请选择" + noun + "位置"
            : selectedDefinition(picture) != null ? picture ? "编辑图片" : "编辑内容" : picture ? "添加图片" : "文字转蓝图";
    }

    void activate() {
        activate(draft != null && draft.picture != null);
    }

    void activate(boolean picture) {
        if (!allowed(picture) || pending || localJob != null) return;
        if (draft != null && chosen) {
            NBTTagCompound action = new NBTTagCompound();
            action.setLong("planRevision", ui.session.revision());
            action.setLong("textRequest", ++request);
            action.setTag("textBox", draft.write());
            pending = true;
            ui.session.send("placeTextBox", action, true);
            return;
        }
        // Finish the current MUI mouse dispatch before replacing its active screen and viewport tree.
        ui.host.parent()
            .deferAction(() -> openEditor(picture));
    }

    private void openEditor(boolean picture) {
        if (Minecraft.getMinecraft().currentScreen != ui.host.parent() || !allowed(picture)
            || pending
            || localJob != null) return;
        try {
            NBTTagCompound selected = selectedDefinition(picture);
            HologramTextBox value = draft != null ? HologramTextBox.read(draft.write())
                : selected == null ? new HologramTextBox() : HologramTextBox.read(selected);
            if (value.id.isEmpty())
                value.id = (picture ? HologramPictureBox.PREFIX : HologramTextBox.PREFIX) + java.util.UUID.randomUUID();
            HologramMuiScreen editor = picture ? new HologramPictureEditorScreen(ui, value)
                : new HologramTextEditorScreen(ui, value);
            editor.openFrom(ui.host.parent());
        } catch (Exception failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Attachment editor could not open", failure);
            ui.session.notice("打开编辑器失败，请重试：" + (failure.getMessage() == null ? "窗口初始化失败" : failure.getMessage()));
        }
    }

    /** A repeated workspace snapshot must not replace a local editor with a new root screen. */
    boolean retainEditor(HologramState next) {
        HologramState current = ui.session.state();
        NBTTagCompound data = next.data;
        if (!current.session.equals(next.session) || ui.session.revision() != data.getLong("planRevision")
            || current.data.getLong("uiSequence") != data.getLong("uiSequence")
            || !data.getBoolean("open")
            || data.getBoolean("settingsOnly")
            || data.getBoolean("virtualPlacement")
            || data.getBoolean("areaSelecting")) return false;
        ui.host.parent()
            .updateState(next);
        return true;
    }

    void begin(HologramTextBox value) throws java.io.IOException {
        if (localJob != null) localJob.cancel();
        pictureTexture.clear();
        picturePixels = null;
        localJob = com.miaokatze.gtmb.hologram.HologramAttachmentGeometry.submit(value);
        draft = value;
        ghost = Collections.emptyList();
        chosen = pending = false;
    }

    void begin(HologramTextBox value, List<HologramTextBox.Cell> generated) {
        ghost = generated;
        draft = value;
        originalX = value.x;
        originalY = value.y;
        originalZ = value.z;
        chosen = false;
        pending = false;
        NBTTagList existing = ui.session.state().data.getTagList("structures", 10);
        for (int i = 0; i < existing.tagCount(); i++) if (value.id.equals(
            existing.getCompoundTagAt(i)
                .getString("id"))) {
                    chosen = true;
                    activate();
                    return;
                }
        if (ui.session.state().cells.isEmpty()) {
            chosen = true;
            activate();
            return;
        }
        ui.session.notice("点击工作区按视角选位；R 整体旋转，Alt+R 竖转；小键盘调整位置，拖轴或彩色小面移动，再确认放置");
    }

    NBTTagCompound selectedDefinition() {
        HologramState.Cell selected = ui.session.selectedCell();
        if (selected == null) return null;
        NBTTagList structures = ui.session.state().data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            NBTTagCompound row = structures.getCompoundTagAt(i);
            if (selected.structureIds.contains(row.getString("id")) && row.hasKey("textBox", 10))
                return row.getCompoundTag("textBox");
        }
        return null;
    }

    private NBTTagCompound selectedDefinition(boolean picture) {
        NBTTagCompound definition = selectedDefinition();
        return definition != null && definition.hasKey("picture", 10) == picture ? definition : null;
    }

    void acknowledge(HologramState state) {
        NBTTagCompound ack = state.data.getCompoundTag("textPlacementAck");
        if (!pending || ack.getLong("request") != request) return;
        if (ack.getBoolean("pending")) return;
        pending = false;
        if (ack.getBoolean("accepted")) {
            reset();
        } else {
            chosen = true;
            ui.session.notice(ack.getString("message") + "；" + draft.noun() + "预览保留，请移动后再次确认");
        }
    }

    boolean key(int key) {
        if (draft == null || ui.router.textFieldFocused()) return false;
        if (key == Keyboard.KEY_ESCAPE) {
            if (pending) ui.session.send("cancelAttachment", new NBTTagCompound(), false);
            reset();
            return true;
        }
        if (pending || localJob != null) return false;
        if (key == Keyboard.KEY_R) {
            int[] previous = draft.turns;
            try {
                draft.turn(HologramClient.altHeld());
                if (draft.picture == null) {
                    localJob = com.miaokatze.gtmb.hologram.HologramAttachmentGeometry.submit(draft);
                }
                originalX = draft.x;
                originalY = draft.y;
                originalZ = draft.z;
            } catch (java.io.IOException invalid) {
                draft.turns = previous;
                ui.session.notice(invalid.getMessage());
            }
            return true;
        }
        if (!chosen) return false;
        int direction = key == Keyboard.KEY_NUMPAD8 || key == Keyboard.KEY_UP ? 1
            : key == Keyboard.KEY_NUMPAD2 || key == Keyboard.KEY_DOWN ? -1 : 0;
        if (direction != 0) {
            if (HologramClient.altHeld()) draft.y += direction;
            else draft.z -= direction;
        } else if (key == Keyboard.KEY_NUMPAD4 || key == Keyboard.KEY_LEFT) draft.x--;
        else if (key == Keyboard.KEY_NUMPAD6 || key == Keyboard.KEY_RIGHT) draft.x++;
        else if (key == Keyboard.KEY_RETURN && chosen) {
            activate();
            return true;
        } else return false;
        chosen = true;
        return true;
    }

    boolean mouse(int mx, int my, int button) {
        if (draft == null || pending || button != 0 || !ui.canvas.inViewport(mx, my)) return false;
        for (HologramUiCanvas.Control control : ui.canvas.controls()) if (control.contains(mx, my)) return false;
        int axis = chosen ? gizmo.pick(mx, my) : -1;
        if (axis >= 0) {
            gizmo.begin(axis, mx, my);
            dragOrigin = new int[] { draft.x, draft.y, draft.z };
            return true;
        }
        int[] point = HologramRenderer.pickAdjacentVoxel(mx, my);
        if (point == null) point = HologramRenderer
            .pickEmptyVoxel(ui.session.state(), mx, my, ui.session.state().data.getInteger("y") + draft.y, false);
        if (point != null) {
            draft.x = point[0] - ui.session.state().data.getInteger("x");
            draft.y = point[1] - ui.session.state().data.getInteger("y");
            draft.z = point[2] - ui.session.state().data.getInteger("z");
            chosen = true;
        }
        return true;
    }

    boolean drag(int mx, int my, boolean release) {
        if (draft == null || gizmo.axis() < 0) return false;
        gizmo.update(mx, my);
        int[] offset = gizmo.offset();
        draft.x = dragOrigin[0] + offset[0];
        draft.y = dragOrigin[1] + offset[1];
        draft.z = dragOrigin[2] + offset[2];
        chosen = true;
        if (release) gizmo.cancel();
        return true;
    }

    void uploadFailed() {
        com.miaokatze.gtmb.hologram.HologramNetwork.clearAttachmentClientTransport();
        pending = false;
        chosen = true;
    }

    void draw() {
        if (draft == null) return;
        if (localJob != null) {
            if (!localJob.done) {
                ui.canvas.text(
                    "后台生成" + draft.noun() + " " + localJob.completed + "/" + localJob.total + " · Esc 取消",
                    38,
                    245,
                    390,
                    0xd6b6ff);
                return;
            }
            com.miaokatze.gtmb.hologram.HologramAttachmentGeometry.Job ready = localJob;
            localJob = null;
            if (ready.failure != null) {
                ui.session.notice(ready.failure.getMessage());
                reset();
                return;
            }
            picturePixels = ready.picturePixels;
            boolean wasChosen = chosen;
            if (ghost.isEmpty()) begin(draft, ready.cells);
            else {
                ghost = ready.cells;
                chosen = wasChosen;
            }
        }
        if (!chosen) {
            ui.canvas.text(
                "请先点击工作区选择" + draft.noun() + "位置 · R 旋转 / Alt+R 竖转 · Esc 取消",
                38,
                245,
                390,
                HologramUiAccent.color(HologramUiAccent.SPECTRUM));
            return;
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Tessellator t = Tessellator.instance;
            if (draft.picture != null && picturePixels != null) {
                pictureTexture.update(draft.picture, picturePixels);
                pictureTexture.bind();
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1f);
                double[][] points = new double[8][];
                for (int v = 0; v < 8; v++) {
                    double[] point = draft.corner(
                        (v & 1) == 0 ? 0 : draft.picture.width * draft.pixelX,
                        (v & 2) == 0 ? 0 : draft.picture.height * draft.pixelY,
                        (v & 4) == 0 ? 0 : draft.thickness);
                    points[v] = HologramRenderer.project(
                        draft.x - .5 + point[0] / 16,
                        draft.y - .5 + point[1] / 16,
                        draft.z - .5 + point[2] / 16);
                }
                t.startDrawingQuads();
                t.setColorRGBA_I(0xffffff, 150);
                for (int[] face : FACES) for (int v : face)
                    t.addVertexWithUV(points[v][0], points[v][1], 10, (v & 1) == 0 ? 0 : 1, (v & 2) == 0 ? 1 : 0);
                t.draw();
            } else {
                int total = 0;
                for (HologramTextBox.Cell cell : ghost) total += cell.boxes.length / 7;
                int stride = Math.max(1, (total + 2047) / 2048), visited = 0;
                int color = HologramTextColors.preview(
                    draft.material,
                    new net.minecraft.item.ItemStack(
                        net.minecraft.block.Block.getBlockFromName(draft.material),
                        1,
                        draft.metadata),
                    draft.color);
                t.startDrawingQuads();
                t.setColorRGBA_I(color, 104);
                for (HologramTextBox.Cell cell : ghost) for (int at = 0; at < cell.boxes.length; at += 7) {
                    if (visited++ % stride != 0) continue;
                    double x = cell.x + draft.x - originalX - .5, y = cell.y + draft.y - originalY - .5,
                        z = cell.z + draft.z - originalZ - .5;
                    double[][] points = new double[8][];
                    for (int v = 0; v < 8; v++) points[v] = HologramRenderer.project(
                        x + cell.boxes[at + ((v & 1) == 0 ? 1 : 4)] / 16d,
                        y + cell.boxes[at + ((v & 2) == 0 ? 2 : 5)] / 16d,
                        z + cell.boxes[at + ((v & 4) == 0 ? 3 : 6)] / 16d);
                    for (int[] face : FACES)
                        for (int vertex : face) t.addVertex(points[vertex][0], points[vertex][1], 10);
                }
                t.draw();
            }
        } finally {
            GL11.glPopAttrib();
        }
        double[] anchor = HologramRenderer.project(draft.x, draft.y, draft.z);
        gizmo.project(anchor[0], anchor[1], ui.preview.yaw(), ui.preview.pitch(), HologramRenderer.gridScale());
        HologramGizmoRenderer.draw(gizmo, ui.canvas, -1000, -1000);
        ui.canvas.text(
            draft.noun() + "位置 " + draft.x + "," + draft.y + "," + draft.z + " · R / Alt+R · 拖轴/彩色面 · Esc 取消",
            38,
            245,
            390,
            0xd6b6ff);
    }

}
