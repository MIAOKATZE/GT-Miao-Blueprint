package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Compatible tool settings and world-owned draft persistence. */
final class HologramDraftPersistence {

    private HologramDraftPersistence() {}

    static void persist(HologramSession s) {
        ItemStack held = HologramSessionPolicy.jobTool(s);
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

    static boolean draftMatches(HologramSession s, NBTTagCompound draft) {
        return !s.virtual && s.target != null
            && draft.getInteger("dimension") == s.dimension
            && draft.getInteger("x") == s.x
            && draft.getInteger("y") == s.y
            && draft.getInteger("z") == s.z
            && draft.getString("controllerType")
                .equals(HologramSessionPolicy.controllerType(s));
    }

    static String draftKey(HologramSession s) {
        return legacyDraftKey(
            s) + ":" + s.dimension + ":" + s.x + ":" + s.y + ":" + s.z + ":" + HologramSessionPolicy.controllerType(s);
    }

    static String legacyDraftKey(HologramSession s) {
        return s.player.getUniqueID() + ":" + s.toolId;
    }

    static NBTTagCompound storedDraft(HologramSession s, ItemStack held) {
        NBTTagCompound draft = held.getTagCompound()
            .getCompoundTag(HologramSessionKeys.DRAFT);
        // The item marker identifies the one remembered projection. Historical controller keys
        // must not revive a projection after another controller or a confirmed discard replaced it.
        if (!draftMatches(s, draft)) return new NBTTagCompound();
        if (!draft.getBoolean("serverStored")) return draft;
        String key = draft.getString("storageKey");
        return HologramDraftStorage.get(s.player.worldObj)
            .read(key.isEmpty() ? legacyDraftKey(s) : key);
    }

    static void forgetProjection(HologramSession s) {
        ItemStack held = HologramSessionPolicy.currentTool(s);
        if (held == null) return;
        if (s.libraryWorld.perWorldStorage != null) {
            HologramDraftStorage storage = HologramDraftStorage.get(s.libraryWorld);
            storage.remove(draftKey(s));
            storage.remove(legacyDraftKey(s));
        }
        NBTTagCompound draft = held.getTagCompound()
            .getCompoundTag(HologramSessionKeys.DRAFT);
        if (draftMatches(s, draft)) {
            held.getTagCompound()
                .removeTag(HologramSessionKeys.DRAFT);
            s.player.inventory.markDirty();
        }
    }

    static void writeDraft(HologramSession s, ItemStack held) {
        if (s.virtual || s.target == null || s.capture == null || s.capture.incomplete || s.capturePending) return;
        NBTTagCompound draft = new NBTTagCompound();
        draft.setInteger("dimension", s.dimension);
        draft.setInteger("x", s.x);
        draft.setInteger("y", s.y);
        draft.setInteger("z", s.z);
        draft.setString("controllerType", HologramSessionPolicy.controllerType(s));
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
            String key = HologramValues.position(cell);
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
            reference.setString("controllerType", HologramSessionPolicy.controllerType(s));
            held.getTagCompound()
                .setTag(HologramSessionKeys.DRAFT, reference);
        } else held.getTagCompound()
            .setTag(HologramSessionKeys.DRAFT, draft);
    }

    /** Preflight before session creation, which may clear selection or initialize the held tool identity. */
    static String admissionFailure(EntityPlayerMP player, int x, int y, int z) {
        ItemStack held = player.getHeldItem();
        if (held == null || !held.hasTagCompound()
            || !held.getTagCompound()
                .hasKey(HologramSessionKeys.DRAFT, 10))
            return "";
        NBTTagCompound marker = held.getTagCompound()
            .getCompoundTag(HologramSessionKeys.DRAFT);
        if (marker.getInteger("dimension") != player.dimension || marker.getInteger("x") != x
            || marker.getInteger("y") != y
            || marker.getInteger("z") != z) return "";
        if (!player.worldObj.blockExists(x, y, z)) return "";
        TileEntity target = player.worldObj.getTileEntity(x, y, z);
        Object context = HologramStructureDiscovery.context(target);
        String type = target instanceof IGregTechTileEntity && context != null ? context.getClass()
            .getName() + ":"
            + ((IGregTechTileEntity) target).getMetaTileID()
            : target == null ? ""
                : target.getClass()
                    .getName();
        if (!marker.getString("controllerType")
            .equals(type)) return "";
        NBTTagCompound draft = marker;
        if (marker.getBoolean("serverStored")) {
            String key = marker.getString("storageKey");
            if (key.isEmpty()) key = player.getUniqueID() + ":"
                + held.getTagCompound()
                    .getString(HologramSessionKeys.TOOL_ID);
            draft = HologramDraftStorage.get(player.worldObj)
                .read(key);
        }
        if (draft.getInteger("dimension") != player.dimension || draft.getInteger("x") != x
            || draft.getInteger("y") != y
            || draft.getInteger("z") != z
            || !draft.getString("controllerType")
                .equals(type))
            return "";
        return countsValid(draft) ? "" : "旧草稿记录格数超过上限：须小于一百万；原草稿已保留，未打开或覆盖";
    }

    static boolean countsValid(NBTTagCompound draft) {
        return HologramBlueprintLimits.accepts(
            draft.getTagList("automatic", 10)
                .tagCount())
            && HologramBlueprintLimits.accepts(
                draft.getTagList("edits", 10)
                    .tagCount())
            && HologramBlueprintLimits.accepts(
                draft.getTagList("blueprintAnnotations", 10)
                    .tagCount());
    }

    static boolean readDraft(HologramSession s) {
        ItemStack held = HologramSessionPolicy.currentTool(s);
        if (held == null || !held.getTagCompound()
            .hasKey(HologramSessionKeys.DRAFT, 10)) return true;
        NBTTagCompound draft = storedDraft(s, held);
        if (!draftMatches(s, draft)) return true;
        if (!countsValid(draft)) {
            s.status = "旧草稿记录格数须小于一百万；原草稿已保留，未载入或覆盖";
            return false;
        }
        s.blueprintPrompt = HologramSessionPolicy.boundedPrompt(draft.getString("blueprintPrompt"));
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
            hint.prompt = HologramSessionPolicy.boundedPrompt(row.getString("prompt"));
            s.blueprintHintAreas.add(hint);
        }
        s.main = Math.max(1, draft.getInteger("main"));
        s.mainExplicit = draft.getBoolean("mainExplicit");
        s.channels = HologramSessionPolicy.boundedChannels(draft.getCompoundTag("channels"));
        s.noHatches = draft.getBoolean("noHatches");
        s.hatchTier = HologramValues.clamp(draft.getInteger("tier"), 0, gregtech.api.enums.GTValues.VN.length - 1);
        s.hatchDowngrade = draft.getBoolean("downgrade");
        s.presetEnabled = draft.getBoolean("presetEnabled");
        s.presetPaused = draft.getBoolean("presetPaused");
        s.allocationsFrozen = draft.getBoolean("allocationsFrozen");
        s.frozenAutoTargets.clear();
        NBTTagList automatic = draft.getTagList("automatic", 10);
        for (int i = 0; i < automatic.tagCount(); i++) {
            NBTTagCompound row = automatic.getCompoundTagAt(i);
            String key = row.getString("position");
            if (key.length() > 48) continue;
            ItemStack sample = ItemStack.loadItemStackFromNBT(row.getCompoundTag("target"));
            if (sample != null && !HologramRecovery.hasSeal(sample))
                s.frozenAutoTargets.put(key, new ItemStack(sample.getItem(), 1, sample.getItemDamage()));
        }
        s.mode = HologramValues.clamp(draft.getInteger("mode"), 0, 2);
        s.scope = 0;
        s.materialSettings.main = draft.getBoolean("materialMain");
        s.materialSettings.containers = draft.getBoolean("materialContainers");
        s.materialSettings.me = draft.getBoolean("materialMe");
        s.materialSettings.priority = HologramValues.clamp(draft.getInteger("materialPriority"), 0, 2);
        s.materialSettings.recoveryDestination = HologramValues.clamp(draft.getInteger("recoveryDestination"), 0, 1);
        NBTTagList edits = draft.getTagList("edits", 10);
        for (int i = 0; i < edits.tagCount(); i++) {
            NBTTagCompound entry = edits.getCompoundTagAt(i);
            int dx = entry.getInteger("dx"), dy = entry.getInteger("dy"), dz = entry.getInteger("dz");
            if (!HologramSessionPolicy.validRelativePosition(s, dx, dy, dz)) continue;
            HologramCapture.Cell cell = new HologramCapture.Cell(null, s.x + dx, s.y + dy, s.z + dz);
            if (dx == 0 && dy == 0 && dz == 0) continue;
            String key = HologramValues.position(cell);
            Object item = Item.itemRegistry.getObject(entry.getString("item"));
            ItemStack sample = item instanceof Item ? new ItemStack((Item) item, 1, entry.getInteger("damage")) : null;
            if (HologramCellPolicy.acceptableSample(s, cell, sample)) s.coordinatePins.put(key, sample);
            int direction = entry.getInteger("facing");
            if (entry.hasKey("facing", 3) && direction >= 0 && direction <= 5) s.facingPins.put(key, direction);
            if (entry.getBoolean("suppressed")) s.suppressedAuto.add(key);
        }
        return true;
    }

    static void restoreBlueprintAnnotations(HologramSession s) {
        if (s.capture == null) return;
        for (int i = 0; i < s.blueprintDraftAnnotations.tagCount(); i++) {
            NBTTagCompound row = s.blueprintDraftAnnotations.getCompoundTagAt(i);
            int[] position = row.getIntArray("position");
            if (position.length != 3
                || !HologramSessionPolicy.validRelativePosition(s, position[0], position[1], position[2])) continue;
            HologramCapture.Cell cell = s.capture.positions
                .get((s.x + position[0]) + "," + (s.y + position[1]) + "," + (s.z + position[2]));
            if (cell == null) continue;
            cell.prompt = HologramSessionPolicy.boundedPrompt(row.getString("prompt"));
            cell.excluded = row.getBoolean("excluded");
        }
        s.blueprintDraftAnnotations = new NBTTagList();
    }

    static void clearDraft(HologramSession s) {
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

    /** Global settings also update a detached controller draft, so reopening it cannot undo the user's switches. */
    static void updateDraftSettings(NBTTagCompound draft, HologramMaterials.Settings settings, boolean downgrade) {
        draft.setBoolean("materialMain", settings.main);
        draft.setBoolean("materialContainers", settings.containers);
        draft.setBoolean("materialMe", settings.me);
        draft.setInteger("materialPriority", settings.priority);
        draft.setInteger("recoveryDestination", settings.recoveryDestination);
        draft.setBoolean("downgrade", downgrade);
    }
}
