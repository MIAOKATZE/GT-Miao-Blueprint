package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Diagnostic locator for existing native smoke/regression reflection after domain ownership changes.
 * It is package-private, is never called by runtime workflows, and grants no operation capability.
 */
final class HologramEngineInspection {

    private HologramEngineInspection() {}

    private static final Class<?>[] OWNERS = { HologramActionDispatcher.class, HologramAreaDeclarations.class,
        HologramAreaWorkflow.class, HologramAuthoringQueries.class, HologramAuthoringWorkflow.class,
        HologramBlueprintData.class, HologramBlueprintEditor.class, HologramBlueprintProjection.class,
        HologramBuildPreparation.class, HologramCellPolicy.class, HologramControllerCapture.class,
        HologramDraftPersistence.class, HologramEditState.class, HologramLibraryWorkflow.class,
        HologramMaterialAccess.class, HologramMaterialSources.class, HologramPlacementJobs.class,
        HologramPlanPolicy.class, HologramPresetWorkflow.class, HologramProjectorWorkflow.class,
        HologramResourcePlanner.class, HologramSession.class, HologramSessionKeys.class, HologramSessionPolicy.class,
        HologramSnapshots.class, HologramValues.class, HologramWorkspace.class, HologramWorkspaceScheduler.class,
        HologramWorldPlacementWorkflow.class, HologramWorldTransactions.class };

    static Method declaredMethod(String name, Class<?>... signature) throws NoSuchMethodException {
        for (Class<?> owner : OWNERS) try {
            return owner.getDeclaredMethod(name, signature);
        } catch (NoSuchMethodException absent) {}
        throw new NoSuchMethodException("No engine domain owns " + name);
    }

    static Field declaredField(String name) throws NoSuchFieldException {
        for (Class<?> owner : OWNERS) try {
            return owner.getDeclaredField(name);
        } catch (NoSuchFieldException absent) {}
        throw new NoSuchFieldException("No engine domain owns " + name);
    }
}
