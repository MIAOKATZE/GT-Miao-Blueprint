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
            HologramAuthoringWorkflow.validateProcessedSaveOptions(valid, save());
            SimpleAssert
                .that(valid.processingReviewed, "Ctrl+S rechecks an approved native NBT turn with its saved policy");
            SimpleAssert
                .eq(1L, valid.geometryEdits.fullValidations, "rotated quick save retains full geometry admission");

            HologramSession changed = savedFixture(cache);
            turn(changed);
            NBTTagCompound manual = HologramBlueprintNbt.decode(changed.capture.cells.get(0).tileNbt);
            manual.setInteger("manualEdit", 7);
            changed.capture.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(manual);
            expectReview(changed, "a manual NBT edit cannot borrow rotation authority");

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
        } finally {
            cache.set(null, previous);
        }
    }

    private static HologramSession savedFixture(Field cache) throws Exception {
        Method factory = HologramGeometryEditRegression.class.getDeclaredMethod("fixture", int.class);
        factory.setAccessible(true);
        HologramSession s = (HologramSession) factory.invoke(null, 2);
        NBTTagCompound nativeData = new NBTTagCompound();
        nativeData.setString("id", "BaseMetaTileEntity");
        nativeData.setShort("mFacing", (short) 2);
        s.virtualPreset.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(nativeData);
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
        policy.autoHints = "none";
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset saved = HologramBlueprintProcessing.process(s.virtualPreset, policy, 0, 0, 64, 0);
        saved.id = "native-turn-quick-save";
        cache.set(null, Collections.singletonList(HologramPresets.copy(saved)));
        // The stone carrier keeps this fixture independent of a GT machine registry, using the real named NBT adapter.
        s.capture.cells.get(0).tileNbt = saved.cells.get(0).tileNbt;
        s.virtualPreset = saved;
        s.processingSource = HologramPresets.copy(saved);
        s.processingPolicy = HologramBlueprintProcessing.policy(saved);
        s.processingReviewed = true;
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

    private static void expectReview(HologramSession s, String reason) throws IOException {
        try {
            HologramAuthoringWorkflow.validateProcessedSaveOptions(s, save());
            throw new AssertionError(reason);
        } catch (IOException expected) {
            SimpleAssert.that(!s.processingReviewed, reason);
        }
    }
}
