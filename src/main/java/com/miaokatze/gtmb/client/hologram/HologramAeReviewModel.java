package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Detached presentation only. The server validates every save and container request again. */
final class HologramAeReviewModel {

    static final int MAX_ISSUES = 8192;
    final String session, transactionId, name;
    final long revision;
    final boolean review, valid, canConfirm;
    final int warningCount, blockerCount;
    final List<Issue> issues;
    final List<Group> groups;
    final List<Material> materials;

    HologramAeReviewModel(NBTTagCompound source, boolean review) {
        NBTTagCompound tag = (NBTTagCompound) source.copy();
        this.review = review;
        session = plain(tag.getString("session"), 128);
        transactionId = plain(tag.getString("transactionId"), 128);
        name = plain(tag.getString("name"), 128);
        revision = tag.getLong("planRevision");
        NBTTagList rows = tag.getTagList("issues", 10);
        List<Issue> parsed = new ArrayList<>();
        int warnings = 0, blockers = 0;
        boolean shape = rows.tagCount() <= MAX_ISSUES;
        if (shape) for (int i = 0; i < rows.tagCount(); i++) {
            Issue issue = new Issue(rows.getCompoundTagAt(i));
            parsed.add(issue);
            shape &= issue.valid;
            if ("warning".equals(issue.severity)) warnings++;
            if ("blocker".equals(issue.severity)) blockers++;
        }
        if (review) shape &= !session.isEmpty() && !transactionId.isEmpty()
            && tag.hasKey("planRevision", 4)
            && tag.getInteger("issueCount") == parsed.size()
            && tag.getInteger("warningCount") == warnings
            && tag.getInteger("blockerCount") == blockers;
        valid = shape;
        canConfirm = review && valid && blockers == 0 && tag.getBoolean("canConfirm");
        warningCount = warnings;
        blockerCount = blockers;
        issues = Collections.unmodifiableList(parsed);
        groups = Collections.unmodifiableList(group(parsed));
        List<Material> costs = new ArrayList<>();
        rows = tag.getTagList("materials", 10);
        for (int i = 0; i < rows.tagCount(); i++) costs.add(new Material(rows.getCompoundTagAt(i)));
        materials = Collections.unmodifiableList(costs);
    }

    static HologramAeReviewModel inspection(HologramState state) {
        NBTTagCompound dto = new NBTTagCompound();
        dto.setString("session", state.session);
        dto.setLong("planRevision", state.data.getLong("planRevision"));
        dto.setTag(
            "issues",
            state.data.getTagList("aeIssues", 10)
                .copy());
        dto.setTag(
            "materials",
            state.data.getTagList("materials", 10)
                .copy());
        return new HologramAeReviewModel(dto, false);
    }

    boolean matches(String session, long revision) {
        return valid && this.session.equals(session) && this.revision == revision;
    }

    NBTTagCompound action() {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", session);
        request.setLong("planRevision", revision);
        request.setString("transactionId", transactionId);
        return request;
    }

    private static List<Group> group(List<Issue> issues) {
        Map<String, Group> result = new LinkedHashMap<>();
        for (Issue issue : issues) {
            String key = issue.severity + ":" + issue.code;
            Group group = result.get(key);
            if (group == null) {
                group = new Group(issue);
                result.put(key, group);
            }
            group.rows.add(issue);
            group.count = issue.count > Long.MAX_VALUE - group.count ? Long.MAX_VALUE : group.count + issue.count;
        }
        return new ArrayList<>(result.values());
    }

    static String plain(String value, int limit) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < limit; i++) {
            char c = value.charAt(i);
            if (c == '\u00a7') {
                if (i + 1 < value.length()) i++;
                continue;
            }
            if (c == '\n' || c == '\t' || c >= 32 && c != 127) result.append(c);
        }
        return result.toString();
    }

    static final class Group {

        final String code, severity;
        final List<Issue> rows = new ArrayList<>();
        long count;

        Group(Issue issue) {
            code = issue.code;
            severity = issue.severity;
        }

        String title() {
            return rows.get(0)
                .title();
        }
    }

    static final class Issue {

        final String code, severity, path, message, structureId, slotId, resolution;
        final int x, y, z;
        final long count;
        final boolean valid, location;

        Issue(NBTTagCompound tag) {
            code = plain(tag.getString("code"), 96);
            severity = plain(tag.getString("severity"), 16);
            path = plain(tag.getString("path"), 256);
            message = plain(tag.getString("message"), 256);
            structureId = plain(tag.getString("structureId"), 128);
            slotId = plain(tag.getString("slotId"), 128);
            resolution = plain(tag.getString("resolution"), 96);
            x = tag.getInteger("x");
            y = tag.getInteger("y");
            z = tag.getInteger("z");
            count = tag.getLong("count");
            location = tag.hasKey("x", 3) && tag.hasKey("y", 3) && tag.hasKey("z", 3);
            valid = count > 0 && code.matches("[A-Za-z0-9_.-]{1,96}")
                && ("info".equals(severity) || "warning".equals(severity) || "blocker".equals(severity));
        }

        String title() {
            String shortCode = code.startsWith("ae.") ? code.substring(3) : code;
            switch (shortCode) {
                case "cell_contents_cleared":
                    return "存储元件内容清空";
                case "addon_cell_blanked":
                case "addon_cell_blank_type":
                    return "扩展存储元件空壳";
                case "p2p_rebind":
                    return "P2P 隧道需要重新绑定";
                case "node_reset":
                case "network_reset":
                    return "旧网络身份不继承";
                case "mark.missing_material":
                    return "施工材料尚不足（可保存）";
                case "slot_overflow":
                    return "标记数量超出槽位上限";
                case "hidden_pattern":
                    return "禁用配方槽仍有物理标记";
                case "unsupported_host":
                    return "未支持的 AE 主机配置";
                case "unsupported_part":
                    return "未支持的 AE 部件配置";
                case "invalid_upgrade":
                case "cell_upgrade_invalid":
                    return "升级卡标记无效";
                case "cell_partition_invalid":
                    return "存储元件分区标记无效";
                case "missing_physical_item":
                    return "物理物品标记不可读取";
                case "unknown_inventory_slot":
                    return "未支持的库存槽标记";
                case "cell_unknown_data":
                    return "元件未知数据不保留";
                case "cpu_runtime_omitted":
                    return "CPU 运行数据需要明确清除";
                default:
                    String key = "gtmb.ae.issue." + shortCode;
                    String localized = net.minecraft.util.StatCollector.translateToLocal(key);
                    return key.equals(localized) ? "AE 提示 · " + shortCode : plain(localized, 128);
            }
        }

        String guidance() {
            if ("replaceCell".equals(resolution)) return "替换或删除这个蓝图方块，然后重新保存。";
            if ("discardOmitted".equals(resolution)) return "在相关容器的只读问题槽明确清除，即确认丢弃未支持标记；然后重新保存。";
            if ("clearSlot".equals(resolution) || !slotId.isEmpty()) return "打开相关容器，补正标记或明确清除对应槽；然后重新保存。";
            return "blocker".equals(severity) ? "打开相关容器修正 / 清除；不支持的主机请替换或删除。"
                : "warning".equals(severity) ? "此项会随蓝图保存；确认已了解上述变化。" : "配置与物理物料按服务端报告处理。";
        }
    }

    static final class Material {

        final String key, type, name, required, available, missing;

        Material(NBTTagCompound tag) {
            key = plain(tag.getString("key"), 256);
            type = plain(tag.getString("type"), 16);
            name = plain(tag.getString("name"), 256);
            required = plain(tag.getString("required"), 64);
            available = plain(tag.getString("available"), 64);
            missing = plain(tag.getString("missing"), 64);
        }

        String line() {
            return name + " · 需 " + required + " / 有 " + available + " / 缺 " + missing;
        }
    }

    /** Nonce bookkeeping suppresses duplicate UI openings only; it never grants authority. */
    static final class Gate {

        private final Map<String, java.util.Set<String>> seen = new LinkedHashMap<>();

        boolean first(HologramAeReviewModel model) {
            if (!model.review || model.transactionId.isEmpty()) return false;
            java.util.Set<String> ids = seen.get(model.session);
            if (ids == null) {
                ids = new java.util.LinkedHashSet<>();
                seen.put(model.session, ids);
            }
            boolean fresh = ids.add(model.transactionId);
            while (seen.size() > 32) seen.remove(
                seen.keySet()
                    .iterator()
                    .next());
            return fresh;
        }
    }
}
