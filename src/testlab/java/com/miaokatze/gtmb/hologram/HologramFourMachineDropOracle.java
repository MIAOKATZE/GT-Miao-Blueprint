// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.BlockMachines;

/** Native drop callbacks operate only on deep NBT copies, never on the business world's live tile. */
final class HologramFourMachineDropOracle extends HologramResourceWorld {

    static final class Quote {

        final List<ItemStack> items;
        final NBTTagCompound evidence;

        Quote(List<ItemStack> items, NBTTagCompound evidence) {
            this.items = items;
            this.evidence = evidence;
        }
    }

    private final Block sourceBlock;
    private final int sourceMetadata, x, y, z;
    private final List<ItemStack> emitted = new ArrayList<>();
    private boolean present = true;

    private HologramFourMachineDropOracle(Block block, int metadata, int x, int y, int z) {
        super(block, metadata);
        sourceBlock = block;
        sourceMetadata = metadata;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    static Quote inspect(WorldServer live, int[] position) throws Exception {
        int x = position[0], y = position[1], z = position[2];
        Block block = live.getBlock(x, y, z);
        int metadata = live.getBlockMetadata(x, y, z);
        TileEntity original = live.getTileEntity(x, y, z);
        NBTTagCompound originalTag = tag(original);
        IMetaTileEntity originalMte = original instanceof IGregTechTileEntity gt ? gt.getMetaTileEntity() : null;
        require(original == null || originalMte != null, "Drop oracle encountered an unreviewed non-GT tile");
        GregTechAPI.addDummyWorld(HologramFourMachineDropOracle.class);
        HologramFourMachineDropOracle isolated = HologramResourceWorld
            .create(() -> new HologramFourMachineDropOracle(block, metadata, x, y, z));
        ThreadLocal<Object> temporary = temporaryTile();
        Object previousTemporary = temporary.get();
        List<ItemStack> drops = new ArrayList<>();
        try {
            if (original != null) {
                TileEntity detached = original.getClass()
                    .getDeclaredConstructor()
                    .newInstance();
                detached.setWorldObj(isolated);
                isolated.tile = detached;
                detached.readFromNBT((NBTTagCompound) originalTag.copy());
                require(
                    detached != original && detached.getWorldObj() == isolated
                        && detached instanceof IGregTechTileEntity,
                    "Native drop oracle tile was not detached");
                IGregTechTileEntity copy = (IGregTechTileEntity) detached;
                require(
                    copy.getMetaTileEntity() != null && copy.getMetaTileEntity() != originalMte
                        && copy.getMetaTileEntity() != GregTechAPI.METATILEENTITIES[copy.getMetaTileID()]
                        && originalMte.getClass()
                            .equals(
                                copy.getMetaTileEntity()
                                    .getClass()),
                    "Native drop oracle reused a live or registry MTE");
                require(
                    originalTag.equals(tag(detached)),
                    "Native oracle clone did not preserve complete persisted NBT");
            }
            // GT harvestBlock calls super first, then setBlockToAir. Thus getDrops (including native cover/item
            // NBT) precedes breakBlock's independent inventory EntityItems and onBlockDestroyed callback.
            for (ItemStack drop : block.getDrops(isolated, x, y, z, metadata, 0)) drops.add(drop.copy());
            if (original != null) {
                block.breakBlock(isolated, x, y, z, block, metadata);
                for (ItemStack drop : isolated.emitted) drops.add(drop.copy());
            }
        } finally {
            if (previousTemporary == null) temporary.remove();
            else temporary.set(previousTemporary);
            require(
                live.getBlock(x, y, z) == block && live.getBlockMetadata(x, y, z) == metadata
                    && live.getTileEntity(x, y, z) == original
                    && originalTag.equals(tag(original))
                    && (original == null || ((IGregTechTileEntity) original).getMetaTileEntity() == originalMte),
                "Isolated drop oracle changed the live block, TE, MTE reference or complete NBT");
        }
        NBTTagCompound evidence = new NBTTagCompound();
        evidence.setIntArray("position", position.clone());
        evidence.setString(
            "tileClass",
            original == null ? ""
                : original.getClass()
                    .getName());
        evidence.setBoolean("deepNbtClone", original != null);
        evidence.setBoolean("completeCloneNbtMatched", true);
        evidence.setBoolean("liveCellAndNbtUnchanged", true);
        evidence.setBoolean("nativeTemporaryTileRestored", temporary.get() == previousTemporary);
        evidence.setInteger("nativeBreakEntityStacks", isolated.emitted.size());
        evidence.setString("source", "Detached native getDrops then breakBlock; covers remain in native item NBT");
        return new Quote(drops, evidence);
    }

    private static NBTTagCompound tag(TileEntity tile) {
        NBTTagCompound tag = new NBTTagCompound();
        if (tile != null) tile.writeToNBT(tag);
        return (NBTTagCompound) tag.copy();
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> temporaryTile() throws ReflectiveOperationException {
        Field field = BlockMachines.class.getDeclaredField("mTemporaryTileEntity");
        field.setAccessible(true);
        return (ThreadLocal<Object>) field.get(null);
    }

    private boolean at(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }

    @Override
    public boolean blockExists(int x, int y, int z) {
        return at(x, y, z);
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        return present && at(x, y, z) ? sourceBlock : Blocks.air;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        return present && at(x, y, z) ? sourceMetadata : 0;
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return present && at(x, y, z) ? tile : null;
    }

    @Override
    public void removeTileEntity(int x, int y, int z) {
        require(at(x, y, z), "Native drop callback escaped its isolated cell");
        tile = null;
        present = false;
    }

    @Override
    public boolean spawnEntityInWorld(Entity entity) {
        require(entity instanceof EntityItem && entity.worldObj == this, "Unexpected native drop oracle entity");
        ItemStack item = ((EntityItem) entity).getEntityItem();
        require(item != null && item.stackSize > 0, "Invalid native breakBlock item emission");
        emitted.add(item.copy());
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
