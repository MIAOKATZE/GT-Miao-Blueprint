// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.World;

import com.miaokatze.gtmb.api.MaterialVariants;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Receiver;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Ticket;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Variant;

/**
 * Internal plain blueprint execution. This owner never installs its private transaction state in the workspace.
 * Only the dedicated scoped boundary may be exposed by a production packet adapter.
 */
final class HologramProjectedLayerService {

    static final int MAX_SLICES = (HologramProjectedLayerWire.MAX_CELLS
        + HologramProjectedLayerWire.MAX_SLICE_CELLS - 1) / HologramProjectedLayerWire.MAX_SLICE_CELLS + 514;
    // One partial chunk per admitted Y level (anchor +/-256), plus the first responsive prefix.
    private static final int MAX_OWNERS = 8;
    private static final int MAX_OFFSET = 256; // This small lane never admits remote arbitrary world coordinates.
    private static final long MAX_IDLE_NANOS = 30_000_000_000L, MAX_PAUSED_NANOS = 300_000_000_000L;
    // Logical server thread only. A future ingress must also gate untrusted messages before queueing them.
    private static final Map<UUID, HologramProjectedLayerService> ACTIVE = new HashMap<>();

    enum StockContract {
        OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL
    }

    enum Phase {
        AWAITING_SLICE, VALIDATING_SLICE, PLACING, PAUSED, COMPLETE, STOPPED
    }

    enum Reason {
        NONE, CANCELLED, TIMEOUT, OWNER_CHANGED, INVALID_SLICE, WORLD_CHANGED, BLOCKED, MISSING_STOCK,
        RECOVERY_PENDING, UNSUPPORTED_TRANSACTION
    }

    /** Identifies a proven world/payment settlement, not the transport ACK or an attempted world write. */
    enum Settlement {
        COMMITTED, ROLLED_BACK, RECOVERY_PENDING, OWNER_CHANGED, DEFERRED
    }

    private enum CellResult {
        COMMITTED(Reason.NONE), DEFERRED(Reason.NONE), RECOVERY_PENDING(Reason.RECOVERY_PENDING),
        OWNER_CHANGED(Reason.OWNER_CHANGED), MISSING_STOCK(Reason.MISSING_STOCK), BLOCKED(Reason.BLOCKED),
        UNSUPPORTED(Reason.UNSUPPORTED_TRANSACTION);

        final Reason failure;

        CellResult(Reason failure) {
            this.failure = failure;
        }
    }

    interface ScopedBoundary {

        boolean current();

        Settlement transact(HologramSession transaction, HologramCapture.Cell cell, BooleanSupplier currentInputs);
    }

    static final class AdmissionFailure extends IOException {

        final Reason reason;

        AdmissionFailure(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    static final class Status {

        final UUID request;
        final long sequence;
        final Phase phase;
        final Reason reason;
        final int acceptedSlices, settledSlices, totalSlices, placed, alreadySatisfied, totalCells;
        final int validatedInSlice, sliceCells;

        private Status(HologramProjectedLayerService task) {
            request = task.header.request;
            sequence = task.sequence;
            phase = task.phase;
            reason = task.reason;
            acceptedSlices = task.acceptedSlices;
            settledSlices = task.settledSlices;
            totalSlices = task.header.slices;
            placed = task.placed;
            alreadySatisfied = task.satisfied;
            totalCells = task.header.cells;
            validatedInSlice = task.initiallySatisfied == null ? 0 : task.inspected;
            sliceCells = task.initiallySatisfied == null ? 0 : task.initiallySatisfied.length;
        }
    }

    private static final class Material {

        final Block block;
        final ItemStack item;
        int remaining;

        Material(Block block, ItemStack item, int count) {
            this.block = block;
            this.item = item;
            remaining = count;
        }
    }

    /** Original admission authority only; constructor/provider callbacks may not replace any of these facts. */
    private static final class AdmissionFence {

        final EntityPlayerMP player;
        final UUID actor;
        final World world;
        final NetHandlerPlayServer connection;
        final int dimension, slot;
        final InventoryPlayer inventory;
        final ItemStack[] slots;
        final HologramSession origin;
        final long workspaceEpoch, planRevision, nativeRules, variants, adapters, descriptors;
        final ScopedBoundary boundary;
        final HologramSessionPolicy.PreparationTool held;
        final HologramProjectedMainPolicy.Snapshot configured;
        final String originalToolId;

        AdmissionFence(EntityPlayerMP player, ScopedBoundary boundary) {
            this.player = player;
            this.boundary = boundary;
            actor = player.getUniqueID();
            origin = HologramWorkspace.find(actor);
            workspaceEpoch = origin == null ? -1 : origin.editorWorkspaceEpoch;
            planRevision = origin == null ? -1 : origin.planRevision;
            world = player.worldObj;
            connection = player.playerNetServerHandler;
            dimension = player.dimension;
            inventory = player.inventory;
            slots = inventory.mainInventory;
            slot = inventory.currentItem;
            ItemStack submitted = slot >= 0 && slot < slots.length ? slots[slot] : null;
            nativeRules = HologramNativePlacement.generation();
            variants = MaterialVariants.generation();
            adapters = HologramDeclarationAdapters.admissionGeneration();
            descriptors = HologramMaterialDescriptor.generation();
            held = new HologramSessionPolicy.PreparationTool(submitted);
            configured = HologramProjectedMainPolicy.captureSelected(HologramMaterials.read(submitted));
            originalToolId = toolId(submitted);
        }

        /** No world/provider callbacks here. This is also repeated after every live readiness probe. */
        private boolean pinned() {
            return configured != null && slot >= 0 && slot < slots.length
                && world != null && player.worldObj == world && player.dimension == dimension
                && player.playerNetServerHandler == connection && player.inventory == inventory
                && inventory.mainInventory == slots && inventory.currentItem == slot
                && !player.isDead && player.capabilities.allowEdit && !player.capabilities.isCreativeMode
                && HologramWorkspace.find(actor) == origin
                && (origin == null || origin.editorWorkspaceEpoch == workspaceEpoch && origin.planRevision == planRevision)
                && HologramNativePlacement.generation() == nativeRules && MaterialVariants.generation() == variants
                && HologramDeclarationAdapters.admissionGeneration() == adapters
                && HologramMaterialDescriptor.generation() == descriptors
                && held.matches(slots[slot]) && ItemNekoHologramProjector.isProjector(slots[slot])
                && (originalToolId.isEmpty() || originalToolId.equals(toolId(slots[slot])))
                && configured.configuredMatches(HologramMaterials.read(slots[slot]))
                && (boundary == null || boundary.current());
        }

        boolean ready(Header header) {
            return pinned() && header.maxY < world.getHeight() && anchor(player, header, true)
                && available(player, origin)
                && !ACTIVE.containsKey(actor) && ACTIVE.size() < MAX_OWNERS
                && !HologramConstructionReceipts.consumed(player, header.request.toString()) && pinned();
        }

        boolean transactionMatches(HologramSession tx) {
            ItemStack item = slots[slot];
            return tx.libraryWorld == world && tx.dimension == dimension && tx.slot == slot && item != null
                && tx.tool == item.getItem() && !tx.toolId.isEmpty() && tx.toolId.equals(toolId(item));
        }

        private static String toolId(ItemStack item) {
            return item == null || !item.hasTagCompound() ? ""
                : item.getTagCompound().getString(HologramSessionKeys.TOOL_ID);
        }
    }

    private final Thread owner = Thread.currentThread();
    private final EntityPlayerMP player;
    private final World world;
    private final NetHandlerPlayServer connection;
    private final InventoryPlayer inventory;
    private final ItemStack[] inventorySlots;
    private final UUID actor;
    private final Header header;
    private final Receiver receiver;
    private final HologramLayerCadence layerCadence;
    private final Material[] materials;
    private final HologramSession origin;
    private final HologramSession transaction;
    private final HologramProjectedMainPolicy.Snapshot configuredSources;
    private final HologramMaterials.Settings selectedScope;
    private final long workspaceEpoch, planRevision, nativeGeneration, materialGeneration, adapterGeneration;
    private final long descriptorGeneration;
    private final LongSupplier clock;
    private final ScopedBoundary boundary;
    private long lastWork, pausedSince, pausedTotal;
    private long sequence;
    private int acceptedSlices, settledSlices, placed, satisfied, inspected, cursor;
    private boolean busy;
    private boolean[] initiallySatisfied;
    private Phase phase = Phase.AWAITING_SLICE, beforePause;
    private Reason reason = Reason.NONE;

    private HologramProjectedLayerService(AdmissionFence proof, Manifest manifest, Material[] materials,
        HologramSession transaction, LongSupplier clock, long now, HologramMaterials.Settings selectedScope) {
        player = proof.player;
        origin = proof.origin;
        this.materials = materials;
        this.transaction = transaction;
        this.clock = clock;
        boundary = proof.boundary;
        configuredSources = proof.configured;
        this.selectedScope = selectedScope;
        lastWork = now;
        actor = proof.actor;
        world = proof.world;
        connection = proof.connection;
        inventory = proof.inventory;
        inventorySlots = proof.slots;
        header = manifest.header;
        layerCadence = new HologramLayerCadence(header.minY);
        receiver = new Receiver(manifest);
        workspaceEpoch = proof.workspaceEpoch;
        planRevision = proof.planRevision;
        nativeGeneration = proof.nativeRules;
        materialGeneration = proof.variants;
        adapterGeneration = proof.adapters;
        descriptorGeneration = proof.descriptors;
    }

    /** The bound owner submits an actual construction intent. This slice observes the selected stock and debits only successful transactions. */
    static HologramProjectedLayerService begin(EntityPlayerMP player, Manifest manifest, StockContract contract)
        throws IOException {
        return begin(player, manifest, contract, System::nanoTime);
    }

    static HologramProjectedLayerService begin(EntityPlayerMP player, Manifest manifest, StockContract contract,
        LongSupplier clock) throws IOException {
        return begin(player, manifest, contract, clock, null);
    }

    static HologramProjectedLayerService beginScoped(EntityPlayerMP player, Manifest manifest, ScopedBoundary boundary)
        throws IOException {
        if (boundary == null) throw refusal();
        return begin(player, manifest, StockContract.OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL, System::nanoTime, boundary);
    }

    /** Profile 2 is an exact bounded metadata/source review only. It cannot enter the profile-1 world driver. */
    static HologramProjectedLayerVariantAdmission reviewScopedVariants(HologramWorkspace.ActionEndpoint endpoint,
        HologramConstructionTaskGate.Lease lease, Manifest exactManifest) throws IOException {
        return new HologramProjectedLayerVariantAdmission(endpoint, lease, exactManifest);
    }

    private static HologramProjectedLayerService begin(EntityPlayerMP player, Manifest manifest, StockContract contract,
        LongSupplier clock, ScopedBoundary boundary) throws IOException {
        if (contract != StockContract.OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL || player == null || manifest == null
            || manifest.header.profile != HologramProjectedLayerWire.VERSION || clock == null) throw refusal();
        Header header = manifest.header;
        var proof = new AdmissionFence(player, boundary);
        if (header.slices > MAX_SLICES || !nearAnchor(header) || !proof.ready(header) || hasKnownRecovery(player))
            throw refusal();
        Material[] materials = new Material[header.palette.size()];
        Map<Item, Material> exact = new HashMap<>();
        for (int index = 0; index < materials.length; index++) {
            Variant row = header.palette.get(index);
            Material material = resolve(row);
            if (exact.put(material.item.getItem(), material) != null) throw refusal();
            materials[index] = material;
        }
        if (!proof.ready(header)) throw refusal();
        HologramMaterials.Settings selectedScope = proof.configured.selectedScope();
        HologramMaterials.Context stock = new HologramMaterials.Context(player, selectedScope, proof.slot);
        requireSelectedStock(materials, stock);
        if (!proof.ready(header)) throw refusal();
        HologramSession tx = new HologramSession(player, header.x, header.y, header.z, false, 1);
        // The constructor calls markDirty and reads mutable world time. Do not bless a newer workspace or source.
        if (!proof.ready(header) || !proof.configured.configuredMatches(tx.materialSettings)
            || !proof.transactionMatches(tx)) throw refusal();
        requireSelectedStock(materials, stock);
        tx.materialSettings = selectedScope;
        tx.virtual = tx.virtualPlacement = tx.virtualLocked = true;
        tx.job = 1;
        tx.executionRequestId = header.request.toString();
        tx.virtualPreset = new HologramPresets.Preset();
        tx.virtualPreset.schema = 2;
        tx.virtualPreset.retainNbt = false;
        tx.virtualPreset.retainCovers = false;
        tx.virtualPreset.deidentificationLevel = 0;
        tx.virtualPreset.deidentificationChosen = true;
        tx.placementDeidentificationChosen = true;
        long now = clock.getAsLong();
        if (hasKnownRecovery(player) || !proof.ready(header) || !proof.transactionMatches(tx)) throw refusal();
        requireSelectedStock(materials, stock);
        if (!proof.pinned()) throw refusal();
        var service = new HologramProjectedLayerService(proof, manifest, materials, tx, clock, now, selectedScope);
        HologramConstructionReceipts.retire(player, header.request.toString());
        ACTIVE.put(proof.actor, service);
        return service;
    }

    static boolean active(EntityPlayerMP player) {
        return player != null && ACTIVE.containsKey(player.getUniqueID());
    }

    static boolean competingWork(EntityPlayerMP player) {
        return active(player) || !quiet(player, HologramWorkspace.find(player.getUniqueID()));
    }

    private static Material resolve(Variant row) throws IOException {
        Block block;
        if ("minecraft:stone".equals(row.block)) block = Blocks.stone;
        else if ("minecraft:cobblestone".equals(row.block)) block = Blocks.cobblestone;
        else if ("minecraft:glass".equals(row.block)) block = Blocks.glass;
        else if ("minecraft:stonebrick".equals(row.block)) block = Blocks.stonebrick;
        else if ("minecraft:quartz_block".equals(row.block)) block = Blocks.quartz_block;
        else if ("minecraft:glowstone".equals(row.block)) block = Blocks.glowstone;
        else throw refusal();
        // Explicit independent one-cell shells only: no state, support relationships, substitutions or paid NBT.
        if (!row.block.equals(row.item) || row.metadata != 0 || row.damage != 0
            || Block.blockRegistry.getObject(row.block) != block || block.hasTileEntity(0)
            || HologramNativePlacement.supported(block, null) || HologramMaterialDescriptor.hasExact(block)
            || HologramDecorMaterials.supports(block)) throw refusal();
        Item item = Item.getItemFromBlock(block);
        if (!exactVanillaItem(row, block, item)) throw refusal();
        ItemStack sample = new ItemStack(item, 1, 0);
        if (HologramGtswnPlacementMaterials.supplyKind(sample) >= 0
            || HologramGtswnPlacementMaterials.freeShell(block, sample)) throw refusal();
        HologramExecutionEligibility.validate(block, 0, sample, -1, false, null, null);
        return new Material(block, sample, row.count);
    }

    private static boolean exactVanillaItem(Variant row, Block block, Item item) {
        return item != null && item.getClass() == ItemBlock.class && Block.blockRegistry.getObject(row.block) == block
            && Item.getItemFromBlock(block) == item && Item.itemRegistry.getObject(row.item) == item
            && Block.getBlockFromItem(item) == block && item.getMetadata(0) == 0;
    }

    static boolean hasKnownRecovery(EntityPlayerMP player) {
        // pendingWorldRecovery covers world-drop receipts, not every existing item/AE recovery queue.
        return HologramConstructionJournal.pending(player) || HologramMaterials.Context.pendingWorldRecovery(player);
    }

    private static boolean available(EntityPlayerMP player, HologramSession origin) {
        return !player.isDead && player.worldObj != null && !player.worldObj.isRemote
            && player.capabilities.allowEdit && !player.capabilities.isCreativeMode
            && HologramNetwork.progressConnected(player) && quiet(player, origin);
    }

    private static boolean quiet(EntityPlayerMP player, HologramSession origin) {
        if (HologramWorkspace.find(player.getUniqueID()) != origin || HologramDemolition.active(player)
            || HologramAeCraftingOrders.active(player))
            return false;
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        return (area == null || !area.selecting && !area.importing)
            && (origin == null || origin.player == player && HologramEditorWorkspaceRegistry.idle(origin)
                && origin.nativeReadyPlacement == null && origin.nativeCompactStart == null
                && origin.executionStart == null && origin.currentPlacementTask == null
                && origin.presentationTransition == null && !HologramSnapshotPreparation.pending(origin)
                && !HologramAuthoringWorkflow.blueprintHasUnsavedChanges(origin));
    }

    private static boolean anchor(EntityPlayerMP player, Header header, boolean admission) {
        World world = player.worldObj;
        return HologramWorldFlow.worldPosition(header.x, header.y, header.z, world.getHeight())
            && world.blockExists(header.x, header.y, header.z)
            && (!admission || ItemNekoHologramProjector.isProjector(player.getHeldItem())
                && HologramSessionPolicy.previewReach(player, header.x, header.y, header.z));
    }

    private static boolean nearAnchor(Header header) {
        return near(header.x, header.minX, header.maxX) && near(header.y, header.minY, header.maxY)
            && near(header.z, header.minZ, header.maxZ);
    }

    private static boolean near(int anchor, int min, int max) {
        return (long) min >= (long) anchor - MAX_OFFSET && (long) max <= (long) anchor + MAX_OFFSET;
    }

    private static boolean stocked(Material[] materials, HologramMaterials.Context stock) {
        for (Material material : materials) if (material.remaining > 0
            && stock.count(material.item, material.remaining) < material.remaining) return false;
        return true;
    }

    private static void requireSelectedStock(Material[] materials, HologramMaterials.Context stock) throws IOException {
        if (!stocked(materials, stock))
            throw new AdmissionFailure(Reason.MISSING_STOCK, "所选材料来源不足，未发送施工分片，未开工。");
    }

    private static IOException refusal() {
        return new IOException("当前蓝图、施工范围或材料来源不支持分层施工；未开始。");
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner || busy) throw new IllegalStateException("Projected layer owner thread");
    }

    private boolean current() {
        return currentInputs() && anchor(player, header, false) && available(player, origin) && currentInputs();
    }

    /** No world/Forge callback inside the transaction's final authority check. */
    private boolean currentInputs() {
        if (boundary != null && !boundary.current() || inventory != player.inventory
            || inventorySlots != inventory.mainInventory) return false;
        ItemStack tool = HologramSessionPolicy.jobTool(transaction);
        return ACTIVE.get(actor) == this && world == player.worldObj && player.dimension == transaction.dimension
            && connection == player.playerNetServerHandler && tool != null
            && !player.isDead && player.capabilities.allowEdit && !player.capabilities.isCreativeMode
            && ItemNekoHologramProjector.isProjector(tool) && HologramWorkspace.find(actor) == origin
            && (origin == null || origin.editorWorkspaceEpoch == workspaceEpoch && origin.planRevision == planRevision)
            && nativeGeneration == HologramNativePlacement.generation()
            && materialGeneration == MaterialVariants.generation()
            && adapterGeneration == HologramDeclarationAdapters.admissionGeneration()
            && descriptorGeneration == HologramMaterialDescriptor.generation()
            && transaction.materialSettings == selectedScope && configuredSources.selectedScopeMatches(selectedScope)
            && configuredSources.configuredMatches(HologramMaterials.read(tool));
    }

    private HologramMaterials.Context stock() {
        return new HologramMaterials.Context(player, transaction.materialSettings, transaction.slot);
    }

    private boolean terminal() {
        return phase == Phase.COMPLETE || phase == Phase.STOPPED;
    }

    private boolean active() {
        if (terminal()) return false;
        long now = clock.getAsLong();
        if (!current()) stopNow(Reason.OWNER_CHANGED);
        else if (hasKnownRecovery(player)) stopNow(Reason.RECOVERY_PENDING);
        else if (phase == Phase.PAUSED ? now - pausedSince >= MAX_PAUSED_NANOS - pausedTotal
            : now - lastWork >= MAX_IDLE_NANOS) stopNow(Reason.TIMEOUT);
        return !terminal();
    }

    Offer offer(EntityPlayerMP sender, byte[] encoded) throws IOException {
        requireOwner();
        if (sender != player || sender.playerNetServerHandler != connection) return Offer.STALE;
        if (!active()) return Offer.CLOSED;
        busy = true;
        try {
            boolean empty = receiver.pending() == null;
            Offer result = receiver.offer(encoded);
            if (result == Offer.ACCEPTED) {
                if (!stocked(materials, stock())) {
                    stopNow(Reason.MISSING_STOCK);
                    return Offer.CLOSED;
                }
                if (!current()) {
                    stopNow(Reason.OWNER_CHANGED);
                    return Offer.CLOSED;
                }
                acceptedSlices++;
                if (empty) startPending();
                worked();
            }
            return result;
        } catch (IOException invalid) {
            stopNow(Reason.INVALID_SLICE);
            throw invalid;
        } catch (RuntimeException | LinkageError invalid) {
            stopNow(Reason.UNSUPPORTED_TRANSACTION);
            throw invalid;
        } finally {
            busy = false;
        }
    }

    private void startPending() {
        inspected = cursor = 0;
        initiallySatisfied = new boolean[receiver.pending().slice.size()];
        if (phase == Phase.PAUSED) beforePause = Phase.VALIDATING_SLICE;
        else phase = Phase.VALIDATING_SLICE;
    }

    /** Time-bounded journal writes across an ordered bounded window; no fixed cells-per-tick throttle. */
    Status step(long deadline, long serverTick) {
        requireOwner();
        busy = true;
        try {
            if (!active() || phase == Phase.PAUSED || receiver.pending() == null) return new Status(this);
            while (!terminal() && receiver.pending() != null && System.nanoTime() < deadline) {
                Ticket ticket = receiver.pending();
                Slice slice = ticket.slice;
                if (System.nanoTime() >= deadline) return new Status(this);
                if (phase == Phase.VALIDATING_SLICE) {
                    while (inspected < slice.size() && System.nanoTime() < deadline) {
                        int observed = observe(slice, inspected);
                        if (!active()) return new Status(this);
                        if (observed < 0) return failed(Reason.BLOCKED);
                        initiallySatisfied[inspected++] = observed == 1;
                        worked();
                    }
                    if (inspected < slice.size()) return new Status(this);
                    if (!stocked(materials, stock())) return failed(Reason.MISSING_STOCK);
                    phase = Phase.PLACING;
                    advance();
                }
                // The next group may prepare early, but cannot write until ten ticks after the prior group settles.
                if (System.nanoTime() >= deadline || !layerCadence.enter(slice.y, serverTick)) return new Status(this);
                HologramMaterials.Context stock = stock();
                while (cursor < slice.size() && System.nanoTime() < deadline) {
                    if (!active()) return new Status(this);
                    int observed = observe(slice, cursor);
                    if (!active()) return new Status(this);
                    if (observed < 0 || initiallySatisfied[cursor] && observed != 1) return failed(Reason.WORLD_CHANGED);
                    Material material = materials[slice.variant(cursor)];
                    if (observed == 1) satisfied++;
                    else {
                        if (stock.count(material.item, 1) != 1) return failed(Reason.MISSING_STOCK);
                        int latest = observe(slice, cursor);
                        if (!active()) return new Status(this);
                        if (latest != 0 || !exactAir(slice, cursor)) return failed(Reason.WORLD_CHANGED);
                        if (!active()) return new Status(this);
                        CellResult result = operate(slice, cursor, stock);
                        if (result == CellResult.DEFERRED) return new Status(this); // Keep this exact pending cell/slice.
                        if (result != CellResult.COMMITTED) return failed(result.failure);
                        placed++;
                    }
                    material.remaining--;
                    cursor++;
                    worked();
                    // The underlying transaction has already committed at this point; retain truthful counts on loss.
                    if (!active()) return new Status(this);
                }
                if (cursor == slice.size()) {
                    if (!receiver.settle(ticket)) throw new IllegalStateException("Lost projected-layer ticket");
                    layerCadence.settle(serverTick);
                    settledSlices++;
                    initiallySatisfied = null;
                    if (receiver.finished()) {
                        phase = Phase.COMPLETE;
                        ACTIVE.remove(actor, this);
                    } else if (!stocked(materials, stock())) {
                        return failed(Reason.MISSING_STOCK);
                    } else if (receiver.pending() != null) startPending();
                    else phase = Phase.AWAITING_SLICE;
                    advance();
                }
            }
            return new Status(this);
        } catch (RuntimeException | LinkageError invalid) {
            return failed(Reason.UNSUPPORTED_TRANSACTION);
        } finally {
            busy = false;
        }
    }

    /** 1 already exact, 0 exact air and may edit, -1 unsupported/occupied/unloaded. Never replaces. */
    private int observe(Slice slice, int index) {
        int x = slice.x(index), y = slice.y, z = slice.z(index);
        if (y >= world.getHeight() || !world.blockExists(x, y, z) || world.getTileEntity(x, y, z) != null) return -1;
        Block actual = world.getBlock(x, y, z);
        Variant row = header.palette.get(slice.variant(index));
        Material wanted = materials[slice.variant(index)];
        if (!exactVanillaItem(row, wanted.block, wanted.item.getItem())) return -1;
        if (actual == wanted.block && world.getBlockMetadata(x, y, z) == 0) return 1;
        if (actual != Blocks.air || !world.canMineBlock(player, x, y, z)
            || !player.canPlayerEdit(x, y, z, 1, HologramSessionPolicy.jobTool(transaction))
            || !world.canPlaceEntityOnSide(wanted.block, x, y, z, false, 1, null, wanted.item.copy())) return -1;
        return 0;
    }

    private boolean exactAir(Slice slice, int index) {
        int x = slice.x(index), y = slice.y, z = slice.z(index);
        return world.blockExists(x, y, z) && world.getBlock(x, y, z) == Blocks.air
            && world.getTileEntity(x, y, z) == null;
    }

    private CellResult operate(Slice slice, int index, HologramMaterials.Context stock) {
        Material material = materials[slice.variant(index)];
        HologramCapture capture = new HologramCapture(null, HologramSessionPolicy.trigger(transaction));
        HologramCapture.Cell cell = new HologramCapture.Cell(null, slice.x(index), slice.y, slice.z(index));
        cell.block = material.block;
        cell.meta = 0;
        cell.blueprintBlock = header.palette.get(slice.variant(index)).block;
        cell.blueprintItem = header.palette.get(slice.variant(index)).item;
        cell.blueprintDamage = 0;
        cell.userPosition = true;
        cell.role = "prefab";
        cell.stableIndex = 0;
        capture.cells.add(cell);
        transaction.capture = capture;
        transaction.pins.put(0, material.item.copy());
        transaction.planTargets.add(material.item.copy());
        transaction.materialContext = stock;
        try {
            Settlement outcome;
            if (boundary != null) outcome = boundary.transact(transaction, cell, this::currentInputs);
            else {
                boolean applied = HologramWorldTransactions.operate(transaction, cell, 0);
                outcome = transaction.job == 4 ? Settlement.RECOVERY_PENDING
                    : applied && "placed".equals(cell.status) ? Settlement.COMMITTED : Settlement.ROLLED_BACK;
            }
            return switch (outcome) {
                case DEFERRED -> CellResult.DEFERRED;
                case RECOVERY_PENDING -> CellResult.RECOVERY_PENDING;
                case OWNER_CHANGED -> CellResult.OWNER_CHANGED;
                case COMMITTED -> CellResult.COMMITTED;
                case ROLLED_BACK -> "missing".equals(cell.status) ? CellResult.MISSING_STOCK
                    : "blocked".equals(cell.status) || "protected".equals(cell.status) ? CellResult.BLOCKED
                        : CellResult.UNSUPPORTED;
            };
        } finally {
            transaction.materialContext = null;
            transaction.planTargets.clear();
            transaction.pins.clear();
            transaction.capture = null;
        }
    }

    Ack transferAck() throws IOException {
        requireOwner();
        if (active() && phase != Phase.PAUSED && !stocked(materials, stock())) stopNow(Reason.MISSING_STOCK);
        Ack ack = receiver.ack();
        return phase == Phase.PAUSED && ack.credit != 0
            ? new Ack(ack.request, ack.nextSlice, ack.settledCells, 0, false, false) : ack;
    }

    Status status() {
        requireOwner();
        return new Status(this);
    }

    boolean pause(EntityPlayerMP sender) {
        requireOwner();
        if (sender != player || !active() || phase == Phase.PAUSED) return false;
        beforePause = phase;
        pausedSince = clock.getAsLong();
        phase = Phase.PAUSED;
        advance();
        return true;
    }

    boolean resume(EntityPlayerMP sender) {
        requireOwner();
        if (sender != player || !active() || phase != Phase.PAUSED) return false;
        if (!stocked(materials, stock())) {
            stopNow(Reason.MISSING_STOCK);
            return false;
        }
        long now = clock.getAsLong();
        pausedTotal += now - pausedSince;
        // Only suspend the idle interval spent frozen. A legitimately staged slice may have reset it during pause.
        lastWork += now - Math.max(pausedSince, lastWork);
        phase = beforePause;
        advance();
        return true;
    }

    void cancel(EntityPlayerMP sender) {
        requireOwner();
        if (sender == player && sender.playerNetServerHandler == connection && !terminal()) stopNow(Reason.CANCELLED);
    }

    void retire() {
        requireOwner();
        stopNow(Reason.OWNER_CHANGED);
    }

    Status failUnsupported() {
        requireOwner();
        return failed(Reason.UNSUPPORTED_TRANSACTION);
    }

    private Status failed(Reason failure) {
        stopNow(failure);
        return new Status(this);
    }

    private void stopNow(Reason failure) {
        if (terminal()) return;
        receiver.abort();
        initiallySatisfied = null;
        phase = Phase.STOPPED;
        reason = failure;
        ACTIVE.remove(actor, this);
        advance();
    }

    private void advance() {
        sequence++;
    }

    private void worked() {
        lastWork = clock.getAsLong();
        advance();
    }
}
