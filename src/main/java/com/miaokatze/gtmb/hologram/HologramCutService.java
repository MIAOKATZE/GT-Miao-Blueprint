package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

/** Move-only server clipboard. The durable redo journal remains authoritative until world bytes are verified. */
public final class HologramCutService {

    private static final HologramRequestGate queued = new HologramRequestGate(8, 128);
    private static final java.util.Map<UUID, DemolitionConnection> demolitionRequests = new java.util.HashMap<>();
    private static MinecraftServer recoveredServer;

    private static final class DemolitionConnection {

        final EntityPlayerMP player;
        final HologramDemolitionProtocol.History history = new HologramDemolitionProtocol.History();

        DemolitionConnection(EntityPlayerMP player) {
            this.player = player;
        }
    }

    private HologramCutService() {}

    /** Redo interrupted edits before the first normal world tick after restart. */
    static void startupRecovery() {
        if (HologramStargateRules.enabled()) return;
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
        demolitionRequests.clear();
        recoveredServer = null;
    }

    static void disconnected(EntityPlayerMP player) {
        DemolitionConnection connection = demolitionRequests.get(player.getUniqueID());
        if (connection != null && connection.player == player) demolitionRequests.remove(player.getUniqueID());
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
            try {
                if (!lease.active()) return;
                String action = copy.getString("action");
                boolean demolitionControl = "demolitionStatus".equals(action) || "demolitionCancel".equals(action);
                if (HologramAeCraftingOrders.active(player) && !"list".equals(action) && !demolitionControl
                    || HologramDemolition.active(player) && !HologramDemolitionProtocol.handles(action)) {
                    NBTTagCompound busy = new NBTTagCompound();
                    busy.setString("action", "state");
                    busy.setString("requestId", copy.getString("requestId"));
                    busy.setBoolean("success", false);
                    busy.setString(
                        "code",
                        HologramDemolition.active(player) ? "demolition_busy" : "material_order_busy");
                    busy.setString(
                        "message",
                        HologramDemolition.active(player) ? "拆除正在进行，请等待完成或按 Esc 取消" : "AE 缺料订单正在后台处理；请先等待完成或取消订单");
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
                HologramNetwork.sendCutState(player, execute(player, copy));
            } finally {
                lease.close();
            }
        });
    }

    static boolean acceptsContext(String action, boolean sameWorld, boolean heldProjector) {
        return "list".equals(action) || sameWorld && heldProjector;
    }

    static NBTTagCompound execute(EntityPlayerMP player, NBTTagCompound request) {
        String requestedAction = request.getString("action");
        if (HologramStargateRules.enabled() && ("cut".equals(requestedAction) || "paste".equals(requestedAction)
            || "undo".equals(requestedAction)
            || "demolish".equals(requestedAction))) {
            NBTTagCompound rejected = new NBTTagCompound();
            rejected.setString("action", "state");
            rejected.setString("requestId", request.getString("requestId"));
            rejected.setBoolean("success", false);
            rejected.setString("code", HologramStargateRules.RESTRICTION_CODE);
            rejected.setString("message", HologramStargateRules.restrictionMessage());
            return rejected;
        }
        if (HologramDemolitionProtocol.handles(request.getString("action"))) return executeDemolition(player, request);
        NBTTagCompound response = new NBTTagCompound();
        response.setString("action", "state");
        response.setString("requestId", request.getString("requestId"));
        HologramCutStorage storage = null;
        NBTTagCompound state = null;
        try {
            if (HologramDemolition.active(player)) throw new IOException("拆除正在进行，请等待完成或按 Esc 取消");
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
            if (!java.util.Arrays.asList("list", "preview", "cut", "paste", "undo", "openWorkspace")
                .contains(request.getString("action"))) throw new IOException("未知剪切操作");
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
            storage = new HologramCutStorage(saveRoot(), HologramLibraryFacade.actorId(player));
            state = storage.load();
            if (HologramStargateRules.enabled() && state.hasKey("pending", 10)) {
                response.setBoolean("success", false);
                response.setString("code", HologramStargateRules.RESTRICTION_CODE);
                response.setString("message", HologramStargateRules.restrictionMessage() + "；旧剪切恢复记录已保留，未重放世界修改");
                return response;
            }
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
                    WorldServer editWorld = editableWorld(player);
                    // Leave enough tombstone capacity for every queued entry to be placed or undone.
                    if ("cut".equals(action) && state.getTagList("requests", 10)
                        .tagCount() >= 65536 - HologramCutProtocol.MAX_ENTRIES) throw new IOException("剪切请求记录已达安全上限");
                    NBTTagCompound entry;
                    String consumeId = null;
                    int x, y, z, dimension;
                    boolean cut = "cut".equals(action);
                    HologramCutWorld.Edit edit;
                    if (cut) {
                        if (state.getTagList("entries", 10)
                            .tagCount() >= HologramCutProtocol.MAX_ENTRIES) throw new IOException("剪切暂存队列已满，请先放置或撤销");
                        HologramAreaSelection selection = HologramCutEntry.selection(request);
                        int[] box = selection.bounds();
                        x = box[0];
                        y = box[1];
                        z = box[2];
                        dimension = player.dimension;
                        NBTTagCompound shape = selectionShape(selection, box);
                        coordinateRights(player, editWorld, shape, x, y, z);
                        edit = new HologramCutWorld.Edit(editWorld, shape, x, y, z);
                        entry = edit.before;
                        entry.setString("id", requestId);
                    } else {
                        entry = find(state, request.getString("id"));
                        if (entry == null) throw new IOException("剪切暂存不存在");
                        // The durable pending payload is validated once in World.apply, before any mutation.
                        consumeId = entry.getString("id");
                        boolean undo = "undo".equals(action);
                        dimension = undo ? entry.getInteger("dimension") : player.dimension;
                        if (dimension != player.dimension) throw new IOException("请回到剪切原维度再撤销，原方块与暂存已保留");
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
                        coordinateRights(player, editWorld, entry, x, y, z);
                        edit = new HologramCutWorld.Edit(editWorld, entry, x, y, z);
                    }
                    authorizeBreaks(player, edit);
                    if (!cut) authorizePlacement(player, edit, entry, storage, state, action, requestId);
                    if (cut) {
                        long sequence = state.getLong("sequence") + 1;
                        state.setLong("sequence", sequence);
                        entry.setLong("sequence", sequence);
                        HologramCutNaming.apply(state, entry, request);
                        state.getTagList("entries", 10)
                            .appendTag(entry);
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
                    if (!cut) pending.setTag("before", edit.before.copy());
                    storage.markPending(pending, action, requestId, true);
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
                    response.setString(
                        "message",
                        durable.getCompoundTag("pending")
                            .hasKey("authorization", 10)
                                ? "剪切操作待恢复，原件记录已保留："
                                    + (failure.getMessage() == null ? "再次同步将核对存盘" : failure.getMessage())
                                : "旧剪切日志缺少权限记录，已保留原记录并阻止自动恢复；请管理员核对存档");
                } else directory(response, durable);
            } catch (IOException ignored) {}
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Cut clipboard request failed", failure);
        }
        return response;
    }

    private static WorldServer editableWorld(EntityPlayerMP player) throws IOException {
        HologramLibraryFacade.actorId(player);
        if (player.isDead || !(player.worldObj instanceof WorldServer)
            || player.dimension != player.worldObj.provider.dimensionId
            || !player.capabilities.allowEdit
            || !ItemNekoHologramProjector.isProjector(player.getHeldItem()))
            throw new IOException("剪切未执行：请在允许编辑的原维度持有投影仪，原方块与暂存已保留");
        return (WorldServer) player.worldObj;
    }

    /** Geometry only. The following Edit snapshot observes attached originals without creating default tiles. */
    private static NBTTagCompound selectionShape(HologramAreaSelection selection, int[] box) throws IOException {
        int sx = box[3] - box[0] + 1, sy = box[4] - box[1] + 1, sz = box[5] - box[2] + 1;
        BitSet selected = HologramCutEntry.selectionMask(selection, box[0], box[1], box[2], sx, sy, sz);
        NBTTagCompound shape = new NBTTagCompound();
        shape.setInteger("sizeX", sx);
        shape.setInteger("sizeY", sy);
        shape.setInteger("sizeZ", sz);
        shape.setInteger("selectionVersion", 1);
        shape.setInteger("selectedCells", selected.cardinality());
        NBTTagList cells = new NBTTagList();
        for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1)) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", i % sx);
            cell.setInteger("y", i / (sx * sz));
            cell.setInteger("z", i / sx % sz);
            cells.appendTag(cell);
        }
        shape.setTag("cells", cells);
        return shape;
    }

    private static void editRights(EntityPlayerMP player, HologramCutWorld.Edit edit) throws IOException {
        if (editableWorld(player) != edit.world) throw new IOException("剪切原世界已变化，操作已停止");
        for (BlockSnapshot snapshot : edit.snapshots)
            if (!edit.world.canMineBlock(player, snapshot.x, snapshot.y, snapshot.z)
                || !player.canPlayerEdit(snapshot.x, snapshot.y, snapshot.z, 1, player.getHeldItem()))
                throw new IOException("该区域不允许编辑，原方块与暂存已保留");
    }

    private static void coordinateRights(EntityPlayerMP player, WorldServer world, NBTTagCompound shape, int x, int y,
        int z) throws IOException {
        HologramCutEntry.validate(shape);
        NBTTagList cells = shape.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            int px = x + cell.getInteger("x"), py = y + cell.getInteger("y"), pz = z + cell.getInteger("z");
            if (!world.canMineBlock(player, px, py, pz) || !player.canPlayerEdit(px, py, pz, 1, player.getHeldItem()))
                throw new IOException("该区域不允许编辑，原方块与暂存已保留");
        }
    }

    private static void authorizeBreaks(EntityPlayerMP player, HologramCutWorld.Edit edit) throws IOException {
        editRights(player, edit);
        for (BlockSnapshot snapshot : edit.snapshots) {
            Block block = snapshot.getReplacedBlock();
            if (block == Blocks.air) continue;
            if (MinecraftForge.EVENT_BUS.post(
                new BlockEvent.BreakEvent(
                    snapshot.x,
                    snapshot.y,
                    snapshot.z,
                    edit.world,
                    block,
                    snapshot.meta,
                    player)))
                throw new IOException("保护取消了方块移除，原方块与暂存已保留");
        }
        edit.verifyOriginal();
        editRights(player, edit);
    }

    /** Forge PlaceEvent observes the real installed target. Until every event accepts, only rollback is durable. */
    private static void authorizePlacement(EntityPlayerMP player, HologramCutWorld.Edit edit, NBTTagCompound entry,
        HologramCutStorage storage, NBTTagCompound state, String action, String requestId) throws Exception {
        if (HologramStargateRules.enabled()) throw new IOException(HologramStargateRules.restrictionMessage());
        NBTTagCompound rollback = new NBTTagCompound();
        rollback.setTag("entry", edit.before.copy());
        rollback.setTag("candidate", entry.copy());
        rollback.setInteger("dimension", edit.world.provider.dimensionId);
        rollback.setInteger("x", edit.x);
        rollback.setInteger("y", edit.y);
        rollback.setInteger("z", edit.z);
        rollback.setBoolean("air", false);
        storage.markPending(rollback, action, requestId, false);
        state.setTag("pending", rollback);
        storage.save(state);
        boolean approved = false;
        try {
            try {
                edit.stage(entry);
                NBTTagList cells = entry.getTagList("cells", 10);
                for (int i = 0; i < cells.tagCount(); i++) {
                    if (Block.getBlockFromName(
                        cells.getCompoundTagAt(i)
                            .getString("block"))
                        == Blocks.air) continue;
                    BlockEvent.PlaceEvent event = ForgeEventFactory
                        .onPlayerBlockPlace(player, edit.snapshots.get(i), ForgeDirection.UP);
                    if (event.isCanceled()) throw new IOException("保护取消了方块放置，原方块与暂存已保留");
                }
                edit.verifyStaged(entry);
                editRights(player, edit);
            } finally {
                edit.restore();
            }
            edit.verifyOriginal();
            editRights(player, edit);
            approved = true;
        } finally {
            if (!approved) {
                // Keep the original live instances after a veto; only a restart needs raw reconstruction.
                HologramCutWorld.saveAndVerify(edit.world, edit.before, edit.x, edit.y, edit.z, false);
                NBTTagCompound completed = (NBTTagCompound) state.copy();
                completed.removeTag("pending");
                storage.save(completed);
                state.removeTag("pending");
            }
        }
        // The disk still contains rollback-only until the complete approved redo is atomically saved below.
        state.removeTag("pending");
    }

    private static NBTTagCompound executeDemolition(EntityPlayerMP player, NBTTagCompound request) {
        NBTTagCompound response = new NBTTagCompound();
        response.setString("action", "state");
        response.setString("requestId", request.getString("requestId"));
        try {
            String id = HologramDemolitionProtocol.validate(request);
            String action = request.getString("action");
            DemolitionConnection connection = demolitionRequests.get(player.getUniqueID());
            if (connection == null || connection.player != player) {
                connection = new DemolitionConnection(player);
                demolitionRequests.put(player.getUniqueID(), connection);
            }
            HologramDemolitionProtocol.History history = connection.history;
            if ("demolitionStatus".equals(action)) {
                HologramDemolitionProtocol.Receipt receipt = history.find(request.getString("id"));
                boolean accepted = receipt != null && "demolish".equals(receipt.action) && receipt.accepted;
                response.setString("reconciledRequestId", request.getString("id"));
                response.setBoolean("requestCompleted", accepted);
                response.setString("message", accepted ? "拆除请求已接收，请留意完成消息" : "该拆除请求未执行，可重新框选确认");
            } else {
                HologramDemolitionProtocol.Receipt receipt = history.find(id);
                if (receipt != null) {
                    if (!action.equals(receipt.action)) throw new IOException("拆除请求编号已使用");
                    response.setBoolean("success", receipt.accepted);
                    response.setString("code", "already_submitted");
                    response.setString("message", receipt.accepted ? "该拆除请求已处理" : "该拆除请求未执行，请重新确认");
                    response.setBoolean("demolitionActive", HologramDemolition.active(player));
                    return response;
                }
                receipt = history.begin(id, action);
                if ("demolish".equals(action)) {
                    HologramDemolition.start(
                        player,
                        HologramDemolitionProtocol.selection(request),
                        HologramDemolitionProtocol.destination(request));
                    HologramSession workspace = HologramWorkspace.find(player.getUniqueID());
                    if (workspace != null && workspace.player == player)
                        HologramProjectorWorkflow.libraryCloseUi(workspace);
                    response.setString("message", "已开始从上往下拆除；按 Esc 可取消剩余拆除");
                } else {
                    HologramDemolition.cancel(player);
                    response.setString("message", "拆除已停止");
                }
                receipt.accepted = true;
            }
            response.setBoolean("success", true);
            response.setString("code", "ok");
        } catch (IOException invalid) {
            response.setBoolean("success", false);
            response.setString("code", "failed");
            response.setString("message", invalid.getMessage());
        }
        response.setBoolean("demolitionActive", HologramDemolition.active(player));
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
        if (HologramStargateRules.enabled()) throw new IOException(HologramStargateRules.restrictionMessage());
        NBTTagCompound pending = state.getCompoundTag("pending");
        boolean approved = storage.approvedPending(pending);
        if (approved && !seen(
            state,
            pending.getCompoundTag("authorization")
                .getString("requestId")))
            throw new IOException("剪切恢复缺少对应请求记录，原记录已保留");
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
        NBTTagCompound before = approved ? air ? entry : pending.getCompoundTag("before") : entry;
        NBTTagCompound candidate = approved ? entry : pending.getCompoundTag("candidate");
        NBTTagCompound changes = HologramCutWorld
            .recoveryChanges(world, before, candidate, x, y, z, approved && air, !approved);
        if (changes != null) HologramCutWorld.apply(world, changes, x, y, z, air);
        HologramCutWorld.saveAndVerify(world, entry, x, y, z, air);
        NBTTagCompound completed = (NBTTagCompound) state.copy();
        String consumeId = approved ? pending.getString("consumeId") : "";
        if (!consumeId.isEmpty()) remove(completed, consumeId);
        completed.removeTag("pending");
        storage.save(completed);
        if (!consumeId.isEmpty()) remove(state, consumeId);
        state.removeTag("pending");
        if (approved) HologramCutWorld.notifyCommitted(world, entry, x, y, z, air);
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
