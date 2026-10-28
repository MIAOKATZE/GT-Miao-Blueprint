package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.text.TextRenderer;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Independent library sidebar and save dock, sharing the original library action owners. */
final class HologramEditorLibraryPane {

    // The router reserves 14000..14999 for captured library identities. Ordinary catalogs scroll without paging.
    private static final int ROW_WINDOW = 15000 - 14000;
    private static final int LOCAL_PREVIOUS = HologramEditorControlIds.LIBRARY + 1,
        LOCAL_NEXT = HologramEditorControlIds.LIBRARY + 2;
    private final HologramUiModules ui;
    private final StringValue search = new StringValue("");
    private final List<NBTTagCompound> matches = new ArrayList<>(), rows = new ArrayList<>();
    private final PagedWidget.Controller pages = new PagedWidget.Controller();
    private final HologramEditorNativeLibraryDialogs dialogs;
    private IWidget built, save;
    private HologramRecessedList rowList;
    private String previousQuery = "";
    private boolean previousPublic;
    private int total, catalogTotal;

    HologramEditorLibraryPane(HologramUiModules ui) {
        this.ui = ui;
        dialogs = new HologramEditorNativeLibraryDialogs(ui);
    }

    boolean consumeBackLayer() {
        if (built == null || pages.getActivePageIndex() != 1) return false;
        pages.setPage(0);
        return true;
    }

    IWidget build() {
        if (built != null) return built;
        // This layer only insets one page container. A plain parent gives both axes to PagedWidget directly;
        // using a Flow with a height-less expanded PagedWidget can finalize MUI2's 18px default height.
        built = new ParentWidget<>().name("native_library")
            .sizeRel(1f)
            .padding(4)
            .child(
                new PagedWidget<>().name("library_pages")
                    .sizeRel(1f)
                    .controller(pages)
                    .addPage(library())
                    .addPage(management()));
        refresh();
        return built;
    }

    /** The original name field has exactly one native parent, in the functional dock's save page. */
    IWidget buildSave() {
        if (save != null) return save;
        Flow page = Flow.col()
            .name("native_blueprint_save")
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(3)
            .collapseDisabledChild(true);
        page.child(
            HologramEditorNativeTheme
                .label(
                    () -> ui.session.isLocalAuthor() ? "Ctrl+S 只保存完整恢复草稿，不等待审核或 PNG。导出与放置为独立步骤。"
                        : "Ctrl+S 保存当前编辑蓝图；公库来源保存为本地副本。")
                .widthRel(1f)
                .height(30));
        IWidget name = ui.shell.adoptField(ui.library.presetNameField())
            .name("library_name")
            .widthRel(1f)
            .height(20);
        ui.shell.registerTarget("library.name", name);
        page.child(name);
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(18)
                .childPadding(3)
                .child(
                    saveMode(false).expanded()
                        .height(18))
                .child(
                    saveMode(true).expanded()
                        .height(18)));
        page.child(
            HologramEditorNativeTheme.label(this::overwriteTargetLabel)
                .widthRel(1f)
                .height(30)
                .setEnabledIf(widget -> ui.library.overwriteMode()));
        page.child(
            action(
                140,
                () -> ui.session.isLocalAuthor() ? "保存恢复草稿" : ui.library.overwriteMode() ? "更新此蓝图" : "保存蓝图",
                ui.library::nativeCanSave,
                () -> ui.session.isLocalAuthor() ? "保存完整原始内容、未知字段及待处理义务，不授予施工或发布许可。"
                    : "保存沿用原作者处理、结构封面与最终写盘回执；覆盖前核对真实目标。").widthRel(1f)
                        .height(20));
        page.child(
            action(
                132,
                () -> ui.session.isLocalAuthor() ? "另存恢复副本（当前稿不变）" : "另存为新蓝图",
                ui.library::nativeCanSave,
                () -> ui.session.isLocalAuthor() ? "新文档身份的完整恢复副本；不清除当前稿未保存状态。" : "保留当前原图，另存新的蓝图与结构封面。").widthRel(1f)
                    .height(18));
        page.child(
            HologramEditorNativeTheme.label(ui.library::nativeStatus)
                .widthRel(1f)
                .height(36));
        page.child(
            HologramEditorNativeTheme.label(() -> "封面与文件", HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(20));
        page.child(
            HologramEditorNativeTheme
                .label(() -> ui.session.isLocalAuthor() ? "导出候选不是服务器审核结果；实际放置仍需权限、材料和目标校验。" : "保存时沿用现有结构预览与 PNG 封面流程。")
                .widthRel(1f)
                .height(30));
        page.child(
            action(
                159,
                () -> "导出 JSON / PNG",
                () -> idle() && !ui.library.nativeDedicated() && ui.library.selectedPresetExists(),
                () -> "导出所选已保存蓝图及其原有 PNG 文件。").widthRel(1f)
                    .height(18));
        page.child(
            local(
                HologramEditorControlIds.LIBRARY + 20,
                () -> "导出候选（未获服务器审核）",
                ui.library::canSubmitAuthorPortable,
                () -> ui.library.submitAuthorPortable(false)).widthRel(1f)
                    .height(20)
                    .setEnabledIf(widget -> ui.session.isLocalAuthor()));
        page.child(
            local(
                HologramEditorControlIds.LIBRARY + 21,
                () -> "提交放置审核",
                ui.library::canSubmitAuthorPortable,
                () -> ui.library.submitAuthorPortable(true)).widthRel(1f)
                    .height(20)
                    .setEnabledIf(widget -> ui.session.isLocalAuthor()));
        save = new HologramRecessedList().paperEdges()
            .name("blueprint_save_content")
            .sizeRel(1f)
            .child(page);
        ui.shell.registerTarget("library.save", save);
        return save;
    }

    private IWidget library() {
        Flow page = Flow.col()
            .name("library_content")
            .sizeRel(1f)
            .childPadding(3)
            .collapseDisabledChild(true);
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(3)
                .child(
                    new Widget<>().size(HologramEditorNativeTheme.ICON_SIZE)
                        .overlay(
                            (context, x, y, width, height, theme) -> HologramEditorIcons
                                .draw("library", x, y, width, false, true, false, false)))
                .child(
                    HologramEditorNativeTheme.label(() -> "蓝图库", HologramEditorNativeTheme.TITLE)
                        .name("library_title")
                        .expanded()
                        .height(20))
                .child(
                    HologramEditorNativeTheme
                        .flatIconButton(
                            "collapse-right",
                            () -> "收起蓝图库",
                            ui.shell::toggleLibrary,
                            () -> true,
                            () -> false)
                        .size(HologramEditorNativeTheme.ICON_BUTTON)));
        ButtonWidget<?> website = action(309, () -> "蓝图站 ↗", this::websiteReady, () -> "在浏览器打开蓝图站。")
            .name("library_website")
            .expanded()
            .height(20);
        ButtonWidget<?> manage = localIcon(
            HologramEditorControlIds.LIBRARY + 3,
            "settings",
            () -> "管理",
            () -> true,
            () -> pages.setPage(1),
            () -> "蓝图库管理，保留工作区。").name("library_manage")
                .expanded()
                .height(20);
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(3)
                .child(website)
                .child(manage));
        ui.shell.registerTarget("library.website", website);
        ui.shell.registerTarget("library.manage", manage);
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(18)
                .childPadding(3)
                .child(
                    source(false).expanded()
                        .height(18))
                .child(
                    source(true).expanded()
                        .height(18)));
        IWidget filter = HologramEditorNativeTheme.textField(search, 120)
            .name("library_search")
            .hintText("搜索蓝图")
            .widthRel(1f)
            .height(20);
        ui.shell.registerTarget("library.search", filter);
        page.child(filter);
        rowList = new HologramRecessedList().paperEdges()
            .name("library_rows")
            // Retain an explicit Y size when MUI relays out an enabled/disabled list child.
            // expanded still assigns the column's remainder; a missing Y unit falls back to the 18px default.
            .sizeRel(1f)
            .expanded();
        rowList.child(
            HologramEditorNativeTheme.label(() -> catalogTotal == 0 ? "暂无保存蓝图\n可从蓝图站获取，或编辑后保存。" : "没有匹配的蓝图")
                .widthRel(1f)
                .height(44)
                .setEnabledIf(widget -> total == 0));
        page.child(rowList);
        ui.shell.registerTarget("library.rows", rowList);
        Flow footer = HologramEditorNativeTheme.collapsingFlow(GuiAxis.Y)
            .name("library_footer")
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(3)
            .collapseDisabledChild(true);
        footer.child(
            Flow.row()
                .widthRel(1f)
                .height(18)
                .childPadding(3)
                .setEnabledIf(widget -> total > ROW_WINDOW)
                .child(
                    local(LOCAL_PREVIOUS, () -> "◂", () -> ui.library.libraryOffset() > 0, () -> offset(-ROW_WINDOW))
                        .width(25)
                        .height(18))
                .child(
                    HologramEditorNativeTheme
                        .label(
                            () -> (ui.library.libraryOffset() + 1) + "–"
                                + Math.min(total, ui.library.libraryOffset() + ROW_WINDOW)
                                + " / "
                                + total)
                        .expanded()
                        .height(18))
                .child(
                    local(
                        LOCAL_NEXT,
                        () -> "▸",
                        () -> ui.library.libraryOffset() + ROW_WINDOW < total,
                        () -> offset(ROW_WINDOW)).width(25)
                            .height(18)));
        footer.child(
            Flow.row()
                .widthRel(1f)
                .height(18)
                .childPadding(3)
                .setEnabledIf(
                    widget -> ui.library.nativeDedicated() && ui.library.publicLibrary()
                        && ui.library.nativeServerPages() > 1)
                .child(
                    action(
                        208,
                        () -> "上页",
                        () -> idle() && ui.library.nativeServerPage() > 0,
                        () -> "读取服务器上一页，保留当前工作区。").width(40)
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
                        () -> "读取服务器下一页，保留当前工作区。").width(40)
                            .height(18)));
        footer.child(
            action(
                141,
                () -> "放置所选蓝图",
                () -> idle() && ui.library.selectedPresetExists(),
                () -> "进入真实世界定位，锁定后校验材料并确认施工。").widthRel(1f)
                    .height(HologramEditorNativeTheme.PRIMARY_BUTTON_HEIGHT));
        Flow secondary = Flow.col()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(3);
        secondary.child(
            actionIcon(
                203,
                "network",
                () -> ui.library.publicLibrary() ? "下载至客户端" : "上传 / 更新公库",
                () -> idle() && ui.library.nativeDedicated() && ui.library.selectedPresetExists(),
                () -> "沿用原 JSON / PNG 传输、权限与配额。").widthRel(1f)
                    .height(20));
        secondary.child(
            action(299, () -> "放弃当前草稿", () -> idle() && !ui.session.state().cells.isEmpty(), () -> "确认后放弃当前草稿；已保存文件保留。")
                .widthRel(1f)
                .height(20));
        footer.child(secondary);
        page.child(footer);
        ui.shell.registerTarget("library.footer", footer);
        return page;
    }

    private IWidget management() {
        Flow page = Flow.col()
            .sizeRel(1f)
            .childPadding(3);
        page.child(
            Flow.row()
                .widthRel(1f)
                .height(20)
                .childPadding(3)
                .child(
                    HologramEditorNativeTheme.label(() -> "蓝图库管理")
                        .expanded()
                        .height(20))
                .child(
                    localIcon(
                        HologramEditorControlIds.LIBRARY + 5,
                        "back",
                        () -> "返回",
                        () -> true,
                        () -> pages.setPage(0),
                        () -> "返回蓝图库，保留搜索与滚动位置。").width(60)
                            .height(18))
                .child(
                    HologramEditorNativeTheme
                        .flatIconButton(
                            "collapse-right",
                            () -> "收起蓝图库",
                            ui.shell::toggleLibrary,
                            () -> true,
                            () -> false)
                        .size(HologramEditorNativeTheme.ICON_BUTTON)));
        HologramRecessedList list = new HologramRecessedList().paperEdges()
            .sizeRel(1f)
            .expanded();
        Flow catalog = Flow.row()
            .widthRel(1f)
            .height(18)
            .childPadding(3);
        catalog.child(
            localIcon(
                HologramEditorControlIds.LIBRARY + 4,
                "reset-view",
                () -> "刷新目录",
                this::idle,
                ui.library::nativeRefreshCatalog,
                () -> "刷新真实目录，保留当前工作区。").expanded()
                    .height(18));
        catalog.child(
            action(139, () -> "编辑所选", () -> idle() && ui.library.selectedPresetExists(), () -> "编辑库中所选蓝图。").expanded()
                .height(18));
        list.child(catalog);
        Flow maintenance = Flow.row()
            .widthRel(1f)
            .height(18)
            .childPadding(3);
        maintenance.child(
            localIcon(
                HologramEditorControlIds.LIBRARY + 6,
                "delete",
                () -> "删除所选蓝图",
                this::canDelete,
                () -> ui.router.activate(ui.library.nativeDedicated() ? 205 : 175),
                () -> "沿用原删除确认与权限校验。").expanded()
                    .height(18));
        maintenance.child(
            action(226, () -> "升级旧蓝图", ui.library::nativeCanUpgrade, () -> "保留原文件版本门禁；服务器公库仅管理员可操作。").expanded()
                .height(18));
        list.child(maintenance);
        list.child(
            action(
                207,
                () -> "服务器库设置 · OP 4",
                () -> !ui.session.isTutorial() && ui.library.nativeAdmin(),
                () -> "设置服务器上传、下载与每人配额。").widthRel(1f)
                    .height(18));
        list.child(
            action(
                142,
                () -> "保留 NBT " + (ui.processing.retainNbt() ? "✓" : "×"),
                this::editing,
                () -> "本次蓝图的 NBT 保留选择与资源审核。").widthRel(1f)
                    .height(18));
        list.child(
            action(
                143,
                () -> "保留覆盖板 " + (ui.processing.retainCovers() ? "✓" : "×"),
                this::editing,
                () -> "覆盖板与内部资源分别审核、付款。").widthRel(1f)
                    .height(18));
        list.child(
            action(227, () -> "全局提示词", this::editing, () -> "编辑整份蓝图的提示。").widthRel(1f)
                .height(18));
        list.child(
            action(
                161,
                () -> "导入命名文件",
                () -> idle() && !ui.library.nativeDedicated(),
                () -> "在保存页名称框填写文件名，沿用原文件准入与换稿确认。").widthRel(1f)
                    .height(18));
        list.child(
            action(150, () -> "来源版本", () -> true, () -> "查看来源与依赖。").widthRel(1f)
                .height(18));
        list.child(
            HologramEditorNativeTheme.label(ui.library::nativeStatus)
                .widthRel(1f)
                .height(40));
        page.child(list);
        return page;
    }

    void refresh() {
        String query = search.getStringValue()
            .trim()
            .toLowerCase(Locale.ROOT);
        boolean changed = !previousQuery.equals(query) || previousPublic != ui.library.publicLibrary();
        if (changed) ui.library.libraryOffset(0);
        previousQuery = query;
        previousPublic = ui.library.publicLibrary();
        matches.clear();
        NBTTagList source = ui.library.nativeRows();
        catalogTotal = source.tagCount();
        for (int i = 0; i < source.tagCount(); i++) {
            NBTTagCompound row = source.getCompoundTagAt(i);
            if (query.isEmpty()
                || (row.getString("name") + " " + row.getString("id") + " " + row.getString("authorName"))
                    .toLowerCase(Locale.ROOT)
                    .contains(query))
                matches.add(row);
        }
        total = matches.size();
        int offset = Math
            .max(0, Math.min(ui.library.libraryOffset(), Math.max(0, (total - 1) / ROW_WINDOW * ROW_WINDOW)));
        ui.library.libraryOffset(offset);
        int count = Math.min(ROW_WINDOW, total - offset);
        if (rowList != null) while (rows.size() < count) addRow();
        for (int slot = 0; slot < rows.size(); slot++) {
            NBTTagCompound row = slot < count ? (NBTTagCompound) matches.get(offset + slot)
                .copy() : new NBTTagCompound();
            rows.set(slot, row);
            ui.library.nativeBindRow(slot, row);
        }
        if (changed && rowList != null && rowList.isValid()) rowList.getScrollData()
            .scrollTo(rowList.getScrollArea(), 0);
        dialogs.refresh();
    }

    private void addRow() {
        final int slot = rows.size();
        rows.add(new NBTTagCompound());
        BooleanSupplier available = () -> ui.library.nativeIdle() && !row(slot).getString("id")
            .isEmpty();
        ButtonWidget<?> button = HologramEditorNativeTheme
            .rowButton(
                () -> rowLabel(slot),
                null,
                () -> selectRow(slot),
                available,
                () -> ui.library.nativeSelected(row(slot)))
            .name("library_row_" + slot)
            .sizeRel(1f)
            .tooltipBuilder(t -> t.addLine(rowTooltip(slot)))
            .tooltipAutoUpdate(true);
        IDrawable count = IKey
            .dynamic(() -> row(slot).getInteger("cells") + " 格 · " + row(slot).getInteger("structures") + " 结构")
            .color(HologramEditorNativeTheme.MUTED)
            .shadow(false)
            .alignment(Alignment.CenterLeft);
        button.overlay((context, x, y, width, height, theme) -> {
            boolean voucher = row(slot).getBoolean("fullyVoucherCovered");
            int ribbon = voucher ? HologramVoucherRibbon.PIXELS : 0;
            int textWidth = Math.max(10, width - 10 - ribbon);
            IKey.str(rowTitle(slot, textWidth))
                .color(available.getAsBoolean() ? HologramEditorNativeTheme.TEXT : HologramEditorNativeTheme.MUTED)
                .shadow(false)
                .alignment(Alignment.CenterLeft)
                .draw(context, x + 5, y + 2, textWidth, 24, theme);
            count.draw(context, x + 5, y + 26, Math.max(1, width - 10 - ribbon), 10, theme);
            if (voucher) HologramVoucherRibbon.draw(x, y, width, height);
        });
        HologramEditorNativeTheme.guided(button, () -> ui.canvas.controlAccent(14000 + slot), available);
        ui.shell.registerControl(14000 + slot, button, () -> rowLabel(slot), available);
        ui.shell.registerTarget("library.row." + slot, button);
        rowList.child(
            new ParentWidget<>().widthRel(1f)
                .height(38)
                .setEnabledIf(
                    widget -> !row(slot).getString("id")
                        .isEmpty())
                .child(button));
    }

    private void selectRow(int slot) {
        NBTTagCompound captured = (NBTTagCompound) row(slot).copy();
        if (captured.getString("id")
            .isEmpty()) return;
        defer(() -> {
            if (!ui.library.nativeIdle()) return;
            // Capture before deferral: an async refresh or a search cannot redirect this click to another row.
            ui.library.nativeBindRow(slot, captured);
            ui.router.activate(14000 + slot);
        });
    }

    private ButtonWidget<?> source(boolean server) {
        BooleanSupplier available = () -> idle() && (ui.library.nativeDedicated() || !server);
        ButtonWidget<?> button = HologramEditorNativeTheme.button(() -> server ? "服务器" : "客户端", null, () -> {
            if (ui.library.publicLibrary() != server) defer(
                () -> {
                    if (available.getAsBoolean() && ui.library.publicLibrary() != server) ui.router.activate(201);
                });
        }, available, () -> ui.library.publicLibrary() == server, false);
        button.tooltipBuilder(t -> t.addLine(server ? "浏览真实服务器目录，保留当前工作区。" : "浏览真实客户端目录，保留当前工作区。"));
        ui.shell.registerControl(
            201,
            button,
            () -> server ? "服务器" : "客户端",
            () -> available.getAsBoolean() && ui.library.publicLibrary() != server);
        ui.shell.registerTarget(server ? "library.source.server" : "library.source.client", button);
        return button;
    }

    private ButtonWidget<?> saveMode(boolean overwrite) {
        BooleanSupplier available = () -> !ui.session.isTutorial() && !ui.library.browsePreviewActive();
        ButtonWidget<?> button = HologramEditorNativeTheme.button(() -> overwrite ? "选择覆盖目标" : "保存当前蓝图", null, () -> {
            if (ui.library.overwriteMode() != overwrite) defer(() -> {
                if (!available.getAsBoolean() || ui.library.overwriteMode() == overwrite) return;
                ui.router.activate(222);
                if (overwrite) ui.shell.selectDockPage(2);
            });
        }, available, () -> ui.library.overwriteMode() == overwrite, false);
        ui.shell.registerControl(
            222,
            button,
            () -> overwrite ? "选择覆盖目标" : "保存当前蓝图",
            () -> available.getAsBoolean() && ui.library.overwriteMode() != overwrite);
        ui.shell.registerTarget(overwrite ? "library.save.overwrite" : "library.save.asNew", button);
        return button;
    }

    private String overwriteTargetLabel() {
        String target = ui.library.overwriteTarget();
        if (target.isEmpty()) return "在右侧蓝图库点击要覆盖的目标；当前草稿保持。";
        NBTTagList source = ui.library.nativeRows();
        for (int i = 0; i < source.tagCount(); i++) {
            NBTTagCompound row = source.getCompoundTagAt(i);
            if (target.equals(row.getString("id"))) return "覆盖目标：" + row.getString("name");
        }
        return "覆盖目标：" + target;
    }

    private void offset(int delta) {
        ui.library.libraryOffset(
            Math.max(
                0,
                Math.min(ui.library.libraryOffset() + delta, Math.max(0, (total - 1) / ROW_WINDOW * ROW_WINDOW))));
        if (rowList != null && rowList.isValid()) rowList.getScrollData()
            .scrollTo(rowList.getScrollArea(), 0);
        refresh();
    }

    private NBTTagCompound row(int slot) {
        return rows.get(slot);
    }

    private String rowLabel(int slot) {
        NBTTagCompound row = row(slot);
        return (ui.library.nativeSelected(row) ? "▸ " : "") + row.getString("name");
    }

    /** Font measurement is only called from the row's client draw callback; the tooltip retains the complete name. */
    private String rowTitle(int slot, int width) {
        FontRenderer font = TextRenderer.getFontRenderer();
        List<String> lines = font.listFormattedStringToWidth(rowLabel(slot), width);
        if (lines.size() <= 2) return String.join("\n", lines);
        return lines.get(0) + "\n"
            + font.trimStringToWidth(lines.get(1), Math.max(1, width - font.getStringWidth("...")))
            + "...";
    }

    private String rowTooltip(int slot) {
        NBTTagCompound row = row(slot);
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
            + (ui.library.overwriteMode() ? "\n仅选择覆盖目标；保存时确认。" : "\n点击切换主场景；未保存内容先确认一次。");
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

    private ButtonWidget<?> actionIcon(int id, String icon, Supplier<String> label, BooleanSupplier enabled,
        Supplier<String> help) {
        return localIcon(
            id,
            icon,
            label,
            enabled,
            () -> defer(() -> { if (enabled.getAsBoolean()) ui.router.activate(id); }),
            help);
    }

    private ButtonWidget<?> localIcon(int id, String icon, Supplier<String> label, BooleanSupplier enabled,
        Runnable action, Supplier<String> help) {
        ButtonWidget<?> button = HologramEditorNativeTheme.button(label, icon, action, enabled, () -> false, false)
            .tooltipBuilder(t -> t.addLine(help.get()))
            .tooltipAutoUpdate(true);
        HologramEditorNativeTheme.guided(button, () -> ui.canvas.controlAccent(id), enabled);
        ui.shell.registerControl(id, button, label, enabled);
        return button;
    }

    private void defer(Runnable action) {
        ui.host.parent()
            .deferAction(() -> {
                try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                    .fieldGraph(ui)) {
                    action.run();
                }
            });
    }

    private ButtonWidget<?> local(int id, Supplier<String> label, BooleanSupplier enabled, Runnable action) {
        return ui.shell.nativeAction(id, label, enabled, () -> "管理当前目录视图，保留工作区。")
            .onMousePressed(button -> {
                if (button == 0 && enabled.getAsBoolean()) action.run();
                return true;
            });
    }
}
