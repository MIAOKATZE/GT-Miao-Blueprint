package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Internal domain boundary. */
final class HologramBlueprintProjection {

    private HologramBlueprintProjection() {}

    static HologramPresets.Preset blueprint(HologramSession s, String name) throws java.io.IOException {
        if (s.capture == null || s.capture.incomplete || s.capturePending) throw new java.io.IOException("需要完整结构或区域");
        HologramPresets.Preset result = s.virtualPreset == null ? new HologramPresets.Preset()
            : HologramSnapshotMetadataSource.copyMetadata(s.virtualPreset);
        result.schema = 2;
        result.name = name == null || name.trim()
            .isEmpty() ? "未命名蓝图" : name.trim();
        if (s.virtualPreset == null) {
            result.id = s.blueprintSavedId.isEmpty() ? null : s.blueprintSavedId;
            result.prompt = s.blueprintPrompt;
            result.hintAreas.addAll(s.blueprintHintAreas);
            result.retainedNbtKeys = s.retainedNbtKeys;
        }
        if (s.machine != null) {
            result.structures.clear();
            HologramPresets.Structure declared = new HologramPresets.Structure();
            declared.id = s.x + "," + s.y + "," + s.z;
            declared.name = s.machine.getLocalName();
            declared.x = 0;
            declared.y = 0;
            declared.z = 0;
            declared.controllerId = ((IGregTechTileEntity) s.target).getMetaTileID();
            declared.sourceClass = s.machine.getClass()
                .getName();
            declared.sourceMod = HologramSessionPolicy.sourceMod(s.machine.getClass());
            HologramBlueprintData.storeStructureConfiguration(s, declared);
            result.structures.add(declared);
            result.sourceClass = s.machine.getClass()
                .getName();
            result.sourceMod = HologramSessionPolicy.sourceMod(s.machine.getClass());
            result.controllerId = ((IGregTechTileEntity) s.target).getMetaTileID();
            result.controllerType = HologramSessionPolicy.controllerType(s);
            result.controllerFacing = s.machine.getExtendedFacing()
                .ordinal();
        }
        java.util.Set<String> palette = new java.util.HashSet<>();
        for (int i = 0; i < s.capture.cells.size(); i++) result.cells.add(projectedCell(s, i, result, palette));
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null) for (HologramArea.Structure structure : area.structures.values()) {
            String mod = HologramSessionPolicy.sourceMod(structure.machine.getClass());
            for (cpw.mods.fml.common.ModContainer installed : cpw.mods.fml.common.Loader.instance()
                .getModList())
                if (installed.getModId()
                    .equals(mod)) result.modVersions.put(mod, installed.getVersion());
        }
        return result;
    }

    /** Project exactly one stable editor index with the same NBT and identity rules as a full export. */
    static HologramPresets.Cell projectedCell(HologramSession s, int i, HologramPresets.Preset result,
        java.util.Set<String> palette) throws java.io.IOException {
        HologramCapture.Cell cell = s.capture.cells.get(i);
        ItemStack target = cell.anchor && s.target instanceof IGregTechTileEntity
            ? new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) s.target).getMetaTileID())
            : HologramCellPolicy.targetFor(s, cell, i);
        if (HologramCellPolicy.hintMaterial(target) || target == null && cell.block != null
            && cell.block == com.gtnewhorizon.structurelib.StructureLibAPI.getBlockHint())
            throw new java.io.IOException(
                "位置 " + HologramValues.position(cell) + " 仍是结构提示占位；请在该格的声明候选中选择实际仓室或方块，再处理或保存蓝图");
        HologramCellPolicy.validateOrdinarySourceVariant(s, cell);
        HologramPresets.Cell saved;
        if (target == null) {
            if (cell.block == Blocks.air && !cell.unknown) cell.blueprintBlock = "minecraft:air";
            if (cell.blueprintBlock.isEmpty() && cell.blueprintItem.isEmpty())
                throw new java.io.IOException("未知结构格需要明确目标");
            saved = new HologramPresets.Cell();
            saved.item = cell.blueprintItem.isEmpty() ? null : cell.blueprintItem;
            saved.damage = cell.blueprintDamage;
            saved.block = cell.blueprintBlock.isEmpty() ? null : cell.blueprintBlock;
            saved.metadata = cell.meta;
            saved.x = cell.x - s.x;
            saved.y = cell.y - s.y;
            saved.z = cell.z - s.z;
            saved.facing = HologramBlockIdentity
                .front(s.facingPins.getOrDefault(HologramValues.position(cell), s.planFacings.getOrDefault(i, -1)));
            saved.controller = "templateController".equals(cell.role);
        } else {
            saved = HologramPresets.cell(
                target,
                cell.x - s.x,
                cell.y - s.y,
                cell.z - s.z,
                s.facingPins
                    .getOrDefault(HologramValues.position(cell), s.planFacings.getOrDefault(i, cell.actualFacing)),
                cell.anchor || "templateController".equals(cell.role));
            Block block = HologramCellPolicy.targetPreviewBlock(s, cell, target);
            if (HologramNativePlacement.supported(cell.block) || HologramAePolicy.handles(cell.block, cell.tileNbt)
                || cell.block == Blocks.lit_redstone_lamp
                    && target.getItem() == Item.getItemFromBlock(Blocks.redstone_lamp))
                block = cell.block;
            saved.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
            saved.metadata = HologramCellPolicy.targetPreviewMeta(s, cell, target);
            if (target.hasTagCompound())
                saved.itemNbt = HologramBlueprintNbt.encodeCaptured(block, target.getTagCompound());
            HologramBlueprintData.addPalette(result, palette, target);
        }
        saved.tileNbt = cell.tileNbt;
        saved.itemNbt = cell.itemNbt.isEmpty() ? saved.itemNbt : cell.itemNbt;
        if (!s.virtual && s.player.worldObj.blockExists(cell.x, cell.y, cell.z)) {
            TileEntity actual = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            if (actual != null && (cell.anchor || HologramCellPolicy.targetMatches(
                s,
                cell,
                i,
                s.player.worldObj.getBlock(cell.x, cell.y, cell.z),
                s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z)))) {
                NBTTagCompound data = HologramNativeCapture.capture(actual);
                saved.tileNbt = HologramAeBlueprint.supports(cell.block, data)
                    ? HologramBlueprintNbt.encodeCanonical(cell.block, data)
                    : HologramBlueprintNbt.encodeCaptured(cell.block, data);
            }
        }
        saved.tileNbt = HologramDisplaySnapshot.retainCasing(target, cell.element, saved.tileNbt);
        saved.structureId = cell.structureId.isEmpty() ? null : cell.structureId;
        saved.structureIds = new ArrayList<>(cell.memberships());
        if (s.machine != null && !result.structures.isEmpty()) {
            saved.structureId = result.structures.get(0).id;
            saved.structureIds = new ArrayList<>(Collections.singletonList(saved.structureId));
        }
        if (cell.structureTargets != null)
            saved.allowedTargets = HologramBlueprintData.savedTargets(cell.structureTargets);
        else if (cell.element != null && !cell.anchor)
            saved.allowedTargets = HologramBlueprintData.savedTargets(HologramCellPolicy.candidates(s, cell));
        saved.excluded = cell.excluded;
        saved.prompt = cell.prompt;
        saved.segmentCellId = cell.segmentCellId;
        return saved;
    }

    static HologramPresets.Preset editedVirtualPreset(HologramSession s, String name) throws java.io.IOException {
        if (s.virtualPreset.schema >= 2) return blueprint(s, name);
        HologramPresets.Preset original = s.virtualPreset;
        HologramPresets.Preset result = new HologramPresets.Preset();
        result.authorName = original.authorName;
        result.authorUuid = original.authorUuid;
        result.createdAt = original.createdAt;
        result.name = name == null ? "" : name.trim();
        result.controllerType = original.controllerType;
        result.controllerId = original.controllerId;
        result.controllerFacing = HologramCellPolicy.virtualControllerFacing(s)
            .ordinal();
        // Retain all original editing candidates, including shell fallbacks not currently selected.
        result.palette.addAll(original.palette);
        Map<String, HologramPresets.Cell> defaults = new HashMap<>();
        for (HologramPresets.Cell cell : original.cells) defaults.put(cell.x + "," + cell.y + "," + cell.z, cell);
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            // The template and explicit edits stay authoritative; inventory-driven hatch downgrades are
            // construction policy and must not silently rewrite the saved template.
            ItemStack target = s.pins.get(i);
            HologramPresets.Cell fallback = defaults.get((cell.x - s.x) + "," + (cell.y - s.y) + "," + (cell.z - s.z));
            if (fallback != null && !s.virtualEdited.contains(HologramValues.position(cell))) target = fallback.stack();
            boolean controller = "templateController".equals(cell.role);
            if (target == null || !HologramCellPolicy.acceptableSample(s, cell, target) || target.hasTagCompound())
                throw new java.io.IOException("存在无效目标或设备数据；仅能保存普通物品身份");
            if (controller && (cell.x != s.x || cell.y != s.y
                || cell.z != s.z
                || !(target.getItem() instanceof ItemMachines)
                || target.getItemDamage() != original.controllerId)) throw new java.io.IOException("不能改变原预设控制器身份");
            Block block = Block.getBlockFromItem(target.getItem());
            if (!(target.getItem() instanceof ItemMachines) && (block == null || block.hasTileEntity(
                target.getItem()
                    .getMetadata(target.getItemDamage()))))
                throw new java.io.IOException("预设含未支持的方块实体");
            int direction = fallback != null && !s.virtualEdited.contains(HologramValues.position(cell))
                ? fallback.facing
                : s.facingPins.getOrDefault(HologramValues.position(cell), s.planFacings.getOrDefault(i, -1));
            if (controller) direction = HologramCellPolicy.virtualControllerFacing(s)
                .getDirection()
                .ordinal();
            HologramPresets.Cell saved = HologramPresets
                .cell(target, cell.x - s.x, cell.y - s.y, cell.z - s.z, direction, controller);
            if (fallback != null) {
                saved.defaultItem = fallback.defaultItem;
                saved.defaultDamage = fallback.defaultDamage;
            }
            result.cells.add(saved);
        }
        return result;
    }

    static void populateVirtual(HologramSession s) {
        s.networkBindings.clear();
        s.networkBindingRequested = false;
        if (!s.virtual || s.virtualPreset == null) return;
        Population population = new Population(s);
        while (!population.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
        HologramResourcePlanner.refreshPlan(s);
    }

    /** Stages a fresh area's projection without touching the currently published session or running its plan twice. */
    static final class Population {

        private final HologramSession s;
        private final Thread owner = Thread.currentThread();
        private final ImplicitStructure implicit;
        private final Map<String, String> sourceClasses = new HashMap<>(), sourceWarnings = new HashMap<>();
        private final Map<String, Integer> controllerIds = new HashMap<>();
        private String fallbackSourceClass, fallbackSourceWarning;
        private int sourceIndex, cursor, pass;
        private boolean initialized;

        Population(HologramSession s) {
            this.s = s;
            implicit = new ImplicitStructure(s);
        }

        String phase() {
            return implicit.done() ? "preset.projection" : "preset.memberships";
        }

        long completed() {
            return implicit.done() ? (long) sourceIndex + cursor + (long) pass * s.virtualPreset.cells.size()
                : implicit.cursor;
        }

        long total() {
            return implicit.done() ? s.virtualPreset.structures.size() + 2L * s.virtualPreset.cells.size()
                : s.virtualPreset.cells.size();
        }

        boolean step() {
            return step(Integer.MAX_VALUE, HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(2_000_000L)));
        }

        boolean step(int maxWork, long deadline) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Projection must stay on its owner thread");
            if (maxWork <= 0 || System.nanoTime() >= deadline) return false;
            int used = 0;
            while (!implicit.done() && used < maxWork && System.nanoTime() < deadline) {
                implicit.advance();
                used++;
            }
            if (!implicit.done() || used == maxWork || System.nanoTime() >= deadline) return false;
            if (!initialized) {
                s.capture = new HologramCapture(null, HologramSessionPolicy.trigger(s));
                s.pins.clear();
                s.coordinatePins.clear();
                s.facingPins.clear();
                s.manualPositions.clear();
                s.suppressedAuto.clear();
                s.autoTargets.clear();
                s.undoEdits.clear();
                s.redoEdits.clear();
                s.virtualEdited.clear();
                s.virtualGroups.clear();
                s.allocationsFrozen = false;
                s.frozenAutoTargets.clear();
                s.selected = -1;
                s.selectedIndices.clear();
                s.structureIndex = new HologramStructureIndex(s.virtualPreset);
                fallbackSourceClass = HologramBlueprintData.sourceClass(s.virtualPreset, null);
                fallbackSourceWarning = HologramBlueprintData.sourceWarning(s.virtualPreset, null);
                initialized = true;
            }
            for (int work = used; work < maxWork; work++) {
                if (System.nanoTime() >= deadline) return false;
                if (sourceIndex < s.virtualPreset.structures.size()) {
                    HologramPresets.Structure source = s.virtualPreset.structures.get(sourceIndex);
                    s.structureIndex.add(sourceIndex++);
                    sourceClasses.put(source.id, HologramBlueprintData.sourceClass(s.virtualPreset, source));
                    sourceWarnings.put(source.id, HologramBlueprintData.sourceWarning(s.virtualPreset, source));
                    controllerIds.put(source.id, source.controllerId);
                } else if (pass < 2) {
                    if (cursor >= s.virtualPreset.cells.size()) {
                        cursor = 0;
                        pass++;
                        continue;
                    }
                    HologramPresets.Cell saved = s.virtualPreset.cells.get(cursor++);
                    if (saved.controller != (pass == 1)) continue;
                    populate(saved);
                    if (s.capture.incomplete) return true;
                } else return true;
            }
            return pass >= 2;
        }

        private boolean controllerMatches(HologramPresets.Cell saved, String warning) {
            if (!saved.controller) return true;
            if (!warning.isEmpty()) return false;
            int controllerId = controllerIds.getOrDefault(saved.structureId, s.virtualPreset.controllerId);
            ItemStack stack = saved.stack();
            return stack == null || !(stack.getItem() instanceof ItemMachines)
                || controllerId < 0
                || stack.getItemDamage() == controllerId;
        }

        private void populate(HologramPresets.Cell saved) {
            String sourceId = saved.structureId == null ? "" : saved.structureId;
            String savedWarning = sourceWarnings.getOrDefault(sourceId, fallbackSourceWarning);
            boolean controllerMatches = controllerMatches(saved, savedWarning);
            ItemStack target = saved.missing() || !controllerMatches ? null
                : HologramEssentia.shellMaterial(saved.stack());
            if (target != null && (HologramNativePlacement.supported(saved.block())
                || saved.itemNbt != null && !saved.itemNbt.isEmpty())) {
                try {
                    NBTTagCompound payload = HologramBlueprintNbt.decode(saved.itemNbt);
                    if (HologramNativePlacement.supported(saved.block())
                        || payload != null && payload.hasKey(HologramRecovery.TAG))
                        target.setTagCompound(HologramBlueprintNbt.nativePayload(payload));
                } catch (java.io.IOException invalid) {
                    target = null;
                }
            }
            HologramCapture.Cell cell = new HologramCapture.Cell(null, s.x + saved.x, s.y + saved.y, s.z + saved.z);
            cell.segmentCellId = saved.segmentCellId;
            cell.block = s.virtualPreset.schema >= 2 && saved.block != null || target == null
                || HologramNativePlacement.supported(saved.block())
                || saved.block() == Blocks.lit_redstone_lamp ? saved.block() : Block.getBlockFromItem(target.getItem());
            cell.meta = s.virtualPreset.schema >= 2 ? saved.metadata
                : target == null ? saved.metadata
                    : target.getItem()
                        .getMetadata(target.getItemDamage());
            cell.blueprintBlock = saved.block == null ? "" : saved.block;
            cell.blueprintItem = saved.item == null ? "" : saved.item;
            cell.blueprintDamage = saved.damage;
            HologramBlueprintData.setMemberships(cell, saved.memberships());
            if (saved.allowedTargets != null) {
                cell.structureTargets = new ArrayList<>();
                for (HologramPresets.Target allowed : saved.allowedTargets) {
                    ItemStack sample = allowed.stack();
                    if (sample != null) cell.structureTargets.add(sample);
                }
            } else if (!saved.memberships()
                .isEmpty()) {
                    // Legacy blueprints have no declarations: their own targets are the safe editing boundary.
                    cell.structureTargets = new ArrayList<>();
                    ItemStack original = saved.stack(), fallback = saved.defaultStack();
                    if (original != null) cell.structureTargets.add(original);
                    if (fallback != null && !HologramValues.same(original, fallback))
                        cell.structureTargets.add(fallback);
                }
            cell.sourceClass = sourceClasses.getOrDefault(cell.structureId, fallbackSourceClass);
            cell.sourceWarning = savedWarning;
            if (saved.controller && !controllerMatches && cell.sourceWarning.isEmpty())
                cell.sourceWarning = "控制器材料 ID 与保存的来源结构身份不同";
            cell.excluded = saved.excluded;
            cell.prompt = saved.prompt == null ? "" : saved.prompt;
            cell.tileNbt = saved.tileNbt == null ? "" : saved.tileNbt;
            cell.itemNbt = saved.itemNbt == null ? "" : saved.itemNbt;
            cell.userPosition = true;
            cell.role = saved.controller ? "templateController" : "prefab";
            try {
                HologramCellPolicy.validateOrdinarySourceVariant(s, cell);
            } catch (java.io.IOException invalidVariant) {
                s.capture.incomplete = true;
                s.capture.failure = "INVALID_PREFAB_VARIANT";
                return;
            }
            if (target != null && !(s.virtualPreset.schema >= 2 && HologramBlueprintData.editableAeSample(cell, target)
                || HologramCellPolicy.placeableSample(s, cell, target))) {
                s.capture.incomplete = true;
                s.capture.failure = "INVALID_PREFAB_ITEM";
                return;
            }
            int index = s.capture.cells.size();
            s.capture.cells.add(cell);
            s.capture.positions.put(HologramValues.position(cell), cell);
            if (target != null) {
                s.pins.put(index, target.copy());
                s.coordinatePins.put(HologramValues.position(cell), target.copy());
            } else if (cell.block != Blocks.air || saved.missing()) {
                cell.unknown = true;
                cell.status = "unsupported";
            }
            for (int legal : HologramCellPolicy.legalFacings(s, cell, target))
                if (saved.facing == legal) s.facingPins.put(HologramValues.position(cell), saved.facing);
        }
    }

    static void materializeImplicitStructure(HologramSession s) {
        ImplicitStructure task = new ImplicitStructure(s);
        while (!task.done()) task.advance();
    }

    /** Legacy implicit membership migration shares the projection cursor instead of scanning ahead of its budget. */
    private static final class ImplicitStructure {

        private final HologramSession s;
        private final HologramPresets.Preset preset;
        private HologramPresets.Structure structure;
        private int stage, cursor;

        ImplicitStructure(HologramSession s) {
            this.s = s;
            preset = s.virtualPreset;
            if (preset.schema < 2 || !preset.structures.isEmpty()) stage = 2;
        }

        boolean done() {
            return stage == 2;
        }

        void advance() {
            if (stage == 0) {
                if (cursor >= preset.cells.size()) { stage = 2; return; }
                HologramPresets.Cell controller = preset.cells.get(cursor++);
                if (!controller.controller) return;
                structure = new HologramPresets.Structure();
                structure.id = UUID.randomUUID().toString();
                structure.name = preset.name == null ? "Template" : preset.name;
                structure.sourceClass = preset.sourceClass;
                structure.sourceMod = preset.sourceMod;
                structure.controllerId = preset.controllerId;
                structure.x = controller.x;
                structure.y = controller.y;
                structure.z = controller.z;
                structure.main = s.main;
                structure.mainExplicit = s.mainExplicit;
                structure.noHatches = s.noHatches;
                for (Object raw : s.channels.func_150296_c())
                    structure.channels.put((String) raw, s.channels.getInteger((String) raw));
                preset.structures.add(structure);
                cursor = 0;
                stage = 1;
            } else if (stage == 1) {
                if (cursor >= preset.cells.size()) { stage = 2; return; }
                HologramPresets.Cell cell = preset.cells.get(cursor++);
                cell.structureId = structure.id;
                cell.structureIds = new ArrayList<>(Collections.singletonList(structure.id));
            }
        }
    }
}
