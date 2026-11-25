// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockEnderChest;
import net.minecraft.block.BlockFurnace;
import net.minecraft.block.BlockHay;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockPistonExtension;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTorch;
import net.minecraft.init.Blocks;

import com.miaokatze.gtmb.api.CutTransforms;

/** Vanilla metadata schemas only; unknown mod metadata is never inferred from numeric coincidences. */
final class HologramCutTransformMetadata {

    private HologramCutTransformMetadata() {}

    static final int LEGACY = -1, UNREPRESENTABLE = -2;
    private static final int DOWN = 0, UP = 1;
    private static final int[] WALL_SUPPORTS = { -1, 5, 4, 3, 2, 1 };
    private static final int[] STAIR_FACES = { 5, 4, 3, 2 };
    private static final int[] SIGN_HEADINGS = { 3, 4, 2, 5 };

    /** Exact known vanilla persisted metadata, not a promise about unqualified blocks or texture reflections. */
    static int placementMetadata(Block block, String id, int meta, CutTransforms.Transform pose) {
        if (block == null || !id.startsWith("minecraft:") || !id.equals(Block.blockRegistry.getNameForObject(block)))
            return LEGACY;
        int up = pose.direction(UP);
        boolean verticalAxis = up == UP || up == DOWN;
        boolean nibble = meta >= 0 && meta <= 15;
        if (block == Blocks.chest || block == Blocks.trapped_chest || block == Blocks.furnace
            || block == Blocks.lit_furnace || block == Blocks.ender_chest || block == Blocks.wall_sign) {
            // Their top/lid/presentation is always upright and only four headings are encoded.
            return up == UP && horizontalFace(meta) ? pose.direction(meta) : UNREPRESENTABLE;
        }
        if (block == Blocks.ladder)
            return verticalAxis && horizontalFace(meta) ? pose.direction(meta) : UNREPRESENTABLE;
        if (block == Blocks.stone_button || block == Blocks.wooden_button) {
            // Forge 1.7.10 supports only 1..4 (walls); bit 8 is the pressed state, not the face.
            if (!nibble || !verticalAxis) return UNREPRESENTABLE;
            int face = meta & 7;
            if (face < 1 || face > 4) return UNREPRESENTABLE;
            int encoded = indexOf(WALL_SUPPORTS, pose.direction(WALL_SUPPORTS[face]), 1, 5);
            return encoded == UNREPRESENTABLE ? encoded : (meta & 8) | encoded;
        }
        if (block == Blocks.torch || block == Blocks.redstone_torch || block == Blocks.unlit_redstone_torch) {
            if (meta < 1 || meta > 5) return UNREPRESENTABLE;
            // 1..4 wall torches, 5 upright; there is no ceiling encoding.
            return indexOf(WALL_SUPPORTS, pose.direction(WALL_SUPPORTS[meta]), 1, 6);
        }
        if (block == Blocks.hopper) {
            int face = meta & 7;
            // The top opening stays upright, and the native outlet excludes UP.
            if (!nibble || up != UP || face == UP || face > 5) return UNREPRESENTABLE;
            return (meta & 8) | pose.direction(face);
        }
        if (block == Blocks.dispenser || block == Blocks.dropper || block == Blocks.piston
            || block == Blocks.sticky_piston || block == Blocks.piston_head) {
            int face = meta & 7;
            return nibble && face < 6 ? (meta & 8) | pose.direction(face) : UNREPRESENTABLE;
        }
        if (block == Blocks.standing_sign) {
            if (!nibble || up != UP) return UNREPRESENTABLE;
            // Starting from south (0), each quarter advances toward west (4), north (8), east (12).
            int base = indexOf(SIGN_HEADINGS, pose.direction(3), 0, 4);
            if (base == UNREPRESENTABLE) return base;
            int[] x = pose.vector(1, 0, 0), z = pose.vector(0, 0, 1);
            int winding = x[0] * z[2] - z[0] * x[2];
            return Math.floorMod(base * 4 + winding * meta, 16);
        }
        if (block.getClass() == BlockStairs.class) {
            if (meta < 0 || meta > 7 || !verticalAxis) return UNREPRESENTABLE;
            int encoded = indexOf(STAIR_FACES, pose.direction(STAIR_FACES[meta & 3]), 0, 4);
            if (encoded == UNREPRESENTABLE) return encoded;
            int half = pose.direction((meta & 4) == 0 ? UP : DOWN) == DOWN ? 4 : 0;
            return encoded | half;
        }
        if (block == Blocks.log || block == Blocks.log2 || block == Blocks.hay_block) {
            int species = meta & 3, axis = meta & 12;
            if (!nibble || block == Blocks.log2 && species > 1 || block == Blocks.hay_block && species != 0)
                return UNREPRESENTABLE;
            if (axis == 12) return block == Blocks.hay_block ? UNREPRESENTABLE : meta; // Logs encode all bark.
            int next = pose.direction(axis == 4 ? 5 : axis == 8 ? 3 : UP) >> 1;
            return species | (next == 2 ? 4 : next == 1 ? 8 : 0);
        }
        if (block == Blocks.quartz_block) {
            if (meta == 0) return up == UP ? 0 : UNREPRESENTABLE; // Plain quartz has different top and bottom.
            if (meta == 1) return verticalAxis ? 1 : UNREPRESENTABLE; // Chiseled ends share one native texture.
            if (meta < 2 || meta > 4) return UNREPRESENTABLE;
            int next = pose.direction(meta == 3 ? 5 : meta == 4 ? 3 : UP) >> 1;
            return next == 2 ? 3 : next == 1 ? 4 : 2;
        }
        if (block == Blocks.air || block.getClass() == Block.class) return nibble ? meta : UNREPRESENTABLE;
        return LEGACY;
    }

    private static boolean horizontalFace(int face) {
        return face >= 2 && face <= 5;
    }

    private static int indexOf(int[] faces, int face, int start, int end) {
        for (int i = start; i < end; i++) if (faces[i] == face) return i;
        return UNREPRESENTABLE;
    }

    /** Legacy detached/raw transformation is intentionally unchanged. */
    static boolean apply(CutTransforms.State state, CutTransforms.Transform pose) {
        Block b = state.block;
        int m = state.metadata;
        if (b == null || b == Blocks.air) return true;
        if (b instanceof BlockChest || b instanceof BlockFurnace
            || b instanceof BlockEnderChest
            || b instanceof BlockDispenser
            || b instanceof BlockHopper
            || b instanceof BlockPistonBase
            || b instanceof BlockPistonExtension
            || b instanceof BlockLadder
            || b == Blocks.wall_sign) {
            int face = m & 7, next = pose.direction(face);
            boolean horizontal = b instanceof BlockChest || b instanceof BlockFurnace
                || b instanceof BlockEnderChest
                || b instanceof BlockLadder
                || b == Blocks.wall_sign;
            if (face < 6 && (!horizontal || next >= 2)) state.metadata = (m & ~7) | next;
            return true;
        }
        if (b instanceof BlockLog || b instanceof BlockHay) {
            int axis = m & 12;
            if (axis != 12) {
                int next = pose.direction(axis == 4 ? 5 : axis == 8 ? 3 : 1) >> 1;
                state.metadata = (m & ~12) | (next == 2 ? 4 : next == 1 ? 8 : 0);
            }
            return true;
        }
        if (b instanceof BlockStairs) {
            int[] faces = { 5, 4, 3, 2 };
            int next = pose.direction(faces[m & 3]);
            for (int i = 0; i < 4; i++) if (faces[i] == next) state.metadata = (state.metadata & ~3) | i;
            if (pose.direction(1) == 0) state.metadata ^= 4;
            return true;
        }
        if (b instanceof BlockTorch || b instanceof BlockButton) {
            int[] faces = { b instanceof BlockButton ? 0 : -1, 5, 4, 3, 2, 1 };
            int face = m & 7;
            int minimum = b instanceof BlockButton ? 0 : 1;
            if (face >= minimum && face < faces.length) {
                int next = pose.direction(faces[face]);
                for (int i = minimum; i < faces.length; i++) if (faces[i] == next) state.metadata = (m & ~7) | i;
            }
            return true;
        }
        if (b == Blocks.standing_sign) {
            double angle = m * Math.PI / 8;
            double[] v = horizontal(pose, -Math.sin(angle), Math.cos(angle));
            if (v != null) state.metadata = Math.floorMod((int) Math.round(Math.atan2(-v[0], v[1]) * 8 / Math.PI), 16);
            return true;
        }
        if (b == Blocks.quartz_block && m >= 2 && m <= 4) {
            int next = pose.direction(m == 3 ? 5 : m == 4 ? 3 : 1) >> 1;
            state.metadata = next == 2 ? 3 : next == 1 ? 4 : 2;
            return true;
        }
        return b.getClass() == Block.class || b.getClass() == BlockAir.class;
    }

    private static double[] horizontal(CutTransforms.Transform pose, double x, double z) {
        int[] a = pose.vector(1, 0, 0), b = pose.vector(0, 0, 1);
        if (a[1] != 0 || b[1] != 0) return null;
        return new double[] { a[0] * x + b[0] * z, a[2] * x + b[2] * z };
    }
}
