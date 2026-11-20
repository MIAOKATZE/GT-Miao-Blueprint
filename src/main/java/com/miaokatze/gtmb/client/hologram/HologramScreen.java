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
import com.miaokatze.gtmb.hologram.HologramLocalControllerCapture;

/** Minecraft screen adapter. Domain state and behavior live in composed workflow modules. */
public final class HologramScreen extends HologramMuiCanvasScreen implements HologramUiHost {

    HologramUiModules ui = new HologramUiModules(this);
    private final HologramEditorWorkspaces.Graphs workspaceGraphs = new HologramEditorWorkspaces.Graphs();
    private final java.util.Set<HologramUiModules> initializedGraphs = java.util.Collections
        .newSetFromMap(new java.util.IdentityHashMap<>());
    private com.cleanroommc.modularui.widget.ParentWidget<?> workspaceMount;
    private boolean websiteConfirmationResumePending;
    private boolean detached, detachedStarted, detachedReturned, detachedReplay;
    private String detachedTopic;
    private Runnable detachedReturn;
    private boolean domainInitialized;
    private long controllerRequest;
    private TileEntity openingController;
    private Object openingConnection;
    private ItemStack openingTool;
    private String openingToolId;
    private int openingSlot;
    private HologramLocalControllerCapture localController;
    private HologramState.Preparation localControllerPreparation;
    private int localControllerSide;
    private HologramState localControllerPrevious;
    private HologramState localControllerBaseline;
    private NBTTagCompound localControllerToolSettings, pendingControllerToolSettings;
    private boolean locallyReplacingView;
    private HologramState suspendedLocalBlueprint;
    private volatile java.util.UUID localMaterialRequest;
    private LocalMaterialScope requestedLocalMaterialScope;
    private NBTTagCompound explicitControllerOperation;
    private volatile java.util.UUID localExecutionRequest;
    private boolean localExecutionSent;

    @com.github.bsideup.jabel.Desugar
    private record LocalMaterialScope(HologramState state, int selected, int[] selection) {}

    private static final java.util.concurrent.ThreadPoolExecutor EXECUTION_PREPARATION = new java.util.concurrent.ThreadPoolExecutor(
        1, 1, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1), work -> {
            Thread thread = new Thread(work, "gtmb-local-controller-execution");
            thread.setDaemon(true);
            return thread;
        });

    static {
        EXECUTION_PREPARATION.allowCoreThreadTimeOut(true);
    }

    HologramScreen(HologramState state) {
        super("workstation");
        ui.session.initialize(state);
        workspaceGraphs.retain(state, () -> ui);
    }

    HologramScreen(TileEntity tile, int dimension, long request) {
        this(controllerWaitingState(dimension, tile.xCoord, tile.yCoord, tile.zCoord));
        controllerRequest = request;
        rememberControllerContext(tile);
    }

    static HologramScreen localWorkspace(ItemStack tool, int dimension) {
        return new HologramScreen(localWorkspaceState(
            HologramLocalControllerCapture.toolSettings(tool), dimension, "blueprint"));
    }

    private static HologramState localWorkspaceState(NBTTagCompound settings, int dimension, String workspace) {
        NBTTagCompound data = new NBTTagCompound();
        for (String key : new String[] { "main", "mainExplicit", "channels", "mode", "scope", "layer", "noHatches",
            "materialMain", "materialContainers", "materialMe", "materialPriority" })
            if (settings.hasKey(key)) data.setTag(key, settings.getTag(key).copy());
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
        ItemStack tool = Minecraft.getMinecraft().thePlayer.getHeldItem();
        NBTTagCompound settings = HologramLocalControllerCapture.toolSettings(tool);
        HologramLocalControllerCapture capture = new HologramLocalControllerCapture(
            tile, dimension, side, tool, settings, "");
        NBTTagCompound opening = capture.opening();
        opening.setString("status", HologramClient.text("controllerLocalPreparing", "正在读取本地标准结构…"));
        HologramScreen screen = new HologramScreen(new HologramState(opening));
        screen.localController = capture;
        screen.localControllerSide = side;
        screen.localControllerToolSettings = settings;
        screen.rememberControllerContext(tile);
        return screen;
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

    boolean localWorkspaceView() {
        return localControllerStandard() || ui.session.localDocument()
            || ui.session.state().data.getBoolean("localWorkspace");
    }

    void switchLocalWorkspace(NBTTagCompound request) {
        HologramState current = ui.session.state();
        String next = request.getString("editorWorkspace");
        if ("blueprint".equals(next) && localControllerStandard()) {
            NBTTagCompound proposal = current.data.getCompoundTag("discardTargetAction");
            if (proposal.getString("sourceSwitchNonce").isEmpty()
                || !proposal.getString("sourceSwitchNonce").equals(request.getString("sourceSwitchNonce"))) {
                NBTTagCompound confirm = new NBTTagCompound();
                confirm.setString("op", "switchEditorWorkspace");
                confirm.setString("editorWorkspace", "blueprint");
                confirm.setString("session", current.session);
                confirm.setLong("planRevision", current.data.getLong("planRevision"));
                confirm.setString("sourceSwitchNonce", java.util.UUID.randomUUID().toString());
                NBTTagCompound changes = new NBTTagCompound();
                changes.setBoolean("discardConfirmationRequired", true);
                changes.setString("discardTargetOp", "switchEditorWorkspace");
                changes.setTag("discardTargetAction", confirm);
                updateState(current.withMetadata(changes));
                return;
            }
            if (localController != null) localController.close();
            localController = null;
            localControllerPreparation = null;
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
        updateState(ui.session.state().withMetadata(changes));
    }

    void clearLocalWorkspace(NBTTagCompound request) {
        HologramState current = ui.session.state();
        String nonce = current.data.getCompoundTag("discardTargetAction").getString("sourceSwitchNonce");
        if ((localControllerStandard() || ui.session.localDocument() && ui.session.document().dirty())
            && (request == null || nonce.isEmpty() || !nonce.equals(request.getString("sourceSwitchNonce")))) {
            NBTTagCompound confirm = new NBTTagCompound();
            confirm.setString("op", "clearWorkspace");
            confirm.setString("session", current.session);
            confirm.setLong("planRevision", current.data.getLong("planRevision"));
            confirm.setString("sourceSwitchNonce", java.util.UUID.randomUUID().toString());
            NBTTagCompound changes = new NBTTagCompound();
            changes.setBoolean("discardConfirmationRequired", true);
            changes.setString("discardTargetOp", "clearWorkspace");
            changes.setTag("discardTargetAction", confirm);
            updateState(current.withMetadata(changes));
            return;
        }
        if (localController != null) localController.close();
        localController = null;
        localControllerPreparation = null;
        suspendedLocalBlueprint = null;
        HologramClient.rememberLocalDocument(null);
        updateState(localWorkspaceState(current.data, current.data.getInteger("dimension"), current.data.getString("editorWorkspace")));
    }

    boolean startEditableBlueprint(String operation, NBTTagCompound request) {
        if (!localWorkspaceView() || ui.session.localDocument()) return false;
        try {
            HologramState current = ui.session.state();
            if (localControllerStandard() && HologramLocalControllerEdits.handles(operation)) {
                if ("customPin".equals(operation)) {
                    int slot = request.getInteger("inventorySlot");
                    ItemStack[] inventory = minecraft().thePlayer.inventory.mainInventory;
                    ItemStack expected = ItemStack.loadItemStackFromNBT(request.getCompoundTag("expectedSample"));
                    ItemStack actual = slot >= 0 && slot < inventory.length ? inventory[slot] : null;
                    if (actual == null || expected == null || !actual.isItemEqual(expected)
                        || !ItemStack.areItemStackTagsEqual(actual, expected)) throw new java.io.IOException("库存样本已变化，请重新选择");
                }
                updateState(HologramLocalControllerEdits.apply(current, localControllerBaseline, operation, request));
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

    void checkLocalMaterials() {
        HologramState displayed = ui.session.state();
        if (!localWorkspaceView() || displayed.cells.isEmpty() || localMaterialRequest != null) return;
        Minecraft client = minecraft();
        var connection = client.getNetHandler();
        var world = client.theWorld;
        if (connection == null || world == null) return;
        java.util.UUID id = java.util.UUID.randomUUID();
        LocalMaterialScope scope = new LocalMaterialScope(displayed, ui.preview.selected(), ui.preview.selection().indices());
        NBTTagCompound demand = (NBTTagCompound) displayed.data.copy();
        demand.setInteger("selected", scope.selected());
        demand.setIntArray("selectedIndices", scope.selection());
        localMaterialRequest = id;
        requestedLocalMaterialScope = scope;
        ui.session.notice("正在准备材料清单…");
        try {
            EXECUTION_PREPARATION.execute(() -> {
                try {
                    net.minecraft.nbt.NBTTagList cells = new net.minecraft.nbt.NBTTagList();
                    for (HologramState.Cell cell : displayed.cells) {
                        if (!id.equals(localMaterialRequest)) return;
                        NBTTagCompound row = new NBTTagCompound();
                        row.setBoolean("inScope", cell.inScope);
                        row.setBoolean("excluded", cell.excluded);
                        row.setBoolean("anchor", cell.anchor);
                        row.setInteger("dy", cell.dy);
                        row.setString("family", cell.family);
                        if (cell.targetStack != null)
                            row.setTag("targetStack", cell.targetStack.writeToNBT(new NBTTagCompound()));
                        cells.appendTag(row);
                    }
                    demand.setTag("cells", cells);
                    NBTTagCompound request = com.miaokatze.gtmb.hologram.HologramPlacementRequest
                        .encodeMaterialCheck(id, demand);
                    client.func_152344_a(() -> {
                        if (!id.equals(localMaterialRequest)) return;
                        if (client.currentScreen != this || client.theWorld != world || client.getNetHandler() != connection) {
                            finishLocalMaterialRequest(id, "");
                            return;
                        }
                        if (!sameMaterialScope(scope)) {
                            finishLocalMaterialRequest(id, "材料范围已变化，请重新核对");
                            return;
                        }
                        try {
                            com.miaokatze.gtmb.hologram.HologramNetwork.sendAction(request);
                            ui.session.notice("正在核对材料库存…");
                        } catch (RuntimeException failure) {
                            finishLocalMaterialRequest(id, "材料请求失败");
                        }
                    });
                } catch (java.io.IOException | RuntimeException failure) {
                    String message = failure.getMessage() == null ? "材料请求失败" : failure.getMessage();
                    client.func_152344_a(() -> finishLocalMaterialRequest(id, message));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            finishLocalMaterialRequest(id, "正在准备其他操作，请稍后重试");
        }
    }

    private void finishLocalMaterialRequest(java.util.UUID id, String message) {
        if (!id.equals(localMaterialRequest)) return;
        localMaterialRequest = null;
        requestedLocalMaterialScope = null;
        if (!message.isEmpty()) ui.session.notice(message);
    }

    private boolean sameMaterialScope(LocalMaterialScope expected) {
        if (expected == null) return false;
        HologramState original = expected.state(), current = ui.session.state();
        if (!original.session.equals(current.session) || original.cells != current.cells
            || original.data.getLong("planRevision") != current.data.getLong("planRevision")
            || expected.selected() != ui.preview.selected()
            || !java.util.Arrays.equals(expected.selection(), ui.preview.selection().indices())) return false;
        for (String key : new String[] { "scope", "layer", "materialMain", "materialContainers", "materialMe",
            "materialPriority", "activeStructure" }) {
            if (!java.util.Objects.equals(original.data.getTag(key), current.data.getTag(key))) return false;
        }
        return true;
    }

    void reviewLocalConstruction(NBTTagCompound settings) {
        if (!localControllerStandard() || !controllerContextCurrent(false) || localExecutionRequest != null) return;
        Minecraft client = minecraft();
        HologramState displayed = ui.session.state();
        if (displayed.cells.isEmpty()) return;
        if (displayed.data.getInteger("mode") != 0) {
            settings.setInteger("mode", displayed.data.getInteger("mode"));
            HologramClient.prepareExplicitControllerOperation(openingController, localControllerSide, settings, displayed);
            return;
        }
        java.util.UUID id = java.util.UUID.randomUUID();
        localExecutionRequest = id;
        int[] anchor = { displayed.data.getInteger("x"), displayed.data.getInteger("y"), displayed.data.getInteger("z") };
        int height = client.theWorld.getHeight();
        NBTTagCompound intent = (NBTTagCompound) displayed.data.copy();
        for (String key : new String[] { "scope", "layer", "selected", "selectedIndices" })
            if (settings.hasKey(key)) intent.setTag(key, settings.getTag(key).copy());
        ui.session.notice("正在准备施工清单…");
        try {
            EXECUTION_PREPARATION.execute(() -> {
                try {
                    net.minecraft.nbt.NBTTagList cells = new net.minecraft.nbt.NBTTagList();
                    for (HologramState.Cell cell : displayed.cells) {
                        if (!id.equals(localExecutionRequest)) return;
                        cells.appendTag(cell.tag());
                    }
                    intent.setTag("cells", cells);
                    var plan = com.miaokatze.gtmb.hologram.HologramProjectedPlacement.compileDisplayed(
                        intent, anchor, height, () -> id.equals(localExecutionRequest));
                    NBTTagCompound request = com.miaokatze.gtmb.hologram.HologramPlacementRequest.encode(
                        id, anchor, plan, intent);
                    var packet = com.miaokatze.gtmb.hologram.HologramNetwork.prepareProjectedPlacement(request);
                    client.func_152344_a(() -> {
                        if (!id.equals(localExecutionRequest)) return;
                        if (client.currentScreen != this || !controllerContextCurrent(false)
                            || displayed.cells != ui.session.state().cells) {
                            localExecutionRequest = null;
                            return;
                        }
                        try {
                            com.miaokatze.gtmb.hologram.HologramNetwork.sendPreparedPlacement(packet);
                            localExecutionSent = true;
                            ui.session.notice("正在核对施工现场和材料…");
                        } catch (RuntimeException failure) {
                            localExecutionFailed(id, failure.getMessage());
                        }
                    });
                } catch (java.io.IOException | RuntimeException failure) {
                    client.func_152344_a(() -> localExecutionFailed(id, failure.getMessage()));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            localExecutionFailed(id, "施工清单正在准备，请稍后重试");
        }
    }

    private void localExecutionFailed(java.util.UUID id, String reason) {
        if (!id.equals(localExecutionRequest)) return;
        localExecutionRequest = null;
        localExecutionSent = false;
        ui.session.notice(reason == null || reason.isEmpty() ? "施工清单准备失败" : reason);
    }

    boolean acceptsLocalExecution(HologramState snapshot) {
        java.util.UUID id = localExecutionRequest;
        if (id == null || !localExecutionSent || !snapshot.data.getBoolean("virtualLocked")
            || !id.toString().equals(snapshot.data.getString("executionRequestId"))) return false;
        if (!snapshot.completeBaseline()) return true;
        localExecutionRequest = null;
        localExecutionSent = false;
        locallyReplacingView = true;
        try {
            updateState(snapshot);
        } finally {
            locallyReplacingView = false;
        }
        return true;
    }

    boolean receiveLocalMaterialCheck(NBTTagCompound receipt) {
        java.util.UUID execution = localExecutionRequest;
        if (execution != null && execution.toString().equals(receipt.getString("placementRequestId"))) {
            if (!receipt.getBoolean("placementRequestAccepted"))
                localExecutionFailed(execution, receipt.getString("errorMessage"));
            return true;
        }
        if (localMaterialRequest == null || !localMaterialRequest.toString().equals(receipt.getString("placementRequestId")))
            return false;
        localMaterialRequest = null;
        LocalMaterialScope expected = requestedLocalMaterialScope;
        requestedLocalMaterialScope = null;
        HologramState current = ui.session.state();
        if (!sameMaterialScope(expected)) return true;
        NBTTagCompound changes = new NBTTagCompound();
        if (receipt.getBoolean("placementRequestAccepted")) {
            changes.setTag("materialQuote", receipt.getCompoundTag("materialCheckQuote").copy());
            changes.setString("status", "材料库存核对完成");
        } else changes.setString("status", receipt.getString("errorMessage"));
        updateState(current.withMetadata(changes));
        ui.session.notice(changes.getString("status"));
        return true;
    }

    void rescanDisplayedStructure() {
        if (localControllerStandard()) {
            refreshLocalController(null);
            return;
        }
        Minecraft client = minecraft();
        HologramState snapshot = ui.session.state();
        NBTTagCompound data = snapshot.data;
        if (ui.session.localDocument() || !data.getBoolean("target") || data.getBoolean("virtual") || client.theWorld == null
            || client.thePlayer == null || client.thePlayer.dimension != data.getInteger("dimension")
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
            if (client.currentScreen != this || ui.session.state() != snapshot || client.theWorld == null
                || client.theWorld.getTileEntity(x, y, z) != tile
                || client.thePlayer == null || !ItemNekoHologramProjector.isProjector(client.thePlayer.getHeldItem()))
                return;
            HologramScreen local = localController(tile, client.thePlayer.dimension, data.getInteger("side"));
            locallyReplacingView = true;
            try {
                local.openFrom(this);
            } finally {
                if (client.currentScreen == this) locallyReplacingView = false;
            }
        });
    }

    void refreshLocalController(NBTTagCompound request) {
        if (!localControllerStandard() || localController != null || !controllerContextCurrent(false)) return;
        Minecraft client = minecraft();
        HologramState previous = ui.session.state();
        ItemStack tool = client.thePlayer.getHeldItem();
        NBTTagCompound live = HologramLocalControllerCapture.toolSettings(tool);
        NBTTagCompound settings = request == null ? ui.config.configuration() : (NBTTagCompound) request.copy();
        settings.setBoolean("mainExplicit", previous.data.getBoolean("mainExplicit")
            || settings.getInteger("main") != previous.data.getInteger("main"));
        rebaseControllerChannels(settings, localControllerToolSettings, live);
        if (!HologramLocalControllerCapture.validSettings(settings)) {
            ui.session.notice("参数无效，配置未修改");
            return;
        }
        boolean geometryChanged = request == null || previous.data.getBoolean("localStructureUnavailable")
            || settings.getInteger("main") != previous.data.getInteger("main")
            || !settings.getCompoundTag("channels").equals(previous.data.getCompoundTag("channels"))
            || settings.getBoolean("noHatches") != previous.data.getBoolean("noHatches");
        if (!geometryChanged) {
            updateState(previous.withMetadata(settings));
            localControllerToolSettings = live;
            return;
        }
        localController = new HologramLocalControllerCapture(
            openingController, client.thePlayer.dimension, localControllerSide, tool, settings, previous.session);
        pendingControllerToolSettings = live;
        localControllerPrevious = previous;
        NBTTagCompound reading = new NBTTagCompound();
        reading.setBoolean("capturePending", true);
        reading.setString("status", HologramClient.text("controllerLocalPreparing", "正在读取本地标准结构…"));
        updateState(previous.withMetadata(reading));
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

    private void advanceLocalController() {
        if (!localControllerStandard() || localController == null) return;
        try {
            Minecraft client = minecraft();
            if (!controllerContextCurrent(false)
                || !localController.matches(client.theWorld, client.thePlayer.dimension))
                throw new java.io.IOException("LOCAL_CONTROLLER_CHANGED");
            if (localControllerPreparation == null) {
                NBTTagCompound snapshot = localController.step(2048, 3_000_000L);
                if (snapshot == null) return;
                snapshot.setString("status", HologramClient.text("controllerLocalStandard", "本地标准结构"));
                localControllerPreparation = new HologramState.Preparation(snapshot);
                return;
            }
            if (!localControllerPreparation.step(2048, 3_000_000L)) return;
            localControllerBaseline = localControllerPreparation.result();
            updateState(localControllerBaseline);
            if (pendingControllerToolSettings != null) localControllerToolSettings = pendingControllerToolSettings;
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .debug("Local controller standard structure unavailable", failure);
            String status = HologramClient.text("controllerLocalUnavailable", "此控制器没有可用的本地标准结构");
            if (localControllerPrevious != null && !localControllerPrevious.cells.isEmpty()) {
                updateState(localControllerPrevious);
                ui.session.notice(status);
            } else updateState(new HologramState(localController.unavailable(status)));
        }
        localController = null;
        localControllerPreparation = null;
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
            || client.theWorld
                .getTileEntity(openingController.xCoord, openingController.yCoord, openingController.zCoord)
                != openingController)
            return false;
        ItemStack held = client.thePlayer.getHeldItem();
        if (!ItemNekoHologramProjector.isProjector(held)) return false;
        String heldId = toolId(held);
        // Opening an unlinked projector assigns its ID server-side and can replace the local stack on sync.
        if (openingToolId.isEmpty()) return held == openingTool || assignedToolAllowed && !heldId.isEmpty();
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

    void controllerOpened() {
        controllerRequest = 0;
        NBTTagCompound operation = explicitControllerOperation;
        explicitControllerOperation = null;
        if (operation != null) tryDeferAction(() -> reviewExplicitControllerOperation(operation));
        openingController = null;
        openingConnection = null;
        openingTool = null;
        openingToolId = null;
    }

    void explicitControllerOperation(NBTTagCompound request, HologramState displayed) {
        explicitControllerOperation = (NBTTagCompound) request.copy();
        net.minecraft.nbt.NBTTagList selection = new net.minecraft.nbt.NBTTagList();
        for (int index : request.getIntArray("selectedIndices")) {
            if (index < 0 || index >= displayed.cells.size()) continue;
            HologramState.Cell cell = displayed.cells.get(index);
            NBTTagCompound at = new NBTTagCompound();
            at.setIntArray("xyz", new int[] { cell.x, cell.y, cell.z });
            selection.appendTag(at);
        }
        explicitControllerOperation.setTag("explicitPositions", selection);
        int primary = request.getInteger("selected");
        if (primary >= 0 && primary < displayed.cells.size()) {
            HologramState.Cell cell = displayed.cells.get(primary);
            explicitControllerOperation.setIntArray("explicitPrimary", new int[] { cell.x, cell.y, cell.z });
        }
    }

    private void reviewExplicitControllerOperation(NBTTagCompound request) {
        if (minecraft().currentScreen != this) return;
        java.util.Set<java.util.List<Integer>> wanted = new java.util.HashSet<>();
        var positions = request.getTagList("explicitPositions", 10);
        for (int index = 0; index < positions.tagCount(); index++) {
            int[] at = positions.getCompoundTagAt(index).getIntArray("xyz");
            wanted.add(java.util.Arrays.asList(at[0], at[1], at[2]));
        }
        int[] primary = request.getIntArray("explicitPrimary");
        java.util.stream.IntStream.Builder selection = java.util.stream.IntStream.builder();
        int selected = -1;
        for (HologramState.Cell cell : ui.session.state().cells) {
            if (wanted.contains(java.util.Arrays.asList(cell.x, cell.y, cell.z))) selection.add(cell.index);
            if (primary.length == 3 && primary[0] == cell.x && primary[1] == cell.y && primary[2] == cell.z)
                selected = cell.index;
        }
        request.removeTag("explicitPositions");
        request.removeTag("explicitPrimary");
        request.setInteger("selected", selected);
        request.setIntArray("selectedIndices", selection.build().toArray());
        request.setBoolean("operationPreflight", true);
        ui.session.awaitingScan(true);
        long previous = ui.session.sequence();
        ui.session.send("scan", request, true);
        if (ui.session.sequence() > previous) ui.review.expectOperationReview(ui.session.sequence());
    }

    boolean closeControllerOpening() {
        if (controllerRequest == 0) return false;
        HologramClient.controllerOpen.dismiss(controllerRequest);
        if (minecraft().currentScreen == this) minecraft().displayGuiScreen(null);
        return true;
    }

    private void deferControllerClose() {
        HologramClient.controllerOpen.dismiss(controllerRequest);
        deferAction(this::closeControllerOpening);
    }

    private HologramScreen(String topic, boolean replay, Runnable returnToStorage) {
        super("detached_tutorial");
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
        }
    }

    @Override
    protected boolean buildNativePanel() {
        if (detached) return true;
        if (!domainInitialized) {
            try (FieldGraph ignored = fieldGraph(ui)) {
                ui.router.initGui();
            }
            domainInitialized = true;
        }
        initializedGraphs.add(ui);
        workspaceMount = new com.cleanroommc.modularui.widget.ParentWidget<>().sizeRel(1f);
        workspaceMount.child(ui.shell.build(panel()));
        panel().child(workspaceMount);
        return true;
    }

    private void mountWorkspaceGraph() {
        if (initializedGraphs.add(ui)) {
            try (FieldGraph ignored = fieldGraph(ui)) {
                ui.router.initGui();
                workspaceMount.child(ui.shell.build(panel()));
            }
            workspaceMount.scheduleResize();
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

    public boolean doesGuiPauseGame() {
        return ui.router.doesGuiPauseGame();
    }

    protected void onMuiClose() {
        try {
            if (detached) {
                ui.tutorial.closeWithoutProgress();
                endTutorialFields();
            } else if (controllerRequest == 0 && !localWorkspaceView()
                && !locallyReplacingView)
                ui.router.onGuiClosed();
            else org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        } finally {
            localMaterialRequest = null;
            requestedLocalMaterialScope = null;
            java.util.UUID execution = localExecutionRequest;
            localExecutionRequest = null;
            if (localExecutionSent && execution != null && minecraft().getNetHandler() != null) {
                NBTTagCompound cancel = new NBTTagCompound();
                cancel.setString("action", "cancelProjectedPlacement");
                cancel.setString("placementRequestId", execution.toString());
                com.miaokatze.gtmb.hologram.HologramNetwork.sendAction(cancel);
            }
            localExecutionSent = false;
            if (localController != null) localController.close();
            localController = null;
            localControllerPreparation = null;
            // This hook runs on final MUI panel disposal, unlike onGuiClosed's
            // temporary website/NEI suspensions. Dormant graph GPU resources end here.
            java.util.Set<HologramUiModules> graphs = java.util.Collections
                .newSetFromMap(new java.util.IdentityHashMap<>());
            graphs.addAll(workspaceGraphs.values());
            graphs.add(ui);
            for (HologramUiModules graph : graphs) {
                if (!detached && graph.session.localDocument())
                    HologramClient.rememberLocalDocument(graph.session.document());
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
        if (minecraft().currentScreen == this) advanceLocalController();
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
        boolean back = key == org.lwjgl.input.Keyboard.KEY_ESCAPE
            || key == org.lwjgl.input.Keyboard.KEY_E && !ui.router.textFieldFocused();
        if (controllerRequest > 0) {
            if (back) deferControllerClose();
            return true;
        }
        if (back && ui.tutorial.active()) {
            deferAction(ui.tutorial::closeWithoutProgress);
            return true;
        }
        if (ui.tutorial.blockInput()) return true;
        if (detached) return true;
        if (back && ui.textBoxes.editorOpen()) {
            deferAction(ui.textBoxes::consumeEditorBack);
            return true;
        }
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE && ui.router.textFieldFocused()) {
            // Keep the current draft. MUI's late Escape closes every panel when a world is loaded.
            getScreen().getContext()
                .removeFocus();
            return true;
        }
        if (back) {
            deferAction(ui.router::back);
            return true;
        }
        return false;
    }

    @Override
    protected boolean muiMouseBefore(int button) {
        if (controllerRequest > 0) {
            if (ui.shell.controllerOpeningExitHovered(button)) deferControllerClose();
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
