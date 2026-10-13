package appeng.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/** Test-only AE namespace fixture: exercises schema rejection without booting an AE grid. */
public final class GtmbAeStaticFixture extends Block {

    public GtmbAeStaticFixture() {
        super(Material.rock);
    }
}
