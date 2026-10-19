package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widgets.ListWidget;

/** Shared frame over native scroll clipping and native scrollbar hit testing. */
final class HologramRecessedList extends ListWidget<IWidget, HologramRecessedList> {

    HologramRecessedList() {
        showScrollShadows(false);
    }

    @Override
    public void postDraw(ModularGuiContext context, boolean transformed) {
        super.postDraw(context, transformed);
        if (!transformed) HologramScrollEdges.draw(0, 0, getArea().width, getArea().height);
    }
}
