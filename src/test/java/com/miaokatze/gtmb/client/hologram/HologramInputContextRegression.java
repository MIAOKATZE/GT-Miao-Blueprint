package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramInputContextRegression {

    private HologramInputContextRegression() {}

    public static void run() {
        HologramWorldTargetRegression.run();
        selectionHints();
        controllerProjectionContext();
        Object world = new Object(), item = new Object(), stack = new Object();
        HologramInputContext context = new HologramInputContext();
        HologramWorldFlow.UsePressFence fence = new HologramWorldFlow.UsePressFence();
        SimpleAssert.that(
            context.observe(world, null, item, 0, 2, "projector", stack, false),
            "first held-tool context is a transition");
        fence.observe(false, false);
        boolean changed = context.observe(world, null, item, 0, 2, "projector", new Object(), false);
        SimpleAssert.that(!changed, "server inventory stack copies preserve logical input context");
        fence.observe(true, changed);
        SimpleAssert.that(fence.consume(), "first corner or confirmation stays usable after slot synchronization");
        SimpleAssert.that(!fence.consume(), "held right mouse still emits one request");
        fence.observe(false, false);
        SimpleAssert.that(fence.consume(), "releasing the physical button permits the second corner");
        SimpleAssert.that(
            context.observe(world, null, item, 0, 2, "another", stack, false),
            "another tool UUID requires release even in the same slot");
        SimpleAssert.that(
            context.observe(world, null, item, 0, 3, "another", stack, false),
            "changing hotbar slot starts another input context");
        SimpleAssert.that(
            context.observe(world, new Object(), item, 0, 3, "another", stack, false),
            "opening a screen isolates world presses");
        HologramInputContext legacy = new HologramInputContext();
        legacy.observe(world, null, item, 0, 2, "", stack, false);
        SimpleAssert.that(
            legacy.observe(world, null, item, 0, 2, "", new Object(), false),
            "unidentified legacy stacks cannot inherit another tool's pressed state");
        SimpleAssert.that(
            HologramInputContext.selectionTool("projector", "projector"),
            "a synchronized copy of the selected projector resumes its own area");
        SimpleAssert.that(
            !HologramInputContext.selectionTool("projector", "another"),
            "another projector cannot mark a suspended area owned by the original UUID");
        SimpleAssert.that(
            !HologramInputContext.selectionTool("projector", ""),
            "an unidentified held stack cannot mark an identified selection");
        SimpleAssert.that(
            HologramWorldGuide.highlight("区域框选 · Ctrl+Shift 减选 · Esc 退出", "\u00a7b")
                .contains("\u00a7eCtrl+Shift\u00a7r\u00a7b"),
            "world guidance colors complete shortcuts without splitting their modifiers");
    }

    private static void controllerProjectionContext() {
        net.minecraft.nbt.NBTTagCompound data = new net.minecraft.nbt.NBTTagCompound();
        data.setString("session", "controller-preview");
        HologramState idle = new HologramState(data);
        SimpleAssert.that(
            !HologramInputContext.worldProjection(idle, false, false),
            "closing the controller UI and switching tools immediately hides its cached world ghost");
        SimpleAssert.that(
            HologramInputContext.worldProjection(idle, true, false),
            "holding the projector preserves its enabled world preview");
        SimpleAssert.that(
            HologramInputContext.worldProjection(idle, false, true),
            "the related projector UI retains the controller preview context");
        for (int job : new int[] { 1, 2 }) {
            data.setInteger("job", job);
            SimpleAssert.that(
                HologramInputContext.worldProjection(new HologramState(data), false, false),
                "active and paused construction retain their existing world projection lifetime");
        }
        for (int job : new int[] { 3, 4, 5 }) {
            data.setInteger("job", job);
            SimpleAssert.that(
                !HologramInputContext.worldProjection(new HologramState(data), false, false),
                "terminal controller jobs do not retain an abandoned tool projection");
        }
        data.setBoolean("virtual", true);
        data.setBoolean("virtualPlacement", true);
        data.setInteger("job", 0);
        SimpleAssert.that(
            HologramInputContext.worldProjection(new HologramState(data), false, false),
            "virtual blueprint placement still uses its existing placement-specific visibility gates");
    }

    private static void selectionHints() {
        com.miaokatze.gtmb.hologram.HologramAreaSelection selection = new com.miaokatze.gtmb.hologram.HologramAreaSelection();
        try {
            SimpleAssert.eq(
                "worldGuideSelectionBoth",
                HologramWorldGuide.selectionCursorKey(selection.snapshot()),
                "empty selection prompts both mouse buttons");
            selection.point(2, 3, 64, 3, "replace");
            SimpleAssert.eq(
                "worldGuideSelectionFirst",
                HologramWorldGuide.selectionCursorKey(selection.snapshot()),
                "right-first selection prompts only the missing left point");
            selection.point(1, 0, 64, 0, "replace");
            SimpleAssert.eq(
                "worldGuideSelectionDone",
                HologramWorldGuide.selectionCursorKey(selection.snapshot()),
                "a complete pair reports selected without confirming or leaving selection");
            selection.beginNext("add");
            SimpleAssert.eq(
                "worldGuideSelectionMore",
                HologramWorldGuide.selectionCursorKey(selection.snapshot()),
                "one more-box gesture changes the cursor prompt immediately");
            selection.point(1, 4, 64, 4, "replace");
            SimpleAssert.eq(
                "worldGuideSelectionSecond",
                HologramWorldGuide.selectionCursorKey(selection.snapshot()),
                "the added box prompts only its missing right point");
            selection.undoRegion();
            SimpleAssert.eq(
                "worldGuideSelectionMore",
                HologramWorldGuide.selectionCursorKey(selection.snapshot()),
                "per-point undo returns the armed more-box prompt");
        } catch (java.io.IOException invalid) {
            throw new AssertionError("selection cursor hints", invalid);
        }
    }
}
