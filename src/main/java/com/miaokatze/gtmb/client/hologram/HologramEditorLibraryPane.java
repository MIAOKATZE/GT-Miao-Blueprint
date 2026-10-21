package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Persistent native library dock. Six controls reuse captured row identities, never row indices at confirmation. */
final class HologramEditorLibraryPane {

    private static final int ROWS = 6, LOCAL_PREVIOUS = HologramEditorControlIds.LIBRARY + 1,
        LOCAL_NEXT = HologramEditorControlIds.LIBRARY + 2;
    private final HologramUiModules ui;
    private final NBTTagCompound[] rows = new NBTTagCompound[ROWS];
    private final PagedWidget.Controller pages = new PagedWidget.Controller();
    private final HologramEditorNativeLibraryDialogs dialogs;
    private IWidget built;
    private int total;

    HologramEditorLibraryPane(HologramUiModules ui) {
        this.ui = ui;
        dialogs = new HologramEditorNativeLibraryDialogs(ui);
        for (int i = 0; i < ROWS; i++) rows[i] = new NBTTagCompound();
    }

    IWidget build() {
        if (built != null) return built;
        Flow root = Flow.col()
            .name("native_library")
            .sizeRel(1f)
            .padding(6)
            .childPadding(4);
        root.child(
            new PagedWidget<>().name("library_pages")
                .widthRel(1f)
                .expanded()
                .controller(pages)
                .addPage(library())
                .addPage(management()));
        built = root;
        refresh();
        return root;
    }

    private IWidget library() {
        Flow page = Flow.col()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(4);
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(4)
                .child(
                    HologramEditorNativeTheme.label(() -> "蓝图库 · " + total + " 项")
                        .expanded()
                        .height(18))
                .child(
                    local(HologramEditorControlIds.LIBRARY + 3, () -> "管理", () -> true, () -> pages.setPage(1))
                        .width(48)
                        .height(20)));
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(3)
                .child(
                    action(
                        201,
                        () -> ui.library.publicLibrary() ? "服务器" : "客户端",
                        () -> !ui.session.isTutorial() && ui.library.nativeDedicated() && ui.library.nativeIdle(),
                        () -> "切换客户端与服务器目录，保留当前工作区。").expanded()
                            .height(20))
                .child(
                    action(309, () -> "蓝图站 ↗", this::websiteReady, () -> "在浏览器打开蓝图站，遵循客户端外链确认。").expanded()
                        .height(20))
                .child(
                    local(
                        HologramEditorControlIds.LIBRARY + 4,
                        () -> "刷新",
                        this::idle,
                        ui.library::nativeRefreshCatalog).expanded()
                            .height(20)));
        page.child(
            ui.shell.adoptField(ui.library.presetNameField())
                .name("library_name")
                .widthRel(1f)
                .height(20));
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(22)
                .childPadding(4)
                .child(
                    action(140, () -> "保存蓝图", ui.library::nativeCanSave, () -> "沿用作者处理、原生预览和最终保存回执；保存不会施工。").expanded()
                        .height(22))
                .child(
                    action(
                        222,
                        () -> "覆盖保存 " + (ui.library.overwriteMode() ? "✓" : "×"),
                        () -> !ui.session.isTutorial() && !ui.library.browsePreviewActive(),
                        () -> "覆盖模式中点行仅指定目标，保存前再核对名称与身份。").expanded()
                            .height(22)));
        HologramRecessedList list = new HologramRecessedList().name("library_rows")
            .widthRel(1f)
            .height(132);
        for (int i = 0; i < ROWS; i++) {
            final int slot = i;
            ButtonWidget<?> row = action(
                14000 + slot,
                () -> rowLabel(slot),
                () -> ui.library.nativeIdle() && !rows[slot].getString("id")
                    .isEmpty(),
                () -> rowTooltip(slot)).name("library_row_" + slot)
                    .sizeRel(1f);
            IDrawable lettering = row.getOverlay();
            row.overlay((context, x, y, width, height, theme) -> {
                boolean voucher = rows[slot].getBoolean("fullyVoucherCovered");
                if (lettering != null)
                    lettering.draw(context, x, y, Math.max(1, width - (voucher ? 22 : 0)), height, theme);
                if (voucher) HologramVoucherRibbon.draw(x, y, width, height);
            });
            list.child(
                new ParentWidget<>().widthRel(1f)
                    .height(22)
                    .setEnabledIf(
                        widget -> !rows[slot].getString("id")
                            .isEmpty())
                    .child(row));
        }
        list.child(
            HologramEditorNativeTheme.label(() -> "暂无保存蓝图\n可从蓝图站获取文件，或框选后保存。")
                .widthRel(1f)
                .height(40)
                .setEnabledIf(widget -> total == 0));
        page.child(list);
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(18)
                .childPadding(3)
                .child(
                    local(LOCAL_PREVIOUS, () -> "◂", () -> ui.library.libraryOffset() > 0, () -> offset(-ROWS))
                        .width(30)
                        .height(18))
                .child(
                    HologramEditorNativeTheme
                        .label(
                            () -> total == 0 ? "暂无蓝图"
                                : (ui.library.libraryOffset() + 1) + "–"
                                    + Math.min(total, ui.library.libraryOffset() + ROWS)
                                    + " / "
                                    + total)
                        .expanded()
                        .height(18))
                .child(
                    local(LOCAL_NEXT, () -> "▸", () -> ui.library.libraryOffset() + ROWS < total, () -> offset(ROWS))
                        .width(30)
                        .height(18)));
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(18)
                .childPadding(3)
                .setEnabledIf(widget -> ui.library.nativeDedicated() && ui.library.publicLibrary())
                .child(
                    action(
                        208,
                        () -> "上页",
                        () -> idle() && ui.library.nativeServerPage() > 0,
                        () -> "读取服务器上一页；不会替换当前草稿。").width(48)
                            .height(18))
                .child(
                    HologramEditorNativeTheme
                        .label(() -> (ui.library.nativeServerPage() + 1) + " / " + ui.library.nativeServerPages())
                        .expanded()
                        .height(18))
                .child(
                    action(
                        209,
                        () -> "下页",
                        () -> idle() && ui.library.nativeServerPage() + 1 < ui.library.nativeServerPages(),
                        () -> "读取服务器下一页；不会清除预览。").width(48)
                            .height(18)));
        page.child(
            action(
                141,
                () -> "放置所选蓝图",
                () -> idle() && ui.library.selectedPresetExists(),
                () -> "进入真实世界 ghost 定位；锁定后核验材料，明确确认一次施工。").widthRel(1f)
                    .height(24));
        page.child(
            HologramEditorNativeTheme.label(ui.library::nativeStatus)
                .widthRel(1f)
                .height(30));
        return new HologramRecessedList().name("library_content")
            .sizeRel(1f)
            .child(page);
    }

    private IWidget management() {
        Flow page = Flow.col()
            .sizeRel(1f)
            .childPadding(4);
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(4)
                .child(
                    HologramEditorNativeTheme.label(() -> "蓝图库管理")
                        .expanded()
                        .height(18))
                .child(
                    local(HologramEditorControlIds.LIBRARY + 5, () -> "返回", () -> true, () -> pages.setPage(0))
                        .width(48)
                        .height(20)));
        HologramRecessedList list = new HologramRecessedList().widthRel(1f)
            .expanded();
        list.child(
            action(
                203,
                () -> ui.library.publicLibrary() ? "下载至客户端" : "上传 / 更新公库",
                () -> idle() && ui.library.nativeDedicated() && ui.library.selectedPresetExists(),
                () -> "完整 JSON / PNG 沿用原传输、权限与配额。").widthRel(1f)
                    .height(22));
        list.child(
            action(139, () -> "编辑所选", () -> idle() && ui.library.selectedPresetExists(), () -> "兼容编辑入口；普通点行已直接载入工作区。")
                .widthRel(1f)
                .height(22));
        list.child(
            action(
                299,
                () -> "放弃蓝图预设",
                () -> idle() && !ui.session.state().cells.isEmpty(),
                () -> "独立放弃当前草稿；需明确确认，已保存文件保留。").widthRel(1f)
                    .height(22));
        list.child(
            local(
                HologramEditorControlIds.LIBRARY + 6,
                () -> "删除所选蓝图",
                this::canDelete,
                () -> ui.router.activate(ui.library.nativeDedicated() ? 205 : 175)).widthRel(1f)
                    .height(22));
        list.child(
            action(226, () -> "升级旧蓝图", ui.library::nativeCanUpgrade, () -> "按原文件版本门禁升级；服务器公库仅管理员可操作。").widthRel(1f)
                .height(22));
        list.child(
            action(
                207,
                () -> "服务器库设置 · OP 4",
                () -> !ui.session.isTutorial() && ui.library.nativeAdmin(),
                () -> "查看和设置原服务器上传、下载与每人配额。").widthRel(1f)
                    .height(22));
        list.child(
            action(
                142,
                () -> "保留 NBT " + (ui.processing.retainNbt() ? "✓" : "×"),
                this::editing,
                () -> "沿用本次蓝图的 NBT 保留选择与资源审核。").widthRel(1f)
                    .height(22));
        list.child(
            action(
                143,
                () -> "保留覆盖板 " + (ui.processing.retainCovers() ? "✓" : "×"),
                this::editing,
                () -> "覆盖板与内部资源仍分别审核、付料。").widthRel(1f)
                    .height(22));
        list.child(
            action(227, () -> "全局提示词", this::editing, () -> "编辑整份方案提示，不改变所选结构的配置。").widthRel(1f)
                .height(22));
        list.child(
            action(
                159,
                () -> "导出 JSON / PNG",
                () -> idle() && !ui.library.nativeDedicated() && ui.library.selectedPresetExists(),
                () -> "沿用原命名分享文件导出入口。").widthRel(1f)
                    .height(22));
        list.child(
            action(161, () -> "导入命名文件", () -> idle() && !ui.library.nativeDedicated(), () -> "名称框填写文件名；沿用原文件准入与换稿确认。")
                .widthRel(1f)
                .height(22));
        list.child(
            action(150, () -> "来源版本", () -> true, () -> "查看来源与依赖；不代替材料或原生机器成型检查。").widthRel(1f)
                .height(22));
        page.child(list);
        return page;
    }

    void refresh() {
        NBTTagList source = ui.library.nativeRows();
        total = source.tagCount();
        int offset = Math.max(0, Math.min(ui.library.libraryOffset(), Math.max(0, total - ROWS)));
        ui.library.libraryOffset(offset);
        for (int slot = 0; slot < ROWS; slot++) {
            rows[slot] = offset + slot < total ? (NBTTagCompound) source.getCompoundTagAt(offset + slot)
                .copy() : new NBTTagCompound();
            ui.library.nativeBindRow(slot, rows[slot]);
        }
        dialogs.refresh();
    }

    private void offset(int delta) {
        ui.library.libraryOffset(Math.max(0, Math.min(ui.library.libraryOffset() + delta, Math.max(0, total - ROWS))));
        refresh();
    }

    private String rowLabel(int slot) {
        NBTTagCompound row = rows[slot];
        return row.getString("id")
            .isEmpty() ? "—" : (ui.library.nativeSelected(row) ? "▸ " : "") + row.getString("name");
    }

    private String rowTooltip(int slot) {
        NBTTagCompound row = rows[slot];
        return row.getString("name") + "\n"
            + row.getInteger("cells")
            + " 格 · "
            + row.getInteger("structures")
            + " 结构 · "
            + ui.library.blueprintSource(row)
            + "\n作者："
            + row.getString("authorName")
            + (ui.library.nativeDedicated() && ui.library.publicLibrary()
                ? "\n上传者（服务器验证）：" + row.getString("uploadedBy")
                : "")
            + (row.getBoolean("fullyVoucherCovered") ? "\n" + HologramVoucherRibbon.HELP : "")
            + (ui.library.overwriteMode() ? "\n仅选择覆盖目标，保存时确认。" : "\n点击载入工作区；未保存时先确认放弃。");
    }

    private boolean idle() {
        return !ui.session.isTutorial() && ui.library.nativeIdle();
    }

    private boolean editing() {
        return idle() && !ui.library.browsePreviewActive();
    }

    private boolean websiteReady() {
        return !ui.session.isTutorial() && ui.session.pendingSequence() < 0
            && !HologramBackgroundTasks.busy()
            && !HologramLibraryClient.INSTANCE.busy();
    }

    private boolean canDelete() {
        return idle() && ui.library.selectedPresetExists()
            && (!ui.library.nativeDedicated() || !ui.library.publicLibrary()
                || ui.library.nativeSelectedRow()
                    .getBoolean("mine"));
    }

    private ButtonWidget<?> action(int id, Supplier<String> label, BooleanSupplier enabled, Supplier<String> help) {
        return ui.shell.nativeAction(id, label, enabled, help);
    }

    private ButtonWidget<?> local(int id, Supplier<String> label, BooleanSupplier enabled, Runnable action) {
        return ui.shell.nativeAction(id, label, enabled, () -> "仅改变本地目录视图，不替换工作区。")
            .onMousePressed(button -> {
                if (button == 0 && enabled.getAsBoolean()) action.run();
                return true;
            });
    }
}
