package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
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
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.ICustomBlockSetting;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
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
        try {
            return operateChecked(s, c, index);
        } catch (RuntimeException | LinkageError failure) {
            if (Boolean.getBoolean("gtmb.v0111.nativeContractsOnly")) failure.printStackTrace(System.err);
            HologramPlacementDiagnostics.failure(s, c, index, "admission", failure);
            c.status = "unsupported";
            s.status = "设备状态或回收数据无法读取，该格未施工";
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static boolean operateChecked(HologramSession s, HologramCapture.Cell c, int index) {
        if (HologramSessionPolicy.ignoredPlacementAir(s, c, index)) {
            c.status = "satisfied";
            return true;
        }
        World w = s.player.worldObj;
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
        if (s.mode != 2 && ((c.unknown && !HologramCellPolicy.explicitTarget(s, c, index)
            && !s.autoTargets.containsKey(index)
            && !(!s.noHatches && s.channels.hasKey("gt_hatch", 3) && "hatch".equals(c.role)))
            || (c.element == null && !HologramCellPolicy.explicitTarget(s, c, index))
            || HologramCellPolicy.targetFor(s, c, index) == null)) {
            c.status = "unsupported";
            return true;
        }
        TileEntity originalTile = w.getTileEntity(c.x, c.y, c.z);
        List<ItemStack> drops = empty
            || (s.player.capabilities.isCreativeMode && w.getTileEntity(c.x, c.y, c.z) == null)
                ? Collections.emptyList()
                : HologramMaterialAccess.drops(old, w, c, oldMeta);
        ItemStack[] inventory = HologramMaterialAccess.copyInventory(s.player);
        NBTTagCompound savedTile = w.getTileEntity(c.x, c.y, c.z) == null ? null
            : (NBTTagCompound) drops.get(0)
                .getTagCompound()
                .getCompoundTag(HologramRecovery.TAG)
                .getCompoundTag("tile")
                .copy();
        BlockSnapshot snapshot = readOnlyBlockSnapshot(w, c.x, c.y, c.z);
        HologramMaterials.Transaction transaction = HologramMaterialAccess.materialSource(s)
            .begin()
            .protectAssets(s.pendingAssetBudget, s.cellAssetBudgets.getOrDefault(index, Collections.emptyMap()));
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
            decorDescriptor = HologramCellPolicy.decoration(s, c);
            boolean voucher = s.mode != 2 && s.virtual
                && s.virtualPreset != null
                && s.virtualPreset.schema >= 2
                && s.jobVoucherRequested
                && HologramDecorationVoucher.enabled();
            voucherShell = voucher && decorDescriptor == null
                && !HologramRecovery.hasSeal(energyTarget)
                && HologramDecorationVoucher.covered(c.block, energyTarget);
            boolean voucherDecoration = voucher && HologramDecorationVoucher.hasCovered(c.block, decorDescriptor);
            if (!s.player.capabilities.isCreativeMode && (voucherShell || voucherDecoration) && !s.jobVoucherConsumed) {
                // The existing inventory snapshot is the rollback receipt. A voucher is deliberately
                // paid from the main inventory, independently of ME/container material priority.
                if (!HologramDecorationVoucher.reserveMain(s.player)) {
                    rollbackMaterials(s, transaction, inventory);
                    c.status = "missing";
                    s.status = "装饰代用券已不足，该格未施工，未扣材料";
                    return false;
                }
                voucherReserved = true;
            }
            if (s.mode != 2 && decorDescriptor == null
                && !voucherShell
                && !HologramMaterialAccess.freeShell(s, c, HologramCellPolicy.targetFor(s, c, index))
                && (!empty || (s.virtual && s.virtualPreset.schema >= 2))
                && (!s.player.capabilities.isCreativeMode
                    || HologramRecovery.hasSeal(HologramCellPolicy.targetFor(s, c, index)))) {
                ItemStack required = HologramEssentia.shellMaterial(HologramCellPolicy.targetFor(s, c, index));
                if (!HologramMaterialAccess.fuzzyBlueprintShells(s, required) && transaction.takeOne(required, false))
                    reserved = required.copy();
                if (reserved == null && !HologramMaterialAccess.fuzzyBlueprintShells(s, required)) {
                    rollbackMaterials(s, transaction, inventory);
                    c.status = "missing";
                    s.status = "替换材料不足，旧方块已保留";
                    return false;
                }
            }
            if (s.mode != 2 && !s.player.capabilities.isCreativeMode)
                for (ItemStack cover : HologramCellPolicy.coverCosts(s, c)) {
                    if (!transaction.takeOne(cover, false)) {
                        rollbackMaterials(s, transaction, inventory);
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
                rollbackMaterials(s, transaction, inventory);
                c.status = "missing";
                s.status = "蓝图库存、流体或无线电网资源不足；该格未施工，已归还全部预留资源";
                return false;
            }
            if (s.mode != 2 && decorDescriptor != null && !s.player.capabilities.isCreativeMode) {
                decorQuote = s.decorLedger.quote(
                    voucherDecoration ? HologramDecorationVoucher.payable(c.block, decorDescriptor) : decorDescriptor,
                    (stack, limit) -> HologramMaterialAccess.decorativeAvailability(s, index, stack, limit));
                for (ItemStack payment : decorQuote.items) if (!transaction.takeAll(payment, false)) {
                    rollbackMaterials(s, transaction, inventory);
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
                    rollbackMaterials(s, transaction, inventory);
                    c.status = "missing";
                    s.status = "去标识设备材料不足，已归还本格预留的全部资源";
                    return false;
                }
                reserved = required.copy();
            }
            if (s.mode != 2 && s.virtual && s.virtualPreset.schema >= 2) {
                cachedPayload = HologramCellPolicy.cachePlan(s, c, index);
                cachePaid = cachedPayload.demand.empty() || transaction.takeCachedEnergy(cachedPayload.demand);
                if (!cachePaid) cacheNotice = "设备缓存余额不足，已按空缓存放置";
                if (!cachedPayload.warnings.isEmpty())
                    cacheNotice += (cacheNotice.isEmpty() ? "" : "；") + "未知缓存已安全省略：" + cachedPayload.warnings.get(0);
                if (!cachedPayload.intrinsicSource.isEmpty())
                    cacheNotice += (cacheNotice.isEmpty() ? "" : "；") + "保留原生固有能源";
            }
            if (reserved != null && !HologramRecovery.hasSeal(reserved)
                && s.virtual
                && s.virtualPreset.schema >= 2
                && !c.itemNbt.isEmpty())
                reserved.setTagCompound(
                    cachedPayload == null ? null : cachePaid ? cachedPayload.itemPaid : cachedPayload.itemEmpty);
            if (!empty) {
                placementStage = "remove";
                BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(c.x, c.y, c.z, w, old, oldMeta, s.player);
                if (MinecraftForge.EVENT_BUS.post(event)) {
                    rollbackMaterials(s, transaction, inventory);
                    c.status = "protected";
                    s.status = "领地/模组保护取消拆除，原方块保留，预留材料已归还";
                    return true;
                }
                if (!sameWorldCell(w, c, old, oldMeta, originalTile)) {
                    rollbackMaterials(s, transaction, inventory);
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
                    removed = HologramRecovery.removeWithoutDrops(w, c.x, c.y, c.z);
                } else {
                    boolean frame = old instanceof BlockFrameBox;
                    if (!frame) {
                        old.onBlockHarvested(w, c.x, c.y, c.z, oldMeta, s.player);
                        if (!sameWorldCell(w, c, old, oldMeta, originalTile)) {
                            rollbackMaterials(s, transaction, inventory);
                            c.status = "protected";
                            s.status = "采收回调已改变现场，该格保留，请重新扫描";
                            return true;
                        }
                    }
                    changedWorld = true;
                    removed = frame ? w.setBlockToAir(c.x, c.y, c.z)
                        : old.removedByPlayer(w, s.player, c.x, c.y, c.z, false);
                    if (removed) old.onBlockDestroyedByPlayer(w, c.x, c.y, c.z, oldMeta);
                }
                if (!removed) {
                    HologramRecovery.restore(w, c.x, c.y, c.z, old, oldMeta, savedTile);
                    rollbackMaterials(s, transaction, inventory);
                    c.status = "protected";
                    return true;
                }
            }
            if (s.mode != 2) {
                placementStage = "native-place";
                if (!HologramValues.same(energyTarget, HologramCellPolicy.targetFor(s, c, index)))
                    throw new IllegalStateException("Placement target changed after resource reservation");
                changedWorld = true;
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
                boolean directCreative = s.player.capabilities.isCreativeMode && !s.noHatches && pin == null;
                boolean customBlock = HologramCellPolicy.explicitTarget(s, c, index) && pin != null
                    && !(pin.getItem() instanceof ItemMachines)
                    && HologramCellPolicy.acceptableSample(s, c, pin);
                PlaceResult result;
                boolean nativeCancelled;
                boolean nativePlacement = s.virtual && s.virtualPreset.schema >= 2
                    && HologramNativePlacement.supported(c.block);
                try (HologramNativePlacementEvents.Scope events = HologramNativePlacementEvents
                    .open(w, s.player, c.x, c.y, c.z)) {
                    if (nativePlacement) {
                        // The complete component plan was reserved above, including the primary material.
                        result = (decorDescriptor != null || HologramMaterialAccess.freeShell(s, c, pin)
                            || s.player.capabilities.isCreativeMode && !HologramRecovery.hasSeal(pin)
                            || (reservation == null && HologramMaterialAccess.fuzzyBlueprintShells(s, pin)
                                ? transaction.takeOneFuzzy(pin, false)
                                : source.takeOne(pin, false)))
                            && w.setBlock(c.x, c.y, c.z, c.block, c.meta, 3) ? PlaceResult.ACCEPT : PlaceResult.REJECT;
                    } else if (directCreative) result = c.element
                        .placeBlock(s.machine, w, c.x, c.y, c.z, HologramCellPolicy.elementTrigger(s, c))
                            ? PlaceResult.ACCEPT
                            : PlaceResult.REJECT;
                    else if (customBlock) {
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
                    } else if ((HologramCellPolicy.explicitTarget(s, c, index) || s.autoTargets.containsKey(index))
                        && pin.getItem() instanceof ItemMachines
                        && HologramCellPolicy.acceptableSample(s, c, pin)) {
                            // Explicit overrides use the legal GT item directly, without structure candidate filters.
                            // Manual choices do not use the automatic atLeast fill quota.
                            result = StructureUtility.survivalPlaceBlock(
                                pin,
                                HologramMaterialAccess.fuzzyBlueprintShells(s, pin) ? ItemStackPredicate.NBTMode.IGNORE
                                    : ItemStackPredicate.NBTMode.EXACT,
                                null,
                                true,
                                w,
                                c.x,
                                c.y,
                                c.z,
                                source,
                                s.player);
                        } else {
                            IStructureElement placement = pin.getItem() instanceof ItemMachines
                                ? HologramCellPolicy.hatchElement(s, c, pin, false)
                                : c.element;
                            result = placement == null ? PlaceResult.REJECT
                                : placement.survivalPlaceBlock(
                                    s.machine,
                                    w,
                                    c.x,
                                    c.y,
                                    c.z,
                                    HologramCellPolicy.elementTrigger(s, c),
                                    AutoPlaceEnvironment.fromLegacy(source, s.player, s.player::addChatMessage));
                        }
                    nativeCancelled = events.cancelled();
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
                    HologramRecovery.restore(w, c.x, c.y, c.z, old, oldMeta, savedTile);
                    rollbackMaterials(s, transaction, inventory);
                    s.defer = true;
                    c.status = "pending";
                    s.status = "原版元素要求等待下一 tick";
                    return false;
                }
                if (result == PlaceResult.ACCEPT_STOP) s.stopTick = true;
                if (s.virtual && s.virtualPreset.schema >= 2
                    && (nativePlacement || pin.getItem() instanceof ItemBlock
                        || w.getBlock(c.x, c.y, c.z) == Block.getBlockFromItem(pin.getItem()))
                    && (result == PlaceResult.ACCEPT || result == PlaceResult.ACCEPT_STOP
                        || result == PlaceResult.SKIP)) {
                    int plannedMetadata = HologramCellPolicy.targetPreviewMeta(s, c, pin);
                    HologramPresets.validateMaterialVariant(w.getBlock(c.x, c.y, c.z), plannedMetadata, pin);
                    w.setBlockMetadataWithNotify(c.x, c.y, c.z, plannedMetadata, 2);
                }
                if ((result != PlaceResult.ACCEPT && result != PlaceResult.ACCEPT_STOP && result != PlaceResult.SKIP)
                    || (!nativePlacement && w.isAirBlock(c.x, c.y, c.z))
                    || (nativePlacement
                        && (w.getBlock(c.x, c.y, c.z) != c.block || w.getBlockMetadata(c.x, c.y, c.z) != c.meta))
                    || (reservation != null && !reservation.used)
                    || (pin != null && !nativePlacement
                        && !HologramCellPolicy.targetMatches(
                            s,
                            c,
                            index,
                            w.getBlock(c.x, c.y, c.z),
                            w.getBlockMetadata(c.x, c.y, c.z),
                            false))) {
                    HologramPlacementDiagnostics
                        .rejected(s, c, index, result.name(), nativeCancelled, reservation == null || reservation.used);
                    HologramRecovery.restore(w, c.x, c.y, c.z, old, oldMeta, savedTile);
                    rollbackMaterials(s, transaction, inventory);
                    c.status = nativeCancelled ? "protected"
                        : result == PlaceResult.REJECT_CONTINUE ? "unsupported" : "missing";
                    s.status = nativeCancelled ? "领地/模组保护取消了原生放置；该格已还原，材料已完整退回" : "原版元素拒绝施工；材料和旧方块已还原";
                    return false;
                }
                Block placed = w.getBlock(c.x, c.y, c.z);
                placementStage = "native-callbacks";
                if (placed instanceof ICustomBlockSetting || directCreative) {
                    ItemStack placedStack = new ItemStack(placed, 1, w.getBlockMetadata(c.x, c.y, c.z));
                    placed.onBlockPlacedBy(w, c.x, c.y, c.z, s.player, placedStack);
                    placed.onPostBlockPlaced(w, c.x, c.y, c.z, w.getBlockMetadata(c.x, c.y, c.z));
                }
                if (!HologramRecovery.restorePlaced(w, c.x, c.y, c.z, pin)
                    || (HologramRecovery.hasSeal(pin) && !HologramRecovery.matchesPlaced(w, c.x, c.y, c.z, pin)))
                    throw new IllegalStateException("stored device restore rejected");
                Integer direction = s.planFacings.get(index);
                TileEntity placedTile = w.getTileEntity(c.x, c.y, c.z);
                if (s.virtual && placedTile instanceof IGregTechTileEntity) {
                    IGregTechTileEntity ownerHost = (IGregTechTileEntity) placedTile;
                    ownerHost.setOwnerUuid(s.player.getUniqueID());
                    ownerHost.setOwnerName(s.player.getCommandSenderName());
                }
                boolean blueprintTileDataApplied = false;
                boolean emptyAeNativeShell = nativePlacement && HologramAeBlueprint.supportsEmptyNativeShell(c.block);
                NBTTagCompound selectedCacheTile = cachedPayload == null ? null
                    : cachePaid ? cachedPayload.tilePaid : cachedPayload.tileEmpty;
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
                                .ordinal() != direction) throw new IllegalStateException("blueprint front rejected");
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
                if (MinecraftForge.EVENT_BUS.post(new BlockEvent.PlaceEvent(snapshot, old, s.player))) {
                    HologramRecovery.restore(w, c.x, c.y, c.z, old, oldMeta, savedTile);
                    rollbackMaterials(s, transaction, inventory);
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
            if (s.virtual && s.mode != 2) {
                placementStage = "final-identity";
                HologramCellPolicy
                    .validateGtPlacedIdentity(energyTarget, w.getBlock(c.x, c.y, c.z), w.getTileEntity(c.x, c.y, c.z));
                if (s.virtualPreset != null && s.virtualPreset.schema >= 2) {
                    HologramCellPolicy.validateOrdinaryPlacedVariant(w, c, energyTarget);
                    if (Boolean.FALSE.equals(HologramCellPolicy.nativePanelMatches(s, c, true))) {
                        c.resourceError = "放置事件改变了原生 NC 面板完整配置或传感卡目标，已回滚本格";
                        throw new java.io.IOException(c.resourceError);
                    }
                }
            }
            transaction.commit();
            if (decorQuote != null) s.decorLedger.commit(decorQuote);
            HologramMaterialAccess.commitAssetBudget(s, index);
            s.player.inventory.markDirty();
            c.status = s.mode == 2 ? "removed" : empty ? "placed" : "replaced";
            if (!cacheNotice.isEmpty()) {
                if (s.blueprintEnergyNotice.isEmpty()) s.blueprintEnergyNotice = cacheNotice;
                else if (!s.blueprintEnergyNotice.contains(cacheNotice) && s.blueprintEnergyNotice.length() < 512)
                    s.blueprintEnergyNotice += "；" + cacheNotice;
                s.status = cacheNotice;
            }
            if (voucherReserved) s.jobVoucherConsumed = true;
        } catch (java.io.IOException | RuntimeException | LinkageError e) {
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
            if (changedWorld) HologramRecovery.restore(w, c.x, c.y, c.z, old, oldMeta, savedTile);
            rollbackMaterials(s, transaction, inventory);
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

    static void rollbackMaterials(HologramSession s, HologramMaterials.Transaction transaction, ItemStack[] inventory) {
        try {
            transaction.rollbackExternal();
        } finally {
            HologramMaterialAccess.restoreInventory(s, inventory);
            HologramMaterialAccess.materialSource(s)
                .afterInventoryRestore();
        }
    }
}
