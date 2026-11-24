// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.client.hologram.HologramPrivateEditPerformance.Preset;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.hologram.HologramSnapshotPalette;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Config I/O and the actual detached local editor/snapshot routes, with no client or renderer startup. */
public final class HologramPrivateEditPerformanceRegression {

    private static final String ID = "local_11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "local_22222222-2222-2222-2222-222222222222";

    private HologramPrivateEditPerformanceRegression() {}

    public static void run() {
        try {
            config();
            ownedRoute();
        } catch (Exception failure) {
            throw new IllegalStateException("Client private Edit performance regression failed", failure);
        }
        System.out.println(
            "GTMB client private Edit performance PASS: startup-configured presets and exact natural ordinary scope");
    }

    private static void config() throws Exception {
        Path game = Files.createTempDirectory("gtmb-private-edit-performance-");
        Path config = game.resolve("config");
        Path directory = config.resolve("gtmb");
        Path file = directory.resolve("client-blueprint-performance.properties");
        try {
            SimpleAssert.eq(Preset.REGULAR, HologramPrivateEditPerformance.load(game), "absent configuration");
            SimpleAssert.that(!Files.exists(config), "absent configuration read never writes");
            Files.createDirectories(directory);
            for (String value : new String[] { "", "stock", "other", "FAST", "51200/10", "high-10-batch", "51200/20",
                "\\u0z00" }) {
                byte[] bytes = ("ordinaryOpenPreset=" + value + "\n").getBytes(StandardCharsets.US_ASCII);
                Files.write(file, bytes);
                SimpleAssert.eq(Preset.REGULAR, HologramPrivateEditPerformance.load(game), "invalid or unknown mode");
                SimpleAssert
                    .that(Arrays.equals(bytes, Files.readAllBytes(file)), "read never repairs invalid configuration");
            }
            byte[] malformed = "ordinaryOpenPreset=fast\nunknown=\\u0z00\n".getBytes(StandardCharsets.US_ASCII);
            Files.write(file, malformed);
            SimpleAssert
                .eq(Preset.REGULAR, HologramPrivateEditPerformance.load(game), "partial parse cannot activate Fast");
            SimpleAssert.that(Arrays.equals(malformed, Files.readAllBytes(file)), "read never repairs malformed bytes");
            Files.write(file, "ordinaryOpenPreset=fast\n".getBytes(StandardCharsets.US_ASCII));
            Preset firstStartup = HologramPrivateEditPerformance.load(game);
            SimpleAssert.eq(Preset.FAST, firstStartup, "explicit opt-in");
            var accepted = HologramPrivateEditPerformance.capture(ID, firstStartup, true);
            Files.write(file, "ordinaryOpenPreset=regular\n".getBytes(StandardCharsets.US_ASCII));
            Preset nextStartup = HologramPrivateEditPerformance.load(game);
            SimpleAssert.eq(Preset.REGULAR, nextStartup, "explicit regular");
            var later = HologramPrivateEditPerformance.capture(ID, nextStartup, true);
            var opened = HologramLocalBlueprintDocument.openForEditor(fixture(), ID, () -> true);
            var rows = opened.document()
                .detachedEditorRows(() -> true);
            NBTTagCompound a = rows.metadata(), b = rows.metadata();
            var first = new HologramState.Preparation(a, true, new HologramBlueprintPreview.CompactCells(rows));
            var next = new HologramState.Preparation(b, true, new HologramBlueprintPreview.CompactCells(rows));
            SimpleAssert.eq(
                Preset.FAST,
                accepted.bind(opened.document(), true, a, first, true)
                    .forPreparation(first, true),
                "accepted task is immutable across preference change");
            SimpleAssert.eq(
                Preset.REGULAR,
                later.bind(opened.document(), true, b, next, true)
                    .forPreparation(next, true),
                "next startup setting does not replace already accepted intent");
            byte[] oversized = new byte[4097];
            byte[] prefix = "ordinaryOpenPreset=fast\n".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(prefix, 0, oversized, 0, prefix.length);
            Files.write(file, oversized);
            SimpleAssert.eq(Preset.REGULAR, HologramPrivateEditPerformance.load(game), "oversized read declines");
            Files.delete(file);
            Files.createDirectory(file);
            SimpleAssert.eq(Preset.REGULAR, HologramPrivateEditPerformance.load(game), "nonfile is not a preference");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
            Files.deleteIfExists(config);
            Files.deleteIfExists(game);
        }
    }

    private static void ownedRoute() throws Exception {
        for (String ineligible : new String[] { null, "", "legacy:slot", "public_11111111-1111-1111-1111-111111111111",
            "local_bad" }) {
            SimpleAssert.eq(
                null,
                HologramPrivateEditPerformance.capture(ineligible, Preset.FAST, true),
                "no other route may request the managed private Edit preset");
        }
        SimpleAssert.eq(null, HologramPrivateEditPerformance.capture(ID, Preset.FAST, false), "no foreign screen");
        byte[] bytes = fixture();
        var open = HologramLocalBlueprintDocument.openForEditor(bytes, ID, () -> true);
        var doc = open.document();
        var rows = doc.detachedEditorRows(() -> true);
        NBTTagCompound base = rows.metadata();
        var prep = new HologramState.Preparation(base, true, new HologramBlueprintPreview.CompactCells(rows));
        var fast = HologramPrivateEditPerformance.capture(ID, Preset.FAST, true);
        SimpleAssert.that(open.editor() == null && !doc.packedAuthoring(), "natural expanded ordinary route");
        var selected = fast.bind(doc, true, base, prep, true);
        SimpleAssert.eq(null, fast.bind(doc, true, base, prep, false), "lost initial source owner");
        SimpleAssert.eq(
            null,
            selected.forPreparation(new HologramState.Preparation(doc.snapshot(), true), true),
            "a bound mode cannot migrate to a different preparation");
        SimpleAssert.eq(null, selected.forPreparation(prep, false), "lost active source graph uses existing fallback");
        SimpleAssert.eq(
            null,
            HologramPrivateEditPerformance.capture(OTHER, Preset.FAST, true)
                .bind(doc, true, base, prep, true),
            "no substitute document can use another admitted preference");
        SimpleAssert.eq(51_200, selected.forPreparation(prep, true).workCap, "Fast cap");
        SimpleAssert.eq(10_000_000L, selected.forPreparation(prep, true).softNanos, "Fast cooperative time");
        SimpleAssert.eq(5120, Preset.REGULAR.workCap, "Regular cap");
        SimpleAssert.eq(5_000_000L, Preset.REGULAR.softNanos, "Regular cooperative time");

        byte[] compact = HologramBlueprintCompactCodec.recompress(bytes, HologramBlueprintArtifact.MAX_JSON_BYTES);
        var compactOpen = HologramLocalBlueprintDocument.openForEditor(compact, ID, () -> true, view -> {}, false);
        SimpleAssert.that(
            compactOpen.editor() != null && !compactOpen.document()
                .packedAuthoring(),
            "naturally compact viewport does not depend on retained packed-authoring flag");
        SimpleAssert.eq(
            null,
            fast.bind(compactOpen.document(), false, base, prep, true),
            "compact viewport always keeps the existing preparation");
        var retained = HologramLocalBlueprintDocument.openForEditor(compact, ID, () -> true, view -> {}, true);
        SimpleAssert.that(
            retained.document()
                .packedAuthoring(),
            "managed compact fixture retains its native packed document");
        SimpleAssert.eq(
            null,
            fast.bind(retained.document(), true, base, prep, true),
            "packed authoring cannot select an ordinary preset");
        NBTTagCompound wire = HologramSnapshotCodec.decode(HologramSnapshotCodec.compressPacked(doc.snapshot()));
        SimpleAssert
            .that(HologramSnapshotPalette.isPacked(wire), "real packed wire provenance, not merely a tag spelling");
        SimpleAssert.eq(
            null,
            fast.bind(doc, true, wire, new HologramState.Preparation(wire, true), true),
            "packed snapshot cannot use an ordinary preset");
        for (String flag : new String[] { "structureProjection", "displayPartial", "partial" }) {
            NBTTagCompound other = doc.snapshot();
            other.setBoolean(flag, true);
            SimpleAssert.eq(
                null,
                fast.bind(doc, true, other, new HologramState.Preparation(other, true), true),
                "nonordinary snapshot uses existing fallback");
        }
        NBTTagCompound empty = doc.snapshot();
        empty.setTag("cells", new NBTTagList());
        SimpleAssert.eq(
            null,
            fast.bind(doc, true, empty, new HologramState.Preparation(empty, true), true),
            "no tuning for an empty or shortcut cursor");

        int[] largePositions = new int[32_768 * 4];
        for (int row = 0; row < 32_768; row++) largePositions[row * 4] = row;
        var largeRows = new HologramLocalBlueprintDocument.DetachedEditorRows(
            base,
            largePositions,
            rows.materials(),
            new int[] { 0, 0, 0, 32_767, 0, 0 });
        var shortcut = new HologramState.Preparation(base, true, new HologramBlueprintPreview.CompactCells(largeRows));
        SimpleAssert
            .that(shortcut.canPrepareCompactIndex(), "large low-cardinality materialized view uses detached index");
        SimpleAssert.eq(
            null,
            fast.bind(doc, true, base, shortcut, true),
            "Fast preference never pulls an eligible detached view back into cooperative client preparation");

        var legacy = new HologramState.Preparation(doc.snapshot(), true);
        var regular = new HologramState.Preparation(base, true, new HologramBlueprintPreview.CompactCells(rows));
        settle(legacy, 512, 2_000_000L);
        settle(regular, Preset.REGULAR.workCap, Preset.REGULAR.softNanos);
        settle(prep, selected.forPreparation(prep, true).workCap, selected.forPreparation(prep, true).softNanos);
        SimpleAssert.eq(
            regular.result()
                .fullData(),
            prep.result()
                .fullData(),
            "Fast/Regular retain the same modern typed view");
        var baseline = legacy.result();
        var modernView = prep.result();
        for (int i = 0; i < baseline.cells.size(); i++) {
            var old = baseline.cells.get(i);
            var modern = modernView.cells.get(i);
            SimpleAssert.eq(old.tag(), modern.tag(), "modern descriptor retains ordinary exposed row contents");
        }
        SimpleAssert.eq(null, fast.bind(doc, true, base, prep, true), "no retuning an already prepared cursor");
        SimpleAssert.that(
            Arrays.equals(bytes, doc.artifact()) && doc.revision() == 0 && !doc.dirty(),
            "preparation settings do not change saved source, revision or editability");
    }

    private static void settle(HologramState.Preparation prep, int cap, long nanos) {
        for (int i = 0; i < 256; i++) if (prep.step(cap, nanos)) return;
        throw new AssertionError("Tiny admitted fixture did not finish");
    }

    private static byte[] fixture() {
        var json = new StringBuilder("{\"schema\":2,\"blueprint\":{\"schema\":2,\"cells\":[");
        for (int x = 0; x < 64; x++) {
            if (x != 0) json.append(',');
            json.append("{\"x\":")
                .append(x)
                .append(",\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\"}");
        }
        return json.append("],\"palette\":[]}}")
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }
}
