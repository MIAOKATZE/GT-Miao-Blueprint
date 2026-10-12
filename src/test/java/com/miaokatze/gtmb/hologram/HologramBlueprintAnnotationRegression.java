package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Annotation scopes and rejected save requests against a world that forbids all writes. */
final class HologramBlueprintAnnotationRegression {

    private HologramBlueprintAnnotationRegression() {}

    static void run() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
                field.setAccessible(true);
                field.set(
                    player,
                    new GameProfile(UUID.fromString("4a21d4e1-3595-41e2-88e9-53a77eb0f121"), "Annotations"));
            }
            player.worldObj = world;
            player.inventory = new InventoryPlayer(player);
            player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.isCreativeMode = true;
            player.capabilities.allowEdit = true;
            Class<?> type = Class.forName(HologramService.class.getName() + "$Session");
            Constructor<?> ctor = type.getDeclaredConstructor(
                EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            ctor.setAccessible(true);
            Object session = ctor.newInstance(player, 0, 64, 0, false, 2);
            set(session, "virtual", true);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.id = "annotations";
            preset.name = "Annotations";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false));
            set(session, "virtualPreset", preset);
            HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
            settings.main = settings.containers = settings.me = false;
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            Method populate = method("populateVirtual", type);
            populate.invoke(null, session);
            Method action = method("blueprintAction", type, NBTTagCompound.class, String.class);
            NBTTagCompound request = new NBTTagCompound();
            request.setIntArray("indices", new int[] { 0 });
            request.setString("prompt", "单格提示");
            action.invoke(null, session, request, "editCellFlags");
            SimpleAssert.eq("单格提示", preset.cells.get(0).prompt, "cell edit updates the reloadable in-memory blueprint");
            request.setString("prompt", "区域提示");
            action.invoke(null, session, request, "editHintArea");
            HologramCapture capture = (HologramCapture) get(session, "capture");
            SimpleAssert.eq("单格提示", capture.cells.get(0).prompt, "area prompt does not overwrite cell prompt");
            SimpleAssert.eq(1, preset.hintAreas.size(), "first area annotation creates one bound");
            request.setString("prompt", "更新区域提示");
            action.invoke(null, session, request, "editHintArea");
            SimpleAssert.eq(1, preset.hintAreas.size(), "repeated save replaces the same area");
            SimpleAssert.eq("更新区域提示", preset.hintAreas.get(0).prompt, "replacement stores the new area text");
            request.setString("prompt", "");
            action.invoke(null, session, request, "editHintArea");
            SimpleAssert.eq(0, preset.hintAreas.size(), "empty area text deletes its exact bounds");
            request.setString("prompt", "整体提示");
            action.invoke(null, session, request, "editBlueprintPrompt");
            populate.invoke(null, session);
            SimpleAssert.eq(
                "单格提示",
                ((HologramCapture) get(session, "capture")).cells.get(0).prompt,
                "repopulating the virtual preview retains unsaved cell annotations");
            Object originalCapture = get(session, "capture");
            request.setString("name", new String(new char[49]).replace('\0', 'x'));
            request.setString("prompt", "过期客户端文本");
            action.invoke(null, session, request, "saveBlueprint");
            SimpleAssert
                .that(get(session, "capture") == originalCapture, "rejected save preserves exact editor capture");
            SimpleAssert.that(get(session, "virtualPreset") == preset, "rejected save preserves edited blueprint");
            SimpleAssert.eq("整体提示", preset.prompt, "save request never replaces overall text with stale UI state");
            SimpleAssert
                .eq("saveBlueprint", get(session, "blueprintOperation"), "save failure identifies its operation");
            SimpleAssert.eq(false, get(session, "blueprintOperationSuccess"), "save failure is explicit in state");
            SimpleAssert.that(((String) get(session, "status")).contains("失败"), "save failure remains visible");
            SimpleAssert
                .eq(0, ((List<?>) get(session, "undoEdits")).size(), "failed save does not create edit history");
            areaSaveOwnership(session, player, preset, type);
        } catch (Exception failure) {
            throw new AssertionError("blueprint annotation scopes and failed save regression", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void areaSaveOwnership(Object session, EntityPlayerMP player, HologramPresets.Preset preset,
        Class<?> type) throws Exception {
        Field areasField = HologramService.class.getDeclaredField("AREAS");
        Field editorsField = HologramService.class.getDeclaredField("AREA_EDITORS");
        areasField.setAccessible(true);
        editorsField.setAccessible(true);
        Map<UUID, HologramArea> areas = (Map<UUID, HologramArea>) areasField.get(null);
        Map<UUID, Object> editors = (Map<UUID, Object>) editorsField.get(null);
        HologramArea area = new HologramArea();
        area.imported = true;
        area.blueprint = preset;
        HologramArea previousArea = areas.put(player.getUniqueID(), area);
        Object previousEditor = editors.remove(player.getUniqueID());
        try {
            set(session, "areaOverviewOwner", area);
            HologramPresets.Preset saved = HologramPresets.copy(preset);
            method("publishSavedBlueprint", type, HologramPresets.Preset.class).invoke(null, session, saved);
            SimpleAssert.that(
                area.blueprint == saved && get(session, "virtualPreset") == saved,
                "first save before a structure switch keeps area and editor on the same saved snapshot");
            editors.put(player.getUniqueID(), session);
            HologramPresets.Preset saveAs = HologramPresets.copy(saved);
            saveAs.id = "new_area_identity";
            method("publishSavedBlueprint", type, HologramPresets.Preset.class).invoke(null, session, saveAs);
            SimpleAssert.that(
                area.blueprint == saveAs && get(session, "virtualPreset") == saveAs,
                "save as from a cached overview keeps its live area association");
            method("detachAreaOverview", type).invoke(null, session);
            HologramPresets.Preset offline = HologramPresets.copy(saveAs);
            set(session, "virtualPreset", offline);
            method("publishSavedBlueprint", type, HologramPresets.Preset.class).invoke(null, session, offline);
            SimpleAssert.that(
                area.blueprint == saveAs && get(session, "areaOverviewOwner") == null,
                "saving a separately loaded preset cannot reattach to an old area with the same ID");
            SimpleAssert.that(
                !editors.containsKey(player.getUniqueID()),
                "loading an offline preset retires a cached live overview ownership slot");
            HologramArea.Structure liveStructure = new HologramArea.Structure(0, 64, 0, null);
            area.structures.put(liveStructure.id, liveStructure);
            HologramPresets.Structure offlineStructure = new HologramPresets.Structure();
            offlineStructure.id = liveStructure.id;
            offline.structures.add(offlineStructure);
            NBTTagCompound change = new NBTTagCompound();
            change.setString("structureId", liveStructure.id);
            change.setBoolean("enabled", false);
            method("blueprintAction", type, NBTTagCompound.class, String.class)
                .invoke(null, session, change, "structureState");
            SimpleAssert.that(!offlineStructure.enabled, "offline structure enable action edits its own blueprint");
            SimpleAssert.that(liveStructure.enabled, "offline structure enable action cannot alter the old live area");
        } finally {
            if (previousArea == null) areas.remove(player.getUniqueID());
            else areas.put(player.getUniqueID(), previousArea);
            if (previousEditor == null) editors.remove(player.getUniqueID());
            else editors.put(player.getUniqueID(), previousEditor);
        }
    }

    private static Method method(String name, Class<?>... arguments) throws Exception {
        Method method = HologramService.class.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
