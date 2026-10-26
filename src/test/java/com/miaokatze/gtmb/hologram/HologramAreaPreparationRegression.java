package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Native traversal oracle, operation-count stress and detached import ownership; every world write is forbidden. */
final class HologramAreaPreparationRegression {

    static void run() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            CountingWorld world = (CountingWorld) unsafe.allocateInstance(CountingWorld.class);
            cursorOracle(world);
            linearStress(world);
            canonicalOwnership();
            nativeSanitationYieldsOnOwner();
            workerAdmissionAndCancellation();
            structureIndex();
            stagingOwnership(unsafe, world);
        } catch (Exception failure) {
            throw new AssertionError("area preparation", failure);
        }
    }

    private static void cursorOracle(CountingWorld world) {
        IStructureDefinition<Object> definition = StructureDefinition.<Object>builder()
            .addShape("mixed", new String[][] { { "A A", " AA", "A  " }, { " AA", "A A", " AA" } })
            .addElement('A', StructureUtility.ofBlock(Blocks.stone, 0))
            .build();
        IStructureElement<?>[] elements = definition.getStructureFor("mixed");
        for (ExtendedFacing facing : ExtendedFacing.values()) {
            List<String> expected = new ArrayList<>(), actual = new ArrayList<>();
            definition.iterate("mixed", world, facing, 4, 70, -3, 2, 1, 1, recording(expected));
            HologramStructureCursor cursor = new HologramStructureCursor(elements, 2, 1, 1);
            while (!cursor.advance(world, facing, 4, 70, -3, recording(actual), 2, Long.MAX_VALUE)) {}
            SimpleAssert
                .eq(expected, actual, "persistent cursor preserves native navigation/offsets/facing/unloaded order");
        }
        List<String> actual = new ArrayList<>();
        HologramStructureCursor stopped = new HologramStructureCursor(elements, 0, 0, 0);
        IStructureWalker<Object> stop = new IStructureWalker<Object>() {

            public boolean visit(IStructureElement<Object> element, World w, int x, int y, int z, int a, int b, int c) {
                actual.add("stop");
                return false;
            }
        };
        SimpleAssert.that(
            stopped.advance(world, ExtendedFacing.DEFAULT, 0, 64, 0, stop, 100, Long.MAX_VALUE),
            "native walker rejection finishes the piece without replaying it");
        SimpleAssert.eq(1, actual.size(), "walker rejection cannot execute later declarations");
    }

    private static IStructureWalker<Object> recording(List<String> result) {
        return new IStructureWalker<Object>() {

            public boolean visit(IStructureElement<Object> element, World w, int x, int y, int z, int a, int b, int c) {
                result.add("loaded:" + x + "," + y + "," + z + ":" + a + "," + b + "," + c);
                return true;
            }

            public boolean blockNotLoaded(IStructureElement<Object> element, World w, int x, int y, int z, int a, int b,
                int c) {
                result.add("unloaded:" + x + "," + y + "," + z + ":" + a + "," + b + "," + c);
                return true;
            }
        };
    }

    private static void linearStress(CountingWorld world) {
        String[] rows = new String[256];
        Arrays.fill(rows, new String(new char[512]).replace('\0', 'A'));
        IStructureElement<?>[] elements = StructureDefinition.<Object>builder()
            .addShape("large", new String[][] { rows })
            .addElement('A', StructureUtility.ofBlock(Blocks.stone, 0))
            .build()
            .getStructureFor("large");
        HologramStructureCursor cursor = new HologramStructureCursor(elements, 0, 0, 0);
        int[] visits = { 0 };
        IStructureWalker<Object> count = new IStructureWalker<Object>() {

            public boolean visit(IStructureElement<Object> element, World w, int x, int y, int z, int a, int b, int c) {
                visits[0]++;
                return true;
            }

            public boolean blockNotLoaded(IStructureElement<Object> element, World w, int x, int y, int z, int a, int b,
                int c) {
                visits[0]++;
                return true;
            }
        };
        world.reads = 0;
        int batches = 0;
        do {
            int before = cursor.done(), reads = world.reads;
            cursor.advance(world, ExtendedFacing.DEFAULT, 0, 64, 0, count, 127, Long.MAX_VALUE);
            SimpleAssert.that(
                cursor.done() - before <= 127 && world.reads - reads <= 127,
                "each continuation bounds navigation and world reads independently of the completed prefix");
            batches++;
        } while (cursor.done() < cursor.total());
        SimpleAssert.eq(131072, visits[0], "all cells of the 131072-cell native shape are visited exactly once");
        SimpleAssert.eq(131072, world.reads, "world read complexity stays linear across over one thousand batches");
        SimpleAssert.that(batches > 1000, "stress exercises many real continuation boundaries");
        IStructureElement<?>[] navigation = new IStructureElement<?>[65536];
        Arrays.fill(navigation, StructureUtility.step(0, 1, 0));
        HologramStructureCursor empty = new HologramStructureCursor(navigation, 0, 0, 0);
        empty.advance(world, ExtendedFacing.DEFAULT, 0, 64, 0, count, 127, Long.MAX_VALUE);
        SimpleAssert.eq(127, empty.done(), "navigation-only tails consume the same bounded work budget");
        int before = empty.done();
        empty.advance(world, ExtendedFacing.DEFAULT, 0, 64, 0, count, 127, Long.MIN_VALUE);
        SimpleAssert.eq(before + 1, empty.done(), "an expired time slice yields after at most one atomic operation");
    }

    private static void canonicalOwnership() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.schema = 2;
        source.deidentificationLevel = 1;
        HologramPresets.Cell plain = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("owner", "source-owner");
        payload.setTag("recipe", new ItemStack(Blocks.diamond_block, 7).writeToNBT(new NBTTagCompound()));
        plain.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        source.cells.add(plain);
        String original = new com.google.gson.Gson().toJson(source);
        HologramAePolicy.Draft ordinary = HologramAePolicy.sanitize(source);
        HologramPresets.Preset ownedSource = HologramPresets.copy(source);
        List<String> phases = new ArrayList<>();
        HologramAePolicy.Draft owned = HologramAePolicy
            .sanitizeOwned(ownedSource, Collections.emptyMap(), (phase, done, total) -> {
                SimpleAssert.that(done >= 0 && done <= total, "canonical phase counters describe actual bounded units");
                if (phases.isEmpty() || !phase.equals(phases.get(phases.size() - 1))) phases.add(phase);
            });
        SimpleAssert.that(owned.preset == ownedSource, "exclusive DTO ownership removes redundant JSON deep copies");
        SimpleAssert.eq(
            new com.google.gson.Gson().toJson(ordinary.preset),
            new com.google.gson.Gson().toJson(owned.preset),
            "owned canonicalization preserves existing NBT projection and issues exactly");
        SimpleAssert.eq(
            original,
            new com.google.gson.Gson().toJson(source),
            "ordinary callers retain immutable source semantics");
        SimpleAssert.that(
            phases.containsAll(Arrays.asList("identity", "networks", "deidentify", "ae", "report")),
            "canonicalization reports independently completed data passes");
        HologramPresets.Preset network = new HologramPresets.Preset();
        network.cells.add(anchored(1));
        network.cells.add(anchored(2));
        Map<String, String> frozen = new HashMap<>();
        frozen.put("-991/1/64/0/", "same-grid");
        frozen.put("-991/2/64/0/", "same-grid");
        HologramPresets.Preset canonical = HologramAePolicy
            .sanitizeOwned(network, frozen, HologramAePolicy.PreparationProgress.NONE).preset;
        String first = HologramNetworkBindings.group(HologramBlueprintNbt.decode(canonical.cells.get(0).tileNbt));
        String second = HologramNetworkBindings.group(HologramBlueprintNbt.decode(canonical.cells.get(1).tileNbt));
        SimpleAssert
            .eq(first, second, "frozen source grid identity groups distinct anchors without worker world resolution");
        SimpleAssert.that(
            !HologramBlueprintNbt.decode(canonical.cells.get(0).tileNbt)
                .hasKey(HologramNetworkBindings.CAPTURE_ANCHOR),
            "portable results discard live capture anchor metadata");
    }

    private static HologramPresets.Cell anchored(int x) throws Exception {
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), x, 0, 0, -1, false);
        NBTTagCompound anchor = new NBTTagCompound(), payload = new NBTTagCompound();
        anchor.setInteger("anchorDim", -991);
        anchor.setInteger("anchorX", x);
        anchor.setInteger("anchorY", 64);
        anchor.setInteger("anchorZ", 0);
        payload.setTag(HologramNetworkBindings.CAPTURE_ANCHOR, anchor);
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        return cell;
    }

    private static void workerAdmissionAndCancellation() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.FutureTask<Void> blocker = new java.util.concurrent.FutureTask<>(() -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("test worker release timed out");
            return null;
        });
        SimpleAssert.that(HologramAreaPreparation.execute(blocker), "bounded worker accepts one active data task");
        SimpleAssert
            .that(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "worker task starts away from caller");
        java.util.concurrent.FutureTask<Void> queued = new java.util.concurrent.FutureTask<>(() -> null);
        HologramAreaPreparation preparation = new HologramAreaPreparation();
        try {
            HologramPresets.Preset source = new HologramPresets.Preset();
            source.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
            String before = new com.google.gson.Gson().toJson(source);
            preparation.captureNetworks(source, 8, Long.MAX_VALUE);
            SimpleAssert.that(preparation.start(source), "one detached area is admitted behind active work");
            preparation.advance(8, Long.MAX_VALUE);
            SimpleAssert
                .that(HologramAreaPreparation.execute(queued), "only the second bounded queue position is admitted");
            SimpleAssert.that(
                !HologramAreaPreparation.execute(new java.util.concurrent.FutureTask<>(() -> null)),
                "an unbounded number of large area drafts cannot accumulate on the compute worker");
            preparation.cancel();
            HologramAreaPreparation.cancel(queued);
            release.countDown();
            blocker.get(5, java.util.concurrent.TimeUnit.SECONDS);
            SimpleAssert.eq(
                before,
                new com.google.gson.Gson().toJson(source),
                "a cancelled queued canonicalization never mutates or publishes its detached source");
        } finally {
            preparation.cancel();
            HologramAreaPreparation.cancel(queued);
            release.countDown();
            blocker.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void nativeSanitationYieldsOnOwner() throws Exception {
        appeng.block.GtmbAeOwnerFixture block = new appeng.block.GtmbAeOwnerFixture();
        int free = 4095;
        while (free > 0 && Block.getBlockById(free) != null && Block.getBlockById(free) != Blocks.air) free--;
        SimpleAssert.that(free > 0, "native owner fixture has a registry slot");
        Block.blockRegistry.addObject(free, "gtmb:ae_owner_fixture", block);
        appeng.block.GtmbAeOwnerFixture.owner = Thread.currentThread();
        appeng.block.GtmbAeOwnerFixture.factories = 0;
        HologramPresets.Preset source = new HologramPresets.Preset();
        for (int index = 0; index < 33; index++) {
            HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), index, 0, 0, -1, false);
            cell.block = Block.blockRegistry.getNameForObject(block);
            NBTTagCompound payload = new NBTTagCompound();
            payload.setInteger("fixture", index);
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
            source.cells.add(cell);
        }
        SimpleAssert.that(
            source.cells.get(0)
                .block() == block,
            "owner sanitation resolves the native AE fixture");
        HologramAreaPreparation preparation = new HologramAreaPreparation();
        preparation.captureNetworks(source, 64, Long.MAX_VALUE);
        preparation.start(source);
        long deadline = System.nanoTime() + 5_000_000_000L;
        int steps = 0;
        try {
            while (!preparation.ready() && System.nanoTime() < deadline) {
                int before = appeng.block.GtmbAeOwnerFixture.factories;
                preparation.advance(1, Long.MAX_VALUE);
                SimpleAssert.that(
                    appeng.block.GtmbAeOwnerFixture.factories - before <= 1,
                    "one owner continuation invokes at most one native cell factory");
                steps++;
                if (preparation.waiting()) Thread.yield();
            }
            SimpleAssert.that(preparation.ready(), "owner sanitation and detached reports finish together");
            SimpleAssert.eq(
                33,
                appeng.block.GtmbAeOwnerFixture.factories,
                "every distinct AE cell is analyzed through its real native factory on the owner");
            SimpleAssert.that(steps >= 66, "both native passes retain independent yield boundaries");
            SimpleAssert.that(preparation.result() == source, "sanitation retains exclusive private DTO ownership");
        } finally {
            preparation.cancel();
            appeng.block.GtmbAeOwnerFixture.owner = null;
        }
    }

    private static void structureIndex() {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        CountedStructures structures = new CountedStructures();
        preset.structures = structures;
        for (int i = 0; i < 8192; i++) {
            HologramPresets.Structure structure = new HologramPresets.Structure();
            structure.id = "s" + i;
            structures.add(structure);
        }
        HologramStructureIndex index = new HologramStructureIndex(preset);
        for (int i = 0; i < structures.size(); i++) index.add(i);
        structures.reads = 0;
        for (int i = 0; i < 8192; i++) SimpleAssert.eq(
            Boolean.TRUE,
            index.enabled(preset, Collections.singletonList("s" + i), ""),
            "indexed membership is enabled");
        SimpleAssert.eq(8192, structures.reads, "membership queries scale with memberships instead of all structures");
        structures.get(3).enabled = false;
        SimpleAssert.eq(
            Boolean.FALSE,
            index.enabled(preset, Collections.singletonList("s3"), ""),
            "live enable changes do not require index invalidation");
        SimpleAssert.eq(
            Boolean.FALSE,
            index.enabled(preset, Collections.singletonList("s3"), "s2"),
            "an active structure excludes cells of another membership");
        structures.set(3, new HologramPresets.Structure());
        SimpleAssert.that(
            index.enabled(preset, Collections.singletonList("s3"), "") == null,
            "same-size external list replacement falls back to the authoritative scan");
    }

    @SuppressWarnings("unchecked")
    private static void stagingOwnership(sun.misc.Unsafe unsafe, CountingWorld world) throws Exception {
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.isCreativeMode = true;
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        UUID identity = UUID.randomUUID();
        for (Field field : net.minecraft.entity.Entity.class.getDeclaredFields()) if (field.getType() == UUID.class) {
            field.setAccessible(true);
            field.set(player, identity);
        }
        for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
            field.setAccessible(true);
            field.set(player, new GameProfile(identity, "AreaPreparation"));
        }
        HologramSession previous = new HologramSession(player, 0, 64, 0, false, 2);
        for (int terminal : new int[] { 3, 4, 5 }) {
            previous.virtual = previous.virtualPlacement = previous.virtualLocked = true;
            previous.blueprintSource = "";
            previous.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
            previous.blueprintAwaitingConfirmation = previous.placementReviewIssued = true;
            previous.job = terminal;
            previous.phase = "ACK";
            previous.batchSeq = 9;
            previous.cursor = previous.completed = 17;
            previous.work.add(0);
            HologramAreaWorkflow.retireFinishedPlacement(previous);
            SimpleAssert.that(
                previous.job == 0 && previous.phase.equals("READY")
                    && previous.batchSeq == 10
                    && previous.work.isEmpty()
                    && previous.cursor == 0
                    && previous.completed == 0,
                "a new selection retires the prior terminal job and ACK generation: " + terminal);
            SimpleAssert.that(
                !previous.virtualPlacement && !previous.virtualLocked
                    && !previous.blueprintAwaitingConfirmation
                    && !previous.placementReviewIssued
                    && !HologramAuthoringWorkflow.blueprintHasUnsavedChanges(previous),
                "completed saved placement leaves no old lock, material consent or false unsaved-source prompt");
        }
        previous.uiSequence = 21;
        previous.planRevision = 17;
        previous.blueprintClientTaskToken = 987;
        previous.libraryQuickClientTaskToken = 654;
        previous.main = 3;
        previous.hatchTier = 8;
        previous.materialSettings.me = false;
        previous.retainedNbtKeys = Arrays.asList("recipe");
        previous.channels.setInteger("pipe", 7);
        previous.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        previous.pins.put(0, new ItemStack(Blocks.dirt));
        HologramArea area = new HologramArea();
        area.blueprint = new HologramPresets.Preset();
        area.blueprint.schema = 2;
        area.minY = 64;
        for (int i = 0; i < 4096; i++)
            area.blueprint.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), i % 64, 0, i / 64, -1, false));
        HologramSession staged = HologramAreaWorkflow.importStaging(previous, area);
        SimpleAssert.that(
            staged != previous && staged.id.equals(previous.id)
                && staged.blueprintClientTaskToken == 987
                && staged.libraryQuickClientTaskToken == 654
                && staged.uiSequence == 21
                && staged.planRevision == 17,
            "staging preserves exact request identity and tokens");
        SimpleAssert.that(
            staged.main == 3 && staged.hatchTier == 8
                && !staged.materialSettings.me
                && staged.channels.getInteger("pipe") == 7
                && staged.retainedNbtKeys.equals(previous.retainedNbtKeys),
            "staging preserves user configuration instead of silently loading constructor defaults");
        HologramBlueprintProjection.Population population = new HologramBlueprintProjection.Population(staged);
        int batches = 0;
        long done = 0;
        do {
            population.step(17, Long.MAX_VALUE);
            SimpleAssert.that(
                population.completed() - done <= 17,
                "projection work remains bounded through both stable controller-order passes");
            done = population.completed();
            batches++;
        } while (done < population.total());
        SimpleAssert.eq(4096, staged.capture.cells.size(), "budgeted projection retains every imported cell");
        SimpleAssert.that(
            batches > 400 && previous.capture.cells.isEmpty()
                && previous.pins.get(0)
                    .getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.dirt),
            "partial projection never mutates the original editor or its coordinate choices");
        net.minecraft.network.NetworkManager connection = new net.minecraft.network.NetworkManager(false);
        io.netty.channel.embedded.EmbeddedChannel embedded = new io.netty.channel.embedded.EmbeddedChannel(
            new io.netty.channel.ChannelInboundHandlerAdapter());
        for (Field field : net.minecraft.network.NetworkManager.class.getDeclaredFields())
            if (field.getType() == io.netty.channel.Channel.class) {
                field.setAccessible(true);
                field.set(connection, embedded);
            }
        new net.minecraft.network.NetHandlerPlayServer(null, connection, player);
        HologramSession oldSession = HologramWorkspace.find(identity);
        HologramArea oldArea = HologramWorkspace.AREAS.get(identity);
        try {
            HologramWorkspace.activate(identity, previous);
            HologramWorkspace.AREAS.put(identity, area);
            area.importing = true;
            SimpleAssert
                .that(HologramAreaWorkflow.importOwned(previous, area), "only the live current import may publish");
            HologramWorkspace.activate(identity, staged);
            SimpleAssert.that(
                !HologramAreaWorkflow.importOwned(previous, area),
                "an older session cannot publish a late result");
            HologramWorkspace.activate(identity, previous);
            HologramWorkspace.AREAS.put(identity, new HologramArea());
            SimpleAssert
                .that(!HologramAreaWorkflow.importOwned(previous, area), "a replaced selection rejects a late result");
            HologramWorkspace.AREAS.put(identity, area);
            ItemStack held = player.inventory.mainInventory[0];
            player.inventory.mainInventory[0] = new ItemStack(Blocks.dirt);
            SimpleAssert.that(
                !HologramAreaWorkflow.importOwned(previous, area),
                "changing the physical tool rejects completion");
            player.inventory.mainInventory[0] = held;
            embedded.close();
            SimpleAssert.that(
                !HologramAreaWorkflow.importOwned(previous, area),
                "disconnect cannot activate a completed worker result");
        } finally {
            embedded.close();
            if (oldArea == null) HologramWorkspace.AREAS.remove(identity);
            else HologramWorkspace.AREAS.put(identity, oldArea);
            if (oldSession != null) HologramWorkspace.activate(identity, oldSession);
            else {
                Field sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
                sessions.setAccessible(true);
                ((Map<UUID, HologramSession>) sessions.get(null)).remove(identity);
            }
        }
        area.staging = staged;
        area.cancelImportWork();
        SimpleAssert.that(
            area.staging == null && previous.pins.size() == 1,
            "cancelling the staged tail discards only unfinished work");
    }

    private static final class CountedStructures extends ArrayList<HologramPresets.Structure> {

        private static final long serialVersionUID = 1L;
        int reads;

        public HologramPresets.Structure get(int index) {
            reads++;
            return super.get(index);
        }
    }

    private static final class CountingWorld extends WorldServer {

        int reads;

        private CountingWorld() {
            super(null, null, "area cursor fixture", 0, null, null);
        }

        public boolean blockExists(int x, int y, int z) {
            reads++;
            return x % 5 != 3;
        }

        public Block getBlock(int x, int y, int z) {
            return Blocks.air;
        }

        public int getBlockMetadata(int x, int y, int z) {
            return 0;
        }

        public TileEntity getTileEntity(int x, int y, int z) {
            return null;
        }

        public long getTotalWorldTime() {
            return 0;
        }

        public int getHeight() {
            return 256;
        }

        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("area preparation must never write world blocks");
        }
    }
}
