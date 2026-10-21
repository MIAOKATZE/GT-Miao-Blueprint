package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.PanelManager;
import com.cleanroommc.modularui.screen.SecondaryPanel;
import com.cleanroommc.modularui.widgets.Dialog;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Input intent and real MUI2 handler/cache contracts; no Minecraft screen, font or GL initialization. */
public final class HologramUiLifecycleRegression {

    private HologramUiLifecycleRegression() {}

    public static void run() {
        deferredIntent();
        try {
            libraryKeepsTheFirstHandler();
            freshDialogClosures();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect MUI2 2.3.92 local dialog state", failure);
        }
    }

    private static void deferredIntent() {
        HologramUiDeferredAction queue = new HologramUiDeferredAction();
        List<String> calls = new ArrayList<>();
        queue.offer(() -> calls.add("confirm"));
        queue.offer(() -> calls.add("double click"));
        SimpleAssert.that(calls.isEmpty(), "input dispatch does not run confirmation inline");
        queue.run(true);
        queue.run(true);
        SimpleAssert.eq(Arrays.asList("confirm"), calls, "one confirmation is consumed once after input returns");
        queue.offer(() -> calls.add("replaced screen"));
        queue.run(false);
        queue.run(true);
        queue.offer(() -> calls.add("NEI suspended screen"));
        queue.clear();
        queue.run(true);
        SimpleAssert.eq(Arrays.asList("confirm"), calls, "replacement and suspension cannot replay stale placement");
        queue.offer(() -> { throw new IllegalStateException("conversion failed"); });
        try {
            queue.run(true);
            throw new AssertionError("conversion failure was silently swallowed");
        } catch (IllegalStateException expected) {
            SimpleAssert.eq("conversion failed", expected.getMessage(), "the owner can report the actual failure");
        }
        queue.offer(() -> calls.add("retry"));
        queue.run(true);
        SimpleAssert.eq(Arrays.asList("confirm", "retry"), calls, "failure retires its intent and permits a retry");
    }

    @SuppressWarnings("unchecked")
    private static void libraryKeepsTheFirstHandler() throws ReflectiveOperationException {
        ModularPanel parent = new ModularPanel("regression_parent");
        SecondaryPanel first = new SecondaryPanel(parent, (p, player) -> new Dialog<>("same_name"), true);
        SecondaryPanel replacement = new SecondaryPanel(parent, (p, player) -> new Dialog<>("same_name"), true);
        field(SecondaryPanel.class, "open").setBoolean(first, true);
        PanelManager manager = new PanelManager(null, parent);
        Map<String, IPanelHandler> handlers = (Map<String, IPanelHandler>) field(PanelManager.class, "panelHandlerMap")
            .get(manager);
        // Isolate the name-collision branch from unrelated screen layout/GL initialization.
        handlers.put("same_name", first);
        manager.openPanel(new Dialog<>("same_name"), replacement);
        SimpleAssert.that(handlers.get("same_name") == first, "real MUI manager keeps the first named handler");
        SimpleAssert
            .that(!replacement.isPanelOpen(), "a newly supplied handler cannot replace the registered provider");
    }

    private static void freshDialogClosures() throws ReflectiveOperationException {
        ModularPanel parent = new ModularPanel("regression_parent");
        HologramMuiScreen.LocalDialog dialog = new HologramMuiScreen.LocalDialog(parent);
        SecondaryPanel handler = (SecondaryPanel) field(HologramMuiScreen.LocalDialog.class, "handler").get(dialog);
        SecondaryPanel.IPanelBuilder provider = (SecondaryPanel.IPanelBuilder) field(SecondaryPanel.class, "provider")
            .get(handler);
        Field cached = field(SecondaryPanel.class, "panel");
        List<String> chosen = new ArrayList<>();
        String[] identities = { "preset-A", "preset-B", "newly-created-preset" };
        for (String identity : identities) {
            cached.set(handler, new Dialog<>("cached_old_draft"));
            SimpleAssert.that(
                dialog.prepare((p, player) -> new Dialog<String>("same_name", ignored -> chosen.add(identity))),
                "a fully closed local dialog can be rebuilt");
            SimpleAssert.that(cached.get(handler) == null, "actual SecondaryPanel cache is invalidated before reuse");
            @SuppressWarnings("unchecked")
            Dialog<String> fresh = (Dialog<String>) provider.build(parent, null);
            // The newly supplied provider must be used through the same real SecondaryPanel.
            fresh.closeWith("confirmed");
        }
        SimpleAssert.eq(
            Arrays.asList(identities),
            chosen,
            "reopen uses current preset/draft identity, never the first closure");
        SimpleAssert.eq(
            1,
            ((List<?>) field(ModularPanel.class, "clientSubPanels").get(parent)).size(),
            "repeated dialogs register only one handler on their parent");
        field(SecondaryPanel.class, "open").setBoolean(handler, true);
        SimpleAssert.that(
            !dialog.prepare((p, player) -> { throw new AssertionError("active modal replaced"); }),
            "a still open or animating dialog retains its own provider");
        handler.closePanelInternal();
        SimpleAssert.that(
            dialog.prepare((p, player) -> new Dialog<>("after_close")),
            "real close notification allows the next dialog request");
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
