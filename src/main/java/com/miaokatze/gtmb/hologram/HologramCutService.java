// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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

    private static final int MAX_CAPABILITY_PEERS = 256;
    /** Main-thread only; one live owner's opaque bounds capability and one earlier operation receipt. */
    private static final java.util.Map<UUID, CutCapability> boundsPeers = new java.util.HashMap<>();

    private static final class CutCapability {
        final HologramWorkspace.ActionEndpoint endpoint;
        final UUID token = UUID.randomUUID();
        String lastOperation = "";

        CutCapability(HologramWorkspace.ActionEndpoint endpoint) {
            this.endpoint = endpoint;
        }
    }

    private static CutCapability boundsPeer(HologramWorkspace.ActionEndpoint endpoint, boolean issue) {
        if (!HologramWorkspace.currentActionEndpoint(endpoint)) return null;
        CutCapability existing = boundsPeers.get(endpoint.actor);
        if (existing != null && existing.endpoint == endpoint) return existing;
        if (!issue) return null;
        boundsPeers.values().removeIf(peer -> !HologramWorkspace.currentActionEndpoint(peer.endpoint));
        if (boundsPeers.size() >= MAX_CAPABILITY_PEERS) return null;
        CutCapability fresh = new CutCapability(endpoint);
        boundsPeers.put(endpoint.actor, fresh);
        return fresh;
    }

    /** Captured before any native or permission callback for one exact new clipboard write. */
    private static final class CutPermit {
        final EntityPlayerMP player;
        final HologramWorkspace.ActionEndpoint endpoint;
        final WorldServer world;
        final int dimension, slot;
        final InventoryPlayer inventory;
        final ItemStack[] backing;
        final PlayerCapabilities permissions;
        final boolean initiallyAllowed;
        final ItemStack tool;
        final Item item;

        CutPermit(EntityPlayerMP player, HologramWorkspace.ActionEndpoint endpoint, WorldServer world) {
            this.player = player;
            this.endpoint = endpoint;
            this.world = world;
            dimension = player.dimension;
            inventory = player.inventory;
            backing = inventory == null ? null : inventory.mainInventory;
            permissions = player.capabilities;
            initiallyAllowed = permissions != null && permissions.allowEdit && !player.isDead && player.worldObj == world;
            slot = inventory == null ? -1 : inventory.currentItem;
            tool = slot < 0 || slot >= 9 || backing == null || slot >= backing.length ? null : backing[slot];
            item = tool == null ? null : tool.getItem();
        }

        private boolean currentPure() {
            return initiallyAllowed && item instanceof ItemNekoHologramProjector && item == tool.getItem()
                && player.worldObj == world && player.dimension == dimension && !player.isDead
                && player.inventory == inventory && inventory.mainInventory == backing
                && inventory.currentItem == slot && backing[slot] == tool
                && permissions != null && player.capabilities == permissions && permissions.allowEdit
                && (endpoint == null || HologramWorkspace.cutActionCurrentPure(endpoint));
        }

        void requirePure() throws IOException {
            if (!currentPure()) throw expired();
        }

        void require() throws IOException {
            if (endpoint != null && !HologramWorkspace.currentActionEndpoint(endpoint)) throw expired();
            // Transport methods and caller validations may have invoked external code; end with field-only proof.
            requirePure();
        }

        private static IOException expired() {
            return new IOException("剪切权限或原上下文已变化；未继续新操作，暂存已保留。");
        }
    }

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
                if (HologramWorkspace.legacyGateRefusal(owner) != null) continue;
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
        boundsPeers.clear();
        recoveredServer = null;
    }

    static void disconnected(EntityPlayerMP player) {
        CutCapability peer = boundsPeers.get(player.getUniqueID());
        if (peer != null && peer.endpoint.player == player && !HologramWorkspace.currentActionEndpoint(peer.endpoint))
            boundsPeers.remove(player.getUniqueID(), peer);
        DemolitionConnection connection = demolitionRequests.get(player.getUniqueID());
        if (connection != null && connection.player == player) demolitionRequests.remove(player.getUniqueID());
    }

    static void receive(HologramWorkspace.ActionEndpoint endpoint, NBTTagCompound request) {
        if (endpoint == null || request == null || !HologramCutEntry.canonicalId(request.getString("requestId"))) return;
        HologramRequestGate.Lease lease = queued.acquire(endpoint.player);
        if (lease == null) {
            // No deferred, unscoped response on saturated ingress. The client reconciles by request ID.
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.ACTION_REJECTED, 1);
            return;
        }
        NBTTagCompound copy = (NBTTagCompound) request.copy();
        // Capture (never adopt) the holder seen by the same authenticated queued request.
        CutPermit ingress = writesClipboard(copy.getString("action")) && endpoint.world instanceof WorldServer world
            ? new CutPermit(endpoint.player, endpoint, world) : null;
        ServerTaskScheduler.scheduleServerTask(() -> {
            try {
                if (!lease.active() || !HologramWorkspace.currentActionEndpoint(endpoint)) return;
                EntityPlayerMP player = endpoint.player;
                String action = copy.getString("action");
                boolean demolitionControl = "demolitionStatus".equals(action) || "demolitionCancel".equals(action);
                if (HologramAeCraftingOrders.active(player) && !"list".equals(action) && !demolitionControl
                    || HologramDemolition.active(player) && !HologramDemolitionProtocol.handles(action)) {
                    NBTTagCompound busy = reply(copy);
                    busy.setString("code",
                        HologramDemolition.active(player) ? "demolition_busy" : "material_order_busy");
                    busy.setString("message",
                        HologramDemolition.active(player) ? "拆除正在进行，请等待完成或按 Esc 取消" : "AE 缺料订单正在后台处理；请先等待完成或取消订单");
                    HologramNetwork.sendCutState(endpoint, busy);
                    return;
                }
                if (!acceptsContext(action, true, ItemNekoHologramProjector.isProjector(player.getHeldItem()))) {
                    NBTTagCompound rejected = reply(copy);
                    rejected.setString("code", "context_changed");
                    rejected.setString("message", "操作未执行：请在原维度持有投影仪后重试");
                    HologramNetwork.sendCutState(endpoint, rejected);
                    return;
                }
                HologramNetwork.sendCutState(endpoint, execute(player, copy, endpoint, ingress));
            } finally {
                lease.close();
            }
        });
    }

    private static NBTTagCompound reply(NBTTagCompound request) {
        NBTTagCompound response = new NBTTagCompound();
        response.setString("action", "state");
        response.setString("requestId", request.getString("requestId"));
        response.setBoolean("success", false);
        return response;
    }

    private static boolean writesClipboard(String action) {
        return "cut".equals(action) || "paste".equals(action) || "undo".equals(action);
    }

    static boolean acceptsContext(String action, boolean sameWorld, boolean heldProjector) {
        return "list".equals(action) || sameWorld && heldProjector;
    }

    static NBTTagCompound execute(EntityPlayerMP player, NBTTagCompound request) {
        return execute(player, request, null, null);
    }

    private static NBTTagCompound execute(EntityPlayerMP player, NBTTagCompound request,
        HologramWorkspace.ActionEndpoint endpoint, CutPermit ingress) {
        NBTTagCompound response = reply(request);
        if (endpoint != null && !HologramWorkspace.currentActionEndpoint(endpoint)) return response;
        if (HologramDemolitionProtocol.handles(request.getString("action"))) return executeDemolition(player, request);
        HologramCutStorage storage = null;
        NBTTagCompound state = null;
        String loggedAction = "unvalidated", phase = "admission";
        try {
            if (HologramDemolition.active(player)) throw new IOException("拆除正在进行，请等待完成或按 Esc 取消");
            for (Object key : request.func_150296_c()) {
                String name = String.valueOf(key);
                if (HologramCutProtocol.passiveListKey(name)) {
                    if (!HologramCutProtocol.passiveList(request)) throw new IOException("无效的只读剪切暂存查询");
                    continue;
                }
                if (HologramCutProtocol.boundsKey(name)) {
                    if (!writesClipboard(request.getString("action")) || HologramCutProtocol.boundsProof(request) == null)
                        throw new IOException("无效的剪切范围协议");
                    continue;
                }
                if (!java.util.Arrays
                    .asList(
                        "action",
                        "requestId",
                        "id",
                        "entrySequence",
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
            String action = request.getString("action");
            if (!java.util.Arrays.asList("list", "preview", "cut", "paste", "undo", "openWorkspace")
                .contains(action)) throw new IOException("未知剪切操作");
            loggedAction = action;
            if ("preview".equals(action)) {
                // Retired raw-preview queries do not load, traverse, transform, or recover a journal.
                response.setBoolean("success", false);
                response.setString("code", "preview_metadata_only");
                response.setString("message", "剪切暂存仅提供范围；不会传送完整预览。");
                return response;
            }
            boolean paste = "paste".equals(action);
            boolean undo = "undo".equals(action);
            boolean exactEntry = paste || undo && (endpoint != null || request.hasKey("entrySequence"));
            if (exactEntry && (!HologramCutEntry.canonicalId(request.getString("id"))
                || !request.hasKey("entrySequence", 4) || request.getLong("entrySequence") <= 0))
                throw new IOException("操作缺少已确认的剪切条目版本；暂存未改动。");
            if (paste) {
                for (String key : new String[] { "originX", "originY", "originZ" })
                    if (!request.hasKey(key, 3)) throw new IOException("放置目标坐标无效；暂存未改动。");
                for (String key : new String[] { "rotation", "turnX", "turnZ", "mirror" })
                    if (request.hasKey(key) && !request.hasKey(key, 3))
                        throw new IOException("放置方向无效；暂存未改动。");
            } else if (!undo && request.hasKey("entrySequence")) {
                throw new IOException("只有放置或撤回请求可以提交条目版本");
            }
            if ("openWorkspace".equals(action)) {
                // Old clients get no workspace construction, replacement, capture, or ordinary snapshot.
                String scopedRefusal = HologramWorkspace.legacyGateRefusal(player.getUniqueID());
                if (scopedRefusal != null) return rejectScopedMutation(response, scopedRefusal);
                response.setString("code", "workspace_local_only");
                response.setString("message", "剪切工作区现仅在客户端打开，请更新客户端；草稿、投影与暂存未改动。");
                return response;
            }
            boolean list = "list".equals(action);
            boolean write = writesClipboard(action);
            String reconcile = list ? request.getString("id") : "";
            if (!reconcile.isEmpty() && !HologramCutEntry.canonicalId(reconcile))
                throw new IOException("无效剪切请求编号");
            CutCapability capability = boundsPeer(endpoint, false);
            if (write && endpoint != null && (capability == null
                || !capability.token.equals(HologramCutProtocol.boundsProof(request)))) {
                response.setString("code", "bounds_protocol_required");
                response.setString("message", "剪切未执行：请同步支持范围确认的暂存列表；原方块与暂存已保留。");
                return response;
            }
            if (write && endpoint != null) {
                if (ingress == null) throw new IOException("剪切权限上下文不存在，未执行操作。");
                ingress.require();
            }
            String scopedRefusal = write ? HologramWorkspace.legacyConstructionRefusal(player) : null;
            if (scopedRefusal != null) return rejectScopedMutation(response, scopedRefusal);
            if (endpoint != null && !HologramWorkspace.currentActionEndpoint(endpoint)) return response;
            storage = new HologramCutStorage(saveRoot(), HologramLibraryFacade.actorId(player));
            state = storage.load();
            if (state.hasKey("pending", 10)) {
                NBTTagCompound pending = state.getCompoundTag("pending");
                NBTTagCompound authorization = pending.getCompoundTag("authorization");
                if (!list || reconcile.isEmpty() || capability == null || !reconcile.equals(capability.lastOperation)
                    || !HologramWorkspace.currentActionEndpoint(endpoint) || !pending.hasKey("authorization", 10)
                    || !authorization.hasKey("requestId", 8) || !reconcile.equals(authorization.getString("requestId")))
                    return pendingStatus(response, pending, null);
                scopedRefusal = HologramWorkspace.legacyConstructionRefusal(player);
                if (scopedRefusal != null) return rejectScopedMutation(response, scopedRefusal);
                if (!HologramWorkspace.currentActionEndpoint(endpoint)) return response;
                recover(storage, state); // Validates actor, phase, nonce and shape from the existing durable receipt.
            }
            if (list) {
                CutCapability proof = capability == null ? boundsPeer(endpoint, true) : capability;
                if (proof != null) HologramCutProtocol.writeBoundsProof(response, proof.token);
                response.setString("message", "剪切暂存已同步");
                if (!reconcile.isEmpty()) reportRequestResult(response, state, reconcile);
            } else if (write) {
                if (seen(state, requestId)) {
                    response.setString("message", "该剪切请求已完成");
                    response.setString("code", "already_completed");
                } else {
                    WorldServer editWorld = editableWorld(player);
                    CutPermit permit = ingress == null ? new CutPermit(player, null, editWorld) : ingress;
                    permit.require();
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
                        coordinateRights(permit, shape, x, y, z);
                        phase = "bind-source";
                        edit = new HologramCutWorld.Edit(editWorld, shape, x, y, z);
                        permit.require();
                        entry = edit.before;
                        entry.setString("id", requestId);
                    } else {
                        entry = find(state, request.getString("id"));
                        if (entry == null) throw new IOException("剪切暂存不存在");
                        // The durable pending payload is validated once in World.apply, before any mutation.
                        consumeId = entry.getString("id");
                        if (exactEntry && (!entry.hasKey("sequence", 4)
                            || entry.getLong("sequence") != request.getLong("entrySequence")))
                            throw new IOException("剪切条目已变化；请同步列表并重新确认。暂存未改动。");
                        dimension = undo ? entry.getInteger("dimension") : player.dimension;
                        if (dimension != player.dimension) throw new IOException("请回到剪切原维度再撤销，原方块与暂存已保留");
                        x = undo ? entry.getInteger("x") : request.getInteger("originX");
                        y = undo ? entry.getInteger("y") : request.getInteger("originY");
                        z = undo ? entry.getInteger("z") : request.getInteger("originZ");
                        if (!undo) {
                            var pose = com.miaokatze.gtmb.api.CutTransforms.Transform.read(request);
                            entry = HologramCutTransforms.applyForPlacement(entry, pose, x, y, z);
                            permit.require();
                            if (!pose.identity() && entry.getTagList("transformUnknown", 8).tagCount() > 0)
                                throw new IOException("此旋转或镜像含未验证的方块方向；未放置，暂存仍保留。");
                        }
                        HologramCutEntry.bounds(
                            x,
                            y,
                            z,
                            entry.getInteger("sizeX"),
                            entry.getInteger("sizeY"),
                            entry.getInteger("sizeZ"));
                        coordinateRights(permit, entry, x, y, z);
                        phase = "bind-destination";
                        edit = new HologramCutWorld.Edit(editWorld, entry, x, y, z);
                        permit.require();
                    }
                    phase = "protect";
                    authorizeBreaks(permit, edit);
                    // Refused validation has no recovery-ticket side effect, just like it has no durable nonce.
                    if (capability != null) capability.lastOperation = requestId;
                    if (!cut) authorizePlacement(permit, edit, entry, storage, state, action, requestId);
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
                    phase = "journal";
                    storage.markPending(pending, action, requestId, true);
                    state.setTag("pending", pending);
                    requireWriteBacking(permit, edit);
                    storage.save(state);
                    // Once durable, this is settlement of existing authority, not admission by a new holder/world.
                    phase = "settle";
                    recover(storage, state, edit.world);
                    if (cut) response.setString("id", entry.getString("id"));
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
                if (durable.hasKey("pending", 10)) pendingStatus(
                    response, durable.getCompoundTag("pending"), failure.getMessage());
                else directory(response, durable);
            } catch (IOException ignored) {}
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn(
                "Cut clipboard request failed: operation={} phase={} code={}", loggedAction, phase,
                response.getString("code"), failure);
        }
        return response;
    }

    private static NBTTagCompound pendingStatus(NBTTagCompound response, NBTTagCompound pending, String detail) {
        response.setBoolean("success", false);
        response.setString("code", "recovery_pending");
        response.setString("message", pending.hasKey("authorization", 10)
            ? "剪切操作待恢复，原件记录已保留：" + (detail == null ? "请核对原操作回执或联系管理员" : detail)
            : "旧剪切日志缺少权限记录，已保留原记录并阻止自动恢复；请管理员核对存档");
        return response;
    }

    private static NBTTagCompound rejectScopedMutation(NBTTagCompound response, String reason) {
        response.setBoolean("success", false);
        response.setString("code", "construction_busy");
        response.setString("message", reason);
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

    private static void requireCoordinate(CutPermit permit, int x, int y, int z) throws IOException {
        boolean mine = permit.world.canMineBlock(permit.player, x, y, z);
        permit.require();
        if (!mine) throw new IOException("该区域不允许编辑，原方块与暂存已保留");
        boolean edit = permit.player.canPlayerEdit(x, y, z, 1, permit.tool);
        permit.require();
        if (!edit) throw new IOException("该区域不允许编辑，原方块与暂存已保留");
    }

    private static void editRights(CutPermit permit, HologramCutWorld.Edit edit) throws IOException {
        permit.require();
        if (editableWorld(permit.player) != edit.world) throw new IOException("剪切原世界已变化，操作已停止");
        for (BlockSnapshot snapshot : edit.snapshots) requireCoordinate(permit, snapshot.x, snapshot.y, snapshot.z);
    }

    private static void coordinateRights(CutPermit permit, NBTTagCompound shape, int x, int y, int z)
        throws IOException {
        permit.require();
        HologramCutEntry.validate(shape);
        NBTTagList cells = shape.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            requireCoordinate(permit, x + cell.getInteger("x"), y + cell.getInteger("y"), z + cell.getInteger("z"));
        }
    }

    private static void authorizeBreaks(CutPermit permit, HologramCutWorld.Edit edit) throws IOException {
        editRights(permit, edit);
        for (BlockSnapshot snapshot : edit.snapshots) {
            Block block = snapshot.getReplacedBlock();
            if (block == Blocks.air) continue;
            boolean cancelled = MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(
                snapshot.x, snapshot.y, snapshot.z, edit.world, block, snapshot.meta, permit.player));
            permit.require();
            if (cancelled) throw new IOException("保护取消了方块移除，原方块与暂存已保留");
        }
        edit.verifyOriginal();
        permit.require();
        editRights(permit, edit);
    }

    /** Last synchronous transport view, then pure original world backing and holder. No authority is reacquired. */
    private static void requireWriteBacking(CutPermit permit, HologramCutWorld.Edit edit) throws IOException {
        permit.require();
        edit.requireOriginalBacking();
        permit.requirePure();
    }

    /** The temporary PlaceEvent audience is scoped; every restoration must prove it still owns the staged state. */
    private static void authorizePlacement(CutPermit permit, HologramCutWorld.Edit edit, NBTTagCompound entry,
        HologramCutStorage storage, NBTTagCompound state, String action, String requestId) throws Exception {
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
        requireWriteBacking(permit, edit);
        storage.save(state);
        boolean approved = false, restored = false;
        try {
            try {
                edit.stage(entry, permit::require, permit::requirePure);
                NBTTagList cells = entry.getTagList("cells", 10);
                for (int i = 0; i < cells.tagCount(); i++) {
                    if (Block.getBlockFromName(cells.getCompoundTagAt(i).getString("block")) == Blocks.air) continue;
                    BlockEvent.PlaceEvent event = ForgeEventFactory
                        .onPlayerBlockPlace(permit.player, edit.snapshots.get(i), ForgeDirection.UP);
                    permit.require();
                    if (event.isCanceled()) throw new IOException("保护取消了方块放置，原方块与暂存已保留");
                }
                edit.verifyStaged(entry);
                permit.require();
                editRights(permit, edit);
            } finally {
                edit.restoreOwned(entry);
                restored = true;
            }
            edit.verifyOriginal();
            permit.require();
            editRights(permit, edit);
            approved = true;
        } finally {
            if (!approved && restored) {
                // Even the current actor cannot clear this record if a callback changed the restored world.
                edit.verifyOriginal();
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
                    String scopedRefusal = HologramWorkspace.legacyConstructionRefusal(player);
                    if (scopedRefusal != null) return rejectScopedMutation(response, scopedRefusal);
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
        recover(storage, state, null);
    }

    private static void recover(HologramCutStorage storage, NBTTagCompound state, WorldServer admittedWorld)
        throws Exception {
        if (!state.hasKey("pending", 10)) return;
        NBTTagCompound pending = state.getCompoundTag("pending");
        boolean approved = storage.approvedPending(pending);
        if (approved && !seen(
            state,
            pending.getCompoundTag("authorization")
                .getString("requestId")))
            throw new IOException("剪切恢复缺少对应请求记录，原记录已保留");
        int dimension = pending.getInteger("dimension");
        WorldServer world = DimensionManager.getWorld(dimension);
        if (admittedWorld != null && world != admittedWorld)
            throw new IOException("待结算的剪切原世界已更换，原日志已保留");
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

    private static void directory(NBTTagCompound response, NBTTagCompound state) {
        response.setBoolean(HologramCutNaming.SKIP_PROMPT, HologramCutNaming.skipsPrompt(state));
        response
            .setBoolean(HologramCutPlacementPreference.SKIP_PROMPT, HologramCutPlacementPreference.skipsPrompt(state));
        NBTTagList entries = new NBTTagList();
        NBTTagList raw = state.getTagList("entries", 10);
        int count = Math.min(raw.tagCount(), HologramCutProtocol.MAX_ENTRIES);
        for (int i = 0; i < count; i++) entries.appendTag(HologramCutEntry.wireSummary(raw.getCompoundTagAt(i)));
        response.setTag("entries", entries);
    }
}
