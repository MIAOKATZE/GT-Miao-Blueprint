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
        Map<String, Set<String>> networkPrompts = new LinkedHashMap<>();
        Map<String, List<Integer>> networkCells = new LinkedHashMap<>();
        String activeStructure = state.data.getString("activeStructure");
        for (HologramState.Cell cell : state.cells) {
            if (state.data.getBoolean("virtual") && cell.quantumNetworkHint()) {
                // Bounds follow this snapshot's transformed cells, restricted to the current editing structure.
                if (!activeStructure.isEmpty() && !cell.belongsToStructure(activeStructure)) continue;
                Set<String> prompts = networkPrompts.computeIfAbsent(cell.networkGroup, key -> new LinkedHashSet<>());
                String hint = cell.specialHint.getString("hint");
                if (!hint.isEmpty() && !hint.equals("量子网络组 " + cell.networkGroup.substring(1))) prompts.add(hint);
                if (!cell.prompt.isEmpty()) prompts.add(cell.prompt);
                networkCells.computeIfAbsent(cell.networkGroup, key -> new ArrayList<>())
                    .add(cell.index);
                continue;
            }
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
        for (Map.Entry<String, List<Integer>> group : networkCells.entrySet()) {
            int[] indices = group.getValue()
                .stream()
                .mapToInt(Integer::intValue)
                .toArray();
            String prompt = String.join("\n", networkPrompts.get(group.getKey()));
            result.add(
                new Marker(
                    "quantum:" + group.getKey(),
                    "量子网络组 " + group.getKey()
                        .substring(1),
                    prompt.isEmpty() ? "同组设备共享本次蓝图放置的网络绑定。" : prompt,
                    HologramAnnotations.bounds(state, indices)));
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
