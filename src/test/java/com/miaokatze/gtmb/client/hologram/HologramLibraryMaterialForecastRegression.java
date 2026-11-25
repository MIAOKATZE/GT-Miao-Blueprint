// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Headless coverage of the projection and the explicit client item/stock adapter, not a placement witness. */
final class HologramLibraryMaterialForecastRegression {

    private HologramLibraryMaterialForecastRegression() {}

    static void run() {
        unmasked();
        masked();
        descriptiveRows();
        sharedSourceFacts();
    }

    private static void sharedSourceFacts() {
        var compact = new HologramSavedMaterialSource.Tally(blueprint(), 8);
        var expanded = new HologramSavedMaterialSource.Tally(blueprint(), 8);
        var frozen = new HologramSavedMaterialSource.Tally(8, true, false);
        for (int i = 0; i < 4; i++) {
            JsonObject row = row("stone", 0);
            if (i == 1) row.addProperty("controller", true);
            if (i == 2) row.addProperty("count", 2);
            if (i == 3) row.addProperty("tileNbt", "opaque retained quantum/fluid payload");
            compact.include(i, row);
            JsonObject located = new JsonObject();
            for (var field : row.entrySet()) located.add(field.getKey(), field.getValue());
            located.addProperty("x", i);
            located.addProperty("y", 2);
            located.addProperty("z", 3);
            expanded.includeExpanded(located);
            frozen.includeFrozen(
                "minecraft:stone",
                0,
                "minecraft:stone",
                0,
                -1,
                i == 2 ? 2 : 1,
                i == 1,
                i == 3 ? "opaque retained quantum/fluid payload" : "",
                "");
        }
        var reference = compact.snapshot();
        for (var source : new HologramSavedMaterialSource[] { expanded.snapshot(), frozen.snapshot() }) {
            SimpleAssert.eq(
                reference.variants(),
                source.variants(),
                "compact, expanded and frozen capture keep equal exact descriptive variants");
            SimpleAssert.that(
                source.payloadCells() == 1 && source.unsupportedCells() == 1,
                "special retained payload and unsupported scalar remain explicit unknowns, never shell aliases");
        }
        var bounded = new HologramSavedMaterialSource.Tally(10_000, true, false);
        for (int i = 0; i <= HologramSavedMaterialSource.MAX_TRACKED_DESCRIPTORS; i++) {
            String id = "fixture:item_" + i;
            bounded.includeFrozen(id, 0, id, 0, -1, 1, false, "", "");
        }
        bounded.includeFrozen("fixture:item_0", 0, "fixture:item_0", 0, -1, 1, false, "", "");
        SimpleAssert.that(
            bounded.snapshot()
                .limitCells() == 1
                && bounded.snapshot()
                    .variants()
                    .get(0)
                    .cells() == 2,
            "wide frozen palettes stay bounded without dropping counts of already-tracked identities");
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
            forecast.candidates()
                .size() == 8
                && forecast.candidates()
                    .get(0)
                    .cells() == 3
                && forecast.unpriced()
                    .nonOrdinary() == 4
                && forecast.unpriced()
                    .cells() == 7,
            "structural candidates are registry-named tuples; no cost or readiness is inferred without resolution");
        var skipped = forecast.stockRows(null, null, -1);
        SimpleAssert.that(
            skipped.getTagList("rows", 10)
                .tagCount() == 0 && skipped.getLong("unresolvedItems") == 10
                && skipped.getLong("unaccounted") == 17
                && skipped.getInteger("airCells") == 1,
            "without explicit resolution, all candidate costs are still unknown");

        var stone = new ItemStack(Blocks.stone, 2, 0);
        var fakeStock = new ItemStack(Blocks.stone, 40, 0);
        fakeStock.setTagCompound(new NBTTagCompound());
        fakeStock.getTagCompound()
            .setBoolean("nonOrdinary", true);
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
            if ("minecraft:log".equals(name) || "minecraft:quartz_block".equals(name)
                || "detached:provider".equals(name)) return null;
            throw new AssertionError("nonordinary, tagged and extension source descriptors cannot reach the resolver");
        },
            new ItemStack[] { new ItemStack(Blocks.stone, 60, 0), stone, fakeStock,
                new ItemStack(Blocks.stonebrick, 1, 1) },
            0);
        SimpleAssert.eq(
            forecast.candidates()
                .stream()
                .map(HologramLibraryMaterialForecast.Need::identity)
                .collect(java.util.stream.Collectors.toList()),
            new ArrayList<>(calls),
            "client resolver sees each ordinary candidate once, never unknown source forms");
        var rows = new HologramMaterialReview();
        rows.updateLocal(stock);
        SimpleAssert.that(
            rows.size() == 2 && "3".equals(rows.row(0).required)
                && "2".equals(rows.row(0).available)
                && "1".equals(rows.row(0).missing)
                && "1".equals(rows.row(1).available),
            "reuse exact backpack arithmetic; tags and protected slot do not count");
        SimpleAssert.that(
            stock.getLong("unaccounted") == 13 && stock.getLong("unresolvedItems") == 6
                && !stock.hasKey("complete")
                && !rows.ready()
                && !rows.hasItemShortage(),
            "a provider not proved ordinary, a resolver alias or a tagged mismatch remain unknown, with no quote authority");
    }

    private static void masked() {
        JsonObject masked = blueprint();
        masked.add("retainedNbtKeys", new com.google.gson.JsonArray());
        var tally = new HologramSavedMaterialSource.Tally(masked, 1);
        tally.include(0, row("stone", 0));
        var forecast = HologramLibraryMaterialForecast.project(tally.snapshot());
        NBTTagCompound rows = forecast
            .stockRows(identity -> { throw new AssertionError("masked policy admits no resolution"); }, null, -1);
        SimpleAssert.that(
            forecast.candidates()
                .isEmpty()
                && forecast.unpriced()
                    .retention() == 1
                && rows.getBoolean("retentionNeedsReview")
                && rows.getLong("unaccounted") == 1,
            "an explicit retention mask is unknown, not a zero item budget");
    }

    private static void descriptiveRows() {
        var tally = new HologramSavedMaterialSource.Tally(blueprint(), 4);
        tally.include(0, row("stone", 0));
        for (int i = 1; i < 32; i++) tally.include(0, null);
        tally.include(1, row("stonebrick", 2));
        JsonObject mismatched = row("stone", 0);
        mismatched.addProperty("item", "minecraft:cobblestone");
        tally.include(2, mismatched);
        tally.include(3, row("air", 0));
        var forecast = HologramLibraryMaterialForecast.project(tally.snapshot());

        ItemStack[] inventory = new ItemStack[36];
        inventory[0] = new ItemStack(Blocks.stone, 64, 0); // The caller's protected slot is never counted.
        inventory[1] = new ItemStack(Blocks.stone, 32, 0);
        inventory[2] = new ItemStack(Blocks.stone, 16, 0);
        inventory[3] = new ItemStack(Blocks.stone, 64, 1);
        inventory[4] = new ItemStack(Blocks.stone, 64, 0);
        inventory[4].setTagCompound(new NBTTagCompound()); // Even an empty tag is not the raw untagged identity.
        inventory[5] = new ItemStack(Blocks.cobblestone, 64, 0);
        inventory[6] = new ItemStack(Blocks.stonebrick, 64, 2);
        AtomicInteger itemCalls = new AtomicInteger();
        inventory[7] = new ItemStack(new Item() {

            @Override
            public int getDamage(ItemStack ignored) {
                itemCalls.incrementAndGet();
                throw new AssertionError("an unrelated inventory item must not run its damage callback");
            }

            @Override
            public String getItemStackDisplayName(ItemStack ignored) {
                itemCalls.incrementAndGet();
                throw new AssertionError("a description must not call an item display callback");
            }
        }, 64, 0);
        inventory[8] = new ItemStack(Blocks.stone, 0, 0);

        AtomicInteger providerCalls = new AtomicInteger();
        String providerId = "gtmb_test:description_throwing_provider";
        try (var installed = NativePlacement.register(providerId, new NativePlacement.Adapter() {

            public boolean supports(Block block) {
                providerCalls.incrementAndGet();
                throw new AssertionError("a plain description must not probe native providers");
            }

            public TileEntity create(Block block, int meta, NBTTagCompound tag, World world) {
                providerCalls.incrementAndGet();
                throw new AssertionError("a plain description must not create a tile");
            }

            public List<ItemStack> materials(Block block, int meta, TileEntity tile) {
                providerCalls.incrementAndGet();
                throw new AssertionError("a plain description must not compute native costs");
            }
        })) {
            SimpleAssert.that(installed.active(), "the throwing fixture must be registered while observing");
            var description = HologramLibraryMaterialDescription.describe(forecast, inventory, 0);
            var stone = description.rows()
                .get(0);
            var stonebrick = description.rows()
                .get(1);
            SimpleAssert.that(
                description.rows()
                    .size() == 2 && description.unlistedCells() == 1
                    && description.airCells() == 1,
                "shape/alias mismatch is unlisted and plain air is separate");
            SimpleAssert.that(
                "minecraft:stone".equals(
                    stone.identity()
                        .registry())
                    && stone.blueprintCells() == 32
                    && Long.valueOf(48)
                        .equals(stone.visibleBackpackCount()),
                "the recorded 32 cells and the 48 observed exact items are independent facts");
            SimpleAssert.that(
                "minecraft:stonebrick".equals(
                    stonebrick.identity()
                        .registry())
                    && stonebrick.blueprintCells() == 1
                    && Long.valueOf(64)
                        .equals(stonebrick.visibleBackpackCount()),
                "registered plain metadata counts only the exact inventory damage");
            SimpleAssert.that(
                HologramLibraryMaterialDescription.describe(null, inventory, 0) == null
                    && HologramLibraryMaterialDescription.describe(forecast, null, 0)
                        .rows()
                        .get(0)
                        .visibleBackpackCount() == null
                    && HologramLibraryMaterialDescription.describe(forecast, inventory, -1)
                        .rows()
                        .get(0)
                        .visibleBackpackCount() == null
                    && HologramLibraryMaterialDescription.describe(forecast, inventory, 9)
                        .rows()
                        .get(0)
                        .visibleBackpackCount() == null,
                "an absent capture, unavailable inventory and unverified projector slot cannot imply zero");
            var empty = HologramLibraryMaterialDescription.describe(forecast, new ItemStack[36], 0);
            SimpleAssert.eq(
                Long.valueOf(0),
                empty.rows()
                    .get(0)
                    .visibleBackpackCount(),
                "only a proved observable empty inventory has a zero");
            inventory[1].stackSize = 1;
            SimpleAssert.eq(Long.valueOf(48), stone.visibleBackpackCount(), "the publication retains no live stacks");
        }
        SimpleAssert.eq(0, providerCalls.get(), "no native provider callback ran");
        SimpleAssert.eq(0, itemCalls.get(), "no dynamic inventory item callback ran");
        SimpleAssert.that(
            GTMBExtensions.descriptors()
                .stream()
                .noneMatch(row -> providerId.equals(row.id)),
            "the hostile fixture owns and retires only its one test registration");
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
