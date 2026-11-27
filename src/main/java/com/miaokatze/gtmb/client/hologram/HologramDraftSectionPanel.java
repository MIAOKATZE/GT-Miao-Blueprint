// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projector;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Unit;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** Not a catalog entry: one native panel owns one local draft revision and one drawn section. */
final class HologramDraftSectionPanel {

    private static final String OPT_IN = "gtmb.experimental.privateDraftSectionReview";
    private final HologramUiModules ui;
    private IPanelHandler handler;
    private ModularPanel panel;
    private Owner owner;

    HologramDraftSectionPanel(HologramUiModules ui) {
        this.ui = ui;
    }

    static boolean enabled() {
        return Boolean.getBoolean(OPT_IN);
    }

    ButtonWidget<?> button() {
        return HologramEditorNativeTheme.button(() -> projector("library.draft_sections"), null,
            () -> ui.host.parent().deferAction(this::open), this::canOpen, () -> false, false)
            .tooltipBuilder(t -> t.addLine(projector("library.draft_sections_help")))
            .widthRel(1f).height(20);
    }

    private boolean canOpen() {
        var doc = ui.session.document();
        var state = ui.session.state();
        var mc = ui.host.minecraft();
        return enabled() && owner == null && doc != null && !doc.packedAuthoring() && ui.library.nativeIdle()
            && mc.func_152345_ab() && mc.currentScreen == ui.host.parent() && mc.theWorld != null && mc.thePlayer != null
            && mc.getNetHandler() != null && HologramTextures.resourceGeneration() > 0
            && ui.shell.libraryPageSelected() && !ui.library.publicLibrary() && !ui.session.isTutorial()
            && !ui.tutorial.active() && !HologramLocalPlacement.INSTANCE.active() && !ui.shell.hasModalPanel()
            && HologramDraftSectionReview.available(state)
            && doc.ownsPresentation(state.session, state.data.getLong("planRevision"), state.cells.size());
    }

    private void open() {
        if (!canOpen()) return;
        if (handler == null) handler = IPanelHandler.simple(ui.shell.host(), (parent, player) -> build(), true);
        Owner started = new Owner();
        owner = started;
        try {
            handler.openPanel();
            if (!started.current()) { close(); return; }
            var source = started.document.freeze();
            var view = ui.session.state();
            HologramLocalBlueprintWorkspace.executeDraftReview(() -> {
                try {
                    var review = HologramDraftSectionReview.stage(source, view, () -> !started.closed);
                    started.mc.func_152344_a(() -> {
                        if (started.current()) {
                            started.review = review;
                            started.request(0);
                        }
                    });
                } catch (Exception | LinkageError invalid) {
                    started.mc.func_152344_a(started::failed);
                }
            });
        } catch (RuntimeException | LinkageError invalid) {
            if (started.current()) started.failed();
            else close();
        }
    }

    private ModularPanel build() {
        Dialog<?> dialog = new Dialog<>(ui.panelName("gtmb_private_draft_section_review"), null);
        panel = dialog;
        dialog.setDisablePanelsBelow(true).setCloseOnOutOfBoundsClick(false).setDraggable(false)
            .width(() -> Math.max(1, Math.min(396, ui.host.screenWidth() - 12)), Unit.Measure.PIXEL)
            .height(() -> Math.max(1, Math.min(300, ui.host.screenHeight() - 12)), Unit.Measure.PIXEL)
            .background(HologramEditorNativeTheme.PANEL);
        dialog.onCloseAction(this::retire);
        Flow body = Flow.col().sizeRel(1f).padding(10).childPadding(4);
        body.child(HologramEditorNativeTheme.label(() -> projector("library.draft_sections"),
            HologramEditorNativeTheme.TITLE).widthRel(1f).height(18));
        body.child(HologramEditorNativeTheme.label(() -> owner == null ? "" : owner.label()).widthRel(1f).height(24));
        body.child(new Scene().sizeRel(1f).expanded());
        body.child(Flow.row().widthRel(1f).height(20).childPadding(5)
            .child(navigation(-1, "library.previous")).child(navigation(1, "library.next"))
            .child(HologramEditorNativeTheme.button(() -> projector("dialog.close"),
                () -> ui.host.parent().deferAction(this::close)).expanded().height(20)));
        return dialog.child(body);
    }

    private ButtonWidget<?> navigation(int delta, String key) {
        return HologramEditorNativeTheme.button(() -> projector(key), null, () -> {
            Owner clicked = owner;
            int next = clicked == null ? -1 : clicked.ordinal + delta;
            ui.host.parent().deferAction(() -> {
                if (clicked != null && clicked.current() && clicked.review != null && !clicked.error
                    && next >= 0 && next < clicked.review.pages()) clicked.request(next);
            });
        }, () -> owner != null && owner.review != null && !owner.error && owner.ready != null
            && owner.ordinal + delta >= 0 && owner.ordinal + delta < owner.review.pages(), () -> false, false)
            .expanded().height(20);
    }

    void tick() {
        if (owner == null) return;
        if (!owner.current()) { close(); return; }
        Owner current = owner;
        try {
            if (current.preparation != null && current.preparation.step(128, 1_000_000L)) {
                current.ready = current.preparation.result();
                current.preparation = null;
            }
        } catch (RuntimeException | LinkageError invalid) {
            current.failed();
        }
    }

    void close() {
        retire();
        if (handler != null && handler.isPanelOpen()) handler.closePanel();
    }

    private void retire() {
        Owner old = owner;
        owner = null;
        if (old == null) return;
        old.closed = true;
        old.clearPage();
        old.review = null;
    }

    private final class Owner {

        final Minecraft mc = ui.host.minecraft();
        final Object world = mc.theWorld, player = mc.thePlayer, connection = mc.getNetHandler();
        final int dimension = mc.thePlayer.dimension;
        final long resources = HologramTextures.resourceGeneration();
        final HologramLocalBlueprintDocument document = ui.session.document();
        final long revision = document.revision();
        final String session = ui.session.state().session;
        final MiaoVisonLiteRenderer renderer = new MiaoVisonLiteRenderer(new HologramPreviewAssets(),
            MiaoVisonLiteRenderer.View.LIBRARY);
        final Object sourceIdentity = ui.session.state().identity;
        HologramState ready;
        HologramState.Preparation preparation;
        HologramDraftSectionReview review;
        volatile boolean closed;
        volatile long epoch;
        int ordinal, sx, sy, sz;
        boolean error;

        boolean current() {
            return !closed && owner == this && mc.func_152345_ab() && mc.currentScreen == ui.host.parent()
                && mc.theWorld == world && mc.thePlayer == player && mc.thePlayer.dimension == dimension
                && mc.getNetHandler() == connection && HologramTextures.resourceGeneration() == resources
                && handler.isPanelOpen() && panel != null
                && ui.shell.host().getScreen().getPanelManager().getTopMostPanel() == panel
                && ui.shell.libraryPageSelected() && !ui.library.publicLibrary() && !ui.session.isTutorial()
                && !ui.tutorial.active() && !HologramLocalPlacement.INSTANCE.active()
                && ui.session.document() == document && ui.session.state().identity == sourceIdentity
                && session.equals(ui.session.state().session)
                && revision == document.revision() && ui.session.state().data.getLong("planRevision") == revision;
        }

        void request(int next) {
            long ticket = ++epoch;
            clearPage();
            ordinal = next;
            HologramDraftSectionReview active = review;
            try {
                HologramLocalBlueprintWorkspace.executeDraftReview(() -> {
                    try {
                        var page = active.read(next, () -> !closed && epoch == ticket);
                        mc.func_152344_a(() -> {
                            if (current() && epoch == ticket) {
                                try {
                                    accept(page);
                                } catch (RuntimeException | LinkageError rejected) {
                                    failed();
                                }
                            }
                        });
                    } catch (Exception | LinkageError invalid) {
                        mc.func_152344_a(() -> { if (epoch == ticket) failed(); });
                    }
                });
            } catch (RuntimeException invalid) {
                failed();
            }
        }

        void accept(NBTTagCompound page) {
            sx = page.getInteger("sectionX");
            sy = page.getInteger("sectionY");
            sz = page.getInteger("sectionZ");
            preparation = new HologramState.Preparation(page, true, null);
        }

        String label() {
            if (error) return projector("library.draft_sections_unavailable");
            if (ready == null) return projector("library.draft_sections_loading");
            return projector("library.draft_sections_page", ordinal + 1, review.pages(), review.cells, sx, sy, sz);
        }

        void failed() {
            if (!current()) return;
            epoch++;
            error = true;
            clearPage();
            review = null;
        }

        void clearPage() {
            preparation = null;
            ready = null;
            try {
                renderer.close();
            } catch (RuntimeException | LinkageError ignored) {
                // Retire page ownership even if this independent preview renderer cannot cleanly release.
            }
        }
    }

    private final class Scene extends Widget<Scene> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            Owner visible = owner;
            if (visible == null || !visible.current() || visible.ready == null) return;
            int[] box = ui.shell.nativeWidgetBounds(this);
            int w = getArea().width, h = getArea().height;
            if (box == null || w <= 0 || h <= 0) return;
            try {
                ViewportCoordinates.FramebufferRect scissor = new ViewportCoordinates(box[0], box[1], box[2], box[3])
                    .framebufferRect(ui.host.screenWidth(), ui.host.screenHeight(), visible.mc.displayWidth,
                        visible.mc.displayHeight);
                GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
                try {
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);
                    GL11.glScissor(scissor.left(), scissor.bottom(), scissor.width(), scissor.height());
                    visible.renderer.preview(visible.ready, 0, 0, w, h, .6, .48, 1, 0, 0, Integer.MAX_VALUE, 0,
                        -1, -1, -1, -1);
                } finally {
                    GL11.glPopAttrib();
                }
            } catch (RuntimeException | LinkageError invalid) {
                visible.failed();
            }
        }

        @Override
        public void dispose() {
            retire();
            super.dispose();
        }
    }
}
