package com.miaokatze.gtmb.hologram;

import java.util.Collections;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

final class HologramVoucherCoverageRegression {

    static void run() throws Exception {
        java.lang.reflect.Field config = DecorationVoucherConfig.class.getDeclaredField("current");
        config.setAccessible(true);
        Object original = config.get(null);
        try {
            config.set(null, DecorationVoucherConfig.defaults());
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            HologramPresets.Cell wool = HologramPresets.cell(new ItemStack(Blocks.wool, 1, 3), 0, 0, 0, -1, false);
            wool.block = "minecraft:wool";
            wool.metadata = 3;
            preset.cells.add(wool);
            SimpleAssert.that(
                HologramVoucherCoverage.compute(preset),
                "entirely waivable geometry gains marker independent of stock");
            SimpleAssert
                .that(preset.fullyVoucherCovered == null, "coverage queries do not manufacture a persisted marker");
            SimpleAssert.that(
                HologramPresets.copy(preset).fullyVoucherCovered == null,
                "ordinary copies/imports retain absence of marker");
            for (int i = 0; i < 1300; i++) preset.cells.add(wool);
            HologramVoucherCoverage.Builder repeated = new HologramVoucherCoverage.Builder(preset);
            while (!repeated.step()) {}
            SimpleAssert.eq(1, repeated.nativeChecks, "identical cells share one native material check");
            preset.cells.subList(1, preset.cells.size())
                .clear();
            HologramPresets.Cell stone = HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false);
            stone.block = "minecraft:stone";
            preset.cells.add(stone);
            SimpleAssert
                .that(!HologramVoucherCoverage.compute(preset), "one nonwaivable shell prevents complete marker");
            stone.excluded = true;
            SimpleAssert.that(HologramVoucherCoverage.compute(preset), "excluded content does not charge materials");
            NBTTagCompound inventory = new NBTTagCompound();
            NBTTagList items = new NBTTagList();
            NBTTagCompound feather = new ItemStack(Items.feather).writeToNBT(new NBTTagCompound());
            feather.setByte("Slot", (byte) 0);
            items.appendTag(feather);
            inventory.setTag("Items", items);
            wool.itemNbt = HologramBlueprintNbt.encodeCanonical(inventory);
            SimpleAssert.that(
                !HologramVoucherCoverage.compute(preset),
                "embedded resources prevent a complete waiver even in a covered block");
            wool.itemNbt = null;
            DecorationVoucherConfig.View defaults = DecorationVoucherConfig.defaults();
            config.set(
                null,
                new DecorationVoucherConfig.View(
                    true,
                    defaults.allowedModIds,
                    defaults.allowedBlocks,
                    defaults.allowedOres,
                    defaults.deniedModIds,
                    Collections.singleton("minecraft:wool@3"),
                    defaults.deniedOres));
            SimpleAssert
                .that(!HologramVoucherCoverage.compute(preset), "server blacklist prevents a new complete marker");
            preset.cells.clear();
            SimpleAssert
                .that(!HologramVoucherCoverage.compute(preset), "empty blueprints never gain the complete marker");
        } finally {
            config.set(null, original);
        }
    }
}
