package com.miaokatze.gtmb.common.util;

import java.util.List;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

/** Shared projector tooltip sections and the common-side-safe brand footer. */
public final class ProjectorTooltip {

    private ProjectorTooltip() {}

    public static void state(List<String> lines) {
        lines.add(StatCollector.translateToLocal("gtmb.tooltip.section.state"));
    }

    public static void description(List<String> lines, String... keys) {
        section(lines, "description", keys);
    }

    public static void controls(List<String> lines, String... keys) {
        section(lines, "controls", keys);
    }

    public static void section(List<String> lines, String section, String... keys) {
        lines.add("");
        lines.add(StatCollector.translateToLocal("gtmb.tooltip.section." + section));
        add(lines, keys);
    }

    public static void add(List<String> lines, String... keys) {
        for (String key : keys) lines.add(StatCollector.translateToLocal(key));
    }

    public static void value(List<String> lines, String label, Object value) {
        lines.add(EnumChatFormatting.GRAY + label + ": " + EnumChatFormatting.WHITE + value + EnumChatFormatting.RESET);
    }

    public static void finish(List<String> lines) {
        lines.add("");
        lines.add(
            EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted(
                "gtmb.tooltip.blueprint_website",
                EnumChatFormatting.AQUA + BlueprintWebsite.URL + EnumChatFormatting.RESET));
        lines.add(addedByLine());
    }

    public static String addedByLine() {
        return EnumChatFormatting.WHITE + StatCollector.translateToLocal("gtmb.tooltip.added_by")
            + " "
            + EnumChatFormatting.AQUA
            + "GT"
            + EnumChatFormatting.GREEN
            + "-"
            + EnumChatFormatting.GOLD
            + "Miao"
            + EnumChatFormatting.LIGHT_PURPLE
            + "-"
            + EnumChatFormatting.AQUA
            + "Blueprint"
            + EnumChatFormatting.RESET;
    }
}
