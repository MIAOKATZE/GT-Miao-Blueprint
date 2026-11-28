// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.covers.CoverRegistry;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.covers.Cover;

/** Visual overlap only. This never establishes construction acceptance, payment or business NBT equality. */
public final class HologramProjectionIdentity {

    private HologramProjectionIdentity() {}

    public static final class Target {

        private final Block block;
        private final int metadata, facing;
        private final ItemStack stack;
        private final NBTTagCompound payload;
        private final NBTTagCompound display;

        private Target(Block block, int metadata, int facing, ItemStack stack, NBTTagCompound payload,
            NBTTagCompound display) {
            this.block = block;
            this.metadata = metadata;
            this.facing = facing;
            this.stack = stack;
            this.payload = payload;
            this.display = display;
        }

        /** A definite occupied-slot conflict, separately from unavailable full configuration evidence. */
        public boolean conflicts(World world, int x, int y, int z) {
            if (world == null || block == null || !world.blockExists(x, y, z)) return false;
            Block actual = world.getBlock(x, y, z);
            if (actual == net.minecraft.init.Blocks.air) return false;
            if (stack != null && stack.getItem() instanceof ItemMachines) {
                if (actual != block) return true;
                TileEntity tile = world.getTileEntity(x, y, z);
                return !(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileID() != stack.getItemDamage()
                    || facing >= 0 && HologramBlockIdentity.front(gt) != facing;
            }
            return !HologramBlockIdentity.sameForPlacement(actual, world.getBlockMetadata(x, y, z), block, metadata);
        }

        public boolean matches(World world, int x, int y, int z) {
            if (world == null || !world.blockExists(x, y, z) || block == null) return false;
            Block actual = world.getBlock(x, y, z);
            int actualMeta = world.getBlockMetadata(x, y, z);
            if (stack != null && stack.getItem() instanceof ItemMachines) {
                if (actual != block) return false;
                TileEntity tile = world.getTileEntity(x, y, z);
                if (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileID() != stack.getItemDamage()
                    || facing >= 0 && HologramBlockIdentity.front(gt) != facing) return false;
                try {
                    return sameGtDisplay(gt, tile);
                } catch (RuntimeException | LinkageError unavailable) {
                    return false;
                }
            }
            if (!HologramBlockIdentity.sameForPlacement(actual, actualMeta, block, metadata)) return false;
            // These vanilla models are fixed by block/meta. Their inventories, names and operational
            // configuration do not add geometry, and remain solely the server's construction concern.
            if (!block.hasTileEntity(metadata) || block == Blocks.chest
                || block == Blocks.trapped_chest
                || block == Blocks.ender_chest
                || block == Blocks.furnace
                || block == Blocks.lit_furnace
                || block == Blocks.hopper
                || block == Blocks.dispenser
                || block == Blocks.dropper
                || block == Blocks.brewing_stand
                || block == Blocks.enchanting_table
                || block == Blocks.beacon) return true;
            if (payload != null && !payload.hasNoTags()) {
                // The native comparison omits project-owned binding labels and checks the retained
                // model payload: different multipart/shape models must not disappear by block ID alone.
                return HologramNativePlacement.supported(block, payload)
                    && HologramNativePlacement.matchesPlaced(block, metadata, world.getTileEntity(x, y, z), payload);
            }
            return false; // Missing model data for an unknown/custom tile is not visual equality.
        }

        private boolean sameGtDisplay(IGregTechTileEntity gt, TileEntity tile) {
            if (display == null) return true;
            if (display.hasKey("color") && gt.getColorization() != display.getInteger("color")) return false;
            if (display.hasKey("connections") && (!(tile instanceof BaseMetaPipeEntity pipe)
                || (pipe.getConnections() & 63) != display.getInteger("connections"))) return false;
            if (display.hasKey("texturePage") || display.hasKey("textureIndex")) {
                NBTTagCompound actual = HologramDisplaySnapshot.projection(tile);
                for (String key : new String[] { "texturePage", "textureIndex" }) if (display.hasKey(key)
                    && (!actual.hasKey(key) || actual.getInteger(key) != display.getInteger(key))) return false;
            }
            NBTTagList covers = display.getTagList("gt.covers", 10);
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                Cover expected = null;
                for (int i = 0; i < covers.tagCount(); i++) {
                    NBTTagCompound row = covers.getCompoundTagAt(i);
                    if (row.getByte("s") == side.ordinal()) {
                        expected = CoverRegistry.buildCoverFromNbt((NBTTagCompound) row.copy(), side, null);
                        if (expected == null || !expected.isValid()) return false;
                    }
                }
                Cover actual = gt.getCoverAtSide(side);
                boolean present = actual != null && actual.isValid();
                if (expected == null ? present
                    : !present || !ItemStack.areItemStacksEqual(expected.asItemStack(), actual.asItemStack()))
                    return false;
            }
            return true;
        }
    }

    /** Reuse the exact block adapters used by blueprint placement; unsupported rotations stay unproven. */
    public static Target target(Block block, int metadata, int facing, ItemStack stack, String sourceClass,
        NBTTagCompound payload, HologramPreviewPose pose) {
        return target(block, metadata, facing, stack, sourceClass, payload, pose, null);
    }

    public static Target target(Block block, int metadata, int facing, ItemStack stack, String sourceClass,
        NBTTagCompound payload, HologramPreviewPose pose, NBTTagCompound visual) {
        boolean gt = stack != null && stack.getItem() instanceof ItemMachines;
        if (pose == null)
            return new Target(block, metadata, HologramBlockIdentity.front(facing), stack, gt ? null : payload, visual);
        try {
            HologramBlockTransforms.Data data = new HologramBlockTransforms.Data(
                block,
                sourceClass,
                metadata,
                gt ? null : payload,
                gt || stack == null ? null : stack.getTagCompound());
            NBTTagCompound display = visual == null ? null : (NBTTagCompound) visual.copy();
            data.authoring = true;
            if (pose != null) for (int turn : pose.recipe()) {
                data = HologramBlockTransforms.turn(data, turn == 1);
                if (HologramAeBlueprint.supports(block, data.tile))
                    HologramAeBlueprint.rotateUnresolved(data.tile, turn == 1);
                if (gt && display != null) {
                    HologramBlockTransformAdapters.gregtech(display, turn == 1);
                    if (display.hasKey("connections")) display.setInteger(
                        "connections",
                        HologramBlockTransformAdapters.mask(display.getInteger("connections"), turn == 1));
                }
            }
            ItemStack expected = stack == null ? null : stack.copy();
            if (expected != null) expected.setTagCompound(data.item);
            int finalFacing = HologramBlockIdentity.front(facing);
            if (pose != null && finalFacing >= 0) {
                ForgeDirection direction = ForgeDirection.getOrientation(finalFacing);
                int[] turned = pose.apply(direction.offsetX, direction.offsetY, direction.offsetZ);
                finalFacing = ForgeDirection.UNKNOWN.ordinal();
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
                    if (side.offsetX == turned[0] && side.offsetY == turned[1] && side.offsetZ == turned[2])
                        finalFacing = side.ordinal();
            }
            return new Target(block, data.metadata, finalFacing, expected, data.tile, display);
        } catch (IOException | RuntimeException | LinkageError unsupported) {
            return null;
        }
    }
}
