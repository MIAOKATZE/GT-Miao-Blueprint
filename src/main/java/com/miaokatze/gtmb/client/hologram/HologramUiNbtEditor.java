package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

/** Internal nbt workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiNbtEditor {

    private final HologramUiModules ui;

    HologramUiNbtEditor(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean nbtDrawer;

    private boolean nbtPlacement;

    private int nbtOffset;

    private final HologramNbtSelection nbtSelection = new HologramNbtSelection();

    private boolean nbtCellScope;

    private boolean nbtPathsView;

    private final List<String> nbtIgnoredPaths = new ArrayList<>();

    private HologramMuiTextField nbtValueField;

    private int nbtValueIndex = -1;

    List<String> availableNbtKeys() {
        List<String> keys = new ArrayList<>();
        NBTTagList entries = ui.nbt.editorNbtEntries();
        for (int i = 0; i < entries.tagCount(); i++) keys.add(
            entries.getCompoundTagAt(i)
                .getString("key"));
        return keys;
    }

    void openNbtEditor(boolean placement) {
        this.nbtDrawer = true;
        this.nbtPlacement = placement;
        this.nbtOffset = 0;
        this.nbtCellScope = !placement && ui.preview.selected() >= 0;
        this.nbtValueIndex = -1;
        this.nbtValueField = new HologramMuiTextField(ui.host.parent(), 62, 233, 286, 17);
        this.nbtValueField.setMaxStringLength(8192);
        this.nbtPathsView = false;
        this.nbtIgnoredPaths.clear();
        boolean customized = placement && ui.session.state().data.getBoolean("placementNbtCustomized");
        this.nbtSelection.load(
            customized ? ui.session.state().data.getBoolean("placementNbtAll")
                : !ui.session.state().data.hasKey("blueprintNbtAll")
                    || ui.session.state().data.getBoolean("blueprintNbtAll"),
            ui.session.state().data.getTagList(customized ? "placementNbtKeys" : "blueprintNbtKeys", 8),
            placement && ui.session.state().data.hasKey("placementRetainCovers")
                ? ui.session.state().data.getBoolean("placementRetainCovers")
                : !ui.session.state().data.hasKey("blueprintRetainCovers")
                    || ui.session.state().data.getBoolean("blueprintRetainCovers"));
        if (this.nbtCellScope) {
            NBTTagCompound row = ui.nbt.cellNbtRow();
            NBTTagList keys = new NBTTagList(), entries = row.getTagList("entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                if (entry.getBoolean("selected") || entry.getBoolean("blueprintSelected"))
                    keys.appendTag(new net.minecraft.nbt.NBTTagString(entry.getString("key")));
            }
            this.nbtSelection.load(!row.hasKey("all") || row.getBoolean("all"), keys, ui.processing.retainCovers());
            NBTTagList paths = row.getTagList("ignoredPaths", 8);
            for (int i = 0; i < paths.tagCount(); i++) this.nbtIgnoredPaths.add(paths.getStringTagAt(i));
        }
    }

    NBTTagCompound cellNbtRow() {
        NBTTagList rows = ui.session.state().data.getTagList("cellNbtEntries", 10);
        for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
            .getInteger("index") == ui.preview.selected()) return rows.getCompoundTagAt(i);
        return new NBTTagCompound();
    }

    NBTTagList editorNbtEntries() {
        return this.nbtCellScope && !this.nbtPlacement ? ui.nbt.cellNbtRow()
            .getTagList("entries", 10) : ui.session.state().data.getTagList("nbtEntries", 10);
    }

    private List<String> editorNbtPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (nbtCellScope && !nbtPlacement) {
            NBTTagList entries = cellNbtRow().getTagList("entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                if (!entry.getBoolean("protected") && !entry.getString("path")
                    .isEmpty()) paths.add(entry.getString("path"));
            }
        } else {
            NBTTagList entries = ui.processing.processingList("nbtPaths", 8);
            for (int i = 0; i < entries.tagCount(); i++) paths.add(entries.getStringTagAt(i));
        }
        paths.addAll(nbtIgnoredPaths);
        return new ArrayList<>(paths);
    }

    void drawNbtEditor(int mx, int my) {
        drawRect(50, 58, 548, 304, 0xff172f36);
        ui.canvas.text(
            this.nbtPlacement ? "本次放置的 NBT 匹配条目"
                : this.nbtCellScope ? "所选 " + ui.preview.selection()
                    .size() + " 格的 NBT" : "蓝图保存的 NBT 条目",
            62,
            68,
            474,
            0xf5e4b9);
        ui.canvas.text(
            this.nbtPlacement ? "默认精确匹配蓝图；取消条目将降级本次放置，原蓝图保存选项保持不变。" : "以下为已捕获设备的实际根条目；默认全部保存，设备身份保护，覆盖板独立选择。",
            62,
            86,
            474,
            0x94afb4);
        ui.canvas.button(170, 62, 101, 112, 17, "全部保留", true, mx, my);
        ui.canvas.button(171, 180, 101, 112, 17, "取消普通条目", true, mx, my);
        if (!this.nbtPlacement && !this.nbtCellScope) ui.canvas.button(
            195,
            306,
            101,
            230,
            17,
            "一键清除网络身份 / 外部坐标",
            ui.session.editable() && ui.session.state().data.getBoolean("virtual"),
            mx,
            my);
        if (this.nbtCellScope) ui.canvas.button(
            229,
            306,
            101,
            230,
            17,
            "切换所选条目保留",
            this.nbtValueIndex >= 0 && !ui.nbt.editorNbtEntries()
                .getCompoundTagAt(this.nbtValueIndex)
                .getBoolean("protected"),
            mx,
            my);
        if (this.nbtPlacement) ui.canvas
            .button(174, 306, 101, 230, 17, "本次保留覆盖板 " + (this.nbtSelection.retainCovers() ? "✓" : "×"), true, mx, my);
        NBTTagList entries = ui.nbt.editorNbtEntries();
        if (this.nbtPathsView) {
            entries = new NBTTagList();
            List<String> paths = editorNbtPaths();
            for (String path : paths) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setString("key", path);
                entries.appendTag(entry);
            }
        }
        this.nbtOffset = Math
            .max(0, Math.min(this.nbtOffset, Math.max(0, entries.tagCount() - (this.nbtCellScope ? 5 : 7))));
        for (int i = this.nbtOffset; i
            < Math.min(entries.tagCount(), this.nbtOffset + (this.nbtCellScope ? 5 : 7)); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            String key = entry.getString("key"), category = entry.getString("category");
            String categoryLabel = "item".equals(category) || "items".equals(category) || "inventory".equals(category)
                ? "库存"
                : "fluid".equals(category) ? "流体" : "energy".equals(category) ? "电力" : "配置";
            int y = 124 + (i - this.nbtOffset) * 17;
            String label = (this.nbtPathsView ? this.nbtIgnoredPaths.contains(key) ? "忽略 × " : "保留 ✓ "
                : this.nbtSelection.selected(key) || entry.getBoolean("protected") ? "✓ " : "× ") + categoryLabel
                + " · "
                + (this.nbtCellScope && entry.hasKey("path") ? entry.getString("path") : key)
                + (entry.hasKey("type") ? " [" + entry.getString("type") + "] " + entry.getString("value") : "");
            ui.canvas
                .button(17000 + i, 62, y, 474, 15, label, this.nbtCellScope || !entry.getBoolean("protected"), mx, my);
            if (mx >= 62 && mx < 536 && my >= y && my < y + 15) ui.canvas.tooltip(
                ui.host.font()
                    .listFormattedStringToWidth(
                        (entry.hasKey("path")
                            ? entry.getString("path") + " ["
                                + entry.getString("type")
                                + "] = "
                                + entry.getString("value")
                            : key)
                            + " · "
                            + (entry.getBoolean("protected") ? "服务端保护字段，只读"
                                : this.nbtCellScope ? "点击查看并编辑此值；所选格独立提交，服务端重新核价" : "取消后不保存/匹配这一根条目"),
                        430));
        }
        if (entries.tagCount() == 0) ui.canvas.text("当前模板未包含可选设备 NBT 条目", 62, 128, 474, 0x94afb4);
        if (this.nbtCellScope && !this.nbtPathsView
            && this.nbtValueIndex >= 0
            && this.nbtValueIndex < ui.nbt.editorNbtEntries()
                .tagCount()) {
            NBTTagCompound entry = ui.nbt.editorNbtEntries()
                .getCompoundTagAt(this.nbtValueIndex);
            ui.canvas.text(entry.getString("path") + " · 类型 " + entry.getString("type"), 62, 220, 474, 0x94afb4);
            this.nbtValueField.drawTextBox();
            ui.canvas.button(
                217,
                358,
                233,
                178,
                17,
                "应用此值 · 服务端校验",
                ui.session.editable() && entry.getBoolean("editable"),
                mx,
                my);
        }
        if (this.nbtCellScope)
            ui.canvas.button(228, 62, 267, 474, 13, this.nbtPathsView ? "返回条目编辑" : "编辑忽略 NBT 路径（配置细节）", true, mx, my);
        else ui.canvas.text("库存：背包 / AE · 流体：AE · 电力：无线电网；材料确认页可查看可用量与缺口。", 62, 270, 474, 0x94afb4);
        ui.canvas.button(172, 62, 283, 230, 17, "取消", true, mx, my);
        ui.canvas.button(
            173,
            306,
            283,
            230,
            17,
            this.nbtPlacement ? "仅应用本次降级" : this.nbtCellScope ? "应用所选格 · 重新核价" : "应用蓝图保存选项",
            ui.session.editable(),
            mx,
            my);
        ui.canvas.button(
            211,
            62,
            251,
            474,
            15,
            "去标识等级 " + ui.identity.deidentificationChoice(this.nbtPlacement) + " · 点击设置 0–10",
            ui.session.editable() && ui.session.state().data.getBoolean("virtual"),
            mx,
            my);
    }

    boolean resetNetworksControl(int id) {
        if (id == 195) {
            ui.session.send("resetBlueprintNetworks", new NBTTagCompound(), true);
            this.nbtDrawer = false;
            return true;
        }
        return false;
    }

    boolean selectEntryControl(int id) {
        if (this.nbtDrawer && id >= 17000 && id < 18000) {
            if (this.nbtPathsView) {
                List<String> paths = editorNbtPaths();
                if (id - 17000 >= paths.size()) return true;
                String path = paths.get(id - 17000);
                if (!this.nbtIgnoredPaths.remove(path)) this.nbtIgnoredPaths.add(path);
                return true;
            }
            if (this.nbtCellScope) {
                this.nbtValueIndex = id - 17000;
                this.nbtValueField.setText(
                    ui.nbt.editorNbtEntries()
                        .getCompoundTagAt(this.nbtValueIndex)
                        .getString("value"));
                this.nbtValueField.setEnabled(
                    ui.nbt.editorNbtEntries()
                        .getCompoundTagAt(this.nbtValueIndex)
                        .getBoolean("editable"));
                this.nbtValueField.setFocused(false);
                return true;
            }
            String key = ui.nbt.editorNbtEntries()
                .getCompoundTagAt(id - 17000)
                .getString("key");
            this.nbtSelection.toggle(key, ui.nbt.availableNbtKeys());
            return true;
        }
        return false;
    }

    boolean activateControl(int id) {
        switch (id) {
            case 217:
                if (this.nbtCellScope && this.nbtValueIndex >= 0
                    && ui.nbt.editorNbtEntries()
                        .getCompoundTagAt(this.nbtValueIndex)
                        .getBoolean("editable")) {
                    NBTTagCompound entry = ui.nbt.editorNbtEntries()
                        .getCompoundTagAt(this.nbtValueIndex), change = new NBTTagCompound(),
                        request = new NBTTagCompound();
                    change.setString("path", entry.getString("path"));
                    change.setString("type", entry.getString("type"));
                    change.setString("value", this.nbtValueField.getText());
                    NBTTagList changes = new NBTTagList();
                    changes.appendTag(change);
                    request.setTag("changes", changes);
                    request.setIntArray(
                        "indices",
                        ui.preview.selection()
                            .indices());
                    request.setLong("planRevision", ui.session.revision());
                    ui.session.send("editBlueprintCellNbt", request, true);
                    this.nbtDrawer = false;
                }
                return true;
            case 229:
                if (this.nbtValueIndex >= 0) this.nbtSelection.toggle(
                    ui.nbt.editorNbtEntries()
                        .getCompoundTagAt(this.nbtValueIndex)
                        .getString("key"),
                    ui.nbt.availableNbtKeys());
                return true;
            case 168:
                ui.nbt.openNbtEditor(true);
                return true;
            case 170:
                this.nbtSelection.all(true);
                return true;
            case 171:
                this.nbtSelection.all(false);
                return true;
            case 172:
                this.nbtDrawer = false;
                return true;
            case 173:
                if (ui.session.editable()) {
                    NBTTagCompound nbt = new NBTTagCompound();
                    nbt.setBoolean("all", this.nbtSelection.all());
                    nbt.setTag("keys", this.nbtSelection.keys());
                    if (this.nbtCellScope && !this.nbtPlacement) {
                        nbt.setIntArray(
                            "indices",
                            ui.preview.selection()
                                .indices());
                        NBTTagList paths = new NBTTagList();
                        for (String path : this.nbtIgnoredPaths)
                            paths.appendTag(new net.minecraft.nbt.NBTTagString(path));
                        nbt.setTag("ignoredPaths", paths);
                        nbt.setLong("planRevision", ui.session.revision());
                    }
                    if (this.nbtPlacement) nbt.setBoolean("retainCovers", this.nbtSelection.retainCovers());
                    this.nbtDrawer = false;
                    ui.session.send(
                        this.nbtPlacement ? "configurePlacementNbt"
                            : this.nbtCellScope ? "configureBlueprintCellNbt" : "configureBlueprintNbt",
                        nbt,
                        true);
                }
                return true;
            case 228:
                this.nbtPathsView = !this.nbtPathsView;
                this.nbtOffset = 0;
                return true;
            case 174:
                this.nbtSelection.retainCovers(!this.nbtSelection.retainCovers());
                return true;
            default:
                return false;
        }
    }

    boolean consumeEditorMouse(int mx, int my, int button) {
        if (this.nbtDrawer) {
            if (this.nbtCellScope && this.nbtValueIndex >= 0 && this.nbtValueField != null) {
                this.nbtValueField.mouseClicked(mx, my, button);
                if (mx >= 62 && mx < 348 && my >= 233 && my < 250) return true;
            }
            if (button == 0) for (Control control : ui.canvas.controls()) if (control.contains(mx, my)) {
                if (control.enabled) ui.router.activate(control.id);
                return true;
            }
            return true;
        }
        return false;
    }

    boolean consumeEditorKey(char c, int key) {
        if (this.nbtDrawer) {
            if (key == Keyboard.KEY_ESCAPE) this.nbtDrawer = false;
            else if (this.nbtCellScope && this.nbtValueIndex >= 0 && this.nbtValueField != null) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) ui.router.activate(217);
                else this.nbtValueField.textboxKeyTyped(c, key);
            }
            return true;
        }
        return false;
    }

    boolean consumeEditorWheel(int wheel) {
        if (this.nbtDrawer) {
            this.nbtOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        (this.nbtPathsView ? editorNbtPaths().size()
                            : ui.nbt.editorNbtEntries()
                                .tagCount())
                            - (this.nbtCellScope ? 5 : 7)),
                    this.nbtOffset + (wheel > 0 ? -1 : 1)));
            return true;
        }
        return false;
    }

    boolean nbtCellScope() {
        return this.nbtCellScope;
    }

    boolean nbtDrawer() {
        return this.nbtDrawer;
    }

    boolean nbtDrawer(boolean value) {
        this.nbtDrawer = value;
        return value;
    }

    boolean nbtPlacement() {
        return this.nbtPlacement;
    }

    HologramMuiTextField nbtValueField() {
        return this.nbtValueField;
    }

    int nbtValueIndex() {
        return this.nbtValueIndex;
    }

    void tickField() {
        if (ui.nbt.nbtValueField() != null && ui.nbt.nbtDrawer()) ui.nbt.nbtValueField()
            .updateCursorCounter();
    }

    void prepareCommand(String op, NBTTagCompound request) {
        if (!request.hasKey("planRevision")
            && ("configureBlueprintCellNbt".equals(op) || "editBlueprintCellNbt".equals(op)))
            request.setLong("planRevision", ui.session.revision());
    }
}
