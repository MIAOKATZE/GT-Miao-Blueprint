package appeng.block;

import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Checks the real AE analyzer's native factory boundary without starting a native tile. */
public final class GtmbAeOwnerFixture extends AEBaseTileBlock {

    public static Thread owner;
    public static int factories;

    public GtmbAeOwnerFixture() {
        super(Material.rock);
    }

    @Override
    public Class<? extends TileEntity> getTileEntityClass() {
        return appeng.tile.misc.TileInterface.class;
    }

    @Override
    public TileEntity createTileEntity(World world, int metadata) {
        if (Thread.currentThread() != owner) throw new AssertionError("Native AE factory escaped its owner thread");
        factories++;
        return null;
    }
}
