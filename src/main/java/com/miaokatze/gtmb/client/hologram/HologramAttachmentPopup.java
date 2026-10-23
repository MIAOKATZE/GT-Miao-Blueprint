package com.miaokatze.gtmb.client.hologram;

import java.util.function.Consumer;

import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.Dialog;

/** Attachment-only child windows share one fit transform for native rendering and input. */
final class HologramAttachmentPopup<T> extends Dialog<T> {

    HologramAttachmentPopup(String name, Consumer<T> result) {
        super(name, result);
    }

    static void fit(ModularPanel panel, IViewportStack stack) {
        int screenWidth = panel.getScreen()
            .getScreenArea().width;
        int screenHeight = panel.getScreen()
            .getScreenArea().height;
        int width = Math.max(1, panel.getArea().width), height = Math.max(1, panel.getArea().height);
        float scale = Math.min(
            1f,
            Math.min(Math.max(1, screenWidth - 12) / (float) width, Math.max(1, screenHeight - 12) / (float) height));
        stack.translate((screenWidth - width * scale) / 2f, (screenHeight - height * scale) / 2f);
        stack.scale(scale, scale);
    }

    @Override
    public void transform(IViewportStack stack) {
        fit(this, stack);
    }

    @Override
    public boolean shouldAnimate() {
        return false;
    }
}
