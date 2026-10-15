package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramRecovery;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Workstation for choosing targets and executing one immediate server-validated operation. */
public final class HologramScreen extends GuiScreen {

    private static final int DESIGN_W = HologramScreenLayout.WIDTH, DESIGN_H = HologramScreenLayout.HEIGHT;
    private final List<Control> controls = new ArrayList<>();
    private static final java.util.Map<String, UiMemory> UI_MEMORY = new java.util.LinkedHashMap<>();
    private static HologramUiTabMemory tabMemory;
    private final RenderItem itemRenderer = new RenderItem();
    private HologramState state;
    private GuiTextField mainField, channelNameField, channelValueField, optionValueField;
    private GuiTextField positionXField, positionYField, positionZField, presetNameField;
    private boolean positionDrawer, sourceDrawer;
    private boolean hatchDrawer, presetDrawer;
    private boolean roleDropdown;
    private int roleOffset;
    private int hatchOffset, hatchRole, hatchFilterTier = -1, presetTier = 1;
    private boolean presetDowngrade;
    private int selected = -1, layer, view, hovered = -1, drawerOffset, materialOffset;
    private static final int viewportX = HologramScreenLayout.VIEWPORT_X, viewportY = HologramScreenLayout.VIEWPORT_Y,
        viewportWidth = HologramScreenLayout.VIEWPORT_WIDTH, viewportHeight = HologramScreenLayout.VIEWPORT_HEIGHT;
    private static final int panelX = HologramScreenLayout.PANEL_X, panelY = HologramScreenLayout.PANEL_Y;
    private boolean shortcutsDrawer, libraryDrawer, blueprintTab, resetReview, resetCalibration;
    private int libraryOffset;
    private boolean publicLibrary, libraryAdminDrawer;
    private GuiTextField libraryUploadField, libraryDownloadField, libraryQuotaField;
    private long libraryConfigRevision = -1;
    private int captureBinding = -1;
    private final HologramKeyBindings keyBindings = new HologramKeyBindings();
    private final HologramEditQueue editQueue = new HologramEditQueue();
    private final HologramSelection selection = new HologramSelection();
    private final HologramGizmo gizmo = new HologramGizmo();
    private boolean aePreviewTransition, awaitingAePreview;
    private boolean networkDrawer;
    private int networkGroupIndex, networkGroupOffset;
    private boolean compactControls, boxSelecting, moveHandles = true, promptDrawer, versionsDrawer;
    private int boxStart = -1, promptScope, versionsOffset;
    private boolean retainNbt = true, retainCovers = true;
    private GuiTextField promptField;
    private GuiTextField networkHintField;
    private boolean nbtDrawer, nbtPlacement, resourceReview;
    private boolean deidentificationDrawer, deidentificationPlacement;
    private int deidentificationLevel = 1;
    private long offeredDeidentificationNonce = -1;
    private int nbtOffset;
    private final HologramNbtSelection nbtSelection = new HologramNbtSelection();
    private int dragX, dragY, originX, originY;
    private double yaw = Math.PI / 4, pitch = Math.PI / 6, zoom = 1;
    private final HologramPreviewCamera camera = new HologramPreviewCamera();
    private long cameraFrame;
    private float scale = 1;
    private boolean allLayers = true, dragging, moved, drawer, confirmation, inventory;
    private int inventorySlot = -1, sourceChoice = -1, sourceOffset;
    private boolean sourceTargets;
    private boolean inventoryDragging, awaitingScan;
    private String optionChannel = "", notice = "";
    private long sequence, pendingSequence = -1, confirmedRevision = -1, renderGeneration;
    private long queuedDispatchSequence = -1;
    private long presetSaveSequence = -1;
    private long seenPlacementReviewNonce;
    private final HologramPlacementReviewContext placementReviewContext = new HologramPlacementReviewContext();
    private String libraryResult = "", deletePresetId = "", deletePresetName = "";
    private HologramState annotationState, promptSummaryState;
    private List<HologramAnnotationMarkers.Marker> annotationMarkers = new ArrayList<>();
    private final List<HologramAnnotationMarkers.Marker> visibleMarkers = new ArrayList<>();
    private static final HologramAeReviewModel.Gate AE_REVIEW_GATE = new HologramAeReviewModel.Gate();
    private HologramAeReviewModel aeReview;
    private HologramAeReviewNavigation aeReviewNavigation;
    private int aeReviewTab, aeReviewOffset, aeReviewGroup = -1, aeReviewSelected = -1, aeDetailOffset;
    private String pinnedAnnotation = "";
    private int pinnedAnnotationOffset;
    private boolean gizmoCopy, boxAdditive;
    private boolean smokeCtrlSelection, smokeAltSelection;
    private int[] emptyVoxelPreview;
    private int summaryScope = -1;
    private String promptScopeSummary = "", promptSavedSummary = "";
    private boolean smokeSameTypeSelection;
    private long pendingSelectionSequence = -1;
    private boolean pendingBuildReview, captureRenderSmokeAction;
    private long pendingBuildReviewRevision = -1;
    private NBTTagCompound capturedRenderSmokeAction;
    private List<String> tooltip;
    private String lastConfirmationTitle = "", lastConfirmationAction = "";
    private String lastFooterScope = "", lastReviewedScope = "";
    private HologramProcessingFlow processingFlow;
    private boolean processingFullReview;
    private boolean overwriteMode, overwriteReview, nbtCellScope;
    private boolean processingPresetSaveReview;
    private String overwriteTarget = "", overwriteName = "", openedPurpose = "";
    private int deferredLibraryAction = -1;
    private boolean discardReview, discardConfirmed;
    private NBTTagCompound sourceSwitchProposal;
    private boolean initialEditHint = true, savedEditHint, structureVisited, basicConfigured;
    private boolean cellEditHint;
    private boolean nbtPathsView;
    private final List<String> nbtIgnoredPaths = new ArrayList<>();
    private GuiTextField nbtValueField, processingPresetField;
    private int nbtValueIndex = -1;
    private String selectedProcessingPreset = "";
    private long processingPresetLoadSequence = -1;
    private long basicConfigurationSequence = -1, basicConfigurationRevision = -1;
    private static final java.util.Map<String, boolean[]> GUIDANCE_MEMORY = new java.util.LinkedHashMap<>();
    private boolean keepLibraryOffset;
    private LibraryNavigation libraryNavigation;

    private static final class LibraryNavigation {

        final String sourceSession, sourceGeneration, targetId, purpose;
        final long sourceRevision;
        final int dimension, offset;
        final Object world;
        final boolean local;
        private boolean cancelled;

        LibraryNavigation(HologramState source, Object world, String targetId, boolean local, String purpose,
            int offset) {
            sourceSession = source.session;
            sourceGeneration = source.data.getString("generation");
            sourceRevision = source.data.getLong("planRevision");
            dimension = source.data.getInteger("dimension");
            this.world = world;
            this.targetId = targetId;
            this.local = local;
            this.purpose = purpose;
            this.offset = offset;
        }

        boolean source(HologramState snapshot, Object currentWorld, boolean publicView) {
            return !cancelled && world == currentWorld
                && local != publicView
                && sourceSession.equals(snapshot.session)
                && sourceGeneration.equals(snapshot.data.getString("generation"))
                && sourceRevision == snapshot.data.getLong("planRevision")
                && dimension == snapshot.data.getInteger("dimension");
        }

        boolean destination(HologramState snapshot) {
            return !cancelled && !sourceSession.equals(snapshot.session)
                && snapshot.session.equals(snapshot.data.getString("generation"))
                && dimension == snapshot.data.getInteger("dimension")
                && snapshot.data.getBoolean("virtual")
                && !snapshot.data.getBoolean("virtualPlacement")
                && snapshot.data.getBoolean("open")
                && purpose.equals(snapshot.data.getString("openPurpose"))
                && targetId.equals(snapshot.data.getString(local ? "libraryLocalId" : "selectedPresetId"));
        }

        void cancel() {
            cancelled = true;
        }
    }

    private void rememberLibraryNavigation(String targetId, String purpose) {
        cancelLibraryNavigation();
        libraryNavigation = targetId.isEmpty() ? null
            : new LibraryNavigation(state, mc.theWorld, targetId, !publicLibrary, purpose, libraryOffset);
    }

    private void cancelLibraryNavigation() {
        if (libraryNavigation != null) libraryNavigation.cancel();
        libraryNavigation = null;
    }

    boolean acceptsLibraryNavigation(HologramState next) {
        return libraryNavigation != null && libraryNavigation.source(state, mc.theWorld, publicLibrary)
            && libraryNavigation.destination(next);
    }

    HologramScreen(HologramState state) {
        this.state = state;
        networkDrawer = state.data.getBoolean("networkBindingRequested");
        retainNbt = !state.data.hasKey("blueprintRetainNbt") || state.data.getBoolean("blueprintRetainNbt");
        retainCovers = !state.data.hasKey("blueprintRetainCovers") || state.data.getBoolean("blueprintRetainCovers");
        libraryDrawer = state.data.getBoolean("presetBrowseForPlacement") || state.data.getBoolean("blueprintPreview");
        if (tabMemory == null) tabMemory = new HologramUiTabMemory(
            new java.io.File(
                net.minecraft.client.Minecraft.getMinecraft().mcDataDir,
                "config/gtmb-hologram-ui.properties"));
        blueprintTab = state.data.getBoolean("presetBrowseForPlacement") || tabMemory.blueprints(libraryDrawer);
        libraryDrawer = blueprintTab;
        selected = state.data.hasKey("selected") ? state.data.getInteger("selected") : -1;
        selection.load(state.data.getIntArray("selectedIndices"), selected, state.cells.size());
        UiMemory memory = UI_MEMORY.get(state.session);
        if (memory != null) {
            blueprintTab = memory.blueprints;
            libraryDrawer = memory.libraryDrawer;
            libraryOffset = memory.offset;
            seenPlacementReviewNonce = memory.reviewNonce;
            int[] restored = rememberedSelection(memory);
            if (restored != null) selection.load(restored, -1, state.cells.size());
        }
        selected = selection.first();
        layer = state.maxY;
        sequence = state.data.getLong("uiSequence");
        resetCamera();
        syncTargetSelectors();
        applyOpeningPurpose();
        receiveAeReview();
        offerPlacementDeidentification();
        acceptSourceSwitchProposal();
    }

    void updateState(HologramState next) {
        boolean libraryHandoff = acceptsLibraryNavigation(next);
        boolean waitingLibraryNavigation = libraryNavigation != null
            && libraryNavigation.source(state, mc.theWorld, publicLibrary)
            && libraryNavigation.source(next, mc.theWorld, publicLibrary);
        int navigationOffset = libraryHandoff ? libraryNavigation.offset : libraryOffset;
        if (!waitingLibraryNavigation) cancelLibraryNavigation();
        HologramState.Cell previousTarget = selectedCell();
        int previousPrimarySelection = selected;
        Integer previousDeidentificationChoice = HologramDeidentificationMemory.client()
            .choice(HologramDeidentificationMemory.source(state.data));
        String previousPreset = state.data.getString("selectedPresetId");
        boolean draftSourceChanged = !HologramDraftNameMemory.sameSource(state.data, next.data);
        if (draftSourceChanged) deidentificationDrawer = false;
        String savedOperation = next.data.getString("blueprintOperation");
        boolean canonicalSaveAcknowledged = next.data.getBoolean("virtual")
            && next.data.getBoolean("blueprintOperationSuccess")
            && ("savePreset".equals(savedOperation) || "saveBlueprintAs".equals(savedOperation)
                || "updatePreset".equals(savedOperation)
                || "exportDraft".equals(savedOperation))
            && (!state.data.getBoolean("blueprintOperationSuccess")
                || !HologramDraftNameMemory.canonicalName(state.data)
                    .equals(HologramDraftNameMemory.canonicalName(next.data)));
        boolean presetSaveAcknowledged = presetSaveSequence >= 0
            && next.data.getLong("uiSequence") >= presetSaveSequence
            && !next.data.getBoolean("blueprintSavePending");
        if (!state.session.equals(next.session)) {
            offeredDeidentificationNonce = -1;
            rememberUi();
            UiMemory nextMemory = UI_MEMORY.get(next.session);
            blueprintTab = nextMemory == null
                ? next.data.getBoolean("presetBrowseForPlacement")
                    || tabMemory.blueprints(next.data.getBoolean("blueprintPreview"))
                : nextMemory.blueprints;
            libraryDrawer = nextMemory == null ? blueprintTab : nextMemory.libraryDrawer;
            libraryOffset = libraryHandoff ? navigationOffset
                : keepLibraryOffset ? libraryOffset : nextMemory == null ? 0 : nextMemory.offset;
            seenPlacementReviewNonce = nextMemory == null ? 0 : nextMemory.reviewNonce;
            pinnedAnnotation = "";
            gizmo.cancel();
            selection.load(
                next.data.getIntArray("selectedIndices"),
                next.data.hasKey("selected") ? next.data.getInteger("selected") : -1,
                next.cells.size());
            pendingSelectionSequence = -1;
            pendingBuildReview = false;
            confirmedRevision = -1;
            presetSaveSequence = -1;
            presetSaveAcknowledged = false;
            pendingSequence = -1;
            queuedDispatchSequence = -1;
            cancelQueuedEdits("会话已变化，已取消等待中的编辑");
        }
        state = next;
        acceptSourceSwitchProposal();
        if (basicConfigurationSequence >= 0 && next.data.getLong("uiSequence") >= basicConfigurationSequence) {
            basicConfigurationSequence = -1;
            if (next.data.getLong("planRevision") != basicConfigurationRevision
                || next.data.getBoolean("presetActive")) {
                basicConfigured = true;
                rememberGuidance();
            }
        }
        if (processingPresetLoadSequence >= 0 && next.data.getLong("uiSequence") >= processingPresetLoadSequence) {
            processingPresetLoadSequence = -1;
            if (processingFlow != null) {
                if (next.data.getBoolean("processingPresetOperationSuccess"))
                    processingFlow.preset(next.data.getCompoundTag("processingPolicy"));
                else processingFlow.clearChoice();
            }
        }
        deidentificationLevel = Math.max(deidentificationLevel, HologramDeidentificationMemory.minimum(next.data));
        if (canonicalSaveAcknowledged && previousDeidentificationChoice != null) HologramDeidentificationMemory.client()
            .remember(
                HologramDeidentificationMemory.source(next.data),
                Math.max(previousDeidentificationChoice, HologramDeidentificationMemory.minimum(next.data)));
        networkDrawer = next.data.getBoolean("networkBindingRequested");
        if (areaImporting()) {
            editQueue.clear();
            queuedDispatchSequence = -1;
            dragging = boxSelecting = inventoryDragging = false;
            awaitingAePreview = awaitingScan = pendingBuildReview = false;
            confirmation = false;
            gizmo.cancel();
        }
        // Opening-purpose changes may persist UI memory immediately. Validate against the
        // new snapshot before any memory, selector or modal callback can read its cells.
        selection.retain(next.cells.size());
        selected = selection.first();
        applyOpeningPurpose();
        if (pendingSelectionSequence >= 0 && next.data.getLong("uiSequence") >= pendingSelectionSequence) {
            selection
                .load(next.data.getIntArray("selectedIndices"), next.data.getInteger("selected"), next.cells.size());
            pendingSelectionSequence = -1;
        }
        if (presetSaveAcknowledged) {
            if (!next.data.getBoolean("blueprintOperationSuccess")) HologramLibraryClient.INSTANCE.cancelDraftReview();
            presetSaveSequence = -1;
            libraryResult = next.data.getString("status");
            notice = libraryResult;
        }
        if (!libraryHandoff && !waitingLibraryNavigation
            && !keepLibraryOffset
            && (presetSaveAcknowledged || !previousPreset.equals(next.data.getString("selectedPresetId"))))
            revealSelectedPreset();
        keepLibraryOffset = false;
        if ((draftSourceChanged || canonicalSaveAcknowledged) && presetNameField != null) {
            presetNameField.setText(HologramDraftNameMemory.canonicalName(next.data));
            rememberUi();
        }
        if (pendingSequence >= 0 && next.data.getLong("uiSequence") >= pendingSequence) pendingSequence = -1;
        if (queuedDispatchSequence >= 0 && next.data.getLong("uiSequence") >= queuedDispatchSequence) {
            queuedDispatchSequence = -1;
            notice = "";
        }
        if (confirmation && confirmedRevision != revision()) confirmation = false;
        if (pendingBuildReview && pendingSequence < 0) {
            pendingBuildReview = false;
            if (HologramPlacementConfirmation.forceReview(next.data, pendingBuildReviewRevision)) {
                confirmedRevision = pendingBuildReviewRevision;
                materialOffset = 0;
                confirmation = true;
            }
        }
        if (next.data.getBoolean("blueprintAwaitingConfirmation")
            && !next.data.getBoolean("presetBrowseForPlacement")) {
            confirmedRevision = revision();
            materialOffset = 0;
            confirmation = true;
        }
        if (allLayers) layer = next.maxY;
        int previousSelection = previousPrimarySelection;
        selected = selection.first();
        if (selected != previousSelection) {
            roleDropdown = false;
            syncTargetSelectors();
        } else if (!sameTarget(previousTarget, selectedCell())) {
            if (hatchRole > 0) syncTargetSelectors();
            // Preserve an explicitly chosen all-types filter; scroll its matching target into view.
            HologramState.Cell target = selectedCell();
            if (target != null) {
                int targetRow = filteredHatches().indexOf(target.chosenChoice);
                if (targetRow >= 0) hatchOffset = targetRow;
                else if (hatchRole == 0) notice = "目标已更新；所选候选不在当前等级筛选中";
            }
        }
        if (mainField != null && !mainField.isFocused())
            mainField.setText(Integer.toString(next.data.getInteger("main")));
        if (awaitingScan && pendingSequence < 0 && !next.data.getBoolean("capturePending")) {
            awaitingScan = false;
            if (next.data.getBoolean("supported")) {
                confirmedRevision = revision();
                materialOffset = 0;
                confirmation = true;
            }
        }
        receiveAeReview();
        offerPlacementDeidentification();
        completeAeReviewNavigation();
        drainQueuedEdits();
        if (awaitingAePreview && pendingSequence < 0) {
            awaitingAePreview = false;
            NBTTagCompound preview = next.data.getCompoundTag("aePreview");
            if (!preview.getString("token")
                .isEmpty() && next.session.equals(preview.getString("session"))) {
                aePreviewTransition = true;
                mc.displayGuiScreen(new HologramAeContainerScreen(this, preview));
            } else notice = next.data.getString("status");
        }
    }

    private static boolean sameTarget(HologramState.Cell before, HologramState.Cell after) {
        if (before == null || after == null) return before == after;
        if (before.targetFacing != after.targetFacing || before.wantMeta != after.wantMeta
            || !before.wantId.equals(after.wantId)) return false;
        if (before.targetStack == null || after.targetStack == null) return before.targetStack == after.targetStack;
        return before.targetStack.isItemEqual(after.targetStack)
            && ItemStack.areItemStackTagsEqual(before.targetStack, after.targetStack);
    }

    @Override
    public void initGui() {
        if (presetNameField != null) rememberUi();
        if (HologramLibraryClient.dedicated()) HologramLibraryClient.INSTANCE.open();
        Keyboard.enableRepeatEvents(true);
        keyBindings.load(new java.io.File(mc.mcDataDir, "config/gtmb-hologram-keys.properties"));
        HologramScreenLayout layout = HologramScreenLayout.fit(width, height);
        scale = layout.scale;
        originX = layout.originX;
        originY = layout.originY;
        mainField = new GuiTextField(fontRendererObj, 271, 213, 55, 16);
        mainField.setMaxStringLength(10);
        mainField.setText(Integer.toString(state.data.getInteger("main")));
        channelNameField = new GuiTextField(fontRendererObj, 165, 190, 150, 16);
        channelNameField.setMaxStringLength(48);
        channelValueField = new GuiTextField(fontRendererObj, 321, 190, 75, 16);
        channelValueField.setMaxStringLength(10);
        channelValueField.setText("1");
        optionValueField = new GuiTextField(fontRendererObj, 168, 232, 137, 16);
        optionValueField.setMaxStringLength(10);
        positionXField = positionField(166);
        positionYField = positionField(246);
        positionZField = positionField(326);
        presetNameField = new GuiTextField(fontRendererObj, 457, 78, HologramScreenLayout.COMPACT_PANEL_WIDTH - 10, 16);
        presetNameField.setMaxStringLength(48);
        if (state.data.getBoolean("virtual")) presetNameField.setText(state.data.getString("virtualPresetName"));
        UiMemory memory = UI_MEMORY.get(state.session);
        if (memory != null) presetNameField.setText(memory.draftName.restore(state.data));
        promptField = new GuiTextField(fontRendererObj, 62, 129, 354, 18);
        promptField.setMaxStringLength(1024);
        promptField.setText(state.data.getString("blueprintPrompt"));
        networkHintField = new GuiTextField(fontRendererObj, 50, 220, 325, 18);
        networkHintField.setMaxStringLength(256);
        loadNetworkHintField();
        if (state.data.getBoolean("blueprintAwaitingConfirmation")
            && !state.data.getBoolean("presetBrowseForPlacement")) {
            confirmation = true;
            confirmedRevision = revision();
        }
    }

    private GuiTextField positionField(int x) {
        GuiTextField field = new GuiTextField(fontRendererObj, x, 116, 70, 18);
        field.setMaxStringLength(11);
        field.setText("0");
        return field;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        cancelLibraryNavigation();
        rememberUi();
        if (aeReview != null && aeReview.review) activateAeReview(182);
        if (aePreviewTransition) {
            aePreviewTransition = false;
            Keyboard.enableRepeatEvents(false);
            return;
        }
        if (state.data.getBoolean("presetBrowseForPlacement")) send("dismissPresetBrowse", null, false);
        editQueue.clear();
        queuedDispatchSequence = -1;
        Keyboard.enableRepeatEvents(false);
    }

    private void rememberUi() {
        if (state.session.isEmpty()) return;
        // An old screen closes after a new snapshot's screen is constructed; its tab must not undo the new intent.
        if (!captureRenderSmokeAction
            && (HologramClient.state == null || HologramClient.state.session.equals(state.session)))
            tabMemory.remember(blueprintTab);
        UI_MEMORY.put(
            state.session,
            new UiMemory(
                blueprintTab,
                libraryDrawer,
                revision(),
                selection.indices(),
                libraryOffset,
                state.data,
                presetNameField == null ? state.data.getString("virtualPresetName") : presetNameField.getText(),
                seenPlacementReviewNonce,
                selectedPositions()));
        while (UI_MEMORY.size() > 32) UI_MEMORY.remove(
            UI_MEMORY.keySet()
                .iterator()
                .next());
    }

    /** An explicit placement enters the world without a screen; remember its Blueprint editor context only. */
    static void rememberBlueprintPlacement(HologramState snapshot) {
        if (tabMemory == null) tabMemory = new HologramUiTabMemory(
            new java.io.File(
                net.minecraft.client.Minecraft.getMinecraft().mcDataDir,
                "config/gtmb-hologram-ui.properties"));
        tabMemory.remember(true);
        UiMemory memory = UI_MEMORY.get(snapshot.session);
        HologramSelection selected = new HologramSelection();
        selected.load(
            snapshot.data.getIntArray("selectedIndices"),
            snapshot.data.hasKey("selected") ? snapshot.data.getInteger("selected") : -1,
            snapshot.cells.size());
        int[] indices = selected.indices(), positions = new int[indices.length * 3];
        for (int i = 0; i < indices.length; i++) {
            HologramState.Cell cell = snapshot.cells.get(indices[i]);
            positions[i * 3] = cell.x;
            positions[i * 3 + 1] = cell.y;
            positions[i * 3 + 2] = cell.z;
        }
        UI_MEMORY.put(
            snapshot.session,
            new UiMemory(
                true,
                false,
                memory == null ? snapshot.data.getLong("planRevision") : memory.revision,
                memory == null ? indices : memory.indices,
                memory == null ? 0 : memory.offset,
                snapshot.data,
                memory == null ? snapshot.data.getString("virtualPresetName") : memory.draftName.restore(snapshot.data),
                memory == null ? 0 : memory.reviewNonce,
                memory == null ? positions : memory.positions));
        while (UI_MEMORY.size() > 32) UI_MEMORY.remove(
            UI_MEMORY.keySet()
                .iterator()
                .next());
    }

    private void closeRoot() {
        if (!captureRenderSmokeAction) HologramLibraryClient.INSTANCE.cancelQuickPlacement();
        if (!captureRenderSmokeAction) HologramLibraryClient.INSTANCE.cancel();
        rememberUi();
        cancelAeReviewNavigation();
        if (!captureRenderSmokeAction) HologramClient.dismissRootView(state.session, sequence + 1);
        send("close", null, false);
        mc.displayGuiScreen(null);
    }

    private boolean areaImporting() {
        return state.data.getBoolean("areaImporting");
    }

    private void drawImportBusy(int mx, int my) {
        text("正在导入框选区域", 142, 13, 360, 0xf5e4b9);
        drawRect(30, 62, 650, 282, 0xff172f36);
        text("正在导入区域 · 请稍候", 50, 78, 580, 0xffe59b);
        String stage = "declarations".equals(state.data.getString("areaImportStage")) ? "正在解析结构与配置…" : "正在读取方块与配置…";
        text(stage, 50, 105, 580, 0x8bdddf);
        text("导入完成前，暂不能编辑、保存或放置蓝图。", 50, 123, 580, 0xe5c28d);
        text("取消后保留导入前的蓝图。", 50, 141, 580, 0xa9d6cf);
        int progress = Math.max(0, Math.min(100, state.data.getInteger("areaImportProgress")));
        drawRect(50, 165, 630, 178, 0xff0c1e28);
        drawRect(50, 165, 50 + progress * 580 / 100, 178, 0xff608c91);
        text(progress + "%", 50, 187, 580, 0xf2dca5);
        text(state.data.getString("status"), 50, 204, 580, 0xe5c28d);
        button(193, 218, 239, 244, 20, "取消导入并返回世界 · Esc / E", true, mx, my);
    }

    private int[] selectedPositions() {
        return selection.positions(state);
    }

    private int[] rememberedSelection(UiMemory memory) {
        if (memory.revision == revision()) return memory.indices;
        java.util.Map<String, Integer> coordinates = new java.util.HashMap<>();
        for (HologramState.Cell cell : state.cells) coordinates.put(cell.x + "," + cell.y + "," + cell.z, cell.index);
        int[] indices = new int[memory.positions.length / 3];
        for (int i = 0; i < indices.length; i++) {
            Integer index = coordinates
                .get(memory.positions[i * 3] + "," + memory.positions[i * 3 + 1] + "," + memory.positions[i * 3 + 2]);
            if (index == null) return null;
            indices[i] = index;
        }
        return indices;
    }

    private void acceptSourceSwitchProposal() {
        NBTTagCompound proposal = state.data.getCompoundTag("discardTargetAction");
        String operation = proposal.getString("op");
        if (state.data.getBoolean("discardConfirmationRequired")
            && ("openController".equals(operation) || "importArea".equals(operation))) {
            sourceSwitchProposal = (NBTTagCompound) proposal.copy();
            discardReview = true;
            deferredLibraryAction = -1;
        } else if (sourceSwitchProposal != null) {
            sourceSwitchProposal = null;
            discardReview = false;
        }
    }

    private void applyOpeningPurpose() {
        String purpose = state.session + ":" + state.data.getString("openPurpose");
        boolean newPurpose = !purpose.equals(openedPurpose);
        if (newPurpose) {
            openedPurpose = purpose;
            initialEditHint = state.data.getBoolean("virtual") && !state.data.getBoolean("blueprintSaved")
                && !state.data.getBoolean("blueprintProcessingComplete");
            savedEditHint = "blueprintEdit".equals(state.data.getString("openPurpose"))
                && state.data.getBoolean("blueprintSaved");
            structureVisited = false;
            basicConfigured = state.data.getBoolean("presetActive");
            boolean[] memory = GUIDANCE_MEMORY.get(purpose);
            if (memory != null) {
                initialEditHint = memory[0];
                savedEditHint = memory[1];
                structureVisited = memory[2];
                basicConfigured = memory[3];
            }
            if ("blueprint".equals(state.data.getString("workspaceTab")) && !state.data.getBoolean("virtualPlacement"))
                blueprintTab = libraryDrawer = true;
            if ("structure".equals(state.data.getString("workspaceTab")) || state.data.getString("workspaceTab")
                .isEmpty() && state.data.getBoolean("virtual")
                && state.data.getString("openPurpose")
                    .isEmpty()
                && !state.data.getBoolean("blueprintSaved")
                && !state.data.getBoolean("virtualPlacement")
                && !state.data.getBoolean("presetBrowseForPlacement")) {
                blueprintTab = libraryDrawer = false;
            }
        }
        // The server identifies actual controller imports; this overrides both per-session and disk tab memory.
        if (newPurpose && "controllerStructure".equals(state.data.getString("openPurpose"))) {
            blueprintTab = false;
            libraryDrawer = false;
            rememberUi();
        } else if (newPurpose && "blueprintBrowse".equals(state.data.getString("openPurpose"))) {
            blueprintTab = true;
            libraryDrawer = true;
            publicLibrary = state.data.getString("selectedPresetId")
                .startsWith("public_");
            rememberUi();
        } else if (newPurpose && ("blueprintEdit".equals(state.data.getString("openPurpose"))
            || "blueprintPlacement".equals(state.data.getString("openPurpose")))) {
                blueprintTab = true;
                libraryDrawer = "blueprintEdit".equals(state.data.getString("openPurpose"));
                rememberUi();
            }
        placementReviewContext.accept(
            state.session,
            state.data.getLong("placementReviewNonce"),
            state.data.getBoolean("open"),
            state.data.getBoolean("virtualPlacement") && state.data.getBoolean("virtualLocked"),
            state.data.getBoolean("blueprintAwaitingConfirmation"));
        if (!"placementReview".equals(state.data.getString("openPurpose"))) return;
        blueprintTab = true;
        long nonce = state.data.getLong("placementReviewNonce");
        if (nonce <= seenPlacementReviewNonce) return;
        seenPlacementReviewNonce = nonce;
        rememberUi();
    }

    boolean retainsPlacementReview(HologramState next) {
        return placementReviewContext.retain(
            next.session,
            next.data.getLong("placementReviewNonce"),
            next.data.getBoolean("virtualPlacement") && next.data.getBoolean("virtualLocked"),
            next.data.getBoolean("moving"));
    }

    private static final class UiMemory {

        final boolean blueprints, libraryDrawer;
        final long revision;
        final int[] indices;
        final int offset;
        final String name;
        final HologramDraftNameMemory draftName;
        final long reviewNonce;
        final int[] positions;

        UiMemory(boolean blueprints, boolean libraryDrawer, long revision, int[] indices, int offset,
            NBTTagCompound source, String name, long reviewNonce, int[] positions) {
            this.blueprints = blueprints;
            this.libraryDrawer = libraryDrawer;
            this.revision = revision;
            this.indices = indices;
            this.offset = offset;
            this.name = name;
            this.draftName = new HologramDraftNameMemory(source, name);
            this.reviewNonce = reviewNonce;
            this.positions = positions;
        }
    }

    @Override
    public void updateScreen() {
        if (presetSaveSequence >= 0 && !HologramLibraryClient.INSTANCE.status()
            .isEmpty()) libraryResult = notice = HologramLibraryClient.INSTANCE.status();
        if (libraryAdminDrawer) {
            NBTTagCompound libraryConfig = HologramLibraryClient.INSTANCE.config();
            if (!libraryConfig.getBoolean("admin")) libraryAdminDrawer = false;
            else {
                if (libraryConfigRevision != libraryConfig.getLong("configRevision")) {
                    libraryUploadField.setText(String.valueOf(libraryConfig.getInteger("uploadKiB")));
                    libraryDownloadField.setText(String.valueOf(libraryConfig.getInteger("downloadKiB")));
                    libraryQuotaField.setText(String.valueOf(libraryConfig.getInteger("maxUploads")));
                    libraryConfigRevision = libraryConfig.getLong("configRevision");
                }
                libraryUploadField.updateCursorCounter();
                libraryDownloadField.updateCursorCounter();
                libraryQuotaField.updateCursorCounter();
            }
        }
        if (mainField != null) mainField.updateCursorCounter();
        if (presetNameField != null) presetNameField.updateCursorCounter();
        if (promptField != null) promptField.updateCursorCounter();
        if (nbtValueField != null && nbtDrawer) nbtValueField.updateCursorCounter();
        if (processingPresetField != null && (processingFlow != null || processingPresetSaveReview))
            processingPresetField.updateCursorCounter();
        if (networkHintField != null && networkDrawer) networkHintField.updateCursorCounter();
        if (channelNameField != null) channelNameField.updateCursorCounter();
        if (channelValueField != null) channelValueField.updateCursorCounter();
        if (optionValueField != null) optionValueField.updateCursorCounter();
        if (positionXField != null) {
            positionXField.updateCursorCounter();
            positionYField.updateCursorCounter();
            positionZField.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float ticks) {
        movePreviewCamera();
        drawDefaultBackground();
        int mx = localX(mouseX), my = localY(mouseY);
        controls.clear();
        tooltip = null;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        GL11.glTranslatef(originX, originY, 0);
        GL11.glScalef(scale, scale, 1);
        try {
            skin("cloth", 0, 0, DESIGN_W, DESIGN_H);
            drawRect(6, 29, DESIGN_W - 6, DESIGN_H - 8, 0xed142a32);
            skin("panel", 6, 6, DESIGN_W - 12, 22);
            if (areaImporting()) {
                drawImportBusy(mx, my);
                renderGeneration++;
                return;
            }
            text(
                placementReviewActive() ? "蓝图放置预检 · " + state.data.getString("virtualPresetName")
                    : state.data.getBoolean("virtual")
                        ? (libraryDrawer ? "" : "结构调整 · ") + state.data.getString("virtualPresetName")
                        : state.data.getString("title"),
                142,
                13,
                HologramLibraryClient.dedicated() && libraryDrawer
                    && HologramLibraryClient.INSTANCE.config()
                        .getBoolean("admin") ? 122 : 197,
                0xf5e4b9);
            if (!libraryDrawer) button(
                60,
                351,
                9,
                117,
                17,
                state.data.getBoolean("virtual") ? "结构等级 / 信道" : "等级 / 信道设置",
                !state.data.getBoolean("virtual") || !state.data.getString("activeStructure")
                    .isEmpty(),
                mx,
                my);
            if (!confirmation) drawPreview(mx, my);
            GL11.glDepthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (!libraryDrawer) {
                GL11.glPushMatrix();
                GL11.glTranslatef(HologramScreenLayout.COMPACT_PANEL_X, 0, 0);
                GL11.glScalef(HologramScreenLayout.CONTROL_WIDTH_SCALE, 1, 1);
                GL11.glTranslatef(-panelX, 0, 0);
                compactControls = true;
                try {
                    drawConfig(compactMouseX(mx), my);
                } finally {
                    compactControls = false;
                    GL11.glPopMatrix();
                }
                drawFooter(mx, my);
                HologramState.Cell activeCell = selectedCell();
                String activeStructureId = selectedStructureId();
                if (activeCell != null && !activeStructureId.isEmpty()) {
                    button(163, 452, 281, 107, 17, activeCell.sourceMissing ? "来源缺失" : "结构编辑", editable(), mx, my);
                    button(
                        164,
                        563,
                        281,
                        109,
                        17,
                        structureEnabled(activeStructureId) ? "施工 ✓" : "施工 ×",
                        editable(),
                        mx,
                        my);
                }
                if (!state.data.getString("activeStructure")
                    .isEmpty()) button(165, 452, 300, 220, 13, "返回区域总览", editable(), mx, my);
            }
            if (!libraryDrawer) {
                button(108, 12, 34, 40, 17, "撤销", editable() && state.data.getBoolean("editCanUndo"), mx, my);
                button(109, 54, 34, 40, 17, "重做", editable() && state.data.getBoolean("editCanRedo"), mx, my);
                button(110, 96, 34, 60, 17, "快捷键", true, mx, my);
            }
            if (!libraryDrawer && networkGroups().tagCount() > 0) button(
                196,
                388,
                34,
                86,
                17,
                selectedNetworkGroup().isEmpty() ? "量子绑定 · " + networkGroups().tagCount()
                    : "网络组 " + selectedNetworkGroup(),
                editable(),
                mx,
                my);
            button(178, 478, 9, 92, 17, "框选区域", editable(), mx, my);
            if (!libraryDrawer) button(179, 578, 9, 94, 17, "AE 容器标记", canOpenAePreview(), mx, my);
            if (drawer || inventory || positionDrawer || sourceDrawer || !optionChannel.isEmpty()) {
                controls.removeIf(control -> control.x >= 157);
                tooltip = null;
                GL11.glDepthMask(true);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            if (drawer) {
                drawChannels(mx, my);
            }
            if (inventory) drawInventory(mx, my);
            if (positionDrawer) drawPosition(mx, my);
            if (sourceDrawer) drawSources(mx, my);
            if (!optionChannel.isEmpty()) {
                controls.clear();
                drawOptions(mx, my);
            }
            if (hatchDrawer || presetDrawer) {
                controls.clear();
                tooltip = null;
                GL11.glDepthMask(true);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                if (hatchDrawer) drawHatches(mx, my);
                else drawPreset(mx, my);
            }
            if (shortcutsDrawer) {
                controls.clear();
                tooltip = null;
                drawShortcuts(mx, my);
            }
            if (libraryDrawer) {
                tooltip = null;
                drawLibrary(mx, my);
            }
            drawTabs(mx, my);
            if (promptDrawer || versionsDrawer) {
                controls.clear();
                tooltip = null;
                if (promptDrawer) drawPromptEditor(mx, my);
                else drawVersions(mx, my);
            }
            if (resetReview) {
                controls.clear();
                tooltip = null;
                drawResetReview(mx, my);
            }
            if (!deletePresetId.isEmpty()) {
                controls.clear();
                tooltip = null;
                drawDeleteReview(mx, my);
            }
            if (libraryAdminDrawer) {
                controls.clear();
                tooltip = null;
                drawLibraryAdmin(mx, my);
            }
            if (confirmation) {
                controls.clear();
                tooltip = null;
                GL11.glDepthMask(true);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                drawConfirmation(mx, my);
            }
            if (roleDropdown && !confirmation) {
                controls.clear();
                tooltip = null;
                drawRoleDropdown(mx, my);
            }
            if (nbtDrawer) {
                controls.clear();
                tooltip = null;
                drawNbtEditor(mx, my);
            }
            if (!confirmation) {
                String status = pendingSequence >= 0 ? "等待服务端校验…" : completionSummary();
                int statusX = libraryDrawer ? 12 : 35;
                int statusWidth = libraryDrawer ? DESIGN_W - 24 : viewportWidth - 12;
                drawRect(statusX - 3, 318, statusX + statusWidth + 3, 333, 0xff142a32);
                text(
                    notice.isEmpty() ? status : notice,
                    statusX,
                    321,
                    statusWidth,
                    notice.isEmpty() ? 0xffbd91 : 0xff9292);
                if (mx >= statusX && mx < statusX + statusWidth && my >= 318 && my < 333) {
                    tooltip = new ArrayList<>(
                        fontRendererObj.listFormattedStringToWidth(notice.isEmpty() ? status : notice, 390));
                    if (!status.equals(state.data.getString("status")))
                        tooltip.addAll(fontRendererObj.listFormattedStringToWidth(state.data.getString("status"), 390));
                    tooltip.add(
                        "材料缺口 " + state.data.getInteger("missing") + " · 保护 " + state.data.getInteger("protected"));
                }
            }
            drawEmptyVoxelPreview(mx, my);
            if (annotationInteractive()) drawAnnotations(mx, my);
            String guidance = HologramGuidance.next(state, selected, libraryDrawer, pendingSequence >= 0);
            lastFooterScope = constructionScopeLabel(false);
            String footerGuide = !libraryDrawer && !confirmation && !state.cells.isEmpty() ? lastFooterScope + " · "
                + (state.data.getInteger("job") == 1 || state.data.getInteger("job") == 2 ? guidance
                    : constructionScopeHint())
                : guidance;
            if (!libraryDrawer && !confirmation
                && state.data.getBoolean("virtual")
                && !state.data.getBoolean("virtualPlacement"))
                footerGuide = initialEditHint ? "可微调蓝图结构；点击、选格或修改后前往蓝图设置" : "结构调整完成后，前往蓝图设置处理并保存";
            text(footerGuide, 35, 306, viewportWidth - 4, 0x8bdddf);
            if (mx >= 35 && mx < viewportX + viewportWidth && my >= 304 && my < 317) {
                tooltip = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(footerGuide, 390));
                tooltip.addAll(fontRendererObj.listFormattedStringToWidth(guidance, 390));
            }
            if (aeReview != null) drawAeReview(mx, my);
            if (aeReviewNavigation != null) drawAeReviewNavigation(mx, my);
            if (networkDrawer) drawNetworkBindings(mx, my);
            if (deidentificationDrawer) {
                controls.clear();
                tooltip = null;
                drawDeidentification(mx, my);
            }
            if (processingFlow != null) {
                controls.clear();
                tooltip = null;
                drawProcessingFlow(mx, my);
            }
            if (discardReview || overwriteReview || processingPresetSaveReview) {
                controls.clear();
                tooltip = null;
                drawBlueprintSwitchReview(mx, my);
            }
            if (tooltip != null) drawHoveringText(tooltip, mx, my, fontRendererObj);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
        renderGeneration++;
    }

    private void drawPreview(int mx, int my) {
        HologramRenderer.selection = selection;
        HologramRenderer.showEmptyCells = inventory || positionDrawer || airSelectionHeld();
        HologramRenderer.projectionLayer = allLayers ? Integer.MAX_VALUE : layer;
        HologramRenderer.selectedStructureId = selectedStructureId();
        drawRect(viewportX, viewportY, viewportX + viewportWidth, viewportY + viewportHeight, 0xff0c1e28);
        int factor = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight)
            .getScaleFactor();
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            Math.round((originX + viewportX * scale) * factor),
            mc.displayHeight - Math.round((originY + (viewportY + viewportHeight) * scale) * factor),
            Math.round(viewportWidth * scale * factor),
            Math.round(viewportHeight * scale * factor));
        try {
            hovered = HologramRenderer.preview(
                state,
                viewportX,
                viewportY,
                viewportWidth,
                viewportHeight,
                yaw,
                pitch,
                zoom,
                camera.x,
                camera.z,
                allLayers ? Integer.MAX_VALUE : layer,
                view,
                selected,
                mx,
                my,
                -1);
        } finally {
            GL11.glPopAttrib();
        }
        button(10, 38, 55, 44, 17, new String[] { "目标", "现场", "差分" }[view], true, mx, my);
        button(11, 84, 55, 50, 17, allLayers ? "全部层" : "层 " + layer, true, mx, my);
        button(12, 136, 55, 20, 17, "−", layer > state.minY, mx, my);
        button(13, 158, 55, 22, 17, "+", layer < state.maxY, mx, my);
        if (selection.size() > 0) text("选中 " + selection.size() + " 格", 186, 61, 100, 0xffe59b);
        if (!libraryDrawer) {
            button(14, 38, 282, 66, 17, "复位视角", true, mx, my);
            button(15, 106, 282, 74, 17, HologramClient.worldPreview ? "世界投影 ✓" : "世界投影 ×", true, mx, my);
        }
        button(
            134,
            184,
            282,
            120,
            17,
            state.data.getBoolean("structureBuilt") ? "校准为当前" : "重置为默认",
            editable() && previewInteractive() && state.data.getBoolean("target") && state.data.getBoolean("supported"),
            mx,
            my);
        if (moveHandles && canMoveSelection() && !libraryDrawer && previewInteractive()) drawGizmo(mx, my);
        if (hovered >= 0 && hovered < state.cells.size()) {
            HologramState.Cell cell = state.cells.get(hovered);
            if (cell.missingBlock || cell.excluded || cell.sourceMissing) {
                tooltip = new ArrayList<>();
                if (cell.missingBlock) tooltip.add("缺失方块：" + cell.wantId + "（点击查看来源版本）");
                if (cell.sourceMissing) {
                    tooltip.addAll(fontRendererObj.listFormattedStringToWidth("来源结构缺失：" + cell.sourceWarning, 260));
                    if (!cell.sourceClass.isEmpty())
                        tooltip.addAll(fontRendererObj.listFormattedStringToWidth(cell.sourceClass, 260));
                }
                if (cell.excluded) tooltip.add("已排除施工");
            }
        }
        String hint = HologramRenderer.hintAt(mx, my);
        if (!hint.isEmpty()) {
            if (tooltip == null) tooltip = new ArrayList<>();
            tooltip.addAll(fontRendererObj.listFormattedStringToWidth("提示区 " + hint, 260));
        }
        String structure = HologramRenderer.pickStructure(mx, my);
        if (!structure.isEmpty()) {
            NBTTagList labels = state.data.getTagList("structures", 10);
            for (int i = 0; i < labels.tagCount(); i++) {
                NBTTagCompound row = labels.getCompoundTagAt(i);
                if (!structure.equals(row.getString("id"))) continue;
                if (tooltip == null) tooltip = new ArrayList<>();
                tooltip.add(row.getString("name") + " · 点击边缘整选");
                if (row.getBoolean("sourceMissing")) {
                    tooltip.addAll(
                        fontRendererObj.listFormattedStringToWidth("来源结构缺失：" + row.getString("sourceWarning"), 260));
                    tooltip.addAll(fontRendererObj.listFormattedStringToWidth(row.getString("sourceClass"), 260));
                }
                break;
            }
        }
    }

    private boolean annotationInteractive() {
        return aeReviewNavigation == null && aeReview == null
            && !deidentificationDrawer
            && !inventory
            && (previewInteractive() || libraryDrawer && !confirmation
                && !resetReview
                && deletePresetId.isEmpty()
                && !nbtDrawer
                && !promptDrawer
                && !versionsDrawer
                && !shortcutsDrawer);
    }

    private boolean canMoveSelection() {
        return state.data.getBoolean("geometryEditable") && state.data.getBoolean("virtual")
            && state.data.getBoolean("supported")
            && editable()
            && selection.movable(state);
    }

    private int[] emptyVoxelAt(int mx, int my) {
        if (!inViewport(mx, my) || previewBlockedAt(mx, my)) return null;
        for (Control control : controls) if (control.contains(mx, my)) return null;
        HologramState.Cell cell = selectedCell();
        int planeY = !allLayers ? state.data.getInteger("y") + layer
            : cell == null ? state.data.getInteger("y") + (state.minY + state.maxY) / 2 : cell.y;
        int[] voxel = HologramRenderer.pickEmptyVoxel(state, mx, my, planeY);
        if (voxel == null) return null;
        String structure = selectedStructureId();
        if (!structure.isEmpty()) {
            int[] bounds = null;
            for (HologramState.Cell member : state.cells) if (member.belongsToStructure(structure)) {
                if (bounds == null) bounds = new int[] { member.x, member.y, member.z, member.x, member.y, member.z };
                else {
                    bounds[0] = Math.min(bounds[0], member.x);
                    bounds[1] = Math.min(bounds[1], member.y);
                    bounds[2] = Math.min(bounds[2], member.z);
                    bounds[3] = Math.max(bounds[3], member.x);
                    bounds[4] = Math.max(bounds[4], member.y);
                    bounds[5] = Math.max(bounds[5], member.z);
                }
            }
            if (bounds != null && !HologramSelection.inside(
                voxel[0],
                voxel[1],
                voxel[2],
                bounds[0] - 1,
                bounds[1] - 1,
                bounds[2] - 1,
                bounds[3] + 1,
                bounds[4] + 1,
                bounds[5] + 1)) return null;
        }
        return voxel;
    }

    private void drawEmptyVoxelPreview(int mx, int my) {
        emptyVoxelPreview = airSelectionHeld() && previewInteractive() && !inventory ? emptyVoxelAt(mx, my) : null;
        if (emptyVoxelPreview == null) return;
        int[] p = emptyVoxelPreview;
        int dx = p[0] - state.data.getInteger("x"), dy = p[1] - state.data.getInteger("y"),
            dz = p[2] - state.data.getInteger("z");
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(2);
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        t.setColorOpaque_I(0x9eedc1);
        for (int axis = 0; axis < 3; axis++) for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            double[] from = { dx + a - .5, dy + b - .5, dz - .5 };
            if (axis == 0) from = new double[] { dx - .5, dy + a - .5, dz + b - .5 };
            if (axis == 1) from = new double[] { dx + a - .5, dy - .5, dz + b - .5 };
            double[] to = from.clone();
            to[axis] += 1;
            double[] start = HologramRenderer.project(from[0], from[1], from[2]);
            double[] end = HologramRenderer.project(to[0], to[1], to[2]);
            t.addVertex(start[0], start[1], 8);
            t.addVertex(end[0], end[1], 8);
        }
        t.draw();
        GL11.glPopAttrib();
        text(
            "空气 " + p[0] + "," + p[1] + "," + p[2] + " · 偏移 " + dx + "," + dy + "," + dz + " · Alt+点击选中",
            viewportX + 8,
            pinnedAnnotation.isEmpty() ? 263 : 80,
            viewportWidth - 16,
            0x9eedc1);
    }

    private boolean selectEmptyVoxel(int mx, int my) {
        int[] voxel = emptyVoxelAt(mx, my);
        if (voxel == null) {
            notice = "此光标位置没有可编辑空气；调整视角或选择工作层";
            return true;
        }
        for (HologramState.Cell cell : state.cells)
            if (cell.x == voxel[0] && cell.y == voxel[1] && cell.z == voxel[2]) {
                selection.click(cell.index, controlSelectionHeld());
                selected = selection.first();
                syncTargetSelectors();
                return true;
            }
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("dx", voxel[0] - state.data.getInteger("x"));
        request.setInteger("dy", voxel[1] - state.data.getInteger("y"));
        request.setInteger("dz", voxel[2] - state.data.getInteger("z"));
        request.setBoolean("airOnly", true);
        request.setString("structureId", selectedStructureId());
        send("addPosition", request, true);
        return true;
    }

    private void drawGizmo(int mx, int my) {
        double x = 0, y = 0, z = 0;
        for (int index : selection.indices()) {
            HologramState.Cell cell = state.cells.get(index);
            x += cell.dx;
            y += cell.dy;
            z += cell.dz;
        }
        int count = selection.size();
        if (count == 0) return;
        double[] center = HologramRenderer.project(x / count, y / count, z / count);
        gizmo.project(center[0], center[1], yaw, pitch);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(3);
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        for (int axis = 0; axis < 3; axis++) {
            int color = HologramGizmo.COLORS[axis];
            double[] end = gizmo.end(axis);
            t.setColorOpaque_I(color & 0xffffff);
            t.addVertex(center[0], center[1], 5);
            t.addVertex(end[0], end[1], 5);
        }
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        for (int axis = 0; axis < 3; axis++) {
            double[] end = gizmo.end(axis);
            drawRect(
                (int) end[0] - 3,
                (int) end[1] - 3,
                (int) end[0] + 4,
                (int) end[1] + 4,
                HologramGizmo.COLORS[axis]);
            text(
                new String[] { "X", "Y", "Z" }[axis],
                (int) end[0] + 5,
                (int) end[1] - 4,
                14,
                HologramGizmo.COLORS[axis]);
        }
        GL11.glPopAttrib();
        if (gizmo.axis() >= 0) text(
            (gizmoCopy ? "复制移动 " : "移动 ") + new String[] { "X", "Y", "Z" }[gizmo.axis()] + " " + gizmo.delta() + " 格",
            38,
            263,
            180,
            0xffffaa);
        // Preview hint and structure overlays may append to the axis tooltip in this same frame.
        else if (gizmo.pick(mx, my) >= 0)
            tooltip = new ArrayList<>(Collections.singletonList("拖动轴移动；按住 Ctrl 开始拖动即复制；松开提交规划"));
    }

    private void drawConfig(int mx, int my) {
        drawRect(
            panelX,
            panelY,
            panelX + HologramScreenLayout.PANEL_WIDTH,
            panelY + HologramScreenLayout.PANEL_HEIGHT,
            0xef172f36);
        skin("panel", panelX, panelY, HologramScreenLayout.PANEL_WIDTH, 17);
        text("目标属性", 286, 65, 220, 0xf5e4b9);
        String[] modes = { "补建", "替换", "拆除" };
        for (int i = 0; i < 3; i++) button(
            70 + i,
            284 + i * 58,
            80,
            55,
            17,
            (state.data.getInteger("mode") == i ? "›" : "") + modes[i],
            editable(),
            mx,
            my);
        text(
            selection.size() == 0 ? "点击预览选择编辑范围" : "编辑所选 " + selection.size() + " 格 · T 同种 / Ctrl 多选",
            286,
            104,
            170,
            0x94afb4);
        HologramState.Cell cell = selectedCell();
        text(
            cell == null ? "在预览中点击选择位置"
                : cell.sourceMissing ? "来源结构缺失" : "位置 " + cell.dx + "," + cell.dy + "," + cell.dz,
            286,
            122,
            168,
            cell != null && cell.sourceMissing ? 0xff7272 : 0xb9d5d4);
        NBTTagCompound grade = stableGradeCapability();
        if (grade != null) {
            text(grade.getString("label"), 286, 140, 220, 0xf5e4b9);
            text("＋ / − 连续调整等级，保留当前选区", 286, 160, 220, 0x94afb4);
            drawGradeSelector(grade, mx, my);
            if (cell != null) button(95, 284, 200, 224, 17, "查看可替换方块", editable(), mx, my);
        } else {
            List<String> roles = hatchRoles();
            hatchRole = Math.floorMod(hatchRole, roles.size());
            button(97, 284, 135, 224, 17, "类型：" + roleLabel(roles.get(hatchRole)) + " ▾", cell != null, mx, my);
            drawTierSelector(284, 155, 224, mx, my);
            if (mx >= 284 && mx < 508 && my >= 135 && my < 173) tooltip = Collections.singletonList(
                "目标类型：" + roleLabel(roles.get(hatchRole))
                    + "；目标等级："
                    + (hatchFilterTier < 0 ? "未指定" : tierName(hatchFilterTier))
                    + "；筛选候选后点击具体目标。"
                    + candidateNote());
            List<Integer> indices = filteredHatches();
            hatchOffset = Math.max(0, Math.min(hatchOffset, Math.max(0, indices.size() - 2)));
            int[] available = cellTag().getIntArray("candidateAvailable");
            for (int row = 0; row < 2 && row + hatchOffset < indices.size(); row++) {
                int index = indices.get(row + hatchOffset);
                ItemStack stack = cell.candidates.get(index);
                drawItem(stack, 284, 177 + row * 20);
                String count = state.data.getBoolean("creativeMaterials") ? "∞"
                    : index < available.length ? Integer.toString(available[index]) : "?";
                button(
                    10000 + index,
                    304,
                    177 + row * 20,
                    204,
                    18,
                    (cell.chosenChoice == index ? "✓ " : "") + stack.getDisplayName() + " ×" + count,
                    editable() && canInventoryPin(),
                    mx,
                    my);
                if (mx >= 284 && mx < 508 && my >= 177 + row * 20 && my < 195 + row * 20)
                    tooltip = fontRendererObj.listFormattedStringToWidth(
                        stack.getDisplayName() + " · 已启用来源可用 " + count + "；仅更新规划目标。" + candidateNote(),
                        230);
            }
            if (indices.isEmpty()) {
                NBTTagCompound primary = primaryCapability();
                if (primary != null && !"_hatch_candidate".equals(primary.getString("id"))) {
                    drawGradeSelector(primary, mx, my);
                } else text(
                    cell != null && !cell.candidateComplete ? "未完整识别 / 无此筛选候选" : "无此类型 / 等级候选",
                    286,
                    181,
                    220,
                    0x94afb4);
            }
        }
        text(cell == null ? "仅编辑规划，不立即施工" : "目标：" + cellName(cell, false), 286, 218, 220, 0x94afb4);
        if (cell != null && mx >= 284 && mx < 456 && my >= 120 && my < 134
            || cell != null && mx >= 284 && mx < 508 && my >= 216 && my < 230)
            tooltip = fontRendererObj.listFormattedStringToWidth(
                cellName(cell, true) + " → "
                    + cellName(cell, false)
                    + "；"
                    + HologramUiText.reason(cellTag().getString("reason"))
                    + "；"
                    + statusName(cell.status),
                230);
        button(112, 284, 231, 40, 17, "复制", editable() && cell != null, mx, my);
        button(66, 327, 231, 75, 17, "恢复默认壳", editable() && cell != null && !cell.anchor, mx, my);
        button(113, 405, 231, 103, 17, "六面转向", editable() && cell != null, mx, my);
        button(94, 284, 251, 107, 15, "仓室预设", editable() && state.data.getInteger("mode") != 2, mx, my);
        button(78, 395, 251, 113, 15, "材料来源", editable(), mx, my);
        button(61, 458, 100, 50, 17, "背包选材", editable() && state.data.getInteger("mode") != 2, mx, my);
    }

    private void drawGradeSelector(NBTTagCompound grade, int mx, int my) {
        button(80, 284, 177, 20, 18, "−", editable() && canStep(grade, -1), mx, my);
        button(81, 307, 177, 178, 18, optionLabel(grade), editable(), mx, my);
        button(82, 488, 177, 20, 18, "+", editable() && canStep(grade, 1), mx, my);
    }

    private NBTTagCompound stableGradeCapability() {
        NBTTagCompound grade = primaryCapability();
        if (grade == null || !grade.getBoolean("editable") || "_hatch_candidate".equals(grade.getString("id")))
            return null;
        HologramState.Cell cell = selectedCell();
        if (cell == null) return grade;
        if (cell.family.equals("coil") && grade.getString("id")
            .equals("coil")) return grade;
        if (cell.family.startsWith("glass:") && grade.getString("id")
            .equals("glass")) return grade;
        if (cell.family.startsWith("tiered:") && grade.getString("kind")
            .equals("material")) return grade;
        return null;
    }

    private void drawShortcuts(int mx, int my) {
        drawRect(70, 62, 346, 250, 0xff172f36);
        text("快捷键 · 点击一项后按新组合", 80, 70, 230, 0xf5e4b9);
        button(114, 318, 66, 20, 17, "×", true, mx, my);
        for (int i = 0; i < HologramKeyBindings.ACTIONS.length; i++) {
            button(
                120 + i,
                80,
                88 + i * 18,
                256,
                16,
                HologramKeyBindings.LABELS[i] + "：" + (captureBinding == i ? "请按键（Esc取消）" : keyBindings.label(i)),
                true,
                mx,
                my);
        }
        button(115, 80, 220, 80, 17, "恢复默认", true, mx, my);
        text("撤销 / 重做只改变规划，不回滚世界", 80, 241, 256, 0xe4b85a);
        drawRect(70, 252, 442, 296, 0xff172f36);
        text("选择：T 同种 · Ctrl 多选 · Shift 框选 · Ctrl+A 全选", 80, 259, 352, 0xb9d5d4);
        text("编辑：Alt 选空气 · G 移动轴 · Ctrl+拖轴复制 · Esc/E 关闭", 80, 277, 352, 0xb9d5d4);
    }

    private void drawTabs(int mx, int my) {
        for (int i = 0; i < 2; i++) {
            int x = 12 + i * 62;
            Control tab = new Control(148 + i, x, 9, 58, 17, true);
            controls.add(tab);
            boolean active = blueprintTab == (i == 1);
            drawRect(x, 9, x + 58, 26, active ? 0xff274c56 : tab.contains(mx, my) ? 0xff213d46 : 0xff17333c);
            int accent = contextualAccent(148 + i);
            if (active || accent != 0) drawRect(x, 24, x + 58, 26, 0xff000000 | (accent != 0 ? accent : 0x81dce9));
            drawCenteredString(
                fontRendererObj,
                i == 0 ? "结构调整" : "蓝图设置",
                x + 29,
                13,
                accent != 0 ? accent : active ? 0x81dce9 : 0xc1d2d0);
        }
    }

    private boolean structureEnabled(String id) {
        NBTTagList structures = state.data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            NBTTagCompound row = structures.getCompoundTagAt(i);
            if (id.equals(row.getString("id"))) return row.getBoolean("enabled");
        }
        return true;
    }

    private int compactMouseX(int x) {
        return panelX
            + Math.round((x - HologramScreenLayout.COMPACT_PANEL_X) / HologramScreenLayout.CONTROL_WIDTH_SCALE);
    }

    private String selectedStructureId() {
        return selection.structureId(state, state.data.getString("activeStructure"), selected);
    }

    private void drawLibrary(int mx, int my) {
        if (HologramLibraryClient.dedicated()) {
            drawClientServerLibrary(mx, my);
            return;
        }
        int x = HologramScreenLayout.COMPACT_PANEL_X, w = HologramScreenLayout.COMPACT_PANEL_WIDTH;
        NBTTagList presets = state.data.getTagList("presets", 10);
        drawRect(x, 57, x + w, 314, 0xef172f36);
        text("蓝图库 · " + presets.tagCount() + " 项", x + 5, 62, w - 10, 0xf5e4b9);
        presetNameField.drawTextBox();
        if (presetNameField.getText()
            .isEmpty() && !presetNameField.isFocused()) text("当前蓝图名称", x + 8, 83, w - 16, 0x647c7f);
        boolean canSave = editable() && processingReady()
            && !state.data.getBoolean("settingsOnly")
            && !state.cells.isEmpty();
        drawRect(x + 3, 97, x + w - 3, 117, 0xff24444d);
        button(140, x + 5, 99, 103, 16, "保存蓝图", canSave, mx, my);
        button(222, x + 112, 99, 103, 16, "覆盖保存 " + (overwriteMode ? "✓" : "×"), true, mx, my);
        button(141, x + 112, 34, 103, 17, "放置所选蓝图", editable() && selectedPresetExists(), mx, my);
        libraryOffset = Math.max(0, Math.min(libraryOffset, Math.max(0, presets.tagCount() - 4)));
        NBTTagCompound selectedRow = null;
        for (int i = 0; i < presets.tagCount(); i++) {
            NBTTagCompound row = presets.getCompoundTagAt(i);
            if (row.getString("id")
                .equals(state.data.getString("selectedPresetId"))) selectedRow = row;
            if (i < libraryOffset || i >= libraryOffset + 4) continue;
            int y = 123 + (i - libraryOffset) * 28;
            boolean active = row.getString("id")
                .equals(overwriteMode ? overwriteTarget : state.data.getString("selectedPresetId"));
            Control entry = new Control(14000 + i, x + 5, y, w - 10, 28, editable());
            controls.add(entry);
            if (active || entry.contains(mx, my))
                drawRect(x + 5, y, x + w - 5, y + 28, active ? 0xff244b57 : 0xff203b43);
            if (active) drawRect(x + 5, y + 2, x + 8, y + 26, 0xff81dce9);
            drawRect(x + 8, y + 27, x + w - 8, y + 28, 0xff2b444c);
            drawRect(x + 12, y + 5, x + 23, y + 16, active ? 0xff7fbdc8 : 0xff637e89);
            drawRect(x + 14, y + 7, x + 21, y + 9, 0xff19313a);
            drawRect(x + 14, y + 11, x + 21, y + 13, 0xff19313a);
            text(row.getString("name"), x + 29, y + 4, w - 42, active ? 0xf5e4b9 : 0xd0dada);
            text(row.getInteger("cells") + " 格 · " + blueprintSource(row), x + 29, y + 17, w - 42, 0x8ba7ad);
            if (entry.contains(mx, my)) {
                tooltip = new ArrayList<>();
                tooltip.add(row.getString("name"));
                tooltip.add(
                    row.getInteger("cells") + " 格 · "
                        + row.getInteger("structures")
                        + " 结构 · 来源："
                        + blueprintSource(row));
                tooltip.add(overwriteMode ? "仅选择覆盖目标；保存时确认覆盖" : "点击选择并预览；上方“放置”开始世界定位");
            }
        }
        if (presets.tagCount() == 0) {
            text("尚无保存蓝图", x + 12, 139, w - 24, 0xb9d5d4);
            text("框选区域后保存为蓝图", x + 12, 157, w - 24, 0x8ba7ad);
        }
        if (presets.tagCount() > 4) {
            drawRect(x + w - 3, 123, x + w - 1, 235, 0xff243a40);
            int thumbHeight = Math.max(12, 112 * 4 / presets.tagCount());
            int thumbY = 123 + (112 - thumbHeight) * libraryOffset / (presets.tagCount() - 4);
            drawRect(x + w - 3, thumbY, x + w - 1, thumbY + thumbHeight, 0xff6aabb8);
        }
        button(139, x + 5, 241, 103, 17, "编辑所选", editable() && selectedPresetExists(), mx, my);
        button(142, x + 5, 261, 103, 17, "保留 NBT " + (retainNbt ? "✓" : "×"), true, mx, my);
        button(143, x + 112, 261, 103, 17, "覆盖板 " + (retainCovers ? "✓" : "×"), true, mx, my);
        button(159, x + 5, 281, 67, 17, "导出 JSON", editable() && selectedPresetExists(), mx, my);
        button(161, x + 77, 281, 66, 17, "导入 JSON", editable(), mx, my);
        button(175, x + 148, 281, 67, 17, "删除", editable() && selectedPresetExists(), mx, my);
        button(150, x + 5, 301, 103, 13, "来源版本", true, mx, my);
        button(227, x + 112, 301, 103, 13, "全局提示词", editable(), mx, my);
        drawBlueprintWorkbench(mx, my);
        drawRect(35, 261, 442, 279, 0xe8172f36);
        if (selectedRow == null) {
            text("蓝图来源：" + state.data.getString("blueprintSource"), 41, 263, 395, 0x8ba7ad);
        } else {
            text(
                "来源：" + blueprintSource(selectedRow) + " · " + selectedRow.getInteger("cells") + " 格",
                41,
                263,
                395,
                0x8ba7ad);
        }
        if (mx >= 35 && mx < 442 && my >= 239 && my < 279) {
            tooltip = new ArrayList<>();
            if (selectedRow != null) {
                tooltip.add(selectedRow.getString("name"));
                tooltip.add("来源：" + blueprintSource(selectedRow));
                if (!selectedRow.getString("sourceClass")
                    .isEmpty()) tooltip.add(selectedRow.getString("sourceClass"));
            }
            String error = state.data.getString("presetConfigError");
            String result = error.isEmpty() ? libraryResult : "读取失败：" + error;
            if (!result.isEmpty()) tooltip.addAll(fontRendererObj.listFormattedStringToWidth(result, 350));
        }
    }

    private void drawClientServerLibrary(int mx, int my) {
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        int x = HologramScreenLayout.COMPACT_PANEL_X, w = HologramScreenLayout.COMPACT_PANEL_WIDTH;
        NBTTagList rows = library.rows(publicLibrary);
        NBTTagCompound selectedRow = library.selectedRow(publicLibrary);
        boolean chosen = !selectedRow.getString("id")
            .isEmpty(), idle = editable() && !library.busy();
        drawRect(x, 57, x + w, 314, 0xef172f36);
        text("蓝图库 · " + rows.tagCount() + " 项", x + 5, 62, w - 70, 0xf5e4b9);
        button(201, x + w - 62, 60, 57, 15, publicLibrary ? "服务器 ▾" : "客户端 ▾", true, mx, my);
        if (library.config()
            .getBoolean("admin")) button(207, 272, 9, 76, 17, "管理员设置", true, mx, my);
        presetNameField.drawTextBox();
        if (presetNameField.getText()
            .isEmpty() && !presetNameField.isFocused()) text("当前蓝图名称", x + 8, 83, w - 16, 0x647c7f);
        button(140, x + 5, 99, 103, 16, "保存蓝图", idle && processingReady() && !state.cells.isEmpty(), mx, my);
        button(222, x + 112, 99, 103, 16, "覆盖保存 " + (overwriteMode ? "✓" : "×"), true, mx, my);
        button(141, x + 112, 34, 103, 17, "放置所选蓝图", idle && chosen, mx, my);
        libraryOffset = Math.max(0, Math.min(libraryOffset, Math.max(0, rows.tagCount() - 4)));
        for (int i = libraryOffset; i < Math.min(rows.tagCount(), libraryOffset + 4); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            int y = 123 + (i - libraryOffset) * 28;
            boolean active = row.getString("id")
                .equals(overwriteMode ? overwriteTarget : library.selected(publicLibrary));
            Control entry = new Control(14000 + i, x + 5, y, w - 10, 28, idle);
            controls.add(entry);
            drawRect(
                x + 5,
                y,
                x + w - 5,
                y + 27,
                active ? 0xff244b57 : entry.contains(mx, my) ? 0xff203b43 : 0xff172f36);
            if (active) drawRect(x + 5, y + 2, x + 8, y + 25, 0xff81dce9);
            text(row.getString("name"), x + 12, y + 3, w - 25, active ? 0xf5e4b9 : 0xd0dada);
            String author = row.getString("authorName");
            text(
                "作者：" + (author.isEmpty() ? "未署名" : author) + " · " + HologramLibraryClient.bytes(row.getLong("bytes")),
                x + 12,
                y + 16,
                w - 25,
                0x8ba7ad);
            if (entry.contains(mx, my)) {
                tooltip = new ArrayList<>();
                tooltip.add(row.getString("name"));
                tooltip.add("作者：" + (author.isEmpty() ? "未署名（旧蓝图）" : author));
                tooltip.add(
                    row.getInteger("cells") + " 格 · "
                        + row.getInteger("structures")
                        + " 结构 · "
                        + HologramLibraryClient.bytes(row.getLong("bytes")));
                if (publicLibrary) tooltip.add("上传者（服务器验证）：" + row.getString("uploadedBy"));
                tooltip.add(publicLibrary ? "选择后可直接放置；下载会保存到客户端" : "客户端蓝图可随您连接不同服务器");
            }
        }
        if (rows.tagCount() == 0) {
            text(publicLibrary ? "服务器暂未发布蓝图" : "客户端暂无蓝图", x + 12, 140, w - 24, 0xb9d5d4);
            text(publicLibrary ? "切换客户端页，上传自己的蓝图" : "保存蓝图后可携带到其他服务器", x + 12, 158, w - 24, 0x8ba7ad);
        }
        button(139, x + 5, 241, 103, 17, "编辑所选", idle && chosen, mx, my);
        button(206, x + 112, 241, 103, 17, "取消传输", library.busy(), mx, my);
        button(142, x + 5, 261, 103, 17, "保留 NBT " + (retainNbt ? "✓" : "×"), true, mx, my);
        button(143, x + 112, 261, 103, 17, "覆盖板 " + (retainCovers ? "✓" : "×"), true, mx, my);
        button(203, x + 5, 281, 103, 17, publicLibrary ? "下载至客户端" : "上传 / 更新公库", idle && chosen, mx, my);
        button(
            205,
            x + 112,
            281,
            103,
            17,
            publicLibrary ? "删除自己的上传" : "删除客户端蓝图",
            idle && chosen && (!publicLibrary || selectedRow.getBoolean("mine")),
            mx,
            my);
        if (publicLibrary) {
            button(208, x + 5, 301, 30, 13, "上页", library.page() > 0, mx, my);
            text((library.page() + 1) + " / " + library.pages(), x + 36, 303, 40, 0x8ba7ad);
            button(209, x + 78, 301, 30, 13, "下页", library.page() + 1 < library.pages(), mx, my);
        } else button(202, x + 5, 301, 103, 13, "刷新", !library.busy(), mx, my);
        button(227, x + 112, 301, 103, 13, "全局提示词", editable(), mx, my);
        drawBlueprintWorkbench(mx, my);
        drawRect(35, 261, 442, 279, 0xe8172f36);
        text(library.status(), 41, 258, 395, 0xb9d5d4);
        if (library.busy() && library.totalBytes() > 0) {
            drawRect(41, 273, 436, 276, 0xff29424b);
            drawRect(
                41,
                273,
                41 + (int) (395L * library.progressBytes() / Math.max(1, library.totalBytes())),
                276,
                0xff81dce9);
        }
        if (mx >= 35 && mx < 442 && my >= 239 && my < 279) {
            tooltip = new ArrayList<>();
            tooltip.add(library.status());
            if (library.busy()) tooltip.add(
                HologramLibraryClient.bytes(library.progressBytes()) + " / "
                    + HologramLibraryClient.bytes(library.totalBytes()));
            tooltip.add("客户端目录：MiaoBlueprints/");
        }
    }

    private void openLibraryAdmin() {
        NBTTagCompound config = HologramLibraryClient.INSTANCE.config();
        if (!config.getBoolean("admin")) return;
        libraryAdminDrawer = true;
        libraryConfigRevision = config.getLong("configRevision");
        libraryUploadField = libraryAdminField(140, String.valueOf(config.getInteger("uploadKiB")));
        libraryDownloadField = libraryAdminField(175, String.valueOf(config.getInteger("downloadKiB")));
        libraryQuotaField = libraryAdminField(210, String.valueOf(config.getInteger("maxUploads")));
    }

    private GuiTextField libraryAdminField(int y, String value) {
        GuiTextField field = new GuiTextField(fontRendererObj, 282, y, 136, 18);
        field.setMaxStringLength(7);
        field.setText(value);
        return field;
    }

    private boolean processingReady() {
        return !state.data.getBoolean("blueprintProcessingRequired")
            || state.data.getBoolean("blueprintProcessingComplete");
    }

    private boolean canEditBlueprintNbt() {
        return processingReady() || state.data.getBoolean("blueprintSaved");
    }

    private void rememberGuidance() {
        GUIDANCE_MEMORY
            .put(openedPurpose, new boolean[] { initialEditHint, savedEditHint, structureVisited, basicConfigured });
        while (GUIDANCE_MEMORY.size() > 64) GUIDANCE_MEMORY.remove(
            GUIDANCE_MEMORY.keySet()
                .iterator()
                .next());
    }

    private int contextualAccent(int id) {
        if (id == 263 && processingFlow != null && processingFlow.stage == 2 && !quantumGroupsConfirmed())
            return 0x80efaa;
        if (id == 231 && processingFlow != null && processingFlow.optional()) return 0x80efaa;
        if (!state.data.getBoolean("virtual")) {
            boolean configured = basicConfigured || state.data.getBoolean("presetActive");
            if (id == 94 && !configured && state.data.getBoolean("presetAvailable")) return 0x80efaa;
            if (id == 50 && configured) return 0x80efaa;
        }
        if (id == 97 && selectedCell() != null && selectedCell().candidates.size() > 1 && hatchRoles().size() > 1)
            return 0x68dded;
        if (state.data.getBoolean("virtual") && !state.data.getBoolean("virtualPlacement")) {
            if ((id == 149 || id == 130) && !libraryDrawer && !initialEditHint) return 0x80efaa;
            if (id == 148 && libraryDrawer && savedEditHint && !structureVisited) return 0x68dded;
            if (id == 220 && !processingReady()) return 0x80efaa;
            if ((id == 140 || id == 221) && libraryDrawer
                && processingReady()
                && state.data.getBoolean("blueprintDirty")) return 0x80efaa;
            if ((id == 166 || id == 146) && selected >= 0 && cellEditHint) return 0x68dded;
        }
        return 0;
    }

    private String blueprintSaveState() {
        if (state.data.getBoolean("blueprintSavePending")) return HologramLibraryClient.INSTANCE.status()
            .isEmpty() ? HologramUiText.localized("gtmb.hologram.blueprint.saving", "正在传输保存，等待写盘确认")
                : HologramLibraryClient.INSTANCE.status();
        return state.data.getBoolean("blueprintDirty")
            ? state.data.getBoolean("blueprintSaved")
                ? HologramUiText.localized("gtmb.hologram.blueprint.dirty", "改动未保存")
                : HologramUiText.localized("gtmb.hologram.blueprint.unsaved", "未保存")
            : state.data.getBoolean("blueprintSaved") ? HologramUiText.localized("gtmb.hologram.blueprint.saved", "已保存")
                : HologramUiText.localized("gtmb.hologram.blueprint.unsaved", "未保存");
    }

    private void drawBlueprintWorkbench(int mx, int my) {
        drawRect(35, 219, 442, 259, 0xf0172f36);
        text(
            blueprintSaveState() + " · "
                + (state.data.getBoolean("blueprintDirectSave") ? "无需处理，可直接保存"
                    : processingReady() ? "处理已完成" : "先完成蓝图处理流程"),
            41,
            223,
            395,
            0xb9d5d4);
        button(
            220,
            41,
            239,
            143,
            17,
            processingReady() ? "重新处理蓝图" : "开始蓝图处理流程",
            editable() && !state.cells.isEmpty(),
            mx,
            my);
        button(221, 190, 239, 125, 17, "保存流程预设", editable() && processingReady() && !state.cells.isEmpty(), mx, my);
        button(
            166,
            321,
            239,
            115,
            17,
            selected >= 0 ? "所选格 NBT" : "方块 NBT",
            editable() && canEditBlueprintNbt(),
            mx,
            my);
        button(223, 41, 282, 120, 17, "旋转查看 ↻", true, mx, my);
        if (selected >= 0) button(146, 167, 282, 129, 17, "所选格提示词", editable(), mx, my);
        if (savedEditHint && !structureVisited) text("可修改 NBT / 提示词；结构调整请进入左上页签", 41, 301, 395, 0x68dded);
    }

    private NBTTagCompound processingState() {
        return state.data.getCompoundTag("processing");
    }

    private boolean quantumGroupsConfirmed() {
        return processingState().getTagList("quantumNodes", 10)
            .tagCount() == 0 || processingState().getBoolean("quantumGroupsConfirmed");
    }

    private boolean processingChoiceReady() {
        processingFlow.quantumGroups(
            processingState().getTagList("quantumNodes", 10)
                .tagCount() > 0,
            quantumGroupsConfirmed());
        return processingFlow.chosen();
    }

    long saveQuantumGroups(NBTTagList groups, long sourceRevision) {
        if (sourceRevision != revision()) return -1;
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", sourceRevision);
        request.setTag("groups", groups.copy());
        send("configureBlueprintQuantumGroups", request, true);
        return sequence;
    }

    void quantumGroupsSaved() {
        if (processingFlow != null && processingFlow.stage == 2) processingFlow.choose();
        notice = "本图量子类群已保存";
    }

    private NBTTagList processingList(String key, int type) {
        String alias = "relations".equals(key) ? "processingRelations"
            : "nbtKeys".equals(key) ? "processingNbtKeys" : "processingNbtPaths";
        return state.data.hasKey(alias) ? state.data.getTagList(alias, type) : processingState().getTagList(key, type);
    }

    private void drawProcessingFlow(int mx, int my) {
        drawRect(28, 48, 650, 304, 0xff172f36);
        int stage = processingFlow.stage;
        NBTTagCompound policy = processingFlow.values();
        text(
            HologramUiText.localized("gtmb.hologram.processing.title", "蓝图处理") + " · "
                + (stage + 1)
                + "/6 · "
                + HologramUiText.localized(
                    "gtmb.hologram.processing." + HologramProcessingFlow.STAGES[stage],
                    new String[] { "流程预设", "去标识等级", "量子网络", "坐标关系", "全局 NBT", "自动提示词" }[stage]),
            42,
            58,
            590,
            0xf5e4b9);
        button(232, 42, 76, 126, 17, "倾向自用" + ("self".equals(policy.getString("tendency")) ? " ✓" : ""), true, mx, my);
        button(
            233,
            174,
            76,
            126,
            17,
            "倾向分享" + ("share".equals(policy.getString("tendency")) ? " ✓" : ""),
            true,
            mx,
            my);
        text(processingFlow.fullReview() ? "倾向自动预选；每一步仍需下一步确认" : "按实际内容处理；空阶段可跳过", 312, 81, 318, 0x94afb4);
        if (stage == 0) {
            text("选择已保存的流程，或明确不使用预设；应用后仍逐步核对", 42, 103, 588, 0xb9d5d4);
            button(235, 42, 123, 588, 18, processingFlow.chosen() ? "✓ 不使用其它流程预设 / 继续当前选择" : "不使用流程预设", true, mx, my);
            NBTTagList presets = state.data.getTagList("processingPresets", 10);
            for (int i = processingFlow.offset; i < Math.min(presets.tagCount(), processingFlow.offset + 3); i++)
                button(
                    70000 + i,
                    42,
                    145 + (i - processingFlow.offset) * 22,
                    588,
                    19,
                    presets.getCompoundTagAt(i)
                        .getString("name"),
                    true,
                    mx,
                    my);
            if (presets.tagCount() == 0) text("暂无流程预设；完成一次处理后可保存供下次复用", 42, 154, 588, 0x94afb4);
            processingPresetField.drawTextBox();
            button(293, 342, 220, 91, 18, "新建流程", processingReady() && editable(), mx, my);
            button(
                294,
                439,
                220,
                91,
                18,
                "更新所选",
                processingReady() && editable() && !selectedProcessingPreset.isEmpty(),
                mx,
                my);
            button(295, 536, 220, 94, 18, "删除所选", editable() && !selectedProcessingPreset.isEmpty(), mx, my);
        } else if (stage == 1) {
            text("等级 0 保留原身份，1 清理已登记网络身份；更高等级逐步清理位置等字段", 42, 103, 588, 0xb9d5d4);
            int minimum = Math.max(0, Math.min(10, processingState().getInteger("minimumLevel")));
            for (int level = 0; level <= 10; level++) button(
                240 + level,
                42 + level * 53,
                126,
                49,
                21,
                (policy.getInteger("deidentificationLevel") == level ? "✓ " : "") + level,
                level >= minimum,
                mx,
                my);
            text(
                new String[] { "保留原身份", "清理登记网络身份", "同时清理登记位置与维度", "同时清理登记频率与频道", "同时清理登记安全标识", "同时清理登记代理与链接",
                    "同时清理登记多方块屏幕关联", "同时清理运行缓存与历史采样", "同时清理界面标签状态", "同时清理停机状态", "同时清理活动与采样时间状态" }[Math
                        .max(0, Math.min(10, policy.getInteger("deidentificationLevel")))],
                42,
                163,
                588,
                0xb9d5d4);
            text("按已登记设备字段逐级处理；已清除的数据无法恢复", 42, 210, 588, 0x94afb4);
            text("当前最低等级 " + minimum + "；只有点击等级才确认本步骤", 42, 185, 588, 0x94afb4);
        } else if (stage == 2) {
            text("自用保留原网络；分享使用自己的终端。每张图仍需明确确认节点分类。", 42, 103, 588, 0xb9d5d4);
            button(
                262,
                42,
                121,
                588,
                22,
                ("preserve".equals(policy.getString("quantum")) ? "✓ " : "") + "自用：保留原量子绑定（仅原存档、原网络位置与维度有效）",
                true,
                mx,
                my);
            button(
                260,
                42,
                149,
                588,
                22,
                ("rebind".equals(policy.getString("quantum")) ? "✓ " : "") + "分享：去除源网络锚定，逐类群选择已绑定目标网络且你有权使用的量子终端",
                true,
                mx,
                my);
            button(
                261,
                42,
                177,
                588,
                22,
                ("clear".equals(policy.getString("quantum")) ? "✓ " : "") + "完全清除分组，放置为未绑定节点",
                true,
                mx,
                my);
            button(
                263,
                42,
                208,
                588,
                23,
                quantumGroupsConfirmed() ? "量子节点网络分类 · 本图已确认，可重新编辑" : "量子节点网络分类 · 请配置并确认本图",
                editable() && processingState().getTagList("quantumNodes", 10)
                    .tagCount() > 0,
                mx,
                my);
            text("所有节点默认属于“默认网络”；未分类节点会阻止保存及下一步。", 42, 235, 588, 0x94afb4);
        } else if (stage == 3) {
            text("每项坐标可清除、保留绝对位置、或随蓝图相对移动", 42, 103, 588, 0xb9d5d4);
            if (mx >= 42 && mx < 630 && my >= 100 && my < 140) tooltip = fontRendererObj.listFormattedStringToWidth(
                "推荐规则：内部关系随蓝图相对移动；外部关系在自用时保留绝对位置，分享时清除。相对只保存映射，清除留下待设置标记。只有已登记且审阅的关系字段按该选择处理，其它设备配置、库存和资产仍精确核对。",
                430);
            String[] defaults = { "auto", "clear", "absolute", "relative" },
                defaultNames = { "推荐规则", "清除", "绝对位置", "相对蓝图" };
            String[] modes = { "clear", "absolute", "relative" }, names = { "清除", "绝对位置", "相对蓝图" };
            for (int i = 0; i < 4; i++) button(
                270 + i,
                42 + i * 148,
                120,
                142,
                18,
                (defaults[i].equals(policy.getString("coordinateDefault")) ? "✓ " : "") + "默认：" + defaultNames[i],
                true,
                mx,
                my);
            NBTTagList relations = processingList("relations", 10);
            for (int i = processingFlow.offset; i < Math.min(relations.tagCount(), processingFlow.offset + 4); i++) {
                NBTTagCompound relation = relations.getCompoundTagAt(i);
                int y = 146 + (i - processingFlow.offset) * 27;
                String location = "(" + relation.getInteger(
                    "targetX") + ", " + relation.getInteger("targetY") + ", " + relation.getInteger("targetZ") + ")";
                text(
                    relation.getString("label") + " · "
                        + (relation.getBoolean("internal") ? "蓝图内 " : "蓝图外 ")
                        + location,
                    42,
                    y + 5,
                    270,
                    0xb9d5d4);
                if (mx >= 42 && mx < 312 && my >= y && my < y + 22)
                    tooltip = fontRendererObj.listFormattedStringToWidth(
                        relation.getString("label") + "：目标维度 "
                            + relation.getInteger("targetDimension")
                            + "，坐标 "
                            + location
                            + "。"
                            + (relation.getBoolean("internal") ? "目标位于蓝图内，推荐随蓝图移动和旋转校准。"
                                : "目标位于蓝图外，请选择保留绝对位置、校准相对位置或清除后重设。"),
                        430);
                for (int mode = 0; mode < 3; mode++) button(
                    71000 + i * 3 + mode,
                    316 + mode * 106,
                    y,
                    102,
                    22,
                    (modes[mode].equals(coordinateDisplayMode(relation)) ? "✓ " : "") + names[mode],
                    true,
                    mx,
                    my);
                if (mx >= 316 && mx < 418
                    && my >= y
                    && my < y + 22
                    && !relation.getString("clearDetail")
                        .isEmpty())
                    tooltip = fontRendererObj.listFormattedStringToWidth(relation.getString("clearDetail"), 430);
            }
            if (relations.tagCount() == 0) text("没有已登记坐标关系；请选择默认方式确认本步骤", 42, 155, 588, 0x94afb4);
        } else if (stage == 4) {
            text("忽略根条目会清除所有方块的同名 NBT；方块细节可在处理后继续编辑", 42, 103, 588, 0xb9d5d4);
            button(280, 42, 122, 288, 18, "全部保留并确认", true, mx, my);
            button(281, 342, 122, 288, 18, "确认当前忽略清单", true, mx, my);
            NBTTagList keys = processingList("nbtKeys", 8);
            for (int i = processingFlow.offset; i < Math.min(keys.tagCount(), processingFlow.offset + 5); i++) {
                String key = keys.getStringTagAt(i);
                button(
                    72000 + i,
                    42,
                    147 + (i - processingFlow.offset) * 20,
                    588,
                    17,
                    (processingFlow.contains("ignoredNbtKeys", key) ? "忽略 × " : "保留 ✓ ") + key,
                    true,
                    mx,
                    my);
                if (mx >= 42 && mx < 630
                    && my >= 147 + (i - processingFlow.offset) * 20
                    && my < 164 + (i - processingFlow.offset) * 20)
                    tooltip = fontRendererObj.listFormattedStringToWidth(
                        key + "：" + HologramUiText.nbtMeaning(key) + " 忽略后不会保存这一根条目；资源与身份保护仍由服务端复核。",
                        430);
            }
            if (mx >= 42 && mx < 630 && my >= 103 && my < 140) tooltip = fontRendererObj.listFormattedStringToWidth(
                "NBT 包含设备配置、网络身份、库存、流体与能量。忽略某根条目会从所有匹配方块移除该项；身份保护和资源校验仍由服务端执行。方块级忽略路径在后续 NBT 编辑中设置。",
                430);
        } else {
            text("自动提示补充已登记配置问题，不改写您已有的提示词", 42, 103, 588, 0xb9d5d4);
            String[] values = { "light", "full", "none" }, names = { "轻度 · 关键绑定与放置提醒", "完整 · 全部已登记问题说明", "不自动添加提示词" };
            for (int i = 0; i < 3; i++) button(
                290 + i,
                42,
                126 + i * 33,
                588,
                25,
                (values[i].equals(policy.getString("autoHints")) ? "✓ " : "") + names[i],
                true,
                mx,
                my);
            text("完成后可保存蓝图、复用流程预设，以及继续编辑各方块 NBT", 42, 238, 588, 0x94afb4);
        }
        button(
            296,
            42,
            249,
            588,
            18,
            processingFlow.fullReview() ? "✓ 完整六步核对 · 点击改为按内容处理" : "手动完整六步核对 · 点击从第一步开始",
            editable(),
            mx,
            my);
        button(230, 42, 274, 126, 20, "上一步", stage > 0, mx, my);
        button(234, 174, 274, 126, 20, "取消", true, mx, my);
        button(
            231,
            312,
            274,
            318,
            20,
            stage == 5 ? "完成处理 · 等待服务器校验" : processingFlow.optional() ? "下一步 · 本步可跳过" : "下一步",
            editable() && processingChoiceReady(),
            mx,
            my);
    }

    private boolean activateProcessing(int id) {
        if (id == 220) {
            NBTTagCompound policy = state.data.hasKey("processingPolicy")
                ? state.data.getCompoundTag("processingPolicy")
                : processingState().getCompoundTag("policy");
            processingFlow = new HologramProcessingFlow(
                policy,
                state.data.getBoolean("processingResumeAllowed"),
                processingFullReview);
            processingFlow.requirements(
                processingState().hasKey("requiredStages") ? processingState().getInteger("requiredStages") : 63);
            processingPresetField = new GuiTextField(fontRendererObj, 42, 220, 288, 18);
            processingPresetField.setMaxStringLength(64);
            processingPresetField.setText(
                presetNameField.getText()
                    .trim() + " · 流程");
            return true;
        }
        if (id == 221) {
            if (!processingReady()) return true;
            processingPresetSaveReview = true;
            processingPresetField = new GuiTextField(fontRendererObj, 96, 150, 486, 18);
            processingPresetField.setMaxStringLength(64);
            processingPresetField.setText(
                presetNameField.getText()
                    .trim() + " · 流程");
            processingPresetField.setFocused(true);
            return true;
        }
        if (processingFlow == null) return false;
        if (id == 296) {
            processingFullReview = !processingFlow.fullReview();
            processingFlow.fullReview(processingFullReview);
            return true;
        }
        NBTTagCompound values = processingFlow.values();
        if (id == 234) {
            processingFlow = null;
            return true;
        }
        if (id == 230) {
            processingFlow.previous();
            return true;
        }
        if (id == 232 || id == 233) {
            processingFlow.tendency(id == 233);
            values.setInteger(
                "deidentificationLevel",
                Math.max(values.getInteger("deidentificationLevel"), processingState().getInteger("minimumLevel")));
            return true;
        }
        if (id == 231) {
            if (!processingChoiceReady()) return true;
            if (processingFlow.stage < 5) processingFlow.next();
            else {
                processingFlow.next();
                if (!processingFlow.complete()) return true;
                NBTTagCompound request = new NBTTagCompound();
                request.setTag("policy", processingFlow.policy());
                processingFlow = null;
                send("processBlueprint", request, true);
            }
            return true;
        }
        if (id == 293 || id == 294 || id == 295) {
            NBTTagCompound request = new NBTTagCompound();
            request.setString("presetId", selectedProcessingPreset);
            request.setString(
                "name",
                processingPresetField.getText()
                    .trim());
            request.setTag(
                "policy",
                state.data.getCompoundTag("processingPolicy")
                    .copy());
            send(
                id == 293 ? "saveProcessingPreset" : id == 294 ? "updateProcessingPreset" : "deleteProcessingPreset",
                request,
                true);
            if (id == 295) selectedProcessingPreset = "";
            return true;
        }
        if (id == 235) processingFlow.choose();
        else if (id >= 70000 && id < 71000) {
            NBTTagCompound row = state.data.getTagList("processingPresets", 10)
                .getCompoundTagAt(id - 70000);
            processingFlow.preset(row.getCompoundTag("policy"));
            selectedProcessingPreset = row.getString("id");
            processingPresetField.setText(row.getString("name"));
            NBTTagCompound request = new NBTTagCompound();
            request.setString("presetId", selectedProcessingPreset);
            send("loadProcessingPreset", request, true);
            processingPresetLoadSequence = sequence;
        } else if (id >= 71000 && id < 72000) {
            int index = (id - 71000) / 3, mode = (id - 71000) % 3;
            processingFlow.coordinate(
                processingList("relations", 10).getCompoundTagAt(index)
                    .getString("id"),
                new String[] { "clear", "absolute", "relative" }[mode]);
            processingFlow.choose();
        } else if (id >= 72000 && id < 73000) {
            processingFlow.toggle("ignoredNbtKeys", processingList("nbtKeys", 8).getStringTagAt(id - 72000));
            processingFlow.choose();
        } else if (id >= 240 && id <= 250) {
            values.setInteger("deidentificationLevel", id - 240);
            processingFlow.choose();
        } else if (id == 260 || id == 261 || id == 262) {
            values.setString("quantum", id == 260 ? "rebind" : id == 261 ? "clear" : "preserve");
            processingFlow.choose();
        } else if (id == 263) {
            if (editable() && processingState().getTagList("quantumNodes", 10)
                .tagCount() > 0) mc.displayGuiScreen(new HologramQuantumGroupsScreen(this, state));
        } else if (id >= 270 && id <= 273) {
            values.setString("coordinateDefault", new String[] { "auto", "clear", "absolute", "relative" }[id - 270]);
            processingFlow.choose();
        } else if (id == 280 || id == 281) {
            if (id == 280) values.setTag("ignoredNbtKeys", new NBTTagList());
            processingFlow.choose();
        } else if (id >= 290 && id <= 292) {
            values.setString("autoHints", new String[] { "light", "full", "none" }[id - 290]);
            processingFlow.choose();
        }
        return true;
    }

    private String coordinateDisplayMode(NBTTagCompound relation) {
        String mode = processingFlow.coordinate(relation.getString("id"));
        if (!"auto".equals(mode)) return mode;
        return relation.getBoolean("internal") ? "relative"
            : "share".equals(
                processingFlow.values()
                    .getString("tendency")) ? "clear" : "absolute";
    }

    private void drawBlueprintSwitchReview(int mx, int my) {
        drawRect(80, 92, 598, 242, 0xff172f36);
        if (processingPresetSaveReview) {
            text("保存流程预设", 96, 106, 486, 0xf5e4b9);
            text("为本次已核对的处理选择命名，供下次导入时复用", 96, 132, 486, 0xb9d5d4);
            processingPresetField.drawTextBox();
            button(225, 96, 201, 230, 22, "取消", true, mx, my);
            button(
                224,
                338,
                201,
                244,
                22,
                "确认保存流程预设",
                editable() && !processingPresetField.getText()
                    .trim()
                    .isEmpty(),
                mx,
                my);
            return;
        }
        text(overwriteReview ? "确认覆盖蓝图" : "放弃未保存改动", 96, 106, 486, 0xf5e4b9);
        text(overwriteReview ? "将以当前蓝图替换：" + overwriteName : "当前蓝图尚未保存；继续将放弃这些改动并切换蓝图。", 96, 138, 486, 0xb9d5d4);
        text(
            overwriteReview ? "目标：" + overwriteTarget
                : sourceSwitchProposal != null
                    ? "将导入：" + ("openController".equals(sourceSwitchProposal.getString("op")) ? "新控制器结构" : "新的框选区域")
                    : "取消可返回编辑并先保存蓝图",
            96,
            163,
            486,
            0x94afb4);
        button(225, 96, 201, 230, 22, "取消", true, mx, my);
        button(224, 338, 201, 244, 22, overwriteReview ? "确认覆盖保存" : "放弃改动并继续", editable(), mx, my);
    }

    private void drawLibraryAdmin(int mx, int my) {
        drawRect(0, 0, DESIGN_W, DESIGN_H, 0xd0102026);
        drawRect(60, 77, 452, 290, 0xff172f36);
        text("服务器蓝图库 · 管理员设置（OP 4）", 76, 90, 360, 0xf5e4b9);
        text("单位 KiB/s；默认较慢传输 16，配额默认 10 项", 76, 112, 360, 0x8ba7ad);
        text("客户端上传带宽", 76, 145, 190, 0xb9d5d4);
        libraryUploadField.drawTextBox();
        text("客户端下载带宽", 76, 180, 190, 0xb9d5d4);
        libraryDownloadField.drawTextBox();
        text("每位玩家上传配额", 76, 215, 190, 0xb9d5d4);
        libraryQuotaField.drawTextBox();
        button(210, 76, 247, 166, 19, "保存并等待服务器确认", true, mx, my);
        button(211, 254, 247, 164, 19, "关闭", true, mx, my);
        text(HologramLibraryClient.INSTANCE.status(), 76, 274, 342, 0x8ba7ad);
    }

    private boolean activateClientLibrary(int id) {
        if (!HologramLibraryClient.dedicated()) return false;
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        if (id == 210 || id == 211) {
            if (id == 211) libraryAdminDrawer = false;
            else try {
                library.saveConfig(
                    Integer.parseInt(libraryUploadField.getText()),
                    Integer.parseInt(libraryDownloadField.getText()),
                    Integer.parseInt(libraryQuotaField.getText()));
            } catch (NumberFormatException invalid) {
                notice = "请输入有效的正整数";
            }
            return true;
        }
        if (!libraryDrawer) return false;
        if (id >= 14000 && id < 15000) {
            NBTTagCompound row = library.rows(publicLibrary)
                .getCompoundTagAt(id - 14000);
            if (publicLibrary || !library.busy()) rememberLibraryNavigation(row.getString("id"), "blueprintBrowse");
            library.select(publicLibrary, row.getString("id"));
            if (publicLibrary) {
                NBTTagCompound preview = new NBTTagCompound();
                preview.setString("presetId", row.getString("id"));
                send("previewPreset", preview, true);
            } else library.sendLocal(state, "preview", false, discardConfirmed);
            return true;
        }
        switch (id) {
            case 201:
                cancelLibraryNavigation();
                publicLibrary = !publicLibrary;
                libraryOffset = 0;
                if (publicLibrary) library.refreshServer(0);
                return true;
            case 202:
                library.refreshLocal();
                return true;
            case 203:
                if (publicLibrary) library.downloadSelected();
                else library.sendLocal(state, "preview", true);
                return true;
            case 205:
                NBTTagCompound row = library.selectedRow(publicLibrary);
                if (!publicLibrary || row.getBoolean("mine")) {
                    deletePresetId = row.getString("id");
                    deletePresetName = row.getString("name");
                }
                return true;
            case 206:
                cancelLibraryNavigation();
                library.cancel();
                return true;
            case 207:
                openLibraryAdmin();
                return true;
            case 208:
                library.refreshServer(library.page() - 1);
                libraryOffset = 0;
                return true;
            case 209:
                library.refreshServer(library.page() + 1);
                libraryOffset = 0;
                return true;
            case 176:
                library.deleteSelected(publicLibrary);
                deletePresetId = deletePresetName = "";
                return true;
            case 141:
            case 139:
                if (id == 139 && (publicLibrary || !library.busy()))
                    rememberLibraryNavigation(library.selected(publicLibrary), "blueprintEdit");
                else if (id == 141) cancelLibraryNavigation();
                if (publicLibrary) {
                    NBTTagCompound action = new NBTTagCompound();
                    action.setString("presetId", library.selected(true));
                    send(id == 141 ? "choosePlacementPreset" : "editPreset", action, true);
                } else library.sendLocal(state, id == 141 ? "place" : "edit", false, discardConfirmed);
                if (id == 139) {
                    blueprintTab = true;
                    libraryDrawer = true;
                }
                return true;
            case 140:
            case 132:
                if (!processingReady()) return true;
                String name = presetNameField.getText()
                    .trim();
                if (name.isEmpty()) {
                    notice = "请输入蓝图名称";
                    return true;
                }
                NBTTagCompound options = new NBTTagCompound();
                options.setString("name", name);
                options.setBoolean("retainNbt", retainNbt);
                options.setBoolean("retainCovers", retainCovers);
                options.setBoolean(
                    "retainAllNbt",
                    !state.data.hasKey("blueprintNbtAll") || state.data.getBoolean("blueprintNbtAll"));
                if (state.data.hasKey("blueprintNbtKeys")) options.setTag(
                    "retainedNbtKeys",
                    state.data.getTagList("blueprintNbtKeys", 8)
                        .copy());
                deidentificationSaveOptions(options);
                library.saveDraft(state, options, false);
                return true;
            default:
                return false;
        }
    }

    private String blueprintSource(NBTTagCompound row) {
        String source = row.getString("sourceMod");
        if (source.isEmpty() && row.getString("id")
            .equals(state.data.getString("selectedPresetId"))) source = state.data.getString("sourceMod");
        return source.isEmpty() ? row.getInteger("schema") >= 2 ? "自定义蓝图" : "结构模板" : source;
    }

    private NBTTagCompound blueprintSaveOptions() {
        NBTTagCompound options = new NBTTagCompound();
        String name = presetNameField.getText()
            .trim();
        options.setString("name", name.isEmpty() ? state.data.getString("virtualPresetName") : name);
        options.setBoolean("retainNbt", retainNbt);
        options.setBoolean("retainCovers", retainCovers);
        options.setBoolean(
            "retainAllNbt",
            !state.data.hasKey("blueprintNbtAll") || state.data.getBoolean("blueprintNbtAll"));
        options.setTag(
            "retainedNbtKeys",
            state.data.getTagList("blueprintNbtKeys", 8)
                .copy());
        deidentificationSaveOptions(options);
        return options;
    }

    private void drawDeleteReview(int mx, int my) {
        drawRect(50, 85, 428, 219, 0xff172f36);
        String storage = HologramLibraryClient.dedicated() ? publicLibrary ? "服务器公库" : "客户端本地" : "本地蓝图库";
        text("删除" + storage + "蓝图", 62, 97, 354, 0xf5e4b9);
        text(deletePresetName, 62, 117, 354, 0xffbd91);
        text("删除此" + storage + "蓝图，当前编辑蓝图保留。", 62, 139, 354, 0x94afb4);
        text("此操作不能撤销。", 62, 155, 354, 0xef8585);
        button(177, 62, 187, 171, 17, "取消", true, mx, my);
        button(176, 245, 187, 171, 17, "确认删除", editable(), mx, my);
    }

    private void drawPromptEditor(int mx, int my) {
        drawRect(50, 77, 428, 268, 0xff172f36);
        text("提示词与施工排除 · 已选 " + selection.size() + " 格", 62, 87, 354, 0xf5e4b9);
        String[] scopes = { "全局", "方块", "提示区" };
        for (int i = 0; i < 3; i++) button(
            152 + i,
            62 + i * 118,
            105,
            112,
            17,
            (promptScope == i ? "›" : "") + scopes[i],
            i == 0 || selected >= 0,
            mx,
            my);
        promptField.drawTextBox();
        if (promptSummaryState != state || summaryScope != promptScope) {
            promptSummaryState = state;
            summaryScope = promptScope;
            int[] indices = selection.indices();
            promptScopeSummary = HologramAnnotations.scope(state, indices, promptScope);
            promptSavedSummary = HologramAnnotations.saved(state, indices, promptScope);
        }
        text(promptScopeSummary, 62, 155, 354, 0x94afb4);
        List<String> saved = fontRendererObj.listFormattedStringToWidth("已存：" + promptSavedSummary, 354);
        for (int i = 0; i < Math.min(3, saved.size()); i++) text(saved.get(i), 62, 170 + i * 11, 354, 0xffe59b);
        button(155, 62, 210, 171, 17, "排除所选施工", selected >= 0 && editable(), mx, my);
        button(156, 245, 210, 171, 17, "恢复所选施工", selected >= 0 && editable(), mx, my);
        button(151, 62, 240, 171, 17, "取消", true, mx, my);
        button(158, 245, 240, 171, 17, "应用此范围提示", editable() && (promptScope == 0 || selection.size() > 0), mx, my);
    }

    private void drawAnnotations(int mx, int my) {
        if (annotationState != state) {
            annotationState = state;
            annotationMarkers = HologramAnnotationMarkers.collect(state);
        }
        visibleMarkers.clear();
        int globalMarkerSlot = 0;
        for (HologramAnnotationMarkers.Marker marker : annotationMarkers) {
            if (marker.bounds == null) {
                marker.x = viewportX + viewportWidth - 18;
                marker.y = 64 + 15 * globalMarkerSlot++;
            } else {
                int[] b = marker.bounds;
                if (!allLayers && (layer < b[1] || layer > b[4])) continue;
                double[] p = HologramRenderer.project((b[0] + b[3]) / 2D, (b[1] + b[4]) / 2D, (b[2] + b[5]) / 2D);
                marker.x = (int) p[0];
                marker.y = (int) p[1];
                if (marker.x < viewportX + 8 || marker.x >= viewportX + viewportWidth - 8
                    || marker.y < 80
                    || marker.y >= 261) continue;
                // Keep nearby markers independently clickable without covering the annotated voxel.
                for (HologramAnnotationMarkers.Marker shown : visibleMarkers)
                    if (Math.abs(marker.x - shown.x) < 14 && Math.abs(marker.y - shown.y) < 14) marker.y += 15;
                if (marker.y >= 261) continue;
            }
            visibleMarkers.add(marker);
            int x = marker.x, y = marker.y;
            int color = marker.key.equals(pinnedAnnotation) ? 0xff7ce1ec
                : marker.key.startsWith("ae:") ? 0xffffb65e : 0xffe6c980;
            drawRect(x - 6, y - 5, x + 5, y + 6, 0xff514634);
            drawRect(x - 4, y - 7, x + 7, y + 4, color);
            drawRect(x + 5, y - 5, x + 8, y + 6, 0xff9e8353);
            text(marker.key.startsWith("ae:") ? "!" : "?", x - 1, y - 5, 8, 0xff172f36);
            if (marker.contains(mx, my)) {
                if (tooltip == null) tooltip = new ArrayList<>();
                else tooltip = new ArrayList<>(tooltip);
                tooltip.add(marker.title + " · 点击固定 / 再次点击收起");
                tooltip.addAll(fontRendererObj.listFormattedStringToWidth(marker.prompt, 250));
            }
            if (visibleMarkers.size() >= 64) break;
        }
        if (!pinnedAnnotation.isEmpty()) {
            HologramAnnotationMarkers.Marker pinned = null;
            for (HologramAnnotationMarkers.Marker marker : annotationMarkers)
                if (marker.key.equals(pinnedAnnotation)) pinned = marker;
            if (pinned == null) {
                pinnedAnnotation = "";
                return;
            }
            List<String> lines = fontRendererObj.listFormattedStringToWidth(pinned.prompt, viewportWidth - 20);
            pinnedAnnotationOffset = Math.max(0, Math.min(pinnedAnnotationOffset, Math.max(0, lines.size() - 4)));
            drawRect(viewportX + 1, 219, viewportX + viewportWidth - 1, 279, 0xf0172f36);
            text(
                pinned.title + " · 滚轮查看 · Esc 收起",
                viewportX + 8,
                224,
                viewportWidth - (pinned.key.startsWith("ae:") ? 108 : 32),
                0xffe59b);
            if (pinned.key.startsWith("ae:"))
                button(184, viewportX + viewportWidth - 104, 221, 78, 15, "AE 全部详情", true, mx, my);
            drawRect(viewportX + viewportWidth - 20, 221, viewportX + viewportWidth - 5, 234, 0xff344d51);
            text("×", viewportX + viewportWidth - 16, 224, 9, 0xffe59b);
            for (int i = 0; i < 4 && i + pinnedAnnotationOffset < lines.size(); i++)
                text(lines.get(i + pinnedAnnotationOffset), viewportX + 8, 237 + i * 10, viewportWidth - 16, 0xb9d5d4);
        }
    }

    private boolean toggleAnnotationAt(int mx, int my) {
        for (Control control : controls) if (control.id == 184 && control.contains(mx, my)) {
            activate(184);
            return true;
        }
        if (!pinnedAnnotation.isEmpty() && mx >= viewportX + viewportWidth - 20
            && mx < viewportX + viewportWidth - 5
            && my >= 221
            && my < 234) {
            pinnedAnnotation = "";
            return true;
        }
        for (int i = visibleMarkers.size() - 1; i >= 0; i--) {
            HologramAnnotationMarkers.Marker marker = visibleMarkers.get(i);
            if (!marker.contains(mx, my)) continue;
            pinnedAnnotation = marker.key.equals(pinnedAnnotation) ? "" : marker.key;
            pinnedAnnotationOffset = 0;
            return true;
        }
        return false;
    }

    private void receiveAeReview() {
        if (areaImporting() || aeReviewNavigation != null) {
            aeReview = null;
            return;
        }
        if (aeReview != null && !aeReview.review) {
            aeReview = HologramAeReviewModel.inspection(state);
            return;
        }
        NBTTagCompound dto = state.data.getCompoundTag("aeSaveReview");
        if (aeReview != null && (!aeReview.matches(state.session, revision())
            || !aeReview.transactionId.equals(dto.getString("transactionId")))) aeReview = null;
        if (dto.getString("transactionId")
            .isEmpty() || !state.session.equals(dto.getString("session"))
            || dto.getLong("planRevision") != revision()) return;
        HologramAeReviewModel next = new HologramAeReviewModel(dto, true);
        if (AE_REVIEW_GATE.first(next)) {
            aeReview = next;
            aeReviewTab = aeReviewOffset = aeDetailOffset = 0;
            aeReviewGroup = aeReviewSelected = -1;
            dragging = boxSelecting = inventoryDragging = false;
            gizmo.cancel();
        }
    }

    private List<HologramAeReviewModel.Issue> aeIssueRows() {
        if (aeReviewGroup >= 0 && aeReviewGroup < aeReview.groups.size())
            return aeReview.groups.get(aeReviewGroup).rows;
        return aeReview.issues;
    }

    private int aeRowCount() {
        return aeReviewTab == 0 ? aeReview.groups.size()
            : aeReviewTab == 1 ? aeIssueRows().size() : aeReview.materials.size();
    }

    private void aePage(int delta) {
        aeReviewOffset = Math.max(0, Math.min(Math.max(0, aeRowCount() - 5), aeReviewOffset + delta));
    }

    private void drawAeReview(int mx, int my) {
        controls.clear();
        tooltip = null;
        GL11.glDepthMask(true);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        drawRect(30, 42, 650, 302, 0xfc172f36);
        text(aeReview.review ? "AE 保存综合告知 · " + aeReview.name : "AE 预览综合提示", 42, 49, 598, 0xffe59b);
        text("支持的存储元件仅保留同类型空元件；其中物品 / 流体 / 外置内容不保存。", 42, 63, 598, 0xe5c28d);
        text(
            "保存等级 " + deidentificationChoice(false)
                + " · 当前 "
                + state.data.getInteger("blueprintDeidentificationLevel")
                + " · 推荐 "
                + state.data.getInteger("deidentificationRecommendedLevel")
                + "；源世界、源元件和源库存不改变。",
            42,
            75,
            598,
            0xe5c28d);
        text("编码配方、升级卡、空元件需要施工物料；ghost 配置免费；缺料仍可保存。", 42, 87, 598, 0xa9d6cf);
        text(
            "完整报告 " + aeReview.issues.size()
                + " 项 · 注意 "
                + aeReview.warningCount
                + " · 阻塞 "
                + aeReview.blockerCount
                + " · 物料 "
                + aeReview.materials.size()
                + " 行",
            42,
            100,
            598,
            0xf2dca5);
        button(185, 42, 114, 120, 17, "同类相邻汇总", true, mx, my);
        button(186, 168, 114, 120, 17, "全部问题", true, mx, my);
        button(187, 294, 114, 120, 17, "施工物料", true, mx, my);
        button(188, 528, 114, 49, 17, "上一页", aeReviewOffset > 0, mx, my);
        button(189, 583, 114, 55, 17, "下一页", aeReviewOffset + 5 < aeRowCount(), mx, my);
        aePage(0);
        text(
            (aeReviewTab == 0 ? "同类相邻问题归组；点击展开每条位置与详情"
                : aeReviewTab == 1 ? (aeReviewGroup >= 0 ? "该类全部问题 · 点击「全部问题」解除筛选" : "全部问题 · 点击条目查看完整路径与说明")
                    : "服务端精确物料 · 尚缺材料仅作提醒")
                + " · "
                + Math.min(aeReviewOffset + 1, aeRowCount())
                + "—"
                + Math.min(aeReviewOffset + 5, aeRowCount())
                + "/"
                + aeRowCount(),
            42,
            136,
            596,
            0x8bdddf);
        for (int i = 0; i < 5 && aeReviewOffset + i < aeRowCount(); i++) {
            int row = aeReviewOffset + i, y = 151 + i * 17;
            if (aeReviewTab == 0) {
                HologramAeReviewModel.Group group = aeReview.groups.get(row);
                button(
                    40000 + row,
                    42,
                    y,
                    596,
                    16,
                    ("blocker".equals(group.severity) ? "阻塞 · " : "注意 / 说明 · ") + group
                        .title() + " × " + group.count + " · " + group.rows.size() + " 条记录（展开）",
                    true,
                    mx,
                    my);
            } else if (aeReviewTab == 1) {
                HologramAeReviewModel.Issue issue = aeIssueRows().get(row);
                button(
                    50000 + row,
                    42,
                    y,
                    596,
                    16,
                    (row == aeReviewSelected ? "▶ " : "") + issue
                        .title() + " × " + issue.count + " · " + issue.x + "," + issue.y + "," + issue.z,
                    true,
                    mx,
                    my);
            } else {
                HologramAeReviewModel.Material material = aeReview.materials.get(row);
                text(material.line(), 46, y + 3, 588, "0".equals(material.missing) ? 0xa9d6cf : 0xffcf88);
                if (mx >= 42 && mx < 638 && my >= y && my < y + 16) {
                    tooltip = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(material.line(), 400));
                    tooltip.add("类型 " + material.type + " · " + material.key);
                }
            }
        }
        List<String> detail = new ArrayList<>();
        HologramAeReviewModel.Issue selectedIssue = null;
        if (aeReviewTab == 1 && aeReviewSelected >= 0 && aeReviewSelected < aeIssueRows().size()) {
            selectedIssue = aeIssueRows().get(aeReviewSelected);
            detail.addAll(
                fontRendererObj.listFormattedStringToWidth(
                    "[" + HologramUiText.severity(selectedIssue.severity)
                        + "] "
                        + selectedIssue.title()
                        + " · "
                        + selectedIssue.x
                        + ","
                        + selectedIssue.y
                        + ","
                        + selectedIssue.z
                        + " · 数量 "
                        + selectedIssue.count,
                    488));
            detail.addAll(fontRendererObj.listFormattedStringToWidth(selectedIssue.explanation(), 488));
            detail.addAll(fontRendererObj.listFormattedStringToWidth(selectedIssue.guidance(), 488));
            detail.addAll(
                fontRendererObj
                    .listFormattedStringToWidth("技术路径：" + selectedIssue.path + " · 槽 " + selectedIssue.slotId, 488));
            detail.addAll(
                fontRendererObj
                    .listFormattedStringToWidth("技术诊断：" + selectedIssue.code + " · " + selectedIssue.message, 488));
        } else detail.add(
            aeReview.valid
                ? aeReview.blockerCount > 0 ? "存在阻塞：展开问题并打开相关容器修正 / 清除，然后重新保存。" : "请查看汇总、完整问题和物料后确认；取消不会写入蓝图库。"
                : "完整报告不符合协议，继续保存已禁用；取消并重新请求保存。");
        aeDetailOffset = Math.max(0, Math.min(Math.max(0, detail.size() - 3), aeDetailOffset));
        for (int i = 0; i < 3 && i + aeDetailOffset < detail.size(); i++)
            text(detail.get(i + aeDetailOffset), 42, 241 + i * 10, 488, 0xe5c28d);
        button(190, 536, 238, 44, 15, "上文", aeDetailOffset > 0, mx, my);
        button(191, 586, 238, 52, 15, "下文", aeDetailOffset + 3 < detail.size(), mx, my);
        button(
            192,
            536,
            258,
            102,
            15,
            selectedIssue != null && "replaceCell".equals(selectedIssue.resolution) ? "返回替换 / 删除" : "打开相关容器",
            selectedIssue != null && selectedIssue.location && editable() && pendingSequence < 0,
            mx,
            my);
        button(182, 42, 279, 170, 17, aeReview.review ? "取消保存" : "关闭详情", true, mx, my);
        if (aeReview.review) button(
            181,
            224,
            279,
            198,
            17,
            aeReview.blockerCount > 0 ? "请先解除阻塞" : "已核对 · 继续保存",
            aeReview.canConfirm && aeReview.matches(state.session, revision()) && pendingSequence < 0,
            mx,
            my);
        button(
            216,
            426,
            279,
            102,
            17,
            "移除阻塞方块",
            editable() && state.data.getBoolean("virtual") && aeReview.blockerCount > 0,
            mx,
            my);
        button(214, 536, 279, 102, 17, "一键去标识", editable() && state.data.getBoolean("virtual"), mx, my);
    }

    private void activateAeReview(int id) {
        if (aeReview == null) return;
        if (id == 214 || id == 216) {
            deidentificationLevel = deidentificationChoice(false);
            activateDeidentification(id);
            return;
        }
        if (id == 181) {
            if (!aeReview.canConfirm || !aeReview.matches(state.session, revision()) || pendingSequence >= 0) return;
            NBTTagCompound exact = aeReview.action();
            aeReview = null;
            presetSaveSequence = sequence + 1;
            send("confirmAeSave", exact, true);
        } else if (id == 182) {
            HologramAeReviewModel closed = aeReview;
            aeReview = null;
            if (closed.review && HologramLibraryClient.dedicated()) HologramLibraryClient.INSTANCE.cancelDraftReview();
            if (closed.review) send("cancelAeSave", closed.action(), false);
        } else if (id >= 185 && id <= 187) {
            aeReviewTab = id - 185;
            aeReviewGroup = aeReviewSelected = -1;
            aeReviewOffset = aeDetailOffset = 0;
        } else if (id == 188 || id == 189) aePage(id == 188 ? -5 : 5);
        else if (id == 190 || id == 191) aeDetailOffset = Math.max(0, aeDetailOffset + (id == 190 ? -3 : 3));
        else if (id == 192 && aeReviewTab == 1 && aeReviewSelected >= 0 && aeReviewSelected < aeIssueRows().size()) {
            HologramAeReviewModel.Issue issue = aeIssueRows().get(aeReviewSelected);
            if (!issue.location || !editable() || pendingSequence >= 0) return;
            if (!aeReview.matches(state.session, revision())) {
                notice = "报告已更新，请重新查看当前问题后定位。";
                return;
            }
            activateAeReview(182);
            beginAeReviewNavigation(issue);
        } else if (id >= 40000 && id < 48192 && aeReviewTab == 0) {
            int row = id - 40000;
            if (row >= aeReview.groups.size()) return;
            aeReviewGroup = row;
            aeReviewTab = 1;
            aeReviewOffset = aeDetailOffset = 0;
            aeReviewSelected = -1;
        } else if (id >= 50000 && id < 58192 && aeReviewTab == 1) {
            int row = id - 50000;
            if (row >= aeIssueRows().size()) return;
            aeReviewSelected = row;
            aeDetailOffset = 0;
        }
    }

    private void beginAeReviewNavigation(HologramAeReviewModel.Issue issue) {
        boolean replace = "replaceCell".equals(issue.resolution);
        if (!HologramAeReviewNavigation.needsSelection(state.data.getString("activeStructure"), issue.structureId)) {
            finishAeReviewNavigation(issue.x, issue.y, issue.z, issue.structureId, replace);
            return;
        }
        cancelQueuedEdits("正在定位相关结构，已取消等待中的编辑");
        aeReviewNavigation = new HologramAeReviewNavigation(
            state.session,
            sequence + 1,
            revision(),
            issue.structureId,
            issue.x,
            issue.y,
            issue.z,
            replace);
        NBTTagCompound request = new NBTTagCompound();
        request.setString("structureId", issue.structureId);
        request.setLong("planRevision", revision());
        request.setBoolean("virtualOnly", true);
        send("selectStructure", request, true);
        notice = "正在切换到相关结构，请稍候…";
    }

    boolean hasPendingAeReviewNavigation() {
        return aeReviewNavigation != null;
    }

    private void completeAeReviewNavigation() {
        if (aeReviewNavigation == null) return;
        HologramAeReviewNavigation pending = aeReviewNavigation;
        HologramAeReviewNavigation.Result result = pending.observe(
            state.session,
            state.data.getLong("uiSequence"),
            revision(),
            state.data.getString("activeStructure"),
            areaImporting(),
            editable() && state.data.getBoolean("virtual"));
        if (result == HologramAeReviewNavigation.Result.WAITING) return;
        aeReviewNavigation = null;
        if (result == HologramAeReviewNavigation.Result.READY)
            finishAeReviewNavigation(pending.x, pending.y, pending.z, pending.structureId, pending.replaceCell);
        else notice = pending.failure() + " " + state.data.getString("status");
    }

    private void finishAeReviewNavigation(int x, int y, int z, String structureId, boolean replace) {
        HologramState.Cell target = null;
        for (HologramState.Cell cell : state.cells) if (cell.x == x && cell.y == y
            && cell.z == z
            && (structureId.isEmpty() || cell.structureIds.isEmpty() || cell.belongsToStructure(structureId))) {
                target = cell;
                break;
            }
        if (target == null || !state.data.getString("activeStructure")
            .equals(structureId) || !editable()) {
            notice = "相关方块或结构已变化，请重新查看当前报告后定位。";
            return;
        }
        selection.load(new int[] { target.index }, target.index, state.cells.size());
        selected = target.index;
        syncTargetSelectors();
        if (replace) notice = "请替换或删除 " + x + "," + y + "," + z + " 的蓝图方块，再重新保存。";
        else openAePreview(x, y, z, structureId);
    }

    private void cancelAeReviewNavigation() {
        if (aeReviewNavigation == null) return;
        aeReviewNavigation.cancel();
        aeReviewNavigation = null;
        notice = "已取消定位。";
    }

    private void drawAeReviewNavigation(int mx, int my) {
        controls.clear();
        tooltip = null;
        drawRect(90, 96, 590, 248, 0xfc172f36);
        text("正在定位相关蓝图结构…", 110, 114, 460, 0xffe59b);
        text("结构：" + aeReviewNavigation.structureId, 110, 139, 460, 0x8bdddf);
        text("切换完成后，将自动定位到对应方块。", 110, 161, 460, 0xa9d6cf);
        button(194, 220, 205, 240, 20, "取消定位 · Esc / E", true, mx, my);
    }

    /** Read-only observations for the real socket driver. Clicking uses existing smokeClickControl(). */
    NBTTagCompound smokeAeUiState() {
        requireInteractionSmoke();
        if (captureRenderSmokeAction)
            throw new IllegalStateException("AE live observations require actual network dispatch");
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("modal", aeReview != null);
        result.setBoolean("awaitingPreview", awaitingAePreview);
        result.setBoolean("awaitingNavigation", aeReviewNavigation != null);
        if (aeReviewNavigation != null) {
            result.setString("navigationStructure", aeReviewNavigation.structureId);
            result.setLong("navigationSequence", aeReviewNavigation.selectionSequence);
        }
        result.setString("pinnedWarning", pinnedAnnotation.startsWith("ae:") ? pinnedAnnotation : "");
        result.setBoolean("rootBlueprintTab", libraryDrawer);
        result.setLong("pendingSequence", pendingSequence);
        result.setInteger("tab", aeReviewTab);
        result.setInteger("offset", aeReviewOffset);
        result.setInteger("group", aeReviewGroup);
        result.setInteger("selectedIssue", aeReviewSelected);
        result.setInteger("detailOffset", aeDetailOffset);
        if (aeReview == null) return result;
        result.setBoolean("saveReview", aeReview.review);
        result.setBoolean("valid", aeReview.valid);
        result.setBoolean(
            "canConfirm",
            aeReview.canConfirm && aeReview.matches(state.session, revision()) && pendingSequence < 0);
        result.setString("transactionId", aeReview.transactionId);
        result.setString("session", aeReview.session);
        result.setLong("planRevision", aeReview.revision);
        result.setInteger("issueCount", aeReview.issues.size());
        result.setInteger("groupCount", aeReview.groups.size());
        result.setInteger("warningCount", aeReview.warningCount);
        result.setInteger("blockerCount", aeReview.blockerCount);
        result.setInteger("materialCount", aeReview.materials.size());
        result.setInteger("filteredRowCount", aeRowCount());
        net.minecraft.nbt.NBTTagList visible = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < 5 && aeReviewOffset + i < aeRowCount(); i++) {
            int index = aeReviewOffset + i;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("index", index);
            if (aeReviewTab == 0) {
                HologramAeReviewModel.Group group = aeReview.groups.get(index);
                row.setInteger("controlId", 40000 + index);
                row.setString("code", group.code);
                row.setString("severity", group.severity);
                row.setLong("count", group.count);
                row.setInteger("issueCount", group.rows.size());
            } else if (aeReviewTab == 1) {
                HologramAeReviewModel.Issue issue = aeIssueRows().get(index);
                row.setInteger("controlId", 50000 + index);
                row.setString("code", issue.code);
                row.setString("severity", issue.severity);
                row.setString("path", issue.path);
                row.setString("slotId", issue.slotId);
                row.setString("structureId", issue.structureId);
                row.setString("resolution", issue.resolution);
                row.setInteger("x", issue.x);
                row.setInteger("y", issue.y);
                row.setInteger("z", issue.z);
                row.setLong("count", issue.count);
            } else {
                HologramAeReviewModel.Material material = aeReview.materials.get(index);
                row.setString("name", material.name);
                row.setString("key", material.key);
                row.setString("required", material.required);
                row.setString("available", material.available);
                row.setString("missing", material.missing);
            }
            visible.appendTag(row);
        }
        result.setTag("visibleRows", visible);
        return result;
    }

    net.minecraft.nbt.NBTTagList smokeAeWarningMarkers() {
        requireInteractionSmoke();
        if (captureRenderSmokeAction)
            throw new IllegalStateException("AE live observations require actual network dispatch");
        net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
        for (HologramAnnotationMarkers.Marker marker : visibleMarkers) {
            if (!marker.key.startsWith("ae:")) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setString("key", marker.key);
            row.setString("title", marker.title);
            row.setIntArray("screenPoint", smokePreviewPoint(marker.x, marker.y));
            row.setBoolean("pinned", marker.key.equals(pinnedAnnotation));
            if (marker.bounds != null) row.setIntArray("bounds", marker.bounds.clone());
            rows.appendTag(row);
        }
        return rows;
    }

    /** Real GuiScreen mouse/key routing; no server/FML completion is inferred from this hook. */
    String smokeAeReviewGui() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("UI smoke disabled");
        NBTTagCompound fixture = (NBTTagCompound) state.data.copy();
        fixture.setString("session", state.session + ":ae-review-fixture:" + System.nanoTime());
        fixture.setLong("planRevision", 77);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("blueprintPreview", false);
        fixture.setBoolean("presetBrowseForPlacement", false);
        fixture.setBoolean("capturePending", false);
        fixture.setInteger("job", 0);
        NBTTagCompound dto = new NBTTagCompound();
        dto.setString("session", fixture.getString("session"));
        dto.setString("transactionId", "gui-review-one");
        dto.setString("name", "AE GUI review fixture");
        dto.setLong("planRevision", 77);
        dto.setBoolean("canConfirm", true);
        net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < 7; i++) {
            NBTTagCompound issue = new NBTTagCompound();
            issue.setString("code", "ae.cell_contents_cleared");
            issue.setString("severity", "warning");
            issue.setString("path", "inv/item" + i);
            issue.setString("message", "Cell contents are omitted; original source unchanged.");
            issue.setLong("count", 1);
            issue.setInteger("x", i);
            issue.setInteger("y", 0);
            issue.setInteger("z", 0);
            rows.appendTag(issue);
        }
        dto.setTag("issues", rows);
        dto.setInteger("issueCount", 7);
        dto.setInteger("warningCount", 7);
        dto.setInteger("blockerCount", 0);
        fixture.setTag("aeSaveReview", dto);
        HologramScreen probe = new HologramScreen(new HologramState(fixture));
        probe.setWorldAndResolution(mc, width, height);
        probe.captureRenderSmokeAction = true;
        GuiScreen originalGui = mc.currentScreen;
        try {
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(186);
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(189);
            if (probe.aeReviewOffset != 2 || probe.aeIssueRows()
                .size() != 7) throw new IllegalStateException("AE issue paging lost rows");
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(181);
            if (probe.capturedRenderSmokeAction == null
                || !"confirmAeSave".equals(probe.capturedRenderSmokeAction.getString("op"))
                || !"gui-review-one".equals(probe.capturedRenderSmokeAction.getString("transactionId"))
                || probe.capturedRenderSmokeAction.getLong("planRevision") != 77)
                throw new IllegalStateException("AE save confirmation did not bind exact review");
            probe.updateState(new HologramState(fixture));
            if (probe.aeReview != null) throw new IllegalStateException("Same AE review nonce opened twice");
            dto.setString("transactionId", "gui-review-blocked");
            rows.getCompoundTagAt(6)
                .setString("severity", "blocker");
            dto.setInteger("warningCount", 6);
            dto.setInteger("blockerCount", 1);
            fixture.setTag("aeSaveReview", dto);
            fixture.setLong("uiSequence", probe.sequence);
            probe.updateState(new HologramState(fixture));
            probe.drawScreen(0, 0, 0);
            probe.capturedRenderSmokeAction = null;
            probe.smokeClickDrawnControl(181);
            if (probe.capturedRenderSmokeAction != null || probe.aeReview == null)
                throw new IllegalStateException("Blocked AE save emitted confirmation");
            probe.keyTyped('\0', Keyboard.KEY_ESCAPE);
            if (probe.aeReview != null || !"cancelAeSave".equals(probe.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("AE modal Esc failed to cancel without closing root");
            probe.drawScreen(0, 0, 0);
            probe.smokeControlPoint(148);
            probe.smokeControlPoint(149);
            if (mc.currentScreen != originalGui) throw new IllegalStateException("AE modal Esc replaced the root GUI");
            probe.updateState(new HologramState(fixture));
            if (probe.aeReview != null) throw new IllegalStateException("Cancelled AE review reopened");
            fixture.removeTag("aeSaveReview");
            if (fixture.getTagList("cells", 10)
                .tagCount() > 0) {
                NBTTagCompound member = fixture.getTagList("cells", 10)
                    .getCompoundTagAt(0);
                member.setString("structureId", "gui-ae-structure");
                for (int i = 0; i < rows.tagCount(); i++) {
                    NBTTagCompound issue = rows.getCompoundTagAt(i);
                    issue.setInteger("x", member.getInteger("x"));
                    issue.setInteger("y", member.getInteger("y"));
                    issue.setInteger("z", member.getInteger("z"));
                    issue.setString("structureId", "gui-ae-structure");
                }
            }
            fixture.setTag("aeIssues", rows);
            probe.updateState(new HologramState(fixture));
            probe.capturedRenderSmokeAction = null;
            probe.drawScreen(0, 0, 0);
            HologramAnnotationMarkers.Marker reportMarker = null;
            int structureMarkers = 0;
            for (HologramAnnotationMarkers.Marker marker : probe.annotationMarkers)
                if ("ae:structure:gui-ae-structure".equals(marker.key)) structureMarkers++;
            for (HologramAnnotationMarkers.Marker marker : probe.visibleMarkers)
                if ("ae:report".equals(marker.key)) reportMarker = marker;
            if (reportMarker == null || fixture.getTagList("cells", 10)
                .tagCount() > 0 && structureMarkers != 1)
                throw new IllegalStateException("AE warnings lack a complete report or one structural aggregate");
            int[] point = probe.smokePreviewPoint(reportMarker.x, reportMarker.y);
            probe.drawScreen(point[0], point[1], 0);
            if (probe.tooltip == null || !probe.tooltip.toString()
                .contains("存储元件内容清空"))
                throw new IllegalStateException("AE warning icon hover did not expand the summary");
            probe.mouseClicked(point[0], point[1], 0);
            probe.mouseMovedOrUp(point[0], point[1], 0);
            probe.drawScreen(0, 0, 0);
            if (!"ae:report".equals(probe.pinnedAnnotation) || probe.capturedRenderSmokeAction != null)
                throw new IllegalStateException("AE warning pin did not persist as a local interaction");
            probe.smokeClickDrawnControl(184);
            if (probe.aeReview == null || probe.aeReview.review || probe.aeReview.issues.size() != 7)
                throw new IllegalStateException("AE warning details failed to retain every report row");
            probe.keyTyped('\0', Keyboard.KEY_ESCAPE);
            if (probe.aeReview != null || probe.capturedRenderSmokeAction != null)
                throw new IllegalStateException("AE preview details Esc sent a save or closed the root");
            probe.libraryDrawer = true;
            probe.presetNameField.setText("A");
            probe.presetNameField.setFocused(true);
            probe.keyTyped('e', Keyboard.KEY_E);
            if (!"Ae".equals(probe.presetNameField.getText()) || !probe.libraryDrawer
                || mc.currentScreen != originalGui)
                throw new IllegalStateException("Focused blueprint name swallowed E after AE modal cancellation");
            return "paged=7 nonce/revision=bound blocker=disabled Esc=cancel/root-tabs E=typing nonce=once warning=hover/pin/one-structure";
        } finally {
            probe.captureRenderSmokeAction = false;
        }
    }

    private void drawVersions(int mx, int my) {
        drawRect(50, 58, 428, 279, 0xff172f36);
        text("蓝图来源与 Mod 版本", 62, 68, 354, 0xf5e4b9);
        text("缺失 红 · 过时 橙 · 超前 青", 62, 85, 354, 0x94afb4);
        if (state.data.getBoolean("sourceMissing")) {
            String warning = "来源结构缺失：" + HologramUiText.reason(state.data.getString("sourceWarning"));
            text(warning, 62, 96, 354, 0xff7272);
            if (mx >= 62 && mx < 416 && my >= 94 && my < 106) {
                tooltip = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(warning, 300));
                tooltip.addAll(fontRendererObj.listFormattedStringToWidth(state.data.getString("sourceClass"), 300));
            }
        }
        NBTTagList versions = state.data.getTagList("modVersions", 10);
        versionsOffset = Math.max(0, Math.min(versionsOffset, Math.max(0, versions.tagCount() - 12)));
        for (int i = versionsOffset; i < Math.min(versions.tagCount(), versionsOffset + 12); i++) {
            NBTTagCompound row = versions.getCompoundTagAt(i);
            String condition = row.getString("state");
            int color = "missing".equals(condition) ? 0xff7272
                : "outdated".equals(condition) ? 0xffbd72 : "ahead".equals(condition) ? 0x69d7ed : 0xa2e8b8;
            int y = 106 + (i - versionsOffset) * 11;
            String label = row.getString("modId") + " · 蓝图 "
                + row.getString("saved")
                + " → 当前 "
                + row.getString("current");
            text(label, 62, y, 354, color);
            if (mx >= 62 && mx < 416 && my >= y && my < y + 11)
                tooltip = fontRendererObj.listFormattedStringToWidth(label, 300);
        }
        HologramState.Cell cell = selectedCell();
        if (cell != null && (cell.missingBlock || cell.sourceMissing)) {
            String warning = cell.sourceMissing ? "来源结构缺失：" + HologramUiText.reason(cell.sourceWarning)
                : "缺失方块 " + cell.wantId + " · " + cell.sourceMod + " " + cell.sourceVersion;
            text(warning, 62, 240, 354, 0xff7272);
            if (mx >= 62 && mx < 416 && my >= 238 && my < 252) {
                tooltip = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(warning, 300));
                if (!cell.sourceClass.isEmpty())
                    tooltip.addAll(fontRendererObj.listFormattedStringToWidth(cell.sourceClass, 300));
            }
        }
        button(151, 62, 255, 171, 17, "返回", true, mx, my);
        button(160, 245, 255, 171, 17, "校准为当前版本", editable(), mx, my);
    }

    private NBTTagList networkGroups() {
        return state.data.getCompoundTag("networkBindings")
            .getTagList("groups", 10);
    }

    private String selectedNetworkGroup() {
        NBTTagList cells = state.data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            if (row.getInteger("index") == selected) return row.getString("networkGroup");
        }
        return "";
    }

    private void loadNetworkHintField() {
        if (networkHintField == null) return;
        networkHintField.setText(
            networkGroupIndex >= 0 && networkGroupIndex < networkGroups().tagCount()
                ? networkGroups().getCompoundTagAt(networkGroupIndex)
                    .getString("prompt")
                : "");
    }

    private void drawNetworkBindings(int mx, int my) {
        controls.clear();
        tooltip = null;
        drawRect(35, 50, 645, 299, 0xfc172f36);
        boolean terminalBinding = state.data.getCompoundTag("networkBindings")
            .getBoolean("terminalBinding");
        text(terminalBinding ? "量子网络类群 · 逐组绑定" : "量子网络类群 · 保留原网络", 50, 64, 570, 0xffe59b);
        text(
            terminalBinding ? "逐组选择已绑定目标网络、且你有权使用的量子终端；全部就绪才可放置。" : "保留原锚点与维度，系统按原网络的安全要求验证；换存档无法使用，请按原因核对。",
            50,
            81,
            570,
            0xa9d6cf);
        NBTTagList groups = networkGroups();
        networkGroupIndex = Math.max(0, Math.min(networkGroupIndex, groups.tagCount() - 1));
        networkGroupOffset = Math.max(0, Math.min(networkGroupOffset, Math.max(0, groups.tagCount() - 5)));
        for (int i = networkGroupOffset; i < Math.min(groups.tagCount(), networkGroupOffset + 5); i++) {
            NBTTagCompound row = groups.getCompoundTagAt(i);
            String groupName = row.getString("name");
            if (groupName.isEmpty()) groupName = row.getString("hint");
            if (groupName.isEmpty()) groupName = row.getString("id");
            String label = (i == networkGroupIndex ? "▶ " : "") + groupName
                + " · "
                + row.getInteger("cells")
                + " 格 · "
                + (row.getBoolean("bound") ? "已就绪" : "待处理");
            button(60000 + i, 50, 103 + (i - networkGroupOffset) * 22, 325, 19, label, pendingSequence < 0, mx, my);
        }
        NBTTagList inventory = state.data.getCompoundTag("networkBindings")
            .getTagList("inventory", 10);
        boolean selectedTerminalBinding = groups.tagCount() > 0 && groups.getCompoundTagAt(networkGroupIndex)
            .getBoolean("terminalBinding");
        boolean privateSecurityCheck = groups.tagCount() > 0 && !selectedTerminalBinding
            && networkSecurityReason(
                groups.getCompoundTagAt(networkGroupIndex)
                    .getString("reason"));
        text(
            selectedTerminalBinding ? "主背包与快捷栏 · 选择终端" : privateSecurityCheck ? "原网安全校验 · 自动查验背包" : "保留原网络 · 此处不改绑",
            405,
            101,
            216,
            0xa9d6cf);
        for (int slot = 0; slot < 36; slot++) {
            int x = 405 + (slot % 9) * 23;
            int y = slot < 9 ? 194 : 121 + ((slot - 9) / 9) * 23;
            NBTTagCompound row = null;
            for (int i = 0; i < inventory.tagCount(); i++) if (inventory.getCompoundTagAt(i)
                .getInteger("slot") == slot) row = inventory.getCompoundTagAt(i);
            boolean available = row != null && row.getBoolean("available");
            button(61000 + slot, x, y, 21, 21, "", selectedTerminalBinding && available && pendingSequence < 0, mx, my);
            if (row != null) {
                ItemStack display = ItemStack.loadItemStackFromNBT(row.getCompoundTag("item"));
                if (display != null) drawItem(display, x + 2, y + 2);
                if (mx >= x && mx < x + 21 && my >= y && my < y + 21) {
                    tooltip = new ArrayList<>();
                    tooltip.add(row.getString("name"));
                    tooltip.add(
                        !selectedTerminalBinding ? "本类群保留原锚点，此处不能选择其他网络重新绑定"
                            : available ? "点击绑定所选网络类群" : HologramUiText.reason(row.getString("reason")));
                    if (selectedTerminalBinding && networkSecurityReason(row.getString("reason"))
                        || privateSecurityCheck) {
                        tooltip.addAll(
                            fontRendererObj.listFormattedStringToWidth(
                                selectedTerminalBinding ? "请使用已在对应网络的 AE 原生安全终端完成配对、且你有 AE 建造权限的量子终端。"
                                    : "已设安全配对的原网络：携带同网配对终端，系统自动验权；终端必须仍指向原网络。",
                                290));
                        tooltip.addAll(fontRendererObj.listFormattedStringToWidth("终端记名仅作描述；以原生安全配对和当前玩家权限为准。", 290));
                    }
                    if (!available) tooltip.add("技术诊断：" + row.getString("reason"));
                }
            }
        }
        if (groups.tagCount() > 0) {
            if (networkHintField != null) networkHintField.drawTextBox();
            NBTTagCompound selectedGroup = groups.getCompoundTagAt(networkGroupIndex);
            text(selectedGroup.getString("name") + " · " + selectedGroup.getString("hint"), 50, 242, 570, 0xffe59b);
            if (!selectedGroup.getBoolean("bound")) {
                text(HologramUiText.reason(selectedGroup.getString("reason")), 50, 257, 570, 0xff9292);
                if (mx >= 50 && mx < 620 && my >= 253 && my < 269) {
                    tooltip = new ArrayList<>(
                        fontRendererObj
                            .listFormattedStringToWidth(HologramUiText.reason(selectedGroup.getString("reason")), 380));
                    if (networkSecurityReason(selectedGroup.getString("reason"))) {
                        tooltip.addAll(
                            fontRendererObj.listFormattedStringToWidth(
                                selectedTerminalBinding ? "请完成对应网络的 AE 原生安全终端配对，并确认你有 AE 建造权限。"
                                    : "已设安全配对的原网络：携带同网配对终端，系统自动验权；保留原网络，不改绑到其他网络。",
                                380));
                        tooltip.addAll(
                            fontRendererObj
                                .listFormattedStringToWidth("安全网络配对后，拆除安全站也不会解除安全锁定；请恢复可用的配对与权限。网络启动中时请稍后重试。", 380));
                    }
                }
            }
        } else text("此蓝图没有需要重新绑定的量子网络组", 50, 242, 570, 0x94afb4);
        button(198, 50, 273, 95, 17, "上一组页", networkGroupOffset > 0, mx, my);
        button(199, 151, 273, 95, 17, "下一组页", networkGroupOffset + 5 < groups.tagCount(), mx, my);
        button(200, 252, 273, 123, 17, "应用本组提示", editable() && groups.tagCount() > 0 && pendingSequence < 0, mx, my);
        button(201, 405, 220, 210, 17, "取消 / 返回 · Esc / E", pendingSequence < 0, mx, my);
        button(
            197,
            405,
            273,
            210,
            17,
            "确认全部类群 · 返回放置",
            pendingSequence < 0 && HologramPlacementConfirmation.networksReady(state.data),
            mx,
            my);
    }

    private static boolean networkSecurityReason(String reason) {
        String lower = reason.toLowerCase(java.util.Locale.ROOT);
        return reason.contains("安全") || reason.contains("配对")
            || reason.contains("权限")
            || lower.contains("security")
            || lower.contains("permission");
    }

    private void activateNetworkBinding(int id) {
        if (id == 197 || id == 201) {
            if (id == 197 && !HologramPlacementConfirmation.networksReady(state.data)) return;
            networkDrawer = false;
            send("closeNetworkBindings", new NBTTagCompound(), true);
        } else if (id == 198) networkGroupOffset = Math.max(0, networkGroupOffset - 5);
        else if (id == 199) networkGroupOffset += 5;
        else if (id == 200 && networkHintField != null && networkGroups().tagCount() > 0) {
            NBTTagCompound action = new NBTTagCompound();
            action.setString(
                "group",
                networkGroups().getCompoundTagAt(networkGroupIndex)
                    .getString("id"));
            action.setString("hint", networkHintField.getText());
            send("editBlueprintNetworkHint", action, true);
        } else if (id >= 60000 && id < 61000) {
            networkGroupIndex = id - 60000;
            loadNetworkHintField();
        } else if (id >= 61000 && id < 61036 && networkGroupIndex < networkGroups().tagCount()) {
            if (!networkGroups().getCompoundTagAt(networkGroupIndex)
                .getBoolean("terminalBinding")) return;
            NBTTagCompound action = new NBTTagCompound();
            action.setString(
                "group",
                networkGroups().getCompoundTagAt(networkGroupIndex)
                    .getString("id"));
            action.setInteger("slot", id - 61000);
            send("bindBlueprintNetwork", action, true);
        }
    }

    private List<String> availableNbtKeys() {
        List<String> keys = new ArrayList<>();
        NBTTagList entries = editorNbtEntries();
        for (int i = 0; i < entries.tagCount(); i++) keys.add(
            entries.getCompoundTagAt(i)
                .getString("key"));
        return keys;
    }

    private void openNbtEditor(boolean placement) {
        nbtDrawer = true;
        nbtPlacement = placement;
        nbtOffset = 0;
        nbtCellScope = !placement && selected >= 0;
        nbtValueIndex = -1;
        nbtValueField = new GuiTextField(fontRendererObj, 62, 233, 286, 17);
        nbtValueField.setMaxStringLength(8192);
        nbtPathsView = false;
        nbtIgnoredPaths.clear();
        boolean customized = placement && state.data.getBoolean("placementNbtCustomized");
        nbtSelection.load(
            customized ? state.data.getBoolean("placementNbtAll")
                : !state.data.hasKey("blueprintNbtAll") || state.data.getBoolean("blueprintNbtAll"),
            state.data.getTagList(customized ? "placementNbtKeys" : "blueprintNbtKeys", 8),
            placement && state.data.hasKey("placementRetainCovers") ? state.data.getBoolean("placementRetainCovers")
                : !state.data.hasKey("blueprintRetainCovers") || state.data.getBoolean("blueprintRetainCovers"));
        if (nbtCellScope) {
            NBTTagCompound row = cellNbtRow();
            NBTTagList keys = new NBTTagList(), entries = row.getTagList("entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                if (entry.getBoolean("selected") || entry.getBoolean("blueprintSelected"))
                    keys.appendTag(new net.minecraft.nbt.NBTTagString(entry.getString("key")));
            }
            nbtSelection.load(!row.hasKey("all") || row.getBoolean("all"), keys, retainCovers);
            NBTTagList paths = row.getTagList("ignoredPaths", 8);
            for (int i = 0; i < paths.tagCount(); i++) nbtIgnoredPaths.add(paths.getStringTagAt(i));
        }
    }

    private NBTTagCompound cellNbtRow() {
        NBTTagList rows = state.data.getTagList("cellNbtEntries", 10);
        for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
            .getInteger("index") == selected) return rows.getCompoundTagAt(i);
        return new NBTTagCompound();
    }

    private NBTTagList editorNbtEntries() {
        return nbtCellScope && !nbtPlacement ? cellNbtRow().getTagList("entries", 10)
            : state.data.getTagList("nbtEntries", 10);
    }

    private void drawNbtEditor(int mx, int my) {
        drawRect(50, 58, 548, 304, 0xff172f36);
        text(
            nbtPlacement ? "本次放置的 NBT 匹配条目" : nbtCellScope ? "所选 " + selection.size() + " 格的 NBT" : "蓝图保存的 NBT 条目",
            62,
            68,
            474,
            0xf5e4b9);
        text(
            nbtPlacement ? "默认精确匹配蓝图；取消条目将降级本次放置，原蓝图保存选项保持不变。" : "以下为已捕获设备的实际根条目；默认全部保存，设备身份保护，覆盖板独立选择。",
            62,
            86,
            474,
            0x94afb4);
        button(170, 62, 101, 112, 17, "全部保留", true, mx, my);
        button(171, 180, 101, 112, 17, "取消普通条目", true, mx, my);
        if (!nbtPlacement && !nbtCellScope)
            button(195, 306, 101, 230, 17, "一键清除网络身份 / 外部坐标", editable() && state.data.getBoolean("virtual"), mx, my);
        if (nbtCellScope) button(
            229,
            306,
            101,
            230,
            17,
            "切换所选条目保留",
            nbtValueIndex >= 0 && !editorNbtEntries().getCompoundTagAt(nbtValueIndex)
                .getBoolean("protected"),
            mx,
            my);
        if (nbtPlacement)
            button(174, 306, 101, 230, 17, "本次保留覆盖板 " + (nbtSelection.retainCovers() ? "✓" : "×"), true, mx, my);
        NBTTagList entries = editorNbtEntries();
        if (nbtPathsView) {
            entries = new NBTTagList();
            NBTTagList paths = processingList("nbtPaths", 8);
            for (int i = 0; i < paths.tagCount(); i++) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setString("key", paths.getStringTagAt(i));
                entries.appendTag(entry);
            }
        }
        nbtOffset = Math.max(0, Math.min(nbtOffset, Math.max(0, entries.tagCount() - (nbtCellScope ? 5 : 7))));
        for (int i = nbtOffset; i < Math.min(entries.tagCount(), nbtOffset + (nbtCellScope ? 5 : 7)); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            String key = entry.getString("key"), category = entry.getString("category");
            String categoryLabel = "item".equals(category) || "items".equals(category) || "inventory".equals(category)
                ? "库存"
                : "fluid".equals(category) ? "流体" : "energy".equals(category) ? "电力" : "配置";
            int y = 124 + (i - nbtOffset) * 17;
            String label = (nbtPathsView ? nbtIgnoredPaths.contains(key) ? "忽略 × " : "保留 ✓ "
                : nbtSelection.selected(key) || entry.getBoolean("protected") ? "✓ " : "× ") + categoryLabel
                + " · "
                + (nbtCellScope && entry.hasKey("path") ? entry.getString("path") : key)
                + (entry.hasKey("type") ? " [" + entry.getString("type") + "] " + entry.getString("value") : "");
            button(17000 + i, 62, y, 474, 15, label, nbtCellScope || !entry.getBoolean("protected"), mx, my);
            if (mx >= 62 && mx < 536 && my >= y && my < y + 15) tooltip = fontRendererObj.listFormattedStringToWidth(
                (entry.hasKey("path")
                    ? entry.getString("path") + " [" + entry.getString("type") + "] = " + entry.getString("value")
                    : key)
                    + " · "
                    + (entry.getBoolean("protected") ? "服务端保护字段，只读"
                        : nbtCellScope ? "点击查看并编辑此值；所选格独立提交，服务端重新核价" : "取消后不保存/匹配这一根条目"),
                430);
        }
        if (entries.tagCount() == 0) text("当前模板未包含可选设备 NBT 条目", 62, 128, 474, 0x94afb4);
        if (nbtCellScope && !nbtPathsView && nbtValueIndex >= 0 && nbtValueIndex < editorNbtEntries().tagCount()) {
            NBTTagCompound entry = editorNbtEntries().getCompoundTagAt(nbtValueIndex);
            text(entry.getString("path") + " · 类型 " + entry.getString("type"), 62, 220, 474, 0x94afb4);
            nbtValueField.drawTextBox();
            button(217, 358, 233, 178, 17, "应用此值 · 服务端校验", editable() && entry.getBoolean("editable"), mx, my);
        }
        if (nbtCellScope) button(228, 62, 267, 474, 13, nbtPathsView ? "返回条目编辑" : "编辑忽略 NBT 路径（配置细节）", true, mx, my);
        else text("库存：背包 / AE · 流体：AE · 电力：无线电网；材料确认页可查看可用量与缺口。", 62, 270, 474, 0x94afb4);
        button(172, 62, 283, 230, 17, "取消", true, mx, my);
        button(173, 306, 283, 230, 17, nbtPlacement ? "仅应用本次降级" : "应用蓝图保存选项", editable(), mx, my);
        button(
            211,
            62,
            251,
            474,
            15,
            "去标识等级 " + deidentificationChoice(nbtPlacement) + " · 点击设置 0–10",
            editable() && state.data.getBoolean("virtual"),
            mx,
            my);
    }

    private int deidentificationChoice(boolean placement) {
        if (!placement
            && (state.data.getBoolean("blueprintProcessingComplete") || state.data.getBoolean("blueprintDirectSave")))
            return state.data.getInteger("blueprintDeidentificationLevel");
        HologramDeidentificationMemory memory = HologramDeidentificationMemory.client();
        return HologramDeidentificationMemory
            .level(state.data, placement, memory.choice(HologramDeidentificationMemory.source(state.data)));
    }

    private void offerPlacementDeidentification() {
        if (!placementReviewActive() || !state.data.getBoolean("placementDeidentificationRecommended")
            || state.data.getBoolean("placementDeidentificationChosen")
            || aeReview != null
            || HologramDeidentificationMemory.minimum(state.data) > 0
            || HologramDeidentificationMemory.client()
                .choice(HologramDeidentificationMemory.source(state.data)) != null)
            return;
        long nonce = state.data.getLong("placementReviewNonce");
        if (offeredDeidentificationNonce == nonce) return;
        offeredDeidentificationNonce = nonce;
        openDeidentification(true);
    }

    private void drawDeidentificationLibraryControl(int mx, int my) {
        button(
            211,
            35,
            284,
            407,
            17,
            "保存去标识等级 " + deidentificationChoice(false)
                + " · 推荐 "
                + state.data.getInteger("deidentificationRecommendedLevel")
                + " · 点击选择 0–10",
            editable() && !state.cells.isEmpty(),
            mx,
            my);
    }

    private void openDeidentification(boolean placement) {
        deidentificationPlacement = placement;
        deidentificationLevel = deidentificationChoice(placement);
        deidentificationDrawer = true;
        dragging = boxSelecting = inventoryDragging = false;
        gizmo.cancel();
        if (presetNameField != null) presetNameField.setFocused(false);
    }

    private void drawDeidentification(int mx, int my) {
        drawRect(40, 47, 650, 310, 0xff172f36);
        text(deidentificationPlacement ? "本次放置校验与去标识" : "保存校验与去标识", 54, 59, 580, 0xf5e4b9);
        int actual = state.data.getInteger(
            deidentificationPlacement ? "placementDeidentificationLevel" : "blueprintDeidentificationLevel");
        text(
            "当前等级 " + actual
                + " · 选择等级 "
                + deidentificationLevel
                + " · 推荐等级 "
                + state.data.getInteger("deidentificationRecommendedLevel"),
            54,
            82,
            580,
            0x8bdddf);
        int minimum = HologramDeidentificationMemory.minimum(state.data);
        text(
            minimum > 0 ? "已处理的数据无法还原；这份蓝图最低可选等级为 " + minimum + "。" : "等级 0 保留原始身份；等级越高，清除的外部身份与运行数据越多。",
            54,
            101,
            580,
            0xe5c28d);
        for (int level = 0; level <= 10; level++) button(
            22000 + level,
            54 + level * 53,
            122,
            48,
            20,
            (level == deidentificationLevel ? "✓ " : "") + level,
            level >= minimum,
            mx,
            my);
        text("1 身份与网络 · 2 外部坐标 · 3 频率 · 4 安全凭据 · 5 连接记录 · 6 屏幕 / 核心运行缓存", 54, 155, 580, 0xa9d6cf);
        text("7 监控身份 / 图表缓存 · 8 私人界面页 · 9 停机诊断 · 10 瞬时运行状态；配方数据保留", 54, 172, 580, 0xa9d6cf);
        text("记住你对这份蓝图的选择（含 0）；下次放置及 Alt 快捷预览沿用。", 54, 190, 580, 0x94afb4);
        text(
            deidentificationPlacement ? "请先修正保存数据或清理阻塞物，再重新核对材料并确认放置。" : "保存仍会检查全部数据；同类相邻提示合并展示，可展开查看每个位置。",
            54,
            208,
            580,
            0xe5c28d);
        button(
            214,
            54,
            233,
            278,
            20,
            "一键去标识处理 · 等级 " + Math.max(1, deidentificationLevel),
            editable() && state.data.getBoolean("virtual"),
            mx,
            my);
        button(215, 342, 233, 290, 20, "一键移除放置阻塞物", editable() && placementReviewActive(), mx, my);
        button(212, 54, 274, 278, 20, "取消", true, mx, my);
        button(213, 342, 274, 290, 20, deidentificationPlacement ? "记住并应用本次选择" : "记住此蓝图的保存选择", editable(), mx, my);
    }

    private boolean activateDeidentification(int id) {
        if (id == 211) {
            openDeidentification(confirmation && placementReviewActive() || nbtDrawer && nbtPlacement);
            return true;
        }
        if (!deidentificationDrawer && id != 214 && id != 215 && id != 216) return false;
        if (id >= 22000 && id <= 22010) {
            int level = id - 22000;
            if (level >= HologramDeidentificationMemory.minimum(state.data)) deidentificationLevel = level;
            return true;
        }
        if (id == 212) {
            deidentificationDrawer = false;
            return true;
        }
        if (id != 213 && id != 214 && id != 215 && id != 216) return false;
        if (!editable()) return true;
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", revision());
        request.setLong("placementReviewNonce", state.data.getLong("placementReviewNonce"));
        if (id == 216) {
            if (aeReview == null || aeReview.blockerCount == 0) return true;
            if (aeReview.review) activateAeReview(182);
            request.setBoolean("confirmDiscard", true);
            send("excludeBlueprintBlockers", request, true);
            return true;
        }
        if (id == 215) {
            if (!placementReviewActive()) return true;
            send("removeBlueprintObstructions", request, true);
            deidentificationDrawer = false;
            return true;
        }
        int level = id == 214 ? Math.max(1, deidentificationLevel) : deidentificationLevel;
        HologramDeidentificationMemory.client()
            .remember(HologramDeidentificationMemory.source(state.data), level);
        request.setInteger("deidentificationLevel", level);
        request.setInteger("level", level);
        request.setBoolean("deidentificationChosen", true);
        if (id == 214) {
            if (aeReview != null && aeReview.review) {
                // Explicitly cancel the exact old save review; processing never confirms that nonce.
                activateAeReview(182);
            }
            send("deidentifyBlueprint", request, true);
        } else if (deidentificationPlacement) send("configurePlacementDeidentification", request, true);
        else notice = "已记住保存去标识等级 " + level + "，保存时将重新校验";
        deidentificationDrawer = false;
        return true;
    }

    private void deidentificationSaveOptions(NBTTagCompound options) {
        HologramDeidentificationMemory memory = HologramDeidentificationMemory.client();
        Integer remembered = memory.choice(HologramDeidentificationMemory.source(state.data));
        options.setInteger("deidentificationLevel", deidentificationChoice(false));
        options.setBoolean(
            "deidentificationChosen",
            state.data.getBoolean("blueprintProcessingComplete") || remembered != null);
        if (state.data.getBoolean("blueprintProcessingComplete")) {
            options.setBoolean("retainNbt", state.data.getBoolean("blueprintRetainNbt"));
            options.setBoolean("retainCovers", state.data.getBoolean("blueprintRetainCovers"));
        }
        options.setLong("planRevision", revision());
    }

    private boolean selectedPresetExists() {
        if (HologramLibraryClient.dedicated()) return !HologramLibraryClient.INSTANCE.selectedRow(publicLibrary)
            .getString("id")
            .isEmpty();
        NBTTagList presets = state.data.getTagList("presets", 10);
        for (int i = 0; i < presets.tagCount(); i++) if (state.data.getString("selectedPresetId")
            .equals(
                presets.getCompoundTagAt(i)
                    .getString("id")))
            return true;
        return false;
    }

    private void revealSelectedPreset() {
        boolean dedicated = HologramLibraryClient.dedicated();
        NBTTagList presets = dedicated ? HologramLibraryClient.INSTANCE.rows(publicLibrary)
            : state.data.getTagList("presets", 10);
        String selectedId = dedicated ? HologramLibraryClient.INSTANCE.selected(publicLibrary)
            : state.data.getString("selectedPresetId");
        for (int i = 0; i < presets.tagCount(); i++) {
            if (!selectedId.equals(
                presets.getCompoundTagAt(i)
                    .getString("id")))
                continue;
            if (i < libraryOffset || i >= libraryOffset + 4)
                libraryOffset = Math.max(0, Math.min(i, presets.tagCount() - 4));
            return;
        }
    }

    NBTTagCompound smokeLibraryUiState() {
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("dedicated", HologramLibraryClient.dedicated());
        result.setBoolean("library", libraryDrawer);
        result.setBoolean("public", publicLibrary);
        result.setBoolean("blueprintTab", blueprintTab);
        result.setBoolean("processingFlow", processingFlow != null);
        result.setInteger("flowStage", processingFlow == null ? -1 : processingFlow.stage);
        result.setBoolean("flowChosen", processingFlow != null && processingFlow.chosen());
        result.setBoolean("flowQuantumGroupsConfirmed", quantumGroupsConfirmed());
        result.setTag(
            "quantumGroups",
            processingState().getTagList("quantumGroups", 10)
                .copy());
        result.setBoolean("flowFullReview", processingFlow != null && processingFlow.fullReview());
        result.setInteger("flowRequiredStages", processingFlow == null ? -1 : processingFlow.requiredStages());
        result.setBoolean("processingComplete", state.data.getBoolean("blueprintProcessingComplete"));
        result.setBoolean("processingRequired", state.data.getBoolean("blueprintProcessingRequired"));
        result.setBoolean("blueprintDirty", state.data.getBoolean("blueprintDirty"));
        result.setBoolean("blueprintSaved", state.data.getBoolean("blueprintSaved"));
        result.setBoolean("discardReview", discardReview);
        if (sourceSwitchProposal != null) result.setTag("sourceSwitchProposal", sourceSwitchProposal.copy());
        result.setBoolean("blueprintSavePending", state.data.getBoolean("blueprintSavePending"));
        result.setBoolean("overwriteReview", overwriteReview);
        result.setBoolean("processingPresetSaveReview", processingPresetSaveReview);
        result.setBoolean("overwriteMode", overwriteMode);
        result.setString("overwriteTarget", overwriteTarget);
        result.setInteger("libraryOffset", libraryOffset);
        result.setDouble("yaw", yaw);
        result.setDouble("pitch", pitch);
        result.setBoolean("nbtCellScope", nbtCellScope);
        result.setInteger("nbtValueIndex", nbtValueIndex);
        result.setTag("visibleNbtEntries", editorNbtEntries().copy());
        result.setString("openPurpose", state.data.getString("openPurpose"));
        result.setString("session", state.session);
        result.setString("serverSelectedPresetId", state.data.getString("selectedPresetId"));
        result.setString("selectedLocal", library.selected(false));
        result.setString("selectedPublic", library.selected(true));
        result.setLong("renderGeneration", renderGeneration);
        result.setLong("planRevision", revision());
        result.setLong("uiSequence", state.data.getLong("uiSequence"));
        result.setBoolean("presetDrawer", presetDrawer);
        result.setBoolean("hatchDrawer", hatchDrawer);
        result.setBoolean("drawer", drawer);
        result.setBoolean("confirmation", confirmation);
        result.setBoolean("deleteReview", !deletePresetId.isEmpty());
        result.setString("deletePresetId", deletePresetId);
        result.setBoolean("promptDrawer", promptDrawer);
        result.setBoolean("versionsDrawer", versionsDrawer);
        result.setBoolean("nbtDrawer", nbtDrawer);
        result.setBoolean("resetReview", resetReview);
        result.setBoolean("shortcutsDrawer", shortcutsDrawer);
        result.setBoolean("positionDrawer", positionDrawer);
        result.setBoolean("sourceDrawer", sourceDrawer);
        result.setBoolean("roleDropdown", roleDropdown);
        result.setBoolean("networkDrawer", networkDrawer);
        result.setInteger("networkGroupIndex", networkGroupIndex);
        result.setBoolean("networkGroupsReady", HologramPlacementConfirmation.networksReady(state.data));
        result.setTag(
            "networkBindings",
            state.data.getCompoundTag("networkBindings")
                .copy());
        result.setBoolean("aeReview", aeReview != null);
        result.setBoolean("aeReviewNavigation", aeReviewNavigation != null);
        result.setBoolean("adminDrawer", libraryAdminDrawer);
        result.setBoolean("busy", library.busy());
        result.setString("selected", library.selected(publicLibrary));
        result.setString("status", library.status());
        result.setInteger(
            "rows",
            library.rows(publicLibrary)
                .tagCount());
        result.setInteger("progressBytes", library.progressBytes());
        result.setInteger("totalBytes", library.totalBytes());
        result.setTag(
            "config",
            library.config()
                .copy());
        result.setTag(
            "entries",
            library.rows(publicLibrary)
                .copy());
        NBTTagList drawn = new NBTTagList();
        for (Control control : controls) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("id", control.id);
            row.setInteger("x", control.x);
            row.setInteger("y", control.y);
            row.setInteger("w", control.w);
            row.setInteger("h", control.h);
            row.setBoolean("enabled", control.enabled);
            row.setIntArray(
                "screenPoint",
                new int[] { originX + Math.round((control.x + control.w / 2F) * scale),
                    originY + Math.round((control.y + control.h / 2F) * scale) });
            drawn.appendTag(row);
            if (control.id == 207) result.setBoolean("adminVisible", true);
            if (control.id == 205) result.setBoolean("deleteEnabled", control.enabled);
            if (control.id == 201) result.setBoolean("switchVisible", true);
        }
        result.setTag("drawnControls", drawn);
        return result;
    }

    private void drawResetReview(int mx, int my) {
        drawRect(58, 76, 358, 246, 0xff172f36);
        text(resetCalibration ? "将规划校准为当前现场？" : "将规划重置为结构默认？", 70, 91, 274, 0xe4b85a);
        text(resetCalibration ? "识别现场材质信道，保留独立仓室与朝向。" : "清除自定义目标，恢复结构默认规划。", 70, 118, 274, 0xb9d5d4);
        text("仅更新规划，不施工，也不删除已存预设。", 70, 141, 274, 0xb9d5d4);
        text(resetCalibration ? "未识别的尺寸与信道保留，已识别项可继续修改。" : "操作前请确认当前手改目标是否需要保留。", 70, 162, 274, 0xe4b85a);
        button(136, 70, 211, 132, 21, "保留当前规划", true, mx, my);
        button(137, 212, 211, 132, 21, resetCalibration ? "确认校准为当前" : "确认重置为默认", editable(), mx, my);
    }

    private boolean initialStructureSetupRequired() {
        return !state.data.getBoolean("virtual") && state.data.getBoolean("presetAvailable")
            && state.data.getInteger("mode") == 0
            && !basicConfigured
            && !state.data.getBoolean("presetActive");
    }

    private void drawFooter(int mx, int my) {
        int job = state.data.getInteger("job");
        if (initialStructureSetupRequired()) {
            text("先完成仓室预设或手动配置", 452, 319, 138, 0x80efaa);
        } else button(
            50,
            452,
            315,
            138,
            17,
            placementReviewActive() ? "继续放置预检"
                : state.data.getBoolean("virtual") && !state.data.getBoolean("virtualPlacement") ? "从蓝图库放置"
                    : state.data.getBoolean("virtual") && !state.data.getBoolean("virtualLocked") ? "世界右键确定位置"
                        : new String[] { "确认构建", "确认替换", "确认拆除" }[Math.floorMod(state.data.getInteger("mode"), 3)],
            editable()
                && (!state.data.getBoolean("virtual")
                    || state.data.getBoolean("virtualPlacement") && state.data.getBoolean("virtualLocked"))
                && state.data.getBoolean("target")
                && (state.data.getBoolean("supported") || state.data.getInteger("mode") == 0),
            mx,
            my);
        button(51, 594, 315, 37, 17, job == 2 ? "继续" : "暂停", pendingSequence < 0 && (job == 1 || job == 2), mx, my);
        button(
            52,
            635,
            315,
            37,
            17,
            "取消",
            pendingSequence < 0 && (job == 1 || job == 2 || state.data.getBoolean("capturePending")),
            mx,
            my);
    }

    private void drawOptions(int mx, int my) {
        NBTTagCompound cap = capability(optionChannel);
        if (cap == null) {
            optionChannel = "";
            return;
        }
        drawRect(160, 59, 405, 257, 0xff172f36);
        text(cap.getString("label") + " · 选择目标", 168, 65, 203, 0xf5e4b9);
        button(63, 375, 62, 23, 17, "×", true, mx, my);
        NBTTagList options = cap.getTagList("options", 10);
        for (int i = drawerOffset; i < options.tagCount() && i < drawerOffset + 7; i++) {
            NBTTagCompound option = options.getCompoundTagAt(i);
            boolean chosen = option.getBoolean("present")
                ? cap.getBoolean("explicit") && option.getInteger("value") == cap.getInteger("current")
                : !cap.getBoolean("explicit");
            int y = 86 + (i - drawerOffset) * 18;
            button(4000 + i, 168, y, 228, 17, (chosen ? "✓ " : "") + option.getString("label"), editable(), mx, my);
            if (mx >= 168 && mx < 396 && my >= y && my < y + 17) tooltip = Collections.singletonList(
                option.getString("label") + " · "
                    + (option.getBoolean("present") ? "显式目标，原始值 " + option.getInteger("value") : "恢复默认值")
                    + (editable() ? "；仅更新目标。" : "；" + editingReason()));
        }
        if (!"_hatch_candidate".equals(optionChannel)) {
            text("原始正整数（可指定列表之外的值）", 168, 215, 228, 0x94afb4);
            optionValueField.drawTextBox();
            button(68, 311, 232, 85, 16, "应用原始值", editable(), mx, my);
        }
    }

    private void drawChannels(int mx, int my) {
        tooltip = null;
        drawRect(157, 59, 405, 257, 0xff172f36);
        text("等级 / 信道设置（滚轮查看全部）", 165, 65, 205, 0xf5e4b9);
        button(62, 373, 62, 23, 17, "×", true, mx, my);
        NBTTagList caps = capabilities();
        for (int i = drawerOffset; i < caps.tagCount() && i < drawerOffset + 4; i++) {
            NBTTagCompound cap = caps.getCompoundTagAt(i);
            int y = 86 + (i - drawerOffset) * 22;
            String source = cap.getString("id")
                .equals("main") ? "主值"
                    : cap.getBoolean("explicit") ? "指定" : "presence".equals(cap.getString("kind")) ? "默认" : "继承";
            button(
                2000 + i,
                165,
                y,
                231,
                20,
                cap.getString("label") + "：" + optionLabel(cap) + "（" + source + "）",
                editable() && cap.getBoolean("editable"),
                mx,
                my);
            if (mx >= 165 && mx < 396 && my >= y && my < y + 20) {
                tooltip = new ArrayList<>();
                tooltip.add(cap.getString("label") + " / " + cap.getString("id"));
                tooltip.add("当前目标：" + optionLabel(cap) + " · 原始值 " + cap.getInteger("current"));
                tooltip.add(
                    cap.getString("id")
                        .equals("main") ? "主原始信号：未独立指定的子信道继承此值。"
                            : cap.getBoolean("explicit") ? "已显式指定；选择继承项可恢复默认。" : "未指定：使用主信号或机器默认规则。");
                tooltip.add("具名目标或原始正整数均可设置；机器是否使用信道由自身规则决定。");
                if (!editable()) tooltip.add(editingReason());
            }
        }
        text("手填 name（小写） / 正整数 value", 165, 178, 231, 0x94afb4);
        channelNameField.drawTextBox();
        channelValueField.drawTextBox();
        button(67, 165, 213, 98, 16, "添加 / 更新", editable(), mx, my);
        mainField.drawTextBox();
        button(30, 330, 213, 66, 16, "应用主值", editable(), mx, my);
        button(23, 165, 234, 74, 16, "重新采集", editable(), mx, my);
        button(
            33,
            242,
            234,
            74,
            16,
            state.data.getBoolean("noHatches") ? "只放外壳 ✓" : "允许仓室",
            editable() && state.data.getInteger("mode") != 2,
            mx,
            my);
        NBTTagCompound hatch = capability("gt_hatch");
        button(
            83,
            319,
            234,
            77,
            16,
            hatch != null && hatch.getBoolean("explicit") ? "自动选材" : "仓室手选",
            editable() && state.data.getInteger("mode") != 2 && hatch != null && hatch.getBoolean("editable"),
            mx,
            my);

    }

    private void drawInventory(int mx, int my) {
        tooltip = null;
        drawRect(157, 59, 405, 257, 0xff172f36);
        text("选择方块 / 仓室 · 不消耗物品", 165, 65, 205, 0xf5e4b9);
        button(16, 165, 82, 74, 18, (sourceTargets ? "" : "› ") + "主背包", true, mx, my);
        button(17, 243, 82, 74, 18, (sourceTargets ? "› " : "") + "材料来源", true, mx, my);
        button(95, 321, 82, 75, 18, "可替换选项", selectedCell() != null, mx, my);
        button(64, 373, 62, 23, 17, "×", true, mx, my);
        HologramState.Cell cell = selectedCell();
        button(
            65,
            165,
            104,
            231,
            18,
            cell == null ? "先点击左侧预览选择目标格" : "目标格：" + cellName(cell, false),
            editable() && canInventoryPin() && pickedTarget() != null,
            mx,
            my);
        text("点击物品再点目标槽，或拖到左侧预览格", 165, 126, 231, 0x94afb4);
        NBTTagList choices = state.data.getTagList("materialChoices", 10);
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) {
            int slot = row == 3 ? col : 9 + row * 9 + col;
            int choice = sourceOffset + row * 9 + col;
            int x = 181 + col * 22, y = 139 + row * 20;
            boolean chosen = sourceTargets ? sourceChoice == choice : inventorySlot == slot;
            drawRect(x, y, x + 20, y + 18, chosen ? 0xff5f858a : 0xff0c1e28);
            ItemStack stack = sourceTargets ? sourceStack(choice) : mc.thePlayer.inventory.mainInventory[slot];
            drawItem(stack, x + 2, y + 1);
            if (mx >= x && mx < x + 20 && my >= y && my < y + 18 && stack != null)
                tooltip = Collections.singletonList(stack.getDisplayName() + " · 选为目标（不消耗）");
        }
        if (sourceTargets && choices.tagCount() == 0) {
            text("开启来源并准备物品", 183, 160, 194, 0xe4d3a9);
            text("然后重新扫描或重新打开", 183, 180, 194, 0xe4d3a9);
        }
        button(66, 165, 221, 105, 17, "恢复默认壳", editable() && hasSelectedPin(), mx, my);
        ItemStack picked = pickedTarget();
        if (sourceTargets) button(18, 277, 221, 119, 17, "刷新来源 · 滚轮翻页", editable(), mx, my);
        else text(picked == null ? "未选物品" : picked.getDisplayName(), 277, 224, 119, 0xe4d3a9);
        text(pendingSequence >= 0 ? "等待服务端校验目标…" : state.data.getString("status"), 165, 243, 231, 0xffbd91);
        if (mx >= 165 && mx < 396 && my >= 240 && my < 255) {
            tooltip = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(state.data.getString("status"), 230));
            if (pendingSequence >= 0) tooltip.add("等待服务端校验目标…");
            if (cell != null) {
                tooltip.add("选中格 #" + cell.index + " · 世界坐标 " + cell.x + "," + cell.y + "," + cell.z);
                tooltip.addAll(fontRendererObj.listFormattedStringToWidth("当前目标：" + cellName(cell, false), 230));
                tooltip.add(cell.wantId + " @ " + cell.wantMeta);
            }
            if (picked != null) tooltip.addAll(
                fontRendererObj
                    .listFormattedStringToWidth((sourceTargets ? "来源选择：" : "背包选择：") + picked.getDisplayName(), 230));
        }
        if (picked != null && inventoryDragging) drawItem(picked, mx - 8, my - 8);
    }

    private boolean sourceEnabled(String key) {
        return !state.data.hasKey(key) || state.data.getBoolean(key);
    }

    private List<String> hatchRoles() {
        List<String> roles = new ArrayList<>();
        roles.add("全部类型");
        NBTTagList tags = cellTag().getTagList("candidateRoles", 8);
        for (int i = 0; i < tags.tagCount(); i++) {
            String role = tags.getStringTagAt(i);
            if (!role.isEmpty() && !roles.contains(role)) roles.add(role);
        }
        return roles;
    }

    private List<Integer> filteredHatches() {
        List<Integer> indices = new ArrayList<>();
        HologramState.Cell cell = selectedCell();
        if (cell == null) return indices;
        List<String> roles = hatchRoles();
        hatchRole = Math.floorMod(hatchRole, roles.size());
        NBTTagList tags = cellTag().getTagList("candidateRoles", 8);
        int[] tiers = cellTag().getIntArray("candidateTiers");
        for (int i = 0; i < cell.candidates.size(); i++) {
            if (hatchRole > 0 && !roles.get(hatchRole)
                .equals(tags.getStringTagAt(i))) continue;
            if (hatchFilterTier >= 0 && (i >= tiers.length || tiers[i] != hatchFilterTier)) continue;
            indices.add(i);
        }
        return indices;
    }

    private String tierName(int tier) {
        return tier >= 0 && tier < GTValues.VN.length ? GTValues.VN[tier] : "固定";
    }

    private List<Integer> availableTiers() {
        List<Integer> levels = new ArrayList<>();
        NBTTagList roles = cellTag().getTagList("candidateRoles", 8);
        int[] tiers = cellTag().getIntArray("candidateTiers");
        List<String> options = hatchRoles();
        for (int i = 0; i < tiers.length; i++) {
            if (hatchRole > 0 && !options.get(hatchRole)
                .equals(roles.getStringTagAt(i))) continue;
            if (tiers[i] >= 0 && !levels.contains(tiers[i])) levels.add(tiers[i]);
        }
        Collections.sort(levels);
        return levels;
    }

    private void drawTierSelector(int x, int y, int width, int mx, int my) {
        List<Integer> levels = availableTiers();
        boolean available = selectedCell() != null;
        button(
            117,
            x,
            y,
            20,
            17,
            "−",
            available && HologramTargetTiers.steppedTier(levels, hatchFilterTier, -1) != hatchFilterTier,
            mx,
            my);
        button(
            98,
            x + 23,
            y,
            width - 46,
            17,
            hatchFilterTier < 0 ? "等级：全部" : "等级：" + tierName(hatchFilterTier),
            available,
            mx,
            my);
        button(
            118,
            x + width - 20,
            y,
            20,
            17,
            "+",
            available && HologramTargetTiers.steppedTier(levels, hatchFilterTier, 1) != hatchFilterTier,
            mx,
            my);
    }

    private void drawRoleDropdown(int mx, int my) {
        List<String> roles = hatchRoles();
        int x = hatchDrawer ? 165 : 284, y = hatchDrawer ? 103 : 153, width = hatchDrawer ? 231 : 224;
        int visible = Math.min(7, roles.size());
        roleOffset = Math.max(0, Math.min(roleOffset, Math.max(0, roles.size() - visible)));
        drawRect(x - 2, y - 2, x + width + 2, y + visible * 18 + 2, 0xff0c1e28);
        for (int row = 0; row < visible; row++) {
            int index = roleOffset + row;
            button(
                15000 + index,
                x,
                y + row * 18,
                width,
                17,
                (hatchRole == index ? "✓ " : "") + roleLabel(roles.get(index)),
                true,
                mx,
                my);
        }
        tooltip = Collections.singletonList("直接选择类型 · 滚轮查看更多 · Esc关闭");
    }

    private String directionName(int facing) {
        String[] names = { "下", "上", "北", "南", "西", "东" };
        return facing >= 0 && facing < names.length ? names[facing] : "默认";
    }

    private void drawHatches(int mx, int my) {
        drawRect(157, 59, 405, 281, 0xff172f36);
        text(
            selectedCell() != null && selectedCell().candidateComplete ? "本格可替换选项 · 类型 / 等级" : "本格已知选项 · 类型 / 等级",
            165,
            65,
            205,
            0xf5e4b9);
        if (mx >= 165 && mx < 370 && my >= 62 && my < 80)
            tooltip = fontRendererObj.listFormattedStringToWidth(candidateNote(), 230);
        button(96, 373, 62, 23, 17, "×", true, mx, my);
        List<String> roles = hatchRoles();
        hatchRole = Math.floorMod(hatchRole, roles.size());
        button(97, 165, 84, 231, 18, "类型：" + roleLabel(roles.get(hatchRole)) + " ▾", true, mx, my);
        drawTierSelector(165, 105, 231, mx, my);
        List<Integer> indices = filteredHatches();
        hatchOffset = Math.max(0, Math.min(hatchOffset, Math.max(0, indices.size() - 5)));
        int[] available = cellTag().getIntArray("candidateAvailable");
        int[] tiers = cellTag().getIntArray("candidateTiers");
        for (int row = 0; row < 5 && hatchOffset + row < indices.size(); row++) {
            int index = indices.get(hatchOffset + row);
            ItemStack stack = selectedCell().candidates.get(index);
            int y = 128 + row * 21;
            drawItem(stack, 165, y);
            String count = state.data.getBoolean("creativeMaterials") ? "无限"
                : index < available.length ? Integer.toString(available[index]) : "?";
            button(
                10000 + index,
                186,
                y,
                210,
                19,
                (selectedCell().chosenChoice == index ? "✓ " : "") + stack.getDisplayName() + " ×" + count,
                editable() && canInventoryPin(),
                mx,
                my);
            if (mx >= 165 && mx < 396 && my >= y && my < y + 19) {
                tooltip = new ArrayList<>();
                tooltip.add(stack.getDisplayName());
                tooltip.add((index < tiers.length ? tierName(tiers[index]) : "固定等级") + " · 已启用来源可用 " + count);
                tooltip.add("仅指定本格目标；施工时重新核对材料。");
                tooltip.add(candidateNote());
                if (!editable()) tooltip.add(editingReason());
            }
        }
        if (indices.isEmpty()) text("本筛选没有候选，请更换类型或等级", 165, 125, 231, 0xe4b85a);
        text("滚轮翻页 · " + indices.size() + " 项", 165, 240, 115, 0x94afb4);
        button(66, 282, 237, 114, 18, "恢复默认壳", editable() && hasSelectedPin(), mx, my);
        int[] facings = cellTag().getIntArray("hatchFacings");
        button(
            99,
            165,
            259,
            139,
            18,
            facings.length == 0 ? "仓室朝向不可用" : "仓室朝向：" + directionName(cellTag().getInteger("hatchFacing")),
            editable() && facings.length > 0,
            mx,
            my);
        button(102, 308, 259, 88, 18, "恢复朝向", editable() && cellTag().getBoolean("hasFacingPin"), mx, my);
    }

    private String candidateNote() {
        HologramState.Cell cell = selectedCell();
        if (cell == null) return "请先选择预览中的位置。";
        return cell.candidateComplete ? "选项来自本格结构声明；机器成型仍受整体结构条件限制。"
            : "仅列出已解析选项：" + cell.candidateBoundary + "；机器成型仍受整体结构条件限制。";
    }

    private void drawPreset(int mx, int my) {
        drawRect(157, 59, 405, 257, 0xff172f36);
        text("声明仓室一键预设", 165, 65, 205, 0xf5e4b9);
        button(96, 373, 62, 23, 17, "×", true, mx, my);
        text("按结构声明配额配置空位，不改已有仓室", 165, 90, 231, 0xb9d5d4);
        button(103, 165, 113, 28, 21, "−", presetTier > 0, mx, my);
        text("仓室等级：" + tierName(presetTier), 201, 120, 153, 0xe4d3a9);
        button(104, 368, 113, 28, 21, "+", presetTier < state.data.getInteger("hatchTierMax"), mx, my);
        button(105, 165, 143, 231, 21, presetDowngrade ? "缺料时允许降级 ✓" : "缺料时严格保持等级", true, mx, my);
        String reason = HologramUiText.reason(state.data.getString("presetReason"));
        List<String> lines = fontRendererObj
            .listFormattedStringToWidth(reason.isEmpty() ? "生成目标后检查差分，再点击底部施工按钮。" : reason, 230);
        for (int i = 0; i < Math.min(4, lines.size()); i++) text(lines.get(i), 165, 174 + i * 12, 231, 0xe4b85a);
        button(
            106,
            165,
            232,
            142,
            20,
            "生成预设目标",
            editable() && state.data.getBoolean("supported") && state.data.getBoolean("presetAvailable"),
            mx,
            my);
        button(107, 312, 232, 84, 20, "撤销预设", editable() && state.data.getBoolean("presetActive"), mx, my);
    }

    private void drawSources(int mx, int my) {
        tooltip = null;
        drawRect(157, 59, 405, 257, 0xff172f36);
        text("材料来源", 166, 67, 200, 0xf5e4b9);
        button(88, 375, 62, 23, 17, "×", true, mx, my);
        String[] keys = { "materialMain", "materialContainers", "materialMe" };
        String[] labels = { "主背包", "背包内物品容器", "携带的 ME 无线终端" };
        for (int i = 0; i < keys.length; i++) button(
            84 + i,
            166,
            94 + i * 25,
            230,
            21,
            labels[i] + (sourceEnabled(keys[i]) ? " ✓" : " ×"),
            editable(),
            mx,
            my);
        int priority = Math.floorMod(state.data.getInteger("materialPriority"), 3);
        button(87, 166, 175, 230, 21, "优先：" + new String[] { "主背包", "物品容器", "ME 终端" }[priority], editable(), mx, my);
        text(new String[] { "主背包 → 容器 → ME", "容器 → 主背包 → ME", "ME → 主背包 → 容器" }[priority], 166, 209, 230, 0xb9d5d4);
        text("仅使用已开启的来源（包括饰品终端）", 166, 226, 230, 0x94afb4);
        text("已绑定、有电、在范围内；遵守网络权限", 166, 241, 230, 0x94afb4);
    }

    private void drawPosition(int mx, int my) {
        tooltip = null;
        drawRect(157, 59, 405, 257, 0xff172f36);
        text("添加位置 · 相对控制器", 166, 67, 228, 0xf5e4b9);
        button(77, 375, 62, 23, 17, "×", true, mx, my);
        text("世界轴偏移 X / Y / Z（−64～64）", 166, 91, 230, 0x94afb4);
        positionXField.drawTextBox();
        positionYField.drawTextBox();
        positionZField.drawTextBox();
        button(76, 166, 146, 230, 20, "添加并选中此位置", editable(), mx, my);
        text("选中后点击“选择方块 / 仓室”指定目标", 166, 180, 230, 0xb9d5d4);
        text("位置须在已加载区块；控制器受保护", 166, 199, 230, 0x94afb4);
    }

    private void addPosition() {
        if (!editable()) return;
        try {
            int dx = Integer.parseInt(
                positionXField.getText()
                    .trim());
            int dy = Integer.parseInt(
                positionYField.getText()
                    .trim());
            int dz = Integer.parseInt(
                positionZField.getText()
                    .trim());
            if (Math.abs((long) dx) > 64 || Math.abs((long) dy) > 64 || Math.abs((long) dz) > 64)
                throw new NumberFormatException();
            NBTTagCompound action = new NBTTagCompound();
            action.setInteger("dx", dx);
            action.setInteger("dy", dy);
            action.setInteger("dz", dz);
            positionDrawer = false;
            send("addPosition", action, true);
        } catch (NumberFormatException invalid) {
            notice = "坐标须为−64～64的整数";
        }
    }

    private ItemStack sourceStack(int choice) {
        NBTTagList choices = state.data.getTagList("materialChoices", 10);
        return choice >= 0 && choice < choices.tagCount()
            ? ItemStack.loadItemStackFromNBT(choices.getCompoundTagAt(choice))
            : null;
    }

    private ItemStack pickedTarget() {
        return sourceTargets ? sourceStack(sourceChoice)
            : inventorySlot >= 0 ? mc.thePlayer.inventory.mainInventory[inventorySlot] : null;
    }

    private int inventorySlotAt(int x, int y) {
        int col = (x - 181) / 22, row = (y - 139) / 20;
        if (x < 181 || y < 139 || col >= 9 || row >= 4 || (x - 181) % 22 >= 20 || (y - 139) % 20 >= 18) return -1;
        return sourceTargets ? sourceOffset + row * 9 + col : row == 3 ? col : 9 + row * 9 + col;
    }

    private void pinInventory(int target) {
        if (!editable() || target < 0 || pickedTarget() == null) return;
        selection.click(target, false);
        selected = target;
        NBTTagCompound n = new NBTTagCompound();
        n.setInteger("selected", target);
        n.setInteger("index", target);
        if (sourceTargets) n.setInteger("choice", sourceChoice);
        else n.setInteger("inventorySlot", inventorySlot);
        n.setInteger("scope", 2);
        send(sourceTargets ? "sourcePin" : "customPin", n, true);
    }

    private boolean canInventoryPin() {
        HologramState.Cell cell = selectedCell();
        return cell != null && !cell.anchor && state.data.getInteger("mode") != 2;
    }

    private boolean hasSelectedPin() {
        return selectedCell() != null && cellTag().getBoolean("hasTargetOverride");
    }

    private void clearSelection() {
        if (selected < 0) return;
        selection.clear();
        pendingSelectionSequence = -1;
        gizmo.cancel();
        selected = -1;
        syncTargetSelectors();
    }

    private boolean previewInteractive() {
        return !areaImporting() && aeReviewNavigation == null
            && aeReview == null
            && !drawer
            && !positionDrawer
            && !sourceDrawer
            && !hatchDrawer
            && !presetDrawer
            && !confirmation
            && !shortcutsDrawer
            && processingFlow == null
            && !discardReview
            && !overwriteReview
            && !processingPresetSaveReview
            && !promptDrawer
            && !versionsDrawer
            && !nbtDrawer
            && !deidentificationDrawer
            && !resetReview
            && deletePresetId.isEmpty()
            && !roleDropdown
            && optionChannel.isEmpty();
    }

    private void drawConfirmation(int mx, int my) {
        drawRect(40, 58, 376, 254, 0xff172f36);
        boolean placement = placementReviewActive();
        lastConfirmationTitle = HologramPlacementConfirmation.forceReview(state.data, confirmedRevision)
            ? "缺料 · 再次确认仅放已有"
            : placement ? "蓝图放置预检 · " + state.data.getString("virtualPresetName") : "材料与改动";
        text(lastConfirmationTitle, 50, 68, 315, 0xf5e4b9);
        int operations = 0, recovery = 0;
        NBTTagList rows = state.data.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            String operation = row.getString("operation");
            if (row.getBoolean("inScope")
                && (operation.equals("PLACE") || operation.equals("REPLACE") || operation.equals("REMOVE"))
                && (row.getString("status")
                    .equals("pending")
                    || row.getString("status")
                        .equals("missing")))
                operations++;
        }
        NBTTagList recovered = state.data.getTagList("recovery", 10);
        for (int i = 0; i < recovered.tagCount(); i++) recovery += recovered.getCompoundTagAt(i)
            .getInteger("expected");
        if (state.data.hasKey("recoveryTotal")) recovery = state.data.getInteger("recoveryTotal");
        lastReviewedScope = constructionScopeLabel(true);
        text(lastReviewedScope + " · 差分 " + operations + " 格", 50, 86, 315, 0xcce5d8);
        if (mx >= 50 && mx < 365 && my >= 84 && my < 96) {
            tooltip = new ArrayList<>(Collections.singletonList(lastReviewedScope));
            if (!placement) tooltip.add("返回预览可用 Ctrl+A 全选，右键预览清选后恢复全部结构");
        }
        HologramState.Cell selected = selectedCell();
        if (HologramPlacementConfirmation.forceReview(state.data, confirmedRevision)) {
            text(state.data.getString("status"), 50, 100, 315, 0xff9292);
            if (mx >= 50 && mx < 365 && my >= 98 && my < 111)
                tooltip = fontRendererObj.listFormattedStringToWidth(state.data.getString("status"), 300);
        } else if (placement) text("位置固定：" + placementPosition(), 50, 100, 315, 0xe4d3a9);
        else if (state.data.getBoolean("creativeMaterials")) text("创造：普通材料无限；封存设备仍消耗真实物品", 50, 100, 315, 0xe4d3a9);
        else if (selected != null)
            text(cellName(selected, true) + " → " + cellName(selected, false), 50, 100, 315, 0xe4d3a9);
        NBTTagList materialRows = state.data.getTagList("materials", 10);
        if (!resourceReview)
            for (int i = materialOffset; i < Math.min(materialOffset + 3, state.materials.size()); i++) {
                HologramState.Material m = state.materials.get(i);
                int y = 119 + (i - materialOffset) * 27;
                String available = state.data.getBoolean("creativeMaterials") && !HologramRecovery.hasSeal(m.stack)
                    ? "无限"
                    : Integer.toString(m.available);
                text(
                    (m.stack == null ? "未知材料" : m.stack.getDisplayName()) + " × " + m.required + " / 可用来源 " + available,
                    50,
                    y,
                    315,
                    m.available < m.required ? 0xff9292 : 0xa2e8b8);
                NBTTagCompound material = materialRows.getCompoundTagAt(i);
                int controllerAvailable = material.getInteger("controllerAvailable");
                String counts = "主背包 " + material.getInteger("mainAvailable")
                    + " · 控制器 "
                    + (controllerAvailable < 0 ? "不可访问" : Integer.toString(controllerAvailable))
                    + "（仅查看）";
                text(counts, 50, y + 11, 315, 0x94afb4);
                if (mx >= 50 && mx < 365 && my >= y && my < y + 24) {
                    tooltip = new ArrayList<>();
                    tooltip.add(m.stack == null ? "未知材料" : m.stack.getDisplayName());
                    tooltip.add("需求 " + m.required + " · 已启用来源可用 " + available);
                    tooltip.add(counts);
                    tooltip.add("控制器库存仅查看，不参与本次取料；滚轮查看更多。");
                }
            }
        if (resourceReview) {
            NBTTagList resources = state.data.getTagList("blueprintResources", 10);
            for (int i = materialOffset; i < Math.min(materialOffset + 3, resources.tagCount()); i++) {
                NBTTagCompound resource = resources.getCompoundTagAt(i);
                String type = resource.getString("type");
                String source = "energy".equals(type) ? "无线电网 EU" : "fluid".equals(type) ? "AE 流体 mB" : "背包 / AE 库存";
                int y = 119 + (i - materialOffset) * 27;
                String missing = resource.getString("missing");
                text(resource.getString("name") + " · " + source, 50, y, 315, 0xb9d5d4);
                String counts = "需求 " + resource
                    .getString("required") + " / 可用 " + resource.getString("available") + " / 缺口 " + missing;
                text(counts, 50, y + 11, 315, missing.isEmpty() || "0".equals(missing) ? 0xa2e8b8 : 0xff9292);
                if (mx >= 50 && mx < 365 && my >= y && my < y + 24) {
                    tooltip = new ArrayList<>();
                    tooltip.add(resource.getString("name") + " · " + source);
                    tooltip.add(counts);
                    tooltip.add("NBT 根条目：" + resource.getString("key"));
                    if (!HologramUiText.reason(resource.getString("reason"))
                        .isEmpty()) tooltip.add(HologramUiText.reason(resource.getString("reason")));
                    tooltip.add("可在本次 NBT 匹配中取消对应条目降级；原蓝图保存选项保持不变。");
                }
            }
            if (resources.tagCount() == 0) text("当前所选 NBT 没有额外设备资源需求", 50, 128, 315, 0x94afb4);
        }
        boolean decorativeNotice = state.data.getBoolean("decorativeMaterialsNotice");
        text(
            decorativeNotice ? "装饰材料：优先成品，缺少时按比例合计原料"
                : "预计回收 " + recovery + " · " + state.data.getString("recoveryDestinationLabel"),
            50,
            202,
            315,
            0x94afb4);
        if (mx >= 50 && mx < 365 && my >= 200 && my < 214)
            tooltip = decorativeNotice ? fontRendererObj.listFormattedStringToWidth(
                HologramUiText.localized(
                    state.data.getString("decorativeMaterialsNoticeKey"),
                    "本图含装饰形状：优先成品，缺少时同材质原始方块按施工比例合计后向上取整。染色和特殊微块按成品核算。"),
                390) : Collections.singletonList("回收不会提前抵扣建造材料；所选去向未能接收的余量持久暂存，等待再次投递。");
        button(
            162,
            50,
            215,
            146,
            14,
            state.data.getBoolean("materialOrderingAvailable") ? "AE 补齐缺料" : "AE 下单接口（预留）",
            editable() && state.data.getBoolean("materialOrderingAvailable") && confirmedRevision == revision(),
            mx,
            my);
        button(168, 203, 215, 78, 14, "本次 NBT", editable() && state.data.getBoolean("virtual"), mx, my);
        button(169, 286, 215, 79, 14, resourceReview ? "方块材料" : "设备资源", true, mx, my);
        if (networkGroups().tagCount() > 0)
            button(196, 50, 253, 315, 17, "量子网络绑定 · " + networkGroups().tagCount() + " 组", editable(), mx, my);
        button(54, 50, 232, 146, 17, placement ? "取消放置" : "返回", true, mx, my);
        lastConfirmationAction = HologramPlacementConfirmation.forceReview(state.data, confirmedRevision) ? "再次确认仅放已有"
            : placement ? "确认放置" : "确认执行施工";
        button(
            55,
            203,
            232,
            162,
            17,
            lastConfirmationAction,
            editable() && (state.data.getBoolean("blueprintAwaitingConfirmation") || operations > 0)
                && confirmedRevision == revision()
                && HologramPlacementConfirmation.networksReady(state.data)
                && (state.data.getBoolean("blueprintAwaitingConfirmation")
                    || HologramPlacementConfirmation.canBuild(state.data, confirmedRevision))
                && (state.data.getBoolean("blueprintAwaitingConfirmation") || !state.data.getBoolean("virtual")
                    || state.data.getBoolean("virtualPlacement") && state.data.getBoolean("virtualLocked")),
            mx,
            my);
        if (placement) {
            int x = HologramScreenLayout.COMPACT_PANEL_X;
            drawRect(x, 60, x + HologramScreenLayout.COMPACT_PANEL_WIDTH, 314, 0xff172f36);
            text("蓝图放置预检", x + 8, 70, 204, 0x81dce9);
            text(state.data.getString("virtualPresetName"), x + 8, 92, 204, 0xf5e4b9);
            text("位置已固定", x + 8, 117, 204, 0x9eedc1);
            text(placementPosition(), x + 8, 132, 204, 0xb9d5d4);
            text("本次 NBT 与设备资源", x + 8, 160, 204, 0xf5e4b9);
            text("点击左侧“本次 NBT”调整", x + 8, 177, 204, 0x94afb4);
            text("取消 NBT 编辑保留位置和蓝图", x + 8, 193, 204, 0x94afb4);
            text("材料核对后点击“确认放置”", x + 8, 232, 204, 0x9eedc1);
            text("缺料时会要求再次明确确认", x + 8, 249, 204, 0xe4b85a);
            button(
                211,
                x + 8,
                208,
                204,
                17,
                "去标识 " + deidentificationChoice(true)
                    + " · 推荐 "
                    + state.data.getInteger("deidentificationRecommendedLevel"),
                editable(),
                mx,
                my);
            button(215, x + 8, 267, 204, 17, "一键移除阻塞物", editable(), mx, my);
            button(
                184,
                x + 8,
                287,
                204,
                17,
                "数据校验详情 · " + state.data.getTagList("aeIssues", 10)
                    .tagCount() + " 项",
                true,
                mx,
                my);
        }
    }

    private boolean placementReviewActive() {
        return state.data.getBoolean("virtualPlacement") && state.data.getBoolean("virtualLocked")
            && state.data.getBoolean("blueprintAwaitingConfirmation");
    }

    private String placementPosition() {
        return state.data.getInteger("x") + ", " + state.data.getInteger("y") + ", " + state.data.getInteger("z");
    }

    private String constructionScopeLabel(boolean reviewed) {
        if (state.data.getBoolean("wholeBlueprintPlacement") || state.data.getBoolean("virtualPlacement"))
            return "施工范围：整份蓝图（" + state.cells.size() + " 格）";
        int count = selection.size();
        if (reviewed) {
            int scope = state.data.getInteger("scope");
            count = scope < 2 ? 0 : state.data.getIntArray("selectedIndices").length;
            if (scope >= 2 && count == 0 && state.data.getInteger("selected") >= 0) count = 1;
        }
        if (count > 0) return "施工范围：已选 " + count + " 格";
        return state.data.getBoolean("virtual") ? "施工范围：整份蓝图" : "施工范围：全部结构";
    }

    private String constructionScopeHint() {
        if (state.data.getBoolean("wholeBlueprintPlacement") || state.data.getBoolean("virtualPlacement"))
            return "确认放置默认全蓝图";
        return selection.size() > 0 ? "只施工所选 · Ctrl+A 全选 · 右键预览清选" : "选择方块可限定范围";
    }

    private String completionSummary() {
        if (state.data.getBoolean("jobPartialPlacement") && state.data.getInteger("job") >= 3)
            return "部分完成：放置 " + state.data.getInteger("completed")
                + " · 缺料 "
                + state.data.getInteger("jobMissingCells")
                + " · 保护 "
                + state.data.getInteger("jobProtectedCells")
                + " · 不支持 "
                + state.data.getInteger("jobUnsupportedCells");
        return HologramUiText.reason(state.data.getString("status"));
    }

    private boolean editable() {
        int job = state.data.getInteger("job");
        return !areaImporting() && !state.data.getBoolean("targetClosed")
            && job != 1
            && job != 2
            && pendingSequence < 0
            && !state.data.getBoolean("capturePending");
    }

    private long revision() {
        return state.data.getLong("planRevision");
    }

    private String facingName() {
        int index = state.data.getInteger("facing");
        if (index < 0 || index >= ExtendedFacing.values().length) return "未知";
        ExtendedFacing facing = ExtendedFacing.values()[index];
        String[] names = { "下", "上", "北", "南", "西", "东", "未知" };
        int direction = facing.getDirection()
            .ordinal();
        return names[Math.min(direction, names.length - 1)] + " / "
            + facing.getRotation()
                .ordinal() * 90
            + "°"
            + (facing.getFlip()
                .ordinal() == 0 ? ""
                    : " / 镜像 " + facing.getFlip()
                        .ordinal());
    }

    private NBTTagList capabilities() {
        return state.data.getTagList("capabilities", 10);
    }

    private NBTTagCompound capability(String id) {
        if ("_hatch_candidate".equals(id)) return hatchCandidateCapability();
        NBTTagList list = capabilities();
        for (int i = 0; i < list.tagCount(); i++) if (id.equals(
            list.getCompoundTagAt(i)
                .getString("id")))
            return list.getCompoundTagAt(i);
        return null;
    }

    private NBTTagCompound primaryCapability() {
        HologramState.Cell selected = selectedCell();
        if (selected != null) {
            NBTTagCompound hatch = hatchCandidateCapability();
            if (hatch != null) return hatch;
            if (selected.family.startsWith("glass:")) {
                NBTTagCompound glass = capability("glass");
                if (glass != null && glass.getBoolean("editable")) return glass;
                return null;
            }
            if (selected.family.startsWith("tiered:")) {
                NBTTagList rows = capabilities();
                for (int i = 0; i < rows.tagCount(); i++) {
                    NBTTagCompound row = rows.getCompoundTagAt(i);
                    if (!row.getBoolean("editable") || !row.getString("kind")
                        .equals("material")) continue;
                    NBTTagList options = row.getTagList("options", 10);
                    for (int j = 0; j < options.tagCount(); j++) {
                        ItemStack item = ItemStack.loadItemStackFromNBT(
                            options.getCompoundTagAt(j)
                                .getCompoundTag("item"));
                        if (item == null) continue;
                        net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromItem(item.getItem());
                        String member = net.minecraft.block.Block.blockRegistry.getNameForObject(block) + "@"
                            + item.getItem()
                                .getMetadata(item.getItemDamage())
                            + ";";
                        if (selected.family.substring("tiered:".length())
                            .contains(member)) return row;
                    }
                }
                return null;
            }
        }
        if (selected != null && !"coil".equals(selected.family)) return null;
        NBTTagCompound coil = capability("coil");
        if (coil != null && coil.getBoolean("editable")) return coil;
        NBTTagList list = capabilities();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound cap = list.getCompoundTagAt(i);
            if (!cap.getString("id")
                .equals("main")
                && !cap.getString("id")
                    .equals("gt_hatch")
                && cap.getBoolean("editable")) return cap;
        }
        return null;
    }

    /** A local selector over server-approved candidates; its values are candidate indices, never channel data. */
    private NBTTagCompound hatchCandidateCapability() {
        HologramState.Cell cell = selectedCell();
        if (cell == null || cell.anchor || state.data.getInteger("mode") == 2) return null;
        ItemStack target = ItemStack.loadItemStackFromNBT(cellTag().getCompoundTag("targetStack"));
        int current = cell.chosenChoice;
        if (current < 0 && target != null) for (int i = 0; i < cell.candidates.size(); i++) {
            if (target.isItemEqual(cell.candidates.get(i))
                && ItemStack.areItemStackTagsEqual(target, cell.candidates.get(i))) {
                current = i;
                break;
            }
        }
        MTEHatch prototype = current >= 0 && current < cell.candidates.size()
            ? hatchPrototype(cell.candidates.get(current))
            : null;
        if (prototype == null) for (int i = 0; i < cell.candidates.size(); i++) {
            prototype = hatchPrototype(cell.candidates.get(i));
            if (prototype != null) {
                current = i;
                break;
            }
        }
        if (prototype == null) return null;
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < cell.candidates.size(); i++) {
            MTEHatch candidate = hatchPrototype(cell.candidates.get(i));
            if (candidate != null && candidate.getClass() == prototype.getClass()) indices.add(i);
        }
        indices.sort(
            (a, b) -> Integer
                .compare(hatchPrototype(cell.candidates.get(a)).mTier, hatchPrototype(cell.candidates.get(b)).mTier));
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", "_hatch_candidate");
        row.setString("kind", "hatchCandidate");
        row.setString("label", "空仓室目标 · 独立设备等级");
        row.setBoolean("editable", true);
        row.setBoolean("explicit", true);
        row.setInteger("current", current);
        NBTTagList options = new NBTTagList();
        for (Integer index : indices) {
            ItemStack stack = cell.candidates.get(index);
            NBTTagCompound option = new NBTTagCompound();
            option.setInteger("value", index);
            option.setBoolean("present", true);
            option.setString("label", stack.getDisplayName() + " · 等级 " + hatchPrototype(stack).mTier);
            option.setTag("item", stack.writeToNBT(new NBTTagCompound()));
            options.appendTag(option);
        }
        row.setTag("options", options);
        return row;
    }

    private static MTEHatch hatchPrototype(ItemStack stack) {
        if (stack == null) return null;
        int id = stack.getItemDamage();
        return stack.getItem() instanceof ItemMachines && id >= 0
            && id < GregTechAPI.METATILEENTITIES.length
            && GregTechAPI.METATILEENTITIES[id] instanceof MTEHatch ? (MTEHatch) GregTechAPI.METATILEENTITIES[id]
                : null;
    }

    private int optionIndex(NBTTagCompound cap) {
        NBTTagList options = cap.getTagList("options", 10);
        for (int i = 0; i < options.tagCount(); i++) {
            NBTTagCompound option = options.getCompoundTagAt(i);
            if (option.getBoolean("present") && option.getInteger("value") == cap.getInteger("current")) return i;
        }
        if (!cap.getBoolean("explicit") && options.tagCount() > 0
            && !options.getCompoundTagAt(0)
                .getBoolean("present"))
            return 0;
        return -1;
    }

    private String optionLabel(NBTTagCompound cap) {
        int index = optionIndex(cap);
        return index < 0 ? "有效值 " + cap.getInteger("current")
            : cap.getTagList("options", 10)
                .getCompoundTagAt(index)
                .getString("label");
    }

    private boolean canStep(NBTTagCompound cap, int delta) {
        int index = optionIndex(cap) + delta;
        return index >= 0 && index < cap.getTagList("options", 10)
            .tagCount()
            && cap.getTagList("options", 10)
                .getCompoundTagAt(index)
                .getBoolean("present");
    }

    private void chooseOption(NBTTagCompound cap, int index) {
        if (cap == null || !cap.getBoolean("editable") || !editable()) return;
        NBTTagList options = cap.getTagList("options", 10);
        if (index < 0 || index >= options.tagCount()) return;
        NBTTagCompound option = options.getCompoundTagAt(index), config = configuration();
        if (cap.getString("kind")
            .equals("hatchCandidate")) {
            config = new NBTTagCompound();
            config.setInteger("selected", selected);
            config.setInteger("choiceindex", option.getInteger("value"));
            optionChannel = "";
            send("pin", config, true);
            return;
        }
        String id = cap.getString("id");
        if (id.equals("main")) config.setInteger("main", option.getInteger("value"));
        else if (option.getBoolean("present")) config.getCompoundTag("channels")
            .setInteger(id, option.getInteger("value"));
        else config.getCompoundTag("channels")
            .removeTag(id);
        optionChannel = "";
        send("configure", config, true);
    }

    private HologramState.Cell selectedCell() {
        return selected >= 0 && selected < state.cells.size() ? state.cells.get(selected) : null;
    }

    private NBTTagCompound cellTag() {
        return selected < 0 ? new NBTTagCompound()
            : state.data.getTagList("cells", 10)
                .getCompoundTagAt(selected);
    }

    private String cellName(HologramState.Cell cell, boolean current) {
        if (current && cell.actualStack != null) return cell.actualStack.getDisplayName();
        if (!current && cell.targetStack != null) return cell.targetStack.getDisplayName();
        if (!current && cell.chosenChoice >= 0 && cell.chosenChoice < cell.candidates.size())
            return cell.candidates.get(cell.chosenChoice)
                .getDisplayName();
        if (!current && !cell.candidates.isEmpty()) return cell.candidates.get(0)
            .getDisplayName();
        net.minecraft.block.Block block = cell.block(current);
        if (block != null) return block.getLocalizedName();
        return current ? "空位 / 未知现场" : "固定接口 / 未知目标";
    }

    private static String statusName(String status) {
        switch (status) {
            case "blocked":
                return "被遮挡：请清空占位后补建";
            case "protected":
                return "受保护：控制器 / 未适配设备保留";
            case "unsupported":
                return "固定接口待手动补齐";
            case "missing":
                return "缺少材料";
            case "satisfied":
                return "目标已满足";
            case "pending":
                return "待施工";
            case "placed":
                return "已建造";
            case "replaced":
                return "已替换";
            case "removed":
                return "已拆除";
            default:
                return status;
        }
    }

    private void text(String value, int x, int y, int w, int color) {
        if (compactControls) {
            GL11.glPushMatrix();
            GL11.glTranslatef(x, 0, 0);
            GL11.glScalef(1 / HologramScreenLayout.CONTROL_WIDTH_SCALE, 1, 1);
            GL11.glTranslatef(-x, 0, 0);
        }
        try {
            drawString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(
                    value,
                    compactControls ? Math.round(w * HologramScreenLayout.CONTROL_WIDTH_SCALE) : w),
                x,
                y,
                color);
        } finally {
            if (compactControls) GL11.glPopMatrix();
        }
    }

    private void skin(String name, int x, int y, int w, int h) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glColor4f(1, 1, 1, 1);
            mc.getTextureManager()
                .bindTexture(new ResourceLocation("gtmb", "textures/gui/pocket/POCKET_C2_" + name + ".png"));
            func_152125_a(x, y, 0, 0, 1, 1, w, h, 1, 1);
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void button(int id, int x, int y, int w, int h, String label, boolean enabled, int mx, int my) {
        Control c = new Control(id, x, y, w, h, enabled);
        if (compactControls) controls.add(
            new Control(
                id,
                HologramScreenLayout.COMPACT_PANEL_X
                    + Math.round((x - panelX) * HologramScreenLayout.CONTROL_WIDTH_SCALE),
                y,
                Math.round(w * HologramScreenLayout.CONTROL_WIDTH_SCALE),
                h,
                enabled));
        else controls.add(c);
        skin(enabled && c.contains(mx, my) ? "btn_pressed" : "btn", x, y, w, h);
        int accent = aeReview != null ? HologramGuidance.modalAccent(id, 181, 182)
            : !deletePresetId.isEmpty() ? HologramGuidance.modalAccent(id, 176, 177)
                : nbtDrawer ? HologramGuidance.modalAccent(id, 173, 172)
                    : promptDrawer ? HologramGuidance.modalAccent(id, 158, 151)
                        : versionsDrawer ? HologramGuidance.modalAccent(id, 160, 151)
                            : resetReview ? HologramGuidance.modalAccent(id, 137, 136)
                                : HologramGuidance.accent(id, state, selected, libraryDrawer, confirmation);
        if (processingFlow != null)
            accent = id == 263 && processingFlow.stage == 2 && !quantumGroupsConfirmed() ? 0x80efaa
                : HologramGuidance.modalAccent(id, 231, 234);
        else if (discardReview || overwriteReview || processingPresetSaveReview)
            accent = HologramGuidance.modalAccent(id, 224, 225);
        else if (aeReview == null && !nbtDrawer && !promptDrawer && !versionsDrawer && !confirmation && !resetReview) {
            int contextual = contextualAccent(id);
            if (contextual != 0 || id == 50 || id == 146 || id == 141) accent = contextual;
        }
        if (id == 150 && state.data.getBoolean("sourceMissing")
            || id == 163 && selectedCell() != null && selectedCell().sourceMissing) accent = 0xff7272;
        if (enabled && accent != 0) {
            int border = 0xff000000 | accent;
            drawRect(x, y, x + w, y + 1, border);
            drawRect(x, y + h - 1, x + w, y + h, border);
            drawRect(x, y, x + 1, y + h, border);
            drawRect(x + w - 1, y, x + w, y + h, border);
        }
        if (compactControls) {
            GL11.glPushMatrix();
            GL11.glTranslatef(x + w / 2F, 0, 0);
            GL11.glScalef(1 / HologramScreenLayout.CONTROL_WIDTH_SCALE, 1, 1);
            GL11.glTranslatef(-x - w / 2F, 0, 0);
        }
        try {
            drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(
                    label,
                    (compactControls ? Math.round(w * HologramScreenLayout.CONTROL_WIDTH_SCALE) : w) - 6),
                x + w / 2,
                y + (h - 8) / 2,
                enabled ? accent == 0 ? 0xf0ddb3 : accent : 0x647c7f);
        } finally {
            if (compactControls) GL11.glPopMatrix();
        }
        if (c.contains(mx, my)) {
            String help = controlHelp(id);
            if (help != null) {
                tooltip = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(help, 230));
                if (!enabled) tooltip.add(disabledReason(id));
            }
        }
    }

    private String editingReason() {
        if (state.data.getBoolean("targetClosed")) return "控制器已拆除；保留最终统计，重新右键新控制器开启任务。";
        if (pendingSequence >= 0) return "等待服务端回执后可操作。";
        if (state.data.getBoolean("capturePending")) return "正在采集结构，请稍候。";
        if (state.data.getInteger("job") == 1 || state.data.getInteger("job") == 2) return "施工任务中；取消任务后可修改蓝图。";
        return "当前目标不支持此操作。";
    }

    private String phaseName() {
        String phase = state.data.getString("phase");
        if (phase.equals("PREPARE")) return "等待服务端执行";
        if (phase.equals("ACK")) return "已收到施工结果";
        if (phase.equals("GAP")) return "等待服务端执行";
        return phase;
    }

    private String disabledReason(int id) {
        if ((id == 140 || id == 132 || id == 166 || id == 221) && !processingReady())
            return "先完成蓝图处理流程，服务端确认后开放保存与方块 NBT 编辑。";
        if (id == 179) return "请单选一个已支持的 AE 方块，并等待当前编辑回执。";
        if (!editable() && id != 51 && id != 52) return editingReason();
        if (id == 162) return "当前未注册 AE 下单适配器。";
        if (id == 65 && selectedCell() != null) {
            if (!canInventoryPin()) return "控制器、已有受保护设备或拆除模式不能指定新目标；仓室数据不迁移，服务端验证替换安全性。";
        }
        if (id == 60 && state.data.getBoolean("virtual")) return "先点结构轮廓，再打开结构编辑以设置此结构等级和信道。";
        if (id == 139) return "先在列表中选择要编辑的预设。";
        if (id == 140 || id == 132) return "先导入区域或载入蓝图以保存当前蓝图。";
        if (id == 175) return "先在蓝图库列表选择要删除的条目。";
        if ((id == 50 || id == 135) && state.data.getBoolean("virtual") && !state.data.getBoolean("virtualPlacement"))
            return "仅编辑模板；请关闭编辑后 Alt + 右击启动投影，再锁定位置施工。";
        if (id == 50 && state.data.getBoolean("virtual") && !state.data.getBoolean("virtualLocked"))
            return "请先锁定虚拟模板，再执行放置。";
        if (id == 66) return selectedCell() == null ? "先选择预览格。" : "控制器锚点不能恢复为外壳。";
        if (id == 69) return "当前没有选中预览格。";
        if (id == 95) return "先选择有仓室候选或可调整朝向的预览格；拆除模式无需配置目标。";
        if (id == 99) return "本格没有可安全修改的仓室朝向。";
        if (id == 102) return "本格没有单独指定的朝向，无需恢复。";
        if (id == 106) return HologramUiText.reason(state.data.getString("presetReason"));
        if (id == 107) return "当前没有启用整机仓室预设，无需撤销。";
        if (id == 103 || id == 104) return "已到达合法仓室等级边界。";
        if (id == 93 && selectedCell() != null && selectedCell().family.isEmpty()) return "本格没有可安全识别的同类部件，请使用单格范围。";
        if (id == 61 && state.data.getInteger("mode") == 2) return "拆除模式无需指定新方块；切换补建或替换后可选材。";
        if (id == 50 && state.data.getInteger("scope") >= 2 && selectedCell() == null) return "选中格或同类部件范围需要先选择预览格。";
        if (id == 92 || id == 93 || id == 65) return selectedCell() == null ? "先点击左侧预览选位，或添加坐标。" : "先从背包选择目标物品。";
        if (id == 83) return "此结构没有已验证的仓室自动选材能力。";
        if (id == 55) return state.data.getInteger("missing") > 0 ? "材料不足：请补充背包后重新采集。" : "材料与改动已更新，请重新查看。";
        if (id == 51 || id == 52) return pendingSequence >= 0 ? "等待服务端回执。" : "没有可暂停或取消的施工任务。";
        if (id == 12 || id == 13) return "已到达结构图层边界。";
        if (id == 80 || id == 82) return "已到达当前已验证目标列表边界。";
        return "此操作不支持当前目标或状态。";
    }

    private String controlHelp(int id) {
        switch (id) {
            case 220:
                return "按步骤选择流程预设、去标识、量子绑定、坐标、全局 NBT 和自动提示；每步明确选择后可继续。";
            case 221:
                return "保存本次已完成的处理选择，下次导入蓝图时复用；应用预设后仍逐步核对。";
            case 222:
                return "勾选后列表只选择覆盖目标，不加载或切换蓝图。保存时会再次显示目标名称并确认覆盖。";
            case 223:
                return "仅旋转此处的查看视角；拖动预览也可旋转查看，不改变世界施工方向。";
            case 159:
                return "导出可分享的 JSON 文件，方便交给其他玩家导入；文件名使用上方名称，完成消息显示实际路径。";
            case 161:
                return "读取 JSON 分享文件；在上方输入文件名。导入后可编辑、处理并另存为蓝图。";
            case 179:
                return "打开自有 AE 标记容器；NEI 拖入、背包样本仅修改蓝图标记。";
            case 178:
                return "开始世界框选：左键第一角、右键第二角，Alt 使用玩家位置，再次 Shift+右击完成，Esc 取消。";
            case 163:
                return selectedCell() != null && selectedCell().sourceMissing
                    ? "来源结构缺失：" + selectedCell().sourceWarning + " · " + selectedCell().sourceClass
                    : "打开当前选中结构的编辑；区域中的其他成员仍保留。";
            case 162:
                return "点击后将当前缺料清单交给已注册的 AE 下单适配器；下单不会自动执行施工。";
            case 130:
                return "前往蓝图设置：处理当前蓝图、编辑 NBT 与提示词，然后保存或导入 JSON 分享文件。";
            case 132:
                return "按名称将当前完整目标规划另存为新预设；原预设保持不变，不执行施工。";
            case 139:
                return "将所选预设载入虚拟编辑预览，可修改仓室、目标和朝向；完成后使用更新原预设保存。";
            case 140:
                return "保存当前蓝图；先完成处理流程。勾选覆盖保存后可选择目标并确认覆盖。";
            case 175:
                return "删除所选蓝图库条目；先核对名称并确认，当前编辑蓝图保留。";
            case 134:
                return state.data.getBoolean("structureBuilt")
                    ? "识别可确认的现场材质信道和诸神锻炉环数，保留独立仓室及朝向；校准后仍可改线圈等信道。未识别的尺寸和原始信道保留。不施工，会覆盖已有自定义目标。"
                    : "恢复结构默认目标并清除本投影仪蓝图。仅改变规划，不回滚世界方块，也不删除已存预设。";
            case 137:
                return resetCalibration ? "用当前现场校准规划，覆盖已有自定义目标；服务端重新校验，不执行施工。"
                    : "恢复结构默认目标并清除本投影仪蓝图。仅改变规划，不回滚世界方块，也不删除已存预设。";
            case 135:
                return "锁定当前虚拟模板后才允许放置；锁定本身不执行施工。";
            case 138:
                return "设置材料来源、取料优先级、缺料降级、所选模板和默认回收去向。";

            case 70:
            case 71:
            case 72:
                return "选择补建、替换或拆除；主界面数字键 1 / 2 / 3 也可切换。切换模式不会直接施工。";
            case 108:
            case 109:
                return "撤销 / 重做仅改变规划，不回滚已施工的世界方块。";
            case 112:
                return "复制本格目标；仅复制规划，不复制设备库存或消耗材料。";
            case 113:
                return "在服务端允许的方向中旋转目标，应用替换后生效。";
            case 10:
                return "切换目标结构、真实现场和施工差分。";
            case 11:
                return "切换所有图层与当前单层预览；点击方块、T 同种、Ctrl 多选或 Shift 框选编辑范围。";
            case 12:
            case 13:
                return "查看相邻图层。";
            case 15:
                return "显示或隐藏世界中的材质预览。";
            case 26:
                return "可选：重新核对材料与改动，不执行施工。";
            case 30:
                return "高级主原始信号；未显式指定的子信道继承它。修改后更新预览。";
            case 33:
                return "只放外壳：可回退位置使用外壳，固定接口仍需手动补齐。允许仓室：服务端可验证仓室候选；不保证机器成型。";
            case 50:
                return HologramPlacementConfirmation.forceReview(state.data, confirmedRevision)
                    ? "重新打开本次缺料复核；保留服务端的二次确认，不重新扫描。"
                    : "先查看当前材料与改动，再明确确认施工。";
            case 51:
                return "暂停或继续当前施工；暂停保留已完成方块。";
            case 52:
                return "停止任务，保留已完成方块与材料消耗，不撤销施工。";
            case 55:
                return HologramPlacementConfirmation.forceReview(state.data, confirmedRevision)
                    ? "再次确认后仅放置现有材料能够满足的方块；缺口保持未放置，完成统计来自服务端。"
                    : HologramPlacementConfirmation.lockedBlueprint(state.data) && state.data.getInteger("missing") > 0
                        ? "首次确认将请求服务端复核缺口；缺料时需再次明确确认仅放已有。"
                        : "按当前确认的规划施工；完成统计来自服务端回执。";
            case 60:
                return "查看具名目标与原始值，可手填任意合法信道；滚轮查看完整列表。";
            case 61:
                return "打开真实36格背包；点击或拖动复制目标，物品不会被移动或消耗。";
            case 75:
                return "按世界轴填写相对控制器的坐标，添加后自动选择单格。";
            case 78:
                return "选择从主背包、背包内容器与 ME 终端取材，并设置优先来源。";
            case 65:
                return "将选中的背包物品指定给当前预览格；可指定任意可放置方块或仓室，服务端验证替换安全性。";
            case 66:
                return "恢复结构默认壳；Delete 同效。只改变规划，已建仓室需点击应用替换。取消格选择单独保留目标。";
            case 69:
                return "仅取消预览格选择，保留已指定目标；也可右键预览或按 Esc。";
            case 80:
            case 81:
            case 82:
                return "修改当前部件的合法目标等级，仅更新目标。";
            case 83:
                return "仓室手选：从候选或背包指定目标。自动选材：允许结构自身选择仓室材料，不保证数量与成型配额。";
            case 90:
                return "施工范围：整个结构中可安全执行的差分格。";
            case 91:
                return "施工范围：当前显示图层的差分格。";
            case 92:
                return "施工范围：当前选中的一个格。";
            case 93:
                return "施工范围：与选中格属于同一已识别部件家族的格；未知部件仍保留保护。";
            case 94:
                return "按结构声明的仓室种类和数量生成空位目标，可选等级及缺料降级策略。保留手动目标和已有仓室。";
            case 95:
                return "主动查看本格可替换的方块或仓室；等级按钮始终保留当前部件和选区。";
            case 97:
                return "打开类型选框，含全部类型；选择后筛选候选，点击具体候选才指定目标。";
            case 98:
                return "恢复全部等级筛选；减号和加号按该类型真实可用等级逐级选择，不循环或切换其他类型。";
            case 117:
            case 118:
                return "按真实可用等级逐级选择；选定具体类型时更新同类型目标，全部类型时只筛选候选。";
            case 99:
                return "在本格合法方向中循环指定仓室朝向；应用施工时生效。";
            case 102:
                return "清除本格独立朝向设置，恢复自动方向规则。";
            case 103:
            case 104:
                return "预设仓室的独立电压等级，不修改线圈、玻璃或其它结构等级。";
            case 105:
                return "严格等级：缺料时保留缺料目标；允许降级：按已启用来源选择较低等级。";
            case 106:
                return "按声明配额生成预设目标，不执行施工；随后检查差分与材料并点击施工按钮。";
            case 107:
                return "撤销整机预设目标；保留手选目标、已有仓室与实际施工结果。";
            default:
                return null;
        }
    }

    private ItemStack displayedStack(HologramState.Cell cell, boolean current) {
        ItemStack stack = current ? cell.actualStack : cell.targetStack;
        if (stack != null) return stack;
        net.minecraft.block.Block block = cell.block(current);
        if (block == null || block == net.minecraft.init.Blocks.air) return null;
        net.minecraft.item.Item item = net.minecraft.item.Item.getItemFromBlock(block);
        return item == null ? null : new ItemStack(item, 1, current ? cell.meta : cell.wantMeta);
    }

    private void drawItem(ItemStack stack, int x, int y) {
        if (stack == null) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        float previousZ = itemRenderer.zLevel;
        try {
            itemRenderer.zLevel = 0;
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            RenderHelper.enableGUIStandardItemLighting();
            itemRenderer.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, x, y);
            itemRenderer.renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(), stack, x, y);
        } finally {
            itemRenderer.zLevel = previousZ;
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private int localX(int x) {
        return (int) ((x - originX) / scale);
    }

    private int localY(int y) {
        return (int) ((y - originY) / scale);
    }

    private boolean inViewport(int x, int y) {
        return x >= viewportX && x < viewportX + viewportWidth && y >= viewportY && y < viewportY + viewportHeight;
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        int mx = localX(x), my = localY(y);
        if (processingFlow != null || discardReview || overwriteReview || processingPresetSaveReview) {
            if (processingPresetSaveReview) {
                processingPresetField.mouseClicked(mx, my, button);
                if (mx >= 96 && mx < 582 && my >= 150 && my < 168) return;
            }
            if (processingFlow != null && processingFlow.stage == 0) {
                processingPresetField.mouseClicked(mx, my, button);
                if (mx >= 42 && mx < 330 && my >= 220 && my < 238) return;
            }
            if (button == 0) for (Control control : controls) if (control.contains(mx, my)) {
                if (control.enabled) activate(control.id);
                return;
            }
            return;
        }
        if (button >= 0 && (inViewport(mx, my) || controls.stream()
            .anyMatch(control -> control.enabled && control.contains(mx, my)))) initialEditHint = savedEditHint = false;
        rememberGuidance();
        if (deidentificationDrawer) {
            if (button == 0) for (Control control : controls) if (control.enabled && control.contains(mx, my)) {
                activateDeidentification(control.id);
                return;
            }
            return;
        }
        if (libraryAdminDrawer) {
            libraryUploadField.mouseClicked(mx, my, button);
            libraryDownloadField.mouseClicked(mx, my, button);
            libraryQuotaField.mouseClicked(mx, my, button);
            if (button == 0) for (Control control : controls)
                if (control.enabled && control.contains(mx, my)) activateClientLibrary(control.id);
            return;
        }
        if (networkDrawer) {
            if (networkHintField != null) {
                networkHintField.mouseClicked(mx, my, button);
                if (mx >= 50 && mx < 375 && my >= 220 && my < 238) return;
            }
            if (button == 0) for (Control control : controls) if (control.enabled && control.contains(mx, my)) {
                activateNetworkBinding(control.id);
                return;
            }
            return;
        }
        if (areaImporting()) {
            if (button == 0) for (Control control : controls)
                if (control.id == 193 && control.enabled && control.contains(mx, my)) closeRoot();
            return;
        }
        if (aeReviewNavigation != null) {
            if (button == 0) for (Control control : controls)
                if (control.id == 194 && control.enabled && control.contains(mx, my)) cancelAeReviewNavigation();
            return;
        }
        if (aeReview != null) {
            if (button == 0) for (Control control : controls) if (control.contains(mx, my)) {
                if (control.enabled) activateAeReview(control.id);
                return;
            }
            return;
        }
        // The visible confirmation owns its controls even when a remembered background tab or drawer is active.
        if (confirmation && !nbtDrawer) {
            if (button == 0) for (int i = controls.size() - 1; i >= 0; i--) {
                Control control = controls.get(i);
                if (control.contains(mx, my)) {
                    if (control.enabled && (control.id == 54 || control.id == 55
                        || control.id == 162
                        || control.id == 168
                        || control.id == 196
                        || control.id == 211
                        || control.id == 215
                        || control.id == 184
                        || control.id == 169)) activate(control.id);
                    return;
                }
            }
            return;
        }
        if (!deletePresetId.isEmpty()) {
            if (button == 0) for (Control control : controls) if (control.contains(mx, my)) {
                if (control.enabled) activate(control.id);
                return;
            }
            return;
        }
        if (nbtDrawer) {
            if (nbtCellScope && nbtValueIndex >= 0 && nbtValueField != null) {
                nbtValueField.mouseClicked(mx, my, button);
                if (mx >= 62 && mx < 348 && my >= 233 && my < 250) return;
            }
            if (button == 0) for (Control control : controls) if (control.contains(mx, my)) {
                if (control.enabled) activate(control.id);
                return;
            }
            return;
        }
        if (promptDrawer || versionsDrawer) {
            if (promptDrawer) promptField.mouseClicked(mx, my, button);
            if (button == 0) for (Control control : controls) if (control.contains(mx, my)) {
                if (control.enabled) activate(control.id);
                return;
            }
            return;
        }
        if (roleDropdown) {
            if (button == 0) for (int i = controls.size() - 1; i >= 0; i--) {
                Control control = controls.get(i);
                if (control.id >= 15000 && control.contains(mx, my)) {
                    activate(control.id);
                    return;
                }
            }
            roleDropdown = false;
            return;
        }
        if (libraryDrawer && !resetReview) presetNameField.mouseClicked(mx, my, button);
        if (button == 0 && annotationInteractive() && toggleAnnotationAt(mx, my)) return;
        if (libraryDrawer || resetReview || shortcutsDrawer || hatchDrawer || presetDrawer) {
            if (button == 0) for (int i = controls.size() - 1; i >= 0; i--) {
                Control control = controls.get(i);
                if (control.contains(mx, my)) {
                    if (control.enabled) activate(control.id);
                    return;
                }
            }
            if (!libraryDrawer || !inViewport(mx, my) || previewBlockedAt(mx, my)) return;
        }
        if (drawer && !confirmation && optionChannel.isEmpty()) {
            mainField.mouseClicked(mx, my, button);
            channelNameField.mouseClicked(mx, my, button);
            channelValueField.mouseClicked(mx, my, button);
        }
        if (!optionChannel.isEmpty() && !confirmation) optionValueField.mouseClicked(mx, my, button);
        if (positionDrawer && !confirmation) {
            positionXField.mouseClicked(mx, my, button);
            positionYField.mouseClicked(mx, my, button);
            positionZField.mouseClicked(mx, my, button);
        }
        if (button == 1 && previewInteractive() && inViewport(mx, my) && !previewBlockedAt(mx, my)) {
            if (inventoryDragging || inventorySlot >= 0 || sourceChoice >= 0) {
                inventoryDragging = false;
                inventorySlot = sourceChoice = -1;
            } else clearSelection();
            dragging = false;
            return;
        }
        if (button == 0) {
            if (previewInteractive() && inViewport(mx, my) && !previewBlockedAt(mx, my) && editable()) {
                int axis = !sameTypeSelectionHeld() && !airSelectionHeld() && moveHandles && canMoveSelection()
                    ? gizmo.pick(mx, my)
                    : -1;
                if (axis >= 0) {
                    gizmoCopy = controlSelectionHeld();
                    gizmo.begin(axis, mx, my);
                    return;
                }
            }
            if (inventory && !confirmation && optionChannel.isEmpty()) {
                int slot = inventorySlotAt(mx, my);
                if (slot >= 0) {
                    if ((sourceTargets ? sourceStack(slot) : mc.thePlayer.inventory.mainInventory[slot]) != null
                        && editable()) {
                        if (sourceTargets) sourceChoice = slot;
                        else inventorySlot = slot;
                        inventoryDragging = true;
                    }
                    return;
                }
            }
            for (int i = controls.size() - 1; i >= 0; i--) {
                Control c = controls.get(i);
                if (c.contains(mx, my)) {
                    if (sourceDrawer && (c.id < 84 || c.id > 88)) return;
                    if (positionDrawer && c.id != 76 && c.id != 77) return;
                    if (confirmation && c.id != 54 && c.id != 55 && c.id != 162 && c.id != 168 && c.id != 169) return;
                    if (!optionChannel.isEmpty() && c.id != 63 && c.id != 68 && c.id < 4000) return;
                    if (inventory && c.id != 64
                        && c.id != 65
                        && c.id != 66
                        && c.id != 16
                        && c.id != 17
                        && c.id != 18
                        && c.id != 95
                        && !inViewport(mx, my)) return;
                    if (drawer && !confirmation
                        && optionChannel.isEmpty()
                        && c.id < 1000
                        && c.id != 62
                        && c.id != 30
                        && c.id != 23
                        && c.id != 33
                        && c.id != 83
                        && c.id != 67
                        && c.id != 68) return;
                    if (c.enabled) activate(c.id);
                    return;
                }
            }
            if (previewBlockedAt(mx, my)) return;
            if (previewInteractive() && inViewport(mx, my)) {
                if (!inventory && airSelectionHeld() && editable()) {
                    selectEmptyVoxel(mx, my);
                    return;
                }
                if (isShiftKeyDown() && !sameTypeSelectionHeld()) {
                    int target = HologramRenderer.pick(mx, my);
                    boxStart = target;
                    boxAdditive = controlSelectionHeld();
                    boxSelecting = boxStart >= 0;
                    return;
                }
                dragging = true;
                moved = false;
                dragX = mx;
                dragY = my;
            }
        }
        if (button == 2 && previewInteractive() && !inventory && inViewport(mx, my) && !previewBlockedAt(mx, my))
            resetCamera();
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (areaImporting() || aeReviewNavigation != null || deidentificationDrawer) return;
        if (gizmo.axis() >= 0) {
            gizmo.update(localX(x), localY(y));
            return;
        }
        if (boxSelecting) {
            selection.box(state, boxStart, HologramRenderer.pick(localX(x), localY(y)), boxAdditive);
            return;
        }
        if (!dragging) return;
        int mx = localX(x), my = localY(y), dx = mx - dragX, dy = my - dragY;
        if (Math.abs(dx) + Math.abs(dy) > 1) moved = true;
        yaw -= dx * .012;
        pitch = Math.max(-1.35, Math.min(1.35, pitch + dy * .012));
        dragX = mx;
        dragY = my;
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        if (areaImporting() || aeReviewNavigation != null || deidentificationDrawer) return;
        if (aeReview != null) return;
        if (button == 0 && gizmo.axis() >= 0) {
            int axis = gizmo.axis(), delta = gizmo.update(localX(x), localY(y));
            gizmo.cancel();
            if (delta != 0 && editable() && canMoveSelection()) {
                NBTTagCompound move = new NBTTagCompound();
                move.setIntArray("indices", selection.indices());
                move.setInteger(axis == 0 ? "dx" : axis == 1 ? "dy" : "dz", delta);
                move.setBoolean("copy", gizmoCopy);
                send("editMove", move, true);
            }
            return;
        }
        if (button == 0 && boxSelecting) {
            boxSelecting = false;
            selection.box(state, boxStart, HologramRenderer.pick(localX(x), localY(y)), boxAdditive);
            selected = selection.first();
            syncTargetSelectors();
            return;
        }
        if (button == 0 && inventoryDragging) {
            int mx = localX(x), my = localY(y);
            inventoryDragging = false;
            int target = HologramRenderer.pick(mx, my);
            if (inViewport(mx, my) && !previewBlockedAt(mx, my) && target >= 0) pinInventory(target);
            else if (mx >= 165 && mx < 396 && my >= 104 && my < 122) pinInventory(selected);
            return;
        }
        if (button == 0 && dragging) {
            int target = HologramRenderer.pick(localX(x), localY(y));
            if (!moved && inViewport(localX(x), localY(y)) && !previewBlockedAt(localX(x), localY(y)) && editable()) {
                String structure = HologramRenderer.pickStructure(localX(x), localY(y));
                boolean whole = sameTypeSelectionHeld();
                if (structure.isEmpty() && !whole && target < 0) {
                    clearSelection();
                    dragging = false;
                    return;
                }
                selection.previewClick(state, target, structure, whole, controlSelectionHeld());
                selected = selection.first();
                cellEditHint = selected >= 0;
                syncTargetSelectors();
                if (target >= 0 && (state.cells.get(target).missingBlock || state.cells.get(target).sourceMissing))
                    versionsDrawer = true;
            }
            dragging = false;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        if (networkDrawer) return;
        if (areaImporting() || aeReviewNavigation != null || deidentificationDrawer) return;
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        if (processingFlow != null) {
            int count = processingFlow.stage == 0 ? state.data.getTagList("processingPresets", 10)
                .tagCount()
                : processingFlow.stage == 3 ? processingList("relations", 10).tagCount()
                    : processingFlow.stage == 4 ? processingList("nbtKeys", 8).tagCount() : 0;
            int rows = processingFlow.stage == 0 ? 3 : processingFlow.stage == 4 ? 5 : 4;
            processingFlow.offset = Math
                .max(0, Math.min(Math.max(0, count - rows), processingFlow.offset + (wheel > 0 ? -1 : 1)));
            return;
        }
        if (discardReview || overwriteReview || processingPresetSaveReview) return;
        if (aeReview != null) {
            int modalY = localY(height - Mouse.getEventY() * height / mc.displayHeight - 1);
            if (modalY >= 238) aeDetailOffset = Math.max(0, aeDetailOffset + (wheel > 0 ? -1 : 1));
            else aePage(wheel > 0 ? -1 : 1);
            return;
        }
        if (nbtDrawer) {
            nbtOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        (nbtPathsView ? processingList("nbtPaths", 8).tagCount() : editorNbtEntries().tagCount())
                            - (nbtCellScope ? 5 : 7)),
                    nbtOffset + (wheel > 0 ? -1 : 1)));
            return;
        }
        if (versionsDrawer) {
            versionsOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        state.data.getTagList("modVersions", 10)
                            .tagCount() - 12),
                    versionsOffset + (wheel > 0 ? -1 : 1)));
            return;
        }
        if (promptDrawer) return;
        if (resetReview) return;
        int mx = localX(Mouse.getEventX() * width / mc.displayWidth),
            my = localY(height - Mouse.getEventY() * height / mc.displayHeight - 1);
        if (!pinnedAnnotation.isEmpty() && inViewport(mx, my) && my >= 219 && my < 279) {
            pinnedAnnotationOffset = Math.max(0, pinnedAnnotationOffset + (wheel > 0 ? -1 : 1));
            return;
        }
        if (roleDropdown) {
            roleOffset = Math.max(0, Math.min(Math.max(0, hatchRoles().size() - 7), roleOffset + (wheel > 0 ? -1 : 1)));
            return;
        }
        if (libraryDrawer) {
            if (inViewport(mx, my) && !previewBlockedAt(mx, my)) {
                zoom = Math.max(.25, Math.min(4, zoom * (wheel > 0 ? 1.1 : 1 / 1.1)));
                return;
            }
            libraryOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        (HologramLibraryClient.dedicated() ? HologramLibraryClient.INSTANCE.rows(publicLibrary)
                            : state.data.getTagList("presets", 10)).tagCount() - 4),
                    libraryOffset + (wheel > 0 ? -1 : 1)));
            return;
        }
        if (positionDrawer || sourceDrawer || presetDrawer || shortcutsDrawer) return;
        if (previewInteractive() && !inventory && inPanel(mx, my)) {
            hatchOffset = Math
                .max(0, Math.min(Math.max(0, filteredHatches().size() - 2), hatchOffset + (wheel > 0 ? -1 : 1)));
            return;
        }
        if (hatchDrawer) {
            hatchOffset = Math
                .max(0, Math.min(Math.max(0, filteredHatches().size() - 5), hatchOffset + (wheel > 0 ? -1 : 1)));
            return;
        }
        if (confirmation) {
            materialOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        (resourceReview ? state.data.getTagList("blueprintResources", 10)
                            .tagCount() : state.materials.size()) - 3),
                    materialOffset + (wheel > 0 ? -1 : 1)));
        } else if (inventory && sourceTargets && mx >= 157) {
            int count = state.data.getTagList("materialChoices", 10)
                .tagCount();
            int maxOffset = Math.max(0, (count - 36 + 8) / 9 * 9);
            sourceOffset = Math.max(0, Math.min(maxOffset, sourceOffset + (wheel > 0 ? -9 : 9)));
        } else if (!optionChannel.isEmpty()) {
            NBTTagCompound cap = capability(optionChannel);
            drawerOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        cap == null ? 0
                            : cap.getTagList("options", 10)
                                .tagCount() - 7),
                    drawerOffset + (wheel > 0 ? -1 : 1)));
        } else if (drawer) {
            drawerOffset = Math
                .max(0, Math.min(Math.max(0, capabilities().tagCount() - 4), drawerOffset + (wheel > 0 ? -1 : 1)));
        } else if (inViewport(mx, my) && !previewBlockedAt(mx, my))
            zoom = Math.max(.25, Math.min(4, zoom * (wheel > 0 ? 1.1 : 1 / 1.1)));
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (processingFlow != null) {
            if (key == Keyboard.KEY_ESCAPE) activateProcessing(234);
            else if (processingFlow.stage == 0 && processingPresetField.isFocused())
                processingPresetField.textboxKeyTyped(c, key);
            else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activateProcessing(231);
            return;
        }
        if (discardReview || overwriteReview || processingPresetSaveReview) {
            if (key == Keyboard.KEY_ESCAPE
                || key == Keyboard.KEY_E && (!processingPresetSaveReview || !processingPresetField.isFocused()))
                activate(225);
            else if (processingPresetSaveReview) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activate(224);
                else processingPresetField.textboxKeyTyped(c, key);
            }
            return;
        }
        initialEditHint = savedEditHint = false;
        rememberGuidance();
        if (deidentificationDrawer) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) activateDeidentification(212);
            else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activateDeidentification(213);
            return;
        }
        if (libraryAdminDrawer) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E && !libraryUploadField.isFocused()
                && !libraryDownloadField.isFocused()
                && !libraryQuotaField.isFocused()) libraryAdminDrawer = false;
            else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activateClientLibrary(210);
            else {
                libraryUploadField.textboxKeyTyped(c, key);
                libraryDownloadField.textboxKeyTyped(c, key);
                libraryQuotaField.textboxKeyTyped(c, key);
            }
            return;
        }
        if (networkDrawer) {
            if (key == Keyboard.KEY_ESCAPE
                || key == Keyboard.KEY_E && (networkHintField == null || !networkHintField.isFocused()))
                activateNetworkBinding(201);
            else if (networkHintField != null) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activateNetworkBinding(200);
                else networkHintField.textboxKeyTyped(c, key);
            }
            return;
        }
        if (areaImporting()) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) closeRoot();
            return;
        }
        if (aeReviewNavigation != null) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) cancelAeReviewNavigation();
            return;
        }
        if (aeReview != null) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) activateAeReview(182);
            return;
        }
        if (key == Keyboard.KEY_E && !textFieldFocused()) key = Keyboard.KEY_ESCAPE;
        if (!deletePresetId.isEmpty()) {
            if (key == Keyboard.KEY_ESCAPE) activate(177);
            return;
        }
        if (nbtDrawer) {
            if (key == Keyboard.KEY_ESCAPE) nbtDrawer = false;
            else if (nbtCellScope && nbtValueIndex >= 0 && nbtValueField != null) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activate(217);
                else nbtValueField.textboxKeyTyped(c, key);
            }
            return;
        }
        if (promptDrawer || versionsDrawer) {
            if (key == Keyboard.KEY_ESCAPE) {
                promptDrawer = versionsDrawer = false;
                return;
            }
            if (promptDrawer) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activate(158);
                else promptField.textboxKeyTyped(c, key);
            }
            return;
        }
        if (key == Keyboard.KEY_G && !textFieldFocused() && canMoveSelection() && previewInteractive()) {
            moveHandles = !moveHandles;
            return;
        }
        if (captureBinding >= 0) {
            if (key == Keyboard.KEY_ESCAPE) captureBinding = -1;
            else if (!HologramKeyBindings.isModifier(key)) {
                if (keyBindings.bind(captureBinding, key, HologramKeyBindings.modifiers())) {
                    captureBinding = -1;
                    notice = keyBindings.save() ? "快捷键已保存" : "快捷键保存失败";
                } else notice = "该组合已使用或保留给模式切换，请选择其他按键";
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            if (!pinnedAnnotation.isEmpty() && annotationInteractive()) {
                pinnedAnnotation = "";
                return;
            }
            cancelQueuedEdits("已取消等待中的编辑");
            if (gizmo.axis() >= 0) {
                gizmo.cancel();
                return;
            }
            if (roleDropdown) {
                roleDropdown = false;
                return;
            }
            if (resetReview) {
                resetReview = false;
                return;
            }
            if (shortcutsDrawer) {
                shortcutsDrawer = false;
                return;
            }
            if (inventoryDragging || inventorySlot >= 0 || sourceChoice >= 0) {
                inventoryDragging = false;
                inventorySlot = sourceChoice = -1;
            } else if (inventory) inventory = false;
            else if (hatchDrawer) hatchDrawer = false;
            else if (presetDrawer) presetDrawer = false;
            else if (confirmation) confirmation = false;
            else if (positionDrawer) positionDrawer = false;
            else if (sourceDrawer) sourceDrawer = false;
            else if (!optionChannel.isEmpty()) optionChannel = "";
            else if (drawer) drawer = false;
            else closeRoot();
            return;
        }
        if (resetReview) return;
        if (key == Keyboard.KEY_T && !textFieldFocused()) return;
        if (libraryDrawer) {
            if (!resetReview) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activate(140);
                else presetNameField.textboxKeyTyped(c, key);
            }
            return;
        }
        if (!textFieldFocused() && previewInteractive()
            && HologramKeyBindings.modifiers() == 0
            && (key == Keyboard.KEY_W || key == Keyboard.KEY_A || key == Keyboard.KEY_S || key == Keyboard.KEY_D))
            return;
        if (handleEditorShortcut(key, HologramKeyBindings.modifiers())) return;
        if (positionDrawer && !confirmation) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activate(76);
            else {
                positionXField.textboxKeyTyped(c, key);
                positionYField.textboxKeyTyped(c, key);
                positionZField.textboxKeyTyped(c, key);
            }
        } else if (!optionChannel.isEmpty() && !confirmation) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) activate(68);
            else optionValueField.textboxKeyTyped(c, key);
        } else if (drawer && !confirmation) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER)
                activate((channelNameField.isFocused() || channelValueField.isFocused()) ? 67 : 30);
            else if (channelNameField.isFocused()) channelNameField.textboxKeyTyped(c, key);
            else if (channelValueField.isFocused()) channelValueField.textboxKeyTyped(c, key);
            else mainField.textboxKeyTyped(c, key);
        }
    }

    private boolean canOpenAePreview() {
        HologramState.Cell cell = selectedCell();
        return editable() && pendingSequence < 0
            && selection.indices().length == 1
            && cell != null
            && com.miaokatze.gtmb.hologram.HologramAeBlueprint.supports(cell.block(false), aePreviewPayload(cell));
    }

    private NBTTagCompound aePreviewPayload(HologramState.Cell cell) {
        // Read only the existing server-issued display snapshot. Recognition must not run native analysis.
        if (cell.targetDisplay.hasKey("nativeTile", 10)) return cell.targetDisplay.getCompoundTag("nativeTile");
        if (cell.wantId.equals(cell.id) && cell.wantMeta == cell.meta)
            return cell.actualDisplay.getCompoundTag("nativeTile");
        return new NBTTagCompound();
    }

    private void openAePreview() {
        if (!canOpenAePreview()) return;
        HologramState.Cell cell = selectedCell();
        openAePreview(cell.x, cell.y, cell.z, selectedStructureId());
    }

    private void openAePreview(int x, int y, int z, String structureId) {
        if (!editable() || pendingSequence >= 0) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", revision());
        request.setInteger("x", x);
        request.setInteger("y", y);
        request.setInteger("z", z);
        request.setString("structureId", structureId);
        awaitingAePreview = true;
        send("aePreviewOpen", request, true);
    }

    private void activate(int id) {
        if (id == 166 || id == 146) cellEditHint = false;
        if (id == 225) {
            cancelLibraryNavigation();
            if (sourceSwitchProposal != null) {
                NBTTagCompound proposal = (NBTTagCompound) sourceSwitchProposal.copy();
                sourceSwitchProposal = null;
                send("cancelSourceSwitch", proposal, false);
            }
            discardReview = overwriteReview = processingPresetSaveReview = false;
            deferredLibraryAction = -1;
            return;
        }
        if (id == 224 && processingPresetSaveReview) {
            String name = processingPresetField.getText()
                .trim();
            if (name.isEmpty()) return;
            NBTTagCompound request = new NBTTagCompound();
            request.setString("name", name);
            request.setTag(
                "policy",
                state.data.getCompoundTag("processingPolicy")
                    .copy());
            processingPresetSaveReview = false;
            send("saveProcessingPreset", request, true);
            return;
        }
        if (id == 224 && overwriteReview) {
            NBTTagCompound request = blueprintSaveOptions();
            request.setString("presetId", overwriteTarget);
            request.setBoolean("confirmOverwrite", true);
            overwriteReview = false;
            if (HologramLibraryClient.dedicated() && !publicLibrary)
                HologramLibraryClient.INSTANCE.expectDraftDownload();
            send("saveBlueprintReplace", request, true);
            presetSaveSequence = sequence;
            return;
        }
        if (id == 224 && discardReview) {
            discardReview = false;
            if (sourceSwitchProposal != null) {
                NBTTagCompound proposal = (NBTTagCompound) sourceSwitchProposal.copy();
                sourceSwitchProposal = null;
                proposal.setBoolean("confirmDiscard", true);
                send(proposal.getString("op"), proposal, true);
                return;
            }
            discardConfirmed = true;
            int action = deferredLibraryAction;
            deferredLibraryAction = -1;
            activate(action);
            discardConfirmed = false;
            return;
        }
        if (id == 222) {
            overwriteMode = !overwriteMode;
            overwriteTarget = overwriteName = "";
            return;
        }
        if (id == 223) {
            yaw += Math.PI / 2;
            return;
        }
        if (activateProcessing(id)) return;
        boolean rowSelection = id >= 14000 && id < 15000;
        boolean switching = id == 139 || id == 141 || rowSelection || id == 161;
        if (switching && (!overwriteMode || !rowSelection)
            && state.data.getBoolean("blueprintDirty")
            && !discardConfirmed) {
            discardReview = true;
            deferredLibraryAction = id;
            return;
        }
        if (overwriteMode && id >= 14000 && id < 15000) {
            NBTTagCompound row = (HologramLibraryClient.dedicated() ? HologramLibraryClient.INSTANCE.rows(publicLibrary)
                : state.data.getTagList("presets", 10)).getCompoundTagAt(id - 14000);
            overwriteTarget = row.getString("id");
            overwriteName = row.getString("name");
            return;
        }
        if (id == 140 && overwriteMode) {
            if (!processingReady()) return;
            if (overwriteTarget.isEmpty()) {
                notice = "先在列表中选择要覆盖的蓝图";
                return;
            }
            overwriteReview = true;
            return;
        }
        if (activateDeidentification(id)) return;
        if (activateClientLibrary(id)) return;
        if (id == 195) {
            send("resetBlueprintNetworks", new NBTTagCompound(), true);
            nbtDrawer = false;
            return;
        }
        if (id == 196) {
            send("openNetworkBindings", new NBTTagCompound(), true);
            networkDrawer = true;
            return;
        }
        if (areaImporting()) {
            if (id == 193) closeRoot();
            return;
        }
        if (aeReviewNavigation != null) {
            if (id == 194) cancelAeReviewNavigation();
            return;
        }
        if (aeReview != null) {
            activateAeReview(id);
            return;
        }
        if (id == 184) {
            aeReview = HologramAeReviewModel.inspection(state);
            aeReviewTab = aeReviewOffset = aeDetailOffset = 0;
            aeReviewGroup = aeReviewSelected = -1;
            return;
        }
        notice = "";
        if (id == 179) {
            openAePreview();
            return;
        }
        if (nbtDrawer && id >= 17000 && id < 18000) {
            if (nbtPathsView) {
                String path = processingList("nbtPaths", 8).getStringTagAt(id - 17000);
                if (!nbtIgnoredPaths.remove(path)) nbtIgnoredPaths.add(path);
                return;
            }
            if (nbtCellScope) {
                nbtValueIndex = id - 17000;
                nbtValueField.setText(
                    editorNbtEntries().getCompoundTagAt(nbtValueIndex)
                        .getString("value"));
                nbtValueField.setEnabled(
                    editorNbtEntries().getCompoundTagAt(nbtValueIndex)
                        .getBoolean("editable"));
                nbtValueField.setFocused(false);
                return;
            }
            String key = editorNbtEntries().getCompoundTagAt(id - 17000)
                .getString("key");
            nbtSelection.toggle(key, availableNbtKeys());
            return;
        }
        if (id >= 15000) {
            int index = id - 15000;
            if (index < hatchRoles().size()) {
                hatchRole = index;
                hatchOffset = 0;
                if (!availableTiers().contains(hatchFilterTier)) hatchFilterTier = -1;
            }
            roleDropdown = false;
            return;
        }
        if (id >= 120 && id < 120 + HologramKeyBindings.ACTIONS.length) {
            captureBinding = id - 120;
            return;
        }
        if (libraryDrawer && id >= 14000) {
            if (!editable()) return;
            NBTTagCompound preset = state.data.getTagList("presets", 10)
                .getCompoundTagAt(id - 14000);
            NBTTagCompound request = new NBTTagCompound();
            request.setString("presetId", preset.getString("id"));
            keepLibraryOffset = true;
            send("previewPreset", request, true);
            return;
        }
        if (id >= 10000) {
            if (!editable() || !canInventoryPin()) return;
            NBTTagCompound n = new NBTTagCompound();
            n.setInteger("selected", selected);
            n.setInteger("choiceindex", id - 10000);
            send("pin", n, true);
            return;
        }
        if (id >= 4000) {
            chooseOption(capability(optionChannel), id - 4000);
            return;
        }
        if (id >= 2000) {
            optionChannel = capabilities().getCompoundTagAt(id - 2000)
                .getString("id");
            drawerOffset = 0;
            optionValueField.setText(Integer.toString(capability(optionChannel).getInteger("current")));
            return;
        }
        if (id >= 90 && id <= 93) {
            configure("scope", id - 90);
            return;
        }
        if (id >= 70 && id <= 72) {
            NBTTagCompound modeConfig = configuration();
            modeConfig.setInteger("mode", id - 70);
            if (id == 72) {
                modeConfig.setBoolean("keepController", true);
            }
            send("configure", modeConfig, true);
            return;
        }
        switch (id) {
            case 217:
                if (nbtCellScope && nbtValueIndex >= 0
                    && editorNbtEntries().getCompoundTagAt(nbtValueIndex)
                        .getBoolean("editable")) {
                    NBTTagCompound entry = editorNbtEntries().getCompoundTagAt(nbtValueIndex),
                        change = new NBTTagCompound(), request = new NBTTagCompound();
                    change.setString("path", entry.getString("path"));
                    change.setString("type", entry.getString("type"));
                    change.setString("value", nbtValueField.getText());
                    NBTTagList changes = new NBTTagList();
                    changes.appendTag(change);
                    request.setTag("changes", changes);
                    request.setIntArray("indices", selection.indices());
                    request.setLong("planRevision", revision());
                    send("editBlueprintCellNbt", request, true);
                    nbtDrawer = false;
                }
                break;
            case 229:
                if (nbtValueIndex >= 0) nbtSelection.toggle(
                    editorNbtEntries().getCompoundTagAt(nbtValueIndex)
                        .getString("key"),
                    availableNbtKeys());
                break;
            case 178:
                if (editable()) HologramClient.beginAreaSelection();
                break;
            case 162:
                if (editable() && state.data.getBoolean("materialOrderingAvailable")
                    && confirmedRevision == revision()) {
                    NBTTagCompound ordering = new NBTTagCompound();
                    ordering.setLong("planRevision", confirmedRevision);
                    awaitingScan = true;
                    send("orderMissingMaterials", ordering, true);
                }
                break;
            case 166:
                if (!canEditBlueprintNbt()) break;
                openNbtEditor(false);
                break;
            case 168:
                openNbtEditor(true);
                break;
            case 169:
                resourceReview = !resourceReview;
                materialOffset = 0;
                break;
            case 170:
                nbtSelection.all(true);
                break;
            case 171:
                nbtSelection.all(false);
                break;
            case 172:
                nbtDrawer = false;
                break;
            case 173:
                if (editable()) {
                    NBTTagCompound nbt = new NBTTagCompound();
                    nbt.setBoolean("all", nbtSelection.all());
                    nbt.setTag("keys", nbtSelection.keys());
                    if (nbtCellScope && !nbtPlacement) {
                        nbt.setIntArray("indices", selection.indices());
                        NBTTagList paths = new NBTTagList();
                        for (String path : nbtIgnoredPaths) paths.appendTag(new net.minecraft.nbt.NBTTagString(path));
                        nbt.setTag("ignoredPaths", paths);
                        nbt.setLong("planRevision", revision());
                    }
                    if (nbtPlacement) nbt.setBoolean("retainCovers", nbtSelection.retainCovers());
                    nbtDrawer = false;
                    send(
                        nbtPlacement ? "configurePlacementNbt"
                            : nbtCellScope ? "configureBlueprintCellNbt" : "configureBlueprintNbt",
                        nbt,
                        true);
                }
                break;
            case 228:
                nbtPathsView = !nbtPathsView;
                nbtOffset = 0;
                break;
            case 174:
                nbtSelection.retainCovers(!nbtSelection.retainCovers());
                break;
            case 163:
            case 165:
                if (editable() && (id == 165 || !selectedStructureId().isEmpty())) {
                    NBTTagCompound structure = new NBTTagCompound();
                    structure.setString("structureId", id == 165 ? "" : selectedStructureId());
                    send("selectStructure", structure, true);
                }
                break;
            case 164:
                if (editable() && !selectedStructureId().isEmpty()) {
                    NBTTagCompound structure = new NBTTagCompound();
                    structure.setString("structureId", selectedStructureId());
                    structure.setBoolean("enabled", !structureEnabled(selectedStructureId()));
                    send("structureState", structure, true);
                }
                break;
            case 141:
                if (editable() && selectedPresetExists()) {
                    NBTTagCompound placement = new NBTTagCompound();
                    placement.setString("presetId", state.data.getString("selectedPresetId"));
                    send("choosePlacementPreset", placement, true);
                }
                break;
            case 142:
                retainNbt = !retainNbt;
                break;
            case 143:
                retainCovers = !retainCovers;
                break;
            case 227:
            case 146:
                promptDrawer = true;
                promptSummaryState = null;
                promptScope = id == 227 ? 0 : selected >= 0 ? 1 : 0;
                promptField.setText(HologramAnnotations.input(state, selection.indices(), promptScope));
                break;
            case 147:
                moveHandles = !moveHandles;
                break;
            case 148:
                blueprintTab = false;
                libraryDrawer = false;
                structureVisited = true;
                rememberGuidance();
                presetNameField.setFocused(false);
                rememberUi();
                break;
            case 149:
                blueprintTab = true;
                libraryDrawer = true;
                rememberUi();
                break;
            case 150:
                versionsDrawer = true;
                break;
            case 151:
                promptDrawer = versionsDrawer = false;
                promptField.setFocused(false);
                break;
            case 152:
            case 153:
            case 154:
                promptScope = id - 152;
                promptField.setText(HologramAnnotations.input(state, selection.indices(), promptScope));
                break;
            case 155:
            case 156:
                if (editable() && selection.size() > 0) {
                    NBTTagCompound flags = new NBTTagCompound();
                    flags.setIntArray("indices", selection.indices());
                    flags.setBoolean("excluded", id == 155);
                    send("editCellFlags", flags, true);
                }
                break;
            case 158:
                if (editable()) {
                    NBTTagCompound prompt = new NBTTagCompound();
                    prompt.setString("prompt", promptField.getText());
                    prompt.setIntArray("indices", selection.indices());
                    send(
                        promptScope == 0 ? "editBlueprintPrompt" : promptScope == 1 ? "editCellFlags" : "editHintArea",
                        prompt,
                        true);
                    notice = "提示已提交 · " + HologramAnnotations.scope(state, selection.indices(), promptScope);
                    promptDrawer = false;
                    promptField.setFocused(false);
                }
                break;
            case 159:
            case 161:
                if (editable()) {
                    NBTTagCompound transfer = new NBTTagCompound();
                    transfer.setString("presetId", state.data.getString("selectedPresetId"));
                    transfer.setString(
                        "fileId",
                        presetNameField.getText()
                            .trim());
                    send(id == 159 ? "exportBlueprint" : "importBlueprint", transfer, true);
                }
                break;
            case 160:
                if (editable()) send("calibrateBlueprintVersions", null, true);
                break;
            case 130:
                blueprintTab = true;
                libraryDrawer = true;
                resetReview = false;
                if (state.data.getBoolean("virtual") && presetNameField.getText()
                    .isEmpty()) presetNameField.setText(state.data.getString("virtualPresetName"));
                break;
            case 131:
                if (state.data.getBoolean("presetBrowseForPlacement")) send("dismissPresetBrowse", null, false);
                libraryDrawer = resetReview = false;
                presetNameField.setFocused(false);
                break;
            case 132:
            case 140:
                if (!editable() || !processingReady() || state.cells.isEmpty()) break;
                String presetName = presetNameField.getText()
                    .trim();
                if (id == 140 && presetName.isEmpty()) presetName = state.data.getString("virtualPresetName");
                if (presetName.isEmpty()) {
                    notice = "请输入预设名称";
                    break;
                }
                NBTTagCompound savePreset = new NBTTagCompound();
                savePreset.setString("name", presetName);
                savePreset.setBoolean("retainNbt", retainNbt);
                savePreset.setBoolean("retainCovers", retainCovers);
                savePreset.setBoolean(
                    "retainAllNbt",
                    !state.data.hasKey("blueprintNbtAll") || state.data.getBoolean("blueprintNbtAll"));
                if (state.data.hasKey("blueprintNbtKeys")) savePreset.setTag(
                    "retainedNbtKeys",
                    state.data.getTagList("blueprintNbtKeys", 8)
                        .copy());
                deidentificationSaveOptions(savePreset);
                libraryResult = notice = id == 140 ? "正在保存，请等待服务器确认" : "正在另存为新蓝图，请等待服务器确认";
                HologramLibraryClient.INSTANCE.expectDraftDownload();
                send("saveBlueprintAs", savePreset, true);
                presetSaveSequence = sequence;
                break;
            case 175:
                if (!editable() || !selectedPresetExists()) break;
                deletePresetId = state.data.getString("selectedPresetId");
                NBTTagList deleteRows = state.data.getTagList("presets", 10);
                for (int i = 0; i < deleteRows.tagCount(); i++) {
                    NBTTagCompound row = deleteRows.getCompoundTagAt(i);
                    if (deletePresetId.equals(row.getString("id"))) deletePresetName = row.getString("name");
                }
                presetNameField.setFocused(false);
                break;
            case 176:
                if (!editable() || deletePresetId.isEmpty()) break;
                NBTTagCompound deletion = new NBTTagCompound();
                deletion.setString("presetId", deletePresetId);
                libraryResult = notice = "正在删除蓝图，请等待服务器确认";
                send("deletePreset", deletion, true);
                presetSaveSequence = sequence;
                deletePresetId = deletePresetName = "";
                break;
            case 177:
                deletePresetId = deletePresetName = "";
                break;
            case 139:
                if (!editable() || !selectedPresetExists()) break;
                NBTTagCompound editPreset = new NBTTagCompound();
                editPreset.setString("presetId", state.data.getString("selectedPresetId"));
                blueprintTab = true;
                libraryDrawer = true;
                presetNameField.setFocused(false);
                send("editPreset", editPreset, true);
                break;
            case 134:
                if (!editable()) break;
                resetCalibration = state.data.getBoolean("structureBuilt");
                resetReview = true;
                presetNameField.setFocused(false);
                break;
            case 135:
                if (editable() && state.data.getBoolean("virtualPlacement") && !state.data.getBoolean("virtualLocked"))
                    send("lockVirtual", null, true);
                break;
            case 136:
                resetReview = false;
                break;
            case 137:
                if (editable() && resetReview) {
                    resetReview = false;
                    send(resetCalibration ? "calibrateCurrent" : "resetDraft", null, true);
                }
                break;
            case 138:
                HologramClient.openSettings();
                break;

            case 108:
                editAction("editUndo");
                break;
            case 109:
                editAction("editRedo");
                break;
            case 110:
                shortcutsDrawer = true;
                break;
            case 112:
                editAction("editCopy");
                break;
            case 113:
                editAction("rotateCell");
                break;
            case 114:
                shortcutsDrawer = false;
                captureBinding = -1;
                break;
            case 115:
                keyBindings.defaults();
                notice = keyBindings.save() ? "已恢复默认快捷键" : "快捷键保存失败";
                break;

            case 10:
                view = (view + 1) % 3;
                break;
            case 11:
                allLayers = !allLayers;
                configure("layer", layer);
                break;
            case 12:
                allLayers = false;
                layer--;
                configure("layer", layer);
                break;
            case 13:
                allLayers = false;
                layer++;
                configure("layer", layer);
                break;
            case 14:
                resetCamera();
                break;
            case 15:
                HologramClient.worldPreview = !HologramClient.worldPreview;
                if (!HologramClient.worldPreview) HologramRenderer.release();
                break;
            case 16:
            case 17:
                sourceTargets = id == 17;
                inventorySlot = sourceChoice = -1;
                inventoryDragging = false;
                sourceOffset = 0;
                break;
            case 18:
                if (editable()) {
                    sourceChoice = -1;
                    send("scan", null, true);
                }
                break;
            case 23:
                if (editable()) send("scan", null, true);
                break;
            case 26:
                if (editable()) {
                    awaitingScan = true;
                    send("scan", null, true);
                }
                break;
            case 60:
                if (state.data.getBoolean("virtual") && state.data.getString("activeStructure")
                    .isEmpty()) {
                    notice = "先选中结构轮廓并打开结构编辑，再设置该结构等级和信道";
                    break;
                }
                drawer = true;
                inventory = false;
                drawerOffset = 0;
                break;
            case 61:
                drawer = false;
                inventory = true;
                inventorySlot = sourceChoice = -1;
                sourceOffset = 0;
                if (editable()) send("scan", null, true);
                break;
            case 64:
                inventory = false;
                inventoryDragging = false;
                inventorySlot = sourceChoice = -1;
                break;
            case 65:
                pinInventory(selected);
                break;
            case 66:
                editAction("restoreCell");
                break;
            case 69:
                clearSelection();
                break;
            case 94:
                presetTier = state.data.getInteger("hatchTier");
                presetDowngrade = state.data.getBoolean("hatchDowngrade");
                presetDrawer = true;
                break;
            case 95:
                if (selectedCell() == null) break;
                requestSelectedDetails();
                inventory = false;
                inventorySlot = sourceChoice = -1;
                hatchDrawer = true;
                hatchOffset = hatchRole = 0;
                hatchFilterTier = -1;
                break;
            case 96:
                hatchDrawer = presetDrawer = false;
                break;
            case 97:
                requestSelectedDetails();
                roleDropdown = !roleDropdown;
                roleOffset = Math.max(0, Math.min(hatchRole, hatchRoles().size() - 7));
                break;
            case 98:
                hatchFilterTier = -1;
                hatchOffset = 0;
                break;
            case 117:
            case 118:
                hatchFilterTier = HologramTargetTiers
                    .steppedTier(availableTiers(), hatchFilterTier, id == 117 ? -1 : 1);
                hatchOffset = 0;
                if (hatchRole > 0 && hatchFilterTier >= 0) selectFilteredTarget();
                break;
            case 99:
            case 102:
                if (!editable()) break;
                int[] facings = cellTag().getIntArray("hatchFacings");
                int nextFacing = -1;
                if (id == 99 && facings.length > 0) {
                    nextFacing = facings[0];
                    for (int i = 0; i < facings.length; i++) if (facings[i] == cellTag().getInteger("hatchFacing")) {
                        nextFacing = facings[(i + 1) % facings.length];
                        break;
                    }
                }
                NBTTagCompound facingAction = new NBTTagCompound();
                facingAction.setInteger("selected", selected);
                facingAction.setInteger("facing", nextFacing);
                send("hatchFacing", facingAction, true);
                break;
            case 103:
                presetTier = Math.max(0, presetTier - 1);
                break;
            case 104:
                presetTier = Math.min(state.data.getInteger("hatchTierMax"), presetTier + 1);
                break;
            case 105:
                presetDowngrade = !presetDowngrade;
                break;
            case 106:
                if (!editable() || !state.data.getBoolean("supported") || !state.data.getBoolean("presetAvailable"))
                    break;
                NBTTagCompound preset = new NBTTagCompound();
                preset.setInteger("tier", presetTier);
                preset.setBoolean("downgrade", presetDowngrade);
                presetDrawer = false;
                send("hatchPreset", preset, true);
                break;
            case 107:
                if (!editable() || !state.data.getBoolean("presetActive")) break;
                presetDrawer = false;
                send("clearHatchPreset", new NBTTagCompound(), true);
                break;
            case 62:
                drawer = false;
                mainField.setFocused(false);
                channelNameField.setFocused(false);
                channelValueField.setFocused(false);
                break;
            case 63:
                optionChannel = "";
                optionValueField.setFocused(false);
                break;
            case 67:
                applyRawChannel(
                    channelNameField.getText()
                        .trim(),
                    channelValueField.getText());
                break;
            case 68:
                applyRawChannel(optionChannel, optionValueField.getText());
                break;
            case 30:
                if (!editable()) break;
                try {
                    int value = Integer.parseInt(
                        mainField.getText()
                            .trim());
                    if (value >= 1) configure("main", value);
                    else notice = "主值须为1–2147483647";
                } catch (NumberFormatException ignored) {
                    notice = "主值须为1–2147483647";
                }
                break;
            case 33:
                if (editable()) {
                    NBTTagCompound n = configuration();
                    n.setBoolean("noHatches", !state.data.getBoolean("noHatches"));
                    send("configure", n, true);
                }
                break;

            case 50:
                if (!initialStructureSetupRequired() && editable()
                    && (!state.data.getBoolean("virtual")
                        || state.data.getBoolean("virtualPlacement") && state.data.getBoolean("virtualLocked"))) {
                    if (placementReviewActive()) {
                        confirmedRevision = revision();
                        confirmation = true;
                    } else if (HologramPlacementConfirmation.forceReview(state.data, confirmedRevision))
                        confirmation = true;
                    else {
                        awaitingScan = true;
                        send("scan", configuration(), true);
                    }
                }
                break;
            case 51:
                send(state.data.getInteger("job") == 2 ? "resume" : "pause", null, true);
                break;
            case 52:
                send("cancel", null, true);
                break;
            case 54:
                confirmation = false;
                if (state.data.getBoolean("blueprintAwaitingConfirmation")) {
                    libraryDrawer = true;
                    send("cancelBlueprintPlacement", null, true);
                }
                break;
            case 55:
                if (!initialStructureSetupRequired() && editable()
                    && confirmation
                    && confirmedRevision == revision()
                    && HologramPlacementConfirmation.networksReady(state.data)
                    && (state.data.getBoolean("blueprintAwaitingConfirmation")
                        || HologramPlacementConfirmation.canBuild(state.data, confirmedRevision))) {
                    NBTTagCompound n = new NBTTagCompound();
                    n.setLong("planRevision", confirmedRevision);
                    n.setLong("placementReviewNonce", state.data.getLong("placementReviewNonce"));
                    n.setBoolean(
                        "forceExplicit",
                        HologramPlacementConfirmation.forceReview(state.data, confirmedRevision));
                    if (!state.data.getBoolean("blueprintAwaitingConfirmation")) {
                        pendingBuildReview = true;
                        pendingBuildReviewRevision = confirmedRevision;
                    }
                    confirmation = false;
                    send(
                        state.data.getBoolean("blueprintAwaitingConfirmation") ? "confirmBlueprintPlacement" : "build",
                        n,
                        true);
                }
                break;
            case 80:
            case 82:
                NBTTagCompound cap = primaryCapability();
                if (cap != null) chooseOption(cap, optionIndex(cap) + (id == 80 ? -1 : 1));
                break;
            case 81:
                NBTTagCompound primary = primaryCapability();
                if (primary != null) {
                    optionChannel = primary.getString("id");
                    optionValueField.setText(Integer.toString(primary.getInteger("current")));
                    drawerOffset = 0;
                }
                break;
            case 75:
                positionDrawer = true;
                drawer = inventory = false;
                break;
            case 76:
                addPosition();
                break;
            case 77:
                positionDrawer = false;
                positionXField.setFocused(false);
                positionYField.setFocused(false);
                positionZField.setFocused(false);
                break;
            case 78:
                sourceDrawer = true;
                drawer = inventory = positionDrawer = false;
                break;
            case 84:
            case 85:
            case 86:
                if (editable()) {
                    String key = new String[] { "materialMain", "materialContainers", "materialMe" }[id - 84];
                    NBTTagCompound sourceConfig = configuration();
                    sourceConfig.setBoolean(key, !sourceEnabled(key));
                    send("configure", sourceConfig, true);
                }
                break;
            case 87:
                configure("materialPriority", (state.data.getInteger("materialPriority") + 1) % 3);
                break;
            case 88:
                sourceDrawer = false;
                break;
            case 83:
                NBTTagCompound hatch = capability("gt_hatch");
                if (hatch != null) chooseOption(hatch, hatch.getBoolean("explicit") ? 0 : 1);
                break;
            default:
                break;
        }
    }

    private boolean handleEditorShortcut(int key, int modifiers) {
        if (!previewInteractive() || inventory || textFieldFocused() || captureBinding >= 0) return false;
        if (key == Keyboard.KEY_A && modifiers == 1) {
            selection.all(state);
            selected = selection.first();
            syncTargetSelectors();
            return true;
        }
        int binding = keyBindings.match(key, modifiers);
        if (binding >= 0) {
            editAction(HologramKeyBindings.ACTIONS[binding]);
            return true;
        }
        if (modifiers == 0 && (key == Keyboard.KEY_1 || key == Keyboard.KEY_2 || key == Keyboard.KEY_3)) {
            activate(70 + key - Keyboard.KEY_1);
            return true;
        }
        return false;
    }

    private static String roleLabel(String role) {
        switch (role) {
            case "Energy Hatch":
                return "能源仓";
            case "Dynamo Hatch":
                return "动力仓";
            case "Multi-Amp Energy Hatch":
                return "多安能源仓";
            case "Laser Source Hatch":
                return "激光源仓";
            case "Laser/Multi-Amp Energy Hatch":
                return "激光 / 多安能源仓";
            case "Laser/Multi-Amp Dynamo Hatch":
                return "激光 / 多安动力仓";
            case "Maintenance Hatch":
                return "维护仓";
            case "Muffler Hatch":
                return "消声仓";
            case "Input Bus":
                return "输入总线";
            case "Input Hatch":
                return "输入仓";
            case "Output Bus":
                return "输出总线";
            case "Output Hatch":
                return "输出仓";
            case "Solidifier Hatch":
                return "流体固化仓";
            case "Cryotheum Cooling Hatch":
                return "极寒之凛冰冷却仓";
            case "Pyrotheum Heating Hatch":
                return "烈焰之炽焱加热仓";
            default:
                return net.minecraft.util.StatCollector.translateToLocal(role);
        }
    }

    private boolean previewBlockedAt(int x, int y) {
        if (!pinnedAnnotation.isEmpty() && inViewport(x, y) && y >= 219 && y < 279) return true;
        if (inventory) return x >= 157 && x < 405 && y >= 59 && y < 257;
        return inPanel(x, y);
    }

    private boolean inPanel(int x, int y) {
        return x >= HologramScreenLayout.COMPACT_PANEL_X
            && x < HologramScreenLayout.COMPACT_PANEL_X + HologramScreenLayout.COMPACT_PANEL_WIDTH
            && y >= panelY
            && y < panelY + HologramScreenLayout.PANEL_HEIGHT;
    }

    private boolean textFieldFocused() {
        return nbtDrawer && nbtValueField != null && nbtValueField.isFocused()
            || processingFlow != null && processingPresetField != null && processingPresetField.isFocused()
            || networkDrawer && networkHintField != null && networkHintField.isFocused()
            || libraryAdminDrawer
                && (libraryUploadField.isFocused() || libraryDownloadField.isFocused() || libraryQuotaField.isFocused())
            || processingPresetSaveReview && processingPresetField != null && processingPresetField.isFocused()
            || promptDrawer && promptField.isFocused()
            || libraryDrawer && presetNameField.isFocused()
            || drawer && (mainField.isFocused() || channelNameField.isFocused() || channelValueField.isFocused())
            || !optionChannel.isEmpty() && optionValueField.isFocused()
            || positionDrawer
                && (positionXField.isFocused() || positionYField.isFocused() || positionZField.isFocused());
    }

    private void syncTargetSelectors() {
        HologramState.Cell cell = selectedCell();
        if (cell == null) {
            hatchRole = 0;
            hatchFilterTier = -1;
            return;
        }
        int index = cell.chosenChoice;
        if (index < 0 && cell.targetStack != null) for (int i = 0; i < cell.candidates.size(); i++) {
            if (cell.targetStack.isItemEqual(cell.candidates.get(i))
                && ItemStack.areItemStackTagsEqual(cell.targetStack, cell.candidates.get(i))) {
                index = i;
                break;
            }
        }
        NBTTagList roles = cellTag().getTagList("candidateRoles", 8);
        int[] tiers = cellTag().getIntArray("candidateTiers");
        hatchRole = index >= 0 && index < roles.tagCount()
            ? Math.max(0, hatchRoles().indexOf(roles.getStringTagAt(index)))
            : 0;
        hatchFilterTier = index >= 0 && index < tiers.length ? tiers[index] : -1;
        hatchOffset = 0;
    }

    private void selectFilteredTarget() {
        List<Integer> candidates = filteredHatches();
        if (!candidates.isEmpty()) activate(10000 + candidates.get(0));
        else notice = "此类型 / 等级没有合法候选，目标未改变";
    }

    private void editAction(String action) {
        boolean history = "editUndo".equals(action) || "editRedo".equals(action);
        HologramState.Cell cell = selectedCell();
        if (!history && cell == null) return;
        if (pendingSequence >= 0 && !state.data.getBoolean("capturePending")
            && !state.data.getBoolean("targetClosed")
            && state.data.getInteger("job") != 1
            && state.data.getInteger("job") != 2
            && !awaitingScan) {
            List<HologramState.Cell> frozenSelection = new ArrayList<>();
            if (!history) for (int index : selection.indices()) frozenSelection.add(state.cells.get(index));
            if (!editQueue.offer(action, state.session, history ? null : cell, frozenSelection)) {
                notice = "等待队列已满（8项），请等待校验后重试";
                return;
            }
            notice = "已排队 " + editQueue.size() + " 项编辑，等待服务端校验";
            return;
        }
        if (!editable()) {
            notice = pendingSequence >= 0 ? "等待服务端校验，请稍后再操作" : editingReason();
            return;
        }
        dispatchEdit(action, selected);
    }

    private void dispatchEdit(String action, int target) {
        dispatchEdit(action, target, selection.indices());
    }

    private void dispatchEdit(String action, int target, int[] indices) {
        if ("editUndo".equals(action) && !state.data.getBoolean("editCanUndo")
            || "editRedo".equals(action) && !state.data.getBoolean("editCanRedo")
            || "editPaste".equals(action) && !state.data.getBoolean("editCanPaste")) {
            notice = "此编辑当前不可用，未执行";
            return;
        }
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("selected", target);
        request.setIntArray("indices", indices);
        request.setIntArray("selectedIndices", indices);
        send(action, request, true);
    }

    private void cancelQueuedEdits(String reason) {
        if (editQueue.isEmpty()) return;
        editQueue.clear();
        notice = reason;
    }

    private void drainQueuedEdits() {
        if (state.data.getBoolean("capturePending") || awaitingScan
            || state.data.getBoolean("targetClosed")
            || state.data.getInteger("job") == 1
            || state.data.getInteger("job") == 2) {
            cancelQueuedEdits("结构正在采集或不可编辑，已取消等待中的编辑");
            return;
        }
        while (editable() && !editQueue.isEmpty()) {
            HologramEditQueue.Entry edit = editQueue.removeFirst(state);
            if (edit == null) {
                notice = "会话或目标格已变化，已取消等待中的编辑";
                return;
            }
            dispatchEdit(edit.action, edit.selectionIn(state), edit.indicesIn(state));
            if (pendingSequence >= 0) {
                queuedDispatchSequence = pendingSequence;
                notice = editQueue.isEmpty() ? "正在执行排队编辑" : "正在执行排队编辑，剩余 " + editQueue.size() + " 项";
            }
        }
    }

    private void applyRawChannel(String name, String raw) {
        if (!editable()) return;
        if (!name.matches("[a-z0-9_.-]{1,48}") || "_hatch_candidate".equals(name)) {
            notice = "name须为1–48位小写字母、数字、_、.或-";
            return;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) throw new NumberFormatException();
            NBTTagCompound config = configuration();
            if ("main".equals(name)) config.setInteger("main", value);
            else config.getCompoundTag("channels")
                .setInteger(name, value);
            optionChannel = "";
            send("configure", config, true);
        } catch (NumberFormatException invalid) {
            notice = "value须为1–2147483647";
        }
    }

    private void resetCamera() {
        yaw = frontCameraYaw();
        pitch = Math.PI / 6;
        zoom = 1;
        camera.reset();
        cameraFrame = 0;
    }

    private void movePreviewCamera() {
        long now = System.nanoTime(), previous = cameraFrame;
        cameraFrame = now;
        if (previous == 0 || !org.lwjgl.opengl.Display.isActive()
            || !previewInteractive()
            || textFieldFocused()
            || libraryAdminDrawer
            || networkDrawer
            || inventory
            || captureBinding >= 0
            || boxSelecting
            || gizmo.axis() >= 0
            || (HologramKeyBindings.modifiers() & 5) != 0) return;
        int right = (Keyboard.isKeyDown(Keyboard.KEY_D) ? 1 : 0) - (Keyboard.isKeyDown(Keyboard.KEY_A) ? 1 : 0);
        int forward = (Keyboard.isKeyDown(Keyboard.KEY_W) ? 1 : 0) - (Keyboard.isKeyDown(Keyboard.KEY_S) ? 1 : 0);
        double speed = Math.max(2, HologramRenderer.previewExtent() * .6) / zoom;
        camera.move(yaw, (now - previous) / 1_000_000_000.0, right, forward, speed);
    }

    private double frontCameraYaw() {
        int facing = state.data.getInteger("facing");
        if (facing < 0 || facing >= ExtendedFacing.values().length) return Math.PI / 4;
        ForgeDirection direction = ExtendedFacing.values()[facing].getDirection();
        if (direction.offsetX == 0 && direction.offsetZ == 0) return Math.PI / 4;
        return Math.atan2(direction.offsetX, direction.offsetZ) + Math.PI / 4;
    }

    private NBTTagCompound configuration() {
        NBTTagCompound n = new NBTTagCompound();
        for (String key : new String[] { "main", "facing", "mode", "scope" })
            n.setInteger(key, state.data.getInteger(key));
        n.setInteger(
            "scope",
            !state.data.getBoolean("wholeBlueprintPlacement") && !state.data.getBoolean("virtualPlacement")
                && selection.size() > 0 ? 2 : 0);
        n.setInteger("layer", layer);
        n.setInteger("selected", selected);
        n.setIntArray("selectedIndices", selection.indices());
        n.setString("structureId", state.data.getString("activeStructure"));
        n.setBoolean("noHatches", state.data.getBoolean("noHatches"));
        n.setBoolean("keepController", true);
        for (String key : new String[] { "materialMain", "materialContainers", "materialMe" })
            n.setBoolean(key, sourceEnabled(key));
        n.setInteger("materialPriority", state.data.getInteger("materialPriority"));
        n.setTag(
            "channels",
            state.data.getCompoundTag("channels")
                .copy());
        return n;
    }

    private void configure(String key, int value) {
        if (!editable()) return;
        NBTTagCompound n = configuration();
        n.setInteger(key, value);
        send("configure", n, true);
    }

    private void send(String op, NBTTagCompound n, boolean pending) {
        if (areaImporting() && !"close".equals(op) && !"cancelAeSave".equals(op) && !"aePreviewClose".equals(op))
            return;
        if (aeReviewNavigation != null && !"selectStructure".equals(op)
            && !"close".equals(op)
            && !"cancelAeSave".equals(op)
            && !"aePreviewClose".equals(op)) return;
        if (!"build".equals(op)) pendingBuildReview = false;
        if ("scan".equals(op)) cancelQueuedEdits("重新采集结构，已取消等待中的编辑");
        if (n == null) n = new NBTTagCompound();
        if (!n.hasKey("planRevision") && ("processBlueprint".equals(op) || "saveProcessingPreset".equals(op)
            || "loadProcessingPreset".equals(op)
            || "updateProcessingPreset".equals(op)
            || "deleteProcessingPreset".equals(op)
            || "configureBlueprintCellNbt".equals(op)
            || "editBlueprintCellNbt".equals(op))) n.setLong("planRevision", revision());
        if (discardConfirmed) n.setBoolean("confirmDiscard", true);
        if ("hatchPreset".equals(op) || "pin".equals(op)
            || "customPin".equals(op)
            || "sourcePin".equals(op)
            || "hatchFacing".equals(op)
            || "configure".equals(op)
                && (n.getInteger("main") != state.data.getInteger("main") || !n.getCompoundTag("channels")
                    .equals(state.data.getCompoundTag("channels")))) {
            basicConfigurationSequence = sequence + 1;
            basicConfigurationRevision = revision();
        }
        if (!n.hasKey("selected")) n.setInteger("selected", selected);
        if (!n.hasKey("selectedIndices")) n.setIntArray("selectedIndices", selection.indices());
        if (!n.hasKey("structureId")) n.setString("structureId", state.data.getString("activeStructure"));
        n.setString("session", state.session);
        n.setString("op", op);
        n.setLong("uiSequence", ++sequence);
        if ("addPosition".equals(op) || "editPaste".equals(op)
            || "editMove".equals(op)
            || "editUndo".equals(op)
            || "editRedo".equals(op)
            || "selectStructure".equals(op)) pendingSelectionSequence = sequence;
        if (pending) {
            pendingSequence = sequence;
            confirmation = false;
        }
        if (captureRenderSmokeAction) capturedRenderSmokeAction = (NBTTagCompound) n.copy();
        else HologramNetwork.sendAction(n);
    }

    /** Candidate catalogues are requested on explicit inspection, never on each preview click. */
    private void requestSelectedDetails() {
        if (editable() && selected >= 0 && state.data.getInteger("selected") != selected)
            send("configure", configuration(), true);
    }

    private boolean sameTypeSelectionHeld() {
        return smokeSameTypeSelection && captureRenderSmokeAction || Keyboard.isKeyDown(Keyboard.KEY_T);
    }

    private boolean airSelectionHeld() {
        return smokeAltSelection && captureRenderSmokeAction || Keyboard.isKeyDown(Keyboard.KEY_LMENU)
            || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    private boolean controlSelectionHeld() {
        return smokeCtrlSelection && captureRenderSmokeAction || isCtrlKeyDown();
    }

    /** Smoke hooks use real mouse hit testing, including modal gates and disabled controls. */
    int[] smokeControlPoint(int id) {
        for (Control c : controls) if (c.id == id) return new int[] { originX + Math.round((c.x + c.w / 2F) * scale),
            originY + Math.round((c.y + c.h / 2F) * scale) };
        throw new IllegalArgumentException("No drawn control " + id);
    }

    void smokeClickControl(int id) {
        requireInteractionSmoke();
        int[] p = smokeControlPoint(id);
        mouseClicked(p[0], p[1], 0);
        mouseMovedOrUp(p[0], p[1], 0);
    }

    /** Real input at a previously observed point, including controls hidden by the import overlay. */
    void smokeClickAt(int x, int y, int button) {
        requireInteractionSmoke();
        if (captureRenderSmokeAction) throw new IllegalStateException("Live clicks require actual network dispatch");
        mouseClicked(x, y, button);
        mouseMovedOrUp(x, y, button);
    }

    NBTTagCompound smokeAreaImportUiState() {
        requireInteractionSmoke();
        if (captureRenderSmokeAction)
            throw new IllegalStateException("Live import observations require actual network dispatch");
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("areaImporting", areaImporting());
        result.setInteger("areaImportProgress", state.data.getInteger("areaImportProgress"));
        result.setString("areaImportStage", state.data.getString("areaImportStage"));
        result.setBoolean("editable", editable());
        result.setLong("renderGeneration", renderGeneration);
        result.setIntArray(
            "visibleControlIds",
            controls.stream()
                .mapToInt(control -> control.id)
                .toArray());
        return result;
    }

    /** Logical coordinates of the fixed left preview and right property surface. */
    int[] smokeViewportBounds() {
        requireInteractionSmoke();
        return new int[] { viewportX, viewportY, viewportWidth, viewportHeight };
    }

    int[] smokePanelBounds() {
        requireInteractionSmoke();
        return new int[] { HologramScreenLayout.COMPACT_PANEL_X, panelY, HologramScreenLayout.COMPACT_PANEL_WIDTH,
            HologramScreenLayout.PANEL_HEIGHT };
    }

    boolean smokeKey(int key, int modifiers) {
        requireInteractionSmoke();
        return handleEditorShortcut(key, modifiers);
    }

    String smokeBinding(int index) {
        requireInteractionSmoke();
        return keyBindings.label(index);
    }

    NBTTagCompound smokeSnapshot() {
        requireInteractionSmoke();
        return (NBTTagCompound) state.data.copy();
    }

    int[] smokeSelectedIndices() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")
            && !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke"))
            throw new IllegalStateException("Selection smoke is disabled");
        return selection.indices();
    }

    int[] smokeCapturePreviewSelection(int logicalX, int logicalY, boolean sameType, boolean additive) {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        boolean previousCapture = captureRenderSmokeAction;
        capturedRenderSmokeAction = null;
        captureRenderSmokeAction = true;
        smokeSameTypeSelection = sameType;
        smokeCtrlSelection = additive;
        boolean previousHandles = moveHandles;
        moveHandles = false;
        try {
            int[] point = smokePreviewPoint(logicalX, logicalY);
            mouseClicked(point[0], point[1], 0);
            mouseMovedOrUp(point[0], point[1], 0);
            if (capturedRenderSmokeAction != null)
                throw new IllegalStateException("Local preview selection emitted a network operation");
            return selection.indices();
        } finally {
            captureRenderSmokeAction = previousCapture;
            moveHandles = previousHandles;
            smokeSameTypeSelection = smokeCtrlSelection = false;
        }
    }

    boolean smokeHasControl(int id) {
        requireInteractionSmoke();
        return drawnControlEnabled(id);
    }

    private boolean drawnControlEnabled(int id) {
        for (Control control : controls) if (control.id == id && control.enabled) return true;
        return false;
    }

    long smokeRenderGeneration() {
        requireInteractionSmoke();
        return renderGeneration;
    }

    private static void requireInteractionSmoke() {
        if (!Boolean.getBoolean("gtmb.hologram.realInteractionSmoke")
            && !Boolean.getBoolean("gtmb.hologram.followupAreaClient"))
            throw new IllegalStateException("Real interaction smoke is disabled");
    }

    boolean smokePending() {
        return pendingSequence >= 0;
    }

    /** Exercise real modal mouse routing while intercepting the packet before any network send. */
    NBTTagCompound smokeCaptureMissingBuildClick() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        NBTTagCompound initial = (NBTTagCompound) state.data.copy();
        long reviewed = confirmedRevision;
        capturedRenderSmokeAction = null;
        captureRenderSmokeAction = true;
        try {
            int[] confirm = smokeControlPoint(55);
            mouseClicked(confirm[0], confirm[1], 0);
            mouseMovedOrUp(confirm[0], confirm[1], 0);
            if (capturedRenderSmokeAction == null || !"build".equals(capturedRenderSmokeAction.getString("op"))
                || capturedRenderSmokeAction.getLong("planRevision") != reviewed)
                throw new IllegalStateException("Missing-material modal click did not emit revision-bound build");
            NBTTagCompound request = (NBTTagCompound) capturedRenderSmokeAction.copy();
            initial.setLong("uiSequence", request.getLong("uiSequence"));
            initial.setBoolean("forcePlacementReady", true);
            updateState(new HologramState(initial));
            if (!confirmation) throw new IllegalStateException("Build force reply did not reopen review");
            int[] cancel = smokeControlPoint(54);
            mouseClicked(cancel[0], cancel[1], 0);
            mouseMovedOrUp(cancel[0], cancel[1], 0);
            updateState(new HologramState(initial));
            if (confirmation)
                throw new IllegalStateException("Cancelled force review reopened on an unrelated snapshot");
            return request;
        } finally {
            captureRenderSmokeAction = false;
        }
    }

    /** Render smoke changes local presentation only; all cells/materials are from its supplied snapshot. */
    void smokeRenderScenario(String scenario) {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        confirmation = promptDrawer = versionsDrawer = libraryDrawer = nbtDrawer = resourceReview = false;
        if ("blueprint".equals(scenario)) libraryDrawer = true;
        if ("materials".equals(scenario) || "resources".equals(scenario)
            || "force-ready".equals(scenario)
            || "missing-confirm".equals(scenario)) {
            confirmation = true;
            confirmedRevision = revision();
            resourceReview = "resources".equals(scenario);
        }
        if ("prompts".equals(scenario) || "gizmo".equals(scenario)
            || scenario.startsWith("gizmo-hover-")
            || "missing".equals(scenario)) {
            selected = state.cells.isEmpty() ? -1 : 0;
            selection.click(selected, false);
            if (("gizmo".equals(scenario) || scenario.startsWith("gizmo-hover-")) && selected >= 0) {
                String id = state.cells.get(selected).structureId;
                if (!id.isEmpty()) selection.structure(state, id, false);
            }
            moveHandles = true;
            if (scenario.startsWith("gizmo-hover-")) {
                yaw = Math.PI / 4;
                pitch = Math.PI / 6;
                zoom = .45;
            }
            if ("prompts".equals(scenario)) {
                promptDrawer = true;
                promptScope = 0;
                promptField.setText(state.data.getString("blueprintPrompt"));
            }
        }
        if ("versions".equals(scenario) || "missing-source".equals(scenario)) versionsDrawer = true;
        if ("nbt-storage".equals(scenario)) openNbtEditor(false);
        if ("nbt-placement".equals(scenario)) openNbtEditor(true);
        if ("native-display-current".equals(scenario)) view = 1;
        if ("native-display-target".equals(scenario)) view = 0;
    }

    /** Native mouse routing must inspect one face without issuing a full-snapshot configure packet. */
    String smokeCaptureLocalSelection() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        if (!previewInteractive())
            throw new IllegalStateException("Selection smoke requires the plain structure surface");
        capturedRenderSmokeAction = null;
        captureRenderSmokeAction = true;
        moveHandles = false;
        try {
            for (int y = viewportY + 50; y < viewportY + viewportHeight - 80; y++) {
                for (int x = viewportX + 10; x < viewportX + viewportWidth - 10; x++) {
                    int target = HologramRenderer.pick(x, y);
                    if (target < 0 || !HologramRenderer.pickStructure(x, y)
                        .isEmpty()) continue;
                    selected = -1;
                    selection.clear();
                    int mouseX = originX + Math.round(x * scale), mouseY = originY + Math.round(y * scale);
                    mouseClicked(mouseX, mouseY, 0);
                    mouseMovedOrUp(mouseX, mouseY, 0);
                    if (selection.size() != 1 || !selection.contains(target)) continue;
                    if (capturedRenderSmokeAction != null)
                        throw new IllegalStateException("Plain preview click emitted a server operation");
                    smokeSameTypeSelection = true;
                    selection.clear();
                    selected = -1;
                    mouseClicked(mouseX, mouseY, 0);
                    mouseMovedOrUp(mouseX, mouseY, 0);
                    HologramState.Cell clicked = state.cells.get(target);
                    String active = state.data.getString("activeStructure");
                    String group = clicked.belongsToStructure(active) ? active : clicked.structureId;
                    int expected = 0;
                    for (HologramState.Cell cell : state.cells)
                        if ((group.isEmpty() ? cell.structureIds.isEmpty() : cell.belongsToStructure(group))
                            && HologramSelection.sameType(clicked, cell)) expected++;
                    if (selection.size() != expected || capturedRenderSmokeAction != null)
                        throw new IllegalStateException(
                            "Held T did not select exact block variants in its editing scope");
                    smokeSameTypeSelection = false;
                    String outlineChecked = "none";
                    outlineSearch: for (int ey = viewportY + 50; ey < viewportY + viewportHeight - 80; ey++) {
                        for (int ex = viewportX + 10; ex < viewportX + viewportWidth - 10; ex++) {
                            String outline = HologramRenderer.pickStructure(ex, ey);
                            if (outline.isEmpty()) continue;
                            int edgeX = originX + Math.round(ex * scale), edgeY = originY + Math.round(ey * scale);
                            int actualX = localX(edgeX), actualY = localY(edgeY);
                            outline = HologramRenderer.pickStructure(actualX, actualY);
                            if (outline.isEmpty()) continue;
                            selection.clear();
                            selected = -1;
                            mouseClicked(edgeX, edgeY, 0);
                            mouseMovedOrUp(edgeX, edgeY, 0);
                            int members = 0;
                            for (HologramState.Cell cell : state.cells) if (cell.belongsToStructure(outline)) members++;
                            if (selection.size() != members || capturedRenderSmokeAction != null)
                                throw new IllegalStateException(
                                    "Outline click did not select its exact structure locally");
                            outlineChecked = outline + ":" + members;
                            break outlineSearch;
                        }
                    }
                    if (!HologramRenderer.structureBounds(state)
                        .isEmpty() && "none".equals(outlineChecked))
                        throw new IllegalStateException(
                            "No actual outline hit region exercised by native selection smoke");
                    clearSelection();
                    if (selected != -1 || selection.size() != 0 || capturedRenderSmokeAction != null)
                        throw new IllegalStateException("Clearing preview selection emitted a server operation");
                    return "native face click=" + target
                        + " selected=1 T-selected="
                        + expected
                        + " outline="
                        + outlineChecked
                        + " cleared=0 packets=0";
                }
            }
            throw new IllegalStateException("No plain face hit region for local selection smoke");
        } finally {
            smokeSameTypeSelection = false;
            captureRenderSmokeAction = false;
        }
    }

    /** Exercise the actual preview mouse path; packets are captured before the network boundary. */
    String smokeCaptureV004Editor() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        NBTTagCompound fixture = (NBTTagCompound) state.data.copy();
        fixture.setBoolean("blueprintPreview", false);
        fixture.setBoolean("presetBrowseForPlacement", false);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("capturePending", false);
        fixture.setInteger("job", 0);
        fixture.setString("blueprintPrompt", "V004 icon hover and pin");
        HologramScreen probe = new HologramScreen(new HologramState(fixture));
        probe.setWorldAndResolution(mc, width, height);
        probe.captureRenderSmokeAction = true;
        try {
            probe.drawScreen(0, 0, 0);
            HologramAnnotationMarkers.Marker global = probe.visibleMarkers.get(0);
            int[] markerPoint = probe.smokePreviewPoint(global.x, global.y);
            probe.drawScreen(markerPoint[0], markerPoint[1], 0);
            if (probe.tooltip == null || !probe.tooltip.toString()
                .contains("V004 icon hover and pin"))
                throw new IllegalStateException("Annotation icon hover did not expand the persisted prompt");
            probe.mouseClicked(markerPoint[0], markerPoint[1], 0);
            probe.mouseMovedOrUp(markerPoint[0], markerPoint[1], 0);
            if (!"blueprint".equals(probe.pinnedAnnotation) || probe.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Annotation pin was not a local mouse interaction");
            probe.drawScreen(0, 0, 0);
            if (!"blueprint".equals(probe.pinnedAnnotation))
                throw new IllegalStateException("Pinned prompt disappeared when the mouse left the icon");
            probe.mouseClicked(markerPoint[0], markerPoint[1], 0);
            probe.mouseMovedOrUp(markerPoint[0], markerPoint[1], 0);
            if (!probe.pinnedAnnotation.isEmpty()) throw new IllegalStateException("Second icon click did not unpin");

            int member = -1;
            for (HologramState.Cell cell : probe.state.cells) if (!cell.structureId.isEmpty()) {
                int members = 0;
                for (HologramState.Cell other : probe.state.cells)
                    if (other.belongsToStructure(cell.structureId)) members++;
                if (members < 2) continue;
                member = cell.index;
                break;
            }
            if (member < 0) throw new IllegalStateException(
                "V004 editor smoke requires a structure with at least two real members");
            probe.selection.click(member, false);
            probe.selected = member;
            if (probe.selection.movable(probe.state))
                throw new IllegalStateException("One member of the smoke structure incorrectly became movable");
            probe.selection.structure(probe.state, probe.state.cells.get(member).structureId, false);
            for (HologramState.Cell cell : probe.state.cells) if (probe.selection.contains(cell.index)
                != cell.belongsToStructure(probe.state.cells.get(member).structureId))
                throw new IllegalStateException(
                    "Whole-structure movement selection included an outside cell or missed a member");
            probe.selected = probe.selection.first();
            probe.moveHandles = true;
            probe.drawScreen(0, 0, 0);
            if (!probe.selection.movable(probe.state))
                throw new IllegalStateException("Complete structure selection did not expose legal movement");
            NBTTagCompound copy = probe.smokeCaptureAxisDrag(true, false);
            if (!copy.getBoolean("copy"))
                throw new IllegalStateException("Ctrl released during drag lost the copy intent locked at mouse-down");
            probe.pendingSequence = probe.pendingSelectionSequence = -1;
            probe.capturedRenderSmokeAction = null;
            NBTTagCompound move = probe.smokeCaptureAxisDrag(false, true);
            if (move.getBoolean("copy"))
                throw new IllegalStateException("Ctrl pressed after drag began changed move into copy");
            probe.pendingSequence = probe.pendingSelectionSequence = -1;
            probe.capturedRenderSmokeAction = null;
            probe.smokeCtrlSelection = false;
            probe.smokeAltSelection = true;
            probe.moveHandles = false;
            probe.selection.clear();
            probe.selected = -1;
            probe.drawScreen(0, 0, 0);
            int[] air = null;
            airSearch: for (int y = viewportY + 50; y < viewportY + viewportHeight - 45; y += 2)
                for (int x = viewportX + 10; x < viewportX + viewportWidth - 10; x += 2) {
                    int[] point = probe.smokePreviewPoint(x, y);
                    int[] voxel = probe.emptyVoxelAt(probe.localX(point[0]), probe.localY(point[1]));
                    if (voxel == null) continue;
                    boolean exists = false;
                    for (HologramState.Cell cell : probe.state.cells)
                        if (cell.x == voxel[0] && cell.y == voxel[1] && cell.z == voxel[2]) exists = true;
                    if (exists) continue;
                    probe.drawScreen(point[0], point[1], 0);
                    if (!java.util.Arrays.equals(voxel, probe.emptyVoxelPreview)) throw new IllegalStateException(
                        "Alt air coordinates disagree with the projected voxel preview");
                    probe.mouseClicked(point[0], point[1], 0);
                    probe.mouseMovedOrUp(point[0], point[1], 0);
                    NBTTagCompound request = probe.capturedRenderSmokeAction;
                    if (request == null || !"addPosition".equals(request.getString("op"))
                        || request.getInteger("dx") + fixture.getInteger("x") != voxel[0]
                        || request.getInteger("dy") + fixture.getInteger("y") != voxel[1]
                        || request.getInteger("dz") + fixture.getInteger("z") != voxel[2])
                        throw new IllegalStateException("Alt mouse pick did not submit its previewed air voxel");
                    air = voxel;
                    break airSearch;
                }
            if (air == null) throw new IllegalStateException("No bounded projected air voxel exercised by mouse smoke");
            return "icon=hover,pin,unpin member-gizmo=hidden complete-gizmo=visible copy-down=true move-down=false air="
                + java.util.Arrays.toString(air);
        } finally {
            probe.captureRenderSmokeAction = probe.smokeCtrlSelection = probe.smokeAltSelection = false;
        }
    }

    String smokeCaptureV004Tabs() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        NBTTagCompound fixture = (NBTTagCompound) state.data.copy();
        String fixtureSession = java.util.UUID
            .nameUUIDFromBytes(("gtmb-tabs:" + state.session).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
        fixture.setString("session", fixtureSession);
        fixture.setBoolean("blueprintPreview", false);
        fixture.setBoolean("presetBrowseForPlacement", false);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("virtualPlacement", false);
        fixture.setString("openPurpose", "");
        fixture.setLong("placementReviewNonce", 0);
        fixture.setInteger("job", 0);
        UiMemory previousMemory = UI_MEMORY.remove(fixtureSession);
        GuiScreen caller = mc.currentScreen;
        boolean previousCapture = captureRenderSmokeAction;
        captureRenderSmokeAction = true;
        try {
            HologramScreen probe = new HologramScreen(new HologramState(fixture));
            probe.captureRenderSmokeAction = true;
            mc.displayGuiScreen(probe);
            probe.drawScreen(0, 0, 0);
            int[] structureTab = probe.smokeControlPoint(148), blueprintTab = probe.smokeControlPoint(149);
            if (structureTab[1] != blueprintTab[1] || structureTab[0] >= blueprintTab[0])
                throw new IllegalStateException("Structure and blueprint tabs are not horizontal peers");
            boolean clicked = false;
            for (int y = viewportY + 50; y < viewportY + viewportHeight - 80 && !clicked; y += 2)
                for (int x = viewportX + 10; x < viewportX + viewportWidth - 10; x += 2) {
                    int hit = HologramRenderer.pick(x, y);
                    if (hit < 0 || !HologramRenderer.pickStructure(x, y)
                        .isEmpty()) continue;
                    HologramState.Cell cell = probe.state.cells.get(hit);
                    if (cell.missingBlock || cell.sourceMissing) continue;
                    probe.smokeCapturePreviewSelection(x, y, false, false);
                    if (probe.selection.size() > 0) {
                        clicked = true;
                        break;
                    }
                }
            if (!clicked) throw new IllegalStateException("Tab persistence smoke never selected a real preview face");
            int[] selected = probe.selection.indices();
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(149);
            if (!probe.libraryDrawer) throw new IllegalStateException("Peer blueprint tab did not open the library");
            probe.drawScreen(0, 0, 0);
            NBTTagList rows = fixture.getTagList("presets", 10);
            if (rows.tagCount() == 0)
                throw new IllegalStateException("Library row smoke requires a real saved blueprint");
            int[] rowPoint = probe.smokeControlPoint(14000);
            probe.drawScreen(rowPoint[0], rowPoint[1], 0);
            if (probe.tooltip == null || !probe.tooltip.toString()
                .contains(
                    rows.getCompoundTagAt(0)
                        .getString("name")))
                throw new IllegalStateException("Flat library row hover lost its blueprint name");
            for (Control control : probe.controls) if (control.id == 14000 && control.h != 28)
                throw new IllegalStateException("Library entries reverted to action-button geometry");
            probe.smokeClickDrawnControl(14000);
            if (probe.capturedRenderSmokeAction == null
                || !"previewPreset".equals(probe.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("Flat list mouse selection did not request its blueprint preview");
            fixture.setString(
                "selectedPresetId",
                rows.getCompoundTagAt(0)
                    .getString("id"));
            fixture.setLong("uiSequence", probe.sequence);
            probe.updateState(new HologramState(fixture));
            int[] namePoint = probe.smokePreviewPoint(HologramScreenLayout.COMPACT_PANEL_X + 18, 84);
            probe.mouseClicked(namePoint[0], namePoint[1], 0);
            String before = probe.presetNameField.getText();
            probe.keyTyped('e', Keyboard.KEY_E);
            if (mc.currentScreen != probe || probe.presetNameField.getText()
                .equals(before))
                throw new IllegalStateException("Focused E closed the library instead of entering its name field");
            probe.presetNameField.setFocused(false);
            probe.keyTyped('\0', Keyboard.KEY_ESCAPE);
            smokeAssertRootClosed();
            HologramScreen reopened = new HologramScreen(new HologramState(fixture));
            reopened.captureRenderSmokeAction = true;
            mc.displayGuiScreen(reopened);
            if (!reopened.libraryDrawer || !java.util.Arrays.equals(selected, reopened.selection.indices()))
                throw new IllegalStateException("Esc library exit lost the focused tab or local face selection");
            reopened.keyTyped('e', Keyboard.KEY_E);
            smokeAssertRootClosed();
            HologramScreen again = new HologramScreen(new HologramState(fixture));
            again.captureRenderSmokeAction = true;
            mc.displayGuiScreen(again);
            fixture.setBoolean("blueprintPreview", false);
            again.updateState(new HologramState(fixture));
            if (!again.libraryDrawer || !java.util.Arrays.equals(selected, again.selection.indices()))
                throw new IllegalStateException("E exit or an ordinary snapshot changed the remembered library tab");
            fixture.setBoolean("virtualPlacement", true);
            fixture.setBoolean("virtualLocked", true);
            fixture.setBoolean("blueprintAwaitingConfirmation", true);
            fixture.setString("openPurpose", "placementReview");
            fixture.setLong("placementReviewNonce", 1);
            again.updateState(new HologramState(fixture));
            again.drawScreen(0, 0, 0);
            if (!again.libraryDrawer || !again.confirmation)
                throw new IllegalStateException("Placement review changed the remembered tab or bypassed materials");
            again.keyTyped('\0', Keyboard.KEY_ESCAPE);
            again.drawScreen(0, 0, 0);
            again.smokeClickDrawnControl(149);
            again.updateState(new HologramState(fixture));
            if (!again.libraryDrawer)
                throw new IllegalStateException("The same review nonce forced a user-selected tab back to structure");
            return "peer-tabs=horizontal flat-row=hover,select focused-E=typed Esc/E=root-close tab+selection=preserved review=materials+tab-preserved";
        } finally {
            mc.displayGuiScreen(caller);
            captureRenderSmokeAction = previousCapture;
            if (previousMemory == null) UI_MEMORY.remove(fixtureSession);
            else UI_MEMORY.put(fixtureSession, previousMemory);
        }
    }

    private void smokeAssertRootClosed() {
        if (mc.currentScreen instanceof HologramScreen || mc.theWorld != null && mc.currentScreen != null)
            throw new IllegalStateException("Esc/E did not close the root hologram UI");
    }

    String smokePlacementReviewContext() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")
            && !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke"))
            throw new IllegalStateException("UI smoke disabled");
        if (!placementReviewActive() || !confirmation)
            throw new IllegalStateException("Repeated right-click did not open the placement precheck");
        drawScreen(0, 0, 0);
        smokeControlPoint(55);
        smokeControlPoint(168);
        smokeControlPoint(169);
        boolean force = HologramPlacementConfirmation.forceReview(state.data, confirmedRevision);
        if (!force && (!lastConfirmationTitle.contains("蓝图放置预检") || !"确认放置".equals(lastConfirmationAction)))
            throw new IllegalStateException("Placement review lost its distinct title or direct confirmation action");
        long previousSequence = sequence;
        smokeClickDrawnControl(168);
        drawScreen(0, 0, 0);
        smokeClickDrawnControl(172);
        if (nbtDrawer || !confirmation || !placementReviewActive() || sequence != previousSequence)
            throw new IllegalStateException(
                "Cancelling NBT adjustment changed the placement authority or fixed position");
        drawScreen(0, 0, 0);
        smokeAssertPlacementConfirmationDispatch();
        return "purpose=" + state.data.getString("openPurpose")
            + " tab="
            + (libraryDrawer ? "blueprints" : "structure")
            + " title="
            + lastConfirmationTitle
            + " fixed="
            + placementPosition()
            + " primary="
            + lastConfirmationAction
            + " NBT/materials=direct NBT-cancel=locked primary-mouse=confirmBlueprintPlacement,both-tabs";
    }

    /** Exercise the drawn confirmation's real mouse route without dispatching or modifying the live session. */
    private void smokeAssertPlacementConfirmationDispatch() {
        for (boolean blueprints : new boolean[] { false, true }) {
            HologramScreen probe = new HologramScreen(new HologramState((NBTTagCompound) state.data.copy()));
            probe.captureRenderSmokeAction = true;
            probe.libraryDrawer = blueprints;
            // These background surfaces can remain selected while the modal covers their controls.
            probe.roleDropdown = probe.positionDrawer = probe.sourceDrawer = true;
            probe.setWorldAndResolution(mc, width, height);
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(55);
            NBTTagCompound request = probe.capturedRenderSmokeAction;
            if (request == null || !"confirmBlueprintPlacement".equals(request.getString("op"))
                || !state.session.equals(request.getString("session"))
                || request.getLong("planRevision") != revision()
                || request.getBoolean("forceExplicit")
                    != HologramPlacementConfirmation.forceReview(state.data, confirmedRevision)
                || probe.libraryDrawer != blueprints
                || probe.confirmation
                || probe.pendingSequence < 0)
                throw new IllegalStateException(
                    "Drawn placement confirmation did not submit the reviewed plan from "
                        + (blueprints ? "blueprints" : "structure")
                        + " tab: "
                        + request);
        }
    }

    String smokeConstructionScopeContext() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")
            && !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke"))
            throw new IllegalStateException("UI smoke disabled");
        drawScreen(0, 0, 0);
        boolean wholePlacement = state.data.getBoolean("wholeBlueprintPlacement")
            || state.data.getBoolean("virtualPlacement");
        int expected = !wholePlacement && selection.size() > 0 ? 2 : 0;
        if (configuration().getInteger("scope") != expected)
            throw new IllegalStateException("Construction scope differs from the visible selection guidance");
        if (wholePlacement && !lastFooterScope.contains("整份蓝图"))
            throw new IllegalStateException("Placement precheck presented a hidden partial blueprint scope");
        if (!wholePlacement && selection.size() > 0
            && (!lastFooterScope.contains("已选 " + selection.size() + " 格")
                || !constructionScopeHint().contains("Ctrl+A")))
            throw new IllegalStateException(
                "Selected construction scope did not disclose its count or whole-selection shortcut");
        if (306 + fontRendererObj.FONT_HEIGHT >= 318 || 321 + fontRendererObj.FONT_HEIGHT + 1 > DESIGN_H - 7)
            throw new IllegalStateException("Footer text overlaps another row or the window border");
        return "preview=" + lastFooterScope
            + " reviewed="
            + lastReviewedScope
            + " scope="
            + expected
            + " footer=306/321 contained status="
            + completionSummary();
    }

    private int[] smokePreviewPoint(int x, int y) {
        return new int[] { originX + Math.round(x * scale), originY + Math.round(y * scale) };
    }

    private NBTTagCompound smokeCaptureAxisDrag(boolean ctrlAtStart, boolean ctrlAtRelease) {
        smokeCtrlSelection = ctrlAtStart;
        drawScreen(0, 0, 0);
        for (int axis = 0; axis < 3; axis++) {
            double[] center = gizmo.start(), end = gizmo.end(axis);
            int sx = (int) Math.round(center[0] + (end[0] - center[0]) * .65);
            int sy = (int) Math.round(center[1] + (end[1] - center[1]) * .65);
            int[] start = smokePreviewPoint(sx, sy);
            if (!inViewport(localX(start[0]), localY(start[1])) || previewBlockedAt(localX(start[0]), localY(start[1]))
                || gizmo.pick(localX(start[0]), localY(start[1])) != axis) continue;
            mouseClicked(start[0], start[1], 0);
            if (gizmo.axis() != axis) continue;
            int[] finish = smokePreviewPoint(
                (int) Math.round(sx + (end[0] - center[0]) * .7),
                (int) Math.round(sy + (end[1] - center[1]) * .7));
            smokeCtrlSelection = ctrlAtRelease;
            mouseClickMove(finish[0], finish[1], 0, 100);
            mouseMovedOrUp(finish[0], finish[1], 0);
            if (capturedRenderSmokeAction == null || !"editMove".equals(capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("Actual RGB axis drag did not submit an editMove packet");
            return (NBTTagCompound) capturedRenderSmokeAction.copy();
        }
        throw new IllegalStateException("No legal actual RGB axis hit region exercised by v004 mouse smoke");
    }

    /** Real rendered controls and mouse dispatch; packet interception is confined to this opt-in fixture. */
    String smokeProcessingFlowContext() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        smokeLibraryNavigationFences();
        NBTTagCompound fixture = (NBTTagCompound) state.data.copy();
        fixture.setString("session", "processing-ui:" + state.session);
        fixture.setString("openPurpose", "blueprintEdit");
        fixture.setBoolean("virtual", true);
        fixture.setBoolean("blueprintSaved", false);
        fixture.setBoolean("blueprintDirty", true);
        fixture.setBoolean("blueprintProcessingRequired", true);
        fixture.setBoolean("blueprintProcessingComplete", false);
        fixture.setBoolean("processingResumeAllowed", false);
        fixture.setInteger("deidentificationMinimumLevel", 6);
        fixture.getCompoundTag("processing")
            .setInteger("minimumLevel", 0);
        if (!fixture.hasKey("processing")) {
            NBTTagCompound processing = new NBTTagCompound();
            processing.setInteger("minimumLevel", 0);
            fixture.setTag("processing", processing);
        }
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("virtualPlacement", false);
        fixture.setBoolean("virtualLocked", false);
        fixture.setBoolean("areaImporting", false);
        fixture.setBoolean("settingsOnly", false);
        fixture.setInteger("job", 0);
        fixture.setString("virtualPresetName", "Processing UI Blueprint");
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < 8; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", "processing-row-" + i);
            row.setString("name", "Processing target " + i);
            rows.appendTag(row);
        }
        fixture.setTag("presets", rows);
        fixture.setString("selectedPresetId", "processing-row-0");
        HologramScreen probe = new HologramScreen(new HologramState(fixture));
        probe.setWorldAndResolution(mc, width, height);
        probe.captureRenderSmokeAction = true;
        try {
            probe.drawScreen(0, 0, 0);
            java.util.Map<Integer, Integer> visibleControlCounts = new java.util.HashMap<>();
            for (Control control : probe.controls)
                visibleControlCounts.put(control.id, visibleControlCounts.getOrDefault(control.id, 0) + 1);
            if (visibleControlCounts.containsKey(130) || visibleControlCounts.containsKey(132)
                || visibleControlCounts.getOrDefault(140, 0) != 1
                || visibleControlCounts.getOrDefault(227, 0) != 1
                || visibleControlCounts.getOrDefault(166, 0) != 1)
                throw new IllegalStateException(
                    "Blueprint page retained a legacy shortcut or duplicate save/prompt/NBT control");
            if (probe.drawnControlEnabled(140) || probe.drawnControlEnabled(166))
                throw new IllegalStateException("Processing gate exposed saving or NBT early");
            probe.smokeClickDrawnControl(220);
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(231);
            if (probe.processingFlow.stage != 0) throw new IllegalStateException("Next advanced an untouched stage");
            probe.smokeClickDrawnControl(233);
            probe.drawScreen(0, 0, 0);
            if (!probe.drawnControlEnabled(231) || probe.processingFlow.complete()
                || probe.processingFlow.policy()
                    .getTagList("confirmedStages", 8)
                    .tagCount() != 0)
                throw new IllegalStateException(
                    "Sharing inclination did not offer defaults or implicitly confirmed stages");
            int[] choices = { 235, 240, 261, 270, 280, 291 };
            for (int stage = 0; stage < choices.length; stage++) {
                probe.drawScreen(0, 0, 0);
                if (probe.processingFlow.stage != stage || probe.processingFlow.policy()
                    .getTagList("confirmedStages", 8)
                    .tagCount() != stage)
                    throw new IllegalStateException("Stage bypassed its own Next confirmation: " + stage);
                if (stage == 1 && (!probe.drawnControlEnabled(240)
                    || HologramDeidentificationMemory.minimum(probe.state.data) != 6))
                    throw new IllegalStateException(
                        "Author raw floor zero did not permit level zero independently of the processed placement floor six");
                probe.smokeClickDrawnControl(choices[stage]);
                probe.drawScreen(0, 0, 0);
                if (!probe.drawnControlEnabled(231))
                    throw new IllegalStateException("Explicit stage choice did not open Next");
                probe.smokeClickDrawnControl(231);
            }
            NBTTagCompound process = probe.capturedRenderSmokeAction;
            if (probe.processingFlow != null || process == null
                || !"processBlueprint".equals(process.getString("op"))
                || process.getCompoundTag("policy")
                    .getTagList("confirmedStages", 8)
                    .tagCount() != 6
                || process.getCompoundTag("policy")
                    .getInteger("deidentificationLevel") != 0)
                throw new IllegalStateException("Six actual choices did not submit a complete processing policy");
            probe.drawScreen(0, 0, 0);
            if (probe.drawnControlEnabled(140))
                throw new IllegalStateException("Saving opened before the server receipt");
            fixture.setLong("uiSequence", probe.sequence);
            fixture.setBoolean("blueprintProcessingComplete", true);
            fixture.setBoolean("blueprintDirty", false);
            probe.updateState(new HologramState(fixture));
            probe.drawScreen(0, 0, 0);
            if (!probe.drawnControlEnabled(140) || !probe.drawnControlEnabled(166))
                throw new IllegalStateException("Server success did not open saving and NBT");
            probe.capturedRenderSmokeAction = null;
            double beforeYaw = probe.yaw;
            int beforeFacing = probe.state.data.getInteger("facing");
            probe.smokeClickDrawnControl(223);
            if (probe.yaw == beforeYaw || probe.state.data.getInteger("facing") != beforeFacing
                || probe.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Viewing rotation changed the construction plan");
            fixture.setBoolean("blueprintDirty", true);
            probe.updateState(new HologramState(fixture));
            probe.libraryOffset = 4;
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(14004);
            if (!probe.discardReview || probe.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Dirty row switch skipped its discard confirmation");
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(225);
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(222);
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(14004);
            if (!"processing-row-4".equals(probe.overwriteTarget) || probe.libraryOffset != 4
                || probe.capturedRenderSmokeAction != null
                || probe.libraryNavigation != null)
                throw new IllegalStateException("Overwrite target selection loaded a blueprint or lost list offset");
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(140);
            if (!probe.overwriteReview || probe.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Saving skipped the named overwrite confirmation");
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(224);
            if (!"saveBlueprintReplace".equals(probe.capturedRenderSmokeAction.getString("op"))
                || !probe.capturedRenderSmokeAction.getBoolean("confirmOverwrite")
                || !"processing-row-4".equals(probe.capturedRenderSmokeAction.getString("presetId")))
                throw new IllegalStateException("Confirmed overwrite used the wrong target");
            fixture.setLong("uiSequence", probe.sequence);
            fixture.setString("workspaceTab", "blueprint");
            fixture.setString("openPurpose", "");
            NBTTagCompound proposal = new NBTTagCompound();
            proposal.setString("op", "openController");
            proposal.setString("session", fixture.getString("session"));
            proposal.setLong("planRevision", fixture.getLong("planRevision"));
            proposal.setLong("sourceSwitchNonce", 701);
            proposal.setInteger("x", 17);
            proposal.setInteger("y", 72);
            proposal.setInteger("z", -9);
            proposal.setString("presetId", "server-held-controller");
            fixture.setTag("discardTargetAction", proposal);
            fixture.setBoolean("discardConfirmationRequired", true);
            probe.capturedRenderSmokeAction = null;
            probe.updateState(new HologramState(fixture));
            probe.drawScreen(0, 0, 0);
            if (!probe.discardReview || !probe.libraryDrawer) throw new IllegalStateException(
                "A guarded world source did not preserve the authoritative blueprint workspace");
            probe.smokeClickDrawnControl(225);
            if (!"cancelSourceSwitch".equals(probe.capturedRenderSmokeAction.getString("op"))
                || probe.capturedRenderSmokeAction.getLong("sourceSwitchNonce") != 701)
                throw new IllegalStateException("Cancel did not cancel the server-held source proposal");
            fixture.setLong("uiSequence", probe.sequence);
            proposal.setString("op", "importArea");
            proposal.setLong("sourceSwitchNonce", 702);
            probe.updateState(new HologramState(fixture));
            probe.capturedRenderSmokeAction = null;
            probe.drawScreen(0, 0, 0);
            probe.smokeClickDrawnControl(224);
            NBTTagCompound sourceRequest = probe.capturedRenderSmokeAction;
            if (sourceRequest == null || !"importArea".equals(sourceRequest.getString("op"))
                || !sourceRequest.getBoolean("confirmDiscard")
                || sourceRequest.getLong("sourceSwitchNonce") != 702
                || sourceRequest.getLong("planRevision") != proposal.getLong("planRevision")
                || sourceRequest.getInteger("x") != 17
                || sourceRequest.getInteger("y") != 72
                || sourceRequest.getInteger("z") != -9)
                throw new IllegalStateException("Confirm dropped the server-held source proposal context or nonce");
            return "six-next-confirmations=PASS author-raw-floor-zero-with-processed-six=PASS inclination-defaults-without-confirm=PASS save-NBT-server-gate=PASS view-only-rotation=PASS dirty-switch=PASS overwrite-target-only=PASS scroll-preserved=PASS world-source-confirm-cancel-context=PASS";
        } finally {
            probe.captureRenderSmokeAction = false;
        }
    }

    /** Pure snapshot admission checks; this regression does not construct a client window or invoke OpenGL. */
    static String smokeLibraryNavigationFences() {
        Object world = new Object();
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "library-source");
        source.setString("generation", "library-source");
        source.setLong("planRevision", 17);
        source.setInteger("dimension", 0);
        HologramState original = new HologramState(source);
        String local = "local_00000000-0000-0000-0000-000000000001";
        LibraryNavigation navigation = new LibraryNavigation(original, world, local, true, "blueprintBrowse", 3);
        NBTTagCompound heartbeat = (NBTTagCompound) source.copy();
        heartbeat.setLong("uiSequence", 100);
        heartbeat.setBoolean("blueprintOperationSuccess", false);
        HologramState stillSource = new HologramState(heartbeat);
        if (!navigation.source(stillSource, world, false) || navigation.destination(stillSource)
            || navigation.offset != 3)
            throw new IllegalStateException(
                "An unrelated or failed same-session reply consumed the library navigation");
        NBTTagCompound target = (NBTTagCompound) source.copy();
        target.setString("session", "library-target");
        target.setString("generation", "library-target");
        target.setBoolean("virtual", true);
        target.setBoolean("open", true);
        target.setString("openPurpose", "blueprintBrowse");
        target.setString("libraryLocalId", local);
        HologramState loaded = new HologramState(target);
        if (!navigation.source(stillSource, world, false) || !navigation.destination(loaded))
            throw new IllegalStateException("The explicitly selected private target lost its cross-session handoff");
        for (String mismatch : new String[] { "target", "generation", "purpose", "placement", "dimension" }) {
            NBTTagCompound wrong = (NBTTagCompound) target.copy();
            if ("target".equals(mismatch)) wrong.setString("libraryLocalId", "local-other");
            else if ("generation".equals(mismatch)) wrong.setString("generation", "stale-generation");
            else if ("purpose".equals(mismatch)) wrong.setString("openPurpose", "blueprintEdit");
            else if ("placement".equals(mismatch)) wrong.setBoolean("virtualPlacement", true);
            else wrong.setInteger("dimension", 1);
            if (navigation.destination(new HologramState(wrong)))
                throw new IllegalStateException("Unrelated library destination accepted: " + mismatch);
        }
        NBTTagCompound changed = (NBTTagCompound) source.copy();
        changed.setLong("planRevision", 18);
        if (navigation.source(new HologramState(changed), world, false)
            || navigation.source(original, new Object(), false)
            || navigation.source(original, world, true))
            throw new IllegalStateException("A changed source, world or library view reused an old navigation");
        changed = (NBTTagCompound) source.copy();
        changed.setString("generation", "replaced-generation");
        if (navigation.source(new HologramState(changed), world, false))
            throw new IllegalStateException("A replaced source generation reused an old navigation");
        navigation.cancel();
        if (navigation.source(original, world, false) || navigation.destination(loaded))
            throw new IllegalStateException("A cancelled or consumed library handoff accepted a delayed target");
        String publicId = "public_00000000-0000-0000-0000-000000000002";
        LibraryNavigation publicNavigation = new LibraryNavigation(
            original,
            world,
            publicId,
            false,
            "blueprintEdit",
            3);
        target.setString("selectedPresetId", publicId);
        target.setString("openPurpose", "blueprintEdit");
        if (!publicNavigation.source(original, world, true) || !publicNavigation.destination(new HologramState(target)))
            throw new IllegalStateException("The explicitly selected public edit target lost its scoped handoff");
        target.setString("selectedPresetId", "public-other");
        if (publicNavigation.destination(new HologramState(target)))
            throw new IllegalStateException("A different public target reused the library navigation");
        return "library-explicit-target-handoff=PASS heartbeat-retained=PASS source-world-generation-fences=PASS cancel-consumed=PASS";
    }

    /** Exercise the library controls with intercepted packets and explicit server success/failure replies. */
    String smokeCaptureLibraryWorkflow() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        NBTTagCompound fixture = (NBTTagCompound) state.data.copy();
        fixture.setString("session", "smoke-library:" + state.session);
        fixture.setString("openPurpose", "");
        fixture.setLong("placementReviewNonce", 0);
        fixture.setBoolean("virtual", true);
        fixture.setBoolean("settingsOnly", false);
        fixture.setBoolean("supported", true);
        fixture.setBoolean("blueprintPreview", true);
        fixture.setBoolean("presetBrowseForPlacement", true);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("blueprintDirty", false);
        fixture.setBoolean("blueprintProcessingRequired", true);
        fixture.setBoolean("blueprintProcessingComplete", true);
        fixture.setString("virtualPresetId", "smoke-ui-entry");
        fixture.setString("virtualPresetName", "Smoke UI Blueprint");
        fixture.setString("selectedPresetId", "smoke-ui-entry");
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", "smoke-ui-entry");
        row.setString("name", "Smoke UI Blueprint");
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        fixture.setTag("presets", rows);
        HologramScreen browse = new HologramScreen(new HologramState(fixture));
        browse.setWorldAndResolution(mc, width, height);
        if (!browse.libraryDrawer || browse.confirmation)
            throw new IllegalStateException("UI library entry incorrectly opened materials before choosing placement");
        browse.captureRenderSmokeAction = true;
        try {
            browse.drawScreen(0, 0, 0);
            browse.smokeClickDrawnControl(140);
            if (browse.capturedRenderSmokeAction == null
                || !"saveBlueprintAs".equals(browse.capturedRenderSmokeAction.getString("op"))
                || browse.capturedRenderSmokeAction.hasKey("prompt"))
                throw new IllegalStateException(
                    "Save did not preserve server-authoritative prompt / original ID operation");
            fixture.setLong("uiSequence", browse.sequence);
            fixture.setString("status", "保存失败：Smoke write failure");
            browse.updateState(new HologramState(fixture));
            if (!browse.libraryDrawer || !browse.libraryResult.contains("Smoke write failure"))
                throw new IllegalStateException("Save failure disappeared from the library");
            browse.drawScreen(0, 0, 0);
            browse.smokeClickDrawnControl(140);
            if (!"saveBlueprintAs".equals(browse.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("Save As did not request a distinct new library entry");
            fixture.setLong("uiSequence", browse.sequence);
            fixture.setString("status", "Smoke saved as new blueprint");
            browse.updateState(new HologramState(fixture));
            browse.drawScreen(0, 0, 0);
            browse.capturedRenderSmokeAction = null;
            browse.smokeClickDrawnControl(175);
            browse.drawScreen(0, 0, 0);
            if (!"smoke-ui-entry".equals(browse.deletePresetId) || browse.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Delete bypassed the named confirmation");
            browse.smokeClickDrawnControl(177);
            if (!browse.deletePresetId.isEmpty() || browse.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Delete cancellation emitted an operation");
            browse.drawScreen(0, 0, 0);
            browse.smokeClickDrawnControl(175);
            browse.drawScreen(0, 0, 0);
            browse.smokeClickDrawnControl(176);
            if (!"deletePreset".equals(browse.capturedRenderSmokeAction.getString("op"))
                || !"smoke-ui-entry".equals(browse.capturedRenderSmokeAction.getString("presetId")))
                throw new IllegalStateException("Confirmed delete did not target the reviewed library ID");
            fixture.setLong("uiSequence", browse.sequence);
            fixture.setString("status", "删除失败：Smoke delete failure");
            browse.updateState(new HologramState(fixture));
            if (!browse.libraryDrawer || !browse.libraryResult.contains("Smoke delete failure"))
                throw new IllegalStateException("Delete failure disappeared from the library");
            browse.drawScreen(0, 0, 0);
            browse.capturedRenderSmokeAction = null;
            browse.smokeClickDrawnControl(141);
            if (browse.capturedRenderSmokeAction == null
                || !"choosePlacementPreset".equals(browse.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("Library Place did not choose the reviewed blueprint");
            fixture.setBoolean("blueprintPreview", false);
            fixture.setBoolean("presetBrowseForPlacement", false);
            fixture.setBoolean("virtualPlacement", true);
            fixture.setBoolean("virtualLocked", false);
            HologramScreen ghost = new HologramScreen(new HologramState(fixture));
            ghost.setWorldAndResolution(mc, width, height);
            if (ghost.confirmation)
                throw new IllegalStateException("Moving placement opened materials before its position was locked");
            fixture.setBoolean("virtualLocked", true);
            fixture.setBoolean("blueprintAwaitingConfirmation", true);
            HologramScreen locked = new HologramScreen(new HologramState(fixture));
            locked.setWorldAndResolution(mc, width, height);
            if (!locked.confirmation || locked.libraryDrawer != ghost.libraryDrawer)
                throw new IllegalStateException("Placement review did not retain its tab with mandatory materials");
            return "UI library Place=ghost locked=materials Save=original SaveAs=new Delete=reviewed-ID cancel=no-packet failures=visible";
        } finally {
            browse.captureRenderSmokeAction = false;
        }
    }

    private void smokeClickDrawnControl(int id) {
        int[] point = smokeControlPoint(id);
        mouseClicked(point[0], point[1], 0);
        mouseMovedOrUp(point[0], point[1], 0);
    }

    /** Find actual overlapping projected hit regions, then exercise the complete GUI tooltip render. */
    String smokeRenderGizmoHover(String scenario) {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        boolean structure = !"gizmo-hover-hint".equals(scenario);
        boolean hint = !"gizmo-hover-structure".equals(scenario);
        for (int y = viewportY + 20; y < viewportY + viewportHeight - 20; y++) {
            for (int x = viewportX + 20; x < viewportX + viewportWidth - 20; x++) {
                int mouseX = originX + Math.round(x * scale), mouseY = originY + Math.round(y * scale);
                int mx = localX(mouseX), my = localY(mouseY);
                if (gizmo.pick(mx, my) < 0) continue;
                String pickedStructure = HologramRenderer.pickStructure(mx, my);
                String pickedHint = HologramRenderer.hintAt(mx, my);
                if (structure != !pickedStructure.isEmpty() || hint != !pickedHint.isEmpty()) continue;
                drawScreen(mouseX, mouseY, 0);
                List<String> expected = new ArrayList<>();
                expected.add("拖动轴移动；按住 Ctrl 开始拖动即复制；松开提交规划");
                if (hint) expected.addAll(fontRendererObj.listFormattedStringToWidth("提示区 " + pickedHint, 260));
                if (structure) expected.add("移动轴重叠结构 · 点击边缘整选");
                if (!expected.equals(tooltip)) throw new IllegalStateException(
                    "Overlapping gizmo tooltip mismatch: " + tooltip + " expected " + expected);
                return "mouse=" + mx + "," + my + " tooltip=" + tooltip;
            }
        }
        throw new IllegalStateException("No projected gizmo/overlay overlap for " + scenario);
    }

    String smokeLayout() {
        if (controls.isEmpty()) throw new IllegalStateException("Screen has not rendered controls");
        for (Control c : controls) if (c.x < 0 || c.y < 0 || c.x + c.w > DESIGN_W || c.y + c.h > DESIGN_H)
            throw new IllegalStateException("Control outside design: " + c.id);
        return "gui=" + width
            + "x"
            + height
            + " design="
            + DESIGN_W
            + "x"
            + DESIGN_H
            + " scale="
            + scale
            + " viewport="
            + viewportWidth
            + "x"
            + viewportHeight
            + " cells="
            + state.cells.size()
            + " controls="
            + controls.size();
    }

    private static final class Control {

        final int id, x, y, w, h;
        final boolean enabled;

        Control(int id, int x, int y, int w, int h, boolean enabled) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.enabled = enabled;
        }

        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
