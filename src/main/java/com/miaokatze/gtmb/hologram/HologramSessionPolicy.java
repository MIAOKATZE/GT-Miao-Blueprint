package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Session/tool/position admission and scope predicates; contains no transport or world mutation. */
final class HologramSessionPolicy {

    private HologramSessionPolicy() {}

    static boolean authoring(HologramSession s) {
        return s.virtual && s.virtualPreset != null && s.virtualPreset.schema >= 2 && !s.virtualPlacement;
    }

    static boolean blockedByAreaImport(HologramSession s) {
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area == null || !area.importing) return false;
        s.status = "正在导入区域，请等待完成；关闭界面可取消导入并保留原蓝图";
        return true;
    }

    static boolean previewReach(EntityPlayerMP player, int x, int y, int z) {
        return HologramWorldFlow
            .withinPreviewReach(player.posX, player.posY + player.getEyeHeight(), player.posZ, x, y, z);
    }

    static int cellIndex(HologramSession s, HologramCapture.Cell cell) {
        if (s.capture == null) return -1;
        int stable = cell.stableIndex;
        if (stable >= 0 && stable < s.capture.cells.size() && s.capture.cells.get(stable) == cell) return stable;
        if (s.indexedCapture != s.capture || s.cellIndices.size() != s.capture.cells.size()) {
            s.cellIndices.clear();
            for (int i = 0; i < s.capture.cells.size(); i++) {
                s.capture.cells.get(i).stableIndex = i;
                s.cellIndices.put(s.capture.cells.get(i), i);
            }
            s.indexedCapture = s.capture;
        }
        return s.cellIndices.getOrDefault(cell, -1);
    }

    static boolean validRelativePosition(HologramSession s, int dx, int dy, int dz) {
        long x = (long) s.x + dx, y = (long) s.y + dy, z = (long) s.z + dz;
        return x >= -30000000L && x < 30000000L
            && z >= -30000000L
            && z < 30000000L
            && y >= 0
            && y < s.player.worldObj.getHeight();
    }

    static boolean valid(HologramSession s) {
        return currentTool(s) != null && validWorldContext(s, false);
    }

    /** Admission remains held-tool-only. An admitted job may run while another hotbar slot is selected. */
    static boolean jobValid(HologramSession s) {
        return jobInvalidReason(s).isEmpty();
    }

    static String jobInvalidReason(HologramSession s) {
        if (jobTool(s) == null) return "原工具槽中的投影仪已移走或被替换";
        if (s.player.isDead) return "玩家已死亡";
        if (s.player.dimension != s.dimension || s.player.worldObj != s.libraryWorld) return "玩家世界或维度已变化";
        if (!validWorldContext(s, true)) return "原目标已卸载、被替换或超出有效范围";
        if (!s.virtual && (s.machine == null || s.machine.getExtendedFacing()
            .ordinal() != s.facing)) return "原控制器朝向已变化";
        return "";
    }

    /** Running/paused construction keeps its admitted context; editors and other tasks retain admission checks. */
    static boolean tickValid(HologramSession s) {
        return s.job == 1 || s.job == 2 ? jobValid(s) : valid(s);
    }

    private static boolean validWorldContext(HologramSession s, boolean admittedJob) {
        return !s.player.isDead && s.player.dimension == s.dimension
            && s.player.worldObj == s.libraryWorld
            && (!s.virtual || !s.virtualPlacement
                || !s.virtualLocked
                || (HologramWorldFlow.worldPosition(s.x, s.y, s.z, s.player.worldObj.getHeight())
                    // Preview reach admits a locked position. Once construction starts the player may
                    // inspect a large blueprint; per-cell loaded-world/edit/event checks still apply.
                    && (admittedJob || previewReach(s.player, s.x, s.y, s.z))))
            && (s.target == null || (s.player.getDistanceSq(s.x + .5, s.y + .5, s.z + .5) <= 4096
                && s.player.worldObj.blockExists(s.x, s.y, s.z)
                && s.player.worldObj.getTileEntity(s.x, s.y, s.z) == s.target
                && (!(s.target instanceof IGregTechTileEntity)
                    || ((IGregTechTileEntity) s.target).getMetaTileEntity() == s.context)));
    }

    static boolean membershipEnabled(HologramSession s, HologramCapture.Cell cell) {
        if (cell.memberships()
            .isEmpty()) return true;
        if (s.virtualPreset != null) {
            String active = wholeBlueprintPlacement(s) ? "" : s.blueprintActiveStructure;
            if (s.structureIndex != null) {
                Boolean enabled = s.structureIndex.enabled(s.virtualPreset, cell.memberships(), active);
                if (enabled != null) return enabled;
            }
            boolean found = false, enabled = false;
            for (HologramPresets.Structure structure : s.virtualPreset.structures) {
                if (!cell.belongsTo(structure.id)) continue;
                if (!active.isEmpty() && !active.equals(structure.id)) continue;
                found = true;
                enabled |= structure.enabled;
            }
            return active.isEmpty() ? !found || enabled : found && enabled;
        }
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
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

    static boolean inScope(HologramSession s, HologramCapture.Cell c, int index) {
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

    static boolean wholeBlueprintPlacement(HologramSession s) {
        return s.virtual && s.virtualPlacement && s.virtualLocked;
    }

    static void clearPlacementSelection(HologramSession s) {
        s.scope = 0;
        s.selected = -1;
        s.selectedIndices.clear();
        s.blueprintActiveStructure = "";
    }

    static boolean sameSelectedFamily(HologramSession s, HologramCapture.Cell cell) {
        if (s.capture == null || s.selected < 0 || s.selected >= s.capture.cells.size()) return false;
        HologramCapture.Cell source = s.capture.cells.get(s.selected);
        if (cell.family.isEmpty() || !cell.family.equals(source.family)) return false;
        String active = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
        if (active.isEmpty()) active = source.structureId;
        return active.isEmpty() ? cell.memberships()
            .isEmpty() : cell.belongsTo(active);
    }

    static ItemStack trigger(HologramSession s) {
        ItemStack current = s.job == 1 || s.job == 2 ? jobTool(s) : currentTool(s);
        ItemStack result = current == null ? new ItemStack(s.tool, 1) : current.copy();
        // Large editor drafts are tool storage, not native structure signals copied into every captured cell.
        if (result.hasTagCompound()) result.getTagCompound()
            .removeTag(HologramSessionKeys.DRAFT);
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

    static ItemStack currentTool(HologramSession s) {
        ItemStack held = s.player.getHeldItem();
        return held != null && held.getItem() == s.tool
            && s.player.inventory.currentItem == s.slot
            && held.hasTagCompound()
            && s.toolId.equals(
                held.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID)) ? held : null;
    }

    static ItemStack jobTool(HologramSession s) {
        ItemStack tool = s.slot < 0 || s.slot >= s.player.inventory.mainInventory.length ? null
            : s.player.inventory.mainInventory[s.slot];
        return tool != null && tool.getItem() == s.tool
            && tool.hasTagCompound()
            && s.toolId.equals(
                tool.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID)) ? tool : null;
    }

    static String controllerType(HologramSession s) {
        if (s.target instanceof IGregTechTileEntity) return s.context.getClass()
            .getName() + ":" + ((IGregTechTileEntity) s.target).getMetaTileID();
        return s.target == null ? ""
            : s.target.getClass()
                .getName();
    }

    static String selectedPreset(HologramSession s) {
        ItemStack held = currentTool(s);
        return held == null ? ""
            : held.getTagCompound()
                .getString(HologramSessionKeys.SELECTED_PRESET);
    }

    static int[] editIndices(HologramSession s, NBTTagCompound action) {
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

    static boolean editableStructureCell(HologramSession s, HologramCapture.Cell cell) {
        String active = s.virtual ? s.blueprintActiveStructure : s.areaStructureId;
        return active.isEmpty() || cell.memberships()
            .isEmpty() || cell.belongsTo(active);
    }

    static boolean placementRetainCovers(HologramSession s) {
        return s.placementRetainCovers != null ? s.placementRetainCovers
            : s.virtualPreset == null || s.virtualPreset.retainCovers;
    }

    static List<String> placementKeys(HologramSession s) {
        return s.placementNbtCustomized ? s.placementNbtKeys
            : s.virtualPreset == null ? s.retainedNbtKeys : s.virtualPreset.retainedNbtKeys;
    }

    static NBTTagList nbtKeys(List<String> keys) {
        NBTTagList tag = new NBTTagList();
        if (keys != null) for (String key : keys) tag.appendTag(new NBTTagString(key));
        return tag;
    }

    static String boundedPrompt(String text) {
        return text.length() <= 2048 ? text : text.substring(0, 2048);
    }

    static String sourceMod(Class<?> type) {
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

    static NBTTagCompound boundedChannels(NBTTagCompound input) {
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

    static boolean validChannels(NBTTagCompound input) {
        if (input.func_150296_c()
            .size() > 64) return false;
        for (Object key : input.func_150296_c()) {
            String name = (String) key;
            if (!name.matches("[a-z0-9_.-]{1,48}") || !input.hasKey(name, 3) || input.getInteger(name) <= 0)
                return false;
        }
        return true;
    }

    static boolean virtualMoving(HologramSession s) {
        return s.virtual && s.virtualPlacement && s.virtualPreset != null && !s.virtualLocked && s.job != 4;
    }
}
