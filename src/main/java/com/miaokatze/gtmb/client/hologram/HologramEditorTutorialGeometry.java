package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Finite teaching geometry. All transactions own copies; no world, player, transport or storage reference. */
final class HologramEditorTutorialGeometry {

    private NBTTagCompound source, clipboard, scene, captured;
    private int[] first, second;
    private boolean locked, placing, consumed;

    static NBTTagCompound raw(HologramState state) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("id", "tutorial-ebf");
        entry.setString("name", "电力高炉 · 真实采集规划快照");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (HologramState.Cell cell : state.cells) {
            minX = Math.min(minX, cell.x);
            minY = Math.min(minY, cell.y);
            minZ = Math.min(minZ, cell.z);
            maxX = Math.max(maxX, cell.x);
            maxY = Math.max(maxY, cell.y);
            maxZ = Math.max(maxZ, cell.z);
        }
        if (state.cells.isEmpty()) throw new IllegalArgumentException("The EBF teaching snapshot is empty");
        NBTTagList cells = new NBTTagList();
        for (HologramState.Cell cell : state.cells) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", cell.x - minX);
            row.setInteger("y", cell.y - minY);
            row.setInteger("z", cell.z - minZ);
            // The physical observation records the actual controller plus its declared target shape.
            boolean actual = cell.anchor && cell.actualStack != null;
            row.setString("block", actual ? cell.id : cell.wantId);
            row.setInteger("meta", actual ? cell.meta : cell.wantMeta);
            NBTTagCompound display = actual ? cell.actualDisplay : cell.targetDisplay;
            if (display.hasKey("nativeTile", 10)) row.setTag(
                "tile",
                display.getCompoundTag("nativeTile")
                    .copy());
            row.setTag("observedCell", cell.tag());
            cells.appendTag(row);
        }
        entry.setInteger("sizeX", maxX - minX + 1);
        entry.setInteger("sizeY", maxY - minY + 1);
        entry.setInteger("sizeZ", maxZ - minZ + 1);
        entry.setTag("cells", cells);
        return entry;
    }

    void initialize(HologramState state) {
        source = raw(state);
        scene = copy(source);
        clipboard = captured = null;
        first = second = null;
        locked = placing = consumed = false;
    }

    NBTTagCompound scene() {
        return scene;
    }

    int corners() {
        return first == null ? 0 : second == null ? 1 : 2;
    }

    int count() {
        return selected().tagCount();
    }

    boolean stored() {
        return clipboard != null;
    }

    boolean consumed() {
        return consumed;
    }

    int[] corner(boolean last) {
        return last
            ? new int[] { source.getInteger("sizeX") - 1, source.getInteger("sizeY") - 1,
                source.getInteger("sizeZ") - 1 }
            : new int[] { 0, 0, 0 };
    }

    void choose(boolean last) {
        if (last && first == null) throw new IllegalStateException("Select the first EBF corner first");
        if (last) second = corner(true);
        else {
            first = corner(false);
            second = null;
        }
    }

    void expand(int dx, int dz) {
        requireSelection();
        int[] end = corner(true);
        second = new int[] { end[0] + dx, end[1], end[2] + dz };
    }

    private NBTTagList selected() {
        NBTTagList result = new NBTTagList();
        if (first == null || second == null) return result;
        NBTTagList cells = source.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            int[] p = { row.getInteger("x"), row.getInteger("y"), row.getInteger("z") };
            boolean inside = true;
            for (int a = 0; a < 3; a++)
                inside &= p[a] >= Math.min(first[a], second[a]) && p[a] <= Math.max(first[a], second[a]);
            if (inside) result.appendTag(row.copy());
        }
        return result;
    }

    void requireSelection() {
        if (count() == 0) throw new IllegalStateException("No recorded EBF cells inside the teaching selection");
    }

    void submit() {
        requireSelection();
        captured = copy(source);
        captured.setTag("cells", selected());
        if (captured.getTagList("cells", 10)
            .tagCount() != count())
            throw new IllegalStateException("Teaching selection capture differs from its geometry");
    }

    void store() {
        requireSelection();
        if (clipboard != null || consumed) throw new IllegalStateException("Teaching cut already stored or consumed");
        clipboard = copy(source);
        clipboard.setTag("cells", selected());
        // This course selects the complete declared footprint. Refuse an accidentally partial cut.
        if (count() != source.getTagList("cells", 10)
            .tagCount()) throw new IllegalStateException("Incomplete teaching EBF selection");
        scene = copy(source);
        scene.setTag("cells", new NBTTagList());
        first = second = null;
    }

    void take() {
        if (clipboard == null) throw new IllegalStateException("No teaching cut entry");
        scene = translated(clipboard, source.getInteger("sizeX") + 2, 0, 2);
        placing = true;
        locked = false;
    }

    void lock() {
        if (!placing || clipboard == null) throw new IllegalStateException("No teaching placement preview");
        locked = true;
    }

    void cancel() {
        if (clipboard == null) throw new IllegalStateException("No retained teaching entry");
        scene = copy(source);
        scene.setTag("cells", new NBTTagList());
        placing = locked = false;
    }

    void place() {
        if (!placing || !locked || clipboard == null)
            throw new IllegalStateException("Lock the teaching preview first");
        NBTTagCompound expected = translated(clipboard, source.getInteger("sizeX") + 2, 0, 2);
        if (!expected.equals(scene))
            throw new IllegalStateException("Teaching placement geometry differs from the retained entry");
        clipboard = null;
        placing = locked = false;
        consumed = true;
    }

    int[] first() {
        return first;
    }

    int[] second() {
        return second;
    }

    int[] bounds() {
        int[] result = { 0, 0, 0, source.getInteger("sizeX"), source.getInteger("sizeY"), source.getInteger("sizeZ") };
        NBTTagList cells = scene.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            int[] p = { row.getInteger("x"), row.getInteger("y"), row.getInteger("z") };
            for (int axis = 0; axis < 3; axis++) {
                result[axis] = Math.min(result[axis], p[axis]);
                result[axis + 3] = Math.max(result[axis + 3], p[axis] + 1);
            }
        }
        if (second != null)
            for (int axis = 0; axis < 3; axis++) result[axis + 3] = Math.max(result[axis + 3], second[axis] + 1);
        return result;
    }

    NBTTagCompound snapshot() {
        NBTTagCompound n = new NBTTagCompound();
        n.setTag("source", copy(source));
        n.setTag("scene", copy(scene));
        if (clipboard != null) n.setTag("clipboard", copy(clipboard));
        if (captured != null) n.setTag("capturedSelection", copy(captured));
        if (first != null) n.setIntArray("first", first);
        if (second != null) n.setIntArray("second", second);
        n.setBoolean("locked", locked);
        n.setBoolean("placing", placing);
        n.setBoolean("consumed", consumed);
        return n;
    }

    void restore(NBTTagCompound n) {
        source = copy(n.getCompoundTag("source"));
        scene = copy(n.getCompoundTag("scene"));
        clipboard = n.hasKey("clipboard", 10) ? copy(n.getCompoundTag("clipboard")) : null;
        captured = n.hasKey("capturedSelection", 10) ? copy(n.getCompoundTag("capturedSelection")) : null;
        first = n.hasKey("first", 11) ? n.getIntArray("first") : null;
        second = n.hasKey("second", 11) ? n.getIntArray("second") : null;
        locked = n.getBoolean("locked");
        placing = n.getBoolean("placing");
        consumed = n.getBoolean("consumed");
    }

    private static NBTTagCompound translated(NBTTagCompound original, int dx, int dy, int dz) {
        NBTTagCompound result = copy(original);
        NBTTagList cells = result.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            row.setInteger("x", row.getInteger("x") + dx);
            row.setInteger("y", row.getInteger("y") + dy);
            row.setInteger("z", row.getInteger("z") + dz);
        }
        return result;
    }

    private static NBTTagCompound copy(NBTTagCompound value) {
        return (NBTTagCompound) value.copy();
    }
}
