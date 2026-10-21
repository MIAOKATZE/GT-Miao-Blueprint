package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Passive native review of the same precise quote model used by the production material dialog. */
final class HologramEditorMaterialLesson {

    static final String PANEL_NAME = "gtmb_tutorial_materials";

    private final HologramMaterialReview materials = new HologramMaterialReview();
    private NBTTagCompound data;
    private ModularPanel panel;

    void show(HologramState source) {
        data = (NBTTagCompound) source.data.copy();
        NBTTagCompound quote = data.getCompoundTag("materialQuote");
        quote.setString("session", data.getString("session"));
        quote.setLong("planRevision", data.getLong("planRevision"));
        quote.setLong("placementReviewNonce", data.getLong("placementReviewNonce"));
        data.setTag("materialQuote", quote);
        materials.update(data);
    }

    boolean visible() {
        return data != null;
    }

    void close() {
        data = null;
        if (panel != null) panel.closeIfOpen();
        panel = null;
    }

    ModularPanel build(HologramUiModules ui) {
        if (panel != null) return panel;
        int width = Math.max(260, Math.min(600, ui.host.screenWidth() - 24));
        int availableHeight = ui.host.screenHeight() - HologramEditorTutorialOverlay.RESERVED_HEIGHT;
        int height = Math.max(80, Math.min(390, availableHeight - 24));
        panel = new Dialog<Void>(ui.panelName(PANEL_NAME)).setDisablePanelsBelow(false)
            .setCloseOnOutOfBoundsClick(false)
            .size(width, height)
            .top(Math.max(6, (availableHeight - height) / 2))
            .background(HologramEditorNativeTheme.PANEL);
        Flow column = Flow.col()
            .sizeRel(1f)
            .padding(8)
            .childPadding(5)
            .name("materials.content");
        column.child(
            HologramEditorNativeTheme.label(() -> "蓝图放置预检 · 教学示例", HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(17)
                .name("materials.title"));
        ListWidget<IWidget, ?> body = new ListWidget<>().sizeRel(1f)
            .expanded()
            .name("materials.rows")
            .background(IDrawable.NONE)
            .disableThemeBackground(true);
        body.child(
            HologramEditorNativeTheme
                .label(
                    () -> data == null ? ""
                        : "整图 · 示例锁定位置 " + data
                            .getInteger("x") + ", " + data.getInteger("y") + ", " + data.getInteger("z"))
                .widthRel(1f)
                .height(17)
                .name("materials.position"));
        body.child(
            HologramEditorNativeTheme.label(() -> "示例库存来自打开时快照；本课不执行实时库存校验。", HologramEditorNativeTheme.MUTED)
                .widthRel(1f)
                .height(24));
        Flow header = Flow.row()
            .widthRel(1f)
            .height(17)
            .childPadding(5);
        header.child(
            HologramEditorNativeTheme.label(() -> "材料 / 精确 NBT")
                .widthRel(1f)
                .expanded()
                .height(17));
        for (String name : new String[] { "需求", "已有", "缺失" }) header.child(
            HologramEditorNativeTheme.label(() -> name)
                .width(54)
                .height(17));
        body.child(header);
        for (int i = 0; i < Math.min(6, materials.size()); i++) body.child(materialRow(i));
        if (materials.size() == 0) body.child(
            HologramEditorNativeTheme.label(() -> "当前快照尚无精确清单；未回传数量不表示零库存。")
                .widthRel(1f)
                .height(34));
        body.child(
            HologramEditorNativeTheme
                .label(
                    () -> materials.size() > 6 ? "共 " + materials.size() + " 项，本示例展示前六项；生产窗口可查看全部。"
                        : materials.size() + " 项精确条目，配置材料与设备分别核算。",
                    HologramEditorNativeTheme.MUTED)
                .widthRel(1f)
                .height(24));
        body.child(HologramEditorNativeTheme.label(() -> {
            String cache = data == null ? "" : HologramPlacementConfirmation.cacheSummary(data);
            return cache.isEmpty() ? "设备缓存按其真实能力与清单核算。" : cache;
        }, HologramEditorNativeTheme.MUTED)
            .widthRel(1f)
            .height(23));
        body.child(options(new String[] { "本次 NBT", "量子网络", "AE 补齐缺料", "装饰抵用券" }, "materials.options.primary"));
        body.child(options(new String[] { "去标识", "清障", "校验详情", "刷新材料" }, "materials.options.secondary"));
        column.child(body);
        ButtonWidget<?> cancel = HologramEditorNativeTheme.button(() -> "取消放置", this::close)
            .width(78)
            .height(20)
            .name("materials.cancel");
        ui.shell.registerTarget("materials.cancel", cancel);
        column.child(
            Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(6)
                .child(cancel)
                .child(
                    HologramEditorNativeTheme.primaryButton(() -> "最终确认 · 仅示范", () -> {})
                        .widthRel(1f)
                        .expanded()
                        .height(20)
                        .name("materials.confirm")));
        panel.child(column);
        ui.shell.registerTarget("materials", panel);
        return panel;
    }

    private IWidget materialRow(int index) {
        HologramMaterialReview.Row row = materials.row(index);
        Flow result = Flow.row()
            .widthRel(1f)
            .height(34)
            .childPadding(5)
            .name("materials.row." + index);
        result.child(
            new ItemDisplayWidget().item(row.item)
                .size(18)
                .name("materials.item." + index));
        result.child(
            Flow.col()
                .expanded()
                .coverChildrenHeight()
                .childPadding(1)
                .child(
                    HologramEditorNativeTheme.label(() -> row.name)
                        .widthRel(1f)
                        .height(16))
                .child(
                    HologramEditorNativeTheme
                        .label(() -> row.identity() + (row.tagged ? " · 精确 NBT" : ""), HologramEditorNativeTheme.MUTED)
                        .widthRel(1f)
                        .height(14)));
        result.child(
            HologramEditorNativeTheme.label(() -> row.required)
                .width(54)
                .height(16));
        result.child(
            HologramEditorNativeTheme.label(() -> row.available)
                .width(54)
                .height(16));
        result.child(
            HologramEditorNativeTheme
                .label(() -> row.missing, row.shortage ? HologramEditorTheme.ERROR : HologramEditorNativeTheme.TEXT)
                .width(54)
                .height(16));
        result.tooltipBuilder(t -> {
            t.addLine(row.name);
            t.addLine(row.identity());
            t.addLine("需求 " + row.required + " / 已有 " + row.available + " / 缺失 " + row.missing + " " + row.unit);
        });
        return result;
    }

    private static IWidget options(String[] labels, String name) {
        Flow result = Flow.row()
            .widthRel(1f)
            .height(20)
            .childPadding(4)
            .name(name);
        for (String label : labels) result.child(
            HologramEditorNativeTheme.button(() -> label, () -> {})
                .widthRel(1f)
                .expanded()
                .height(20));
        return result;
    }

    int[] bounds(int width, int height) {
        return panel == null || !panel.isValid() ? null
            : new int[] { panel.getArea().x, panel.getArea().y, panel.getArea().width, panel.getArea().height };
    }

    /** The retired canvas no longer paints a second imitation of this native panel. */
    void draw(HologramUiModules ui) {}
}
