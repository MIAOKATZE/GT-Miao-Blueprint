package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** A view of the current workspace's real structure declarations and complete member selection. */
final class HologramEditorStructurePane {

    private static final int ROWS = 8;
    private final HologramUiModules ui;
    private final StringValue search = new StringValue("");
    private final List<NBTTagCompound> matches = new ArrayList<>();
    private final Flow[] rowContainers = new Flow[ROWS];
    private Flow root;
    private int offset;
    private String previousQuery = "";
    private int previousVisible = -1;
    private HologramState observedState;

    HologramEditorStructurePane(HologramUiModules ui) {
        this.ui = ui;
    }

    IWidget build() {
        if (root != null) return root;
        root = Flow.col()
            .name("native_structure_list")
            .sizeRel(1f)
            .padding(5)
            .childPadding(4);
        root.child(
            HologramEditorNativeTheme.label(() -> "结构列表 · " + matches.size(), HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(20));
        root.child(
            HologramEditorNativeTheme.textField(search, 80)
                .hintText("查找结构")
                .widthRel(1f)
                .height(20));
        HologramRecessedList list = new HologramRecessedList().paperEdges()
            .widthRel(1f)
            .expanded();
        for (int i = 0; i < ROWS; i++) {
            final int slot = i;
            ButtonWidget<?> button = HologramEditorNativeTheme
                .rowButton(
                    () -> rowLabel(slot),
                    "structure",
                    () -> select(slot),
                    () -> available(slot),
                    () -> selected(slot))
                .widthRel(1f)
                .height(34);
            button.tooltipBuilder(t -> {
                NBTTagCompound row = row(slot);
                if (row == null) return;
                t.addLine(HologramEditorPropertiesPane.sourceLabel(ui, row.getString("id")));
                t.addLine("点击选择完整结构；Ctrl 保留已有选区。");
                if (row.getBoolean("sourceMissing")) t.addLine("来源缺失：" + row.getString("sourceWarning"));
            })
                .tooltipAutoUpdate(true);
            ui.shell.registerTarget("structure.row." + slot, button);
            rowContainers[i] = Flow.col()
                .widthRel(1f)
                .height(34)
                .child(button);
            rowContainers[i].setEnabledIf(w -> row(slot) != null);
            list.child(rowContainers[i]);
        }
        root.child(list);
        Flow pager = HologramEditorPropertiesPane.row();
        pager.child(
            HologramEditorNativeTheme
                .button(
                    () -> "上一页",
                    null,
                    () -> offset = Math.max(0, offset - ROWS),
                    () -> offset > 0,
                    () -> false,
                    false)
                .expanded());
        pager.child(
            HologramEditorNativeTheme
                .button(
                    () -> "下一页",
                    null,
                    () -> offset += ROWS,
                    () -> offset + ROWS < matches.size(),
                    () -> false,
                    false)
                .expanded());
        root.child(pager);
        root.child(
            HologramEditorNativeTheme.label(() -> matches.isEmpty() ? "当前没有匹配的结构" : "列表选择整组 · 画布选择单格")
                .widthRel(1f)
                .height(28));
        refresh();
        return root;
    }

    void refresh() {
        String query = search.getStringValue()
            .trim()
            .toLowerCase(Locale.ROOT);
        boolean queryChanged = !query.equals(previousQuery);
        if (queryChanged) offset = 0;
        HologramState state = ui.session.state();
        if (queryChanged || observedState != state) {
            previousQuery = query;
            observedState = state;
            matches.clear();
            NBTTagList structures = state.data.getTagList("structures", 10);
            for (int i = 0; i < structures.tagCount(); i++) {
                NBTTagCompound row = structures.getCompoundTagAt(i);
                if (query.isEmpty() || (row.getString("name") + " " + row.getString("id")).toLowerCase(Locale.ROOT)
                    .contains(query)) matches.add(row);
            }
        }
        offset = Math.max(0, Math.min(offset, Math.max(0, (matches.size() - 1) / ROWS * ROWS)));
        int visible = Math.min(ROWS, Math.max(0, matches.size() - offset));
        if (root != null && visible != previousVisible) {
            previousVisible = visible;
            root.scheduleResize();
        }
    }

    private NBTTagCompound row(int slot) {
        int index = offset + slot;
        return index >= 0 && index < matches.size() ? matches.get(index) : null;
    }

    private String rowLabel(int slot) {
        NBTTagCompound row = row(slot);
        if (row == null) return "";
        int count = ui.session.state().structureMembers
            .getOrDefault(row.getString("id"), java.util.Collections.emptyList())
            .size();
        return row.getString("name") + " · " + count + " 格" + (row.getBoolean("sourceMissing") ? " · 来源缺失" : "");
    }

    private boolean available(int slot) {
        return row(slot) != null && ui.session.editable()
            && !ui.library.browsePreviewActive()
            && !ui.preview.inventory();
    }

    private boolean selected(int slot) {
        NBTTagCompound row = row(slot);
        return row != null && row.getString("id")
            .equals(ui.config.selectedStructureId());
    }

    private void select(int slot) {
        if (!available(slot)) return;
        String id = row(slot).getString("id");
        ui.preview.selection()
            .structure(ui.session.state(), id, ui.preview.controlSelectionHeld());
        ui.preview.selected(
            ui.preview.selection()
                .first());
        ui.memory.cellEditHint(ui.preview.selected() >= 0);
        ui.config.syncTargetSelectors();
    }
}
