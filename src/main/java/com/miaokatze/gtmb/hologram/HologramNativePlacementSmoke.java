package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Strict installed-mod server test of capture, payment and the actual Service placement transaction. */
public final class HologramNativePlacementSmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-NATIVE-PLACEMENT] ";

    private HologramNativePlacementSmoke() {}

    public static void run(WorldServer world) throws Exception {
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-smoke-"),
            "isolated_world_guard");
        HologramAeNativeResourcesSmoke.run();
        rfSchema();
        HologramNativeCaptureSmoke.run(world);
        Object saveCache = storageCache("s_savehandler");
        Object mapCache = storageCache("s_mapStorage");
        Object liveStorage = world.mapStorage;
        EntityPlayerMP player = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("776dddb0-84e0-46b8-8521-9962604f8c11"), "GTMBNativePlace"));
        player.inventory.mainInventory = new ItemStack[36];
        player.inventory.currentItem = 0;
        player.capabilities.isCreativeMode = false;
        player.capabilities.allowEdit = true;
        player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        player.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        int kind = 0;
        for (Block block : HologramNativeCompatibilityFixture.blocks()) {
            HologramResourceWorld isolated = HologramResourceWorld.create(block, 0);
            require(
                isolated.mapStorage != liveStorage && isolated.getSaveHandler() != world.getSaveHandler(),
                "material_world_owns_independent_storage_" + kind);
            require(!isolated.setBlock(0, 64, 0, block, 0, 3), "material_world_rejects_mutation_" + kind);
            unchangedStorage(world, saveCache, mapCache, liveStorage, "material_world_constructor_cache_" + kind);
            int sourceX = 288 + kind * 4, destinationX = 336 + kind * 4, y = 88, z = 96;
            world.getChunkFromBlockCoords(sourceX, z);
            world.getChunkFromBlockCoords(destinationX, z);
            require(world.isAirBlock(sourceX, y, z) && world.isAirBlock(destinationX, y, z), "fresh_fixture_" + kind);
            NBTTagCompound seed = canonicalSeed(block, sourceX, y, z);
            if (HologramAeBlueprint.supports(block, seed)) {
                HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, 0, seed);
                StringBuilder issues = new StringBuilder();
                for (HologramAeBlueprint.Issue issue : analysis.issues) {
                    String description = issue.code + ":" + issue.severity + ":" + issue.path + ":" + issue.message;
                    System.out.println(PREFIX + "AE SOURCE ISSUE " + description);
                    if (issues.length() < 2048) issues.append(description)
                        .append("; ");
                }
                require(
                    analysis.supported && !analysis.blocking,
                    "official_ae_source_schema_supported_" + kind + " issues=" + issues);
            }
            require(world.setBlock(sourceX, y, z, block, 0, 3), "source_block_" + kind);
            HologramNativePlacement.install(world, sourceX, y, z, block, 0, seed);
            NBTTagCompound captured = snapshot(world.getTileEntity(sourceX, y, z));
            ItemStack capturedSample = HologramArea.serverSample(block, 0, world.getTileEntity(sourceX, y, z));
            if (capturedSample == null) HologramNativePlacement.materials(block, 0, captured); // Preserve the strict
                                                                                               // factory/material
                                                                                               // cause.
            require(capturedSample != null, "native_capture_material_" + kind);
            if (kind == 4) rfBudget(world, player, block, captured, capturedSample, destinationX + 1, y, z);
            HologramNativePlacement.materials(block, 0, captured);
            HologramNativePlacement.materials(block, 0, captured);
            unchangedStorage(world, saveCache, mapCache, liveStorage, "repeated_material_callbacks_cache_" + kind);
            require(
                captured.equals(snapshot(world.getTileEntity(sourceX, y, z))),
                "capture_never_extracts_source_" + kind);
            HologramBlockTransformApi.RotationResult rotated = HologramBlockTransformApi
                .rotate(block, 0, captured, false, 1);
            HologramCapture.Cell oldCell = cell(block, 0, captured, capturedSample, destinationX, y, z);
            Object session = session(player, oldCell, capturedSample);
            require(
                transform(session, destinationX, y, z),
                "service_transform_native_draft_" + kind + "_" + get(session, "status"));
            HologramCapture.Cell cell = ((HologramCapture) get(session, "capture")).cells.get(0);
            NBTTagCompound transformed = HologramBlueprintNbt.nativePayload(HologramBlueprintNbt.decode(cell.tileNbt));
            require(
                cell.meta == rotated.metadata && transformed.equals(rotated.tile),
                "service_transform_matches_native_api_" + kind);
            List<ItemStack> costs = HologramNativePlacement.materials(block, cell.meta, transformed);
            require(costs.size() >= 2, "multiple_payable_native_components_" + kind);
            ItemStack primary = nativeTargets(session, cell, transformed, kind);
            require(
                captured.equals(snapshot(world.getTileEntity(sourceX, y, z))),
                "service_transform_preserves_real_source_" + kind);
            if (kind == 2) require(
                !ItemStack.areItemStackTagsEqual(capturedSample, primary),
                "littletiles_asymmetric_drop_tag_actually_rotates");
            fill(player, costs, true);
            player.setPosition(destinationX + 0.5, y, z + 0.5);
            require(
                !operate(session, cell) && "missing".equals(cell.status),
                "missing_extra_component_rejects_" + kind
                    + " cell="
                    + cell.status
                    + " status="
                    + get(session, "status")
                    + " resources="
                    + cell.resourceError
                    + " block="
                    + Block.blockRegistry.getNameForObject(world.getBlock(destinationX, y, z))
                    + " materials="
                    + materialsCount(player));
            require(
                world.getBlock(destinationX, y, z) == net.minecraft.init.Blocks.air
                    && world.getTileEntity(destinationX, y, z) == null
                    && materialsCount(player) == 1,
                "missing_extra_component_world_and_material_rollback_" + kind);
            fill(player, costs, false);
            ItemStack[] before = inventory(player);
            Veto veto = new Veto(destinationX, y, z, block);
            MinecraftForge.EVENT_BUS.register(veto);
            try {
                operate(session, cell);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(veto);
            }
            require(
                veto.events == 1 && veto.complete,
                "native_composite_visible_to_place_event_" + kind
                    + " events="
                    + veto.events
                    + " complete="
                    + veto.complete
                    + " cell="
                    + cell.status
                    + " status="
                    + get(session, "status")
                    + " resources="
                    + cell.resourceError
                    + " cause="
                    + veto.failure);
            require(
                world.getBlock(destinationX, y, z) == net.minecraft.init.Blocks.air
                    && world.getTileEntity(destinationX, y, z) == null
                    && sameInventory(before, player),
                "cancelled_place_refunds_all_native_components_" + kind);
            require(
                operate(session, cell) && "placed".equals(cell.status),
                "service_native_rotation_placement_" + kind
                    + "_"
                    + cell.status
                    + "_"
                    + get(session, "status")
                    + "_"
                    + cell.resourceError);
            require(
                world.getBlock(destinationX, y, z) == block && world.getTileEntity(destinationX, y, z) != null,
                "real_native_block_and_tile_" + kind);
            require(materialsCount(player) == 0, "every_native_material_consumed_once_" + kind);
            require(
                captured.equals(snapshot(world.getTileEntity(sourceX, y, z))),
                "source_unchanged_after_placement_" + kind);
            require(
                captured.equals(snapshot(world.getTileEntity(sourceX, y, z))),
                "source_unchanged_after_rotation_" + kind);
            HologramPresets.Preset preset = (HologramPresets.Preset) get(session, "virtualPreset");
            preset.retainNbt = false;
            require(
                !operate(session, cell) && "unsupported".equals(cell.status),
                "excluded_native_payload_never_restored_" + kind);
            preset.retainNbt = true;
            if (kind == 1) {
                require(
                    snapshot(world.getTileEntity(destinationX, y, z)).getTagList("parts", 10)
                        .tagCount() == 2,
                    "fmp_true_server_composite_two_parts");
                NBTTagCompound broken = (NBTTagCompound) captured.copy();
                broken.getTagList("parts", 10)
                    .getCompoundTagAt(1)
                    .setString("id", "gtmb:missing_part");
                require(rejected(block, broken), "missing_fmp_part_explicitly_rejected");
                broken = (NBTTagCompound) captured.copy();
                broken.getTagList("parts", 10)
                    .getCompoundTagAt(1)
                    .setString("material", "gtmb:missing_material");
                require(rejected(block, broken), "missing_fmp_material_explicitly_rejected");
                HologramRecovery
                    .restore(world, destinationX, y, z, block, 0, snapshot(world.getTileEntity(destinationX, y, z)));
                require(
                    snapshot(world.getTileEntity(destinationX, y, z)).getTagList("parts", 10)
                        .tagCount() == 2,
                    "fmp_rollback_uses_true_composite_factory");
            }
            unchangedStorage(
                world,
                saveCache,
                mapCache,
                liveStorage,
                "placement_cancel_and_rejected_payload_cache_" + kind);
            kind++;
        }
        require(kind == 6, "all_six_mods_required");
        fmpAeLifecycle(world, player);
        System.out.println(PREFIX + "FINAL PASS mods=" + kind);
    }

    private static NBTTagCompound canonicalSeed(Block block, int x, int y, int z) throws Exception {
        NBTTagCompound tag = new NBTTagCompound();
        TileEntity prototype = block.createTileEntity(HologramResourceWorld.create(block, 0), 0);
        if (prototype != null) prototype.writeToNBT(tag);
        if (block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) tag.setString("id", "savedMultipart");
        NBTTagCompound seed = HologramNativeCompatibilityFixture.seed(block, x, y, z);
        for (Object key : seed.func_150296_c()) tag.setTag(
            (String) key,
            seed.getTag((String) key)
                .copy());
        if (block.getClass()
            .getName()
            .equals("gcewing.architecture.common.block.BlockShape")) {
            // The shared curved-model visual seed has no cladding faces. Use a legitimate two-material shape
            // for this material/payment fixture; RoofTile's official kind always supports cladding.
            Object roof = Class.forName("gcewing.architecture.common.shape.Shape")
                .getField("RoofTile")
                .get(null);
            Object kind = roof.getClass()
                .getField("kind")
                .get(roof);
            require(
                (Boolean) kind.getClass()
                    .getMethod("acceptsCladding")
                    .invoke(kind),
                "architecture_fixture_uses_native_cladding_shape");
            tag.setInteger(
                "Shape",
                roof.getClass()
                    .getField("id")
                    .getInt(roof));
            tag.setString(
                "Name2",
                net.minecraft.block.Block.blockRegistry.getNameForObject(net.minecraft.init.Blocks.stone));
            tag.setInteger("Data2", 0);
        }
        if (block.getClass()
            .getName()
            .equals("appeng.block.networking.BlockCableBus")) {
            ItemStack anchor = appeng.api.AEApi.instance()
                .definitions()
                .parts()
                .cableAnchor()
                .maybeStack(1)
                .get();
            NBTTagCompound stack = new NBTTagCompound();
            anchor.writeToNBT(stack);
            tag.setTag("def:1", stack);
            tag.setTag("extra:1", new NBTTagCompound());
        }
        return tag;
    }

    private static boolean rejected(Block block, NBTTagCompound tag) {
        try {
            HologramNativePlacement.materials(block, 0, tag);
            return false;
        } catch (java.io.IOException expected) {
            return true;
        }
    }

    private static void rfBudget(WorldServer world, EntityPlayerMP player, Block block, NBTTagCompound source,
        ItemStack primary, int x, int y, int z) throws Exception {
        Class<?> powerType = Class.forName("crazypants.enderio.conduit.power.PowerConduit");
        Object power = powerType.getConstructor(int.class)
            .newInstance(0);
        powerType.getMethod("setEnergyStored", int.class)
            .invoke(power, 137);
        require(
            (Integer) powerType.getMethod("getEnergyStored")
                .invoke(power) == 137,
            "official_power_conduit_has_real_rf");
        NBTTagCompound row = new NBTTagCompound();
        Class.forName("crazypants.enderio.conduit.ConduitUtil")
            .getMethod("writeToNBT", Class.forName("crazypants.enderio.conduit.IConduit"), NBTTagCompound.class)
            .invoke(null, power, row);
        NBTTagCompound charged = (NBTTagCompound) source.copy();
        net.minecraft.nbt.NBTTagList rows = charged.getTagList("conduits", 10);
        boolean found = false;
        for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
            .getString("conduitType")
            .equals(powerType.getName())) {
                rows.func_150304_a(i, row);
                found = true;
            }
        require(
            found && row.getCompoundTag("conduit")
                .getInteger("energyStoredRF") == 137,
            "official_conduit_serializer_preserves_rf");
        require(rfRejected(charged), "selected_power_conduit_rf_is_not_free");
        NBTTagCompound legacy = (NBTTagCompound) charged.copy();
        for (int i = 0; i < legacy.getTagList("conduits", 10)
            .tagCount(); i++) {
            NBTTagCompound body = legacy.getTagList("conduits", 10)
                .getCompoundTagAt(i)
                .getCompoundTag("conduit");
            if (!body.hasKey("energyStoredRF")) continue;
            body.setInteger("energyStoredRF", 0);
            body.setFloat("energyStored", 13.7F);
        }
        require(rfRejected(legacy), "legacy_power_conduit_rf_is_not_free");
        NBTTagCompound deselected = HologramBlueprintNbt.decode(
            HologramBlueprintNbt.filterCaptured(
                HologramBlueprintNbt.encodeCaptured(charged),
                java.util.Collections.singletonList("id"),
                true));
        require(
            !deselected.hasKey("conduits") && HologramBlueprintResources.plan(deselected).energy.signum() == 0,
            "deselecting_conduits_removes_rf_restoration_budget");
        require(
            HologramBlueprintResources.plan(source).energy.signum() == 0,
            "zero_rf_native_payload_remains_supported");
        HologramCapture.Cell cell = cell(block, 0, charged, primary, x, y, z);
        Object session = session(player, cell, primary);
        fill(player, HologramNativePlacement.materials(block, 0, source), false);
        ItemStack[] inventory = inventory(player);
        require(
            !operate(session, cell) && "unsupported".equals(cell.status)
                && cell.resourceError.contains("energyStoredRF"),
            "real_service_rejects_unpaid_rf_before_construction");
        require(
            world.isAirBlock(x, y, z) && sameInventory(inventory, player),
            "rf_rejection_preserves_world_and_inventory");
    }

    private static void rfSchema() throws Exception {
        Class<?> powerType = Class.forName("crazypants.enderio.conduit.power.PowerConduit");
        Object power = powerType.getConstructor(int.class)
            .newInstance(0);
        powerType.getMethod("setEnergyStored", int.class)
            .invoke(power, 137);
        NBTTagCompound row = new NBTTagCompound();
        Class.forName("crazypants.enderio.conduit.ConduitUtil")
            .getMethod("writeToNBT", Class.forName("crazypants.enderio.conduit.IConduit"), NBTTagCompound.class)
            .invoke(null, power, row);
        require(
            row.getCompoundTag("conduit")
                .getInteger("energyStoredRF") == 137,
            "rf_guard_fixture_uses_official_power_serializer");
        net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
        rows.appendTag(row);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setTag("conduits", rows);
        require(rfRejected(payload), "official_nonzero_rf_resource_rejected");
        row.getCompoundTag("conduit")
            .setInteger("energyStoredRF", 0);
        require(HologramBlueprintResources.plan(payload).energy.signum() == 0, "official_zero_rf_resource_supported");
        row.getCompoundTag("conduit")
            .setFloat("energyStored", 13.7F);
        require(rfRejected(payload), "official_legacy_nonzero_rf_resource_rejected");
    }

    private static void fmpAeLifecycle(WorldServer world, EntityPlayerMP player) throws Exception {
        List<Block> blocks = HologramNativeCompatibilityFixture.blocks();
        Block fmp = blocks.get(1), ae = blocks.get(5);
        int x = 368, y = 88, z = 96;
        world.getChunkFromBlockCoords(x, z);
        require(world.isAirBlock(x, y, z), "fmp_ae_lifecycle_fixture_fresh");
        NBTTagCompound cableRow = canonicalSeed(ae, x, y, z);
        cableRow.setString("id", "ae2_cablebus");
        NBTTagCompound data = new NBTTagCompound();
        data.setString("id", "savedMultipart");
        data.setInteger("x", x);
        data.setInteger("y", y);
        data.setInteger("z", z);
        net.minecraft.nbt.NBTTagList parts = new net.minecraft.nbt.NBTTagList();
        parts.appendTag(cableRow);
        data.setTag("parts", parts);
        List<ItemStack> costs = HologramNativePlacement.materials(fmp, 0, data);
        ItemStack primary = costs.get(0)
            .copy();
        primary.stackSize = 1;
        HologramCapture.Cell cell = cell(fmp, 0, data, primary, x, y, z);
        Object session = session(player, cell, primary);
        fill(player, costs, false);
        require(
            operate(session, cell) && "placed".equals(cell.status),
            "fmp_ae_actual_service_installs_paid_composite_" + get(session, "status"));
        int owner = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(player.getGameProfile());
        readyFmpAe(world.getTileEntity(x, y, z), owner, "fmp_ae_native_join_and_actor_owner");
        NBTTagCompound captured = snapshot(world.getTileEntity(x, y, z));
        HologramRecovery.restore(world, x, y, z, fmp, 0, captured);
        readyFmpAe(world.getTileEntity(x, y, z), owner, "fmp_ae_rollback_rejoins_native_grid_with_actor_owner");
        require(materialsCount(player) == 0, "fmp_ae_all_parts_paid_once");
    }

    private static void readyFmpAe(TileEntity tile, int owner, String name) throws Exception {
        List<?> parts = (List<?>) tile.getClass()
            .getMethod("jPartList")
            .invoke(tile);
        Object bus = parts.get(0)
            .getClass()
            .getMethod("getCableBus")
            .invoke(parts.get(0));
        appeng.api.parts.IPart cable = (appeng.api.parts.IPart) bus.getClass()
            .getMethod("getPart", net.minecraftforge.common.util.ForgeDirection.class)
            .invoke(bus, net.minecraftforge.common.util.ForgeDirection.UNKNOWN);
        appeng.api.networking.IGridNode node = cable.getGridNode();
        String diagnostic = " tile=" + tile.getClass()
            .getName()
            + " outerPart="
            + parts.get(0)
                .getClass()
                .getName()
            + " cable="
            + cable.getClass()
                .getName()
            + " expectedOwner="
            + owner
            + " actualOwner="
            + (node == null ? "nullNode" : node.getPlayerID());
        System.out.println(PREFIX + "FMP AE LIFECYCLE" + diagnostic);
        require(node != null && node.getPlayerID() == owner, name + diagnostic);
    }

    private static boolean rfRejected(NBTTagCompound payload) {
        try {
            HologramBlueprintResources.plan(payload);
            return false;
        } catch (java.io.IOException expected) {
            return expected.getMessage()
                .contains("conduits");
        }
    }

    private static HologramCapture.Cell cell(Block block, int metadata, NBTTagCompound tag, ItemStack sample, int x,
        int y, int z) throws Exception {
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, y, z);
        cell.block = block;
        cell.meta = metadata;
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(tag);
        cell.itemNbt = sample.hasTagCompound() ? HologramBlueprintNbt.encodeCaptured(sample.getTagCompound()) : "";
        cell.blueprintBlock = Block.blockRegistry.getNameForObject(block);
        cell.blueprintItem = Item.itemRegistry.getNameForObject(sample.getItem());
        cell.blueprintDamage = sample.getItemDamage();
        cell.role = "prefab";
        cell.userPosition = true;
        return cell;
    }

    @SuppressWarnings("unchecked")
    private static Object session(EntityPlayerMP player, HologramCapture.Cell cell, ItemStack primary)
        throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramService$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(player, cell.x, cell.y, cell.z);
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.controllerType = "area";
        HologramPresets.Cell saved = HologramPresets.cell(primary, 0, 0, 0, -1, false);
        saved.block = cell.blueprintBlock;
        saved.metadata = cell.meta;
        saved.tileNbt = cell.tileNbt;
        saved.itemNbt = cell.itemNbt;
        preset.cells.add(saved);
        HologramCapture capture = new HologramCapture(null, player.getHeldItem());
        capture.cells.add(cell);
        capture.positions.put(cell.x + "," + cell.y + "," + cell.z, cell);
        set(session, "virtual", true);
        set(session, "virtualPreset", preset);
        set(session, "capture", capture);
        ((Map<Integer, ItemStack>) get(session, "pins")).put(0, primary.copy());
        ((List<ItemStack>) get(session, "planTargets")).add(primary.copy());
        String key = cell.x + "," + cell.y + "," + cell.z;
        ((Map<String, ItemStack>) get(session, "coordinatePins")).put(key, primary.copy());
        ((Map<Integer, ItemStack>) get(session, "autoTargets")).put(0, primary.copy());
        ((Map<String, ItemStack>) get(session, "frozenAutoTargets")).put(key, primary.copy());
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = false;
        settings.containers = false;
        set(session, "materialSettings", settings);
        return session;
    }

    private static boolean transform(Object session, int x, int y, int z) throws Exception {
        Method method = HologramService.class.getDeclaredMethod(
            "transformVirtual",
            session.getClass(),
            int.class,
            int.class,
            int.class,
            boolean.class,
            boolean.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, session, x, y, z, true, false);
    }

    @SuppressWarnings("unchecked")
    private static ItemStack nativeTargets(Object session, HologramCapture.Cell cell, NBTTagCompound transformed,
        int kind) throws Exception {
        ItemStack primary = ((List<ItemStack>) get(session, "planTargets")).get(0);
        require(
            HologramNativePlacement.matchesSample(cell.block, cell.meta, transformed, primary),
            "rotated_plan_primary_is_exact_native_material_" + kind);
        String key = cell.x + "," + cell.y + "," + cell.z;
        require(
            ItemStack.areItemStacksEqual(primary, ((Map<Integer, ItemStack>) get(session, "pins")).get(0))
                && ItemStack
                    .areItemStacksEqual(primary, ((Map<String, ItemStack>) get(session, "coordinatePins")).get(key))
                && ItemStack.areItemStacksEqual(primary, ((Map<Integer, ItemStack>) get(session, "autoTargets")).get(0))
                && ItemStack
                    .areItemStacksEqual(primary, ((Map<String, ItemStack>) get(session, "frozenAutoTargets")).get(key)),
            "service_rotation_commits_all_native_primary_maps_" + kind);
        HologramPresets.Cell saved = ((HologramPresets.Preset) get(session, "virtualPreset")).cells.get(0);
        ItemStack savedSample = saved.stack();
        savedSample.setTagCompound(HologramBlueprintNbt.nativePayload(HologramBlueprintNbt.decode(saved.itemNbt)));
        require(
            ItemStack.areItemStacksEqual(primary, savedSample),
            "saved_native_primary_stays_exact_after_rotation_" + kind);
        return primary.copy();
    }

    private static boolean operate(Object session, HologramCapture.Cell cell) throws Exception {
        Method method = HologramService.class
            .getDeclaredMethod("operate", session.getClass(), HologramCapture.Cell.class, int.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, session, cell, 0);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object storageCache(String name) throws Exception {
        Field field = net.minecraft.world.World.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void unchangedStorage(WorldServer world, Object save, Object map, Object live, String name)
        throws Exception {
        require(
            storageCache("s_savehandler") == save && storageCache("s_mapStorage") == map && world.mapStorage == live,
            name);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static NBTTagCompound snapshot(TileEntity tile) throws java.io.IOException {
        return HologramNativeCapture.capture(tile);
    }

    private static void fill(EntityPlayerMP player, List<ItemStack> costs, boolean onlyPrimary) {
        for (int slot = 1; slot < player.inventory.mainInventory.length; slot++)
            player.inventory.mainInventory[slot] = null;
        for (int i = 0; i < (onlyPrimary ? 1 : costs.size()); i++) {
            ItemStack stack = costs.get(i)
                .copy();
            if (onlyPrimary) stack.stackSize = 1;
            player.inventory.mainInventory[i + 1] = stack;
        }
    }

    private static int materialsCount(EntityPlayerMP player) {
        int count = 0;
        for (int slot = 1; slot < player.inventory.mainInventory.length; slot++)
            if (player.inventory.mainInventory[slot] != null) count += player.inventory.mainInventory[slot].stackSize;
        return count;
    }

    private static ItemStack[] inventory(EntityPlayerMP player) {
        ItemStack[] copy = new ItemStack[36];
        for (int i = 0; i < copy.length; i++)
            if (player.inventory.mainInventory[i] != null) copy[i] = player.inventory.mainInventory[i].copy();
        return copy;
    }

    private static boolean sameInventory(ItemStack[] before, EntityPlayerMP player) {
        for (int i = 1; i < before.length; i++)
            if (!ItemStack.areItemStacksEqual(before[i], player.inventory.mainInventory[i])) return false;
        return true;
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println(PREFIX + "PASS " + name);
    }

    public static final class Veto {

        final int x, y, z;
        final Block block;
        int events;
        boolean complete;
        String failure = "";

        Veto(int x, int y, int z, Block block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }

        @SubscribeEvent
        public void place(BlockEvent.PlaceEvent event) {
            if (event.x != x || event.y != y || event.z != z) return;
            events++;
            try {
                TileEntity tile = event.world.getTileEntity(x, y, z);
                complete = tile != null && event.world.getBlock(x, y, z) == block
                    && !event.world.isAirBlock(x, y, z)
                    && HologramNativePlacement.materials(block, 0, snapshot(tile))
                        .size() >= 2;
            } catch (java.io.IOException failure) {
                complete = false;
                this.failure = failure.toString();
                failure.printStackTrace(System.err);
            }
            event.setCanceled(true);
        }
    }
}
