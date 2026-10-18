package com.miaokatze.gtmb.client.hologram;

import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import codechicken.nei.LayoutManager;

/** Staged inputs for a live tick driver. No handmade action DTOs, state updates or network interception. */
final class HologramAeActualInputProbe {

    private long lastMouseFrame = -1;

    boolean childControl(HologramAeContainerScreen screen, int id, long renderedFrame) {
        if (renderedFrame <= lastMouseFrame || !screen.smokeControlEnabled(id)) return false;
        int[] point = screen.smokeControlPoint(id);
        if (point == null) return false;
        mouse(screen, point, 0);
        lastMouseFrame = renderedFrame;
        return true;
    }

    boolean rootControl(HologramScreen screen, int id, long renderedFrame) {
        if (renderedFrame <= lastMouseFrame) return false;
        require(!intercepting(screen), "live root actions must not be intercepted");
        try {
            screen.smokeControlPoint(id);
        } catch (IllegalArgumentException notDrawnYet) {
            return false;
        }
        // The production mouse route handles the drawn control's enabled/modal gates.
        HologramMuiProbeInput.control(screen, id);
        lastMouseFrame = renderedFrame;
        return true;
    }

    EditWitness clear(HologramAeContainerScreen screen, String slotId, long renderedFrame) {
        if (renderedFrame <= lastMouseFrame || screen.smokePendingSequence() >= 0) return null;
        int[] point = screen.smokeSlotPoint(slotId);
        if (point == null) return null;
        HologramAePreviewModel before = screen.smokeModel();
        mouse(screen, point, 1);
        lastMouseFrame = renderedFrame;
        require(screen.smokePendingSequence() >= 1, "actual right-click CLEAR did not dispatch a request");
        return new EditWitness(before, slotId, screen.smokePendingSequence(), null);
    }

    EditWitness backpackDrop(HologramAeContainerScreen screen, String slotId, int column, long renderedFrame) {
        if (renderedFrame <= lastMouseFrame || screen.smokePendingSequence() >= 0) return null;
        int[] target = screen.smokeSlotPoint(slotId), source = screen.smokeBackpackPoint(column);
        if (target == null || source == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        int sourceIndex = screen.smokeBackpackPage() * 9 + column;
        require(
            sourceIndex >= 0 && sourceIndex < mc.thePlayer.inventory.mainInventory.length,
            "actual backpack sample index out of bounds");
        ItemStack sample = mc.thePlayer.inventory.mainInventory[sourceIndex];
        require(sample != null, "prepared real player sample slot is empty");
        HologramAePreviewModel before = screen.smokeModel();
        HologramMuiProbeInput.press(screen, source[0], source[1], 0);
        HologramMuiProbeInput.release(screen, target[0], target[1], 0);
        lastMouseFrame = renderedFrame;
        require(screen.smokePendingSequence() >= 1, "actual backpack drag did not dispatch a request");
        return new EditWitness(before, slotId, screen.smokePendingSequence(), sample);
    }

    EditWitness neiDrop(HologramAeContainerScreen screen, String slotId, Predicate<ItemStack> acceptsSample,
        boolean bookmark, long renderedFrame) {
        if (renderedFrame <= lastMouseFrame || screen.smokePendingSequence() >= 0) return null;
        int[] target = screen.smokeSlotPoint(slotId);
        if (target == null) return null;
        ItemStack sample = null;
        int sx = -1, sy = -1;
        // Read actual populated public panels. Do not call NEI input, cheat, give or deletion handlers.
        for (int y = 0; y < screen.height && sample == null; y += 2) for (int x = 0; x < screen.width; x += 2) {
            ItemStack candidate = bookmark ? LayoutManager.bookmarkPanel.getStackMouseOver(x, y)
                : LayoutManager.itemPanel.getStackMouseOver(x, y);
            if (candidate != null && acceptsSample.test(candidate.copy())) {
                sample = candidate.copy();
                sx = x;
                sy = y;
                break;
            }
        }
        if (sample == null) return null; // Caller retains its stage, applies bounded timeout and real search/page
                                         // inputs.
        HologramAePreviewModel before = screen.smokeModel();
        HologramMuiProbeInput.press(screen, sx, sy, 0);
        HologramMuiProbeInput.release(screen, target[0], target[1], 0);
        lastMouseFrame = renderedFrame;
        require(screen.smokePendingSequence() >= 1, "actual public NEI sample drag did not dispatch a request");
        return new EditWitness(before, slotId, screen.smokePendingSequence(), sample);
    }

    void exitWithKey(HologramAeContainerScreen screen, char character, int key) {
        HologramMuiProbeInput.key(screen, character, key);
    }

    static final class EditWitness {

        final String session, token, structureId, slotId;
        final long revision, sequence;
        final ItemStack requestedSample, originalMark;

        EditWitness(HologramAePreviewModel before, String slotId, long sequence, ItemStack sample) {
            session = before.session;
            token = before.token;
            structureId = before.structureId;
            revision = before.revision;
            this.sequence = sequence;
            this.slotId = slotId;
            requestedSample = sample == null ? null : sample.copy();
            originalMark = mark(before, slotId);
        }

        boolean assertAcknowledged(HologramScreen parent, HologramAeContainerScreen child, boolean accepted,
            ItemStack expectedCanonical, String expectedRejectCode) {
            NBTTagCompound snapshot = parent.smokeSnapshot();
            NBTTagCompound ack = snapshot.getCompoundTag("aeEditAck");
            if (ack.getLong("editSeq") != sequence || child.smokePendingSequence() >= 0) return false;
            require(ack.getBoolean("accepted") == accepted, "actual edit acceptance differs from expectation");
            HologramAePreviewModel after = child.smokeModel();
            require(
                session.equals(after.session) && token.equals(after.token) && structureId.equals(after.structureId),
                "actual canonical ACK changed the preview binding");
            require(
                after.revision == revision + (accepted ? 1 : 0) && ack.getLong("newRevision") == after.revision,
                "actual edit revision is not exact");
            if (!accepted) require(expectedRejectCode.equals(ack.getString("code")), "wrong server rejection reason");
            require(
                equal(mark(after, slotId), accepted ? expectedCanonical : originalMark),
                "canonical marker differs or rejected edit changed the draft");
            return true;
        }
    }

    static final class SaveReviewWitness {

        final String transaction, session;
        final long revision;
        final boolean canConfirm;
        final int issues, blockers;

        SaveReviewWitness(HologramScreen screen) {
            NBTTagCompound ui = screen.smokeAeUiState();
            require(
                ui.getBoolean("modal") && ui.getBoolean("saveReview") && ui.getBoolean("valid"),
                "actual server review modal required");
            transaction = ui.getString("transactionId");
            session = ui.getString("session");
            revision = ui.getLong("planRevision");
            canConfirm = ui.getBoolean("canConfirm");
            issues = ui.getInteger("issueCount");
            blockers = ui.getInteger("blockerCount");
            require(!transaction.isEmpty() && !session.isEmpty() && revision > 0, "invalid live save binding");
            require(!canConfirm || blockers == 0, "live review permits a blocker");
        }

        void assertSameReview(HologramScreen screen) {
            NBTTagCompound ui = screen.smokeAeUiState();
            require(
                transaction.equals(ui.getString("transactionId")) && session.equals(ui.getString("session"))
                    && revision == ui.getLong("planRevision"),
                "inspection/navigation changed the save binding");
        }

        void assertReplacementReview(HologramScreen screen) {
            SaveReviewWitness replacement = new SaveReviewWitness(screen);
            require(!transaction.equals(replacement.transaction), "edited draft reused its old save nonce");
            require(
                session.equals(replacement.session) && replacement.revision > revision,
                "edited draft did not produce a newer bound save review");
        }

        NBTTagCompound visibleResolution(HologramScreen screen, String resolution) {
            NBTTagList rows = screen.smokeAeUiState()
                .getTagList("visibleRows", 10);
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound row = rows.getCompoundTagAt(i);
                if (resolution.equals(row.getString("resolution"))) return (NBTTagCompound) row.copy();
            }
            return null;
        }
    }

    private static ItemStack mark(HologramAePreviewModel model, String slotId) {
        for (HologramAePreviewModel.MarkSlot slot : model.slots) if (slotId.equals(slot.id)) return slot.stack();
        // A successful tombstone CLEAR may remove the readonly descriptor completely.
        if (slotId.startsWith("review/")) return null;
        throw new IllegalStateException("server-issued slot missing: " + slotId);
    }

    private static boolean equal(ItemStack a, ItemStack b) {
        return a == null ? b == null
            : b != null && a.writeToNBT(new NBTTagCompound())
                .equals(b.writeToNBT(new NBTTagCompound()));
    }

    private static boolean intercepting(HologramScreen screen) {
        try {

            return ((Boolean) HologramUiDiagnostics.readDiagnosticField(screen, "captureRenderSmokeAction"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot verify real dispatch mode", failure);
        }
    }

    private static void mouse(HologramAeContainerScreen screen, int[] point, int button) {
        HologramMuiProbeInput.press(screen, point[0], point[1], button);
        HologramMuiProbeInput.release(screen, point[0], point[1], button);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
