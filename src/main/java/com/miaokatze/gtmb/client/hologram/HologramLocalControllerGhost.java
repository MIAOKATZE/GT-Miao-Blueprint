// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramLocalControllerCapture;

/**
 * A closed local controller editor may lend its immutable target to the world renderer; it is never a server
 * session, a placement request or a new copy of the geometry. All calls run on the client thread.
 */
final class HologramLocalControllerGhost {

    static final long IDLE_VISIBILITY_NANOS = 5_000_000_000L;
    private static Lease current;

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

    static void clear() {
        if (current == null) return;
        HologramRenderer.releaseWorldFor(current.target);
        current = null;
    }
}
