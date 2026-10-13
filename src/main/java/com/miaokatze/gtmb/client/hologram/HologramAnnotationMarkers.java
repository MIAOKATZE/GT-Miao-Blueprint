package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Annotation content is grouped before projection: one structure produces one marker. */
final class HologramAnnotationMarkers {

    private HologramAnnotationMarkers() {}

    static List<Marker> collect(HologramState state) {
        List<Marker> result = new ArrayList<>();
        String global = state.data.getString("blueprintPrompt");
        if (!global.isEmpty()) result.add(new Marker("blueprint", "蓝图提示", global, null));
        Map<String, Set<String>> structurePrompts = new LinkedHashMap<>();
        Map<String, List<Integer>> structureCells = new LinkedHashMap<>();
        for (HologramState.Cell cell : state.cells) {
            if (cell.prompt.isEmpty()) continue;
            if (cell.structureId.isEmpty()) {
                result.add(
                    new Marker(
                        "cell:" + cell.index,
                        "方块 " + cell.dx + "," + cell.dy + "," + cell.dz,
                        cell.prompt,
                        new int[] { cell.dx, cell.dy, cell.dz, cell.dx, cell.dy, cell.dz }));
            } else {
                structurePrompts.computeIfAbsent(cell.structureId, key -> new LinkedHashSet<>())
                    .add(cell.prompt);
                structureCells.computeIfAbsent(cell.structureId, key -> new ArrayList<>())
                    .add(cell.index);
            }
        }
        NBTTagList structures = state.data.getTagList("structures", 10);
        for (Map.Entry<String, Set<String>> row : structurePrompts.entrySet()) {
            String name = row.getKey();
            for (int i = 0; i < structures.tagCount(); i++) {
                NBTTagCompound structure = structures.getCompoundTagAt(i);
                if (row.getKey()
                    .equals(structure.getString("id"))) name = structure.getString("name");
            }
            int[] indices = structureCells.get(row.getKey())
                .stream()
                .mapToInt(Integer::intValue)
                .toArray();
            result.add(
                new Marker(
                    "structure:" + row.getKey(),
                    "结构提示 · " + name,
                    String.join("\n", row.getValue()),
                    HologramAnnotations.bounds(state, indices)));
        }
        NBTTagList areas = state.data.getTagList("hintAreas", 10);
        for (int i = 0; i < areas.tagCount(); i++) {
            NBTTagCompound area = areas.getCompoundTagAt(i);
            int[] bounds = area.getIntArray("bounds");
            if (bounds.length == 6 && !area.getString("prompt")
                .isEmpty()) result.add(new Marker("area:" + i, "提示区 " + (i + 1), area.getString("prompt"), bounds));
        }
        result.addAll(0, HologramAeWarningMarkers.collect(state));
        return result;
    }

    static final class Marker {

        final String key, title, prompt;
        final int[] bounds;
        int x, y;

        Marker(String key, String title, String prompt, int[] bounds) {
            this.key = key;
            this.title = title;
            this.prompt = prompt;
            this.bounds = bounds;
        }

        boolean contains(int mx, int my) {
            return mx >= x - 6 && mx < x + 7 && my >= y - 6 && my < y + 7;
        }
    }
}
