package com.miaokatze.gtmb.hologram;

import java.util.Arrays;
import java.util.Collections;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;

import com.gtnewhorizon.structurelib.item.ItemConstructableTrigger;
import com.miaokatze.gtmb.common.items.hologram.ItemDecorationVoucher;
import com.miaokatze.gtmb.register.CreativeTabManager;
import com.miaokatze.gtmb.register.DecorationVoucherRegistry;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** No Forge startup needed: actual shapeless matching, namespace policy, and industrial vetoes. */
public final class DecorationVoucherRegression {

    private DecorationVoucherRegression() {}

    public static void run() {
        try {
            DecorationVoucherConfigRegression.run();
            HologramVoucherCoverageRegression.run();
        } catch (Exception failure) {
            throw new AssertionError("Voucher configuration", failure);
        }
        Item voucher = new ItemDecorationVoucher();
        Item official = new ItemConstructableTrigger();
        SimpleAssert.eq(64, voucher.getItemStackLimit(), "voucher stacks as consumable banknotes");
        SimpleAssert.eq(CreativeTabManager.CREATIVE_TAB, voucher.getCreativeTab(), "voucher is on own item tab");
        ShapelessRecipes recipe = DecorationVoucherRegistry.createRecipe(voucher, official);
        InventoryCrafting grid = new InventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(EntityPlayer player) {
                return true;
            }
        }, 3, 3);
        for (int left = 0; left < 9; left++) for (int right = left + 1; right < 9; right++) {
            grid.setInventorySlotContents(left, new ItemStack(official));
            SimpleAssert.that(!recipe.matches(grid, null), "one native projector cannot buy a voucher");
            grid.setInventorySlotContents(right, new ItemStack(official));
            SimpleAssert.that(recipe.matches(grid, null), "two native projectors work at any distinct positions");
            ItemStack output = recipe.getCraftingResult(grid);
            SimpleAssert.eq(voucher, output.getItem(), "voucher recipe produces the registered item type");
            SimpleAssert.eq(1, output.stackSize, "two projectors produce one voucher");
            output.stackSize = 63;
            SimpleAssert.eq(1, recipe.getCraftingResult(grid).stackSize, "craft results are detached");
            grid.setInventorySlotContents(left, null);
            grid.setInventorySlotContents(right, null);
        }
        grid.setInventorySlotContents(0, new ItemStack(official));
        grid.setInventorySlotContents(1, new ItemStack(official));
        grid.setInventorySlotContents(2, new ItemStack(official));
        SimpleAssert.that(!recipe.matches(grid, null), "extra native projector rejected");
        grid.setInventorySlotContents(2, new ItemStack(Items.feather));
        SimpleAssert.that(!recipe.matches(grid, null), "unrelated ingredient rejected");
        DecorationVoucherConfig.View enabled = new DecorationVoucherConfig.View(
            true,
            Arrays.asList(" LittleTiles ", "ArchitectureCraft", "minecraft", "", "bad:id", null));
        SimpleAssert.that(enabled.allows("LITTLETILES"), "native mixed case IDs compare consistently");
        SimpleAssert.that(enabled.allows("ArchitectureCraft"), "ArchitectureCraft namespace accepted");
        SimpleAssert.that(!enabled.allows("bad:id"), "full item IDs cannot accidentally become allowed mods");
        SimpleAssert.that(!enabled.allows("unknown"), "unknown namespace fails closed");
        SimpleAssert.that(
            !new DecorationVoucherConfig.View(false, Collections.singletonList("LittleTiles")).allows("LittleTiles"),
            "disabled policy rejects otherwise allowed decorations");
        for (String industrial : new String[] { "gregtech", "appliedenergistics2", "bartworks", "miscutils",
            "GoodGenerator", "IC2", "tectech", "ExtraUtilities" }) {
            SimpleAssert.that(
                HologramDecorationVoucher.industrial(industrial, "example.Item"),
                "administrator mod allowlist cannot waive industrial resources: " + industrial);
        }
        SimpleAssert.that(
            HologramDecorationVoucher.industrial("decor", "appeng.items.ItemMultiPart"),
            "native AE item remains protected under an alternate namespace");
        SimpleAssert.that(
            HologramDecorationVoucher.industrial("decor", "gregtech.common.blocks.ItemMachines"),
            "native GT machine remains protected under an alternate namespace");
        SimpleAssert.that(
            !HologramDecorationVoucher.industrial("minecraft", "net.minecraft.item.ItemBlock"),
            "native validated decor adapters may waive vanilla raw blocks");
        SimpleAssert.that(
            !HologramDecorationVoucher.eligible(new ItemStack(Items.feather)),
            "ordinary vanilla items cannot become decorative merely through a display label");
        SimpleAssert.that(
            !HologramDecorationVoucher.safe(new ItemStack(Items.water_bucket)),
            "filled fluid containers remain payable resources");
        SimpleAssert.that(
            !HologramDecorationVoucher.isVoucher(new ItemStack(voucher)),
            "unregistered voucher placeholder does not authenticate payment");
    }
}
