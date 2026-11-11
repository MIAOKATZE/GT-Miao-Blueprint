// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import java.util.List;

import com.miaokatze.miaokatzecore.api.testlab.LabCase;

/** Discovery for preserved private/isolated launch drivers; never advertised as ordinary dimension runs. */
final class LegacyProbeCatalog {

    private LegacyProbeCatalog() {}

    static void addTo(List<LabCase> cases) {
        cases.add(
            new LabCase(
                "legacy-HologramAeActualInputProbe",
                "HologramAeActualInputProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramAeActualInputProbe")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramAeInteractionClientSmoke",
                "HologramAeInteractionClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramAeInteractionClientSmoke",
                        "jvm:gtmb.hologram.aeRealInteractionSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramAeNativePreviewSmoke",
                "HologramAeNativePreviewSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramAeNativePreviewSmoke",
                        "jvm:gtmb.hologram.extendedCompatibilitySmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramAreaSelectionClientSmoke",
                "HologramAreaSelectionClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramAreaSelectionClientSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramBackgroundNativeProbe",
                "HologramBackgroundNativeProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramBackgroundNativeProbe",
                        "jvm:gtmb.hologram.backgroundProbe",
                        "jvm:gtmb.hologram.backgroundProbeDir",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramClientSmoke",
                "HologramClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramClientSmoke",
                        "jvm:gtmb.hologram.aeClientSmoke",
                        "jvm:gtmb.hologram.clientSmoke",
                        "jvm:gtmb.hologram.clientState",
                        "jvm:gtmb.hologram.extendedCompatibilitySmoke",
                        "jvm:gtmb.hologram.localMaterialVisualSmoke",
                        "jvm:gtmb.hologram.materialVisualSmoke",
                        "jvm:gtmb.hologram.nativeStartupSeconds",
                        "jvm:gtmb.hologram.referenceClient",
                        "jvm:gtmb.hologram.v010GuiSmoke",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramCutNativeClientProbe",
                "HologramCutNativeClientProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramCutNativeClientProbe",
                        "jvm:gtmb.hologram.cutNativeAddress",
                        "jvm:gtmb.hologram.cutNativeClient",
                        "jvm:gtmb.hologram.nativeStartupSeconds",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramDecorationPreviewSmoke",
                "HologramDecorationPreviewSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramDecorationPreviewSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramEnderIoPreviewSmoke",
                "HologramEnderIoPreviewSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramEnderIoPreviewSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramFloodLightsPreviewSmoke",
                "HologramFloodLightsPreviewSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramFloodLightsPreviewSmoke",
                        "jvm:gtmb.hologram.extendedCompatibilitySmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramGuidanceNativeClientProbe",
                "HologramGuidanceNativeClientProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramGuidanceNativeClientProbe",
                        "jvm:gtmb.hologram.guidanceNativeAddress",
                        "jvm:gtmb.hologram.guidanceNativeClient",
                        "jvm:gtmb.hologram.nativeStartupSeconds",
                        "jvm:gtmb.hologram.realInteractionSmoke",
                        "jvm:gtmb.hologram.sulfurLineClient",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramInteractionClientSmoke",
                "HologramInteractionClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramInteractionClientSmoke",
                        "jvm:gtmb.hologram.aeRealInteractionSmoke",
                        "jvm:gtmb.hologram.interactionAddress",
                        "jvm:gtmb.hologram.interactionReceiptDir",
                        "jvm:gtmb.hologram.librarySocketSmoke",
                        "jvm:gtmb.hologram.nativeStartupSeconds",
                        "jvm:gtmb.hologram.realInteractionSmoke",
                        "jvm:gtmb.hologram.v010MovingWheelSmoke",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramInteractionReceiptSmoke",
                "HologramInteractionReceiptSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramInteractionReceiptSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramLibrarySocketClientProbe",
                "HologramLibrarySocketClientProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramLibrarySocketClientProbe",
                        "jvm:gtmb.hologram.libraryReceiptDir",
                        "jvm:gtmb.hologram.librarySocketSmoke",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramLibraryUiSocketSmoke",
                "HologramLibraryUiSocketSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramLibraryUiSocketSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramLockedNudgeClientSmoke",
                "HologramLockedNudgeClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramLockedNudgeClientSmoke",
                        "jvm:gtmb.hologram.interactionReceiptDir")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramMaterialConfirmationClientProbe",
                "HologramMaterialConfirmationClientProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationClientProbe",
                        "jvm:gtmb.hologram.materialVisualSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramMaterialVisualSmoke",
                "HologramMaterialVisualSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramMaterialVisualSmoke",
                        "jvm:gtmb.hologram.materialVisualScale",
                        "jvm:gtmb.hologram.materialVisualSmoke",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramMuiProbeInput",
                "HologramMuiProbeInput",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramMuiProbeInput")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramNativeCompatibilitySmoke",
                "HologramNativeCompatibilitySmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramNativeCompatibilitySmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramNativeRasterSmoke",
                "HologramNativeRasterSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramNativeRasterSmoke",
                        "jvm:gtmb.hologram.extendedCompatibilitySmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramNetworkBindingUiSmoke",
                "HologramNetworkBindingUiSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramNetworkBindingUiSmoke",
                        "jvm:gtmb.hologram.clientSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramNetworkInteractionClientSmoke",
                "HologramNetworkInteractionClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramNetworkInteractionClientSmoke",
                        "jvm:gtmb.hologram.packagedProbeDir",
                        "jvm:gtmb.hologram.productionPreviewOutput")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramPlacementReviewClientSmoke",
                "HologramPlacementReviewClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramPlacementReviewClientSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramProcessingClientSmoke",
                "HologramProcessingClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramProcessingClientSmoke")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramProductionPreviewSmoke",
                "HologramProductionPreviewSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramProductionPreviewSmoke",
                        "jvm:gtmb.hologram.energyNetworkSmoke",
                        "jvm:gtmb.hologram.followupNormalDecor",
                        "jvm:gtmb.hologram.networkInteractionSmoke",
                        "jvm:gtmb.hologram.packagedProbeDir",
                        "jvm:gtmb.hologram.productionAdjacentPreview",
                        "jvm:gtmb.hologram.productionPreviewOutput",
                        "jvm:gtmb.hologram.productionPreviewSmoke",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramQuantumAnnotationClientProbe",
                "HologramQuantumAnnotationClientProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramQuantumAnnotationClientProbe")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramSulfurLineSmokeClientProbe",
                "HologramSulfurLineSmokeClientProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramSulfurLineSmokeClientProbe",
                        "jvm:gtmb.hologram.clientSmoke",
                        "jvm:gtmb.hologram.followupWorkbenchClient",
                        "jvm:gtmb.hologram.realInteractionSmoke",
                        "jvm:gtmb.hologram.sulfurLineAddress",
                        "jvm:gtmb.hologram.sulfurLineClient",
                        "jvm:gtmb.hologram.sulfurLineDimension",
                        "jvm:gtmb.hologram.sulfurLineIntent",
                        "jvm:gtmb.hologram.sulfurLineRole",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramV010GuiSmoke",
                "HologramV010GuiSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramV010GuiSmoke",
                        "jvm:gtmb.hologram.clientSmoke",
                        "jvm:gtmb.hologram.clientState",
                        "jvm:gtmb.hologram.interactionReceiptDir",
                        "jvm:gtmb.hologram.v010GuiSmoke",
                        "jvm:gtmb.hologram.v010MovingWheelSmoke",
                        "jvm:gtmb.hologram.v010ReceiptDir")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramV011AreaClientSmoke",
                "HologramV011AreaClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramV011AreaClientSmoke",
                        "jvm:gtmb.hologram.followupAddress",
                        "jvm:gtmb.hologram.followupAreaClient",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramV011NormalDecorProbe",
                "HologramV011NormalDecorProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramV011NormalDecorProbe",
                        "jvm:gtmb.hologram.followupNormalDecor",
                        "jvm:gtmb.hologram.previewCaptureWitness")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramV011ProcessingClientProbe",
                "HologramV011ProcessingClientProbe",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramV011ProcessingClientProbe",
                        "jvm:gtmb.hologram.followupProductionFlow",
                        "jvm:gtmb.hologram.followupWorkbenchClient",
                        "jvm:gtmb.hologram.productionPreviewSmoke",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramV011WorkbenchClientSmoke",
                "HologramV011WorkbenchClientSmoke",
                "client",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramV011WorkbenchClientSmoke",
                        "jvm:gtmb.hologram.clientSmoke",
                        "jvm:gtmb.hologram.followupAddress",
                        "jvm:gtmb.hologram.followupWorkbenchClient",
                        "jvm:gtmb.hologram.previewCaptureWitness",
                        "jvm:gtmb.hologram.realInteractionSmoke",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramAeBlueprintSmoke",
                "HologramAeBlueprintSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramAeBlueprintSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramAeCellHandlerSmoke",
                "HologramAeCellHandlerSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramAeCellHandlerSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramAeExternalCellSmoke",
                "HologramAeExternalCellSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramAeExternalCellSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramAeInteractionServerProbe",
                "HologramAeInteractionServerProbe",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramAeInteractionServerProbe")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramAeNativeResourcesSmoke",
                "HologramAeNativeResourcesSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramAeNativeResourcesSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramBlueprintOwnershipSmoke",
                "HologramBlueprintOwnershipSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramBlueprintOwnershipSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramBlueprintSmoke",
                "HologramBlueprintSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramBlueprintSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCommunityDeclarationSmoke",
                "HologramCommunityDeclarationSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCommunityDeclarationSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCompatibilitySmoke",
                "HologramCompatibilitySmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCompatibilitySmoke",
                        "jvm:gtmb.hologram.compatibilitySmoke",
                        "method:registerIfEnabled")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramConstructionPerformanceProbe",
                "HologramConstructionPerformanceProbe",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramConstructionPerformanceProbe")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramConstructionRecoverySmoke",
                "HologramConstructionRecoverySmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramConstructionRecoverySmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCoordinateServiceSmoke",
                "HologramCoordinateServiceSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCoordinateServiceSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCutAeDataNativeSmoke",
                "HologramCutAeDataNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCutAeDataNativeSmoke",
                        "jvm:gtmb.hologram.cutAeDataNativeSmoke",
                        "jvm:gtmb.hologram.cutAeDataPhase")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCutAeNativeSmoke",
                "HologramCutAeNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCutAeNativeSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCutLightingNativeSmoke",
                "HologramCutLightingNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCutLightingNativeSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCutMultipartNativeSmoke",
                "HologramCutMultipartNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCutMultipartNativeSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCutNativeServerProbe",
                "HologramCutNativeServerProbe",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCutNativeServerProbe",
                        "jvm:gtmb.hologram.cutNativeServer",
                        "jvm:gtmb.hologram.nativeStartupSeconds",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramCutNativeSmoke",
                "HologramCutNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCutNativeSmoke",
                        "jvm:gtmb.hologram.cutNativeKeepRunning",
                        "jvm:gtmb.hologram.cutNativeSmoke",
                        "jvm:gtmb.hologram.cutPersistencePhase",
                        "jvm:gtmb.hologram.dynamicDefinitionNativeSmoke",
                        "method:install")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramCutTransformNativeSmoke",
                "HologramCutTransformNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramCutTransformNativeSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramDecorSourceSmoke",
                "HologramDecorSourceSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramDecorSourceSmoke",
                        "jvm:gtmb.hologram.followupNormalDecor",
                        "jvm:gtmb.hologram.networkInteractionSmoke",
                        "jvm:gtmb.hologram.productionAdjacentPreview",
                        "jvm:gtmb.hologram.productionPreviewSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramDynamicDefinitionNativeSmoke",
                "HologramDynamicDefinitionNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramDynamicDefinitionNativeSmoke",
                        "jvm:gtmb.hologram.dynamicDefinitionReceipt")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramEnergyNetworkSmoke",
                "HologramEnergyNetworkSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramEnergyNetworkSmoke",
                        "jvm:energyNetworkSmoke",
                        "jvm:gtmb.hologram.energyNetworkSmoke",
                        "jvm:gtmb.hologram.packagedProbeDir",
                        "jvm:gtmb.hologram.productionPreviewOutput",
                        "method:registerIfEnabled")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramEssentiaMeSmoke",
                "HologramEssentiaMeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramEssentiaMeSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramEssentiaNativeSmoke",
                "HologramEssentiaNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramEssentiaNativeSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramGtswnCacheNativeSmoke",
                "HologramGtswnCacheNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramGtswnCacheNativeSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramGtswnNativeMaterialsSmoke",
                "HologramGtswnNativeMaterialsSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramGtswnNativeMaterialsSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramGuidanceNativeServerProbe",
                "HologramGuidanceNativeServerProbe",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramGuidanceNativeServerProbe",
                        "jvm:gtmb.hologram.guidanceNativeServer",
                        "jvm:gtmb.hologram.nativeStartupSeconds",
                        "jvm:gtmb.hologram.sulfurLineServer",
                        "method:install")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramInteractionSmoke",
                "HologramInteractionSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramInteractionSmoke",
                        "jvm:gtmb.hologram.aeRealInteractionSmoke",
                        "jvm:gtmb.hologram.extendedCompatibilitySmoke",
                        "jvm:gtmb.hologram.interactionReceiptDir",
                        "jvm:gtmb.hologram.interactionServer",
                        "jvm:gtmb.hologram.nativeStartupSeconds",
                        "method:registerIfEnabled")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramLibrarySocketServerProbe",
                "HologramLibrarySocketServerProbe",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramLibrarySocketServerProbe",
                        "jvm:gtmb.hologram.libraryReceiptDir",
                        "jvm:gtmb.hologram.librarySocketSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramNativeCaptureSmoke",
                "HologramNativeCaptureSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramNativeCaptureSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramNativeDisplaySmoke",
                "HologramNativeDisplaySmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramNativeDisplaySmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramNativePlacementSmoke",
                "HologramNativePlacementSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramNativePlacementSmoke",
                        "jvm:gtmb.v0115.largeGtJob")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramNetworkBindingSmoke",
                "HologramNetworkBindingSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramNetworkBindingSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramNetworkInteractionSmoke",
                "HologramNetworkInteractionSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramNetworkInteractionSmoke",
                        "jvm:gtmb.hologram.networkBindingSmoke",
                        "jvm:gtmb.hologram.networkInteractionSmoke",
                        "jvm:gtmb.hologram.packagedProbeDir",
                        "jvm:gtmb.hologram.productionPreviewOutput")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramPartialPlacementSmoke",
                "HologramPartialPlacementSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramPartialPlacementSmoke",
                        "jvm:gtmb.hologram.placementIdentitySmoke",
                        "jvm:gtmb.hologram.smokeShutdown",
                        "method:registerIdentityFixtureIfEnabled")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramPipeBlueprintSmoke",
                "HologramPipeBlueprintSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramPipeBlueprintSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramQuantumClearNativeSmoke",
                "HologramQuantumClearNativeSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramQuantumClearNativeSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramSmokeTest",
                "HologramSmokeTest",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramSmokeTest",
                        "jvm:energyNetworkSmoke",
                        "jvm:gtmb.hologram.aeExternalCellSmoke",
                        "jvm:gtmb.hologram.coexistSmoke",
                        "jvm:gtmb.hologram.energyNetworkSmoke",
                        "jvm:gtmb.hologram.extendedCompatibilitySmoke",
                        "jvm:gtmb.hologram.networkBindingSmoke",
                        "jvm:gtmb.hologram.referenceServer",
                        "jvm:gtmb.hologram.smoketest",
                        "jvm:gtmb.hologram.v010WorldSmoke",
                        "method:registerIfEnabled")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramSulfurLineSmoke",
                "HologramSulfurLineSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramSulfurLineSmoke",
                        "jvm:gtmb.hologram.sulfurLineDimension",
                        "jvm:gtmb.hologram.sulfurLineIntent",
                        "jvm:gtmb.hologram.sulfurLineMaxTicks",
                        "jvm:gtmb.hologram.sulfurLinePhase",
                        "jvm:gtmb.hologram.sulfurLineServer")),
                false,
                true));
        cases.add(
            new LabCase(
                "legacy-HologramSulfurLineSmokeBank",
                "HologramSulfurLineSmokeBank",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramSulfurLineSmokeBank")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramSulfurLineSmokeDecor",
                "HologramSulfurLineSmokeDecor",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramSulfurLineSmokeDecor",
                        "jvm:gtmb.hologram.sulfurLineServer")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramSulfurLineSmokeNative",
                "HologramSulfurLineSmokeNative",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramSulfurLineSmokeNative")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramSulfurLineSmokeOldIssues",
                "HologramSulfurLineSmokeOldIssues",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramSulfurLineSmokeOldIssues")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramTestLabBlueprintProbe",
                "HologramTestLabBlueprintProbe",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramTestLabBlueprintProbe")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramV010WorldSmoke",
                "HologramV010WorldSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramV010WorldSmoke",
                        "jvm:gtmb.hologram.v010WorldSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramV0115AcceptanceSmoke",
                "HologramV0115AcceptanceSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramV0115AcceptanceSmoke",
                        "jvm:gtmb.v0115.acceptanceSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramV0115HubAcceptanceSmoke",
                "HologramV0115HubAcceptanceSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramV0115HubAcceptanceSmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramV0116AeQuoteAcceptanceSmoke",
                "HologramV0116AeQuoteAcceptanceSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramV0116AeQuoteAcceptanceSmoke",
                        "jvm:gtmb.v0116.aeQuoteAcceptance")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramV0116MaterialAcceptanceProbe",
                "HologramV0116MaterialAcceptanceProbe",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramV0116MaterialAcceptanceProbe",
                        "jvm:gtmb.v0116.blueprintInput",
                        "jvm:gtmb.v0116.materialProbe")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramV0116StructureAcceptanceSmoke",
                "HologramV0116StructureAcceptanceSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramV0116StructureAcceptanceSmoke",
                        "jvm:gtmb.v0116.structureAcceptance")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramV011AssetPrioritySmoke",
                "HologramV011AssetPrioritySmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramV011AssetPrioritySmoke")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-HologramV011WorldSmoke",
                "HologramV011WorldSmoke",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.hologram.HologramV011WorldSmoke",
                        "jvm:gtmb.hologram.followupProfile",
                        "jvm:gtmb.hologram.followupWorldServer",
                        "method:install")),
                false,
                false));
        cases.add(
            new LabCase(
                "legacy-ProbeChunkTickets",
                "ProbeChunkTickets",
                "jvm",
                new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                        "legacy",
                        "isolated-jvm",
                        "explicit-launch-profile",
                        "entry:com.miaokatze.gtmb.util.ProbeChunkTickets")),
                false,
                false));
    }
}
