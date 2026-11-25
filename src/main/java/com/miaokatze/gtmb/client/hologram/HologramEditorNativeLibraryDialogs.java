// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projector;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Client-local native popups with one handler and one widget tree per purpose for the screen lifetime. */
final class HologramEditorNativeLibraryDialogs {

    private enum Kind {
        DISCARD,
        OVERWRITE,
        DELETE,
        ADMIN,
        CONVERSION,
        PRESET
    }

    private final HologramUiModules ui;
    private final Map<Kind, IPanelHandler> handlers = new EnumMap<>(Kind.class);
    private final StringValue presetName = new StringValue("");
    private Kind displayed;
    private boolean handingOffProjection;

    HologramEditorNativeLibraryDialogs(HologramUiModules ui) {
        this.ui = ui;
    }

    void refresh() {
        Kind desired = desired();
        IPanelHandler current = displayed == null ? null : handlers.get(displayed);
        if (desired == displayed && (desired == null || current != null && current.isPanelOpen())) return;
        // No separate queued latch: the screen accepts one deferred intent. If
        // another input already owns it, the next update retries this transition.
        ui.host.parent()
            .deferAction(this::transition);
    }

    private Kind desired() {
        if (ui.library.conversionPromptOpen()) return Kind.CONVERSION;
        if (ui.processing.processingPresetSaveReview()) return Kind.PRESET;
        if (ui.library.overwriteReview()) return Kind.OVERWRITE;
        if (ui.library.discardReview()) return Kind.DISCARD;
        if (!ui.library.deletePresetId()
            .isEmpty()) return Kind.DELETE;
        return ui.library.libraryAdminDrawer() ? Kind.ADMIN : null;
    }

    private void transition() {
        Kind desired = desired();
        displayed = desired;
        for (Map.Entry<Kind, IPanelHandler> entry : handlers.entrySet())
            if (entry.getKey() != desired && entry.getValue()
                .isPanelOpen())
                entry.getValue()
                    .closePanel();
        if (desired == null) return;
        IPanelHandler handler = handlers.computeIfAbsent(
            desired,
            key -> IPanelHandler.simple(ui.shell.host(), (parent, player) -> build(key), true));
        if (handler.isPanelOpen()) return;
        if (desired == Kind.PRESET) presetName.setStringValue(
            ui.processing.processingPresetField()
                .getText());
        handler.openPanel();
    }

    private ModularPanel build(Kind kind) {
        Dialog<?> dialog = new Dialog<Void>(
            ui.panelName(
                "gtmb_native_library_" + kind.name()
                    .toLowerCase(java.util.Locale.ROOT)),
            null) {

            @Override
            public boolean shouldAnimate() {
                // A committed source switch retires this graph immediately; no invisible closing panel
                // may survive in MUI's open-panel stack and shade/swallow input to the destination.
                return !(kind == Kind.DISCARD && handingOffProjection) && super.shouldAnimate();
            }
        };
        dialog.setDisablePanelsBelow(true)
            .setCloseOnOutOfBoundsClick(false)
            .setDraggable(true)
            .size(kind == Kind.ADMIN ? 460 : 360, kind == Kind.ADMIN ? 350 : kind == Kind.PRESET ? 196 : 184)
            .background(HologramEditorNativeTheme.PANEL);
        if (kind == Kind.ADMIN) dialog
            .width(
                () -> HologramLibraryAdminEditor.nativeWidth(ui.host.screenWidth()),
                com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .height(
                () -> HologramLibraryAdminEditor.nativeHeight(ui.host.screenHeight()),
                com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL);
        dialog.onCloseAction(() -> {
            if (kind == Kind.DISCARD && handingOffProjection) return; // confirmed, not cancelled
            if (kind == Kind.CONVERSION) {
                ui.library.cancelConversionPrompt();
                return;
            }
            if (kind == Kind.ADMIN) {
                ui.library.libraryAdminDrawer(false);
                return;
            }
            if (desired() == kind && ui.host.minecraft().currentScreen == ui.host.parent())
                ui.router.activate(cancelId(kind));
        });
        Flow content = Flow.col()
            .sizeRel(1f)
            .padding(12)
            .childPadding(kind == Kind.ADMIN ? 3 : 7);
        content.child(
            HologramEditorNativeTheme.label(() -> title(kind), HologramEditorNativeTheme.TITLE)
                .widthRel(1f)
                .height(22));
        if (kind == Kind.CONVERSION) {
            content.child(
                HologramEditorNativeTheme.label(() -> ui.library.conversionPromptName())
                    .widthRel(1f)
                    .height(22));
            content.child(
                HologramEditorNativeTheme
                    .label(
                        () -> ui.library.promptCanConvert() ? projector("library.convert_selected_help")
                            : projector("library.convert_unpaired"))
                    .widthRel(1f)
                    .expanded());
            Flow choices = Flow.row()
                .widthRel(1f)
                .height(24)
                .childPadding(4);
            choices.child(
                conversionChoice(
                    "library.upgrade_one",
                    ui.library::promptCanConvert,
                    () -> ui.library.confirmConversionPrompt(false)));
            choices.child(
                conversionChoice(
                    "library.upgrade",
                    ui.library::nativeCanUpgrade,
                    () -> ui.library.confirmConversionPrompt(true)));
            choices.child(conversionChoice("dialog.cancel", () -> true, ui.library::cancelConversionPrompt));
            content.child(choices);
            dialog.child(content);
            return dialog;
        }
        if (kind == Kind.ADMIN) admin(content);
        else if (kind == Kind.PRESET) {
            content.child(
                HologramEditorNativeTheme.label(() -> projector("library.preset_help"))
                    .widthRel(1f)
                    .height(32));
            content.child(
                HologramEditorNativeTheme.textField(presetName, 64)
                    .name("processing_preset_name")
                    .widthRel(1f)
                    .height(22));
            content.child(
                HologramEditorNativeTheme.label(() -> projector("library.preset_scope"))
                    .sizeRel(1f)
                    .expanded());
        } else {
            content.child(
                HologramEditorNativeTheme
                    .label(
                        () -> kind == Kind.DELETE ? projector("dialog.delete_body", ui.library.nativeDeleteName())
                            : ui.library.nativeReviewBody())
                    .widthRel(1f)
                    .height(48));
            content.child(
                HologramEditorNativeTheme
                    .label(
                        () -> kind == Kind.DELETE ? projector("library.delete_scope") : ui.library.nativeReviewTarget())
                    .sizeRel(1f)
                    .expanded());
        }
        Flow footer = Flow.row()
            .widthRel(1f)
            .height(24)
            .childPadding(8);
        ButtonWidget<?> cancel = ui.shell
            .nativeAction(
                cancelId(kind),
                () -> kind == Kind.ADMIN ? projector("dialog.close") : projector("dialog.cancel"),
                () -> true,
                () -> "")
            .widthRel(1f)
            .expanded()
            .height(24);
        if (kind == Kind.ADMIN) cancel.onMousePressed(button -> {
            if (button != 0) return true;
            ui.library.libraryAdminDrawer(false);
            dialog.closeIfOpen();
            return true;
        });
        ui.shell.registerTarget("modal.close", cancel);
        footer.child(cancel);
        footer.child(
            confirm(kind, dialog).widthRel(1f)
                .expanded()
                .height(24));
        content.child(footer);
        dialog.child(content);
        return dialog;
    }

    private ButtonWidget<?> conversionChoice(String key, BooleanSupplier enabled, Runnable click) {
        return HologramEditorNativeTheme.button(() -> projector(key), null, click, enabled, () -> false, false)
            .widthRel(1f)
            .expanded()
            .height(24);
    }

    private void admin(Flow content) {
        content.child(
            ui.library.adminEditor()
                .buildNative());
        content.child(
            HologramEditorNativeTheme.label(
                () -> ui.library.adminEditor()
                    .status())
                .widthRel(1f)
                .height(32));
    }

    private ButtonWidget<?> confirm(Kind kind, Dialog<?> dialog) {
        int id = kind == Kind.DELETE ? 176 : kind == Kind.ADMIN ? 210 : 224;
        BooleanSupplier enabled = () -> kind == Kind.ADMIN ? ui.library.nativeAdmin() && ui.library.adminEditor()
            .canSave()
            : kind == Kind.PRESET ? ui.session.editable() && !presetName.getStringValue()
                .trim()
                .isEmpty()
                : kind == Kind.DELETE ? ui.session.editable()
                    : ui.session.editable()
                        || HologramBackgroundTasks.awaitingSourceReview() && ui.session.pendingSequence() < 0;
        Supplier<String> label = () -> kind == Kind.PRESET ? projector("dialog.confirm_preset")
            : kind == Kind.DELETE ? projector("dialog.confirm_delete")
                : kind == Kind.ADMIN ? projector("dialog.confirm_admin")
                    : kind == Kind.OVERWRITE ? projector("dialog.confirm_overwrite")
                        : ui.library.clearWorkspaceReview() ? projector("dialog.confirm_clear")
                            : ui.library.projectionDiscardReview() ? projector("dialog.confirm_projection")
                                : projector("dialog.confirm_discard");
        return ui.shell.nativeAction(id, label, enabled, () -> "")
            .onMousePressed(button -> {
                if (button != 0 || !enabled.getAsBoolean() || desired() != kind) return true;
                if (kind == Kind.DISCARD && ui.library.projectionDiscardReview()) {
                    NBTTagCompound reviewed = ui.library.sourceSwitchProposal();
                    HologramScreen owner = ui.host.parent();
                    owner.deferNativeCommand(() -> {
                        if (owner.ui != ui || !dialog.isOpen()
                            || desired() != kind
                            || !enabled.getAsBoolean()
                            || !reviewed.equals(ui.library.sourceSwitchProposal())) return;
                        // Run outside MUI's mouse dispatch. Close the actual modal before the local/synchronous
                        // workspace change can pause its graph; a subsequent refresh of this graph may never run.
                        handingOffProjection = true;
                        try {
                            dialog.closeIfOpen();
                        } finally {
                            handingOffProjection = false;
                        }
                        if (!dialog.isOpen()) ui.router.activate(id);
                    });
                    return true;
                }
                if (kind == Kind.PRESET) ui.processing.processingPresetField()
                    .setText(presetName.getStringValue());
                ui.router.activate(id);
                return true;
            });
    }

    private int cancelId(Kind kind) {
        return kind == Kind.DELETE ? 177 : kind == Kind.ADMIN ? 211 : 225;
    }

    private String title(Kind kind) {
        return kind == Kind.CONVERSION ? projector("library.convert_required")
            : kind == Kind.ADMIN ? projector("library.admin_title")
                : kind == Kind.DELETE ? projector("library.delete_selected")
                    : kind == Kind.PRESET ? projector("dialog.preset_title") : ui.library.nativeReviewTitle();
    }
}
