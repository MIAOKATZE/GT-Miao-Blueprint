package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.miaokatze.gtmb.hologram.HologramPictureBox;
import com.miaokatze.gtmb.hologram.HologramTextBox;

/** Local image catalog and material-pixel proof sheet, sharing the existing attachment placement intent. */
final class HologramPictureEditorScreen extends HologramMuiScreen {

    private final HologramUiModules ui;
    private final HologramTextBox original;
    private HologramPictureBox source;
    private final Path folder = Minecraft.getMinecraft().mcDataDir.toPath()
        .resolve("MiaoBlueprintPicture");
    private final List<String> files = new ArrayList<>();
    private final Map<String, StringValue> values = new LinkedHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        task -> {
            Thread thread = new Thread(task, "GTMB picture conversion");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy());
    private boolean filters, keepAspect, removeBackground, dither, loading;
    private volatile boolean closed;
    private int page, palette, filter;
    private long generation, previewGeneration;
    private String notice = "将图片放入 MiaoBlueprintPicture，点击列表读取；滤镜仅改变本地草稿";
    private String previewSignature = "";
    private byte[] preview;
    private HologramPictureBox previewPicture;

    HologramPictureEditorScreen(HologramUiModules ui, HologramTextBox value) {
        super("picture_editor", 620, 454);
        this.ui = ui;
        original = value;
        source = value.picture == null ? new HologramPictureBox() : value.picture;
        keepAspect = source.keepAspect;
        removeBackground = source.removeBackground;
        dither = source.dither;
        palette = source.palette;
        filter = source.filter;
        value("shortSide", source.shortSide);
        value("width", source.width);
        value("height", source.height);
        value("pixel", value.pixelX);
        value("thickness", value.thickness);
        value("brightness", source.brightness);
        value("contrast", source.contrast);
        value("saturation", source.saturation);
        value("tolerance", source.backgroundTolerance);
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> "图片附加 · LittleTiles 像素画", 620);
        panel().child(
            HologramMuiTheme.label(() -> ".minecraft / MiaoBlueprintPicture", 14, 40, 450, 18, HologramMuiTheme.MUTED)
                .tooltipBuilder(
                    t -> t.addLine(
                        folder.toAbsolutePath()
                            .toString())));
        panel().child(HologramMuiTheme.button(() -> "刷新图片", 480, 38, 126, 22, this::refresh));
        ParentWidget<?> catalog = new ParentWidget<>().pos(14, 66)
            .size(178, 278)
            .background(HologramMuiTheme.PANEL);
        catalog.child(HologramMuiTheme.label(() -> "本地图片 · " + files.size(), 10, 8, 158, 16, HologramMuiTheme.TITLE));
        for (int i = 0; i < 9; i++) {
            final int row = i;
            ParentWidget<?> fileRow = new ParentWidget<>().pos(8, 30 + i * 24)
                .size(162, 22)
                .setEnabledIf(w -> page * 9 + row < files.size());
            fileRow.child(
                HologramMuiTheme.button(() -> filename(row), 0, 0, 162, 22, () -> load(filename(row)))
                    .setEnabledIf(w -> !loading)
                    .tooltipBuilder(t -> t.addLine(filename(row)))
                    .tooltipAutoUpdate(true));
            catalog.child(fileRow);
        }
        catalog.child(HologramMuiTheme.button(() -> "‹", 8, 250, 38, 20, () -> page = Math.max(0, page - 1)));
        catalog.child(
            HologramMuiTheme.label(
                () -> (page + 1) + " / " + Math.max(1, (files.size() + 8) / 9),
                50,
                250,
                78,
                20,
                HologramMuiTheme.MUTED));
        catalog.child(
            HologramMuiTheme.button(
                () -> "›",
                132,
                250,
                38,
                20,
                () -> page = Math.min(Math.max(0, (files.size() - 1) / 9), page + 1)));
        panel().child(catalog);
        panel().child(
            HologramMuiTheme
                .label(() -> "PNG / JPG / BMP / GIF 首帧\n蓝图中整体编辑；导出为普通小瓷砖。", 18, 354, 170, 44, HologramMuiTheme.MUTED));
        panel().child(
            new PixelPreview().pos(204, 66)
                .size(402, 162)
                .background(HologramMuiTheme.PANEL));
        panel().child(
            HologramMuiTheme.button(() -> (!filters ? "● " : "") + "尺寸与材料", 204, 236, 198, 22, () -> filters = false));
        panel().child(
            HologramMuiTheme.button(() -> (filters ? "● " : "") + "滤镜与抠图", 408, 236, 198, 22, () -> filters = true));
        ParentWidget<?> dimensions = new ParentWidget<>().pos(204, 264)
            .size(402, 134)
            .setEnabledIf(w -> !filters);
        field(dimensions, "shortSide", "短边像素 · 默认 64", 0, 0, () -> keepAspect);
        field(dimensions, "width", "横向像素", 136, 0, () -> !keepAspect);
        field(dimensions, "height", "竖向像素", 272, 0, () -> !keepAspect);
        dimensions.child(
            HologramMuiTheme.label(
                () -> values.get("width")
                    .getStringValue() + " px",
                136,
                16,
                130,
                20,
                HologramMuiTheme.TEXT)
                .setEnabledIf(w -> keepAspect));
        dimensions.child(
            HologramMuiTheme.label(
                () -> values.get("height")
                    .getStringValue() + " px",
                272,
                16,
                130,
                20,
                HologramMuiTheme.TEXT)
                .setEnabledIf(w -> keepAspect));
        field(dimensions, "pixel", "像素边长 / 16", 0, 42, () -> true);
        field(dimensions, "thickness", "厚度 / 16", 136, 42, () -> true);
        dimensions.child(HologramMuiTheme.label(() -> "固定材料组合", 272, 42, 130, 15, HologramMuiTheme.MUTED));
        dimensions.child(
            HologramMuiTheme.button(
                () -> HologramPictureBox.PALETTE_NAMES[palette],
                272,
                58,
                130,
                20,
                () -> palette = (palette + 1) % HologramPictureBox.PALETTE_NAMES.length));
        dimensions.child(
            HologramMuiTheme.button(() -> (keepAspect ? "✓ " : "") + "保持纵横比", 0, 88, 198, 22, this::toggleAspect));
        dimensions.child(
            HologramMuiTheme.button(() -> (dither ? "✓ " : "") + "颜色抖动", 204, 88, 198, 22, () -> dither = !dither));
        dimensions.child(
            HologramMuiTheme.label(() -> "每边 ≤512，总像素 ≤65536；像素边长与厚度 1–16。", 0, 116, 402, 16, HologramMuiTheme.MUTED));
        panel().child(dimensions);
        ParentWidget<?> effects = new ParentWidget<>().pos(204, 264)
            .size(402, 134)
            .setEnabledIf(w -> filters);
        field(effects, "brightness", "亮度 −100…100", 0, 0, () -> true);
        field(effects, "contrast", "对比度 −100…100", 136, 0, () -> true);
        field(effects, "saturation", "饱和度 0…200", 272, 0, () -> true);
        effects.child(
            HologramMuiTheme.button(
                () -> "滤镜 · " + HologramPictureBox.FILTER_NAMES[filter],
                0,
                44,
                266,
                24,
                () -> filter = (filter + 1) % HologramPictureBox.FILTER_NAMES.length));
        effects.child(
            HologramMuiTheme.button(
                () -> (removeBackground ? "✓ " : "") + "自动抠图",
                272,
                44,
                130,
                24,
                () -> removeBackground = !removeBackground));
        field(effects, "tolerance", "背景色容差 0…64", 0, 80, () -> removeBackground);
        effects.child(
            HologramMuiTheme
                .label(() -> "仅去除从边缘连通的主背景色；\n透明像素不生成，内部同色区域保留。", 142, 82, 260, 42, HologramMuiTheme.MUTED));
        panel().child(effects);
        panel().child(
            HologramMuiTheme.label(() -> notice, 14, 404, 592, 20, HologramMuiTheme.TITLE)
                .tooltipBuilder(t -> t.addLine(notice))
                .tooltipAutoUpdate(true));
        panel().child(
            HologramMuiTheme.button(
                () -> "返回工作区",
                14,
                430,
                160,
                20,
                () -> Minecraft.getMinecraft()
                    .displayGuiScreen(ui.host.parent())));
        panel().child(
            HologramMuiTheme.primaryButton(
                () -> loading ? "正在转换…" : ui.session.state().cells.isEmpty() ? "确认图片 · 生成像素画" : "确认图片 · 选择放置位置",
                342,
                430,
                264,
                20,
                this::confirm));
        refresh();
    }

    private void field(ParentWidget<?> target, String key, String label, int x, int y,
        java.util.function.BooleanSupplier enabled) {
        target.child(HologramMuiTheme.label(() -> label, x, y, 130, 15, HologramMuiTheme.MUTED));
        target.child(
            HologramMuiTheme.textField(values.get(key), x, y + 16, 130, 20, 6)
                .setEnabledIf(w -> enabled.getAsBoolean()));
    }

    private void value(String key, int initial) {
        values.put(key, new StringValue(Integer.toString(initial)));
    }

    private int number(String key) {
        return Integer.parseInt(
            values.get(key)
                .getStringValue());
    }

    private String filename(int row) {
        int at = page * 9 + row;
        return at >= files.size() ? "" : files.get(at);
    }

    private void toggleAspect() {
        if (keepAspect) try {
            HologramPictureBox current = settings();
            values.get("width")
                .setStringValue(Integer.toString(current.width));
            values.get("height")
                .setStringValue(Integer.toString(current.height));
        } catch (Exception ignored) {}
        keepAspect = !keepAspect;
    }

    private void refresh() {
        if (loading) return;
        loading = true;
        long token = ++generation;
        worker.execute(() -> {
            try {
                List<String> names = HologramPictureFiles.scan(folder);
                main(() -> {
                    if (token != generation || closed) return;
                    files.clear();
                    files.addAll(names);
                    page = 0;
                    loading = false;
                    if (source.source.length == 0 && !files.isEmpty()) load(files.get(0));
                    else notice = names.isEmpty() ? "目录已创建，请放入图片后点击刷新" : "读取到 " + files.size() + " 张图片；点击名称转换";
                });
            } catch (Exception failure) {
                failed(token, failure);
            }
        });
    }

    private void load(String filename) {
        if (loading || filename.isEmpty()) return;
        loading = true;
        long token = ++generation;
        ++previewGeneration;
        notice = "正在读取 " + filename + "…";
        worker.getQueue()
            .clear();
        worker.execute(() -> {
            try {
                HologramPictureBox image = HologramPictureFiles.load(folder, filename);
                main(() -> {
                    if (token != generation || closed) return;
                    source = image;
                    loading = false;
                    previewSignature = "";
                    notice = "已读取 " + filename + "；原图仅提供比例，按像素参数重新生成";
                });
            } catch (Exception failure) {
                failed(token, failure);
            }
        });
    }

    private HologramPictureBox settings() throws java.io.IOException {
        HologramPictureBox result = new HologramPictureBox();
        result.filename = source.filename;
        result.originalWidth = source.originalWidth;
        result.originalHeight = source.originalHeight;
        result.sourceWidth = source.sourceWidth;
        result.sourceHeight = source.sourceHeight;
        result.source = source.source;
        result.keepAspect = keepAspect;
        result.shortSide = number("shortSide");
        if (keepAspect) {
            result.resizeShortSide(result.shortSide);
            values.get("width")
                .setStringValue(Integer.toString(result.width));
            values.get("height")
                .setStringValue(Integer.toString(result.height));
        } else {
            result.width = number("width");
            result.height = number("height");
        }
        result.palette = palette;
        result.filter = filter;
        result.brightness = number("brightness");
        result.contrast = number("contrast");
        result.saturation = number("saturation");
        result.backgroundTolerance = number("tolerance");
        result.removeBackground = removeBackground;
        result.dither = dither;
        result.validate();
        return result;
    }

    private void confirm() {
        if (loading || closed) return;
        try {
            HologramTextBox draft = new HologramTextBox();
            draft.id = original.id;
            draft.x = original.x;
            draft.y = original.y;
            draft.z = original.z;
            draft.yaw = original.yaw;
            draft.pitch = original.pitch;
            draft.roll = original.roll;
            draft.turns = original.turns.clone();
            draft.picture = settings();
            draft.pixelX = draft.pixelY = number("pixel");
            draft.thickness = number("thickness");
            draft.validate();
            loading = true;
            long token = ++generation;
            worker.getQueue()
                .clear();
            worker.execute(() -> {
                try {
                    List<HologramTextBox.Cell> geometry = draft.generate();
                    main(() -> {
                        if (token != generation || closed) return;
                        loading = false;
                        if (!ui.textBoxes.allowed()) {
                            notice = "工作区状态已变化，请返回后重试";
                            return;
                        }
                        ui.textBoxes.begin(draft, geometry);
                        Minecraft.getMinecraft()
                            .displayGuiScreen(ui.host.parent());
                    });
                } catch (Exception failure) {
                    failed(token, failure);
                }
            });
        } catch (Exception failure) {
            notice = message(failure);
        }
    }

    private void failed(long token, Exception failure) {
        String message = message(failure);
        main(() -> {
            if (!closed && token == generation) {
                loading = false;
                notice = message;
            }
        });
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null ? "图片参数无效，请检查后重试" : failure.getMessage();
    }

    private static void main(Runnable callback) {
        Minecraft.getMinecraft()
            .func_152344_a(callback);
    }

    @Override
    protected void onMuiClose() {
        closed = true;
        generation++;
        worker.shutdownNow();
    }

    private final class PixelPreview extends com.cleanroommc.modularui.widget.Widget<PixelPreview> {

        @Override
        public void draw(com.cleanroommc.modularui.screen.viewport.ModularGuiContext context,
            com.cleanroommc.modularui.theme.WidgetThemeEntry<?> theme) {
            String signature = generation + ":" + keepAspect + removeBackground + dither + palette + ":" + filter;
            for (StringValue value : values.values()) signature += ":" + value.getStringValue();
            if (!loading && !closed && source.source.length > 0 && !signature.equals(previewSignature)) {
                previewSignature = signature;
                long token = ++previewGeneration;
                try {
                    HologramPictureBox candidate = settings();
                    worker.execute(() -> {
                        try {
                            byte[] pixels = candidate.rasterize();
                            main(() -> {
                                if (!closed && token == previewGeneration) {
                                    preview = pixels;
                                    previewPicture = candidate;
                                }
                            });
                        } catch (Exception failure) {
                            main(() -> {
                                if (!closed && token == previewGeneration) {
                                    preview = null;
                                    notice = message(failure);
                                }
                            });
                        }
                    });
                } catch (Exception failure) {
                    preview = null;
                    notice = message(failure);
                }
            }
            net.minecraft.client.gui.FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            String title = previewPicture == null ? "材料像素预览"
                : "材料像素预览 · " + previewPicture.width + " × " + previewPicture.height;
            font.drawString(title, 8, 6, HologramMuiTheme.TITLE);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                Tessellator t = Tessellator.instance;
                t.startDrawingQuads();
                for (int y = 24; y < 154; y += 10) for (int x = 8; x < 394; x += 10) {
                    t.setColorOpaque_I(((x / 10 + y / 10) & 1) == 0 ? 0x35414a : 0x253039);
                    quad(t, x, y, Math.min(10, 394 - x), Math.min(10, 154 - y));
                }
                if (preview != null && previewPicture != null) {
                    double scale = Math.min(386d / previewPicture.width, 130d / previewPicture.height);
                    double left = (402 - previewPicture.width * scale) / 2,
                        top = 24 + (130 - previewPicture.height * scale) / 2;
                    for (int y = 0; y < previewPicture.height; y++) for (int x = 0; x < previewPicture.width; x++) {
                        int index = (preview[y * previewPicture.width + x] & 255) - 1;
                        if (index < 0) continue;
                        t.setColorOpaque_I(previewPicture.paletteColor(index));
                        quad(t, left + x * scale, top + y * scale, scale, scale);
                    }
                }
                t.draw();
            } finally {
                GL11.glPopAttrib();
            }
        }
    }

    private static void quad(Tessellator t, double x, double y, double width, double height) {
        t.addVertex(x, y + height, 0);
        t.addVertex(x + width, y + height, 0);
        t.addVertex(x + width, y, 0);
        t.addVertex(x, y, 0);
    }
}
