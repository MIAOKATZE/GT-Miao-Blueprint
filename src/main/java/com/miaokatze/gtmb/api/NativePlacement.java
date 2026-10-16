package com.miaokatze.gtmb.api;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.hologram.HologramNativePlacement;

/** Detached persistence and complete physical material accounting. Does not expose server placement operations. */
public final class NativePlacement {

    private NativePlacement() {}

    public interface Adapter {

        boolean supports(Block block);

        TileEntity create(Block block, int metadata, NBTTagCompound tag, World detachedWorld) throws Exception;

        List<ItemStack> materials(Block block, int metadata, TileEntity detachedTile) throws Exception;

        default boolean includesInventoryDrops(Block block) {
            return false;
        }
    }

    public static GTMBExtensions.Registration register(String id, Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        return GTMBExtensions.install(id, "native-placement", () -> {
            HologramNativePlacement.Adapter bridge = new HologramNativePlacement.Adapter() {

                public boolean supports(Block block) {
                    return adapter.supports(block);
                }

                public TileEntity create(Block block, int meta, NBTTagCompound tag, World world) throws Exception {
                    return adapter.create(block, meta, tag, world);
                }

                public List<ItemStack> materials(Block block, int meta, TileEntity tile) throws Exception {
                    return adapter.materials(block, meta, tile);
                }

                public boolean includesInventoryDrops(Block block) {
                    return adapter.includesInventoryDrops(block);
                }
            };
            HologramNativePlacement.register(id, bridge);
            return () -> HologramNativePlacement.unregister(id, bridge);
        });
    }
}
