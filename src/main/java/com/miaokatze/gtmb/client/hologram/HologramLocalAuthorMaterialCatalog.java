package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramAuthorMaterialCatalog;
import com.miaokatze.gtmb.hologram.HologramNetwork;

import gregtech.common.blocks.ItemMachines;

/** Explicit read-only inventory query; source edits use only frozen public samples, never remote edit authority. */
final class HologramLocalAuthorMaterialCatalog {

    private static final ArrayBlockingQueue<Reply> REPLIES = new ArrayBlockingQueue<>(4);
    private static Query pending, frozen;
    private static Reply awaitingToolSync;
    private static List<ItemStack> samples = java.util.Collections.emptyList();

    private HologramLocalAuthorMaterialCatalog() {}

    static void request(HologramLocalAuthorRuntime owner, HologramUiSession ui) {
        request(owner, ui, null);
    }

    static void configure(HologramLocalAuthorRuntime owner, HologramUiSession ui, NBTTagCompound settings) {
        request(owner, ui, settings);
    }

    private static void request(HologramLocalAuthorRuntime owner, HologramUiSession ui, NBTTagCompound settings) {
        if (owner == null || !owner.editable()) return;
        pending = frozen = null;
        awaitingToolSync = null;
        samples = java.util.Collections.emptyList();
        REPLIES.clear();
        try {
            Query query = new Query(owner);
            if (settings != null) {
                query.request.setString("op", HologramAuthorMaterialCatalog.SETTINGS_OPERATION);
                for (String key : new String[] {
                    "materialMain", "materialContainers", "materialMe", "materialPriority" })
                    if (settings.hasKey(key)) query.request.setTag(
                        key,
                        settings.getTag(key)
                            .copy());
            }
            HologramNetwork.setAuthorMaterialCatalogReceiver(
                (reply, connection) -> REPLIES.offer(new Reply((NBTTagCompound) reply.copy(), connection)));
            pending = query;
            publish(
                owner,
                new NBTTagList(),
                true,
                settings == null ? "正在只读查询真实材料来源；不移动物品、不扣费、不创建作者会话" : "正在保存手持工具的材料来源设置并读取样本；不移动物品、不扣费");
            HologramNetwork.sendAction(query.request);
        } catch (IOException | RuntimeException failure) {
            publish(owner, new NBTTagList(), false, "无法读取材料样本：" + failure.getMessage());
        }
    }

    /** Called by the owning runtime on the client thread. Never sends a request or scans inventories. */
    static void tick() {
        Query query = pending;
        if (query != null && (!query.current() || System.nanoTime() - query.deadline >= 0)) {
            pending = null;
            awaitingToolSync = null;
            publish(query.owner, new NBTTagList(), false, "样本请求已过期或文档/工具已变化；工具设置结果请刷新核对");
        }
        if (awaitingToolSync != null) {
            REPLIES.offer(awaitingToolSync);
            awaitingToolSync = null;
        }
        Reply reply;
        while ((reply = REPLIES.poll()) != null) {
            query = pending;
            if (query == null || reply.connection != query.connection
                || !matches(query.request, reply.data)
                || !query.current()) continue;
            try {
                String actual = reply.data.getString("actualToolFingerprint");
                if (!actual.isEmpty()) query.expectedFingerprint = actual;
                if (!query.fingerprintCurrent()) {
                    if (reply.data.getBoolean("settingsApplied")) {
                        awaitingToolSync = reply;
                        break; // Server settings are saved; wait for normal authoritative held-slot synchronization.
                    }
                    throw new IOException("手持工具设置已变化，请重新刷新");
                }
                pending = null;
                NBTTagCompound settings = new NBTTagCompound();
                for (String key : new String[] {
                    "materialMain", "materialContainers", "materialMe", "materialPriority" })
                    if (reply.data.hasKey(key)) settings.setTag(
                        key,
                        reply.data.getTag(key)
                            .copy());
                query.owner.setViewMetadata(settings);
                if (!reply.data.getBoolean("success")) throw new IOException(reply.data.getString("status"));
                NBTTagList rows = reply.data.getTagList("materialChoices", 10);
                if (rows.tagCount() > 96) throw new IOException("样本目录超过限制");
                List<ItemStack> accepted = new ArrayList<>();
                NBTTagList display = new NBTTagList();
                for (int i = 0; i < rows.tagCount(); i++) {
                    NBTTagCompound row = rows.getCompoundTagAt(i);
                    Object registered = Item.itemRegistry.getObject(row.getString("item"));
                    if (!(registered instanceof Item) || row.getInteger("damage") < 0
                        || !((Item) registered instanceof ItemBlock || (Item) registered instanceof ItemMachines))
                        throw new IOException("服务端返回了无效公开物品样本");
                    ItemStack sample = new ItemStack((Item) registered, 1, row.getInteger("damage"));
                    accepted.add(sample);
                    display.appendTag(sample.writeToNBT(new NBTTagCompound()));
                }
                frozen = query;
                samples = java.util.Collections.unmodifiableList(accepted);
                publish(query.owner, display, false, reply.data.getString("status"));
            } catch (IOException | RuntimeException failure) {
                pending = null;
                frozen = null;
                samples = java.util.Collections.emptyList();
                publish(query.owner, new NBTTagList(), false, "材料样本未采用：" + failure.getMessage());
            }
        }
        if (frozen != null && !frozen.current()) {
            Query old = frozen;
            frozen = null;
            samples = java.util.Collections.emptyList();
            publish(old.owner, new NBTTagList(), false, "样本对应的文档或连接已变化；请重新刷新");
        }
    }

    static ItemStack sample(HologramLocalAuthorRuntime owner, int index) throws IOException {
        Query query = frozen;
        if (query == null || query.owner != owner
            || !query.current()
            || !query.fingerprintCurrent()
            || index < 0
            || index >= samples.size()) throw new IOException("材料来源样本已过期，请重新刷新并选择");
        return samples.get(index)
            .copy();
    }

    static boolean matches(NBTTagCompound request, NBTTagCompound response) {
        for (String key : new String[] { "token", "document", "player", "toolFingerprint" })
            if (!response.hasKey(key, 8) || !request.getString(key)
                .equals(response.getString(key))) return false;
        return response.hasKey("revision", 4) && response.hasKey("toolSlot", 3)
            && request.getLong("revision") == response.getLong("revision")
            && request.getInteger("toolSlot") == response.getInteger("toolSlot");
    }

    private static void publish(HologramLocalAuthorRuntime owner, NBTTagList display, boolean waiting, String status) {
        if (HologramLocalAuthorRuntime.active() != owner) return;
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setTag("materialChoices", display);
        metadata.setBoolean("materialCataloguePending", waiting);
        metadata.setString("materialCatalogueStatus", status);
        owner.setViewMetadata(metadata);
    }

    private static final class Query {

        final HologramLocalAuthorRuntime owner;
        final NetworkManager connection;
        final Object player, world;
        String expectedFingerprint;
        final NBTTagCompound request = new NBTTagCompound();
        final long deadline = System.nanoTime() + 15_000_000_000L;

        Query(HologramLocalAuthorRuntime owner) throws IOException {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.thePlayer == null || minecraft.theWorld == null
                || minecraft.getNetHandler() == null
                || !ItemNekoHologramProjector.isProjector(minecraft.thePlayer.getHeldItem()))
                throw new IOException("请在已连接世界中手持投影工具");
            this.owner = owner;
            connection = minecraft.getNetHandler()
                .getNetworkManager();
            if (connection == null || !connection.isChannelOpen()) throw new IOException("服务器连接不可用");
            player = minecraft.thePlayer;
            world = minecraft.theWorld;
            ItemStack tool = minecraft.thePlayer.getHeldItem();
            request.setString("op", HologramAuthorMaterialCatalog.OPERATION);
            request.setString(
                "token",
                UUID.randomUUID()
                    .toString());
            request.setString(
                "document",
                owner.document()
                    .documentId());
            request.setLong(
                "revision",
                owner.document()
                    .revision());
            request.setString(
                "player",
                minecraft.thePlayer.getUniqueID()
                    .toString());
            request.setInteger("toolSlot", minecraft.thePlayer.inventory.currentItem);
            expectedFingerprint = HologramAuthorMaterialCatalog.toolFingerprint(tool);
            request.setString("toolFingerprint", expectedFingerprint);
        }

        boolean current() {
            Minecraft minecraft = Minecraft.getMinecraft();
            return HologramLocalAuthorRuntime.active() == owner && owner.document()
                .documentId()
                .equals(request.getString("document"))
                && owner.document()
                    .revision() == request.getLong("revision")
                && minecraft.thePlayer == player
                && minecraft.theWorld == world
                && minecraft.getNetHandler() != null
                && minecraft.getNetHandler()
                    .getNetworkManager() == connection
                && connection.isChannelOpen()
                && ItemNekoHologramProjector.isProjector(minecraft.thePlayer.getHeldItem())
                && minecraft.thePlayer.inventory.currentItem == request.getInteger("toolSlot");
        }

        boolean fingerprintCurrent() throws IOException {
            return current()
                && HologramAuthorMaterialCatalog.toolFingerprint(Minecraft.getMinecraft().thePlayer.getHeldItem())
                    .equals(expectedFingerprint);
        }
    }

    private static final class Reply {

        final NBTTagCompound data;
        final NetworkManager connection;

        Reply(NBTTagCompound data, NetworkManager connection) {
            this.data = data;
            this.connection = connection;
        }
    }
}
