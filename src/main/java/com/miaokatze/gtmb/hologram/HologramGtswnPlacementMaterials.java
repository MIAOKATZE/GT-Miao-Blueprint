package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;

/** GTSWN 1.8.67 terminal placement bills; generated nodes/covers are identities, not payable items. */
final class HologramGtswnPlacementMaterials {

    static final int ANCHOR = 0, TUBE = 1;
    private static final int CAPACITY = 640;
    private static final String[] SUPPLIES = { "GTSWN_Supply_ANCHOR", "GTSWN_Supply_TUBE" };
    private static final String KINDS = "com.miaokatze.gtswn.common.gui.TerminalSupplyStore$Kind";
    private static final ItemStack[] REFERENCES = new ItemStack[2];
    private static boolean absent;

    private HologramGtswnPlacementMaterials() {}

    static boolean freeShell(Block block, ItemStack sample) {
        return HologramNetworkBindings.node(block) && sample != null
            && !HologramRecovery.hasSeal(sample)
            && sample.getItem() == Item.getItemFromBlock(block)
            && sample.getItemDamage() == block.damageDropped(0);
    }

    static List<ItemStack> extraMaterials(Block block, NBTTagCompound payload) throws IOException {
        if (payload == null || !"join".equals(payload.getString(HologramNetworkBindings.ROLE)))
            return Collections.emptyList();
        HologramNetworkBindings.group(payload);
        if (HologramNetworkBindings.node(block)) throw new IOException("量子节点不能使用并入方块的材料规则");
        return Collections.singletonList(reference(ANCHOR));
    }

    static List<ItemStack> coverCosts(NBTTagCompound tile, ItemStack cover) throws IOException {
        if (HologramGtswnCacheAdapter.kindForCover(cover) < 0) return Collections.singletonList(cover.copy());
        return laserHost(tile) ? Collections.singletonList(reference(TUBE)) : Collections.emptyList();
    }

    /** Resolve the registered host MTE, never infer laser status from a name or an arbitrary NBT flag. */
    private static boolean laserHost(NBTTagCompound tile) throws IOException {
        if (tile == null || !"BaseMetaTileEntity".equals(tile.getString("id"))
            || !tile.hasKey("mID", 99)
            || tile.getTag("mID")
                .getId() > 4)
            throw new IOException("无线覆盖板缺少可核验的GT宿主");
        long id = tile.getLong("mID");
        if (id < 0 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[(int) id] == null)
            throw new IOException("无线覆盖板宿主机器未注册");
        IMetaTileEntity host = GregTechAPI.METATILEENTITIES[(int) id];
        return host instanceof MTEHatch && ((MTEHatch) host).getConnectionType() == MTEHatch.ConnectionType.LASER;
    }

    /** Ask the exact shipped native enum for both identities, including GT's LaserVacuumPipe meta. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static ItemStack reference(int kind) throws IOException {
        if (kind < ANCHOR || kind > TUBE) throw new IOException("原生终端耗材类型无效");
        if (REFERENCES[kind] != null) return REFERENCES[kind].copy();
        try {
            Class<?> type = Class.forName(KINDS);
            Object value = Enum.valueOf((Class) type, kind == ANCHOR ? "ANCHOR" : "TUBE");
            ItemStack stack = (ItemStack) type.getMethod("reference")
                .invoke(value);
            if (stack == null || stack.getItem() == null || stack.stackSize != 1) throw new IOException("原生终端耗材尚未注册");
            REFERENCES[kind] = stack.copy();
            return stack.copy();
        } catch (ClassNotFoundException missing) {
            absent = true;
            throw new IOException("当前GTSWN缺少已审阅的终端耗材规则", missing);
        } catch (InvocationTargetException failure) {
            throw new IOException("原生终端耗材解析失败", failure.getCause());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            throw new IOException("当前GTSWN不支持已审阅的终端耗材规则", failure);
        }
    }

    static int supplyKind(ItemStack sample) {
        if (absent || sample == null
            || sample.getItem() == null
            || HologramRecovery.hasSeal(sample)
            || sample.hasTagCompound() && !sample.getTagCompound()
                .hasNoTags())
            return -1;
        try {
            for (int kind = ANCHOR; kind <= TUBE; kind++) {
                ItemStack reference = reference(kind);
                if (sample.getItem() == reference.getItem() && sample.getItemDamage() == reference.getItemDamage())
                    return kind;
            }
        } catch (IOException unavailableOptionalMod) {
            // No native identity means no exemption or access to a player's saved supplies.
        }
        return -1;
    }

    static int supplyCount(EntityPlayer player, int kind) {
        return supplyCount(
            player.getEntityData()
                .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG),
            kind);
    }

    static int supplyCount(NBTTagCompound persisted, int kind) {
        return Math.max(0, Math.min(CAPACITY, persisted.getInteger(SUPPLIES[kind])));
    }

    static void setSupply(EntityPlayer player, int kind, int count) {
        NBTTagCompound data = player.getEntityData();
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        persisted.setInteger(SUPPLIES[kind], Math.max(0, Math.min(CAPACITY, count)));
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }

    static int supplyRefundRoom(int current, int amount) {
        return Math.min(Math.max(0, amount), CAPACITY - Math.max(0, Math.min(CAPACITY, current)));
    }
}
