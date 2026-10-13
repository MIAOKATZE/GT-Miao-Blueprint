package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Only the bounded, server-issued display projection. No tile, native inventory or arbitrary NBT path. */
final class HologramAePreviewModel {

    static final int MAX_SLOTS = 512;
    static final int MAX_ISSUES = 8192;
    final String session, token, block, structureId;
    final int x, y, z, metadata;
    final long revision;
    final List<MarkSlot> slots;
    final List<String> issues;

    HologramAePreviewModel(NBTTagCompound snapshot) {
        session = snapshot.getString("session");
        token = snapshot.getString("token");
        block = snapshot.getString("block");
        structureId = snapshot.getString("structureId");
        x = snapshot.getInteger("x");
        y = snapshot.getInteger("y");
        z = snapshot.getInteger("z");
        metadata = snapshot.getInteger("metadata");
        revision = snapshot.getLong("planRevision");
        List<MarkSlot> parsed = new ArrayList<>();
        NBTTagList entries = snapshot.getTagList("slots", 10);
        if (entries.tagCount() > MAX_SLOTS) throw new IllegalArgumentException("AE preview slot budget exceeded");
        for (int i = 0; i < entries.tagCount(); i++) parsed.add(new MarkSlot(entries.getCompoundTagAt(i)));
        slots = Collections.unmodifiableList(parsed);
        List<String> warnings = new ArrayList<>();
        NBTTagList issueTags = snapshot.getTagList("issues", 10);
        if (issueTags.tagCount() > MAX_ISSUES) throw new IllegalArgumentException("AE preview issue budget exceeded");
        for (int i = 0; i < issueTags.tagCount(); i++) {
            NBTTagCompound issue = issueTags.getCompoundTagAt(i);
            HologramAeReviewModel.Issue detail = new HologramAeReviewModel.Issue(issue);
            warnings.add(
                HologramUiText.severity(
                    detail.severity) + " · " + detail.title() + " · " + detail.path + "：" + detail.explanation());
        }
        issues = Collections.unmodifiableList(warnings);
    }

    NBTTagCompound action(String operation) {
        NBTTagCompound out = new NBTTagCompound();
        out.setString("op", operation);
        out.setString("session", session);
        out.setString("token", token);
        out.setString("structureId", structureId);
        out.setLong("planRevision", revision);
        out.setInteger("x", x);
        out.setInteger("y", y);
        out.setInteger("z", z);
        return out;
    }

    static final class MarkSlot {

        final String id, kind, label, allowed, issueCode, issuePath;
        final int limit;
        final boolean enabled;
        private final ItemStack icon;

        MarkSlot(NBTTagCompound n) {
            id = n.getString("id");
            kind = n.getString("kind");
            label = n.getString("label");
            issueCode = n.getString("issueCode");
            issuePath = n.getString("issuePath");
            NBTTagList allowedTags = n.getTagList("allowed", 8);
            List<String> allowedNames = new ArrayList<>();
            for (int i = 0; i < Math.min(allowedTags.tagCount(), 32); i++)
                allowedNames.add(allowedTags.getStringTagAt(i));
            allowed = allowedNames.isEmpty() ? n.getString("allowed") : String.join(" / ", allowedNames);
            limit = n.getInteger("limit");
            enabled = n.getBoolean("enabled") && limit > 0 && limit <= 64 && !"readonly".equals(kind);
            ItemStack parsed = ItemStack.loadItemStackFromNBT(n.getCompoundTag("stack"));
            icon = parsed == null ? null : parsed.copy();
        }

        ItemStack stack() {
            return icon == null ? null : icon.copy();
        }

        ItemStack candidate(ItemStack source) {
            if (!enabled || source == null || source.getItem() == null || source.stackSize <= 0) return null;
            ItemStack copy = source.copy();
            copy.stackSize = Math.min(source.stackSize, Math.min(limit, copy.getMaxStackSize()));
            return copy;
        }

        String role() {
            if (!issueCode.isEmpty()) return "确认丢弃 · 仅清除蓝图中此问题对应的数据";
            if ("ghost".equals(kind)) return "配置样本 · 不计材料";
            if ("emptyCell".equals(kind)) return "空存储元件 · 内容不保留 · 需要材料";
            if ("physical".equals(kind)) return "预计安装 · 精确 NBT · 需要材料";
            return "只读标记";
        }
    }
}
