// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/** Reads the actual loaded world and native inventory. It never writes a block or alters a target or job. */
public final class HologramFourMachineWorldAudit {

    private HologramFourMachineWorldAudit() {}

    public static NBTTagCompound cell(WorldServer world, int x, int y, int z) {
        if (world == null || !world.blockExists(x, y, z))
            throw new IllegalStateException("Audit position is not loaded");
        NBTTagCompound result = new NBTTagCompound();
        result.setIntArray("position", new int[] { x, y, z });
        Block block = world.getBlock(x, y, z);
        result.setString("block", String.valueOf(Block.blockRegistry.getNameForObject(block)));
        result.setInteger("metadata", world.getBlockMetadata(x, y, z));
        result.setLong("worldTick", world.getTotalWorldTime());
        TileEntity tile = world.getTileEntity(x, y, z);
        result.setString(
            "tileClass",
            tile == null ? ""
                : tile.getClass()
                    .getName());
        if (tile != null) result.setInteger("liveTileIdentity", System.identityHashCode(tile));
        if (tile instanceof IGregTechTileEntity) {
            IGregTechTileEntity gt = (IGregTechTileEntity) tile;
            result.setInteger("metaTileId", gt.getMetaTileID());
            result.setInteger(
                "front",
                gt.getFrontFacing()
                    .ordinal());
            result.setBoolean("active", gt.isActive());
            if (tile instanceof BaseMetaTileEntity)
                result.setLong("storedEU", ((BaseMetaTileEntity) tile).getStoredEU());
            NBTTagList contents = new NBTTagList();
            for (int slot = 0; slot < gt.getSizeInventory(); slot++) {
                ItemStack stack = gt.getStackInSlot(slot);
                if (stack != null) {
                    NBTTagCompound row = stack.writeToNBT(new NBTTagCompound());
                    row.setInteger("slot", slot);
                    contents.appendTag(row);
                }
            }
            result.setTag("nativeInventory", contents);
            Object machine = gt.getMetaTileEntity();
            result.setString(
                "metaTileClass",
                machine == null ? ""
                    : machine.getClass()
                        .getName());
            if (machine instanceof MTEMultiBlockBase)
                result.setBoolean("naturalFormed", ((MTEMultiBlockBase) machine).mMachine);
            if (machine instanceof MTEHatch) {
                result.setInteger("texturePage", integer(machine, "texturePage"));
                result.setInteger("textureIndex", integer(machine, "textureIndex"));
            }
            result.setTag("nativeProjection", HologramDisplaySnapshot.projection(tile));
        }
        return result;
    }

    public static Map<NBTTagCompound, Long> inventory(EntityPlayerMP actor) {
        if (actor == null) throw new IllegalStateException("Actual actor missing");
        var counts = new LinkedHashMap<NBTTagCompound, Long>();
        for (ItemStack stack : actor.inventory.mainInventory) add(counts, stack);
        for (ItemStack stack : actor.inventory.armorInventory) add(counts, stack);
        return counts;
    }

    public static Map<NBTTagCompound, Long> looseItems(WorldServer world, int x, int y, int z) {
        var counts = new LinkedHashMap<NBTTagCompound, Long>();
        // Real fixture break drops fall from the elevated owned pad. Keep the finite X/Z columns, including ground.
        AxisAlignedBB area = AxisAlignedBB.getBoundingBox(x - 18, 0, z - 18, x + 19, world.getActualHeight(), z + 19);
        for (Object object : world.getEntitiesWithinAABB(EntityItem.class, area)) {
            EntityItem item = (EntityItem) object;
            if (!item.isDead) add(counts, item.getEntityItem());
        }
        return counts;
    }

    public static NBTTagList looseEntities(WorldServer world, int x, int z) {
        NBTTagList result = new NBTTagList();
        AxisAlignedBB area = AxisAlignedBB.getBoundingBox(x - 18, 0, z - 18, x + 19, world.getActualHeight(), z + 19);
        for (Object object : world.getEntitiesWithinAABB(EntityItem.class, area)) {
            EntityItem item = (EntityItem) object;
            if (item.isDead) continue;
            NBTTagCompound row = identity(item.getEntityItem());
            row.setString(
                "entity",
                item.getUniqueID()
                    .toString());
            row.setInteger("amount", item.getEntityItem().stackSize);
            row.setInteger("age", item.age);
            row.setDouble("x", item.posX);
            row.setDouble("y", item.posY);
            row.setDouble("z", item.posZ);
            result.appendTag(row);
        }
        return result;
    }

    public static NBTTagList counts(Map<NBTTagCompound, Long> counts) {
        NBTTagList rows = new NBTTagList();
        for (var entry : counts.entrySet()) {
            NBTTagCompound row = (NBTTagCompound) entry.getKey()
                .copy();
            row.setLong("amount", entry.getValue());
            rows.appendTag(row);
        }
        return rows;
    }

    public static NBTTagCompound identity(ItemStack stack) {
        if (stack == null || stack.getItem() == null) throw new IllegalStateException("Unregistered empty item");
        String item = (String) Item.itemRegistry.getNameForObject(stack.getItem());
        if (item == null || item.isEmpty()) throw new IllegalStateException("Unregistered material item");
        NBTTagCompound key = new NBTTagCompound();
        key.setString("item", item);
        key.setInteger("damage", stack.getItemDamage());
        if (stack.hasTagCompound()) key.setTag(
            "tag",
            stack.getTagCompound()
                .copy());
        return key;
    }

    public static void add(Map<NBTTagCompound, Long> counts, ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return;
        counts.merge(identity(stack), (long) stack.stackSize, Math::addExact);
    }

    private static int integer(Object value, String name) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object data = field.get(value);
                if (!(data instanceof Number))
                    throw new IllegalStateException("Native hatch skin field is not numeric");
                return ((Number) data).intValue();
            } catch (NoSuchFieldException next) {
                // Continue only through the exact live hatch object's native hierarchy.
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native hatch skin unavailable: " + name, failure);
            }
        }
        throw new IllegalStateException("Native hatch skin field missing: " + name);
    }
}
