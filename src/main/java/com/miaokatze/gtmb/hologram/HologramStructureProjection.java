package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;

/** Native structure materials and hatch planning without blueprint processing or persistence capture. */
final class HologramStructureProjection {

    private HologramStructureProjection() {}

    static boolean showing(HologramSession s) {
        return HologramEditorWorkspaceRegistry.physicalProjection(s) && s.capture != null && s.capture.projectionOnly;
    }

    static void refresh(HologramSession s) {
        cancel(s);
        if (!ItemStack.areItemStacksEqual(s.capture.trigger, HologramSessionPolicy.trigger(s)))
            HologramControllerCapture.collectProjection(s, true);
        else HologramControllerCapture.replanProjection(s);
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

    static void start(HologramSession s) {
        if (!showing(s) || s.capturePending || s.capture.incomplete || s.cancelled) return;
        if (s.open && HologramSessionPolicy.valid(s)) s.libraryUiActive = true;
        HologramSnapshotPreparation.start(s);
    }

    static boolean pending(HologramSession s) {
        return showing(s) && HologramSnapshotPreparation.pending(s);
    }

    static boolean canAdvance(HologramSession s) {
        return showing(s) && HologramSnapshotPreparation.canAdvance(s);
    }

    static void cancel(HologramSession s) {
        if (showing(s)) HologramSnapshotPreparation.cancel(s);
    }

    static boolean tick(HologramSession s) {
        return showing(s) && HologramSnapshotPreparation.tick(s);
    }

    static NBTTagCompound header(HologramSession s) {
        NBTTagCompound state = new NBTTagCompound();
        HologramSnapshots.writeServerRules(state);
        state.setString("session", s.id);
        state.setString("generation", s.id);
        state.setString("toolId", s.toolId);
        state.setLong("planRevision", s.planRevision);
        state.setLong("uiSequence", s.uiSequence);
        state.setLong("placementReviewNonce", s.placementReviewNonce);
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
        state.setBoolean("quotesIncomplete", false);
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
        state.setBoolean("presetSupported", s.presetSupported);
        state.setBoolean("presetAvailable", s.presetAvailable);
        state.setBoolean("presetActive", s.presetEnabled || !s.autoTargets.isEmpty());
        state.setString("presetReason", s.presetReason);
        state.setBoolean("materialMain", s.materialSettings.main);
        state.setBoolean("materialContainers", s.materialSettings.containers);
        state.setBoolean("materialMe", s.materialSettings.me);
        state.setInteger("materialPriority", s.materialSettings.priority);
        state.setInteger("recoveryDestination", s.materialSettings.recoveryDestination);
        state.setBoolean("recoveryFits", s.planRecoveryFits);
        state.setString("blueprintResourceMissing", s.blueprintResourceMissing);
        NBTTagList choices = new NBTTagList();
        for (ItemStack sample : s.materialChoices) choices.appendTag(sample.writeToNBT(new NBTTagCompound()));
        state.setTag("materialChoices", choices);
        try {
            state.setTag("presets", HologramLibraryFacade.enabled() ? new NBTTagList() : HologramPresets.labels());
        } catch (java.io.IOException invalid) {
            state.setTag("presets", new NBTTagList());
            state.setString("presetConfigError", invalid.getMessage());
        }
        state.setInteger("mode", s.mode);
        state.setInteger("scope", s.scope);
        state.setInteger("layer", s.layer);
        state.setInteger("selected", s.selected);
        state.setIntArray(
            "selectedIndices",
            s.selectedIndices.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        state.setInteger("job", 0);
        state.setString("status", s.status);
        state.setBoolean("editCanUndo", !s.undoEdits.isEmpty());
        state.setBoolean("editCanRedo", !s.redoEdits.isEmpty());
        state.setBoolean("editCanPaste", s.editClipboard != null);
        return state;
    }

    static NBTTagCompound row(HologramSession s, HologramCapture.Cell cell, int index) {
        return rowData(s, cell, index).row;
    }

    /** Native material identity and GT visual scalars only; no blueprint payload capture or processing. */
    static HologramSnapshots.CellRow rowData(HologramSession s, HologramCapture.Cell cell, int index) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", cell.x);
        row.setInteger("y", cell.y);
        row.setInteger("z", cell.z);
        row.setInteger("dx", cell.x - s.x);
        row.setInteger("dy", cell.y - s.y);
        row.setInteger("dz", cell.z - s.z);
        World world = s.player.worldObj;
        boolean loaded = cell.planLoaded;
        Block actual = s.expectedBlocks.get(index);
        int actualMeta = s.expectedMeta.get(index);
        net.minecraft.tileentity.TileEntity tile = s.expectedTiles.get(index);
        ItemStack target = cell.anchor ? cell.actualStack : HologramCellPolicy.targetFor(s, cell, index);
        Block wanted = cell.block;
        int wantedMeta = cell.meta;
        if (target != null) {
            try {
                wanted = HologramCellPolicy.targetPreviewBlock(s, cell, target);
                wantedMeta = HologramCellPolicy.targetPreviewMeta(s, cell, target);
            } catch (java.io.IOException invalid) {
                throw new IllegalStateException("Invalid native structure target", invalid);
            }
        }
        HologramStructureHintData.Hint hint = cell.renderHint;
        if (wanted == null && hint != null) {
            wanted = hint.block;
            wantedMeta = hint.metadata;
        }
        boolean inScope = HologramSessionPolicy.inScope(s, cell, index);
        String position = HologramValues.position(cell);
        int facing = cell.anchor ? cell.actualFacing
            : s.facingPins.getOrDefault(position, s.planFacings.getOrDefault(index, -1));
        row.setString("id", String.valueOf(Block.blockRegistry.getNameForObject(actual)));
        row.setInteger("meta", actualMeta);
        row.setString("wantId", wanted == null ? "" : String.valueOf(Block.blockRegistry.getNameForObject(wanted)));
        row.setInteger("wantMeta", wantedMeta);
        row.setBoolean("missingBlock", wanted == null);
        row.setString(
            "renderKind",
            target != null && target.getItem() instanceof gregtech.common.blocks.ItemMachines || cell.anchor ? "item"
                : cell.renderKind);
        row.setInteger("hintIndex", cell.hintIndex);
        row.setBoolean("iconOnly", cell.iconOnly && wanted == null);
        NBTTagList icons = new NBTTagList();
        if (cell.hintIcons != null)
            for (String icon : cell.hintIcons) icons.appendTag(new NBTTagString(icon == null ? "" : icon));
        row.setTag("hintIcons", icons);
        if (cell.hintTint != null) {
            int[] tint = new int[cell.hintTint.length];
            for (int i = 0; i < tint.length; i++) tint[i] = cell.hintTint[i];
            row.setIntArray("hintTint", tint);
        }
        row.setBoolean("anchor", cell.anchor);
        row.setBoolean("excluded", cell.excluded);
        row.setBoolean("userPosition", cell.userPosition);
        row.setString("prompt", cell.prompt);
        row.setBoolean("inScope", inScope);
        row.setString("status", cell.status);
        row.setString("role", cell.role);
        row.setString("family", cell.family);
        row.setString("structureId", cell.structureId);
        row.setTag("structureIds", HologramSessionPolicy.nbtKeys(cell.memberships()));
        row.setString(
            "exactIdentity",
            target == null ? HologramBlockIdentity.key(cell.block, cell.meta) : HologramBlockIdentity.key(target));
        row.setBoolean("hasPin", s.pins.containsKey(index));
        row.setBoolean("hasTargetOverride", s.pins.containsKey(index) || s.autoTargets.containsKey(index));
        row.setBoolean(
            "explicitTarget",
            HologramCellPolicy.explicitTarget(s, cell, index) || HologramCellPolicy.explicitGrade(s, cell));
        row.setBoolean("autoTarget", s.autoTargets.containsKey(index));
        row.setBoolean("hasFacingPin", s.facingPins.containsKey(position));
        row.setInteger("targetFacing", facing);
        row.setInteger("hatchFacing", facing);
        row.setInteger("actualFacing", s.expectedFacings.get(index));
        row.setIntArray("hatchFacings", HologramCellPolicy.legalFacings(s, cell, target));
        NBTTagCompound display = HologramDisplaySnapshot.projection(tile);
        row.setTag("actualDisplay", display);
        if (tile instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity) {
            ItemStack actualStack = new ItemStack(
                gregtech.api.GregTechAPI.sBlockMachines,
                1,
                ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) tile).getMetaTileID());
            row.setTag("actualStack", actualStack.writeToNBT(new NBTTagCompound()));
        }
        if (target != null) row.setTag("targetStack", target.writeToNBT(new NBTTagCompound()));
        NBTTagCompound targetDisplay = cell.anchor ? display
            : HologramDisplaySnapshot.targetPayload(wanted, target, facing, cell.element, null, false);
        row.setTag("targetDisplay", targetDisplay);
        row.setString("reason", HologramPlanPolicy.reason(s, cell, index, loaded, tile));
        row.setString(
            "operation",
            !inScope ? "OUTSIDE"
                : "satisfied".equals(cell.status) ? "KEEP"
                    : "protected".equals(cell.status) || "unsupported".equals(cell.status)
                        || "blocked".equals(cell.status)
                            ? "PROTECT"
                            : s.mode == 2 ? "REMOVE"
                                : loaded && (actual == net.minecraft.init.Blocks.air
                                    || actual instanceof net.minecraft.block.BlockBush && tile == null
                                        && actual.isReplaceable(world, cell.x, cell.y, cell.z)) ? "PLACE" : "REPLACE");
        java.util.List<ItemStack> options = HologramCellPolicy.snapshotCandidates(s, cell, index == s.selected);
        NBTTagList candidates = new NBTTagList(), roles = new NBTTagList();
        int[] tiers = new int[options.size()], available = new int[options.size()];
        int chosen = -1;
        for (int i = 0; i < options.size(); i++) {
            ItemStack sample = options.get(i);
            candidates.appendTag(sample.writeToNBT(new NBTTagCompound()));
            roles.appendTag(new NBTTagString(HologramCellPolicy.candidateRole(s, cell, sample)));
            int tier = HologramCellPolicy.hatchTier(sample);
            tiers[i] = tier == Integer.MAX_VALUE ? -1 : tier;
            available[i] = HologramMaterialAccess.shellCount(s, sample, Math.max(4096, s.capture.cells.size()));
            if (HologramValues.same(target, sample)) chosen = i;
        }
        row.setTag("candidates", candidates);
        row.setTag("candidateRoles", roles);
        row.setIntArray("candidateTiers", tiers);
        row.setIntArray("candidateAvailable", available);
        row.setInteger("chosenChoice", chosen);
        row.setBoolean("candidateComplete", cell.candidateComplete);
        row.setString("candidateBoundary", cell.candidateBoundary);
        row.setString("pinScope", "target");
        return new HologramSnapshots.CellRow(row, target, loaded, inScope, actual, actualMeta, tile);
    }
}
