package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widgets.ListWidget;

/** Shared frame over native scroll clipping and native scrollbar hit testing. */
final class HologramRecessedList extends ListWidget<IWidget, HologramRecessedList> {

    private boolean paperEdges;

    HologramRecessedList() {
        showScrollShadows(false);
    }

    HologramRecessedList paperEdges() {
        paperEdges = true;
        return this;
    }

    @Override
    public void postDraw(ModularGuiContext context, boolean transformed) {
        super.postDraw(context, transformed);
        if (!transformed) {
            if (paperEdges) HologramScrollEdges.drawPaper(0, 0, getArea().width, getArea().height);
            else HologramScrollEdges.draw(0, 0, getArea().width, getArea().height);
        }
    }
}
