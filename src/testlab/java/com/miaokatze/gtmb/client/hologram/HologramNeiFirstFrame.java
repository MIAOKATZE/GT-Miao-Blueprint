// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;

import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.GuiRecipeTab;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Drives NEI's registered recipe GUI and observes its actual BlockRenderer framebuffer. */
final class HologramNeiFirstFrame {

    private static final String STRUCTURE_HANDLER = "blockrenderer6343.integration.gregtech.GTNEIMultiblockHandler";

    private HologramNeiFirstFrame() {}

    static GuiRecipe<?> open(Minecraft mc, ItemStack controller) {
        if (!GuiUsageRecipe.openRecipeGui("item", controller.copy())
            || !(mc.currentScreen instanceof GuiUsageRecipe gui))
            throw new IllegalStateException("NEI did not open the controller's usage recipes");
        IRecipeHandler structure = gui.currenthandlers.stream()
            .filter(
                handler -> STRUCTURE_HANDLER.equals(
                    handler.getClass()
                        .getName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("NEI has no registered structure recipe for the controller"));
        String name = GuiRecipeTab.getHandlerInfo(structure)
            .getHandlerName();
        gui.openTargetRecipe(Recipe.RecipeId.of((Object) controller, name, Collections.emptyList()));
        if (gui.getHandler() != structure || structure.numRecipes() != 1)
            throw new IllegalStateException("NEI did not select the exact controller structure recipe");
        return gui;
    }

    static Map<String, Object> frame(Minecraft mc, GuiRecipe<?> gui, ItemStack expected)
        throws ReflectiveOperationException {
        IRecipeHandler recipe = gui.getHandler();
        if (!STRUCTURE_HANDLER.equals(
            recipe.getClass()
                .getName()))
            throw new IllegalStateException("NEI changed its selected structure recipe");
        Object view = read(recipe, "guiHandler");
        Object renderer = read(view, "renderer");
        if (renderer == null) return Collections.emptyMap();
        ItemStack shown = (ItemStack) read(view, "stackForm");
        if (!expected.isItemEqual(shown)) throw new IllegalStateException("NEI displayed a different controller item");
        Set<?> rendered = (Set<?>) read(renderer, "renderedBlocks");
        if (rendered.size() <= 1) return Collections.emptyMap();
        World world = (World) read(renderer, "world");
        Object anchor = field(view.getClass(), "MB_PLACE_POS").get(null);
        int x = coordinate(anchor, "x"), y = coordinate(anchor, "y"), z = coordinate(anchor, "z");
        if (!(world.getTileEntity(x, y, z) instanceof IGregTechTileEntity tile)
            || tile.getMetaTileID() != expected.getItemDamage()
            || tile.getFrontFacing() != ForgeDirection.SOUTH)
            throw new IllegalStateException("NEI world controller identity or facing differs from the fixture");
        ItemStack trigger = (ItemStack) read(view, "trigger");
        Map<String, Integer> channels = new TreeMap<>();
        ChannelDataAccessor.iterateChannelData(trigger)
            .forEach(entry -> channels.put(entry.getKey(), entry.getValue()));
        if (trigger.stackSize != 1 || !channels.isEmpty()) throw new IllegalStateException(
            "NEI timing requires the default trigger after construction, got " + trigger.stackSize + " " + channels);
        Object rect = read(renderer, "rect");
        int[] viewport = { coordinate(rect, "x"), coordinate(rect, "y"), coordinate(rect, "z"), coordinate(rect, "w") };
        int pixels = drawnDepthPixels(mc, viewport);
        if (pixels == 0) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
            "recipeHandler",
            recipe.getClass()
                .getName());
        result.put(
            "screen",
            gui.getClass()
                .getName());
        result.put("controllerMeta", (int) tile.getMetaTileID());
        result.put(
            "facing",
            tile.getFrontFacing()
                .name());
        result.put("main", trigger.stackSize);
        result.put("channelsAfterConstruction", channels);
        result.put("channels", Collections.singletonMap("gt_hatch", 1));
        result.put("visibleBlockPositions", rendered.size());
        result.put("viewport", viewport);
        result.put("sceneDepthPixels", pixels);
        return result;
    }

    /** BlockRenderer clears depth in its own scissor. Count perspective depths away from the pane edges. */
    private static int drawnDepthPixels(Minecraft mc, int[] rect) {
        int marginX = Math.max(1, rect[2] / 8), marginY = Math.max(1, rect[3] / 8);
        int width = rect[2] - 2 * marginX, height = rect[3] - 2 * marginY;
        int left = rect[0] + marginX, bottom = rect[1] + marginY;
        if (width <= 0 || height <= 0
            || left < 0
            || bottom < 0
            || left + width > mc.displayWidth
            || bottom + height > mc.displayHeight) return 0;
        FloatBuffer depth = BufferUtils.createFloatBuffer(Math.multiplyExact(width, height));
        GL11.glReadPixels(left, bottom, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        int count = 0;
        float low = 1, high = 0;
        for (int i = 0; i < depth.capacity(); i++) {
            float pixel = depth.get(i);
            if (!(pixel > .5f && pixel < 1)) continue;
            count++;
            low = Math.min(low, pixel);
            high = Math.max(high, pixel);
        }
        return high > low && count >= 8 ? count : 0;
    }

    private static int coordinate(Object value, String name) throws ReflectiveOperationException {
        return field(value.getClass(), name).getInt(value);
    }

    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        return field(owner.getClass(), name).get(owner);
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field declared = owner.getDeclaredField(name);
                declared.setAccessible(true);
                return declared;
            } catch (NoSuchFieldException absent) {
                // BlockRenderer's scene state is declared across the concrete and base types.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
