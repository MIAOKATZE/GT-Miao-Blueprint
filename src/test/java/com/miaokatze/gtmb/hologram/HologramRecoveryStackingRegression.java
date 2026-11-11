// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.function.Consumer;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real item/tag equality and recovery insertion; world removal and native GT registration are separate checks. */
public final class HologramRecoveryStackingRegression {

    private HologramRecoveryStackingRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramRecoveryStackingRegression.class, args)) return;
        run();
        System.out.println("HologramRecoveryStackingRegression PASS");
    }

    static void run() {
        NBTTagCompound first = tile(12, 64, -8), second = tile(-311, 93, 42);
        NBTTagCompound before = (NBTTagCompound) first.copy();
        ItemStack empty = item(first), elsewhere = item(second);
        SimpleAssert.eq(before, first, "sealing never changes the live capture, including its original position");
        SimpleAssert.that(
            ItemStack.areItemStackTagsEqual(empty, elsewhere),
            "equivalent empty GT payloads at different source coordinates produce stackable items");
        ItemStack[] inventory = new ItemStack[3];
        inventory[1] = empty;
        HologramMaterials.insertRecovery(inventory, 0, 64, elsewhere);
        SimpleAssert.eq(2, inventory[1].stackSize, "normal recovery merges equivalent sealed items");
        SimpleAssert.that(elsewhere.stackSize == 0 && inventory[2] == null, "merging needs no extra inventory slot");

        NBTTagCompound loaded = (NBTTagCompound) first.copy();
        NBTTagCompound cargo = new ItemStack(Items.diamond, 1).writeToNBT(new NBTTagCompound());
        cargo.setInteger("IntSlot", 3);
        cargo.setInteger("Count", 512);
        NBTTagCompound cargoTag = new NBTTagCompound();
        cargoTag.setString("label", "exact cargo NBT");
        cargo.setTag("tag", cargoTag);
        loaded.getTagList("Inventory", 10)
            .appendTag(cargo);
        NBTTagCompound fluid = new NBTTagCompound();
        fluid.setString("FluidName", "water");
        fluid.setInteger("Amount", 32000);
        loaded.setTag("mFluid", fluid);
        NBTTagCompound cover = new NBTTagCompound();
        cover.setInteger("coverID", 120);
        cover.setInteger("coverData", 987);
        loaded.setTag("cover", cover);
        NBTTagCompound extension = new NBTTagCompound();
        extension.setString("mode", "future configuration");
        extension.setInteger("x", 73);
        loaded.setTag("extension", extension);
        NBTTagCompound loadedElsewhere = (NBTTagCompound) loaded.copy();
        loadedElsewhere.setInteger("x", -900);
        loadedElsewhere.setInteger("y", 1);
        loadedElsewhere.setInteger("z", 700);
        SimpleAssert.that(
            ItemStack.areItemStackTagsEqual(item(loaded), item(loadedElsewhere)),
            "identical contents and configuration remain stackable after relocation");
        SimpleAssert.that(
            !ItemStack.areItemStackTagsEqual(empty, item(loaded)),
            "an empty machine cannot merge with a machine carrying real contents");
        distinct(
            loaded,
            tag -> tag.getTagList("Inventory", 10)
                .getCompoundTagAt(0)
                .setInteger("Count", 511),
            "inventory quantity");
        distinct(
            loaded,
            tag -> tag.getTagList("Inventory", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("tag")
                .setString("label", "different cargo NBT"),
            "inventory item NBT");
        distinct(
            loaded,
            tag -> tag.getCompoundTag("mFluid")
                .setInteger("Amount", 1),
            "fluid amount");
        distinct(
            loaded,
            tag -> tag.getCompoundTag("mFluid")
                .setString("FluidName", "lava"),
            "fluid identity");
        distinct(
            loaded,
            tag -> tag.getCompoundTag("cover")
                .setInteger("coverData", 988),
            "cover state");
        distinct(loaded, tag -> tag.setShort("mFacing", (short) 5), "machine facing");
        distinct(loaded, tag -> tag.setString("mOwnerUuid", "another owner"), "machine ownership");
        distinct(loaded, tag -> tag.setBoolean("disableFilter", false), "machine configuration");
        distinct(loaded, tag -> tag.setLong("mStoredEnergy", 1), "stored energy");
        distinct(
            loaded,
            tag -> tag.getCompoundTag("extension")
                .setInteger("x", 74),
            "nested coordinates belonging to unknown configuration");
        distinct(
            loaded,
            tag -> tag.getCompoundTag("extension")
                .setString("mode", "another mode"),
            "unknown extension data");

        legacy(loaded);
    }

    private static void distinct(NBTTagCompound original, Consumer<NBTTagCompound> change, String state) {
        NBTTagCompound changed = (NBTTagCompound) original.copy();
        change.accept(changed);
        ItemStack first = item(original), second = item(changed);
        SimpleAssert.that(!ItemStack.areItemStackTagsEqual(first, second), "different " + state + " never merges");
        ItemStack[] inventory = new ItemStack[2];
        inventory[1] = first;
        HologramMaterials.insertRecovery(inventory, 0, 64, second);
        SimpleAssert.that(
            first.stackSize == 1 && second.stackSize == 1,
            "full recovery inventory retains the distinct " + state + " item");
    }

    private static void legacy(NBTTagCompound original) {
        NBTTagCompound seal = seal(original);
        // The existing v1 schema stored the actual source coordinates. Do not rewrite old inventory items.
        seal.setTag("tile", original.copy());
        NBTTagCompound before = (NBTTagCompound) seal.copy();
        SimpleAssert.that(
            HologramRecovery.matchesEnvelope(seal, "gregtech:machine", "gregtech:machine", 25),
            "pre-fix v1 machine items remain readable");
        NBTTagCompound relocated = HologramRecovery.relocateNBT(seal, -1, 80, 2);
        NBTTagCompound expected = (NBTTagCompound) original.copy();
        expected.setInteger("x", -1);
        expected.setInteger("y", 80);
        expected.setInteger("z", 2);
        SimpleAssert.eq(expected, relocated, "old v1 payloads still restore every state field at the destination");
        SimpleAssert.eq(before, seal, "reading or relocating an old item never mutates its exact payment identity");
        SimpleAssert.that(
            !HologramRecovery.matchesEnvelope(seal, "gregtech:machine", "gregtech:machine", 26),
            "old items retain the machine identity guard");
        NBTTagCompound broken = (NBTTagCompound) seal.copy();
        broken.getCompoundTag("tile")
            .removeTag("Inventory");
        SimpleAssert.that(
            !HologramRecovery.matchesEnvelope(broken, "gregtech:machine", "gregtech:machine", 25),
            "a damaged old item is never interpreted as an empty machine");
        ItemStack canonical = item(original);
        ItemStack old = canonical.copy();
        old.getTagCompound()
            .setTag(HologramRecovery.TAG, seal);
        SimpleAssert.that(
            !ItemStack.areItemStackTagsEqual(canonical, old),
            "old coordinate-bearing items keep their existing exact identity until normally placed and recovered");
        NBTTagCompound receipt = HologramMaterials.itemReceipt(old);
        SimpleAssert.that(
            ItemStack.areItemStackTagsEqual(old, HologramMaterials.recoveryItem(receipt)),
            "the recovery ledger preserves old items without hidden migration");
    }

    private static NBTTagCompound tile(int x, int y, int z) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "GT_TileEntityMetaID_Machine");
        tag.setInteger("mID", 25);
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setShort("mFacing", (short) 2);
        tag.setString("mOwnerUuid", "same owner");
        tag.setLong("mStoredEnergy", 0L);
        tag.setBoolean("disableFilter", true);
        tag.setTag("Inventory", new NBTTagList());
        return tag;
    }

    private static NBTTagCompound seal(NBTTagCompound tile) {
        return HologramRecovery.sealNBT(tile, "gregtech:machine", 0, "gregtech:machine", 25, 25);
    }

    private static ItemStack item(NBTTagCompound tile) {
        // A registered vanilla surrogate exercises Minecraft's actual ItemStack/NBT and inventory semantics.
        ItemStack stack = new ItemStack(Items.apple);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(HologramRecovery.TAG, seal(tile));
        stack.setTagCompound(tag);
        return stack;
    }
}
