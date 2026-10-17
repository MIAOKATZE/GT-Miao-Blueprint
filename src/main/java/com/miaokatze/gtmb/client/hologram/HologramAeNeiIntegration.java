package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import codechicken.nei.Button;
import codechicken.nei.LayoutManager;
import codechicken.nei.PanelWidget;
import codechicken.nei.VisiblityData;
import codechicken.nei.api.API;
import codechicken.nei.api.INEIGuiAdapter;

/** NEI display/search/page APIs only. Never invokes panel.handleClick, manager input, cheat or give actions. */
final class HologramAeNeiIntegration extends INEIGuiAdapter {

    private static boolean registered;

    static void register() {
        if (registered) return;
        API.registerNEIGuiHandler(new HologramAeNeiIntegration());
        registered = true;
    }

    @Override
    public VisiblityData modifyVisiblity(GuiContainer gui, VisiblityData visibility) {
        if (HologramAeContainerScreen.from(gui) != null) {
            visibility.showUtilityButtons = false;
            visibility.showStateButtons = false;
            visibility.showSubsetDropdown = false;
            visibility.enableDeleteMode = false;
        }
        return visibility;
    }

    @Override
    public Iterable<Integer> getItemSpawnSlots(GuiContainer gui, ItemStack stack) {
        return HologramAeContainerScreen.from(gui) != null ? Collections.emptyList() : null;
    }

    @Override
    public boolean handleDragNDrop(GuiContainer gui, int x, int y, ItemStack dragged, int button) {
        return HologramAeContainerScreen.from(gui) != null && HologramAeContainerScreen.from(gui)
            .acceptDrop(x, y, dragged == null ? null : dragged.copy(), button);
    }

    static ItemStack sampleAt(int x, int y) {
        ItemStack sample = LayoutManager.itemPanel == null ? null : LayoutManager.itemPanel.getStackMouseOver(x, y);
        if (sample == null && LayoutManager.bookmarkPanel != null)
            sample = LayoutManager.bookmarkPanel.getStackMouseOver(x, y);
        return sample == null ? null : sample.copy();
    }

    static void safeClick(int x, int y, int button) {
        if (LayoutManager.searchField != null) {
            if (LayoutManager.searchField.contains(x, y)) {
                LayoutManager.searchField.handleClick(x, y, button);
                return;
            }
            LayoutManager.searchField.setFocus(false);
        }
        if (button != 0) return;
        pageClick(LayoutManager.itemPanel, x, y);
        pageClick(LayoutManager.bookmarkPanel, x, y);
    }

    private static void pageClick(PanelWidget<?> panel, int x, int y) {
        if (panel == null) return;
        if (panel.pagePrev != null && panel.pagePrev.contains(x, y)) panel.pagePrev.handleClick(x, y, 0);
        if (panel.pageNext != null && panel.pageNext.contains(x, y)) panel.pageNext.handleClick(x, y, 0);
    }

    static boolean safeKey(char character, int key) {
        return LayoutManager.searchField != null && LayoutManager.searchField.focused()
            && LayoutManager.searchField.handleKeyPress(key, character);
    }

    static void safeScroll(int x, int y, int wheel) {
        PanelWidget<?> panel = LayoutManager.itemPanel != null && LayoutManager.itemPanel.contains(x, y)
            ? LayoutManager.itemPanel
            : LayoutManager.bookmarkPanel != null && LayoutManager.bookmarkPanel.contains(x, y)
                ? LayoutManager.bookmarkPanel
                : null;
        if (panel == null) return;
        Button page = wheel < 0 ? panel.pageNext : panel.pagePrev;
        if (page != null) page.handleClick(page.x + 1, page.y + 1, 0);
    }

    static void releaseSearchFocus() {
        if (LayoutManager.searchField != null) LayoutManager.searchField.setFocus(false);
    }
}
