package com.miaokatze.gtmb.client.hologram;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.client.hologram.HologramEditorLessons.Operation;

/** Spatial contract for the authored blueprint lesson, in block units relative to its observed controller. */
final class HologramEditorTutorialBlueprint {

    private HologramEditorTutorialBlueprint() {}

    static int[] offset(Operation operation) {
        if (operation == Operation.MOVE_X) return new int[] { 2, 0, 0 };
        if (operation == Operation.MOVE_XZ) return new int[] { 3, 0, 1 };
        if (operation == Operation.COPY_X) return new int[] { -5, 0, 0 };
        throw new IllegalArgumentException("Not a blueprint translation: " + operation);
    }

    static void translate(NBTTagCompound data, int[] selected, Set<String> members, int[] offset) {
        NBTTagList cells = data.getTagList("cells", 10);
        for (int index : selected) {
            NBTTagCompound cell = cells.getCompoundTagAt(index);
            for (int axis = 0; axis < 3; axis++) {
                String key = new String[] { "x", "y", "z" }[axis];
                cell.setInteger(key, cell.getInteger(key) + offset[axis]);
                cell.setInteger("d" + key, cell.getInteger("d" + key) + offset[axis]);
            }
        }
        NBTTagList structures = data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            NBTTagCompound structure = structures.getCompoundTagAt(i);
            if (members.contains(structure.getString("id"))) for (int axis = 0; axis < 3; axis++) {
                String key = new String[] { "x", "y", "z" }[axis];
                structure.setInteger(key, structure.getInteger(key) + offset[axis]);
                if (structure.hasKey("textBox", 10)) {
                    NBTTagCompound attachment = structure.getCompoundTag("textBox");
                    attachment.setInteger(key, attachment.getInteger(key) + offset[axis]);
                }
            }
        }
    }

    /** Check the prospective final geometry before committing a native gizmo result. */
    static boolean clearAndSupported(NBTTagCompound data, int[] selected, int[] offset) {
        NBTTagList cells = data.getTagList("cells", 10);
        Set<Integer> moving = new HashSet<>();
        for (int index : selected) {
            if (index < 0 || index >= cells.tagCount()) return false;
            moving.add(index);
        }
        if (moving.isEmpty()) return false;
        Set<String> fixed = new HashSet<>(), destination = new HashSet<>();
        int bottom = Integer.MAX_VALUE;
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (moving.contains(i)) bottom = Math.min(bottom, cell.getInteger("dy") + offset[1]);
            else fixed.add(key(cell.getInteger("dx"), cell.getInteger("dy"), cell.getInteger("dz")));
        }
        for (int index : selected) {
            NBTTagCompound cell = cells.getCompoundTagAt(index);
            int x = cell.getInteger("dx") + offset[0], y = cell.getInteger("dy") + offset[1],
                z = cell.getInteger("dz") + offset[2];
            String key = key(x, y, z);
            if (fixed.contains(key) || !destination.add(key)) return false;
            if (y == bottom && !fixed.contains(key(x, y - 1, z))) return false;
        }
        return true;
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
