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
    private int originalX, originalY, originalZ;
    private int[] dragOrigin;
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

    boolean choosingPosition() {
        return draft != null && !chosen && !pending;
    }

    void reset() {
        draft = null;
        ghost = Collections.emptyList();
        pending = chosen = false;
        gizmo.cancel();
    }

    String label() {
        return draft != null ? pending ? "校验文字…" : chosen ? "确认放置" : "请选择文字位置"
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
        if (key == Keyboard.KEY_R) {
            int[] previous = draft.turns;
            try {
                draft.turn(HologramClient.altHeld());
                ghost = draft.generate();
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

    void draw() {
        if (draft == null) return;
        if (!chosen) {
            ui.canvas.text(
                "请先点击工作区选择文字位置 · R 旋转 / Alt+R 竖转 · Esc 取消",
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
            t.startDrawingQuads();
            int color = HologramTextColors.preview(
                draft.material,
                new net.minecraft.item.ItemStack(
                    net.minecraft.block.Block.getBlockFromName(draft.material),
                    1,
                    draft.metadata),
                draft.color);
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
        } finally {
            GL11.glPopAttrib();
        }
        double[] anchor = HologramRenderer.project(draft.x, draft.y, draft.z);
        gizmo.project(anchor[0], anchor[1], ui.preview.yaw(), ui.preview.pitch(), HologramRenderer.gridScale());
        HologramGizmoRenderer.draw(gizmo, ui.canvas, -1000, -1000);
        ui.canvas.text(
            "文字位置 " + draft.x + "," + draft.y + "," + draft.z + " · R / Alt+R · 拖轴/彩色面 · Esc 取消",
            38,
            245,
            390,
            0xd6b6ff);
    }
}
