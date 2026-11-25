// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalFrame;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalView;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;

/** Exercises the actual client quote consumer with wire identities and counts that broke the old material list. */
public final class HologramMaterialReviewRegression {

    private HologramMaterialReviewRegression() {}

    public static void run() {
        NBTTagCompound state = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        ItemStack tagged = new ItemStack(Items.diamond, 1, 7);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("displayVariant", "first");
        tagged.setTagCompound(tag);
        NBTTagCompound first = HologramMaterialQuote.itemRow(tagged, 4294967301L, 2147483649L);
        // Deliberately contradict the process-local native ID. The stable registry identity must win.
        first.getCompoundTag("item")
            .setShort("id", (short) net.minecraft.item.Item.getIdFromItem(Items.stick));
        rows.appendTag(first);
        tagged.getTagCompound()
            .setString("displayVariant", "second");
        rows.appendTag(HologramMaterialQuote.itemRow(tagged, 2, 1));
        NBTTagCompound unknown = (NBTTagCompound) first.copy();
        unknown.setString("itemRegistry", "gtmb_regression:missing_registry_item");
        rows.appendTag(unknown);
        NBTTagCompound energy = new NBTTagCompound();
        energy.setString("kind", "energy");
        energy.setLong("required", Long.MAX_VALUE);
        energy.setString("requiredExact", "123456789012345678901234567890");
        rows.appendTag(energy);
        NBTTagCompound quote = new NBTTagCompound();
        quote.setBoolean("complete", true);
        quote.setTag("rows", rows);
        state.setTag("materialQuote", quote);

        HologramMaterialReview model = new HologramMaterialReview();
        SimpleAssert.that(model.update(state) && model.ready() && model.size() == 4, "consumer retains exact rows");
        HologramMaterialReview.Row a = model.row(0), b = model.row(1);
        SimpleAssert.that(
            a.item.getItem() == Items.diamond && a.item.getItemDamage() == 7,
            "registry and damage determine the visible icon, ignoring an unrelated numeric id");
        SimpleAssert.that(
            "4294967301".equals(a.required) && "2147483649".equals(a.available) && "2147483652".equals(a.missing),
            "all visible material counts preserve values above signed int");
        SimpleAssert.that(
            "first".equals(
                a.item.getTagCompound()
                    .getString("displayVariant"))
                && "second".equals(
                    b.item.getTagCompound()
                        .getString("displayVariant"))
                && a.index != b.index,
            "same-name items with different complete NBT stay independently inspectable");
        a.item.getTagCompound()
            .setString("displayVariant", "client-only");
        SimpleAssert.that(
            "first".equals(
                first.getCompoundTag("item")
                    .getCompoundTag("tag")
                    .getString("displayVariant")),
            "render samples are detached from the authoritative quote");
        SimpleAssert
            .that(model.row(2).item == null, "unavailable registry never falls back to a misleading numeric icon");
        SimpleAssert.that(
            "123456789012345678901234567890".equals(model.row(3).required),
            "energy display uses exact decimal quantities beyond long");
        SimpleAssert.that(
            !model.update(state) && model.row(0) == a,
            "an unchanged snapshot reuses decoded samples across rendered frames");
        // Restore the detached render sample before checking reuse across a separate wire quote.
        a.item.getTagCompound()
            .setString("displayVariant", "first");
        NBTTagCompound projected = (NBTTagCompound) quote.copy();
        projected.getTagList("rows", 10)
            .getCompoundTagAt(1)
            .setLong("available", 2);
        projected.getTagList("rows", 10)
            .getCompoundTagAt(1)
            .setLong("missing", 0);
        state.setTag("materialQuote", projected);
        SimpleAssert.that(
            !model.update(state) && model.row(0) == a && model.row(1) != b,
            "a voucher projection keeps list geometry and unaffected decoded rows while updating changed quantities");
        SimpleAssert.that("0".equals(model.row(1).missing), "projected shortage updates without rebuilding the list");
        state.setTag("materialQuote", quote);
        quote.setLong("planRevision", 3);
        state.setLong("planRevision", 4);
        model.update(state);
        SimpleAssert.that(!model.ready(), "stale material quote cannot enable the new transaction button");
        metadataFence();
        contextAndUnchangedChoices();
        incompleteEstimateCannotEnableConfirmation();
        detachedBackpackEstimate();
        detachedNativeScope();
        staleNativeOwner();
        graphlessLocalViewControls();
        explicitLocalPaymentChoice();
    }

    private static void explicitLocalPaymentChoice() {
        NBTTagCompound original = new NBTTagCompound();
        original.setInteger("placementX", 17);
        var source = new HologramLocalPlacement.Submission(null, null, 42, original);
        var selected = HologramLibraryMaterialReviewScreen.paymentChoice(source, true);
        var plain = HologramLibraryMaterialReviewScreen.paymentChoice(source, false);
        SimpleAssert.that(selected.intent().getBoolean("voucherRequested")
            && !plain.intent().getBoolean("voucherRequested") && !original.hasKey("voucherRequested")
            && !source.intent().hasKey("voucherRequested")
            && selected.lockGeneration() == source.lockGeneration()
            && selected.intent().getInteger("placementX") == 17,
            "a local voucher choice is explicit and preserves exact frozen source/placement without mutating either");
        SimpleAssert.that(HologramLibraryMaterialReviewScreen.paymentChoice(null, true) == null,
            "expired local tickets cannot create a new payment intent");
    }

    private static void detachedBackpackEstimate() {
        ItemStack first = new ItemStack(Items.diamond, 3, 7);
        ItemStack second = new ItemStack(Items.diamond, 9, 7);
        first.setTagCompound(new NBTTagCompound());
        first.getTagCompound()
            .setString("variant", "first");
        second.setTagCompound(new NBTTagCompound());
        second.getTagCompound()
            .setString("variant", "second");
        ItemStack protectedStack = first.copy();
        protectedStack.stackSize = 30;
        NBTTagList demand = new NBTTagList();
        demand.appendTag(HologramMaterialQuote.itemRow(first, 8L, 0L));
        demand.appendTag(HologramMaterialQuote.itemRow(second, 8L, 0L));
        NBTTagCompound resource = new NBTTagCompound();
        resource.setString("kind", "energy");
        resource.setString("requiredExact", "987654321123456789123456789");
        demand.appendTag(resource);
        NBTTagCompound theory = new NBTTagCompound();
        theory.setBoolean("complete", true);
        theory.setTag("rows", demand);

        HologramMaterialReview model = new HologramMaterialReview();
        NBTTagCompound visible = HologramLocalMaterialReview
            .stockRows(theory, new ItemStack[] { first, second, protectedStack }, 2);
        model.updateLocal(visible);
        SimpleAssert.that(
            "3".equals(model.row(0).available) && "5".equals(model.row(0).missing)
                && "9".equals(model.row(1).available)
                && "0".equals(model.row(1).missing),
            "detached backpack uses exact NBT, excludes protected slot, and reports only a backpack delta");
        SimpleAssert.that(
            "987654321123456789123456789".equals(model.row(2).required) && "未知".equals(model.row(2).available)
                && "未知".equals(model.row(2).missing)
                && !model.row(2).shortage,
            "an unobserved resource is neither zero nor a definite shortage");
        SimpleAssert.that(
            theory.getBoolean("complete") && !visible.hasKey("complete")
                && !demand.getCompoundTagAt(0)
                    .hasKey("availableKnown")
                && !model.ready()
                && !model.hasItemShortage()
                && !model.hasVoucherDemand(),
            "local display does not mutate theory or grant any server quote capability");
        model.updateLocal(HologramLocalMaterialReview.stockRows(theory, null, 2));
        SimpleAssert.that(
            "未知".equals(model.row(0).available) && "未知".equals(model.row(0).missing),
            "disabled or unsynced backpack is not reported as an observed zero");
    }

    private static void detachedNativeScope() {
        NBTTagCompound source = new NBTTagCompound();
        source.setLong("planRevision", 4);
        source.setBoolean("localStandardController", true);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(estimateCell(0, false, true)); // Uncharged machine anchor.
        cells.appendTag(estimateCell(1, false, true)); // Outside selection.
        cells.appendTag(estimateCell(2, false, true)); // Known selected target.
        cells.appendTag(estimateCell(3, false, false)); // Selected, target unresolved.
        cells.appendTag(estimateCell(4, true, true)); // Excluded, even when selected.
        source.setTag("cells", cells);
        HologramState displayed = new HologramState(source);
        NBTTagCompound config = new NBTTagCompound();
        config.setInteger("scope", 2);
        config.setInteger("layer", 7);
        config.setInteger("selected", 2);
        config.setIntArray("selectedIndices", new int[] { 0, 2, 3, 4 });
        config.setBoolean("materialMain", true);
        HologramSelection selection = new HologramSelection();
        selection.bindMaterials(displayed);
        selection.load(config.getIntArray("selectedIndices"), 2, displayed.cells.size());
        HologramLocalMaterialReview review = new HologramLocalMaterialReview(displayed, config, true, selection);
        ItemStack[] backpack = { new ItemStack(net.minecraft.init.Blocks.stone, 2, 0) };
        review.tick(backpack, -1);

        HologramMaterialReview model = new HologramMaterialReview();
        model.updateLocal(review.visible());
        SimpleAssert.that(
            review.ready() && review.status()
                .contains("1 格未核算")
                && model.size() == 1
                && "1".equals(model.row(0).required)
                && "2".equals(model.row(0).available),
            "native estimate includes only the selected scope, reports missing identity, and excludes the anchor");
        SimpleAssert.that(review.current(displayed, 7) && !review.current(displayed, 8), "local review is layer-bound");
        NBTTagCompound newer = new NBTTagCompound();
        newer.setLong("planRevision", 5);
        SimpleAssert.that(!review.current(displayed.withMetadata(newer), 7), "local review is revision-bound");
        config.setIntArray("selectedIndices", new int[] { 1 });
        SimpleAssert.that(
            review.settings()
                .getIntArray("selectedIndices").length == 4,
            "scope is frozen for submission");
    }

    private static NBTTagCompound estimateCell(int x, boolean excluded, boolean known) {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        cell.setInteger("dx", x);
        cell.setString("wantId", "minecraft:stone");
        cell.setBoolean("inScope", true);
        cell.setBoolean("excluded", excluded);
        if (known)
            cell.setTag("targetStack", new ItemStack(net.minecraft.init.Blocks.stone).writeToNBT(new NBTTagCompound()));
        return cell;
    }

    private static void staleNativeOwner() {
        try {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            Field channel = field(HologramNetwork.class, "channel");
            Object previous = channel.get(null);
            MaterialWire wire = (MaterialWire) unsafe.allocateInstance(MaterialWire.class);
            channel.set(null, wire);
            try {
                staleNativeOwner(unsafe, wire);
            } finally {
                channel.set(null, previous);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot construct the headless material pane owner", failure);
        }
    }

    private static void staleNativeOwner(sun.misc.Unsafe unsafe, MaterialWire wire)
        throws ReflectiveOperationException {
        Minecraft client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        OwnerWorld world = (OwnerWorld) unsafe.allocateInstance(OwnerWorld.class);
        EntityClientPlayerMP player = (EntityClientPlayerMP) unsafe.allocateInstance(EntityClientPlayerMP.class);
        NetHandlerPlayClient connection = (NetHandlerPlayClient) unsafe
            .allocateInstance(NetHandlerPlayClient.class);
        field(EntityClientPlayerMP.class, "sendQueue").set(player, connection);
        player.inventory = new InventoryPlayer(player);
        ItemStack tool = new ItemStack(new ItemNekoHologramProjector());
        player.inventory.mainInventory[0] = tool;
        client.thePlayer = player;
        client.theWorld = world;
        TileEntity tile = new TileEntity();
        tile.setWorldObj(world);
        world.tile = tile;
        HologramScreen screen = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
        field(GuiScreen.class, "mc").set(screen, client);
        client.currentScreen = screen;
        HologramUiModules ui = new HologramUiModules(screen);
        screen.ui = ui;
        field(HologramScreen.class, "openedPlayer").set(screen, player);
        field(HologramScreen.class, "openingController").set(screen, tile);
        field(HologramScreen.class, "openingConnection").set(screen, connection);
        field(HologramScreen.class, "openingTool").set(screen, tool);
        field(HologramScreen.class, "openingToolId").set(screen, "");
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setBoolean("localStandardController", true);
        metadata.setBoolean("supported", true);
        metadata.setBoolean("projectionDataComplete", true);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(estimateCell(1, false, true));
        metadata.setTag("cells", cells);
        field(HologramUiSession.class, "state").set(ui.session, new HologramState(metadata));

        LocalView view = ui.review;
        SimpleAssert.that(
            ui.preview.activateControl(26) && view.localFrame() != null && ui.session.sequence() == 0,
            "material view 26 opens the matching mode 0 local estimate without a session command");
        HologramLocalMaterialReview frozen = ui.review.localMaterials();
        int[] attempts = { 0 };
        ui.review.submitLocalMaterials(settings -> {
            attempts[0]++;
            ui.session.notice("fixture pre-dispatch refusal");
            return false;
        });
        SimpleAssert.that(
            attempts[0] == 1 && ui.review.localMaterials() == frozen
                && "fixture pre-dispatch refusal".equals(view.localFrame().status()),
            "a locally refused submit keeps the only current pane open and shows its reason");
        ui.review.submitLocalMaterials(settings -> {
            attempts[0]++;
            return true;
        });
        ui.review.submitLocalMaterials(settings -> {
            throw new AssertionError("a closed pane must not re-dispatch");
        });
        SimpleAssert.that(
            attempts[0] == 2 && view.localFrame() == null && ui.session.sequence() == 0,
            "successful local dispatch closes once without claiming a server acknowledgement");
        ui.review.openNativeLocalMaterials();
        view.localAction(LocalAction.BACK);
        SimpleAssert.that(
            view.localFrame() == null && ui.session.sequence() == 0,
            "the native port's Back is purely local");
        for (int mode : new int[] { 1, 2 }) {
            NBTTagCompound otherMode = (NBTTagCompound) metadata.copy();
            otherMode.setInteger("mode", mode);
            assertLocalMaterialViewUnknown(ui, wire, otherMode);
        }
        NBTTagCompound localWorkspace = (NBTTagCompound) metadata.copy();
        localWorkspace.removeTag("localStandardController");
        localWorkspace.setBoolean("localWorkspace", true);
        assertLocalMaterialViewUnknown(ui, wire, localWorkspace);
        field(HologramUiSession.class, "state").set(ui.session, new HologramState(metadata));
        ui.review.openNativeLocalMaterials();
        player.inventory.currentItem = 1;
        LocalFrame stale = view.localFrame();
        SimpleAssert.that(
            stale != null && stale.rows().hasNoTags() && !stale.ready() && !view.localSubmitAvailable()
                && stale.status().contains("已变化"),
            "the visible pane explains stale ownership without exposing old rows or enabling submission");
        ui.review.submitLocalMaterials(settings -> {
            throw new AssertionError("a stale scope must not dispatch");
        });
        view.localAction(LocalAction.REFRESH);
        SimpleAssert.that(
            ui.review.localMaterials() == null && ui.review.openNativeLocalMaterials()
                && ui.review.localMaterials() == null,
            "changed slot retires refresh; stale opening is handled locally rather than using the legacy query");

        player.inventory.currentItem = 0;
        ui.review.openNativeLocalMaterials();
        world.tile = new TileEntity();
        view.localAction(LocalAction.SUBMIT);
        SimpleAssert.that(
            ui.review.localMaterials() == null && ui.session.sequence() == 0,
            "an old pane cannot send or submit after the controller tile is replaced");

        world.tile = tile;
        ui.review.openNativeLocalMaterials();
        SimpleAssert.that(ui.review.localMaterials() != null, "original owner can open a fresh pane");
        client.thePlayer = (EntityClientPlayerMP) unsafe.allocateInstance(EntityClientPlayerMP.class);
        view.localAction(LocalAction.REFRESH); // Replacement player has no inventory; the fence must run first.
        SimpleAssert
            .that(ui.review.localMaterials() == null, "stale review never reads another player's inventory");

        client.thePlayer = player;
        ui.review.openNativeLocalMaterials();
        screen.ui = new HologramUiModules(screen);
        view.localAction(LocalAction.SUBMIT);
        SimpleAssert.that(ui.review.localMaterials() == null, "an inactive workspace graph cannot submit");

        screen.ui = ui;
        ui.review.openNativeLocalMaterials();
        client.currentScreen = null;
        view.localAction(LocalAction.REFRESH);
        SimpleAssert.that(ui.review.localMaterials() == null, "an inactive root screen cannot refresh");
        SimpleAssert.eq(0, wire.sends, "all local-only material page operations and stale-owner paths emitted no C2S");
    }

    private static void assertLocalMaterialViewUnknown(HologramUiModules ui, MaterialWire wire, NBTTagCompound metadata)
        throws ReflectiveOperationException {
        HologramState state = new HologramState(metadata);
        field(HologramUiSession.class, "state").set(ui.session, state);
        ui.session.notice("");
        int sends = wire.sends;
        SimpleAssert.that(
            ui.preview.activateControl(26) && ui.session.notice()
                .contains("未核验服务器库存或回收结果")
                && ui.review.localFrame() == null
                && ui.session.state() == state
                && !ui.session.awaitingScan()
                && ui.session.sequence() == 0 && wire.sends == sends,
            "unsupported local material view reports unknown without a scan, stock query or any outbound message");
    }

    private static final class MaterialWire extends SimpleNetworkWrapper {

        int sends;

        private MaterialWire() {
            super("gtmb_local_material_fixture");
        }

        @Override
        public void sendToServer(IMessage message) {
            sends++;
        }
    }

    private static void graphlessLocalViewControls() {
        try {
            var actions = new ArrayList<LocalAction>();
            NBTTagList rows = new NBTTagList();
            rows.appendTag(HologramMaterialQuote.itemRow(new ItemStack(Items.diamond), 2, 0));
            NBTTagCompound data = new NBTTagCompound();
            data.setTag("rows", rows);
            LocalFrame[] frame = { new LocalFrame(data, "fixture scope", "fixture summary", "fixture empty", true) };
            LocalView view = new LocalView() {

                @Override
                public LocalFrame localFrame() {
                    return frame[0];
                }

                @Override
                public boolean localSubmitAvailable() {
                    return frame[0] != null;
                }

                @Override
                public void localAction(LocalAction action) {
                    actions.add(action);
                }
            };
            HologramMaterialConfirmationDialog dialog = new HologramMaterialConfirmationDialog(
                new ModularPanel("fixture_root"),
                view);
            // Replace only panel attachment bookkeeping; the material panel, model and buttons are real.
            boolean[] attached = { true };
            IPanelHandler attachment = (IPanelHandler) java.lang.reflect.Proxy.newProxyInstance(
                IPanelHandler.class.getClassLoader(),
                new Class<?>[] { IPanelHandler.class },
                (proxy, method, arguments) -> {
                    if ("isPanelOpen".equals(method.getName())) return attached[0];
                    if ("closePanel".equals(method.getName())) attached[0] = false;
                    return null;
                });
            field(HologramMaterialConfirmationDialog.class, "handler").set(dialog, attachment);
            dialog.tick(true);
            var build = HologramMaterialConfirmationDialog.class.getDeclaredMethod("build");
            build.setAccessible(true);
            ModularPanel panel = (ModularPanel) build.invoke(dialog);
            SimpleAssert.that(
                "gtmb_local_material_review".equals(panel.getName()) && dialog.probeState(null)
                    .getInteger("rows") == 1,
                "local widgets consume the detached row model without an authority graph");
            var controls = (Map<?, ?>) field(HologramMaterialConfirmationDialog.class, "enabledControls").get(dialog);
            var buttons = panel.getChildren()
                .stream()
                .filter(child -> child instanceof ButtonWidget<?>)
                .map(child -> (ButtonWidget<?>) child)
                .collect(java.util.stream.Collectors.toList());
            SimpleAssert.that(
                controls.keySet()
                    .equals(new java.util.HashSet<>(java.util.Arrays.asList(162, 245, 247, 54, 55))) && buttons.size() == 5,
                "one local review tree retains shared voucher/order control ids with unavailable capabilities disabled");
            SimpleAssert.that(
                dialog.consumeKey(org.lwjgl.input.Keyboard.KEY_RETURN) && actions.isEmpty(),
                "Enter still does not act on a local estimate");
            for (ButtonWidget<?> button : buttons) {
                button.playClickSound(false); // No Minecraft audio manager in this fixture.
                button.onMousePressed(0);
            }
            SimpleAssert.eq(
                java.util.Arrays.asList(LocalAction.REFRESH, LocalAction.BACK, LocalAction.SUBMIT),
                actions,
                "actual existing widgets dispatch the corresponding local verbs");
            frame[0] = null;
            dialog.tick(true);
            SimpleAssert.that(
                !dialog.isOpen() && actions.size() == 3,
                "a stale owner frame closes the local modal without dispatch");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot construct the detached material view widgets", failure);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class OwnerWorld extends WorldClient {

        TileEntity tile;

        private OwnerWorld() {
            super(null, null, 0, null, null);
            throw new AssertionError("Use the fixture without creating a real world");
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tile;
        }
    }

    private static void incompleteEstimateCannotEnableConfirmation() {
        NBTTagCompound row = HologramMaterialQuote.itemRow(new ItemStack(Items.diamond), 37, 0);
        row.setBoolean("voucherEligible", true);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        NBTTagCompound quote = new NBTTagCompound();
        quote.setBoolean("complete", false);
        quote.setBoolean("localEstimate", true);
        quote.setTag("rows", rows);
        NBTTagCompound state = new NBTTagCompound();
        state.setTag("materialQuote", quote);
        HologramMaterialReview model = new HologramMaterialReview();
        model.update(state);
        SimpleAssert.that(
            !model.ready() && !model.hasItemShortage() && !model.hasVoucherDemand(),
            "an incomplete estimate cannot enable confirmation, material orders or voucher controls");
    }

    private static void contextAndUnchangedChoices() {
        try {
            HologramUiModules ui = new HologramUiModules(null);
            NBTTagCompound source = new NBTTagCompound();
            source.setString("session", "context");
            source.setBoolean("virtual", true);
            source.setBoolean("placementDeidentificationChosen", true);
            source.setInteger("placementDeidentificationLevel", 1);
            NBTTagCompound processing = new NBTTagCompound();
            source.setTag("processing", processing);
            java.lang.reflect.Field state = HologramUiSession.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(ui.session, new HologramState(source));
            SimpleAssert.that(
                !ui.nbt.hasPlacementNbtChoices() && !ui.identity.hasPlacementIdentityChoices(),
                "plain blocks expose no ineffective NBT or identity settings in material review");
            NBTTagList keys = new NBTTagList();
            keys.appendTag(new net.minecraft.nbt.NBTTagString("configuration"));
            processing.setTag("nbtKeys", keys);
            processing.setInteger("requiredStages", 1 << 1);
            state.set(ui.session, new HologramState(source));
            SimpleAssert.that(
                ui.nbt.hasPlacementNbtChoices() && ui.identity.hasPlacementIdentityChoices(),
                "server-analyzed editable keys and identity stage retain useful manual configuration");
            for (String name : new String[] { "nbtPlacement", "openedPlacementAll", "openedPlacementCovers" }) {
                java.lang.reflect.Field field = HologramUiNbtEditor.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(ui.nbt, true);
            }
            ui.nbt.nbtDrawer(true);
            SimpleAssert.that(
                ui.nbt.activateControl(173) && !ui.nbt.nbtDrawer()
                    && ui.session.sequence() == 0
                    && ui.session.pendingSequence() < 0,
                "applying an unchanged placement NBT choice closes the drawer without a request or revalidation");
            java.lang.reflect.Field placement = HologramUiDeidentification.class
                .getDeclaredField("deidentificationPlacement");
            placement.setAccessible(true);
            placement.setBoolean(ui.identity, true);
            java.lang.reflect.Field drawer = HologramUiDeidentification.class
                .getDeclaredField("deidentificationDrawer");
            drawer.setAccessible(true);
            drawer.setBoolean(ui.identity, true);
            SimpleAssert.that(
                ui.identity.activateDeidentification(213) && !drawer.getBoolean(ui.identity)
                    && ui.session.sequence() == 0
                    && ui.session.pendingSequence() < 0,
                "reapplying the same chosen identity level performs no network action or review reset");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot install actual no-op review controls", failure);
        }
    }

    private static void metadataFence() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "quote-session");
        source.setLong("planRevision", 12);
        source.setLong("uiSequence", 5);
        source.setLong("placementReviewNonce", 3);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(new NBTTagCompound());
        source.setTag("cells", cells);
        HologramState base = new HologramState(source);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("materialQuoteOnly", true);
        receipt.setString("session", "quote-session");
        receipt.setLong("planRevision", 12);
        receipt.setLong("uiSequence", 6);
        receipt.setLong("placementReviewNonce", 4);
        receipt.setBoolean("targetClosed", true);
        receipt.setTag("cells", new NBTTagList());
        NBTTagCompound voucher = new NBTTagCompound();
        voucher.setBoolean("selected", true);
        receipt.setTag("decorativeVoucher", voucher);
        HologramState next = HologramMaterialQuoteUpdates.apply(base, receipt);
        SimpleAssert.that(
            next != null && next.cells == base.cells
                && next.regions == base.regions
                && next.structureMembers == base.structureMembers
                && next.parsedCells == 0,
            "lightweight quote retains parsed geometry, spatial indices and membership references");
        SimpleAssert.that(
            next.data.getCompoundTag("decorativeVoucher")
                .getBoolean("selected")
                && !next.data.getBoolean("targetClosed")
                && !base.data.getCompoundTag("decorativeVoucher")
                    .getBoolean("selected"),
            "quote whitelist updates only material metadata without mutating the previous snapshot");
        SimpleAssert.that(
            next.data.getLong("uiSequence") == 5 && next.data.getLong("materialQuoteSequence") == 6,
            "a material ACK has its own sequence and cannot invalidate an in-flight full encoding");
        HologramState delayedFull = new HologramState(source);
        HologramState retained = HologramMaterialQuoteUpdates.retain(delayedFull, next);
        SimpleAssert.that(
            retained.cells == delayedFull.cells && retained.data.getLong("placementReviewNonce") == 4
                && retained.data.getCompoundTag("decorativeVoucher")
                    .getBoolean("selected"),
            "a delayed full baseline installs its geometry while retaining the newer voucher receipt");
        source.setLong("uiSequence", 7);
        HologramState newerFull = new HologramState(source);
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.retain(newerFull, next) == newerFull,
            "a newer ordinary action remains authoritative over the prior quote");
        source.setLong("uiSequence", 5);
        source.setLong("planRevision", 13);
        HologramState changedPlan = HologramMaterialQuoteUpdates.retain(new HologramState(source), next);
        SimpleAssert.that(
            changedPlan.data.getLong("materialQuoteSequence") == 6
                && !changedPlan.data.getCompoundTag("decorativeVoucher")
                    .getBoolean("selected"),
            "a newer plan keeps the local sequence watermark without inheriting an old voucher projection");
        receipt.setLong("placementReviewNonce", 2);
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.apply(next, receipt) == null,
            "a stale voucher response cannot roll back a newer review nonce");
        receipt.setLong("placementReviewNonce", 5);
        receipt.setLong("uiSequence", 4);
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.apply(next, receipt) == null,
            "an old command response cannot replace current material metadata");
        receipt.setLong("uiSequence", 7);
        receipt.setLong("planRevision", 13);
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.apply(next, receipt) == null,
            "a different plan requires an actual snapshot, not a material-only reply");
        receipt.setLong("planRevision", 12);
        receipt.setString("session", "other-session");
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.apply(next, receipt) == null,
            "a quote from another workstation cannot enter this review");
    }

    public static void main(String[] args) {
        run();
    }
}
