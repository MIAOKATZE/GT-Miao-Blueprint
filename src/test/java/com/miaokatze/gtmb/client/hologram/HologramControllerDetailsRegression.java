// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.common.blocks.ItemMachines;

public final class HologramControllerDetailsRegression {

    private HologramControllerDetailsRegression() {}

    public static void run() {
        NBTTagCompound target = new NBTTagCompound();
        target.setString("session", "controller");
        target.setString("generation", "read-1");
        target.setBoolean("localStandardController", true);
        target.setBoolean("capturePending", true);
        var block = new HologramControllerTarget.Cell(
            16,
            64,
            0,
            0,
            0,
            0,
            "minecraft:air",
            "minecraft:stone",
            0,
            0,
            Blocks.air,
            Blocks.stone,
            null,
            new ItemStack(Blocks.stone),
            null,
            null,
            false,
            false,
            false,
            "missing",
            "block",
            "",
            "block",
            "",
            -1,
            -1,
            null,
            null,
            -1,
            true,
            HologramControllerTarget.TargetKind.MATERIAL);
        wholeNativeConstructionWhileDetailsPending(block);
        var targetPreparation = new HologramState.Preparation(
            new HologramControllerTarget.Projection(target, java.util.Collections.singletonList(block)));
        SimpleAssert.that(targetPreparation.step(4, Long.MAX_VALUE), "typed target prepares a complete spatial index");
        HologramState early = targetPreparation.result();
        SimpleAssert.that(
            early.cells.get(0)
                .block(false) == Blocks.stone && early.cells.get(0)
                    .tag()
                    .equals(block.tag())
                && early.cells.get(0)
                    .localObservationMissing(),
            "serialization preserves the original typed target");

        NBTTagCompound details = new NBTTagCompound();
        details.setString("session", "controller");
        details.setString("generation", "read-1");
        details.setBoolean("capturePending", false);
        details.setBoolean("projectionDataComplete", true);
        NBTTagCompound options = new NBTTagCompound();
        options.setString("role", "hatch");
        options.setBoolean("candidateComplete", true);
        options.setTag("candidates", list(new ItemStack(Blocks.dirt).writeToNBT(new NBTTagCompound())));
        options.setInteger("chosenChoice", 0);
        details.setTag("cells", list(options));
        HologramState.Cell requested = new HologramState.Cell(early.cells.get(0), options);
        independentSelectedRow(early, options);
        SimpleAssert.that(
            requested.chosenChoice == 0 && requested.candidateComplete
                && requested.tag()
                    .getTagList("candidates", 10)
                    .tagCount() == 1,
            "only requested candidate row is resolved");
        SimpleAssert.that(
            early.cells.get(0).candidates.isEmpty() && "block".equals(early.cells.get(0).role)
                && early.regions.at(16, 64, 0) == early.cells.get(0),
            "requesting an option does not rewrite target geometry or its spatial index");

        NBTTagCompound update = new NBTTagCompound();
        update.setString("status", "Ready");
        update.setTag("capabilities", new NBTTagList());
        HologramState earlyMetadata = early.withMetadata(update);
        SimpleAssert.that(
            earlyMetadata.reusesControllerGeometry(early),
            "capability-only publication reuses render preparation");
        update = new NBTTagCompound();
        update.setBoolean("nativeBuildSatisfied", true);
        HologramState receipt = earlyMetadata.withMetadata(update);
        SimpleAssert.that(receipt.reusesControllerGeometry(earlyMetadata), "receipt metadata keeps the mesh");
        update = new NBTTagCompound();
        update.setInteger("mode", 2);
        SimpleAssert.that(
            !earlyMetadata.withMetadata(update)
                .reusesControllerGeometry(earlyMetadata),
            "a material-view mode change still invalidates the presentation");
        update = new NBTTagCompound();
        update.setString("generation", "read-2");
        SimpleAssert.that(
            !earlyMetadata.withMetadata(update)
                .reusesControllerGeometry(earlyMetadata),
            "a new capture generation is never allowed to reuse the old frame");

        options.setString("role", "interface");
        SimpleAssert.that(
            "interface".equals(new HologramState.Cell(early.cells.get(0), options).role)
                && "block".equals(early.cells.get(0).role),
            "an option may describe a role without changing the world preview");
    }

    private static void wholeNativeConstructionWhileDetailsPending(HologramControllerTarget.Cell cell) {
        NBTTagCompound target = new NBTTagCompound();
        target.setString("session", "native-construction");
        target.setString("generation", "target-1");
        target.setBoolean("localStandardController", true);
        target.setBoolean("capturePending", true);
        target.setBoolean("target", true);
        target.setBoolean("supported", true);
        target.setInteger("siteOccupied", 0);
        target.setInteger("siteUnknown", 0);
        target.setInteger("x", cell.x() - 1);
        target.setInteger("y", cell.y());
        target.setInteger("z", cell.z());
        var counts = new com.miaokatze.gtmb.hologram.HologramControllerMaterialCounts.Builder();
        counts.add(cell, cell.x() - 1, cell.y(), cell.z());
        var preparation = new HologramState.Preparation(
            new HologramControllerTarget.Projection(target, java.util.Collections.singletonList(cell), counts.seal()));
        SimpleAssert.that(preparation.step(32, Long.MAX_VALUE), "native target and ledger are published");
        HologramState ready = preparation.result();
        NBTTagCompound settings = new NBTTagCompound();
        settings.setInteger("mode", 0);
        settings.setInteger("scope", 0);
        SimpleAssert.that(
            HologramPlacementConfirmation.localStructureReviewBlocker(ready, settings, false)
                .isEmpty(),
            "ordinary native construction need not wait for candidate details");
        SimpleAssert.that(
            !HologramPlacementConfirmation.localStructureReviewBlocker(ready)
                .isEmpty()
                && !HologramPlacementConfirmation.localStructureReviewBlocker(ready, settings, true)
                    .isEmpty(),
            "generic and manually changed targets still require candidate details");
        SimpleAssert.that(
            !HologramPlacementConfirmation.localStructureReviewBlocker(new HologramState(target), settings, false)
                .isEmpty(),
            "a controller shell without a sealed target is not buildable");
        NBTTagCompound changed = new NBTTagCompound();
        changed.setInteger("siteUnknown", 1);
        SimpleAssert.that(
            !HologramPlacementConfirmation.localStructureReviewBlocker(ready.withMetadata(changed), settings, false)
                .isEmpty(),
            "unread terrain cannot use early native admission");
        NBTTagCompound selected = (NBTTagCompound) settings.copy();
        selected.setInteger("scope", 2);
        selected.setIntArray("selectedIndices", new int[] { 0 });
        SimpleAssert.that(
            !HologramPlacementConfirmation.localStructureReviewBlocker(ready, selected, false)
                .isEmpty(),
            "selected construction never uses early whole-structure admission");
        NBTTagCompound details = (NBTTagCompound) target.copy();
        details.setBoolean("capturePending", false);
        details.setBoolean("projectionDataComplete", true);
        details.removeTag("cells");
        var liveReview = new HologramLocalMaterialReview(ready, settings, null, true);
        SimpleAssert.that(
            liveReview.ready() && liveReview.current(ready, 0),
            "formal materials use the sealed exact ledger before candidate details");
        HologramState enriched = ready.withMetadata(details);
        SimpleAssert.that(
            HologramPlacementConfirmation.sameWholeNativeTarget(ready, enriched),
            "candidate publication preserves construction target identity");
        SimpleAssert.that(
            liveReview.current(enriched, 0) && liveReview.wholeLedgerStillSelected(settings)
                && !liveReview.wholeLedgerStillSelected(selected),
            "the exact materials stay open only for the frozen whole-structure scope");
        NBTTagCompound outcome = new NBTTagCompound();
        outcome.setBoolean("nativeBuildSatisfied", true);
        enriched = enriched.withMetadata(outcome);
        SimpleAssert.that(
            !HologramPlacementConfirmation.localStructureReviewBlocker(enriched, settings, false)
                .isEmpty() && HologramPlacementConfirmation.localStructureReviewBlocker(enriched, selected, false)
                    .isEmpty(),
            "completion only retires the finished whole-structure action");
        outcome.setLong("planRevision", 1);
        SimpleAssert.that(
            !HologramPlacementConfirmation.sameWholeNativeTarget(ready, enriched.withMetadata(outcome)),
            "a changed plan revision cannot reuse an in-flight build confirmation");
    }

    private static void independentSelectedRow(HologramState early, NBTTagCompound selected) {
        HologramState.Cell known = new HologramState.Cell(early.cells.get(0), selected);
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("selected", 0);
        request.setInteger("choiceindex", 0);
        try {
            NBTTagCompound row = HologramLocalControllerEdits.selectedRow(known, known, "pin", request);
            HologramState edited = HologramLocalControllerEdits.applySelectedRows(early, list(row), 1);
            SimpleAssert.that(
                edited.data.getBoolean("capturePending") && edited.cells.get(0).wantId.equals("minecraft:dirt")
                    && early.cells.get(0).wantId.equals("minecraft:stone")
                    && "manual".equals(edited.cells.get(0).pinScope),
                "a real local pin renders as a sparse patch without unlocking whole capture or mutating its baseline");
            request.setInteger("selected", 1);
            try {
                HologramLocalControllerEdits.selectedRow(known, known, "pin", request);
                throw new AssertionError("stale position accepted");
            } catch (java.io.IOException expected) {}
            request.setInteger("selected", 0);
            request.removeTag("choiceindex");
            try {
                HologramLocalControllerEdits.selectedRow(known, known, "pin", request);
                throw new AssertionError("omitted candidate became default index zero");
            } catch (java.io.IOException expected) {}
            SimpleAssert.that(
                !HologramLocalControllerEdits.selectedOnly("customPin")
                    && !HologramLocalControllerEdits.selectedOnly("hatchPreset"),
                "no off-scope early mutation");
        } catch (java.io.IOException failure) {
            throw new AssertionError("independent native selection", failure);
        }
        NBTTagCompound input = new NBTTagCompound();
        input.setInteger("mode", 1);
        input.setBoolean("materialMe", true);
        input.setBoolean("presetActive", true);
        input.setBoolean("capturePending", false);
        NBTTagCompound prefs = HologramScreen.localControllerPreferences(input);
        SimpleAssert.that(
            prefs.getInteger("mode") == 1 && prefs.getBoolean("materialMe")
                && !prefs.hasKey("presetActive")
                && !prefs.hasKey("capturePending"),
            "controls carry only local preferences and cannot force readiness or claim quota");
    }

    public static void hatchFacings() {
        Block priorMachines = GregTechAPI.sBlockMachines;
        Block machines = new Block(net.minecraft.block.material.Material.iron) {};
        Block.blockRegistry.addObject(4090, "gtmb:local_hatch_facing_machines", machines);
        Item.itemRegistry.addObject(4090, "gtmb:local_hatch_facing_machines", new ItemMachines(machines));
        GregTechAPI.sBlockMachines = machines;
        try {
            ItemStack hatch = new ItemStack(machines, 1, 101);
            NBTTagCompound sidePayload = new NBTTagCompound();
            sidePayload.setString("variant", "a");
            hatch.setTagCompound(sidePayload);
            ItemStack other = hatch.copy();
            other.getTagCompound()
                .setString("variant", "b");
            var block = new HologramControllerTarget.Cell(
                16,
                64,
                0,
                0,
                0,
                0,
                "minecraft:air",
                "minecraft:stone",
                0,
                0,
                Blocks.air,
                Blocks.stone,
                null,
                new ItemStack(Blocks.stone),
                null,
                null,
                false,
                false,
                false,
                "missing",
                "block",
                "",
                "block",
                "",
                -1,
                -1,
                null,
                null,
                -1,
                true,
                HologramControllerTarget.TargetKind.MATERIAL);
            NBTTagCompound header = new NBTTagCompound();
            header.setString("session", "local-facing");
            header.setString("generation", "read-1");
            header.setBoolean("localStandardController", true);
            header.setBoolean("capturePending", true);
            var target = new HologramState.Preparation(
                new HologramControllerTarget.Projection(header, java.util.Collections.singletonList(block)));
            SimpleAssert.that(target.step(4, Long.MAX_VALUE), "typed target prepared");
            HologramState early = target.result();

            NBTTagCompound details = (NBTTagCompound) header.copy();
            details.setBoolean("capturePending", false);
            details.setBoolean("projectionDataComplete", true);
            NBTTagCompound options = new NBTTagCompound();
            NBTTagList candidates = list(hatch.writeToNBT(new NBTTagCompound()));
            candidates.appendTag(other.writeToNBT(new NBTTagCompound()));
            options.setTag("candidates", candidates);
            options.setIntArray("candidateTiers", new int[] { 1, 2 });
            options.setByteArray("candidateFacingMasks", new byte[] { 1 << 2 | 1 << 5, 1 << 4 });
            details.setTag("cells", list(options));
            HologramState baseline = fixtureOptionRow(early, options);

            HologramState pinned = edit(baseline, baseline, "pin", "choice", 0);
            SimpleAssert.that(
                Arrays.equals(
                    new int[] { 2, 5 },
                    pinned.cells.get(0)
                        .tag()
                        .getIntArray("hatchFacings")),
                "manual pin sees only the selected exact candidate's facing proof");
            HologramState turned = edit(pinned, baseline, "hatchFacing", "facing", 5);
            NBTTagCompound turnedRow = turned.cells.get(0)
                .tag();
            SimpleAssert.that(
                turnedRow.getBoolean("hasFacingPin") && turned.cells.get(0).targetFacing == 5
                    && turnedRow.getCompoundTag("targetDisplay")
                        .getInteger("facing") == 5,
                "explicit face updates both construction intent and display, and enables Restore Facing");
            try {
                edit(pinned, baseline, "hatchFacing", "facing", 4);
                throw new AssertionError("A face proved only for another candidate was accepted");
            } catch (IOException expected) {}

            HologramState reset = edit(turned, baseline, "hatchFacing", "facing", -1);
            SimpleAssert.that(
                reset.cells.get(0).targetFacing == -1 && !reset.cells.get(0)
                    .tag()
                    .getBoolean("hasFacingPin"),
                "restoring facing leaves an alternate candidate on its native auto default");
            HologramState changed = edit(turned, baseline, "pin", "choice", 1);
            NBTTagCompound changedRow = changed.cells.get(0)
                .tag();
            SimpleAssert.that(
                Arrays.equals(new int[] { 4 }, changedRow.getIntArray("hatchFacings"))
                    && !changedRow.getBoolean("hasFacingPin")
                    && changed.cells.get(0).targetFacing == -1,
                "same GT ID but different exact NBT replaces the proof and clears the old override");

            ItemStack unknown = hatch.copy();
            unknown.getTagCompound()
                .setString("variant", "not-in-list");
            NBTTagCompound sample = new NBTTagCompound();
            sample.setInteger("selected", 0);
            sample.setTag("expectedSample", unknown.writeToNBT(new NBTTagCompound()));
            HologramState custom = HologramLocalControllerEdits.apply(turned, baseline, "customPin", sample);
            SimpleAssert.eq(
                0,
                custom.cells.get(0)
                    .tag()
                    .getIntArray("hatchFacings").length,
                "a custom sample without exact proof cannot inherit facing choices");
            NBTTagCompound supplementalDetails = (NBTTagCompound) details.copy();
            NBTTagCompound supplementalOptions = supplementalDetails.getTagList("cells", 10)
                .getCompoundTagAt(0);
            supplementalOptions.setInteger("nativeCandidateCount", 1);
            HologramState separated = fixtureOptionRow(early, supplementalOptions);
            SimpleAssert.that(
                separated.cells.get(0)
                    .tag()
                    .getInteger("nativeCandidateCount") == 1,
                "the native prefix is retained on requested candidate data");
            HologramState manual = edit(separated, separated, "pin", "choice", 1);
            SimpleAssert.that(
                manual.cells.get(0).chosenChoice == 1 && Arrays.equals(
                    new int[] { 4 },
                    manual.cells.get(0)
                        .tag()
                        .getIntArray("hatchFacings")),
                "the supplementary offer can still be pinned manually with exact facing proof");
            SimpleAssert.that(
                !HologramLocalControllerEdits.handles("hatchPreset")
                    && !HologramLocalControllerEdits.handles("clearHatchPreset"),
                "the native preset no longer uses the deprecated row-by-row operation");
            HologramState kept = HologramLocalControllerEdits.replaceNativePreset(
                turned,
                baseline,
                java.util.Collections.singleton(0),
                java.util.Collections.emptyList(),
                0,
                false);
            SimpleAssert.eq(5, kept.cells.get(0).targetFacing, "clear preset never overwrites a manual pin");
            HologramState original = edit(turned, baseline, "restoreCell", "selected", 0);
            SimpleAssert.that(
                original.cells.get(0).targetStack.getItem() == Item.getItemFromBlock(Blocks.stone)
                    && !original.cells.get(0)
                        .tag()
                        .getBoolean("hasFacingPin"),
                "restore default restores the original target and clears the hatch override");
            localBackpackIntentWithoutLegacyPlacement(machines);
        } catch (IOException failure) {
            throw new AssertionError("local controller hatch facing edits", failure);
        } finally {
            GregTechAPI.sBlockMachines = priorMachines;
        }
    }

    /** Run the same sparse backpack edit and the same full-plan compiler as explicit local Confirm. */
    private static void localBackpackIntentWithoutLegacyPlacement(Block machines) throws IOException {
        IMetaTileEntity[] prototypes = GregTechAPI.METATILEENTITIES;
        int id = Math.min(prototypes.length - 1, Short.MAX_VALUE);
        while (id > 1 && (prototypes[id] != null || prototypes[id - 1] != null)) id--;
        SimpleAssert.that(id > 1, "two free registered GT prototype slots for native review regression");
        final int machineId = id, hatchId = id - 1;
        // A real registry entry on an ItemMachines backpack stack, but not a production controller class:
        // loading the live EBF here would bind its static StructureLib casing definition before GT5U is bootstrapped.
        prototypes[machineId] = (IMetaTileEntity) Proxy.newProxyInstance(
            IMetaTileEntity.class.getClassLoader(),
            new Class<?>[] { IMetaTileEntity.class },
            (fixture, method, args) -> {
                throw new AssertionError(
                    "Legacy handpicked material only needs prototype identity: " + method.getName());
            });
        try {
            prototypes[hatchId] = new MTEHatchOutput("gtmb_test_legacy_backpack_hatch", 1, new String[0], null);
            ItemStack existingMachine = new ItemStack(machines, 2, machineId);
            NBTTagCompound retained = new NBTTagCompound();
            retained.setByte("mColor", (byte) 4);
            existingMachine.setTagCompound(retained);
            ItemStack hatch = new ItemStack(machines, 3, hatchId);
            ItemStack coloredHatch = hatch.copy();
            coloredHatch.setTagCompound((NBTTagCompound) retained.copy());
            ItemStack[] backpack = { existingMachine, hatch, coloredHatch };
            NBTTagCompound header = new NBTTagCompound();
            header.setString("session", "local-backpack-review");
            header.setString("generation", "review-1");
            header.setBoolean("localStandardController", true);
            header.setBoolean("projectionDataComplete", true);
            header.setBoolean("supported", true);
            NBTTagList rows = new NBTTagList();
            for (int i = 0; i < 2; i++) {
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("x", 16 + i);
                row.setInteger("y", 64);
                row.setInteger("dx", i + 1);
                row.setString("blockId", "minecraft:air");
                row.setString("wantId", "minecraft:stone");
                row.setString("family", "review-fixture");
                row.setBoolean("inScope", true);
                row.setTag("targetStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
                rows.appendTag(row);
            }
            header.setTag("cells", rows);
            HologramState baseline = new HologramState(header);
            HologramState pinnedMachine = drag(baseline, baseline, backpack, 0, 0);
            HologramState mixed = drag(pinnedMachine, baseline, backpack, 1, 1);
            NBTTagCompound full = new NBTTagCompound();
            full.setTag("channels", new NBTTagCompound());
            NBTTagCompound family = new NBTTagCompound();
            family.setTag("channels", new NBTTagCompound());
            family.setInteger("scope", 3);
            family.setInteger("selected", 0);
            SimpleAssert.eq(
                "",
                HologramScreen.nativeConstructionUnavailable(baseline.data, full, false),
                "an unedited, ordinary full controller stays eligible for the short native request");
            var declaredFamily = HologramScreen
                .declaredSelection(machineId, baseline, family, java.util.Collections.emptySet());
            SimpleAssert.that(
                declaredFamily != null && declaredFamily.scope() == 3
                    && declaredFamily.hatches()
                        .isEmpty(),
                "a selected family is represented on the short native route");
            SimpleAssert.that(
                HologramScreen.declaredSelection(machineId, pinnedMachine, full, java.util.Collections.singleton(0))
                    == null,
                "a tagged arbitrary machine is not a portable native hatch");
            SimpleAssert.that(
                HologramScreen
                    .declaredSelection(machineId, mixed, family, new java.util.LinkedHashSet<>(Arrays.asList(0, 1)))
                    == null,
                "a family action must still reject an invalid hatch choice");

            HologramState.Cell first = mixed.cells.get(0), second = mixed.cells.get(1);
            SimpleAssert.that(
                first.x == 16 && first.targetStack.getItemDamage() == machineId
                    && retained.equals(first.targetStack.getTagCompound())
                    && second.x == 17
                    && second.targetStack.getItemDamage() == hatchId
                    && !second.targetStack.hasTagCompound(),
                "refusing the old geometry review leaves both exact local edits and their separate coordinates intact");

            HologramState pure = drag(baseline, baseline, backpack, 1, 1);
            var declaredHatch = HologramScreen
                .declaredSelection(machineId, pure, full, java.util.Collections.singleton(1));
            SimpleAssert.that(
                declaredHatch != null && declaredHatch.hatches()
                    .size() == 1
                    && declaredHatch.hatches()
                        .get(0)
                        .id() == hatchId,
                "the sparse untagged ordinary hatch is carried on the short route");
            NBTTagCompound presetActive = new NBTTagCompound();
            presetActive.setBoolean("presetActive", true);
            deniedLegacyConstruction(pure.withMetadata(presetActive), full, true);
            deniedLegacyConstruction(baseline.withMetadata(presetActive), full, false);
            HologramState tagged = drag(baseline, baseline, backpack, 2, 0);
            SimpleAssert.that(
                HologramScreen.declaredSelection(machineId, tagged, full, java.util.Collections.singleton(0)) == null,
                "a tagged hatch is not silently stripped and placed on the short route");
            SimpleAssert.eq(
                retained,
                tagged.cells.get(0).targetStack.getTagCompound(),
                "retiring the transport does not sanitize tagged hatch edits out of the local document");
            for (int mode : new int[] { 1, 2 }) {
                NBTTagCompound destructive = new NBTTagCompound();
                destructive.setInteger("mode", mode);
                deniedLegacyConstruction(baseline.withMetadata(destructive), full, false);
            }
            NBTTagCompound selection = new NBTTagCompound();
            selection.setIntArray("selectedIndices", new int[] { 0, 1 });
            deniedLegacyConstruction(baseline, selection, false);
        } finally {
            prototypes[machineId] = null;
            prototypes[hatchId] = null;
        }
    }

    private static HologramState drag(HologramState state, HologramState baseline, ItemStack[] backpack, int slot,
        int row) throws IOException {
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("selected", row);
        request.setInteger("inventorySlot", slot);
        request.setTag("expectedSample", backpack[slot].writeToNBT(new NBTTagCompound()));
        return HologramLocalControllerEdits.apply(state, baseline, "customPin", request);
    }

    private static void deniedLegacyConstruction(HologramState state, NBTTagCompound settings, boolean edited) {
        SimpleAssert.that(
            !HologramScreen.nativeConstructionUnavailable(state.data, settings, edited)
                .isEmpty(),
            "unsupported native action must visibly refuse instead of changing or serializing the plan");
    }

    private static HologramState edit(HologramState state, HologramState baseline, String op, String key, int value)
        throws IOException {
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("selected", 0);
        request.setInteger(key, value);
        return HologramLocalControllerEdits.apply(state, baseline, op, request);
    }

    /** Materializes only the one exact option used by this fixture, never a global detail stage. */
    private static HologramState fixtureOptionRow(HologramState geometry, NBTTagCompound options) {
        NBTTagCompound state = (NBTTagCompound) geometry.data.copy();
        state.setBoolean("capturePending", false);
        state.setBoolean("projectionDataComplete", true);
        state.setTag("cells", list(new HologramState.Cell(geometry.cells.get(0), options).tag()));
        return new HologramState(state);
    }

    private static NBTTagList list(NBTTagCompound row) {
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        return rows;
    }
}
