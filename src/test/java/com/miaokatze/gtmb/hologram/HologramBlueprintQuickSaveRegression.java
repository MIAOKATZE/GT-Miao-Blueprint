package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real author rotations enter the save gate with a saved policy and an unchanged native payload source. */
final class HologramBlueprintQuickSaveRegression {

    private HologramBlueprintQuickSaveRegression() {}

    static void run() throws Exception {
        Field cache = HologramPresets.class.getDeclaredField("cached");
        cache.setAccessible(true);
        Object previous = cache.get(null);
        try {
            HologramSession valid = savedFixture(cache);
            turn(valid);
            SimpleAssert.eq(
                5,
                (int) HologramBlueprintNbt.decode(valid.capture.cells.get(0).tileNbt)
                    .getShort("mFacing"),
                "the approved GT schema adapter changes the retained native facing bytes");
            HologramPresets.Preset disk = HologramLibraryFacade.get(valid.player, valid.virtualPreset.id);
            HologramPresets.Preset replay = HologramBlueprintRotation
                .rotatedBaseline(disk, HologramBlueprintRotation.approvedTurns(valid, disk));
            HologramPresets.Preset current = HologramBlueprintProjection.blueprint(valid, "Current rotated save");
            SimpleAssert.eq(
                replay.cells.get(0).block,
                current.cells.get(0).block,
                "the admitted saved source and current projection retain the same explicit material block identity");
            HologramAuthoringWorkflow.validateProcessedSaveOptions(valid, save());
            SimpleAssert
                .that(valid.processingReviewed, "Ctrl+S rechecks an approved native NBT turn with its saved policy");
            SimpleAssert
                .eq(1L, valid.geometryEdits.fullValidations, "rotated quick save retains full geometry admission");
            assertSavedPreviewPose(valid);

            HologramSession overwrite = savedFixture(cache);
            turn(overwrite);
            HologramAuthoringWorkflow.validateProcessedSaveOptions(overwrite, replace());
            SimpleAssert.that(
                overwrite.processingReviewed,
                "explicit overwrite without saveCurrent rechecks the same trusted rotated source");
            assertSavedPreviewPose(overwrite);

            HologramSession changed = savedFixture(cache);
            turn(changed);
            NBTTagCompound manual = HologramBlueprintNbt.decode(changed.capture.cells.get(0).tileNbt);
            manual.setInteger("manualEdit", 7);
            changed.capture.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(manual);
            expectReview(changed, "a manual NBT edit cannot borrow rotation authority");
            expectReview(changed, replace(), "explicit overwrite cannot borrow approval for changed native NBT");

            HologramSession copied = savedFixture(cache);
            turn(copied);
            NBTTagCompound duplicate = new NBTTagCompound();
            duplicate.setInteger("selected", 0);
            duplicate.setBoolean("copy", true);
            duplicate.setInteger("dx", 4);
            duplicate.setLong("planRevision", copied.planRevision);
            HologramBlueprintEditor.moveBlueprintCells(copied, duplicate);
            HologramAuthoringWorkflow.blueprintEdited(copied, "geometry");
            expectReview(copied, "a copied native identity cannot borrow its source's one rotation approval");
            expectReview(copied, replace(), "explicit overwrite retains the saved source's native payload count limit");

            HologramSession changedOptions = savedFixture(cache);
            turn(changedOptions);
            NBTTagCompound options = replace();
            options.setBoolean("retainCovers", false);
            try {
                HologramAuthoringWorkflow.validateProcessedSaveOptions(changedOptions, options);
                throw new AssertionError("explicit overwrite must retain reviewed save option checks");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("保存选项"),
                    "policy replay still checks the actual requested options against the reviewed result");
            }
        } finally {
            cache.set(null, previous);
        }
    }

    /** Follow the save's compact JSON into the production PNG decoder, after the policy replay gate. */
    private static void assertSavedPreviewPose(HologramSession s) throws Exception {
        HologramPresets.Preset exported = HologramBlueprintProjection.blueprint(s, "Rotated preview");
        HologramBlueprintVersions.stampSaved(exported);
        byte[] json = HologramDraftEncoding.encode(exported, (done, total) -> {});
        Method decode = com.miaokatze.gtmb.client.hologram.HologramBlueprintPreview.class
            .getDeclaredMethod("snapshot", byte[].class);
        decode.setAccessible(true);
        NBTTagCompound preview = (NBTTagCompound) decode.invoke(null, (Object) json);
        NBTTagCompound offset = preview.getTagList("cells", 10)
            .getCompoundTagAt(1);
        SimpleAssert
            .eq(0, offset.getInteger("dx"), "PNG input retains the rotated asymmetric X offset after quick save");
        SimpleAssert
            .eq(1, offset.getInteger("dz"), "PNG input retains the rotated asymmetric Z offset after quick save");
        NBTTagCompound controller = preview.getTagList("cells", 10)
            .getCompoundTagAt(0);
        SimpleAssert.eq(
            5,
            (int) controller.getCompoundTag("previewTile")
                .getShort("mFacing"),
            "PNG input retains the native facing from the newly exported save");
    }

    private static HologramSession savedFixture(Field cache) throws Exception {
        Method factory = HologramGeometryEditRegression.class.getDeclaredMethod("fixture", int.class);
        factory.setAccessible(true);
        HologramSession s = (HologramSession) factory.invoke(null, 2);
        NBTTagCompound nativeData = new NBTTagCompound();
        nativeData.setString("id", "BaseMetaTileEntity");
        nativeData.setShort("mFacing", (short) 2);
        s.capture.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(nativeData);
        HologramPresets.Preset raw = HologramBlueprintProjection.blueprint(s, "Native rotation quick save");
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
        policy.autoHints = "none";
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset saved = HologramBlueprintProcessing.process(raw, policy, 0, 0, 64, 0);
        saved = HologramAePolicy.sanitize(saved).preset;
        HologramBlueprintProcessing.enforcePolicy(saved);
        saved.id = "native-turn-quick-save";
        HologramAuthoringWorkflow.validateAuthorDraft(saved);
        SimpleAssert.eq(
            "minecraft:stone",
            saved.cells.get(0).block,
            "the fixture enters the library through the real projection and complete save admission");
        cache.set(null, Collections.singletonList(HologramPresets.copy(saved)));
        // The stone carrier keeps this fixture independent of a GT machine registry, using the real named NBT adapter.
        s.virtualPreset = HologramPresets.get(saved.id);
        s.processingSource = HologramPresets.copy(s.virtualPreset);
        s.processingPolicy = HologramBlueprintProcessing.policy(s.virtualPreset);
        s.processingReviewed = false;
        s.retainedNbtKeys = s.virtualPreset.retainedNbtKeys;
        HologramBlueprintProjection.populateVirtual(s);
        s.blueprintSource = "saved";
        s.open = true;
        s.openPurpose = "blueprintEdit";
        NBTTagCompound opened = HologramSnapshots.basicState(s);
        SimpleAssert
            .eq("blueprint", opened.getString("editorWorkspace"), "the actual snapshot publishes the author workspace");
        SimpleAssert.eq("", s.openPurpose, "opening the real snapshot consumes its transient blueprint edit intent");
        SimpleAssert.that(!s.open, "a current save must also survive consumption of the one-shot UI open flag");
        return s;
    }

    private static void turn(HologramSession s) throws IOException {
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", s.planRevision);
        HologramBlueprintRotation.rotate(s, request);
    }

    private static NBTTagCompound save() {
        NBTTagCompound request = new NBTTagCompound();
        request.setBoolean("saveCurrent", true);
        return request;
    }

    private static NBTTagCompound replace() {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("op", "saveBlueprintReplace");
        request.setString("presetId", "explicit-overwrite-target");
        request.setBoolean("confirmOverwrite", true);
        return request;
    }

    private static void expectReview(HologramSession s, String reason) throws IOException {
        expectReview(s, save(), reason);
    }

    private static void expectReview(HologramSession s, NBTTagCompound request, String reason) throws IOException {
        try {
            HologramAuthoringWorkflow.validateProcessedSaveOptions(s, request);
            throw new AssertionError(reason);
        } catch (IOException expected) {
            SimpleAssert.that(!s.processingReviewed, reason);
        }
    }
}
