// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramControllerMaterialCounts;
import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramLocalMaterialPlannerRegression {

    private HologramLocalMaterialPlannerRegression() {}

    public static void run() {
        capturedNativeMaterials();
        HologramState repeated = state(123_369, 0);
        NBTTagCompound config = configuration();
        HologramLocalMaterialReview review = new HologramLocalMaterialReview(repeated, config);
        ItemStack[] stock = { new ItemStack(Blocks.stone, 3), new ItemStack(Blocks.stone, 7) };
        review.tick(stock, 0);
        SimpleAssert.that(
            review.ready() && review.visible()
                .getTagList("rows", 10)
                .tagCount() == 1,
            "the complete published native target opens one grouped material row without a queued scan");
        HologramSelection selected = new HologramSelection();
        selected.bindMaterials(repeated);
        selected.click(1, false);
        selected.click(2, true);
        var selectedFacts = selected.materialResult(repeated);
        java.util.List<HologramState.Cell> unreadable = new java.util.AbstractList<>() {

            @Override
            public HologramState.Cell get(int ignored) {
                throw new AssertionError("material open read geometry");
            }

            @Override
            public int size() {
                throw new AssertionError("material open read geometry size");
            }
        };
        SimpleAssert.that(
            repeated.localMaterialIndex.select(unreadable, 2, 0, 1, new int[] { 1, 2 }, selectedFacts) == selectedFacts
                && selectedFacts.targets() == 2,
            "explicit selection review consumes totals computed during selection mutation, never geometry");
        selected.click(1, true);
        SimpleAssert.eq(
            1,
            selected.materialResult(repeated)
                .targets(),
            "deselection removes exactly its prior contribution");
        selected.clear();
        SimpleAssert.eq(
            0,
            selected.materialResult(repeated)
                .targets(),
            "clearing selection retires all selected costs");
        reviews(review, stock, 0, 20);
        NBTTagCompound row = review.visible()
            .getTagList("rows", 10)
            .getCompoundTagAt(0);
        SimpleAssert.that(
            review.ready() && "123369".equals(HologramMaterialReview.amount(row, "required"))
                && row.getBoolean("availableKnown")
                && row.getLong("available") == 7
                && "123362".equals(row.getString("missingExact")),
            "repeated exact stack totals and protected slot");

        HologramLocalMaterialReview cancelled = new HologramLocalMaterialReview(repeated, config);
        NBTTagCompound pending = new NBTTagCompound();
        pending.setBoolean("capturePending", true);
        pending.setBoolean("nativeTargetReady", false);
        HologramState recapturing = repeated.withMetadata(pending);
        SimpleAssert.that(
            recapturing.cells == repeated.cells && !cancelled.current(recapturing, 0),
            "a recapture shell with the same geometry immediately retires the old material scope");
        pending.setBoolean("capturePending", false);
        pending.setString("generation", "another-local-capture");
        SimpleAssert.that(
            !cancelled.current(repeated.withMetadata(pending), 0),
            "identical visible rows do not authorize a different local capture generation");
        cancelled.close();
        cancelled.tick(stock, 0);
        SimpleAssert.that(!cancelled.ready(), "retired review cannot publish or regain readiness");

        for (int style = 1; style <= 2; style++) {
            HologramLocalMaterialReview mixed = new HologramLocalMaterialReview(state(129, style), config);
            reviews(mixed, stock, 0, 20);
            var rows = mixed.visible()
                .getTagList("rows", 10);
            SimpleAssert.that(
                mixed.ready() && rows.tagCount() >= 2
                    && !mixed.visible()
                        .getBoolean("complete"),
                "mixed material/metadata and tagged-device paths remain descriptive, never authoritative");
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound candidate = rows.getCompoundTagAt(i);
                ItemStack exact = com.miaokatze.gtmb.hologram.HologramMaterialQuote.readItem(candidate);
                if (exact != null && exact.hasTagCompound()) SimpleAssert.that(
                    !candidate.getBoolean("availableKnown") || candidate.getLong("available") == 0,
                    "an untagged backpack stack cannot be counted as exact tagged stock");
            }
            mixed.close();
            mixed.refresh(stock, 0);
            mixed.tick(stock, 0);
            SimpleAssert.that(!mixed.current(repeated, 0), "a closed pane cannot regain ownership");
        }
    }

    private static void capturedNativeMaterials() {
        HologramState geometry = state(6, 2, true);
        HologramLocalMaterialReview captured = new HologramLocalMaterialReview(geometry, configuration(), null, true);
        captured.tick(new ItemStack[] { new ItemStack(Blocks.chest, 40), new ItemStack(Blocks.chest, 2) }, 0);
        NBTTagList rows = captured.visible()
            .getTagList("rows", 10);
        boolean chest = false;
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            ItemStack item = com.miaokatze.gtmb.hologram.HologramMaterialQuote.readItem(row);
            if (item == null || item.getItem() != net.minecraft.item.Item.getItemFromBlock(Blocks.chest)) continue;
            chest = row.getBoolean("availableKnown") && row.getLong("available") == 2
                && "3".equals(HologramMaterialReview.amount(row, "required"))
                && "1".equals(row.getString("missingExact"));
        }
        SimpleAssert.that(
            captured.ready() && captured.current(geometry, 0)
                && chest
                && !captured.visible()
                    .getBoolean("complete"),
            "opening before candidate browsing uses the same exact material rows, not a separate summary");
        NBTTagCompound ready = new NBTTagCompound();
        ready.setBoolean("capturePending", false);
        ready.setBoolean("projectionDataComplete", true);
        HologramState settled = geometry.withMetadata(ready);
        SimpleAssert.that(
            captured.current(settled, 0) && geometry.localMaterialIndex == settled.localMaterialIndex,
            "unchanged geometry metadata retains the same ledger and pane");
        NBTTagCompound stale = new NBTTagCompound();
        stale.setString("generation", "other-capture");
        SimpleAssert.that(
            !captured.current(settled.withMetadata(stale), 0) && captured.current(settled, 1),
            "another controller generation retires the pane while a visual layer change leaves its full target intact");

        NBTTagCompound change = settled.cells.get(0)
            .tag();
        change.setString("wantId", "minecraft:stone");
        change.setTag("targetStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        change.setInteger("index", 0);
        NBTTagList edits = new NBTTagList();
        edits.appendTag(change);
        NBTTagCompound patch = new NBTTagCompound();
        patch.setBoolean("partial", true);
        patch.setString("session", settled.session);
        patch.setString("generation", settled.data.getString("generation"));
        patch.setInteger("cellCount", settled.cells.size());
        patch.setLong("baseRevision", 1);
        patch.setLong("planRevision", 2);
        patch.setTag("cells", edits);
        HologramState edited = HologramState.apply(settled, patch);
        var tally = edited.controllerMaterialCounts;
        SimpleAssert.that(
            !captured.current(edited, 0) && tally.total() == 6
                && tally.unlisted() == 3
                && tally.rows()
                    .get(0)
                    .cells() == 2
                && tally.rows()
                    .get(1)
                    .cells() == 1
                && "minecraft:stone".equals(
                    tally.rows()
                        .get(1)
                        .identity()
                        .registry()),
            "point target replacement uses exactly one decrement/increment and retires the older pane");
        NBTTagCompound recapture = new NBTTagCompound();
        recapture.setBoolean("capturePending", true);
        SimpleAssert.that(
            edited.withMetadata(recapture).controllerMaterialCounts == null,
            "a recapture shell cannot serve the old geometry's list");
    }

    static int reviews(HologramLocalMaterialReview review, ItemStack[] stock, int slot, int limit) {
        int ticks = 0;
        review.tick(stock, slot);
        while (!review.ready() && ticks < limit) {
            review.tick(stock, slot);
            ticks++;
        }
        return ticks;
    }

    static NBTTagCompound configuration() {
        NBTTagCompound config = new NBTTagCompound();
        config.setBoolean("materialMain", true);
        config.setBoolean("materialContainers", false);
        config.setBoolean("materialMe", false);
        return config;
    }

    static HologramState state(int count, int style) {
        return state(count, style, false);
    }

    static HologramState state(int count, int style, boolean captureCounts) {
        HologramControllerMaterialCounts.Builder tally = captureCounts ? new HologramControllerMaterialCounts.Builder()
            : null;
        List<HologramControllerTarget.Cell> cells = new ArrayList<>(count);
        Block[] blocks = { Blocks.stone, Blocks.wool, Blocks.stained_glass, Blocks.chest };
        String[] ids = { "minecraft:stone", "minecraft:wool", "minecraft:stained_glass", "minecraft:chest" };
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setString("session", "controlled-local-material-planner");
        metadata.setBoolean("localStandardController", true);
        metadata.setLong("nativeTargetRevision", 1);
        metadata.setBoolean("nativeTargetReady", true);
        metadata.setLong("planRevision", 1);
        metadata.setBoolean("capturePending", captureCounts);
        metadata.setString("generation", "local-capture-one");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("display", "owned sample retaining NBT");
        ItemStack[][] items = new ItemStack[blocks.length][16];
        for (int row = 0; row < items.length; row++)
            for (int damage = 0; damage < 16; damage++) items[row][damage] = new ItemStack(blocks[row], 1, damage);
        for (int i = 0; i < count; i++) {
            int block = style == 0 ? 0 : style == 1 ? (i % 97 == 0 ? 3 : i % 3) : i % 2 == 0 ? 3 : 0;
            int damage = block == 0 || block == 3 ? 0 : i / 3 % 16;
            ItemStack item = items[block][damage];
            if (style == 2 && i % 2 == 1) {
                item = item.copy();
                item.setTagCompound(tag);
            }
            int x = 1 + i % 64, y = i / 64 % 32, z = i / 2048;
            HologramControllerTarget.Cell cell = new HologramControllerTarget.Cell(
                x,
                y,
                z,
                x,
                y,
                z,
                "minecraft:air",
                ids[block],
                0,
                damage,
                Blocks.air,
                blocks[block],
                null,
                item,
                null,
                null,
                false,
                false,
                false,
                "",
                "",
                "sample",
                "",
                "",
                -1,
                -1,
                null,
                null,
                -1,
                false,
                HologramControllerTarget.TargetKind.MATERIAL);
            cells.add(cell);
            if (tally != null) tally.add(cell, 0, 0, 0);
        }
        HologramState.Preparation preparation = new HologramState.Preparation(
            new HologramControllerTarget.Projection(metadata, cells, tally == null ? null : tally.seal()));
        while (!preparation.step(8192, Long.MAX_VALUE)) {
            // Exercise the same frozen structure representation as the local native controller.
        }
        return preparation.result();
    }
}
