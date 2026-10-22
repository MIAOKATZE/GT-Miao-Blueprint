package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.screen.ModularPanel;

/** Lifecycle adapter over the production material dialog, with no imitation controls or renderer. */
final class HologramEditorMaterialLesson {

    static final String PANEL_NAME = "gtmb_tutorial_materials";
    private NBTTagCompound data;
    private ModularPanel panel;

    void show(HologramState source) {
        data = source.data;
    }

    boolean visible() {
        return data != null;
    }

    void close() {
        data = null;
        if (panel != null) panel.closeIfOpen();
        panel = null;
    }

    ModularPanel build(HologramUiModules ui) {
        if (panel == null) panel = new HologramMaterialConfirmationDialog(ui).buildLesson(this::close);
        return panel;
    }

    int[] bounds(int width, int height) {
        if (panel == null || !panel.isValid() || !panel.isOpen()) return null;
        int available = height - HologramEditorTutorialOverlay.RESERVED_HEIGHT;
        float scale = Math.min(1f, Math.min(width / 644f, available / 458f));
        return new int[] { Math.round((width - 632 * scale) / 2), Math.round((available - 446 * scale) / 2),
            Math.round(632 * scale), Math.round(446 * scale) };
    }

    void draw(HologramUiModules ui) {}
}
