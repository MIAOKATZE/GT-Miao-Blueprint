// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;

/** One optional observer around the existing selectPin dispatch; never routes input or decides acceptance. */
final class HologramEbfHatchClickLedger {

    static final String FLAG = "gtmb.testlab.localPreviewStagesEbfHatchClickLedger";
    private final HologramScreen screen;
    private final HologramState baseline;
    private final IWidget scene;
    private final String oldPin;
    private final BooleanSupplier fixtureOwner;
    private final HologramUiModules graph;
    private final Map<IWidget, Integer> hoverIds = new IdentityHashMap<>();
    private final Map<String, Object> row = new LinkedHashMap<>();
    private final Map<String, Object> phases = new LinkedHashMap<>();

    static boolean enabled(boolean requested, boolean coilOnly, String label) {
        return requested && !coilOnly && "selectPin".equals(label);
    }

    HologramEbfHatchClickLedger(HologramScreen screen, HologramState baseline, IWidget scene,
        Map<String, Object> evidence, int[] clippedScene, int[] pickedLocal, BooleanSupplier fixtureOwner) {
        this.screen = screen;
        this.baseline = baseline;
        this.scene = scene;
        this.fixtureOwner = fixtureOwner;
        this.graph = screen.ui;
        this.oldPin = screen.ui.annotations.pinnedAnnotation();
        evidence.put("clickLedger", row);
        row.put("schema", "gtmb-ebf-hatch-click-ledger-v1");
        row.put("acceptedWidget", "not exposed by ModularPanel in MUI2 2.3.94; handled is not a widget receipt");
        row.put("pickedLocal", pickedLocal);
        row.put("sceneClip", clippedScene);
        row.put("phases", phases);
        // After the existing onFrameUpdate, before input: the same open panel and hover ordering used by MUI.
        try {
            var mui = screen.getScreen();
            var ctx = mui.getContext();
            int x = ctx.getAbsMouseX(), y = ctx.getAbsMouseY();
            List<Map<String, Object>> panels = new ArrayList<>();
            row.put("panelsBeforePress", panels);
            for (var panel : mui.getPanelManager().getOpenPanels()) {
                var p = new LinkedHashMap<String, Object>();
                panels.add(p);
                p.put("class", panel.getClass().getName());
                p.put("scenePanel", panel == scene.getPanel());
                p.put("enabled", panel.isEnabled());
                p.put("blocksBelow", panel.disablePanelsBelow());
                var hits = new ArrayList<Map<String, Object>>();
                p.put("hoverInDispatchOrder", hits);
                for (var located : panel.getHovering()) {
                    IWidget w = located.getElement();
                    var h = new LinkedHashMap<String, Object>();
                    hits.add(h);
                    h.put("class", type(w));
                    h.put("isScene", w == scene);
                    h.put("valid", w != null && w.isValid());
                    if (w == null || !w.isValid()) continue;
                    int id = hoverIds.size();
                    if (!hoverIds.containsKey(w)) hoverIds.put(w, id);
                    h.put("hoverId", hoverIds.get(w)); // receipt-local only
                    h.put("interactable", w instanceof Interactable);
                    h.put("clickThrough", w.canClickThrough());
                    h.put("pointerInsideOwnClip", inside(screen.ui.shell.nativeWidgetBounds(w), x, y));
                    if (w == scene) {
                        var matrix = located.getTransformationMatrix();
                        int[] local = { matrix.unTransformX(x, y), matrix.unTransformY(x, y) };
                        h.put("dispatchLocal", local);
                        h.put("dispatchLocalEqualsPickedLocal", Arrays.equals(local, pickedLocal));
                    }
                }
            }
        } catch (RuntimeException e) {
            row.put("hoverObservationErrorClass", e.getClass().getName());
        }
        sample("beforePress");
    }

    static boolean inside(int[] r, int x, int y) {
        return r != null && r.length == 4 && r[2] > 0 && r[3] > 0 && x >= r[0] && y >= r[1]
            && x < (long) r[0] + r[2] && y < (long) r[1] + r[3];
    }

    static boolean separate(int[] a, int[] b) {
        return a != null && b != null && a.length == 4 && b.length == 4 && a[2] > 0 && a[3] > 0
            && b[2] > 0 && b[3] > 0 && ((long) a[0] + a[2] <= b[0] || (long) b[0] + b[2] <= a[0]
                || (long) a[1] + a[3] <= b[1] || (long) b[1] + b[3] <= a[1]);
    }

    private static String type(IWidget w) {
        return w == null ? null : w.getClass().getName();
    }

    // Between press and release keep this bounded: no layout scan, hover refresh, picker, delays, IO or callbacks.
    void sample(String phase) {
        var s = new LinkedHashMap<String, Object>();
        phases.put(phase, s);
        try {
            var ui = screen.ui;
            var ctx = screen.getScreen().getContext();
            var state = ui.session.state();
            IWidget focus = ctx.getFocusedWidget().getElement();
            s.put("sameActorWorldConnectionAndSlot", fixtureOwner.getAsBoolean());
            s.put("uiGraphIsOriginal", ui == graph);
            s.put("stateIsBaseline", state == baseline);
            s.put("sceneIsOriginal", ui.shell.scene() == scene);
            s.put("hostIsScenePanel", scene.isValid() && ui.shell.host() == scene.getPanel());
            s.put("screenIsCurrent", screen.minecraft().currentScreen == screen);
            s.put("sameSession", baseline.session.equals(state.session));
            s.put("revision", state.data.getLong("planRevision"));
            s.put("pointer", new int[] { ctx.getAbsMouseX(), ctx.getAbsMouseY() });
            s.put("focusedClass", type(focus));
            s.put("focusedHoverId", hoverIds.get(focus));
            s.put("focusInScenePanel", focus != null && focus.isValid() && scene.isValid()
                && focus.getPanel() == scene.getPanel());
            s.put("muiHasDraggable", ctx.hasDraggable());
            s.put("toolsPointerCaptured", ui.tools.pointerCaptured());
            s.put("controllerOpeningPressGate", screen.controllerRequest() > 0);
            s.put("tutorialInputGate", ui.tutorial.blockInput());
            s.put("editable", ui.session.editable());
            s.put("localDocumentBusy", ui.session.localDocument() && ui.library.localBusy());
            s.put("backgroundBusy", HologramBackgroundTasks.busy());
            s.put("ticketPending", ui.session.pendingSequence() >= 0);
            s.put("job", state.data.getInteger("job"));
            for (String key : EDIT_GATES) s.put(key, state.data.getBoolean(key));
            s.put("physicalCameraGesture", !ui.preview.blueprintCameraControls() && ui.preview.cameraGestureActive());
            s.put("inventoryDragging", ui.preview.inventoryDragging());
            s.put("boxSelecting", ui.preview.boxSelecting());
            s.put("gizmoAxis", ui.preview.gizmo().axis());
            s.put("textBoxActive", ui.textBoxes.active());
            // Only report the comparison, never the identifier, annotation text or player data.
            s.put("annotationPinChanged", !oldPin.equals(ui.annotations.pinnedAnnotation()));
            s.put("selectionVersion", ui.preview.selection().version());
            s.put("selected", ui.preview.selected());
            s.put("selectionIndices", ui.preview.selection().indices());
        } catch (RuntimeException e) {
            s.put("observationErrorClass", e.getClass().getName());
        }
    }

    private static final String[] EDIT_GATES =
        { "areaImporting", "targetClosed", "areaSelectionShell", "attachmentPending", "currentPlacementPending", "capturePending" };

    // A skipped dispatch leaves no handled value, rather than publishing a fake native false.
    void result(String edge, boolean pre, Boolean handled) {
        recordEdge(row, edge, pre, handled);
    }

    static void recordEdge(Map<String, Object> row, String edge, boolean pre, Boolean handled) {
        row.put(edge + "PreConsumed", pre);
        row.put(edge + "Dispatched", !pre);
        if (!pre) row.put(edge + "Handled", handled);
    }
}
