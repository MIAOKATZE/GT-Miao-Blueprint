package com.miaokatze.gtmb.client.hologram;

import java.util.concurrent.atomic.AtomicLong;

final class HologramUiModules {

    private static final AtomicLong NEXT_PANEL_GRAPH = new AtomicLong();
    private final String panelGraphSuffix = "__tutorial_graph_" + NEXT_PANEL_GRAPH.incrementAndGet();

    final HologramUiHost host;
    final HologramUiAeReview ae;
    final HologramUiAnnotations annotations;
    final HologramUiNetworkBindings bindings;
    final HologramUiCanvas canvas;
    final HologramUiConfiguration config;
    final HologramUiDiagnostics diagnostics;
    final HologramUiDeidentification identity;
    final HologramUiLibrary library;
    final HologramUiMemory memory;
    final HologramUiNbtEditor nbt;
    final HologramUiPosition position;
    final HologramUiPreview preview;
    final HologramUiProcessing processing;
    final HologramUiBuildReview review;
    final HologramUiRouter router;
    final HologramUiSession session;
    final HologramUiShortcuts shortcuts;
    final HologramUiTextBoxes textBoxes;
    final HologramEditorTools tools;
    final HologramEditorGuide guide;
    final HologramEditorTutorial tutorial;
    final HologramEditorShell shell;
    final HologramEditorWorkspaces workspaces;

    /** MUI retains the first handler for a panel name until the entire screen is disposed. */
    String panelName(String base) {
        if (base == null || base.isEmpty()) throw new IllegalArgumentException("Panel names require a base identity");
        return base + panelGraphSuffix;
    }

    HologramUiModules(HologramUiHost host) {
        this.host = host;
        ae = new HologramUiAeReview(this);
        annotations = new HologramUiAnnotations(this);
        bindings = new HologramUiNetworkBindings(this);
        canvas = new HologramUiCanvas(this);
        config = new HologramUiConfiguration(this);
        diagnostics = new HologramUiDiagnostics(this);
        identity = new HologramUiDeidentification(this);
        library = new HologramUiLibrary(this);
        memory = new HologramUiMemory(this);
        nbt = new HologramUiNbtEditor(this);
        position = new HologramUiPosition(this);
        preview = new HologramUiPreview(this);
        processing = new HologramUiProcessing(this);
        review = new HologramUiBuildReview(this);
        router = new HologramUiRouter(this);
        session = new HologramUiSession(this);
        workspaces = new HologramEditorWorkspaces(this);
        shortcuts = new HologramUiShortcuts(this);
        textBoxes = new HologramUiTextBoxes(this);
        tools = new HologramEditorTools(this);
        guide = new HologramEditorGuide(this);
        tutorial = new HologramEditorTutorial(this);
        shell = new HologramEditorShell(this);
    }
}
