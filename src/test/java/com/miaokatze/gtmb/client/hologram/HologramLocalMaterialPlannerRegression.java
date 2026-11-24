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

import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramLocalMaterialPlannerRegression {

    private HologramLocalMaterialPlannerRegression() {}

    public static void run() {
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
        List<HologramControllerTarget.Cell> cells = new ArrayList<>(count);
        Block[] blocks = { Blocks.stone, Blocks.wool, Blocks.stained_glass, Blocks.chest };
        String[] ids = { "minecraft:stone", "minecraft:wool", "minecraft:stained_glass", "minecraft:chest" };
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setString("session", "controlled-local-material-planner");
        metadata.setBoolean("localStandardController", true);
        metadata.setLong("planRevision", 1);
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
            cells.add(new HologramControllerTarget.Cell(x, y, z, x, y, z, "minecraft:air", ids[block], 0, damage,
                Blocks.air, blocks[block], null, item, null, null, false, false, false, "", "", "sample", "", "",
                -1, -1, null, null, -1, false, HologramControllerTarget.TargetKind.MATERIAL));
        }
        HologramState.Preparation preparation = new HologramState.Preparation(
            new HologramControllerTarget.Projection(metadata, cells));
        while (!preparation.step(8192, Long.MAX_VALUE)) {
            // Exercise the same frozen structure representation as the local native controller.
        }
        return preparation.result();
    }
}
