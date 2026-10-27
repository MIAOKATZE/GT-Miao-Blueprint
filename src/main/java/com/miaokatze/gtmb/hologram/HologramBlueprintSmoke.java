package com.miaokatze.gtmb.hologram;

import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.util.GTUtility;
import gregtech.common.misc.WirelessNetworkManager;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Opt-in dedicated-world receipts from public service actions and actual world/inventory observations.
 * The embedded peer proves exact JSON download only: native GL PNG capture and atomic client save ACK are separate
 * client suites. This fixture never manufactures a preview verification or clears the production save capability.
 */
public final class HologramBlueprintSmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-BLUEPRINT-SMOKE] ";
    private static final UUID USER = UUID.fromString("914aa60c-3f03-46a1-a681-2ca6bb545d73");
    private static final UUID SOURCE_OWNER = UUID.fromString("b372ac1e-5f4e-46ed-bc74-73c419ce0e43");
    private final CaptureNetwork capture = new CaptureNetwork();
    private int assertions;
    private WorldServer world;
    private FakePlayer player;
    private String blueprintId;
    private final java.util.Map<String, HologramPresets.Preset> dedicatedFiles = new java.util.LinkedHashMap<>();
    // File aliases refer to exact JSON drafts, including pending saves; they do not assert committed artifact pairs.
    private final java.util.Map<String, String> dedicatedDraftLocalIds = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Path> dedicatedDraftPaths = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> dedicatedDraftHashes = new java.util.LinkedHashMap<>();

    private HologramBlueprintSmoke() {}

    public static void run(WorldServer world) throws Exception {
        HologramBlueprintSmoke smoke = new HologramBlueprintSmoke();
        try {
            smoke.exercise(world);
            HologramPartialPlacementSmoke.run(world);
            System.out.println(PREFIX + "FINAL PASS assertions=" + smoke.assertions);
        } catch (Throwable failure) {
            System.err.println(PREFIX + "FINAL FAIL " + failure);
            throw failure;
        }
    }

    private void exercise(WorldServer serverWorld) throws Exception {
        world = serverWorld;
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-smoke-"),
            "isolated_world_guard");
        player = FakePlayerFactory.get(world, new GameProfile(USER, "GTMBBlueprint"));
        player.inventory.mainInventory = new ItemStack[36];
        player.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        player.inventory.currentItem = 0;
        player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        player.capabilities.allowEdit = true;
        player.setPosition(180.5, 80, 174.5);
        EmbeddedChannel transport = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        NetworkManager manager = new NetworkManager(false);
        for (Field field : NetworkManager.class.getDeclaredFields()) if (field.getType() == Channel.class) {
            field.setAccessible(true);
            field.set(manager, transport);
        }
        player.playerNetServerHandler = new NetHandlerPlayServer(MinecraftServer.getServer(), manager, player);
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previous = channel.get(null);
        channel.set(null, capture);
        if (HologramLibraryFacade.enabled()) {
            require(
                HologramLibraryFacade.backend() != null && HologramLibraryFacade.backend()
                    .available(),
                "dedicated_library_ready_for_real_private_download");
            HologramLibraryFacade.backend()
                .submit(player, HologramLibraryProtocol.request("configGet"), false);
            HologramLibraryFacade.backend()
                .heartbeat();
        }
        HologramService.clear();
        try {
            System.out.println(
                PREFIX + "SCOPE actual author processing, exact JSON draft downloads, detached service activation, "
                    + "world placement and resource payment; native PNG, artifact-pair upload and saved ACK belong "
                    + "to the real client suites");
            sourceFixture();
            areaAndSharing();
            placement();
            wirelessEnergy();
            explicitLevelOneSave();
        } finally {
            HologramService.clear();
            channel.set(null, previous);
            transport.finish();
            Object pending;
            while ((pending = transport.readOutbound()) != null) io.netty.util.ReferenceCountUtil.release(pending);
        }
    }

    private void sourceFixture() {
        BaseMetaTileEntity first = HologramInteractionSmoke.createFixture(world, player, 176, 80, 176);
        HologramInteractionSmoke.createFixture(world, player, 190, 80, 176);
        first.setOwnerName("OriginalBlueprintOwner");
        first.setOwnerUuid(SOURCE_OWNER);
        ItemStack cover = ItemList.Cover_Controller.get(1);
        require(CoverRegistry.isCover(cover), "fixture_real_registered_GT_cover");
        first.attachCover(CoverRegistry.buildCover(cover, ForgeDirection.UP, first));
        require(
            first.getCoverAtSide(ForgeDirection.UP)
                .isValid(),
            "fixture_real_GT_cover_attached");
        require(world.setBlock(178, 80, 176, Blocks.chest, 5, 3), "fixture_real_chest_created");
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(178, 80, 176);
        ItemStack treasure = new ItemStack(Items.diamond, 7);
        treasure.setStackDisplayName("Blueprint NBT receipt");
        chest.setInventorySlotContents(4, treasure);
        NBTTagCompound chestConfiguration = new NBTTagCompound();
        chest.writeToNBT(chestConfiguration);
        chestConfiguration.setString("CustomName", "Blueprint chest configuration receipt");
        chest.readFromNBT(chestConfiguration);
        world.setBlock(179, 80, 176, Blocks.stone, 0, 3);
        world.setBlock(180, 80, 176, Blocks.gold_block, 0, 3);
        world.setBlockMetadataWithNotify(178, 80, 176, 5, 3);
        require(stock(Items.diamond) == 0, "captured_inventory_is_world_NBT_not_player_stock");
    }

    private void areaAndSharing() throws Exception {
        HologramService.toggleAreaSelection(player);
        HologramSmokeTest.flushTransport();
        require(capture.state.getBoolean("areaSelecting"), "public_toggle_enters_region_selection");
        HologramService.areaPoint(player, 1, 176, 80, 176, false);
        HologramSmokeTest.flushTransport();
        HologramService.areaPoint(player, 2, 190, 80, 176, false);
        HologramSmokeTest.flushTransport();
        require(capture.state.getIntArray("areaPoints").length == 6, "both_region_points_have_server_receipt");
        HologramService.toggleAreaSelection(player);
        HologramSmokeTest.flushTransport();
        require(capture.state.getBoolean("areaImporting"), "closing_selection_starts_resumable_import");
        settle();
        require(capture.state.getBoolean("areaImported"), "real_region_import_completes");
        require(
            capture.state.getTagList("cells", 10)
                .tagCount() == 5,
            "region_captures_arbitrary_blocks_and_two_controllers");
        NBTTagList structures = capture.state.getTagList("structures", 10);
        require(structures.tagCount() == 2, "two_actual_EBF_controllers_discovered");
        require(
            structures.getCompoundTagAt(0)
                .getBoolean("declared")
                && structures.getCompoundTagAt(1)
                    .getBoolean("declared"),
            "both_structures_use_real_complete_declaration_capture");
        require(
            stock(Items.diamond) == 0
                && ((TileEntityChest) world.getTileEntity(178, 80, 176)).getStackInSlot(4).stackSize == 7,
            "import_does_not_mutate_world_or_extract_inventory");

        NBTTagCompound flags = new NBTTagCompound();
        flags.setIntArray("indices", new int[] { indexAt(180, 80, 176) });
        flags.setBoolean("excluded", true);
        flags.setString("prompt", "保留这格金块作为手工接口");
        action("editCellFlags", flags);
        require(rowAt(180, 80, 176).getBoolean("excluded"), "server_cell_exclusion_has_snapshot_receipt");
        NBTTagCompound hint = new NBTTagCompound();
        hint.setIntArray("indices", new int[] { indexAt(178, 80, 176), indexAt(180, 80, 176) });
        hint.setString("prompt", "胸箱与接口提示区域");
        action("editHintArea", hint);
        NBTTagCompound overallPrompt = new NBTTagCompound();
        overallPrompt.setString("prompt", "真实多结构蓝图与NBT往返");
        action("editBlueprintPrompt", overallPrompt);
        NBTTagCompound save = new NBTTagCompound();
        save.setString("name", "Runtime blueprint receipt");
        action("saveBlueprint", save);
        blueprintId = capture.state.getString("virtualPresetId");
        HologramPresets.Preset saved = preset(blueprintId);
        require(
            saved != null && saved.schema == 2 && saved.cells.size() == 5,
            "reviewed_save_downloads_exact_schema2_region_draft");
        HologramPresets.Cell chest = cell(saved, 2, 0, 0);
        require(chest.metadata == 5, "saved_arbitrary_block_metadata_is_original_world_metadata");
        NBTTagCompound chestData = HologramBlueprintNbt.decode(chest.tileNbt);
        require(
            chestData.getTagList("Items", 10)
                .tagCount() == 1,
            "saved_chest_inventory_NBT_is_retained");
        ItemStack treasure = ItemStack.loadItemStackFromNBT(
            chestData.getTagList("Items", 10)
                .getCompoundTagAt(0));
        require(
            treasure.getItem() == Items.diamond && treasure.stackSize == 7
                && "Blueprint NBT receipt".equals(treasure.getDisplayName()),
            "saved_chest_item_NBT_and_count_are_exact");
        require(
            "Blueprint chest configuration receipt".equals(chestData.getString("CustomName")),
            "saved_chest_non_inventory_configuration_is_exact");
        NBTTagCompound machineData = HologramBlueprintNbt.decode(cell(saved, 0, 0, 0).tileNbt);
        require(
            "OriginalBlueprintOwner".equals(machineData.getString("mOwnerName")) && SOURCE_OWNER.toString()
                .equals(machineData.getString("mOwnerUuid")),
            "level_zero_storage_preserves_original_owner_for_explicit_review");
        require(
            machineData.getTagList("gt.covers", 10)
                .tagCount() == 1,
            "stored_GT_NBT_preserves_real_cover");
        require(
            SOURCE_OWNER.equals(((BaseMetaTileEntity) world.getTileEntity(176, 80, 176)).getOwnerUuid()),
            "owner_sanitization_does_not_mutate_source_machine");
        require(cell(saved, 4, 0, 0).excluded && saved.hintAreas.size() == 1, "exclude_and_hint_area_are_saved");

        NBTTagCompound chooseStructure = new NBTTagCompound();
        chooseStructure.setString(
            "structureId",
            structures.getCompoundTagAt(0)
                .getString("id"));
        action("selectStructure", chooseStructure);
        settle();
        require(!capture.state.getBoolean("virtual"), "saving_imported_area_keeps_live_structure_editor_ownership");
        action("selectStructure", new NBTTagCompound());
        require(
            capture.state.getBoolean("virtual") && blueprintId.equals(capture.state.getString("virtualPresetId")),
            "returning_from_live_structure_restores_the_saved_area_overview");
        action("saveBlueprint", save);
        String savedAgain = capture.state.getString("virtualPresetId");
        require(
            HologramLibraryFacade.enabled() ? !sameDraftFile(blueprintId, savedAgain) : blueprintId.equals(savedAgain),
            "uncommitted_area_retry_issues_fresh_local_download_capability");
        require(
            "Runtime blueprint receipt".equals(preset(blueprintId).name)
                && HologramBlueprintNbt.decode(cell(preset(blueprintId), 2, 0, 0).tileNbt)
                    .equals(chestData),
            "pending_retry_preserves_first_downloaded_draft_name_and_full_chest_payload");
        blueprintId = savedAgain;
        action("selectStructure", chooseStructure);
        settle();
        require(!capture.state.getBoolean("virtual"), "saving_cached_overview_keeps_live_structure_editor_ownership");
        action("selectStructure", new NBTTagCompound());
        require(
            "保留这格金块作为手工接口".equals(rowAt(180, 80, 176).getString("prompt"))
                && rowAt(180, 80, 176).getBoolean("excluded"),
            "saved_area_switching_preserves_cell_annotations_and_exclusion");

        if (HologramLibraryFacade.enabled()) {
            // A downloaded JSON is not a committed PNG pair. Explicit detached DTO activation supplies the
            // source-file binding needed to exercise update/replace, without manufacturing a client save ACK.
            String sourceId = blueprintId;
            String sourceLocalId = dedicatedDraftLocalIds.get(sourceId);
            String activated = activatePrivate(preset(sourceId), "edit", sourceLocalId);
            require(sameDraftFile(sourceId, activated), "detached_DTO_activation_binds_actual_downloaded_source_file");
            NBTTagCompound update = new NBTTagCompound();
            update.setString("name", "Runtime loaded DTO update receipt");
            action("saveBlueprint", update);
            blueprintId = capture.state.getString("virtualPresetId");
            require(
                sameDraftFile(sourceId, blueprintId) && sourceLocalId.equals(capture.downloadId),
                "loaded_DTO_update_targets_its_actual_source_file_without_claiming_commit");
            update.setString("name", "Runtime explicit overwrite receipt");
            update.setString("presetId", sourceLocalId);
            update.setBoolean("confirmOverwrite", true);
            action("saveBlueprintReplace", update);
            blueprintId = capture.state.getString("virtualPresetId");
            require(
                sameDraftFile(sourceId, blueprintId) && sourceLocalId.equals(capture.downloadId),
                "explicit_confirmed_replace_targets_the_requested_local_file_without_claiming_commit");
        }

        String fileId = "runtime_smoke_" + UUID.randomUUID()
            .toString()
            .replace("-", "");
        NBTTagCompound share = new NBTTagCompound();
        share.setString("presetId", blueprintId);
        share.setString("fileId", fileId);
        Path path = HologramBlueprintSharing.resolve(fileId);
        try {
            action("exportBlueprint", share);
            if (HologramLibraryFacade.enabled()) path = capture.lastDownload;
            require(Files.isRegularFile(path), "public_export_writes_separate_share_file");
            HologramPresets.Preset imported = HologramLibraryFacade.enabled() ? downloadedDraft()
                : HologramPresets.importBlueprint(fileId);
            require(
                imported.schema == 2 && imported.cells.size() == 5
                    && cell(imported, 4, 0, 0).excluded
                    && "保留这格金块作为手工接口".equals(cell(imported, 4, 0, 0).prompt)
                    && imported.hintAreas.size() == 1
                    && "胸箱与接口提示区域".equals(imported.hintAreas.get(0).prompt)
                    && "真实多结构蓝图与NBT往返".equals(imported.prompt),
                "share_roundtrip_retains_exclusion_cell_prompt_region_prompt_and_blueprint_prompt");
            require(
                HologramBlueprintNbt.decode(cell(imported, 2, 0, 0).tileNbt)
                    .equals(chestData),
                "share_roundtrip_retains_arbitrary_full_tile_NBT");
            int oldSize = HologramPresets.all()
                .size();
            if (HologramLibraryFacade.enabled()) {
                String importedLocalId = capture.downloadId;
                require(
                    importedLocalId.startsWith("local_") && !importedLocalId.equals(imported.id)
                        && !importedLocalId.equals(dedicatedDraftLocalIds.get(blueprintId)),
                    "export_download_allocates_local_file_identity_independent_of_json_session_identity");
                String activated = activatePrivate(imported, "edit", importedLocalId);
                require(
                    activated.startsWith("private_") && !activated.equals(blueprintId)
                        && HologramPresets.all()
                            .size() == oldSize,
                    "draft_reactivation_allocates_independent_session_identity_without_server_global_save");
            } else {
                action("importBlueprint", share);
                require(
                    HologramPresets.all()
                        .size() == oldSize + 1,
                    "public_import_adds_independent_library_entry");
            }
        } finally {
            Files.deleteIfExists(path);
        }
        NBTTagCompound structure = new NBTTagCompound();
        structure.setString(
            "structureId",
            structures.getCompoundTagAt(1)
                .getString("id"));
        structure.setBoolean("enabled", false);
        action("structureState", structure);
        require(
            !capture.state.getTagList("structures", 10)
                .getCompoundTagAt(1)
                .getBoolean("enabled"),
            "second_structure_can_be_disabled_independently");
        structure.setBoolean("enabled", true);
        action("structureState", structure);
    }

    private void placement() throws Exception {
        HologramPresets.Preset saved = preset(blueprintId);
        supply(saved, true);
        beginPlacement(208, blueprintId);
        require(
            !capture.state.getBoolean("blueprintAwaitingConfirmation") && capture.state.getBoolean("virtualPlacement")
                && capture.state.getBoolean("moving"),
            "choosing_blueprint_starts_only_moving_preview_before_material_confirmation");
        require(
            capture.state.getInteger("missing") > 0 && stock(Item.getItemFromBlock(Blocks.chest)) == 1,
            "material_preview_reports_real_missing_stock_without_deduction");
        require(stock(Items.diamond) == 7, "preview_keeps_real_inventory_contents_material_untouched");
        snapshotReceipt();
        HologramService.lockVirtual(player, 176, 80, 208, 1);
        HologramSmokeTest.flushTransport();
        require(
            capture.state.getBoolean("virtualPlacement") && capture.state.getBoolean("virtualLocked")
                && capture.state.getBoolean("blueprintAwaitingConfirmation")
                && !capture.state.getBoolean("moving")
                && !capture.state.getBoolean("open")
                && stock(Item.getItemFromBlock(Blocks.chest)) == 1
                && world.isAirBlock(178, 80, 208),
            "first_world_right_locks_without_opening_material_review_or_mutating_world_inventory");
        submitConstruction();
        require(
            !capture.state.getBoolean("forcePlacementReady") && capture.state.getInteger("job") != 1
                && stock(Item.getItemFromBlock(Blocks.chest)) == 1
                && stock(Items.diamond) == 7
                && world.isAirBlock(178, 80, 208),
            "forged_current_revision_confirmation_before_RIGHT2_cannot_arm_or_start_construction");
        long firstLockNonce = capture.state.getLong("placementReviewNonce");
        NBTTagCompound unlock = new NBTTagCompound();
        unlock.setString("session", capture.state.getString("session"));
        unlock.setLong("planRevision", capture.state.getLong("planRevision"));
        advance();
        HologramService.unlockWorldPosition(player, unlock);
        HologramSmokeTest.flushTransport();
        require(
            capture.state.getBoolean("moving") && !capture.state.getBoolean("virtualLocked")
                && !capture.state.getBoolean("blueprintAwaitingConfirmation")
                && !capture.state.getBoolean("open")
                && capture.state.getLong("placementReviewNonce") > firstLockNonce,
            "Shift_right_unlock_returns_to_moving_world_and_invalidates_review_nonce");
        HologramService.lockVirtual(player, 176, 80, 208, 1);
        HologramSmokeTest.flushTransport();
        require(!capture.state.getBoolean("open"), "relocking_does_not_open_GUI");
        long relockNonce = capture.state.getLong("placementReviewNonce");
        HologramService.openAir(player);
        HologramSmokeTest.flushTransport();
        require(
            capture.state.getBoolean("open") && capture.state.getBoolean("virtualLocked")
                && capture.state.getLong("placementReviewNonce") > relockNonce
                && stock(Item.getItemFromBlock(Blocks.chest)) == 1
                && world.isAirBlock(178, 80, 208),
            "RIGHT2_issues_fresh_material_review_without_world_or_inventory_mutation");
        NBTTagCompound stale = new NBTTagCompound();
        stale.setLong("planRevision", capture.state.getLong("planRevision") - 1);
        stale.setString("session", capture.state.getString("session"));
        stale.setString("op", "confirmBlueprintPlacement");
        advance();
        HologramService.handle(player, stale);
        HologramSmokeTest.flushTransport();
        require(capture.state.getBoolean("blueprintAwaitingConfirmation"), "stale_material_confirmation_is_rejected");
        submitConstruction();
        require(
            capture.state.getBoolean("jobPartialPlacement") && capture.state.getInteger("job") == 1
                && stock(Item.getItemFromBlock(Blocks.chest)) == 1
                && world.isAirBlock(178, 80, 208),
            "one_explicit_missing_material_review_admits_job_without_immediate_world_changes");
        settle();
        require(
            world.getBlock(178, 80, 208) == Blocks.chest && stock(Item.getItemFromBlock(Blocks.chest)) == 0,
            "existing_stock_job_places_chest_and_deducts_exactly_once");
        require(
            world.isAirBlock(179, 80, 208) && world.isAirBlock(190, 80, 208),
            "existing_stock_job_does_not_invent_missing_stone_or_second_controller");
        require(world.isAirBlock(180, 80, 208), "excluded_gold_cell_never_builds");
        verifyChest(208, true);
        require(stock(Items.diamond) == 0, "restored_chest_contents_cost_exactly_seven_real_diamonds");
        verifyController(208, true);
        require(stock(ItemList.Cover_Controller.get(1)) == 0, "retained_cover_deducts_real_cover_material_once");

        // A fresh placement must return to choose/confirm even with the same selected tool preset.
        supply(saved, false);
        beginPlacement(240, blueprintId);
        require(
            !capture.state.getBoolean("blueprintAwaitingConfirmation") && capture.state.getBoolean("moving"),
            "already_selected_blueprint_still_requires_world_position_on_every_placement");
        HologramService.lockVirtual(player, 176, 80, 240, 1);
        HologramService.openAir(player);
        HologramSmokeTest.flushTransport();
        PlaceVeto veto = new PlaceVeto(178, 80, 240);
        MinecraftForge.EVENT_BUS.register(veto);
        try {
            submitConstruction();
            settle();
            require(veto.hits > 0, "real_forge_place_permission_veto_was_observed");
            require(veto.restoredTileObserved, "permission_event_observes_actual_restored_tile_before_veto");
            require(
                world.isAirBlock(178, 80, 240) && world.getTileEntity(178, 80, 240) == null,
                "permission_veto_restores_original_world_and_removes_restored_tile_NBT");
            require(
                stock(Item.getItemFromBlock(Blocks.chest)) == 1 && stock(Items.diamond) == 7,
                "permission_veto_restores_block_and_seven_contents_materials_without_duplication");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
        }
        submitConstruction();
        settle();
        require(
            world.getBlock(178, 80, 240) == Blocks.chest && stock(Item.getItemFromBlock(Blocks.chest)) == 0,
            "retry_after_permission_veto_consumes_restored_chest_once");
        verifyChest(240, true);
        verifyController(240, true);
        require(
            world.getBlock(179, 80, 240) == Blocks.stone
                && world.getTileEntity(190, 80, 240) instanceof BaseMetaTileEntity
                && USER.equals(((BaseMetaTileEntity) world.getTileEntity(190, 80, 240)).getOwnerUuid()),
            "full_material_retry_builds_second_actual_controller_with_current_owner");
        int remaining = 0;
        for (int i = 1; i < player.inventory.mainInventory.length; i++)
            if (player.inventory.mainInventory[i] != null) remaining += player.inventory.mainInventory[i].stackSize;
        require(remaining == 0, "full_blueprint_block_and_cover_materials_are_consumed_exactly_once");

        supply(saved, false);
        ItemStack coverMaterial = ItemList.Cover_Controller.get(1);
        for (int i = 1; i < player.inventory.mainInventory.length; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack != null && stack.getItem() == coverMaterial.getItem()
                && stack.getItemDamage() == coverMaterial.getItemDamage()) player.inventory.mainInventory[i] = null;
        }
        beginPlacement(336, blueprintId);
        require(capture.state.getInteger("missing") > 0, "real_missing_cover_is_in_material_preview");
        HologramService.lockVirtual(player, 176, 80, 336, 1);
        HologramService.openAir(player);
        HologramSmokeTest.flushTransport();
        submitConstruction();
        require(
            capture.state.getBoolean("jobPartialPlacement"),
            "cover_shortage_is_accepted_in_initial_explicit_review");
        settle();
        require(
            world.isAirBlock(176, 80, 336) && stock(
                saved.cells.stream()
                    .filter(cell -> cell.controller)
                    .findFirst()
                    .get()
                    .stack())
                == 1,
            "missing_cover_cannot_create_controller_or_consume_its_block_material");
        player.inventory.mainInventory[35] = coverMaterial.copy();
        submitConstruction();
        settle();
        verifyController(336, true);
        require(stock(coverMaterial) == 0, "supplying_real_cover_allows_retry_and_deducts_cover_once");

        NBTTagCompound retention = new NBTTagCompound();
        retention.setString("name", "Runtime no covers receipt");
        retention.setBoolean("retainNbt", true);
        retention.setBoolean("retainCovers", false);
        action("saveBlueprintAs", retention);
        String noCovers = capture.state.getString("virtualPresetId");
        require(
            !blueprintId.equals(noCovers) && !sameDraftFile(blueprintId, noCovers),
            "no_covers_variant_download_targets_an_independent_file");
        require(
            HologramBlueprintProcessing.complete(preset(noCovers))
                && HologramBlueprintNbt.decode(cell(preset(noCovers), 0, 0, 0).tileNbt)
                    .getTagList("gt.covers", 10)
                    .tagCount() == 0,
            "no_covers_processing_materializes_the_explicit_cover_omission_in_downloaded_NBT");
        require(
            HologramBlueprintNbt.decode(cell(preset(blueprintId), 0, 0, 0).tileNbt)
                .getTagList("gt.covers", 10)
                .tagCount() == 1,
            "saving_no_covers_variant_preserves_original_cover_payload");
        supply(preset(noCovers), false);
        beginPlacement(272, noCovers);
        HologramService.lockVirtual(player, 176, 80, 272, 1);
        HologramService.openAir(player);
        HologramSmokeTest.flushTransport();
        submitConstruction();
        settle();
        verifyChest(272, true);
        verifyController(272, false);

        NBTTagCompound original = new NBTTagCompound();
        original.setString("presetId", blueprintId);
        action("editPreset", original);
        require(
            sameDraftFile(blueprintId, capture.state.getString("virtualPresetId")),
            "covers_only_storage_case_reloads_original_complete_blueprint");
        retention.setString("name", "Runtime no NBT receipt");
        retention.setBoolean("retainNbt", false);
        retention.setBoolean("retainCovers", true);
        action("saveBlueprintAs", retention);
        String noNbt = capture.state.getString("virtualPresetId");
        require(
            !blueprintId.equals(noNbt) && !noCovers.equals(noNbt)
                && !sameDraftFile(blueprintId, noNbt)
                && !sameDraftFile(noCovers, noNbt),
            "covers_only_variant_download_targets_a_second_independent_file");
        HologramPresets.Preset originalComplete = preset(blueprintId);
        require(
            originalComplete.retainNbt && originalComplete.retainCovers
                && HologramBlueprintNbt.decode(cell(originalComplete, 2, 0, 0).tileNbt)
                    .getTagList("Items", 10)
                    .tagCount() == 1
                && HologramBlueprintNbt.decode(cell(originalComplete, 0, 0, 0).tileNbt)
                    .getTagList("gt.covers", 10)
                    .tagCount() == 1,
            "saving_retention_variants_preserves_original_device_NBT_and_cover_configuration");
        HologramPresets.Preset coversOnly = preset(noNbt);
        require(
            HologramBlueprintProcessing.complete(coversOnly) && coversOnly.retainCovers
                && HologramBlueprintNbt.decode(cell(coversOnly, 2, 0, 0).tileNbt)
                    .getTagList("Items", 10)
                    .tagCount() == 0
                && HologramBlueprintNbt.decode(cell(coversOnly, 0, 0, 0).tileNbt)
                    .getTagList("gt.covers", 10)
                    .tagCount() == 1,
            "covers_only_variant_stores_real_cover_payload_from_original_blueprint");
        supply(coversOnly, false);
        beginPlacement(304, noNbt);
        HologramService.lockVirtual(player, 176, 80, 304, 1);
        HologramService.openAir(player);
        HologramSmokeTest.flushTransport();
        submitConstruction();
        settle();
        verifyChest(304, false);
        verifyController(304, true);

        supply(saved, false);
        removeStock(Items.diamond);
        beginPlacement(368, blueprintId);
        require(
            capture.state.getInteger("missing") > 0,
            "unpaid_captured_inventory_is_reported_as_real_material_shortage");
        HologramService.lockVirtual(player, 176, 80, 368, 1);
        HologramService.openAir(player);
        HologramSmokeTest.flushTransport();
        submitConstruction();
        require(
            capture.state.getBoolean("jobPartialPlacement") && world.isAirBlock(178, 80, 368),
            "unpaid_inventory_initial_explicit_review_admits_partial_job_without_world_change");
        settle();
        require(
            world.isAirBlock(178, 80, 368) && stock(Item.getItemFromBlock(Blocks.chest)) == 1
                && stock(Items.diamond) == 0,
            "forced_partial_build_cannot_generate_unpaid_inventory_or_consume_its_chest");

        supply(saved, false);
        removeStock(Items.diamond);
        beginPlacement(400, blueprintId);
        Set<String> rootKeys = new LinkedHashSet<>();
        for (HologramPresets.Cell entry : saved.cells) {
            NBTTagCompound data = HologramBlueprintNbt.decode(entry.tileNbt);
            if (data != null) for (Object key : data.func_150296_c()) rootKeys.add((String) key);
        }
        rootKeys.remove("Items");
        NBTTagList selectedKeys = new NBTTagList();
        for (String key : rootKeys) selectedKeys.appendTag(new NBTTagString(key));
        NBTTagCompound selection = new NBTTagCompound();
        selection.setBoolean("all", false);
        selection.setTag("keys", selectedKeys);
        action("configurePlacementNbt", selection);
        require(
            capture.state.getInteger("missing") == 0,
            "explicit_inventory_entry_cancellation_removes_contents_cost_for_this_placement");
        HologramService.lockVirtual(player, 176, 80, 400, 1);
        HologramService.openAir(player);
        HologramSmokeTest.flushTransport();
        submitConstruction();
        settle();
        verifyChest(400, true, false);
        verifyController(400, true);
        require(
            stock(Items.diamond) == 0 && HologramBlueprintNbt.decode(cell(preset(blueprintId), 2, 0, 0).tileNbt)
                .getTagList("Items", 10)
                .getCompoundTagAt(0)
                .getByte("Count") == 7,
            "temporary_inventory_downgrade_keeps_original_saved_blueprint_contents_unchanged");
        require(
            world.getBlock(178, 80, 176) == Blocks.chest
                && ((TileEntityChest) world.getTileEntity(178, 80, 176)).getStackInSlot(4).stackSize == 7,
            "all_placement_and_rollback_tests_leave_source_NBT_unchanged");
    }

    private void beginPlacement(int z, String id) {
        player.setPosition(180.5, 80, z - 1.5);
        for (int x = 176; x <= 190; x++) {
            world.getChunkFromBlockCoords(x, z);
            require(world.isAirBlock(x, 80, z), "fresh_destination_air_" + x + "_" + z);
        }
        advance();
        HologramService.openVirtual(player, 176, 80, z, 1, id);
        HologramSmokeTest.flushTransport();
        require(
            capture.state.getBoolean("presetBrowseForPlacement") && !capture.state.getBoolean("moving"),
            "workspace_shows_blueprint_chooser_before_preview_" + z);
        NBTTagCompound choose = new NBTTagCompound();
        choose.setString("presetId", id);
        action("choosePlacementPreset", choose);
    }

    private void wirelessEnergy() throws Exception {
        int id = -1;
        for (int i = 1; i < GregTechAPI.METATILEENTITIES.length; i++)
            if (GregTechAPI.METATILEENTITIES[i] instanceof MTEHatchEnergy
                && ((MTEHatchEnergy) GregTechAPI.METATILEENTITIES[i]).maxEUStore() >= 5000) {
                    id = i;
                    break;
                }
        require(id > 0, "registered_energy_hatch_with_real_capacity_available");
        player.setPosition(180.5, 80, 430.5);
        world.getChunkFromBlockCoords(176, 432);
        require(world.isAirBlock(176, 80, 432), "fresh_energy_source_air");
        world.setBlock(176, 80, 432, GregTechAPI.sBlockMachines, 0, 3);
        BaseMetaTileEntity source = (BaseMetaTileEntity) world.getTileEntity(176, 80, 432);
        source.setInitialValuesAsNBT(null, (short) id);
        source.setFrontFacing(ForgeDirection.NORTH);
        source.setOwnerName("OriginalBlueprintOwner");
        source.setOwnerUuid(SOURCE_OWNER);
        require(source.setStoredEU(5000) && source.getStoredEU() == 5000, "real_source_energy_hatch_contains_5000_EU");
        HologramService.toggleAreaSelection(player);
        HologramSmokeTest.flushTransport();
        HologramService.areaPoint(player, 1, 176, 80, 432, false);
        HologramSmokeTest.flushTransport();
        HologramService.areaPoint(player, 2, 176, 80, 432, false);
        HologramSmokeTest.flushTransport();
        HologramService.toggleAreaSelection(player);
        HologramSmokeTest.flushTransport();
        settle();
        NBTTagCompound save = new NBTTagCompound();
        save.setString("name", "Runtime wireless energy receipt");
        action("saveBlueprint", save);
        String energyId = capture.state.getString("virtualPresetId");
        HologramPresets.Preset energy = preset(energyId);
        require(
            energy != null && energy.cells.size() == 1
                && HologramBlueprintNbt.decode(energy.cells.get(0).tileNbt)
                    .getLong("mStoredEnergy") == 5000,
            "default_blueprint_save_retains_actual_stored_energy_NBT");
        WirelessNetworkManager.strongCheckOrAddUser(USER);
        BigInteger previousBalance = WirelessNetworkManager.getUserEU(USER);
        try {
            WirelessNetworkManager.setUserEU(USER, BigInteger.valueOf(100000));
            supply(energy, false);
            beginPlacement(464, energyId);
            require(
                capture.state.getInteger("missing") == 0 && WirelessNetworkManager.getUserEU(USER)
                    .equals(BigInteger.valueOf(100000)),
                "real_wireless_balance_satisfies_preview_without_energy_deduction");
            HologramService.lockVirtual(player, 176, 80, 464, 1);
            HologramService.openAir(player);
            HologramSmokeTest.flushTransport();
            PlaceVeto veto = new PlaceVeto(176, 80, 464);
            MinecraftForge.EVENT_BUS.register(veto);
            try {
                submitConstruction();
                settle();
                require(
                    veto.hits > 0 && veto.restoredEnergyObserved,
                    "permission_event_sees_actual_restored_5000_EU_hatch_before_veto");
                require(
                    world.isAirBlock(176, 80, 464) && stock(
                        energy.cells.get(0)
                            .stack())
                        == 1
                        && WirelessNetworkManager.getUserEU(USER)
                            .equals(BigInteger.valueOf(100000)),
                    "permission_veto_refunds_real_wireless_EU_and_machine_material");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(veto);
            }
            submitConstruction();
            settle();
            require(
                world.getTileEntity(176, 80, 464) instanceof BaseMetaTileEntity
                    && ((BaseMetaTileEntity) world.getTileEntity(176, 80, 464)).getStoredEU() == 5000
                    && WirelessNetworkManager.getUserEU(USER)
                        .equals(BigInteger.valueOf(95000)),
                "successful_energy_placement_costs_exactly_5000_real_wireless_EU");
            require(
                USER.equals(((BaseMetaTileEntity) world.getTileEntity(176, 80, 464)).getOwnerUuid()),
                "paid_energy_hatch_is_owned_by_current_user");

            WirelessNetworkManager.setUserEU(USER, BigInteger.ZERO);
            supply(energy, false);
            beginPlacement(496, energyId);
            require(
                capture.state.getInteger("missing") == 0,
                "cache_shortage_does_not_block_available_physical_materials");
            boolean cacheFallback = false;
            NBTTagList cacheRows = capture.state.getTagList("blueprintResources", 10);
            for (int row = 0; row < cacheRows.tagCount(); row++) cacheFallback |= cacheRows.getCompoundTagAt(row)
                .getBoolean("optionalCache")
                && cacheRows.getCompoundTagAt(row)
                    .getBoolean("emptyFallback");
            require(cacheFallback, "actual_empty_wireless_balance_is_visible_as_optional_cache_fallback");
            HologramService.lockVirtual(player, 176, 80, 496, 1);
            HologramService.openAir(player);
            HologramSmokeTest.flushTransport();
            submitConstruction();
            settle();
            require(
                world.getTileEntity(176, 80, 496) instanceof BaseMetaTileEntity
                    && ((BaseMetaTileEntity) world.getTileEntity(176, 80, 496)).getStoredEU() == 0
                    && stock(
                        energy.cells.get(0)
                            .stack())
                        == 0
                    && WirelessNetworkManager.getUserEU(USER)
                        .signum() == 0,
                "insufficient_real_balance_places_empty_cache_and_consumes_only_real_machine_material");
            require(
                !capture.state.getString("blueprintEnergyNotice")
                    .isEmpty(),
                "empty_cache_placement_reports_visible_warning");
            WirelessNetworkManager.setUserEU(USER, BigInteger.valueOf(5000));
            submitConstruction();
            settle();
            require(
                ((BaseMetaTileEntity) world.getTileEntity(176, 80, 496)).getStoredEU() == 0
                    && WirelessNetworkManager.getUserEU(USER)
                        .equals(BigInteger.valueOf(5000))
                    && stock(
                        energy.cells.get(0)
                            .stack())
                        == 0,
                "refresh_and_repeat_keep_empty_cache_placement_complete_without_second_charge_or_material");
            supply(energy, false);
            beginPlacement(528, energyId);
            HologramService.lockVirtual(player, 176, 80, 528, 1);
            HologramService.openAir(player);
            HologramSmokeTest.flushTransport();
            submitConstruction();
            settle();
            require(
                world.getTileEntity(176, 80, 528) instanceof BaseMetaTileEntity
                    && ((BaseMetaTileEntity) world.getTileEntity(176, 80, 528)).getStoredEU() == 5000
                    && WirelessNetworkManager.getUserEU(USER)
                        .signum() == 0,
                "next_paid_placement_costs_exact_balance_after_empty_cache_placement");
            require(
                source.getStoredEU() == 5000 && SOURCE_OWNER.equals(source.getOwnerUuid()),
                "energy_import_and_placement_leave_source_energy_and_owner_unchanged");
        } finally {
            WirelessNetworkManager.setUserEU(USER, previousBalance);
        }
    }

    private void explicitLevelOneSave() throws Exception {
        String originalId = capture.state.getString("virtualPresetId");
        HologramPresets.Preset original = preset(originalId);
        require(
            original != null && original.deidentificationLevel == 0,
            "level_one_save_starts_from_retained_raw_identity");
        String originalPayload = original.cells.get(0).tileNbt;
        NBTTagCompound save = new NBTTagCompound();
        save.setString("name", "Runtime explicit level one identity receipt");
        save.setInteger("deidentificationLevel", 1);
        save.setBoolean("deidentificationChosen", true);
        action("saveBlueprintAs", save);
        String processedId = capture.state.getString("virtualPresetId");
        require(!sameDraftFile(originalId, processedId), "level_one_SaveAs_targets_an_independent_download_file");
        HologramPresets.Preset processed = preset(processedId);
        NBTTagCompound nativePayload = HologramBlueprintNbt.decode(processed.cells.get(0).tileNbt);
        require(
            processed.deidentificationLevel == 1 && processed.deidentificationChosen
                && nativePayload.getString("mOwnerName")
                    .isEmpty()
                && nativePayload.getString("mOwnerUuid")
                    .isEmpty(),
            "actual_explicit_level_one_save_removes_owner_identity");
        require(
            originalPayload.equals(preset(originalId).cells.get(0).tileNbt)
                && SOURCE_OWNER.equals(((BaseMetaTileEntity) world.getTileEntity(176, 80, 432)).getOwnerUuid()),
            "explicit_level_one_save_preserves_raw_private_file_and_actual_source_owner");
    }

    private void verifyChest(int z, boolean retained) {
        verifyChest(z, retained, retained);
    }

    private void verifyChest(int z, boolean retained, boolean inventoryRetained) {
        require(world.getTileEntity(178, 80, z) instanceof TileEntityChest, "actual_chest_tile_placed_" + z);
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(178, 80, z);
        ItemStack contents = chest.getStackInSlot(4);
        NBTTagCompound data = new NBTTagCompound();
        chest.writeToNBT(data);
        require(
            retained ? "Blueprint chest configuration receipt".equals(data.getString("CustomName"))
                : data.getString("CustomName")
                    .isEmpty(),
            "actual_chest_configuration_NBT_retention_" + retained + "_" + z);
        require(
            inventoryRetained
                ? contents != null && contents.getItem() == Items.diamond
                    && contents.stackSize == 7
                    && "Blueprint NBT receipt".equals(contents.getDisplayName())
                : contents == null,
            "actual_chest_inventory_NBT_retention_" + inventoryRetained + "_" + z);
        require(
            chest.xCoord == 178 && chest.yCoord == 80 && chest.zCoord == z,
            "placed_tile_NBT_coordinates_rewritten_to_destination_" + z);
        require(world.getBlockMetadata(178, 80, z) == 5, "placed_chest_metadata_retains_blueprint_direction_" + z);
    }

    private void verifyController(int z, boolean covers) {
        require(world.getTileEntity(176, 80, z) instanceof BaseMetaTileEntity, "actual_GT_controller_placed_" + z);
        BaseMetaTileEntity base = (BaseMetaTileEntity) world.getTileEntity(176, 80, z);
        require(
            USER.equals(base.getOwnerUuid()) && player.getCommandSenderName()
                .equals(base.getOwnerName()),
            "actual_GT_owner_rewritten_to_current_user_" + z);
        NBTTagCompound actualNbt = new NBTTagCompound();
        base.writeToNBT(actualNbt);
        StringBuilder sides = new StringBuilder();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (sides.length() > 0) sides.append(',');
            sides.append(side.name())
                .append(':')
                .append(
                    base.getCoverAtSide(side)
                        .getCoverID());
        }
        System.out.println(
            PREFIX + "COVER receipt z="
                + z
                + " expected="
                + covers
                + " preset="
                + capture.state.getString("virtualPresetId")
                + " storeNbt="
                + capture.state.getBoolean("blueprintRetainNbt")
                + " storeCovers="
                + capture.state.getBoolean("blueprintRetainCovers")
                + " placementCovers="
                + capture.state.getBoolean("placementRetainCovers")
                + " actualSides="
                + sides
                + " actualCoverData="
                + actualNbt.getTagList("gt.covers", 10));
        require(
            base.getCoverAtSide(ForgeDirection.UP)
                .isValid() == covers,
            "actual_GT_cover_retention_" + covers + "_" + z);
    }

    private void submitConstruction() {
        if (!capture.state.getBoolean("blueprintAwaitingConfirmation")) action("scan");
        if (capture.state.getBoolean("blueprintAwaitingConfirmation") && capture.state.getString("session")
            .equals(capture.reviewedSession) && capture.state.getInteger("placementDeidentificationLevel") == 0) {
            int before = carriedStock();
            NBTTagCompound rawReview = new NBTTagCompound();
            rawReview.setLong("placementReviewNonce", capture.state.getLong("placementReviewNonce"));
            action("confirmBlueprintPlacement", rawReview);
            require(
                capture.state.getInteger("job") == 0 && !capture.state.getBoolean("forcePlacementReady")
                    && before == carriedStock(),
                "foreign_raw_identity_review_refuses_construction_without_material_payment");
            NBTTagCompound choice = new NBTTagCompound();
            choice.setInteger("level", 1);
            long rawNonce = capture.state.getLong("placementReviewNonce");
            long rawRevision = capture.state.getLong("planRevision");
            choice.setLong("placementReviewNonce", rawNonce);
            action("configurePlacementDeidentification", choice);
            require(
                capture.state.getInteger("placementDeidentificationLevel") == 1
                    && capture.state.getBoolean("placementDeidentificationChosen")
                    && capture.state.getLong("placementReviewNonce") > rawNonce
                    && capture.state.getLong("planRevision") > rawRevision
                    && capture.state.getInteger("job") == 0
                    && before == carriedStock(),
                "actual_review_explicitly_chooses_level_one_before_current_actor_placement");
        }
        NBTTagCompound review = new NBTTagCompound();
        review.setBoolean("forceExplicit", capture.state.getInteger("missing") > 0);
        NBTTagList caches = capture.state.getTagList("blueprintResources", 10);
        for (int i = 0; i < caches.tagCount(); i++) if (caches.getCompoundTagAt(i)
            .getBoolean("emptyFallback")) review.setBoolean("allowEmptyCache", true);
        review.setLong("placementReviewNonce", capture.state.getLong("placementReviewNonce"));
        action(
            capture.state.getBoolean("blueprintAwaitingConfirmation") ? "confirmBlueprintPlacement" : "build",
            review);
    }

    private int carriedStock() {
        int count = 0;
        for (int slot = 1; slot < player.inventory.mainInventory.length; slot++)
            if (player.inventory.mainInventory[slot] != null) count += player.inventory.mainInventory[slot].stackSize;
        return count;
    }

    private boolean foreignOwnerIssue() {
        NBTTagList issues = capture.state.getTagList("aeIssues", 10);
        for (int i = 0; i < issues.tagCount(); i++) if ("identity.foreign_owner".equals(
            issues.getCompoundTagAt(i)
                .getString("code")))
            return true;
        return false;
    }

    private void action(String op) {
        action(op, new NBTTagCompound());
    }

    private void action(String op, NBTTagCompound fields) {
        require(capture.state != null, "service_snapshot_available_before_" + op);
        if (HologramLibraryFacade.enabled() && fields.hasKey("presetId", 8)
            && dedicatedFiles.containsKey(fields.getString("presetId"))
            && ("editPreset".equals(op) || "previewPreset".equals(op) || "choosePlacementPreset".equals(op))) {
            String oldId = fields.getString("presetId");
            try {
                activatePrivate(
                    preset(oldId),
                    "choosePlacementPreset".equals(op) ? "place" : "previewPreset".equals(op) ? "preview" : "edit",
                    dedicatedDraftLocalIds.get(oldId));
            } catch (Exception invalid) {
                throw new AssertionError("real dedicated private activation failed", invalid);
            }
            return;
        }
        boolean save = "saveBlueprint".equals(op) || "saveBlueprintAs".equals(op) || "saveBlueprintReplace".equals(op);
        java.util.Map<String, String> previousDraftHashes = new java.util.LinkedHashMap<>();
        if (HologramLibraryFacade.enabled() && save) {
            for (java.util.Map.Entry<String, String> entry : dedicatedDraftHashes.entrySet()) {
                // The exported roundtrip draft is deliberately deleted after that independent scenario.
                if (Files.isRegularFile(dedicatedDraftPaths.get(entry.getKey())))
                    previousDraftHashes.put(entry.getKey(), entry.getValue());
            }
        }
        if (save) prepareAuthorSave(fields);
        NBTTagCompound request = (NBTTagCompound) fields.copy();
        // The current author workflow materializes each selection in the cells; a legacy global flag cannot
        // override that reviewed result. The choices above were applied through the six-stage process policy.
        if (save) {
            request.removeTag("retainNbt");
            request.removeTag("retainCovers");
            request.removeTag("retainAllNbt");
            request.removeTag("retainedNbtKeys");
        }
        request.setString("session", capture.state.getString("session"));
        request.setString("op", op);
        request.setLong("planRevision", capture.state.getLong("planRevision"));
        int previousDownloads = capture.downloads;
        int previousGlobalEntries = -1;
        try {
            if (HologramLibraryFacade.enabled() && save) previousGlobalEntries = HologramPresets.all()
                .size();
        } catch (java.io.IOException invalid) {
            throw new AssertionError("Actual global library could not be inspected", invalid);
        }
        advance();
        HologramService.handle(player, request);
        HologramSmokeTest.flushTransport();
        if (HologramLibraryFacade.enabled() && (save || "exportBlueprint".equals(op))) {
            capture.awaitDownload(player, previousDownloads);
            try {
                dedicatedDraftPaths.put(capture.downloadId, capture.lastDownload);
                dedicatedDraftHashes.put(capture.downloadId, capture.downloadHash);
                if (!"exportBlueprint".equals(op)) {
                    String id = capture.state.getString("virtualPresetId");
                    String local = capture.downloadId;
                    HologramSession current = HologramWorkspace.find(player.getUniqueID());
                    require(
                        id.startsWith("private_") && local.startsWith("local_")
                            && local.equals(capture.downloadProof.getString("localId"))
                            && local.equals(current.blueprintSaveLocalId)
                            && id.equals(current.blueprintSaveSelectedPresetId)
                            && capture.lastDownload.getFileName()
                                .toString()
                                .equals(local + ".json"),
                        "actual_save_receipt_binds_download_target_and_private_session_identity");
                    require(
                        !capture.downloadProof.getString("saveReceipt")
                            .isEmpty()
                            && current.blueprintSaveReceipt.equals(capture.downloadProof.getString("saveReceipt"))
                            && current.blueprintSavePreviewSha256.isEmpty()
                            && !Files.exists(capture.lastDownload.resolveSibling(local + ".png"))
                            && HologramPresets.all()
                                .size() == previousGlobalEntries,
                        "draft_download_keeps_pending_real_png_capability_without_committing_artifact_or_global_entry");
                    HologramPresets.Preset draft = downloadedDraft();
                    require(
                        draft.schema == 2 && HologramBlueprintProcessing.complete(draft)
                            && (fields.getString("name")
                                .trim()
                                .isEmpty()
                                || fields.getString("name")
                                    .trim()
                                    .equals(draft.name)),
                        "actual_download_keeps_reviewed_schema_processing_receipt_and_requested_name");
                    if ("saveBlueprintAs".equals(op)) require(
                        !previousDraftHashes.containsKey(local),
                        "SaveAs_issues_a_new_local_target_instead_of_overwriting_any_prior_download");
                    boolean otherDraftsUnchanged = true;
                    for (java.util.Map.Entry<String, String> entry : previousDraftHashes.entrySet()) {
                        if (entry.getKey()
                            .equals(local)) continue;
                        byte[] bytes = Files.readAllBytes(dedicatedDraftPaths.get(entry.getKey()));
                        otherDraftsUnchanged &= entry.getValue()
                            .equals(HologramBlueprintArtifact.hash(bytes));
                    }
                    require(otherDraftsUnchanged, "save_preserves_exact_bytes_of_all_other_downloaded_source_drafts");
                    dedicatedFiles.put(id, draft);
                    dedicatedDraftLocalIds.put(id, local);
                }
            } catch (Exception invalid) {
                throw new AssertionError("actual dedicated private file could not be read", invalid);
            }
        }
    }

    private HologramPresets.Preset downloadedDraft() throws java.io.IOException {
        if (capture.downloadProof == null || !HologramLibraryService.draftCurrent(player, capture.downloadProof))
            throw new java.io.IOException("Downloaded draft session/save capability changed");
        byte[] actual = Files.readAllBytes(capture.lastDownload);
        if (!capture.downloadHash.equals(HologramBlueprintArtifact.hash(actual)))
            throw new java.io.IOException("Downloaded draft file bytes changed");
        HologramPresets.Preset draft = HologramBlueprintSharing
            .decode(new String(actual, java.nio.charset.StandardCharsets.UTF_8));
        if (!capture.downloadProof.getString("saveReceipt")
            .isEmpty() && !capture.downloadId.equals(draft.id))
            throw new java.io.IOException("Downloaded draft file ID differs from its issued local capability");
        require(
            !capture.state.getBoolean("blueprintOperationSuccess") && capture.state.getBoolean("blueprintDirty"),
            "json_download_is_not_a_committed_png_artifact_or_saved_ack");
        return draft;
    }

    private void prepareAuthorSave(NBTTagCompound options) {
        try {
            HologramSession session = HologramWorkspace.find(player.getUniqueID());
            HologramPresets.Preset source = HologramAuthoringQueries.processingSource(session);
            HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults(
                "self",
                options.hasKey("deidentificationLevel", 3) ? options.getInteger("deidentificationLevel")
                    : HologramDeidentification.level(source));
            policy.autoHints = "none";
            // Current authoring stores explicit per-cell selections in the processed payload. Translate the old
            // fixture's retention choices into the public process policy, without replacing its review source.
            if (options.hasKey("retainNbt") || options.hasKey("retainCovers") || options.hasKey("retainAllNbt")) {
                boolean retainNbt = options.hasKey("retainNbt") ? options.getBoolean("retainNbt") : source.retainNbt;
                boolean covers = options.hasKey("retainCovers") ? options.getBoolean("retainCovers")
                    : source.retainCovers;
                java.util.List<String> keys = options.hasKey("retainAllNbt")
                    ? options.getBoolean("retainAllNbt") ? null
                        : HologramAuthoringQueries.selectedNbtKeys(options.getTagList("retainedNbtKeys", 8))
                    : source.retainedNbtKeys;
                if (!retainNbt) keys = java.util.Collections.emptyList();
                for (HologramPresets.Cell cell : source.cells) {
                    HologramBlueprintProcessing.CellNbtOverride choice = new HologramBlueprintProcessing.CellNbtOverride();
                    choice.x = cell.x;
                    choice.y = cell.y;
                    choice.z = cell.z;
                    choice.all = keys == null;
                    if (keys != null) choice.keys.addAll(keys);
                    omittedNbtPaths(cell, cell.tileNbt, "tile:", keys, covers, choice);
                    omittedNbtPaths(cell, cell.itemNbt, "item:", keys, covers, choice);
                    policy.cellNbtOverrides.add(choice);
                }
            }
            policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
            NBTTagCompound process = new NBTTagCompound();
            process.setTag("policy", policy.write());
            action("processBlueprint", process);
            require(
                session.processingReviewed && HologramBlueprintProcessing.complete(session.virtualPreset),
                "actual_author_processing_completes_before_save");
        } catch (java.io.IOException invalid) {
            throw new AssertionError("Actual author processing failed", invalid);
        }
    }

    private void omittedNbtPaths(HologramPresets.Cell cell, String encoded, String kind, java.util.List<String> keys,
        boolean covers, HologramBlueprintProcessing.CellNbtOverride choice) throws java.io.IOException {
        NBTTagCompound raw = HologramBlueprintNbt.decode(encoded);
        if (raw == null) return;
        NBTTagCompound selected = HologramBlueprintNbt.filterSelection(cell.block(), raw, keys, covers);
        for (Object entry : raw.func_150296_c()) {
            String key = (String) entry;
            if (!selected.hasKey(key)) choice.ignoredPaths.add(
                kind + "/"
                    + key.replace("~", "~0")
                        .replace("/", "~1"));
        }
    }

    private HologramPresets.Preset preset(String id) throws java.io.IOException {
        HologramPresets.Preset saved = dedicatedFiles.get(id);
        if (saved == null) return HologramPresets.get(id);
        String localId = dedicatedDraftLocalIds.get(id);
        Path path = dedicatedDraftPaths.get(localId);
        String hash = dedicatedDraftHashes.get(localId);
        if (path == null || hash == null) throw new java.io.IOException("No exact downloaded draft for " + id);
        byte[] actual = Files.readAllBytes(path);
        if (!hash.equals(HologramBlueprintArtifact.hash(actual)))
            throw new java.io.IOException("Previously downloaded draft bytes changed: " + localId);
        return HologramBlueprintSharing.decode(new String(actual, java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean sameDraftFile(String before, String after) {
        return HologramLibraryFacade.enabled()
            ? dedicatedDraftLocalIds.containsKey(before) && dedicatedDraftLocalIds.get(before)
                .equals(dedicatedDraftLocalIds.get(after))
            : before.equals(after);
    }

    private String activatePrivate(HologramPresets.Preset preset, String action, String localId) throws Exception {
        if (localId == null || !dedicatedDraftPaths.containsKey(localId))
            throw new java.io.IOException("Private activation has no actual downloaded local file identity");
        String validatedLocalId = HologramLibraryService.localId(localId, false);
        NBTTagCompound request = HologramLibraryProtocol.request("privateBegin");
        request.setString("session", capture.state.getString("session"));
        request.setLong("planRevision", capture.state.getLong("planRevision"));
        request.setString("action", action);
        request.setString("localId", validatedLocalId);
        // The embedded peer has downloaded the JSON, but has no GL context and therefore no PNG/disk ACK.
        // Explicitly discard that pending client save when this server fixture advances to the next source.
        request.setBoolean("confirmDiscard", true);
        // Supply a detached DTO to the public service entry. This asserts no artifact-pair upload receipt.
        String[] outcome = new String[2];
        HologramService.libraryActivate(
            player,
            request,
            HologramPresets.copy(preset),
            new HologramLibraryFacade.ActivationCompletion() {

                @Override
                public void completed(String id) {
                    outcome[0] = id;
                }

                @Override
                public void failed(String reason) {
                    outcome[1] = reason;
                }
            });
        for (int tick = 0; outcome[0] == null && outcome[1] == null && tick < 128; tick++) {
            advance();
            HologramService.tick();
            HologramSmokeTest.flushTransport();
        }
        require(outcome[0] != null && outcome[1] == null, "private_activation_waits_for_real_ready_callback");
        String id = outcome[0];
        HologramSmokeTest.flushTransport();
        require(
            id.startsWith("private_") && id.equals(capture.state.getString("virtualPresetId"))
                && validatedLocalId.equals(capture.state.getString("libraryLocalId"))
                && !id.equals(validatedLocalId),
            "actual_private_activation_preserves_local_identity_and_issues_current_session_identity");
        dedicatedFiles.put(id, HologramPresets.copy(preset));
        dedicatedDraftLocalIds.put(id, validatedLocalId);
        return id;
    }

    private void advance() {
        world.getWorldInfo()
            .incrementTotalWorldTime(world.getTotalWorldTime() + 20);
    }

    private void settle() {
        for (int tick = 0; tick < 128; tick++) {
            advance();
            HologramService.tick();
            HologramSmokeTest.flushTransport();
            if (capture.state != null && !capture.state.getBoolean("areaImporting")
                && !capture.state.getBoolean("capturePending")
                && capture.state.getInteger("job") != 1) return;
        }
        throw new AssertionError("bounded service job/import timeout");
    }

    private int stock(Item item) {
        int count = 0;
        for (ItemStack stack : player.inventory.mainInventory)
            if (stack != null && stack.getItem() == item) count += stack.stackSize;
        return count;
    }

    private void removeStock(Item item) {
        for (int i = 1; i < player.inventory.mainInventory.length; i++)
            if (player.inventory.mainInventory[i] != null && player.inventory.mainInventory[i].getItem() == item)
                player.inventory.mainInventory[i] = null;
    }

    private int stock(ItemStack sample) {
        int count = 0;
        for (ItemStack stack : player.inventory.mainInventory)
            if (stack != null && stack.getItem() == sample.getItem() && stack.getItemDamage() == sample.getItemDamage())
                count += stack.stackSize;
        return count;
    }

    private static HologramPresets.Cell cell(HologramPresets.Preset blueprint, int x, int y, int z) {
        for (HologramPresets.Cell cell : blueprint.cells) if (cell.x == x && cell.y == y && cell.z == z) return cell;
        throw new AssertionError("missing blueprint coordinate " + x + "," + y + "," + z);
    }

    private void supply(HologramPresets.Preset blueprint, boolean partial) throws Exception {
        for (int i = 1; i < player.inventory.mainInventory.length; i++) player.inventory.mainInventory[i] = null;
        int slot = 1;
        boolean controllerSupplied = false;
        for (HologramPresets.Cell cell : blueprint.cells) {
            if (cell.excluded) continue;
            if (partial && !"minecraft:chest".equals(cell.block)) {
                if (!cell.controller || controllerSupplied) continue;
                controllerSupplied = true;
            }
            require(slot < player.inventory.mainInventory.length, "fixture_material_inventory_capacity");
            ItemStack stack = cell.stack();
            require(stack != null, "fixture_material_has_registered_item");
            player.inventory.mainInventory[slot++] = stack;
            if (cell.tileNbt != null && !cell.tileNbt.isEmpty()) {
                NBTTagCompound data = HologramBlueprintNbt.decode(cell.tileNbt);
                NBTTagList covers = blueprint.retainCovers ? data.getTagList("gt.covers", 10) : new NBTTagList();
                for (int i = 0; i < covers.tagCount(); i++) {
                    ItemStack cover = GTUtility.intToStack(
                        covers.getCompoundTagAt(i)
                            .getInteger("id"));
                    require(
                        cover != null && slot < player.inventory.mainInventory.length,
                        "fixture_captured_cover_has_real_material_item");
                    cover.stackSize = 1;
                    player.inventory.mainInventory[slot++] = cover;
                }
                if (blueprint.retainNbt
                    && (blueprint.retainedNbtKeys == null || blueprint.retainedNbtKeys.contains("Items"))) {
                    NBTTagList contents = data.getTagList("Items", 10);
                    for (int i = 0; i < contents.tagCount(); i++) {
                        ItemStack material = ItemStack.loadItemStackFromNBT(contents.getCompoundTagAt(i));
                        require(
                            material != null && slot < player.inventory.mainInventory.length,
                            "fixture_captured_inventory_has_real_payment_item");
                        player.inventory.mainInventory[slot++] = material.copy();
                    }
                }
            }
        }
    }

    private NBTTagCompound rowAt(int x, int y, int z) {
        NBTTagList rows = capture.state.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.getInteger("x") == x && row.getInteger("y") == y && row.getInteger("z") == z) return row;
        }
        throw new AssertionError("missing captured world position " + x + "," + y + "," + z);
    }

    private int indexAt(int x, int y, int z) {
        NBTTagList rows = capture.state.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.getInteger("x") == x && row.getInteger("y") == y && row.getInteger("z") == z) return i;
        }
        throw new AssertionError("missing captured index " + x + "," + y + "," + z);
    }

    private void snapshotReceipt() throws Exception {
        try (FileOutputStream output = new FileOutputStream("hologram-blueprint-client-state.nbt")) {
            CompressedStreamTools.writeCompressed(capture.state, output);
        }
        System.out.println(PREFIX + "RECEIPT hologram-blueprint-client-state.nbt schema=2");
    }

    private void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(
            name + " job="
                + (capture.state == null ? -1 : capture.state.getInteger("job"))
                + " status="
                + (capture.state == null ? "no snapshot" : capture.state.getString("status")));
        assertions++;
        System.out.println(PREFIX + "PASS " + name);
    }

    private static final class CaptureNetwork extends SimpleNetworkWrapper {

        volatile NBTTagCompound state;
        volatile String reviewedSession = "";
        volatile int downloads;
        volatile Path lastDownload;
        volatile Throwable libraryFailure;
        private java.io.ByteArrayOutputStream download;
        private String transfer, downloadHash, downloadId;
        private NBTTagCompound downloadProof;
        private int nextChunk, total;
        final HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();

        CaptureNetwork() {
            super("gtmb_blueprint_observer");
        }

        @Override
        public void sendTo(IMessage message, EntityPlayerMP recipient) {
            if (USER.equals(recipient.getUniqueID())) {
                if (message instanceof HologramNetwork.LibraryReply) {
                    try {
                        acceptLibrary(((HologramNetwork.LibraryReply) message).tag);
                    } catch (Exception failure) {
                        libraryFailure = failure;
                    }
                    return;
                }
                NBTTagCompound decoded = HologramSmokeTest.decodeState(message, assembly);
                if (decoded != null) {
                    // open is a one-shot GUI intent. Subsequent stale-action snapshots do not close the review.
                    if (!decoded.getBoolean("virtualPlacement") || !decoded.getBoolean("virtualLocked")
                        || !decoded.getBoolean("blueprintAwaitingConfirmation")) reviewedSession = "";
                    else if (decoded.getBoolean("open") && "placementReview".equals(decoded.getString("openPurpose")))
                        reviewedSession = decoded.getString("session");
                    state = decoded;
                }
            }
        }

        private synchronized void acceptLibrary(NBTTagCompound packet) throws Exception {
            String op = packet.getString("op");
            if ("error".equals(op)) throw new java.io.IOException(packet.getString("message"));
            if ("downloadBegin".equals(op)) {
                if (download != null) throw new java.io.IOException("Overlapping fixture download");
                transfer = packet.getString("transfer");
                downloadHash = packet.getString("hash");
                downloadId = HologramLibraryService.localId(packet.getString("localId"), false);
                downloadProof = (NBTTagCompound) packet.copy();
                total = packet.getInteger("total");
                HologramLibraryProtocol.header(transfer, total, downloadHash);
                nextChunk = 0;
                download = new java.io.ByteArrayOutputStream();
            } else if ("downloadChunk".equals(op)) {
                if (download == null || !transfer.equals(packet.getString("transfer")))
                    throw new java.io.IOException("Fixture download transfer changed");
                byte[] bytes = packet.getByteArray("data");
                HologramLibraryProtocol
                    .chunk(total, download.size(), nextChunk++, packet.getInteger("index"), bytes.length);
                download.write(bytes);
            } else if ("downloadDone".equals(op)) {
                if (download == null || !transfer.equals(packet.getString("transfer")) || download.size() != total)
                    throw new java.io.IOException("Fixture download incomplete");
                byte[] bytes = HologramLibraryDownload.decode(
                    download.toByteArray(),
                    downloadProof.getString("encoding"),
                    downloadProof.getInteger("decodedTotal"),
                    downloadHash);
                if (!downloadHash.equals(
                    HologramLibraryProtocol.hex(
                        HologramLibraryProtocol.digest()
                            .digest(bytes))))
                    throw new java.io.IOException("Fixture actual download hash mismatch");
                HologramPresets.Preset parsed = HologramBlueprintSharing
                    .decode(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                if (!downloadProof.getString("saveReceipt")
                    .isEmpty() && !downloadId.equals(parsed.id))
                    throw new java.io.IOException("Server draft JSON/local file capability mismatch");
                Path directory = java.nio.file.Paths.get("MiaoBlueprints/smoke-client-blueprints");
                Files.createDirectories(directory);
                Path saved = directory.resolve(downloadId + ".json");
                // This is a downloaded draft, not a committed artifact pair. Keep the exact server bytes.
                Files.write(saved, bytes);
                lastDownload = saved;
                download = null;
                downloads++;
            }
        }

        void awaitDownload(EntityPlayerMP player, int previous) {
            long deadline = System.nanoTime() + 30000000000L;
            while (downloads == previous) {
                if (libraryFailure != null)
                    throw new AssertionError("Actual dedicated download failed", libraryFailure);
                if (System.nanoTime() > deadline) throw new AssertionError("Actual dedicated download timed out");
                com.miaokatze.gtmb.util.ServerTaskScheduler.INSTANCE.onServerTick(
                    new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                        cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
                HologramLibraryFacade.backend()
                    .heartbeat();
                HologramSmokeTest.flushTransport();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    public static final class PlaceVeto {

        int hits;
        int events;
        boolean restoredTileObserved;
        boolean restoredEnergyObserved;
        final int x, y, z;

        PlaceVeto(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @SubscribeEvent
        public void place(net.minecraftforge.event.world.BlockEvent.PlaceEvent event) {
            if (event.x == x && event.y == y && event.z == z) {
                events++;
                String tileType = "none", customName = "", owner = "";
                int contents = 0;
                long energy = 0;
                boolean restored = false;
                if (event.world.getTileEntity(x, y, z) instanceof TileEntityChest) {
                    tileType = "chest";
                    NBTTagCompound data = new NBTTagCompound();
                    event.world.getTileEntity(x, y, z)
                        .writeToNBT(data);
                    customName = data.getString("CustomName");
                    ItemStack treasure = ((TileEntityChest) event.world.getTileEntity(x, y, z)).getStackInSlot(4);
                    contents = treasure == null ? 0 : treasure.stackSize;
                    restored = "Blueprint chest configuration receipt".equals(customName) && treasure != null
                        && treasure.getItem() == Items.diamond
                        && contents == 7
                        && "Blueprint NBT receipt".equals(treasure.getDisplayName());
                    restoredTileObserved |= restored;
                }
                if (event.world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity) {
                    tileType = "GT";
                    BaseMetaTileEntity tile = (BaseMetaTileEntity) event.world.getTileEntity(x, y, z);
                    energy = tile.getStoredEU();
                    owner = String.valueOf(tile.getOwnerUuid());
                    restored = energy == 5000 && USER.equals(tile.getOwnerUuid());
                    restoredEnergyObserved |= restored;
                }
                // ItemStack placement posts its native event before the service restores the blueprint.
                // Target the later service event so this fixture exercises rollback after actual NBT restoration.
                if (restored) {
                    hits++;
                    event.setCanceled(true);
                }
                int diamondStock = 0, chestStock = 0;
                for (ItemStack stack : event.player.inventory.mainInventory) if (stack != null) {
                    if (stack.getItem() == Items.diamond) diamondStock += stack.stackSize;
                    if (stack.getItem() == Item.getItemFromBlock(Blocks.chest)) chestStock += stack.stackSize;
                }
                System.out.println(
                    PREFIX + "EVENT receipt="
                        + events
                        + " at="
                        + x
                        + ","
                        + y
                        + ","
                        + z
                        + " tile="
                        + tileType
                        + " customName="
                        + customName
                        + " contents="
                        + contents
                        + " playerDiamonds="
                        + diamondStock
                        + " playerChests="
                        + chestStock
                        + " energy="
                        + energy
                        + " owner="
                        + owner
                        + " cancelledAfterRestore="
                        + restored);
            }
        }
    }
}
