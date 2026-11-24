// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalFrame;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;

/** Borrow the existing owned one-stone fixture. Never captures/suppresses or sends a packet. */
final class HologramLocalMaterialQuietCase {

    static final String FLAG = "gtmb.testlab.localPreviewStagesMaterialsOnly";
    private static final int WINDOW = 12;

    @FunctionalInterface
    interface WorldUse {
        void use() throws Exception;
    }

    final Map<String, Object> evidence = new LinkedHashMap<>();
    private final WorldClient world;
    private final EntityClientPlayerMP player;
    private final NetHandlerPlayClient connection;
    private final HologramState ghost;
    private final ItemStack tool;
    private final int slot;
    private final int[] anchor;
    private final NBTTagList stock;
    private final boolean metricsWereEnabled;
    private final long idleStarted;
    private final Map<String, Long> before;
    private HologramLibraryMaterialReviewScreen screen;
    private HologramMaterialConfirmationDialog dialog;
    private LocalFrame frame;
    private long browseStarted, backTick;
    private int step;

    HologramLocalMaterialQuietCase(Minecraft mc, int[] anchor, Map<String, Integer> writes) {
        player = mc.thePlayer;
        world = mc.theWorld;
        connection = mc.getNetHandler();
        tool = player.getHeldItem();
        slot = player.inventory.currentItem;
        ghost = HologramLocalPlacement.INSTANCE.display();
        this.anchor = anchor.clone();
        stock = inventory();
        current(mc);
        require(writes.isEmpty() && ghost.cells.size() == 1 && mc.currentScreen == null,
            "Expected silent locked one-stone source before opening materials");
        metricsWereEnabled = HologramRuntimeMetrics.isEnabled();
        HologramRuntimeMetrics.setEnabled(true);
        before = counters();
        idleStarted = world.getTotalWorldTime();
        evidence.put("observer", "pass-through FML application writes and production GTMB C2S codec packet/byte counts");
        evidence.put("idleBaselineStart", before);
    }

    boolean step(Minecraft mc, Map<String, Integer> writes, WorldUse use) throws Exception {
        current(mc);
        Map<String, Long> now = counters();
        require(writes.isEmpty() && before.equals(now), "Unexpected GTMB C2S during local material observation: " + writes);
        long tick = world.getTotalWorldTime();
        if (step == 0) {
            if (tick - idleStarted < WINDOW) return false;
            require(mc.currentScreen == null, "World view retired before the material browse");
            evidence.put("idleBaselineEnd", now);
            use.use();
            require(mc.currentScreen instanceof HologramLibraryMaterialReviewScreen,
                "Ordinary second use did not open the locked blueprint local materials");
            screen = (HologramLibraryMaterialReviewScreen) mc.currentScreen;
            var field = HologramLibraryMaterialReviewScreen.class.getDeclaredField("dialog");
            field.setAccessible(true);
            dialog = (HologramMaterialConfirmationDialog) field.get(screen);
            browseStarted = tick;
            step++;
        } else if (step == 1) {
            require(mc.currentScreen == screen, "Local material screen lost ownership");
            var drawn = dialog.probeState(null);
            if (!drawn.getBoolean("open") || drawn.getLong("drawnFrames") == 0) return false;
            frame = readDescription();
            require(drawn.getInteger("rows") == 1, "The ordinary local material row was not presented");
            userClick(247, 0);
            evidence.put("refreshNativeHit", true);
            step++;
        } else if (step == 2) {
            require(mc.currentScreen == screen, "Material refresh unexpectedly replaced the local screen");
            LocalFrame refreshed = readDescription();
            if (refreshed == frame) return false;
            evidence.put("refreshedWithNoQuote", true);
            userClick(54, 1);
            evidence.put("backNativeHit", true);
            step++;
        } else if (step == 3) {
            if (mc.currentScreen == screen) return false;
            require(mc.currentScreen == null, "Back did not return to the same world preview");
            backTick = tick;
            step++;
        } else {
            require(mc.currentScreen == null, "World preview unexpectedly replaced after Back");
            if (tick - backTick < WINDOW) return false;
            evidence.put("browseAndAfterBackEnd", now);
            evidence.put("idleTicks", browseStarted - idleStarted);
            evidence.put("browseAndAfterBackTicks", tick - browseStarted);
            evidence.put("sourceCells", 1);
            evidence.put("visibleSameItemCount", 1);
            evidence.put("ownerAnchorAndInventoryUnchanged", true);
            evidence.put("gtmbC2SWrites", writes);
            return true;
        }
        return false;
    }

    void close() {
        if (!metricsWereEnabled) HologramRuntimeMetrics.setEnabled(false);
    }

    private void current(Minecraft mc) {
        require(mc.theWorld == world && mc.thePlayer == player && connection != null && mc.getNetHandler() == connection
            && connection.getNetworkManager().isChannelOpen() && player.inventory.currentItem == slot
            && player.getHeldItem() == tool && !player.isDead && !player.capabilities.isCreativeMode
            && stock.equals(inventory()), "Owned material browse world, connection, tool or stock changed");
        var placement = HologramLocalPlacement.INSTANCE;
        require(placement.display() == ghost && placement.locked() && !placement.submitting()
            && Arrays.equals(anchor, placement.anchor()) && !HologramLibraryClient.INSTANCE.busy(),
            "Owned local ghost changed or started an explicit submission");
    }

    private NBTTagList inventory() {
        return player.inventory.writeToNBT(new NBTTagList());
    }

    private LocalFrame readDescription() {
        LocalFrame local = screen.localFrame();
        require(local != null && local.rows().hasNoTags() && local.ready()
            && local.description().size() == 1 && local.status().contains("施工用量未核算"),
            "Expected a rendered description, not authoritative material quote");
        var row = local.description().get(0);
        require(row.blueprintCells() == 1 && Long.valueOf(1).equals(row.visibleBackpackCount())
            && Item.itemRegistry.getNameForObject(Item.getItemFromBlock(Blocks.stone)).equals(row.identity().registry()) && row.identity().damage() == 0,
            "Expected one recorded ordinary stone and one observed ordinary stone in the backpack");
        return local;
    }

    private void userClick(int id, int child) {
        require(id == 247 && child == 0 || id == 54 && child == 1, "Only Refresh and Back are permitted");
        var mui = screen.getScreen();
        int[] point = dialog.controlPoint(id);
        require(point != null, "Expected the native material control to have drawn: " + id);
        mui.getContext().updateState(point[0], point[1], 0);
        mui.onFrameUpdate();
        ModularPanel panel = mui.getPanelManager().getOpenPanels().stream()
            .filter(p -> "gtmb_local_material_review".equals(p.getName())).findFirst()
            .orElseThrow(() -> new IllegalStateException("The material dialog is not the attached native panel"));
        List<?> buttons = panel.getChildren().stream().filter(ButtonWidget.class::isInstance)
            .collect(java.util.stream.Collectors.toList());
        require(buttons.size() == 3 && panel.getHovering().stream().anyMatch(p -> p.getElement() == buttons.get(child)),
            "Native click did not hit the exact allowed control: " + id);
        HologramMuiProbeInput.click(screen, point[0], point[1], 0);
    }

    private static Map<String, Long> counters() {
        NBTTagCompound snapshot = HologramRuntimeMetrics.snapshot();
        require(snapshot.getBoolean("enabled"), "GTMB production codec instrumentation is disabled");
        NBTTagCompound network = snapshot.getCompoundTag("network");
        require(!network.hasNoTags(), "GTMB production codec counters are missing");
        Map<String, Long> result = new java.util.TreeMap<>();
        for (Object key : network.func_150296_c()) {
            NBTTagCompound row = network.getCompoundTag((String) key);
            for (String field : new String[] { "c2sTxPackets", "c2sTxBytes", "c2sRxPackets", "c2sRxBytes" }) {
                require(row.hasKey(field, 4), "GTMB production codec counter is missing: " + field);
                result.put(key + "." + field, row.getLong(field));
            }
        }
        return result;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
