// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static net.minecraft.client.gui.Gui.drawRect;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.miaokatze.gtmb.hologram.HologramLibraryPermissions;

/** One editable snapshot. Server broadcasts never silently replace an administrator's draft. */
final class HologramLibraryAdminEditor {

    private final HologramUiModules ui;
    final HologramMuiTextField upload, download, quota;
    private final HologramMuiTextField name, member;
    private final List<NBTTagCompound> groups = new ArrayList<>();
    private int selected, groupPage, memberPage;
    private long revision;
    private boolean saving;
    private String notice = "";

    HologramLibraryAdminEditor(HologramUiModules ui, NBTTagCompound config) {
        this.ui = ui;
        upload = field(146, 59, 88, 7);
        download = field(361, 59, 88, 7);
        name = field(320, 103, 310, 32);
        quota = field(518, 156, 112, 4);
        member = field(250, 190, 380, 36);
        load(config);
    }

    private HologramMuiTextField field(int x, int y, int width, int max) {
        HologramMuiTextField field = new HologramMuiTextField(ui.host.parent(), x, y, width, 18);
        field.setMaxStringLength(max);
        return field;
    }

    void reopen(NBTTagCompound config) {
        if (!saving) load(config);
    }

    private void load(NBTTagCompound config) {
        revision = config.getLong("configRevision");
        upload.setText(String.valueOf(config.getInteger("uploadKiB")));
        download.setText(String.valueOf(config.getInteger("downloadKiB")));
        groups.clear();
        NBTTagList list = config.hasKey("permissionGroups", 9) ? config.getTagList("permissionGroups", 10)
            : HologramLibraryPermissions.defaults(Math.max(1, config.getInteger("maxUploads")))
                .tag();
        for (int i = 0; i < list.tagCount(); i++) groups.add(
            (NBTTagCompound) list.getCompoundTagAt(i)
                .copy());
        selected = groupPage = memberPage = 0;
        showGroup();
        notice = "";
    }

    private NBTTagCompound selected() {
        return groups.get(selected);
    }

    private void storeGroup() throws IOException {
        int value;
        try {
            value = Integer.parseInt(quota.getText());
        } catch (NumberFormatException invalid) {
            throw new IOException("每成员配额须为 1..1000 的整数");
        }
        selected().setString("name", name.getText());
        selected().setInteger("quota", value);
    }

    private void showGroup() {
        name.setText(selected().getString("name"));
        quota.setText(String.valueOf(selected().getInteger("quota")));
        member.setText("");
        memberPage = 0;
    }

    private boolean defaultGroup() {
        return HologramLibraryPermissions.DEFAULT.equals(selected().getString("id"));
    }

    private NBTTagList tags() {
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound group : groups) list.appendTag(group.copy());
        return list;
    }

    private void assign(boolean remove) throws IOException {
        UUID id = HologramLibraryPermissions.uuid(
            member.getText()
                .trim());
        for (NBTTagCompound group : groups) {
            if (remove && group != selected()) continue;
            NBTTagList before = group.getTagList("members", 8), after = new NBTTagList();
            for (int i = 0; i < before.tagCount(); i++) if (!id.toString()
                .equals(before.getStringTagAt(i))) after.appendTag(new NBTTagString(before.getStringTagAt(i)));
            group.setTag("members", after);
        }
        if (!remove && !defaultGroup()) selected().getTagList("members", 8)
            .appendTag(new NBTTagString(id.toString()));
        notice = remove || defaultGroup() ? "已移回默认组；保存后生效" : "已登记到当前组；保存后生效";
    }

    boolean activate(int id) {
        if (id != 210 && (id < 18000 || id > 18202)) return false;
        try {
            if (saving) return true;
            if (id >= 18100 && id < 18106) {
                int next = groupPage * 6 + id - 18100;
                if (next < groups.size()) {
                    storeGroup();
                    selected = next;
                    showGroup();
                }
            } else if (id >= 18200 && id < 18203) {
                NBTTagList members = selected().getTagList("members", 8);
                int index = memberPage * 3 + id - 18200;
                if (index < members.tagCount()) member.setText(members.getStringTagAt(index));
            } else switch (id) {
                case 18000:
                    groupPage = Math.max(0, groupPage - 1);
                    break;
                case 18001:
                    groupPage = Math.min((groups.size() - 1) / 6, groupPage + 1);
                    break;
                case 18002:
                    storeGroup();
                    if (groups.size() >= HologramLibraryPermissions.MAX_GROUPS) throw new IOException("权限组已达16组上限");
                    NBTTagCompound group = new HologramLibraryPermissions.Group(
                        "group_" + UUID.randomUUID()
                            .toString()
                            .replace("-", ""),
                        "新权限组" + groups.size(),
                        true,
                        true,
                        10,
                        java.util.Collections.emptyList()).tag();
                    groups.add(group);
                    selected = groups.size() - 1;
                    groupPage = selected / 6;
                    showGroup();
                    break;
                case 18003:
                    if (!defaultGroup()) {
                        groups.remove(selected);
                        selected = groupPage = 0;
                        showGroup();
                        notice = "已删除组，其成员归默认组；保存后生效";
                    }
                    break;
                case 18004:
                    selected().setBoolean("publish", !selected().getBoolean("publish"));
                    break;
                case 18005:
                    selected().setBoolean("download", !selected().getBoolean("download"));
                    break;
                case 18006:
                    assign(false);
                    break;
                case 18007:
                    assign(true);
                    break;
                case 18008:
                    memberPage = Math.max(0, memberPage - 1);
                    break;
                case 18009:
                    memberPage++;
                    break;
                case 18010:
                    load(HologramLibraryClient.INSTANCE.config());
                    break;
                case 210:
                    storeGroup();
                    HologramLibraryPermissions permissions = HologramLibraryPermissions.read(tags());
                    int up = Integer.parseInt(upload.getText()), down = Integer.parseInt(download.getText());
                    if (up < 1 || up > 1024 || down < 1 || down > 1024) throw new IOException("全服带宽须为 1..1024 KiB/s");
                    HologramLibraryClient.INSTANCE
                        .saveConfig(up, down, permissions.effective(null).quota, revision, permissions.tag());
                    saving = HologramLibraryClient.INSTANCE.configSavePending();
                    notice = "等待服务器确认全部设置";
                    break;
                default:
                    return false;
            }
        } catch (IOException | IllegalArgumentException invalid) {
            notice = invalid.getMessage() == null ? "请输入有效的整数" : invalid.getMessage();
        }
        return true;
    }

    void tick() {
        upload.setEnabled(!saving);
        download.setEnabled(!saving);
        name.setEnabled(!saving);
        quota.setEnabled(!saving);
        member.setEnabled(!saving);
        if (saving && !HologramLibraryClient.INSTANCE.configSavePending()) {
            saving = false;
            if (HologramLibraryClient.INSTANCE.confirmedConfigRevision() > revision) {
                load(HologramLibraryClient.INSTANCE.config());
                notice = "服务器已确认全部设置 · 修订 " + revision;
            } else notice = HologramLibraryClient.INSTANCE.status();
        }
    }

    boolean canSave() {
        return !saving && HologramLibraryClient.INSTANCE.supportsPermissionGroups()
            && HologramLibraryClient.INSTANCE.config()
                .getLong("configRevision") == revision;
    }

    String status() {
        return !saving && HologramLibraryClient.INSTANCE.config()
            .getLong("configRevision") != revision ? "其他管理员已更新；草稿保留，请重新载入后编辑" : notice;
    }

    /** The production ADMIN dialog adopts the same fields and draft as the legacy canvas adapter. */
    IWidget buildNative() {
        Flow form = Flow.col()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(4);
        form.child(
            HologramEditorNativeTheme.label(() -> "全服共享带宽（KiB/s，1..1024）")
                .widthRel(1f)
                .height(18));
        form.child(nativeField("上传", upload));
        form.child(nativeField("下载", download));
        form.child(
            HologramEditorNativeTheme.label(() -> "权限组仅控制公库；不授予管理或删除他人蓝图权限。")
                .widthRel(1f)
                .height(30));
        form.child(
            Flow.row()
                .widthRel(1f)
                .height(22)
                .childPadding(4)
                .child(nativeButton(18000, () -> "上一组", () -> selected > 0).width(68))
                .child(
                    HologramEditorNativeTheme
                        .label(() -> (selected + 1) + "/" + groups.size() + " · " + selected().getString("name"))
                        .widthRel(1f)
                        .expanded()
                        .height(22))
                .child(nativeButton(18001, () -> "下一组", () -> selected + 1 < groups.size()).width(68)));
        form.child(
            Flow.row()
                .widthRel(1f)
                .height(22)
                .childPadding(4)
                .child(
                    nativeButton(18002, () -> "新增组", () -> groups.size() < HologramLibraryPermissions.MAX_GROUPS)
                        .widthRel(1f)
                        .expanded())
                .child(
                    nativeButton(18003, () -> "删除组，成员归默认", () -> !defaultGroup()).widthRel(1f)
                        .expanded()));
        form.child(nativeField("组名", name));
        form.child(nativeField("每成员配额", quota));
        form.child(
            Flow.row()
                .widthRel(1f)
                .height(22)
                .childPadding(4)
                .child(
                    nativeButton(18004, () -> "发布：" + (selected().getBoolean("publish") ? "允许" : "禁止"), () -> true)
                        .widthRel(1f)
                        .expanded())
                .child(
                    nativeButton(18005, () -> "下载：" + (selected().getBoolean("download") ? "允许" : "禁止"), () -> true)
                        .widthRel(1f)
                        .expanded()));
        form.child(
            HologramEditorNativeTheme
                .label(() -> defaultGroup() ? "默认组涵盖未登记玩家；输入 UUID 可将其归默认。" : "一人一组；输入完整小写 UUID，登记会从原组移入。")
                .widthRel(1f)
                .height(30));
        IWidget uuidField = ui.shell.adoptField(member)
            .name("library_permission_member_uuid")
            .widthRel(1f)
            .height(22);
        ui.shell.registerTarget("library.permissions.member", uuidField);
        form.child(uuidField);
        form.child(
            Flow.row()
                .widthRel(1f)
                .height(22)
                .childPadding(4)
                .child(
                    nativeButton(18006, () -> defaultGroup() ? "归默认组" : "登记 / 移入", () -> true).widthRel(1f)
                        .expanded())
                .child(
                    nativeButton(18007, () -> "移除成员", () -> !defaultGroup()).widthRel(1f)
                        .expanded()));
        for (int row = 0; row < 3; row++) {
            final int slot = row;
            form.child(
                nativeButton(18200 + row, () -> memberAt(slot), () -> !memberAt(slot).isEmpty()).widthRel(1f)
                    .height(18));
        }
        form.child(
            Flow.row()
                .widthRel(1f)
                .height(22)
                .childPadding(4)
                .child(nativeButton(18008, () -> "成员上页", () -> memberPage > 0).width(88))
                .child(
                    HologramEditorNativeTheme.label(
                        () -> "登记 " + selected().getTagList("members", 8)
                            .tagCount() + " 位 · 页 " + (memberPage + 1))
                        .widthRel(1f)
                        .expanded()
                        .height(22))
                .child(
                    nativeButton(
                        18009,
                        () -> "成员下页",
                        () -> (memberPage + 1) * 3 < selected().getTagList("members", 8)
                            .tagCount()).width(88)));
        form.child(
            nativeButton(18010, () -> "重新载入服务器设置（放弃本地草稿）", () -> true).widthRel(1f)
                .height(22));
        return new HologramRecessedList().name("library_permission_form")
            .widthRel(1f)
            .height(
                () -> Math.max(40, nativeHeight(ui.host.screenHeight()) - 124),
                com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .child(form);
    }

    static int nativeWidth(int screenWidth) {
        return Math.max(1, Math.min(460, screenWidth - 12));
    }

    static int nativeHeight(int screenHeight) {
        return Math.max(1, Math.min(350, screenHeight - 12));
    }

    private String memberAt(int slot) {
        NBTTagList members = selected().getTagList("members", 8);
        memberPage = Math.max(0, Math.min(memberPage, Math.max(0, (members.tagCount() - 1) / 3)));
        int index = memberPage * 3 + slot;
        return index < members.tagCount() ? members.getStringTagAt(index) : "";
    }

    private IWidget nativeField(String label, HologramMuiTextField field) {
        IWidget widget = ui.shell.adoptField(field)
            .widthRel(1f)
            .expanded()
            .height(22);
        ui.shell.registerTarget(
            "library.permissions."
                + (field == name ? "name" : field == quota ? "quota" : field == upload ? "upload" : "download"),
            widget);
        return Flow.row()
            .widthRel(1f)
            .height(22)
            .childPadding(4)
            .child(
                HologramEditorNativeTheme.label(() -> label)
                    .width(88)
                    .height(22))
            .child(widget);
    }

    private ButtonWidget<?> nativeButton(int id, java.util.function.Supplier<String> label,
        java.util.function.BooleanSupplier enabled) {
        ButtonWidget<?> button = HologramEditorNativeTheme.button(label, () -> {
            if (saving || !enabled.getAsBoolean()) return;
            if (id == 18000 || id == 18001) {
                try {
                    storeGroup();
                    selected = Math.max(0, Math.min(groups.size() - 1, selected + (id == 18000 ? -1 : 1)));
                    showGroup();
                } catch (IOException invalid) {
                    notice = invalid.getMessage();
                }
            } else activate(id);
        })
            .height(22)
            .setEnabledIf(widget -> !saving && enabled.getAsBoolean());
        ui.shell.registerTarget("library.permissions.action." + id, button);
        return button;
    }

    void draw(int mx, int my) {
        drawRect(0, 0, 680, 340, 0xe0102026);
        HologramEditorTheme.PANEL.draw(25, 15, 630, 320);
        ui.canvas.text("服务器公共蓝图库 · 权限组设置（OP 4）", 45, 27, 585, HologramEditorTheme.TITLE);
        ui.canvas.text("全服上传 KiB/s", 45, 64, 100, HologramEditorTheme.TEXT);
        ui.canvas.text("全服下载 KiB/s", 250, 64, 110, HologramEditorTheme.TEXT);
        upload.drawTextBox();
        download.drawTextBox();
        ui.canvas.text("总带宽由全服共享；权限组不授予管理或删除他人蓝图的权限", 45, 84, 585, HologramEditorTheme.MUTED);
        boolean idle = !saving;
        upload.setEnabled(idle);
        download.setEnabled(idle);
        name.setEnabled(idle);
        quota.setEnabled(idle);
        member.setEnabled(idle);
        for (int row = 0; row < 6; row++) {
            int index = groupPage * 6 + row;
            if (index >= groups.size()) break;
            NBTTagCompound group = groups.get(index);
            ui.canvas.button(
                18100 + row,
                45,
                105 + row * 20,
                180,
                18,
                (index == selected ? "▶ " : "") + group.getString("name"),
                idle,
                mx,
                my);
        }
        ui.canvas.button(18000, 45, 228, 54, 17, "上页", idle && groupPage > 0, mx, my);
        ui.canvas.text((groupPage + 1) + " / " + ((groups.size() + 5) / 6), 106, 232, 55, HologramEditorTheme.MUTED);
        ui.canvas.button(18001, 171, 228, 54, 17, "下页", idle && (groupPage + 1) * 6 < groups.size(), mx, my);
        ui.canvas.button(
            18002,
            45,
            252,
            180,
            18,
            "新增权限组",
            idle && groups.size() < HologramLibraryPermissions.MAX_GROUPS,
            mx,
            my);
        ui.canvas.button(18003, 45, 275, 180, 18, "删除组，成员归默认", idle && !defaultGroup(), mx, my);
        ui.canvas.text("组名", 250, 108, 65, HologramEditorTheme.TEXT);
        name.drawTextBox();
        ui.canvas
            .button(18004, 250, 130, 180, 19, "允许发布：" + (selected().getBoolean("publish") ? "是" : "否"), idle, mx, my);
        ui.canvas
            .button(18005, 445, 130, 185, 19, "允许下载：" + (selected().getBoolean("download") ? "是" : "否"), idle, mx, my);
        ui.canvas.text("每成员发布配额（1..1000）", 250, 161, 265, HologramEditorTheme.TEXT);
        quota.drawTextBox();
        ui.canvas.text(
            defaultGroup() ? "未登记玩家均使用默认组；输入 UUID 可将其归默认" : "成员 UUID（一人一组；登记会从原组移入）",
            250,
            179,
            380,
            HologramEditorTheme.MUTED);
        member.drawTextBox();
        ui.canvas.button(18006, 250, 213, 184, 18, defaultGroup() ? "将该 UUID 归默认组" : "登记 / 移入当前组", idle, mx, my);
        ui.canvas.button(18007, 446, 213, 184, 18, "移除该 UUID，归默认组", idle && !defaultGroup(), mx, my);
        NBTTagList members = selected().getTagList("members", 8);
        memberPage = Math.max(0, Math.min(memberPage, Math.max(0, (members.tagCount() - 1) / 3)));
        for (int row = 0; row < 3 && memberPage * 3 + row < members.tagCount(); row++) ui.canvas.button(
            18200 + row,
            250,
            237 + row * 15,
            380,
            14,
            members.getStringTagAt(memberPage * 3 + row),
            idle,
            mx,
            my);
        ui.canvas.button(18008, 250, 283, 62, 15, "上页", idle && memberPage > 0, mx, my);
        ui.canvas.text(
            defaultGroup() ? "默认组成员自动计算" : "已登记 " + members.tagCount() + " 位 · 第 " + (memberPage + 1) + " 页",
            320,
            286,
            235,
            HologramEditorTheme.MUTED);
        ui.canvas.button(18009, 568, 283, 62, 15, "下页", idle && (memberPage + 1) * 3 < members.tagCount(), mx, my);
        boolean conflict = HologramLibraryClient.INSTANCE.config()
            .getLong("configRevision") != revision;
        ui.canvas.button(210, 45, 303, 180, 18, "保存全部设置", idle && !conflict, mx, my);
        ui.canvas.button(18010, 250, 303, 184, 18, "重新载入服务器设置", idle, mx, my);
        ui.canvas.button(211, 446, 303, 184, 18, "关闭", true, mx, my);
        ui.canvas.text(
            conflict && !saving ? "其他管理员已更新设置；草稿保留，请重新载入后编辑" : notice,
            45,
            324,
            585,
            conflict ? 0xffcc80 : HologramEditorTheme.MUTED);
    }

    boolean focused() {
        return upload.isFocused() || download.isFocused()
            || name.isFocused()
            || quota.isFocused()
            || member.isFocused();
    }

    NBTTagCompound state() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("permissionGroups", tags());
        tag.setLong("configRevision", revision);
        tag.setString("uploadText", upload.getText());
        tag.setString("downloadText", download.getText());
        tag.setString("nameText", name.getText());
        tag.setString("quotaText", quota.getText());
        tag.setString("memberText", member.getText());
        tag.setInteger("selected", selected);
        tag.setInteger("groupPage", groupPage);
        tag.setInteger("memberPage", memberPage);
        tag.setString("notice", notice);
        tag.setBoolean("saving", saving);
        return tag;
    }

    void restore(NBTTagCompound tag) {
        load(tag);
        selected = Math.max(0, Math.min(groups.size() - 1, tag.getInteger("selected")));
        showGroup();
        groupPage = Math.max(0, Math.min((groups.size() - 1) / 6, tag.getInteger("groupPage")));
        memberPage = Math.max(0, tag.getInteger("memberPage"));
        upload.setText(tag.getString("uploadText"));
        download.setText(tag.getString("downloadText"));
        name.setText(tag.getString("nameText"));
        quota.setText(tag.getString("quotaText"));
        member.setText(tag.getString("memberText"));
        notice = tag.getString("notice");
        saving = tag.getBoolean("saving");
    }
}
