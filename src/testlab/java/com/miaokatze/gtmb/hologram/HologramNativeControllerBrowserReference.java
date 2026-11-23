// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.mojang.authlib.GameProfile;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.threads.RunnableMachineUpdate;

/** Testlab oracle using the real pinned BlockRenderer world, item source, and preview actor. */
public final class HologramNativeControllerBrowserReference {

    private static final int Y = 64;

    private HologramNativeControllerBrowserReference() {}

    public static Map<String, String> cells(ItemStack item, ForgeDirection facing, int main, NBTTagCompound channels)
        throws ReflectiveOperationException {
        Class<?> tracked = Class.forName("blockrenderer6343.client.world.TrackedDummyWorld");
        World world = HologramResourceWorld.create(() -> {
            try {
                return (World) tracked.getConstructor().newInstance();
            } catch (ReflectiveOperationException unavailable) {
                throw new IllegalStateException("BlockRenderer test world", unavailable);
            }
        });
        Class<?> fakePlayer = Class.forName("blockrenderer6343.client.world.ClientFakePlayer");
        EntityPlayer actor = (EntityPlayer) fakePlayer.getConstructor(World.class, GameProfile.class)
            .newInstance(world, new GameProfile(UUID.randomUUID(), "[GTMB NEI test oracle]"));
        Class<?> creative = Class.forName("blockrenderer6343.api.utils.CreativeItemSource");
        IItemSource source = (IItemSource) creative.getField("instance").get(null);
        ItemStack trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem(), main);
        for (Object entry : channels.func_150296_c()) {
            String id = (String) entry;
            ChannelDataAccessor.setChannelData(trigger, id, channels.getInteger(id));
        }
        ChannelDataAccessor.setChannelData(trigger, "gt_hatch", 1);

        boolean update = RunnableMachineUpdate.isCurrentThreadEnabled();
        RunnableMachineUpdate.setCurrentThreadEnabled(false);
        try {
            ItemStack stack = item.copy();
            stack.getItem().onItemUse(stack, actor, world, 0, Y, 0, 0, 0, Y, 0);
            if (!(world.getTileEntity(0, Y, 0) instanceof IGregTechTileEntity base))
                throw new IllegalStateException("BlockRenderer reference controller could not be placed");
            base.setFrontFacing(facing);
            Object machine = base.getMetaTileEntity();
            INEIPreviewModifier modifier = machine instanceof INEIPreviewModifier hook ? hook : null;
            if (modifier != null) modifier.onPreviewConstruct(trigger);
            if (machine instanceof ISurvivalConstructable survival) {
                var changed = tracked.getMethod("hasChanged");
                ISurvivalBuildEnvironment environment = ISurvivalBuildEnvironment.create(source, actor);
                for (int round = 1; ; round++) {
                    survival.survivalConstruct(trigger, Integer.MAX_VALUE, environment);
                    if (!(Boolean) changed.invoke(world)) break;
                    if (round == 2000) throw new IllegalStateException("BlockRenderer reference did not converge");
                }
            } else {
                TileEntity tile = world.getTileEntity(0, Y, 0);
                IConstructable constructable = tile instanceof IConstructableProvider provider
                    ? provider.getConstructable() : tile instanceof IConstructable nativeSource ? nativeSource : null;
                if (constructable != null) constructable.construct(trigger, false);
            }
            if (modifier != null) modifier.onPreviewStructureComplete(trigger);
        } finally {
            RunnableMachineUpdate.setCurrentThreadEnabled(update);
        }

        Map<?, ?> blocks = (Map<?, ?>) tracked.getField("blockMap").get(world);
        Map<String, String> rows = new TreeMap<>();
        for (Object entry : blocks.keySet()) {
            long position = (Long) entry;
            int x = CoordinatePacker.unpackX(position), y = CoordinatePacker.unpackY(position),
                z = CoordinatePacker.unpackZ(position);
            if (x == 0 && y == Y && z == 0) continue;
            Block block = world.getBlock(x, y, z);
            if (block == Blocks.air) continue;
            String id = Block.blockRegistry.getNameForObject(block);
            int meta = world.getBlockMetadata(x, y, z);
            TileEntity tile = world.getTileEntity(x, y, z);
            if (block == GregTechAPI.sBlockMachines && tile instanceof IGregTechTileEntity gt)
                rows.put(position(x, y - Y, z), block(id, meta, gt.getMetaTileID(), gt.getFrontFacing().ordinal()));
            else rows.put(position(x, y - Y, z), block(id, meta, -1, -1));
        }
        return rows;
    }

    public static String position(int dx, int dy, int dz) {
        return dx + "," + dy + "," + dz;
    }

    public static String block(String id, int meta, int machine, int facing) {
        return id + "/" + meta + (machine < 0 ? "" : "/" + machine + "/" + facing);
    }
}
