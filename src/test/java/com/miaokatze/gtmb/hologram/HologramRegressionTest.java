package com.miaokatze.gtmb.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.testutil.TestRunner;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 真 NBT/StructureLib/网络边界回归。真实世界施工另由 HologramSmokeTest 检查。 */
public final class HologramRegressionTest {

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramRegressionTest.class, args)) return;
        Map<String, Runnable> cases = new LinkedHashMap<>();
        cases.put("blueprint_actual_cell_limits_and_sparse_area_scanning", HologramBlueprintLimitsRegression::run);
        cases.put(
            "blueprint_client_cell_admission_is_strict_and_atomic",
            com.miaokatze.gtmb.client.hologram.HologramBlueprintCellAdmissionRegression::run);
        cases.put(
            "selection_discard_confirmation_preserves_content_and_persists_only_accepted_choice",
            com.miaokatze.gtmb.client.hologram.HologramSelectionDiscardRegression::run);
        cases.put(
            "editor_workspaces_keep_owned_domains_and_transport_rollback",
            HologramEditorWorkspacesRegression::run);
        cases.put(
            "tutorial_engine_geometry_owns_cut_payload_and_checkpoint",
            com.miaokatze.gtmb.client.hologram.HologramEditorTutorialGeometryRegression::run);
        cases.put(
            "tutorial_real_ebf_controller_and_declared_author_courses",
            com.miaokatze.gtmb.client.hologram.HologramEditorTutorialLessonsRegression::run);
        cases.put(
            "tutorial_playback_empty_document_identity_and_retired_callbacks",
            com.miaokatze.gtmb.client.hologram.HologramEditorTutorialPlaybackRegression::run);
        cases.put(
            "isometric_camera_policy_loaded_surface_and_signed_floating_target",
            () -> com.miaokatze.gtmb.client.hologram.HologramIsometricCameraRegression.main(new String[0]));
        cases.put(
            "large_attachment_vanilla_boundary_compact_geometry_and_local_presets",
            HologramLargeAttachmentRegression::run);
        cases.put(
            "picture_attachment_palette_filters_background_volume_and_portability",
            HologramPictureBoxRegression::run);
        cases.put(
            "text_microblock_volume_editable_metadata_version_provenance_and_blacklist",
            HologramTextBoxRegression::run);
        cases.put(
            "completed_projection_deadline_is_not_extended_by_heartbeats",
            com.miaokatze.gtmb.client.hologram.HologramProjectionLifetimeRegression::run);
        cases.put(
            "coordinate_free_hatch_profiles_match_complete_role_combinations",
            HologramHatchProfilesRegression::run);
        cases.put(
            "configuration_presets_persist_detached_combinations_and_exact_successful_receipts",
            com.miaokatze.gtmb.client.hologram.HologramConfigurationPresetsRegression::run);
        cases.put(
            "background_tasks_survive_ui_close_and_fence_late_results",
            com.miaokatze.gtmb.client.hologram.HologramBackgroundTasksRegression::run);
        cases.put(
            "area_import_task_uses_complete_geometry_provenance_after_metadata_stripping",
            com.miaokatze.gtmb.client.hologram.HologramAreaWorkspaceReceiptRegression::run);
        cases.put(
            "completed_area_capture_retires_local_gestures_before_the_next_blueprint",
            com.miaokatze.gtmb.client.hologram.HologramAreaSelectionLifecycleRegression::run);
        cases.put("server_work_budgets_packet_metrics_and_job_deltas", HologramServerLoadRegression::run);
        cases.put(
            "admitted_jobs_survive_hotbar_selection_and_publish_real_stop_receipts",
            HologramJobLifecycleRegression::run);
        cases.put(
            "area_resource_planning_is_resumable_and_matches_sync_results",
            HologramResourcePlannerRegression::run);
        cases.put("blueprint_load_copy_and_capture_encoding_preserve_complete_data", () -> {
            try {
                HologramBlueprintCopyEncodingRegression.run();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        cases.put(
            "snapshot_publication_preserves_fifo_and_cancels_stale_generations",
            HologramSnapshotPublishRegression::run);
        cases.put("snapshot_ae_single_decode_preserves_complete_reports", HologramSnapshotAeTaskRegression::run);
        cases.put(
            "client_progress_is_factual_session_bound_and_render_only",
            com.miaokatze.gtmb.client.hologram.HologramProgressRegression::run);
        cases.put(
            "client_job_deltas_are_ordered_bounded_and_display_only",
            com.miaokatze.gtmb.client.hologram.HologramJobVisualRegression::run);
        cases.put(
            "configured_testlab_is_inert_by_default_and_owns_bounded_facilities",
            com.miaokatze.gtmb.testlab.TestLabRegression::run);
        cases.put(
            "testlab_blueprint_bridge_requires_owned_loaded_survival_fixtures",
            HologramTestLabBlueprintRegression::run);
        cases.put(
            "dynamic_structure_definitions_are_frozen_per_capture_and_resumed_across_pieces",
            HologramDynamicDefinitionRegression::run);
        cases.put("community_extension_registrations_and_detached_snapshot_processing", () -> {
            try {
                HologramCommunityApiRegression.run();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        cases.put("cut_clipboard_preserves_raw_data_atomic_storage_and_packet_budgets", HologramCutRegression::run);
        cases.put("cut_native_tile_read_has_world_and_preserves_registry_payload", HologramCutTilesRegression::run);
        cases.put(
            "cut_native_disk_readback_rejects_stale_blocks_tiles_and_relocated_chunks",
            HologramCutDiskRegression::run);
        cases.put("cut_pose_geometry_raw_directions_and_extension_lifecycle", HologramCutTransformRegression::run);
        cases.put("cut_raw_preview_preserves_payload_in_detached_world", () -> {
            try {
                com.miaokatze.gtmb.client.hologram.HologramCutRawPreviewRegression.run();
            } catch (ReflectiveOperationException failure) {
                throw new RuntimeException(failure);
            }
        });
        cases.put(
            "cut_input_switcher_queue_priority_and_cancel",
            com.miaokatze.gtmb.client.hologram.HologramCutInputRegression::run);
        cases.put(
            "cut_idle_sync_and_lost_ack_recovery_never_replay_world_edits",
            com.miaokatze.gtmb.client.hologram.HologramCutRequestRegression::run);
        cases.put("cut_directory_reads_survive_tool_context_races", HologramCutContextRegression::run);
        cases.put(
            "workstation_modal_input_and_session_bound_ack_tickets",
            com.miaokatze.gtmb.client.hologram.HologramUiModuleRegression::run);
        cases.put(
            "workstation_host_ports_are_explicit_and_do_not_share_minecraft_method_names",
            com.miaokatze.gtmb.client.hologram.HologramUiHostRegression::run);
        cases.put(
            "projector_family_uses_native_mui2_controls_and_shared_pocket_textures",
            com.miaokatze.gtmb.client.hologram.HologramMuiMigrationRegression::run);
        cases.put(
            "attachment_dialog_reopen_refreshes_closures_and_retires_stale_input",
            com.miaokatze.gtmb.client.hologram.HologramUiLifecycleRegression::run);
        cases.put(
            "cut_storage_preview_fences_selection_lifecycle_and_late_raw_replies",
            com.miaokatze.gtmb.client.hologram.HologramCutStoragePreviewRegression::run);
        cases.put(
            "preview_camera_wasd_follows_orientation_and_normalizes_motion",
            com.miaokatze.gtmb.client.hologram.HologramPreviewCameraRegression::run);
        cases.put(
            "editor_camera_frame_matches_native_geometry_and_presented_picking",
            com.miaokatze.gtmb.client.hologram.HologramEditorCameraFrameRegression::run);
        cases.put(
            "native_annotation_markers_keep_collision_and_viewport_hit_bounds",
            com.miaokatze.gtmb.client.hologram.HologramEditorAnnotationOverlayRegression::run);
        cases.put(
            "native_editor_theme_serialization_keeps_registered_tiled_and_nine_slice_textures",
            com.miaokatze.gtmb.client.hologram.HologramEditorNativeThemeRegression::run);
        cases.put(
            "native_editor_expanded_layout_waits_for_parent_and_preserves_both_axes",
            com.miaokatze.gtmb.client.hologram.HologramEditorNativeLayoutRegression::run);
        cases.put("native_channels_fallback_and_arbitrary_keys", HologramRegressionTest::channels);
        cases.put("unadapted_channels_are_editable_and_reach_native_trigger", HologramRegressionTest::openChannels);
        cases.put("packet_snapshot_roundtrip_keeps_all_channel_values", HologramRegressionTest::packetRoundTrip);
        cases.put("hostile_packet_lengths_are_rejected", HologramRegressionTest::badLengths);
        cases.put("capture_hooks_are_inert_outside_collection", HologramRegressionTest::inertCapture);
        cases.put("server_batch_receipt_roundtrip", HologramRegressionTest::planProtocol);
        cases.put("failed_native_draft_encoding_revokes_only_matching_owner", HologramRegressionTest::failedDraftOwner);
        cases.put("frame_factory_material_and_te_boundary", HologramRegressionTest::frames);
        cases.put("sealed_gt_inventory_fluid_and_position_roundtrip", HologramRegressionTest::sealedRecovery);
        cases.put("material_source_settings_persist_and_default", HologramRegressionTest::materialSettings);
        cases.put(
            "client_blueprint_library_is_local_atomic_and_preserves_authorship",
            com.miaokatze.gtmb.client.hologram.HologramClientLibraryRegression::run);
        cases.put("legacy_blueprint_upgrade_is_lossless_portable_and_pair_index_atomic", () -> {
            try {
                HologramBlueprintUpgradeRegression.run();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        cases.put("dedicated_public_library_enforces_quota_owner_op4_and_transfer_budgets", () -> {
            try {
                HologramLibraryRegression.run();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        cases.put("network_energy_quotes_consume_once_and_refund_only_actual_debits", HologramEnergyRegression::run);
        cases.put("decor_fractional_materials_pay_once_and_refund_failed_cells", HologramDecorMaterialsRegression::run);
        cases.put(
            "blueprint_processing_gui_requires_choices_and_preserves_review_progress",
            com.miaokatze.gtmb.client.hologram.HologramProcessingFlowRegression::run);
        cases.put(
            "quantum_group_drafts_preserve_selection_history_and_complete_placement",
            com.miaokatze.gtmb.client.hologram.HologramQuantumGroupsRegression::run);
        cases.put("essentia_payloads_require_actual_aspect_payment_and_exact_refunds", HologramEssentiaRegression::run);
        cases.put("gt_me_samples_keep_filters_and_drop_simulated_inventory_shadows", HologramGtMeShadowRegression::run);
        cases.put("blueprint_processing_reviews_nbt_coordinates_and_reusable_presets", () -> {
            try {
                HologramBlueprintProcessingRegression.run();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        cases.put("device_buffers_require_paid_energy_and_keep_native_capacity", HologramEnergyPayloadRegression::run);
        cases.put("gtswn_cover_buffers_pay_current_wireless_energy_and_refund_once", HologramGtswnCacheRegression::run);
        cases.put("blueprint_deidentification_levels_preserve_recipes_and_irregular_selection", () -> {
            try {
                HologramDeidentificationRegression.run();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        cases.put("network_groups_drop_external_identity_and_charge_physical_templates", () -> {
            try {
                HologramNetworkBindingsRegression.run();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        cases.put("container_simulation_and_committed_consumption", HologramRegressionTest::materialSimulation);
        cases
            .put("container_rollback_returns_once_without_restoring_old_bag", HologramRegressionTest::materialRollback);
        cases.put(
            "container_exception_after_report_preserves_actual_material",
            HologramRegressionTest::materialException);
        cases.put("refund_overflow_keeps_remainder_and_skips_held_tool", HologramRegressionTest::refundOverflow);
        cases.put("full_inventory_refund_survives_persisted_player_data", HologramRegressionTest::persistedRefund);
        cases.put("preview_counts_match_nbt_and_never_take_controller_items", HologramRegressionTest::previewCounts);
        cases
            .put("hatch_faces_exclude_template_cavity_even_before_construction", HologramRegressionTest::hatchExterior);
        cases.put("voltage_preset_respects_requested_tier_stock_and_fixed_roles", HologramRegressionTest::hatchPreset);
        cases.put("sealed_facing_override_keeps_inventory_and_source_payload", HologramRegressionTest::sealedFacing);
        cases.put("disabled_sources_are_not_inspected_or_used", HologramRegressionTest::disabledSources);
        cases.put("recovery_accounts_for_partial_ME_acceptance", HologramRegressionTest::recoveryRemainder);
        cases.put("committed_world_recovery_waits_for_room_and_delivers_once", HologramRegressionTest::worldRecovery);
        cases.put("prefab_samples_exclude_device_nbt_and_bound_coordinates", HologramRegressionTest::prefabSamples);
        cases.put(
            "global_settings_update_detached_controller_draft_without_changing_targets",
            HologramRegressionTest::draftSettings);
        cases.put(
            "custom_shortcuts_persist_and_reject_conflicts",
            com.miaokatze.gtmb.client.hologram.HologramKeyBindingsRegression::run);
        cases.put(
            "workstation_fits_display_scales_and_keeps_preview_clear",
            com.miaokatze.gtmb.client.hologram.HologramLayoutRegression::run);
        cases.put(
            "machine_fronts_follow_world_orientation_on_all_six_faces",
            com.miaokatze.gtmb.client.hologram.HologramRenderingRegression::run);
        cases.put(
            "machine_textures_support_optional_inventory_api_and_legacy_six_face_fallback",
            com.miaokatze.gtmb.client.hologram.HologramTextureCompatibilityRegression::run);
        cases.put(
            "native_direct_vertex_layouts_keep_geometry_material_and_source_buffer_state",
            com.miaokatze.gtmb.client.hologram.HologramVertexDataRegression::run);
        cases.put(
            "partial_native_models_keep_neighboring_floor_and_wall_faces",
            com.miaokatze.gtmb.client.hologram.HologramOcclusionRegression::run);
        cases.put(
            "declared_alternatives_include_all_chain_and_tier_branches",
            HologramRegressionTest::declaredAlternatives);
        cases.put(
            "unknown_declarations_remain_partial_without_running_machine_callbacks",
            HologramRegressionTest::unknownDeclaration);
        cases.put("native_channel_casing_and_batch_compatibility", HologramCompatibilityRegression::run);
        cases.put("channel_wrapped_declarations_are_expanded", HologramRegressionTest::wrappedDeclarations);
        cases.put("clipboard_edits_preserve_automatic_source_and_other_cells", HologramServiceEditRegression::run);
        cases.put("stable_module_edits_use_sparse_history_resources_and_cell_deltas", HologramLocalEditRegression::run);
        cases.put(
            "geometry_edits_patch_both_endpoints_and_validate_at_next_workflow",
            HologramGeometryEditRegression::run);
        cases.put("preset_updates_keep_identity_and_persist_atomically", HologramPresetPersistenceRegression::run);
        cases.put(
            "blueprint_nbt_sharing_missing_dependencies_and_legacy_storage",
            HologramBlueprintPersistenceRegression::run);
        cases.put("blueprint_cover_costs_preserve_data_and_reject_invalid_ids", HologramBlueprintCoverRegression::run);
        cases.put("blueprint_batch_geometry_and_flags_are_atomic", HologramBlueprintServiceRegression::run);
        cases.put(
            "multiblock_candidates_and_configuration_are_structure_scoped",
            HologramStructureIsolationRegression::run);
        cases.put(
            "blueprint_inventory_fluid_and_wireless_resource_transactions",
            HologramBlueprintResourcesRegression::run);
        cases.put(
            "virtual_preset_world_turns_preserve_geometry_edits_and_controller_roll",
            HologramVirtualRotationRegression::run);
        cases.put(
            "blueprint_default_orientation_is_atomic_undoable_and_saved",
            HologramBlueprintRotationRegression::run);
        cases.put(
            "local_preview_cube_poses_preserve_handedness_and_canonical_recipes",
            HologramPreviewPoseRegression::run);
        cases.put("local_display_decoding_grants_no_server_construction_authority", () -> {
            try {
                com.miaokatze.gtmb.client.hologram.HologramLocalPreviewRegression.run();
            } catch (Exception failure) {
                throw new AssertionError("Local display decoding", failure);
            }
        });
        cases.put(
            "local_material_estimates_never_grant_payment_or_inventory_authority",
            HologramStaticMaterialsRegression::run);
        cases.put("rotation_cycles_all_six_legal_fronts", HologramRegressionTest::faceRotation);
        cases.put("hatch_direction_policy_keeps_built_snapshot", HologramRegressionTest::hatchDirectionSnapshot);
        cases.put("hatch_matching_preserves_override_and_legacy_class_rules", HologramRegressionTest::hatchMatching);
        cases.put("large_snapshots_are_atomic_ordered_and_bounded", HologramSnapshotAssemblyRegression::run);
        cases.put("incremental_capture_and_large_drafts_keep_editor_state", HologramLargeStructureRegression::run);
        cases.put("large_snapshot_codec_rejects_hostile_lengths", HologramSnapshotCodecRegression::run);
        cases.put("large_blueprint_library_transactions_and_byte_budgets", HologramBlueprintStorageV003Regression::run);
        cases.put("blueprint_annotations_and_save_failures_remain_visible", HologramBlueprintAnnotationRegression::run);
        cases.put("reviewed_lazy_declarations_recover_fixed_casing_and_frames", HologramLazyDeclarationRegression::run);
        cases.put(
            "standard_factory_provenance_and_read_only_declaration_spi",
            HologramDeclarationAdaptersRegression::run);
        cases.put(
            "native_pipe_hatch_and_cover_display_preserves_prototypes",
            com.miaokatze.gtmb.client.hologram.HologramNativeDisplayRegression::run);
        cases.put(
            "client_snapshot_decoder_preserves_fifo_budget_and_world_epoch",
            com.miaokatze.gtmb.client.hologram.HologramSnapshotInboxRegression::run);
        cases.put(
            "large_client_snapshots_keep_remote_structure_cells",
            com.miaokatze.gtmb.client.hologram.HologramLargeStateRegression::run);
        cases.put("independent_registry_has_own_item_tab_and_namespace", ProjectorRegistrationRegression::registry);
        cases.put("decoration_voucher_recipe_and_industrial_resource_veto", DecorationVoucherRegression::run);
        cases.put(
            "material_quote_stable_identity_long_counts_and_shared_fuzzy_stock",
            HologramMaterialQuoteRegression::run);
        cases.put(
            "official_projector_upgrades_one_to_one_with_no_extra_inputs",
            ProjectorRegistrationRegression::recipe);
        cases.put(
            "native_block_rotation_transforms_preserve_payload_and_geometry",
            HologramBlockTransformRegression::run);
        cases.put("world_gestures_require_explicit_material_confirmation", HologramWorldFlowRegression::run);
        cases.put(
            "library_stage_is_owner_session_revision_bound_and_preserves_creator",
            HologramLibraryServiceRegression::run);
        cases.put("world_use_requires_physical_release_between_stages", HologramUsePressFenceRegression::run);
        cases.put("locked_world_arrows_quantize_camera_direction_and_throttle_repeat", HologramNudgeRegression::run);
        cases
            .put("locked_nudge_revokes_old_authority_and_rejects_hostile_offsets", HologramNudgeServiceRegression::run);
        cases.put(
            "opened_placement_review_receives_background_job_updates_without_reopening_dismissed_UI",
            com.miaokatze.gtmb.client.hologram.HologramPlacementReviewContextRegression::run);
        cases.put(
            "guidance_follows_library_context_and_exact_network_binding_acknowledgements",
            com.miaokatze.gtmb.client.hologram.HologramGuidanceRegression::run);
        cases.put(
            "workspace_tab_survives_server_session_and_client_restart",
            com.miaokatze.gtmb.client.hologram.HologramUiTabMemoryRegression::run);
        cases.put(
            "world_service_locks_current_revision_and_retains_cancelled_draft",
            HologramWorldServiceRegression::run);
        cases.put(
            "editor_selection_preserves_gt_identity_and_structure_membership",
            com.miaokatze.gtmb.client.hologram.HologramSelectionRegression::run);
        cases.put(
            "native_preview_tiles_are_isolated_and_never_write_live_worlds",
            com.miaokatze.gtmb.client.hologram.HologramNativePreviewIsolationRegression::run);
        cases
            .put("registered_declared_families_never_mix_unaccepted_metadata", HologramRegisteredFamilyRegression::run);
        cases.put("ae_edit_review_tokens_and_canonical_persistence", HologramAeProtocolRegression::run);
        cases.put("ae_unresolved_loss_markers_require_explicit_resolution", HologramAeBlueprintMarkerRegression::run);
        cases.put("ae2fc_fluid_cells_keep_strict_metadata_and_drop_contents", HologramAeFluidCellRegression::run);
        cases.put(
            "blueprint_metadata_records_release_and_exact_pack_versions",
            HologramBlueprintVersionsRegression::run);
        cases.put("blueprint_json_png_artifacts_preserve_atomic_pairs", HologramBlueprintArtifactRegression::run);
        cases.put(
            "compact_blueprints_preserve_semantics_and_bound_dictionary_expansion",
            HologramBlueprintCompactRegression::run);
        cases.put(
            "ae_virtual_container_isolates_marks_from_real_inventory",
            com.miaokatze.gtmb.client.hologram.HologramAeContainerRegression::run);
        cases.put(
            "ae_save_guidance_preserves_complete_bounded_issue_reports",
            com.miaokatze.gtmb.client.hologram.HologramAeGuidanceRegression::run);
        cases.put(
            "website_accent_cycles_smoothly_and_preserves_disabled_and_warning_colors",
            com.miaokatze.gtmb.client.hologram.HologramUiSpectrumRegression::run);
        TestRunner.run(HologramRegressionTest.class, cases);
    }

    @SuppressWarnings("unchecked")
    private static void failedDraftOwner() {
        Map<java.util.UUID, Object> sessions = null;
        java.util.Queue<Runnable> tasks = null;
        java.util.List<Runnable> previousTasks = new java.util.ArrayList<>();
        java.util.UUID actor = java.util.UUID.fromString("f4bf794d-95f5-4c02-ad27-6924a035b31f");
        Object previous = null;
        net.minecraft.entity.player.EntityPlayerMP owner = null, reconnect = null;
        try {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            java.lang.reflect.Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
                "player",
                sun.misc.Unsafe.class,
                HologramServiceEditRegression.ReadOnlyWorld.class,
                java.util.UUID.class,
                String.class);
            factory.setAccessible(true);
            owner = (net.minecraft.entity.player.EntityPlayerMP) factory
                .invoke(null, unsafe, world, actor, "DraftOwner");
            reconnect = (net.minecraft.entity.player.EntityPlayerMP) factory
                .invoke(null, unsafe, world, actor, "DraftOwner");
            Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(
                net.minecraft.entity.player.EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            constructor.setAccessible(true);
            Object session = constructor.newInstance(owner, 0, 64, 0, false, 2);
            java.lang.reflect.Field sessionMap = HologramWorkspace.class.getDeclaredField("SESSIONS");
            sessionMap.setAccessible(true);
            sessions = (Map<java.util.UUID, Object>) sessionMap.get(null);
            previous = sessions.put(actor, session);
            java.lang.reflect.Method set = HologramLibraryServiceRegression.class
                .getDeclaredMethod("set", Object.class, String.class, Object.class);
            java.lang.reflect.Method get = HologramLibraryServiceRegression.class
                .getDeclaredMethod("get", Object.class, String.class);
            set.setAccessible(true);
            get.setAccessible(true);
            set.invoke(null, session, "libraryUiActive", true);
            set.invoke(null, session, "planRevision", 42L);
            set.invoke(null, session, "blueprintSaveRevision", 42L);
            String receipt = java.util.UUID.randomUUID()
                .toString(), local = "local_" + java.util.UUID.randomUUID();
            set.invoke(null, session, "blueprintSaveReceipt", receipt);
            set.invoke(null, session, "blueprintSaveLocalId", local);
            set.invoke(null, session, "blueprintDirty", false);
            NBTTagCompound proof = new NBTTagCompound();
            proof.setString("session", (String) get.invoke(null, session, "id"));
            proof.setLong("planRevision", 42L);
            proof.setString("saveReceipt", receipt);
            proof.setString("replaceLocalId", local);
            java.lang.reflect.Field queue = com.miaokatze.gtmb.util.ServerTaskScheduler.class
                .getDeclaredField("SERVER_TASKS");
            queue.setAccessible(true);
            tasks = (java.util.Queue<Runnable>) queue.get(null);
            Runnable previousTask;
            while ((previousTask = tasks.poll()) != null) previousTasks.add(previousTask);
            for (String field : new String[] { "session", "planRevision", "saveReceipt", "replaceLocalId" }) {
                NBTTagCompound stale = (NBTTagCompound) proof.copy();
                if ("planRevision".equals(field)) stale.setLong(field, 41L);
                else stale.setString(field, "stale");
                HologramLibraryFacade.draftPreparationFailed(owner, stale, "encode failure");
                tasks.remove()
                    .run();
                SimpleAssert.eq(
                    receipt,
                    get.invoke(null, session, "blueprintSaveReceipt"),
                    "failed encoding cannot revoke another " + field);
            }
            HologramLibraryFacade.draftPreparationFailed(reconnect, proof, "encode failure");
            tasks.remove()
                .run();
            SimpleAssert.eq(
                receipt,
                get.invoke(null, session, "blueprintSaveReceipt"),
                "a reconnected actor cannot revoke the old owner capability");
            owner.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            HologramLibraryFacade.draftPreparationFailed(owner, proof, "encode failure");
            tasks.remove()
                .run();
            SimpleAssert.eq(
                receipt,
                get.invoke(null, session, "blueprintSaveReceipt"),
                "a reloaded world cannot revoke another world capability");
            owner.worldObj = world;
            HologramLibraryFacade.draftPreparationFailed(owner, proof, "encode failure");
            SimpleAssert.eq(
                receipt,
                get.invoke(null, session, "blueprintSaveReceipt"),
                "the worker never mutates the server owner before its queued callback");
            tasks.remove()
                .run();
            SimpleAssert.that(
                ((String) get.invoke(null, session, "blueprintSaveReceipt")).isEmpty()
                    && (Boolean) get.invoke(null, session, "blueprintDirty"),
                "the exact issued capability is retired before preview verification and its draft stays dirty");
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        } finally {
            if (sessions != null) {
                if (previous == null) sessions.remove(actor);
                else sessions.put(actor, previous);
            }
            if (tasks != null) {
                tasks.clear();
                tasks.addAll(previousTasks);
            }
            for (net.minecraft.entity.player.EntityPlayerMP player : new net.minecraft.entity.player.EntityPlayerMP[] {
                owner, reconnect }) if (player != null)
                    player.playerNetServerHandler.netManager.channel()
                        .close();
        }
    }

    private static void hatchMatching() {
        gregtech.api.interfaces.metatileentity.IMetaTileEntity tile;
        try {
            java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            tile = (gregtech.api.interfaces.metatileentity.IMetaTileEntity) ((sun.misc.Unsafe) field.get(null))
                .allocateInstance(gregtech.api.metatileentity.implementations.MTEHatchInput.class);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot create hatch class fixture", failure);
        }
        var rejection = new gregtech.api.interfaces.IHatchElement<Object>() {

            public java.util.List<Class<? extends gregtech.api.interfaces.metatileentity.IMetaTileEntity>> mteClasses() {
                return java.util.Collections
                    .singletonList(gregtech.api.interfaces.metatileentity.IMetaTileEntity.class);
            }

            public gregtech.api.util.IGTHatchAdder<Object> adder() {
                return null;
            }

            public String name() {
                return "test";
            }

            public long count(Object context) {
                return 0;
            }

            public boolean matchesHatch(gregtech.api.interfaces.metatileentity.IMetaTileEntity candidate) {
                if (candidate == null) throw new IllegalArgumentException("delegate failure");
                return false;
            }
        };
        SimpleAssert.that(!HologramHatchPolicy.matchesHatch(rejection, tile), "custom rejection overrides class match");
        SimpleAssert
            .that(HologramHatchPolicy.legacyMatchesHatch(rejection, tile), "legacy rule accepts declared class");
        SimpleAssert.that(!HologramHatchPolicy.legacyMatchesHatch(rejection, null), "legacy rule rejects absent tile");
        SimpleAssert.that(
            !HologramHatchPolicy.legacyMatchesHatch(
                rejection.withMteClass(gregtech.api.metatileentity.implementations.MTEHatchOutput.class),
                tile),
            "legacy rule rejects undeclared tile class");
        try {
            HologramHatchPolicy.matchesHatch(rejection, null);
            throw new AssertionError("delegate failure must propagate");
        } catch (IllegalArgumentException expected) {
            SimpleAssert.eq("delegate failure", expected.getMessage(), "failure cannot fall back to class matching");
        }
    }

    private static void hatchDirectionSnapshot() {
        var element = com.gtnewhorizon.structurelib.structure.StructureUtility
            .ofBlock(net.minecraft.init.Blocks.stone, 0);
        var facing = com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.DEFAULT;
        var excluded = java.util.EnumSet.of(net.minecraftforge.common.util.ForgeDirection.NORTH);
        HologramHatchPolicy.directions(element, excluded);
        excluded.clear();
        excluded.add(net.minecraftforge.common.util.ForgeDirection.SOUTH);
        SimpleAssert.that(
            !HologramHatchPolicy.allowed(
                element,
                facing,
                facing.getWorldDirection(net.minecraftforge.common.util.ForgeDirection.NORTH)),
            "built hatch retains the original forbidden direction after builder mutation");
        SimpleAssert.that(
            HologramHatchPolicy.allowed(
                element,
                facing,
                facing.getWorldDirection(net.minecraftforge.common.util.ForgeDirection.SOUTH)),
            "later builder exclusions do not alter the built hatch");
    }

    private static void faceRotation() {
        int[] legal = { 0, 1, 2, 3, 4, 5 };
        SimpleAssert.eq(5, HologramCellRotation.nextFacing(2, legal), "north turns east");
        SimpleAssert.eq(3, HologramCellRotation.nextFacing(5, legal), "east turns south");
        SimpleAssert.eq(4, HologramCellRotation.nextFacing(3, legal), "south turns west");
        SimpleAssert.eq(1, HologramCellRotation.nextFacing(4, legal), "west turns up");
        SimpleAssert.eq(0, HologramCellRotation.nextFacing(1, legal), "up turns down");
        SimpleAssert.eq(2, HologramCellRotation.nextFacing(0, legal), "down wraps to north");
        SimpleAssert.eq(3, HologramCellRotation.nextFacing(2, new int[] { 0, 1, 2, 3 }), "skip invalid east");
        SimpleAssert.eq(1, HologramCellRotation.nextFacing(2, new int[] { 0, 1, 2 }), "skip invalid horizontal fronts");
        SimpleAssert.eq(1, HologramCellRotation.nextFacing(0, new int[] { 0, 1 }), "vertical fronts remain legal");
        SimpleAssert.eq(-1, HologramCellRotation.nextFacing(2, new int[] { 2 }), "single face has no rotation");
    }

    private static void declaredAlternatives() {
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> stone = com.gtnewhorizon.structurelib.structure.StructureUtility
            .ofBlock(net.minecraft.init.Blocks.stone, 0);
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> dirt = com.gtnewhorizon.structurelib.structure.StructureUtility
            .onElementPass(
                value -> { throw new AssertionError("must not mutate controller while listing candidates"); },
                com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock(net.minecraft.init.Blocks.dirt, 0));
        HologramDeclarationCandidates chain = new HologramDeclarationCandidates();
        chain.collect(
            com.gtnewhorizon.structurelib.structure.StructureUtility.ofChain(stone, dirt, stone),
            null,
            null,
            0,
            0,
            0,
            new ItemStack(Items.feather),
            null,
            0);
        SimpleAssert.eq(2, chain.stacks.size(), "chain lists every branch once");
        SimpleAssert.that(chain.complete, "fixed block declaration is complete");
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> tiered = com.gtnewhorizon.structurelib.structure.StructureUtility
            .<Object, Integer>ofBlocksTiered(
                (block, meta) -> { throw new AssertionError("must not execute arbitrary tier converter"); },
                java.util.Arrays.asList(
                    org.apache.commons.lang3.tuple.Pair.of(net.minecraft.init.Blocks.stone, 0),
                    org.apache.commons.lang3.tuple.Pair.of(net.minecraft.init.Blocks.dirt, 0),
                    org.apache.commons.lang3.tuple.Pair.of(net.minecraft.init.Blocks.cobblestone, 0)),
                -1,
                (value, tier) -> { throw new AssertionError("must not update controller tier"); },
                value -> { throw new AssertionError("must not read arbitrary controller tier"); });
        HologramDeclarationCandidates tiers = new HologramDeclarationCandidates();
        tiers.collect(tiered, null, null, 0, 0, 0, new ItemStack(Items.feather), null, 0);
        SimpleAssert.eq(3, tiers.stacks.size(), "lists all declared tiers despite one selected signal");
        SimpleAssert.that(!tiers.complete, "tier hints do not prove arbitrary acceptance is exhaustive");
    }

    private static void wrappedDeclarations() {
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> element = com.gtnewhorizon.structurelib.structure.StructureUtility
            .withChannel(
                "unit_casing",
                com.gtnewhorizon.structurelib.structure.StructureUtility.onElementPass(
                    value -> { throw new AssertionError("must not update controller"); },
                    com.gtnewhorizon.structurelib.structure.StructureUtility
                        .ofBlock(net.minecraft.init.Blocks.stone, 0)));
        HologramDeclarationCandidates declaration = new HologramDeclarationCandidates();
        declaration.collect(element, null, null, 0, 0, 0, new ItemStack(Items.feather), null, 0);
        SimpleAssert.eq(1, declaration.stacks.size(), "native channel wrapper exposes backing declaration");
        SimpleAssert.that(declaration.complete, "reviewed wrapper preserves completeness");
    }

    private static void unknownDeclaration() {
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> unknown = new com.gtnewhorizon.structurelib.structure.IStructureElement<Object>() {

            @Override
            public boolean check(Object context, net.minecraft.world.World world, int x, int y, int z) {
                throw new AssertionError("must not invoke machine check");
            }

            @Override
            public boolean spawnHint(Object context, net.minecraft.world.World world, int x, int y, int z,
                ItemStack trigger) {
                throw new AssertionError("must not invoke unknown hint callback");
            }

            @Override
            public boolean placeBlock(Object context, net.minecraft.world.World world, int x, int y, int z,
                ItemStack trigger) {
                throw new AssertionError("must not invoke unknown placement callback");
            }
        };
        HologramDeclarationCandidates result = new HologramDeclarationCandidates();
        result.collect(unknown, null, null, 0, 0, 0, new ItemStack(Items.feather), null, 0);
        SimpleAssert.that(
            !result.complete && result.stacks.isEmpty(),
            "unknown factory is partial and gives no guessed alternatives");
        HologramDeclarationCandidates air = new HologramDeclarationCandidates();
        air.collect(
            com.gtnewhorizon.structurelib.structure.StructureUtility.isAir(),
            null,
            null,
            0,
            0,
            0,
            new ItemStack(Items.feather),
            null,
            0);
        SimpleAssert.that(air.complete && air.stacks.isEmpty(), "declared air needs no material alternatives");
    }

    private static void disabledSources() {
        net.minecraft.entity.player.EntityPlayerMP player = materialPlayer();
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.main = false;
        settings.containers = false;
        settings.me = false;
        HologramMaterials.Context context = new HologramMaterials.Context(
            player,
            settings,
            java.util.Collections.emptyList());
        // Disabled sources must not even dereference the player's inventory when reporting stock.
        player.inventory = null;
        SimpleAssert
            .eq(0, context.mainCount(new ItemStack(Items.feather), 64), "disabled main source is not inspected");
        SimpleAssert.eq(0, context.count(new ItemStack(Items.feather), 64), "disabled sources cannot supply items");
        SimpleAssert.eq(
            0,
            context.catalog(20)
                .size(),
            "disabled sources do not provide catalog samples");
    }

    private static void recoveryRemainder() {
        SimpleAssert
            .eq(0, HologramMaterials.recoveryRemainder(8, 8, 0), "full network acceptance leaves nothing to refund");
        SimpleAssert.eq(3, HologramMaterials.recoveryRemainder(8, 8, 3), "partial acceptance preserves exact leftover");
        SimpleAssert.eq(5, HologramMaterials.recoveryRemainder(8, 5, 2), "power limited offer keeps unoffered items");
        SimpleAssert.eq(8, HologramMaterials.recoveryRemainder(8, 0, 0), "no network offer retains all material");
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.recoveryDestination = 1;
        ItemStack tool = new ItemStack(Items.feather);
        HologramMaterials.write(tool, settings);
        SimpleAssert.eq(1, HologramMaterials.read(tool).recoveryDestination, "recovery target persists on tool");
    }

    private static void worldRecovery() {
        net.minecraft.entity.player.EntityPlayerMP player = materialPlayer();
        for (int i = 1; i < player.inventory.mainInventory.length; i++)
            player.inventory.mainInventory[i] = new ItemStack(net.minecraft.init.Blocks.cobblestone, 64);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = false;
        settings.containers = false;
        settings.recoveryDestination = 1;
        HologramMaterials.Context context = new HologramMaterials.Context(
            player,
            settings,
            java.util.Collections.emptyList());
        ItemStack actualDrop = new ItemStack(net.minecraft.init.Blocks.stone, 3);
        context.recoverWorld(java.util.Collections.singletonList(actualDrop));
        context.flushRecovery();
        SimpleAssert.eq(3, actualDrop.stackSize, "committed receipt is copied without changing source drop");
        SimpleAssert.eq(0, looseStone(player), "full inventory does not receive phantom items");
        SimpleAssert.eq(
            1,
            player.getEntityData()
                .getCompoundTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG)
                .getTagList("gtmbHologramMaterialRecovery", 10)
                .tagCount(),
            "full backpack persists pending world recovery");
        player.inventory.mainInventory[1] = null;
        context.flushRecovery();
        context.flushRecovery();
        SimpleAssert.eq(3, looseStone(player), "disabled ME falls back and delivers exactly once when room exists");
        SimpleAssert.eq(
            0,
            player.getEntityData()
                .getCompoundTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG)
                .getTagList("gtmbHologramMaterialRecovery", 10)
                .tagCount(),
            "completed receipt removed");
    }

    private static void draftSettings() {
        NBTTagCompound draft = new NBTTagCompound();
        draft.setInteger("x", 123);
        draft.setString("controllerType", "kept-controller");
        NBTTagCompound target = new NBTTagCompound();
        target.setString("item", "minecraft:stone");
        draft.setTag("target", target.copy());
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.main = false;
        settings.containers = false;
        settings.me = true;
        settings.priority = 2;
        settings.recoveryDestination = 1;
        HologramService.updateDraftSettings(draft, settings, true);
        SimpleAssert.that(
            !draft.getBoolean("materialMain") && !draft.getBoolean("materialContainers"),
            "disabled sources stay disabled after draft restore");
        SimpleAssert.that(
            draft.getBoolean("materialMe") && draft.getBoolean("downgrade"),
            "enabled settings reach detached draft");
        SimpleAssert.eq(1, draft.getInteger("recoveryDestination"), "recovery preference also synchronized");
        SimpleAssert.eq(123, draft.getInteger("x"), "draft controller binding untouched");
        SimpleAssert.that(target.equals(draft.getCompoundTag("target")), "existing target data unchanged");
    }

    private static void prefabSamples() {
        ItemStack source = new ItemStack(net.minecraft.init.Blocks.stone, 64);
        source.setTagCompound(new NBTTagCompound());
        source.getTagCompound()
            .setString("inventory", "never-copy-this-device-state");
        HologramPresets.Cell cell = HologramPresets.cell(source, 1, 2, 3, 4, false);
        SimpleAssert.eq(1, cell.stack().stackSize, "template stores one real item per cell");
        SimpleAssert.that(
            !cell.stack()
                .hasTagCompound(),
            "template never clones source NBT");
        try {
            java.lang.reflect.Method validate = HologramPresets.class
                .getDeclaredMethod("validateCell", HologramPresets.Cell.class);
            validate.setAccessible(true);
            validate.invoke(null, cell);
            cell.x = Integer.MIN_VALUE;
            try {
                validate.invoke(null, cell);
                throw new AssertionError("hostile coordinate should be rejected");
            } catch (java.lang.reflect.InvocationTargetException rejected) {
                SimpleAssert.that(
                    rejected.getCause() instanceof java.io.IOException,
                    "extreme coordinate rejected without overflow");
            }
            cell.x = 1;
            cell.count = 64;
            try {
                validate.invoke(null, cell);
                throw new AssertionError("bulk item count per coordinate should be rejected");
            } catch (java.lang.reflect.InvocationTargetException rejected) {
                SimpleAssert
                    .that(rejected.getCause() instanceof java.io.IOException, "one real item per coordinate enforced");
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void previewCounts() {
        ItemStack target = new ItemStack(net.minecraft.init.Blocks.stone);
        target.setTagCompound(new NBTTagCompound());
        target.getTagCompound()
            .setString("variant", "kept");
        ItemStack match = target.copy();
        match.stackSize = 12;
        ItemStack other = target.copy();
        other.stackSize = 31;
        other.getTagCompound()
            .setString("variant", "other");
        ItemStack held = target.copy();
        held.stackSize = 64;
        ItemStack[] inventory = { held, match, other };
        SimpleAssert.eq(12, HologramMaterials.mainCount(inventory, 0, target, 4096), "held and other NBT excluded");
        SimpleAssert.eq(7, HologramMaterials.mainCount(inventory, 0, target, 7), "display count bounded");
        net.minecraft.inventory.InventoryBasic controller = new net.minecraft.inventory.InventoryBasic(
            "controller",
            true,
            3);
        controller.setInventorySlotContents(0, match.copy());
        controller.setInventorySlotContents(1, other.copy());
        NBTTagCompound before = controller.getStackInSlot(0)
            .writeToNBT(new NBTTagCompound());
        SimpleAssert.eq(12, HologramMaterials.controllerCount(controller, target, 4096), "controller exact NBT count");
        SimpleAssert.eq(4, HologramMaterials.controllerCount(controller, target, 4), "controller display bounded");
        SimpleAssert.that(
            before.equals(
                controller.getStackInSlot(0)
                    .writeToNBT(new NBTTagCompound())),
            "preview never consumes controller items");
        SimpleAssert.eq(12, match.stackSize, "player source unchanged");
        ItemStack[] giantStock = new ItemStack[80];
        net.minecraft.inventory.InventoryBasic giantController = new net.minecraft.inventory.InventoryBasic(
            "giant",
            true,
            80);
        for (int slot = 0; slot < giantStock.length; slot++) {
            giantStock[slot] = target.copy();
            giantStock[slot].stackSize = 64;
            giantController.setInventorySlotContents(slot, giantStock[slot].copy());
        }
        SimpleAssert.eq(
            5120,
            HologramMaterials.mainCount(giantStock, -1, target, 6000),
            "large stock is not truncated at 4096");
        SimpleAssert.eq(
            5000,
            HologramMaterials.controllerCount(giantController, target, 5000),
            "large read-only inventory respects requested limit");
        SimpleAssert.eq(64, giantStock[0].stackSize, "large stock count does not consume items");
    }

    private static void hatchExterior() {
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++)
            for (int z = 0; z < 3; z++) cells.add(new int[] { x, y, z, x == 1 && y == 1 && z == 1 ? 0 : 1 });
        java.util.Set<String> outside = HologramHatchGeometry.exterior(cells, (x, y, z) -> true);
        SimpleAssert.that(!outside.contains("1,1,1"), "template cavity never exterior even if world is all air");
        SimpleAssert.eq(
            4,
            HologramHatchGeometry.choose(0, 1, 1, new int[] { 5, 4 }, outside),
            "choose external face instead of cavity");
        java.util.Set<String> obstructed = HologramHatchGeometry.exterior(cells, (x, y, z) -> x >= 0);
        SimpleAssert.eq(
            -1,
            HologramHatchGeometry.choose(0, 1, 1, new int[] { 5, 4 }, obstructed),
            "no legal external air does not select internal face");
        for (int[] cell : cells) if (cell[0] == 0 && cell[1] == 1 && cell[2] == 1) cell[3] = 0;
        outside = HologramHatchGeometry.exterior(cells, (x, y, z) -> true);
        SimpleAssert.that(
            !outside.contains("1,1,1") && !outside.contains("0,1,1"),
            "open template air also excluded from exterior");
        cells.add(new int[] { 1000000, 0, 0, 1 });
        SimpleAssert.that(
            HologramHatchGeometry.exterior(cells, (x, y, z) -> true)
                .isEmpty(),
            "huge sparse definitions remain bounded");
    }

    private static void hatchPreset() {
        int[] tiers = { 1, 2, 3, 4 };
        int[] stock = { 5, 2, 0, 10 };
        SimpleAssert
            .eq(2, HologramHatchPreset.choose(tiers, stock, 3, false), "strict tier retains missing requested target");
        SimpleAssert
            .eq(1, HologramHatchPreset.choose(tiers, stock, 3, true), "downgrade selects highest stocked lower tier");
        SimpleAssert
            .eq(0, HologramHatchPreset.choose(tiers, stock, 1, true), "higher stocked tiers never upgrade request");
        SimpleAssert.eq(
            2,
            HologramHatchPreset.choose(tiers, new int[] { 0, 0, 0, 0 }, 3, true),
            "depleted reservations stay missing instead of reusing stock");
        SimpleAssert.eq(
            1,
            HologramHatchPreset.choose(new int[] { 2, 2 }, new int[] { 0, 1 }, 2, false),
            "same-tier variant with stock preferred");
        SimpleAssert.eq(
            0,
            HologramHatchPreset.choose(new int[] { 1 }, new int[] { 1 }, 3, false),
            "fixed-tier maintenance valid for higher machine tier");
        SimpleAssert.eq(
            -1,
            HologramHatchPreset.choose(new int[] { 1, 3 }, new int[] { 1, 1 }, 2, false),
            "absent strict voltage tier is not silently substituted");
    }

    private static void sealedFacing() {
        NBTTagCompound tile = new NBTTagCompound();
        tile.setShort("mFacing", (short) 2);
        tile.setInteger("mID", 123);
        tile.setString("fluid", "preserved");
        tile.setTag("Inventory", new net.minecraft.nbt.NBTTagList());
        NBTTagCompound seal = new NBTTagCompound();
        seal.setTag("tile", tile.copy());
        NBTTagCompound expected = HologramRecovery.relocateNBT(seal, 10, 20, 30, 5);
        SimpleAssert.eq((short) 5, expected.getShort("mFacing"), "only the explicitly requested face changes");
        SimpleAssert.eq(
            (short) 2,
            seal.getCompoundTag("tile")
                .getShort("mFacing"),
            "sealed source facing unchanged");
        SimpleAssert.eq("preserved", expected.getString("fluid"), "facing keeps stored fluid");
        SimpleAssert.that(
            expected.getTag("Inventory")
                .equals(tile.getTag("Inventory")),
            "facing keeps stored inventory");
        SimpleAssert.eq(
            (short) 2,
            HologramRecovery.relocateNBT(seal, 10, 20, 30, -1)
                .getShort("mFacing"),
            "default retains sealed direction");
    }

    private static void materialSettings() {
        ItemStack tool = new ItemStack(Items.feather);
        HologramMaterials.Settings defaults = HologramMaterials.read(tool);
        SimpleAssert.that(defaults.main && defaults.containers && defaults.me, "all sources default to enabled");
        defaults.main = false;
        defaults.containers = true;
        defaults.me = false;
        defaults.priority = 2;
        tool.setTagCompound(new NBTTagCompound());
        tool.getTagCompound()
            .setString("existing_tool_data", "preserved");
        HologramMaterials.write(tool, defaults);
        ItemStack restored = ItemStack.loadItemStackFromNBT(tool.writeToNBT(new NBTTagCompound()));
        HologramMaterials.Settings read = HologramMaterials.read(restored);
        SimpleAssert.that(
            !read.main && read.containers && !read.me && read.priority == 2,
            "source settings survive actual item serialization");
        SimpleAssert.eq(
            "preserved",
            restored.getTagCompound()
                .getString("existing_tool_data"),
            "source settings preserve other tool NBT");
    }

    private static void materialSimulation() {
        net.minecraft.entity.player.EntityPlayerMP player = materialPlayer();
        HologramMaterials.Context context = materialContext(player);
        ItemStack stone = new ItemStack(net.minecraft.init.Blocks.stone, 1);
        SimpleAssert.eq(10, context.count(stone, 4096), "registered container adapter contributes exact availability");
        SimpleAssert.eq(
            10,
            player.inventory.mainInventory[1].getTagCompound()
                .getInteger("materials"),
            "even a mutating simulator only receives a bag copy");
        HologramMaterials.Transaction transaction = context.begin();
        SimpleAssert.that(transaction.takeOne(stone, false), "real material is taken through adapter");
        transaction.commit();
        transaction.rollbackExternal();
        SimpleAssert.eq(
            9,
            player.inventory.mainInventory[1].getTagCompound()
                .getInteger("materials"),
            "committed deduction is not refunded");
        SimpleAssert.eq(0, looseStone(player), "commit never creates an extra loose material");
        boolean rejected = false;
        try {
            context.takeOne(new ItemStack(net.minecraft.init.Blocks.stone, 64), true);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "native takeOne contract rejects a multi-item request");
        player.inventory.mainInventory[1].getTagCompound()
            .setInteger("materials", 8192);
        SimpleAssert.eq(8192, context.count(stone, 10000), "giant structure counts full container stock");
        SimpleAssert.eq(
            8192,
            player.inventory.mainInventory[1].getTagCompound()
                .getInteger("materials"),
            "large simulation preserves container");
        SimpleAssert.that(
            context.take(stack -> true, false, 5000)
                .isEmpty(),
            "large counting does not enlarge committed extraction budget");
    }

    private static void materialRollback() {
        net.minecraft.entity.player.EntityPlayerMP player = materialPlayer();
        HologramMaterials.Context context = materialContext(player);
        ItemStack[] before = inventoryCopy(player);
        HologramMaterials.Transaction transaction = context.begin();
        SimpleAssert.that(
            transaction.takeOne(new ItemStack(net.minecraft.init.Blocks.stone, 1), false),
            "external material is deducted");
        transaction.rollbackExternal();
        player.inventory.mainInventory = before;
        context.afterInventoryRestore();
        SimpleAssert.eq(
            9,
            player.inventory.mainInventory[1].getTagCompound()
                .getInteger("materials"),
            "rollback preserves real post-extraction bag state");
        SimpleAssert.eq(1, looseStone(player), "deducted item returns to player exactly once");
        transaction.rollbackExternal();
        context.flushRecovery();
        SimpleAssert.eq(1, looseStone(player), "repeated rollback cannot duplicate its ledger");
        SimpleAssert.eq(
            9,
            context.count(new ItemStack(net.minecraft.init.Blocks.stone, 1), 4096),
            "source references follow restored inventory objects");
    }

    private static void materialException() {
        net.minecraft.entity.player.EntityPlayerMP player = materialPlayer();
        player.inventory.mainInventory[1].getTagCompound()
            .setBoolean("throwAfterReport", true);
        HologramMaterials.Context context = materialContext(player);
        ItemStack[] before = inventoryCopy(player);
        HologramMaterials.Transaction transaction = context.begin();
        boolean failed = false;
        try {
            transaction.takeOne(new ItemStack(net.minecraft.init.Blocks.stone, 1), false);
        } catch (IllegalStateException expected) {
            failed = true;
        }
        SimpleAssert.that(failed, "adapter exception reaches caller for world rollback");
        transaction.rollbackExternal();
        player.inventory.mainInventory = before;
        context.afterInventoryRestore();
        SimpleAssert.eq(
            9,
            player.inventory.mainInventory[1].getTagCompound()
                .getInteger("materials"),
            "reported deduction remains removed from bag after exception");
        SimpleAssert.eq(1, looseStone(player), "reported material survives exceptional extraction");
    }

    private static void refundOverflow() {
        ItemStack[] inventory = { new ItemStack(Items.feather, 1), new ItemStack(net.minecraft.init.Blocks.stone, 63),
            new ItemStack(Items.book, 64) };
        ItemStack refund = new ItemStack(net.minecraft.init.Blocks.stone, 3);
        HologramMaterials.insertRecovery(inventory, 0, 64, refund);
        SimpleAssert.eq(64, inventory[1].stackSize, "refund fills available stack space");
        SimpleAssert.eq(2, refund.stackSize, "full inventory retains uninserted refund instead of deleting it");
        SimpleAssert.eq(1, inventory[0].stackSize, "held tool remains untouched");
        inventory[2] = null;
        HologramMaterials.insertRecovery(inventory, 0, 64, refund);
        SimpleAssert.eq(0, refund.stackSize, "remaining refund can be retried after space opens");
        SimpleAssert.eq(2, inventory[2].stackSize, "retry inserts only the remaining real materials");
    }

    private static void persistedRefund() {
        net.minecraft.entity.player.EntityPlayerMP player = materialPlayer();
        for (int i = 2; i < player.inventory.mainInventory.length; i++)
            player.inventory.mainInventory[i] = new ItemStack(Items.feather, 64);
        HologramMaterials.Context context = materialContext(player);
        ItemStack[] before = inventoryCopy(player);
        HologramMaterials.Transaction transaction = context.begin();
        SimpleAssert.that(
            transaction.takeOne(new ItemStack(net.minecraft.init.Blocks.stone, 1), false),
            "full main inventory still draws from enabled bag");
        transaction.rollbackExternal();
        player.inventory.mainInventory = before;
        context.afterInventoryRestore();
        SimpleAssert.eq(
            9,
            player.inventory.mainInventory[1].getTagCompound()
                .getInteger("materials"),
            "full inventory does not restore deducted bag contents");
        SimpleAssert.eq(0, looseStone(player), "no space means no invented inventory slot");
        NBTTagCompound persisted = player.getEntityData()
            .getCompoundTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG);
        SimpleAssert.eq(
            1,
            persisted.getTagList("gtmbHologramMaterialRecovery", 10)
                .tagCount(),
            "real refund is stored in Forge player-persisted NBT");
        net.minecraft.nbt.NBTTagList legacyRefund = new net.minecraft.nbt.NBTTagList();
        legacyRefund.appendTag(HologramMaterials.itemReceipt(new ItemStack(net.minecraft.init.Blocks.dirt, 3)));
        persisted.setTag("gtitHologramMaterialRecovery", legacyRefund);
        net.minecraft.entity.player.EntityPlayerMP restored = materialPlayer();
        restored.getEntityData()
            .setTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG, persisted.copy());
        HologramMaterials.Context next = materialContext(restored);
        next.flushRecovery();
        next.flushRecovery();
        SimpleAssert
            .eq(1, looseStone(restored), "reloaded persistent refund is delivered once when space is available");
        SimpleAssert.eq(
            0,
            restored.getEntityData()
                .getCompoundTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG)
                .getTagList("gtmbHologramMaterialRecovery", 10)
                .tagCount(),
            "delivered refund is removed from persistent ledger");
        SimpleAssert.eq(
            legacyRefund,
            restored.getEntityData()
                .getCompoundTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG)
                .getTagList("gtitHologramMaterialRecovery", 10),
            "GTMB never consumes or modifies the co-installed GTIT refund queue");
    }

    private static net.minecraft.entity.player.EntityPlayerMP materialPlayer() {
        try {
            java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) ((sun.misc.Unsafe) field
                .get(null)).allocateInstance(net.minecraft.entity.player.EntityPlayerMP.class);
            player.inventory = new net.minecraft.entity.player.InventoryPlayer(player);
            player.inventoryContainer = new net.minecraft.inventory.Container() {

                @Override
                public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer ignored) {
                    return true;
                }
            };
            player.inventory.mainInventory[0] = new ItemStack(Items.feather);
            ItemStack bag = new ItemStack(Items.writable_book);
            bag.setTagCompound(new NBTTagCompound());
            bag.getTagCompound()
                .setInteger("materials", 10);
            player.inventory.mainInventory[1] = bag;
            return player;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static HologramMaterials.Context materialContext(net.minecraft.entity.player.EntityPlayerMP player) {
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.main = false;
        settings.me = false;
        com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackExtractor adapter = new com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackExtractor() {

            @Override
            public boolean isAPIImplemented(APIType type) {
                return type == APIType.MAIN || type == APIType.IS_VALID_SOURCE;
            }

            @Override
            public boolean isValidSource(ItemStack stack, net.minecraft.entity.player.EntityPlayerMP ignored) {
                return stack.hasTagCompound() && stack.getTagCompound()
                    .hasKey("materials");
            }

            @Override
            public int takeFromStack(java.util.function.Predicate<ItemStack> predicate, boolean simulate, int count,
                com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackCounter counter, ItemStack source,
                ItemStack exact, net.minecraft.entity.player.EntityPlayerMP ignored) {
                ItemStack stone = new ItemStack(net.minecraft.init.Blocks.stone, 1);
                if (!predicate.test(stone)) return 0;
                int available = source.getTagCompound()
                    .getInteger("materials");
                int taken = Math.min(count, available);
                source.getTagCompound()
                    .setInteger("materials", available - taken);
                counter.add(stone, taken);
                if (!simulate && source.getTagCompound()
                    .getBoolean("throwAfterReport"))
                    throw new IllegalStateException("test reported extraction failure");
                return taken;
            }
        };
        return new HologramMaterials.Context(player, settings, java.util.Collections.singletonList(adapter));
    }

    private static ItemStack[] inventoryCopy(net.minecraft.entity.player.EntityPlayerMP player) {
        ItemStack[] copy = new ItemStack[player.inventory.mainInventory.length];
        for (int i = 0; i < copy.length; i++)
            if (player.inventory.mainInventory[i] != null) copy[i] = player.inventory.mainInventory[i].copy();
        return copy;
    }

    private static int looseStone(net.minecraft.entity.player.EntityPlayerMP player) {
        int count = 0;
        for (ItemStack stack : player.inventory.mainInventory) if (stack != null
            && stack.getItem() == net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.stone))
            count += stack.stackSize;
        return count;
    }

    private static void frames() {
        try {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            // Avoid a global GameRegistry mutation: these tested methods use no instance fields.
            gregtech.common.blocks.BlockFrameBox frame = (gregtech.common.blocks.BlockFrameBox) unsafe
                .allocateInstance(gregtech.common.blocks.BlockFrameBox.class);
            com.gtnewhorizon.structurelib.structure.IStructureElement<Object> tiered = com.gtnewhorizon.structurelib.structure.StructureUtility
                .<Object, Integer>ofBlocksTiered(
                    (block, meta) -> meta,
                    java.util.Arrays.asList(
                        org.apache.commons.lang3.tuple.Pair.of(frame, 17),
                        org.apache.commons.lang3.tuple.Pair.of(frame, 18)),
                    -1,
                    (context, tier) -> {},
                    context -> -1);
            HologramReplacementFamily.Family family = HologramReplacementFamily.resolve(tiered, frame, 17);
            SimpleAssert.that(
                family != null && family.id.startsWith("frame:"),
                "real steam-style tiered factory resolves frame family above metadata fifteen");
            SimpleAssert.that(family.contains(frame, 17), "first declared frame material is accepted");
            SimpleAssert.that(family.contains(frame, 18), "second declared frame material is accepted");
            SimpleAssert.that(!family.contains(frame, 19), "unlisted frame material is protected");
            SimpleAssert.that(!family.contains(frame, 17 | 0x1000), "tiered TE frame is protected");
            SimpleAssert.that(!frame.hasTileEntity(0xFFF), "ordinary frame maximum metadata has no TE");
            SimpleAssert.that(frame.hasTileEntity(0x1000), "real GT frame TE boundary uses MTE bit");
            SimpleAssert
                .that(!family.contains(net.minecraft.init.Blocks.stone, 17), "another block never joins frame family");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void channels() {
        ItemStack trigger = new ItemStack(Items.feather, 123);
        SimpleAssert.eq(123, ChannelDataAccessor.getChannelData(trigger, "custom.future_mod"), "未设置通道回退主值");
        ChannelDataAccessor.setChannelData(trigger, "custom.future_mod", 2048);
        SimpleAssert.eq(2048, ChannelDataAccessor.withChannel(trigger, "custom.future_mod").stackSize, "原版子通道构造器看到完整值");
        SimpleAssert.eq(123, trigger.stackSize, "子通道不污染主值");
        ChannelDataAccessor.unsetChannelData(trigger, "custom.future_mod");
        SimpleAssert.eq(123, ChannelDataAccessor.getChannelData(trigger, "custom.future_mod"), "删除通道重新回退主值");
    }

    private static void openChannels() {
        ItemStack trigger = new ItemStack(Items.feather, 123);
        HologramChannelTrace.Scope scope = HologramChannelTrace.begin(null);
        HologramChannelTrace.record(trigger, "custom.future_mod", 123, 1);
        HologramCapabilities.Result capabilities = HologramCapabilities
            .describe(null, trigger, HologramChannelTrace.end(scope));
        NBTTagCompound channels = new NBTTagCompound();
        channels.setInteger("custom.future_mod", 2048);
        channels.setInteger("new_channel", Integer.MAX_VALUE);
        SimpleAssert.that(capabilities.validConfiguration(123, channels), "未知及新建信道允许完整正整数值");
        NBTTagCompound active = capabilities.sanitizeChannels(channels);
        SimpleAssert.eq(2048, active.getInteger("custom.future_mod"), "未适配的已观测信道不被过滤");
        SimpleAssert.eq(Integer.MAX_VALUE, active.getInteger("new_channel"), "新建信道不被静默过滤或截断");
        for (Object id : active.func_150296_c())
            ChannelDataAccessor.setChannelData(trigger, (String) id, active.getInteger((String) id));
        SimpleAssert.eq(2048, ChannelDataAccessor.withChannel(trigger, "custom.future_mod").stackSize, "实际原生构造信号保留手动值");
        SimpleAssert.that(capabilities.encodeOption(channels, "custom.future_mod", 4096), "观测信道可设原始值");
        SimpleAssert.that(capabilities.encodeOption(channels, "custom.future_mod", 0), "删除观测信道恢复继承");
        SimpleAssert.that(!channels.hasKey("custom.future_mod"), "继承不保留显式零值");
        for (int value : new int[] { 0, -1 }) {
            NBTTagCompound invalid = new NBTTagCompound();
            invalid.setInteger("custom.future_mod", value);
            SimpleAssert.that(!capabilities.validConfiguration(1, invalid), "非正整数配置被拒绝");
        }
        NBTTagCompound invalid = new NBTTagCompound();
        invalid.setString("custom.future_mod", "2048");
        SimpleAssert.that(!capabilities.validConfiguration(1, invalid), "字符串不能冒充整数");
        invalid = new NBTTagCompound();
        invalid.setInteger("Bad Name", 1);
        SimpleAssert.that(!capabilities.validConfiguration(1, invalid), "无效名称不进入原生API");
        invalid = new NBTTagCompound();
        for (int i = 0; i < 65; i++) invalid.setInteger("channel_" + i, 1);
        SimpleAssert.that(!capabilities.validConfiguration(1, invalid), "超出64个信道不接受部分配置");
    }

    private static void sealedRecovery() {
        NBTTagCompound tile = new NBTTagCompound();
        tile.setString("id", "GT_TileEntityMetaID_Machine");
        tile.setInteger("mID", 25);
        tile.setInteger("x", 100);
        tile.setInteger("y", 40);
        tile.setInteger("z", -100);
        net.minecraft.nbt.NBTTagList inventory = new net.minecraft.nbt.NBTTagList();
        NBTTagCompound contents = new ItemStack(Items.feather, 1).writeToNBT(new NBTTagCompound());
        contents.setInteger("IntSlot", 3);
        contents.setInteger("Count", 512);
        inventory.appendTag(contents);
        tile.setTag("Inventory", inventory);
        NBTTagCompound fluid = new NBTTagCompound();
        fluid.setString("FluidName", "water");
        fluid.setInteger("Amount", 32000);
        tile.setTag("mFluid", fluid);
        NBTTagCompound cover = new NBTTagCompound();
        cover.setInteger("coverID", 120);
        cover.setInteger("coverData", 987);
        tile.setTag("cover", cover);
        NBTTagCompound sealed = HologramRecovery.sealNBT(tile, "gregtech:machine", 0, "gregtech:machine", 25, 25);
        // The live tile and item payload must never share mutable inventory/tank compounds.
        contents.setInteger("Count", 1);
        fluid.setInteger("Amount", 0);
        SimpleAssert.that(
            HologramRecovery.matchesEnvelope(sealed, "gregtech:machine", "gregtech:machine", 25),
            "匹配原设备的完整封存载荷有效");
        NBTTagCompound relocated = HologramRecovery.relocateNBT(sealed, -1, 65, 2);
        SimpleAssert.eq(-1, relocated.getInteger("x"), "放回新位置不能沿用旧坐标");
        SimpleAssert.eq(65, relocated.getInteger("y"), "放回新高度");
        SimpleAssert.eq(2, relocated.getInteger("z"), "放回新Z");
        SimpleAssert.eq(
            512,
            relocated.getTagList("Inventory", 10)
                .getCompoundTagAt(0)
                .getInteger("Count"),
            "大数量库存未被字节截断");
        SimpleAssert.eq(
            32000,
            relocated.getCompoundTag("mFluid")
                .getInteger("Amount"),
            "流体量独立封存");
        SimpleAssert.eq(
            987,
            relocated.getCompoundTag("cover")
                .getInteger("coverData"),
            "覆盖板配置独立封存");
        relocated.getCompoundTag("mFluid")
            .setInteger("Amount", 1);
        SimpleAssert.eq(
            32000,
            sealed.getCompoundTag("tile")
                .getCompoundTag("mFluid")
                .getInteger("Amount"),
            "恢复不能修改库存中物品载荷");
        SimpleAssert.eq(
            100,
            sealed.getCompoundTag("tile")
                .getInteger("x"),
            "原封存坐标只读");
        SimpleAssert
            .that(!HologramRecovery.matchesEnvelope(sealed, "gregtech:frame", "gregtech:machine", 25), "不将机器封存载荷放到框架");
        SimpleAssert
            .that(!HologramRecovery.matchesEnvelope(sealed, "gregtech:machine", "gregtech:machine", 26), "设备ID不同不恢复载荷");
        NBTTagCompound corrupt = (NBTTagCompound) sealed.copy();
        corrupt.getCompoundTag("tile")
            .setInteger("mID", 26);
        SimpleAssert.that(
            !HologramRecovery.matchesEnvelope(corrupt, "gregtech:machine", "gregtech:machine", 25),
            "封存头与实体ID不符拒绝");
        corrupt = (NBTTagCompound) sealed.copy();
        corrupt.getCompoundTag("tile")
            .removeTag("Inventory");
        SimpleAssert.that(
            !HologramRecovery.matchesEnvelope(corrupt, "gregtech:machine", "gregtech:machine", 25),
            "缺库存载荷不作为空库存恢复");
    }

    private static void packetRoundTrip() {
        NBTTagCompound action = new NBTTagCompound();
        action.setInteger("main", 123);
        NBTTagCompound channels = new NBTTagCompound();
        channels.setInteger("custom.future_mod", 2048);
        action.setTag("channels", channels);
        HologramNetwork.Action packet = new HologramNetwork.Action(action);
        channels.setInteger("custom.future_mod", 2);
        action.setInteger("main", 1);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes);
            HologramNetwork.Action decoded = new HologramNetwork.Action();
            decoded.fromBytes(bytes);
            SimpleAssert.eq(123, decoded.tag.getInteger("main"), "发送前快照隔离主值编辑");
            SimpleAssert.eq(
                2048,
                decoded.tag.getCompoundTag("channels")
                    .getInteger("custom.future_mod"),
                "未知通道经过真压缩NBT传输");
            SimpleAssert.eq(0, bytes.readableBytes(), "载荷完整读完");
        } finally {
            bytes.release();
        }
    }

    private static void badLengths() {
        for (int length : new int[] { -1, 1048577, 4 }) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                bytes.writeInt(length);
                boolean rejected = false;
                try {
                    new HologramNetwork.Action().fromBytes(bytes);
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                SimpleAssert.that(rejected, "恶意/截断长度被拒绝 " + length);
            } finally {
                bytes.release();
            }
        }
    }

    private static void planProtocol() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("generation", "server-generation");
        state.setLong("planRevision", 9007199254740993L);
        state.setLong("uiSequence", 4294967297L);
        state.setString("phase", "COMPLETE");
        state.setIntArray("ackIndices", new int[] { 2, 8, 35 });
        state.setIntArray("ackSuccesses", new int[] { 1, 0, 1 });
        state.setInteger("job", 5);
        HologramNetwork.State packet = new HologramNetwork.State(state);
        state.setLong("planRevision", 1);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes);
            HologramNetwork.State decoded = new HologramNetwork.State();
            decoded.fromBytes(bytes);
            SimpleAssert.that(
                decoded.tag.getLong("planRevision") == 9007199254740993L,
                "plan revision retains full long precision and sender snapshot");
            SimpleAssert
                .that(decoded.tag.getLong("uiSequence") == 4294967297L, "configuration receipt retains long sequence");
            SimpleAssert.that("COMPLETE".equals(decoded.tag.getString("phase")), "批量完成回执无需预告或动画状态");
            SimpleAssert.eq(3, decoded.tag.getIntArray("ackIndices").length, "ACK retains all same-tick cells");
            SimpleAssert.eq(0, decoded.tag.getIntArray("ackSuccesses")[1], "failed cell is not animated as success");
            SimpleAssert.eq(5, decoded.tag.getInteger("job"), "partial completion remains distinct from complete");
        } finally {
            bytes.release();
        }
    }

    private static void inertCapture() {
        SimpleAssert.that(
            !HologramCapture.piece(new Object(), "main", new ItemStack(Items.feather), true, 1, 1, 0),
            "普通construct没有被截获");
        SimpleAssert.that(!HologramCapture.hint(null, 0, 0, 0, net.minecraft.init.Blocks.stone, 0), "普通方块提示没有被吞");
        SimpleAssert.that(!HologramCapture.iconHint(), "普通图标提示没有被吞");
    }
}
