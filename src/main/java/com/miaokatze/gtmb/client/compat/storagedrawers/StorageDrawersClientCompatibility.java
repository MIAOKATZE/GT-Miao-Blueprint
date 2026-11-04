package com.miaokatze.gtmb.client.compat.storagedrawers;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.api.ClientRendering;
import com.miaokatze.gtmb.api.GTMBExtensions;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Native two-pass table body; its ISBRH uses metadata and atlas icons without reading inventory or invoking a TESR. */
@SideOnly(Side.CLIENT)
public final class StorageDrawersClientCompatibility implements ClientRendering.Adapter {

    private static GTMBExtensions.Registration handle;

    private StorageDrawersClientCompatibility() {}

    public static synchronized void initialize() {
        if (handle == null && Loader.isModLoaded("StorageDrawers")) handle = ClientRendering
            .register("gtmb:storagedrawers/framing-table-body", new StorageDrawersClientCompatibility());
    }

    @Override
    public boolean supports(Block block) {
        return block != null && block.getClass()
            .getName()
            .equals("com.jaquadro.minecraft.storagedrawers.block.BlockFramingTable");
    }

    @Override
    public boolean requiresTile(Block block, int metadata) {
        return false;
    }

    @Override
    public TileEntity createTile(Block block, int metadata, World world) {
        return null;
    }
}
