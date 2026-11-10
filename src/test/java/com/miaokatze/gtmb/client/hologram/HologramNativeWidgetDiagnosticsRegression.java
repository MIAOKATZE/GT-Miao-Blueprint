package com.miaokatze.gtmb.client.hologram;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real MUI parent lifecycle and pure matrix inspection; no window or GL draw is fabricated. */
public final class HologramNativeWidgetDiagnosticsRegression {

    private HologramNativeWidgetDiagnosticsRegression() {}

    public static void run() {
        java.lang.reflect.Field names = null;
        Object previous = null;
        try {
            names = cpw.mods.fml.common.Loader.class.getDeclaredField("namedMods");
            names.setAccessible(true);
            previous = names.get(cpw.mods.fml.common.Loader.instance());
            if (previous == null) names.set(cpw.mods.fml.common.Loader.instance(), new java.util.HashMap<>());
            runNativeTree();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot configure isolated optional-mod lookup for the real MUI matrix", failure);
        } finally {
            if (names != null) try {
                names.set(cpw.mods.fml.common.Loader.instance(), previous);
            } catch (IllegalAccessException failure) {
                throw new AssertionError("Cannot restore optional-mod lookup", failure);
            }
        }
    }

    private static void runNativeTree() {
        ModularPanel panel = new ModularPanel("diagnostic_parent_contract").size(100, 100);
        ParentWidget<?> parent = new ParentWidget<>().size(80, 80);
        Widget<?> child = new Widget<>().size(20, 20);
        parent.child(child);
        panel.child(parent);
        ModularScreen screen = new ModularScreen("gtmb", panel);
        screen.getContext()
            .setSettings(new com.cleanroommc.modularui.screen.UISettings());
        new com.cleanroommc.modularui.screen.GuiScreenWrapper(screen);
        screen.onResize(100, 100);
        SimpleAssert.that(
            panel.isValid() && panel.isOpen() && panel.getParent() == null,
            "real MUI open panel is a valid parentless root");
        SimpleAssert.that(
            child.isValid() && child.getParent() == parent && parent.getParent() == panel,
            "real MUI initialization attaches the actual child tree");
        AtomicInteger boundsCalls = new AtomicInteger();
        java.util.function.Function<com.cleanroommc.modularui.api.widget.IWidget, int[]> bounds = widget -> {
            boundsCalls.incrementAndGet();
            return new int[] { 0, 0, widget.getArea().width, widget.getArea().height };
        };
        Map<String, Object> root = HologramTestLabUiProfile.describeWidget(panel, screen, bounds);
        SimpleAssert.eq(
            "attached",
            root.get("attachment"),
            "enabled native panel root is inspected without walking beyond null: " + root);
        SimpleAssert.that(
            Boolean.TRUE.equals(root.get("ancestorsEnabled")) && root.containsKey("screenBounds"),
            "real parentless root retains its safe transform evidence");
        Map<String, Object> leaf = HologramTestLabUiProfile.describeWidget(child, screen, bounds);
        SimpleAssert
            .eq("attached", leaf.get("attachment"), "real attached native child retains strict owner validation");
        int beforeWrongOwner = boundsCalls.get();
        ModularScreen other = new ModularScreen("gtmb", new ModularPanel("other_owner"));
        SimpleAssert.eq(
            "unattached",
            HologramTestLabUiProfile.describeWidget(child, other, bounds)
                .get("attachment"),
            "real native child is never transformed under another screen owner");
        SimpleAssert.eq(beforeWrongOwner, boundsCalls.get(), "wrong owner cannot reach global bounds inspection");
        Map<String, Object> failing = HologramTestLabUiProfile.describeWidget(
            panel,
            screen,
            widget -> { throw new IllegalStateException("one-node-diagnostic-failure"); });
        SimpleAssert.that(
            String.valueOf(failing.get("diagnosticErrorStackTrace"))
                .contains("one-node-diagnostic-failure"),
            "one diagnostic node records its full exception without aborting inspection");
        SimpleAssert.eq(
            "attached",
            HologramTestLabUiProfile.describeWidget(child, screen, bounds)
                .get("attachment"),
            "a failed node does not discard another valid native widget");
        panel.setEnabled(false);
        Map<String, Object> disabled = HologramTestLabUiProfile.describeWidget(child, screen, bounds);
        SimpleAssert.that(
            Boolean.FALSE.equals(disabled.get("ancestorsEnabled")),
            "native root enabled state participates in child diagnostics");
        panel.setEnabled(true);
        int beforeDetached = boundsCalls.get();
        Map<String, Object> neverAttached = HologramTestLabUiProfile.describeWidget(new Widget<>(), screen, bounds);
        child.dispose();
        Map<String, Object> disposed = HologramTestLabUiProfile.describeWidget(child, screen, bounds);
        SimpleAssert
            .eq("unattached", neverAttached.get("attachment"), "never initialized native widget is marked unattached");
        SimpleAssert.eq("unattached", disposed.get("attachment"), "disposed native child is marked unattached");
        SimpleAssert
            .eq(beforeDetached, boundsCalls.get(), "unattached widgets never reach global bounds or matrix inspection");
        String trace = HologramTestLabUiProfile
            .failureStack(new IllegalStateException("outer-contract", new java.io.IOException("inner-contract")));
        SimpleAssert.that(
            trace.contains("outer-contract") && trace.contains("Caused by: java.io.IOException: inner-contract")
                && trace.contains("HologramNativeWidgetDiagnosticsRegression.run"),
            "diagnostic stack retains call site and cause chain");
    }
}
