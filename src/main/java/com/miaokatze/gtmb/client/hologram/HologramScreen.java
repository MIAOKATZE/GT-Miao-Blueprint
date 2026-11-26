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

import com.github.bsideup.jabel.Desugar;
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
    private HologramLocalControllerGhost.Profile rememberedControllerPlacement;
    private HologramState localControllerBaseline;
    private HologramState.Cell localControllerInteractionCell;
    private net.minecraft.nbt.NBTTagList localControllerCapabilities;
    private static final java.util.concurrent.atomic.AtomicLong NATIVE_PRESET_IDS = new java.util.concurrent.atomic.AtomicLong();

    @Desugar
    private record NativePresetRequest(long id, HologramState state, DeclarationSiteReference site,
        com.miaokatze.gtmb.hologram.HologramNativePresetIndex.Prepared prepared, int tier, int sources, long started) {}

    @Desugar
    private record DeclarationSiteReference(HologramClientStockNative.DeclarationSite site, java.util.UUID tool) {}

    private final java.util.Set<Integer> localPresetChoices = new java.util.LinkedHashSet<>();
    private NativePresetRequest nativePresetRequest;

    private final java.util.Set<Integer> localNativeChoices = new java.util.LinkedHashSet<>();
    private NBTTagCompound localControllerToolSettings, pendingControllerToolSettings;
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
        data.setString("title", "全息构造器");
        data.setString("status", "右击真实控制器导入结构，或在蓝图库选择蓝图后点击放置");
        return new HologramState(data);
    }

    static HologramScreen localController(TileEntity tile, int dimension, int side) {
        long started = HologramRuntimeMetrics.start();
        try {
            HologramLocalControllerCapture capture;
            NBTTagCompound settings, inheritedSettings;
            HologramLocalControllerGhost.Profile remembered;
            HologramState state;
            try {
                Minecraft client = Minecraft.getMinecraft();
                ItemStack tool = client.thePlayer.getHeldItem();
                inheritedSettings = HologramLocalControllerCapture.toolSettings(tool);
                settings = (NBTTagCompound) inheritedSettings.copy();
                remembered = HologramLocalControllerGhost.latestFor(client, tile);
                if (remembered != null) remembered.applyChannels(settings);
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
            screen.localControllerToolSettings = inheritedSettings;
            screen.rememberedControllerPlacement = remembered;
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
            localPresetChoices.clear();
            nativePresetRequest = null;
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
        localPresetChoices.clear();
        nativePresetRequest = null;
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
            if (localControllerStandard() && current.data.getBoolean("capturePending")) return true;
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
                    HologramLocalControllerEdits.apply(
                        current,
                        localControllerBaseline,
                        operation,
                        request,
                        this::nativeDeclarationRow,
                        cell -> {
                            if ("manual".equals(cell.pinScope) || cell.hatchFacing >= 0)
                                localNativeChoices.add(cell.index);
                            else localNativeChoices.remove(cell.index);
                            if (!"preset".equals(cell.pinScope)) localPresetChoices.remove(cell.index);
                        }));
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
            ui.session.notice(failure.getMessage() == null ? "无法编辑当前结构" : failure.getMessage());
            return true;
        }
    }

    /** Planning does not submit; direct construction requires a click in the existing material panel. */
    boolean reviewLocalConstruction(NBTTagCompound settings) {
        if (!localControllerStandard()) return false;
        String unavailable = nativeConstructionUnavailable(
            ui.session.state().data,
            settings,
            !localNativeChoices.isEmpty());
        ui.session.notice(unavailable.isEmpty() ? "请在材料页确认施工。" : unavailable);
        return false;
    }

    HologramClientStockNative.DeclarationSite stockNativeSite() {
        return localControllerStandard() && controllerContextCurrent()
            ? HologramClientStockNative.DeclarationSite.read(minecraft().theWorld, openingController)
            : null;
    }

    boolean hasLocalNativeChoices() {
        return !localNativeChoices.isEmpty() || !localPresetChoices.isEmpty();
    }

    private java.util.Set<Integer> nativeConstructionChoices() {
        java.util.Set<Integer> result = new java.util.LinkedHashSet<>(localPresetChoices);
        result.addAll(localNativeChoices);
        return result;
    }

    void applyNativePreset(NBTTagCompound settings) {
        if (!localMaterialOwnerCurrent(ui) || !ui.session.editable() || localController == null) return;
        HologramState current = ui.session.state();
        if (!sameLocalControllerTarget(current) || current.data.getInteger("mode") != 0
            || current.data.getBoolean("capturePending")
            || stockNativePending()) return;
        int tier = settings.getInteger("hatchTier");
        if (!settings.hasKey("hatchTier", 3) || tier < 0 || tier >= gregtech.api.enums.GTValues.VN.length) return;
        var site = stockNativeSite();
        var client = minecraft();
        ItemStack held = client.thePlayer.getHeldItem();
        var tool = HologramClientStockNative.canonicalTool(toolId(held));
        if (site == null || tool == null) return;
        var stock = com.miaokatze.gtmb.hologram.HologramMaterials.read(held);
        boolean creative = client.thePlayer.capabilities.isCreativeMode;
        if (!creative && !stockNativeSources(settings, stock, false)) {
            ui.session.notice(HologramClient.text("nativePresetUnavailable", "Preset unavailable"));
            return;
        }
        try {
            java.util.Map<Integer, Integer> manual = new java.util.LinkedHashMap<>();
            for (int index : localNativeChoices) {
                var cell = current.cells.get(index);
                if (cell.targetStack != null) manual.put(index, cell.targetStack.getItemDamage());
            }
            var prepared = localController.prepareNativePreset(settings, manual);
            int sources = com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy.settingsKey(stock);
            long id = NATIVE_PRESET_IDS.incrementAndGet();
            var pending = new NativePresetRequest(
                id,
                current,
                new DeclarationSiteReference(site, tool),
                prepared,
                tier,
                sources,
                System.nanoTime());
            nativePresetRequest = pending;
            int[] ids = prepared.stockIds();
            if (creative || ids.length == 0) {
                finishNativePreset(pending, new int[ids.length], creative);
            } else if (!com.miaokatze.gtmb.hologram.HologramNativePresetStock.request(
                id,
                tool,
                client.thePlayer.dimension,
                openingController.xCoord,
                openingController.yCoord,
                openingController.zCoord,
                site.controllerId(),
                site.facing(),
                openingSlot,
                sources,
                ids)) {
                    nativePresetRequest = null;
                    ui.session.notice(HologramClient.text("nativePresetUnavailable", "Preset unavailable"));
                }
        } catch (java.io.IOException | RuntimeException failure) {
            nativePresetRequest = null;
            ui.session.notice(HologramClient.text("nativePresetUnavailable", "Preset unavailable"));
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.debug("Native preset preparation failed", failure);
        }
    }

    void receiveNativePreset(com.miaokatze.gtmb.hologram.HologramNativePresetStock.Reply reply) {
        var pending = nativePresetRequest;
        if (pending == null || pending.id() != reply.id()) return;
        if (!reply.reason()
            .isEmpty()
            || !java.util.Arrays.equals(
                pending.prepared()
                    .stockIds(),
                reply.ids())) {
            nativePresetRequest = null;
            ui.session.notice(HologramClient.text("nativePresetUnavailable", "Preset unavailable"));
            return;
        }
        finishNativePreset(pending, reply.counts(), false);
    }

    private void finishNativePreset(NativePresetRequest pending, int[] counts, boolean creative) {
        nativePresetRequest = null;
        HologramState current = ui.session.state();
        if (!localMaterialOwnerCurrent(ui) || !sameNativeReview(pending.state(), current)
            || !pending.site()
                .site()
                .equals(stockNativeSite())
            || stockNativePending()) return;
        var held = minecraft().thePlayer.getHeldItem();
        if (!pending.site()
            .tool()
            .equals(HologramClientStockNative.canonicalTool(toolId(held)))
            || creative != minecraft().thePlayer.capabilities.isCreativeMode
            || pending.sources() != com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy
                .settingsKey(com.miaokatze.gtmb.hologram.HologramMaterials.read(held)))
            return;
        try {
            var outcome = pending.prepared()
                .resolve(counts, creative);
            if (!outcome.complete()) {
                ui.session.notice(outcome.reason());
                return;
            }
            var state = HologramLocalControllerEdits.replaceNativePreset(
                current,
                localControllerBaseline,
                localPresetChoices,
                outcome.choices(),
                pending.tier(),
                true);
            localPresetChoices.clear();
            for (var choice : outcome.choices()) localPresetChoices.add(choice.index());
            localControllerInteractionCell = null;
            updateState(state);
            ui.session.notice(HologramClient.text("nativePresetApplied", "Preset applied"));
        } catch (java.io.IOException | RuntimeException failure) {
            ui.session.notice(HologramClient.text("nativePresetUnavailable", "Preset unavailable"));
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.debug("Native preset update failed", failure);
        }
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
        settings
            .setInteger("hatchTier", current.data.hasKey("hatchTier", 3) ? current.data.getInteger("hatchTier") : tier);
        settings.setBoolean("hatchDowngrade", downgrade);
        return localController.captureNativeHatchProfile(overrides, settings);
    }

    void clearNativePreset() {
        if (!localMaterialOwnerCurrent(ui) || !ui.session.editable() || stockNativePending()) return;
        nativePresetRequest = null;
        if (!ui.session.state().data.getBoolean("nativePresetExact")) return;
        try {
            var next = HologramLocalControllerEdits.replaceNativePreset(
                ui.session.state(),
                localControllerBaseline,
                localPresetChoices,
                java.util.Collections.emptyList(),
                0,
                false);
            localPresetChoices.clear();
            localControllerInteractionCell = null;
            updateState(next);
        } catch (java.io.IOException | RuntimeException failure) {
            ui.session.notice(HologramClient.text("nativePresetUnavailable", "Preset unavailable"));
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.debug("Native preset clear failed", failure);
        }
    }

    boolean stockNativePending() {
        return HologramClientStockNative.OWNER.pending(this);
    }

    boolean confirmStockNative(NBTTagCompound settings, HologramClientStockNative.DeclarationSite paneSite) {
        if (!localControllerStandard()) return false;
        if (stockNativePending()) {
            ui.session.notice("上一次施工请求尚未确认结束，请先核对现场。");
            return false;
        }
        HologramState displayed = ui.session.state();
        String blocker = HologramPlacementConfirmation
            .localStructureReviewBlocker(displayed, settings, hasLocalNativeChoices());
        if (blocker.isEmpty())
            blocker = nativeConstructionUnavailable(displayed.data, settings, !localNativeChoices.isEmpty());
        if (!blocker.isEmpty()) {
            ui.session.notice(blocker);
            return false;
        }
        Minecraft client = minecraft();
        if (paneSite == null || !controllerContextCurrent() || !paneSite.equals(stockNativeSite())) {
            ui.session.notice("已确认的原生控制器或手持投影仪已变化，请重新打开结构。");
            return false;
        }
        var capabilities = client.thePlayer.capabilities;
        boolean creative = capabilities != null && capabilities.isCreativeMode;
        if (creative && settings.getInteger("mode") != 0) {
            ui.session.notice("创造模式的结构施工仅支持新建和补建。");
            return false;
        }
        ItemStack held = client.thePlayer.getHeldItem();
        java.util.UUID tool = HologramClientStockNative.canonicalTool(toolId(held));
        if (tool == null || !tool.toString()
            .equals(openingToolId)) {
            ui.session.notice("投影仪身份尚未同步，请确认当前投影仪后重新打开。未发送施工。");
            return false;
        }
        var configured = com.miaokatze.gtmb.hologram.HologramMaterials.read(held);
        var declaration = declaredSelection(paneSite.controllerId(), displayed, settings, nativeConstructionChoices());
        if (declaration == null || !stockNativeSources(settings, configured, creative)) {
            ui.session.notice("施工范围、信道、手选仓室或材料来源无效，请重新确认。未发送施工。");
            return false;
        }
        int main = settings.getInteger("main");
        if (main < 1 || main > 64) {
            ui.session.notice("原生控制器的等级无效，请重新确认。未发送施工。");
            return false;
        }
        var context = HologramClientStockNative.context(client);
        long sequence = com.miaokatze.gtmb.hologram.HologramNetwork.nextConstructionIntentSequence();
        if (sequence <= 0 || context == null) {
            ui.session.notice("未提交施工：服务器连接未就绪。");
            return false;
        }
        int picked = ui.preview.selection()
            .version();
        int x = openingController.xCoord, y = openingController.yCoord, z = openingController.zCoord;
        ItemStack pinnedTool = held.copy();
        var nonce = new com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key(
            java.util.UUID.randomUUID(),
            sequence,
            0);
        int sourceKey = com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy
            .structurePlacementKey(configured, creative);
        var original = com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation.declared(
            nonce,
            client.thePlayer.dimension,
            tool,
            x,
            y,
            z,
            localControllerSide,
            openingSlot,
            paneSite.facing(),
            main,
            displayed.data.getBoolean("mainExplicit"),
            settings.getBoolean("noHatches"),
            sourceKey,
            declaration);
        com.miaokatze.gtmb.hologram.HologramStockActionTrace
            .event("client.admitted", nonce, "operation=" + declaration.operation());
        var view = ui;
        java.util.function.BooleanSupplier visible = () -> minecraft().currentScreen == this && ui == view
            && localControllerStandard()
            && context.same(HologramClientStockNative.context(minecraft()));
        var owner = new HologramClientStockNative.Owner(this, () -> {
            HologramState currentState = ui.session.state();
            NBTTagCompound active = ui.config.configuration();
            boolean sameWhole = HologramPlacementConfirmation.wholeNativeTargetReady(displayed, settings, false)
                && HologramPlacementConfirmation.sameWholeNativeTarget(displayed, currentState)
                && HologramPlacementConfirmation.wholeNativeTargetReady(currentState, active, hasLocalNativeChoices());
            if (!visible.getAsBoolean() || !ui.session.nativeConstructionEditable(active)
                || !sameNativeReview(displayed, currentState) && !sameWhole
                || !controllerContextCurrent()
                || creative
                    != (minecraft().thePlayer.capabilities != null && minecraft().thePlayer.capabilities.isCreativeMode)
                || !sameWhole && ui.preview.selection()
                    .version() != picked
                || openingController.xCoord != x
                || openingController.yCoord != y
                || openingController.zCoord != z
                || !ItemStack.areItemStacksEqual(pinnedTool, minecraft().thePlayer.getHeldItem())) return false;
            if (declaration.operation() == 2) active.setInteger("demolitionDestination", declaration.destination());
            return stockNativeSources(
                active,
                com.miaokatze.gtmb.hologram.HologramMaterials.read(minecraft().thePlayer.getHeldItem()),
                creative) && active.getInteger("main") == original.main()
                && active.getBoolean("noHatches") == original.noHatches()
                && ordinaryNativeSubmit(currentState.data, active, !localNativeChoices.isEmpty())
                && declaration.equals(
                    declaredSelection(paneSite.controllerId(), currentState, active, nativeConstructionChoices()))
                && paneSite.current(minecraft().theWorld);
        }, visible, view.review::stockNativeNotice, reply -> {
            if (reply.phase() != com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase.COMPLETE
                || declaration.operation() != 0
                || declaration.scope() != 0
                || !reply.reason()
                    .isEmpty())
                return;
            HologramLocalControllerGhost
                .rememberPlacement(context, paneSite, original, displayed.data.getInteger("hatchTier"));
            if (!visible.getAsBoolean()) return;
            HologramState current = view.session.state();
            if (current.session.equals(displayed.session) && current.data.getString("generation")
                .equals(displayed.data.getString("generation"))
                && current.data.getLong("planRevision") == displayed.data.getLong("planRevision")) {
                NBTTagCompound receipt = new NBTTagCompound();
                receipt.setBoolean("nativeBuildSatisfied", true);
                updateState(current.withMetadata(receipt));
            }
        }, () -> {
            var currentClient = Minecraft.getMinecraft();
            return context.same(HologramClientStockNative.context(currentClient))
                && currentClient.thePlayer.inventory.currentItem == original.slot()
                && currentClient.thePlayer.capabilities.isCreativeMode == creative
                && ItemStack.areItemStacksEqual(pinnedTool, currentClient.thePlayer.getHeldItem())
                && paneSite.current(currentClient.theWorld);
        });
        return HologramClientStockNative.OWNER.submit(original, context, owner, System.nanoTime());
    }

    /** HUD metadata may be republished while a PROBE is in flight. Never accept new geometry or planning authority. */
    static boolean sameNativeReview(HologramState before, HologramState now) {
        if (before == null || now == null
            || now.cells != before.cells
            || !before.session.equals(now.session)
            || !now.data.getBoolean("localStandardController")
            || now.data.getBoolean("targetClosed")
            || !HologramPlacementConfirmation.localStructureReviewBlocker(now)
                .isEmpty())
            return false;
        return before.data.getString("generation")
            .equals(now.data.getString("generation"))
            && before.data.getLong("planRevision") == now.data.getLong("planRevision")
            && before.data.getBoolean("mainExplicit") == now.data.getBoolean("mainExplicit");
    }

    static com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection declaredSelection(int controller,
        HologramState displayed, NBTTagCompound settings, java.util.Set<Integer> manual) {
        var selected = new java.util.ArrayList<com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Offset>();
        var hatches = new java.util.ArrayList<com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Hatch>();
        var channels = new java.util.LinkedHashMap<String, Integer>();
        if (!settings.hasKey("channels", 10)) return null;
        var source = settings.getCompoundTag("channels");
        for (Object name : source.func_150296_c()) {
            if (!source.hasKey((String) name, 3)) return null;
            channels.put((String) name, source.getInteger((String) name));
        }
        int mode = displayed.data.getInteger("mode");
        if ((mode != 0 || settings.hasKey("mode")) && !settings.hasKey("mode", 3)
            || settings.getInteger("mode") != mode) return null;
        int destination = mode == 2 && settings.hasKey("demolitionDestination", 3)
            ? settings.getInteger("demolitionDestination")
            : -1;
        int scope = settings.getInteger("scope"), layer = scope == 1 ? settings.getInteger("layer") : 0;
        int primary = settings.hasKey("selected", 3) ? settings.getInteger("selected") : -1;
        int[] indices = scope == 2 ? settings.getIntArray("selectedIndices") : new int[0];
        if (scope == 3 || scope == 2 && indices.length == 0) indices = new int[] { primary };
        if (indices.length > com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.MAX_POINTS) return null;
        var indicesInScope = new java.util.HashSet<Integer>();
        for (int index : indices) {
            if (index < 0 || index >= displayed.cells.size() || !indicesInScope.add(index)) return null;
            var cell = displayed.cells.get(index);
            selected
                .add(new com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Offset(cell.dx, cell.dy, cell.dz));
        }
        String family = scope == 3 ? displayed.cells.get(primary).family : "";
        for (int index : mode == 2 ? java.util.Collections.<Integer>emptySet() : manual) {
            if (index < 0 || index >= displayed.cells.size()) return null;
            var cell = displayed.cells.get(index);
            if (!(scope == 0 || scope == 1 && cell.dy == layer
                || scope == 2 && indicesInScope.contains(index)
                || scope == 3 && !family.isEmpty() && family.equals(cell.family))) continue;
            var target = cell.targetStack;
            if (target == null || !(target.getItem() instanceof gregtech.common.blocks.ItemMachines)
                || target.hasTagCompound()
                || hatches.size() >= com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.MAX_POINTS)
                return null;
            var point = new com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Offset(
                cell.dx,
                cell.dy,
                cell.dz);
            hatches.add(
                new com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Hatch(
                    point,
                    target.getItemDamage(),
                    cell.hatchFacing));
        }
        hatches.sort(
            java.util.Comparator
                .comparingInt(
                    (com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Hatch h) -> h.position()
                        .x())
                .thenComparingInt(
                    h -> h.position()
                        .y())
                .thenComparingInt(
                    h -> h.position()
                        .z()));
        var result = new com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection(
            controller,
            channels,
            scope,
            layer,
            selected,
            hatches,
            mode,
            destination);
        return result.valid() ? result : null;
    }

    private static boolean stockNativeSources(NBTTagCompound confirmed,
        com.miaokatze.gtmb.hologram.HologramMaterials.Settings projector, boolean creative) {
        return confirmed.getBoolean("materialMain") == projector.main
            && confirmed.getBoolean("materialContainers") == projector.containers
            && confirmed.getBoolean("materialMe") == projector.me
            && confirmed.getInteger("materialPriority") == projector.priority
            && com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy.structurePlacementKey(projector, creative) >= 0;
    }

    static String nativeConstructionUnavailable(NBTTagCompound state, NBTTagCompound settings, boolean localEdits) {
        int mode = state.getInteger("mode");
        if (mode != 0 && settings.getInteger("scope") != 2) return "请逐格选中原机已登记的仓室。当前不允许整机换装或拆除。";
        if (mode == 1 && !localEdits) return "请为所选仓室逐格指定替换类型或朝向。";
        if (!ordinaryNativeSubmit(state, settings, localEdits)) return "当前配置不属于现场控制器的原生声明。规划保留，未发送施工。";
        return "";
    }

    /** A current local display owns modes/scopes; the server resolves the same scope from its own definition. */
    static boolean ordinaryNativeSubmit(NBTTagCompound state, NBTTagCompound settings, boolean localEdits) {
        int scope = settings.getInteger("scope");
        int mode = state.getInteger("mode");
        return mode >= 0 && mode <= 2
            && settings.getInteger("mode") == mode
            && (mode == 0 ? scope >= 0 && scope <= 3 : scope == 2)
            && (scope >= 2 || settings.getIntArray("selectedIndices").length == 0)
            && (!state.getBoolean("presetActive") || state.getBoolean("nativePresetExact"))
            && settings.getString("structureId")
                .isEmpty();
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
            && localController != null
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
            updateState(
                previous.withMetadata(localController == null ? settings : localControllerPreferences(settings)));
            if (localController == null || localControllerTarget != null) localControllerToolSettings = live;
            else pendingControllerToolSettings = live;
            return;
        }
        HologramState fallback = localController == null || localControllerTarget != null ? previous
            : localControllerPrevious;
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
        if (nativePresetRequest != null
            && (!localMaterialOwnerCurrent(ui) || System.nanoTime() - nativePresetRequest.started() > 5_000_000_000L)) {
            nativePresetRequest = null;
            ui.session.notice(HologramClient.text("nativePresetUnavailable", "Preset unavailable"));
        }
        if (!localControllerStandard() || localController == null) return;
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
            String status = HologramClient.text("controllerLocalUnavailable", "此控制器没有可用的本地标准结构");
            if (failure instanceof java.io.IOException
                && "LOCAL_NATIVE_PREVIEW_INCOMPLETE".equals(failure.getMessage())) {
                boolean retained = localControllerPrevious != null && !localControllerPrevious.cells.isEmpty();
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn(
                    "Controller preview stage=capture-failed generation={} reason=LOCAL_NATIVE_PREVIEW_INCOMPLETE retainedPrevious={}",
                    ui.session.state().data.getString("generation"),
                    retained);
                status = retained ? "原生声明未返回完整结构，已保留上一次预览" : "原生声明未返回完整结构，未显示不完整的预览";
            }
            if (localControllerPrevious != null && !localControllerPrevious.cells.isEmpty()) {
                updateState(localControllerPrevious.withMetadata(localControllerPreferences(ui.session.state().data)));
                ui.session.notice(status);
            } else updateState(
                new HologramState(localController.unavailable(status))
                    .withMetadata(localControllerPreferences(ui.session.state().data)));
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
            localNativeChoices.clear();
            localPresetChoices.clear();
            nativePresetRequest = null;
            restoreRememberedHatches();
            if (pendingControllerToolSettings != null) localControllerToolSettings = pendingControllerToolSettings;
            pendingControllerToolSettings = null;
            localControllerPreparation = null;
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_PREPARE, preparation);
        }
        publishLocalController(localControllerTarget, HologramRuntimeMetrics.LoadStage.CLIENT_CONTROLLER_TARGET);
    }

    private void restoreRememberedHatches() {
        var remembered = rememberedControllerPlacement;
        rememberedControllerPlacement = null;
        if (remembered == null) return;
        try {
            var choices = remembered.choices(localControllerTarget, localController);
            if (choices.isEmpty()) return;
            localControllerTarget = HologramLocalControllerEdits.replaceNativePreset(
                localControllerTarget,
                localControllerBaseline,
                java.util.Collections.emptySet(),
                choices,
                remembered.tier(),
                true);
            for (var choice : choices) localPresetChoices.add(choice.index());
        } catch (java.io.IOException | RuntimeException | LinkageError stale) {
            // The cached channel selection can remain; incompatible hatch choices must never enter the new plan.
        }
    }

    private NBTTagCompound localControllerCapabilityMetadata() {
        NBTTagCompound changes = new NBTTagCompound();
        changes.setTag("capabilities", localControllerCapabilities);
        return changes;
    }

    static String localSiteCaption(NBTTagCompound data) {
        if (!data.hasKey("siteOccupied", 3)) return "正在读取控制器现场";
        boolean installed = data.hasKey("siteNativeGroups", 3);
        int occupied = data.getInteger(installed ? "siteNativeOccupied" : "siteOccupied");
        int unknown = data.getInteger(installed ? "siteNativeUnknown" : "siteUnknown");
        String description = occupied > 0
            ? "现场：" + occupied + " 格非空，" + data.getInteger(installed ? "siteNativeHatches" : "siteHatches") + " 个仓室"
            : unknown > 0 ? "现场：已读取范围内未见机身" : "现场：仅控制器";
        return description + " · 缺 "
            + data.getInteger("siteMissing")
            + "，与目标不同 "
            + data.getInteger("siteDifferent")
            + (unknown > 0 ? "，未确认 " + unknown : "");
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
        return offers == null ? cell : new HologramState.Cell(cell, offers);
    }

    private void publishLocalController(HologramState state, HologramRuntimeMetrics.LoadStage stage) {
        long install = HologramRuntimeMetrics.start();
        try {
            ui.preview.chooseControllerSiteView(state.data);
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
        localControllerTarget = null;
        localControllerInteractionCell = null;
        localControllerCapabilities = null;
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
            localNativeChoices.clear();
            localPresetChoices.clear();
            nativePresetRequest = null;
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
