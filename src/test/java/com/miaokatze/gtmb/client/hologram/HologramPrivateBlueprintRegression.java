// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.testutil.SimpleAssert.eq;
import static com.miaokatze.gtmb.testutil.SimpleAssert.that;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.client.hologram.HologramPrivateBlueprint.Position;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramBlueprintPreviewBinding;
import com.miaokatze.gtmb.testutil.BlueprintArtifactTestData;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

public final class HologramPrivateBlueprintRegression {

    private HologramPrivateBlueprintRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramPrivateBlueprintRegression.class, args)) return;
        Path scratch = Files.createTempDirectory("gtmb-private-document-");
        try {
            exercise(scratch);
            if (args.length > 0) exerciseLarge(scratch, Paths.get(args[0]));
            System.out.println("Private blueprint document round trip passed");
        } finally {
            try (Stream<Path> paths = Files.walk(scratch)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void exercise(Path scratch) throws Exception {
        byte[] legacy = legacyJson(), png = BlueprintArtifactTestData.previewPng();
        HologramClientLibrary library = new HologramClientLibrary(scratch);
        String id = "local_00000000-0000-0000-0000-000000000001";
        Path path = library.savedPath(id);
        HologramBlueprintArtifact.writePair(path, legacy, png);
        HologramBlueprintArtifact.Artifact original = library.privatePair(id);
        HologramPrivateBlueprint document = new HologramPrivateBlueprint(original.json);
        Position first = new Position(0, 0, 0), second = new Position(1, 0, 0), added = new Position(2, 0, 0);
        JsonObject firstRaw = document.cell(first);
        that(!document.upsert(first, "missing:block", 0, "", 0), "defaulted identity keeps the original payload");
        eq(2, document.visibleCells(), "legacy file has two visible cells");
        that(
            document.snapshot("test")
                .getTagList("cells", 10)
                .getCompoundTagAt(0)
                .getBoolean("privatePreviewPlaceholder"),
            "unrecognized NBT is indicated in preview without losing original bytes");

        that(document.upsert(second, "minecraft:cobblestone", 0, "minecraft:cobblestone", 0), "replace one cell");
        that(document.upsert(added, "minecraft:glass", 0, "minecraft:glass", 0), "add another cell");
        that(document.remove(first), "remove a cell with opaque data");
        eq(first, document.undo(true), "undo restores removed position");
        eq(firstRaw, document.cell(first), "undo restores exact opaque cell JSON");
        eq(first, document.undo(false), "redo reapplies deletion");
        eq(null, document.cell(first), "redo deletes the cell");
        document.undo(true);
        that(document.dirty(), "geometry edit makes document dirty");

        byte[] edited = document.saveBytes();
        JsonObject expected = HologramBlueprintCompactCodec.parse(legacy);
        JsonObject changed = HologramBlueprintCompactCodec.parse(edited);
        eq(expected.get("unknownEnvelope"), changed.get("unknownEnvelope"), "unknown root fields survive compact save");
        JsonObject before = expected.getAsJsonObject("blueprint"), after = changed.getAsJsonObject("blueprint");
        eq(before.get("authorName"), after.get("authorName"), "source author remains untouched");
        eq(before.get("authorUuid"), after.get("authorUuid"), "absence of a server author UUID remains untouched");
        eq(before.get("unknownBlueprint"), after.get("unknownBlueprint"), "unknown metadata survives");
        eq(before.get("semanticNbt"), after.get("semanticNbt"), "opaque blueprint NBT survives");
        eq(
            firstRaw,
            after.getAsJsonArray("cells")
                .get(0),
            "unchanged cell and NBT survive byte-for-byte as values");
        JsonObject replacement = after.getAsJsonArray("cells")
            .get(1)
            .getAsJsonObject();
        eq(
            "Retained field",
            replacement.get("forkCellInfo")
                .getAsString(),
            "unrecognized field survives replacement");
        that(!replacement.has("tileNbt"), "replacement does not attach another block's known tile payload");
        that(!after.has("fullyVoucherCovered"), "edited geometry retires old complete-coverage label");
        NBTTagCompound receipt = CompressedStreamTools.func_152457_a(
            Base64.getDecoder()
                .decode(
                    after.get("processingNbt")
                        .getAsString()),
            new NBTSizeTracker(1048576));
        that(!receipt.getBoolean("complete"), "old processing receipt is no longer complete");
        eq("kept", receipt.getString("forkReceipt"), "unrecognized processing tag survives");
        eq(
            3,
            receipt.getCompoundTag("policy")
                .getTagList("confirmedStages", 8)
                .tagCount(),
            "geometry dependencies retired");

        HologramBlueprintArtifact.Artifact next = HologramBlueprintArtifact.prepareSave(edited, png);
        HologramClientLibrary injected = new HologramClientLibrary(scratch, new ReadbackFailure());
        try {
            injected.saveDocument(next.json, next.png, id, original.jsonSha256, original.pngSha256, () -> true);
            throw new AssertionError("save with failed readback must fail");
        } catch (IOException expectedFailure) {
            eq("injected readback failure", expectedFailure.getMessage(), "failure after replacing pair is reported");
        }
        eq(original.jsonSha256, library.privatePair(id).jsonSha256, "readback failure rolls back JSON");
        eq(original.pngSha256, library.privatePair(id).pngSha256, "readback failure rolls back PNG");
        that(document.dirty(), "failed save preserves document edits");
        try {
            library.saveDocument(next.json, next.png, id, "stale", original.pngSha256, () -> true);
            throw new AssertionError("stale source must fail");
        } catch (IOException expectedFailure) {
            eq(original.jsonSha256, library.privatePair(id).jsonSha256, "stale writer cannot replace original");
        }
        HologramClientLibrary.DocumentSave saved = library
            .saveDocument(next.json, next.png, id, original.jsonSha256, original.pngSha256, () -> true);
        document.saved();
        that(!document.dirty(), "durable commit marks the new revision clean");
        HologramClientLibrary reopened = new HologramClientLibrary(scratch);
        HologramBlueprintArtifact.Artifact updated = reopened.privatePair(saved.id);
        eq(saved.jsonHash, updated.jsonSha256, "logical source reopens updated compact JSON");
        eq(saved.pngHash, updated.pngSha256, "reopened preview belongs to saved JSON");
        HologramPrivateBlueprint compact = new HologramPrivateBlueprint(updated.json);
        eq("minecraft:glass", HologramPrivateBlueprint.text(compact.cell(added), "block"), "new cell is durable");
        eq("Original builder", compact.author(), "author survives restart with no authenticated player");
        eq(
            changed,
            HologramBlueprintCompactCodec.parse(HologramBlueprintPreviewBinding.sourceJson(updated.json)),
            "compact pair round trips all surviving values");

        document.undo(true);
        document.undo(true);
        eq(
            expected,
            HologramBlueprintCompactCodec.parse(document.saveBytes()),
            "undo to original restores receipt too");
        HologramClientLibrary.DocumentSave copy = reopened.saveDocument(next.json, next.png, "", "", "", () -> true);
        that(!copy.id.equals(saved.id), "Save Copy uses a new logical ID");
        eq(
            2,
            reopened.list()
                .size(),
            "both portable pairs remain visible");

        HologramPrivateBlueprint empty = new HologramPrivateBlueprint(
            "{\"schema\":2,\"blueprint\":{\"name\":\"Empty document\",\"cells\":[]}}".getBytes(StandardCharsets.UTF_8));
        HologramBlueprintArtifact.Artifact emptyPair = geometryPair(empty);
        HologramClientLibrary.DocumentSave emptySaved = reopened
            .saveDocument(emptyPair.json, emptyPair.png, "", "", "", () -> true);
        HologramPrivateBlueprint emptyAgain = new HologramPrivateBlueprint(reopened.privatePair(emptySaved.id).json);
        eq(0, emptyAgain.visibleCells(), "empty geometry has a bound preview and reopens without a world or renderer");
    }

    private static void exerciseLarge(Path scratch, Path sampleDirectory) throws Exception {
        Path largest = null;
        long size = 0;
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(sampleDirectory, "*.json")) {
            for (Path path : files) {
                if (!Files.isRegularFile(HologramBlueprintArtifact.previewPath(path))) continue;
                long candidate = Files.size(path);
                if (candidate <= HologramBlueprintArtifact.MAX_JSON_BYTES && candidate > size) {
                    largest = path;
                    size = candidate;
                }
            }
        }
        if (largest == null) throw new IOException("No paired blueprint found in read-only sample directory");
        HologramBlueprintArtifact.Artifact original = HologramBlueprintArtifact.readPair(largest);
        HologramPrivateBlueprint sample = new HologramPrivateBlueprint(original.json);
        int before = sample.visibleCells();
        that(before > 100_000, "sample actually exercises a large document");
        Position unused = new Position(2_000_000, 0, 0);
        while (sample.cell(unused) != null) unused = new Position(unused.x + 1, 0, 0);
        sample.upsert(unused, "minecraft:stone", 0, "minecraft:stone", 0);
        eq(before + 1, sample.visibleCells(), "real large document edits by coordinate");
        sample.undo(true);
        eq(before, sample.visibleCells(), "real large document undo restores geometry");
        sample.undo(false);
        HologramBlueprintArtifact.Artifact rendered = geometryPair(sample);
        HologramClientLibrary temporary = new HologramClientLibrary(scratch);
        HologramClientLibrary.DocumentSave saved = temporary
            .saveDocument(rendered.json, rendered.png, "", "", "", () -> true);
        HologramPrivateBlueprint reopened = new HologramPrivateBlueprint(temporary.privatePair(saved.id).json);
        eq(before + 1, reopened.visibleCells(), "large copy reopens with the edited geometry");
        eq(sample.author(), reopened.author(), "large copy retains original author");
        HologramBlueprintArtifact.Artifact untouched = HologramBlueprintArtifact.readPair(largest);
        eq(original.jsonSha256, untouched.jsonSha256, "sample JSON stays byte-exact");
        eq(original.pngSha256, untouched.pngSha256, "sample PNG stays byte-exact");
        System.out.println("Read-only sample round trip passed for " + before + " visible cells (" + size + " bytes)");
    }

    private static HologramBlueprintArtifact.Artifact geometryPair(HologramPrivateBlueprint document)
        throws IOException {
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        that(ImageIO.write(document.thumbnail(.75, .65), "png", pixels), "geometry preview is PNG-encodable");
        return HologramBlueprintArtifact.prepareSave(document.saveBytes(), pixels.toByteArray());
    }

    private static byte[] legacyJson() throws IOException {
        NBTTagCompound receipt = new NBTTagCompound(), policy = new NBTTagCompound();
        NBTTagList stages = new NBTTagList();
        for (String stage : new String[] { "preset", "identity", "quantum", "coordinates", "nbt", "hints" })
            stages.appendTag(new NBTTagString(stage));
        policy.setTag("confirmedStages", stages);
        receipt.setTag("policy", policy);
        receipt.setBoolean("complete", true);
        receipt.setString("forkReceipt", "kept");
        String encoded = Base64.getEncoder()
            .encodeToString(CompressedStreamTools.compress(receipt));
        return ("{\"schema\":2,\"unknownEnvelope\":{\"kept\":[1,null]},\"blueprint\":{"
            + "\"schema\":2,\"name\":\"Portable blueprint\",\"authorName\":\"Original builder\","
            + "\"unknownBlueprint\":{\"hidden\":true},\"semanticNbt\":\"opaque binary text\","
            + "\"fullyVoucherCovered\":true,\"processingNbt\":\""
            + encoded
            + "\",\"cells\":["
            + "{\"x\":0,\"y\":0,\"z\":0,\"block\":\"missing:block\",\"tileNbt\":\"not-known-nbt\"},"
            + "{\"x\":1,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"tileNbt\":\"old\","
            + "\"forkCellInfo\":\"Retained field\"}]}}").getBytes(StandardCharsets.UTF_8);
    }

    private static final class ReadbackFailure implements HologramClientLibrary.PairIo {

        private boolean replacing;

        @Override
        public HologramBlueprintArtifact.PairWrite write(Path path, byte[] json, byte[] png, BooleanSupplier current)
            throws IOException {
            HologramBlueprintArtifact.PairWrite write = HologramBlueprintArtifact
                .writePairPending(path, json, png, current);
            replacing = true;
            return write;
        }

        @Override
        public HologramBlueprintArtifact.Artifact read(Path path) throws IOException {
            if (replacing) throw new IOException("injected readback failure");
            return HologramBlueprintArtifact.readPair(path);
        }
    }
}
