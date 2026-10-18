package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/** Drives the optional real placement identity modal before observing or clicking the material review. */
final class HologramPlacementReviewClientSmoke {

    interface WorldReceipt {

        NBTTagCompound read() throws Exception;
    }

    private final File receipts;
    private final String label;
    private final HologramProcessingClientSmoke.MouseClick mouse;
    private final WorldReceipt worldReceipt;
    private HologramScreen clickedScreen, readyScreen;
    private long clickedFrame = -1, readyFrame = -1, readyRevision, readyNonce, readySequence, readyStarted;
    private String readySession = "", session, proof;
    private long sequence, revision, nonce, started;
    private int phase, level, observedChoices;
    private int[] anchor;
    private NBTTagCompound beforeWorld;

    HologramPlacementReviewClientSmoke(File receipts, String label, HologramProcessingClientSmoke.MouseClick mouse,
        WorldReceipt worldReceipt) {
        this.receipts = receipts;
        this.label = label;
        this.mouse = mouse;
        this.worldReceipt = worldReceipt;
    }

    boolean tick(HologramScreen screen) throws Exception {
        NBTTagCompound state = screen.smokeSnapshot();
        boolean placementReview = state.getBoolean("virtualPlacement") && state.getBoolean("virtualLocked")
            && state.getBoolean("blueprintAwaitingConfirmation");
        if (!placementReview) {
            require(phase == 0, "identity input or ACK unexpectedly revoked the active locked review");
            return true;
        }
        boolean drawer = (Boolean) HologramUiDiagnostics.readDiagnosticField(screen, "deidentificationDrawer");
        if (phase != 0 && System.nanoTime() - started > 10000000000L)
            throw new IllegalStateException("10 second actual placement identity input/ACK wait: " + label);
        if (phase != 0) {
            require(session.equals(state.getString("session")), "identity choice changed the source session");
            require(Arrays.equals(anchor, anchor(state)), "identity choice moved the locked placement");
        }
        if (screen.smokePending() || clickedScreen == screen && screen.smokeRenderGeneration() <= clickedFrame)
            return false;
        if (phase == 0 && drawer) {
            if (!screen.smokeHasControl(213)) return false;
            NBTTagCompound world = worldReceipt.read();
            if (world == null) return false;
            NBTTagCompound server = world.getCompoundTag("blueprintSession");
            if (!state.getString("session")
                .equals(server.getString("id")) || server.getLong("planRevision") < state.getLong("planRevision")
                || server.getLong("placementReviewNonce") < state.getLong("placementReviewNonce")) return false;
            require(
                (Boolean) HologramUiDiagnostics.readDiagnosticField(screen, "deidentificationPlacement"),
                "the visible identity modal does not belong to this placement");
            session = state.getString("session");
            revision = state.getLong("planRevision");
            nonce = state.getLong("placementReviewNonce");
            anchor = anchor(state);
            level = HologramDeidentificationMemory.minimum(state);
            proof = label + "-placement-identity-" + ++observedChoices;
            started = System.nanoTime();
            beforeWorld = (NBTTagCompound) world.copy();
            observe("modal-state", state);
            observe("modal-ui", screen.smokeLibraryUiState());
            observe("modal-world", world);
            // Preserve the raw payload at level zero when allowed; never select below the server floor.
            if (click(screen, 22000 + level)) phase = 1;
            return false;
        }
        if (phase == 1) {
            require(
                drawer && revision == state.getLong("planRevision") && nonce == state.getLong("placementReviewNonce"),
                "placement changed before applying the visible identity choice");
            require(
                (Integer) HologramUiDiagnostics.readDiagnosticField(screen, "deidentificationLevel") == level,
                "the actual level click did not select the server-permitted level");
            if (click(screen, 213)) {
                require(screen.smokePending(), "applying the actual placement choice did not dispatch its request");
                sequence = (Long) HologramUiDiagnostics.readDiagnosticField(screen, "sequence");
                observe("submitted-state", state);
                NBTTagCompound submitted = screen.smokeLibraryUiState();
                submitted.setLong("actualRequestSequence", sequence);
                observe("submitted-ui", submitted);
                phase = 2;
            }
            return false;
        }
        if (phase == 2) {
            require(state.getLong("uiSequence") >= sequence, "identity ACK precedes the actual request");
            require(
                state.getLong("planRevision") >= revision && state.getLong("placementReviewNonce") >= nonce,
                "identity ACK lost the locked plan context");
            require(
                state.getBoolean("placementDeidentificationChosen")
                    && state.getInteger("placementDeidentificationLevel") == level
                    && !drawer,
                "server did not acknowledge the explicit placement identity choice: " + state.getString("status"));
            NBTTagCompound world = worldReceipt.read();
            if (world == null) return false;
            NBTTagCompound server = world.getCompoundTag("blueprintSession");
            if (!session.equals(server.getString("id")) || server.getLong("uiSequence") < sequence
                || server.getLong("planRevision") < state.getLong("planRevision")
                || server.getLong("placementReviewNonce") < state.getLong("placementReviewNonce")) return false;
            require(
                server.getBoolean("placementReviewIssued") && server.getBoolean("virtualPlacement")
                    && server.getBoolean("virtualLocked")
                    && server.getBoolean("blueprintAwaitingConfirmation")
                    && server.getInteger("job") == 0
                    && !server.getBoolean("forcePlacementReady"),
                "independent server receipt lost the unpaid locked placement review");
            require(
                Arrays.equals(anchor, world.getIntArray("blueprintAnchor")),
                "independent server identity ACK moved the locked anchor");
            NBTTagCompound previous = beforeWorld.getCompoundTag("blueprintSession");
            for (String key : new String[] { "actor", "presetId", "libraryLocalId" }) require(
                previous.getString(key)
                    .equals(server.getString(key)),
                "identity ACK changed the source " + key);
            for (String key : new String[] {
                "placed", "stock", "blueprintPlaced", "blueprintStock", "blueprintSourceIntact", "aePlaceEvents" })
                if (beforeWorld.hasKey(key)) require(
                    beforeWorld.getTag(key)
                        .equals(world.getTag(key)),
                    "identity choice changed paid world, materials or source: " + key);
            observe("ack-state", state);
            observe("ack-ui", screen.smokeLibraryUiState());
            observe("ack-world", world);
            phase = 0;
        }
        // A state ACK can arrive before the GUI's next real draw. Observe a later frame of this exact review.
        if (readyScreen != screen || !readySession.equals(state.getString("session"))
            || readyRevision != state.getLong("planRevision")
            || readyNonce != state.getLong("placementReviewNonce")
            || readySequence != state.getLong("uiSequence")) {
            readyScreen = screen;
            readySession = state.getString("session");
            readyRevision = state.getLong("planRevision");
            readyNonce = state.getLong("placementReviewNonce");
            readySequence = state.getLong("uiSequence");
            readyFrame = screen.smokeRenderGeneration();
            readyStarted = System.nanoTime();
            return false;
        }
        boolean ready = screen.smokeRenderGeneration() > readyFrame && screen.smokeHasControl(55)
            && screen.smokeHasControl(168)
            && screen.ui.review.materialDialogOpen();
        if (!ready && System.nanoTime() - readyStarted > 10000000000L)
            throw new IllegalStateException("10 second actual material review draw wait: " + label);
        return ready;
    }

    private boolean click(HologramScreen screen, int id) throws Exception {
        if (!mouse.click(screen, id)) return false;
        clickedScreen = screen;
        clickedFrame = screen.smokeRenderGeneration();
        return true;
    }

    private void observe(String suffix, NBTTagCompound value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(receipts, proof + "-" + suffix + ".nbt"))) {
            CompressedStreamTools.writeCompressed(value, output);
        }
    }

    private static int[] anchor(NBTTagCompound state) {
        return new int[] { state.getInteger("x"), state.getInteger("y"), state.getInteger("z") };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Actual placement identity UI: " + message);
    }
}
