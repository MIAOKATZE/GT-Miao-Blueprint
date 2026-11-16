// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Public-library policy only. Membership never grants operator or blueprint ownership authority. */
public final class HologramLibraryPermissions {

    public static final int VERSION = 1;
    public static final int MAX_GROUPS = 16;
    public static final int MAX_MEMBERS = 128;
    public static final String DEFAULT = "default";
    public final List<Group> groups;

    public static final class Group {

        public final String id, name;
        public final boolean publish, download;
        public final int quota;
        public final List<UUID> members;

        public Group(String id, String name, boolean publish, boolean download, int quota, List<UUID> members) {
            this.id = id;
            this.name = name;
            this.publish = publish;
            this.download = download;
            this.quota = quota;
            this.members = Collections.unmodifiableList(new ArrayList<>(members));
        }

        public NBTTagCompound tag() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", id);
            tag.setString("name", name);
            tag.setBoolean("publish", publish);
            tag.setBoolean("download", download);
            tag.setInteger("quota", quota);
            NBTTagList list = new NBTTagList();
            for (UUID member : members) list.appendTag(new NBTTagString(member.toString()));
            tag.setTag("members", list);
            return tag;
        }
    }

    public HologramLibraryPermissions(List<Group> groups) throws IOException {
        if (groups == null || groups.isEmpty() || groups.size() > MAX_GROUPS)
            throw new IOException("权限组数量必须为 1.." + MAX_GROUPS);
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        Set<UUID> members = new HashSet<>();
        boolean defaultFound = false;
        for (Group group : groups) {
            if (group == null || group.id == null || !group.id.matches("[a-z0-9_]{1,48}") || !ids.add(group.id))
                throw new IOException("权限组 ID 无效或重复");
            if (group.name == null || group.name.trim()
                .isEmpty()
                || !group.name.equals(group.name.trim())
                || group.name.length() > 32
                || !names.add(group.name)) throw new IOException("组名须为不重复的 1..32 字符");
            for (int i = 0; i < group.name.length(); i++)
                if (Character.isISOControl(group.name.charAt(i)) || group.name.charAt(i) == '\u00a7')
                    throw new IOException("组名不能含控制符或格式代码");
            if (group.quota < 1 || group.quota > 1000) throw new IOException("每成员发布配额必须为 1..1000");
            if (DEFAULT.equals(group.id)) {
                defaultFound = true;
                if (!group.members.isEmpty()) throw new IOException("默认组使用未分组玩家，无需登记成员");
            }
            for (UUID member : group.members)
                if (member == null || !members.add(member)) throw new IOException("每个 UUID 只能属于一个组");
            if (members.size() > MAX_MEMBERS) throw new IOException("已登记成员最多 " + MAX_MEMBERS + " 位");
        }
        if (!defaultFound) throw new IOException("必须保留默认组");
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
    }

    public static HologramLibraryPermissions defaults(int quota) {
        try {
            return new HologramLibraryPermissions(
                Collections.singletonList(new Group(DEFAULT, "默认组", true, true, quota, Collections.emptyList())));
        } catch (IOException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    public Group effective(UUID member) {
        Group fallback = null;
        for (Group group : groups) {
            if (DEFAULT.equals(group.id)) fallback = group;
            else if (member != null && group.members.contains(member)) return group;
        }
        return fallback;
    }

    HologramLibraryPermissions withDefaultQuota(int quota) throws IOException {
        List<Group> next = new ArrayList<>();
        for (Group group : groups) next.add(
            DEFAULT.equals(group.id)
                ? new Group(group.id, group.name, group.publish, group.download, quota, group.members)
                : group);
        return new HologramLibraryPermissions(next);
    }

    public NBTTagList tag() {
        NBTTagList list = new NBTTagList();
        for (Group group : groups) list.appendTag(group.tag());
        return list;
    }

    public static HologramLibraryPermissions read(NBTTagList list) throws IOException {
        if (list == null || list.tagCount() < 1 || list.tagCount() > MAX_GROUPS) throw new IOException("权限组列表无效");
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            if (!tag.hasKey("id", 8) || !tag.hasKey("name", 8)
                || !tag.hasKey("publish", 1)
                || !tag.hasKey("download", 1)
                || !tag.hasKey("quota", 3)
                || !tag.hasKey("members", 9)) throw new IOException("权限组字段缺失或类型错误");
            NBTTagList memberTags = (NBTTagList) tag.getTag("members");
            if (memberTags.tagCount() > 0 && memberTags.func_150303_d() != 8 || memberTags.tagCount() > MAX_MEMBERS)
                throw new IOException("成员列表类型或数量错误");
            List<UUID> members = new ArrayList<>();
            for (int j = 0; j < memberTags.tagCount(); j++) members.add(uuid(memberTags.getStringTagAt(j)));
            groups.add(
                new Group(
                    tag.getString("id"),
                    tag.getString("name"),
                    tag.getBoolean("publish"),
                    tag.getBoolean("download"),
                    tag.getInteger("quota"),
                    members));
        }
        return new HologramLibraryPermissions(groups);
    }

    public static UUID uuid(String value) throws IOException {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString()
                .equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException invalid) {
            throw new IOException("请输入完整的小写 UUID（含连字符）");
        }
    }

    String json() {
        JsonArray list = new JsonArray();
        for (Group group : groups) {
            JsonObject row = new JsonObject();
            row.addProperty("id", group.id);
            row.addProperty("name", group.name);
            row.addProperty("publish", group.publish);
            row.addProperty("download", group.download);
            row.addProperty("quota", group.quota);
            JsonArray members = new JsonArray();
            for (UUID member : group.members) members.add(new com.google.gson.JsonPrimitive(member.toString()));
            row.add("members", members);
            list.add(row);
        }
        return list.toString();
    }

    static HologramLibraryPermissions json(String value) throws IOException {
        try {
            JsonArray list = new JsonParser().parse(value)
                .getAsJsonArray();
            if (list.size() > MAX_GROUPS) throw new IOException("权限组数量超限");
            List<Group> groups = new ArrayList<>();
            for (JsonElement element : list) {
                JsonObject row = element.getAsJsonObject();
                List<UUID> members = new ArrayList<>();
                JsonArray entries = row.getAsJsonArray("members");
                if (entries.size() > MAX_MEMBERS) throw new IOException("成员数量超限");
                for (JsonElement entry : entries) members.add(uuid(entry.getAsString()));
                if (!row.get("publish")
                    .getAsJsonPrimitive()
                    .isBoolean()
                    || !row.get("download")
                        .getAsJsonPrimitive()
                        .isBoolean()
                    || !row.get("quota")
                        .getAsString()
                        .matches("[0-9]{1,4}"))
                    throw new IOException("权限组字段类型错误");
                groups.add(
                    new Group(
                        row.get("id")
                            .getAsString(),
                        row.get("name")
                            .getAsString(),
                        row.get("publish")
                            .getAsBoolean(),
                        row.get("download")
                            .getAsBoolean(),
                        row.get("quota")
                            .getAsInt(),
                        members));
            }
            return new HologramLibraryPermissions(groups);
        } catch (RuntimeException invalid) {
            throw new IOException("权限组配置损坏，请管理员修复", invalid);
        }
    }
}
