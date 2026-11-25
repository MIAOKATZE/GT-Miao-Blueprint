// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.hologram.HologramLocalControllerCapture;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;

/** Minecraft screen adapter. Domain state and behavior live in composed workflow modules. */
public final class HologramScreen extends HologramMuiCanvasScreen implements HologramUiHost {

    private static final long LOCAL_CONTROLLER_FRAME_NANOS = 3_000_000L;
    private static final int LOCAL_CONTROLLER_FRAME_ROWS = 8_192;

    HologramUiModules ui = new HologramUiModules(this);
    private final HologramEditorWorkspaces.Graphs workspaceGraphs = new HologramEditorWorkspaces.Graphs();
    private final java.util.Set<HologramUiModules> initializedGraphs = java.util.Collections
        .newSetFromMap(new java.util.IdentityHashMap<>());
    private com.cleanroommc.modularui.widget.ParentWidget<?> workspaceMount;
    private boolean websiteConfirmationResumePending;
    private boolean detached, detachedStarted, detachedReturned, detachedReplay;
    private String detachedTopic;
    private Runnable detachedReturn;
    private long controllerRequest;
    private TileEntity openingController;
    private Object openingConnection;
    private ItemStack openingTool;
    private String openingToolId;
    private int openingSlot;
    private HologramLocalControllerCapture localController;
    private HologramState.Preparation localControllerPreparation;
    private HologramState.ControllerDetails localControllerDetails;
    private HologramState localControllerTarget;
    private int localControllerSide;
    private HologramState localControllerPrevious;
    private HologramState localControllerBaseline;
    private java.util.List<HologramState.Cell> localControllerRenderedCells;
    private HologramState.Cell localControllerInteractionCell;
    private net.minecraft.nbt.NBTTagList localControllerCapabilities;
    private final java.util.Map<Integer, EarlyControllerEdit> earlyControllerEdits = new java.util.LinkedHashMap<>();

    private static final class EarlyControllerEdit {
        private final HologramState.Cell original;
        private final NBTTagCompound row;

        EarlyControllerEdit(HologramState.Cell original, NBTTagCompound row) {
            this.original = original;
            this.row = row;
        }

        HologramState.Cell original() {
            return original;
        }

        NBTTagCompound row() {
            return row;
        }

        boolean matches(HologramState.Cell refreshed) {
            return original.tag().equals(refreshed.tag());
        }
    }
    private final java.util.Set<Integer> localNativeChoices = new java.util.LinkedHashSet<>();
    private NBTTagCompound localControllerToolSettings, pendingControllerToolSettings;
    private boolean locallyReplacingView;
    private HologramState suspendedLocalBlueprint;
    private volatile java.util.UUID localExecutionRequest;
    private boolean localExecutionSent;
    private boolean localCompactNative;
    private long nativeCompactReceiptDeadline;
    private long nativePreparationUnits;
    private String nativeCompactAssignedTool = "";

    private final Object openedWorld, openedConnection, openedPlayer;
    private final int openedDimension;
    private final long openedPresentationResources;

    HologramScreen(HologramState state) {
        this(state, 0);
    }

    private HologramScreen(HologramState state, long modulesStarted) {
        super("workstation");
        Minecraft client = minecraft();
        openedWorld = client.theWorld;
        openedConnection = client.getNetHandler();
        openedPlayer = client.thePlayer;
        openedDimension = client.thePlayer == null ? Integer.MIN_VALUE : client.thePlayer.dimension;
        openedPresentationResources = HologramTextures.resourceGeneration();
        HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.WORKBENCH_SCREEN_MODULES, modulesStarted);
        long sessionStarted = modulesStarted == 0 ? 0 : HologramRuntimeMetrics.start();
        try {
            ui.session.initialize(state);
            workspaceGraphs.retain(state, () -> ui);
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.WORKBENCH_SESSION_INITIALIZE, sessionStarted);
        }
    }

    HologramScreen(TileEntity tile, int dimension, long request) {
        this(controllerWaitingState(dimension, tile.xCoord, tile.yCoord, tile.zCoord));
        controllerRequest = request;
        rememberControllerContext(tile);
    }

    static HologramScreen localWorkspace(ItemStack tool, int dimension) {
        return new HologramScreen(
            localWorkspaceState(HologramLocalControllerCapture.toolSettings(tool), dimension, "blueprint"));
    }

    private static HologramState localWorkspaceState(NBTTagCompound settings, int dimension, String workspace) {
        NBTTagCompound data = new NBTTagCompound();
        for (String key : new String[] { "main", "mainExplicit", "channels", "mode", "scope", "layer", "noHatches",
            "materialMain", "materialContainers", "materialMe", "materialPriority" })
            if (settings.hasKey(key)) data.setTag(
                key,
                settings.getTag(key)
                    .copy());
        data.setString("session", "local-workspace:" + java.util.UUID.randomUUID());
        data.setString("editorWorkspace", workspace);
        data.setString("workspaceTab", "projection".equals(workspace) ? "structure" : "blueprint");
        data.setBoolean("localWorkspace", true);
        data.setBoolean("virtual", true);
        data.setBoolean("blueprintPreview", !"projection".equals(workspace));
        data.setInteger("dimension", dimension);
        data.setInteger("selected", -1);
        data.setString("title", "全息构造器");
        data.setString("status", "右击真实控制器导入结构，或在蓝图库选择蓝图后点击放置");
        return new HologramState(data);
    }

    static HologramScreen localController(TileEntity tile, int dimension, int side) {
        long started = HologramRuntimeMetrics.start();
        try {
            HologramLocalControllerCapture capture;
            NBTTagCompound settings;
            HologramState state;
            try {
                ItemStack tool = Minecraft.getMinecraft().thePlayer.getHeldItem();
                settings = HologramLocalControllerCapture.toolSettings(tool);
                capture = new HologramLocalControllerCapture(tile, dimension, side, tool, settings, "");
                NBTTagCompound opening = capture.opening();
                opening.setString("status", HologramClient.text("controllerLocalPreparing", "正在读取本地标准结构…"));
                state = new HologramState(opening);
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.WORKBENCH_CONTROLLER_CAPTURE, started);
            }
            HologramScreen screen = new HologramScreen(state, HologramRuntimeMetrics.start());
            screen.localController = capture;
            screen.localControllerSide = side;
            screen.localControllerToolSettings = settings;
            screen.rememberControllerContext(tile);
            return screen;
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.WORKBENCH_CONTROLLER_OPENING, started);
        }
    }

    private void rememberControllerContext(TileEntity tile) {
        Minecraft client = minecraft();
        openingController = tile;
        openingConnection = client.getNetHandler();
        openingTool = client.thePlayer.getHeldItem();
        openingToolId = toolId(openingTool);
        openingSlot = client.thePlayer.inventory.currentItem;
    }

    boolean localControllerStandard() {
        return ui.session.state().data.getBoolean("localStandardController");
    }

    /** Read-only ownership check for the detached material pane and its deferred controls. */
    boolean localMaterialOwnerCurrent(HologramUiModules graph) {
        Minecraft client = minecraft();
        return client != null && client.currentScreen == this
            && ui == graph
            && localControllerStandard()
            && openedPlayer != null
            && openedPlayer == client.thePlayer
            && openedDimension == client.thePlayer.dimension
            && controllerContextCurrent(false);
    }

    HologramLocalMachineState localMachineState() {
        if (!localControllerStandard() || !controllerContextCurrent(false)) return HologramLocalMachineState.UNKNOWN;
        return HologramLocalMachineState.read(openingController);
    }

    boolean localWorkspaceView() {
        return localControllerStandard() || ui.session.localDocument()
            || ui.session.state().data.getBoolean("localWorkspace");
    }

    void switchLocalWorkspace(NBTTagCompound request) {
        HologramState current = ui.session.state();
        String next = request.getString("editorWorkspace");
        if ("blueprint".equals(next) && localControllerStandard()) {
            NBTTagCompound proposal = current.data.getCompoundTag("discardTargetAction");
            if (proposal.getString("sourceSwitchNonce")
                .isEmpty()
                || !proposal.getString("sourceSwitchNonce")
                    .equals(request.getString("sourceSwitchNonce"))) {
                NBTTagCompound confirm = new NBTTagCompound();
                confirm.setString("op", "switchEditorWorkspace");
                confirm.setString("editorWorkspace", "blueprint");
                confirm.setString("session", current.session);
                confirm.setLong("planRevision", current.data.getLong("planRevision"));
                confirm.setString(
                    "sourceSwitchNonce",
                    java.util.UUID.randomUUID()
                        .toString());
                NBTTagCompound changes = new NBTTagCompound();
                changes.setBoolean("discardConfirmationRequired", true);
                changes.setString("discardTargetOp", "switchEditorWorkspace");
                changes.setTag("discardTargetAction", confirm);
                updateState(current.withMetadata(changes));
                return;
            }
            retireLocalController();
            localControllerBaseline = null;
            localNativeChoices.clear();
        }
        if ("projection".equals(next)) suspendedLocalBlueprint = current;
        if ("blueprint".equals(next) && suspendedLocalBlueprint != null) {
            updateState(suspendedLocalBlueprint);
            suspendedLocalBlueprint = null;
        } else if ("blueprint".equals(next) || "projection".equals(next))
            updateState(localWorkspaceState(current.data, current.data.getInteger("dimension"), next));
    }

    void cancelLocalSourceSwitch() {
        NBTTagCompound changes = new NBTTagCompound();
        changes.setBoolean("discardConfirmationRequired", false);
        changes.setString("discardTargetOp", "");
        changes.setTag("discardTargetAction", new NBTTagCompound());
        updateState(
            ui.session.state()
                .withMetadata(changes));
    }

    void clearLocalWorkspace(NBTTagCompound request) {
        HologramState current = ui.session.state();
        String nonce = current.data.getCompoundTag("discardTargetAction")
            .getString("sourceSwitchNonce");
        if ((localControllerStandard() || ui.session.localDocument() && ui.session.document()
            .dirty())
            && (request == null || nonce.isEmpty() || !nonce.equals(request.getString("sourceSwitchNonce")))) {
            NBTTagCompound confirm = new NBTTagCompound();
            confirm.setString("op", "clearWorkspace");
            confirm.setString("session", current.session);
            confirm.setLong("planRevision", current.data.getLong("planRevision"));
            confirm.setString(
                "sourceSwitchNonce",
                java.util.UUID.randomUUID()
                    .toString());
            NBTTagCompound changes = new NBTTagCompound();
            changes.setBoolean("discardConfirmationRequired", true);
            changes.setString("discardTargetOp", "clearWorkspace");
            changes.setTag("discardTargetAction", confirm);
            updateState(current.withMetadata(changes));
            return;
        }
        retireLocalController();
        localControllerBaseline = null;
        localNativeChoices.clear();
        suspendedLocalBlueprint = null;
        HologramClient.rememberLocalDocument(null);
        updateState(
            localWorkspaceState(
                current.data,
                current.data.getInteger("dimension"),
                current.data.getString("editorWorkspace")));
    }

    boolean startEditableBlueprint(String operation, NBTTagCompound request) {
        if (!localWorkspaceView() || ui.session.localDocument()) return false;
        try {
            HologramState current = ui.session.state();
            if (localControllerStandard() && current.data.getBoolean("capturePending")) {
                if (HologramLocalControllerEdits.selectedOnly(operation) && earlyControllerRowReady(ui)) {
                    int index = request.getInteger("selected");
                    if (index != localControllerInteractionCell.index)
                        throw new java.io.IOException("所选结构格已变化，请重新选择");
                    EarlyControllerEdit previous = earlyControllerEdits.get(index);
                    if (previous == null && earlyControllerEdits.size() >= 8)
                        throw new java.io.IOException("已记录 8 格修改；更多位置请等结构明细就绪后再编辑。");
                    HologramState.Cell original = previous == null ? localControllerInteractionCell : previous.original();
                    NBTTagCompound row = HologramLocalControllerEdits
                        .selectedRow(localControllerInteractionCell, original, operation, request);
                    net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
                    rows.appendTag(row);
                    HologramState planned = HologramLocalControllerEdits
                        .applySelectedRows(current, rows, current.data.getLong("planRevision") + 1);
                    if (planned == current) throw new java.io.IOException("当前规划已变化，请重新选择");
                    earlyControllerEdits.put(index, new EarlyControllerEdit(original, row));
                    localControllerInteractionCell = planned.cells.get(index);
                    localControllerRenderedCells = planned.cells;
                    updateState(planned);
                }
                return true;
            }
            if (localControllerStandard() && HologramLocalControllerEdits.handles(operation)) {
                if ("customPin".equals(operation)) {
                    int slot = request.getInteger("inventorySlot");
                    ItemStack[] inventory = minecraft().thePlayer.inventory.mainInventory;
                    ItemStack expected = ItemStack.loadItemStackFromNBT(request.getCompoundTag("expectedSample"));
                    ItemStack actual = slot >= 0 && slot < inventory.length ? inventory[slot] : null;
                    if (actual == null || expected == null
                        || !actual.isItemEqual(expected)
                        || !ItemStack.areItemStackTagsEqual(actual, expected))
                        throw new java.io.IOException("库存样本已变化，请重新选择");
                }
                updateState(
                    HologramLocalControllerEdits.apply(current, localControllerBaseline, operation, request, cell -> {
                        if ("manual".equals(cell.pinScope) || cell.hatchFacing >= 0) localNativeChoices.add(cell.index);
                        else localNativeChoices.remove(cell.index);
                    }));
                return true;
            }
            com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument document;
            if (localControllerStandard() && "exportDraft".equals(operation)) {
                NBTTagCompound source = (NBTTagCompound) current.data.copy();
                net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
                for (HologramState.Cell cell : current.cells) rows.appendTag(cell.tag());
                source.setTag("cells", rows);
                document = com.miaokatze.gtmb.hologram.HologramLocalBlueprintInputs.controller(source);
            } else if (!localControllerStandard() && "addPosition".equals(operation)) {
                document = com.miaokatze.gtmb.hologram.HologramLocalBlueprintInputs.empty();
                request.removeTag("planRevision");
                document.apply(operation, request);
            } else return false;
            ui.library.openCapture(document, "");
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            ui.session.notice(failure.getMessage() == null ? "无法编辑当前结构" : failure.getMessage());
            return true;
        }
    }

    /** Generic refresh/preflight and advanced controls are not the existing local SUBMIT confirmation. */
    boolean reviewLocalConstruction(NBTTagCompound settings) {
        return reviewLocalConstruction(settings, false);
    }

    boolean reviewLocalConstruction(NBTTagCompound settings, boolean compatibilitySelected) {
        if (!localControllerStandard()) return false;
        String unavailable = nativeConstructionUnavailable(ui.session.state().data, settings, !localNativeChoices.isEmpty(),
            compatibilitySelected);
        ui.session.notice(unavailable.isEmpty() ? "请在材料页确认焦炉补建；预览与扫描不发送施工请求。" : unavailable);
        return false;
    }

    HologramClientStockNative.Site stockNativeSite() {
        return localControllerStandard() && controllerContextCurrent(false)
            ? HologramClientStockNative.choose(minecraft().theWorld, openingController) : null;
    }

    boolean confirmStockNative(NBTTagCompound settings, HologramClientStockNative.Site paneSite) {
        if (!localControllerStandard()) return false;
        if (localExecutionRequest != null || HologramClientStockNative.OWNER.pending(this)) {
            ui.session.notice("上一次施工请求尚未确认结束，请先核对现场。");
            return false;
        }
        HologramState displayed = ui.session.state();
        String blocker = HologramPlacementConfirmation.localStructureReviewBlocker(displayed);
        if (!blocker.isEmpty()) {
            ui.session.notice(blocker);
            return false;
        }
        String unavailable = nativeConstructionUnavailable(displayed.data, settings, !localNativeChoices.isEmpty(), false);
        if (!unavailable.isEmpty()) {
            ui.session.notice(unavailable);
            return false;
        }
        Minecraft client = minecraft();
        if (!controllerContextCurrent(false)) {
            ui.session.notice("控制器或手持投影仪已变化，请重新打开结构。");
            return false;
        }
        if (paneSite == null || !paneSite.equals(stockNativeSite())) {
            ui.session.notice("此短请求只支持未成型焦炉 #236，并须保留材料页显示的一个已安装 ULV 焦炉仓室；未发送施工。");
            return false;
        }
        ItemStack held = client.thePlayer.getHeldItem();
        java.util.UUID tool = HologramClientStockNative.canonicalTool(toolId(held));
        if (tool == null || !tool.toString().equals(openingToolId)) {
            ui.session.notice("投影仪身份尚未同步，请确认当前投影仪后重新打开。未发送施工。");
            return false;
        }
        var configured = com.miaokatze.gtmb.hologram.HologramMaterials.read(held);
        if (!stockNativeSettings(settings, configured)) {
            ui.session.notice("焦炉短请求仅扣主背包焦炉砖。请先启用主背包并选择主背包优先；未覆盖其他材料来源。");
            return false;
        }
        int main = settings.getInteger("main");
        if (main < 1 || main > 64 || localControllerBaseline == null || displayed.cells != localControllerBaseline.cells) {
            ui.session.notice("原生控制器设置、范围或显示内容已变化；未发送施工。");
            return false;
        }
        var scope = HologramClientStockNative.context(client);
        long sequence = com.miaokatze.gtmb.hologram.HologramNetwork.nextConstructionIntentSequence();
        if (sequence <= 0 || scope == null) return false;
        int picked = ui.preview.selection().version();
        int x = openingController.xCoord, y = openingController.yCoord, z = openingController.zCoord;
        ItemStack pinnedTool = held.copy();
        var original = new com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation(
            new com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key(java.util.UUID.randomUUID(), sequence, 0),
            client.thePlayer.dimension, tool, x, y, z, localControllerSide, openingSlot, paneSite.controllerFacing(),
            main, displayed.data.getBoolean("mainExplicit"), settings.getBoolean("noHatches"), 0,
            paneSite.hx(), paneSite.hy(), paneSite.hz(), paneSite.hatchFacing());
        var view = ui;
        java.util.function.BooleanSupplier visible = () -> minecraft().currentScreen == this && ui == view
            && localControllerStandard() && scope.same(HologramClientStockNative.context(minecraft()));
        var owner = new HologramClientStockNative.Owner(this, () -> {
            if (!visible.getAsBoolean() || ui.session.state() != displayed || !controllerContextCurrent(false)
                || ui.preview.selection().version() != picked || openingController.xCoord != x
                || openingController.yCoord != y || openingController.zCoord != z
                || minecraft().thePlayer.getHeldItem() != held || !ItemStack.areItemStacksEqual(pinnedTool, held)
                || !localNativeChoices.isEmpty()) return false;
            NBTTagCompound activeSettings = ui.config.configuration();
            return stockNativeSettings(activeSettings, com.miaokatze.gtmb.hologram.HologramMaterials.read(held))
                && activeSettings.getInteger("main") == original.main()
                && activeSettings.getBoolean("noHatches") == original.noHatches()
                && ordinaryNativeSubmit(displayed.data, activeSettings, false, false)
                && paneSite.current(minecraft().theWorld);
        }, visible, view.session::notice, this::rescanDisplayedStructure);
        return HologramClientStockNative.OWNER.submit(original, scope, owner, System.nanoTime());
    }

    private static boolean stockNativeSettings(NBTTagCompound confirmed,
        com.miaokatze.gtmb.hologram.HologramMaterials.Settings projector) {
        return confirmed.getBoolean("materialMain") && confirmed.getInteger("materialPriority") == 0
            && confirmed.hasKey("channels", 10) && confirmed.getCompoundTag("channels").hasNoTags()
            && projector.priority == 0 && com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy.eligible(projector);
    }

    static String nativeConstructionUnavailable(NBTTagCompound state, NBTTagCompound settings, boolean localEdits,
        boolean compatibilitySelected) {
        if (compatibilitySelected) return "高级兼容施工暂不可用：已停用需要传送完整结构的流程。本地材料清单和编辑保留。";
        if (state.getInteger("mode") == 1 || state.getInteger("mode") == 2)
            return "换装／拆除暂不可用：旧核对会传送完整结构。当前选择保留，未改动现场。";
        if (!ordinaryNativeSubmit(state, settings, localEdits, false))
            return "当前范围、手选目标或仓室预设还不能使用简短原生请求。规划保留，未传送或施工。";
        return "";
    }

    /** Only a full, unedited native configuration can start the short ordinary native request. */
    static boolean ordinaryNativeSubmit(NBTTagCompound state, NBTTagCompound settings, boolean localEdits,
        boolean compatibilitySelected) {
        return !compatibilitySelected && state.getInteger("mode") == 0 && settings.getInteger("scope") == 0
            && settings.getIntArray("selectedIndices").length == 0 && !state.getBoolean("presetActive")
            && settings.getString("structureId").isEmpty() && !localEdits;
    }

    private void localExecutionFailed(java.util.UUID id, String reason) {
        if (!id.equals(localExecutionRequest)) return;
        localExecutionRequest = null;
        localExecutionSent = false;
        localCompactNative = false;
        nativeCompactReceiptDeadline = 0;
        ui.session.notice(reason == null || reason.isEmpty() ? "施工清单准备失败" : reason);
    }

    /** A regular local native workspace no longer upgrades an old virtual geometry review into execution. */
    boolean acceptsLocalExecution(HologramState snapshot) {
        return false;
    }

    void receiveNativePlacementProgress(NBTTagCompound progress) {
        java.util.UUID request = localExecutionRequest;
        if (!localCompactNative || nativeCompactReceiptDeadline == 0 || request == null
            || !request.toString().equals(progress.getString("placementRequestId")) || !progress.hasKey("done", 4))
            return;
        long units = progress.getLong("done");
        if (units <= nativePreparationUnits) return;
        nativePreparationUnits = units;
        nativeCompactReceiptDeadline = System.nanoTime() + 35_000_000_000L;
        String phase = progress.getString("phase");
        ui.session.notice("placement.admission".equals(phase) ? "正在复核施工范围和库存…"
            : "placement.plan".equals(phase) ? "正在计算原生结构材料…" : "正在采集服务器原生结构…");
    }

    boolean receiveLocalMaterialCheck(NBTTagCompound receipt) {
        java.util.UUID execution = localExecutionRequest;
        if (execution != null && execution.toString()
            .equals(receipt.getString("placementRequestId"))) {
            if (!receipt.getBoolean("placementRequestAccepted")) {
                localExecutionFailed(execution, receipt.getString("errorMessage"));
            } else if (localCompactNative) {
                nativeCompactReceiptDeadline = 0;
                if (receipt.getBoolean("nativeCompactJob")) {
                    ui.session.notice(receipt.getString("status"));
                    if (receipt.getInteger("job") >= 3) {
                        localExecutionRequest = null;
                        localExecutionSent = localCompactNative = false;
                        rescanDisplayedStructure();
                    }
                } else if (receipt.getBoolean("nativeCompactStarted") && receipt.getInteger("slot") == openingSlot
                    && minecraft().thePlayer != null) {
                        String assigned = receipt.getString("toolId");
                        ItemStack held = minecraft().thePlayer.getHeldItem();
                        if (openingToolId.isEmpty() && !assigned.isEmpty()
                            && (held == openingTool || assigned.equals(toolId(held))))
                            nativeCompactAssignedTool = assigned;
                        ui.session.notice("原生施工已开始。");
                    }
            }
            return true;
        }
        return false; // No local material operation uses the retired network request.
    }

    void rescanDisplayedStructure() {
        if (localControllerStandard()) {
            refreshLocalController(null);
            return;
        }
        Minecraft client = minecraft();
        HologramState snapshot = ui.session.state();
        NBTTagCompound data = snapshot.data;
        if (ui.session.localDocument() || !data.getBoolean("target")
            || data.getBoolean("virtual")
            || client.theWorld == null
            || client.thePlayer == null
            || client.thePlayer.dimension != data.getInteger("dimension")
            || !ItemNekoHologramProjector.isProjector(client.thePlayer.getHeldItem())) {
            HologramRenderer.sceneClosed();
            return;
        }
        int x = data.getInteger("x"), y = data.getInteger("y"), z = data.getInteger("z");
        TileEntity tile = client.theWorld.blockExists(x, y, z) ? client.theWorld.getTileEntity(x, y, z) : null;
        if (!HologramLocalControllerCapture.supports(tile)) {
            HologramRenderer.sceneClosed();
            return;
        }
        tryDeferAction(() -> {
            if (client.currentScreen != this || ui.session.state() != snapshot
                || client.theWorld == null
                || client.theWorld.getTileEntity(x, y, z) != tile
                || client.thePlayer == null
                || !ItemNekoHologramProjector.isProjector(client.thePlayer.getHeldItem())) return;
            HologramScreen local = localController(tile, client.thePlayer.dimension, data.getInteger("side"));
            locallyReplacingView = true;
            try {
                local.openFrom(this);
            } finally {
                if (client.currentScreen == this) locallyReplacingView = false;
            }
        });
    }

    boolean localConfigurationCurrent(HologramUiModules owner) {
        return owner == ui && localControllerStandard() && localController != null
            && minecraft().currentScreen == this && controllerContextCurrent(false);
    }

    /** Options with no geometry dependency survive the independently completed capture publications. */
    static NBTTagCompound localControllerPreferences(NBTTagCompound settings) {
        NBTTagCompound preferences = new NBTTagCompound();
        for (String key : new String[] { "mode", "scope", "layer", "materialPriority", "materialMain",
            "materialContainers", "materialMe" }) {
            if (settings.hasKey(key)) preferences.setTag(key, settings.getTag(key).copy());
        }
        return preferences;
    }

    void refreshLocalController(NBTTagCompound request) {
        if (!localControllerStandard() || !controllerContextCurrent(false)
            || localController != null && (request == null || !localConfigurationCurrent(ui))) return;
        Minecraft client = minecraft();
        HologramState previous = ui.session.state();
        ItemStack tool = client.thePlayer.getHeldItem();
        NBTTagCompound live = HologramLocalControllerCapture.toolSettings(tool);
        NBTTagCompound settings = request == null ? ui.config.configuration() : (NBTTagCompound) request.copy();
        settings.setBoolean(
            "mainExplicit",
            previous.data.getBoolean("mainExplicit")
                || settings.getInteger("main") != previous.data.getInteger("main"));
        rebaseControllerChannels(
            settings,
            pendingControllerToolSettings == null ? localControllerToolSettings : pendingControllerToolSettings,
            live);
        if (!HologramLocalControllerCapture.validSettings(settings)) {
            ui.session.notice("参数无效，配置未修改");
            return;
        }
        boolean geometryChanged = request == null || previous.data.getBoolean("localStructureUnavailable")
            || settings.getInteger("main") != previous.data.getInteger("main")
            || !settings.getCompoundTag("channels")
                .equals(previous.data.getCompoundTag("channels"))
            || settings.getBoolean("noHatches") != previous.data.getBoolean("noHatches");
        if (!geometryChanged) {
            updateState(previous.withMetadata(localController == null ? settings : localControllerPreferences(settings)));
            if (localController == null) localControllerToolSettings = live;
            else pendingControllerToolSettings = live;
            return;
        }
        HologramState fallback = localController == null ? previous : localControllerPrevious;
        retireLocalController();
        localController = new HologramLocalControllerCapture(
            openingController,
            client.thePlayer.dimension,
            localControllerSide,
            tool,
            settings,
            previous.session);
        pendingControllerToolSettings = live;
        localControllerPrevious = fallback;
        NBTTagCompound reading = localController.opening();
        reading.setString("status", HologramClient.text("controllerLocalPreparing", "正在读取本地标准结构…"));
        updateState(new HologramState(reading));
    }

    /** A fresh held-tool sync updates inherited values and leaves explicit workbench edits intact. */
    static void rebaseControllerChannels(NBTTagCompound settings, NBTTagCompound before, NBTTagCompound now) {
        if (settings.getInteger("main") == before.getInteger("main")) {
            settings.setInteger("main", now.getInteger("main"));
            settings.setBoolean("mainExplicit", now.getBoolean("mainExplicit"));
        }
        NBTTagCompound active = settings.getCompoundTag("channels");
        NBTTagCompound oldChannels = before.getCompoundTag("channels");
        NBTTagCompound newChannels = now.getCompoundTag("channels");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Object key : oldChannels.func_150296_c()) names.add((String) key);
        for (Object key : newChannels.func_150296_c()) names.add((String) key);
        for (String name : names) {
            if (!java.util.Objects.equals(active.getTag(name), oldChannels.getTag(name))) continue;
            if (newChannels.hasKey(name, 3)) active.setInteger(name, newChannels.getInteger(name));
            else active.removeTag(name);
        }
    }

    void advanceLocalController() {
        HologramClientStockNative.OWNER.tick(this, HologramClientStockNative.context(minecraft()), System.nanoTime());
        if (localCompactNative && localExecutionRequest != null
            && nativeCompactReceiptDeadline != 0
            && System.nanoTime() >= nativeCompactReceiptDeadline) {
            java.util.UUID expired = localExecutionRequest;
            boolean sent = cancelPendingLocalExecution(expired);
            localExecutionFailed(expired, sent ? "未收到原生补建确认，已请求取消尚未开工的请求；请先核对现场再重试。"
                : "未收到原生补建确认，请先核对现场再重试。");
        }
        if (!localControllerStandard() || localController == null) return;
        boolean retire = false;
        try {
            Minecraft client = minecraft();
            if (!controllerContextCurrent(false)
                || !localController.matches(client.theWorld, client.thePlayer.dimension))
                throw new java.io.IOException("LOCAL_CONTROLLER_CHANGED");
            long deadline = System.nanoTime() + LOCAL_CONTROLLER_FRAME_NANOS;
            if (localControllerTarget == null) {
                advanceLocalControllerTarget(deadline);
                if (System.nanoTime() < deadline) advanceLocalControllerConfiguration();
                return;
            }
            advanceLocalControllerConfiguration();
            if (System.nanoTime() >= deadline) return;
            HologramState completed = advanceLocalControllerDetails(deadline);
            if (completed == null) return;
            retire = true;
            completed = carryLocalControllerPreferences(completed, ui.session.state());
            localControllerBaseline = completed;
            localNativeChoices.clear();
            HologramState planned = restoreEarlyControllerEdits(completed, ui.session.state());
            publishLocalController(planned, HologramRuntimeMetrics.LoadStage.CLIENT_INSTALL);
            if (pendingControllerToolSettings != null) localControllerToolSettings = pendingControllerToolSettings;
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            retire = true;
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .debug("Local controller standard structure unavailable", failure);
            String status = HologramClient.text("controllerLocalUnavailable", "此控制器没有可用的本地标准结构");
            if (localControllerPrevious != null && !localControllerPrevious.cells.isEmpty()) {
                updateState(localControllerPrevious.withMetadata(localControllerPreferences(ui.session.state().data)));
                ui.session.notice(status);
            } else updateState(new HologramState(
                localController.unavailable(status)).withMetadata(localControllerPreferences(ui.session.state().data)));
        } finally {
            if (retire) retireLocalController();
        }
    }

    private void advanceLocalControllerTarget(long deadline) throws java.io.IOException {
        if (localControllerPreparation == null) {
            HologramControllerTarget.Projection snapshot = localController
                .stepTarget(LOCAL_CONTROLLER_FRAME_ROWS, Math.max(1, deadline - System.nanoTime()));
            if (snapshot == null) return;
            snapshot.metadata()
                .setString("status", HologramClient.text("controllerLocalPreparing", "正在读取本地标准结构…"));
            localControllerPreparation = new HologramState.Preparation(snapshot);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        long preparation = HologramRuntimeMetrics.start();
        try {
            if (!localControllerPreparation.step(LOCAL_CONTROLLER_FRAME_ROWS, remaining)) return;
            localControllerTarget = carryLocalControllerPreferences(
                localControllerPreparation.result(), ui.session.state());
            if (localControllerCapabilities != null)
                localControllerTarget = localControllerTarget.withMetadata(localControllerCapabilityMetadata());
            localControllerRenderedCells = localControllerTarget.cells;
            localControllerPreparation = null;
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_PREPARE, preparation);
        }
        publishLocalController(localControllerTarget, HologramRuntimeMetrics.LoadStage.CLIENT_CONTROLLER_TARGET);
    }

    private NBTTagCompound localControllerCapabilityMetadata() {
        NBTTagCompound changes = new NBTTagCompound();
        changes.setTag("capabilities", localControllerCapabilities);
        return changes;
    }

    private void advanceLocalControllerConfiguration() {
        if (localControllerCapabilities != null || localControllerDetails != null) return;
        localControllerCapabilities = localController.readyCapabilities();
        if (localControllerCapabilities != null)
            updateState(ui.session.state().withMetadata(localControllerCapabilityMetadata()));
    }

    private HologramState advanceLocalControllerDetails(long deadline) throws java.io.IOException {
        HologramState current = ui.session.state();
        if (!sameLocalControllerTarget(current))
            throw new java.io.IOException("LOCAL_CONTROLLER_CHANGED");
        prepareSelectedControllerRow();
        if (localControllerDetails == null) {
            NBTTagCompound details = localController
                .stepDetails(LOCAL_CONTROLLER_FRAME_ROWS, Math.max(1, deadline - System.nanoTime()));
            if (details == null) return null;
            details.setString("status", HologramClient.text("controllerLocalStandard", "本地标准结构"));
            NBTTagCompound preferences = localControllerPreferences(current.data);
            for (Object name : preferences.func_150296_c()) {
                String key = (String) name;
                details.setTag(key, preferences.getTag(key));
            }
            localControllerDetails = new HologramState.ControllerDetails(localControllerTarget, details);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return null;
        long preparation = HologramRuntimeMetrics.start();
        try {
            return localControllerDetails.step(LOCAL_CONTROLLER_FRAME_ROWS, remaining) ? localControllerDetails.result()
                : null;
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_PREPARE, preparation);
        }
    }

    private static HologramState carryLocalControllerPreferences(HologramState target, HologramState current) {
        NBTTagCompound preferences = localControllerPreferences(current.data);
        return preferences.equals(localControllerPreferences(target.data)) ? target : target.withMetadata(preferences);
    }

    private boolean sameLocalControllerTarget(HologramState state) {
        return localControllerTarget != null && state.cells == localControllerRenderedCells
            && state.session.equals(localControllerTarget.session)
            && state.data.getString("generation").equals(localControllerTarget.data.getString("generation"));
    }

    HologramState.Cell interactionCell(HologramUiModules owner, HologramState state, int index) {
        return owner == ui && sameLocalControllerTarget(state) && localControllerInteractionCell != null
            && localControllerInteractionCell.index == index ? localControllerInteractionCell : null;
    }

    boolean earlyControllerRowReady(HologramUiModules owner) {
        HologramState state = ui.session.state();
        HologramState.Cell selected = interactionCell(owner, state, ui.preview.selected());
        return selected != null && !selected.anchor && state.data.getInteger("mode") != 2
            && localConfigurationCurrent(owner);
    }

    private void prepareSelectedControllerRow() throws java.io.IOException {
        int selected = ui.preview.selected();
        if (localControllerInteractionCell != null && localControllerInteractionCell.index == selected) return;
        localControllerInteractionCell = null;
        if (selected < 0 || selected >= localControllerTarget.cells.size()) return;
        EarlyControllerEdit edit = earlyControllerEdits.get(selected);
        if (edit != null) {
            localControllerInteractionCell = new HologramState.Cell(selected, edit.row());
            return;
        }
        NBTTagCompound row = localControllerDetails == null ? localController.selectedDetails(selected)
            : localControllerDetails.interactionRow(selected);
        if (row != null) localControllerInteractionCell = new HologramState.Cell(
            localControllerTarget.cells.get(selected), row);
    }

    private HologramState restoreEarlyControllerEdits(HologramState completed, HologramState current) {
        net.minecraft.nbt.NBTTagList edits = new net.minecraft.nbt.NBTTagList();
        boolean changed = false;
        for (var entry : earlyControllerEdits.entrySet()) {
            int index = entry.getKey();
            EarlyControllerEdit edit = entry.getValue();
            if (index < completed.cells.size() && edit.matches(completed.cells.get(index))) {
                edits.appendTag(edit.row());
                if ("manual".equals(edit.row().getString("pinScope")) || edit.row().getInteger("hatchFacing") >= 0)
                    localNativeChoices.add(index);
            } else changed = true;
        }
        if (changed) ui.session.notice("部分所选格的候选已变化，未保留对应修改。");
        return HologramLocalControllerEdits.applySelectedRows(
            completed, edits, current.data.getLong("planRevision") + 1);
    }

    private void publishLocalController(HologramState state, HologramRuntimeMetrics.LoadStage stage) {
        long install = HologramRuntimeMetrics.start();
        try {
            updateState(state);
            HologramRuntimeMetrics.loadStage(stage, state.data, -1, state.cells.size());
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_INSTALL, install);
        }
    }

    private void retireLocalController() {
        if (localController != null) localController.close();
        localController = null;
        localControllerPreparation = null;
        localControllerDetails = null;
        localControllerTarget = null;
        localControllerRenderedCells = null;
        localControllerInteractionCell = null;
        localControllerCapabilities = null;
        earlyControllerEdits.clear();
        localControllerPrevious = null;
        pendingControllerToolSettings = null;
    }

    static HologramState controllerWaitingState(int dimension, int x, int y, int z) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("editorWorkspace", "projection");
        data.setString("workspaceTab", "structure");
        data.setString("title", HologramClient.text("controllerOpening", "Reading controller"));
        data.setBoolean("target", true);
        data.setBoolean("controllerOpening", true);
        data.setBoolean("snapshotPending", true);
        data.setBoolean("areaSelectionShell", true);
        data.setInteger("dimension", dimension);
        data.setInteger("x", x);
        data.setInteger("y", y);
        data.setInteger("z", z);
        return new HologramState(data);
    }

    long controllerRequest() {
        return controllerRequest;
    }

    boolean controllerOpeningCurrent() {
        return controllerRequest > 0 && HologramClient.controllerOpen.accepts(controllerRequest)
            && controllerContextCurrent(true);
    }

    private boolean controllerContextCurrent(boolean assignedToolAllowed) {
        Minecraft client = minecraft();
        if (openingController == null || client.thePlayer == null
            || client.theWorld == null
            || client.getNetHandler() != openingConnection
            || client.theWorld != openingController.getWorldObj()
            || client.thePlayer.inventory.currentItem != openingSlot
            || !client.theWorld
                .blockExists(openingController.xCoord, openingController.yCoord, openingController.zCoord)
            || client.theWorld
                .getTileEntity(openingController.xCoord, openingController.yCoord, openingController.zCoord)
                != openingController)
            return false;
        ItemStack held = client.thePlayer.getHeldItem();
        if (!ItemNekoHologramProjector.isProjector(held)) return false;
        String heldId = toolId(held);
        // Opening an unlinked projector assigns its ID server-side and can replace the local stack on sync.
        if (openingToolId.isEmpty()) return held == openingTool
            || !nativeCompactAssignedTool.isEmpty() && nativeCompactAssignedTool.equals(heldId)
            || assignedToolAllowed && !heldId.isEmpty();
        return openingToolId.equals(heldId);
    }

    boolean acceptsControllerTool(String serverToolId) {
        String heldId = toolId(minecraft().thePlayer.getHeldItem());
        return heldId.isEmpty() || serverToolId.isEmpty() || heldId.equals(serverToolId);
    }

    private static String toolId(ItemStack stack) {
        return stack == null || !stack.hasTagCompound() ? ""
            : stack.getTagCompound()
                .getString("gtitHologramToolId");
    }

    void controllerOpened(boolean ready) {
        controllerRequest = 0;
        openingController = null;
        openingConnection = null;
        openingTool = null;
        openingToolId = null;
    }

    // Retire the old opener's full-baseline continuation even if a stale caller still requests it.
    void explicitControllerOperation(NBTTagCompound request, HologramState displayed) {
        ui.session.notice("旧原生核对已停用；未传送或修改结构。");
    }

    boolean closeControllerOpening() {
        if (controllerRequest == 0) return false;
        HologramClient.controllerOpen.dismiss(controllerRequest);
        if (minecraft().currentScreen == this) minecraft().displayGuiScreen(null);
        return true;
    }

    private void deferControllerClose(boolean escape) {
        HologramClient.controllerOpen.dismiss(controllerRequest);
        deferRootBack(escape, this::closeControllerOpening);
    }

    private void deferRootBack(boolean escape, Runnable action) {
        if (!escape) {
            deferAction(action);
            return;
        }
        HologramUiModules inputOwner = ui;
        deferEscapeAction(() -> {
            if (ui == inputOwner) action.run();
        });
    }

    private HologramScreen(String topic, boolean replay, Runnable returnToStorage) {
        super("detached_tutorial");
        openedWorld = openedConnection = openedPlayer = null;
        openedDimension = Integer.MIN_VALUE;
        openedPresentationResources = -1;
        if (returnToStorage == null) throw new IllegalArgumentException("A detached tutorial requires a return target");
        detached = true;
        detachedTopic = topic;
        detachedReplay = replay;
        detachedReturn = returnToStorage;
        ui.session.initializeIsolated(HologramEditorTutorial.detachedState(), (operation, request) -> {});
    }

    static HologramScreen detachedTutorial(String topic, boolean replay, Runnable returnToStorage) {
        return new HologramScreen(topic, replay, returnToStorage);
    }

    private void returnFromDetached() {
        if (detachedReturned) return;
        detachedReturned = true;
        detachedReturn.run();
    }

    boolean acceptsLibraryNavigation(HologramState next) {
        return ui.library.acceptsLibraryNavigation(next);
    }

    void updateState(HologramState next) {
        if (!detached && !ui.tutorial.active()
            && HologramEditorWorkspaces.domain(ui.session.state()) != HologramEditorWorkspaces.domain(next)) {
            ui.memory.rememberUi();
            ui.preview.releasePointerCapture();
            ui.tools.closeTransient();
            ui.review.closeLocalMaterials();
            ui.shell.pauseWorkspace();
            ui.workspaces.acknowledged();
            ui = workspaceGraphs.retain(next, () -> {
                HologramUiModules created = new HologramUiModules(this);
                created.session.initialize(next);
                return created;
            });
            ui.workspaces.domainActivated();
            if (workspaceMount != null) mountWorkspaceGraph();
            ui.shell.resumeWorkspace();
        }
        if (!detached) try (FieldGraph ignored = fieldGraph(ui)) {
            ui.session.updateState(next);
            ui.shell.prepareAvailableBlueprintPages();
        }
    }

    @Override
    protected boolean buildNativePanel() {
        if (detached) return true;
        workspaceMount = new com.cleanroommc.modularui.widget.ParentWidget<>().sizeRel(1f);
        mountWorkspaceGraph();
        panel().child(workspaceMount);
        return true;
    }

    private void mountWorkspaceGraph() {
        if (initializedGraphs.add(ui)) {
            try (FieldGraph ignored = fieldGraph(ui)) {
                ui.router.initGui();
                long shell = localControllerStandard() ? HologramRuntimeMetrics.start() : 0;
                try {
                    workspaceMount.child(ui.shell.build(panel()));
                } finally {
                    HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.WORKBENCH_SHELL, shell);
                }
            }
            if (workspaceMount.isValid()) workspaceMount.scheduleResize();
        }
    }

    public void initGui() {
        super.initGui();
        // openFrom builds this graph before displayGuiScreen attaches its main panel to MUI.
        // Secondary tutorial panels therefore start from the first valid native tick, never construction.
        if (detached) return;
        if (websiteConfirmationResumePending) websiteConfirmationResumePending = false;
        ui.canvas.resize();
        if (controllerRequest == 0 && !localWorkspaceView()) HologramBackgroundTasks.restoreUi(ui);
    }

    void resumeAfterWebsiteConfirmation() {
        websiteConfirmationResumePending = true;
    }

    private boolean cancelPendingLocalExecution(java.util.UUID execution) {
        if (!localExecutionSent || minecraft().getNetHandler() != openingConnection
            || openingController == null || minecraft().theWorld != openingController.getWorldObj()) return false;
        NBTTagCompound cancel = new NBTTagCompound();
        cancel.setString("action", "cancelProjectedPlacement");
        cancel.setString("placementRequestId", execution.toString());
        try {
            com.miaokatze.gtmb.hologram.HologramNetwork.sendAction(cancel);
            return true;
        } catch (RuntimeException disconnected) {
            return false; // The server still owns a bounded lease.
        }
    }

    boolean occludesWorldProjection(HologramState snapshot) {
        return snapshot != null && workspaceMount != null && workspaceMount.isValid() && ui.session.state() == snapshot
            && snapshot.data.getBoolean("target") && !snapshot.data.getBoolean("virtual")
            && HologramEditorWorkspaces.domain(snapshot) == HologramEditorWorkspaces.Mode.PROJECTION;
    }

    public boolean doesGuiPauseGame() {
        return ui.router.doesGuiPauseGame();
    }

    protected void onMuiClose() {
        try {
            if (detached) {
                ui.tutorial.closeWithoutProgress();
                endTutorialFields();
            } else
                if (controllerRequest == 0 && !localWorkspaceView() && !locallyReplacingView) ui.router.onGuiClosed();
                else org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        } finally {
            HologramClientStockNative.OWNER.close(this, HologramClientStockNative.context(minecraft()));
            java.util.UUID execution = localExecutionRequest;
            localExecutionRequest = null;
            if (execution != null) cancelPendingLocalExecution(execution);
            localExecutionSent = false;
            retireLocalController();
            localControllerBaseline = null;
            localNativeChoices.clear();
            // This hook runs on final MUI panel disposal, unlike onGuiClosed's
            // temporary website/NEI suspensions. Dormant graph GPU resources end here.
            java.util.Set<HologramUiModules> graphs = java.util.Collections
                .newSetFromMap(new java.util.IdentityHashMap<>());
            graphs.addAll(workspaceGraphs.values());
            graphs.add(ui);
            Minecraft client = minecraft();
            // A retiring old screen must not assign its draft to a new player/world on close.
            boolean sameOwner = openedWorld != null && openedWorld == client.theWorld
                && openedConnection != null
                && openedConnection == client.getNetHandler()
                && openedPlayer != null
                && openedPlayer == client.thePlayer
                && openedDimension == client.thePlayer.dimension;
            for (HologramUiModules graph : graphs) {
                if (!detached && sameOwner && graph.session.localDocument()) HologramClient.rememberLocalDocument(
                    graph.session.document(),
                    graph.session.state(),
                    !graph.library.localBusy() && graph.session.pendingSequence() < 0
                        && !graph.session.isTutorial()
                        && openedPresentationResources == HologramTextures.resourceGeneration());
                graph.library.retireLocalView();
                graph.shell.close();
                graph.canvas.layout()
                    .release();
                releaseFieldGraph(graph);
            }
            workspaceGraphs.clear();
            initializedGraphs.clear();
        }
    }

    @Override
    protected void onMuiActionFailure(RuntimeException failure) {
        ui.session.notice(failure.getMessage() == null ? "界面操作失败，请重试" : failure.getMessage());
    }

    @Override
    public void onGuiClosed() {
        ui.review.closeLocalMaterials();
        ui.tutorial.closeWithoutProgress();
        ui.tools.closeTransient();
        ui.canvas.layout()
            .release();
        ui.preview.releasePointerCapture();
        if (detached) {
            detachedReturned = true;
            endTutorialFields();
            return;
        }
        if (controllerRequest > 0) {
            HologramClient.controllerOpen.dismiss(controllerRequest);
            return;
        }
        if (localWorkspaceView() || locallyReplacingView) return;
        ui.memory.rememberUi();
        HologramBackgroundTasks.rememberUi(ui);
        if (ui.ae.aePreviewTransition()) ui.ae.aePreviewTransition(false);
    }

    static void rememberBlueprintPlacement(HologramState snapshot) {
        HologramUiMemory.rememberBlueprintPlacement(snapshot);
    }

    boolean retainsPlacementReview(HologramState next) {
        return ui.review.retainsPlacementReview(next);
    }

    public void updateScreen() {
        if (controllerRequest > 0) {
            if (!controllerOpeningCurrent()) closeControllerOpening();
            else try (FieldGraph ignored = fieldGraph(ui)) {
                ui.shell.tick();
            }
            return;
        }
        if (detached) {
            if (!detachedStarted) {
                if (detachedHostReady()) tryDeferAction(() -> {
                    if (!detachedHostReady() || detachedStarted) return;
                    detachedStarted = true;
                    ui.tutorial.startDetached(detachedTopic, detachedReplay, this::returnFromDetached);
                });
                return;
            }
            ui.tutorial.tick();
            if (detachedStarted && !ui.tutorial.active() && !detachedReturned) deferAction(this::returnFromDetached);
            return;
        }
        try (FieldGraph ignored = fieldGraph(ui)) {
            ui.router.updateScreen();
            ui.shell.tick();
        }
    }

    private boolean detachedHostReady() {
        return !detachedReturned && minecraft().currentScreen == this
            && panel().isValid()
            && getScreen().getPanelManager()
                .isOpen();
    }

    protected void renderScene(int mouseX, int mouseY, float ticks) {
        if (detached && !ui.tutorial.active()) return;
        ui.canvas.beginFrame(mouseX, mouseY);
        canvasTransform(ui.canvas.originX(), ui.canvas.originY(), ui.canvas.scale());
        try {
            ui.router.drawScreen(mouseX, mouseY, ticks);
        } catch (RuntimeException failure) {
            if (!detached) throw failure;
            ui.tutorial.closeWithoutProgress();
            deferAction(this::returnFromDetached);
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .warn("Detached tutorial closed after a presentation failure", failure);
            return;
        }
        if (detached && !ui.tutorial.active()) return;
        for (HologramUiCanvas.Control control : ui.tutorial.renderUi().canvas.controls()) {
            nativeButton(
                control.id,
                control.x,
                control.y,
                control.w,
                control.h,
                control.label,
                control.enabled,
                control.accent,
                control.selected);
            nativeVoucherRibbon(control.id, control.voucherRibbon);
            nativeSceneTooltip(control.id, control.sceneTooltip);
        }
    }

    long saveQuantumGroups(NBTTagList groups, long sourceRevision) {
        return ui.processing.saveQuantumGroups(groups, sourceRevision);
    }

    void quantumGroupsSaved() {
        ui.processing.quantumGroupsSaved();
    }

    boolean hasPendingAeReviewNavigation() {
        return ui.ae.hasPendingAeReviewNavigation();
    }

    NBTTagCompound smokeAeUiState() {
        return ui.diagnostics.smokeAeUiState();
    }

    net.minecraft.nbt.NBTTagList smokeAeWarningMarkers() {
        return ui.diagnostics.smokeAeWarningMarkers();
    }

    String smokeAeReviewGui() {
        return ui.diagnostics.smokeAeReviewGui();
    }

    NBTTagCompound smokeLibraryUiState() {
        return ui.diagnostics.smokeLibraryUiState();
    }

    public void mouseClicked(int x, int y, int button) {
        if (detached) return;
        ui.router.mouseClicked(x, y, button);
    }

    public void mouseClickMove(int x, int y, int button, long elapsed) {
        if (detached) return;
        ui.router.mouseClickMove(x, y, button, elapsed);
    }

    public void mouseMovedOrUp(int x, int y, int button) {
        if (detached) return;
        ui.router.mouseMovedOrUp(x, y, button);
    }

    public void handleMouseInput() {
        if (detached && !ui.tutorial.active()) return;
        super.handleMouseInput();
        // Native panels and the bounded scene widget own wheel routing.
    }

    public void keyTyped(char c, int key) {
        if (detached || controllerRequest > 0 && muiKeyBefore(c, key)) return;
        ui.router.keyTyped(c, key);
    }

    @Override
    protected void activateNative(int id) {
        if (detached) {
            ui.tutorial.activate(id);
            return;
        }
        if (ui.tutorial.activate(id)) return;
        ui.shortcuts.activity();
        ui.memory.initialEditHint(ui.memory.savedEditHint(false));
        ui.memory.rememberGuidance();
        try (FieldGraph ignored = fieldGraph(ui)) {
            ui.router.activate(id);
        }
    }

    @Override
    protected void sceneScroll() {
        ui.router.handleMouseInput();
    }

    @Override
    protected String nativeTooltip(int id, boolean enabled) {
        if (id == HologramEditorControlIds.GUIDE) return "收纳 / 展开智能指引；此偏好会保留到下次打开。";
        if (id == HologramEditorControlIds.GUIDE + 1) return "完整操作说明与八门独立教程。";
        if (id == HologramEditorControlIds.TUTORIAL + 1) return "回到上一步的动画开头。";
        if (id == HologramEditorControlIds.TUTORIAL + 2) return "继续下一步；当前动画会先完整播放。";
        if (id == HologramEditorControlIds.TUTORIAL + 3) return "跳过教程并恢复原工作区。";
        if (id == HologramEditorControlIds.TUTORIAL + 4) return "清空教学进度，从本课程第一步重新播放。";
        String tool = ui.tools.tooltip(id);
        if (tool != null) return tool;
        String help = ui.canvas.controlHelp(id);
        if (help == null) return "";
        return enabled ? help : help + "\n" + ui.canvas.disabledReason(id);
    }

    @Override
    protected String nativeIcon(int id, String label) {
        return id == HologramEditorControlIds.GUIDE + 1 ? "help-circle" : ui.tools.icon(id);
    }

    @Override
    protected boolean nativeIconOnly(int id) {
        return id == HologramEditorControlIds.GUIDE + 1 || ui.tools.iconOnly(id);
    }

    @Override
    protected boolean muiKeyBefore(char character, int key) {
        boolean escape = key == org.lwjgl.input.Keyboard.KEY_ESCAPE;
        boolean back = escape || key == org.lwjgl.input.Keyboard.KEY_E && !ui.router.textFieldFocused();
        if (controllerRequest > 0) {
            if (back) deferControllerClose(escape);
            return true;
        }
        if (back && ui.tutorial.active()) {
            deferRootBack(escape, ui.tutorial::closeWithoutProgress);
            return true;
        }
        if (ui.tutorial.blockInput()) return true;
        if (detached) return true;
        if (back && ui.textBoxes.editorOpen()) {
            deferRootBack(escape, ui.textBoxes::consumeEditorBack);
            return true;
        }
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE && ui.router.textFieldFocused()) {
            // Keep the current draft. MUI's late Escape closes every panel when a world is loaded.
            getScreen().getContext()
                .removeFocus();
            return true;
        }
        if (back) {
            deferRootBack(escape, ui.router::back);
            return true;
        }
        return false;
    }

    @Override
    protected boolean muiMouseBefore(int button) {
        if (controllerRequest > 0) {
            if (ui.shell.controllerOpeningExitHovered(button)) deferControllerClose(false);
            return true;
        }
        if (ui.tutorial.blockInput()) return button != 0 || !ui.tutorial.navigationContains(
            getScreen().getContext()
                .getAbsMouseX(),
            getScreen().getContext()
                .getAbsMouseY(),
            button);
        return detached && !ui.tutorial.active();
    }

    @Override
    protected boolean muiScrollBefore() {
        return controllerRequest > 0 || ui.tutorial.blockInput() || detached && !ui.tutorial.active();
    }

    @Override
    protected void muiActivity() {
        if (!detached) ui.shortcuts.activity();
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (detached || ui.tutorial.blockInput()) return true;
        if (ui.shell.hasModalPanel()) return false;
        ui.router.keyTyped(character, key);
        return true;
    }

    int[] smokeControlPoint(int id) {
        return ui.diagnostics.smokeControlPoint(id);
    }

    void smokeClickControl(int id) {
        ui.diagnostics.smokeClickControl(id);
    }

    void smokeClickAt(int x, int y, int button) {
        ui.diagnostics.smokeClickAt(x, y, button);
    }

    NBTTagCompound smokeAreaImportUiState() {
        return ui.diagnostics.smokeAreaImportUiState();
    }

    int[] smokeViewportBounds() {
        return ui.diagnostics.smokeViewportBounds();
    }

    int[] smokePanelBounds() {
        return ui.diagnostics.smokePanelBounds();
    }

    boolean smokeKey(int key, int modifiers) {
        return ui.diagnostics.smokeKey(key, modifiers);
    }

    String smokeBinding(int index) {
        return ui.diagnostics.smokeBinding(index);
    }

    NBTTagCompound smokeSnapshot() {
        return ui.diagnostics.smokeSnapshot();
    }

    int[] smokeSelectedIndices() {
        return ui.diagnostics.smokeSelectedIndices();
    }

    int[] smokeCapturePreviewSelection(int logicalX, int logicalY, boolean sameType, boolean additive) {
        return ui.diagnostics.smokeCapturePreviewSelection(logicalX, logicalY, sameType, additive);
    }

    boolean smokeHasControl(int id) {
        return ui.diagnostics.smokeHasControl(id);
    }

    long smokeRenderGeneration() {
        return ui.diagnostics.smokeRenderGeneration();
    }

    boolean smokePending() {
        return ui.diagnostics.smokePending();
    }

    NBTTagCompound smokeCaptureMissingBuildClick() {
        return ui.diagnostics.smokeCaptureMissingBuildClick();
    }

    void smokeRenderScenario(String scenario) {
        ui.diagnostics.smokeRenderScenario(scenario);
    }

    String smokeCaptureLocalSelection() {
        return ui.diagnostics.smokeCaptureLocalSelection();
    }

    String smokeCaptureV004Editor() {
        return ui.diagnostics.smokeCaptureV004Editor();
    }

    String smokeCaptureV004Tabs() {
        return ui.diagnostics.smokeCaptureV004Tabs();
    }

    String smokePlacementReviewContext() {
        return ui.diagnostics.smokePlacementReviewContext();
    }

    String smokeConstructionScopeContext() {
        return ui.diagnostics.smokeConstructionScopeContext();
    }

    String smokeProcessingFlowContext() {
        return ui.diagnostics.smokeProcessingFlowContext();
    }

    static String smokeLibraryNavigationFences() {
        return HologramUiDiagnostics.smokeLibraryNavigationFences();
    }

    String smokeCaptureLibraryWorkflow() {
        return ui.diagnostics.smokeCaptureLibraryWorkflow();
    }

    String smokeRenderGizmoHover(String scenario) {
        return ui.diagnostics.smokeRenderGizmoHover(scenario);
    }

    String smokeLayout() {
        return ui.diagnostics.smokeLayout();
    }

    public net.minecraft.client.Minecraft minecraft() {
        return mc == null ? net.minecraft.client.Minecraft.getMinecraft() : mc;
    }

    public net.minecraft.client.gui.FontRenderer font() {
        return fontRendererObj;
    }

    public int screenWidth() {
        return width;
    }

    public int screenHeight() {
        return height;
    }

    public HologramScreen parent() {
        return this;
    }

    @Override
    public void renderBackground() {
        super.drawDefaultBackground();
    }

    @Override
    public void renderText(net.minecraft.client.gui.FontRenderer font, String text, int x, int y, int color) {
        font.drawString(text, x, y, color);
    }

    @Override
    public void renderCenteredText(net.minecraft.client.gui.FontRenderer font, String text, int x, int y, int color) {
        font.drawString(text, x - font.getStringWidth(text) / 2, y, color);
    }

    @Override
    public void renderTooltip(java.util.List<String> rows, int x, int y, net.minecraft.client.gui.FontRenderer font) {
        super.drawHoveringText(rows, x, y, font);
    }
}
