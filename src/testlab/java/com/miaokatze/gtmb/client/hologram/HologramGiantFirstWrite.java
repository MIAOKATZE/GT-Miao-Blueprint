// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;

/** A single native edit plus bounded history and Save Current on the same leased document. */
final class HologramGiantFirstWrite {

    static final String FLAG = "gtmb.testlab.giantNativeFirstWrite";
    private static final String PROMPT = "gtmb first write";
    private static final long SECOND = 1_000_000_000L;

    private enum Phase {
        DOCK, PROMPT, FOCUS, PROMPT_FRAME, APPLY, ACK, UNDO, UNDO_ACK, REDO, REDO_ACK, SAVE_DOCK, SAVE, SAVE_ACK,
        VERIFY, SAVE_GUIDANCE, CELL_HISTORY, DONE
    }

    private final HologramScreen screen;
    private final HologramGiantWriteArtifact artifact;
    private final Path output;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final HologramGiantWriteFrames timing = new HologramGiantWriteFrames();
    private final String fileId, session;
    private final int expectedCells;
    private final boolean packedArm, crossHistory;
    private final HologramGiantHistoryTiming historyTiming;
    private final long revision0, sequence0;
    private HologramLocalBlueprintDocument document;
    private Phase phase = Phase.DOCK;
    private long since = System.nanoTime(), naturalFrames, clickedAtFrame, initialSerial, priorCapture;
    private long press, ack, complete;
    private boolean busySeen, admissionSeen, saveGuidanceDismissSent;
    private Object workspace, deferredQueue;
    private HologramGiantApplyWitness applyWitness;
    private HologramGiantCellHistory cellHistory;
    private HologramGiantCellDelta cellDelta;
    private HologramGiantSaveLedger saveLedger;
    private ModularPanel readyPromptPanel;
    private IWidget readyPromptWidget;
    private long readyPromptFrame;

    void recordSaveFailure() {
        if (saveLedger != null) saveLedger.finish(false);
    }

    HologramGiantFirstWrite(HologramScreen screen, HologramGiantWriteArtifact artifact, Path output,
        String fileId, int expectedCells, boolean packedArm, boolean crossHistory, HologramGiantHistoryTiming historyTiming) {
        this.screen = screen;
        this.artifact = artifact;
        this.output = output;
        this.fileId = fileId;
        this.expectedCells = expectedCells;
        this.packedArm = packedArm;
        this.crossHistory = crossHistory;
        this.historyTiming = historyTiming;
        document = screen.ui.session.document();
        HologramState initial = state();
        session = initial.session;
        revision0 = initial.data.getLong("planRevision");
        sequence0 = screen.ui.session.sequence();
        require(document != null && fileId.equals(document.fileId()) && !document.dirty()
            && document.packedAuthoring() == packedArm && initial.cells.size() == expectedCells
            && initial.data.getString("blueprintPrompt").isEmpty()
            && !initial.data.getBoolean("editCanUndo") && !initial.data.getBoolean("editCanRedo"),
            "PRECONDITION: initial exact clean document/empty global prompt/stored arm");
        evidence.put("schema", "gtmb.nativeFirstWrite.v2");
        evidence.put("operation", "editBlueprintPrompt");
        evidence.put("expectedPackedArm", packedArm);
        evidence.put("sourceSession", session);
        evidence.put("initialPlanRevision", revision0);
        evidence.put("initialUiSequence", sequence0);
    }

    Map<String, Object> evidence() {
        return evidence;
    }

    boolean saved() {
        return phase == Phase.DONE;
    }

    boolean isReopen(HologramState next) {
        return saved() && !session.equals(next.session) && PROMPT.equals(next.data.getString("blueprintPrompt"))
            && (!crossHistory || HologramGiantCellHistory.matchesCells(next, cellDelta, true));
    }

    void tick() throws Exception {
        if (saved()) return;
        require(document != null && screen.ui.session.document() == document && fileId.equals(document.fileId())
            && session.equals(state().session), "OWNER_CHANGED: first-write document or session replaced");
        if (phase == Phase.CELL_HISTORY) {
            cellHistory.tick();
            return;
        }
        long limit = phase == Phase.SAVE_ACK ? 150 : 30;
        require(System.nanoTime() - since < limit * SECOND,
            (phase == Phase.ACK ? admissionSeen ? "ACK_OR_FULL_FRAME" : "ADMISSION_UNWITNESSED"
                : phase == Phase.SAVE_ACK ? "SAVE_PENDING"
                    : phase == Phase.FOCUS ? "NATIVE_PROMPT_MOUNT_TIMEOUT" : "LIFECYCLE_TIMEOUT") + ": " + phase);
        // A MUI tick can run between GUI draws. Observe the exact admitted task before waiting for another frame.
        if (phase == Phase.ACK) observeAdmission(System.nanoTime(), "owned-client-tick-end");
        if (phase == Phase.SAVE_ACK) saveLedger.tick();
        if (naturalFrames <= clickedAtFrame) return;
        switch (phase) {
            case DOCK -> {
                if (nativeClick("launcher.6", "promptDockHit")) step(Phase.PROMPT);
            }
            case PROMPT -> {
                if (nativeClick("control.227", "globalPromptHit")) step(Phase.FOCUS);
            }
            case FOCUS -> {
                if (readyPromptFrame <= clickedAtFrame) return;
                enterPrompt();
                step(Phase.PROMPT_FRAME);
            }
            case APPLY -> {
                if (!timing.hasBaseline()) return;
                require(screen.ui.annotations.promptDrawer() && screen.ui.annotations.promptField().isFocused()
                    && PROMPT.equals(screen.ui.annotations.promptField().getText()),
                    "NATIVE_HIT_OR_TEXT: actual focused field lost its typed prompt");
                apply();
                step(Phase.ACK);
            }
            case UNDO -> {
                if (screen.ui.shell.hasModalPanel()) return;
                initialSerial = materialSerial();
                if (nativeClick(tool("editUndo"), "undoHit")) step(Phase.UNDO_ACK);
            }
            case REDO -> {
                if (screen.ui.shell.hasModalPanel()) return;
                initialSerial = materialSerial();
                if (nativeClick(tool("editRedo"), "redoHit")) step(Phase.REDO_ACK);
            }
            case SAVE_DOCK -> {
                if (screen.ui.shell.hasModalPanel()) return;
                if (nativeClick("header.save", "saveDockHit")) step(Phase.SAVE);
            }
            case SAVE -> {
                if (screen.ui.shell.hasModalPanel()) return;
                require(!screen.ui.library.overwriteMode(), "SAVE_PENDING: switched to arbitrary overwrite mode");
                if (screen.ui.shell.targetBounds("control.140") == null) return;
                require(document.packedAuthoring() == (packedArm && !crossHistory),
                    "PACKED_HEADER: unexpected storage before Save");
                evidence.put("packedAtSavePress", document.packedAuthoring());
                saveLedger = new HologramGiantSaveLedger(screen, artifact, evidence);
                priorCapture = saveLedger.captureBefore();
                var hit = new LinkedHashMap<String, Object>();
                evidence.put("saveCurrentHit", hit);
                HologramMuiProbeInput.nativeTarget(screen, "control.140", hit, saveLedger::beforePress);
                saveLedger.afterClick();
                step(Phase.SAVE_ACK);
            }
            case VERIFY -> {
                var pair = new LinkedHashMap<String, Object>();
                evidence.put("managedPair", pair);
                artifact.verifyAfterSave(fileId, PROMPT, priorCapture, HologramBlueprintPreview.lastCaptureWitness(), pair, cellDelta);
                step(Phase.SAVE_GUIDANCE);
            }
            case SAVE_GUIDANCE -> {
                if (screen.ui.shell.hasModalPanel()) {
                    if (screen.ui.library.saveGuidanceOpen() && !saveGuidanceDismissSent) {
                        // Dismiss the known advisory only via its normal Escape action. Never suppress it or select a link.
                        require("gtmb_save_structure_guidance".equals(topModalName()),
                            "SAVE_GUIDANCE: unrelated modal owns input after verified disk commit");
                        HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                        saveGuidanceDismissSent = true;
                        evidence.put("nativeSaveGuidanceEscape", true);
                    }
                    return;
                }
                document = null; // do not retain the old mutable DTO/undo list across the real reopen
                step(Phase.DONE);
            }
            default -> {}
        }
    }

    void beforeHistoryDraw() {
        if (cellHistory != null) cellHistory.beforeDraw();
    }

    void afterHistoryDraw() {
        if (cellHistory != null) cellHistory.afterDraw();
    }

    void renderEnd(long end) throws Exception {
        if (saved()) return;
        naturalFrames++;
        if (phase == Phase.CELL_HISTORY) {
            cellHistory.renderEnd(end);
            if (cellHistory.done()) {
                cellDelta = cellHistory.delta();
                cellHistory = null;
                step(Phase.SAVE_DOCK);
            }
            return;
        }
        HologramState state = state();
        if (phase == Phase.FOCUS) observePromptMount();
        if (phase == Phase.PROMPT_FRAME) {
            captureTypedPromptFrame();
            return;
        }
        if (press == 0 || phase == Phase.ACK) timing.frameEnd(end);
        if (phase == Phase.ACK) {
            observeAdmission(end, "owned-render-tick-end");
            require(document.packedAuthoring() == packedArm, "PACKED_HEADER: Apply changed the storage arm");
            if (screen.ui.library.localBusy() && !busySeen) {
                busySeen = true;
                evidence.put("firstBusyObservedAtEndNanos", end - press);
            }
            if (isReceipt(revision0 + 1, sequence0 + 1)) {
                require(PROMPT.equals(state.data.getString("blueprintPrompt")) && document.dirty()
                    && state.data.getBoolean("editCanUndo") && !state.data.getBoolean("editCanRedo"),
                    "ACK_OR_REVISION: edited prompt/history state");
                if (ack == 0) {
                    ack = end;
                    evidence.put("editAckEndNanos", ack - press);
                    evidence.put("editAckSequence", state.data.getLong("uiSequence"));
                }
                long serial = freshMaterial(state);
                if (serial < 0) return;
                require(admissionSeen, "ADMISSION_UNWITNESSED: no matching native Apply task");
                evidence.put("packedAfterPrompt", document.packedAuthoring());
                complete = end;
                evidence.put("newEditBodySerial", serial);
                evidence.put("editFullMaterialEndNanos", complete - press);
                evidence.put("heapGcAfterFullMaterial", HologramGiantNativeEditProbe.heapGc());
                evidence.put("endToEndFrameGaps", timing.receipt());
                if (crossHistory) {
                    var details = new LinkedHashMap<String, Object>();
                    evidence.put("crossMaterializationHistory", details);
                    cellHistory = new HologramGiantCellHistory(screen, artifact, PROMPT, revision0, sequence0,
                        expectedCells, details, historyTiming);
                    step(Phase.CELL_HISTORY);
                } else step(Phase.UNDO);
            }
        } else if (phase == Phase.UNDO_ACK && isReceipt(revision0 + 2, sequence0 + 2)) {
            require(state.data.getString("blueprintPrompt").isEmpty() && !document.dirty()
                && !state.data.getBoolean("editCanUndo") && state.data.getBoolean("editCanRedo"),
                "HISTORY_STATE: Undo did not restore exact clean prompt");
            if (freshMaterial(state) >= 0) {
                require(document.packedAuthoring() == packedArm, "PACKED_HEADER: Undo changed the storage arm");
                evidence.put("packedAfterUndo", document.packedAuthoring());
                evidence.put("undoRevision", revision0 + 2);
                evidence.put("undoUiSequence", state.data.getLong("uiSequence"));
                step(Phase.REDO);
            }
        } else if (phase == Phase.REDO_ACK && isReceipt(revision0 + 3, sequence0 + 3)) {
            require(PROMPT.equals(state.data.getString("blueprintPrompt")) && document.dirty()
                && state.data.getBoolean("editCanUndo") && !state.data.getBoolean("editCanRedo"),
                "HISTORY_STATE: Redo did not restore exact edited prompt");
            if (freshMaterial(state) >= 0) {
                require(document.packedAuthoring() == packedArm, "PACKED_HEADER: Redo changed the storage arm");
                evidence.put("packedAfterRedo", document.packedAuthoring());
                evidence.put("redoRevision", revision0 + 3);
                evidence.put("redoUiSequence", state.data.getLong("uiSequence"));
                step(Phase.SAVE_DOCK);
            }
        } else if (phase == Phase.SAVE_ACK && !screen.ui.library.localBusy()
            && state.data.getLong("planRevision") == revision0 + (crossHistory ? 7 : 4)) {
            long revision = revision0 + (crossHistory ? 7 : 4), sequence = sequence0 + (crossHistory ? 6 : 3);
            require(screen.ui.session.document() == document && session.equals(state.session) && fileId.equals(document.fileId())
                && screen.ui.session.sequence() == sequence && state.data.getLong("uiSequence") == sequence
                && !document.packedAuthoring() && !document.dirty() && document.revision() == revision
                && PROMPT.equals(state.data.getString("blueprintPrompt")) && state.cells.size() == expectedCells
                && (!crossHistory || HologramGiantCellHistory.matchesCells(state, cellDelta, true)),
                "SAVE_PENDING: missing current-document clean commit acknowledgment");
            saveLedger.finish(true);
            saveLedger = null; // do not hold the old document or its state across the real reopen
            evidence.put("saveRevision", revision);
            step(Phase.VERIFY);
        }
    }

    /** The domain flag/open panel can precede LegacyPaint, which actually mounts the field and action. */
    private void observePromptMount() throws ReflectiveOperationException {
        if (readyPromptPanel != null || naturalFrames <= clickedAtFrame) return;
        var facts = new LinkedHashMap<String, Object>();
        evidence.putIfAbsent("firstPromptMountEnd", facts);
        evidence.put("lastPromptMountEnd", facts);
        facts.put("ownedRenderEndFrame", naturalFrames);
        facts.put("nativeOpenPressFrame", clickedAtFrame);
        var state = state();
        require(screen == Minecraft.getMinecraft().currentScreen && screen.ui.session.document() == document
            && session.equals(state.session) && state.data.getLong("planRevision") == revision0
            && screen.ui.session.sequence() == sequence0, "OWNER_CHANGED: natural prompt mount");
        boolean drawer = screen.ui.annotations.promptDrawer();
        boolean global = Integer.valueOf(0).equals(observe(screen.ui.annotations, "promptScope"))
            && screen.ui.preview.selection().size() == 0;
        facts.put("drawerRequested", drawer);
        facts.put("unselectedGlobal", global);
        var modal = topModal();
        boolean ownPanel = modal != null && screen.ui.panelName("gtmb_editor_advanced_prompt").equals(modal.getName());
        facts.put("topPanel", modal == null ? "" : modal.getName());
        facts.put("ownedPromptPanel", ownPanel);
        var field = screen.ui.annotations.promptField();
        var widget = field == null ? null : (IWidget) observe(field, "field"); // never materialize a pending adapter
        boolean valid = widget != null && widget.isValid();
        boolean enabled = widget != null && widget.isEnabled();
        boolean ancestorsEnabled = valid && widget.areAncestorsEnabled();
        boolean fieldOwned = valid && ownPanel && widget.getPanel() == modal;
        int[] fieldBounds = fieldOwned ? screen.ui.shell.nativeWidgetBounds(widget) : null;
        boolean stateEmpty = state.data.getString("blueprintPrompt").isEmpty();
        boolean fieldEmpty = field != null && field.getText().isEmpty();
        facts.put("fieldAdapter", field != null);
        facts.put("fieldWidget", widget != null);
        facts.put("fieldValid", valid);
        facts.put("fieldEnabled", enabled);
        facts.put("fieldAncestorsEnabled", ancestorsEnabled);
        facts.put("fieldInOwnedTopPanel", fieldOwned);
        facts.put("fieldClippedBounds", fieldBounds);
        facts.put("snapshotPromptEmpty", stateEmpty);
        facts.put("fieldTextEmpty", fieldEmpty);
        int[] actionBounds = ownPanel ? screen.ui.shell.targetBounds("control.158") : null;
        var registered = (Map<?, ?>) observe(screen.ui.shell, "targets");
        var values = registered.get("control.158");
        boolean actionOwned = false;
        if (actionBounds != null && values instanceof List<?> widgets) for (Object value : widgets) {
            IWidget action = (IWidget) value;
            if (action.isValid() && action.getPanel() == modal
                && Arrays.equals(actionBounds, screen.ui.shell.nativeWidgetBounds(action))) actionOwned = true;
        }
        facts.put("applyClippedBounds", actionBounds);
        facts.put("applyInOwnedTopPanel", actionOwned);
        require(stateEmpty && (!drawer || global && (field == null || fieldEmpty)),
            "NATIVE_HIT_OR_TEXT: global prompt was not empty before any typing");
        if (!drawer || !global || !ownPanel || !fieldOwned || !enabled || !ancestorsEnabled || !fieldEmpty
            || fieldBounds == null || fieldBounds[2] <= 0 || fieldBounds[3] <= 0 || !actionOwned) return;
        readyPromptPanel = modal;
        readyPromptWidget = widget;
        readyPromptFrame = naturalFrames;
        evidence.put("promptMountReadyEnd", facts);
    }

    private void enterPrompt() throws ReflectiveOperationException {
        var field = screen.ui.annotations.promptField();
        var widget = field == null ? null : (IWidget) observe(field, "field");
        var facts = new LinkedHashMap<String, Object>();
        evidence.put("promptBeforeFocusPress", facts);
        facts.put("sameField", widget != null && widget == readyPromptWidget);
        facts.put("sameTopPanel", readyPromptPanel == topModal());
        facts.put("fieldValid", widget != null && widget.isValid());
        facts.put("fieldEnabled", widget != null && widget.isEnabled());
        facts.put("fieldAncestorsEnabled", widget != null && widget.isValid() && widget.areAncestorsEnabled());
        facts.put("fieldTextEmpty", field != null && field.getText().isEmpty());
        require(!facts.containsValue(Boolean.FALSE) && screen.ui.annotations.promptDrawer()
            && screen.ui.preview.selection().size() == 0
            && Integer.valueOf(0).equals(observe(screen.ui.annotations, "promptScope")),
            "NATIVE_PROMPT_MOUNT_LOST: owned empty global prompt changed before native focus");
        int[] bounds = screen.ui.shell.nativeWidgetBounds(widget);
        require(bounds != null && bounds[2] > 0 && bounds[3] > 0, "NATIVE_HIT_OR_TEXT: clipped prompt field");
        int x = bounds[0] + bounds[2] / 2, y = bounds[1] + bounds[3] / 2;
        var nativeScreen = screen.getScreen();
        nativeScreen.getContext().updateState(x, y, 0);
        nativeScreen.onFrameUpdate();
        boolean hit = false;
        for (var located : widget.getPanel().getHovering()) {
            IWidget target = located.getElement();
            if (target == widget) hit = true;
        }
        require(hit, "NATIVE_HIT_OR_TEXT: pointer did not hit actual prompt field");
        HologramMuiProbeInput.click(screen, x, y, 0);
        require(field.isFocused(), "NATIVE_HIT_OR_TEXT: native prompt click did not acquire focus");
        for (char ch : PROMPT.toCharArray()) {
            int key = ch == ' ' ? Keyboard.KEY_SPACE
                : Keyboard.getKeyIndex(Character.toString(ch).toUpperCase(java.util.Locale.ROOT));
            require(key != Keyboard.KEY_NONE, "NATIVE_HIT_OR_TEXT: missing printable key");
            HologramMuiProbeInput.key(screen, ch, key);
        }
        require(PROMPT.equals(field.getText()), "NATIVE_HIT_OR_TEXT: real key/text dispatch did not type the probe value");
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("bounds", bounds);
        prompt.put("hit", true);
        prompt.put("focus", true);
        prompt.put("typed", PROMPT);
        evidence.put("promptField", prompt);
        readyPromptPanel = null;
        readyPromptWidget = null;
    }

    private void captureTypedPromptFrame() throws Exception {
        var field = screen.ui.annotations.promptField();
        require(screen.ui.annotations.promptDrawer() && field.isFocused() && PROMPT.equals(field.getText()),
            "NATIVE_HIT_OR_TEXT: actual focused field lost before natural capture");
        if (screen.ui.shell.targetBounds("control.158") == null) return;
        require(field.widget().getPanel().getName().equals(topModalName()),
            "NATIVE_HIT_OR_TEXT: typed prompt not in actual top modal");
        String name = "giant-edit-typed-prompt.png";
        Files.createDirectories(output.resolve("screenshots"));
        Path png = output.resolve("screenshots").resolve(name);
        require(!Files.exists(png), "NATIVE_HIT_OR_TEXT: refusing to replace modal evidence");
        Minecraft mc = Minecraft.getMinecraft();
        ScreenShotHelper.saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        require(Files.isRegularFile(png) && Files.size(png) > 1024, "NATIVE_HIT_OR_TEXT: actual modal capture missing");
        evidence.put("typedPromptFrame", "screenshots/" + name);
        evidence.put("typedPromptFrameSha256", HologramBlueprintArtifact.hash(Files.readAllBytes(png)));
        // End-to-end input excludes readback, hashing, and the next 30 natural baseline frames.
        timing.resetBaseline();
        step(Phase.APPLY);
    }

    private ModularPanel topModal() throws ReflectiveOperationException {
        var method = HologramEditorShell.class.getDeclaredMethod("topModal");
        method.setAccessible(true);
        return (ModularPanel) method.invoke(screen.ui.shell);
    }

    private String topModalName() throws ReflectiveOperationException {
        var modal = topModal();
        return modal == null ? "" : modal.getName();
    }

    private void apply() throws Exception {
        require(screen.ui.annotations.promptDrawer() && screen.ui.session.editable()
            && !screen.ui.library.localBusy() && !HologramBackgroundTasks.busy()
            && state().data.getLong("planRevision") == revision0 && screen.ui.session.sequence() == sequence0
            && document.packedAuthoring() == packedArm && !document.dirty(),
            "PRECONDITION: native edit no longer idle/initial");
        initialSerial = materialSerial();
        workspace = observe(screen.ui.library, "localWorkspace");
        var queue = HologramMuiScreen.class.getDeclaredField("deferredAction");
        queue.setAccessible(true); // read the declaring class; the usual diagnostic helper does not traverse inheritance
        deferredQueue = queue.get(screen);
        require(observe(workspace, "task") == null && observe(deferredQueue, "pending") == null,
            "PRECONDITION: a different task already owns the screen's native input");
        String promptPanel = screen.ui.annotations.promptField().widget().getPanel().getName();
        Map<String, Object> hit = new LinkedHashMap<>();
        evidence.put("applyNativeHit", hit);
        evidence.put("heapGcBeforeApply", HologramGiantNativeEditProbe.heapGc());
        HologramMuiProbeInput.nativeTarget(screen, "control.158", hit, () -> {
            require(Boolean.TRUE.equals(hit.get("modalPresent")) && Boolean.FALSE.equals(hit.get("modalBlocked"))
                && Boolean.TRUE.equals(hit.get("widgetEnabled")) && Boolean.TRUE.equals(hit.get("targetInActualHovering"))
                && promptPanel.equals(hit.get("panelName")),
                "NATIVE_HIT_OR_TEXT: Apply not enabled in the actual prompt field's top panel");
            applyWitness = new HologramGiantApplyWitness(state(), document, revision0, sequence0 + 1, packedArm);
            timing.begin();
            press = System.nanoTime();
        });
        evidence.put("nativeApplyDispatchNanos", System.nanoTime() - press);
        require(observe(deferredQueue, "pending") != null && observe(workspace, "task") == null
            && screen.ui.annotations.promptDrawer() && screen.ui.session.sequence() == sequence0
            && state().data.getLong("planRevision") == revision0 && document.packedAuthoring() == packedArm
            && !document.dirty(), "ADMISSION_UNWITNESSED: native Apply did not queue exactly one deferred intent");
        evidence.put("deferredIntentAtApplyReturn", true);
        evidence.put("packedAtApplyReturn", document.packedAuthoring());
    }

    private void observeAdmission(long now, String observer) throws ReflectiveOperationException {
        if (admissionSeen) return;
        var task = observe(workspace, "task");
        // No current exact task means no admission evidence, even if a later revision were to look right.
        if (task == null) return;
        var mc = Minecraft.getMinecraft();
        var owner = (HologramLocalBlueprintWorkspace.PresentationOwner) observe(task, "owner");
        boolean owned = owner != null && mc.thePlayer != null
            && owner.sameSource(mc.theWorld, mc.getNetHandler(), mc.thePlayer.getUniqueID(), session, revision0)
            && observe(deferredQueue, "pending") == null && screen.ui.library.localBusy()
            && !screen.ui.annotations.promptDrawer();
        // Only the direct header route retains the empty admission message, not conversion or structure work.
        boolean directRoute = "".equals(observe(task, "message"));
        boolean observedPacked = document.packedAuthoring();
        long taskRevision = (Long) observe(task, "revision");
        long draftRevision = document.revision();
        var progress = applyWitness.observe(
            observe(task, "source"), observe(task, "draft"), (String) observe(task, "operation"),
            (Long) observe(task, "sequence"), owned, directRoute, observedPacked, taskRevision, draftRevision);
        var observation = new LinkedHashMap<String, Object>();
        observation.put("ownedDeferredDispatch", owned);
        observation.put("directRoute", directRoute);
        observation.put("packed", observedPacked);
        observation.put("taskRevision", taskRevision);
        observation.put("draftRevision", draftRevision);
        observation.put("progress", progress == null ? "REJECTED" : progress.name());
        evidence.put("applyAdmissionObservation", observation);
        require(progress != null, "ADMISSION_UNWITNESSED: no exact owned header task on the expected revision");
        admissionSeen = true;
        evidence.put("workspaceAdmissionObservedNanos", now - press);
        evidence.put("workspaceAdmissionObserver", observer);
        evidence.put("packedAtWorkspaceAdmission", observedPacked);
        // Retain neither the task nor the deferred intent. Continue with native ACK and newer frame proof.
        applyWitness = null;
        workspace = null;
        deferredQueue = null;
    }

    private boolean isReceipt(long revision, long seq) {
        HologramState current = state();
        long actual = current.data.getLong("planRevision"), actualSequence = current.data.getLong("uiSequence");
        require(actual <= revision && actualSequence <= seq, "ACK_OR_REVISION: unexpected/duplicate sequence");
        return !screen.ui.library.localBusy() && !HologramBackgroundTasks.busy() && screen.ui.session.editable()
            && actual == revision && actualSequence == seq && document.revision() == revision;
    }

    private long freshMaterial(HologramState state) {
        var material = HologramRenderer.editorMaterialPresentation();
        return material != null && material.state() == state && material.serial() > initialSerial
            && material.bodyMaterial() && material.complete() && material.submittedCells() == expectedCells
            && !state.data.getBoolean("displayPartial") && state.data.getBoolean("target")
            && state.cells.size() == expectedCells && HologramRenderer.previewMeshReady(state)
            && screen.ui.shell.scene() instanceof HologramEditorSceneWidget scene && scene.editFrameReady()
                ? material.serial() : -1;
    }

    private long materialSerial() {
        var material = HologramRenderer.editorMaterialPresentation();
        return material == null ? 0 : material.serial();
    }

    private HologramState state() {
        return screen.ui.session.state();
    }

    private String tool(String command) {
        int index = new HologramEditorCommands(screen.ui).index(command);
        require(index >= 0, "NATIVE_HIT_OR_TEXT: missing native history action " + command);
        return "control." + (HologramEditorControlIds.TOOLS + index);
    }

    private boolean nativeClick(String key, String record) throws Exception {
        if (screen.ui.shell.targetBounds(key) == null) return false;
        var hit = new LinkedHashMap<String, Object>();
        evidence.put(record, hit);
        HologramMuiProbeInput.nativeTarget(screen, key, hit);
        return true;
    }

    private void step(Phase next) {
        phase = next;
        since = System.nanoTime();
        clickedAtFrame = naturalFrames;
        evidence.put("phase", phase.name());
    }

    private static Object observe(Object owner, String field) throws ReflectiveOperationException {
        return HologramTestLabUiDiagnostics.readDiagnosticField(owner, field);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
