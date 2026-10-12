package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashSet;
import java.util.Set;

/** Client selection only; server actions carry explicit indices and validate every edit. */
public final class HologramSelection {

    private final Set<Integer> indices = new LinkedHashSet<>();
    private int corner = -1;
    private String clickedStructureId = "";
    private boolean blueprintSelected;

    public void clear() {
        indices.clear();
        corner = -1;
        clickedStructureId = "";
        blueprintSelected = false;
    }

    public void click(int index, boolean additive) {
        if (!additive) clear();
        clickedStructureId = "";
        blueprintSelected = false;
        if (index < 0) return;
        if (additive && indices.contains(index)) indices.remove(index);
        else indices.add(index);
        corner = index;
    }

    void load(int[] supplied, int primary, int size) {
        clear();
        for (int index : supplied) if (index >= 0 && index < size) indices.add(index);
        if (indices.isEmpty() && primary >= 0 && primary < size) indices.add(primary);
        corner = indices.isEmpty() ? -1 : first();
    }

    void box(HologramState state, int from, int to, boolean additive) {
        if (from < 0 || to < 0 || from >= state.cells.size() || to >= state.cells.size()) return;
        HologramState.Cell a = state.cells.get(from), b = state.cells.get(to);
        clickedStructureId = "";
        blueprintSelected = false;
        if (!additive) indices.clear();
        for (HologramState.Cell cell : state.cells) {
            if (inside(cell.dx, cell.dy, cell.dz, a.dx, a.dy, a.dz, b.dx, b.dy, b.dz)) indices.add(cell.index);
        }
        corner = from;
    }

    /** Inclusive world-space volume, independent of camera angle and visible faces. */
    public static boolean inside(int x, int y, int z, int ax, int ay, int az, int bx, int by, int bz) {
        return x >= Math.min(ax, bx) && x <= Math.max(ax, bx)
            && y >= Math.min(ay, by)
            && y <= Math.max(ay, by)
            && z >= Math.min(az, bz)
            && z <= Math.max(az, bz);
    }

    void structure(HologramState state, String id, boolean additive) {
        if (!additive) clear();
        blueprintSelected = false;
        for (HologramState.Cell cell : state.cells) if (cell.belongsToStructure(id)) indices.add(cell.index);
        clickedStructureId = knownStructure(state, id) ? id : "";
    }

    /** Ordinary faces select one cell; only an outline or held T expands the selection. */
    void previewClick(HologramState state, int index, String outline, boolean whole, boolean additive) {
        if (!outline.isEmpty() && knownStructure(state, outline)) {
            structure(state, outline, additive);
            return;
        }
        if (index < 0 || index >= state.cells.size()) {
            if (!additive) clear();
            return;
        }
        if (!whole) {
            click(index, additive);
            return;
        }
        String id = state.cells.get(index).structureId;
        if (knownStructure(state, id)) structure(state, id, additive);
        else {
            if (!additive) clear();
            clickedStructureId = "";
            blueprintSelected = true;
            indices.add(index);
            for (HologramState.Cell cell : state.cells) indices.add(cell.index);
            corner = index;
        }
    }

    /** Prefer the clicked edge's group when the shared first cell has another primary ID. */
    String structureId(HologramState state, String activeStructure, int primaryCell) {
        if (blueprintSelected) return "";
        if (!clickedStructureId.isEmpty() && !knownStructure(state, clickedStructureId)) clickedStructureId = "";
        if (!clickedStructureId.isEmpty()) return clickedStructureId;
        if (knownStructure(state, activeStructure)) return activeStructure;
        if (primaryCell >= 0 && primaryCell < state.cells.size()) {
            String primary = state.cells.get(primaryCell).structureId;
            if (knownStructure(state, primary)) return primary;
        }
        return "";
    }

    private static boolean knownStructure(HologramState state, String id) {
        if (id.isEmpty()) return false;
        if (state.data.hasKey("structures")) {
            boolean declared = false;
            net.minecraft.nbt.NBTTagList structures = state.data.getTagList("structures", 10);
            for (int i = 0; i < structures.tagCount(); i++) {
                if (id.equals(
                    structures.getCompoundTagAt(i)
                        .getString("id"))) {
                    declared = true;
                    break;
                }
            }
            if (!declared) return false;
        }
        for (HologramState.Cell cell : state.cells) if (cell.belongsToStructure(id)) return true;
        return false;
    }

    void retain(int size) {
        indices.removeIf(i -> i < 0 || i >= size);
        if (corner >= size) corner = -1;
        if (indices.isEmpty()) clickedStructureId = "";
    }

    public boolean contains(int index) {
        return indices.contains(index);
    }

    public int size() {
        return indices.size();
    }

    public int corner() {
        return corner;
    }

    public int first() {
        return indices.isEmpty() ? -1
            : indices.iterator()
                .next();
    }

    public int[] indices() {
        int[] result = new int[indices.size()];
        int at = 0;
        for (int index : indices) result[at++] = index;
        return result;
    }
}
