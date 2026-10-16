package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/**
 * Input priority shared by the world controller and regression tests.
 * CUT mode survives local cancellation; the server-owned queue is independent of every local stage.
 * IDLE -> SELECTING (first CUT choice / Shift-Alt-right) -> IDLE (saved cut).
 * IDLE -> MOVING (Shift-right with a stored cut) -> LOCKED (right) -> confirmation/submission (right).
 * Escape returns any local activity to IDLE; only a confirmed server paste/undo consumes a stored cut.
 */
final class HologramCutInput {

    enum Stage {
        IDLE,
        SELECTING,
        MOVING,
        LOCKED
    }

    enum Intent {
        NONE,
        SWITCHER,
        STORAGE,
        SELECT,
        PREVIEW,
        UNDO,
        MARK,
        LOCK,
        CONFIRM
    }

    private HologramCutInput() {}

    /** Keep active blueprint gestures authoritative; completed/cancelled job records are idle history. */
    static boolean blueprintBusy(NBTTagCompound snapshot) {
        HologramWorldFlow.Stage stage = HologramWorldFlow.stage(
            snapshot.getBoolean("areaSelecting") || snapshot.getBoolean("areaImporting"),
            snapshot.getBoolean("virtual"),
            snapshot.getBoolean("virtualPlacement"),
            snapshot.getBoolean("virtualLocked"),
            snapshot.getInteger("job"));
        return snapshot.getBoolean("capturePending") || stage != HologramWorldFlow.Stage.WORKSPACE;
    }

    /** Mode selection is local and remains reachable while a cut write awaits its authoritative result. */
    static Intent right(Stage stage, boolean shift, boolean alt, boolean blueprintBusy, boolean cutMode,
        boolean control) {
        if (control && shift && !alt && !blueprintBusy) return Intent.SWITCHER;
        if (stage == Stage.SELECTING) return Intent.MARK;
        if (stage == Stage.MOVING) return Intent.LOCK;
        if (stage == Stage.LOCKED) return Intent.CONFIRM;
        if (blueprintBusy) return Intent.NONE;
        if (shift && alt) return Intent.SELECT;
        if (cutMode) {
            if (alt) return Intent.UNDO;
            return shift ? Intent.PREVIEW : Intent.STORAGE;
        }
        return shift ? Intent.SWITCHER : Intent.NONE;
    }

    static boolean interceptsUse(Stage stage, boolean shift, boolean blueprintBusy, boolean cutMode) {
        return stage != Stage.IDLE || !blueprintBusy && (cutMode || shift);
    }

    static boolean cancelsActivity(Stage stage, boolean session, boolean preview) {
        return stage != Stage.IDLE || session || preview;
    }

    static boolean requiresCutReady(Intent intent) {
        return intent == Intent.SELECT || intent == Intent.PREVIEW
            || intent == Intent.UNDO
            || intent == Intent.MARK
            || intent == Intent.CONFIRM;
    }

    static final class Switcher {

        private boolean cut, finished;

        Switcher() {}

        Switcher(boolean cut) {
            this.cut = cut;
        }

        boolean cut() {
            return cut;
        }

        void cycle() {
            if (!finished) cut = !cut;
        }

        boolean release(boolean shift) {
            if (shift || finished) return false;
            finished = true;
            return true;
        }

        void cancel() {
            finished = true;
        }
    }
}
