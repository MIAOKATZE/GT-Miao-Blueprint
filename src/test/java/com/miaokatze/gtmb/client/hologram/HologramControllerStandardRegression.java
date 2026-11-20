// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.client.hologram.HologramControllerOpenIntent.Route;
import com.miaokatze.gtmb.hologram.HologramWorldFlow.Stage;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramControllerStandardRegression {

    private HologramControllerStandardRegression() {}

    public static void run() {
        SimpleAssert.eq(
            Route.CONTROLLER_PREVIEW,
            HologramControllerOpenIntent.route(Stage.WORKSPACE, false, true),
            "recognized live controller opens a local standard preview");
        SimpleAssert.eq(
            Route.PRIVATE_LIBRARY,
            HologramControllerOpenIntent.route(Stage.WORKSPACE, true, false),
            "empty-space idle use opens the private library");
        SimpleAssert.eq(
            Route.SERVER_WORKSPACE,
            HologramControllerOpenIntent.route(Stage.WORKSPACE, false, false),
            "ordinary blocks keep the workspace route");
        SimpleAssert.eq(
            Route.SERVER_WORKSPACE,
            HologramControllerOpenIntent.route(Stage.JOB, false, true),
            "an active construction retains its existing workspace");

        HologramControllerTemplate.Source machine = new HologramControllerTemplate.Source(Blocks.furnace, 2, 1000);
        NBTTagCompound scratch = new NBTTagCompound();
        scratch.setInteger("mID", 1000);
        SimpleAssert.eq("minecraft:furnace:1000", machine.guideId(), "the GuideNH request includes the machine ID");
        SimpleAssert.that(
            machine.matchesController(Blocks.furnace, 0, "minecraft:furnace", scratch),
            "GuideNH's base registry ID matches the original nonzero meta-tile ID");
        SimpleAssert.that(
            machine.matchesController(Blocks.furnace, 0, "minecraft:furnace:1000", scratch),
            "an explicit full ID also matches the original meta-tile ID");
        scratch.setInteger("mID", 1001);
        SimpleAssert.that(
            !machine.matchesController(Blocks.furnace, 0, "minecraft:furnace", scratch),
            "another machine in the same block registry entry is not the controller");
        NBTTagCompound row = HologramControllerTemplate.row(-2, 3, 4, Blocks.stone, 0, scratch, -1);
        NBTTagCompound retained = row.getCompoundTag("previewTile");
        scratch.setInteger("mID", 1002);
        SimpleAssert.eq(1001, retained.getInteger("mID"), "local display owns the scratch tile tag");
        SimpleAssert.eq(-2, row.getInteger("dx"), "relative negative X is preserved");
        SimpleAssert.eq(3, row.getInteger("dy"), "relative Y is preserved");
        SimpleAssert.eq(4, row.getInteger("dz"), "relative Z is preserved");

        HologramControllerTemplate fallback = new HologramControllerTemplate(
            new HologramControllerTemplate.Source(Blocks.furnace, 2, -1),
            false);
        HologramState state = null;
        for (int attempt = 0; attempt < 8 && state == null; attempt++) state = fallback.step(32, 100_000_000L);
        SimpleAssert.that(!fallback.standard && state != null, "absence of GuideNH still permits a controller display");
        SimpleAssert.eq(1, state.cells.size(), "the fallback contains only the representative controller");
        SimpleAssert
            .eq("minecraft:furnace", state.cells.get(0).wantId, "controller fallback uses the registered block");
        SimpleAssert.that(state.data.getBoolean("localControllerPreview"), "the display uses the local renderer route");
        SimpleAssert.that(!state.data.hasKey("planRevision"), "local display carries no authoritative plan revision");
    }
}
