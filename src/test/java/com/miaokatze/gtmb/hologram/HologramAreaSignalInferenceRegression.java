package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Production inference reads source geometry without constructing its live controller or registration prototype. */
public final class HologramAreaSignalInferenceRegression {

    public static void main(String[] args) throws Exception {
        if (!com.miaokatze.gtmb.testutil.MinecraftTestBootstrap
            .enter(HologramAreaSignalInferenceRegression.class, args)) return;
        run();
    }

    static void run() {
        String previousName = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            runServer();
        } finally {
            Thread.currentThread()
                .setName(previousName);
        }
    }

    private static void runServer() {
        int id = 32759;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[id];
        SceneController prototype = new SceneController();
        GregTechAPI.METATILEENTITIES[id] = prototype;
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            SceneWorld world = (SceneWorld) ((sun.misc.Unsafe) field.get(null)).allocateInstance(SceneWorld.class);
            world.blocks = new HashMap<>();
            SceneController source = (SceneController) HologramDetachedController.create(id, ExtendedFacing.DEFAULT);
            SceneTile tile = new SceneTile();
            tile.setWorldObj(world);
            tile.yCoord = 128;
            tile.setMetaTileEntity(source);
            source.setBaseMetaTileEntity(tile);
            world.controller = tile;
            witness(source, world, 4);
            witness(source, world, 2);
            SimpleAssert.eq(0, prototype.constructions, "inference never constructs the registry prototype");
            SimpleAssert.eq(1, prototype.height, "probe temporary size cannot leak to the prototype");
        } catch (Exception failure) {
            throw new AssertionError("area native signal inference", failure);
        } finally {
            GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    private static void witness(SceneController source, SceneWorld world, int height) throws Exception {
        ItemStack seed = new ItemStack(Blocks.stone);
        ChannelDataAccessor.setChannelData(seed, "height", height);
        HologramCapture expected = HologramCapture.collect(source, seed, source.getExtendedFacing());
        while (expected.pending) expected = HologramCapture.collect(source, seed, source.getExtendedFacing(), expected);
        SimpleAssert.that(!expected.incomplete, "source declaration fixture is complete");
        world.blocks.clear();
        for (HologramCapture.Cell cell : expected.cells) if (!cell.anchor) {
            SimpleAssert.that(
                cell.block == Blocks.stone && !cell.unknown,
                "fixture emits the native stone hint through the explicit headless hook");
            world.blocks.put(HologramValues.position(cell), Blocks.stone);
        }
        int constructions = source.constructions;
        ItemStack unrelatedTool = new ItemStack(Blocks.stone, 49);
        ChannelDataAccessor.setChannelData(unrelatedTool, "height", 1);
        HologramAreaSignalInference inference = new HologramAreaSignalInference(source, unrelatedTool);
        int polls = 0;
        while (!inference.step(System.nanoTime() + 5_000_000L) && ++polls < 512) {}
        SimpleAssert.that(polls < 512, "inference completes within resumable work slices");
        String diagnostic = "";
        if (height != ChannelDataAccessor.getChannelData(inference.capture().trigger, "height")) {
            Field machineField = HologramAreaSignalInference.class.getDeclaredField("machine");
            machineField.setAccessible(true);
            MTEEnhancedMultiBlockBase<?> cloned = (MTEEnhancedMultiBlockBase<?>) machineField.get(inference);
            diagnostic = "; source=" + source.getBaseMetaTileEntity()
                .getXCoord()
                + ","
                + source.getBaseMetaTileEntity()
                    .getYCoord()
                + ","
                + source.getBaseMetaTileEntity()
                    .getZCoord()
                + "/"
                + source.getExtendedFacing()
                + "; clone="
                + cloned.getBaseMetaTileEntity()
                    .getXCoord()
                + ","
                + cloned.getBaseMetaTileEntity()
                    .getYCoord()
                + ","
                + cloned.getBaseMetaTileEntity()
                    .getZCoord()
                + "/"
                + cloned.getExtendedFacing()
                + "; main="
                + inference.capture().trigger.stackSize
                + "; effectiveHeight="
                + ChannelDataAccessor.getChannelData(inference.capture().trigger, "height")
                + "; counts="
                + inference.diagnostics()
                + "; expected="
                + cells(expected, world)
                + "; best="
                + cells(inference.capture(), world);
        }
        SimpleAssert.eq(
            height,
            ChannelDataAccessor.getChannelData(inference.capture().trigger, "height"),
            "source geometry overrides the unrelated tool's default height" + diagnostic);
        SimpleAssert.eq(
            expected.cells.size(),
            inference.capture().cells.size(),
            "same-name native piece shape growth establishes complete source memberships");
        SimpleAssert.eq(constructions, source.constructions, "only an isolated native instance receives probes");
        SimpleAssert.eq(height, source.height, "temporary high probes cannot overwrite live size fields");
        SimpleAssert.that(
            inference.diagnostics()
                .getInteger("declarationQueries") >= 128,
            "diagnostics account for both main and observed named signal queries");
        SimpleAssert.that(
            inference.diagnostics()
                .getInteger("fullCaptures") <= 8,
            "duplicate raw signals never cause 64 complete world traversals");
        SimpleAssert.eq(49, unrelatedTool.stackSize, "source inference does not rewrite the user's tool");
    }

    private static String cells(HologramCapture capture, SceneWorld world) {
        StringBuilder result = new StringBuilder("cells=").append(capture.cells.size());
        for (HologramCapture.Cell cell : capture.cells) {
            if (result.length() > 1200) break;
            result.append(" [")
                .append(HologramValues.position(cell))
                .append(" expected=")
                .append(Block.blockRegistry.getNameForObject(cell.block))
                .append(':')
                .append(cell.meta)
                .append(" actual=")
                .append(Block.blockRegistry.getNameForObject(world.getBlock(cell.x, cell.y, cell.z)))
                .append(':')
                .append(world.getBlockMetadata(cell.x, cell.y, cell.z))
                .append(" unknown=")
                .append(cell.unknown)
                .append(" anchor=")
                .append(cell.anchor)
                .append(']');
        }
        return result.toString();
    }

    static final class SceneWorld extends net.minecraft.world.WorldServer {

        Map<String, Block> blocks;
        TileEntity controller;

        private SceneWorld() {
            super(null, null, "fixture", 0, null, null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public long getTotalWorldTime() {
            return 0;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
            throw new AssertionError("area inference cannot write its source world");
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, Blocks.air);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return 0;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return controller != null && x == controller.xCoord && y == controller.yCoord && z == controller.zCoord
                ? controller
                : null;
        }
    }

    static final class SceneController extends MTEEnhancedMultiBlockBase<SceneController> {

        int height = 1, constructions;

        SceneController() {
            super("area_height_fixture");
        }

        @Override
        public IStructureDefinition<SceneController> getStructureDefinition() {
            String[] rows = new String[height];
            java.util.Arrays.fill(rows, "S");
            return StructureDefinition.<SceneController>builder()
                .addShape("body", new String[][] { rows })
                .addElement('S', new HintedStone())
                .build();
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            constructions++;
            height = Math.max(1, Math.min(4, ChannelDataAccessor.getChannelData(trigger, "height")));
            HologramChannelTrace.record(trigger, "height", height, 2);
            HologramCapture.piece(this, "body", trigger, hints, -3, 0, 0);
        }

        @Override
        public ItemStack getStackForm(long count) {
            return new ItemStack(Blocks.stone, (int) count);
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return null;
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new SceneController();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }

    /** The raw harness supplies the same hint hook normally installed in StructureLib by the runtime mixin. */
    static final class HintedStone
        implements com.gtnewhorizon.structurelib.structure.IStructureElement<SceneController> {

        final com.gtnewhorizon.structurelib.structure.IStructureElement<SceneController> nativeStone = StructureUtility
            .ofBlock(Blocks.stone, 0);

        @Override
        public boolean check(SceneController context, net.minecraft.world.World world, int x, int y, int z) {
            throw new AssertionError("inference must not run the native structure check");
        }

        @Override
        public boolean spawnHint(SceneController context, net.minecraft.world.World world, int x, int y, int z,
            ItemStack signal) {
            return HologramCapture.hint(world, x, y, z, Blocks.stone, 0);
        }

        @Override
        public boolean placeBlock(SceneController context, net.minecraft.world.World world, int x, int y, int z,
            ItemStack signal) {
            throw new AssertionError("inference must not place source blocks");
        }

        @Override
        public BlocksToPlace getBlocksToPlace(SceneController context, net.minecraft.world.World world, int x, int y,
            int z, ItemStack signal, com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment environment) {
            return nativeStone.getBlocksToPlace(context, world, x, y, z, signal, environment);
        }
    }

    /** The headless classpath omits ChunkAPI's chunk codec; keep native MTE persistence and identity intact. */
    static final class SceneTile extends gregtech.api.metatileentity.BaseMetaTileEntity {

        @Override
        public int getMetaTileID() {
            return 32759;
        }

        @Override
        public ForgeDirection getFrontFacing() {
            return ForgeDirection.NORTH;
        }

        @Override
        public void writeToNBT(net.minecraft.nbt.NBTTagCompound tag) {
            tag.setString("id", "BaseMetaTileEntity");
            tag.setInteger("x", xCoord);
            tag.setInteger("y", yCoord);
            tag.setInteger("z", zCoord);
            tag.setInteger("mID", getMetaTileID());
            tag.setShort("mFacing", (short) getFrontFacing().ordinal());
            getMetaTileEntity().saveNBTData(tag);
        }
    }
}
