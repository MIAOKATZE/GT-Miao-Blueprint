package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** Presentation intent is separate from both library navigation and the server's geometry flags. */
final class HologramEditorWorkspaces {

    enum Mode {
        PROJECTION,
        BLUEPRINT,
        CUT
    }

    /** Domain ownership outlives the server nonce used by an individual library document. */
    static final class Graphs {

        private final java.util.EnumMap<Mode, HologramUiModules> graphs = new java.util.EnumMap<>(Mode.class);

        HologramUiModules retain(HologramState snapshot, java.util.function.Supplier<HologramUiModules> create) {
            return graphs.computeIfAbsent(domain(snapshot), ignored -> create.get());
        }

        java.util.Collection<HologramUiModules> values() {
            return graphs.values();
        }

        void clear() {
            graphs.clear();
        }
    }

    private final HologramUiModules ui;
    private boolean cut;
    private boolean switching;

    HologramEditorWorkspaces(HologramUiModules ui) {
        this.ui = ui;
    }

    static Mode domain(HologramState state) {
        String workspace = state.data.getString("editorWorkspace");
        if ("projection".equals(workspace)) return Mode.PROJECTION;
        if ("blueprint".equals(workspace)) return Mode.BLUEPRINT;
        // Legacy snapshots have no domain identity. A real controller admits projection;
        // a virtual placement remains blueprint even while it renders in the real world.
        return !state.data.getBoolean("virtual") && state.data.getBoolean("target") ? Mode.PROJECTION : Mode.BLUEPRINT;
    }

    Mode mode() {
        return cut ? Mode.CUT : domain(ui.session.state());
    }

    boolean projection() {
        return mode() == Mode.PROJECTION;
    }

    boolean emptyProjection() {
        return projection() && !ui.session.state().data.getBoolean("target");
    }

    boolean hasProjectionContent() {
        return projection() && !emptyProjection();
    }

    boolean blueprint() {
        return mode() == Mode.BLUEPRINT;
    }

    boolean isCut() {
        return mode() == Mode.CUT;
    }

    boolean switching() {
        return switching;
    }

    void activateProjection() {
        activate(Mode.PROJECTION);
    }

    void activateBlueprint() {
        activate(Mode.BLUEPRINT);
    }

    void activateCut() {
        tryActivateCut();
    }

    boolean tryActivateCut() {
        NBTTagCompound state = ui.session.state().data;
        int job = state.getInteger("job");
        if (switching || HologramBackgroundTasks.busy()
            || HologramCutClient.INSTANCE.workspaceSwitchBusy()
            || ui.session.pendingSequence() >= 0
            || ui.session.materialReviewPending()
            || job == 1
            || job == 2
            || state.getBoolean("areaImporting")
            || state.getBoolean("areaSelecting")
            || state.getBoolean("capturePending")
            || state.getBoolean("attachmentPending")
            || state.getBoolean("localPlanPending")
            || state.getBoolean("placementValidating")
            || state.getBoolean("virtualPlacement")
            || state.getBoolean("blueprintSavePending")
            || state.getBoolean("discardConfirmationRequired")
            || ui.processing.processingFlow() != null
            || ui.ae.awaitingAePreview()
            || ui.ae.aeReview() != null && ui.ae.aeReview().review
            || ui.tutorial.active()) {
            ui.session.notice("请先完成当前操作，再浏览剪切工作区；草稿和选区保留");
            return false;
        }
        cut = true;
        ui.tools.closeTransient();
        return true;
    }

    private void activate(Mode requested) {
        if (requested == Mode.BLUEPRINT && !ui.session.isTutorial()) {
            if (!ui.session.isLocalAuthor()) HologramLocalAuthorRuntime.openWorkspace();
            cut = false;
            return;
        }
        if (requested == Mode.PROJECTION && ui.session.isLocalAuthor()) {
            ui.host.parent()
                .showServerProjection();
            return;
        }
        if (domain(ui.session.state()) == requested) {
            if (cut && HologramCutClient.INSTANCE.workspaceSwitchBusy()) {
                ui.session.notice("请先等待剪切操作完成，再返回工作区");
                return;
            }
            cut = false;
            return;
        }
        if (!canSwitch()) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("editorWorkspace", requested == Mode.PROJECTION ? "projection" : "blueprint");
        request.setLong("planRevision", ui.session.revision());
        ui.memory.rememberUi();
        long sequence = ui.session.sequence();
        ui.session.send("switchEditorWorkspace", request, true);
        switching = ui.session.sequence() > sequence;
    }

    private boolean canSwitch() {
        if (switching || !ui.session.editable()
            || ui.session.pendingSequence() >= 0
            || ui.session.state().data.getBoolean("virtualPlacement")
            || ui.processing.processingFlow() != null
            || ui.tutorial.active()) {
            ui.session.notice("请先完成当前操作，再切换工作区；草稿和选区保留");
            return false;
        }
        return true;
    }

    void acknowledged() {
        switching = false;
    }

    /** A cross-domain ACK activates the requested editor, even when that graph last displayed Cut. */
    void domainActivated() {
        cut = false;
        switching = false;
    }
}
