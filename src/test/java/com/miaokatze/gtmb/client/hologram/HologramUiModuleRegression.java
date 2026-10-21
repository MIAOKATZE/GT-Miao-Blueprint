package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Boundary regressions for the actual composed workstation input and request lifecycle. */
public final class HologramUiModuleRegression {

    private HologramUiModuleRegression() {}

    public static void main(String[] args) throws Exception {
        if (!com.miaokatze.gtmb.testutil.MinecraftTestBootstrap.enter(HologramUiModuleRegression.class, args)) return;
        run();
        System.out.println("HologramUiModuleRegression PASS");
    }

    public static void run() {
        localPlanConsumersWaitWithoutLockingEditing();
        outgoingSelectionScope();
        switchReviewOwnsOverlappingLibraryRows();
        nativeBindingDrawerControls();
        selectedParsedNetworkGroup();
        movementHandleControl();
        idleHintTiming();
        confirmedLibraryTargets();
        controllerConstructionConfirmation();
        presentationSnapshotFailure();
        List<String> visited = new ArrayList<>();
        boolean handled = HologramUiInputChain.consume(() -> {
            visited.add("hidden overlay");
            return false;
        }, () -> {
            visited.add("visible modal: disabled control");
            return true;
        }, () -> {
            visited.add("underlying build action");
            return true;
        });
        SimpleAssert.that(handled, "a visible modal owns an inert click");
        SimpleAssert.eq(
            Arrays.asList("hidden overlay", "visible modal: disabled control"),
            visited,
            "modal consumption prevents the underlying world/editor action from running");
        visited.clear();
        SimpleAssert.that(!HologramUiInputChain.consume(() -> {
            visited.add("no modal");
            return false;
        }), "unclaimed input remains available to the viewport");

        HologramUiTicket ticket = new HologramUiTicket();
        ticket.begin("workspace-a", 12);
        SimpleAssert.that(!ticket.acknowledge("workspace-a", 11), "older ACK retains the pending command");
        SimpleAssert.eq(12L, ticket.sequence(), "pending sequence survives an older snapshot");
        SimpleAssert.that(
            !ticket.acknowledge("workspace-b", 99),
            "a different session cannot complete a command even with a higher sequence");
        SimpleAssert.that(ticket.acknowledge("workspace-a", 12), "the matching ACK clears its ticket");
        SimpleAssert.eq(-1L, ticket.sequence(), "completed command is no longer pending");
        ticket.begin("workspace-b", 2);
        SimpleAssert.that(
            !ticket.acknowledge("workspace-a", 1000),
            "a late response cannot complete the next workspace's reused sequence");
        ticket.clear();
        SimpleAssert.that(!ticket.acknowledge("workspace-b", 2), "explicit close/reset retires the ticket");
        ticket.begin("workspace-b", 3);
        SimpleAssert.that(!ticket.rejectExact("workspace-a", 3), "a busy result from another session is unrelated");
        SimpleAssert.that(!ticket.rejectExact("workspace-b", 2), "an old denial cannot cancel a newer command");
        SimpleAssert.that(!ticket.rejectExact("workspace-b", 4), "a higher denial sequence is not an ACK");
        SimpleAssert.that(ticket.rejectExact("workspace-b", 3), "only the exact denied command releases its ticket");

        NBTTagCompound opening = new NBTTagCompound();
        opening.setString("workspaceTab", "structure");
        SimpleAssert.eq(
            Boolean.FALSE,
            HologramUiTabMemory.openingTab(opening, false),
            "a new workspace uses its server default");
        SimpleAssert.eq(
            null,
            HologramUiTabMemory.openingTab(opening, true),
            "air reopen retains the user's blueprint tab over a stale structure default");
        opening.setString("workspaceTab", "blueprint");
        SimpleAssert.eq(
            Boolean.TRUE,
            HologramUiTabMemory.openingTab(opening, false),
            "a fresh blueprint workspace opens its default tab");
        SimpleAssert.eq(
            null,
            HologramUiTabMemory.openingTab(opening, true),
            "a heartbeat or reopen also retains the user's structure tab over a stale blueprint default");
        opening.setString("openPurpose", "controllerStructure");
        SimpleAssert.eq(
            Boolean.FALSE,
            HologramUiTabMemory.openingTab(opening, true),
            "an actual controller click overrides remembered blueprint navigation");
        opening.setString("workspaceTab", "structure");
        for (String purpose : new String[] { "blueprintBrowse", "blueprintEdit", "blueprintPlacement" }) {
            opening.setString("openPurpose", purpose);
            SimpleAssert.eq(
                Boolean.TRUE,
                HologramUiTabMemory.openingTab(opening, true),
                "the explicit " + purpose + " opening overrides remembered structure navigation");
        }
    }

    private static void presentationSnapshotFailure() {
        try {
            HologramUiModules processing = presentationUi("attachment-processing", 0);
            processing.session.state().data.setBoolean("attachmentPending", true);
            SimpleAssert
                .that(!processing.session.editable(), "an attachment progress receipt cannot reopen competing edits");
            processing.session.state().data.setBoolean("attachmentPending", false);
            SimpleAssert.that(processing.session.editable(), "terminal attachment state releases editing");
            for (String operation : new String[] { "previewPreset", "choosePlacementPreset", "lockVirtual",
                "placeTextBox" }) {
                HologramUiModules ui = presentationUi("presentation-" + operation, 0);
                ui.session.send(operation, null, true);
                long failedSequence = ui.session.pendingSequence();
                NBTTagCompound progress = new NBTTagCompound();
                progress.setString("scope", "server");
                progress.setString("session", ui.session.state().session);
                progress.setLong("uiSequence", failedSequence);
                progress.setLong("planRevision", 7);
                progress.setString("phase", "snapshot.prepare");
                progress.setLong("sequence", 1);
                progress.setLong("done", 99);
                progress.setLong("total", 100);
                HologramClientProgress.receive(progress);
                NBTTagCompound failure = presentationFailure(ui, failedSequence);
                for (String stale : new String[] { "session", "sequence", "token", "revision" }) {
                    NBTTagCompound wrong = (NBTTagCompound) failure.copy();
                    if ("session".equals(stale)) wrong.setString("session", "older-presentation");
                    if ("sequence".equals(stale)) wrong.setLong("uiSequence", failedSequence - 1);
                    if ("token".equals(stale)) wrong.setLong("clientTaskToken", 77);
                    if ("revision".equals(stale)) wrong.setLong("planRevision", 6);
                    SimpleAssert.that(
                        !HologramClient.presentationTransportFailed(ui.session, wrong)
                            && ui.session.pendingSequence() == failedSequence
                            && HologramClientProgress.view("server", ui.session.state().session) != null,
                        "stale " + stale + " failure preserves the current presentation ticket and progress");
                }
                HologramState previous = ui.session.state();
                SimpleAssert.that(
                    HologramClient.presentationTransportFailed(ui.session, failure) && ui.session.pendingSequence() < 0
                        && ui.session.editable()
                        && HologramClientProgress.view("server", previous.session) == null
                        && ui.session.progress() == null
                        && ui.session.notice()
                            .contains("准备失败")
                        && ui.session.state() == previous,
                    "an exact ownerless " + operation
                        + " snapshot failure releases UI/progress and retains the preview");
                ui.session.send("choosePlacementPreset", null, true);
                NBTTagCompound retry = (NBTTagCompound) uiField(ui.diagnostics, "capturedRenderSmokeAction");
                SimpleAssert.that(
                    retry.getLong("uiSequence") > failedSequence
                        && "choosePlacementPreset".equals(retry.getString("op"))
                        && !HologramClient.presentationTransportFailed(ui.session, failure)
                        && ui.session.pendingSequence() == retry.getLong("uiSequence"),
                    "the next blueprint request is emitted immediately and survives a late prior failure");
                HologramClientProgress.clear();
            }
            for (int oldJob : new int[] { 1, 2, 3 }) {
                HologramUiModules ui = presentationUi("old-completed-job-" + oldJob, oldJob);
                ui.session.send("choosePlacementPreset", null, true);
                SimpleAssert.that(
                    HologramClient
                        .presentationTransportFailed(ui.session, presentationFailure(ui, ui.session.pendingSequence()))
                        && ui.session.pendingSequence() < 0,
                    "stale job metadata cannot retain an exact new presentation failure after its owner ended");
                ui.session.send("lockVirtual", null, true);
                SimpleAssert.that(
                    ui.session.pendingSequence() > 1,
                    "after the terminal presentation receipt a new lock request can acquire a distinct ticket");
            }
            activeOwnerRejectsPresentationFailure();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("actual UI presentation failure lifecycle", failure);
        } finally {
            HologramClientProgress.clear();
        }
    }

    private static HologramUiModules presentationUi(String session, int job) throws ReflectiveOperationException {
        HologramUiModules ui = headlessUi();
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", session);
        data.setLong("planRevision", 7);
        data.setInteger("job", job);
        setUiField(ui.session, "state", new HologramState(data));
        setUiField(ui.diagnostics, "captureRenderSmokeAction", true);
        return ui;
    }

    private static NBTTagCompound presentationFailure(HologramUiModules ui, long sequence) {
        NBTTagCompound failure = new NBTTagCompound();
        failure.setBoolean("transportError", true);
        failure.setString("errorCode", "snapshot.prepare");
        failure.setString("session", ui.session.state().session);
        failure.setLong("uiSequence", sequence);
        failure.setLong("planRevision", 7);
        return failure;
    }

    private static void activeOwnerRejectsPresentationFailure() throws ReflectiveOperationException {
        java.lang.reflect.Field ownerField = HologramBackgroundTasks.class.getDeclaredField("OWNER");
        ownerField.setAccessible(true);
        HologramTaskOwner owner = (HologramTaskOwner) ownerField.get(null);
        java.lang.reflect.Field callbacks = HologramTaskOwner.class.getDeclaredField("notifications");
        callbacks.setAccessible(true);
        Object original = callbacks.get(owner);
        callbacks.set(owner, new HologramTaskOwner.Notifications() {

            @Override
            public void background(String label) {}

            @Override
            public void finished(String label, HologramTaskOwner.Result result, String detail) {}
        });
        long token = 0;
        try {
            for (String kind : new String[] { "server", "material", "library" }) {
                HologramUiModules ui = presentationUi("active-owner-" + kind, 0);
                ui.session.send("previewPreset", null, true);
                long pending = ui.session.pendingSequence();
                token = owner.begin(kind, ui.session.state().session + ":" + pending, kind);
                SimpleAssert.that(
                    token > 0
                        && !HologramClient.presentationTransportFailed(ui.session, presentationFailure(ui, pending))
                        && owner.busy()
                        && owner.token() == token
                        && ui.session.pendingSequence() == pending,
                    "a presentation snapshot error cannot end an active " + kind + " owner or its UI ticket");
                owner.finish(token, HologramTaskOwner.Result.CANCELLED, "test cleanup");
                token = 0;
            }
            for (String operation : new String[] { "build", "orderMissingMaterials", "confirmAeSave",
                "saveBlueprint" }) {
                HologramUiModules ui = presentationUi("unrelated-operation-" + operation, 0);
                setUiField(ui.session, "pendingOperation", operation);
                ui.session.pendingSequence(8);
                SimpleAssert.that(
                    !HologramClient.presentationTransportFailed(ui.session, presentationFailure(ui, 8))
                        && ui.session.pendingSequence() == 8,
                    "the presentation fallback never consumes a " + operation + " ticket even without an owner");
            }
        } finally {
            if (token != 0) owner.finish(token, HologramTaskOwner.Result.CANCELLED, "test cleanup");
            callbacks.set(owner, original);
        }
    }

    private static void selectedParsedNetworkGroup() {
        HologramUiModules ui = headlessUi();
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "parsed-network-selection");
        NBTTagList cells = new NBTTagList();
        for (String group : new String[] { "q1", "q2", "" }) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setString("networkGroup", group);
            cells.appendTag(cell);
        }
        source.setTag("cells", cells);
        HologramState parsed = new HologramState(source);
        try {
            setUiField(ui.session, "state", parsed);
            SimpleAssert.that(!parsed.data.hasKey("cells"), "network selection uses the real metadata-only snapshot");
            ui.preview.selected(1);
            SimpleAssert.eq(
                "q2",
                ui.bindings.selectedNetworkGroup(),
                "the selected parsed cell resolves its actual network group without a raw cells tree");
            ui.preview.selected(0);
            SimpleAssert.eq("q1", ui.bindings.selectedNetworkGroup(), "changing selection follows the typed cell");
            for (int selected : new int[] { -1, 2, 3 }) {
                ui.preview.selected(selected);
                SimpleAssert.eq(
                    "",
                    ui.bindings.selectedNetworkGroup(),
                    "unknown selection and cells without a network do not fabricate a group");
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("parsed network group selection", failure);
        }
    }

    private static void controllerConstructionConfirmation() {
        try {
            HologramUiModules ui = headlessUi();
            NBTTagCompound data = new NBTTagCompound();
            data.setString("session", "controller-hatch-preset");
            data.setString("generation", "controller-hatch-preset");
            data.setLong("planRevision", 7);
            data.setBoolean("presetActive", true);
            NBTTagList cells = new NBTTagList();
            String[] operations = { "PLACE", "REPLACE", "REMOVE", "PLACE", "REPLACE", "KEEP" };
            for (int i = 0; i < operations.length; i++) {
                NBTTagCompound cell = new NBTTagCompound();
                cell.setInteger("x", i);
                cell.setString("operation", operations[i]);
                cell.setString("status", i == 4 ? "protected" : "pending");
                cell.setBoolean("inScope", i != 3);
                cells.appendTag(cell);
            }
            data.setTag("cells", cells);
            HologramState parsed = new HologramState(data);
            setUiField(ui.session, "state", parsed);
            ui.review.confirmedRevision(7);
            SimpleAssert.that(
                !parsed.data.hasKey("cells") && parsed.cells.size() == 6,
                "a real controller snapshot keeps geometry in parsed pages instead of metadata NBT");
            SimpleAssert
                .eq(3, ui.review.operationCount(), "review counts scoped PLACE/REPLACE/REMOVE work from parsed cells");
            SimpleAssert.that(
                ui.review.confirmationAllowed(ui.review.operationCount()),
                "the actual confirmation button condition accepts a reviewed controller with actionable work");
            parsed.data.setInteger("missing", 1);
            SimpleAssert.that(
                !ui.review.confirmationAllowed(ui.review.operationCount()),
                "controller material shortages still disable confirmation");
            parsed.data.setInteger("missing", 0);
            ui.review.confirmedRevision(6);
            SimpleAssert.that(
                !ui.review.confirmationAllowed(ui.review.operationCount()),
                "a stale review still cannot authorize construction");
            ui.review.confirmedRevision(7);

            NBTTagCompound patch = new NBTTagCompound();
            patch.setBoolean("partial", true);
            patch.setString("session", parsed.session);
            patch.setString("generation", parsed.session);
            patch.setLong("baseRevision", 7);
            patch.setLong("planRevision", 8);
            patch.setInteger("cellCount", parsed.cells.size());
            NBTTagList changed = new NBTTagList();
            for (int i = 0; i < 3; i++) {
                NBTTagCompound cell = parsed.cells.get(i)
                    .tag();
                cell.setInteger("index", i);
                if (i == 0) cell.setBoolean("inScope", false);
                else cell.setString("status", "satisfied");
                changed.appendTag(cell);
            }
            patch.setTag("cells", changed);
            HologramState finished = HologramState.apply(parsed, patch);
            setUiField(ui.session, "state", finished);
            ui.review.confirmedRevision(8);
            SimpleAssert.that(
                !finished.cells.get(0).inScope && parsed.cells.get(0).inScope && ui.review.operationCount() == 0,
                "incremental scope/status updates change the count without mutating the prior parsed snapshot");
            SimpleAssert.that(
                !ui.review.confirmationAllowed(ui.review.operationCount()),
                "a controller with no remaining actionable work still has a disabled confirm button");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("controller construction confirmation", failure);
        }
    }

    private static void confirmedLibraryTargets() {
        java.lang.reflect.Field singleton = null;
        Object previous = null;
        try {
            for (java.lang.reflect.Field field : net.minecraft.client.Minecraft.class.getDeclaredFields())
                if (field.getType() == net.minecraft.client.Minecraft.class
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers())) singleton = field;
            if (singleton == null) throw new NoSuchFieldException("Minecraft singleton");
            singleton.setAccessible(true);
            previous = singleton.get(null);
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            singleton.set(
                null,
                ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(net.minecraft.client.Minecraft.class));
            for (int action : new int[] { 139, 14001 }) {
                HologramUiModules ui = librarySwitchUi();
                HologramState state = ui.session.state();
                // The compatibility button may address a separately browsed item. Ordinary rows instead use
                // the immutable identity captured when their native control was drawn.
                if (action == 139) setUiField(ui.library, "browseServerPresetId", "chosen-target");
                NBTTagCompound originalView = ui.preview.backgroundUiState();
                ui.router.activate(action);
                SimpleAssert.that(
                    ui.library.discardReview() && uiField(ui.diagnostics, "capturedRenderSmokeAction") == null,
                    "a dirty source waits for explicit confirmation before switching");
                SimpleAssert.that(
                    ui.session.state() == state && ui.session.sequence() == 0
                        && "original".equals(state.data.getString("selectedPresetId"))
                        && originalView.equals(ui.preview.backgroundUiState()),
                    "the discard guard precedes any draft, row, selection or camera mutation");
                // Save ACKs and catalog refreshes can reorder rows or restore another selected id while the modal is
                // open.
                NBTTagList reordered = new NBTTagList();
                reordered.appendTag(
                    state.data.getTagList("presets", 10)
                        .getCompoundTagAt(1)
                        .copy());
                reordered.appendTag(
                    state.data.getTagList("presets", 10)
                        .getCompoundTagAt(0)
                        .copy());
                state.data.setTag("presets", reordered);
                state.data.setString("selectedPresetId", "original");
                visibleLibraryRows(ui).put(
                    14001,
                    (NBTTagCompound) reordered.getCompoundTagAt(1)
                        .copy());
                ui.router.activate(224);
                NBTTagCompound confirmed = (NBTTagCompound) uiField(ui.diagnostics, "capturedRenderSmokeAction");
                SimpleAssert
                    .eq("chosen-target", confirmed.getString("presetId"), "confirmation retains the clicked identity");
                SimpleAssert.eq("editPreset", confirmed.getString("op"), "confirmation retains the original operation");
                SimpleAssert.that(
                    confirmed.getBoolean("confirmDiscard") && ui.session.sequence() == 1 && !ui.library.discardReview(),
                    "one confirmation submits the switch through the existing discard gate without a separate discard command");
                SimpleAssert.that(
                    originalView.equals(ui.preview.backgroundUiState())
                        && "chosen-target".equals(ui.library.libraryNavigation().targetId),
                    "confirmed handoff keeps the camera and the originally captured navigation target");
            }

            HologramUiModules clean = librarySwitchUi();
            clean.session.state().data.setBoolean("blueprintDirty", false);
            NBTTagCompound cleanView = clean.preview.backgroundUiState();
            clean.router.activate(14001);
            NBTTagCompound cleanRequest = (NBTTagCompound) uiField(clean.diagnostics, "capturedRenderSmokeAction");
            SimpleAssert.that(
                !clean.library.discardReview() && clean.session.sequence() == 1
                    && "editPreset".equals(cleanRequest.getString("op"))
                    && "chosen-target".equals(cleanRequest.getString("presetId"))
                    && !cleanRequest.getBoolean("confirmDiscard"),
                "a clean row directly enters the real editPreset chain without an extra edit or discard action");
            SimpleAssert.that(
                cleanView.equals(clean.preview.backgroundUiState()) && !clean.library.browsePreviewActive()
                    && "blueprintEdit".equals(clean.library.libraryNavigation().purpose)
                    && !clean.library.libraryNavigation().local,
                "integrated-library row handoff is an editable server session and preserves the current camera");
            NBTTagCompound nextData = (NBTTagCompound) clean.session.state().data.copy();
            nextData.setString("session", "loaded-target");
            nextData.setString("generation", "loaded-target");
            nextData.setString("libraryLocalId", "");
            nextData.setString("selectedPresetId", "chosen-target");
            nextData.setBoolean("open", true);
            HologramState next = new HologramState(nextData);
            SimpleAssert.that(
                clean.library.acceptsLibraryNavigation(next),
                "the real integrated-library response is accepted in the existing screen instead of reopening it");
            clean.preview.resetSession(next);
            NBTTagCompound nextView = clean.preview.backgroundUiState();
            for (String field : new String[] { "yaw", "pitch", "zoom", "cameraX", "cameraZ" }) SimpleAssert.eq(
                cleanView.getDouble(field),
                nextView.getDouble(field),
                "same-screen row response preserves editor camera " + field);

            for (boolean localIdentity : new boolean[] { false, true }) {
                HologramUiModules same = librarySwitchUi();
                if (!localIdentity) same.session.state().data.setString("libraryLocalId", "");
                same.router.activate(14000);
                same.router.activate(139);
                SimpleAssert.that(
                    !same.library.discardReview() && same.session.sequence() == 0
                        && uiField(same.diagnostics, "capturedRenderSmokeAction") == null
                        && same.library.libraryNavigation() == null,
                    "the canonical current item neither reloads nor prompts, through a row or compatibility button");
            }
            HologramUiModules fresh = librarySwitchUi();
            fresh.session.state().data.setBoolean("blueprintDirty", false);
            fresh.session.state().data.setString("blueprintSource", "");
            fresh.router.activate(14001);
            SimpleAssert.that(
                fresh.library.discardReview() && fresh.session.sequence() == 0,
                "an unsaved populated workspace is guarded even without an explicit dirty flag");

            // Read-only display remains an explicit legacy path, independently of ordinary row loading.
            HologramUiModules browsing = librarySwitchUi();
            HologramState editing = browsing.session.state();
            SimpleAssert.that(
                !browsing.library.prepareLibraryNavigation(141) && !browsing.library.discardReview()
                    && browsing.session.state() == editing
                    && browsing.session.sequence() == 0
                    && browsing.preview.selection()
                        .contains(4),
                "preparing world positioning is separate from row editing and retains the unsaved draft");
            setUiField(browsing.library, "browsePreview", true);
            SimpleAssert.that(browsing.library.browsePreviewActive(), "library display has an explicit read-only mode");
            browsing.preview.activateControl(11);
            browsing.preview.activateControl(13);
            browsing.preview.editAction("editUndo");
            SimpleAssert.that(
                !browsing.preview.handleEditorShortcut(org.lwjgl.input.Keyboard.KEY_T, 0)
                    && !browsing.preview.handleEditorShortcut(org.lwjgl.input.Keyboard.KEY_X, 1)
                    && !browsing.preview.canMoveSelection(),
                "display cannot enable selection shortcuts or geometry edits");
            SimpleAssert.that(
                browsing.session.state() == editing && browsing.session.sequence() == 0
                    && uiField(browsing.diagnostics, "capturedRenderSmokeAction") == null
                    && browsing.preview.selection()
                        .contains(4),
                "display layer controls and denied edits preserve the authoritative state and original selection");
            for (int action : new int[] { 139, 14001 }) {
                HologramUiModules cancelled = librarySwitchUi();
                if (action == 139) setUiField(cancelled.library, "browseServerPresetId", "chosen-target");
                HologramState draft = cancelled.session.state();
                NBTTagCompound originalView = cancelled.preview.backgroundUiState();
                cancelled.router.activate(action);
                SimpleAssert.that(cancelled.library.discardReview(), "switching a dirty draft asks once before cancel");
                cancelled.router.activate(225);
                SimpleAssert.that(
                    !cancelled.library.discardReview() && cancelled.session.sequence() == 0
                        && uiField(cancelled.diagnostics, "capturedRenderSmokeAction") == null
                        && cancelled.session.state() == draft
                        && draft.data.getBoolean("blueprintDirty")
                        && originalView.equals(cancelled.preview.backgroundUiState())
                        && cancelled.library.libraryNavigation() == null,
                    "cancel keeps the original draft, selection and camera without starting a source handoff");
                SimpleAssert.eq(
                    "original",
                    draft.data.getString("selectedPresetId"),
                    "cancel does not change the existing library row");
                NBTTagCompound context = cancelled.library.backgroundUiState();
                SimpleAssert.that(
                    context.getInteger("deferredLibraryAction") == -1 && context.getString("deferredLibraryTargetId")
                        .isEmpty(),
                    "cancel clears the pending target so it cannot be submitted later");
            }

            HologramUiModules rejected = librarySwitchUi();
            rejected.session.state().data.setBoolean("discardConfirmationRequired", true);
            rejected.session.state().data.setString("discardTargetOp", "editPreset");
            rejected.session.state().data.setString("discardTargetId", "chosen-target");
            rejected.library.acceptSourceSwitchProposal();
            SimpleAssert
                .that(rejected.library.discardReview(), "the existing scalar server discard reply offers confirmation");
            rejected.session.state().data.setString("selectedPresetId", "original");
            rejected.router.activate(224);
            NBTTagCompound retry = (NBTTagCompound) uiField(rejected.diagnostics, "capturedRenderSmokeAction");
            SimpleAssert.that(
                "editPreset".equals(retry.getString("op")) && "chosen-target".equals(retry.getString("presetId"))
                    && retry.getBoolean("confirmDiscard")
                    && rejected.session.sequence() == 1,
                "a server-requested confirmation retries its exact target and operation once");
            libraryCatalogControlsPreservePreview();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot exercise library discard confirmation", failure);
        } finally {
            if (singleton != null) try {
                singleton.set(null, previous);
            } catch (IllegalAccessException failure) {
                throw new AssertionError("cannot restore detached Minecraft singleton", failure);
            }
        }
    }

    private static HologramUiModules librarySwitchUi() throws ReflectiveOperationException {
        HologramUiModules ui = headlessUi();
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "dirty-library-switch");
        data.setString("generation", "dirty-library-switch");
        data.setBoolean("blueprintDirty", true);
        data.setBoolean("virtual", true);
        data.setString("openPurpose", "blueprintEdit");
        data.setString("blueprintSource", "preset");
        data.setString("libraryLocalId", "original");
        data.setString("selectedPresetId", "original");
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 5; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("dx", i);
            cells.appendTag(cell);
        }
        data.setTag("cells", cells);
        NBTTagList rows = new NBTTagList();
        for (String id : new String[] { "original", "chosen-target" }) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", id);
            row.setString("name", id);
            rows.appendTag(row);
        }
        data.setTag("presets", rows);
        setUiField(ui.session, "state", new HologramState(data));
        setUiField(ui.diagnostics, "captureRenderSmokeAction", true);
        ui.library.libraryDrawer(true);
        for (int i = 0; i < rows.tagCount(); i++) visibleLibraryRows(ui).put(
            14000 + i,
            (NBTTagCompound) rows.getCompoundTagAt(i)
                .copy());
        ui.preview.selection()
            .click(4, false);
        setUiField(ui.preview, "yaw", .87d);
        setUiField(ui.preview, "pitch", .42d);
        setUiField(ui.preview, "zoom", 1.75d);
        HologramPreviewCamera camera = (HologramPreviewCamera) uiField(ui.preview, "camera");
        camera.x = 2.25;
        camera.z = -3.5;
        return ui;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Integer, NBTTagCompound> visibleLibraryRows(HologramUiModules ui)
        throws ReflectiveOperationException {
        return (java.util.Map<Integer, NBTTagCompound>) uiField(ui.library, "visibleLibraryRows");
    }

    /**
     * Catalog controls issue real dedicated-server RPCs. Inspect only their compiled branches here instead
     * of creating a fake connected world or touching a player's client library on disk.
     */
    private static void libraryCatalogControlsPreservePreview() {
        final java.util.Set<Integer> seen = new java.util.HashSet<>(), refreshed = new java.util.HashSet<>();
        try (java.io.InputStream input = HologramUiLibrary.class.getResourceAsStream("HologramUiLibrary.class")) {
            if (input == null) throw new AssertionError("missing compiled library owner");
            new org.objectweb.asm.ClassReader(input)
                .accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {

                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                        if (!"activateClientLibrary".equals(name)) return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {

                            private final java.util.Map<org.objectweb.asm.Label, Integer> cases = new java.util.HashMap<>();
                            private int current = -1;

                            @Override
                            public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label fallback,
                                org.objectweb.asm.Label... labels) {
                                for (int i = 0; i < labels.length; i++) cases.put(labels[i], min + i);
                            }

                            @Override
                            public void visitLookupSwitchInsn(org.objectweb.asm.Label fallback, int[] keys,
                                org.objectweb.asm.Label[] labels) {
                                for (int i = 0; i < labels.length; i++) cases.put(labels[i], keys[i]);
                            }

                            @Override
                            public void visitLabel(org.objectweb.asm.Label label) {
                                if (cases.containsKey(label)) current = cases.get(label);
                                if (catalog()) seen.add(current);
                            }

                            private boolean catalog() {
                                return current == 201 || current == 208 || current == 209;
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                if (catalog() && opcode == org.objectweb.asm.Opcodes.PUTFIELD) SimpleAssert.that(
                                    !"browsePreview".equals(name) && !"browseServerPresetId".equals(name),
                                    "catalog source/page controls cannot replace the displayed preview: " + current);
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
                                if (!catalog()) return;
                                SimpleAssert.that(
                                    !"clearBrowsePreview".equals(name) && !"resetCamera".equals(name)
                                        && !"resetSession".equals(name)
                                        && !"clearDraft".equals(name),
                                    "catalog source/page controls retain the draft and camera: " + current);
                                if (owner.endsWith("/HologramLibraryClient") && "refreshServer".equals(name))
                                    refreshed.add(current);
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == org.objectweb.asm.Opcodes.IRETURN) current = -1;
                            }
                        };
                    }
                }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            SimpleAssert.eq(
                new java.util.HashSet<>(Arrays.asList(201, 208, 209)),
                seen,
                "compiled dedicated source and both page controls remain reachable");
            SimpleAssert.eq(seen, refreshed, "catalog controls retain their real server refresh entry points");
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot inspect dedicated library catalog controls", failure);
        }
    }

    private static void movementHandleControl() {
        // The canvas only collects native controls here. A detached singleton supplies its library-mode query;
        // no Minecraft constructor, display, GL context, world, or network is used.
        java.lang.reflect.Field singleton = null;
        Object previous = null;
        try {
            for (java.lang.reflect.Field field : net.minecraft.client.Minecraft.class.getDeclaredFields())
                if (field.getType() == net.minecraft.client.Minecraft.class
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers())) singleton = field;
            if (singleton == null) throw new NoSuchFieldException("Minecraft singleton");
            singleton.setAccessible(true);
            previous = singleton.get(null);
            if (previous == null) {
                java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                singleton.set(
                    null,
                    ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(net.minecraft.client.Minecraft.class));
            }
            HologramUiModules ui = headlessUi();
            NBTTagCompound data = new NBTTagCompound();
            data.setString("session", "movement-control");
            data.setBoolean("virtual", true);
            data.setBoolean("supported", true);
            data.setBoolean("geometryEditable", true);
            NBTTagList cells = new NBTTagList(), structures = new NBTTagList();
            NBTTagCompound structure = new NBTTagCompound();
            structure.setString("id", "machine");
            structures.appendTag(structure);
            data.setTag("structures", structures);
            for (int i = 0; i < 2; i++) {
                NBTTagCompound cell = new NBTTagCompound();
                cell.setString("structureId", "machine");
                cell.setInteger("dx", i);
                cells.appendTag(cell);
            }
            data.setTag("cells", cells);
            HologramState state = new HologramState(data);
            setUiField(ui.session, "state", state);
            ui.preview.selection()
                .structure(state, "machine", false);
            ui.preview.drawMoveHandleControl(-1, -1);
            HologramUiCanvas.Control control = ui.canvas.controls()
                .get(0);
            SimpleAssert.that(
                control.id == 147 && control.enabled,
                "the actual preview exposes the existing axis action for a complete structure");
            ui.router.activate(control.id);
            SimpleAssert.that(!ui.preview.moveHandles(), "the visible native action hides the actual movement handles");
            SimpleAssert.that(
                ui.canvas.controlHelp(147)
                    .contains("已关闭"),
                "a hidden axis has an explicit local recovery instruction");
            SimpleAssert.that(
                ui.shortcuts.idleHintLines()
                    .get(1)
                    .contains("当前关"),
                "idle guidance exposes the actual hidden-axis state");
            SimpleAssert.that(
                ui.tools.commands()
                    .bind(
                        ui.tools.commands()
                            .index("editUndo"),
                        org.lwjgl.input.Keyboard.KEY_F6,
                        0),
                "the active editor command registry accepts the player's rebound undo shortcut");
            SimpleAssert.that(
                ui.shortcuts.idleHintLines()
                    .get(2)
                    .startsWith("F6 "),
                "idle guidance reads the player's actual rebound undo shortcut");
            HologramIdleHints idle = (HologramIdleHints) uiField(ui.shortcuts, "idleHints");
            idle.pointer(10, 10, 0);
            SimpleAssert.that(
                ui.shortcuts.idleHintsVisible(HologramIdleHints.DELAY_NANOS),
                "an idle editable preview presents its shortcut guidance");
            Object nativeTools = uiField(ui.tools, "nativeTools");
            Object previousShortcuts = uiField(nativeTools, "shortcuts");
            com.cleanroommc.modularui.api.IPanelHandler openShortcuts = (com.cleanroommc.modularui.api.IPanelHandler) java.lang.reflect.Proxy
                .newProxyInstance(
                    com.cleanroommc.modularui.api.IPanelHandler.class.getClassLoader(),
                    new Class<?>[] { com.cleanroommc.modularui.api.IPanelHandler.class },
                    (proxy, method, arguments) -> {
                        if ("isPanelOpen".equals(method.getName())) return true;
                        throw new AssertionError("headless modal visibility test attempted " + method.getName());
                    });
            setUiField(nativeTools, "shortcuts", openShortcuts);
            SimpleAssert.that(
                !ui.shortcuts.idleHintsVisible(HologramIdleHints.DELAY_NANOS),
                "an open shortcut editor suppresses idle guidance instead of overlaying the modal");
            setUiField(nativeTools, "shortcuts", previousShortcuts);
            ui.library.libraryDrawer(true);
            SimpleAssert.that(
                ui.shortcuts.idleHintsVisible(HologramIdleHints.DELAY_NANOS),
                "an expanded library dock leaves the editable scene and its idle guidance available");
            setUiField(ui.library, "browsePreview", true);
            SimpleAssert.that(
                !ui.shortcuts.idleHintsVisible(HologramIdleHints.DELAY_NANOS),
                "explicit read-only library display still suppresses editing guidance");
            setUiField(ui.library, "browsePreview", false);
            ui.library.libraryDrawer(false);
            ui.canvas.controls()
                .clear();
            ui.preview.drawMoveHandleControl(-1, -1);
            SimpleAssert.that(
                ui.canvas.controls()
                    .get(0).enabled,
                "hidden handles keep their recovery control enabled");
            ui.router.activate(147);
            SimpleAssert.that(
                ui.preview.moveHandles(),
                "the same native action restores the handles without changing the selection");
            ui.preview.consumeGizmoKey('g', org.lwjgl.input.Keyboard.KEY_G);
            SimpleAssert.that(!ui.preview.moveHandles(), "the original G shortcut shares the same visibility state");
            ui.preview.selection()
                .click(0, false);
            ui.canvas.controls()
                .clear();
            ui.preview.drawMoveHandleControl(-1, -1);
            SimpleAssert.that(
                !ui.canvas.controls()
                    .get(0).enabled,
                "a partial structure never enables movement");
            ui.router.activate(147);
            SimpleAssert
                .that(!ui.preview.moveHandles(), "an unavailable axis action cannot toggle around the selection gate");
            SimpleAssert.that(
                ui.canvas.disabledReason(147)
                    .contains("未包含完整结构"),
                "partial selection explains the missing axis");
            ui.preview.selection()
                .structure(state, "machine", false);
            ui.session.pendingSequence(4);
            SimpleAssert.that(
                ui.canvas.disabledReason(147)
                    .contains("等待服务端回执"),
                "a pending command explains temporary unavailability");
            ui.session.pendingSequence(-1);
            state.data.setBoolean("virtual", false);
            SimpleAssert.that(
                ui.canvas.disabledReason(147)
                    .contains("真实控制器模式"),
                "live controller configuration retains its existing movement boundary");
            SimpleAssert.eq(0L, ui.session.sequence(), "axis visibility and explanations emit no command");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot exercise the actual movement control", failure);
        } finally {
            if (singleton != null) try {
                singleton.set(null, previous);
            } catch (IllegalAccessException failure) {
                throw new AssertionError("cannot restore detached Minecraft singleton", failure);
            }
        }
    }

    private static void idleHintTiming() {
        HologramIdleHints hints = new HologramIdleHints(0);
        long delay = HologramIdleHints.DELAY_NANOS;
        SimpleAssert.that(!hints.visible(delay), "a screen waits for its first pointer observation");
        hints.pointer(20, 30, 0);
        SimpleAssert.that(!hints.visible(delay - 1), "idle hints do not appear before two seconds");
        hints.pointer(20, 30, delay);
        SimpleAssert.that(hints.visible(delay), "stationary frames allow idle hints to appear");
        hints.pointer(21, 30, delay);
        SimpleAssert.that(!hints.visible(delay), "even one pixel of mouse movement immediately hides guidance");
        SimpleAssert.that(hints.visible(delay * 2), "guidance returns after the next idle interval");
        hints.activity(delay * 2);
        SimpleAssert.that(!hints.visible(delay * 2), "input observation immediately cancels visible guidance");
        SimpleAssert.that(!hints.visible(delay * 3 - 1), "input restarts the full idle interval");
        SimpleAssert.that(hints.visible(delay * 3), "guidance returns after another idle interval");
    }

    private static void nativeBindingDrawerControls() {
        HologramUiModules ui = headlessUi();
        NBTTagCompound data = new NBTTagCompound(), bindings = new NBTTagCompound();
        data.setString("session", "native-binding-router");
        data.setLong("uiSequence", 3);
        NBTTagList groups = new NBTTagList();
        for (int i = 0; i < 10; i++) {
            NBTTagCompound group = new NBTTagCompound();
            group.setString("id", "q" + i);
            group.setBoolean("bound", i != 0);
            groups.appendTag(group);
        }
        bindings.setTag("groups", groups);
        data.setTag("networkBindings", bindings);
        try {
            HologramState snapshot = new HologramState(data);
            setUiField(ui.session, "state", snapshot);
            groups = snapshot.data.getCompoundTag("networkBindings")
                .getTagList("groups", 10);
            setUiField(ui.session, "sequence", 3L);
            setUiField(ui.diagnostics, "captureRenderSmokeAction", true);
            SimpleAssert.that(
                !ui.bindings.consumeNativeControl(197) && !ui.bindings.consumeNativeControl(201),
                "a hidden binding drawer does not intercept another page's reused control ids");
            ui.bindings.networkDrawer(true);
            for (int id : new int[] { 197, 198, 199, 201, 60001, 61033 }) ui.canvas.controls()
                .add(new HologramUiCanvas.Control(id, 0, 0, 20, 20, true));
            ui.router.activate(197);
            SimpleAssert.that(ui.bindings.networkDrawer(), "an unbound group still refuses native confirm197");
            SimpleAssert.eq(-1L, ui.session.pendingSequence(), "refused group confirmation emits no command");
            ui.router.activate(60001);
            SimpleAssert.eq(1, ui.bindings.networkGroupIndex(), "native group row reaches the binding handler");
            ui.router.activate(199);
            SimpleAssert.eq(5, ui.bindings.networkGroupOffset(), "native next-page control reaches the drawer");
            ui.router.activate(198);
            SimpleAssert.eq(0, ui.bindings.networkGroupOffset(), "native previous-page control reaches the drawer");
            ui.router.activate(61033);
            SimpleAssert.eq(-1L, ui.session.pendingSequence(), "preserved group refuses terminal rebinding");
            ui.router.activate(55);
            SimpleAssert.eq(-1L, ui.session.pendingSequence(), "drawer consumes an underlying build control");
            groups.getCompoundTagAt(0)
                .setBoolean("bound", true);
            ui.canvas.controls()
                .set(0, new HologramUiCanvas.Control(197, 0, 0, 20, 20, false));
            ui.router.activate(197);
            SimpleAssert.that(ui.bindings.networkDrawer(), "a disabled native confirm remains inert");
            ui.canvas.controls()
                .set(0, new HologramUiCanvas.Control(197, 0, 0, 20, 20, true));
            ui.router.activate(197);
            NBTTagCompound confirmation = (NBTTagCompound) uiField(ui.diagnostics, "capturedRenderSmokeAction");
            SimpleAssert.that(!ui.bindings.networkDrawer(), "native confirm closes the actual local drawer");
            SimpleAssert.eq(
                "closeNetworkBindings",
                confirmation.getString("op"),
                "native confirm emits the existing backend operation");
            SimpleAssert.eq(
                "native-binding-router",
                confirmation.getString("session"),
                "confirmation retains the actual session");
            SimpleAssert.eq(4L, ui.session.pendingSequence(), "native confirm starts a fresh sequenced ACK ticket");
            ui.session.pendingSequence(-1);
            ui.bindings.networkDrawer(true);
            groups.getCompoundTagAt(0)
                .setBoolean("bound", false);
            setUiField(ui.diagnostics, "capturedRenderSmokeAction", null);
            ui.router.activate(201);
            NBTTagCompound cancel = (NBTTagCompound) uiField(ui.diagnostics, "capturedRenderSmokeAction");
            SimpleAssert.that(!ui.bindings.networkDrawer(), "native cancel201 can leave an incomplete drawer");
            SimpleAssert
                .eq("closeNetworkBindings", cancel.getString("op"), "native cancel uses the original close operation");
            SimpleAssert.eq(5L, ui.session.pendingSequence(), "cancel receives its own subsequent ACK ticket");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect the actual composed binding dispatch", failure);
        }
    }

    private static HologramUiModules headlessUi() {
        try {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            HologramScreen parent = (HologramScreen) ((sun.misc.Unsafe) unsafeField.get(null))
                .allocateInstance(HologramScreen.class);
            java.lang.reflect.Field fields = HologramMuiCanvasScreen.class.getDeclaredField("fields");
            fields.setAccessible(true);
            fields.set(parent, new ArrayList<HologramMuiTextField>());
            return new HologramUiModules(new HeadlessHost(parent));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot create headless workstation host", failure);
        }
    }

    /** Supplies the real canvas parent cleanup boundary without constructing MUI, Minecraft, or a renderer. */
    private static final class HeadlessHost implements HologramUiHost {

        private final HologramScreen parent;

        HeadlessHost(HologramScreen parent) {
            this.parent = parent;
        }

        @Override
        public net.minecraft.client.Minecraft minecraft() {
            return net.minecraft.client.Minecraft.getMinecraft();
        }

        @Override
        public net.minecraft.client.gui.FontRenderer font() {
            throw new AssertionError("headless control test requested a renderer");
        }

        @Override
        public int screenWidth() {
            return HologramScreenLayout.WIDTH;
        }

        @Override
        public int screenHeight() {
            return HologramScreenLayout.HEIGHT;
        }

        @Override
        public HologramScreen parent() {
            return parent;
        }

        @Override
        public void renderBackground() {
            throw new AssertionError("headless control test requested background rendering");
        }

        @Override
        public void renderText(net.minecraft.client.gui.FontRenderer font, String text, int x, int y, int color) {
            throw new AssertionError("headless control test requested text rendering");
        }

        @Override
        public void renderCenteredText(net.minecraft.client.gui.FontRenderer font, String text, int x, int y,
            int color) {
            throw new AssertionError("headless control test requested centered text rendering");
        }

        @Override
        public void renderTooltip(List<String> rows, int x, int y, net.minecraft.client.gui.FontRenderer font) {
            throw new AssertionError("headless control test requested tooltip rendering");
        }
    }

    private static Object uiField(Object owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setUiField(Object owner, String name, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void switchReviewOwnsOverlappingLibraryRows() {
        List<HologramUiCanvas.Control> controls = new ArrayList<>();
        controls.add(new HologramUiCanvas.Control(14002, 457, 179, 215, 28, true));
        HologramUiCanvas.Control confirm = new HologramUiCanvas.Control(224, 338, 201, 244, 22, true);
        controls.add(confirm);
        SimpleAssert.that(
            HologramUiLibrary.switchReviewControl(controls, 470, 203) == confirm,
            "discard/overwrite confirmation owns clicks that overlap the underlying selected library row");
        HologramUiCanvas.Control disabled = new HologramUiCanvas.Control(224, 338, 201, 244, 22, false);
        controls.add(disabled);
        SimpleAssert.that(
            HologramUiLibrary.switchReviewControl(controls, 470, 203) == disabled,
            "a disabled modal control still consumes the hit instead of activating the underlying library");
        SimpleAssert.eq(
            null,
            HologramUiLibrary.switchReviewControl(controls, 470, 190),
            "empty modal space never resolves to an underlying library row");
    }

    private static void outgoingSelectionScope() {
        for (String op : new String[] { "close", "settings", "savePreset", "saveBlueprint", "saveBlueprintAs",
            "saveBlueprintReplace", "updatePreset", "processBlueprint", "confirmAeSave", "aePreviewEdit",
            "configureBlueprintQuantumGroups", "selectStructure", "confirmBlueprintPlacement", "pause", "cancel" }) {
            NBTTagCompound request = new NBTTagCompound();
            request.setLong("clientTaskToken", 71L);
            HologramUiSession.buildOutgoingRequest(
                op,
                request,
                9,
                () -> {
                    throw new AssertionError(
                        "non-selection operation materialized a potentially million-cell selection");
                },
                "module-a",
                "session-a");
            SimpleAssert.that(!request.hasKey("selectedIndices"), op + " sends no unrelated selection array");
            SimpleAssert.eq(71L, request.getLong("clientTaskToken"), op + " retains its background task owner");
            SimpleAssert.eq("session-a", request.getString("session"), op + " retains the workspace owner");
            SimpleAssert.eq("module-a", request.getString("structureId"), op + " retains structure context");
            SimpleAssert.eq(9, request.getInteger("selected"), op + " retains the single selected cell context");
        }
        int[] indices = new int[50000];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        for (String op : new String[] { "configure", "build", "start", "scan", "editCopy", "editCut", "editPaste",
            "editMove", "restoreCell", "rotateCell", "clearPin", "customPin", "sourcePin", "pin", "hatchFacing",
            "hatchPreset", "clearHatchPreset", "editCellFlags", "editHintArea", "configureBlueprintCellNbt",
            "editBlueprintCellNbt" }) {
            NBTTagCompound request = HologramUiSession
                .buildOutgoingRequest(op, new NBTTagCompound(), 7, () -> indices, "module-a", "session-a");
            SimpleAssert.that(
                Arrays.equals(indices, request.getIntArray("selectedIndices")),
                op + " retains every selected cell, including configure scope=2 and processing NBT fallback");
        }
        NBTTagCompound explicit = new NBTTagCompound();
        explicit.setIntArray("indices", new int[] { 2, 5 });
        explicit.setIntArray("selectedIndices", new int[] { 8 });
        explicit.setInteger("selected", 8);
        explicit.setString("structureId", "explicit-module");
        HologramUiSession.buildOutgoingRequest(
            "editCut",
            explicit,
            0,
            () -> { throw new AssertionError("explicit selection was unnecessarily rebuilt"); },
            "module-a",
            "session-a");
        SimpleAssert
            .that(Arrays.equals(new int[] { 2, 5 }, explicit.getIntArray("indices")), "explicit batch survives");
        SimpleAssert
            .that(Arrays.equals(new int[] { 8 }, explicit.getIntArray("selectedIndices")), "explicit scope survives");
        SimpleAssert.eq("explicit-module", explicit.getString("structureId"), "explicit structure context survives");
    }

    private static void localPlanConsumersWaitWithoutLockingEditing() {
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("localPlanPending", true);
        state.setInteger("job", 2);
        for (int id : new int[] { 50, 51, 55, 132, 140, 159, 162, 179, 181, 192, 220, 221, 293, 294 })
            SimpleAssert.that(
                HologramUiCanvas.localPlanBlocks(state, id),
                "full-plan consumer " + id + " waits for the server planning publication");
        for (int id : new int[] { 10, 11, 12, 13, 14, 15, 52, 54, 60, 61, 65, 66, 69, 95, 108, 109, 112, 113, 134, 146,
            148, 149, 182, 214, 216, 225, 230, 232, 233, 234, 240, 263, 295, 296, 10001 })
            SimpleAssert.that(
                !HologramUiCanvas.localPlanBlocks(state, id),
                "editing, selection, view or cancellation control " + id + " remains available during planning");
        state.setInteger("job", 1);
        SimpleAssert.that(
            !HologramUiCanvas.localPlanBlocks(state, 51),
            "pausing an existing job remains available although resuming waits for the current plan");
        SimpleAssert.that(
            HologramUiCanvas.localPlanBlocks(state, 231, true, false)
                && !HologramUiCanvas.localPlanBlocks(state, 231, false, false),
            "only the processing wizard's final server submission waits; local next-step navigation remains usable");
        SimpleAssert.that(
            HologramUiCanvas.localPlanBlocks(state, 224, false, true)
                && !HologramUiCanvas.localPlanBlocks(state, 224, false, false),
            "overwrite/save confirmation waits while discarding edits or switching sources remains usable");
        state.setString("localPlanError", "actual planner failure");
        SimpleAssert.eq(
            "actual planner failure",
            HologramUiCanvas.localPlanReason(state),
            "a failed planner shows its actual error rather than pretending to make progress");
        state.setBoolean("localPlanPending", false);
        SimpleAssert.that(
            !HologramUiCanvas.localPlanBlocks(state, 140),
            "the successful complete-plan publication immediately reopens saving");
        HologramProgress.Tracker progress = new HologramProgress.Tracker();
        HologramProgress actual = progress.update("plan-a", "localPlan.plan.targets", 17, 410, "cells", false, 1L);
        SimpleAssert.that(
            HologramUiCanvas.localPlanProgress(actual) && actual.done == 17 && actual.total == 410,
            "the original preview uses factual local planning counters");
        SimpleAssert.that(
            !HologramUiCanvas.localPlanProgress(progress.update("job-a", "job", 17, 410, "cells", false, 2L)),
            "another server operation is never relabeled as local planning progress");
        SimpleAssert.that(
            !HologramUiCanvas
                .localPlanProgress(progress.update("plan-a", "localPlan.plan.targets", 410, 410, "cells", true, 3L)),
            "a completed local phase does not leave a stale pending overlay");
    }
}
