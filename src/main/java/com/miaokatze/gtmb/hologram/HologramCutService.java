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
    private static final int MAX_PREPARATIONS = 4;
    private static final java.util.ArrayDeque<Preparation> preparing = new java.util.ArrayDeque<>();
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
                try (HologramCutDiagnostics diagnostics = HologramCutDiagnostics.begin()) {
                    HologramCutStorage storage = new HologramCutStorage(saveRoot(), owner, diagnostics);
                    NBTTagCompound state = storage.load();
                    recover(storage, state, diagnostics);
                    diagnostics.completed();
                }
            } catch (Exception failure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                    .error("Interrupted cut remains in durable recovery journal: " + file, failure);
            }
        } catch (IOException failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.error("Cut recovery directory unavailable", failure);
        }
    }

    public static void clear() {
        while (!preparing.isEmpty()) preparing.removeFirst().lease.close();
        queued.clear();
        recoveredServer = null;
    }

    public static void receive(EntityPlayerMP player, NBTTagCompound request) {
        if (player == null || request == null) return;
        HologramRequestGate.Lease lease = queued.acquire(player);
        if (lease == null) {
            HologramBusyReplies.reject(player, request, HologramBusyReplies.Kind.CUT);
            return;
        }
        net.minecraft.world.World identity = player.worldObj;
        NBTTagCompound copy = (NBTTagCompound) request.copy();
        ServerTaskScheduler.scheduleServerTask(() -> {
            boolean transferred = false;
            try {
                if (!lease.active()) return;
                if (HologramAeCraftingOrders.active(player) && !"list".equals(copy.getString("action"))) {
                    NBTTagCompound busy = new NBTTagCompound();
                    busy.setString("action", "state");
                    busy.setString("requestId", copy.getString("requestId"));
                    busy.setBoolean("success", false);
                    busy.setString("code", "material_order_busy");
                    busy.setString("message", "AE 缺料订单正在后台处理；请先等待完成或取消订单");
                    HologramNetwork.sendCutState(player, busy);
                    return;
                }
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
                if (preparable(copy) && preparing.size() >= MAX_PREPARATIONS) {
                    HologramBusyReplies.reject(player, copy, HologramBusyReplies.Kind.CUT);
                    return;
                }
                Preparation preparation = prepare(player, copy, identity, lease);
                if (preparation != null) {
                    preparing.addLast(preparation);
                    transferred = true;
                } else HologramNetwork.sendCutState(player, execute(player, copy));
            } finally {
                if (!transferred) lease.close();
            }
        });
    }

    /** Pure raw-cell work only; durable world mutation and native callbacks retain their synchronous barrier. */
    public static void tick() {
        long deadline = HologramWorkBudget.deadline(HologramWorkBudget.SLICE_NANOS);
        int remaining = preparing.size();
        while (remaining-- > 0 && !preparing.isEmpty()) {
            Preparation work = preparing.removeFirst();
            boolean retained = false;
            try {
                if (!work.lease.active() || work.player.playerNetServerHandler == null
                    || work.player.playerNetServerHandler.netManager == null
                    || !work.player.playerNetServerHandler.netManager.isChannelOpen()) continue;
                if (work.player.worldObj != work.world
                    || !ItemNekoHologramProjector.isProjector(work.player.getHeldItem())) {
                    NBTTagCompound rejected = new NBTTagCompound();
                    rejected.setString("action", "state");
                    rejected.setString("requestId", work.request.getString("requestId"));
                    rejected.setBoolean("success", false);
                    rejected.setString("code", "context_changed");
                    rejected.setString("message", "操作未执行：请在原维度持有投影仪后重试");
                    HologramNetwork.sendCutState(work.player, rejected);
                } else if (HologramAeCraftingOrders.active(work.player)) {
                    NBTTagCompound busy = new NBTTagCompound();
                    busy.setString("action", "state");
                    busy.setString("requestId", work.request.getString("requestId"));
                    busy.setBoolean("success", false);
                    busy.setString("code", "material_order_busy");
                    busy.setString("message", "AE 缺料订单正在后台处理；请先等待完成或取消订单");
                    HologramNetwork.sendCutState(work.player, busy);
                } else if (work.step(256, deadline)) {
                    HologramNetwork.sendCutState(work.player, execute(work.player, work.request, work));
                } else {
                    preparing.addLast(work);
                    retained = true;
                }
            } catch (Exception | LinkageError failure) {
                NBTTagCompound rejected = new NBTTagCompound();
                rejected.setString("action", "state");
                rejected.setString("requestId", work.request.getString("requestId"));
                rejected.setBoolean("success", false);
                rejected.setString("code", "failed");
                rejected.setString("message", "剪切准备失败，请重试；原件记录已保留");
                HologramNetwork.sendCutState(work.player, rejected);
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut preparation failed", failure);
            } finally {
                if (!retained) work.lease.close();
            }
            if (System.nanoTime() >= deadline) break;
        }
    }

    private static Preparation prepare(EntityPlayerMP player, NBTTagCompound request, net.minecraft.world.World world,
        HologramRequestGate.Lease lease) {
        String action = request.getString("action"), purpose = request.getString("previewPurpose");
        if (!preparable(request)) return null;
        try {
            validateRequestKeys(request);
            HologramCutStorage storage = new HologramCutStorage(saveRoot(), HologramLibraryFacade.actorId(player));
            NBTTagCompound state = storage.load();
            // Recovery owns the whole world barrier; never stage a result against a half-recovered journal.
            if (state.hasKey("pending", 10)) return null;
            NBTTagCompound entry = find(state, request.getString("id"));
            if (entry == null) return null;
            boolean target = "paste".equals(action) || "placement".equals(purpose);
            com.miaokatze.gtmb.api.CutTransforms.Transform pose = com.miaokatze.gtmb.api.CutTransforms.Transform
                .read(request);
            return new Preparation(
                player,
                request,
                world,
                lease,
                entry,
                pose,
                target ? request.getInteger("originX") : entry.getInteger("x"),
                target ? request.getInteger("originY") : entry.getInteger("y"),
                target ? request.getInteger("originZ") : entry.getInteger("z"),
                target ? player.dimension : entry.getInteger("dimension"));
        } catch (Exception failure) {
            // The ordinary request path owns validation errors and its exact durable recovery receipt.
            return null;
        }
    }

    private static boolean preparable(NBTTagCompound request) {
        String action = request.getString("action");
        return "paste".equals(action)
            || "preview".equals(action) && !"storage".equals(request.getString("previewPurpose"));
    }

    private static final class Preparation {

        final EntityPlayerMP player;
        final NBTTagCompound request, original;
        final net.minecraft.world.World world;
        final HologramRequestGate.Lease lease;
        final HologramCutEntry.Validation validation;
        final com.miaokatze.gtmb.api.CutTransforms.Transform pose;
        final int x, y, z, dimension;
        HologramCutTransforms.Task task;

        Preparation(EntityPlayerMP player, NBTTagCompound request, net.minecraft.world.World world,
            HologramRequestGate.Lease lease, NBTTagCompound original,
            com.miaokatze.gtmb.api.CutTransforms.Transform pose, int x, int y, int z, int dimension)
            throws IOException {
            this.player = player;
            this.request = request;
            this.world = world;
            this.lease = lease;
            this.original = original;
            validation = new HologramCutEntry.Validation(original);
            this.pose = pose;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
        }

        boolean step(int maximumCells, long deadline) throws IOException {
            if (task == null) {
                if (!validation.step(maximumCells, deadline)) return false;
                HologramCutPairs.validatePose(original, pose);
                task = new HologramCutTransforms.Task(original, pose, x, y, z, dimension);
                return false;
            }
            return task.step(maximumCells, deadline);
        }

        NBTTagCompound checkedResult(NBTTagCompound current) throws IOException {
            if (!HologramCutWorld.sameNbt(original, current)) throw new IOException("剪切暂存已改变，请重新请求预览或放置");
            return task.result();
        }
    }

    static boolean acceptsContext(String action, boolean sameWorld, boolean heldProjector) {
        return "list".equals(action) || sameWorld && heldProjector;
    }

    static void validateRequestKeys(NBTTagCompound request) throws IOException {
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
                    "mirror",
                    "previewPurpose",
                    "previewGeneration")
                .contains(name)) throw new IOException("剪切请求只能包含操作、坐标、名称和条目编号");
        }
    }

    static NBTTagCompound execute(EntityPlayerMP player, NBTTagCompound request) {
        return execute(player, request, null);
    }

    private static NBTTagCompound execute(EntityPlayerMP player, NBTTagCompound request, Preparation preparation) {
        HologramCutDiagnostics diagnostics = HologramCutDiagnostics.begin();
        NBTTagCompound response = new NBTTagCompound();
        response.setString("action", "state");
        response.setString("requestId", request.getString("requestId"));
        HologramCutStorage storage = null;
        NBTTagCompound state = null;
        try {
            validateRequestKeys(request);
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
            if ("openWorkspace".equals(request.getString("action"))) {
                // Opening a manager never loads the clipboard journal or retries a world mutation.
                HologramLibraryFacade.actorId(player);
                if (player.isDead || !(player.worldObj instanceof WorldServer)
                    || !ItemNekoHologramProjector.isProjector(player.getHeldItem()))
                    throw new IOException("请持有投影仪后打开剪切工作区");
                HologramSession workspace = HologramEditorWorkspaceRegistry.openCutWorkspace(player);
                if (workspace == null) throw new IOException("当前任务尚未完成；请保留原工作区并等待回执");
                workspace.open = true;
                workspace.settingsOnly = false;
                workspace.openPurpose = "cutWorkspace";
                workspace.cutWorkspaceRequestId = requestId;
                HologramSnapshots.send(workspace);
                response.setBoolean("success", true);
                response.setString("code", "ok");
                response.setString("message", "正在打开剪切工作区");
                return response;
            }
            storage = new HologramCutStorage(saveRoot(), HologramLibraryFacade.actorId(player), diagnostics);
            state = storage.load();
            recover(storage, state, diagnostics);
            String action = request.getString("action");
            if ("list".equals(action)) {
                response.setString("message", "剪切暂存已同步");
                String reconcile = request.getString("id");
                if (!reconcile.isEmpty()) reportRequestResult(response, state, reconcile);
            } else if ("preview".equals(action)) {
                NBTTagCompound entry = find(state, request.getString("id"));
                if (entry == null) throw new IOException("剪切暂存不存在");
                if (preparation == null) HologramCutEntry.validate(entry);
                preview(response, entry, request, player.dimension, preparation);
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
                        try (HologramCutDiagnostics.Scope ignored = diagnostics.phase(HologramCutDiagnostics.Phase.CAPTURE, 1)) {
                            entry = HologramCutEntry.capture(
                                (WorldServer) player.worldObj,
                                x,
                                y,
                                z,
                                box[3] - x + 1,
                                box[4] - y + 1,
                                box[5] - z + 1,
                                selection);
                        }
                        HologramCutPairs.validateEntry(entry);
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
                        if (!undo) {
                            com.miaokatze.gtmb.api.CutTransforms.Transform transform = com.miaokatze.gtmb.api.CutTransforms.Transform
                                .read(request);
                            HologramCutPairs.validatePose(entry, transform);
                            entry = preparation == null
                                ? HologramCutTransforms.apply(entry, transform, x, y, z, dimension)
                                : preparation.checkedResult(entry);
                        }
                        HologramCutEntry.bounds(
                            x,
                            y,
                            z,
                            entry.getInteger("sizeX"),
                            entry.getInteger("sizeY"),
                            entry.getInteger("sizeZ"));
                        if (!undo) {
                            HologramCutPairs.validateEntry(entry);
                            HologramCutPairs.validateDestination(player.worldObj, entry, x, y, z);
                        }
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
                    recover(storage, state, diagnostics);
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
        } finally {
            if (response.getBoolean("success")) diagnostics.completed();
            diagnostics.close();
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
        try (HologramCutDiagnostics diagnostics = HologramCutDiagnostics.begin()) {
            recover(storage, state, diagnostics);
            diagnostics.completed();
        }
    }

    private static void recover(HologramCutStorage storage, NBTTagCompound state, HologramCutDiagnostics diagnostics)
        throws Exception {
        if (!state.hasKey("pending", 10)) return;
        NBTTagCompound pending = state.getCompoundTag("pending");
        int dimension = pending.getInteger("dimension");
        WorldServer world = DimensionManager.getWorld(dimension);
        if (world == null) {
            DimensionManager.initDimension(dimension);
            world = DimensionManager.getWorld(dimension);
        }
        if (world == null) throw new IOException("原维度不可用，剪切记录已保留");
        HologramCutTransaction.recover(storage, state, world, diagnostics);
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

    private static void select(NBTTagCompound response, NBTTagCompound entry) {
        response.setString("id", entry.getString("id"));
        response.setTag("preview", HologramCutEntry.preview(entry));
    }

    /** Storage is an original local view; moving is a pose sketch; only locked placement binds a target origin. */
    static void preview(NBTTagCompound response, NBTTagCompound entry, NBTTagCompound request, int targetDimension)
        throws IOException {
        preview(response, entry, request, targetDimension, null);
    }

    private static void preview(NBTTagCompound response, NBTTagCompound entry, NBTTagCompound request,
        int targetDimension, Preparation preparation) throws IOException {
        String purpose = request.getString("previewPurpose");
        if (purpose.isEmpty()) purpose = "local"; // Existing read clients retain their source-local pose semantics.
        if (!"storage".equals(purpose) && !"local".equals(purpose) && !"placement".equals(purpose))
            throw new IOException("未知剪切预览上下文");
        response.setString("previewPurpose", purpose);
        response.setLong("previewGeneration", request.getLong("previewGeneration"));
        if ("storage".equals(purpose)) {
            select(response, entry);
            return;
        }
        int x = entry.getInteger("x"), y = entry.getInteger("y"), z = entry.getInteger("z");
        int dimension = entry.getInteger("dimension");
        if ("placement".equals(purpose)) {
            for (String key : new String[] { "originX", "originY", "originZ" })
                if (!request.hasKey(key, 3)) throw new IOException("放置预览目标坐标不完整");
            x = request.getInteger("originX");
            y = request.getInteger("originY");
            z = request.getInteger("originZ");
            dimension = targetDimension;
            response.setInteger("originX", x);
            response.setInteger("originY", y);
            response.setInteger("originZ", z);
        }
        com.miaokatze.gtmb.api.CutTransforms.Transform transform = com.miaokatze.gtmb.api.CutTransforms.Transform
            .read(request);
        HologramCutPairs.validatePose(entry, transform);
        int[] size = transform.size(entry.getInteger("sizeX"), entry.getInteger("sizeY"), entry.getInteger("sizeZ"));
        if ("placement".equals(purpose)) HologramCutEntry.bounds(x, y, z, size[0], size[1], size[2]);
        NBTTagCompound transformed = preparation == null
            ? HologramCutTransforms.apply(entry, transform, x, y, z, dimension)
            : preparation.checkedResult(entry);
        select(response, transformed);
        if ("placement".equals(purpose)) response.setBoolean(
            "previewTargetIndependent",
            !transformed.getBoolean("transformTargetDependent") && transformed.getTagList("transformUnknown", 8)
                .tagCount() == 0);
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
