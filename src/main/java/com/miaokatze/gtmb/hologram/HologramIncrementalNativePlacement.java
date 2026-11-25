// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.LongSupplier;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import io.netty.channel.Channel;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.ICustomBlockSetting;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.StructureSources;

/** Internal first-placement path for an explicitly partial, server-pinned fixed native piece. */
final class HologramIncrementalNativePlacement {

    /** Deliberately not implied by the ordinary whole-structure confirmation. */
    enum Consent {
        PLACE_SAFE_CELLS_WITH_AVAILABLE_STOCK
    }

    enum Result {
        YIELDED, PLACED, COMPLETE, PARTIAL, STOPPED
    }

    /**
     * The authenticated caller holds exactly one slot for this active workspace/connection generation.
     * There is intentionally no global task map or API that starts from a bare session.
     */
    static final class Owner {

        private final HologramSession session;
        private final Thread thread = Thread.currentThread();
        private final NetHandlerPlayServer connection;
        private final NetworkManager network;
        private final Channel channel;
        private final World world;
        private final long epoch, generation;
        private final LongSupplier currentGeneration;
        private HologramIncrementalNativePlacement task;
        private boolean retired, reserving;

        Owner(HologramSession session, NetHandlerPlayServer authenticatedConnection, long generation,
            LongSupplier currentGeneration) throws IOException {
            if (session == null || authenticatedConnection == null || currentGeneration == null
                || authenticatedConnection.netManager == null)
                throw new IOException("原生逐格补建需要当前已认证的连接。");
            this.session = session;
            this.connection = authenticatedConnection;
            this.generation = generation;
            this.currentGeneration = currentGeneration;
            network = connection.netManager;
            channel = network.channel();
            world = session.player.worldObj;
            epoch = session.editorWorkspaceEpoch;
            if (!current()) throw new IOException("原生逐格补建的连接已变化。");
        }

        HologramIncrementalNativePlacement begin(UUID request, String sourceId, Consent consent) throws IOException {
            requireThread();
            if (task != null || reserving) throw new IOException("此工作台已有原生逐格补建任务。");
            reserving = true;
            try {
                if (!current()) throw new IOException("原生逐格补建的连接已变化。");
                HologramIncrementalNativePlacement next = create(this, request, sourceId, consent);
                if (!current()) throw new IOException("原生逐格补建的连接已变化。");
                task = next;
                return next;
            } finally {
                reserving = false;
            }
        }

        void retire() {
            requireThread();
            retired = true;
            if (task != null) task.stop("原生逐格补建的连接已退役。");
        }

        private void requireThread() {
            if (Thread.currentThread() != thread)
                throw new IllegalStateException("Native placement needs its owner thread");
        }

        private boolean current() {
            if (retired) return false;
            boolean matched = currentGeneration.getAsLong() == generation && channel != null
                && connection == session.player.playerNetServerHandler && connection.playerEntity == session.player
                && connection.netManager == network && network.channel() == channel && channel.isOpen()
                && session.player.worldObj == world && session.libraryWorld == world
                && session.player.dimension == session.dimension && session.editorWorkspaceEpoch == epoch
                && HologramWorkspace.find(session.player.getUniqueID()) == session && !retired;
            if (!matched) retired = true;
            return matched;
        }

        private boolean owns(HologramIncrementalNativePlacement expected) {
            return task == expected && expected.terminal == null && current()
                && task == expected && expected.terminal == null;
        }

        private void release(HologramIncrementalNativePlacement expected) {
            if (task == expected) task = null;
        }
    }

    private static final int MAX_WALK_ENTRIES = 32;
    // StructureLib's public step factory returns distinct concrete classes for advance A, reset A, and reset A/B.
    private static final Class<?> STEP_A = ((Object) StructureUtility.step(1, 0, 0)).getClass();
    private static final Class<?> STEP_B = ((Object) StructureUtility.step(0, 1, 0)).getClass();
    private static final Class<?> STEP_C = ((Object) StructureUtility.step(0, 0, 1)).getClass();

    private final Owner owner;
    private final HologramSession session;
    private final World world;
    private final StructureSources.CompactNative pin;
    private final long revision, plan;
    private final int anchorX, anchorY, anchorZ, side, main, priority, recoveryDestination;
    private final boolean creative, mainExplicit, noHatches, hatchDowngrade;
    private final NBTTagCompound channels;
    private final TileEntity controller;
    private final Object machine;
    private final ItemStack trigger;
    private final ExtendedFacing facing;
    private final IStructureDefinition<?> definition;
    private final String pieceName;
    private final IStructureElement<?>[] elements;
    private final HologramStructureCursor cursor;

    private int x, y, z;
    private boolean loaded, position, advancing;
    private Result terminal;
    private BlockMaterial candidate;
    private int candidateEntry = -1, placed, skipped;
    private String detail = "";

    private final IStructureWalker<Object> receiver = new IStructureWalker<Object>() {

        @Override
        public boolean visit(IStructureElement<Object> element, World world, int x, int y, int z, int a, int b, int c) {
            locate(x, y, z, true);
            return true;
        }

        @Override
        public boolean blockNotLoaded(IStructureElement<Object> element, World world, int x, int y, int z, int a,
            int b, int c) {
            locate(x, y, z, false);
            return true;
        }
    };

    private HologramIncrementalNativePlacement(Owner owner, StructureSources.CompactNative pin,
        StructureSources.Resolution resolution, StructureSources.Piece piece, ItemStack trigger) {
        this.owner = owner;
        session = owner.session;
        this.pin = pin;
        this.trigger = trigger;
        world = owner.world;
        controller = session.target;
        machine = session.machine;
        anchorX = session.x;
        anchorY = session.y;
        anchorZ = session.z;
        side = session.side;
        main = session.main;
        mainExplicit = session.mainExplicit;
        noHatches = session.noHatches;
        hatchDowngrade = session.hatchDowngrade;
        channels = (NBTTagCompound) session.channels.copy();
        priority = session.materialSettings.priority;
        recoveryDestination = session.materialSettings.recoveryDestination;
        facing = session.machine.getExtendedFacing();
        creative = session.player.capabilities.isCreativeMode;
        revision = resolution.sourceRevision;
        plan = session.planRevision;
        definition = resolution.plan.definition;
        pieceName = piece.name;
        elements = definition.getStructureFor(pieceName);
        cursor = new HologramStructureCursor(elements, piece.a, piece.b, piece.c);
    }

    /**
     * The caller owns a fresh, active server workspace and has already bound the input to that player/connection.
     * No client cells, item stacks or operations enter this path.
     */
    private static HologramIncrementalNativePlacement create(Owner owner, UUID request, String sourceId,
        Consent consent) throws IOException {
        HologramSession session = owner.session;
        if (consent != Consent.PLACE_SAFE_CELLS_WITH_AVAILABLE_STOCK || request == null
            || sourceId == null || sourceId.isEmpty()) throw new IOException("原生逐格补建需要明确的部分施工确认。");
        if (!ready(session)) throw new IOException("原生逐格补建的投影仪或工作台不可用。");
        var pin = StructureSources.compactNative(session.machine.getClass());
        if (pin == null || !pin.accepts(session.main, session.channels))
            throw new IOException("当前原生声明或信道不支持逐格补建。");
        ItemStack trigger = HologramSessionPolicy.trigger(session);
        var resolution = StructureSources.resolveCompactNative(session.machine.getClass(), pin, trigger);
        if (resolution == null || !sourceId.equals(resolution.sourceId)
            || !HologramStructureCursor.supports(resolution.plan.definition))
            throw new IOException("原生结构声明或版本已变更。");
        StructureSources.Piece piece = resolution.plan.pieces.get(0);
        if (!saneOffset(piece.a) || !saneOffset(piece.b) || !saneOffset(piece.c))
            throw new IOException("原生结构锚点超出逐格补建支持范围。");
        if (HologramConstructionJournal.pending(session.player)) throw new IOException("存在待核定施工凭据。");
        String id = request.toString();
        if (HologramProjectedPlacementService.consumed(session.player, id))
            throw new IOException("这次施工请求已经受理。");
        HologramProjectedPlacementService.retire(session.player, id);
        return new HologramIncrementalNativePlacement(owner, pin, resolution, piece, trigger);
    }

    private static boolean saneOffset(int offset) {
        return Math.abs((long) offset) <= HologramBlueprintLimits.MAX_CELLS;
    }

    private static boolean ready(HologramSession s) {
        return s != null && s.machine != null && s.target != null
            && s.job == 0 && s.mode == 0 && s.scope == 0 && !s.virtual && !s.cancelled
            && s.areaStructureId.isEmpty() && s.capture == null && s.planTargets.isEmpty() && s.pins.isEmpty()
            && s.coordinatePins.isEmpty() && s.facingPins.isEmpty() && s.manualPositions.isEmpty()
            && s.nativeOperationInputs == null && s.nativeCompactStart == null && s.nativeReadyPlacement == null
            && s.executionStart == null && s.currentPlacementTask == null && s.structureSavePreparation == null
            && s.presentationTransition == null && !HologramSnapshotPreparation.pending(s)
            && !s.jobVoucherRequested && s.materialSettings.main && !s.materialSettings.containers
            && !s.materialSettings.me
            && HologramWorkspace.find(s.player.getUniqueID()) == s && HologramEditorWorkspaceRegistry.idle(s)
            && HologramNetwork.progressConnected(s.player) && HologramSessionPolicy.valid(s)
            && s.player.capabilities.allowEdit && s.player.getDistanceSq(s.x + .5, s.y + .5, s.z + .5) <= 64
            && !HologramPlacementUpload.active(s.player) && !HologramProjectedPlacementService.preparing(s.player)
            && !StructureLibAPI.isInstrumentEnabled()
            && !BlueprintSnapshots.hasProcessors(BlueprintSnapshots.Stage.CAPTURE_COMPLETE);
    }

    private boolean current() {
        return boundInputs() && owner.owns(this) && ready(session) && session.machine.getExtendedFacing() == facing
            && ItemStack.areItemStacksEqual(trigger, HologramSessionPolicy.trigger(session))
            && owner.owns(this) && boundInputs();
    }

    private boolean boundInputs() {
        return terminal == null && session.target == controller && session.machine == machine
            && session.x == anchorX && session.y == anchorY && session.z == anchorZ && session.side == side
            && session.main == main && session.mainExplicit == mainExplicit && session.noHatches == noHatches
            && session.hatchDowngrade == hatchDowngrade && channels.equals(session.channels)
            && session.materialSettings.priority == priority
            && session.materialSettings.recoveryDestination == recoveryDestination
            && session.planRevision == plan && session.player.capabilities.isCreativeMode == creative
            && session.job == 0 && session.mode == 0 && session.scope == 0 && !session.virtual && !session.cancelled
            && session.materialSettings.main && !session.materialSettings.containers && !session.materialSettings.me
            && session.facing == facing.ordinal()
            && definition.getStructureFor(pieceName) == elements
            && StructureSources.compactNative(machine.getClass()) == pin && StructureSources.revision() == revision;
    }

    private boolean entryCurrent(int index, IStructureElement<?> expected) {
        return definition.getStructureFor(pieceName) == elements && index >= 0 && index < elements.length
            && elements[index] == expected;
    }

    private Result declarationChanged() {
        return stop("原生结构声明已变化，未继续逐格补建。");
    }

    private void locate(int x, int y, int z, boolean loaded) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.loaded = loaded;
        position = true;
    }

    private Result finish(Result outcome, String reason) {
        if (terminal == null) {
            terminal = outcome;
            detail = reason;
            candidate = null;
            // Cancellation from a native callback cannot free a second writer before this slice unwinds.
            if (!advancing) owner.release(this);
        }
        return terminal;
    }

    private Result stop(String reason) {
        return finish(Result.STOPPED, reason);
    }

    void cancel() {
        owner.requireThread();
        stop("原生逐格补建已取消。");
    }

    /** First-progress driver: return on the first real committed cell. */
    Result step(long deadline) {
        return drain(deadline, MAX_WALK_ENTRIES, 1);
    }

    /**
     * Continue the same native job without waiting between independent cells. The server driver supplies
     * its existing tick deadline and work/commit quotas; no layer, rendering or network ACK advances this cursor.
     * A synchronous cell cannot be interrupted once its authoritative transaction has started.
     */
    Result drain(long deadline, int workQuota, int commitQuota) {
        owner.requireThread();
        if (workQuota < 1 || commitQuota < 1) throw new IllegalArgumentException("Native placement slice quotas");
        if (terminal != null) return terminal;
        if (advancing) throw new IllegalStateException("Native placement slice cannot be re-entered");
        advancing = true;
        try {
            if (!owner.owns(this)) return stop("原生逐格补建的连接已退役。");
            Result result = advance(deadline, Math.min(workQuota, MAX_WALK_ENTRIES), commitQuota);
            return terminal == null ? result : terminal;
        } catch (RuntimeException | LinkageError failure) {
            return stop("原生逐格补建遇到不支持的现场或材料读取，未继续施工。");
        } finally {
            advancing = false;
            if (terminal != null) owner.release(this);
        }
    }

    private Result advance(long deadline, int workQuota, int commitQuota) {
        if (System.nanoTime() >= deadline) return Result.YIELDED;
        if (!current()) return stop("原生补建的来源、投影仪或现场已变更。");
        if (HologramConstructionJournal.pending(session.player)) return stop("存在待核定施工凭据。");
        int before = placed;
        for (int work = 0; work < workQuota && System.nanoTime() < deadline; work++) {
            if (!owner.owns(this)) return stop("原生逐格补建的连接已退役。");
            if (candidate == null) {
                if (cursor.done() == cursor.total()) return skipped == 0 ? finish(Result.COMPLETE, "")
                    : finish(Result.PARTIAL, "存在未放置的格子；已提交的施工保留。");
                int entry = cursor.done();
                IStructureElement<?> element = elements[entry];
                BlockMaterial material = material(element);
                boolean air = element == StructureUtility.isAir();
                if (!entryCurrent(entry, element)) return declarationChanged();
                if (!current()) return stop("原生补建的来源、投影仪或现场已变更。");
                if (!entryCurrent(entry, element)) return declarationChanged();
                if (material == null && !air && !isNativeNavigation(element))
                    return stop("此原生元素未通过逐格补建支持审查。");
                if (System.nanoTime() >= deadline) break;
                position = false;
                if (!cursor.consumePinned(world, facing, anchorX, anchorY, anchorZ, receiver, entry, element))
                    return declarationChanged();
                if (!owner.owns(this)) return stop("原生逐格补建的连接已退役。");
                if (!entryCurrent(entry, element)) return declarationChanged();
                if (!position || x == anchorX && y == anchorY && z == anchorZ) continue;
                boolean validPosition = loaded && HologramWorldFlow.worldPosition(x, y, z, world.getHeight());
                if (!entryCurrent(entry, element)) return declarationChanged();
                if (!validPosition) {
                    skipped++;
                    continue;
                }
                if (air) {
                    boolean occupied = world.getBlock(x, y, z) != Blocks.air || world.getTileEntity(x, y, z) != null;
                    if (!entryCurrent(entry, element)) return declarationChanged();
                    if (occupied) skipped++;
                    continue;
                }
                candidate = material;
                candidateEntry = entry;
            }
            if (System.nanoTime() >= deadline) break;
            Result result = place(deadline);
            if (terminal != null) return terminal;
            if (result == Result.PLACED && placed - before >= commitQuota) return result;
        }
        return placed == before ? Result.YIELDED : Result.PLACED;
    }

    private Result discard(boolean count) {
        candidate = null;
        candidateEntry = -1;
        if (count) skipped++;
        return Result.YIELDED;
    }

    private Result place(long deadline) {
        BlockMaterial material = candidate;
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        if (!current()) return stop("原生补建的来源、投影仪或现场已变更。");
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        boolean loaded = world.blockExists(x, y, z);
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        if (!loaded) return discard(true);
        Block old = world.getBlock(x, y, z);
        var tile = world.getTileEntity(x, y, z);
        boolean matched = old == material.block && world.getBlockMetadata(x, y, z) == material.meta && tile == null;
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        if (matched) return discard(false);
        boolean editable = old == Blocks.air && tile == null && world.canMineBlock(session.player, x, y, z)
            && session.player.canPlayerEdit(x, y, z, 1, trigger);
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        if (!editable) return discard(true);
        var stock = new HologramMaterials.Context(session.player, session.materialSettings, session.slot);
        boolean stocked = creative || stock.count(material.item, 1) >= 1;
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        if (!stocked) return discard(true);
        if (System.nanoTime() >= deadline) return Result.YIELDED;
        if (!current()) return stop("原生补建的来源、投影仪或现场已变更。");
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        boolean stillEmpty = world.blockExists(x, y, z) && world.getBlock(x, y, z) == Blocks.air
            && world.getTileEntity(x, y, z) == null;
        // Tile/chunk access may call out; cancellation or a changed declaration cannot start a transaction.
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        if (!current()) return stop("原生补建的来源、投影仪或现场已变更。");
        if (!entryCurrent(candidateEntry, material.element)) return declarationChanged();
        if (!stillEmpty) return discard(true);
        if (System.nanoTime() >= deadline) return Result.YIELDED;
        candidate = null;
        candidateEntry = -1;
        // Only this one proven row enters the production world/payment transaction. No native construct callback,
        // aggregate quote, full capture, or UI snapshot is needed for its exact shell.
        HologramCapture capture = new HologramCapture(null, trigger);
        HologramCapture.Cell cell = new HologramCapture.Cell(material.element, x, y, z, trigger);
        cell.block = material.block;
        cell.meta = material.meta;
        cell.stableIndex = 0;
        capture.cells.add(cell);
        var previousStock = session.materialContext;
        session.materialContext = stock;
        session.capture = capture;
        session.planTargets.add(material.item);
        session.pins.put(0, material.item);
        try {
            boolean changed = HologramWorldTransactions.operate(session, cell, 0);
            boolean committed = changed && "placed".equals(cell.status);
            if (committed) placed++;
            if (session.job == 4) return stop(session.status);
            if (committed) return Result.PLACED;
            skipped++;
            return Result.YIELDED;
        } finally {
            if (session.capture == capture) {
                session.capture = null;
                session.pins.remove(0);
                session.planTargets.clear();
            }
            if (session.materialContext == stock) session.materialContext = previousStock;
        }
    }

    static boolean isNativeNavigation(IStructureElement<?> element) {
        if (element == null) return false;
        Class<?> type = element.getClass();
        // Do not call isNavigating() or other user-supplied element methods to prove eligibility.
        return type == STEP_A || type == STEP_B || type == STEP_C;
    }

    /** Never evaluates arbitrary element checks, channels, wrappers, tile factories or hatch callbacks. */
    private static BlockMaterial material(IStructureElement<?> element) {
        if (element == null) return null;
        Block block = fixedBlockData(element);
        if (block == null || block instanceof ICustomBlockSetting) return null;
        // The fixed factory alone does not prove ItemBlock setter/metadata semantics. These audited independent
        // vanilla shells have a plain ItemBlock and no tile/configuration/alternate material identity.
        String id = block == Blocks.stone ? "minecraft:stone" : block == Blocks.cobblestone ? "minecraft:cobblestone"
            : block == Blocks.glass ? "minecraft:glass" : null;
        if (id == null || Block.blockRegistry.getObject(id) != block || block.hasTileEntity(0)
            || HologramNativePlacement.supported(block, null) || HologramMaterialDescriptor.hasExact(block)
            || HologramDecorMaterials.supports(block)) return null;
        Item item = Item.getItemFromBlock(block);
        if (item == null || item.getClass() != ItemBlock.class || Item.itemRegistry.getObject(id) != item
            || Block.getBlockFromItem(item) != block || item.getMetadata(0) != 0) return null;
        ItemStack sample = new ItemStack(item, 1, 0);
        if (HologramGtswnPlacementMaterials.supplyKind(sample) >= 0
            || HologramGtswnPlacementMaterials.freeShell(block, sample)) return null;
        return new BlockMaterial(element, block, 0, sample);
    }

    /** Read only the audited native factory's captured constants; the generic fallback may call into the block. */
    static Block fixedBlockData(IStructureElement<?> element) {
        Method factory = HologramDeclarationWrappers.factory(element.getClass());
        if (factory == null || factory.getDeclaringClass() != StructureUtility.class
            || !"ofBlock".equals(factory.getName())) return null;
        Class<?>[] types = factory.getParameterTypes();
        if (types.length != 4 || types[0] != Block.class || types[1] != int.class || types[2] != Block.class
            || types[3] != int.class) return null;
        try {
            Object accepted = HologramDeclarationWrappers.captured(element, "val$block");
            return accepted instanceof Block && accepted == HologramDeclarationWrappers.captured(element, "val$defaultBlock")
                && Integer.valueOf(0).equals(HologramDeclarationWrappers.captured(element, "val$meta"))
                && Integer.valueOf(0).equals(HologramDeclarationWrappers.captured(element, "val$defaultMeta"))
                    ? (Block) accepted : null;
        } catch (ReflectiveOperationException unknownNativeVersion) {
            return null;
        }
    }

    private static final class BlockMaterial {

        final IStructureElement<?> element;
        final Block block;
        final int meta;
        final ItemStack item;

        BlockMaterial(IStructureElement<?> element, Block block, int meta, ItemStack item) {
            this.element = element;
            this.block = block;
            this.meta = meta;
            this.item = item;
        }
    }

    int visited() {
        return cursor.done();
    }

    int total() {
        return cursor.total();
    }

    int placed() {
        return placed;
    }

    int skipped() {
        return skipped;
    }

    String detail() {
        return detail;
    }
}
