// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.client.hologram.HologramLibraryClient.PlainAvailability;
import com.miaokatze.gtmb.client.hologram.HologramLibraryMaterialReviewScreen.Ticket;
import com.miaokatze.gtmb.client.hologram.HologramLocalPlacement.Submission;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramMaterials;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Escrow contract only; does not submit to the real library service or launch a client UI. */
final class HologramLibrarySecondUseRegression {

    private HologramLibrarySecondUseRegression() {}

    public static void main(String[] args) throws IOException {
        requestRouting();
        supportedModeAndSource();
        System.out.println("Local construction confirmation regression passed.");
    }

    private static void requestRouting() {
        var frozen = new Submission(null, null, 0, new NBTTagCompound());
        var pending = new AtomicReference<>(frozen);
        var calls = new ArrayList<Boolean>();
        java.util.function.BiPredicate<Submission, Boolean> refuse = (actual, direct) -> {
            SimpleAssert.that(actual == frozen, "the original frozen submission crosses the actual submit port");
            calls.add(direct);
            return false;
        };
        for (var action : new LocalAction[] { LocalAction.REFRESH, LocalAction.BACK }) SimpleAssert.that(
            !HologramLibraryMaterialReviewScreen.dispatchRefused(
                action,
                () -> { throw new AssertionError("Local view and unsupported actions cannot consume a ticket"); },
                refuse),
            "there is no placement request from a local or unsupported action");
        SimpleAssert.that(calls.isEmpty() && pending.get() == frozen, "no preliminary send or ticket loss");
        SimpleAssert.that(
            HologramLibraryMaterialReviewScreen
                .dispatchRefused(LocalAction.SUBMIT, () -> pending.getAndSet(null), refuse),
            "the direct refusal stays visible");
        SimpleAssert
            .eq(Collections.singletonList(true), calls, "only explicit normal confirm can choose the sliced path");
        SimpleAssert.that(
            !HologramLibraryMaterialReviewScreen
                .dispatchRefused(LocalAction.SUBMIT, () -> pending.getAndSet(null), refuse),
            "a second normal click cannot revive a consumed ticket");
        SimpleAssert.that(
            pending.get() == null && calls.size() == 1,
            "no obsolete transport can be retried after a refused ordinary click");
    }

    private static void supportedModeAndSource() {
        var defaultTool = new ItemStack(new ItemNekoHologramProjector());
        var settings = HologramMaterials.read(defaultTool);
        SimpleAssert.that(
            settings.main && settings.containers && settings.me && settings.priority == 0,
            "an unconfigured real projector enables all three sources and prioritizes the main backpack");
        SimpleAssert.eq(
            PlainAvailability.SOURCE,
            HologramLibraryClient.plainPlacementAvailability(false, true, false, settings, true, true),
            "public, portable and unsupported variants stay material-only");
        SimpleAssert.eq(
            PlainAvailability.MODE,
            HologramLibraryClient.plainPlacementAvailability(true, false, false, settings, true, true),
            "missing edit permission does not promise placement");
        SimpleAssert.eq(
            PlainAvailability.READY,
            HologramLibraryClient.plainPlacementAvailability(true, true, false, settings, true, true),
            "the unmodified default passes capability checks with the selected stock providers");
        SimpleAssert.that(
            !defaultTool.hasTagCompound() && settings.main && settings.containers && settings.me,
            "availability does not rewrite enabled sources on the held projector");
        SimpleAssert.eq(
            PlainAvailability.READY,
            HologramLibraryClient.plainPlacementAvailability(true, true, true, null, true, true),
            "creative placement does not require any survival material providers");
        SimpleAssert.eq(
            PlainAvailability.MODE,
            HologramLibraryClient.plainPlacementAvailability(true, false, true, settings, true, true),
            "creative still needs world editing privileges");
        // Mask order: neither external source, containers, wireless ME, both.
        var ready = PlainAvailability.READY;
        var blocked = PlainAvailability.MATERIALS;
        PlainAvailability[][] withMain = { { ready, ready, ready, ready }, { ready, ready, ready, ready },
            { ready, ready, ready, ready } };
        for (int priority = 0; priority < withMain.length; priority++) {
            for (int mask = 0; mask < withMain[priority].length; mask++) {
                var configured = new HologramMaterials.Settings();
                configured.priority = priority;
                configured.containers = (mask & 1) != 0;
                configured.me = (mask & 2) != 0;
                SimpleAssert.eq(
                    withMain[priority][mask],
                    HologramLibraryClient.plainPlacementAvailability(true, true, false, configured, true, true),
                    "main enabled: priority " + priority + ", fallback mask " + mask);
                configured.main = false;
                SimpleAssert.eq(
                    mask == 0 ? blocked : ready,
                    HologramLibraryClient.plainPlacementAvailability(true, true, false, configured, true, true),
                    "only explicitly enabled external sources remain available: priority " + priority
                        + ", fallback mask "
                        + mask);
            }
        }
        for (int invalid : new int[] { -1, 3 }) {
            var configured = new HologramMaterials.Settings();
            configured.priority = invalid;
            SimpleAssert.eq(
                blocked,
                HologramLibraryClient.plainPlacementAvailability(true, true, false, configured, true, true),
                "invalid priority does not start placement");
        }
        SimpleAssert.eq(
            blocked,
            HologramLibraryClient.plainPlacementAvailability(true, true, false, null, true, true),
            "missing source settings fail closed");
        SimpleAssert.eq(
            PlainAvailability.TOOL,
            HologramLibraryClient.plainPlacementAvailability(true, true, false, settings, false, true),
            "fresh projector with no verified id is declined without guessing another stack");
        SimpleAssert.that(
            HologramLibraryClient.canonicalProjectorId(new UUID(3, 1).toString())
                && !HologramLibraryClient.canonicalProjectorId("")
                && !HologramLibraryClient.canonicalProjectorId("1-2-3-4-5"),
            "only the current canonical UUID qualifies as the tracked tool identity");
        SimpleAssert.eq(
            PlainAvailability.TRANSPORT,
            HologramLibraryClient.plainPlacementAvailability(true, true, false, settings, true, false),
            "no local transport is a proved unavailable condition and has no fallback");
        SimpleAssert.eq(
            PlainAvailability.READY,
            HologramLibraryClient.plainPlacementAvailability(true, true, false, settings, true, true),
            "exact selected private/main-only/survival scope keeps the existing explicit submit affordance");
    }

    static void run() throws Exception {
        requestRouting();
        supportedModeAndSource();
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        Minecraft client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        WorldClient world = (WorldClient) unsafe.allocateInstance(WorldClient.class);
        EntityClientPlayerMP player = (EntityClientPlayerMP) unsafe.allocateInstance(EntityClientPlayerMP.class);
        NetHandlerPlayClient network = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
        field(EntityClientPlayerMP.class, "sendQueue").set(player, network);
        player.inventory = new InventoryPlayer(player);
        ItemStack projector = new ItemStack(new ItemNekoHologramProjector());
        projector.setTagCompound(new NBTTagCompound());
        projector.getTagCompound()
            .setString("gtitHologramToolId", "fixture");
        player.inventory.mainInventory[0] = projector;
        client.theWorld = world;
        client.thePlayer = player;

        var placement = new HologramLocalPlacement();
        var captured = new HologramLibraryClient.DisplaySource("portable", "", null, null, "", "", "", "");
        var display = new HologramState(new NBTTagCompound());
        field(HologramLocalPlacement.class, "display").set(placement, display);
        field(HologramLocalPlacement.class, "source").set(placement, captured);
        field(HologramLocalPlacement.class, "world").set(placement, world);
        field(HologramLocalPlacement.class, "connection").set(placement, network);
        field(HologramLocalPlacement.class, "player").set(placement, player);
        field(HologramLocalPlacement.class, "locked").setBoolean(placement, true);
        var frozen = new Submission(captured, display, 0, HologramLocalPlacement.reviewOptions());

        var original = new GuiScreen();
        var newer = new GuiScreen();
        client.currentScreen = original;
        Ticket old = new Ticket(placement, frozen, client, original);
        client.currentScreen = newer;
        Ticket ticket = new Ticket(placement, frozen, client, newer);
        SimpleAssert.that(old.consume(client) == null, "a queued action from a different window cannot submit");
        player.inventory.mainInventory[1] = projector;
        player.inventory.currentItem = 1;
        SimpleAssert.that(ticket.consume(client) == null, "same projector in a changed slot cannot submit");
        player.inventory.currentItem = 0;
        SimpleAssert
            .that(ticket.consume(client) == null, "an observed stale click cannot revive when the slot returns");
        ticket = new Ticket(placement, frozen, client, newer);
        player.inventory.mainInventory[0] = projector.copy();
        SimpleAssert.that(ticket.consume(client) == null, "same-ID replacement stack cannot submit");
        player.inventory.mainInventory[0] = projector;
        ticket = new Ticket(placement, frozen, client, newer);
        projector.getTagCompound()
            .setString("gtitHologramToolId", "other");
        SimpleAssert.that(ticket.consume(client) == null, "changed ID on the same object cannot submit");
        projector.getTagCompound()
            .setString("gtitHologramToolId", "fixture");
        ticket = new Ticket(placement, frozen, client, newer);
        player.dimension = 1;
        SimpleAssert
            .that(ticket.consume(client) == null, "same player and world with a changed dimension cannot submit");
        player.dimension = 0;
        ticket = new Ticket(placement, frozen, client, newer);
        client.theWorld = null;
        SimpleAssert.that(ticket.consume(client) == null, "world loss invalidates the old view");
        client.theWorld = world;
        ticket = new Ticket(placement, frozen, client, newer);
        client.thePlayer = null;
        SimpleAssert
            .that(ticket.consume(client) == null, "player loss invalidates the old view before inventory access");
        client.thePlayer = player;
        ticket = new Ticket(placement, frozen, client, newer);
        player.isDead = true;
        SimpleAssert.that(
            !ticket.current(client) && ticket.consume(client) == null,
            "death invalidates the local owner before sampling or submitting");
        player.isDead = false;
        ticket = new Ticket(placement, frozen, client, newer);
        field(EntityClientPlayerMP.class, "sendQueue").set(player, null);
        SimpleAssert.that(
            ticket.consume(client) == null,
            "unchanged world and inventory cannot substitute for connection ownership");
        field(EntityClientPlayerMP.class, "sendQueue").set(player, network);
        ticket = new Ticket(placement, frozen, client, newer);
        field(HologramLocalPlacement.class, "lockGeneration").setLong(placement, 1);
        SimpleAssert.that(ticket.consume(client) == null, "relocking the same source and position invalidates escrow");

        var relocked = new Submission(captured, display, 1, HologramLocalPlacement.reviewOptions());
        var back = new Ticket(placement, relocked, client, newer);
        back.retire();
        SimpleAssert.that(
            back.consume(client) == null && relocked.current(placement, client),
            "local Back retires only its window; world lock and frozen selection stay valid");
        var explicit = new Ticket(placement, relocked, client, newer);
        SimpleAssert.that(
            explicit.consume(client) == relocked && explicit.consume(client) == null,
            "one explicit click receives the exact frozen object, not a new freeze or duplicate submission");
        client.currentScreen = original;
        SimpleAssert.that(
            relocked.current(placement, client),
            "the existing async preparation may still use the same source after its loading screen replaces the pane");

        var observation = new HologramLibraryMaterialReviewScreen.CapturedDescription(
            HologramLibraryMaterialForecast.fromCapture(captured));
        var frame = observation
            .frame(() -> { throw new AssertionError("unindexed source has no stock to refresh"); }, 0);
        SimpleAssert.that(
            frame.status()
                .contains("未核算") && !frame.ready()
                && !frame.rows()
                    .hasKey("complete")
                && frame.description()
                    .isEmpty(),
            "already-present unproved source uses an explicit local unknown frame, not an authoritative quote");
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
