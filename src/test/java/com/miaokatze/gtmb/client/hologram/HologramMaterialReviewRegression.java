package com.miaokatze.gtmb.client.hologram;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the actual client quote consumer with wire identities and counts that broke the old material list. */
public final class HologramMaterialReviewRegression {

    private HologramMaterialReviewRegression() {}

    public static void run() {
        NBTTagCompound state = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        ItemStack tagged = new ItemStack(Items.diamond, 1, 7);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("displayVariant", "first");
        tagged.setTagCompound(tag);
        NBTTagCompound first = HologramMaterialQuote.itemRow(tagged, 4294967301L, 2147483649L);
        // Deliberately contradict the process-local native ID. The stable registry identity must win.
        first.getCompoundTag("item")
            .setShort("id", (short) net.minecraft.item.Item.getIdFromItem(Items.stick));
        rows.appendTag(first);
        tagged.getTagCompound()
            .setString("displayVariant", "second");
        rows.appendTag(HologramMaterialQuote.itemRow(tagged, 2, 1));
        NBTTagCompound unknown = (NBTTagCompound) first.copy();
        unknown.setString("itemRegistry", "gtmb_regression:missing_registry_item");
        rows.appendTag(unknown);
        NBTTagCompound energy = new NBTTagCompound();
        energy.setString("kind", "energy");
        energy.setLong("required", Long.MAX_VALUE);
        energy.setString("requiredExact", "123456789012345678901234567890");
        rows.appendTag(energy);
        NBTTagCompound quote = new NBTTagCompound();
        quote.setBoolean("complete", true);
        quote.setTag("rows", rows);
        state.setTag("materialQuote", quote);

        HologramMaterialReview model = new HologramMaterialReview();
        SimpleAssert.that(model.update(state) && model.ready() && model.size() == 4, "consumer retains exact rows");
        HologramMaterialReview.Row a = model.row(0), b = model.row(1);
        SimpleAssert.that(
            a.item.getItem() == Items.diamond && a.item.getItemDamage() == 7,
            "registry and damage determine the visible icon, ignoring an unrelated numeric id");
        SimpleAssert.that(
            "4294967301".equals(a.required) && "2147483649".equals(a.available) && "2147483652".equals(a.missing),
            "all visible material counts preserve values above signed int");
        SimpleAssert.that(
            "first".equals(
                a.item.getTagCompound()
                    .getString("displayVariant"))
                && "second".equals(
                    b.item.getTagCompound()
                        .getString("displayVariant"))
                && a.index != b.index,
            "same-name items with different complete NBT stay independently inspectable");
        a.item.getTagCompound()
            .setString("displayVariant", "client-only");
        SimpleAssert.that(
            "first".equals(
                first.getCompoundTag("item")
                    .getCompoundTag("tag")
                    .getString("displayVariant")),
            "render samples are detached from the authoritative quote");
        SimpleAssert
            .that(model.row(2).item == null, "unavailable registry never falls back to a misleading numeric icon");
        SimpleAssert.that(
            "123456789012345678901234567890".equals(model.row(3).required),
            "energy display uses exact decimal quantities beyond long");
        SimpleAssert.that(
            !model.update(state) && model.row(0) == a,
            "an unchanged snapshot reuses decoded samples across rendered frames");
        quote.setLong("planRevision", 3);
        state.setLong("planRevision", 4);
        model.update(state);
        SimpleAssert.that(!model.ready(), "stale material quote cannot enable the new transaction button");
    }

    public static void main(String[] args) {
        run();
    }
}
