package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.ResourceContainers;

/** Audited native data schemas, selected by complete typed records, never by a mod ID or machine number. */
final class HologramNativeHubData {

    static final String HUB = "gtsr.hub", LIST = "gtsr.nodes.list", MAP = "gtsr.nodes.map";
    static final String HUB_POS = "gtsr.hubPos", NODES = "mBoundNodes", TYPE = "mStoredFluidType";
    private static final int MAX_BINDINGS = 8192;

    private HologramNativeHubData() {}

    static final class Fluid {

        final String key;
        final ResourceContainers.FluidAmount value;

        Fluid(String key, ResourceContainers.FluidAmount value) {
            this.key = key;
            this.value = value;
        }
    }

    static Fluid fluid(NBTTagCompound tag) throws IOException {
        boolean steam = tag.hasKey("mSteamStored"), water = tag.hasKey("mWaterStored");
        if (steam && water) throw invalid("conflicting split fluid quantities");
        if (!steam && !water && !tag.hasKey(TYPE)) return null;
        if (!steam && !water) {
            if (!tag.hasKey(TYPE, 8) && !tag.hasKey(TYPE, 10)) throw invalid("invalid fluid type lock");
            water = tag.hasKey(TYPE, 8);
        }
        String key = water ? "mWaterStored" : "mSteamStored";
        return new Fluid(key, ResourceContainers.splitFluidAmount(tag, key, TYPE, water));
    }

    static boolean fluidKey(String key) {
        return "mSteamStored".equals(key) || "mWaterStored".equals(key) || TYPE.equals(key);
    }

    static final class Binding {

        final String adapter;
        final int slot;
        final NBTTagCompound target;

        Binding(String adapter, int slot, NBTTagCompound target) {
            this.adapter = adapter;
            this.slot = slot;
            this.target = target;
        }
    }

    /** Only actual native root bindings are inspected; nested item tags and terminal GUI state are not searched. */
    static List<Binding> bindings(NBTTagCompound tag) throws IOException {
        if (!tag.hasKey(HUB_POS) && !tag.hasKey(NODES)) return java.util.Collections.emptyList();
        List<Binding> result = new ArrayList<>();
        if (tag.hasKey(HUB_POS)) {
            if (!tag.hasKey(HUB_POS, 10)) throw invalid("hub position must be a compound");
            NBTTagCompound row = tag.getCompoundTag(HUB_POS);
            tuple(row, true, "type", "output", "reinforced");
            if (!row.hasKey("type", 8)) throw invalid("hub type must be a string");
            result.add(new Binding(HUB, -1, row));
        }
        if (!tag.hasKey(NODES)) return result;
        if (tag.hasKey(NODES, 9)) {
            NBTTagList rows = (NBTTagList) tag.getTag(NODES);
            if (rows.tagCount() > MAX_BINDINGS || rows.tagCount() > 0 && rows.func_150303_d() != 10)
                throw invalid("invalid bound-node list");
            for (int index = 0; index < rows.tagCount(); index++) {
                NBTTagCompound row = rows.getCompoundTagAt(index);
                tuple(row, true, "reinforced", "outputMode", "miner", "active");
                // Steam-array and remote-worker records are separate native contracts, not a mixed union.
                boolean steam = row.hasKey("reinforced") || row.hasKey("outputMode");
                if (steam
                    ? !row.hasKey("reinforced", 1) || !row.hasKey("outputMode", 1)
                        || row.hasKey("miner")
                        || row.hasKey("active")
                    : !row.hasKey("miner", 1) || !row.hasKey("active", 1))
                    throw invalid("incomplete or mixed bound-node record");
                result.add(new Binding(LIST, index, row));
            }
        } else if (tag.hasKey(NODES, 10)) {
            NBTTagCompound rows = tag.getCompoundTag(NODES);
            if (!rows.hasKey("count", 3)) throw invalid("bound-node count must be an integer");
            int count = rows.getInteger("count");
            if (count < 0 || count > MAX_BINDINGS
                || rows.func_150296_c()
                    .size() != count + 1)
                throw invalid("invalid bound-node count or unknown field");
            for (int index = 0; index < count; index++) {
                if (!rows.hasKey("node" + index, 10)) throw invalid("noncontiguous bound-node slots");
                NBTTagCompound row = rows.getCompoundTag("node" + index);
                tuple(row, true, "out");
                if (!row.hasKey("out", 1)) throw invalid("missing bound-node mode");
                result.add(new Binding(MAP, index, row));
            }
        } else throw invalid("unknown bound-node storage format");
        return result;
    }

    private static void tuple(NBTTagCompound row, boolean coordinates, String... fields) throws IOException {
        if (coordinates) for (String axis : new String[] { "x", "y", "z", "dim" })
            if (!row.hasKey(axis, 3)) throw invalid("incomplete coordinate tuple");
        for (Object raw : row.func_150296_c()) {
            String key = (String) raw;
            if (Arrays.asList("x", "y", "z", "dim")
                .contains(key)) continue;
            if (!Arrays.asList(fields)
                .contains(key)) throw invalid("unparsed binding field " + key);
            if ("type".equals(key)) {
                if (!row.hasKey(key, 8)) throw invalid("invalid binding type");
            } else if (!row.hasKey(key, 1) || row.getByte(key) < 0 || row.getByte(key) > 1)
                throw invalid("invalid binding mode " + key);
        }
    }

    static boolean adapter(String adapter) {
        return HUB.equals(adapter) || LIST.equals(adapter) || MAP.equals(adapter);
    }

    static boolean bindingKey(String key) {
        return HUB_POS.equals(key) || NODES.equals(key);
    }

    static NBTTagCompound target(NBTTagCompound tag, String adapter, int slot) throws IOException {
        if (HUB.equals(adapter)) return tag.hasKey(HUB_POS, 10) ? tag.getCompoundTag(HUB_POS) : null;
        if (LIST.equals(adapter)) {
            if (!tag.hasKey(NODES)) return null;
            if (!tag.hasKey(NODES, 9)) throw invalid("bound-node list schema changed");
            NBTTagList rows = (NBTTagList) tag.getTag(NODES);
            if (rows.func_150303_d() != 10 || slot >= rows.tagCount()) throw invalid("bound-node slot changed");
            return rows.getCompoundTagAt(slot);
        }
        if (MAP.equals(adapter)) {
            if (!tag.hasKey(NODES)) return null;
            if (!tag.hasKey(NODES, 10)) throw invalid("bound-node map schema changed");
            NBTTagCompound rows = tag.getCompoundTag(NODES);
            if (!rows.hasKey("node" + slot, 10)) throw invalid("bound-node slot changed");
            return rows.getCompoundTag("node" + slot);
        }
        throw invalid("unknown binding adapter");
    }

    static void clear(NBTTagCompound tag, String adapter) {
        tag.removeTag(HUB.equals(adapter) ? HUB_POS : NODES);
    }

    private static IOException invalid(String detail) {
        return new IOException("无法完整解析枢纽/节点NBT：" + detail + "；请移除对应信息后保存");
    }
}
