package com.miaokatze.gtmb.client.hologram;

final class HologramUiModules {

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
        shortcuts = new HologramUiShortcuts(this);
    }
}
