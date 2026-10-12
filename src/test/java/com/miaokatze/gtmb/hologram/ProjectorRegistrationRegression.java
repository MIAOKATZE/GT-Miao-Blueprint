package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;

import com.gtnewhorizon.structurelib.item.ItemConstructableTrigger;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.register.CreativeTabManager;
import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** 检查独立物品身份和真实原版无序配方；专用服另检查实际 FML 注册及配方入口。 */
public final class ProjectorRegistrationRegression {

    private ProjectorRegistrationRegression() {}

    public static void registry() {
        ItemNekoHologramProjector projector = new ItemNekoHologramProjector();
        SimpleAssert.eq("gtmb", GTMiaoBlueprint.MODID, "independent mod id");
        SimpleAssert.eq("neko_hologram_projector", ProjectorRegistry.ITEM_ID, "stable item registry name");
        SimpleAssert.eq(CreativeTabManager.CREATIVE_TAB, projector.getCreativeTab(), "independent creative tab");
        SimpleAssert.eq(1, projector.getItemStackLimit(), "projector cannot stack");
        SimpleAssert.that(ItemNekoHologramProjector.isProjector(new ItemStack(projector)), "own projector recognized");
        SimpleAssert
            .that(!ItemNekoHologramProjector.isProjector(new ItemStack(Items.feather)), "unrelated tool rejected");
        SimpleAssert.eq(1, com.miaokatze.gtmb.api.GTMBApi.getLegacyMappingContractVersion(), "public mapping contract");
        SimpleAssert.that(!com.miaokatze.gtmb.api.GTMBApi.isEngineOwner(), "pure JVM has not initialized FML engine");
        SimpleAssert
            .that(com.miaokatze.gtmb.api.GTMBApi.getProjectorItem() == null, "API exposes no pre-init placeholder");
        SimpleAssert.that(
            !com.miaokatze.gtmb.api.GTMBApi.isProjector(new ItemStack(projector)),
            "unregistered item is not API identity");
    }

    public static void recipe() {
        Item projector = new ItemNekoHologramProjector();
        Item official = new ItemConstructableTrigger();
        ShapelessRecipes recipe = ProjectorRegistry.createRecipe(projector, official);
        InventoryCrafting grid = new InventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(EntityPlayer player) {
                return true;
            }
        }, 3, 3);
        SimpleAssert.that(!recipe.matches(grid, null), "empty crafting grid rejected");
        for (int slot = 0; slot < 9; slot++) {
            grid.setInventorySlotContents(slot, new ItemStack(official));
            SimpleAssert.that(recipe.matches(grid, null), "official projector accepted at any position " + slot);
            ItemStack output = recipe.getCraftingResult(grid);
            SimpleAssert.eq(projector, output.getItem(), "recipe returns independent projector");
            SimpleAssert.eq(1, output.stackSize, "one official projector produces one independent projector");
            output.stackSize = 7;
            SimpleAssert.eq(1, recipe.getCraftingResult(grid).stackSize, "recipe output is a detached copy");
            grid.setInventorySlotContents(slot, null);
        }
        grid.setInventorySlotContents(0, new ItemStack(official));
        grid.setInventorySlotContents(1, new ItemStack(official));
        SimpleAssert.that(!recipe.matches(grid, null), "extra official projector rejected");
        grid.setInventorySlotContents(1, new ItemStack(Items.feather));
        SimpleAssert.that(!recipe.matches(grid, null), "extra unrelated ingredient rejected");
        grid.setInventorySlotContents(0, new ItemStack(Items.feather));
        grid.setInventorySlotContents(1, null);
        SimpleAssert.that(!recipe.matches(grid, null), "unrelated single ingredient rejected");
    }
}
