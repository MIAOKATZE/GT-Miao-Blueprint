// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.testutil.SimpleAssert;

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

    private static NBTTagList list(NBTTagCompound row) {
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        return rows;
    }
}
