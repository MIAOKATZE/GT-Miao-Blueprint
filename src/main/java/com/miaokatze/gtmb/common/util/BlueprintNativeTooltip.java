// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.common.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.miaokatze.miaokatzecore.api.tooltip.component.NativeTooltipPage;
import com.miaokatze.miaokatzecore.api.tooltip.component.TooltipTable;

/** Reviewed three-tier item descriptions; presentation state belongs to Core, channel values to the stack. */
public final class BlueprintNativeTooltip {

    private BlueprintNativeTooltip() {}

    public static void projector(ItemStack stack, List<String> lines) {
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
        table(page, new int[] { 3, 4, 5 }, new int[][] {
            { 6, 7, 8 },
            { 6, 9, 10 },
            { 11, 12, 13 },
            { 14, 15, 16 },
            { 17, 15, 18 },
            { 17, 19, 20 },
        });
        page.divider();
        page.input(text(21));
        page.input(text(22));
        table(page, new int[] { 23, 24 }, new int[][] {
            { 25, 26 },
            { 27, 28 },
            { 29, 30 },
        });
        page.divider();
        page.input(text(31));
        table(page, new int[] { 3, 32, 33 }, new int[][] {
            { 34, 15, 35 },
            { 36, 37, 13 },
        });
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
        table(page, new int[] { 3, 4, 5 }, new int[][] {
            { 6, 7, 8 },
            { 6, 9, 10 },
            { 39, 40, 41 },
            { 11, 12, 13 },
            { 14, 15, 16 },
            { 14, 42, 43 },
            { 17, 15, 18 },
            { 17, 19, 20 },
            { 17, 44, 45 },
        });
        page.input(text(46));
        page.divider();
        page.input(text(21));
        page.input(text(22));
        table(page, new int[] { 23, 24 }, new int[][] {
            { 25, 26 },
            { 27, 47 },
            { 29, 30 },
        });
        page.input(text(48));
        page.divider();
        page.input(text(49));
        table(page, new int[] { 3, 4, 5 }, new int[][] {
            { 50, 19, 51 },
            { 52, 53, 54 },
            { 55, 56, 57 },
            { 58, 53, 59 },
        });
        page.divider();
        page.input(text(31));
        table(page, new int[] { 3, 4, 5 }, new int[][] {
            { 34, 15, 35 },
            { 60, 61, 35 },
            { 36, 37, 13 },
            { 62, 40, 63 },
        });
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
        table(page, new int[] { 67, 68 }, new int[][] {
            { 69, 70 },
            { 71, 72 },
        });
        page.divider();
        page.input(text(66));
        page.input(text(73));
        page.line(ProjectorTooltip.addedByLine());
    }

    private static void voucherDetail(NativeTooltipPage page, ItemStack stack) {
        page.input(text(64));
        page.input(text(65));
        page.divider();
        table(page, new int[] { 67, 68 }, new int[][] {
            { 69, 74 },
            { 75, 76 },
            { 71, 77 },
        });
        page.divider();
        page.input(text(66));
        page.input(text(73));
        page.divider();
        page.input(text(78));
        table(page, new int[] { 79, 80 }, new int[][] {
            { 81, 82 },
            { 83, 84 },
        });
        page.input(text(85));
        page.line(ProjectorTooltip.addedByLine());
    }

    private static void channels(NativeTooltipPage page, ItemStack stack) {
        int main = stack.hasTagCompound() ? Math.max(1, stack.getTagCompound().getInteger("gtitHologramMain")) : 1;
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
        TooltipTable table = TooltipTable.of("", cells(headers), content).withInputIcons();
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
