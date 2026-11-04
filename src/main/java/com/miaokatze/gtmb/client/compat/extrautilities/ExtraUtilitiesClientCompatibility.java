package com.miaokatze.gtmb.client.compat.extrautilities;

import net.minecraft.block.Block;

import com.miaokatze.gtmb.api.ClientRendering;
import com.miaokatze.gtmb.api.GTMBExtensions;

import cpw.mods.fml.common.Loader;

/** Explicit native data-carrier declaration, never a policy for arbitrary renderType=-1 blocks. */
public final class ExtraUtilitiesClientCompatibility implements ClientRendering.Adapter {

    private static GTMBExtensions.Registration handle;

    private ExtraUtilitiesClientCompatibility() {}

    public static synchronized void initialize() {
        if (handle == null && Loader.isModLoaded("ExtraUtilities")) handle = ClientRendering
            .register("gtmb:extrautils/palette-invisible", new ExtraUtilitiesClientCompatibility());
    }

    public boolean supports(Block block) {
        return block != null && block.getClass()
            .getName()
            .equals("com.rwtema.extrautils.block.BlockColorData");
    }

    public boolean invisible(Block block, int metadata) {
        return supports(block);
    }

    public boolean requiresTile(Block block, int metadata) {
        return false;
    }

    public net.minecraft.tileentity.TileEntity createTile(Block block, int metadata, net.minecraft.world.World world) {
        return null;
    }
}
