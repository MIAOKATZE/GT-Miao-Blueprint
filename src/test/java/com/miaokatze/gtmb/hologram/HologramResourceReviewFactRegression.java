package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Bounded native-NBT fixtures and real leases, not heap/throughput or in-game acceptance. */
public final class HologramResourceReviewFactRegression {

    private HologramResourceReviewFactRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramResourceReviewFactRegression.class, args)) return;
        run();
        System.out.println("Resource review fact regression passed");
    }

    public static void run() throws Exception {
        exactEmptySchemaRoundTrips();
        fallbackPreservesCompleteNbt();
        boundedCertificatePreservesExtensions();
        realContextReuseAndLeaseLifetime();
    }

    private static NBTTagCompound summary(long total, long blocking, boolean complete) {
        NBTTagCompound value = new NBTTagCompound();
        value.setTag("rows", new NBTTagList());
        value.setLong("total", total);
        value.setLong("blocking", blocking);
        value.setBoolean("nativeComplete", complete);
        return value;
    }

    private static void exactEmptySchemaRoundTrips() {
        for (long count : new long[] { 0, 1, Long.MAX_VALUE, Long.MIN_VALUE }) {
            for (boolean complete : new boolean[] { false, true }) {
                NBTTagCompound source = summary(count, count / 2, complete);
                NBTTagCompound expected = (NBTTagCompound) source.copy();
                HologramResourceReviewFact fact = HologramResourceReviewFact.freeze(source);
                SimpleAssert.that(fact.compact(), "exact empty schema uses no retained NBT graph");
                SimpleAssert.eq(1088L, fact.retainedBytes(), "entry allowance plus actual typed fact allowance");
                SimpleAssert.that(
                    fact.retainedBytes() < 1024 + HologramSnapshotNbt.estimate(source),
                    "smaller charge follows a different retained representation");
                SimpleAssert.eq(0, fact.rowCount(), "zero displayed rows does not erase aggregate counts");
                source.setLong("total", 42);
                NBTTagCompound returned = fact.copy();
                SimpleAssert.that(HologramSnapshotNbt.same(expected, returned), "all scalar values round trip exactly");
                returned.setLong("blocking", 42);
                returned.getTagList("rows", 10)
                    .appendTag(new NBTTagCompound());
                SimpleAssert
                    .that(HologramSnapshotNbt.same(expected, fact.copy()), "returned certificates are independent");
            }
        }
    }

    private static void fallbackPreservesCompleteNbt() {
        NBTTagCompound unknown = summary(3, 2, false);
        NBTTagCompound extension = new NBTTagCompound();
        extension.setByteArray("opaque", new byte[] { 1, 2, 3 });
        unknown.setTag("future", extension);
        fallback(unknown, "unknown compound field");
        NBTTagCompound wrongCount = summary(3, 2, true);
        wrongCount.setInteger("total", 3);
        fallback(wrongCount, "count has a different numeric type");
        NBTTagCompound wrongFlag = summary(3, 2, true);
        wrongFlag.setByte("nativeComplete", (byte) 2);
        fallback(wrongFlag, "noncanonical byte is not collapsed to true");
        NBTTagCompound missing = summary(3, 2, true);
        missing.removeTag("blocking");
        fallback(missing, "missing schema member");
        NBTTagCompound wrongRows = summary(3, 2, true);
        wrongRows.setString("rows", "future schema");
        fallback(wrongRows, "rows is not a list");
        NBTTagCompound nonempty = summary(3, 2, true);
        NBTTagCompound row = new NBTTagCompound();
        row.setString("path", "/tile/extension");
        nonempty.getTagList("rows", 10)
            .appendTag(row);
        fallback(nonempty, "nonempty rows");
        NBTTagCompound typedEmpty = (NBTTagCompound) nonempty.copy();
        typedEmpty.getTagList("rows", 10)
            .removeTag(0);
        SimpleAssert.eq(10, ((NBTTagList) typedEmpty.getTag("rows")).func_150303_d(), "fixture has typed empty list");
        fallback(typedEmpty, "typed empty list is not silently canonicalized");
    }

    private static void fallback(NBTTagCompound source, String label) {
        NBTTagCompound expected = (NBTTagCompound) source.copy();
        HologramResourceReviewFact fact = HologramResourceReviewFact.freeze(source);
        SimpleAssert.that(!fact.compact(), label + " retains the complete schema");
        SimpleAssert.eq(
            1088 + HologramSnapshotNbt.estimate(source),
            fact.retainedBytes(),
            label + " charges NBT plus its wrapper and entry");
        source.setString("mutated", "source");
        NBTTagCompound returned = fact.copy();
        SimpleAssert.that(HologramSnapshotNbt.same(expected, returned), label + " is an exact native-NBT copy");
        returned.setString("mutated", "reader");
        if (returned.hasKey("future", 10)) returned.getCompoundTag("future")
            .setByteArray("opaque", new byte[] { 9 });
        SimpleAssert.that(HologramSnapshotNbt.same(expected, fact.copy()), label + " has deep mutation isolation");
    }

    private static void boundedCertificatePreservesExtensions() throws Exception {
        Method bound = HologramSnapshotDataContext.class
            .getDeclaredMethod("boundedResourceReview", NBTTagCompound.class, int.class, long.class);
        bound.setAccessible(true);
        NBTTagCompound source = summary(9, 7, false);
        source.setString("future", "preserve exactly");
        source.setInteger("blocking", 7);
        NBTTagList rows = source.getTagList("rows", 10);
        rows.appendTag(new NBTTagCompound());
        rows.appendTag(new NBTTagCompound());
        NBTTagCompound bounded = (NBTTagCompound) bound.invoke(null, source, 1, Long.MAX_VALUE);
        SimpleAssert.eq(
            1,
            bounded.getTagList("rows", 10)
                .tagCount(),
            "only the presentation rows are bounded");
        SimpleAssert.eq("preserve exactly", bounded.getString("future"), "unknown fields survive certificate bounding");
        SimpleAssert.that(bounded.hasKey("blocking", 3), "bounding preserves original scalar types");
        SimpleAssert.that(
            !HologramResourceReviewFact.freeze(bounded)
                .compact(),
            "extended certificate uses full fallback");
        source.setString("rows", "opaque future rows");
        bounded = (NBTTagCompound) bound.invoke(null, source, 0, 0L);
        SimpleAssert
            .that(HologramSnapshotNbt.same(source, bounded), "unknown row schema is preserved, not projected away");
    }

    private static void realContextReuseAndLeaseLifetime() throws Exception {
        Thread owner = Thread.currentThread();
        int[] calls = { 0 };
        GTMBExtensions.Registration codec = ResourceContainers.register("gtmb:test_compact_resource_fact", value -> {
            SimpleAssert.that(
                Thread.currentThread() == owner && !HologramSnapshotDataContext.active(),
                "native provider remains owner-only");
            calls[0]++;
            return null;
        });
        HologramRetainedCache.drain();
        long before = HologramProcessGovernor.retainedBytes();
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        AutoCloseable consumer = null;
        try {
            HologramPresets.Cell first = cell(1), repeated = cell(1), changed = cell(2);
            NBTTagCompound expected;
            Method ownerFreeze = HologramSnapshotDataContext.class.getDeclaredMethod("ownerFreeze");
            ownerFreeze.setAccessible(true);
            try (AutoCloseable scope = (AutoCloseable) ownerFreeze.invoke(context);
                HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
                expected = HologramBlueprintProcessing.freezeResourceReview(first, "tile", facts, 8192, 8L << 20);
            }
            SimpleAssert.that(
                HologramResourceReviewFact.freeze(expected)
                    .compact(),
                "ordinary configuration fixture is compact");
            context.freezeBlock(first);
            int firstCalls = calls[0];
            long firstBytes = context.retainedResourceBytes();
            context.freezeBlock(repeated);
            SimpleAssert.eq(firstCalls, calls[0], "identical identity reuses its owner-evaluated fact");
            SimpleAssert.eq(firstBytes, context.retainedResourceBytes(), "identical identity has no second lease");
            context.freezeBlock(changed);
            SimpleAssert.that(calls[0] > firstCalls, "different complete payload is evaluated separately");
            SimpleAssert.eq(2, context.retainedResourceFacts(), "exact payload identity remains part of the key");
            SimpleAssert.eq(2176L, context.retainedResourceBytes(), "two compact facts use two honest entry charges");
            Field reviews = HologramSnapshotDataContext.class.getDeclaredField("resourceReviews");
            reviews.setAccessible(true);
            for (Object value : ((Map<?, ?>) reviews.get(context)).values()) {
                SimpleAssert.that(
                    value instanceof HologramResourceReviewFact && ((HologramResourceReviewFact) value).compact(),
                    "production directory retains typed facts rather than original NBT compounds");
            }
            Field fixed = HologramSnapshotDataContext.class.getDeclaredField("fixedBytes");
            fixed.setAccessible(true);
            long held = HologramProcessGovernor.retainedBytes();
            SimpleAssert.eq(
                fixed.getLong(context) + context.retainedDecodedBytes(),
                held - before,
                "real governor ledger accounts for fixed facts and optional decoded residency");
            consumer = context.retain();
            context.close();
            SimpleAssert.eq(
                held,
                HologramProcessGovernor.retainedBytes(),
                "owner close cannot retire a live detached consumer");
            int frozenCalls = calls[0];
            FutureTask<Boolean> worker = new FutureTask<>(() -> {
                HologramSnapshotDataContext.enter(context);
                try {
                    NBTTagCompound actual = HologramSnapshotDataContext.resourceReview(first, "tile");
                    boolean same = HologramSnapshotNbt.same(expected, actual);
                    actual.setLong("total", 999);
                    return same && HologramSnapshotNbt
                        .same(expected, HologramSnapshotDataContext.resourceReview(first, "tile"));
                } finally {
                    HologramSnapshotDataContext.exit();
                }
            });
            new Thread(worker, "GTMB-compact-facts-test").start();
            SimpleAssert.that(
                worker.get(10, TimeUnit.SECONDS),
                "worker sees native-equivalent independently owned certificates");
            SimpleAssert.eq(frozenCalls, calls[0], "worker materialization performs no native callbacks");
            consumer.close();
            consumer = null;
            SimpleAssert
                .eq(before, HologramProcessGovernor.retainedBytes(), "last consumer releases actual leases without GC");
            SimpleAssert.eq(0, context.retainedResourceFacts(), "last close drops the typed-fact directory");
        } finally {
            context.close();
            if (consumer != null) consumer.close();
            codec.close();
        }
    }

    private static HologramPresets.Cell cell(int marker) throws Exception {
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.block = "minecraft:stone";
        NBTTagCompound root = new NBTTagCompound(), config = new NBTTagCompound();
        config.setInteger("setting", marker);
        root.setTag("extensionConfig", config);
        cell.tileNbt = HologramBlueprintNbt.encodeData(root);
        return cell;
    }
}
