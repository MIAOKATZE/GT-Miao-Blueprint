package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.gui.Gui;

/** A single contextual instruction, with an optional reference sheet and independent lesson entries. */
final class HologramEditorGuide {

    private static final int BASE = HologramEditorControlIds.GUIDE;
    private final HologramUiModules ui;
    private boolean reference;

    HologramEditorGuide(HologramUiModules ui) {
        this.ui = ui;
    }

    private HologramEditorPreferences preferences() {
        return HologramEditorPreferences.get(ui.host.minecraft().mcDataDir);
    }

    void draw(int mx, int my) {
        if (ui.session.isTutorial()) return;
        int w = ui.canvas.layout()
            .width(),
            h = ui.canvas.layout()
                .height();
        int x = Math.max(8, (w - 450) / 2), y = h - 34;
        if (preferences().collapsed()) {
            ui.canvas.button(BASE, Math.max(8, w / 2 - 34), 3, 68, 17, "指引  v", true, mx, my);
            ui.canvas.button(BASE + 1, Math.max(80, w / 2 + 38), 3, 20, 17, "?", true, mx, my);
        } else {
            ui.canvas.skin("panel", x, y, 450, 30);
            ui.canvas.text(instruction(), x + 7, y + 4, 388, HologramEditorTheme.TEXT);
            ui.canvas.text(keys(), x + 7, y + 17, 388, HologramEditorTheme.MUTED);
            ui.canvas.button(BASE, x + 398, y + 5, 20, 19, "^", true, mx, my);
            ui.canvas.button(BASE + 1, x + 422, y + 5, 20, 19, "?", true, mx, my);
        }
        if (!reference) return;
        x = (w - 460) / 2;
        y = Math.max(54, (h - 242) / 2);
        Gui.drawRect(x - 3, y - 3, x + 463, y + 245, 0xc12b2118);
        ui.canvas.skin("panel", x, y, 460, 242);
        ui.canvas.text("操作说明与独立课程", x + 12, y + 10, 410, HologramEditorTheme.TITLE);
        String[] paragraphs = { "点一格选择；点结构轮廓整选；" + key("sameType") + " 扩展同类；Ctrl 点选增减。",
            key("moveHandles") + " 显示三轴和三平面；拖动整格吸附；Ctrl 起拖复制。", "结构参数按来源成员保存；具体设备保持合法候选、朝向与 NBT。",
            "实体结构可用仓室配额与命名搭配；作者蓝图不能套整机仓室预设。", "点库行切换中央草稿；未保存时沿用原提示；世界 ghost 先定位并锁定。", "材料核验只有一次最终确认；Esc 按当前面板层级返回。" };
        for (int i = 0; i < paragraphs.length; i++)
            ui.canvas.text(paragraphs[i], x + 12, y + 31 + i * 15, 436, HologramEditorTheme.TEXT);
        String[] names = { "投影教程", "蓝图教程", "剪切教程", "结构操作", "普通框选", "等距框选", "普通剪切", "等距剪切" };
        for (int i = 0; i < names.length; i++)
            ui.canvas.button(BASE + 2 + i, x + 12 + i % 4 * 110, y + 133 + i / 4 * 29, 104, 23, names[i], true, mx, my);
        ui.canvas.button(BASE + 1, x + 348, y + 208, 100, 22, "收起说明", true, mx, my);
    }

    String instruction() {
        if (ui.session.progress() != null) return "任务正在进行；完成后继续编辑或返回世界查看进度。";
        if (!ui.session.editable()) return "等待当前操作完成；原有任务和文件处理会继续。";
        if (ui.review.confirmation()) return "逐项核对真实需求、已有与缺失，确认后按当前规划施工。";
        if (ui.config.presetDrawer()) return "按本机声明配额生成目标；命名搭配保留具体设备与数量。";
        if (ui.config.hatchDrawer()) return "从此格合法候选选具体设备；类型与电压只筛选候选。";
        if (ui.config.drawer()) return "修改当前来源结构的能力参数，再应用；各结构单独保存。";
        if (ui.preview.inventory()) return "选择背包或已启用来源中的具体物品，再指定目标格。";
        if (ui.library.libraryDrawer()) return "单击库行在中央预览；编辑、世界放置与保存沿用库内入口。";
        if (ui.preview.moveHandles()) return "拖动轴线改变一轴，拖动平面同时改变两轴。";
        if (ui.preview.selected() >= 0) return "查看所选格的合法目标、默认壳与朝向；点轮廓选择完整结构。";
        return ui.session.state().data.getBoolean("virtual") ? "点击对象选择；从库载入蓝图或开始空白图文稿。" : "控制器锚定当前结构；先调整目标与来源，再核对材料。";
    }

    String keys() {
        if (ui.review.confirmation()) return "Esc 返回编辑  ·  确认沿用材料窗口按钮";
        if (ui.config.drawer() || ui.config.hatchDrawer() || ui.config.presetDrawer()) return "Esc 关窗  ·  ? 查看完整说明";
        return "Ctrl 增减选择  ·  " + key("sameType") + " 同类  ·  " + key("moveHandles") + " 手柄  ·  ? 说明";
    }

    private String key(String command) {
        return ui.tools.commands()
            .shortcut(command);
    }

    boolean activate(int id) {
        if (id == BASE) {
            preferences().collapsed(!preferences().collapsed());
            return true;
        }
        if (id == BASE + 1) {
            reference = !reference;
            return true;
        }
        if (id >= BASE + 2 && id <= BASE + 9) {
            reference = false;
            ui.tutorial.start(
                new String[] { "projection", "blueprint", "cut", "structure", "box-fps", "box-iso", "cut-fps",
                    "cut-iso" }[id - (BASE + 2)],
                true);
            return true;
        }
        return false;
    }
}
