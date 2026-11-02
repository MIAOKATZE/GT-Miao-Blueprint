package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.Declarations;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** A community-defined leaf reaches real consumers without a machine-name table or native element callbacks. */
final class HologramPureElementsRegression {

    private HologramPureElementsRegression() {}

    static void run() {
        try {
            dataOnlyCaptureAndCandidates();
            explicitWorldIdentity();
            wrapperLifecycleAndUnknownBranches();
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("pure declaration contract", failure);
        }
    }

    private static void dataOnlyCaptureAndCandidates() throws ReflectiveOperationException, IOException {
        Leaf declaration = new Leaf(false);
        HologramSession session = session(HologramResourceWorld.create(Blocks.stone, 0));
        HologramCapture.Cell cell = describe(session, declaration);
        SimpleAssert.eq(Blocks.stone, cell.block, "capture projects the public primary material");
        SimpleAssert.that(!cell.unknown, "the explicit pure contract describes the cell without a native hint");
        SimpleAssert.eq(0, session.capture.nativeHintCalls, "pure capture never enters native hint callbacks");
        SimpleAssert.eq(
            "fixture material",
            HologramCellPolicy.candidateRole(session, cell, declaration.primary()),
            "candidate labels come from the contract rather than machine identity");
        SimpleAssert.that(HologramDeclarationPurity.contextFree(declaration), "explicit pure leaves share raw data");
        SimpleAssert.eq(
            2,
            HologramCellPolicy.candidates(session, cell)
                .size(),
            "all declared alternatives reach UI data");
        SimpleAssert.that(!cell.candidateComplete, "a partial candidate declaration remains partial");
        SimpleAssert
            .eq("fixture candidates are partial", cell.candidateBoundary, "the declaration retains its boundary");
        SimpleAssert.that(
            HologramCellPolicy.acceptableSample(session, cell, new ItemStack(Blocks.dirt)),
            "an explicit declared alternative can be selected");
        SimpleAssert.that(
            !HologramCellPolicy.acceptableSample(session, cell, new ItemStack(Blocks.cobblestone)),
            "unrelated registered materials never inherit acceptance");
        SimpleAssert.eq(
            Blocks.stone,
            Block.getBlockFromItem(
                HologramCellPolicy.draftTarget(session, cell, 0)
                    .getItem()),
            "automatic draft targets use the pure primary");
        SimpleAssert.eq(
            0,
            HologramCellPolicy.targetPreviewMeta(session, cell, declaration.primary()),
            "preview metadata uses the pure material declaration");
    }

    private static void explicitWorldIdentity() throws ReflectiveOperationException {
        Leaf declaration = new Leaf(true, Blocks.chest, TileEntityChest.class);
        HologramResourceWorld world = HologramResourceWorld.create(Blocks.chest, 0);
        HologramSession session = session(world);
        HologramCapture.Cell cell = describe(session, declaration);
        SimpleAssert.that(
            !HologramCellPolicy.targetSatisfied(session, cell, 0, Blocks.chest, 0),
            "a matching visual block cannot replace a declared tile requirement");
        SimpleAssert.that(
            !HologramCellPolicy.targetMatches(session, cell, 0, Blocks.chest, 0),
            "target verification uses the same pure world contract");
        world.tile = new TileEntityChest();
        world.tile.yCoord = 64;
        SimpleAssert.that(
            HologramCellPolicy.targetSatisfied(session, cell, 0, Blocks.chest, 0),
            "the real expected tile satisfies the declared cell");
        SimpleAssert.that(
            HologramCellPolicy.originalMember(session, cell),
            "replacement membership consumes the public pure world contract");
        Declarations.WorldState state = HologramCellPolicy.worldState(world, 0, 64, 0);
        SimpleAssert
            .eq(TileEntityChest.class, state.tileType, "snapshots retain only tile type, not mutable tile state");
        SimpleAssert.eq(-1, state.machineId, "ordinary tiles acquire no invented machine identity");
        SimpleAssert.that(
            !declaration.matches(HologramCellPolicy.worldState(world, 1, 64, 0)),
            "unloaded coordinates cannot satisfy a declaration");
    }

    private static void wrapperLifecycleAndUnknownBranches() throws ReflectiveOperationException {
        Leaf declaration = new Leaf(true);
        Wrapper wrapper = new Wrapper();
        HologramDeclarationCollector collector = new HologramDeclarationCollector();
        ItemStack signal = declaration.primary();
        SimpleAssert.that(
            collector.collect(wrapper, null, null, 0, 64, 0, "", signal, null).stacks.isEmpty(),
            "an unknown wrapper has no inferred declaration");
        try (GTMBExtensions.Registration ignored = Declarations.registerReader(
            "gtmb_test:pure-element-wrapper",
            Wrapper.class,
            element -> new Declarations.Declaration(declaration))) {
            SimpleAssert.that(
                HologramElementCatalog.pure(wrapper) == declaration,
                "an explicitly registered transparent wrapper resolves its pure backing");
            SimpleAssert
                .that(HologramDeclarationPurity.contextFree(wrapper), "registered pure wrappers share raw data");
            SimpleAssert.eq(
                2,
                collector.collect(wrapper, null, null, 0, 64, 0, "", signal, null).stacks.size(),
                "public registration invalidates the prior unknown candidate cache");
            HologramSession session = session(HologramResourceWorld.create(Blocks.stone, 0));
            SimpleAssert.eq(
                Blocks.stone,
                describe(session, wrapper).block,
                "the actual capture consumer accepts a registered pure wrapper");
        }
        SimpleAssert.that(HologramElementCatalog.pure(wrapper) == null, "closing the reader retires pure authority");
        SimpleAssert
            .that(!HologramDeclarationPurity.contextFree(wrapper), "closing the reader retires pure cacheability");
        SimpleAssert.that(
            collector.collect(wrapper, null, null, 0, 64, 0, "", signal, null).stacks.isEmpty(),
            "reader removal invalidates the previously accepted candidate cache");
        IStructureElement<Object> conditional = StructureUtility
            .onlyIf(controller -> { throw new AssertionError("unknown predicate was evaluated"); }, declaration);
        IStructureElement<Object> deferred = StructureUtility
            .defer(controller -> { throw new AssertionError("unknown supplier was evaluated"); });
        for (IStructureElement<?> unknown : Arrays.asList(conditional, deferred)) {
            SimpleAssert.that(
                HologramElementCatalog.pure(unknown) == null,
                "an arbitrary conditional or supplier cannot inherit leaf authority");
            HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
            candidates.collect(unknown, null, null, 0, 64, 0, signal, null, 0);
            SimpleAssert.that(
                !candidates.complete && candidates.stacks.isEmpty(),
                "unknown declarations stay incomplete without probing their callbacks");
        }
    }

    @SuppressWarnings("unchecked")
    private static HologramCapture.Cell describe(HologramSession session, IStructureElement<?> element)
        throws ReflectiveOperationException {
        ItemStack signal = new ItemStack(Blocks.stone);
        HologramCapture capture = new HologramCapture(new Object(), signal);
        capture.definition = StructureDefinition.<Object>builder()
            .addShape("main", new String[][] { { "A" } })
            .addElement('A', (IStructureElement<Object>) element)
            .build();
        capture.pieces.add(new HologramCapture.Piece("main", 0, 0, 0, signal));
        capture.deadline = Long.MAX_VALUE;
        Method walk = HologramCapture.class.getDeclaredMethod(
            "walkPieces",
            HologramCapture.class,
            IStructureDefinition.class,
            World.class,
            ExtendedFacing.class,
            int.class,
            int.class,
            int.class);
        walk.setAccessible(true);
        walk.invoke(null, capture, capture.definition, session.player.worldObj, ExtendedFacing.DEFAULT, 0, 64, 0);
        SimpleAssert.eq(1, capture.cells.size(), "the actual structure walker captures the community pure leaf");
        HologramCapture.Cell cell = capture.cells.get(0);
        capture.current = cell;
        Method hint = HologramCapture.class.getDeclaredMethod(
            "spawnHint",
            IStructureElement.class,
            World.class,
            int.class,
            int.class,
            int.class,
            ItemStack.class,
            int.class);
        hint.setAccessible(true);
        SimpleAssert.eq(
            true,
            hint.invoke(capture, element, session.player.worldObj, 0, 64, 0, signal, 0),
            "pure element capture completes through the production hint dispatcher");
        capture.current = null;
        session.capture = capture;
        return cell;
    }

    private static HologramSession session(World world) throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) ((sun.misc.Unsafe) field.get(null))
            .allocateInstance(EntityPlayerMP.class);
        UUID identity = UUID.fromString("52ba46f2-a7f2-4002-a891-258f50f2e132");
        for (Field candidate : EntityPlayer.class.getDeclaredFields()) if (candidate.getType() == GameProfile.class) {
            candidate.setAccessible(true);
            candidate.set(player, new GameProfile(identity, "PureElementFixture"));
        }
        for (Field candidate : Entity.class.getDeclaredFields()) if (candidate.getType() == UUID.class) {
            candidate.setAccessible(true);
            candidate.set(player, identity);
        }
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        return new HologramSession(player, 0, 64, 0, false, 2);
    }

    static class Leaf implements Declarations.PureElement<Object> {

        private final boolean complete;
        private final Block block;
        private final Class<? extends TileEntity> tile;

        Leaf(boolean complete) {
            this(complete, Blocks.stone, null);
        }

        Leaf(boolean complete, Block block, Class<? extends TileEntity> tile) {
            this.complete = complete;
            this.block = block;
            this.tile = tile;
        }

        public ItemStack primary() {
            return new ItemStack(block);
        }

        public List<ItemStack> candidates() {
            return block == Blocks.stone ? Arrays.asList(primary(), new ItemStack(Blocks.dirt))
                : Collections.singletonList(primary());
        }

        public boolean complete() {
            return complete;
        }

        public String role() {
            return "block";
        }

        public String candidateBoundary() {
            return "fixture candidates are partial";
        }

        public String candidateRoleLabel(ItemStack sample) {
            return "fixture material";
        }

        public boolean accepts(ItemStack sample) {
            return sample != null && sample.getItemDamage() == 0
                && candidates().stream()
                    .anyMatch(sample::isItemEqual);
        }

        public boolean placementAllows(ItemStack sample) {
            return accepts(sample);
        }

        public int targetMeta(ItemStack sample) {
            return 0;
        }

        public boolean matches(Declarations.WorldState state) {
            return state.loaded && state.metadata == 0
                && (tile == null ? state.tileType == null
                    : state.tileType != null && tile.isAssignableFrom(state.tileType))
                && candidates().stream()
                    .anyMatch(sample -> Block.getBlockFromItem(sample.getItem()) == state.block);
        }

        public boolean matchesTarget(Declarations.WorldState state, ItemStack target) {
            return accepts(target) && matches(state) && state.block == Block.getBlockFromItem(target.getItem());
        }

        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("pure declaration native check was called");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("pure declaration native hint was called");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("pure declaration native placement was called");
        }

        public BlocksToPlace getBlocksToPlace(Object context, World world, int x, int y, int z, ItemStack trigger,
            AutoPlaceEnvironment environment) {
            throw new AssertionError("pure declaration native candidates were called");
        }
    }

    private static final class Wrapper implements IStructureElement<Object> {

        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("wrapper check was called");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("wrapper hint was called");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("wrapper placement was called");
        }
    }
}
