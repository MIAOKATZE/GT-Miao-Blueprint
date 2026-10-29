package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.client.hologram.HologramAuthorLibraryLifecycleRegression;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Client predicates consume real server snapshot factories, not hand-written lookalike DTOs. */
final class HologramAuthorSaveSnapshotRegression {

    private HologramAuthorSaveSnapshotRegression() {}

    static void run(HologramSession original) {
        ItemStack tool = HologramSessionPolicy.currentTool(original);
        NBTTagCompound previous = (NBTTagCompound) tool.getTagCompound()
            .copy();
        try {
            for (boolean integrated : new boolean[] { false, true }) {
                HologramSession source = new HologramSession(
                    original.player,
                    original.x,
                    original.y,
                    original.z,
                    false,
                    original.side);
                source.virtual = true;
                source.virtualPreset = new HologramPresets.Preset();
                source.virtualPreset.schema = 2;
                source.virtualPreset.id = integrated ? "integrated-source" : "private-source";
                source.virtualPreset.name = "formal Save";
                source.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
                source.libraryLocalId = integrated ? "" : "local_source";
                source.blueprintClientTaskToken = 719;
                source.uiSequence = 31;
                source.openPurpose = "blueprintEdit";
                source.open = true;
                source.blueprintSource = "saved";
                tool.getTagCompound()
                    .setString(HologramSessionKeys.SELECTED_PRESET, source.virtualPreset.id);
                NBTTagCompound request = new NBTTagCompound();
                request.setString("action", "edit");
                request.setBoolean("saveCurrent", true);
                request.setLong("clientTaskToken", 719);
                HologramLibraryWorkflow.bindAuthorSaveHandoff(source, request);
                SimpleAssert.eq(719L, source.authorSaveHandoffTask, "only explicit task binds formal save routing");
                SimpleAssert.that(
                    !HologramSnapshots.localAuthorSourceReady(source),
                    "formal submission cannot be replaced by a source-only capability without receipts");
                NBTTagCompound activated = HologramSnapshots.basicState(source);
                HologramSnapshots.writeBlueprintScalars(source, activated);
                source.uiSequence++;
                source.planRevision++;
                source.processingPresetOperation = "processBlueprint";
                source.processingPresetOperationSuccess = true;
                NBTTagCompound processed = HologramSnapshots.basicState(source);
                HologramSnapshots.writeBlueprintScalars(source, processed);
                HologramAuthorLibraryLifecycleRegression
                    .serverSnapshot(activated, processed, "local_source", integrated ? "integrated-source" : "", 719);
                NBTTagCompound cancel = new NBTTagCompound();
                cancel.setLong("clientTaskToken", 720);
                SimpleAssert.that(
                    !HologramLibraryWorkflow.cancelAuthorSaveHandoff(source, cancel),
                    "unrelated task cannot cancel unpublished formal-save activation");
                cancel.setLong("clientTaskToken", 719);
                SimpleAssert.that(
                    HologramLibraryWorkflow.cancelAuthorSaveHandoff(source, cancel),
                    "own task cancellation before activation publication needs no unknown session id");
                SimpleAssert.eq(0L, source.authorSaveHandoffTask, "early cancellation restores source handoff routing");
                request.setString("action", "preview");
                HologramLibraryWorkflow.bindAuthorSaveHandoff(source, request);
                SimpleAssert
                    .eq(0L, source.authorSaveHandoffTask, "preview and placement never inherit formal Save routing");
                request.setString("action", "edit");
                request.removeTag("saveCurrent");
                HologramLibraryWorkflow.bindAuthorSaveHandoff(source, request);
                SimpleAssert
                    .eq(0L, source.authorSaveHandoffTask, "legacy edit request retains original source handoff");
            }
        } finally {
            tool.setTagCompound(previous);
        }
    }
}
