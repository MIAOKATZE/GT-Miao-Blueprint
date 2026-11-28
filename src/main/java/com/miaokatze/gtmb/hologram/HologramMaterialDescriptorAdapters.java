// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import bartworks.system.material.BWItemMetaGeneratedBlock;
import bartworks.system.material.BWMetaGeneratedBlocks;
import bartworks.system.material.BWMetaGeneratedBlocksCasing;

/** Reviewed material-type implementations; these rules do not depend on a machine or structure source. */
final class HologramMaterialDescriptorAdapters {

    private HologramMaterialDescriptorAdapters() {}

    static void register() {
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
            HologramBwMaterialCompatibility::actualDamage);
    }
}
