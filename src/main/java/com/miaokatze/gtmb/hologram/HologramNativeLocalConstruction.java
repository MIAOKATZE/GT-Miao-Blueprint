// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.End;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Permit;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Settlement;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintSpatialView;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** In-process single-player construction. Neither the stored definition nor an execution frame goes on the wire. */
public final class HologramNativeLocalConstruction {

    private static final java.util.Set<Attempt> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();

    private HologramNativeLocalConstruction() {}

    static boolean active(EntityPlayerMP player) {
        return player != null && JOBS.containsKey(player.getUniqueID());
    }

    public enum Phase {
        PREPARING,
        EXECUTING,
        COMPLETE,
        CANCELLED,
        FAILED
    }

    public static final class Result {

        public final Phase phase;
        public final int placed, satisfied, total;
        public final long sequence;
        public final String detail;

        private Result(Phase phase, int placed, int satisfied, int total, long sequence, String detail) {
            this.phase = phase;
            this.placed = placed;
            this.satisfied = satisfied;
            this.total = total;
            this.sequence = sequence;
            this.detail = detail;
        }
    }

    public static final class Attempt {

        public final UUID id;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile boolean admitted;

        boolean admitted() {
            return admitted;
        }

        private volatile Result result = new Result(Phase.PREPARING, 0, 0, 0, 0, "");
        private final long intent;
        private com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition.Lease fileLease;

        private synchronized void release() {
            if (fileLease != null) {
                fileLease.close();
                fileLease = null;
            }
        }

        public Attempt(long intent) {
            this(intent, UUID.randomUUID());
        }

        Attempt(long intent, UUID id) {
            this.intent = intent;
            this.id = id;
        }

        public Result result() {
            return result;
        }

        public boolean cancelled() {
            return cancelled.get();
        }

        public boolean stop() {
            Phase current = result.phase;
            return (current == Phase.PREPARING || current == Phase.EXECUTING) && cancelled.compareAndSet(false, true);
        }

        private void publish(Phase phase, int placed, int satisfied, int total, String detail) {
            result = new Result(phase, placed, satisfied, total, result.sequence + 1, detail);
            if (phase == Phase.COMPLETE || phase == Phase.CANCELLED || phase == Phase.FAILED) {
                PENDING.remove(this);
                release();
            }
        }
    }

    @com.github.bsideup.jabel.Desugar
    private record Target(Block block, ItemStack sample, int metadata, int facing, String tile, String item,
        boolean controller, boolean stateful, String blockId, String itemId) {

        Target(Block block, ItemStack sample, int metadata, int facing, String tile, String item, boolean controller,
            boolean stateful) {
            this(
                block,
                sample,
                metadata,
                facing,
                tile,
                item,
                controller,
                stateful,
                Block.blockRegistry.getNameForObject(block),
                net.minecraft.item.Item.itemRegistry.getNameForObject(sample.getItem()));
        }
    }

    /** FIFO and retained source refer to one pinned, immutable file revision. */
    public static final class Prepared {

        private final NativeBlueprintSpatialView source;
        private final BooleanSupplier currentVersion;
        private final Map<Long, com.google.gson.JsonObject> targets;
        private final LongOpenHashSet excluded;
        private final int total;
        private final HologramNativeBlueprintDraft draft;

        private Prepared(NativeBlueprintSpatialView source, BooleanSupplier currentVersion,
            Map<Long, com.google.gson.JsonObject> targets, LongOpenHashSet excluded, int total,
            HologramNativeBlueprintDraft draft) {
            this.draft = draft;
            this.source = source;
            this.currentVersion = currentVersion;
            this.targets = targets;
            this.excluded = excluded;
            this.total = total;
        }

        Prepared bound(BooleanSupplier owner) {
            return new Prepared(source, owner, targets, excluded, total, draft);
        }
    }

    /**
     * Detached definition and descriptor counts only. Preserve unknown/special blocks with an explicit refusal
     * rather than charging ordinary shells and throwing away native payloads or block dependencies.
     */
    public static Prepared prepare(HologramNativeBlueprintDraft draft, NativeBlueprintPairStore.Receipt receipt,
        BooleanSupplier current) throws IOException {
        if (receipt == null || !draft.revision()
            .source()
            .revision()
            .equals(receipt.physicalHash)
            || !draft.revision()
                .source()
                .allowsGeometryMutation()
            || !draft.revision()
                .isUnchanged())
            throw new IOException("原生蓝图版本或语义不可用于施工");
        return preparePinned(draft, NativeBlueprintPairStore.watchForAdmission(receipt), current);
    }

    /** Authenticated cache pins refer to a server-owned validated native file, never to a client source path. */
    static Prepared preparePinned(HologramNativeBlueprintDraft draft, BooleanSupplier version, BooleanSupplier current)
        throws IOException {
        if (!draft.revision()
            .source()
            .allowsGeometryMutation()
            || !draft.revision()
                .isUnchanged())
            throw new IOException("原生蓝图语义不可用于施工");
        var header = draft.header();
        if (header.schema != 2 || header.controllerId != -1
            || header.controllerFacing != null && header.controllerFacing != -1
            || header.controllerType != null && !header.controllerType.isEmpty()
                && !"area".equals(header.controllerType)
            || meaningful(header.semanticNbt)) throw new IOException("此原生蓝图含当前原生施工不支持的整图规则；未放置或扣料");
        HologramBlueprintProcessing.validateReceipt(header);
        var source = draft.space();
        Map<String, Boolean> structures = new HashMap<>();
        if (header.structures != null)
            for (var structure : header.structures) structures.put(structure.id, structure.enabled);
        Map<Long, com.google.gson.JsonObject> targets = new LinkedHashMap<>();
        LongOpenHashSet excluded = new LongOpenHashSet();
        int total = 0;
        for (var usage : source.counts()
            .entrySet()) {
            if (!current.getAsBoolean()) throw new IOException("施工已取消");
            long descriptor = usage.getKey();
            var wrapper = source.wrapper(descriptor);
            var cell = draft.placementShape(descriptor, wrapper);
            var members = cell.memberships();
            if (cell.excluded || "minecraft:air".equals(cell.block)
                || !members.isEmpty() && members.stream()
                    .noneMatch(id -> structures.getOrDefault(id, true))) {
                excluded.add(descriptor);
                continue;
            }
            if (cell.block == null || cell.count != 1) throw new IOException("原生蓝图包含无方块身份或数量不正确的目标");
            targets.put(descriptor, wrapper);
            total += usage.getValue();
        }
        if (total == 0) throw new IOException("此蓝图没有可施工目标");
        if (header.aeIssues != null) for (var issue : header.aeIssues) if ("blocker".equals(issue.severity)) {
            var point = source.atPosition(issue.x, issue.y, issue.z);
            if (point != null && !excluded.contains(point.descriptor))
                throw new IOException("AE 蓝图仍有未处理的净化问题；请修正对应标记后保存");
        }
        // Do not substitute the latest generation for the version examined by the player.
        return new Prepared(source, version, targets, excluded, total, draft);
    }

    private static boolean meaningful(String value) {
        return value != null && !value.isEmpty();
    }

    public static void submit(Attempt attempt, Prepared prepared, Supplier<EntityPlayerMP> player, String toolId,
        int dimension, int[] anchor, int[] recipe, int reviewedSources, boolean voucher) {
        submit(attempt, prepared, player, toolId, dimension, anchor, recipe, reviewedSources, voucher, new int[0]);
    }

    public static void submit(Attempt attempt, Prepared prepared, Supplier<EntityPlayerMP> player, String toolId,
        int dimension, int[] anchor, int[] recipe, int reviewedSources, boolean voucher, int[] choices) {
        if (!HologramSavedBindingChoices.valid(choices)) {
            attempt.publish(Phase.FAILED, 0, 0, prepared.total, "量子终端槽位选择无效");
            return;
        }
        int[] fixedAnchor = anchor.clone(), fixedPose = recipe.clone(), fixedChoices = choices.clone();
        synchronized (attempt) {
            if (attempt.fileLease != null || attempt.result().phase != Phase.PREPARING) return;
            try {
                attempt.fileLease = prepared.source.definition()
                    .retain();
                PENDING.add(attempt);
            } catch (IllegalStateException stale) {
                attempt.publish(Phase.FAILED, 0, 0, prepared.total, "已保存源已释放");
                return;
            }
        }
        ServerTaskScheduler.scheduleServerTask(() -> {
            if (attempt.cancelled.get() || !PENDING.contains(attempt)) {
                attempt.publish(Phase.CANCELLED, 0, 0, prepared.total, "施工已取消");
                return;
            }
            try {
                admit(
                    attempt,
                    prepared,
                    player.get(),
                    toolId,
                    dimension,
                    fixedAnchor,
                    fixedPose,
                    reviewedSources,
                    voucher,
                    fixedChoices);
            } catch (IOException | RuntimeException | LinkageError invalid) {
                attempt.publish(
                    Phase.FAILED,
                    0,
                    0,
                    prepared.total,
                    invalid instanceof IOException ? invalid.getMessage() : "无法开始原生蓝图施工");
            }
        });
    }

    private static void admit(Attempt attempt, Prepared prepared, EntityPlayerMP player, String toolId, int dimension,
        int[] anchor, int[] recipe, int reviewedSources, boolean voucher, int[] bindingSlots) throws IOException {
        if (player == null || player.worldObj == null
            || player.worldObj.isRemote
            || player.dimension != dimension
            || player.isDead
            || !player.capabilities.allowEdit
            || player.playerNetServerHandler == null
            || attempt.intent <= 0
            || anchor.length != 3
            || recipe.length > HologramPreviewPose.MAX_RECIPE_LENGTH
            || toolId == null
            || toolId.isEmpty()) throw new IOException("原生施工上下文已变化");
        World world = player.worldObj;
        ItemStack held = player.getHeldItem();
        if (!ItemNekoHologramProjector.isProjector(held) || !toolId.equals(toolId(held))
            || !HologramWorldFlow.worldPosition(anchor[0], anchor[1], anchor[2], world.getHeight())
            || !world.blockExists(anchor[0], anchor[1], anchor[2])
            || !HologramSessionPolicy.previewReach(player, anchor[0], anchor[1], anchor[2]))
            throw new IOException("手持投影仪或施工位置已变化");
        HologramPreviewPose pose = new HologramPreviewPose();
        for (int axis : recipe) {
            if (axis != 0 && axis != 1) throw new IOException("无效施工方向");
            pose = pose.turn(axis == 1);
        }
        var frame = prepared.source.definition()
            .frame();
        for (long dx : new long[] { frame.minX, frame.minX + frame.sizeX - 1 })
            for (long dy : new long[] { frame.minY, frame.minY + frame.sizeY - 1 })
                for (long dz : new long[] { frame.minZ, frame.minZ + frame.sizeZ - 1 }) {
                    int[] p = pose.apply(
                        boundedOffset(dx, frame.anchorX),
                        boundedOffset(dy, frame.anchorY),
                        boundedOffset(dz, frame.anchorZ));
                    if (!HologramWorldFlow.worldPosition(
                        Math.addExact(anchor[0], p[0]),
                        Math.addExact(anchor[1], p[1]),
                        Math.addExact(anchor[2], p[2]),
                        world.getHeight())) throw new IOException("原生蓝图施工范围超出世界边界");
                }
        var endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
        var gate = HologramWorkspace.constructionGate(endpoint);
        final boolean creative = player.capabilities.isCreativeMode;
        var settings = HologramMaterials.read(held);
        var configured = HologramProjectedMainPolicy.captureSaved(settings, creative);
        if (gate == null || configured == null
            || reviewedSources != HologramProjectedMainPolicy.savedPlacementKey(settings, creative))
            throw new IOException("施工权限或材料来源不可用");
        var scope = configured.selectedScope();
        // No real material source, wireless terminal, container or voucher is opened for creative construction.
        if (creative) scope.main = scope.containers = scope.me = false;
        var tx = new HologramSession(player, anchor[0], anchor[1], anchor[2], false, 1);
        if (!toolId.equals(toolId(player.getHeldItem())) || !configured.configuredMatches(tx.materialSettings))
            throw new IOException("材料来源或投影仪已变化");
        tx.materialSettings = scope;
        tx.jobVoucherRequested = !creative && voucher;
        tx.virtual = tx.virtualPlacement = tx.virtualLocked = true;
        tx.job = 1;
        tx.executionRequestId = attempt.id.toString();
        tx.virtualPreset = prepared.draft.header();
        tx.placementDeidentificationChosen = tx.virtualPreset.deidentificationChosen;
        var stock = new HologramMaterials.Context(player, scope, tx.slot);
        Map<Long, Target> samples = new LinkedHashMap<>();
        for (var row : prepared.targets.entrySet()) {
            if (attempt.cancelled()) throw new IOException("施工已取消");
            samples.put(row.getKey(), resolve(prepared, tx, row.getKey(), row.getValue(), recipe, bindingSlots));
        }
        // Only the transaction knows whether the target is already satisfied. A gross bill is
        // not a stock admission gate: never reserve/spend for already-present or skipped cells.
        if (tx.jobVoucherRequested && !HologramDecorationVoucher.enabled()) throw new IOException("装饰抵用券不可用，未开始施工");
        if (!HologramWorkspace.currentActionEndpoint(endpoint) || attempt.cancelled.get()
            || player.capabilities.isCreativeMode != creative
            || !toolId.equals(toolId(player.getHeldItem()))
            || player.dimension != dimension
            || !configured.configuredMatches(HologramMaterials.read(player.getHeldItem())))
            throw new IOException("原生施工上下文已变化");
        var intent = new HologramConstructionTaskGate.Request(
            attempt.id,
            attempt.intent,
            HologramConstructionTaskGate.Kind.LOCAL_BLUEPRINT);
        if (!prepared.currentVersion.getAsBoolean()) throw new IOException("蓝图已更新，请重新打开材料页");
        var admitted = gate.acquire(
            endpoint.constructionScope,
            intent,
            HologramConstructionAuthority.competingWork(player) || JOBS.containsKey(player.getUniqueID()),
            HologramConstructionAuthority.hasKnownRecovery(player));
        if (admitted.lease == null) throw new IOException("已有施工或待核定结果");
        JOBS.put(
            player.getUniqueID(),
            new Job(
                attempt,
                prepared,
                endpoint,
                gate,
                admitted.lease,
                tx,
                configured,
                stock,
                toolId,
                anchor,
                pose,
                samples,
                creative));
        attempt.admitted = true;
        attempt.publish(Phase.EXECUTING, 0, 0, prepared.total, "开始原生蓝图施工");
    }

    private static Target resolve(Prepared prepared, HologramSession tx, long descriptor,
        com.google.gson.JsonObject wrapper, int[] recipe, int[] bindingSlots) throws IOException {
        var cell = prepared.draft.executionCell(descriptor, wrapper);
        Block block = cell.block();
        ItemStack sample = cell.stack();
        if (block == null || sample == null || cell.missing()) throw new IOException(
            "服务端缺少注册的施工材料：" + cell.block
                + " / "
                + cell.item
                + (block == null ? "（无方块）" : sample == null ? "（无物品）" : "（无设备子型）"));
        for (int turn : recipe) {
            boolean vertical = turn == 1;
            if (!cell.tileNbt.isEmpty()
                && HologramBlockTransformLittleTiles.needsNeighbours(block, HologramBlueprintNbt.decode(cell.tileNbt)))
                throw new IOException("该原生组合方块的跨坐标旋转尚未核对，请保存为目标方向后施工");
            HologramBlockTransforms.turn(cell, vertical, null, true);
            cell.facing = HologramVirtualTransform.front(cell.facing, vertical);
        }
        var tile = HologramExecutionEligibility.payload(cell.tileNbt);
        var item = HologramExecutionEligibility.payload(cell.itemNbt);
        if (HologramDeidentification.level(tx.virtualPreset) == 0
            && (HologramCellPolicy.foreignBlueprintPayloadOwner(tx, block, tile)
                || HologramCellPolicy.foreignBlueprintPayloadOwner(tx, block, item)))
            throw new IOException("0 级蓝图包含其他玩家所有权；请先完成去标识");
        if (HologramAeBlueprint.supports(block, tile)
            && HologramAeBlueprint.analyze(block, cell.metadata, tile).blocking)
            throw new IOException("AE 附件存在未处理的净化问题");
        if (sample.getItem() instanceof gregtech.common.blocks.ItemMachines)
            tile = HologramExecutionEligibility.gtState(tile, sample, true, true);
        HologramExecutionEligibility.validateSaved(
            block,
            cell.metadata,
            sample,
            cell.facing,
            cell.controller,
            tile,
            item,
            HologramBlueprintProcessing.quantumCleared(tx.virtualPreset));
        int group = HologramSavedBindingChoices.requiredGroup(tile);
        if (group != 0 && !tx.networkBindings.holds("q" + group)) {
            int slot = HologramSavedBindingChoices.slot(bindingSlots, group);
            if (slot >= 0) tx.networkBindings.select(tx.player, "q" + group, slot); // authentic server inventory,
                                                                                    // world, authority.
        }
        HologramNetworkBindings.validate(tile, tx.player, tx.networkBindings);
        if (HologramNativePlacement.supported(block, tile)) {
            ItemStack exact = HologramNativePlacement.sample(block, cell.metadata, tile);
            if (exact != null) sample = exact;
        }
        boolean aliasedItem = HologramLittleTilesMaterialAliases.applyItem(block, item);
        if (item != null) sample.setTagCompound(HologramBlueprintNbt.nativePayload(item));
        return new Target(
            block,
            sample,
            cell.metadata,
            cell.facing,
            tile == null ? "" : HologramBlueprintNbt.encodeCanonical(block, tile),
            aliasedItem ? HologramBlueprintNbt.encodeCanonical(item) : cell.itemNbt,
            cell.controller,
            block.hasTileEntity(cell.metadata) || tile != null
                || item != null
                || HologramDecorMaterials.supports(block));
    }

    private static int boundedOffset(long coordinate, int origin) throws IOException {
        long offset = coordinate - origin;
        if (offset <= Integer.MIN_VALUE || offset > Integer.MAX_VALUE) throw new IOException("原生蓝图超出此施工方式的坐标范围");
        return (int) offset;
    }

    private static String toolId(ItemStack item) {
        return item == null || !item.hasTagCompound() ? ""
            : item.getTagCompound()
                .getString(HologramSessionKeys.TOOL_ID);
    }

    /** Same elapsed-time budget as existing world work, never cells per tick. */
    static void tick(long deadline) {
        var it = JOBS.values()
            .iterator();
        while (it.hasNext()) if (it.next()
            .step(deadline)) it.remove();
    }

    public static void stopServer() {
        for (Job job : JOBS.values()) job.stop(Phase.CANCELLED, "世界已关闭");
        JOBS.clear();
        for (Attempt pending : PENDING) {
            pending.stop();
            pending.release();
        }
        PENDING.clear();
        HologramPrivateNativeServer.stopLive();
    }

    private static final class Job {

        final Attempt attempt;
        final Prepared prepared;
        final HologramWorkspace.ActionEndpoint endpoint;
        final HologramConstructionTaskGate gate;
        final HologramConstructionTaskGate.Lease lease;
        final HologramSession tx;
        final HologramProjectedMainPolicy.Snapshot configured;
        final HologramMaterials.Context stock;
        final String toolId;
        final int[] anchor;
        final HologramPreviewPose pose;
        final NativeBlueprintSpatialView.Cursor cursor;
        final int frameX, frameY, frameZ;
        final int[] rotated = new int[3];
        final Map<Long, Target> samples;
        final EntityPlayerMP player;
        final World world;
        final boolean creative;
        long activeDescriptor = Long.MIN_VALUE;
        Target activeTarget;
        int placed, satisfied;
        boolean uncertain;

        Job(Attempt attempt, Prepared prepared, HologramWorkspace.ActionEndpoint endpoint,
            HologramConstructionTaskGate gate, HologramConstructionTaskGate.Lease lease, HologramSession tx,
            HologramProjectedMainPolicy.Snapshot configured, HologramMaterials.Context stock, String toolId,
            int[] anchor, HologramPreviewPose pose, Map<Long, Target> samples, boolean creative) {
            this.creative = creative;
            this.attempt = attempt;
            this.prepared = prepared;
            this.endpoint = endpoint;
            this.gate = gate;
            this.lease = lease;
            this.tx = tx;
            this.configured = configured;
            this.stock = stock;
            this.toolId = toolId;
            this.anchor = anchor;
            this.pose = pose;
            this.cursor = prepared.source.cursor();
            var frame = prepared.source.definition()
                .frame();
            frameX = frame.anchorX;
            frameY = frame.anchorY;
            frameZ = frame.anchorZ;
            this.samples = samples;
            player = endpoint.player;
            world = player.worldObj;
        }

        boolean current() {
            return !attempt.cancelled.get() && HologramWorkspace.currentActionEndpoint(endpoint)
                && player.worldObj == world
                && !player.isDead
                && player.capabilities.allowEdit
                && player.capabilities.isCreativeMode == creative
                && player.inventory.currentItem == tx.slot
                && toolId.equals(toolId(player.getHeldItem()))
                && tx.tool == player.getHeldItem()
                    .getItem()
                && configured.configuredMatches(HologramMaterials.read(player.getHeldItem()));
        }

        boolean step(long deadline) {
            if (!current()) return stop(Phase.CANCELLED, "施工已停止，未完成部分不再扣料");
            int old = placed + satisfied;
            try {
                while (cursor.hasNext() && System.nanoTime() < deadline) {
                    if (!current()) return stop(Phase.CANCELLED, "施工已停止，未完成部分不再扣料");
                    cursor.advance();
                    long descriptor = cursor.descriptor();
                    if (descriptor != activeDescriptor) {
                        activeDescriptor = descriptor;
                        // Only included descriptors have resolved, authenticated targets.
                        activeTarget = samples.get(descriptor);
                    }
                    if (activeTarget != null && !place(cursor.x(), cursor.y(), cursor.z(), activeTarget)) return stop(
                        attempt.cancelled.get() ? Phase.CANCELLED : Phase.FAILED,
                        uncertain ? "施工结果待核定，已停止" : "施工受阻或缺料，已停止");
                }
            } catch (IOException | RuntimeException | LinkageError invalid) {
                return stop(Phase.FAILED, uncertain ? "施工结果待核定，已停止" : "读取或执行原生蓝图失败，已停止");
            }
            if (!cursor.hasNext()) return stop(Phase.COMPLETE, "原生蓝图施工完成");
            if (old != placed + satisfied)
                attempt.publish(Phase.EXECUTING, placed, satisfied, prepared.total, "原生蓝图施工中");
            return false;
        }

        boolean place(int baseX, int baseY, int baseZ, Target target) {
            pose.apply(
                Math.subtractExact(baseX, frameX),
                Math.subtractExact(baseY, frameY),
                Math.subtractExact(baseZ, frameZ),
                rotated);
            int x = Math.addExact(anchor[0], rotated[0]), y = Math.addExact(anchor[1], rotated[1]),
                z = Math.addExact(anchor[2], rotated[2]);
            if (!HologramWorldFlow.worldPosition(x, y, z, world.getHeight()) || !world.blockExists(x, y, z))
                return false;
            Permit permit = gate.beginTransaction(lease);
            if (permit == null) return false;
            boolean settled = false;
            try {
                var capture = new HologramCapture(null, HologramSessionPolicy.trigger(tx));
                var cell = new HologramCapture.Cell(null, x, y, z);
                cell.block = target.block;
                cell.meta = target.metadata;
                cell.blueprintBlock = target.blockId;
                cell.blueprintItem = target.itemId;
                cell.blueprintDamage = target.sample.getItemDamage();
                cell.tileNbt = target.tile;
                cell.itemNbt = target.item;
                cell.userPosition = true;
                cell.role = target.controller ? "templateController" : "prefab";
                cell.stableIndex = 0;
                capture.cells.add(cell);
                tx.capture = capture;
                tx.pins.put(0, target.sample.copy());
                tx.planTargets.add(target.sample.copy());
                if (target.facing >= 0) tx.planFacings.put(0, target.facing);
                tx.materialContext = stock;
                boolean applied = HologramWorldTransactions.operateSavedNative(
                    tx,
                    cell,
                    0,
                    target.stateful,
                    () -> current() && gate.transactionCurrent(permit));
                if (tx.job == 4 || HologramConstructionJournal.pending(player)) return false;
                boolean committed = applied && "placed".equals(cell.status);
                boolean wasSatisfied = applied && "satisfied".equals(cell.status);
                settled = gate.settle(permit, committed ? Settlement.COMMITTED : Settlement.ROLLED_BACK);
                if (settled && committed) placed++;
                if (settled && wasSatisfied) satisfied++;
                return settled && (committed || wasSatisfied);
            } finally {
                if (!settled) {
                    uncertain = true;
                    gate.settle(permit, Settlement.RECOVERY_PENDING);
                }
                tx.materialContext = null;
                tx.planTargets.clear();
                tx.planFacings.clear();
                tx.pins.clear();
                tx.capture = null;
            }
        }

        boolean stop(Phase phase, String detail) {
            if (!uncertain) {
                if (phase == Phase.CANCELLED) gate.cancel(lease.scope, lease.request);
                else if (!gate.finish(lease, phase == Phase.COMPLETE ? End.COMPLETE : End.FAILED)
                    && phase == Phase.COMPLETE) {
                        phase = Phase.FAILED;
                        detail = "施工结果不能确认";
                    }
            }
            attempt.publish(phase, placed, satisfied, prepared.total, detail);
            return true;
        }
    }
}
