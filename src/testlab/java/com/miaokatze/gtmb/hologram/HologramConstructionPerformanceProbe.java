// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

/** Twenty ordinary cells through real projection/review/job/world/payment, one production slice per real lab tick. */
public final class HologramConstructionPerformanceProbe {

    private final WorldServer world;
    private final FakePlayer actor;
    private final HologramSession session;
    private final int x, y, z;
    private final long started, writesBefore, publishBefore;
    private final NBTTagList slices = new NBTTagList();

    public HologramConstructionPerformanceProbe(WorldServer world, FakePlayer actor, int x, int y, int z)
        throws Exception {
        require(
            com.miaokatze.gtmb.testlab.TestLab.isOwnedFixture(world, actor, x, y, z, 5, 1, 4),
            "Performance probe requires active owned testlab cells");
        for (Object user : world.playerEntities)
            require(user instanceof FakePlayer, "Human presence forbids construction performance fixture");
        this.world = world;
        this.actor = actor;
        this.x = x;
        this.y = y;
        this.z = z;
        for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 5; dx++)
            require(world.isAirBlock(x + dx, y, z + dz), "Performance fixture cells must be empty");
        actor.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 20);
        actor.setPosition(x + 2, y + 1, z + 2);
        session = new HologramSession(actor, x, y, z, false, 1);
        session.virtual = session.virtualPlacement = session.virtualLocked = session.open = true;
        session.materialSettings.main = true;
        session.materialSettings.containers = session.materialSettings.me = false;
        session.materialContext = new HologramMaterials.Context(actor, session.materialSettings);
        session.virtualPreset = HologramTestLabBlueprintProbe.preset(actor.getUniqueID(), actor.getCommandSenderName());
        session.virtualPreset.cells.clear();
        for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 5; dx++) {
            HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), dx, 0, dz, -1, false);
            cell.block = "minecraft:stone";
            session.virtualPreset.cells.add(cell);
        }
        HologramPresets.validate(session.virtualPreset);
        HologramBlueprintProjection.populateVirtual(session);
        NBTTagCompound review = new NBTTagCompound();
        review.setString("session", session.id);
        review.setLong("planRevision", session.planRevision);
        review.setLong("placementReviewNonce", session.placementReviewNonce);
        review.setBoolean("forceExplicit", false);
        writesBefore = HologramConstructionJournal.publications;
        publishBefore = HologramConstructionJournal.publishNanos;
        started = System.nanoTime();
        HologramBuildPreparation.reviewedBuild(session, review);
        require(
            session.job == 1 && session.work.size() == 20,
            "Twenty-cell native reviewed build not admitted: " + session.status);
    }

    public boolean step() {
        int cursor = session.cursor;
        long start = System.nanoTime(), writes = HologramConstructionJournal.publications;
        HologramPlacementJobs.tickJob(session);
        NBTTagCompound slice = new NBTTagCompound();
        slice.setInteger("attemptedCells", session.cursor - cursor);
        slice.setLong("nanos", System.nanoTime() - start);
        slice.setLong("durablePublications", HologramConstructionJournal.publications - writes);
        slices.appendTag(slice);
        require(session.job == 1 || session.job == 3, "Construction stopped unexpectedly: " + session.status);
        return session.job == 3;
    }

    public NBTTagCompound evidence() {
        require(
            session.completed == 20 && actor.inventory.mainInventory[1] == null,
            "Actual twenty-cell material debit differs");
        for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 5; dx++)
            require(world.getBlock(x + dx, y, z + dz) == Blocks.stone, "Actual performance fixture placement differs");
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("cells", session.completed);
        result.setTag("realTickSlices", slices);
        result.setLong("elapsedNanos", System.nanoTime() - started);
        result.setLong("durablePublications", HologramConstructionJournal.publications - writesBefore);
        result.setLong("publishNanos", HologramConstructionJournal.publishNanos - publishBefore);
        result.setString(
            "scope",
            "ordinary main-inventory construction; real WorldTransactions; no AE-grid or global performance claim");
        return result;
    }

    public void cleanup() {
        for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 5; dx++)
            if (world.getBlock(x + dx, y, z + dz) == Blocks.stone) world.setBlockToAir(x + dx, y, z + dz);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
