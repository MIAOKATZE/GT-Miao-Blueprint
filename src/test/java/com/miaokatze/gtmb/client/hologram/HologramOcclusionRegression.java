package com.miaokatze.gtmb.client.hologram;

import net.minecraft.init.Blocks;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Uses real vanilla opacity and block-shape contracts without a GL context. */
public final class HologramOcclusionRegression {

    private HologramOcclusionRegression() {}

    public static void run() {
        SimpleAssert.that(
            HologramOcclusion.fullCube(Blocks.stone, false, false, false),
            "adjacent opaque stone cubes still remove their shared interior faces");
        SimpleAssert.that(
            !HologramOcclusion.fullCube(Blocks.glass, false, false, false),
            "glass preserves the material faces visible through it");
        SimpleAssert.that(
            !HologramOcclusion.fullCube(Blocks.stone_slab, false, false, false),
            "a slab does not cover an entire neighboring voxel face");
        SimpleAssert.that(
            !HologramOcclusion.fullCube(Blocks.stone, true, false, false),
            "a narrow pipe retains neighboring casing faces even when the host is opaque");
        SimpleAssert.that(
            !HologramOcclusion.fullCube(Blocks.stone, false, true, false),
            "native workbench and cable hosts cannot hide the floor through their model openings");
        SimpleAssert.that(
            !HologramOcclusion.fullCube(Blocks.stone, false, false, true),
            "a placeholder icon does not cover an adjoining material face");
    }
}
