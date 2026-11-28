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
        for (int i = 1; i <= 4; i++) {
            update = new NBTTagCompound();
            update.setInteger("nativeAction", 0);
            update.setString("nativeStage", "UNFORMED");
            update.setLong("nativeTargetRevision", 1);
            update.setBoolean("nativeTargetReady", true);
            update.setString("nativeObservationReason", "");
            update.setString("nativeTargetReason", "");
            HologramState observed = receipt.withMetadata(update);
            SimpleAssert.that(
                observed.reusesControllerGeometry(receipt) && observed.cells == receipt.cells
                    && observed.regions == receipt.regions,
                "repeated native observations retain geometry and unfinished renderer cursors");
            receipt = observed;
        }
        update = new NBTTagCompound();
        NBTTagCompound changedChannel = new NBTTagCompound();
        changedChannel.setInteger("width", 7);
        update.setTag("channels", changedChannel);
        SimpleAssert.that(
            !receipt.withMetadata(update)
                .reusesControllerGeometry(receipt),
            "a genuine channel change cannot retain the old target presentation");
        update = new NBTTagCompound();
        update.setInteger("mode", 2);
        SimpleAssert.that(
            !earlyMetadata.withMetadata(update)
                .reusesControllerGeometry(earlyMetadata),
            "a material-view mode change still invalidates the presentation");
        update = new NBTTagCompound();
        NBTTagCompound publishedSettings = HologramScreen.targetSettings(earlyMetadata);
        publishedSettings.setInteger("main", 4);
        NBTTagCompound sector = new NBTTagCompound();
        sector.setInteger("ore_center_sectors", 1);
        publishedSettings.setTag("channels", sector);
        HologramState published = earlyMetadata.withMetadata(publishedSettings);
        NBTTagCompound requestedSettings = (NBTTagCompound) publishedSettings.copy();
        requestedSettings.getCompoundTag("channels")
            .setInteger("ore_center_sectors", 5);
        SimpleAssert.that(
            HologramScreen.requiresControllerCapture(published, requestedSettings, publishedSettings, true),
            "a changed sector requires a complete new target");
        NBTTagCompound failedState = new NBTTagCompound();
        failedState.setBoolean("nativeTargetReady", false);
        HologramState retained = published.withMetadata(failedState);
        SimpleAssert.that(
            retained.cells == published.cells && HologramScreen.targetSettings(retained)
                .equals(publishedSettings),
            "failure retains the exact published geometry together with its original sector parameters");
        SimpleAssert.that(
            HologramScreen.requiresControllerCapture(retained, publishedSettings, publishedSettings, false),
            "an explicit retry with the same value still regenerates a failed target");
        var demolitionState = new HologramNativeControllerState();
        demolitionState.targetFailed("failed new target");
        demolitionState.observe(
            new com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation(
                1,
                0,
                0,
                0,
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage.UNFORMED,
                demolitionState.targetRevision(),
                ""));
        NBTTagCompound unsupportedTarget = new NBTTagCompound();
        unsupportedTarget.setBoolean("nativeExecutionSupported", false);
        SimpleAssert.eq(
            "",
            HologramScreen.nativeTargetBlocker(
                demolitionState,
                unsupportedTarget,
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.DEMOLISH),
            "observed site demolition remains available when the new target failed or has unsupported materials");
        SimpleAssert.that(
            !HologramScreen
                .nativeTargetBlocker(
                    demolitionState,
                    unsupportedTarget,
                    com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.REPAIR)
                .isEmpty(),
            "unsupported target materials still block material placement actions");
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
        target.setString("session", "whole-native-before-details");
        target.setString("generation", "target-1");
        target.setBoolean("target", true);
        target.setBoolean("supported", true);
        target.setBoolean("localStandardController", true);
        target.setBoolean("capturePending", true);
        target.setLong("nativeTargetRevision", 1);
        var counts = new com.miaokatze.gtmb.hologram.HologramControllerMaterialCounts.Builder();
        counts.add(cell, 0, 0, 0);
        var preparation = new HologramState.Preparation(
            new HologramControllerTarget.Projection(target, java.util.Collections.singletonList(cell), counts.seal()));
        SimpleAssert.that(preparation.step(32, Long.MAX_VALUE), "native target and ledger are published together");
        NBTTagCompound published = new NBTTagCompound();
        published.setBoolean("nativeTargetReady", true);
        HologramState ready = preparation.result()
            .withMetadata(published);
        NBTTagCompound settings = new NBTTagCompound();
        settings.setInteger("scope", 0);
        SimpleAssert.that(
            HologramPlacementConfirmation.localStructureReviewBlocker(ready)
                .isEmpty()
                && HologramPlacementConfirmation.localStructureReviewBlocker(ready, settings, true)
                    .isEmpty(),
            "a complete native target can be reviewed independently of candidate-browser enrichment");
        SimpleAssert.that(
            !HologramPlacementConfirmation.localStructureReviewBlocker(new HologramState(target))
                .isEmpty(),
            "a controller header without the published target cannot claim material readiness");
        var liveReview = new HologramLocalMaterialReview(ready, settings);
        SimpleAssert.that(
            liveReview.ready() && liveReview.current(ready, 0)
                && liveReview.hatchDelta.unknown == 1
                && liveReview.hatchDelta.theory.getInteger("unaccounted") == 1,
            "formal review can open before candidate details while unread world observations remain explicitly unaccounted");
        NBTTagCompound details = new NBTTagCompound();
        details.setBoolean("capturePending", false);
        details.setBoolean("projectionDataComplete", true);
        HologramState enriched = ready.withMetadata(details);
        SimpleAssert.that(
            liveReview.current(enriched, 0) && liveReview.current(enriched, 7),
            "candidate and visual-layer changes preserve the same complete target review");
        NBTTagCompound nextTarget = new NBTTagCompound();
        nextTarget.setLong("nativeTargetRevision", 2);
        nextTarget.setBoolean("nativeTargetReady", false);
        SimpleAssert.that(
            !liveReview.current(enriched.withMetadata(nextTarget), 0),
            "a new target revision retires the old confirmation even before new cells arrive");
        NBTTagCompound closed = new NBTTagCompound();
        closed.setBoolean("targetClosed", true);
        SimpleAssert.that(
            !liveReview.current(enriched.withMetadata(closed), 0),
            "a closed controller cannot retain a live material confirmation");
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
            SimpleAssert.that(
                !kept.data.getBoolean("presetActive") && !kept.data.getBoolean("nativePresetExact")
                    && kept.data.getInteger("hatchTier") == -1,
                "clearing a preset returns to the explicit unselected state");
            HologramState original = edit(turned, baseline, "restoreCell", "selected", 0);
            SimpleAssert.that(
                original.cells.get(0).targetStack.getItem() == Item.getItemFromBlock(Blocks.stone)
                    && !original.cells.get(0)
                        .tag()
                        .getBoolean("hasFacingPin"),
                "restore default restores the original target and clears the hatch override");
            nativeFacingChoices(machines, turnedRow);
            localSamplesAndFullTargetReview(machines);
        } catch (IOException failure) {
            throw new AssertionError("local controller hatch facing edits", failure);
        } finally {
            GregTechAPI.sBlockMachines = priorMachines;
        }
    }

    private static void nativeFacingChoices(Block machines, NBTTagCompound template) throws IOException {
        NBTTagCompound row = (NBTTagCompound) template.copy();
        row.setTag("targetStack", new ItemStack(machines, 1, 101).writeToNBT(new NBTTagCompound()));
        row.setIntArray("hatchFacings", new int[] { 2, 5 });
        NBTTagCompound display = row.getCompoundTag("targetDisplay");
        display.setInteger("casingTexture", 73);
        display.setString("channelPayload", "retained");
        row.setTag("targetDisplay", display);
        HologramState.Cell turned = new HologramState.Cell(0, row);
        NBTTagCompound choice = HologramLocalControllerEdits.nativeChoice(turned);
        SimpleAssert.that(
            choice.getInteger("id") == 101 && choice.getInteger("facing") == 5,
            "the existing native choice carries the selected model and explicit legal facing");

        NBTTagCompound replayed = (NBTTagCompound) row.copy();
        replayed.removeTag("hatchFacing");
        replayed.removeTag("hasFacingPin");
        NBTTagCompound intent = HologramLocalControllerEdits
            .nativeFacingRow(new HologramState.Cell(0, replayed), list(choice));
        SimpleAssert.that(
            intent.getInteger("hatchFacing") == 5 && intent.getBoolean("hasFacingPin")
                && intent.getCompoundTag("targetDisplay")
                    .equals(display),
            "replayed native geometry retains restore intent without replacing casing or display NBT");
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("selected", 0);
        request.setInteger("facing", -1);
        HologramState.Cell selected = new HologramState.Cell(0, intent);
        NBTTagCompound restored = HologramLocalControllerEdits.selectedRow(selected, selected, "hatchFacing", request);
        NBTTagCompound restoredChoice = HologramLocalControllerEdits.nativeChoice(new HologramState.Cell(0, restored));
        SimpleAssert.that(
            restoredChoice.getInteger("id") == 101 && restoredChoice.getInteger("facing") == -1
                && !restored.getBoolean("hasFacingPin"),
            "restore submits the same native model with no direction override");

        NBTTagCompound changedModel = (NBTTagCompound) replayed.copy();
        changedModel.setTag("targetStack", new ItemStack(machines, 1, 102).writeToNBT(new NBTTagCompound()));
        NBTTagCompound changedIntent = HologramLocalControllerEdits
            .nativeFacingRow(new HologramState.Cell(0, changedModel), list(choice));
        SimpleAssert.that(
            changedIntent.getInteger("hatchFacing") == -1 && !changedIntent.getBoolean("hasFacingPin"),
            "changing the native model cannot inherit the previous model's facing pin");
        NBTTagCompound wrongPosition = (NBTTagCompound) choice.copy();
        wrongPosition.setInteger("x", choice.getInteger("x") + 1);
        SimpleAssert.that(
            !HologramLocalControllerEdits.nativeFacingRow(turned, list(wrongPosition))
                .getBoolean("hasFacingPin"),
            "direction intent is attached only to its selected native coordinate");
    }

    /** Local documents retain exact samples; native confirmation reviews the complete revisioned target. */
    private static void localSamplesAndFullTargetReview(Block machines) throws IOException {
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
                throw new AssertionError("Local sample only needs prototype identity: " + method.getName());
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
                row.setString("id", "minecraft:air");
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
            HologramState.Cell first = mixed.cells.get(0), second = mixed.cells.get(1);
            SimpleAssert.that(
                first.x == 16 && first.targetStack.getItemDamage() == machineId
                    && retained.equals(first.targetStack.getTagCompound())
                    && second.x == 17
                    && second.targetStack.getItemDamage() == hatchId
                    && !second.targetStack.hasTagCompound(),
                "local document edits preserve exact samples and independent coordinates without granting construction authority");
            HologramState tagged = drag(baseline, baseline, backpack, 2, 0);
            SimpleAssert.eq(
                retained,
                tagged.cells.get(0).targetStack.getTagCompound(),
                "the local document never strips a tagged hatch into a different material identity");
            wholeNativeTargetContract(header);
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

    private static void wholeNativeTargetContract(NBTTagCompound original) {
        NBTTagCompound input = (NBTTagCompound) original.copy();
        input.setLong("nativeTargetRevision", 7);
        input.setBoolean("nativeTargetReady", true);
        input
            .setInteger("nativeAction", com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.REPAIR.ordinal());
        input.setInteger("main", 3);
        NBTTagCompound channels = new NBTTagCompound();
        channels.setInteger("width", 9);
        input.setTag("channels", channels);
        NBTTagCompound existing = input.getTagList("cells", 10)
            .getCompoundTagAt(0);
        existing.setString("id", "minecraft:stone");
        existing.setTag("actualStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        HologramState source = new HologramState(input);
        HologramUiModules ui = new HologramUiModules(null);
        try {
            var field = HologramUiSession.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(ui.session, source);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("detached native configuration fixture", failure);
        }
        ui.preview.selected(1);
        ui.preview.selection()
            .click(1, false);
        NBTTagCompound full = ui.config.configuration();
        SimpleAssert.that(
            full.getInteger("scope") == 0 && full.getInteger("selected") == -1
                && full.getIntArray("selectedIndices").length == 0
                && full.getString("structureId")
                    .isEmpty(),
            "native execution always reviews the full target rather than a selected hatch or family");
        SimpleAssert.that(
            ui.preview.selected() == 1 && ui.preview.selection()
                .size() == 1,
            "full-target execution leaves the user's visual selection intact");
        SimpleAssert.that(
            full.getInteger("main") == 3 && full.getCompoundTag("channels")
                .getInteger("width") == 9,
            "main and auxiliary channels coexist in every native action");
        full.getCompoundTag("channels")
            .setInteger("width", 11);
        SimpleAssert.eq(
            9,
            source.data.getCompoundTag("channels")
                .getInteger("width"),
            "confirmation settings do not mutate the published target configuration");
        full.getCompoundTag("channels")
            .setInteger("width", 9);
        HologramLocalMaterialReview review = new HologramLocalMaterialReview(source, full, ui.preview.selection());
        SimpleAssert.that(
            review.ready() && review.hatchDelta != null
                && review.hatchDelta.exchanges == 1
                && review.hatchDelta.unchanged == 1,
            "repair estimates include the missing unselected cell and leave the same installed material unchanged");
        SimpleAssert.eq(
            1L,
            review.hatchDelta.theory.getTagList("rows", 10)
                .getCompoundTagAt(0)
                .getLong("required"),
            "matching installed material is excluded from the replacement-material estimate");
        NBTTagCompound demolition = new NBTTagCompound();
        demolition.setInteger(
            "nativeAction",
            com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.DEMOLISH.ordinal());
        demolition.setBoolean("nativeTargetReady", false);
        demolition.setString("nativeStage", "UNFORMED");
        HologramState observed = source.withMetadata(demolition);
        NBTTagCompound whole = new NBTTagCompound();
        whole.setInteger("scope", 0);
        HologramLocalMaterialReview removal = new HologramLocalMaterialReview(observed, whole);
        SimpleAssert.that(
            removal.ready() && removal.hatchDelta.removals == 1
                && removal.hatchDelta.blocker.isEmpty()
                && removal.current(observed, 0),
            "whole-machine demolition needs neither a selected hatch nor a successfully generated new target");
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
