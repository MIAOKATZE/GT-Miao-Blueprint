// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Verifies only known static cuts get the same detached preview and payment material identity. */
final class HologramLittleTilesMaterialAliasesRegression {

    private HologramLittleTilesMaterialAliasesRegression() {}

    private static NBTTagCompound cut(String material, int meta, String type) {
        var child = new NBTTagCompound();
        child.setString("block", material);
        child.setInteger("meta", meta);
        child.setString("tID", type);
        child.setInteger("bBox0minY", 5);
        child.setInteger("bBox0maxY", 9);
        return child;
    }

    static void run() {
        var legacy = HologramLittleTilesMaterialAliases.OLD_CONCRETE;
        var canonical = HologramLittleTilesMaterialAliases.CURRENT_CONCRETE;
        var original = new NBTTagCompound();
        original.setString("id", "LittleTilesTileEntity");
        original.setInteger("tilesCount", 20);
        for (int x = 0; x < 16; x++)
            original.setTag("t" + x, cut(legacy, x, x % 2 == 0 ? "BlockTileBlock" : "BlockTileColored"));
        original.setTag("t16", cut("othermod:concrete", 4, "BlockTileBlock"));
        original.setTag("t17", cut(legacy, 7, "unreviewed"));
        original.setTag("t18", cut(legacy, 16, "BlockTileBlock"));
        original.setTag("t19", cut(legacy, 2, "BlockTileBlock"));
        original.getCompoundTag("t19")
            .setTag("tileEntity", new NBTTagCompound());
        var visual = (NBTTagCompound) original.copy();
        SimpleAssert.that(
            !HologramLittleTilesMaterialAliases.applyTile(Blocks.stone, visual) && visual.equals(original),
            "non-LittleTiles registry host is untouched");
        SimpleAssert.that(
            !HologramLittleTilesMaterialAliases.rewriteTile(visual, false) && visual.equals(original),
            "no mapping while original exists or the replacement is absent");
        SimpleAssert.that(
            HologramLittleTilesMaterialAliases.rewriteTile(visual, true),
            "detached exact LittleTiles template gets the registered replacement");
        for (int i = 0; i < 20; i++) {
            var old = original.getCompoundTag("t" + i);
            var converted = visual.getCompoundTag("t" + i);
            if (i < 16) {
                var matchingPayment = (NBTTagCompound) old.copy();
                SimpleAssert.that(
                    HologramLittleTilesMaterialAliases.rewriteChild(matchingPayment, true)
                        && canonical.equals(converted.getString("block"))
                        && converted.getInteger("meta") == i
                        && converted.equals(matchingPayment)
                        && legacy.equals(old.getString("block")),
                    "written cut and exact debit sample match all 16 dye metadata without mutating source");
                var geometry = (NBTTagCompound) converted.copy();
                geometry.setString("block", legacy);
                SimpleAssert.that(geometry.equals(old), "only block name differs; geometry/style unchanged");
            } else SimpleAssert.that(converted.equals(old), "unknown, malformed and nested payloads stay untouched");
        }
        SimpleAssert.that(!HologramLittleTilesMaterialAliases.rewriteTile(visual, true), "alias is idempotent");
        System.out.println(
            "[GTMB-LITTLE-TILES] exact legacy concrete: all 16 dye metadata preserved; geometry, unrelated and original payloads unchanged");
    }
}
