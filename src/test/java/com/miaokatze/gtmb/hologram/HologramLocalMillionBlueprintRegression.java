// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Explicit large-file admission/save probe using the same ordinary local editor entry as the gallery. */
public final class HologramLocalMillionBlueprintRegression {

    private HologramLocalMillionBlueprintRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLocalMillionBlueprintRegression.class, args)) return;
        if (args.length != 2) throw new IllegalArgumentException("ordinary JSON path and expected cell count required");
        byte[] input = Files.readAllBytes(Paths.get(args[0]));
        int expected = Integer.parseInt(args[1]);
        if (expected <= HologramBlueprintLimits.MAX_CELLS) throw new AssertionError("probe must exceed old cell cap");
        if (input.length <= HologramBlueprintArtifact.MAX_JSON_BYTES)
            throw new AssertionError("probe must exceed old byte cap");
        if (!HologramBlueprintLimits.acceptsLocal(HologramBlueprintLimits.LOCAL_CELLS)
            || HologramBlueprintLimits.acceptsLocal((long) HologramBlueprintLimits.LOCAL_CELLS + 1)
            || HologramBlueprintLimits.acceptsLocal(-1)
            || HologramBlueprintLimits.acceptsLocal(Long.MAX_VALUE))
            throw new AssertionError("local cell limit has an off-by-one or overflow");
        AtomicInteger callbacks = new AtomicInteger();
        long start = System.nanoTime();
        var opened = HologramLocalBlueprintDocument.openStoredForEditor(
            input,
            "",
            () -> true,
            editor -> callbacks.incrementAndGet(),
            true,
            HologramLocalBlueprintDocument.LocalEditorOrigin.NAMED_IMPORT);
        var document = opened.document();
        if (callbacks.get() != 1 || opened.editor() == null || !document.packedAuthoring())
            throw new AssertionError("ordinary editor failed to publish and retain compact rows");
        var rows = opened.editor()
            .rows();
        if (rows.cells() != expected || document.freeze()
            .cellCount() != expected) throw new AssertionError("ordinary editor dropped cells");
        long originalFingerprint = fingerprint(rows);
        long openMs = (System.nanoTime() - start) / 1_000_000;
        if (!Arrays.equals(input, document.artifact())) throw new AssertionError("unchanged source bytes changed");
        start = System.nanoTime();
        NBTTagCompound prompt = new NBTTagCompound();
        prompt.setString("prompt", "ordinary-million-roundtrip");
        document.apply("editBlueprintPrompt", prompt);
        if (!document.packedAuthoring()) throw new AssertionError("header edit materialized every cell");
        byte[] saved = document.prepareSave("DOT-five ordinary local roundtrip", "DOT", new UUID(0, 1), "");
        long saveMs = (System.nanoTime() - start) / 1_000_000;
        if (!document.packedAuthoring()) throw new AssertionError("ordinary save materialized every cell");
        start = System.nanoTime();
        var reloaded = HologramLocalBlueprintDocument.openStoredForEditor(
            saved,
            "",
            () -> true,
            editor -> {},
            true,
            HologramLocalBlueprintDocument.LocalEditorOrigin.NAMED_IMPORT);
        if (!reloaded.document()
            .packedAuthoring() || reloaded.editor() == null
            || reloaded.editor()
                .rows()
                .cells() != expected
            || fingerprint(
                reloaded.editor()
                    .rows())
                != originalFingerprint)
            throw new AssertionError("ordinary save/reopen changed coordinates or material references");
        if (!"ordinary-million-roundtrip".equals(
            reloaded.editor()
                .metadata()
                .getString("blueprintPrompt")))
            throw new AssertionError("ordinary save/reopen lost edited header");
        long reopenMs = (System.nanoTime() - start) / 1_000_000;
        if (HologramBlueprintLimits.accepts(expected)) throw new AssertionError("legacy network cap was widened");
        try {
            HologramBlueprintCompactCodec.parse(input);
            throw new AssertionError("wire/default parser unexpectedly admitted oversized file");
        } catch (java.io.IOException expectedFailure) {}
        System.out.println(
            "PASS ordinary-local-blueprint cells=" + expected
                + " inputBytes="
                + input.length
                + " savedBytes="
                + saved.length
                + " openMs="
                + openMs
                + " saveMs="
                + saveMs
                + " reopenMs="
                + reopenMs
                + " fullCoordinateMaterialFingerprint="
                + Long.toUnsignedString(originalFingerprint)
                + " noGame=true noNetwork=true");
    }

    private static long fingerprint(HologramBlueprintCompactCodec.DisplayRows rows) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < rows.materials(); i++) {
            String material = rows.material(i)
                .toString();
            for (int j = 0; j < material.length(); j++) hash = (hash ^ material.charAt(j)) * 0x100000001b3L;
        }
        for (int i = 0; i < rows.cells(); i++) {
            hash = (hash ^ rows.coordinate(i, 0)) * 0x100000001b3L;
            hash = (hash ^ rows.coordinate(i, 1)) * 0x100000001b3L;
            hash = (hash ^ rows.coordinate(i, 2)) * 0x100000001b3L;
            hash = (hash ^ rows.materialIndex(i)) * 0x100000001b3L;
        }
        return hash;
    }
}
