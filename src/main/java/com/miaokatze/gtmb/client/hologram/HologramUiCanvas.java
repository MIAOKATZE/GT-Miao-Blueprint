package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

/** Internal canvas workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiCanvas {

    private final HologramUiModules ui;

    HologramUiCanvas(HologramUiModules ui) {
        this.ui = ui;
    }

    static final int DESIGN_W = HologramScreenLayout.WIDTH;

    static final int DESIGN_H = HologramScreenLayout.HEIGHT;

    private final List<Control> controls = new ArrayList<>();

    private final RenderItem itemRenderer = new RenderItem();

    static final int viewportX = HologramScreenLayout.VIEWPORT_X;

    static final int viewportY = HologramScreenLayout.VIEWPORT_Y;

    static final int viewportWidth = HologramScreenLayout.VIEWPORT_WIDTH;

    static final int viewportHeight = HologramScreenLayout.VIEWPORT_HEIGHT;

    static final int panelX = HologramScreenLayout.PANEL_X;

    static final int panelY = HologramScreenLayout.PANEL_Y;

    private boolean compactControls;

    private int originX;

    private int originY;

    private float scale = 1;

    private long renderGeneration;

    private List<String> tooltip;

    static final class Control {

        final int id, x, y, w, h;
        final boolean enabled;
        String label = "";
        int accent;

        Control(int id, int x, int y, int w, int h, boolean enabled) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.enabled = enabled;
        }

        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    int compactMouseX(int x) {
        return HologramUiCanvas.panelX
            + Math.round((x - HologramScreenLayout.COMPACT_PANEL_X) / HologramScreenLayout.CONTROL_WIDTH_SCALE);
    }

    void text(String value, int x, int y, int w, int color) {
        if (this.compactControls) {
            GL11.glPushMatrix();
            GL11.glTranslatef(x, 0, 0);
            GL11.glScalef(1 / HologramScreenLayout.CONTROL_WIDTH_SCALE, 1, 1);
            GL11.glTranslatef(-x, 0, 0);
        }
        try {
            ui.host.renderText(
                ui.host.font(),
                ui.host.font()
                    .trimStringToWidth(
                        value,
                        this.compactControls ? Math.round(w * HologramScreenLayout.CONTROL_WIDTH_SCALE) : w),
                x,
                y,
                color);
        } finally {
            if (this.compactControls) GL11.glPopMatrix();
        }
    }

    void skin(String name, int x, int y, int w, int h) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glColor4f(1, 1, 1, 1);
            ui.host.minecraft()
                .getTextureManager()
                .bindTexture(new ResourceLocation("gtmb", "textures/gui/pocket/POCKET_C2_" + name + ".png"));
            func_152125_a(x, y, 0, 0, 1, 1, w, h, 1, 1);
        } finally {
            GL11.glPopAttrib();
        }
    }

    void button(int id, int x, int y, int w, int h, String label, boolean enabled, int mx, int my) {
        Control c = new Control(id, x, y, w, h, enabled);
        Control drawn = c;
        if (this.compactControls) drawn = new Control(
            id,
            HologramScreenLayout.COMPACT_PANEL_X
                + Math.round((x - HologramUiCanvas.panelX) * HologramScreenLayout.CONTROL_WIDTH_SCALE),
            y,
            Math.round(w * HologramScreenLayout.CONTROL_WIDTH_SCALE),
            h,
            enabled);
        this.controls.add(drawn);
        ui.canvas.skin(enabled && c.contains(mx, my) ? "btn_pressed" : "btn", x, y, w, h);
        int accent = ui.ae.aeReview() != null ? HologramGuidance.modalAccent(id, 181, 182)
            : !ui.library.deletePresetId()
                .isEmpty()
                    ? HologramGuidance.modalAccent(id, 176, 177)
                    : ui.nbt.nbtDrawer() ? HologramGuidance.modalAccent(id, 173, 172)
                        : ui.annotations.promptDrawer() ? HologramGuidance.modalAccent(id, 158, 151)
                            : ui.annotations.versionsDrawer() ? HologramGuidance.modalAccent(id, 160, 151)
                                : ui.config.resetReview() ? HologramGuidance.modalAccent(id, 137, 136)
                                    : HologramGuidance.accent(
                                        ui.session.state(),
                                        id,
                                        ui.preview.selected(),
                                        ui.library.libraryDrawer(),
                                        ui.review.confirmation(),
                                        ui.library.selectedPresetExists());
        if (ui.bindings.networkDrawer()) accent = HologramNetworkBindingNavigation.accent(
            id,
            ui.session.state().data,
            ui.bindings.networkGroupIndex(),
            ui.session.pendingSequence() >= 0 || ui.bindings.networkBindingNavigation()
                .pending(),
            ui.bindings.networkRebindSelected());
        else if (ui.processing.processingFlow() != null) accent = HologramGuidance.modalAccent(id, 231, 234);
        else if (ui.review.confirmation() && (id == 169 || id == 55)
            && HologramPlacementConfirmation.cacheResources(ui.session.state().data)
                .tagCount() > 0)
            accent = HologramPlacementConfirmation.emptyCacheFallback(ui.session.state().data) ? 0xffce86
                : id == 169 ? 0x68dded : accent;
        else if (ui.library.discardReview() || ui.library.overwriteReview()
            || ui.processing.processingPresetSaveReview()) accent = HologramGuidance.modalAccent(id, 224, 225);
        else if (ui.ae.aeReview() == null && !ui.nbt.nbtDrawer()
            && !ui.annotations.promptDrawer()
            && !ui.annotations.versionsDrawer()
            && !ui.review.confirmation()
            && !ui.config.resetReview()) {
                int contextual = ui.memory.contextualAccent(id);
                if (contextual != 0 || id == 50 || id == 146 || id == 141) accent = contextual;
            }
        if (id == 150 && ui.session.state().data.getBoolean("sourceMissing")
            || id == 163 && ui.session.selectedCell() != null && ui.session.selectedCell().sourceMissing)
            accent = 0xff7272;
        drawn.label = label;
        drawn.accent = enabled ? accent : 0;
        if (enabled && accent != 0) {
            int border = 0xff000000 | accent;
            drawRect(x, y, x + w, y + 1, border);
            drawRect(x, y + h - 1, x + w, y + h, border);
            drawRect(x, y, x + 1, y + h, border);
            drawRect(x + w - 1, y, x + w, y + h, border);
        }
        if (this.compactControls) {
            GL11.glPushMatrix();
            GL11.glTranslatef(x + w / 2F, 0, 0);
            GL11.glScalef(1 / HologramScreenLayout.CONTROL_WIDTH_SCALE, 1, 1);
            GL11.glTranslatef(-x - w / 2F, 0, 0);
        }
        try {
            ui.host.renderCenteredText(
                ui.host.font(),
                ui.host.font()
                    .trimStringToWidth(
                        label,
                        (this.compactControls ? Math.round(w * HologramScreenLayout.CONTROL_WIDTH_SCALE) : w) - 6),
                x + w / 2,
                y + (h - 8) / 2,
                enabled ? accent == 0 ? 0xf0ddb3 : accent : 0x647c7f);
        } finally {
            if (this.compactControls) GL11.glPopMatrix();
        }
        if (c.contains(mx, my)) {
            String help = ui.canvas.controlHelp(id);
            if (help != null) {
                this.tooltip = new ArrayList<>(
                    ui.host.font()
                        .listFormattedStringToWidth(help, 230));
                if (!enabled) this.tooltip.add(ui.canvas.disabledReason(id));
            }
        }
    }

    String disabledReason(int id) {
        if ((id == 140 || id == 132 || id == 166 || id == 221) && !ui.processing.processingReady())
            return "先完成蓝图处理流程，服务端确认后开放保存与方块 NBT 编辑。";
        if (id == 179) return "请单选一个已支持的 AE 方块，并等待当前编辑回执。";
        if (!ui.session.editable() && id != 51 && id != 52) return ui.session.editingReason();
        if (id == 162) return "当前未注册 AE 下单适配器。";
        if (id == 65 && ui.session.selectedCell() != null) {
            if (!ui.preview.canInventoryPin()) return "控制器、已有受保护设备或拆除模式不能指定新目标；仓室数据不迁移，服务端验证替换安全性。";
        }
        if (id == 60 && ui.session.state().data.getBoolean("virtual")) return "先点结构轮廓，再打开结构编辑以设置此结构等级和信道。";
        if (id == 139) return "先在列表中选择要编辑的预设。";
        if (id == 140 || id == 132) return "先导入区域或载入蓝图以保存当前蓝图。";
        if (id == 175) return "先在蓝图库列表选择要删除的条目。";
        if ((id == 50 || id == 135) && ui.session.state().data.getBoolean("virtual")
            && !ui.session.state().data.getBoolean("virtualPlacement")) return "仅编辑模板；请关闭编辑后 Alt + 右击启动投影，再锁定位置施工。";
        if (id == 50 && ui.session.state().data.getBoolean("virtual")
            && !ui.session.state().data.getBoolean("virtualLocked")) return "请先锁定虚拟模板，再执行放置。";
        if (id == 66) return ui.session.selectedCell() == null ? "先选择预览格。" : "控制器锚点不能恢复为外壳。";
        if (id == 69) return "当前没有选中预览格。";
        if (id == 95) return "先选择有仓室候选或可调整朝向的预览格；拆除模式无需配置目标。";
        if (id == 99) return "本格没有可安全修改的仓室朝向。";
        if (id == 102) return "本格没有单独指定的朝向，无需恢复。";
        if (id == 106) return HologramUiText.reason(ui.session.state().data.getString("presetReason"));
        if (id == 107) return "当前没有启用整机仓室预设，无需撤销。";
        if (id == 103 || id == 104) return "已到达合法仓室等级边界。";
        if (id == 93 && ui.session.selectedCell() != null && ui.session.selectedCell().family.isEmpty())
            return "本格没有可安全识别的同类部件，请使用单格范围。";
        if (id == 61 && ui.session.state().data.getInteger("mode") == 2) return "拆除模式无需指定新方块；切换补建或替换后可选材。";
        if (id == 50 && ui.session.state().data.getInteger("scope") >= 2 && ui.session.selectedCell() == null)
            return "选中格或同类部件范围需要先选择预览格。";
        if (id == 92 || id == 93 || id == 65)
            return ui.session.selectedCell() == null ? "先点击左侧预览选位，或添加坐标。" : "先从背包选择目标物品。";
        if (id == 83) return "此结构没有已验证的仓室自动选材能力。";
        if (id == 55) return ui.session.state().data.getInteger("missing") > 0 ? "材料不足：请补充背包后重新采集。" : "材料与改动已更新，请重新查看。";
        if (id == 51 || id == 52) return ui.session.pendingSequence() >= 0 ? "等待服务端回执。" : "没有可暂停或取消的施工任务。";
        if (id == 12 || id == 13) return "已到达结构图层边界。";
        if (id == 80 || id == 82) return "已到达当前已验证目标列表边界。";
        return "此操作不支持当前目标或状态。";
    }

    String controlHelp(int id) {
        switch (id) {
            case 220:
                return "按步骤选择流程预设、去标识、量子绑定、坐标、全局 NBT 和自动提示；每步明确选择后可继续。";
            case 221:
                return "保存本次已完成的处理选择，下次导入蓝图时复用；应用预设后仍逐步核对。";
            case 222:
                return "勾选后列表只选择覆盖目标，不加载或切换蓝图。保存时会再次显示目标名称并确认覆盖。";
            case 223:
                return "仅旋转此处的查看视角；拖动预览也可旋转查看，不改变世界施工方向。";
            case 159:
                return "导出可分享的 JSON 文件，方便交给其他玩家导入；文件名使用上方名称，完成消息显示实际路径。";
            case 161:
                return "读取 JSON 分享文件；在上方输入文件名。导入后可编辑、处理并另存为蓝图。";
            case 179:
                return "打开自有 AE 标记容器；NEI 拖入、背包样本仅修改蓝图标记。";
            case 178:
                return "开始世界框选：左键第一角、右键第二角，Alt 使用玩家位置，再次 Shift+右击完成，Esc 取消。";
            case 163:
                return ui.session.selectedCell() != null && ui.session.selectedCell().sourceMissing
                    ? "来源结构缺失：" + ui.session.selectedCell().sourceWarning
                        + " · "
                        + ui.session.selectedCell().sourceClass
                    : "打开当前选中结构的编辑；区域中的其他成员仍保留。";
            case 162:
                return "点击后将当前缺料清单交给已注册的 AE 下单适配器；下单不会自动执行施工。";
            case 130:
                return "前往蓝图设置：处理当前蓝图、编辑 NBT 与提示词，然后保存或导入 JSON 分享文件。";
            case 132:
                return "按名称将当前完整目标规划另存为新预设；原预设保持不变，不执行施工。";
            case 139:
                return "将所选预设载入虚拟编辑预览，可修改仓室、目标和朝向；完成后使用更新原预设保存。";
            case 140:
                return "保存当前蓝图；先完成处理流程。勾选覆盖保存后可选择目标并确认覆盖。";
            case 175:
                return "删除所选蓝图库条目；先核对名称并确认，当前编辑蓝图保留。";
            case 134:
                return ui.session.state().data.getBoolean("structureBuilt")
                    ? "识别可确认的现场材质信道和诸神锻炉环数，保留独立仓室及朝向；校准后仍可改线圈等信道。未识别的尺寸和原始信道保留。不施工，会覆盖已有自定义目标。"
                    : "恢复结构默认目标并清除本投影仪蓝图。仅改变规划，不回滚世界方块，也不删除已存预设。";
            case 137:
                return ui.config.resetCalibration() ? "用当前现场校准规划，覆盖已有自定义目标；服务端重新校验，不执行施工。"
                    : "恢复结构默认目标并清除本投影仪蓝图。仅改变规划，不回滚世界方块，也不删除已存预设。";
            case 135:
                return "锁定当前虚拟模板后才允许放置；锁定本身不执行施工。";
            case 138:
                return "设置材料来源、取料优先级、缺料降级、所选模板和默认回收去向。";

            case 70:
            case 71:
            case 72:
                return "选择补建、替换或拆除；主界面数字键 1 / 2 / 3 也可切换。切换模式不会直接施工。";
            case 108:
            case 109:
                return "撤销 / 重做仅改变规划，不回滚已施工的世界方块。";
            case 112:
                return "复制本格目标；仅复制规划，不复制设备库存或消耗材料。";
            case 113:
                return "在服务端允许的方向中旋转目标，应用替换后生效。";
            case 10:
                return "切换目标结构、真实现场和施工差分。";
            case 11:
                return "切换所有图层与当前单层预览；点击方块、T 同种、Ctrl 多选或 Shift 框选编辑范围。";
            case 12:
            case 13:
                return "查看相邻图层。";
            case 15:
                return "显示或隐藏世界中的材质预览。";
            case 26:
                return "可选：重新核对材料与改动，不执行施工。";
            case 30:
                return "高级主原始信号；未显式指定的子信道继承它。修改后更新预览。";
            case 33:
                return "只放外壳：可回退位置使用外壳，固定接口仍需手动补齐。允许仓室：服务端可验证仓室候选；不保证机器成型。";
            case 50:
                return HologramPlacementConfirmation.forceReview(ui.session.state().data, ui.review.confirmedRevision())
                    ? "重新打开本次缺料复核；保留服务端的二次确认，不重新扫描。"
                    : "先查看当前材料与改动，再明确确认施工。";
            case 51:
                return "暂停或继续当前施工；暂停保留已完成方块。";
            case 52:
                return "停止任务，保留已完成方块与材料消耗，不撤销施工。";
            case 55:
                if (HologramPlacementConfirmation.emptyCacheFallback(ui.session.state().data))
                    return "缓存余额不足，本次将以空缓存放置；点击此按钮明确接受。若需要保留缓存，请返回补足能源来源后重新核对。";
                return HologramPlacementConfirmation.forceReview(ui.session.state().data, ui.review.confirmedRevision())
                    ? "再次确认后仅放置现有材料能够满足的方块；缺口保持未放置，完成统计来自服务端。"
                    : HologramPlacementConfirmation.lockedBlueprint(ui.session.state().data)
                        && ui.session.state().data.getInteger("missing") > 0 ? "首次确认将请求服务端复核缺口；缺料时需再次明确确认仅放已有。"
                            : "按当前确认的规划施工；完成统计来自服务端回执。";
            case 60:
                return "查看具名目标与原始值，可手填任意合法信道；滚轮查看完整列表。";
            case 61:
                return "打开真实36格背包；点击或拖动复制目标，物品不会被移动或消耗。";
            case 75:
                return "按世界轴填写相对控制器的坐标，添加后自动选择单格。";
            case 78:
                return "选择从主背包、背包内容器与 ME 终端取材，并设置优先来源。";
            case 65:
                return "将选中的背包物品指定给当前预览格；可指定任意可放置方块或仓室，服务端验证替换安全性。";
            case 66:
                return "恢复结构默认壳；Delete 同效。只改变规划，已建仓室需点击应用替换。取消格选择单独保留目标。";
            case 69:
                return "仅取消预览格选择，保留已指定目标；也可右键预览或按 Esc。";
            case 80:
            case 81:
            case 82:
                return "修改当前部件的合法目标等级，仅更新目标。";
            case 83:
                return "仓室手选：从候选或背包指定目标。自动选材：允许结构自身选择仓室材料，不保证数量与成型配额。";
            case 90:
                return "施工范围：整个结构中可安全执行的差分格。";
            case 91:
                return "施工范围：当前显示图层的差分格。";
            case 92:
                return "施工范围：当前选中的一个格。";
            case 93:
                return "施工范围：与选中格属于同一已识别部件家族的格；未知部件仍保留保护。";
            case 94:
                return "按结构声明的仓室种类和数量生成空位目标，可选等级及缺料降级策略。保留手动目标和已有仓室。";
            case 95:
                return "主动查看本格可替换的方块或仓室；等级按钮始终保留当前部件和选区。";
            case 97:
                return "打开类型选框，含全部类型；选择后筛选候选，点击具体候选才指定目标。";
            case 98:
                return "恢复全部等级筛选；减号和加号按该类型真实可用等级逐级选择，不循环或切换其他类型。";
            case 117:
            case 118:
                return "按真实可用等级逐级选择；选定具体类型时更新同类型目标，全部类型时只筛选候选。";
            case 99:
                return "在本格合法方向中循环指定仓室朝向；应用施工时生效。";
            case 102:
                return "清除本格独立朝向设置，恢复自动方向规则。";
            case 103:
            case 104:
                return "预设仓室的独立电压等级，不修改线圈、玻璃或其它结构等级。";
            case 105:
                return "严格等级：缺料时保留缺料目标；允许降级：按已启用来源选择较低等级。";
            case 106:
                return "按声明配额生成预设目标，不执行施工；随后检查差分与材料并点击施工按钮。";
            case 107:
                return "撤销整机预设目标；保留手选目标、已有仓室与实际施工结果。";
            default:
                return null;
        }
    }

    void drawItem(ItemStack stack, int x, int y) {
        if (stack == null) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        float previousZ = this.itemRenderer.zLevel;
        try {
            this.itemRenderer.zLevel = 0;
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            RenderHelper.enableGUIStandardItemLighting();
            this.itemRenderer.renderItemAndEffectIntoGUI(
                ui.host.font(),
                ui.host.minecraft()
                    .getTextureManager(),
                stack,
                x,
                y);
            this.itemRenderer.renderItemOverlayIntoGUI(
                ui.host.font(),
                ui.host.minecraft()
                    .getTextureManager(),
                stack,
                x,
                y);
        } finally {
            this.itemRenderer.zLevel = previousZ;
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    int localX(int x) {
        return (int) ((x - this.originX) / this.scale);
    }

    int localY(int y) {
        return (int) ((y - this.originY) / this.scale);
    }

    boolean inViewport(int x, int y) {
        return x >= HologramUiCanvas.viewportX && x < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth
            && y >= HologramUiCanvas.viewportY
            && y < HologramUiCanvas.viewportY + HologramUiCanvas.viewportHeight;
    }

    boolean inPanel(int x, int y) {
        return x >= HologramScreenLayout.COMPACT_PANEL_X
            && x < HologramScreenLayout.COMPACT_PANEL_X + HologramScreenLayout.COMPACT_PANEL_WIDTH
            && y >= HologramUiCanvas.panelY
            && y < HologramUiCanvas.panelY + HologramScreenLayout.PANEL_HEIGHT;
    }

    boolean compactControls(boolean value) {
        this.compactControls = value;
        return value;
    }

    List<Control> controls() {
        return this.controls;
    }

    int originX() {
        return this.originX;
    }

    int originX(int value) {
        this.originX = value;
        return value;
    }

    int originY() {
        return this.originY;
    }

    int originY(int value) {
        this.originY = value;
        return value;
    }

    long renderGeneration() {
        return this.renderGeneration;
    }

    long incrementRenderGeneration(int delta, boolean postfix) {
        long before = this.renderGeneration;
        this.renderGeneration += delta;
        return postfix ? before : this.renderGeneration;
    }

    float scale() {
        return this.scale;
    }

    float scale(float value) {
        this.scale = value;
        return value;
    }

    List<String> tooltip() {
        return this.tooltip;
    }

    List<String> tooltip(List<String> value) {
        this.tooltip = value;
        return value;
    }

    void resize() {
        HologramScreenLayout layout = HologramScreenLayout.fit(ui.host.screenWidth(), ui.host.screenHeight());
        ui.canvas.scale(layout.scale);
        ui.canvas.originX(layout.originX);
        ui.canvas.originY(layout.originY);
    }
}
