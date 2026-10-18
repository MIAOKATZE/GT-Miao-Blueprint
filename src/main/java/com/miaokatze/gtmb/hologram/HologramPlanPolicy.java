package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Read-only world differential classification and preflight authorization. */
final class HologramPlanPolicy {

    private HologramPlanPolicy() {}

    /** Pure preflight: never posts Forge events or calls element.check/checkMachine. */
    static String classify(HologramSession s, HologramCapture.Cell c, int index) {
        if (HologramSessionPolicy.ignoredPlacementAir(s, c, index)) return "satisfied";
        World w = s.player.worldObj;
        boolean loaded = w.blockExists(c.x, c.y, c.z);
        return classify(
            s,
            c,
            index,
            loaded,
            loaded ? w.getBlock(c.x, c.y, c.z) : Blocks.air,
            loaded ? w.getBlockMetadata(c.x, c.y, c.z) : 0,
            loaded ? w.getTileEntity(c.x, c.y, c.z) : null);
    }

    /** One atomic classification reuses its just-read primary state; native permissions remain live callbacks. */
    static String classify(HologramSession s, HologramCapture.Cell c, int index, boolean loaded, Block old, int meta,
        TileEntity tile) {
        if (HologramSessionPolicy.ignoredPlacementAir(s, c, index)) return "satisfied";
        World w = s.player.worldObj;
        if (c.y < 0 || c.y >= w.getHeight() || !loaded) return "unsupported";
        if (c.anchor) return "protected";
        // Native payload errors must survive even when their primary material cannot be sampled.
        boolean resourceChecked = s.mode != 2 && s.virtual
            && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && HologramNativePlacement.supported(c.block);
        if (resourceChecked && !HologramCellPolicy.resourcePlanValid(s, c)) return "unsupported";
        boolean approvedNativePrimary = false;
        boolean sampleApproved = false;
        if ((s.virtual || !s.areaStructureId.isEmpty()) && s.mode != 2
            && HologramCellPolicy.targetFor(s, c, index) != null) {
            ItemStack proposed = HologramCellPolicy.targetFor(s, c, index);
            if (!HologramCellPolicy.acceptableSample(s, c, proposed)) return "unsupported";
            sampleApproved = true;
            // placeableSample already validated this exact primary against its registered native material provider.
            approvedNativePrimary = s.virtual && s.virtualPreset != null
                && s.virtualPreset.schema >= 2
                && !(proposed.getItem() instanceof ItemMachines)
                && !c.tileNbt.isEmpty()
                && HologramNativePlacement.adapter(c.block) != null;
        }
        if (c.unknown && HologramCellPolicy.targetFor(s, c, index) == null) return "unsupported";
        if (!c.sourceWarning.isEmpty() && "templateController".equals(c.role)) return "unsupported";
        if ("templateController".equals(c.role) && !HologramCellPolicy.explicitTarget(s, c, index))
            return "unsupported";
        if (!HologramCellPolicy.coverCostsValid(s, c)
            || !resourceChecked && !HologramCellPolicy.resourcePlanValid(s, c)) return "unsupported";
        if (s.mode == 1 && (!s.replacementReady || !HologramCellPolicy.originalMember(s, c))) return "protected";
        if (c.userPosition && !HologramCellPolicy.explicitTarget(s, c, index)) return "satisfied";
        Boolean matches = null;
        if (s.mode == 0 && !s.player.capabilities.isCreativeMode
            && !HologramCellPolicy.trivial(w, c.x, c.y, c.z)
            && (HologramCellPolicy.targetFor(s, c, index) != null || c.block == Blocks.air && !c.unknown)) {
            matches = HologramCellPolicy.targetMatches(s, c, index, old, meta);
            if (!matches) return "blocked";
        }
        if (s.mode == 2 && c.block == Blocks.air && !HologramCellPolicy.explicitTarget(s, c, index)) return "satisfied";
        // Keeping a matching tile requires no harvest authority; unmatched tiles remain protected below.
        if (s.mode == 0 && (matches != null ? matches : HologramCellPolicy.targetMatches(s, c, index, old, meta)))
            return "satisfied";
        if (tile != null && !HologramCellPolicy.recoverable(s, c)) return "protected";
        if (!w.canMineBlock(s.player, c.x, c.y, c.z)
            || !s.player.canPlayerEdit(c.x, c.y, c.z, 1, HologramSessionPolicy.trigger(s))) return "protected";
        boolean empty = HologramCellPolicy.trivial(w, c.x, c.y, c.z);
        if (s.mode == 2) return empty ? "satisfied" : HologramCellPolicy.removable(s, c) ? "pending" : "protected";
        if ((c.element == null && !HologramCellPolicy.explicitTarget(s, c, index))
            || (c.unknown && !HologramCellPolicy.explicitTarget(s, c, index)
                && !s.autoTargets.containsKey(index)
                && !(!s.noHatches && s.channels.hasKey("gt_hatch", 3) && "hatch".equals(c.role))))
            return "unsupported";
        if (HologramCellPolicy.targetSatisfied(s, c, index, old, meta)) return "satisfied";
        if (s.facingPins.containsKey(HologramValues.position(c))
            && HologramCellPolicy.targetMatches(s, c, index, old, meta)) return s.mode == 1 ? "pending" : "satisfied";
        ItemStack target = HologramCellPolicy.targetFor(s, c, index);
        if (target == null || Block.getBlockFromItem(target.getItem()) == Blocks.air && !approvedNativePrimary)
            return "unsupported";
        if (HologramCellPolicy.explicitTarget(s, c, index)) {
            if (!sampleApproved && !HologramCellPolicy.acceptableSample(s, c, target)) return "unsupported";
        }
        if (empty) return s.mode == 1 ? "protected" : "pending";
        if (s.mode == 0 && !s.player.capabilities.isCreativeMode) return "blocked";
        if (!HologramCellPolicy.removable(s, c)) return "protected";
        return sampleApproved || HologramCellPolicy.acceptableSample(s, c, target) ? "pending" : "protected";
    }

    static String reason(HologramSession s, HologramCapture.Cell c, int index) {
        return reasonCurrent(s, c, index, null, null);
    }

    static String reason(HologramSession s, HologramCapture.Cell c, int index, boolean loaded, TileEntity tile) {
        return reasonCurrent(s, c, index, loaded, tile);
    }

    private static String reasonCurrent(HologramSession s, HologramCapture.Cell c, int index, Boolean loaded,
        TileEntity tile) {
        if (HologramSessionPolicy.ignoredPlacementAir(s, c, index)) return "蓝图空气不参与施工，保留现场";
        if (!c.sourceWarning.isEmpty() && "templateController".equals(c.role)) return c.sourceWarning;
        if ("templateController".equals(c.role) && !HologramCellPolicy.explicitTarget(s, c, index))
            return "控制器缺少合法施工目标；请重新载入原图纸并核对来源控制器身份";
        if (!c.resourceError.isEmpty()) return c.resourceError + "；取消对应 NBT 条目后可降级放置";
        World w = s.player.worldObj;
        if (!(loaded == null ? w.blockExists(c.x, c.y, c.z) : loaded)) return "区块未加载";
        if (c.anchor) return "始终保留控制器锚点";
        if ("blocked".equals(c.status)) return "结构占位被遮挡，生存补建不会替换旧方块，请清空后重试";
        if (s.mode == 1 && (!s.replacementReady || !HologramCellPolicy.originalMember(s, c)))
            return "替换仅限已完整构建的原结构位置；新增位置与声明空气保持现场";
        if (s.mode == 1 && HologramCellPolicy.orientationOnly(s, c, index)) return "仅调整现有仓室朝向，不拆除、不扣材料";
        if (c.userPosition && !HologramCellPolicy.explicitTarget(s, c, index)) return "新增位置尚未指定目标，保持现场";
        if (s.mode == 2 && c.block == Blocks.air && !HologramCellPolicy.explicitTarget(s, c, index))
            return "声明空气位置保持现场";
        if ((loaded == null ? w.getTileEntity(c.x, c.y, c.z) : tile) != null)
            return HologramCellPolicy.recoverable(s, c) ? "完整封存设备状态、库存、流体与覆盖板后回收" : "此设备尚无完整状态回收路径";
        if ("unsupported".equals(c.status) || (c.element == null && !HologramCellPolicy.explicitTarget(s, c, index)))
            return "此元素未提供安全施工目标";
        if ("protected".equals(c.status)) return "权限限制或旧块不属于受支持结构族";
        if ("satisfied".equals(c.status)) return "现有方块满足目标，保留";
        return "逐格重验；Forge 事件仅在实际操作时处理";
    }

    static boolean expectedCurrent(HologramSession s, int index) {
        HologramCapture.Cell c = s.capture.cells.get(index);
        if (HologramSessionPolicy.ignoredPlacementAir(s, c, index)) return true;
        World w = s.player.worldObj;
        return w.blockExists(c.x, c.y, c.z) && w.getBlock(c.x, c.y, c.z) == s.expectedBlocks.get(index)
            && w.getBlockMetadata(c.x, c.y, c.z) == s.expectedMeta.get(index)
            && w.getTileEntity(c.x, c.y, c.z) == s.expectedTiles.get(index)
            && (!(s.expectedTiles.get(index) instanceof IGregTechTileEntity)
                || ((IGregTechTileEntity) s.expectedTiles.get(index)).getFrontFacing()
                    .ordinal() == s.expectedFacings.get(index));
    }

    static boolean planCurrent(HologramSession s) {
        if (s.capture == null || s.expectedBlocks.size() != s.capture.cells.size()) return false;
        for (int i = 0; i < s.capture.cells.size(); i++)
            if (HologramSessionPolicy.inScope(s, s.capture.cells.get(i), i) && !expectedCurrent(s, i)) return false;
        return true;
    }

    static boolean structureBuilt(HologramSession s) {
        return !s.virtual && s.machine != null && (s.machine.mMachine || replacementStructureBuilt(s));
    }

    /** Verify the original declaration without invoking a mutating machine check. */
    static boolean replacementStructureBuilt(HologramSession s) {
        if (s.virtual || s.machine == null || s.target == null) return false;
        HologramCapture original = s.capture;
        if (original == null || original.pending || original.incomplete) return false;
        boolean found = false;
        for (HologramCapture.Cell cell : original.cells) {
            int built = replacementCellBuilt(s, cell);
            if (built < 0) return false;
            found |= built > 0;
        }
        return found;
    }

    /** 0: ignored declaration; 1: verified member; -1: original structure is incomplete. */
    static int replacementCellBuilt(HologramSession s, HologramCapture.Cell cell) {
        if (cell.anchor || cell.userPosition) return 0;
        if (!s.player.worldObj.blockExists(cell.x, cell.y, cell.z)) return -1;
        Block block = s.player.worldObj.getBlock(cell.x, cell.y, cell.z);
        TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
        if (tile instanceof IGregTechTileEntity
            && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEHatch) {
            if (HologramCellPolicy.hatchElement(
                s,
                cell,
                new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) tile).getMetaTileID()),
                true) == null) return -1;
        } else {
            if (cell.unknown || cell.element == null || cell.block == null) return -1;
            if (cell.block == Blocks.air) {
                if (!HologramCellPolicy.trivial(s.player.worldObj, cell.x, cell.y, cell.z)) return -1;
                return 0;
            }
            int meta = s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z);
            if (tile != null && !HologramFrameSupport.acceptsCovered(cell.element, block, meta)) return -1;
            if (!HologramCellPolicy.knownSatisfied(cell, block, meta)
                && !HologramCellPolicy.destructive(cell, block, meta)) return -1;
        }
        return 1;
    }

    static boolean constructionAllowed(HologramSession s) {
        if (HologramSessionPolicy.blockedByAreaImport(s)) return false;
        if (s.virtual && (!s.virtualPlacement || !s.virtualLocked || s.mode == 2)) {
            s.status = "请先放置蓝图，右击确定位置并核对材料后施工";
            return false;
        }
        if (s.mode == 1 && !replacementStructureBuilt(s)) {
            s.status = "替换需要绑定真实控制器且原结构完整构建，请先补建或重新扫描";
            return false;
        }
        int blocked = 0;
        if (s.mode == 0 && !s.player.capabilities.isCreativeMode) for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (HologramSessionPolicy.inScope(s, cell, i) && "blocked".equals(classify(s, cell, i))) blocked++;
        }
        if (blocked == 0) return true;
        s.status = "选定范围有 " + blocked + " 格结构占位被遮挡，已整体拒绝补建，请清空后重试";
        return false;
    }

    static boolean replacementSnapshotCurrent(HologramSession s) {
        if (!s.replacementAuthorized || s.capture == null || s.expectedBlocks.size() != s.capture.cells.size())
            return false;
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            // Completed transaction cells no longer have their original block; remaining authority stays frozen.
            if (!"replaced".equals(cell.status) && !"placed".equals(cell.status) && !expectedCurrent(s, i))
                return false;
        }
        return true;
    }
}
