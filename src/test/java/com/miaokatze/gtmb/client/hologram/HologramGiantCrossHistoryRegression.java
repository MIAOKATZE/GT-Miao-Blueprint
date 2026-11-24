// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/** Headless domain/interleaving and disk-verifier tests, not substitutes for the native probe. */
public final class HologramGiantCrossHistoryRegression {

    private static final String ID = "local_00000000-0000-0000-0000-000000000017";
    private static final String HEADER = "gtmb first write";

    private HologramGiantCrossHistoryRegression() {}

    public static void run() throws Exception {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setByte("nativePayloadFixture", (byte) 7);
        String rawNbt = Base64.getEncoder()
            .encodeToString(HologramSnapshotCodec.compress(nbt));
        byte[] input = fixture(rawNbt, "", "", 0, false);
        var original = HologramBlueprintCompactCodec.displayGeometry(input);
        var delta = HologramGiantCellDelta.select(original, 1, 0);
        check(delta != null && Arrays.equals(delta.targetXyz, new int[] { 1, 3, -5 }), "source-backed coordinate");
        check(HologramGiantCellDelta.select(original, 1, 1) == null, "target cannot be its own neighbor");
        check(HologramGiantCellDelta.select(original, 0, 2) == null, "non-adjacent neighbor refused");
        check(HologramGiantCellDelta.select(original, 1, 3) == null, "out of fixture neighbor refused");
        delta.verify(
            original,
            HologramBlueprintCompactCodec
                .displayGeometry(fixture(rawNbt, HologramGiantCellDelta.PROMPT, "", 1, false)));
        rejected(delta, original, fixture(rawNbt, HologramGiantCellDelta.PROMPT, "", 2, false), "wrong target");
        rejected(
            delta,
            original,
            fixture(rawNbt, HologramGiantCellDelta.PROMPT, HologramGiantCellDelta.PROMPT, 1, false),
            "neighbor changed");
        rejected(delta, original, fixture(rawNbt, HologramGiantCellDelta.PROMPT, "", 1, true), "native payload lost");

        var packed = HologramLocalBlueprintDocument.openForEditor(input, ID, () -> true, view -> {}, true)
            .document();
        var eager = HologramLocalBlueprintDocument.open(input, ID);
        check(packed.packedAuthoring() && !packed.dirty() && !eager.packedAuthoring(), "exact initial storage states");
        String[] actions = { "editBlueprintPrompt", "editCellFlags", "editUndo", "editUndo", "editRedo", "editRedo" };
        for (int i = 0; i < actions.length; i++) {
            NBTTagCompound request = new NBTTagCompound();
            request.setLong("planRevision", i);
            if (i == 0) request.setString("prompt", HEADER);
            if (i == 1) {
                request.setString("prompt", HologramGiantCellDelta.PROMPT);
                request.setIntArray("indices", new int[] { 1 });
                try {
                    packed.apply(actions[i], request);
                    throw new AssertionError("selected cell bypassed owner conversion");
                } catch (IllegalStateException expected) {}
                check(
                    packed.packedAuthoring() && packed.revision() == 1 && packed.dirty(),
                    "rejected bypass leaves header");
                var converted = packed.preparePackedMaterialization(1, () -> true);
                packed.acceptPackedMaterialization(converted, 1, () -> true);
                check(
                    !packed.packedAuthoring() && packed.revision() == 1 && packed.dirty(),
                    "conversion is not history");
            }
            packed.apply(actions[i], request);
            eager.apply(actions[i], request);
            check(
                packed.packedAuthoring() == (i == 0) && packed.revision() == i + 1 && packed.dirty() == (i != 3),
                "exact revision/storage/dirty at " + i);
            if (i > 0) {
                byte[] exact = packed.artifact();
                check(Arrays.equals(eager.artifact(), exact), "cross-history equals ordinary emission at " + i);
                var actual = HologramBlueprintCompactCodec.displayGeometry(exact);
                boolean cellEdited = i == 1 || i == 5;
                if (cellEdited) delta.verify(original, actual);
                else for (int row : new int[] { delta.target, delta.neighbor }) HologramGiantCellDelta.sameMaterial(
                    original.material(original.materialIndex(row)),
                    actual.material(actual.materialIndex(row)),
                    row,
                    false);
                check(
                    (i == 3 ? "" : HEADER).equals(
                        packed.snapshotMetadata()
                            .getString("blueprintPrompt")),
                    "header restoration at " + i);
            }
        }
        NBTTagCompound save = new NBTTagCompound();
        byte[] staged = packed.prepareSave(save, "test", new UUID(0, 12), ID);
        check(Arrays.equals(staged, eager.prepareSave(save, "test", new UUID(0, 12), ID)), "same frozen save as eager");
        delta.verify(original, HologramBlueprintCompactCodec.displayGeometry(staged));
        System.out.println(
            "GTMB cross-materialization history contract PASS: owner conversion, 6 revisions, target and native fields");
        certifiedHistory(rawNbt);
        System.out.println(
            "GTMB certified history delta PASS: inverse identity, bounded publication, fallback and unchanged artifact");
    }

    private static void certifiedHistory(String rawNbt) throws Exception {
        String id = ID;
        byte[] input = ("{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{\"schema\":2,\"id\":\"" + id
            + "\",\"name\":\"certified history\",\"createdAt\":1234,\"cells\":["
            + "[0,{\"x\":0,\"y\":3,\"z\":-5}],[1,{\"x\":1,\"y\":3,\"z\":-5}]]},"
            + "\"materials\":[{\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"tileNbt\":0,\"itemNbt\":0},"
            + "{\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"opaqueRow\":19}],\"nbt\":[\""
            + rawNbt
            + "\"]}").getBytes(StandardCharsets.UTF_8);
        check(
            HologramLocalBlueprintWorkspace.boundedSinglePromptView(125_000, 231)
                && !HologramLocalBlueprintWorkspace.boundedSinglePromptView(512 * 256 + 1, 231)
                && !HologramLocalBlueprintWorkspace.boundedSinglePromptView(125_000, 257),
            "real giant fixture fits bounded page and region copies, dense/sparse overflow falls back");
        var document = HologramLocalBlueprintDocument.openForEditor(input, id, () -> true, view -> {}, true)
            .document();
        var expected = HologramLocalBlueprintDocument.open(input, id);
        check(document.packedAuthoring(), "header is first authored on the packed root");
        var header = new NBTTagCompound();
        header.setString("prompt", HEADER);
        document.apply("editBlueprintPrompt", header);
        expected.apply("editBlueprintPrompt", header);
        var materialized = document.preparePackedMaterialization(1, () -> true);
        document.acceptPackedMaterialization(materialized, 1, () -> true);
        var note = new NBTTagCompound();
        note.setIntArray("indices", new int[] { 1 });
        note.setString("prompt", "new note");
        note.setBoolean("excluded", false);
        document.apply("editCellFlags", note);
        expected.apply("editCellFlags", note);
        var preserved = expected.artifact(); // independent no-history path
        HologramState view = historyView(document);
        var neighbor = view.cells.get(0);
        for (int i = 0; i < 4; i++) {
            long previous = document.revision();
            boolean headerEdge = i == 1 || i == 2;
            NBTTagCompound request = new NBTTagCompound();
            request.setLong("planRevision", previous);
            String op = i < 2 ? "editUndo" : "editRedo";
            if (i == 0) {
                try {
                    document.apply(op, request, () -> false);
                    throw new AssertionError("lost history owner was accepted");
                } catch (IOException expectedCancellation) {}
                check(
                    document.revision() == previous && document.historyDelta(previous) == null,
                    "lost owner did not advance history");
            }
            document.apply(op, request);
            var delta = document.historyDelta(previous);
            check(
                delta != null && delta.header() == headerEdge
                    && delta.revision() == previous + 1
                    && document.historyDelta(previous - 1) == null,
                "certified direction and exact revision for edge " + i);
            var counterfeited = new HologramLocalBlueprintDocument.HistoryDelta(
                delta.fromRevision(),
                delta.revision(),
                delta.cellCount(),
                delta.index(),
                delta.x(),
                delta.y(),
                delta.z(),
                delta.expectedPrompt(),
                delta.prompt(),
                delta.block(),
                delta.metadata(),
                delta.facing(),
                delta.excluded());
            check(
                !HologramLocalBlueprintWorkspace.hasHistoryCertificate(delta, counterfeited)
                    && HologramLocalBlueprintWorkspace.hasHistoryCertificate(delta, delta),
                "forged equal certificate cannot publish");
            var metadata = document.snapshotMetadata();
            var generation = (NBTTagCompound) metadata.copy();
            generation.setString("generation", "unrelated");
            check(
                HologramLocalBlueprintWorkspace.certifiedHistoryView(view, generation, delta) == null,
                "foreign publication generation");
            var topology = (NBTTagCompound) metadata.copy();
            topology.setBoolean("topology", true);
            check(
                HologramLocalBlueprintWorkspace.certifiedHistoryView(view, topology, delta) == null,
                "never certify a changed shape");
            if (!headerEdge) {
                var wrong = new HologramLocalBlueprintDocument.HistoryDelta(
                    delta.fromRevision(),
                    delta.revision(),
                    delta.cellCount(),
                    delta.index(),
                    delta.x() + 1,
                    delta.y(),
                    delta.z(),
                    delta.expectedPrompt(),
                    delta.prompt(),
                    delta.block(),
                    delta.metadata(),
                    delta.facing(),
                    delta.excluded());
                check(
                    HologramLocalBlueprintWorkspace.certifiedHistoryView(view, (NBTTagCompound) metadata.copy(), wrong)
                        == null,
                    "forged annotation coordinate rejected");
            }
            HologramState next = HologramLocalBlueprintWorkspace.certifiedHistoryView(view, metadata, delta);
            var full = historyView(document);
            check(
                next != null && next.follows(view)
                    && next.parsedCells == (headerEdge ? 0 : 1)
                    && (next.cells == view.cells) == headerEdge
                    && next.cells.get(0) == neighbor
                    && next.regions.at(1, 3, -5) == next.cells.get(1)
                    && next.cells.get(1)
                        .tag()
                        .equals(
                            full.cells.get(1)
                                .tag())
                    && next.cells.get(0)
                        .tag()
                        .equals(
                            full.cells.get(0)
                                .tag())
                    && next.data.getString("blueprintPrompt")
                        .equals(full.data.getString("blueprintPrompt"))
                    && document.dirty() == (i != 1),
                "delta parity with authoritative full view for edge " + i);
            check(
                HologramLocalBlueprintWorkspace.certifiedHistoryView(next, metadata, delta) == null,
                "a published edge cannot be reapplied for a new revision");
            view = next;
        }
        var frozen = document.freeze();
        var otherBranch = new NBTTagCompound();
        otherBranch.setIntArray("indices", new int[] { 1 });
        otherBranch.setBoolean("excluded", true);
        document.apply("editCellFlags", otherBranch);
        check(
            Arrays.equals(preserved, frozen.artifact()),
            "later COW mutation does not affect retained rows, positions or NBT");
        long branch = document.revision();
        document.apply("editUndo", new NBTTagCompound());
        check(
            document.historyDelta(branch) == null && Arrays.equals(preserved, document.artifact()),
            "exclusion changes travel through full fallback and recover canonical artifact");
        document.apply("editBlueprintPrompt", new NBTTagCompound());
        check(
            !document.snapshotMetadata()
                .getBoolean("editCanRedo"),
            "new branch retires the old inverse");

        byte[] saved = document.prepareSave(new NBTTagCompound(), "test", new UUID(0, 12), id);
        document.saved(document.revision(), saved);
        long savedRevision = document.revision();
        check(!document.dirty(), "commit stores the same clean token");
        document.apply("editUndo", new NBTTagCompound());
        check(
            document.historyDelta(savedRevision) == null && document.dirty(),
            "a save fence retires certificates but preserves correct dirty/full Undo");
    }

    private static HologramState historyView(HologramLocalBlueprintDocument document) throws IOException {
        var preparation = new HologramState.Preparation(document.snapshot(), true);
        while (!preparation.step(5, Long.MAX_VALUE)) {}
        return preparation.result();
    }

    private static void rejected(HologramGiantCellDelta delta, HologramBlueprintCompactCodec.DisplayRows original,
        byte[] candidate, String label) throws IOException {
        var parsed = HologramBlueprintCompactCodec.displayGeometry(candidate);
        try {
            delta.verify(original, parsed);
            throw new AssertionError(label);
        } catch (IOException expected) {}
    }

    private static byte[] fixture(String nbt, String prompt, String neighborPrompt, int edited, boolean lostNative) {
        String material = "{\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"opaqueRow\":17";
        String kept = material + ",\"prompt\":\"" + neighborPrompt + "\",\"tileNbt\":0,\"itemNbt\":0}";
        String changed = material + ",\"prompt\":\""
            + prompt
            + "\""
            + (lostNative ? "" : ",\"tileNbt\":0,\"itemNbt\":0")
            + "}";
        return ("{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{\"schema\":2,\"id\":\"" + ID
            + "\",\"name\":\"cross history\",\"createdAt\":1234,\"cells\":["
            + "[0,{\"x\":0,\"y\":3,\"z\":-5}],["
            + (edited == 1 ? 1 : 0)
            + ",{\"x\":1,\"y\":3,\"z\":-5}],["
            + (edited == 2 ? 1 : 0)
            + ",{\"x\":2,\"y\":3,\"z\":-5}]]},\"materials\":["
            + kept
            + (edited == 0 ? "" : "," + changed)
            + "],\"nbt\":[\""
            + nbt
            + "\"]}").getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}
