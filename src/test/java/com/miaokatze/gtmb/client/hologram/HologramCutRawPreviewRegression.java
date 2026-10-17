package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.client.registry.RenderingRegistry;

/** Native reader boundary without an FML provider or GL context; the native client probe covers actual drawing. */
public final class HologramCutRawPreviewRegression {

    private HologramCutRawPreviewRegression() {}

    public static void run() throws ReflectiveOperationException {
        registeredHandlerRouting();
        AtomicInteger failures = new AtomicInteger();
        HologramCutRawPreviewWorld world = shell((cell, error) -> failures.incrementAndGet());
        NBTTagCompound selectedAir = new NBTTagCompound();
        selectedAir.setString("block", "minecraft:air");
        selectedAir.setInteger("x", 4);
        selectedAir.setInteger("y", 3);
        selectedAir.setInteger("z", 4);
        world.cells.put(HologramCutRawPreviewWorld.key(4, 3, 4), new HologramCutRawPreviewWorld.Cell(selectedAir));
        check(world.blockExists(4, 3, 4) && world.isAirBlock(4, 3, 4), "selected air remains in the raw preview mask");
        check(
            !world.blockExists(3, 3, 4) && world.cell(3, 3, 4) == null,
            "unselected gaps have no raw preview cell or renderable payload");
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", 2);
        row.setInteger("y", 3);
        row.setInteger("z", 4);
        row.setString("block", "minecraft:chest");
        row.setInteger("meta", 5);
        NBTTagCompound tile = new NBTTagCompound();
        tile.setString("id", "unknown-addon:full-native-tile");
        tile.setInteger("x", 12345);
        tile.setInteger("y", 99);
        tile.setInteger("z", -6789);
        tile.setString("ownerUUID", "preserve-owner-exactly");
        tile.setString("networkKey", "preserve-network-exactly");
        NBTTagCompound unknown = new NBTTagCompound();
        unknown.setString("privateAddonField", "full-private-state");
        unknown.setByteArray("opaque", new byte[] { 0, -1, 42 });
        tile.setTag("unknown", unknown);
        row.setTag("tile", tile);
        NBTTagCompound original = (NBTTagCompound) row.copy();
        HologramCutRawPreviewWorld.Cell cell = new HologramCutRawPreviewWorld.Cell(row);
        Field block = HologramCutRawPreviewWorld.Cell.class.getDeclaredField("block");
        block.setAccessible(true);
        block.set(cell, new ProbeBlock());
        world.cells.put(HologramCutRawPreviewWorld.key(2, 3, 4), cell);
        ProbeTile.reads = ProbeTile.lifecycle = 0;
        ProbeTile loaded = (ProbeTile) world.getTileEntity(2, 3, 4);
        check(loaded != null && loaded.getWorldObj() == world, "arbitrary native family uses only the detached world");
        check(loaded.xCoord == 2 && loaded.yCoord == 3 && loaded.zCoord == 4, "only detached root XYZ are relocated");
        check(
            loaded.seen.getString("ownerUUID")
                .equals("preserve-owner-exactly")
                && loaded.seen.getString("networkKey")
                    .equals("preserve-network-exactly")
                && loaded.seen.getCompoundTag("unknown")
                    .equals(unknown),
            "complete unrecognized payload reaches the native reader");
        check(
            row.equals(original) && cell.raw.equals(original),
            "a mutating native reader cannot modify either raw source");
        check(
            world.getTileEntity(2, 3, 4) == loaded && ProbeTile.reads == 1,
            "native tiles are cached rather than recreated per frame");
        check(
            !world.setBlock(2, 3, 4, net.minecraft.init.Blocks.air, 0, 3) && world.getBlockMetadata(2, 3, 4) == 5,
            "snapshot writes cannot change raw cells");
        check(
            world.setBlockMetadataWithNotify(2, 3, 4, 7, 4) && world.getBlockMetadata(2, 3, 4) == 7,
            "render metadata impersonation is a disposable local overlay");
        world.endRender();
        check(world.getBlockMetadata(2, 3, 4) == 5, "ending native render restores source metadata");
        loaded = null;
        world.release();
        check(
            ProbeTile.lifecycle == 0 && world.cells.isEmpty(),
            "dispose never dispatches validate, tick, or unload lifecycle");

        world = shell((value, error) -> failures.incrementAndGet());
        cell = new HologramCutRawPreviewWorld.Cell(row);
        block.set(cell, new ProbeBlock());
        cell.raw.getCompoundTag("tile")
            .setBoolean("fail", true);
        NBTTagCompound failingOriginal = (NBTTagCompound) cell.raw.copy();
        world.cells.put(HologramCutRawPreviewWorld.key(2, 3, 4), cell);
        check(
            world.getTileEntity(2, 3, 4) == null && failures.get() == 1,
            "native assertion failure becomes a visible diagnostic");
        check(
            world.getTileEntity(2, 3, 4) == null && failures.get() == 1,
            "failed native factory is not retried every frame");
        check(cell.raw.equals(failingOriginal) && row.equals(original), "failed reader retains the complete raw cache");
        world.release();
    }

    /** Chisel-like constructors change the render type shared by every block in their family. */
    @SuppressWarnings("unchecked")
    private static void registeredHandlerRouting() throws ReflectiveOperationException {
        RenderingRegistry registry = RenderingRegistry.instance();
        Field handlersField = RenderingRegistry.class.getDeclaredField("blockRenderers");
        Field nextIdField = RenderingRegistry.class.getDeclaredField("nextRenderId");
        handlersField.setAccessible(true);
        nextIdField.setAccessible(true);
        Map<Integer, ISimpleBlockRenderingHandler> handlers = (Map<Integer, ISimpleBlockRenderingHandler>) handlersField
            .get(registry);
        int previousNextId = nextIdField.getInt(registry);
        RoutingHandler.constructions = RoutingHandler.draws = 0;
        RoutingHandler official = new RoutingHandler();
        int registeredId = RoutingHandler.renderId;
        RenderingRegistry.registerBlockHandler(official);
        try {
            Block block = new RoutingBlock();
            Method choose = HologramCutRawPreviewSession.class.getDeclaredMethod("handler", Block.class);
            choose.setAccessible(true);
            HologramCutRawPreviewSession preview = new HologramCutRawPreviewSession();
            for (int pass = 0; pass < 2; pass++) {
                Object selected = choose.invoke(preview, block);
                check(selected == official, "raw preview selects the same native handler as world dispatch");
                check(
                    RoutingHandler.constructions == 1 && block.getRenderType() == registeredId,
                    "preview lookup cannot rerun a constructor that changes the family render ID");
                check(
                    registry.renderWorldBlock(null, null, 0, 0, 0, block, block.getRenderType()),
                    "ordinary world geometry still reaches the registered handler after preview lookup");
                check(
                    registry.renderItemAsFull3DBlock(block.getRenderType()),
                    "ordinary inventory blocks retain their registered 3D rendering after preview lookup");
            }
            preview.release();
            check(
                choose.invoke(new HologramCutRawPreviewSession(), block) == official
                    && block.getRenderType() == registeredId
                    && RoutingHandler.constructions == 1
                    && RoutingHandler.draws == 2,
                "releasing and reopening a preview preserves process-wide native routing");
        } finally {
            handlers.remove(registeredId);
            nextIdField.setInt(registry, previousNextId);
        }
    }

    @SuppressWarnings("unchecked")
    private static HologramCutRawPreviewWorld shell(BiConsumer<HologramCutRawPreviewWorld.Cell, Throwable> failure)
        throws ReflectiveOperationException {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        HologramCutRawPreviewWorld world = (HologramCutRawPreviewWorld) ((sun.misc.Unsafe) singleton.get(null))
            .allocateInstance(HologramCutRawPreviewWorld.class);
        world.isRemote = true;
        for (String name : new String[] { "cells", "renderMetadata" }) {
            Field field = HologramCutRawPreviewWorld.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(world, new LinkedHashMap<String, Object>());
        }
        Field callback = HologramCutRawPreviewWorld.class.getDeclaredField("failure");
        callback.setAccessible(true);
        callback.set(world, failure);
        return world;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class ProbeBlock extends BlockContainer {

        ProbeBlock() {
            super(Material.wood);
        }

        @Override
        public TileEntity createNewTileEntity(World world, int meta) {
            return new ProbeTile();
        }
    }

    private static final class RoutingBlock extends Block {

        RoutingBlock() {
            super(Material.rock);
        }

        @Override
        public int getRenderType() {
            return RoutingHandler.renderId;
        }
    }

    public static final class RoutingHandler implements ISimpleBlockRenderingHandler {

        static int renderId, constructions, draws;

        public RoutingHandler() {
            constructions++;
            renderId = RenderingRegistry.getNextAvailableRenderId();
        }

        public boolean renderWorldBlock(IBlockAccess world, int x, int y, int z, Block block, int modelId,
            RenderBlocks renderer) {
            draws++;
            return true;
        }

        public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {}

        public boolean shouldRender3DInInventory(int modelId) {
            return true;
        }

        public int getRenderId() {
            return renderId;
        }
    }

    public static final class ProbeTile extends TileEntity {

        static int reads, lifecycle;
        NBTTagCompound seen;

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            super.readFromNBT(tag);
            reads++;
            seen = (NBTTagCompound) tag.copy();
            tag.getCompoundTag("unknown")
                .setString("privateAddonField", "reader mutated its input");
            if (tag.getBoolean("fail")) throw new AssertionError("native reader failure fixture");
        }

        @Override
        public void validate() {
            lifecycle++;
        }

        @Override
        public void invalidate() {
            lifecycle++;
        }

        @Override
        public void onChunkUnload() {
            lifecycle++;
        }

        @Override
        public void updateEntity() {
            lifecycle++;
        }
    }
}
