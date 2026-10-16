package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Preset serialization, selection, loading and virtual blueprint materialization. */
final class HologramPresetWorkflow {

    private HologramPresetWorkflow() {}

    /** Capture current server targets; all inventory and device data come from server-owned state. */

    static void savePreset(HologramSession s, String name) {
        HologramPresets.Preset directSource = null;
        if (!s.processingReviewed) {
            try {
                directSource = HologramAuthoringQueries.processingSource(s);
                HologramAuthoringWorkflow.requireBlueprintProcessing(s, directSource);
            } catch (java.io.IOException rejected) {
                s.status = rejected.getMessage();
                return;
            }
        }
        if (s.virtual) {
            saveVirtualPreset(s, name, false);
            return;
        }
        if (s.capture == null || s.capture.incomplete
            || s.capturePending
            || s.virtual
            || s.machine == null
            || !(s.target instanceof IGregTechTileEntity)) {
            s.status = "请从实体控制器完整采集结构后保存预设";
            return;
        }
        HologramPresets.Preset preset = new HologramPresets.Preset();
        if (directSource != null) preset.processingNbt = directSource.processingNbt;
        preset.name = name == null ? "" : name.trim();
        preset.prompt = s.blueprintPrompt;
        preset.hintAreas.addAll(s.blueprintHintAreas);
        preset.controllerType = HologramSessionPolicy.controllerType(s);
        preset.controllerId = ((IGregTechTileEntity) s.target).getMetaTileID();
        preset.controllerFacing = s.machine.getExtendedFacing()
            .ordinal();
        java.util.Set<String> palette = new java.util.HashSet<>();
        try {
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                ItemStack target;
                int direction = s.facingPins
                    .getOrDefault(HologramValues.position(cell), s.planFacings.getOrDefault(i, -1));
                if (cell.anchor) {
                    target = new ItemStack(GregTechAPI.sBlockMachines, 1, preset.controllerId);
                    direction = HologramBlockIdentity.front((IGregTechTileEntity) s.target);
                } else {
                    if (cell.block == Blocks.air && !s.pins.containsKey(i)) continue;
                    if (cell.unknown && !s.pins.containsKey(i)) {
                        s.status = "预设存在没有安全目标的未知元素，请先明确选择合法目标";
                        return;
                    }
                    target = HologramCellPolicy.targetFor(s, cell, i);
                    if (target == null || !HologramCellPolicy.acceptableSample(s, cell, target)) {
                        s.status = "存在无法安全保存的结构目标；预设未写入";
                        return;
                    }
                }
                if (!cell.anchor) {
                    TileEntity actual = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
                    Block placed = Block.getBlockFromItem(target.getItem());
                    if ((actual != null && !HologramRecovery.supported(actual))
                        || (!(target.getItem() instanceof ItemMachines) && placed.hasTileEntity(
                            target.getItem()
                                .getMetadata(target.getItemDamage())))) {
                        s.status = "预设含未支持的方块实体，不能保存为普通物品模板";
                        return;
                    }
                }
                HologramPresets.Cell saved = HologramPresets
                    .cell(target, cell.x - s.x, cell.y - s.y, cell.z - s.z, direction, cell.anchor);
                saved.prompt = cell.prompt;
                saved.excluded = cell.excluded;
                preset.cells.add(saved);
                HologramBlueprintData.addPalette(preset, palette, target);
                if (!cell.anchor) {
                    HologramCasingFallback.Target casing = HologramCasingFallback
                        .resolve(cell.element, HologramCellPolicy.elementTrigger(s, cell));
                    ItemStack shell = casing == null ? null : new ItemStack(casing.block, 1, casing.meta);
                    if (shell == null && !cell.unknown
                        && !"hatch".equals(cell.role)
                        && cell.block != null
                        && cell.block != Blocks.air) shell = new ItemStack(cell.block, 1, cell.meta);
                    if (HologramCellPolicy.acceptableSample(s, cell, shell)
                        && !(shell.getItem() instanceof ItemMachines)) {
                        saved.defaultItem = String.valueOf(Item.itemRegistry.getNameForObject(shell.getItem()));
                        saved.defaultDamage = shell.getItemDamage();
                        HologramBlueprintData.addPalette(preset, palette, shell);
                    }
                    if (cell.block != null && cell.block != Blocks.air) {
                        ItemStack original = new ItemStack(cell.block, 1, cell.meta);
                        if (HologramCellPolicy.acceptableSample(s, cell, original))
                            HologramBlueprintData.addPalette(preset, palette, original);
                    }
                    for (ItemStack candidate : HologramCellPolicy.candidates(s, cell))
                        if (HologramCellPolicy.acceptableSample(s, cell, candidate))
                            HologramBlueprintData.addPalette(preset, palette, candidate);
                }
            }
            // Some constructables omit the controller from their walked pieces. Save its server-known identity once.
            boolean controllerSaved = false;
            for (HologramPresets.Cell cell : preset.cells) if (cell.controller) controllerSaved = true;
            if (!controllerSaved) {
                ItemStack target = new ItemStack(GregTechAPI.sBlockMachines, 1, preset.controllerId);
                preset.cells.add(
                    HologramPresets
                        .cell(target, 0, 0, 0, HologramBlockIdentity.front((IGregTechTileEntity) s.target), true));
                HologramBlueprintData.addPalette(preset, palette, target);
            }
            HologramBlueprintMetadata.captureVersions(preset);
            HologramLibraryWorkflow.saveReviewedArtifact(s, preset, "savePreset", false, false, true);
        } catch (java.io.IOException invalid) {
            s.status = "预设保存失败：" + invalid.getMessage();
        }
    }

    static void loadPreset(HologramSession s, String id) {
        loadPreset(s, id, true);
    }

    static void loadPreset(HologramSession s, String id, boolean selectOnly) {
        try {
            HologramPresets.Preset preset = HologramLibraryFacade.get(s.player, id);
            if (preset == null) throw new java.io.IOException("预设不存在");
            if (preset.schema < 2
                && (preset.controllerId < 0 || preset.controllerId >= GregTechAPI.METATILEENTITIES.length
                    || !(GregTechAPI.METATILEENTITIES[preset.controllerId] instanceof MTEEnhancedMultiBlockBase)
                    || !preset.controllerType.equals(
                        GregTechAPI.METATILEENTITIES[preset.controllerId].getClass()
                            .getName() + ":" + preset.controllerId)))
                throw new java.io.IOException("模板控制器身份已失效");
            if (selectOnly) {
                selectPreset(s, id);
                presetGuidance(s, "蓝图已选定；点击放置进入位置预览，或打开蓝图继续编辑。");
                return;
            }
            HologramBlueprintData.detachAreaOverview(s);
            s.virtualPreset = preset;
            s.processingSource = HologramPresets.copy(preset);
            s.blueprintSaveReceipt = "";
            s.processingPolicy = HologramBlueprintProcessing.policy(preset);
            s.processingReviewed = false;
            s.processingWorkflowStarted = false;
            s.decorLedger = new HologramDecorMaterials.Ledger();
            s.blueprintDirty = false;
            s.blueprintSource = "saved";
            HologramAuthoringWorkflow.clearSourceSwitch(s);
            s.libraryInitialSnapshot = HologramLibraryFacade.enabled() && id.startsWith("public_");
            if (!id.startsWith("private_")) {
                s.libraryLocalId = "";
                s.libraryPrivatePreset = null;
            }
            s.retainedNbtKeys = preset.retainedNbtKeys;
            s.placementNbtCustomized = false;
            s.placementNbtKeys = null;
            s.placementRetainCovers = null;
            s.presetBrowseForPlacement = false;
            s.virtualLocked = false;
            s.placementReviewIssued = false;
            s.cancelled = false;
            s.open = !s.virtualPlacement;
            s.settingsOnly = false;
            s.mode = 0;
            s.scope = 0;
            HologramSessionPolicy.currentTool(s)
                .getTagCompound()
                .setString(HologramSessionKeys.SELECTED_PRESET, id);
            HologramBlueprintProjection.populateVirtual(s);
            s.status = s.virtualPlacement ? "预设已加载；投影跟随鼠标，锁定位置后施工" : "预设已加载，仅编辑模板";
            if (s.virtualPlacement) presetGuidance(s, "位置预览跟随鼠标：R 水平旋转，Ctrl + R 垂直旋转；右键锁定，再次右键核对材料与 NBT，Esc 取消放置。");
        } catch (java.io.IOException invalid) {
            s.status = "预设加载失败：" + invalid.getMessage();
        }
    }

    /** Guidance is emitted only by explicit player actions, never by scan or tick. */
    static void selectPreset(HologramSession s, String id) {
        HologramSessionPolicy.currentTool(s)
            .getTagCompound()
            .setString(HologramSessionKeys.SELECTED_PRESET, id);
        s.status = "蓝图已选定；点击放置进入位置预览";
        s.player.inventory.markDirty();
    }

    /** Guidance is emitted only by explicit player actions, never by scan or tick. */
    static void presetGuidance(HologramSession s, String message) {
        s.player.addChatMessage(new ChatComponentText("[猫猫全息·自定义预设] " + message));
    }

    static HologramSession editPreset(HologramSession previous, String id) {
        int x = previous.target != null || previous.virtual ? previous.x
            : net.minecraft.util.MathHelper.floor_double(previous.player.posX);
        int y = previous.target != null || previous.virtual ? previous.y
            : HologramValues.clamp(
                net.minecraft.util.MathHelper.floor_double(previous.player.posY),
                0,
                previous.player.worldObj.getHeight() - 1);
        int z = previous.target != null || previous.virtual ? previous.z
            : net.minecraft.util.MathHelper.floor_double(previous.player.posZ);
        if (!previous.player.worldObj.blockExists(x, y, z)
            || previous.player.getDistanceSq(x + .5, y + .5, z + .5) > 4096) {
            previous.status = "编辑锚点未加载或超出距离限制";
            return previous;
        }
        HologramSession editing = HologramWorkspace.create(previous.player, x, y, z, false, previous.side);
        HologramLibraryWorkflow.copyLibraryIntent(previous, editing);
        editing.virtual = true;
        loadPreset(editing, id, false);
        if (editing.virtualPreset == null) {
            previous.status = editing.status;
            return previous;
        }
        editing.open = true;
        editing.openPurpose = "blueprintEdit";
        editing.settingsOnly = false;
        editing.status = "已打开自定义预设编辑；保存回原预设或另存为，仅保存规划，不施工";
        HologramWorkspace.activate(previous.player.getUniqueID(), editing);
        presetGuidance(editing, "编辑完成后可保存或另存为；点击放置预览位置，右击确定位置，再核对材料并确认施工。");
        return editing;
    }

    static void saveVirtualPreset(HologramSession s, String name, boolean update) {
        if (!s.virtual || s.virtualPreset == null || s.capture == null || s.capture.incomplete || s.capturePending) {
            s.status = "请先完整载入自定义预设进行编辑";
            return;
        }
        try {
            HologramPresets.Preset preset = HologramBlueprintProjection.editedVirtualPreset(
                s,
                update && (name == null || name.trim()
                    .isEmpty()) ? s.virtualPreset.name : name);
            if (update) preset.id = s.virtualPreset.id;
            HologramAuthoringWorkflow.beginAeSaveReview(s, preset, update ? "updatePreset" : "saveBlueprintAs", update);
        } catch (java.io.IOException invalid) {
            s.status = "预设保存失败：" + invalid.getMessage();
        }
    }

    /** A legacy template controller identifies one whole footprint; absence of tags never makes its shell loose. */

}
