package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;

/** A present voxel is not necessarily an opaque face: cables and workbenches leave their neighbors visible. */
final class HologramOcclusion {

    private HologramOcclusion() {}

    static boolean fullCube(Block block, boolean partial, boolean nativeModel, boolean iconOnly) {
        // Native geometry can leave openings even when its host reports solid attachment sides.
        // Exact opaque Chisel CTM cubes cover their faces; partial/native subclasses do not inherit that rule.
        return block != null && !partial
            && (!nativeModel || HologramChiselNativeRender.opaqueCube(block))
            && !iconOnly
            && block.isOpaqueCube()
            && block.renderAsNormalBlock();
    }
}
