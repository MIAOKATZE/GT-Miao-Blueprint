package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/** A separate real-model viewport for this blueprint's transactional quantum grouping draft. */
public final class HologramQuantumGroupsScreen extends HologramMuiCanvasScreen {

    private static final int WIDTH = 680, HEIGHT = 360, VX = 18, VY = 65, VW = 424, VH = 218;
    private static final int[] COLORS = { 0x75d7d3, 0xffcf78, 0xbda2ef, 0x8bd998, 0xed9cb3, 0x92baff };
    private final HologramScreen editor;
    private final HologramQuantumGroupsModel model;
    private final HologramSelection previewSelection = new HologramSelection();
    private final List<Control> controls = new ArrayList<>();
    private final Object world;
    private HologramState state;
    private HologramMuiTextField nameField;
    private float scale;
    private int originX, originY, offset, hovered = -1, dragX, dragY;
    private boolean dragging, stale;
    private double yaw = Math.PI / 4, pitch = Math.PI / 6, zoom = 1;
    private long pending = -1, renderGeneration;
    private String notice = "";
    private NBTTagList renderedNodeMarkers = new NBTTagList();
    private long markerRenderGeneration;

    private static final class Control {

        final int id, x, y, width, height;
        final boolean enabled;
        String label;

        Control(int id, int x, int y, int width, int height, boolean enabled) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.enabled = enabled;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    HologramQuantumGroupsScreen(HologramScreen editor, HologramState state) {
        super("quantum_groups");
        this.editor = editor;
        this.state = state;
        model = new HologramQuantumGroupsModel(state);
        world = net.minecraft.client.Minecraft.getMinecraft().theWorld;
    }

    @Override
    public void initGui() {
        super.initGui();
        resetNativeFields();
        scale = Math.min(width / (float) WIDTH, height / (float) HEIGHT);
        originX = Math.round((width - WIDTH * scale) / 2);
        originY = Math.round((height - HEIGHT * scale) / 2);
        String previous = nameField == null ? "" : nameField.getText();
        nameField = new HologramMuiTextField(this, 460, 220, 200, 19);
        nameField.setMaxStringLength(64);
        if (previous.isEmpty()) loadName();
        else nameField.setText(previous);
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void updateScreen() {
        nameField.updateCursorCounter();
        boolean local = editor.ui.session.isLocalAuthor();
        HologramState next = local ? editor.ui.session.author()
            .view() : HologramClient.state;
        if (next == null || !local && world != mc.theWorld
            || !model.session.equals(next.session)
            || local && HologramLocalAuthorRuntime.active() != editor.ui.session.author()) {
            stale = true;
            notice = "预览会话已改变，请取消并重新打开分类";
            return;
        }
        if (next == state) return;
        state = next;
        editor.updateState(next);
        if (pending >= 0 && next.data.getLong("uiSequence") >= pending) {
            pending = -1;
            NBTTagCompound processing = next.data.getCompoundTag("processing");
            if (next.data.getBoolean("quantumGroupsOperationSuccess")
                && "configureBlueprintQuantumGroups".equals(next.data.getString("processingPresetOperation"))
                && processing.getBoolean("quantumGroupsConfirmed")) {
                editor.quantumGroupsSaved();
                mc.displayGuiScreen(editor);
                return;
            }
            notice = "分类未保存：" + next.data.getString("status");
        }
        stale = model.revision != next.data.getLong("planRevision");
        if (stale) notice = "蓝图版本已改变，请取消并重新打开分类";
    }

    @Override
    protected void renderScene(int mouseX, int mouseY, float partialTicks) {
        canvasTransform(originX, originY, scale);
        drawDefaultBackground();
        int mx = localX(mouseX), my = localY(mouseY);
        controls.clear();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glTranslatef(originX, originY, 0);
        GL11.glScalef(scale, scale, 1);
        try {
            GL11.glColor4f(1, 1, 1, 1);
            HologramMuiTheme.CLOTH.draw(0f, 0f, WIDTH, HEIGHT);
            text("量子节点网络分类", 18, 13, 640, 0xffe59b);
            text("可选自定义分类；取消保留已有配置。点击节点选择，Ctrl 多选 / 再次点击取消。", 18, 31, 642, 0xb9d5d4);
            text("T 全选 · Ctrl+T 选择补集 · Ctrl+Z / Y 撤销重做 · 右键拖动旋转 · 滚轮缩放", 18, 47, 642, 0x91afb9);
            drawPreview(mx, my);
            text("类群 · " + model.groups.size() + " 组", 460, 68, 200, 0xffe59b);
            offset = Math.max(0, Math.min(offset, Math.max(0, model.groups.size() - 5)));
            for (int i = offset; i < Math.min(model.groups.size(), offset + 5); i++) {
                HologramQuantumGroupsModel.Group group = model.groups.get(i);
                button(
                    1000 + i,
                    460,
                    85 + (i - offset) * 23,
                    200,
                    20,
                    (model.active.equals(group.id) ? "▶ " : "") + group.name + " · " + model.count(group.id),
                    editable(),
                    mx,
                    my);
            }
            text("类群名称（最多 64 字，可输入中文）", 460, 205, 200, 0x91afb9);
            nameField.setEnabled(editable());
            nameField.drawTextBox();
            button(
                5,
                460,
                246,
                97,
                20,
                "新建并分入",
                editable() && !model.selected.isEmpty() && HologramQuantumGroupsModel.validName(nameField.getText()),
                mx,
                my);
            button(
                10,
                563,
                246,
                97,
                20,
                "应用名称",
                editable() && HologramQuantumGroupsModel.validName(nameField.getText()),
                mx,
                my);
            button(6, 460, 272, 200, 20, "选择的节点 → 当前类群", editable() && !model.selected.isEmpty(), mx, my);
            button(3, 18, 290, 96, 20, "全选 · T", editable(), mx, my);
            button(4, 120, 290, 115, 20, "补集 · Ctrl+T", editable(), mx, my);
            button(7, 241, 290, 103, 20, "设为未分类", editable() && !model.selected.isEmpty(), mx, my);
            button(11, 350, 290, 92, 20, "复位视角", true, mx, my);
            button(8, 18, 319, 94, 22, "撤销 · Ctrl+Z", editable() && model.canUndo(), mx, my);
            button(9, 118, 319, 94, 22, "重做 · Ctrl+Y", editable() && model.canRedo(), mx, my);
            button(2, 460, 319, 76, 22, "取消", pending < 0, mx, my);
            button(
                1,
                542,
                319,
                118,
                22,
                pending >= 0 ? "等待服务器确认" : "保存自定义分类",
                editable() && model.complete() && HologramQuantumGroupsModel.validName(nameField.getText()),
                mx,
                my);
            String status = pending >= 0 ? "正在原子校验全部节点…"
                : !notice.isEmpty() ? notice
                    : model.unassigned() > 0 ? "还有 " + model.unassigned() + " 个节点未分类，分配后才能保存"
                        : "已选 " + model.selected.size() + " / " + model.nodes.size() + " 个节点；所有节点均已分类";
            text(status, 224, 325, 225, stale || model.unassigned() > 0 ? 0xff9292 : 0xa9d6cf);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
        for (Control control : controls) nativeButton(
            control.id,
            control.x,
            control.y,
            control.width,
            control.height,
            control.label,
            control.enabled,
            control.id == 1 ? 0x80efaa : 0);
        renderGeneration++;
    }

    private void drawPreview(int mx, int my) {
        renderedNodeMarkers = new NBTTagList();
        drawRect(VX, VY, VX + VW, VY + VH, 0xff0a1922);
        int[] selected = model.selected.stream()
            .mapToInt(index -> model.nodes.get(index).cell)
            .filter(index -> index >= 0)
            .toArray();
        previewSelection.load(selected, -1, state.cells.size());
        HologramSelection previousSelection = HologramRenderer.selection;
        String previousStructure = HologramRenderer.selectedStructureId;
        boolean previousEmpty = HologramRenderer.showEmptyCells;
        int previousLayer = HologramRenderer.projectionLayer;
        int factor = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight)
            .getScaleFactor();
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            Math.round((originX + VX * scale) * factor),
            mc.displayHeight - Math.round((originY + (VY + VH) * scale) * factor),
            Math.round(VW * scale * factor),
            Math.round(VH * scale * factor));
        try {
            HologramRenderer.selection = previewSelection;
            HologramRenderer.selectedStructureId = "";
            HologramRenderer.showEmptyCells = false;
            HologramRenderer.projectionLayer = Integer.MAX_VALUE;
            hovered = HologramRenderer.preview(
                state,
                VX,
                VY,
                VW,
                VH,
                yaw,
                pitch,
                zoom,
                Integer.MAX_VALUE,
                0,
                previewSelection.first(),
                mx,
                my,
                -1);
            for (int i = 0; i < model.nodes.size(); i++) {
                HologramQuantumGroupsModel.Node node = model.nodes.get(i);
                if (node.cell < 0) continue;
                HologramState.Cell cell = state.cells.get(node.cell);
                double[] point = HologramRenderer.project(cell.dx, cell.dy, cell.dz);
                int x = (int) point[0], y = (int) point[1];
                int group = 0;
                while (group < model.groups.size() && !model.groups.get(group).id.equals(node.group)) group++;
                int color = node.group.isEmpty() ? 0xff9292 : COLORS[group % COLORS.length];
                if (model.selected.contains(i)) drawRect(x - 5, y - 5, x + 6, y + 6, 0xffffffff);
                drawRect(x - 3, y - 3, x + 4, y + 4, 0xff000000 | color);
                NBTTagCompound marker = new NBTTagCompound();
                marker.setInteger("node", i);
                marker.setInteger("cell", node.cell);
                marker.setString("group", node.group);
                marker.setDouble("projectedX", point[0]);
                marker.setDouble("projectedY", point[1]);
                marker.setInteger("drawX", x);
                marker.setInteger("drawY", y);
                marker.setBoolean("selected", model.selected.contains(i));
                marker.setBoolean("drawSubmitted", true);
                marker.setBoolean("centerInViewport", viewport(x, y));
                marker.setBoolean("clipIntersects", x + 4 > VX && x - 3 < VX + VW && y + 4 > VY && y - 3 < VY + VH);
                renderedNodeMarkers.appendTag(marker);
                if (node.cell == hovered) {
                    HologramQuantumGroupsModel.Group entry = model.find(node.group);
                    text(
                        (entry == null ? "未分类" : entry.name) + " [" + node.x + "," + node.y + "," + node.z + "]",
                        VX + 7,
                        VY + VH - 16,
                        VW - 14,
                        color);
                }
            }
        } finally {
            HologramRenderer.selection = previousSelection;
            HologramRenderer.selectedStructureId = previousStructure;
            HologramRenderer.showEmptyCells = previousEmpty;
            HologramRenderer.projectionLayer = previousLayer;
            GL11.glPopAttrib();
        }
        markerRenderGeneration = renderGeneration + 1;
    }

    private void text(String text, int x, int y, int width, int color) {
        boolean paper = x < VX || x >= VX + VW || y < VY || y >= VY + VH;
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(text, width),
            x,
            y,
            HologramEditorTheme.textColor(color, !paper));
    }

    private void button(int id, int x, int y, int w, int h, String label, boolean enabled, int mx, int my) {
        Control control = new Control(id, x, y, w, h, enabled);
        controls.add(control);
        control.label = label;
    }

    private boolean editable() {
        return !stale && pending < 0;
    }

    private void loadName() {
        HologramQuantumGroupsModel.Group group = model.find(model.active);
        if (nameField != null) nameField.setText(group == null ? "" : group.name);
    }

    private int localX(int x) {
        return (int) ((x - originX) / scale);
    }

    private int localY(int y) {
        return (int) ((y - originY) / scale);
    }

    private boolean viewport(int x, int y) {
        return x >= VX && x < VX + VW && y >= VY && y < VY + VH;
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        click(x, y, button, isCtrlKeyDown());
    }

    private void click(int x, int y, int button, boolean controlHeld) {
        int mx = localX(x), my = localY(y);
        nameField.mouseClicked(mx, my, button);
        if (button == 0) for (Control control : controls) if (control.contains(mx, my)) {
            if (control.enabled) activate(control.id);
            return;
        }
        if (!viewport(mx, my)) return;
        if (button == 1) {
            dragging = true;
            dragX = mx;
            dragY = my;
        } else if (button == 0 && editable()) {
            int picked = HologramRenderer.pick(mx, my);
            for (int i = 0; i < model.nodes.size(); i++) if (model.nodes.get(i).cell == picked) {
                model.click(i, controlHeld);
                break;
            }
        }
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (!dragging || button != 1) return;
        int mx = localX(x), my = localY(y);
        yaw += (mx - dragX) * .012;
        pitch = Math.max(-1.4, Math.min(1.4, pitch + (my - dragY) * .012));
        dragX = mx;
        dragY = my;
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        if (button == 1) dragging = false;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        sceneScroll();
    }

    @Override
    protected void sceneScroll() {
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mx = localX(Mouse.getEventX() * width / mc.displayWidth);
        int my = localY(height - Mouse.getEventY() * height / mc.displayHeight - 1);
        if (viewport(mx, my)) zoom = Math.max(.25, Math.min(4, zoom * (wheel > 0 ? 1.1 : 1 / 1.1)));
        else if (mx >= 460 && my >= 85 && my < 202) offset += wheel > 0 ? -1 : 1;
    }

    @Override
    protected void activateNative(int id) {
        activate(id);
    }

    @Override
    protected void onMuiActionFailure(RuntimeException failure) {
        notice = failure.getMessage() == null ? "返回工作区失败，请重试" : failure.getMessage();
    }

    private void activate(int id) {
        if (id == 2 && pending < 0) {
            deferAction(() -> mc.displayGuiScreen(editor));
            return;
        }
        if (id == 11) {
            yaw = Math.PI / 4;
            pitch = Math.PI / 6;
            zoom = 1;
            return;
        }
        if (!editable()) return;
        if (id == 1 && model.complete() && HologramQuantumGroupsModel.validName(nameField.getText())) {
            model.rename(nameField.getText());
            long result = editor.saveQuantumGroups(model.export(), model.revision);
            if (editor.ui.session.isLocalAuthor()) {
                if (result >= 0) deferAction(() -> mc.displayGuiScreen(editor));
                else notice = editor.ui.session.notice();
            } else pending = result;
        } else if (id == 3 || id == 4) model.select(id == 4);
        else if (id == 5) {
            if (model.create(nameField.getText())) loadName();
        } else if (id == 6 || id == 7) model.assign(id == 7);
        else if (id == 8 || id == 9) {
            model.undo(id == 9);
            loadName();
        } else if (id == 10) {
            model.rename(nameField.getText());
            loadName();
        } else if (id >= 1000 && id < 1000 + model.groups.size()) {
            model.activate(model.groups.get(id - 1000).id);
            loadName();
        }
    }

    @Override
    protected void keyTyped(char character, int key) {
        key(character, key, isCtrlKeyDown());
    }

    @Override
    protected boolean muiKeyBefore(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE && key != Keyboard.KEY_RETURN
            && key != Keyboard.KEY_NUMPADENTER
            && !(isCtrlKeyDown() && (key == Keyboard.KEY_Z || key == Keyboard.KEY_Y))) return false;
        key(character, key, isCtrlKeyDown());
        return true;
    }

    @Override
    protected boolean muiKey(char character, int key) {
        key(character, key, isCtrlKeyDown());
        return true;
    }

    private void key(char character, int key, boolean control) {
        if (key == Keyboard.KEY_ESCAPE) {
            activate(2);
            return;
        }
        if (control && (key == Keyboard.KEY_Z || key == Keyboard.KEY_Y)) {
            activate(key == Keyboard.KEY_Z ? 8 : 9);
            return;
        }
        if (nameField.isFocused()) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activate(10);
            else if (editable()) nameField.textboxKeyTyped(character, key);
            return;
        }
        if (key == Keyboard.KEY_T) activate(control ? 4 : 3);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void onMuiClose() {
        Keyboard.enableRepeatEvents(false);
    }

    private static void requireSmoke() {
        if (!Boolean.getBoolean("gtmb.hologram.realInteractionSmoke")
            && !Boolean.getBoolean("gtmb.hologram.sulfurLineClient"))
            throw new IllegalStateException("Real interaction smoke is disabled");
    }

    /** All point helpers return actual scaled GuiScreen input coordinates, after a real render. */
    int[] smokeControlPoint(int id) {
        requireSmoke();
        for (Control control : controls)
            if (control.id == id) return point(control.x + control.width / 2, control.y + control.height / 2);
        throw new IllegalArgumentException("No drawn quantum group control " + id);
    }

    private int[] point(int x, int y) {
        return new int[] { originX + Math.round(x * scale), originY + Math.round(y * scale) };
    }

    int[] smokeNodePoint(int index) {
        requireSmoke();
        int cell = model.nodes.get(index).cell;
        for (int y = VY + 1; y < VY + VH - 1; y++)
            for (int x = VX + 1; x < VX + VW - 1; x++) if (HologramRenderer.pick(x, y) == cell) return point(x, y);
        throw new IllegalStateException("No actual visible quantum node hit region " + index);
    }

    void smokeClickControl(int id) {
        int[] point = smokeControlPoint(id);
        mouseClicked(point[0], point[1], 0);
    }

    void smokeClickNode(int index, boolean control) {
        int[] point = smokeNodePoint(index);
        click(point[0], point[1], 0, control);
    }

    void smokeKey(int key, boolean control) {
        requireSmoke();
        nameField.setFocused(false);
        key('\0', key, control);
    }

    int[] smokeNamePoint() {
        requireSmoke();
        return point(550, 229);
    }

    void smokeName(String value) {
        requireSmoke();
        int[] point = smokeNamePoint();
        mouseClicked(point[0], point[1], 0);
        nameField.setCursorPositionEnd();
        nameField.setSelectionPos(0);
        for (int i = 0; i < value.length(); i++) keyTyped(value.charAt(i), 0);
    }

    NBTTagCompound smokeUiState() {
        requireSmoke();
        NBTTagCompound result = new NBTTagCompound();
        result.setString("session", model.session);
        result.setLong("planRevision", model.revision);
        result.setLong("renderGeneration", renderGeneration);
        result.setLong("markerRenderGeneration", markerRenderGeneration);
        result.setIntArray("nodeMarkerViewport", new int[] { VX, VY, VW, VH });
        result.setTag("renderedNodeMarkers", renderedNodeMarkers.copy());
        result.setLong("pendingSequence", pending);
        result.setBoolean("stale", stale);
        result.setBoolean(
            "saveEnabled",
            editable() && model.complete() && HologramQuantumGroupsModel.validName(nameField.getText()));
        result.setBoolean("canUndo", model.canUndo());
        result.setBoolean("canRedo", model.canRedo());
        result.setInteger("unassigned", model.unassigned());
        result.setInteger("nodeCount", model.nodes.size());
        result.setInteger("groupCount", model.groups.size());
        result.setIntArray(
            "selection",
            model.selected.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        result.setString("activeGroup", model.active);
        result.setString("nameField", nameField.getText());
        result.setTag("groups", model.export());
        NBTTagList nodes = new NBTTagList();
        for (HologramQuantumGroupsModel.Node node : model.nodes) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", node.x);
            row.setInteger("y", node.y);
            row.setInteger("z", node.z);
            row.setInteger("cell", node.cell);
            row.setString("group", node.group);
            nodes.appendTag(row);
        }
        result.setTag("nodes", nodes);
        return result;
    }
}
