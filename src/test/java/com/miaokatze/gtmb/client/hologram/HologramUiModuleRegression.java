package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Boundary regressions for the actual composed workstation input and request lifecycle. */
public final class HologramUiModuleRegression {

    private HologramUiModuleRegression() {}

    public static void run() {
        localPlanConsumersWaitWithoutLockingEditing();
        outgoingSelectionScope();
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
