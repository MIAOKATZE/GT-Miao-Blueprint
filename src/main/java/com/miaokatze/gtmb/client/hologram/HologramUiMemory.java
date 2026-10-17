package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.client.hologram.HologramUiMemory.UiMemory;

/** Internal memory workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiMemory {

    private final HologramUiModules ui;

    HologramUiMemory(HologramUiModules ui) {
        this.ui = ui;
    }

    private static final java.util.Map<String, UiMemory> UI_MEMORY = new java.util.LinkedHashMap<>();

    private static HologramUiTabMemory tabMemory;

    private boolean initialEditHint = true;

    private boolean savedEditHint;

    private boolean structureVisited;

    private boolean basicConfigured;

    private boolean cellEditHint;

    private static final java.util.Map<String, boolean[]> GUIDANCE_MEMORY = new java.util.LinkedHashMap<>();

    static final class UiMemory {

        final boolean blueprints, libraryDrawer;
        final long revision;
        final int[] indices;
        final int offset;
        final String name;
        final HologramDraftNameMemory draftName;
        final long reviewNonce;
        final int[] positions;

        UiMemory(boolean blueprints, boolean libraryDrawer, long revision, int[] indices, int offset,
            NBTTagCompound source, String name, long reviewNonce, int[] positions) {
            this.blueprints = blueprints;
            this.libraryDrawer = libraryDrawer;
            this.revision = revision;
            this.indices = indices.clone();
            this.offset = offset;
            this.name = name;
            this.draftName = new HologramDraftNameMemory(source, name);
            this.reviewNonce = reviewNonce;
            this.positions = positions.clone();
        }
    }

    void rememberUi() {
        if (ui.session.state().session.isEmpty()) return;
        // An old screen closes after a new snapshot's screen is constructed; its tab must not undo the new intent.
        if (!ui.diagnostics.captureRenderSmokeAction()
            && (HologramClient.state == null || HologramClient.state.session.equals(ui.session.state().session)))
            HologramUiMemory.tabMemory.remember(ui.library.blueprintTab());
        HologramUiMemory.UI_MEMORY.put(
            ui.session.state().session,
            new UiMemory(
                ui.library.blueprintTab(),
                ui.library.libraryDrawer(),
                ui.session.revision(),
                ui.preview.selection()
                    .indices(),
                ui.library.libraryOffset(),
                ui.session.state().data,
                ui.library.presetNameField() == null ? ui.session.state().data.getString("virtualPresetName")
                    : ui.library.presetNameField()
                        .getText(),
                ui.review.seenPlacementReviewNonce(),
                ui.memory.selectedPositions()));
        while (HologramUiMemory.UI_MEMORY.size() > 32) HologramUiMemory.UI_MEMORY.remove(
            HologramUiMemory.UI_MEMORY.keySet()
                .iterator()
                .next());
    }

    static void rememberBlueprintPlacement(HologramState snapshot) {
        if (HologramUiMemory.tabMemory == null) HologramUiMemory.tabMemory = new HologramUiTabMemory(
            new java.io.File(
                net.minecraft.client.Minecraft.getMinecraft().mcDataDir,
                "config/gtmb-hologram-ui.properties"));
        HologramUiMemory.tabMemory.remember(true);
        UiMemory memory = HologramUiMemory.UI_MEMORY.get(snapshot.session);
        HologramSelection selected = new HologramSelection();
        selected.load(
            snapshot.data.getIntArray("selectedIndices"),
            snapshot.data.hasKey("selected") ? snapshot.data.getInteger("selected") : -1,
            snapshot.cells.size());
        int[] indices = selected.indices(), positions = new int[indices.length * 3];
        for (int i = 0; i < indices.length; i++) {
            HologramState.Cell cell = snapshot.cells.get(indices[i]);
            positions[i * 3] = cell.x;
            positions[i * 3 + 1] = cell.y;
            positions[i * 3 + 2] = cell.z;
        }
        HologramUiMemory.UI_MEMORY.put(
            snapshot.session,
            new UiMemory(
                true,
                false,
                memory == null ? snapshot.data.getLong("planRevision") : memory.revision,
                memory == null ? indices : memory.indices,
                memory == null ? 0 : memory.offset,
                snapshot.data,
                memory == null ? snapshot.data.getString("virtualPresetName") : memory.draftName.restore(snapshot.data),
                memory == null ? 0 : memory.reviewNonce,
                memory == null ? positions : memory.positions));
        while (HologramUiMemory.UI_MEMORY.size() > 32) HologramUiMemory.UI_MEMORY.remove(
            HologramUiMemory.UI_MEMORY.keySet()
                .iterator()
                .next());
    }

    int[] selectedPositions() {
        return ui.preview.selection()
            .positions(ui.session.state());
    }

    int[] rememberedSelection(UiMemory memory) {
        if (memory.revision == ui.session.revision()) return memory.indices;
        java.util.Map<String, Integer> coordinates = new java.util.HashMap<>();
        for (HologramState.Cell cell : ui.session.state().cells)
            coordinates.put(cell.x + "," + cell.y + "," + cell.z, cell.index);
        int[] indices = new int[memory.positions.length / 3];
        for (int i = 0; i < indices.length; i++) {
            Integer index = coordinates
                .get(memory.positions[i * 3] + "," + memory.positions[i * 3 + 1] + "," + memory.positions[i * 3 + 2]);
            if (index == null) return null;
            indices[i] = index;
        }
        return indices;
    }

    void rememberGuidance() {
        HologramUiMemory.GUIDANCE_MEMORY.put(
            ui.session.openedPurpose(),
            new boolean[] { this.initialEditHint, this.savedEditHint, this.structureVisited, this.basicConfigured });
        while (HologramUiMemory.GUIDANCE_MEMORY.size() > 64) HologramUiMemory.GUIDANCE_MEMORY.remove(
            HologramUiMemory.GUIDANCE_MEMORY.keySet()
                .iterator()
                .next());
    }

    int contextualAccent(int id) {
        if (id == 141 && ui.library.libraryDrawer()) return ui.library.selectedPresetExists() ? 0x80efaa : 0;
        if (id == 231 && ui.processing.processingFlow() != null
            && ui.processing.processingFlow()
                .optional())
            return 0x80efaa;
        int structureAccent = HologramGuidance.structureAccent(ui.session.state().data, id);
        if (structureAccent != 0) return structureAccent;
        if (id == 97 && ui.session.selectedCell() != null
            && ui.session.selectedCell().candidates.size() > 1
            && ui.config.hatchRoles()
                .size() > 1)
            return 0x68dded;
        if (ui.session.state().data.getBoolean("virtual") && !ui.session.state().data.getBoolean("virtualPlacement")) {
            if ((id == 149 || id == 130) && !ui.library.libraryDrawer() && !this.initialEditHint) return 0x80efaa;
            if (id == 148 && ui.library.libraryDrawer() && this.savedEditHint && !this.structureVisited)
                return 0x68dded;
            if (id == 220 && HologramGuidance.authorWorkspace(ui.session.state().data)
                && !ui.processing.processingReady()) return 0x80efaa;
            if ((id == 140 || id == 221) && ui.library.libraryDrawer()
                && ui.processing.processingReady()
                && ui.session.state().data.getBoolean("blueprintDirty")) return 0x80efaa;
            if ((id == 166 || id == 146) && ui.preview.selected() >= 0 && this.cellEditHint) return 0x68dded;
        }
        return 0;
    }

    boolean initialStructureSetupRequired() {
        // A preset is an optional shortcut. Construction review validates the current server plan.
        return false;
    }

    static java.util.Map<String, boolean[]> GUIDANCE_MEMORY() {
        return HologramUiMemory.GUIDANCE_MEMORY;
    }

    static java.util.Map<String, UiMemory> UI_MEMORY() {
        return HologramUiMemory.UI_MEMORY;
    }

    boolean basicConfigured(boolean value) {
        this.basicConfigured = value;
        return value;
    }

    boolean cellEditHint(boolean value) {
        this.cellEditHint = value;
        return value;
    }

    boolean initialEditHint() {
        return this.initialEditHint;
    }

    boolean initialEditHint(boolean value) {
        this.initialEditHint = value;
        return value;
    }

    boolean savedEditHint() {
        return this.savedEditHint;
    }

    boolean savedEditHint(boolean value) {
        this.savedEditHint = value;
        return value;
    }

    boolean structureVisited() {
        return this.structureVisited;
    }

    boolean structureVisited(boolean value) {
        this.structureVisited = value;
        return value;
    }

    static HologramUiTabMemory tabMemory() {
        return HologramUiMemory.tabMemory;
    }

    static HologramUiTabMemory tabMemory(HologramUiTabMemory value) {
        HologramUiMemory.tabMemory = value;
        return value;
    }

    void initializeTabStore() {
        if (tabMemory == null) tabMemory = new HologramUiTabMemory(
            new java.io.File(
                net.minecraft.client.Minecraft.getMinecraft().mcDataDir,
                "config/gtmb-hologram-ui.properties"));
    }

    UiMemory sessionMemory(String session) {
        return UI_MEMORY.get(session);
    }
}
