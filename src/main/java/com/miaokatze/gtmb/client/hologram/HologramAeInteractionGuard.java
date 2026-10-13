package com.miaokatze.gtmb.client.hologram;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C17PacketCustomPayload;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/** Actual socket observations only. Installs after vanilla crafting, before the first AE preview action. */
final class HologramAeInteractionGuard implements AutoCloseable {

    private static final String HANDLER = "gtmb_ae_real_interaction_guard";
    private final Minecraft mc;
    private final Channel channel;
    private final Container realContainer;
    private final int realWindow, heldSlot;
    private final NBTTagCompound inventory;
    private final Set<Integer> originalItemEntities = new HashSet<>();
    private final AtomicLong outbound = new AtomicLong(), custom = new AtomicLong();
    private final AtomicLong clicks = new AtomicLong(), closes = new AtomicLong();
    private boolean closed;

    HologramAeInteractionGuard(Minecraft mc) {
        require(Boolean.getBoolean("gtmb.hologram.realInteractionSmoke"), "real smoke flag required");
        require(mc.thePlayer != null && mc.theWorld != null, "connected real player required");
        this.mc = mc;
        realContainer = mc.thePlayer.openContainer;
        require(realContainer == mc.thePlayer.inventoryContainer, "start with the real player inventory container");
        realWindow = realContainer.windowId;
        heldSlot = mc.thePlayer.inventory.currentItem;
        inventory = inventorySnapshot();
        for (Object entity : mc.theWorld.loadedEntityList)
            if (entity instanceof EntityItem) originalItemEntities.add(((EntityItem) entity).getEntityId());
        channel = mc.thePlayer.sendQueue.getNetworkManager()
            .channel();
        require(channel != null && channel.isActive(), "active real socket required");
        require(
            channel.pipeline()
                .get(HANDLER) == null,
            "duplicate AE transport observer");
        require(
            channel.pipeline()
                .get("packet_handler") != null,
            "native packet handler missing");
        channel.pipeline()
            .addBefore("packet_handler", HANDLER, new ChannelDuplexHandler() {

                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
                    throws Exception {
                    outbound.incrementAndGet();
                    if (message instanceof C0EPacketClickWindow) clicks.incrementAndGet();
                    if (message instanceof C0DPacketCloseWindow) closes.incrementAndGet();
                    if (message instanceof C17PacketCustomPayload || message.getClass()
                        .getName()
                        .equals("cpw.mods.fml.common.network.internal.FMLProxyPacket")) custom.incrementAndGet();
                    // Observational: all packets are forwarded unchanged. A failure is reported on the main thread.
                    super.write(context, message, promise);
                }
            });
    }

    void assertStable(String stage) {
        require(
            !closed && channel.pipeline()
                .get(HANDLER) != null,
            stage + ": observer detached");
        require(
            clicks.get() == 0 && closes.get() == 0,
            stage + ": AE preview emitted native C0E=" + clicks.get() + " C0D=" + closes.get());
        require(
            mc.thePlayer.openContainer == realContainer && realContainer.windowId == realWindow,
            stage + ": real openContainer identity/window changed");
        require(mc.thePlayer.inventory.currentItem == heldSlot, stage + ": selected hand slot changed");
        require(inventory.equals(inventorySnapshot()), stage + ": real inventory, armor or cursor changed");
        for (Object entity : mc.theWorld.loadedEntityList) if (entity instanceof EntityItem) require(
            originalItemEntities.contains(((EntityItem) entity).getEntityId()),
            stage + ": new dropped item entity");
    }

    long customPackets() {
        return custom.get();
    }

    void requireCustomTrafficSince(long before, String stage) {
        assertStable(stage);
        require(custom.get() > before, stage + ": no actual custom packet witnessed");
    }

    NBTTagCompound receipt() {
        NBTTagCompound result = new NBTTagCompound();
        result.setLong("observedOutbound", outbound.get());
        result.setLong("observedCustom", custom.get());
        result.setLong("nativeWindowClicks", clicks.get());
        result.setLong("nativeWindowCloses", closes.get());
        result.setInteger("realWindow", realWindow);
        result.setInteger("heldSlot", heldSlot);
        result.setBoolean("inventoryUnchangedExceptPlannerDraft", inventory.equals(inventorySnapshot()));
        result.setBoolean("serverSpawnAndSourceReceiptRequired", true);
        return result;
    }

    private NBTTagCompound inventorySnapshot() {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList main = new NBTTagList(), armor = new NBTTagList();
        for (int i = 0; i < mc.thePlayer.inventory.mainInventory.length; i++)
            main.appendTag(stack(mc.thePlayer.inventory.mainInventory[i], i == heldSlot));
        for (ItemStack value : mc.thePlayer.inventory.armorInventory) armor.appendTag(stack(value, false));
        result.setTag("main", main);
        result.setTag("armor", armor);
        result.setTag("cursor", stack(mc.thePlayer.inventory.getItemStack(), false));
        return result;
    }

    private static NBTTagCompound stack(ItemStack value, boolean heldProjector) {
        if (value == null) return new NBTTagCompound();
        NBTTagCompound encoded = value.writeToNBT(new NBTTagCompound());
        if (heldProjector && encoded.hasKey("tag", 10)) {
            NBTTagCompound tags = encoded.getCompoundTag("tag");
            // AE edits/saves legitimately persist these two planner-owned fields. All other tags remain exact.
            tags.removeTag("gtitHologramDraft");
            tags.removeTag("gtitHologramPreset");
            if (tags.hasNoTags()) encoded.removeTag("tag");
        }
        return encoded;
    }

    @Override
    public void close() {
        if (!closed) {
            if (channel.pipeline()
                .get(HANDLER) != null)
                channel.pipeline()
                    .remove(HANDLER);
            closed = true;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
