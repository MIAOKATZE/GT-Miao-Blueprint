package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTBase.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Read-only exact projection comparison. Unlike the disk verifier it never copies a list to iterate it. */
final class HologramSnapshotNbt {

    private static final Field ENTRIES = entriesField();

    private HologramSnapshotNbt() {}

    static boolean same(NBTBase a, NBTBase b) {
        if (a == b) return true;
        if (a == null || b == null || a.getId() != b.getId()) return false;
        if (a instanceof NBTTagCompound) {
            NBTTagCompound left = (NBTTagCompound) a, right = (NBTTagCompound) b;
            if (!left.func_150296_c()
                .equals(right.func_150296_c())) return false;
            for (Object key : left.func_150296_c())
                if (!same(left.getTag((String) key), right.getTag((String) key))) return false;
            return true;
        }
        if (a instanceof NBTTagList) {
            NBTTagList left = (NBTTagList) a, right = (NBTTagList) b;
            if (left.func_150303_d() != right.func_150303_d() || left.tagCount() != right.tagCount()) return false;
            List<?> x = entries(left), y = entries(right);
            for (int i = 0; i < x.size(); i++) if (!same((NBTBase) x.get(i), (NBTBase) y.get(i))) return false;
            return true;
        }
        if (a instanceof NBTTagByteArray)
            return Arrays.equals(((NBTTagByteArray) a).func_150292_c(), ((NBTTagByteArray) b).func_150292_c());
        if (a instanceof NBTTagIntArray)
            return Arrays.equals(((NBTTagIntArray) a).func_150302_c(), ((NBTTagIntArray) b).func_150302_c());
        if (a instanceof NBTTagString) return ((NBTTagString) a).func_150285_a_()
            .equals(((NBTTagString) b).func_150285_a_());
        if (a instanceof NBTPrimitive) {
            NBTPrimitive x = (NBTPrimitive) a, y = (NBTPrimitive) b;
            if (a.getId() == 5)
                return Float.floatToRawIntBits(x.func_150288_h()) == Float.floatToRawIntBits(y.func_150288_h());
            if (a.getId() == 6)
                return Double.doubleToRawLongBits(x.func_150286_g()) == Double.doubleToRawLongBits(y.func_150286_g());
            return x.func_150291_c() == y.func_150291_c();
        }
        return a.getId() == 0;
    }

    static long estimate(NBTBase tag) {
        return estimate(tag, 0);
    }

    private static long estimate(NBTBase tag, int depth) {
        if (depth > 512) throw new IllegalArgumentException("snapshot NBT depth");
        long bytes = 64;
        if (tag instanceof NBTTagCompound) {
            bytes += 64;
            NBTTagCompound compound = (NBTTagCompound) tag;
            for (Object key : compound.func_150296_c())
                bytes += 128L + 2L * ((String) key).length() + estimate(compound.getTag((String) key), depth + 1);
        } else if (tag instanceof NBTTagList) {
            List<?> list = entries((NBTTagList) tag);
            bytes += 64L + 16L * list.size();
            for (Object child : list) bytes += estimate((NBTBase) child, depth + 1);
        } else if (tag instanceof NBTTagByteArray) bytes += 24L + ((NBTTagByteArray) tag).func_150292_c().length;
        else if (tag instanceof NBTTagIntArray) bytes += 24L + 4L * ((NBTTagIntArray) tag).func_150302_c().length;
        else if (tag instanceof NBTTagString) bytes += 64L + 2L * ((NBTTagString) tag).func_150285_a_()
            .length();
        return bytes;
    }

    private static List<?> entries(NBTTagList list) {
        try {
            return (List<?>) ENTRIES.get(list);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("snapshot list read-only projection", failure);
        }
    }

    private static Field entriesField() {
        Field selected = null;
        for (Field field : NBTTagList.class.getDeclaredFields())
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                && List.class.isAssignableFrom(field.getType())) {
                    if (selected != null) throw new IllegalStateException("ambiguous snapshot list entries");
                    selected = field;
                }
        if (selected == null) throw new IllegalStateException("snapshot list entries unavailable");
        selected.setAccessible(true);
        return selected;
    }
}
