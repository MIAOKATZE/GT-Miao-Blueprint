package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/**
 * Input priority shared by the world controller and regression tests.
 * The server-owned queue is independent of the selected tool and every local stage.
 * Choosing CUT enters SELECTING; a saved cut starts a fresh empty selection until Escape.
 * Shift-right opens storage from every cut stage; an explicit row action starts MOVING -> LOCKED -> paste.
 * Escape exits CUT entirely; only a confirmed server paste/undo consumes a stored cut.
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
        if (shift && alt && !blueprintBusy) return Intent.SELECT;
        // Alt is also held while adjusting selection distance; corner 2 must keep priority over undo.
        if (stage != Stage.SELECTING && cutMode && alt && !shift && !blueprintBusy) return Intent.UNDO;
        if (cutMode && shift && !blueprintBusy) return Intent.STORAGE;
        if (stage == Stage.SELECTING) return Intent.MARK;
        if (stage == Stage.MOVING) return Intent.LOCK;
        if (stage == Stage.LOCKED) return Intent.CONFIRM;
        if (blueprintBusy) return Intent.NONE;
        if (cutMode) {
            if (alt) return Intent.UNDO;
            return Intent.STORAGE;
        }
        return shift ? Intent.SWITCHER : Intent.NONE;
    }

    static boolean interceptsUse(Stage stage, boolean shift, boolean blueprintBusy, boolean cutMode) {
        return stage != Stage.IDLE || !blueprintBusy && (cutMode || shift);
    }

    static boolean cancelsActivity(Stage stage, boolean session, boolean preview) {
        return stage != Stage.IDLE || session || preview;
    }

    static boolean exitsMode(Stage stage, boolean cutMode, boolean session, boolean preview) {
        return cutMode || cancelsActivity(stage, session, preview);
    }

    static boolean requiresCutReady(Intent intent) {
        return intent == Intent.SELECT || intent == Intent.PREVIEW
            || intent == Intent.UNDO
            || intent == Intent.MARK
            || intent == Intent.CONFIRM;
    }

    static boolean allowsWorldAction(Intent intent, boolean backgroundBusy) {
        return !backgroundBusy || intent == Intent.NONE || intent == Intent.STORAGE || intent == Intent.SWITCHER;
    }

    static HologramWorldFlow.Nudge nudgeDirection(int index, boolean alt) {
        if (index < 0 || index > 5) throw new IllegalArgumentException("cut nudge key");
        if (index == 0) return alt ? HologramWorldFlow.Nudge.UP : HologramWorldFlow.Nudge.FORWARD;
        if (index == 1) return alt ? HologramWorldFlow.Nudge.DOWN : HologramWorldFlow.Nudge.BACKWARD;
        if (index == 2) return HologramWorldFlow.Nudge.LEFT;
        if (index == 3) return HologramWorldFlow.Nudge.RIGHT;
        return index == 4 ? HologramWorldFlow.Nudge.UP : HologramWorldFlow.Nudge.DOWN;
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
