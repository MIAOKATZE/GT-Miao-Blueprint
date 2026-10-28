package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Detached configuration adapters must preserve exact source records and never widen native declarations. */
public final class HologramLocalAuthorConfigurationsRegression {

    private HologramLocalAuthorConfigurationsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLocalAuthorConfigurationsRegression.class, args)) return;
        run();
        System.out.println("Local author configuration regression passed");
    }

    public static void run() throws Exception {
        JsonObject cell = new JsonObject();
        cell.addProperty("block", "minecraft:stone");
        cell.addProperty("item", "minecraft:stone");
        cell.addProperty("defaultItem", "minecraft:stone");
        cell.addProperty("metadata", 0);
        cell.addProperty("damage", 0);
        cell.addProperty("facing", -1);
        cell.addProperty("futureVendor", "unchanged");
        JsonArray allowed = new JsonArray();
        JsonObject declaration = new JsonObject();
        declaration.addProperty("item", "minecraft:stone");
        declaration.addProperty("damage", 0);
        allowed.add(declaration);
        cell.add("allowedTargets", allowed);
        String original = cell.toString();
        SimpleAssert.eq(
            1,
            HologramLocalAuthorConfigurations.candidates(cell)
                .size(),
            "native candidates use cell declaration");
        JsonObject pinned = HologramLocalAuthorConfigurations.target(cell, new ItemStack(Blocks.stone));
        SimpleAssert.eq(original, pinned.toString(), "same native target preserves exact source shape");
        boolean refused = false;
        try {
            HologramLocalAuthorConfigurations.target(cell, new ItemStack(Blocks.dirt));
        } catch (IOException expected) {
            refused = true;
        }
        SimpleAssert.that(refused, "unrelated block cannot widen the declared target set");
        SimpleAssert.eq(original, cell.toString(), "rejected target leaves original source intact");
        cell.addProperty("controller", true);
        refused = false;
        try {
            HologramLocalAuthorConfigurations.restore(cell);
        } catch (IOException expected) {
            refused = true;
        }
        SimpleAssert.that(refused, "controller cannot restore shell");
        cell.remove("controller");
        SimpleAssert.eq(
            0,
            HologramLocalAuthorConfigurations.legalFacings(cell).length,
            "ordinary source does not fabricate legal GT facings");

        JsonObject header = new JsonObject();
        header.addProperty("schema", 2);
        header.addProperty("deidentificationVersion", 1);
        header.addProperty("deidentificationLevel", 4);
        JsonArray cells = new JsonArray();
        cells.add(cell);
        header.add("cells", cells);
        header.addProperty("futureHeader", "keep");
        JsonObject deidentified = HologramLocalAuthorConfigurations.deidentify(header, 0);
        SimpleAssert.eq(
            4,
            deidentified.get("deidentificationLevel")
                .getAsInt(),
            "minimum deidentification cannot downgrade");
        SimpleAssert.eq(
            "keep",
            deidentified.get("futureHeader")
                .getAsString(),
            "unknown header retained");
        SimpleAssert.eq(
            "unchanged",
            deidentified.getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject()
                .get("futureVendor")
                .getAsString(),
            "unknown cell members retained");
        NBTTagCompound descriptors = HologramLocalAuthorConfigurations.networks(header);
        SimpleAssert.that(
            !descriptors.getBoolean("nativeAuthority") && !descriptors.getBoolean("terminalSecurityChecked"),
            "local network descriptors never certify world ownership or terminal security");
        SimpleAssert.eq(
            0,
            descriptors.getTagList("inventory", 10)
                .tagCount(),
            "no fabricated real inventory catalogue");
        refused = false;
        try {
            HologramLocalAuthorConfigurations.excludeBlockers(header, false);
        } catch (IOException expected) {
            refused = true;
        }
        SimpleAssert.that(refused, "blocker exclusion requires deliberate discard confirmation");
        refused = false;
        try {
            HologramLocalAuthorAe.preview(cell, 9);
        } catch (IOException expected) {
            refused = true;
        }
        SimpleAssert.that(refused, "ordinary block cannot pretend to have an AE native slot editor");
    }
}
