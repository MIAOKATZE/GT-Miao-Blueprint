// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection;
import com.miaokatze.gtmb.hologram.HologramLocalControllerCapture;
import com.miaokatze.gtmb.hologram.HologramNativePresetIndex;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire;

/**
 * A closed local controller editor may lend its immutable target to the world renderer; it is never a server
 * session, a placement request or a new copy of the geometry. All calls run on the client thread.
 */
final class HologramLocalControllerGhost {

    static final long IDLE_VISIBILITY_NANOS = 5_000_000_000L;
    private static Lease current;
    private static Profile latestPlacement;

    private HologramLocalControllerGhost() {}

    static final class Lease {

        final HologramState target;
        final Object world, connection, player, tile, unlinkedStack;
        final int dimension, slot;
        final String tool, serverSession;
        final long opened;
        final UUID activeRequest;

        Lease(HologramState target, Object world, Object connection, Object player, Object tile, int dimension,
            int slot, String tool, Object stack, String serverSession, long now) {
            this(target, world, connection, player, tile, dimension, slot, tool, stack, serverSession, now, null);
        }

        Lease(HologramState target, Object world, Object connection, Object player, Object tile, int dimension,
            int slot, String tool, Object stack, String serverSession, long now, UUID activeRequest) {
            this.target = target;
            this.world = world;
            this.connection = connection;
            this.player = player;
            this.tile = tile;
            this.dimension = dimension;
            this.slot = slot;
            this.tool = tool;
            this.unlinkedStack = tool.isEmpty() ? stack : null;
            this.serverSession = serverSession;
            this.opened = now;
            this.activeRequest = activeRequest;
        }

        boolean current(Object world, Object connection, Object player, Object tile, int dimension, long now) {
            return this.world == world && this.connection == connection
                && this.player == player
                && this.tile == tile
                && this.dimension == dimension
                && (activeRequest != null || now - opened < IDLE_VISIBILITY_NANOS);
        }

        boolean ownedBy(UUID request) {
            return request != null && request.equals(activeRequest);
        }

        boolean superseded(boolean blueprint, boolean cut, HologramState server, String workspaceSession) {
            return blueprint || cut
                || server != null && (server.data.getBoolean("virtualPlacement") || server.data.getInteger("job") == 1
                    || server.data.getInteger("job") == 2
                    || server.data.getBoolean("target") && !server.session.equals(serverSession))
                || activeRequest == null && workspaceSession != null && !target.session.equals(workspaceSession);
        }

        boolean show(boolean enabled, boolean screenClosed, boolean projector, int heldSlot, String heldId,
            Object stack) {
            return enabled && screenClosed
                && (activeRequest != null || projector && heldSlot == slot
                    && (tool.isEmpty() ? stack == unlinkedStack : tool.equals(heldId)));
        }
    }

    static boolean eligible(HologramState target) {
        if (target == null || target.cells.isEmpty()
            || !target.data.getBoolean("localStandardController")
            || !target.data.getBoolean("target")
            || target.data.getBoolean("virtual")
            || target.data.getBoolean("nativeBuildSatisfied")
            || target.data.getBoolean("localStructureUnavailable")
            || target.data.getString("generation")
                .isEmpty())
            return false;
        // The completed site already matches; never resurrect its ghost on leaving a refreshed build review.
        return !target.data.hasKey("siteMissing", 3) || target.data.getInteger("siteMissing") > 0
            || target.data.getInteger("siteDifferent") > 0
            || target.data.getInteger("siteUnknown") > 0
            || target.data.getInteger("mode") != 0
            || target.data.getLong("planRevision") > 0;
    }

    static void retain(Minecraft mc, TileEntity controller, String toolId, HologramState target, UUID request) {
        if (request == null && current != null && current.activeRequest != null) return;
        clear();
        if (!eligible(target) || mc == null
            || mc.theWorld == null
            || mc.getNetHandler() == null
            || mc.thePlayer == null
            || controller == null
            || controller.isInvalid()
            || !sameOrientation(target, controller)
            || mc.theWorld != controller.getWorldObj()
            || target.data.getInteger("dimension") != mc.thePlayer.dimension
            || target.data.getInteger("x") != controller.xCoord
            || target.data.getInteger("y") != controller.yCoord
            || target.data.getInteger("z") != controller.zCoord) return;
        HologramState server = HologramClient.state;
        current = new Lease(
            target,
            mc.theWorld,
            mc.getNetHandler(),
            mc.thePlayer,
            controller,
            mc.thePlayer.dimension,
            mc.thePlayer.inventory.currentItem,
            toolId == null ? "" : toolId,
            mc.thePlayer.getHeldItem(),
            server == null ? "" : server.session,
            System.nanoTime(),
            request);
    }

    static void observe(Minecraft mc, HologramState server) {
        if (latestPlacement != null && !latestPlacement.context.same(HologramClientStockNative.context(mc)))
            latestPlacement = null;
        Lease lease = current;
        if (lease == null) return;
        TileEntity tile = (TileEntity) lease.tile;
        if (mc == null || mc.thePlayer == null
            || mc.theWorld != lease.world
            || tile.isInvalid()
            || !sameOrientation(lease.target, tile)
            || !mc.theWorld.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)
            || !lease.current(
                mc.theWorld,
                mc.getNetHandler(),
                mc.thePlayer,
                mc.theWorld.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord),
                mc.thePlayer.dimension,
                System.nanoTime())
            || lease.superseded(
                HologramClient.blueprintInputBusy(),
                HologramCutClient.INSTANCE.active(),
                server,
                mc.currentScreen instanceof HologramScreen screen ? screen.ui.session.state().session : null))
            clear();
    }

    static boolean render(Minecraft mc, boolean enabled, float partialTicks) {
        Lease lease = current;
        if (lease == null || mc.thePlayer == null) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        String id = held == null || !held.hasTagCompound() ? ""
            : held.getTagCompound()
                .getString("gtitHologramToolId");
        if (!lease.show(
            enabled,
            mc.currentScreen == null,
            ItemNekoHologramProjector.isProjector(held),
            mc.thePlayer.inventory.currentItem,
            id,
            held)) return false;
        HologramRenderer.world(lease.target, partialTicks);
        return true;
    }

    private static boolean sameOrientation(HologramState target, TileEntity tile) {
        try {
            return target.data.hasKey("facing", 3) && target.data.getInteger("facing")
                == HologramLocalControllerCapture.liveOrientation(tile, target.data.getInteger("side"));
        } catch (RuntimeException | LinkageError invalid) {
            return false;
        }
    }

    static void finish(UUID request) {
        if (afterTerminal(current, request) != current) clear();
    }

    static Lease afterTerminal(Lease lease, UUID request) {
        return lease != null && lease.ownedBy(request) ? null : lease;
    }

    /** One session-only slot; not a named preset or disk write. */
    static void rememberPlacement(HologramClientStockNative.Context context,
        HologramClientStockNative.DeclarationSite site, HologramStockNativeWire.Confirmation confirmation, int tier) {
        var declaration = confirmation.declaration();
        if (context == null || site == null
            || declaration == null
            || declaration.operation() != 0
            || declaration.scope() != 0) return;
        latestPlacement = new Profile(
            context,
            site.controllerId(),
            site.machine()
                .getClass(),
            site.facing(),
            confirmation.main(),
            confirmation.mainExplicit(),
            confirmation.noHatches(),
            declaration,
            tier);
    }

    static Profile latestFor(Minecraft mc, TileEntity tile) {
        var profile = latestPlacement;
        var context = HologramClientStockNative.context(mc);
        if (profile == null) return null;
        if (!profile.context.same(context)) {
            latestPlacement = null;
            return null;
        }
        var site = HologramClientStockNative.DeclarationSite.read(mc.theWorld, tile);
        return site != null && profile.matches(
            context,
            site.controllerId(),
            site.machine()
                .getClass()) ? profile : null;
    }

    @com.github.bsideup.jabel.Desugar
    record Profile(HologramClientStockNative.Context context, int controllerId, Class<?> sourceClass, int facing,
        int main, boolean explicit, boolean noHatches, HologramDeclaredNativeSelection declaration, int tier) {

        boolean matches(HologramClientStockNative.Context candidate, int registration, Class<?> type) {
            return context.same(candidate) && controllerId == registration && sourceClass == type;
        }

        void applyChannels(NBTTagCompound settings) {
            settings.setInteger("main", main);
            settings.setBoolean("mainExplicit", explicit);
            settings.setBoolean("noHatches", noHatches);
            settings.setTag("channels", declaration.channelTag());
        }

        /** Rebind the old relative targets against this freshly captured machine; never reuse old geometry/indices. */
        List<HologramNativePresetIndex.Choice> choices(HologramState fresh, HologramLocalControllerCapture capture)
            throws IOException {
            if (declaration.hatches()
                .isEmpty() || fresh.data.getInteger("facing") != facing) return Collections.emptyList();
            if (fresh.data.getInteger("main") != main || fresh.data.getBoolean("noHatches") != noHatches
                || !fresh.data.getCompoundTag("channels")
                    .equals(declaration.channelTag()))
                return Collections.emptyList();
            int x = fresh.data.getInteger("x"), y = fresh.data.getInteger("y"), z = fresh.data.getInteger("z");
            var result = new ArrayList<HologramNativePresetIndex.Choice>();
            for (var hatch : declaration.hatches()) {
                var offset = hatch.position();
                var cell = fresh.regions.at(x + offset.x(), y + offset.y(), z + offset.z());
                var offers = cell == null || cell.anchor ? null : capture.selectedOffers(cell.index);
                if (offers == null || !offers.allowsRememberedHatch(hatch.id(), hatch.facing()))
                    return Collections.emptyList();
                result.add(new HologramNativePresetIndex.Choice(cell.index, hatch.id(), hatch.facing()));
            }
            return result;
        }
    }

    static void clear() {
        if (current == null) return;
        HologramRenderer.releaseWorldFor(current.target);
        current = null;
    }
}
