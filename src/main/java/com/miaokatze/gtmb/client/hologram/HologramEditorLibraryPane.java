// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projector;

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
    private final HologramDraftSectionPanel draftSections;
    private IWidget built, save;
    private HologramRecessedList rowList;
    private String previousQuery = "";
    private boolean previousPublic;
    private int total, catalogTotal;

    HologramEditorLibraryPane(HologramUiModules ui) {
        this.ui = ui;
        dialogs = new HologramEditorNativeLibraryDialogs(ui);
        draftSections = HologramDraftSectionPanel.enabled() ? new HologramDraftSectionPanel(ui) : null;
    }

    void tickDraftSections() {
        if (draftSections != null) draftSections.tick();
    }

    void closeDraftSections() {
        if (draftSections != null) draftSections.close();
    }

    void showMaintenance() {
        if (built != null) pages.setPage(1);
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
            HologramEditorNativeTheme.label(() -> projector("library.save_shortcut"))
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
                () -> ui.library.overwriteMode() ? projector("library.update") : projector("library.save"),
                ui.library::nativeCanSave,
                () -> projector("library.save_help")).widthRel(1f)
                    .height(20));
        page.child(
            action(132, () -> projector("library.save_copy"), ui.library::nativeCanSave, () -> projector("library.save_copy_help")).widthRel(1f)
                .height(18));
        page.child(
            HologramEditorNativeTheme.label(ui.library::nativeStatus)
                .widthRel(1f)
                .height(36));
        page.child(
            HologramEditorNativeTheme.label(() -> projector("library.files"), HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(20));
        page.child(
            HologramEditorNativeTheme.label(() -> projector("library.files_help"))
                .widthRel(1f)
                .height(30));
        page.child(
            action(
                159,
                () -> projector("library.export"),
                () -> idle() && !ui.library.nativeDedicated() && ui.library.selectedPresetExists(),
                () -> projector("library.export_help")).widthRel(1f)
                    .height(18));
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
                    HologramEditorNativeTheme.label(() -> projector("library.title"), HologramEditorNativeTheme.TITLE)
                        .name("library_title")
                        .expanded()
                        .height(20))
                .child(
                    HologramEditorNativeTheme
                        .flatIconButton(
                            "collapse-right",
                            () -> projector("library.collapse"),
                            ui.shell::toggleLibrary,
                            () -> true,
                            () -> false)
                        .size(HologramEditorNativeTheme.ICON_BUTTON)));
        ButtonWidget<?> website = action(309, () -> projector("library.website"), this::websiteReady, () -> projector("library.website_help"))
            .name("library_website")
            .expanded()
            .height(20);
        ButtonWidget<?> manage = localIcon(
            HologramEditorControlIds.LIBRARY + 3,
            "settings",
            () -> projector("library.manage"),
            () -> true,
            () -> pages.setPage(1),
            () -> projector("library.manage_help")).name("library_manage")
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
            .hintText(projector("library.search"))
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
            HologramEditorNativeTheme.label(() -> catalogTotal == 0 ? projector("library.empty") : projector("library.no_match"))
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
                        () -> projector("library.previous"),
                        () -> idle() && ui.library.nativeServerPage() > 0,
                        () -> projector("library.previous_help")).width(40)
                            .height(18))
                .child(
                    HologramEditorNativeTheme
                        .label(() -> (ui.library.nativeServerPage() + 1) + " / " + ui.library.nativeServerPages())
                        .expanded()
                        .height(18))
                .child(
                    action(
                        209,
                        () -> projector("library.next"),
                        () -> idle() && ui.library.nativeServerPage() + 1 < ui.library.nativeServerPages(),
                        () -> projector("library.next_help")).width(40)
                            .height(18)));
        footer.child(
            action(
                311,
                () -> projector("library.place_current"),
                ui.library::nativeCanPlaceCurrent,
                () -> projector("library.place_current_help")).name("place_current_blueprint")
                    .widthRel(1f)
                    .height(HologramEditorNativeTheme.PRIMARY_BUTTON_HEIGHT));
        footer.child(
            action(
                141,
                () -> projector("library.place_selected"),
                () -> idle() && ui.library.selectedPresetExists() && ui.library.nativeCanReadSelected(),
                () -> projector("library.place_selected_help")).widthRel(1f)
                    .height(HologramEditorNativeTheme.PRIMARY_BUTTON_HEIGHT));
        Flow secondary = Flow.col()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(3);
        secondary.child(
            actionIcon(
                203,
                "network",
                () -> ui.library.publicLibrary() ? projector("library.download") : projector("library.publish"),
                () -> idle() && ui.library.nativeDedicated()
                    && ui.library.selectedPresetExists()
                    && ui.library.nativeCanTransferSelected(),
                () -> projector("library.transfer_help")).widthRel(1f)
                    .height(20));
        secondary.child(
            action(299, () -> projector("library.discard"), () -> idle() && !ui.session.state().cells.isEmpty(), () -> projector("library.discard_help"))
                .widthRel(1f)
                .height(20));
        footer.child(secondary);
        if (draftSections != null) footer.child(Flow.row().widthRel(1f).height(20)
            .setEnabledIf(widget -> ui.session.localDocument() && !ui.library.publicLibrary())
            .child(draftSections.button().name("library_private_draft_sections")));
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
                    HologramEditorNativeTheme.label(() -> projector("library.manage_title"))
                        .expanded()
                        .height(20))
                .child(
                    localIcon(
                        HologramEditorControlIds.LIBRARY + 5,
                        "back",
                        () -> projector("library.back"),
                        () -> true,
                        () -> pages.setPage(0),
                        () -> projector("library.back_help")).width(60)
                            .height(18))
                .child(
                    HologramEditorNativeTheme
                        .flatIconButton(
                            "collapse-right",
                            () -> projector("library.collapse"),
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
                () -> projector("library.refresh"),
                this::idle,
                ui.library::nativeRefreshCatalog,
                () -> projector("library.refresh_help")).expanded()
                    .height(18));
        catalog.child(
            action(
                139,
                () -> projector("library.edit_selected"),
                () -> idle() && ui.library.selectedPresetExists() && ui.library.nativeCanReadSelected(),
                () -> projector("library.edit_selected_help")).expanded()
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
                () -> projector("library.delete_selected"),
                this::canDelete,
                () -> ui.router.activate(ui.library.nativeDedicated() ? 205 : 175),
                () -> projector("library.delete_help")).expanded()
                    .height(18));
        maintenance.child(
            action(
                226,
                () -> projector("library.upgrade"),
                ui.library::nativeCanUpgrade,
                () -> projector("library.upgrade_help"))
                    .expanded()
                    .height(18));
        list.child(maintenance);
        list.child(
            action(
                207,
                () -> projector("library.server_settings"),
                () -> !ui.session.isTutorial() && ui.library.nativeAdmin(),
                () -> projector("library.server_settings_help")).widthRel(1f)
                    .height(18));
        list.child(
            action(
                142,
                () -> projector("library.keep_nbt", ui.processing.retainNbt() ? "✓" : "×"),
                this::editing,
                () -> projector("library.keep_nbt_help")).widthRel(1f)
                    .height(18));
        list.child(
            action(
                143,
                () -> projector("library.keep_covers", ui.processing.retainCovers() ? "✓" : "×"),
                this::editing,
                () -> projector("library.keep_covers_help")).widthRel(1f)
                    .height(18));
        list.child(
            action(227, () -> projector("editor.global_prompt"), this::editing, () -> projector("library.global_prompt_help")).widthRel(1f)
                .height(18));
        list.child(
            action(
                161,
                () -> projector("library.import_named"),
                this::idle,
                () -> projector("library.import_named_help")).widthRel(1f)
                    .height(18));
        list.child(
            localIcon(
                HologramEditorControlIds.LIBRARY + 7,
                "settings",
                () -> projector("library.compact_named"),
                ui.library::nativeCanUpgradeNamed,
                ui.library::nativeUpgradeNamed,
                () -> projector("library.compact_named_help")).widthRel(1f)
                    .height(18));
        list.child(
            action(150, () -> projector("library.source_version"), () -> true, () -> projector("library.source_version_help")).widthRel(1f)
                .height(18));
        list.child(
            HologramEditorNativeTheme.label(ui.library::nativeStatus)
                .widthRel(1f)
                .height(40));
        page.child(list);
        return page;
    }

    void refresh() {
        if (built == null) {
            dialogs.refresh();
            return;
        }
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
            .dynamic(
                () -> projector(
                    "library.row_counts",
                    row(slot).getInteger("cells"),
                    row(slot).getInteger("structures")))
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
        ButtonWidget<?> button = HologramEditorNativeTheme.button(() -> server ? projector("library.server") : projector("library.client"), null, () -> {
            if (ui.library.publicLibrary() != server) defer(
                () -> {
                    if (available.getAsBoolean() && ui.library.publicLibrary() != server) ui.router.activate(201);
                });
        }, available, () -> ui.library.publicLibrary() == server, false);
        button.tooltipBuilder(t -> t.addLine(server ? projector("library.server_help") : projector("library.client_help")));
        ui.shell.registerControl(
            201,
            button,
            () -> server ? projector("library.server") : projector("library.client"),
            () -> available.getAsBoolean() && ui.library.publicLibrary() != server);
        ui.shell.registerTarget(server ? "library.source.server" : "library.source.client", button);
        return button;
    }

    private ButtonWidget<?> saveMode(boolean overwrite) {
        BooleanSupplier available = () -> !ui.session.isTutorial() && !ui.library.browsePreviewActive();
        ButtonWidget<?> button = HologramEditorNativeTheme.button(() -> overwrite ? projector("library.choose_overwrite") : projector("library.save_current"), null, () -> {
            if (ui.library.overwriteMode() != overwrite) defer(() -> {
                if (!available.getAsBoolean() || ui.library.overwriteMode() == overwrite) return;
                ui.router.activate(222);
                if (overwrite) ui.shell.selectDockPage(2);
            });
        }, available, () -> ui.library.overwriteMode() == overwrite, false);
        ui.shell.registerControl(
            222,
            button,
            () -> overwrite ? projector("library.choose_overwrite") : projector("library.save_current"),
            () -> available.getAsBoolean() && ui.library.overwriteMode() != overwrite);
        ui.shell.registerTarget(overwrite ? "library.save.overwrite" : "library.save.asNew", button);
        return button;
    }

    private String overwriteTargetLabel() {
        String target = ui.library.overwriteTarget();
        if (target.isEmpty()) return projector("library.choose_overwrite_help");
        NBTTagList source = ui.library.nativeRows();
        for (int i = 0; i < source.tagCount(); i++) {
            NBTTagCompound row = source.getCompoundTagAt(i);
            if (target.equals(row.getString("id"))) return projector("library.overwrite_target", row.getString("name"));
        }
        return projector("library.overwrite_target", target);
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
            + projector("library.row_counts", row.getInteger("cells"), row.getInteger("structures"))
            + " · "
            + ui.library.blueprintSource(row)
            + "\n"
            + projector("library.author", row.getString("authorName"))
            + (ui.library.nativeDedicated() && ui.library.publicLibrary()
                ? "\n" + projector("library.uploader", row.getString("uploadedBy"))
                : "")
            + (row.getBoolean("fullyVoucherCovered") ? "\n" + projector("library.voucher_help") : "")
            + "\n"
            + (ui.library.overwriteMode() ? projector("library.row_overwrite") : projector("library.row_browse"));
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
        return ui.shell.nativeAction(id, label, enabled, () -> projector("library.view_help"))
            .onMousePressed(button -> {
                if (button == 0 && enabled.getAsBoolean()) action.run();
                return true;
            });
    }
}
