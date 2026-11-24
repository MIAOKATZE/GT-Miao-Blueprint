// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.DisplayRows;

/** One source-backed annotation and a distinct, same-descriptor adjacent control. No draft or geometry is retained. */
final class HologramGiantCellDelta {

    static final String PROMPT = "gtmb selected cell";
    final int target, neighbor;
    final int[] targetXyz, neighborXyz;
    final boolean excluded;

    private HologramGiantCellDelta(int target, int neighbor, int[] targetXyz, int[] neighborXyz, boolean excluded) {
        this.target = target;
        this.neighbor = neighbor;
        this.targetXyz = targetXyz;
        this.neighborXyz = neighborXyz;
        this.excluded = excluded;
    }

    static HologramGiantCellDelta select(DisplayRows original, int target, int neighbor) {
        if (!original.compact() || target < 0
            || neighbor < 0
            || target >= original.cells()
            || neighbor >= original.cells()
            || target == neighbor
            || original.materialIndex(target) != original.materialIndex(neighbor)) return null;
        int[] a = xyz(original, target), b = xyz(original, neighbor);
        if (Math.abs((long) a[0] - b[0]) + Math.abs((long) a[1] - b[1]) + Math.abs((long) a[2] - b[2]) != 1)
            return null;
        JsonObject material = original.material(original.materialIndex(target));
        if (!value(material, "prompt").isEmpty() || value(material, "block").isEmpty()
            || "minecraft:air".equals(value(material, "block"))) return null;
        return new HologramGiantCellDelta(
            target,
            neighbor,
            a,
            b,
            present(material.get("excluded")) && material.get("excluded")
                .getAsBoolean());
    }

    void verify(DisplayRows original, DisplayRows saved) throws IOException {
        if (select(original, target, neighbor) == null || !Arrays.equals(targetXyz, xyz(original, target))
            || !Arrays.equals(neighborXyz, xyz(original, neighbor)))
            throw new IOException("CELL_PAIR: original target/control identity changed");
        if (!saved.compact() || saved.cells() != original.cells()
            || !Arrays.equals(targetXyz, xyz(saved, target))
            || !Arrays.equals(neighborXyz, xyz(saved, neighbor)))
            throw new IOException("CELL_PAIR: saved target/control geometry changed");
        JsonObject before = original.material(original.materialIndex(target));
        JsonObject edited = saved.material(saved.materialIndex(target));
        JsonObject untouched = saved.material(saved.materialIndex(neighbor));
        if (saved.materialIndex(target) == saved.materialIndex(neighbor) || !PROMPT.equals(value(edited, "prompt"))
            || !value(untouched, "prompt").isEmpty())
            throw new IOException("CELL_PAIR: target-local annotation or descriptor split missing");
        sameMaterial(before, edited, target, true);
        sameMaterial(before, untouched, neighbor, false);
    }

    static void sameMaterial(JsonObject original, JsonObject saved, int row, boolean promptDelta) throws IOException {
        for (Map.Entry<String, JsonElement> field : original.entrySet()) {
            if (promptDelta && "prompt".equals(field.getKey())) continue;
            if (!Objects.equals(field.getValue(), saved.get(field.getKey())))
                throw new IOException("PAIR_PAYLOAD: retained material field " + field.getKey() + " changed at " + row);
        }
        if (!promptDelta && !value(original, "prompt").equals(value(saved, "prompt")))
            throw new IOException("PAIR_PAYLOAD: unrelated prompt changed at " + row);
        if (Boolean.parseBoolean(value(original, "excluded")) != Boolean.parseBoolean(value(saved, "excluded")))
            throw new IOException("PAIR_PAYLOAD: construction exclusion changed at " + row);
        for (String key : new String[] { "tileNbt", "itemNbt" })
            if (present(original.get(key)) != present(saved.get(key)))
                throw new IOException("PAIR_PAYLOAD: added or lost native field " + key + " at " + row);
    }

    private static int[] xyz(DisplayRows rows, int at) {
        return new int[] { rows.coordinate(at, 0), rows.coordinate(at, 1), rows.coordinate(at, 2) };
    }

    private static String value(JsonObject object, String key) {
        JsonElement field = object.get(key);
        return present(field) ? field.getAsString() : "";
    }

    private static boolean present(JsonElement element) {
        return element != null && !element.isJsonNull();
    }
}
