package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual retained-tree counts and decode counters, rather than estimated Java heap sizes. */
public final class HologramSnapshotDataContextRegression {

    private HologramSnapshotDataContextRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramSnapshotDataContextRegression.class, args)) return;
        run();
        System.out.println("Snapshot data context regressions passed");
    }

    static void run() throws Exception {
        boundedWindowAndSummaries();
        summaryErrorsAndReceipts();
        frozenRegistryAndUnknownFields();
        nearExpandedLimit();
        interruptedWorkerReleasesWindow();
    }

    private static void boundedWindowAndSummaries() throws Exception {
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        List<HologramPresets.Cell> cells = new ArrayList<>();
        int peak = 0;
        for (int i = 0; i < 9; i++) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("ordinal", i);
            tag.setString("unknown-key-" + i, "kept");
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.block = "minecraft:stone";
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(tag);
            cell.itemNbt = cell.tileNbt;
            context.freezeBlock(cell);
            cells.add(cell);
            peak = Math.max(peak, decoded(context).size());
            SimpleAssert.that(decoded(context).size() <= 2, "owner retains at most two expanded trees");
        }
        SimpleAssert.eq(2, peak, "unique payloads actually fill the two-tree window");
        SimpleAssert.that(!decoded(context).containsKey(cells.get(0).tileNbt), "oldest unique owner tree was evicted");
        long sourceDecodes = counter(context, "ownerDecodes");
        SimpleAssert.eq(9L, sourceDecodes, "one owner decode admits each unique tile/item reference");
        Set<String> keys = new HashSet<>();
        for (HologramPresets.Cell cell : cells) {
            context.rootKeys(cell.tileNbt, cell.itemNbt, keys);
            SimpleAssert.that(!context.hasNetwork(cell.tileNbt), "ordinary payload keeps its absent-group summary");
            context.freezeBlock(cell);
        }
        SimpleAssert.eq(10, keys.size(), "source-to-capture summaries retain all original root keys");
        SimpleAssert.eq(
            sourceDecodes,
            counter(context, "ownerDecodes"),
            "root keys and reused classification never re-decode the owner source");
        FutureTask<Void> task = new FutureTask<>(() -> {
            HologramSnapshotMetadataSource.enterDetached(context);
            try {
                try {
                    context.freeze(cells.get(0).tileNbt);
                    throw new AssertionError("worker froze an owner identity");
                } catch (IllegalStateException expected) {
                    SimpleAssert.that(true, "owner-thread admission remains enforced");
                }
                for (int pass = 0; pass < 2; pass++) for (int i = 0; i < cells.size(); i++) {
                    NBTTagCompound tag = HologramBlueprintNbt.decode(cells.get(i).tileNbt);
                    SimpleAssert.eq(i, tag.getInteger("ordinal"), "evicted values restore complete independent data");
                    tag.setInteger("ordinal", -1);
                    SimpleAssert.eq(
                        i,
                        HologramBlueprintNbt.decode(cells.get(i).tileNbt)
                            .getInteger("ordinal"),
                        "a caller cannot modify the retained window");
                    SimpleAssert.that(decoded(context).size() <= 2, "worker retains at most two expanded trees");
                }
                try {
                    HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeData(new NBTTagCompound()));
                    throw new AssertionError("worker admitted an unknown compressed reference");
                } catch (IOException expected) {
                    SimpleAssert.that(
                        expected.getMessage()
                            .contains("not frozen"),
                        "unfrozen reference fails before identity resolution");
                }
            } finally {
                HologramSnapshotMetadataSource.exitDetached();
            }
            return null;
        });
        runWorker(task);
        SimpleAssert.eq(
            18L,
            counter(context, "detachedDecodes"),
            "two unique-value scans re-decode evicted entries but immediate rereads reuse the window");
        SimpleAssert.eq(0, decoded(context).size(), "completed worker releases all retained expanded trees");
        System.out.println(
            "Snapshot window unique=9 retainedPeak=" + peak
                + " ownerDecodes="
                + sourceDecodes
                + " workerDecodes="
                + counter(context, "detachedDecodes"));
    }

    private static void frozenRegistryAndUnknownFields() throws Exception {
        String lateName = "gtmb:snapshot_late_" + Long.toHexString(System.nanoTime());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("unknown", "猫猫" + (char) 0);
        tag.setLong("unknown-long", Long.MIN_VALUE);
        tag.setTag("known-item", new ItemStack(Blocks.stone, 7, 3).writeToNBT(new NBTTagCompound()));
        NBTTagCompound missing = new NBTTagCompound();
        missing.setShort("id", (short) 123);
        missing.setByte("Count", (byte) 9);
        missing.setShort("Damage", (short) 5);
        missing.setString(HologramBlueprintNbt.ITEM_REGISTRY, lateName);
        tag.setTag("late-item", missing);
        NBTTagList covers = new NBTTagList();
        NBTTagCompound cover = new NBTTagCompound();
        cover.setInteger("id", 123);
        cover.setString(HologramBlueprintNbt.COVER_REGISTRY, lateName);
        cover.setInteger(HologramBlueprintNbt.COVER_DAMAGE, 12);
        cover.setString("unknown-cover", "exact");
        covers.appendTag(cover);
        NBTTagCompound knownCover = new NBTTagCompound();
        knownCover.setInteger("id", 123);
        knownCover.setString(HologramBlueprintNbt.COVER_REGISTRY, "minecraft:stone");
        knownCover.setInteger(HologramBlueprintNbt.COVER_DAMAGE, 4);
        covers.appendTag(knownCover);
        tag.setTag("gt.covers", covers);
        String encoded = HologramBlueprintNbt.encodeCaptured(tag);
        NBTTagCompound expected = HologramBlueprintNbt.decode(encoded);
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        NBTTagCompound ownerView = context.freeze(encoded);
        SimpleAssert.eq(expected, ownerView, "first admission keeps the original decode semantics");
        for (int i = 0; i < 3; i++) {
            NBTTagCompound other = new NBTTagCompound();
            other.setInteger("other", i);
            context.freeze(HologramBlueprintNbt.encodeData(other));
        }
        int free = 31000;
        while (free > 0 && Item.getItemById(free) != null) free--;
        if (free == 0) throw new AssertionError("No free isolated item registry slot");
        Item.itemRegistry.addObject(free, lateName, new Item());
        SimpleAssert.that(
            !expected.equals(HologramBlueprintNbt.decode(encoded)),
            "fixture registry really changed after freezing");
        FutureTask<Void> worker = new FutureTask<>(() -> {
            HologramSnapshotMetadataSource.enterDetached(context);
            try {
                NBTTagCompound actual = HologramBlueprintNbt.decode(encoded);
                SimpleAssert.eq(
                    expected,
                    actual,
                    "evicted payload restores owner-frozen item/cover IDs and every unknown field");
                SimpleAssert.that(
                    actual.getCompoundTag("late-item")
                        .getBoolean(HologramBlueprintNbt.MISSING_ITEM),
                    "missing item remains missing after live registration");
                SimpleAssert.that(
                    actual.getTagList("gt.covers", 10)
                        .getCompoundTagAt(0)
                        .getBoolean(HologramBlueprintNbt.MISSING_COVER),
                    "missing cover remains missing after live registration");
            } finally {
                HologramSnapshotMetadataSource.exitDetached();
            }
            return null;
        });
        runWorker(worker);
        SimpleAssert.eq(
            expected,
            context.freeze(encoded),
            "owner reread of an admitted reference also preserves its frozen identities");
    }

    private static void summaryErrorsAndReceipts() throws Exception {
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        NBTTagCompound ordinary = new NBTTagCompound();
        ordinary.setString("", "empty key");
        ordinary.setString("OwnerUUID", "unknown identity");
        ordinary.setTag(HologramNetworkBindings.ORIGINAL, new NBTTagCompound());
        String encoded = HologramBlueprintNbt.encodeData(ordinary);
        context.freeze(encoded);
        Set<String> expectedKeys = new HashSet<>();
        for (Object key : HologramBlueprintNbt.decode(encoded)
            .func_150296_c()) expectedKeys.add((String) key);
        Set<String> keys = new HashSet<>();
        context.rootKeys(encoded, null, keys);
        SimpleAssert.eq(expectedKeys, keys, "root summaries use the old unfiltered complete key set");
        SimpleAssert.eq(
            networkSnapshot(encoded, null),
            networkSnapshot(encoded, context),
            "absent GROUP still ignores even an unknown ORIGINAL descriptor");
        keys.clear();
        try {
            context.rootKeys(encoded, "%", keys);
            throw new AssertionError("invalid item payload was accepted while collecting root keys");
        } catch (IOException expected) {
            SimpleAssert.that(keys.isEmpty(), "a failed item read does not partially publish valid tile keys");
        }
        for (int type = 0; type < 3; type++) {
            NBTTagCompound network = new NBTTagCompound();
            if (type == 0) network.setInteger(HologramNetworkBindings.GROUP, 42);
            else {
                network.setString(HologramNetworkBindings.GROUP, "q1");
                network.setString(HologramNetworkBindings.ROLE, "join");
                if (type == 1) network.setInteger(HologramNetworkBindings.ORIGINAL, 42);
                else {
                    NBTTagCompound unknownOriginal = new NBTTagCompound();
                    unknownOriginal.setString("unknown-original", "must remain visible to validation");
                    network.setTag(HologramNetworkBindings.ORIGINAL, unknownOriginal);
                }
            }
            String malformed = HologramBlueprintNbt.encodeData(network);
            context.freeze(malformed);
            SimpleAssert.that(context.hasNetwork(malformed), "every GROUP type reaches the original network checks");
            if (type == 0) {
                String originalError;
                try {
                    networkSnapshot(malformed, null);
                    throw new AssertionError("wrong GROUP type was accepted by the original path");
                } catch (IOException expected) {
                    originalError = expected.getMessage();
                }
                try {
                    networkSnapshot(malformed, context);
                    throw new AssertionError("wrong GROUP type was hidden by the summary");
                } catch (IOException expected) {
                    SimpleAssert.eq(originalError, expected.getMessage(), "GROUP type error is unchanged");
                }
            } else SimpleAssert.eq(
                networkSnapshot(malformed, null),
                networkSnapshot(malformed, context),
                "noncompound and unknown ORIGINAL retain their original error rows");
        }
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("complete", true);
        receipt.setTag(
            "policy",
            HologramBlueprintProcessing.defaults("self", 0)
                .write());
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.processingNbt = HologramBlueprintNbt.encodeData(receipt);
        NBTTagCompound policy = HologramBlueprintProcessing.policy(preset)
            .write();
        boolean complete = HologramBlueprintProcessing.complete(preset);
        context.freeze(preset.processingNbt);
        runWorker(new FutureTask<>(() -> {
            HologramSnapshotMetadataSource.enterDetached(context);
            try {
                SimpleAssert.eq(
                    policy,
                    HologramBlueprintProcessing.policy(preset)
                        .write(),
                    "processing receipt policy remains available to detached reports");
                SimpleAssert.eq(
                    complete,
                    HologramBlueprintProcessing.complete(preset),
                    "processing receipt completion is unchanged");
            } finally {
                HologramSnapshotMetadataSource.exitDetached();
            }
            return null;
        }));
    }

    private static NBTTagCompound networkSnapshot(String encoded, HologramSnapshotDataContext context)
        throws IOException {
        HologramNetworkBindings.SnapshotTask task = new HologramNetworkBindings.SnapshotTask(
            null,
            new HologramNetworkBindings.State());
        if (context == null) task.add(HologramBlueprintNbt.decode(encoded));
        else if (context.hasNetwork(encoded)) task.add(context.freeze(encoded));
        while (!task.step(1, Long.MAX_VALUE)) {}
        return task.result;
    }

    private static void nearExpandedLimit() throws Exception {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setShort("id", (short) 1);
        tag.setByte("Count", (byte) 1);
        tag.setShort("Damage", (short) 2);
        tag.setString(HologramBlueprintNbt.ITEM_REGISTRY, "missing:snapshot_boundary");
        tag.setByteArray("a", new byte[0]);
        int overhead = HologramBlueprintNbt.expandedBytes(HologramBlueprintNbt.encodeData(tag));
        int length = HologramBlueprintNbt.MAX_EXPANDED - overhead;
        tag.setByteArray("a", new byte[length]);
        String encoded = HologramBlueprintNbt.encodeData(tag);
        SimpleAssert.eq(
            HologramBlueprintNbt.MAX_EXPANDED,
            HologramBlueprintNbt.expandedBytes(encoded),
            "raw fixture reaches the existing exact wire-byte limit");
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        context.freeze(encoded);
        for (int i = 0; i < 2; i++) {
            NBTTagCompound other = new NBTTagCompound();
            other.setInteger("other", i);
            context.freeze(HologramBlueprintNbt.encodeData(other));
        }
        SimpleAssert.that(
            !decoded(context).containsKey(encoded),
            "near-limit worker must restore an evicted original reference");
        FutureTask<Void> worker = new FutureTask<>(() -> {
            HologramSnapshotMetadataSource.enterDetached(context);
            try {
                NBTTagCompound actual = HologramBlueprintNbt.decode(encoded);
                SimpleAssert.eq(
                    length,
                    actual.getByteArray("a").length,
                    "near-limit unknown byte array is retained completely");
                SimpleAssert.that(
                    actual.getBoolean(HologramBlueprintNbt.MISSING_ITEM),
                    "normalization may add a missing marker without re-encoding or imposing a new wire limit");
            } finally {
                HologramSnapshotMetadataSource.exitDetached();
            }
            return null;
        });
        runWorker(worker);
    }

    private static void interruptedWorkerReleasesWindow() throws Exception {
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("kept", 73);
        String encoded = HologramBlueprintNbt.encodeData(tag);
        context.freeze(encoded);
        CountDownLatch reading = new CountDownLatch(1), exited = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            HologramSnapshotMetadataSource.enterDetached(context);
            try {
                NBTTagCompound own = HologramBlueprintNbt.decode(encoded);
                own.setInteger("kept", 0);
                reading.countDown();
                new CountDownLatch(1).await();
            } finally {
                HologramSnapshotMetadataSource.exitDetached();
                exited.countDown();
            }
            return null;
        });
        Thread worker = new Thread(task, "snapshot-window-cancel-regression");
        worker.start();
        SimpleAssert.that(reading.await(5, TimeUnit.SECONDS), "cancel fixture began on its worker");
        task.cancel(true);
        SimpleAssert.that(exited.await(5, TimeUnit.SECONDS), "cancelled worker actually left its detached scope");
        worker.join(5000);
        SimpleAssert.eq(0, decoded(context).size(), "interruption releases the worker window after actual exit");
        SimpleAssert.eq(
            73,
            context.freeze(encoded)
                .getInteger("kept"),
            "late/cancelled reader never changed the owner's compressed source");
    }

    private static void runWorker(FutureTask<Void> task) throws Exception {
        Thread worker = new Thread(task, "snapshot-window-regression");
        worker.start();
        task.get(10, TimeUnit.SECONDS);
        worker.join(5000);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, NBTTagCompound> decoded(HologramSnapshotDataContext context) throws Exception {
        Field field = HologramSnapshotDataContext.class.getDeclaredField("decoded");
        field.setAccessible(true);
        return (Map<String, NBTTagCompound>) field.get(context);
    }

    static long counter(HologramSnapshotDataContext context, String name) throws Exception {
        Field field = HologramSnapshotDataContext.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(context);
    }
}
