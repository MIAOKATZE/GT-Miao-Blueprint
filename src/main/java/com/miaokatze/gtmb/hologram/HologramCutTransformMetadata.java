package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockCake;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockCocoa;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockEndPortalFrame;
import net.minecraft.block.BlockEnderChest;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockFurnace;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockHugeMushroom;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockPistonExtension;
import net.minecraft.block.BlockPortal;
import net.minecraft.block.BlockPumpkin;
import net.minecraft.block.BlockQuartz;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRedstoneDiode;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.BlockSkull;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockTripWireHook;
import net.minecraft.block.BlockVine;
import net.minecraft.init.Blocks;

import com.miaokatze.gtmb.api.CutTransforms;

/** Native schemas only. A recognized but unrepresentable pose is an error, never a successful no-op. */
final class HologramCutTransformMetadata {

    private static final int[] CARDINAL = { 3, 4, 2, 5 };
    private static final int[] DOOR = { 5, 3, 4, 2 };
    private static final int[] TRAPDOOR = { 3, 2, 5, 4 };
    private static final int[] TORCH = { -1, 5, 4, 3, 2, 1 };
    private static final int[] BUTTON = { 0, 5, 4, 3, 2, 1 };

    private HologramCutTransformMetadata() {}

    static boolean supports(Block b) {
        if (b == null) return false;
        return b.getClass() == Block.class || b instanceof BlockAir
            || b instanceof BlockChest
            || b instanceof BlockFurnace
            || b instanceof BlockEnderChest
            || b instanceof BlockDispenser
            || b instanceof BlockHopper
            || b instanceof BlockPistonBase
            || b instanceof BlockPistonExtension
            || b instanceof BlockLadder
            || b instanceof BlockRotatedPillar
            || b instanceof BlockLog
            || b instanceof BlockStairs
            || b instanceof BlockSlab
            || b instanceof BlockTorch
            || b instanceof BlockButton
            || b instanceof BlockRailBase
            || b instanceof BlockLever
            || b instanceof BlockDoor
            || b instanceof BlockBed
            || b instanceof BlockDoublePlant
            || b instanceof BlockTrapDoor
            || b instanceof BlockRedstoneDiode
            || b instanceof BlockPumpkin
            || b instanceof BlockFenceGate
            || b instanceof BlockAnvil
            || b instanceof BlockCocoa
            || b instanceof BlockTripWireHook
            || b instanceof BlockEndPortalFrame
            || b instanceof BlockVine
            || b instanceof BlockPortal
            || b instanceof BlockHugeMushroom
            || b instanceof BlockQuartz
            || b instanceof BlockSkull
            || b == Blocks.wall_sign
            || b == Blocks.standing_sign
            || spike(b)
            || HologramMetadataNeutral.kind(b) != 0;
    }

    static boolean isPaired(Block b) {
        return b instanceof BlockDoor || b instanceof BlockBed || b instanceof BlockDoublePlant;
    }

    static boolean knownPayload(Block block, net.minecraft.nbt.NBTTagCompound tile,
        net.minecraft.nbt.NBTTagCompound item) {
        if (block != null && HologramMetadataNeutral.kind(block) == 0
            && block.getClass()
                .getName()
                .startsWith("net.minecraft.block."))
            return true;
        return HologramMetadataPayloads.known(block, tile, item);
    }

    static void validatePairedPose(Block b, CutTransforms.Transform pose) throws IOException {
        if (isPaired(b)) upright(pose);
    }

    static boolean apply(CutTransforms.State state, CutTransforms.Transform pose) throws IOException {
        Block b = state.block;
        int m = state.metadata;
        if (b == null) return false;
        if (b == Blocks.air) return true;
        if (!supports(b)) return false;
        if (!knownPayload(b, state.tile, null)) return false;
        require(m >= 0 && m <= 15, "metadata outside native nibble");
        if (b instanceof BlockChest || b instanceof BlockFurnace
            || b instanceof BlockEnderChest
            || b instanceof BlockLadder
            || b == Blocks.wall_sign) {
            upright(pose);
            require((m & 7) >= 2 && (m & 7) <= 5, "invalid horizontal facing");
            state.metadata = (m & ~7) | pose.direction(m & 7);
        } else if (b instanceof BlockDispenser || b instanceof BlockPistonBase
            || b instanceof BlockPistonExtension
            || b instanceof BlockHopper) {
                int face = m & 7;
                require(face < 6 && (!(b instanceof BlockHopper) || face != 1), "invalid native facing");
                int next = pose.direction(face);
                if (b instanceof BlockHopper) {
                    upright(pose);
                    require(next != 1, "hopper has no upward outlet");
                }
                state.metadata = (m & ~7) | next;
            } else if (b instanceof BlockLog || b instanceof BlockRotatedPillar) {
                require(
                    !(b.getClass()
                        .getName()
                        .equals("thaumcraft.common.blocks.BlockMagicalLog") && (m & 3) == 2
                        && state.tile != null),
                    "Thaumcraft node-bearing log requires its tile schema");
                state.metadata = axis(m, pose);
            } else if (b instanceof BlockStairs) {
                require(m < 8, "invalid stair metadata");
                state.metadata = HologramMetadataGeometry.stair(m, pose);
            } else if (b instanceof BlockSlab) {
                if (!b.isOpaqueCube()) {
                    int up = pose.direction(1);
                    require(up < 2, "sideways half slab is not representable");
                    state.metadata = up == 0 ? m ^ 8 : m;
                }
            } else if (b instanceof BlockTorch || b instanceof BlockButton) {
                // Forestry stump's bit 8 is its lit state; attachment occupies only the low three bits.
                state.metadata = remap(m, 7, b instanceof BlockButton ? BUTTON : TORCH, pose);
            } else if (b instanceof BlockRailBase) {
                upright(pose);
                state.metadata = HologramMetadataGeometry.rail(m, ((BlockRailBase) b).isPowered(), pose);
            } else if (b instanceof BlockLever) {
                state.metadata = HologramMetadataGeometry.lever(m, pose);
            } else if (b instanceof BlockDoor) {
                validatePairedPose(b, pose);
                state.metadata = (m & 8) != 0 ? mirrored(pose) ? m ^ 1 : m : remap(m, 3, DOOR, pose);
            } else if (b instanceof BlockBed) {
                validatePairedPose(b, pose);
                state.metadata = remap(m, 3, CARDINAL, pose);
            } else if (b instanceof BlockDoublePlant) {
                validatePairedPose(b, pose);
                if ((m & 8) != 0) state.metadata = remap(m, 3, CARDINAL, pose);
            } else if (b instanceof BlockTrapDoor) {
                require(pose.direction(1) < 2, "trapdoor roll is not representable without changing open state");
                state.metadata = remap(m, 3, TRAPDOOR, pose);
                if (pose.direction(1) == 0) state.metadata ^= 8;
            } else if (b instanceof BlockRedstoneDiode || b instanceof BlockPumpkin
                || b instanceof BlockFenceGate
                || b instanceof BlockAnvil
                || b instanceof BlockCocoa
                || b instanceof BlockTripWireHook
                || b instanceof BlockEndPortalFrame) {
                    upright(pose);
                    state.metadata = remap(m, 3, CARDINAL, pose);
                } else if (b instanceof BlockVine) {
                    upright(pose);
                    int next = 0;
                    for (int i = 0; i < 4; i++)
                        if ((m & (1 << i)) != 0) next |= 1 << index(CARDINAL, pose.direction(CARDINAL[i]));
                    state.metadata = next;
                } else if (b instanceof BlockPortal) {
                    require(m == 1 || m == 2, "portal plane requires a resolved native axis");
                    int normal = pose.direction(m == 1 ? 3 : 5);
                    require(normal >= 2, "horizontal portal plane is not representable");
                    state.metadata = normal < 4 ? 1 : 2;
                } else if (b instanceof BlockHugeMushroom) {
                    state.metadata = HologramMetadataGeometry.mushroom(m, pose);
                } else if (b == Blocks.standing_sign) {
                    upright(pose);
                    state.metadata = angle(m, pose);
                } else if (b instanceof BlockQuartz) {
                    require(m <= 4, "invalid quartz variant");
                    if (m >= 2) {
                        int next = pose.direction(m == 3 ? 5 : m == 4 ? 3 : 1) >> 1;
                        state.metadata = next == 2 ? 3 : next == 1 ? 4 : 2;
                    }
                } else if (b instanceof BlockSkull) {
                    upright(pose);
                    int face = m & 7;
                    require(face >= 1 && face <= 5, "skull has no ceiling attachment");
                    if (face == 1) {
                        require(state.tile != null && state.tile.hasKey("Rot", 1), "skull rotation payload is absent");
                        int rot = state.tile.getByte("Rot");
                        require(rot >= 0 && rot < 16, "invalid skull rotation");
                        state.tile.setByte("Rot", (byte) angle(rot, pose));
                    } else state.metadata = (m & ~7) | pose.direction(face);
                } else if (spike(b)) {
                    require(m < 12, "invalid ExtraUtilities spike variant");
                    state.metadata = m / 6 * 6 + pose.direction(m % 6);
                } else if (HologramMetadataNeutral.kind(b) == 2) {
                    upright(pose);
                    if (b instanceof BlockCake && m > 0)
                        require(pose.direction(5) == 5, "native cake cannot move its bitten side");
                }
        return true;
    }

    static int axis(int metadata, CutTransforms.Transform pose) {
        int axis = metadata & 12;
        if (axis == 12) return metadata;
        int next = pose.direction(axis == 4 ? 5 : axis == 8 ? 3 : 1) >> 1;
        return (metadata & ~12) | (next == 2 ? 4 : next == 1 ? 8 : 0);
    }

    private static boolean spike(Block block) {
        for (Class<?> type = block == null ? null : block.getClass(); type != null; type = type.getSuperclass())
            if (type.getName()
                .equals("com.rwtema.extrautils.block.BlockSpike")) return true;
        return false;
    }

    static int remap(int metadata, int mask, int[] faces, CutTransforms.Transform pose) throws IOException {
        int old = metadata & mask;
        require(old < faces.length && faces[old] >= 0, "invalid attachment metadata");
        return (metadata & ~mask) | index(faces, pose.direction(faces[old]));
    }

    static int index(int[] faces, int direction) throws IOException {
        for (int i = 0; i < faces.length; i++) if (faces[i] == direction) return i;
        throw new IOException("Native metadata cannot represent the transformed attachment");
    }

    static void upright(CutTransforms.Transform pose) throws IOException {
        require(pose.direction(1) == 1, "native upright block cannot represent this pose");
    }

    static void require(boolean valid, String reason) throws IOException {
        if (!valid) throw new IOException(reason);
    }

    private static boolean mirrored(CutTransforms.Transform pose) {
        int[] a = pose.vector(1, 0, 0), b = pose.vector(0, 0, 1);
        return a[0] * b[2] - a[2] * b[0] < 0;
    }

    private static int angle(int rotation, CutTransforms.Transform pose) {
        double angle = rotation * Math.PI / 8;
        int[] a = pose.vector(1, 0, 0), b = pose.vector(0, 0, 1);
        double x = -a[0] * Math.sin(angle) + b[0] * Math.cos(angle);
        double z = -a[2] * Math.sin(angle) + b[2] * Math.cos(angle);
        return Math.floorMod((int) Math.round(Math.atan2(-x, z) * 8 / Math.PI), 16);
    }
}
