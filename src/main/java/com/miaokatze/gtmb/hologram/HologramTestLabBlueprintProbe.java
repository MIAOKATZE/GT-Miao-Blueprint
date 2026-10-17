package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testlab.TestLab;
import com.miaokatze.gtmb.testlab.TestLabWorldProvider;

/** Agent-specific lab fixture, never an API for arbitrary actors, blueprints or world writes. No network authority. */
public final class HologramTestLabBlueprintProbe {

    private HologramTestLabBlueprintProbe() {}

    public static NBTTagCompound run(WorldServer world, FakePlayer actor, int x, int y, int z) throws IOException {
        requireOwned(world, actor, x, y, z);
        require(
            count(actor, Blocks.stone) == 0 && count(actor, Blocks.chest) == 0,
            "Ordinary fixture requires no pre-existing stone/chest stock; reset the lab first");
        NBTTagCompound original = worldState(world, x, y, z);
        for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 4; dx++)
            require(world.isAirBlock(x + dx, y, z + dz), "Ordinary fixture cells must be empty; reset the lab first");
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("serverBackendOnly", true);
        result.setBoolean("actualNetty", false);
        result.setBoolean("guiReviewExercised", false);
        result.setBoolean("fixturePreset", true);
        result.setBoolean("jobSlicesDrivenDirectly", true);
        result.setString(
            "transportScope",
            "Production snapshot outbox may queue DTOs; disconnected FakePlayer delivery is not tested");
        result.setString("entry", "Projection/ResourcePlanner/reviewedBuild/PlacementJobs.tickJob/WorldTransactions");
        result.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        result.setInteger("dimension", world.provider.dimensionId);
        result.setIntArray("origin", new int[] { x, y, z });
        NBTTagList cases = new NBTTagList();
        result.setTag("cases", cases);

        HologramSession missing = fixture(actor, x, y, z + 2);
        NBTTagList noStock = inventory(actor);
        NBTTagCompound denied = request(missing);
        HologramBuildPreparation.reviewedBuild(missing, denied);
        HologramBuildPreparation.reviewedBuild(missing, (NBTTagCompound) denied.copy());
        require(
            missing.job == 0 && missing.planMissing > 0,
            "Missing materials must not start a job, including replay");
        require(
            !HologramWorldTransactions.operate(missing, missing.capture.cells.get(0), 0),
            "The final per-cell transaction must independently reject missing materials");
        require("missing".equals(missing.capture.cells.get(0).status), "Missing transaction must report missing");
        require(
            original.equals(worldState(world, x, y, z)) && noStock.equals(inventory(actor)),
            "Missing-material plan/request/final transaction changed world or inventory");
        NBTTagCompound rejected = receipt("missing_materials_and_repeat", missing, actor);
        rejected.setTag("world", worldState(world, x, y, z));
        cases.appendTag(rejected);

        supply(actor, new ItemStack(Blocks.stone, 4));
        supply(actor, new ItemStack(Blocks.chest, 3));
        HologramSession paid = fixture(actor, x, y, z);
        NBTTagList funded = inventory(actor);
        long revision = paid.planRevision;
        HologramResourcePlanner.refreshPlan(paid);
        NBTTagCompound move = new NBTTagCompound();
        move.setIntArray("indices", new int[] { 0 });
        move.setInteger("dx", 1);
        HologramBlueprintEditor.moveBlueprintCells(paid, move);
        require(
            paid.capture.positions.containsKey((x + 1) + "," + y + "," + z),
            "Real blueprint geometry edit did not move the draft");
        HologramBlueprintEditor.edit(paid, new NBTTagCompound(), "editUndo");
        require(
            paid.capture.positions.containsKey(x + "," + y + "," + z),
            "Real draft undo did not restore its coordinate");
        require(
            paid.planRevision > revision && paid.capture.cells.size() == 2 && paid.planMissing == 0,
            "Normal planner must derive a funded two-cell plan after editing");
        require(
            original.equals(worldState(world, x, y, z)) && funded.equals(inventory(actor)),
            "Blueprint projection/planning/edit/undo mutated real world or consumed material");
        NBTTagCompound planning = receipt("planning_edit_undo_read_only", paid, actor);
        planning.setTag("world", worldState(world, x, y, z));
        cases.appendTag(planning);

        NBTTagCompound build = request(paid);
        requireOwned(world, actor, x, y, z);
        HologramBuildPreparation.reviewedBuild(paid, build);
        require(
            paid.job == 1 && paid.work.size() == 2,
            "Production reviewed job did not admit two cells: " + paid.status);
        int slices = 0;
        // Two ordinary cells need at most two production slices. No tick, connection, ACK or job result is invented.
        while (paid.job == 1 && slices++ < 2) {
            requireOwned(world, actor, x, y, z);
            HologramPlacementJobs.tickJob(paid);
            require(paid.job != 1 || paid.cursor == 1, "Ordinary fixture deferred unexpectedly: " + paid.status);
        }
        require(
            paid.job == 3 && paid.cursor == 2 && paid.completed == 2,
            "Production job did not reach its complete terminal state: " + paid.status);
        require(
            world.getBlock(x, y, z) == Blocks.stone && world.getBlockMetadata(x, y, z) == 0
                && world.getBlock(x + 2, y, z) == Blocks.chest,
            "Real ordinary stone/chest placement differs");
        TileEntity tile = world.getTileEntity(x + 2, y, z);
        require(tile instanceof TileEntityChest, "Normal chest placement did not create its native tile");
        TileEntityChest chest = (TileEntityChest) tile;
        for (int slot = 0; slot < chest.getSizeInventory(); slot++)
            require(chest.getStackInSlot(slot) == null, "Ordinary empty chest fixture created unpriced inventory");
        require(
            count(actor, Blocks.stone) == 3 && count(actor, Blocks.chest) == 2,
            "Normal survival construction must debit exactly one stone and one chest");
        NBTTagList expectedPaid = expectedPaidInventory(funded);
        require(
            expectedPaid.equals(inventory(actor)),
            "Construction changed other inventory items, NBT or slot identity");
        NBTTagCompound completed = receipt("ordinary_paid_job_complete", paid, actor);
        completed.setInteger("productionSlices", slices);
        completed.setTag("world", worldState(world, x, y, z));
        completed.setTag("nativeChest", HologramNativeCapture.capture(chest));
        cases.appendTag(completed);

        NBTTagCompound builtWorld = worldState(world, x, y, z);
        NBTTagList paidInventory = inventory(actor);
        HologramBuildPreparation.reviewedBuild(paid, (NBTTagCompound) build.copy());
        HologramBuildPreparation.reviewedBuild(paid, (NBTTagCompound) build.copy());
        require(
            builtWorld.equals(worldState(world, x, y, z)) && paidInventory.equals(inventory(actor))
                && world.getTileEntity(x + 2, y, z) == chest
                && paid.job != 1,
            "Repeated reviewed backend request wrote the world or debited inventory twice");
        NBTTagCompound replay = receipt("same_review_request_no_second_payment", paid, actor);
        replay.setLong("originalRequestRevision", build.getLong("planRevision"));
        replay.setTag("world", builtWorld.copy());
        cases.appendTag(replay);
        result.setTag("originalWorld", original);
        result.setTag("finalWorld", builtWorld);
        result.setTag("fundedInventory", funded);
        result.setTag("paidInventory", paidInventory);
        result.setTag("expectedPaidInventory", expectedPaid);
        result.setInteger("placedCells", 2);
        result.setInteger("completedJobBeforeReplay", completed.getInteger("job"));
        result.setInteger("jobAfterReplay", paid.job);
        result.setInteger("stoneDebit", 1);
        result.setInteger("chestDebit", 1);
        result.setBoolean("success", true);
        return (NBTTagCompound) result.copy();
    }

    private static void requireOwned(WorldServer world, FakePlayer actor, int x, int y, int z) throws IOException {
        if (!TestLab.isEnabled() || world == null
            || actor == null
            || !(world.provider instanceof TestLabWorldProvider)
            || world.isRemote
            || actor.worldObj != world
            || actor.capabilities == null
            || actor.capabilities.isCreativeMode
            || actor.isDead
            || !actor.capabilities.allowEdit
            || !ItemNekoHologramProjector.isProjector(actor.getHeldItem())
            || !TestLab.isOwnedFixture(world, actor, x, y, z, 4, 1, 4))
            throw new IOException("Ordinary blueprint fixture requires the active owned lab FakePlayer and projector");
        for (Object user : world.playerEntities) if (!(user instanceof FakePlayer))
            throw new IOException("Human presence forbids automated lab construction");
    }

    private static HologramSession fixture(FakePlayer actor, int x, int y, int z) throws IOException {
        HologramSession session = new HologramSession(actor, x, y, z, false, 1);
        session.virtual = session.virtualPlacement = session.virtualLocked = true;
        session.open = true; // A complete DTO intent; no GUI or connected FakePlayer is asserted.
        session.materialSettings.main = true;
        session.materialSettings.containers = session.materialSettings.me = false;
        session.materialContext = new HologramMaterials.Context(actor, session.materialSettings);
        session.virtualPreset = preset(actor.getUniqueID(), actor.getCommandSenderName());
        HologramBlueprintProjection.populateVirtual(session);
        require(
            HologramSessionPolicy.valid(session) && !session.capture.incomplete && session.capture.cells.size() == 2,
            "Normal blueprint projection/session admission failed");
        return session;
    }

    static HologramPresets.Preset preset(UUID owner, String name) throws IOException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = UUID.randomUUID()
            .toString();
        preset.name = "Testlab ordinary paid stone and empty chest";
        preset.controllerType = "area";
        preset.authorName = name;
        preset.authorUuid = owner.toString();
        preset.sourceMod = "minecraft";
        preset.sourceClass = HologramTestLabBlueprintProbe.class.getName();
        preset.createdAt = System.currentTimeMillis();
        HologramPresets.Cell stone = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        stone.block = "minecraft:stone";
        HologramPresets.Cell chest = HologramPresets.cell(new ItemStack(Blocks.chest), 2, 0, 0, -1, false);
        chest.block = "minecraft:chest";
        chest.metadata = 2;
        preset.cells.add(stone);
        preset.cells.add(chest);
        HologramPresets.validate(preset);
        return preset;
    }

    private static NBTTagCompound request(HologramSession session) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", session.id);
        request.setLong("planRevision", session.planRevision);
        request.setBoolean("forceExplicit", false);
        return request;
    }

    private static NBTTagCompound receipt(String name, HologramSession session, FakePlayer actor) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("case", name);
        row.setString("session", session.id);
        row.setString("fixturePresetId", session.virtualPreset.id);
        row.setBoolean("passed", true);
        row.setInteger("job", session.job);
        row.setInteger("cursor", session.cursor);
        row.setInteger("completed", session.completed);
        row.setInteger("planMissing", session.planMissing);
        row.setLong("planRevision", session.planRevision);
        row.setString("status", session.status);
        row.setTag("progress", HologramSnapshots.progress(session));
        row.setTag("inventory", inventory(actor));
        NBTTagList cells = new NBTTagList();
        for (int index = 0; index < session.capture.cells.size(); index++) {
            HologramCapture.Cell cell = session.capture.cells.get(index);
            NBTTagCompound data = new NBTTagCompound();
            data.setIntArray("position", new int[] { cell.x, cell.y, cell.z });
            data.setString("status", cell.status);
            data.setString("wantBlock", String.valueOf(Block.blockRegistry.getNameForObject(cell.block)));
            data.setInteger("wantMeta", cell.meta);
            ItemStack target = HologramCellPolicy.targetFor(session, cell, index);
            if (target != null) data.setTag(
                "paymentStack",
                target.copy()
                    .writeToNBT(new NBTTagCompound()));
            cells.appendTag(data);
        }
        row.setTag("cells", cells);
        return row;
    }

    private static NBTTagCompound worldState(WorldServer world, int x, int y, int z) throws IOException {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList cells = new NBTTagList();
        for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 4; dx++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString(
                "block",
                String.valueOf(Block.blockRegistry.getNameForObject(world.getBlock(x + dx, y, z + dz))));
            row.setInteger("meta", world.getBlockMetadata(x + dx, y, z + dz));
            row.setIntArray("position", new int[] { x + dx, y, z + dz });
            TileEntity tile = world.getTileEntity(x + dx, y, z + dz);
            if (tile != null) row.setTag("tile", HologramNativeCapture.capture(tile));
            cells.appendTag(row);
        }
        result.setTag("cells", cells);
        return result;
    }

    private static NBTTagList inventory(FakePlayer actor) {
        return actor.inventory.writeToNBT(new NBTTagList());
    }

    /** Independent expected accounting, derived from the original typed inventory rather than service counters. */
    static NBTTagList expectedPaidInventory(NBTTagList funded) throws IOException {
        NBTTagList expected = new NBTTagList();
        boolean stone = false, chest = false;
        for (int index = 0; index < funded.tagCount(); index++) {
            NBTTagCompound row = (NBTTagCompound) funded.getCompoundTagAt(index)
                .copy();
            ItemStack stack = ItemStack.loadItemStackFromNBT(row);
            boolean debit = false;
            if (stack != null && !stone && stack.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone)) {
                stone = debit = true;
            } else if (stack != null && !chest
                && stack.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.chest)) {
                    chest = debit = true;
                }
            if (debit) {
                if (stack.stackSize == 1) continue;
                row.setByte("Count", (byte) (stack.stackSize - 1));
            }
            expected.appendTag(row);
        }
        require(stone && chest, "Expected accounting requires both ordinary materials");
        return expected;
    }

    private static int count(FakePlayer actor, Block block) {
        int count = 0;
        for (ItemStack stack : actor.inventory.mainInventory)
            if (stack != null && stack.getItem() == net.minecraft.item.Item.getItemFromBlock(block))
                count += stack.stackSize;
        return count;
    }

    private static void supply(FakePlayer actor, ItemStack stack) throws IOException {
        for (int slot = 1; slot < actor.inventory.mainInventory.length; slot++)
            if (actor.inventory.mainInventory[slot] == null) {
                actor.inventory.setInventorySlotContents(slot, stack);
                actor.inventory.markDirty();
                return;
            }
        throw new IOException("No free lab inventory slot for ordinary fixture materials");
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
