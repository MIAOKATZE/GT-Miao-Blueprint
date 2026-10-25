package com.miaokatze.gtmb.client.hologram;

/** Local editor chrome IDs; never sent as server commands or used as snapshot cell indices. */
final class HologramEditorControlIds {

    // Each local namespace reserves 1,000 controls. Legacy network group/slot IDs remain 60000/61000.
    static final int SHELL = 100000;
    static final int TOOLS = 101000;
    static final int GUIDE = 102000;
    static final int TUTORIAL = 103000;
    static final int LIBRARY = 104000;
    static final int BLUEPRINT_ROTATE = 105000;

    private HologramEditorControlIds() {}
}
