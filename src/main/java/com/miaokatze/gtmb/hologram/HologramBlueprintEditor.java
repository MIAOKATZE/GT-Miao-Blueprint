package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Coordinate editing, clipboard, undo and redo; never writes the live world. */
final class HologramBlueprintEditor {

    private HologramBlueprintEditor() {}

    static boolean isCellEdit(String op) {
        return "editCopy".equals(op) || "editCut".equals(op)
            || "editPaste".equals(op)
            || "restoreCell".equals(op)
            || "rotateCell".equals(op);
    }

    static void pinSelection(HologramSession s, NBTTagCompound action, int source, ItemStack target)
        throws java.io.IOException {
        int[] indices = geometryIndices(s, action);
        if (indices.length == 0) throw new java.io.IOException("请先选择目标位置");
        for (int index : indices) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (cell.anchor || !HologramCellPolicy.acceptableSample(s, cell, target))
                throw new java.io.IOException("选中格的结构声明不接受该候选，目标未修改");
        }
        if (indices.length > 1) HologramResourcePlanner.freezeAllocations(s);
        for (int index : indices) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            HologramCellPolicy.setBlueprintTarget(s, cell, target);
            if (s.virtual) s.virtualEdited.add(HologramValues.position(cell));
            s.pins.put(index, target.copy());
            s.coordinatePins.put(HologramValues.position(cell), target.copy());
        }
        s.selected = source;
        s.scope = 2;
        s.selectedIndices.clear();
        for (int index : indices) s.selectedIndices.add(index);
        refreshEdited(s, HologramLocalPlanRefresh.selected(s, action));
    }

    static void refreshEdited(HologramSession s, java.util.Set<Integer> dirty) {
        if (HologramSessionPolicy.authoring(s) && s.geometryEdits.validationPending) {
            s.geometryEdits.changed(s, dirty);
            HologramGeometryEdits.refresh(s);
            return;
        }
        if (HologramLocalPlanRefresh.refresh(s, dirty)) return;
        if (HologramSessionPolicy.authoring(s)) {
            HologramResourcePlanner.refreshPlan(s);
            return;
        }
        if (s.virtual && s.virtualPreset != null && s.virtualPreset.schema >= 2 && s.cellDeltaProtocol) {
            HologramDeferredPlanRefresh.request(s, dirty);
        } else HologramResourcePlanner.refreshPlan(s);
    }

    static boolean isPlanningEdit(String op) {
        return isCellEdit(op) || "clearPin".equals(op)
            || "customPin".equals(op)
            || "sourcePin".equals(op)
            || "pin".equals(op)
            || "hatchFacing".equals(op)
            || "hatchPreset".equals(op)
            || "clearHatchPreset".equals(op);
    }

    static void remember(List<HologramEditState> history, HologramEditState state) {
        if (history.size() >= 32) history.remove(0);
        history.add(state);
    }

    /** Editing never harvests a block or consumes a material. Clipboard samples carry no device state. */
    static void edit(HologramSession s, NBTTagCompound action, String op) {
        if (s.job == 1 || s.job == 2) {
            s.status = "施工任务保留当前计划；请先取消再编辑";
            return;
        }
        if (s.capturePending || s.capture == null || s.capture.incomplete) {
            s.status = "请等待完整结构采集后编辑";
            return;
        }
        if ("editUndo".equals(op) || "editRedo".equals(op)) {
            boolean undo = "editUndo".equals(op);
            List<HologramEditState> from = undo ? s.undoEdits : s.redoEdits;
            if (from.isEmpty()) {
                s.status = undo ? "没有可撤销的规划编辑" : "没有可重做的规划编辑";
                return;
            }
            HologramEditState previous = from.remove(from.size() - 1);
            remember(undo ? s.redoEdits : s.undoEdits, previous.current(s));
            if (HologramSessionPolicy.authoring(s) && previous.sparseIndices == null) s.geometryEdits.begin(s);
            previous.apply(s);
            if (previous.sparseIndices == null && HologramSessionPolicy.authoring(s)) HologramGeometryEdits.refresh(s);
            else if (previous.sparseIndices == null) HologramResourcePlanner.refreshPlan(s);
            else refreshEdited(s, previous.sparseIndices);
            if (previous.sparseIndices == null || !HologramLocalPlanRefresh.editedSource(s, previous.sparseIndices))
                HologramAuthoringWorkflow.blueprintEdited(s, "geometry");
            s.status = undo ? "已撤销规划编辑；已施工的世界保持现状" : "已重做规划编辑；点击施工才改变世界";
            return;
        }
        if (s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && ("editCopy".equals(op) || "editCut".equals(op) || "editPaste".equals(op))) {
            try {
                blueprintClipboard(s, action, op);
            } catch (java.io.IOException failure) {
                s.status = "批量编辑失败：" + failure.getMessage();
            }
            return;
        }
        int[] batch = action.getIntArray("indices");
        if (batch.length > 1) {
            NBTTagCompound single = (NBTTagCompound) action.copy();
            single.removeTag("indices");
            single.removeTag("selectedIndices");
            for (int selected : batch) {
                single.setInteger("selected", selected);
                edit(s, single, op);
            }
            return;
        }
        int index = action.hasKey("selected", 3) ? action.getInteger("selected") : s.selected;
        if (index < 0 || index >= s.capture.cells.size()) {
            s.status = "请先选择结构格";
            return;
        }
        HologramCapture.Cell cell = s.capture.cells.get(index);
        if (!HologramSessionPolicy.editableStructureCell(s, cell)) {
            s.status = "该格属于其他结构，请先切换结构";
            return;
        }
        if (cell.anchor) {
            s.status = "控制器始终受保护";
            return;
        }
        if ("editCopy".equals(op) || "editCut".equals(op)) {
            ItemStack sample = HologramCellPolicy.targetFor(s, cell, index);
            TileEntity actual = s.player.worldObj.blockExists(cell.x, cell.y, cell.z)
                ? s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z)
                : null;
            if (!HologramCellPolicy.explicitTarget(s, cell, index) && !s.autoTargets.containsKey(index)
                && actual instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) actual).getMetaTileEntity() instanceof MTEHatch)
                sample = new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) actual).getMetaTileID());
            if (!HologramCellPolicy.acceptableSample(s, cell, sample)) {
                s.status = "本格没有可复制的放置目标";
                return;
            }
            if (HologramRecovery.hasSeal(sample)) {
                s.status = "封存设备携带真实库存，不能复制或剪切；请从普通仓室目标复制";
                return;
            }
            // Item identity only: inventories, fluids, covers and arbitrary item NBT never enter clipboard.
            ItemStack clean = new ItemStack(sample.getItem(), 1, sample.getItemDamage());
            int direction = s.facingPins
                .getOrDefault(HologramValues.position(cell), s.planFacings.getOrDefault(index, -1));
            if (direction < 0 && sample.getItem() instanceof ItemMachines
                && actual instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) actual).getMetaTileID() == sample.getItemDamage())
                direction = HologramBlockIdentity.front((IGregTechTileEntity) actual);
            if ("editCut".equals(op) && !restoreCell(s, cell, index)) return;
            s.editClipboard = clean;
            s.editClipboardFacing = direction;
            s.status = "editCopy".equals(op) ? "已复制目标物品与朝向，不复制设备库存" : "已剪切规划目标；现场设备保留至施工";
            return;
        }
        if ("restoreCell".equals(op)) {
            if (restoreCell(s, cell, index)) s.status = "本格已设为原结构默认外壳；施工时安全回收旧设备";
            return;
        }
        if ("editPaste".equals(op)) {
            ItemStack sample = s.editClipboard;
            if (!HologramCellPolicy.acceptableSample(s, cell, sample)) {
                s.status = "剪贴板为空或该目标不可放置";
                return;
            }
            int direction = s.editClipboardFacing;
            int[] legal = HologramCellPolicy.legalFacings(s, cell, sample);
            if (HologramCellPolicy.hatchTier(sample) != Integer.MAX_VALUE && legal.length == 0) {
                s.status = "本格没有适用于剪贴板仓室的合法朝向";
                return;
            }
            if (legal.length > 0 && direction != -1) {
                boolean accepted = false;
                for (int side : legal) if (side == direction) accepted = true;
                if (!accepted) {
                    s.status = "剪贴板朝向不适用于本格，请先调整源格朝向";
                    return;
                }
            }
            HologramResourcePlanner.freezeAllocations(s);
            if (s.virtual) s.virtualEdited.add(HologramValues.position(cell));
            s.pins.put(index, sample.copy());
            s.coordinatePins.put(HologramValues.position(cell), sample.copy());
            s.suppressedAuto.add(HologramValues.position(cell));
            s.facingPins.remove(HologramValues.position(cell));
            if (legal.length > 0 && direction >= 0) s.facingPins.put(HologramValues.position(cell), direction);
            s.selected = index;
            refreshEdited(s, java.util.Collections.singleton(index));
            s.status = "已粘贴目标规划；施工时才扣除真实材料";
            return;
        }
        ItemStack rotationTarget = HologramCellPolicy.targetFor(s, cell, index);
        boolean nativePipe = HologramBlockTransforms.isPipe(rotationTarget);
        if (s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && (nativePipe || !(rotationTarget != null && rotationTarget.getItem() instanceof ItemMachines))) {
            HologramCapture.Cell staged = HologramBlueprintData.blueprintCell(cell, cell.x, cell.y, cell.z);
            ItemStack nativeSample;
            try {
                HologramBlockTransforms.turn(staged, false);
                nativeSample = HologramBlockTransforms.nativeSample(staged, rotationTarget);
            } catch (java.io.IOException invalid) {
                s.status = "旋转被拒绝：" + invalid.getMessage();
                return;
            }
            if (staged.meta == cell.meta && java.util.Objects.equals(staged.tileNbt, cell.tileNbt)
                && java.util.Objects.equals(staged.itemNbt, cell.itemNbt)) {
                s.status = "本格没有已适配的可旋转状态";
                return;
            }
            HologramResourcePlanner.freezeAllocations(s);
            String key = HologramValues.position(cell);
            s.capture.cells.set(index, staged);
            s.capture.positions.put(key, staged);
            if (s.manualPositions.containsKey(key)) s.manualPositions.put(key, staged);
            if (nativePipe) s.facingPins.remove(key);
            if (nativeSample != null) {
                s.pins.put(index, nativeSample.copy());
                s.coordinatePins.put(key, nativeSample.copy());
                if (s.autoTargets.containsKey(index)) s.autoTargets.put(index, nativeSample.copy());
                if (s.frozenAutoTargets.containsKey(key)) s.frozenAutoTargets.put(key, nativeSample.copy());
                if (index < s.planTargets.size()) s.planTargets.set(index, nativeSample.copy());
            }
            s.virtualEdited.add(key);
            s.cellIndices.remove(cell);
            staged.stableIndex = index;
            s.cellIndices.put(staged, index);
            s.selected = index;
            refreshEdited(s, java.util.Collections.singleton(index));
            s.status = "本格方块状态已水平旋转 90°；施工时生效";
            return;
        }
        int[] legal = HologramCellPolicy.legalFacings(s, cell, rotationTarget);
        int current = s.facingPins.getOrDefault(HologramValues.position(cell), s.planFacings.getOrDefault(index, -1));
        int next = HologramCellRotation.nextFacing(current, legal);
        if (next < 0) {
            s.status = "本格目标没有可轮换的合法朝向";
            return;
        }
        HologramResourcePlanner.freezeAllocations(s);
        if (s.virtual) s.virtualEdited.add(HologramValues.position(cell));
        s.facingPins.put(HologramValues.position(cell), next);
        s.selected = index;
        refreshEdited(s, java.util.Collections.singleton(index));
        s.status = "已轮换到下一合法朝向；施工时生效";
    }

    static boolean restoreCell(HologramSession s, HologramCapture.Cell cell, int index) {
        if ("templateController".equals(cell.role)) {
            s.status = "模板控制器不能恢复外壳";
            return false;
        }
        HologramCasingFallback.Target casing = HologramCasingFallback
            .resolve(cell.element, HologramCellPolicy.elementTrigger(s, cell));
        ItemStack target = casing == null ? null : new ItemStack(casing.block, 1, casing.meta);
        if (s.virtual && s.virtualPreset != null) {
            HologramPresets.Cell saved = s.localEdits.defaults
                .get((cell.x - s.x) + "," + (cell.y - s.y) + "," + (cell.z - s.z));
            if (saved != null) target = saved.defaultStack();
            else for (HologramPresets.Cell fallback : s.virtualPreset.cells)
                if (fallback.x == cell.x - s.x && fallback.y == cell.y - s.y && fallback.z == cell.z - s.z) {
                    target = fallback.defaultStack();
                    break;
                }
        }
        if (target == null && !cell.userPosition
            && !cell.unknown
            && !"hatch".equals(cell.role)
            && cell.block != null
            && cell.block != Blocks.air) {
            ItemStack original = new ItemStack(cell.block, 1, cell.meta);
            if (!(original.getItem() instanceof ItemMachines) && HologramCellPolicy.acceptableSample(s, cell, original))
                target = original;
        }
        if (!HologramCellPolicy.acceptableSample(s, cell, target)) {
            s.status = "本格没有可确认的结构默认外壳，规划未修改";
            return false;
        }
        HologramResourcePlanner.freezeAllocations(s);
        if (s.virtual) s.virtualEdited.add(HologramValues.position(cell));
        s.pins.put(index, target.copy());
        s.coordinatePins.put(HologramValues.position(cell), target.copy());
        s.facingPins.remove(HologramValues.position(cell));
        s.suppressedAuto.add(HologramValues.position(cell));
        s.selected = index;
        s.scope = 2;
        refreshEdited(s, java.util.Collections.singleton(index));
        return true;
    }

    static void addPosition(HologramSession s, NBTTagCompound action) {
        if ((!s.virtual && s.target == null) || s.capture == null || s.capture.incomplete || s.capturePending) {
            s.status = "请先完成结构采集";
            return;
        }
        int dx = action.getInteger("dx"), dy = action.getInteger("dy"), dz = action.getInteger("dz");
        if (!action.hasKey("dx", 3) || !action.hasKey("dy", 3)
            || !action.hasKey("dz", 3)
            || !HologramSessionPolicy.validRelativePosition(s, dx, dy, dz)) {
            s.status = "位置必须在世界坐标与高度范围内";
            return;
        }
        int x = s.x + dx, y = s.y + dy, z = s.z + dz;
        if ((!HologramSessionPolicy.authoring(s) && !s.player.worldObj.blockExists(x, y, z)) || y < 0
            || y >= s.player.worldObj.getHeight()) {
            s.status = "该位置未加载或超出世界范围";
            return;
        }
        if (action.getBoolean("airOnly")
            && ((!HologramSessionPolicy.authoring(s) && !s.player.worldObj.isAirBlock(x, y, z))
                || !withinAirEditBounds(s, action, x, y, z))) {
            s.status = "请选择当前蓝图或结构边界内的空气位置";
            return;
        }
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.x == x && cell.y == y && cell.z == z) {
                s.selected = i;
                s.selectedIndices.clear();
                s.selectedIndices.add(i);
                s.scope = 2;
                HologramResourcePlanner.refreshPlan(s);
                s.status = cell.anchor ? "控制器始终受保护" : "已选择该位置，可从背包指定目标";
                return;
            }
        }
        if (s.capture.cells.size() >= HologramCapture.LIMIT) {
            s.status = "结构位置已达到集合容量上限";
            return;
        }
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, y, z);
        cell.block = Blocks.air;
        cell.blueprintBlock = "minecraft:air";
        cell.userPosition = true;
        cell.role = "manual";
        s.manualPositions.put(HologramValues.position(cell), cell);
        s.capture.cells.add(cell);
        s.capture.positions.put(HologramValues.position(cell), cell);
        s.selected = s.capture.cells.size() - 1;
        s.selectedIndices.clear();
        s.selectedIndices.add(s.selected);
        s.scope = 2;
        HologramResourcePlanner.refreshPlan(s);
        s.status = "位置已添加；指定目标后才会施工";
    }

    static boolean withinAirEditBounds(HologramSession s, NBTTagCompound action, int x, int y, int z) {
        String active = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
        String requested = action.getString("structureId");
        if (!active.isEmpty() && !requested.isEmpty() && !active.equals(requested)) return false;
        if (active.isEmpty()) active = requested;
        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (HologramCapture.Cell cell : s.capture.cells) {
            if (!active.isEmpty() && !cell.belongsTo(active)) continue;
            minX = Math.min(minX, cell.x);
            minY = Math.min(minY, cell.y);
            minZ = Math.min(minZ, cell.z);
            maxX = Math.max(maxX, cell.x);
            maxY = Math.max(maxY, cell.y);
            maxZ = Math.max(maxZ, cell.z);
        }
        return minX != Long.MAX_VALUE && x >= minX - 1
            && x <= maxX + 1
            && y >= minY - 1
            && y <= maxY + 1
            && z >= minZ - 1
            && z <= maxZ + 1;
    }

    /** A shared cell may follow a complete member structure; its other memberships remain at the source. */
    static java.util.Set<String> completeGeometrySelection(HologramSession s, int[] indices)
        throws java.io.IOException {
        if (s.virtualPreset.structures.isEmpty() && s.capture.cells.stream()
            .anyMatch(cell -> "templateController".equals(cell.role)))
            throw new java.io.IOException("模板结构身份缺失，请重新加载蓝图后编辑");
        java.util.Set<Integer> selected = new java.util.HashSet<>();
        for (int index : indices) selected.add(index);
        java.util.Set<String> complete = new java.util.HashSet<>();
        if (s.localEdits.current(s)) {
            Map<String, Integer> counts = new HashMap<>();
            for (int index : selected) for (String member : s.capture.cells.get(index)
                .memberships()) counts.merge(member, 1, Integer::sum);
            for (Map.Entry<String, Integer> count : counts.entrySet()) {
                List<Integer> members = s.localEdits.memberships.get(count.getKey());
                if (members != null && members.size() == count.getValue()) complete.add(count.getKey());
            }
        } else for (HologramPresets.Structure structure : s.virtualPreset.structures) {
            boolean present = false, whole = true;
            for (int index = 0; index < s.capture.cells.size(); index++) if (s.capture.cells.get(index)
                .belongsTo(structure.id)) {
                    present = true;
                    if (!selected.contains(index)) whole = false;
                }
            if (present && whole) complete.add(structure.id);
        }
        for (int index : indices) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (!cell.memberships()
                .isEmpty() && cell.memberships()
                    .stream()
                    .noneMatch(complete::contains))
                throw new java.io.IOException("结构内部不能独立移动或复制，请选择完整结构");
        }
        return complete;
    }

    /** Remove workspace geometry only; controller structure edits retain their default-shell action. */
    static void deleteBlueprintCells(HologramSession s, NBTTagCompound action) throws java.io.IOException {
        if (!HologramSessionPolicy.authoring(s) || !s.blueprintActiveStructure.isEmpty()
            || !s.player.capabilities.allowEdit
            || s.capture == null
            || s.capturePending
            || s.capture.pending
            || s.capture.incomplete
            || s.localPlanPending
            || s.job == 1
            || s.job == 2
            || !action.hasKey("planRevision", 4)
            || action.getLong("planRevision") != s.planRevision) throw new java.io.IOException("请在完整的蓝图编辑工作区选择方块后删除");
        int[] indices = geometryIndices(s, action);
        java.util.Set<Integer> selected = new java.util.HashSet<>();
        for (int index : indices) selected.add(index);
        for (HologramPresets.Structure group : s.virtualPreset.structures) {
            if (group.textBoxNbt == null || group.textBoxNbt.isEmpty()) continue;
            boolean any = false, whole = true;
            for (int index = 0; index < s.capture.cells.size(); index++) if (s.capture.cells.get(index)
                .belongsTo(group.id)) {
                    any |= selected.contains(index);
                    whole &= selected.contains(index);
                }
            if (any && !whole) throw new java.io.IOException("文字或图片须整体选择后删除");
        }
        java.util.Set<String> remove = new java.util.HashSet<>(), touched = new java.util.HashSet<>();
        for (int index : indices) {
            HologramCapture.Cell source = s.capture.cells.get(index);
            if (source.anchor) throw new java.io.IOException("控制器始终受保护");
            remove.add(HologramValues.position(source));
            touched.addAll(source.memberships());
        }
        stageGeometry(s, java.util.Collections.emptyList(), remove, false);
        java.util.Set<String> remaining = new java.util.HashSet<>();
        for (HologramCapture.Cell cell : s.capture.cells) remaining.addAll(cell.memberships());
        touched.removeAll(remaining);
        s.virtualPreset.structures.removeIf(structure -> touched.contains(structure.id));
        for (HologramPresets.Cell cell : s.virtualPreset.palette) {
            List<String> members = cell.memberships();
            members.removeAll(touched);
            cell.structureIds = members;
            cell.structureId = members.isEmpty() ? "" : members.get(0);
        }
        s.localEdits.invalidate();
        HologramAuthoringWorkflow.blueprintEdited(s, "geometry");
        s.status = "已删除选中蓝图内容，可撤销；现场方块保持原状";
    }

    static int[] geometryIndices(HologramSession s, NBTTagCompound action) throws java.io.IOException {
        int[] raw = action.getIntArray("indices");
        if (raw.length == 0) raw = action.getIntArray("selectedIndices");
        if (raw.length == 0)
            raw = new int[] { action.hasKey("selected", 3) ? action.getInteger("selected") : s.selected };
        if (raw.length > HologramCapture.LIMIT) throw new java.io.IOException("选择数量超过蓝图上限");
        for (int index : raw) if (index < 0 || index >= s.capture.cells.size()
            || !HologramSessionPolicy.editableStructureCell(s, s.capture.cells.get(index)))
            throw new java.io.IOException("选择位置或结构已变化，请重新选择");
        return java.util.Arrays.stream(raw)
            .distinct()
            .toArray();
    }

    static void blueprintClipboard(HologramSession s, NBTTagCompound action, String op) throws java.io.IOException {
        if ("editCopy".equals(op) || "editCut".equals(op)) {
            int[] indices = geometryIndices(s, action);
            if (indices.length == 0) throw new java.io.IOException("请选择蓝图格");
            List<HologramCapture.Cell> clipboard = new ArrayList<>();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (int index : indices) {
                HologramCapture.Cell source = s.capture.cells.get(index);
                minX = Math.min(minX, source.x);
                minY = Math.min(minY, source.y);
                minZ = Math.min(minZ, source.z);
            }
            for (int index : indices) {
                HologramCapture.Cell source = s.capture.cells.get(index);
                HologramCapture.Cell copy = HologramBlueprintData
                    .blueprintCell(source, source.x - minX, source.y - minY, source.z - minZ);
                ItemStack target = HologramCellPolicy.targetFor(s, source, index);
                if (HologramRecovery.hasSeal(target)) throw new java.io.IOException("封存设备不能复制真实库存");
                copy.actualStack = target == null ? null : target.copy();
                copy.actualFacing = s.facingPins
                    .getOrDefault(HologramValues.position(source), s.planFacings.getOrDefault(index, -1));
                clipboard.add(copy);
            }
            s.blueprintClipboard.clear();
            s.blueprintClipboard.addAll(clipboard);
            if ("editCut".equals(op)) {
                for (int index : indices) s.capture.cells.get(index).excluded = true;
                java.util.Set<Integer> dirty = new java.util.LinkedHashSet<>();
                for (int index : indices) dirty.add(index);
                refreshEdited(s, dirty);
            }
            s.status = "editCut".equals(op) ? "已剪切蓝图规划，原位置标为排除；世界保持原状" : "已复制选中蓝图格及设备配置";
        } else {
            int index = action.hasKey("selected") ? action.getInteger("selected") : s.selected;
            if (index < 0 || index >= s.capture.cells.size() || s.blueprintClipboard.isEmpty())
                throw new java.io.IOException("请先复制并选择粘贴锚点");
            HologramCapture.Cell anchor = s.capture.cells.get(index);
            List<HologramCapture.Cell> copies = new ArrayList<>();
            for (HologramCapture.Cell source : s.blueprintClipboard) {
                HologramCapture.Cell cell = HologramBlueprintData
                    .blueprintCell(source, anchor.x + source.x, anchor.y + source.y, anchor.z + source.z);
                validateBlueprintCoordinate(s, cell.x, cell.y, cell.z);
                copies.add(cell);
            }
            stageGeometry(s, copies, java.util.Collections.emptySet(), true);
            s.status = "已粘贴蓝图规划；实际放置才扣除材料";
        }
    }

    static void moveBlueprintCells(HologramSession s, NBTTagCompound action) throws java.io.IOException {
        if (!s.virtual || s.capture == null || s.virtualPreset == null || s.virtualPreset.schema < 2)
            throw new java.io.IOException("坐标移动用于区域与蓝图编辑");
        if (s.capturePending || s.capture.pending
            || s.capture.incomplete
            || s.job == 1
            || s.job == 2
            || !s.player.capabilities.allowEdit) throw new java.io.IOException("请等待完整采集并结束施工后编辑蓝图");
        if (action.hasKey("planRevision")
            && (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision))
            throw new java.io.IOException("蓝图计划已更新，请重新选择移动目标");
        if ((!action.hasKey("dx", 3) && !action.hasKey("dy", 3) && !action.hasKey("dz", 3))
            || (action.hasKey("dx") && !action.hasKey("dx", 3))
            || (action.hasKey("dy") && !action.hasKey("dy", 3))
            || (action.hasKey("dz") && !action.hasKey("dz", 3))) throw new java.io.IOException("移动坐标无效");
        int[] indices = geometryIndices(s, action);
        if (indices.length == 0) throw new java.io.IOException("请先选择蓝图格");
        java.util.Set<String> complete = completeGeometrySelection(s, indices);
        // A rejected geometry request must acknowledge this valid selection, not the previous tool's selection.
        s.selectedIndices.clear();
        for (int index : indices) s.selectedIndices.add(index);
        int selected = action.hasKey("selected", 3) ? action.getInteger("selected") : s.selected;
        s.selected = s.selectedIndices.contains(selected) ? selected : indices[0];
        if (action.getInteger("dx") == 0 && action.getInteger("dy") == 0 && action.getInteger("dz") == 0) return;
        java.util.Set<String> remove = new java.util.HashSet<>();
        Map<String, HologramCapture.Cell> retained = new HashMap<>();
        List<HologramCapture.Cell> copies = new ArrayList<>();
        for (int index : indices) {
            HologramCapture.Cell source = s.capture.cells.get(index);
            long x = (long) source.x + action.getInteger("dx"), y = (long) source.y + action.getInteger("dy"),
                z = (long) source.z + action.getInteger("dz");
            validateBlueprintCoordinate(s, x, y, z);
            if (!HologramSessionPolicy.authoring(s) && (!s.player.worldObj.blockExists((int) x, (int) y, (int) z)
                || !s.player.worldObj.isAirBlock((int) x, (int) y, (int) z)))
                throw new java.io.IOException("移动目标必须是已加载的空气方块；原选区保留");
            HologramCapture.Cell copy = HologramBlueprintData.blueprintCell(source, (int) x, (int) y, (int) z);
            copy.stableIndex = action.getBoolean("copy") ? -1 : index;
            HologramBlueprintData.setMemberships(
                copy,
                source.memberships()
                    .stream()
                    .filter(complete::contains)
                    .collect(java.util.stream.Collectors.toList()));
            ItemStack target = HologramCellPolicy.targetFor(s, source, index);
            if (action.getBoolean("copy") && HologramRecovery.hasSeal(target))
                throw new java.io.IOException("封存设备不能复制真实库存");
            copy.actualStack = target == null ? null : target.copy();
            copy.actualFacing = s.facingPins
                .getOrDefault(HologramValues.position(source), s.planFacings.getOrDefault(index, -1));
            copies.add(copy);
            if (!action.getBoolean("copy")) {
                List<String> remaining = source.memberships()
                    .stream()
                    .filter(id -> !complete.contains(id))
                    .collect(java.util.stream.Collectors.toList());
                if (remaining.isEmpty()) remove.add(HologramValues.position(source));
                else {
                    HologramCapture.Cell stay = HologramBlueprintData
                        .blueprintCell(source, source.x, source.y, source.z);
                    HologramBlueprintData.setMemberships(stay, remaining);
                    retained.put(HologramValues.position(source), stay);
                }
            }
        }
        stageGeometry(s, copies, remove, false, retained, action.getBoolean("copy"));
        s.status = action.getBoolean("copy") ? "已复制选中蓝图格到新坐标" : "已移动选中蓝图格；世界保持原状";
    }

    static void validateBlueprintCoordinate(HologramSession s, long x, long y, long z) throws java.io.IOException {
        if (x < -30000000L || x >= 30000000L
            || z < -30000000L
            || z >= 30000000L
            || y < 0
            || y >= s.player.worldObj.getHeight()) throw new java.io.IOException("目标坐标超出世界边界");
    }

    /** Validate the complete geometry before changing any server-owned coordinate map. */
    static void stageGeometry(HologramSession s, List<HologramCapture.Cell> copies, java.util.Set<String> remove,
        boolean overlay) throws java.io.IOException {
        stageGeometry(s, copies, remove, overlay, java.util.Collections.emptyMap(), remove.isEmpty());
    }

    static void stageGeometry(HologramSession s, List<HologramCapture.Cell> copies, java.util.Set<String> remove,
        boolean overlay, Map<String, HologramCapture.Cell> retained, boolean copyGroups) throws java.io.IOException {
        stageGeometry(s, copies, remove, overlay, retained, copyGroups, null);
    }

    static void stageGeometry(HologramSession s, List<HologramCapture.Cell> copies, java.util.Set<String> remove,
        boolean overlay, Map<String, HologramCapture.Cell> retained, boolean copyGroups,
        Map<String, Integer> preparedSizes) throws java.io.IOException {
        stageGeometry(s, copies, remove, overlay, retained, copyGroups, preparedSizes, null);
    }

    static void stageGeometry(HologramSession s, List<HologramCapture.Cell> copies, java.util.Set<String> remove,
        boolean overlay, Map<String, HologramCapture.Cell> retained, boolean copyGroups,
        Map<String, Integer> preparedSizes, Map<String, ItemStack> projectedTargets) throws java.io.IOException {
        Map<String, HologramCapture.Cell> staged = new LinkedHashMap<>();
        Map<String, ItemStack> targets = new HashMap<>();
        Map<String, Integer> facings = new HashMap<>(s.facingPins);
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (remove.contains(HologramValues.position(cell))) continue;
            staged.put(HologramValues.position(cell), retained.getOrDefault(HologramValues.position(cell), cell));
            ItemStack target = projectedTargets == null ? HologramCellPolicy.targetFor(s, cell, index)
                : projectedTargets.get(HologramValues.position(cell));
            if (target != null) targets.put(HologramValues.position(cell), target.copy());
        }
        List<HologramPresets.Structure> copiedStructures = new ArrayList<>();
        Map<String, String> groupIds = new HashMap<>();
        if (copyGroups) for (HologramCapture.Cell copy : copies) {
            if (!"templateController".equals(copy.role) || copy.structureId.isEmpty()) continue;
            for (HologramPresets.Structure original : s.virtualPreset.structures)
                if (original.id.equals(copy.structureId)) {
                    HologramPresets.Structure duplicate = HologramBlueprintData.copyStructure(original);
                    duplicate.id = UUID.randomUUID()
                        .toString();
                    duplicate.x = copy.x - s.x;
                    duplicate.y = copy.y - s.y;
                    duplicate.z = copy.z - s.z;
                    copiedStructures.add(duplicate);
                    groupIds.put(original.id, duplicate.id);
                }
        }
        java.util.Set<String> added = new java.util.HashSet<>();
        if (copyGroups) for (HologramPresets.Structure original : s.virtualPreset.structures) {
            if (original.textBoxNbt == null || original.textBoxNbt.isEmpty() || groupIds.containsKey(original.id))
                continue;
            int[] before = textMinimum(s.capture.cells, original.id), after = textMinimum(copies, original.id);
            if (after == null || before == null) continue;
            HologramPresets.Structure duplicate = HologramBlueprintData.copyStructure(original);
            duplicate.id = (HologramTextWorkflow.definition(original)
                .hasKey("picture", 10) ? HologramPictureBox.PREFIX : HologramTextBox.PREFIX) + UUID.randomUUID();
            duplicate.x += after[0] - before[0];
            duplicate.y += after[1] - before[1];
            duplicate.z += after[2] - before[2];
            copiedStructures.add(duplicate);
            groupIds.put(original.id, duplicate.id);
        }
        for (HologramCapture.Cell copy : copies) {
            String key = HologramValues.position(copy);
            if (!added.add(key) || (!overlay && staged.containsKey(key)))
                throw new java.io.IOException("目标与其他蓝图格重叠，坐标未修改");
            List<String> rewritten = new ArrayList<>();
            boolean copyingGroup = copy.memberships()
                .stream()
                .anyMatch(groupIds::containsKey);
            for (String member : copy.memberships()) {
                if (copyingGroup && !groupIds.containsKey(member)) continue;
                rewritten.add(groupIds.getOrDefault(member, member));
            }
            HologramBlueprintData.setMemberships(copy, rewritten);
            staged.put(key, copy);
            if (copy.actualStack == null) targets.remove(key);
            else targets.put(key, copy.actualStack.copy());
            facings.remove(key);
            if (copy.actualFacing >= 0) facings.put(key, copy.actualFacing);
        }
        if (staged.size() > HologramCapture.LIMIT || s.virtualPreset.structures.size() + copiedStructures.size() > 256)
            throw new java.io.IOException("复制后的蓝图方块或结构数量超过上限");
        long expanded = 0;
        Map<String, Integer> sizes = new HashMap<>();
        List<String> payloads = new ArrayList<>();
        for (HologramCapture.Cell cell : staged.values()) {
            payloads.add(cell.tileNbt);
            payloads.add(cell.itemNbt);
        }
        for (HologramPresets.Cell cell : s.virtualPreset.palette) {
            payloads.add(cell.tileNbt);
            payloads.add(cell.itemNbt);
        }
        for (String value : payloads) {
            if (value == null || value.isEmpty()) continue;
            Integer size = sizes.get(value);
            if (size == null) {
                if (preparedSizes == null) {
                    size = HologramSessionPolicy.authoring(s) ? s.geometryEdits.payloadSizes.get(value) : null;
                    if (size == null) size = HologramBlueprintNbt.expandedBytes(value);
                } else size = preparedSizes.get(value);
                if (size == null) throw new java.io.IOException("附加对象数据预算已变化，请重新确认");
                sizes.put(value, size);
            }
            expanded += size;
            if (expanded > 64L * 1024 * 1024) throw new java.io.IOException("复制后的设备NBT超过64MiB上限");
        }
        s.geometryEdits.begin(s);
        if (HologramSessionPolicy.authoring(s)) s.geometryEdits.payloadSizes = sizes;
        remember(s.undoEdits, new HologramEditState(s));
        s.redoEdits.clear();
        if (!copyGroups && !remove.isEmpty()) for (HologramPresets.Structure structure : s.virtualPreset.structures) {
            if (structure.textBoxNbt == null || structure.textBoxNbt.isEmpty()) continue;
            int[] before = textMinimum(s.capture.cells, structure.id), after = textMinimum(copies, structure.id);
            if (before == null || after == null) continue;
            structure.x += after[0] - before[0];
            structure.y += after[1] - before[1];
            structure.z += after[2] - before[2];
        }
        s.virtualPreset.structures.addAll(copiedStructures);
        if (!copyGroups) for (HologramCapture.Cell copy : copies) if ("templateController".equals(copy.role))
            for (HologramPresets.Structure structure : s.virtualPreset.structures)
                if (structure.id.equals(copy.structureId)) {
                    structure.x = copy.x - s.x;
                    structure.y = copy.y - s.y;
                    structure.z = copy.z - s.z;
                }
        HologramCapture capture = new HologramCapture(null, HologramSessionPolicy.trigger(s));
        capture.cells.addAll(
            HologramSessionPolicy.authoring(s) ? HologramGeometryEdits.stableOrder(s.capture.cells, staged, copies)
                : staged.values());
        capture.positions.putAll(staged);
        s.capture = capture;
        s.pins.clear();
        s.coordinatePins.clear();
        s.autoTargets.clear();
        s.frozenAutoTargets.clear();
        s.manualPositions.clear();
        s.virtualEdited.clear();
        s.selectedIndices.clear();
        s.allocationsFrozen = true;
        s.facingPins.clear();
        s.facingPins.putAll(facings);
        for (int index = 0; index < capture.cells.size(); index++) {
            HologramCapture.Cell cell = capture.cells.get(index);
            String key = HologramValues.position(cell);
            ItemStack target = targets.get(key);
            if (target != null) {
                s.pins.put(index, target);
                s.coordinatePins.put(key, target.copy());
            }
            s.virtualEdited.add(key);
            if (added.contains(key)) s.selectedIndices.add(index);
        }
        s.selected = s.selectedIndices.isEmpty() ? -1
            : s.selectedIndices.iterator()
                .next();
        if (HologramSessionPolicy.authoring(s)) HologramGeometryEdits.refresh(s);
        else HologramResourcePlanner.refreshPlan(s);
    }

    private static int[] textMinimum(List<HologramCapture.Cell> cells, String group) {
        int[] result = null;
        for (HologramCapture.Cell cell : cells) if (cell.belongsTo(group)) {
            if (result == null) result = new int[] { cell.x, cell.y, cell.z };
            else {
                result[0] = Math.min(result[0], cell.x);
                result[1] = Math.min(result[1], cell.y);
                result[2] = Math.min(result[2], cell.z);
            }
        }
        return result;
    }

    /** An explicit preview confirmation excludes only server-reported blocker cells; it never removes world blocks. */
    static void excludeBlueprintBlockers(HologramSession s, NBTTagCompound action) throws java.io.IOException {
        if (!s.virtual || s.virtualPreset == null
            || s.capture == null
            || !action.getBoolean("confirmDiscard")
            || !action.hasKey("planRevision", 4)
            || action.getLong("planRevision") != s.planRevision) throw new java.io.IOException("请先确认排除校验报告中的阻塞蓝图格");
        HologramEditState before = new HologramEditState(s);
        int excluded = 0;
        for (HologramAePolicy.Issue issue : s.virtualPreset.aeIssues) {
            if (!"blocker".equals(issue.severity)) continue;
            HologramCapture.Cell cell = s.capture.positions
                .get((s.x + issue.x) + "," + (s.y + issue.y) + "," + (s.z + issue.z));
            if (cell != null && !cell.anchor && !cell.excluded) {
                cell.excluded = true;
                excluded++;
            }
        }
        remember(s.undoEdits, before);
        s.redoEdits.clear();
        s.ae.invalidate();
        HologramResourcePlanner.refreshPlan(s);
        s.status = "已排除 " + excluded + " 个阻塞蓝图格，世界与材料保持，可撤销该编辑";
    }
}
