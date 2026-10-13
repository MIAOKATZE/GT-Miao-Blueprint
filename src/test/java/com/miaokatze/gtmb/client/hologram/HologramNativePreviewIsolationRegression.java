package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Verifies native NBT hydration and writes are isolated even for a registered community adapter. */
public final class HologramNativePreviewIsolationRegression {

    private HologramNativePreviewIsolationRegression() {}

    public static void run() {
        try {
            isolatedHydration();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native preview isolation fixture", failure);
        }
    }

    private static void isolatedHydration() throws ReflectiveOperationException {
        Block block = net.minecraft.init.Blocks.stone;
        String name = "minecraft:stone";
        ProbeTile[] foreign = { null };
        HologramNativeRenderRegistry.Adapter adapter = new HologramNativeRenderRegistry.Adapter() {

            public boolean supports(Block candidate) {
                return candidate == block;
            }

            public TileEntity createTile(Block candidate, int metadata, World isolated) {
                return foreign[0] == null ? new ProbeTile() : foreign[0];
            }

            public boolean requiresTile(Block candidate, int metadata) {
                return true;
            }
        };
        HologramNativeRenderRegistry.register(adapter);
        try {
            NBTTagCompound data = new NBTTagCompound();
            NBTTagList cells = new NBTTagList();
            NBTTagCompound cell = new NBTTagCompound();
            cell.setString("id", name);
            cell.setString("wantId", name);
            cell.setInteger("x", 7);
            cell.setInteger("y", 68);
            cell.setInteger("z", -3);
            cell.setInteger("meta", 2);
            cell.setInteger("wantMeta", 5);
            NBTTagCompound tile = new NBTTagCompound();
            tile.setInteger("x", 12345);
            NBTTagCompound material = new NBTTagCompound();
            material.setString("block", "minecraft:planks");
            tile.setTag("material", material);
            NBTTagCompound display = new NBTTagCompound();
            display.setTag("nativeTile", tile);
            cell.setTag("actualDisplay", display.copy());
            cell.setTag("targetDisplay", display.copy());
            cells.appendTag(cell);
            data.setTag("cells", cells);
            HologramState state = new HologramState(data);
            SimpleAssert.eq(1, state.cells.size(), "native fixture is retained as one snapshot cell");
            SimpleAssert.that(
                state.cells.get(0)
                    .block(false) == block,
                "native fixture registry resolves the planned block");
            SimpleAssert.that(
                state.cells.get(0)
                    .block(true) == block,
                "native fixture registry resolves the current block");
            SimpleAssert.that(
                state.cells.get(0).targetDisplay.hasKey("nativeTile", 10),
                "planned native payload remains a compound after snapshot copy");
            SimpleAssert.that(
                state.cells.get(0).actualDisplay.hasKey("nativeTile", 10),
                "current native payload remains a compound after snapshot copy");
            SimpleAssert
                .that(HologramNativeTiles.supported(block), "public adapter recognizes the registered block family");
            NBTTagCompound before = (NBTTagCompound) state.data.copy();
            HologramBlockAccess target = access(state, false);
            SimpleAssert
                .that(target.getBlock(7, 68, -3) == block, "bounded native access resolves the fixture coordinates");
            SimpleAssert.that(
                HologramNativeRenderRegistry.adapter(target.getBlock(7, 68, -3)) != null,
                "bounded native access retains the public adapter identity");
            ProbeTile planned = (ProbeTile) target.getTileEntity(7, 68, -3);
            SimpleAssert.that(
                planned != null,
                "registered family can hydrate an optional native tile without the mod: " + target.failure(7, 68, -3));
            SimpleAssert.eq(5, planned.seenMetadata, "planned world metadata reaches native deserialization");
            SimpleAssert
                .eq(7, planned.xCoord, "native tile receives snapshot coordinates, not saved source coordinates");
            SimpleAssert
                .that(planned == target.getTileEntity(7, 68, -3), "neighbor reads reuse the same detached tile");
            SimpleAssert.that(target.getTileEntity(100, 68, -3) == null, "out-of-snapshot reads expose no live tile");
            ProbeTile actual = (ProbeTile) access(state, true).getTileEntity(7, 68, -3);
            SimpleAssert.that(
                actual != planned && actual.getWorldObj() != planned.getWorldObj(),
                "current and target hydrate independent native tile/world instances");
            SimpleAssert.eq(2, actual.seenMetadata, "current native world has independent actual metadata");
            World isolated = planned.getWorldObj();
            SimpleAssert.that(
                !isolated.setBlock(7, 68, -3, net.minecraft.init.Blocks.air, 0, 3),
                "native world refuses block writes");
            SimpleAssert
                .that(!isolated.setBlockMetadataWithNotify(7, 68, -3, 9, 3), "native world refuses metadata writes");
            isolated.removeTileEntity(7, 68, -3);
            SimpleAssert
                .that(isolated.getTileEntity(7, 68, -3) == planned, "native callbacks cannot remove snapshot tiles");
            SimpleAssert.eq(5, isolated.getBlockMetadata(7, 68, -3), "writes leave snapshot metadata unchanged");
            SimpleAssert.eq(
                block.getLightOpacity(isolated, 7, 68, -3),
                isolated.getBlockLightOpacity(7, 68, -3),
                "native opacity reads the bounded block without a chunk provider");
            SimpleAssert
                .eq(0, isolated.getBlockLightOpacity(100, 68, -3), "outside snapshot opacity follows native air");
            SimpleAssert.eq(0, isolated.getBlockLightOpacity(7, -1, -3), "outside world height opacity remains zero");
            SimpleAssert.that(
                isolated.isSideSolid(7, 68, -3, net.minecraftforge.common.util.ForgeDirection.UP, false)
                    == block.isSideSolid(isolated, 7, 68, -3, net.minecraftforge.common.util.ForgeDirection.UP),
                "native solidity reads the bounded block without a chunk provider");
            SimpleAssert.that(
                !isolated.isSideSolid(100, 68, -3, net.minecraftforge.common.util.ForgeDirection.UP, true)
                    && !isolated.isSideSolid(100, 68, -3, net.minecraftforge.common.util.ForgeDirection.UP, false),
                "snapshot air has native solidity regardless of the caller default");
            SimpleAssert.that(
                isolated.isSideSolid(30000000, 68, -3, net.minecraftforge.common.util.ForgeDirection.UP, true)
                    && !isolated.isSideSolid(30000000, 68, -3, net.minecraftforge.common.util.ForgeDirection.UP, false),
                "outside world bounds preserves the native caller default");
            SimpleAssert.eq(before, state.data, "mutating a serializer's input never changes the source snapshot");
            SimpleAssert.eq(
                "minecraft:planks",
                state.cells.get(0).targetDisplay.getCompoundTag("nativeTile")
                    .getCompoundTag("material")
                    .getString("block"),
                "nested material NBT is copied before native hydration");
            foreign[0] = planned;
            SimpleAssert.that(
                access(state, false).getTileEntity(7, 68, -3) == null,
                "a provider returning an existing tile from another world is rejected");
            SimpleAssert
                .that(planned.getWorldObj() == isolated, "rejected provider never reassigns the existing tile's world");
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static HologramBlockAccess access(HologramState state, boolean current)
        throws ReflectiveOperationException {
        HologramBlockAccess access = new HologramBlockAccess(state, current);
        // The raw regression JVM has no FML provider/bootstrap context. Allocate an inert
        // shell and exercise only HologramRenderWorld's snapshot reads/write refusals;
        // the installed-mod GL smoke constructs and tests the fully initialized world.
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        HologramRenderWorld world = (HologramRenderWorld) unsafe.allocateInstance(HologramRenderWorld.class);
        world.isRemote = true;
        java.lang.reflect.Field cells = HologramRenderWorld.class.getDeclaredField("access");
        cells.setAccessible(true);
        cells.set(world, access);
        java.lang.reflect.Field renderWorld = HologramBlockAccess.class.getDeclaredField("renderWorld");
        renderWorld.setAccessible(true);
        renderWorld.set(access, world);
        return access;
    }

    public static final class ProbeTile extends TileEntity {

        int seenMetadata;

        public void readFromNBT(NBTTagCompound tag) {
            super.readFromNBT(tag);
            seenMetadata = getWorldObj().getBlockMetadata(xCoord, yCoord, zCoord);
            tag.getCompoundTag("material")
                .setString("block", "mutated serializer input");
        }
    }
}
