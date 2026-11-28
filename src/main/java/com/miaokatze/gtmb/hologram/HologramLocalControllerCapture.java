// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
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
    private HologramControllerMaterialCounts.Builder targetMaterials;
    private boolean targetPublished;
    private HologramNativePresetIndex presetIndex;
    private final Map<IStructureElement<?>, Map<HologramBlueprintResources.ExactKey, Integer>> facingMasks = new IdentityHashMap<>();
    private final HologramLocalControllerSite site = new HologramLocalControllerSite();
    private HologramInstalledControllerShape installedShape;
    private List<HologramControllerTarget.Cell> installedCells;
    private it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<HologramControllerTarget.Cell> installedOverlap;
    private HologramControllerTarget.Cell previewAnchor;
    private int installedNonAir, installedUnknown, installedHatches;
    private final NBTTagCompound settings;
    private final ItemStack trigger;
    private final boolean alreadyDetached;
    private final boolean nativeWorkflow;
    private MTEEnhancedMultiBlockBase<?> detached;
    private TileEntity detachedTile;
    private Object detachedContext;
    private HologramCapture capture;
    private HologramNativeStructurePreview nativePreview;
    private HologramNativeTargetPlan nativeTarget;
    private List<HologramCapture.Cell> nativeTargetCells;
    private HologramNativeHatchChooser sourceCandidates;
    private final HologramDeclarationAdapters.Bindings presetDeclarations = new HologramDeclarationAdapters.Bindings();
    private Iterator<HologramCapture.Cell> declared;
    private Iterator<HologramNativePreviewWorld.Placement> nativePlacements;
    private HologramChannelTrace.Report report;
    private NBTTagList publishedCapabilities;
    private boolean geometryReady;
    private final long openedAt = System.nanoTime();
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
        liveContext = HologramStructureDiscovery.context(tile);
        controllerBlock = world == null || !world.blockExists(x, y, z) ? Blocks.air : world.getBlock(x, y, z);
        controllerMeta = controllerBlock == Blocks.air ? 0 : world.getBlockMetadata(x, y, z);
        controllerId = tile instanceof IGregTechTileEntity gt ? gt.getMetaTileID() : -1;
        nativeWorkflow = !alreadyDetached && controllerId >= 0
            && HologramStructureDiscovery.constructable(
                tile,
                liveContext,
                side) instanceof com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
        this.trigger = nativeWorkflow
            ? HologramNativeTargetPlan
                .canonicalTrigger(this.settings.getInteger("main"), this.settings.getCompoundTag("channels"))
            : previewTrigger(tool, this.settings);
        facing = alreadyDetached ? detachedFacing
            : nativeWorkflow ? nativeFacing(tile, liveContext, side) : facing(tile, liveContext, side);
        title = liveContext instanceof MTEEnhancedMultiBlockBase<?>machine ? machine.getLocalName()
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
            if (!sourceClass.isEmpty() && !sourceClass.equals(
                prototype.getClass()
                    .getName()))
                throw new IOException("来源控制器身份不匹配");
            String failure = StructureSources.sourceFailure(prototype.getClass());
            if (failure != null) throw new IOException(failure);
            StructureSources.PolicyBinding policy = StructureSources.policy(prototype.getClass());
            base.setInitialValuesAsNBT(
                controllerState == null || policy != null && policy.freshControllerOnly() ? null : owned,
                (short) id);
            Object context = HologramStructureDiscovery.context(copy);
            if (context == prototype || context == null || context.getClass() != prototype.getClass())
                throw new IOException("来源控制器无法创建独立实例");
            if (base.getFrontFacing() != orientation.getDirection()) base.setFrontFacing(orientation.getDirection());
        } else {
            if (copy instanceof IGregTechTileEntity || sourceClass.isEmpty()
                || !sourceClass.equals(
                    copy.getClass()
                        .getName()))
                throw new IOException("来源控制器身份不匹配");
            String failure = StructureSources.sourceFailure(copy.getClass());
            if (failure != null) throw new IOException(failure);
            if (controllerState != null) copy.readFromNBT(owned);
        }
        if (copy.getWorldObj() != memory || copy.xCoord != 0 || copy.yCoord != anchorY || copy.zCoord != 0)
            throw new IOException("来源控制器的独立坐标已变化");
        IAlignment alignment = alignment(copy, HologramStructureDiscovery.context(copy));
        if (alignment != null && alignment.getExtendedFacing() != orientation
            && !alignment.checkedSetExtendedFacing(orientation)) throw new IOException("无法恢复来源控制器的朝向");
        return new HologramLocalControllerCapture(
            copy,
            0,
            orientation.getDirection()
                .ordinal(),
            null,
            settings,
            "",
            orientation);
    }

    /** The native tool stack count is a physical signal, not a previous GTMB planning value. */
    public static int defaultMainSignal(ItemStack tool) {
        return tool == null ? 1 : Math.max(1, tool.stackSize);
    }

    /** New workspaces inherit only physical StructureLib inputs, not a previous GTMB workspace. */
    public static NBTTagCompound toolSettings(ItemStack tool) {
        NBTTagCompound settings = new NBTTagCompound();
        int main = defaultMainSignal(tool);
        NBTTagCompound channels = new NBTTagCompound();
        if (tool != null) ChannelDataAccessor.iterateChannelData(tool)
            .forEach(entry -> { if (entry.getValue() > 0) channels.setInteger(entry.getKey(), entry.getValue()); });
        settings.setInteger("main", main);
        settings.setBoolean("mainExplicit", main != 1);
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
                if (!HologramSessionKeys.DRAFT.equals(name)) copied.setTag(
                    name,
                    held.getTag(name)
                        .copy());
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
        trigger.getTagCompound()
            .setInteger("gtitHologramMain", trigger.stackSize);
        trigger.getTagCompound()
            .setBoolean("gtitHologramMainExplicit", settings.getBoolean("mainExplicit"));
        return trigger;
    }

    public boolean matches(World current, int dimension) {
        return controllerContextMatches(current, dimension)
            && (installedShape == null || installedShape.matches(liveContext));
    }

    public boolean controllerContextMatches(World current, int dimension) {
        return current == world && current != null
            && current.isRemote
            && this.dimension == dimension
            && !tile.isInvalid()
            && current.blockExists(x, y, z)
            && current.getTileEntity(x, y, z) == tile
            && HologramStructureDiscovery.context(tile) == liveContext
            && (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileID() == controllerId)
            && (nativeWorkflow ? nativeFacing(tile, liveContext, side) : facing(tile, liveContext, side)) == facing;
    }

    /** A header has the existing projection shape but no server session identity or receipts. */
    public NBTTagCompound opening() {
        NBTTagCompound state = header(false);
        state.setBoolean("capturePending", true);
        state.setTag("capabilities", shellCapabilities());
        return state;
    }

    public NBTTagCompound unavailable(String status) {
        NBTTagCompound state = header(false);
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
        targetPublished = true;
        NBTTagCompound metadata = header();
        metadata.setBoolean("projectionDataComplete", true);
        metadata.setTag("capabilities", readyCapabilities());
        if (!alreadyDetached && nativeTarget == null) site.publish(metadata);
        if (nativePreview != null) metadata.setTag("structureDescription", nativePreview.description());
        publishInstalledSite(metadata);
        if (!alreadyDetached) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info(
                "Controller preview stage=capture generation={} source={} declaredPieces={} survivalPasses={} lastSurvivalResult={} creativeDisplayOnly={} targetWithAnchor={} observedNonAir={} unknown={} elapsedMs={}",
                generation,
                metadata.getString("sourceClass"),
                capture == null ? -1 : capture.pieces.size(),
                nativePreview == null ? 0 : nativePreview.passes(),
                nativePreview == null || nativePreview.nativeResult() == Integer.MIN_VALUE ? "n/a"
                    : Integer.toString(nativePreview.nativeResult()),
                nativePreview == null ? 0 : nativePreview.extraDisplayCells(),
                targetCells.size(),
                metadata.getInteger("siteOccupied"),
                metadata.getInteger("siteUnknown"),
                (System.nanoTime() - openedAt) / 1_000_000L);
        }
        HologramControllerMaterialCounts materials = targetMaterials.seal();
        targetMaterials = null;
        if (installedCells != null) com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info(
            "Controller preview stage=installed-site generation={} nativeGroups={} declared={} observedNonAir={} unknown={}",
            generation,
            installedShape.groups(),
            installedCells.size(),
            installedNonAir,
            installedUnknown);
        return new HologramControllerTarget.Projection(metadata, targetCells, materials, installedCells);
    }

    private boolean stepRows(int maxRows, long maxNanos, boolean targetOnly) throws IOException {
        checkStep(maxRows, maxNanos);
        if (targetPublished) throw new IllegalStateException("Local controller target already published");
        if (targetOnly) {
            if (rows != null) throw new IllegalStateException("Local controller capture needs full rows");
            if (targetCells == null) {
                targetCells = new ArrayList<>();
                targetMaterials = new HologramControllerMaterialCounts.Builder();
                if (!nativeWorkflow && !alreadyDetached && world.isRemote)
                    presetIndex = new HologramNativePresetIndex(liveContext);
                if (!nativeWorkflow && !alreadyDetached && world.isRemote) {
                    installedShape = HologramInstalledControllerShape.read(liveContext, trigger);
                    if (installedShape != null) {
                        installedCells = new ArrayList<>();
                        installedOverlap = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
                    }
                }
            }
        } else {
            if (targetCells != null) throw new IllegalStateException("Local controller capture needs target cells");
            if (rows == null) rows = new NBTTagList();
        }
        long started = System.nanoTime();
        if (declared == null && nativePlacements == null) {
            long geometry = HologramRuntimeMetrics.start();
            try {
                if (!prepareGeometry(started + maxNanos)) return false;
                geometryReady = true;
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
        if ((nativePlacements != null || nativeTarget != null) && previewAnchor == null)
            appendCell(anchor(), targetOnly);
        if (installedShape == null) return true;
        // Reuse the overlapping observations. Only the installed declaration contributes extra SITE rows.
        long deadline = started + maxNanos;
        for (; installedShape.hasNext() && work < maxRows && System.nanoTime() < deadline; work++) {
            var offset = installedShape.next(facing);
            if (offset == null || offset.x() == 0 && offset.y() == 0 && offset.z() == 0) continue;
            int bx = Math.addExact(x, offset.x()), by = Math.addExact(y, offset.y()), bz = Math.addExact(z, offset.z());
            HologramControllerTarget.Cell current = HologramNativePreviewWorld.packable(bx, by, bz)
                ? installedOverlap.get(CoordinatePacker.pack(bx, by, bz))
                : null;
            if (current == null) {
                HologramCapture.Cell cell = new HologramCapture.Cell(
                    null,
                    detachedTile.xCoord + offset.x(),
                    detachedTile.yCoord + offset.y(),
                    detachedTile.zCoord + offset.z(),
                    null,
                    true);
                current = target(cell, true);
            }
            if (current.localObservationMissing()) installedUnknown++;
            else if (current.actualBlock() != Blocks.air) {
                installedNonAir++;
                if (current.actualDisplay() != null && current.actualDisplay()
                    .hasKey("siteHatchTier", 3)) installedHatches++;
            }
            installedCells.add(current);
            if (!HologramBlueprintLimits.accepts(installedCells.size() + 1L))
                throw new IOException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
        }
        if (installedShape.hasNext()) return false;
        if (previewAnchor != null) installedCells.add(previewAnchor);
        installedOverlap = null;
        return true;
    }

    /** The capability report depends on the declaration, not on rendering or candidate rows. */
    public NBTTagList readyCapabilities() {
        if (Thread.currentThread() != owner) throw new IllegalArgumentException("Invalid local capability owner");
        if (!geometryReady || complete) return null;
        if (publishedCapabilities == null)
            publishedCapabilities = capabilities(nativePreview == null ? report : nativePreview.channels());
        return (NBTTagList) publishedCapabilities.copy();
    }

    /** Old profile shape; reads only the initial sparse hatch index and the supplied exact edits. */
    public NBTTagCompound captureNativeHatchProfile(Map<Integer, ItemStack> overrides, NBTTagCompound options)
        throws IOException {
        checkStep(1, 1);
        if (nativeTarget != null) {
            NBTTagCompound profile = new NBTTagCompound();
            profile.setInteger("tier", options.getInteger("hatchTier"));
            profile.setBoolean("downgrade", options.getBoolean("hatchDowngrade"));
            Map<HologramBlueprintResources.ExactKey, NBTTagCompound> entries = new LinkedHashMap<>();
            for (int index = 0; index < targetCells.size(); index++) {
                ItemStack item = overrides.containsKey(index) ? overrides.get(index)
                    : targetCells.get(index)
                        .targetStack();
                if (HologramCellPolicy.hatchTier(item) == Integer.MAX_VALUE) continue;
                var key = HologramValues.stackKey(item);
                NBTTagCompound row = entries.get(key);
                if (row == null) {
                    row = new NBTTagCompound();
                    row.setString("role", "native_hatch");
                    row.setString(
                        "item",
                        String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(item.getItem())));
                    row.setInteger("damage", item.getItemDamage());
                    entries.put(key, row);
                }
                row.setInteger("count", row.getInteger("count") + 1);
            }
            NBTTagList rows = new NBTTagList();
            entries.values()
                .forEach(rows::appendTag);
            profile.setTag("entries", rows);
            return profile;
        }
        if (!targetPublished || presetIndex == null) throw new IOException("当前仓室尚未就绪。");
        return presetIndex.captureProfile(
            overrides,
            options,
            index -> targetCells.get(index)
                .actualStack());
    }

    /** Resolve the requested position in the captured native declaration; no candidate NBT is made here. */
    public NativeOffers selectedOffers(int index) throws IOException {
        checkStep(1, 1);
        if (!targetPublished || index < 0 || index >= targetCells.size()) return null;
        HologramControllerTarget.Cell target = targetCells.get(index);
        if (target.anchor()) return null;
        if (nativeTarget != null) {
            HologramCapture.Cell cell = nativeTargetCells.get(index);
            HologramNativeTargetPlan.Cell material = nativeTargetCell(target.dx(), target.dy(), target.dz());
            cell.candidates = material == null ? java.util.Collections.emptyList() : nativeTarget.offers(material);
            cell.candidateComplete = false;
            cell.candidateBoundary = "原生自动建造候选；配置后将重新生成完整目标";
            return nativeOffers(
                cell,
                target.targetStack(),
                nativeTarget.facingMask(target.dx(), target.dy(), target.dz(), target.targetStack()));
        }
        HologramCapture.Cell cell = capture != null ? capture.cells.get(index)
            : nativePreview.candidateCell(
                target.dx() + detachedTile.xCoord,
                target.dy() + detachedTile.yCoord,
                target.dz() + detachedTile.zCoord,
                target.role());
        if (capture != null && cell.candidates == null) {
            if (sourceCandidates == null) {
                HologramNativePreviewItems items = new HologramNativePreviewItems();
                sourceCandidates = new HologramNativeHatchChooser(items::catalog, () -> (element, x, y, z, signal) -> {
                    @SuppressWarnings("rawtypes")
                    var nativeElement = (com.gtnewhorizon.structurelib.structure.IStructureElement) element;
                    return nativeElement.getBlocksToPlace(
                        detachedContext,
                        detachedTile.getWorldObj(),
                        x,
                        y,
                        z,
                        signal,
                        com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment.fromLegacy(
                            items,
                            new HologramProjectorSimulation.Actor(detachedTile.getWorldObj()),
                            message -> {}));
                });
            }
            try (var observing = HologramDeclarationAdapters.observe(presetDeclarations)) {
                cell.candidates = sourceCandidates.offers(cell.element, cell.x, cell.y, cell.z, cell.elementTrigger);
                // Individual declaration positives do not assert an exhaustive native catalog or global quota.
                cell.candidateComplete = false;
                cell.candidateBoundary = "仅列出本格已证明的声明候选";
            }
        }
        return nativeOffers(cell, target.targetStack());
    }

    private HologramNativeTargetPlan.Cell nativeTargetCell(int dx, int dy, int dz) {
        if (nativeTarget != null) for (var cell : nativeTarget.cells())
            if (cell.dx() == dx && cell.dy() == dy && cell.dz() == dz) return cell;
        return null;
    }

    private static final class NativeClassOffers {

        private final List<ItemStack> stacks;
        private final List<String> roles;
        private final Map<HologramBlueprintResources.ExactKey, Integer> selectedByMaterial = new HashMap<>();
        private final int[] tiers;
        private final byte[] facingMasks;

        NativeClassOffers(List<ItemStack> stacks, List<String> roles, int[] tiers, byte[] facingMasks) {
            List<ItemStack> frozen = new ArrayList<>(stacks.size());
            for (ItemStack stack : stacks) {
                selectedByMaterial.put(new HologramBlueprintResources.ExactKey(stack, null), frozen.size());
                frozen.add(stack.copy());
            }
            this.stacks = new java.util.AbstractList<>() {

                @Override
                public ItemStack get(int index) {
                    return frozen.get(index)
                        .copy();
                }

                @Override
                public int size() {
                    return frozen.size();
                }
            };
            this.roles = java.util.Collections.unmodifiableList(new ArrayList<>(roles));
            this.tiers = tiers.clone();
            this.facingMasks = facingMasks.clone();
        }
    }

    /** The chosen material and any actual-facing proof belong to this use of the declared category. */
    public static final class NativeOffers {

        private final NativeClassOffers options;
        private final String role, boundary;
        private final int chosen, facingMask, nativeCount;
        private final boolean complete;

        NativeOffers(NativeClassOffers options, String role, int chosen, int facingMask, boolean complete,
            String boundary, int nativeCount) {
            this.options = options;
            this.role = role;
            this.chosen = chosen;
            this.facingMask = facingMask;
            this.complete = complete;
            this.boundary = boundary;
            this.nativeCount = nativeCount;
        }

        /** Verify one remembered hatch against the freshly evaluated declaration and its actual facing mask. */
        public boolean allowsRememberedHatch(int metaId, int direction) {
            ItemStack material = new ItemStack(GregTechAPI.sBlockMachines, 1, metaId);
            Integer choice = options.selectedByMaterial.get(new HologramBlueprintResources.ExactKey(material, null));
            return choice != null && (direction == -1
                || direction >= 0 && direction < 6 && (options.facingMasks[choice] & (1 << direction)) != 0);
        }

        public List<ItemStack> candidates() {
            return options.stacks;
        }

        public List<String> roles() {
            return options.roles;
        }

        public int[] tiers() {
            return options.tiers.clone();
        }

        public int[] hatchFacings() {
            return HologramPreviewHatchFacings.directions(facingMask);
        }

        public String role() {
            return role;
        }

        public int chosen() {
            return chosen;
        }

        public boolean complete() {
            return complete;
        }

        public String boundary() {
            return boundary;
        }

        /** Serialize only at an actual patch/export/legacy snapshot boundary. */
        public void writeTo(NBTTagCompound row) {
            NBTTagList choices = new NBTTagList(), labels = new NBTTagList();
            for (ItemStack stack : options.stacks) choices.appendTag(stack.writeToNBT(new NBTTagCompound()));
            for (String label : options.roles) labels.appendTag(new NBTTagString(label));
            row.setTag("candidates", choices);
            row.setTag("candidateRoles", labels);
            row.setIntArray("candidateTiers", tiers());
            for (byte mask : options.facingMasks) if (mask != 0) {
                row.setByteArray("candidateFacingMasks", options.facingMasks.clone());
                break;
            }
            if (facingMask != 0) {
                row.setIntArray("hatchFacings", hatchFacings());
                if (!row.hasKey("hatchFacing", 3)) row.setInteger("hatchFacing", -1);
            }
            row.setString("role", role);
            row.setInteger("chosenChoice", chosen);
            row.setBoolean("candidateComplete", complete);
            if (nativeCount >= 0) row.setInteger("nativeCandidateCount", nativeCount);
            row.setString("candidateBoundary", boundary);
        }
    }

    private void checkStep(int maxRows, long maxNanos) throws IOException {
        if (Thread.currentThread() != owner || maxRows <= 0 || maxNanos <= 0)
            throw new IllegalArgumentException("Invalid local controller capture step");
        if (complete) throw new IllegalStateException("Local controller model already published");
        if (!supports(tile) || liveContext == null || facing == null)
            throw new IOException("LOCAL_DECLARATION_UNAVAILABLE");
        String boundary = nativeWorkflow ? null : StructureSources.sourceFailure(liveContext.getClass());
        if (boundary != null) throw new IOException(boundary);
        if (!alreadyDetached
            && !(targetPublished ? controllerContextMatches(world, dimension) : matches(world, dimension)))
            throw new IOException("LOCAL_CONTROLLER_CHANGED");
        if (capture != null && declared != null) {
            String reason = capture.sourceValidityFailure();
            if (reason != null) throw new IOException(reason);
        }
    }

    private NBTTagCompound finish(NBTTagList resultRows) {
        NBTTagCompound state = header();
        if (!alreadyDetached) site.publish(state);
        publishInstalledSite(state);
        state.setBoolean("projectionDataComplete", true);
        state.setTag("cells", resultRows);
        if (nativePreview != null) {
            state.setTag("structureDescription", nativePreview.description());
            report = nativePreview.channels();
        }
        long capabilityStart = HologramRuntimeMetrics.start();
        try {
            state.setTag("capabilities", publishedCapabilities == null ? capabilities(report) : publishedCapabilities);
        } finally {
            HologramRuntimeMetrics
                .finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_CAPABILITIES, capabilityStart);
        }
        if (alreadyDetached && capture != null
            && capture.sourceResolution.status == StructureSources.Status.READY
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
        if (nativeTarget != null) nativeTarget.close();
        nativeTarget = null;
        nativeTargetCells = null;
        if (sourceCandidates != null) sourceCandidates.clear();
        sourceCandidates = null;
        report = null;
        publishedCapabilities = null;
        rows = null;
        targetCells = null;
        targetMaterials = null;
        installedShape = null;
        installedCells = null;
        installedOverlap = null;
        previewAnchor = null;
        facingMasks.clear();
        presetIndex = null;
    }

    private boolean hasNext() {
        return declared != null ? declared.hasNext() : nativePlacements.hasNext();
    }

    private boolean prepareGeometry(long deadline) throws IOException {
        if (nativeWorkflow) {
            if (nativeTarget == null)
                nativeTarget = HologramNativeTargetPlan.begin(controllerId, facing, trigger, settings);
            if (!nativeTarget.advance(deadline)) return false;
            detachedTile = nativeTarget.controller();
            detachedContext = nativeTarget.context();
            report = nativeTarget.channels();
            nativeTargetCells = new java.util.AbstractList<>() {

                @Override
                public HologramCapture.Cell get(int index) {
                    var value = nativeTarget.cells()
                        .get(index);
                    HologramCapture.Cell cell = new HologramCapture.Cell(
                        value.element(),
                        detachedTile.xCoord + value.dx(),
                        detachedTile.yCoord + value.dy(),
                        detachedTile.zCoord + value.dz(),
                        value.trigger(),
                        true);
                    cell.block = value.block();
                    cell.meta = value.metadata();
                    cell.previewStack = value.material();
                    cell.previewFacing = value.facing();
                    cell.targetDisplay = value.display();
                    cell.role = HologramCellPolicy.hatchTier(value.material()) == Integer.MAX_VALUE ? "block" : "hatch";
                    cell.renderKind = value.material() != null && value.material()
                        .getItem() instanceof ItemMachines ? "item" : "block";
                    return cell;
                }

                @Override
                public int size() {
                    return nativeTarget.cells()
                        .size();
                }
            };
            declared = nativeTargetCells.iterator();
            return true;
        }
        if (nativePreview == null && liveContext instanceof MTEEnhancedMultiBlockBase<?>
            && StructureSources.hasSource(liveContext.getClass())) {
            if (detached == null) detached = alreadyDetached ? (MTEEnhancedMultiBlockBase<?>) liveContext
                : HologramDetachedController.create(controllerId, facing);
            detachedTile = (TileEntity) detached.getBaseMetaTileEntity();
            detachedContext = detached;
            capture = HologramCapture
                .collectLocalProjection(detached, trigger, facing, capture, Math.max(1, deadline - System.nanoTime()));
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
            IConstructable constructable = HologramStructureDiscovery
                .localConstructable(detachedTile, detachedContext, facing);
            if (constructable == null) throw new IOException("LOCAL_DECLARATION_UNAVAILABLE");
            boolean structureCompat = controllerId < 0 && IMultiblockInfoContainer.contains(detachedTile.getClass());
            nativePreview = new HologramNativeStructurePreview(
                detachedTile,
                detachedContext,
                constructable,
                structureCompat,
                previewTrigger(null, settings),
                this::createNativeController,
                facing,
                true);
        }
        if (!nativePreview.advance(deadline)) return false;
        nativePlacements = nativePreview.placements();
        return true;
    }

    private TileEntity createNativeController() throws IOException {
        HologramResourceWorld memory = HologramResourceWorld.declarations(controllerBlock, controllerMeta);
        TileEntity copy = controllerBlock.createTileEntity(memory, controllerMeta);
        if (copy == null) try {
            copy = tile.getClass()
                .getDeclaredConstructor()
                .newInstance();
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
            if (context == null || context == liveContext
                || context.getClass() != liveContext.getClass()
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

    public static int liveOrientation(TileEntity tile, int side) {
        if (tile == null || tile.isInvalid()) return -1;
        ExtendedFacing pose = nativeFacing(tile, HologramStructureDiscovery.context(tile), side);
        return pose == null ? -1 : pose.ordinal();
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

    private static ExtendedFacing nativeFacing(TileEntity tile, Object context, int side) {
        IAlignment alignment = alignment(tile, context);
        return alignment == null ? ExtendedFacing.of(ForgeDirection.getOrientation(side))
            : alignment.getExtendedFacing();
    }

    private static IAlignment alignment(TileEntity tile, Object context) {
        if (context instanceof IAlignment aligned) return aligned;
        if (tile instanceof IAlignment aligned) return aligned;
        if (tile instanceof IAlignmentProvider provider) return provider.getAlignment();
        return context instanceof IAlignmentProvider provider ? provider.getAlignment() : null;
    }

    private HologramCapture.Cell anchor() {
        HologramCapture.Cell cell = new HologramCapture.Cell(
            null,
            detachedTile.xCoord,
            detachedTile.yCoord,
            detachedTile.zCoord,
            null,
            true);
        cell.anchor = true;
        cell.block = controllerBlock;
        cell.meta = controllerMeta;
        cell.role = "controller";
        cell.actualFacing = facing.getDirection()
            .ordinal();
        cell.actualStack = controllerId >= 0 ? new ItemStack(GregTechAPI.sBlockMachines, 1, controllerId)
            : HologramBlockIdentity.materialStack(controllerBlock, controllerMeta);
        cell.actualDisplay = HologramDisplaySnapshot.projection(detachedTile);
        return cell;
    }

    private NBTTagList capabilities(HologramChannelTrace.Report report) {
        HologramCapabilities.Result capabilities = (nativeTarget != null
            ? HologramCapabilities.describeNative(detachedContext, trigger, report, nativeTarget.definition())
            : HologramCapabilities.describe(detachedContext, trigger, report)
                .withNativeDefinition(
                    nativePreview != null ? nativePreview.definition() : capture == null ? null : capture.definition))
                        .withSavedChannels(settings.getCompoundTag("channels"));
        return capabilities.toNBT();
    }

    private NBTTagList shellCapabilities() {
        return HologramCapabilities.describe(null, trigger, null)
            .withSavedChannels(settings.getCompoundTag("channels"))
            .toNBT();
    }

    private NBTTagCompound header() {
        return header(true);
    }

    private NBTTagCompound header(boolean publishTarget) {
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
        boolean ready = publishTarget && nativeTarget != null
            && nativeTarget.complete()
            && nativeTarget.targetDigest() != null
            && !nativeTarget.targetDigest()
                .isEmpty();
        state.setBoolean("nativeTargetReady", ready);
        state.setBoolean(
            "nativeExecutionSupported",
            nativeWorkflow && ready
                && (!settings.hasKey("siteKnown") || settings.getBoolean("siteKnown")
                    || settings.getBoolean("siteExplicitTarget"))
                && nativeTarget.cells()
                    .stream()
                    .allMatch(
                        cell -> cell.requiredAir() || cell.material() != null && cell.material()
                            .getItem() != null));
        if (ready) {
            state.setString("nativeTargetDigest", nativeTarget.targetDigest());
            state.setBoolean(
                "siteFormed",
                liveContext instanceof gregtech.api.metatileentity.implementations.MTEMultiBlockBase machine
                    && machine.mMachine);
            int present = 0, unknown = 0, hatches = 0, missing = 0, different = 0, matching = 0;
            if (targetCells != null) for (var cell : targetCells) if (!cell.anchor()) {
                if (cell.localObservationMissing()) unknown++;
                else if (cell.actualBlock() != null && cell.actualBlock() != Blocks.air) {
                    present++;
                    if (HologramCellPolicy.hatchTier(cell.actualStack()) != Integer.MAX_VALUE) hatches++;
                }
                if ("missing".equals(cell.status())) missing++;
                if ("occupied".equals(cell.status())) different++;
                if ("satisfied".equals(cell.status())) matching++;
            }
            state.setInteger("siteNonAir", present);
            state.setInteger("siteUnknown", unknown);
            state.setInteger("siteOccupied", present);
            state.setInteger("siteHatches", hatches);
            state.setInteger("siteMissing", missing);
            state.setInteger("siteDifferent", different);
            state.setInteger("siteMatching", matching);
            state.setInteger("siteMatchingSolid", Math.max(0, present - different));
        }
        for (Object raw : settings.func_150296_c()) {
            String key = (String) raw;
            if (key.startsWith("site") && !state.hasKey(key)) state.setTag(
                key,
                settings.getTag(key)
                    .copy());
        }
        if (settings.hasKey("siteUnknown", 3)) state
            .setInteger("siteUnknown", Math.max(state.getInteger("siteUnknown"), settings.getInteger("siteUnknown")));
        if (settings.getBoolean("siteKnown")) state.setTag(
            "siteChannels",
            settings.getCompoundTag("channels")
                .copy());
        state.setInteger("dimension", dimension);
        state.setInteger("x", x);
        state.setInteger("y", y);
        state.setInteger("z", z);
        state.setInteger("side", side);
        state.setInteger("selected", -1);
        // Voltage labels come from the client GT registry, not the structure's material channels.
        state.setInteger("hatchTierMax", gregtech.api.enums.GTValues.VN.length - 1);
        for (String key : new String[] { "main", "mode", "scope", "layer", "materialPriority" })
            state.setInteger(key, settings.getInteger(key));
        for (String key : new String[] { "mainExplicit", "noHatches", "materialMain", "materialContainers",
            "materialMe" }) if (settings.hasKey(key)) state.setBoolean(key, settings.getBoolean(key));
        state.setTag(
            "channels",
            settings.getCompoundTag("channels")
                .copy());
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
            if (target.anchor()) previewAnchor = target;
            int index = targetCells.size();
            targetCells.add(target);
            targetMaterials.add(target, x, y, z);
            if (presetIndex != null) presetIndex.record(cell.element, target, index);
            if (installedOverlap != null && HologramNativePreviewWorld.packable(target.x(), target.y(), target.z()))
                installedOverlap.put(CoordinatePacker.pack(target.x(), target.y(), target.z()), target);
            return;
        }
        NBTTagCompound row = target.tag();
        if (!cell.anchor) writeCandidates(row, cell, target.targetStack());
        rows.appendTag(row);
    }

    private void publishInstalledSite(NBTTagCompound state) {
        if (installedCells == null) return;
        state.setInteger("siteNativeGroups", installedShape.groups());
        state.setInteger("siteNativeOccupied", installedNonAir);
        state.setInteger("siteNativeUnknown", installedUnknown);
        state.setInteger("siteNativeHatches", installedHatches);
    }

    private HologramControllerTarget.Cell target(HologramCapture.Cell cell) {
        return target(cell, false);
    }

    private HologramControllerTarget.Cell target(HologramCapture.Cell cell, boolean installedOnly) {
        int dx = cell.x - detachedTile.xCoord, dy = cell.y - detachedTile.yCoord, dz = cell.z - detachedTile.zCoord;
        int bx = Math.addExact(x, dx), by = Math.addExact(y, dy), bz = Math.addExact(z, dz);
        Block block = cell.block != null || cell.renderHint == null ? cell.block : cell.renderHint.block;
        int wantMeta = cell.block != null || cell.renderHint == null ? cell.meta : cell.renderHint.metadata;
        String registeredTarget = block == null ? null : Block.blockRegistry.getNameForObject(block);
        String wantId = block == null ? "" : String.valueOf(registeredTarget);
        Block hint = StructureLibAPI.getBlockHint();
        ItemStack target = cell.anchor ? cell.actualStack
            : cell.previewStack != null ? cell.previewStack
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
        boolean hatch = false;
        boolean gtIdentityMissing = false;
        if (!alreadyDetached && world.isRemote) {
            long observation = HologramRuntimeMetrics.start();
            try {
                observationMissing = !isCellLoaded(bx, by, bz);
                if (!observationMissing) {
                    actualBlock = world.getBlock(bx, by, bz);
                    meta = world.getBlockMetadata(bx, by, bz);
                    id = Block.blockRegistry.getNameForObject(actualBlock);
                    TileEntity current = actualBlock.hasTileEntity(meta) ? world.getTileEntity(bx, by, bz) : null;
                    if (current instanceof IGregTechTileEntity gt && !current.isInvalid()
                        && current.getWorldObj() == world
                        && current.xCoord == bx
                        && current.yCoord == by
                        && current.zCoord == bz
                        && gt.getMetaTileEntity() != null) {
                        hatch = gt.getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEHatch;
                        actual = HologramGtNativeItemState.sample(actualBlock, gt);
                        actualFacing = gt.getFrontFacing()
                            .ordinal();
                        actualDisplay = HologramDisplaySnapshot.projection(current);
                        if (gt
                            .getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEHatch installed)
                            actualDisplay.setInteger("siteHatchTier", installed.mTier);
                    } else {
                        gtIdentityMissing = actualBlock == GregTechAPI.sBlockMachines;
                        actual = gtIdentityMissing ? null : HologramBlockIdentity.materialStack(actualBlock, meta);
                    }
                }
            } finally {
                HologramRuntimeMetrics.finish(HologramRuntimeMetrics.Activity.LOCAL_CONTROLLER_WORLD, observation);
            }
        }
        String status = cell.anchor ? "protected" : cell.unknown ? "unsupported" : "missing";
        if (nativeTarget != null && !cell.anchor) {
            boolean same = !observationMissing && !gtIdentityMissing && actualBlock == block && meta == wantMeta;
            if (same && target != null && target.getItem() instanceof ItemMachines)
                same = actual != null && actual.getItemDamage() == target.getItemDamage()
                    && actualFacing == cell.previewFacing;
            status = same ? "satisfied"
                : observationMissing || gtIdentityMissing ? "unknown"
                    : actualBlock == Blocks.air ? "missing" : "occupied";
        } else if (!installedOnly && !alreadyDetached && world.isRemote && !cell.anchor) {
            status = site.observe(
                cell.element,
                observationMissing || gtIdentityMissing,
                actualBlock,
                meta,
                actual,
                actualFacing,
                block,
                wantMeta,
                target,
                cell.previewFacing,
                cell.unknown || block == hint,
                hatch);
        }
        if (installedOnly) {
            wantId = "minecraft:air";
            block = Blocks.air;
            wantMeta = 0;
            registeredTarget = "minecraft:air";
            status = "siteOnly";
        }
        int[] hintTint = null;
        if (cell.hintTint != null) {
            hintTint = new int[cell.hintTint.length];
            for (int i = 0; i < hintTint.length; i++) hintTint[i] = cell.hintTint[i];
        }
        return new HologramControllerTarget.Cell(
            bx,
            by,
            bz,
            dx,
            dy,
            dz,
            id,
            wantId,
            meta,
            wantMeta,
            id == null || id.isEmpty() || "null".equals(id) ? null : actualBlock,
            registeredTarget == null ? null : block,
            actual,
            target,
            actualDisplay,
            targetDisplay,
            cell.anchor,
            cell.iconOnly,
            cell.unknown || block == hint,
            status,
            cell.role,
            cell.family,
            target != null && target.getItem() instanceof ItemMachines ? "item" : cell.renderKind,
            cell.anchor ? "" : cell.prompt,
            actualFacing,
            cell.anchor ? cell.actualFacing : cell.previewFacing,
            cell.hintIcons == null ? null : cell.hintIcons.clone(),
            hintTint,
            cell.hintIndex,
            observationMissing || gtIdentityMissing,
            cell.nativeSourceWater ? HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER
                : HologramControllerTarget.TargetKind.MATERIAL);
    }

    private void writeCandidates(NBTTagCompound row, HologramCapture.Cell cell, ItemStack material) {
        nativeOffers(cell, material).writeTo(row);
    }

    private NativeOffers nativeOffers(HologramCapture.Cell cell, ItemStack material) {
        return nativeOffers(cell, material, -1);
    }

    /** Six-face proof is requested only by selectedOffers, for its current model at this position. */
    private NativeOffers nativeOffers(HologramCapture.Cell cell, ItemStack material, int selectedFacingMask) {
        List<ItemStack> candidates = cell.candidates == null ? java.util.Collections.emptyList() : cell.candidates;
        int nativeFacingMask = nativeTarget == null ? -1
            : selectedFacingMask >= 0 ? selectedFacingMask : facingMask(cell, material);
        NativeClassOffers options;
        {
            List<String> roles = new ArrayList<>(candidates.size());
            int[] tiers = new int[candidates.size()];
            byte[] masks = new byte[candidates.size()];
            for (int index = 0; index < candidates.size(); index++) {
                ItemStack candidate = candidates.get(index);
                roles.add(HologramCellPolicy.candidateRole(cell, candidate));
                int tier = HologramCellPolicy.hatchTier(candidate);
                tiers[index] = tier == Integer.MAX_VALUE ? -1 : tier;
                if (tier != Integer.MAX_VALUE) masks[index] = (byte) (nativeFacingMask >= 0
                    ? (HologramValues.sameMaterial(material, candidate) ? nativeFacingMask : 0)
                    : facingMask(cell, candidate));
            }
            options = new NativeClassOffers(candidates, roles, tiers, masks);
        }
        int chosen = material == null ? -1
            : options.selectedByMaterial.getOrDefault(HologramBlueprintResources.ExactKey.borrowed(material), -1);
        int mask = chosen < 0 ? (HologramCellPolicy.hatchTier(material) == Integer.MAX_VALUE ? 0
            : nativeFacingMask >= 0 ? nativeFacingMask : facingMask(cell, material)) : options.facingMasks[chosen];
        return new NativeOffers(
            options,
            cell.role,
            chosen,
            mask,
            cell.candidateComplete,
            cell.candidateBoundary,
            cell.nativeCandidateCount);
    }

    private int facingMask(HologramCapture.Cell cell, ItemStack sample) {
        if (nativeTarget != null) {
            var target = nativeTargetCell(
                cell.x - detachedTile.xCoord,
                cell.y - detachedTile.yCoord,
                cell.z - detachedTile.zCoord);
            if (target != null && target.facing() >= 0 && HologramValues.sameMaterial(target.material(), sample))
                return 1 << target.facing();
            return 0; // A different model gets its facing from the next complete native replay.
        }
        if (cell.element == null) return 0;
        Map<HologramBlueprintResources.ExactKey, Integer> masks = facingMasks
            .computeIfAbsent(cell.element, ignored -> new HashMap<>());
        Integer present = masks.get(HologramBlueprintResources.ExactKey.borrowed(sample));
        if (present != null) return present;
        int mask = HologramPreviewHatchFacings
            .mask(HologramCellPolicy.previewHatchFacings(cell, sample, detachedContext, facing));
        masks.put(new HologramBlueprintResources.ExactKey(sample, null), mask);
        return mask;
    }

    private boolean isCellLoaded(int bx, int by, int bz) {
        int cx = bx >> 4, cz = bz >> 4;
        if (by < 0 || by >= world.getHeight()
            || !world.getChunkProvider()
                .chunkExists(cx, cz))
            return false;
        Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
        return chunk != null && !(chunk instanceof EmptyChunk);
    }
}
