package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Component reuse/isolation, plus an explicit streaming benchmark; no live world or installation is simulated. */
public final class HologramBlueprintValidationCacheRegression {

    private HologramBlueprintValidationCacheRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramBlueprintValidationCacheRegression.class, args)) return;
        run();
        BenchmarkSamples repeated = new BenchmarkSamples(1), mixed = new BenchmarkSamples(64);
        compare("repeated", repeated);
        compare("mixed64", mixed);
        for (int count : new int[] { 100000, 1000000 }) {
            BenchmarkResult result = benchmark(count, mixed, true);
            report("mixed64-scale", count, true, new BenchmarkResult[] { result }, 0);
        }
    }

    static void run() {
        try {
            reuseAndIsolation();
            boundedFallback();
            authoritativePositions();
        } catch (IOException failure) {
            throw new AssertionError("pass-local blueprint validation cache", failure);
        }
    }

    private static void reuseAndIsolation() throws IOException {
        HologramPresets.Preset preset = preset();
        String encoded = payload(7);
        HologramBlueprintValidationCache cache = new HologramBlueprintValidationCache(preset);
        HologramPresets.Cell first = cell(1, encoded), second = cell(99, new String(encoded));
        HologramPresets.validateBlueprintCell(first, true, cache);
        cache.filter(first);
        HologramPresets.validateBlueprintCell(second, true, cache);
        cache.filter(second);
        SimpleAssert.eq(1L, cache.filterMisses, "equal immutable encodings filter once across positions");
        SimpleAssert.eq(1L, cache.filterHits, "separate equal strings hit by content");
        SimpleAssert.eq(1L, cache.damageMisses, "vanilla block metadata is resolved once");
        SimpleAssert.eq(1L, cache.damageHits, "same vanilla block metadata reuses material variant");
        SimpleAssert.eq(2L, cache.registryMisses, "one item and block registry resolution per pass");
        SimpleAssert.eq(first.tileNbt, second.tileNbt, "filtered immutable transport result is shared");
        final int[] callbacks = { 0 };
        Block thirdParty = new Block(Material.rock) {

            @Override
            public int getDamageValue(World world, int x, int y, int z) {
                return ++callbacks[0];
            }
        };
        SimpleAssert.eq(1, cache.materialDamage(thirdParty, 0), "third-party callback executes first read");
        SimpleAssert.eq(2, cache.materialDamage(thirdParty, 0), "third-party callback is never memoized");

        HologramPresets.Cell different = cell(3, payload(8));
        cache.filter(different);
        SimpleAssert.eq(2L, cache.filterMisses, "different full NBT never aliases by type");
        SimpleAssert.eq(
            8,
            HologramBlueprintNbt.decode(different.tileNbt)
                .getInteger("config"),
            "distinct NBT survives");
        different.tileNbt = payload(9);
        cache.filter(different);
        SimpleAssert.eq(
            9,
            HologramBlueprintNbt.decode(different.tileNbt)
                .getInteger("config"),
            "edited encoded key invalidates");

        HologramPresets.Cell otherType = cell(4, encoded);
        otherType.block = "minecraft:dirt";
        cache.filter(otherType);
        SimpleAssert.eq(4L, cache.filterMisses, "tile projection keys include block identity");
        HologramPresets.Cell item = cell(5, null);
        item.itemNbt = encoded;
        cache.filter(item);
        SimpleAssert.eq(5L, cache.filterMisses, "tile and item projection never share schema context");

        UUID actor = UUID.randomUUID();
        NBTTagCompound atFirst = HologramBlueprintNbt
            .forPlacement(first.tileNbt, actor, "One", 1, 64, 2, true, true, null);
        NBTTagCompound atSecond = HologramBlueprintNbt
            .forPlacement(second.tileNbt, actor, "Two", 99, 70, 9, true, true, null);
        SimpleAssert.eq(1, atFirst.getInteger("x"), "first final position is bound after cache");
        SimpleAssert.eq(99, atSecond.getInteger("x"), "second final position remains independent");
        atFirst.setInteger("config", -1);
        SimpleAssert.eq(
            7,
            HologramBlueprintNbt.decode(second.tileNbt)
                .getInteger("config"),
            "mutable placement result cannot poison cache");

        preset.retainedNbtKeys = new ArrayList<>(Arrays.asList("config"));
        HologramBlueprintValidationCache frozen = new HologramBlueprintValidationCache(preset);
        preset.retainedNbtKeys.clear();
        HologramPresets.Cell frozenCell = cell(6, encoded);
        frozen.filter(frozenCell);
        SimpleAssert.eq(
            7,
            HologramBlueprintNbt.decode(frozenCell.tileNbt)
                .getInteger("config"),
            "selection list is frozen once per pass");
        HologramBlueprintValidationCache changed = new HologramBlueprintValidationCache(preset);
        HologramPresets.Cell changedCell = cell(7, encoded);
        changed.filter(changedCell);
        SimpleAssert.that(
            !HologramBlueprintNbt.decode(changedCell.tileNbt)
                .hasKey("config"),
            "new pass observes changed selection");

        String gt = gtPayload(12);
        cache.validateGt(gt, 12, "BaseMetaTileEntity", false, false);
        cache.validateGt(new String(gt), 12, "BaseMetaTileEntity", false, false);
        SimpleAssert.eq(1L, cache.gtHits, "GT header validation reuses exact payload and damage");
        expectIo(() -> cache.validateGt(gt, 13, "BaseMetaTileEntity", false, false));
        expectIo(() -> cache.validateGt(gt, 12, "BaseMetaPipeEntity", false, false));
        expectIo(() -> cache.validateGt(gtPayload(13), 12, "BaseMetaTileEntity", false, false));
        HologramPresets.Cell invalid = cell(8, "invalid base64!");
        expectIo(() -> cache.filter(invalid));
        long misses = cache.filterMisses;
        expectIo(() -> cache.filter(invalid));
        SimpleAssert.eq(misses + 1, cache.filterMisses, "failed validation is never cached as success");
    }

    private static void boundedFallback() throws IOException {
        HologramPresets.Preset preset = preset();
        HologramBlueprintValidationCache bounded = new HologramBlueprintValidationCache(preset, 2, 32);
        for (int i = 0; i < 16; i++) {
            HologramPresets.Cell cell = cell(i, payload(i));
            bounded.filter(cell);
            SimpleAssert.eq(
                i,
                HologramBlueprintNbt.decode(cell.tileNbt)
                    .getInteger("config"),
                "cache saturation preserves full filtering");
        }
        SimpleAssert
            .that(bounded.entries() <= 2 && bounded.stringChars() <= 32, "entry and character budgets are bounded");
        SimpleAssert.eq(16L, bounded.filterMisses, "oversized keys take ordinary uncached path");
        HologramBlueprintValidationCache next = new HologramBlueprintValidationCache(preset);
        next.filter(cell(0, payload(0)));
        SimpleAssert.eq(1L, next.filterMisses, "new task has no prior player or world cache state");
    }

    private static void authoritativePositions() throws IOException {
        HologramPresets.Preset preset = preset();
        preset.cells.add(cell(0, null));
        preset.cells.add(cell(1, null));
        HologramPresets.validate(preset);
        preset.cells.get(1).x = 0;
        expectIo(() -> HologramPresets.validate(preset));
        preset.cells.get(1).x = 1;
        preset.cells.get(1).y = 257;
        expectIo(() -> HologramPresets.validate(preset));
        preset.cells.get(1).y = 0;
        preset.cells.get(1).structureId = "absent";
        expectIo(() -> HologramPresets.validate(preset));
        preset.cells.get(1).structureId = null;
        preset.cells.get(1).facing = 6;
        expectIo(() -> HologramPresets.validate(preset));
        preset.cells.get(1).facing = -1;
        preset.cells.get(1).damage = 1;
        expectIo(() -> HologramPresets.validate(preset));
    }

    private static void compare(String fixture, BenchmarkSamples samples) throws IOException {
        int count = 50000;
        // Warm both paths three times at the measured size; alternate order to reduce systematic order bias.
        for (int round = 0; round < 3; round++) {
            benchmark(count, samples, round % 2 == 0);
            benchmark(count, samples, round % 2 != 0);
            System.out.println(
                "{\"scope\":\"validation-component-stream\",\"nativeWorld\":false,\"fixture\":\"" + fixture
                    + "\",\"phase\":\"warmup\",\"round\":"
                    + (round + 1)
                    + ",\"cellsPerPath\":"
                    + count
                    + "}");
        }
        BenchmarkResult[] disabled = new BenchmarkResult[3], enabled = new BenchmarkResult[3];
        for (int round = 0; round < 3; round++) {
            if (round % 2 == 0) {
                disabled[round] = benchmark(count, samples, false);
                enabled[round] = benchmark(count, samples, true);
            } else {
                enabled[round] = benchmark(count, samples, true);
                disabled[round] = benchmark(count, samples, false);
            }
        }
        report(fixture, count, false, disabled, 3);
        report(fixture, count, true, enabled, 3);
    }

    private static BenchmarkResult benchmark(int count, BenchmarkSamples samples, boolean enabled) throws IOException {
        HologramPresets.Preset preset = preset();
        HologramBlueprintValidationCache cache = enabled ? new HologramBlueprintValidationCache(preset)
            : new HologramBlueprintValidationCache(preset, 0, 0);
        String[] observed = new String[samples.encoded.length];
        long started = System.nanoTime();
        for (int i = 0; i < count; i++) {
            int variant = i % samples.encoded.length;
            // Every position owns a fresh mutable cell; only immutable complete source encodings are shared.
            HologramPresets.Cell cell = cell(i, samples.encoded[variant]);
            HologramPresets.validateBlueprintCell(cell, true, cache);
            cache.filter(cell);
            if (!samples.expected[variant].equals(cell.tileNbt))
                throw new AssertionError("mixed complete NBT projection aliased at component position " + i);
            observed[variant] = cell.tileNbt;
        }
        long elapsed = System.nanoTime() - started;
        SimpleAssert.eq(
            enabled ? (long) samples.encoded.length : (long) count,
            cache.filterMisses,
            "streaming benchmark accounts for every complete NBT miss");
        SimpleAssert.eq(
            enabled ? (long) count - samples.encoded.length : 0L,
            cache.filterHits,
            "streaming benchmark accounts for every exact duplicate");
        SimpleAssert.eq(enabled ? (long) count - 1 : 0L, cache.damageHits, "vanilla material cache hit count");
        SimpleAssert.eq(enabled ? 1L : (long) count, cache.damageMisses, "vanilla material cache miss count");
        SimpleAssert.eq(
            samples.encoded.length,
            new HashSet<>(Arrays.asList(observed)).size(),
            "every distinct complete NBT variant remains distinct");
        for (int variant = 0; variant < observed.length; variant++) SimpleAssert.eq(
            variant,
            HologramBlueprintNbt.decode(observed[variant])
                .getInteger("config"),
            "mixed NBT configuration survives every independent cell projection");
        if (!enabled) SimpleAssert.eq(0, cache.entries(), "disabled comparison never admits cache entries");
        return new BenchmarkResult(elapsed, cache, samples.encoded.length);
    }

    private static void report(String fixture, int count, boolean enabled, BenchmarkResult[] results, int warmups) {
        BenchmarkResult[] sorted = results.clone();
        Arrays.sort(sorted, (left, right) -> Long.compare(left.elapsedNanos, right.elapsedNanos));
        BenchmarkResult median = sorted[sorted.length / 2];
        HologramBlueprintValidationCache cache = median.cache;
        long[] measurements = new long[results.length];
        for (int i = 0; i < results.length; i++) measurements[i] = results[i].elapsedNanos;
        System.out.println(
            "{\"scope\":\"validation-component-stream\",\"nativeWorld\":false,\"fixture\":\"" + fixture
                + "\",\"payload\":\"small-synthetic-full-encoded-tile-nbt\",\"cells\":"
                + count
                + ",\"distinctNbtVariants\":"
                + median.variants
                + ",\"cacheEnabled\":"
                + enabled
                + ",\"warmupRounds\":"
                + warmups
                + ",\"measuredRounds\":"
                + results.length
                + ",\"medianNanos\":"
                + median.elapsedNanos
                + ",\"measurementsNanos\":"
                + Arrays.toString(measurements)
                + ",\"filterHits\":"
                + cache.filterHits
                + ",\"filterMisses\":"
                + cache.filterMisses
                + ",\"registryHits\":"
                + cache.registryHits
                + ",\"registryMisses\":"
                + cache.registryMisses
                + ",\"damageHits\":"
                + cache.damageHits
                + ",\"damageMisses\":"
                + cache.damageMisses
                + ",\"entries\":"
                + cache.entries()
                + ",\"gtHits\":"
                + cache.gtHits
                + ",\"gtMisses\":"
                + cache.gtMisses
                + ",\"syntaxHits\":"
                + cache.syntaxHits
                + ",\"syntaxMisses\":"
                + cache.syntaxMisses
                + ",\"stringChars\":"
                + cache.stringChars()
                + "}");
    }

    private static final class BenchmarkSamples {

        final String[] encoded, expected;

        BenchmarkSamples(int variants) throws IOException {
            encoded = new String[variants];
            expected = new String[variants];
            HologramBlueprintValidationCache uncached = new HologramBlueprintValidationCache(preset(), 0, 0);
            for (int variant = 0; variant < variants; variant++) {
                encoded[variant] = payload(variant);
                HologramPresets.Cell sample = cell(0, encoded[variant]);
                uncached.filter(sample);
                expected[variant] = sample.tileNbt;
            }
            SimpleAssert
                .eq(variants, new HashSet<>(Arrays.asList(encoded)).size(), "full source encodings are distinct");
            SimpleAssert
                .eq(variants, new HashSet<>(Arrays.asList(expected)).size(), "full expected projections are distinct");
        }
    }

    private static final class BenchmarkResult {

        final long elapsedNanos;
        final HologramBlueprintValidationCache cache;
        final int variants;

        BenchmarkResult(long elapsedNanos, HologramBlueprintValidationCache cache, int variants) {
            this.elapsedNanos = elapsedNanos;
            this.cache = cache;
            this.variants = variants;
        }
    }

    private static HologramPresets.Preset preset() {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = "validation-cache";
        preset.name = "Validation cache";
        return preset;
    }

    private static HologramPresets.Cell cell(int x, String encoded) {
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.item = "minecraft:stone";
        cell.block = "minecraft:stone";
        cell.x = x;
        cell.tileNbt = encoded;
        return cell;
    }

    private static String payload(int config) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("config", config);
        tag.setString("custom", "retained");
        return HologramBlueprintNbt.encodeCaptured(tag);
    }

    private static String gtPayload(int id) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("mID", id);
        tag.setString("id", "BaseMetaTileEntity");
        return HologramBlueprintNbt.encodeCaptured(tag);
    }

    private interface Checked {

        void run() throws IOException;
    }

    private static void expectIo(Checked work) {
        try {
            work.run();
            throw new AssertionError("invalid per-cell data was accepted after a cache hit");
        } catch (IOException expected) {
            // Expected authoritative rejection.
        }
    }
}
