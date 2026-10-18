package com.miaokatze.gtmb.register;

import java.util.Arrays;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.miaokatze.gtmb.common.items.hologram.ItemDecorationVoucher;
import com.miaokatze.gtmb.hologram.DecorationVoucherConfig;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;

/** Uses exactly the same native StructureLib projector identity as the existing projector upgrade. */
public final class DecorationVoucherRegistry {

    public static final String ITEM_ID = "decoration_voucher";
    public static ItemDecorationVoucher voucher;

    private DecorationVoucherRegistry() {}

    public static void registerItem() {
        DecorationVoucherConfig.load(
            Loader.instance()
                .getConfigDir());
        voucher = new ItemDecorationVoucher();
        GameRegistry.registerItem(voucher, ITEM_ID);
    }

    public static void registerRecipe() {
        if (DecorationVoucherConfig.view().enabled)
            GameRegistry.addRecipe(createRecipe(voucher, StructureLibAPI.getDefaultHologramItem()));
    }

    public static ShapelessRecipes createRecipe(Item output, Item officialProjector) {
        if (output == null || officialProjector == null)
            throw new IllegalStateException("Voucher registry must be initialized before its recipe");
        return new ShapelessRecipes(
            new ItemStack(output),
            Arrays.asList(new ItemStack(officialProjector), new ItemStack(officialProjector)));
    }
}
