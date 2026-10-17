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
