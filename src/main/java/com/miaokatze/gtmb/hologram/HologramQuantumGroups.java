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
        return analyze(preset, new HologramAnalysisFacts()).nodes();
    }

    static NBTTagList groups(HologramPresets.Preset preset) throws IOException {
        return analyze(preset, new HologramAnalysisFacts()).groups();
    }

    static Facts analyze(HologramPresets.Preset preset, HologramAnalysisFacts facts) throws IOException {
        return new Facts(preset, facts);
    }

    /** Small membership facts only; no decoded whole-blueprint NBT or validity flag is retained. */
    static final class Facts {

        private final java.util.List<Node> members = new java.util.ArrayList<>();
        private final HologramAnalysisFacts facts;
        private final String receipt;
        private boolean allAssigned = true;
        private Boolean confirmed;
        private NBTTagList assigned;
        private String fingerprint;

        private Facts(HologramPresets.Preset preset, HologramAnalysisFacts facts) throws IOException {
            this.facts = facts;
            receipt = preset == null ? null : preset.quantumGroupingNbt;
            if (preset == null) return;
            for (HologramPresets.Cell cell : preset.cells) {
                NBTTagCompound entry = facts.certificate(
                    cell,
                    HologramSegmentContract.Domain.RELATIONS,
                    "quantum-member",
                    new Object[] { cell.block(), cell.excluded, HologramSegmentContract.SCHEMA_EPOCH },
                    true,
                    () -> membership(cell, facts));
                facts.networkNode(cell, entry);
                if (!entry.getBoolean("member")) continue;
                NBTTagCompound descriptor = entry.getCompoundTag("descriptor");
                allAssigned &= descriptor.hasKey(HologramNetworkBindings.GROUP)
                    && descriptor.hasKey(HologramNetworkBindings.HINT);
                members.add(new Node(cell, descriptor));
            }
            facts.networks();
        }

        private static NBTTagCompound membership(HologramPresets.Cell cell, HologramAnalysisFacts facts)
            throws IOException {
            NBTTagCompound tag = facts.read(cell.tileNbt), result = new NBTTagCompound();
            result.setBoolean("member", member(cell, tag));
            result.setBoolean("ae", !cell.excluded && HologramAeBlueprint.supports(cell.block(), tag));
            NBTTagCompound descriptor = new NBTTagCompound();
            if (tag != null && result.getBoolean("member")) {
                for (String key : new String[] { HologramNetworkBindings.GROUP, HologramNetworkBindings.HINT })
                    if (tag.hasKey(key)) {
                        if (tag.hasKey(key, 8)) descriptor.setString(key, tag.getString(key));
                        else descriptor.setInteger(key, 0);
                    }
                descriptor.setString(HologramNetworkBindings.ROLE, tag.getString(HologramNetworkBindings.ROLE));
            }
            result.setTag("descriptor", descriptor);
            return result;
        }

        boolean confirmed() throws IOException {
            if (confirmed != null) return confirmed;
            if (members.isEmpty()) return confirmed = true;
            if (!allAssigned || receipt == null) return confirmed = false;
            NBTTagCompound tag = facts.read(receipt);
            return confirmed = tag.hasKey("version", 3) && tag.getInteger("version") == 1
                && tag.hasKey("fingerprint", 8)
                && tag.getString("fingerprint")
                    .equals(fingerprint());
        }

        NBTTagList nodes() throws IOException {
            boolean reviewed = confirmed();
            NBTTagList result = new NBTTagList();
            for (Node node : members) {
                NBTTagCompound row = (NBTTagCompound) node.position.copy();
                row.setString("kind", node.kind);
                row.setString("group", reviewed ? HologramNetworkBindings.group(node.descriptor) : "q1");
                row.setString(
                    "name",
                    reviewed ? name(node.descriptor.getString(HologramNetworkBindings.HINT)) : "默认网络");
                result.appendTag(row);
            }
            return result;
        }

        NBTTagList groups() throws IOException {
            if (confirmed()) return (NBTTagList) assigned().copy();
            NBTTagList result = new NBTTagList();
            if (members.isEmpty()) return result;
            NBTTagList cells = new NBTTagList();
            for (Node node : members) cells.appendTag(node.position.copy());
            NBTTagCompound group = new NBTTagCompound();
            group.setString("id", "q1");
            group.setString("name", "默认网络");
            group.setTag("cells", cells);
            result.appendTag(group);
            return result;
        }

        private NBTTagList assigned() throws IOException {
            if (assigned != null) return assigned;
            Map<String, NBTTagCompound> groups = new TreeMap<>();
            Map<String, NBTTagCompound> ordered = new TreeMap<>();
            for (Node node : members) {
                String id = HologramNetworkBindings.group(node.descriptor);
                String name = name(node.descriptor.getString(HologramNetworkBindings.HINT));
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
                NBTTagCompound row = (NBTTagCompound) node.position.copy();
                row.setString("group", id);
                ordered.put(key(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")), row);
            }
            for (NBTTagCompound row : ordered.values()) {
                String id = row.getString("group");
                row.removeTag("group");
                groups.get(id)
                    .getTagList("cells", 10)
                    .appendTag(row);
            }
            NBTTagList result = new NBTTagList();
            for (NBTTagCompound group : groups.values()) result.appendTag(group);
            assigned = result;
            return result;
        }

        private String fingerprint() throws IOException {
            if (fingerprint == null)
                fingerprint = facts.fingerprint(() -> HologramQuantumGroups.fingerprint(assigned()));
            return fingerprint;
        }
    }

    private static final class Node {

        final NBTTagCompound position, descriptor;
        final String kind;

        Node(HologramPresets.Cell cell, NBTTagCompound descriptor) {
            position = position(cell);
            this.descriptor = descriptor;
            kind = HologramNetworkBindings.node(cell.block()) ? "node" : "join";
        }
    }

    static HologramPresets.Preset configure(HologramPresets.Preset source, NBTTagList groups) throws IOException {
        HologramPresets.Preset result = HologramPresets.copy(source);
        HologramAnalysisFacts facts = new HologramAnalysisFacts();
        Map<String, HologramPresets.Cell> remaining = new LinkedHashMap<>();
        for (HologramPresets.Cell cell : result.cells) if (member(cell, facts.read(cell.tileNbt))) {
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
                NBTTagCompound before = facts.read(cell.tileNbt);
                NBTTagCompound tag = before == null ? null : (NBTTagCompound) before.copy();
                if (tag == null) throw new IOException("量子节点缺少原生数据，不能推断分类");
                tag.setString(HologramNetworkBindings.GROUP, group.getString("id"));
                tag.setString(
                    HologramNetworkBindings.ROLE,
                    HologramNetworkBindings.node(cell.block()) ? "node" : "join");
                tag.setString(HologramNetworkBindings.HINT, name);
                if (!before.equals(tag)) cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
            }
        }
        if (!remaining.isEmpty()) throw new IOException("所有量子节点都必须分配到命名类群后才能保存");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", 1);
        receipt.setString("fingerprint", analyze(result, facts).fingerprint());
        result.quantumGroupingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
        return result;
    }

    static boolean confirmed(HologramPresets.Preset preset) throws IOException {
        return analyze(preset, new HologramAnalysisFacts()).confirmed();
    }

    static HologramPresets.Preset defaulted(HologramPresets.Preset preset) throws IOException {
        return defaulted(preset, new HologramAnalysisFacts());
    }

    static HologramPresets.Preset defaulted(HologramPresets.Preset preset, HologramAnalysisFacts facts)
        throws IOException {
        Facts quantum = analyze(preset, facts);
        if (quantum.confirmed()) return HologramPresets.copy(preset);
        // Defaults are optional author input, not permission to erase malformed descriptors.
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = facts.read(cell.tileNbt);
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
        return configure(preset, quantum.groups());
    }

    private static String fingerprint(NBTTagList groups) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("groups", groups);
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
