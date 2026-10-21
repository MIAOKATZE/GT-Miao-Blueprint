package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Detached blueprint copying and source identity descriptors. */
final class HologramBlueprintData {

    private HologramBlueprintData() {}

    static List<HologramPresets.Target> savedTargets(List<ItemStack> targets) {
        List<HologramPresets.Target> result = new ArrayList<>();
        for (ItemStack target : targets) if (target != null) result.add(new HologramPresets.Target(target));
        return result;
    }

    static void setMemberships(HologramCapture.Cell cell, List<String> ids) {
        cell.structureIds = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        cell.structureId = cell.structureIds.isEmpty() ? "" : cell.structureIds.get(0);
    }

    static HologramPresets.Structure copyStructure(HologramPresets.Structure original) {
        HologramPresets.Structure copy = new HologramPresets.Structure();
        copy.id = original.id;
        copy.name = original.name;
        copy.sourceMod = original.sourceMod;
        copy.sourceClass = original.sourceClass;
        copy.textBoxNbt = original.textBoxNbt;
        copy.controllerId = original.controllerId;
        copy.x = original.x;
        copy.y = original.y;
        copy.z = original.z;
        copy.enabled = original.enabled;
        copy.main = original.main;
        copy.mainExplicit = original.mainExplicit;
        copy.noHatches = original.noHatches;
        copy.channels = original.channels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(original.channels);
        return copy;
    }

    static HologramCapture.Cell blueprintCell(HologramCapture.Cell old, int x, int y, int z) {
        HologramCapture.Cell cell = HologramVirtualTransform.at(old, x, y, z);
        cell.sourceClass = old.sourceClass;
        cell.sourceWarning = old.sourceWarning;
        cell.blueprintBlock = old.blueprintBlock;
        cell.blueprintItem = old.blueprintItem;
        cell.blueprintDamage = old.blueprintDamage;
        cell.excluded = old.excluded;
        cell.prompt = old.prompt;
        cell.tileNbt = old.tileNbt;
        cell.itemNbt = old.itemNbt;
        setMemberships(cell, old.memberships());
        cell.resourceFingerprint = old.resourceFingerprint;
        cell.resourceError = old.resourceError;
        cell.resourcePlan = old.resourcePlan;
        cell.resourcePlanKey = old.resourcePlanKey;
        cell.coverCostsNbt = old.coverCostsNbt;
        cell.coverMaterials = old.coverMaterials;
        cell.invalidCoverCosts = old.invalidCoverCosts;
        cell.actualFacing = old.actualFacing;
        cell.actualStack = old.actualStack == null ? null : old.actualStack.copy();
        cell.structureTargets = old.structureTargets == null ? null : new ArrayList<>(old.structureTargets);
        return cell;
    }

    /** Detach a physical capture using the metadata produced by its actual schema2 projection. */
    static HologramCapture projectedCapture(HologramCapture original, HologramPresets.Preset projected, int originX,
        int originY, int originZ) throws java.io.IOException {
        java.util.Map<String, HologramPresets.Cell> saved = new java.util.HashMap<>();
        for (HologramPresets.Cell cell : projected.cells) {
            String key = (originX + cell.x) + "," + (originY + cell.y) + "," + (originZ + cell.z);
            if (saved.put(key, cell) != null) throw new java.io.IOException("投影来源存在重复坐标");
        }
        HologramCapture detached = new HologramCapture(null, original.trigger);
        detached.extensionReports = (NBTTagCompound) original.extensionReports.copy();
        for (HologramCapture.Cell old : original.cells) {
            String position = HologramValues.position(old);
            HologramPresets.Cell source = saved.remove(position);
            if (source == null) throw new java.io.IOException("投影来源未包含原结构格：" + position);
            HologramCapture.Cell copy = blueprintCell(old, old.x, old.y, old.z);
            setMemberships(copy, source.memberships());
            copy.blueprintBlock = source.block == null ? "" : source.block;
            copy.blueprintItem = source.item == null ? "" : source.item;
            copy.blueprintDamage = source.damage;
            copy.tileNbt = source.tileNbt == null ? "" : source.tileNbt;
            copy.itemNbt = source.itemNbt == null ? "" : source.itemNbt;
            copy.sourceClass = blueprintSourceClass(projected, copy.structureId);
            copy.sourceWarning = blueprintSourceWarning(projected, copy.structureId);
            copy.actualDisplay = (NBTTagCompound) old.actualDisplay.copy();
            copy.targetDisplay = (NBTTagCompound) old.targetDisplay.copy();
            copy.status = old.status;
            copy.resourceTile = old.resourceTile;
            copy.resourceItem = old.resourceItem;
            // Schema2 controllers belong to their saved member and move only with that complete member.
            copy.anchor = false;
            if (source.controller) copy.role = "templateController";
            if (source.allowedTargets != null) {
                copy.structureTargets = new ArrayList<>();
                for (HologramPresets.Target target : source.allowedTargets) {
                    ItemStack stack = target.stack();
                    if (stack != null) copy.structureTargets.add(stack);
                }
            }
            detached.cells.add(copy);
            detached.positions.put(position, copy);
        }
        if (!saved.isEmpty()) throw new java.io.IOException("投影来源包含不属于原结构的格");
        return detached;
    }

    static void updateHintArea(List<HologramPresets.HintArea> hints, HologramPresets.HintArea hint)
        throws java.io.IOException {
        hints.removeIf(
            existing -> existing.x1 == hint.x1 && existing.y1 == hint.y1
                && existing.z1 == hint.z1
                && existing.x2 == hint.x2
                && existing.y2 == hint.y2
                && existing.z2 == hint.z2);
        if (hint.prompt.isEmpty()) return;
        if (hints.size() >= 256) throw new java.io.IOException("提示区已达256个上限；请先删除已有提示区");
        hints.add(hint);
    }

    static void addPalette(HologramPresets.Preset preset, java.util.Set<String> keys, ItemStack stack) {
        String key = Item.itemRegistry.getNameForObject(stack.getItem()) + ":" + stack.getItemDamage();
        if (keys.add(key)) preset.palette.add(HologramPresets.cell(stack, 0, 0, 0, -1, false));
    }

    static HologramPresets.Structure blueprintSource(HologramPresets.Preset preset, String id) {
        for (HologramPresets.Structure structure : preset.structures) if (structure.id.equals(id)) return structure;
        return null;
    }

    static String blueprintSourceClass(HologramPresets.Preset preset, String id) {
        return sourceClass(preset, blueprintSource(preset, id));
    }

    static String sourceClass(HologramPresets.Preset preset, HologramPresets.Structure structure) {
        if (structure != null && structure.sourceClass != null) return structure.sourceClass;
        if (preset.sourceClass != null && !preset.sourceClass.isEmpty()) return preset.sourceClass;
        if (preset.controllerId >= 0 && preset.controllerType != null && preset.controllerType.lastIndexOf(':') > 0)
            return preset.controllerType.substring(0, preset.controllerType.lastIndexOf(':'));
        return "";
    }

    static String blueprintSourceWarning(HologramPresets.Preset preset, String id) {
        return sourceWarning(preset, blueprintSource(preset, id));
    }

    static String sourceWarning(HologramPresets.Preset preset, HologramPresets.Structure structure) {
        String sourceClass = sourceClass(preset, structure);
        if (sourceClass.isEmpty()) return "";
        try {
            Class.forName(sourceClass, false, HologramBlueprintData.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError missing) {
            return "来源结构类缺失：" + sourceClass;
        }
        int controllerId = structure == null ? preset.controllerId : structure.controllerId;
        if (controllerId >= 0) {
            gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = controllerId
                >= GregTechAPI.METATILEENTITIES.length ? null : GregTechAPI.METATILEENTITIES[controllerId];
            if (prototype == null || !sourceClass.equals(
                prototype.getClass()
                    .getName()))
                return "来源控制器身份缺失或 ID 已被其他机器占用：" + sourceClass + ":" + controllerId;
        }
        return "";
    }

    static boolean blueprintControllerMatches(HologramPresets.Preset preset, HologramPresets.Cell saved,
        String warning) {
        if (!saved.controller) return true;
        String id = saved.structureId == null ? "" : saved.structureId;
        if (!warning.isEmpty()) return false;
        HologramPresets.Structure structure = blueprintSource(preset, id);
        int controllerId = structure == null ? preset.controllerId : structure.controllerId;
        ItemStack stack = saved.stack();
        if (stack != null && stack.getItem() instanceof ItemMachines
            && controllerId >= 0
            && stack.getItemDamage() != controllerId) return false;
        return true;
    }

    /** Import admission checks the stored AE shell only; unresolved contents remain visible for explicit repair. */
    static boolean editableAeSample(HologramCapture.Cell cell, ItemStack target) {
        if (target == null || target.getItem() == null || target.stackSize < 1) return false;
        try {
            NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
            return HologramAeBlueprint.supports(cell.block, payload) && HologramValues
                .sameMaterial(target, HologramAeBlueprint.sampleIdentity(cell.block, cell.meta, payload));
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    static void storeStructureConfiguration(HologramSession s, HologramPresets.Structure saved) {
        saved.main = s.main;
        saved.mainExplicit = s.mainExplicit;
        saved.noHatches = s.noHatches;
        saved.channels.clear();
        for (Object key : s.channels.func_150296_c())
            saved.channels.put((String) key, s.channels.getInteger((String) key));
    }

    static void applyStructureConfiguration(HologramSession s, HologramPresets.Structure saved) {
        s.main = Math.max(1, saved.main);
        s.mainExplicit = saved.mainExplicit;
        s.noHatches = saved.noHatches;
        s.channels = new NBTTagCompound();
        if (saved.channels != null) saved.channels.forEach((key, value) -> s.channels.setInteger(key, value));
        s.capabilities = null;
    }

    static void saveStructureConfiguration(HologramSession s) {
        if (s.virtualPreset == null) return;
        HologramPresets.Structure selected = HologramBlueprintData
            .blueprintSource(s.virtualPreset, s.blueprintActiveStructure);
        if (selected != null) storeStructureConfiguration(s, selected);
    }

    static void loadStructureConfiguration(HologramSession s, String id) {
        if (s.blueprintActiveStructure.isEmpty() && !id.isEmpty()) {
            s.areaOverviewConfiguration = new HologramPresets.Structure();
            storeStructureConfiguration(s, s.areaOverviewConfiguration);
        }
        HologramPresets.Structure selected = id.isEmpty() ? s.areaOverviewConfiguration
            : HologramBlueprintData.blueprintSource(s.virtualPreset, id);
        if (selected != null) {
            applyStructureConfiguration(s, selected);
            try {
                s.capabilities = HologramVirtualStructureEditing.capabilities(s, selected);
            } catch (java.io.IOException | RuntimeException | LinkageError unavailable) {
                s.capabilities = HologramCapabilities.describe(null, HologramSessionPolicy.trigger(s), null)
                    .withSavedChannels(s.channels);
                s.status = "来源结构声明不可用，保留保存的信道：" + unavailable.getMessage();
            }
        }
    }

    static void detachAreaOverview(HologramSession s) {
        s.areaOverviewOwner = null;
        if (HologramWorkspace.AREA_EDITORS.get(s.player.getUniqueID()) == s)
            HologramWorkspace.AREA_EDITORS.remove(s.player.getUniqueID());
    }
}
