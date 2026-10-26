package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Cross-entry contracts: inherited metadata and detached spatial NBT must survive the blueprint bridge. */
public final class HologramBlockTransformParityRegression {

    private HologramBlockTransformParityRegression() {}

    public static void run() {
        try {
            parity(new InheritedStairs(), 0, 2);
            parity(new InheritedTorch(), 4, 1);
            parity(new InheritedPillar(), 4, 8);
            HologramBlockTransformApi.RotationResult neutral = HologramBlockTransformApi
                .rotate(Blocks.stone, 0, null, false, 1);
            SimpleAssert.that(neutral.handled, "known neutral stone does not become an unknown transform");
            NBTTagCompound skull = new NBTTagCompound();
            skull.setString("id", "Skull");
            skull.setByte("Rot", (byte) 0);
            skull.setString("Owner", "preserved");
            HologramBlockTransforms.Data original = new HologramBlockTransforms.Data(Blocks.skull, "", 1, skull, null);
            original.authoring = true;
            HologramBlockTransforms.Data turned = HologramBlockTransforms.turn(original, false);
            SimpleAssert.eq(4, (int) turned.tile.getByte("Rot"), "blueprint bridge publishes detached skull yaw");
            SimpleAssert.eq("preserved", turned.tile.getString("Owner"), "skull owner is not a direction");
            SimpleAssert.eq(0, (int) original.tile.getByte("Rot"), "blueprint bridge retains its original tile");
            SimpleAssert.eq(0, (int) skull.getByte("Rot"), "caller's NBT stays unchanged");
            NBTTagCompound opaque = new NBTTagCompound();
            opaque.setString("id", "Control");
            opaque.setString("Command", "unchanged");
            HologramBlockTransformApi.RotationResult unknown = HologramBlockTransformApi
                .rotate(Blocks.command_block, 3, opaque, false, 1);
            SimpleAssert.that(!unknown.handled, "opaque command payload is not claimed as a supported schema");
            SimpleAssert.eq(opaque, unknown.tile, "unknown result retains the entire payload");
        } catch (IOException failure) {
            throw new AssertionError("Shared metadata entry contracts", failure);
        }
    }

    private static void parity(Block block, int metadata, int expected) throws IOException {
        CutTransforms.State raw = new CutTransforms.State(block, metadata, null, 0, 0, 0, 0, 0, 0);
        SimpleAssert.that(
            HologramCutTransformDirections.apply(raw, new CutTransforms.Transform(1, 0, 0, 0)),
            "raw path recognizes native inherited geometry");
        HologramBlockTransforms.Data data = new HologramBlockTransforms.Data(block, "", metadata, null, null);
        data.authoring = true;
        HologramBlockTransforms.Data blueprint = HologramBlockTransforms.turn(data, false);
        SimpleAssert.eq(expected, raw.metadata, "raw entry matches the independent native direction expectation");
        SimpleAssert.eq(expected, blueprint.metadata, "blueprint shares the inherited native schema");
        SimpleAssert.that(blueprint.handled, "blueprint records recognized metadata capability");
    }

    private static final class InheritedStairs extends BlockStairs {

        InheritedStairs() {
            super(Blocks.stone, 0);
        }
    }

    private static final class InheritedTorch extends BlockTorch {
    }

    private static final class InheritedPillar extends BlockRotatedPillar {

        InheritedPillar() {
            super(Material.wood);
        }

        @Override
        protected net.minecraft.util.IIcon getSideIcon(int metadata) {
            return null;
        }
    }
}
