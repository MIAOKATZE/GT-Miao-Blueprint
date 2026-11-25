// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.structure.ICustomBlockSetting;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;

final class HologramIncrementalNativePlacementRegression {

    private static final String SOURCE = "gtmb_test:incremental_native";
    private static final HologramIncrementalNativePlacement.Consent CONSENT =
        HologramIncrementalNativePlacement.Consent.PLACE_SAFE_CELLS_WITH_AVAILABLE_STOCK;

    private HologramIncrementalNativePlacementRegression() {}

    static void run() {
        var previousRoot = HologramConstructionJournal.STORAGE_ROOT.get();
        EntityPlayerMP player = null;
        java.nio.file.Path scratch = null;
        try {
            var unsafeAccess = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeAccess.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeAccess.get(null);
            scratch = Files.createTempDirectory("gtmb-incremental-native-");
            HologramConstructionJournal.STORAGE_ROOT.set(scratch);
            HologramWorkspace.clear();
            HologramProjectedPlacementService.clear();
            var world = HologramPlacementFixtureWorld.create(unsafe);
            player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "IncrementalNativeFixture");
            player.capabilities.isCreativeMode = false;
            player.capabilities.allowEdit = true;
            player.posX = player.posZ = .5;
            player.posY = 64;
            var machine = new HologramDynamicDefinitionRegression.DynamicController();
            var tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
                .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
            tile.setWorldObj(world);
            tile.xCoord = 0;
            tile.yCoord = 64;
            tile.zCoord = 0;
            machine.setBaseMetaTileEntity(tile);
            world.occupiedX = 0;
            world.occupant = tile;
            world.occupants = java.util.Collections.singletonMap("0,64,0", tile);
            world.seed(0, 64, 0, GregTechAPI.sBlockMachines, 0);
            player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 2);
            HologramSession session = new HologramSession(player, 0, 64, 0, true, 2);
            session.materialSettings.main = true;
            session.materialSettings.containers = session.materialSettings.me = false;
            HologramWorkspace.activate(player.getUniqueID(), session);
            AtomicLong generation = new AtomicLong(11);
            var owner = new HologramIncrementalNativePlacement.Owner(
                session, player.playerNetServerHandler, generation.get(), generation::get);

            char[] row = new char[4096];
            Arrays.fill(row, 'X');
            var definition = StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
                .addShape("preset", new String[][] { { new String(row) } })
                .addElement('X', StructureUtility.ofBlock(Blocks.stone, 0))
                .build();
            machine.presetActionDefinition = definition;
            UUID request = UUID.randomUUID();
            HologramIncrementalNativePlacement placement;
            try (var pin = StructureSources.registerCompactNative(
                SOURCE, HologramDynamicDefinitionRegression.DynamicController.class, definition, "preset", -1, 0, 0, 1)) {
                expectRefusal(owner, request, SOURCE, null);
                expectRefusal(owner, request, "gtmb_test:wrong_source", CONSENT);
                placement = owner.begin(request, SOURCE, CONSENT);
                UUID competing = UUID.randomUUID();
                expectRefusal(owner, competing, SOURCE, CONSENT);
                SimpleAssert.that(!HologramProjectedPlacementService.consumed(player, competing.toString()),
                    "a different UUID cannot claim the live slot or consume a retry capability");
                SimpleAssert.eq(0, placement.visited(), "request admission does not walk even the first declaration row");
                SimpleAssert.eq(
                    HologramIncrementalNativePlacement.Result.YIELDED,
                    placement.step(Long.MIN_VALUE),
                    "expired slice cannot pay or walk");
                SimpleAssert.eq(0, placement.visited(), "expired slice preserves starting cursor");
                world.arm();
                int callbacks = machine.constructions + machine.definitionBuilds;
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.PLACED, placement.step(Long.MAX_VALUE),
                    "real first owner slice commits a block, not merely an acknowledgement");
                int[] offset = new int[3];
                machine.getExtendedFacing().getWorldOffset(new int[] { 1, 0, 0 }, offset);
                String first = HologramPlacementFixtureWorld.position(offset[0], 64 + offset[1], offset[2]);
                SimpleAssert.that(world.writes().equals(java.util.Collections.singletonList(first))
                    && world.getBlock(offset[0], 64 + offset[1], offset[2]) == Blocks.stone,
                    "exact transformed first block is written once; controller is untouched");
                SimpleAssert.that(placement.total() >= 4096 && placement.visited() <= 2 && placement.placed() == 1,
                    "exact first success has not walked or materialized the entire native shape");
                SimpleAssert.eq(1, player.inventory.mainInventory[1].stackSize, "exact survival debit is one stone");
                SimpleAssert.eq(callbacks, machine.constructions + machine.definitionBuilds,
                    "pinned initiation and first commit never call the machine definition getter or construct");
                SimpleAssert.that(session.capture == null && !HologramConstructionJournal.pending(player),
                    "one-row transaction leaves no UI capture and settles the actual journal");
                expectRefusal(owner, request, SOURCE, CONSENT);
            }
            SimpleAssert.eq(HologramIncrementalNativePlacement.Result.STOPPED, placement.step(Long.MAX_VALUE),
                "retired native source cannot resume the same cursor");
            SimpleAssert.that(world.writes().size() == 1 && player.inventory.mainInventory[1].stackSize == 1,
                "old or replayed request cannot write or debit another block");

            var unknown = StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
                .addShape("preset", new String[][] { { "X" } })
                .addElement('X', StructureUtility.ofBlock(Blocks.stone, 0, Blocks.cobblestone, 0))
                .build();
            machine.presetActionDefinition = unknown;
            try (var pin = StructureSources.registerCompactNative(
                SOURCE, HologramDynamicDefinitionRegression.DynamicController.class, unknown, "preset", -1, 0, 0, 1)) {
                var refused = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
                placement.cancel();
                expectRefusal(owner, UUID.randomUUID(), SOURCE, CONSENT);
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.STOPPED, refused.step(Long.MAX_VALUE),
                    "different acceptance and placement blocks are not an exact supported element");
                SimpleAssert.that(!refused.detail().isEmpty() && world.writes().size() == 1
                    && player.inventory.mainInventory[1].stackSize == 1,
                    "unsupported declaration stops explicitly without an ordinary fallback or another payment");
            }
            var shortDefinition = StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
                .addShape("preset", new String[][] { { "XX" } })
                .addElement('X', StructureUtility.ofBlock(Blocks.stone, 0))
                .build();
            machine.presetActionDefinition = shortDefinition;
            try (var pin = StructureSources.registerCompactNative(
                SOURCE, HologramDynamicDefinitionRegression.DynamicController.class, shortDefinition, "preset", -1, 0, 0, 1)) {
                var cancelled = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
                cancelled.cancel();
                var partial = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
                cancelled.cancel();
                expectRefusal(owner, UUID.randomUUID(), SOURCE, CONSENT);
                world.denyMine = true;
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.PARTIAL, partial.step(Long.MAX_VALUE),
                    "a skipped protected cell cannot report complete");
                world.denyMine = false;
                SimpleAssert.that(partial.skipped() == 1 && partial.placed() == 0 && !partial.detail().isEmpty(),
                    "the terminal retains truthful protected outcome counts");
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.PARTIAL, partial.step(Long.MAX_VALUE),
                    "the released task retains its terminal outcome");

                var moved = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
                long reads = worldReads(world);
                session.x++;
                try {
                    SimpleAssert.eq(HologramIncrementalNativePlacement.Result.STOPPED, moved.step(Long.MAX_VALUE),
                        "changing the bound anchor rejects before reading a new position");
                } finally {
                    session.x--;
                }
                SimpleAssert.eq(reads, worldReads(world), "a moved anchor may not make a late coordinate read");

                var stale = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
                reads = worldReads(world);
                generation.incrementAndGet();
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.STOPPED, stale.step(Long.MIN_VALUE),
                    "retired generation releases even when there is no work budget");
                SimpleAssert.eq(0, stale.visited(), "retired generation does not advance cursor");
                SimpleAssert.eq(reads, worldReads(world), "retired generation makes no world read");
                expectRefusal(owner, UUID.randomUUID(), SOURCE, CONSENT);

                var authenticated = player.playerNetServerHandler;
                var nextOwner = new HologramIncrementalNativePlacement.Owner(
                    session, authenticated, generation.get(), generation::get);
                var disconnected = nextOwner.begin(UUID.randomUUID(), SOURCE, CONSENT);
                reads = worldReads(world);
                player.playerNetServerHandler = null;
                try {
                    SimpleAssert.eq(HologramIncrementalNativePlacement.Result.STOPPED, disconnected.step(Long.MAX_VALUE),
                        "a changed connection is rejected even with unchanged generation");
                } finally {
                    player.playerNetServerHandler = authenticated;
                }
                SimpleAssert.eq(reads, worldReads(world), "disconnected cursor makes no late coordinate read");
                expectRefusal(nextOwner, UUID.randomUUID(), SOURCE, CONSENT);
                nextOwner.retire();
                SimpleAssert.that(world.writes().size() == 1 && player.inventory.mainInventory[1].stackSize == 1,
                    "no stale, cancelled or protected request adds a write or debit");
            }
            testThreeDimensionalNavigation(world, player, session, machine);
            testPinnedSourceMutation(world, player, session, machine);
            testCallbackFencesAndDrain(world, player, session, machine);
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("pinned incremental native placement fixture", failure);
        } finally {
            HologramWorkspace.clear();
            HologramProjectedPlacementService.clear();
            if (player != null) player.playerNetServerHandler.netManager.channel().close();
            if (previousRoot == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(previousRoot);
            if (scratch != null) try (var files = Files.walk(scratch)) {
                var paths = files.sorted(Comparator.reverseOrder()).iterator();
                while (paths.hasNext()) Files.deleteIfExists(paths.next());
            } catch (IOException failure) {
                throw new AssertionError("delete incremental native fixture", failure);
            }
        }
    }

    private static void testThreeDimensionalNavigation(HologramPlacementFixtureWorld world, EntityPlayerMP player,
        HologramSession session, HologramDynamicDefinitionRegression.DynamicController machine) throws IOException {
        var definition = StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
            .addShape("preset", new String[][] { { " XX", "X  " }, { "   ", "  X" } })
            .addElement('X', StructureUtility.ofBlock(Blocks.stone, 0))
            .build();
        var stepTypes = new HashSet<Class<?>>();
        for (IStructureElement<?> element : definition.getStructureFor("preset"))
            if (HologramIncrementalNativePlacement.isNativeNavigation(element)) stepTypes.add(element.getClass());
        SimpleAssert.eq(3, stepTypes.size(), "the real 3-D builder emits all three native navigation classes");
        SimpleAssert.that(stepTypes.contains(((Object) StructureUtility.step(1, 0, 0)).getClass())
            && stepTypes.contains(((Object) StructureUtility.step(0, 1, 0)).getClass())
            && stepTypes.contains(((Object) StructureUtility.step(0, 0, 1)).getClass()),
            "trusted navigation is exactly the three public factory class witnesses");
        var foreign = (IStructureElement<?>) Proxy.newProxyInstance(
            IStructureElement.class.getClassLoader(), new Class<?>[] { IStructureElement.class },
            (proxy, method, args) -> { throw new AssertionError("must not invoke custom navigation " + method); });
        SimpleAssert.that(!HologramIncrementalNativePlacement.isNativeNavigation(foreign),
            "a custom element cannot gain native navigation eligibility through a callback");

        machine.presetActionDefinition = definition;
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 5);
        var owner = new HologramIncrementalNativePlacement.Owner(session, player.playerNetServerHandler, 13L, () -> 13L);
        List<String> expected = Arrays.asList(
            transformed(machine, 31, 0, 0),
            transformed(machine, 32, 0, 0),
            transformed(machine, 30, 1, 0),
            transformed(machine, 32, 1, 1));
        int before = world.writes().size();
        UUID request = UUID.randomUUID();
        try (var pin = StructureSources.registerCompactNative(
            SOURCE, HologramDynamicDefinitionRegression.DynamicController.class, definition, "preset", -30, 0, 0, 1)) {
            var task = owner.begin(request, SOURCE, CONSENT);
            var tileReads = new ArrayList<Object>();
            Runnable restore = observeTileReads(world, tileReads::add);
            try {
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.PLACED, task.step(Long.MAX_VALUE),
                    "the real first 3-D cell commits after an A skip, not after walking the whole declaration");
            } finally {
                restore.run();
            }
            SimpleAssert.that(task.visited() == 2 && task.placed() == 1
                && world.writes().size() == before + 1 && expected.get(0).equals(world.writes().get(before))
                && player.inventory.mainInventory[1].stackSize == 4,
                "one exact world write and debit occur before the 3-D declaration tail");
            SimpleAssert.that(tileReads.contains(expected.get(0))
                && expected.subList(1, expected.size()).stream().noneMatch(tileReads::contains),
                "the first commit inspects its own tile, not any later 3-D candidate");

            SimpleAssert.eq(HologramIncrementalNativePlacement.Result.PLACED, task.drain(Long.MAX_VALUE, 32, 3),
                "the same cursor traverses the B/C resets without restart or replacement");
            SimpleAssert.eq(HologramIncrementalNativePlacement.Result.COMPLETE, task.step(Long.MAX_VALUE),
                "all native 3-D cells reach the truthful terminal");
            SimpleAssert.that(world.writes().subList(before, world.writes().size()).equals(expected)
                && task.visited() == task.total() && task.placed() == 4 && task.skipped() == 0
                && player.inventory.mainInventory[1].stackSize == 1
                && !HologramConstructionJournal.pending(player),
                "loop resets yield exact per-axis world coordinates, four successful debits and no unresolved receipt");
            expectRefusal(owner, request, SOURCE, CONSENT);
        } finally {
            owner.retire();
        }
    }

    @SuppressWarnings("unchecked")
    private static void testPinnedSourceMutation(HologramPlacementFixtureWorld world, EntityPlayerMP player,
        HologramSession session, HologramDynamicDefinitionRegression.DynamicController machine) throws IOException {
        var definition = StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
            .addShape("preset", new String[][] { { "XX" } })
            .addElement('X', StructureUtility.ofBlock(Blocks.stone, 0))
            .build();
        var nativeDefinition = (StructureDefinition<HologramDynamicDefinitionRegression.DynamicController>) definition;
        var entries = nativeDefinition.getStructureFor("preset");
        var approved = entries[0];
        var foreign = (IStructureElement<HologramDynamicDefinitionRegression.DynamicController>) Proxy.newProxyInstance(
            IStructureElement.class.getClassLoader(), new Class<?>[] { IStructureElement.class },
            (proxy, method, args) -> { throw new AssertionError("swapped declaration callback " + method); });
        machine.presetActionDefinition = definition;
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 3);
        var owner = new HologramIncrementalNativePlacement.Owner(session, player.playerNetServerHandler, 14L, () -> 14L);
        int before = world.writes().size();
        String anchor = HologramPlacementFixtureWorld.position(0, 64, 0);
        String first = transformed(machine, 40);
        try (var pin = StructureSources.registerCompactNative(
            SOURCE, HologramDynamicDefinitionRegression.DynamicController.class, definition, "preset", -40, 0, 0, 1)) {
            var beforeCursor = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
            AtomicInteger anchorReads = new AtomicInteger();
            Runnable restore = observeTileReads(world, key -> {
                if (anchor.equals(key) && anchorReads.incrementAndGet() == 2) entries[0] = foreign;
            });
            try {
                assertNoSettledMutation(beforeCursor, world, player, before, 0,
                    "a native callback cannot replace the authorized row before cursor execution");
                SimpleAssert.eq(2, anchorReads.get(), "swap follows row proof but precedes cursor execution");
            } finally {
                restore.run();
                entries[0] = approved;
            }

            var backingChanged = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
            anchorReads.set(0);
            restore = observeTileReads(world, key -> {
                if (anchor.equals(key) && anchorReads.incrementAndGet() == 2)
                    nativeDefinition.getStructures().put("preset", entries.clone());
            });
            try {
                assertNoSettledMutation(backingChanged, world, player, before, 0,
                    "substitution of the backing array, even with the same elements, changes the source lease");
                SimpleAssert.eq(2, anchorReads.get(), "same-content array replacement ran at the authority read");
            } finally {
                restore.run();
                nativeDefinition.getStructures().put("preset", entries);
            }

            var beforeWrite = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
            AtomicInteger candidateReads = new AtomicInteger();
            restore = observeTileReads(world, key -> {
                if (first.equals(key) && candidateReads.incrementAndGet() == 2) entries[0] = foreign;
            });
            try {
                assertNoSettledMutation(beforeWrite, world, player, before, 1,
                    "the already-located row is re-fenced after the final tile read and before payment/world mutation");
                SimpleAssert.eq(2, candidateReads.get(), "source changes after the real candidate's last tile read");
            } finally {
                restore.run();
                entries[0] = approved;
            }
        }
        var custom = StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
            .addShape("preset", new String[][] { { "X" } })
            .addElement('X', StructureUtility.ofBlock(new CustomSetterBlock(), 0))
            .build();
        try {
            machine.presetActionDefinition = custom;
            try (var pin = StructureSources.registerCompactNative(
                SOURCE, HologramDynamicDefinitionRegression.DynamicController.class, custom, "preset", -40, 0, 0, 1)) {
                assertNoSettledMutation(owner.begin(UUID.randomUUID(), SOURCE, CONSENT), world, player, before, 0,
                    "the exact native ofBlock factory cannot invoke a foreign tile test or setter");
            }
            machine.presetActionDefinition = definition;
            try (var pin = StructureSources.registerCompactNative(
                SOURCE, HologramDynamicDefinitionRegression.DynamicController.class, definition, "preset", -40, 0, 0, 1)) {
                var next = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.PLACED, next.step(Long.MAX_VALUE),
                    "the same slot and owner may still commit a genuinely approved vanilla declaration");
                SimpleAssert.that(next.visited() == 1 && next.placed() == 1 && world.writes().size() == before + 1
                    && first.equals(world.writes().get(before)) && player.inventory.mainInventory[1].stackSize == 2
                    && !HologramConstructionJournal.pending(player),
                    "exact native world write and payment survive the callback-free declaration proof");
                next.cancel();
            }
        } finally {
            owner.retire();
        }
    }

    private static void assertNoSettledMutation(HologramIncrementalNativePlacement task,
        HologramPlacementFixtureWorld world, EntityPlayerMP player, int before, int visited, String message) {
        SimpleAssert.eq(HologramIncrementalNativePlacement.Result.STOPPED, task.step(Long.MAX_VALUE), message);
        SimpleAssert.that(task.visited() == visited && task.placed() == 0 && world.writes().size() == before
            && player.inventory.mainInventory[1].stackSize == 3 && !HologramConstructionJournal.pending(player),
            "refusing a swapped or unsupported source leaves exact world and stock untouched");
    }

    private static final class CustomSetterBlock extends Block implements ICustomBlockSetting {

        CustomSetterBlock() {
            super(Material.rock);
        }

        @Override
        public boolean hasTileEntity(int metadata) {
            throw new AssertionError("unapproved block callback");
        }

        @Override
        public void setBlock(World world, int x, int y, int z, int metadata) {
            throw new AssertionError("unapproved block setter");
        }
    }

    private static void testCallbackFencesAndDrain(HologramPlacementFixtureWorld world, EntityPlayerMP player,
        HologramSession session, HologramDynamicDefinitionRegression.DynamicController machine) throws IOException {
        var definition = StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
            .addShape("preset", new String[][] { { "XXXXXX" } })
            .addElement('X', StructureUtility.ofBlock(Blocks.stone, 0))
            .build();
        machine.presetActionDefinition = definition;
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 6);
        AtomicReference<Runnable> observeGeneration = new AtomicReference<>();
        var owner = new HologramIncrementalNativePlacement.Owner(session, player.playerNetServerHandler, 12L, () -> {
            Runnable callback = observeGeneration.getAndSet(null);
            if (callback != null) callback.run();
            return 12L;
        });
        int before = world.writes().size();
        try (var pin = StructureSources.registerCompactNative(
            SOURCE, HologramDynamicDefinitionRegression.DynamicController.class, definition, "preset", -10, 0, 0, 1)) {
            UUID nested = UUID.randomUUID();
            observeGeneration.set(() -> expectRefusal(owner, nested, SOURCE, CONSENT));
            var cancelling = owner.begin(UUID.randomUUID(), SOURCE, CONSENT);
            SimpleAssert.that(observeGeneration.get() == null
                && !HologramProjectedPlacementService.consumed(player, nested.toString()),
                "reserve the live slot before a generation callback can re-enter admission");
            String first = transformed(machine, 10);
            AtomicInteger targetTileReads = new AtomicInteger();
            Runnable restore = observeTileReads(world, key -> {
                if (first.equals(key) && targetTileReads.incrementAndGet() == 2) cancelling.cancel();
            });
            try {
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.STOPPED, cancelling.step(Long.MAX_VALUE),
                    "cancellation inside the final candidate tile read stops before starting the transaction");
            } finally {
                restore.run();
            }
            SimpleAssert.eq(2, targetTileReads.get(), "cancellation ran at the last pre-transaction candidate tile read");
            SimpleAssert.that(cancelling.placed() == 0 && world.writes().size() == before
                && player.inventory.mainInventory[1].stackSize == 6 && !HologramConstructionJournal.pending(player),
                "last-read cancellation does not touch the real world, inventory or construction journal");

            var running = owner.begin(nested, SOURCE, CONSENT);
            SimpleAssert.eq(HologramIncrementalNativePlacement.Result.YIELDED, running.drain(Long.MIN_VALUE, 4, 4),
                "a continuous drain with an expired deadline does not advance");
            SimpleAssert.eq(0, running.visited(), "expired continuous drain does not walk the declaration");
            SimpleAssert.eq(HologramIncrementalNativePlacement.Result.PLACED, running.drain(Long.MAX_VALUE, 2, 4),
                "one native drain commits multiple independent cells up to the caller's work quota");
            SimpleAssert.that(running.visited() == 2 && running.placed() == 2 && world.writes().size() == before + 2
                && player.inventory.mainInventory[1].stackSize == 4,
                "two exact native writes, two exact survival debits, no tail walk");
            SimpleAssert.eq(HologramIncrementalNativePlacement.Result.PLACED, running.drain(Long.MAX_VALUE, 5, 1),
                "the same job resumes immediately and respects the independent commit quota");
            SimpleAssert.that(running.visited() == 3 && running.placed() == 3 && world.writes().size() == before + 3
                && player.inventory.mainInventory[1].stackSize == 3,
                "the single-commit quota neither restarts the cursor nor processes an extra coordinate");
            for (int i = 0; i < 3; i++) {
                SimpleAssert.eq(transformed(machine, 10 + i), world.writes().get(before + i),
                    "continuous driver preserves authoritative StructureLib traversal order");
            }

            // Model a re-entrant stop observation after the world write, while the real transaction settles normally.
            String fourth = transformed(machine, 13);
            String stopDetail = "fixture: recovery stop after committed world write";
            AtomicInteger stopSignals = new AtomicInteger();
            restore = observeTileReads(world, key -> {
                if (fourth.equals(key) && world.writes().size() == before + 4 && stopSignals.getAndIncrement() == 0) {
                    session.job = 4;
                    session.status = stopDetail;
                }
            });
            try {
                SimpleAssert.eq(HologramIncrementalNativePlacement.Result.STOPPED, running.drain(Long.MAX_VALUE, 6, 6),
                    "a stop signalled during successful transaction settlement stops the continuous driver");
            } finally {
                restore.run();
            }
            SimpleAssert.that(stopSignals.get() > 0 && running.placed() == 4 && running.skipped() == 0
                && running.visited() == 4 && world.writes().size() == before + 4
                && world.writes().get(before + 3).equals(fourth) && player.inventory.mainInventory[1].stackSize == 2
                && stopDetail.equals(running.detail()) && !HologramConstructionJournal.pending(player),
                "the settled write and exact debit count before STOPPED; no fifth coordinate or false rollback");
        } finally {
            owner.retire();
        }
    }

    private static String transformed(HologramDynamicDefinitionRegression.DynamicController machine, int a) {
        return transformed(machine, a, 0, 0);
    }

    private static String transformed(HologramDynamicDefinitionRegression.DynamicController machine, int a, int b, int c) {
        int[] offset = new int[3];
        machine.getExtendedFacing().getWorldOffset(new int[] { a, b, c }, offset);
        return HologramPlacementFixtureWorld.position(offset[0], 64 + offset[1], offset[2]);
    }

    private static Runnable observeTileReads(HologramPlacementFixtureWorld world, Consumer<Object> reader) {
        Map<String, TileEntity> previous = world.occupants;
        world.occupants = new HashMap<String, TileEntity>(previous) {
            @Override
            public TileEntity get(Object key) {
                reader.accept(key);
                return super.get(key);
            }
        };
        return () -> world.occupants = previous;
    }

    private static long worldReads(HologramPlacementFixtureWorld world) {
        return world.loadReads + world.blockReads + world.metaReads + world.tileReads + world.permissionReads;
    }

    private static void expectRefusal(HologramIncrementalNativePlacement.Owner owner, UUID request, String source,
        HologramIncrementalNativePlacement.Consent consent) {
        try {
            owner.begin(request, source, consent);
            throw new AssertionError("native incremental admission accepted changed authority");
        } catch (IOException expected) {}
    }

}
