// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;

/** Read-only, single-click ledger. Never runs/replaces a queued intent or polls from another thread. */
final class HologramGiantSaveLedger {

    private final HologramScreen screen;
    private final HologramLocalBlueprintDocument document;
    private final HologramState source;
    private final HologramGiantWriteArtifact artifact;
    private final HologramLocalBlueprintWorkspace workspace;
    private final Object queue;
    private final Field pendingField, taskField;
    private final ThreadPoolExecutor io;
    private final AtomicLong capture;
    private final long started = System.nanoTime(), revision, sequence, ioBefore, captureBefore;
    private final Map<String, Object> receipt = new LinkedHashMap<>();
    private final List<Map<String, Object>> edges = new ArrayList<>();
    private Object queued, admitted;
    private HologramLocalSaveTrace trace;
    private boolean sawQueue, sawTask, terminal, observed, planGuardNotice;
    private int idleWithoutAck;
    private Object lastPending, lastTask;
    private String lastNotice;
    private boolean lastPlan, lastPacked;
    private long lastCapture, lastRevision, lastIo;

    HologramGiantSaveLedger(HologramScreen screen, HologramGiantWriteArtifact artifact,
        Map<String, Object> parent) throws Exception {
        this.screen = screen;
        this.artifact = artifact;
        document = screen.ui.session.document();
        source = screen.ui.session.state();
        revision = document.revision();
        sequence = screen.ui.session.sequence();
        workspace = (HologramLocalBlueprintWorkspace) field(HologramUiLibrary.class, "localWorkspace").get(screen.ui.library);
        queue = field(HologramMuiScreen.class, "deferredAction").get(screen);
        pendingField = field(HologramUiDeferredAction.class, "pending");
        taskField = field(HologramLocalBlueprintWorkspace.class, "task");
        io = (ThreadPoolExecutor) field(HologramLocalBlueprintWorkspace.class, "IO").get(null);
        capture = (AtomicLong) field(HologramBlueprintPreview.class, "CAPTURE_GENERATION").get(null);
        ioBefore = io.getTaskCount();
        captureBefore = capture.get();
        parent.put("saveTransitions", receipt);
        receipt.put("schema", "gtmb.nativeSaveEdges.v1");
        receipt.put("sourceSession", source.session);
        receipt.put("sourceFile", document.fileId());
        receipt.put("sourceRevision", revision);
        receipt.put("sourceSequence", sequence);
        receipt.put("sourceCaptureGeneration", captureBefore);
        receipt.put("edges", edges);
        receipt.put("diskBefore", artifact.diskWitness(false));
    }

    long captureBefore() { return captureBefore; }

    void beforePress() {
        sample("before-native-press", true);
        require(!observedBusy() && document.dirty() && screen.ui.library.nativeCanSave()
            && source == screen.ui.session.state() && document == screen.ui.session.document()
            && screen == Minecraft.getMinecraft().currentScreen, "Native Save baseline lost ownership/idle gate");
    }

    private boolean observedBusy() { return lastPending != null || lastTask != null; }

    void afterClick() throws IllegalAccessException {
        queued = pendingField.get(queue);
        sawQueue = queued != null;
        sample("native-click-return", true);
        require(sawQueue && lastTask == null, "Native Save did not queue a new intent on the idle screen");
        // This non-tutorial nativeAction lambda captures its owning shell and primitive control id.
        // Read the values, not compiler-dependent capture-field names; never invoke or replace the closure.
        boolean ownShell = false, control = false;
        for (Field capture : queued.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(capture.getModifiers())) continue;
            capture.setAccessible(true);
            if (capture.getType() == int.class) control |= capture.getInt(queued) == 140;
            else if (capture.getType() == HologramEditorShell.class) ownShell |= capture.get(queued) == screen.ui.shell;
        }
        receipt.put("deferredCapturedOwningShell", ownShell);
        receipt.put("deferredCapturedControl140", control);
        require(ownShell && control, "Cannot attribute queued native intent to this shell/control.140");
    }

    void tick() {
        sample("owned-client-tick-end", false);
        // Dispatch admission is synchronous inside the native queue drain; an accepted Save holds the same task
        // until the client-thread terminal callback. Give publication two further natural ticks and never retry it.
        boolean idle = sawQueue && lastPending == null && lastTask == null && document.dirty()
            && document.revision() == revision && screen.ui.session.state().data.getLong("planRevision") == revision;
        idleWithoutAck = idle ? idleWithoutAck + 1 : 0;
        require(idleWithoutAck < 3, "SAVE_NO_ACK: native intent left queue and workspace retired/idle with unchanged dirty revision");
    }

    void sample(String at, boolean force) {
        if (terminal) return;
        try {
            Object pending = pendingField.get(queue), task = taskField.get(workspace);
            var state = screen.ui.session.state();
            String notice = screen.ui.session.notice();
            boolean plan = state.data.getBoolean("localPlanPending");
            boolean packed = document.packedAuthoring();
            long generation = capture.get(), rev = document.revision(), jobs = io.getTaskCount();
            if (!force && observed && pending == lastPending && task == lastTask && generation == lastCapture
                && rev == lastRevision && jobs == lastIo && plan == lastPlan && packed == lastPacked
                && Objects.equals(notice, lastNotice)) return;
            var row = new LinkedHashMap<String, Object>();
            row.put("at", at);
            row.put("elapsedNanos", System.nanoTime() - started);
            row.put("deferredIntent", pending == null ? "empty" : pending == queued ? "same-click" : "other");
            row.put("workspaceTask", task == null ? "empty" : token(task));
            row.put("workspaceIoSubmittedDeltaApprox", jobs - ioBefore); // diagnostic only, shared by all workspaces
            row.put("captureGeneration", generation);
            row.put("localPlanPending", plan);
            row.put("localPlanError", state.data.getString("localPlanError"));
            boolean blocked = screen.ui.canvas.localPlanBlockedControl(140);
            row.put("control140PlanBlocked", blocked);
            if (queued != null && lastPending == queued && pending == null && task == null && blocked
                && !Objects.equals(notice, lastNotice) && Objects.equals(notice, HologramUiCanvas.localPlanReason(state.data))
                && jobs == ioBefore && generation == captureBefore) planGuardNotice = true;
            row.put("newPlanGuardNoticeAtDrain", planGuardNotice);
            row.put("notice", notice);
            row.put("editable", screen.ui.session.editable());
            row.put("nativeCanSave", screen.ui.library.nativeCanSave());
            row.put("sourceOwner", screen == Minecraft.getMinecraft().currentScreen
                && screen.ui.session.document() == document && state.session.equals(source.session));
            row.put("uiRevision", state.data.getLong("planRevision"));
            row.put("documentRevision", rev);
            row.put("uiSequence", state.data.getLong("uiSequence"));
            row.put("sessionSequence", screen.ui.session.sequence());
            row.put("dirty", document.dirty());
            row.put("packedAuthoring", packed);
            if (task != null && task != lastTask) {
                Class<?> type = task.getClass();
                var owner = (HologramLocalBlueprintWorkspace.PresentationOwner) field(type, "owner").get(task);
                Minecraft mc = Minecraft.getMinecraft();
                Object message = field(type, "message").get(task);
                boolean matches = mc.thePlayer != null && owner.sameSource(mc.theWorld, mc.getNetHandler(),
                    mc.thePlayer.getUniqueID(), source.session, revision)
                    && field(type, "source").get(task) == source && field(type, "draft").get(task) == document
                    && field(type, "revision").getLong(task) == revision
                    && "".equals(field(type, "operation").get(task))
                    && ("正在准备本地蓝图预览".equals(message)
                        || packed && "正在准备蓝图编辑数据".equals(message));
                row.put("taskMessage", message);
                row.put("matchesExactSaveTask", matches);
                if (matches && sawQueue && pending != queued) {
                    admitted = task;
                    sawTask = true;
                    trace = (HologramLocalSaveTrace) field(type, "saveTrace").get(task);
                }
            }
            if (lastTask != null && lastTask == admitted && task != admitted) row.put("ownedSaveTaskRetired", true);
            if (edges.size() < 48) edges.add(row);
            else receipt.put("edgesTruncated", true);
            receipt.put("last", row);
            observed = true;
            lastPending = pending;
            lastTask = task;
            lastCapture = generation;
            lastRevision = rev;
            lastIo = jobs;
            lastPlan = plan;
            lastPacked = packed;
            lastNotice = notice;
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("Cannot read native Save ledger", failed);
        }
    }

    void finish(boolean ack) {
        if (terminal) return;
        try {
            sample(ack ? "exact-native-ack" : "failure-before-cleanup", true);
            receipt.put("outcome", ack ? "EXACT_ACK" : !sawQueue ? "NO_CLICK_INTENT_OBSERVED"
                : lastPending == queued ? "CLICK_INTENT_STILL_QUEUED" : sawTask
                    ? lastTask == admitted ? "ADMITTED_STILL_ACTIVE" : "ADMITTED_RETIRED_NO_EXACT_ACK"
                    : planGuardNotice ? "PLAN_GATE_NOTICE_AT_DRAIN_WITHOUT_WORKSPACE_IO"
                    : "QUEUE_LEFT_NO_EXACT_TASK_OBSERVED");
            receipt.put("exactSaveTaskObserved", sawTask);
            receipt.put("exactAck", ack);
            if (trace != null) receipt.put("ownedSaveTrace", trace.snapshot());
            else if (Boolean.getBoolean(HologramLocalSaveTrace.FLAG)) receipt.put("ownedSaveTraceObserved", false);
            receipt.put("diskAfter", artifact.diskWitness(ack));
        } finally {
            terminal = true;
            admitted = queued = lastTask = lastPending = null;
            trace = null;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static String token(Object value) {
        return value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
