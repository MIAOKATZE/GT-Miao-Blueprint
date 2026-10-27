package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Differential equivalence to the pre-refactor Cut comparator, not vanilla NBT.equals. */
final class HologramCutNbtComparisonRegression {

    private HologramCutNbtComparisonRegression() {}

    static void run() {
        List<NBTBase> values = new ArrayList<>();
        values.add(null);
        values.add(new NBTTagByte((byte) 1));
        values.add(new NBTTagShort((short) 2));
        values.add(new NBTTagInt(3));
        values.add(new NBTTagLong(4));
        for (float number : new float[] { 0.0F, -0.0F, 1.0F, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.NaN, Float.intBitsToFloat(0x7f800001), Float.intBitsToFloat(0xffc00001) })
            values.add(new NBTTagFloat(number));
        for (double number : new double[] { 0.0D, -0.0D, 1.0D, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.NaN, Double.longBitsToDouble(0x7ff0000000000001L), Double.longBitsToDouble(0xfff8000000000001L) })
            values.add(new NBTTagDouble(number));
        values.add(new NBTTagByteArray(new byte[] { 1, -2 }));
        values.add(new NBTTagString("private-value"));
        values.add(new NBTTagString("different-value"));
        values.add(new NBTTagIntArray(new int[] { 1, -2 }));
        values.add(new NBTTagIntArray(new int[] { 1, 2 }));
        NBTTagCompound compound = new NBTTagCompound();
        compound.setFloat("float", Float.NaN);
        compound.setDouble("zero", -0.0D);
        values.add(compound);
        NBTTagCompound changed = (NBTTagCompound) compound.copy();
        changed.setDouble("zero", 0.0D);
        values.add(changed);
        int scalarCount = values.size();
        for (int i = 1; i < scalarCount; i++) {
            NBTTagList list = new NBTTagList();
            list.appendTag(
                values.get(i)
                    .copy());
            list.appendTag(
                values.get(i)
                    .copy());
            values.add(list);
            NBTTagList nested = new NBTTagList();
            nested.appendTag(list.copy());
            values.add(nested);
            NBTTagList typedEmpty = (NBTTagList) list.copy();
            typedEmpty.removeTag(1);
            typedEmpty.removeTag(0);
            values.add(typedEmpty);
        }
        NBTTagList ordered = new NBTTagList(), reversed = new NBTTagList(), shorter = new NBTTagList();
        ordered.appendTag(new NBTTagString("first"));
        ordered.appendTag(new NBTTagString("second"));
        reversed.appendTag(new NBTTagString("second"));
        reversed.appendTag(new NBTTagString("first"));
        shorter.appendTag(new NBTTagString("first"));
        values.add(ordered);
        values.add(reversed);
        values.add(shorter);
        values.add(new NBTTagList());
        for (NBTBase a : values) for (NBTBase b : values) {
            NBTBase beforeA = a == null ? null : a.copy(), beforeB = b == null ? null : b.copy();
            SimpleAssert
                .that(legacy(a, b) == HologramCutNbtComparison.same(a, b), "cut comparator preserves legacy result");
            SimpleAssert.that(legacy(a, beforeA) && legacy(b, beforeB), "comparison never mutates caller NBT");
        }
        NBTTagList a = new NBTTagList(), b = new NBTTagList();
        CountingCompound first = new CountingCompound(), second = new CountingCompound();
        first.setInteger("inventory", 12);
        second.setInteger("inventory", 12);
        a.appendTag(first);
        b.appendTag(second);
        SimpleAssert.that(HologramCutNbtComparison.same(a, b), "compound list values agree");
        SimpleAssert
            .that(first.copies == 0 && second.copies == 0, "typed compound comparison avoids duplicate deep copies");
        second.setInteger("inventory", 13);
        SimpleAssert.that(!HologramCutNbtComparison.same(a, b), "equal block type does not hide changed inventory");
        SimpleAssert.that(first.copies == 0 && second.copies == 0, "failed typed comparison also avoids copies");
    }

    private static final class CountingCompound extends NBTTagCompound {

        int copies;

        @Override
        public NBTBase copy() {
            copies++;
            return super.copy();
        }
    }

    /** Frozen old implementation is the oracle, including NaN, signed-zero and empty-list behavior. */
    private static boolean legacy(NBTBase first, NBTBase second) {
        if (first == second) return true;
        if (first == null || second == null || first.getId() != second.getId()) return false;
        if (first instanceof NBTTagFloat) return Float.floatToIntBits(((NBTTagFloat) first).func_150288_h())
            == Float.floatToIntBits(((NBTTagFloat) second).func_150288_h());
        if (first instanceof NBTTagDouble) return Double.doubleToLongBits(((NBTTagDouble) first).func_150286_g())
            == Double.doubleToLongBits(((NBTTagDouble) second).func_150286_g());
        if (first instanceof NBTTagCompound) {
            NBTTagCompound a = (NBTTagCompound) first, b = (NBTTagCompound) second;
            if (!a.func_150296_c()
                .equals(b.func_150296_c())) return false;
            for (Object key : a.func_150296_c())
                if (!legacy(a.getTag((String) key), b.getTag((String) key))) return false;
            return true;
        }
        if (first instanceof NBTTagList) {
            NBTTagList a = (NBTTagList) first.copy(), b = (NBTTagList) second.copy();
            if (a.tagCount() != b.tagCount()) return false;
            for (int i = a.tagCount() - 1; i >= 0; i--) if (!legacy(a.removeTag(i), b.removeTag(i))) return false;
            return true;
        }
        return first.equals(second);
    }
}
