package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Deterministic server/common contracts; no world writes or payment outcomes are cached. */
public final class HologramSegmentValidationRegression {

    public static void run() {
        try {
            stablePages();
            exactBlobs();
            projectionBoundary();
            occurrenceBudget();
        } catch (IOException failure) {
            throw new AssertionError("segmented admission regression", failure);
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
