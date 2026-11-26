// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.ICustomBlockSetting;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import com.gtnewhorizon.structurelib.structure.IStructureElement.PlaceResult;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.gtnewhorizon.structurelib.util.ItemStackPredicate;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.BlockFrameBox;
import gregtech.common.blocks.ItemMachines;

/** Authoritative world mutations, native callbacks, payment and rollback; no public session API. */
final class HologramWorldTransactions {

    private HologramWorldTransactions() {}

    static void applyVirtualControllerAlignment(HologramSession s, TileEntity placed) {
        if (!(placed instanceof IGregTechTileEntity)
            || !(((IGregTechTileEntity) placed).getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase))
            throw new IllegalStateException("virtual controller missing enhanced alignment");
        IGregTechTileEntity base = (IGregTechTileEntity) placed;
        MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) base.getMetaTileEntity();
        ExtendedFacing facing = HologramCellPolicy.virtualControllerFacing(s);
        if (!machine.getAlignmentLimits()
            .isNewExtendedFacingValid(facing)) throw new IllegalStateException("virtual controller alignment rejected");
        base.setFrontFacing(facing.getDirection());
        machine.setExtendedFacing(facing);
        if (base.getFrontFacing() != facing.getDirection() || machine.getExtendedFacing() != facing)
            throw new IllegalStateException("virtual controller alignment failed");
    }

    static boolean operate(HologramSession s, HologramCapture.Cell c, int index) {
        return operate(s, c, index, null);
    }

    /**
     * A plain empty-shell write owned by a still-live native or projected driver. The driver's predicate must
     * be side-effect-free; its exact lease remains held through this call and any durable recovery it leaves.
     */
    static boolean operateScoped(HologramSession s, HologramCapture.Cell c, int index, BooleanSupplier authority) {
        return operateScoped(s, c, index, authority, null);
    }

    /**
     * Optional live dependencies read after Forge but before the final candidate/stock fence. This is not
     * part of the callback-free authority predicate and may not settle or release the driver's lease.
     */
    static boolean operateScoped(HologramSession s, HologramCapture.Cell c, int index, BooleanSupplier authority,
        BooleanSupplier livePrecommit) {
        PlacementScope scope = new PlacementScope(s, c, Objects.requireNonNull(authority), livePrecommit);
        scope.selectedSources = s.stockNativePrivate && (s.materialSettings.containers || s.materialSettings.me);
        return operate(s, c, index, scope);
    }

    /** Fresh native projection material only. No saved-blueprint payload or declaration adapter is consulted. */
    static boolean placeNativeProjection(HologramSession s, HologramNativeTargetPlan.Cell target,
        BooleanSupplier authority) {
        World world = s.player.worldObj;
        int x = s.x + target.dx(), y = s.y + target.dy(), z = s.z + target.dz();
        var cell = new HologramCapture.Cell(null, x, y, z);
        ItemStack material = target.material() == null ? null
            : target.material()
                .copy();
        if (material == null || !(material.getItem() instanceof ItemBlock)
            || !authority.getAsBoolean()
            || !world.blockExists(x, y, z)
            || world.getBlock(x, y, z) != Blocks.air
            || world.getTileEntity(x, y, z) != null
            || !world.canMineBlock(s.player, x, y, z)
            || !s.player.canPlayerEdit(x, y, z, 1, s.player.getHeldItem())) {
            s.status = "原生施工目标、权限或空位已变化。";
            return false;
        }
        material.stackSize = 1;
        var inventory = HologramMaterialAccess.copyInventory(s.player, s.slot);
        var context = s.materialContext;
        var transaction = context.begin();
        transaction.scopedJournal(
            HologramConstructionJournal.begin(s.player, x, y, z, Blocks.air, 0, null, inventory, s.slot));
        net.minecraft.tileentity.TileEntity candidate = null;
        boolean attempted = false;
        ItemStack[] paidInventory = null;
        try {
            if (!s.player.capabilities.isCreativeMode && !transaction.takeOne(material, false))
                throw new java.io.IOException("所选材料来源不足，本格未放置。");
            paidInventory = HologramMaterialAccess.copyInventory(s.player, s.slot);
            if (!authority.getAsBoolean() || world.getBlock(x, y, z) != Blocks.air
                || world.getTileEntity(x, y, z) != null) throw new java.io.IOException("付款期间原生施工条件已变化。");
            var escrow = new HologramMaterialSources.ReservedSource(material);
            transaction.beforeWorldMutation();
            attempted = true;
            PlaceResult result;
            try (var events = HologramNativePlacementEvents.open(world, s.player, x, y, z)) {
                result = StructureUtility.survivalPlaceBlock(
                    material,
                    ItemStackPredicate.NBTMode.EXACT,
                    null,
                    false,
                    world,
                    x,
                    y,
                    z,
                    escrow,
                    s.player,
                    s.player::addChatMessage);
                candidate = world.getTileEntity(x, y, z);
                if (events.cancelled() || !escrow.used || result != PlaceResult.ACCEPT)
                    throw new java.io.IOException("原生物品放置或保护事件拒绝了本格。");
            }
            if (!nativeProjectionIdentity(world, x, y, z, target))
                throw new java.io.IOException("原生物品初始化的方块或设备身份与目标不符。");
            applyNativeProjectionState(world, x, y, z, target);
            var snapshot = new BlockSnapshot(world, x, y, z, Blocks.air, 0, null);
            if (MinecraftForge.EVENT_BUS.post(new BlockEvent.PlaceEvent(snapshot, Blocks.air, s.player))
                || !authority.getAsBoolean()
                || world.getTileEntity(x, y, z) != candidate
                || !nativeProjectionStateMatches(world, x, y, z, target)
                || !nativeProjectionInventory(s, paidInventory)) throw new java.io.IOException("原生施工权限、付款库存或目标在提交前变化。");
            transaction.commit();
            return true;
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            if (transaction.commitStarted() || transaction.uncertain()
                || s.player.worldObj != world
                || paidInventory != null && !nativeProjectionInventory(s, paidInventory)) {
                HologramConstructionJournal.suspend(s, transaction, failure);
                return false;
            }
            boolean empty = world.getBlock(x, y, z) == Blocks.air && world.getTileEntity(x, y, z) == null;
            if (attempted && !empty) {
                if (!nativeProjectionIdentity(world, x, y, z, target) || world.getTileEntity(x, y, z) != candidate) {
                    HologramConstructionJournal.suspend(s, transaction, failure);
                    return false;
                }
                if (!restoreCell(s, transaction, () -> {
                    if (!world.setBlockToAir(x, y, z) || world.getTileEntity(x, y, z) != null)
                        throw new IllegalStateException("native candidate rollback failed");
                })) return false;
            }
            rollbackMaterials(s, transaction, inventory);
            s.status = failure.getMessage() == null ? "原生放置未提交。" : failure.getMessage();
            return false;
        }
    }

    static boolean nativeProjectionIdentity(World world, int x, int y, int z, HologramNativeTargetPlan.Cell target) {
        if (world.getBlock(x, y, z) != target.block() || world.getBlockMetadata(x, y, z) != target.metadata())
            return false;
        ItemStack material = target.material();
        if (material == null || !(material.getItem() instanceof ItemMachines)) return true;
        var prototype = ItemMachines.getMetaTileEntity(material);
        return prototype != null && world.getTileEntity(x, y, z) instanceof IGregTechTileEntity gt
            && gt.getMetaTileID() == material.getItemDamage()
            && gt.getMetaTileEntity() != null
            && gt.getMetaTileEntity()
                .getClass() == prototype.getClass()
            && gt.getMetaTileEntity()
                .getBaseMetaTileEntity() == gt;
    }

    private static boolean nativeProjectionInventory(HologramSession session, ItemStack[] expected) {
        var actual = session.player.inventory.mainInventory;
        if (expected == null || expected.length != actual.length) return false;
        for (int i = 0; i < actual.length; i++)
            if (i != session.slot && !ItemStack.areItemStacksEqual(expected[i], actual[i])) return false;
        return true;
    }

    static boolean nativeProjectionStateMatches(World world, int x, int y, int z,
        HologramNativeTargetPlan.Cell target) {
        if (!nativeProjectionIdentity(world, x, y, z, target)) return false;
        if (!(world.getTileEntity(x, y, z) instanceof IGregTechTileEntity gt)) return true;
        if (target.facing() >= 0 && gt.getFrontFacing()
            .ordinal() != target.facing()) return false;
        var expected = target.tileData();
        if (gt.getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEHatch hatch
            && expected != null
            && expected.hasKey("texturePage", 3)
            && expected.hasKey("textureIndex", 3)) {
            var state = new NBTTagCompound();
            hatch.saveNBTData(state);
            return state.getInteger("texturePage") == expected.getInteger("texturePage")
                && state.getInteger("textureIndex") == expected.getInteger("textureIndex");
        }
        return true;
    }

    static boolean synchronizeNativeProjection(HologramSession s, HologramNativeTargetPlan.Cell target,
        BooleanSupplier authority) {
        World world = s.player.worldObj;
        int x = s.x + target.dx(), y = s.y + target.dy(), z = s.z + target.dz();
        TileEntity original = world.getTileEntity(x, y, z);
        if (!(original instanceof IGregTechTileEntity) || !authority.getAsBoolean()
            || !nativeProjectionIdentity(world, x, y, z, target)
            || !world.canMineBlock(s.player, x, y, z)
            || !s.player.canPlayerEdit(x, y, z, 1, s.player.getHeldItem())
            || MinecraftForge.EVENT_BUS
                .post(new BlockEvent.BreakEvent(x, y, z, world, target.block(), target.metadata(), s.player))
            || !authority.getAsBoolean()
            || world.getTileEntity(x, y, z) != original) return false;
        var old = new NBTTagCompound();
        original.writeToNBT(old);
        var transaction = s.materialContext.begin();
        transaction.scopedJournal(
            HologramConstructionJournal.begin(
                s.player,
                x,
                y,
                z,
                target.block(),
                target.metadata(),
                old,
                HologramMaterialAccess.copyInventory(s.player, s.slot),
                s.slot));
        try {
            transaction.beforeWorldMutation();
            applyNativeProjectionState(world, x, y, z, target);
            if (!authority.getAsBoolean() || world.getTileEntity(x, y, z) != original
                || !nativeProjectionStateMatches(world, x, y, z, target))
                throw new java.io.IOException("设备原位同步后身份或施工权限已变化。");
            transaction.commit();
            return true;
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            HologramConstructionJournal.suspend(s, transaction, failure);
            return false;
        }
    }

    /** Only native placement-authored orientation and GT casing appearance are synchronized, never asset NBT. */
    static void applyNativeProjectionState(World world, int x, int y, int z, HologramNativeTargetPlan.Cell target)
        throws java.io.IOException {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof IGregTechTileEntity gt) {
            if (target.facing() >= 0 && gt.getFrontFacing()
                .ordinal() != target.facing()) {
                var direction = ForgeDirection.getOrientation(target.facing());
                if (gt.getMetaTileEntity() == null || !gt.getMetaTileEntity()
                    .isFacingValid(direction)) throw new java.io.IOException("原生设备不接受目标朝向。");
                gt.setFrontFacing(direction);
                if (gt.getFrontFacing() != direction) throw new java.io.IOException("原生设备未接受目标朝向。");
            }
            var expected = target.tileData();
            if (gt.getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEHatch hatch
                && expected != null
                && expected.hasKey("texturePage", 3)
                && expected.hasKey("textureIndex", 3))
                hatch.updateTexture((expected.getInteger("texturePage") << 7) | expected.getInteger("textureIndex"));
            tile.markDirty();
            gt.issueTileUpdate();
            world.markBlockForUpdate(x, y, z);
        }
    }

    /** Saved native state uses the same resource, Forge, ownership and recovery logic as reviewed legacy plans. */
    static boolean operateSavedNative(HologramSession s, HologramCapture.Cell c, int index, boolean stateful,
        BooleanSupplier authority) {
        PlacementScope scope = new PlacementScope(s, c, Objects.requireNonNull(authority), null);
        scope.selectedSources = true;
        scope.allowVoucher = s.jobVoucherRequested;
        scope.savedNative = true;
        scope.stateful = stateful;
        if (c.savedPlacementTarget == null) return scopedRejected(s, c);
        return operate(s, c, index, scope);
    }

    private static final class PlacementScope {

        final BooleanSupplier authority, livePrecommit;
        final World world;
        final Block block;
        final int metadata;
        final InventoryPlayer inventory;
        final ItemStack[] main;
        final HologramMaterials.Settings settings;
        final int dimension, slot;
        final boolean creative;
        HologramMaterials.Context materials;
        HologramMaterials.ScopedMainClaim claim;
        boolean unresolved, selectedSources, allowVoucher, savedNative, stateful;
        boolean candidateKnown, declared;
        int declaredFacing = -1;
        TileEntity candidateTile;

        PlacementScope(HologramSession s, HologramCapture.Cell c, BooleanSupplier authority,
            BooleanSupplier livePrecommit) {
            this.authority = authority;
            this.livePrecommit = livePrecommit;
            world = s.player.worldObj;
            block = c.block;
            metadata = c.meta;
            inventory = s.player.inventory;
            main = inventory.mainInventory;
            settings = s.materialSettings;
            dimension = s.player.dimension;
            slot = s.slot;
            creative = s.player.capabilities.isCreativeMode;
        }

        boolean originalCurrent(HologramCapture.Cell cell, Block old, int oldMeta, TileEntity tile) {
            return sameWorldCell(world, cell, old, oldMeta, tile);
        }

        void observeCandidate(HologramCapture.Cell c) {
            if (!savedNative || world.getBlock(c.x, c.y, c.z) != block
                || world.getBlockMetadata(c.x, c.y, c.z) != metadata) return;
            candidateTile = world.getTileEntity(c.x, c.y, c.z);
            candidateKnown = true;
        }

        boolean candidate(HologramCapture.Cell c) {
            return (!stateful || candidateKnown)
                && sameWorldCell(world, c, block, metadata, stateful ? candidateTile : null);
        }

        boolean worldCurrent(HologramSession s) {
            return s.player.worldObj == world && s.player.dimension == dimension;
        }

        boolean endpoint(HologramSession s) {
            return worldCurrent(s) && s.player.inventory == inventory && inventory.mainInventory == main;
        }

        boolean stockCurrent(HologramSession s) {
            return creative || selectedSources || claim != null && claim.matches(s.player);
        }

        boolean inputs(HologramSession s) {
            return endpoint(s) && s.mode == 0
                && s.slot == slot
                && settings == s.materialSettings
                && (selectedSources || settings.main && !settings.containers && !settings.me)
                && allowVoucher == s.jobVoucherRequested
                && s.player.capabilities.allowEdit
                && s.player.capabilities.isCreativeMode == creative
                && (materials == null || s.materialContext == materials
                    && (selectedSources ? materials.scopedSelected(s.player, settings, slot)
                        : materials.scopedMain(s.player, settings, slot)));
        }

        boolean current(HologramSession s) {
            return inputs(s) && authority.getAsBoolean() && inputs(s);
        }
    }

    private static boolean operate(HologramSession s, HologramCapture.Cell c, int index, PlacementScope scope) {
        HologramMaterials.Context previous = scope == null ? null : s.materialContext;
        try {
            if (scope != null && !scope.current(s)) return scopedRejected(s, c);
            if (HologramConstructionJournal.pending(s.player)) {
                s.job = 4;
                s.stopTick = true;
                s.status = "存在待核定施工凭据，施工已暂停；请联系管理员 /gtmb recovery";
                return false;
            }
            if (scope != null) {
                // materialSource lazily delivers unrelated recovery, so the scoped caller uses an already bound
                // stock context (or explicitly creates it here without that opportunistic delivery).
                scope.materials = previous == null ? new HologramMaterials.Context(s.player, s.materialSettings, s.slot)
                    : previous;
                if (s.materialContext != previous) return scopedRejected(s, c);
                s.materialContext = scope.materials;
                if (!scope.current(s)) return scopedRejected(s, c);
            }
            boolean result = operateChecked(s, c, index, scope);
            if (HologramConstructionJournal.pending(s.player)) {
                s.job = 4;
                s.stopTick = true;
                if (scope == null || !scope.unresolved) s.status = "施工结果待核定，已暂停并保留世界/付款凭据；请联系管理员 /gtmb recovery";
            }
            return result;
        } catch (RuntimeException | LinkageError failure) {
            if (Boolean.getBoolean("gtmb.v0111.nativeContractsOnly")) failure.printStackTrace(System.err);
            if (HologramConstructionJournal.owner(s.player)
                .hasKey(HologramConstructionJournal.KEY)) {
                s.job = 4;
                s.stopTick = true;
                s.status = "Construction recovery pending; external writes stopped; /gtmb recovery";
                return false;
            }
            HologramPlacementDiagnostics.failure(s, c, index, "admission", failure);
            c.status = "unsupported";
            s.status = "设备状态或回收数据无法读取，该格未施工";
            return false;
        } finally {
            if (scope != null && s.materialContext == scope.materials) s.materialContext = previous;
        }
    }

    private static boolean scopedRejected(HologramSession s, HologramCapture.Cell c) {
        c.status = "unsupported";
        s.status = "施工授权或材料范围已失效，该格未提交";
        return false;
    }

    private static boolean scopedPlainShell(HologramSession s, HologramCapture.Cell c, ItemStack target,
        PlacementScope scope) throws java.io.IOException {
        return target != null && target.getItem() instanceof ItemBlock
            && !target.hasTagCompound()
            && Block.getBlockFromItem(target.getItem()) == c.block
            && target.getItem()
                .getMetadata(target.getItemDamage()) == c.meta
            && c.itemNbt.isEmpty()
            && c.tileNbt.isEmpty()
            && c.coverMaterials.isEmpty()
            && c.coverCostsNbt.isEmpty()
            && !c.invalidCoverCosts
            && s.pendingAssetBudget.isEmpty()
            && s.cellAssetBudgets.isEmpty()
            && (!HologramMaterialAccess.voucherSelected(s) || scope.allowVoucher)
            && HologramGtswnPlacementMaterials.supplyKind(target) < 0
            && !HologramGtswnPlacementMaterials.freeShell(c.block, target)
            && !HologramNativePlacement.supported(c.block, null)
            && !HologramDecorMaterials.supports(c.block)
            && (!s.virtual || s.virtualPreset != null && s.virtualPreset.schema >= 2
                && !s.virtualPreset.retainNbt
                && !s.virtualPreset.retainCovers
                && HologramCellPolicy.coverCosts(s, c)
                    .isEmpty());
    }

    private static boolean scopedJournalOwned(HologramSession s, HologramMaterials.Transaction transaction) {
        return HologramConstructionJournal.owner(s.player)
            .getTag(HologramConstructionJournal.KEY) == transaction.journalReceipt();
    }

    private static boolean scopedMutationCurrent(HologramSession s, HologramCapture.Cell c,
        HologramMaterials.Transaction transaction, PlacementScope scope) {
        return c.block == scope.block && c.meta == scope.metadata
            && scope.stockCurrent(s)
            && scope.current(s)
            && scopedJournalOwned(s, transaction);
    }

    private static IItemSource scopedEscrow(HologramSession s, HologramCapture.Cell c,
        HologramMaterials.Transaction transaction, PlacementScope scope, IItemSource escrow) {
        return (predicate, simulate, count) -> {
            // StructureLib checks replaceability before this probe. Do not reject its later token consumption on
            // revocation: the upstream failure path blindly sets the coordinate to air after its attempted write.
            if (simulate && !scopedMutationCurrent(s, c, transaction, scope)) return Collections.emptyMap();
            var offered = escrow.take(predicate, simulate, count);
            return simulate && !scopedMutationCurrent(s, c, transaction, scope) ? Collections.emptyMap() : offered;
        };
    }

    private static boolean retainScopedJournal(HologramSession s, HologramMaterials.Transaction transaction,
        PlacementScope scope) {
        return retainScopedJournal(s, transaction, scope, "world-or-inventory-binding-changed");
    }

    private static boolean retainScopedJournal(HologramSession s, HologramMaterials.Transaction transaction,
        PlacementScope scope, String reason) {
        scope.unresolved = true;
        boolean durable = HologramConstructionJournal
            .retainScopedUncertainty(s.player, transaction.journalReceipt(), scope.world, reason);
        s.job = 4;
        s.stopTick = true;
        s.status = durable ? "施工现场或库存已变更，原凭据已保留，未自动补偿；请联系管理员 /gtmb recovery"
            : "施工现场或库存已变更，原凭据未能写入磁盘，已暂停且未自动补偿；请联系管理员 /gtmb recovery";
        return false;
    }

    static boolean operateChecked(HologramSession s, HologramCapture.Cell c, int index) {
        return operateChecked(s, c, index, null);
    }

    @SuppressWarnings("unchecked")
    private static boolean operateChecked(HologramSession s, HologramCapture.Cell c, int index, PlacementScope scope) {
        if (s.capture != null && !s.capture.sourceCurrent()) {
            c.status = "unsupported";
            s.status = "结构来源或现场状态已变化，未扣除材料，请重新采集";
            return false;
        }
        if (HologramSessionPolicy.ignoredPlacementAir(s, c, index)) {
            c.status = "satisfied";
            return true;
        }

        String declarationProtection = scope != null && scope.declared ? null
            : HologramSourcePolicy.protectionReason(s, c, index);
        if (declarationProtection != null) {
            c.status = "protected";
            s.status = declarationProtection;
            return true;
        }
        World w = scope == null ? s.player.worldObj : scope.world;
        if (s.mode != 2 && s.virtual && s.virtualPreset != null && s.virtualPreset.schema >= 2) {
            try {
                ItemStack target = HologramCellPolicy.targetFor(s, c, index);
                if (target != null) {
                    int plannedMetadata = HologramCellPolicy.targetPreviewMeta(s, c, target);
                    HologramPresets.validateMaterialVariant(
                        HologramCellPolicy.targetPreviewBlock(s, c, target),
                        plannedMetadata,
                        target);
                }
            } catch (java.io.IOException invalidVariant) {
                c.status = "unsupported";
                s.status = invalidVariant.getMessage();
                return false;
            }
        }
        ItemStack sealedPlacement = null;
        if (s.mode != 2 && s.virtual && HologramRecovery.hasSeal(HologramCellPolicy.targetFor(s, c, index))) {
            try {
                sealedPlacement = HologramCellPolicy.sealedPlacementTarget(s, c, index);
            } catch (java.io.IOException unsafeMerge) {
                c.status = "unsupported";
                s.status = unsafeMerge.getMessage();
                return false;
            }
        }
        if (s.virtual && s.virtualPreset.schema >= 2
            && s.mode != 2
            && HologramNativePlacement.supported(c.block)
            && !HologramCellPolicy.resourcePlanValid(s, c)) {
            c.status = "unsupported";
            s.status = c.resourceError;
            return false;
        }
        if (!c.anchor && (s.virtual || !s.areaStructureId.isEmpty())
            && s.mode != 2
            && HologramCellPolicy.targetFor(s, c, index) != null
            && !HologramCellPolicy.acceptableSample(s, c, HologramCellPolicy.targetFor(s, c, index))) {
            c.status = "unsupported";
            s.status = "目标不符合原结构声明，该格未施工";
            return false;
        }
        if (c.y < 0 || c.y >= w.getHeight() || !w.blockExists(c.x, c.y, c.z)) {
            c.status = "unsupported";
            s.status = "区块未加载";
            return false;
        }
        Block old = w.getBlock(c.x, c.y, c.z);
        int oldMeta = w.getBlockMetadata(c.x, c.y, c.z);

        if (s.mode == 1 && (!s.replacementAuthorized || !HologramCellPolicy.originalMember(s, c)
            || HologramCellPolicy.trivial(w, c.x, c.y, c.z))) {
            c.status = "protected";
            return true;
        }
        if (c.anchor) {
            c.status = "protected";
            return true;
        }
        // Keeping a fully matching target requires no harvest authority or recoverable device path.
        if (s.mode == 0 && HologramCellPolicy.targetMatches(s, c, index, old, oldMeta)) {
            c.status = "satisfied";
            return true;
        }
        if (w.getTileEntity(c.x, c.y, c.z) != null && !HologramCellPolicy.recoverable(s, c)) {
            c.status = "protected";
            return true;
        }
        if (!w.canMineBlock(s.player, c.x, c.y, c.z)
            || !s.player.canPlayerEdit(c.x, c.y, c.z, 1, HologramSessionPolicy.trigger(s))) {
            c.status = "protected";
            return true;
        }
        boolean empty = HologramCellPolicy.trivial(w, c.x, c.y, c.z);
        if (s.mode == 2 && empty) {
            c.status = "satisfied";
            return true;
        }
        if (s.mode != 2 && HologramCellPolicy.targetSatisfied(s, c, index, old, oldMeta)) {
            c.status = "satisfied";
            return true;
        }
        if (s.mode == 1 && HologramCellPolicy.orientationOnly(s, c, index)) {
            IGregTechTileEntity actual = (IGregTechTileEntity) w.getTileEntity(c.x, c.y, c.z);
            ForgeDirection before = actual.getFrontFacing();
            try {
                int requested = s.facingPins.get(HologramValues.position(c));
                boolean legal = false;
                for (int allowed : HologramCellPolicy.legalFacings(s, c, HologramCellPolicy.targetFor(s, c, index)))
                    if (allowed == requested) legal = true;
                if (!legal) throw new IllegalStateException("illegal facing");
                actual.setFrontFacing(ForgeDirection.getOrientation(requested));
                if (actual.getFrontFacing()
                    .ordinal() != requested) throw new IllegalStateException("facing rejected");
                c.status = "replaced";
                return true;
            } catch (RuntimeException | LinkageError failure) {
                actual.setFrontFacing(before);
                c.status = "protected";
                s.status = "朝向修改失败，已恢复原方向";
                return false;
            }
        }
        if (!empty && s.mode == 0 && !s.player.capabilities.isCreativeMode) {
            c.status = "blocked";
            s.status = "结构占位被遮挡，未扣材料，未替换旧方块";
            return false;
        }
        if (!empty && !HologramCellPolicy.removable(s, c)) {
            c.status = "protected";
            return true;
        }
        if (s.mode != 2 && ((c.element == null && !HologramCellPolicy.explicitTarget(s, c, index))
            || HologramCellPolicy.targetFor(s, c, index) == null)) {
            c.status = "unsupported";
            return true;
        }
        TileEntity originalTile = w.getTileEntity(c.x, c.y, c.z);
        if (scope != null) {
            if (!empty || old != Blocks.air || originalTile != null) {
                c.status = "blocked";
                s.status = "逐格施工只填真实空位，不替换原方块";
                return false;
            }
            if (c.block == null || !scope.savedNative && c.block.hasTileEntity(c.meta)) return scopedRejected(s, c);
        }
        List<ItemStack> drops = empty
            || (s.player.capabilities.isCreativeMode && w.getTileEntity(c.x, c.y, c.z) == null)
                ? Collections.emptyList()
                : HologramMaterialAccess.drops(old, w, c, oldMeta);

        // Ordinary declared construction cannot debit its held projector. Do not snapshot a draft into a cell
        // journal or roll the projector tag backwards on a failed cell.
        int untouchedSlot = scope != null && scope.declared ? s.slot : -1;
        ItemStack[] inventory = HologramMaterialAccess.copyInventory(s.player, untouchedSlot);
        NBTTagCompound savedTile = w.getTileEntity(c.x, c.y, c.z) == null ? null
            : (NBTTagCompound) drops.get(0)
                .getTagCompound()
                .getCompoundTag(HologramRecovery.TAG)
                .getCompoundTag("tile")
                .copy();
        BlockSnapshot snapshot = readOnlyBlockSnapshot(w, c.x, c.y, c.z);
        if (scope != null && !scope.current(s)) return scopedRejected(s, c);
        HologramMaterials.Transaction transaction = materialSource(s, scope).begin();
        if (scope == null || scope.selectedSources) {
            transaction
                .protectAssets(s.pendingAssetBudget, s.cellAssetBudgets.getOrDefault(index, Collections.emptyMap()))
                .journal(
                    HologramConstructionJournal
                        .begin(s.player, c.x, c.y, c.z, old, oldMeta, savedTile, inventory, untouchedSlot));
        } else {
            transaction.scopedJournal(
                HologramConstructionJournal.begin(s.player, c.x, c.y, c.z, old, oldMeta, savedTile, inventory));
        }
        HologramDecorMaterials.Descriptor decorDescriptor = null;
        HologramDecorMaterials.Quote decorQuote = null;
        ItemStack reserved = null;
        ItemStack energyTarget = HologramCellPolicy.targetFor(s, c, index);
        energyTarget = energyTarget == null ? null : energyTarget.copy();
        HologramEnergyPayload.Prepared cachedPayload = null;
        boolean cachePaid = false;
        boolean voucherReserved = false;
        boolean voucherShell = false;
        String cacheNotice = "";
        boolean changedWorld = false;
        NBTTagCompound quantumPlacementPayload = null;
        TileEntity newQuantumJoin = null;
        String placementStage = "reserve";
        try {
            if (scope != null && !scope.savedNative) {
                if (!scopedPlainShell(s, c, energyTarget, scope) || !scope.current(s)) {
                    rollbackMaterials(s, transaction, inventory, scope);
                    return scopedRejected(s, c);
                }
                if (!scope.creative) {
                    ItemStack specimen = energyTarget.copy();
                    specimen.stackSize = 1;
                    voucherShell = scope.allowVoucher && HologramMaterialAccess.voucherSelected(s)
                        && HologramDecorationVoucher.covered(c.block, specimen);
                    if (voucherShell && !s.jobVoucherConsumed) {
                        if (!HologramDecorationVoucher.reserveMain(s.player)) {
                            rollbackMaterials(s, transaction, inventory, scope);
                            c.status = "missing";
                            s.status = "装饰抵用券不足，本格未施工、未扣材料";
                            return false;
                        }
                        voucherReserved = true;
                    }
                    boolean funded;
                    if (voucherShell) {
                        funded = true;
                    } else if (scope.selectedSources) {
                        funded = transaction.takeOne(specimen, false);
                    } else {
                        scope.claim = HologramMaterials.ScopedMainClaim
                            .observe(s.player, scope.inventory, scope.main, scope.slot, specimen);
                        funded = scope.claim != null;
                    }
                    if (!funded || !scope.current(s)) {
                        rollbackMaterials(s, transaction, inventory, scope);
                        c.status = "missing";
                        s.status = "所选来源没有可核对的本格材料，未施工、已还原可确认的扣料";
                        return false;
                    }
                    // A fungible local escrow token only; StructureLib never extracts from a live backpack.
                    reserved = voucherShell ? null : specimen;
                }
            } else {
                decorDescriptor = HologramCellPolicy.decoration(s, c);
                boolean voucher = HologramMaterialAccess.voucherSelected(s);
                voucherShell = voucher && decorDescriptor == null
                    && !HologramRecovery.hasSeal(energyTarget)
                    && HologramDecorationVoucher.covered(c.block, energyTarget);
                boolean voucherDecoration = voucher && HologramDecorationVoucher.hasCovered(c.block, decorDescriptor);
                if (!s.player.capabilities.isCreativeMode && (voucherShell || voucherDecoration)
                    && !s.jobVoucherConsumed) {
                    // The existing inventory snapshot is the rollback receipt. A voucher is deliberately
                    // paid from the main inventory, independently of ME/container material priority.
                    if (!HologramDecorationVoucher.reserveMain(s.player)) {
                        rollbackMaterials(s, transaction, inventory, scope);
                        c.status = "missing";
                        s.status = "装饰代用券已不足，该格未施工，未扣材料";
                        return false;
                    }
                    voucherReserved = true;
                }
                if (s.mode != 2 && decorDescriptor == null
                    && !voucherShell
                    && !HologramMaterialAccess.freeShell(s, c, HologramCellPolicy.targetFor(s, c, index))
                    && (!empty || scope != null && scope.declared || s.virtual && s.virtualPreset.schema >= 2)
                    && (!s.player.capabilities.isCreativeMode
                        || HologramRecovery.hasSeal(HologramCellPolicy.targetFor(s, c, index)))) {
                    ItemStack required = HologramEssentia.shellMaterial(HologramCellPolicy.targetFor(s, c, index));
                    if (!HologramMaterialAccess.fuzzyBlueprintShells(s, required)
                        && transaction.takeOne(required, false)) reserved = required.copy();
                    if (reserved == null && !HologramMaterialAccess.fuzzyBlueprintShells(s, required)) {
                        rollbackMaterials(s, transaction, inventory, scope);
                        c.status = "missing";
                        s.status = scope != null && scope.declared ? "所选材料来源不足，这格未放置" : "替换材料不足，旧方块已保留";
                        return false;
                    }
                }
                if (s.mode != 2 && !s.player.capabilities.isCreativeMode)
                    for (ItemStack cover : HologramCellPolicy.coverCosts(s, c)) {
                        if (!transaction.takeOne(cover, false)) {
                            rollbackMaterials(s, transaction, inventory, scope);
                            c.status = "missing";
                            s.status = "覆盖板材料不足，该格未施工，已归还预留材料";
                            return false;
                        }
                    }
                if (!HologramCellPolicy.coverCostsValid(s, c))
                    throw new IllegalStateException("blueprint cover data invalid");
                if (s.virtual && s.virtualPreset.schema >= 2
                    && !(s.player.capabilities.isCreativeMode ? HologramCellPolicy.resourcePlan(s, c)
                        .reserveEssentia(transaction)
                        : HologramCellPolicy.resourcePlan(s, c)
                            .reserve(transaction))) {
                    rollbackMaterials(s, transaction, inventory, scope);
                    c.status = "missing";
                    s.status = "蓝图库存、流体或无线电网资源不足；该格未施工，已归还全部预留资源";
                    return false;
                }
                if (s.mode != 2 && decorDescriptor != null && !s.player.capabilities.isCreativeMode) {
                    decorQuote = s.decorLedger.quote(
                        voucherDecoration ? HologramDecorationVoucher.payable(c.block, decorDescriptor)
                            : decorDescriptor,
                        (stack, limit) -> HologramMaterialAccess.decorativeAvailability(s, index, stack, limit));
                    for (ItemStack payment : decorQuote.items) if (!transaction.takeAll(payment, false)) {
                        rollbackMaterials(s, transaction, inventory, scope);
                        c.status = "missing";
                        s.status = "装饰材料不足；支持原料比例向上取整或已有预制件，该格未施工";
                        return false;
                    }
                }
                if (s.mode != 2 && decorDescriptor == null
                    && !voucherShell
                    && !HologramMaterialAccess.freeShell(s, c, HologramCellPolicy.targetFor(s, c, index))
                    && HologramMaterialAccess.fuzzyBlueprintShells(s, HologramCellPolicy.targetFor(s, c, index))
                    && (!s.player.capabilities.isCreativeMode
                        || HologramRecovery.hasSeal(HologramCellPolicy.targetFor(s, c, index)))) {
                    ItemStack required = HologramEssentia.shellMaterial(HologramCellPolicy.targetFor(s, c, index));
                    // Exact resources reserve first, leaving another NBT variant available for the fuzzy shell.
                    if (!transaction.takeOneFuzzy(required, false)) {
                        rollbackMaterials(s, transaction, inventory, scope);
                        c.status = "missing";
                        s.status = "去标识设备材料不足，已归还本格预留的全部资源";
                        return false;
                    }
                    reserved = required.copy();
                }
                if (s.mode != 2 && s.virtual && s.virtualPreset.schema >= 2) {
                    cachedPayload = HologramCellPolicy.cachePlan(s, c, index);
                    // Saved creative builds must never withdraw from a real player's energy network.
                    // Keep protected stored energy empty unless no payment was required.
                    boolean noStockCreative = scope != null && scope.savedNative && !scope.declared && scope.creative;
                    cachePaid = cachedPayload.demand.empty()
                        || !noStockCreative && transaction.takeCachedEnergy(cachedPayload.demand);
                    if (!cachePaid) cacheNotice = "设备缓存余额不足，已按空缓存放置";
                    if (!cachedPayload.warnings.isEmpty())
                        cacheNotice += (cacheNotice.isEmpty() ? "" : "；") + "未知缓存已安全省略："
                            + cachedPayload.warnings.get(0);
                    if (!cachedPayload.intrinsicSource.isEmpty())
                        cacheNotice += (cacheNotice.isEmpty() ? "" : "；") + "保留原生固有能源";
                }
                if (reserved != null && !HologramRecovery.hasSeal(reserved)
                    && s.virtual
                    && s.virtualPreset.schema >= 2
                    && !c.itemNbt.isEmpty())
                    reserved.setTagCompound(
                        cachedPayload == null ? null : cachePaid ? cachedPayload.itemPaid : cachedPayload.itemEmpty);
            }
            if (!empty) {
                placementStage = "remove";

                BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(c.x, c.y, c.z, w, old, oldMeta, s.player);
                boolean cancelled = MinecraftForge.EVENT_BUS.post(event);

                if (cancelled) {
                    rollbackMaterials(s, transaction, inventory, scope);
                    c.status = "protected";
                    s.status = "领地/模组保护取消拆除，原方块保留，预留材料已归还";
                    return true;
                }
                if (!sameWorldCell(w, c, old, oldMeta, originalTile)) {
                    rollbackMaterials(s, transaction, inventory, scope);
                    c.status = "protected";
                    s.status = "拆除事件已改变现场，该格保留，请重新扫描";
                    return true;
                }
                if (originalTile != null) {
                    // A listener may change a hatch inventory/fluid without changing the block identity.
                    // Capture its latest state, rather than rolling back or recovering the obsolete preview.
                    drops = HologramRecovery.drops(w, c.x, c.y, c.z);
                    savedTile = (NBTTagCompound) drops.get(0)
                        .getTagCompound()
                        .getCompoundTag(HologramRecovery.TAG)
                        .getCompoundTag("tile")
                        .copy();
                    snapshot = readOnlyBlockSnapshot(w, c.x, c.y, c.z);
                }

                boolean tile = originalTile != null;
                boolean removed;
                if (tile) {
                    changedWorld = true;
                    transaction.beforeWorldMutation();
                    removed = HologramRecovery.removeWithoutDrops(w, c.x, c.y, c.z);

                } else {
                    boolean frame = old instanceof BlockFrameBox;
                    if (!frame) {
                        old.onBlockHarvested(w, c.x, c.y, c.z, oldMeta, s.player);
                        if (!sameWorldCell(w, c, old, oldMeta, originalTile)) {
                            rollbackMaterials(s, transaction, inventory, scope);
                            c.status = "protected";
                            s.status = "采收回调已改变现场，该格保留，请重新扫描";
                            return true;
                        }
                    }
                    changedWorld = true;
                    transaction.beforeWorldMutation();
                    removed = frame ? w.setBlockToAir(c.x, c.y, c.z)
                        : old.removedByPlayer(w, s.player, c.x, c.y, c.z, false);
                    if (removed) old.onBlockDestroyedByPlayer(w, c.x, c.y, c.z, oldMeta);
                }
                if (!removed) {
                    if (!restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                    rollbackMaterials(s, transaction, inventory, scope);
                    c.status = "protected";
                    return true;
                }
            }
            if (s.mode != 2) {
                placementStage = "native-place";
                if (!HologramValues.same(energyTarget, HologramCellPolicy.targetFor(s, c, index)))
                    throw new IllegalStateException("Placement target changed after resource reservation");
                if (scope != null && (!scope.stockCurrent(s) || !scope.current(s))) {
                    if (changedWorld && !restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                    rollbackMaterials(s, transaction, inventory, scope);
                    return scopedRejected(s, c);
                }
                if (scope == null || scope.selectedSources) {
                    changedWorld = true;
                    transaction.beforeWorldMutation();
                } else transaction.beforeScopedWorldMutation();
                final ItemStack material = reserved;
                HologramMaterialSources.ReservedSource reservation = material == null ? null
                    : new HologramMaterialSources.ReservedSource(material);
                IItemSource source = reservation == null ? transaction : reservation;
                // Lock the pure plan's chosen material. Native auto-selection must not consume a different item.
                ItemStack pin = HologramEssentia.shellMaterial(HologramCellPolicy.targetFor(s, c, index));
                if (s.virtual && s.virtualPreset.schema >= 2
                    && !c.itemNbt.isEmpty()
                    && pin.isItemEqual(HologramCellPolicy.targetFor(s, c, index))
                    && !HologramRecovery.hasSeal(pin)) {
                    pin = pin.copy();
                    pin.setTagCompound(
                        cachedPayload == null ? null : cachePaid ? cachedPayload.itemPaid : cachedPayload.itemEmpty);
                }
                if ((s.player.capabilities.isCreativeMode || voucherShell) && !HologramRecovery.hasSeal(pin))
                    source = new HologramMaterialSources.CreativeSource(Collections.singletonList(pin));
                if (pin != null && material == null) source = new HologramMaterialSources.FilteredSource(source, pin);
                if (s.noHatches && !HologramCellPolicy.explicitTarget(s, c, index))
                    source = new HologramMaterialSources.ShellSource(source);
                if (scope != null) source = scopedEscrow(s, c, transaction, scope, source);
                PlaceResult result;
                boolean nativeCancelled;
                NBTTagCompound selectedCacheTile = cachedPayload == null ? null
                    : cachePaid ? cachedPayload.tilePaid : cachedPayload.tileEmpty;
                boolean nativePlacement = (scope == null || scope.savedNative) && s.virtual
                    && s.virtualPreset.schema >= 2
                    && HologramNativePlacement.supported(c.block, selectedCacheTile);
                try (HologramNativePlacementEvents.Scope events = HologramNativePlacementEvents
                    .open(w, s.player, c.x, c.y, c.z)) {
                    if (scope != null) {
                        if (!scope.current(s) || !scope.originalCurrent(c, old, oldMeta, originalTile)
                            || c.block != scope.block
                            || c.meta != scope.metadata
                            || !scope.stockCurrent(s)
                            || !scope.current(s)) {
                            if (changedWorld && !restoreCell(s, c, transaction, old, oldMeta, savedTile, scope))
                                return false;
                            rollbackMaterials(s, transaction, inventory, scope);
                            return scopedRejected(s, c);
                        }
                        // The existing Forge ItemBlock route retains its own permission and candidate checks.

                        if (!scopedJournalOwned(s, transaction))
                            return retainScopedJournal(s, transaction, scope, "journal-ownership-changed");
                        changedWorld = true;
                    }
                    if (nativePlacement) {
                        // The complete component plan was reserved above, including the primary material.
                        result = (decorDescriptor != null || HologramMaterialAccess.freeShell(s, c, pin)
                            || s.player.capabilities.isCreativeMode && !HologramRecovery.hasSeal(pin)
                            || (reservation == null && HologramMaterialAccess.fuzzyBlueprintShells(s, pin)
                                ? transaction.takeOneFuzzy(pin, false)
                                : source.takeOne(pin, false)))
                            && w.setBlock(c.x, c.y, c.z, c.block, c.meta, 3) ? PlaceResult.ACCEPT : PlaceResult.REJECT;
                    } else {
                        // The exact material was proved above. Never re-enter a chain's unknown checks,
                        // tier setters, hatch adders or deferred suppliers while placing that material.
                        result = StructureUtility.survivalPlaceBlock(
                            pin,
                            HologramMaterialAccess.fuzzyBlueprintShells(s, pin) ? ItemStackPredicate.NBTMode.IGNORE
                                : ItemStackPredicate.NBTMode.EXACT,
                            null,
                            false,
                            w,
                            c.x,
                            c.y,
                            c.z,
                            source,
                            s.player,
                            s.player::addChatMessage);
                    }
                    nativeCancelled = events.cancelled();
                }
                if (scope != null && scope.savedNative
                    && (result == PlaceResult.ACCEPT || result == PlaceResult.ACCEPT_STOP)) scope.observeCandidate(c);
                if (scope != null) {
                    if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);

                    if (!scopedMutationCurrent(s, c, transaction, scope)) {
                        if (!restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                        rollbackMaterials(s, transaction, inventory, scope);
                        return scopedRejected(s, c);
                    }
                }
                if (Boolean.getBoolean("gtmb.v0111.nativeContractsOnly")) System.out.println(
                    "[GTMB-NATIVE-CONTRACTS] placementResult=" + result
                        + ", nativePlacement="
                        + nativePlacement
                        + ", nativeCancelled="
                        + nativeCancelled
                        + ", reservationUsed="
                        + (reservation == null ? "none" : reservation.used)
                        + ", planned="
                        + Block.blockRegistry.getNameForObject(c.block)
                        + ":"
                        + c.meta
                        + ", actual="
                        + Block.blockRegistry.getNameForObject(w.getBlock(c.x, c.y, c.z))
                        + ":"
                        + w.getBlockMetadata(c.x, c.y, c.z));
                if (result == PlaceResult.STOP) {
                    if (!restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                    rollbackMaterials(s, transaction, inventory, scope);
                    s.defer = true;
                    c.status = "pending";
                    s.status = "原版元素要求等待下一 tick";
                    return false;
                }
                if (result == PlaceResult.ACCEPT_STOP) s.stopTick = true;
                if ((scope == null || scope.savedNative) && s.virtual
                    && s.virtualPreset.schema >= 2
                    && (nativePlacement || pin.getItem() instanceof ItemBlock
                        || w.getBlock(c.x, c.y, c.z) == Block.getBlockFromItem(pin.getItem()))
                    && (result == PlaceResult.ACCEPT || result == PlaceResult.ACCEPT_STOP
                        || result == PlaceResult.SKIP)) {
                    int plannedMetadata = HologramCellPolicy.targetPreviewMeta(s, c, pin);
                    HologramPresets.validateMaterialVariant(w.getBlock(c.x, c.y, c.z), plannedMetadata, pin);
                    if (w.getBlockMetadata(c.x, c.y, c.z) != plannedMetadata)
                        w.setBlockMetadataWithNotify(c.x, c.y, c.z, plannedMetadata, 2);
                    if (scope != null && scope.savedNative) scope.observeCandidate(c);
                }
                if ((result != PlaceResult.ACCEPT && result != PlaceResult.ACCEPT_STOP && result != PlaceResult.SKIP)
                    || (!nativePlacement && w.isAirBlock(c.x, c.y, c.z))
                    || (nativePlacement
                        && (w.getBlock(c.x, c.y, c.z) != c.block || w.getBlockMetadata(c.x, c.y, c.z) != c.meta))
                    || (reservation != null && !reservation.used)
                    || (pin != null && !nativePlacement
                        && (!HologramCellPolicy.targetMatches(
                            s,
                            c,
                            index,
                            w.getBlock(c.x, c.y, c.z),
                            w.getBlockMetadata(c.x, c.y, c.z),
                            false)))) {
                    HologramPlacementDiagnostics
                        .rejected(s, c, index, result.name(), nativeCancelled, reservation == null || reservation.used);
                    if (!restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                    rollbackMaterials(s, transaction, inventory, scope);
                    c.status = nativeCancelled ? "protected"
                        : result == PlaceResult.REJECT_CONTINUE ? "unsupported" : "missing";
                    s.status = nativeCancelled ? "领地/模组保护取消了原生放置；该格已还原，材料已完整退回" : "原版元素拒绝施工；材料和旧方块已还原";
                    return false;
                }
                if (scope != null) {
                    // A scoped plain shell has no follow-up state to normalize or install. The single native
                    // setter must already have authored this exact candidate before the final protection event.
                    boolean exact = scope.candidate(c);
                    if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
                    if (!exact)
                        return retainScopedJournal(s, transaction, scope, "candidate-changed-before-permission");
                    if (!scopedMutationCurrent(s, c, transaction, scope)) {
                        if (!restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                        rollbackMaterials(s, transaction, inventory, scope);
                        return scopedRejected(s, c);
                    }
                }
                if (scope == null || scope.savedNative && scope.stateful) {
                    Block placed = w.getBlock(c.x, c.y, c.z);
                    placementStage = "native-callbacks";
                    if (placed instanceof ICustomBlockSetting) {
                        ItemStack placedStack = new ItemStack(placed, 1, w.getBlockMetadata(c.x, c.y, c.z));
                        placed.onBlockPlacedBy(w, c.x, c.y, c.z, s.player, placedStack);
                        placed.onPostBlockPlaced(w, c.x, c.y, c.z, w.getBlockMetadata(c.x, c.y, c.z));
                    }
                    if (!HologramRecovery.restorePlaced(w, c.x, c.y, c.z, pin)
                        || (HologramRecovery.hasSeal(pin) && !HologramRecovery.matchesPlaced(w, c.x, c.y, c.z, pin)))
                        throw new IllegalStateException("stored device restore rejected");
                    Integer direction = c.savedPlacementTarget != null
                        ? (c.savedPlacementFacing < 0 ? null : Integer.valueOf(c.savedPlacementFacing))
                        : scope != null && scope.declared && scope.declaredFacing >= 0
                            ? Integer.valueOf(scope.declaredFacing)
                            : s.planFacings.get(index);
                    TileEntity placedTile = w.getTileEntity(c.x, c.y, c.z);
                    if (s.virtual && placedTile instanceof IGregTechTileEntity) {
                        IGregTechTileEntity ownerHost = (IGregTechTileEntity) placedTile;
                        ownerHost.setOwnerUuid(s.player.getUniqueID());
                        ownerHost.setOwnerName(s.player.getCommandSenderName());
                    }
                    boolean blueprintTileDataApplied = false;
                    boolean emptyAeNativeShell = nativePlacement
                        && HologramAeBlueprint.supportsEmptyNativeShell(c.block);
                    boolean requiredNetworkBinding = selectedCacheTile != null
                        && selectedCacheTile.hasKey(HologramNetworkBindings.GROUP);
                    boolean restoreBlueprintTileData = s.virtual && s.virtualPreset.schema >= 2
                        && sealedPlacement == null
                        && !c.tileNbt.isEmpty()
                        && selectedCacheTile != null
                        && !HologramAeBlueprint.staticBlockShell(c.block, c.meta)
                        && (s.virtualPreset.retainNbt || HologramSessionPolicy.placementRetainCovers(s)
                            || emptyAeNativeShell
                            || requiredNetworkBinding);
                    if (sealedPlacement != null) {
                        placementStage = "sealed-state";
                        if (placedTile == null || !HologramRecovery.supported(placedTile))
                            throw new java.io.IOException("封存设备没有可验证的完整状态恢复路径");
                        NBTTagCompound paidState = (NBTTagCompound) sealedPlacement.getTagCompound()
                            .getCompoundTag(HologramRecovery.TAG)
                            .getCompoundTag("tile")
                            .copy();
                        if (direction != null && s.facingPins.containsKey(HologramValues.position(c)))
                            paidState.setShort("mFacing", direction.shortValue());
                        paidState = HologramBlueprintOwnership.prepare(placedTile, paidState, s.player);
                        HologramCellPolicy.validateGtPayloadIdentity(pin, paidState);
                        placedTile.readFromNBT(paidState);
                        ItemStack expectedSeal = sealedPlacement.copy();
                        expectedSeal.getTagCompound()
                            .getCompoundTag(HologramRecovery.TAG)
                            .setTag("tile", paidState.copy());
                        if (!HologramRecovery.matchesPlaced(w, c.x, c.y, c.z, expectedSeal))
                            throw new java.io.IOException("封存设备所有权合并无法保留完整付费状态，已回滚本格");
                        placedTile.markDirty();
                        w.markBlockForUpdate(c.x, c.y, c.z);
                        blueprintTileDataApplied = true;
                        NBTTagCompound descriptor = HologramBlueprintNbt.decode(c.tileNbt);
                        if (descriptor != null && descriptor.hasKey(HologramNetworkBindings.GROUP)) {
                            quantumPlacementPayload = new NBTTagCompound();
                            for (Object raw : descriptor.func_150296_c()) {
                                String key = (String) raw;
                                if (HologramNetworkBindings.descriptorKey(key)) quantumPlacementPayload.setTag(
                                    key,
                                    descriptor.getTag(key)
                                        .copy());
                            }
                        }
                    }
                    if (direction != null && direction >= 0
                        && placedTile instanceof IGregTechTileEntity
                        && !restoreBlueprintTileData
                        && (!HologramRecovery.hasSeal(pin) || s.facingPins.containsKey(HologramValues.position(c)))) {
                        placementStage = "default-facing";
                        IGregTechTileEntity gt = (IGregTechTileEntity) placedTile;
                        if (c.anchor || !gt.getMetaTileEntity()
                            .isFacingValid(ForgeDirection.getOrientation(direction)))
                            throw new IllegalStateException("hatch facing rejected");
                        gt.setFrontFacing(ForgeDirection.getOrientation(direction));
                        if (gt.getFrontFacing()
                            .ordinal() != direction) throw new IllegalStateException("hatch facing failed");
                    }
                    if (s.virtual && s.virtualPreset.schema < 2 && "templateController".equals(c.role))
                        applyVirtualControllerAlignment(s, placedTile);
                    if (restoreBlueprintTileData) {
                        placementStage = "blueprint-state";
                        if (placedTile == null && !nativePlacement)
                            throw new IllegalStateException("blueprint tile missing");
                        NBTTagCompound data = selectedCacheTile;
                        if (data != null) {
                            data = HologramGtMeShadow.project(data);
                            data = HologramCoordinatePolicies.placementProjection(data, s.dimension, c.x, c.y, c.z);
                            HologramNetworkBindings.validate(data, s.player, s.networkBindings);
                            quantumPlacementPayload = data;
                            if (!s.virtualPreset.retainNbt && !emptyAeNativeShell) {
                                NBTTagCompound defaults = HologramNativeCapture.capture(placedTile);
                                for (Object key : data.func_150296_c()) defaults.setTag(
                                    (String) key,
                                    data.getTag((String) key)
                                        .copy());
                                data = defaults;
                            }
                            data = HologramBlueprintOwnership
                                .prepare(placedTile, HologramBlueprintNbt.nativePayload(data), s.player);
                            HologramCellPolicy.validateGtPayloadIdentity(pin, data);
                            if (nativePlacement) placedTile = HologramNativePlacement.install(
                                w,
                                c.x,
                                c.y,
                                c.z,
                                c.block,
                                c.meta,
                                data,
                                s.player,
                                s.networkBindings,
                                HologramNetworkBindings.node(c.block)
                                    && HologramBlueprintProcessing.quantumCleared(s.virtualPreset));
                            else placedTile.readFromNBT(data);
                            if (scope != null && scope.savedNative && w.getTileEntity(c.x, c.y, c.z) == placedTile)
                                scope.observeCandidate(c);
                            blueprintTileDataApplied = true;
                            if (direction != null && direction >= 0 && placedTile instanceof IGregTechTileEntity) {
                                placementStage = "restored-facing";
                                IGregTechTileEntity gt = (IGregTechTileEntity) placedTile;
                                // Basic machines restore a distinct main-facing from NBT. Validate their output-facing
                                // against that restored state, rather than the temporary item-placement defaults.
                                if (c.anchor || !gt.getMetaTileEntity()
                                    .isFacingValid(ForgeDirection.getOrientation(direction)))
                                    throw new IllegalStateException("blueprint facing rejected");
                                gt.setFrontFacing(ForgeDirection.getOrientation(direction));
                                if (gt.getFrontFacing()
                                    .ordinal() != direction)
                                    throw new IllegalStateException("blueprint front rejected");
                            }
                            placedTile.markDirty();
                            w.markBlockForUpdate(c.x, c.y, c.z);
                        }
                    }
                    if (s.virtual && s.virtualPreset.schema >= 2 && placedTile != null && !blueprintTileDataApplied) {
                        placementStage = "ownership";
                        NBTTagCompound owned = HologramBlueprintOwnership.prepare(placedTile, null, s.player);
                        if (owned != null) {
                            HologramCellPolicy.validateGtPayloadIdentity(pin, owned);
                            placedTile.readFromNBT(owned);
                            placedTile.markDirty();
                            w.markBlockForUpdate(c.x, c.y, c.z);
                        }
                    }
                    if (s.virtual && placedTile instanceof IGregTechTileEntity) {
                        IGregTechTileEntity gtOwner = (IGregTechTileEntity) placedTile;
                        gtOwner.setOwnerUuid(s.player.getUniqueID());
                        gtOwner.setOwnerName(s.player.getCommandSenderName());
                        placedTile.markDirty();
                    }
                    // Legacy templates upgraded in memory have no device NBT, but their declared
                    // single controller alignment still carries roll and flip and must be restored.
                    if (s.virtual && s.virtualPreset.schema >= 2
                        && "templateController".equals(c.role)
                        && c.x == s.x
                        && c.y == s.y
                        && c.z == s.z
                        && s.virtualPreset.controllerId >= 0
                        && pin.getItem() instanceof ItemMachines
                        && pin.getItemDamage() == s.virtualPreset.controllerId)
                        applyVirtualControllerAlignment(s, placedTile);
                    placementStage = "installed-state";
                    // Native multipart blocks report air until their complete tile is installed.
                    // Verify semantic occupancy only after the audited factory and world-join path finish.
                    if (nativePlacement && (!blueprintTileDataApplied || placedTile == null
                        || w.getBlock(c.x, c.y, c.z) != c.block
                        || w.getBlockMetadata(c.x, c.y, c.z) != c.meta
                        || w.getTileEntity(c.x, c.y, c.z) != placedTile
                        || w.isAirBlock(c.x, c.y, c.z)))
                        throw new IllegalStateException("Native composite installation is incomplete");
                    if (s.virtual && s.virtualPreset.schema >= 2
                        && Boolean.FALSE.equals(HologramCellPolicy.nativePanelMatches(s, c, true))) {
                        c.resourceError = "原生 NC 面板完整配置或传感卡目标未恢复，已回滚本格";
                        throw new java.io.IOException(c.resourceError);
                    }
                }
                if (scope != null && scope.savedNative
                    && (!scope.candidate(c) || !scopedMutationCurrent(s, c, transaction, scope))) {
                    if (!restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                    rollbackMaterials(s, transaction, inventory, scope);
                    return scopedRejected(s, c);
                }
                if (MinecraftForge.EVENT_BUS.post(new BlockEvent.PlaceEvent(snapshot, old, s.player))) {
                    if (!restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                    rollbackMaterials(s, transaction, inventory, scope);
                    c.status = "protected";
                    s.status = "领地/模组保护取消放置，该格已恢复，预留材料已归还";
                    return true;
                }
            }
            if (quantumPlacementPayload != null
                && "join".equals(quantumPlacementPayload.getString(HologramNetworkBindings.ROLE))) {
                placementStage = "network-join";
                newQuantumJoin = w.getTileEntity(c.x, c.y, c.z);
                if (newQuantumJoin == null) throw new java.io.IOException("Quantum incorporation target is missing");
                HologramNetworkBindings.bindJoin(newQuantumJoin, quantumPlacementPayload, s.player, s.networkBindings);
            }
            if ((scope == null || scope.savedNative) && s.virtual && s.mode != 2) {
                placementStage = "final-identity";
                HologramCellPolicy
                    .validateGtPlacedIdentity(energyTarget, w.getBlock(c.x, c.y, c.z), w.getTileEntity(c.x, c.y, c.z));
                if (s.virtualPreset != null && s.virtualPreset.schema >= 2) {
                    if (scope != null && scope.savedNative
                        && scope.stateful
                        && !HologramCellPolicy
                            .targetMatches(s, c, index, w.getBlock(c.x, c.y, c.z), w.getBlockMetadata(c.x, c.y, c.z))) {
                        // A listener changed the installed state. Never refund and erase an uncertain foreign
                        // candidate.
                        return retainScopedJournal(s, transaction, scope, "native-post-event-state-changed");
                    }
                    HologramCellPolicy.validateOrdinaryPlacedVariant(w, c, energyTarget);
                    if (Boolean.FALSE.equals(HologramCellPolicy.nativePanelMatches(s, c, true))) {
                        c.resourceError = "放置事件改变了原生 NC 面板完整配置或传感卡目标，已回滚本格";
                        throw new java.io.IOException(c.resourceError);
                    }
                }
            }
            if (scope != null) {
                if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
                if (scope.declared && scope.declaredFacing >= 0) {
                    TileEntity physical = w.getTileEntity(c.x, c.y, c.z);
                    if (!(physical instanceof IGregTechTileEntity gt) || gt.getFrontFacing() == null
                        || gt.getFrontFacing()
                            .ordinal() != scope.declaredFacing)
                        return retainScopedJournal(s, transaction, scope, "declared-hatch-facing-changed");
                }
                if (scope.endpoint(s)) scope.inventory.markDirty();
                if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
                boolean liveIntact = scope.livePrecommit == null
                    || (scope.current(s) && scope.livePrecommit.getAsBoolean());
                if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
                // Candidate, journal and slot must be proved after the additional live getter callbacks.
                boolean candidateIntact = scope.candidate(c) && c.block == scope.block && c.meta == scope.metadata;
                // The last live check may itself reenter through NetworkManager/Channel. Keep it before raw
                // data, even when it says no. Rollback still needs an exact original-world ownership witness.

                if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
                if (!scopedJournalOwned(s, transaction))
                    return retainScopedJournal(s, transaction, scope, "journal-ownership-changed");

                if (!candidateIntact)
                    return retainScopedJournal(s, transaction, scope, "candidate-changed-before-commit");
                // Forge and every final world callback have returned. Debit only from the confirmed original
                // slot and retire this exact receipt in one callback-free transition.
                if (!liveIntact || !scope.stockCurrent(s)
                    || !scope.current(s)
                    || !scope.selectedSources && !transaction.finishScopedPlain(scope.claim)) {
                    if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
                    if (changedWorld && !restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                    rollbackMaterials(s, transaction, inventory, scope);
                    return scopedRejected(s, c);
                }
                if (scope.selectedSources) {
                    try {
                        transaction.commit();
                    } catch (RuntimeException | LinkageError failure) {
                        return retainScopedJournal(s, transaction, scope, "selected-payment-commit-uncertain");
                    }
                }
                if (decorQuote != null) s.decorLedger.commit(decorQuote);
                HologramMaterialAccess.commitAssetBudget(s, index);
                if (voucherReserved) s.jobVoucherConsumed = true;
                c.status = "placed";
                return true;
            }
            if (decorQuote != null) s.decorLedger.commit(decorQuote);
            HologramMaterialAccess.commitAssetBudget(s, index);
            if (scope == null) s.player.inventory.markDirty();
            c.status = s.mode == 2 ? "removed" : empty ? "placed" : "replaced";
            if (!cacheNotice.isEmpty()) {
                if (s.blueprintEnergyNotice.isEmpty()) s.blueprintEnergyNotice = cacheNotice;
                else if (!s.blueprintEnergyNotice.contains(cacheNotice) && s.blueprintEnergyNotice.length() < 512)
                    s.blueprintEnergyNotice += "；" + cacheNotice;
                s.status = cacheNotice;
            }
            transaction.commit();
            if (voucherReserved) s.jobVoucherConsumed = true;
        } catch (java.io.IOException | RuntimeException | LinkageError e) {
            if (scope != null) {
                if (scope.declared) {
                    HologramPlacementDiagnostics.failure(s, c, index, placementStage, e);
                    if (Boolean.getBoolean("gtmb.v0111.nativeContractsOnly")) e.printStackTrace(System.err);
                }
                if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);

                if (newQuantumJoin != null) {
                    if (!scope.candidate(c))
                        return retainScopedJournal(s, transaction, scope, "scoped-quantum-join-candidate-changed");
                    try {
                        HologramNetworkBindings.rollbackJoin(newQuantumJoin, s.player);
                    } catch (java.io.IOException rollback) {
                        return retainScopedJournal(s, transaction, scope, "scoped-quantum-join-rollback-uncertain");
                    }
                }
                if (changedWorld && !restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
                rollbackMaterials(s, transaction, inventory, scope);
                return scopedRejected(s, c);
            }
            if (Boolean.getBoolean("gtmb.v0111.nativeContractsOnly")) e.printStackTrace(System.err);
            HologramPlacementDiagnostics.failure(s, c, index, placementStage, e);
            if (newQuantumJoin != null) {
                try {
                    HologramNetworkBindings.rollbackJoin(newQuantumJoin, s.player);
                } catch (java.io.IOException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            String nativeFailure = "";
            if (HologramNativePlacement.supported(c.block)) {
                if (Boolean.getBoolean("gtmb.hologram.smoketest")
                    || Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke")) e.printStackTrace(System.err);
                Throwable cause = e;
                for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++)
                    cause = cause.getCause();
                nativeFailure = cause.getClass()
                    .getSimpleName() + ": "
                    + String.valueOf(cause.getMessage());
                if (nativeFailure.length() > 240) nativeFailure = nativeFailure.substring(0, 240);
            }
            if (transaction.commitStarted()) {
                HologramConstructionJournal.suspend(s, transaction, e);
                return false;
            }
            if (changedWorld && !restoreCell(s, c, transaction, old, oldMeta, savedTile, scope)) return false;
            rollbackMaterials(s, transaction, inventory, scope);
            s.status = "施工异常，已还原该格；请检查部分完成结果";
            if (!nativeFailure.isEmpty()) s.status += " [" + nativeFailure + "]";
            return false;
        }
        // Delivery is outside the rollback region: ME insertion is an external, committed transfer.
        HologramMaterialAccess.materialSource(s)
            .recoverWorld(drops);
        for (ItemStack drop : drops) s.recovered.add(drop.copy());
        try {
            HologramMaterialAccess.materialSource(s)
                .flushRecovery();
        } catch (RuntimeException | LinkageError deliveryFailure) {
            s.status = "施工已完成；回收物保留在持久暂存，稍后继续交付";
        }
        return true;
    }

    static boolean sameWorldCell(World world, HologramCapture.Cell cell, Block block, int meta, TileEntity tile) {
        return world.blockExists(cell.x, cell.y, cell.z) && world.getBlock(cell.x, cell.y, cell.z) == block
            && world.getBlockMetadata(cell.x, cell.y, cell.z) == meta
            && world.getTileEntity(cell.x, cell.y, cell.z) == tile;
    }

    /** The reviewed target cells are the complete destructive scope; client coordinates are never accepted. */
    static void removeBlueprintObstructions(HologramSession s, NBTTagCompound action) throws java.io.IOException {
        if (!s.virtual || s.capture == null
            || !s.virtualPlacement
            || !s.virtualLocked
            || !s.blueprintAwaitingConfirmation
            || !s.placementReviewIssued
            || !action.hasKey("placementReviewNonce", 4)
            || action.getLong("placementReviewNonce") != s.placementReviewNonce
            || !action.hasKey("planRevision", 4)
            || action.getLong("planRevision") != s.planRevision
            || !s.player.capabilities.allowEdit
            || s.job == 1
            || s.job == 2) throw new java.io.IOException("请先锁定蓝图位置并核对当前清障范围");
        if (!HologramPlanPolicy.planCurrent(s)) {
            HologramResourcePlanner.refreshPlan(s);
            throw new java.io.IOException("现场已变化，请重新核对清障范围");
        }
        World world = s.player.worldObj;
        int removed = 0, protectedCount = 0;
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (!HologramSessionPolicy.inScope(s, cell, index) || cell.anchor
                || !world.blockExists(cell.x, cell.y, cell.z)
                || HologramCellPolicy.trivial(world, cell.x, cell.y, cell.z)) continue;
            Block block = world.getBlock(cell.x, cell.y, cell.z);
            int metadata = world.getBlockMetadata(cell.x, cell.y, cell.z);
            if (HologramCellPolicy.targetMatches(s, cell, index, block, metadata)) continue;
            TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            if (block.getBlockHardness(world, cell.x, cell.y, cell.z) < 0
                || !world.canMineBlock(s.player, cell.x, cell.y, cell.z)
                || !s.player.canPlayerEdit(cell.x, cell.y, cell.z, 1, HologramSessionPolicy.trigger(s))
                || tile != null && !HologramRecovery.supported(tile)) {
                protectedCount++;
                continue;
            }
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(
                cell.x,
                cell.y,
                cell.z,
                world,
                block,
                metadata,
                s.player);
            if (MinecraftForge.EVENT_BUS.post(event) || !sameWorldCell(world, cell, block, metadata, tile)) {
                protectedCount++;
                continue;
            }
            List<ItemStack> recovered = HologramMaterialAccess.drops(block, world, cell, metadata);
            NBTTagCompound saved = tile == null ? null : HologramNativeCapture.capture(tile);
            boolean changed = false;
            try {
                if (tile != null) {
                    changed = true;
                    if (!HologramRecovery.removeWithoutDrops(world, cell.x, cell.y, cell.z))
                        throw new java.io.IOException("无法移除受支持的占位设备");
                } else {
                    block.onBlockHarvested(world, cell.x, cell.y, cell.z, metadata, s.player);
                    if (!sameWorldCell(world, cell, block, metadata, tile)) {
                        protectedCount++;
                        continue;
                    }
                    changed = true;
                    if (!block.removedByPlayer(world, s.player, cell.x, cell.y, cell.z, false))
                        throw new java.io.IOException("方块拒绝清障");
                    block.onBlockDestroyedByPlayer(world, cell.x, cell.y, cell.z, metadata);
                }
            } catch (java.io.IOException | RuntimeException | LinkageError failure) {
                if (changed) HologramRecovery.restore(world, cell.x, cell.y, cell.z, block, metadata, saved);
                protectedCount++;
                continue;
            }
            // Persist recovery before attempting destination insertion. A full inventory cannot erase the refund.
            HologramMaterialAccess.materialSource(s)
                .recoverWorld(recovered);
            for (ItemStack drop : recovered) s.recovered.add(drop.copy());
            removed++;
        }
        List<ItemStack> obstructionRefunds = new ArrayList<>(s.recovered);
        HologramResourcePlanner.refreshPlan(s);
        s.recovered.addAll(obstructionRefunds);
        s.placementReviewNonce++;
        s.placementReviewIssued = true;
        s.status = "已清除 " + removed + " 格占位，回收物保留；" + protectedCount + " 格受保护或不可回收，需重新核对施工";
    }

    /** Forge's ordinary snapshot constructor invokes the live serializer; provide an already detached read-only tag. */
    static BlockSnapshot readOnlyBlockSnapshot(World world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        NBTTagCompound data = null;
        if (tile != null) try {
            data = HologramNativeCapture.capture(tile);
        } catch (java.io.IOException unsafe) {
            throw new IllegalStateException("Cannot safely capture rollback state", unsafe);
        }
        return new BlockSnapshot(world, x, y, z, world.getBlock(x, y, z), world.getBlockMetadata(x, y, z), data);
    }

    private static boolean restoreCell(HologramSession s, HologramCapture.Cell c,
        HologramMaterials.Transaction transaction, Block old, int metadata, NBTTagCompound tile, PlacementScope scope) {
        if (scope == null) return restoreCell(
            s,
            transaction,
            () -> HologramRecovery.restore(s.player.worldObj, c.x, c.y, c.z, old, metadata, tile));
        if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);

        boolean originalIntact = sameWorldCell(scope.world, c, old, metadata, null);
        boolean candidateIntact = !originalIntact && scope.candidate(c);
        if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
        if (!scopedJournalOwned(s, transaction))
            return retainScopedJournal(s, transaction, scope, "journal-ownership-changed");
        if (originalIntact) return true;
        if (!candidateIntact) return retainScopedJournal(s, transaction, scope, "candidate-changed-before-rollback");
        if (old != Blocks.air || tile != null)
            return retainScopedJournal(s, transaction, scope, "scoped-original-not-air");
        try {
            // The exact scoped candidate has no tile. One bound-world air setter is sufficient; neither remove
            // a tile separately nor signal a second explicit update. A successful setter return is not proof.
            scope.world.setBlock(c.x, c.y, c.z, Blocks.air, metadata, 3);
            if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
            boolean restored = sameWorldCell(scope.world, c, Blocks.air, metadata, null);
            if (!scope.worldCurrent(s)) return retainScopedJournal(s, transaction, scope);
            if (!scopedJournalOwned(s, transaction))
                return retainScopedJournal(s, transaction, scope, "journal-ownership-changed");
            return restored || retainScopedJournal(s, transaction, scope, "scoped-world-restore-not-exact");
        } catch (RuntimeException | LinkageError failure) {
            return retainScopedJournal(s, transaction, scope, "scoped-world-restore-failed");
        }
    }

    static boolean restoreCell(HologramSession s, HologramMaterials.Transaction transaction, Runnable restoration) {
        transaction.checkpoint();
        try {
            restoration.run();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            HologramConstructionJournal.suspend(s, transaction, failure);
            return false;
        }
    }

    private static HologramMaterials.Context materialSource(HologramSession s, PlacementScope scope) {
        return scope == null ? HologramMaterialAccess.materialSource(s) : scope.materials;
    }

    static void rollbackMaterials(HologramSession s, HologramMaterials.Transaction transaction, ItemStack[] inventory) {
        rollbackMaterials(s, transaction, inventory, null);
    }

    private static void rollbackMaterials(HologramSession s, HologramMaterials.Transaction transaction,
        ItemStack[] inventory, PlacementScope scope) {
        if (scope != null) {
            if (scope.selectedSources) {
                if (!scope.worldCurrent(s) || !scope.endpoint(s))
                    retainScopedJournal(s, transaction, scope, "selected-payment-owner-changed");
                else rollbackMaterials(s, transaction, inventory, null);
                return;
            }
            if (!scope.worldCurrent(s)) retainScopedJournal(s, transaction, scope);
            else if (!transaction.finishScopedPlain(null))
                retainScopedJournal(s, transaction, scope, "scoped-unpaid-receipt-not-clearable");
            return;
        }
        if (!transaction.hasJournal()) {
            try {
                transaction.rollbackExternal();
            } finally {
                HologramMaterialAccess.restoreInventory(s, inventory);
                HologramMaterialAccess.materialSource(s)
                    .afterInventoryRestore();
            }
            return;
        }
        if (transaction.uncertain()) {
            HologramConstructionJournal
                .suspend(s, transaction, new IllegalStateException("Native extraction outcome unknown"));
            return;
        }
        transaction.checkpoint();
        NBTTagCompound journal = HologramConstructionJournal.owner(s.player)
            .getCompoundTag(HologramConstructionJournal.KEY);
        journal.setTag(
            "settlementPayment",
            journal.getCompoundTag("payment")
                .copy());
        journal.setBoolean("settlementStarted", true);
        if (journal.getBoolean("durable")) HologramConstructionJournal.persist(s.player, journal);
        try {
            transaction.rollbackExternal();
            HologramMaterialAccess.restoreInventory(s, inventory);
            HologramMaterialAccess.materialSource(s)
                .afterInventoryRestore();
            HologramConstructionJournal.clear(s.player);
        } catch (RuntimeException | LinkageError failure) {
            HologramConstructionJournal.suspend(s, transaction, failure);
        }
    }
}
