// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.End;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Lease;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Permit;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Settlement;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Receiver;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedVariantPricing.Row;
import com.miaokatze.gtmb.hologram.HologramWorkspace.ActionEndpoint;

/** Dormant reviewed-profile bridge for exact, no-tile shells only. No packet registrations or decorated fallback. */
final class HologramProjectedReviewedPlainPlacement {

    private static final String HEMP = "chisel:hempcrete", LAVE = "Ztones:tile.laveBlock",
        GLAXX = "Ztones:tile.glaxx", GREEN = "ExtraUtilities:greenscreen";
    private static final String ZT_ITEM = "com.riciJak.Ztones.item.block.ItemDecoBlocks2";
    private static final String MATERIAL_SETTINGS = "gtitHologramMaterials"; // HologramMaterials.read/write
    private static final int MAX_OWNERS = 8, MAX_VARIANTS = 5;
    private static final Map<UUID, HologramProjectedReviewedPlainPlacement> ACTIVE = new HashMap<>();

    enum Result {
        WAITING, PLACED, SATISFIED, COMPLETE, STOPPED, RECOVERY_PENDING
    }

    private final Thread owner = Thread.currentThread();
    private final ActionEndpoint endpoint;
    private final HologramConstructionTaskGate gate;
    private final Lease lease;
    private final EntityPlayerMP player;
    private final HologramSession origin;
    private final long epoch, revision, declarations;
    private final Header header;
    private final Receiver receiver;
    private final InventoryPlayer inventory;
    private final ItemStack[] inventorySlots;
    private final ItemStack tool;
    private final Item toolType;
    private final String toolId;
    private final int slot, dimension;
    private final HologramProjectedMainPolicy.Snapshot configured;
    private final HologramMaterials.Settings main;
    private HologramProjectedVariantPricing.Catalog catalog;
    private HologramSession transaction;
    private final int[] remaining;
    private int next, credit = -1, placed, satisfied;
    private boolean stepping, closed, complete;
    private Permit unresolved;

    private HologramProjectedReviewedPlainPlacement(ActionEndpoint endpoint, HologramConstructionTaskGate gate,
        Lease lease, Manifest manifest) throws IOException {
        this.endpoint = endpoint;
        this.gate = gate;
        this.lease = lease;
        player = endpoint.player;
        header = manifest.header;
        receiver = new Receiver(manifest);
        remaining = new int[header.palette.size()];
        for (int i = 0; i < remaining.length; i++) remaining[i] = header.palette.get(i).count;
        origin = HologramWorkspace.find(endpoint.actor);
        epoch = origin == null ? -1 : origin.editorWorkspaceEpoch;
        revision = origin == null ? -1 : origin.planRevision;
        declarations = HologramDeclarationAdapters.admissionGeneration();
        dimension = player.dimension;
        inventory = player.inventory;
        inventorySlots = inventory.mainInventory;
        slot = inventory.currentItem;
        tool = slot >= 0 && slot < inventorySlots.length ? inventorySlots[slot] : null;
        toolType = tool == null ? null : tool.getItem();
        if (!ItemNekoHologramProjector.isProjector(tool) || !vanillaToolTags(tool)) throw refused();
        toolId = id(tool);
        configured = HologramProjectedMainPolicy.capture(HologramMaterials.read(tool));
        if (!canonical(toolId) || configured == null) throw refused();
        main = configured.mainScope();
    }

    /**
     * The caller owns the admitted lease until a job is returned. Every failed admission, including one before
     * its private owner slot exists, leaves the caller responsible for settling that exact lease.
     */
    static HologramProjectedReviewedPlainPlacement begin(ActionEndpoint endpoint, Lease lease, Manifest manifest)
        throws IOException {
        if (!plainManifest(manifest) || endpoint == null || lease == null || lease.scope != endpoint.constructionScope
            || lease.request.kind != HologramConstructionTaskGate.Kind.PROJECTED_LAYER
            || !lease.request.id.equals(manifest.header.request)) throw refused();
        var gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null || ACTIVE.containsKey(endpoint.actor) || ACTIVE.size() >= MAX_OWNERS) throw refused();
        var job = new HologramProjectedReviewedPlainPlacement(endpoint, gate, lease, manifest);
        if (ACTIVE.putIfAbsent(endpoint.actor, job) != null) throw refused();
        try {
            job.prepare();
            return job;
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("Reviewed plain provider not available", failure);
        } finally {
            if (job.transaction == null) {
                job.receiver.abort();
                job.closed = true;
                ACTIVE.remove(endpoint.actor, job);
            }
        }
    }

    /** Reject a mixed dictionary before observing any provider, stock, or world. */
    static boolean plainManifest(Manifest manifest) throws IOException {
        if (manifest == null || manifest.header.profile != HologramProjectedLayerWire.REVIEWED_PROFILE
            || manifest.header.slices > HologramProjectedLayerService.MAX_SLICES
            || manifest.header.palette.isEmpty() || manifest.header.palette.size() > MAX_VARIANTS) return false;
        var header = manifest.header;
        if (!near(header.x, header.minX, header.maxX) || !near(header.y, header.minY, header.maxY)
            || !near(header.z, header.minZ, header.maxZ)) return false;
        for (var variant : header.palette) {
            Row row = variant.reviewedRow();
            if (!row.block.equals(row.item) || row.metadata != row.damage || row.facing != -1 || row.controller
                || !row.tileNbt.isEmpty() || !row.itemNbt.isEmpty() || itemType(row) == null) return false;
        }
        return true;
    }

    private void prepare() throws IOException {
        if (!live(true) || HologramProjectedLayerService.hasKnownRecovery(player)) throw refused();
        var builder = new HologramProjectedVariantPricing.Builder(remaining.length);
        for (var variant : header.palette) {
            builder.add(variant.reviewedRow());
            if (!live(true)) throw refused();
        }
        catalog = builder.finish();
        for (int i = 0; i < remaining.length; i++) if (!provider(i)) throw refused();
        // Observe enough legitimate MAIN stock for the whole declared job; this is not escrow.
        if (!funded() || !live(true)) throw refused();
        var tx = new HologramSession(player, header.x, header.y, header.z, false, 1);
        if (!live(true) || tx.libraryWorld != endpoint.world || tx.slot != slot || tx.boundTool != tool
            || !toolId.equals(tx.toolId) || !configured.configuredMatches(tx.materialSettings)
            || !funded() || !actionCurrent()) throw refused();
        tx.materialSettings = main;
        tx.virtual = tx.virtualPlacement = tx.virtualLocked = true;
        tx.job = 1;
        tx.executionRequestId = header.request.toString();
        tx.virtualPreset = new HologramPresets.Preset();
        tx.virtualPreset.schema = 2;
        tx.virtualPreset.retainNbt = tx.virtualPreset.retainCovers = false;
        transaction = tx;
    }

    /** One credit only, and only after a fresh whole remaining-stock observation. */
    Ack credit() throws IOException {
        requireOwner();
        if (stepping || transaction == null) return null;
        stepping = true;
        try {
            if (!closed && receiver.pending() == null && (!live(false) || !funded() || !actionCurrent())) stop();
            Ack ack = receiver.ack();
            if (ack.complete && !complete) return null;
            if (ack.credit == 1 && !closed) credit = ack.nextSlice;
            return ack;
        } catch (RuntimeException | LinkageError failure) {
            stop();
            throw new IOException("Reviewed plain placement stock changed", failure);
        } finally {
            stepping = false;
        }
    }

    Offer offer(byte[] exact) throws IOException {
        requireOwner();
        if (closed || transaction == null || stepping || credit < 0 && receiver.pending() == null)
            return Offer.CLOSED;
        stepping = true;
        try {
            if (!live(false)) {
                stop();
                return Offer.CLOSED;
            }
            Offer result = receiver.offer(exact);
            if (result == Offer.ACCEPTED) credit = -1;
            return result;
        } catch (IOException invalid) {
            stop();
            throw invalid;
        } catch (RuntimeException | LinkageError failure) {
            stop();
            throw new IOException("Reviewed plain placement owner changed", failure);
        } finally {
            stepping = false;
        }
    }

    /** At most one real scoped cell transaction per step. The caller may not treat an ACK as placement. */
    Result step() {
        requireOwner();
        if (stepping || transaction == null) return Result.WAITING;
        if (unresolved != null) return Result.RECOVERY_PENDING;
        if (closed) return complete ? Result.COMPLETE : Result.STOPPED;
        stepping = true;
        Permit permit = null;
        try {
            if (!live(false)) return stop();
            var pending = receiver.pending();
            if (pending == null) return Result.WAITING;
            Slice slice = pending.slice;
            int variant = slice.variant(next);
            if (!live(false) || !provider(variant) || !live(false)) return stop();
            var known = catalog.variants.get(variant);
            int x = slice.x(next), y = slice.y, z = slice.z(next);
            var world = endpoint.world;
            if (!world.blockExists(x, y, z) || world.getTileEntity(x, y, z) != null) return stop();
            Block before = world.getBlock(x, y, z);
            if (before == known.block && sameShell(known, x, y, z))
                return account(pending, variant, false);
            ItemStack sample = known.sample();
            if (before != Blocks.air || !world.canMineBlock(player, x, y, z)
                || !player.canPlayerEdit(x, y, z, 1, tool)
                || !world.canPlaceEntityOnSide(known.block, x, y, z, false, 1, null, sample.copy())
                || !live(false)) return stop();
            permit = gate.beginTransaction(lease);
            if (permit == null) return stop();
            var exactPermit = permit;
            var row = header.palette.get(variant);
            var capture = new HologramCapture(null, tool);
            var cell = new HologramCapture.Cell(null, x, y, z);
            cell.block = known.block;
            cell.meta = known.metadata;
            cell.blueprintBlock = row.block;
            cell.blueprintItem = row.item;
            cell.blueprintDamage = row.damage;
            cell.userPosition = true;
            cell.role = "prefab";
            cell.stableIndex = 0;
            capture.cells.add(cell);
            transaction.capture = capture;
            transaction.pins.put(0, sample.copy());
            transaction.planTargets.add(sample.copy());
            boolean applied = HologramWorldTransactions.operateScoped(transaction, cell, 0,
                () -> actionCurrent() && gate.transactionCurrent(exactPermit),
                () -> precommitProviderCurrent(variant));
            if (transaction.job == 4) return uncertain(permit);
            if (!applied || !"placed".equals(cell.status)) {
                // Another actor made a complete identical cell before our transaction; never charge or count a write.
                if (!gate.settle(permit, Settlement.ROLLED_BACK)) return uncertain(permit);
                permit = null;
                if (applied && "satisfied".equals(cell.status) && sameShell(known, x, y, z))
                    return account(pending, variant, false);
                return stop();
            }
            if (!gate.settle(permit, Settlement.COMMITTED)) return uncertain(permit);
            permit = null;
            return account(pending, variant, true);
        } catch (IOException | RuntimeException | LinkageError failure) {
            // A thrown callback after permit admission is not proof of rollback.
            return permit == null ? stop() : uncertain(permit);
        } finally {
            transaction.capture = null;
            transaction.pins.clear();
            transaction.planTargets.clear();
            stepping = false;
        }
    }

    private Result account(HologramProjectedLayerPlan.Ticket ticket, int variant, boolean written) {
        if (written) placed++;
        else satisfied++;
        remaining[variant]--;
        if (++next == ticket.slice.size()) {
            if (!receiver.settle(ticket)) return stop();
            next = 0;
            if (receiver.finished()) {
                if (gate.finish(lease, End.COMPLETE)) {
                    complete = closed = true;
                    ACTIVE.remove(endpoint.actor, this);
                } else stop();
            }
        }
        return written ? Result.PLACED : Result.SATISFIED;
    }

    private Result uncertain(Permit permit) {
        gate.settle(permit, Settlement.RECOVERY_PENDING);
        unresolved = permit;
        receiver.abort();
        closed = true;
        ACTIVE.remove(endpoint.actor, this);
        return Result.RECOVERY_PENDING;
    }

    /** Cancel without touching old or third-party evidence. An in-flight owner is settled only by that step. */
    Result cancel() {
        requireOwner();
        if (!closed && stepping) {
            gate.cancel(lease.scope, lease.request);
            return Result.WAITING;
        }
        if (!closed) gate.cancel(lease.scope, lease.request);
        if (complete) return Result.COMPLETE;
        return unresolved != null ? Result.RECOVERY_PENDING : stop();
    }

    int placed() {
        return placed;
    }

    int alreadySatisfied() {
        return satisfied;
    }

    private boolean sameShell(HologramProjectedVariantPricing.Variant wanted, int x, int y, int z) {
        // A tile lookup is itself a live callback. After it and the transport checks, observe one loaded raw
        // section/map, not a mixture of independently state-changing Block/metadata/tile getters.
        if (!(endpoint.world instanceof WorldServer world) || !live(false) || !world.blockExists(x, y, z)
            || world.getTileEntity(x, y, z) != null || !actionCurrent()) return false;
        var provider = world.theChunkProviderServer;
        if (provider == null || provider.getClass() != ChunkProviderServer.class || provider.worldObj != world)
            return false;
        LongHashMap loaded = provider.loadedChunkHashMap;
        if (loaded == null || loaded.getClass() != LongHashMap.class) return false;
        Object exact = loaded.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x >> 4, z >> 4));
        if (exact == null || exact.getClass() != Chunk.class) return false;
        Chunk chunk = (Chunk) exact;
        if (!chunk.isChunkLoaded || chunk.worldObj != world || chunk.xPosition != x >> 4 || chunk.zPosition != z >> 4
            || chunk.chunkTileEntityMap == null || chunk.chunkTileEntityMap.getClass() != HashMap.class) return false;
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        if (y < 0 || (y >> 4) >= sections.length) return false;
        ExtendedBlockStorage section = sections[y >> 4];
        if (section == null || section.getClass() != ExtendedBlockStorage.class
            || section.getBlockMSBArray() != null && section.getBlockMSBArray().getClass() != NibbleArray.class
            || section.getMetadataArray() == null || section.getMetadataArray().getClass() != NibbleArray.class)
            return false;
        return section.getBlockByExtId(x & 15, y & 15, z & 15) == wanted.block
            && section.getExtBlockMetadata(x & 15, y & 15, z & 15) == wanted.metadata
            && !chunk.chunkTileEntityMap.containsKey(new ChunkPosition(x & 15, y, z & 15)) && pinned();
    }

    private Result stop() {
        receiver.abort();
        closed = true;
        credit = -1;
        gate.finish(lease, End.FAILED);
        ACTIVE.remove(endpoint.actor, this);
        return Result.STOPPED;
    }

    private boolean funded() {
        // Count the original array directly; opening a source context here is unnecessary.
        for (int i = 0; i < remaining.length; i++) {
            if (remaining[i] > 0 && HologramMaterials.mainCount(inventorySlots, slot,
                catalog.variants.get(i).sample(), remaining[i]) != remaining[i]) return false;
        }
        return true;
    }

    private boolean precommitProviderCurrent(int i) {
        try {
            return live(false) && provider(i) && actionCurrent();
        } catch (IOException invalidProvider) {
            // A failing live proof cannot approve debit; the scoped transaction owns exact rollback.
            return false;
        }
    }

    private boolean provider(int i) throws IOException {
        Row row = header.palette.get(i).reviewedRow();
        var variant = catalog.variants.get(i);
        Item item = variant.sample().getItem();
        return variant.tile() == null && item instanceof ItemBlock && itemType(row).equals(item.getClass().getName())
            && Block.blockRegistry.getObject(row.block) == variant.block && Item.itemRegistry.getObject(row.item) == item
            && Item.getItemFromBlock(variant.block) == item && Block.getBlockFromItem(item) == variant.block
            && item.getMetadata(row.damage) == variant.metadata && !variant.block.hasTileEntity(variant.metadata);
    }

    private static String itemType(Row row) {
        return switch (row.block) {
            case HEMP -> row.metadata == 15 ? "team.chisel.item.ItemCarvable" : null;
            case LAVE -> row.metadata == 12 ? ZT_ITEM : null;
            case GLAXX -> row.metadata == 0 ? ZT_ITEM : null;
            case GREEN -> row.metadata == 0 || row.metadata == 3 ? "com.rwtema.extrautils.item.ItemBlockMetadata" : null;
            default -> null;
        };
    }

    private boolean pinned() {
        return Thread.currentThread() == owner && !closed && ACTIVE.get(endpoint.actor) == this
            && player.worldObj == endpoint.world && player.dimension == dimension
            && player.playerNetServerHandler == endpoint.connection && endpoint.connection.playerEntity == player
            && !player.isDead && player.capabilities.allowEdit && !player.capabilities.isCreativeMode
            && player.inventory == inventory && inventory.mainInventory == inventorySlots
            && inventory.currentItem == slot && inventorySlots[slot] == tool && tool.getItem() == toolType
            && tool.stackSize > 0 && vanillaToolTags(tool) && toolId.equals(id(tool))
            && configured.configuredMatches(HologramMaterials.read(tool)) && configured.mainScopeMatches(main)
            && HologramWorkspace.find(endpoint.actor) == origin
            && (origin == null || origin.editorWorkspaceEpoch == epoch && origin.planRevision == revision)
            && declarations == HologramDeclarationAdapters.admissionGeneration()
            && (catalog == null || catalog.current())
            && gate.query(lease.scope, lease.request).phase == HologramConstructionTaskGate.Phase.ACTIVE;
    }

    private boolean actionCurrent() {
        // constructionGate checks the published ActionEndpoint and physical channel; those transport getters are
        // virtual. Pure original identity/scope checks must follow, not precede, the live transport observation.
        return HologramWorkspace.constructionGate(endpoint) == gate && pinned();
    }

    private boolean live(boolean initial) {
        var world = endpoint.world;
        return actionCurrent() && !HologramProjectedLayerService.competingWork(player)
            && header.maxY < world.getHeight()
            && HologramWorldFlow.worldPosition(header.x, header.y, header.z, world.getHeight())
            && world.blockExists(header.x, header.y, header.z)
            && (!initial || HologramSessionPolicy.previewReach(player, header.x, header.y, header.z))
            && actionCurrent();
    }

    /** Refuse custom in-memory NBT getter callbacks at the final identity/stock boundary. */
    private static boolean vanillaToolTags(ItemStack stack) {
        NBTTagCompound root = stack.getTagCompound();
        if (root == null || root.getClass() != NBTTagCompound.class
            || !vanilla(root.getTag(HologramSessionKeys.TOOL_ID), NBTTagString.class, false)) return false;
        NBTBase raw = root.getTag(MATERIAL_SETTINGS);
        if (raw == null) return true;
        if (raw.getClass() != NBTTagCompound.class) return false;
        NBTTagCompound settings = (NBTTagCompound) raw;
        return vanilla(settings.getTag("main"), NBTTagByte.class, true)
            && vanilla(settings.getTag("containers"), NBTTagByte.class, true)
            && vanilla(settings.getTag("me"), NBTTagByte.class, true)
            && vanilla(settings.getTag("priority"), NBTTagInt.class, true)
            && vanilla(settings.getTag("recoveryDestination"), NBTTagInt.class, true);
    }

    private static boolean vanilla(NBTBase tag, Class<? extends NBTBase> type, boolean absenceAllowed) {
        return tag == null ? absenceAllowed : tag.getClass() == type;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Reviewed plain placement owner");
    }

    private static boolean near(int anchor, int min, int max) {
        return (long) min >= (long) anchor - 256 && (long) max <= (long) anchor + 256;
    }

    private static String id(ItemStack stack) {
        return stack == null || !stack.hasTagCompound() ? "" : stack.getTagCompound().getString(HologramSessionKeys.TOOL_ID);
    }

    private static boolean canonical(String id) {
        if (id.length() != 36) return false;
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static IOException refused() {
        return new IOException("Reviewed plain placement not admitted");
    }
}
