package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

/** Selection, import and per-structure editing of a server-owned area. */
final class HologramAreaWorkflow {

    private HologramAreaWorkflow() {}

    static void toggleAreaSelection(EntityPlayerMP player) {
        HologramSession s = HologramProjectorWorkflow.uiSession(player);
        if (s.job == 1 || s.job == 2) {
            s.status = "请先取消当前施工任务再选择区域";
            HologramSnapshots.send(s);
            return;
        }
        HologramArea area = HologramWorkspace.AREAS
            .computeIfAbsent(player.getUniqueID(), ignored -> new HologramArea());
        if (area.importing) {
            area.importing = false;
            s.status = "区域导入已取消，原规划保留";
        } else {
            area.selecting = !area.selecting;
            HologramSessionPolicy.currentTool(s)
                .getTagCompound()
                .setBoolean("gtitHologramAreaSelecting", area.selecting);
            player.inventory.markDirty();
            s.status = area.selecting ? "区域选择已开启：左键第一点，右键第二点；Alt 使用玩家坐标" : "区域选择已关闭";
            if (!area.selecting && area.first != null && area.second != null) startAreaImport(s, area);
        }
        player.addChatMessage(new ChatComponentText("[猫猫蓝图全息投影仪] " + s.status));
        HologramSnapshots.send(s);
    }

    /** Begin and finish are separate, idempotent gestures; a repeated click never starts an import. */
    static void beginAreaSelection(EntityPlayerMP player) {
        HologramSession s = HologramProjectorWorkflow.uiSession(player);
        if (s.job == 1 || s.job == 2) {
            s.status = "请先完成或取消当前施工，再框选区域";
            HologramSnapshots.send(s);
            return;
        }
        HologramArea existing = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (existing != null && (existing.selecting || existing.importing)) return;
        HologramArea area = new HologramArea();
        HologramWorkspace.AREAS.put(player.getUniqueID(), area);
        area.selecting = true;
        HologramSessionPolicy.currentTool(s)
            .getTagCompound()
            .setBoolean("gtitHologramAreaSelecting", true);
        player.inventory.markDirty();
        HologramProjectorWorkflow.libraryCloseUi(s);
        s.status = "左键第一角，右键第二角；浮空框选，Alt + 滚轮调整距离；再次 Shift + 右键完成，Esc 取消";
        HologramSnapshots.send(s);
    }

    static void finishAreaSelection(EntityPlayerMP player, NBTTagCompound request) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (s == null || s.player != player
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || s.planRevision != request.getLong("planRevision")
            || !HologramSessionPolicy.valid(s)
            || s.job == 1
            || s.job == 2
            || area == null
            || !area.selecting) return;
        if (request.hasKey("uiSequence", 4)) {
            long sequence = request.getLong("uiSequence");
            if (sequence <= s.uiSequence) return;
            s.uiSequence = sequence;
        }
        if (area.regions.isEmpty() && (area.first == null || area.second == null)) {
            s.status = "请先设置两个角点，再次 Shift + 右键完成";
            HologramSnapshots.send(s);
            return;
        }
        area.selecting = false;
        HologramWorkspace.clearAreaMarker(s, HologramSessionPolicy.currentTool(s));
        player.inventory.markDirty();
        startAreaImport(s, area);
        HologramSnapshots.send(s);
    }

    static void cancelAreaSelection(EntityPlayerMP player, String session) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (s == null || s.player != player
            || !s.id.equals(session)
            || !HologramWorkspace.areaSessionOwned(s)
            || s.job == 1
            || s.job == 2
            || area == null
            || !area.selecting && !area.importing) return;
        HologramWorkspace.clearAreaSelection(s);
        HologramProjectorWorkflow.libraryCloseUi(s);
        s.status = "区域框选已取消，当前蓝图保留";
        HologramSnapshots.send(s);
    }

    static void worldAreaPoint(EntityPlayerMP player, NBTTagCompound request) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s == null || s.player != player
            || !s.id.equals(request.getString("session"))
            || !HologramSessionPolicy.valid(s)
            || !request.hasKey("planRevision", 4)
            || request.getLong("planRevision") != s.planRevision) return;
        areaPoint(
            player,
            request.getInteger("point"),
            request.getInteger("x"),
            request.getInteger("y"),
            request.getInteger("z"),
            request.getBoolean("atPlayer"),
            request.hasKey("mode", 8) ? request.getString("mode") : "replace");
    }

    static void undoAreaSelection(EntityPlayerMP player, NBTTagCompound request) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (s == null || s.player != player
            || !HologramSessionPolicy.valid(s)
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || request.getLong("planRevision") != s.planRevision
            || area == null
            || !area.selecting
            || s.job == 1
            || s.job == 2) return;
        area.undoRegion();
        s.status = "已撤销上一步区域组合";
        HologramSnapshots.send(s);
    }

    static void areaPoint(EntityPlayerMP player, int point, int x, int y, int z, boolean atPlayer) {
        areaPoint(player, point, x, y, z, atPlayer, "replace");
    }

    static void areaPoint(EntityPlayerMP player, int point, int x, int y, int z, boolean atPlayer, String mode) {
        HologramSession s = HologramProjectorWorkflow.uiSession(player);
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (area == null || !area.selecting || point < 1 || point > 2) return;
        if (atPlayer) {
            x = net.minecraft.util.MathHelper.floor_double(player.posX);
            y = net.minecraft.util.MathHelper.floor_double(player.posY);
            z = net.minecraft.util.MathHelper.floor_double(player.posZ);
        }
        if (y < 0 || y >= player.worldObj.getHeight()
            || !player.worldObj.blockExists(x, y, z)
            || player.getDistanceSq(x + .5, y + .5, z + .5) > 4096) return;
        try {
            area.point(point, x, y, z, mode);
        } catch (java.io.IOException invalid) {
            s.status = invalid.getMessage();
            HologramSnapshots.send(s);
            return;
        }
        s.status = "区域点 " + point + " = " + x + ", " + y + ", " + z;
        player.addChatMessage(new ChatComponentText("[猫猫蓝图全息投影仪] " + s.status));
        HologramSnapshots.send(s);
    }

    static boolean startAreaImport(HologramSession s, HologramArea area) {
        if (HologramSessionPolicy.blockedByAreaImport(s) || s.job == 1 || s.job == 2) {
            s.status = "请先取消区域导入或当前施工任务，再重新导入";
            return false;
        }
        if (HologramAuthoringWorkflow.blueprintHasUnsavedChanges(s)) {
            NBTTagCompound next = new NBTTagCompound();
            next.setString("op", "importArea");
            next.setString("presetId", "area");
            HologramAuthoringWorkflow.requestSourceSwitch(s, next, null, null, area);
            return false;
        }
        try {
            area.begin(s.player.worldObj);
            s.areaImportPreviousDirty = s.blueprintDirty;
            s.areaImportPreviousSource = s.blueprintSource;
            HologramSession.cancelCaptureRetry(s);
            HologramSession.clearPending(s);
            s.ae.invalidate();
            s.planRevision++;
            s.blueprintAwaitingConfirmation = false;
            s.forcePlacementReady = false;
            s.placementReviewNonce++;
            s.virtualPlacement = false;
            s.virtualLocked = false;
            s.status = "正在分段导入区域与声明结构，请稍候";
            return true;
        } catch (java.io.IOException failure) {
            s.status = "区域导入失败：" + failure.getMessage();
            return false;
        }
    }

    /** Closing cancels only the unfinished import; the previous draft, targets and world remain intact. */
    static void cancelAreaImport(HologramSession s) {
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null) {
            if (area.importing) {
                s.blueprintDirty = s.areaImportPreviousDirty;
                s.blueprintSource = s.areaImportPreviousSource;
            }
            area.cancelImportWork();
            area.selecting = false;
        }
        s.ae.invalidate();
        HologramProjectorWorkflow.libraryCloseUi(s);
        s.settingsOnly = false;
        s.status = "区域导入已取消，原蓝图保留";
    }

    /** Warning navigation remains in the server-owned virtual draft, even when live area controllers exist. */
    static boolean selectVirtualStructure(HologramSession s, NBTTagCompound action) {
        if (!s.virtual || s.virtualPreset == null
            || s.virtualPreset.schema < 2
            || s.capture == null
            || s.capture.incomplete
            || s.capture.pending
            || s.capturePending
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || !action.hasKey("virtualOnly", 1)
            || !action.getBoolean("virtualOnly")
            || !action.hasKey("planRevision", 4)
            || action.getLong("planRevision") != s.planRevision
            || !action.hasKey("structureId", 8)
            || HologramSessionPolicy.blockedByAreaImport(s)) {
            s.status = "虚拟结构导航已过期或当前蓝图不可编辑，请重新核对报告";
            return false;
        }
        String id = action.getString("structureId");
        HologramPresets.Structure selected = id.isEmpty() ? null
            : HologramBlueprintData.blueprintSource(s.virtualPreset, id);
        boolean savedMember = s.virtualPreset.cells.stream()
            .anyMatch(cell -> id.isEmpty() || cell.belongsTo(id));
        boolean capturedMember = s.capture.cells.stream()
            .anyMatch(cell -> id.isEmpty() || cell.belongsTo(id));
        if ((!id.isEmpty() && (selected == null || !selected.enabled)) || !savedMember || !capturedMember) {
            s.status = "该结构不属于当前蓝图、已停用或没有蓝图格，导航未修改";
            return false;
        }
        HologramBlueprintData.saveStructureConfiguration(s);
        HologramBlueprintData.loadStructureConfiguration(s, id);
        s.selectedIndices.clear();
        for (int index = 0; index < s.capture.cells.size(); index++) if (id.isEmpty() || s.capture.cells.get(index)
            .belongsTo(id)) s.selectedIndices.add(index);
        s.scope = id.isEmpty() ? 0 : 2;
        s.selected = s.selectedIndices.isEmpty() ? -1
            : s.selectedIndices.iterator()
                .next();
        s.blueprintActiveStructure = id;
        HologramResourcePlanner.refreshPlan(s);
        s.open = true;
        s.status = id.isEmpty() ? "已返回整份虚拟蓝图，请继续处理报告中的问题" : "已切换虚拟蓝图结构，请继续处理报告中的问题";
        return true;
    }

    static HologramSession selectAreaStructure(HologramSession previous, String id) {
        HologramArea area = HologramWorkspace.AREAS.get(previous.player.getUniqueID());
        if (previous.virtual && area != null
            && previous.virtualPreset != area.blueprint
            && previous.areaOverviewOwner != area) area = null;
        if (area != null && area.imported && previous.virtual && previous.virtualPreset == area.blueprint) {
            try {
                area.edited.addAll(previous.virtualEdited);
                area.blueprint = HologramBlueprintProjection.blueprint(previous, area.blueprint.name);
                previous.virtualPreset = area.blueprint;
            } catch (java.io.IOException failure) {
                previous.status = "区域编辑尚不能同步：" + failure.getMessage();
                return previous;
            }
        }
        if (area != null && area.imported) synchronizeAreaStructure(previous, area);
        if (previous.virtual && previous.virtualPreset != null
            && !previous.virtualPreset.structures.isEmpty()
            && (area == null || !area.imported || (!id.isEmpty() && !area.structures.containsKey(id)))) {
            if (!id.isEmpty() && HologramBlueprintData.blueprintSource(previous.virtualPreset, id) == null) {
                previous.status = "结构不存在，选择未修改";
                return previous;
            }
            HologramBlueprintData.saveStructureConfiguration(previous);
            HologramBlueprintData.loadStructureConfiguration(previous, id);
            previous.selectedIndices.clear();
            for (int index = 0; index < previous.capture.cells.size(); index++)
                if (id.isEmpty() || previous.capture.cells.get(index)
                    .belongsTo(id)) previous.selectedIndices.add(index);
            previous.scope = id.isEmpty() ? 0 : 2;
            previous.selected = previous.selectedIndices.isEmpty() ? -1
                : previous.selectedIndices.iterator()
                    .next();
            previous.blueprintActiveStructure = id;
            HologramResourcePlanner.refreshPlan(previous);
            previous.open = true;
            return previous;
        }
        if (area == null || !area.imported) {
            previous.status = "请先导入施工区域";
            return previous;
        }
        if (id.isEmpty()) {
            HologramSession editor = HologramWorkspace.AREA_EDITORS.get(previous.player.getUniqueID());
            if (editor != null && HologramSessionPolicy.valid(editor)) {
                area.active = "";
                HologramBlueprintProjection.populateVirtual(editor);
                HologramResourcePlanner.refreshPlan(editor);
                HologramWorkspace.activateAreaSession(editor);
                editor.open = true;
                HologramWorkspace.activate(previous.player.getUniqueID(), editor);
                return editor;
            }
            return previous;
        }
        HologramArea.Structure structure = area.structures.get(id);
        if (structure == null) {
            previous.status = "声明结构已不存在";
            return previous;
        }
        if (previous.player.worldObj.getTileEntity(structure.x, structure.y, structure.z)
            != structure.machine.getBaseMetaTileEntity()
            || previous.player.getDistanceSq(structure.x + .5, structure.y + .5, structure.z + .5) > 4096) {
            previous.status = "结构控制器已变化或距离过远，请重新导入区域";
            return previous;
        }
        if (area.active.isEmpty()) HologramWorkspace.AREA_EDITORS.put(previous.player.getUniqueID(), previous);
        Map<String, HologramSession> editors = HologramWorkspace.AREA_STRUCTURES
            .computeIfAbsent(previous.player.getUniqueID(), ignored -> new HashMap<>());
        HologramSession selected = editors.get(id);
        if (selected == null || !HologramSessionPolicy.valid(selected)) {
            selected = HologramWorkspace
                .create(previous.player, structure.x, structure.y, structure.z, true, previous.side);
            selected.areaStructureId = id;
            selected.main = 1;
            selected.mainExplicit = false;
            selected.channels = new NBTTagCompound();
            HologramPresets.Structure saved = HologramBlueprintData.blueprintSource(area.blueprint, id);
            if (saved != null) HologramBlueprintData.applyStructureConfiguration(selected, saved);
            editors.put(id, selected);
        }
        HologramBuildPreparation.scan(selected);
        HologramAreaDeclarations.bindAreaMemberships(selected);
        restoreAreaTargets(selected, area);
        area.active = id;
        HologramWorkspace.activateAreaSession(selected);
        selected.open = true;
        HologramWorkspace.activate(previous.player.getUniqueID(), selected);
        return selected;
    }

    /** Membership is tied to all real declarations at a position, including shared walls. */

    /** Commit planning changes to the shared area template before changing editors. */
    static void synchronizeAreaStructure(HologramSession s, HologramArea area) {
        if (s.areaStructureId.isEmpty() || s.capture == null || s.capture.incomplete || s.capturePending) return;
        HologramPresets.Structure savedStructure = HologramBlueprintData
            .blueprintSource(area.blueprint, s.areaStructureId);
        if (savedStructure != null) HologramBlueprintData.storeStructureConfiguration(s, savedStructure);
        Map<String, HologramPresets.Cell> savedCells = new HashMap<>();
        for (HologramPresets.Cell saved : area.blueprint.cells)
            savedCells.put((area.minX + saved.x) + "," + (area.minY + saved.y) + "," + (area.minZ + saved.z), saved);
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            HologramPresets.Cell saved = savedCells.get(HologramValues.position(cell));
            if (saved == null || cell.anchor) continue;
            ItemStack target = HologramCellPolicy.draftTarget(s, cell, index);
            if (!HologramCellPolicy.acceptableSample(s, cell, target)) continue;
            int targetMetadata;
            try {
                targetMetadata = HologramCellPolicy.targetPreviewMeta(s, cell, target);
            } catch (java.io.IOException invalidVariant) {
                s.status = invalidVariant.getMessage();
                continue;
            }
            boolean changed = !HologramValues.sameMaterial(saved.stack(), target);
            if (changed || HologramCellPolicy.explicitTarget(s, cell, index))
                area.edited.add(HologramValues.position(cell));
            HologramPresets.Cell identity = HologramPresets.cell(
                target,
                saved.x,
                saved.y,
                saved.z,
                s.facingPins.getOrDefault(HologramValues.position(cell), saved.facing),
                saved.controller);
            saved.item = identity.item;
            saved.damage = identity.damage;
            if (changed) {
                saved.block = String.valueOf(
                    Block.blockRegistry.getNameForObject(HologramCellPolicy.targetPreviewBlock(s, cell, target)));
                saved.metadata = targetMetadata;
                saved.tileNbt = null;
                saved.itemNbt = null;
            }
            saved.facing = identity.facing;
            saved.allowedTargets = HologramBlueprintData.savedTargets(HologramCellPolicy.candidates(s, cell));
            saved.prompt = cell.prompt;
            saved.excluded = cell.excluded;
        }
    }

    static void restoreAreaTargets(HologramSession s, HologramArea area) {
        if (s.capture == null || s.capture.incomplete || s.capturePending) return;
        Map<String, HologramPresets.Cell> savedCells = new HashMap<>();
        for (HologramPresets.Cell saved : area.blueprint.cells)
            savedCells.put((area.minX + saved.x) + "," + (area.minY + saved.y) + "," + (area.minZ + saved.z), saved);
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            HologramPresets.Cell saved = savedCells.get(HologramValues.position(cell));
            if (saved == null || cell.anchor) continue;
            cell.prompt = saved.prompt == null ? "" : saved.prompt;
            cell.excluded = saved.excluded;
            ItemStack target = saved.stack();
            if (!area.edited.contains(HologramValues.position(cell))
                || !HologramCellPolicy.acceptableSample(s, cell, target)) continue;
            s.pins.put(index, target);
            s.coordinatePins.put(HologramValues.position(cell), target.copy());
            if (saved.facing >= 0) for (int direction : HologramCellPolicy.legalFacings(s, cell, target))
                if (direction == saved.facing) s.facingPins.put(HologramValues.position(cell), direction);
        }
        HologramResourcePlanner.refreshPlan(s);
    }

    /** Preserve user policy and request ownership; all source-specific pins/allocations belong to the new draft. */
    static HologramSession importStaging(HologramSession source, HologramArea area) {
        HologramSession staged = new HologramSession(
            source.player,
            area.minX,
            area.minY,
            area.minZ,
            false,
            source.side);
        staged.id = source.id;
        staged.uiSequence = source.uiSequence;
        staged.planRevision = source.planRevision;
        staged.blueprintClientTaskToken = source.blueprintClientTaskToken;
        staged.libraryQuickClientTaskToken = source.libraryQuickClientTaskToken;
        staged.libraryQuickToken = source.libraryQuickToken;
        staged.libraryQuickPending = source.libraryQuickPending;
        staged.libraryQuickBootstrapTick = source.libraryQuickBootstrapTick;
        staged.libraryInitialSnapshot = source.libraryInitialSnapshot;
        staged.libraryUiActive = source.libraryUiActive;
        staged.materialSettings = source.materialSettings;
        staged.main = source.main;
        staged.mainExplicit = source.mainExplicit;
        staged.channels = (NBTTagCompound) source.channels.copy();
        staged.capabilities = source.capabilities;
        staged.hatchTier = source.hatchTier;
        staged.hatchDowngrade = source.hatchDowngrade;
        staged.noHatches = source.noHatches;
        staged.hints = source.hints;
        staged.facing = source.facing;
        staged.layer = source.layer;
        staged.presetEnabled = source.presetEnabled;
        staged.presetPaused = source.presetPaused;
        staged.workspaceTab = source.workspaceTab;
        staged.retainedNbtKeys = source.retainedNbtKeys;
        staged.placementNbtKeys = source.placementNbtKeys;
        staged.placementNbtCustomized = source.placementNbtCustomized;
        staged.deidentificationChosen = source.deidentificationChosen;
        staged.placementDeidentificationChosen = source.placementDeidentificationChosen;
        staged.placementDeidentificationLevel = source.placementDeidentificationLevel;
        staged.placementRetainCovers = source.placementRetainCovers;
        staged.blueprintSavedId = source.blueprintSavedId;
        staged.blueprintPrompt = source.blueprintPrompt;
        staged.selectedProcessingPresetId = source.selectedProcessingPresetId;
        staged.virtual = true;
        staged.virtualPreset = area.blueprint;
        staged.areaOverviewOwner = area;
        // Fresh source: old mode/scope, bindings, processing review and all coordinate edits are not transferable.
        return staged;
    }

    private static void importStructure(HologramArea area, HologramArea.Structure structure) {
        HologramPresets.Structure saved = new HologramPresets.Structure();
        saved.id = structure.id;
        saved.name = structure.machine.getLocalName();
        saved.x = structure.x - area.minX;
        saved.y = structure.y - area.minY;
        saved.z = structure.z - area.minZ;
        saved.controllerId = structure.machine.getBaseMetaTileEntity()
            .getMetaTileID();
        saved.sourceClass = structure.machine.getClass()
            .getName();
        saved.sourceMod = HologramSessionPolicy.sourceMod(structure.machine.getClass());
        saved.enabled = structure.enabled;
        if (structure.capture != null) saved.main = Math.max(1, structure.capture.trigger.stackSize);
        area.blueprint.structures.add(saved);
    }

    private static void importMembership(HologramArea area, HologramPresets.Cell saved) {
        HologramSession staged = area.staging;
        int x = area.minX + saved.x, y = area.minY + saved.y, z = area.minZ + saved.z;
        saved.structureIds = area.memberships(x, y, z);
        saved.structureId = saved.structureIds.isEmpty() ? null : saved.structureIds.get(0);
        if (saved.structureIds.isEmpty() || saved.controller) return;
        List<ItemStack> allowed = null;
        for (String member : saved.structureIds) {
            HologramSession source = HologramAreaDeclarations
                .areaDeclarationSession(staged, area, member, area.declarationSessions);
            HologramCapture.Cell declared = source == null ? null : source.capture.positions.get(x + "," + y + "," + z);
            List<ItemStack> local = declared == null || source.capture.incomplete ? Collections.emptyList()
                : HologramCellPolicy.declaredCandidates(source, declared);
            if (allowed == null) allowed = new ArrayList<>(local);
            else {
                // Material intersections ignore NBT by definition; hash exact item identity + damage once per member.
                Map<net.minecraft.item.Item, java.util.Set<Integer>> materials = new java.util.IdentityHashMap<>();
                for (ItemStack option : local) if (option != null)
                    materials.computeIfAbsent(option.getItem(), ignored -> new java.util.HashSet<>())
                        .add(option.getItemDamage());
                allowed.removeIf(
                    target -> target == null || !materials.getOrDefault(target.getItem(), Collections.emptySet())
                        .contains(target.getItemDamage()));
            }
        }
        saved.allowedTargets = HologramBlueprintData.savedTargets(allowed == null ? Collections.emptyList() : allowed);
    }

    static boolean importOwned(HologramSession s, HologramArea area) {
        return area.importing && HologramWorkspace.find(s.player.getUniqueID()) == s
            && HologramWorkspace.AREAS.get(s.player.getUniqueID()) == area
            && HologramNetwork.progressConnected(s.player)
            && HologramSessionPolicy.valid(s);
    }

    /** Each tail phase owns a real cursor; the currently published editor remains intact until the complete plan. */
    static void tickImport(HologramSession s, HologramArea area) {
        String previousPhase = area.importStage();
        try {
            if (!importOwned(s, area)) throw new java.io.IOException("工具、维度或玩家状态已变化");
            if (!area.imported) {
                area.tick(s.player, HologramSessionPolicy.trigger(s));
            } else if ("memberships".equals(area.importPhase)) {
                if (area.staging == null) area.staging = importStaging(s, area);
                long deadline = HologramWorkBudget.deadline(2_000_000L);
                for (int work = 0; work < 4096; work++) {
                    if (work > 0 && System.nanoTime() >= deadline) break;
                    if (area.structureIndex < area.discovery.size())
                        importStructure(area, area.discovery.get(area.structureIndex++));
                    else if (area.membershipIndex < area.blueprint.cells.size())
                        importMembership(area, area.blueprint.cells.get(area.membershipIndex++));
                    else {
                        area.population = new HologramBlueprintProjection.Population(area.staging);
                        area.importPhase = "projection";
                        break;
                    }
                }
            } else if ("projection".equals(area.importPhase)) {
                if (area.population.step(4096, HologramWorkBudget.deadline(2_000_000L))) {
                    area.planTask = HologramResourcePlanner.beginPlan(area.staging);
                    area.importPhase = "plan";
                }
            } else if ("plan".equals(area.importPhase) && area.planTask.step(4096, 2_000_000L)) {
                publishImport(s, area);
                return;
            }
            if (!previousPhase.equals(area.importStage()) || s.player.worldObj.getTotalWorldTime() % 10 == 0)
                HologramSnapshots.sendProgress(s);
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            area.cancelImportWork();
            s.status = "区域导入失败：" + failure.getMessage();
            s.open = true;
            HologramSnapshots.send(s);
        }
    }

    private static void publishImport(HologramSession previous, HologramArea area) throws java.io.IOException {
        if (area.staging == null || !importOwned(previous, area)) throw new java.io.IOException("区域导入会话已过期");
        HologramSession staged = area.staging;
        staged.uiSequence = previous.uiSequence;
        staged.blueprintClientTaskToken = previous.blueprintClientTaskToken;
        staged.libraryQuickClientTaskToken = previous.libraryQuickClientTaskToken;
        staged.placementReviewNonce = previous.placementReviewNonce;
        staged.blueprintDirty = true;
        staged.blueprintSource = "area";
        staged.status = "区域已导入；识别 " + area.structures.size() + " 个声明控制器，可分别编辑与保存蓝图";
        staged.open = true;
        staged.openPurpose = "blueprintEdit";
        HologramWorkspace.AREA_STRUCTURES.put(previous.player.getUniqueID(), area.declarationSessions);
        HologramWorkspace.AREA_EDITORS.put(previous.player.getUniqueID(), staged);
        HologramWorkspace.activate(previous.player.getUniqueID(), staged);
        area.importing = false;
        area.staging = null;
        area.population = null;
        area.planTask = null;
        HologramSnapshots.send(staged);
    }
}
