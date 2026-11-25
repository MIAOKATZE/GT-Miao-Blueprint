// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;

/** A preset decision needs the actor's stock by native hatch type, never a second projection or a world census. */
public final class HologramNativePresetStock {

    static final String MARKER = "nativePresetStock";
    public static final int MAX_IDS = 256;
    private static final HologramRequestGate pending = new HologramRequestGate(2, 32);
    private static final Map<HologramWorkspace.ActionEndpoint, Long> last = new WeakHashMap<>();
    private static final String[] INTS = { "dimension", "x", "y", "z", "controller", "facing", "slot", "sources" };
    private static volatile BiConsumer<Reply, Object> receiver;

    private HologramNativePresetStock() {}

    @Desugar
    public record Reply(long id, int[] ids, int[] counts, String reason) {}

    public static void setReceiver(BiConsumer<Reply, Object> value) {
        receiver = value;
    }

    public static boolean request(long id, UUID tool, int dimension, int x, int y, int z, int controller, int facing,
        int slot, int sources, int[] hatchIds) {
        if (id <= 0 || tool == null
            || (tool.getLeastSignificantBits() == 0 && tool.getMostSignificantBits() == 0)
            || !validIds(hatchIds)
            || sources < 0
            || slot < 0
            || slot > 8
            || controller <= 0
            || facing < 0) return false;
        NBTTagCompound tag = new NBTTagCompound();
        // Inert on old peers. The explicit marker is consumed before generic workspace dispatch.
        tag.setString("action", "serverRules");
        tag.setInteger(MARKER, 1);
        tag.setLong("id", id);
        tag.setLong("toolMost", tool.getMostSignificantBits());
        tag.setLong("toolLeast", tool.getLeastSignificantBits());
        tag.setInteger("dimension", dimension);
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setInteger("controller", controller);
        tag.setInteger("facing", facing);
        tag.setInteger("slot", slot);
        tag.setInteger("sources", sources);
        tag.setIntArray("ids", hatchIds.clone());
        return HologramNetwork.sendStockNativeAction(tag);
    }

    static boolean validIds(int[] ids) {
        if (ids == null || ids.length == 0 || ids.length > MAX_IDS) return false;
        Set<Integer> seen = new HashSet<>();
        for (int id : ids) if (id <= 0 || id > Short.MAX_VALUE || !seen.add(id)) return false;
        return true;
    }

    static boolean validRequest(NBTTagCompound tag) {
        if (tag == null || tag.func_150296_c()
            .size() != 14
            || !tag.hasKey(MARKER, 3)
            || tag.getInteger(MARKER) != 1
            || !"serverRules".equals(tag.getString("action"))
            || !tag.hasKey("id", 4)
            || tag.getLong("id") <= 0
            || !tag.hasKey("toolMost", 4)
            || !tag.hasKey("toolLeast", 4)
            || (tag.getLong("toolMost") == 0 && tag.getLong("toolLeast") == 0)
            || !tag.hasKey("ids", 11)) return false;
        for (String key : INTS) if (!tag.hasKey(key, 3)) return false;
        return tag.getInteger("controller") > 0 && tag.getInteger("controller") <= Short.MAX_VALUE
            && tag.getInteger("facing") >= 0
            && tag.getInteger("sources") >= 0
            && tag.getInteger("sources") < 64
            && tag.getInteger("slot") >= 0
            && tag.getInteger("slot") <= 8
            && validIds(tag.getIntArray("ids"));
    }

    static void enqueue(NBTTagCompound tag, HologramWorkspace.ActionEndpoint endpoint) {
        if (endpoint == null || !validRequest(tag)) return;
        long id = tag.getLong("id");
        HologramRequestGate.Lease lease = pending.acquire(endpoint);
        if (lease == null) {
            reject(endpoint, id, "busy");
            return;
        }
        ServerTaskScheduler.scheduleServerTask(() -> HologramNetwork.runQueuedAction(endpoint, lease, () -> {
            synchronized (last) {
                if (id <= last.getOrDefault(endpoint, 0L)) {
                    reject(endpoint, id, "invalid");
                    return;
                }
                last.put(endpoint, id);
            }
            try {
                quote(endpoint, tag);
            } catch (RuntimeException | LinkageError failure) {
                GTMiaoBlueprint.LOG.warn("Native preset stock query {} failed", id, failure);
                reject(endpoint, id, "unavailable");
            }
        }));
    }

    private static void quote(HologramWorkspace.ActionEndpoint endpoint, NBTTagCompound tag) {
        var player = endpoint.player;
        var world = endpoint.world;
        long id = tag.getLong("id");
        int slot = tag.getInteger("slot");
        ItemStack held = player.getHeldItem();
        String tool = new UUID(tag.getLong("toolMost"), tag.getLong("toolLeast")).toString();
        if (player.isDead || player.dimension != tag.getInteger("dimension")
            || player.inventory.currentItem != slot
            || !ItemNekoHologramProjector.isProjector(held)
            || !held.hasTagCompound()
            || !tool.equals(
                held.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID))) {
            reject(endpoint, id, "owner");
            return;
        }
        int x = tag.getInteger("x"), y = tag.getInteger("y"), z = tag.getInteger("z");
        int controller = tag.getInteger("controller");
        if (!HologramWorldFlow.worldPosition(x, y, z, world.getHeight())
            || player.getDistanceSq(x + .5, y + .5, z + .5) > 64
            || !world.blockExists(x, y, z)
            || world.getBlock(x, y, z) != GregTechAPI.sBlockMachines
            || !(world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity tile)
            || tile.isInvalid()
            || tile.getMetaTileID() != controller
            || controller >= GregTechAPI.METATILEENTITIES.length
            || !(tile.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>machine)
            || !(GregTechAPI.METATILEENTITIES[controller] instanceof MTEEnhancedMultiBlockBase<?>registered)
            || registered.getClass() != machine.getClass()
            || machine.getExtendedFacing()
                .ordinal() != tag.getInteger("facing")
            || !machine.isAccessAllowed(player)
            || (tile.privateAccess() && !endpoint.actor.equals(tile.getOwnerUuid())
                && !player.getCommandSenderName()
                    .equals(tile.getOwnerName()))) {
            reject(endpoint, id, "controller");
            return;
        }
        var settings = HologramMaterials.read(held);
        if (HologramProjectedMainPolicy.settingsKey(settings) != tag.getInteger("sources")) {
            reject(endpoint, id, "sources");
            return;
        }
        int[] ids = tag.getIntArray("ids");
        for (int hatch : ids) if (hatch >= GregTechAPI.METATILEENTITIES.length
            || !(GregTechAPI.METATILEENTITIES[hatch] instanceof MTEHatch)) {
                reject(endpoint, id, "invalid");
                return;
            }
        var stock = new HologramMaterials.Context(player, settings, slot);
        int[] counts = new int[ids.length];
        for (int i = 0; i < ids.length; i++)
            counts[i] = stock.count(new ItemStack(GregTechAPI.sBlockMachines, 1, ids[i]), Integer.MAX_VALUE);
        NBTTagCompound reply = header(id);
        reply.setIntArray("ids", ids);
        reply.setIntArray("counts", counts);
        HologramNetwork.sendPresetQuote(endpoint, reply);
    }

    private static NBTTagCompound header(long id) {
        NBTTagCompound reply = new NBTTagCompound();
        reply.setInteger(MARKER, 1);
        reply.setLong("id", id);
        return reply;
    }

    private static void reject(HologramWorkspace.ActionEndpoint endpoint, long id, String reason) {
        var reply = header(id);
        reply.setString("reason", reason);
        HologramNetwork.sendPresetQuote(endpoint, reply);
    }

    static Reply readReply(NBTTagCompound tag) {
        if (tag == null || !tag.hasKey(MARKER, 3)
            || tag.getInteger(MARKER) != 1
            || !tag.hasKey("id", 4)
            || tag.getLong("id") <= 0) return null;
        long id = tag.getLong("id");
        if (tag.hasKey("reason", 8)) {
            String reason = tag.getString("reason");
            return tag.func_150296_c()
                .size() == 3 && !reason.isEmpty()
                && reason.length() <= 16 ? new Reply(id, new int[0], new int[0], reason) : null;
        }
        if (tag.func_150296_c()
            .size() != 4 || !tag.hasKey("ids", 11)
            || !tag.hasKey("counts", 11)) return null;
        int[] ids = tag.getIntArray("ids"), counts = tag.getIntArray("counts");
        if (!validIds(ids) || counts.length != ids.length) return null;
        for (int count : counts) if (count < 0) return null;
        return new Reply(id, ids, counts, "");
    }

    static void receive(NBTTagCompound tag, Object inbound) {
        var sink = receiver;
        Reply reply = sink == null ? null : readReply(tag);
        if (reply != null) sink.accept(reply, inbound);
    }

    static void reset() {
        pending.clear();
        synchronized (last) {
            last.clear();
        }
    }
}
