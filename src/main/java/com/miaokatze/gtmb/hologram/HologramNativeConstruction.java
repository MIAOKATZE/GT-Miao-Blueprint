// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.IAlignmentProvider;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.gtnewhorizon.structurelib.structure.ICustomBlockSetting;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** One prepared target and one paid native placement, shared by structures and saved blueprints. */
final class HologramNativeConstruction {

    /** Only public method ownership is cached; inherited vanilla use keeps native placeBlockAt overrides. */
    private static final ClassValue<Boolean> VANILLA_ITEM_USE = new ClassValue<Boolean>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            boolean found = false;
            for (String name : new String[] { "onItemUse", "func_77648_a" }) try {
                var method = type.getMethod(
                    name,
                    ItemStack.class,
                    EntityPlayer.class,
                    World.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    float.class,
                    float.class,
                    float.class);
                found = true;
                if (method.getDeclaringClass() != ItemBlock.class) return false;
            } catch (NoSuchMethodException absentAlias) {
                // Exactly one public MCP/SRG alias normally exists in a given runtime.
            }
            return found;
        }
    };

    static boolean inheritsVanillaItemUse(Class<? extends ItemBlock> type) {
        return VANILLA_ITEM_USE.get(type);
    }

    enum Outcome {
        PLACED,
        SATISFIED,
        OBSTRUCTED,
        FAILED
    }

    /** Source/retention analysis is descriptor-scoped. No coordinate or mutable live stock is cached here. */
    static final class Prepared {

        final Block block;
        final int metadata, facing;
        final ExtendedFacing alignment;
        final ItemStack sample;
        final HologramEnergyPayload.Prepared buffers;
        final HologramBlueprintResources.Plan resources;
        final List<ItemStack> covers;
        final HologramDecorMaterials.Descriptor decoration;
        final boolean saved, nativeTile, retain, cleared, freeShell, fuzzy;
        final NBTTagCompound appearance;
        final NBTTagCompound nativeItem;
        final HologramGtNativeItemState.Baseline nativeState;
        final Map<HologramBlueprintResources.ExactKey, Long> assets = new HashMap<>();
        ItemStack sealTemplate;

        private Prepared(Block block, int metadata, int facing, ItemStack sample,
            HologramEnergyPayload.Prepared buffers, HologramBlueprintResources.Plan resources, List<ItemStack> covers,
            HologramDecorMaterials.Descriptor decoration, boolean saved, boolean nativeTile, boolean retain,
            boolean cleared, boolean freeShell, boolean fuzzy, NBTTagCompound appearance, ExtendedFacing alignment,
            HologramGtNativeItemState.Baseline baseline) throws IOException {
            this.block = block;
            this.metadata = metadata;
            this.facing = facing;
            this.alignment = alignment;
            this.sample = sample.copy();
            this.sample.stackSize = 1;
            nativeState = HologramRecovery.hasSeal(this.sample) ? null
                : baseline == null ? HologramGtNativeItemState.baseline(this.sample) : baseline;
            nativeItem = nativeState == null ? null : nativeState.item;
            this.buffers = buffers;
            this.resources = resources;
            this.covers = covers;
            this.decoration = decoration;
            this.saved = saved;
            this.nativeTile = nativeTile;
            this.retain = retain;
            this.cleared = cleared;
            this.freeShell = freeShell;
            this.fuzzy = fuzzy;
            this.appearance = appearance;
            if (saved) {
                for (var item : resources.items)
                    HologramValues.mergeStack(assets, item, (long) item.stackSize, Long::sum);
                for (var cover : covers) HologramValues.mergeStack(assets, cover, 1L, Long::sum);
                if (decoration == null && !freeShell
                    && (!fuzzy && sample.hasTagCompound() || HologramMaterialReservations.coordinate(sample)))
                    HologramValues.mergeStack(assets, HologramEssentia.shellMaterial(sample), 1L, Long::sum);
            }
        }
    }

    private HologramNativeConstruction() {}

    /** Only distinct immutable material/state variants are prepared, rather than a third full cell index. */
    static final class ProjectionCache {

        private final Map<ProjectionKey, Prepared> variants = new HashMap<>();

        Prepared get(HologramNativeTargetPlan.Cell cell) throws IOException {
            var appearance = cell.tileData();
            ExtendedFacing alignment = geometry(cell.material(), cell.facing(), appearance);
            int texture = appearance != null && appearance.hasKey("texturePage", 3)
                && appearance.hasKey("textureIndex", 3)
                    ? (appearance.getInteger("texturePage") << 7) | appearance.getInteger("textureIndex")
                    : -1;
            var key = new ProjectionKey(
                cell.block(),
                cell.metadata(),
                cell.facing(),
                HologramBlueprintResources.ExactKey.borrowed(cell.material()),
                texture,
                alignment);
            var prepared = variants.get(key);
            if (prepared == null) {
                prepared = projection(cell, alignment);
                variants.put(
                    new ProjectionKey(
                        cell.block(),
                        cell.metadata(),
                        cell.facing(),
                        new HologramBlueprintResources.ExactKey(cell.material(), null),
                        texture,
                        alignment),
                    prepared);
            }
            return prepared;
        }
    }

    @com.github.bsideup.jabel.Desugar
    private record ProjectionKey(Block block, int metadata, int facing, HologramBlueprintResources.ExactKey material,
        int texture, ExtendedFacing alignment) {}

    static Prepared projection(HologramNativeTargetPlan.Cell target) throws IOException {
        return projection(target, geometry(target.material(), target.facing(), target.tileData()));
    }

    private static Prepared projection(HologramNativeTargetPlan.Cell target, ExtendedFacing alignment)
        throws IOException {
        if (target.material() == null) throw new IOException("原生目标没有施工材料。");
        return new Prepared(
            target.block(),
            target.metadata(),
            target.facing(),
            target.material(),
            HologramEnergyPayload.prepare(null, null),
            new HologramBlueprintResources.Plan(),
            Collections.emptyList(),
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            target.tileData(),
            alignment,
            null);
    }

    static Prepared saved(HologramSession session, Block block, int metadata, ItemStack sample, int facing,
        String tileEncoded, String itemEncoded) throws IOException {
        return saved(
            session,
            block,
            metadata,
            sample,
            facing,
            tileEncoded,
            itemEncoded,
            geometry(sample, facing, HologramExecutionEligibility.payload(tileEncoded)));
    }

    static Prepared saved(HologramSession session, Block block, int metadata, ItemStack sample, int facing,
        String tileEncoded, String itemEncoded, ExtendedFacing alignment) throws IOException {
        var preset = session.virtualPreset;
        boolean sealed = HologramRecovery.hasSeal(sample);
        HologramGtNativeItemState.Baseline baseline = sealed ? null : HologramGtNativeItemState.baseline(sample);
        boolean retain = preset.retainNbt;
        NBTTagCompound tile = sealed ? null
            : HologramBlueprintNbt.forPlacement(
                block,
                metadata,
                tileEncoded,
                session.player.getUniqueID(),
                session.player.getCommandSenderName(),
                0,
                0,
                0,
                retain,
                HologramSessionPolicy.placementRetainCovers(session),
                HologramSessionPolicy.placementKeys(session));
        NBTTagCompound item = sealed ? null
            : HologramBlueprintNbt.itemForPlacement(
                itemEncoded,
                session.player.getUniqueID(),
                session.player.getCommandSenderName(),
                retain,
                HologramSessionPolicy.placementRetainCovers(session),
                HologramSessionPolicy.placementKeys(session));
        if (sample.getItem() instanceof ItemMachines) {
            // Alignment belongs to target geometry even when the user deselects all retained NBT.
            // It is applied through IAlignment, never passed back as an unchecked configuration subtree.
            if (tile != null) {
                tile.removeTag("eRotation");
                tile.removeTag("eFlip");
            }
            tile = HologramExecutionEligibility.gtState(tile, sample, true, true, baseline);
            item = HologramExecutionEligibility.gtState(item, sample, false, true, baseline);
        }
        HologramExecutionEligibility.validateSaved(
            block,
            metadata,
            sample,
            facing,
            false,
            tile,
            item,
            HologramBlueprintProcessing.quantumCleared(preset),
            baseline);
        HologramCellPolicy.validateGtPayloadIdentity(sample, tile);
        if (item != null && (item.hasKey("mID") || item.hasKey("id")))
            HologramCellPolicy.validateGtPayloadIdentity(sample, item);
        tile = HologramGtMeShadow.project(tile);
        var buffers = HologramEnergyPayload.prepare(block, metadata, tile, item);
        tile = buffers.tileEmpty;
        boolean nativeTile = tile != null && HologramNativePlacement.supported(block, tile);
        var adapter = nativeTile ? HologramNativePlacement.adapter(block) : null;
        HologramDecorMaterials.Descriptor decoration = null;
        if (!sealed && HologramDecorMaterials.supports(block, tile)) {
            if (!retain) throw new IOException("装饰形状需要保留已登记的设备 NBT。");
            decoration = HologramDecorMaterials.describe(block, metadata, tile);
        }
        HologramBlueprintResources.Plan resources;
        if (sealed) resources = new HologramBlueprintResources.Plan();
        else if (HologramAeBlueprint.supports(block, tile))
            resources = HologramAeBlueprint.resourcePlan(block, metadata, tile, sample);
        else {
            List<ItemStack> components = adapter == null ? Collections.emptyList()
                : HologramNativePlacement.materials(block, metadata, tile);
            resources = HologramBlueprintResources
                .plan(tile, components, adapter != null && adapter.includesInventoryDrops(block))
                .merge(HologramBlueprintResources.plan(buffers.itemEmpty));
            if (adapter != null && decoration == null) {
                if (!retain) throw new IOException("原生组合方块需要保留设备 NBT。");
                if (adapter.includesInventoryDrops(block)) resources.removeItemsIncludedByNativeDrops(components);
                resources.addNativeItems(HologramNativePlacement.additionalMaterials(block, components, sample));
            }
        }
        resources.addNativeItems(
            HologramGtswnPlacementMaterials
                .extraMaterials(block, sealed ? HologramBlueprintNbt.decode(tileEncoded) : tile));
        List<ItemStack> covers = sealed || !HologramSessionPolicy.placementRetainCovers(session)
            ? Collections.emptyList()
            : HologramBlueprintCovers.materials(tile);
        boolean freeShell = !sealed && HologramGtswnPlacementMaterials.freeShell(block, sample);
        var result = new Prepared(
            block,
            metadata,
            facing,
            sample,
            buffers,
            resources,
            covers,
            decoration,
            true,
            nativeTile,
            retain,
            HologramBlueprintProcessing.quantumCleared(preset),
            freeShell,
            HologramMaterialAccess.fuzzyBlueprintShells(session, sample),
            tile,
            alignment,
            baseline);
        if (sealed) result.sealTemplate = HologramCellPolicy
            .sealedPlacementTarget(session, sample, HologramBlueprintNbt.decode(tileEncoded), 0, 0, 0);
        return result;
    }

    /** Read once from the rotated raw descriptor, before retention removes the native geometry pair. */
    static ExtendedFacing geometry(ItemStack sample, int facing, NBTTagCompound raw) throws IOException {
        if (sample == null || !(sample.getItem() instanceof ItemMachines)
            || raw == null
            || !raw.hasKey("eRotation") && !raw.hasKey("eFlip")) return null;
        int rotation = geometryIndex(raw, "eRotation", Rotation.VALUES.length);
        int flip = geometryIndex(raw, "eFlip", Flip.VALUES.length);
        int front = facing;
        if (raw.hasKey("mFacing")) {
            if (!raw.hasKey("mFacing", 99) || raw.getTag("mFacing")
                .getId() > 4) throw new IOException("GT alignment front has an invalid type");
            int stored = raw.getInteger("mFacing");
            if (stored < 0 || stored > 5 || front >= 0 && front != stored)
                throw new IOException("GT alignment front differs from target geometry");
            front = stored;
        }
        if (front < 0 || front > 5) throw new IOException("GT alignment is missing a valid target front");
        ExtendedFacing value = ExtendedFacing
            .of(ForgeDirection.getOrientation(front), Rotation.byIndex(rotation), Flip.byIndex(flip));
        IAlignment nativeAlignment = alignment(null, ItemMachines.getMetaTileEntity(sample));
        if (nativeAlignment == null || nativeAlignment.getAlignmentLimits() == null
            || !nativeAlignment.getAlignmentLimits()
                .isNewExtendedFacingValid(value))
            throw new IOException("GT target does not support the requested native alignment");
        return value;
    }

    private static int geometryIndex(NBTTagCompound raw, String key, int count) throws IOException {
        if (!raw.hasKey(key)) return 0; // Native loadNBTData defaults an absent companion to NORMAL/NONE.
        if (!raw.hasKey(key, 1) || raw.getByte(key) < 0 || raw.getByte(key) >= count)
            throw new IOException("GT alignment index is invalid: " + key);
        return raw.getByte(key);
    }

    private static IAlignment alignment(TileEntity tile, Object context) {
        if (context instanceof IAlignment aligned) return aligned;
        if (tile instanceof IAlignment aligned) return aligned;
        if (tile instanceof IAlignmentProvider provider) return provider.getAlignment();
        return context instanceof IAlignmentProvider provider ? provider.getAlignment() : null;
    }

    static boolean alignmentMatches(TileEntity tile, ExtendedFacing expected) {
        if (expected == null) return true;
        Object context = tile instanceof IGregTechTileEntity gt ? gt.getMetaTileEntity() : null;
        IAlignment actual = alignment(tile, context);
        return actual != null && actual.getAlignmentLimits() != null
            && actual.getAlignmentLimits()
                .isNewExtendedFacingValid(expected)
            && actual.getExtendedFacing() == expected;
    }

    private static NBTTagCompound payload(Prepared target, World world, int x, int y, int z, boolean paid)
        throws IOException {
        NBTTagCompound data = paid ? target.buffers.tilePaid : target.buffers.tileEmpty;
        if (data == null) return null;
        var bound = HologramCoordinatePolicies.placementProjection(data, world.provider.dimensionId, x, y, z);
        HologramBlueprintNbt.bindTarget(bound, x, y, z);
        return bound;
    }

    static boolean matches(HologramSession session, Prepared target, int x, int y, int z) throws IOException {
        return matches(session, target, x, y, z, null);
    }

    private static boolean matches(HologramSession session, Prepared target, int x, int y, int z, Boolean paid)
        throws IOException {
        World world = session.player.worldObj;
        if (!identity(world, target, x, y, z)) return false;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!alignmentMatches(tile, target.alignment)) return false;
        if (HologramRecovery.hasSeal(target.sample))
            return HologramRecovery.matchesPlaced(world, x, y, z, placedSeal(session, target, x, y, z), target.facing);
        if (tile instanceof IGregTechTileEntity gt && target.facing >= 0
            && gt.getFrontFacing()
                .ordinal() != target.facing)
            return false;
        if (!HologramGtNativeItemState.matchesBaseline(tile, target.sample, target.nativeState)) return false;
        if (!target.saved) return appearanceMatches(tile, target.appearance);
        var data = payload(target, world, x, y, z, Boolean.TRUE.equals(paid));
        if (!HologramNetworkBindings.matchesBinding(tile, data, session.player, session.networkBindings)) return false;
        if (target.cleared && HologramNetworkBindings.node(target.block)
            && !HologramNativePlacement.hasUnboundNativeNode(tile)) return false;
        if (target.nativeTile)
            return HologramNativePlacement.matchesPlaced(target.block, target.metadata, tile, data, session.player);
        return HologramNativeConfiguration.matchesRetained(tile, session.player, target.sample, data, paid != null);
    }

    static boolean identity(World world, Prepared target, int x, int y, int z) {
        if (!HologramBlockIdentity
            .sameForPlacement(world.getBlock(x, y, z), world.getBlockMetadata(x, y, z), target.block, target.metadata)
            && !(world.getBlock(x, y, z) == target.block && HologramEnergyPayload.volatileEnergyMetadata(target.block)))
            return false;
        if (!(target.sample.getItem() instanceof ItemMachines)) return true;
        var prototype = ItemMachines.getMetaTileEntity(target.sample);
        return prototype != null && world.getTileEntity(x, y, z) instanceof IGregTechTileEntity gt
            && gt.getMetaTileID() == target.sample.getItemDamage()
            && gt.getMetaTileEntity() != null
            && gt.getMetaTileEntity()
                .getClass() == prototype.getClass()
            && gt.getMetaTileEntity()
                .getBaseMetaTileEntity() == gt;
    }

    private static boolean appearanceMatches(TileEntity tile, NBTTagCompound appearance) {
        if (appearance == null || !appearance.hasKey("texturePage", 3) || !appearance.hasKey("textureIndex", 3))
            return true;
        if (!(tile instanceof IGregTechTileEntity gt) || !(gt.getMetaTileEntity() instanceof MTEHatch hatch))
            return false;
        var actual = new NBTTagCompound();
        hatch.saveNBTData(actual);
        return actual.getInteger("texturePage") == appearance.getInteger("texturePage")
            && actual.getInteger("textureIndex") == appearance.getInteger("textureIndex");
    }

    /** Read-only eligibility for changing only native geometry/casing on the same paid GT instance. */
    static boolean synchronizable(HologramSession session, Prepared target, int x, int y, int z) throws IOException {
        World world = session.player.worldObj;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IGregTechTileEntity gt) || HologramRecovery.hasSeal(target.sample)
            || !identity(world, target, x, y, z)
            || !HologramGtNativeItemState.matchesBaseline(tile, target.sample, target.nativeState)) return false;
        if (target.facing >= 0 && !gt.getMetaTileEntity()
            .isFacingValid(ForgeDirection.getOrientation(target.facing))) return false;
        if (target.alignment != null) {
            IAlignment aligned = alignment(tile, gt.getMetaTileEntity());
            if (aligned == null || !aligned.isNewExtendedFacingValid(target.alignment)) return false;
        }
        if (!target.saved) return true;
        NBTTagCompound selected = payload(target, world, x, y, z, false);
        if (!HologramNetworkBindings.matchesBinding(tile, selected, session.player, session.networkBindings))
            return false;
        if (selected != null) {
            selected.removeTag("mFacing");
            selected.removeTag("texturePage");
            selected.removeTag("textureIndex");
        }
        return HologramNativeConfiguration.matchesRetained(tile, session.player, target.sample, selected, false);
    }

    /** Preserve the same paid TE and contents when a native structure changes only facing or casing appearance. */
    static boolean synchronize(HologramSession session, Prepared target, int x, int y, int z,
        BooleanSupplier authority) {
        World world = session.player.worldObj;
        var actor = session.player;
        var inventory = actor.inventory;
        var main = inventory.mainInventory;
        int dimension = actor.dimension;
        TileEntity tile = world.getTileEntity(x, y, z);
        BooleanSupplier bound = () -> actor.worldObj == world && actor.dimension == dimension
            && actor.inventory == inventory
            && inventory.mainInventory == main;
        BooleanSupplier forwardAllowed = () -> mayAdvance(session, authority);
        if (!(tile instanceof IGregTechTileEntity) || !authorized(forwardAllowed, bound)
            || !identity(world, target, x, y, z)
            || world.captureBlockSnapshots
            || world.restoringBlockSnapshots
            || !world.canMineBlock(actor, x, y, z)
            || !actor.canPlayerEdit(x, y, z, 1, target.sample)
            || MinecraftForge.EVENT_BUS
                .post(new BlockEvent.BreakEvent(x, y, z, world, target.block, target.metadata, actor))
            || !authorized(forwardAllowed, bound)
            || world.getTileEntity(x, y, z) != tile) return false;
        HologramMaterials.Transaction transaction = null;
        BooleanSupplier owned = null;
        try {
            if (!synchronizable(session, target, x, y, z)) return false;
            NBTTagCompound old = HologramNativeCapture.capture(tile);
            if (!authorized(forwardAllowed, bound) || world.getTileEntity(x, y, z) != tile) return false;
            transaction = session.materialContext.begin();
            transaction.trackLocalInventory();
            transaction.scopedJournal(
                HologramConstructionJournal
                    .beginAction(actor, x, y, z, target.block, target.metadata, old, session.slot));
            var receipt = transaction.journalReceipt();
            owned = () -> bound.getAsBoolean() && HologramConstructionJournal.owner(actor)
                .getTag(HologramConstructionJournal.KEY) == receipt && bound.getAsBoolean();
            transaction.guard(owned);
            transaction.beforeWorldMutation();
            if (!authorized(forwardAllowed, owned) || world.getTileEntity(x, y, z) != tile)
                throw new IOException("设备原位同步后身份或施工权限已变化。");
            applyNativeState(session, target, tile, x, y, z, false);
            world.markBlockForUpdate(x, y, z);
            if (!authorized(forwardAllowed, owned) || world.getTileEntity(x, y, z) != tile
                || !transaction.localInventoryCurrent()
                || !matches(session, target, x, y, z)) throw new IOException("设备原位同步后身份或施工权限已变化。");
            transaction.commit();
            return true;
        } catch (IOException | RuntimeException | LinkageError failure) {
            // A setter can affect more than this TE. Without a captured native mutation scope, keep
            // its evidence instead of declaring a single-cell snapshot to be complete compensation.
            if (transaction != null) suspend(session, transaction, world, failure);
            else session.status = failure.getMessage() == null ? "设备原位同步后身份或施工权限已变化。" : failure.getMessage();
            return false;
        }
    }

    static Outcome place(HologramSession session, Prepared target, int x, int y, int z, BooleanSupplier authority) {
        World world = session.player.worldObj;
        var actor = session.player;
        var ownerInventory = actor.inventory;
        var ownerMain = ownerInventory.mainInventory;
        int dimension = actor.dimension;
        BooleanSupplier bound = () -> actor.worldObj == world && actor.dimension == dimension
            && actor.inventory == ownerInventory
            && ownerInventory.mainInventory == ownerMain;
        BooleanSupplier forwardAllowed = () -> mayAdvance(session, authority);
        if (!authorized(forwardAllowed, bound) || !world.blockExists(x, y, z)) return Outcome.FAILED;
        try {
            if (matches(session, target, x, y, z)) return Outcome.SATISFIED;
        } catch (IOException invalid) {
            session.status = invalid.getMessage();
            return Outcome.FAILED;
        }
        if (world.getBlock(x, y, z) != Blocks.air || world.getTileEntity(x, y, z) != null
            || !world.canMineBlock(actor, x, y, z)
            || !actor.canPlayerEdit(x, y, z, 1, target.sample)
            || world.captureBlockSnapshots
            || world.restoringBlockSnapshots) return Outcome.FAILED;
        int side = placementSide(target);
        if (!target.nativeTile && target.sample.getItem() instanceof ItemBlock item
            && !world.canPlaceEntityOnSide(item.field_150939_a, x, y, z, false, side, actor, target.sample))
            return Outcome.OBSTRUCTED;
        if (!authorized(forwardAllowed, bound)) return Outcome.FAILED;
        var transaction = session.materialContext.begin();
        transaction.trackLocalInventory();
        transaction.protectAssets(session.pendingAssetBudget, target.assets);
        transaction
            .scopedJournal(HologramConstructionJournal.beginAction(actor, x, y, z, Blocks.air, 0, null, session.slot));
        BooleanSupplier owned = () -> bound.getAsBoolean() && HologramConstructionJournal.owner(actor)
            .getTag(HologramConstructionJournal.KEY) == transaction.journalReceipt() && bound.getAsBoolean();
        var snapshots = new ArrayList<BlockSnapshot>();
        var candidates = new ArrayList<Candidate>();
        boolean changed = false;
        HologramDecorMaterials.Quote decorationQuote = null;
        try {
            transaction.guard(owned);
            boolean creative = actor.capabilities.isCreativeMode;
            if (creative && HologramRecovery.hasSeal(target.sample) && !transaction.takeOne(target.sample, false))
                throw new IOException("封存设备必须提供完整原件，本格未施工。");
            boolean voucher = usesVoucher(session, target);
            if (voucher && !creative && !session.jobVoucherConsumed && !transaction.reserveVoucherMain())
                throw new IOException("装饰抵用券不足，本格未施工。");
            boolean shell = !creative && target.decoration == null && !target.freeShell && !voucher;
            boolean deferredShell = shell && !target.sample.hasTagCompound()
                && target.covers.isEmpty()
                && target.resources.items.isEmpty()
                && target.resources.fluids.isEmpty()
                && target.resources.essentia.isEmpty()
                && target.resources.energy.signum() == 0
                && target.buffers.demand.empty();
            if (deferredShell
                && !(target.fuzzy ? transaction.takeOneFuzzy(HologramEssentia.shellMaterial(target.sample), true)
                    : transaction.takeOne(HologramEssentia.shellMaterial(target.sample), true)))
                throw new IOException("所选材料来源不足，本格未施工。");
            if (!creative) {
                if (target.decoration != null) {
                    decorationQuote = session.decorLedger.quote(
                        voucher ? HologramDecorationVoucher.payable(target.block, target.decoration)
                            : target.decoration,
                        (stack, limit) -> {
                            long protectedCount = Math.max(
                                0L,
                                session.pendingAssetBudget.getOrDefault(HologramValues.lookupKey(stack), 0L)
                                    - target.assets.getOrDefault(HologramValues.lookupKey(stack), 0L));
                            long available = session.materialContext.countLong(
                                stack,
                                Math.min(Long.MAX_VALUE - protectedCount, (long) limit) + protectedCount);
                            return (int) Math.min(limit, Math.max(0L, available - protectedCount));
                        });
                    for (var payment : decorationQuote.items)
                        if (!transaction.takeAll(payment, false)) throw new IOException("装饰材料不足，本格未施工。");
                } else if (shell && !deferredShell
                    && !(target.fuzzy ? transaction.takeOneFuzzy(HologramEssentia.shellMaterial(target.sample), false)
                        : transaction.takeOne(HologramEssentia.shellMaterial(target.sample), false)))
                    throw new IOException("所选材料来源不足，本格未施工。");
                for (var cover : target.covers)
                    if (!transaction.takeOne(cover, false)) throw new IOException("覆盖板材料不足，本格未施工。");
                if (!target.resources.reserve(transaction)) throw new IOException("设备资产材料不足，本格未施工。");
            } else if (!target.resources.reserveEssentia(transaction)) throw new IOException("设备源质不足，本格未施工。");
            boolean cachePaid = target.buffers.demand.empty()
                || !creative && transaction.takeCachedEnergy(target.buffers.demand);
            ItemStack material = target.sample.copy();
            if (target.saved && !HologramRecovery.hasSeal(material)) material.setTagCompound(
                HologramGtNativeItemState.placementItem(
                    target.sample,
                    cachePaid ? copy(target.buffers.itemPaid) : copy(target.buffers.itemEmpty)));
            if (!authorized(forwardAllowed, owned) || world.getBlock(x, y, z) != Blocks.air
                || world.getTileEntity(x, y, z) != null) throw new IOException("付款期间施工条件已变化。");
            transaction.beforeWorldMutation();
            if (!authorized(forwardAllowed, owned) || !transaction.localInventoryCurrent()
                || world.getBlock(x, y, z) != Blocks.air
                || world.getTileEntity(x, y, z) != null) throw new IOException("付款期间施工条件已变化。");
            changed = true;
            int start = world.capturedBlockSnapshots.size();
            world.captureBlockSnapshots = true;
            try {
                writeNative(session, target, material, x, y, z, cachePaid);
            } finally {
                world.captureBlockSnapshots = false;
                snapshots.addAll(world.capturedBlockSnapshots.subList(start, world.capturedBlockSnapshots.size()));
                world.capturedBlockSnapshots.subList(start, world.capturedBlockSnapshots.size())
                    .clear();
                HologramConstructionJournal.recordNativeSnapshots(transaction, snapshots, owned);
                for (var snapshot : snapshots) candidates.add(new Candidate(world, snapshot.x, snapshot.y, snapshot.z));
            }
            if (!identity(world, target, x, y, z) || !matches(session, target, x, y, z, cachePaid))
                throw new IOException("原生放置后的身份、朝向或配置与目标不符。");
            if (snapshots.isEmpty()) throw new IOException("原生放置未提供世界变更凭据。");
            var event = snapshots.size() == 1
                ? ForgeEventFactory.onPlayerBlockPlace(actor, snapshots.get(0), ForgeDirection.getOrientation(side))
                : ForgeEventFactory.onPlayerMultiBlockPlace(actor, snapshots, ForgeDirection.getOrientation(side));
            boolean intact = candidatesIntact(world, candidates);
            if (!intact) throw new IOException("保护事件已改变施工现场，结果待核定。");
            if (event.isCanceled()) throw new IOException("领地或模组保护取消放置，本格已还原。");
            if (!authorized(forwardAllowed, owned) || !transaction.localInventoryCurrent())
                throw new IOException("保护事件后施工权限或付款库存已变化。");
            if (deferredShell
                && !(target.fuzzy ? transaction.takeOneFuzzy(HologramEssentia.shellMaterial(target.sample), false)
                    : transaction.takeOne(HologramEssentia.shellMaterial(target.sample), false)))
                throw new IOException("所选材料来源已变化，本格未提交。");
            if (!authorized(forwardAllowed, owned) || !transaction.localInventoryCurrent()
                || !candidatesIntact(world, candidates)) throw new IOException("取料后施工条件已变化。");
            var data = payload(target, world, x, y, z, cachePaid);
            if (data != null && "join".equals(data.getString(HologramNetworkBindings.ROLE)))
                HologramNetworkBindings.bindJoin(world.getTileEntity(x, y, z), data, actor, session.networkBindings);
            candidates.clear();
            for (var snapshot : snapshots) candidates.add(new Candidate(world, snapshot.x, snapshot.y, snapshot.z));
            for (var snapshot : snapshots) {
                Block block = world.getBlock(snapshot.x, snapshot.y, snapshot.z);
                int metadata = world.getBlockMetadata(snapshot.x, snapshot.y, snapshot.z);
                if (!block.hasTileEntity(metadata)) block.onBlockAdded(world, snapshot.x, snapshot.y, snapshot.z);
                world.markAndNotifyBlock(
                    snapshot.x,
                    snapshot.y,
                    snapshot.z,
                    null,
                    snapshot.getReplacedBlock(),
                    block,
                    snapshot.flag);
            }
            if (!authorized(forwardAllowed, owned) || !transaction.localInventoryCurrent()
                || !candidatesIntact(world, candidates)
                || !matches(session, target, x, y, z, cachePaid)) throw new IOException("原生通知后施工条件已变化。");
            transaction.commit();
            if (decorationQuote != null) session.decorLedger.commit(decorationQuote);
            if (voucher && !creative) session.jobVoucherConsumed = true;
            return Outcome.PLACED;
        } catch (IOException | RuntimeException | LinkageError failure) {
            if (transaction.commitStarted() || transaction.uncertain()
                || !owned.getAsBoolean()
                || !transaction.localInventoryCurrent()
                || changed && (candidates.size() != snapshots.size() || untrackedMain(world, x, y, z, snapshots)
                    || !candidatesIntact(world, candidates))) {
                suspend(session, transaction, world, failure);
                return Outcome.FAILED;
            }
            if (changed) {
                try {
                    transaction.checkpoint();
                    restore(world, snapshots, owned);
                } catch (RuntimeException | LinkageError restorationFailure) {
                    suspend(session, transaction, world, restorationFailure);
                    return Outcome.FAILED;
                }
            }
            if (!owned.getAsBoolean()) {
                suspend(session, transaction, world, failure);
                return Outcome.FAILED;
            }
            try {
                if (!transaction.rollbackAction()) throw new IOException("原生材料退款未完成。");
            } catch (IOException | RuntimeException | LinkageError refundFailure) {
                suspend(session, transaction, world, refundFailure);
                return Outcome.FAILED;
            }
            session.status = failure.getMessage() == null ? "原生放置未提交。" : failure.getMessage();
            return Outcome.FAILED;
        }
    }

    static boolean usesVoucher(HologramSession session, Prepared target) {
        return session.jobVoucherRequested && HologramDecorationVoucher.enabled()
            && !HologramRecovery.hasSeal(target.sample)
            && (HologramDecorationVoucher.covered(target.block, target.sample) || target.decoration != null);
    }

    private static boolean mayAdvance(HologramSession session, BooleanSupplier authority) {
        // Forward permission is tied to the actual admitted stack; compensation uses owned instead.
        var tool = session.boundTool;
        return tool != null && HologramSessionPolicy.jobTool(session) == tool
            && authority.getAsBoolean()
            && HologramSessionPolicy.jobTool(session) == tool;
    }

    private static boolean authorized(BooleanSupplier authority, BooleanSupplier owned) {
        return owned.getAsBoolean() && authority.getAsBoolean() && owned.getAsBoolean();
    }

    private static void suspend(HologramSession session, HologramMaterials.Transaction transaction, World world,
        Throwable failure) {
        transaction.snapshotPayment();
        HologramConstructionJournal.retainScopedUncertainty(
            session.player,
            transaction.journalReceipt(),
            world,
            failure.getClass()
                .getSimpleName() + ": "
                + String.valueOf(failure.getMessage()));
        session.job = 4;
        session.stopTick = true;
        session.defer = false;
        session.phase = "STOPPED";
        session.status = "施工结果待核定，原世界与付款凭据已保留；请联系管理员 /gtmb recovery";
    }

    private static void writeNative(HologramSession session, Prepared target, ItemStack material, int x, int y, int z,
        boolean cachePaid) throws IOException {
        World world = session.player.worldObj;
        try (HologramConstructionSounds ignored = HologramConstructionSounds.enter(world, x, y, z)) {
            int side = placementSide(target);
            if (target.nativeTile) {
                if (!world.setBlock(x, y, z, target.block, target.metadata, 3)) throw new IOException("原生组合方块拒绝施工。");
            } else if (target.block instanceof ICustomBlockSetting custom) {
                custom.setBlock(world, x, y, z, target.metadata);
                target.block.onBlockPlacedBy(world, x, y, z, session.player, material.copy());
                target.block.onPostBlockPlaced(world, x, y, z, target.metadata);
            } else if (material.getItem() instanceof ItemBlock item) {
                if (!world.canPlaceEntityOnSide(item.field_150939_a, x, y, z, false, side, session.player, material))
                    throw new IOException("原生碰撞检查拒绝施工。");
                boolean placed;
                if (item instanceof ItemMachines || inheritsVanillaItemUse(item.getClass())) {
                    int metadata = item.field_150939_a
                        .onBlockPlaced(world, x, y, z, side, .5f, .5f, .5f, item.getMetadata(material.getItemDamage()));
                    placed = item
                        .placeBlockAt(material.copy(), session.player, world, x, y, z, side, .5f, .5f, .5f, metadata);
                } else {
                    // Custom use semantics (for example slabs) remain native; Forge event ownership stays here.
                    placed = item.onItemUse(material.copy(), session.player, world, x, y, z, side, .5f, .5f, .5f);
                }
                if (!placed) throw new IOException("原生物品拒绝放置。");
                restoreSavedVanillaPose(world, target, x, y, z);
            } else throw new IOException("该原生目标没有可用的物品放置接口。");
            TileEntity tile = world.getTileEntity(x, y, z);
            if (HologramRecovery.hasSeal(material)) {
                if (tile == null || !HologramRecovery.supported(tile)) throw new IOException("封存设备没有完整恢复能力。");
                if (!HologramRecovery.restorePlaced(world, x, y, z, placedSeal(session, target, x, y, z)))
                    throw new IOException("付费设备状态恢复失败。");
                tile = world.getTileEntity(x, y, z);
            } else if (target.saved) {
                NBTTagCompound data = payload(target, world, x, y, z, cachePaid);
                if (data != null && !HologramAeBlueprint.staticBlockShell(target.block, target.metadata)) {
                    HologramNetworkBindings.validate(data, session.player, session.networkBindings);
                    data = HologramBlueprintOwnership
                        .prepare(tile, HologramBlueprintNbt.nativePayload(data), session.player);
                    HologramCellPolicy.validateGtPayloadIdentity(material, data);
                    if (target.nativeTile) tile = HologramNativePlacement.install(
                        world,
                        x,
                        y,
                        z,
                        target.block,
                        target.metadata,
                        data,
                        session.player,
                        session.networkBindings,
                        HologramNetworkBindings.node(target.block) && target.cleared);
                    else {
                        if (tile == null) throw new IOException("原生物品未初始化目标设备。");
                        // A selected subset must not zero the native item's other defaults or modes.
                        NBTTagCompound defaults = HologramNativeCapture.capture(tile);
                        for (Object key : data.func_150296_c()) defaults.setTag(
                            (String) key,
                            data.getTag((String) key)
                                .copy());
                        tile.readFromNBT(defaults);
                    }
                }
            }
            if (tile instanceof IGregTechTileEntity gt) {
                gt.setOwnerUuid(session.player.getUniqueID());
                gt.setOwnerName(session.player.getCommandSenderName());
            }
            applyNativeState(session, target, tile, x, y, z, cachePaid);
        }
    }

    /** Reuse the exact persisted vanilla schemas already used for blueprint/CUT rotation. */
    private static boolean savedVanillaPose(Prepared target) {
        if (!target.saved || target.sample.getItem() instanceof ItemMachines) return false;
        String id = Block.blockRegistry.getNameForObject(target.block);
        return id != null && HologramCutTransformMetadata.placementMetadata(
            target.block,
            id,
            target.metadata,
            new com.miaokatze.gtmb.api.CutTransforms.Transform(0, 0, 0, 0)) == target.metadata;
    }

    private static int placementSide(Prepared target) {
        if (!savedVanillaPose(target)) return 1;
        Block block = target.block;
        int meta = target.metadata;
        if (block == Blocks.wall_sign || block == Blocks.ladder) return meta;
        if (block == Blocks.torch || block == Blocks.redstone_torch
            || block == Blocks.unlit_redstone_torch
            || block == Blocks.stone_button
            || block == Blocks.wooden_button) {
            return switch (meta & 7) {
                case 1 -> 5;
                case 2 -> 4;
                case 3 -> 3;
                case 4 -> 2;
                default -> 1;
            };
        }
        if (block.getClass() == net.minecraft.block.BlockStairs.class) return (meta & 4) == 0 ? 1 : 0;
        if (block == Blocks.log || block == Blocks.log2 || block == Blocks.hay_block)
            return (meta & 12) == 4 ? 5 : (meta & 12) == 8 ? 3 : 1;
        return 1;
    }

    private static void restoreSavedVanillaPose(World world, Prepared target, int x, int y, int z) throws IOException {
        // Ordinary native targets must match their native outcome. Only an explicitly saved, known vanilla pose
        // is restored here; arbitrary mod metadata never acquires a generic post-placement overwrite.
        if (world.getBlock(x, y, z) != target.block || world.getBlockMetadata(x, y, z) == target.metadata
            || !savedVanillaPose(target)) return;
        if (!world.setBlockMetadataWithNotify(x, y, z, target.metadata, 2)) throw new IOException("原生方块拒绝已保存的方向状态。");
    }

    private static void applyNativeState(HologramSession session, Prepared target, TileEntity tile, int x, int y, int z,
        boolean cachePaid) throws IOException {
        World world = session.player.worldObj;
        if (tile instanceof IGregTechTileEntity gt) {
            if (target.saved) {
                NBTTagCompound selected = payload(target, world, x, y, z, cachePaid);
                if (!HologramNativeConfiguration.matches(tile, session.player, selected))
                    HologramNativeConfiguration.apply(tile, session.player, selected);
            }
            boolean geometryChange = target.facing >= 0 && gt.getFrontFacing()
                .ordinal() != target.facing || !alignmentMatches(tile, target.alignment);
            boolean textureChange = !appearanceMatches(tile, target.appearance);
            if (geometryChange || textureChange) {
                HologramNativeConfiguration.StateGuard preserved = HologramNativeConfiguration.StateGuard.capture(tile);
                if (geometryChange) applyGeometry(target, tile);
                if (textureChange && gt.getMetaTileEntity() instanceof MTEHatch hatch) hatch.updateTexture(
                    (target.appearance.getInteger("texturePage") << 7) | target.appearance.getInteger("textureIndex"));
                preserved.verify(tile);
            }
            gt.issueTileUpdate();
        }
        if (tile != null) tile.markDirty();
    }

    static void applyGeometry(Prepared target, TileEntity tile) throws IOException {
        if (!(tile instanceof IGregTechTileEntity gt)) {
            if (target.alignment != null) throw new IOException("GT alignment target is missing");
            return;
        }
        IAlignment aligned = alignment(tile, gt.getMetaTileEntity());
        if (target.alignment != null && (aligned == null || aligned.getAlignmentLimits() == null
            || !aligned.getAlignmentLimits()
                .isNewExtendedFacingValid(target.alignment)))
            throw new IOException("原生设备不接受目标对齐。");
        int front = target.alignment == null ? target.facing
            : target.alignment.getDirection()
                .ordinal();
        if (front >= 0 && gt.getFrontFacing()
            .ordinal() != front) {
            ForgeDirection direction = ForgeDirection.getOrientation(front);
            if (gt.getMetaTileEntity() == null || !gt.getMetaTileEntity()
                .isFacingValid(direction)) throw new IOException("原生设备不接受目标朝向。");
            gt.setFrontFacing(direction);
        }
        if (target.alignment != null && aligned.getExtendedFacing() != target.alignment
            && !aligned.checkedSetExtendedFacing(target.alignment)) throw new IOException("原生设备拒绝目标对齐。");
        if (front >= 0 && gt.getFrontFacing()
            .ordinal() != front || !alignmentMatches(tile, target.alignment)) throw new IOException("原生设备未完整应用目标对齐。");
    }

    private static NBTTagCompound copy(NBTTagCompound data) {
        return data == null ? null : (NBTTagCompound) data.copy();
    }

    private static ItemStack placedSeal(HologramSession session, Prepared target, int x, int y, int z)
        throws IOException {
        ItemStack seal = target.sealTemplate.copy();
        NBTTagCompound state = HologramRecovery.relocateNBT(
            seal.getTagCompound()
                .getCompoundTag(HologramRecovery.TAG),
            x,
            y,
            z,
            target.facing);
        state = HologramBlueprintOwnership
            .prepare(session.player.worldObj.getTileEntity(x, y, z), state, session.player);
        HologramCellPolicy.validateGtPayloadIdentity(seal, state);
        seal.getTagCompound()
            .getCompoundTag(HologramRecovery.TAG)
            .setTag("tile", state);
        return seal;
    }

    private static final class Candidate {

        final int x, y, z, metadata;
        final Block block;
        final TileEntity tile;
        final NBTTagCompound state;

        Candidate(World world, int x, int y, int z) throws IOException {
            this.x = x;
            this.y = y;
            this.z = z;
            block = world.getBlock(x, y, z);
            metadata = world.getBlockMetadata(x, y, z);
            tile = world.getTileEntity(x, y, z);
            state = tile == null ? null : HologramNativeCapture.capture(tile);
        }

        boolean intact(World world) throws IOException {
            return world.getBlock(x, y, z) == block && world.getBlockMetadata(x, y, z) == metadata
                && world.getTileEntity(x, y, z) == tile
                && (tile == null || state.equals(HologramNativeCapture.capture(tile)));
        }
    }

    private static boolean candidatesIntact(World world, List<Candidate> candidates) {
        try {
            for (var candidate : candidates) if (!candidate.intact(world)) return false;
            return true;
        } catch (IOException | RuntimeException | LinkageError unknown) {
            return false;
        }
    }

    /** A custom callback that bypasses Forge snapshots cannot authorize an automatic refund/erase. */
    private static boolean untrackedMain(World world, int x, int y, int z, List<BlockSnapshot> snapshots) {
        for (var snapshot : snapshots) if (snapshot.x == x && snapshot.y == y && snapshot.z == z) return false;
        return world.getBlock(x, y, z) != Blocks.air || world.getTileEntity(x, y, z) != null;
    }

    private static void restore(World world, List<BlockSnapshot> snapshots, BooleanSupplier owned) {
        world.restoringBlockSnapshots = true;
        try {
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                if (!owned.getAsBoolean() || !snapshots.get(i)
                    .restore(true, false) || !owned.getAsBoolean()) throw new IllegalStateException("原生世界回退失败或绑定已变化。");
            }
        } finally {
            world.restoringBlockSnapshots = false;
        }
        for (var snapshot : snapshots)
            if (world.getBlock(snapshot.x, snapshot.y, snapshot.z) != snapshot.getReplacedBlock()
                || world.getBlockMetadata(snapshot.x, snapshot.y, snapshot.z) != snapshot.meta)
                throw new IllegalStateException("原生世界回退后状态不符。");
    }
}
