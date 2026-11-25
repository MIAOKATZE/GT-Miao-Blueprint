// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import static com.miaokatze.gtmb.hologram.HologramProjectedLayerService.Phase.AWAITING_SLICE;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerService.Phase.COMPLETE;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerService.Phase.PAUSED;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerService.Phase.STOPPED;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerService.StockContract.OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerService.Reason;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerService.Status;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Variant;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Real projected owner/codec/transaction with only world and peer endpoints isolated. */
public final class HologramProjectedLayerServiceRegression {

    private HologramProjectedLayerServiceRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramProjectedLayerServiceRegression.class, args)) return;
        run();
        System.out.println("projected_layer_placement_and_payment: passed");
    }

    public static void run() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        Path previous = HologramConstructionJournal.STORAGE_ROOT.get();
        Path scratch = Files.createTempDirectory("gtmb-projected-layer-");
        HologramConstructionJournal.STORAGE_ROOT.set(scratch);
        try {
            transactionBeforeTail(unsafe);
            noFalseAdmission(unsafe);
            blockedTailPreservesPrefix(unsafe);
            pauseStockStopAndDisconnect(unsafe);
            deadlineBoundedWork(unsafe);
            heightGroupCadence(unsafe);
            activityLease(unsafe);
        } finally {
            if (previous == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(previous);
            try (Stream<Path> paths = Files.walk(scratch)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void transactionBeforeTail(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 3)) {
            fixture.world.seed(1, 64, 0, Blocks.stone, 0);
            StreamPlan plan = new StreamPlan("stone", 3, new int[] { 0, 0, 0, 1, 0, 0 }, new int[] { 0, 0, 0 });
            HologramProjectedLayerService job = fixture.begin(plan);
            require(job.transferAck().credit == 1 && job.status().placed == 0 && fixture.world.writes().isEmpty(),
                "admission grants credit, without placing or charging");
            require(fixture.stock() == 3 && HologramWorkspace.find(fixture.player.getUniqueID()) == null,
                "dry observation and no workspace geometry");
            fixture.world.arm();
            require(job.offer(fixture.player, plan.first) == Offer.ACCEPTED, "first slice accepted");
            require(job.offer(fixture.player, plan.first) == Offer.REPLAY && job.transferAck().nextSlice == 0,
                "replay before settlement never advances the cumulative frontier");
            Status first = job.step(Long.MAX_VALUE, 0);
            require(first.phase == AWAITING_SLICE && first.acceptedSlices == 1 && first.settledSlices == 1
                && first.placed == 1 && first.alreadySatisfied == 1 && fixture.stock() == 2
                && fixture.world.getBlock(0, 64, 0) == Blocks.stone && fixture.world.getBlock(0, 65, 0) == Blocks.air
                && fixture.world.writes().size() == 1,
                "actual first world transaction and exact debit precede any final-slice arrival");
            require(job.offer(fixture.player, plan.first) == Offer.REPLAY, "settled replay leaves the world untouched");
            require(!HologramConstructionJournal.pending(fixture.player), "existing journal settled first payment");
            require(job.offer(fixture.player, plan.tail) == Offer.ACCEPTED, "tail credit");
            Status done = job.step(Long.MAX_VALUE, 0);
            require(done.phase == COMPLETE && done.placed == 2 && done.alreadySatisfied == 1 && fixture.stock() == 1
                && done.sequence > first.sequence && job.transferAck().complete
                && fixture.world.writes().size() == 2 && !HologramProjectedLayerService.active(fixture.player),
                "terminal distinguishes placed cells, existing matches, and effective debit");
            invalid(() -> HologramProjectedLayerService.begin(fixture.player, plan.manifest,
                OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL));
        }
    }

    private static void noFalseAdmission(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 1)) {
            StreamPlan two = new StreamPlan("stone", 2, new int[] { 0, 0, 0 }, new int[] { 0, 0, 0 });
            invalid(() -> fixture.begin(two));
            fixture.player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 2);
            fixture.player.inventory.mainInventory[1].setTagCompound(new NBTTagCompound());
            fixture.player.inventory.mainInventory[1].getTagCompound().setString("variant", "not the exact bill");
            invalid(() -> fixture.begin(two));
            invalid(() -> HologramProjectedLayerService.begin(fixture.player, two.manifest, null));
            StreamPlan sand = new StreamPlan("sand", 1, new int[] { 0, 0, 0 }, null);
            invalid(() -> fixture.begin(sand));
            require(!HologramProjectedLayerService.active(fixture.player) && fixture.world.writes().isEmpty()
                && fixture.stock() == 2, "missing exact stock, unsupported block and absent stock contract fail closed");
        }
    }

    private static void blockedTailPreservesPrefix(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 3)) {
            fixture.world.seed(1, 65, 0, Blocks.dirt, 0);
            StreamPlan plan = new StreamPlan("stone", 3, new int[] { 0, 0, 0 }, new int[] { 0, 0, 0, 1, 0, 0 });
            HologramProjectedLayerService job = fixture.begin(plan);
            fixture.world.arm();
            require(job.offer(fixture.player, plan.first) == Offer.ACCEPTED && job.step(Long.MAX_VALUE, 0).placed == 1,
                "first safe layer commits");
            require(job.offer(fixture.player, plan.tail) == Offer.ACCEPTED, "later valid frame is not admission to write");
            Status stopped = job.step(Long.MAX_VALUE, 0);
            require(stopped.phase == STOPPED && stopped.reason == Reason.BLOCKED && stopped.placed == 1
                && stopped.acceptedSlices == 2 && stopped.settledSlices == 1 && fixture.stock() == 2
                && fixture.world.getBlock(0, 65, 0) == Blocks.air
                && fixture.world.getBlock(1, 65, 0) == Blocks.dirt
                && job.transferAck().aborted && fixture.world.writes().size() == 1,
                "blocked slice never mutates its own prefix and prior settled world/debit remain");
        }
    }

    private static void pauseStockStopAndDisconnect(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 2)) {
            StreamPlan plan = new StreamPlan("stone", 2, new int[] { 0, 0, 0 }, new int[] { 0, 0, 0 });
            HologramProjectedLayerService job = fixture.begin(plan);
            fixture.world.arm();
            require(job.transferAck().credit == 1 && job.pause(fixture.player) && job.transferAck().credit == 0,
                "pause withdraws future credit");
            require(job.offer(fixture.player, plan.first) == Offer.ACCEPTED && job.step(Long.MAX_VALUE, 0).phase == PAUSED
                && job.status().placed == 0 && fixture.stock() == 2, "one prior in-flight slice can stage, not write");
            require(job.resume(fixture.player) && job.step(Long.MAX_VALUE, 0).placed == 1, "owned resume");
            fixture.player.inventory.mainInventory[1] = null;
            require(job.transferAck().aborted && job.status().reason == Reason.MISSING_STOCK && job.status().placed == 1
                && job.offer(fixture.player, plan.tail) == Offer.CLOSED && fixture.world.writes().size() == 1,
                "dry observation did not lock stock; new shortage stops before granting next credit");
        }
        try (Fixture fixture = new Fixture(unsafe, 2)) {
            StreamPlan plan = new StreamPlan("stone", 2, new int[] { 0, 0, 0 }, new int[] { 0, 0, 0 });
            HologramProjectedLayerService job = fixture.begin(plan);
            fixture.world.arm();
            require(job.offer(fixture.player, plan.first) == Offer.ACCEPTED, "staged before lost owner");
            fixture.player.playerNetServerHandler.netManager.channel().close().awaitUninterruptibly();
            Status stopped = job.step(Long.MAX_VALUE, 0);
            require(stopped.phase == STOPPED && stopped.reason == Reason.OWNER_CHANGED && fixture.stock() == 2
                && fixture.world.writes().isEmpty(), "connection loss cannot write or debit staged cells");
        }
    }

    private static void deadlineBoundedWork(sun.misc.Unsafe unsafe) throws Exception {
        // More than both former quotas (32 inspections and 4 writes), still one legitimate stack and wire frame.
        int count = 40;
        try (Fixture fixture = new Fixture(unsafe, count)) {
            UUID id = UUID.randomUUID();
            String stone = "minecraft:stone";
            Header header = new Header(id, 0, 64, 0, 0, 64, 0, count - 1, 64, 0, count, 1,
                Collections.singletonList(new Variant(stone, 0, stone, 0, -1, false, "", "", count)));
            int[] ops = new int[count * 3];
            for (int i = 0; i < count; i++) ops[i * 3] = i;
            byte[] slice = HologramProjectedLayerWire.encodeSlice(
                new Slice(id, 0, 64, ops));
            Manifest manifest = new Manifest(header);
            HologramProjectedLayerService job = fixture.job = HologramProjectedLayerService.begin(
                fixture.player, manifest, OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL);
            fixture.world.arm();
            require(job.offer(fixture.player, slice) == Offer.ACCEPTED, "one bounded wire slab");
            require(job.step(System.nanoTime() - 1, 0).placed == 0, "expired shared deadline never starts a transaction");
            Status done = job.step(Long.MAX_VALUE, 0);
            require(done.phase == COMPLETE && done.placed == count && job.transferAck().complete
                && fixture.stock() == 0 && fixture.world.writes().size() == count,
                "one service invocation drains received work without a fixed cells-per-tick quota");
            require(!HologramConstructionJournal.pending(fixture.player), "all actual world/payment transactions settled");
        }
    }

    private static void heightGroupCadence(sun.misc.Unsafe unsafe) throws Exception {
        try (Fixture fixture = new Fixture(unsafe, 7)) {
            UUID id = UUID.randomUUID();
            String stone = "minecraft:stone";
            Header header = new Header(id, 0, 64, 0, 0, 64, 0, 0, 70, 0, 7, 7,
                Collections.singletonList(new Variant(stone, 0, stone, 0, -1, false, "", "", 7)));
            byte[][] frames = new byte[7][];
            for (int i = 6; i >= 0; i--) {
                frames[i] = HologramProjectedLayerWire.encodeSlice(
                    new Slice(id, i, 64 + i, new int[] { 0, 0, 0 }));
            }
            fixture.job = HologramProjectedLayerService.begin(fixture.player,
                new Manifest(header), OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL);
            var job = fixture.job;
            fixture.world.arm();
            for (int i = 0; i < 5; i++)
                require(job.offer(fixture.player, frames[i]) == Offer.ACCEPTED, "first five layers staged");
            require(job.step(Long.MAX_VALUE, 100).placed == 5 && fixture.stock() == 2,
                "first part of the same height group writes and debits");
            for (int i = 5; i < frames.length; i++)
                require(job.offer(fixture.player, frames[i]) == Offer.ACCEPTED, "sixth and seventh layers staged");
            require(job.step(Long.MAX_VALUE, 112).placed == 6 && fixture.stock() == 1
                && fixture.world.writes().size() == 6, "the sixth settles, but the seventh cannot write or debit");
            require(job.step(Long.MAX_VALUE, 121).placed == 6 && fixture.stock() == 1,
                "the seventh waits at completion plus nine ticks");
            require(job.step(Long.MAX_VALUE, 122).phase == COMPLETE && fixture.stock() == 0
                && fixture.world.getBlock(0, 70, 0) == Blocks.stone && fixture.world.writes().size() == 7,
                "the seventh writes and debits exactly ten ticks after the first group finishes");
        }
    }

    private static void activityLease(sun.misc.Unsafe unsafe) throws Exception {
        final long second = 1_000_000_000L;
        AtomicLong time = new AtomicLong();
        int slices = 24;
        try (Fixture fixture = new Fixture(unsafe, slices)) {
            UUID id = UUID.randomUUID();
            String stone = "minecraft:stone";
            Header header = new Header(id, 0, 64, 0, 0, 64, 0, slices - 1, 64, 0, slices, slices,
                Collections.singletonList(new Variant(stone, 0, stone, 0, -1, false, "", "", slices)));
            byte[][] pieces = new byte[slices][];
            for (int i = slices - 1; i >= 0; i--) {
                fixture.world.seed(i, 64, 0, Blocks.stone, 0);
                pieces[i] = HologramProjectedLayerWire.encodeSlice(new Slice(id, i, 64, new int[] { i, 0, 0 }));
            }
            HologramProjectedLayerService job = fixture.job = HologramProjectedLayerService
                .begin(fixture.player, new Manifest(header), OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL, time::get);
            for (byte[] piece : pieces) {
                time.addAndGet(29 * second);
                require(job.offer(fixture.player, piece) == Offer.ACCEPTED, "genuine continued activity");
                job.step(Long.MAX_VALUE, 0);
            }
            require(time.get() > 600 * second && job.status().phase == COMPLETE
                && job.status().alreadySatisfied == slices && job.status().placed == 0 && fixture.stock() == slices,
                "legitimate work continues past stale fixed lifetime; already present cells never debit");
        }
        time.set(0);
        try (Fixture fixture = new Fixture(unsafe, 2)) {
            StreamPlan plan = new StreamPlan("stone", 2, new int[] { 0, 0, 0 }, new int[] { 0, 0, 0 });
            HologramProjectedLayerService job = fixture.job = HologramProjectedLayerService
                .begin(fixture.player, plan.manifest, OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL, time::get);
            require(job.offer(fixture.player, plan.first) == Offer.ACCEPTED, "owned first pending slice");
            time.set(29 * second);
            require(job.offer(fixture.player, plan.first) == Offer.REPLAY && !job.transferAck().aborted,
                "a duplicate can be observed without advancing progress");
            time.set(30 * second);
            require(job.transferAck().aborted && job.status().reason == Reason.TIMEOUT && fixture.stock() == 2,
                "an idle lease cannot be renewed by replay or ACK");
        }
        time.set(0);
        try (Fixture fixture = new Fixture(unsafe, 2)) {
            StreamPlan plan = new StreamPlan("stone", 2, new int[] { 0, 0, 0 }, new int[] { 0, 0, 0 });
            HologramProjectedLayerService job = fixture.job = HologramProjectedLayerService
                .begin(fixture.player, plan.manifest, OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL, time::get);
            require(job.pause(fixture.player), "initial pause");
            time.set(240 * second);
            require(job.resume(fixture.player) && job.pause(fixture.player), "pause can resume within total budget");
            time.set(300 * second);
            require(!job.resume(fixture.player) && job.status().reason == Reason.TIMEOUT,
                "repeated pause/resume cannot create an immortal job");
        }
    }

    private static final class StreamPlan {

        final Manifest manifest;
        final byte[] first, tail;

        StreamPlan(String block, int count, int[] firstOps, int[] tailOps) throws IOException {
            UUID id = UUID.randomUUID();
            String name = "minecraft:" + block;
            Header header = new Header(id, 0, 64, 0, 0, 64, 0, 4, 65, 0, count, tailOps == null ? 1 : 2,
                Collections.singletonList(new Variant(name, 0, name, 0, -1, false, "", "", count)));
            tail = tailOps == null ? null : HologramProjectedLayerWire.encodeSlice(new Slice(id, 1, 65, tailOps));
            first = HologramProjectedLayerWire.encodeSlice(
                new Slice(id, 0, 64, firstOps));
            manifest = HologramProjectedLayerWire.decodeManifest(HologramProjectedLayerWire
                .encodeManifest(new Manifest(header)));
        }
    }

    private static final class Fixture implements AutoCloseable {

        final HologramPlacementFixtureWorld world;
        final EntityPlayerMP player;
        HologramProjectedLayerService job;

        Fixture(sun.misc.Unsafe unsafe, int stock) throws ReflectiveOperationException {
            world = HologramPlacementFixtureWorld.create(unsafe);
            player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "projected-fix");
            player.posY = 64;
            player.capabilities.allowEdit = true;
            player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, stock);
            HologramMaterials.Settings settings = new HologramMaterials.Settings();
            settings.containers = settings.me = false;
            HologramMaterials.write(player.getHeldItem(), settings);
        }

        HologramProjectedLayerService begin(StreamPlan plan) throws IOException {
            job = HologramProjectedLayerService.begin(player, plan.manifest, OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL);
            return job;
        }

        int stock() {
            ItemStack item = player.inventory.mainInventory[1];
            return item == null ? 0 : item.stackSize;
        }

        @Override
        public void close() {
            if (job != null) job.cancel(player);
            player.playerNetServerHandler.netManager.channel().close();
        }
    }

    private interface Checked {

        void run() throws IOException;
    }

    private static void invalid(Checked body) throws IOException {
        try {
            body.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Unsupported manifest must not receive credit");
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
