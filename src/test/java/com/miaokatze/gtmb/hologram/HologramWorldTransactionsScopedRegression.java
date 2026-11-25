// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;

final class HologramWorldTransactionsScopedRegression {

    private HologramWorldTransactionsScopedRegression() {}

    static void run() {
        var previousRoot = HologramConstructionJournal.STORAGE_ROOT.get();
        EntityPlayerMP player = null;
        Path scratch = null;
        try {
            var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) singleton.get(null);
            scratch = Files.createTempDirectory("gtmb-scoped-world-");
            HologramConstructionJournal.STORAGE_ROOT.set(scratch);
            var world = HologramPlacementFixtureWorld.create(unsafe);
            var movedWorld = HologramPlacementFixtureWorld.create(unsafe);
            movedWorld.provider.dimensionId = 7;
            Path originalSave = Files.createDirectory(scratch.resolve("original"));
            Path replacementSave = Files.createDirectory(scratch.resolve("replacement"));
            setSaveRoot(unsafe, world, originalSave);
            setSaveRoot(unsafe, movedWorld, replacementSave);
            long publications = HologramConstructionJournal.publications;
            player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "ScopedTransactionFixture");
            player.capabilities.allowEdit = true;
            player.capabilities.isCreativeMode = false;
            player.posY = 64;
            player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 8);
            var session = new HologramSession(player, 0, 64, 0, false, 2);
            session.materialSettings.main = true;
            session.materialSettings.containers = session.materialSettings.me = false;
            var unrelated = new HologramMaterials.Context(player, session.materialSettings, session.slot);
            unrelated.recoverWorld(Collections.singletonList(new ItemStack(Blocks.cobblestone, 2)));
            NBTTagCompound dormant = (NBTTagCompound) HologramConstructionJournal.owner(player)
                .copy();
            SimpleAssert.eq(0, stock(player, Blocks.cobblestone), "recovery is staged, not already delivered");
            world.arm();
            movedWorld.arm();

            SimpleAssert.that(
                HologramWorldTransactions.operateScoped(session, select(session, 1), 0, () -> true),
                "the scoped authority uses the real placement/deduction transaction");
            SimpleAssert.that(
                world.getBlock(1, 64, 0) == Blocks.stone && stock(player, Blocks.stone) == 7
                    && !HologramConstructionJournal.pending(player)
                    && session.materialContext == null
                    && HologramConstructionJournal.publications == publications,
                "a settled scoped placement writes the exact block and charges once without retaining scratch stock");
            dormantUnchanged(dormant, player, "a scoped commit never flushes unrelated recovery");

            var eventField = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object busBase = unsafe.staticFieldBase(eventField);
            long busOffset = unsafe.staticFieldOffset(eventField);
            EventBus previousBus = MinecraftForge.EVENT_BUS;
            AtomicBoolean authority = new AtomicBoolean(true);
            var actor = player;
            int[] observed = { 0 };
            EventBus fence = new EventBus() {

                @Override
                public boolean post(Event event) {
                    if (event instanceof BlockEvent.PlaceEvent place && place.player == actor
                        && place.world == world
                        && place.x == 2
                        && world.getBlock(2, 64, 0) == Blocks.stone) {
                        observed[0]++;
                        SimpleAssert.eq(7, stock(actor, Blocks.stone), "Forge sees no speculative live debit");
                        actor.inventory.mainInventory[10] = new ItemStack(Blocks.gravel, 5);
                        authority.set(false);
                    }
                    return false; // Forge did not veto; only the exact owned authority was retired.
                }
            };
            unsafe.putObjectVolatile(busBase, busOffset, fence);
            try {
                SimpleAssert.that(
                    !HologramWorldTransactions.operateScoped(session, select(session, 2), 0, authority::get),
                    "retirement in the real Forge event must not reach payment/world commit");
            } finally {
                unsafe.putObjectVolatile(busBase, busOffset, previousBus);
            }
            SimpleAssert.that(
                observed[0] > 0 && world.getBlock(2, 64, 0) == Blocks.air
                    && stock(player, Blocks.stone) == 7
                    && stock(player, Blocks.gravel) == 5
                    && !HologramConstructionJournal.pending(player)
                    && HologramConstructionJournal.publications == publications,
                "the real candidate was present in the event, no debit committed, unrelated live slot edit survives");
            dormantUnchanged(dormant, player, "a scoped rollback also never flushes unrelated recovery");

            SimpleAssert.that(
                HologramWorldTransactions.operate(session, select(session, 3), 0),
                "ordinary unscoped placement keeps its existing settlement path");
            SimpleAssert.that(
                world.getBlock(3, 64, 0) == Blocks.stone && stock(player, Blocks.stone) == 6
                    && stock(player, Blocks.cobblestone) == 2
                    && !HologramConstructionJournal.pending(player)
                    && HologramConstructionJournal.publications == publications,
                "the same dormant recovery remains deliverable by the original unscoped path");

            checkInventorySwaps(unsafe, world, player, session, publications);
            checkProjectedAndLastProbe(world, player, session, publications);

            // Remove the shared storage override: two real distinct fixture SaveHandler paths are now used.
            HologramConstructionJournal.STORAGE_ROOT.remove();
            Path originReceipt = constructionFile(originalSave, player);
            Path replacementReceipt = constructionFile(replacementSave, player);
            projected(session, true);
            Runnable restoreFirstSetter = afterSetter(world, HologramPlacementFixtureWorld.position(4, 64, 0), () -> {
                actor.worldObj = movedWorld;
                actor.dimension = 7;
            });
            try (var oldMutations = new PositionWatch(world, 4, null);
                var replacementMutations = new PositionWatch(movedWorld, 4, null)) {
                SimpleAssert.that(
                    !HologramWorldTransactions.operateScoped(session, select(session, 4), 0, () -> true)
                        && HologramConstructionJournal.pending(player)
                        && session.job == 4,
                    "a changed physical world can neither commit nor clear the original receipt");
                var receipt = pendingReceipt(player);
                SimpleAssert.that(
                    HologramConstructionJournal.publications == publications + 1 && Files.isRegularFile(originReceipt)
                        && !Files.exists(replacementReceipt),
                    "only the observed abnormal cell publishes, and only in its original save root");
                assertPersisted(originReceipt, receipt, world);
                expectResolutionRefusal(player, receipt, "accept");
                expectResolutionRefusal(player, receipt, "rollback");
                SimpleAssert.that(
                    world.getBlock(4, 64, 0) == Blocks.stone && world.getBlockMetadata(4, 64, 0) == 0
                        && movedWorld.getBlock(4, 64, 0) == Blocks.air
                        && oldMutations.puts == 1
                        && replacementMutations.puts == 0
                        && stock(player, Blocks.stone) == 5,
                    "virtual schema 2 loss inside the setter leaves one original attempted write, no metadata fixup,"
                        + " no replacement-world change and no debit");
            } finally {
                restoreFirstSetter.run();
                projected(session, false);
                player.worldObj = world;
                player.dimension = world.provider.dimensionId;
            }
            var receipt = pendingReceipt(player);
            expectResolutionRefusal(player, receipt, "rollback");
            HologramConstructionJournal.resolve(player, receipt.getString("id"), "accept");
            SimpleAssert.that(
                !HologramConstructionJournal.pending(player),
                "explicit adjudication in the original root releases exactly that durable receipt");
            publications = HologramConstructionJournal.publications;
            session.job = 0;
            session.stopTick = false;

            try {
                unsafe.putObjectVolatile(
                    busBase,
                    busOffset,
                    observing(world, actor, 5, () -> world.setBlock(5, 64, 0, Blocks.cobblestone, 0, 3)));
                SimpleAssert.that(
                    !HologramWorldTransactions.operateScoped(session, select(session, 5), 0, () -> true)
                        && HologramConstructionJournal.pending(player)
                        && session.job == 4
                        && world.getBlock(5, 64, 0) == Blocks.cobblestone
                        && HologramConstructionJournal.publications == publications + 1,
                    "even active authority cannot claim a different Forge-written candidate as its committed cell");
            } finally {
                unsafe.putObjectVolatile(busBase, busOffset, previousBus);
            }
            receipt = pendingReceipt(player);
            SimpleAssert.eq(
                "candidate-changed-before-commit",
                receipt.getString("scopeUncertainReason"),
                "the contested physical result is distinguishable from a moved owner");
            HologramConstructionJournal.resolve(player, receipt.getString("id"), "accept");
            publications = HologramConstructionJournal.publications;
            session.job = 0;
            session.stopTick = false;

            AtomicBoolean restoreAuthority = new AtomicBoolean(true);
            int writesBeforeResidue = world.writes()
                .size();
            Runnable restoreResidualSetter = afterSetter(
                world,
                HologramPlacementFixtureWorld.position(11, 64, 0),
                2,
                () -> world.setBlock(11, 64, 0, Blocks.cobblestone, 0, 3));
            try {
                unsafe.putObjectVolatile(
                    busBase,
                    busOffset,
                    observing(world, actor, 11, () -> restoreAuthority.set(false)));
                SimpleAssert.that(
                    !HologramWorldTransactions.operateScoped(session, select(session, 11), 0, restoreAuthority::get)
                        && session.job == 4
                        && HologramConstructionJournal.pending(player)
                        && world.writes()
                            .size() == writesBeforeResidue + 3
                        && world.getBlock(11, 64, 0) == Blocks.cobblestone
                        && stock(player, Blocks.stone) == 5
                        && HologramConstructionJournal.publications == publications + 1,
                    "a true air-setter result with third-party residual state cannot settle or issue a second restore");
            } finally {
                unsafe.putObjectVolatile(busBase, busOffset, previousBus);
                restoreResidualSetter.run();
            }
            receipt = pendingReceipt(player);
            SimpleAssert.eq(
                "scoped-world-restore-not-exact",
                receipt.getString("scopeUncertainReason"),
                "a rollback postcondition failure retains the exact evidence without a debit");
            expectResolutionRefusal(player, receipt, "rollback");
            HologramConstructionJournal.resolve(player, receipt.getString("id"), "accept");
            publications = HologramConstructionJournal.publications;
            session.job = 0;
            session.stopTick = false;

            Files.delete(originReceipt);
            Files.createDirectory(originReceipt); // not a valid journal, must never be overwritten
            try {
                unsafe.putObjectVolatile(busBase, busOffset, observing(world, actor, 6, () -> {
                    actor.worldObj = movedWorld;
                    actor.dimension = 7;
                }));
                SimpleAssert.that(
                    !HologramWorldTransactions.operateScoped(session, select(session, 6), 0, () -> true)
                        && HologramConstructionJournal.pending(player)
                        && session.job == 4
                        && pendingReceipt(player).getBoolean("scopePublicationFailed")
                        && HologramConstructionJournal.publications == publications
                        && Files.isDirectory(originReceipt)
                        && !Files.exists(replacementReceipt),
                    "original-root storage failure leaves pending exact authority, with no false publication or reroute");
                var pending = pendingReceipt(player);
                HologramConstructionJournal.reload(player);
                SimpleAssert.that(
                    pending == pendingReceipt(player) && HologramConstructionJournal.pending(player),
                    "loading the replacement root cannot erase the retained original uncertainty");
                expectResolutionRefusal(player, pending, "accept");
            } finally {
                unsafe.putObjectVolatile(busBase, busOffset, previousBus);
                player.worldObj = world;
                player.dimension = world.provider.dimensionId;
            }
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("scoped world transaction fixture", failure);
        } finally {
            if (player != null) player.playerNetServerHandler.netManager.channel()
                .close();
            if (previousRoot == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(previousRoot);
            if (scratch != null) try (var files = Files.walk(scratch)) {
                var paths = files.sorted(Comparator.reverseOrder())
                    .iterator();
                while (paths.hasNext()) Files.deleteIfExists(paths.next());
            } catch (IOException failure) {
                throw new AssertionError("delete scoped world transaction fixture", failure);
            }
        }
    }

    private static void checkProjectedAndLastProbe(HologramPlacementFixtureWorld world, EntityPlayerMP player,
        HologramSession session, long publications) throws ReflectiveOperationException {
        projected(session, true);
        try {
            try (var state = new PositionWatch(world, 9, null)) {
                var cell = select(session, 9);
                SimpleAssert.that(
                    HologramWorldTransactions.operateScoped(session, cell, 0, () -> true)
                        && "placed".equals(cell.status)
                        && state.puts == 1
                        && world.getBlock(9, 64, 0) == Blocks.stone
                        && world.getBlockMetadata(9, 64, 0) == 0
                        && stock(player, Blocks.stone) == 5,
                    "virtual schema 2 positively commits its exact authored block and one debit, without metadata rewrite");
            }
            AtomicBoolean authority = new AtomicBoolean(true);
            AtomicBoolean inReplaceability = new AtomicBoolean();
            try (var state = new PositionWatch(world, 10, () -> {
                if (!"world-pending".equals(pendingReceipt(player).getString("phase"))) return;
                for (var frame : Thread.currentThread()
                    .getStackTrace()) {
                    if ("com.gtnewhorizon.structurelib.StructureLibAPI".equals(frame.getClassName())
                        && "isBlockTriviallyReplaceable".equals(frame.getMethodName())) {
                        inReplaceability.set(true);
                        authority.set(false);
                        return;
                    }
                }
            })) {
                SimpleAssert.that(
                    !HologramWorldTransactions.operateScoped(session, select(session, 10), 0, authority::get)
                        && inReplaceability.get()
                        && state.puts == 0
                        && world.getBlock(10, 64, 0) == Blocks.air
                        && stock(player, Blocks.stone) == 5
                        && !HologramConstructionJournal.pending(player)
                        && HologramConstructionJournal.publications == publications,
                    "authority revoked in the real StructureLib replaceability callback fences the simulated token"
                        + " and never reaches the setter or a debit");
            }
        } finally {
            projected(session, false);
        }
    }

    private static void projected(HologramSession s, boolean enabled) {
        s.virtual = s.virtualPlacement = s.virtualLocked = enabled;
        if (enabled) {
            s.virtualPreset = new HologramPresets.Preset();
            s.virtualPreset.schema = 2;
            s.virtualPreset.retainNbt = s.virtualPreset.retainCovers = false;
            s.virtualPreset.deidentificationChosen = s.placementDeidentificationChosen = true;
        } else {
            s.virtualPreset = null;
            s.placementDeidentificationChosen = false;
        }
    }

    /** The shared world stores both full setters and otherwise invisible metadata mutations in this same map. */
    private static final class PositionWatch extends HashMap<String, Object> implements AutoCloseable {

        private final HologramPlacementFixtureWorld world;
        private final java.lang.reflect.Field field;
        private final Map<String, Object> previous;
        private final String position;
        private final Runnable onRead;
        int puts;

        @SuppressWarnings("unchecked")
        PositionWatch(HologramPlacementFixtureWorld world, int x, Runnable onRead) throws ReflectiveOperationException {
            this.world = world;
            this.onRead = onRead;
            position = HologramPlacementFixtureWorld.position(x, 64, 0);
            field = HologramPlacementFixtureWorld.class.getDeclaredField("placed");
            field.setAccessible(true);
            previous = (Map<String, Object>) field.get(world);
            putAll(previous);
            field.set(world, this);
        }

        @Override
        public Object get(Object key) {
            if (onRead != null && position.equals(key)) onRead.run();
            return super.get(key);
        }

        @Override
        public Object put(String key, Object value) {
            if (position.equals(key)) puts++;
            return super.put(key, value);
        }

        @Override
        public void close() throws IllegalAccessException {
            previous.clear();
            previous.putAll(this);
            field.set(world, previous);
        }
    }

    private static void checkInventorySwaps(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world,
        EntityPlayerMP player, HologramSession session, long publications) throws ReflectiveOperationException {
        var inventoryField = EntityPlayer.class.getDeclaredField("inventory");
        long inventoryOffset = unsafe.objectFieldOffset(inventoryField);
        InventoryPlayer original = player.inventory;
        InventoryPlayer replacement = new InventoryPlayer(player);
        replacement.mainInventory[1] = new ItemStack(Blocks.stone, 13);
        replacement.mainInventory[10] = new ItemStack(Blocks.dirt, 2);
        int originalStock = stock(player, Blocks.stone);
        int writesBefore = world.writes()
            .size();
        String beforePosition = HologramPlacementFixtureWorld.position(7, 64, 0);
        var previousTiles = world.occupants;
        AtomicBoolean beforeWrite = new AtomicBoolean();
        world.occupants = new HashMap<String, TileEntity>() {

            @Override
            public TileEntity get(Object key) {
                if (beforePosition.equals(key) && world.writes()
                    .size() == writesBefore
                    && "world-pending".equals(pendingReceipt(player).getString("phase"))
                    && beforeWrite.compareAndSet(false, true)) {
                    unsafe.putObject(player, inventoryOffset, replacement);
                }
                return previousTiles == null ? null : previousTiles.get(key);
            }
        };
        try {
            SimpleAssert.that(
                !HologramWorldTransactions.operateScoped(session, select(session, 7), 0, () -> true)
                    && beforeWrite.get()
                    && player.inventory == replacement
                    && stock(player, Blocks.stone) == 13
                    && world.writes()
                        .size() == writesBefore,
                "real final World read swaps inventory after the claim: no setter and no replacement charge");
        } finally {
            world.occupants = previousTiles;
            unsafe.putObject(player, inventoryOffset, original);
        }
        SimpleAssert.eq(originalStock, stock(player, Blocks.stone), "claim alone never pays from original stock");

        String setPosition = HologramPlacementFixtureWorld.position(8, 64, 0);
        AtomicBoolean insideSetter = new AtomicBoolean();
        Runnable restoreWrites = afterSetter(world, setPosition, () -> {
            SimpleAssert
                .eq(originalStock, stock(player, Blocks.stone), "native setter runs before the final real debit");
            unsafe.putObject(player, inventoryOffset, replacement);
            insideSetter.set(true);
        });
        try {
            SimpleAssert.that(
                !HologramWorldTransactions.operateScoped(session, select(session, 8), 0, () -> true)
                    && insideSetter.get()
                    && player.inventory == replacement
                    && stock(player, Blocks.stone) == 13
                    && stock(player, Blocks.dirt) == 2
                    && world.getBlock(8, 64, 0) == Blocks.air
                    && world.writes()
                        .size() == writesBefore + 2,
                "swap during actual setter: tentative place and exact restore, never charged replacement stock");
        } finally {
            restoreWrites.run();
            unsafe.putObject(player, inventoryOffset, original);
        }
        SimpleAssert.that(
            stock(player, Blocks.stone) == originalStock && stock(player, Blocks.gravel) == 5
                && !HologramConstructionJournal.pending(player)
                && HologramConstructionJournal.publications == publications,
            "same-world escrow abort preserves both inventories, unrelated slot and journal publication count");
    }

    private static Runnable afterSetter(HologramPlacementFixtureWorld world, String target, Runnable action)
        throws ReflectiveOperationException {
        return afterSetter(world, target, 1, action);
    }

    @SuppressWarnings("unchecked")
    private static Runnable afterSetter(HologramPlacementFixtureWorld world, String target, int ordinal,
        Runnable action) throws ReflectiveOperationException {
        var field = HologramPlacementFixtureWorld.class.getDeclaredField("writes");
        field.setAccessible(true);
        var previous = (List<String>) field.get(world);
        var watched = new ArrayList<String>(previous) {

            private int seen;

            @Override
            public boolean add(String position) {
                boolean added = super.add(position);
                if (target.equals(position) && ++seen == ordinal) action.run();
                return added;
            }
        };
        field.set(world, watched);
        return () -> {
            previous.clear();
            previous.addAll(watched);
            try {
                field.set(world, previous);
            } catch (IllegalAccessException failure) {
                throw new AssertionError("restore fixture world setter", failure);
            }
        };
    }

    private static void setSaveRoot(sun.misc.Unsafe unsafe, World world, Path root)
        throws ReflectiveOperationException {
        var field = World.class.getDeclaredField("saveHandler");
        var handler = (ISaveHandler) Proxy.newProxyInstance(
            ISaveHandler.class.getClassLoader(),
            new Class<?>[] { ISaveHandler.class },
            (proxy, method, args) -> {
                if ("getWorldDirectory".equals(method.getName())) return root.toFile();
                throw new UnsupportedOperationException("Unexpected fixture save handler method: " + method.getName());
            });
        unsafe.putObject(world, unsafe.objectFieldOffset(field), handler);
    }

    private static Path constructionFile(Path root, EntityPlayerMP player) {
        return root.resolve("gtmb-construction-recovery")
            .resolve(player.getUniqueID() + ".nbt");
    }

    private static NBTTagCompound pendingReceipt(EntityPlayerMP player) {
        return HologramConstructionJournal.owner(player)
            .getCompoundTag(HologramConstructionJournal.KEY);
    }

    private static void assertPersisted(Path path, NBTTagCompound exact, World world) throws IOException {
        var saved = HologramSnapshotCodec.decode(Files.readAllBytes(path));
        SimpleAssert.that(
            saved.equals(exact) && saved.getBoolean("durable")
                && saved.getInteger("dimension") == world.provider.dimensionId
                && "world-or-inventory-binding-changed".equals(saved.getString("scopeUncertainReason")),
            "exact original journal, revision, dimension and reason persisted");
    }

    private static void expectResolutionRefusal(EntityPlayerMP player, NBTTagCompound receipt, String action) {
        try {
            HologramConstructionJournal.resolve(player, receipt.getString("id"), action);
            throw new AssertionError("unsafe scoped journal resolution accepted " + action);
        } catch (IllegalStateException expected) {}
    }

    private static EventBus observing(HologramPlacementFixtureWorld world, EntityPlayerMP actor, int x,
        Runnable action) {
        return new EventBus() {

            private boolean fired;

            @Override
            public boolean post(Event event) {
                if (!fired && event instanceof BlockEvent.PlaceEvent place
                    && place.player == actor
                    && place.world == world
                    && place.x == x
                    && world.getBlock(x, 64, 0) == Blocks.stone) {
                    fired = true;
                    action.run();
                }
                return false;
            }
        };
    }

    private static void dormantUnchanged(NBTTagCompound expected, EntityPlayerMP player, String detail) {
        SimpleAssert.that(
            expected.equals(HologramConstructionJournal.owner(player)) && stock(player, Blocks.cobblestone) == 0,
            detail);
    }

    private static int stock(EntityPlayerMP player, Block block) {
        Item item = Item.getItemFromBlock(block);
        int total = 0;
        for (ItemStack stack : player.inventory.mainInventory)
            if (stack != null && stack.getItem() == item) total += stack.stackSize;
        return total;
    }

    private static HologramCapture.Cell select(HologramSession s, int x) {
        var trigger = HologramSessionPolicy.trigger(s);
        var capture = new HologramCapture(null, trigger);
        var cell = new HologramCapture.Cell(null, x, 64, 0);
        cell.block = Blocks.stone;
        cell.meta = cell.blueprintDamage = cell.stableIndex = 0;
        cell.blueprintBlock = "minecraft:stone";
        cell.blueprintItem = "minecraft:stone";
        cell.userPosition = true; // This is the explicit fixed prefab target emitted by the plain projected driver.
        cell.role = "prefab";
        capture.cells.add(cell);
        s.capture = capture;
        s.pins.put(0, new ItemStack(Blocks.stone));
        s.planTargets.clear();
        s.planTargets.add(new ItemStack(Blocks.stone));
        return cell;
    }
}
