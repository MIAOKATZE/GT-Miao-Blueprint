// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
        AtomicReference<Runnable> queued = new AtomicReference<>();
        HologramLocalMaterialReview review = new HologramLocalMaterialReview(repeated, config, queued::set);
        ItemStack[] stock = { new ItemStack(Blocks.stone, 3), new ItemStack(Blocks.stone, 7) };
        review.tick(stock, 0);
        SimpleAssert.that(!review.ready() && review.visible().getTagList("rows", 10).tagCount() == 0,
            "queued planner is neither ready nor a zero bill");
        queued.get().run();
        reviews(review, stock, 0, 20);
        NBTTagCompound row = review.visible().getTagList("rows", 10).getCompoundTagAt(0);
        SimpleAssert.that(review.ready() && "123369".equals(row.getString("requiredExact"))
            && row.getBoolean("availableKnown") && row.getLong("available") == 7
            && "123362".equals(row.getString("missingExact")), "repeated exact stack totals and protected slot");

        HologramLocalMaterialReview cancelled = new HologramLocalMaterialReview(repeated, config, queued::set);
        NBTTagCompound pending = new NBTTagCompound();
        pending.setBoolean("capturePending", true);
        HologramState recapturing = repeated.withMetadata(pending);
        SimpleAssert.that(recapturing.cells == repeated.cells && !cancelled.current(recapturing, 0),
            "a recapture shell with the same geometry immediately retires the old material scope");
        pending.setBoolean("capturePending", false);
        pending.setString("generation", "another-local-capture");
        SimpleAssert.that(!cancelled.current(repeated.withMetadata(pending), 0),
            "identical visible rows do not authorize a different local capture generation");
        cancelled.close();
        queued.get().run();
        cancelled.tick(stock, 0);
        SimpleAssert.that(!cancelled.ready() && !cancelled.visible().hasKey("rows"), "retired worker cannot publish");

        for (int style = 1; style <= 2; style++) {
            HologramLocalMaterialReview mixed = new HologramLocalMaterialReview(state(129, style), config, queued::set);
            queued.get().run();
            reviews(mixed, stock, 0, 20);
            var rows = mixed.visible().getTagList("rows", 10);
            SimpleAssert.that(mixed.ready() && rows.tagCount() >= 2 && !mixed.visible().getBoolean("complete"),
                "mixed material/metadata and tagged-device paths remain descriptive, never authoritative");
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound candidate = rows.getCompoundTagAt(i);
                if (candidate.getCompoundTag("stack").hasKey("tag", 10))
                    SimpleAssert.that(!candidate.getBoolean("availableKnown") || candidate.getLong("available") == 0,
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
        HologramLocalMaterialReview captured = new HologramLocalMaterialReview(geometry, configuration(),
            ignored -> { throw new AssertionError("captured material list must not queue a target scan"); });
        var description = captured.description();
        SimpleAssert.that(captured.ready() && geometry.data.getBoolean("capturePending") && description.size() == 1
            && "minecraft:chest".equals(description.get(0).identity().registry())
            && description.get(0).blueprintCells() == 3 && description.get(0).visibleBackpackCount() == null
            && geometry.controllerMaterialCounts.unlisted() == 3,
            "settled base loop supplies known mixed counts before candidate detail and inventory; tagged rows stay unknown");
        captured.tick(new ItemStack[] { new ItemStack(Blocks.chest, 40), new ItemStack(Blocks.chest, 2) }, 0);
        SimpleAssert.eq(Long.valueOf(2), captured.description().get(0).visibleBackpackCount(),
            "independent owner stock excludes the protected projector slot");

        NBTTagCompound finish = (NBTTagCompound) geometry.data.copy();
        finish.setBoolean("projectionDataComplete", true);
        finish.setBoolean("capturePending", false);
        NBTTagList details = new NBTTagList();
        for (int i = 0; i < geometry.cells.size(); i++) details.appendTag(new NBTTagCompound());
        finish.setTag("cells", details);
        HologramState.ControllerDetails enrichment = new HologramState.ControllerDetails(geometry, finish);
        while (!enrichment.step(32, Long.MAX_VALUE)) {}
        HologramState settled = enrichment.result();
        SimpleAssert.that(captured.current(settled, 0), "same-generation detail publication preserves the base list");
        java.util.concurrent.atomic.AtomicReference<Runnable> detailedWork = new java.util.concurrent.atomic.AtomicReference<>();
        HologramLocalMaterialReview detailed = new HologramLocalMaterialReview(
            settled, configuration(), true, detailedWork::set);
        SimpleAssert.that(detailedWork.get() != null && !detailed.ready() && detailed.description() == null
            && captured.ready(), "only the explicit detail mode queues the legacy full local estimator");
        detailedWork.get().run();
        reviews(detailed, new ItemStack[2], 0, 8);
        SimpleAssert.that(detailed.ready() && detailed.visible().hasKey("rows", 9),
            "the explicitly requested full local estimate keeps its prior target inputs and detail rows");
        detailed.close();

        NBTTagCompound change = settled.cells.get(0).tag();
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
        SimpleAssert.that(!captured.current(edited, 0) && tally.total() == 6 && tally.unlisted() == 3
            && tally.rows().get(0).cells() == 2 && tally.rows().get(1).cells() == 1
            && "minecraft:stone".equals(tally.rows().get(1).identity().registry()),
            "point target replacement uses exactly one decrement/increment and retires the older pane");
        NBTTagCompound recapture = new NBTTagCompound();
        recapture.setBoolean("capturePending", true);
        SimpleAssert.that(edited.withMetadata(recapture).controllerMaterialCounts == null,
            "a recapture shell cannot serve the old geometry's list");
    }

    static int reviews(HologramLocalMaterialReview review, ItemStack[] stock, int slot, int limit) {
        int ticks = 0;
        while (!review.ready() && ticks < limit) {
            review.tick(stock, slot);
            ticks++;
        }
        return ticks;
    }

    static NBTTagCompound configuration() {
        NBTTagCompound config = new NBTTagCompound();
        config.setBoolean("materialMain", true);
        return config;
    }

    static HologramState state(int count, int style) {
        return state(count, style, false);
    }

    private static HologramState state(int count, int style, boolean captureCounts) {
        HologramControllerMaterialCounts.Builder tally = captureCounts ? new HologramControllerMaterialCounts.Builder()
            : null;
        List<HologramControllerTarget.Cell> cells = new ArrayList<>(count);
        Block[] blocks = { Blocks.stone, Blocks.wool, Blocks.stained_glass, Blocks.chest };
        String[] ids = { "minecraft:stone", "minecraft:wool", "minecraft:stained_glass", "minecraft:chest" };
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setString("session", "controlled-local-material-planner");
        metadata.setBoolean("localStandardController", true);
        metadata.setLong("planRevision", 1);
        metadata.setBoolean("capturePending", captureCounts);
        metadata.setString("generation", "local-capture-one");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("display", "owned sample retaining NBT");
        ItemStack[][] items = new ItemStack[blocks.length][16];
        for (int row = 0; row < items.length; row++) for (int damage = 0; damage < 16; damage++)
            items[row][damage] = new ItemStack(blocks[row], 1, damage);
        for (int i = 0; i < count; i++) {
            int block = style == 0 ? 0 : style == 1 ? (i % 97 == 0 ? 3 : i % 3) : i % 2 == 0 ? 3 : 0;
            int damage = block == 0 || block == 3 ? 0 : i / 3 % 16;
            ItemStack item = items[block][damage];
            if (style == 2 && i % 2 == 1) {
                item = item.copy();
                item.setTagCompound(tag);
            }
            int x = 1 + i % 64, y = i / 64 % 32, z = i / 2048;
            HologramControllerTarget.Cell cell = new HologramControllerTarget.Cell(x, y, z, x, y, z, "minecraft:air",
                ids[block], 0, damage, Blocks.air, blocks[block], null, item, null, null, false, false, false, "", "",
                "sample", "", "", -1, -1, null, null, -1, false, HologramControllerTarget.TargetKind.MATERIAL);
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
