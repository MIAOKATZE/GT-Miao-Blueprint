// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.hologram.HologramLayerStockBill.Need;
import com.miaokatze.gtmb.hologram.HologramLayerStockBill.Term;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Isolated stock arithmetic; native registry admission has its own opt-in fixtures. */
public final class HologramProjectedVariantPricingRegression {

    private HologramProjectedVariantPricingRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramProjectedVariantPricingRegression.class, args)) return;
        run();
        System.out.println("projected_variant_stock_arithmetic: passed");
    }

    public static void run() throws Exception {
        wholeBillAndCursor();
    }

    private static void wholeBillAndCursor() throws Exception {
        Item stone = Item.getItemFromBlock(Blocks.stone), glass = Item.getItemFromBlock(Blocks.glass);
        ItemStack base = new ItemStack(stone, 1), cover = new ItemStack(glass, 1);
        Term quarter = new Term(base, 1, 4), third = new Term(base, 1, 3), half = new Term(cover, 1, 2);
        var bill = new HologramLayerStockBill(Arrays.asList(new HologramLayerStockBill.Row(1, new Term(base, 1, 1)),
            new HologramLayerStockBill.Row(4, quarter), new HologramLayerStockBill.Row(3, third, third),
            new HologramLayerStockBill.Row(5, half)));
        require(quantity(bill.required(), stone, 0) == 4 && quantity(bill.required(), glass, 0) == 3,
            "group fractional native, secondary and full-block stock by one exact item before rounding");
        var cursor = bill.cursor();
        var failed = cursor.quote(1); // Never settle a failed operation.
        var same = cursor.quote(1);
        require(quantity(failed.debit, stone, 0) == 1 && quantity(same.debit, stone, 0) == 1,
            "retry of an unpaid placement cannot inherit a fractional credit");
        cursor.confirmedPlacement(same);
        expect(() -> cursor.confirmedPlacement(failed));
        expect(() -> bill.cursor().confirmedPlacement(same));
        require(cursor.quote(1).debit.isEmpty(), "fraction carries across successful operations and slices");
        cursor.confirmedAlreadySatisfied(1);
        long stonePaid = 1, glassPaid = 0;
        for (int row : new int[] { 1, 3, 2, 2, 3, 1, 2, 0, 3, 3, 3 }) {
            var quote = cursor.quote(row);
            stonePaid += quantity(quote.debit, stone, 0);
            glassPaid += quantity(quote.debit, glass, 0);
            cursor.confirmedPlacement(quote);
        }
        require(stonePaid == 4 && glassPaid == 3, "interleaved exact debits converge on the one rounded raw balance");
        expect(() -> cursor.quote(2));

        var satisfied = bill.cursor();
        satisfied.confirmedAlreadySatisfied(1);
        require(quantity(satisfied.quote(1).debit, stone, 0) == 1,
            "an exact existing cell consumes declared occurrence but never earns paid fractional credit");
        expect(() -> new HologramLayerStockBill(Collections.singletonList(new HologramLayerStockBill.Row(
            HologramBlueprintLimits.MAX_CELLS, new Term(base, 4096, 1)))));
    }

    private static int quantity(List<Need> needs, Item item, int damage) {
        int total = 0;
        for (Need need : needs) if (need.resource.item == item && need.resource.damage == damage) total += need.count;
        return total;
    }

    private interface Checked {

        void run() throws IOException;
    }

    private static void expect(Checked checked) throws IOException {
        try {
            checked.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Unsupported scheme received a material bill");
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
