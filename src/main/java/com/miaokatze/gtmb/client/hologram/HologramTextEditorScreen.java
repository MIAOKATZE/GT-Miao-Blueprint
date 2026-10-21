package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.value.ObjectValue;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ColorPickerDialog;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;
import com.cleanroommc.modularui.widgets.textfield.TextEditorWidget;
import com.miaokatze.gtmb.hologram.HologramTextBox;

/** Multiline document input with two compact property sheets; changes remain local until placement confirmation. */
final class HologramTextEditorScreen extends HologramMuiScreen {

    private static final String[] FONTS = { "minecraft", "miao", "fusion", "dejavu", "dejavu_serif" };
    private static final String[] COLOR_NAMES = { "白", "橙", "品红", "淡蓝", "黄", "黄绿", "粉", "灰", "淡灰", "青", "紫", "蓝", "棕",
        "绿", "红", "黑" };
    private static final int[] COLORS = { 0xf0f0f0, 0xf9801d, 0xc74ebd, 0x70b9da, 0xfed83d, 0x80c71f, 0xf38baa,
        0x474f52, 0x9d9d97, 0x169c9c, 0x8932b8, 0x3c44aa, 0x835432, 0x5e7c16, 0xb02e26, 0x1d1d21 };
    private static final String[] FONT_NAMES = { "游戏字体 / 资源包", "Miao Wiki Pixel", "Fusion Pixel 完整版", "DejaVu Sans",
        "DejaVu Serif" };
    private final HologramUiModules ui;
    private HologramTextBox text;
    private final Map<String, StringValue> values = new LinkedHashMap<>();
    private final Document document;
    private boolean materials;
    private String notice = "确认后在工作区选择位置；再次确认才写入蓝图";

    HologramTextEditorScreen(HologramUiModules ui, HologramTextBox text) {
        super("text_editor", 590, 398);
        this.ui = ui;
        this.text = text;
        document = new Document(text.text);
        value("material", text.material);
        value("metadata", Integer.toString(text.metadata));
        value("color", String.format(java.util.Locale.ROOT, "%06X", text.color));
    }

    @Override
    boolean retainAttachmentEditor(HologramState next) {
        return ui.textBoxes.retainEditor(next);
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> "文字转蓝图 · LittleTiles 文本框", 590);
        panel().child(
            document.pos(14, 40)
                .size(402, 82)
                .background(HologramMuiTheme.PANEL)
                .setTextColor(HologramMuiTheme.TEXT));
        panel().child(
            new FontPreview().pos(426, 40)
                .size(150, 82)
                .background(HologramMuiTheme.PANEL));
        panel().child(HologramMuiTheme.button(this::fontName, 14, 130, 218, 20, this::cycleFont));
        panel().child(
            HologramMuiTheme
                .button(() -> (text.bold ? "✓ " : "") + "加粗", 240, 130, 76, 20, () -> text.bold = !text.bold));
        panel().child(
            HologramMuiTheme.button(
                () -> (text.underline ? "✓ " : "") + "下划线",
                324,
                130,
                76,
                20,
                () -> text.underline = !text.underline));
        panel().child(
            HologramMuiTheme.button(
                () -> (text.vertical ? "✓ " : "") + "竖向排列",
                408,
                130,
                76,
                20,
                () -> text.vertical = !text.vertical));
        panel().child(
            HologramMuiTheme
                .button(() -> materials ? "返回形态" : "颜色与材料", 492, 130, 84, 20, () -> materials = !materials));
        ParentWidget<?> shape = new ParentWidget<>().pos(14, 158)
            .size(562, 170)
            .setEnabledIf(w -> !materials);
        field(shape, "pixelX", "像素宽度 / 16", text.pixelX, 0, 0);
        field(shape, "pixelY", "像素高度 / 16", text.pixelY, 188, 0);
        field(shape, "thickness", "厚度 / 16", text.thickness, 376, 0);
        field(shape, "yaw", "Y 轴旋转 °", text.yaw, 0, 40);
        field(shape, "pitch", "X 轴旋转 °", text.pitch, 188, 40);
        field(shape, "roll", "Z 轴旋转 °", text.roll, 376, 40);
        field(shape, "arc", "平面弧度 / 环形 °", text.arc, 0, 80);
        field(shape, "bend", "空间弯曲 °", text.bend, 188, 80);
        field(shape, "twist", "沿文字扭转 °", text.twist, 376, 80);
        field(shape, "shear", "平面倾斜 %", text.shear, 0, 120);
        shape.child(
            HologramMuiTheme
                .label(() -> "像素宽高 1–16，厚度 1–16；角度 ±360°。环形可设为 360°。", 188, 128, 374, 30, HologramMuiTheme.MUTED));
        panel().child(shape);
        ParentWidget<?> material = new ParentWidget<>().pos(14, 158)
            .size(562, 170)
            .setEnabledIf(w -> materials);
        buildMaterials(material);
        panel().child(material);
        panel().child(
            HologramMuiTheme.label(() -> notice, 14, 336, 562, 23, HologramMuiTheme.TITLE)
                .tooltipBuilder(t -> t.addLine(notice))
                .tooltipAutoUpdate(true));
        panel().child(
            HologramMuiTheme.button(
                () -> "返回工作区",
                14,
                368,
                160,
                20,
                () -> deferAction(
                    () -> Minecraft.getMinecraft()
                        .displayGuiScreen(ui.host.parent()))));
        panel().child(HologramMuiTheme.button(() -> "字体预设", 180, 368, 124, 20, this::openPresets));
        panel().child(
            HologramMuiTheme.primaryButton(
                () -> ui.session.state().cells.isEmpty() ? "确认内容 · 生成文字" : "确认内容 · 选择放置位置",
                312,
                368,
                264,
                20,
                this::confirm));
    }

    private void field(ParentWidget<?> panel, String key, String name, double initial, int x, int y) {
        StringValue value = value(
            key,
            initial == Math.rint(initial) ? Long.toString((long) initial) : Double.toString(initial));
        panel.child(HologramMuiTheme.label(() -> name, x, y, 180, 15, HologramMuiTheme.MUTED));
        panel.child(HologramMuiTheme.textField(value, x, y + 16, 180, 20, 10));
    }

    private StringValue value(String key, String initial) {
        return values.computeIfAbsent(key, ignored -> new StringValue(initial));
    }

    private void cycleFont() {
        int at = java.util.Arrays.asList(FONTS)
            .indexOf(text.font);
        text.font = FONTS[(at + 1) % FONTS.length];
    }

    private void buildMaterials(ParentWidget<?> material) {
        ParentWidget<?> base = new ParentWidget<>().pos(0, 0)
            .size(276, 170)
            .background(HologramMuiTheme.PANEL);
        ParentWidget<?> dye = new ParentWidget<>().pos(286, 0)
            .size(276, 170)
            .background(HologramMuiTheme.PANEL);
        material.child(base)
            .child(dye);
        base.child(HologramMuiTheme.label(() -> "基础材质", 12, 8, 252, 16, HologramMuiTheme.TITLE));
        base.child(
            new MaterialSample(this::selectedMaterial, () -> openInventory()).pos(12, 29)
                .size(24));
        base.child(HologramMuiTheme.textField(values.get("material"), 44, 29, 180, 22, 192));
        base.child(HologramMuiTheme.textField(values.get("metadata"), 230, 29, 34, 22, 5));
        base.child(
            HologramMuiTheme.button(
                () -> "羊毛粘土",
                12,
                59,
                76,
                22,
                () -> palette(com.miaokatze.gtmb.hologram.HologramPictureBox.WOOLEN_CLAY)));
        base.child(
            HologramMuiTheme.button(() -> "陶瓦", 94, 59, 60, 22, () -> palette("minecraft:stained_hardened_clay")));
        base.child(HologramMuiTheme.button(() -> "羊毛", 160, 59, 50, 22, () -> palette("minecraft:wool")));
        base.child(HologramMuiTheme.button(() -> "背包", 216, 59, 48, 22, this::openInventory));
        for (int i = 0; i < 16; i++) {
            final int meta = i;
            base.child(
                new MaterialSample(
                    () -> variant(meta),
                    () -> values.get("metadata")
                        .setStringValue(Integer.toString(meta))).pos(12 + i % 8 * 31, 90 + i / 8 * 32)
                            .size(26)
                            .background((context, x, y, width, height, theme) -> {
                                HologramMuiTheme.BUTTON.draw(context, x, y, width, height, theme);
                                if (Integer.toString(meta)
                                    .equals(
                                        values.get("metadata")
                                            .getStringValue()))
                                    com.cleanroommc.modularui.drawable.GuiDraw
                                        .drawRect(x + 2, y + height - 2, width - 4, 1, HologramMuiTheme.TITLE);
                            })
                            .tooltipBuilder(t -> t.addLine(COLOR_NAMES[meta] + " · 材质色 " + meta))
                            .setEnabledIf(w -> coloredMaterial()));
        }
        base.child(
            HologramMuiTheme.label(() -> "点击背包槽位选择方块", 12, 113, 252, 30, HologramMuiTheme.MUTED)
                .setEnabledIf(w -> !coloredMaterial()));
        dye.child(HologramMuiTheme.label(() -> "瓷砖染色", 12, 8, 252, 16, HologramMuiTheme.TITLE));
        dye.child(
            new ParentWidget<>().pos(12, 29)
                .size(30, 22)
                .background(
                    (context, x, y, width, height, theme) -> com.cleanroommc.modularui.drawable.GuiDraw
                        .drawRect(x, y, width, height, 0xff000000 | tint())));
        dye.child(HologramMuiTheme.textField(values.get("color"), 50, 29, 112, 22, 6));
        dye.child(
            HologramMuiTheme.button(
                () -> "原材色",
                172,
                29,
                92,
                22,
                () -> values.get("color")
                    .setStringValue("FFFFFF")));
        dye.child(HologramMuiTheme.button(() -> "调色器 · RGB / HSV", 12, 59, 252, 24, this::openColorPicker));
        for (int i = 0; i < 16; i++) {
            final int color = i;
            dye.child(
                HologramMuiTheme.button(
                    () -> "",
                    12 + i % 8 * 31,
                    92 + i / 8 * 32,
                    26,
                    26,
                    () -> values.get("color")
                        .setStringValue(String.format(java.util.Locale.ROOT, "%06X", COLORS[color])))
                    .background(colorChip(color, false))
                    .hoverBackground(colorChip(color, true))
                    .tooltipBuilder(t -> t.addLine(COLOR_NAMES[color] + " · 染色")));
        }
    }

    private boolean coloredMaterial() {
        String material = values.get("material")
            .getStringValue();
        return "minecraft:wool".equals(material) || "minecraft:stained_hardened_clay".equals(material)
            || com.miaokatze.gtmb.hologram.HologramPictureBox.WOOLEN_CLAY.equals(material);
    }

    private void palette(String material) {
        values.get("material")
            .setStringValue(material);
    }

    private ItemStack selectedMaterial() {
        try {
            net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromName(
                values.get("material")
                    .getStringValue());
            return block == null || block == net.minecraft.init.Blocks.air ? null
                : new ItemStack(block, 1, (int) number("metadata"));
        } catch (RuntimeException invalidDraft) {
            return null;
        }
    }

    private ItemStack variant(int metadata) {
        ItemStack stack = selectedMaterial();
        if (stack != null) stack.setItemDamage(metadata);
        return stack;
    }

    private int tint() {
        try {
            return Integer.parseInt(
                values.get("color")
                    .getStringValue(),
                16) & 0xffffff;
        } catch (NumberFormatException incomplete) {
            return text.color;
        }
    }

    private int previewColor() {
        return HologramTextColors.preview(
            values.get("material")
                .getStringValue(),
            selectedMaterial(),
            tint());
    }

    private com.cleanroommc.modularui.api.drawable.IDrawable colorChip(int color, boolean hover) {
        return (context, x, y, width, height, theme) -> {
            (hover ? HologramMuiTheme.PRESSED : HologramMuiTheme.BUTTON).draw(context, x, y, width, height, theme);
            com.cleanroommc.modularui.drawable.GuiDraw
                .drawRect(x + 3, y + 3, width - 6, height - 6, 0xff000000 | COLORS[color]);
            if (COLORS[color] == tint()) com.cleanroommc.modularui.drawable.GuiDraw
                .drawRect(x + 2, y + height - 2, width - 4, 1, HologramMuiTheme.TITLE);
        };
    }

    private void openColorPicker() {
        openDialog(
            "gtmb_text_color",
            (parent, player) -> new ColorPickerDialog(
                "gtmb_text_color",
                color -> values.get("color")
                    .setStringValue(String.format(java.util.Locale.ROOT, "%06X", color & 0xffffff)),
                0xff000000 | tint(),
                false).background(HologramMuiTheme.CLOTH));
    }

    @Override
    protected void onMuiActionFailure(RuntimeException failure) {
        notice = failure.getMessage() == null ? "文字编辑操作失败，请重试" : failure.getMessage();
    }

    private void openInventory() {
        openDialog("gtmb_text_material_inventory", (parent, player) -> {
            Dialog<ItemStack> inventory = new Dialog<>("gtmb_text_material_inventory", this::sample);
            inventory.size(202, 166)
                .background(HologramMuiTheme.CLOTH);
            HologramMuiTheme.header(inventory, () -> "背包 · 选择材质", 202);
            for (int i = 0; i < 36; i++) {
                final int slot = i < 27 ? i + 9 : i - 27;
                inventory.child(new MaterialSample(() -> player.inventory.mainInventory[slot], () -> {
                    ItemStack stack = player.inventory.mainInventory[slot];
                    if (stack != null
                        && net.minecraft.block.Block.getBlockFromItem(stack.getItem()) != net.minecraft.init.Blocks.air)
                        inventory.closeWith(stack.copy());
                }).pos(20 + i % 9 * 18, i < 27 ? 42 + i / 9 * 18 : 104)
                    .size(18)
                    .background(i < 27 ? GuiTextures.SLOT_ITEM_PLAYER : GuiTextures.SLOT_ITEM_HOTBAR));
            }
            inventory.child(HologramMuiTheme.button(() -> "返回材质设置", 20, 136, 162, 20, inventory::closeIfOpen));
            return inventory;
        });
    }

    /** Native item rendering and slot backgrounds; sampling never moves or edits the real inventory. */
    private static final class MaterialSample extends ItemDisplayWidget implements Interactable {

        private final Runnable select;

        MaterialSample(java.util.function.Supplier<ItemStack> supplier, Runnable select) {
            this.select = select;
            item(new ObjectValue.Dynamic<>(ItemStack.class, supplier, ignored -> {}));
            background(GuiTextures.SLOT_ITEM);
            tooltipBuilder(t -> {
                ItemStack stack = supplier.get();
                if (stack != null) t.addLine(stack.getDisplayName());
            }).tooltipAutoUpdate(true);
        }

        @Override
        public Result onMousePressed(int button) {
            if (button == 0) select.run();
            return Result.SUCCESS;
        }
    }

    private void sample(ItemStack stack) {
        if (stack == null
            || net.minecraft.block.Block.getBlockFromItem(stack.getItem()) == net.minecraft.init.Blocks.air) return;
        values.get("material")
            .setStringValue(net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()));
        values.get("metadata")
            .setStringValue(Integer.toString(stack.getItemDamage()));
    }

    private String fontName() {
        int at = java.util.Arrays.asList(FONTS)
            .indexOf(text.font);
        return FONT_NAMES[Math.max(0, at)];
    }

    private double previewPixel(String key) {
        try {
            double value = number(key);
            return Double.isFinite(value) ? Math.max(1, Math.min(16, value)) : 1;
        } catch (RuntimeException incomplete) {
            return 1;
        }
    }

    private double number(String key) {
        return Double.parseDouble(
            values.get(key)
                .getStringValue());
    }

    private void confirm() {
        try {
            text.text = document.content();
            text.pixelX = (int) number("pixelX");
            text.pixelY = (int) number("pixelY");
            text.thickness = (int) number("thickness");
            text.yaw = number("yaw");
            text.pitch = number("pitch");
            text.roll = number("roll");
            text.arc = number("arc");
            text.bend = number("bend");
            text.twist = number("twist");
            text.shear = number("shear");
            text.material = values.get("material")
                .getStringValue();
            text.metadata = (int) number("metadata");
            text.color = Integer.parseInt(
                values.get("color")
                    .getStringValue(),
                16);
            HologramTextRasterizer.rasterize(text);
            HologramAttachmentPresetDialogs.confirm(
                this,
                false,
                com.miaokatze.gtmb.hologram.HologramAttachmentPresets.settings(text),
                this::place);

        } catch (Exception failure) {
            notice = failure.getMessage() == null ? "文字参数无效" : failure.getMessage();
        }
    }

    private void place() {
        try {
            ui.textBoxes.begin(text);
            Minecraft.getMinecraft()
                .displayGuiScreen(ui.host.parent());
        } catch (java.io.IOException failure) {
            notice = failure.getMessage();
        }
    }

    private void openPresets() {
        HologramAttachmentPresetDialogs.open(this, false, settings -> {
            try {
                text = com.miaokatze.gtmb.hologram.HologramAttachmentPresets.apply(text, settings);
                String[] integers = { "pixelX", "pixelY", "thickness", "metadata" };
                int[] numbers = { text.pixelX, text.pixelY, text.thickness, text.metadata };
                for (int i = 0; i < integers.length; i++) values.get(integers[i])
                    .setStringValue(Integer.toString(numbers[i]));
                String[] decimals = { "yaw", "pitch", "roll", "arc", "bend", "twist", "shear" };
                double[] angles = { text.yaw, text.pitch, text.roll, text.arc, text.bend, text.twist, text.shear };
                for (int i = 0; i < decimals.length; i++) values.get(decimals[i])
                    .setStringValue(Double.toString(angles[i]));
                values.get("material")
                    .setStringValue(text.material);
                values.get("color")
                    .setStringValue(String.format(java.util.Locale.ROOT, "%06X", text.color));
                notice = "已应用字体处理预设；文字内容与位置保留";
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException(failure.getMessage(), failure);
            }
        });
    }

    private static final class Document extends TextEditorWidget {

        Document(String text) {
            handler.setMaxLines(8);
            handler.setMaxCharacters(512);
            handler.getText()
                .addAll(java.util.Arrays.asList(text.split("\n", -1)));
        }

        String content() {
            return handler.getTextAsString();
        }
    }

    /** Cache glyph work on text/font changes; rendering batches the small preview into one draw. */
    private final class FontPreview extends com.cleanroommc.modularui.widget.Widget<FontPreview> {

        private String signature = "", error = "";
        private HologramTextBox mask;

        @Override
        public void draw(com.cleanroommc.modularui.screen.viewport.ModularGuiContext context,
            com.cleanroommc.modularui.theme.WidgetThemeEntry<?> theme) {
            String content = document.content();
            String current = content + '\u0000' + text.font + text.bold + text.underline + text.vertical;
            if (!signature.equals(current)) {
                signature = current;
                error = "";
                mask = null;
                try {
                    HologramTextBox candidate = new HologramTextBox();
                    candidate.text = content;
                    candidate.font = text.font;
                    candidate.bold = text.bold;
                    candidate.underline = text.underline;
                    candidate.vertical = text.vertical;
                    HologramTextRasterizer.rasterize(candidate);
                    mask = candidate;
                } catch (Exception failure) {
                    error = failure.getMessage() == null ? "字形不可用" : failure.getMessage();
                }
            }
            net.minecraft.client.gui.FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            font.drawString("字形预览", 6, 5, HologramMuiTheme.TITLE);
            if (mask == null) {
                font.drawString(font.trimStringToWidth(error, 138), 6, 26, HologramMuiTheme.MUTED);
                return;
            }
            double pixelX = previewPixel("pixelX"), pixelY = previewPixel("pixelY");
            double scale = Math.min(3, Math.min(138d / (mask.width * pixelX), 57d / (mask.height * pixelY)));
            double sx = scale * pixelX, sy = scale * pixelY;
            double left = (150 - mask.width * sx) / 2, top = 20 + (57 - mask.height * sy) / 2;
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ALL_ATTRIB_BITS);
            try {
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                net.minecraft.client.renderer.Tessellator buffer = net.minecraft.client.renderer.Tessellator.instance;
                buffer.startDrawingQuads();
                buffer.setColorOpaque_I(previewColor());
                for (int y = 0; y < mask.height; y++) for (int x = 0; x < mask.width; x++) if (mask.pixel(x, y)) {
                    double xx = left + x * sx, yy = top + y * sy;
                    buffer.addVertex(xx, yy + sy, 0);
                    buffer.addVertex(xx + sx, yy + sy, 0);
                    buffer.addVertex(xx + sx, yy, 0);
                    buffer.addVertex(xx, yy, 0);
                }
                buffer.draw();
            } finally {
                org.lwjgl.opengl.GL11.glPopAttrib();
            }
        }
    }
}
