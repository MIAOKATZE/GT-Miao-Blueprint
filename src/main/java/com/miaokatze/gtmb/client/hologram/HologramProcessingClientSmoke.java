package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileOutputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Real-input continuation shared by the opt-in socket fixtures; never supplies a policy or an ACK itself. */
final class HologramProcessingClientSmoke {

    interface MouseClick {

        boolean click(HologramScreen screen, int id) throws Exception;
    }

    private final File receipts;
    private final String label;
    private final MouseClick mouse;
    private String session;
    private long revision, sequence = -1, clickedFrame = -1;
    private HologramScreen clickedScreen;
    private int phase, step;
    private boolean complete, fullReviewSelected;

    HologramProcessingClientSmoke(File receipts, String label, MouseClick mouse) {
        this.receipts = receipts;
        this.label = label;
        this.mouse = mouse;
    }

    boolean tick(HologramScreen screen) throws Exception {
        if (complete) return true;
        NBTTagCompound state = screen.smokeSnapshot();
        if (session == null) {
            session = state.getString("session");
            revision = state.getLong("planRevision");
            observe("source", state);
        }
        require(session.equals(state.getString("session")), "source session changed during processing");
        if (screen.smokePending() || clickedScreen == screen && screen.smokeRenderGeneration() <= clickedFrame)
            return false;
        if (phase == 4) {
            require(state.getLong("uiSequence") >= sequence, "processing ACK sequence precedes the actual request");
            require(state.getLong("planRevision") >= revision, "processing ACK lost the source revision");
            require(
                "processBlueprint".equals(state.getString("processingPresetOperation"))
                    && state.getBoolean("processingPresetOperationSuccess"),
                "actual processBlueprint ACK rejected: " + state.getString("status"));
            require(
                state.getBoolean("blueprintProcessingComplete"),
                "server rejected processing: " + state.getString("status"));
            NBTTagList confirmed = state.getCompoundTag("processingPolicy")
                .getTagList("confirmedStages", 8);
            require(confirmed.tagCount() == 6, "server did not acknowledge all six explicit Next confirmations");
            observe("ack", state);
            observe("ack-ui", screen.smokeLibraryUiState());
            complete = true;
            System.out
                .println("[GTMB-HOLOGRAM-PROCESSING-CLIENT] PASS " + label + " six actual choices/Next + server ACK");
            return true;
        }
        if (phase == 0
            && (!state.getBoolean("blueprintProcessingRequired") || state.getBoolean("blueprintProcessingComplete"))) {
            observe("ready", state);
            complete = true;
            return true;
        }
        NBTTagCompound observed = screen.smokeLibraryUiState();
        if (phase == 0) {
            if (!observed.getBoolean("library")) {
                click(screen, 149);
                return false;
            }
            if (click(screen, 220)) phase = 1;
        } else if (phase == 1) {
            require(observed.getBoolean("processingFlow"), "processing control did not open its actual modal");
            if (!fullReviewSelected) {
                // The visible full-review toggle resets a resumed policy to stage zero. Select it through input.
                boolean wasFullReview = observed.getBoolean("flowFullReview");
                if (click(screen, 296) && !wasFullReview) fullReviewSelected = true;
                return false;
            }
            require(observed.getBoolean("flowFullReview"), "actual six-step review toggle was not retained");
            require(observed.getInteger("flowStage") == 0, "new source unexpectedly resumed another processing stage");
            if (click(screen, 232)) phase = 2;
        } else if (phase == 2) {
            require(
                observed.getBoolean("processingFlow") && observed.getInteger("flowStage") == step,
                "processing stage advanced without its actual Next click");
            int minimum = Math.max(
                0,
                Math.min(
                    10,
                    state.getCompoundTag("processing")
                        .getInteger("minimumLevel")));
            int[] choices = { 235, 240 + minimum, 262, 270, 280, 292 };
            if (click(screen, choices[step])) phase = 3;
        } else if (phase == 3) {
            require(
                observed.getBoolean("flowChosen") && observed.getInteger("flowStage") == step,
                "the actual stage choice did not enable Next");
            if (click(screen, 231)) {
                if (step == 5) {
                    require(screen.smokePending(), "final actual Next did not dispatch processing");
                    sequence = (Long) HologramUiDiagnostics.readDiagnosticField(screen, "sequence");
                    phase = 4;
                    observe("submitted-ui", screen.smokeLibraryUiState());
                } else {
                    step++;
                    phase = 2;
                }
            }
        }
        return false;
    }

    private boolean click(HologramScreen screen, int id) throws Exception {
        if (!mouse.click(screen, id)) return false;
        clickedScreen = screen;
        clickedFrame = screen.smokeRenderGeneration();
        return true;
    }

    private void observe(String phase, NBTTagCompound state) throws Exception {
        try (FileOutputStream output = new FileOutputStream(
            new File(receipts, label + "-processing-" + phase + ".nbt"))) {
            CompressedStreamTools.writeCompressed(state, output);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Actual processing UI: " + message);
    }
}
