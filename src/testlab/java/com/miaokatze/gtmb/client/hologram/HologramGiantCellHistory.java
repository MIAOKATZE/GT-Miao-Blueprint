// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;

/** Alternate native annotation/history lane; no commands are submitted outside production MUI input. */
final class HologramGiantCellHistory {

    static final String FLAG = "gtmb.testlab.giantNativeCrossHistory";
    static final String OP = "editCellFlags";
    private static final String CONVERTING = "正在准备蓝图编辑数据";
    private static final long LIMIT = 45_000_000_000L;
    private static final int[][] ADJACENT = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 },
        { 0, 1, 0 }, { 0, -1, 0 } };

    private enum Phase { PICK, OPEN, MOUNT, TYPED_DRAW, APPLY, CELL_ACK, HISTORY_PRESS, HISTORY_ACK, DONE }

    private final HologramScreen screen;
    private final HologramGiantWriteArtifact artifact;
    private final HologramLocalBlueprintDocument draft;
    private final String header, session;
    private final long baseRevision, baseSequence;
    private final int rows;
    private final Map<String, Object> report;
    private final HologramGiantHistoryTiming timing;
    private static final String[] ACTIONS = { "undoCell", "undoHeader", "redoHeader", "redoCell" };
    private final List<Map<String, Object>> history = new ArrayList<>();
    private Phase phase;
    private long since, press, beforeSerial;
    private HologramGiantCellDelta delta;
    private HologramState applySource;
    private Object workspace, queue;
    private ModularPanel mountedPanel;
    private IWidget mountedField;
    private boolean typedDrawn, conversionAdmitted, observedConversion;
    private int historyStep;

    HologramGiantCellHistory(HologramScreen screen, HologramGiantWriteArtifact artifact, String header,
        long baseRevision, long baseSequence, int rows, Map<String, Object> report, HologramGiantHistoryTiming timing) {
        this.screen = screen;
        this.artifact = artifact;
        this.header = header;
        this.baseRevision = baseRevision;
        this.baseSequence = baseSequence;
        this.rows = rows;
        this.report = report;
        this.timing = timing;
        draft = screen.ui.session.document();
        session = screen.ui.session.state().session;
        report.put("operation", OP);
        report.put("phaseTimeoutNanos", LIMIT);
        report.put("history", history);
        step(Phase.PICK);
    }

    HologramGiantCellDelta delta() { return delta; }

    boolean done() { return phase == Phase.DONE; }

    void tick() throws Exception {
        require(System.nanoTime() - since < LIMIT, "CELL_HISTORY_TIMEOUT: " + phase);
        if (phase == Phase.CELL_ACK) observeConversion();
        if (phase == Phase.PICK) {
            if (!idle() || screen.ui.shell.hasModalPanel()) return;
            pick();
            step(Phase.OPEN);
        } else if (phase == Phase.OPEN) {
            require(selection(), "Selected cell lost before opening its native prompt");
            if (screen.ui.shell.targetBounds("control.146") == null) {
                if ((!screen.ui.shell.dockExpanded() || screen.ui.shell.dockPage() != 6) && !report.containsKey("annotationDockReopened"))
                    click("launcher.6", "annotationDockReopened");
                return;
            }
            click("control.146", "selectedPromptHit");
            step(Phase.MOUNT);
        } else if (phase == Phase.MOUNT && mountedField != null) {
            enterPrompt();
            step(Phase.TYPED_DRAW);
        } else if (phase == Phase.APPLY) {
            require(typedDrawn && mounted()
                && HologramGiantCellDelta.PROMPT.equals(screen.ui.annotations.promptField().getText()),
                "Selected prompt lost its typed natural modal");
            apply();
            step(Phase.CELL_ACK);
        } else if (phase == Phase.HISTORY_PRESS) {
            if (!idle() || screen.ui.shell.hasModalPanel()) return;
            String command = historyStep < 2 ? "editUndo" : "editRedo";
            int index = new HologramEditorCommands(screen.ui).index(command);
            require(index >= 0, "Native history command missing");
            beforeSerial = materialSerial();
            String key = "control." + (HologramEditorControlIds.TOOLS + index);
            String label = "historyHit" + historyStep;
            if (timing == null) {
                press = System.nanoTime();
                click(key, label);
            } else {
                timing.prime(ACTIONS[historyStep]);
                var hit = new LinkedHashMap<String, Object>();
                report.put(label, hit);
                HologramMuiProbeInput.nativeTarget(screen, key, hit, () -> {
                    press = System.nanoTime();
                    timing.press(screen, draft, command, rows, baseSequence + 3 + historyStep, press);
                });
            }
            step(Phase.HISTORY_ACK);
        }
    }

    void beforeDraw() {
        if (phase == Phase.HISTORY_ACK && timing != null) timing.beforeDraw(screen);
    }

    void afterDraw() {
        if (phase == Phase.HISTORY_ACK && timing != null) timing.afterDraw(screen);
    }

    void renderEnd(long end) throws Exception {
        if (timing != null) timing.frame(end);
        if (phase == Phase.MOUNT) {
            if (!screen.ui.annotations.promptDrawer()) return;
            require(selection() && scope(), "Native modal does not own cell scope and singleton selection");
            ModularPanel panel = top();
            var adapter = screen.ui.annotations.promptField();
            IWidget field = (IWidget) observe(adapter, "field");
            boolean ready = panel != null && screen.ui.panelName("gtmb_editor_advanced_prompt").equals(panel.getName())
                && field != null && field.isValid() && field.isEnabled() && field.areAncestorsEnabled()
                && field.getPanel() == panel && screen.ui.shell.nativeWidgetBounds(field) != null
                && screen.ui.shell.targetBounds("control.158") != null;
            if (!ready) return;
            require(adapter.getText().isEmpty() && displayed(delta.target).prompt.isEmpty(), "Target was not initially blank");
            mountedPanel = panel;
            mountedField = field;
            report.put("ownedSelectedPromptMounted", true);
        } else if (phase == Phase.TYPED_DRAW) {
            require(mounted() && screen.ui.annotations.promptField().isFocused()
                && HologramGiantCellDelta.PROMPT.equals(screen.ui.annotations.promptField().getText()),
                "Typed cell prompt was not present in the same natural modal");
            typedDrawn = true;
            step(Phase.APPLY);
        } else if (phase == Phase.CELL_ACK) {
            observeConversion();
            long serial = receipt(2, true, true, true, false);
            if (serial < 0) return;
            require(conversionAdmitted && !draft.packedAuthoring(),
                "No exact admitted fallback edge before the cell ACK");
            report.put("packedAfterCell", draft.packedAuthoring());
            report.put("packedFlipObservedWhileTaskActive", observedConversion);
            report.put("cellAckFullNanos", end - press);
            report.put("cellRevision", baseRevision + 2);
            report.put("cellUiSequence", baseSequence + 2);
            report.put("cellFullBodySerial", serial);
            applySource = null;
            workspace = null;
            queue = null;
            step(Phase.HISTORY_PRESS);
        } else if (phase == Phase.HISTORY_ACK) {
            int i = historyStep;
            // Undo cell, Undo header, Redo header, Redo cell. Only the second state is clean.
            long serial = receipt(3 + i, i == 3, i != 1, i != 1, i != 3);
            if (serial < 0) return;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("action", ACTIONS[i]);
            entry.put("revision", baseRevision + 3 + i);
            entry.put("uiSequence", baseSequence + 3 + i);
            entry.put("bodySerial", serial);
            entry.put("ackFullNanos", end - press);
            entry.put("dirty", draft.dirty());
            entry.put("packed", draft.packedAuthoring());
            history.add(entry);
            if (timing != null) timing.record(screen.ui.session.state(), serial, end);
            step(++historyStep == 4 ? Phase.DONE : Phase.HISTORY_PRESS);
        }
    }

    private void pick() throws Exception {
        var state = screen.ui.session.state();
        require(draft.packedAuthoring() && draft.dirty() && state.data.getLong("planRevision") == baseRevision + 1
            && screen.ui.session.sequence() == baseSequence + 1 && screen.ui.preview.selection().size() == 0,
            "Cell selection must start on the exact packed edited header");
        require(screen.ui.shell.scene() instanceof HologramEditorSceneWidget, "Missing real editor scene");
        HologramEditorSceneWidget scene = (HologramEditorSceneWidget) screen.ui.shell.scene();
        require(scene.editFrameReady() && screen.ui.preview.previewInteractive(), "No current pickable body");
        int[] bounds = screen.ui.shell.nativeWidgetBounds(scene);
        require(bounds != null && bounds[2] >= 44 && bounds[3] >= 44, "Scene clipped or too small");
        require(!screen.ui.preview.sameTypeSelectionHeld() && !screen.ui.preview.airSelectionHeld()
            && !screen.ui.preview.controlSelectionHeld() && !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
            && !Keyboard.isKeyDown(Keyboard.KEY_RSHIFT), "Selection modifier unexpectedly held");
        var original = artifact.pristineRows();
        // Center-out fixed grid, up to 121 queries of the actual presented picker. Do not click rejected samples.
        int sx = bounds[2] / 12, sy = bounds[3] / 12;
        for (int ring = 0; ring <= 5; ring++) for (int iy = -ring; iy <= ring; iy++)
            for (int ix = -ring; ix <= ring; ix++) {
                if (Math.max(Math.abs(ix), Math.abs(iy)) != ring) continue;
                int x = bounds[0] + bounds[2] / 2 + ix * sx, y = bounds[1] + bounds[3] / 2 + iy * sy;
                int[] local = screen.ui.shell.sceneLocalPoint(x, y);
                if (local == null || !HologramRenderer.pickStructure(local[0], local[1]).isEmpty()) continue;
                int ordinal = HologramRenderer.pick(local[0], local[1]);
                if (ordinal < 0 || ordinal >= rows) continue;
                var candidate = displayed(ordinal);
                if (candidate.anchor || candidate.iconOnly || candidate.missingBlock || candidate.sourceMissing
                    || !candidate.prompt.isEmpty() || "minecraft:air".equals(candidate.wantId)) continue;
                for (int[] offset : ADJACENT) {
                    var adjacent = state.regions.at(candidate.x + offset[0], candidate.y + offset[1], candidate.z + offset[2]);
                    if (adjacent == null || !adjacent.prompt.isEmpty()) continue;
                    var chosen = HologramGiantCellDelta.select(original, ordinal, adjacent.index);
                    if (chosen == null || !coords(candidate, chosen.targetXyz)
                        || !coords(adjacent, chosen.neighborXyz) || !hovering(scene, x, y)) continue;
                    // The production hit and pre-click state choose the row, never the synthetic selection API.
                    require(scene.editFrameReady() && HologramRenderer.pick(local[0], local[1]) == ordinal,
                        "Natural picker changed before dispatch");
                    var ms = screen.getScreen();
                    boolean pre = ms.onMouseInputPre(0, true), handled = false;
                    try {
                        if (!pre) handled = ms.onMousePressed(0);
                    } finally {
                        if (!ms.onMouseInputPre(0, false)) ms.onMouseRelease(0);
                    }
                    delta = chosen;
                    report.put("nativeCellPick", new int[] { x, y });
                    report.put("selectedOrdinal", delta.target);
                    report.put("selectedCoordinate", delta.targetXyz);
                    report.put("unchangedNeighborOrdinal", delta.neighbor);
                    report.put("unchangedNeighborCoordinate", delta.neighborXyz);
                    require(!pre && handled && selection() && draft.packedAuthoring()
                        && screen.ui.session.state() == state && !screen.ui.library.localBusy()
                        && matchesCells(state, delta, false),
                        "Real scene dispatch did not make one exact local cell selection");
                    return;
                }
            }
        throw new IllegalStateException("Native picker found no eligible same-descriptor adjacent control");
    }

    private void enterPrompt() throws Exception {
        require(mounted() && screen.ui.annotations.promptField().getText().isEmpty(),
            "Cell modal lost scope, owner, or empty field before input");
        int[] bounds = screen.ui.shell.nativeWidgetBounds(mountedField);
        require(bounds != null && bounds[2] > 0 && bounds[3] > 0, "Cell field is clipped");
        int x = bounds[0] + bounds[2] / 2, y = bounds[1] + bounds[3] / 2;
        require(hovering(mountedField, x, y), "Actual cell text field did not pass native hit test");
        HologramMuiProbeInput.click(screen, x, y, 0);
        var field = screen.ui.annotations.promptField();
        require(field.isFocused(), "No native cell prompt focus");
        for (char ch : HologramGiantCellDelta.PROMPT.toCharArray()) {
            int key = ch == ' ' ? Keyboard.KEY_SPACE : Keyboard.getKeyIndex(Character.toString(ch).toUpperCase(java.util.Locale.ROOT));
            require(key != Keyboard.KEY_NONE, "No cell prompt keybinding");
            HologramMuiProbeInput.key(screen, ch, key);
        }
        require(HologramGiantCellDelta.PROMPT.equals(field.getText()), "Actual cell field did not retain typed prompt");
    }

    private void apply() throws Exception {
        applySource = screen.ui.session.state();
        beforeSerial = materialSerial();
        workspace = observe(screen.ui.library, "localWorkspace");
        Field deferred = HologramMuiScreen.class.getDeclaredField("deferredAction");
        deferred.setAccessible(true);
        queue = deferred.get(screen);
        require(idle() && draft.packedAuthoring() && draft.revision() == baseRevision + 1
            && screen.ui.session.sequence() == baseSequence + 1 && observe(queue, "pending") == null,
            "Cell Apply baseline was not exact packed header");
        var hit = new LinkedHashMap<String, Object>();
        report.put("cellApplyHit", hit);
        HologramMuiProbeInput.nativeTarget(screen, "control.158", hit, () -> {
            require(Boolean.TRUE.equals(hit.get("modalPresent")) && Boolean.FALSE.equals(hit.get("modalBlocked"))
                && mountedPanel.getName().equals(hit.get("panelName")), "Native cell Apply has a different panel");
            press = System.nanoTime();
        });
        require(observe(queue, "pending") != null && observe(workspace, "task") == null && draft.packedAuthoring()
            && draft.revision() == baseRevision + 1 && screen.ui.session.state() == applySource,
            "Cell Apply did not enqueue exactly one deferred packed input");
        report.put("packedAtCellApplyReturn", true);
        mountedPanel = null;
        mountedField = null;
    }

    private void observeConversion() throws Exception {
        Object task = observe(workspace, "task");
        if (task == null) return;
        var mc = Minecraft.getMinecraft();
        var owner = (HologramLocalBlueprintWorkspace.PresentationOwner) observe(task, "owner");
        require(owner != null && mc.thePlayer != null
            && owner.sameSource(mc.theWorld, mc.getNetHandler(), mc.thePlayer.getUniqueID(), session, baseRevision + 1)
            && observe(task, "source") == applySource && observe(task, "draft") == draft
            && OP.equals(observe(task, "operation")) && Long.valueOf(baseSequence + 2).equals(observe(task, "sequence"))
            && observe(queue, "pending") == null && !screen.ui.annotations.promptDrawer() && screen.ui.library.localBusy()
            && CONVERTING.equals(observe(task, "message")), "Unexpected exact-task owner, scope or conversion route");
        if (!conversionAdmitted) {
            conversionAdmitted = true;
            report.put("cellConversionAdmittedNanos", System.nanoTime() - press);
        }
        if (!draft.packedAuthoring()) observedConversion = true;
    }

    private long receipt(long step, boolean cellEdited, boolean headerEdited, boolean undo, boolean redo) {
        var state = screen.ui.session.state();
        long revision = baseRevision + step, sequence = baseSequence + step;
        require(draft == screen.ui.session.document() && session.equals(state.session),
            "Cell-history action lost its exact authoring source");
        require(state.data.getLong("planRevision") <= revision && screen.ui.session.sequence() <= sequence
            && state.data.getLong("uiSequence") <= sequence,
            "Cell-history action skipped or duplicated a revision/sequence");
        if (!idle() || state.data.getLong("planRevision") != revision || state.data.getLong("uiSequence") != sequence
            || screen.ui.session.sequence() != sequence || draft.revision() != revision) return -1;
        require(!draft.packedAuthoring() && draft.dirty() == headerEdited
            && state.data.getBoolean("editCanUndo") == undo && state.data.getBoolean("editCanRedo") == redo
            && (headerEdited ? header : "").equals(state.data.getString("blueprintPrompt"))
            && matchesCells(state, delta, cellEdited), "Cell-history content/dirty/storage proof");
        var frame = HologramRenderer.editorMaterialPresentation();
        return frame != null && frame.state() == state && frame.serial() > beforeSerial && frame.bodyMaterial()
            && frame.complete() && frame.submittedCells() == rows && state.cells.size() == rows
            && state.data.getBoolean("target") && !state.data.getBoolean("displayPartial")
            && HologramRenderer.previewMeshReady(state) && screen.ui.shell.scene() instanceof HologramEditorSceneWidget
            && ((HologramEditorSceneWidget) screen.ui.shell.scene()).editFrameReady() ? frame.serial() : -1;
    }

    static boolean matchesCells(HologramState state, HologramGiantCellDelta delta, boolean edited) {
        if (delta == null || state.cells.size() <= Math.max(delta.target, delta.neighbor)) return false;
        var target = state.cells.get(delta.target);
        var neighbor = state.cells.get(delta.neighbor);
        return coords(target, delta.targetXyz) && coords(neighbor, delta.neighborXyz)
            && (edited ? HologramGiantCellDelta.PROMPT : "").equals(target.prompt) && neighbor.prompt.isEmpty()
            && target.excluded == delta.excluded && neighbor.excluded == delta.excluded;
    }

    private static boolean coords(HologramState.Cell cell, int[] xyz) {
        return cell.x == xyz[0] && cell.y == xyz[1] && cell.z == xyz[2];
    }

    private HologramState.Cell displayed(int i) { return screen.ui.session.state().cells.get(i); }

    private boolean idle() {
        return !screen.ui.library.localBusy() && !HologramBackgroundTasks.busy() && screen.ui.session.editable();
    }

    private long materialSerial() {
        var frame = HologramRenderer.editorMaterialPresentation();
        return frame == null ? 0 : frame.serial();
    }

    private boolean selection() {
        return delta != null && screen.ui.preview.selected() == delta.target
            && Arrays.equals(screen.ui.preview.selection().indices(), new int[] { delta.target });
    }

    private boolean scope() throws ReflectiveOperationException {
        return Integer.valueOf(1).equals(observe(screen.ui.annotations, "promptScope"));
    }

    private boolean mounted() throws ReflectiveOperationException {
        return screen.ui.annotations.promptDrawer() && selection() && scope() && mountedPanel != null && mountedPanel == top()
            && mountedField != null && mountedField == observe(screen.ui.annotations.promptField(), "field")
            && mountedField.isValid() && mountedField.isEnabled() && mountedField.areAncestorsEnabled();
    }

    private ModularPanel top() throws ReflectiveOperationException {
        var method = HologramEditorShell.class.getDeclaredMethod("topModal");
        method.setAccessible(true);
        return (ModularPanel) method.invoke(screen.ui.shell);
    }

    private boolean hovering(IWidget widget, int x, int y) {
        var nativeScreen = screen.getScreen();
        nativeScreen.getContext().updateState(x, y, 0);
        nativeScreen.onFrameUpdate();
        for (var located : widget.getPanel().getHovering()) if (located.getElement() == widget) return true;
        return false;
    }

    private void click(String key, String label) throws ReflectiveOperationException {
        var evidence = new LinkedHashMap<String, Object>();
        report.put(label, evidence);
        HologramMuiProbeInput.nativeTarget(screen, key, evidence);
    }

    private void step(Phase next) {
        phase = next;
        since = System.nanoTime();
        report.put("phase", next.name());
    }

    private static Object observe(Object owner, String field) throws ReflectiveOperationException {
        return HologramTestLabUiDiagnostics.readDiagnosticField(owner, field);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
