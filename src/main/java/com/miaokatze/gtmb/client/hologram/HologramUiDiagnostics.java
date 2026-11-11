// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** Passive optional diagnostics seam. Normal workstation construction never loads test implementation classes. */
class HologramUiDiagnostics {

    static HologramUiDiagnostics create(HologramUiModules ui) {
        try {
            Class<?> type = Class.forName("com.miaokatze.gtmb.client.hologram.HologramTestLabUiDiagnostics");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(HologramUiModules.class);
            constructor.setAccessible(true);
            return (HologramUiDiagnostics) constructor.newInstance(ui);
        } catch (ClassNotFoundException absent) {
            return new HologramUiDiagnostics();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Optional diagnostics initialization failed", failure);
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("This operation requires the complete GTMB testlab variant");
    }

    net.minecraft.nbt.NBTTagList smokeAeWarningMarkers() {
        throw unavailable();
    }

    NBTTagCompound smokeAeUiState() {
        throw unavailable();
    }

    String smokeAeReviewGui() {
        throw unavailable();
    }

    NBTTagCompound smokeLibraryUiState() {
        throw unavailable();
    }

    int[] smokeControlPoint(int id) {
        throw unavailable();
    }

    void smokeClickControl(int id) {
        throw unavailable();
    }

    void smokeClickAt(int x, int y, int button) {
        throw unavailable();
    }

    NBTTagCompound smokeAreaImportUiState() {
        throw unavailable();
    }

    int[] smokeViewportBounds() {
        throw unavailable();
    }

    int[] smokePanelBounds() {
        throw unavailable();
    }

    boolean smokeKey(int key, int modifiers) {
        throw unavailable();
    }

    String smokeBinding(int index) {
        throw unavailable();
    }

    NBTTagCompound smokeSnapshot() {
        throw unavailable();
    }

    int[] smokeSelectedIndices() {
        throw unavailable();
    }

    int[] smokeCapturePreviewSelection(int logicalX, int logicalY, boolean sameType, boolean additive) {
        throw unavailable();
    }

    boolean smokeHasControl(int id) {
        throw unavailable();
    }

    boolean drawnControlEnabled(int id) {
        throw unavailable();
    }

    long smokeRenderGeneration() {
        throw unavailable();
    }

    static void requireInteractionSmoke() {
        throw unavailable();
    }

    boolean smokePending() {
        throw unavailable();
    }

    NBTTagCompound smokeCaptureMissingBuildClick() {
        throw unavailable();
    }

    void smokeRenderScenario(String scenario) {
        throw unavailable();
    }

    String smokeCaptureLocalSelection() {
        throw unavailable();
    }

    String smokeCaptureV004Editor() {
        throw unavailable();
    }

    String smokeCaptureV004Tabs() {
        throw unavailable();
    }

    void smokeAssertRootClosed() {
        throw unavailable();
    }

    String smokePlacementReviewContext() {
        throw unavailable();
    }

    void smokeAssertPlacementConfirmationDispatch() {
        throw unavailable();
    }

    String smokeConstructionScopeContext() {
        throw unavailable();
    }

    int[] smokePreviewPoint(int x, int y) {
        throw unavailable();
    }

    NBTTagCompound smokeCaptureAxisDrag(boolean ctrlAtStart, boolean ctrlAtRelease) {
        throw unavailable();
    }

    String smokeProcessingFlowContext() {
        throw unavailable();
    }

    static String smokeLibraryNavigationFences() {
        return (String) com.miaokatze.gtmb.main.OptionalTestLab
            .call("com.miaokatze.gtmb.client.hologram.HologramTestLabUiDiagnostics", "smokeLibraryNavigationFences");
    }

    String smokeCaptureLibraryWorkflow() {
        throw unavailable();
    }

    void smokeClickDrawnControl(int id) {
        throw unavailable();
    }

    String smokeRenderGizmoHover(String scenario) {
        throw unavailable();
    }

    String smokeLayout() {
        throw unavailable();
    }

    boolean captureRenderSmokeAction() {
        return false;
    }

    NBTTagCompound capturedRenderSmokeAction(NBTTagCompound value) {
        return value;
    }

    boolean smokeAltSelection() {
        return false;
    }

    boolean smokeCtrlSelection() {
        return false;
    }

    boolean smokeSameTypeSelection() {
        return false;
    }
}
