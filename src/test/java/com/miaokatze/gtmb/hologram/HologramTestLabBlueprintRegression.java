package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testlab.TestLab;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Headless admission/accounting checks only; real FakePlayer world transactions run in the enabled lab. */
public final class HologramTestLabBlueprintRegression {

    private HologramTestLabBlueprintRegression() {}

    public static void run() {
        try {
            NBTTagCompound before = TestLab.status();
            SimpleAssert.that(!TestLab.isEnabled(), "Regression must run with lab opt-in disabled");
            try {
                HologramTestLabBlueprintProbe.start(null, null, Integer.MAX_VALUE, -1, Integer.MIN_VALUE);
                throw new AssertionError("Disabled bridge accepted a fixture");
            } catch (IOException refused) {
                SimpleAssert
                    .that(before.equals(TestLab.status()), "Rejected probe cannot load/register a lab or actor");
            }
            UUID owner = UUID.fromString("0f90ef78-6d48-4efc-9b7e-8f27dbb94725");
            HologramPresets.Preset preset = HologramTestLabBlueprintProbe.preset(owner, "OrdinaryLabUnit");
            SimpleAssert.eq(2, preset.schema, "Fixture uses ordinary schema2 validation");
            SimpleAssert
                .eq(owner.toString(), preset.authorUuid, "Fixture attributes its actual supplied actor identity");
            SimpleAssert.eq(2, preset.cells.size(), "Minimal ordinary fixture has two cells");
            SimpleAssert.that(
                preset.cells.get(0)
                    .stack()
                    .isItemEqual(new ItemStack(Blocks.stone)),
                "Stone shell is payable");
            SimpleAssert.that(
                preset.cells.get(1)
                    .stack()
                    .isItemEqual(new ItemStack(Blocks.chest)),
                "Chest shell is payable");
            for (HologramPresets.Cell cell : preset.cells) {
                SimpleAssert.that(
                    cell.tileNbt == null || cell.tileNbt.isEmpty(),
                    "Ordinary fixture carries no free tile inventory");
                SimpleAssert.that(
                    cell.itemNbt == null || cell.itemNbt.isEmpty(),
                    "Ordinary fixture carries no sealed cut item");
            }
            NBTTagList inventory = new NBTTagList();
            NBTTagCompound unrelated = new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound());
            unrelated.setByte("Slot", (byte) 1);
            unrelated.setString("auditOpaque", "keep exact typed fields");
            inventory.appendTag(unrelated);
            inventory.appendTag(slot(new ItemStack(Blocks.stone, 4), 2));
            inventory.appendTag(slot(new ItemStack(Blocks.chest, 3), 3));
            NBTTagList original = (NBTTagList) inventory.copy();
            NBTTagList paid = HologramTestLabBlueprintProbe.expectedPaidInventory(inventory);
            SimpleAssert.that(original.equals(inventory), "Expected accounting cannot mutate the original observation");
            SimpleAssert
                .that(unrelated.equals(paid.getCompoundTagAt(0)), "Unrelated inventory typed fields remain exact");
            SimpleAssert.eq(
                3,
                (int) paid.getCompoundTagAt(1)
                    .getByte("Count"),
                "Exactly one stone is debited");
            SimpleAssert.eq(
                2,
                (int) paid.getCompoundTagAt(2)
                    .getByte("Count"),
                "Exactly one chest is debited");
            paid.getCompoundTagAt(0)
                .setString("auditOpaque", "changed copy");
            SimpleAssert.that(original.equals(inventory), "Returned accounting is detached");
            NBTTagList last = new NBTTagList();
            last.appendTag(slot(new ItemStack(Blocks.stone), 2));
            last.appendTag(slot(new ItemStack(Blocks.chest), 3));
            SimpleAssert.eq(
                0,
                HologramTestLabBlueprintProbe.expectedPaidInventory(last)
                    .tagCount(),
                "Last materials leave empty slots");
            try {
                HologramTestLabBlueprintProbe.expectedPaidInventory(new NBTTagList());
                throw new AssertionError("Unfunded expected accounting was accepted");
            } catch (IOException expected) {}
            realReviewPrerequisitesAdmit();
        } catch (IOException | ReflectiveOperationException failure) {
            throw new AssertionError("Ordinary lab fixture admission/accounting", failure);
        }
    }

    private static void realReviewPrerequisitesAdmit() throws ReflectiveOperationException, IOException {
        Method fixture = HologramBuildContinuationRegression.class.getDeclaredMethod("fixture", int.class);
        fixture.setAccessible(true);
        HologramSession seed = (HologramSession) fixture.invoke(null, 0);
        net.minecraft.entity.player.EntityPlayerMP actor = seed.player;
        HologramSession previous = HologramWorkspace.find(actor.getUniqueID());
        actor.inventory.mainInventory[1] = null;
        HologramSession missing = null, paid = null;
        try {
            missing = HologramTestLabBlueprintProbe.fixture(actor, 0, 64, 0);
            SimpleAssert.that(
                !missing.virtualLocked && !missing.placementReviewIssued,
                "Lab starts without an invented position lock or material review capability");
            HologramTestLabBlueprintProbe.Review missingReview = new HologramTestLabBlueprintProbe.Review(missing);
            finishReview(missingReview);
            SimpleAssert.that(
                missing.virtualLocked && missing.placementReviewIssued
                    && missing.blueprintAwaitingConfirmation
                    && missing.planMissing == 2,
                "Real lock and quote completion issue a missing-material review");
            NBTTagCompound denied = missingReview.action();
            HologramBuildPreparation.reviewedBuild(missing, denied);
            HologramBuildContinuationRegression.drain(missing);
            SimpleAssert.that(
                missing.job != 1 && missing.planMissing == 2,
                "Lab missing-material action is refused after the real review prerequisites passed");
            HologramBuildPreparation.reviewedBuild(missing, (NBTTagCompound) denied.copy());
            HologramBuildContinuationRegression.drain(missing);
            SimpleAssert.that(missing.job != 1, "Repeated missing-material review does not admit a job");

            actor.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 4);
            actor.inventory.mainInventory[2] = new ItemStack(Blocks.chest, 3);
            paid = HologramTestLabBlueprintProbe.fixture(actor, 0, 64, 0);
            HologramTestLabBlueprintProbe.Review initial = new HologramTestLabBlueprintProbe.Review(paid);
            finishReview(initial);
            NBTTagCompound stale = initial.action();
            NBTTagList inventory = actor.inventory.writeToNBT(new NBTTagList());
            NBTTagCompound move = new NBTTagCompound();
            move.setIntArray("indices", new int[] { 0 });
            move.setInteger("dx", 1);
            HologramBlueprintEditor.moveBlueprintCells(paid, move);
            HologramBlueprintEditor.edit(paid, new NBTTagCompound(), "editUndo");
            HologramBuildPreparation.reviewedBuild(paid, stale);
            SimpleAssert.that(
                paid.buildTask == null && paid.job != 1,
                "Real move/undo invalidates the formerly issued Lab review");
            HologramTestLabBlueprintProbe.Review current = new HologramTestLabBlueprintProbe.Review(paid);
            finishReview(current);
            NBTTagCompound action = current.action();
            SimpleAssert.that(
                action.getLong("placementReviewNonce") > stale.getLong("placementReviewNonce")
                    && action.getLong("planRevision") == paid.planRevision
                    && paid.planMissing == 0,
                "Post-edit review uses the fresh complete quote, plan revision and nonce");
            HologramMaterialWorkflow.validateConfirmation(paid, action);
            HologramBuildPreparation.reviewedBuild(paid, action);
            HologramBuildContinuationRegression.drain(paid);
            SimpleAssert.that(
                paid.job == 1 && paid.work.size() == 2 && !paid.blueprintAwaitingConfirmation,
                "Lab review entry reaches the real final BuildTask confirmation and admits both payable cells");
            SimpleAssert.that(
                inventory.equals(actor.inventory.writeToNBT(new NBTTagList())),
                "Lock/review/admission cannot debit stock or write this read-only world");
            SimpleAssert.that(
                HologramWorkspace.find(actor.getUniqueID()) == previous,
                "Scoped Lab review restores the previous workspace slot after every continuation");
        } finally {
            for (HologramSession session : new HologramSession[] { missing, paid }) if (session != null) {
                HologramBuildPreparation.cancel(session);
                HologramDeferredPlanRefresh.cancel(session);
                HologramSnapshotPreparation.cancel(session);
            }
            actor.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static void finishReview(HologramTestLabBlueprintProbe.Review review) throws IOException {
        for (int step = 0; step < 10000; step++) if (review.advance()) return;
        throw new AssertionError("Real Lab position/material review did not finish its bounded continuations");
    }

    private static NBTTagCompound slot(ItemStack stack, int index) {
        NBTTagCompound row = stack.writeToNBT(new NBTTagCompound());
        row.setByte("Slot", (byte) index);
        return row;
    }
}
