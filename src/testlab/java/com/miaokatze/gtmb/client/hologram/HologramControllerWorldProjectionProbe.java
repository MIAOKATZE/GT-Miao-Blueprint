// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/** TestLab observer only: uses the ordinary close lifecycle and waits for the normal Forge world renderer. */
final class HologramControllerWorldProjectionProbe {

    private static final String FLAG = "gtmb.testlab.localPreviewStagesWorldProjection";
    private static final long LIMIT_NANOS = 25_000_000_000L;

    private final HologramState target;
    private final WorldClient world;
    private final EntityClientPlayerMP actor;
    private final NetHandlerPlayClient connection;
    private final TileEntity tile;
    private final ItemStack projector;
    private final int slot;
    private final int x, y, z;
    private final long previousGeneration, closedAt;
    private boolean passed;
    private final Map<String, Object> report = new LinkedHashMap<>();

    static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    HologramControllerWorldProjectionProbe(Minecraft mc, HologramScreen screen, Map<String, Object> parent) {
        parent.put("controllerWorldProjection", report);
        report.put("passed", false);
        require(
            Boolean.getBoolean("gtmb.hologram.previewCaptureWitness"),
            "Enable gtmb.hologram.previewCaptureWitness for real GL submission evidence");
        require(HologramClient.worldPreview, "World projection is disabled in the TestLab profile");
        require(
            screen == mc.currentScreen && screen.localControllerStandard(),
            "The live controller workbench must be open");
        target = screen.ui.session.state();
        require(
            target.data.getBoolean("projectionDataComplete") && !target.data.getBoolean("capturePending")
                && target.data.getInteger("siteMissing") > 0
                && !target.cells.isEmpty(),
            "The live controller must have a published and incomplete structure");
        world = mc.theWorld;
        actor = mc.thePlayer;
        connection = mc.getNetHandler();
        require(world != null && actor != null && connection != null, "Missing live client context");
        x = target.data.getInteger("x");
        y = target.data.getInteger("y");
        z = target.data.getInteger("z");
        tile = world.getTileEntity(x, y, z);
        require(tile != null && !tile.isInvalid(), "Missing live controller tile");
        slot = actor.inventory.currentItem;
        var held = actor.getHeldItem();
        require(ItemNekoHologramProjector.isProjector(held), "Ordinary projector must still be held");
        projector = held.copy();
        var previous = MiaoVisonLiteRenderer.lastWorldRenderWitness();
        previousGeneration = previous == null ? 0 : previous.generation;
        report.put("anchor", new int[] { x, y, z });
        report.put("targetCells", target.cells.size());
        report.put("missingAtClose", target.data.getInteger("siteMissing"));
        closedAt = System.nanoTime();
        mc.displayGuiScreen(null);
    }

    boolean step(Minecraft mc) {
        if (passed) return true;
        require(
            mc.theWorld == world && mc.thePlayer == actor
                && mc.getNetHandler() == connection
                && world.getTileEntity(x, y, z) == tile
                && !tile.isInvalid()
                && actor.inventory.currentItem == slot
                && ItemStack.areItemStacksEqual(projector, actor.getHeldItem()),
            "Live world, controller, connection or held projector changed before the world witness");
        var witness = MiaoVisonLiteRenderer.lastWorldRenderWitness();
        if (witness != null && witness.generation > previousGeneration) {
            report.put("lastDrawSameSnapshot", witness.state == target);
            report.put("lastDrawLists", witness.lists);
            report.put("lastDrawGeometry", witness.geometry);
            report.put("lastDrawGhosts", witness.ghosts);
            if (mc.currentScreen == null && witness.state == target
                && !witness.moving
                && witness.anchorX == x
                && witness.anchorY == y
                && witness.anchorZ == z
                && witness.lists > 0
                && witness.geometry > 0
                && witness.ghosts > 0) {
                report.put("closeToWorldDrawNanos", System.nanoTime() - closedAt);
                report.put("passed", true);
                passed = true;
                return true;
            }
        }
        require(
            System.nanoTime() - closedAt < LIMIT_NANOS,
            "No real controller ghost world draw after ordinary GUI close");
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
