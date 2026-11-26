// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.hologram.HologramMaterialStockWire;
import com.miaokatze.gtmb.hologram.HologramMaterials;
import com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy;

/** A disposable stock observer. Replies are scoped to one pane, player, world and connection. */
final class HologramClientMaterialStock {

    private static final AtomicLong sequence = new AtomicLong();
    private static final ConcurrentHashMap<Long, HologramClientMaterialStock> pending = new ConcurrentHashMap<>();
    private static volatile SourceChange sourceChange;
    static {
        HologramMaterialStockWire.listen((reply, inbound) -> {
            var change = sourceChange;
            if (change != null && change.id == reply.getLong("id") && change.connection == inbound) {
                change.reply = reply;
                return;
            }
            var owner = pending.remove(reply.getLong("id"));
            if (owner != null && owner.connection == inbound) owner.inbox.set(reply);
        });
    }

    private static final class SourceChange {

        final long id = sequence.incrementAndGet(), deadline = System.nanoTime() + 5_000_000_000L;
        final Object world, connection, player;
        final String tool;
        final int slot, mask;
        volatile NBTTagCompound reply;

        SourceChange(Minecraft client, HologramMaterials.Settings settings) {
            world = client.theWorld;
            connection = client.getNetHandler();
            player = client.thePlayer;
            tool = HologramLibraryClient.projectorId(client.thePlayer.getHeldItem());
            slot = client.thePlayer.inventory.currentItem;
            mask = HologramProjectedMainPolicy.structurePlacementKey(settings, true);
        }
    }

    /** A source preference is a held-tool operation, not a blueprint edit or a stock-query side effect. */
    static boolean changePreferences(NBTTagCompound request) {
        Minecraft client = Minecraft.getMinecraft();
        if (client == null || client.thePlayer == null || client.theWorld == null) return false;
        var settings = HologramMaterials.read(client.thePlayer.getHeldItem());
        int before = HologramProjectedMainPolicy.structurePlacementKey(settings, true);
        if (request.hasKey("materialMain")) settings.main = request.getBoolean("materialMain");
        if (request.hasKey("materialContainers")) settings.containers = request.getBoolean("materialContainers");
        if (request.hasKey("materialMe")) settings.me = request.getBoolean("materialMe");
        if (request.hasKey("materialPriority")) settings.priority = request.getInteger("materialPriority");
        if (before == HologramProjectedMainPolicy.structurePlacementKey(settings, true)) return false;
        if (preferencesPending()) return true;
        var change = new SourceChange(client, settings);
        if (HologramClientStockNative.canonicalTool(change.tool) == null || change.mask < 0) return true;
        sourceChange = change;
        try {
            if (!HologramMaterialStockWire.request(
                change.id,
                change.tool,
                change.slot,
                client.thePlayer.dimension,
                change.mask,
                "sources",
                new NBTTagList(),
                "")) sourceChange = null;
        } catch (RuntimeException | LinkageError unavailable) {
            sourceChange = null;
        }
        return true;
    }

    static boolean preferencesPending() {
        var change = sourceChange;
        if (change == null) return false;
        var client = Minecraft.getMinecraft();
        if (client == null || client.thePlayer != change.player
            || client.theWorld != change.world
            || client.getNetHandler() != change.connection
            || client.thePlayer.inventory.currentItem != change.slot
            || !change.tool.equals(HologramLibraryClient.projectorId(client.thePlayer.getHeldItem()))) {
            sourceChange = null;
            return false;
        }
        if (change.reply != null && change.reply.getBoolean("preferencesAccepted")
            && change.mask == HologramProjectedMainPolicy
                .structurePlacementKey(HologramMaterials.read(client.thePlayer.getHeldItem()), true)) {
            sourceChange = null;
            return false;
        }
        if (change.reply != null && !change.reply.getBoolean("preferencesAccepted")
            || System.nanoTime() >= change.deadline) {
            sourceChange = null;
            HologramClient.selectionNotice(
                HologramUiText.projectorOr("stock.sources_unavailable", "Material sources unavailable"));
            return false;
        }
        return true;
    }

    private final AtomicReference<NBTTagCompound> inbox = new AtomicReference<>();
    private Object world, connection, player;
    private String tool;
    private int slot;
    private long request, sent, refreshed;
    private NBTTagCompound observed;
    private boolean closed;
    private String message = "";

    void close() {
        closed = true;
        pending.remove(request, this);
        inbox.set(null);
    }

    void tick(NBTTagCompound theory, HologramMaterials.Settings settings) {
        if (closed) return;
        var client = Minecraft.getMinecraft();
        if (client == null || client.thePlayer == null || client.theWorld == null) return;
        if (world == null) {
            String identity = HologramLibraryClient.projectorId(client.thePlayer.getHeldItem());
            if (HologramClientStockNative.canonicalTool(identity) == null || client.getNetHandler() == null) return;
            world = client.theWorld;
            connection = client.getNetHandler();
            player = client.thePlayer;
            slot = client.thePlayer.inventory.currentItem;
            tool = HologramLibraryClient.projectorId(client.thePlayer.getHeldItem());
        }
        if (!current(client)) {
            close();
            return;
        }
        NBTTagCompound reply = inbox.getAndSet(null);
        if (reply != null && reply.getLong("id") == request) {
            request = 0;
            observed = reply.getBoolean("stockKnown") ? reply : null;
            if (reply.hasKey("statusKey"))
                message = HologramUiText.projectorOr(reply.getString("statusKey"), "Material sources unavailable");
            else if (reply.hasKey("status")) message = reply.getString("status");
            else message = "";
        }
        long now = System.nanoTime();
        if (request != 0 && now - sent > 5_000_000_000L) {
            pending.remove(request, this);
            request = 0;
            observed = null;
            message = HologramUiText.projectorOr("stock.sources_unavailable", "Material sources unavailable");
        }
        if (request == 0 && now - refreshed >= 1_000_000_000L) send(theory, settings, "stock");
    }

    private boolean current(Minecraft client) {
        return world == client.theWorld && connection == client.getNetHandler()
            && player == client.thePlayer
            && slot == client.thePlayer.inventory.currentItem
            && tool.equals(HologramLibraryClient.projectorId(client.thePlayer.getHeldItem()));
    }

    boolean ready() {
        return !closed && observed != null && request == 0;
    }

    String message() {
        if (observed != null && observed.getCompoundTag("materialOrder")
            .hasKey("state")) {
            var order = observed.getCompoundTag("materialOrder");
            return HologramUiText.projectorOr(
                "stock.ae_progress",
                "AE %1$s: %2$s / %3$s",
                HologramUiText.projectorOr("stock.ae_" + order.getString("state"), order.getString("state")),
                order.getLong("done"),
                order.getLong("total"));
        }
        return message;
    }

    boolean ordering() {
        return observed != null && observed.getCompoundTag("materialOrder")
            .getBoolean("active");
    }

    String orderLabel() {
        return HologramUiText.projectorOr(
            ordering() ? "stock.cancel_ae_order" : "stock.order_ae",
            ordering() ? "Cancel AE order" : "Order missing items from AE");
    }

    void refresh() {
        observed = null;
        refreshed = 0;
    }

    void order(NBTTagCompound theory, HologramMaterials.Settings settings) {
        if (ready()) send(theory, settings, ordering() ? "cancelOrder" : "order");
    }

    private void send(NBTTagCompound theory, HologramMaterials.Settings settings, String operation) {
        var client = Minecraft.getMinecraft();
        if (closed || !current(client) || request != 0) return;
        NBTTagList requested = new NBTTagList();
        NBTTagList rows = theory.getTagList("rows", 10);
        java.util.Map<String, NBTTagCompound> identities = new java.util.LinkedHashMap<>();
        for (int i = 0; !"cancelOrder".equals(operation) && i < rows.tagCount(); i++) {
            var row = rows.getCompoundTagAt(i);
            if (!"item".equals(row.getString("kind"))) continue;
            var item = HologramMaterialQuote.readItem(row);
            if (item == null) continue;
            BigInteger amount = new BigInteger(HologramMaterialReview.amount(row, "required"));
            if (amount.signum() <= 0) continue;
            amount = amount.min(BigInteger.valueOf(Long.MAX_VALUE));
            var input = HologramMaterialQuote.itemRow(item, amount.longValueExact(), 0);
            String identity = input.getString("itemRegistry") + ":" + input.getCompoundTag("item");
            var prior = identities.get(identity);
            if (prior == null) identities.put(identity, input);
            else {
                long total = amount.add(BigInteger.valueOf(prior.getLong("required")))
                    .min(BigInteger.valueOf(Long.MAX_VALUE))
                    .longValueExact();
                prior.setLong("required", total);
            }
        }
        if (identities.size() > HologramMaterialStockWire.MAX_ROWS) {
            message = HologramUiText.projectorOr("stock.sources_unavailable", "Material sources unavailable");
            return;
        }
        identities.values()
            .forEach(requested::appendTag);
        long id = sequence.incrementAndGet();
        request = id;
        sent = refreshed = System.nanoTime();
        pending.put(id, this);
        int mask = HologramProjectedMainPolicy.structurePlacementKey(settings, true);
        String order = observed == null ? ""
            : observed.getCompoundTag("materialOrder")
                .getString("orderId");
        try {
            if (HologramMaterialStockWire
                .request(id, tool, slot, client.thePlayer.dimension, mask, operation, requested, order)) return;
        } catch (RuntimeException | LinkageError unavailable) {
            message = HologramUiText.projectorOr("stock.sources_unavailable", "Material sources unavailable");
        }
        pending.remove(id, this);
        request = 0;
    }

    NBTTagCompound overlay(NBTTagCompound local) {
        if (observed == null) return local;
        var output = (NBTTagCompound) local.copy();
        var rows = output.getTagList("rows", 10);
        var stock = observed.getTagList("rows", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            var row = rows.getCompoundTagAt(i);
            if (!"item".equals(row.getString("kind"))) continue;
            var identity = HologramMaterialQuote.readItem(row);
            for (int j = 0; j < stock.tagCount(); j++) {
                var counted = stock.getCompoundTagAt(j);
                if (!net.minecraft.item.ItemStack.areItemStacksEqual(identity, HologramMaterialQuote.readItem(counted)))
                    continue;
                long available = counted.getLong("available");
                row.setBoolean("availableKnown", true);
                row.setLong("available", available);
                row.setString(
                    "missingExact",
                    new BigInteger(HologramMaterialReview.amount(row, "required"))
                        .subtract(BigInteger.valueOf(available))
                        .max(BigInteger.ZERO)
                        .toString());
                break;
            }
        }
        output.setBoolean("serverStock", true);
        return output;
    }
}
