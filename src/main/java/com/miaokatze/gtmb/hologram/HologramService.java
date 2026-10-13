package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.IMultiblockInfoContainer;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.ICustomBlockSetting;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElement.PlaceResult;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.gtnewhorizon.structurelib.util.ItemStackPredicate;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.interfaces.IHeatingCoil;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.blocks.BlockFrameBox;
import gregtech.common.blocks.ItemMachines;
import tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods;

/** Server-owned previews and bounded jobs. Client cells are never accepted as world operations. */
public final class HologramService {

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, HologramArea> AREAS = new HashMap<>();
    private static final Map<UUID, Session> AREA_EDITORS = new HashMap<>();
    private static final Map<UUID, Map<String, Session>> AREA_STRUCTURES = new HashMap<>();
    private static final int BUDGET = 4096;
    private static final String TOOL_ID = "gtitHologramToolId";
    private static final int CAPTURE_ATTEMPTS = 3;
    private static final String DRAFT = "gtitHologramDraft";
    private static final String SELECTED_PRESET = "gtitHologramPreset";

    private HologramService() {}

    public static void open(EntityPlayerMP player, int x, int y, int z, int side) {
        Session previous = SESSIONS.get(player.getUniqueID());
        if (previous != null && previous.player == player && blockedByAreaImport(previous)) {
            send(previous);
            return;
        }
        if (previous != null && previous.player == player
            && !previous.virtual
            && !previous.cancelled
            && previous.x == x
            && previous.y == y
            && previous.z == z
            && valid(previous)) {
            previous.open = true;
            previous.settingsOnly = false;
            dismissPresetBrowse(previous);
            send(previous);
            return;
        }
        if (previous != null && (previous.job == 1 || previous.job == 2)) {
            previous.job = 4;
            clearPending(previous);
        }
        Session session = new Session(player, x, y, z, true, side);
        SESSIONS.put(player.getUniqueID(), session);
        readDraft(session);
        scan(session);
        persist(session);
        send(session);
    }

    public static void openAir(EntityPlayerMP player) {
        Session previous = SESSIONS.get(player.getUniqueID());
        if (openPlacementReview(previous, player)) return;
        if (previous != null && previous.player == player
            && !previous.cancelled
            && previous.job != 4
            && (previous.target != null || previous.virtual)
            && valid(previous)) {
            previous.open = true;
            previous.settingsOnly = false;
            dismissPresetBrowse(previous);
            send(previous);
            return;
        }
        Session session = uiSession(player);
        session.open = true;
        session.settingsOnly = false;
        session.blueprintPreview = true;
        session.status = "右击真实控制器导入结构，或在蓝图库选择蓝图后点击放置";
        SESSIONS.put(player.getUniqueID(), session);
        send(session);
    }

    /** Ordinary world use imports a constructable controller or opens the existing workspace. */
    public static void openWorld(EntityPlayerMP player, int x, int y, int z, int side) {
        Session previous = SESSIONS.get(player.getUniqueID());
        if (openPlacementReview(previous, player)) return;
        if (previous != null && previous.player == player
            && !previous.cancelled
            && valid(previous)
            && (previous.job == 1 || previous.job == 2)) {
            openAir(player);
            return;
        }
        TileEntity tile = player.worldObj.getTileEntity(x, y, z);
        Object context = tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getMetaTileEntity() : tile;
        if (context instanceof IConstructable || context instanceof IConstructableProvider
            || tile != null && IMultiblockInfoContainer.contains(tile.getClass())) open(player, x, y, z, side);
        else openAir(player);
    }

    /** The second ordinary world click opens review without importing the block beneath the ghost. */
    private static boolean openPlacementReview(Session s, EntityPlayerMP player) {
        if (s == null || s.player != player
            || s.cancelled
            || !valid(s)
            || !s.virtual
            || !s.virtualPlacement
            || !s.virtualLocked
            || s.virtualPreset == null
            || s.job == 1
            || s.job == 2
            || s.job == 3
            || s.job == 5
            || s.job == 4) return false;
        if (blockedByAreaImport(s)) {
            send(s);
            return true;
        }
        refreshPlan(s);
        s.blueprintAwaitingConfirmation = true;
        s.placementReviewIssued = true;
        s.forcePlacementReady = false;
        s.open = true;
        s.settingsOnly = false;
        s.openPurpose = "placementReview";
        s.placementReviewNonce++;
        s.status = "位置已锁定，请核对材料与 NBT 条目后确认施工";
        send(s);
        return true;
    }

    private static Session uiSession(EntityPlayerMP player) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player || s.cancelled || !valid(s)) {
            if (s != null) clearAreaSelection(s);
            s = new Session(
                player,
                net.minecraft.util.MathHelper.floor_double(player.posX),
                net.minecraft.util.MathHelper.floor_double(player.posY),
                net.minecraft.util.MathHelper.floor_double(player.posZ),
                false,
                1);
            SESSIONS.put(player.getUniqueID(), s);
        }
        return s;
    }

    /** Persistent tool markers describe only a currently valid, server-owned selection session. */
    public static boolean isAreaSelecting(EntityPlayer player, ItemStack stack) {
        Session s = SESSIONS.get(player.getUniqueID());
        HologramArea area = AREAS.get(player.getUniqueID());
        return s != null && s.player == player
            && !s.cancelled
            && area != null
            && area.selecting
            && stack != null
            && stack.getItem() == s.tool
            && stack.hasTagCompound()
            && s.toolId.equals(
                stack.getTagCompound()
                    .getString(TOOL_ID))
            && valid(s);
    }

    private static boolean clearAreaMarker(Session s, ItemStack stack) {
        if (stack == null || stack.getItem() != s.tool
            || !stack.hasTagCompound()
            || !s.toolId.equals(
                stack.getTagCompound()
                    .getString(TOOL_ID))
            || !stack.getTagCompound()
                .hasKey("gtitHologramAreaSelecting"))
            return false;
        stack.getTagCompound()
            .removeTag("gtitHologramAreaSelecting");
        return true;
    }

    private static void clearAreaSelection(Session s) {
        AREAS.remove(s.player.getUniqueID());
        AREA_EDITORS.remove(s.player.getUniqueID());
        AREA_STRUCTURES.remove(s.player.getUniqueID());
        boolean changed = clearAreaMarker(s, s.boundTool);
        for (ItemStack stack : s.player.inventory.mainInventory) changed |= clearAreaMarker(s, stack);
        for (ItemStack stack : s.player.inventory.armorInventory) changed |= clearAreaMarker(s, stack);
        if (changed) s.player.inventory.markDirty();
    }

    public static void toggleAreaSelection(EntityPlayerMP player) {
        Session s = uiSession(player);
        if (s.job == 1 || s.job == 2) {
            s.status = "请先取消当前施工任务再选择区域";
            send(s);
            return;
        }
        HologramArea area = AREAS.computeIfAbsent(player.getUniqueID(), ignored -> new HologramArea());
        if (area.importing) {
            area.importing = false;
            s.status = "区域导入已取消，原规划保留";
        } else {
            area.selecting = !area.selecting;
            currentTool(s).getTagCompound()
                .setBoolean("gtitHologramAreaSelecting", area.selecting);
            player.inventory.markDirty();
            s.status = area.selecting ? "区域选择已开启：左键第一点，右键第二点；Alt 使用玩家坐标" : "区域选择已关闭";
            if (!area.selecting && area.first != null && area.second != null) startAreaImport(s, area);
        }
        player.addChatMessage(new ChatComponentText("[猫猫投影仪] " + s.status));
        send(s);
    }

    /** Begin and finish are separate, idempotent gestures; a repeated click never starts an import. */
    public static void beginAreaSelection(EntityPlayerMP player) {
        Session s = uiSession(player);
        if (s.job == 1 || s.job == 2) {
            s.status = "请先完成或取消当前施工，再框选区域";
            send(s);
            return;
        }
        HologramArea existing = AREAS.get(player.getUniqueID());
        if (existing != null && (existing.selecting || existing.importing)) return;
        HologramArea area = new HologramArea();
        AREAS.put(player.getUniqueID(), area);
        area.selecting = true;
        currentTool(s).getTagCompound()
            .setBoolean("gtitHologramAreaSelecting", true);
        player.inventory.markDirty();
        s.open = false;
        s.status = "左键第一角，右键第二角；Alt 使用玩家坐标；再次 Shift + 右键完成，Esc 取消";
        send(s);
    }

    public static void finishAreaSelection(EntityPlayerMP player, NBTTagCompound request) {
        Session s = SESSIONS.get(player.getUniqueID());
        HologramArea area = AREAS.get(player.getUniqueID());
        if (s == null || s.player != player
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || s.planRevision != request.getLong("planRevision")
            || !valid(s)
            || s.job == 1
            || s.job == 2
            || area == null
            || !area.selecting) return;
        if (area.first == null || area.second == null) {
            s.status = "请先设置两个角点，再次 Shift + 右键完成";
            send(s);
            return;
        }
        area.selecting = false;
        clearAreaMarker(s, currentTool(s));
        player.inventory.markDirty();
        startAreaImport(s, area);
        send(s);
    }

    public static void cancelAreaSelection(EntityPlayerMP player, String session) {
        Session s = SESSIONS.get(player.getUniqueID());
        HologramArea area = AREAS.get(player.getUniqueID());
        if (s == null || s.player != player
            || !s.id.equals(session)
            || !valid(s)
            || s.job == 1
            || s.job == 2
            || area == null
            || !area.selecting && !area.importing) return;
        clearAreaSelection(s);
        s.open = false;
        s.status = "区域框选已取消，当前草稿保留";
        send(s);
    }

    public static void worldAreaPoint(EntityPlayerMP player, NBTTagCompound request) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player || !s.id.equals(request.getString("session")) || !valid(s)) return;
        areaPoint(
            player,
            request.getInteger("point"),
            request.getInteger("x"),
            request.getInteger("y"),
            request.getInteger("z"),
            request.getBoolean("atPlayer"));
    }

    public static void areaPoint(EntityPlayerMP player, int point, int x, int y, int z, boolean atPlayer) {
        Session s = uiSession(player);
        HologramArea area = AREAS.get(player.getUniqueID());
        if (area == null || !area.selecting || point < 1 || point > 2) return;
        if (atPlayer) {
            x = net.minecraft.util.MathHelper.floor_double(player.posX);
            y = net.minecraft.util.MathHelper.floor_double(player.posY);
            z = net.minecraft.util.MathHelper.floor_double(player.posZ);
        }
        if (y < 0 || y >= player.worldObj.getHeight()
            || !player.worldObj.blockExists(x, y, z)
            || player.getDistanceSq(x + .5, y + .5, z + .5) > 4096) return;
        area.point(point, x, y, z);
        s.status = "区域点 " + point + " = " + x + ", " + y + ", " + z;
        player.addChatMessage(new ChatComponentText("[猫猫投影仪] " + s.status));
        send(s);
    }

    private static void startAreaImport(Session s, HologramArea area) {
        if (blockedByAreaImport(s) || s.job == 1 || s.job == 2) {
            s.status = "请先取消区域导入或当前施工任务，再重新导入";
            return;
        }
        try {
            area.begin(s.player.worldObj);
            cancelCaptureRetry(s);
            clearPending(s);
            s.ae.invalidate();
            s.planRevision++;
            s.blueprintAwaitingConfirmation = false;
            s.forcePlacementReady = false;
            s.placementReviewNonce++;
            s.virtualPlacement = false;
            s.virtualLocked = false;
            s.status = "正在分段导入区域与声明结构，请稍候";
        } catch (java.io.IOException failure) {
            s.status = "区域导入失败：" + failure.getMessage();
        }
    }

    private static boolean blockedByAreaImport(Session s) {
        HologramArea area = AREAS.get(s.player.getUniqueID());
        if (area == null || !area.importing) return false;
        s.status = "正在导入区域，请等待完成；关闭界面可取消导入并保留原草稿";
        return true;
    }

    /** Closing cancels only the unfinished import; the previous draft, targets and world remain intact. */
    private static void cancelAreaImport(Session s) {
        HologramArea area = AREAS.get(s.player.getUniqueID());
        if (area != null) {
            area.importing = false;
            area.selecting = false;
        }
        s.ae.invalidate();
        s.open = false;
        s.settingsOnly = false;
        s.status = "区域导入已取消，原草稿保留";
    }

    /** Warning navigation remains in the server-owned virtual draft, even when live area controllers exist. */
    private static boolean selectVirtualStructure(Session s, NBTTagCompound action) {
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
            || blockedByAreaImport(s)) {
            s.status = "虚拟结构导航已过期或当前草稿不可编辑，请重新核对报告";
            return false;
        }
        String id = action.getString("structureId");
        HologramPresets.Structure selected = id.isEmpty() ? null : blueprintSource(s.virtualPreset, id);
        boolean savedMember = s.virtualPreset.cells.stream()
            .anyMatch(cell -> id.isEmpty() || cell.belongsTo(id));
        boolean capturedMember = s.capture.cells.stream()
            .anyMatch(cell -> id.isEmpty() || cell.belongsTo(id));
        if ((!id.isEmpty() && (selected == null || !selected.enabled)) || !savedMember || !capturedMember) {
            s.status = "该结构不属于当前蓝图、已停用或没有草稿格，导航未修改";
            return false;
        }
        saveStructureConfiguration(s);
        loadStructureConfiguration(s, id);
        s.selectedIndices.clear();
        for (int index = 0; index < s.capture.cells.size(); index++) if (id.isEmpty() || s.capture.cells.get(index)
            .belongsTo(id)) s.selectedIndices.add(index);
        s.scope = id.isEmpty() ? 0 : 2;
        s.selected = s.selectedIndices.isEmpty() ? -1
            : s.selectedIndices.iterator()
                .next();
        s.blueprintActiveStructure = id;
        refreshPlan(s);
        s.open = true;
        s.status = id.isEmpty() ? "已返回整份虚拟蓝图，请继续处理报告中的问题" : "已切换虚拟蓝图结构，请继续处理报告中的问题";
        return true;
    }

    private static Session selectAreaStructure(Session previous, String id) {
        HologramArea area = AREAS.get(previous.player.getUniqueID());
        if (previous.virtual && area != null
            && previous.virtualPreset != area.blueprint
            && previous.areaOverviewOwner != area) area = null;
        if (area != null && area.imported && previous.virtual && previous.virtualPreset == area.blueprint) {
            try {
                area.edited.addAll(previous.virtualEdited);
                area.blueprint = blueprint(previous, area.blueprint.name);
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
            if (!id.isEmpty() && blueprintSource(previous.virtualPreset, id) == null) {
                previous.status = "结构不存在，选择未修改";
                return previous;
            }
            saveStructureConfiguration(previous);
            loadStructureConfiguration(previous, id);
            previous.selectedIndices.clear();
            for (int index = 0; index < previous.capture.cells.size(); index++)
                if (id.isEmpty() || previous.capture.cells.get(index)
                    .belongsTo(id)) previous.selectedIndices.add(index);
            previous.scope = id.isEmpty() ? 0 : 2;
            previous.selected = previous.selectedIndices.isEmpty() ? -1
                : previous.selectedIndices.iterator()
                    .next();
            previous.blueprintActiveStructure = id;
            refreshPlan(previous);
            previous.open = true;
            return previous;
        }
        if (area == null || !area.imported) {
            previous.status = "请先导入施工区域";
            return previous;
        }
        if (id.isEmpty()) {
            Session editor = AREA_EDITORS.get(previous.player.getUniqueID());
            if (editor != null && valid(editor)) {
                area.active = "";
                populateVirtual(editor);
                refreshPlan(editor);
                activateAreaSession(editor);
                editor.open = true;
                SESSIONS.put(previous.player.getUniqueID(), editor);
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
        if (area.active.isEmpty()) AREA_EDITORS.put(previous.player.getUniqueID(), previous);
        Map<String, Session> editors = AREA_STRUCTURES
            .computeIfAbsent(previous.player.getUniqueID(), ignored -> new HashMap<>());
        Session selected = editors.get(id);
        if (selected == null || !valid(selected)) {
            selected = new Session(previous.player, structure.x, structure.y, structure.z, true, previous.side);
            selected.areaStructureId = id;
            selected.main = 1;
            selected.mainExplicit = false;
            selected.channels = new NBTTagCompound();
            HologramPresets.Structure saved = blueprintSource(area.blueprint, id);
            if (saved != null) applyStructureConfiguration(selected, saved);
            editors.put(id, selected);
        }
        scan(selected);
        bindAreaMemberships(selected);
        restoreAreaTargets(selected, area);
        area.active = id;
        activateAreaSession(selected);
        selected.open = true;
        SESSIONS.put(previous.player.getUniqueID(), selected);
        return selected;
    }

    /** A cached editor keeps its planning data but never reuses a retired network nonce. */
    private static void activateAreaSession(Session s) {
        s.id = UUID.randomUUID()
            .toString();
        s.uiSequence = 0;
        s.actionTick = -1;
        s.actionCount = 0;
    }

    private static void storeStructureConfiguration(Session s, HologramPresets.Structure saved) {
        saved.main = s.main;
        saved.mainExplicit = s.mainExplicit;
        saved.noHatches = s.noHatches;
        saved.channels.clear();
        for (Object key : s.channels.func_150296_c())
            saved.channels.put((String) key, s.channels.getInteger((String) key));
    }

    private static void applyStructureConfiguration(Session s, HologramPresets.Structure saved) {
        s.main = Math.max(1, saved.main);
        s.mainExplicit = saved.mainExplicit;
        s.noHatches = saved.noHatches;
        s.channels = new NBTTagCompound();
        if (saved.channels != null) saved.channels.forEach((key, value) -> s.channels.setInteger(key, value));
        s.capabilities = null;
    }

    private static void saveStructureConfiguration(Session s) {
        if (s.virtualPreset == null) return;
        HologramPresets.Structure selected = blueprintSource(s.virtualPreset, s.blueprintActiveStructure);
        if (selected != null) storeStructureConfiguration(s, selected);
    }

    private static void loadStructureConfiguration(Session s, String id) {
        if (s.blueprintActiveStructure.isEmpty() && !id.isEmpty()) {
            s.areaOverviewConfiguration = new HologramPresets.Structure();
            storeStructureConfiguration(s, s.areaOverviewConfiguration);
        }
        HologramPresets.Structure selected = id.isEmpty() ? s.areaOverviewConfiguration
            : blueprintSource(s.virtualPreset, id);
        if (selected != null) {
            applyStructureConfiguration(s, selected);
            Object source = selected.controllerId >= 0 && selected.controllerId < GregTechAPI.METATILEENTITIES.length
                ? GregTechAPI.METATILEENTITIES[selected.controllerId]
                : null;
            s.capabilities = HologramCapabilities.describe(source, trigger(s), null)
                .withSavedChannels(s.channels);
        }
    }

    /** Membership is tied to all real declarations at a position, including shared walls. */
    private static void bindAreaMemberships(Session s) {
        if (s.areaStructureId.isEmpty() || s.capture == null || s.capture.pending) return;
        HologramArea area = AREAS.get(s.player.getUniqueID());
        if (area == null) return;
        HologramArea.Structure own = area.structures.get(s.areaStructureId);
        if (own == null) return;
        own.capture = s.capture;
        Map<String, Session> editors = AREA_STRUCTURES.get(s.player.getUniqueID());
        if (editors != null) for (Session editor : editors.values())
            if (editor.capture != null) for (HologramCapture.Cell cell : editor.capture.cells) cell.candidates = null;
        for (HologramCapture.Cell cell : s.capture.cells) {
            List<String> ids = area.memberships(cell.x, cell.y, cell.z);
            if ((cell.element != null || cell.anchor) && !ids.contains(own.id)) ids.add(own.id);
            setMemberships(cell, ids);
            cell.candidates = null;
        }
    }

    /** Commit planning changes to the shared area template before changing editors. */
    private static void synchronizeAreaStructure(Session s, HologramArea area) {
        if (s.areaStructureId.isEmpty() || s.capture == null || s.capture.incomplete || s.capturePending) return;
        HologramPresets.Structure savedStructure = blueprintSource(area.blueprint, s.areaStructureId);
        if (savedStructure != null) storeStructureConfiguration(s, savedStructure);
        Map<String, HologramPresets.Cell> savedCells = new HashMap<>();
        for (HologramPresets.Cell saved : area.blueprint.cells)
            savedCells.put((area.minX + saved.x) + "," + (area.minY + saved.y) + "," + (area.minZ + saved.z), saved);
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            HologramPresets.Cell saved = savedCells.get(position(cell));
            if (saved == null || cell.anchor) continue;
            ItemStack target = draftTarget(s, cell, index);
            if (!acceptableSample(s, cell, target)) continue;
            boolean changed = !sameMaterial(saved.stack(), target);
            if (changed || explicitTarget(s, cell, index)) area.edited.add(position(cell));
            HologramPresets.Cell identity = HologramPresets.cell(
                target,
                saved.x,
                saved.y,
                saved.z,
                s.facingPins.getOrDefault(position(cell), saved.facing),
                saved.controller);
            saved.item = identity.item;
            saved.damage = identity.damage;
            if (changed) {
                saved.block = String
                    .valueOf(Block.blockRegistry.getNameForObject(Block.getBlockFromItem(target.getItem())));
                saved.metadata = target.getItem()
                    .getMetadata(target.getItemDamage());
                saved.tileNbt = null;
                saved.itemNbt = null;
            }
            saved.facing = identity.facing;
            saved.allowedTargets = savedTargets(candidates(s, cell));
            saved.prompt = cell.prompt;
            saved.excluded = cell.excluded;
        }
    }

    private static List<HologramPresets.Target> savedTargets(List<ItemStack> targets) {
        List<HologramPresets.Target> result = new ArrayList<>();
        for (ItemStack target : targets) if (target != null) result.add(new HologramPresets.Target(target));
        return result;
    }

    private static void restoreAreaTargets(Session s, HologramArea area) {
        if (s.capture == null || s.capture.incomplete || s.capturePending) return;
        Map<String, HologramPresets.Cell> savedCells = new HashMap<>();
        for (HologramPresets.Cell saved : area.blueprint.cells)
            savedCells.put((area.minX + saved.x) + "," + (area.minY + saved.y) + "," + (area.minZ + saved.z), saved);
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            HologramPresets.Cell saved = savedCells.get(position(cell));
            if (saved == null || cell.anchor) continue;
            cell.prompt = saved.prompt == null ? "" : saved.prompt;
            cell.excluded = saved.excluded;
            ItemStack target = saved.stack();
            if (!area.edited.contains(position(cell)) || !acceptableSample(s, cell, target)) continue;
            s.pins.put(index, target);
            s.coordinatePins.put(position(cell), target.copy());
            if (saved.facing >= 0) for (int direction : legalFacings(s, cell, target))
                if (direction == saved.facing) s.facingPins.put(position(cell), direction);
        }
        refreshPlan(s);
    }

    public static void clear() {
        for (Session s : SESSIONS.values()) clearAreaSelection(s);
        SESSIONS.clear();
        AREAS.clear();
        AREA_EDITORS.clear();
        AREA_STRUCTURES.clear();
    }

    public static void openSettings(EntityPlayerMP player) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player || s.cancelled || !valid(s)) {
            s = new Session(player, 0, 0, 0, false, 2);
            SESSIONS.put(player.getUniqueID(), s);
        }
        s.settingsOnly = false;
        dismissPresetBrowse(s);
        s.open = true;
        send(s);
    }

    public static void openVirtual(EntityPlayerMP player, int x, int y, int z, int side, String presetId) {
        Session previous = SESSIONS.get(player.getUniqueID());
        if (previous != null && previous.player == player && blockedByAreaImport(previous)) {
            send(previous);
            return;
        }
        if (previous != null && previous.player == player && (previous.job == 1 || previous.job == 2)) {
            previous.status = "请先取消当前施工任务";
            send(previous);
            return;
        }
        Session s = new Session(player, x, y, z, false, side);
        s.virtual = true;
        s.virtualPlacement = false;
        s.blueprintAwaitingConfirmation = false;
        s.open = true;
        SESSIONS.put(player.getUniqueID(), s);
        if (!valid(s) || !player.worldObj.blockExists(x, y, z)) {
            s.cancelled = true;
            s.status = "虚拟锚点未加载、超出高度或距离限制";
            send(s);
            return;
        }
        s.presetBrowseForPlacement = true;
        s.status = "请选择蓝图并点击放置；预览位置后右击确定，再核对材料并确认施工";
        send(s);
    }

    /** Last selection belongs to the held logical tool; shortcut loading uses the ordinary validated preset path. */
    public static void quickPlaceLastBlueprint(EntityPlayerMP player, NBTTagCompound request) {
        Session previous = uiSession(player);
        if (!valid(previous)) return;
        HologramArea area = AREAS.get(player.getUniqueID());
        if (previous.job == 1 || previous.job == 2
            || area != null && (area.selecting || area.importing)
            || previous.virtual && previous.virtualPlacement
                && previous.job != 3
                && previous.job != 4
                && previous.job != 5) {
            previous.status = "请先完成当前框选或放置，再快捷放置蓝图";
            presetGuidance(previous, previous.status);
            send(previous);
            return;
        }
        String id = selectedPreset(previous);
        if (id.isEmpty()) {
            previous.status = "尚未选择有效蓝图，或上次选择已删除；普通右键打开蓝图库重新选择后可用 Alt + 右键快捷放置";
            presetGuidance(previous, previous.status);
            send(previous);
            return;
        }
        int x = request.getInteger("x"), y = request.getInteger("y"), z = request.getInteger("z");
        if (!request.hasKey("x", 3) || !request.hasKey("y", 3)
            || !request.hasKey("z", 3)
            || !request.hasKey("side", 3)
            || request.getInteger("side") < 0
            || request.getInteger("side") > 5
            || !HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
            || !previewReach(player, x, y, z)
            || !player.worldObj.blockExists(x, y, z)) {
            previous.status = "快捷放置位置未加载或超出范围；请靠近目标后重试";
            presetGuidance(previous, previous.status);
            send(previous);
            return;
        }
        Session placement = new Session(player, x, y, z, false, request.getInteger("side"));
        placement.virtual = true;
        loadPreset(placement, id, false);
        if (placement.virtualPreset == null || placement.capture == null
            || placement.capture.incomplete
            || !virtualBoundsValid(placement)
            || !beginVirtualPlacement(placement, x, y, z, placement.side)) {
            previous.status = "上次选择的蓝图不可快捷放置：" + placement.status + "；普通右键打开蓝图库重新选择";
            presetGuidance(previous, previous.status);
            send(previous);
            return;
        }
        clearAreaSelection(previous);
        SESSIONS.put(player.getUniqueID(), placement);
        send(placement);
    }

    private static boolean virtualBoundsValid(Session s) {
        for (HologramCapture.Cell cell : s.capture.cells)
            if (!HologramWorldFlow.worldPosition(cell.x, cell.y, cell.z, s.player.worldObj.getHeight())) {
                s.status = "蓝图结构范围超出世界边界或高度";
                return false;
            }
        return true;
    }

    public static void lockVirtual(EntityPlayerMP player, int x, int y, int z, int side) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s != null && s.player == player && blockedByAreaImport(s)) {
            send(s);
            return;
        }
        if (s == null || s.player != player
            || s.cancelled
            || !s.virtual
            || !s.virtualPlacement
            || s.virtualPreset == null
            || !valid(s)
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || s.virtualLocked
            || s.capture == null
            || s.capture.incomplete
            || s.capture.pending
            || s.capturePending) return;
        if (!moveVirtual(s, x, y, z, side)) {
            s.status = "该位置或结构范围不合法，预览与草稿保留；请调整位置后重试";
            s.open = false;
            send(s);
            return;
        }
        s.virtualLocked = true;
        clearPlacementSelection(s);
        s.open = false;
        s.blueprintAwaitingConfirmation = true;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        refreshPlan(s);
        s.openPurpose = "";
        s.placementReviewNonce++;
        s.status = "位置已锁定；Shift + 右键取消锁定，普通右键打开材料与 NBT 放置确认";
        send(s);
    }

    public static void confirmWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player || !s.id.equals(request.getString("session"))) return;
        if (blockedByAreaImport(s)) {
            send(s);
            return;
        }
        if (s.cancelled || !s.virtual
            || !s.virtualPlacement
            || s.virtualLocked
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || !valid(s)) return;
        String failure = null;
        int x = request.getInteger("x"), y = request.getInteger("y"), z = request.getInteger("z");
        if (!request.hasKey("planRevision", 4) || s.planRevision != request.getLong("planRevision"))
            failure = "蓝图计划已更新，预览与草稿保留；请再次确定位置";
        else if (!request.hasKey("x", 3) || !request.hasKey("y", 3)
            || !request.hasKey("z", 3)
            || !request.hasKey("side", 3)
            || request.getInteger("side") < 0
            || request.getInteger("side") > 5) failure = "位置数据不完整，预览与草稿保留；请重新确定位置";
        else if (!HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight()))
            failure = "目标超出世界边界或高度，预览与草稿保留；请调整位置";
        else if (!previewReach(player, x, y, z)) failure = "目标超出放置距离，预览与草稿保留；请靠近目标或缩短滚轮距离";
        else if (!player.worldObj.blockExists(x, y, z)) failure = "目标区块尚未加载，预览与草稿保留；请靠近后重试";
        else if (s.capture == null || s.capture.incomplete || s.capture.pending || s.capturePending)
            failure = "蓝图采集尚未完整，预览与草稿保留；请等待后重试";
        if (failure != null) {
            s.status = failure;
            s.open = false;
            send(s);
            return;
        }
        lockVirtual(
            player,
            request.getInteger("x"),
            request.getInteger("y"),
            request.getInteger("z"),
            request.getInteger("side"));
    }

    /** Unlock invalidates every prior material confirmation capability while retaining the editable draft. */
    public static void unlockWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player
            || s.cancelled
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || s.planRevision != request.getLong("planRevision")
            || !valid(s)
            || !s.virtual
            || !s.virtualPlacement
            || !s.virtualLocked
            || s.virtualPreset == null
            || s.job == 1
            || s.job == 2
            || s.job == 4) return;
        if (blockedByAreaImport(s)) {
            send(s);
            return;
        }
        s.virtualLocked = false;
        s.blueprintAwaitingConfirmation = false;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        s.openPurpose = "";
        s.placementReviewNonce++;
        s.ae.invalidateReviews();
        refreshPlan(s);
        s.open = false;
        s.status = "已取消位置锁定；移动鼠标调整预览，右键重新锁定，Esc 取消放置";
        send(s);
    }

    private static boolean previewReach(EntityPlayerMP player, int x, int y, int z) {
        return HologramWorldFlow
            .withinPreviewReach(player.posX, player.posY + player.getEyeHeight(), player.posZ, x, y, z);
    }

    /** Adjust only a locked, unpaid preview; every previous construction and AE review capability expires. */
    public static void nudgeWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player
            || s.cancelled
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || s.planRevision != request.getLong("planRevision")
            || !valid(s)
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem())
            || !s.virtual
            || !s.virtualPlacement
            || !s.virtualLocked
            || s.job != 0
            || s.virtualPreset == null
            || s.capture == null
            || s.capture.incomplete
            || s.capture.pending
            || s.capturePending
            || blockedByAreaImport(s)
            || !request.hasKey("dx", 3)
            || !request.hasKey("dy", 3)
            || !request.hasKey("dz", 3)) return;
        int dx = request.getInteger("dx"), dy = request.getInteger("dy"), dz = request.getInteger("dz");
        if (!HologramWorldFlow.unitNudge(dx, dy, dz)) return;
        long tick = player.worldObj.getTotalWorldTime();
        if (s.positionNudgeTick != Long.MIN_VALUE && tick >= s.positionNudgeTick
            && tick - s.positionNudgeTick < HologramWorldFlow.NUDGE_REPEAT_INTERVAL) return;
        s.positionNudgeTick = tick;
        int x = s.x + dx, y = s.y + dy, z = s.z + dz;
        boolean allowed = HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
            && previewReach(player, x, y, z)
            && player.worldObj.blockExists(x, y, z);
        if (allowed) for (HologramCapture.Cell cell : s.capture.cells) {
            long xx = (long) cell.x + dx, yy = (long) cell.y + dy, zz = (long) cell.z + dz;
            if (xx < -30000000L || xx >= 30000000L
                || yy < 0
                || yy >= player.worldObj.getHeight()
                || zz < -30000000L
                || zz >= 30000000L
                || !player.worldObj.blockExists((int) xx, (int) yy, (int) zz)) {
                allowed = false;
                break;
            }
        }
        if (!allowed || !moveVirtual(s, x, y, z, s.side)) {
            s.open = false;
            s.status = "微调位置超出距离、世界边界或已加载区域；原锁定位置与草稿保留";
            send(s);
            return;
        }
        s.blueprintAwaitingConfirmation = true;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        s.openPurpose = "";
        s.placementReviewNonce++;
        s.ae.invalidateReviews();
        s.open = false;
        s.status = "锁定位置已微调一格；箭头水平微调，Alt + 上下调整高度；右键重新核对材料与 NBT";
        send(s);
    }

    /** Escape leaves the editable draft and stored blueprint intact and never changes an active job. */
    public static void cancelWorldPlacement(EntityPlayerMP player, NBTTagCompound request) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player
            || !s.id.equals(request.getString("session"))
            || !request.hasKey("planRevision", 4)
            || s.planRevision != request.getLong("planRevision")
            || !valid(s)
            || s.cancelled
            || !s.virtual
            || !s.virtualPlacement
            || s.job == 1
            || s.job == 2
            || s.job == 4) return;
        boolean expireCapability = s.virtualLocked || s.placementReviewIssued || s.forcePlacementReady;
        s.virtualPlacement = false;
        s.virtualLocked = false;
        s.blueprintAwaitingConfirmation = false;
        s.placementReviewIssued = false;
        s.forcePlacementReady = false;
        s.openPurpose = "";
        if (expireCapability) s.placementReviewNonce++;
        s.ae.invalidateReviews();
        s.blueprintPreview = true;
        s.presetBrowseForPlacement = false;
        s.open = true;
        s.status = "放置已取消，蓝图草稿保留，可继续编辑";
        send(s);
    }

    /** Rebase the in-memory draft without publishing a temporary lock or discarding edits. */
    private static boolean beginVirtualPlacement(Session s, int x, int y, int z, int side) {
        if (!moveVirtual(s, x, y, z, side)) return false;
        s.virtualPlacement = true;
        s.virtualLocked = false;
        s.blueprintAwaitingConfirmation = false;
        s.forcePlacementReady = false;
        s.open = false;
        s.settingsOnly = false;
        s.placementReviewIssued = false;
        s.status = "移动鼠标预览位置；右键锁定位置，再次右键核对材料与 NBT，Esc 取消放置";
        return true;
    }

    /** Rebase the in-memory draft without publishing a temporary lock or discarding edits. */
    private static boolean moveVirtual(Session s, int x, int y, int z, int side) {
        if (blockedByAreaImport(s)) return false;
        EntityPlayerMP player = s.player;
        if (s.capture == null || !HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
            || !previewReach(player, x, y, z)
            || !player.worldObj.blockExists(x, y, z)) return false;
        if (s.x != x || s.y != y || s.z != z) {
            if (!transformVirtual(s, x, y, z, false, false)) return false;
            s.x = x;
            s.y = y;
            s.z = z;
        }
        s.side = side;
        refreshPlan(s);
        return true;
    }

    /** A complete, unlocked placement may rotate; never writes world or preset storage. */
    private static boolean rotateVirtual(Session s, boolean vertical) {
        if (blockedByAreaImport(s)) return false;
        if (s.cancelled || !s.virtual
            || !s.virtualPlacement
            || s.virtualLocked
            || s.virtualPreset == null
            || s.capture == null
            || s.capture.incomplete
            || s.capture.pending
            || s.capturePending
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || !valid(s)
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(s.player.getHeldItem()))
            return false;
        String beforeStatus = s.status;
        if (!transformVirtual(s, s.x, s.y, s.z, true, vertical)) {
            s.status = java.util.Objects.equals(beforeStatus, s.status) ? "旋转被拒绝：坐标、控制器姿态或仓室方向不合法；原目标保留"
                : "旋转被拒绝：" + s.status + "；原目标保留";
            return false;
        }
        freezeAllocations(s);
        refreshPlan(s);
        s.status = vertical ? "虚拟结构已垂直旋转 90°；右击确定位置并核对材料" : "虚拟结构已水平旋转 90°；右击确定位置并核对材料";
        return true;
    }

    private static ExtendedFacing virtualControllerFacing(Session s) {
        ExtendedFacing facing = HologramVirtualTransform.controller(s.virtualPreset);
        Integer front = s.facingPins.get(s.x + "," + s.y + "," + s.z);
        return front == null ? facing : facing.with(ForgeDirection.getOrientation(front));
    }

    /** Stage every coordinate and front before committing; stable cell order keeps all indexed targets selected. */
    private static boolean transformVirtual(Session s, int x, int y, int z, boolean rotate, boolean vertical) {
        HologramPresets.Preset preset = s.virtualPreset;
        ExtendedFacing controller = null;
        if (rotate) {
            preset = HologramPresets.copy(preset);
            Map<String, int[]> littleReferences;
            try {
                littleReferences = HologramBlockTransformLittleTiles.presetReferences(preset.cells, vertical);
            } catch (java.io.IOException invalid) {
                return false;
            }
            controller = HologramVirtualTransform.alignment(virtualControllerFacing(s), vertical);
            gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = preset.controllerId < 0
                || preset.controllerId >= GregTechAPI.METATILEENTITIES.length ? null
                    : GregTechAPI.METATILEENTITIES[preset.controllerId];
            if ((preset.schema < 2 || preset.controllerId >= 0) && (!(prototype instanceof MTEEnhancedMultiBlockBase)
                || !((MTEEnhancedMultiBlockBase<?>) prototype).getAlignmentLimits()
                    .isNewExtendedFacingValid(controller)))
                return false;
            preset.controllerFacing = controller.ordinal();
            Map<String, Integer> currentFacings = new HashMap<>();
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                String key = position(cell);
                Integer front = s.facingPins.getOrDefault(key, s.planFacings.get(i));
                if (front != null && !s.virtualEdited.contains(key)) currentFacings.put(key, front);
            }
            for (HologramPresets.Cell saved : preset.cells) {
                int front = currentFacings
                    .getOrDefault((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z), saved.facing);
                if (preset.schema >= 2) {
                    try {
                        HologramBlockTransforms.turn(saved, vertical, littleReferences);
                        HologramBlockTransforms.nativeSample(saved);
                    } catch (java.io.IOException invalid) {
                        s.status = invalid.getMessage();
                        return false;
                    }
                }
                int[] offset = HologramVirtualTransform.turn(saved.x, saved.y, saved.z, vertical);
                saved.x = offset[0];
                saved.y = offset[1];
                saved.z = offset[2];
                saved.facing = HologramBlockTransforms.isPipe(saved.stack()) ? -1
                    : saved.controller && preset.schema < 2 ? controller.getDirection()
                        .ordinal() : HologramVirtualTransform.front(front, vertical);
                if (saved.stack() != null && !saved.missing() && !virtualFrontAllowed(saved.stack(), saved.facing))
                    return false;
            }
        }
        if (rotate) for (HologramAePolicy.Issue issue : preset.aeIssues) {
            if ("policy".equals(issue.path)) continue;
            int[] location = HologramVirtualTransform.turn(issue.x, issue.y, issue.z, vertical);
            issue.x = location[0];
            issue.y = location[1];
            issue.z = location[2];
            issue.path = HologramAeBlueprint.rotateIssuePath(issue.path, vertical);
        }
        if (rotate) for (HologramPresets.Structure saved : preset.structures) {
            int[] coordinate = HologramVirtualTransform.turn(saved.x, saved.y, saved.z, vertical);
            saved.x = coordinate[0];
            saved.y = coordinate[1];
            saved.z = coordinate[2];
        }
        if (rotate) for (HologramPresets.HintArea hint : preset.hintAreas) {
            int[] a = HologramVirtualTransform.turn(hint.x1, hint.y1, hint.z1, vertical);
            int[] b = HologramVirtualTransform.turn(hint.x2, hint.y2, hint.z2, vertical);
            hint.x1 = Math.min(a[0], b[0]);
            hint.x2 = Math.max(a[0], b[0]);
            hint.y1 = Math.min(a[1], b[1]);
            hint.y2 = Math.max(a[1], b[1]);
            hint.z1 = Math.min(a[2], b[2]);
            hint.z2 = Math.max(a[2], b[2]);
        }
        HologramCapture capture = new HologramCapture(s.capture.context, s.capture.trigger);
        capture.incomplete = s.capture.incomplete;
        capture.pending = s.capture.pending;
        capture.failure = s.capture.failure;
        capture.channelReport = s.capture.channelReport;
        Map<String, String> keys = new HashMap<>();
        Map<String, Integer> facings = new HashMap<>();
        Map<Integer, ItemStack> nativeTargets = new HashMap<>();
        Map<String, int[]> littleReferences = null;
        if (rotate && preset.schema >= 2) {
            try {
                littleReferences = HologramBlockTransformLittleTiles.captureReferences(s.capture.cells, vertical);
            } catch (java.io.IOException invalid) {
                return false;
            }
        }
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell old = s.capture.cells.get(i);
            int[] offset = new int[] { old.x - s.x, old.y - s.y, old.z - s.z };
            if (rotate) offset = HologramVirtualTransform.turn(offset[0], offset[1], offset[2], vertical);
            long xx = (long) x + offset[0], yy = (long) y + offset[1], zz = (long) z + offset[2];
            if (xx < -30000000L || xx >= 30000000L
                || yy < 0
                || yy >= s.player.worldObj.getHeight()
                || zz < -30000000L
                || zz >= 30000000L) return false;
            HologramCapture.Cell cell = blueprintCell(old, (int) xx, (int) yy, (int) zz);
            if (rotate && preset.schema >= 2) {
                try {
                    HologramBlockTransforms.turn(cell, vertical, littleReferences, old.x, old.y, old.z);
                    ItemStack sample = HologramBlockTransforms.nativeSample(cell, targetFor(s, old, i));
                    if (sample != null) nativeTargets.put(i, sample);
                } catch (java.io.IOException invalid) {
                    s.status = invalid.getMessage();
                    return false;
                }
            }
            String key = position(cell);
            keys.put(position(old), key);
            capture.cells.add(cell);
            capture.positions.put(key, cell);
            Integer front = s.facingPins.get(position(old));
            if (front == null && rotate) front = s.planFacings.get(i);
            if (HologramBlockTransforms.isPipe(targetFor(s, old, i))) front = null;
            if (front != null) {
                if (rotate)
                    front = "templateController".equals(old.role) && preset.schema < 2 ? controller.getDirection()
                        .ordinal() : HologramVirtualTransform.front(front, vertical);
                if (targetFor(s, old, i) != null && !virtualFrontAllowed(targetFor(s, old, i), front)) return false;
                facings.put(key, front);
            }
        }
        // No mutation above this line: a rejected turn leaves cells, pins, template and revision untouched.
        rekey(s.coordinatePins, keys);
        rekey(s.frozenAutoTargets, keys);
        for (Map.Entry<Integer, ItemStack> entry : nativeTargets.entrySet()) {
            int index = entry.getKey();
            HologramCapture.Cell cell = capture.cells.get(index);
            String key = position(cell);
            ItemStack sample = entry.getValue();
            if (s.pins.containsKey(index) || cell.userPosition && !cell.excluded) {
                s.pins.put(index, sample.copy());
                s.coordinatePins.put(key, sample.copy());
            } else if (s.coordinatePins.containsKey(key)) s.coordinatePins.put(key, sample.copy());
            if (s.autoTargets.containsKey(index)) s.autoTargets.put(index, sample.copy());
            if (s.frozenAutoTargets.containsKey(key)) s.frozenAutoTargets.put(key, sample.copy());
            if (index < s.planTargets.size()) s.planTargets.set(index, sample.copy());
        }
        rekey(s.virtualEdited, keys);
        rekey(s.suppressedAuto, keys);
        java.util.Set<String> manualKeys = new java.util.HashSet<>(s.manualPositions.keySet());
        s.manualPositions.clear();
        for (String old : manualKeys) {
            HologramCapture.Cell cell = capture.positions.get(keys.get(old));
            if (cell != null) s.manualPositions.put(position(cell), cell);
        }
        s.facingPins.clear();
        s.facingPins.putAll(facings);
        s.capture = capture;
        s.virtualPreset = preset;
        if (rotate) s.facing = controller.ordinal();
        s.cellIndices.clear();
        s.indexedCapture = null;
        // A whole placement turn/rebase establishes a new preset baseline; discard history tied to its old baseline.
        s.undoEdits.clear();
        s.redoEdits.clear();
        return true;
    }

    private static void applyVirtualControllerAlignment(Session s, TileEntity placed) {
        if (!(placed instanceof IGregTechTileEntity)
            || !(((IGregTechTileEntity) placed).getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase))
            throw new IllegalStateException("virtual controller missing enhanced alignment");
        IGregTechTileEntity base = (IGregTechTileEntity) placed;
        MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) base.getMetaTileEntity();
        ExtendedFacing facing = virtualControllerFacing(s);
        if (!machine.getAlignmentLimits()
            .isNewExtendedFacingValid(facing)) throw new IllegalStateException("virtual controller alignment rejected");
        base.setFrontFacing(facing.getDirection());
        machine.setExtendedFacing(facing);
        if (base.getFrontFacing() != facing.getDirection() || machine.getExtendedFacing() != facing)
            throw new IllegalStateException("virtual controller alignment failed");
    }

    private static boolean virtualFrontAllowed(ItemStack target, int front) {
        if (front < 0) return true;
        if (target == null || !(target.getItem() instanceof ItemMachines)) return false;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(target);
        return front <= 5 && prototype != null && prototype.isFacingValid(ForgeDirection.getOrientation(front));
    }

    private static <T> void rekey(Map<String, T> values, Map<String, String> keys) {
        Map<String, T> result = new HashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            String key = keys.get(entry.getKey());
            if (key != null) result.put(key, entry.getValue());
        }
        values.clear();
        values.putAll(result);
    }

    private static void rekey(java.util.Set<String> values, Map<String, String> keys) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String old : values) if (keys.containsKey(old)) result.add(keys.get(old));
        values.clear();
        values.addAll(result);
    }

    public static void immediateVirtual(EntityPlayerMP player, int x, int y, int z, int side, String presetId) {
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("x", x);
        request.setInteger("y", y);
        request.setInteger("z", z);
        request.setInteger("side", side);
        quickPlaceLastBlueprint(player, request);
    }

    public static void cancel(EntityPlayerMP player) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player || !valid(s)) return;
        if (blockedByAreaImport(s)) {
            cancelAreaImport(s);
            send(s);
            return;
        }
        HologramArea area = AREAS.get(player.getUniqueID());
        if (area != null) area.importing = false;
        s.job = 4;
        s.cancelled = true;
        s.open = false;
        if (s.virtual) s.virtualLocked = true;
        cancelCaptureRetry(s);
        clearPending(s);
        s.status = "已取消未执行的施工；已施工世界保留";
        if (s.virtual) presetGuidance(s, "投影已取消，已施工方块保留；右击打开界面，可在蓝图库再次放置。");
        send(s);
    }

    public static void handle(EntityPlayerMP player, NBTTagCompound action) {
        Session s = SESSIONS.get(player.getUniqueID());
        if (s == null || s.player != player || s.cancelled || !s.id.equals(action.getString("session"))) return;
        if (action.hasKey("uiSequence")) s.uiSequence = action.getLong("uiSequence");
        long now = player.worldObj.getTotalWorldTime();
        if (s.actionTick != now) {
            s.actionTick = now;
            s.actionCount = 0;
        }
        if (s.actionCount++ >= 8) return;
        if (!valid(s)) {
            clearAreaSelection(s);
            s.job = 4;
            cancelCaptureRetry(s);
            clearPending(s);
            s.status = "目标、维度、距离或持有工具已变化";
            send(s);
            return;
        }
        String op = action.getString("op");
        if (blockedByAreaImport(s)) {
            if ("close".equals(op) || "cancel".equals(op) || "areaCancel".equals(op)) cancelAreaImport(s);
            else if ("cancelAeSave".equals(op)) s.ae.review = null;
            else if ("aePreviewClose".equals(op)) s.ae.preview = null;
            else if ("aePreviewEdit".equals(op))
                s.ae.result(action.getLong("editSeq"), false, "ae.import.busy", s.planRevision);
            // Publish the read-only old draft without AE edits, recovery flush, disk writes or construction.
            send(s);
            return;
        }
        if (!("confirmAeSave".equals(op) || "cancelAeSave".equals(op)
            || "aePreviewOpen".equals(op)
            || "aePreviewEdit".equals(op)
            || "aePreviewClose".equals(op)
            || "close".equals(op))) s.ae.invalidateReviews();
        if (aeAction(s, action, op)) {
            send(s);
            return;
        }
        s.materialContext = new HologramMaterials.Context(player, s.materialSettings);
        s.materialContext.flushRecovery();
        boolean construction = "build".equals(op) || "start".equals(op)
            || "native".equals(op)
            || "confirmBlueprintPlacement".equals(op);
        if (construction && s.virtual && (!s.virtualPlacement || !s.virtualLocked || s.mode == 2)) {
            s.forcePlacementReady = false;
            s.status = s.mode == 2 ? "虚拟预设不支持拆除" : !s.virtualPlacement ? "请在蓝图库点击放置，再右击确定位置并核对材料" : "请先右击确定位置，再在界面确认施工";
            send(s);
            return;
        }
        if (construction && (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision)) {
            s.forcePlacementReady = false;
            s.status = "确认计划已更新，请重新核对材料与位置";
            send(s);
            return;
        }
        if (construction && s.virtual && s.blueprintAwaitingConfirmation && !"confirmBlueprintPlacement".equals(op)) {
            s.status = "请先在材料界面确认本次放置";
            send(s);
            return;
        }
        if (construction && s.virtual && s.blueprintAwaitingConfirmation && !s.placementReviewIssued) {
            s.status = "位置已锁定，请再次右键打开材料与 NBT 确认界面";
            send(s);
            return;
        }
        if (s.job != 1 && s.job != 2
            && !"selectStructure".equals(op)
            && !"configure".equals(op)
            && (isPlanningEdit(op) || "build".equals(op) || "start".equals(op) || "scan".equals(op))) {
            String active = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
            if (action.hasKey("structureId", 8) && !active.equals(action.getString("structureId"))) {
                s.status = "结构选择已变化，请重新选择编辑目标";
                send(s);
                return;
            }
            if (action.hasKey("selected", 3)) {
                int selected = action.getInteger("selected");
                if (selected < -1 || (s.capture != null && selected >= s.capture.cells.size())) return;
                s.selected = selected;
            }
            if (action.hasKey("selectedIndices", 11)) {
                s.selectedIndices.clear();
                for (int index : action.getIntArray("selectedIndices"))
                    if (s.capture != null && index >= 0 && index < s.capture.cells.size()) s.selectedIndices.add(index);
            }
        }
        if (!"build".equals(op) && !"start".equals(op) && !"confirmBlueprintPlacement".equals(op))
            s.forcePlacementReady = false;
        if (isPlanningEdit(op) && !"hatchPreset".equals(op)
            && !"clearHatchPreset".equals(op)
            && s.capture != null
            && s.selected >= 0
            && s.selected < s.capture.cells.size()
            && !editableStructureCell(s, s.capture.cells.get(s.selected))) {
            s.status = "该格属于其他结构，请先切换结构";
            send(s);
            return;
        }
        EditState beforeEdit = isPlanningEdit(op) && s.job != 1 && s.job != 2 ? new EditState(s) : null;
        if (s.job != 1 && s.job != 2 && ("hatchPreset".equals(op) || "clearHatchPreset".equals(op))) {
            s.allocationsFrozen = false;
            s.frozenAutoTargets.clear();
        }
        if (s.virtual && beforeEdit != null
            && !isCellEdit(op)
            && !"hatchPreset".equals(op)
            && !"clearHatchPreset".equals(op)
            && s.capture != null) {
            int selected = action.hasKey("selected", 3) ? action.getInteger("selected") : s.selected;
            if (selected >= 0 && selected < s.capture.cells.size())
                s.virtualEdited.add(position(s.capture.cells.get(selected)));
        }
        if (s.job != 1 && s.job != 2 && "selectStructure".equals(op)) {
            if (action.hasKey("virtualOnly") && (!action.hasKey("virtualOnly", 1) || action.getBoolean("virtualOnly")))
                selectVirtualStructure(s, action);
            else s = selectAreaStructure(s, action.getString("structureId"));
            send(s);
            return;
        }
        if (s.job != 1 && s.job != 2 && "previewPreset".equals(op)) {
            s = editPreset(s, action.getString("presetId"));
            s.blueprintPreview = true;
            s.open = true;
            send(s);
            return;
        }
        if (s.job != 1 && s.job != 2 && blueprintAction(s, action, op)) {
            persist(s);
            send(s);
            return;
        }
        if ("settings".equals(op) && s.job != 1 && s.job != 2) {
            applySettings(s, action);
        } else if ("savePreset".equals(op) && s.job != 1 && s.job != 2) {
            savePreset(s, action.getString("name"));
        } else if ("loadPreset".equals(op) && s.job != 1 && s.job != 2) {
            loadPreset(s, action.getString("presetId"));
        } else if ("choosePlacementPreset".equals(op) && s.job != 1 && s.job != 2) {
            s.virtual = true;
            clearPlacementSelection(s);
            loadPreset(s, action.getString("presetId"), false);
            if (s.virtualPreset != null && action.getString("presetId")
                .equals(s.virtualPreset.id)) {
                s.virtualPlacement = true;
                s.target = null;
                s.machine = null;
                s.context = null;
                s.blueprintAwaitingConfirmation = false;
                s.placementReviewIssued = false;
                s.virtualLocked = false;
                s.forcePlacementReady = false;
                s.open = false;
                s.status = "移动鼠标预览位置；右键锁定，再次右键核对材料与 NBT，Esc 取消放置";
            }
        } else if ("dismissPresetBrowse".equals(op)) {
            dismissPresetBrowse(s);
            s.open = false;
        } else if ("editPreset".equals(op) && s.job != 1 && s.job != 2) {
            s = editPreset(s, action.getString("presetId"));
        } else if ("updatePreset".equals(op) && s.job != 1 && s.job != 2) {
            saveVirtualPreset(s, action.getString("name"), true);
        } else if ("resetDraft".equals(op) && s.job != 1 && s.job != 2) {
            resetDraft(s);
        } else if ("calibrateCurrent".equals(op) && s.job != 1 && s.job != 2) {
            calibrateCurrent(s);
        } else if ("rotateVirtual".equals(op)) {
            rotateVirtual(s, action.getBoolean("vertical"));
        } else if ("lockVirtual".equals(op)) {
            lockVirtual(player, s.x, s.y, s.z, s.side);
        } else if ("close".equals(op)) {
            dismissPresetBrowse(s);
            s.open = false;
            s.settingsOnly = false;
        } else if (isCellEdit(op) || "editUndo".equals(op) || "editRedo".equals(op)) {
            edit(s, action, op);
        } else if ("pause".equals(op) && s.job == 1) {
            s.job = 2;
            s.status = "已暂停";
        } else if ("cancel".equals(op)) {
            HologramArea area = AREAS.get(player.getUniqueID());
            if (area != null) area.importing = false;
            s.job = 4;
            cancelCaptureRetry(s);
            clearPending(s);
            s.status = "已取消";
        } else if ("resume".equals(op) && s.job == 2) {
            if (s.mode == 1 && !replacementSnapshotCurrent(s)) {
                s.status = "原结构现场已变化，替换保持暂停，请取消后重新扫描";
                send(s);
                return;
            }
            s.job = 1;
            s.status = "继续施工";
        } else if ("scan".equals(op) && s.job != 1 && s.job != 2) scan(s);
        else if ("configure".equals(op) && s.job != 1 && s.job != 2) {
            if (!configure(s, action)) {
                send(s);
                return;
            }
        } else if ("build".equals(op) && s.job != 1 && s.job != 2) {
            reviewedBuild(s, action);
        } else if ("removePosition".equals(op) && s.job != 1 && s.job != 2) {
            int index = action.getInteger("selected");
            if (s.capture != null && index >= 0 && index < s.capture.cells.size()) {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                s.coordinatePins.remove(position(cell));
                s.manualPositions.remove(position(cell));
                s.pins.remove(index);
                if (s.autoTargets.containsKey(index)) s.suppressedAuto.add(position(s.capture.cells.get(index)));
                scan(s);
                s.status = "位置覆盖已清除，已恢复结构默认目标";
            }
        } else if ("addPosition".equals(op) && s.job != 1 && s.job != 2) {
            addPosition(s, action);
        } else if ("start".equals(op) && s.job != 1 && s.job != 2) {
            if (s.virtual && (!s.virtualPlacement || !s.virtualLocked || s.mode == 2))
                s.status = "请先放置蓝图并右击确定位置，虚拟预设不能拆除";
            else if (s.capturePending) s.status = "正在采集完整结构，请等待自动重试回执";
            else if (s.capture == null || s.capture.incomplete) s.status = "该机器无法完整安全采集，请使用原版提示或搭建";
            else if (!wholeBlueprintPlacement(s) && (s.scope == 2 || s.scope == 3)
                && (s.selected < 0 || s.selected >= s.capture.cells.size())) s.status = "请先选择施工范围的结构格";
            else if (!wholeBlueprintPlacement(s) && s.scope == 3 && s.capture.cells.get(s.selected).family.isEmpty())
                s.status = "选中格没有安全同族适配，请使用单格范围";
            else if (!s.virtual && s.machine.getExtendedFacing()
                .ordinal() != s.facing) {
                    scan(s);
                    s.status = "控制器朝向已变化，请重新确认计划";
                } else if (action.getLong("planRevision") != s.planRevision) s.status = "确认计划已过期，请重新核对差分";
            else if (!planCurrent(s)) {
                refreshPlan(s);
                s.status = "现场已变化，请重新确认更新后的差分";
            } else {
                if (!constructionAllowed(s)) {
                    send(s);
                    return;
                }
                refreshResources(s);
                if (s.planMissing > 0 || !s.planRecoveryFits) {
                    s.status = s.planMissing > 0 ? "整份计划材料不足，请补齐后重新确认" : "背包无法容纳预计回收，请整理后重新确认";
                    send(s);
                    return;
                }
                s.cursor = 0;
                s.completed = 0;
                s.defer = false;
                s.stopTick = false;
                clearPending(s);
                s.job = 1;
                s.replacementAuthorized = s.mode == 1;
                s.work.clear();
                for (int i = 0; i < s.capture.cells.size(); i++) {
                    if (inScope(s, s.capture.cells.get(i), i) && "pending".equals(s.planStatus.get(i))) s.work.add(i);
                }
                controllerWorkLast(s);
                s.phase = "RUNNING";
                resetJobCounts(s, false);
                s.status = "正在直接施工";
            }
        } else if ("native".equals(op) && s.job != 1 && s.job != 2) {
            if (s.mode == 0) reviewedBuild(s, action);
            else s.status = "原版搭建只用于建造模式";
        } else if ("hints".equals(op)) {
            s.hints = true;
            s.status = "显示原版结构提示";
        } else if (("customPin".equals(op) || "clearPin".equals(op)) && s.job != 1 && s.job != 2) {
            int index = action.getInteger("selected");
            if (s.capturePending || s.capture == null || index < 0 || index >= s.capture.cells.size())
                s.status = "请先选择完整采集中的结构格";
            else if ("clearPin".equals(op)) {
                edit(s, action, "restoreCell");
            } else {
                int slot = action.getInteger("inventorySlot");
                ItemStack sample = slot < 0 || slot >= player.inventory.mainInventory.length ? null
                    : player.inventory.mainInventory[slot];
                HologramCapture.Cell cell = s.capture.cells.get(index);
                if (s.mode == 2) s.status = "拆除模式不接受建造目标，请先切换模式";
                else if (sample == null || sample.stackSize < 1 || cell.anchor) s.status = "库存样本或目标格无效";
                else if (!acceptableSample(s, cell, sample)) s.status = "该库存物品不是可放置方块或有效 GT 机器";
                else {
                    ItemStack pin = sample.copy();
                    pin.stackSize = 1;
                    s.selected = index;
                    s.scope = 2;
                    if (pin.getItem() instanceof ItemMachines) s.noHatches = false;
                    setBlueprintTarget(s, cell, pin);
                    s.pins.put(index, pin);
                    s.coordinatePins.put(position(cell), pin.copy());
                    refreshPlan(s);
                    s.status = "库存样本已设为目标；点击施工才扣材料";
                }
            }
        } else if ("sourcePin".equals(op) && s.job != 1 && s.job != 2) {
            int index = action.getInteger("selected"), choice = action.getInteger("choice");
            if (s.capturePending || s.capture == null
                || s.capture.incomplete
                || index < 0
                || index >= s.capture.cells.size()
                || choice < 0
                || choice >= s.materialChoices.size()) {
                s.status = "来源目录或目标位置已失效，请重新选择";
            } else {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                ItemStack pin = s.materialChoices.get(choice)
                    .copy();
                pin.stackSize = 1;
                if (s.mode == 2) s.status = "拆除模式不接受建造目标";
                else if (!acceptableSample(s, cell, pin)) s.status = "该来源物品不是有效放置目标";
                else if (!materialSource(s).takeOne(pin, true)) s.status = "来源物品已不可用，请刷新后重选";
                else {
                    s.selected = index;
                    s.scope = 2;
                    if (pin.getItem() instanceof ItemMachines) s.noHatches = false;
                    setBlueprintTarget(s, cell, pin);
                    s.pins.put(index, pin);
                    s.coordinatePins.put(position(cell), pin.copy());
                    refreshPlan(s);
                    s.status = "来源物品已设为目标；点击施工才扣材料";
                }
            }
        } else if ("hatchPreset".equals(op) && s.job != 1 && s.job != 2) {
            int tier = action.getInteger("tier");
            if (tier < 0 || tier >= gregtech.api.enums.GTValues.VN.length || s.capture == null || s.capture.incomplete)
                s.status = "电压等级或完整结构无效";
            else {
                s.hatchTier = tier;
                s.hatchDowngrade = action.getBoolean("downgrade");
                s.presetEnabled = true;
                s.presetPaused = false;
                s.scope = 0;
                s.noHatches = false;
                s.suppressedAuto.clear();
                for (HologramCapture.Cell cell : s.capture.cells) cell.candidates = null;
                refreshPlan(s);
                s.status = s.presetReason;
            }
        } else if ("clearHatchPreset".equals(op) && s.job != 1 && s.job != 2) {
            s.presetEnabled = false;
            s.presetPaused = true;
            s.autoTargets.clear();
            s.suppressedAuto.clear();
            refreshPlan(s);
            s.status = "整机预设已撤销，已有仓室与手选目标保留";
        } else if ("hatchFacing".equals(op) && s.job != 1 && s.job != 2) {
            int index = action.getInteger("selected"), direction = action.getInteger("facing");
            if (s.capture == null || index < 0 || index >= s.capture.cells.size()) s.status = "请先选择仓室格";
            else {
                HologramCapture.Cell cell = s.capture.cells.get(index);
                boolean legal = direction == -1;
                for (int allowed : legalFacings(s, cell, targetFor(s, cell, index)))
                    if (allowed == direction) legal = true;
                if (cell.anchor || !legal) s.status = "控制器受保护，或该仓室不支持所选方向";
                else {
                    freezeAllocations(s);
                    if (direction == -1) s.facingPins.remove(position(cell));
                    else s.facingPins.put(position(cell), direction);
                    refreshPlan(s);
                    s.status = direction == -1 ? "已恢复自动朝向" : "仓室朝向已指定，施工时生效";
                }
            }
        } else if ("pin".equals(op) && s.job != 1 && s.job != 2) {
            if (s.capturePending) {
                s.status = "正在采集中，请等待完整结构后选择具体目标";
                send(s);
                return;
            }
            int index = action.getInteger("selected"),
                choice = action.hasKey("choiceindex") ? action.getInteger("choiceindex") : action.getInteger("choice");
            if (s.capture != null && index >= 0 && index < s.capture.cells.size()) {
                List<ItemStack> options = candidates(s, s.capture.cells.get(index));
                if (choice >= 0 && choice < options.size()) {
                    HologramCapture.Cell cell = s.capture.cells.get(index);
                    ItemStack chosen = options.get(choice);
                    if (cell.anchor) s.status = "控制器始终受保护";
                    else if (s.mode == 2) s.status = "拆除模式不接受建造目标";
                    else {
                        try {
                            pinSelection(s, action, index, chosen);
                            s.status = "目标已指定；点击施工才会扣材料并放置";
                        } catch (java.io.IOException invalid) {
                            s.status = invalid.getMessage();
                        }
                    }
                }
            }
        } else if (s.job == 2 && ("scan".equals(op) || "configure".equals(op) || "native".equals(op)))
            s.status = "暂停任务保留进度；请先取消再改变配置或重新扫描";
        if (s.job != 1 && s.job != 2
            && ("configure".equals(op) || "customPin".equals(
                op) || "sourcePin".equals(op) || "pin".equals(op) || "addPosition".equals(op) || "clearPin".equals(op)))
            refreshMaterialChoices(s);
        if (beforeEdit != null && !beforeEdit.sameAs(new EditState(s))) {
            remember(s.undoEdits, beforeEdit);
            s.redoEdits.clear();
        }
        persist(s);
        send(s);
    }

    /** Selection and scope changes refresh the diff without redistributing existing automatic targets. */
    private static boolean configure(Session s, NBTTagCompound action) {
        String activeStructure = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
        if (action.hasKey("structureId", 8) && !activeStructure.equals(action.getString("structureId"))) {
            s.status = "结构选择已变化，请重新打开参数面板";
            return false;
        }
        if (s.virtual && action.getInteger("mode") == 2) {
            s.status = "虚拟预设不支持拆除；请先建立实体控制器";
            return false;
        }
        int requestedFacing = action.getInteger("facing");
        if (!action.hasKey("main", 3) || action.getInteger("main") < 1
            || !validChannels(action.getCompoundTag("channels"))
            || (s.capabilities != null
                && !s.capabilities.validConfiguration(action.getInteger("main"), action.getCompoundTag("channels")))
            || action.getInteger("mode") < 0
            || action.getInteger("mode") > 2
            || action.getInteger("scope") < 0
            || action.getInteger("scope") > 3
            || action.getInteger("layer") < -128
            || action.getInteger("layer") > 128
            || (s.machine != null && requestedFacing != s.machine.getExtendedFacing()
                .ordinal())) {
            s.status = "参数无效，配置未修改";
            return false;
        }
        boolean geometryChanged = s.main != action.getInteger("main")
            || !s.channels.equals(action.getCompoundTag("channels"))
            || s.noHatches != action.getBoolean("noHatches")
            || (s.machine != null && s.facing != s.machine.getExtendedFacing()
                .ordinal());
        if (geometryChanged) {
            s.allocationsFrozen = false;
            s.frozenAutoTargets.clear();
        }
        if (s.main != action.getInteger("main")) s.mainExplicit = true;
        s.main = action.getInteger("main");
        s.channels = boundedChannels(action.getCompoundTag("channels"));
        s.noHatches = action.getBoolean("noHatches");
        saveStructureConfiguration(s);
        if (s.virtual && !s.blueprintActiveStructure.isEmpty())
            loadStructureConfiguration(s, s.blueprintActiveStructure);
        if (action.hasKey("materialMain")) s.materialSettings.main = action.getBoolean("materialMain");
        if (action.hasKey("materialContainers"))
            s.materialSettings.containers = action.getBoolean("materialContainers");
        if (action.hasKey("materialMe")) s.materialSettings.me = action.getBoolean("materialMe");
        if (action.hasKey("materialPriority"))
            s.materialSettings.priority = clamp(action.getInteger("materialPriority"), 0, 2);
        s.materialContext = new HologramMaterials.Context(s.player, s.materialSettings);
        s.mode = clamp(action.getInteger("mode"), 0, 2);
        s.scope = clamp(action.getInteger("scope"), 0, 3);
        s.layer = clamp(action.getInteger("layer"), -128, 128);
        s.selected = action.getInteger("selected");
        if (action.hasKey("selectedIndices", 11)) {
            s.selectedIndices.clear();
            for (int index : action.getIntArray("selectedIndices"))
                if (s.capture != null && index >= 0 && index < s.capture.cells.size()) s.selectedIndices.add(index);
        }
        if (geometryChanged || s.capturePending) scan(s);
        else {
            s.job = 0;
            refreshPlan(s);
        }
        persist(s);
        return true;
    }

    private static boolean isCellEdit(String op) {
        return "editCopy".equals(op) || "editCut".equals(op)
            || "editPaste".equals(op)
            || "restoreCell".equals(op)
            || "rotateCell".equals(op);
    }

    private static void pinSelection(Session s, NBTTagCompound action, int source, ItemStack target)
        throws java.io.IOException {
        int[] indices = geometryIndices(s, action);
        if (indices.length == 0) throw new java.io.IOException("请先选择目标位置");
        for (int index : indices) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (cell.anchor || !acceptableSample(s, cell, target))
                throw new java.io.IOException("选中格的结构声明不接受该候选，目标未修改");
        }
        if (indices.length > 1) freezeAllocations(s);
        for (int index : indices) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            setBlueprintTarget(s, cell, target);
            if (s.virtual) s.virtualEdited.add(position(cell));
            s.pins.put(index, target.copy());
            s.coordinatePins.put(position(cell), target.copy());
        }
        s.selected = source;
        s.scope = 2;
        s.selectedIndices.clear();
        for (int index : indices) s.selectedIndices.add(index);
        refreshPlan(s);
    }

    private static boolean isPlanningEdit(String op) {
        return isCellEdit(op) || "clearPin".equals(op)
            || "customPin".equals(op)
            || "sourcePin".equals(op)
            || "pin".equals(op)
            || "hatchFacing".equals(op)
            || "hatchPreset".equals(op)
            || "clearHatchPreset".equals(op);
    }

    private static void remember(List<EditState> history, EditState state) {
        if (history.size() >= 32) history.remove(0);
        history.add(state);
    }

    /** Editing never harvests a block or consumes a material. Clipboard samples carry no device state. */
    private static void edit(Session s, NBTTagCompound action, String op) {
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
            List<EditState> from = undo ? s.undoEdits : s.redoEdits;
            if (from.isEmpty()) {
                s.status = undo ? "没有可撤销的规划编辑" : "没有可重做的规划编辑";
                return;
            }
            remember(undo ? s.redoEdits : s.undoEdits, new EditState(s));
            from.remove(from.size() - 1)
                .apply(s);
            refreshPlan(s);
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
        if (!editableStructureCell(s, cell)) {
            s.status = "该格属于其他结构，请先切换结构";
            return;
        }
        if (cell.anchor) {
            s.status = "控制器始终受保护";
            return;
        }
        if ("editCopy".equals(op) || "editCut".equals(op)) {
            ItemStack sample = targetFor(s, cell, index);
            TileEntity actual = s.player.worldObj.blockExists(cell.x, cell.y, cell.z)
                ? s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z)
                : null;
            if (!explicitTarget(s, cell, index) && !s.autoTargets.containsKey(index)
                && actual instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) actual).getMetaTileEntity() instanceof MTEHatch)
                sample = new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) actual).getMetaTileID());
            if (!acceptableSample(s, cell, sample)) {
                s.status = "本格没有可复制的放置目标";
                return;
            }
            if (HologramRecovery.hasSeal(sample)) {
                s.status = "封存设备携带真实库存，不能复制或剪切；请从普通仓室目标复制";
                return;
            }
            // Item identity only: inventories, fluids, covers and arbitrary item NBT never enter clipboard.
            ItemStack clean = new ItemStack(sample.getItem(), 1, sample.getItemDamage());
            int direction = s.facingPins.getOrDefault(position(cell), s.planFacings.getOrDefault(index, -1));
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
            if (!acceptableSample(s, cell, sample)) {
                s.status = "剪贴板为空或该目标不可放置";
                return;
            }
            int direction = s.editClipboardFacing;
            int[] legal = legalFacings(s, cell, sample);
            if (hatchTier(sample) != Integer.MAX_VALUE && legal.length == 0) {
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
            freezeAllocations(s);
            if (s.virtual) s.virtualEdited.add(position(cell));
            s.pins.put(index, sample.copy());
            s.coordinatePins.put(position(cell), sample.copy());
            s.suppressedAuto.add(position(cell));
            s.facingPins.remove(position(cell));
            if (legal.length > 0 && direction >= 0) s.facingPins.put(position(cell), direction);
            s.selected = index;
            refreshPlan(s);
            s.status = "已粘贴目标规划；施工时才扣除真实材料";
            return;
        }
        ItemStack rotationTarget = targetFor(s, cell, index);
        boolean nativePipe = HologramBlockTransforms.isPipe(rotationTarget);
        if (s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && (nativePipe || !(rotationTarget != null && rotationTarget.getItem() instanceof ItemMachines))) {
            HologramCapture.Cell staged = blueprintCell(cell, cell.x, cell.y, cell.z);
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
            freezeAllocations(s);
            String key = position(cell);
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
            s.cellIndices.clear();
            s.indexedCapture = null;
            s.selected = index;
            refreshPlan(s);
            s.status = "本格方块状态已水平旋转 90°；施工时生效";
            return;
        }
        int[] legal = legalFacings(s, cell, rotationTarget);
        int current = s.facingPins.getOrDefault(position(cell), s.planFacings.getOrDefault(index, -1));
        int next = HologramCellRotation.nextFacing(current, legal);
        if (next < 0) {
            s.status = "本格目标没有可轮换的合法朝向";
            return;
        }
        freezeAllocations(s);
        if (s.virtual) s.virtualEdited.add(position(cell));
        s.facingPins.put(position(cell), next);
        s.selected = index;
        refreshPlan(s);
        s.status = "已轮换到下一合法朝向；施工时生效";
    }

    /** Local edits retain the already chosen whole-machine allocation, including its source cell. */
    private static void freezeAllocations(Session s) {
        if (s.allocationsFrozen) return;
        s.allocationsFrozen = true;
        s.presetSupported = false;
        s.presetReason = "已保留逐格编辑前的仓室分配；手动改动后的配额由原机检查，重新生成预设才重新分配";
        s.frozenAutoTargets.clear();
        for (Map.Entry<Integer, ItemStack> entry : s.autoTargets.entrySet()) s.frozenAutoTargets.put(
            position(s.capture.cells.get(entry.getKey())),
            entry.getValue()
                .copy());
        // Resolve a former family-wide pin before switching to a single-cell scope.
        if (s.scope == 3) for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.anchor || s.pins.containsKey(i) || !explicitTarget(s, cell, i)) continue;
            ItemStack target = targetFor(s, cell, i);
            if (target != null) {
                s.pins.put(i, target.copy());
                s.coordinatePins.put(position(cell), target.copy());
            }
        }
    }

    private static boolean restoreCell(Session s, HologramCapture.Cell cell, int index) {
        if ("templateController".equals(cell.role)) {
            s.status = "模板控制器不能恢复外壳";
            return false;
        }
        HologramCasingFallback.Target casing = HologramCasingFallback.resolve(cell.element, elementTrigger(s, cell));
        ItemStack target = casing == null ? null : new ItemStack(casing.block, 1, casing.meta);
        if (s.virtual && s.virtualPreset != null) for (HologramPresets.Cell saved : s.virtualPreset.cells)
            if (saved.x == cell.x - s.x && saved.y == cell.y - s.y && saved.z == cell.z - s.z)
                target = saved.defaultStack();
        if (target == null && !cell.userPosition
            && !cell.unknown
            && !"hatch".equals(cell.role)
            && cell.block != null
            && cell.block != Blocks.air) {
            ItemStack original = new ItemStack(cell.block, 1, cell.meta);
            if (!(original.getItem() instanceof ItemMachines) && acceptableSample(s, cell, original)) target = original;
        }
        if (!acceptableSample(s, cell, target)) {
            s.status = "本格没有可确认的结构默认外壳，规划未修改";
            return false;
        }
        freezeAllocations(s);
        if (s.virtual) s.virtualEdited.add(position(cell));
        s.pins.put(index, target.copy());
        s.coordinatePins.put(position(cell), target.copy());
        s.facingPins.remove(position(cell));
        s.suppressedAuto.add(position(cell));
        s.selected = index;
        s.scope = 2;
        refreshPlan(s);
        return true;
    }

    public static void tick() {
        Iterator<Session> sessions = SESSIONS.values()
            .iterator();
        while (sessions.hasNext()) {
            Session s = sessions.next();
            if (s.player.playerNetServerHandler == null || s.player.playerNetServerHandler.netManager == null
                || !s.player.playerNetServerHandler.netManager.isChannelOpen()) {
                clearPending(s);
                cancelCaptureRetry(s);
                clearAreaSelection(s);
                sessions.remove();
                continue;
            }
            if (!valid(s)) clearAreaSelection(s);
            HologramArea area = AREAS.get(s.player.getUniqueID());
            if (area != null && area.importing) {
                try {
                    if (!valid(s)) throw new java.io.IOException("工具、维度或玩家状态已变化");
                    if (area.tick(s.player, trigger(s))) {
                        s.x = area.minX;
                        s.y = area.minY;
                        s.z = area.minZ;
                        s.target = null;
                        s.machine = null;
                        s.context = null;
                        s.virtual = true;
                        s.virtualPlacement = false;
                        // A fresh area draft must not inherit the previous machine's dismantling mode or scope.
                        s.mode = 0;
                        s.scope = 0;
                        s.virtualPreset = area.blueprint;
                        s.areaOverviewOwner = area;
                        for (HologramArea.Structure structure : area.structures.values()) {
                            HologramPresets.Structure saved = new HologramPresets.Structure();
                            saved.id = structure.id;
                            saved.name = structure.machine.getLocalName();
                            saved.x = structure.x - s.x;
                            saved.y = structure.y - s.y;
                            saved.z = structure.z - s.z;
                            saved.controllerId = structure.machine.getBaseMetaTileEntity()
                                .getMetaTileID();
                            saved.sourceClass = structure.machine.getClass()
                                .getName();
                            saved.sourceMod = sourceMod(structure.machine.getClass());
                            saved.enabled = structure.enabled;
                            if (structure.capture != null)
                                saved.main = Math.max(1, structure.capture.trigger.stackSize);
                            area.blueprint.structures.add(saved);
                        }
                        for (HologramPresets.Cell saved : area.blueprint.cells) {
                            saved.structureIds = area.memberships(s.x + saved.x, s.y + saved.y, s.z + saved.z);
                            saved.structureId = saved.structureIds.isEmpty() ? null : saved.structureIds.get(0);
                            if (!saved.structureIds.isEmpty() && !saved.controller) {
                                List<ItemStack> allowed = null;
                                for (String member : saved.structureIds) {
                                    Session source = areaDeclarationSession(s, area, member);
                                    HologramCapture.Cell declared = source == null ? null
                                        : source.capture.positions
                                            .get((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z));
                                    List<ItemStack> local = declared == null || source.capture.incomplete
                                        ? Collections.emptyList()
                                        : declaredCandidates(source, declared);
                                    if (allowed == null) allowed = new ArrayList<>(local);
                                    else allowed.removeIf(
                                        target -> local.stream()
                                            .noneMatch(option -> sameMaterial(option, target)));
                                }
                                saved.allowedTargets = savedTargets(
                                    allowed == null ? Collections.emptyList() : allowed);
                            }
                        }
                        populateVirtual(s);
                        refreshPlan(s);
                        s.status = "区域已导入；识别 " + area.structures.size() + " 个声明控制器，可分别编辑与保存蓝图";
                        s.open = true;
                        send(s);
                    } else if (s.player.worldObj.getTotalWorldTime() % 10 == 0) {
                        // Progress snapshots keep the existing editor read-only and never request reopening.
                        send(s);
                    }
                } catch (java.io.IOException | RuntimeException | LinkageError failure) {
                    area.importing = false;
                    s.status = "区域导入失败：" + failure.getMessage();
                    s.open = true;
                    send(s);
                }
                continue;
            }
            if (s.capturePending) {
                if (!valid(s) || s.machine == null
                    || s.machine.getExtendedFacing()
                        .ordinal() != s.retryFacing
                    || !captureConfigurationCurrent(s)) {
                    cancelCaptureRetry(s);
                    clearPending(s);
                    s.job = 4;
                    s.status = "工具、目标、控制器朝向或配置已变化，自动采集已停止";
                    send(s);
                } else if (s.player.worldObj.getTotalWorldTime() >= s.captureRetryDue) {
                    scan(s, false);
                    send(s);
                }
                continue;
            }
            if (s.job != 1) continue;
            if (!valid(s) || (!s.virtual && (s.machine == null || s.machine.getExtendedFacing()
                .ordinal() != s.facing))) {
                clearPending(s);
                s.job = 4;
                s.status = "工具、目标或控制器朝向已变化，请重新采集";
                send(s);
                continue;
            }
            s.materialContext = new HologramMaterials.Context(s.player, s.materialSettings);
            s.materialContext.flushRecovery();
            s.ackIndices.clear();
            s.ackSuccesses.clear();
            long deadline = System.nanoTime() + 100000000L;
            int attempted = 0;
            while (s.cursor < s.work.size() && attempted < BUDGET && (attempted == 0 || System.nanoTime() < deadline)) {
                attempted++;
                int index = s.work.get(s.cursor);
                HologramCapture.Cell cell = s.capture.cells.get(index);
                boolean unchanged = expectedCurrent(s, index);
                boolean success = unchanged && operate(s, cell, index);
                if (!unchanged) {
                    cell.status = "protected";
                    s.status = "现场已变化，请重新扫描";
                }
                if (s.defer) {
                    s.defer = false;
                    if (s.deferredIndex != index) {
                        s.deferredIndex = index;
                        s.deferAttempts = 0;
                    }
                    if (++s.deferAttempts < 3) break;
                    cell.status = "unsupported";
                    s.status = "原版元素持续要求等待，请重新规划或手动施工";
                }
                s.deferredIndex = -1;
                s.deferAttempts = 0;
                s.cursor++;
                boolean changed = success && ("placed".equals(cell.status) || "replaced".equals(cell.status)
                    || "removed".equals(cell.status));
                if (changed) s.completed++;
                else if (!success && "pending".equals(cell.status)) cell.status = "unsupported";
                s.ackIndices.add(index);
                s.ackSuccesses.add(changed ? 1 : 0);
                s.ackIndex = index;
                s.ackSuccess = changed;
                if (s.stopTick) {
                    s.stopTick = false;
                    break;
                }
            }
            s.phase = "ACK";
            s.batchSeq++;
            updateJobCounts(s);
            if (s.cursor >= s.work.size()) {
                int unresolved = 0;
                for (int i = 0; i < s.capture.cells.size(); i++)
                    if (inScope(s, s.capture.cells.get(i), i) && !s.capture.cells.get(i).anchor) {
                        String status = s.capture.cells.get(i).status;
                        if (!"satisfied".equals(status) && !"placed".equals(status)
                            && !"replaced".equals(status)
                            && !"removed".equals(status)) unresolved++;
                    }
                s.job = unresolved == 0 ? 3 : 5;
                s.status = s.jobPartialPlacement
                    ? "部分放置结束：已放置 " + s.completed
                        + " 格；缺料跳过 "
                        + s.jobMissingCells
                        + " 格；保护或占位跳过 "
                        + s.jobProtectedCells
                        + " 格；不支持跳过 "
                        + s.jobUnsupportedCells
                        + " 格"
                    : unresolved == 0 ? "范围内施工完成：已完成 " + s.completed + " 格（未执行机器成型检查）"
                        : "部分完成：已完成 " + s.completed
                            + " 格；缺料跳过 "
                            + s.jobMissingCells
                            + " 格；其他未完成 "
                            + (unresolved - s.jobMissingCells)
                            + " 格";
            }
            // One full result per batch, never one packet per block.
            send(s);
        }
    }

    private static void nativeBuild(Session s) {
        if (blockedByAreaImport(s)) return;
        if (s.mode != 0) {
            s.status = "原版搭建只用于补建模式";
            return;
        }
        // Native constructors can write outside the verified differential transaction.
        // Route complete captures through that transaction; never guess from partial hints.
        if (s.capture == null || s.capture.incomplete || s.capture.pending || s.capturePending) {
            s.status = "结构采集不完整，无法确认位置是否被遮挡，未施工";
            return;
        }
        directBuild(s, false);
    }

    private static void resetJobCounts(Session s, boolean partial) {
        s.jobPartialPlacement = partial;
        s.jobMissingCells = s.jobProtectedCells = s.jobUnsupportedCells = s.jobSkippedCells = 0;
    }

    private static void updateJobCounts(Session s) {
        s.jobMissingCells = s.jobProtectedCells = s.jobUnsupportedCells = 0;
        for (int i = 0; i < s.cursor && i < s.work.size(); i++) {
            String status = s.capture.cells.get(s.work.get(i)).status;
            if ("missing".equals(status)) s.jobMissingCells++;
            else if ("protected".equals(status) || "blocked".equals(status)) s.jobProtectedCells++;
            else if ("unsupported".equals(status)) s.jobUnsupportedCells++;
        }
        s.jobSkippedCells = Math.max(0, s.cursor - s.completed);
    }

    private static String position(HologramCapture.Cell cell) {
        return cell.x + "," + cell.y + "," + cell.z;
    }

    private static int cellIndex(Session s, HologramCapture.Cell cell) {
        if (s.capture == null) return -1;
        if (s.indexedCapture != s.capture || s.cellIndices.size() != s.capture.cells.size()) {
            s.cellIndices.clear();
            for (int i = 0; i < s.capture.cells.size(); i++) s.cellIndices.put(s.capture.cells.get(i), i);
            s.indexedCapture = s.capture;
        }
        return s.cellIndices.getOrDefault(cell, -1);
    }

    private static boolean validRelativePosition(Session s, int dx, int dy, int dz) {
        long x = (long) s.x + dx, y = (long) s.y + dy, z = (long) s.z + dz;
        return x >= -30000000L && x < 30000000L
            && z >= -30000000L
            && z < 30000000L
            && y >= 0
            && y < s.player.worldObj.getHeight();
    }

    private static void addPosition(Session s, NBTTagCompound action) {
        if ((!s.virtual && s.target == null) || s.capture == null || s.capture.incomplete || s.capturePending) {
            s.status = "请先完成结构采集";
            return;
        }
        int dx = action.getInteger("dx"), dy = action.getInteger("dy"), dz = action.getInteger("dz");
        if (!action.hasKey("dx", 3) || !action.hasKey("dy", 3)
            || !action.hasKey("dz", 3)
            || !validRelativePosition(s, dx, dy, dz)) {
            s.status = "位置必须在世界坐标与高度范围内";
            return;
        }
        int x = s.x + dx, y = s.y + dy, z = s.z + dz;
        if (!s.player.worldObj.blockExists(x, y, z) || y < 0 || y >= s.player.worldObj.getHeight()) {
            s.status = "该位置未加载或超出世界范围";
            return;
        }
        if (action.getBoolean("airOnly")
            && (!s.player.worldObj.isAirBlock(x, y, z) || !withinAirEditBounds(s, action, x, y, z))) {
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
                refreshPlan(s);
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
        s.manualPositions.put(position(cell), cell);
        s.capture.cells.add(cell);
        s.capture.positions.put(position(cell), cell);
        s.selected = s.capture.cells.size() - 1;
        s.selectedIndices.clear();
        s.selectedIndices.add(s.selected);
        s.scope = 2;
        refreshPlan(s);
        s.status = "位置已添加；指定目标后才会施工";
    }

    private static boolean withinAirEditBounds(Session s, NBTTagCompound action, int x, int y, int z) {
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

    private static void directBuild(Session s) {
        directBuild(s, true);
    }

    /** A reviewed request must keep the same world differential; resource availability is checked again. */
    private static void reviewedBuild(Session s, NBTTagCompound action) {
        if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision) {
            s.forcePlacementReady = false;
            s.status = "确认计划已更新，请重新核对材料与位置";
            return;
        }
        if (s.capture == null || s.capture.incomplete || s.capture.pending || s.capturePending) {
            s.forcePlacementReady = false;
            s.status = "请等待完整结构采集，再核对材料确认施工";
            return;
        }
        if (!planCurrent(s)) {
            s.forcePlacementReady = false;
            refreshPlan(s);
            s.status = "现场已变化，请重新核对更新后的材料与位置";
            return;
        }
        // A repeated packet alone cannot exercise the partial-material capability.
        if (!action.getBoolean("forceExplicit")) s.forcePlacementReady = false;
        directBuild(s, false);
    }

    private static void directBuild(Session s, boolean rescan) {
        if (blockedByAreaImport(s)) return;
        if (!aeReady(s)) return;
        if (s.virtual && (!s.virtualPlacement || !s.virtualLocked || s.mode == 2)) {
            s.status = s.mode == 2 ? "虚拟预设不支持拆除" : !s.virtualPlacement ? "请在蓝图库点击放置，再右击确定位置并核对材料" : "请先右击确定位置，再在界面确认施工";
            return;
        }
        if (rescan) scan(s);
        if (s.capturePending) {
            s.buildAfterCapture = true;
            s.status = "正在采集完整结构，完成后将按当前规划施工";
            return;
        }
        if (s.capture == null || s.capture.incomplete || s.capturePending) {
            cancelCaptureRetry(s);
            if (s.mode == 0) nativeBuild(s);
            else s.status = "替换和拆除需要完整结构采集";
            return;
        }
        if (!wholeBlueprintPlacement(s) && (s.scope == 2 || s.scope == 3)
            && (s.selected < 0 || s.selected >= s.capture.cells.size())) {
            s.status = "请先选择施工位置";
            return;
        }
        if (!wholeBlueprintPlacement(s) && s.scope == 3 && s.capture.cells.get(s.selected).family.isEmpty()) {
            s.status = "选中格没有同族范围，请使用单格范围";
            return;
        }
        refreshResources(s);
        if (!constructionAllowed(s)) return;
        if (!s.planRecoveryFits) {
            s.status = "背包无法容纳回收，请整理后重试";
            return;
        }
        if (s.planMissing > 0 && (!s.virtual || !s.forcePlacementReady)) {
            s.forcePlacementReady = s.virtual;
            s.status = s.virtual
                ? (s.blueprintResourceMissing.isEmpty() ? "蓝图材料不足" : s.blueprintResourceMissing)
                    + "；再次点击施工将只放置现有材料，权限与占位保护仍有效"
                : "施工材料不足，请补齐后重试";
            return;
        }
        boolean partial = s.virtual && s.planMissing > 0 && s.forcePlacementReady;
        s.forcePlacementReady = false;
        s.work.clear();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            if (inScope(s, s.capture.cells.get(i), i) && "pending".equals(s.planStatus.get(i))) s.work.add(i);
        }
        controllerWorkLast(s);
        if (s.work.isEmpty()) {
            s.status = "选定范围没有可安全施工的位置";
            return;
        }
        s.replacementAuthorized = s.mode == 1;
        s.cursor = 0;
        s.completed = 0;
        s.job = 1;
        resetJobCounts(s, partial);
        s.phase = "RUNNING";
        s.status = "正在直接施工";
    }

    private static void controllerWorkLast(Session s) {
        if (s.virtual) s.work.sort(
            (left, right) -> Boolean.compare(
                "templateController".equals(s.capture.cells.get(left).role),
                "templateController".equals(s.capture.cells.get(right).role)));
    }

    private static void clearPending(Session s) {
        s.ackIndices.clear();
        s.ackSuccesses.clear();
        s.work.clear();
        s.phase = "IDLE";
        s.ackIndex = -1;
        s.ackSuccess = false;
    }

    private static boolean valid(Session s) {
        ItemStack held = s.player.getHeldItem();
        return !s.player.isDead && s.player.dimension == s.dimension
            && held != null
            && held.hasTagCompound()
            && s.toolId.equals(
                held.getTagCompound()
                    .getString(TOOL_ID))
            && held.getItem() == s.tool
            && s.player.inventory.currentItem == s.slot
            && (!s.virtual || !s.virtualPlacement
                || !s.virtualLocked
                || (HologramWorldFlow.worldPosition(s.x, s.y, s.z, s.player.worldObj.getHeight())
                    && previewReach(s.player, s.x, s.y, s.z)))
            && (s.target == null || (s.player.getDistanceSq(s.x + .5, s.y + .5, s.z + .5) <= 4096
                && s.player.worldObj.blockExists(s.x, s.y, s.z)
                && s.player.worldObj.getTileEntity(s.x, s.y, s.z) == s.target
                && (!(s.target instanceof IGregTechTileEntity)
                    || ((IGregTechTileEntity) s.target).getMetaTileEntity() == s.context)));
    }

    private static void setMemberships(HologramCapture.Cell cell, List<String> ids) {
        cell.structureIds = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        cell.structureId = cell.structureIds.isEmpty() ? "" : cell.structureIds.get(0);
    }

    private static boolean membershipEnabled(Session s, HologramCapture.Cell cell) {
        if (cell.memberships()
            .isEmpty()) return true;
        if (s.virtualPreset != null) {
            String active = wholeBlueprintPlacement(s) ? "" : s.blueprintActiveStructure;
            boolean found = false, enabled = false;
            for (HologramPresets.Structure structure : s.virtualPreset.structures) {
                if (!cell.belongsTo(structure.id)) continue;
                if (!active.isEmpty() && !active.equals(structure.id)) continue;
                found = true;
                enabled |= structure.enabled;
            }
            return active.isEmpty() ? !found || enabled : found && enabled;
        }
        HologramArea area = AREAS.get(s.player.getUniqueID());
        if (!s.virtual && area != null) {
            boolean found = false, enabled = false;
            for (String id : cell.memberships()) {
                HologramArea.Structure structure = area.structures.get(id);
                if (structure == null || (!area.active.isEmpty() && !area.active.equals(id))) continue;
                found = true;
                enabled |= structure.enabled;
            }
            return area.active.isEmpty() ? !found || enabled : found && enabled;
        }
        return true;
    }

    private static boolean inScope(Session s, HologramCapture.Cell c, int index) {
        if (c.excluded) return false;
        if (!membershipEnabled(s, c)) return false;
        if (wholeBlueprintPlacement(s)) return true;
        if (s.scope == 2 && !s.selectedIndices.isEmpty()) return s.selectedIndices.contains(index);
        return s.scope == 0 || (s.scope == 1 && c.y - s.y == s.layer)
            || (s.scope == 2 && index == s.selected)
            || (s.scope == 3 && s.selected >= 0
                && s.selected < s.capture.cells.size()
                && !c.family.isEmpty()
                && sameSelectedFamily(s, c));
    }

    private static boolean wholeBlueprintPlacement(Session s) {
        return s.virtual && s.virtualPlacement && s.virtualLocked;
    }

    private static void clearPlacementSelection(Session s) {
        s.scope = 0;
        s.selected = -1;
        s.selectedIndices.clear();
        s.blueprintActiveStructure = "";
    }

    private static boolean sameSelectedFamily(Session s, HologramCapture.Cell cell) {
        if (s.capture == null || s.selected < 0 || s.selected >= s.capture.cells.size()) return false;
        HologramCapture.Cell source = s.capture.cells.get(s.selected);
        if (cell.family.isEmpty() || !cell.family.equals(source.family)) return false;
        String active = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
        if (active.isEmpty()) active = source.structureId;
        return active.isEmpty() ? cell.memberships()
            .isEmpty() : cell.belongsTo(active);
    }

    private static boolean trivial(World w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        return w.isAirBlock(x, y, z)
            || (b instanceof BlockBush && b.isReplaceable(w, x, y, z) && w.getTileEntity(x, y, z) == null);
    }

    private static boolean coil(Block b, int meta) {
        return b == GregTechAPI.sBlockCasings5 && meta >= 0
            && meta < 16
            && BlockCasings5.getCoilHeatFromDamage(meta) != HeatingCoilLevel.None;
    }

    private static boolean knownSatisfied(HologramCapture.Cell c, Block old, int meta) {
        if (old == c.block && meta == c.meta) return true;
        if (old instanceof IHeatingCoil && coil(c.block, c.meta)
            && factory(c.element, "ofCoil", new IdentityHashMap<>(), 0)) {
            try {
                return ((IHeatingCoil) old).getCoilHeat(meta) == BlockCasings5.getCoilHeatFromDamage(c.meta);
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    /** A capture position plus a real valid coil family is the deliberately narrow destructive adapter. */
    private static boolean destructive(HologramCapture.Cell c, Block old, int meta) {
        if (HologramFrameSupport.accepts(c.element, old, meta)) return true;
        if (old instanceof BlockFrameBox && !old.hasTileEntity(meta)
            && old == c.block
            && meta == c.meta
            && factory(c.element, "ofBlock", new IdentityHashMap<>(), 0)) return true;
        HologramReplacementFamily.Family family = HologramReplacementFamily.resolve(c.element, c.block, c.meta);
        if (family != null && family.contains(old, meta)) return true;
        if (coil(c.block, c.meta) && coil(old, meta)) return factory(c.element, "ofCoil", new IdentityHashMap<>(), 0);
        return old == c.block && meta == c.meta
            && (!old.hasTileEntity(meta) || HologramFrameSupport.inertFrameBlock(old, meta))
            && factory(c.element, "ofBlock", new IdentityHashMap<>(), 0);
    }

    /**
     * Identify actual factory provenance through element delegates, rather than treating a visual hint as authority.
     */
    private static boolean factory(Object value, String name, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || depth > 12 || seen.put(value, Boolean.TRUE) != null) return false;
        if (value instanceof IStructureElement) {
            try {
                IStructureElement<?> backing = HologramDeclarationWrappers.backing((IStructureElement<?>) value);
                if (backing != null && factory(backing, name, seen, depth + 1)) return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        }
        Class<?> type = value.getClass();
        java.lang.reflect.Method enclosing = type.getEnclosingMethod();
        if (enclosing != null && enclosing.getName()
            .equals(name)
            && enclosing.getDeclaringClass()
                .getName()
                .equals(
                    (name.equals("ofCoil") || name.equals("ofFrame")) ? "gregtech.api.util.GTStructureUtility"
                        : "com.gtnewhorizon.structurelib.structure.StructureUtility"))
            return true;
        for (java.lang.reflect.Field field : type.getDeclaredFields())
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                && IStructureElement.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        if (factory(field.get(value), name, seen, depth + 1)) return true;
                    } catch (IllegalAccessException | RuntimeException ignored) {}
                }
        if (value instanceof IStructureElementChain)
            for (Object child : ((IStructureElementChain<?>) value).fallbacks())
                if (factory(child, name, seen, depth + 1)) return true;
        return false;
    }

    private static ItemStack targetFor(Session s, HologramCapture.Cell c, int index) {
        if (index >= 0 && index < s.planTargets.size()) {
            ItemStack target = s.planTargets.get(index);
            return target == null ? null : target.copy();
        }
        return draftTarget(s, c, index);
    }

    private static ItemStack draftTarget(Session s, HologramCapture.Cell c, int index) {
        ItemStack pin = s.pins.get(index);
        if (pin == null && s.scope == 3
            && s.selected >= 0
            && s.selected < s.capture.cells.size()
            && !c.family.isEmpty()
            && sameSelectedFamily(s, c)) pin = s.pins.get(s.selected);
        if (pin != null) return pin.copy();
        if (c.userPosition) return null;
        ItemStack automatic = s.autoTargets.get(index);
        if (!s.noHatches && automatic != null) return automatic.copy();
        TileEntity actual = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
        // Capture replaces hint blocks with any recognized casing fallback. A remaining
        // hatch hint therefore needs its declared hatch even when automatic filling is off.
        // Preserve only an installed hatch accepted by that declaration; never synthesize one.
        if ((!s.noHatches || (c.block == StructureLibAPI.getBlockHint() && "hatch".equals(c.role)))
            && actual instanceof IGregTechTileEntity
            && !c.anchor) {
            ItemStack existing = new ItemStack(
                GregTechAPI.sBlockMachines,
                1,
                ((IGregTechTileEntity) actual).getMetaTileID());
            if (hatchElement(s, c, existing, true) != null) return existing;
        }
        if (c.block != null && c.block != Blocks.air) {
            ItemStack block = new ItemStack(c.block, 1, c.meta);
            if (block.getItem() != null) return block;
        }
        List<ItemStack> options = candidates(s, c);
        return options.isEmpty() ? null
            : options.get(0)
                .copy();
    }

    private static boolean explicitTarget(Session s, HologramCapture.Cell c, int index) {
        return s.pins.containsKey(index) || (s.scope == 3 && s.pins.containsKey(s.selected)
            && s.selected >= 0
            && s.selected < s.capture.cells.size()
            && !c.family.isEmpty()
            && sameSelectedFamily(s, c));
    }

    private static boolean explicitGrade(Session s, HologramCapture.Cell c) {
        if (s.mainExplicit) return true;
        for (String channel : HologramDeclarationWrappers.channels(c.element))
            if (s.channels.hasKey(channel, 3)) return true;
        if (c.family.equals("coil")) return s.channels.hasKey("coil", 3);
        if (c.family.startsWith("glass:")) return s.channels.hasKey("glass", 3);
        return c.family.startsWith("tiered:")
            && (s.channels.hasKey("item_pipe", 3) || s.channels.hasKey("coke_oven_casing", 3));
    }

    private static boolean targetSatisfied(Session s, HologramCapture.Cell c, int index, Block old, int meta) {
        ItemStack target = targetFor(s, c, index);
        if (s.facingPins.containsKey(position(c))) {
            TileEntity actual = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            if (!(actual instanceof IGregTechTileEntity) || ((IGregTechTileEntity) actual).getFrontFacing()
                .ordinal() != s.facingPins.get(position(c))) return false;
        }
        if (explicitTarget(s, c, index) && HologramRecovery.hasSeal(target)) return HologramRecovery
            .matchesPlaced(s.player.worldObj, c.x, c.y, c.z, target, s.facingPins.getOrDefault(position(c), -1));
        if (s.facingPins.containsKey(position(c)) || s.autoTargets.containsKey(index)
            || explicitGrade(s, c)
            || explicitTarget(s, c, index)) return targetMatches(s, c, index, old, meta);
        HologramReplacementFamily.Family family = HologramReplacementFamily.resolve(c.element, c.block, c.meta);
        if (family != null && family.contains(old, meta)) return true;
        return knownSatisfied(c, old, meta);
    }

    private static boolean targetMatches(Session s, HologramCapture.Cell c, int index, Block old, int meta) {
        ItemStack wanted = targetFor(s, c, index);
        if (wanted == null) return false;
        if (s.virtual && s.virtualPreset.schema >= 2 && HologramNativePlacement.supported(c.block)) {
            // A multipart/conduit material item often has no ItemBlock and maps to air.
            // Only the captured native block can satisfy this target, never that item's block mapping.
            if (old != c.block || meta != c.meta) return false;
            try {
                return HologramNativePlacement.matchesPlaced(
                    c.block,
                    c.meta,
                    s.player.worldObj.getTileEntity(c.x, c.y, c.z),
                    HologramBlueprintNbt.forPlacement(
                        c.block,
                        c.meta,
                        c.tileNbt,
                        s.player.getUniqueID(),
                        s.player.getCommandSenderName(),
                        c.x,
                        c.y,
                        c.z,
                        s.virtualPreset.retainNbt,
                        placementRetainCovers(s),
                        placementKeys(s)));
            } catch (java.io.IOException invalid) {
                return false;
            }
        }
        if (wanted != null && wanted.getItem() instanceof ItemMachines) {
            TileEntity tile = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            return old == Block.getBlockFromItem(wanted.getItem()) && tile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) tile).getMetaTileID() == wanted.getItemDamage();
        }
        if (old != Block.getBlockFromItem(wanted.getItem())) return false;
        if (s.virtual && s.virtualPreset.schema >= 2 && c.block == old) return meta == c.meta;
        if (explicitTarget(s, c, index)) {
            // Direction and half-block placement metadata need not equal the item's damage value.
            try {
                return old.getDamageValue(s.player.worldObj, c.x, c.y, c.z) == wanted.getItemDamage();
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return meta == wanted.getItem()
            .getMetadata(wanted.getItemDamage());
    }

    private static ItemStack elementTrigger(Session s, HologramCapture.Cell c) {
        ItemStack result = trigger(s);
        if (c.elementTrigger != null) {
            // Preserve the current inventory tool's identity/unrelated NBT while retaining the real piece signal.
            result.stackSize = c.elementTrigger.stackSize;
            List<String> existing = new ArrayList<>();
            ChannelDataAccessor.iterateChannelData(result)
                .forEach(entry -> existing.add(entry.getKey()));
            for (String name : existing) ChannelDataAccessor.unsetChannelData(result, name);
            ChannelDataAccessor.iterateChannelData(c.elementTrigger)
                .forEach(entry -> ChannelDataAccessor.setChannelData(result, entry.getKey(), entry.getValue()));
        }
        // A concrete new hatch pin authorizes that item even when the automatic hatch toggle is off.
        if (!s.noHatches && "hatch".equals(c.role)) {
            int index = cellIndex(s, c);
            if (index >= 0 && (explicitTarget(s, c, index) || s.autoTargets.containsKey(index))) {
                ItemStack target = targetFor(s, c, index);
                if (target != null && target.getItem() instanceof ItemMachines)
                    ChannelDataAccessor.setChannelData(result, "gt_hatch", 1);
            }
        }
        return result;
    }

    /** Pure preflight: never posts Forge events or calls element.check/checkMachine. */
    private static String classify(Session s, HologramCapture.Cell c, int index) {
        World w = s.player.worldObj;
        if (c.y < 0 || c.y >= w.getHeight() || !w.blockExists(c.x, c.y, c.z)) return "unsupported";
        if (c.anchor) return "protected";
        boolean approvedNativePrimary = false;
        if ((s.virtual || !s.areaStructureId.isEmpty()) && s.mode != 2 && targetFor(s, c, index) != null) {
            ItemStack proposed = targetFor(s, c, index);
            if (!acceptableSample(s, c, proposed)) return "unsupported";
            // placeableSample already validated this exact primary against its registered native material provider.
            approvedNativePrimary = s.virtual && s.virtualPreset != null
                && s.virtualPreset.schema >= 2
                && !(proposed.getItem() instanceof ItemMachines)
                && !c.tileNbt.isEmpty()
                && HologramNativePlacement.adapter(c.block) != null;
        }
        if (c.unknown && targetFor(s, c, index) == null) return "unsupported";
        if (!c.sourceWarning.isEmpty() && "templateController".equals(c.role)) return "unsupported";
        if (!coverCostsValid(s, c) || !resourcePlanValid(s, c)) return "unsupported";
        if (s.mode == 1 && (!s.replacementReady || !originalMember(s, c))) return "protected";
        if (c.userPosition && !explicitTarget(s, c, index)) return "satisfied";
        if (s.mode == 0 && !s.player.capabilities.isCreativeMode
            && !trivial(w, c.x, c.y, c.z)
            && (targetFor(s, c, index) != null || c.block == Blocks.air && !c.unknown)
            && !targetMatches(s, c, index, w.getBlock(c.x, c.y, c.z), w.getBlockMetadata(c.x, c.y, c.z)))
            return "blocked";
        if (s.mode == 2 && c.block == Blocks.air && !explicitTarget(s, c, index)) return "satisfied";
        // Keeping a matching tile requires no harvest authority; unmatched tiles remain protected below.
        if (s.mode == 0 && targetMatches(s, c, index, w.getBlock(c.x, c.y, c.z), w.getBlockMetadata(c.x, c.y, c.z)))
            return "satisfied";
        if (w.getTileEntity(c.x, c.y, c.z) != null && !recoverable(s, c)) return "protected";
        if (!w.canMineBlock(s.player, c.x, c.y, c.z) || !s.player.canPlayerEdit(c.x, c.y, c.z, 1, trigger(s)))
            return "protected";
        boolean empty = trivial(w, c.x, c.y, c.z);
        if (s.mode == 2) return empty ? "satisfied" : removable(s, c) ? "pending" : "protected";
        Block old = w.getBlock(c.x, c.y, c.z);
        int meta = w.getBlockMetadata(c.x, c.y, c.z);
        if ((c.element == null && !explicitTarget(s, c, index)) || (c.unknown && !explicitTarget(s, c, index)
            && !s.autoTargets.containsKey(index)
            && !(!s.noHatches && s.channels.hasKey("gt_hatch", 3) && "hatch".equals(c.role)))) return "unsupported";
        if (targetSatisfied(s, c, index, old, meta)) return "satisfied";
        if (s.facingPins.containsKey(position(c)) && targetMatches(s, c, index, old, meta))
            return s.mode == 1 ? "pending" : "satisfied";
        ItemStack target = targetFor(s, c, index);
        if (target == null || Block.getBlockFromItem(target.getItem()) == Blocks.air && !approvedNativePrimary)
            return "unsupported";
        if (explicitTarget(s, c, index)) {
            if (!acceptableSample(s, c, target)) return "unsupported";
        }
        if (empty) return s.mode == 1 ? "protected" : "pending";
        if (s.mode == 0 && !s.player.capabilities.isCreativeMode) return "blocked";
        if (!removable(s, c)) return "protected";
        return acceptableSample(s, c, target) ? "pending" : "protected";
    }

    private static String reason(Session s, HologramCapture.Cell c, int index) {
        if (!c.sourceWarning.isEmpty() && "templateController".equals(c.role)) return c.sourceWarning;
        if (!c.resourceError.isEmpty()) return c.resourceError + "；取消对应 NBT 条目后可降级放置";
        World w = s.player.worldObj;
        if (!w.blockExists(c.x, c.y, c.z)) return "区块未加载";
        if (c.anchor) return "始终保留控制器锚点";
        if ("blocked".equals(c.status)) return "结构占位被遮挡，生存补建不会替换旧方块，请清空后重试";
        if (s.mode == 1 && (!s.replacementReady || !originalMember(s, c))) return "替换仅限已完整构建的原结构位置；新增位置与声明空气保持现场";
        if (s.mode == 1 && orientationOnly(s, c, index)) return "仅调整现有仓室朝向，不拆除、不扣材料";
        if (c.userPosition && !explicitTarget(s, c, index)) return "新增位置尚未指定目标，保持现场";
        if (s.mode == 2 && c.block == Blocks.air && !explicitTarget(s, c, index)) return "声明空气位置保持现场";
        if (w.getTileEntity(c.x, c.y, c.z) != null)
            return recoverable(s, c) ? "完整封存设备状态、库存、流体与覆盖板后回收" : "此设备尚无完整状态回收路径";
        if ("unsupported".equals(c.status) || (c.element == null && !explicitTarget(s, c, index)))
            return "此元素未提供安全施工目标";
        if ("protected".equals(c.status)) return "权限限制或旧块不属于受支持结构族";
        if ("satisfied".equals(c.status)) return "现有方块满足目标，保留";
        return "逐格重验；Forge 事件仅在实际操作时处理";
    }

    private static void refreshPlan(Session s) {
        s.ae.invalidate();
        s.replacementAuthorized = false;
        s.replacementReady = replacementStructureBuilt(s);
        s.job = 0;
        s.cursor = 0;
        s.completed = 0;
        resetJobCounts(s, false);
        s.recovered.clear();
        s.defer = false;
        s.stopTick = false;
        clearPending(s);
        s.status = "差分已更新，可查看材料、回收与保护原因";
        s.planRevision++;
        s.expectedBlocks.clear();
        s.expectedMeta.clear();
        s.expectedTiles.clear();
        s.expectedFacings.clear();
        s.planStatus.clear();
        s.planTargets.clear();
        s.planMissing = 0;
        s.planRecoveryFits = true;
        s.blueprintTotalResources = null;
        if (s.capture == null) return;
        World w = s.player.worldObj;
        if (s.virtual && !s.allocationsFrozen) allocateVirtualTargets(s);
        buildPreset(s);
        if (s.mode == 2) s.planFacings.clear();
        else refreshFacings(s);
        for (int i = 0; i < s.capture.cells.size(); i++) s.planTargets.add(draftTarget(s, s.capture.cells.get(i), i));
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            boolean loaded = w.blockExists(c.x, c.y, c.z);
            s.expectedBlocks.add(loaded ? w.getBlock(c.x, c.y, c.z) : Blocks.air);
            s.expectedMeta.add(loaded ? w.getBlockMetadata(c.x, c.y, c.z) : 0);
            TileEntity tile = loaded ? w.getTileEntity(c.x, c.y, c.z) : null;
            s.expectedTiles.add(tile);
            s.expectedFacings.add(
                tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getFrontFacing()
                    .ordinal() : -1);
            c.status = classify(s, c, i);
            s.planStatus.add(c.status);
        }
        refreshResources(s);
    }

    private static HologramBlueprintResources.Plan resourcePlan(Session s, HologramCapture.Cell cell)
        throws java.io.IOException {
        if (!s.virtual || s.virtualPreset == null || s.virtualPreset.schema < 2)
            return new HologramBlueprintResources.Plan();
        String fingerprint = cell.tileNbt + ":"
            + cell.itemNbt
            + ":"
            + s.virtualPreset.retainNbt
            + ":"
            + placementRetainCovers(s)
            + ":"
            + placementKeys(s)
            + ":"
            + cell.block
            + ":"
            + cell.meta
            + ":"
            + targetFor(s, cell, cellIndex(s, cell));
        if (!fingerprint.equals(cell.resourceFingerprint)) {
            cell.resourceFingerprint = fingerprint;
            cell.resourceError = "";
            cell.resourcePlan = null;
            try {
                NBTTagCompound tile = HologramBlueprintNbt.forPlacement(
                    cell.block,
                    cell.meta,
                    cell.tileNbt,
                    s.player.getUniqueID(),
                    s.player.getCommandSenderName(),
                    cell.x,
                    cell.y,
                    cell.z,
                    s.virtualPreset.retainNbt,
                    placementRetainCovers(s),
                    placementKeys(s));
                NBTTagCompound item = HologramBlueprintNbt.itemForPlacement(
                    cell.itemNbt,
                    s.player.getUniqueID(),
                    s.player.getCommandSenderName(),
                    s.virtualPreset.retainNbt,
                    placementRetainCovers(s),
                    placementKeys(s));
                if (HologramAeBlueprint.supports(cell.block, tile)) {
                    cell.resourcePlan = HologramAeBlueprint
                        .resourcePlan(cell.block, cell.meta, tile, targetFor(s, cell, cellIndex(s, cell)));
                } else {
                    cell.resourcePlan = HologramBlueprintResources.plan(tile)
                        .merge(HologramBlueprintResources.plan(item));
                    if (HologramNativePlacement.supported(cell.block)) {
                        if (!s.virtualPreset.retainNbt)
                            throw new java.io.IOException("Native composite tile requires retained NBT");
                        if (HologramNativePlacement.adapter(cell.block)
                            .includesInventoryDrops(cell.block))
                            cell.resourcePlan.removeItemsIncludedByNativeDrops(
                                HologramNativePlacement.materials(cell.block, cell.meta, tile));
                        cell.resourcePlan.addNativeItems(
                            HologramNativePlacement.additionalMaterials(
                                cell.block,
                                cell.meta,
                                tile,
                                targetFor(s, cell, cellIndex(s, cell))));
                    }
                }
            } catch (java.io.IOException invalid) {
                cell.resourceError = invalid.getMessage();
            }
        }
        if (!cell.resourceError.isEmpty()) throw new java.io.IOException(cell.resourceError);
        return cell.resourcePlan == null ? new HologramBlueprintResources.Plan() : cell.resourcePlan;
    }

    private static boolean resourcePlanValid(Session s, HologramCapture.Cell cell) {
        try {
            resourcePlan(s, cell);
            return true;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    private static List<ItemStack> coverCosts(Session s, HologramCapture.Cell cell) {
        if (!s.virtual || s.virtualPreset == null
            || s.virtualPreset.schema < 2
            || !placementRetainCovers(s)
            || cell.tileNbt.isEmpty()) return Collections.emptyList();
        if (!cell.tileNbt.equals(cell.coverCostsNbt)) {
            cell.coverCostsNbt = cell.tileNbt;
            cell.invalidCoverCosts = false;
            try {
                cell.coverMaterials = HologramBlueprintCovers.materials(cell.tileNbt);
            } catch (java.io.IOException invalid) {
                cell.coverMaterials = Collections.emptyList();
                cell.invalidCoverCosts = true;
            }
        }
        return cell.coverMaterials;
    }

    private static boolean coverCostsValid(Session s, HologramCapture.Cell cell) {
        coverCosts(s, cell);
        return !s.virtual || s.virtualPreset == null || !placementRetainCovers(s) || !cell.invalidCoverCosts;
    }

    private static void refreshResources(Session s) {
        s.planMissing = 0;
        s.planRecoveryFits = true;
        s.blueprintTotalResources = null;
        if (s.capture == null) return;
        World w = s.player.worldObj;
        Map<String, ItemStack> stacks = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        s.blueprintResources = new HologramBlueprintResources.Plan();
        s.blueprintResourceMissing = "";
        List<ItemStack> recovery = new ArrayList<>();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            if (!inScope(s, c, i) || !"pending".equals(c.status)) continue;
            ItemStack target = targetFor(s, c, i);
            if (s.mode != 2 && s.virtual && s.virtualPreset.schema >= 2) {
                try {
                    s.blueprintResources.merge(resourcePlan(s, c));
                } catch (java.io.IOException invalid) {
                    s.planRecoveryFits = false;
                    s.blueprintResourceMissing = invalid.getMessage();
                }
            }
            if (s.mode != 2 && orientationOnly(s, c, i)) continue;
            if (s.mode != 2 && !s.player.capabilities.isCreativeMode) for (ItemStack cover : coverCosts(s, c)) {
                String key = stackKey(cover);
                stacks.put(key, cover);
                counts.merge(key, 1, Integer::sum);
            }
            if (s.mode != 2 && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(target))) {
                String key = stackKey(target);
                stacks.put(key, target);
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            }
            if (!trivial(w, c.x, c.y, c.z)
                && (!s.player.capabilities.isCreativeMode || w.getTileEntity(c.x, c.y, c.z) != null)) try {
                    recovery.addAll(drops(w.getBlock(c.x, c.y, c.z), w, c, w.getBlockMetadata(c.x, c.y, c.z)));
                } catch (RuntimeException | LinkageError failure) {
                    s.planRecoveryFits = false;
                }
        }
        for (Map.Entry<String, ItemStack> entry : stacks.entrySet()) {
            ItemStack stack = entry.getValue();
            int available = materialSource(s).count(stack, Math.max(4096, s.capture.cells.size()));
            s.planMissing += Math.max(0, counts.get(entry.getKey()) - available);
        }
        if (s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && !s.player.capabilities.isCreativeMode) {
            try {
                HologramBlueprintResources.Plan total = new HologramBlueprintResources.Plan()
                    .merge(s.blueprintResources);
                List<ItemStack> base = new ArrayList<>();
                for (Map.Entry<String, ItemStack> entry : stacks.entrySet()) {
                    ItemStack stack = entry.getValue()
                        .copy();
                    stack.stackSize = counts.get(entry.getKey());
                    base.add(stack);
                }
                total.addItems(base);
                s.blueprintTotalResources = total;
                String missing = total.missing(materialSource(s));
                s.blueprintResourceMissing = missing == null ? "" : missing;
                if (missing != null) s.planMissing = Math.max(1, s.planMissing);
            } catch (java.io.IOException invalid) {
                s.planRecoveryFits = false;
                s.blueprintResourceMissing = invalid.getMessage();
            }
        }
        // Committed drops can remain in the player's persistent recovery queue when destinations are full.
    }

    private static String stackKey(ItemStack stack) {
        return stack.getItem() + ":" + stack.getItemDamage() + ":" + stack.getTagCompound();
    }

    private static boolean expectedCurrent(Session s, int index) {
        HologramCapture.Cell c = s.capture.cells.get(index);
        World w = s.player.worldObj;
        return w.blockExists(c.x, c.y, c.z) && w.getBlock(c.x, c.y, c.z) == s.expectedBlocks.get(index)
            && w.getBlockMetadata(c.x, c.y, c.z) == s.expectedMeta.get(index)
            && w.getTileEntity(c.x, c.y, c.z) == s.expectedTiles.get(index)
            && (!(s.expectedTiles.get(index) instanceof IGregTechTileEntity)
                || ((IGregTechTileEntity) s.expectedTiles.get(index)).getFrontFacing()
                    .ordinal() == s.expectedFacings.get(index));
    }

    private static boolean planCurrent(Session s) {
        if (s.capture == null || s.expectedBlocks.size() != s.capture.cells.size()) return false;
        for (int i = 0; i < s.capture.cells.size(); i++)
            if (inScope(s, s.capture.cells.get(i), i) && !expectedCurrent(s, i)) return false;
        return true;
    }

    private static boolean operate(Session s, HologramCapture.Cell c, int index) {
        try {
            return operateChecked(s, c, index);
        } catch (RuntimeException | LinkageError failure) {
            c.status = "unsupported";
            s.status = "设备状态或回收数据无法读取，该格未施工";
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean operateChecked(Session s, HologramCapture.Cell c, int index) {
        World w = s.player.worldObj;
        if (s.virtual && s.virtualPreset.schema >= 2
            && s.mode != 2
            && HologramNativePlacement.supported(c.block)
            && !resourcePlanValid(s, c)) {
            c.status = "unsupported";
            s.status = c.resourceError;
            return false;
        }
        if (!c.anchor && (s.virtual || !s.areaStructureId.isEmpty())
            && s.mode != 2
            && targetFor(s, c, index) != null
            && !acceptableSample(s, c, targetFor(s, c, index))) {
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
        if (s.mode == 1 && (!s.replacementAuthorized || !originalMember(s, c) || trivial(w, c.x, c.y, c.z))) {
            c.status = "protected";
            return true;
        }
        if (c.anchor || (w.getTileEntity(c.x, c.y, c.z) != null && !recoverable(s, c))) {
            c.status = "protected";
            return true;
        }
        if (!w.canMineBlock(s.player, c.x, c.y, c.z) || !s.player.canPlayerEdit(c.x, c.y, c.z, 1, trigger(s))) {
            c.status = "protected";
            return true;
        }
        boolean empty = trivial(w, c.x, c.y, c.z);
        if (s.mode == 2 && empty) {
            c.status = "satisfied";
            return true;
        }
        if (s.mode != 2 && targetSatisfied(s, c, index, old, oldMeta)) {
            c.status = "satisfied";
            return true;
        }
        if (s.mode == 0 && targetMatches(s, c, index, old, oldMeta)) {
            c.status = "satisfied";
            return true;
        }
        if (s.mode == 1 && orientationOnly(s, c, index)) {
            IGregTechTileEntity actual = (IGregTechTileEntity) w.getTileEntity(c.x, c.y, c.z);
            ForgeDirection before = actual.getFrontFacing();
            try {
                int requested = s.facingPins.get(position(c));
                boolean legal = false;
                for (int allowed : legalFacings(s, c, targetFor(s, c, index))) if (allowed == requested) legal = true;
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
        if (!empty && !removable(s, c)) {
            c.status = "protected";
            return true;
        }
        if (s.mode != 2 && ((c.unknown && !explicitTarget(s, c, index)
            && !s.autoTargets.containsKey(index)
            && !(!s.noHatches && s.channels.hasKey("gt_hatch", 3) && "hatch".equals(c.role)))
            || (c.element == null && !explicitTarget(s, c, index))
            || targetFor(s, c, index) == null)) {
            c.status = "unsupported";
            return true;
        }
        TileEntity originalTile = w.getTileEntity(c.x, c.y, c.z);
        List<ItemStack> drops = empty
            || (s.player.capabilities.isCreativeMode && w.getTileEntity(c.x, c.y, c.z) == null)
                ? Collections.emptyList()
                : drops(old, w, c, oldMeta);
        ItemStack[] inventory = copyInventory(s.player);
        NBTTagCompound savedTile = w.getTileEntity(c.x, c.y, c.z) == null ? null
            : (NBTTagCompound) drops.get(0)
                .getTagCompound()
                .getCompoundTag(HologramRecovery.TAG)
                .getCompoundTag("tile")
                .copy();
        BlockSnapshot snapshot = readOnlyBlockSnapshot(w, c.x, c.y, c.z);
        HologramMaterials.Transaction transaction = materialSource(s).begin();
        ItemStack reserved = null;
        boolean changedWorld = false;
        try {
            if (s.mode != 2 && (!empty || (s.virtual && s.virtualPreset.schema >= 2))
                && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(targetFor(s, c, index)))) {
                ItemStack required = targetFor(s, c, index);
                if (transaction.takeOne(required, false)) reserved = required.copy();
                if (reserved == null) {
                    rollbackMaterials(s, transaction, inventory);
                    c.status = "missing";
                    s.status = "替换材料不足，旧方块已保留";
                    return false;
                }
            }
            if (s.mode != 2 && !s.player.capabilities.isCreativeMode) for (ItemStack cover : coverCosts(s, c)) {
                if (!transaction.takeOne(cover, false)) {
                    rollbackMaterials(s, transaction, inventory);
                    c.status = "missing";
                    s.status = "覆盖板材料不足，该格未施工，已归还预留材料";
                    return false;
                }
            }
            if (!coverCostsValid(s, c)) throw new IllegalStateException("blueprint cover data invalid");
            if (s.virtual && s.virtualPreset.schema >= 2
                && !s.player.capabilities.isCreativeMode
                && !resourcePlan(s, c).reserve(transaction)) {
                rollbackMaterials(s, transaction, inventory);
                c.status = "missing";
                s.status = "蓝图库存、流体或无线电网资源不足；该格未施工，已归还全部预留资源";
                return false;
            }
            if (reserved != null && s.virtual && s.virtualPreset.schema >= 2 && !c.itemNbt.isEmpty())
                reserved.setTagCompound(
                    HologramBlueprintNbt.itemForPlacement(
                        c.itemNbt,
                        s.player.getUniqueID(),
                        s.player.getCommandSenderName(),
                        s.virtualPreset.retainNbt,
                        placementRetainCovers(s),
                        placementKeys(s)));
            if (!empty) {
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
                changedWorld = true;
                final ItemStack material = reserved;
                ReservedSource reservation = material == null ? null : new ReservedSource(material);
                IItemSource source = reservation == null ? transaction : reservation;
                // Lock the pure plan's chosen material. Native auto-selection must not consume a different item.
                ItemStack pin = targetFor(s, c, index);
                if (s.virtual && s.virtualPreset.schema >= 2 && !c.itemNbt.isEmpty()) {
                    pin = pin.copy();
                    pin.setTagCompound(
                        HologramBlueprintNbt.itemForPlacement(
                            c.itemNbt,
                            s.player.getUniqueID(),
                            s.player.getCommandSenderName(),
                            s.virtualPreset.retainNbt,
                            placementRetainCovers(s),
                            placementKeys(s)));
                }
                if (s.player.capabilities.isCreativeMode && !HologramRecovery.hasSeal(pin))
                    source = new CreativeSource(Collections.singletonList(pin));
                if (pin != null && material == null) source = new FilteredSource(source, pin);
                if (s.noHatches && !explicitTarget(s, c, index)) source = new ShellSource(source);
                boolean directCreative = s.player.capabilities.isCreativeMode && !s.noHatches && pin == null;
                boolean customBlock = explicitTarget(s, c, index) && pin != null
                    && !(pin.getItem() instanceof ItemMachines)
                    && acceptableSample(s, c, pin);
                PlaceResult result;
                boolean nativePlacement = s.virtual && s.virtualPreset.schema >= 2
                    && HologramNativePlacement.supported(c.block);
                if (nativePlacement) {
                    // The complete component plan was reserved above, including the primary material.
                    result = source.takeOne(pin, false) && w.setBlock(c.x, c.y, c.z, c.block, c.meta, 3)
                        ? PlaceResult.ACCEPT
                        : PlaceResult.REJECT;
                } else
                    if (directCreative) result = c.element.placeBlock(s.machine, w, c.x, c.y, c.z, elementTrigger(s, c))
                        ? PlaceResult.ACCEPT
                        : PlaceResult.REJECT;
                    else if (customBlock) {
                        result = StructureUtility.survivalPlaceBlock(
                            pin,
                            ItemStackPredicate.NBTMode.EXACT,
                            null,
                            false,
                            w,
                            c.x,
                            c.y,
                            c.z,
                            source,
                            s.player,
                            s.player::addChatMessage);
                    } else if ((explicitTarget(s, c, index) || s.autoTargets.containsKey(index))
                        && pin.getItem() instanceof ItemMachines
                        && acceptableSample(s, c, pin)) {
                            // Explicit overrides use the legal GT item directly, without structure candidate filters.
                            // Manual choices do not use the automatic atLeast fill quota.
                            result = StructureUtility.survivalPlaceBlock(
                                pin,
                                ItemStackPredicate.NBTMode.EXACT,
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
                                ? hatchElement(s, c, pin, false)
                                : c.element;
                            result = placement == null ? PlaceResult.REJECT
                                : placement.survivalPlaceBlock(
                                    s.machine,
                                    w,
                                    c.x,
                                    c.y,
                                    c.z,
                                    elementTrigger(s, c),
                                    AutoPlaceEnvironment.fromLegacy(source, s.player, s.player::addChatMessage));
                        }
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
                    && c.block != null
                    && w.getBlock(c.x, c.y, c.z) == c.block
                    && (result == PlaceResult.ACCEPT || result == PlaceResult.ACCEPT_STOP
                        || result == PlaceResult.SKIP))
                    w.setBlockMetadataWithNotify(c.x, c.y, c.z, c.meta, 2);
                if ((result != PlaceResult.ACCEPT && result != PlaceResult.ACCEPT_STOP && result != PlaceResult.SKIP)
                    || (!nativePlacement && w.isAirBlock(c.x, c.y, c.z))
                    || (nativePlacement
                        && (w.getBlock(c.x, c.y, c.z) != c.block || w.getBlockMetadata(c.x, c.y, c.z) != c.meta))
                    || (reservation != null && !reservation.used)
                    || (pin != null && !nativePlacement
                        && !targetMatches(s, c, index, w.getBlock(c.x, c.y, c.z), w.getBlockMetadata(c.x, c.y, c.z)))) {
                    HologramRecovery.restore(w, c.x, c.y, c.z, old, oldMeta, savedTile);
                    rollbackMaterials(s, transaction, inventory);
                    c.status = result == PlaceResult.REJECT_CONTINUE ? "unsupported" : "missing";
                    s.status = "原版元素拒绝施工；材料和旧方块已还原";
                    return false;
                }
                Block placed = w.getBlock(c.x, c.y, c.z);
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
                boolean blueprintTileDataApplied = false;
                if (direction != null && direction >= 0
                    && placedTile instanceof IGregTechTileEntity
                    && (!HologramRecovery.hasSeal(pin) || s.facingPins.containsKey(position(c)))) {
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
                boolean emptyAeNativeShell = nativePlacement && HologramAeBlueprint.supportsEmptyNativeShell(c.block);
                if (s.virtual && s.virtualPreset.schema >= 2
                    && !c.tileNbt.isEmpty()
                    && (s.virtualPreset.retainNbt || placementRetainCovers(s) || emptyAeNativeShell)) {
                    if (placedTile == null && !nativePlacement)
                        throw new IllegalStateException("blueprint tile missing");
                    NBTTagCompound data = HologramBlueprintNbt.forPlacement(
                        c.block,
                        c.meta,
                        c.tileNbt,
                        s.player.getUniqueID(),
                        s.player.getCommandSenderName(),
                        c.x,
                        c.y,
                        c.z,
                        s.virtualPreset.retainNbt,
                        placementRetainCovers(s),
                        placementKeys(s));
                    if (data != null) {
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
                        if (nativePlacement) placedTile = HologramNativePlacement
                            .install(w, c.x, c.y, c.z, c.block, c.meta, data, s.player);
                        else placedTile.readFromNBT(data);
                        blueprintTileDataApplied = true;
                        if (direction != null && direction >= 0 && placedTile instanceof IGregTechTileEntity) {
                            IGregTechTileEntity gt = (IGregTechTileEntity) placedTile;
                            gt.setFrontFacing(ForgeDirection.getOrientation(direction));
                            if (gt.getFrontFacing()
                                .ordinal() != direction) throw new IllegalStateException("blueprint front rejected");
                        }
                        placedTile.markDirty();
                        w.markBlockForUpdate(c.x, c.y, c.z);
                    }
                }
                if (s.virtual && s.virtualPreset.schema >= 2 && placedTile != null && !blueprintTileDataApplied) {
                    NBTTagCompound owned = HologramBlueprintOwnership.prepare(placedTile, null, s.player);
                    if (owned != null) {
                        placedTile.readFromNBT(owned);
                        placedTile.markDirty();
                        w.markBlockForUpdate(c.x, c.y, c.z);
                    }
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
                // Native multipart blocks report air until their complete tile is installed.
                // Verify semantic occupancy only after the audited factory and world-join path finish.
                if (nativePlacement && (!blueprintTileDataApplied || placedTile == null
                    || w.getBlock(c.x, c.y, c.z) != c.block
                    || w.getBlockMetadata(c.x, c.y, c.z) != c.meta
                    || w.getTileEntity(c.x, c.y, c.z) != placedTile
                    || w.isAirBlock(c.x, c.y, c.z)))
                    throw new IllegalStateException("Native composite installation is incomplete");
                if (MinecraftForge.EVENT_BUS.post(new BlockEvent.PlaceEvent(snapshot, old, s.player))) {
                    HologramRecovery.restore(w, c.x, c.y, c.z, old, oldMeta, savedTile);
                    rollbackMaterials(s, transaction, inventory);
                    c.status = "protected";
                    s.status = "领地/模组保护取消放置，该格已恢复，预留材料已归还";
                    return true;
                }
            }
            transaction.commit();
            s.player.inventory.markDirty();
            c.status = s.mode == 2 ? "removed" : empty ? "placed" : "replaced";
        } catch (java.io.IOException | RuntimeException | LinkageError e) {
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
        materialSource(s).recoverWorld(drops);
        for (ItemStack drop : drops) s.recovered.add(drop.copy());
        try {
            materialSource(s).flushRecovery();
        } catch (RuntimeException | LinkageError deliveryFailure) {
            s.status = "施工已完成；回收物保留在持久暂存，稍后继续交付";
        }
        return true;
    }

    private static boolean sameWorldCell(World world, HologramCapture.Cell cell, Block block, int meta,
        TileEntity tile) {
        return world.blockExists(cell.x, cell.y, cell.z) && world.getBlock(cell.x, cell.y, cell.z) == block
            && world.getBlockMetadata(cell.x, cell.y, cell.z) == meta
            && world.getTileEntity(cell.x, cell.y, cell.z) == tile;
    }

    private static List<ItemStack> drops(Block block, World world, HologramCapture.Cell cell, int meta) {
        if (world.getTileEntity(cell.x, cell.y, cell.z) != null)
            return HologramRecovery.drops(world, cell.x, cell.y, cell.z);
        if (block instanceof BlockFrameBox && !block.hasTileEntity(meta))
            return Collections.singletonList(((BlockFrameBox) block).getStackForm(1, meta));
        return block.getDrops(world, cell.x, cell.y, cell.z, meta, 0);
    }

    private static ItemStack[] copyInventory(EntityPlayerMP p) {
        ItemStack[] result = new ItemStack[p.inventory.mainInventory.length];
        for (int i = 0; i < result.length; i++)
            result[i] = p.inventory.mainInventory[i] == null ? null : p.inventory.mainInventory[i].copy();
        return result;
    }

    private static void restoreInventory(EntityPlayerMP p, ItemStack[] saved) {
        for (int i = 0; i < saved.length; i++) {
            ItemStack current = p.inventory.mainInventory[i];
            if (i == p.inventory.currentItem && current != null
                && saved[i] != null
                && current.getItem() == saved[i].getItem()
                && current.hasTagCompound()
                && saved[i].hasTagCompound()
                && current.getTagCompound()
                    .getString(TOOL_ID)
                    .equals(
                        saved[i].getTagCompound()
                            .getString(TOOL_ID))) {
                current.stackSize = saved[i].stackSize;
                current.setItemDamage(saved[i].getItemDamage());
                current.setTagCompound(
                    saved[i].hasTagCompound() ? (NBTTagCompound) saved[i].getTagCompound()
                        .copy() : null);
            } else if (i != p.inventory.currentItem)
                p.inventory.mainInventory[i] = saved[i] == null ? null : saved[i].copy();
        }
        p.inventory.markDirty();
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a != null && b != null
            && a.getItem() == b.getItem()
            && a.getItemDamage() == b.getItemDamage()
            && ItemStack.areItemStackTagsEqual(a, b);
    }

    private static boolean sameMaterial(ItemStack a, ItemStack b) {
        return a != null && b != null && a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    private static boolean canFit(EntityPlayerMP p, List<ItemStack> drops) {
        ItemStack[] simulation = copyInventory(p);
        for (ItemStack drop : drops) {
            int left = drop.stackSize;
            for (ItemStack slot : simulation) if (same(slot, drop)) {
                int n = Math
                    .min(left, Math.min(slot.getMaxStackSize(), p.inventory.getInventoryStackLimit()) - slot.stackSize);
                slot.stackSize += n;
                left -= n;
            }
            for (int i = 0; i < simulation.length && left > 0; i++) if (simulation[i] == null) {
                simulation[i] = drop.copy();
                simulation[i].stackSize = Math.min(left, drop.getMaxStackSize());
                left -= simulation[i].stackSize;
            }
            if (left > 0) return false;
        }
        return true;
    }

    private static ItemStack trigger(Session s) {
        ItemStack current = currentTool(s);
        ItemStack result = current == null ? new ItemStack(s.tool, 1) : current.copy();
        // Large editor drafts are tool storage, not native structure signals copied into every captured cell.
        if (result.hasTagCompound()) result.getTagCompound()
            .removeTag(DRAFT);
        result.stackSize = s.main;
        List<String> existing = new ArrayList<>();
        ChannelDataAccessor.iterateChannelData(result)
            .forEach(entry -> existing.add(entry.getKey()));
        for (String channel : existing) ChannelDataAccessor.unsetChannelData(result, channel);
        NBTTagCompound active = s.capabilities == null ? s.channels : s.capabilities.sanitizeChannels(s.channels);
        for (Object key : active.func_150296_c())
            ChannelDataAccessor.setChannelData(result, (String) key, active.getInteger((String) key));
        return result;
    }

    private static ItemStack currentTool(Session s) {
        ItemStack held = s.player.getHeldItem();
        return held != null && held.getItem() == s.tool
            && s.player.inventory.currentItem == s.slot
            && held.hasTagCompound()
            && s.toolId.equals(
                held.getTagCompound()
                    .getString(TOOL_ID)) ? held : null;
    }

    private static void persist(Session s) {
        ItemStack held = currentTool(s);
        if (held == null) return;
        HologramMaterials.write(held, s.materialSettings);
        held.getTagCompound()
            .setInteger("gtitHologramMain", s.main);
        held.getTagCompound()
            .setBoolean("gtitHologramMainExplicit", s.mainExplicit);
        held.getTagCompound()
            .setBoolean("gtitHologramDowngrade", s.hatchDowngrade);
        List<String> existing = new ArrayList<>();
        ChannelDataAccessor.iterateChannelData(held)
            .forEach(entry -> existing.add(entry.getKey()));
        for (String name : existing) ChannelDataAccessor.unsetChannelData(held, name);
        for (Object key : s.channels.func_150296_c())
            ChannelDataAccessor.setChannelData(held, (String) key, s.channels.getInteger((String) key));
        writeDraft(s, held);
        s.player.inventory.markDirty();
    }

    private static String controllerType(Session s) {
        if (s.target instanceof IGregTechTileEntity) return s.context.getClass()
            .getName() + ":" + ((IGregTechTileEntity) s.target).getMetaTileID();
        return s.target == null ? ""
            : s.target.getClass()
                .getName();
    }

    private static String selectedPreset(Session s) {
        ItemStack held = currentTool(s);
        return held == null ? ""
            : held.getTagCompound()
                .getString(SELECTED_PRESET);
    }

    private static boolean draftMatches(Session s, NBTTagCompound draft) {
        return !s.virtual && s.target != null
            && draft.getInteger("dimension") == s.dimension
            && draft.getInteger("x") == s.x
            && draft.getInteger("y") == s.y
            && draft.getInteger("z") == s.z
            && draft.getString("controllerType")
                .equals(controllerType(s));
    }

    private static String draftKey(Session s) {
        return legacyDraftKey(s) + ":" + s.dimension + ":" + s.x + ":" + s.y + ":" + s.z + ":" + controllerType(s);
    }

    private static String legacyDraftKey(Session s) {
        return s.player.getUniqueID() + ":" + s.toolId;
    }

    private static NBTTagCompound storedDraft(Session s, ItemStack held) {
        NBTTagCompound owned = HologramDraftStorage.get(s.player.worldObj)
            .read(draftKey(s));
        if (draftMatches(s, owned)) return owned;
        NBTTagCompound draft = held.getTagCompound()
            .getCompoundTag(DRAFT);
        if (!draft.getBoolean("serverStored")) return draft;
        String key = draft.getString("storageKey");
        return HologramDraftStorage.get(s.player.worldObj)
            .read(key.isEmpty() ? legacyDraftKey(s) : key);
    }

    private static void writeDraft(Session s, ItemStack held) {
        if (s.virtual || s.target == null || s.capture == null || s.capture.incomplete || s.capturePending) return;
        NBTTagCompound draft = new NBTTagCompound();
        draft.setInteger("dimension", s.dimension);
        draft.setInteger("x", s.x);
        draft.setInteger("y", s.y);
        draft.setInteger("z", s.z);
        draft.setString("controllerType", controllerType(s));
        draft.setString("blueprintPrompt", s.blueprintPrompt);
        draft.setString("blueprintSavedId", s.blueprintSavedId);
        NBTTagList annotations = new NBTTagList();
        for (HologramCapture.Cell cell : s.capture.cells) {
            if (!cell.excluded && cell.prompt.isEmpty()) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("position", new int[] { cell.x - s.x, cell.y - s.y, cell.z - s.z });
            row.setString("prompt", cell.prompt);
            row.setBoolean("excluded", cell.excluded);
            annotations.appendTag(row);
        }
        draft.setTag("blueprintAnnotations", annotations);
        NBTTagList hintAreas = new NBTTagList();
        for (HologramPresets.HintArea hint : s.blueprintHintAreas) {
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("bounds", new int[] { hint.x1, hint.y1, hint.z1, hint.x2, hint.y2, hint.z2 });
            row.setString("prompt", hint.prompt == null ? "" : hint.prompt);
            hintAreas.appendTag(row);
        }
        draft.setTag("blueprintHintAreas", hintAreas);
        draft.setInteger("main", s.main);
        draft.setBoolean("mainExplicit", s.mainExplicit);
        draft.setTag("channels", s.channels.copy());
        draft.setBoolean("noHatches", s.noHatches);
        draft.setInteger("tier", s.hatchTier);
        draft.setBoolean("downgrade", s.hatchDowngrade);
        draft.setBoolean("presetEnabled", s.presetEnabled);
        draft.setBoolean("presetPaused", s.presetPaused);
        draft.setBoolean("allocationsFrozen", s.allocationsFrozen);
        NBTTagList automatic = new NBTTagList();
        for (Map.Entry<String, ItemStack> entry : s.frozenAutoTargets.entrySet()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("position", entry.getKey());
            row.setTag(
                "target",
                entry.getValue()
                    .writeToNBT(new NBTTagCompound()));
            automatic.appendTag(row);
        }
        draft.setTag("automatic", automatic);
        draft.setInteger("mode", s.mode);
        draft.setInteger("scope", s.scope);
        draft.setBoolean("materialMain", s.materialSettings.main);
        draft.setBoolean("materialContainers", s.materialSettings.containers);
        draft.setBoolean("materialMe", s.materialSettings.me);
        draft.setInteger("materialPriority", s.materialSettings.priority);
        draft.setInteger("recoveryDestination", s.materialSettings.recoveryDestination);
        NBTTagList edits = new NBTTagList();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.anchor) continue;
            String key = position(cell);
            ItemStack target = s.pins.get(i);
            if (target == null && !s.facingPins.containsKey(key) && !s.suppressedAuto.contains(key)) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("dx", cell.x - s.x);
            entry.setInteger("dy", cell.y - s.y);
            entry.setInteger("dz", cell.z - s.z);
            if (target != null && !HologramRecovery.hasSeal(target)) {
                entry.setString("item", String.valueOf(Item.itemRegistry.getNameForObject(target.getItem())));
                entry.setInteger("damage", target.getItemDamage());
            }
            if (s.facingPins.containsKey(key)) entry.setInteger("facing", s.facingPins.get(key));
            entry.setBoolean("suppressed", s.suppressedAuto.contains(key));
            edits.appendTag(entry);
        }
        draft.setTag("edits", edits);
        HologramDraftStorage.get(s.player.worldObj)
            .put(draftKey(s), draft);
        if (!s.areaStructureId.isEmpty() || edits.tagCount() + automatic.tagCount() > 256
            || annotations.tagCount() > 0
            || hintAreas.tagCount() > 0
            || !s.blueprintPrompt.isEmpty()) {
            NBTTagCompound reference = new NBTTagCompound();
            reference.setBoolean("serverStored", true);
            reference.setString("storageKey", draftKey(s));
            reference.setInteger("dimension", s.dimension);
            reference.setInteger("x", s.x);
            reference.setInteger("y", s.y);
            reference.setInteger("z", s.z);
            reference.setString("controllerType", controllerType(s));
            held.getTagCompound()
                .setTag(DRAFT, reference);
        } else held.getTagCompound()
            .setTag(DRAFT, draft);
    }

    private static void readDraft(Session s) {
        ItemStack held = currentTool(s);
        if (held == null || !held.getTagCompound()
            .hasKey(DRAFT, 10)) return;
        NBTTagCompound draft = storedDraft(s, held);
        if (!draftMatches(s, draft)) return;
        s.blueprintPrompt = boundedPrompt(draft.getString("blueprintPrompt"));
        s.blueprintSavedId = draft.getString("blueprintSavedId");
        if (!s.blueprintSavedId.isEmpty() && !s.blueprintSavedId.matches("[a-zA-Z0-9_-]{1,64}"))
            s.blueprintSavedId = "";
        s.blueprintDraftAnnotations = draft.getTagList("blueprintAnnotations", 10);
        s.blueprintHintAreas.clear();
        NBTTagList hintAreas = draft.getTagList("blueprintHintAreas", 10);
        for (int i = 0; i < Math.min(hintAreas.tagCount(), 256); i++) {
            NBTTagCompound row = hintAreas.getCompoundTagAt(i);
            int[] bounds = row.getIntArray("bounds");
            if (bounds.length != 6 || bounds[0] > bounds[3] || bounds[1] > bounds[4] || bounds[2] > bounds[5]) continue;
            HologramPresets.HintArea hint = new HologramPresets.HintArea();
            hint.x1 = bounds[0];
            hint.y1 = bounds[1];
            hint.z1 = bounds[2];
            hint.x2 = bounds[3];
            hint.y2 = bounds[4];
            hint.z2 = bounds[5];
            hint.prompt = boundedPrompt(row.getString("prompt"));
            s.blueprintHintAreas.add(hint);
        }
        s.main = Math.max(1, draft.getInteger("main"));
        s.mainExplicit = draft.getBoolean("mainExplicit");
        s.channels = boundedChannels(draft.getCompoundTag("channels"));
        s.noHatches = draft.getBoolean("noHatches");
        s.hatchTier = clamp(draft.getInteger("tier"), 0, gregtech.api.enums.GTValues.VN.length - 1);
        s.hatchDowngrade = draft.getBoolean("downgrade");
        s.presetEnabled = draft.getBoolean("presetEnabled");
        s.presetPaused = draft.getBoolean("presetPaused");
        s.allocationsFrozen = draft.getBoolean("allocationsFrozen");
        s.frozenAutoTargets.clear();
        NBTTagList automatic = draft.getTagList("automatic", 10);
        for (int i = 0; i < Math.min(automatic.tagCount(), HologramCapture.LIMIT); i++) {
            NBTTagCompound row = automatic.getCompoundTagAt(i);
            String key = row.getString("position");
            if (key.length() > 48) continue;
            ItemStack sample = ItemStack.loadItemStackFromNBT(row.getCompoundTag("target"));
            if (sample != null && !HologramRecovery.hasSeal(sample))
                s.frozenAutoTargets.put(key, new ItemStack(sample.getItem(), 1, sample.getItemDamage()));
        }
        s.mode = clamp(draft.getInteger("mode"), 0, 2);
        s.scope = 0;
        s.materialSettings.main = draft.getBoolean("materialMain");
        s.materialSettings.containers = draft.getBoolean("materialContainers");
        s.materialSettings.me = draft.getBoolean("materialMe");
        s.materialSettings.priority = clamp(draft.getInteger("materialPriority"), 0, 2);
        s.materialSettings.recoveryDestination = clamp(draft.getInteger("recoveryDestination"), 0, 1);
        NBTTagList edits = draft.getTagList("edits", 10);
        for (int i = 0; i < Math.min(edits.tagCount(), HologramCapture.LIMIT); i++) {
            NBTTagCompound entry = edits.getCompoundTagAt(i);
            int dx = entry.getInteger("dx"), dy = entry.getInteger("dy"), dz = entry.getInteger("dz");
            if (!validRelativePosition(s, dx, dy, dz)) continue;
            HologramCapture.Cell cell = new HologramCapture.Cell(null, s.x + dx, s.y + dy, s.z + dz);
            if (dx == 0 && dy == 0 && dz == 0) continue;
            String key = position(cell);
            Object item = Item.itemRegistry.getObject(entry.getString("item"));
            ItemStack sample = item instanceof Item ? new ItemStack((Item) item, 1, entry.getInteger("damage")) : null;
            if (acceptableSample(s, cell, sample)) s.coordinatePins.put(key, sample);
            int direction = entry.getInteger("facing");
            if (entry.hasKey("facing", 3) && direction >= 0 && direction <= 5) s.facingPins.put(key, direction);
            if (entry.getBoolean("suppressed")) s.suppressedAuto.add(key);
        }
    }

    private static void restoreBlueprintAnnotations(Session s) {
        if (s.capture == null) return;
        for (int i = 0; i < s.blueprintDraftAnnotations.tagCount(); i++) {
            NBTTagCompound row = s.blueprintDraftAnnotations.getCompoundTagAt(i);
            int[] position = row.getIntArray("position");
            if (position.length != 3 || !validRelativePosition(s, position[0], position[1], position[2])) continue;
            HologramCapture.Cell cell = s.capture.positions
                .get((s.x + position[0]) + "," + (s.y + position[1]) + "," + (s.z + position[2]));
            if (cell == null) continue;
            cell.prompt = boundedPrompt(row.getString("prompt"));
            cell.excluded = row.getBoolean("excluded");
        }
        s.blueprintDraftAnnotations = new NBTTagList();
    }

    private static void resetDraft(Session s) {
        clearDraft(s);
        if (s.virtual) populateVirtual(s);
        else scan(s);
        s.status = "已恢复默认规划；现场方块、设备和库存保持现状";
    }

    private static void clearDraft(Session s) {
        s.calibratePending = false;
        s.buildAfterCapture = false;
        s.pins.clear();
        s.coordinatePins.clear();
        s.facingPins.clear();
        s.suppressedAuto.clear();
        s.autoTargets.clear();
        s.frozenAutoTargets.clear();
        s.allocationsFrozen = false;
        s.manualPositions.clear();
        s.undoEdits.clear();
        s.redoEdits.clear();
        s.presetEnabled = false;
        s.presetPaused = true;
        s.hatchDowngrade = false;
        s.hatchTier = 1;
        s.noHatches = false;
        s.main = 1;
        s.mainExplicit = false;
        s.channels = new NBTTagCompound();
        s.mode = 0;
        s.scope = 0;
        s.selected = -1;
        s.blueprintPrompt = "";
        s.blueprintHintAreas.clear();
        s.blueprintDraftAnnotations = new NBTTagList();
    }

    private static boolean structureBuilt(Session s) {
        return !s.virtual && s.machine != null && (s.machine.mMachine || replacementStructureBuilt(s));
    }

    /** Verify the original declaration without invoking a mutating machine check. */
    private static boolean replacementStructureBuilt(Session s) {
        if (s.virtual || s.machine == null || s.target == null) return false;
        HologramCapture original = s.capture;
        if (original == null || original.pending || original.incomplete) return false;
        boolean found = false;
        for (HologramCapture.Cell cell : original.cells) {
            if (cell.anchor || cell.userPosition) continue;
            if (!s.player.worldObj.blockExists(cell.x, cell.y, cell.z)) return false;
            Block block = s.player.worldObj.getBlock(cell.x, cell.y, cell.z);
            TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            if (tile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEHatch) {
                if (hatchElement(
                    s,
                    cell,
                    new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) tile).getMetaTileID()),
                    true) == null) return false;
            } else {
                if (cell.unknown || cell.element == null || cell.block == null) return false;
                if (cell.block == Blocks.air) {
                    if (!trivial(s.player.worldObj, cell.x, cell.y, cell.z)) return false;
                    continue;
                }
                int meta = s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z);
                if (tile != null && !HologramFrameSupport.acceptsCovered(cell.element, block, meta)) return false;
                if (!knownSatisfied(cell, block, meta) && !destructive(cell, block, meta)) return false;
            }
            found = true;
        }
        return found;
    }

    private static boolean originalMember(Session s, HologramCapture.Cell c) {
        if (c.anchor || c.userPosition || c.element == null) return false;
        HologramCapture original = s.capture;
        if (original == null) return false;
        World world = s.player.worldObj;
        TileEntity tile = world.getTileEntity(c.x, c.y, c.z);
        if (tile instanceof IGregTechTileEntity) {
            if (world.getBlock(c.x, c.y, c.z) instanceof BlockFrameBox) return HologramFrameSupport
                .acceptsCovered(c.element, world.getBlock(c.x, c.y, c.z), world.getBlockMetadata(c.x, c.y, c.z));
            return ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEHatch && hatchElement(
                s,
                c,
                new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) tile).getMetaTileID()),
                true) != null;
        }
        if (c.block == null || c.block == Blocks.air || c.unknown) return false;
        return tile == null && destructive(c, world.getBlock(c.x, c.y, c.z), world.getBlockMetadata(c.x, c.y, c.z));
    }

    private static boolean constructionAllowed(Session s) {
        if (blockedByAreaImport(s)) return false;
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
            if (inScope(s, cell, i) && "blocked".equals(classify(s, cell, i))) blocked++;
        }
        if (blocked == 0) return true;
        s.status = "选定范围有 " + blocked + " 格结构占位被遮挡，已整体拒绝补建，请清空后重试";
        return false;
    }

    private static boolean replacementSnapshotCurrent(Session s) {
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

    private static void calibrateCurrent(Session s) {
        if (!structureBuilt(s)) {
            s.status = "原机尚未报告结构成型，请恢复默认或等待原机检查";
            return;
        }
        if (s.machine instanceof MTEForgeOfGods) {
            s.main = clamp(
                ((MTEForgeOfGods) s.machine).getData()
                    .getRingAmount(),
                1,
                3);
            s.mainExplicit = true;
        }
        s.calibratePending = true;
        scan(s);
    }

    private static void applyCurrentCalibration(Session s) {
        int main = s.main;
        boolean mainExplicit = s.mainExplicit;
        NBTTagCompound channels = (NBTTagCompound) s.channels.copy();
        clearDraft(s);
        s.main = main;
        s.mainExplicit = mainExplicit;
        s.channels = channels;
        s.channels.removeTag("gt_hatch");
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.anchor || !s.player.worldObj.blockExists(cell.x, cell.y, cell.z)) continue;
            TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            Block block = s.player.worldObj.getBlock(cell.x, cell.y, cell.z);
            if (block == Blocks.air) continue;
            ItemStack sample = tile instanceof IGregTechTileEntity
                ? new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) tile).getMetaTileID())
                : new ItemStack(block, 1, block.getDamageValue(s.player.worldObj, cell.x, cell.y, cell.z));
            if (!acceptableSample(s, cell, sample)) continue;
            if (tile instanceof IGregTechTileEntity) {
                if (hatchElement(s, cell, sample, true) == null) continue;
            } else if (!destructive(cell, block, s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z))
                && !knownSatisfied(cell, block, s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z))) continue;
            s.pins.put(i, sample);
            s.coordinatePins.put(position(cell), sample.copy());
            if (tile instanceof IGregTechTileEntity) {
                int facing = ((IGregTechTileEntity) tile).getFrontFacing()
                    .ordinal();
                for (int legal : legalFacings(s, cell, sample))
                    if (legal == facing) s.facingPins.put(position(cell), facing);
            }
        }
        calibrateMaterialChannels(s.capture, s.capabilities.toNBT(), s.channels, s.pins, s.coordinatePins);
        // Reconstruct with the inferred signals; otherwise the captured default block still reflects the old trigger.
        scan(s);
        s.status = s.capturePending ? "现场材质信道已识别，正在重新采集校准后的结构"
            : s.capture.incomplete ? "现场材质已识别，但重新采集不完整，高级施工已阻止"
                : "已校准现场材质信道与独立仓室；" + (s.machine instanceof MTEForgeOfGods ? "环数已按原机识别" : "无法可靠反推的主信号、尺寸及原始信道保留当前值");
    }

    /** Only uniform, source-reviewed material options can establish a reliable named signal. */
    static void calibrateMaterialChannels(HologramCapture capture, NBTTagList rows, NBTTagCompound channels,
        Map<Integer, ItemStack> pins, Map<String, ItemStack> coordinatePins) {
        for (int r = 0; r < rows.tagCount(); r++) {
            NBTTagCompound row = rows.getCompoundTagAt(r);
            String kind = row.getString("kind");
            if (!"material".equals(kind) && !"coil".equals(kind)) continue;
            NBTTagList options = row.getTagList("options", 10);
            int uniform = 0;
            boolean invalid = false;
            List<Integer> governed = new ArrayList<>();
            for (int i = 0; i < capture.cells.size(); i++) {
                HologramCapture.Cell cell = capture.cells.get(i);
                // ofCoil (including activeCoils wrappers) enumerates only the old trigger's tier.
                // Its source-reviewed replacement family identifies the controlled cells independently.
                boolean relevant = "coil".equals(kind) && "coil".equals(cell.family);
                int observed = 0;
                ItemStack actual = coordinatePins.get(position(cell));
                for (int o = 0; o < options.tagCount(); o++) {
                    NBTTagCompound option = options.getCompoundTagAt(o);
                    ItemStack sample = ItemStack.loadItemStackFromNBT(option.getCompoundTag("item"));
                    if (sample == null) continue;
                    if (cell.candidates != null)
                        for (ItemStack candidate : cell.candidates) if (same(candidate, sample)) relevant = true;
                    if (same(actual, sample) || "coil".equals(kind) && relevant && sameCoilLevel(actual, sample)) {
                        int value = option.getInteger("value");
                        if (observed != 0 && observed != value) invalid = true;
                        observed = value;
                    }
                }
                if (!relevant) continue;
                governed.add(i);
                if (observed == 0 || (uniform != 0 && uniform != observed)) {
                    invalid = true;
                    break;
                }
                uniform = observed;
            }
            if (!governed.isEmpty()) channels.removeTag(row.getString("id"));
            if (!invalid && uniform > 0) {
                channels.setInteger(row.getString("id"), uniform);
                for (int index : governed) {
                    pins.remove(index);
                    coordinatePins.remove(position(capture.cells.get(index)));
                }
            }
        }
    }

    private static boolean sameCoilLevel(ItemStack actual, ItemStack sample) {
        if (actual == null || sample == null) return false;
        Block installed = Block.getBlockFromItem(actual.getItem());
        Block option = Block.getBlockFromItem(sample.getItem());
        if (!(installed instanceof IHeatingCoil) || !(option instanceof IHeatingCoil)) return false;
        HeatingCoilLevel level = ((IHeatingCoil) installed).getCoilHeat(actual.getItemDamage());
        return level != HeatingCoilLevel.None && level == ((IHeatingCoil) option).getCoilHeat(sample.getItemDamage());
    }

    private static void applySettings(Session s, NBTTagCompound action) {
        if (action.hasKey("materialMain")) s.materialSettings.main = action.getBoolean("materialMain");
        if (action.hasKey("materialContainers"))
            s.materialSettings.containers = action.getBoolean("materialContainers");
        if (action.hasKey("materialMe")) s.materialSettings.me = action.getBoolean("materialMe");
        if (action.hasKey("materialPriority"))
            s.materialSettings.priority = clamp(action.getInteger("materialPriority"), 0, 2);
        if (action.hasKey("hatchDowngrade")) s.hatchDowngrade = action.getBoolean("hatchDowngrade");
        if (action.hasKey("recoveryDestination"))
            s.materialSettings.recoveryDestination = clamp(action.getInteger("recoveryDestination"), 0, 1);
        if (action.hasKey("selectedPresetId")) try {
            String id = action.getString("selectedPresetId");
            if (!id.isEmpty() && HologramPresets.get(id) == null) throw new java.io.IOException("预设不存在");
            boolean changed = !id.equals(selectedPreset(s));
            currentTool(s).getTagCompound()
                .setString(SELECTED_PRESET, id);
            if (changed && !id.isEmpty()) presetGuidance(s, "蓝图已选定；点击放置进入位置预览，或打开蓝图继续编辑。");
        } catch (java.io.IOException invalid) {
            s.status = "预设选择未保存：" + invalid.getMessage();
            return;
        }
        ItemStack tool = currentTool(s);
        if (tool != null && tool.hasTagCompound()
            && tool.getTagCompound()
                .hasKey(DRAFT, 10)) {
            NBTTagCompound draft = storedDraft(s, tool);
            updateDraftSettings(draft, s.materialSettings, s.hatchDowngrade);
            if (draft.hasKey("controllerType", 8)) HologramDraftStorage.get(s.player.worldObj)
                .put(
                    legacyDraftKey(s) + ":"
                        + draft.getInteger("dimension")
                        + ":"
                        + draft.getInteger("x")
                        + ":"
                        + draft.getInteger("y")
                        + ":"
                        + draft.getInteger("z")
                        + ":"
                        + draft.getString("controllerType"),
                    draft);
        }
        s.materialContext = new HologramMaterials.Context(s.player, s.materialSettings);
        if (s.capture != null) refreshPlan(s);
        s.status = "设置已保存到当前投影仪";
    }

    /** Global settings also update a detached controller draft, so reopening it cannot undo the user's switches. */
    static void updateDraftSettings(NBTTagCompound draft, HologramMaterials.Settings settings, boolean downgrade) {
        draft.setBoolean("materialMain", settings.main);
        draft.setBoolean("materialContainers", settings.containers);
        draft.setBoolean("materialMe", settings.me);
        draft.setInteger("materialPriority", settings.priority);
        draft.setInteger("recoveryDestination", settings.recoveryDestination);
        draft.setBoolean("downgrade", downgrade);
    }

    private static HologramPresets.Structure copyStructure(HologramPresets.Structure original) {
        HologramPresets.Structure copy = new HologramPresets.Structure();
        copy.id = original.id;
        copy.name = original.name;
        copy.sourceMod = original.sourceMod;
        copy.sourceClass = original.sourceClass;
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

    private static HologramCapture.Cell blueprintCell(HologramCapture.Cell old, int x, int y, int z) {
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
        cell.coverCostsNbt = old.coverCostsNbt;
        cell.coverMaterials = old.coverMaterials;
        cell.invalidCoverCosts = old.invalidCoverCosts;
        cell.actualFacing = old.actualFacing;
        cell.actualStack = old.actualStack == null ? null : old.actualStack.copy();
        cell.structureTargets = old.structureTargets == null ? null : new ArrayList<>(old.structureTargets);
        return cell;
    }

    private static int[] editIndices(Session s, NBTTagCompound action) {
        int[] indices = action.getIntArray("indices");
        if (indices.length == 0) indices = action.getIntArray("selectedIndices");
        if (indices.length == 0)
            indices = new int[] { action.hasKey("selected") ? action.getInteger("selected") : s.selected };
        return java.util.Arrays.stream(indices)
            .filter(index -> index >= 0 && s.capture != null && index < s.capture.cells.size())
            .filter(index -> editableStructureCell(s, s.capture.cells.get(index)))
            .distinct()
            .toArray();
    }

    private static boolean editableStructureCell(Session s, HologramCapture.Cell cell) {
        String active = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
        return active.isEmpty() || cell.memberships()
            .isEmpty() || cell.belongsTo(active);
    }

    /** A shared cell may follow a complete member structure; its other memberships remain at the source. */
    private static java.util.Set<String> completeGeometrySelection(Session s, int[] indices)
        throws java.io.IOException {
        if (s.virtualPreset.structures.isEmpty() && s.capture.cells.stream()
            .anyMatch(cell -> "templateController".equals(cell.role)))
            throw new java.io.IOException("模板结构身份缺失，请重新加载蓝图后编辑");
        java.util.Set<Integer> selected = new java.util.HashSet<>();
        for (int index : indices) selected.add(index);
        java.util.Set<String> complete = new java.util.HashSet<>();
        for (HologramPresets.Structure structure : s.virtualPreset.structures) {
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

    private static int[] geometryIndices(Session s, NBTTagCompound action) throws java.io.IOException {
        int[] raw = action.getIntArray("indices");
        if (raw.length == 0) raw = action.getIntArray("selectedIndices");
        if (raw.length == 0)
            raw = new int[] { action.hasKey("selected", 3) ? action.getInteger("selected") : s.selected };
        if (raw.length > HologramCapture.LIMIT) throw new java.io.IOException("选择数量超过蓝图上限");
        for (int index : raw)
            if (index < 0 || index >= s.capture.cells.size() || !editableStructureCell(s, s.capture.cells.get(index)))
                throw new java.io.IOException("选择位置或结构已变化，请重新选择");
        return java.util.Arrays.stream(raw)
            .distinct()
            .toArray();
    }

    private static void blueprintClipboard(Session s, NBTTagCompound action, String op) throws java.io.IOException {
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
                HologramCapture.Cell copy = blueprintCell(source, source.x - minX, source.y - minY, source.z - minZ);
                ItemStack target = targetFor(s, source, index);
                if (HologramRecovery.hasSeal(target)) throw new java.io.IOException("封存设备不能复制真实库存");
                copy.actualStack = target == null ? null : target.copy();
                copy.actualFacing = s.facingPins.getOrDefault(position(source), s.planFacings.getOrDefault(index, -1));
                clipboard.add(copy);
            }
            s.blueprintClipboard.clear();
            s.blueprintClipboard.addAll(clipboard);
            if ("editCut".equals(op)) {
                EditState before = new EditState(s);
                for (int index : indices) s.capture.cells.get(index).excluded = true;
                remember(s.undoEdits, before);
                s.redoEdits.clear();
                refreshPlan(s);
            }
            s.status = "editCut".equals(op) ? "已剪切蓝图规划，原位置标为排除；世界保持原状" : "已复制选中蓝图格及设备配置";
        } else {
            int index = action.hasKey("selected") ? action.getInteger("selected") : s.selected;
            if (index < 0 || index >= s.capture.cells.size() || s.blueprintClipboard.isEmpty())
                throw new java.io.IOException("请先复制并选择粘贴锚点");
            HologramCapture.Cell anchor = s.capture.cells.get(index);
            List<HologramCapture.Cell> copies = new ArrayList<>();
            for (HologramCapture.Cell source : s.blueprintClipboard) {
                HologramCapture.Cell cell = blueprintCell(
                    source,
                    anchor.x + source.x,
                    anchor.y + source.y,
                    anchor.z + source.z);
                validateBlueprintCoordinate(s, cell.x, cell.y, cell.z);
                copies.add(cell);
            }
            stageGeometry(s, copies, java.util.Collections.emptySet(), true);
            s.status = "已粘贴蓝图规划；实际放置才扣除材料";
        }
    }

    private static void moveBlueprintCells(Session s, NBTTagCompound action) throws java.io.IOException {
        if (!s.virtual || s.capture == null || s.virtualPreset == null || s.virtualPreset.schema < 2)
            throw new java.io.IOException("坐标移动用于区域与蓝图编辑");
        if (s.capturePending || s.capture.pending
            || s.capture.incomplete
            || s.job == 1
            || s.job == 2
            || !s.player.capabilities.allowEdit) throw new java.io.IOException("请等待完整采集并结束施工后编辑蓝图");
        if ((!action.hasKey("dx", 3) && !action.hasKey("dy", 3) && !action.hasKey("dz", 3))
            || (action.hasKey("dx") && !action.hasKey("dx", 3))
            || (action.hasKey("dy") && !action.hasKey("dy", 3))
            || (action.hasKey("dz") && !action.hasKey("dz", 3))) throw new java.io.IOException("移动坐标无效");
        int[] indices = geometryIndices(s, action);
        if (indices.length == 0) throw new java.io.IOException("请先选择蓝图格");
        java.util.Set<String> complete = completeGeometrySelection(s, indices);
        if (action.getInteger("dx") == 0 && action.getInteger("dy") == 0 && action.getInteger("dz") == 0) return;
        java.util.Set<String> remove = new java.util.HashSet<>();
        Map<String, HologramCapture.Cell> retained = new HashMap<>();
        List<HologramCapture.Cell> copies = new ArrayList<>();
        for (int index : indices) {
            HologramCapture.Cell source = s.capture.cells.get(index);
            long x = (long) source.x + action.getInteger("dx"), y = (long) source.y + action.getInteger("dy"),
                z = (long) source.z + action.getInteger("dz");
            validateBlueprintCoordinate(s, x, y, z);
            HologramCapture.Cell copy = blueprintCell(source, (int) x, (int) y, (int) z);
            setMemberships(
                copy,
                source.memberships()
                    .stream()
                    .filter(complete::contains)
                    .collect(java.util.stream.Collectors.toList()));
            ItemStack target = targetFor(s, source, index);
            if (action.getBoolean("copy") && HologramRecovery.hasSeal(target))
                throw new java.io.IOException("封存设备不能复制真实库存");
            copy.actualStack = target == null ? null : target.copy();
            copy.actualFacing = s.facingPins.getOrDefault(position(source), s.planFacings.getOrDefault(index, -1));
            copies.add(copy);
            if (!action.getBoolean("copy")) {
                List<String> remaining = source.memberships()
                    .stream()
                    .filter(id -> !complete.contains(id))
                    .collect(java.util.stream.Collectors.toList());
                if (remaining.isEmpty()) remove.add(position(source));
                else {
                    HologramCapture.Cell stay = blueprintCell(source, source.x, source.y, source.z);
                    setMemberships(stay, remaining);
                    retained.put(position(source), stay);
                }
            }
        }
        stageGeometry(s, copies, remove, false, retained, action.getBoolean("copy"));
        s.status = action.getBoolean("copy") ? "已复制选中蓝图格到新坐标" : "已移动选中蓝图格；世界保持原状";
    }

    private static void validateBlueprintCoordinate(Session s, long x, long y, long z) throws java.io.IOException {
        if (x < -30000000L || x >= 30000000L
            || z < -30000000L
            || z >= 30000000L
            || y < 0
            || y >= s.player.worldObj.getHeight()) throw new java.io.IOException("目标坐标超出世界边界");
    }

    /** Validate the complete geometry before changing any server-owned coordinate map. */
    private static void stageGeometry(Session s, List<HologramCapture.Cell> copies, java.util.Set<String> remove,
        boolean overlay) throws java.io.IOException {
        stageGeometry(s, copies, remove, overlay, java.util.Collections.emptyMap(), remove.isEmpty());
    }

    private static void stageGeometry(Session s, List<HologramCapture.Cell> copies, java.util.Set<String> remove,
        boolean overlay, Map<String, HologramCapture.Cell> retained, boolean copyGroups) throws java.io.IOException {
        Map<String, HologramCapture.Cell> staged = new LinkedHashMap<>();
        Map<String, ItemStack> targets = new HashMap<>();
        Map<String, Integer> facings = new HashMap<>(s.facingPins);
        for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            if (remove.contains(position(cell))) continue;
            staged.put(position(cell), retained.getOrDefault(position(cell), cell));
            ItemStack target = targetFor(s, cell, index);
            if (target != null) targets.put(position(cell), target.copy());
        }
        List<HologramPresets.Structure> copiedStructures = new ArrayList<>();
        Map<String, String> groupIds = new HashMap<>();
        if (copyGroups) for (HologramCapture.Cell copy : copies) {
            if (!"templateController".equals(copy.role) || copy.structureId.isEmpty()) continue;
            for (HologramPresets.Structure original : s.virtualPreset.structures)
                if (original.id.equals(copy.structureId)) {
                    HologramPresets.Structure duplicate = copyStructure(original);
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
        for (HologramCapture.Cell copy : copies) {
            String key = position(copy);
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
            setMemberships(copy, rewritten);
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
                size = HologramBlueprintNbt.expandedBytes(value);
                sizes.put(value, size);
            }
            expanded += size;
            if (expanded > 64L * 1024 * 1024) throw new java.io.IOException("复制后的设备NBT超过64MiB上限");
        }
        remember(s.undoEdits, new EditState(s));
        s.redoEdits.clear();
        s.virtualPreset.structures.addAll(copiedStructures);
        if (!copyGroups) for (HologramCapture.Cell copy : copies) if ("templateController".equals(copy.role))
            for (HologramPresets.Structure structure : s.virtualPreset.structures)
                if (structure.id.equals(copy.structureId)) {
                    structure.x = copy.x - s.x;
                    structure.y = copy.y - s.y;
                    structure.z = copy.z - s.z;
                }
        HologramCapture capture = new HologramCapture(null, trigger(s));
        capture.cells.addAll(staged.values());
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
            String key = position(cell);
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
        refreshPlan(s);
    }

    /** Dedicated mark-only actions are dispatched before material flush/recovery and ordinary cell selection. */
    private static boolean aeAction(Session s, NBTTagCompound action, String op) {
        if (!("aePreviewOpen".equals(op) || "aePreviewEdit".equals(op) || "aePreviewClose".equals(op))) return false;
        long seq = action.getLong("editSeq");
        EditState rollback = null;
        long originalRevision = s.planRevision;
        HologramAeProtocol.Preview originalPreview = s.ae.preview;
        HologramAeProtocol.Review originalReview = s.ae.review;
        try {
            if ("aePreviewClose".equals(op)) {
                if (s.ae.preview != null && s.ae.preview.token.equals(action.getString("token"))) s.ae.preview = null;
                return true;
            }
            if (!s.virtual || s.virtualPreset == null
                || s.capture == null
                || s.capture.incomplete
                || s.capturePending
                || s.capture.pending
                || s.job == 1
                || s.job == 2
                || s.job == 4) throw new java.io.IOException("AE preview requires a complete editable blueprint");
            if (!action.hasKey("x", 3) || !action.hasKey("y", 3)
                || !action.hasKey("z", 3)
                || !action.hasKey("structureId", 8)) throw new java.io.IOException("AE preview coordinate is missing");
            String active = s.blueprintActiveStructure;
            if (!active.equals(action.getString("structureId")))
                throw new java.io.IOException("AE preview structure changed");
            HologramCapture.Cell cell = s.capture.positions
                .get(action.getInteger("x") + "," + action.getInteger("y") + "," + action.getInteger("z"));
            if (cell == null || !editableStructureCell(s, cell)
                || !membershipEnabled(s, cell)
                || cell.excluded
                || !HologramAePolicy.handles(cell.block, cell.tileNbt))
                throw new java.io.IOException("AE preview cell is not editable");
            int index = cellIndex(s, cell);
            ItemStack target = targetFor(s, cell, index);
            // The server draft's independent shell identity authorizes editing, even while its contents block
            // placement.
            if (target == null
                ? cell.blueprintBlock.isEmpty()
                    || !cell.blueprintBlock.equals(String.valueOf(Block.blockRegistry.getNameForObject(cell.block)))
                : (!cell.blueprintItem.isEmpty()
                    ? !cell.blueprintItem.equals(String.valueOf(Item.itemRegistry.getNameForObject(target.getItem())))
                        || cell.blueprintDamage != target.getItemDamage()
                    : Block.getBlockFromItem(target.getItem()) != cell.block))
                throw new java.io.IOException("AE target block family changed");
            HologramAeProtocol.Preview preview = s.ae.preview;
            if ("aePreviewEdit".equals(op) && preview != null
                && preview.current(
                    s.id,
                    s.toolId,
                    active,
                    s.planRevision,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.block,
                    cell.meta,
                    cell.tileNbt)
                && preview.replay(action)) {
                s.ae.ack = (NBTTagCompound) preview.lastAck.copy();
                return true;
            }
            if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision)
                throw new java.io.IOException("AE preview revision changed");
            HologramAeBlueprint.Analysis analysis = HologramAeBlueprint
                .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
            if ("aePreviewOpen".equals(op)) {
                s.ae.preview = new HologramAeProtocol.Preview(
                    s.id,
                    s.toolId,
                    active,
                    s.planRevision,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.block,
                    cell.meta,
                    cell.tileNbt);
                s.ae.ack = null;
                return true;
            }
            HologramAeProtocol.validateEdit(action, s.ae, s.player.worldObj.getTotalWorldTime());
            if (preview == null || !preview.token.equals(action.getString("token"))
                || !preview.current(
                    s.id,
                    s.toolId,
                    active,
                    s.planRevision,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.block,
                    cell.meta,
                    cell.tileNbt)
                || seq <= preview.lastSeq) throw new java.io.IOException("AE preview token/sequence changed");
            HologramAeBlueprint.Slot slot = null;
            for (HologramAeBlueprint.Slot candidate : analysis.slots)
                if (candidate.id.equals(action.getString("slotId"))) slot = candidate;
            if (slot == null || ("SET".equals(action.getString("mutation")) && !slot.enabled))
                throw new java.io.IOException("AE slot is invalid or disabled");
            ItemStack marked = HologramAeProtocol.candidate(action, slot);
            HologramAeBlueprint.Analysis updated = HologramAeBlueprint
                .updateSlot(cell.block, cell.meta, analysis.payload(), slot.id, marked);
            boolean rebindShell = marked == null && "readonly".equals(slot.kind)
                && (slot.id.startsWith("def:") || slot.id.matches("@part=\\d+/def:.*"));
            ItemStack remainingShell = rebindShell
                ? HologramAeBlueprint.sampleIdentity(cell.block, cell.meta, updated.payload())
                : null;
            if (rebindShell && remainingShell == null
                && cell.block.getClass()
                    .getName()
                    .equals("codechicken.multipart.BlockMultipart")
                && HologramAeBlueprint.withoutAeMultipart(updated.payload())
                    .getTagList("parts", 10)
                    .tagCount() > 0)
                throw new java.io.IOException(
                    "Remaining non-AE multipart identity cannot be rebound safely; replace this draft cell explicitly");
            List<HologramAePolicy.Issue> nextIssues = HologramAePolicy.resolveAfterMutation(
                s.virtualPreset.aeIssues,
                cell.x - s.x,
                cell.y - s.y,
                cell.z - s.z,
                slot.id,
                analysis,
                updated);
            String payload = HologramBlueprintNbt.encodeCanonical(cell.block, updated.payload());
            // Serialize/validate everything before replacing the single server-owned draft payload.
            HologramAeProtocol.bytes(updated.payload(), HologramAeProtocol.MAX_STACK_AGGREGATE);
            preview.snapshot(updated);
            EditState before = new EditState(s);
            rollback = before;
            cell.tileNbt = payload == null ? "" : payload;
            cell.itemNbt = "";
            cell.resourceFingerprint = "";
            if (rebindShell) rebindAeShell(s, cell, index, remainingShell);
            s.virtualPreset.aeIssues = nextIssues;
            s.virtualEdited.add(position(cell));
            refreshPlan(s);
            s.ae.preview = rebindShell && remainingShell == null ? null : preview;
            s.ae.result(seq, true, "ae.edit.accepted", s.planRevision);
            preview.remember(action, s.ae.ack, s.planRevision, cell.tileNbt);
            remember(s.undoEdits, before);
            s.redoEdits.clear();
            rollback = null;
            s.forcePlacementReady = false;
            s.placementReviewNonce++;
            s.status = "AE 蓝图标记已更新；未移动真实物品，材料仅在施工时扣除。请重新核对材料与保存报告。";
        } catch (java.io.IOException | RuntimeException invalid) {
            if (rollback != null) {
                rollback.apply(s);
                try {
                    refreshPlan(s);
                } catch (RuntimeException failedRollbackPlanning) {
                    // Original draft is restored; incomplete planning stays blocked until an explicit rescan.
                    s.capture.incomplete = true;
                }
                s.planRevision = originalRevision;
                s.ae.preview = originalPreview;
                s.ae.review = originalReview;
            }
            if ("aePreviewEdit".equals(op)) s.ae.result(seq, false, "ae.edit.rejected", s.planRevision);
            s.status = "AE 蓝图操作被拒绝：" + HologramAePolicy.fallback(invalid.getMessage()) + "；原标记保留";
        }
        return true;
    }

    /** Rebind only a server-derived surviving native shell; never reinterpret a part item as a block. */
    private static void rebindAeShell(Session s, HologramCapture.Cell cell, int index, ItemStack sample) {
        String key = position(cell);
        s.allocationsFrozen = true;
        cell.candidates = null;
        cell.candidateComplete = false;
        cell.resourceFingerprint = "";
        cell.resourcePlan = null;
        cell.targetDisplay = new NBTTagCompound();
        if (sample == null) {
            // Native hosts without a part are air. Preserve group membership and never clear the live world.
            cell.block = Blocks.air;
            cell.meta = 0;
            cell.blueprintBlock = "minecraft:air";
            cell.blueprintItem = "";
            cell.blueprintDamage = 0;
            cell.unknown = false;
            cell.excluded = true;
            cell.userPosition = true;
            cell.tileNbt = "";
            cell.itemNbt = "";
            cell.structureTargets = new ArrayList<>();
            s.pins.remove(index);
            s.coordinatePins.remove(key);
            s.autoTargets.remove(index);
            s.frozenAutoTargets.remove(key);
            s.facingPins.remove(key);
            s.suppressedAuto.add(key);
            if (index < s.planTargets.size()) s.planTargets.set(index, null);
            return;
        }
        cell.blueprintBlock = String.valueOf(Block.blockRegistry.getNameForObject(cell.block));
        cell.blueprintItem = String.valueOf(Item.itemRegistry.getNameForObject(sample.getItem()));
        cell.blueprintDamage = sample.getItemDamage();
        cell.unknown = false;
        // A native host's declaration is its stored shell identity. Do not add unrelated client choices.
        if (cell.structureTargets != null)
            cell.structureTargets = new ArrayList<>(Collections.singletonList(sample.copy()));
        s.pins.put(index, sample.copy());
        s.coordinatePins.put(key, sample.copy());
        if (s.autoTargets.containsKey(index)) s.autoTargets.put(index, sample.copy());
        if (s.frozenAutoTargets.containsKey(key)) s.frozenAutoTargets.put(key, sample.copy());
        if (index < s.planTargets.size()) s.planTargets.set(index, sample.copy());
    }

    private static void beginAeSaveReview(Session s, HologramPresets.Preset source, String operation, boolean update)
        throws java.io.IOException {
        if (source.id == null || source.id.isEmpty()) source.id = java.util.UUID.randomUUID()
            .toString();
        HologramAePolicy.Draft draft = HologramAePolicy.sanitize(source);
        HologramPresets.validate(draft.preset);
        boolean containsAe = false;
        for (HologramPresets.Cell cell : draft.preset.cells)
            containsAe |= HologramAePolicy.handles(cell.block(), cell.tileNbt);
        if (!containsAe && !draft.blocking) {
            HologramBlueprintMetadata.captureVersions(draft.preset);
            saveReviewedDraft(s, draft.preset, operation, update, false);
            return;
        }
        // One report covers all cells, including disabled/hidden slots; costs never reserve/extract here.
        HologramBlueprintResources.Plan materials = HologramAePolicy.materials(draft.preset);
        HologramBlueprintMetadata.captureVersions(draft.preset);
        s.ae.review = new HologramAeProtocol.Review(
            s.id,
            s.toolId,
            s.planRevision,
            operation,
            update,
            draft,
            materials.entries(materialSource(s)));
        s.blueprintOperation = operation;
        s.blueprintOperationSuccess = false;
        s.status = "保存前请核对完整报告：将清空存储元件内容、重建 AE 网络与安全身份；样板、升级卡及空元件在施工时需要材料。明确继续后才保存净化草稿。";
    }

    private static void confirmAeSave(Session s, NBTTagCompound action) throws java.io.IOException {
        HologramAeProtocol.Review review = s.ae.review;
        if (review == null) throw new java.io.IOException("请先核对当前保存报告");
        HologramPresets.Preset preset = review.approve(action, s.id, s.toolId, s.planRevision);
        saveReviewedDraft(s, preset, review.operation, review.update, true);
    }

    private static void saveReviewedDraft(Session s, HologramPresets.Preset preset, String operation, boolean update,
        boolean ae) throws java.io.IOException {
        if (update) HologramPresets.update(preset);
        else HologramPresets.save(preset);
        currentTool(s).getTagCompound()
            .setString(SELECTED_PRESET, preset.id);
        if (s.virtual && !"importBlueprint".equals(operation)) publishSavedBlueprint(s, preset);
        else s.blueprintSavedId = preset.id;
        s.ae.review = null;
        s.blueprintOperation = operation;
        s.blueprintOperationSuccess = true;
        s.status = update ? "蓝图修改已保存回原蓝图" : "蓝图已保存为新条目";
        if (ae) s.status += "；存储元件内容未保存，源物品未改变；AE 身份在施工时重建，样板、卡及空元件需付料。";
    }

    private static void aeSnapshot(Session s, NBTTagCompound state) {
        try {
            if (s.virtualPreset != null) state.setTag("aeIssues", aeCurrentIssues(s));
            if (s.ae.ack != null) state.setTag("aeEditAck", s.ae.ack.copy());
            if (s.ae.review != null) {
                if (s.ae.review.revision != s.planRevision) s.ae.review = null;
                else state.setTag("aeSaveReview", s.ae.review.snapshot(s.x, s.y, s.z, s.blueprintActiveStructure));
            }
            HologramAeProtocol.Preview preview = s.ae.preview;
            if (preview != null && s.capture != null) {
                HologramCapture.Cell cell = s.capture.positions.get(preview.x + "," + preview.y + "," + preview.z);
                if (cell == null || !preview.current(
                    s.id,
                    s.toolId,
                    s.blueprintActiveStructure,
                    s.planRevision,
                    cell.x,
                    cell.y,
                    cell.z,
                    cell.block,
                    cell.meta,
                    cell.tileNbt)) s.ae.preview = null;
                else {
                    HologramAeBlueprint.Analysis analysis = HologramAeBlueprint
                        .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
                    NBTTagCompound dto = preview.snapshot(analysis);
                    NBTTagList issues = dto.getTagList("issues", 10);
                    for (HologramAePolicy.Issue historical : s.virtualPreset.aeIssues) {
                        if (!(historical.x == cell.x - s.x && historical.y == cell.y - s.y
                            && historical.z == cell.z - s.z) && !"policy".equals(historical.path)) continue;
                        boolean present = false;
                        for (int issueIndex = 0; issueIndex < issues.tagCount(); issueIndex++) {
                            NBTTagCompound now = issues.getCompoundTagAt(issueIndex);
                            present |= historical.code.equals(now.getString("code"))
                                && historical.path.equals(now.getString("path"));
                        }
                        if (!present) issues.appendTag(historical.dto(s.x, s.y, s.z));
                        if (issues.tagCount() > HologramAePolicy.MAX_ISSUES)
                            throw new java.io.IOException("AE preview report budget");
                    }
                    for (int index = 0; index < analysis.materials.size(); index++) {
                        ItemStack material = analysis.materials.get(index);
                        if (materialSource(s).count(material, material.stackSize) >= material.stackSize) continue;
                        issues.appendTag(
                            new HologramAePolicy.Issue(
                                "ae.mark.missing_material",
                                "warning",
                                "materials." + index,
                                HologramAePolicy.fallback("标记允许保留；施工时缺少材料：" + material.getDisplayName()),
                                cell.x,
                                cell.y,
                                cell.z).dto(0, 0, 0));
                        if (issues.tagCount() > HologramAePolicy.MAX_ISSUES)
                            throw new java.io.IOException("AE preview report budget");
                    }
                    for (int index = 0; index < issues.tagCount(); index++)
                        HologramAePolicy.projectResolution(issues.getCompoundTagAt(index), analysis, preview.structure);
                    dto.setTag("issues", issues);
                    state.setTag("aePreview", dto);
                }
            }
        } catch (java.io.IOException | RuntimeException invalid) {
            state.setString("aeReportError", "ae.report.rejected");
            state.setBoolean("aeReportBlocking", true);
            // Never publish an incomplete/truncated report as a successful review.
            state.removeTag("aeSaveReview");
            s.ae.review = null;
        }
    }

    /** Original losses and unresolved blockers persist; explicit validated mutations retire exact resolved problems. */
    private static NBTTagList aeCurrentIssues(Session s) throws java.io.IOException {
        Map<String, HologramAePolicy.Issue> issues = new LinkedHashMap<>();
        Map<String, HologramAeBlueprint.Analysis> analyses = new LinkedHashMap<>();
        if (s.virtualPreset.aeIssues != null) for (HologramAePolicy.Issue issue : s.virtualPreset.aeIssues) {
            issue.validate();
            issues.put(issue.x + "," + issue.y + "," + issue.z + ":" + issue.path + ":" + issue.code, issue);
        }
        if (s.capture != null) for (HologramCapture.Cell cell : s.capture.cells) {
            if (!HologramAePolicy.handles(cell.block, cell.tileNbt)) continue;
            HologramAeBlueprint.Analysis analysis = HologramAeBlueprint
                .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
            analyses.put(position(cell), analysis);
            for (HologramAeBlueprint.Issue source : analysis.issues) {
                HologramAePolicy.Issue issue = new HologramAePolicy.Issue(
                    source.code,
                    HologramAePolicy.severity(source.severity),
                    source.path,
                    HologramAePolicy.fallback(source.message),
                    cell.x - s.x,
                    cell.y - s.y,
                    cell.z - s.z);
                issues.put(issue.x + "," + issue.y + "," + issue.z + ":" + issue.path + ":" + issue.code, issue);
            }
            if (issues.size() > HologramAePolicy.MAX_ISSUES) throw new java.io.IOException("AE complete report budget");
        }
        NBTTagList rows = HologramAePolicy.issues(new ArrayList<>(issues.values()), s.x, s.y, s.z);
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            String key = row.getInteger("x") + "," + row.getInteger("y") + "," + row.getInteger("z");
            HologramAeBlueprint.Analysis analysis = analyses.get(key);
            HologramCapture.Cell cell = s.capture == null ? null : s.capture.positions.get(key);
            if (analysis == null || cell == null) continue;
            String structure = cell.belongsTo(s.blueprintActiveStructure) ? s.blueprintActiveStructure
                : cell.memberships()
                    .isEmpty() ? ""
                        : cell.memberships()
                            .get(0);
            HologramAePolicy.projectResolution(row, analysis, structure);
        }
        return rows;
    }

    private static boolean aeReady(Session s) {
        if (!s.virtual || s.virtualPreset == null || s.virtualPreset.schema < 2 || s.capture == null) return true;
        try {
            for (HologramAePolicy.Issue issue : s.virtualPreset.aeIssues) {
                if (!"blocker".equals(issue.severity)) continue;
                HologramCapture.Cell cell = s.capture.positions
                    .get((s.x + issue.x) + "," + (s.y + issue.y) + "," + (s.z + issue.z));
                if (cell != null && !cell.excluded && membershipEnabled(s, cell)) {
                    s.status = "AE 蓝图仍有未明确处理的净化问题；请在预览中清除对应标记，再核对施工计划";
                    return false;
                }
            }
            for (HologramCapture.Cell cell : s.capture.cells) {
                if (!HologramAePolicy.handles(cell.block, cell.tileNbt) || cell.excluded || !membershipEnabled(s, cell))
                    continue;
                if (HologramAeBlueprint
                    .analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt)).blocking) {
                    s.status = "AE 蓝图仍有阻止施工的问题，请打开相关方块预览并删除或替换不安全标记";
                    return false;
                }
            }
            return true;
        } catch (java.io.IOException | RuntimeException invalid) {
            s.status = "AE 蓝图净化报告无效，保持草稿；请重新审查后施工";
            return false;
        }
    }

    private static boolean blueprintAction(Session s, NBTTagCompound action, String op) {
        try {
            if ("confirmAeSave".equals(op)) {
                confirmAeSave(s, action);
            } else if ("cancelAeSave".equals(op)) {
                s.ae.review = null;
                s.status = "保存已取消，草稿与原蓝图文件保留";
            } else if ("cancelBlueprintPlacement".equals(op)) {
                s.blueprintAwaitingConfirmation = false;
                s.virtualPlacement = false;
                s.presetBrowseForPlacement = false;
                s.blueprintPreview = true;
                s.open = true;
                s.status = "已取消放置确认，蓝图仍可编辑";
            } else if ("confirmBlueprintPlacement".equals(op)) {
                if (!s.virtual || s.virtualPreset == null
                    || !s.virtualPlacement
                    || !s.virtualLocked
                    || !s.blueprintAwaitingConfirmation
                    || !s.placementReviewIssued
                    || !s.id.equals(action.getString("session"))) {
                    s.status = "请先放置蓝图，右击确定位置并查看材料";
                } else if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision) {
                    s.status = "材料计划已更新，请重新核对并确认";
                } else {
                    reviewedBuild(s, action);
                    if (s.job == 1) s.blueprintAwaitingConfirmation = false;
                    s.open = true;
                }
            } else if ("configureBlueprintNbt".equals(op) || "configurePlacementNbt".equals(op)) {
                List<String> keys = action.hasKey("all")
                    ? action.getBoolean("all") ? null : selectedNbtKeys(action.getTagList("keys", 8))
                    : "configurePlacementNbt".equals(op) ? placementKeys(s) : s.retainedNbtKeys;
                if ("configurePlacementNbt".equals(op)) {
                    s.placementNbtCustomized = true;
                    s.placementNbtKeys = keys;
                    if (action.hasKey("retainCovers")) s.placementRetainCovers = action.getBoolean("retainCovers");
                } else {
                    s.retainedNbtKeys = keys;
                    if (s.virtualPreset != null) s.virtualPreset.retainedNbtKeys = keys;
                }
                refreshPlan(s);
                s.status = "NBT 条目已更新；取消资源条目会保留其余配置";
            } else if ("orderMissingMaterials".equals(op)) {
                if (!s.virtual || s.virtualPreset == null) throw new java.io.IOException("请先选择有效蓝图");
                if (!HologramMaterialOrdering.available(s.player)) throw new java.io.IOException("AE 缺料下单接口尚未安装实现");
                refreshPlan(s);
                NBTTagCompound request = new NBTTagCompound();
                request.setString("session", s.id);
                request.setString("presetId", s.virtualPreset.id == null ? "" : s.virtualPreset.id);
                request.setLong("planRevision", s.planRevision);
                NBTTagList missingMaterials = new NBTTagList(), missingResources = new NBTTagList();
                if (s.blueprintTotalResources != null) for (ItemStack item : s.blueprintTotalResources.items) {
                    int available = materialSource(s).count(item, item.stackSize);
                    if (available >= item.stackSize) continue;
                    ItemStack identity = item.copy();
                    identity.stackSize = 1;
                    NBTTagCompound row = identity.writeToNBT(new NBTTagCompound());
                    row.setInteger("required", item.stackSize);
                    row.setInteger("available", available);
                    row.setInteger("missing", item.stackSize - available);
                    missingMaterials.appendTag(row);
                }
                NBTTagList resourceRows = s.blueprintResources == null ? new NBTTagList()
                    : s.blueprintResources.entries(materialSource(s));
                for (int index = 0; index < resourceRows.tagCount(); index++) {
                    NBTTagCompound row = resourceRows.getCompoundTagAt(index);
                    if (new java.math.BigInteger(row.getString("missing")).signum() > 0)
                        missingResources.appendTag(row.copy());
                }
                if (missingMaterials.tagCount() == 0 && missingResources.tagCount() == 0)
                    throw new java.io.IOException("当前所选范围没有可下单的缺料");
                request.setTag("materials", missingMaterials);
                request.setTag("blueprintResources", missingResources);
                String reply = HologramMaterialOrdering.submit(s.player, request);
                s.status = reply == null ? "缺料下单已提交给已注册接口" : boundedPrompt(reply);
            } else if ("saveBlueprint".equals(op) || "saveBlueprintAs".equals(op) || "updatePreset".equals(op)) {
                s.blueprintOperation = op;
                s.blueprintOperationSuccess = false;
                String name = action.getString("name");
                if (name.trim()
                    .isEmpty() && s.virtualPreset != null) name = s.virtualPreset.name;
                HologramPresets.Preset preset = blueprint(s, name);
                if (action.hasKey("retainNbt")) preset.retainNbt = action.getBoolean("retainNbt");
                if (action.hasKey("retainCovers")) preset.retainCovers = action.getBoolean("retainCovers");
                if (action.hasKey("retainAllNbt")) preset.retainedNbtKeys = action.getBoolean("retainAllNbt") ? null
                    : selectedNbtKeys(action.getTagList("retainedNbtKeys", 8));
                boolean update = !"saveBlueprintAs".equals(op) && preset.id != null && !preset.id.isEmpty();
                if ("updatePreset".equals(op) && !update) throw new java.io.IOException("请先保存蓝图，或使用另存为");
                if (preset.id == null || preset.id.isEmpty()) preset.id = java.util.UUID.randomUUID()
                    .toString();
                beginAeSaveReview(s, preset, op, update);
            } else if ("deletePreset".equals(op)) {
                s.blueprintOperation = op;
                s.blueprintOperationSuccess = false;
                String id = action.getString("presetId");
                HologramPresets.delete(id);
                if (id.equals(selectedPreset(s))) currentTool(s).getTagCompound()
                    .removeTag(SELECTED_PRESET);
                if (s.virtualPreset != null && id.equals(s.virtualPreset.id)) s.virtualPreset.id = null;
                if (id.equals(s.blueprintSavedId)) s.blueprintSavedId = "";
                s.blueprintOperationSuccess = true;
                s.status = "蓝图已从库中删除；当前编辑内容保留，可另存为";
            } else if ("editBlueprintPrompt".equals(op)) {
                s.blueprintPrompt = boundedPrompt(action.getString("prompt"));
                if (s.virtualPreset != null) s.virtualPreset.prompt = s.blueprintPrompt;
                s.status = "蓝图整体提示已更新；请保存修改";
            } else if ("editCellFlags".equals(op) || "editHintArea".equals(op)) {
                if (s.capture == null) throw new java.io.IOException("请先载入区域或结构");
                int[] indices = editIndices(s, action);
                for (int index : indices) if (index >= 0 && index < s.capture.cells.size()) {
                    HologramCapture.Cell cell = s.capture.cells.get(index);
                    if (action.hasKey("excluded")) cell.excluded = action.getBoolean("excluded");
                    if ("editCellFlags".equals(op) && action.hasKey("prompt"))
                        cell.prompt = boundedPrompt(action.getString("prompt"));
                }
                if ("editCellFlags".equals(op) && s.virtualPreset != null) {
                    for (HologramPresets.Cell saved : s.virtualPreset.cells) {
                        HologramCapture.Cell cell = s.capture.positions
                            .get((s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z));
                        if (cell == null) continue;
                        saved.prompt = cell.prompt;
                        saved.excluded = cell.excluded;
                    }
                }
                if ("editHintArea".equals(op)) {
                    HologramPresets.HintArea hint = new HologramPresets.HintArea();
                    hint.prompt = boundedPrompt(action.getString("prompt"));
                    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    for (int index : indices) if (index >= 0 && index < s.capture.cells.size()) {
                        HologramCapture.Cell cell = s.capture.cells.get(index);
                        minX = Math.min(minX, cell.x - s.x);
                        maxX = Math.max(maxX, cell.x - s.x);
                        minY = Math.min(minY, cell.y - s.y);
                        maxY = Math.max(maxY, cell.y - s.y);
                        minZ = Math.min(minZ, cell.z - s.z);
                        maxZ = Math.max(maxZ, cell.z - s.z);
                    }
                    if (minX != Integer.MAX_VALUE) {
                        hint.x1 = minX;
                        hint.y1 = minY;
                        hint.z1 = minZ;
                        hint.x2 = maxX;
                        hint.y2 = maxY;
                        hint.z2 = maxZ;
                        List<HologramPresets.HintArea> hints = s.virtualPreset == null ? s.blueprintHintAreas
                            : s.virtualPreset.hintAreas;
                        updateHintArea(hints, hint);
                    }
                }
                refreshPlan(s);
                s.status = "排除与提示已更新；保存蓝图后保留";
            } else if ("importArea".equals(op)) {
                HologramArea area = AREAS.get(s.player.getUniqueID());
                if (area == null) throw new java.io.IOException("请先选择区域");
                startAreaImport(s, area);
            } else if ("structureState".equals(op)) {
                if (s.virtualPreset != null) {
                    for (HologramPresets.Structure saved : s.virtualPreset.structures)
                        if (saved.id.equals(action.getString("structureId")))
                            saved.enabled = action.getBoolean("enabled");
                }
                HologramArea area = AREAS.get(s.player.getUniqueID());
                if (s.virtual && area != null && s.areaOverviewOwner != area && s.virtualPreset != area.blueprint)
                    area = null;
                HologramArea.Structure structure = area == null ? null
                    : area.structures.get(action.getString("structureId"));
                if (structure == null && s.virtualPreset == null) throw new java.io.IOException("结构不存在");
                if (structure != null) structure.enabled = action.getBoolean("enabled");
                refreshPlan(s);
                s.status = action.getBoolean("enabled") ? "结构施工已启用" : "结构施工已停用";
            } else if ("exportBlueprint".equals(op)) {
                HologramPresets.Preset preset = HologramPresets.get(action.getString("presetId"));
                if (preset == null) throw new java.io.IOException("请先保存并选择蓝图");
                HologramPresets.exportBlueprint(action.getString("fileId"), preset);
                s.status = "蓝图已导出到服务器分享目录";
            } else if ("importBlueprint".equals(op)) {
                HologramPresets.Preset preset = HologramPresets.importBlueprint(action.getString("fileId"));
                beginAeSaveReview(s, preset, op, false);
            } else if ("calibrateBlueprintVersions".equals(op)) {
                if (s.virtualPreset == null) throw new java.io.IOException("请先打开蓝图编辑");
                HologramBlueprintMetadata.captureVersions(s.virtualPreset);
                s.status = "蓝图模组版本已校准；请保存修改";
            } else if ("editMove".equals(op)) {
                moveBlueprintCells(s, action);
            } else return false;
        } catch (java.io.IOException | RuntimeException failure) {
            s.status = "蓝图操作失败：" + failure.getMessage();
        }
        return true;
    }

    private static List<String> selectedNbtKeys(NBTTagList input) throws java.io.IOException {
        if (input.tagCount() > 512) throw new java.io.IOException("NBT 条目过多");
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (int index = 0; index < input.tagCount(); index++) {
            String key = input.getStringTagAt(index);
            if (key.isEmpty() || key.length() > 128) throw new java.io.IOException("NBT 条目名称无效");
            keys.add(key);
        }
        return new ArrayList<>(keys);
    }

    private static void updateHintArea(List<HologramPresets.HintArea> hints, HologramPresets.HintArea hint)
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

    /** Keep a live area overview attached after storage publishes a detached saved snapshot. */
    private static void publishSavedBlueprint(Session s, HologramPresets.Preset preset) {
        HologramArea area = AREAS.get(s.player.getUniqueID());
        if (area != null && area.imported && (s.areaOverviewOwner == area || s.virtualPreset == area.blueprint)) {
            s.areaOverviewOwner = area;
            area.blueprint = preset;
            Session overview = AREA_EDITORS.get(s.player.getUniqueID());
            if (overview != null && overview.areaOverviewOwner == area) overview.virtualPreset = preset;
        }
        s.virtualPreset = preset;
    }

    private static void detachAreaOverview(Session s) {
        s.areaOverviewOwner = null;
        if (AREA_EDITORS.get(s.player.getUniqueID()) == s) AREA_EDITORS.remove(s.player.getUniqueID());
    }

    private static boolean placementRetainCovers(Session s) {
        return s.placementRetainCovers != null ? s.placementRetainCovers
            : s.virtualPreset == null || s.virtualPreset.retainCovers;
    }

    private static List<String> placementKeys(Session s) {
        return s.placementNbtCustomized ? s.placementNbtKeys
            : s.virtualPreset == null ? s.retainedNbtKeys : s.virtualPreset.retainedNbtKeys;
    }

    /** Select the planned display payload without installation assertions that would hide repairable blockers. */
    private static String targetPreviewTile(Session s, HologramCapture.Cell cell) throws java.io.IOException {
        if (!s.virtual) return "";
        if (s.virtualPreset == null || s.virtualPreset.schema < 2) return cell.tileNbt;
        return HologramBlueprintNbt.filterCanonical(
            cell.block,
            cell.tileNbt,
            s.virtualPreset.retainNbt ? placementKeys(s) : Collections.emptyList(),
            placementRetainCovers(s));
    }

    private static NBTTagList nbtKeys(List<String> keys) {
        NBTTagList tag = new NBTTagList();
        if (keys != null) for (String key : keys) tag.appendTag(new NBTTagString(key));
        return tag;
    }

    private static String boundedPrompt(String text) {
        return text.length() <= 2048 ? text : text.substring(0, 2048);
    }

    /** Capture current server targets; all inventory and device data come from server-owned state. */
    private static HologramPresets.Preset blueprint(Session s, String name) throws java.io.IOException {
        if (s.capture == null || s.capture.incomplete || s.capturePending) throw new java.io.IOException("需要完整结构或区域");
        HologramPresets.Preset result = s.virtualPreset == null ? new HologramPresets.Preset()
            : HologramPresets.copy(s.virtualPreset);
        result.schema = 2;
        result.name = name == null || name.trim()
            .isEmpty() ? "未命名蓝图" : name.trim();
        result.cells.clear();
        result.palette.clear();
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
            declared.sourceMod = sourceMod(s.machine.getClass());
            storeStructureConfiguration(s, declared);
            result.structures.add(declared);
            result.sourceClass = s.machine.getClass()
                .getName();
            result.sourceMod = sourceMod(s.machine.getClass());
            result.controllerId = ((IGregTechTileEntity) s.target).getMetaTileID();
            result.controllerType = controllerType(s);
            result.controllerFacing = s.machine.getExtendedFacing()
                .ordinal();
        }
        java.util.Set<String> palette = new java.util.HashSet<>();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            ItemStack target = cell.anchor && s.target instanceof IGregTechTileEntity
                ? new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) s.target).getMetaTileID())
                : targetFor(s, cell, i);
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
                    .front(s.facingPins.getOrDefault(position(cell), s.planFacings.getOrDefault(i, -1)));
                saved.controller = "templateController".equals(cell.role);
            } else {
                saved = HologramPresets.cell(
                    target,
                    cell.x - s.x,
                    cell.y - s.y,
                    cell.z - s.z,
                    s.facingPins.getOrDefault(position(cell), s.planFacings.getOrDefault(i, cell.actualFacing)),
                    cell.anchor || "templateController".equals(cell.role));
                Block block = Block.getBlockFromItem(target.getItem());
                if (HologramAePolicy.handles(cell.block, cell.tileNbt)) block = cell.block;
                saved.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
                saved.metadata = s.virtual && cell.block == block ? cell.meta
                    : target.getItem()
                        .getMetadata(target.getItemDamage());
                if (target.hasTagCompound())
                    saved.itemNbt = HologramBlueprintNbt.encodeCaptured(target.getTagCompound());
                addPalette(result, palette, target);
            }
            saved.tileNbt = cell.tileNbt;
            saved.itemNbt = cell.itemNbt.isEmpty() ? saved.itemNbt : cell.itemNbt;
            if (!s.virtual && s.player.worldObj.blockExists(cell.x, cell.y, cell.z)) {
                TileEntity actual = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
                if (actual != null && (cell.anchor || targetMatches(
                    s,
                    cell,
                    i,
                    s.player.worldObj.getBlock(cell.x, cell.y, cell.z),
                    s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z)))) {
                    NBTTagCompound data = HologramNativeCapture.capture(actual);
                    saved.tileNbt = HologramAeBlueprint.supports(cell.block, data)
                        ? HologramBlueprintNbt.encodeCanonical(cell.block, data)
                        : HologramBlueprintNbt.encodeCaptured(data);
                }
            }
            saved.structureId = cell.structureId.isEmpty() ? null : cell.structureId;
            saved.structureIds = new ArrayList<>(cell.memberships());
            if (s.machine != null && !result.structures.isEmpty()) {
                saved.structureId = result.structures.get(0).id;
                saved.structureIds = new ArrayList<>(Collections.singletonList(saved.structureId));
            }
            if (cell.structureTargets != null) saved.allowedTargets = savedTargets(cell.structureTargets);
            else if (cell.element != null && !cell.anchor) saved.allowedTargets = savedTargets(candidates(s, cell));
            saved.excluded = cell.excluded;
            saved.prompt = cell.prompt;
            result.cells.add(saved);
        }
        HologramArea area = AREAS.get(s.player.getUniqueID());
        if (area != null) for (HologramArea.Structure structure : area.structures.values()) {
            String mod = sourceMod(structure.machine.getClass());
            for (cpw.mods.fml.common.ModContainer installed : cpw.mods.fml.common.Loader.instance()
                .getModList())
                if (installed.getModId()
                    .equals(mod)) result.modVersions.put(mod, installed.getVersion());
        }
        return result;
    }

    private static String sourceMod(Class<?> type) {
        String className = type.getName();
        for (cpw.mods.fml.common.ModContainer mod : cpw.mods.fml.common.Loader.instance()
            .getModList()) {
            try {
                java.net.URL location = type.getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
                if (mod.getSource() != null && mod.getSource()
                    .getCanonicalFile()
                    .equals(new java.io.File(location.toURI()).getCanonicalFile())) return mod.getModId();
            } catch (Exception ignored) { /* The package fallback also supports synthetic test classes. */ }
            Object instance = mod.getMod();
            if (instance != null) {
                String modClass = instance.getClass()
                    .getName();
                int last = modClass.lastIndexOf('.');
                if (last > 0 && className.startsWith(modClass.substring(0, last) + ".")) return mod.getModId();
            }
        }
        if (className.startsWith("gregtech.")) return "gregtech";
        if (className.startsWith("tectech.")) return "tectech";
        return "";
    }

    private static void writeBlueprintState(Session s, NBTTagCompound state) {
        HologramArea area = AREAS.get(s.player.getUniqueID());
        state.setBoolean("areaSelecting", area != null && area.selecting);
        state.setBoolean("areaImporting", area != null && area.importing);
        state.setInteger("areaImportProgress", area != null && area.importing ? area.importProgress() : 0);
        state.setString("areaImportStage", area != null && area.importing ? area.importStage() : "");
        state.setBoolean("areaImported", area != null && area.imported);
        state.setIntArray("areaPoints", area == null ? new int[0] : area.points());
        NBTTagList structureLabels = area == null ? new NBTTagList() : area.labels();
        if (s.virtualPreset != null && !s.virtualPreset.structures.isEmpty()) {
            structureLabels = new NBTTagList();
            for (HologramPresets.Structure saved : s.virtualPreset.structures) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("id", saved.id);
                row.setString("name", saved.name == null ? "结构" : saved.name);
                row.setInteger("x", s.x + saved.x);
                row.setInteger("y", s.y + saved.y);
                row.setInteger("z", s.z + saved.z);
                row.setBoolean("enabled", saved.enabled);
                String warning = blueprintSourceWarning(s.virtualPreset, saved.id);
                row.setBoolean("sourceMissing", !warning.isEmpty());
                row.setString("sourceWarning", warning);
                row.setString("sourceClass", saved.sourceClass == null ? "" : saved.sourceClass);
                row.setString("sourceMod", saved.sourceMod == null ? "" : saved.sourceMod);
                row.setBoolean("declared", warning.isEmpty());
                TileEntity tile = s.player.worldObj.getTileEntity(s.x + saved.x, s.y + saved.y, s.z + saved.z);
                row.setBoolean(
                    "formed",
                    tile instanceof IGregTechTileEntity
                        && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase
                        && ((MTEEnhancedMultiBlockBase<?>) ((IGregTechTileEntity) tile).getMetaTileEntity()).mMachine);
                structureLabels.appendTag(row);
            }
        }
        String sourceWarning = s.virtualPreset == null ? "" : blueprintSourceWarning(s.virtualPreset, "");
        if (s.virtualPreset != null && sourceWarning.isEmpty())
            for (HologramPresets.Structure source : s.virtualPreset.structures) {
                sourceWarning = blueprintSourceWarning(s.virtualPreset, source.id);
                if (!sourceWarning.isEmpty()) break;
            }
        state.setBoolean("sourceMissing", !sourceWarning.isEmpty());
        state.setString("sourceWarning", sourceWarning);
        state.setTag("structures", structureLabels);
        state.setString("activeStructure", s.virtual ? s.blueprintActiveStructure : area == null ? "" : area.active);
        List<String> savedKeys = s.virtualPreset == null ? s.retainedNbtKeys : s.virtualPreset.retainedNbtKeys;
        state.setBoolean("blueprintNbtAll", savedKeys == null);
        state.setTag("blueprintNbtKeys", nbtKeys(savedKeys));
        state.setBoolean("placementNbtCustomized", s.placementNbtCustomized);
        state.setBoolean("placementRetainCovers", placementRetainCovers(s));
        state.setBoolean("placementNbtAll", placementKeys(s) == null);
        state.setTag("placementNbtKeys", nbtKeys(placementKeys(s)));
        if (s.nbtKeysCapture != s.capture) {
            s.nbtKeysCapture = s.capture;
            s.availableNbtKeys.clear();
            if (s.capture != null) for (HologramCapture.Cell cell : s.capture.cells) {
                try {
                    NBTTagCompound data = s.virtual ? HologramBlueprintNbt.decode(cell.tileNbt) : null;
                    if (!s.virtual) {
                        TileEntity tile = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
                        if (tile != null) {
                            data = HologramNativeCapture.capture(tile);
                        }
                    }
                    if (data != null) for (Object key : data.func_150296_c()) s.availableNbtKeys.add((String) key);
                    if (s.virtual) {
                        NBTTagCompound item = HologramBlueprintNbt.decode(cell.itemNbt);
                        if (item != null) for (Object key : item.func_150296_c()) s.availableNbtKeys.add((String) key);
                    }
                } catch (java.io.IOException | RuntimeException ignored) { /* Invalid payload stays unsupported. */ }
            }
        }
        NBTTagList entries = new NBTTagList();
        for (String key : s.availableNbtKeys) {
            NBTTagCompound row = new NBTTagCompound();
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            row.setString("key", key);
            row.setString("label", key);
            row.setString(
                "category",
                lower.contains("fluid") || lower.contains("tank") ? "fluid"
                    : lower.contains("energy") || lower.equals("meu") ? "energy"
                        : lower.contains("inventory") || lower.equals("items") ? "items" : "configuration");
            row.setBoolean("selected", savedKeys == null || savedKeys.contains(key));
            row.setBoolean("blueprintSelected", savedKeys == null || savedKeys.contains(key));
            row.setBoolean("placementSelected", placementKeys(s) == null || placementKeys(s).contains(key));
            entries.appendTag(row);
        }
        state.setTag("nbtEntries", entries);
        state.setBoolean(
            "materialOrderingAvailable",
            s.virtual && s.virtualPreset != null && HologramMaterialOrdering.available(s.player));
        state.setTag(
            "blueprintResources",
            s.blueprintResources == null ? new NBTTagList() : s.blueprintResources.entries(materialSource(s)));
        state.setString("blueprintResourceMissing", s.blueprintResourceMissing);
        state.setBoolean("blueprintPreview", s.blueprintPreview);
        state.setBoolean("blueprintAwaitingConfirmation", s.blueprintAwaitingConfirmation);
        state.setBoolean("forcePlacementReady", s.forcePlacementReady);
        state.setBoolean("blueprintRetainNbt", s.virtualPreset == null || s.virtualPreset.retainNbt);
        state.setBoolean("blueprintRetainCovers", s.virtualPreset == null || s.virtualPreset.retainCovers);
        state.setString("blueprintOperation", s.blueprintOperation);
        state.setBoolean("blueprintOperationSuccess", s.blueprintOperationSuccess);
        state.setString(
            "blueprintSavedId",
            s.virtualPreset == null ? s.blueprintSavedId : s.virtualPreset.id == null ? "" : s.virtualPreset.id);
        state.setString(
            "blueprintPrompt",
            s.virtualPreset == null ? s.blueprintPrompt : s.virtualPreset.prompt == null ? "" : s.virtualPreset.prompt);
        NBTTagList versions = new NBTTagList();
        Map<String, String> dependencies = s.virtualPreset == null ? new LinkedHashMap<>()
            : s.virtualPreset.modVersions;
        if (s.machine != null) {
            String modId = sourceMod(s.machine.getClass());
            state.setString("sourceMod", modId);
            state.setString(
                "sourceClass",
                s.machine.getClass()
                    .getName());
            for (cpw.mods.fml.common.ModContainer installed : cpw.mods.fml.common.Loader.instance()
                .getModList())
                if (installed.getModId()
                    .equals(modId)) dependencies.put(modId, installed.getVersion());
        }
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            NBTTagCompound row = new NBTTagCompound();
            String current = "minecraft".equals(entry.getKey()) ? "1.7.10" : null;
            for (cpw.mods.fml.common.ModContainer mod : cpw.mods.fml.common.Loader.instance()
                .getModList())
                if (entry.getKey()
                    .equals(mod.getModId())) current = mod.getVersion();
            int comparison = current == null ? 0 : HologramBlueprintMetadata.compareVersions(current, entry.getValue());
            row.setString("modId", entry.getKey());
            row.setString("saved", entry.getValue());
            row.setString("current", current == null ? "" : current);
            row.setString(
                "state",
                current == null ? "missing" : comparison < 0 ? "outdated" : comparison > 0 ? "ahead" : "current");
            versions.appendTag(row);
        }
        state.setTag("modVersions", versions);
        NBTTagList hints = new NBTTagList();
        for (HologramPresets.HintArea hint : s.virtualPreset == null ? s.blueprintHintAreas
            : s.virtualPreset.hintAreas) {
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("bounds", new int[] { hint.x1, hint.y1, hint.z1, hint.x2, hint.y2, hint.z2 });
            row.setString("prompt", hint.prompt == null ? "" : hint.prompt);
            hints.appendTag(row);
        }
        state.setTag("hintAreas", hints);
        state.setIntArray(
            "selectedIndices",
            s.selectedIndices.stream()
                .mapToInt(Integer::intValue)
                .toArray());
    }

    private static void savePreset(Session s, String name) {
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
        preset.name = name == null ? "" : name.trim();
        preset.prompt = s.blueprintPrompt;
        preset.hintAreas.addAll(s.blueprintHintAreas);
        preset.controllerType = controllerType(s);
        preset.controllerId = ((IGregTechTileEntity) s.target).getMetaTileID();
        preset.controllerFacing = s.machine.getExtendedFacing()
            .ordinal();
        java.util.Set<String> palette = new java.util.HashSet<>();
        try {
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                ItemStack target;
                int direction = s.facingPins.getOrDefault(position(cell), s.planFacings.getOrDefault(i, -1));
                if (cell.anchor) {
                    target = new ItemStack(GregTechAPI.sBlockMachines, 1, preset.controllerId);
                    direction = HologramBlockIdentity.front((IGregTechTileEntity) s.target);
                } else {
                    if (cell.block == Blocks.air && !s.pins.containsKey(i)) continue;
                    if (cell.unknown && !s.pins.containsKey(i)) {
                        s.status = "预设存在没有安全目标的未知元素，请先明确选择合法目标";
                        return;
                    }
                    target = targetFor(s, cell, i);
                    if (target == null || !acceptableSample(s, cell, target)) {
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
                addPalette(preset, palette, target);
                if (!cell.anchor) {
                    HologramCasingFallback.Target casing = HologramCasingFallback
                        .resolve(cell.element, elementTrigger(s, cell));
                    ItemStack shell = casing == null ? null : new ItemStack(casing.block, 1, casing.meta);
                    if (shell == null && !cell.unknown
                        && !"hatch".equals(cell.role)
                        && cell.block != null
                        && cell.block != Blocks.air) shell = new ItemStack(cell.block, 1, cell.meta);
                    if (acceptableSample(s, cell, shell) && !(shell.getItem() instanceof ItemMachines)) {
                        saved.defaultItem = String.valueOf(Item.itemRegistry.getNameForObject(shell.getItem()));
                        saved.defaultDamage = shell.getItemDamage();
                        addPalette(preset, palette, shell);
                    }
                    if (cell.block != null && cell.block != Blocks.air) {
                        ItemStack original = new ItemStack(cell.block, 1, cell.meta);
                        if (acceptableSample(s, cell, original)) addPalette(preset, palette, original);
                    }
                    for (ItemStack candidate : candidates(s, cell))
                        if (acceptableSample(s, cell, candidate)) addPalette(preset, palette, candidate);
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
                addPalette(preset, palette, target);
            }
            HologramPresets.save(preset);
            currentTool(s).getTagCompound()
                .setString(SELECTED_PRESET, preset.id);
            s.status = "预设已保存到服务器配置；包含控制器，施工按真实材料扣料";
        } catch (java.io.IOException invalid) {
            s.status = "预设保存失败：" + invalid.getMessage();
        }
    }

    private static void addPalette(HologramPresets.Preset preset, java.util.Set<String> keys, ItemStack stack) {
        String key = Item.itemRegistry.getNameForObject(stack.getItem()) + ":" + stack.getItemDamage();
        if (keys.add(key)) preset.palette.add(HologramPresets.cell(stack, 0, 0, 0, -1, false));
    }

    private static void loadPreset(Session s, String id) {
        loadPreset(s, id, true);
    }

    private static void loadPreset(Session s, String id, boolean selectOnly) {
        try {
            HologramPresets.Preset preset = HologramPresets.get(id);
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
            detachAreaOverview(s);
            s.virtualPreset = preset;
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
            currentTool(s).getTagCompound()
                .setString(SELECTED_PRESET, id);
            populateVirtual(s);
            s.status = s.virtualPlacement ? "预设已加载；投影跟随鼠标，锁定位置后施工" : "预设已加载，仅编辑模板";
            if (s.virtualPlacement) presetGuidance(s, "位置预览跟随鼠标：R 水平旋转，Ctrl + R 垂直旋转；右键锁定，再次右键核对材料与 NBT，Esc 取消放置。");
        } catch (java.io.IOException invalid) {
            s.status = "预设加载失败：" + invalid.getMessage();
        }
    }

    /** Guidance is emitted only by explicit player actions, never by scan or tick. */
    private static void selectPreset(Session s, String id) {
        currentTool(s).getTagCompound()
            .setString(SELECTED_PRESET, id);
        s.status = "蓝图已选定；点击放置进入位置预览";
        s.player.inventory.markDirty();
    }

    /** Guidance is emitted only by explicit player actions, never by scan or tick. */
    private static void presetGuidance(Session s, String message) {
        s.player.addChatMessage(new ChatComponentText("[猫猫全息·自定义预设] " + message));
    }

    private static Session editPreset(Session previous, String id) {
        int x = previous.target != null || previous.virtual ? previous.x
            : net.minecraft.util.MathHelper.floor_double(previous.player.posX);
        int y = previous.target != null || previous.virtual ? previous.y
            : clamp(
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
        Session editing = new Session(previous.player, x, y, z, false, previous.side);
        editing.virtual = true;
        loadPreset(editing, id, false);
        if (editing.virtualPreset == null) {
            previous.status = editing.status;
            return previous;
        }
        editing.open = true;
        editing.settingsOnly = false;
        editing.status = "已打开自定义预设编辑；保存回原预设或另存为，仅保存规划，不施工";
        SESSIONS.put(previous.player.getUniqueID(), editing);
        presetGuidance(editing, "编辑完成后可保存或另存为；点击放置预览位置，右击确定位置，再核对材料并确认施工。");
        return editing;
    }

    private static void saveVirtualPreset(Session s, String name, boolean update) {
        if (!s.virtual || s.virtualPreset == null || s.capture == null || s.capture.incomplete || s.capturePending) {
            s.status = "请先完整载入自定义预设进行编辑";
            return;
        }
        try {
            HologramPresets.Preset preset = editedVirtualPreset(
                s,
                update && (name == null || name.trim()
                    .isEmpty()) ? s.virtualPreset.name : name);
            if (update) preset.id = s.virtualPreset.id;
            beginAeSaveReview(s, preset, update ? "updatePreset" : "saveBlueprintAs", update);
        } catch (java.io.IOException invalid) {
            s.status = "预设保存失败：" + invalid.getMessage();
        }
    }

    private static HologramPresets.Preset editedVirtualPreset(Session s, String name) throws java.io.IOException {
        if (s.virtualPreset.schema >= 2) return blueprint(s, name);
        HologramPresets.Preset original = s.virtualPreset;
        HologramPresets.Preset result = new HologramPresets.Preset();
        result.name = name == null ? "" : name.trim();
        result.controllerType = original.controllerType;
        result.controllerId = original.controllerId;
        result.controllerFacing = virtualControllerFacing(s).ordinal();
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
            if (fallback != null && !s.virtualEdited.contains(position(cell))) target = fallback.stack();
            boolean controller = "templateController".equals(cell.role);
            if (target == null || !acceptableSample(s, cell, target) || target.hasTagCompound())
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
            int direction = fallback != null && !s.virtualEdited.contains(position(cell)) ? fallback.facing
                : s.facingPins.getOrDefault(position(cell), s.planFacings.getOrDefault(i, -1));
            if (controller) direction = virtualControllerFacing(s).getDirection()
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

    private static HologramPresets.Structure blueprintSource(HologramPresets.Preset preset, String id) {
        for (HologramPresets.Structure structure : preset.structures) if (structure.id.equals(id)) return structure;
        return null;
    }

    private static String blueprintSourceClass(HologramPresets.Preset preset, String id) {
        HologramPresets.Structure structure = blueprintSource(preset, id);
        if (structure != null && structure.sourceClass != null) return structure.sourceClass;
        if (preset.sourceClass != null && !preset.sourceClass.isEmpty()) return preset.sourceClass;
        if (preset.controllerId >= 0 && preset.controllerType != null && preset.controllerType.lastIndexOf(':') > 0)
            return preset.controllerType.substring(0, preset.controllerType.lastIndexOf(':'));
        return "";
    }

    private static String blueprintSourceWarning(HologramPresets.Preset preset, String id) {
        String sourceClass = blueprintSourceClass(preset, id);
        if (sourceClass.isEmpty()) return "";
        try {
            Class.forName(sourceClass, false, HologramService.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError missing) {
            return "来源结构类缺失：" + sourceClass;
        }
        HologramPresets.Structure structure = blueprintSource(preset, id);
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

    private static boolean blueprintControllerMatches(HologramPresets.Preset preset, HologramPresets.Cell saved,
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

    private static void populateVirtual(Session s) {
        if (!s.virtual || s.virtualPreset == null) return;
        materializeImplicitStructure(s);
        s.capture = new HologramCapture(null, trigger(s));
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
        String fallbackSourceClass = blueprintSourceClass(s.virtualPreset, "");
        String fallbackSourceWarning = blueprintSourceWarning(s.virtualPreset, "");
        Map<String, String> sourceClasses = new HashMap<>(), sourceWarnings = new HashMap<>();
        for (HologramPresets.Structure source : s.virtualPreset.structures) {
            sourceClasses.put(source.id, blueprintSourceClass(s.virtualPreset, source.id));
            sourceWarnings.put(source.id, blueprintSourceWarning(s.virtualPreset, source.id));
        }
        List<HologramPresets.Cell> ordered = new ArrayList<>(s.virtualPreset.cells);
        ordered.sort((a, b) -> Boolean.compare(a.controller, b.controller));
        for (HologramPresets.Cell saved : ordered) {
            String sourceId = saved.structureId == null ? "" : saved.structureId;
            String savedWarning = sourceWarnings.getOrDefault(sourceId, fallbackSourceWarning);
            boolean controllerMatches = blueprintControllerMatches(s.virtualPreset, saved, savedWarning);
            ItemStack target = saved.missing() || !controllerMatches ? null : saved.stack();
            if (target != null && HologramNativePlacement.supported(saved.block())) {
                try {
                    target
                        .setTagCompound(HologramBlueprintNbt.nativePayload(HologramBlueprintNbt.decode(saved.itemNbt)));
                } catch (java.io.IOException invalid) {
                    target = null;
                }
            }
            HologramCapture.Cell cell = new HologramCapture.Cell(null, s.x + saved.x, s.y + saved.y, s.z + saved.z);
            cell.block = target == null || HologramNativePlacement.supported(saved.block()) ? saved.block()
                : Block.getBlockFromItem(target.getItem());
            cell.meta = s.virtualPreset.schema >= 2 ? saved.metadata
                : target == null ? saved.metadata
                    : target.getItem()
                        .getMetadata(target.getItemDamage());
            cell.blueprintBlock = saved.block == null ? "" : saved.block;
            cell.blueprintItem = saved.item == null ? "" : saved.item;
            cell.blueprintDamage = saved.damage;
            setMemberships(cell, saved.memberships());
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
                    if (fallback != null && !same(original, fallback)) cell.structureTargets.add(fallback);
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
            if (target != null && !(s.virtualPreset.schema >= 2 && editableAeSample(cell, target)
                || placeableSample(s, cell, target))) {
                s.capture.incomplete = true;
                s.capture.failure = "INVALID_PREFAB_ITEM";
                break;
            }
            int index = s.capture.cells.size();
            s.capture.cells.add(cell);
            s.capture.positions.put(position(cell), cell);
            if (target != null) {
                s.pins.put(index, target.copy());
                s.coordinatePins.put(position(cell), target.copy());
            } else if (cell.block != Blocks.air || saved.missing()) {
                cell.unknown = true;
                cell.status = "unsupported";
            }
            for (int legal : legalFacings(s, cell, target))
                if (saved.facing == legal) s.facingPins.put(position(cell), saved.facing);
        }
        refreshPlan(s);
    }

    /** Import admission checks the stored AE shell only; unresolved contents remain visible for explicit repair. */
    private static boolean editableAeSample(HologramCapture.Cell cell, ItemStack target) {
        if (target == null || target.getItem() == null || target.stackSize < 1) return false;
        try {
            NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
            return HologramAeBlueprint.supports(cell.block, payload)
                && sameMaterial(target, HologramAeBlueprint.sampleIdentity(cell.block, cell.meta, payload));
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    /** A legacy template controller identifies one whole footprint; absence of tags never makes its shell loose. */
    private static void materializeImplicitStructure(Session s) {
        HologramPresets.Preset preset = s.virtualPreset;
        if (preset.schema < 2 || !preset.structures.isEmpty()) return;
        HologramPresets.Cell controller = null;
        for (HologramPresets.Cell cell : preset.cells) if (cell.controller) {
            controller = cell;
            break;
        }
        if (controller == null) return;
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.id = UUID.randomUUID()
            .toString();
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
        for (HologramPresets.Cell cell : preset.cells) {
            cell.structureId = structure.id;
            cell.structureIds = new ArrayList<>(Collections.singletonList(structure.id));
        }
    }

    /** Allocate only untouched template targets, with cached class/tier groups and shared material reservations. */
    private static void allocateVirtualTargets(Session s) {
        if (s.virtualPreset == null || s.capture.incomplete || s.virtualPreset.schema >= 2) return;
        if (s.virtualGroups.isEmpty()) {
            for (HologramPresets.Cell entry : s.virtualPreset.palette) {
                ItemStack sample = entry.stack();
                if (sample == null || hatchTier(sample) == Integer.MAX_VALUE) continue;
                String role = ItemMachines.getMetaTileEntity(sample)
                    .getClass()
                    .getName();
                s.virtualGroups.computeIfAbsent(role, ignored -> new ArrayList<>())
                    .add(sample);
            }
            for (List<ItemStack> group : s.virtualGroups.values())
                group.sort((a, b) -> Integer.compare(hatchTier(b), hatchTier(a)));
        }
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> reserved = new HashMap<>();
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            String key = position(cell);
            indices.put(key, i);
            ItemStack manual = s.pins.get(i);
            if (manual != null && s.virtualEdited.contains(key) && !plainTargetInstalled(s, cell, manual))
                reserved.merge(stackKey(manual), 1, Integer::sum);
        }
        for (HologramPresets.Cell saved : s.virtualPreset.cells) {
            String position = (s.x + saved.x) + "," + (s.y + saved.y) + "," + (s.z + saved.z);
            Integer index = indices.get(position);
            if (index == null || s.virtualEdited.contains(position)) continue;
            HologramCapture.Cell cell = s.capture.cells.get(index);
            ItemStack desired = saved.stack();
            if (desired == null) continue;
            ItemStack chosen = desired;
            int tier = hatchTier(desired);
            if (s.hatchDowngrade && tier != Integer.MAX_VALUE && !plainTargetInstalled(s, cell, desired)) {
                String role = ItemMachines.getMetaTileEntity(desired)
                    .getClass()
                    .getName();
                List<ItemStack> group = s.virtualGroups.getOrDefault(role, Collections.emptyList());
                ItemStack existing = null;
                for (ItemStack candidate : group)
                    if (hatchTier(candidate) <= tier && plainTargetInstalled(s, cell, candidate)) {
                        existing = candidate;
                        break;
                    }
                if (existing != null) chosen = existing;
                else for (ItemStack candidate : group) {
                    if (hatchTier(candidate) > tier || legalFacings(s, cell, candidate).length == 0) continue;
                    String key = stackKey(candidate);
                    int available = counts.computeIfAbsent(
                        key,
                        ignored -> s.player.capabilities.isCreativeMode ? HologramCapture.LIMIT
                            : materialSource(s).count(candidate, Math.max(4096, s.capture.cells.size())));
                    if (available > reserved.getOrDefault(key, 0)) {
                        chosen = candidate;
                        break;
                    }
                }
            }
            s.pins.put(index, chosen.copy());
            s.coordinatePins.put(position, chosen.copy());
            if (!plainTargetInstalled(s, cell, chosen)) reserved.merge(stackKey(chosen), 1, Integer::sum);
            Integer facing = s.facingPins.get(position);
            if (facing != null) {
                boolean legal = false;
                for (int direction : legalFacings(s, cell, chosen)) if (direction == facing) legal = true;
                if (!legal) s.facingPins.remove(position);
            }
        }
    }

    private static boolean plainTargetInstalled(Session s, HologramCapture.Cell cell, ItemStack target) {
        World world = s.player.worldObj;
        if (!world.blockExists(cell.x, cell.y, cell.z)) return false;
        if (target.getItem() instanceof ItemMachines) {
            TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            return tile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) tile).getMetaTileID() == target.getItemDamage();
        }
        return world.getBlock(cell.x, cell.y, cell.z) == Block.getBlockFromItem(target.getItem())
            && world.getBlockMetadata(cell.x, cell.y, cell.z) == target.getItem()
                .getMetadata(target.getItemDamage());
    }

    private static NBTTagCompound boundedChannels(NBTTagCompound input) {
        NBTTagCompound result = new NBTTagCompound();
        int count = 0;
        for (Object key : input.func_150296_c()) {
            String name = (String) key;
            if (count++ >= 64) break;
            if (name.matches("[a-z0-9_.-]{1,48}") && input.getInteger(name) > 0)
                result.setInteger(name, input.getInteger(name));
        }
        return result;
    }

    private static boolean validChannels(NBTTagCompound input) {
        if (input.func_150296_c()
            .size() > 64) return false;
        for (Object key : input.func_150296_c()) {
            String name = (String) key;
            if (!name.matches("[a-z0-9_.-]{1,48}") || !input.hasKey(name, 3) || input.getInteger(name) <= 0)
                return false;
        }
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void cancelCaptureRetry(Session s) {
        s.capturePending = false;
        s.captureRetryDue = 0;
        s.retryChannels = null;
        s.buildAfterCapture = false;
        s.calibratePending = false;
    }

    private static boolean captureConfigurationCurrent(Session s) {
        return s.retryChannels != null && s.retryChannels.equals(s.channels)
            && s.retryMain == s.main
            && s.retryNoHatches == s.noHatches
            && s.retryMode == s.mode
            && s.retryScope == s.scope
            && s.retryLayer == s.layer
            && s.retrySelected == s.selected;
    }

    private static void scheduleCaptureRetry(Session s) {
        // A timeout mixed with another failure is not treated as a transient cold-load timeout.
        boolean continuing = s.capture != null && s.capture.pending;
        boolean timeoutOnly = s.capture != null && s.capture.incomplete
            && (s.capture.failure.equals("TIME_LIMIT") || s.capture.failure.startsWith("TIME_LIMIT "));
        if (!continuing && !timeoutOnly) return;
        if (!continuing && s.captureAttempts >= CAPTURE_ATTEMPTS) {
            s.status = "结构采集连续 " + CAPTURE_ATTEMPTS + " 次超时，高级施工已阻止；可重新扫描或使用原版提示";
            return;
        }
        s.capturePending = true;
        s.captureRetryDue = s.player.worldObj.getTotalWorldTime() + 2;
        s.retryFacing = s.facing;
        s.retryMain = s.main;
        s.retryChannels = (NBTTagCompound) s.channels.copy();
        s.retryNoHatches = s.noHatches;
        s.retryMode = s.mode;
        s.retryScope = s.scope;
        s.retryLayer = s.layer;
        s.retrySelected = s.selected;
        s.status = continuing ? "正在分批采集完整结构（已采集 " + s.capture.cells.size() + " 格）"
            : "正在采集结构：首次加载超出单次预算，稍后自动重试（" + s.captureAttempts + "/" + CAPTURE_ATTEMPTS + "）";
    }

    private static void scan(Session s) {
        scan(s, true);
    }

    private static void scan(Session s, boolean resetAttempts) {
        if (s.virtual) {
            refreshPlan(s);
            return;
        }
        HologramCapture resumed = !resetAttempts && s.capture != null && s.capture.pending ? s.capture : null;
        refreshMaterialChoices(s);
        boolean calibrate = s.calibratePending, build = !resetAttempts && s.buildAfterCapture;
        cancelCaptureRetry(s);
        s.calibratePending = calibrate;
        s.buildAfterCapture = build;
        if (resetAttempts) s.captureAttempts = 0;
        s.captureAttempts++;
        s.job = 0;
        s.cursor = 0;
        s.completed = 0;
        s.recovered.clear();
        s.defer = false;
        s.stopTick = false;
        clearPending(s);
        Map<String, ItemStack> savedPins = new HashMap<>(s.coordinatePins);
        String selectedPosition = s.scanSelectedPosition;
        java.util.Set<String> selectedPositions = new java.util.HashSet<>(s.scanSelectedPositions);
        if (s.capture != null) for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (s.pins.containsKey(i)) savedPins.put(
                position(cell),
                s.pins.get(i)
                    .copy());
            if (resetAttempts && i == s.selected) selectedPosition = position(cell);
            if (resetAttempts && s.selectedIndices.contains(i)) selectedPositions.add(position(cell));
        }
        s.scanSelectedPosition = selectedPosition;
        s.scanSelectedPositions.clear();
        s.scanSelectedPositions.addAll(selectedPositions);
        s.pins.clear();
        s.hatchCandidates.clear();
        s.declarationCandidates.clear();
        s.declarationCandidateCount = 0;
        s.pureDeclarations.clear();
        s.hatchParts.clear();
        s.hatchRequirements.clear();
        s.capabilities = HologramCapabilities.describe(s.context, trigger(s), null)
            .withSavedChannels(s.channels);
        if (s.target == null) {
            s.capture = null;
            refreshPlan(s);
            s.status = "信道配置已保存；请右键控制器选择施工目标";
            return;
        }
        if (s.machine == null || !(s.machine instanceof IConstructable)) {
            s.capture = null;
            refreshPlan(s);
            s.status = "未支持高级采集；仍可使用机器原版构造器接口";
            return;
        }
        s.facing = s.machine.getExtendedFacing()
            .ordinal();
        if (!s.mainExplicit && s.machine instanceof MTEForgeOfGods) s.main = clamp(
            ((MTEForgeOfGods) s.machine).getData()
                .getRingAmount(),
            1,
            3);
        ItemStack initialTrigger = trigger(s);
        s.capture = HologramCapture.collect(s.machine, initialTrigger, s.machine.getExtendedFacing(), resumed);
        if (s.capture.pending) {
            s.coordinatePins.putAll(savedPins);
            scheduleCaptureRetry(s);
            return;
        }
        s.capabilities = HologramCapabilities.describe(s.context, initialTrigger, s.capture.channelReport)
            .withSavedChannels(s.channels);
        ItemStack observedTrigger = trigger(s);
        if (!s.capture.incomplete && !ItemStack.areItemStackTagsEqual(initialTrigger, observedTrigger)) {
            s.capture = HologramCapture.collect(s.machine, observedTrigger, s.machine.getExtendedFacing());
            if (s.capture.pending) {
                s.coordinatePins.putAll(savedPins);
                scheduleCaptureRetry(s);
                return;
            }
            s.capabilities = HologramCapabilities.describe(s.context, observedTrigger, s.capture.channelReport)
                .withSavedChannels(s.channels);
            if (!ItemStack.areItemStackTagsEqual(observedTrigger, trigger(s))) {
                s.capture.incomplete = true;
                s.capture.failure = "CHANNEL_BRANCH_UNSTABLE";
            }
        }
        for (String key : savedPins.keySet()) {
            boolean found = false;
            found = s.capture.positions.containsKey(key);
            if (!found && !s.manualPositions.containsKey(key)) {
                String[] xyz = key.split(",");
                HologramCapture.Cell cell = new HologramCapture.Cell(
                    null,
                    Integer.parseInt(xyz[0]),
                    Integer.parseInt(xyz[1]),
                    Integer.parseInt(xyz[2]));
                cell.userPosition = true;
                cell.block = Blocks.air;
                cell.role = "manual";
                s.manualPositions.put(key, cell);
            }
        }
        for (HologramCapture.Cell manual : s.manualPositions.values()) {
            boolean found = false;
            found = s.capture.positions.containsKey(position(manual));
            if (!found && s.capture.cells.size() < HologramCapture.LIMIT) {
                s.capture.cells.add(manual);
                s.capture.positions.put(position(manual), manual);
            }
        }
        s.selected = -1;
        s.selectedIndices.clear();
        // Memberships must be attached before resolving shared-cell declaration intersections.
        bindAreaMemberships(s);
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            ItemStack savedPin = savedPins.get(position(cell));
            if (savedPin != null && !cell.anchor) s.pins.put(i, savedPin);
            if (position(cell).equals(selectedPosition)) s.selected = i;
            if (selectedPositions.contains(position(cell))) s.selectedIndices.add(i);
            cell.candidates = candidates(s, cell);
        }
        s.scanSelectedPosition = null;
        s.scanSelectedPositions.clear();
        restoreBlueprintAnnotations(s);
        refreshPlan(s);
        s.status = s.capture.incomplete ? "结构采集不完整，高级施工已阻止" : "预览已采集；未知元素及机器部件保持保护";
        scheduleCaptureRetry(s);
        if (s.calibratePending && !s.capturePending) {
            s.calibratePending = false;
            if (!s.capture.incomplete) applyCurrentCalibration(s);
            else s.status = "采集失败，当前规划已保留，未校准现场目标";
        }
        if (s.buildAfterCapture && !s.capturePending) {
            s.buildAfterCapture = false;
            if (!s.capture.incomplete) directBuild(s, false);
            else s.status = "采集失败，未执行施工";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> candidates(Session s, HologramCapture.Cell cell) {
        if (s.virtual && s.virtualPreset != null) {
            cell.candidateComplete = false;
            cell.candidateBoundary = "预设调色板仅提供编辑样本；原机器结构声明未随预设保存";
            List<ItemStack> result = new ArrayList<>();
            if (cell.structureTargets != null) {
                cell.candidateBoundary = "仅提供本格保存的结构声明候选；共享格须同时符合各成员结构";
                for (ItemStack sample : cell.structureTargets) if (placeableSample(s, cell, sample)) result.add(sample);
                return result;
            }
            for (HologramPresets.Cell entry : s.virtualPreset.palette) {
                ItemStack sample = entry.stack();
                if (placeableSample(s, cell, sample)) result.add(sample);
            }
            return result;
        }
        if (cell.element == null) return Collections.emptyList();
        if (cell.candidates != null) return cell.candidates;
        List<ItemStack> result = declaredCandidates(s, cell);
        HologramArea area = AREAS.get(s.player.getUniqueID());
        if (area != null && !s.areaStructureId.isEmpty()) for (String id : cell.memberships()) {
            if (id.equals(s.areaStructureId)) continue;
            Session other = areaDeclarationSession(s, area, id);
            HologramCapture.Cell shared = other == null || other.capture == null ? null
                : other.capture.positions.get(position(cell));
            if (shared == null || other.capture.incomplete || other.capture.pending) {
                result.clear();
                cell.candidateComplete = false;
                cell.candidateBoundary = "共享格的成员结构声明不完整，目标编辑已保护";
                break;
            }
            List<ItemStack> allowed = declaredCandidates(other, shared);
            if (!shared.candidateComplete) {
                cell.candidateComplete = false;
                cell.candidateBoundary = "共享格的成员声明未完整解析，仅提供已知候选交集";
            }
            result.removeIf(
                target -> allowed.stream()
                    .noneMatch(option -> sameMaterial(option, target)));
        }
        cell.candidates = result;
        return result;
    }

    private static Session areaDeclarationSession(Session s, HologramArea area, String id) {
        HologramArea.Structure structure = area.structures.get(id);
        if (structure == null || structure.capture == null
            || s.player.worldObj.getTileEntity(structure.x, structure.y, structure.z)
                != structure.machine.getBaseMetaTileEntity())
            return null;
        Map<String, Session> sessions = AREA_STRUCTURES
            .computeIfAbsent(s.player.getUniqueID(), ignored -> new HashMap<>());
        Session cached = sessions.get(id);
        if (cached != null && cached.capture == structure.capture) return cached;
        Session source = new Session(s.player, structure.x, structure.y, structure.z, true, s.side);
        source.areaStructureId = id;
        source.main = structure.capture.trigger.stackSize;
        source.mainExplicit = false;
        source.channels = new NBTTagCompound();
        ChannelDataAccessor.iterateChannelData(structure.capture.trigger)
            .forEach(entry -> source.channels.setInteger(entry.getKey(), entry.getValue()));
        source.capture = structure.capture;
        sessions.put(id, source);
        return source;
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> declaredCandidates(Session s, HologramCapture.Cell cell) {
        ItemStack signal = elementTrigger(s, cell);
        ChannelDataAccessor.setChannelData(signal, "gt_hatch", 1);
        long generation = HologramDeclarationAdapters.generation();
        if (s.declarationGeneration != generation || s.declarationCandidateCount >= 1024) {
            s.declarationCandidates.clear();
            s.declarationCandidateCount = 0;
            s.pureDeclarations.clear();
            s.declarationGeneration = generation;
        }
        boolean pure = s.pureDeclarations.computeIfAbsent(cell.element, HologramDeclarationPurity::contextFree);
        String signalKey = (pure ? "pure:" : position(cell) + ":" + s.areaStructureId + ":") + signal.stackSize
            + ":"
            + signal.getTagCompound();
        Map<String, HologramDeclarationCandidates> signals = s.declarationCandidates
            .computeIfAbsent(cell.element, ignored -> new HashMap<>());
        HologramDeclarationCandidates declaration = signals.get(signalKey);
        if (declaration == null) {
            declaration = new HologramDeclarationCandidates();
            declaration.collect(
                cell.element,
                s.machine,
                s.player.worldObj,
                cell.x,
                cell.y,
                cell.z,
                signal,
                AutoPlaceEnvironment.fromLegacy(
                    (predicate, simulate, count) -> materialSource(s).take(predicate, true, count),
                    s.player,
                    message -> {}),
                0);
            signals.put(signalKey, declaration);
            s.declarationCandidateCount++;
        }
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : declaration.stacks) {
            Block block = Block.getBlockFromItem(stack.getItem());
            if (!s.noHatches || (block != Blocks.air && (!block.hasTileEntity(stack.getItemDamage())
                || HologramFrameSupport.inertFrameBlock(block, stack.getItemDamage())))) result.add(stack);
        }
        cell.candidateComplete = declaration.complete;
        cell.candidateBoundary = declaration.complete ? "" : "仅列出已识别的结构声明；自定义校验或动态条件未完整解析";
        return result;
    }

    @SuppressWarnings("unchecked")
    private static IStructureElement hatchElement(Session s, HologramCapture.Cell cell, ItemStack sample) {
        return hatchElement(s, cell, sample, true);
    }

    @SuppressWarnings("unchecked")
    private static IStructureElement hatchElement(Session s, HologramCapture.Cell cell, ItemStack sample,
        boolean manual) {
        ItemStack signal = elementTrigger(s, cell);
        ChannelDataAccessor.setChannelData(signal, "gt_hatch", 1);
        return hatchElement(s, cell, sample, manual, signal);
    }

    @SuppressWarnings("unchecked")
    private static IStructureElement hatchElement(Session s, HologramCapture.Cell cell, ItemStack sample,
        boolean manual, ItemStack signal) {
        if (hatchTier(sample) == Integer.MAX_VALUE) return null;
        List<IStructureElement<?>> parts = s.hatchParts.get(cell.element);
        if (parts == null) {
            parts = new ArrayList<>();
            for (IStructureElement<?> part : HologramElementCatalog.parts(cell.element))
                if ("hatch".equals(HologramElementCatalog.hatchRole(part))) parts.add(part);
            s.hatchParts.put(cell.element, parts);
        }
        for (IStructureElement part : parts) {
            try {
                java.util.function.Predicate<ItemStack> declared = manual
                    ? HologramHatchPolicy.predicate(part, s.machine, signal)
                    : null;
                if (declared != null) {
                    if (declared.test(sample)) return part;
                    continue;
                }
                IStructureElement.BlocksToPlace blocks = part.getBlocksToPlace(
                    s.machine,
                    s.player.worldObj,
                    cell.x,
                    cell.y,
                    cell.z,
                    signal,
                    AutoPlaceEnvironment.fromLegacy(
                        (predicate, simulate, count) -> materialSource(s).take(predicate, true, count),
                        s.player,
                        message -> {}));
                if (blocks != null && blocks.getPredicate()
                    .test(sample)) return part;
            } catch (RuntimeException | LinkageError ignored) {}
        }
        return null;
    }

    private static boolean recoverable(Session s, HologramCapture.Cell c) {
        TileEntity tile = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
        if (!HologramRecovery.supported(tile)) return false;
        if (c.anchor) return false;
        int index = cellIndex(s, c);
        if (explicitTarget(s, c, index) && tile instanceof IGregTechTileEntity) return true;
        if (s.mode == 2 && !c.userPosition
            && c.block != null
            && c.block != Blocks.air
            && tile instanceof IGregTechTileEntity) return true;
        if (tile instanceof IGregTechTileEntity
            && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEHatch) {
            int id = ((IGregTechTileEntity) tile).getMetaTileID();
            return hatchElement(s, c, new ItemStack(GregTechAPI.sBlockMachines, 1, id)) != null;
        }
        Block block = s.player.worldObj.getBlock(c.x, c.y, c.z);
        int meta = s.player.worldObj.getBlockMetadata(c.x, c.y, c.z);
        return block instanceof BlockFrameBox && (HologramFrameSupport.acceptsCovered(c.element, block, meta)
            || destructive(c, block, meta & ~BlockFrameBox.MTE_BIT));
    }

    private static boolean removable(Session s, HologramCapture.Cell c) {
        World world = s.player.worldObj;
        if (s.mode == 1) return originalMember(s, c);
        if (world.getTileEntity(c.x, c.y, c.z) != null)
            return recoverable(s, c) && (s.mode != 0 || originalMember(s, c));
        if (s.mode == 0 && s.player.capabilities.isCreativeMode) return !c.anchor;
        int index = cellIndex(s, c);
        return !c.anchor && (explicitTarget(s, c, index)
            || destructive(c, world.getBlock(c.x, c.y, c.z), world.getBlockMetadata(c.x, c.y, c.z)));
    }

    private static void setBlueprintTarget(Session s, HologramCapture.Cell cell, ItemStack target) {
        if (!s.virtual || s.virtualPreset == null || s.virtualPreset.schema < 2 || target == null) return;
        Block block = Block.getBlockFromItem(target.getItem());
        if (HologramNativePlacement.supported(cell.block)) {
            try {
                if (HologramNativePlacement
                    .matchesSample(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt), target))
                    block = cell.block;
            } catch (java.io.IOException invalid) {
                // Changing an unsupported draft still uses the normal replacement reset below.
            }
        }
        String item = String.valueOf(Item.itemRegistry.getNameForObject(target.getItem()));
        if (cell.block != block || (!cell.blueprintItem.isEmpty()
            && (!cell.blueprintItem.equals(item) || cell.blueprintDamage != target.getItemDamage()))) {
            cell.tileNbt = "";
            cell.itemNbt = "";
            cell.coverCostsNbt = "";
            s.nbtKeysCapture = null;
            cell.coverMaterials = Collections.emptyList();
            cell.invalidCoverCosts = false;
            cell.block = block;
            cell.meta = target.getItem()
                .getMetadata(target.getItemDamage());
        }
        cell.blueprintItem = item;
        cell.blueprintDamage = target.getItemDamage();
        cell.blueprintBlock = String.valueOf(Block.blockRegistry.getNameForObject(block));
        cell.unknown = false;
    }

    private static boolean acceptableSample(Session s, HologramCapture.Cell cell, ItemStack sample) {
        if (!placeableSample(s, cell, sample)) return false;
        if ("templateController".equals(cell.role))
            return sample.getItem() instanceof ItemMachines && sample.getItemDamage() == cell.blueprintDamage;
        if (cell.structureTargets != null || (!s.areaStructureId.isEmpty() && !cell.memberships()
            .isEmpty())) return candidates(s, cell).stream()
                .anyMatch(option -> sameMaterial(option, sample));
        return true;
    }

    private static boolean placeableSample(Session s, HologramCapture.Cell cell, ItemStack sample) {
        if (cell.anchor || sample == null || sample.getItem() == null || sample.stackSize < 1) return false;
        if (sample.getItemDamage() < 0) return false;
        if (s.virtual && (s.virtualPreset == null || (s.virtualPreset.schema < 2 && !s.virtualPreset.allows(sample))))
            return false;
        if (s.virtual && s.virtualPreset.schema < 2
            && "templateController".equals(cell.role)
            && (!(sample.getItem() instanceof ItemMachines) || sample.getItemDamage() != s.virtualPreset.controllerId))
            return false;
        if (sample.getItem() instanceof ItemMachines) {
            int id = sample.getItemDamage();
            return id >= 0 && id < GregTechAPI.METATILEENTITIES.length && GregTechAPI.METATILEENTITIES[id] != null;
        }
        if (s.virtual && s.virtualPreset.schema >= 2 && HologramNativePlacement.supported(cell.block)) {
            try {
                return HologramNativePlacement
                    .matchesSample(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt), sample);
            } catch (java.io.IOException invalid) {
                return false;
            }
        }
        Block block = Block.getBlockFromItem(sample.getItem());
        return sample.getItem() instanceof ItemBlock && block != Blocks.air
            && (!s.virtual || s.virtualPreset.schema >= 2
                || !block.hasTileEntity(
                    sample.getItem()
                        .getMetadata(sample.getItemDamage())));
    }

    private static int hatchTier(ItemStack stack) {
        if (stack == null) return Integer.MAX_VALUE;
        int id = stack.getItemDamage();
        return stack.getItem() instanceof ItemMachines && id >= 0
            && id < GregTechAPI.METATILEENTITIES.length
            && GregTechAPI.METATILEENTITIES[id] instanceof MTEHatch
                ? ((MTEHatch) GregTechAPI.METATILEENTITIES[id]).mTier
                : Integer.MAX_VALUE;
    }

    private static Map<gregtech.api.interfaces.IHatchElement<?>, Integer> hatchRequirements(Session s,
        HologramCapture.Cell c) {
        Map<gregtech.api.interfaces.IHatchElement<?>, Integer> cached = s.hatchRequirements.get(c.element);
        if (cached != null) return cached;
        Map<gregtech.api.interfaces.IHatchElement<?>, Integer> result = new LinkedHashMap<>();
        for (IStructureElement<?> part : HologramElementCatalog.parts(c.element)) HologramHatchPolicy.requirements(part)
            .forEach((type, count) -> result.merge(type, count, Math::max));
        s.hatchRequirements.put(c.element, result);
        return result;
    }

    private static String candidateRole(Session s, HologramCapture.Cell c, ItemStack stack) {
        if (hatchTier(stack) == Integer.MAX_VALUE) return "外壳/方块";
        gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(stack);
        for (IStructureElement<?> part : HologramElementCatalog.parts(c.element))
            for (gregtech.api.interfaces.IHatchElement<?> type : HologramHatchPolicy.declared(part))
                if (HologramHatchPolicy.matchesHatch(type, prototype)) return type.getDisplayName();
        return "仓室";
    }

    private static int[] legalFacings(Session s, HologramCapture.Cell c, ItemStack target) {
        if (c.anchor || target == null
            || (!(s.virtual && target.getItem() instanceof ItemMachines) && hatchTier(target) == Integer.MAX_VALUE))
            return new int[0];
        gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(target);
        if (prototype instanceof gregtech.api.metatileentity.MetaPipeEntity) return new int[0];
        IStructureElement<?> part = s.virtual ? null : hatchElement(s, c, target, true);
        List<Integer> result = new ArrayList<>();
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
            if (prototype != null && prototype.isFacingValid(direction)
                && (part == null || HologramHatchPolicy.allowed(part, s.machine.getExtendedFacing(), direction)))
                result.add(direction.ordinal());
        int[] values = new int[result.size()];
        for (int i = 0; i < values.length; i++) values[i] = result.get(i);
        return values;
    }

    private static boolean orientationOnly(Session s, HologramCapture.Cell c, int index) {
        return !c.anchor && s.facingPins.containsKey(position(c))
            && s.player.worldObj.getTileEntity(c.x, c.y, c.z) instanceof IGregTechTileEntity
            && targetMatches(
                s,
                c,
                index,
                s.player.worldObj.getBlock(c.x, c.y, c.z),
                s.player.worldObj.getBlockMetadata(c.x, c.y, c.z))
            && (!HologramRecovery.hasSeal(targetFor(s, c, index)) || HologramRecovery.matchesPlaced(
                s.player.worldObj,
                c.x,
                c.y,
                c.z,
                targetFor(s, c, index),
                ((IGregTechTileEntity) s.player.worldObj.getTileEntity(c.x, c.y, c.z)).getFrontFacing()
                    .ordinal()));
    }

    private static void refreshFacings(Session s) {
        s.planFacings.clear();
        List<int[]> cells = new ArrayList<>();
        for (HologramCapture.Cell c : s.capture.cells)
            cells.add(new int[] { c.x, c.y, c.z, c.block == Blocks.air && !c.anchor ? 0 : 1 });
        java.util.Set<String> exterior = HologramHatchGeometry.exterior(
            cells,
            (x, y, z) -> s.player.worldObj.blockExists(x, y, z) && trivial(s.player.worldObj, x, y, z));
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            ItemStack target = draftTarget(s, c, i);
            if (target != null && target.getItem() instanceof ItemMachines
                && ItemMachines.getMetaTileEntity(target) instanceof gregtech.api.metatileentity.MetaPipeEntity) {
                s.facingPins.remove(position(c));
                continue;
            }
            int[] legal = legalFacings(s, c, target);
            if (legal.length == 0) continue;
            Integer pin = s.facingPins.get(position(c));
            if (pin != null) {
                for (int side : legal) if (side == pin) s.planFacings.put(i, pin);
                continue;
            }
            TileEntity actual = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            if (actual instanceof IGregTechTileEntity && target != null
                && !HologramRecovery.hasSeal(target)
                && ((IGregTechTileEntity) actual).getMetaTileID() == target.getItemDamage()) {
                int front = HologramBlockIdentity.front((IGregTechTileEntity) actual);
                if (front >= 0) s.planFacings.put(i, front);
                continue;
            }
            if (HologramRecovery.hasSeal(target)) continue;
            int direction = HologramHatchGeometry.choose(c.x, c.y, c.z, legal, exterior);
            if (direction >= 0) s.planFacings.put(i, direction);
        }
    }

    /** Freeze a whole-machine target list; quotas and inventory are shared across every position. */
    private static void buildPreset(Session s) {
        s.autoTargets.clear();
        if (s.allocationsFrozen) {
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                ItemStack target = s.frozenAutoTargets.get(position(cell));
                if (!cell.anchor && !s.pins.containsKey(i) && acceptableSample(s, cell, target))
                    s.autoTargets.put(i, target.copy());
            }
            return;
        }
        s.presetSupported = false;
        s.presetAvailable = false;
        s.presetReason = "此定义未声明完整仓室配额，不能保证自动成型";
        if (s.virtual || s.mode == 2) {
            s.presetReason = "拆除独立使用原结构范围，保留仓室预设设置供建造或替换使用";
            return;
        }
        Map<String, gregtech.api.interfaces.IHatchElement<?>> types = new LinkedHashMap<>();
        Map<String, Integer> quotas = new LinkedHashMap<>();
        Map<String, Integer> reserved = new HashMap<>();
        for (HologramCapture.Cell c : s.capture.cells) hatchRequirements(s, c).forEach((type, count) -> {
            types.putIfAbsent(type.name(), type);
            quotas.merge(type.name(), count, Math::max);
        });
        if (types.isEmpty()) return;
        s.presetAvailable = true;
        if (s.presetPaused) {
            s.presetReason = "预设已撤销；选择电压级后可重新生成";
            return;
        }
        if (s.noHatches || !(s.presetEnabled || s.channels.hasKey("gt_hatch", 3))) {
            s.presetReason = "选择电压级后生成目标；保留已有仓室与手动指定";
            return;
        }
        // Count legal existing devices and explicit targets without changing either of them.
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell c = s.capture.cells.get(i);
            if (c.anchor) continue;
            ItemStack stack = s.pins.get(i);
            TileEntity actual = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            if (stack == null && actual instanceof IGregTechTileEntity)
                stack = new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) actual).getMetaTileID());
            if (stack == null || hatchElement(s, c, stack, true) == null) continue;
            if (s.pins.containsKey(i) && !(actual instanceof IGregTechTileEntity))
                reserved.merge(stackKey(stack), 1, Integer::sum);
            gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(stack);
            for (Map.Entry<String, gregtech.api.interfaces.IHatchElement<?>> type : types.entrySet())
                if (HologramHatchPolicy.matchesHatch(type.getValue(), prototype))
                    quotas.computeIfPresent(type.getKey(), (key, n) -> Math.max(0, n - 1));
        }
        List<String> ordered = new ArrayList<>(types.keySet());
        ordered.sort((a, b) -> Integer.compare(presetPositions(s, types.get(a)), presetPositions(s, types.get(b))));
        int unfilled = 0;
        for (String name : ordered) for (int remaining = quotas.get(name); remaining > 0; remaining--) {
            gregtech.api.interfaces.IHatchElement<?> type = types.get(name);
            ItemStack chosen = null;
            int chosenIndex = -1;
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell c = s.capture.cells.get(i);
                if (!presetPosition(s, c, i, type)) continue;
                List<ItemStack> options = new ArrayList<>();
                for (ItemStack option : candidates(s, c)) if (hatchTier(option) != Integer.MAX_VALUE
                    && HologramHatchPolicy.matchesHatch(type, ItemMachines.getMetaTileEntity(option)))
                    options.add(option);
                ItemStack pick = presetCandidate(s, options, reserved);
                if (pick == null) continue;
                if (chosen == null || hatchTier(pick) > hatchTier(chosen)) {
                    chosen = pick;
                    chosenIndex = i;
                }
                if (hatchTier(pick) == s.hatchTier) break;
            }
            if (chosen == null) {
                unfilled++;
                continue;
            }
            s.autoTargets.put(chosenIndex, chosen.copy());
            reserved.merge(stackKey(chosen), 1, Integer::sum);
        }
        s.presetSupported = unfilled == 0;
        s.presetReason = unfilled == 0 ? "已按声明配额生成仓室预设；已有仓室与手选目标保留，额外成型条件仍由原机检查"
            : "部分声明仓室没有可放置位置或对应等级候选，不能保证自动成型（缺 " + unfilled + " 格）";
    }

    private static boolean presetPosition(Session s, HologramCapture.Cell c, int i,
        gregtech.api.interfaces.IHatchElement<?> type) {
        if (c.anchor || c.userPosition
            || explicitTarget(s, c, i)
            || s.autoTargets.containsKey(i)
            || s.suppressedAuto.contains(position(c))
            || s.player.worldObj.getTileEntity(c.x, c.y, c.z) != null) return false;
        boolean declared = false;
        for (gregtech.api.interfaces.IHatchElement<?> allowed : hatchRequirements(s, c).keySet()) if (allowed.name()
            .equals(type.name())) declared = true;
        return declared && (trivial(s.player.worldObj, c.x, c.y, c.z) || s.mode == 1 && destructive(
            c,
            s.player.worldObj.getBlock(c.x, c.y, c.z),
            s.player.worldObj.getBlockMetadata(c.x, c.y, c.z)));
    }

    private static int presetPositions(Session s, gregtech.api.interfaces.IHatchElement<?> type) {
        int count = 0;
        for (int i = 0; i < s.capture.cells.size(); i++)
            if (presetPosition(s, s.capture.cells.get(i), i, type)) count++;
        return count;
    }

    private static ItemStack presetCandidate(Session s, List<ItemStack> options, Map<String, Integer> reserved) {
        int[] tiers = new int[options.size()], available = new int[options.size()];
        for (int i = 0; i < options.size(); i++) {
            ItemStack option = options.get(i);
            tiers[i] = hatchTier(option);
            available[i] = s.player.capabilities.isCreativeMode ? 1
                : materialSource(s).count(option, Math.max(4096, s.capture.cells.size()))
                    - reserved.getOrDefault(stackKey(option), 0);
        }
        int chosen = HologramHatchPreset.choose(tiers, available, s.hatchTier, s.hatchDowngrade);
        return chosen < 0 ? null : options.get(chosen);
    }

    private static boolean virtualMoving(Session s) {
        return s.virtual && s.virtualPlacement && s.virtualPreset != null && !s.virtualLocked && s.job != 4;
    }

    private static void dismissPresetBrowse(Session s) {
        if (s.presetBrowseForPlacement && s.virtualPreset == null) s.virtualPlacement = false;
        s.presetBrowseForPlacement = false;
        if (!s.virtualPlacement || !s.virtualLocked) {
            s.blueprintAwaitingConfirmation = false;
            s.placementReviewIssued = false;
        }
    }

    private static void send(Session s) {
        // Partial batches are server-only. Keep the original open intent until a complete snapshot exists.
        if (s.capturePending) return;
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("open", s.open);
        state.setString("openPurpose", s.openPurpose);
        state.setLong("placementReviewNonce", s.placementReviewNonce);
        s.openPurpose = "";
        state.setInteger("hatchTier", s.hatchTier);
        state.setInteger("hatchTierMax", gregtech.api.enums.GTValues.VN.length - 1);
        state.setBoolean("hatchDowngrade", s.hatchDowngrade);
        state.setBoolean("presetSupported", s.presetSupported);
        state.setBoolean("presetAvailable", s.presetAvailable);
        state.setBoolean("presetActive", s.presetEnabled || !s.autoTargets.isEmpty());
        boolean editable = s.job != 1 && s.job != 2 && !s.capturePending && s.capture != null && !s.capture.incomplete;
        state.setBoolean("editCanUndo", editable && !s.undoEdits.isEmpty());
        state.setBoolean("editCanRedo", editable && !s.redoEdits.isEmpty());
        state.setBoolean("editCanPaste", editable && (s.editClipboard != null || !s.blueprintClipboard.isEmpty()));
        state.setString("presetReason", s.presetReason);
        state.setBoolean("creativeMaterials", s.player.capabilities.isCreativeMode);
        s.open = false;
        state.setString("session", s.id);
        state.setString("generation", s.id);
        state.setBoolean("capturePending", s.capturePending);
        state.setInteger("captureAttempts", s.captureAttempts);
        state.setLong("captureRetryDue", s.captureRetryDue);
        state.setLong("planRevision", s.planRevision);
        aeSnapshot(s, state);
        state.setLong("uiSequence", s.uiSequence);
        state.setString("phase", s.phase);
        state.setIntArray("pending", new int[0]);
        state.setIntArray(
            "ackIndices",
            s.ackIndices.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        state.setIntArray(
            "ackSuccesses",
            s.ackSuccesses.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        state.setInteger("layerOrdinal", 0);
        state.setInteger("layerCount", 0);
        state.setInteger("layerCompleted", s.completed);
        state.setInteger("layerTotal", s.work.size());
        state.setInteger("layerY", s.y);
        state.setInteger("layerElapsed", 0);
        state.setInteger("layerLeadRemaining", 0);
        state.setInteger("layerDuration", 0);
        state.setInteger("layerGap", 0);
        state.setLong("due", 0);
        state.setLong("serverTick", s.player.worldObj.getTotalWorldTime());
        state.setLong("batchSeq", s.batchSeq);
        state.setIntArray("ack", s.ackIndex < 0 ? new int[0] : new int[] { s.ackIndex });
        state.setBoolean("ackSuccess", s.ackSuccess);
        state.setBoolean("recoveryFits", s.planRecoveryFits);
        state.setString(
            "recoveryDestinationLabel",
            s.materialSettings.recoveryDestination == 1 ? "优先 ME；余量回背包或持久暂存" : "主背包；满时持久暂存");
        state.setBoolean("formationChecked", false);
        state.setBoolean("structureBuilt", structureBuilt(s));
        state.setTag("capabilities", s.capabilities == null ? new NBTTagList() : s.capabilities.toNBT());
        state.setBoolean("target", s.target != null || s.virtual && s.capture != null);
        state.setBoolean("virtual", s.virtual);
        state.setBoolean("virtualPlacement", s.virtualPlacement);
        state.setBoolean("wholeBlueprintPlacement", wholeBlueprintPlacement(s));
        state.setBoolean("presetBrowseForPlacement", s.presetBrowseForPlacement);
        state.setBoolean("virtualLocked", s.virtualLocked);
        state.setString(
            "virtualPresetId",
            s.virtualPreset == null || s.virtualPreset.id == null ? "" : s.virtualPreset.id);
        state.setString("virtualPresetName", s.virtualPreset == null ? "" : s.virtualPreset.name);
        state.setBoolean("moving", virtualMoving(s));
        state.setBoolean("settingsOnly", s.settingsOnly);
        state.setBoolean("cancelled", s.cancelled);
        state.setString("selectedPresetId", selectedPreset(s));
        ItemStack heldDraft = currentTool(s);
        state.setBoolean(
            "draftAvailable",
            heldDraft != null && draftMatches(
                s,
                heldDraft.getTagCompound()
                    .getCompoundTag(DRAFT)));
        try {
            state.setTag("presets", HologramPresets.labels());
        } catch (java.io.IOException invalid) {
            state.setTag("presets", new NBTTagList());
            state.setString("presetConfigError", invalid.getMessage());
        }
        state.setBoolean("targetClosed", false);
        state.setInteger("x", s.x);
        state.setInteger("y", s.y);
        state.setInteger("z", s.z);
        state.setInteger("dimension", s.dimension);
        state.setInteger("side", s.side);
        state.setString("title", s.machine == null ? "全息构造器" : s.machine.getLocalName());
        state.setInteger("main", s.main);
        state.setTag("channels", s.channels.copy());
        state.setBoolean("noHatches", s.noHatches);
        state.setBoolean("keepController", !s.virtual);
        NBTTagList materialChoices = new NBTTagList();
        for (ItemStack sample : s.materialChoices) materialChoices.appendTag(sample.writeToNBT(new NBTTagCompound()));
        state.setTag("materialChoices", materialChoices);
        state.setBoolean("materialMain", s.materialSettings.main);
        state.setBoolean("materialContainers", s.materialSettings.containers);
        state.setBoolean("materialMe", s.materialSettings.me);
        state.setInteger("materialPriority", s.materialSettings.priority);
        state.setInteger("recoveryDestination", s.materialSettings.recoveryDestination);
        state.setInteger("facing", s.facing);
        state.setInteger("mode", s.mode);
        state.setInteger("scope", s.scope);
        state.setInteger("layer", s.layer);
        state.setInteger("selected", s.selected);
        state.setString("status", s.status);
        state.setInteger("job", s.job);
        state.setInteger("completed", s.completed);
        state.setBoolean("jobPartialPlacement", s.jobPartialPlacement);
        state.setInteger("jobMissingCells", s.jobMissingCells);
        state.setInteger("jobProtectedCells", s.jobProtectedCells);
        state.setInteger("jobUnsupportedCells", s.jobUnsupportedCells);
        state.setInteger("jobSkippedCells", s.jobSkippedCells);
        state.setBoolean("hints", s.hints);
        s.hints = false;
        state.setIntArray("facings", s.machine == null ? new int[0] : new int[] { s.facing });
        state.setBoolean("supported", s.capture != null && !s.capture.incomplete);
        state.setBoolean(
            "geometryEditable",
            s.virtual && s.virtualPreset != null
                && s.virtualPreset.schema >= 2
                && s.capture != null
                && !s.capture.incomplete
                && !s.capture.pending
                && !s.capturePending
                && s.player.capabilities.allowEdit
                && s.job != 1
                && s.job != 2);
        state.setInteger("virtualSchema", s.virtualPreset == null ? 0 : s.virtualPreset.schema);
        state.setString(
            "unsupportedReason",
            s.capture == null || s.capture.incomplete
                ? s.status + (s.capture == null || s.capture.failure.isEmpty() ? "" : " (" + s.capture.failure + ")")
                : "");
        writeBlueprintState(s, state);
        NBTTagList cells = new NBTTagList(), materials = new NBTTagList(), recovery = new NBTTagList();
        int protectedCount = 0, missing = 0, total = 0, workTotal = 0;
        int recoveryRows = 0, recoveryItems = 0;
        Map<String, NBTTagCompound> requirements = new LinkedHashMap<>();
        if (s.capture != null) for (int index = 0; index < s.capture.cells.size(); index++) {
            HologramCapture.Cell c = s.capture.cells.get(index);
            World world = s.player.worldObj;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", c.x);
            row.setInteger("y", c.y);
            row.setInteger("z", c.z);
            row.setInteger("dx", c.x - s.x);
            row.setInteger("dy", c.y - s.y);
            row.setInteger("dz", c.z - s.z);
            row.setBoolean("sourceMissing", !c.sourceWarning.isEmpty());
            row.setString("sourceClass", c.sourceClass);
            row.setString("sourceWarning", c.sourceWarning);
            row.setBoolean("missingBlock", c.unknown && targetFor(s, c, index) == null);
            String sourceRegistry = c.blueprintBlock.isEmpty() ? c.blueprintItem : c.blueprintBlock;
            String sourceMod = sourceRegistry.contains(":") ? sourceRegistry.substring(0, sourceRegistry.indexOf(':'))
                : "";
            row.setString("sourceMod", sourceMod);
            row.setString(
                "sourceVersion",
                s.virtualPreset == null ? "" : s.virtualPreset.modVersions.getOrDefault(sourceMod, ""));
            row.setBoolean("excluded", c.excluded);
            row.setString("prompt", c.prompt);
            row.setString("structureId", c.structureId);
            row.setTag("structureIds", nbtKeys(c.memberships()));
            row.setBoolean("anchor", c.anchor);
            row.setBoolean("hasPin", s.pins.containsKey(index));
            row.setBoolean("templateController", "templateController".equals(c.role));
            row.setBoolean("hasTargetOverride", s.pins.containsKey(index) || s.autoTargets.containsKey(index));
            row.setBoolean("hasFacingPin", s.facingPins.containsKey(position(c)));
            row.setInteger("hatchFacing", s.planFacings.getOrDefault(index, -1));
            row.setInteger("targetFacing", c.anchor ? c.actualFacing : s.planFacings.getOrDefault(index, -1));
            TileEntity actualTile = world.getTileEntity(c.x, c.y, c.z);
            try {
                c.actualDisplay = HologramDisplaySnapshot.capture(actualTile);
            } catch (RuntimeException | LinkageError ignored) {
                c.actualDisplay = new NBTTagCompound();
            }
            row.setTag("actualDisplay", c.actualDisplay.copy());
            row.setInteger(
                "actualFacing",
                actualTile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) actualTile).getFrontFacing()
                    .ordinal() : -1);
            row.setIntArray("hatchFacings", legalFacings(s, c, targetFor(s, c, index)));
            row.setBoolean("userPosition", c.userPosition);
            row.setBoolean("iconOnly", c.iconOnly);
            row.setString("role", c.role);
            row.setString("family", c.family);
            row.setString("renderKind", c.renderKind);
            if (c.actualStack != null) row.setTag("actualStack", c.actualStack.writeToNBT(new NBTTagCompound()));
            NBTTagList icons = new NBTTagList();
            if (c.hintIcons != null)
                for (String icon : c.hintIcons) icons.appendTag(new NBTTagString(icon == null ? "" : icon));
            row.setTag("hintIcons", icons);
            if (c.hintTint != null) {
                int[] tint = new int[c.hintTint.length];
                for (int i = 0; i < tint.length; i++) tint[i] = c.hintTint[i];
                row.setIntArray("hintTint", tint);
            }
            row.setInteger("hintIndex", c.hintIndex);
            row.setBoolean("inScope", inScope(s, c, index));
            row.setBoolean("explicitTarget", explicitTarget(s, c, index) || explicitGrade(s, c));
            row.setBoolean(
                "autoTarget",
                !s.noHatches && s.channels.hasKey("gt_hatch", 3)
                    && "hatch".equals(c.role)
                    && !explicitTarget(s, c, index) || s.autoTargets.containsKey(index));
            boolean loaded = world.blockExists(c.x, c.y, c.z);
            Block current = loaded ? world.getBlock(c.x, c.y, c.z) : Blocks.air;
            int meta = loaded ? world.getBlockMetadata(c.x, c.y, c.z) : 0;
            row.setString("id", String.valueOf(Block.blockRegistry.getNameForObject(current)));
            row.setInteger("meta", meta);
            row.setString(
                "wantId",
                c.block == null ? c.blueprintBlock : String.valueOf(Block.blockRegistry.getNameForObject(c.block)));
            row.setInteger("wantMeta", c.meta);
            ItemStack pinned = targetFor(s, c, index);
            row.setString(
                "exactIdentity",
                pinned == null ? HologramBlockIdentity.key(c.block, c.meta) : HologramBlockIdentity.key(pinned));
            // Resolve the exact same wanted block used by this DTO before selecting its detached native display.
            if (pinned != null) {
                Block pinnedBlock = Block.getBlockFromItem(pinned.getItem());
                if (pinnedBlock != Blocks.air) {
                    row.setString("wantId", String.valueOf(Block.blockRegistry.getNameForObject(pinnedBlock)));
                    row.setInteger(
                        "wantMeta",
                        s.virtual && s.virtualPreset.schema >= 2 && c.block == pinnedBlock ? c.meta
                            : pinned.getItem()
                                .getMetadata(pinned.getItemDamage()));
                }
            }
            Block wantedBlock = Block.getBlockFromName(row.getString("wantId"));
            int displayFacing = c.anchor ? c.actualFacing : s.planFacings.getOrDefault(index, -1);
            String previewTile = "", previewError = "";
            try {
                previewTile = targetPreviewTile(s, c);
            } catch (java.io.IOException invalid) {
                previewError = "蓝图目标预览NBT无效：" + HologramAePolicy.fallback(invalid.getMessage());
                row.setBoolean("previewInvalid", true);
                row.setString("previewError", previewError);
            }
            c.targetDisplay = HologramDisplaySnapshot.target(
                wantedBlock,
                pinned,
                displayFacing,
                c.element,
                previewTile,
                !s.virtual || placementRetainCovers(s));
            if (!s.virtual && pinned != null
                && actualTile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) actualTile).getMetaTileID() == pinned.getItemDamage()) {
                c.targetDisplay = (NBTTagCompound) c.actualDisplay.copy();
                if (displayFacing >= 0) c.targetDisplay.setInteger("facing", displayFacing);
            }
            row.setTag("targetDisplay", c.targetDisplay.copy());
            if (pinned != null) row.setTag("targetStack", pinned.writeToNBT(new NBTTagCompound()));
            if (pinned != null && pinned.getItem() instanceof ItemMachines && loaded && trivial(world, c.x, c.y, c.z))
                row.setString("renderKind", "item");
            if (s.mode == 2 && loaded && (removable(s, c) || "removed".equals(c.status))) {
                row.setString("wantId", "minecraft:air");
                row.setInteger("wantMeta", 0);
            }
            row.setString("status", c.status);
            row.setString("reason", previewError.isEmpty() ? reason(s, c, index) : previewError);
            row.setString(
                "operation",
                !inScope(s, c, index) ? "OUTSIDE"
                    : "satisfied".equals(c.status) ? "KEEP"
                        : "protected".equals(c.status) || "unsupported".equals(c.status) || "blocked".equals(c.status)
                            ? "PROTECT"
                            : s.mode == 2 ? "REMOVE" : loaded && trivial(world, c.x, c.y, c.z) ? "PLACE" : "REPLACE");
            if (inScope(s, c, index)
                && ("protected".equals(c.status) || "unsupported".equals(c.status) || "blocked".equals(c.status)))
                protectedCount++;
            if (inScope(s, c, index)) total++;
            if (inScope(s, c, index) && index < s.planStatus.size() && "pending".equals(s.planStatus.get(index)))
                workTotal++;
            NBTTagList choices = new NBTTagList();
            List<ItemStack> options = index == s.selected ? candidates(s, c) : Collections.emptyList();
            int chosenChoice = -1;
            for (int choice = 0; choice < options.size(); choice++) if (same(pinned, options.get(choice))) {
                chosenChoice = choice;
                break;
            }
            row.setInteger("chosenChoice", chosenChoice);
            row.setString("pinScope", "target");
            for (ItemStack option : options) {
                NBTTagCompound tag = new NBTTagCompound();
                option.writeToNBT(tag);
                choices.appendTag(tag);
            }
            row.setTag("candidates", choices);
            row.setBoolean("candidateComplete", c.candidateComplete);
            row.setString("candidateBoundary", c.candidateBoundary);
            NBTTagList candidateRoles = new NBTTagList();
            int[] candidateTiers = new int[options.size()], candidateAvailable = new int[options.size()];
            for (int n = 0; n < options.size(); n++) {
                ItemStack option = options.get(n);
                candidateRoles.appendTag(new NBTTagString(candidateRole(s, c, option)));
                candidateTiers[n] = hatchTier(option) == Integer.MAX_VALUE ? -1 : hatchTier(option);
                candidateAvailable[n] = materialSource(s).count(option, Math.max(4096, s.capture.cells.size()));
            }
            row.setTag("candidateRoles", candidateRoles);
            row.setIntArray("candidateTiers", candidateTiers);
            row.setIntArray("candidateAvailable", candidateAvailable);
            cells.appendTag(row);
            if (!"satisfied".equals(c.status) && !"protected".equals(c.status)
                && !"unsupported".equals(c.status)
                && !"placed".equals(c.status)
                && !"replaced".equals(c.status)
                && pinned != null
                && !orientationOnly(s, c, index)
                && inScope(s, c, index)
                && s.mode != 2
                && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(pinned))) {
                ItemStack stack = pinned.copy();
                stack.stackSize = 1;
                String key = stackKey(stack);
                NBTTagCompound req = requirements.get(key);
                if (req == null) {
                    req = new NBTTagCompound();
                    stack.writeToNBT(req);
                    req.setInteger("available", materialSource(s).count(stack, Math.max(4096, s.capture.cells.size())));
                    req.setInteger(
                        "mainAvailable",
                        materialSource(s).mainCount(stack, Math.max(4096, s.capture.cells.size())));
                    req.setInteger(
                        "controllerAvailable",
                        s.target instanceof net.minecraft.inventory.IInventory
                            && ((net.minecraft.inventory.IInventory) s.target).isUseableByPlayer(s.player)
                                ? HologramMaterials.controllerCount(
                                    (net.minecraft.inventory.IInventory) s.target,
                                    stack,
                                    Math.max(4096, s.capture.cells.size()))
                                : -1);
                    requirements.put(key, req);
                }
                req.setInteger("required", req.getInteger("required") + 1);
            }
            if (inScope(s, c, index) && "pending".equals(c.status)
                && s.mode != 2
                && !s.player.capabilities.isCreativeMode) for (ItemStack cover : coverCosts(s, c)) {
                    String key = stackKey(cover);
                    NBTTagCompound requirement = requirements.get(key);
                    if (requirement == null) {
                        requirement = cover.writeToNBT(new NBTTagCompound());
                        requirement.setInteger(
                            "available",
                            materialSource(s).count(cover, Math.max(4096, s.capture.cells.size() * 6)));
                        requirement.setInteger(
                            "mainAvailable",
                            materialSource(s).mainCount(cover, Math.max(4096, s.capture.cells.size() * 6)));
                        requirement.setInteger("controllerAvailable", -1);
                        requirements.put(key, requirement);
                    }
                    requirement.setInteger("required", requirement.getInteger("required") + 1);
                }
            if (inScope(s, c, index) && "pending".equals(c.status)
                && !orientationOnly(s, c, index)
                && loaded
                && !trivial(world, c.x, c.y, c.z)
                && (!s.player.capabilities.isCreativeMode || world.getTileEntity(c.x, c.y, c.z) != null)) try {
                    for (ItemStack drop : drops(current, world, c, meta)) {
                        recoveryRows++;
                        recoveryItems += drop.stackSize;
                        if (recovery.tagCount() < 96) recovery.appendTag(recoverySummary(drop, true));
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    row.setString("reason", "预计掉落无法确定，施工将重验并可暂停");
                }
        }
        if (s.blueprintResources != null && !s.player.capabilities.isCreativeMode)
            for (ItemStack content : s.blueprintResources.items) {
                String key = stackKey(content);
                NBTTagCompound requirement = requirements.get(key);
                if (requirement == null) {
                    ItemStack identity = content.copy();
                    identity.stackSize = 1;
                    requirement = identity.writeToNBT(new NBTTagCompound());
                    requirement
                        .setInteger("available", materialSource(s).count(identity, Math.max(4096, content.stackSize)));
                    requirement.setInteger(
                        "mainAvailable",
                        materialSource(s).mainCount(identity, Math.max(4096, content.stackSize)));
                    requirement.setInteger("controllerAvailable", -1);
                    requirements.put(key, requirement);
                }
                requirement.setInteger("required", requirement.getInteger("required") + content.stackSize);
            }
        for (NBTTagCompound req : requirements.values()) {
            materials.appendTag(req);
            missing += Math.max(0, req.getInteger("required") - req.getInteger("available"));
        }
        state.setTag("cells", cells);
        state.setTag("materials", materials);
        for (ItemStack drop : s.recovered) {
            recoveryRows++;
            recoveryItems += drop.stackSize;
            if (recovery.tagCount() < 96) recovery.appendTag(recoverySummary(drop, false));
        }
        state.setInteger("recoveryMore", Math.max(0, recoveryRows - recovery.tagCount()));
        state.setInteger("recoveryTotal", recoveryItems);
        state.setTag("recovery", recovery);
        state.setInteger("protected", protectedCount);
        state.setInteger("missing", Math.max(missing, s.planMissing));
        state.setInteger("total", total);
        state.setInteger("workTotal", workTotal);
        state.setString("description", "控制器始终保留；直接施工会重新扫描并逐格重验。手选目标覆盖对应位置，不保证结构成型；差分可供查看材料与改动。");
        HologramNetwork.sendState(s.player, state);
    }

    private static NBTTagCompound recoverySummary(ItemStack drop, boolean estimated) {
        ItemStack plain = new ItemStack(drop.getItem(), Math.min(64, drop.stackSize), drop.getItemDamage());
        NBTTagCompound row = plain.writeToNBT(new NBTTagCompound());
        row.setInteger("expected", drop.stackSize);
        row.setBoolean("estimated", estimated);
        row.setBoolean("sealed", HologramRecovery.hasSeal(drop));
        return row;
    }

    /** Coordinate based planning snapshots; world, inventories and clipboard are deliberately outside history. */
    private static final class EditState {

        final List<HologramCapture.Cell> geometry = new ArrayList<>();
        final List<HologramPresets.Structure> structures = new ArrayList<>();
        final List<HologramAePolicy.Issue> aeIssues;
        final Map<String, ItemStack> targets = new HashMap<>();
        final Map<String, ItemStack> automatic = new HashMap<>();
        final java.util.Set<String> selectedPositions = new java.util.LinkedHashSet<>();
        final int mode, scope, selected;
        final boolean presetSupported, presetAvailable;
        final String presetReason;
        final Map<String, Integer> facings;
        final java.util.Set<String> suppressed;
        final java.util.Set<String> virtualEdited;
        final boolean presetEnabled, presetPaused, hatchDowngrade, noHatches;
        final int hatchTier;

        EditState(Session s) {
            aeIssues = HologramAePolicy.copyIssues(s.virtualPreset == null ? null : s.virtualPreset.aeIssues);
            if (s.virtualPreset != null) for (HologramPresets.Structure original : s.virtualPreset.structures)
                structures.add(copyStructure(original));
            if (s.virtual && s.virtualPreset != null && s.virtualPreset.schema >= 2 && s.capture != null)
                for (HologramCapture.Cell cell : s.capture.cells)
                    geometry.add(blueprintCell(cell, cell.x, cell.y, cell.z));
            presetSupported = s.presetSupported;
            presetAvailable = s.presetAvailable;
            presetReason = s.presetReason;
            mode = s.mode;
            scope = s.scope;
            selected = s.selected;
            if (s.capture != null) for (int index : s.selectedIndices) if (index >= 0 && index < s.capture.cells.size())
                selectedPositions.add(position(s.capture.cells.get(index)));
            if (s.capture != null) for (Map.Entry<Integer, ItemStack> entry : s.autoTargets.entrySet()) automatic.put(
                position(s.capture.cells.get(entry.getKey())),
                entry.getValue()
                    .copy());
            for (Map.Entry<String, ItemStack> entry : s.coordinatePins.entrySet()) targets.put(
                entry.getKey(),
                entry.getValue()
                    .copy());
            if (s.capture != null)
                for (int i = 0; i < s.capture.cells.size(); i++) if (s.pins.containsKey(i)) targets.put(
                    position(s.capture.cells.get(i)),
                    s.pins.get(i)
                        .copy());
            facings = new HashMap<>(s.facingPins);
            suppressed = new java.util.HashSet<>(s.suppressedAuto);
            virtualEdited = new java.util.HashSet<>(s.virtualEdited);
            presetEnabled = s.presetEnabled;
            presetPaused = s.presetPaused;
            hatchDowngrade = s.hatchDowngrade;
            noHatches = s.noHatches;
            hatchTier = s.hatchTier;
        }

        boolean sameAs(EditState other) {
            if (geometry.size() != other.geometry.size()) return false;
            for (int i = 0; i < geometry.size(); i++) {
                HologramCapture.Cell a = geometry.get(i), b = other.geometry.get(i);
                if (!position(a).equals(position(b)) || !a.memberships()
                    .equals(b.memberships())
                    || a.block != b.block
                    || a.meta != b.meta
                    || a.blueprintDamage != b.blueprintDamage
                    || !a.blueprintBlock.equals(b.blueprintBlock)
                    || !a.blueprintItem.equals(b.blueprintItem)
                    || a.excluded != b.excluded
                    || !a.prompt.equals(b.prompt)
                    || !a.tileNbt.equals(b.tileNbt)
                    || !a.itemNbt.equals(b.itemNbt)) return false;
            }
            if (!targets.keySet()
                .equals(other.targets.keySet()) || !facings.equals(other.facings)
                || !suppressed.equals(other.suppressed)
                || !virtualEdited.equals(other.virtualEdited)
                || presetEnabled != other.presetEnabled
                || presetPaused != other.presetPaused
                || hatchDowngrade != other.hatchDowngrade
                || noHatches != other.noHatches
                || hatchTier != other.hatchTier
                || mode != other.mode
                || scope != other.scope
                || selected != other.selected
                || !selectedPositions.equals(other.selectedPositions)
                || !automatic.keySet()
                    .equals(other.automatic.keySet()))
                return false;
            for (String key : automatic.keySet()) if (!same(automatic.get(key), other.automatic.get(key))) return false;
            for (String key : targets.keySet()) if (!same(targets.get(key), other.targets.get(key))) return false;
            return true;
        }

        void apply(Session s) {
            if (s.virtualPreset != null) {
                s.virtualPreset.aeIssues = HologramAePolicy.copyIssues(aeIssues);
                s.virtualPreset.structures.clear();
                for (HologramPresets.Structure original : structures)
                    s.virtualPreset.structures.add(copyStructure(original));
            }
            if (!geometry.isEmpty()) {
                HologramCapture capture = new HologramCapture(null, trigger(s));
                for (HologramCapture.Cell saved : geometry) {
                    HologramCapture.Cell cell = blueprintCell(saved, saved.x, saved.y, saved.z);
                    capture.cells.add(cell);
                    capture.positions.put(position(cell), cell);
                }
                s.capture = capture;
                s.selectedIndices.clear();
            }
            // History restores exact positions, never reruns quota allocation.
            s.allocationsFrozen = true;
            s.frozenAutoTargets.clear();
            for (Map.Entry<String, ItemStack> entry : automatic.entrySet()) s.frozenAutoTargets.put(
                entry.getKey(),
                entry.getValue()
                    .copy());
            s.presetSupported = presetSupported;
            s.presetAvailable = presetAvailable;
            s.presetReason = presetReason;
            s.mode = mode;
            s.scope = scope;
            s.selected = selected;
            s.selectedIndices.clear();
            for (int index = 0; index < s.capture.cells.size(); index++)
                if (selectedPositions.contains(position(s.capture.cells.get(index)))) s.selectedIndices.add(index);
            s.pins.clear();
            s.autoTargets.clear();
            s.coordinatePins.clear();
            s.facingPins.clear();
            s.suppressedAuto.clear();
            s.virtualEdited.clear();
            s.virtualEdited.addAll(virtualEdited);
            s.presetEnabled = presetEnabled;
            s.presetPaused = presetPaused;
            s.hatchDowngrade = hatchDowngrade;
            s.noHatches = noHatches;
            s.hatchTier = hatchTier;
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                if (cell.anchor) continue;
                String key = position(cell);
                ItemStack automaticTarget = automatic.get(key);
                if (acceptableSample(s, cell, automaticTarget)) s.autoTargets.put(i, automaticTarget.copy());
                ItemStack target = targets.get(key);
                if (acceptableSample(s, cell, target)) {
                    s.pins.put(i, target.copy());
                    s.coordinatePins.put(key, target.copy());
                }
                if (suppressed.contains(key)) s.suppressedAuto.add(key);
            }
            // Validate directions again against the current capture; rescans can change a cell's legal faces.
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                Integer direction = facings.get(position(cell));
                if (direction == null || cell.anchor) continue;
                ItemStack target = s.pins.get(i);
                if (target == null) target = draftTarget(s, cell, i);
                for (int allowed : legalFacings(s, cell, target))
                    if (allowed == direction) s.facingPins.put(position(cell), direction);
            }
            for (HologramCapture.Cell cell : s.capture.cells) cell.candidates = null;
        }
    }

    private static final class Session {

        final EntityPlayerMP player;
        String id = UUID.randomUUID()
            .toString();
        int x, y, z, side;
        final int dimension, slot;
        final Item tool;
        final ItemStack boundTool;
        final String toolId;
        final long openTick;
        TileEntity target;
        MTEEnhancedMultiBlockBase<?> machine;
        IConstructable constructable;
        Object context;
        int main = 1, facing, mode, scope, layer, selected = -1, job, cursor, completed;
        int jobMissingCells, jobProtectedCells, jobUnsupportedCells, jobSkippedCells;
        boolean jobPartialPlacement;
        boolean noHatches, hints, defer, stopTick, mainExplicit;
        HologramBlueprintResources.Plan blueprintResources, blueprintTotalResources;
        String blueprintResourceMissing = "";
        HologramCapture nbtKeysCapture;
        final java.util.Set<String> availableNbtKeys = new java.util.TreeSet<>();
        List<String> retainedNbtKeys, placementNbtKeys;
        boolean placementNbtCustomized;
        Boolean placementRetainCovers;
        String blueprintActiveStructure = "", blueprintPrompt = "";
        String areaStructureId = "";
        HologramPresets.Structure areaOverviewConfiguration;
        HologramArea areaOverviewOwner;
        String blueprintOperation = "";
        String blueprintSavedId = "";
        String openPurpose = "";
        long placementReviewNonce;
        boolean blueprintOperationSuccess;
        final List<HologramPresets.HintArea> blueprintHintAreas = new ArrayList<>();
        NBTTagList blueprintDraftAnnotations = new NBTTagList();
        boolean blueprintPreview, blueprintAwaitingConfirmation, placementReviewIssued, forcePlacementReady;
        final java.util.Set<Integer> selectedIndices = new java.util.LinkedHashSet<>();
        String scanSelectedPosition;
        final java.util.Set<String> scanSelectedPositions = new java.util.LinkedHashSet<>();
        final List<HologramCapture.Cell> blueprintClipboard = new ArrayList<>();
        boolean virtual, virtualPlacement, presetBrowseForPlacement, virtualLocked, settingsOnly, cancelled,
            calibratePending, buildAfterCapture;
        HologramPresets.Preset virtualPreset;
        final java.util.Set<String> virtualEdited = new java.util.HashSet<>();
        final Map<String, List<ItemStack>> virtualGroups = new HashMap<>();
        String status = "";
        NBTTagCompound channels = new NBTTagCompound();
        HologramCapture capture;
        boolean replacementReady, replacementAuthorized;
        HologramCapture indexedCapture;
        final IdentityHashMap<HologramCapture.Cell, Integer> cellIndices = new IdentityHashMap<>();
        HologramCapabilities.Result capabilities;
        HologramMaterials.Settings materialSettings;
        HologramMaterials.Context materialContext;
        final List<ItemStack> materialChoices = new ArrayList<>();
        final Map<Integer, ItemStack> pins = new HashMap<>();
        ItemStack editClipboard;
        int editClipboardFacing = -1;
        final List<EditState> undoEdits = new ArrayList<>();
        final List<EditState> redoEdits = new ArrayList<>();
        final Map<Integer, ItemStack> autoTargets = new HashMap<>();
        final Map<String, ItemStack> frozenAutoTargets = new HashMap<>();
        boolean allocationsFrozen;
        final java.util.Set<String> suppressedAuto = new java.util.HashSet<>();
        final Map<String, Integer> facingPins = new HashMap<>();
        final Map<Integer, Integer> planFacings = new HashMap<>();
        int hatchTier = 1;
        boolean hatchDowngrade, presetEnabled, presetSupported, presetAvailable, presetPaused;
        String presetReason = "请选择电压级生成整机仓室预设";
        final Map<String, ItemStack> coordinatePins = new HashMap<>();
        final Map<String, HologramCapture.Cell> manualPositions = new LinkedHashMap<>();
        final IdentityHashMap<IStructureElement<?>, Map<String, List<ItemStack>>> hatchCandidates = new IdentityHashMap<>();
        final IdentityHashMap<IStructureElement<?>, Map<String, HologramDeclarationCandidates>> declarationCandidates = new IdentityHashMap<>();
        final IdentityHashMap<IStructureElement<?>, Boolean> pureDeclarations = new IdentityHashMap<>();
        int declarationCandidateCount;
        long declarationGeneration = -1;
        final IdentityHashMap<IStructureElement<?>, List<IStructureElement<?>>> hatchParts = new IdentityHashMap<>();
        final IdentityHashMap<IStructureElement<?>, Map<gregtech.api.interfaces.IHatchElement<?>, Integer>> hatchRequirements = new IdentityHashMap<>();
        final List<Integer> work = new ArrayList<>();
        final List<ItemStack> recovered = new ArrayList<>();
        final List<Integer> ackIndices = new ArrayList<>(), ackSuccesses = new ArrayList<>();
        int deferAttempts, deferredIndex = -1;
        boolean open;
        long planRevision, uiSequence, batchSeq;
        final HologramAeProtocol.State ae = new HologramAeProtocol.State();
        int ackIndex = -1, planMissing;
        String phase = "IDLE";
        boolean planRecoveryFits = true, ackSuccess;
        boolean capturePending, retryNoHatches;
        int captureAttempts, retryFacing, retryMain, retryMode, retryScope, retryLayer, retrySelected;
        long captureRetryDue;
        NBTTagCompound retryChannels;
        final List<Block> expectedBlocks = new ArrayList<>();
        final List<Integer> expectedMeta = new ArrayList<>();
        final List<TileEntity> expectedTiles = new ArrayList<>();
        final List<Integer> expectedFacings = new ArrayList<>();
        final List<String> planStatus = new ArrayList<>();
        final List<ItemStack> planTargets = new ArrayList<>();
        long actionTick = -1;
        long positionNudgeTick = Long.MIN_VALUE;
        int actionCount;

        Session(EntityPlayerMP p, int x, int y, int z) {
            this(p, x, y, z, true, 2);
        }

        Session(EntityPlayerMP p, int x, int y, int z, boolean targetMode, int side) {
            Session previous = SESSIONS.get(p.getUniqueID());
            if (previous != null && (previous.player != p || !valid(previous))) clearAreaSelection(previous);
            this.side = side;
            player = p;
            this.x = x;
            this.y = y;
            this.z = z;
            dimension = p.dimension;
            slot = p.inventory.currentItem;
            tool = p.getHeldItem() == null ? null
                : p.getHeldItem()
                    .getItem();
            target = targetMode ? p.worldObj.getTileEntity(x, y, z) : null;
            ItemStack heldStack = p.getHeldItem();
            boundTool = heldStack;
            materialSettings = HologramMaterials.read(heldStack);
            hatchDowngrade = heldStack != null && heldStack.hasTagCompound()
                && heldStack.getTagCompound()
                    .getBoolean("gtitHologramDowngrade");
            // Copies retain identity. Exact creative NBT clones represent the same logical tool;
            // opening any target rotates the session nonce and invalidates old client actions.
            if (heldStack != null) {
                if (!heldStack.hasTagCompound()) heldStack.setTagCompound(new NBTTagCompound());
                if (heldStack.getTagCompound()
                    .getString(TOOL_ID)
                    .isEmpty())
                    heldStack.getTagCompound()
                        .setString(
                            TOOL_ID,
                            UUID.randomUUID()
                                .toString());
                p.inventory.markDirty();
            }
            toolId = heldStack == null ? ""
                : heldStack.getTagCompound()
                    .getString(TOOL_ID);
            openTick = p.worldObj.getTotalWorldTime();
            context = target instanceof IGregTechTileEntity ? ((IGregTechTileEntity) target).getMetaTileEntity()
                : target;
            if (heldStack != null) {
                if (heldStack.hasTagCompound()) main = Math.max(
                    1,
                    heldStack.getTagCompound()
                        .getInteger("gtitHologramMain"));
                mainExplicit = heldStack.getTagCompound()
                    .getBoolean("gtitHologramMainExplicit") || main != 1;
                ChannelDataAccessor.iterateChannelData(heldStack)
                    .forEach(
                        entry -> { if (entry.getValue() > 0) channels.setInteger(entry.getKey(), entry.getValue()); });
            }
            if (context instanceof MTEEnhancedMultiBlockBase) {
                machine = (MTEEnhancedMultiBlockBase<?>) context;
                facing = machine.getExtendedFacing()
                    .ordinal();
            }
            if (target instanceof IConstructableProvider)
                constructable = ((IConstructableProvider) target).getConstructable();
            else if (target instanceof IConstructable) constructable = (IConstructable) target;
            else if (target != null && IMultiblockInfoContainer.contains(target.getClass())) {
                ExtendedFacing nativeFacing = target instanceof IAlignment ? ((IAlignment) target).getExtendedFacing()
                    : ExtendedFacing.of(ForgeDirection.getOrientation(side));
                constructable = IMultiblockInfoContainer.<TileEntity>get(target.getClass())
                    .toConstructable(target, nativeFacing);
            } else if (context instanceof IConstructableProvider)
                constructable = ((IConstructableProvider) context).getConstructable();
            else if (context instanceof IConstructable) constructable = (IConstructable) context;
        }
    }

    private static void refreshMaterialChoices(Session s) {
        s.materialChoices.clear();
        s.materialChoices.addAll(materialSource(s).catalog(96));
    }

    private static HologramMaterials.Context materialSource(Session s) {
        if (s.materialContext == null) s.materialContext = new HologramMaterials.Context(s.player, s.materialSettings);
        return s.materialContext;
    }

    /** Forge's ordinary snapshot constructor invokes the live serializer; provide an already detached read-only tag. */
    private static BlockSnapshot readOnlyBlockSnapshot(World world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        NBTTagCompound data = null;
        if (tile != null) try {
            data = HologramNativeCapture.capture(tile);
        } catch (java.io.IOException unsafe) {
            throw new IllegalStateException("Cannot safely capture rollback state", unsafe);
        }
        return new BlockSnapshot(world, x, y, z, world.getBlock(x, y, z), world.getBlockMetadata(x, y, z), data);
    }

    private static void rollbackMaterials(Session s, HologramMaterials.Transaction transaction, ItemStack[] inventory) {
        try {
            transaction.rollbackExternal();
        } finally {
            restoreInventory(s.player, inventory);
            materialSource(s).afterInventoryRestore();
        }
    }

    private static final class FilteredSource implements IItemSource {

        final IItemSource source;
        final ItemStack pin;

        FilteredSource(IItemSource source, ItemStack pin) {
            this.source = source;
            this.pin = pin;
        }

        public boolean takeOne(ItemStack stack, boolean simulate) {
            return same(stack, pin) && source.takeOne(stack, simulate);
        }

        public boolean takeAll(ItemStack stack, boolean simulate) {
            return same(stack, pin) && source.takeAll(stack, simulate);
        }

        public Map<ItemStack, Integer> take(java.util.function.Predicate<ItemStack> predicate, boolean simulate,
            int count) {
            return source.take(stack -> same(stack, pin) && predicate.test(stack), simulate, count);
        }
    }

    private static final class ShellSource implements IItemSource {

        final IItemSource source;

        ShellSource(IItemSource source) {
            this.source = source;
        }

        private boolean shell(ItemStack stack) {
            Block block = Block.getBlockFromItem(stack.getItem());
            return block != Blocks.air && (!block.hasTileEntity(stack.getItemDamage())
                || HologramFrameSupport.inertFrameBlock(block, stack.getItemDamage()));
        }

        public boolean takeOne(ItemStack stack, boolean simulate) {
            return shell(stack) && source.takeOne(stack, simulate);
        }

        public boolean takeAll(ItemStack stack, boolean simulate) {
            return shell(stack) && source.takeAll(stack, simulate);
        }

        public Map<ItemStack, Integer> take(java.util.function.Predicate<ItemStack> predicate, boolean simulate,
            int count) {
            return source.take(stack -> shell(stack) && predicate.test(stack), simulate, count);
        }
    }

    private static final class ReservedSource implements IItemSource {

        final ItemStack reserved;
        boolean used;

        ReservedSource(ItemStack stack) {
            reserved = stack.copy();
            reserved.stackSize = 1;
        }

        public Map<ItemStack, Integer> take(java.util.function.Predicate<ItemStack> predicate, boolean simulate,
            int count) {
            if (used || count != 1 || !predicate.test(reserved)) return Collections.emptyMap();
            if (!simulate) used = true;
            return Collections.singletonMap(reserved.copy(), 1);
        }
    }

    private static final class CreativeSource implements IItemSource {

        final List<ItemStack> options;

        CreativeSource(List<ItemStack> options) {
            this.options = options;
        }

        public boolean takeOne(ItemStack stack, boolean simulate) {
            return stack != null && stack.getItem() != null && stack.stackSize == 1;
        }

        public boolean takeAll(ItemStack stack, boolean simulate) {
            return stack != null && stack.getItem() != null && stack.stackSize > 0 && stack.stackSize <= 4096;
        }

        public Map<ItemStack, Integer> take(java.util.function.Predicate<ItemStack> predicate, boolean simulate,
            int count) {
            if (count < 1 || count > 4096) return Collections.emptyMap();
            for (ItemStack option : options) if (predicate.test(option)) {
                ItemStack stack = option.copy();
                stack.stackSize = count;
                return Collections.singletonMap(stack, count);
            }
            return Collections.emptyMap();
        }
    }
}
