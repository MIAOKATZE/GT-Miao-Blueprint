package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Deterministic server/common contracts; no world writes or payment outcomes are cached. */
public final class HologramSegmentValidationRegression {

    public static void run() {
        try {
            stablePages();
            workingSetOwnership();
            largeWorkingSet();
            exactBlobs();
            projectionBoundary();
            occurrenceBudget();
        } catch (IOException failure) {
            throw new AssertionError("segmented admission regression", failure);
        }
    }

    private static void largeWorkingSet() throws IOException {
        HologramPresets.Preset large = preset(100000);
        HologramPresets.validate(large);
        SimpleAssert.that(large.segmentDocument == null, "large shape cache never masquerades as a full manifest");
        SimpleAssert.eq(100000L, large.schemaPageCache.checks, "every cold large-document shape is checked");
        checkWorkingSet(large, false);
        HologramPresets.validate(large);
        checkWorkingSet(large, true);
        long firstCursor = large.schemaPageCache.scanCursor;
        for (int index : new int[] { 0, 50000, 99999 }) {
            HologramPresets.Cell cell = large.cells.get(index);
            HologramSchemaPageCache.prefer(large, cell.segmentCellId);
            cell.prompt = "edited " + index;
            HologramPresets.validate(large);
            checkWorkingSet(large, true);
            long page = (cell.segmentCellId - 1) / HologramSegmentContract.PAGE_SIZE;
            SimpleAssert.that(large.schemaPageCache.pages.containsKey(page), "an active middle/tail page enters cache");
            HologramPresets.validate(large);
            checkWorkingSet(large, true);
            SimpleAssert.that(large.schemaPageCache.pages.containsKey(page), "active page survives the cold scan");
        }
        SimpleAssert
            .that(large.schemaPageCache.scanCursor != firstCursor, "bounded exploration progresses past first pages");
        HologramSchemaPageCache saved = large.schemaPageCache;
        int oldX = large.cells.get(99999).x;
        large.cells.get(99999).x = large.cells.get(0).x;
        expectIo(() -> HologramPresets.validate(large));
        SimpleAssert.that(large.schemaPageCache == saved, "cross-page duplicate cannot publish staged shape proofs");
        large.cells.get(99999).x = oldX;
        HologramPresets.Cell middle = large.cells.get(50000);
        middle.allowedTargets = new java.util.ArrayList<>();
        HologramPresets.Target target = new HologramPresets.Target();
        target.item = "minecraft:stone";
        middle.allowedTargets.add(target);
        HologramSchemaPageCache.prefer(large, middle.segmentCellId);
        HologramPresets.validate(large);
        target.damage = -1; // Modify the old mutable DTO in-place, with the same list and target identities.
        expectIo(() -> HologramPresets.validate(large));
        target.damage = 0;
        middle.structureId = "missing";
        expectIo(() -> HologramPresets.validate(large));
        middle.structureId = null;
        middle.tileNbt = "invalid!";
        expectIo(() -> HologramPresets.validate(large));
        middle.tileNbt = null;

        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("config", 9);
        String payload = HologramBlueprintNbt.encodeCanonical(tag);
        for (HologramPresets.Cell cell : large.cells) cell.tileNbt = payload;
        HologramPresets.validate(large);
        HologramPresets.validate(large);
        checkWorkingSet(large, true);
        SimpleAssert.eq(payload, large.cells.get(99999).tileNbt, "repeated payload remains exact on a large warm path");
        HologramPresets.Preset repeated = preset(100000);
        for (HologramPresets.Cell cell : repeated.cells) cell.tileNbt = payload;
        HologramPresets.validate(repeated);
        SimpleAssert
            .eq(100000L, repeated.schemaPageCache.checks, "fresh repeated-payload input receives full cold admission");
        checkWorkingSet(repeated, false);
        HologramPresets.validate(repeated);
        checkWorkingSet(repeated, true);
        HologramPresets.Preset detached = HologramPresets.copy(large);
        SimpleAssert
            .that(detached.schemaPageCache == large.schemaPageCache, "legacy copies share immutable shape proofs");
        runPrepared(detached);
        checkWorkingSet(detached, true);
        for (int index : new int[] { 0, 50000, 99999 }) {
            HologramPresets.Cell cell = detached.cells.get(index);
            HologramSchemaPageCache.prefer(detached, cell.segmentCellId);
            cell.prompt = "prepared edit " + index;
            runPrepared(detached);
            checkWorkingSet(detached, true);
            SimpleAssert.that(
                detached.schemaPageCache.pages
                    .containsKey((cell.segmentCellId - 1) / HologramSegmentContract.PAGE_SIZE),
                "prepared validation publishes the active page after strict success");
        }
        String oldSwitch = System.getProperty("gtmb.segment.validationCache");
        try {
            System.setProperty("gtmb.segment.validationCache", "false");
            HologramPresets.validate(detached);
            SimpleAssert.that(detached.schemaPageCache == null, "cache-off retains no shape proof");
            for (int index : new int[] { 0, 50000, 99999 }) SimpleAssert.eq(
                large.cells.get(index).tileNbt,
                detached.cells.get(index).tileNbt,
                "warm/prepared/cache-off preserve repeated NBT output");
        } finally {
            if (oldSwitch == null) System.clearProperty("gtmb.segment.validationCache");
            else System.setProperty("gtmb.segment.validationCache", oldSwitch);
        }
    }

    private static void checkWorkingSet(HologramPresets.Preset preset, boolean warm) {
        HologramSchemaPageCache cache = preset.schemaPageCache;
        SimpleAssert.that(cache != null && !cache.pages.isEmpty(), "large legal document retains actual shape pages");
        SimpleAssert.that(cache.pages.size() <= HologramSchemaPageCache.MAX_PAGES, "page count remains bounded");
        SimpleAssert.that(cache.retainedBytes <= HologramSchemaPageCache.MAX_BYTES, "retained shape pages obey bytes");
        SimpleAssert.that(
            cache.retainedBytes < 128L * 1024 * cache.pages.size(),
            "resident leases charge actual conservative page weights rather than all scratch maxima");
        SimpleAssert.eq(
            (long) preset.cells.size() + preset.palette.size(),
            cache.checks + cache.reused,
            "every logical cell has an exact proof or a real shape check");
        SimpleAssert.that(cache.checks > 0, "nonresident pages remain real strict misses on a large document");
        if (warm) SimpleAssert.that(cache.reused > 0, "warm large document really reuses exact shape proofs");
        System.out.println(
            "schema-working-set cells=" + preset.cells.size()
                + " pages="
                + cache.pages.size()
                + " bytes="
                + cache.retainedBytes
                + " checks="
                + cache.checks
                + " reused="
                + cache.reused);
    }

    private static void runPrepared(HologramPresets.Preset preset) throws IOException {
        try (HologramPreparedBlueprintValidation task = new HologramPreparedBlueprintValidation(preset)) {
            for (int i = 0; !task.done() && i < preset.cells.size() + preset.palette.size() + 1000; i++) task.advance();
            SimpleAssert.that(task.done(), "prepared validation completes in bounded per-cell steps");
        }
    }

    private static void workingSetOwnership() throws IOException {
        HologramPresets.Preset source = preset(2);
        List<HologramProcessGovernor.Lease> leases;
        // Reserve may reap unrelated retired caches. A process-wide before/after equality can therefore
        // fail on correct cleanup, or hide a leaked stage lease behind an unrelated release.
        try (HologramProcessGovernor.Lease unrelated = HologramProcessGovernor.retain("fixture.retired.schema", 4096);
            HologramRetainedCache.Owner retired = HologramRetainedCache.watch(source)) {
            retired.add(unrelated);
            SimpleAssert.that(!leaseClosed(unrelated), "unrelated retirement fixture starts charged");
            boolean rejectedOpenLease = false;
            try {
                closedLeases(Collections.singletonList(unrelated), "negative control must reject an open lease");
            } catch (AssertionError expected) {
                rejectedOpenLease = true;
            }
            SimpleAssert.that(rejectedOpenLease, "exact closure oracle detects a genuinely open lease");
            try (HologramSchemaPageCache.Stage stage = HologramSchemaPageCache.begin(source)) {
                retainedLease(stage, "root", "schema.pages.staging", 128 * 1024);
                stage.validate(source.cells.get(0), true, new HologramBlueprintValidationCache(source));
                leases = stageLeases(stage, 0);
                // Model unrelated retirement deterministically, without the reaper's deadline or GC timing.
                retired.close();
                closedLeases(Collections.singletonList(unrelated), "unrelated charge retires while staging is live");
                for (HologramProcessGovernor.Lease lease : leases)
                    SimpleAssert.that(!leaseClosed(lease), "unrelated retirement does not close a stage lease");
            }
            closedLeases(leases, "aborted staging releases both root and page");
        }
        try (HologramPreparedBlueprintValidation task = new HologramPreparedBlueprintValidation(source)) {
            for (int i = 0; i < 6; i++) task.advance();
            leases = stageLeases((HologramSchemaPageCache.Stage) field(task, "schema"), 0);
        }
        closedLeases(leases, "prepared cancellation releases staged proof leases");
        SimpleAssert.that(source.schemaPageCache == null, "prepared cancellation publishes no partial proof");
        HologramPresets.Preset cancelled = preset(600);
        try (HologramSchemaPageCache.Stage stage = HologramSchemaPageCache.begin(cancelled)) {
            HologramBlueprintValidationCache validation = new HologramBlueprintValidationCache(cancelled);
            stage.validate(cancelled.cells.get(0), true, validation);
            HologramProcessGovernor.Lease firstScratch = retainedLease(
                field(stage, "builder"),
                "lease",
                "schema.page",
                128 * 1024);
            for (int i = 1; i < 257; i++) stage.validate(cancelled.cells.get(i), true, validation);
            SimpleAssert.that(leaseClosed(firstScratch), "freezing a page releases its original scratch lease");
            leases = stageLeases(stage, 1);
        }
        closedLeases(leases, "cancellation releases root, frozen candidate page and next scratch page");

        HologramPresets.Preset alias = preset(1);
        alias.cells.get(0).block = null;
        runPrepared(alias);
        SimpleAssert.eq("minecraft:stone", alias.cells.get(0).block, "material validation derives a real block alias");
        runPrepared(alias);
        SimpleAssert.eq(
            1L,
            alias.schemaPageCache.checks,
            "a rewritten alias is reconciled instead of certifying unchecked output as original input");
        runPrepared(alias);
        SimpleAssert.eq(1L, alias.schemaPageCache.reused, "the reconciled alias then receives an exact warm proof");

        String shared = new String(new char[3000]).replace('\0', 'p');
        HologramPresets.Preset sharedText = preset(2), separateText = preset(2);
        for (int i = 0; i < 2; i++) {
            sharedText.cells.get(i).prompt = shared;
            separateText.cells.get(i).prompt = new String(shared.toCharArray());
        }
        runPrepared(sharedText);
        runPrepared(separateText);
        SimpleAssert.that(
            separateText.schemaPageCache.retainedBytes > sharedText.schemaPageCache.retainedBytes + 6000,
            "one shared String object is charged once per page, while distinct equal objects own separate bytes");

        HologramPresets.Preset heavy = preset(600);
        HologramPresets.Cell first = heavy.cells.get(0);
        first.allowedTargets = new java.util.ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            HologramPresets.Target target = new HologramPresets.Target();
            target.item = "fixture:target_" + i + "_" + new String(new char[128]).replace('\0', 'x');
            target.damage = i;
            first.allowedTargets.add(target);
        }
        runPrepared(heavy);
        SimpleAssert.that(!heavy.schemaPageCache.pages.containsKey(0L), "oversized shape page falls back alone");
        SimpleAssert.that(heavy.schemaPageCache.pages.containsKey(1L), "other pages survive one oversized page");
        runPrepared(heavy);
        SimpleAssert.that(heavy.schemaPageCache.reused > 0, "ordinary pages remain warm next to a heavy page");
        HologramSchemaPageCache previous = heavy.schemaPageCache;
        heavy.cells.get(599).count = 2;
        expectIo(() -> runPrepared(heavy));
        SimpleAssert
            .that(heavy.schemaPageCache == previous, "prepared tail failure preserves previous proof working set");
    }

    private static List<HologramProcessGovernor.Lease> stageLeases(HologramSchemaPageCache.Stage stage,
        int frozenPages) {
        List<HologramProcessGovernor.Lease> leases = new ArrayList<>();
        leases.add(retainedLease(stage, "root", "schema.pages.staging", 128 * 1024));
        leases.add(retainedLease(field(stage, "builder"), "lease", "schema.page", 128 * 1024));
        Map<?, ?> owned = (Map<?, ?>) field(stage, "owned");
        SimpleAssert.eq(frozenPages, owned.size(), "fixture captures every unpublished resident page");
        for (Object value : owned.values()) {
            HologramSchemaPageCache.Page page = (HologramSchemaPageCache.Page) value;
            leases.add(retainedLease(page, "unpublished", "schema.page.resident", page.weight));
        }
        return leases;
    }

    private static HologramProcessGovernor.Lease retainedLease(Object owner, String name, String label, long bytes) {
        HologramProcessGovernor.Lease lease = (HologramProcessGovernor.Lease) field(owner, name);
        SimpleAssert.that(lease != null, "fixture owns " + label);
        SimpleAssert.eq(label, lease.owner(), "captured lease has the expected owner");
        SimpleAssert.eq(bytes, lease.bytes(), "staging reserves exact bytes before shape allocation");
        SimpleAssert.that(!leaseClosed(lease), "captured lease is open before owner cancellation: " + label);
        return lease;
    }

    private static void closedLeases(List<HologramProcessGovernor.Lease> leases, String message) {
        for (HologramProcessGovernor.Lease lease : leases)
            SimpleAssert.that(leaseClosed(lease), message + ": " + lease.owner());
    }

    private static boolean leaseClosed(HologramProcessGovernor.Lease lease) {
        // Core's bytes() is the immutable original charge even after close. Observe the exact lease's
        // terminal state instead, even after the Stage clears its ownership fields on cancellation.
        return ((AtomicBoolean) field(field(lease, "delegate"), "closed")).get();
    }

    private static Object field(Object owner, String name) {
        SimpleAssert.that(owner != null, "ownership fixture exists for field " + name);
        try {
            Field field = owner.getClass()
                .getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect exact lease ownership: " + name, failure);
        }
    }

    private static void stablePages() throws IOException {
        HologramPresets.Preset preset = preset(600);
        HologramPresets.validate(preset);
        SimpleAssert.eq(600L, preset.segmentDocument.schemaChecks, "all cold cells receive schema admission");
        SimpleAssert.eq(3, preset.segmentDocument.pages.size(), "common logical pages contain 256 stable ids");
        HologramPresets.validate(preset);
        SimpleAssert.eq(0L, preset.segmentDocument.schemaChecks, "unchanged pages perform no deep schema checks");
        HologramSegmentContract.Stamp assets = preset.segmentDocument.pages.get(0L)
            .stamp(HologramSegmentContract.Domain.ASSET_NEEDS);
        preset.cells.get(0).prompt = "display only";
        HologramPresets.validate(preset);
        SimpleAssert.eq(256L, preset.segmentDocument.schemaChecks, "one edit validates only its page");
        SimpleAssert.eq(2L, preset.segmentDocument.reusedPages, "untouched pages reuse their domain certificates");
        SimpleAssert.that(
            assets.sameInput(
                preset.segmentDocument.pages.get(0L)
                    .stamp(HologramSegmentContract.Domain.ASSET_NEEDS)),
            "appearance edit preserves independent asset stamp");
        long nextPageId = preset.cells.get(256).segmentCellId;
        preset.cells.remove(0);
        HologramPresets.validate(preset);
        SimpleAssert.eq(nextPageId, preset.cells.get(255).segmentCellId, "delete never renumbers following cells");
        SimpleAssert.eq(255L, preset.segmentDocument.schemaChecks, "delete rebuilds only the changed logical page");
        HologramPresets.Preset copy = HologramPresets.copy(preset);
        HologramPresets.validate(copy);
        SimpleAssert.eq(0L, copy.segmentDocument.schemaChecks, "independent legacy DTO copy retains certificates");
        copy.cells.get(0).x = copy.cells.get(300).x;
        expectIo(() -> HologramPresets.validate(copy));
        HologramPresets.validate(preset);
        SimpleAssert
            .eq(0L, preset.segmentDocument.schemaChecks, "failed staged duplicate cannot poison source manifest");
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.id = "member";
        preset.structures.add(structure);
        preset.cells.get(0).structureId = structure.id;
        HologramPresets.validate(preset);
        HologramSegmentContract.Stamp relation = preset.segmentDocument.pages.get(0L)
            .stamp(HologramSegmentContract.Domain.RELATIONS);
        HologramSegmentContract.Stamp unrelated = preset.segmentDocument.pages.get(1L)
            .stamp(HologramSegmentContract.Domain.ASSET_NEEDS);
        structure.enabled = false;
        HologramPresets.validate(preset);
        SimpleAssert.eq(0L, preset.segmentDocument.schemaChecks, "structure policy does not invalidate cell schema");
        SimpleAssert.that(
            !relation.sameInput(
                preset.segmentDocument.pages.get(0L)
                    .stamp(HologramSegmentContract.Domain.RELATIONS)),
            "disable invalidates member page relation domain");
        SimpleAssert.that(
            unrelated.sameInput(
                preset.segmentDocument.pages.get(1L)
                    .stamp(HologramSegmentContract.Domain.ASSET_NEEDS)),
            "disable preserves unrelated page asset domain");
        SimpleAssert.that(
            preset.segmentDocument.structurePages.get("member")
                .contains(0L),
            "structure reverse index records member pages");
        preset.structures.clear();
        expectIo(() -> HologramPresets.validate(preset));
        preset.structures.add(structure);
        preset.cells.get(400).structureId = "missing";
        expectIo(() -> HologramPresets.validate(preset));
        preset.cells.get(400).structureId = null;
        preset.cells.get(400).allowedTargets = new java.util.ArrayList<>();
        preset.cells.get(400).allowedTargets.add(null);
        expectIo(() -> HologramPresets.validate(preset));
    }

    private static void exactBlobs() throws IOException {
        HologramBlueprintNbt.clearExactCache();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("config", 9);
        String encoded = HologramBlueprintNbt.encodeCaptured(tag);
        HologramBlueprintNbt.ExactBlob first = HologramBlueprintNbt.exact(encoded);
        HologramBlueprintNbt.ExactBlob second = HologramBlueprintNbt.exact(new String(encoded));
        SimpleAssert.that(first == second, "complete equal encodings share immutable blob facts");
        SimpleAssert.eq(1L, HologramBlueprintNbt.blobMisses, "same blob parsed only once");
        NBTTagCompound mutable = first.copyForEdit();
        mutable.setInteger("config", -1);
        SimpleAssert.eq(
            9,
            second.copyForEdit()
                .getInteger("config"),
            "mutable consumer cannot poison a blob");
        SimpleAssert.that(first.expandedBytes() > 0, "expanded occurrence accounting comes from exact blob");
        SimpleAssert.that(
            HologramBlueprintNbt.exact(null)
                .presence() == HologramSegmentContract.Presence.ABSENT,
            "absence has its own semantics");
        SimpleAssert.that(
            HologramBlueprintNbt.exact(HologramBlueprintNbt.encodeCaptured(new NBTTagCompound()))
                .presence() == HologramSegmentContract.Presence.EXPLICIT_EMPTY,
            "explicit empty is never absence");
        expectIo(() -> HologramBlueprintNbt.exact("invalid!"));
        long misses = HologramBlueprintNbt.blobMisses;
        expectIo(() -> HologramBlueprintNbt.exact("invalid!"));
        SimpleAssert.eq(misses + 1, HologramBlueprintNbt.blobMisses, "failed parses are never success entries");
        HologramBlueprintNbt.clearExactCache();
        SimpleAssert.eq(
            9,
            HologramBlueprintNbt.decode(encoded)
                .getInteger("config"),
            "eviction preserves output");
        SimpleAssert.that(
            HologramBlueprintNbt.exactCacheBytes() <= 32L * 1024 * 1024,
            "decoded cache obeys expanded-byte budget");
        NBTTagCompound pressure = new NBTTagCompound();
        pressure.setByteArray("data", new byte[128 * 1024]);
        for (int index = 0; index < 10; index++) {
            pressure.setInteger("variant", index);
            HologramBlueprintNbt.exact(HologramBlueprintNbt.encodeCaptured(pressure));
            SimpleAssert.that(
                HologramBlueprintNbt.exactCacheBytes() <= 32L * 1024 * 1024,
                "high cardinality evicts before retaining another expanded tree");
        }
        SimpleAssert.eq(
            9,
            HologramBlueprintNbt.decode(encoded)
                .getInteger("config"),
            "cold reconstruction after real LRU pressure preserves exact value");
    }

    private static void occurrenceBudget() throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByteArray("data", new byte[128 * 1024]);
        String encoded = HologramBlueprintNbt.encodeCaptured(tag);
        HologramPresets.Preset preset = preset(600);
        for (HologramPresets.Cell cell : preset.cells) cell.tileNbt = encoded;
        expectIo(() -> HologramPresets.validate(preset));
        SimpleAssert.that(
            preset.segmentDocument == null,
            "shared blob occurrence overflow never publishes a manifest certificate");
    }

    private static void projectionBoundary() throws IOException {
        HologramBlueprintValidationCache.clearProjectionCache();
        HologramPresets.Preset preset = preset(1);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("config", 42);
        String encoded = HologramBlueprintNbt.encodeCaptured(tag);
        HologramPresets.Cell first = preset.cells.get(0);
        first.tileNbt = encoded;
        HologramBlueprintValidationCache cold = new HologramBlueprintValidationCache(preset);
        cold.filter(first);
        HologramPresets.Cell second = HologramPresets.copy(preset).cells.get(0);
        second.tileNbt = encoded;
        HologramBlueprintValidationCache warm = new HologramBlueprintValidationCache(preset);
        warm.filter(second);
        SimpleAssert.eq(1L, cold.filterComputations, "cold pure payload projection runs exact filtering");
        SimpleAssert
            .eq(0L, warm.filterComputations, "same pure value reuses persistent projection without decode/copy/encode");
        SimpleAssert.eq(first.tileNbt, second.tileNbt, "cold and warm projection outputs match");
        first.block = "minecraft:chest";
        first.tileNbt = encoded;
        HologramBlueprintValidationCache unknown = new HologramBlueprintValidationCache(preset);
        unknown.filter(first);
        second.block = first.block;
        second.tileNbt = encoded;
        HologramBlueprintValidationCache unknownAgain = new HologramBlueprintValidationCache(preset);
        unknownAgain.filter(second);
        SimpleAssert.eq(
            1L,
            unknownAgain.filterComputations,
            "unaudited native schema retains the strict original filter despite a blob hit");
    }

    private static HologramPresets.Preset preset(int size) {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = "segment-fixture";
        preset.name = "Segment fixture";
        for (int index = 0; index < size; index++) {
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.item = "minecraft:stone";
            cell.block = "minecraft:stone";
            cell.x = index;
            preset.cells.add(cell);
        }
        return preset;
    }

    private interface Checked {

        void run() throws IOException;
    }

    private static void expectIo(Checked operation) {
        try {
            operation.run();
            throw new AssertionError("invalid segmented input was accepted");
        } catch (IOException expected) {
            // Expected strict admission failure.
        }
    }

    private HologramSegmentValidationRegression() {}
}
