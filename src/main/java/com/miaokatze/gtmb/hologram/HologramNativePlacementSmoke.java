package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
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
        HologramNativeCaptureSmoke.run(world);
        Object saveCache = storageCache("s_savehandler");
        Object mapCache = storageCache("s_mapStorage");
        Object liveStorage = world.mapStorage;
        EntityPlayerMP player = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("776dddb0-84e0-46b8-8521-9962604f8c11"), "GTMBNativePlace"));
        // Backhand extends this array before the native container captures its slot indices.
        // Preserve both the array shape and the existing container; only clear this fixture's items.
        java.util.Arrays.fill(player.inventory.mainInventory, null);
        player.inventory.currentItem = 0;
        player.capabilities.isCreativeMode = false;
        player.capabilities.allowEdit = true;
        player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        player.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        player.inventoryContainer.detectAndSendChanges();
        require(
            !player.capabilities.isCreativeMode
                && player.theItemInWorldManager.getGameType() == net.minecraft.world.WorldSettings.GameType.SURVIVAL,
            "native_survival_inventory_shape_main_" + player.inventory.mainInventory.length
                + "_armor_"
                + player.inventory.armorInventory.length
                + "_size_"
                + player.inventory.getSizeInventory()
                + "_container_"
                + player.inventoryContainer.inventorySlots.size());
        detachedReportingParts();
        basicMachineFacingRestore(world, player);
        if (Boolean.getBoolean("gtmb.v0115.largeGtJob")) largeBackgroundGtJob(world, player);
        idleAeHosts();
        stateAndControllerPlacement(world, player);
        rfSchema();
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

    private static void idleAeHosts() throws Exception {
        for (String name : new String[] { "BlockPatternOptimizationMatrix", "BlockStorageReshuffle" }) {
            Block block = Block.getBlockFromName("appliedenergistics2:tile." + name);
            require(block != null, "idle_ae_host_registered_" + name);
            TileEntity original = block.createTileEntity(null, 0);
            if (original instanceof appeng.tile.misc.TileStorageReshuffle) {
                appeng.tile.misc.TileStorageReshuffle reshuffle = (appeng.tile.misc.TileStorageReshuffle) original;
                reshuffle.getTypeFilters()
                    .setEnabled(appeng.util.item.AEItemStackType.ITEM_STACK_TYPE, false);
                reshuffle.getConfigManager()
                    .putSetting(appeng.api.config.Settings.INCLUDE_SUBNETS, appeng.api.config.YesNo.NO);
            }
            NBTTagCompound source = new NBTTagCompound();
            original.writeToNBT(source);
            NBTTagCompound before = (NBTTagCompound) source.copy();
            HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, 0, source);
            require(
                analysis.supported && !analysis.blocking && before.equals(source),
                "native_idle_ae_host_retained_" + name);
            ItemStack primary = HologramAeBlueprint.blockSample(block, 0);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.id = "native-idle-" + name;
            preset.name = preset.id;
            preset.controllerType = "area";
            HologramPresets.Cell saved = HologramPresets.cell(primary, 0, 0, 0, -1, false);
            saved.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
            saved.metadata = 0;
            saved.tileNbt = HologramBlueprintNbt.encodeCaptured(source);
            preset.cells.add(saved);
            HologramAePolicy.Draft draft = HologramAePolicy.sanitize(preset);
            require(
                !draft.blocking && !draft.preset.cells.get(0).excluded,
                "idle_ae_host_never_automatically_excluded_" + name);
            TileEntity restored = HologramNativePlacement.adapter(block)
                .create(block, 0, analysis.payload(), HologramResourceWorld.create(block, 0));
            require(
                restored.getClass()
                    .equals(original.getClass()),
                "idle_ae_host_native_factory_" + name);
            HologramBlueprintResources.Plan plan = HologramAeBlueprint.resourcePlan(block, 0, source, primary);
            require(plan.items.isEmpty() && plan.fluids.isEmpty(), "idle_ae_host_only_charges_primary_shell_" + name);
            if (!(original instanceof appeng.tile.misc.TileStorageReshuffle)) continue;
            NBTTagCompound actual = new NBTTagCompound();
            restored.writeToNBT(actual);
            for (String key : new String[] { "typeFilters", "INCLUDE_SUBNETS", "INSERT_ORDER", "CELL_HEALTH_SORT",
                "SORT_DIRECTION" })
                require(
                    source.getTag(key)
                        .equals(actual.getTag(key)),
                    "reshuffle_native_configuration_roundtrip_" + key);
            String[] buffers = { "cantInject", "extracted", "injectQueue" };
            for (boolean fluid : new boolean[] { false, true }) {
                for (long amount : new long[] { 1L, (long) Integer.MAX_VALUE + 1L, Long.MAX_VALUE }) {
                    appeng.api.storage.data.IAEStack<?> stack = fluid
                        ? appeng.util.item.AEFluidStack.create(
                            new net.minecraftforge.fluids.FluidStack(net.minecraftforge.fluids.FluidRegistry.WATER, 1))
                        : appeng.util.item.AEItemStack.create(new ItemStack(net.minecraft.init.Items.iron_ingot));
                    stack.setStackSize(amount);
                    NBTTagCompound row = appeng.util.Platform.writeStackNBT(stack, new NBTTagCompound(), true);
                    NBTTagCompound buffered = (NBTTagCompound) source.copy();
                    for (String key : buffers) {
                        net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
                        list.appendTag(row.copy());
                        buffered.setTag(key, list);
                    }
                    HologramAeBlueprint.Analysis blocked = HologramAeBlueprint.analyze(block, 0, buffered);
                    require(
                        blocked.supported && blocked.blocking,
                        "reshuffle_physical_buffers_block_without_host_exclusion");
                    for (String key : buffers) require(
                        buffered.getTag(key)
                            .equals(
                                blocked.payload()
                                    .getTag(key)),
                        "unpaid_buffer_preserves_exact_native_nbt_" + key);
                    HologramPresets.Preset bufferedPreset = HologramPresets.copy(preset);
                    bufferedPreset.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(buffered);
                    HologramAePolicy.Draft bufferedDraft = HologramAePolicy.sanitize(bufferedPreset);
                    require(
                        bufferedDraft.blocking && !bufferedDraft.preset.cells.get(0).excluded,
                        "unpaid_reshuffle_draft_keeps_blocking_cell_in_construction_scope");
                    NBTTagCompound retained = HologramBlueprintNbt
                        .nativePayload(HologramBlueprintNbt.decode(bufferedDraft.preset.cells.get(0).tileNbt));
                    for (String key : buffers) require(
                        retained.getTag(key)
                            .equals(buffered.getTag(key)),
                        "draft_processing_preserves_unpaid_custody_root_" + key);
                    if (!fluid && amount == 1L) {
                        TileEntity nativeRead = block.createTileEntity(null, 0);
                        nativeRead.readFromNBT((NBTTagCompound) buffered.copy());
                        NBTTagCompound nativeWritten = new NBTTagCompound();
                        nativeRead.writeToNBT(nativeWritten);
                        require(
                            nativeWritten.getTagList("cantInject", 10)
                                .tagCount() == 1
                                && nativeWritten.getTagList("cantInject", 10)
                                    .getCompoundTagAt(0)
                                    .getLong("Cnt") == 3L,
                            "native_reshuffle_reader_adds_all_three_lists_without_alias_deduplication");
                    }
                    boolean denied = false;
                    try {
                        HologramAeBlueprint.resourcePlan(block, 0, buffered, primary);
                    } catch (java.io.IOException expected) {
                        denied = true;
                    }
                    require(denied, "reshuffle_nonempty_buffers_cannot_be_restored_free");
                    for (int i = 0; i < buffers.length; i++) {
                        blocked = HologramAeBlueprint.updateSlot(block, 0, blocked.payload(), buffers[i], null);
                        require(
                            !blocked.payload()
                                .hasKey(buffers[i]) && blocked.blocking == (i < buffers.length - 1),
                            "explicit_clear_removes_only_selected_custody_buffer_" + buffers[i]);
                    }
                    require(
                        buffered.getTag("injectQueue") != null && before.equals(source),
                        "blueprint_buffer_clear_never_changes_source");
                }
            }
            NBTTagCompound malformed = (NBTTagCompound) source.copy();
            malformed.setString("cantInject", "invalid");
            malformed.setInteger("INCLUDE_SUBNETS", 0);
            malformed.setString("typeFilters", "invalid");
            HologramAeBlueprint.Analysis blocked = HologramAeBlueprint.analyze(block, 0, malformed);
            require(
                blocked.blocking && blocked.payload()
                    .getTag("cantInject")
                    .equals(malformed.getTag("cantInject")),
                "malformed_reshuffle_data_retained_for_explicit_review");
            for (String key : new String[] { "cantInject", "INCLUDE_SUBNETS", "typeFilters" })
                blocked = HologramAeBlueprint.updateSlot(block, 0, blocked.payload(), key, null);
            require(!blocked.blocking, "existing_clear_slot_flow_resolves_retained_reshuffle_blockers");
        }
    }

    private static void detachedReportingParts() throws Exception {
        HologramResourceWorld opaque = HologramResourceWorld.create(net.minecraft.init.Blocks.stone, 0);
        require(
            opaque.getBlockLightOpacity(0, 64, 0) == net.minecraft.init.Blocks.stone.getLightOpacity()
                && opaque.getBlockLightOpacity(0, 64, -1) == 0,
            "detached_opacity_reads_only_its_single_block_and_air_neighbors");
        Block block = Block.getBlockFromName("appliedenergistics2:tile.BlockCableBus");
        require(block != null, "reporting_parts_native_cable_host_registered");
        ItemStack[] terminals = { appeng.api.AEApi.instance()
            .definitions()
            .parts()
            .terminal()
            .maybeStack(1)
            .get(),
            appeng.api.AEApi.instance()
                .definitions()
                .parts()
                .patternTerminal()
                .maybeStack(1)
                .get() };
        for (ItemStack terminal : terminals) {
            NBTTagCompound seed = new NBTTagCompound();
            seed.setString("id", "BlockCableBus");
            seed.setInteger("x", 16);
            seed.setInteger("y", 64);
            seed.setInteger("z", 16);
            seed.setTag("def:2", terminal.writeToNBT(new NBTTagCompound()));
            seed.setTag("extra:2", new NBTTagCompound());
            seed.setTag(
                "def:6",
                appeng.api.AEApi.instance()
                    .definitions()
                    .parts()
                    .cableGlass()
                    .stack(appeng.api.util.AEColor.Transparent, 1)
                    .writeToNBT(new NBTTagCompound()));
            seed.setTag("extra:6", new NBTTagCompound());
            NBTTagCompound before = (NBTTagCompound) seed.copy();
            HologramResourceWorld detached = HologramResourceWorld.create(block, 0);
            TileEntity tile = HologramNativePlacement.adapter(block)
                .create(block, 0, seed, detached);
            require(tile instanceof appeng.tile.networking.TileCableBus, "reporting_part_native_factory_retains_host");
            appeng.tile.networking.TileCableBus host = (appeng.tile.networking.TileCableBus) tile;
            appeng.api.parts.IPart part = host.getPart(net.minecraftforge.common.util.ForgeDirection.NORTH);
            require(
                part != null && part.getItemStack(appeng.api.parts.PartItemStack.World)
                    .isItemEqual(terminal)
                    && host.getPart(net.minecraftforge.common.util.ForgeDirection.UNKNOWN) != null
                    && tile.getWorldObj() == detached
                    && before.equals(seed),
                "native_reporting_part_reader_needs_no_real_chunks_" + terminal.getItemDamage());
        }
    }

    private static void basicMachineFacingRestore(WorldServer world, EntityPlayerMP player) throws Exception {
        Block block = gregtech.api.GregTechAPI.sBlockMachines;
        ItemStack primary = new ItemStack(block, 1, 103);
        require(
            gregtech.common.blocks.ItemMachines
                .getMetaTileEntity(primary) instanceof gregtech.api.metatileentity.implementations.MTEBasicMachine,
            "facing_fixture_uses_actual_registered_basic_machine");
        int sourceX = 544, y = 88, z = 160;
        float oldYaw = player.rotationYaw, oldPitch = player.rotationPitch;
        try {
            player.rotationYaw = 180;
            player.rotationPitch = 0;
            // provideChunk may return EmptyChunk while this pack throttles generation. Its writes
            // report success but reads stay air, so load and verify the actual fixture chunks first.
            for (int chunkX = sourceX >> 4; chunkX <= (568 >> 4); chunkX++) {
                Chunk loaded = world.theChunkProviderServer.loadChunk(chunkX, z >> 4);
                require(
                    loaded != null && !(loaded instanceof EmptyChunk)
                        && loaded.xPosition == chunkX
                        && loaded.zPosition == (z >> 4)
                        && loaded.isChunkLoaded
                        && world.theChunkProviderServer.chunkExists(chunkX, z >> 4)
                        && world.getChunkFromChunkCoords(chunkX, z >> 4) == loaded,
                    "basic_facing_genuine_loaded_fixture_chunk_" + chunkX);
            }
            for (int x = sourceX; x <= 568; x += 4) {
                require(world.isAirBlock(x, y, z), "basic_facing_fresh_position_" + x);
            }
            require(
                ((gregtech.common.blocks.ItemMachines) primary.getItem())
                    .placeBlockAt(primary.copy(), player, world, sourceX, y, z, 1, .5F, .5F, .5F, 0),
                "basic_facing_native_source_placement");
            gregtech.api.interfaces.tileentity.IGregTechTileEntity source = (gregtech.api.interfaces.tileentity.IGregTechTileEntity) world
                .getTileEntity(sourceX, y, z);
            gregtech.api.metatileentity.implementations.MTEBasicMachine basic = (gregtech.api.metatileentity.implementations.MTEBasicMachine) source
                .getMetaTileEntity();
            require(
                basic.mMainFacing == net.minecraftforge.common.util.ForgeDirection.SOUTH
                    && !basic.isFacingValid(net.minecraftforge.common.util.ForgeDirection.SOUTH),
                "actor_yaw_180_initializes_main_south_which_rejects_output_south");
            basic.setMainFacing(net.minecraftforge.common.util.ForgeDirection.NORTH);
            source.setFrontFacing(net.minecraftforge.common.util.ForgeDirection.SOUTH);
            NBTTagCompound retained = snapshot((TileEntity) source);
            require(
                retained.getInteger("mMainFacing") == 2 && retained.getShort("mFacing") == 3,
                "basic_native_source_has_distinct_main_north_and_output_south");
            for (int scenario = 0; scenario < 3; scenario++) {
                int x = 560 + scenario * 4;
                HologramCapture.Cell target = cell(
                    block,
                    world.getBlockMetadata(sourceX, y, z),
                    retained,
                    primary,
                    x,
                    y,
                    z);
                Object session = session(player, target, primary);
                @SuppressWarnings("unchecked")
                Map<Integer, Integer> facings = (Map<Integer, Integer>) get(session, "planFacings");
                facings.put(0, scenario == 1 ? 2 : 3);
                if (scenario == 2) {
                    target.tileNbt = "";
                    ((HologramPresets.Preset) get(session, "virtualPreset")).cells.get(0).tileNbt = "";
                }
                fill(player, java.util.Collections.singletonList(primary), false);
                ItemStack[] before = inventory(player);
                boolean placed = operate(session, target);
                HologramSession actualSession = (HologramSession) session;
                TileEntity actualTile = world.getTileEntity(x, y, z);
                String actualIdentity = actualTile == null ? "none"
                    : actualTile.getClass()
                        .getName();
                if (actualTile instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity) {
                    gregtech.api.interfaces.tileentity.IGregTechTileEntity actualGt = (gregtech.api.interfaces.tileentity.IGregTechTileEntity) actualTile;
                    actualIdentity += ":id=" + actualGt.getMetaTileID() + ":front=" + actualGt.getFrontFacing();
                    if (actualGt
                        .getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEBasicMachine)
                        actualIdentity += ":main="
                            + ((gregtech.api.metatileentity.implementations.MTEBasicMachine) actualGt
                                .getMetaTileEntity()).mMainFacing;
                }
                String outcome = "scenario=" + scenario
                    + ", returned="
                    + placed
                    + ", cellStatus="
                    + target.status
                    + ", sessionStatus="
                    + actualSession.status
                    + ", resourceError="
                    + target.resourceError
                    + ", block="
                    + Block.blockRegistry.getNameForObject(world.getBlock(x, y, z))
                    + ", metadata="
                    + world.getBlockMetadata(x, y, z)
                    + ", tile="
                    + actualIdentity
                    + ", materialsRemaining="
                    + materialsCount(player)
                    + ", inventoryRestored="
                    + sameInventory(before, player);
                System.out.println(PREFIX + "BASIC FACING TRANSACTION " + outcome);
                if (scenario == 0) {
                    require(
                        placed && "placed".equals(target.status),
                        "basic_full_nbt_restores_before_output_validation [" + outcome + "]");
                    NBTTagCompound actual = snapshot(world.getTileEntity(x, y, z));
                    require(
                        actual.getInteger("mID") == primary.getItemDamage() && actual.getInteger("mMainFacing") == 2
                            && actual.getShort("mFacing") == 3
                            && materialsCount(player) == 0,
                        "basic_main_and_output_restored_with_exactly_one_paid_shell");
                } else require(
                    !placed && world.isAirBlock(x, y, z)
                        && world.getTileEntity(x, y, z) == null
                        && sameInventory(before, player),
                    (scenario == 1 ? "invalid_output_equal_to_restored_main_rolls_back_and_refunds"
                        : "omitted_nbt_keeps_native_facing_validation_and_refunds") + " [" + outcome + "]");
            }
        } finally {
            player.rotationYaw = oldYaw;
            player.rotationPitch = oldPitch;
        }
        backgroundGtJobs(world, player);
    }

    /** Small real GT contract: retained hatch/controller state, both payment modes, and actual job continuation. */
    private static void backgroundGtJobs(WorldServer world, EntityPlayerMP player) throws Exception {
        int originalSlot = player.inventory.currentItem;
        boolean originalCreative = player.capabilities.isCreativeMode;
        Block block = gregtech.api.GregTechAPI.sBlockMachines;
        try {
            player.inventory.currentItem = 0;
            List<Integer> identities = new ArrayList<>(java.util.Arrays.asList(51, 1000));
            if (cpw.mods.fml.common.Loader.isModLoaded("gtsr")) {
                int addonHatch = -1;
                for (int id = 1; id < gregtech.api.GregTechAPI.METATILEENTITIES.length; id++) {
                    gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = gregtech.api.GregTechAPI.METATILEENTITIES[id];
                    if (prototype instanceof gregtech.api.metatileentity.implementations.MTEHatch
                        && prototype.getClass()
                            .getName()
                            .equals("com.miaokatze.gtsr.common.machine.base.MTESteamInputHatchGeneric")) {
                        addonHatch = id;
                        break;
                    }
                }
                require(addonHatch > 0, "background_gt_loaded_addon_generic_hatch_registered");
                identities.add(addonHatch);
            } else System.out.println(PREFIX + "BACKGROUND GT GTSR coverage=NOT_LOADED");
            for (int chunk = 600 >> 4; chunk <= 664 >> 4; chunk++) {
                Chunk loaded = world.theChunkProviderServer.loadChunk(chunk, 160 >> 4);
                require(
                    loaded != null && !(loaded instanceof EmptyChunk) && loaded.isChunkLoaded,
                    "background_gt_actual_loaded_chunk_" + chunk);
            }
            int scenario = 0, sourceIndex = 0;
            for (int id : identities) {
                ItemStack primary = new ItemStack(block, 1, id);
                int sourceX = 600 + sourceIndex++ * 4;
                require(world.isAirBlock(sourceX, 88, 160), "background_gt_fresh_source_" + id);
                require(
                    ((gregtech.common.blocks.ItemMachines) primary.getItem())
                        .placeBlockAt(primary.copy(), player, world, sourceX, 88, 160, 1, .5F, .5F, .5F, 0),
                    "background_gt_real_item_source_" + id);
                TileEntity source = world.getTileEntity(sourceX, 88, 160);
                NBTTagCompound retained = snapshot(source);
                for (boolean creative : new boolean[] { false, true }) {
                    player.inventory.currentItem = 0;
                    player.capabilities.isCreativeMode = creative;
                    int destinationX = 640 + scenario++ * 4;
                    require(world.isAirBlock(destinationX, 88, 160), "background_gt_fresh_destination_" + scenario);
                    HologramCapture.Cell target = cell(
                        block,
                        world.getBlockMetadata(sourceX, 88, 160),
                        retained,
                        primary,
                        destinationX,
                        88,
                        160);
                    // The captured native item is authoritative even when a structure wrapper advertised
                    // only a different editable candidate. No add-on name or hatch-adder is involved.
                    target.structureTargets = java.util.Collections.singletonList(new ItemStack(block, 1, 1000));
                    HologramSession s = (HologramSession) session(player, target, primary);
                    s.virtualPreset.deidentificationLevel = 1;
                    s.virtualPreset.deidentificationChosen = true;
                    if (id == 1000) {
                        target.role = "templateController";
                        s.virtualPreset.controllerId = id;
                        s.facing = ((gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>) ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) source)
                            .getMetaTileEntity()).getExtendedFacing()
                                .ordinal();
                    }
                    s.planFacings.put(0, retained.getShort("mFacing") & 0xffff);
                    s.expectedBlocks.add(world.getBlock(destinationX, 88, 160));
                    s.expectedMeta.add(world.getBlockMetadata(destinationX, 88, 160));
                    s.expectedTiles.add(null);
                    s.expectedFacings.add(-1);
                    s.work.add(0);
                    s.job = 1;
                    fill(player, java.util.Collections.singletonList(primary), false);
                    player.inventory.currentItem = 8;
                    require(
                        !HologramSessionPolicy.valid(s) && HologramSessionPolicy.jobValid(s),
                        "background_gt_keeps_admission_strict_" + scenario);
                    HologramPlacementJobs.tickJob(s);
                    require(
                        s.job == 3 && s.completed == 1 && "placed".equals(target.status),
                        "background_gt_actual_job_places_" + id + "_creative_" + creative + "_status_" + s.status);
                    TileEntity actual = world.getTileEntity(destinationX, 88, 160);
                    require(
                        actual instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity,
                        "background_gt_real_tile_" + scenario);
                    gregtech.api.interfaces.tileentity.IGregTechTileEntity gt = (gregtech.api.interfaces.tileentity.IGregTechTileEntity) actual;
                    require(
                        gt.getMetaTileID() == id && gt.getMetaTileEntity() != null
                            && gt.getFrontFacing()
                                .ordinal() == retained.getShort("mFacing")
                            && world.getBlockMetadata(destinationX, 88, 160) == target.meta,
                        "background_gt_paid_identity_native_state_and_facing_" + scenario);
                    require(
                        materialsCount(player) == (creative ? 1 : 0),
                        "background_gt_exact_shell_payment_" + scenario);
                    System.out.println(
                        PREFIX + "BACKGROUND GT CONTRACT id="
                            + id
                            + " class="
                            + gt.getMetaTileEntity()
                                .getClass()
                                .getName()
                            + " creative="
                            + creative
                            + " selectedSlot="
                            + player.inventory.currentItem
                            + " result=PASS"
                            + " originalCandidateOmitted="
                            + target.structureTargets.stream()
                                .noneMatch(option -> HologramValues.sameMaterial(option, primary))
                            + " job="
                            + s.job
                            + " placed="
                            + s.completed);
                }
            }
        } finally {
            player.inventory.currentItem = originalSlot;
            player.capabilities.isCreativeMode = originalCreative;
        }
    }

    /** Opt-in 64k real transaction contract; stays inside the disposable smoke world. */
    private static void largeBackgroundGtJob(WorldServer world, EntityPlayerMP player) throws Exception {
        final int count = 64000, controllers = 100, x = 800, y = 96, z = 800;
        int originalSlot = player.inventory.currentItem;
        boolean originalCreative = player.capabilities.isCreativeMode;
        double oldX = player.posX, oldY = player.posY, oldZ = player.posZ;
        net.minecraft.inventory.Container originalContainer = player.openContainer;
        HologramSession previousWorkspace = HologramWorkspace.find(player.getUniqueID());
        Block machines = gregtech.api.GregTechAPI.sBlockMachines;
        List<Integer> ids = new ArrayList<>(java.util.Arrays.asList(1000, 51));
        for (int id = 1; id < gregtech.api.GregTechAPI.METATILEENTITIES.length; id++) {
            gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = gregtech.api.GregTechAPI.METATILEENTITIES[id];
            if (prototype instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase
                && id != 1000) {
                ids.add(id);
                break;
            }
        }
        require(ids.size() == 3, "large_gt_second_native_controller_registered");
        try {
            player.inventory.currentItem = 0;
            player.capabilities.isCreativeMode = true;
            for (int cx = (x - 32) >> 4; cx <= (x + 40) >> 4; cx++)
                for (int cz = z >> 4; cz <= (z + 40) >> 4; cz++) world.theChunkProviderServer.loadChunk(cx, cz);
            ItemStack stone = new ItemStack(net.minecraft.init.Blocks.stone);
            HologramCapture.Cell seed = cell(net.minecraft.init.Blocks.stone, 0, new NBTTagCompound(), stone, x, y, z);
            HologramSession s = (HologramSession) session(player, seed, stone);
            s.capture.cells.clear();
            s.capture.positions.clear();
            s.pins.clear();
            s.planTargets.clear();
            s.virtualPreset.cells.clear();
            s.virtualPreset.id = "large_gt_background_fixture";
            s.virtualPreset.name = "64k mixed GT background";
            s.virtualPreset.controllerId = -1; // Composite area: each controller restores its own captured state.
            s.virtualPreset.retainNbt = true;
            s.virtualPlacement = s.virtualLocked = true;
            List<NBTTagCompound> states = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                ItemStack material = new ItemStack(machines, 1, ids.get(i));
                int sourceX = x - 24 + i * 4;
                require(world.isAirBlock(sourceX, y, z), "large_gt_fresh_source_" + i);
                require(
                    ((gregtech.common.blocks.ItemMachines) material.getItem())
                        .placeBlockAt(material, player, world, sourceX, y, z, 1, .5F, .5F, .5F, 0),
                    "large_gt_native_source_" + i);
                ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) world.getTileEntity(sourceX, y, z))
                    .setFrontFacing(net.minecraftforge.common.util.ForgeDirection.NORTH);
                states.add(snapshot(world.getTileEntity(sourceX, y, z)));
            }
            for (int i = 0; i < count; i++) {
                int px = x + i % 40, py = y + i / 1600, pz = z + i / 40 % 40;
                if (!world.isAirBlock(px, py, pz)) throw new AssertionError("large_gt_fresh_destination_" + i);
                int sourceKind = i < controllers ? (i % 2 == 0 ? 0 : 2) : i == controllers ? 1 : -1;
                ItemStack material = sourceKind >= 0 ? new ItemStack(machines, 1, ids.get(sourceKind)) : stone;
                HologramCapture.Cell c;
                if (sourceKind >= 0) c = cell(
                    machines,
                    world.getBlockMetadata(x - 24 + sourceKind * 4, y, z),
                    states.get(sourceKind),
                    material,
                    px,
                    py,
                    pz);
                else {
                    c = new HologramCapture.Cell(null, px, py, pz);
                    c.block = net.minecraft.init.Blocks.stone;
                    c.blueprintBlock = "minecraft:stone";
                    c.blueprintItem = "minecraft:stone";
                    c.role = "prefab";
                    c.userPosition = true;
                }
                if (i < controllers) c.role = "templateController";
                s.capture.cells.add(c);
                s.capture.positions.put(HologramValues.position(c), c);
                s.pins.put(i, material.copy());
                s.planTargets.add(material.copy());
                s.work.add(i);
                s.expectedBlocks.add(net.minecraft.init.Blocks.air);
                s.expectedMeta.add(0);
                s.expectedTiles.add(null);
                s.expectedFacings.add(-1);
                if (sourceKind >= 0) s.planFacings.put(i, 2);
            }
            long spatialSortNanos = HologramPlacementJobs.spatialWorkOrder(s);
            require(
                s.work.get(0) == 0 && s.work.get(controllers - 1) == controllers - 1
                    && s.work.get(count - 1) == count - 1,
                "large_gt_controllers_follow_the_same_spatial_order_as_the_body");
            player.setPosition(x + .5, y, z + .5);
            require(HologramSessionPolicy.valid(s), "large_gt_position_admitted_near_anchor");
            s.job = 1;
            HologramWorkspace.activate(player.getUniqueID(), s);
            int ticks = 0;
            boolean walked = false;
            while (s.job == 1 && ticks++ < count + 100) {
                if (!walked && s.cursor > 0) {
                    require(
                        "placed".equals(s.capture.cells.get(0).status) && world
                            .getTileEntity(x, y, z) instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity,
                        "large_gt_first_spatial_controller_is_already_placed");
                    player.setPosition(x + 160, y, z + .5);
                    player.inventory.currentItem = 8;
                    walked = true;
                    require(
                        !HologramSessionPolicy.valid(s) && HologramSessionPolicy.jobValid(s),
                        "large_gt_walk_and_hotbar_change_keep_only_runtime_authority");
                    player.openContainer = player.inventoryContainer;
                    NBTTagCompound lifecycle = new NBTTagCompound();
                    lifecycle.setString("session", s.id);
                    lifecycle.setString("op", "close");
                    HologramService.handle(player, lifecycle);
                    require(
                        s.job == 1 && HologramWorkspace.find(player.getUniqueID()) == s,
                        "large_gt_actual_close_keeps_admitted_job");
                    long openingSequence = s.areaSelectionSequence;
                    HologramProjectorWorkflow.openAir(player);
                    require(
                        s.areaSelectionSequence > openingSequence && s.job == 1
                            && HologramWorkspace.find(player.getUniqueID()) == s,
                        "large_gt_reopen_keeps_same_session_beyond_reach");
                    lifecycle.setString("op", "pause");
                    HologramService.handle(player, lifecycle);
                    require(s.job == 2, "large_gt_actual_pause_beyond_reach");
                    int pausedCursor = s.cursor;
                    HologramPlacementJobs.tickJob(s);
                    require(s.cursor == pausedCursor, "large_gt_paused_job_does_not_mutate_world");
                    lifecycle.setString("op", "resume");
                    HologramService.handle(player, lifecycle);
                    require(s.job == 1, "large_gt_actual_resume_beyond_reach");
                    lifecycle.setString("op", "close");
                    HologramService.handle(player, lifecycle);
                    HologramSnapshotPreparation.cancel(s);
                    s.open = false;
                }
                if (ticks <= 2) s.stopTick = true; // Walk between genuine slices in spatial construction order.
                HologramPlacementJobs.tickJob(s);
            }
            require(walked && s.job == 3 && s.completed == count, "large_gt_64000_actual_transactions_complete");
            for (int i = 0; i < count; i++) {
                HologramCapture.Cell c = s.capture.cells.get(i);
                if (!"placed".equals(c.status) || world.getBlock(c.x, c.y, c.z) != c.block)
                    throw new AssertionError("large_gt_actual_world_cell_" + i);
                if (i <= controllers) {
                    int sourceKind = i < controllers ? (i % 2 == 0 ? 0 : 2) : 1;
                    gregtech.api.interfaces.tileentity.IGregTechTileEntity gt = (gregtech.api.interfaces.tileentity.IGregTechTileEntity) world
                        .getTileEntity(c.x, c.y, c.z);
                    require(
                        gt.getMetaTileID() == ids.get(sourceKind) && gt.getFrontFacing()
                            .ordinal() == 2,
                        "large_gt_final_identity_and_facing_" + i);
                }
            }
            System.out.println(
                PREFIX + "LARGE BACKGROUND GT CONTRACT cells="
                    + count
                    + " controllers="
                    + controllers
                    + " ticks="
                    + ticks
                    + " spatialSortNanos="
                    + spatialSortNanos
                    + " walkedBeyondPreviewReach=true selectedSlot=8 closeReopenPauseResume=true result=PASS");
        } finally {
            if (previousWorkspace != null) HologramWorkspace.activate(player.getUniqueID(), previousWorkspace);
            else {
                Field sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
                sessions.setAccessible(true);
                ((Map<?, ?>) sessions.get(null)).remove(player.getUniqueID());
            }
            player.inventory.currentItem = originalSlot;
            player.capabilities.isCreativeMode = originalCreative;
            player.openContainer = originalContainer;
            player.setPosition(oldX, oldY, oldZ);
        }
    }

    /** Regression for real area imports whose item, world state, or GT controller metadata differ. */
    private static void stateAndControllerPlacement(WorldServer world, EntityPlayerMP player) throws Exception {
        String reactorType = "gregtech.common.tileentities.machines.multi.MTELargeNaquadahReactor";
        int reactorId = -1;
        for (int id = 1; id < gregtech.api.GregTechAPI.METATILEENTITIES.length; id++) {
            gregtech.api.interfaces.metatileentity.IMetaTileEntity machine = gregtech.api.GregTechAPI.METATILEENTITIES[id];
            if (machine != null && machine.getClass()
                .getName()
                .equals(reactorType)) {
                reactorId = id;
                break;
            }
        }
        require(reactorId > 0, "area_controller_real_large_naquadah_reactor_registered");
        Block[] blocks = { net.minecraft.init.Blocks.lit_redstone_lamp,
            registeredBlock("gcewing.architecture.common.block.BlockSawbench"),
            registeredBlock("gcewing.architecture.common.block.BlockShape"), gregtech.api.GregTechAPI.sBlockMachines };
        for (int kind : new int[] { 0, 1, 3, 2 }) {
            int sourceX = 416 + kind * 4, destinationX = 464 + kind * 4, y = 88, z = 128;
            for (int x = sourceX - 16; x <= destinationX + 16; x += 8)
                for (int zz = z - 16; zz <= z + 16; zz += 8) world.getChunkFromBlockCoords(x, zz);
            require(
                world.isAirBlock(sourceX, y, z) && world.isAirBlock(destinationX, y, z),
                "state_controller_fresh_fixture_" + kind);
            Block block = blocks[kind];
            if (kind == 0) world.setBlock(sourceX, y - 1, z, net.minecraft.init.Blocks.redstone_block, 0, 3);
            if (kind == 3) {
                ItemStack controller = new ItemStack(block, 1, reactorId);
                require(
                    ((gregtech.common.blocks.ItemMachines) controller.getItem())
                        .placeBlockAt(controller, player, world, sourceX, y, z, 1, .5F, .5F, .5F, 0),
                    "state_controller_native_gt_item_source");
            } else require(
                world.setBlock(sourceX, y, z, kind == 0 ? net.minecraft.init.Blocks.redstone_lamp : block, 0, 3),
                "state_controller_real_source_" + kind);
            if (kind == 0) require(world.getBlock(sourceX, y, z) == block, "native_powered_lamp_source_state");
            if (kind == 2)
                HologramNativePlacement.install(world, sourceX, y, z, block, 0, canonicalSeed(block, sourceX, y, z));
            if (kind == 3) {
                gregtech.api.metatileentity.BaseMetaTileEntity base = (gregtech.api.metatileentity.BaseMetaTileEntity) world
                    .getTileEntity(sourceX, y, z);
                base.setFrontFacing(net.minecraftforge.common.util.ForgeDirection.NORTH);
                require(
                    base.getMetaTileEntity()
                        .getClass()
                        .getName()
                        .equals(reactorType),
                    "reactor_source_has_actual_registered_machine");
            }
            TileEntity sourceTile = world.getTileEntity(sourceX, y, z);
            NBTTagCompound before = sourceTile == null ? null : snapshot(sourceTile);
            HologramArea area = new HologramArea();
            area.point(1, sourceX, y, z);
            area.point(2, sourceX, y, z);
            area.begin(world);
            for (int attempt = 0; attempt < 256 && !area.imported; attempt++) area.tick(player, player.getHeldItem());
            require(area.imported && area.blueprint.cells.size() == 1, "real_area_capture_completes_" + kind);
            HologramPresets.Cell saved = area.blueprint.cells.get(0);
            ItemStack primary = saved.stack();
            require(
                !saved.missing() && primary != null && primary.getItem() != null,
                "real_area_capture_has_payable_identity_" + kind);
            require(
                saved.block()
                    .equals(block),
                "real_area_capture_retains_native_block_identity_" + kind);
            if (kind == 3) {
                require(
                    saved.controller && primary.getItemDamage() == reactorId,
                    "reactor_area_import_keeps_controller_flag_and_full_meta_id");
                HologramPresets.Structure declared = new HologramPresets.Structure();
                declared.id = sourceX + "," + y + "," + z;
                declared.name = "Large Naquadah Reactor";
                declared.controllerId = reactorId;
                declared.sourceClass = reactorType;
                area.blueprint.structures.add(declared);
                saved.structureId = declared.id;
                saved.structureIds = java.util.Collections.singletonList(declared.id);
            }
            HologramCapture.Cell placeholder = cell(
                block,
                saved.metadata,
                sourceTile == null ? new NBTTagCompound() : before,
                primary,
                destinationX,
                y,
                z);
            Object session = session(player, placeholder, primary);
            set(session, "virtualPreset", area.blueprint);
            Method populate = HologramBlueprintProjection.class
                .getDeclaredMethod("populateVirtual", session.getClass());
            populate.setAccessible(true);
            populate.invoke(null, session);
            HologramCapture capture = (HologramCapture) get(session, "capture");
            require(!capture.incomplete && capture.cells.size() == 1, "real_area_virtual_population_" + kind);
            HologramCapture.Cell target = capture.cells.get(0);
            if (kind == 3) require(
                !target.anchor && "templateController".equals(target.role),
                "virtual_reactor_controller_is_a_construction_target");
            if (kind == 3) {
                ((java.util.Map<?, ?>) get(session, "pins")).clear();
                ((java.util.Map<?, ?>) get(session, "coordinatePins")).clear();
                Method refresh = HologramResourcePlanner.class.getDeclaredMethod("refreshPlan", session.getClass());
                refresh.setAccessible(true);
                refresh.invoke(null, session);
                @SuppressWarnings("unchecked")
                List<ItemStack> restoredTargets = (List<ItemStack>) get(session, "planTargets");
                require(
                    restoredTargets.get(0) != null && restoredTargets.get(0)
                        .getItemDamage() == reactorId && "pending".equals(target.status),
                    "cleared_reactor_pin_keeps_registered_default_target_and_pending_cost");
                target.sourceWarning = "fixture invalid controller source";
                Method classify = HologramPlanPolicy.class
                    .getDeclaredMethod("classify", session.getClass(), HologramCapture.Cell.class, int.class);
                classify.setAccessible(true);
                require(
                    "unsupported".equals(classify.invoke(null, session, target, 0)),
                    "cleared_reactor_pin_does_not_bypass_source_identity_warning");
                target.sourceWarning = "";
            }
            List<ItemStack> payment = kind == 2
                ? HologramNativePlacement.materials(block, saved.metadata, HologramBlueprintNbt.decode(saved.tileNbt))
                : java.util.Collections.singletonList(primary);
            if (kind == 2) {
                ItemStack actualSample = HologramArea.serverSample(block, saved.metadata, sourceTile);
                require(
                    actualSample != null && actualSample.hasTagCompound()
                        && HologramNativePlacement.matchesSample(
                            block,
                            saved.metadata,
                            HologramBlueprintNbt.decode(saved.tileNbt),
                            actualSample),
                    "produced_shape_payment_is_actual_native_tagged_sample_at_level_zero");
                require(
                    payment.stream()
                        .anyMatch(stack -> ItemStack.areItemStacksEqual(stack, actualSample)),
                    "produced_shape_native_cost_contains_actual_tagged_sample");
            }
            fill(player, payment, false);
            require(
                operate(session, target) && "placed".equals(target.status),
                "real_area_service_places_state_or_controller_" + kind
                    + "_"
                    + target.status
                    + "_"
                    + get(session, "status")
                    + "_"
                    + target.resourceError);
            require(materialsCount(player) == 0, "real_area_placement_pays_one_primary_exactly_once_" + kind);
            require(
                HologramBlockIdentity.sameForPlacement(
                    block,
                    saved.metadata,
                    world.getBlock(destinationX, y, z),
                    world.getBlockMetadata(destinationX, y, z)) || kind == 3,
                "real_area_world_has_expected_native_state_" + kind);
            TileEntity placed = world.getTileEntity(destinationX, y, z);
            if (kind == 0) require(
                world.getBlock(destinationX, y, z) == net.minecraft.init.Blocks.redstone_lamp,
                "unpowered_destination_uses_native_off_lamp_state");
            else require(
                placed != null && placed.getClass()
                    .equals(sourceTile.getClass()),
                "real_area_world_contains_actual_native_tile_" + kind);
            if (kind == 2) {
                NBTTagCompound expected = HologramBlueprintNbt.decode(saved.tileNbt);
                NBTTagCompound actual = snapshot(placed);
                System.out.println(PREFIX + "ARCHITECTURE SHAPE PERSISTENCE source=" + before + " target=" + actual);
                require(
                    HologramNativePlacement.matchesPlaced(block, saved.metadata, placed, expected),
                    "produced_architecture_shape_preserves_material_and_shape");
                NBTTagCompound changed = (NBTTagCompound) expected.copy();
                changed.setInteger("Shape", expected.getInteger("Shape") == 0 ? 1 : 0);
                require(
                    !HologramNativePlacement.matchesPlaced(block, saved.metadata, placed, changed),
                    "produced_architecture_shape_rejects_different_shape");
                changed = (NBTTagCompound) expected.copy();
                changed.setString(
                    "BaseName",
                    expected.getString("BaseName")
                        .equals("minecraft:stone") ? "minecraft:planks" : "minecraft:stone");
                require(
                    !HologramNativePlacement.matchesPlaced(block, saved.metadata, placed, changed),
                    "produced_architecture_shape_rejects_different_base_material");
                changed = (NBTTagCompound) expected.copy();
                changed.setString(
                    "Name2",
                    expected.getString("Name2")
                        .equals("minecraft:glass") ? "minecraft:stone" : "minecraft:glass");
                require(
                    !HologramNativePlacement.matchesPlaced(block, saved.metadata, placed, changed),
                    "produced_architecture_shape_rejects_different_secondary_material");
                changed = (NBTTagCompound) expected.copy();
                changed.setByte("offsetX", (byte) (expected.getByte("offsetX") == 0 ? 1 : 0));
                require(
                    !HologramNativePlacement.matchesPlaced(block, saved.metadata, placed, changed),
                    "produced_architecture_shape_rejects_different_native_offset");
            }
            if (kind == 3) {
                require(
                    world.getBlock(destinationX, y, z) == gregtech.api.GregTechAPI.sBlockMachines
                        && placed instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity,
                    "virtual_reactor_world_contains_actual_gt_controller_block");
                gregtech.api.interfaces.tileentity.IGregTechTileEntity gt = (gregtech.api.interfaces.tileentity.IGregTechTileEntity) placed;
                require(
                    gt.getMetaTileID() == reactorId && gt.getMetaTileEntity() != null
                        && gt.getMetaTileEntity()
                            .getClass()
                            .getName()
                            .equals(reactorType),
                    "virtual_reactor_world_controller_has_exact_registered_machine_id");
            }
            require(
                sourceTile == null || before.equals(snapshot(sourceTile)),
                "real_area_capture_and_placement_leave_source_unchanged_" + kind);
            System.out.println(
                PREFIX + "AREA STATE CONTROLLER PASS kind="
                    + kind
                    + " source="
                    + Block.blockRegistry.getNameForObject(block)
                    + " primary="
                    + Item.itemRegistry.getNameForObject(primary.getItem())
                    + "@"
                    + primary.getItemDamage());
        }
    }

    private static Block registeredBlock(String className) {
        for (Object registered : Block.blockRegistry) if (registered instanceof Block && registered.getClass()
            .getName()
            .equals(className)) return (Block) registered;
        throw new AssertionError("Installed state/controller fixture requires " + className);
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
        require(
            HologramEnergyPayload.prepare(charged, null).demand.rf.intValueExact() == 137,
            "selected_power_conduit_rf_has_exact_paid_energy_demand");
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
        require(
            HologramEnergyPayload.prepare(legacy, null).demand.rf.compareTo(new java.math.BigDecimal("137")) == 0,
            "legacy_power_conduit_rf_has_equivalent_paid_energy_demand");
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
        java.math.BigInteger oldBalance = gregtech.common.misc.WirelessNetworkManager.getUserEU(player.getUniqueID());
        try {
            gregtech.common.misc.WirelessNetworkManager.setUserEU(player.getUniqueID(), java.math.BigInteger.ZERO);
            require(
                operate(session, cell) && world.getBlock(x, y, z) == block,
                "unfunded_rf_still_places_paid_physical_conduit_with_empty_buffer");
            NBTTagCompound unpaid = snapshot(world.getTileEntity(x, y, z));
            require(
                HologramEnergyPayload.prepare(unpaid, null).demand.rf.signum() == 0
                    && gregtech.common.misc.WirelessNetworkManager.getUserEU(player.getUniqueID())
                        .signum() == 0,
                "unfunded_rf_never_restores_copied_charge_or_invents_EU");
            HologramCapture.Cell paidCell = cell(block, 0, charged, primary, x + 1, y, z);
            Object paidSession = session(player, paidCell, primary);
            fill(player, HologramNativePlacement.materials(block, 0, source), false);
            java.math.BigInteger initial = java.math.BigInteger.valueOf(10000);
            gregtech.common.misc.WirelessNetworkManager.setUserEU(player.getUniqueID(), initial);
            HologramEnergy.Quote quote = HologramEnergy
                .quote(HologramEnergyPayload.prepare(charged, null).demand, new HologramEnergyNetwork(player));
            require(operate(paidSession, paidCell), "funded_rf_native_conduit_actual_placement");
            require(
                HologramEnergyPayload.prepare(snapshot(world.getTileEntity(x + 1, y, z)), null).demand.rf
                    .compareTo(new java.math.BigDecimal("137")) == 0
                    && gregtech.common.misc.WirelessNetworkManager.getUserEU(player.getUniqueID())
                        .equals(initial.subtract(quote.eu)),
                "funded_rf_restores_exact_native_charge_and_debits_exact_converted_EU_once");
        } finally {
            gregtech.common.misc.WirelessNetworkManager.setUserEU(player.getUniqueID(), oldBalance);
        }
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
        HologramEnergyPayload.Prepared charged = HologramEnergyPayload.prepare(payload, null);
        require(
            charged.demand.rf.intValueExact() == 137
                && HologramBlueprintResources.plan(charged.tileEmpty).energy.signum() == 0,
            "official_nonzero_rf_is_separate_exact_paid_demand_and_empty_resource_projection");
        row.getCompoundTag("conduit")
            .setInteger("energyStoredRF", 0);
        require(HologramBlueprintResources.plan(payload).energy.signum() == 0, "official_zero_rf_resource_supported");
        row.getCompoundTag("conduit")
            .setFloat("energyStored", 13.7F);
        require(
            HologramEnergyPayload.prepare(payload, null).demand.rf.compareTo(new java.math.BigDecimal("137")) == 0,
            "official_legacy_nonzero_rf_has_exact_paid_demand");
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
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
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
        Method method = HologramWorldPlacementWorkflow.class.getDeclaredMethod(
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
        Method method = HologramWorldTransactions.class
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
        ItemStack[] copy = new ItemStack[player.inventory.mainInventory.length];
        for (int i = 0; i < copy.length; i++)
            if (player.inventory.mainInventory[i] != null) copy[i] = player.inventory.mainInventory[i].copy();
        return copy;
    }

    private static boolean sameInventory(ItemStack[] before, EntityPlayerMP player) {
        if (before.length != player.inventory.mainInventory.length) return false;
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
