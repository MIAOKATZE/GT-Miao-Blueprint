// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Headless coverage of the projection and the explicit client item/stock adapter, not a placement witness. */
final class HologramLibraryMaterialForecastRegression {

    private HologramLibraryMaterialForecastRegression() {}

    static void run() {
        unmasked();
        masked();
    }

    private static void unmasked() {
        int limit = HologramSavedMaterialSource.MAX_TRACKED_DESCRIPTORS;
        var tally = new HologramSavedMaterialSource.Tally(blueprint(), limit + 1);
        tally.include(0, row("stone", 0));
        tally.include(0, null);
        tally.include(0, null);
        tally.include(1, row("stonebrick", 1));
        JsonObject alias = row("stone", 0);
        alias.addProperty("item", "minecraft:cobblestone");
        tally.include(2, alias);
        JsonObject directed = row("stone", 0);
        directed.addProperty("facing", 3);
        tally.include(3, directed);
        JsonObject controller = row("stone", 0);
        controller.addProperty("controller", true);
        tally.include(4, controller);
        tally.include(5, row("log", 0));
        tally.include(6, row("quartz_block", 2));
        JsonObject payload = row("stone", 0);
        payload.addProperty("itemNbt", "synthetic ignored payload");
        tally.include(7, payload);
        JsonObject unsupported = row("stone", 0);
        unsupported.addProperty("opaque extension", true);
        tally.include(8, unsupported);
        tally.include(9, row("air", 0));
        tally.include(10, row("stonebrick", 0));
        tally.include(11, row("planks", 2));
        JsonObject disagreement = row("stonebrick", 2);
        disagreement.addProperty("metadata", 1);
        tally.include(12, disagreement);
        JsonObject provider = row("stone", 0);
        provider.addProperty("block", "detached:provider");
        provider.addProperty("item", "detached:provider");
        tally.include(13, provider);
        tally.include(14, row("glass", 0));
        tally.include(limit, null);

        var forecast = HologramLibraryMaterialForecast.project(tally.snapshot());
        SimpleAssert.that(
            forecast.candidates().size() == 5 && forecast.candidates().get(0).cells() == 3
                && forecast.unpriced().nonOrdinary() == 7 && forecast.unpriced().cells() == 10,
            "only equal canonical vanilla names with stable plain metadata are even resolution candidates");
        var skipped = forecast.stockRows(null, null, -1);
        SimpleAssert.that(
            skipped.getTagList("rows", 10).tagCount() == 0 && skipped.getLong("unresolvedItems") == 7
                && skipped.getLong("unaccounted") == 17 && skipped.getInteger("airCells") == 1,
            "without explicit resolution, all candidate costs are still unknown");

        var stone = new ItemStack(Blocks.stone, 2, 0);
        var fakeStock = new ItemStack(Blocks.stone, 40, 0);
        fakeStock.setTagCompound(new NBTTagCompound());
        fakeStock.getTagCompound().setBoolean("nonOrdinary", true);
        var taggedResult = new ItemStack(Blocks.planks, 1, 2);
        taggedResult.setTagCompound(new NBTTagCompound());
        var calls = new ArrayList<HologramLibraryMaterialForecast.Identity>();
        NBTTagCompound stock = forecast.stockRows(identity -> {
            calls.add(identity);
            String name = identity.registry();
            if ("minecraft:stone".equals(name)) return proved(name, new ItemStack(Blocks.stone));
            if ("minecraft:stonebrick".equals(name) && identity.damage() == 1)
                return proved(name, new ItemStack(Blocks.stonebrick, 1, 1));
            if ("minecraft:stonebrick".equals(name)) return proved("minecraft:stone", new ItemStack(Blocks.stone));
            if ("minecraft:planks".equals(name)) return proved(name, taggedResult);
            if ("minecraft:glass".equals(name)) return null; // No proof this runtime kept ordinary provider semantics.
            throw new AssertionError("nonordinary, tagged and extension source descriptors cannot reach the resolver");
        }, new ItemStack[] {new ItemStack(Blocks.stone, 60, 0), stone, fakeStock,
            new ItemStack(Blocks.stonebrick, 1, 1)}, 0);
        SimpleAssert.eq(forecast.candidates().stream().map(HologramLibraryMaterialForecast.Need::identity).toList(),
            List.copyOf(calls), "client resolver sees each ordinary candidate once, never unknown source forms");
        var rows = new HologramMaterialReview();
        rows.updateLocal(stock);
        SimpleAssert.that(
            rows.size() == 2 && "3".equals(rows.row(0).required) && "2".equals(rows.row(0).available)
                && "1".equals(rows.row(0).missing) && "1".equals(rows.row(1).available),
            "reuse exact backpack arithmetic; tags and protected slot do not count");
        SimpleAssert.that(
            stock.getLong("unaccounted") == 13 && stock.getLong("unresolvedItems") == 3
                && !stock.hasKey("complete") && !rows.ready() && !rows.hasItemShortage(),
            "a provider not proved ordinary, a resolver alias or a tagged mismatch remain unknown, with no quote authority");
    }

    private static void masked() {
        JsonObject masked = blueprint();
        masked.add("retainedNbtKeys", new com.google.gson.JsonArray());
        var tally = new HologramSavedMaterialSource.Tally(masked, 1);
        tally.include(0, row("stone", 0));
        var forecast = HologramLibraryMaterialForecast.project(tally.snapshot());
        NBTTagCompound rows = forecast.stockRows(
            identity -> { throw new AssertionError("masked policy admits no resolution"); }, null, -1);
        SimpleAssert.that(
            forecast.candidates().isEmpty() && forecast.unpriced().retention() == 1
                && rows.getBoolean("retentionNeedsReview") && rows.getLong("unaccounted") == 1,
            "an explicit retention mask is unknown, not a zero item budget");
    }

    private static JsonObject blueprint() {
        JsonObject blueprint = new JsonObject();
        blueprint.addProperty("schema", 2);
        return blueprint;
    }

    private static JsonObject row(String id, int metadata) {
        JsonObject row = new JsonObject();
        row.addProperty("block", "minecraft:" + id);
        row.addProperty("item", "minecraft:" + id);
        row.addProperty("metadata", metadata);
        row.addProperty("damage", metadata);
        return row;
    }

    private static HologramLibraryMaterialForecast.Resolved proved(String name, ItemStack sample) {
        return new HologramLibraryMaterialForecast.Resolved(name, sample);
    }
}
