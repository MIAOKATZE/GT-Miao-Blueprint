// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramWorldFlow.Stage;

/** Esc can retire a click before any server session exists on the client. */
final class HologramControllerOpenIntent {

    enum Route {
        CONTROLLER_PREVIEW,
        PRIVATE_LIBRARY,
        SERVER_WORKSPACE
    }

    static Route route(Stage stage, boolean air, boolean constructable) {
        if (stage != Stage.WORKSPACE) return Route.SERVER_WORKSPACE;
        if (air) return Route.PRIVATE_LIBRARY;
        return constructable ? Route.CONTROLLER_PREVIEW : Route.SERVER_WORKSPACE;
    }

    /** A local screen may send a deliberate server action only for the projector and world that opened it. */
    static final class Context {

        private final World world;
        private final EntityPlayer player;
        private final Object connection;
        private final int slot;
        private final ItemStack equipped;
        private final String toolId;

        Context(Minecraft client) {
            world = client.theWorld;
            player = client.thePlayer;
            connection = client.getNetHandler();
            slot = player.inventory.currentItem;
            equipped = player.getHeldItem();
            toolId = toolId(equipped);
        }

        boolean current(Minecraft client) {
            if (client.theWorld != world || client.thePlayer != player
                || client.getNetHandler() != connection
                || player.inventory.currentItem != slot) return false;
            ItemStack held = player.getHeldItem();
            return ItemNekoHologramProjector.isProjector(held)
                && (toolId.isEmpty() ? held == equipped : toolId.equals(toolId(held)));
        }

        private static String toolId(ItemStack stack) {
            return stack == null || !stack.hasTagCompound() ? ""
                : stack.getTagCompound()
                    .getString("gtitHologramToolId");
        }
    }

    private long latest;
    private boolean dismissed;
    private boolean tracksTarget;
    private int dimension, x, y, z;
    private String legacySession = "";
    private final HologramProgress.Tracker waiting = new HologramProgress.Tracker();

    long begin(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        tracksTarget = true;
        dismissed = false;
        legacySession = "";
        ++latest;
        waiting.update("controller:" + latest, "ack", 0, 0, "", false, System.nanoTime());
        return latest;
    }

    void forgetTarget() {
        tracksTarget = false;
        dismissed = true;
        legacySession = "";
    }

    void installed() {
        tracksTarget = false;
        legacySession = "";
    }

    boolean acceptsLegacy(int dimension, int x, int y, int z) {
        return !tracksTarget || !dismissed && matchesTarget(dimension, x, y, z);
    }

    private boolean matchesTarget(int dimension, int x, int y, int z) {
        return this.dimension == dimension && this.x == x && this.y == y && this.z == z;
    }

    boolean answers(NBTTagCompound snapshot, long id) {
        if (!accepts(id)) return false;
        long request = snapshot.getLong("controllerOpenId");
        if (request > 0) return request == id;
        return tracksTarget
            && matchesTarget(
                snapshot.getInteger("dimension"),
                snapshot.getInteger("x"),
                snapshot.getInteger("y"),
                snapshot.getInteger("z"))
            && (snapshot.getBoolean("open") && "controllerStructure".equals(snapshot.getString("openPurpose"))
                || followsLegacyOpening(snapshot));
    }

    void expectLegacyBaseline(NBTTagCompound snapshot) {
        if (snapshot.getLong("controllerOpenId") == 0) legacySession = snapshot.getString("session");
    }

    boolean hidesLegacyBaseline(NBTTagCompound snapshot) {
        return dismissed && followsLegacyOpening(snapshot);
    }

    private boolean followsLegacyOpening(NBTTagCompound snapshot) {
        return !legacySession.isEmpty() && legacySession.equals(snapshot.getString("session"))
            && HologramState.fullBaseline(snapshot);
    }

    HologramProgress progress() {
        HologramProgress current = HologramClientProgress.controllerOpening(latest);
        return current == null ? waiting.view(System.nanoTime()) : current;
    }

    void dismiss(long id) {
        if (id == latest) dismissed = true;
    }

    boolean canOpenWhileScanning() {
        return tracksTarget && dismissed && HologramBackgroundTasks.scanningControllerOpening(latest);
    }

    boolean accepts(long id) {
        return id > 0 && id == latest && !dismissed;
    }
}
