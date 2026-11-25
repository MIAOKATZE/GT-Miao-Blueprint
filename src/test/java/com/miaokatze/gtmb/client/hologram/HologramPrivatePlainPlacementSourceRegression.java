// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramPreviewPose;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Plain;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramPrivatePlainPlacementSourceRegression {

    private static final int[][] COORDINATES = {
        { -18, 0, 0 }, { 0, 2, 18 }, { 34, -3, -16 }, { 1, 1, 5 }, { 17, 2, 18 }
    };
    private static final int[] ANCHOR = { -19, 70, 86 };

    private HologramPrivatePlainPlacementSourceRegression() {}

    public static void main(String[] args) throws IOException {
        run();
    }

    public static void run() throws IOException {
        List<HologramState.Cell> sourceCells = new ArrayList<>();
        StringBuilder json = new StringBuilder("{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\","
            + "\"blueprint\":{\"schema\":2,\"cells\":[");
        for (int i = 0; i < COORDINATES.length; i++) {
            int[] point = COORDINATES[i];
            if (i != 0) json.append(',');
            json.append("[0,{\"x\":").append(point[0]).append(",\"y\":").append(point[1])
                .append(",\"z\":").append(point[2]).append("}]");
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", point[0]);
            row.setInteger("y", point[1]);
            row.setInteger("z", point[2]);
            row.setString("wantId", "minecraft:stone");
            row.setInteger("targetFacing", -1);
            sourceCells.add(new HologramState.Cell(i, row));
        }
        byte[] captured = json.append("],\"palette\":[]},\"materials\":[{\"block\":\"minecraft:stone\","
            + "\"item\":\"minecraft:stone\"}],\"nbt\":[]}").toString().getBytes(StandardCharsets.UTF_8);
        var unmapped = HologramBlueprintPreview.displayInput(captured, null, true, false);
        SimpleAssert.eq(COORDINATES.length, unmapped.cells.size(), "same preview remains available without indexing");
        SimpleAssert.eq(null, unmapped.plainPlacement, "portable/unindexed display cannot mint private proof");
        var input = HologramBlueprintPreview.displayInput(captured, null, true, true);
        var evidence = input.plainPlacement;
        SimpleAssert.that(evidence != null && evidence.sameCapture(captured),
            "existing completed compact display mints its own exact indexed-private evidence");
        SimpleAssert.eq(input.cells.size(), evidence.cells(), "one proof ordinal per displayed compact row");
        SimpleAssert.eq(null, HologramBlueprintPreview.displayInput(captured, null, false, true).plainPlacement,
            "an indexed flag alone cannot turn the public view decoder into a private source");
        HologramRegionCache regions = new HologramRegionCache(sourceCells);
        AtomicBoolean active = new AtomicBoolean(true);
        for (int[] recipe : new int[][] { {}, { 0 }, { 1 }, { 0, 1, 0 }, { 1, 1, 0 } }) {
            var source = new HologramPrivatePlainPlacementSource(
                evidence, captured, regions, ANCHOR, recipe, 256, active::get);
            var pose = new HologramPreviewPose();
            for (int turn : recipe) pose = pose.turn(turn == 1);
            List<String> expected = new ArrayList<>();
            for (int[] item : COORDINATES) {
                int[] position = pose.apply(item[0], item[1], item[2]);
                expected.add(key(position[0] + ANCHOR[0], position[1] + ANCHOR[1], position[2] + ANCHOR[2]));
            }
            Collections.sort(expected);
            SimpleAssert.eq(expected, walk(source, false),
                "position index visits exact transformed cells in forward Y/Z/X order");
            Collections.reverse(expected);
            SimpleAssert.eq(expected, walk(source, true),
                "reverse traversal yields identical cells across negative regions and skipped empty chunks");
            SimpleAssert.eq(source.cells(), source.count(Plain.STONE), "exact certified palette counts");
        }
        try {
            new HologramPrivatePlainPlacementSource(evidence, captured.clone(), regions, ANCHOR, new int[0], 256,
                () -> true);
            throw new AssertionError("must not reuse evidence from another equal byte array");
        } catch (IOException accepted) {}
        try {
            new HologramPrivatePlainPlacementSource(evidence, captured, regions, new int[] { 0, 3, 0 },
                new int[] { 1 }, 256, () -> true);
            throw new AssertionError("rotated frozen source extends outside world height");
        } catch (IOException accepted) {}
        var retired = new HologramPrivatePlainPlacementSource(
            evidence, captured, regions, ANCHOR, new int[0], 256, active::get).open(false);
        SimpleAssert.that(retired.next(), "first owned row");
        active.set(false);
        try {
            retired.next();
            throw new AssertionError("abandoned cursor cannot continue work");
        } catch (InterruptedIOException accepted) {}
    }

    /** Bounded synthetic fixture may materialize expected rows; production adapter may not. */
    private static List<String> walk(HologramPrivatePlainPlacementSource source, boolean reverse) throws IOException {
        List<String> values = new ArrayList<>();
        var cursor = source.open(reverse);
        while (cursor.next()) {
            SimpleAssert.eq(Plain.STONE, cursor.kind(), "identity came from the accepted view");
            values.add(key(cursor.x(), cursor.y(), cursor.z()));
        }
        return values;
    }

    /** Bounded fixture keeps offset positive so textual key matches integer sorting. */
    private static String key(int x, int y, int z) {
        return String.format(java.util.Locale.ROOT, "%04d/%04d/%04d", y + 100, z + 100, x + 100);
    }
}
