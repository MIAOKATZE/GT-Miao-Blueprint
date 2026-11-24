// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramProjectedPlacementGuardTrace.Phase;
import com.miaokatze.gtmb.hologram.HologramProjectedPlacementGuardTrace.Reason;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** Turns an explicit material-review request into the existing server-owned placement transaction. */
final class HologramProjectedPlacementService {

    private static final int MAX_PENDING = 8;
    private static final int RECENT_LIMIT = 1024;
    private static final Map<UUID, Job> PENDING = new LinkedHashMap<>();
    private static final Map<UUID, QuoteJob> QUOTES = new LinkedHashMap<>();
    private static final Set<Request> RECENT = new LinkedHashSet<>();

    private HologramProjectedPlacementService() {}

    static boolean consumed(EntityPlayerMP player, String id) {
        return RECENT.contains(new Request(player.getUniqueID(), id));
    }

    static boolean preparing(EntityPlayerMP player) {
        return PENDING.containsKey(player.getUniqueID());
    }

    static void receive(EntityPlayerMP player, NBTTagCompound tag) {
        String id = tag.getString("placementRequestId");
        try {
            HologramPlacementRequest.Header header = HologramPlacementRequest.header(tag);
            UUID owner = player.getUniqueID();
            if (RECENT.contains(new Request(owner, id))) return;
            if (HologramPlacementUpload.active(player)) throw new IOException("已有施工清单正在上传，请稍后重试");
            Job pending = PENDING.get(owner);
            if (pending != null && pending.header.id()
                .equals(id)) return;
            if (pending != null || PENDING.size() + QUOTES.size() >= MAX_PENDING)
                throw new IOException("已有施工核对任务，请稍后重试");
            Job job = new Job(player, header, tag);
            job.requireCurrent(Phase.RECEIVE);
            if (!HologramAreaPreparation.execute(job.decoding)) throw new IOException("施工准备队列繁忙，请稍后重试");
            PENDING.put(owner, job);
        } catch (IOException invalid) {
            if (id.length() == 36) reply(player, id, false, invalid.getMessage());
        }
    }

    static void checkMaterials(EntityPlayerMP player, NBTTagCompound tag) {
        String id = tag.getString("placementRequestId");
        try {
            UUID owner = player.getUniqueID();
            if (RECENT.contains(new Request(owner, id))) return;
            QuoteJob pending = QUOTES.get(owner);
            if (pending != null && pending.id.equals(id)) return;
            if (pending != null || PENDING.size() + QUOTES.size() >= MAX_PENDING)
                throw new IOException("已有材料核对任务，请稍后重试");
            QuoteJob job = new QuoteJob(player, id, HologramPlacementRequest.materialCheck(tag),
                HologramPlacementRequest.sourceOptions(tag));
            job.requireCurrent();
            QUOTES.put(owner, job);
        } catch (IOException invalid) {
            if (id.length() == 36) reply(player, id, false, invalid.getMessage());
        }
    }

    static void cancel(EntityPlayerMP player, String id) {
        try {
            if (id.length() != 36 || !UUID.fromString(id).toString().equals(id)) return;
        } catch (IllegalArgumentException invalid) {
            return;
        }
        HologramPlacementUpload.cancel(player, id);
        retire(player, id);
        QuoteJob quote = QUOTES.get(player.getUniqueID());
        if (quote != null && quote.player == player && quote.id.equals(id)) {
            QUOTES.remove(player.getUniqueID());
            return;
        }
        Job job = PENDING.get(player.getUniqueID());
        if (job != null && job.player == player
            && job.header.id()
                .equals(id)) {
            PENDING.remove(player.getUniqueID());
            HologramAreaPreparation.cancel(job.decoding);
            return;
        }
        HologramSession published = HologramWorkspace.find(player.getUniqueID());
        if (published != null && published.player == player
            && published.job == 0
            && published.executionRequestId.equals(id) && !id.isEmpty()) {
            NBTTagCompound cancel = new NBTTagCompound();
            cancel.setString("session", published.id);
            cancel.setLong("planRevision", published.planRevision);
            HologramWorldPlacementWorkflow.cancelWorldPlacement(player, cancel);
        }
    }

    static void forget(EntityPlayerMP player) {
        QUOTES.remove(player.getUniqueID());
        Job job = PENDING.remove(player.getUniqueID());
        if (job != null) HologramAreaPreparation.cancel(job.decoding);
    }

    static void clear() {
        PENDING.values()
            .forEach(job -> HologramAreaPreparation.cancel(job.decoding));
        PENDING.clear();
        QUOTES.clear();
        RECENT.clear();
    }

    static void tick() {
        long deadline = HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(2_000_000L));
        tickQuotes(deadline);
        Iterator<Job> pending = PENDING.values()
            .iterator();
        while (pending.hasNext() && System.nanoTime() < deadline) {
            Job job = pending.next();
            try {
                if (!job.step(deadline)) continue;
                retire(job.player, job.header.id());
                pending.remove();
            } catch (IOException | ExecutionException failure) {
                HologramAreaPreparation.cancel(job.decoding);
                Throwable reason = failure instanceof ExecutionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
                reply(job.player, job.header.id(), false, reason.getMessage());
                pending.remove();
            } catch (RuntimeException | LinkageError failure) {
                HologramAreaPreparation.cancel(job.decoding);
                GTMiaoBlueprint.LOG.warn("Projected placement preparation failed", failure);
                reply(job.player, job.header.id(), false, "施工准备失败，未进行扣料或建造");
                pending.remove();
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                return;
            }
        }
    }

    private static void tickQuotes(long deadline) {
        Iterator<QuoteJob> jobs = QUOTES.values().iterator();
        while (jobs.hasNext() && System.nanoTime() < deadline) {
            QuoteJob job = jobs.next();
            try {
                if (!job.step(deadline)) continue;
                retire(job.player, job.id);
            } catch (IOException | RuntimeException | LinkageError invalid) {
                reply(job.player, job.id, false, "无法核对材料库存：" + HologramAePolicy.fallback(invalid.getMessage()));
            }
            jobs.remove();
        }
    }

    static void retire(EntityPlayerMP player, String id) {
        if (RECENT.size() >= RECENT_LIMIT && !consumed(player, id)) {
            Iterator<Request> entries = RECENT.iterator();
            entries.next();
            entries.remove();
        }
        RECENT.add(new Request(player.getUniqueID(), id));
    }

    @Desugar
    private record Request(UUID player, String id) {}

    static void reply(EntityPlayerMP player, String id, boolean accepted, String reason) {
        reply(player, id, accepted, reason, null);
    }

    private static void reply(EntityPlayerMP player, String id, boolean accepted, String reason, NBTTagCompound quote) {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("placementRequestId", id);
        receipt.setBoolean("placementRequestAccepted", accepted);
        if (reason != null) receipt.setString("errorMessage", reason);
        if (quote != null) receipt.setTag("materialCheckQuote", quote);
        HologramNetwork.sendState(player, receipt);
    }

    private static final class QuoteJob {

        private final EntityPlayerMP player;
        private final String id;
        private final World world;
        private final int toolSlot;
        private final ItemStack tool;
        private final HologramMaterials.Settings settings;
        private final long started = System.nanoTime();
        private final Iterator<Map.Entry<HologramPlacementRequest.Material, Integer>> materials;
        private final NBTTagList rows = new NBTTagList();

        private QuoteJob(EntityPlayerMP player, String id, Map<HologramPlacementRequest.Material, Integer> materials,
            HologramPlacementRequest.SourceOptions sources) {
            this.player = player;
            this.id = id;
            world = player.worldObj;
            toolSlot = player.inventory.currentItem;
            tool = player.getHeldItem();
            settings = HologramMaterials.read(tool);
            sources.apply(settings);
            this.materials = materials.entrySet().iterator();
        }

        private void requireCurrent() throws IOException {
            if (System.nanoTime() - started > 30_000_000_000L || !HologramNetwork.progressConnected(player)
                || player.isDead || player.worldObj != world || player.inventory.currentItem != toolSlot
                || player.getHeldItem() != tool || !ItemNekoHologramProjector.isProjector(tool))
                throw new IOException("材料核对上下文已经变化，请重试");
        }

        private boolean step(long deadline) throws IOException {
            requireCurrent();
            HologramMaterials.Context stock = new HologramMaterials.Context(player, settings, toolSlot);
            while (materials.hasNext() && System.nanoTime() < deadline) {
                Map.Entry<HologramPlacementRequest.Material, Integer> row = materials.next();
                HologramPlacementRequest.Material material = row.getKey();
                Object registered = Item.itemRegistry.getObject(material.item());
                if (!(registered instanceof Item item)) throw new IOException("服务器缺少材料 " + material.item());
                ItemStack sample = new ItemStack(item, 1, material.damage());
                int needed = row.getValue();
                long available = player.capabilities.isCreativeMode ? needed : stock.countLong(sample, needed);
                rows.appendTag(HologramMaterialQuote.itemRow(sample, needed, available));
            }
            if (materials.hasNext()) return false;
            NBTTagCompound quote = new NBTTagCompound();
            quote.setBoolean("complete", true);
            quote.setTag("rows", rows);
            reply(player, id, true, null, quote);
            return true;
        }
    }

    private static final class Job {

        private final EntityPlayerMP player;
        private final World world;
        private final int toolSlot;
        private final HologramSessionPolicy.PreparationTool tool;
        private final HologramSession previous;
        private final long previousRevision, previousWorkspace;
        private final HologramPlacementRequest.Header header;
        private final long started = System.nanoTime();
        private final FutureTask<HologramPlacementRequest.Decoded> decoding;
        private final List<Resolved> variants = new ArrayList<>();
        private HologramPlacementRequest.Decoded decoded;
        private HologramSession session;
        private int operation, pass;

        private Job(EntityPlayerMP player, HologramPlacementRequest.Header header, NBTTagCompound tag) {
            this.player = player;
            this.header = header;
            world = player.worldObj;
            toolSlot = player.inventory.currentItem;
            tool = new HologramSessionPolicy.PreparationTool(player.getHeldItem());
            previous = HologramWorkspace.find(player.getUniqueID());
            previousRevision = previous == null ? -1 : previous.planRevision;
            previousWorkspace = previous == null ? -1 : previous.editorWorkspaceEpoch;
            int height = world.getHeight();
            decoding = new FutureTask<>(() -> HologramPlacementRequest.decode(header, tag, height));
        }

        private void requireCurrent(Phase phase) throws IOException {
            Reason rejection = firstRejection();
            if (rejection != null)
                throw HologramProjectedPlacementGuardTrace
                    .failure(phase, rejection, line -> GTMiaoBlueprint.LOG.info(line));
        }

        /** Preserve the former short-circuit order; diagnosing an early failure does not probe later conditions. */
        private Reason firstRejection() {
            if (System.nanoTime() - started > 90_000_000_000L) return Reason.TIMEOUT;
            if (!HologramNetwork.progressConnected(player)) return Reason.CONNECTION_CLOSED;
            if (player.worldObj != world) return Reason.WORLD_CHANGED;
            if (player.inventory.currentItem != toolSlot) return Reason.SLOT_CHANGED;
            ItemStack held = player.getHeldItem();
            if (!tool.matches(held)) return Reason.STACK_REPLACED;
            if (!ItemNekoHologramProjector.isProjector(held)) return Reason.NOT_PROJECTOR;
            if (!player.capabilities.allowEdit) return Reason.EDIT_DISABLED;
            if (HologramWorkspace.find(player.getUniqueID()) != previous) return Reason.WORKSPACE_CHANGED;
            if (!HologramWorldFlow.worldPosition(header.x(), header.y(), header.z(), world.getHeight()))
                return Reason.ANCHOR_BOUNDS;
            if (!HologramSessionPolicy.previewReach(player, header.x(), header.y(), header.z()))
                return Reason.ANCHOR_REACH;
            if (!world.blockExists(header.x(), header.y(), header.z())) return Reason.ANCHOR_NOT_LOADED;

            HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
            if (HologramDemolition.active(player)) return Reason.ACTIVE_DEMOLITION;
            if (HologramAeCraftingOrders.active(player)) return Reason.ACTIVE_AE_ORDER;
            if (area != null && (area.selecting || area.importing)) return Reason.AREA_BUSY;
            if (previous != null) {
                if (previous.planRevision != previousRevision) return Reason.PREVIOUS_REVISION_CHANGED;
                if (previous.editorWorkspaceEpoch != previousWorkspace) return Reason.PREVIOUS_EPOCH_CHANGED;
                if (previous.job == 1 || previous.job == 2) return Reason.PREVIOUS_JOB_BUSY;
                if (previous.capturePending) return Reason.PREVIOUS_CAPTURE_PENDING;
                if (HologramAuthoringWorkflow.blueprintHasUnsavedChanges(previous)) return Reason.PREVIOUS_UNSAVED;
            }
            return null;
        }

        private boolean step(long deadline) throws IOException, ExecutionException, InterruptedException {
            requireCurrent(Phase.WAIT);
            if (decoded == null) {
                if (!decoding.isDone()) return false;
                decoded = decoding.get();
            }
            if (session == null) {
                session = new HologramSession(player, header.x(), header.y(), header.z(), false, 1);
                session.virtual = true;
                session.virtualPlacement = true;
                session.virtualLocked = true;
                header.sources().apply(session.materialSettings);
                session.executionRequestId = header.id();
                session.virtualPreset = new HologramPresets.Preset(); // Metadata expected by the existing transaction UI.
                session.virtualPreset.schema = 2;
                session.virtualPreset.id = "operation_" + header.id();
                session.virtualPreset.name = "当前施工";
                session.virtualPreset.retainNbt = true;
                session.virtualPreset.retainCovers = true;
                session.virtualPreset.deidentificationLevel = 1;
                session.placementDeidentificationChosen = true;
                session.capture = new HologramCapture(null, HologramSessionPolicy.trigger(session));
            }
            while (variants.size() < decoded.variants().size() && System.nanoTime() < deadline) {
                Resolved resolved = new Resolved(decoded.variants().get(variants.size()));
                if (resolved.network != null) {
                    String group = HologramNetworkBindings.group(resolved.network);
                    session.executionNetworks.putIfAbsent(group, resolved.network);
                    if (session.executionNetworks.size() > 64) throw new IOException("施工量子网络组过多，请拆分蓝图");
                }
                variants.add(resolved);
            }
            if (variants.size() < decoded.variants().size()) return false;
            while (pass < 2 && System.nanoTime() < deadline) {
                if (operation == decoded.size()) {
                    operation = 0;
                    pass++;
                    continue;
                }
                Resolved resolved = variants.get(decoded.variantIndex(operation));
                HologramProjectedPlacement.Cell target = decoded.cell(operation++);
                if (target.controller() == (pass == 1)) add(target, resolved);
            }
            if (pass < 2) return false;
            requireCurrent(Phase.PUBLISH);
            HologramWorkspace.activate(player.getUniqueID(), session);
            if (!HologramProjectorWorkflow.openPlacementReview(session, player))
                throw new IOException("材料核对未能启动，请重新确认");
            reply(player, header.id(), true, null);
            return true;
        }

        private void add(HologramProjectedPlacement.Cell target, Resolved resolved) {
            HologramCapture.Cell cell = new HologramCapture.Cell(
                null, header.x() + target.x(), header.y() + target.y(), header.z() + target.z());
            cell.block = resolved.block;
            cell.meta = target.metadata();
            cell.blueprintBlock = target.block();
            cell.blueprintItem = target.item();
            cell.blueprintDamage = target.damage();
            cell.tileNbt = target.tile();
            cell.itemNbt = target.itemState();
            cell.userPosition = true;
            cell.role = target.controller() ? "templateController" : "prefab";
            cell.stableIndex = session.capture.cells.size();
            String position = HologramValues.position(cell);
            session.capture.cells.add(cell);
            session.capture.positions.put(position, cell);
            session.pins.put(cell.stableIndex, resolved.material.copy());
            session.coordinatePins.put(position, resolved.material.copy());
            if (target.facing() >= 0) session.facingPins.put(position, target.facing());
        }
    }

    private static final class Resolved {

        final Block block;
        final ItemStack material;
        final NBTTagCompound network;

        Resolved(HologramPlacementRequest.Variant variant) throws IOException {
            Object registered = Item.itemRegistry.getObject(variant.item());
            Object target = Block.blockRegistry.getObject(variant.block());
            if (!(registered instanceof Item item) || !(target instanceof Block found))
                throw new IOException("服务器无法识别施工方块或耗材");
            block = found;
            NBTTagCompound state = HologramExecutionEligibility.payload(variant.tile());
            NBTTagCompound itemState = HologramExecutionEligibility.payload(variant.itemState());
            material = HologramEssentia.shellMaterial(new ItemStack(item, 1, variant.damage()));
            HologramExecutionEligibility.validate(
                block, variant.metadata(), material, variant.facing(), variant.controller(), state, itemState);
            if (HologramNativePlacement.supported(block, state))
                material.setTagCompound(HologramBlueprintNbt.nativePayload(itemState));
            if (state != null && state.hasKey(HologramNetworkBindings.GROUP)) {
                network = new NBTTagCompound();
                for (String key : new String[] { HologramNetworkBindings.GROUP, HologramNetworkBindings.ROLE,
                    HologramNetworkBindings.HINT })
                    if (state.hasKey(key)) network.setString(key, state.getString(key));
            } else network = null;
        }
    }
}
