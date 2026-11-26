// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

/** Read carried stock and explicitly request ordinary AE crafting. This grants no build/quote authority. */
public final class HologramMaterialStockWire {

    static final String MARKER = "carriedMaterialStock";
    public static final int MAX_ROWS = 1024;
    private static final HologramRequestGate pending = new HologramRequestGate(2, 32);
    private static final Map<HologramWorkspace.ActionEndpoint, Long> last = new WeakHashMap<>();
    private static volatile java.util.function.BiConsumer<NBTTagCompound, Object> receiver;

    private HologramMaterialStockWire() {}

    public static void listen(java.util.function.BiConsumer<NBTTagCompound, Object> callback) {
        receiver = callback;
    }

    public static boolean request(long id, String tool, int slot, int dimension, int sources, String operation,
        NBTTagList items, String order) {
        if (id <= 0 || items.tagCount() > MAX_ROWS) return false;
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("action", "serverRules");
        tag.setInteger(MARKER, 1);
        tag.setLong("id", id);
        tag.setString("tool", tool);
        tag.setInteger("slot", slot);
        tag.setInteger("dimension", dimension);
        tag.setInteger("sources", sources);
        tag.setString("operation", operation);
        tag.setTag("items", items.copy());
        tag.setString("order", order);
        return HologramNetwork.sendStockNativeAction(tag);
    }

    static boolean valid(NBTTagCompound tag) {
        if (tag == null || tag.func_150296_c()
            .size() != 10
            || tag.getInteger(MARKER) != 1
            || !"serverRules".equals(tag.getString("action"))
            || !tag.hasKey("id", 4)
            || tag.getLong("id") <= 0
            || !tag.hasKey("items", 9)
            || tag.getTagList("items", 10)
                .tagCount() > MAX_ROWS
            || !tag.hasKey("slot", 3)
            || tag.getInteger("slot") < 0
            || tag.getInteger("slot") > 8
            || !tag.hasKey("dimension", 3)
            || !tag.hasKey("sources", 3)
            || decode(tag.getInteger("sources")) == null) return false;
        try {
            if (!UUID.fromString(tag.getString("tool"))
                .toString()
                .equals(tag.getString("tool"))) return false;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (("sources".equals(tag.getString("operation")) || "cancelOrder".equals(tag.getString("operation")))
            && tag.getTagList("items", 10)
                .tagCount() != 0)
            return false;
        return "stock".equals(tag.getString("operation")) || "order".equals(tag.getString("operation"))
            || "cancelOrder".equals(tag.getString("operation"))
            || "sources".equals(tag.getString("operation"));
    }

    public static HologramMaterials.Settings decode(int mask) {
        if (mask < 0 || mask >= 64 || (mask >>> 3 & 3) > 2) return null;
        var settings = new HologramMaterials.Settings();
        settings.main = (mask & 1) != 0;
        settings.containers = (mask & 2) != 0;
        settings.me = (mask & 4) != 0;
        settings.priority = mask >>> 3 & 3;
        settings.recoveryDestination = mask >>> 5 & 1;
        return settings;
    }

    static void enqueue(NBTTagCompound tag, HologramWorkspace.ActionEndpoint endpoint) {
        if (endpoint == null || !valid(tag)) return;
        var lease = pending.acquire(endpoint);
        if (lease == null) return;
        ServerTaskScheduler.scheduleServerTask(() -> HologramNetwork.runQueuedAction(endpoint, lease, () -> {
            synchronized (last) {
                if (tag.getLong("id") <= last.getOrDefault(endpoint, 0L)) return;
                last.put(endpoint, tag.getLong("id"));
            }
            NBTTagCompound reply = new NBTTagCompound();
            reply.setInteger(MARKER, 1);
            reply.setLong("id", tag.getLong("id"));
            try {
                inspect(endpoint, tag, reply);
            } catch (RuntimeException | LinkageError unavailable) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Carried material query failed", unavailable);
                reply.setString("statusKey", "stock.sources_unavailable");
            }
            HologramNetwork.sendPresetQuote(endpoint, reply);
        }));
    }

    private static void inspect(HologramWorkspace.ActionEndpoint endpoint, NBTTagCompound tag, NBTTagCompound reply) {
        var player = endpoint.player;
        var tool = player.getHeldItem();
        if (player.isDead || player.dimension != tag.getInteger("dimension")
            || player.inventory.currentItem != tag.getInteger("slot")
            || !ItemNekoHologramProjector.isProjector(tool)
            || !tool.hasTagCompound()
            || !tag.getString("tool")
                .equals(
                    tool.getTagCompound()
                        .getString(HologramSessionKeys.TOOL_ID))) {
            reply.setString("statusKey", "stock.owner_changed");
            return;
        }
        if ("sources".equals(tag.getString("operation"))) {
            if (HologramWorkspace.legacyConstructionRefusal(player) != null || HologramDemolition.active(player)) {
                reply.setString("statusKey", "stock.sources_busy");
                return;
            }
            var selected = decode(tag.getInteger("sources"));
            selected.recoveryDestination = HologramMaterials.read(tool).recoveryDestination;
            HologramMaterials.write(tool, selected);
            player.inventory.markDirty();
            if (player.inventoryContainer != null) player.inventoryContainer.detectAndSendChanges();
            reply.setBoolean("preferencesAccepted", true);
            return;
        }
        if ("cancelOrder".equals(tag.getString("operation"))) {
            var current = HologramAeCraftingOrders.snapshot(player);
            if (!tag.getString("order")
                .isEmpty() && tag.getString("order")
                    .equals(current.getString("orderId"))) {
                HologramAeCraftingOrders.cancel(player, "AE order cancelled by requester");
                reply.setString("statusKey", "stock.ae_cancelled");
            }
            reply.setTag("materialOrder", HologramAeCraftingOrders.snapshot(player));
            return; // Stopping an owned order never opens an unavailable material provider.
        }
        var settings = decode(tag.getInteger("sources"));
        var stock = new HologramMaterials.Context(player, settings, player.inventory.currentItem);
        NBTTagList items = requestedItems(tag.getTagList("items", 10));
        NBTTagList rows = quoteItems(items, stock);
        reply.setTag("rows", rows);
        String operation = tag.getString("operation");
        if ("order".equals(operation)) {
            if (!settings.me) reply.setString("statusKey", "stock.enable_ae");
            else {
                boolean alreadyActive = HologramAeCraftingOrders.active(player);
                String diagnostic = HologramAeCraftingOrders.submitRequestedItems(player, settings, items);
                reply.setString(
                    "statusKey",
                    HologramAeCraftingOrders.active(player) ? alreadyActive ? "stock.ae_running" : "stock.ae_received"
                        : "stock.ae_unavailable");
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info("AE material order: {}", diagnostic);
            }
        }
        reply.setTag("materialOrder", HologramAeCraftingOrders.snapshot(player));
        reply.setBoolean("stockKnown", true);
    }

    /** A manual AE request carries only item identities and desired quantities, never client missing/quote fields. */
    static NBTTagList requestedItems(NBTTagList supplied) {
        if (supplied.tagCount() > MAX_ROWS) throw new IllegalArgumentException("Too many requested items");
        NBTTagList result = new NBTTagList();
        java.util.Set<HologramBlueprintResources.ExactKey> seen = new java.util.HashSet<>();
        for (int i = 0; i < supplied.tagCount(); i++) {
            var input = supplied.getCompoundTagAt(i);
            long amount = input.getLong("required");
            var item = HologramMaterialQuote.readItem(input);
            if (!input.hasKey("required", 4) || amount <= 0 || item == null || !seen.add(HologramValues.stackKey(item)))
                throw new IllegalArgumentException("Invalid requested item");
            result.appendTag(HologramMaterialQuote.itemRow(item, amount, 0));
        }
        return result;
    }

    static NBTTagList quoteItems(NBTTagList items, HologramMaterials.Context stock) {
        NBTTagList result = new NBTTagList();
        for (int i = 0; i < items.tagCount(); i++) {
            var row = items.getCompoundTagAt(i);
            var item = HologramMaterialQuote.readItem(row);
            var fresh = HologramMaterialQuote
                .itemRow(item, row.getLong("required"), stock.countLong(item, Long.MAX_VALUE));
            fresh.setBoolean("availableKnown", true);
            result.appendTag(fresh);
        }
        return result;
    }

    static void receive(NBTTagCompound tag, Object inbound) {
        var callback = receiver;
        if (callback != null && tag.getInteger(MARKER) == 1
            && tag.getLong("id") > 0
            && tag.getTagList("rows", 10)
                .tagCount() <= MAX_ROWS)
            callback.accept(tag, inbound);
    }

    static void reset() {
        pending.clear();
        synchronized (last) {
            last.clear();
        }
    }
}
