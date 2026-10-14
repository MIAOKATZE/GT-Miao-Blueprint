package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import appeng.api.config.FuzzyMode;
import appeng.api.implementations.items.IStorageCell;
import appeng.api.storage.data.IAEStackType;
import appeng.util.item.AEFluidStackType;

/** Pure NBT policy regression; real registered AE2FC cells are exercised by the native sulfur fixture. */
public final class HologramAeFluidCellRegression {

    private static final String PREFIX = "com.glodblock.github.common.item.";

    private HologramAeFluidCellRegression() {}

    public static void run() {
        try {
            strictContentProjection();
            unknownSchemas();
            falseNativeAdapter();
        } catch (IOException failure) {
            throw new AssertionError("AE2FC strict fluid-cell projection failed", failure);
        }
    }

    private static void strictContentProjection() throws IOException {
        NBTTagCompound source = new NBTTagCompound();
        source.setShort("ft", (short) 2);
        source.setLong("fc", 64000L);
        NBTTagCompound water = new NBTTagCompound();
        water.setString("FluidName", "water");
        water.setLong("Cnt", 48000L);
        source.setTag("#0", water);
        source.setLong("@0", 48000L);
        NBTTagCompound acid = new NBTTagCompound();
        acid.setString("FluidName", "sulfuricacid");
        acid.setLong("Cnt", 16000L);
        source.setTag("#1", acid);
        source.setLong("@1", 16000L);
        // Native slot records are removed even when stale or absent from the saved type count.
        source.setTag("#62", (NBTTagCompound) water.copy());
        source.setLong("@62", 1L);
        source.setString("FuzzyMode", "IGNORE_ALL");
        source.setString("OreFilter", "cell-filter");
        source.setByte("cellRestrictionTypes", (byte) 5);
        source.setLong("cellRestrictionAmount", 32000L);
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", "paid finite fluid cell");
        source.setTag("display", display);
        NBTTagCompound partition = new NBTTagCompound();
        partition.setTag("#0", (NBTTagCompound) water.copy());
        source.setTag("list", partition);
        NBTTagCompound cards = new NBTTagCompound(), card = new NBTTagCompound();
        new ItemStack(Items.diamond).writeToNBT(card);
        cards.setTag("#0", card);
        source.setTag("upgrades", cards);
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("ownerUUID", "recipe-owner-must-survive");
        recipe.setInteger("x", 31);
        recipe.setInteger("y", 72);
        recipe.setInteger("z", -17);
        recipe.setShort("ft", (short) 9);
        recipe.setLong("fc", 123L);
        recipe.setTag("#0", (NBTTagCompound) acid.copy());
        recipe.setLong("@0", 123L);
        NBTTagList inputs = new NBTTagList();
        inputs.appendTag((NBTTagCompound) card.copy());
        recipe.setTag("in", inputs);
        source.setTag("futureRecipe", recipe);
        source.setString("futureField", "unreviewed-but-inert-metadata");
        source.setLong("it", 17L);
        source.setLong("ic", 19L);
        source.setString("#recipe", "not-a-native-storage-slot");
        NBTTagCompound before = (NBTTagCompound) source.copy();
        NBTTagCompound expected = (NBTTagCompound) source.copy();
        for (String key : new String[] { "ft", "fc", "#0", "@0", "#1", "@1", "#62", "@62" }) expected.removeTag(key);
        for (String type : new String[] { "ItemBasicFluidStorageCell", "ItemMultiFluidStorageCell",
            "ItemFluidExtremeStorageCell" }) {
            NBTTagCompound projected = HologramAeBlueprint.projectNativeFluidCellMetadata(PREFIX + type, source);
            SimpleAssert.eq(expected, projected, type + " removes exactly native root fluid contents/counts");
            SimpleAssert.eq(recipe, projected.getCompoundTag("futureRecipe"), "unknown recipe data survives intact");
            SimpleAssert.eq(
                expected,
                HologramAeBlueprint.projectNativeFluidCellMetadata(PREFIX + type, projected),
                "empty projection is idempotent");
            SimpleAssert.eq(before, source, "projection leaves the complete source snapshot unchanged");
            projected.getCompoundTag("futureRecipe")
                .setString("ownerUUID", "output-only-change");
            projected.getCompoundTag("list")
                .getCompoundTag("#0")
                .setString("FluidName", "output-only-change");
            projected.getCompoundTag("upgrades")
                .getCompoundTag("#0")
                .setInteger("Damage", 77);
            SimpleAssert.eq(before, source, "all retained nested NBT is detached from the source");
        }
        SimpleAssert.that(
            HologramAeBlueprint.projectNativeFluidCellMetadata(PREFIX + "ItemMultiFluidStorageCell", null)
                .hasNoTags(),
            "a native empty shell does not invent capacity, fluids, identity or recipe records");
    }

    private static void unknownSchemas() throws IOException {
        NBTTagCompound source = new NBTTagCompound();
        source.setLong("fc", 64000L);
        source.setString("diskuuid", "external-asset-must-not-be-interpreted");
        NBTTagCompound before = (NBTTagCompound) source.copy();
        for (String type : new String[] { PREFIX + "FCBaseItemCell", PREFIX + "ItemCreativeFluidStorageCell",
            PREFIX + "ItemFluidVoidStorageCell", PREFIX + "ItemInfinityWaterStorageCell",
            PREFIX + "ItemPortableFluidCell", PREFIX + "ItemMultiFluidStorageCell$UnknownSubclass",
            "other.mod.ItemMultiFluidStorageCell" }) {
            boolean rejected = false;
            try {
                HologramAeBlueprint.projectNativeFluidCellMetadata(type, source);
            } catch (IOException expected) {
                rejected = true;
            }
            SimpleAssert.that(rejected, "unreviewed cell cannot acquire the finite native schema: " + type);
            SimpleAssert.eq(before, source, "rejected cell preserves its complete source including unknown assets");
        }
    }

    private static void falseNativeAdapter() {
        PretendFluidCell impostor = new PretendFluidCell();
        ItemStack source = new ItemStack(impostor);
        NBTTagCompound original = new NBTTagCompound();
        original.setString("diskuuid", "external-pointer");
        source.setTagCompound(original);
        NBTTagCompound before = (NBTTagCompound) original.copy();
        SimpleAssert.that(
            !HologramAeBlueprint.nativeFluidCellSchema(source),
            "a third-party IStorageCell advertising FLUID is not a reviewed native adapter");
        SimpleAssert.eq(0, impostor.callbacks, "unknown cell API is never called to inspect external storage");
        SimpleAssert.eq(before, source.getTagCompound(), "false adapter detection does not edit the original stack");
        SimpleAssert.that(!HologramAeBlueprint.nativeFluidCellSchema(null), "no sample cannot become a native cell");
    }

    private static final class PretendFluidCell extends Item implements IStorageCell {

        int callbacks;

        @Override
        public IAEStackType<?> getStackType() {
            callbacks++;
            return AEFluidStackType.FLUID_STACK_TYPE;
        }

        @Override
        public boolean isEditable(ItemStack stack) {
            return true;
        }

        @Override
        public IInventory getUpgradesInventory(ItemStack stack) {
            throw new AssertionError("Unknown cell inventory must not be read");
        }

        @Override
        public FuzzyMode getFuzzyMode(ItemStack stack) {
            return FuzzyMode.IGNORE_ALL;
        }

        @Override
        public void setFuzzyMode(ItemStack stack, FuzzyMode mode) {
            throw new AssertionError("Unknown cell configuration must not be changed");
        }

        @Override
        public int getBytes(ItemStack stack) {
            return 65536;
        }

        @Override
        public int BytePerType(ItemStack stack) {
            return 512;
        }

        @Override
        public int getBytesPerType(ItemStack stack) {
            return 512;
        }

        @Override
        public int getTotalTypes(ItemStack stack) {
            return 5;
        }

        @Override
        public boolean storableInStorageCell() {
            return true;
        }

        @Override
        public boolean isStorageCell(ItemStack stack) {
            return true;
        }

        @Override
        public double getIdleDrain() {
            return 2.0;
        }
    }
}
