package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;

/** A present voxel is not necessarily an opaque face: cables and workbenches leave their neighbors visible. */
final class HologramOcclusion {

    private HologramOcclusion() {}

    static boolean fullCube(Block block, boolean partial, boolean nativeModel, boolean iconOnly) {
        // Native geometry can leave openings even when its host reports solid attachment sides.
        // Keep adjacent faces until that renderer supplies an audited face-coverage contract.
        return block != null && !partial
            && !nativeModel
            && !iconOnly
            && block.isOpaqueCube()
            && block.renderAsNormalBlock();
    }
}
