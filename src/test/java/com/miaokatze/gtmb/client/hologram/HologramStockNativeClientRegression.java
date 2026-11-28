// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Command;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTECokeOven;
import io.netty.buffer.Unpooled;

/** Existing material control and button 55, real Forge codecs/receiver, isolated in-memory client owner. */
public final class HologramStockNativeClientRegression {

    private HologramStockNativeClientRegression() {}

    public static void main(String[] args) {
        int status = 0;
        try {
            if (MinecraftTestBootstrap.enter(HologramStockNativeClientRegression.class, args)) {
                com.miaokatze.gtmb.hologram.HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                    try {
                        run();
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                System.out.println("PASS stock native client panel, exact replies and detached completion");
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        }
        // This separate JavaExec owns vanilla's lifetime non-daemon I/O threads.
        System.exit(status);
    }

    public static void run() throws ReflectiveOperationException {
        run(false);
    }

    static void runNotifications() throws ReflectiveOperationException {
        run(true);
    }

    private static void run(boolean notificationsOnly) throws ReflectiveOperationException {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field instance = field(Minecraft.class, "theMinecraft");
        Object previousClient = instance.get(null);
        Block savedCasing = GregTechAPI.sBlockCasings12, savedMachines = GregTechAPI.sBlockMachines;
        instance.set(null, client);
        try {
            // This offline launcher has no GT access transformer for the graphical tooltip reload-listener.
            // FML 1.7.10 getEffectiveSide uses this name; only initialize that superclass as on a dedicated
            // server, then restore the actual client side before constructing any UI or dispatching any click.
            Thread bootstrap = Thread.currentThread();
            String priorName = bootstrap.getName();
            try {
                bootstrap.setName("Server thread");
                Class.forName(
                    "gregtech.api.metatileentity.implementations.MTETooltipMultiBlockBase",
                    true,
                    HologramStockNativeClientRegression.class.getClassLoader());
            } finally {
                bootstrap.setName(priorName);
            }
            field(Minecraft.class, "mcResourceManager").set(
                client,
                new net.minecraft.client.resources.SimpleReloadableResourceManager(
                    new net.minecraft.client.resources.data.IMetadataSerializer()));
            if (savedCasing == null) GregTechAPI.sBlockCasings12 = (gregtech.common.blocks.BlockCasings12) unsafe
                .allocateInstance(gregtech.common.blocks.BlockCasings12.class);
            if (savedMachines == null) GregTechAPI.sBlockMachines = Blocks.iron_block;
            runPanel(unsafe, client, notificationsOnly);
        } finally {
            GregTechAPI.sBlockCasings12 = savedCasing;
            GregTechAPI.sBlockMachines = savedMachines;
            instance.set(null, previousClient);
        }
    }

    private static final class FixturePlayer extends EntityClientPlayerMP {

        int closedScreens;

        private FixturePlayer() {
            super(null, null, null, null, null);
            throw new AssertionError("Unsafe-only fixture");
        }

        @Override
        public void closeScreen() {
            closedScreens++;
            Minecraft.getMinecraft().currentScreen = null;
        }
    }

    private static void runPanel(sun.misc.Unsafe unsafe, Minecraft client, boolean notificationsOnly)
        throws ReflectiveOperationException {
        var world = (CokeWorld) unsafe.allocateInstance(CokeWorld.class);
        var player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
        var connection = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
        var other = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
        field(EntityClientPlayerMP.class, "sendQueue").set(player, connection);
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        var projector = new ItemStack(new ItemNekoHologramProjector());
        var tag = new NBTTagCompound();
        tag.setString(
            "gtitHologramToolId",
            UUID.randomUUID()
                .toString());
        projector.setTagCompound(tag);
        player.inventory.mainInventory[0] = projector;

        var controller = new MTECokeOven("gtmb_stock_client_fixture");
        var controllerTile = (BaseMetaTileEntity) unsafe.allocateInstance(BaseMetaTileEntity.class);
        controllerTile.setWorldObj(world);
        controllerTile.yCoord = 64;
        controllerTile.setMetaTileID((short) 236);
        field(BaseMetaTileEntity.class, "mFacing").set(controllerTile, ForgeDirection.NORTH);
        controller.setBaseMetaTileEntity(controllerTile);
        int[] coords = new int[3];
        controller.getExtendedFacing()
            .getWorldOffset(new int[] { 1, 1, 2 }, coords);
        world.anchor = controllerTile;

        client.thePlayer = player;
        client.theWorld = world;
        var screen = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
        field(GuiScreen.class, "mc").set(screen, client);
        client.currentScreen = screen;
        var ui = new HologramUiModules(screen);
        screen.ui = ui;
        field(HologramScreen.class, "openedPlayer").set(screen, player);
        field(HologramScreen.class, "openingController").set(screen, controllerTile);
        field(HologramScreen.class, "openingConnection").set(screen, connection);
        field(HologramScreen.class, "openingTool").set(screen, projector);
        field(HologramScreen.class, "openingToolId").set(screen, tag.getString("gtitHologramToolId"));
        field(HologramScreen.class, "localControllerSide").set(screen, 2);
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setBoolean("localStandardController", true);
        metadata.setBoolean("supported", true);
        metadata.setBoolean("target", true);
        metadata.setBoolean("projectionDataComplete", true);
        metadata.setBoolean("nativeExecutionSupported", true);
        metadata.setString("nativeTargetDigest", String.join("", java.util.Collections.nCopies(64, "a")));
        metadata.setInteger("siteOccupied", 0);
        metadata.setInteger("siteMatchingSolid", 0);
        metadata.setInteger("main", 1);
        metadata.setInteger("hatchTierMax", 12);
        metadata.setInteger(
            "facing",
            controller.getExtendedFacing()
                .ordinal());
        var row = new NBTTagCompound();
        row.setInteger("x", coords[0]);
        row.setInteger("y", 64 + coords[1]);
        row.setInteger("z", coords[2]);
        row.setBoolean("inScope", true);
        row.setString("id", "minecraft:air");
        row.setString("wantId", "minecraft:stone");
        row.setTag("targetStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        var cells = new NBTTagList();
        cells.appendTag(row);
        metadata.setTag("cells", cells);
        var nativeState = screen.nativeControllerState();
        nativeState.targetReady();
        // This fixture starts with an already adopted, rendered target; subsequent PROBEs carry stage only.
        field(HologramScreen.class, "nativeSiteInitialized").setBoolean(screen, true);
        nativeState.observe(
            new HologramStockNativeWire.ServerObservation(
                236,
                0,
                64,
                0,
                HologramStockNativeWire.Stage.CONTROLLER_ONLY,
                nativeState.targetRevision(),
                ""));
        NBTTagCompound actionState = nativeState.metadata();
        for (Object key : actionState.func_150296_c()) metadata.setTag(
            (String) key,
            actionState.getTag((String) key)
                .copy());
        var displayed = new HologramState(metadata);
        field(HologramUiSession.class, "state").set(ui.session, displayed);
        field(HologramScreen.class, "localControllerBaseline").set(screen, displayed);
        field(HologramScreen.class, "nativeTargetSettings").set(screen, ui.config.configuration());
        ui.config.activateControl(94);
        SimpleAssert.that(
            !ui.config.basicPresetActionAvailable(),
            "an owned fresh target still requires an explicit preset tier");
        ui.config.activateControl(1700);
        for (int mode : new int[] { 0, 1, 2 }) {
            NBTTagCompound renderMode = new NBTTagCompound();
            renderMode.setInteger("mode", mode);
            field(HologramUiSession.class, "state").set(ui.session, displayed.withMetadata(renderMode));
            SimpleAssert.that(
                ui.config.basicPresetActionAvailable(),
                "native target configuration stays editable in render mode " + mode);
        }
        field(HologramUiSession.class, "state").set(ui.session, displayed);
        ui.config.activateControl(96);
        var presetConfiguration = new NBTTagCompound();
        presetConfiguration.setBoolean("presetActive", false);
        presetConfiguration.setInteger("hatchTier", 0);
        presetConfiguration.setBoolean("hatchDowngrade", true);
        presetConfiguration.setTag("hatchProfile", new NBTTagList());
        displayed = displayed.withMetadata(presetConfiguration);
        field(HologramUiSession.class, "state").set(ui.session, displayed);
        field(HologramScreen.class, "nativeTargetSettings").set(screen, ui.config.configuration());

        var networkChannel = field(HologramNetwork.class, "channel");
        Object previousChannel = networkChannel.get(null);
        var recorded = (RecordingWire) unsafe.allocateInstance(RecordingWire.class);
        recorded.sent = new ArrayList<>();
        var receiver = field(HologramNetwork.class, "stockNativeReplies");
        var previous = (HologramNetwork.StockNativeReplies) receiver.get(null);
        networkChannel.set(null, recorded);
        HologramNetwork.setStockNativeReplies(
            (reply, inbound) -> HologramClientStockNative.OWNER
                .receive(reply, inbound, HologramClientStockNative.context(client), System.nanoTime()));
        try {
            if (notificationsOnly) {
                nativeState.observe(
                    new HologramStockNativeWire.ServerObservation(
                        236,
                        0,
                        64,
                        0,
                        HologramStockNativeWire.Stage.FORMED,
                        nativeState.targetRevision(),
                        ""));
                displayed = displayed.withMetadata(nativeState.metadata());
                field(HologramUiSession.class, "state").set(ui.session, displayed);
                HologramControllerNotificationsRegression
                    .exercise(unsafe, client, world, screen, connection, other, displayed, recorded.sent);
                return;
            }
            verifyNativeRecoveryRouting(ui, screen, displayed, recorded, connection);
            SimpleAssert.that(
                ui.preview.activateControl(26) && ui.review.localFrame() != null
                    && ui.review.localFrame()
                        .scope()
                        .contains("当前完整结构")
                    && ui.review.localSubmitLabel()
                        .equals("确认建造")
                    && recorded.sent.isEmpty(),
                "controller-only review uses the full native target and remains local until the existing submit widget is clicked");
            SimpleAssert.that(
                !ui.review.localRecoveryAvailable(),
                "BUILD has no old blocks to recover and hides the recovery selector");
            SimpleAssert.that(
                !screen.reviewLocalConstruction(ui.config.configuration()) && recorded.sent.isEmpty(),
                "former direct/default scan/preflight cannot masquerade as visible confirmation");
            NBTTagCompound pending = new NBTTagCompound();
            pending.setBoolean("currentPlacementPending", true);
            field(HologramUiSession.class, "state").set(ui.session, displayed.withMetadata(pending));
            SimpleAssert.that(!ui.review.localSubmitAvailable(), "pending submission must be blocked");
            ui.review.localSubmitDenied();
            SimpleAssert.that(
                ui.review.localFrame()
                    .status()
                    .contains("等待") && recorded.sent.isEmpty(),
                "blocked confirm explains current request, no network bytes");
            field(HologramUiSession.class, "state").set(ui.session, displayed);
            ui.review.closeLocalMaterials();
            SimpleAssert.that(
                ui.review.openNativeLocalMaterials(),
                "reopen the official pane after the pending request clears");
            clickExistingSubmit(ui);
            SimpleAssert.that(
                recorded.sent.size() == 1 && command(recorded.sent.get(0)) == Command.BEGIN,
                "actual existing material button emits exactly one confirmed BEGIN");
            var projectionConfig = recorded.sent.get(0)
                .getCompoundTag("projectionConfiguration");
            SimpleAssert.eq(
                new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList("presetActive", "hatchTier", "hatchDowngrade", "hatchProfile")),
                projectionConfig.func_150296_c(),
                "real Screen confirmation sends only the four preset fields in the strict projection configuration");
            SimpleAssert.that(
                !projectionConfig.getBoolean("presetActive") && projectionConfig.hasKey("hatchTier", 3)
                    && projectionConfig.getInteger("hatchTier") == 0
                    && projectionConfig.getBoolean("hatchDowngrade")
                    && projectionConfig.hasKey("hatchProfile", 9),
                "false preset activation, explicit ULV and exact NBT types survive the real UI codec");
            SimpleAssert.that(
                ui.config.configuration()
                    .hasKey("scope")
                    && ui.config.configuration()
                        .hasKey("materialMain")
                    && recorded.sent.get(0)
                        .getInteger("main")
                        == ui.config.configuration()
                            .getInteger("main")
                    && recorded.sent.get(0)
                        .hasKey("projectionChannels", 10)
                    && recorded.sent.get(0)
                        .getIntArray("projectionHatches").length == 0,
                "UI admission and source settings stay outside preset configuration; no automatic hatches become explicit choices");
            SimpleAssert.that(
                client.currentScreen == screen && player.closedScreens == 0
                    && ui.review.localFrame() != null
                    && !ui.review.localFrame()
                        .status()
                        .isEmpty()
                    && screen.stockNativePending()
                    && !ui.review.localSubmitAvailable()
                    && ui.review.localSubmitBlocker()
                        .equals(HologramUiText.projectorOr("stock.await_result", "正在等候本次施工结果。")),
                "BEGIN waits for server acceptance with its pane visible and duplicate submission blocked");
            clickExistingSubmit(ui, false);
            SimpleAssert
                .eq(1, recorded.sent.size(), "a repeated actual footer click sends no duplicate confirmed BEGIN");
            var hudOnly = new NBTTagCompound();
            hudOnly.setString("status", "fixture HUD update");
            field(HologramUiSession.class, "state").set(ui.session, displayed.withMetadata(hudOnly));
            // Forge may publish another identical stack instance for the same slot/tool. Neither is a new authority.
            player.inventory.mainInventory[0] = projector.copy();
            SimpleAssert.eq(
                displayed.data.getLong("nativeTargetRevision"),
                ui.session.state().data.getLong("nativeTargetRevision"),
                "a HUD-only update preserves the frozen target revision");
            Key first = key(recorded.sent.get(0));
            deliver(new Reply(first, Phase.ACCEPTED, 0, ""), other);
            SimpleAssert.eq(0, player.closedScreens, "wrong inbound handler cannot accept the captured task");
            var generic = new NBTTagCompound();
            generic.setString(
                "placementRequestId",
                first.request()
                    .toString());
            generic.setBoolean("placementRequestAccepted", true);
            deliver(generic, connection);
            SimpleAssert.eq(1, recorded.sent.size(), "old generic replies never issue another command");
            deliver(new Reply(first, Phase.REFUSED, 0, "Fixture terminal"), connection);
            SimpleAssert.that(
                ui.review.localFrame()
                    .status()
                    .equals("Fixture terminal") && ui.review.localSubmitAvailable(),
                "exact refusal unlocks the material dialog");
            SimpleAssert.that(ui.preview.activateControl(26), "reopening remains local");
            clickExistingSubmit(ui);
            SimpleAssert.that(
                recorded.sent.size() == 2 && command(recorded.sent.get(1)) == Command.BEGIN,
                "each deliberate click submits one direct BEGIN");
            Key accepted = key(recorded.sent.get(1));
            client.currentScreen = new GuiScreen() {};
            deliver(new Reply(accepted, Phase.ACCEPTED, 0, ""), connection);
            SimpleAssert.that(
                client.currentScreen == null && player.closedScreens == 1 && screen.stockNativePending(),
                "server acceptance closes the replacement GUI and keeps the live task");
            HologramClientStockNative.OWNER.close(screen, HologramClientStockNative.context(client));
            deliver(new Reply(accepted, Phase.STOPPED, 0, ""), connection);
            client.currentScreen = screen;
            SimpleAssert.that(ui.preview.activateControl(26), "stopped workspace can be explicitly reopened");
            clickExistingSubmit(ui);
            SimpleAssert.that(
                recorded.sent.size() == 3 && command(recorded.sent.get(2)) == Command.BEGIN,
                "no generation or proof is needed for the next explicit request");
            HologramClientStockNative.OWNER.tick(screen, HologramClientStockNative.context(client), Long.MAX_VALUE);
            HologramClientStockNative.OWNER.tick(screen, HologramClientStockNative.context(client), Long.MAX_VALUE);
            HologramClientStockNative.OWNER.close(screen, HologramClientStockNative.context(client));
            SimpleAssert.that(
                recorded.sent.size() == 4 && command(recorded.sent.get(3)) == Command.STOP,
                "uncertain BEGIN issues at most one exact STOP and never retries BEGIN");
        } finally {
            HologramClientStockNative.OWNER.close(screen, HologramClientStockNative.context(client));
            HologramClientStockNative.OWNER.tick(null, null, System.nanoTime());
            HologramNetwork.setStockNativeReplies(previous);
            networkChannel.set(null, previousChannel);
            ui.review.closeLocalMaterials();
        }
        directLifecycleFlow();
        acceptedConstructionClosesOnceAndStillCancels();
    }

    private static void verifyNativeRecoveryRouting(HologramUiModules ui, HologramScreen screen,
        HologramState displayed, RecordingWire recorded, NetHandlerPlayClient connection)
        throws ReflectiveOperationException {
        var state = screen.nativeControllerState();
        for (var action : new HologramStockNativeWire.Operation[] { HologramStockNativeWire.Operation.REPLACE,
            HologramStockNativeWire.Operation.DEMOLISH }) {
            var stage = HologramStockNativeWire.Stage.FORMED;
            state.observe(
                new HologramStockNativeWire.ServerObservation(236, 0, 64, 0, stage, state.targetRevision(), ""));
            SimpleAssert.that(state.select(action), "matching observed stage admits recovery test action");
            field(HologramUiSession.class, "state").set(ui.session, displayed.withMetadata(state.metadata()));
            SimpleAssert.that(
                ui.review.openNativeLocalMaterials() && ui.review.localRecoveryAvailable(),
                "replacement and demolition expose recovery destination in their own current material pane");
            clickExistingRecovery(ui);
            SimpleAssert
                .eq(1, ui.review.nativeRecoveryDestination(), "real recovery widget chooses AE before confirmation");
            clickExistingSubmit(ui);
            SimpleAssert
                .eq(1, recorded.sent.size(), "one replacement/demolition footer click sends one confirmed BEGIN");
            var probe = recorded.sent.get(0);
            SimpleAssert.that(
                probe.getInteger("projectionOperation") == action.wireId() && probe.getInteger("destination") == 1
                    && (probe.getInteger("sources") & 32) == 32,
                "real confirmation freezes AE recovery in projection and source mask bit 5 for " + action);
            SimpleAssert
                .that(!ui.review.localRecoveryAvailable(), "pending confirmation locks the recovery destination");
            Key key = key(probe);
            deliver(new Reply(key, Phase.CAPABLE, 0, ""), connection);
            SimpleAssert.that(
                recorded.sent.size() == 1 && command(recorded.sent.get(0)) == Command.BEGIN,
                "read-only observations cannot issue another construction command");
            deliver(new Reply(key, Phase.REFUSED, 0, "Fixture recovery terminal"), connection);
            SimpleAssert.that(
                ui.review.localRecoveryAvailable() && !screen.stockNativePending(),
                "terminal refusal releases the same recovery selector");
            clickExistingRecovery(ui);
            SimpleAssert
                .eq(2, ui.review.nativeRecoveryDestination(), "recovery widget includes native backpack destination");
            clickExistingRecovery(ui);
            SimpleAssert.eq(0, ui.review.nativeRecoveryDestination(), "recovery widget restores inventory destination");
            ui.review.closeLocalMaterials();
            recorded.sent.clear();
        }
        state.observe(
            new HologramStockNativeWire.ServerObservation(
                236,
                0,
                64,
                0,
                HologramStockNativeWire.Stage.CONTROLLER_ONLY,
                state.targetRevision(),
                ""));
        field(HologramUiSession.class, "state").set(ui.session, displayed.withMetadata(state.metadata()));
    }

    private static void clickExistingRecovery(HologramUiModules ui) throws ReflectiveOperationException {
        var dialog = new HologramMaterialConfirmationDialog(
            new ModularPanel("gtmb_stock_recovery_fixture"),
            ui,
            ui.review);
        var build = HologramMaterialConfirmationDialog.class.getDeclaredMethod("build");
        build.setAccessible(true);
        build.invoke(dialog);
        var widgets = (Map<?, ?>) field(HologramMaterialConfirmationDialog.class, "controlWidgets").get(dialog);
        var recovery = (ButtonWidget<?>) widgets.get(252);
        SimpleAssert.that(recovery != null, "recovery destination is the real control 252");
        recovery.playClickSound(false);
        recovery.onUpdate();
        SimpleAssert
            .eq(Interactable.Result.SUCCESS, recovery.onMousePressed(0), "real recovery control handles the click");
    }

    public static void acceptedConstructionClosesOnceAndStillCancels() {
        var sent = new ArrayList<Command>();
        var flow = flow(sent, true);
        Object handler = new Object();
        var context = new HologramClientStockNative.Context(handler, new Object(), new Object(), 0);
        boolean[] visible = { true };
        int[] exits = { 0 };
        var receipts = new ArrayList<Reply>();
        var owner = new HologramClientStockNative.Owner(
            new Object(),
            () -> true,
            () -> visible[0],
            text -> {},
            receipts::add,
            () -> true,
            () -> {
                exits[0]++;
                visible[0] = false;
            });
        var confirmation = flowIntent(1);
        SimpleAssert.that(
            flow.submit(confirmation, context, owner, 0) && exits[0] == 0,
            "submit is one BEGIN; GUI remains until authoritative acceptance");
        flow.receive(new Reply(confirmation.key(), Phase.CAPABLE, 0, ""), handler, context, 1);
        SimpleAssert.eq(0, exits[0], "read-only capability does not accept a construction");
        flow.receive(new Reply(confirmation.key(), Phase.ACCEPTED, 0, ""), handler, context, 2);
        flow.close(owner.view(), context);
        flow.receive(new Reply(confirmation.key(), Phase.ACCEPTED, 0, ""), handler, context, 3);
        flow.receive(new Reply(confirmation.key(), Phase.ACTIVE, 1, ""), handler, context, 4);
        SimpleAssert
            .that(exits[0] == 1 && flow.pendingHere(context), "acceptance exits exactly once and retains ownership");
        SimpleAssert.that(flow.cancelHere(context, 5), "detached construction can be cancelled");
        flow.receive(new Reply(confirmation.key(), Phase.STOPPED, 1, "Cancelled"), handler, context, 6);
        flow.receive(new Reply(confirmation.key(), Phase.STOPPED, 1, "Cancelled"), handler, context, 7);
        SimpleAssert.that(
            !flow.pendingHere(context) && receipts.size() == 1 && exits[0] == 1,
            "one terminal callback retires the job without reopening the GUI");
        SimpleAssert.eq(
            java.util.Arrays.asList(Command.BEGIN, Command.STOP),
            sent,
            "one explicit BEGIN and one exact STOP; no cold handshake");
    }

    private static HologramClientStockNative.Flow flow(List<Command> sent, boolean sends) {
        return new HologramClientStockNative.Flow(new HologramClientStockNative.Wire() {

            public boolean begin(Confirmation value) {
                sent.add(Command.BEGIN);
                return sends;
            }

            public boolean stop(Key key) {
                sent.add(Command.STOP);
                return true;
            }
        });
    }

    private static void directLifecycleFlow() {
        Object handler = new Object();
        var context = new HologramClientStockNative.Context(handler, new Object(), new Object(), 0);
        var commands = new ArrayList<Command>();
        var flow = flow(commands, true);
        var terminals = new ArrayList<Reply>();
        var owner = new HologramClientStockNative.Owner(
            new Object(),
            () -> true,
            () -> false,
            text -> {},
            terminals::add);
        var confirmation = flowIntent(2);
        SimpleAssert.that(flow.submit(confirmation, context, owner, 0), "explicit request is submitted directly");
        SimpleAssert.that(
            !flow.submit(flowIntent(3), context, owner, 1),
            "same client owner cannot start a concurrent request");
        flow.close(owner.view(), context);
        flow.receive(new Reply(confirmation.key(), Phase.COMPLETE, 1, ""), new Object(), context, 2);
        SimpleAssert.that(flow.pendingHere(context), "wrong connection cannot finish detached work");
        flow.receive(
            new Reply(
                new Key(
                    UUID.randomUUID(),
                    confirmation.key()
                        .sequence()),
                Phase.COMPLETE,
                1,
                ""),
            handler,
            context,
            3);
        SimpleAssert.that(flow.pendingHere(context), "another request UUID cannot finish detached work");
        flow.receive(new Reply(confirmation.key(), Phase.COMPLETE, 1, ""), handler, context, 4);
        SimpleAssert
            .that(!flow.pendingHere(context) && terminals.size() == 1, "detached task receives its exact result");
        SimpleAssert
            .that(flow.submit(flowIntent(4), context, owner, 5), "new explicit request after terminal is allowed");
        flow.tick(owner.view(), context, Long.MAX_VALUE);
        flow.tick(owner.view(), context, Long.MAX_VALUE);
        SimpleAssert.eq(
            java.util.Arrays.asList(Command.BEGIN, Command.BEGIN, Command.STOP),
            commands,
            "lost terminal sends one STOP without retrying construction");
        var changed = new HologramClientStockNative.Context(new Object(), context.world(), context.player(), 0);
        SimpleAssert.that(!flow.pendingHere(changed), "connection change retires local ownership");
        var unsentCommands = new ArrayList<Command>();
        var unsent = flow(unsentCommands, false);
        SimpleAssert.that(!unsent.submit(flowIntent(5), context, owner, 0), "proven pre-send failure is not admitted");
        unsent.tick(owner.view(), context, Long.MAX_VALUE);
        SimpleAssert.eq(
            java.util.Collections.singletonList(Command.BEGIN),
            unsentCommands,
            "unsent requests never issue STOP or automatic retries");
    }

    private static Confirmation flowIntent(long sequence) {
        return Confirmation.projection(
            new Key(UUID.randomUUID(), sequence),
            0,
            UUID.randomUUID(),
            0,
            64,
            0,
            2,
            0,
            0,
            1,
            false,
            false,
            1,
            new HologramStockNativeWire.Projection(
                236,
                HologramStockNativeWire.Operation.BUILD,
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyList(),
                0,
                sequence).withNativeTargetDigest(String.join("", java.util.Collections.nCopies(64, "a"))));
    }

    private static void clickExistingSubmit(HologramUiModules ui) throws ReflectiveOperationException {
        clickExistingSubmit(ui, true);
    }

    private static void clickExistingSubmit(HologramUiModules ui, boolean expectedAvailable)
        throws ReflectiveOperationException {
        SimpleAssert.eq(expectedAvailable, ui.review.localSubmitAvailable(), "exact local pane submit availability");
        var dialog = new HologramMaterialConfirmationDialog(
            new ModularPanel("gtmb_stock_button_fixture"),
            ui,
            ui.review);
        var build = HologramMaterialConfirmationDialog.class.getDeclaredMethod("build");
        build.setAccessible(true);
        ModularPanel panel = (ModularPanel) build.invoke(dialog);
        // controlEnabled is the live panel/drawn-frame probe; a detached tree must not report that it was shown.
        SimpleAssert.that(!dialog.controlEnabled(55), "headless material tree is neither opened nor drawn");
        var enabled = (Map<?, ?>) field(HologramMaterialConfirmationDialog.class, "enabledControls").get(dialog);
        SimpleAssert.that(
            enabled.get(55) instanceof BooleanSupplier available && available.getAsBoolean() == expectedAvailable,
            "real footer registered the current admission predicate for default control 55");
        SimpleAssert.that(
            !enabled.containsKey(248),
            "real native material footer no longer mounts an old compatibility button");
        ButtonWidget<?> submit = null;
        for (var child : panel.getChildren()) {
            if (child instanceof ButtonWidget<?>button && button.getOverlay() instanceof IKey label
                && label.get()
                    .equals(ui.review.localSubmitLabel())) {
                SimpleAssert.that(submit == null, "exactly one actual material widget has the current submit label");
                submit = button;
            }
        }
        SimpleAssert.that(submit != null, "default SUBMIT is a real footer widget, not a direct test action");
        submit.playClickSound(false);
        submit.onUpdate(); // Apply the production widget's live enabled predicate before the headless callback.
        SimpleAssert
            .eq(Interactable.Result.SUCCESS, submit.onMousePressed(0), "actual primary button callback handled");
    }

    private static void deliver(Reply reply, NetHandlerPlayClient handler) throws ReflectiveOperationException {
        var encode = HologramStockNativeWire.class.getDeclaredMethod("writeReply", Reply.class);
        encode.setAccessible(true);
        deliver((NBTTagCompound) encode.invoke(null, reply), handler);
    }

    private static void deliver(NBTTagCompound tag, NetHandlerPlayClient handler) throws ReflectiveOperationException {
        var packetType = HologramNetwork.State.class.getDeclaredConstructor(NBTTagCompound.class);
        packetType.setAccessible(true);
        var context = MessageContext.class.getDeclaredConstructor(INetHandler.class, Side.class);
        context.setAccessible(true);
        var payload = Unpooled.buffer();
        try {
            packetType.newInstance(tag)
                .toBytes(payload);
            var decoded = new HologramNetwork.State();
            decoded.fromBytes(payload);
            new HologramNetwork.ClientHandler().onMessage(decoded, context.newInstance(handler, Side.CLIENT));
        } finally {
            payload.release();
        }
    }

    private static Key key(NBTTagCompound wire) {
        return new Key(UUID.fromString(wire.getString("nativeStockRequest")), wire.getLong("nativeStockIntent"));
    }

    private static Command command(NBTTagCompound wire) {
        SimpleAssert.eq("serverRules", wire.getString("action"), "old-peer inert Action carrier");
        return Command.values()[wire.getByte("nativeStockCommand")];
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class RecordingWire extends SimpleNetworkWrapper {

        List<NBTTagCompound> sent;

        RecordingWire() {
            super("gtmb_stock_client_fixture");
        }

        @Override
        public void sendToServer(IMessage message) {
            SimpleAssert
                .that(message instanceof HologramNetwork.Action, "no new opaque discriminator for older servers");
            var data = Unpooled.buffer();
            try {
                message.toBytes(data);
                SimpleAssert.that(data.readableBytes() <= 512, "actual confirmed client message is bounded");
                var decoded = new HologramNetwork.Action();
                decoded.fromBytes(data);
                var tag = (NBTTagCompound) field(HologramNetwork.Packet.class, "tag").get(decoded);
                var read = HologramStockNativeWire.class.getDeclaredMethod("readRequest", NBTTagCompound.class);
                read.setAccessible(true);
                SimpleAssert.that(
                    read.invoke(null, tag) != null,
                    "actual Screen confirmation passes the unchanged native ingress decoder after the real Forge codec");
                SimpleAssert
                    .that(!tag.hasKey("cells") && !tag.hasKey("geometry"), "no client geometry in stock messages");
                sent.add(tag);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                data.release();
            }
        }
    }

    private static final class CokeWorld extends WorldClient {

        TileEntity anchor;

        CokeWorld() {
            super(null, null, 0, null, null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (anchor.xCoord == x && anchor.yCoord == y && anchor.zCoord == z) return anchor;
            return null;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return getTileEntity(x, y, z) == null ? Blocks.air : GregTechAPI.sBlockMachines;
        }
    }
}
