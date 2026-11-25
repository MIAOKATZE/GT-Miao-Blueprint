// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;

/** Shared guard for exclusive construction, regardless of source or machine entry. */
final class HologramConstructionAuthority {

    private HologramConstructionAuthority() {}

    static boolean competingWork(EntityPlayerMP player) {
        return competingWork(player, false);
    }

    static boolean competingWork(EntityPlayerMP player, boolean preserveIdleDraft) {
        return HologramNativeLocalConstruction.active(player)
            || !quiet(player, HologramWorkspace.find(player.getUniqueID()), preserveIdleDraft);
    }

    static boolean hasKnownRecovery(EntityPlayerMP player) {
        return HologramConstructionJournal.pending(player) || HologramMaterials.Context.pendingWorldRecovery(player);
    }

    private static boolean quiet(EntityPlayerMP player, HologramSession origin, boolean preserveIdleDraft) {
        if (HologramWorkspace.find(player.getUniqueID()) != origin || HologramDemolition.active(player)
            || HologramAeCraftingOrders.active(player)) return false;
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        return (area == null || !area.selecting && !area.importing)
            && (origin == null || origin.player == player && HologramEditorWorkspaceRegistry.idle(origin)
                && origin.nativeReadyPlacement == null
                && origin.nativeCompactStart == null
                && origin.presentationTransition == null
                && !HologramSnapshotPreparation.pending(origin)
                && (preserveIdleDraft || !HologramAuthoringWorkflow.blueprintHasUnsavedChanges(origin)));
    }

}
