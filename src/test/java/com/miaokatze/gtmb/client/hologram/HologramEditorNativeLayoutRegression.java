// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widget.sizer.Box;
import com.cleanroommc.modularui.widget.sizer.StandardResizer;
import com.cleanroommc.modularui.widgets.Expandable;
import com.cleanroommc.modularui.widgets.layout.Flow;
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
        ownedDockedPaperTracksTheResizedSlot();
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

    private static void ownedDockedPaperTracksTheResizedSlot() {
        Widget<?> overlay = new Widget<>();
        overlay.getArea()
            .setBounds(10, 35, 640, 303);
        overlay.resizer()
            .setResized(true);
        Flow paper = Flow.col()
            .size(184, 250);
        StandardResizer size = paper.resizer();
        size.initialize(overlay.resizer(), overlay.resizer());
        Area slot = new Area(466, 57, 184, 281);
        SimpleAssert.that(
            !HologramEditorShell.layoutDockedInspector(paper, slot, overlay.getArea(), false, false),
            "closed dock is not available even if a prior frame left a nonempty slot");
        SimpleAssert.that(
            !HologramEditorShell.layoutDockedInspector(paper, slot, overlay.getArea(), true, true),
            "opening waits for the actual MUI anchor resize");
        SimpleAssert.that(
            !HologramEditorShell
                .layoutDockedInspector(paper, new Area(650, 57, 0, 281), overlay.getArea(), true, false),
            "zero-width collapsed anchor denied");
        SimpleAssert.that(
            HologramEditorShell.layoutDockedInspector(paper, slot, overlay.getArea(), true, false),
            "owned, open, resolved native dock anchor");
        size.setResized(false);
        size.resize(false);
        size.preApplyPos();
        SimpleAssert.eq(slot.x, paper.getArea().x, "first layout is at the right dock, not the overlay origin");
        SimpleAssert.eq(slot.y, paper.getArea().y, "native dock y");
        SimpleAssert.eq(slot.width, paper.getArea().width, "exact native reserved width");
        SimpleAssert.eq(slot.height, paper.getArea().height, "exact native reserved height");
        size.onResized();
        SimpleAssert.that(
            HologramEditorShell.layoutDockedInspector(paper, slot, overlay.getArea(), true, false)
                && !size.requiresResize(),
            "same settled geometry does not reschedule");

        overlay.getArea()
            .setBounds(35, 40, 540, 303);
        slot.setBounds(391, 62, 184, 281);
        SimpleAssert.that(
            !HologramEditorShell.layoutDockedInspector(paper, slot, overlay.getArea(), true, true)
                && !size.requiresResize(),
            "a pending new generation does not publish or mutate the settled paper");
        SimpleAssert.that(
            HologramEditorShell.layoutDockedInspector(paper, slot, overlay.getArea(), true, false)
                && size.requiresResize(),
            "newly resolved geometry schedules exactly the paper");
        size.setResized(false);
        size.resize(false);
        size.preApplyPos();
        SimpleAssert.eq(slot.x, paper.getArea().x, "new native anchor x after width change");
        SimpleAssert.eq(slot.y, paper.getArea().y, "new native anchor y after origin change");
        System.out
            .println("GTMB native inspector layout PASS: closed/stale fences, owned right slot and no stable resize");
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
