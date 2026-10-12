package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Client selection only; server actions carry explicit indices and validate every edit. */
public final class HologramSelection {

    private final Set<Integer> indices = new LinkedHashSet<>();
    private int corner = -1;
    private String clickedStructureId = "";
    private int version, mobilityVersion = -1;
    private HologramState mobilityState;
    private boolean mobility;

    public void clear() {
        version++;
        indices.clear();
        corner = -1;
        clickedStructureId = "";
    }

    public void click(int index, boolean additive) {
        version++;
        if (!additive) clear();
        clickedStructureId = "";
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
        version++;
        clickedStructureId = "";
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
        version++;
        if (!additive) clear();
        for (HologramState.Cell cell : state.cells) if (cell.belongsToStructure(id)) indices.add(cell.index);
        clickedStructureId = knownStructure(state, id) ? id : "";
    }

    /** T selects exact block variants; declared replacement families stay inside the clicked structure. */
    void previewClick(HologramState state, int index, String outline, boolean sameType, boolean additive) {
        if (!outline.isEmpty() && knownStructure(state, outline)) {
            structure(state, outline, additive);
            return;
        }
        if (index < 0 || index >= state.cells.size()) {
            if (!additive) clear();
            return;
        }
        HologramState.Cell clicked = state.cells.get(index);
        if (!sameType && clicked.family.isEmpty()) {
            click(index, additive);
            return;
        }
        String active = state.data.getString("activeStructure");
        String scope = clicked.belongsToStructure(active) ? active : clicked.structureId;
        if (!additive) clear();
        version++;
        clickedStructureId = "";
        Set<Integer> matched = new LinkedHashSet<>();
        matched.add(index);
        for (HologramState.Cell cell : state.cells) {
            boolean inScope = scope.isEmpty() ? cell.structureIds.isEmpty() : cell.belongsToStructure(scope);
            boolean matches = sameType ? sameType(clicked, cell) : clicked.family.equals(cell.family);
            if (inScope && matches) matched.add(cell.index);
        }
        if (additive && indices.containsAll(matched)) indices.removeAll(matched);
        else indices.addAll(matched);
        corner = index;
    }

    static boolean sameType(HologramState.Cell a, HologramState.Cell b) {
        if (!a.exactIdentity.isEmpty() || !b.exactIdentity.isEmpty())
            return !a.exactIdentity.isEmpty() && a.exactIdentity.equals(b.exactIdentity);
        return a.wantId.equals(b.wantId) && a.wantMeta == b.wantMeta;
    }

    /** A structure member moves only as part of a complete selected structure, including shared walls. */
    boolean movable(HologramState state) {
        if (mobilityState == state && mobilityVersion == version) return mobility;
        mobilityState = state;
        mobilityVersion = version;
        mobility = computeMovable(state);
        return mobility;
    }

    private boolean computeMovable(HologramState state) {
        if (indices.isEmpty()) return false;
        Set<String> complete = new LinkedHashSet<>();
        Map<String, Integer> total = new LinkedHashMap<>(), chosen = new LinkedHashMap<>();
        for (HologramState.Cell cell : state.cells)
            for (String id : cell.structureIds) total.put(id, total.getOrDefault(id, 0) + 1);
        for (int index : indices) {
            if (index < 0 || index >= state.cells.size()) return false;
            for (String id : state.cells.get(index).structureIds) chosen.put(id, chosen.getOrDefault(id, 0) + 1);
        }
        for (String id : chosen.keySet()) if (chosen.get(id)
            .equals(total.get(id)) && knownStructure(state, id)) complete.add(id);
        for (int index : indices) {
            HologramState.Cell cell = state.cells.get(index);
            if (cell.structureIds.isEmpty()) continue;
            boolean covered = false;
            for (String id : cell.structureIds) if (complete.contains(id)) covered = true;
            if (!covered) return false;
        }
        return true;
    }

    /** Prefer the clicked edge's group when the shared first cell has another primary ID. */
    String structureId(HologramState state, String activeStructure, int primaryCell) {
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
        version++;
        indices.removeIf(i -> i < 0 || i >= size);
        if (corner >= size) corner = -1;
        if (indices.isEmpty()) clickedStructureId = "";
    }

    void all(HologramState state) {
        clear();
        for (HologramState.Cell cell : state.cells) indices.add(cell.index);
        corner = first();
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
