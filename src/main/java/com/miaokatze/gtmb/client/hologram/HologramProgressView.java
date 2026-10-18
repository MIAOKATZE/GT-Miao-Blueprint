package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.StatCollector;

/** One compact style for panels; HUDs use the same factual summary without taking another row of buttons. */
final class HologramProgressView {

    static final class RenderWitness {

        final HologramProgress progress;
        final int x, y, width, height;
        final String label, metric;
        final long frameSerial, drawSerial;

        RenderWitness(HologramProgress progress, int x, int y, int width, int height, String label, String metric,
            long frameSerial, long drawSerial) {
            this.progress = progress;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
            this.metric = metric;
            this.frameSerial = frameSerial;
            this.drawSerial = drawSerial;
        }
    }

    private static final java.util.List<RenderWitness> rendered = new java.util.ArrayList<>();
    private static long frameSerial, drawSerial;

    static void beginFrame() {
        frameSerial++;
        rendered.clear();
    }

    static java.util.List<RenderWitness> rendered() {
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(rendered));
    }

    private HologramProgressView() {}

    static String phase(String phase) {
        String key = "gtmb.hologram.progress."
            + (phase.startsWith("localPlan.") ? "area.detail." + phase.substring("localPlan.".length()) : phase);
        String value = StatCollector.translateToLocal(key);
        return key.equals(value) ? phase.replace('.', ' ') : value;
    }

    static String summary(HologramProgress value, long now) {
        if (value == null) return "";
        return phaseLabel(value) + " · " + metric(value, now);
    }

    private static String phaseLabel(HologramProgress value) {
        return phase(value.phase) + (value.detail.isEmpty() ? ""
            : " · "
                + phase((value.phase.startsWith("snapshot.") ? "snapshot.detail." : "area.detail.") + value.detail));
    }

    private static String metric(HologramProgress value, long now) {
        String elapsed = value.elapsedSeconds(now) + "s";
        if (value.percent() < 0) return elapsed;
        String detail = detail(value);
        return (detail.isEmpty() ? "" : detail + " · ") + value.percent() + "% · " + elapsed;
    }

    static String detail(HologramProgress value) {
        if (value == null || value.total == 0) return "";
        if ("bytes".equals(value.unit))
            return HologramLibraryClient.bytes(value.done) + " / " + HologramLibraryClient.bytes(value.total);
        return value.done + " / " + value.total + " " + phase("unit." + value.unit);
    }

    static void draw(FontRenderer font, HologramProgress value, int x, int y, int width, int barHeight) {
        if (value == null || width < 40) return;
        long now = System.nanoTime();
        String metric = metric(value, now);
        int metricWidth = font.getStringWidth(metric);
        String label = font.trimStringToWidth(phaseLabel(value), Math.max(0, width - metricWidth - 8));
        font.drawString(label, x, y, 0xb9d5d4);
        font.drawString(metric, x + width - metricWidth, y, 0xf2dca5);
        if (rendered.size() < 16) rendered.add(
            new RenderWitness(
                value,
                x,
                y,
                width,
                font.FONT_HEIGHT + (barHeight > 0 ? 3 + barHeight : 0),
                label,
                metric,
                frameSerial,
                ++drawSerial));
        if (barHeight < 1) return;
        int top = y + font.FONT_HEIGHT + 3;
        Gui.drawRect(x, top, x + width, top + barHeight, 0xff16303b);
        if (value.percent() >= 0) {
            int fill = width * value.percent() / 100;
            Gui.drawRect(x, top, x + fill, top + barHeight, value.percent() == 100 ? 0xff82d6aa : 0xff81dce9);
        } else {
            int segment = Math.max(8, width / 5), travel = Math.max(1, width - segment);
            long position = Math.max(0, now - value.startedAt) / 10000000L % (travel * 2L);
            int offset = (int) (position > travel ? travel * 2L - position : position);
            Gui.drawRect(x + offset, top, x + offset + segment, top + barHeight, 0xff81dce9);
        }
    }
}
