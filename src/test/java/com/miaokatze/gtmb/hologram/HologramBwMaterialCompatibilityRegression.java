// SPDX-License-Identifier: AGPL-3.0-only
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import bartworks.system.material.BWMetaGeneratedBlocksCasing;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Real installed BW class, without registering its material/item/bootstrap machinery. */
public final class HologramBwMaterialCompatibilityRegression {

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramBwMaterialCompatibilityRegression.class, args)) return;
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        BWMetaGeneratedBlocksCasing block = (BWMetaGeneratedBlocksCasing) ((sun.misc.Unsafe) field.get(null))
            .allocateInstance(BWMetaGeneratedBlocksCasing.class);
        Field delegate = Block.class.getField("delegate");
        delegate.setAccessible(true);
        delegate.set(block, new cpw.mods.fml.common.registry.RegistryDelegate.Delegate<Block>(block, Block.class));
        java.lang.reflect.Method mainData = cpw.mods.fml.common.registry.GameData.class.getDeclaredMethod("getMain");
        mainData.setAccessible(true);
        java.lang.reflect.Method register = cpw.mods.fml.common.registry.GameData.class
            .getDeclaredMethod("registerBlock", Block.class, String.class, int.class);
        register.setAccessible(true);
        // No mod-discovery phase exists here; use the native registry insertion after FML's prefix selection.
        register.invoke(mainData.invoke(null), block, "bartworks:bw.werkstoffblockscasing.01", 3490);
        NBTTagCompound entry = entry();
        NBTTagCompound original = (NBTTagCompound) entry.copy();
        NBTTagCompound actual = HologramBwMaterialCompatibility.forRestoration(entry);
        NBTTagCompound cell = actual.getTagList("cells", 10)
            .getCompoundTagAt(0);
        if (block.hasTileEntity(1)) {
            SimpleAssert.that(actual.equals(original), "legacy BW keeps exact RAW tile representation");
            Class<?> type = Class.forName("bartworks.system.material.BWTileEntityMetaGeneratedBlocksCasing");
            TileEntity tile = (TileEntity) type.getConstructor()
                .newInstance();
            type.getField("mMetaData")
                .setShort(tile, (short) 513);
            @SuppressWarnings("unchecked")
            Map<Class<?>, String> names = (Map<Class<?>, String>) ReflectionHelper
                .findField(TileEntity.class, "classToNameMap", "field_145853_j")
                .get(null);
            String previous = names.put(type, "bw.werkstoffblockcasingTE");
            try {
                SimpleAssert.eq(
                    513,
                    HologramBwMaterialCompatibility.actualDamage(block, 1, tile),
                    "legacy actual reader uses tile short rather than truncated world metadata");
            } finally {
                if (previous == null) names.remove(type);
                else names.put(type, previous);
            }
        } else {
            SimpleAssert.eq(513, cell.getInteger("meta"), "modern BW retains the complete material metadata");
            SimpleAssert.that(!cell.hasKey("tile"), "modern BW no longer instantiates the deleted legacy tile");
            SimpleAssert.eq(
                513,
                HologramBwMaterialCompatibility.actualDamage(block, 513, null),
                "actual reader consumes full metadata without a tile");
        }
        SimpleAssert.that(entry.equals(original), "migration leaves the private archive input unchanged");
        NBTTagCompound bad = entry();
        bad.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("tile")
            .setString("owner", "unexpressible");
        reject(bad);
        bad = entry();
        bad.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("tile")
            .setShort("m", (short) -1);
        reject(bad);
        bad = entry();
        bad.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setString("block", "minecraft:stone");
        reject(bad);
        System.out.println("BW material/RAW compatibility passed; no world/payment execution");
    }

    private static NBTTagCompound entry() {
        NBTTagCompound entry = new NBTTagCompound(), cell = new NBTTagCompound(), tile = new NBTTagCompound();
        cell.setString("block", "bartworks:bw.werkstoffblockscasing.01");
        cell.setInteger("meta", 1);
        tile.setString("id", "bw.werkstoffblockcasingTE");
        tile.setShort("m", (short) 513);
        cell.setTag("tile", tile);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        entry.setTag("cells", cells);
        return entry;
    }

    private static void reject(NBTTagCompound entry) throws Exception {
        NBTTagCompound copy = (NBTTagCompound) entry.copy();
        try {
            HologramBwMaterialCompatibility.forRestoration(entry);
            throw new AssertionError("unsafe RAW migration accepted");
        } catch (IOException expected) {
            SimpleAssert.that(copy.equals(entry), "rejected RAW migration preserves the source");
        }
    }
}
