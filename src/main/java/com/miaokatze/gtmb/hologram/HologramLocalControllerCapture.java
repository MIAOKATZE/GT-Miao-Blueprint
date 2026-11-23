// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.IAlignmentProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IMultiblockInfoContainer;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.api.StructureSources;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Read-only declaration and loaded client data for the original workbench. */
public final class HologramLocalControllerCapture implements AutoCloseable {

    private final Thread owner = Thread.currentThread();
    private final TileEntity tile;
    private final World world;
    private final int dimension, x, y, z, side;
    private final Object liveContext;
    private final Block controllerBlock;
    private final int controllerMeta;
    private final int controllerId;
    private final ExtendedFacing facing;
    private final String title;
    private final String session, generation;
    private NBTTagList rows;
    private List<HologramControllerTarget.Cell> targetCells;
    private NBTTagList detailRows;
    private final Map<IStructureElement<?>, Map<HologramBlueprintResources.ExactKey, Integer>> facingMasks =
        new IdentityHashMap<>();
    private final NBTTagCompound settings;
    private final ItemStack trigger;
    private final boolean alreadyDetached;
    private MTEEnhancedMultiBlockBase<?> detached;
    private TileEntity detachedTile;
    private Object detachedContext;
    private HologramCapture capture;
    private HologramNativeStructurePreview nativePreview;
    private Iterator<HologramCapture.Cell> declared;
    private Iterator<HologramNativePreviewWorld.Placement> nativePlacements;
    private HologramChannelTrace.Report report;
    private StructureSources.ReadSet reuseProof;
    private boolean complete;

    /** Class/registration check only; resolving a constructable belongs to the detached source. */
    public static boolean supports(TileEntity tile) {
        return HologramStructureDiscovery.supportsLocal(tile);
    }

    public HologramLocalControllerCapture(TileEntity tile, int dimension, int side) {
        this(tile, dimension, side, null, toolSettings(null), "");
    }

    public HologramLocalControllerCapture(TileEntity tile, int dimension, int side, ItemStack tool,
        NBTTagCompound settings, String session) {
        this(tile, dimension, side, tool, settings, session, null);
    }

    private HologramLocalControllerCapture(TileEntity tile, int dimension, int side, ItemStack tool,
        NBTTagCompound settings, String session, ExtendedFacing detachedFacing) {
        this.tile = tile;
        this.world = tile.getWorldObj();
        this.dimension = dimension;
        this.x = tile.xCoord;
        this.y = tile.yCoord;
        this.z = tile.zCoord;
        this.side = side;
        this.session = session.isEmpty() ? "local-controller:" + UUID.randomUUID() : session;
        generation = session.isEmpty() ? this.session : "local-controller:" + UUID.randomUUID();
        this.settings = (NBTTagCompound) settings.copy();
        alreadyDetached = detachedFacing != null;
        this.trigger = previewTrigger(tool, this.settings);
        liveContext = HologramStructureDiscovery.context(tile);
        controllerBlock = world == null || !world.blockExists(x, y, z) ? Blocks.air : world.getBlock(x, y, z);
        controllerMeta = controllerBlock == Blocks.air ? 0 : world.getBlockMetadata(x, y, z);
        controllerId = tile instanceof IGregTechTileEntity gt ? gt.getMetaTileID() : -1;
        facing = alreadyDetached ? detachedFacing : facing(tile, liveContext, side);
        title = liveContext instanceof MTEEnhancedMultiBlockBase<?> machine ? machine.getLocalName()
            : controllerBlock.getLocalizedName();
    }

    /** A file-owned controller uses the same preview execution and coordinate conversion as a live target. */
    static HologramLocalControllerCapture forBlueprint(Block block, int meta, int id, String sourceClass,
        ExtendedFacing orientation, NBTTagCompound controllerState, NBTTagCompound settings) throws IOException {
        if (block == null || block == Blocks.air || id >= GregTechAPI.METATILEENTITIES.length)
            throw new IOException("来源控制器未注册");
        Object prototype = id < 0 ? null : GregTechAPI.METATILEENTITIES[id];
        if (id >= 0 && prototype == null) throw new IOException("来源控制器未注册");
        int anchorY = previewAnchorY(prototype);
        HologramResourceWorld memory = HologramResourceWorld.declarations(block, meta);
        TileEntity copy = block.createTileEntity(memory, meta);
        if (copy == null || copy.getWorldObj() != null && copy.getWorldObj() != memory)
            throw new IOException("来源方块无法创建独立控制器");
        copy.setWorldObj(memory);
        copy.xCoord = 0;
        copy.yCoord = anchorY;
        copy.zCoord = 0;
        copy.blockMetadata = meta;
        memory.tile = copy;

        NBTTagCompound owned = controllerState == null ? new NBTTagCompound() : (NBTTagCompound) controllerState.copy();
        owned.setInteger("x", 0);
        owned.setInteger("y", anchorY);
        owned.setInteger("z", 0);
        if (id >= 0) {
            if (!(copy instanceof BaseMetaTileEntity base)) throw new IOException("来源控制器身份不匹配");
            if (!sourceClass.isEmpty() && !sourceClass.equals(prototype.getClass().getName()))
                throw new IOException("来源控制器身份不匹配");
            String failure = StructureSources.sourceFailure(prototype.getClass());
            if (failure != null) throw new IOException(failure);
            StructureSources.PolicyBinding policy = StructureSources.policy(prototype.getClass());
            base.setInitialValuesAsNBT(controllerState == null || policy != null && policy.freshControllerOnly()
                ? null : owned, (short) id);
            Object context = HologramStructureDiscovery.context(copy);
            if (context == prototype || context == null || context.getClass() != prototype.getClass())
                throw new IOException("来源控制器无法创建独立实例");
            if (base.getFrontFacing() != orientation.getDirection()) base.setFrontFacing(orientation.getDirection());
        } else {
            if (copy instanceof IGregTechTileEntity || sourceClass.isEmpty() || !sourceClass.equals(copy.getClass().getName()))
                throw new IOException("来源控制器身份不匹配");
            String failure = StructureSources.sourceFailure(copy.getClass());
            if (failure != null) throw new IOException(failure);
            if (controllerState != null) copy.readFromNBT(owned);
        }
        if (copy.getWorldObj() != memory || copy.xCoord != 0 || copy.yCoord != anchorY || copy.zCoord != 0)
            throw new IOException("来源控制器的独立坐标已变化");
        IAlignment alignment = alignment(copy, HologramStructureDiscovery.context(copy));
        if (alignment != null && alignment.getExtendedFacing() != orientation
            && !alignment.checkedSetExtendedFacing(orientation))
            throw new IOException("无法恢复来源控制器的朝向");
        return new HologramLocalControllerCapture(
            copy, 0, orientation.getDirection().ordinal(), null, settings, "", orientation);
    }

    /** The GTMB master value is stored separately because the projector itself is unstackable. */
    public static NBTTagCompound toolSettings(ItemStack tool) {
        NBTTagCompound settings = new NBTTagCompound();
        NBTTagCompound tags = tool == null ? null : tool.getTagCompound();
        int main = tags != null && tags.hasKey("gtitHologramMain", 3)
            ? Math.max(1, tags.getInteger("gtitHologramMain"))
            : tool == null ? 1 : Math.max(1, tool.stackSize);
        NBTTagCompound channels = new NBTTagCompound();
        if (tool != null) ChannelDataAccessor.iterateChannelData(tool)
            .forEach(entry -> {
                if (entry.getValue() > 0) channels.setInteger(entry.getKey(), entry.getValue());
            });
        settings.setInteger("main", main);
        settings.setBoolean("mainExplicit", main != 1 || tags != null && tags.getBoolean("gtitHologramMainExplicit"));
        settings.setTag("channels", channels);
        return settings;
    }

    public static boolean validSettings(NBTTagCompound settings) {
        return settings.hasKey("main", 3) && settings.getInteger("main") > 0
            && HologramSessionPolicy.validChannels(settings.getCompoundTag("channels"));
    }

    static ItemStack previewTrigger(ItemStack tool, NBTTagCompound settings) {
        ItemStack trigger = tool == null ? new ItemStack(StructureLibAPI.getDefaultHologramItem(), 1)
            : new ItemStack(tool.getItem(), 1, tool.getItemDamage());
        NBTTagCompound copied = new NBTTagCompound();
        if (tool != null && tool.hasTagCompound()) {
            NBTTagCompound held = tool.getTagCompound();
            for (Object key : held.func_150296_c()) {
                String name = (String) key;
                if (!HologramSessionKeys.DRAFT.equals(name)) copied.setTag(name, held.getTag(name).copy());
            }
        }
        trigger.setTagCompound(copied);
        ChannelDataAccessor.wipeChannelData(trigger);
        trigger.stackSize = settings.getInteger("main");
        NBTTagCompound channels = settings.getCompoundTag("channels");
        for (Object key : channels.func_150296_c()) {
            String name = (String) key;
            ChannelDataAccessor.setChannelData(trigger, name, channels.getInteger(name));
        }
        if (!trigger.hasTagCompound()) trigger.setTagCompound(new NBTTagCompound());
        trigger.getTagCompound().setInteger("gtitHologramMain", trigger.stackSize);
        trigger.getTagCompound().setBoolean("gtitHologramMainExplicit", settings.getBoolean("mainExplicit"));
        return trigger;
    }

    public boolean matches(World current, int dimension) {
        return current == world && current != null
            && current.isRemote
            && this.dimension == dimension
            && !tile.isInvalid()
            && current.blockExists(x, y, z)
            && current.getTileEntity(x, y, z) == tile
            && HologramStructureDiscovery.context(tile) == liveContext
            && (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileID() == controllerId)
            && facing(tile, liveContext, side) == facing;
    }

    /** A header has the existing projection shape but no server session identity or receipts. */
    public NBTTagCompound opening() {
        NBTTagCompound state = header();
        state.setBoolean("capturePending", true);
        state.setTag("capabilities", shellCapabilities());
        return state;
    }

    public NBTTagCompound unavailable(String status) {
        NBTTagCompound state = header();
        state.setBoolean("supported", false);
        state.setBoolean("localStructureUnavailable", true);
        state.setString("status", status);
        state.setTag("capabilities", shellCapabilities());
        return state;
    }

    /** One client tick owns the native declaration walk or the row conversion work. */
    public NBTTagCompound step(int maxRows, long maxNanos) throws IOException {
        return stepRows(maxRows, maxNanos, false) ? finish(rows) : null;
    }

    /** Final native placements can be shown before material alternatives have been evaluated. */
    public HologramControllerTarget.Projection stepTarget(int maxRows, long maxNanos) throws IOException {
        if (!stepRows(maxRows, maxNanos, true)) return null;
        detailRows = new NBTTagList();
        if (capture != null) declared = capture.cells.iterator();
        NBTTagCompound metadata = opening();
        if (nativePreview != null) metadata.setTag("structureDescription", nativePreview.description());
        return new HologramControllerTarget.Projection(metadata, targetCells);
    }

    private boolean stepRows(int maxRows, long maxNanos, boolean targetOnly) throws IOException {
        checkStep(maxRows, maxNanos);
        if (detailRows != null) throw new IllegalStateException("Local controller target already published");
        if (targetOnly) {
            if (rows != null) throw new IllegalStateException("Local controller capture needs full rows");
            if (targetCells == null) targetCells = new ArrayList<>();
        } else {
            if (targetCells != null) throw new IllegalStateException("Local controller capture needs target cells");
            if (rows == null) rows = new NBTTagList();
        }
        long started = System.nanoTime();
        if (declared == null && nativePlacements == null) {
            long geometry = HologramRuntimeMetrics.start();
            try {
                if (!prepareGeometry(started + maxNanos)) return false;
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_GEOMETRY, geometry);
            }
        }
        long rowStart = HologramRuntimeMetrics.start();
        int work = 0;
        try {
            while (hasNext() && work < maxRows && (work == 0 || System.nanoTime() - started < maxNanos)) {
                HologramCapture.Cell cell = declared != null ? declared.next()
                    : targetOnly ? nativePreview.targetCell(nativePlacements.next())
                        : nativePreview.cell(nativePlacements.next());
                appendCell(cell, targetOnly);
                work++;
            }
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_ROWS, rowStart);
        }
        if (hasNext()) return false;
        if (nativePlacements != null) appendCell(anchor(), targetOnly);
        return true;
    }

    /** Details use the original native coordinates and the same target stack shown in the workbench. */
    public NBTTagCompound stepDetails(int maxRows, long maxNanos) throws IOException {
        checkStep(maxRows, maxNanos);
        if (detailRows == null) throw new IllegalStateException("Local controller target is unavailable");
        long started = System.nanoTime();
        long rowStart = HologramRuntimeMetrics.start();
        int work = 0;
        try {
            while (detailRows.tagCount() < targetCells.size() && work < maxRows
                && (work == 0 || System.nanoTime() - started < maxNanos)) {
                HologramControllerTarget.Cell target = targetCells.get(detailRows.tagCount());
                NBTTagCompound details = new NBTTagCompound();
                HologramCapture.Cell cell = declared == null ? null : declared.next();
                if (!target.anchor()) {
                    if (cell == null) cell = nativePreview.candidateCell(
                        target.dx() + detachedTile.xCoord,
                        target.dy() + detachedTile.yCoord,
                        target.dz() + detachedTile.zCoord,
                        target.role());
                    writeCandidates(details, cell, target.targetStack());
                    if (!cell.role.equals(target.role())) details.setString("role", cell.role);
                }
                detailRows.appendTag(details);
                work++;
            }
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_ROWS, rowStart);
        }
        return detailRows.tagCount() == targetCells.size() ? finish(detailRows) : null;
    }

    private void checkStep(int maxRows, long maxNanos) throws IOException {
        if (Thread.currentThread() != owner || maxRows <= 0 || maxNanos <= 0)
            throw new IllegalArgumentException("Invalid local controller capture step");
        if (complete) throw new IllegalStateException("Local controller model already published");
        if (!supports(tile) || liveContext == null || facing == null)
            throw new IOException("LOCAL_DECLARATION_UNAVAILABLE");
        String boundary = StructureSources.sourceFailure(liveContext.getClass());
        if (boundary != null) throw new IOException(boundary);
        if (!alreadyDetached && !matches(world, dimension)) throw new IOException("LOCAL_CONTROLLER_CHANGED");
        if (capture != null && declared != null) {
            String reason = capture.sourceValidityFailure();
            if (reason != null) throw new IOException(reason);
        }
    }

    private NBTTagCompound finish(NBTTagList resultRows) {
        NBTTagCompound state = header();
        state.setBoolean("projectionDataComplete", true);
        if (detailRows != null) state.setBoolean("capturePending", false);
        state.setTag("cells", resultRows);
        if (nativePreview != null) {
            state.setTag("structureDescription", nativePreview.description());
            report = nativePreview.channels();
        }
        long capabilityStart = HologramRuntimeMetrics.start();
        try {
            state.setTag("capabilities", capabilities(report));
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_CAPABILITIES, capabilityStart);
        }
        if (alreadyDetached && capture != null && capture.sourceResolution.status == StructureSources.Status.READY
            && capture.sourceResolution.hasObservedReadSet()) {
            StructureSources.ReadSet source = capture.sourceResolution.validityProof();
            HologramDeclarationAdapters.Bindings declarations = capture.declarationBindings;
            reuseProof = () -> {
                String failure = source.invalidReason();
                return failure == null ? declarations.validityFailure() : failure;
            };
        }
        close();
        return state;
    }

    StructureSources.ReadSet reuseProof() {
        return reuseProof;
    }

    @Override
    public void close() {
        complete = true;
        capture = null;
        detached = null;
        detachedTile = null;
        detachedContext = null;
        declared = null;
        nativePlacements = null;
        if (nativePreview != null) nativePreview.close();
        nativePreview = null;
        report = null;
        rows = null;
        targetCells = null;
        detailRows = null;
        facingMasks.clear();
    }

    private boolean hasNext() {
        return declared != null ? declared.hasNext() : nativePlacements.hasNext();
    }

    private boolean prepareGeometry(long deadline) throws IOException {
        if (nativePreview == null && liveContext instanceof MTEEnhancedMultiBlockBase<?>
            && StructureSources.hasSource(liveContext.getClass())) {
            if (detached == null) detached = alreadyDetached ? (MTEEnhancedMultiBlockBase<?>) liveContext
                : HologramDetachedController.create(controllerId, facing);
            detachedTile = (TileEntity) detached.getBaseMetaTileEntity();
            detachedContext = detached;
            capture = HologramCapture.collectLocalProjection(
                detached, trigger, facing, capture, Math.max(1, deadline - System.nanoTime()));
            if (capture.pending) return false;
            if (!capture.incomplete && capture.cells.size() > 1) {
                declared = capture.cells.iterator();
                report = capture.channelReport;
                return true;
            }
            throw new IOException(capture.failure.isEmpty() ? "LOCAL_DECLARATION_UNAVAILABLE" : capture.failure);
        }
        if (nativePreview == null) {
            detachedTile = alreadyDetached ? tile : createNativeController();
            detachedContext = HologramStructureDiscovery.context(detachedTile);
            IConstructable constructable = HologramStructureDiscovery.localConstructable(detachedTile, detachedContext, facing);
            if (constructable == null) throw new IOException("LOCAL_DECLARATION_UNAVAILABLE");
            boolean structureCompat = controllerId < 0 && IMultiblockInfoContainer.contains(detachedTile.getClass());
            nativePreview = new HologramNativeStructurePreview(
                detachedTile, detachedContext, constructable, structureCompat, previewTrigger(null, settings));
        }
        if (!nativePreview.advance(deadline)) return false;
        nativePlacements = nativePreview.placements();
        return true;
    }

    private TileEntity createNativeController() throws IOException {
        HologramResourceWorld memory = HologramResourceWorld.declarations(controllerBlock, controllerMeta);
        TileEntity copy = controllerBlock.createTileEntity(memory, controllerMeta);
        if (copy == null) try {
            copy = tile.getClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IOException("LOCAL_NATIVE_CONTROLLER_UNAVAILABLE", failure);
        }
        if (copy == tile || copy.getClass() != tile.getClass()
            || copy.getWorldObj() != null && copy.getWorldObj() != memory)
            throw new IOException("LOCAL_NATIVE_CONTROLLER_UNAVAILABLE");
        copy.setWorldObj(memory);
        copy.xCoord = 0;
        copy.yCoord = previewAnchorY(liveContext);
        copy.zCoord = 0;
        copy.blockMetadata = controllerMeta;
        memory.tile = copy;
        if (controllerId >= 0) {
            if (!(copy instanceof BaseMetaTileEntity gt)) throw new IOException("LOCAL_NATIVE_CONTROLLER_UNAVAILABLE");
            gt.setInitialValuesAsNBT(null, (short) controllerId);
            Object context = HologramStructureDiscovery.context(copy);
            if (context == null || context == liveContext || context.getClass() != liveContext.getClass()
                || controllerId >= GregTechAPI.METATILEENTITIES.length
                || context == GregTechAPI.METATILEENTITIES[controllerId])
                throw new IOException("LOCAL_NATIVE_CONTROLLER_UNAVAILABLE");
            if (gt.getFrontFacing() != facing.getDirection()) gt.setFrontFacing(facing.getDirection());
        }
        Object context = HologramStructureDiscovery.context(copy);
        IAlignment alignment = alignment(copy, context);
        if (alignment != null && alignment.getExtendedFacing() != facing && !alignment.checkedSetExtendedFacing(facing))
            throw new IOException("LOCAL_NATIVE_CONTROLLER_FACING_UNAVAILABLE");
        return copy;
    }

    private static int previewAnchorY(Object context) {
        return HologramBlockRendererPreview.required(context) ? HologramBlockRendererPreview.CONTROLLER_Y : 128;
    }

    private static ExtendedFacing facing(TileEntity tile, Object context, int side) {
        // Critical's alignment getter binds state to its host. Read the base orientation on the live controller.
        if (HologramBlockRendererPreview.required(context) && tile instanceof IGregTechTileEntity gt)
            return ExtendedFacing.of(gt.getFrontFacing());
        IAlignment alignment = alignment(tile, context);
        if (alignment != null) return alignment.getExtendedFacing();
        if (tile instanceof IGregTechTileEntity gt) return ExtendedFacing.of(gt.getFrontFacing());
        ForgeDirection direction = ForgeDirection.getOrientation(side);
        return direction == ForgeDirection.UNKNOWN ? ExtendedFacing.DEFAULT : ExtendedFacing.of(direction);
    }

    private static IAlignment alignment(TileEntity tile, Object context) {
        if (context instanceof IAlignment aligned) return aligned;
        if (tile instanceof IAlignment aligned) return aligned;
        if (tile instanceof IAlignmentProvider provider) return provider.getAlignment();
        return context instanceof IAlignmentProvider provider ? provider.getAlignment() : null;
    }

    private HologramCapture.Cell anchor() {
        HologramCapture.Cell cell = new HologramCapture.Cell(
            null, detachedTile.xCoord, detachedTile.yCoord, detachedTile.zCoord, null, true);
        cell.anchor = true;
        cell.block = controllerBlock;
        cell.meta = controllerMeta;
        cell.role = "controller";
        cell.actualFacing = facing.getDirection().ordinal();
        cell.actualStack = controllerId >= 0 ? new ItemStack(GregTechAPI.sBlockMachines, 1, controllerId)
            : HologramBlockIdentity.materialStack(controllerBlock, controllerMeta);
        cell.actualDisplay = HologramDisplaySnapshot.projection(detachedTile);
        return cell;
    }

    private NBTTagList capabilities(HologramChannelTrace.Report report) {
        HologramCapabilities.Result capabilities = HologramCapabilities.describe(detachedContext, trigger, report)
            .withNativeDefinition(nativePreview != null ? nativePreview.definition() : capture == null ? null : capture.definition)
            .withSavedChannels(settings.getCompoundTag("channels"));
        if (nativePreview != null && controllerId >= 0) capabilities.withFixedSignal("gt_hatch", 1);
        if (detachedContext != null && "com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter"
            .equals(detachedContext.getClass().getName())) capabilities.withDefaultSignal("ore_center_sectors", 1);
        return capabilities.toNBT();
    }

    private NBTTagList shellCapabilities() {
        return HologramCapabilities.describe(null, trigger, null)
            .withSavedChannels(settings.getCompoundTag("channels")).toNBT();
    }

    private NBTTagCompound header() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", session);
        state.setString("generation", generation);
        state.setString("editorWorkspace", "projection");
        state.setString("workspaceTab", "structure");
        state.setString("openPurpose", "controllerStructure");
        state.setBoolean("localStandardController", true);
        state.setBoolean("structureProjection", true);
        state.setBoolean("target", true);
        state.setBoolean("supported", true);
        state.setBoolean("keepController", true);
        state.setBoolean("quotesIncomplete", true);
        state.setInteger("dimension", dimension);
        state.setInteger("x", x);
        state.setInteger("y", y);
        state.setInteger("z", z);
        state.setInteger("side", side);
        state.setInteger("selected", -1);
        for (String key : new String[] { "main", "mode", "scope", "layer", "materialPriority" })
            state.setInteger(key, settings.getInteger(key));
        for (String key : new String[] { "mainExplicit", "noHatches", "materialMain", "materialContainers", "materialMe" })
            if (settings.hasKey(key)) state.setBoolean(key, settings.getBoolean(key));
        state.setTag("channels", settings.getCompoundTag("channels").copy());
        state.setString("title", title);
        if (liveContext != null) {
            Class<?> source = liveContext.getClass();
            state.setString("sourceClass", source.getName());
            state.setString("sourceMod", HologramSessionPolicy.sourceMod(source));
        }
        if (facing != null) {
            state.setInteger("facing", facing.ordinal());
            state.setIntArray("facings", new int[] { facing.ordinal() });
        }
        return state;
    }

    private void appendCell(HologramCapture.Cell cell, boolean targetOnly) {
        HologramControllerTarget.Cell target = target(cell);
        if (targetOnly) {
            targetCells.add(target);
            return;
        }
        NBTTagCompound row = target.tag();
        if (!cell.anchor) writeCandidates(row, cell, target.targetStack());
        rows.appendTag(row);
    }

    private HologramControllerTarget.Cell target(HologramCapture.Cell cell) {
        int dx = cell.x - detachedTile.xCoord, dy = cell.y - detachedTile.yCoord, dz = cell.z - detachedTile.zCoord;
        int bx = Math.addExact(x, dx), by = Math.addExact(y, dy), bz = Math.addExact(z, dz);
        Block block = cell.block != null || cell.renderHint == null ? cell.block : cell.renderHint.block;
        int wantMeta = cell.block != null || cell.renderHint == null ? cell.meta : cell.renderHint.metadata;
        String registeredTarget = block == null ? null : Block.blockRegistry.getNameForObject(block);
        String wantId = block == null ? "" : String.valueOf(registeredTarget);
        Block hint = StructureLibAPI.getBlockHint();
        ItemStack target = cell.anchor ? cell.actualStack : cell.previewStack != null ? cell.previewStack
            : cell.nativeSourceWater || block == null || block == hint || block == Blocks.air ? null
                : HologramBlockIdentity.materialStack(block, wantMeta);
        Block actualBlock = cell.anchor ? block : Blocks.air;
        String id = cell.anchor ? wantId : "minecraft:air";
        int meta = cell.anchor ? wantMeta : 0;
        int actualFacing = cell.anchor ? cell.actualFacing : -1;
        ItemStack actual = cell.anchor && target != null ? target.copy() : null;
        NBTTagCompound actualDisplay = cell.anchor ? cell.actualDisplay : null;
        NBTTagCompound targetDisplay = !cell.anchor ? cell.targetDisplay
            : actualDisplay == null ? null : (NBTTagCompound) actualDisplay.copy();
        boolean observationMissing = false;
        if (!alreadyDetached && world.isRemote) {
            long observation = HologramRuntimeMetrics.start();
            try {
                observationMissing = !isCellLoaded(bx, by, bz);
                if (!observationMissing) {
                    actualBlock = world.getBlock(bx, by, bz);
                    meta = world.getBlockMetadata(bx, by, bz);
                    id = Block.blockRegistry.getNameForObject(actualBlock);
                    TileEntity current = actualBlock.hasTileEntity(meta) ? world.getTileEntity(bx, by, bz) : null;
                    if (current instanceof IGregTechTileEntity gt && gt.getMetaTileEntity() != null) {
                        actual = new ItemStack(actualBlock, 1, gt.getMetaTileID());
                        actualFacing = gt.getFrontFacing().ordinal();
                        actualDisplay = HologramDisplaySnapshot.projection(current);
                    } else {
                        actual = actualBlock == GregTechAPI.sBlockMachines ? null
                            : HologramBlockIdentity.materialStack(actualBlock, meta);
                    }
                }
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_WORLD, observation);
            }
        }
        int[] hintTint = null;
        if (cell.hintTint != null) {
            hintTint = new int[cell.hintTint.length];
            for (int i = 0; i < hintTint.length; i++) hintTint[i] = cell.hintTint[i];
        }
        return new HologramControllerTarget.Cell(
            bx, by, bz, dx, dy, dz, id, wantId, meta, wantMeta,
            id == null || id.isEmpty() || "null".equals(id) ? null : actualBlock, registeredTarget == null ? null : block,
            actual, target, actualDisplay, targetDisplay, cell.anchor, cell.iconOnly, cell.unknown || block == hint,
            cell.anchor ? "protected" : cell.unknown ? "unsupported" : "missing", cell.role, cell.family,
            target != null && target.getItem() instanceof ItemMachines ? "item" : cell.renderKind,
            cell.anchor ? "" : cell.prompt, actualFacing, cell.anchor ? cell.actualFacing : cell.previewFacing,
            cell.hintIcons == null ? null : cell.hintIcons.clone(), hintTint, cell.hintIndex, observationMissing,
            cell.nativeSourceWater ? HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER
                : HologramControllerTarget.TargetKind.MATERIAL);
    }

    private void writeCandidates(NBTTagCompound row, HologramCapture.Cell cell, ItemStack material) {
        NBTTagList candidates = new NBTTagList();
        NBTTagList roles = new NBTTagList();
        int chosen = -1;
        byte[] facingMasks = null;
        int[] tiers = new int[cell.candidates == null ? 0 : cell.candidates.size()];
        if (cell.candidates != null) {
            for (int index = 0; index < cell.candidates.size(); index++) {
                ItemStack candidate = cell.candidates.get(index);
                candidates.appendTag(candidate.writeToNBT(new NBTTagCompound()));
                roles.appendTag(new NBTTagString(HologramCellPolicy.candidateRole(cell, candidate)));
                int tier = HologramCellPolicy.hatchTier(candidate);
                tiers[index] = tier == Integer.MAX_VALUE ? -1 : tier;
                if (tier != Integer.MAX_VALUE) {
                    int mask = facingMask(cell, candidate);
                    if (mask != 0) {
                        if (facingMasks == null) facingMasks = new byte[tiers.length];
                        facingMasks[index] = (byte) mask;
                    }
                }
                if (HologramValues.same(material, candidate)) chosen = index;
            }
        }
        row.setTag("candidates", candidates);
        row.setTag("candidateRoles", roles);
        row.setIntArray("candidateTiers", tiers);
        if (facingMasks != null) row.setByteArray("candidateFacingMasks", facingMasks);
        int materialMask = chosen >= 0 && facingMasks != null ? facingMasks[chosen] : 0;
        if (chosen < 0 && HologramCellPolicy.hatchTier(material) != Integer.MAX_VALUE)
            materialMask = facingMask(cell, material);
        if (materialMask != 0) {
            row.setIntArray("hatchFacings", HologramPreviewHatchFacings.directions(materialMask));
            row.setInteger("hatchFacing", -1);
        }
        row.setInteger("chosenChoice", chosen);
        row.setBoolean("candidateComplete", cell.candidateComplete);
        if (cell.nativeCandidateCount >= 0) row.setInteger("nativeCandidateCount", cell.nativeCandidateCount);
        row.setString("candidateBoundary", cell.candidateBoundary);
    }

    private int facingMask(HologramCapture.Cell cell, ItemStack sample) {
        if (cell.element == null) return 0;
        Map<HologramBlueprintResources.ExactKey, Integer> masks = facingMasks.computeIfAbsent(
            cell.element, ignored -> new HashMap<>());
        Integer present = masks.get(HologramBlueprintResources.ExactKey.borrowed(sample));
        if (present != null) return present;
        int mask = HologramPreviewHatchFacings.mask(
            HologramCellPolicy.previewHatchFacings(cell, sample, detachedContext, facing));
        masks.put(new HologramBlueprintResources.ExactKey(sample, null), mask);
        return mask;
    }

    private boolean isCellLoaded(int bx, int by, int bz) {
        int cx = bx >> 4, cz = bz >> 4;
        if (by < 0 || by >= world.getHeight() || !world.getChunkProvider().chunkExists(cx, cz)) return false;
        Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
        return chunk != null && !(chunk instanceof EmptyChunk);
    }
}
