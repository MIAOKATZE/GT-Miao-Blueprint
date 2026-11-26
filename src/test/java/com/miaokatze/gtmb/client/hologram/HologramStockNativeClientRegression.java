// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchCokeOven;
import gregtech.common.tileentities.machines.multi.MTECokeOven;
import io.netty.buffer.Unpooled;

/** Existing material control and button 55, real Forge codecs/receiver, isolated in-memory client owner. */
public final class HologramStockNativeClientRegression {

    private HologramStockNativeClientRegression() {}

    public static void run() throws ReflectiveOperationException {
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
            runPanel(unsafe, client);
        } finally {
            GregTechAPI.sBlockCasings12 = savedCasing;
            GregTechAPI.sBlockMachines = savedMachines;
            instance.set(null, previousClient);
        }
    }

    private static void runPanel(sun.misc.Unsafe unsafe, Minecraft client) throws ReflectiveOperationException {
        var world = (CokeWorld) unsafe.allocateInstance(CokeWorld.class);
        var player = (EntityClientPlayerMP) unsafe.allocateInstance(EntityClientPlayerMP.class);
        var connection = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
        var other = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
        field(EntityClientPlayerMP.class, "sendQueue").set(player, connection);
        player.inventory = new InventoryPlayer(player);
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
        var hatchTile = (BaseMetaTileEntity) unsafe.allocateInstance(BaseMetaTileEntity.class);
        var hatch = (MTEHatchCokeOven) unsafe.allocateInstance(MTEHatchCokeOven.class);
        controllerTile.setWorldObj(world);
        controllerTile.yCoord = 64;
        controllerTile.setMetaTileID((short) 236);
        field(BaseMetaTileEntity.class, "mFacing").set(controllerTile, ForgeDirection.NORTH);
        controller.setBaseMetaTileEntity(controllerTile);
        int[] coords = new int[3];
        controller.getExtendedFacing()
            .getWorldOffset(new int[] { 1, 1, 2 }, coords);
        hatchTile.setWorldObj(world);
        hatchTile.xCoord = coords[0];
        hatchTile.yCoord = 64 + coords[1];
        hatchTile.zCoord = coords[2];
        hatchTile.setMetaTileID((short) 237);
        field(BaseMetaTileEntity.class, "mFacing").set(hatchTile, ForgeDirection.WEST);
        hatch.setBaseMetaTileEntity(hatchTile);
        world.anchor = controllerTile;
        world.hatch = hatchTile;

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
        field(HologramScreen.class, "localNativeChoices").set(screen, new LinkedHashSet<Integer>());
        field(HologramScreen.class, "localPresetChoices").set(screen, new LinkedHashSet<Integer>());
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setBoolean("localStandardController", true);
        metadata.setBoolean("supported", true);
        metadata.setBoolean("target", true);
        metadata.setBoolean("projectionDataComplete", true);
        metadata.setInteger("siteOccupied", 0);
        metadata.setInteger("siteMatchingSolid", 0);
        metadata.setInteger("main", 1);
        metadata.setInteger(
            "facing",
            controller.getExtendedFacing()
                .ordinal());
        var row = new NBTTagCompound();
        row.setInteger("x", coords[0]);
        row.setInteger("y", 64 + coords[1]);
        row.setInteger("z", coords[2]);
        row.setBoolean("inScope", true);
        row.setString("wantId", "minecraft:stone");
        var cells = new NBTTagList();
        cells.appendTag(row);
        metadata.setTag("cells", cells);
        var displayed = new HologramState(metadata);
        field(HologramUiSession.class, "state").set(ui.session, displayed);
        field(HologramScreen.class, "localControllerBaseline").set(screen, displayed);

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
            SimpleAssert.that(
                ui.preview.activateControl(26) && ui.review.localFrame() != null
                    && ui.review.localFrame()
                        .scope()
                        .contains("现场")
                    && ui.review.localSubmitLabel()
                        .equals("确认新建")
                    && recorded.sent.isEmpty(),
                "bare-site confirmation uses the declaration and remains local, without calling the retired coke profile");
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
                recorded.sent.size() == 1 && command(recorded.sent.get(0)) == Command.PROBE,
                "actual existing material button emits exactly one cold proof request and no eager BEGIN");
            SimpleAssert.that(
                ui.review.localFrame()
                    .status()
                    .contains("等待服务器确认") && !ui.review.localSubmitAvailable(),
                "the clicked pane remains visible and prevents duplicate send while pending");
            var hudOnly = new NBTTagCompound();
            hudOnly.setString("status", "fixture HUD update");
            field(HologramUiSession.class, "state").set(ui.session, displayed.withMetadata(hudOnly));
            // Forge may publish another identical stack instance for the same slot/tool. Neither is a new authority.
            player.inventory.mainInventory[0] = projector.copy();
            var changed = new NBTTagCompound();
            changed.setLong("planRevision", displayed.data.getLong("planRevision") + 1);
            SimpleAssert.that(
                HologramScreen.sameNativeReview(displayed, ui.session.state())
                    && !HologramScreen.sameNativeReview(displayed, displayed.withMetadata(changed))
                    && !HologramScreen.sameNativeReview(displayed, new HologramState(metadata)),
                "HUD-only state is admissible; different plan revision or new geometry is not");
            Key cold = key(recorded.sent.get(0), 71);
            var proof = new Reply(cold, Phase.CAPABLE, 0, "");
            deliver(proof, other);
            SimpleAssert.eq(1, recorded.sent.size(), "wrong inbound client handler cannot unlock the captured click");
            var generic = new NBTTagCompound();
            generic.setString(
                "placementRequestId",
                cold.request()
                    .toString());
            generic.setBoolean("placementRequestAccepted", true);
            deliver(generic, connection);
            SimpleAssert.that(
                recorded.sent.size() == 1,
                "old generic placement/material reply does not authorize the new native attempt");
            deliver(proof, connection);
            deliver(proof, connection);
            SimpleAssert.that(
                recorded.sent.size() == 2 && command(recorded.sent.get(1)) == Command.BEGIN_AFTER_PROBE
                    && key(recorded.sent.get(1), 71).equals(cold),
                "one matching nonce emits one unchanged cold BEGIN; no duplicate");
            deliver(new Reply(cold, Phase.REFUSED, 0, "Fixture terminal"), connection);
            SimpleAssert.that(
                ui.review.localFrame()
                    .status()
                    .equals("Fixture terminal") && ui.review.localSubmitAvailable(),
                "a refusal arriving over the real reply codec is visible in the exact material dialog and can be retried");
            SimpleAssert.that(ui.preview.activateControl(26) && recorded.sent.size() == 2, "reopening remains local");
            clickExistingSubmit(ui);
            SimpleAssert.that(
                recorded.sent.size() == 3 && command(recorded.sent.get(2)) == Command.PROBE,
                "CAPABLE plus a rejected cold submission does not pre-arm the next deliberate click");
            Key accepted = key(recorded.sent.get(2), 71);
            deliver(new Reply(accepted, Phase.CAPABLE, 0, ""), connection);
            SimpleAssert.that(
                recorded.sent.size() == 4 && command(recorded.sent.get(3)) == Command.BEGIN_AFTER_PROBE,
                "second deliberate click is independently confirmed once");
            deliver(new Reply(accepted, Phase.ACCEPTED, 0, ""), connection);
            deliver(new Reply(accepted, Phase.STOPPED, 0, ""), connection);
            SimpleAssert
                .that(ui.preview.activateControl(26) && recorded.sent.size() == 4, "warm reopening remains local");
            clickExistingSubmit(ui);
            SimpleAssert.that(
                recorded.sent.size() == 5 && command(recorded.sent.get(4)) == Command.BEGIN
                    && recorded.sent.get(4)
                        .getLong("nativeStockGeneration") == 71,
                "authoritatively admitted cold action warms the exact live owner, then one direct BEGIN");
            HologramClientStockNative.OWNER.tick(screen, HologramClientStockNative.context(client), Long.MAX_VALUE);
            HologramClientStockNative.OWNER.tick(screen, HologramClientStockNative.context(client), Long.MAX_VALUE);
            HologramClientStockNative.OWNER.close(screen, HologramClientStockNative.context(client));
            SimpleAssert.that(
                recorded.sent.size() == 6 && command(recorded.sent.get(5)) == Command.STOP
                    && ui.session.notice()
                        .contains("未确认停工"),
                "uncertain actual BEGIN issues at most one exact STOP, no retry");
        } finally {
            HologramClientStockNative.OWNER.close(screen, HologramClientStockNative.context(client));
            HologramClientStockNative.OWNER.tick(null, null, System.nanoTime());
            HologramNetwork.setStockNativeReplies(previous);
            networkChannel.set(null, previousChannel);
            ui.review.closeLocalMaterials();
        }
        staleProofFlow();
        lostAndUnsentFlow();
        detachedPlacementFlow();
    }

    private static void detachedPlacementFlow() {
        var sent = new ArrayList<Command>();
        var flow = new HologramClientStockNative.Flow(new HologramClientStockNative.Wire() {

            @Override
            public boolean probe(Confirmation value) {
                sent.add(Command.PROBE);
                return true;
            }

            @Override
            public boolean begin(Confirmation value, boolean cold) {
                sent.add(cold ? Command.BEGIN_AFTER_PROBE : Command.BEGIN);
                return true;
            }

            @Override
            public boolean stop(Key key) {
                sent.add(Command.STOP);
                return true;
            }
        });
        var handler = new Object();
        var scope = new HologramClientStockNative.Context(handler, new Object(), new Object(), 0);
        boolean[] open = { true };
        var owner = new HologramClientStockNative.Owner(
            new Object(),
            () -> open[0],
            () -> open[0],
            notice -> {},
            reply -> { if (!open[0]) throw new AssertionError("Disposed screen received a callback"); },
            () -> true);
        var first = flowIntent(1);
        SimpleAssert.that(flow.submit(first, scope, owner, 0), "submit cold construction");
        open[0] = false;
        flow.close(owner.view(), scope);
        SimpleAssert.that(flow.pendingHere(scope), "closing before capability does not cancel the frozen intent");
        var key = first.forGeneration(32)
            .key();
        flow.receive(new Reply(key, Phase.CAPABLE, 0, ""), handler, scope, 1);
        flow.close(owner.view(), scope);
        flow.receive(new Reply(key, Phase.ACTIVE, 2, ""), handler, scope, 2);
        SimpleAssert.that(
            flow.worldStatus(scope, 2)
                .contains("2"),
            "detached construction exposes committed progress");
        flow.receive(new Reply(key, Phase.COMPLETE, 6, ""), handler, scope, 3);
        SimpleAssert
            .eq(java.util.Arrays.asList(Command.PROBE, Command.BEGIN_AFTER_PROBE), sent, "close never sends STOP");
        SimpleAssert.that(
            !flow.pendingHere(scope) && flow.worldStatus(scope, 3)
                .contains("6"),
            "world HUD sees completion");
        open[0] = true;
        var second = flowIntent(2);
        SimpleAssert.that(flow.submit(second, scope, owner, 4), "another user confirmation warms same context");
        open[0] = false;
        flow.close(owner.view(), scope);
        SimpleAssert
            .that(flow.cancelHere(scope, 5) && !flow.cancelHere(scope, 6), "explicit cancellation sends one STOP");
        SimpleAssert.eq(Command.STOP, sent.get(sent.size() - 1), "only explicit cancellation stops this submission");
        flow.receive(
            new Reply(
                second.forGeneration(32)
                    .key(),
                Phase.STOPPED,
                0,
                ""),
            handler,
            scope,
            7);
    }

    private static void staleProofFlow() {
        var sent = new ArrayList<Command>();
        var flow = new HologramClientStockNative.Flow(new HologramClientStockNative.Wire() {

            @Override
            public boolean probe(Confirmation value) {
                sent.add(Command.PROBE);
                return true;
            }

            @Override
            public boolean begin(Confirmation value, boolean cold) {
                sent.add(cold ? Command.BEGIN_AFTER_PROBE : Command.BEGIN);
                return true;
            }

            @Override
            public boolean stop(Key key) {
                sent.add(Command.STOP);
                return true;
            }
        });
        var sender = new Object();
        var scope = new HologramClientStockNative.Context(sender, new Object(), new Object(), 0);
        var nonce = new Key(UUID.randomUUID(), 1, 0);
        var exact = new Confirmation(nonce, 0, UUID.randomUUID(), 0, 64, 0, 2, 0, 0, 1, false, false, 0, 1, 1, 2, 4);
        boolean[] current = { true };
        var owner = new HologramClientStockNative.Owner(
            new Object(),
            () -> current[0],
            () -> true,
            text -> {},
            () -> {});
        SimpleAssert.that(flow.submit(exact, scope, owner, 0), "genuine first local action starts cold proof");
        current[0] = false;
        flow.receive(
            new Reply(
                exact.forGeneration(42)
                    .key(),
                Phase.CAPABLE,
                0,
                ""),
            sender,
            scope,
            1);
        flow.tick(owner.view(), scope, Long.MAX_VALUE);
        SimpleAssert.eq(
            java.util.Collections.singletonList(Command.PROBE),
            sent,
            "lost original panel/selection/tool proof never sends BEGIN or STOP, and never auto-retries");
    }

    private static void lostAndUnsentFlow() {
        var sent = new ArrayList<Command>();
        var flow = new HologramClientStockNative.Flow(new HologramClientStockNative.Wire() {

            @Override
            public boolean probe(Confirmation value) {
                sent.add(Command.PROBE);
                return true;
            }

            @Override
            public boolean begin(Confirmation value, boolean cold) {
                sent.add(cold ? Command.BEGIN_AFTER_PROBE : Command.BEGIN);
                return false; // Proven pre-send transport failure; never arm the current scope.
            }

            @Override
            public boolean stop(Key key) {
                throw new AssertionError("A lost/unsent cold proof never issues STOP");
            }
        });
        var sender = new Object();
        var scope = new HologramClientStockNative.Context(sender, new Object(), new Object(), 0);
        var owner = new HologramClientStockNative.Owner(new Object(), () -> true, () -> true, text -> {}, () -> {});
        var first = flowIntent(1);
        var second = flowIntent(2);
        var third = flowIntent(3);
        SimpleAssert.that(flow.submit(first, scope, owner, 0), "true click submits one cold probe");
        flow.tick(owner.view(), scope, HologramClientStockNative.FIRST_PROOF_NANOS + 1);
        SimpleAssert.eq(java.util.Collections.singletonList(Command.PROBE), sent, "lost proof never auto-retries");
        SimpleAssert.that(
            flow.submit(second, scope, owner, HologramClientStockNative.FIRST_PROOF_NANOS + 2),
            "new explicit click is still cold");
        flow.receive(
            new Reply(
                second.forGeneration(42)
                    .key(),
                Phase.CAPABLE,
                0,
                ""),
            sender,
            scope,
            HologramClientStockNative.FIRST_PROOF_NANOS + 3);
        SimpleAssert.that(
            flow.submit(third, scope, owner, HologramClientStockNative.FIRST_PROOF_NANOS + 4),
            "an unsent cold construction has not warmed this generation");
        SimpleAssert.eq(
            java.util.Arrays.asList(Command.PROBE, Command.PROBE, Command.BEGIN_AFTER_PROBE, Command.PROBE),
            sent,
            "no placement, retry, stop or warm BEGIN from a lost or locally unsent first action");
    }

    private static Confirmation flowIntent(long sequence) {
        return new Confirmation(
            new Key(UUID.randomUUID(), sequence, 0),
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
            0,
            1,
            1,
            2,
            4);
    }

    private static void clickExistingSubmit(HologramUiModules ui) throws ReflectiveOperationException {
        SimpleAssert.that(
            ui.review.localSubmitAvailable(),
            "existing real default SUBMIT is available for this exact local pane");
        var dialog = new HologramMaterialConfirmationDialog(new ModularPanel("gtmb_stock_button_fixture"), ui.review);
        var build = HologramMaterialConfirmationDialog.class.getDeclaredMethod("build");
        build.setAccessible(true);
        ModularPanel panel = (ModularPanel) build.invoke(dialog);
        // controlEnabled is the live panel/drawn-frame probe; a detached tree must not report that it was shown.
        SimpleAssert.that(!dialog.controlEnabled(55), "headless material tree is neither opened nor drawn");
        var enabled = (Map<?, ?>) field(HologramMaterialConfirmationDialog.class, "enabledControls").get(dialog);
        SimpleAssert.that(
            enabled.get(55) instanceof BooleanSupplier available && available.getAsBoolean(),
            "real footer registered the enabled predicate for default control 55");
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

    private static Key key(NBTTagCompound wire, long generation) {
        return new Key(
            UUID.fromString(wire.getString("nativeStockRequest")),
            wire.getLong("nativeStockIntent"),
            generation);
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

        TileEntity anchor, hatch;

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
            return hatch.xCoord == x && hatch.yCoord == y && hatch.zCoord == z ? hatch : null;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return getTileEntity(x, y, z) == null ? Blocks.air : GregTechAPI.sBlockMachines;
        }
    }
}
