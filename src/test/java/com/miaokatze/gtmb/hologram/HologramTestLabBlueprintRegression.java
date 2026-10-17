package com.miaokatze.gtmb.hologram;

import java.io.IOException;
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
                HologramTestLabBlueprintProbe.run(null, null, Integer.MAX_VALUE, -1, Integer.MIN_VALUE);
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
        } catch (IOException failure) {
            throw new AssertionError("Ordinary lab fixture admission/accounting", failure);
        }
    }

    private static NBTTagCompound slot(ItemStack stack, int index) {
        NBTTagCompound row = stack.writeToNBT(new NBTTagCompound());
        row.setByte("Slot", (byte) index);
        return row;
    }
}
