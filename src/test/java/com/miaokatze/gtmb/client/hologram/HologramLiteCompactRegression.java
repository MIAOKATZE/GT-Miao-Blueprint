// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.IdentityHashMap;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Headless display preparation and geometry culling; the testlab profile supplies live GL evidence. */
public final class HologramLiteCompactRegression {

    private static final int CELLS = 123_369;

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLiteCompactRegression.class, args)) return;
        String path = args.length == 0 ? "" : args[0];
        byte[] bytes;
        if (path.isEmpty()) bytes = synthetic();
        else {
            Path source = Paths.get(path);
            if (Files.size(source) > HologramBlueprintArtifact.MAX_JSON_BYTES)
                throw new IOException("Read-only blueprint sample exceeds the JSON byte limit");
            bytes = Files.readAllBytes(source);
        }
        long start = System.nanoTime();
        int[] lastProgress = { 0, 0 };
        NBTTagCompound decoded = HologramBlueprintPreview.privateSnapshot(bytes, (done, total) -> {
            lastProgress[0] = done;
            lastProgress[1] = total;
        });
        long parsed = System.nanoTime();
        if (path.isEmpty()) {
            SimpleAssert
                .that(decoded instanceof HologramBlueprintPreview.PackedDisplay, "fixture is dictionary-encoded");
            SimpleAssert.that(!decoded.hasKey("cells"), "compact preview has no per-cell NBT tree");
        }
        SimpleAssert.eq(lastProgress[1], lastProgress[0], "worker parse publishes final source position");
        HologramState.Preparation preparation = new HologramState.Preparation(decoded, true);
        SimpleAssert.that(!preparation.step(256, Long.MAX_VALUE), "large display remains incomplete after a slice");
        HologramState first = preparation.firstDisplay();
        SimpleAssert.eq(256, first.cells.size(), "first view owns a bounded drawable prefix");
        SimpleAssert.that(first.data.getBoolean("displayPartial"), "first view does not claim complete geometry");
        long firstPrepared = System.nanoTime();
        while (!preparation.step(4096, Long.MAX_VALUE)) {}
        HologramState all = preparation.result();
        long allPrepared = System.nanoTime();
        SimpleAssert.eq(256, first.cells.size(), "completing display never mutates an already published view");
        SimpleAssert.that(first.cells.get(0) == all.cells.get(0), "first display and complete snapshot share cells");
        IdentityHashMap<Object, Boolean> descriptors = new IdentityHashMap<>();
        for (HologramState.Cell cell : all.cells) descriptors.put(cell.descriptorIdentity(), Boolean.TRUE);
        if (path.isEmpty()) {
            SimpleAssert.eq(CELLS, all.cells.size(), "all compact fixture positions materialized");
            SimpleAssert.eq(2, descriptors.size(), "123369 positions share two hydrated descriptors");
            HologramState.Cell initial = all.cells.get(0);
            SimpleAssert.eq(-32, initial.x, "negative region coordinate preserved");
            SimpleAssert.eq(
                -32,
                initial.tag()
                    .getInteger("dx"),
                "projection reconstructs detached row coordinates");
        }
        try (HologramLiteRender lite = new HologramLiteRender(all, Integer.MAX_VALUE, 0, false, false)) {
            HologramEditorCameraFrame frame = new HologramEditorCameraFrame(0, 0, 240, 180, .7, .5, 1, 0, 0, 0, 40);
            HologramLiteRender.RegionFilter cull = lite.viewport(frame);
            SimpleAssert.that(cull.visible(-16, -16, -16), "region straddling the camera origin remains visible");
            SimpleAssert.that(!cull.visible(4096, 4096, 0), "offscreen region is culled before submission");
        }
        System.out.printf(
            "MiaoVisonLite display: packed=%s bytes=%d cells=%d palette=%d regions=%d decodeMs=%.1f first256Ms=%.1f fullStateMs=%.1f%n",
            decoded instanceof HologramBlueprintPreview.PackedDisplay,
            bytes.length,
            all.cells.size(),
            descriptors.size(),
            all.regions.regions.size(),
            (parsed - start) / 1e6,
            (firstPrepared - parsed) / 1e6,
            (allPrepared - parsed) / 1e6);
        System.exit(0);
    }

    private static byte[] synthetic() {
        StringBuilder json = new StringBuilder(CELLS * 36);
        json.append("{\"encoding\":\"gtmb-dictionary-1\",\"materials\":[{\"block\":\"minecraft:stone\"},");
        json.append("{\"block\":\"minecraft:wool\",\"metadata\":14}],\"nbt\":[],\"blueprint\":{\"cells\":[");
        for (int index = 0; index < CELLS; index++) {
            if (index != 0) json.append(',');
            json.append('[')
                .append(index & 1)
                .append(",{\"x\":")
                .append((index & 63) - 32);
            json.append(",\"y\":")
                .append((index >> 6 & 31) - 16);
            json.append(",\"z\":")
                .append((index >> 11) - 32)
                .append("}]");
        }
        return json.append("]}}")
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }
}
