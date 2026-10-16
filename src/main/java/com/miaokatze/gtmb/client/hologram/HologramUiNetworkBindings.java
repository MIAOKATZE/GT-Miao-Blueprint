package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

/** Internal bindings workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiNetworkBindings {

    private final HologramUiModules ui;

    HologramUiNetworkBindings(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean networkDrawer;

    private int networkGroupIndex;

    private int networkGroupOffset;

    private boolean networkRebindSelected;

    private final HologramNetworkBindingNavigation networkBindingNavigation = new HologramNetworkBindingNavigation();

    private HologramMuiTextField networkHintField;

    NBTTagList networkGroups() {
        return ui.session.state().data.getCompoundTag("networkBindings")
            .getTagList("groups", 10);
    }

    String selectedNetworkGroup() {
        NBTTagList cells = ui.session.state().data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            if (row.getInteger("index") == ui.preview.selected()) return row.getString("networkGroup");
        }
        return "";
    }

    void loadNetworkHintField() {
        if (this.networkHintField == null) return;
        this.networkHintField.setText(
            this.networkGroupIndex >= 0 && this.networkGroupIndex < ui.bindings.networkGroups()
                .tagCount() ? ui.bindings.networkGroups()
                    .getCompoundTagAt(this.networkGroupIndex)
                    .getString("prompt") : "");
    }

    void revealPendingNetworkGroup() {
        int pending = HologramNetworkBindingNavigation.firstPending(ui.session.state().data);
        this.networkGroupIndex = pending >= 0 ? pending : 0;
        this.networkGroupOffset = this.networkGroupIndex / 5 * 5;
        this.networkRebindSelected = false;
        ui.bindings.loadNetworkHintField();
    }

    void drawNetworkBindings(int mx, int my) {
        ui.canvas.controls()
            .clear();
        ui.canvas.tooltip(null);
        drawRect(35, 50, 645, 299, 0xfc172f36);
        boolean terminalBinding = ui.session.state().data.getCompoundTag("networkBindings")
            .getBoolean("terminalBinding");
        ui.canvas.text(terminalBinding ? "量子网络类群 · 逐组绑定" : "量子网络类群 · 保留原网络", 50, 64, 570, 0xffe59b);
        ui.canvas.text(
            !terminalBinding ? "自用保留原绑定，无需终端或重新绑定；原存档、原网络位置与维度仍需可访问。"
                : ui.session.pendingSequence() >= 0 ? "正在绑定，请等待服务端确认；成功后自动选中下一个待绑定类群。"
                    : HologramPlacementConfirmation.networksReady(ui.session.state().data)
                        ? "步骤 3/3 · 全部类群已就绪，点击绿色“确认全部类群”；也可选择已绑类群改绑。"
                        : "步骤 1/3 · 选择左侧待绑定类群 → 步骤 2/3 · 点击右侧绿色量子终端；成功后自动下一组。",
            50,
            81,
            570,
            0xa9d6cf);
        NBTTagList groups = ui.bindings.networkGroups();
        this.networkGroupIndex = Math.max(0, Math.min(this.networkGroupIndex, groups.tagCount() - 1));
        this.networkGroupOffset = Math.max(0, Math.min(this.networkGroupOffset, Math.max(0, groups.tagCount() - 5)));
        for (int i = this.networkGroupOffset; i < Math.min(groups.tagCount(), this.networkGroupOffset + 5); i++) {
            NBTTagCompound row = groups.getCompoundTagAt(i);
            String groupName = row.getString("name");
            if (groupName.isEmpty()) groupName = row.getString("hint");
            if (groupName.isEmpty()) groupName = row.getString("id");
            String label = (i == this.networkGroupIndex ? "▶ " : "") + groupName
                + " · "
                + row.getInteger("cells")
                + " 格 · "
                + (row.getBoolean("bound") ? "已就绪" : "待处理");
            ui.canvas.button(
                60000 + i,
                50,
                103 + (i - this.networkGroupOffset) * 22,
                325,
                19,
                label,
                ui.session.pendingSequence() < 0,
                mx,
                my);
        }
        NBTTagList inventory = ui.session.state().data.getCompoundTag("networkBindings")
            .getTagList("inventory", 10);
        boolean selectedTerminalBinding = groups.tagCount() > 0 && groups.getCompoundTagAt(this.networkGroupIndex)
            .getBoolean("terminalBinding");
        ui.canvas.text(selectedTerminalBinding ? "主背包与快捷栏 · 选择终端" : "保留原绑定 · 无需量子终端", 405, 101, 216, 0xa9d6cf);
        for (int slot = 0; slot < 36; slot++) {
            int x = 405 + (slot % 9) * 23;
            int y = slot < 9 ? 194 : 121 + ((slot - 9) / 9) * 23;
            NBTTagCompound row = null;
            for (int i = 0; i < inventory.tagCount(); i++) if (inventory.getCompoundTagAt(i)
                .getInteger("slot") == slot) row = inventory.getCompoundTagAt(i);
            boolean available = row != null && row.getBoolean("available");
            ui.canvas.button(
                61000 + slot,
                x,
                y,
                21,
                21,
                "",
                selectedTerminalBinding && available && ui.session.pendingSequence() < 0,
                mx,
                my);
            if (row != null) {
                ItemStack display = ItemStack.loadItemStackFromNBT(row.getCompoundTag("item"));
                if (display != null) ui.canvas.drawItem(display, x + 2, y + 2);
                if (mx >= x && mx < x + 21 && my >= y && my < y + 21) {
                    ui.canvas.tooltip(new ArrayList<>());
                    ui.canvas.tooltip()
                        .add(row.getString("name"));
                    ui.canvas.tooltip()
                        .add(
                            !selectedTerminalBinding ? "本类群保留原锚点，此处不能选择其他网络重新绑定"
                                : available ? "点击绑定所选网络类群" : HologramUiText.reason(row.getString("reason")));
                    if (selectedTerminalBinding && !available) ui.canvas.tooltip()
                        .add("技术诊断：" + row.getString("reason"));
                }
            }
        }
        if (groups.tagCount() > 0) {
            if (this.networkHintField != null) this.networkHintField.drawTextBox();
            NBTTagCompound selectedGroup = groups.getCompoundTagAt(this.networkGroupIndex);
            ui.canvas.text(
                selectedGroup.getString("name") + " · " + selectedGroup.getString("hint"),
                50,
                242,
                570,
                0xffe59b);
            if (!selectedGroup.getBoolean("bound")) {
                ui.canvas.text(HologramUiText.reason(selectedGroup.getString("reason")), 50, 257, 570, 0xff9292);
                if (mx >= 50 && mx < 620 && my >= 253 && my < 269) {
                    ui.canvas.tooltip(
                        new ArrayList<>(
                            ui.host.font()
                                .listFormattedStringToWidth(
                                    HologramUiText.reason(selectedGroup.getString("reason")),
                                    380)));
                }
            }
        } else ui.canvas.text("此蓝图没有需要重新绑定的量子网络组", 50, 242, 570, 0x94afb4);
        ui.canvas.button(198, 50, 273, 95, 17, "上一组页", this.networkGroupOffset > 0, mx, my);
        ui.canvas.button(199, 151, 273, 95, 17, "下一组页", this.networkGroupOffset + 5 < groups.tagCount(), mx, my);
        ui.canvas.button(
            200,
            252,
            273,
            123,
            17,
            "应用本组提示",
            ui.session.editable() && groups.tagCount() > 0 && ui.session.pendingSequence() < 0,
            mx,
            my);
        ui.canvas.button(201, 405, 220, 210, 17, "取消 / 返回 · Esc / E", ui.session.pendingSequence() < 0, mx, my);
        ui.canvas.button(
            197,
            405,
            273,
            210,
            17,
            terminalBinding ? "确认全部类群 · 返回放置" : "返回放置 · 保留原绑定",
            ui.session.pendingSequence() < 0 && HologramPlacementConfirmation.networksReady(ui.session.state().data),
            mx,
            my);
    }

    void activateNetworkBinding(int id) {
        if (id == 197 || id == 201) {
            if (id == 197 && !HologramPlacementConfirmation.networksReady(ui.session.state().data)) return;
            this.networkDrawer = false;
            ui.session.send("closeNetworkBindings", new NBTTagCompound(), true);
        } else if (id == 198) this.networkGroupOffset = Math.max(0, this.networkGroupOffset - 5);
        else if (id == 199) this.networkGroupOffset = this.networkGroupOffset + (5);
        else if (id == 200 && this.networkHintField != null
            && ui.bindings.networkGroups()
                .tagCount() > 0) {
                    NBTTagCompound action = new NBTTagCompound();
                    action.setString(
                        "group",
                        ui.bindings.networkGroups()
                            .getCompoundTagAt(this.networkGroupIndex)
                            .getString("id"));
                    action.setString("hint", this.networkHintField.getText());
                    ui.session.send("editBlueprintNetworkHint", action, true);
                } else
            if (id >= 60000 && id < 61000
                && id - 60000 < ui.bindings.networkGroups()
                    .tagCount()) {
                        this.networkGroupIndex = id - 60000;
                        this.networkRebindSelected = ui.bindings.networkGroups()
                            .getCompoundTagAt(this.networkGroupIndex)
                            .getBoolean("bound");
                        this.networkBindingNavigation.cancel();
                        ui.bindings.loadNetworkHintField();
                    } else
                if (id >= 61000 && id < 61036
                    && this.networkGroupIndex < ui.bindings.networkGroups()
                        .tagCount()) {
                            if (ui.session.pendingSequence() >= 0 || this.networkBindingNavigation.pending()) return;
                            if (!ui.bindings.networkGroups()
                                .getCompoundTagAt(this.networkGroupIndex)
                                .getBoolean("terminalBinding")) return;
                            NBTTagCompound action = new NBTTagCompound();
                            action.setString(
                                "group",
                                ui.bindings.networkGroups()
                                    .getCompoundTagAt(this.networkGroupIndex)
                                    .getString("id"));
                            action.setInteger("slot", id - 61000);
                            this.networkBindingNavigation.begin(
                                ui.session.state().data,
                                ui.session.sequence() + 1,
                                action.getString("group"),
                                ui.bindings.networkGroups()
                                    .getCompoundTagAt(this.networkGroupIndex)
                                    .getBoolean("bound"));
                            ui.session.send("bindBlueprintNetwork", action, true);
                        }
    }

    boolean openBindingsControl(int id) {
        if (id == 196) {
            ui.bindings.revealPendingNetworkGroup();
            ui.session.send("openNetworkBindings", new NBTTagCompound(), true);
            this.networkDrawer = true;
            return true;
        }
        return false;
    }

    boolean consumeBindingsMouse(int mx, int my, int button) {
        if (this.networkDrawer) {
            if (this.networkHintField != null) {
                this.networkHintField.mouseClicked(mx, my, button);
                if (mx >= 50 && mx < 375 && my >= 220 && my < 238) return true;
            }
            if (button == 0)
                for (Control control : ui.canvas.controls()) if (control.enabled && control.contains(mx, my)) {
                    ui.bindings.activateNetworkBinding(control.id);
                    return true;
                }
            return true;
        }
        return false;
    }

    boolean consumeBindingsKey(char c, int key) {
        if (this.networkDrawer) {
            if (key == Keyboard.KEY_ESCAPE
                || key == Keyboard.KEY_E && (this.networkHintField == null || !this.networkHintField.isFocused()))
                ui.bindings.activateNetworkBinding(201);
            else if (this.networkHintField != null) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER)
                    ui.bindings.activateNetworkBinding(200);
                else this.networkHintField.textboxKeyTyped(c, key);
            }
            return true;
        }
        return false;
    }

    static final class SnapshotTransition {

        final boolean drawer;
        final String group;

        SnapshotTransition(boolean drawer, String group) {
            this.drawer = drawer;
            this.group = group;
        }
    }

    SnapshotTransition captureSnapshotTransition() {
        return new SnapshotTransition(
            networkDrawer,
            networkGroupIndex >= 0 && networkGroupIndex < networkGroups().tagCount()
                ? networkGroups().getCompoundTagAt(networkGroupIndex)
                    .getString("id")
                : "");
    }

    void acknowledgeSnapshot(HologramState next, SnapshotTransition previous, boolean sourceChanged) {
        networkDrawer = next.data.getBoolean("networkBindingRequested");
        boolean wasPending = networkBindingNavigation.pending();
        int group = networkBindingNavigation.observe(next.data, previous.group);
        if (wasPending && !networkBindingNavigation.pending()) networkRebindSelected = false;
        if (networkDrawer && (!previous.drawer || sourceChanged)) revealPendingNetworkGroup();
        else if (group >= 0) {
            networkGroupIndex = group;
            networkGroupOffset = group / 5 * 5;
            networkRebindSelected = false;
            loadNetworkHintField();
        }
        if (!networkDrawer) networkRebindSelected = false;
    }

    HologramNetworkBindingNavigation networkBindingNavigation() {
        return this.networkBindingNavigation;
    }

    boolean networkDrawer() {
        return this.networkDrawer;
    }

    boolean networkDrawer(boolean value) {
        this.networkDrawer = value;
        return value;
    }

    int networkGroupIndex() {
        return this.networkGroupIndex;
    }

    int networkGroupOffset() {
        return this.networkGroupOffset;
    }

    HologramMuiTextField networkHintField() {
        return this.networkHintField;
    }

    HologramMuiTextField networkHintField(HologramMuiTextField value) {
        this.networkHintField = value;
        return value;
    }

    boolean networkRebindSelected() {
        return this.networkRebindSelected;
    }

    void initializeFields() {
        ui.bindings.networkHintField(new HologramMuiTextField(ui.host.parent(), 50, 220, 325, 18));
        ui.bindings.networkHintField()
            .setMaxStringLength(256);
        ui.bindings.loadNetworkHintField();
    }

    void tickField() {
        if (ui.bindings.networkHintField() != null && ui.bindings.networkDrawer()) ui.bindings.networkHintField()
            .updateCursorCounter();
    }

    void initializeSnapshot(HologramState snapshot) {
        networkDrawer = snapshot.data.getBoolean("networkBindingRequested");
        revealPendingNetworkGroup();
    }
}
