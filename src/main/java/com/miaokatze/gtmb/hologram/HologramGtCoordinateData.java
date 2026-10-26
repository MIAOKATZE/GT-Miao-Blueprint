package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

/** GT5U .205 native link schemas. Does not traverse inventories or infer relationships from key names. */
final class HologramGtCoordinateData {

    static final String UNIT = "gt.purification.controller", PCB = "gt.pcb.controllers", BIO = "gt.pcb.bio",
        COOLING = "gt.pcb.cooling";
    private static final String MULTI = "gregtech.common.tileentities.machines.multi.";

    private HologramGtCoordinateData() {}

    static final class Binding {

        final String adapter;
        final int slot, x, y, z;

        Binding(String adapter, int slot, int x, int y, int z) {
            this.adapter = adapter;
            this.slot = slot;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static boolean adapter(String adapter) {
        return UNIT.equals(adapter) || PCB.equals(adapter) || BIO.equals(adapter) || COOLING.equals(adapter);
    }

    static boolean validSlot(String adapter, int slot) {
        return PCB.equals(adapter) ? slot >= 0 && slot < 8192 : slot == -1;
    }

    private static boolean supports(NBTTagCompound tag, String adapter) {
        return supports(tag, adapter, prototypeType(tag));
    }

    private static Class<?> prototypeType(NBTTagCompound tag) {
        if (tag == null || !"BaseMetaTileEntity".equals(tag.getString("id"))
            || !(tag.hasKey("mID", 2) || tag.hasKey("mID", 3))) return null;
        int id = tag.getInteger("mID");
        Object prototype = id >= 0 && id < gregtech.api.GregTechAPI.METATILEENTITIES.length
            ? gregtech.api.GregTechAPI.METATILEENTITIES[id]
            : null;
        return prototype == null ? null : prototype.getClass();
    }

    private static boolean supports(NBTTagCompound tag, String adapter, Class<?> sourceType) {
        if (tag == null || !"BaseMetaTileEntity".equals(tag.getString("id"))) return false;
        String expected = UNIT.equals(adapter) ? MULTI + "purification.MTEPurificationUnitBase"
            : PCB.equals(adapter) ? MULTI + "pcb.MTEPCBUpgradeBase" : MULTI + "pcb.MTEPCBFactory";
        for (Class<?> type = sourceType; type != null; type = type.getSuperclass())
            if (expected.equals(type.getName())) return true;
        return false;
    }

    private static String key(String adapter) {
        return UNIT.equals(adapter) ? "controller" : BIO.equals(adapter) ? "mBioChamber" : "mCoolingTower";
    }

    static List<Binding> bindings(NBTTagCompound tag) {
        return bindings(tag, prototypeType(tag));
    }

    /** Exact resolved native source class, also usable without initializing its eager structure singleton. */
    static List<Binding> bindings(NBTTagCompound tag, Class<?> sourceType) {
        List<Binding> result = new ArrayList<>();
        for (String adapter : new String[] { UNIT, BIO, COOLING }) {
            if (!supports(tag, adapter, sourceType) || !tag.hasKey(key(adapter), 10)) continue;
            NBTTagCompound target = tag.getCompoundTag(key(adapter));
            if (target.hasKey("x", 3) && target.hasKey("y", 3) && target.hasKey("z", 3)) result
                .add(new Binding(adapter, -1, target.getInteger("x"), target.getInteger("y"), target.getInteger("z")));
        }
        if (supports(tag, PCB, sourceType) && tag.hasKey("controllers", 11)) {
            int[] values = tag.getIntArray("controllers");
            if (values.length % 3 == 0 && values.length / 3 <= 8192) for (int i = 0; i < values.length; i += 3)
                result.add(new Binding(PCB, i / 3, values[i], values[i + 1], values[i + 2]));
        }
        return result;
    }

    static boolean present(NBTTagCompound tag, String adapter, int slot) throws IOException {
        check(tag, adapter, slot);
        if (!PCB.equals(adapter)) return tag.hasKey(key(adapter), 10);
        return tag.hasKey("controllers", 11) && tag.getIntArray("controllers").length % 3 == 0
            && slot < tag.getIntArray("controllers").length / 3;
    }

    private static void check(NBTTagCompound tag, String adapter, int slot) throws IOException {
        if (!adapter(adapter) || !validSlot(adapter, slot) || !supports(tag, adapter))
            throw new IOException("GT坐标关系宿主或类型不匹配");
    }

    static void write(NBTTagCompound tag, String adapter, int slot, int x, int y, int z) throws IOException {
        if (!present(tag, adapter, slot)) return;
        if (PCB.equals(adapter)) {
            int[] values = tag.getIntArray("controllers")
                .clone();
            values[slot * 3] = x;
            values[slot * 3 + 1] = y;
            values[slot * 3 + 2] = z;
            tag.setIntArray("controllers", values);
        } else {
            NBTTagCompound target = tag.getCompoundTag(key(adapter));
            target.setInteger("x", x);
            target.setInteger("y", y);
            target.setInteger("z", z);
        }
    }

    static void scrub(NBTTagCompound tag, String adapter, int slot, boolean clear) throws IOException {
        if (!present(tag, adapter, slot)) return;
        if (PCB.equals(adapter)) {
            // Preserve descriptor indices until placement has resolved all list entries.
            write(tag, adapter, slot, 0, 0, 0);
        } else {
            NBTTagCompound target = tag.getCompoundTag(key(adapter));
            if (clear) for (Object raw : target.func_150296_c()) if (!java.util.Arrays.asList("x", "y", "z")
                .contains(raw)) throw new IOException("GT链接含未知附加字段，不能安全清除");
            target.removeTag("x");
            target.removeTag("y");
            target.removeTag("z");
        }
    }

    static void clear(NBTTagCompound tag, String adapter, int slot) throws IOException {
        scrub(tag, adapter, slot, true);
        if (!PCB.equals(adapter)) tag.removeTag(key(adapter));
    }

    static void removeClearedControllers(NBTTagCompound tag, BitSet removed) throws IOException {
        if (removed.isEmpty()) return;
        check(tag, PCB, removed.nextSetBit(0));
        int[] values = tag.getIntArray("controllers");
        if (values.length % 3 != 0 || removed.length() > values.length / 3) throw new IOException("GT链接列表与清除记录不匹配");
        int[] result = new int[values.length - removed.cardinality() * 3];
        for (int slot = 0, out = 0; slot < values.length / 3; slot++) if (!removed.get(slot)) {
            System.arraycopy(values, slot * 3, result, out, 3);
            out += 3;
        }
        if (result.length == 0) tag.removeTag("controllers");
        else tag.setIntArray("controllers", result);
    }
}
