package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;

import com.mojang.authlib.GameProfile;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaPipeEntity;

/** Actual GT pipe area import, persisted library reload, native rotation and paid Service placement. */
final class HologramPipeBlueprintSmoke {

    private HologramPipeBlueprintSmoke() {}

    @SuppressWarnings("unchecked")
    static void run(WorldServer world, BaseMetaPipeEntity source, ItemStack coverMaterial) throws Exception {
        require(
            world.getWorldInfo()
                .getWorldName()
                .contains("hologram-smoke"),
            "isolated_world_guard");
        require(source.getFrontFacing() == ForgeDirection.UNKNOWN, "native_pipe_has_no_front");
        require(HologramBlockIdentity.front(source) == -1, "identity_normalizes_native_unknown_front");
        NBTTagCompound original = snapshot(source);
        int sourceConnections = source.mConnections & 0xFF;
        EntityPlayerMP player = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("0d552144-edb7-43b6-b884-6b38b015e4b4"), "GTMBPipeSave"));
        player.inventory.mainInventory = new ItemStack[36];
        player.inventory.currentItem = 0;
        player.capabilities.isCreativeMode = false;
        player.capabilities.allowEdit = true;
        player.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
        Item projector = (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector");
        player.inventory.mainInventory[0] = new ItemStack(projector);
        HologramArea area = new HologramArea();
        area.point(1, source.xCoord, source.yCoord, source.zCoord);
        area.point(2, source.xCoord, source.yCoord, source.zCoord);
        area.begin(world);
        int attempts = 0;
        while (!area.tick(player, player.getHeldItem()) && ++attempts < 16) {}
        require(area.imported && area.blueprint.cells.size() == 1, "area_captures_exact_real_pipe_cell");
        HologramPresets.Preset preset = area.blueprint;
        preset.id = UUID.randomUUID()
            .toString();
        preset.name = "Native GT pipe save smoke";
        require(preset.cells.get(0).facing == -1, "area_capture_never_serializes_unknown_six");
        require(
            preset.cells.get(0)
                .stack()
                .getItemDamage() == source.getMetaTileID(),
            "area_keeps_pipe_metatile_identity");
        require(original.equals(snapshot(source)), "area_capture_preserves_real_source");
        HologramPresets.validate(preset);
        Path library = HologramPresets.libraryPath();
        byte[] libraryBefore = Files.exists(library)
            ? HologramBlueprintSharing.readBytes(library, HologramPresets.MAX_LIBRARY_BYTES)
            : null;
        Field cache = HologramPresets.class.getDeclaredField("cached");
        cache.setAccessible(true);
        Object cacheBefore = cache.get(null);
        boolean saveAttempted = false;
        try {
            saveAttempted = true;
            HologramPresets.save(preset);
            HologramPresets.Preset loaded = readSaved(preset.id);
            require(loaded.cells.get(0).facing == -1, "real_disk_reload_keeps_no_front");
            HologramBlockTransforms.turn(loaded.cells.get(0), false);
            HologramPresets.validate(loaded);
            require(loaded.cells.get(0).facing == -1, "native_pipe_rotation_never_manufactures_front");
            int expected = rotatedConnections(sourceConnections);
            NBTTagCompound rotated = HologramBlueprintNbt.decode(loaded.cells.get(0).tileNbt);
            require((rotated.getByte("mConnections") & 0xFF) == expected, "native_pipe_rotates_actual_connection_mask");
            HologramPresets.update(loaded);
            loaded = readSaved(preset.id);
            require(loaded.cells.get(0).facing == -1, "rotated_disk_reload_keeps_no_front");
            int x = source.xCoord, y = source.yCoord, z = source.zCoord + 32;
            world.getChunkFromBlockCoords(x, z);
            require(world.isAirBlock(x, y, z), "fresh_paid_placement_destination");
            player.setPosition(x + .5, y, z + .5);
            ItemStack pipeMaterial = loaded.cells.get(0)
                .stack();
            NBTTagCompound item = HologramBlueprintNbt.decode(loaded.cells.get(0).itemNbt);
            if (item != null) pipeMaterial.setTagCompound(HologramBlueprintNbt.nativePayload(item));
            player.inventory.mainInventory[1] = pipeMaterial.copy();
            player.inventory.mainInventory[2] = coverMaterial.copy();
            Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
            Constructor<?> constructor = type
                .getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            Object session = constructor.newInstance(player, x, y, z);
            set(session, "virtual", true);
            set(session, "virtualPreset", loaded);
            set(session, "virtualPlacement", true);
            set(session, "virtualLocked", true);
            HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
            settings.main = true;
            settings.containers = false;
            settings.me = false;
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            invoke("populateVirtual", type, session);
            HologramCapture capture = (HologramCapture) get(session, "capture");
            HologramCapture.Cell cell = capture.cells.get(0);
            require(
                cell.meta == loaded.cells.get(0).metadata && cell.block == loaded.cells.get(0)
                    .block(),
                "loaded_pipe_retains_native_world_block_metadata");
            String position = cell.x + "," + cell.y + "," + cell.z;
            ((Map<String, Integer>) get(session, "facingPins")).put(position, 6);
            invoke("refreshPlan", type, session);
            require(
                ((Map<?, ?>) get(session, "facingPins")).isEmpty()
                    && ((Map<?, ?>) get(session, "planFacings")).isEmpty(),
                "pipe_plan_drops_legacy_unknown_pin");
            require(
                (Integer) get(session, "planMissing") == 0 && "pending".equals(cell.status),
                "native_pipe_and_cover_have_exact_payable_plan");
            Method operate = HologramWorldTransactions.class
                .getDeclaredMethod("operateChecked", type, HologramCapture.Cell.class, int.class);
            operate.setAccessible(true);
            require(
                (Boolean) operate.invoke(null, session, cell, 0) && "placed".equals(cell.status),
                "actual_service_transaction_places_saved_pipe");
            require(
                world.getTileEntity(x, y, z) instanceof BaseMetaPipeEntity,
                "placement_restores_real_pipe_tile_class");
            BaseMetaPipeEntity placed = (BaseMetaPipeEntity) world.getTileEntity(x, y, z);
            require(
                placed.getMetaTileID() == source.getMetaTileID() && placed.getFrontFacing() == ForgeDirection.UNKNOWN,
                "placed_pipe_keeps_identity_and_native_no_front");
            require((placed.mConnections & 0xFF) == expected, "placed_pipe_keeps_rotated_branch_connections");
            require(
                ((IGregTechTileEntity) placed).getCoverAtSide(ForgeDirection.UP)
                    .isValid(),
                "paid_placement_restores_up_cover");
            require(
                player.inventory.mainInventory[1] == null && player.inventory.mainInventory[2] == null,
                "pipe_and_cover_consumed_exactly_once");
            require(original.equals(snapshot(source)), "save_rotate_replacement_preserves_real_source");
            System.out.println(
                "[GTMB-HOLOGRAM-PIPE-BLUEPRINT] FINAL PASS real area/disk/rotation/paid placement; UNKNOWN front normalized");
        } finally {
            if (saveAttempted) {
                try {
                    Path pair = library.getParent()
                        .resolve("local_" + preset.id + ".json");
                    if (Files.exists(pair) || Files.exists(HologramBlueprintArtifact.previewPath(pair)))
                        HologramPresets.delete(preset.id);
                } finally {
                    restoreLibrary(library, libraryBefore, cache, cacheBefore);
                }
            }
        }
    }

    /** This server-only fixture restores its own state; it does not manufacture a native preview. */
    private static void restoreLibrary(Path library, byte[] bytes, Field cache, Object previousCache) throws Exception {
        if (bytes == null) {
            Files.deleteIfExists(library);
        } else {
            Path restore = Files.createTempFile(library.getParent(), "gtmb-pipe-fixture-restore-", ".tmp");
            try {
                Files.write(restore, bytes);
                Files.move(restore, library, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(restore);
            }
        }
        cache.set(null, previousCache);
    }

    @SuppressWarnings("unchecked")
    private static HologramPresets.Preset readSaved(String id) throws Exception {
        Method read = HologramPresets.class.getDeclaredMethod("readLibrary", Path.class);
        read.setAccessible(true);
        for (HologramPresets.Preset preset : (List<HologramPresets.Preset>) read
            .invoke(null, HologramPresets.libraryPath())) if (id.equals(preset.id)) return preset;
        throw new AssertionError("pipe saved library entry missing on disk");
    }

    private static int rotatedConnections(int original) {
        int result = original & ~0x3F;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) if ((original & 1 << side.ordinal()) != 0)
            result |= 1 << HologramVirtualTransform.front(side.ordinal(), false);
        return result;
    }

    private static NBTTagCompound snapshot(BaseMetaPipeEntity tile) throws java.io.IOException {
        return HologramNativeCapture.capture(tile);
    }

    private static Object get(Object session, String name) throws Exception {
        Field field = session.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(session);
    }

    private static void set(Object session, String name, Object value) throws Exception {
        Field field = session.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private static void invoke(String name, Class<?> type, Object session) throws Exception {
        Method method = HologramEngineInspection.declaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(null, session);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("[GTMB-HOLOGRAM-PIPE-BLUEPRINT] PASS " + message);
    }
}
