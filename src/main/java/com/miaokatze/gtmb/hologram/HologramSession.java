package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

final class HologramSession {

    final HologramNetworkBindings.State networkBindings = new HologramNetworkBindings.State();
    boolean networkBindingRequested;

    final EntityPlayerMP player;
    final World libraryWorld;
    String id = UUID.randomUUID()
        .toString();
    int x, y, z, side;
    final int dimension, slot;
    final Item tool;
    final ItemStack boundTool;
    final String toolId;
    final long openTick;
    TileEntity target;
    MTEEnhancedMultiBlockBase<?> machine;
    IConstructable constructable;
    Object context;
    int main = 1, facing, mode, scope, layer, selected = -1, job, cursor, completed;
    int jobMissingCells, jobProtectedCells, jobUnsupportedCells, jobSkippedCells;
    boolean jobPartialPlacement;
    /** Explicit material-review choice; the voucher is charged only by a successful covered transaction. */
    boolean jobVoucherRequested, jobVoucherConsumed;
    boolean placementValidating;
    HologramTextWorkflow.Task attachmentTask;
    NBTTagCompound textPlacementAck = new NBTTagCompound();
    NBTTagCompound materialQuote = new NBTTagCompound();
    HologramMaterialQuote.Cache materialQuoteCache;
    boolean noHatches, hints, defer, stopTick, mainExplicit;
    HologramBlueprintResources.Plan blueprintResources, blueprintTotalResources;
    HologramEnergy.Demand blueprintEnergy = new HologramEnergy.Demand();
    final List<HologramEnergy.Demand> blueprintEnergyCells = new ArrayList<>();
    final List<String> blueprintEnergyWarnings = new ArrayList<>();
    final List<String> blueprintIntrinsicSources = new ArrayList<>();
    String blueprintEnergyNotice = "";
    String blueprintResourceMissing = "";
    HologramCapture nbtKeysCapture;
    final java.util.Set<String> availableNbtKeys = new java.util.TreeSet<>();
    List<String> retainedNbtKeys, placementNbtKeys;
    boolean placementNbtCustomized;
    boolean deidentificationChosen, placementDeidentificationChosen;
    int placementDeidentificationLevel;
    Boolean placementRetainCovers;
    String blueprintActiveStructure = "", blueprintPrompt = "";
    String areaStructureId = "";
    HologramPresets.Structure areaOverviewConfiguration;
    HologramArea areaOverviewOwner;
    String blueprintOperation = "";
    String blueprintSavedId = "";
    String blueprintSource = "", discardTargetOp = "", discardTargetId = "";
    String areaImportPreviousSource = "";
    boolean areaImportPreviousDirty;
    String processingPresetOperation = "", selectedProcessingPresetId = "";
    String blueprintSaveReceipt = "", blueprintSaveLocalId = "";
    String blueprintSaveJsonSha256 = "", blueprintSavePreviewSha256 = "";
    String blueprintSaveSelectedPresetId = "";
    HologramPresets.Preset blueprintSavePrepared;
    boolean blueprintSaveUpdate;
    NBTTagCompound pendingSourceAction = new NBTTagCompound();
    TileEntity pendingSourceTarget;
    Object pendingSourceContext;
    HologramArea pendingSourceArea;
    NBTTagList pendingSourceRegions = new NBTTagList();
    int[] pendingSourcePoints = new int[0];
    Item pendingSourceTool;
    String pendingSourceToolId = "";
    int pendingSourceSlot;
    long blueprintSaveRevision;
    boolean processingPresetOperationSuccess;
    boolean blueprintDirty, discardConfirmationRequired, processingReviewed, processingWorkflowStarted;
    HologramPresets.Preset processingSource;
    HologramDecorMaterials.Ledger decorLedger = new HologramDecorMaterials.Ledger();
    final List<ItemStack> decorPlanItems = new ArrayList<>();
    final Map<HologramBlueprintResources.ExactKey, Long> pendingAssetBudget = new HashMap<>();
    final Map<Integer, Map<HologramBlueprintResources.ExactKey, Long>> cellAssetBudgets = new HashMap<>();
    HologramBlueprintProcessing.Policy processingPolicy;
    String workspaceTab = "structure";
    String openPurpose = "";
    long placementReviewNonce;
    boolean blueprintOperationSuccess;
    final List<HologramPresets.HintArea> blueprintHintAreas = new ArrayList<>();
    NBTTagList blueprintDraftAnnotations = new NBTTagList();
    boolean blueprintPreview, blueprintAwaitingConfirmation, placementReviewIssued, forcePlacementReady;
    final java.util.Set<Integer> selectedIndices = new java.util.LinkedHashSet<>();
    String scanSelectedPosition;
    final java.util.Set<String> scanSelectedPositions = new java.util.LinkedHashSet<>();
    final List<HologramCapture.Cell> blueprintClipboard = new ArrayList<>();
    boolean virtual, virtualPlacement, presetBrowseForPlacement, virtualLocked, settingsOnly, cancelled,
        calibratePending, buildAfterCapture;
    HologramPresets.Preset virtualPreset;
    HologramStructureIndex structureIndex;
    // Private files remain on the client. Only one temporary, owner/session-bound draft is staged here.
    HologramPresets.Preset libraryPrivatePreset;
    String libraryLocalId = "", libraryReplaceLocalId = "";
    String libraryQuickToken = "";
    boolean libraryQuickPending, libraryUiActive;
    long libraryQuickBootstrapTick = Long.MIN_VALUE;
    long blueprintClientTaskToken;
    long libraryQuickClientTaskToken;
    boolean libraryInitialSnapshot;
    final java.util.Set<String> virtualEdited = new java.util.HashSet<>();
    final Map<String, List<ItemStack>> virtualGroups = new HashMap<>();
    String status = "";
    NBTTagCompound channels = new NBTTagCompound();
    HologramCapture capture;
    boolean localPlanPending;
    long localPlanGeneration;
    String localPlanError = "";
    HologramDeferredPlanRefresh.Work localPlanTask;
    final HologramLocalPlanRefresh localEdits = new HologramLocalPlanRefresh();
    boolean cellDeltaProtocol;
    long snapshotResyncTick = Long.MIN_VALUE;
    long areaSelectionSequence;
    boolean replacementReady, replacementAuthorized;
    HologramCapture indexedCapture;
    IdentityHashMap<HologramCapture.Cell, Integer> cellIndices = new IdentityHashMap<>();
    HologramCapabilities.Result capabilities;
    HologramMaterials.Settings materialSettings;
    HologramMaterials.Context materialContext;
    final List<ItemStack> materialChoices = new ArrayList<>();
    final Map<Integer, ItemStack> pins = new HashMap<>();
    ItemStack editClipboard;
    int editClipboardFacing = -1;
    final List<HologramEditState> undoEdits = new ArrayList<>();
    final List<HologramEditState> redoEdits = new ArrayList<>();
    final Map<Integer, ItemStack> autoTargets = new HashMap<>();
    final Map<String, ItemStack> frozenAutoTargets = new HashMap<>();
    boolean allocationsFrozen;
    final java.util.Set<String> suppressedAuto = new java.util.HashSet<>();
    final Map<String, Integer> facingPins = new HashMap<>();
    final Map<Integer, Integer> planFacings = new HashMap<>();
    int hatchTier = 1;
    boolean hatchDowngrade, presetEnabled, presetSupported, presetAvailable, presetPaused;
    String presetReason = "请选择电压级生成整机仓室预设";
    final Map<String, ItemStack> coordinatePins = new HashMap<>();
    final Map<String, HologramCapture.Cell> manualPositions = new LinkedHashMap<>();
    final IdentityHashMap<IStructureElement<?>, Map<String, List<ItemStack>>> hatchCandidates = new IdentityHashMap<>();
    final HologramDeclarationCollector declarations = new HologramDeclarationCollector();
    final IdentityHashMap<IStructureElement<?>, List<IStructureElement<?>>> hatchParts = new IdentityHashMap<>();
    final IdentityHashMap<IStructureElement<?>, Map<gregtech.api.interfaces.IHatchElement<?>, Integer>> hatchRequirements = new IdentityHashMap<>();
    final List<Integer> work = new ArrayList<>();
    final List<ItemStack> recovered = new ArrayList<>();
    final List<Integer> ackIndices = new ArrayList<>(), ackSuccesses = new ArrayList<>();
    final HologramJobPublication jobPublication = new HologramJobPublication();
    long progressTick = Long.MIN_VALUE;
    String progressPhase = "";
    int deferAttempts, deferredIndex = -1;
    boolean open;
    long planRevision, uiSequence, batchSeq;
    HologramWorkspace.PresentationTransition presentationTransition;
    long configurationAcceptedSequence = -1;
    final HologramAeProtocol.State ae = new HologramAeProtocol.State();
    int ackIndex = -1, planMissing;
    String phase = "IDLE";
    boolean planRecoveryFits = true, ackSuccess;
    boolean capturePending, retryNoHatches;
    HologramVirtualStructureEditing.Task virtualStructureTask;
    int captureAttempts, retryFacing, retryMain, retryMode, retryScope, retryLayer, retrySelected;
    long captureRetryDue;
    NBTTagCompound retryChannels;
    final List<Block> expectedBlocks = new ArrayList<>();
    final List<Integer> expectedMeta = new ArrayList<>();
    final List<TileEntity> expectedTiles = new ArrayList<>();
    final List<Integer> expectedFacings = new ArrayList<>();
    final List<String> planStatus = new ArrayList<>();
    final List<ItemStack> planTargets = new ArrayList<>();
    final HologramPlacementDiagnostics.Limits placementDiagnostics = new HologramPlacementDiagnostics.Limits();
    long actionTick = -1;
    long positionNudgeTick = Long.MIN_VALUE;
    long positionNudgeRefillTick = Long.MIN_VALUE;
    int positionNudgeCredits = 2;
    int actionCount;

    HologramSession(EntityPlayerMP p, int x, int y, int z) {
        this(p, x, y, z, true, 2);
    }

    HologramSession(EntityPlayerMP p, int x, int y, int z, boolean targetMode, int side) {
        this.side = side;
        player = p;
        libraryWorld = p.worldObj;
        this.x = x;
        this.y = y;
        this.z = z;
        dimension = p.dimension;
        slot = p.inventory.currentItem;
        tool = p.getHeldItem() == null ? null
            : p.getHeldItem()
                .getItem();
        target = targetMode ? p.worldObj.getTileEntity(x, y, z) : null;
        ItemStack heldStack = p.getHeldItem();
        boundTool = heldStack;
        materialSettings = HologramMaterials.read(heldStack);
        hatchDowngrade = heldStack != null && heldStack.hasTagCompound()
            && heldStack.getTagCompound()
                .getBoolean("gtitHologramDowngrade");
        // Copies retain identity. Exact creative NBT clones represent the same logical tool;
        // opening any target rotates the session nonce and invalidates old client actions.
        if (heldStack != null) {
            if (!heldStack.hasTagCompound()) heldStack.setTagCompound(new NBTTagCompound());
            if (heldStack.getTagCompound()
                .getString(HologramSessionKeys.TOOL_ID)
                .isEmpty())
                heldStack.getTagCompound()
                    .setString(
                        HologramSessionKeys.TOOL_ID,
                        UUID.randomUUID()
                            .toString());
            p.inventory.markDirty();
        }
        toolId = heldStack == null ? ""
            : heldStack.getTagCompound()
                .getString(HologramSessionKeys.TOOL_ID);
        openTick = p.worldObj.getTotalWorldTime();
        context = HologramStructureDiscovery.context(target);
        if (heldStack != null) {
            if (heldStack.hasTagCompound()) main = Math.max(
                1,
                heldStack.getTagCompound()
                    .getInteger("gtitHologramMain"));
            mainExplicit = heldStack.getTagCompound()
                .getBoolean("gtitHologramMainExplicit") || main != 1;
            ChannelDataAccessor.iterateChannelData(heldStack)
                .forEach(entry -> { if (entry.getValue() > 0) channels.setInteger(entry.getKey(), entry.getValue()); });
        }
        if (context instanceof MTEEnhancedMultiBlockBase) {
            machine = (MTEEnhancedMultiBlockBase<?>) context;
            facing = machine.getExtendedFacing()
                .ordinal();
        }
        constructable = HologramStructureDiscovery.constructable(target, context, side);
    }

    static void resetJobCounts(HologramSession s, boolean partial) {
        s.jobPublication.clear();
        s.placementDiagnostics.clear();
        s.jobVoucherConsumed = false;
        s.jobPartialPlacement = partial;
        s.jobMissingCells = s.jobProtectedCells = s.jobUnsupportedCells = s.jobSkippedCells = 0;
    }

    static void clearPending(HologramSession s) {
        s.ackIndices.clear();
        s.ackSuccesses.clear();
        s.work.clear();
        s.phase = "IDLE";
        s.ackIndex = -1;
        s.ackSuccess = false;
    }

    static void cancelCaptureRetry(HologramSession s) {

        HologramVirtualStructureEditing.cancel(s);
        if (s.capturePending && s.capture != null) s.capture.cancelPending();
        s.capturePending = false;
        s.captureRetryDue = 0;
        s.retryChannels = null;
        s.buildAfterCapture = false;
        s.calibratePending = false;

    }
}
