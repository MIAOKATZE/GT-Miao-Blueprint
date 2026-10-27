package com.miaokatze.gtmb.hologram;

import java.util.Arrays;
import java.util.Objects;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;

/**
 * Read-only native NBT value comparison within one owned operation. No memoization, registry access, world facts
 * or admission decisions. Callers retain exclusive ownership of mutable inputs throughout comparison.
 */
final class HologramCutNbtComparison {

    private HologramCutNbtComparison() {}

    static boolean same(NBTBase first, NBTBase second) {
        if (first == second) return true;
        if (first == null || second == null || first.getId() != second.getId()) return false;
        // Preserve Cut's existing semantics, NOT vanilla numeric equals: canonical NaN, distinct signed zero.
        if (first instanceof NBTTagFloat) return Float.floatToIntBits(((NBTTagFloat) first).func_150288_h())
            == Float.floatToIntBits(((NBTTagFloat) second).func_150288_h());
        if (first instanceof NBTTagDouble) return Double.doubleToLongBits(((NBTTagDouble) first).func_150286_g())
            == Double.doubleToLongBits(((NBTTagDouble) second).func_150286_g());
        if (first instanceof NBTTagCompound) {
            NBTTagCompound a = (NBTTagCompound) first, b = (NBTTagCompound) second;
            if (!a.func_150296_c()
                .equals(b.func_150296_c())) return false;
            for (Object key : a.func_150296_c())
                if (!same(a.getTag((String) key), b.getTag((String) key))) return false;
            return true;
        }
        if (first instanceof NBTTagList) return sameList((NBTTagList) first, (NBTTagList) second);
        return first.equals(second);
    }

    private static boolean sameList(NBTTagList a, NBTTagList b) {
        int count = a.tagCount();
        if (count != b.tagCount()) return false;
        // The old copy/remove implementation also treated differently typed empty lists as equal.
        if (count == 0) return true;
        if (a.func_150303_d() == b.func_150303_d()) {
            switch (a.func_150303_d()) {
                case 10:
                    for (int i = count - 1; i >= 0; i--)
                        if (!same(a.getCompoundTagAt(i), b.getCompoundTagAt(i))) return false;
                    return true;
                case 5:
                    for (int i = count - 1; i >= 0; i--)
                        if (Float.floatToIntBits(a.func_150308_e(i)) != Float.floatToIntBits(b.func_150308_e(i)))
                            return false;
                    return true;
                case 6:
                    for (int i = count - 1; i >= 0; i--)
                        if (Double.doubleToLongBits(a.func_150309_d(i)) != Double.doubleToLongBits(b.func_150309_d(i)))
                            return false;
                    return true;
                case 8:
                    for (int i = count - 1; i >= 0; i--)
                        if (!Objects.equals(a.getStringTagAt(i), b.getStringTagAt(i))) return false;
                    return true;
                case 11:
                    for (int i = count - 1; i >= 0; i--)
                        if (!Arrays.equals(a.func_150306_c(i), b.func_150306_c(i))) return false;
                    return true;
                default:
                    break;
            }
        }
        // 1.7 has no public generic element getter. Keep the exact old detached-copy fallback for other list
        // types (including nested lists) and mismatched element types; never remove from either caller input.
        NBTTagList first = (NBTTagList) a.copy(), second = (NBTTagList) b.copy();
        for (int i = count - 1; i >= 0; i--) if (!same(first.removeTag(i), second.removeTag(i))) return false;
        return true;
    }
}
