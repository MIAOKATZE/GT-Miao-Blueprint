// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
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
            16, 64, 0, 0, 0, 0, "minecraft:air", "minecraft:stone", 0, 0, Blocks.air, Blocks.stone, null,
            new ItemStack(Blocks.stone), null, null, false, false, false, "missing", "block", "", "block", "",
            -1, -1, null, null, -1, true);
        var targetPreparation = new HologramState.Preparation(
            new HologramControllerTarget.Projection(target, java.util.Collections.singletonList(block)));
        SimpleAssert.that(targetPreparation.step(4, Long.MAX_VALUE), "typed target prepares a complete spatial index");
        HologramState early = targetPreparation.result();
        SimpleAssert.that(early.cells.get(0).block(false) == Blocks.stone
            && early.cells.get(0).tag().equals(block.tag())
            && early.cells.get(0).localObservationMissing(), "serialization preserves the original typed target");

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
        HologramState.ControllerDetails preparation = new HologramState.ControllerDetails(early, details);
        SimpleAssert.that(preparation.step(1, 1), "one detail completes without re-reading target data");
        HologramState enriched = preparation.result();
        SimpleAssert.that(enriched.reusesControllerGeometry(early), "candidate enrichment preserves the displayed geometry");
        SimpleAssert.that(enriched.regions.at(16, 64, 0) == enriched.cells.get(0), "spatial lookup returns the enriched row");
        SimpleAssert.that(enriched.cells.get(0).chosenChoice == 0 && enriched.cells.get(0).candidateComplete
            && enriched.cells.get(0).tag().getTagList("candidates", 10).tagCount() == 1, "candidate row survives export");
        SimpleAssert.that(early.cells.get(0).candidates.isEmpty()
            && "block".equals(early.cells.get(0).role), "early snapshots remain immutable");

        options.setString("role", "interface");
        preparation = new HologramState.ControllerDetails(early, details);
        preparation.step(1, 1);
        SimpleAssert.that(!preparation.result().reusesControllerGeometry(early), "a new visual marker invalidates the frame");
        details.setString("generation", "read-2");
        try {
            new HologramState.ControllerDetails(early, details);
            throw new AssertionError("Cross-generation candidates were accepted");
        } catch (IllegalArgumentException expected) {
            // The session is still the same, as it is when switching grades in one workbench.
        }
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
            other.getTagCompound().setString("variant", "b");
            var block = new HologramControllerTarget.Cell(
                16, 64, 0, 0, 0, 0, "minecraft:air", "minecraft:stone", 0, 0, Blocks.air, Blocks.stone, null,
                new ItemStack(Blocks.stone), null, null, false, false, false, "missing", "block", "", "block", "",
                -1, -1, null, null, -1, true);
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
            var prepared = new HologramState.ControllerDetails(early, details);
            SimpleAssert.that(prepared.step(4, Long.MAX_VALUE), "candidate proofs accepted only in the details stage");
            HologramState baseline = prepared.result();
            SimpleAssert.that(baseline.reusesControllerGeometry(early), "adding proofs does not rebuild the mesh");

            HologramState pinned = edit(baseline, baseline, "pin", "choice", 0);
            SimpleAssert.that(Arrays.equals(new int[] { 2, 5 }, pinned.cells.get(0).tag().getIntArray("hatchFacings")),
                "manual pin sees only the selected exact candidate's facing proof");
            HologramState turned = edit(pinned, baseline, "hatchFacing", "facing", 5);
            NBTTagCompound turnedRow = turned.cells.get(0).tag();
            SimpleAssert.that(turnedRow.getBoolean("hasFacingPin") && turned.cells.get(0).targetFacing == 5
                && turnedRow.getCompoundTag("targetDisplay").getInteger("facing") == 5,
                "explicit face updates both construction intent and display, and enables Restore Facing");
            try {
                edit(pinned, baseline, "hatchFacing", "facing", 4);
                throw new AssertionError("A face proved only for another candidate was accepted");
            } catch (IOException expected) {}

            HologramState reset = edit(turned, baseline, "hatchFacing", "facing", -1);
            SimpleAssert.that(reset.cells.get(0).targetFacing == -1 && !reset.cells.get(0).tag().getBoolean("hasFacingPin"),
                "restoring facing leaves an alternate candidate on its native auto default");
            HologramState changed = edit(turned, baseline, "pin", "choice", 1);
            NBTTagCompound changedRow = changed.cells.get(0).tag();
            SimpleAssert.that(Arrays.equals(new int[] { 4 }, changedRow.getIntArray("hatchFacings"))
                && !changedRow.getBoolean("hasFacingPin") && changed.cells.get(0).targetFacing == -1,
                "same GT ID but different exact NBT replaces the proof and clears the old override");

            ItemStack unknown = hatch.copy();
            unknown.getTagCompound().setString("variant", "not-in-list");
            NBTTagCompound sample = new NBTTagCompound();
            sample.setInteger("selected", 0);
            sample.setTag("expectedSample", unknown.writeToNBT(new NBTTagCompound()));
            HologramState custom = HologramLocalControllerEdits.apply(turned, baseline, "customPin", sample);
            SimpleAssert.eq(0, custom.cells.get(0).tag().getIntArray("hatchFacings").length,
                "a custom sample without exact proof cannot inherit facing choices");
            HologramState preset = edit(baseline, baseline, "hatchPreset", "tier", 2);
            SimpleAssert.that(Arrays.equals(new int[] { 4 }, preset.cells.get(0).tag().getIntArray("hatchFacings")),
                "tier preset publishes the selected candidate's proof");
            NBTTagCompound supplementalDetails = (NBTTagCompound) details.copy();
            NBTTagCompound supplementalOptions = supplementalDetails.getTagList("cells", 10).getCompoundTagAt(0);
            supplementalOptions.setInteger("nativeCandidateCount", 1);
            var separatedDetails = new HologramState.ControllerDetails(early, supplementalDetails);
            SimpleAssert.that(separatedDetails.step(4, Long.MAX_VALUE), "offers with distinct native proof enrich in place");
            HologramState separated = separatedDetails.result();
            SimpleAssert.that(separated.reusesControllerGeometry(early)
                && separated.cells.get(0).tag().getInteger("nativeCandidateCount") == 1,
                "the native prefix survives details assembly without touching target geometry");
            HologramState manual = edit(separated, separated, "pin", "choice", 1);
            SimpleAssert.that(manual.cells.get(0).chosenChoice == 1
                && Arrays.equals(new int[] { 4 }, manual.cells.get(0).tag().getIntArray("hatchFacings")),
                "the supplementary offer can still be pinned manually with exact facing proof");
            HologramState skipped = edit(separated, separated, "hatchPreset", "tier", 2);
            SimpleAssert.that(skipped.cells.get(0).chosenChoice == -1,
                "a supplementary option does not get picked by the legacy local tier preset");
            HologramState nativeTier = edit(separated, separated, "hatchPreset", "tier", 1);
            SimpleAssert.that(nativeTier.cells.get(0).chosenChoice == 0,
                "the native prefix preserves the prior available tier preset");
            for (int malformed : new int[] { -1, 3 }) {
                supplementalOptions.setInteger("nativeCandidateCount", malformed);
                separatedDetails = new HologramState.ControllerDetails(early, supplementalDetails);
                SimpleAssert.that(separatedDetails.step(4, Long.MAX_VALUE), "malformed prefix does not break the drawer");
                HologramState invalid = separatedDetails.result();
                SimpleAssert.that(edit(invalid, invalid, "hatchPreset", "tier", 2).cells.get(0).chosenChoice == -1
                    && edit(invalid, invalid, "pin", "choice", 1).cells.get(0).chosenChoice == 1,
                    "malformed optional proof fails closed only for automatic selection");
            }
            NBTTagList unparseablePrefix = list(new NBTTagCompound());
            unparseablePrefix.appendTag(other.writeToNBT(new NBTTagCompound()));
            supplementalOptions.setTag("candidates", unparseablePrefix);
            supplementalOptions.setInteger("nativeCandidateCount", 1);
            supplementalOptions.setIntArray("candidateTiers", new int[] { 2, 2 });
            separatedDetails = new HologramState.ControllerDetails(early, supplementalDetails);
            SimpleAssert.that(separatedDetails.step(4, Long.MAX_VALUE), "unreadable stack is normalized");
            HologramState normalized = separatedDetails.result();
            SimpleAssert.that(normalized.cells.get(0).candidates.size() == 1
                && edit(normalized, normalized, "hatchPreset", "tier", 2).cells.get(0).chosenChoice == -1,
                "an unreadable native stack cannot promote a formerly manual-only option into the automatic prefix");
            HologramState kept = edit(turned, baseline, "hatchPreset", "tier", 2);
            SimpleAssert.eq(5, kept.cells.get(0).targetFacing, "tier preset does not overwrite a manual pin");
            HologramState restored = edit(preset, baseline, "clearHatchPreset", "tier", 0);
            SimpleAssert.eq(0, restored.cells.get(0).tag().getIntArray("hatchFacings").length,
                "clear preset restores the casing without hatch choices");
            HologramState original = edit(turned, baseline, "restoreCell", "selected", 0);
            SimpleAssert.that(original.cells.get(0).targetStack.getItem() == Item.getItemFromBlock(Blocks.stone)
                && !original.cells.get(0).tag().getBoolean("hasFacingPin"),
                "restore default restores the original target and clears the hatch override");
        } catch (IOException failure) {
            throw new AssertionError("local controller hatch facing edits", failure);
        } finally {
            GregTechAPI.sBlockMachines = priorMachines;
        }
    }

    private static HologramState edit(HologramState state, HologramState baseline, String op, String key, int value)
        throws IOException {
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("selected", 0);
        request.setInteger(key, value);
        return HologramLocalControllerEdits.apply(state, baseline, op, request);
    }

    private static NBTTagList list(NBTTagCompound row) {
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        return rows;
    }
}
