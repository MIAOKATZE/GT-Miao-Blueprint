// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramLocalPresentationRegression {

    private static final String ID = "local_11111111-1111-1111-1111-111111111111";

    private HologramLocalPresentationRegression() {}

    public static void run() {
        try {
            NBTTagCompound item = new NBTTagCompound();
            item.setIntArray("fixture", new int[] { 17, -9, 7 });
            String nbt = java.util.Base64.getEncoder()
                .encodeToString(CompressedStreamTools.compress(item));
            byte[] raw = ("{\"schema\":2,\"blueprint\":{\"schema\":2,\"name\":\"Resume fixture\",\"cells\":["
                + "{\"x\":-6,\"y\":0,\"z\":2,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\","
                + "\"excluded\":true,\"controller\":true,\"structureId\":\"s\",\"prompt\":\"stay\",\"itemNbt\":\""
                + nbt
                + "\"},"
                + "{\"x\":-5,\"y\":0,\"z\":2,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\","
                + "\"excluded\":true,\"controller\":true,\"structureId\":\"s\",\"prompt\":\"stay\",\"itemNbt\":\""
                + nbt
                + "\"},"
                + "{\"x\":-4,\"y\":0,\"z\":2,\"block\":\"nonexistent:fixture\",\"item\":\"nonexistent:fixture\"}],"
                + "\"palette\":[],\"structures\":[{\"id\":\"s\",\"name\":\"Structure\"}],"
                + "\"hintAreas\":[{\"x1\":-6,\"x2\":-5,\"prompt\":\"hint\"}]}}").getBytes(StandardCharsets.UTF_8);
            byte[] stored = HologramBlueprintCompactCodec.recompress(raw, HologramBlueprintArtifact.MAX_JSON_BYTES);
            for (boolean retainPacked : new boolean[] { false, true }) {
                var opened = HologramLocalBlueprintDocument.openStoredForEditor(
                    stored,
                    ID,
                    () -> true,
                    view -> {},
                    retainPacked,
                    HologramLocalBlueprintDocument.LocalEditorOrigin.TRACKED_PRIVATE);
                var doc = opened.document();
                SimpleAssert.eq(retainPacked, doc.packedAuthoring(), "two actual draft storage representations");
                NBTTagCompound dirty = new NBTTagCompound();
                dirty.setString("prompt", "dirty headline");
                doc.apply("editBlueprintPrompt", dirty);
                SimpleAssert.that(doc.dirty(), "the retained draft has a meaningful unsaved local edit");
                var exact = doc.resumePresentationMetadata(() -> true);
                exact.setInteger("dimension", 7);
                exact.setTag("textPlacementAck", new NBTTagCompound());
                exact.setTag("discardTargetAction", new NBTTagCompound());
                exact.setBoolean("foreignRendererFlag", true);
                HologramBlueprintPreview.CompactCells cells;
                if (retainPacked) cells = new HologramBlueprintPreview.CompactCells(doc.packedEditorRows(() -> true));
                else {
                    var rows = doc.detachedEditorRows(() -> true);
                    cells = new HologramBlueprintPreview.CompactCells(rows);
                    SimpleAssert.eq(
                        2,
                        rows.materials().length,
                        "ordinary compact rebuild deduplicates exact repeated native descriptors");
                    SimpleAssert
                        .eq(rows.coordinates()[3], rows.coordinates()[7], "same descriptor reused without reordering");
                    SimpleAssert.that(
                        rows.materials()[0].hasKey("previewItemTag", 10) && rows.materials()[0].getBoolean("excluded")
                            && rows.materials()[0].getBoolean("anchor")
                            && rows.materials()[1].getBoolean("missingBlock"),
                        "native payload, excluded anchor and missing cell survive");
                    try {
                        doc.detachedEditorRows(() -> false);
                        throw new AssertionError("cancelled draft should not rebuild");
                    } catch (IOException accepted) {}
                }
                var prep = new HologramState.Preparation(exact, true, cells);
                while (!prep.step(512, Long.MAX_VALUE)) {}
                var previous = prep.result();
                Object world = new Object(), connection = new Object(), player = new Object();
                var seed = HologramLocalPresentation.capture(doc, previous, world, connection, player, 7, 3, true);
                SimpleAssert.that(
                    seed != null && seed.current(doc, world, connection, player, 7, 3),
                    "same exact owner accepts");
                SimpleAssert.that(
                    !seed.current(doc, new Object(), connection, player, 7, 3)
                        && !seed.current(doc, world, new Object(), player, 7, 3)
                        && !seed.current(doc, world, connection, new Object(), 7, 3)
                        && !seed.current(doc, world, connection, player, 8, 3)
                        && !seed.current(doc, world, connection, player, 7, 4),
                    "world, connection, actor, dimension and resources fence reuse");
                for (String flag : new String[] { "displayPartial", "attachmentPending", "discardConfirmationRequired",
                    "virtualPlacement" }) {
                    NBTTagCompound transientState = new NBTTagCompound();
                    transientState.setBoolean(flag, true);
                    SimpleAssert.eq(
                        null,
                        HologramLocalPresentation
                            .capture(doc, previous.withMetadata(transientState), world, connection, player, 7, 3, true),
                        "no resume of transient state " + flag);
                }
                SimpleAssert.eq(
                    null,
                    HologramLocalPresentation.capture(doc, previous, world, connection, player, 7, 3, false),
                    "no resume while original view work is pending");
                var restored = seed.rebase(doc.resumePresentationMetadata(() -> true));
                SimpleAssert.that(
                    restored != null && restored.identity != previous.identity
                        && restored.parentIdentity == null
                        && restored.cells == previous.cells
                        && restored.regions == previous.regions
                        && restored.parsedCells == 0
                        && restored.changedIndices.length == 0,
                    "fresh presentation identity uses accepted geometry without reconstructing a cell");
                SimpleAssert.that(
                    !restored.data.hasKey("textPlacementAck") && !restored.data.hasKey("discardTargetAction")
                        && !restored.data.hasKey("foreignRendererFlag"),
                    "old metadata is not copied");
                SimpleAssert.eq(ID, restored.data.getString("selectedPresetId"), "saved slot selection retained");
                SimpleAssert
                    .eq("dirty headline", restored.data.getString("blueprintPrompt"), "exact in-memory edit retained");
                var visual = restored.cells.get(0);
                SimpleAssert.that(
                    restored.data.getBoolean("blueprintDirty") && restored.data.getBoolean("editCanUndo")
                        && doc.dirty()
                        && visual == previous.cells.get(0)
                        && visual.anchor
                        && visual.excluded
                        && visual.belongsToStructure("s")
                        && "stay".equals(visual.prompt)
                        && visual.targetStack != null
                        && item.equals(visual.targetStack.getTagCompound()),
                    "unsaved history and exact native visual payload stay with the same draft");
                doc.apply("editUndo", new NBTTagCompound());
                SimpleAssert.that(
                    !doc.dirty() && !seed.current(doc, world, connection, player, 7, 3)
                        && seed.rebase(doc.resumePresentationMetadata(() -> true)) == null,
                    "changed revision invalidates cached geometry");
            }
            opaqueAndDistinctDescriptors();
        } catch (IOException failed) {
            throw new AssertionError("Local editor presentation reopen regression", failed);
        }
    }

    private static void opaqueAndDistinctDescriptors() throws IOException {
        NBTTagCompound positive = new NBTTagCompound(), negative = new NBTTagCompound();
        positive.setFloat("zero", 0.0F);
        negative.setFloat("zero", -0.0F);
        String plus = java.util.Base64.getEncoder()
            .encodeToString(CompressedStreamTools.compress(positive));
        String minus = java.util.Base64.getEncoder()
            .encodeToString(CompressedStreamTools.compress(negative));
        StringBuilder raw = new StringBuilder("{\"blueprint\":{\"cells\":[");
        for (int x = 0; x < 70; x++) {
            if (x != 0) raw.append(',');
            raw.append("{\"x\":")
                .append(x)
                .append(",\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",")
                .append("\"prompt\":\"")
                .append(x < 2 ? "same" : "unique " + x)
                .append("\",\"itemNbt\":\"")
                .append(x == 1 ? minus : plus)
                .append("\"}");
        }
        var doc = HologramLocalBlueprintDocument.open(
            raw.append("]}}")
                .toString()
                .getBytes(StandardCharsets.UTF_8),
            ID);
        var rows = doc.detachedEditorRows(() -> true);
        SimpleAssert.eq(70, rows.materials().length, "higher-cardinality views remain fully representable");
        SimpleAssert.eq(
            Float.floatToRawIntBits(0.0F),
            Float.floatToRawIntBits(
                rows.materials()[0].getCompoundTag("previewItemTag")
                    .getFloat("zero")),
            "positive zero payload survives modern material sharing");
        SimpleAssert.eq(
            Float.floatToRawIntBits(-0.0F),
            Float.floatToRawIntBits(
                rows.materials()[1].getCompoundTag("previewItemTag")
                    .getFloat("zero")),
            "negative zero is not merged by NBT equality");
        var prep = new HologramState.Preparation(
            rows.metadata(),
            true,
            new HologramBlueprintPreview.CompactCells(rows));
        SimpleAssert
            .that(prep.usesEditorCells() && !prep.canPrepareCompactIndex(), "ordinary cooperative compact path");
        while (!prep.step(5, Long.MAX_VALUE)) {}
        SimpleAssert
            .eq(70, prep.result().cells.size(), "every editable ordinal survives higher descriptor cardinality");
    }

}
