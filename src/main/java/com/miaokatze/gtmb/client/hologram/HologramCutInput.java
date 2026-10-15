package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/** Input priority shared by the world controller and regression tests. */
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

    static Intent right(Stage stage, boolean queued, boolean shift, boolean alt, boolean blueprintBusy) {
        if (stage == Stage.SELECTING) return Intent.MARK;
        if (stage == Stage.MOVING) return Intent.LOCK;
        if (stage == Stage.LOCKED) return Intent.CONFIRM;
        if (blueprintBusy) return Intent.NONE;
        if (queued) {
            if (shift && alt) return Intent.SELECT;
            if (alt) return Intent.UNDO;
            return shift ? Intent.PREVIEW : Intent.STORAGE;
        }
        return shift ? Intent.SWITCHER : Intent.NONE;
    }

    static final class Switcher {

        private boolean cut, finished;

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
