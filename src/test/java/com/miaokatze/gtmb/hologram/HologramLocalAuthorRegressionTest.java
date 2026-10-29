package com.miaokatze.gtmb.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.TestRunner;

/** Independent JVM: local native fixtures must not disturb the server suite's process-wide lease baselines. */
public final class HologramLocalAuthorRegressionTest {

    private HologramLocalAuthorRegressionTest() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLocalAuthorRegressionTest.class, args)) return;
        HologramResourceWorld.init();
        Map<String, Runnable> cases = new LinkedHashMap<>();
        add(
            cases,
            "source_revision_cow_and_provenance",
            com.miaokatze.gtmb.blueprint.editor.AuthorDocumentRegression::run);
        add(cases, "selected_native_nbt_and_legacy_migration", HologramLocalAuthorSemanticsRegression::run);
        add(
            cases,
            "persistent_render_directories",
            com.miaokatze.gtmb.client.hologram.HologramDocumentIndexRegression::run);
        add(
            cases,
            "incremental_projection_and_lazy_payload",
            com.miaokatze.gtmb.client.hologram.HologramAuthorProjectionRegression::run);
        add(
            cases,
            "draft_persistence_and_recovery",
            com.miaokatze.gtmb.client.hologram.HologramLocalAuthorPersistenceRegression::run);
        add(
            cases,
            "author_save_name_preservation",
            com.miaokatze.gtmb.client.hologram.HologramAuthorSaveNameRegression::run);
        add(
            cases,
            "author_model_deadline_scheduling",
            com.miaokatze.gtmb.client.hologram.HologramAuthorProjectionSchedulerRegression::run);
        add(
            cases,
            "author_preview_native_owner_and_regions",
            com.miaokatze.gtmb.client.hologram.HologramAuthorPreviewOwnershipRegression::run);
        add(
            cases,
            "author_whole_source_region_fairness",
            com.miaokatze.gtmb.client.hologram.HologramAuthorRegionFairnessRegression::run);
        add(
            cases,
            "author_typed_dependency_invalidation",
            com.miaokatze.gtmb.client.hologram.HologramTypedDependencyInvalidationRegression::run);
        add(
            cases,
            "author_plain_block_descriptor_budget",
            com.miaokatze.gtmb.client.hologram.HologramAuthorPlainBlockRegression::run);
        add(
            cases,
            "author_owned_row_isolation",
            com.miaokatze.gtmb.client.hologram.HologramAuthorOwnedRowRegression::run);
        add(
            cases,
            "author_final_owned_payload_accounting",
            com.miaokatze.gtmb.client.hologram.HologramAuthorOwnershipBudgetRegression::run);
        add(
            cases,
            "author_saturated_neighbor_demand",
            com.miaokatze.gtmb.client.hologram.HologramAuthorModelDemandRegression::run);
        add(
            cases,
            "bounded_material_dedup_cache",
            com.miaokatze.gtmb.client.hologram.HologramMaterialCacheRegression::run);
        add(
            cases,
            "author_hidden_budget_recovery",
            com.miaokatze.gtmb.client.hologram.HologramAuthorBudgetRecoveryRegression::run);
        add(
            cases,
            "detail_region_publication",
            com.miaokatze.gtmb.client.hologram.HologramDetailPublicationRegression::run);
        add(
            cases,
            "author_publication_cost_and_native_retention",
            com.miaokatze.gtmb.client.hologram.HologramAuthorPublicationCostRegression::run);
        add(
            cases,
            "visible_detail_work_and_camera_slices",
            com.miaokatze.gtmb.client.hologram.HologramVisibleDetailWorkRegression::run);
        add(
            cases,
            "native_vertex_inspection_equivalence",
            com.miaokatze.gtmb.client.hologram.HologramNativeGeometryInspectionRegression::run);
        add(
            cases,
            "conservative_cheap_draw_occlusion",
            com.miaokatze.gtmb.client.hologram.HologramCheapOcclusionRegression::run);
        add(
            cases,
            "material_hint_tint_key_identity",
            com.miaokatze.gtmb.client.hologram.HologramMaterialTintKeyRegression::run);
        add(
            cases,
            "bounded_native_dependency_preparation",
            com.miaokatze.gtmb.client.hologram.HologramNativePreparationRegression::run);
        add(
            cases,
            "capture_preparation_request_lifetime",
            com.miaokatze.gtmb.client.hologram.HologramCapturePreparationRegression::run);
        add(cases, "atomic_native_attachments", HologramLocalAuthorAttachmentsRegression::run);
        add(cases, "native_configuration_contract", HologramLocalAuthorConfigurationsRegression::run);
        add(cases, "native_structure_regeneration", HologramLocalAuthorStructuresRegression::run);
        add(cases, "legacy_extension_owner_boundary", HologramExtensionExecutionRegression::run);
        add(cases, "native_structure_and_configuration_server_review", HologramAuthorNativeReviewRegression::run);
        add(
            cases,
            "server_review_client_correlation",
            com.miaokatze.gtmb.client.hologram.HologramAuthorReviewFlowRegression::run);
        add(cases, "explicit_portable_candidate", HologramAuthorPortableCompilerRegression::run);
        add(
            cases,
            "candidate_pair_persistence",
            com.miaokatze.gtmb.client.hologram.HologramAuthorCandidatePersistenceRegression::run);
        add(cases, "real_material_sample_privacy_and_settings", HologramAuthorMaterialCatalogRegression::run);
        add(
            cases,
            "material_sample_client_correlation",
            com.miaokatze.gtmb.client.hologram.HologramLocalAuthorMaterialCatalogRegression::run);
        TestRunner.run(HologramLocalAuthorRegressionTest.class, cases);
    }

    private interface Case {

        void run() throws Exception;
    }

    private static void add(Map<String, Runnable> cases, String name, Case test) {
        cases.put(name, () -> {
            try {
                test.run();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
    }
}
