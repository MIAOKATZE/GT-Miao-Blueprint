package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Per-blueprint complete node classification. Reusable flow presets never carry this review receipt. */
final class HologramQuantumGroups {

    private HologramQuantumGroups() {}

    static boolean member(HologramPresets.Cell cell, NBTTagCompound tag) {
        return !cell.excluded && (HologramNetworkBindings.node(cell.block()) || tag != null
            && (tag.hasKey(HologramNetworkBindings.GROUP) || tag.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)
                || tag.hasKey(HologramNetworkBindings.ORIGINAL)));
    }

    static NBTTagList nodes(HologramPresets.Preset preset) throws IOException {
        NBTTagList nodes = new NBTTagList();
        if (preset == null) return nodes;
        boolean confirmed = confirmed(preset);
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (!member(cell, tag)) continue;
            NBTTagCompound row = position(cell);
            row.setString("kind", HologramNetworkBindings.node(cell.block()) ? "node" : "join");
            row.setString("group", confirmed ? HologramNetworkBindings.group(tag) : "q1");
            row.setString("name", confirmed ? name(tag.getString(HologramNetworkBindings.HINT)) : "默认网络");
            nodes.appendTag(row);
        }
        return nodes;
    }

    static NBTTagList groups(HologramPresets.Preset preset) throws IOException {
        if (confirmed(preset)) return assigned(preset);
        NBTTagList cells = new NBTTagList();
        if (preset != null) for (HologramPresets.Cell cell : preset.cells)
            if (member(cell, HologramBlueprintNbt.decode(cell.tileNbt))) cells.appendTag(position(cell));
        NBTTagList groups = new NBTTagList();
        if (cells.tagCount() > 0) {
            NBTTagCompound group = new NBTTagCompound();
            group.setString("id", "q1");
            group.setString("name", "默认网络");
            group.setTag("cells", cells);
            groups.appendTag(group);
        }
        return groups;
    }

    static HologramPresets.Preset configure(HologramPresets.Preset source, NBTTagList groups) throws IOException {
        HologramPresets.Preset result = HologramPresets.copy(source);
        Map<String, HologramPresets.Cell> remaining = new LinkedHashMap<>();
        for (HologramPresets.Cell cell : result.cells) if (member(cell, HologramBlueprintNbt.decode(cell.tileNbt))) {
            if (remaining.put(key(cell.x, cell.y, cell.z), cell) != null) throw new IOException("量子节点坐标重复，不能分类");
        }
        if (groups == null || groups.tagCount() > 1024 || groups.tagCount() > 0 && groups.func_150303_d() != 10)
            throw new IOException("量子网络分类格式或数量无效");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < groups.tagCount(); i++) {
            NBTTagCompound group = groups.getCompoundTagAt(i);
            if (!group.hasKey("id", 8) || !group.getString("id")
                .matches("q[1-9][0-9]{0,5}")
                || !ids.add(group.getString("id"))
                || !group.hasKey("name", 8)
                || !group.hasKey("cells", 9)) throw new IOException("量子网络类群标识、名称或节点列表无效");
            String name = name(group.getString("name"));
            NBTTagList cells = group.getTagList("cells", 10);
            if (cells.tagCount() == 0) throw new IOException("量子网络类群不能为空");
            for (int j = 0; j < cells.tagCount(); j++) {
                NBTTagCompound row = cells.getCompoundTagAt(j);
                if (!row.hasKey("x", 3) || !row.hasKey("y", 3) || !row.hasKey("z", 3))
                    throw new IOException("量子网络分类坐标必须是整数");
                HologramPresets.Cell cell = remaining
                    .remove(key(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")));
                if (cell == null) throw new IOException("量子网络分类含重复、未知或非量子节点");
                NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
                if (tag == null) throw new IOException("量子节点缺少原生数据，不能推断分类");
                tag.setString(HologramNetworkBindings.GROUP, group.getString("id"));
                tag.setString(
                    HologramNetworkBindings.ROLE,
                    HologramNetworkBindings.node(cell.block()) ? "node" : "join");
                tag.setString(HologramNetworkBindings.HINT, name);
                cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
            }
        }
        if (!remaining.isEmpty()) throw new IOException("所有量子节点都必须分配到命名类群后才能保存");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", 1);
        receipt.setString("fingerprint", fingerprint(result));
        result.quantumGroupingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
        return result;
    }

    static boolean confirmed(HologramPresets.Preset preset) throws IOException {
        boolean hasNodes = false;
        if (preset != null) for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (!member(cell, tag)) continue;
            hasNodes = true;
            // An explicit clear (including an earlier saved clear with its old receipt) has no
            // assigned map to fingerprint. It is an unclassified source for a future binding policy.
            if (tag == null || !tag.hasKey(HologramNetworkBindings.GROUP) || !tag.hasKey(HologramNetworkBindings.HINT))
                return false;
        }
        if (!hasNodes) return true;
        if (preset.quantumGroupingNbt == null) return false;
        NBTTagCompound receipt = HologramBlueprintNbt.decode(preset.quantumGroupingNbt);
        return receipt.hasKey("version", 3) && receipt.getInteger("version") == 1
            && receipt.hasKey("fingerprint", 8)
            && receipt.getString("fingerprint")
                .equals(fingerprint(preset));
    }

    static HologramPresets.Preset defaulted(HologramPresets.Preset preset) throws IOException {
        if (confirmed(preset)) return HologramPresets.copy(preset);
        // Defaults are optional author input, not permission to erase malformed descriptors.
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (!member(cell, tag) || tag == null) continue;
            if (tag.hasKey(HologramNetworkBindings.GROUP)) HologramNetworkBindings.group(tag);
            if (tag.hasKey(HologramNetworkBindings.ROLE) && (!tag.hasKey(HologramNetworkBindings.ROLE, 8)
                || !("node".equals(tag.getString(HologramNetworkBindings.ROLE))
                    || "join".equals(tag.getString(HologramNetworkBindings.ROLE)))))
                throw new IOException("量子网络节点类型无效，不能自动分类");
            if (tag.hasKey(HologramNetworkBindings.HINT)) {
                if (!tag.hasKey(HologramNetworkBindings.HINT, 8)) throw new IOException("量子网络类群名称类型无效，不能自动分类");
                HologramNetworkBindings.checkedHint(tag.getString(HologramNetworkBindings.HINT));
            }
        }
        return configure(preset, groups(preset));
    }

    private static NBTTagList assigned(HologramPresets.Preset preset) throws IOException {
        Map<String, NBTTagCompound> groups = new TreeMap<>();
        Map<String, NBTTagCompound> ordered = new TreeMap<>();
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (!member(cell, tag)) continue;
            String id = HologramNetworkBindings.group(tag), name = name(tag.getString(HologramNetworkBindings.HINT));
            NBTTagCompound group = groups.get(id);
            if (group == null) {
                group = new NBTTagCompound();
                group.setString("id", id);
                group.setString("name", name);
                group.setTag("cells", new NBTTagList());
                groups.put(id, group);
            }
            if (!group.getString("name")
                .equals(name)) throw new IOException("同一量子类群的名称不一致");
            NBTTagCompound row = position(cell);
            row.setString("group", id);
            ordered.put(key(cell.x, cell.y, cell.z), row);
        }
        for (NBTTagCompound row : ordered.values()) {
            String id = row.getString("group");
            row.removeTag("group");
            groups.get(id)
                .getTagList("cells", 10)
                .appendTag(row);
        }
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound group : groups.values()) list.appendTag(group);
        return list;
    }

    private static String fingerprint(HologramPresets.Preset preset) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("groups", assigned(preset));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(
                    HologramBlueprintNbt.encodeData(tag)
                        .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256不可用", impossible);
        }
    }

    private static String name(String value) throws IOException {
        if (value == null || value.trim()
            .isEmpty()
            || value.length() > 64
            || value.chars()
                .anyMatch(c -> Character.isISOControl(c) || c == 167))
            throw new IOException("量子类群名称须为1至64个普通字符");
        return value.trim();
    }

    private static NBTTagCompound position(HologramPresets.Cell cell) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", cell.x);
        row.setInteger("y", cell.y);
        row.setInteger("z", cell.z);
        return row;
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
