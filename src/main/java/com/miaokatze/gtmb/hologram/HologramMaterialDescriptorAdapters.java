package com.miaokatze.gtmb.hologram;

import bartworks.system.material.BWItemMetaGeneratedBlock;
import bartworks.system.material.BWMetaGeneratedBlocks;
import bartworks.system.material.BWMetaGeneratedBlocksCasing;
import bartworks.system.material.TileEntityMetaGeneratedBlock;
import gregtech.common.blocks.BlockCasingsAbstract;

/** Reviewed material-type implementations; these rules do not depend on a machine or structure source. */
final class HologramMaterialDescriptorAdapters {

    private HologramMaterialDescriptorAdapters() {}

    static void register() {
        HologramMaterialDescriptor.registerNative(BlockCasingsAbstract.class, metadata -> metadata);
        HologramMaterialDescriptor.registerExact(
            BWMetaGeneratedBlocksCasing.class,
            BWItemMetaGeneratedBlock.class,
            BWMetaGeneratedBlocks.class,
            metadata -> {
                // The native item writes the positive short to both world metadata and the placed tile.
                if (metadata < 1 || metadata > Short.MAX_VALUE)
                    throw new IllegalArgumentException("Casing material must be a positive short");
                return metadata;
            },
            tile -> tile instanceof TileEntityMetaGeneratedBlock ? ((TileEntityMetaGeneratedBlock) tile).mMetaData : 0);
    }
}
