// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;
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
    private ItemStack openingTool, openingToolSnapshot;
    private String openingToolId;
    private int openingSlot;
    private HologramLocalControllerCapture localController;
    private HologramState.Preparation localControllerPreparation;
    private HologramState localControllerTarget;
    private int localControllerSide;
    private HologramState localControllerPrevious;
    private HologramState localControllerBaseline;
    private HologramState.Cell localControllerInteractionCell;
    private net.minecraft.nbt.NBTTagList localControllerCapabilities;
    private NBTTagCompound localControllerToolSettings, pendingControllerToolSettings;
    private NBTTagCompound nativeTargetSettings;
    private HologramNativeControllerState nativeControllerState;
    private com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation nativeObservationRequest;
    private boolean nativeObservationNeeded = true;
    private NBTTagCompound pendingNativeTargetSettings;
    private HologramState suspendedLocalBlueprint;

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
        data.setString("title", projectorOr("native.ui.holographic_constructor", "全息构造器"));
        data.setString(
            "status",
            projectorOr("native.ui.right_click_a_real_controller_or_choose", "右击真实控制器导入结构，或在蓝图库选择蓝图后点击放置"));
        return new HologramState(data);
    }

    static HologramScreen localController(TileEntity tile, int dimension, int side) {
        long started = HologramRuntimeMetrics.start();
        try {
            HologramLocalControllerCapture capture;
            NBTTagCompound settings, inheritedSettings;
            HologramState state;
            try {
                Minecraft client = Minecraft.getMinecraft();
                ItemStack tool = client.thePlayer.getHeldItem();
                inheritedSettings = HologramLocalControllerCapture.toolSettings(tool);
                settings = (NBTTagCompound) inheritedSettings.copy();
                capture = new HologramLocalControllerCapture(tile, dimension, side, tool, settings, "");
                NBTTagCompound opening = capture.opening();
                opening.setString(
                    "status",
                    HologramClient.text(
                        "controllerLocalPreparing",
                        projectorOr("native.ui.reading_the_local_native_structure", "正在读取本地标准结构…")));
                state = new HologramState(opening);
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.WORKBENCH_CONTROLLER_CAPTURE, started);
            }
            HologramScreen screen = new HologramScreen(state, HologramRuntimeMetrics.start());
            screen.localController = capture;
            screen.localControllerSide = side;
            screen.localControllerToolSettings = inheritedSettings;
            screen.nativeTargetSettings = (NBTTagCompound) settings.copy();
            screen.nativeControllerState = new HologramNativeControllerState();
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
        openingToolSnapshot = openingToolId.isEmpty() && openingTool != null ? openingTool.copy() : null;
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
            && controllerContextCurrent();
    }

    HologramLocalMachineState localMachineState() {
        if (!localControllerStandard() || !controllerContextCurrent()) return HologramLocalMachineState.UNKNOWN;
        return HologramLocalMachineState.read(openingController);
    }

    HologramNativeControllerState nativeControllerState() {
        if (nativeControllerState == null) nativeControllerState = new HologramNativeControllerState();
        return nativeControllerState;
    }

    String nativeStatusCaption() {
        if (stockNativePending()) {
            var context = HologramClientStockNative.context(minecraft());
            if (context != null) {
                String progress = HologramClientStockNative.OWNER.worldStatus(context, System.nanoTime());
                if (!progress.isEmpty()) return progress;
            }
        }
        return nativeControllerState().statusCaption();
    }

    String nativeActionBlocker(com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation action) {
        if (!localConfigurationCurrent(ui))
            return projectorOr("native.ui.the_controller_or_held_projector_changed_reopen", "控制器或手持投影仪已变化，请重新打开结构。");
        if (stockNativePending())
            return projectorOr("native.ui.waiting_for_the_current_construction_request_to", "正在等待当前施工请求结束。");
        return nativeTargetBlocker(nativeControllerState(), ui.session.state().data, action);
    }

    static String nativeTargetBlocker(HologramNativeControllerState state, NBTTagCompound target,
        com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation action) {
        if (action != com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.DEMOLISH
            && target.hasKey("nativeExecutionSupported")
            && !target.getBoolean("nativeExecutionSupported"))
            return projectorOr(
                "native.ui.this_controller_has_no_synchronously_executable_native",
                "此控制器尚未提供可同步执行的原生目标，当前仅供预览。");
        return state.blocker(action);
    }

    boolean selectNativeAction(int action) {
        var values = com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.values();
        if (action < 0 || action >= values.length || !nativeActionBlocker(values[action]).isEmpty()) return false;
        if (!nativeControllerState().select(values[action])) return false;
        updateState(
            ui.session.state()
                .withMetadata(nativeControllerState().metadata()));
        ui.review.closeLocalMaterials();
        return true;
    }

    private com.miaokatze.gtmb.hologram.HologramStockNativeWire.Projection nativeProjection(HologramState target,
        NBTTagCompound settings, int destination, boolean observationOnly) {
        var channels = new java.util.LinkedHashMap<String, Integer>();
        var values = settings.getCompoundTag("channels");
        for (Object name : values.func_150296_c()) {
            if (!values.hasKey((String) name, 3)) return null;
            channels.put((String) name, values.getInteger((String) name));
        }
        var action = nativeControllerState().action();
        boolean configureTarget = !observationOnly
            && action != com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.DEMOLISH;
        var hatches = new java.util.ArrayList<com.miaokatze.gtmb.hologram.HologramStockNativeWire.HatchChoice>();
        var configuration = new NBTTagCompound();
        if (configureTarget) {
            for (String key : new String[] { "presetActive", "hatchTier", "hatchDowngrade", "hatchProfile" })
                if (settings.hasKey(key)) configuration.setTag(
                    key,
                    settings.getTag(key)
                        .copy());
            var explicit = settings.getTagList("nativeHatchChoices", 10);
            if (explicit.tagCount() > 128) return null;
            for (int i = 0; i < explicit.tagCount(); i++) {
                var row = explicit.getCompoundTagAt(i);
                hatches.add(
                    new com.miaokatze.gtmb.hologram.HologramStockNativeWire.HatchChoice(
                        row.getInteger("x"),
                        row.getInteger("y"),
                        row.getInteger("z"),
                        row.getInteger("id"),
                        row.getInteger("facing")));
            }
        }
        var site = stockNativeSite();
        return site == null ? null
            : new com.miaokatze.gtmb.hologram.HologramStockNativeWire.Projection(
                site.controllerId(),
                action,
                channels,
                hatches,
                destination,
                nativeControllerState().targetRevision()).withConfiguration(configuration)
                    .withNativeTargetDigest(configureTarget ? target.data.getString("nativeTargetDigest") : "");
    }

    private void requestNativeObservation() {
        if (!localConfigurationCurrent(ui) || stockNativePending()) return;
        if (!nativeObservationNeeded || nativeObservationRequest != null) return;
        var state = ui.session.state();
        var settings = ui.config.configuration();
        var projection = nativeProjection(state, settings, 0, true);
        var client = minecraft();
        var held = client.thePlayer.getHeldItem();
        var tool = HologramClientStockNative.canonicalTool(toolId(held));
        var site = stockNativeSite();
        if (projection == null || site == null || tool == null) return;
        var key = new com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key(
            java.util.UUID.randomUUID(),
            com.miaokatze.gtmb.hologram.HologramNetwork.nextConstructionIntentSequence());
        var request = com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation.projection(
            key,
            client.thePlayer.dimension,
            tool,
            openingController.xCoord,
            openingController.yCoord,
            openingController.zCoord,
            localControllerSide,
            openingSlot,
            site.facing(),
            settings.getInteger("main"),
            state.data.getBoolean("mainExplicit"),
            false,
            Math.max(0, nativeSourceKey(settings, true)),
            projection);
        nativeObservationNeeded = false;
        nativeObservationRequest = request;
        if (!com.miaokatze.gtmb.hologram.HologramNetwork.sendStockNativeProbe(request)) nativeObservationRequest = null;
    }

    boolean receiveNativeObservation(com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply reply, Object from) {
        var pending = nativeObservationRequest;
        if (pending == null || !pending.key()
            .request()
            .equals(
                reply.key()
                    .request())
            || pending.key()
                .sequence()
                != reply.key()
                    .sequence())
            return false;
        if (from != openingConnection || !controllerContextCurrent()) return true;
        var observation = reply.observation();
        if (observation == null) {
            nativeObservationRequest = null;
            nativeControllerState().observationFailed(reply.reason());
            var changes = nativeControllerState().metadata();
            changes.removeTag("mode");
            updateState(
                ui.session.state()
                    .withMetadata(changes));
            return true;
        }
        if (observation.controller() != pending.projection()
            .controller() || observation.x() != openingController.xCoord
            || observation.y() != openingController.yCoord
            || observation.z() != openingController.zCoord) return true;
        nativeObservationRequest = null;
        if (nativeObservationNeeded) return true;
        if (nativeControllerState().observe(observation)) {
            var changes = nativeControllerState().metadata();
            // An automatic action default is business state, not a request to rebuild the preview mode.
            changes.removeTag("mode");
            if (!stockNativePending()) changes.setString("status", nativeControllerState().statusCaption());
            updateState(
                ui.session.state()
                    .withMetadata(changes));
        }
        return true;
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
            if (localControllerStandard() && !current.data.getBoolean("nativeTargetReady")) return true;
            if (localControllerStandard() && HologramLocalControllerEdits.handles(operation)) {
                if ("customPin".equals(operation)) {
                    int slot = request.getInteger("inventorySlot");
                    ItemStack[] inventory = minecraft().thePlayer.inventory.mainInventory;
                    ItemStack expected = ItemStack.loadItemStackFromNBT(request.getCompoundTag("expectedSample"));
                    ItemStack actual = slot >= 0 && slot < inventory.length ? inventory[slot] : null;
                    if (actual == null || expected == null
                        || !actual.isItemEqual(expected)
                        || !ItemStack.areItemStackTagsEqual(actual, expected))
                        throw new java.io.IOException(
                            projectorOr("native.ui.the_inventory_sample_changed_select_it_again", "库存样本已变化，请重新选择"));
                }
                if (request.getIntArray("selectedIndices").length > 128) throw new java.io.IOException(
                    projectorOr("native.ui.at_most_128_explicit_hatch_positions_may", "单次最多配置128个显式仓室位置。"));
                var changed = new java.util.ArrayList<HologramState.Cell>();
                HologramLocalControllerEdits.apply(
                    current,
                    localControllerBaseline,
                    operation,
                    request,
                    this::nativeDeclarationRow,
                    changed::add);
                NBTTagCompound settings = ui.config.configuration();
                var selected = new java.util.LinkedHashMap<String, NBTTagCompound>();
                NBTTagList previous = settings.getTagList("nativeHatchChoices", 10);
                for (int i = 0; i < previous.tagCount(); i++) {
                    var choice = previous.getCompoundTagAt(i);
                    selected.put(
                        choice.getInteger("x") + "," + choice.getInteger("y") + "," + choice.getInteger("z"),
                        (NBTTagCompound) choice.copy());
                }
                boolean restore = "restoreCell".equals(operation) || "clearPin".equals(operation);
                for (var cell : changed) {
                    String position = cell.dx + "," + cell.dy + "," + cell.dz;
                    if (restore) {
                        selected.remove(position);
                        continue;
                    }
                    selected.put(position, HologramLocalControllerEdits.nativeChoice(cell));
                }
                if (selected.size() > 128) throw new java.io.IOException(
                    projectorOr("native.ui.at_most_128_explicit_hatch_positions_may_253", "最多配置128个显式仓室位置。"));
                NBTTagList choices = new NBTTagList();
                for (var choice : selected.values()) choices.appendTag(choice);
                settings.setTag("nativeHatchChoices", choices);
                refreshLocalController(settings);
                localControllerInteractionCell = null;
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
            ui.session.notice(
                failure.getMessage() == null ? projectorOr("native.ui.cannot_edit_the_current_structure", "无法编辑当前结构")
                    : failure.getMessage());
            return true;
        }
    }

    /** Planning does not submit; direct construction requires a click in the existing material panel. */
    boolean reviewLocalConstruction(NBTTagCompound settings) {
        if (!localControllerStandard()) return false;
        String unavailable = nativeActionBlocker(nativeControllerState().action());
        ui.session.notice(
            unavailable.isEmpty() ? projectorOr("native.ui.confirm_construction_on_the_materials_page", "请在材料页确认施工。")
                : unavailable);
        return false;
    }

    HologramClientStockNative.DeclarationSite stockNativeSite() {
        return localControllerStandard() && controllerContextCurrent()
            ? HologramClientStockNative.DeclarationSite.read(minecraft().theWorld, openingController)
            : null;
    }

    boolean hasLocalNativeChoices() {
        return nativeTargetSettings != null && (nativeTargetSettings.getBoolean("presetActive")
            || nativeTargetSettings.getTagList("nativeHatchChoices", 10)
                .tagCount() > 0);
    }

    private java.util.Set<Integer> nativeConstructionChoices() {
        java.util.Set<Integer> result = new java.util.LinkedHashSet<>();
        if (nativeTargetSettings != null)
            for (var cell : ui.session.state().cells) if (!cell.anchor && cell.targetStack != null
                && cell.targetStack.getItem() instanceof gregtech.common.blocks.ItemMachines
                && gregtech.common.blocks.ItemMachines.getMetaTileEntity(
                    cell.targetStack) instanceof gregtech.api.metatileentity.implementations.MTEHatch)
                result.add(cell.index);
        return result;
    }

    void applyNativePreset(NBTTagCompound settings) {
        if (!localMaterialOwnerCurrent(ui) || !ui.session.configurationEditable()) return;
        int tier = settings.getInteger("hatchTier");
        if (!settings.hasKey("hatchTier", 3) || tier < 0 || tier >= gregtech.api.enums.GTValues.VN.length) return;
        NBTTagCompound target = (NBTTagCompound) settings.copy();
        target.setBoolean("presetActive", true);
        target.removeTag("applyHatchPreset");
        refreshLocalController(target);
    }

    void receiveNativePreset(com.miaokatze.gtmb.hologram.HologramNativePresetStock.Reply reply) {
        // Legacy inventory quotes cannot publish a target in the revisioned native controller workflow.
    }

    NBTTagCompound currentNativeHatchProfile(int tier, boolean downgrade) throws java.io.IOException {
        HologramState current = ui.session.state();
        if (!localMaterialOwnerCurrent(ui) || !sameLocalControllerTarget(current) || localController == null)
            throw new java.io.IOException(HologramClient.text("nativePresetUnavailable", "Preset unavailable"));
        var overrides = new java.util.LinkedHashMap<Integer, ItemStack>();
        for (int index : nativeConstructionChoices()) {
            ItemStack sample = current.cells.get(index).targetStack;
            if (sample != null) overrides.put(index, sample.copy());
        }
        NBTTagCompound settings = new NBTTagCompound();
        settings.setInteger(
            "hatchTier",
            current.data.getBoolean("presetActive") && current.data.hasKey("hatchTier", 3)
                ? current.data.getInteger("hatchTier")
                : Math.max(0, tier));
        settings.setBoolean("hatchDowngrade", downgrade);
        return localController.captureNativeHatchProfile(overrides, settings);
    }

    void clearNativePreset() {
        if (!localMaterialOwnerCurrent(ui) || !ui.session.configurationEditable()) return;
        NBTTagCompound target = ui.config.configuration();
        target.setBoolean("presetActive", false);
        target.setInteger("hatchTier", -1);
        target.removeTag("hatchProfile");
        refreshLocalController(target);
    }

    boolean stockNativePending() {
        return HologramClientStockNative.OWNER.pending(this);
    }

    boolean confirmStockNative(NBTTagCompound settings, HologramClientStockNative.DeclarationSite paneSite) {
        if (!localControllerStandard()) return false;
        var state = ui.session.state();
        var nativeState = nativeControllerState();
        String blocker = nativeActionBlocker(nativeState.action());
        if (!blocker.isEmpty()) {
            ui.session.notice(blocker);
            return false;
        }
        if (settings.getLong("nativeTargetRevision") != nativeState.targetRevision() || paneSite == null
            || !paneSite.equals(stockNativeSite())) {
            ui.session
                .notice(projectorOr("native.ui.the_complete_target_or_world_changed_review", "当前完整目标或现场已变化，请重新核对材料。"));
            return false;
        }
        var client = minecraft();
        var held = client.thePlayer.getHeldItem();
        var tool = HologramClientStockNative.canonicalTool(toolId(held));
        boolean creative = client.thePlayer.capabilities != null && client.thePlayer.capabilities.isCreativeMode;
        boolean sourceLess = nativeState.action()
            == com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.DEMOLISH;
        if (tool == null || !tool.toString()
            .equals(openingToolId) || nativeSourceKey(settings, creative || sourceLess) < 0) {
            ui.session.notice(
                projectorOr("native.ui.the_projector_identity_or_material_sources_changed", "投影仪身份或材料来源已变化，请重新确认。"));
            return false;
        }
        var projection = nativeProjection(state, settings, settings.getInteger("demolitionDestination"), false);
        var context = HologramClientStockNative.context(client);
        long sequence = com.miaokatze.gtmb.hologram.HologramNetwork.nextConstructionIntentSequence();
        if (projection == null || context == null || sequence <= 0) return false;
        var key = new com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key(java.util.UUID.randomUUID(), sequence);
        var original = com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation.projection(
            key,
            client.thePlayer.dimension,
            tool,
            openingController.xCoord,
            openingController.yCoord,
            openingController.zCoord,
            localControllerSide,
            openingSlot,
            paneSite.facing(),
            settings.getInteger("main"),
            state.data.getBoolean("mainExplicit"),
            false,
            nativeSourceKey(settings, creative || sourceLess),
            projection);
        long revision = nativeState.targetRevision();
        var operation = nativeState.action();
        var view = ui;
        ItemStack pinnedTool = held.copy();
        java.util.function.BooleanSupplier visible = () -> minecraft().currentScreen == this && ui == view
            && localControllerStandard()
            && context.same(HologramClientStockNative.context(minecraft()));
        java.util.function.BooleanSupplier exact = () -> controllerContextCurrent()
            && context.same(HologramClientStockNative.context(minecraft()))
            && nativeControllerState().targetRevision() == revision
            && nativeControllerState().action() == operation
            && (ui.session.state().data.getBoolean("nativeTargetReady")
                || operation == com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.DEMOLISH)
            && minecraft().thePlayer.inventory.currentItem == original.slot()
            && minecraft().thePlayer.capabilities.isCreativeMode == creative
            && ItemStack.areItemStacksEqual(pinnedTool, minecraft().thePlayer.getHeldItem())
            && nativeSourceKey(ui.config.configuration(), creative || sourceLess) == original.sources()
            && paneSite.current(minecraft().theWorld);
        var owner = new HologramClientStockNative.Owner(
            this,
            () -> visible.getAsBoolean() && exact.getAsBoolean(),
            visible,
            view.review::stockNativeNotice,
            reply -> {
                nativeControllerState().invalidateObservation();
                nativeObservationNeeded = true;
                if (visible.getAsBoolean()) refreshLocalController(null);
            },
            exact,
            () -> { if (controllerContextCurrent()) minecraft().thePlayer.closeScreen(); });
        com.miaokatze.gtmb.hologram.HologramStockActionTrace.event("client.admitted", key, "operation=" + operation);
        return HologramClientStockNative.OWNER.submit(original, context, owner, System.nanoTime());
    }

    private static int nativeSourceKey(NBTTagCompound settings, boolean sourceLess) {
        var sources = new com.miaokatze.gtmb.hologram.HologramMaterials.Settings();
        sources.main = settings.getBoolean("materialMain");
        sources.containers = settings.getBoolean("materialContainers");
        sources.me = settings.getBoolean("materialMe");
        sources.priority = settings.getInteger("materialPriority");
        sources.recoveryDestination = settings.getInteger("demolitionDestination");
        return com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy.structurePlacementKey(sources, sourceLess);
    }

    void rescanDisplayedStructure() {
        if (localControllerStandard()) {
            refreshLocalController(null);
            return;
        }
        HologramState current = ui.session.state();
        if (ui.session.localDocument() || !current.data.getBoolean("target") || current.data.getBoolean("virtual"))
            return;
        // A server-backed structure retains its prepared preset and current editor owner.
        ui.session.send("scan", null, true);
    }

    boolean localConfigurationCurrent(HologramUiModules owner) {
        return owner == ui && localControllerStandard()
            && (localController != null || nativeTargetSettings != null)
            && minecraft().currentScreen == this
            && controllerContextCurrent();
    }

    /** Options with no geometry dependency survive the independently completed capture publications. */
    static NBTTagCompound localControllerPreferences(NBTTagCompound settings) {
        NBTTagCompound preferences = new NBTTagCompound();
        for (String key : new String[] { "mode", "scope", "layer", "materialPriority", "materialMain",
            "materialContainers", "materialMe" }) {
            if (settings.hasKey(key)) preferences.setTag(
                key,
                settings.getTag(key)
                    .copy());
        }
        return preferences;
    }

    void refreshLocalController(NBTTagCompound request) {
        if (!localControllerStandard() || !controllerContextCurrent()
            || localController != null && localControllerTarget == null
                && (request == null || !localConfigurationCurrent(ui)))
            return;
        Minecraft client = minecraft();
        HologramState previous = ui.session.state();
        ItemStack tool = client.thePlayer.getHeldItem();
        NBTTagCompound live = HologramLocalControllerCapture.toolSettings(tool);
        NBTTagCompound settings = request == null ? ui.config.configuration() : (NBTTagCompound) request.copy();
        settings.setBoolean(
            "mainExplicit",
            previous.data.getBoolean("mainExplicit")
                || settings.getInteger("main") != previous.data.getInteger("main"));
        if (!HologramLocalControllerCapture.validSettings(settings)) {
            ui.session.notice(projectorOr("native.ui.invalid_parameters_configuration_unchanged", "参数无效，配置未修改"));
            return;
        }
        boolean geometryChanged = request == null || requiresControllerCapture(
            previous,
            settings,
            nativeTargetSettings,
            nativeControllerState().metadata()
                .getBoolean("nativeTargetReady"));
        if (!geometryChanged && nativeControllerState().metadata()
            .getBoolean("nativeTargetReady")) {
            nativeTargetSettings = (NBTTagCompound) settings.copy();
            updateState(
                previous.withMetadata(localController == null ? settings : localControllerPreferences(settings)));
            if (localController == null || localControllerTarget != null) localControllerToolSettings = live;
            else pendingControllerToolSettings = live;
            return;
        }
        HologramState fallback = localController == null || localControllerTarget != null ? previous
            : localControllerPrevious;
        nativeControllerState().targetChanged();
        nativeObservationNeeded = false;
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
        pendingNativeTargetSettings = (NBTTagCompound) settings.copy();
        NBTTagCompound reading = localController.opening();
        reading = withNativeControllerState(reading);
        reading.setString(
            "status",
            HologramClient.text(
                "controllerLocalPreparing",
                projectorOr("native.ui.reading_the_local_native_structure", "正在读取本地标准结构…")));
        updateState(new HologramState(reading));
    }

    static boolean requiresControllerCapture(HologramState published, NBTTagCompound settings, NBTTagCompound canonical,
        boolean ready) {
        return !ready || published.data.getBoolean("localStructureUnavailable")
            || settings.getInteger("main") != published.data.getInteger("main")
            || !settings.getCompoundTag("channels")
                .equals(published.data.getCompoundTag("channels"))
            || settings.getBoolean("noHatches") != published.data.getBoolean("noHatches")
            || !sameNativeTargetOptions(settings, canonical);
    }

    private static boolean sameNativeTargetOptions(NBTTagCompound a, NBTTagCompound b) {
        if (b == null) return false;
        for (String key : new String[] { "presetActive", "hatchTier", "hatchDowngrade", "hatchProfile",
            "nativeHatchChoices" }) if (!java.util.Objects.equals(a.getTag(key), b.getTag(key))) return false;
        return true;
    }

    private NBTTagCompound mergeNativeTargetMetadata(NBTTagCompound source) {
        NBTTagCompound result = (NBTTagCompound) source.copy();
        if (nativeTargetSettings != null) for (String key : new String[] { "main", "mainExplicit", "channels",
            "presetActive", "hatchTier", "hatchDowngrade", "hatchProfile", "nativeHatchChoices" })
            if (nativeTargetSettings.hasKey(key)) result.setTag(
                key,
                nativeTargetSettings.getTag(key)
                    .copy());
        var state = nativeControllerState().metadata();
        for (Object key : state.func_150296_c()) result.setTag((String) key, state.getTag((String) key));
        return result;
    }

    private NBTTagCompound withNativeControllerState(NBTTagCompound source) {
        NBTTagCompound result = (NBTTagCompound) source.copy();
        var state = nativeControllerState().metadata();
        for (Object key : state.func_150296_c()) result.setTag((String) key, state.getTag((String) key));
        return result;
    }

    static NBTTagCompound targetSettings(HologramState source) {
        NBTTagCompound result = new NBTTagCompound();
        for (String key : new String[] { "main", "mainExplicit", "channels", "presetActive", "hatchTier",
            "hatchDowngrade", "hatchProfile", "nativeHatchChoices" })
            if (source.data.hasKey(key)) result.setTag(
                key,
                source.data.getTag(key)
                    .copy());
        return result;
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
        if (!localControllerStandard()) return;
        requestNativeObservation();
        if (localController == null) return;
        boolean retire = false;
        try {
            Minecraft client = minecraft();
            if (!controllerContextCurrent() || !(localControllerTarget == null
                ? localController.matches(client.theWorld, client.thePlayer.dimension)
                : localController.controllerContextMatches(client.theWorld, client.thePlayer.dimension)))
                throw new java.io.IOException("LOCAL_CONTROLLER_CHANGED");
            long deadline = System.nanoTime() + LOCAL_CONTROLLER_FRAME_NANOS;
            if (localControllerTarget == null) {
                advanceLocalControllerTarget(deadline);
                if (System.nanoTime() < deadline) advanceLocalControllerConfiguration();
                return;
            }
            if (System.nanoTime() < deadline) try {
                prepareSelectedControllerRow();
            } catch (java.io.IOException | RuntimeException | LinkageError unavailable) {
                int index = ui.preview.selected();
                if (index >= 0 && index < ui.session.state().cells.size())
                    localControllerInteractionCell = ui.session.state().cells.get(index);
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                    .debug("Local native declaration options unavailable", unavailable);
            }
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            retire = true;
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .debug("Local controller standard structure unavailable", failure);
            String status = HologramClient.text(
                "controllerLocalUnavailable",
                projectorOr("native.ui.this_controller_has_no_available_local_native", "此控制器没有可用的本地标准结构"));
            if (failure.getMessage() != null && !failure.getMessage()
                .isEmpty()) status = HologramNativeUiText.reason(failure.getMessage());
            nativeControllerState().targetFailed(status);
            nativeObservationNeeded = true;
            if (failure instanceof java.io.IOException
                && "LOCAL_NATIVE_PREVIEW_INCOMPLETE".equals(failure.getMessage())) {
                boolean retained = localControllerPrevious != null && !localControllerPrevious.cells.isEmpty();
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn(
                    "Controller preview stage=capture-failed generation={} reason=LOCAL_NATIVE_PREVIEW_INCOMPLETE retainedPrevious={}",
                    ui.session.state().data.getString("generation"),
                    retained);
                status = retained
                    ? projectorOr(
                        "native.ui.the_native_declaration_returned_an_incomplete_structure",
                        "原生声明未返回完整结构，已保留上一次预览")
                    : projectorOr(
                        "native.ui.the_native_declaration_returned_an_incomplete_structure_261",
                        "原生声明未返回完整结构，未显示不完整的预览");
            }
            if (localControllerPrevious != null && !localControllerPrevious.cells.isEmpty()) {
                updateState(
                    failedControllerTarget(
                        localControllerPrevious,
                        withNativeControllerState(localControllerPreferences(ui.session.state().data)),
                        status));
                nativeTargetSettings = targetSettings(localControllerPrevious);
                ui.session.notice(status);
            } else {
                // Read-only site observation remains possible even when no target has ever been published.
                if (nativeTargetSettings == null && pendingNativeTargetSettings != null)
                    nativeTargetSettings = (NBTTagCompound) pendingNativeTargetSettings.copy();
                NBTTagCompound unavailable = withNativeControllerState(
                    localControllerPreferences(ui.session.state().data));
                unavailable.setBoolean("supported", false);
                unavailable.setBoolean("localStructureUnavailable", true);
                // Failure publication uses only already-owned metadata. Never call back into the
                // incomplete native target (or its capabilities) while handling its exception.
                updateState(failedControllerTarget(ui.session.state(), unavailable, status));
            }
        } finally {
            if (retire) retireLocalController();
        }
    }

    static HologramState failedControllerTarget(HologramState previous, NBTTagCompound metadata, String status) {
        NBTTagCompound failure = (NBTTagCompound) metadata.copy();
        failure.setBoolean("capturePending", false);
        failure.setBoolean("nativeTargetReady", false);
        failure.setBoolean("nativeExecutionSupported", false);
        failure.setString("status", status);
        return previous.withMetadata(failure);
    }

    private void advanceLocalControllerTarget(long deadline) throws java.io.IOException {
        if (localControllerPreparation == null) {
            HologramControllerTarget.Projection snapshot = localController
                .stepTarget(LOCAL_CONTROLLER_FRAME_ROWS, Math.max(1, deadline - System.nanoTime()));
            if (snapshot == null) return;
            snapshot.metadata()
                .setString("status", localSiteCaption(snapshot.metadata()));
            localControllerPreparation = new HologramState.Preparation(snapshot);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        long preparation = HologramRuntimeMetrics.start();
        try {
            if (!localControllerPreparation.step(LOCAL_CONTROLLER_FRAME_ROWS, remaining)) return;
            localControllerTarget = carryLocalControllerPreferences(
                localControllerPreparation.result(),
                ui.session.state());
            if (localControllerCapabilities != null)
                localControllerTarget = localControllerTarget.withMetadata(localControllerCapabilityMetadata());
            localControllerBaseline = localControllerTarget;
            if (pendingControllerToolSettings != null) localControllerToolSettings = pendingControllerToolSettings;
            pendingControllerToolSettings = null;
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

    static String localSiteCaption(NBTTagCompound data) {
        if (!data.hasKey("siteOccupied", 3)) return projectorOr("native.ui.reading_the_controller_site", "正在读取控制器现场");
        boolean installed = data.hasKey("siteNativeGroups", 3);
        int occupied = data.getInteger(installed ? "siteNativeOccupied" : "siteOccupied");
        int unknown = data.getInteger(installed ? "siteNativeUnknown" : "siteUnknown");
        String description = occupied > 0
            ? (projectorOr("native.ui.world", "现场：") + " ") + occupied
                + (" " + projectorOr("native.ui.non_air_cells", "格非空，") + " ")
                + data.getInteger(installed ? "siteNativeHatches" : "siteHatches")
                + (" " + projectorOr("native.ui.hatches", "个仓室"))
            : unknown > 0 ? projectorOr("native.ui.world_no_body_found_in_the_read", "现场：已读取范围内未见机身")
                : projectorOr("native.ui.world_controller_only", "现场：仅控制器");
        return description + (" " + projectorOr("native.ui.missing_268", "· 缺") + " ")
            + data.getInteger("siteMissing")
            + (projectorOr("native.ui.different_from_target", "，与目标不同") + " ")
            + data.getInteger("siteDifferent")
            + (unknown > 0 ? (projectorOr("native.ui.unverified", "，未确认") + " ") + unknown : "");
    }

    private void advanceLocalControllerConfiguration() {
        if (localControllerCapabilities != null) return;
        localControllerCapabilities = localController.readyCapabilities();
        if (localControllerCapabilities != null) updateState(
            ui.session.state()
                .withMetadata(localControllerCapabilityMetadata()));
    }

    private static HologramState carryLocalControllerPreferences(HologramState target, HologramState current) {
        NBTTagCompound preferences = localControllerPreferences(current.data);
        boolean sameCompleted = current.data.getBoolean("nativeBuildSatisfied")
            && current.session.equals(target.session)
            && current.data.getLong("planRevision") == target.data.getLong("planRevision")
            && current.data.getString("generation")
                .equals(target.data.getString("generation"));
        if (sameCompleted) preferences.setBoolean("nativeBuildSatisfied", true);
        return preferences.equals(localControllerPreferences(target.data)) ? target : target.withMetadata(preferences);
    }

    private boolean sameLocalControllerTarget(HologramState state) {
        return localControllerTarget != null && state.cells.size() == localControllerTarget.cells.size()
            && state.session.equals(localControllerTarget.session)
            && state.data.getString("generation")
                .equals(localControllerTarget.data.getString("generation"));
    }

    HologramState.Cell interactionCell(HologramUiModules owner, HologramState state, int index) {
        return owner == ui && sameLocalControllerTarget(state)
            && localControllerInteractionCell != null
            && localControllerInteractionCell.index == index ? localControllerInteractionCell : null;
    }

    boolean earlyControllerRowReady(HologramUiModules owner) {
        HologramState state = ui.session.state();
        HologramState.Cell selected = interactionCell(owner, state, ui.preview.selected());
        return selected != null && !selected.anchor
            && state.data.getInteger("mode") != 2
            && localConfigurationCurrent(owner);
    }

    private void prepareSelectedControllerRow() throws java.io.IOException {
        HologramState state = ui.session.state();
        if (!sameLocalControllerTarget(state)) return;
        int selected = ui.preview.selected();
        if (localControllerInteractionCell != null && localControllerInteractionCell.index == selected) return;
        localControllerInteractionCell = selected >= 0 && selected < state.cells.size()
            ? nativeDeclarationRow(state.cells.get(selected))
            : null;
    }

    private HologramState.Cell nativeDeclarationRow(HologramState.Cell cell) throws java.io.IOException {
        if (localController == null || localControllerTarget == null || cell.anchor || !cell.candidates.isEmpty())
            return cell;
        var offers = localController.selectedOffers(cell.index);
        if (offers == null) return cell;
        // Geometry carries the replayed facing; the sparse choice keeps the explicit override for Restore Facing.
        NBTTagCompound row = HologramLocalControllerEdits.nativeFacingRow(
            cell,
            ui.config.configuration()
                .getTagList("nativeHatchChoices", 10));
        return new HologramState.Cell(new HologramState.Cell(cell.index, row), offers);
    }

    private void publishLocalController(HologramState state, HologramRuntimeMetrics.LoadStage stage) {
        long install = HologramRuntimeMetrics.start();
        try {
            nativeControllerState().targetReady();
            if (pendingNativeTargetSettings != null) nativeTargetSettings = pendingNativeTargetSettings;
            pendingNativeTargetSettings = null;
            state = state.withMetadata(mergeNativeTargetMetadata(state.data));
            nativeObservationNeeded = true;
            ui.preview.chooseControllerSiteView(state.data);
            updateState(state);
            HologramRuntimeMetrics.loadStage(stage, state.data, -1, state.cells.size());
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_INSTALL, install);
        }
    }

    void nativeBlockChanged(int x, int y, int z) {
        if (nativeObservationNeeded || !localControllerStandard() || !controllerContextCurrent()) return;
        if (ui.session.state().regions.at(x, y, z) != null) invalidateNativeObservation();
    }

    boolean nativeChunkChanged(int x, int z, int sections) {
        if (!localControllerStandard() || !controllerContextCurrent()) return false;
        if (nativeObservationNeeded) return true;
        if (!ui.session.state().regions.intersectsChunk(x, z, sections)) return false;
        invalidateNativeObservation();
        return true;
    }

    private void invalidateNativeObservation() {
        nativeObservationNeeded = true;
        nativeControllerState().invalidateObservation();
        var changes = nativeControllerState().metadata();
        changes.removeTag("mode");
        updateState(
            ui.session.state()
                .withMetadata(changes));
    }

    private void retireLocalController() {
        cancelNativeObservation();
        nativeObservationNeeded = true;
        if (localController != null) localController.close();
        localController = null;
        localControllerPreparation = null;
        localControllerTarget = null;
        localControllerInteractionCell = null;
        localControllerCapabilities = null;
        localControllerPrevious = null;
        pendingNativeTargetSettings = null;
        pendingControllerToolSettings = null;
    }

    private void cancelNativeObservation() {
        var pending = nativeObservationRequest;
        nativeObservationRequest = null;
        if (pending != null && minecraft().getNetHandler() == openingConnection)
            com.miaokatze.gtmb.hologram.HologramNetwork.sendStockNativeControl(
                pending.key(),
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Command.STOP);
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
            && controllerContextCurrent();
    }

    private boolean controllerContextCurrent() {
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
        // Normal inventory sync may replace the ItemStack while the held slot gets its first server UUID.
        // The owner, world, slot and controller above must still match, and no other stack data may change.
        if (!openingToolId.isEmpty()) return openingToolId.equals(heldId);
        if (heldId.isEmpty()) return held == openingTool || ItemStack.areItemStacksEqual(held, openingToolSnapshot);
        if (!sameToolWithFirstIdentity(openingToolSnapshot, held)) return false;
        openingTool = held;
        openingToolId = heldId;
        openingToolSnapshot = null;
        return true;
    }

    static boolean sameToolWithFirstIdentity(ItemStack old, ItemStack synced) {
        if (old == null || synced == null
            || !toolId(old).isEmpty()
            || HologramClientStockNative.canonicalTool(toolId(synced)) == null) return false;
        return ItemStack.areItemStacksEqual(withoutToolIdentity(old), withoutToolIdentity(synced));
    }

    private static ItemStack withoutToolIdentity(ItemStack value) {
        ItemStack copy = value.copy();
        NBTTagCompound tag = copy.getTagCompound();
        if (tag != null) {
            tag.removeTag("gtitHologramToolId");
            if (tag.hasNoTags()) copy.setTagCompound(null);
        }
        return copy;
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
        if (localControllerStandard()) {
            if (!ready) return;
            retireLocalController();
        }
        openingController = null;
        openingConnection = null;
        openingTool = null;
        openingToolSnapshot = null;
        openingToolId = null;
    }

    boolean closeControllerOpening() {
        if (controllerRequest == 0) return false;
        HologramClient.controllerOpen.dismiss(controllerRequest);
        if (localControllerStandard()) controllerOpened(false);
        else if (minecraft().currentScreen == this) minecraft().displayGuiScreen(null);
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
        deferEscapeAction(() -> { if (ui == inputOwner) action.run(); });
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

    boolean occludesWorldProjection(HologramState snapshot) {
        return snapshot != null && workspaceMount != null
            && workspaceMount.isValid()
            && ui.session.state() == snapshot
            && snapshot.data.getBoolean("target")
            && !snapshot.data.getBoolean("virtual")
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
            } else if (controllerRequest == 0 && !localWorkspaceView()) ui.router.onGuiClosed();
            else org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        } finally {
            if (!detached && controllerRequest == 0 && localControllerStandard() && controllerContextCurrent()) {
                var stockContext = HologramClientStockNative.context(minecraft());
                HologramLocalControllerGhost.retain(
                    minecraft(),
                    openingController,
                    openingToolId,
                    ui.session.state(),
                    HologramClientStockNative.OWNER.requestFor(this, stockContext));
            }
            HologramClientStockNative.OWNER.close(this, HologramClientStockNative.context(minecraft()));
            retireLocalController();
            localControllerBaseline = null;
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
        ui.session.notice(
            failure.getMessage() == null ? projectorOr("native.ui.the_ui_action_failed_please_retry", "界面操作失败，请重试")
                : failure.getMessage());
    }

    @Override
    public void onGuiClosed() {
        nativeObservationNeeded = true;
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
        if (localWorkspaceView()) return;
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
        if (id == HologramEditorControlIds.GUIDE) return projectorOr(
            "native.ui.collapse_expand_smart_guidance_this_preference_is",
            "收纳 / 展开智能指引；此偏好会保留到下次打开。");
        if (id == HologramEditorControlIds.GUIDE + 1)
            return projectorOr("native.ui.full_operation_guide_and_eight_independent_tutorials", "完整操作说明与八门独立教程。");
        if (id == HologramEditorControlIds.TUTORIAL + 1)
            return projectorOr("native.ui.return_to_the_beginning_of_the_previous", "回到上一步的动画开头。");
        if (id == HologramEditorControlIds.TUTORIAL + 2)
            return projectorOr("native.ui.continue_to_the_next_step_after_this", "继续下一步；当前动画会先完整播放。");
        if (id == HologramEditorControlIds.TUTORIAL + 3)
            return projectorOr("native.ui.skip_the_tutorial_and_restore_the_original", "跳过教程并恢复原工作区。");
        if (id == HologramEditorControlIds.TUTORIAL + 4)
            return projectorOr("native.ui.clear_tutorial_progress_and_restart_this_lesson", "清空教学进度，从本课程第一步重新播放。");
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
