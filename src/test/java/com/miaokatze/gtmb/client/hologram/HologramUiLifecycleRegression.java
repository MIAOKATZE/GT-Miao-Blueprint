package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

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
            isolatedGraphsKeepSeparateHandlers();
            boundedWorkspaceGraphs();
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
        queue.offer(() -> calls.add("player navigation"));
        SimpleAssert.that(
            !queue.tryOffer(() -> calls.add("lost animation")),
            "playback learns that a player's navigation already owns the pending tick");
        queue.run(true);
        SimpleAssert.that(
            queue.tryOffer(() -> calls.add("animation retry")),
            "a rejected animation can retry after player navigation completes");
        queue.run(true);
        SimpleAssert.eq(
            Arrays.asList("confirm", "retry", "player navigation", "animation retry"),
            calls,
            "rejected playback neither replaces player input nor falsely claims to be queued");
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
        first.deleteCachedPanel();
        SimpleAssert
            .that(handlers.get("same_name") == first, "deleting the panel cache does not unregister its named handler");
        SimpleAssert
            .that(!replacement.isPanelOpen(), "a newly supplied handler cannot replace the registered provider");
    }

    @SuppressWarnings("unchecked")
    private static void isolatedGraphsKeepSeparateHandlers() throws ReflectiveOperationException {
        HologramUiModules live = new HologramUiModules(null);
        HologramUiModules otherLive = new HologramUiModules(null);
        HologramUiModules firstLesson = new HologramUiModules(null);
        HologramUiModules replay = new HologramUiModules(null);
        HologramState sample = new HologramState(new NBTTagCompound());
        firstLesson.session.initializeIsolated(sample, (operation, request) -> {});
        replay.session.initializeIsolated(sample, (operation, request) -> {});
        String[] bases = { "gtmb_tutorial_scene", "gtmb_tutorial_overlay", "gtmb_tutorial_materials",
            "gtmb_editor_configuration_channels", "gtmb_native_library_discard", "gtmb_editor_context_tools",
            "gtmb_editor_reference", "gtmb_editor_pinned_annotation", "gtmb_editor_advanced_processing" };
        for (String base : bases) {
            SimpleAssert.that(
                !live.panelName(base)
                    .equals(otherLive.panelName(base)),
                "two retained live workspace graphs never share a named panel handler");
            SimpleAssert.eq(
                live.panelName(base),
                live.panelName(base),
                "one live workspace graph keeps its stable panel identity");
            SimpleAssert.that(
                !live.panelName(base)
                    .equals(firstLesson.panelName(base)),
                "lesson never reuses a live panel name");
            SimpleAssert.that(
                !firstLesson.panelName(base)
                    .equals(replay.panelName(base)),
                "a replay owns a new panel graph");
            SimpleAssert.eq(
                firstLesson.panelName(base),
                firstLesson.panelName(base),
                "one graph reuses its cached panel identity");
        }

        ModularPanel parent = new ModularPanel("graph_regression_parent");
        PanelManager manager = new PanelManager(null, parent);
        Map<String, IPanelHandler> handlers = (Map<String, IPanelHandler>) field(PanelManager.class, "panelHandlerMap")
            .get(manager);
        List<String> closures = new ArrayList<>();
        HologramUiModules[] graphs = { live, otherLive, firstLesson, replay };
        for (int i = 0; i < graphs.length; i++) {
            String graph = "graph-" + i;
            String name = graphs[i].panelName("gtmb_editor_configuration_channels");
            SecondaryPanel registered = new SecondaryPanel(
                parent,
                (p, player) -> new Dialog<String>(name, ignored -> closures.add(graph)),
                true);
            field(SecondaryPanel.class, "open").setBoolean(registered, true);
            handlers.put(name, registered);
            registered.deleteCachedPanel();
            SecondaryPanel replacement = new SecondaryPanel(parent, (p, player) -> new Dialog<>(name), true);
            // Exercise the actual first-handler branch without initializing an unrelated Minecraft window.
            manager.openPanel(new Dialog<>(name), replacement);
            SimpleAssert.that(
                handlers.get(name) == registered && !replacement.isPanelOpen(),
                "MUI resolves only this graph's original provider after a cache deletion");
            SecondaryPanel.IPanelBuilder provider = (SecondaryPanel.IPanelBuilder) field(
                SecondaryPanel.class,
                "provider").get(handlers.get(name));
            ((Dialog<String>) provider.build(parent, null)).closeWith("confirmed");
        }
        SimpleAssert.eq(
            4,
            handlers.size(),
            "both live workspace graphs, first lesson and replay coexist in the real named-handler map");
        SimpleAssert.eq(
            Arrays.asList("graph-0", "graph-1", "graph-2", "graph-3"),
            closures,
            "each cached provider keeps its own graph callback instead of reopening live or previous lesson fields");
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

    private static void boundedWorkspaceGraphs() throws ReflectiveOperationException {
        HologramEditorWorkspaces.Graphs graphs = new HologramEditorWorkspaces.Graphs();
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<HologramUiModules> factory = () -> {
            created.incrementAndGet();
            return new HologramUiModules(null);
        };
        HologramState projectionState = workspaceState("controller-owner", "projection", 1);
        HologramUiModules projection = graphs.retain(projectionState, factory);
        HologramUiModules blueprint = graphs.retain(workspaceState("row-0", "blueprint", 2), factory);
        field(HologramUiSession.class, "state").set(blueprint.session, workspaceState("row-0", "blueprint", 2));
        field(HologramUiPreview.class, "yaw").setDouble(projection.preview, .75);
        field(HologramUiPreview.class, "yaw").setDouble(blueprint.preview, 1.25);
        field(HologramUiLibrary.class, "libraryOffset").setInt(blueprint.library, 317);
        Object nativeTree = blueprint.shell;
        for (int row = 1; row <= 1000; row++) {
            HologramState incoming = workspaceState("library-row-" + row, "blueprint", row + 2);
            SimpleAssert.that(
                graphs.retain(incoming, factory) == blueprint,
                "new library session/epoch keeps its domain graph and native tree");
        }
        SimpleAssert.eq(2, created.get(), "a thousand library documents allocate only two domain graphs");
        SimpleAssert.eq(
            2,
            graphs.values()
                .size(),
            "retained graphs have a fixed two-domain bound");
        SimpleAssert.that(blueprint.shell == nativeTree, "library navigation keeps the mounted native widget tree");
        SimpleAssert.eq(
            317,
            field(HologramUiLibrary.class, "libraryOffset").getInt(blueprint.library),
            "retaining a blueprint graph does not reset its library viewport");
        SimpleAssert.eq(
            1.25,
            field(HologramUiPreview.class, "yaw").getDouble(blueprint.preview),
            "retaining a blueprint graph does not reset its camera");
        field(HologramEditorWorkspaces.class, "cut").setBoolean(blueprint.workspaces, true);
        SimpleAssert.that(
            graphs.retain(workspaceState("author-member", "blueprint", 1003), factory) == blueprint,
            "Cut presentation never becomes a third underlying editing domain");
        SimpleAssert.that(
            graphs.retain(projectionState, factory) == projection,
            "returning from library navigation restores the original controller graph");
        blueprint.workspaces.domainActivated();
        SimpleAssert.that(
            !blueprint.workspaces.isCut(),
            "Cut to Projection to Blueprint activates the requested blueprint page instead of stale Cut presentation");
        SimpleAssert.eq(
            .75,
            field(HologramUiPreview.class, "yaw").getDouble(projection.preview),
            "the controller camera remains independent from blueprint navigation");
        graphs.clear();
        SimpleAssert.that(
            graphs.values()
                .isEmpty(),
            "final screen disposal releases all retained graph references");
    }

    private static HologramState workspaceState(String session, String domain, long epoch) {
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("session", session);
        snapshot.setString("editorWorkspace", domain);
        snapshot.setLong("editorWorkspaceEpoch", epoch);
        return new HologramState(snapshot);
    }
}
