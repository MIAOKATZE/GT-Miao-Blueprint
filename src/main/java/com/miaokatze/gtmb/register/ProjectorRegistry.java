package com.miaokatze.gtmb.register;

import java.util.Collections;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

import cpw.mods.fml.common.registry.GameRegistry;

/** 独立物品及官方全息投影仪的一对一无序升级配方。 */
public final class ProjectorRegistry {

    public static final String ITEM_ID = "neko_hologram_projector";
    public static ItemNekoHologramProjector projector;

    private ProjectorRegistry() {}

    public static void registerItem() {
        projector = new ItemNekoHologramProjector();
        GameRegistry.registerItem(projector, ITEM_ID);
    }

    public static void registerRecipe() {
        GameRegistry.addRecipe(createRecipe(projector, StructureLibAPI.getDefaultHologramItem()));
    }

    public static ShapelessRecipes createRecipe(Item output, Item officialProjector) {
        if (output == null || officialProjector == null)
            throw new IllegalStateException("Projector registry must be initialized before its recipe");
        return new ShapelessRecipes(new ItemStack(output), Collections.singletonList(new ItemStack(officialProjector)));
    }
}
