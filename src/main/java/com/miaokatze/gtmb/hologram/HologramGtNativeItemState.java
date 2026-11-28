// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.stats.StatBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import com.mojang.authlib.GameProfile;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.modularui.IControllerWithOptionalFeatures;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.blocks.ItemMachines;

/** Native item state is paid as part of the exact material, never pasted as free machine configuration. */
final class HologramGtNativeItemState {

    private HologramGtNativeItemState() {}

    static ItemStack sample(Block sourceBlock, IGregTechTileEntity source) {
        // Observation belongs to the block actually hosting this tile, including independent native fixtures.
        ItemStack item = new ItemStack(sourceBlock, 1, source.getMetaTileID());
        IMetaTileEntity machine = source.getMetaTileEntity();
        if (machine == null) return item;
        NBTTagCompound state = new NBTTagCompound();
        machine.setItemNBT(state);
        if (!state.hasNoTags()) item.setTagCompound(state);
        return item;
    }

    /** Descriptor-owned results of the complete native item placement, never a reusable live machine. */
    static final class Baseline {

        final NBTTagCompound item, tile;
        private final NBTTagCompound credited = new NBTTagCompound();

        Baseline(NBTTagCompound item, NBTTagCompound tile) {
            this.item = item;
            this.tile = tile;
        }

        boolean owns(NBTTagCompound selected, String key) {
            boolean same = !protectedField(key) && selected.hasKey(key)
                && tile.hasKey(key)
                && selected.getTag(key)
                    .equals(tile.getTag(key));
            if (same) credited.setTag(
                key,
                tile.getTag(key)
                    .copy());
            return same;
        }

        boolean matchesCredits(TileEntity target) throws IOException {
            if (credited.hasNoTags()) return true;
            NBTTagCompound actual = HologramNativeCapture.capture(target);
            for (Object raw : credited.func_150296_c()) {
                String key = (String) raw;
                if (!credited.getTag(key)
                    .equals(actual.getTag(key))) return false;
            }
            return true;
        }
    }

    /** The real ItemMachines path owns factories, default modes, connections and placement callbacks. */
    static Baseline baseline(ItemStack paid) throws IOException {
        return baseline(paid, null);
    }

    static void verifyMode(ItemStack paid, int mode) throws IOException {
        baseline(paid, mode);
    }

    private static Baseline baseline(ItemStack paid, Integer mode) throws IOException {
        if (paid == null || !(paid.getItem() instanceof ItemMachines))
            return new Baseline(new NBTTagCompound(), new NBTTagCompound());
        if (HologramRecovery.hasSeal(paid))
            throw new IOException("A sealed original must use the complete recovery contract");
        NBTTagCompound supplied = paid.getTagCompound();
        if (supplied != null) for (Object raw : supplied.func_150296_c()) if (protectedField((String) raw))
            throw new IOException("GT native material cannot supply source authority: " + raw);
        // Resource inspection omits buffers. Validate their existing energy projection first: an
        // intrinsic paid item may not silently replace malformed charge with a normalized empty value.
        NBTTagCompound nativeInput = HologramBlueprintNbt.nativePayload(supplied);
        HologramEnergyPayload.Prepared energy = HologramEnergyPayload.prepare(null, nativeInput);
        if (nativeInput != null && !nativeInput.equals(energy.itemPaid)) throw new IOException(
            "GT native item state cannot be verified",
            new IOException(String.join("; ", energy.warnings)));
        IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(paid);
        if (prototype == null) throw new IOException("GT native item is unregistered");
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        RunnableMachineUpdate.setCurrentThreadEnabled(false);
        try {
            GregTechAPI.addDummyWorld(PlacementWorld.class);
            PlacementWorld world = HologramResourceWorld.create(PlacementWorld::new);
            ItemMachines item = (ItemMachines) paid.getItem();
            ItemStack material = paid.copy();
            material.stackSize = 1;
            material.setTagCompound(nativeInput);
            if (!item.placeBlockAt(
                material,
                new PlacementPlayer(world),
                world,
                0,
                128,
                0,
                1,
                .5f,
                .5f,
                .5f,
                item.getMetadata(paid.getItemDamage())))
                throw new IOException("GT native item refused sandbox placement");
            TileEntity tile = world.getTileEntity(0, 128, 0);
            if (!(tile instanceof IGregTechTileEntity nativeTile) || nativeTile.getMetaTileID() != paid.getItemDamage())
                throw new IOException("GT native placement did not create the paid machine");
            IMetaTileEntity machine = nativeTile.getMetaTileEntity();
            if (machine == null || machine == prototype || machine.getClass() != prototype.getClass())
                throw new IOException("GT native item factory did not create an independent machine");
            if (mode != null) {
                // The native persistence contract owns the selected value. There is no public mode-count API,
                // so do not invent a domain by cycling a prototype or consulting client display methods.
                NBTTagCompound persisted = new NBTTagCompound();
                tile.writeToNBT(persisted);
                persisted.setInteger("machineMode", mode);
                tile.readFromNBT(persisted);
                machine = nativeTile.getMetaTileEntity();
                if (!(machine instanceof IControllerWithOptionalFeatures features)
                    || !features.supportsMachineModeSwitch()
                    || features.getMachineMode() != mode)
                    throw new IOException("GT native persistence did not retain selected machineMode: " + mode);
            }
            NBTTagCompound result = new NBTTagCompound();
            machine.setItemNBT(result);
            NBTTagCompound state = new NBTTagCompound();
            tile.writeToNBT(state);
            return new Baseline(result, state);
        } catch (RuntimeException | LinkageError invalid) {
            throw new IOException("GT native item state cannot be verified", invalid);
        } finally {
            RunnableMachineUpdate.setCurrentThreadEnabled(updates);
        }
    }

    /** Compatibility view for callers interested only in the native portable item state. */
    static NBTTagCompound canonical(ItemStack paid) throws IOException {
        return baseline(paid).item;
    }

    static boolean paidField(NBTTagCompound state, String key, ItemStack paid, NBTTagCompound nativeItem)
        throws IOException {
        if (HologramRecovery.hasSeal(paid) || protectedField(key) || !nativeItem.hasKey(key)) return false;
        NBTTagCompound item = HologramBlueprintNbt.nativePayload(paid.getTagCompound());
        if (item == null || !item.hasKey(key)
            || !item.getTag(key)
                .equals(nativeItem.getTag(key)))
            throw new IOException("GT native item state differs after native initialization: " + key);
        if (!state.getTag(key)
            .equals(nativeItem.getTag(key)))
            throw new IOException("GT retained state differs from the paid native item: " + key);
        return true;
    }

    private static boolean protectedField(String key) {
        return key.equals("id") || key.equals("mID")
            || key.equals("nbtVersion")
            || key.equals("mFacing")
            || key.equals("x")
            || key.equals("y")
            || key.equals("z")
            || key.startsWith("mOwner")
            || key.equals(HologramRecovery.TAG)
            || key.equals(HologramFullPreservation.TAG)
            || key.equals(HologramNativeConfiguration.TAG)
            || HologramNativeConfiguration.bindingField(key)
            || key.equals(HologramNetworkBindings.CAPTURE_ANCHOR)
            || key.equals("anchorDim")
            || key.equals("anchorX")
            || key.equals("anchorY")
            || key.equals("anchorZ")
            || key.equals("QT_AnchorDim")
            || key.equals("QT_AnchorX")
            || key.equals("QT_AnchorY")
            || key.equals("QT_AnchorZ");
    }

    static NBTTagCompound placementItem(ItemStack paid, NBTTagCompound optional) throws IOException {
        if (HologramRecovery.hasSeal(paid))
            throw new IOException("A sealed original must use the complete recovery contract");
        NBTTagCompound result = paid.getItem() instanceof ItemMachines
            ? HologramBlueprintNbt.nativePayload(paid.getTagCompound())
            : null;
        if (result == null) return optional == null ? null : (NBTTagCompound) optional.copy();
        if (optional != null) for (Object raw : optional.func_150296_c()) {
            String key = (String) raw;
            if (result.hasKey(key) && !result.getTag(key)
                .equals(optional.getTag(key)))
                throw new IOException("GT selected configuration conflicts with the paid native item: " + key);
            result.setTag(
                key,
                optional.getTag(key)
                    .copy());
        }
        return result;
    }

    static boolean matches(TileEntity tile, ItemStack paid) throws IOException {
        if (paid == null || !(paid.getItem() instanceof ItemMachines) || HologramRecovery.hasSeal(paid)) return true;
        if (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileID() != paid.getItemDamage()) return false;
        NBTTagCompound actual = new NBTTagCompound();
        if (gt.getMetaTileEntity() == null) return false;
        gt.getMetaTileEntity()
            .setItemNBT(actual);
        if (!paid.hasTagCompound() && actual.hasNoTags()) return true;
        return canonical(paid).equals(actual);
    }

    /** Prepared owns this immutable expected state; repeated world checks never construct detached machines. */
    static boolean matches(TileEntity tile, ItemStack paid, NBTTagCompound canonical) throws IOException {
        if (paid == null || !(paid.getItem() instanceof ItemMachines) || HologramRecovery.hasSeal(paid)) return true;
        if (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileID() != paid.getItemDamage()
            || gt.getMetaTileEntity() == null
            || canonical == null) return false;
        NBTTagCompound actual = new NBTTagCompound();
        gt.getMetaTileEntity()
            .setItemNBT(actual);
        return canonical.equals(actual);
    }

    static boolean matchesBaseline(TileEntity tile, ItemStack paid, Baseline baseline) throws IOException {
        return baseline == null ? HologramRecovery.hasSeal(paid)
            : matches(tile, paid, baseline.item) && baseline.matchesCredits(tile);
    }

    /** A writable, server-side single-cell world; no live player, chunk, file, network or inventory is attached. */
    private static final class PlacementWorld extends HologramResourceWorld {

        private Block placed = Blocks.air;
        private int placedMeta;

        PlacementWorld() {
            super(Blocks.air, 0);
        }

        private boolean at(int x, int y, int z) {
            return x == 0 && y == 128 && z == 0;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return y >= 0 && y < 256;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return at(x, y, z) ? placed : Blocks.air;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return at(x, y, z) ? placedMeta : 0;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return at(x, y, z) ? tile : null;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
            if (!at(x, y, z)) throw new IllegalStateException("GT native item wrote outside its sandbox cell");
            placed = block;
            placedMeta = metadata;
            tile = block.hasTileEntity(metadata) ? block.createTileEntity(this, metadata) : null;
            if (tile != null) setTileEntity(x, y, z, tile);
            if (block != Blocks.air) block.onBlockAdded(this, x, y, z);
            return true;
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
            if (!at(x, y, z) || placed == Blocks.air) return false;
            placedMeta = metadata;
            if (tile != null) tile.blockMetadata = metadata;
            return true;
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity value) {
            if (!at(x, y, z)) throw new IllegalStateException("GT native item installed a foreign sandbox tile");
            tile = value;
            if (value != null) {
                value.setWorldObj(this);
                value.xCoord = x;
                value.yCoord = y;
                value.zCoord = z;
                value.blockMetadata = placedMeta;
            }
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            if (at(x, y, z)) tile = null;
        }
    }

    private static final class PlacementPlayer extends EntityPlayer {

        PlacementPlayer(World world) {
            super(
                world,
                new GameProfile(UUID.fromString("bca5e26b-40aa-49a3-a448-305788c3eb44"), "[GTMB Native Item]"));
            setPosition(.5, 128, .5);
        }

        @Override
        public void addChatMessage(IChatComponent message) {}

        @Override
        public void addStat(StatBase statistic, int amount) {}

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return false;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 128, 0);
        }
    }
}
