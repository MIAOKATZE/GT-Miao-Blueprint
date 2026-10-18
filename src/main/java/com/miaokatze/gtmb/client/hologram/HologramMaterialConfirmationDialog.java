package com.miaokatze.gtmb.client.hologram;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.IParentWidget;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Platform;
import com.cleanroommc.modularui.widget.AbstractScrollWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;

/** Independent MUI2 material review, with framework scrolling/clipping and bounded visible-row rendering. */
final class HologramMaterialConfirmationDialog {

    private static final int WIDTH = 632, HEIGHT = 446, ROW_HEIGHT = 44, LIST_HEIGHT = 198;
    private static final int LIST_WIDTH = 604, ROW_INSET = 5, ROW_WIDTH = LIST_WIDTH - ROW_INSET * 2;
    private final HologramUiModules ui;
    private final HologramMaterialReview materials = new HologramMaterialReview();
    private final IPanelHandler handler;
    private final VerticalScrollData scroll = new VerticalScrollData();
    private MaterialList list;
    private RowsCanvas canvas;
    private int hoveredRow = -1;
    private long drawnFrames;
    private int panelBuilds, listResizes;
    private final RenderItem iconRenderer = new RenderItem();
    private final Map<Integer, BooleanSupplier> enabledControls = new HashMap<>();
    private final Map<Integer, IntSupplier> controlAccents = new HashMap<>();

    boolean controlEnabled(int id) {
        BooleanSupplier enabled = enabledControls.get(id);
        return isOpen() && drawnFrames > 0 && enabled != null && enabled.getAsBoolean();
    }

    HologramMaterialConfirmationDialog(HologramUiModules ui) {
        this.ui = ui;
        handler = IPanelHandler.simple(
            ui.host.parent()
                .panel(),
            (panel, player) -> build(),
            true);
        scroll.setScrollSpeed(ROW_HEIGHT);
    }

    void tick(boolean show) {
        if (materials.update(state()) && canvas != null) {
            listResizes++;
            canvas.height(Math.max(LIST_HEIGHT, materials.size() * ROW_HEIGHT))
                .scheduleResize();
            scroll.setScrollSize(Math.max(LIST_HEIGHT, materials.size() * ROW_HEIGHT));
            scroll.clamp(list.getScrollArea());
            list.scheduleResize();
        }
        if (show && !handler.isPanelOpen()) handler.openPanel();
        else if (!show && handler.isPanelOpen()) handler.closePanel();
    }

    boolean isOpen() {
        return handler.isPanelOpen();
    }

    void close() {
        if (isOpen()) handler.closePanel();
    }

    int[] controlPoint(int id) {
        if (!isOpen() || drawnFrames == 0) return null;
        int[] bounds = switch (id) {
            case 54 -> new int[] { 14, 402, 160 };
            case 55 -> new int[] { 184, 402, 434 };
            case 162 -> new int[] { 234, 340, 160 };
            case 168 -> new int[] { 14, 340, 92 };
            case 196 -> new int[] { 112, 340, 116 };
            case 211 -> new int[] { 14, 369, 102 };
            case 215 -> new int[] { 122, 369, 102 };
            case 184 -> new int[] { 230, 369, 102 };
            case 245 -> new int[] { 400, 340, 218 };
            case 246 -> new int[] { 476, 369, 142 };
            case 247 -> new int[] { 338, 369, 132 };
            default -> null;
        };
        if (bounds == null) return null;
        int w = ui.host.parent()
            .getScreen()
            .getScreenArea().width;
        int h = ui.host.parent()
            .getScreen()
            .getScreenArea().height;
        float scale = Math.min(1f, Math.min(w / (float) (WIDTH + 12), h / (float) (HEIGHT + 12)));
        return new int[] { Math.round((w - WIDTH * scale) / 2f + (bounds[0] + bounds[2] / 2f) * scale),
            Math.round((h - HEIGHT * scale) / 2f + (bounds[1] + 11.5f) * scale) };
    }

    NBTTagCompound probeState() {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("open", isOpen());
        result.setInteger("rows", materials.size());
        result.setInteger("scroll", scroll.getScroll());
        result.setLong("drawnFrames", drawnFrames);
        result.setInteger("drawRowBound", LIST_HEIGHT / ROW_HEIGHT + 2);
        result.setBoolean("orderActive", orderActive());
        result.setBoolean("voucherEnabled", voucherEnabled());
        result.setBoolean("voucherSelected", voucherSelected());
        result.setBoolean("quotePending", ui.session.materialReviewPending());
        result.setInteger("panelBuilds", panelBuilds);
        result.setInteger("listResizes", listResizes);
        result.setInteger("listIdentity", System.identityHashCode(list));
        result.setInteger("canvasIdentity", System.identityHashCode(canvas));
        result.setInteger("cellsIdentity", System.identityHashCode(ui.session.state().cells));
        result.setInteger("firstRowIdentity", System.identityHashCode(materials.row(0)));
        result.setInteger("cacheSummaryColor", ui.review.lastCacheReviewColor());
        result.setString("cacheSummary", ui.review.lastCacheReviewSummary());
        for (Map.Entry<Integer, IntSupplier> entry : controlAccents.entrySet()) result.setInteger(
            "accent" + entry.getKey(),
            entry.getValue()
                .getAsInt());
        return result;
    }

    boolean consumeKey(int key) {
        if (!isOpen()) return false;
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) {
            if (orderActive()) ui.router.closeRoot();
            else {
                ui.review.confirmation(false);
                close();
            }
        }
        // Enter never silently confirms a material transaction; the green button is explicit.
        return true;
    }

    private NBTTagCompound state() {
        return ui.session.state().data;
    }

    private boolean orderActive() {
        return state().getCompoundTag("materialOrder")
            .getBoolean("active") || HologramBackgroundTasks.materialTask();
    }

    private boolean editable() {
        return ui.session.editable() && !state().getBoolean("localPlanPending") && !orderActive();
    }

    private boolean voucherEnabled() {
        return state().getCompoundTag("decorativeVoucher")
            .getBoolean("enabled");
    }

    private boolean voucherSelected() {
        return ui.session.materialVoucherSelected();
    }

    private void action(int id) {
        close();
        ui.router.activate(id);
    }

    private ModularPanel build() {
        panelBuilds++;
        MaterialPanel panel = new MaterialPanel();
        panel.size(WIDTH, HEIGHT)
            .background(HologramMuiTheme.CLOTH);
        HologramMuiTheme.header(panel, () -> ui.review.lastConfirmationTitle(), WIDTH);
        panel.child(
            HologramMuiTheme
                .label(() -> ui.review.constructionScopeLabel(true), 14, 38, 604, 15, HologramMuiTheme.MUTED));
        panel.child(
            HologramMuiTheme.label(this::reviewHint, 14, 55, 604, 15, HologramMuiTheme.MUTED)
                .tooltipBuilder(t -> {
                    t.addLine("控制器库存仅查看，不计入已有。");
                    t.addLine(
                        state().getCompoundTag("materialQuote")
                            .getString("validation"));
                })
                .tooltipAutoUpdate(true));
        panel
            .child(HologramMuiTheme.label(() -> "材料 / 精确 NBT 条目", 42 + ROW_INSET, 77, 220, 15, HologramMuiTheme.TITLE));
        panel.child(HologramMuiTheme.label(() -> "需求", 270 + ROW_INSET, 77, 110, 15, HologramMuiTheme.TITLE));
        panel.child(HologramMuiTheme.label(() -> "已有", 384 + ROW_INSET, 77, 110, 15, HologramMuiTheme.TITLE));
        panel.child(HologramMuiTheme.label(() -> "缺失", 498 + ROW_INSET, 77, 110, 15, HologramUiAccent.WARNING));
        list = new MaterialList().pos(14, 96)
            .size(LIST_WIDTH, LIST_HEIGHT)
            .background(HologramMuiTheme.PANEL);
        // Center the rows inside the frame; the right gutter also accommodates MUI's scrollbar.
        canvas = new RowsCanvas().pos(ROW_INSET, 0)
            .size(ROW_WIDTH, Math.max(LIST_HEIGHT, materials.size() * ROW_HEIGHT));
        canvas.tooltipBuilder(t -> {
            HologramMaterialReview.Row row = materials.row(getHoveredRow());
            if (row == null) return;
            t.addLine(row.name);
            t.addLine("需求 " + row.required + " / 已有 " + row.available + " / 缺失 " + row.missing + " " + row.unit);
            t.addLine(row.identity());
            if (row.item != null) for (Object line : row.item.getTooltip(Minecraft.getMinecraft().thePlayer, false))
                t.addLine(String.valueOf(line));
            t.addLine("清单条目 #" + (row.index + 1) + (row.tagged ? " · 精确 NBT，独立计费" : ""));
            if (row.data.getBoolean("voucherEligible")) t.addLine("本次可使用装饰抵用券抵扣");
            if (!row.data.getString("nbtKey")
                .isEmpty()) t.addLine("设备资源：" + row.data.getString("nbtKey"));
        })
            .tooltipAutoUpdate(true);
        list.child(canvas);
        panel.child(list);
        panel.child(
            HologramMuiTheme.label(this::status, 14, 299, 604, 16, HologramMuiTheme.TEXT)
                .tooltipBuilder(t -> t.addLine(status()))
                .tooltipAutoUpdate(true));
        panel.child(
            HologramMuiTheme.label(ui.review::lastCacheReviewSummary, 14, 316, 604, 16, HologramMuiTheme.TEXT)
                .color(ui.review::lastCacheReviewColor)
                .tooltipBuilder(t -> {
                    net.minecraft.nbt.NBTTagList rows = HologramPlacementConfirmation.cacheResources(state());
                    for (int i = 0; i < rows.tagCount(); i++) {
                        NBTTagCompound row = rows.getCompoundTagAt(i);
                        t.addLine(
                            row.getString("name") + "：需求 "
                                + row.getString("required")
                                + " / 已有 "
                                + row.getString("available"));
                    }
                    if (HologramPlacementConfirmation.emptyCacheFallback(state())) t.addLine("确认表示接受空缓存放置结果。");
                })
                .tooltipAutoUpdate(true));
        panel.child(
            new OrderProgress().pos(14, 333)
                .size(604, 4));
        panel.child(
            button(
                168,
                () -> "本次 NBT",
                14,
                340,
                92,
                () -> action(168),
                () -> editable() && state().getBoolean("virtual") && ui.nbt.hasPlacementNbtChoices(),
                () -> 0));
        panel.child(
            button(
                196,
                () -> "量子网络绑定",
                112,
                340,
                116,
                () -> action(196),
                () -> editable() && ui.bindings.networkGroups()
                    .tagCount() > 0,
                () -> HologramPlacementConfirmation.networksReady(state()) ? 0 : HologramUiAccent.WARNING));
        panel.child(
            button(
                162,
                () -> orderActive() ? "取消本次 AE 订单" : "AE 补齐本次缺料",
                234,
                340,
                160,
                this::order,
                () -> orderActive() || editable() && !ui.session.materialReviewPending()
                    && state().getBoolean("materialOrderingAvailable")
                    && materials.hasItemShortage(),
                () -> orderActive() ? HologramUiAccent.WARNING : recommendOrder() ? HologramUiAccent.SUGGESTION : 0)
                    .tooltipBuilder(
                        t -> {
                            t.addLine(orderActive() ? "只取消本次订单；已提交的 AE 合成由服务器按能力撤销。" : "仅对本次清单中可合成的缺料下单；不可合成项会明确报告。");
                        }));
        panel.child(
            button(
                245,
                this::voucherLabel,
                400,
                340,
                218,
                this::voucher,
                () -> editable() && HologramPlacementConfirmation
                    .voucherChoiceAvailable(state(), hasVoucherDemand(), voucherSelected()),
                () -> voucherSelected() ? HologramUiAccent.SUGGESTION : 0).tooltipBuilder(t -> {
                    t.addLine(
                        state().getCompoundTag("materialQuote")
                            .getBoolean("creativePaymentsFree") ? "创造模式可切换并核查抵扣方案；施工不消耗抵用券。"
                                : "使用 1 张抵本次建筑 Mod 材料；工业设备、内部资源和封存物品仍按清单取料。");
                }));
        panel.child(
            button(
                211,
                () -> "去标识设置",
                14,
                369,
                102,
                () -> action(211),
                () -> editable() && ui.identity.hasPlacementIdentityChoices(),
                () -> 0));
        panel.child(
            button(
                215,
                () -> "移除阻塞物",
                122,
                369,
                102,
                () -> action(215),
                () -> editable() && ui.review.materialBlockers() > 0,
                () -> ui.review.materialBlockers() > 0 ? HologramUiAccent.WARNING : 0));
        panel.child(button(184, () -> "数据校验详情", 230, 369, 102, () -> action(184), () -> !orderActive(), () -> 0));
        panel.child(
            button(247, () -> "刷新材料", 338, 369, 132, ui.session::refreshMaterialReview, this::editable, () -> 0)
                .tooltipBuilder(t -> t.addLine("重新检查本次材料库存；确认施工或提交 AE 时再次核对。")));
        panel.child(
            button(246, () -> "返回世界 · 后台继续", 476, 369, 142, () -> ui.router.closeRoot(), this::orderActive, () -> 0));
        panel.child(
            button(
                54,
                () -> ui.review.placementReviewActive() ? "取消放置" : "返回预览",
                14,
                402,
                160,
                () -> action(54),
                () -> !orderActive(),
                () -> ui.review.placementReviewActive() ? HologramUiAccent.WARNING : 0));
        panel.child(
            button(
                55,
                () -> ui.review.lastConfirmationAction(),
                184,
                402,
                434,
                () -> action(55),
                () -> materials.ready() && !orderActive() && ui.review.confirmationAllowed(ui.review.operationCount()),
                () -> HologramPlacementConfirmation.forceReview(state(), ui.review.confirmedRevision())
                    ? HologramUiAccent.WARNING
                    : HologramUiAccent.NEXT));
        return panel;
    }

    private String status() {
        if (!ui.session.notice()
            .isEmpty()) return ui.session.notice();
        if (state().getBoolean("localPlanPending")) return "材料规划更新中；完成后再核对清单。";
        if (ui.session.materialReviewPending()) return "正在更新本次材料报价…可继续切换抵用券。";
        if (!materials.ready()) return "等待服务器的精确材料清单…";
        NBTTagCompound order = state().getCompoundTag("materialOrder");
        if (orderActive()) return "AE 本次订单 " + order
            .getLong("done") + " / " + order.getLong("total") + " · " + order.getString("status") + " · 可关闭界面，后台继续";
        if (!order.getString("status")
            .isEmpty()) return order.getString("status");
        if (HologramPlacementConfirmation.forceReview(state(), ui.review.confirmedRevision()))
            return state().getString("status")
                .contains("请重新核对") ? state().getString("status") : "确认后仅放置材料足够的格；缺料格跳过，权限与占位保护仍有效。";
        return materials.size() + " 项精确材料 · 图标悬停查看完整数值与身份 · 普通滚轮 / 拖动右侧滚动条";
    }

    private String reviewHint() {
        boolean creative = state().getCompoundTag("materialQuote")
            .getBoolean("creativePaymentsFree");
        if (ui.review.placementReviewActive())
            return "位置已固定：" + ui.review.placementPosition() + (creative ? " · 创造模式 · 完整材料需求" : " · 已有以最近检查为准");
        return creative ? "创造模式仍展示完整需求，实际消耗按施工规则。" : "已有按最近检查；确认施工或提交 AE 时核对实际库存。";
    }

    private String voucherLabel() {
        if (!voucherEnabled()) return "装饰抵用券：配置已禁用";
        boolean creative = state().getCompoundTag("materialQuote")
            .getBoolean("creativePaymentsFree");
        return (voucherSelected() ? creative ? "✓ 查看抵扣方案 · 创造不扣券" : "✓ 本次使用 1 张" : "本次使用装饰抵用券") + " · 持有 "
            + state().getCompoundTag("decorativeVoucher")
                .getLong("available");
    }

    private void voucher() {
        if (!editable() || !voucherEnabled()) return;
        ui.session.selectMaterialVoucher(!voucherSelected());
    }

    private boolean recommendOrder() {
        return materials.hasItemShortage() && ui.review.materialBlockers() == 0
            && HologramPlacementConfirmation.networksReady(state());
    }

    private String unavailableReason(int id) {
        if (id == 168 && !ui.nbt.hasPlacementNbtChoices()) return "本次蓝图没有可调整的 NBT 条目。";
        if (id == 196 && ui.bindings.networkGroups()
            .tagCount() == 0) return "本图没有需要绑定的量子网络。";
        if (id == 211 && !ui.identity.hasPlacementIdentityChoices()) return "本图没有可调整的身份与外部绑定数据。";
        if (id == 215 && ui.review.materialBlockers() == 0) return "本次范围没有需要移除的阻塞物。";
        if (id == 162 && !orderActive() && !materials.hasItemShortage()) return "本次没有可由 AE 补齐的缺失物品。";
        if (id == 245 && !voucherSelected()) {
            if (!voucherEnabled()) return "服务器配置已禁用装饰抵用券。";
            if (!hasVoucherDemand()) return "本次没有可抵扣的建筑材料需求。";
            if (state().getCompoundTag("decorativeVoucher")
                .getLong("available") < 1) return "主背包没有装饰抵用券。";
        }
        return "";
    }

    private boolean hasVoucherDemand() {
        NBTTagCompound voucher = state().getCompoundTag("decorativeVoucher");
        return materials.ready() && (voucher.hasKey("eligibleDemand", 4) ? voucher.getLong("eligibleDemand") > 0
            : materials.hasVoucherDemand());
    }

    private void order() {
        if (!orderActive()) {
            if (!materials.hasItemShortage()) return;
            ui.review.activateControl(162);
            return;
        }
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", ui.review.confirmedRevision());
        request.setLong("placementReviewNonce", state().getLong("placementReviewNonce"));
        ui.session.send("cancelMaterialOrder", request, true);
    }

    private ButtonWidget<?> button(int id, Supplier<String> text, int x, int y, int width, Runnable action,
        BooleanSupplier enabled, IntSupplier accent) {
        enabledControls.put(id, enabled);
        controlAccents.put(id, accent);
        return HologramMuiTheme.button(text, x, y, width, 23, action)
            .background(HologramMuiTheme.guidanceBackground(false, accent, enabled))
            .hoverBackground(HologramMuiTheme.guidanceBackground(true, accent, enabled))
            .overlay(
                IKey.dynamic(text)
                    .color(() -> HologramUiAccent.textColor(accent.getAsInt(), enabled.getAsBoolean()))
                    .shadow(false))
            .tooltipBuilder(t -> {
                String reason = unavailableReason(id);
                if (!reason.isEmpty()) t.addLine(reason);
            })
            .tooltipAutoUpdate(true)
            .setEnabledIf(widget -> enabled.getAsBoolean());
    }

    private int getHoveredRow() {
        // RichTooltip builds in the foreground's transform. Keep only the current draw's local hit index.
        return hoveredRow;
    }

    private final class MaterialList extends AbstractScrollWidget<IWidget, MaterialList>
        implements IParentWidget<IWidget, MaterialList> {

        MaterialList() {
            super(null, scroll);
        }

        @Override
        public boolean addChild(IWidget child, int index) {
            return super.addChild(child, index);
        }

        @Override
        public void beforeResize(boolean onOpen) {
            scroll.setScrollSize(Math.max(LIST_HEIGHT, materials.size() * ROW_HEIGHT));
            super.beforeResize(onOpen);
        }
    }

    private final class OrderProgress extends Widget<OrderProgress> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            NBTTagCompound order = state().getCompoundTag("materialOrder");
            long total = order.getLong("total");
            if (total <= 0) return;
            long done = Math.min(total, Math.max(0, order.getLong("done")));
            GuiDraw.drawRect(0, 0, 604, 4, 0xff14252e);
            int fill = (int) Math.floor(604d * done / total);
            GuiDraw.drawRect(0, 0, fill, 4, orderActive() ? HologramUiAccent.SUGGESTION : HologramUiAccent.NEXT);
        }
    }

    /** One framework-clipped child; draw work and decoded item cache are independent of total row count. */
    private final class RowsCanvas extends Widget<RowsCanvas> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            drawnFrames++;
            hoveredRow = isHovering() && context.getMouseY() >= 0 ? context.getMouseY() / ROW_HEIGHT : -1;
            int first = Math.max(0, scroll.getScroll() / ROW_HEIGHT);
            int last = Math.min(materials.size(), first + LIST_HEIGHT / ROW_HEIGHT + 2);
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            try {
                for (int i = first; i < last; i++) {
                    HologramMaterialReview.Row row = materials.row(i);
                    int y = i * ROW_HEIGHT;
                    GuiDraw.drawRect(0, y, ROW_WIDTH, ROW_HEIGHT - 1, (i & 1) == 0 ? 0xff24323a : 0xff293941);
                    if (row.item != null) drawItem(row.item, 5, y + 8, context.getCurrentDrawingZ());
                    else if (row.fluid != null)
                        GuiDraw.drawFluidTexture(row.fluid, 7, y + 9, 18, 22, context.getCurrentDrawingZ());
                    else font.drawString(
                        "energy".equals(row.data.getString("kind")) ? "EU"
                            : "essentia".equals(row.data.getString("kind")) ? "Vis" : "?",
                        7,
                        y + 15,
                        HologramUiAccent.INFORMATION);
                    font.drawString(font.trimStringToWidth(row.name, 220), 31, y + 8, HologramMuiTheme.TEXT);
                    String note = "#" + (i + 1)
                        + " · "
                        + (row.tagged ? "精确 NBT" : row.unit)
                        + (row.data.getBoolean("voucherEligible") ? " · 可抵用券" : "");
                    font.drawString(font.trimStringToWidth(note, 220), 31, y + 24, HologramMuiTheme.MUTED);
                    number(font, row.required, 256, y + 16, HologramMuiTheme.TEXT);
                    number(font, row.available, 370, y + 16, HologramMuiTheme.TEXT);
                    number(
                        font,
                        row.missing,
                        484,
                        y + 16,
                        row.shortage ? HologramUiAccent.WARNING : HologramMuiTheme.MUTED);
                }
                if (materials.size() == 0)
                    font.drawString(materials.ready() ? "本次没有材料需求" : "等待精确材料清单…", 12, 16, HologramMuiTheme.MUTED);
            } finally {
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }

        private void number(FontRenderer font, String value, int x, int y, int color) {
            int width = Math.max(1, font.getStringWidth(value));
            float scale = Math.min(1f, 106f / width);
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(x, y, 0);
                GL11.glScalef(scale, scale, 1);
                font.drawString(value, 0, 0, color);
            } finally {
                GL11.glPopMatrix();
            }
        }

        private void drawItem(ItemStack item, int x, int y, int z) {
            // Keep item-renderer zLevel private, and restore attributes/matrices even when an item renderer rejects
            // NBT.
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            float previousZ = iconRenderer.zLevel;
            try {
                Platform.setupDrawItem();
                GL11.glTranslatef(x, y, 0);
                GL11.glScalef(22f / 16f, 22f / 16f, 1);
                iconRenderer.zLevel = z + 100;
                Minecraft mc = Minecraft.getMinecraft();
                iconRenderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), item, 0, 0);
                GuiDraw.afterRenderItemAndEffectIntoGUI(item);
            } finally {
                iconRenderer.zLevel = previousZ;
                Platform.endDrawItem();
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }
    }

    private static final class MaterialPanel extends Dialog<Void> {

        MaterialPanel() {
            super("gtmb_material_confirmation");
            setDisablePanelsBelow(true);
            setCloseOnOutOfBoundsClick(false);
            setDraggable(false);
        }

        @Override
        public void transform(IViewportStack stack) {
            int w = getScreen().getScreenArea().width, h = getScreen().getScreenArea().height;
            float scale = Math.min(1f, Math.min(w / (float) (WIDTH + 12), h / (float) (HEIGHT + 12)));
            stack.translate((w - WIDTH * scale) / 2f, (h - HEIGHT * scale) / 2f);
            stack.scale(scale, scale);
        }
    }
}
