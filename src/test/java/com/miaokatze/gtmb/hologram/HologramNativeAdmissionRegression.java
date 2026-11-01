package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.StatBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import gregtech.api.GregTechAPI;
import gregtech.api.util.GTStructureUtility;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Actual native factories must remain constructible while unknown callbacks retain no placement authority. */
final class HologramNativeAdmissionRegression {

    private HologramNativeAdmissionRegression() {}

    static void run() {
        try {
            nativeFurnace();
            nativeTieredAndKnownChain();
            failedPlacementRollsBack();
            unknownAndDisplayOnly();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native construction declaration admission", failure);
        }
    }

    private static void nativeFurnace() throws ReflectiveOperationException {
        Block previousCasing = GregTechAPI.sBlockCasings1, previousCoil = GregTechAPI.sBlockCasings5;
        try {
            if (GregTechAPI.sBlockCasings1 == null) GregTechAPI.sBlockCasings1 = Blocks.wool;
            String key = "gtmb:native_admission_coil";
            Block registered = (Block) Block.blockRegistry.getObject(key);
            BlockCasings5 coil = registered instanceof BlockCasings5 ? (BlockCasings5) registered : null;
            if (coil == null) {
                coil = (BlockCasings5) unsafe().allocateInstance(BlockCasings5.class);
                Field delegate = Block.class.getDeclaredField("delegate");
                delegate.setAccessible(true);
                delegate.set(
                    coil,
                    ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Block.blockRegistry)
                        .getDelegate(coil, Block.class));
                coil.stepSound = Blocks.iron_block.stepSound;
                int id = 3900;
                while (Block.getBlockById(id) != null && Block.getBlockById(id) != Blocks.air
                    || Item.getItemById(id) != null) id--;
                Block.blockRegistry.addObject(id, key, coil);
                Item.itemRegistry.addObject(id, key, new ItemBlock(coil) {

                    @Override
                    public int getMetadata(int damage) {
                        return damage;
                    }
                });
            }
            GregTechAPI.sBlockCasings5 = coil;
            MTEElectricBlastFurnace furnace = new MTEElectricBlastFurnace("native_admission_ebf");
            HologramSession session = session();
            session.machine = furnace;
            IStructureElement<?> coilElement = null, casingElement = null;
            ItemStack casingTarget = null;
            int coilCells = 0;
            for (IStructureElement<?> element : furnace.getStructureDefinition()
                .getStructureFor("main")) {
                if (element.isNavigating() || element == StructureUtility.isAir()) continue;
                if (HologramCellPolicy.factory(element, "ofCoil", new java.util.IdentityHashMap<>(), 0)) {
                    coilElement = element;
                    coilCells++;
                } else if (casingElement == null) {
                    HologramCasingFallback.Target fallback = HologramCasingFallback
                        .resolve(element, new ItemStack(Items.feather));
                    if (fallback != null) {
                        casingElement = element;
                        casingTarget = new ItemStack(fallback.block, 1, fallback.meta);
                    }
                }
            }
            SimpleAssert.eq(16, coilCells, "the real industrial blast furnace retains its sixteen native coil cells");
            SimpleAssert.that(casingElement != null, "the real industrial blast furnace exposes its native casing");
            HologramCapture.Cell casing = use(session, casingElement, casingTarget);
            SimpleAssert.eq(null, HologramSourcePolicy.constructionFailure(session), "native EBF casing is admitted");
            place(session, casing, casingTarget);

            ItemStack coilTarget = new ItemStack(coil, 1, 0);
            HologramCapture.Cell coilCell = use(session, coilElement, coilTarget);
            HologramCellPolicy.declaredCandidates(session, coilCell);
            SimpleAssert.that(!coilCell.candidateComplete, "native coil alternatives intentionally remain partial");
            SimpleAssert
                .eq(null, HologramSourcePolicy.constructionFailure(session), "partial native EBF coil is admitted");
            SimpleAssert.eq(null, HologramSourcePolicy.targetFailure(session, coilCell, 0), "the exact coil is proved");
            place(session, coilCell, coilTarget);
        } finally {
            GregTechAPI.sBlockCasings1 = previousCasing;
            GregTechAPI.sBlockCasings5 = previousCoil;
        }
    }

    private static void nativeTieredAndKnownChain() throws ReflectiveOperationException {
        IStructureElement<Object> tiered = StructureUtility.ofBlocksTiered(
            (block, meta) -> { throw new AssertionError("tier extractor must not run during GTMB construction"); },
            Arrays.asList(Pair.of(Blocks.stone, 0), Pair.of(Blocks.dirt, 0)),
            -1,
            (context, tier) -> { throw new AssertionError("native tier setter must not run"); },
            context -> { throw new AssertionError("native tier getter must not run"); });
        HologramSession session = session();
        ItemStack stone = new ItemStack(Blocks.stone);
        HologramCapture.Cell cell = use(session, tiered, stone);
        HologramCellPolicy.declaredCandidates(session, cell);
        SimpleAssert.that(!cell.candidateComplete, "tiered construction outputs are not exhaustive acceptance");
        SimpleAssert.eq(null, HologramSourcePolicy.targetFailure(session, cell, 0), "a declared tier can be placed");
        place(session, cell, stone);

        Unknown unknown = new Unknown();
        IStructureElement<Object> fixed = StructureUtility.onElementPass(
            context -> { throw new AssertionError("native element-pass callback must not run"); },
            StructureUtility.ofBlock(Blocks.stone, 0));
        cell = use(session, StructureUtility.ofChain(unknown, fixed), stone);
        HologramCellPolicy.declaredCandidates(session, cell);
        SimpleAssert.that(!cell.candidateComplete, "an unknown OR branch keeps the complete flag false");
        SimpleAssert.eq(null, HologramSourcePolicy.targetFailure(session, cell, 0), "the known OR branch proves stone");
        place(session, cell, stone);
        SimpleAssert.eq(0, unknown.calls, "construction never enters the unknown OR branch");

        IStructureElement<Object> differentHint = StructureUtility.ofBlock(Blocks.stone, 0, Blocks.dirt, 0);
        cell = use(session, differentHint, new ItemStack(Blocks.dirt));
        SimpleAssert.that(
            HologramSourcePolicy.targetFailure(session, cell, 0) != null,
            "a visual hint alone cannot authorize the different advertised block");
    }

    private static void unknownAndDisplayOnly() throws ReflectiveOperationException {
        HologramSession session = session();
        ItemStack stone = new ItemStack(Blocks.stone);
        Unknown unknown = new Unknown();
        IStructureElement<Object> fixed = StructureUtility.ofBlock(Blocks.stone, 0);
        IStructureElement<Object> display = GTStructureUtility.triggerItemTransform(
            trigger -> { throw new AssertionError("unknown trigger transform must not run"); },
            fixed);
        for (IStructureElement<?> element : Arrays.asList(
            unknown,
            display,
            StructureUtility.onlyIf(context -> { throw new AssertionError("unknown predicate must not run"); }, fixed),
            StructureUtility.defer(context -> { throw new AssertionError("unknown supplier must not run"); }))) {
            HologramCapture.Cell cell = use(session, element, stone);
            cell.unknown = true;
            if (element == display) {
                SimpleAssert.eq(
                    1,
                    HologramCellPolicy.declaredCandidates(session, cell)
                        .size(),
                    "display candidates remain visible");
            }
            SimpleAssert.eq(
                null,
                HologramSourcePolicy.constructionFailure(session),
                "unknown defaults do not block safe cells");
            SimpleAssert
                .eq("protected", HologramPlanPolicy.classify(session, cell, 0), "unproved defaults stay protected");
            Scene world = (Scene) session.player.worldObj;
            int writes = world.writes;
            session.player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 2);
            SimpleAssert
                .that(HologramWorldTransactions.operateChecked(session, cell, 0), "unknown cell is skipped safely");
            SimpleAssert.eq(writes, world.writes, "unknown cell never writes the world");
            SimpleAssert
                .eq(2, session.player.inventory.mainInventory[1].stackSize, "unknown cell never consumes materials");
            session.pins.put(0, stone);
            String failure = HologramSourcePolicy.constructionFailure(session);
            SimpleAssert
                .eq("SOURCE_DECLARATION_UNVERIFIED", failure, "explicit unproved material still rejects the plan");
            String message = HologramSourcePolicy.failureMessage(session, failure);
            SimpleAssert.that(
                message.contains("0, 64, 0") && message.contains("施工声明"),
                "rejection explains the affected position and cause");
        }
        for (IStructureElement<?> chain : Arrays
            .asList(StructureUtility.ofChain(display, fixed), StructureUtility.ofChain(fixed, display))) {
            HologramCapture.Cell cell = use(session, chain, stone);
            SimpleAssert.eq(
                null,
                HologramSourcePolicy.targetFailure(session, cell, 0),
                "display-only traversal cannot hide a known OR proof");
        }
        SimpleAssert.eq(0, unknown.calls, "unknown callbacks are not invoked by admission, planning or transactions");
    }

    private static void failedPlacementRollsBack() throws ReflectiveOperationException {
        HologramSession session = session();
        ItemStack stone = new ItemStack(Blocks.stone);
        HologramCapture.Cell cell = use(session, StructureUtility.ofBlock(Blocks.stone, 0), stone);
        session.player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 2);
        Scene world = (Scene) session.player.worldObj;
        world.rewriteNextPlacement = true;
        SimpleAssert.that(
            !HologramWorldTransactions.operateChecked(session, cell, 0),
            "an unexpected installed material fails the existing identity check");
        SimpleAssert.that(world.isAirBlock(0, 64, 0), "failed native item placement restores the original air");
        SimpleAssert.eq(2, world.writes, "the transaction performs a real placement and a rollback");
        SimpleAssert.eq(2, session.player.inventory.mainInventory[1].stackSize, "rollback returns the paid material");
    }

    private static HologramCapture.Cell use(HologramSession session, IStructureElement<?> element, ItemStack target) {
        session.capture = new HologramCapture(session.machine, new ItemStack(Items.feather));
        HologramCapture.Cell cell = new HologramCapture.Cell(element, 0, 64, 0);
        cell.block = Block.getBlockFromItem(target.getItem());
        cell.meta = target.getItemDamage();
        session.capture.cells.add(cell);
        session.pins.clear();
        session.planTargets.clear();
        session.planTargets.add(target.copy());
        Scene world = (Scene) session.player.worldObj;
        world.placed = null;
        return cell;
    }

    private static void place(HologramSession session, HologramCapture.Cell cell, ItemStack target) {
        Scene world = (Scene) session.player.worldObj;
        world.placed = null;
        session.player.inventory.mainInventory[1] = target.copy();
        session.player.inventory.mainInventory[1].stackSize = 2;
        SimpleAssert.eq(
            "pending",
            HologramPlanPolicy.classify(session, cell, 0),
            "the native target reaches actual planned work");
        SimpleAssert.that(
            HologramWorldTransactions.operateChecked(session, cell, 0),
            "the native material transaction commits");
        SimpleAssert.eq("placed", cell.status, "the actual native target is placed rather than silently skipped");
        SimpleAssert
            .eq(Block.getBlockFromItem(target.getItem()), world.placed, "world retains the proved block identity");
        SimpleAssert.eq(
            1,
            session.player.inventory.mainInventory[1].stackSize,
            "successful native construction consumes exactly one item");
        SimpleAssert.that(
            HologramCellPolicy.targetMatches(session, cell, 0, world.placed, world.metadata),
            "placed identity passes the existing replay check");
    }

    private static HologramSession session() throws ReflectiveOperationException {
        EntityPlayerMP player = (EntityPlayerMP) unsafe().allocateInstance(PlacementPlayer.class);
        UUID identity = UUID.fromString("c73dd758-17c3-432d-8d4c-eb1c66baa1a6");
        for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
            field.setAccessible(true);
            field.set(player, new GameProfile(identity, "NativeAdmission"));
        }
        for (Field field : Entity.class.getDeclaredFields()) if (field.getType() == UUID.class) {
            field.setAccessible(true);
            field.set(player, identity);
        }
        Scene world = (Scene) unsafe().allocateInstance(Scene.class);
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        world.capturedBlockSnapshots = new ArrayList<>();
        Field observers = World.class.getDeclaredField("worldAccesses");
        observers.setAccessible(true);
        observers.set(world, new ArrayList<>());
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[0] = new ItemStack(Items.feather);
        HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
        session.materialSettings.containers = session.materialSettings.me = false;
        session.materialContext = new HologramMaterials.Context(player, session.materialSettings, 0);
        return session;
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static final class Unknown implements IStructureElement<Object> {

        int calls;

        public boolean check(Object context, World world, int x, int y, int z) {
            calls++;
            throw new AssertionError("unknown check callback");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
            calls++;
            throw new AssertionError("unknown hint callback");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack trigger) {
            calls++;
            throw new AssertionError("unknown placement callback");
        }

        public BlocksToPlace getBlocksToPlace(Object context, World world, int x, int y, int z, ItemStack trigger,
            AutoPlaceEnvironment environment) {
            calls++;
            throw new AssertionError("unknown material callback");
        }
    }

    private static final class PlacementPlayer extends EntityPlayerMP {

        private PlacementPlayer() {
            super(null, null, null, null);
        }

        @Override
        public void addStat(StatBase statistic, int amount) {
            // Native ItemBlock statistics have no server statistics store in this detached fixture.
        }
    }

    private static final class Scene extends WorldServer {

        Block placed;
        int metadata, writes;
        boolean rewriteNextPlacement;

        private Scene() {
            super(null, null, "native-admission", 0, null, null);
        }

        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        public int getHeight() {
            return 256;
        }

        public long getTotalWorldTime() {
            return 0;
        }

        public Block getBlock(int x, int y, int z) {
            return placed == null ? Blocks.air : placed;
        }

        public int getBlockMetadata(int x, int y, int z) {
            return metadata;
        }

        public TileEntity getTileEntity(int x, int y, int z) {
            return null;
        }

        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            return true;
        }

        public boolean isAirBlock(int x, int y, int z) {
            return placed == null || placed == Blocks.air;
        }

        public boolean canPlaceEntityOnSide(Block block, int x, int y, int z, boolean replace, int side, Entity entity,
            ItemStack stack) {
            return true;
        }

        public void playSoundEffect(double x, double y, double z, String sound, float volume, float pitch) {}

        public void removeTileEntity(int x, int y, int z) {
            // This fixture contains ordinary blocks only; rollback still calls the real recovery boundary.
        }

        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            placed = rewriteNextPlacement ? Blocks.dirt : block;
            rewriteNextPlacement = false;
            metadata = meta;
            writes++;
            return true;
        }

        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            metadata = meta;
            return true;
        }
    }
}
