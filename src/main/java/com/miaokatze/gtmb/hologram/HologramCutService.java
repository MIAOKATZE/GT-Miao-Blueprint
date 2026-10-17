package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

/** Move-only server clipboard. The durable redo journal remains authoritative until world bytes are verified. */
public final class HologramCutService {

    private static final HologramRequestGate queued = new HologramRequestGate(8, 128);
    private static MinecraftServer recoveredServer;

    private HologramCutService() {}

    /** Redo interrupted edits before the first normal world tick after restart. */
    static void startupRecovery() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server == recoveredServer || server.worldServerForDimension(0) == null) return;
        recoveredServer = server;
        Path folder = saveRoot().resolve("gtmb-cut");
        if (!java.nio.file.Files.isDirectory(folder)) return;
        try (java.nio.file.DirectoryStream<Path> files = java.nio.file.Files.newDirectoryStream(folder, "*.nbt")) {
            for (Path file : files) try {
                String name = file.getFileName()
                    .toString();
                UUID owner = UUID.fromString(name.substring(0, name.length() - 4));
                HologramCutStorage storage = new HologramCutStorage(saveRoot(), owner);
                NBTTagCompound state = storage.load();
                recover(storage, state);
            } catch (Exception failure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                    .error("Interrupted cut remains in durable recovery journal: " + file, failure);
            }
        } catch (IOException failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.error("Cut recovery directory unavailable", failure);
        }
    }

    public static void clear() {
        queued.clear();
        recoveredServer = null;
    }

    public static void receive(EntityPlayerMP player, NBTTagCompound request) {
        if (player == null || request == null) return;
        if (!queued.enter(player)) {
            HologramBusyReplies.reject(player, request, HologramBusyReplies.Kind.CUT);
            return;
        }
        net.minecraft.world.World identity = player.worldObj;
        NBTTagCompound copy = (NBTTagCompound) request.copy();
        ServerTaskScheduler.scheduleServerTask(() -> {
            try {
                if (player.playerNetServerHandler == null || player.playerNetServerHandler.netManager == null
                    || !player.playerNetServerHandler.netManager.isChannelOpen()) return;
                if (!acceptsContext(
                    copy.getString("action"),
                    player.worldObj == identity,
                    ItemNekoHologramProjector.isProjector(player.getHeldItem()))) {
                    NBTTagCompound rejected = new NBTTagCompound();
                    rejected.setString("action", "state");
                    rejected.setString("requestId", copy.getString("requestId"));
                    rejected.setBoolean("success", false);
                    rejected.setString("code", "context_changed");
                    rejected.setString("message", "操作未执行：请在原维度持有投影仪后重试");
                    HologramNetwork.sendCutState(player, rejected);
                    return;
                }
                HologramNetwork.sendCutState(player, execute(player, copy));
            } finally {
                queued.leave(player);
            }
        });
    }

    static boolean acceptsContext(String action, boolean sameWorld, boolean heldProjector) {
        return "list".equals(action) || sameWorld && heldProjector;
    }

    static NBTTagCompound execute(EntityPlayerMP player, NBTTagCompound request) {
        NBTTagCompound response = new NBTTagCompound();
        response.setString("action", "state");
        response.setString("requestId", request.getString("requestId"));
        HologramCutStorage storage = null;
        NBTTagCompound state = null;
        try {
            for (Object key : request.func_150296_c()) {
                String name = String.valueOf(key);
                if (!java.util.Arrays
                    .asList(
                        "action",
                        "requestId",
                        "id",
                        "name",
                        HologramCutNaming.SKIP_PROMPT,
                        HologramCutPlacementPreference.SKIP_PROMPT,
                        "x1",
                        "y1",
                        "z1",
                        "x2",
                        "y2",
                        "z2",
                        "areaSelection",
                        "originX",
                        "originY",
                        "originZ",
                        "rotation",
                        "turnX",
                        "turnZ",
                        "mirror")
                    .contains(name)) throw new IOException("剪切请求只能包含操作、坐标、名称和条目编号");
            }
            if (request.hasKey("areaSelection") && !"cut".equals(request.getString("action")))
                throw new IOException("只有剪切操作可以提交区域组合");
            if (request.hasKey("name") && (!"cut".equals(request.getString("action")) || !request.hasKey("name", 8)))
                throw new IOException("只有剪切操作可以提交文本名称");
            if (request.hasKey(HologramCutNaming.SKIP_PROMPT)
                && (!"cut".equals(request.getString("action")) || !request.hasKey(HologramCutNaming.SKIP_PROMPT, 1)))
                throw new IOException("只有剪切操作可以保存命名偏好");
            if ("cut".equals(request.getString("action"))) HologramCutNaming.checkedName(request.getString("name"));
            HologramCutPlacementPreference.validate(request);
            String requestId = UUID.fromString(request.getString("requestId"))
                .toString();
            if (!requestId.equals(request.getString("requestId"))) throw new IOException("无效剪切请求编号");
            storage = new HologramCutStorage(saveRoot(), HologramLibraryFacade.actorId(player));
            state = storage.load();
            recover(storage, state);
            String action = request.getString("action");
            if ("list".equals(action)) {
                response.setString("message", "剪切暂存已同步");
                String reconcile = request.getString("id");
                if (!reconcile.isEmpty()) reportRequestResult(response, state, reconcile);
            } else if ("preview".equals(action)) {
                NBTTagCompound entry = find(state, request.getString("id"));
                if (entry == null) throw new IOException("剪切暂存不存在");
                HologramCutEntry.validate(entry);
                select(
                    response,
                    HologramCutTransforms.apply(
                        entry,
                        com.miaokatze.gtmb.api.CutTransforms.Transform.read(request),
                        entry.getInteger("x"),
                        entry.getInteger("y"),
                        entry.getInteger("z")));
            } else if ("cut".equals(action) || "paste".equals(action) || "undo".equals(action)) {
                if (seen(state, requestId)) {
                    response.setString("message", "该剪切请求已完成");
                    response.setString("code", "already_completed");
                } else {
                    // Leave enough tombstone capacity for every queued entry to be placed or undone.
                    if ("cut".equals(action) && state.getTagList("requests", 10)
                        .tagCount() >= 65536 - HologramCutProtocol.MAX_ENTRIES) throw new IOException("剪切请求记录已达安全上限");
                    NBTTagCompound entry;
                    String consumeId = null;
                    int x, y, z, dimension;
                    boolean cut = "cut".equals(action);
                    if (cut) {
                        if (state.getTagList("entries", 10)
                            .tagCount() >= HologramCutProtocol.MAX_ENTRIES) throw new IOException("剪切暂存队列已满，请先放置或撤销");
                        HologramAreaSelection selection = HologramCutEntry.selection(request);
                        int[] box = selection.bounds();
                        x = box[0];
                        y = box[1];
                        z = box[2];
                        dimension = player.dimension;
                        entry = HologramCutEntry.capture(
                            (WorldServer) player.worldObj,
                            x,
                            y,
                            z,
                            box[3] - x + 1,
                            box[4] - y + 1,
                            box[5] - z + 1,
                            selection);
                        entry.setString("id", requestId);
                        long sequence = state.getLong("sequence") + 1;
                        state.setLong("sequence", sequence);
                        entry.setLong("sequence", sequence);
                        HologramCutNaming.apply(state, entry, request);
                        state.getTagList("entries", 10)
                            .appendTag(entry);
                    } else {
                        entry = find(state, request.getString("id"));
                        if (entry == null) throw new IOException("剪切暂存不存在");
                        // The durable pending payload is validated once in World.apply, before any mutation.
                        consumeId = entry.getString("id");
                        boolean undo = "undo".equals(action);
                        dimension = undo ? entry.getInteger("dimension") : player.dimension;
                        x = undo ? entry.getInteger("x") : request.getInteger("originX");
                        y = undo ? entry.getInteger("y") : request.getInteger("originY");
                        z = undo ? entry.getInteger("z") : request.getInteger("originZ");
                        if (!undo) entry = HologramCutTransforms
                            .apply(entry, com.miaokatze.gtmb.api.CutTransforms.Transform.read(request), x, y, z);
                        HologramCutEntry.bounds(
                            x,
                            y,
                            z,
                            entry.getInteger("sizeX"),
                            entry.getInteger("sizeY"),
                            entry.getInteger("sizeZ"));
                    }
                    HologramCutPlacementPreference.remember(state, request);
                    NBTTagCompound done = new NBTTagCompound();
                    done.setString("id", requestId);
                    state.getTagList("requests", 10)
                        .appendTag(done);
                    NBTTagCompound pending = new NBTTagCompound();
                    pending.setTag("entry", entry.copy());
                    pending.setInteger("dimension", dimension);
                    pending.setInteger("x", x);
                    pending.setInteger("y", y);
                    pending.setInteger("z", z);
                    pending.setBoolean("air", cut);
                    if (consumeId != null) pending.setString("consumeId", consumeId);
                    state.setTag("pending", pending);
                    storage.save(state); // Original blocks and inventories are durable before the first mutation.
                    recover(storage, state);
                    if (cut) select(response, entry);
                    response.setString(
                        "message",
                        cut ? "已完整剪切并加入暂存" : "undo".equals(action) ? "已撤销剪切并还原原位，暂存已消费" : "已完整放置，暂存已消费");
                }
            } else throw new IOException("未知剪切操作");
            response.setBoolean("success", true);
            if (response.getString("code")
                .isEmpty()) response.setString("code", "ok");
            directory(response, state);
        } catch (Exception failure) {
            response.setBoolean("success", false);
            response.setString("code", "failed");
            response.setString("message", failure.getMessage() == null ? "剪切操作失败，原件记录已保留" : failure.getMessage());
            // Never expose an entry that might still exist in its original world or a partially placed target.
            if (storage != null) try {
                NBTTagCompound durable = storage.load();
                if (durable.hasKey("pending", 10)) {
                    response.setString("code", "recovery_pending");
                    response.setString("message", "剪切操作待恢复，原件记录已保留；再次同步将重试存盘");
                } else directory(response, durable);
            } catch (IOException ignored) {}
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut clipboard request failed", failure);
        }
        return response;
    }

    static Path saveRoot() {
        return MinecraftServer.getServer()
            .worldServerForDimension(0)
            .getSaveHandler()
            .getWorldDirectory()
            .toPath();
    }

    static void recover(HologramCutStorage storage, NBTTagCompound state) throws Exception {
        if (!state.hasKey("pending", 10)) return;
        NBTTagCompound pending = state.getCompoundTag("pending");
        int dimension = pending.getInteger("dimension");
        WorldServer world = DimensionManager.getWorld(dimension);
        if (world == null) {
            DimensionManager.initDimension(dimension);
            world = DimensionManager.getWorld(dimension);
        }
        if (world == null) throw new IOException("原维度不可用，剪切记录已保留");
        NBTTagCompound entry = pending.getCompoundTag("entry");
        int x = pending.getInteger("x"), y = pending.getInteger("y"), z = pending.getInteger("z");
        boolean air = pending.getBoolean("air");
        HologramCutWorld.apply(world, entry, x, y, z, air);
        HologramCutWorld.saveAndVerify(world, entry, x, y, z, air);
        NBTTagCompound completed = (NBTTagCompound) state.copy();
        String consumeId = pending.getString("consumeId");
        if (!consumeId.isEmpty()) remove(completed, consumeId);
        completed.removeTag("pending");
        storage.save(completed);
        if (!consumeId.isEmpty()) remove(state, consumeId);
        state.removeTag("pending");
        HologramCutWorld.notifyCommitted(world, entry, x, y, z, air);
    }

    private static boolean seen(NBTTagCompound state, String id) {
        NBTTagList requests = state.getTagList("requests", 10);
        for (int i = 0; i < requests.tagCount(); i++) if (id.equals(
            requests.getCompoundTagAt(i)
                .getString("id")))
            return true;
        return false;
    }

    static void reportRequestResult(NBTTagCompound response, NBTTagCompound state, String id) throws IOException {
        if (!UUID.fromString(id)
            .toString()
            .equals(id)) throw new IOException("无效剪切请求编号");
        response.setString("reconciledRequestId", id);
        response.setBoolean("requestCompleted", seen(state, id));
    }

    private static NBTTagCompound find(NBTTagCompound state, String id) {
        NBTTagList entries = state.getTagList("entries", 10);
        if (id.isEmpty() && entries.tagCount() > 0) return entries.getCompoundTagAt(0);
        for (int i = 0; i < entries.tagCount(); i++) if (id.equals(
            entries.getCompoundTagAt(i)
                .getString("id")))
            return entries.getCompoundTagAt(i);
        return null;
    }

    private static void remove(NBTTagCompound state, String id) {
        NBTTagList entries = state.getTagList("entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) if (id.equals(
            entries.getCompoundTagAt(i)
                .getString("id"))) {
                    entries.removeTag(i);
                    return;
                }
    }

    private static void select(NBTTagCompound response, NBTTagCompound entry) {
        response.setString("id", entry.getString("id"));
        response.setTag("preview", HologramCutEntry.preview(entry));
    }

    private static void directory(NBTTagCompound response, NBTTagCompound state) {
        response.setBoolean(HologramCutNaming.SKIP_PROMPT, HologramCutNaming.skipsPrompt(state));
        response
            .setBoolean(HologramCutPlacementPreference.SKIP_PROMPT, HologramCutPlacementPreference.skipsPrompt(state));
        NBTTagList entries = new NBTTagList();
        NBTTagList raw = state.getTagList("entries", 10);
        for (int i = 0; i < raw.tagCount(); i++) entries.appendTag(HologramCutEntry.directory(raw.getCompoundTagAt(i)));
        response.setTag("entries", entries);
    }
}
