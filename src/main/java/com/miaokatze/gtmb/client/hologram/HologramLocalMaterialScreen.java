package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.widget.IParentWidget;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Platform;
import com.cleanroommc.modularui.widget.AbstractScrollWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/** Opens no session or upload; public sources may request a read-only static material summary. */
final class HologramLocalMaterialScreen extends HologramMuiScreen {

    private static final int WIDTH = 632, HEIGHT = 412, ROW_HEIGHT = 44, LIST_HEIGHT = 242, ROW_WIDTH = 594;
    private final HologramLocalPlacement placement;
    private final HologramLocalMaterials estimate;
    private final HologramMaterialReview materials = new HologramMaterialReview();
    private final VerticalScrollData scroll = new VerticalScrollData();
    private final RenderItem renderer = new RenderItem();
    private final Object world, connection;
    private final int slot;
    private final NBTTagCompound reviewedIntent = new NBTTagCompound();
    private NBTTagCompound summary;
    private MaterialList list;
    private RowsCanvas canvas;
    private int hovered = -1;
    private long drawnFrames;
    private boolean finished;

    static void openLocalMaterialReview(HologramLocalPlacement placement) {
        if (placement.display() == null || !placement.locked() || placement.submitting()) return;
        if (placement.artifact() == null) HologramLibraryClient.INSTANCE.requestPublicMaterials(placement);
        Minecraft.getMinecraft()
            .displayGuiScreen(new HologramLocalMaterialScreen(placement));
    }

    private HologramLocalMaterialScreen(HologramLocalPlacement placement) {
        this(placement, null);
    }

    /** Menu-only visual fixture; the construction control remains disabled without a placement intent. */
    static HologramLocalMaterialScreen visualFixture(NBTTagCompound summary) {
        return new HologramLocalMaterialScreen(null, summary);
    }

    private HologramLocalMaterialScreen(HologramLocalPlacement placement, NBTTagCompound fixture) {
        super("local_material_review", WIDTH, HEIGHT);
        this.placement = placement;
        estimate = placement == null ? null : new HologramLocalMaterials(placement);
        Minecraft minecraft = Minecraft.getMinecraft();
        world = minecraft.theWorld;
        connection = minecraft.getNetHandler();
        slot = minecraft.thePlayer == null ? -1 : minecraft.thePlayer.inventory.currentItem;
        if (placement != null) placement.intent(reviewedIntent);
        if (fixture != null) publish(fixture);
        scroll.setScrollSpeed(ROW_HEIGHT);
    }

    NBTTagCompound visualState() {
        NBTTagCompound state = new NBTTagCompound();
        state.setInteger("rows", materials.size());
        state.setInteger("scroll", scroll.getScroll());
        state.setLong("drawnFrames", drawnFrames);
        state.setBoolean("constructionEnabled", placement != null && summary != null && current());
        state.setBoolean("stockKnown", false);
        return state;
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> "本地材料预估 · 开始建设前核对", WIDTH);
        int[] at = placement == null ? new int[] { 0, 0, 0 } : placement.anchor();
        panel().child(
            HologramMuiTheme.label(
                () -> placement == null ? "本地视觉示例 · 未连接世界 / 服务器" : "已固定位置：" + at[0] + " / " + at[1] + " / " + at[2],
                14,
                38,
                604,
                15,
                HologramMuiTheme.MUTED));
        panel()
            .child(HologramMuiTheme.label(() -> "理论完整需求；库存尚未查询，已有与缺失均为未知。", 14, 55, 604, 15, HologramMuiTheme.MUTED));
        panel().child(HologramMuiTheme.label(() -> "材料 / 精确 NBT 条目", 47, 77, 220, 15, HologramMuiTheme.TITLE));
        panel().child(HologramMuiTheme.label(() -> "需求", 275, 77, 110, 15, HologramMuiTheme.TITLE));
        panel().child(HologramMuiTheme.label(() -> "已有", 389, 77, 110, 15, HologramMuiTheme.TITLE));
        panel().child(HologramMuiTheme.label(() -> "缺失", 503, 77, 110, 15, HologramMuiTheme.MUTED));
        list = new MaterialList().pos(14, 96)
            .size(604, LIST_HEIGHT)
            .background(HologramMuiTheme.PANEL)
            .name("local_material_list");
        canvas = new RowsCanvas().pos(5, 0)
            .size(ROW_WIDTH, Math.max(LIST_HEIGHT, materials.size() * ROW_HEIGHT))
            .name("local_material_rows");
        canvas.tooltipBuilder(t -> {
            HologramMaterialReview.Row row = materials.row(hovered);
            if (row == null) return;
            t.addLine(row.name);
            t.addLine("理论需求 " + row.required + " " + row.unit + " / 已有未知 / 缺失未知");
            t.addLine(row.identity());
            if (row.item != null)
                for (Object line : row.item.getTooltip(mc.thePlayer, false)) t.addLine(String.valueOf(line));
            t.addLine("本地估算不构成扣料或放置许可。");
        })
            .tooltipAutoUpdate(true);
        list.child(canvas);
        panel().child(list);
        panel().child(
            HologramMuiTheme.label(this::status, 14, 344, 604, 16, HologramMuiTheme.TEXT)
                .tooltipBuilder(t -> {
                    t.addLine(status());
                    if (summary != null) {
                        if (!summary.getString("detail")
                            .isEmpty()) t.addLine(summary.getString("detail"));
                        if (summary.getBoolean("cacheReviewRequired")) t.addLine("保留设备缓存的费用尚未核算；服务器会再次显示费用方案。");
                        if (summary.getBoolean("alternatives")) t.addLine("装饰件列出预制件需求；若改用原材料，服务器会显示差异方案。");
                    }
                    t.addLine("开始建设时服务器检查位置、维度、距离、权限、现场占位和实际库存。");
                    t.addLine("缺料、未核算需求或额外费用会返回完整确认，不能静默追加扣费。");
                })
                .tooltipAutoUpdate(true));
        panel().child(
            HologramMuiTheme.button(() -> "返回本地预览", 14, 378, 160, 22, this::back)
                .name("local_material_back"));
        panel().child(
            HologramMuiTheme.primaryButton(() -> "开始建设 · 服务器复核", 184, 378, 434, 22, this::submit)
                .name("local_material_construct")
                .setEnabledIf(widget -> placement != null && summary != null && current()));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (placement == null) return;
        if (!current()) {
            back();
            return;
        }
        if (summary == null && estimate.step()) {
            publish(estimate.result());
            int height = Math.max(LIST_HEIGHT, materials.size() * ROW_HEIGHT);
            canvas.height(height)
                .scheduleResize();
            scroll.setScrollSize(height);
            list.scheduleResize();
        }
    }

    private void publish(NBTTagCompound value) {
        summary = value;
        NBTTagCompound quote = (NBTTagCompound) value.copy();
        quote.setBoolean("localEstimate", true);
        NBTTagCompound view = new NBTTagCompound();
        view.setTag("materialQuote", quote);
        materials.update(view);
    }

    private String status() {
        if (summary == null) return "正在本地核算材料… " + estimate.progress();
        if (!summary.getBoolean("complete")) return "部分需求未核算：" + summary.getString("detail");
        if (summary.getBoolean("cacheReviewRequired")) return materials.size() + " 项材料 · 设备缓存费用待核算";
        return materials.size() + " 项理论材料 · 悬停查看精确数量与 NBT 身份";
    }

    private boolean current() {
        if (!estimate.current() || !placement.locked()
            || mc.theWorld != world
            || mc.getNetHandler() != connection
            || mc.thePlayer == null
            || mc.thePlayer.inventory.currentItem != slot
            || !ItemNekoHologramProjector.isProjector(mc.thePlayer.getHeldItem())) return false;
        NBTTagCompound now = new NBTTagCompound();
        placement.intent(now);
        return reviewedIntent.equals(now);
    }

    private void submit() {
        if (finished || placement == null || summary == null || !current()) return;
        NBTTagCompound options = new NBTTagCompound();
        options.setBoolean("executeIntent", true);
        options.setBoolean("openReview", false);
        options.setTag("acknowledgedMaterials", summary.copy());
        finished = true;
        mc.displayGuiScreen(null);
        placement.submit(options);
    }

    private void back() {
        if (finished) return;
        finished = true;
        mc.displayGuiScreen(null);
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE && key != Keyboard.KEY_E) return false;
        back();
        return true;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
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

    /** Bounded visible row rendering shares the established material review's 44px rows and icon layout. */
    private final class RowsCanvas extends Widget<RowsCanvas> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            drawnFrames++;
            hovered = isHovering() && context.getMouseY() >= 0 ? context.getMouseY() / ROW_HEIGHT : -1;
            int first = Math.max(0, scroll.getScroll() / ROW_HEIGHT);
            int last = Math.min(materials.size(), first + LIST_HEIGHT / ROW_HEIGHT + 2);
            FontRenderer font = mc.fontRenderer;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            try {
                for (int i = first; i < last; i++) {
                    HologramMaterialReview.Row row = materials.row(i);
                    int y = i * ROW_HEIGHT;
                    GuiDraw.drawRect(0, y, ROW_WIDTH, ROW_HEIGHT - 1, (i & 1) == 0 ? 0xff24323a : 0xff293941);
                    if (row.item != null) drawItem(row.item, y, context.getCurrentDrawingZ());
                    else if (row.fluid != null)
                        GuiDraw.drawFluidTexture(row.fluid, 7, y + 9, 18, 22, context.getCurrentDrawingZ());
                    else font.drawString(
                        "energy".equals(row.data.getString("kind")) ? "EU" : "Vis",
                        7,
                        y + 15,
                        HologramUiAccent.INFORMATION);
                    font.drawString(font.trimStringToWidth(row.name, 220), 31, y + 8, HologramMuiTheme.TEXT);
                    font.drawString(
                        "#" + (i + 1) + " · " + (row.tagged ? "精确 NBT" : row.unit),
                        31,
                        y + 24,
                        HologramMuiTheme.MUTED);
                    number(row.required, 256, y + 16, HologramMuiTheme.TEXT);
                    number("未知", 370, y + 16, HologramMuiTheme.MUTED);
                    number("未知", 484, y + 16, HologramMuiTheme.MUTED);
                }
                if (materials.size() == 0)
                    font.drawString(summary == null ? "正在本地核算…" : "没有已核算的材料条目", 12, 16, HologramMuiTheme.MUTED);
            } finally {
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }

        private void number(String value, int x, int y, int color) {
            GL11.glPushMatrix();
            try {
                float scale = Math.min(1f, 106f / Math.max(1, mc.fontRenderer.getStringWidth(value)));
                GL11.glTranslatef(x, y, 0);
                GL11.glScalef(scale, scale, 1);
                mc.fontRenderer.drawString(value, 0, 0, color);
            } finally {
                GL11.glPopMatrix();
            }
        }

        private void drawItem(ItemStack item, int y, int z) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            float previousZ = renderer.zLevel;
            try {
                Platform.setupDrawItem();
                GL11.glTranslatef(5, y + 8, 0);
                GL11.glScalef(22f / 16f, 22f / 16f, 1);
                renderer.zLevel = z + 100;
                renderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), item, 0, 0);
                GuiDraw.afterRenderItemAndEffectIntoGUI(item);
            } finally {
                renderer.zLevel = previousZ;
                Platform.endDrawItem();
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }
    }
}
