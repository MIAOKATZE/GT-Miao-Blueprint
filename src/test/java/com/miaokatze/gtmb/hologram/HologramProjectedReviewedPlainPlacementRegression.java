// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.End;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Kind;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Request;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Variant;
import com.miaokatze.gtmb.hologram.HologramProjectedReviewedPlainPlacement.Result;
import com.miaokatze.gtmb.hologram.HologramProjectedVariantPricing.Row;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Bounded provider-neutral refusal tests; installed providers are required for optional per-cell checks. */
public final class HologramProjectedReviewedPlainPlacementRegression {

    private HologramProjectedReviewedPlainPlacementRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramProjectedReviewedPlainPlacementRegression.class, args)) return;
        run();
    }

    public static void run() throws Exception {
        var rows = rows();
        var plain = plain(rows);
        SimpleAssert.eq(29, rows.size(), "original used dictionary");
        SimpleAssert.eq(5, plain.size(), "exact five no-tile rows");
        SimpleAssert.eq(122_115, plain.stream().mapToInt(row -> row.count).sum(), "plain count in original archive");
        SimpleAssert.that(HologramProjectedReviewedPlainPlacement.plainManifest(program(plain).manifest),
            "the five exact rows can reach registered-provider admission without a whole shape");

        try (Fixture fixture = new Fixture()) {
            for (Row row : rows) if (!row.tileNbt.isEmpty() || !row.itemNbt.isEmpty()) {
                var mixed = program(Arrays.asList(plain.get(0), row));
                SimpleAssert.that(!HologramProjectedReviewedPlainPlacement.plainManifest(mixed.manifest),
                    "every one of the 24 real decorated rows is refused even inside a mixed dictionary");
                fixture.refuse(mixed);
            }
            Row base = plain.get(0);
            var decoratedPlain = new Row(base.block, base.item, base.metadata, base.damage, -1, false,
                rows.stream().filter(r -> !r.tileNbt.isEmpty()).findFirst().get().tileNbt, "", 1);
            fixture.refuse(program(Arrays.asList(decoratedPlain)));
            fixture.refuse(program(plain)); // No main items; never admits a credit, even with registered providers.
            SimpleAssert.eq(0, fixture.world.writes().size(), "no world changes on missing or unsupported stock");
        }
    }

    /** Optional: load actual installed Chisel, Ztones and ExtraUtilities first. Never substitute fixture providers. */
    public static void runWithRegisteredProviders() throws Exception {
        var rows = plain(rows());
        try (Fixture fixture = new Fixture()) {
            Program program = program(rows);
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                Item nativeItem = (Item) Item.itemRegistry.getObject(row.item);
                SimpleAssert.that(nativeItem != null && Block.getBlockFromName(row.block) != Blocks.air,
                    "real installed material registry row " + row.item);
                fixture.player.inventory.mainInventory[i + 1] = new ItemStack(nativeItem, 1, row.damage);
            }
            var job = HologramProjectedReviewedPlainPlacement.begin(fixture.endpoint, fixture.lease(program),
                program.manifest);
            fixture.world.arm();
            try {
                SimpleAssert.eq(Offer.CLOSED, job.offer(program.first), "no bytes accepted before whole stock credit");
                SimpleAssert.eq(1, job.credit().credit, "exact main stock admits one credit");
                SimpleAssert.eq(Offer.OUT_OF_ORDER, job.offer(program.last), "tail before first slice does not place");
                SimpleAssert.eq(Offer.ACCEPTED, job.offer(program.first), "the actual first committed slice is admitted");
                SimpleAssert.eq(Result.PLACED, job.step(), "the exact provider cell and debit settle before tail");
                SimpleAssert.eq(1, fixture.world.writes().size(), "one genuine scoped write before final fragment");
                SimpleAssert.that(fixture.player.inventory.mainInventory[1] == null,
                    "settled first cell debits its exact main slot once");
                SimpleAssert.eq(1, job.credit().credit, "a second credit follows actual first settlement");
                SimpleAssert.eq(Offer.REPLAY, job.offer(program.first), "duplicate cannot charge first slice again");
                SimpleAssert.eq(Offer.ACCEPTED, job.offer(program.last), "next exact encoded slice");
                for (int i = 1; i < rows.size(); i++) {
                    SimpleAssert.eq(Result.PLACED, job.step(), "one physical cell per driver step");
                    Row row = rows.get(i);
                    SimpleAssert.that(fixture.world.getBlock(i + 1, 64, 0) == Block.getBlockFromName(row.block)
                        && fixture.world.getBlockMetadata(i + 1, 64, 0) == row.metadata
                        && fixture.player.inventory.mainInventory[i + 1] == null, "actual exact per-row stock and target");
                }
                SimpleAssert.eq(Result.COMPLETE, job.step(), "terminal requires authoritative gate settlement");
                SimpleAssert.eq(rows.size(), job.placed(), "all five physically placed in the accounting fixture");
                SimpleAssert.eq(0, job.alreadySatisfied(), "no placement is relabelled as already matched");
                var options = HologramMaterials.read(fixture.player.getHeldItem());
                SimpleAssert.that(options.main && options.containers && options.me && options.priority == 0,
                    "ordinary configured settings were not changed by the private main-only lane");
            } finally {
                job.cancel();
            }
            Row first = rows.get(0);
            ExtendedBlockStorage raw = loadedShell(fixture.world, first);
            fixture.player.inventory.mainInventory[1] =
                new ItemStack((Item) Item.itemRegistry.getObject(first.item), 1, first.damage);
            Program same = program(Arrays.asList(first));
            var replay = HologramProjectedReviewedPlainPlacement.begin(fixture.endpoint, fixture.lease(same),
                same.manifest);
            try {
                SimpleAssert.eq(1, replay.credit().credit, "a fresh intent still proves whole current stock");
                SimpleAssert.eq(Offer.ACCEPTED, replay.offer(same.first), "same position is independently verified");
                SimpleAssert.eq(Result.SATISFIED, replay.step(), "already-exact provider cell is not a placement");
                SimpleAssert.eq(Result.COMPLETE, replay.step(), "one validated no-op settles the new intent");
                SimpleAssert.eq(rows.size(), fixture.world.writes().size(), "matching cell does not write or debit");
                SimpleAssert.eq(1, fixture.player.inventory.mainInventory[1].stackSize, "original main item kept");
            } finally {
                replay.cancel();
            }

            // A live target getter reenters after an earlier coherent matching observation. Its null return must
            // not splice the old block+metadata together with the newly changed target's no-tile state.
            Program changed = program(Arrays.asList(first));
            var changedJob = HologramProjectedReviewedPlainPlacement.begin(fixture.endpoint, fixture.lease(changed),
                changed.manifest);
            int[] tileProbes = { 0 };
            int different = (first.metadata + 1) & 15;
            fixture.world.occupants = new HashMap<String, TileEntity>() {
                @Override
                public TileEntity get(Object key) {
                    if ("1,64,0".equals(key) && ++tileProbes[0] == 2) {
                        raw.setExtBlockMetadata(1, 0, 0, different);
                        fixture.world.setBlockMetadataWithNotify(1, 64, 0, different, 0);
                    }
                    return null;
                }
            };
            try {
                SimpleAssert.eq(1, changedJob.credit().credit, "known intentional no-op reached actual credit");
                SimpleAssert.eq(Offer.ACCEPTED, changedJob.offer(changed.first), "proof uses exact server stock");
                SimpleAssert.eq(Result.STOPPED, changedJob.step(), "do not count the reentrant target as satisfied");
                SimpleAssert.eq(2, tileProbes[0], "target changed inside its final no-tile observation");
                SimpleAssert.eq(0, changedJob.alreadySatisfied(), "no stale no-op receipt");
                SimpleAssert.eq(1, fixture.player.inventory.mainInventory[1].stackSize, "no-op race never debits");
                SimpleAssert.eq(different, fixture.world.getBlockMetadata(1, 64, 0),
                    "unowned callback change was not overwritten");
                SimpleAssert.eq(rows.size(), fixture.world.writes().size(), "no extra candidate placement");
            } finally {
                fixture.world.occupants = null;
                changedJob.cancel();
            }
        }
        try (Fixture fixture = new Fixture()) {
            Row row = rows.get(0);
            Program one = program(Arrays.asList(row));
            Item item = (Item) Item.itemRegistry.getObject(row.item);
            fixture.player.inventory.mainInventory[1] = new ItemStack(item, 1, row.damage);
            var job = HologramProjectedReviewedPlainPlacement.begin(fixture.endpoint, fixture.lease(one),
                one.manifest);
            fixture.world.arm();
            try {
                SimpleAssert.eq(1, job.credit().credit, "initial stock suffices");
                fixture.player.inventory.mainInventory[1] = null;
                SimpleAssert.eq(Offer.ACCEPTED, job.offer(one.first), "already issued credit is transfer only");
                SimpleAssert.eq(Result.STOPPED, job.step(), "current exact main item missing before debit");
                SimpleAssert.that(fixture.world.writes().isEmpty() && job.placed() == 0,
                    "late stock removal cannot place or use the container/ME fallback");
                SimpleAssert.eq(0, job.credit().credit, "failed placement cannot grant the next credit");
            } finally {
                job.cancel();
            }
        }
        closeAfterLiveProbe(rows.get(0));
    }

    /** Optional native provider accounting only; the underlying fixture is not a native-neighbor proof. */
    private static void closeAfterLiveProbe(Row row) throws Exception {
        try (Fixture fixture = new Fixture()) {
            var block = Block.getBlockFromName(row.block);
            Item item = (Item) Item.itemRegistry.getObject(row.item);
            ItemStack stock = new ItemStack(item, 1, row.damage);
            boolean[] verifyCandidate = { false }, closedInRead = { false };
            var old = fixture.player.inventory;
            var probed = new InventoryPlayer(fixture.player) {
                @Override
                public void markDirty() {
                    // The real scoped final fence invokes markDirty after the candidate exists, only to let all
                    // inventory callbacks finish before its live probe and final forensic target read.
                    if (fixture.world.getBlock(1, 64, 0) == block) verifyCandidate[0] = true;
                    super.markDirty();
                }
            };
            System.arraycopy(old.mainInventory, 0, probed.mainInventory, 0, old.mainInventory.length);
            probed.currentItem = old.currentItem;
            probed.mainInventory[1] = stock;
            fixture.player.inventory = probed;
            var channel = fixture.player.playerNetServerHandler.netManager.channel();
            fixture.world.occupants = new HashMap<String, TileEntity>() {
                @Override
                public TileEntity get(Object key) {
                    if ("1,64,0".equals(key) && verifyCandidate[0] && !closedInRead[0]) {
                        closedInRead[0] = true;
                        channel.close().syncUninterruptibly();
                    }
                    return null;
                }
            };
            Program one = program(Arrays.asList(row));
            var job = HologramProjectedReviewedPlainPlacement.begin(fixture.endpoint, fixture.lease(one),
                one.manifest);
            fixture.world.arm();
            try {
                SimpleAssert.eq(1, job.credit().credit, "physical channel initially live");
                SimpleAssert.eq(Offer.ACCEPTED, job.offer(one.first), "one exact first slice");
                SimpleAssert.eq(Result.STOPPED, job.step(), "last target callback closed the real endpoint");
                SimpleAssert.that(closedInRead[0] && !channel.isOpen(), "close happened only in candidate reread");
                SimpleAssert.that(fixture.player.inventory.mainInventory[1] == stock && stock.stackSize == 1,
                    "closed channel cannot authorize a final debit");
                SimpleAssert.that(!fixture.world.writes().isEmpty()
                    && fixture.world.getBlock(1, 64, 0) == Blocks.air && job.placed() == 0,
                    "staged candidate existed, rolled back against the original world, and never counted");
                SimpleAssert.that(!HologramConstructionJournal.pending(fixture.player),
                    "exact rollback retired only its own transient receipt");
            } finally {
                job.cancel();
            }
        }
    }

    private static ExtendedBlockStorage loadedShell(HologramPlacementFixtureWorld world, Row row) {
        var provider = new ChunkProviderServer(world, null, null);
        world.theChunkProviderServer = provider;
        var chunk = new Chunk(world, 0, 0);
        var section = new ExtendedBlockStorage(64, false);
        section.func_150818_a(1, 0, 0, Block.getBlockFromName(row.block));
        section.setExtBlockMetadata(1, 0, 0, row.metadata);
        chunk.getBlockStorageArray()[64 >> 4] = section;
        chunk.isChunkLoaded = true;
        provider.loadedChunkHashMap.add(ChunkCoordIntPair.chunkXZ2Int(0, 0), chunk);
        return section;
    }

    private static final class Fixture implements AutoCloseable {

        final Path scratch;
        final Path oldRoot;
        final HologramPlacementFixtureWorld world;
        final EntityPlayerMP player;
        final HologramWorkspace.ActionEndpoint endpoint;
        final HologramConstructionTaskGate gate;
        long intent;

        Fixture() throws Exception {
            var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) access.get(null);
            world = HologramPlacementFixtureWorld.create(unsafe);
            player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "ReviewedPlainScope");
            player.posY = 64;
            player.capabilities.allowEdit = true;
            var held = player.getHeldItem();
            var settings = new HologramMaterials.Settings();
            settings.priority = 0;
            settings.main = settings.containers = settings.me = true;
            HologramMaterials.write(held, settings);
            held.getTagCompound().setString(HologramSessionKeys.TOOL_ID, UUID.randomUUID().toString());
            // V1 fixture's default stone is neither proof nor stock for the reviewed modded profile.
            player.inventory.mainInventory[1] = null;
            oldRoot = HologramConstructionJournal.STORAGE_ROOT.get();
            scratch = Files.createTempDirectory("gtmb-reviewed-plain-");
            HologramConstructionJournal.STORAGE_ROOT.set(scratch);
            HologramWorkspace.publishActionEndpoint(player, false);
            endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
            gate = HologramWorkspace.constructionGate(endpoint);
            SimpleAssert.that(gate != null, "real server endpoint/gate");
        }

        HologramConstructionTaskGate.Lease lease(Program program) {
            var request = new Request(program.manifest.header.request, ++intent, Kind.PROJECTED_LAYER);
            var acquired = gate.acquire(endpoint.constructionScope, request, false, false);
            SimpleAssert.that(acquired.lease != null, "exact construction lease");
            return acquired.lease;
        }

        void refuse(Program program) throws Exception {
            var lease = lease(program);
            try {
                var unexpected = HologramProjectedReviewedPlainPlacement.begin(endpoint, lease, program.manifest);
                unexpected.cancel();
                throw new AssertionError("unsupported or unfunded program obtained an admission");
            } catch (IOException expected) {
                SimpleAssert.that(!HologramConstructionJournal.pending(player), "no journal on refused preflight");
            } finally {
                gate.finish(lease, End.FAILED);
            }
        }

        @Override
        public void close() throws IOException {
            HologramWorkspace.revokeActionEndpoint(player);
            player.playerNetServerHandler.netManager.channel().close();
            if (oldRoot == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(oldRoot);
            try (Stream<Path> files = Files.walk(scratch)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(file);
            }
        }
    }

    private static final class Program {

        final Manifest manifest;
        final byte[] first, last;

        Program(Manifest manifest, byte[] first, byte[] last) {
            this.manifest = manifest;
            this.first = first;
            this.last = last;
        }
    }

    private static Program program(List<Row> rows) throws IOException {
        UUID request = UUID.randomUUID();
        List<Variant> palette = new ArrayList<>();
        int[] coordinates = new int[rows.size() * 3];
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            palette.add(Variant.reviewed(new Row(r.block, r.item, r.metadata, r.damage, r.facing, r.controller,
                r.tileNbt, r.itemNbt, 1)));
            coordinates[i * 3] = i + 1;
            coordinates[i * 3 + 2] = i;
        }
        int slices = rows.size() == 1 ? 1 : 2;
        Header h = Header.reviewed(request, 0, 64, 0, 1, 64, 0, rows.size(), 64, 0, rows.size(), slices, palette);
        byte[] tail = slices == 1 ? null : HologramProjectedLayerWire.encodeSlice(
            new Slice(request, 1, 64, Arrays.copyOfRange(coordinates, 3, coordinates.length),
                HologramProjectedLayerWire.end()));
        byte[] first = HologramProjectedLayerWire.encodeSlice(new Slice(request, 0, 64,
            Arrays.copyOf(coordinates, 3), tail == null ? HologramProjectedLayerWire.end()
                : HologramProjectedLayerWire.commitment(h, tail)));
        var manifest = new Manifest(h, HologramProjectedLayerWire.commitment(h, first));
        return new Program(HologramProjectedLayerWire.decodeReviewedManifest(
            HologramProjectedLayerWire.encodeReviewedManifest(manifest)), first, tail);
    }

    private static List<Row> plain(List<Row> rows) {
        List<Row> result = new ArrayList<>();
        for (Row row : rows) if (row.tileNbt.isEmpty() && row.itemNbt.isEmpty()) result.add(row);
        return result;
    }

    private static List<Row> rows() throws IOException {
        var resource = java.nio.file.Files.newInputStream(java.nio.file.Paths.get(
            System.getProperty("gtmb.reviewedMetadata.fixture")));
        if (resource == null) throw new IOException("Original used dictionary fixture missing");
        var rows = new ArrayList<Row>();
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] f = line.split("\t", -1);
                rows.add(new Row(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), -1, false,
                    "-".equals(f[5]) ? "" : f[5], "-".equals(f[6]) ? "" : f[6], Integer.parseInt(f[4])));
            }
        }
        return rows;
    }
}
