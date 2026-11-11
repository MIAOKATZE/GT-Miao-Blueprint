// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package appeng.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/** Test-only AE namespace fixture: exercises schema and material boundaries without booting an AE grid. */
public final class GtmbAeStaticFixture extends Block {

    private final int dropDamage;

    public GtmbAeStaticFixture() {
        this(0);
    }

    public GtmbAeStaticFixture(int dropDamage) {
        super(Material.rock);
        this.dropDamage = dropDamage;
    }

    @Override
    public int damageDropped(int metadata) {
        return dropDamage;
    }
}
