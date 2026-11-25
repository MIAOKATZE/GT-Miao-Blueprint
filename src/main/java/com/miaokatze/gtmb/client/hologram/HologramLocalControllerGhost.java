// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/**
 * A closed local controller editor may lend its immutable target to the world renderer; it is never a server
 * session, a placement request or a new copy of the geometry. All calls run on the client thread.
 */
final class HologramLocalControllerGhost {

    private static Lease current;

    private HologramLocalControllerGhost() {}

    static final class Lease {

        final HologramState target;
        final Object world, connection, player, tile, unlinkedStack;
        final int dimension, slot;
        final String tool, serverSession;
        final long opened;

        Lease(HologramState target, Object world, Object connection, Object player, Object tile, int dimension,
            int slot, String tool, Object stack, String serverSession, long now) {
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
        }

        boolean current(Object world, Object connection, Object player, Object tile, int dimension, long now) {
            return this.world == world && this.connection == connection
                && this.player == player
                && this.tile == tile
                && this.dimension == dimension
                && now - opened <= HologramProjectionLifetime.SNAPSHOT_TTL_NANOS;
        }

        boolean superseded(boolean blueprint, boolean cut, HologramState server, String workspaceSession) {
            return blueprint || cut
                || server != null && (server.data.getBoolean("virtualPlacement") || server.data.getInteger("job") == 1
                    || server.data.getInteger("job") == 2
                    || server.data.getBoolean("target") && !server.session.equals(serverSession))
                || workspaceSession != null && !target.session.equals(workspaceSession);
        }

        boolean show(boolean enabled, boolean screenClosed, boolean projector, int heldSlot, String heldId,
            Object stack) {
            return enabled && screenClosed
                && projector
                && heldSlot == slot
                && (tool.isEmpty() ? stack == unlinkedStack : tool.equals(heldId));
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

    static void retain(Minecraft mc, TileEntity controller, String toolId, HologramState target) {
        clear();
        if (!eligible(target) || mc == null
            || mc.theWorld == null
            || mc.getNetHandler() == null
            || mc.thePlayer == null
            || controller == null
            || controller.isInvalid()
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
            System.nanoTime());
    }

    static void observe(Minecraft mc, HologramState server) {
        Lease lease = current;
        if (lease == null) return;
        TileEntity tile = (TileEntity) lease.tile;
        if (mc == null || mc.thePlayer == null
            || mc.theWorld != lease.world
            || tile.isInvalid()
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

    static void clear() {
        if (current == null) return;
        HologramRenderer.releaseWorldFor(current.target);
        current = null;
    }
}
