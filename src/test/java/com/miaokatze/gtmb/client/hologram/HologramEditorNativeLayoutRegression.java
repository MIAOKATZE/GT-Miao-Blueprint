package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Box;
import com.cleanroommc.modularui.widget.sizer.StandardResizer;
import com.cleanroommc.modularui.widgets.Expandable;
import com.cleanroommc.modularui.widgets.layout.SimpleFlow;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Runs actual MUI2 resizers without a Minecraft window or a rendered mockup. */
public final class HologramEditorNativeLayoutRegression {

    private HologramEditorNativeLayoutRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        waitsForParentAxis(GuiAxis.Y, 434, 388);
        waitsForParentAxis(GuiAxis.X, 224, 24);
        Expandable dock = new Expandable().coverChildrenWidth()
            .disableCoverChildrenHeight()
            .heightRel(1f);
        SimpleAssert.that(
            dock.resizer()
                .dependsOnChildrenX(),
            "dock width still follows its active view");
        SimpleAssert.that(
            !dock.resizer()
                .dependsOnChildrenY(),
            "dock height follows the bounded body instead of its children");
    }

    private static void waitsForParentAxis(GuiAxis axis, int width, int height) {
        Widget<?> parent = new Widget<>();
        parent.getArea()
            .setSize(width, height);
        StandardResizer parentSize = parent.resizer();
        parentSize.setResized(true, true, axis != GuiAxis.X, axis != GuiAxis.Y);

        Widget<?> child = new Widget<>().sizeRel(1f)
            .expanded();
        StandardResizer size = child.resizer();
        size.initialize(parentSize, parentSize);
        size.checkExpanded(axis);
        size.setResized(false);
        // The first traversal occurs before an ancestor Flow has allocated this parent.
        size.resize(true);
        size.resize(true);
        SimpleAssert.that(
            !size.isSizeCalculated(axis),
            "expanded axis remains pending across an unresolved-parent retry: " + axis);

        parentSize.setSizeResized(axis, true);
        SimpleFlow flow = new SimpleFlow();
        flow.widgets.add(child);
        flow.expanderCount = 1;
        int available = axis == GuiAxis.X ? width : height;
        flow.layout(axis, available, Box.ZERO, Alignment.MainAxis.START, 0);
        size.resize(true);
        SimpleAssert.eq(
            available,
            child.getArea()
                .getSize(axis),
            "actual MUI Flow allocates the complete remaining axis: " + axis);
        SimpleAssert.eq(
            axis == GuiAxis.X ? height : width,
            child.getArea()
                .getSize(axis.getOther()),
            "cross axis remains explicitly bounded: " + axis);
    }
}
