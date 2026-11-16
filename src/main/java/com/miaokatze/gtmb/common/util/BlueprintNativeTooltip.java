// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.common.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.miaokatze.miaokatzecore.api.tooltip.component.NativeTooltipPage;
import com.miaokatze.miaokatzecore.api.tooltip.component.TooltipTable;

/** Reviewed three-tier item descriptions; presentation state belongs to Core, channel values to the stack. */
public final class BlueprintNativeTooltip {

    private static JsonObject authoredChinese;

    private BlueprintNativeTooltip() {}

    public static void projector(ItemStack stack, List<String> lines) {
        if (authored(stack, lines, "gtmb:neko_hologram_projector@0")) return;
        NativeTooltipPage page = NativeTooltipPage.forItem(stack);
        switch (page.tier()) {
            case QUICK:
                projectorQuick(page, stack);
                break;
            case DETAIL:
                projectorDetail(page, stack);
                break;
            default:
                projectorCommon(page, stack);
                break;
        }
        page.appendTo(stack, lines);
    }

    public static void voucher(ItemStack stack, List<String> lines) {
        if (authored(stack, lines, "gtmb:decoration_voucher@0")) return;
        NativeTooltipPage page = NativeTooltipPage.forItem(stack);
        switch (page.tier()) {
            case QUICK:
                voucherQuick(page, stack);
                break;
            case DETAIL:
                voucherDetail(page, stack);
                break;
            default:
                voucherCommon(page, stack);
                break;
        }
        page.appendTo(stack, lines);
    }

    /** Fixed copy follows the reviewed catalog; channels and the public website keep their runtime sources. */
    private static boolean authored(ItemStack stack, List<String> lines, String id) {
        if (!"zh_CN".equals(StatCollector.translateToLocal("gtmb.tooltip.authored_locale"))) return false;
        JsonObject catalog = authoredChinese();
        if (catalog == null) return false;
        for (JsonElement value : catalog.getAsJsonArray("items")) {
            JsonObject item = value.getAsJsonObject();
            if (!id.equals(
                item.get("id")
                    .getAsString()))
                continue;
            NativeTooltipPage page = NativeTooltipPage.forItem(stack);
            String tier = page.tier()
                .name()
                .toLowerCase(java.util.Locale.ROOT);
            for (JsonElement entry : item.getAsJsonObject("tiers")
                .getAsJsonArray(tier)) {
                JsonObject block = entry.getAsJsonObject();
                switch (block.get("type")
                    .getAsString()) {
                    case "line":
                        page.input(
                            block.get("text")
                                .getAsString());
                        break;
                    case "warning":
                        page.warning(
                            block.get("text")
                                .getAsString());
                        break;
                    case "divider":
                        page.divider();
                        break;
                    case "table":
                        List<List<String>> rows = new ArrayList<>();
                        for (JsonElement row : block.getAsJsonArray("rows")) rows.add(strings(row.getAsJsonArray()));
                        TooltipTable table = TooltipTable.of("", strings(block.getAsJsonArray("columns")), rows)
                            .withInputIcons();
                        for (int column = 0; column < table.headers()
                            .size(); column++) {
                            table = table.align(column, TooltipTable.Alignment.LEFT);
                        }
                        page.block(table);
                        break;
                    case "channels":
                        channels(page, stack);
                        break;
                    case "link":
                        website(
                            page,
                            block.has("qr") && block.get("qr")
                                .getAsBoolean());
                        break;
                    default:
                        throw new IllegalStateException("Unknown tooltip block: " + block);
                }
            }
            return page.appendTo(stack, lines);
        }
        return false;
    }

    private static synchronized JsonObject authoredChinese() {
        if (authoredChinese != null) return authoredChinese;
        String resource = "/assets/gtmb/tooltip/authored_zh_CN.json";
        InputStream stream = BlueprintNativeTooltip.class.getResourceAsStream(resource);
        if (stream == null) return null;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            authoredChinese = new JsonParser().parse(reader)
                .getAsJsonObject();
            return authoredChinese;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read tooltip catalog: " + resource, e);
        }
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : array) result.add(value.getAsString());
        return result;
    }

    private static void projectorQuick(NativeTooltipPage page, ItemStack stack) {
        page.input(text(0));
        channels(page, stack);
        website(page, false);
        page.divider();
        page.input(text(1));
        page.line(ProjectorTooltip.addedByLine());
    }

    private static void projectorCommon(NativeTooltipPage page, ItemStack stack) {
        page.input(text(0));
        channels(page, stack);
        website(page, false);
        page.divider();
        page.input(text(2));
        table(
            page,
            new int[] { 3, 4, 5 },
            new int[][] { { 6, 7, 8 }, { 6, 9, 10 }, { 11, 12, 13 }, { 14, 15, 16 }, { 17, 15, 18 }, { 17, 19, 20 }, });
        page.divider();
        page.input(text(21));
        page.input(text(22));
        table(page, new int[] { 23, 24 }, new int[][] { { 25, 26 }, { 27, 28 }, { 29, 30 }, });
        page.divider();
        page.input(text(31));
        table(page, new int[] { 3, 32, 33 }, new int[][] { { 34, 15, 35 }, { 36, 37, 13 }, });
        page.divider();
        page.input(text(38));
        page.line(ProjectorTooltip.addedByLine());
    }

    private static void projectorDetail(NativeTooltipPage page, ItemStack stack) {
        page.input(text(0));
        channels(page, stack);
        website(page, true);
        page.divider();
        page.input(text(2));
        table(
            page,
            new int[] { 3, 4, 5 },
            new int[][] { { 6, 7, 8 }, { 6, 9, 10 }, { 39, 40, 41 }, { 11, 12, 13 }, { 14, 15, 16 }, { 14, 42, 43 },
                { 17, 15, 18 }, { 17, 19, 20 }, { 17, 44, 45 }, });
        page.input(text(46));
        page.divider();
        page.input(text(21));
        page.input(text(22));
        table(page, new int[] { 23, 24 }, new int[][] { { 25, 26 }, { 27, 47 }, { 29, 30 }, });
        page.input(text(48));
        page.divider();
        page.input(text(49));
        table(
            page,
            new int[] { 3, 4, 5 },
            new int[][] { { 50, 19, 51 }, { 52, 53, 54 }, { 55, 56, 57 }, { 58, 53, 59 }, });
        page.divider();
        page.input(text(31));
        table(
            page,
            new int[] { 3, 4, 5 },
            new int[][] { { 34, 15, 35 }, { 60, 61, 35 }, { 36, 37, 13 }, { 62, 40, 63 }, });
        page.divider();
        page.input(text(38));
        page.line(ProjectorTooltip.addedByLine());
    }

    private static void voucherQuick(NativeTooltipPage page, ItemStack stack) {
        page.input(text(64));
        page.input(text(65));
        page.input(text(66));
        page.line(ProjectorTooltip.addedByLine());
    }

    private static void voucherCommon(NativeTooltipPage page, ItemStack stack) {
        page.input(text(64));
        page.input(text(65));
        page.divider();
        table(page, new int[] { 67, 68 }, new int[][] { { 69, 70 }, { 71, 72 }, });
        page.divider();
        page.input(text(66));
        page.input(text(73));
        page.line(ProjectorTooltip.addedByLine());
    }

    private static void voucherDetail(NativeTooltipPage page, ItemStack stack) {
        page.input(text(64));
        page.input(text(65));
        page.divider();
        table(page, new int[] { 67, 68 }, new int[][] { { 69, 74 }, { 75, 76 }, { 71, 77 }, });
        page.divider();
        page.input(text(66));
        page.input(text(73));
        page.divider();
        page.input(text(78));
        table(page, new int[] { 79, 80 }, new int[][] { { 81, 82 }, { 83, 84 }, });
        page.input(text(85));
        page.line(ProjectorTooltip.addedByLine());
    }

    private static void channels(NativeTooltipPage page, ItemStack stack) {
        int main = stack.hasTagCompound() ? Math.max(
            1,
            stack.getTagCompound()
                .getInteger("gtitHologramMain"))
            : 1;
        page.line("§7" + StatCollector.translateToLocal("gtmb.hologram.main") + ": §f" + main + "§r");
        ChannelDataAccessor.iterateChannelData(stack)
            .limit(8)
            .forEach(entry -> page.line("§7" + entry.getKey() + ": §f" + entry.getValue() + "§r"));
    }

    private static void website(NativeTooltipPage page, boolean qr) {
        String prefix = StatCollector.translateToLocal("gtmb.tooltip.native.website");
        if (qr) page.qr(prefix, BlueprintWebsite.URL);
        else page.line(prefix + BlueprintWebsite.URL);
    }

    private static void table(NativeTooltipPage page, int[] headers, int[][] rows) {
        List<List<String>> content = new ArrayList<>();
        for (int[] row : rows) content.add(cells(row));
        TooltipTable table = TooltipTable.of("", cells(headers), content)
            .withInputIcons();
        for (int i = 0; i < headers.length; i++) table = table.align(i, TooltipTable.Alignment.LEFT);
        page.block(table);
    }

    private static List<String> cells(int[] ids) {
        List<String> cells = new ArrayList<>();
        for (int id : ids) cells.add(text(id));
        return cells;
    }

    private static String text(int id) {
        return StatCollector.translateToLocal("gtmb.tooltip.native." + id);
    }
}
