package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtmb.hologram.HologramTextBox;

/** Local text placement intent, including pixel ghost and axis/keyboard positioning. */
final class HologramUiTextBoxes {

    private final HologramUiModules ui;
    private final HologramGizmo gizmo = new HologramGizmo();
    private HologramTextBox draft;
    private List<HologramTextBox.Cell> ghost = Collections.emptyList();
    private int originalX, originalY, originalZ, axisStart;
    private boolean chosen, pending;
    private long request;

    HologramUiTextBoxes(HologramUiModules ui) {
        this.ui = ui;
    }

    boolean allowed() {
        NBTTagCompound state = ui.session.state().data;
        return ui.session.editable() && !state.getBoolean("virtualPlacement")
            && !state.getBoolean("presetBrowseForPlacement")
            && !state.getBoolean("snapshotPending")
            && !state.getBoolean("localPlanPending")
            && !ui.library.browsePreviewActive()
            && cpw.mods.fml.common.Loader.isModLoaded("littletiles");
    }

    boolean active() {
        return draft != null;
    }

    void reset() {
        draft = null;
        ghost = Collections.emptyList();
        pending = chosen = false;
        gizmo.cancel();
    }

    String label() {
        return draft != null ? pending ? "校验文字…" : chosen ? "确认放置" : "选择文字位置"
            : selectedDefinition() != null ? "编辑内容" : "文字转蓝图";
    }

    void activate() {
        if (!allowed() || pending) return;
        if (draft != null && chosen) {
            NBTTagCompound action = new NBTTagCompound();
            action.setLong("planRevision", ui.session.revision());
            action.setLong("textRequest", ++request);
            action.setTag("textBox", draft.write());
            pending = true;
            ui.session.send("placeTextBox", action, true);
            return;
        }
        try {
            NBTTagCompound selected = selectedDefinition();
            HologramTextBox value = draft != null ? HologramTextBox.read(draft.write())
                : selected == null ? new HologramTextBox() : HologramTextBox.read(selected);
            if (value.id.isEmpty()) value.id = HologramTextBox.PREFIX + java.util.UUID.randomUUID();
            Minecraft.getMinecraft()
                .displayGuiScreen(new HologramTextEditorScreen(ui, value));
        } catch (Exception failure) {
            ui.session.notice(failure.getMessage());
        }
    }

    void begin(HologramTextBox value) throws java.io.IOException {
        ghost = value.generate();
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
        ui.session.notice("点击工作区选择文字位置；小键盘 8/2/4/6 调整平面，Alt+8/2 调整高度，或拖动坐标轴；确认放置时校验");
    }

    private NBTTagCompound selectedDefinition() {
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

    void acknowledge(HologramState state) {
        NBTTagCompound ack = state.data.getCompoundTag("textPlacementAck");
        if (!pending || ack.getLong("request") != request) return;
        pending = false;
        if (ack.getBoolean("accepted")) {
            draft = null;
            ghost = Collections.emptyList();
            gizmo.cancel();
        } else {
            chosen = true;
            ui.session.notice(ack.getString("message") + "；文字预览保留，请移动后再次确认");
        }
    }

    boolean key(int key) {
        if (draft == null || pending || ui.router.textFieldFocused()) return false;
        if (key == Keyboard.KEY_ESCAPE) {
            draft = null;
            ghost = Collections.emptyList();
            gizmo.cancel();
            return true;
        }
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
        int axis = gizmo.pick(mx, my);
        if (axis >= 0) {
            gizmo.begin(axis, mx, my);
            axisStart = axis == 0 ? draft.x : axis == 1 ? draft.y : draft.z;
            return true;
        }
        int target = HologramRenderer.pick(mx, my);
        int[] point;
        if (target >= 0) {
            HologramState.Cell cell = ui.session.state().cells.get(target);
            point = new int[] { cell.x, cell.y + 1, cell.z };
        } else point = HologramRenderer
            .pickEmptyVoxel(ui.session.state(), mx, my, ui.session.state().data.getInteger("y") + draft.y);
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
        int axis = gizmo.axis(), value = axisStart + gizmo.update(mx, my);
        if (axis == 0) draft.x = value;
        else if (axis == 1) draft.y = value;
        else draft.z = value;
        chosen = true;
        if (release) gizmo.cancel();
        return true;
    }

    void draw() {
        if (draft == null) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            int color = draft.color == 0xffffff ? 0xc19cf4 : draft.color;
            t.setColorRGBA_I(color, 104);
            for (HologramTextBox.Cell cell : ghost) for (int i = 0; i < cell.tile.getInteger("tilesCount"); i++) {
                NBTTagCompound box = cell.tile.getCompoundTag("t" + i);
                double x = cell.x + draft.x - originalX - .5, y = cell.y + draft.y - originalY - .5,
                    z = cell.z + draft.z - originalZ - .5;
                double[][] points = new double[8][];
                for (int v = 0; v < 8; v++) points[v] = HologramRenderer.project(
                    x + box.getInteger((v & 1) == 0 ? "bBox0minX" : "bBox0maxX") / 16d,
                    y + box.getInteger((v & 2) == 0 ? "bBox0minY" : "bBox0maxY") / 16d,
                    z + box.getInteger((v & 4) == 0 ? "bBox0minZ" : "bBox0maxZ") / 16d);
                for (int[] face : new int[][] { { 0, 1, 3, 2 }, { 4, 6, 7, 5 }, { 0, 4, 5, 1 }, { 2, 3, 7, 6 },
                    { 0, 2, 6, 4 }, { 1, 5, 7, 3 } })
                    for (int vertex : face) t.addVertex(points[vertex][0], points[vertex][1], 10);
            }
            t.draw();
            double[] anchor = HologramRenderer.project(draft.x, draft.y, draft.z);
            gizmo.project(anchor[0], anchor[1], ui.preview.yaw(), ui.preview.pitch());
            t.startDrawing(GL11.GL_LINES);
            for (int axis = 0; axis < 3; axis++) {
                double[] end = gizmo.end(axis);
                t.setColorOpaque_I(HologramGizmo.COLORS[axis]);
                t.addVertex(anchor[0], anchor[1], 20);
                t.addVertex(end[0], end[1], 20);
            }
            t.draw();
        } finally {
            GL11.glPopAttrib();
        }
        ui.canvas.text(
            "文字位置 " + draft.x + "," + draft.y + "," + draft.z + " · 点击选位 / 拖轴 / 小键盘 · Esc 取消",
            38,
            245,
            390,
            0xd6b6ff);
    }
}
