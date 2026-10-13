package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtmb.hologram.HologramNetwork;

import codechicken.nei.guihook.GuiContainerManager;

/** Independent blueprint marker UI. All changes are SET/CLEAR requests; no vanilla inventory input executes. */
public final class HologramAeContainerScreen extends GuiContainer {

    private final HologramScreen editor;
    private final RenderItem icons = new RenderItem();
    private HologramAePreviewModel model;
    private GuiContainerManager nei;
    private final Map<String, List<Integer>> groups = new LinkedHashMap<>();
    private final List<String> groupNames = new ArrayList<>();
    private final ArrayDeque<String> clearing = new ArrayDeque<>();
    private int group, slotPage, selected = -1, backpackPage, issueOffset;
    private long editSequence, pending = -1;
    private long clearAfter;
    private String pendingSlot = "", notice = "";
    private ItemStack candidate, dragging;
    private boolean explicitReturn;
    private boolean compactHeight;

    HologramAeContainerScreen(HologramScreen editor, NBTTagCompound snapshot) {
        super(new HologramAeGhostContainer(new HologramAePreviewModel(snapshot).slots));
        this.editor = editor;
        model = new HologramAePreviewModel(snapshot);
        xSize = 256;
        ySize = 238;
        regroup();
    }

    @Override
    public void initGui() {
        // GuiContainer.initGui binds player.openContainer. Never call it for a virtual preview.
        compactHeight = height < 262;
        ySize = compactHeight ? 212 : 238;
        guiLeft = (width - xSize) / 2;
        guiTop = (height - 24 - ySize) / 2;
        buttonList.clear();
        Keyboard.enableRepeatEvents(true);
        HologramAeNeiIntegration.register();
        // NEI's public setWorldAndResolution transformer creates its manager after this initGui returns.
        nei = null;
        rebuildTabs();
        int footerY = guiTop + (compactHeight ? 186 : 212);
        buttonList.add(new GuiButton(1, guiLeft + 184, footerY, 64, 20, "完成"));
        buttonList.add(new GuiButton(2, guiLeft + 8, footerY, 74, 20, "清空此页"));
        buttonList.add(new GuiButton(3, guiLeft + 88, footerY, 90, 20, "背包样本 1/4"));
        buttonList.add(new GuiButton(4, guiLeft + 232, guiTop + 62, 16, 18, "↑"));
        buttonList.add(new GuiButton(5, guiLeft + 232, guiTop + 84, 16, 18, "↓"));
        buttonList.add(new GuiButton(6, guiLeft + 232, guiTop + 106, 16, 14, "+"));
        buttonList.add(new GuiButton(7, guiLeft + 232, guiTop + 122, 16, 14, "−"));
        positionSlots();
    }

    private void regroup() {
        String activeGroup = group < groupNames.size() ? groupNames.get(group) : "";
        groups.clear();
        for (int i = 0; i < model.slots.size(); i++) {
            HologramAePreviewModel.MarkSlot slot = model.slots.get(i);
            String id = slot.id;
            String name = !slot.issueCode.isEmpty() ? "确认丢弃"
                : id.contains("patterns") ? "样板"
                    : id.contains("upgrades") ? "升级卡"
                        : id.contains("config") ? "配置样本" : id.contains("storage") ? "缓存物品" : "容器槽";
            groups.computeIfAbsent(name, ignored -> new ArrayList<>())
                .add(i);
        }
        groupNames.clear();
        groupNames.addAll(groups.keySet());
        group = groupNames.contains(activeGroup) ? groupNames.indexOf(activeGroup)
            : Math.min(group, Math.max(0, groupNames.size() - 1));
        slotPage = Math.min(slotPage, Math.max(0, (groupSlots().size() - 1) / 36));
    }

    private void rebuildTabs() {
        buttonList.removeIf(button -> ((GuiButton) button).id >= 100);
        int tabWidth = Math.min(60, (xSize - 16) / Math.max(1, groupNames.size()));
        for (int i = 0; i < groupNames.size(); i++) buttonList
            .add(new GuiButton(100 + i, guiLeft + 8 + i * tabWidth, guiTop + 36, tabWidth - 2, 20, groupNames.get(i)));
    }

    private List<Integer> groupSlots() {
        return groupNames.isEmpty() ? java.util.Collections.emptyList() : groups.get(groupNames.get(group));
    }

    private List<Integer> visibleSlots() {
        List<Integer> all = groupSlots();
        int start = Math.min(slotPage * 36, all.size());
        return all.subList(start, Math.min(start + 36, all.size()));
    }

    private int columns() {
        return visibleSlots().size() == 10 ? 5 : 9;
    }

    private void positionSlots() {
        for (Object entry : inventorySlots.inventorySlots) {
            Slot slot = (Slot) entry;
            slot.xDisplayPosition = -10000;
            slot.yDisplayPosition = -10000;
        }
        List<Integer> visible = visibleSlots();
        for (int i = 0; i < visible.size(); i++) {
            Slot slot = inventorySlots.getSlot(visible.get(i));
            slot.xDisplayPosition = 12 + i % columns() * 24;
            slot.yDisplayPosition = 62 + i / columns() * 18;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Draw manually: vanilla draw/input state and NEI's inventory mutation handlers never run.
        drawDefaultBackground();
        if (nei == null) nei = GuiContainerManager.getManager(this);
        if (nei != null) nei.preDraw();
        drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
        for (Object object : buttonList) ((GuiButton) object).drawButton(mc, mouseX, mouseY);
        if (nei != null) {
            // NEI removes the container translation internally; provide the same matrix as vanilla rendering.
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(guiLeft, guiTop, 0);
                nei.renderObjects(mouseX, mouseY);
            } finally {
                GL11.glPopMatrix();
            }
        }
        int hit = slotAt(mouseX, mouseY);
        if (hit >= 0) {
            HologramAePreviewModel.MarkSlot slot = model.slots.get(hit);
            List<String> tooltip = new ArrayList<>();
            ItemStack stack = slot.stack();
            if (stack != null) tooltip.add(stack.getDisplayName());
            tooltip.add(slot.label);
            tooltip.add(slot.role());
            tooltip.add("允许：" + slot.allowed + " · 上限 " + slot.limit);
            if (!slot.issueCode.isEmpty()) {
                tooltip.add("问题：" + slot.issueCode);
                tooltip.addAll(fontRendererObj.listFormattedStringToWidth("完整路径：" + slot.issuePath, 240));
                tooltip.add("右键 / Shift / Delete 确认丢弃此问题对应的蓝图数据");
                tooltip.add("原世界与源物品保持不变");
            } else tooltip.add(slot.enabled ? "左键标记；右键 / Shift / Delete 清除" : "仅支持右键 / Shift / Delete 清除蓝图标记");
            if (pendingSlot.equals(slot.id)) tooltip.add("等待服务端确认，显示仍为已确认标记");
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        } else if (inside(mouseX, mouseY, 0, issueY(), xSize, compactHeight ? 12 : 25) && !model.issues.isEmpty()) {
            List<String> warning = fontRendererObj.listFormattedStringToWidth(model.issues.get(issueOffset), 240);
            drawHoveringText(warning, mouseX, mouseY, fontRendererObj);
        } else if (nei != null) nei.renderToolTips(mouseX, mouseY);
        if (dragging != null) drawIcon(dragging, mouseX + 6, mouseY + 6);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xf01b2630);
        text("AE 蓝图容器 · 仅标记", 8, 9, 0x80dcee);
        text(model.block + " [" + model.x + ", " + model.y + ", " + model.z + "]", 8, 23, 0xa9bcc5);
        for (int index : visibleSlots()) {
            HologramAePreviewModel.MarkSlot descriptor = model.slots.get(index);
            Slot slot = inventorySlots.getSlot(index);
            int x = guiLeft + slot.xDisplayPosition, y = guiTop + slot.yDisplayPosition;
            int color = !descriptor.enabled ? 0xff46515a : "ghost".equals(descriptor.kind) ? 0xff38606e : 0xff635538;
            if (descriptor.id.equals(pendingSlot)) color = 0xffc18d40;
            if (!descriptor.issueCode.isEmpty()) color = 0xffbd7440;
            if (index == selected) color = 0xff66bace;
            drawRect(x - 1, y - 1, x + 17, y + 17, color);
            drawRect(x, y, x + 16, y + 16, 0xff10191e);
            drawIcon(descriptor.stack(), x, y);
            if (!descriptor.issueCode.isEmpty()) fontRendererObj.drawString("!", x + 5, y + 4, 0xffc976);
        }
        if (compactHeight) text("样本不消耗 · 元件内容不保留 · 警告 " + model.issues.size(), 8, 140, 0xf2c976);
        else {
            text("样本不消耗；安装物品需从材料源支付", 8, 140, 0xa9bcc5);
            text("元件内容不保留 · 警告 " + model.issues.size() + " 条（滚轮）", 8, 152, 0xf2c976);
            if (!model.issues.isEmpty()) text(trim(model.issues.get(issueOffset), 240), 8, 164, 0xf2c976);
        }
        text(
            pending >= 0 ? "等待服务端确认…" : trim(notice.isEmpty() ? "NEI 拖入或选择背包样本，再点击槽位" : notice, 240),
            8,
            compactHeight ? 152 : 176,
            pending >= 0 ? 0xf2c976 : 0xa9bcc5);
        for (int i = 0; i < 9; i++) {
            int x = guiLeft + 12 + i * 24, y = guiTop + backpackY();
            drawRect(x - 1, y - 1, x + 17, y + 17, 0xff35454f);
            drawIcon(backpackSample(i), x, y);
        }
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            if (button.id == 2) button.enabled = pending < 0 && clearing.isEmpty();
            if (button.id == 3) button.displayString = "背包样本 " + (backpackPage + 1) + "/4";
            if (button.id == 4 || button.id == 5) {
                button.visible = groupSlots().size() > 36;
                button.enabled = button.id == 4 ? slotPage > 0 : (slotPage + 1) * 36 < groupSlots().size();
            }
            if (button.id == 6 || button.id == 7) {
                HologramAePreviewModel.MarkSlot current = selected < 0 ? null : model.slots.get(selected);
                ItemStack marked = current == null ? null : current.stack();
                button.enabled = pending < 0 && current != null
                    && current.enabled
                    && marked != null
                    && (button.id == 7 || marked.stackSize < Math.min(current.limit, marked.getMaxStackSize()));
            }
            if (button.id >= 100) {
                button.displayString = groupNames.get(button.id - 100);
                if (button.id - 100 == group && groupSlots().size() > 36)
                    button.displayString += " " + (slotPage + 1) + "/" + ((groupSlots().size() + 35) / 36);
            }
        }
    }

    private void drawIcon(ItemStack stack, int x, int y) {
        if (stack == null) return;
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glColor4f(1, 1, 1, 1);
        icons.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack.copy(), x, y);
        icons.renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(), stack.copy(), x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    private void text(String value, int x, int y, int color) {
        fontRendererObj.drawString(value, guiLeft + x, guiTop + y, color);
    }

    private String trim(String value, int width) {
        return fontRendererObj.trimStringToWidth(value, width);
    }

    private ItemStack backpackSample(int column) {
        if (mc.thePlayer == null) return null;
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(backpackPage * 9 + column);
        return stack == null ? null : stack.copy();
    }

    private int slotAt(int x, int y) {
        for (int index : visibleSlots()) {
            Slot slot = inventorySlots.getSlot(index);
            if (inside(x, y, slot.xDisplayPosition - 1, slot.yDisplayPosition - 1, 18, 18)) return index;
        }
        return -1;
    }

    private boolean inside(int x, int y, int left, int top, int w, int h) {
        return x >= guiLeft + left && x < guiLeft + left + w && y >= guiTop + top && y < guiTop + top + h;
    }

    private int backpackY() {
        return compactHeight ? 164 : 190;
    }

    private int issueY() {
        return compactHeight ? 139 : 151;
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        for (Object object : buttonList) {
            GuiButton control = (GuiButton) object;
            if (button == 0 && control.mousePressed(mc, x, y)) {
                actionPerformed(control);
                return;
            }
        }
        int slot = slotAt(x, y);
        if (slot >= 0) {
            selected = slot;
            if (button == 1 || isShiftKeyDown()) edit(slot, null);
            else if (button == 0) {
                ItemStack held = candidate;
                if (held == null && mc.thePlayer != null) held = mc.thePlayer.inventory.getItemStack();
                if (held == null && mc.thePlayer != null) held = mc.thePlayer.getHeldItem();
                if (held == null) notice = "先从 NEI 或下方背包样本选择物品";
                else edit(slot, held.copy());
            }
            return;
        }
        for (int i = 0; i < 9; i++) if (inside(x, y, 11 + i * 24, backpackY() - 1, 18, 18)) {
            candidate = backpackSample(i);
            dragging = candidate == null ? null : candidate.copy();
            notice = candidate == null ? "此背包样本为空" : "已选择样本：" + candidate.getDisplayName();
            return;
        }
        ItemStack sample = HologramAeNeiIntegration.sampleAt(x, y);
        if (sample != null && button == 0) {
            candidate = sample.copy();
            dragging = sample.copy();
            notice = "拖入槽位标记：" + sample.getDisplayName();
            return;
        }
        HologramAeNeiIntegration.safeClick(x, y, button);
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        if (button < 0) return;
        ItemStack drop = dragging;
        dragging = null;
        if (drop != null && button == 0) acceptDrop(x, y, drop, button);
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {}

    @Override
    protected void handleMouseClick(Slot slot, int id, int button, int mode) {}

    @Override
    protected boolean checkHotbarKeys(int key) {
        return false;
    }

    /** Always consume drops on this virtual screen, including invalid/outside drops. */
    boolean acceptDrop(int x, int y, ItemStack stack, int button) {
        int slot = slotAt(x, y);
        if (slot < 0) {
            notice = "请拖入容器标记槽";
            return true;
        }
        selected = slot;
        if (button == 1 || isShiftKeyDown()) edit(slot, null);
        else if (stack != null) edit(slot, stack.copy());
        return true;
    }

    private void edit(int index, ItemStack source) {
        if (pending >= 0) {
            notice = "等待上一条标记请求确认";
            return;
        }
        HologramAePreviewModel.MarkSlot slot = model.slots.get(index);
        if (!slot.enabled && source != null) {
            notice = "此槽当前禁用：" + slot.allowed;
            return;
        }
        ItemStack bounded = source == null ? null : slot.candidate(source);
        if (source != null && bounded == null) {
            notice = "无效物品；允许：" + slot.allowed;
            return;
        }
        NBTTagCompound request = model.action("aePreviewEdit");
        request.setString("slotId", slot.id);
        request.setString("mutation", bounded == null ? "CLEAR" : "SET");
        pending = ++editSequence;
        pendingSlot = slot.id;
        request.setLong("editSeq", pending);
        if (bounded != null) request.setTag("stack", bounded.writeToNBT(new NBTTagCompound()));
        HologramNetwork.sendAction(request);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= 100) {
            group = button.id - 100;
            slotPage = 0;
            selected = -1;
            positionSlots();
        } else if (button.id == 1) returnToEditor();
        else if (button.id == 3) backpackPage = (backpackPage + 1) % 4;
        else if (button.id == 4 || button.id == 5) {
            int lastPage = Math.max(0, (groupSlots().size() - 1) / 36);
            slotPage = Math.max(0, Math.min(lastPage, slotPage + (button.id == 4 ? -1 : 1)));
            selected = -1;
            positionSlots();
        } else if ((button.id == 6 || button.id == 7) && selected >= 0) {
            ItemStack marked = model.slots.get(selected)
                .stack();
            if (marked != null) {
                marked.stackSize += button.id == 6 ? 1 : -1;
                edit(selected, marked.stackSize <= 0 ? null : marked);
            }
        } else if (button.id == 2 && pending < 0) {
            for (int index : visibleSlots()) if (model.slots.get(index)
                .stack() != null) clearing.add(model.slots.get(index).id);
            clearNext();
        }
    }

    private void clearNext() {
        while (pending < 0 && !clearing.isEmpty()) {
            String id = clearing.removeFirst();
            for (int i = 0; i < model.slots.size(); i++) if (id.equals(model.slots.get(i).id)) {
                edit(i, null);
                break;
            }
        }
    }

    void updateState(HologramState next) {
        String selectedId = selected >= 0 && selected < model.slots.size() ? model.slots.get(selected).id : "";
        editor.updateState(next);
        NBTTagCompound preview = next.data.getCompoundTag("aePreview");
        if (!model.session.equals(next.session) || !model.token.equals(preview.getString("token"))) {
            clearing.clear();
            notice = "预览会话已失效";
            mc.displayGuiScreen(editor);
            return;
        }
        HologramAePreviewModel canonical = new HologramAePreviewModel(preview);
        NBTTagCompound ack = next.data.getCompoundTag("aeEditAck");
        if (pending >= 0 && ack.getLong("editSeq") == pending && ack.hasKey("accepted")) {
            boolean accepted = ack.getBoolean("accepted");
            pending = -1;
            pendingSlot = "";
            notice = accepted ? "标记已确认" : "标记被拒绝：" + ack.getString("code");
            if (!accepted && !next.data.getString("status")
                .isEmpty()) notice += " · " + next.data.getString("status");
            if (!accepted) clearing.clear();
            clearAfter = System.nanoTime() + 100_000_000L;
        }
        model = canonical;
        selected = -1;
        for (int i = 0; i < model.slots.size(); i++) if (model.slots.get(i).id.equals(selectedId)) {
            selected = i;
            break;
        }
        inventorySlots = new HologramAeGhostContainer(model.slots);
        regroup();
        rebuildTabs();
        positionSlots();
        issueOffset = Math.min(issueOffset, Math.max(0, model.issues.size() - 1));
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            returnToEditor();
            return;
        }
        if (HologramAeNeiIntegration.safeKey(character, key)) return;
        if (key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            returnToEditor();
            return;
        }
        if ((key == Keyboard.KEY_DELETE || key == Keyboard.KEY_BACK) && selected >= 0) edit(selected, null);
    }

    @Override
    public void handleMouseInput() {
        // GuiScreen's dispatch is copied here; GuiContainer/NEI injected mutation routing is excluded.
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        int button = Mouse.getEventButton();
        if (Mouse.getEventButtonState()) mouseClicked(x, y, button);
        else if (button >= 0) mouseMovedOrUp(x, y, button);
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            if (inside(x, y, 0, issueY(), xSize, compactHeight ? 12 : 25) && !model.issues.isEmpty())
                issueOffset = Math.max(0, Math.min(model.issues.size() - 1, issueOffset + (wheel < 0 ? 1 : -1)));
            else HologramAeNeiIntegration.safeScroll(x, y, wheel);
        }
    }

    @Override
    public void handleKeyboardInput() {
        if (Keyboard.getEventKeyState()) keyTyped(Keyboard.getEventCharacter(), Keyboard.getEventKey());
    }

    @Override
    public void updateScreen() {
        // NEI 2.8.155 transformSubclasses inserts an unconditional super call when its bytecode is absent.
        // Retain that bytecode behind a permanently false function so NEI recognizes it without executing it.
        if (vanillaTickEnabled()) super.updateScreen();
        if (nei != null) nei.updateScreen();
        if (pending < 0 && !clearing.isEmpty() && System.nanoTime() >= clearAfter) clearNext();
    }

    private static boolean vanillaTickEnabled() {
        return false;
    }

    private void returnToEditor() {
        if (explicitReturn) return;
        explicitReturn = true;
        clearing.clear();
        HologramNetwork.sendAction(model.action("aePreviewClose"));
        mc.displayGuiScreen(editor);
    }

    @Override
    public void onGuiClosed() {
        dragging = null;
        Keyboard.enableRepeatEvents(false);
        HologramAeNeiIntegration.releaseSearchFocus();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Read-only coordinates for isolated/live smoke drivers; navigation still uses the real mouse routes. */
    int[] smokeSlotPoint(String slotId) {
        for (int index : visibleSlots()) if (model.slots.get(index).id.equals(slotId)) {
            Slot slot = inventorySlots.getSlot(index);
            return new int[] { guiLeft + slot.xDisplayPosition + 8, guiTop + slot.yDisplayPosition + 8 };
        }
        return null;
    }

    int[] smokeControlPoint(int id) {
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            if (button.id == id)
                return new int[] { button.xPosition + button.width / 2, button.yPosition + button.height / 2 };
        }
        return null;
    }

    boolean smokeControlEnabled(int id) {
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            if (button.id == id) return button.visible && button.enabled;
        }
        return false;
    }

    int[] smokeBackpackPoint(int column) {
        if (column < 0 || column >= 9) return null;
        return new int[] { guiLeft + 20 + column * 24, guiTop + backpackY() + 8 };
    }

    List<String> smokeGroups() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(groupNames));
    }

    HologramAePreviewModel smokeModel() {
        return model;
    }

    long smokePendingSequence() {
        return pending;
    }

    int smokeSlotPage() {
        return slotPage;
    }

    int smokeBackpackPage() {
        return backpackPage;
    }

    /** No interception/capture: invokes the exact input methods that dispatch real GTMB requests. */
    void smokeMouseButton(int x, int y, int button, boolean pressed) {
        if (pressed) mouseClicked(x, y, button);
        else mouseMovedOrUp(x, y, button);
    }
}
