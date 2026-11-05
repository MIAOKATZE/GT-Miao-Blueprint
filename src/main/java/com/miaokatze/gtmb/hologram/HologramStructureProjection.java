package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureLibAPI;

/** Physical structure presentation. No candidate, construction-plan or blueprint-processing preparation. */
final class HologramStructureProjection {

    private HologramStructureProjection() {}

    static boolean showing(HologramSession s) {
        return HologramEditorWorkspaceRegistry.physicalProjection(s) && s.capture != null && s.capture.projectionOnly;
    }

    static void refresh(HologramSession s) {
        cancel(s);
        s.planRevision++;
    }

    static void rememberEdits(HologramSession s, java.util.Set<Integer> indices) {
        for (int index : indices) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            s.projectionEdits.put(HologramValues.position(cell), cell);
        }
    }

    /** Full operation preparation uses the native source/owner-checked continuation, never a preview plan. */
    static boolean prepareOperation(HologramSession s) {
        if (!showing(s)) return !s.capturePending;
        if (s.capturePending || s.capture.incomplete
            || !HologramSessionPolicy.valid(s)
            || HologramWorkspace.find(s.player.getUniqueID()) != s
            || !s.capture.sourceCurrent(HologramSessionPolicy.trigger(s))) {
            s.projectionHatchConfiguration = null;
            s.status = "结构来源已变化或仍在读取，请重新核对后操作";
            if (s.capture.incomplete) HologramControllerCapture.stopRejectedSource(s);
            return false;
        }
        cancel(s);
        HologramSnapshotPreparation.cancel(s);
        HologramSession.clearPending(s);
        HologramAuthoringWorkflow.clearSourceSwitch(s);
        s.forcePlacementReady = false;
        s.placementReviewIssued = false;
        s.placementReviewNonce++;
        s.ae.invalidate();
        s.processingSource = null;
        s.processingReviewed = false;
        s.processingWorkflowStarted = false;
        s.planRevision++;
        NBTTagCompound hatchConfiguration = s.projectionHatchConfiguration;
        s.projectionHatchConfiguration = null;
        HologramControllerCapture.collect(s);
        if (s.capture == null || s.capture.incomplete) return false;
        s.status = s.capturePending ? "正在准备完整操作数据；施工需重新确认当前计划" : "完整操作数据已准备；请核对当前计划";
        if (hatchConfiguration != null && s.capture != null && !s.capture.projectionOnly && !s.capture.incomplete) {
            s.projectionHatchConfiguration = hatchConfiguration;
            s.projectionHatchCapture = s.capture;
            s.projectionHatchUi = s.uiSequence;
            s.projectionHatchEpoch = s.editorWorkspaceEpoch;
            if (!s.capturePending) finishHatchConfiguration(s);
        }
        return false;
    }

    static void finishHatchConfiguration(HologramSession s) {
        NBTTagCompound request = s.projectionHatchConfiguration;
        HologramCapture capture = s.projectionHatchCapture;
        s.projectionHatchConfiguration = null;
        s.projectionHatchCapture = null;
        if (request != null && capture == s.capture
            && capture != null
            && !capture.incomplete
            && !capture.pending
            && !s.capturePending
            && s.projectionHatchUi == s.uiSequence
            && s.projectionHatchEpoch == s.editorWorkspaceEpoch
            && !s.cancelled
            && HologramWorkspace.find(s.player.getUniqueID()) == s
            && HologramSessionPolicy.valid(s)
            && capture.sourceCurrent(HologramSessionPolicy.trigger(s))) HologramActionDispatcher.configure(s, request);
    }

    /** Only save/export intents survive preparation; placement confirmations are deliberately not retained. */
    static boolean prepareSave(HologramSession s, NBTTagCompound request, String operation) {
        if (!showing(s)) return !s.capturePending;
        if (!request.hasKey("planRevision", 4) || request.getLong("planRevision") != s.planRevision) {
            s.status = "保存来源已更新，请重新核对当前结构";
            return false;
        }
        SavePreparation save = new SavePreparation(s, request, operation);
        if (!prepareOperation(s) && s.capture != null && !s.capture.projectionOnly && !s.capture.incomplete) {
            save.capture = s.capture;
            s.structureSavePreparation = save;
            if (!s.capturePending) finishSave(s);
        }
        return false;
    }

    static void finishSave(HologramSession s) {
        SavePreparation save = s.structureSavePreparation;
        s.structureSavePreparation = null;
        if (save == null || !save.current(s)) return;
        NBTTagCompound request = (NBTTagCompound) save.request.copy();
        request.setLong("planRevision", s.planRevision);
        request.setLong("uiSequence", s.uiSequence);
        try {
            if ("savePreset".equals(save.operation)) HologramPresetWorkflow.savePreset(s, request.getString("name"));
            else if ("exportDraft".equals(save.operation))
                HologramLibraryWorkflow.libraryExportDraft(s.player, request);
            else HologramAuthoringWorkflow.blueprintAction(s, request, save.operation);
        } catch (java.io.IOException failure) {
            s.status = "保存准备失败：" + failure.getMessage();
        }
    }

    static final class SavePreparation {

        final NBTTagCompound request;
        final String operation, session;
        final Object target, machine;
        final World world;
        final long epoch, ui;
        final int main, facing;
        final boolean noHatches;
        final NBTTagCompound channels;
        HologramCapture capture;

        SavePreparation(HologramSession s, NBTTagCompound request, String operation) {
            this.request = (NBTTagCompound) request.copy();
            this.operation = operation;
            session = s.id;
            target = s.target;
            machine = s.machine;
            world = s.player.worldObj;
            epoch = s.editorWorkspaceEpoch;
            ui = s.uiSequence;
            main = s.main;
            facing = s.facing;
            noHatches = s.noHatches;
            channels = (NBTTagCompound) s.channels.copy();
        }

        boolean current(HologramSession s) {
            return !s.cancelled && session.equals(s.id)
                && target == s.target
                && machine == s.machine
                && world == s.player.worldObj
                && epoch == s.editorWorkspaceEpoch
                && ui == s.uiSequence
                && main == s.main
                && facing == s.facing
                && noHatches == s.noHatches
                && channels.equals(s.channels)
                && HologramWorkspace.find(s.player.getUniqueID()) == s
                && HologramSessionPolicy.valid(s)
                && !s.capturePending
                && s.capture == capture
                && !capture.incomplete
                && !capture.projectionOnly
                && s.capture.sourceCurrent(HologramSessionPolicy.trigger(s));
        }
    }

    static void collect(HologramSession s, boolean reset) {
        HologramCapture resumed = !reset && s.capture != null && s.capture.projectionOnly && s.capture.pending
            ? s.capture
            : null;
        if (resumed == null) {
            s.structureSavePreparation = null;
            s.projectionHatchConfiguration = null;
            cancel(s);
            HologramSnapshotPreparation.cancel(s);
            if (s.capture != null) {
                for (java.util.Map.Entry<Integer, ItemStack> pin : s.pins.entrySet()) {
                    if (pin.getKey() >= 0 && pin.getKey() < s.capture.cells.size()) s.coordinatePins.put(
                        HologramValues.position(s.capture.cells.get(pin.getKey())),
                        pin.getValue()
                            .copy());
                }
                if (reset && s.selected >= 0 && s.selected < s.capture.cells.size())
                    s.scanSelectedPosition = HologramValues.position(s.capture.cells.get(s.selected));
                if (reset) for (int index : s.selectedIndices) if (index >= 0 && index < s.capture.cells.size())
                    s.scanSelectedPositions.add(HologramValues.position(s.capture.cells.get(index)));
            }
            HologramSession.cancelCaptureRetry(s);
            if (reset) s.planRevision++;
            s.job = 0;
            s.cursor = s.completed = 0;
            if (reset) {
                s.captureAttempts = 0;
                s.captureError = "";
            }
            s.pins.clear();
            s.planTargets.clear();
            s.planFacings.clear();
            s.autoTargets.clear();
            s.expectedBlocks.clear();
            s.expectedMeta.clear();
            s.expectedTiles.clear();
            s.expectedFacings.clear();
            s.planStatus.clear();
            s.cellIndices.clear();
            s.indexedCapture = null;
            s.selected = -1;
            s.selectedIndices.clear();
            s.facing = s.machine.getExtendedFacing()
                .ordinal();
        }
        ItemStack trigger = HologramSessionPolicy.trigger(s);
        s.capturePending = false;
        s.capture = HologramCapture
            .collectProjection(s.machine, trigger, s.machine.getExtendedFacing(), s.captureIntent, resumed);
        if (resumed == null) s.captureAttempts++;
        if (s.capture.pending) {
            HologramControllerCapture.scheduleCaptureRetry(s);
            return;
        }
        if (s.capture.incomplete) {
            HologramControllerCapture.scheduleCaptureRetry(s);
            if (s.capturePending) return;
            HologramControllerCapture.stopRejectedSource(s);
            return;
        }
        s.captureRetryDue = 0;
        s.retryChannels = null;
        s.capabilities = HologramCapabilities.describe(s.context, trigger, s.capture.channelReport)
            .withSavedChannels(s.channels);
        s.status = "结构投影已读取；施工与蓝图数据按操作准备";
    }

    static void start(HologramSession s) {
        if (!showing(s) || s.capturePending || s.capture.incomplete || s.cancelled) return;
        Frame previous = s.structureProjectionFrame;
        if (previous != null && previous.valid()) return;
        cancel(s);
        if (s.open && HologramSessionPolicy.valid(s)) s.libraryUiActive = true;
        HologramNetwork.SnapshotTicket ticket = HologramNetwork.reserveOwnedState(s);
        if (ticket != null) s.structureProjectionFrame = new Frame(s, ticket);
    }

    static boolean pending(HologramSession s) {
        return s.structureProjectionFrame != null;
    }

    static boolean canAdvance(HologramSession s) {
        return s.structureProjectionFrame != null && !s.structureProjectionFrame.encoding;
    }

    static void cancel(HologramSession s) {
        Frame previous = s.structureProjectionFrame;
        s.structureProjectionFrame = null;
        if (previous != null) HologramNetwork.cancelState(previous.ticket);
    }

    static boolean tick(HologramSession s) {
        Frame frame = s.structureProjectionFrame;
        if (frame == null) return false;
        if (!frame.valid()) {
            cancel(s);
            if (s.capture != null && s.capture.incomplete) HologramControllerCapture.stopRejectedSource(s);
            return true;
        }
        if (frame.encoding) return true;
        try {
            frame.step(HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(HologramWorkBudget.SLICE_NANOS)));
        } catch (RuntimeException | LinkageError failure) {
            cancel(s);
            HologramControllerCapture.stop(s, "结构投影准备失败，请重新读取控制器", failure);
        }
        return true;
    }

    static NBTTagCompound header(HologramSession s) {
        NBTTagCompound state = new NBTTagCompound();
        HologramSnapshots.writeServerRules(state);
        state.setString("session", s.id);
        state.setString("generation", s.id);
        state.setString("toolId", s.toolId);
        state.setLong("planRevision", s.planRevision);
        state.setLong("uiSequence", s.uiSequence);
        state.setLong("configurationAcceptedSequence", s.configurationAcceptedSequence);
        state.setLong("editorWorkspaceEpoch", s.editorWorkspaceEpoch);
        state.setLong("editorWorkspaceSequenceFloor", HologramEditorWorkspaceRegistry.sequenceFloor(s));
        state.setString("editorWorkspace", "projection");
        state.setString("workspaceTab", "structure");
        state.setString("openPurpose", s.openPurpose);
        state.setBoolean("open", s.open);
        if (s.presentationTransition != null)
            state.setString("editorWorkspacePreviousSession", s.presentationTransition.originId);
        state.setBoolean("structureProjection", true);
        state.setBoolean("projectionDataComplete", true);
        state.setBoolean("operationPrepared", false);
        state.setBoolean("quotesIncomplete", true);
        state.setBoolean("supported", true);
        state.setBoolean("target", s.target != null);
        state.setBoolean("serverLibraryEnabled", HologramLibraryFacade.enabled());
        state.setString("selectedPresetId", HologramSessionPolicy.selectedPreset(s));
        state.setBoolean("creativeMaterials", s.player.capabilities.isCreativeMode);
        state.setString("captureIntent", s.capture.captureIntent.name());
        if (s.capture.sourceMetadata.hasKey("sourceId", 8))
            state.setString("sourceId", s.capture.sourceMetadata.getString("sourceId"));
        if (s.capture.sourceMetadata.hasKey("sourceRevision", 4))
            state.setLong("sourceRevision", s.capture.sourceMetadata.getLong("sourceRevision"));
        state.setBoolean("capturePending", false);
        state.setBoolean("snapshotPending", false);
        state.setBoolean("libraryUiActive", s.libraryUiActive);
        state.setBoolean("keepController", true);
        state.setInteger("dimension", s.dimension);
        state.setInteger("x", s.x);
        state.setInteger("y", s.y);
        state.setInteger("z", s.z);
        state.setInteger("side", s.side);
        state.setInteger("facing", s.facing);
        state.setIntArray("facings", new int[] { s.facing });
        state.setString("title", s.machine.getLocalName());
        state.setInteger("main", s.main);
        state.setBoolean("mainExplicit", s.mainExplicit);
        state.setTag("channels", s.channels.copy());
        state.setTag("capabilities", s.capabilities.toNBT());
        state.setBoolean("noHatches", s.noHatches);
        state.setInteger("hatchTier", s.hatchTier);
        state.setInteger("hatchTierMax", gregtech.api.enums.GTValues.VN.length - 1);
        state.setBoolean("hatchDowngrade", s.hatchDowngrade);
        state.setInteger("mode", s.mode);
        state.setInteger("scope", s.scope);
        state.setInteger("layer", s.layer);
        state.setInteger("selected", s.selected);
        state.setInteger("job", 0);
        state.setString("status", s.status);
        state.setBoolean("editCanUndo", !s.undoEdits.isEmpty());
        state.setBoolean("editCanRedo", !s.redoEdits.isEmpty());
        state.setBoolean("editCanPaste", s.editClipboard != null);
        return state;
    }

    static NBTTagCompound row(HologramSession s, HologramCapture.Cell cell, int index) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", cell.x);
        row.setInteger("y", cell.y);
        row.setInteger("z", cell.z);
        row.setInteger("dx", cell.x - s.x);
        row.setInteger("dy", cell.y - s.y);
        row.setInteger("dz", cell.z - s.z);
        HologramStructureHintData.Hint hint = cell.renderHint;
        ItemStack pin = s.pins.get(index);
        if (pin == null && !s.coordinatePins.isEmpty()) pin = s.coordinatePins.get(HologramValues.position(cell));
        if (!cell.anchor && pin != null && Block.getBlockFromItem(pin.getItem()) != Blocks.air)
            hint = new HologramStructureHintData.Hint(Block.getBlockFromItem(pin.getItem()), pin.getItemDamage());
        Block block = hint == null ? StructureLibAPI.getBlockHint() : hint.block;
        String id = block == null ? "minecraft:air" : String.valueOf(Block.blockRegistry.getNameForObject(block));
        row.setString("id", id);
        row.setString("wantId", id);
        row.setInteger("meta", hint == null ? 0 : hint.metadata);
        row.setInteger("wantMeta", hint == null ? 0 : hint.metadata);
        row.setString("renderKind", hint != null && hint.icons != null ? "icons" : cell.anchor ? "item" : "block");
        row.setInteger("hintIndex", block == StructureLibAPI.getBlockHint() && hint != null ? hint.metadata : -1);
        if (hint != null && hint.icons != null) {
            NBTTagList icons = new NBTTagList();
            for (String icon : hint.icons) icons.appendTag(new NBTTagString(icon == null ? "" : icon));
            row.setTag("hintIcons", icons);
            if (hint.tint != null) {
                int[] tint = new int[hint.tint.length];
                for (int i = 0; i < tint.length; i++) tint[i] = hint.tint[i];
                row.setIntArray("hintTint", tint);
            }
            row.setBoolean("iconOnly", true);
        }
        row.setBoolean("missingBlock", hint == null);
        row.setBoolean("anchor", cell.anchor);
        row.setBoolean("excluded", cell.excluded);
        row.setBoolean("userPosition", cell.userPosition);
        row.setString("prompt", cell.prompt);
        row.setBoolean("inScope", true);
        row.setString("status", "preview");
        row.setString("operation", "PREVIEW");
        row.setString("role", cell.anchor ? "controller" : "projection");
        if (hint == null) row.setString("candidateBoundary", cell.renderBoundary);
        if (cell.anchor && cell.actualDisplay != null) {
            row.setTag("actualDisplay", cell.actualDisplay.copy());
            row.setTag("targetDisplay", cell.actualDisplay.copy());
        }
        if (cell.actualStack != null) {
            ItemStack identity = new ItemStack(cell.actualStack.getItem(), 1, cell.actualStack.getItemDamage());
            row.setTag("actualStack", identity.writeToNBT(new NBTTagCompound()));
            row.setTag("targetStack", identity.writeToNBT(new NBTTagCompound()));
        }
        if (!cell.anchor && pin != null) row.setTag(
            "targetStack",
            new ItemStack(pin.getItem(), 1, pin.getItemDamage()).writeToNBT(new NBTTagCompound()));
        return row;
    }

    static final class Frame {

        final HologramSession s;
        final HologramNetwork.SnapshotTicket ticket;
        final HologramCapture capture;
        final World world;
        final long revision, ui, epoch;
        final NBTTagCompound state;
        final NBTTagList rows = new NBTTagList();
        final java.util.Iterator<HologramCapture.Cell> manual;
        final NBTTagList annotations;
        int cursor, unknown, annotation;
        boolean manualComplete;
        boolean encoding;

        Frame(HologramSession s, HologramNetwork.SnapshotTicket ticket) {
            this.s = s;
            this.ticket = ticket;
            capture = s.capture;
            world = s.player.worldObj;
            revision = s.planRevision;
            ui = s.uiSequence;
            epoch = s.editorWorkspaceEpoch;
            state = header(s);
            manual = s.manualPositions.values()
                .iterator();
            ItemStack held = HologramSessionPolicy.currentTool(s);
            annotations = (NBTTagList) (s.blueprintDraftAnnotations.tagCount() > 0 ? s.blueprintDraftAnnotations
                : held == null ? new NBTTagList()
                    : HologramDraftPersistence.storedDraft(s, held)
                        .getTagList("blueprintAnnotations", 10)).copy();
            HologramRuntimeMetrics.loadStage(
                HologramRuntimeMetrics.LoadStage.SERVER_ROWS_BEGIN,
                s,
                ticket.sequence(),
                capture.cells.size());
        }

        boolean valid() {
            if (!(ticket.current() && HologramWorkspace.find(s.player.getUniqueID()) == s
                && s.player.worldObj == world
                && s.libraryWorld == world
                && !s.cancelled
                && s.capture == capture
                && showing(s)
                && !s.capturePending
                && !capture.incomplete
                && revision == s.planRevision
                && ui == s.uiSequence
                && epoch == s.editorWorkspaceEpoch)) return false;
            return capture.sourceCurrent(HologramSessionPolicy.trigger(s)) && HologramSessionPolicy.tickValid(s);
        }

        void step(long deadline) {
            int work = 0;
            while (!manualComplete && (work++ == 0 || System.nanoTime() < deadline)) {
                if (!manual.hasNext()) {
                    manualComplete = true;
                    break;
                }
                HologramCapture.Cell cell = manual.next();
                String key = HologramValues.position(cell);
                if (!capture.positions.containsKey(key)) {
                    if (capture.cells.size() >= HologramCapture.LIMIT)
                        throw new IllegalStateException("Projection manual positions exceed cell limit");
                    capture.cells.add(cell);
                    capture.positions.put(key, cell);
                }
            }
            if (!manualComplete) return;
            while (annotation < annotations.tagCount() && (work++ == 0 || System.nanoTime() < deadline)) {
                NBTTagCompound saved = annotations.getCompoundTagAt(annotation++);
                int[] position = saved.getIntArray("position");
                if (position.length != 3
                    || !HologramSessionPolicy.validRelativePosition(s, position[0], position[1], position[2])) continue;
                HologramCapture.Cell cell = capture.positions
                    .get((s.x + position[0]) + "," + (s.y + position[1]) + "," + (s.z + position[2]));
                if (cell != null) {
                    cell.prompt = HologramSessionPolicy.boundedPrompt(saved.getString("prompt"));
                    cell.excluded = saved.getBoolean("excluded");
                }
            }
            if (annotation < annotations.tagCount()) return;
            while (cursor < capture.cells.size() && (work++ == 0 || System.nanoTime() < deadline)) {
                HologramCapture.Cell cell = capture.cells.get(cursor++);
                HologramCapture.Cell edited = s.projectionEdits.get(HologramValues.position(cell));
                if (edited != null) {
                    cell.prompt = edited.prompt;
                    cell.excluded = edited.excluded;
                }
                if (!s.coordinatePins.isEmpty() || s.scanSelectedPosition != null
                    || !s.scanSelectedPositions.isEmpty()) {
                    String key = HologramValues.position(cell);
                    ItemStack pin = s.coordinatePins.get(key);
                    if (pin != null && !cell.anchor) s.pins.put(cursor - 1, pin.copy());
                    if (key.equals(s.scanSelectedPosition)) s.selected = cursor - 1;
                    if (s.scanSelectedPositions.contains(key)) s.selectedIndices.add(cursor - 1);
                }
                if (cell.renderHint == null) unknown++;
                rows.appendTag(row(s, cell, cursor - 1));
            }
            if (cursor < capture.cells.size()) return;
            state.setInteger("cellCount", rows.tagCount());
            state.setInteger("renderUnknown", unknown);
            state.setInteger("selected", s.selected);
            state.setTag("cells", rows);
            encoding = true;
            HologramRuntimeMetrics
                .loadStage(HologramRuntimeMetrics.LoadStage.SERVER_ROWS_END, s, ticket.sequence(), rows.tagCount());
            HologramNetwork.completeOwnedState(ticket, state, this::valid, () -> {
                if (s.structureProjectionFrame != this) return;
                s.structureProjectionFrame = null;
                s.open = false;
                s.openPurpose = "";
                s.scanSelectedPosition = null;
                s.scanSelectedPositions.clear();
            });
        }
    }
}
