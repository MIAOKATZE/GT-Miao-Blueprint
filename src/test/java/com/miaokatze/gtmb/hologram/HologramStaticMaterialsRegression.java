package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises detached exact material demand and the privacy boundary of public appearance summaries. */
final class HologramStaticMaterialsRegression {

    private HologramStaticMaterialsRegression() {}

    static void run() {
        try {
            HologramStaticMaterials.Builder builder = new HologramStaticMaterials.Builder();
            NBTTagCompound tile = new NBTTagCompound();
            tile.setString("id", "Chest");
            tile.setLong("mStoredEnergy", 1234);
            NBTTagList inventory = new NBTTagList();
            for (String variant : new String[] { "private-owner-a", "private-owner-b" }) {
                ItemStack item = new ItemStack(Items.diamond, 3);
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("owner", variant);
                item.setTagCompound(tag);
                inventory.appendTag(item.writeToNBT(new NBTTagCompound()));
            }
            tile.setTag("Inventory", inventory);
            NBTTagCompound original = (NBTTagCompound) tile.copy();
            builder.add(Blocks.chest, 0, new ItemStack(Blocks.chest), tile, null);
            builder.add(Blocks.chest, 0, new ItemStack(Blocks.chest), tile, null);
            SimpleAssert.eq(original, tile, "material inspection never edits the retained source payload");
            NBTTagCompound local = builder.snapshot();
            SimpleAssert
                .that(local.getBoolean("complete"), "all understood static schemas produce a complete material bill");
            SimpleAssert.that(
                local.getBoolean("cacheReviewRequired"),
                "buffer restoration costs require final configured payment review");
            NBTTagList rows = local.getTagList("rows", 10);
            SimpleAssert.eq(3, rows.tagCount(), "shell and two exact inventory variants are independently aggregated");
            int exactVariants = 0;
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound row = rows.getCompoundTagAt(i);
                SimpleAssert.that(!row.getBoolean("availableKnown"), "detached demand cannot claim observed inventory");
                if (row.getCompoundTag("item")
                    .hasKey("tag", 10)) {
                    exactVariants++;
                    SimpleAssert.eq("6", row.getString("requiredExact"), "same exact variant sums across source cells");
                }
            }
            SimpleAssert.eq(2, exactVariants, "same-name inventory variants must not merge by display name");
            builder.unaccounted("private-owner-secret-frequency-42");
            NBTTagCompound publicSummary = builder.publicSnapshot();
            SimpleAssert.that(
                !publicSummary.getBoolean("complete"),
                "hidden exact tags make the public bill explicitly partial");
            SimpleAssert.eq(
                1,
                publicSummary.getTagList("rows", 10)
                    .tagCount(),
                "public summary exposes only the untagged shell demand");
            SimpleAssert.that(
                !publicSummary.toString()
                    .contains("private-owner"),
                "public summary hides native exception values and all private exact inventory tags");
            SimpleAssert.eq(
                3,
                builder.snapshot()
                    .getTagList("rows", 10)
                    .tagCount(),
                "public redaction cannot mutate the owned local exact bill");
        } catch (Exception failure) {
            throw new AssertionError("Detached material estimate regression", failure);
        }
    }
}
