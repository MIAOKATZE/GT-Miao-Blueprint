package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import net.minecraft.nbt.NBTTagCompound;

/** Internal position workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiPosition {

    private final HologramUiModules ui;

    HologramUiPosition(HologramUiModules ui) {
        this.ui = ui;
    }

    private HologramMuiTextField positionXField;

    private HologramMuiTextField positionYField;

    private HologramMuiTextField positionZField;

    private boolean positionDrawer;

    HologramMuiTextField positionField(int x) {
        HologramMuiTextField field = new HologramMuiTextField(ui.host.parent(), x, 116, 70, 18);
        field.setMaxStringLength(11);
        field.setText("0");
        return field;
    }

    void drawPosition(int mx, int my) {
        ui.canvas.tooltip(null);
        drawRect(157, 59, 405, 257, 0xff172f36);
        ui.canvas.text("添加位置 · 相对控制器", 166, 67, 228, 0xf5e4b9);
        ui.canvas.button(77, 375, 62, 23, 17, "×", true, mx, my);
        ui.canvas.text("世界轴偏移 X / Y / Z（−64～64）", 166, 91, 230, 0x94afb4);
        this.positionXField.drawTextBox();
        this.positionYField.drawTextBox();
        this.positionZField.drawTextBox();
        ui.canvas.button(76, 166, 146, 230, 20, "添加并选中此位置", ui.session.editable(), mx, my);
        ui.canvas.text("选中后点击“选择方块 / 仓室”指定目标", 166, 180, 230, 0xb9d5d4);
        ui.canvas.text("位置须在已加载区块；控制器受保护", 166, 199, 230, 0x94afb4);
    }

    void addPosition() {
        if (!ui.session.editable()) return;
        try {
            int dx = Integer.parseInt(
                this.positionXField.getText()
                    .trim());
            int dy = Integer.parseInt(
                this.positionYField.getText()
                    .trim());
            int dz = Integer.parseInt(
                this.positionZField.getText()
                    .trim());
            if (Math.abs((long) dx) > 64 || Math.abs((long) dy) > 64 || Math.abs((long) dz) > 64)
                throw new NumberFormatException();
            NBTTagCompound action = new NBTTagCompound();
            action.setInteger("dx", dx);
            action.setInteger("dy", dy);
            action.setInteger("dz", dz);
            this.positionDrawer = false;
            ui.session.send("addPosition", action, true);
        } catch (NumberFormatException invalid) {
            ui.session.notice("坐标须为−64～64的整数");
        }
    }

    boolean activateControl(int id) {
        switch (id) {
            case 75:
                this.positionDrawer = true;
                ui.config.drawer(ui.preview.inventory(false));
                return true;
            case 76:
                ui.position.addPosition();
                return true;
            case 77:
                this.positionDrawer = false;
                this.positionXField.setFocused(false);
                this.positionYField.setFocused(false);
                this.positionZField.setFocused(false);
                return true;
            default:
                return false;
        }
    }

    boolean positionDrawer() {
        return this.positionDrawer;
    }

    boolean positionDrawer(boolean value) {
        this.positionDrawer = value;
        return value;
    }

    HologramMuiTextField positionXField() {
        return this.positionXField;
    }

    HologramMuiTextField positionXField(HologramMuiTextField value) {
        this.positionXField = value;
        return value;
    }

    HologramMuiTextField positionYField() {
        return this.positionYField;
    }

    HologramMuiTextField positionYField(HologramMuiTextField value) {
        this.positionYField = value;
        return value;
    }

    HologramMuiTextField positionZField() {
        return this.positionZField;
    }

    HologramMuiTextField positionZField(HologramMuiTextField value) {
        this.positionZField = value;
        return value;
    }

    void initializeFields() {
        ui.position.positionXField(ui.position.positionField(166));
        ui.position.positionYField(ui.position.positionField(246));
        ui.position.positionZField(ui.position.positionField(326));
    }

    void tickFields() {
        if (ui.position.positionXField() != null) {
            ui.position.positionXField()
                .updateCursorCounter();
            ui.position.positionYField()
                .updateCursorCounter();
            ui.position.positionZField()
                .updateCursorCounter();
        }
    }
}
