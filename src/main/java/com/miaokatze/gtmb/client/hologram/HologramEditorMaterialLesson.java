package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.gui.Gui;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.opengl.GL11;

/** Passive teaching view of the production material-review model and its 632 x 446 layout. */
final class HologramEditorMaterialLesson {

    private final HologramMaterialReview materials = new HologramMaterialReview();
    private NBTTagCompound data;
    private double left, top, scale;

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
    }

    int[] bounds(int width, int height) {
        scale = Math.max(.25, Math.min(1, Math.min((width - 30) / 632d, (height - 168) / 446d)));
        left = (width - 632 * scale) / 2;
        top = Math.max(42, (height - 138 - 446 * scale) / 2);
        return new int[] { (int) left, (int) top, (int) Math.ceil(632 * scale), (int) Math.ceil(446 * scale) };
    }

    void draw(HologramUiModules ui) {
        if (data == null) return;
        bounds(
            ui.canvas.layout()
                .width(),
            ui.canvas.layout()
                .height());
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(left, top, 0);
            GL11.glScaled(scale, scale, 1);
            ui.canvas.skin("panel", 0, 0, 632, 446);
            ui.canvas.text("蓝图放置预检 · 隔离教学示例", 14, 14, 604, HologramEditorTheme.TITLE);
            ui.canvas.text(
                "整图范围 · 示例锁定位置 " + data.getInteger("x") + ", " + data.getInteger("y") + ", " + data.getInteger("z"),
                14,
                38,
                604,
                HologramEditorTheme.TEXT);
            ui.canvas.text("示例库存：进入教程时快照，仅用于说明；教程未校验背包、ME 或世界位置。", 14, 55, 604, HologramEditorTheme.MUTED);
            ui.canvas.text("材料 / 精确 NBT 条目", 49, 77, 220, HologramEditorTheme.TITLE);
            ui.canvas.text("需求", 277, 77, 90, HologramEditorTheme.TITLE);
            ui.canvas.text("已有", 391, 77, 90, HologramEditorTheme.TITLE);
            ui.canvas.text("缺失", 505, 77, 90, HologramEditorTheme.ERROR);
            Gui.drawRect(14, 96, 618, 294, 0x492e2318);
            for (int i = 0; i < Math.min(4, materials.size()); i++) {
                HologramMaterialReview.Row row = materials.row(i);
                int y = 101 + i * 44;
                Gui.drawRect(21, y, 605, y + 41, (i & 1) == 0 ? 0x31f3d4a0 : 0x1d624326);
                if (row.item != null) ui.canvas.drawItem(row.item, 28, y + 5);
                ui.canvas.text(row.name, 49, y + 4, 212, HologramEditorTheme.TEXT);
                ui.canvas
                    .text(row.identity() + (row.tagged ? " · 精确 NBT" : ""), 49, y + 20, 212, HologramEditorTheme.MUTED);
                ui.canvas.text(row.required, 277, y + 13, 107, HologramEditorTheme.TEXT);
                ui.canvas.text(row.available, 391, y + 13, 107, HologramEditorTheme.TEXT);
                ui.canvas.text(
                    row.missing,
                    505,
                    y + 13,
                    100,
                    row.shortage ? HologramEditorTheme.ERROR : HologramEditorTheme.PAPER_NEXT);
            }
            if (materials.size() == 0) {
                ui.canvas.text("当前示例快照尚无精确材料清单", 29, 121, 560, HologramEditorTheme.TEXT);
                ui.canvas.text("真实材料清单由服务端提供；未回传数量不记作零库存。", 29, 145, 560, HologramEditorTheme.MUTED);
            }
            ui.canvas.text(materials.size() + " 项精确材料 · 生产窗口可滚动逐项查看身份与完整数量", 14, 299, 604, HologramEditorTheme.TEXT);
            String cache = HologramPlacementConfirmation.cacheSummary(data);
            ui.canvas.text(cache.isEmpty() ? "设备缓存与配置材料按真实清单分别核算" : cache, 14, 316, 604, HologramEditorTheme.MUTED);
            button(ui, 14, 340, 92, "本次 NBT", false);
            button(ui, 112, 340, 116, "量子网络绑定", false);
            button(ui, 234, 340, 160, "AE 补齐本次缺料", false);
            button(ui, 400, 340, 218, "猫猫装饰抵用券", false);
            button(ui, 14, 369, 102, "去标识设置", false);
            button(ui, 122, 369, 102, "移除阻塞物", false);
            button(ui, 230, 369, 102, "数据校验详情", false);
            button(ui, 338, 369, 132, "刷新材料", false);
            button(ui, 476, 369, 142, "返回世界", false);
            button(ui, 14, 402, 160, "取消放置", false);
            button(ui, 184, 402, 434, "最终确认 · 此处仅演示，不执行施工", true);
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static void button(HologramUiModules ui, int x, int y, int width, String text, boolean emphasis) {
        HologramEditorTheme.button(true, false, false)
            .draw(x, y, width, 23);
        ui.canvas.text(text, x + 5, y + 7, width - 10, HologramEditorTheme.BUTTON_TEXT);
        if (emphasis) HologramEditorTheme.drawGuidanceFrame(x, y, width, 23, HologramUiAccent.NEXT, true);
    }
}
