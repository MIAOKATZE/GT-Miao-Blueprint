package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
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
    private final HologramTextBox text;
    private final Map<String, StringValue> values = new LinkedHashMap<>();
    private final Document document;
    private boolean materials;
    private String notice = "确认后在工作区选择位置；再次确认才写入蓝图";

    HologramTextEditorScreen(HologramUiModules ui, HologramTextBox text) {
        super("text_editor", 590, 398);
        this.ui = ui;
        this.text = text;
        document = new Document(text.text);
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
        panel().child(HologramMuiTheme.button(this::fontName, 14, 130, 218, 20, () -> {
            int at = java.util.Arrays.asList(FONTS)
                .indexOf(text.font);
            text.font = FONTS[(at + 1) % FONTS.length];
        }));
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
                () -> (text.vertical ? "✓ " : "") + "竖向",
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
        material.child(
            HologramMuiTheme
                .label(() -> "材料方块注册名 / 元数据 / RGB 色值（FFFFFF 保持原材质）", 0, 0, 560, 16, HologramMuiTheme.MUTED));
        values.put("material", new StringValue(text.material));
        values.put("metadata", new StringValue(Integer.toString(text.metadata)));
        values.put("color", new StringValue(String.format(java.util.Locale.ROOT, "%06X", text.color)));
        material.child(HologramMuiTheme.textField(values.get("material"), 0, 20, 312, 20, 192));
        material.child(HologramMuiTheme.textField(values.get("metadata"), 320, 20, 74, 20, 5));
        material.child(HologramMuiTheme.textField(values.get("color"), 402, 20, 160, 20, 6));
        material.child(HologramMuiTheme.button(() -> "羊毛调色板", 0, 46, 180, 19, () -> palette("minecraft:wool")));
        material.child(
            HologramMuiTheme
                .button(() -> "染色黏土调色板", 188, 46, 180, 19, () -> palette("minecraft:stained_hardened_clay")));
        material.child(
            HologramMuiTheme.button(
                () -> "使用主手方块",
                376,
                46,
                186,
                19,
                () -> sample(Minecraft.getMinecraft().thePlayer.getHeldItem())));
        for (int i = 0; i < 16; i++) {
            final int meta = i;
            material.child(
                HologramMuiTheme.button(
                    () -> COLOR_NAMES[meta],
                    i * 35,
                    71,
                    32,
                    19,
                    () -> values.get("metadata")
                        .setStringValue(Integer.toString(meta)))
                    .background(paletteBackground(meta, false))
                    .hoverBackground(paletteBackground(meta, true))
                    .overlay(
                        com.cleanroommc.modularui.api.drawable.IKey.str(COLOR_NAMES[meta])
                            .color(
                                meta == 0 || meta == 3 || meta == 4 || meta == 5 || meta == 6 || meta == 8 ? 0xff182028
                                    : 0xfff6f1e9)
                            .shadow(false))
                    .tooltipBuilder(t -> t.addLine(COLOR_NAMES[meta] + " · 元数据 " + meta)));
        }
        for (int i = 0; i < 18; i++) {
            final int slot = i;
            material.child(HologramMuiTheme.button(() -> {
                ItemStack item = Minecraft.getMinecraft().thePlayer.inventory.mainInventory[slot];
                return item == null ? "—" : item.getDisplayName();
            },
                i % 9 * 62,
                98 + i / 9 * 26,
                58,
                23,
                () -> sample(Minecraft.getMinecraft().thePlayer.inventory.mainInventory[slot])));
        }
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
                () -> Minecraft.getMinecraft()
                    .displayGuiScreen(ui.host.parent())));
        panel().child(HologramMuiTheme.primaryButton(() -> "确认内容 · 选择放置位置", 312, 368, 264, 20, this::confirm));
    }

    private void field(ParentWidget<?> panel, String key, String name, double initial, int x, int y) {
        StringValue value = new StringValue(
            initial == Math.rint(initial) ? Long.toString((long) initial) : Double.toString(initial));
        values.put(key, value);
        panel.child(HologramMuiTheme.label(() -> name, x, y, 180, 15, HologramMuiTheme.MUTED));
        panel.child(HologramMuiTheme.textField(value, x, y + 16, 180, 20, 10));
    }

    private void palette(String material) {
        values.get("material")
            .setStringValue(material);
        values.get("color")
            .setStringValue("FFFFFF");
    }

    private com.cleanroommc.modularui.api.drawable.IDrawable paletteBackground(int metadata, boolean hover) {
        return (context, x, y, width, height, theme) -> {
            (hover ? HologramMuiTheme.PRESSED : HologramMuiTheme.BUTTON).draw(context, x, y, width, height, theme);
            com.cleanroommc.modularui.drawable.GuiDraw
                .drawRect(x + 3, y + 3, width - 6, height - 6, 0xff000000 | COLORS[metadata]);
            if (Integer.toString(metadata)
                .equals(
                    values.get("metadata")
                        .getStringValue()))
                com.cleanroommc.modularui.drawable.GuiDraw
                    .drawRect(x + 2, y + height - 2, width - 4, 1, HologramMuiTheme.TITLE);
        };
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
            ui.textBoxes.begin(text);
            Minecraft.getMinecraft()
                .displayGuiScreen(ui.host.parent());
        } catch (Exception failure) {
            notice = failure.getMessage() == null ? "文字参数无效" : failure.getMessage();
        }
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
            String current = content + '\u0000' + text.font + text.bold + text.underline;
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
            double scale = Math.min(3, Math.min(138d / mask.width, 57d / mask.height));
            double left = (150 - mask.width * scale) / 2, top = 20 + (57 - mask.height * scale) / 2;
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ALL_ATTRIB_BITS);
            try {
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                net.minecraft.client.renderer.Tessellator buffer = net.minecraft.client.renderer.Tessellator.instance;
                buffer.startDrawingQuads();
                buffer.setColorOpaque_I(0xf2e6cf);
                for (int y = 0; y < mask.height; y++) for (int x = 0; x < mask.width; x++) if (mask.pixel(x, y)) {
                    double xx = left + x * scale, yy = top + y * scale;
                    buffer.addVertex(xx, yy + scale, 0);
                    buffer.addVertex(xx + scale, yy + scale, 0);
                    buffer.addVertex(xx + scale, yy, 0);
                    buffer.addVertex(xx, yy, 0);
                }
                buffer.draw();
            } finally {
                org.lwjgl.opengl.GL11.glPopAttrib();
            }
        }
    }
}
