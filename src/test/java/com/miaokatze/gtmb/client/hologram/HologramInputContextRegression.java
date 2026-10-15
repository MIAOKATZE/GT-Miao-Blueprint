package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramInputContextRegression {

    private HologramInputContextRegression() {}

    public static void run() {
        HologramWorldTargetRegression.run();
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
}
