// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Iterator;
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
    private final String session;
    private final NBTTagList rows = new NBTTagList();
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
        state.setTag("capabilities", capabilities(null));
        return state;
    }

    public NBTTagCompound unavailable(String status) {
        NBTTagCompound state = header();
        state.setBoolean("supported", false);
        state.setBoolean("localStructureUnavailable", true);
        state.setString("status", status);
        state.setTag("capabilities", capabilities(null));
        return state;
    }

    /** One client tick owns the native declaration walk or the row conversion work. */
    public NBTTagCompound step(int maxRows, long maxNanos) throws IOException {
        if (Thread.currentThread() != owner || maxRows <= 0 || maxNanos <= 0)
            throw new IllegalArgumentException("Invalid local controller capture step");
        if (complete) throw new IllegalStateException("Local controller model already published");
        if (!supports(tile) || liveContext == null || facing == null)
            throw new IOException("LOCAL_DECLARATION_UNAVAILABLE");
        String boundary = StructureSources.sourceFailure(liveContext.getClass());
        if (boundary != null) throw new IOException(boundary);
        if (!alreadyDetached && !matches(world, dimension)) throw new IOException("LOCAL_CONTROLLER_CHANGED");
        long started = System.nanoTime();
        if (declared == null && nativePlacements == null) {
            long geometry = HologramRuntimeMetrics.start();
            try {
                if (!prepareGeometry(started + maxNanos)) return null;
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_GEOMETRY, geometry);
            }
        }
        long rowStart = HologramRuntimeMetrics.start();
        int work = 0;
        try {
            while (hasNext() && work++ < maxRows && (work == 1 || System.nanoTime() - started < maxNanos)) {
                HologramCapture.Cell cell = declared == null ? nativePreview.cell(nativePlacements.next())
                    : declared.next();
                rows.appendTag(row(cell));
            }
        } finally {
            HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_ROWS, rowStart);
        }
        if (hasNext()) return null;
        if (nativePlacements != null) rows.appendTag(row(anchor()));
        NBTTagCompound state = header();
        state.setBoolean("projectionDataComplete", true);
        state.setTag("cells", rows);
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
        close();
        return state;
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

    private NBTTagCompound header() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", session);
        state.setString("generation", session);
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

    private NBTTagCompound row(HologramCapture.Cell cell) {
        int dx = cell.x - detachedTile.xCoord, dy = cell.y - detachedTile.yCoord, dz = cell.z - detachedTile.zCoord;
        Block block = cell.block != null || cell.renderHint == null ? cell.block : cell.renderHint.block;
        int metadata = cell.block != null || cell.renderHint == null ? cell.meta : cell.renderHint.metadata;
        Block hint = StructureLibAPI.getBlockHint();
        ItemStack material = cell.anchor ? cell.actualStack : cell.previewStack != null ? cell.previewStack
            : block == null || block == hint || block == Blocks.air ? null
                : HologramBlockIdentity.materialStack(block, metadata);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("x", Math.addExact(x, dx));
        nbt.setInteger("y", Math.addExact(y, dy));
        nbt.setInteger("z", Math.addExact(z, dz));
        nbt.setInteger("dx", dx);
        nbt.setInteger("dy", dy);
        nbt.setInteger("dz", dz);
        String registry = block == null ? "" : String.valueOf(Block.blockRegistry.getNameForObject(block));
        nbt.setString("id", cell.anchor ? registry : "minecraft:air");
        nbt.setInteger("meta", cell.anchor ? metadata : 0);
        nbt.setString("wantId", registry);
        nbt.setInteger("wantMeta", metadata);
        nbt.setBoolean("anchor", cell.anchor);
        nbt.setBoolean("inScope", true);
        nbt.setBoolean("missingBlock", cell.unknown || block == hint);
        nbt.setBoolean("iconOnly", cell.iconOnly);
        nbt.setString("status", cell.anchor ? "protected" : cell.unknown ? "unsupported" : "missing");
        nbt.setString("role", cell.role);
        nbt.setString("family", cell.family);
        nbt.setString("renderKind", material != null && material.getItem() instanceof ItemMachines ? "item" : cell.renderKind);
        nbt.setInteger("targetFacing", cell.anchor ? cell.actualFacing : cell.previewFacing);
        nbt.setInteger("actualFacing", cell.anchor ? cell.actualFacing : -1);
        if (material != null) {
            NBTTagCompound stack = material.writeToNBT(new NBTTagCompound());
            nbt.setTag("targetStack", stack);
            if (cell.anchor) nbt.setTag("actualStack", stack.copy());
        }
        if (cell.anchor && cell.actualDisplay != null) {
            nbt.setTag("actualDisplay", cell.actualDisplay.copy());
            nbt.setTag("targetDisplay", cell.actualDisplay.copy());
        }
        if (!cell.anchor && cell.targetDisplay != null) nbt.setTag("targetDisplay", cell.targetDisplay.copy());
        if (!cell.anchor) {
            NBTTagList candidates = new NBTTagList();
            NBTTagList roles = new NBTTagList();
            int chosen = -1;
            int[] tiers = new int[cell.candidates == null ? 0 : cell.candidates.size()];
            if (cell.candidates != null) {
                for (int index = 0; index < cell.candidates.size(); index++) {
                    ItemStack candidate = cell.candidates.get(index);
                    candidates.appendTag(candidate.writeToNBT(new NBTTagCompound()));
                    roles.appendTag(new NBTTagString(HologramCellPolicy.candidateRole(cell, candidate)));
                    int tier = HologramCellPolicy.hatchTier(candidate);
                    tiers[index] = tier == Integer.MAX_VALUE ? -1 : tier;
                    if (HologramValues.same(material, candidate)) chosen = index;
                }
            }
            nbt.setTag("candidates", candidates);
            nbt.setTag("candidateRoles", roles);
            nbt.setIntArray("candidateTiers", tiers);
            nbt.setInteger("chosenChoice", chosen);
            nbt.setBoolean("candidateComplete", cell.candidateComplete);
            nbt.setString("candidateBoundary", cell.candidateBoundary);
            nbt.setString("prompt", cell.prompt);
        }
        if (cell.hintIcons != null) {
            NBTTagList icons = new NBTTagList();
            for (String icon : cell.hintIcons) icons.appendTag(new NBTTagString(icon == null ? "" : icon));
            nbt.setTag("hintIcons", icons);
        }
        if (cell.hintTint != null) {
            int[] tint = new int[cell.hintTint.length];
            for (int i = 0; i < tint.length; i++) tint[i] = cell.hintTint[i];
            nbt.setIntArray("hintTint", tint);
        }
        nbt.setInteger("hintIndex", cell.hintIndex);
        if (!alreadyDetached && world.isRemote) {
            long observation = HologramRuntimeMetrics.start();
            try {
                observeLoadedCell(nbt);
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_WORLD, observation);
            }
        }
        return nbt;
    }

    private void observeLoadedCell(NBTTagCompound row) {
        int bx = row.getInteger("x"), by = row.getInteger("y"), bz = row.getInteger("z");
        int cx = bx >> 4, cz = bz >> 4;
        if (by < 0 || by >= world.getHeight() || !world.getChunkProvider().chunkExists(cx, cz)) {
            row.setBoolean("localObservationMissing", true);
            row.setString("reason", "客户端尚未读到此位置的现场数据");
            return;
        }
        Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
        if (chunk == null || chunk instanceof EmptyChunk) {
            row.setBoolean("localObservationMissing", true);
            row.setString("reason", "客户端尚未读到此位置的现场数据");
            return;
        }

        Block current = world.getBlock(bx, by, bz);
        int metadata = world.getBlockMetadata(bx, by, bz);
        TileEntity currentTile = current.hasTileEntity(metadata) ? world.getTileEntity(bx, by, bz) : null;
        row.setString("id", Block.blockRegistry.getNameForObject(current));
        row.setInteger("meta", metadata);
        row.removeTag("actualStack");
        ItemStack stack = null;
        if (currentTile instanceof IGregTechTileEntity gt && gt.getMetaTileEntity() != null) {
            stack = new ItemStack(current, 1, gt.getMetaTileID());
            row.setInteger("actualFacing", gt.getFrontFacing().ordinal());
            row.setTag("actualDisplay", HologramDisplaySnapshot.projection(currentTile));
        } else if (current != Blocks.air && current != GregTechAPI.sBlockMachines) {
            stack = HologramBlockIdentity.materialStack(current, metadata);
        }
        if (stack != null) row.setTag("actualStack", stack.writeToNBT(new NBTTagCompound()));
    }
}
