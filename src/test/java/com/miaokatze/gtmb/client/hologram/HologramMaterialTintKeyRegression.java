package com.miaokatze.gtmb.client.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Pure material-key regression; no atlas, texture callbacks or native rendering. */
public final class HologramMaterialTintKeyRegression {

    private HologramMaterialTintKeyRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramMaterialTintKeyRegression.class, args)) return;
        run();
        System.out.println("HologramMaterialTintKeyRegression PASS");
    }

    public static void run() {
        NBTTagCompound source = row();
        String planned = key(source);
        for (String status : new String[] { "protected", "unsupported", "missing", "satisfied", "placed" }) {
            source.setString("status", status);
            SimpleAssert.that(!planned.equals(key(source)), "untinted hint color includes semantic status " + status);
        }
        source.setString("status", "planned");
        for (String flag : new String[] { "excluded", "missingBlock", "sourceMissing" }) {
            source.setBoolean(flag, true);
            SimpleAssert.that(!planned.equals(key(source)), "untinted hint color includes " + flag);
            source.removeTag(flag);
        }
        source.setIntArray("hintTint", new int[] { 4, 5, 6 });
        String explicit = key(source);
        source.setString("status", "protected");
        source.setBoolean("excluded", true);
        SimpleAssert.eq(explicit, key(source), "explicit three-channel tint already fully determines hint color");
        source = row();
        source.setBoolean("iconOnly", false);
        String ordinary = key(source);
        source.setString("status", "missing");
        source.setBoolean("sourceMissing", true);
        SimpleAssert.eq(ordinary, key(source), "ordinary material sharing is not fragmented by unrelated status tint");
        source = row();
        source.setIntArray("hintTint", new int[] { 4, 5 });
        planned = key(source);
        source.setString("status", "missing");
        SimpleAssert.that(!planned.equals(key(source)), "incomplete hint tint still uses semantic fallback color");
    }

    private static String key(NBTTagCompound row) {
        return HologramTextures.cacheKey(new HologramState.Cell(0, row), false);
    }

    private static NBTTagCompound row() {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", "minecraft:stone");
        row.setString("wantId", "minecraft:stone");
        row.setString("status", "planned");
        row.setBoolean("iconOnly", true);
        NBTTagList icons = new NBTTagList();
        icons.appendTag(new NBTTagString("stone"));
        row.setTag("hintIcons", icons);
        row.setTag("actualStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        row.setTag("targetStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        return row;
    }
}
