package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerGhostIngredientSlot;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.RecipeViewerSettingsImpl;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.value.ObjectValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.miaokatze.gtmb.hologram.HologramNetwork;

/** Native client MUI2 marker editor. Display widgets never register real inventory slots. */
public final class HologramAeContainerScreen extends ModularScreen {

    private static HologramAeContainerScreen active;
    private final HologramScreen editor;
    private final Minecraft mc = Minecraft.getMinecraft();
    private Container playerContainer;
    private HologramAePreviewModel model;
    private final Map<String, List<Integer>> groups = new LinkedHashMap<>();
    private final List<String> groupNames = new ArrayList<>();
    private final ArrayDeque<String> clearing = new ArrayDeque<>();
    private final List<MarkerWidget> markers = new ArrayList<>();
    private final List<ItemDisplayWidget> samples = new ArrayList<>();
    private final Map<Integer, ButtonWidget<?>> controls = new LinkedHashMap<>();
    private int group, slotPage, selected = -1, backpackPage, issueOffset;
    private long editSequence, pending = -1, clearAfter;
    private String pendingSlot = "", notice = "";
    private ItemStack candidate, dragging;
    private boolean explicitReturn;
    private boolean compactHeight;
    private TextWidget noticeLabel;
    // Public dimensions preserve the live input probe's view of the actual wrapper.
    int width, height;

    HologramAeContainerScreen(HologramScreen editor, NBTTagCompound snapshot) {
        super("gtmb", HologramMuiTheme.panel("ae_markers", 256, 238));
        this.editor = editor;
        model = new HologramAePreviewModel(snapshot);
        regroup();
        buildPanel();
    }

    static HologramAeContainerScreen from(GuiScreen screen) {
        if (!(screen instanceof IMuiScreen)) return null;
        ModularScreen modular = ((IMuiScreen) screen).getScreen();
        return modular instanceof HologramAeContainerScreen ? (HologramAeContainerScreen) modular : null;
    }

    static HologramAeContainerScreen activeFor(HologramScreen editor) {
        HologramAeContainerScreen screen = active();
        return screen != null && screen.editor == editor ? screen : null;
    }

    static HologramAeContainerScreen active() {
        if (active != null && active.getPanelManager()
            .isDisposed()) active = null;
        return active != null && !active.explicitReturn ? active : null;
    }

    static HologramAeContainerScreen activeForSession(String session) {
        HologramAeContainerScreen screen = active();
        return screen != null && screen.model.session.equals(session) ? screen : null;
    }

    void open() {
        active = this;
        playerContainer = mc.thePlayer == null ? null : mc.thePlayer.openContainer;
        HologramAeNeiIntegration.register();
        RecipeViewerSettingsImpl viewer = new RecipeViewerSettingsImpl();
        viewer.enable();
        // GuiManager owns constructClientOnly and creates GuiContainerWrapper. No custom Container bridge.
        ClientGUI.open(this, viewer, HologramAeGhostContainer::new);
    }

    @Override
    public void onOpen() {
        restorePlayerContainer();
    }

    private void restorePlayerContainer() {
        // Vanilla GuiContainer.initGui binds its container before MUI2's InitGui.Post/onOpen.
        // This editor owns no real slots, so keep the player's authoritative inventory window.
        if (mc.thePlayer != null && playerContainer != null && mc.thePlayer.openContainer == getContainer())
            mc.thePlayer.openContainer = playerContainer;
    }

    private void buildPanel() {
        ModularPanel panel = getMainPanel();
        panel.child(
            HologramMuiTheme.label(
                () -> "AE 蓝图容器 · 仅标记 · " + (slotPage + 1) + "/" + Math.max(1, (groupSlots().size() + 35) / 36),
                8,
                7,
                240,
                12,
                0xfff0ddb3));
        panel.child(
            HologramMuiTheme.label(
                () -> model.block + " [" + model.x + ", " + model.y + ", " + model.z + "]",
                8,
                21,
                240,
                12,
                0xffd1bea0));
        for (int i = 0; i < 6; i++) {
            final int tab = i;
            control(
                100 + i,
                () -> tab < groupNames.size() ? groupNames.get(tab) : "",
                8 + i * 40,
                36,
                39,
                20,
                () -> tab < groupNames.size());
        }
        for (int i = 0; i < 36; i++) {
            MarkerWidget marker = new MarkerWidget(i);
            markers.add(marker);
            panel.child(marker);
        }
        control(4, () -> "↑", 232, 62, 16, 18, () -> slotPage > 0);
        control(5, () -> "↓", 232, 84, 16, 18, () -> (slotPage + 1) * 36 < groupSlots().size());
        control(6, () -> "+", 232, 106, 16, 14, () -> amountEnabled(true));
        control(7, () -> "−", 232, 122, 16, 14, () -> amountEnabled(false));
        panel.child(
            HologramMuiTheme.label(
                () -> compactHeight ? "样本不消耗 · 元件内容不保留 · 警告 " + model.issues.size() : "样本不消耗；安装物品需从材料源支付",
                8,
                140,
                240,
                12,
                0xffd1bea0));
        panel.child(
            HologramMuiTheme.label(() -> "元件内容不保留 · 警告 " + model.issues.size() + " 条（滚轮）", 8, 152, 240, 12, 0xfff2c976)
                .setEnabledIf(w -> !compactHeight));
        panel.child(
            HologramMuiTheme
                .label(() -> model.issues.isEmpty() ? "" : model.issues.get(issueOffset), 8, 164, 240, 12, 0xfff2c976)
                .setEnabledIf(w -> !compactHeight)
                .tooltipDynamic(t -> { if (!model.issues.isEmpty()) t.addLine(model.issues.get(issueOffset)); })
                .tooltipAutoUpdate(true));
        noticeLabel = HologramMuiTheme.label(
            () -> pending >= 0 ? "等待服务端确认…" : notice.isEmpty() ? "NEI 拖入或选择背包样本，再点击槽位" : notice,
            8,
            176,
            240,
            12,
            0xffd1bea0);
        panel.child(noticeLabel);
        for (int i = 0; i < 9; i++) {
            final int column = i;
            ItemDisplayWidget sample = new SampleWidget(column);
            sample.item(new ObjectValue.Dynamic<>(ItemStack.class, () -> backpackSample(column), ignored -> {}))
                .displayAmount(true)
                .background(HologramMuiTheme.PANEL)
                .pos(11 + i * 24, 189)
                .size(18);
            samples.add(sample);
            panel.child(sample);
        }
        control(1, () -> "完成", 184, 212, 64, 20, () -> true);
        control(2, () -> "清空此页", 8, 212, 74, 20, () -> pending < 0 && clearing.isEmpty());
        control(3, () -> "背包样本 " + (backpackPage + 1) + "/4", 88, 212, 90, 20, () -> true);
        refreshSlots();
    }

    private void control(int id, Supplier<String> text, int x, int y, int w, int h, BooleanSupplier enabled) {
        ButtonWidget<?> button = HologramMuiTheme
            .button(text, x, y, w, h, () -> { if (enabled.getAsBoolean()) action(id); })
            .setEnabledIf(widget -> enabled.getAsBoolean());
        controls.put(id, button);
        getMainPanel().child(button);
    }

    private boolean amountEnabled(boolean increase) {
        if (pending >= 0 || selected < 0 || selected >= model.slots.size()) return false;
        HologramAePreviewModel.MarkSlot slot = model.slots.get(selected);
        ItemStack stack = slot.stack();
        return slot.enabled && stack != null
            && (!increase || stack.stackSize < Math.min(slot.limit, stack.getMaxStackSize()));
    }

    @Override
    public void onResize(int width, int height) {
        this.width = width;
        this.height = height;
        compactHeight = height < 262;
        getMainPanel().size(256, compactHeight ? 212 : 238);
        noticeLabel.top(compactHeight ? 152 : 176);
        for (int id = 1; id <= 3; id++) controls.get(id)
            .top(compactHeight ? 186 : 212);
        for (ItemDisplayWidget sample : samples) sample.top(backpackY() - 1);
        super.onResize(width, height);
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

    private void refreshSlots() {
        for (int i = 0; i < markers.size(); i++) {
            MarkerWidget marker = markers.get(i);
            marker.pos(11 + i % columns() * 24, 61 + i / columns() * 18);
            marker.setEnabled(i < visibleSlots().size());
            marker.markTooltipDirty();
        }
    }

    private int visibleIndex(int cell) {
        List<Integer> visible = visibleSlots();
        return cell >= visible.size() ? -1 : visible.get(cell);
    }

    private ItemStack backpackSample(int column) {
        if (mc.thePlayer == null) return null;
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(backpackPage * 9 + column);
        return stack == null ? null : stack.copy();
    }

    private int backpackY() {
        return compactHeight ? 164 : 190;
    }

    private int issueY() {
        return compactHeight ? 139 : 151;
    }

    private int slotAt(int x, int y) {
        for (MarkerWidget marker : markers) if (marker.isEnabled() && marker.getArea()
            .contains(x, y)) return visibleIndex(marker.cell);
        return -1;
    }

    private void selectSlot(int index, int button) {
        if (index < 0) return;
        selected = index;
        if (button == 1 || GuiScreen.isShiftKeyDown()) edit(index, null);
        else if (button == 0) {
            ItemStack held = candidate;
            if (held == null && mc.thePlayer != null) held = mc.thePlayer.inventory.getItemStack();
            if (held == null && mc.thePlayer != null) held = mc.thePlayer.getHeldItem();
            if (held == null) notice = "先从 NEI 或下方背包样本选择物品";
            else edit(index, held.copy());
        }
    }

    /** Always consume virtual marker drops, including rejected/outside drops. */
    boolean acceptDrop(int x, int y, ItemStack stack, int button) {
        int slot = slotAt(x, y);
        if (slot < 0) notice = "请拖入容器标记槽";
        else {
            selected = slot;
            if (button == 1 || GuiScreen.isShiftKeyDown()) edit(slot, null);
            else if (stack != null) edit(slot, stack.copy());
        }
        return true;
    }

    @Override
    public boolean onMousePressed(int button) {
        int x = getContext().getAbsMouseX(), y = getContext().getAbsMouseY();
        if (getMainPanel().getArea()
            .contains(x, y)) {
            HologramAeNeiIntegration.releaseSearchFocus();
            super.onMousePressed(button);
            return true;
        }
        // Keep the existing display/search-only NEI contract; do not fall through to cheat/give handlers.
        ItemStack sample = HologramAeNeiIntegration.sampleAt(x, y);
        if (sample != null && button == 0) chooseSample(sample);
        else HologramAeNeiIntegration.safeClick(x, y, button);
        return true;
    }

    @Override
    public boolean onMouseRelease(int button) {
        ItemStack drop = dragging;
        dragging = null;
        super.onMouseRelease(button);
        if (drop != null && button == 0)
            return acceptDrop(getContext().getAbsMouseX(), getContext().getAbsMouseY(), drop, button);
        return true;
    }

    private void chooseSample(ItemStack sample) {
        candidate = sample == null ? null : sample.copy();
        dragging = candidate == null ? null : candidate.copy();
        notice = candidate == null ? "此背包样本为空" : "已选择样本：" + candidate.getDisplayName();
    }

    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        int x = getContext().getAbsMouseX(), y = getContext().getAbsMouseY();
        int top = getMainPanel().getArea().y + issueY();
        if (getMainPanel().getArea()
            .contains(x, y) && y >= top && y < top + (compactHeight ? 12 : 25) && !model.issues.isEmpty())
            issueOffset = Math
                .max(0, Math.min(model.issues.size() - 1, issueOffset + (direction == UpOrDown.DOWN ? 1 : -1)));
        else HologramAeNeiIntegration.safeScroll(x, y, direction == UpOrDown.DOWN ? -1 : 1);
        return true;
    }

    @Override
    public boolean onKeyPressed(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            returnToEditor(true);
            return true;
        }
        if (HologramAeNeiIntegration.safeKey(character, key)) return true;
        if ((key == Keyboard.KEY_DELETE || key == Keyboard.KEY_BACK) && selected >= 0) edit(selected, null);
        // No hotbar, throw, pick-block, vanilla Container or NEI mutation key route.
        return true;
    }

    void keyTyped(char character, int key) {
        onKeyPressed(character, key);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (pending < 0 && !clearing.isEmpty() && System.nanoTime() >= clearAfter) clearNext();
    }

    @Override
    public void drawForeground() {
        super.drawForeground();
        if (dragging != null) GuiDraw
            .drawItem(dragging.copy(), getContext().getAbsMouseX() + 6, getContext().getAbsMouseY() + 6, 16, 16, 500);
    }

    // onClose also runs for NEI screen handoffs. Transaction state belongs to the preview session,
    // so only the explicit completion/Escape path below sends aePreviewClose or clears its queue.
    @Override
    public void onClose() {
        restorePlayerContainer();
        HologramAeNeiIntegration.releaseSearchFocus();
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
        HologramClient.stampSemanticRevision(request);
        HologramNetwork.sendAction(request);
    }

    private void action(int id) {
        if (id >= 100) {
            group = id - 100;
            slotPage = 0;
            selected = -1;
            refreshSlots();
        } else if (id == 1) returnToEditor(true);
        else if (id == 3) backpackPage = (backpackPage + 1) % 4;
        else if (id == 4 || id == 5) {
            int lastPage = Math.max(0, (groupSlots().size() - 1) / 36);
            slotPage = Math.max(0, Math.min(lastPage, slotPage + (id == 4 ? -1 : 1)));
            selected = -1;
            refreshSlots();
        } else if ((id == 6 || id == 7) && selected >= 0) {
            ItemStack marked = model.slots.get(selected)
                .stack();
            if (marked != null) {
                marked.stackSize += id == 6 ? 1 : -1;
                edit(selected, marked.stackSize <= 0 ? null : marked);
            }
        } else if (id == 2 && pending < 0) {
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
            returnToEditor(false);
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
        regroup();
        refreshSlots();
        issueOffset = Math.min(issueOffset, Math.max(0, model.issues.size() - 1));
    }

    private void returnToEditor(boolean sendClose) {
        if (explicitReturn) return;
        explicitReturn = true;
        clearing.clear();
        if (active == this) active = null;
        if (sendClose) {
            NBTTagCompound request = model.action("aePreviewClose");
            HologramClient.stampSemanticRevision(request);
            HologramNetwork.sendAction(request);
        }
        ClientGUI.open(editor);
    }

    private final class MarkerWidget extends ItemDisplayWidget
        implements Interactable, RecipeViewerGhostIngredientSlot<ItemStack> {

        private final int cell;

        MarkerWidget(int cell) {
            this.cell = cell;
            size(18).background(HologramMuiTheme.PANEL)
                .displayAmount(true);
            item(new ObjectValue.Dynamic<>(ItemStack.class, () -> {
                int index = visibleIndex(cell);
                return index < 0 ? null
                    : model.slots.get(index)
                        .stack();
            }, ignored -> {}));
            tooltipDynamic(t -> {
                int index = visibleIndex(cell);
                if (index < 0) return;
                HologramAePreviewModel.MarkSlot slot = model.slots.get(index);
                ItemStack stack = slot.stack();
                if (stack != null) t.addLine(stack.getDisplayName());
                t.addLine(slot.label);
                t.addLine(slot.role());
                t.addLine("允许：" + slot.allowed + " · 上限 " + slot.limit);
                if (!slot.issueCode.isEmpty()) {
                    t.addLine("问题：" + slot.issueCode);
                    t.addLine("完整路径：" + slot.issuePath);
                    t.addLine("右键 / Shift / Delete 确认丢弃此问题对应的蓝图数据");
                } else t.addLine(slot.enabled ? "左键标记；右键 / Shift / Delete 清除" : "仅支持清除蓝图标记");
                if (pendingSlot.equals(slot.id)) t.addLine("等待服务端确认，显示仍为已确认标记");
            });
            tooltipAutoUpdate(true);
        }

        @Override
        public Result onMousePressed(int button) {
            selectSlot(visibleIndex(cell), button);
            return Result.SUCCESS;
        }

        @Override
        public void drawForeground(ModularGuiContext context) {
            super.drawForeground(context);
            int index = visibleIndex(cell);
            if (index < 0) return;
            HologramAePreviewModel.MarkSlot slot = model.slots.get(index);
            int color = !slot.enabled ? 0xff777164 : "ghost".equals(slot.kind) ? 0xff638b87 : 0xffa79060;
            if (!slot.issueCode.isEmpty() || slot.id.equals(pendingSlot)) color = 0xffc18d40;
            if (index == selected) color = 0xff80dcee;
            GuiDraw.drawBorderInsideLTRB(0, 0, 18, 18, color);
            if (!slot.issueCode.isEmpty()) mc.fontRenderer.drawString("!", 6, 5, 0xfff2c976);
        }

        @Override
        public boolean handleDragAndDrop(ItemStack stack, int button) {
            return acceptDrop(getArea().x + 8, getArea().y + 8, stack, button);
        }
    }

    private final class SampleWidget extends ItemDisplayWidget implements Interactable {

        private final int column;

        SampleWidget(int column) {
            this.column = column;
        }

        @Override
        public Result onMousePressed(int button) {
            if (button == 0) chooseSample(backpackSample(column));
            return Result.SUCCESS;
        }
    }

    int[] smokeSlotPoint(String slotId) {
        for (MarkerWidget marker : markers) {
            int index = visibleIndex(marker.cell);
            if (index >= 0 && model.slots.get(index).id.equals(slotId))
                return new int[] { marker.getArea().x + 9, marker.getArea().y + 9 };
        }
        return null;
    }

    int[] smokeControlPoint(int id) {
        ButtonWidget<?> button = controls.get(id);
        return button == null ? null
            : new int[] { button.getArea().x + button.getArea().width / 2,
                button.getArea().y + button.getArea().height / 2 };
    }

    boolean smokeControlEnabled(int id) {
        ButtonWidget<?> button = controls.get(id);
        return button != null && button.isEnabled();
    }

    int[] smokeBackpackPoint(int column) {
        if (column < 0 || column >= samples.size()) return null;
        return new int[] { samples.get(column)
            .getArea().x + 9,
            samples.get(column)
                .getArea().y + 9 };
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

    /** Dispatches the same native MUI2 event hooks used by physical input. */
    void smokeMouseButton(int x, int y, int button, boolean pressed) {
        getContext().updateState(x, y, 0);
        // Use the native frame hook to collect hover targets at the probe's synthetic pointer.
        onFrameUpdate();
        if (pressed) onMousePressed(button);
        else onMouseRelease(button);
    }
}
