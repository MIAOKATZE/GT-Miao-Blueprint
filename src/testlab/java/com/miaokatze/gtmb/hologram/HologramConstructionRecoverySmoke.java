// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.mojang.authlib.GameProfile;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;

/** Save-owned testlab fixture. Stage/resume may be separated by a normal server process restart. */
public final class HologramConstructionRecoverySmoke {

    private HologramConstructionRecoverySmoke() {}

    public static NBTTagCompound run(WorldServer world, EntityPlayerMP activeActor, int x, int y, int z, boolean resume)
        throws Exception {
        require(
            com.miaokatze.gtmb.testlab.TestLab.isOwnedFixture(world, activeActor, x, y, z, 2, 1, 1),
            "Recovery probe requires active owned testlab cells");
        for (Object user : world.playerEntities) require(
            user instanceof net.minecraftforge.common.util.FakePlayer,
            "Human presence forbids recovery fault injection");
        UUID identity = UUID.nameUUIDFromBytes(
            ("gtmb-construction-recovery-probe:" + world.getSaveHandler()
                .getWorldDirectory()
                .getCanonicalPath()).getBytes(StandardCharsets.UTF_8));
        EntityPlayerMP actor = FakePlayerFactory.get(world, new GameProfile(identity, "GTMBRecoveryProbe"));
        actor.worldObj = world;
        actor.dimension = world.provider.dimensionId;
        HologramConstructionJournal.reload(actor);
        NBTTagCompound result = new NBTTagCompound();
        result.setString("actor", identity.toString());
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        if (!resume) {
            require(
                !HologramConstructionJournal.pending(actor),
                "Previous recovery probe is unresolved; resume it first");
            require(
                world.isAirBlock(x, y, z) && world.isAirBlock(x + 1, y, z),
                "Recovery fixture requires empty owned cells");
            java.util.Arrays.fill(actor.inventory.mainInventory, null);
            actor.inventory.mainInventory[1] = new ItemStack(Items.apple, 5);
            HologramSession session = new HologramSession(actor, x, y, z, false, 2);
            HologramMaterials.Context context = new HologramMaterials.Context(actor, settings, -1);
            NBTTagCompound journal = HologramConstructionJournal.beginAction(actor, x, y, z, Blocks.air, 0, null, -1);
            HologramMaterials.Transaction transaction = context.begin()
                .trackLocalInventory()
                .journal(journal);
            require(transaction.takeOne(new ItemStack(Items.apple, 1), false), "Actual main-inventory payment failed");
            transaction.beforeWorldMutation();
            require(world.setBlock(x, y, z, Blocks.dirt, 0, 3), "Actual target placement failed");
            // The retired restoreCell lambda seam did not exercise native world restoration. This fixture now
            // stages the production sparse recovery journal after actual payment and an actual world write.
            HologramConstructionJournal.suspend(
                session,
                transaction,
                new IllegalStateException("Injected stop after world mutation for durable sparse recovery"));
            require(
                session.job == 4 && session.stopTick && journal.getBoolean("sparseLocal"),
                "Production suspension did not retain the sparse action");
            require(
                actor.inventory.mainInventory[1].stackSize == 4 && world.getBlock(x, y, z) == Blocks.dirt,
                "Uncertain world must retain both the live block and its existing deduction");
            rejects(
                () -> HologramConstructionJournal.beginAction(actor, x + 1, y, z, Blocks.air, 0, null, -1),
                "A pending receipt admitted a second action");
            result.setString("receipt", journal.getString("id"));
            result.setString("state", "STAGED_RESTART_REQUIRED");
            return result;
        }
        require(HologramConstructionJournal.pending(actor), "No durable recovery probe to resume");
        NBTTagCompound journal = HologramConstructionJournal.owner(actor)
            .getCompoundTag(HologramConstructionJournal.KEY);
        require(
            journal.getInteger("x") == x && journal.getInteger("y") == y
                && journal.getInteger("z") == z
                && world.getBlock(x, y, z) == Blocks.dirt,
            "Restarted fixture world/evidence differs");
        // This generated offline FakePlayer has no real-player inventory. Reconstitute only its owned test stock.
        if (journal.getBoolean("sparseLocal")) {
            net.minecraft.nbt.NBTTagList slots = journal.getTagList("localSlots", 10);
            require(slots.tagCount() == 1, "Sparse fixture must retain only its actually deducted slot");
            NBTTagCompound slot = slots.getCompoundTagAt(0);
            require(slot.getInteger("slot") == 1 && slot.hasKey("after", 10), "Sparse fixture payment slot differs");
            ItemStack paid = ItemStack.loadItemStackFromNBT(slot.getCompoundTag("after"));
            require(
                paid != null && paid.getItem() == Items.apple && paid.stackSize == 4,
                "Sparse fixture native paid stock differs");
            actor.inventory.mainInventory[1] = paid;
            actor.inventory.mainInventory[2] = new ItemStack(Items.carrot, 11);
        } else {
            // A previously staged legacy fixture remains resumable through the production old-receipt reader.
            ItemStack[] held = HologramConstructionJournal.inventory(journal.getTagList("heldInventory", 10));
            require(held.length == actor.inventory.mainInventory.length, "Frozen probe inventory length differs");
            System.arraycopy(held, 0, actor.inventory.mainInventory, 0, held.length);
        }
        boolean sparse = journal.getBoolean("sparseLocal");
        HologramConstructionJournal.resolve(actor, journal.getString("id"), "rollback");
        require(
            world.isAirBlock(x, y, z) && actor.inventory.mainInventory[1].stackSize == 5,
            "Restarted rollback did not restore exact original world/payment");
        require(
            !sparse || actor.inventory.mainInventory[2] != null
                && actor.inventory.mainInventory[2].getItem() == Items.carrot
                && actor.inventory.mainInventory[2].stackSize == 11,
            "Sparse restart rollback overwrote unrelated fixture inventory");

        require(world.isAirBlock(x + 1, y, z), "External fixture cell is occupied");
        world.setBlock(x + 1, y, z, Blocks.chest, 0, 3);
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(x + 1, y, z);
        chest.setInventorySlotContents(0, new ItemStack(Items.apple, 5));
        HologramSession session = new HologramSession(actor, x, y, z, false, 2);
        HologramMaterials.Context context = new HologramMaterials.Context(actor, settings, -1);
        journal = HologramConstructionJournal.beginAction(actor, x, y, z, Blocks.air, 0, null, -1);
        HologramMaterials.Transaction transaction = context.begin()
            .trackLocalInventory()
            .journal(journal);
        IAEItemStack request = AEItemStack.create(new ItemStack(Items.apple, 4));
        try {
            transaction.extract("item", request, () -> {
                chest.decrStackSize(0, 2);
                chest.markDirty();
                throw new IllegalStateException("Native chest-backed provider deducted two then threw");
            });
            throw new IllegalStateException("Expected native provider failure");
        } catch (IllegalStateException expected) {
            require(transaction.uncertain(), "Unknown extraction lost its write-ahead evidence");
        }
        rejects(transaction::rollbackAction, "Unknown native extraction was allowed to auto-refund");
        HologramConstructionJournal.suspend(
            session,
            transaction,
            new IllegalStateException("Native provider result requires actual-amount adjudication"));
        require(
            chest.getStackInSlot(0).stackSize == 3 && actor.inventory.mainInventory[1].stackSize == 5,
            "Unknown extraction was blindly refunded or replayed");
        HologramConstructionJournal.reload(actor);
        journal = HologramConstructionJournal.owner(actor)
            .getCompoundTag(HologramConstructionJournal.KEY);
        HologramMaterials.Transaction.adjudicateExtraction(journal, 2);
        HologramConstructionJournal.persist(actor, journal);
        HologramConstructionJournal.resolve(actor, journal.getString("id"), "rollback");
        int apples = 0;
        for (ItemStack stack : actor.inventory.mainInventory)
            if (stack != null && stack.getItem() == Items.apple) apples += stack.stackSize;
        require(
            apples == 7 && chest.getStackInSlot(0).stackSize == 3,
            "Adjudicated resources do not conserve actual stock");
        require(!HologramConstructionJournal.pending(actor), "Settled journal still permits replay");
        world.removeTileEntity(x + 1, y, z);
        world.setBlockToAir(x + 1, y, z);
        java.util.Arrays.fill(actor.inventory.mainInventory, null);
        result.setString("state", "PASS");
        result.setInteger("conservedApples", apples + 3);
        result.setString(
            "coverage",
            "actual world/main inventory; sparse durable suspend/restart/resolve; unrelated slot preserved; native chest-backed extraction fault; adjudication; no writer restoration-catch or live AE grid claim");
        return result;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new IllegalStateException(message);
    }
}
