package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;

/** Exact audited native classes with scalar metadata; mod subclasses are deliberately not inferred as neutral. */
final class HologramMetadataNeutral {

    private HologramMetadataNeutral() {}

    /** 0 = unknown, 1 = scalar cube, 2 = scalar metadata with a fixed native up/support plane. */
    static int kind(Block block) {
        if (block == null) return 0;
        String name = block.getClass()
            .getName();
        if (!name.startsWith("net.minecraft.block.")) return 0;
        switch (name.substring("net.minecraft.block.".length())) {
            case "BlockStone":
            case "BlockOre":
            case "BlockCompressed":
            case "BlockCompressedPowered":
            case "BlockColored":
            case "BlockGlass":
            case "BlockStainedGlass":
            case "BlockHardenedClay":
            case "BlockClay":
            case "BlockGlowstone":
            case "BlockGravel":
            case "BlockIce":
            case "BlockPackedIce":
            case "BlockNetherrack":
            case "BlockObsidian":
            case "BlockSand":
            case "BlockSnowBlock":
            case "BlockSponge":
            case "BlockSilverfish":
            case "BlockStoneBrick":
            case "BlockRedstoneLight":
            case "BlockRedstoneOre":
            case "BlockWeb":
            case "BlockWood":
            case "BlockOldLeaf":
            case "BlockNewLeaf":
                return 1;
            case "BlockGrass":
            case "BlockDirt":
            case "BlockMycelium":
            case "BlockTNT":
            case "BlockBookshelf":
            case "BlockSandStone":
            case "BlockMelon":
            case "BlockNote":
            case "BlockJukebox":
            case "BlockWorkbench":
            case "BlockSoulSand":
            case "BlockFence":
            case "BlockWall":
            case "BlockPane":
            case "BlockStainedGlassPane":
            case "BlockDragonEgg":
            case "BlockFire":
            case "BlockDynamicLiquid":
            case "BlockStaticLiquid":
            case "BlockRedstoneWire":
            case "BlockTripWire":
            case "BlockPressurePlate":
            case "BlockPressurePlateWeighted":
            case "BlockSnow":
            case "BlockCarpet":
            case "BlockFarmland":
            case "BlockCake":
            case "BlockCrops":
            case "BlockCarrot":
            case "BlockPotato":
            case "BlockNetherWart":
            case "BlockSapling":
            case "BlockTallGrass":
            case "BlockDeadBush":
            case "BlockFlower":
            case "BlockMushroom":
            case "BlockStem":
            case "BlockLilyPad":
            case "BlockReed":
            case "BlockCactus":
            case "BlockBeacon":
            case "BlockDaylightDetector":
            case "BlockCauldron":
            case "BlockEnchantmentTable":
            case "BlockFlowerPot":
            case "BlockEndPortal":
                return 2;
            default:
                // In particular command blocks, moving pistons and spawners contain opaque spatial payloads.
                return 0;
        }
    }
}
