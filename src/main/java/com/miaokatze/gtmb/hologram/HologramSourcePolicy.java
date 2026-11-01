package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.Declarations;
import com.miaokatze.gtmb.api.StructureSources;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Adapts current session facts to registered read-only source rules. Saved labels never create source authority. */
final class HologramSourcePolicy {

    private HologramSourcePolicy() {}

    static boolean requiresFence(HologramSession session) {
        if (session.capture != null && session.capture.hasSourcePolicy()) return true;
        if (!session.virtual || session.virtualPreset == null) return false;
        HologramPresets.Preset preset = session.virtualPreset;
        if (preset.structures != null && !preset.structures.isEmpty()) {
            for (HologramPresets.Structure source : preset.structures) if (source != null && source.enabled
                && StructureSources.policy(HologramBlueprintData.sourceClass(preset, source)) != null) return true;
            return false;
        }
        return StructureSources.policy(HologramBlueprintData.sourceClass(preset, null)) != null;
    }

    static boolean requiresReread(HologramPresets.Preset preset, HologramPresets.Structure source) {
        String type = HologramBlueprintData.sourceClass(preset, source);
        return StructureSources.policy(type) != null || StructureSources.sourceFailure(type) != null;
    }

    static String constructionFailure(HologramSession session) {
        if (session.capture == null) return null;
        String declarationFailure = declarationFailure(session);
        if (declarationFailure != null) return declarationFailure;
        if (session.virtual && session.virtualPreset != null) {
            String targetFailure = savedControllerTargetFailure(session);
            if (targetFailure != null) return targetFailure;
            HologramPresets.Preset preset = session.virtualPreset;
            if (preset.structures != null && !preset.structures.isEmpty()) {
                for (HologramPresets.Structure source : preset.structures) {
                    if (source == null || !source.enabled) continue;
                    String failure = savedConstructionFailure(session, source);
                    if (failure != null) return failure;
                }
                return null;
            }
            return savedConstructionFailure(session, null);
        }
        if (session.machine == null) return null;
        String type = session.machine.getClass()
            .getName();
        String failure = StructureSources.sourceFailure(session.machine.getClass());
        if (failure != null) return failure;
        StructureSources.PolicyBinding policy = StructureSources.policy(session.machine.getClass());
        if (policy == null) return null;
        int controllerId = session.machine.getBaseMetaTileEntity()
            .getMetaTileID();
        return validate(session, policy, type, controllerId, "", new NBTTagCompound(), false);
    }

    /** Appearance alone cannot authorize a native element callback or an unknown acceptance condition. */
    private static String declarationFailure(HologramSession session) {
        if (session.mode == 2) return null;
        Set<HologramCapture.SourceAuthority> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = 0; index < session.capture.cells.size(); index++) {
            HologramCapture.Cell cell = session.capture.cells.get(index);
            if (cell.anchor || !HologramSessionPolicy.inScope(session, cell, index)
                || HologramSessionPolicy.ignoredPlacementAir(session, cell, index)) continue;
            ItemStack target = HologramCellPolicy.targetFor(session, cell, index);
            if (cell.userPosition && target == null) continue;
            if (cell.sourceAuthority != null && sources.add(cell.sourceAuthority)) {
                String failure = cell.sourceAuthority.validityFailure();
                if (failure != null) return failure;
            }
            if (cell.unknown) return "SOURCE_DECLARATION_UNVERIFIED";
            if (cell.element == null) {
                // Saved physical materials and explicit user positions use the existing payload/material gates.
                if (cell.userPosition && (session.virtual || HologramCellPolicy.explicitTarget(session, cell, index)))
                    continue;
                return "SOURCE_DECLARATION_UNVERIFIED";
            }
            if (cell.element == StructureUtility.isAir()) continue;
            Declarations.PureElement<?> pure = HologramElementCatalog.pure(cell.element);
            if (pure != null) {
                if (!pure.accepts(target)) return "SOURCE_TARGET_REJECTED";
                if (!HologramCellPolicy.explicitTarget(session, cell, index) && !pure.placementAllows(target))
                    return "SOURCE_AUTOMATIC_TARGET_REJECTED";
                continue;
            }
            List<ItemStack> candidates = HologramCellPolicy.declaredCandidates(session, cell);
            if (!cell.candidateComplete || candidates.stream()
                .noneMatch(candidate -> HologramValues.sameMaterial(candidate, target)))
                return "SOURCE_DECLARATION_UNVERIFIED";
        }
        return null;
    }

    private static String savedConstructionFailure(HologramSession session, HologramPresets.Structure source) {
        HologramPresets.Preset preset = session.virtualPreset;
        String declared = HologramBlueprintData.sourceClass(preset, source);
        int controllerId = source == null ? preset.controllerId : source.controllerId;
        IMetaTileEntity prototype = prototype(controllerId);
        String actual = prototype == null ? null
            : prototype.getClass()
                .getName();
        StructureSources.PolicyBinding policy = StructureSources.policy(declared);
        StructureSources.PolicyBinding actualPolicy = StructureSources.policy(actual);
        String declaredFailure = StructureSources.sourceFailure(declared);
        String actualFailure = prototype == null ? null : StructureSources.sourceFailure(prototype.getClass());
        if (policy == null && actualPolicy == null && declaredFailure == null && actualFailure == null) return null;
        if (declared == null || actual != null && !declared.equals(actual)) return "SOURCE_SAVED_IDENTITY_MISMATCH";
        if (declaredFailure != null) return declaredFailure;
        if (actualFailure != null) return actualFailure;
        if (prototype == null || policy == null || policy.controllerType != prototype.getClass())
            return "SOURCE_SAVED_IDENTITY_MISMATCH";
        NBTTagCompound saved;
        try {
            saved = HologramBlueprintNbt.decode(source == null ? preset.semanticNbt : source.semanticNbt);
        } catch (IOException invalid) {
            return "SOURCE_SAVED_METADATA_INVALID";
        }
        return validate(
            session,
            policy,
            declared,
            controllerId,
            source == null ? "" : source.id,
            saved == null ? new NBTTagCompound() : saved,
            true);
    }

    private static String validate(HologramSession session, StructureSources.PolicyBinding policy, String type,
        int controllerId, String structureId, NBTTagCompound saved, boolean savedPlan) {
        HologramCapture.SourceAuthority authority = null;
        boolean fresh = true;
        List<StructureSources.ValidationCell> cells = new ArrayList<>();
        for (int index = 0; index < session.capture.cells.size(); index++) {
            HologramCapture.Cell cell = session.capture.cells.get(index);
            if (!structureId.isEmpty() && !cell.belongsTo(structureId)) continue;
            boolean air = cell.element == StructureUtility.isAir();
            boolean controller = savedPlan && templateController(session, cell, index, type, controllerId, structureId);
            if (!cell.anchor && !air && !controller) {
                HologramCapture.SourceAuthority observed = cell.sourceAuthority;
                if (observed == null || observed.policy == null) fresh = false;
                else {
                    if (!policy.sourceId.equals(observed.policy.sourceId)
                        || policy.controllerType != observed.policy.controllerType
                        || controllerId != observed.controllerId
                        || authority != null && authority != observed) return "SOURCE_DECLARATION_MISMATCH";
                    if (authority == null) {
                        String failure = observed.validityFailure();
                        if (failure != null) return failure;
                    }
                    authority = observed;
                }
            }
            ItemStack target = HologramCellPolicy.targetFor(session, cell, index);
            boolean automatic = session.autoTargets.containsKey(index);
            boolean matches = automatic && target != null
                && HologramCellPolicy.targetMatches(
                    session,
                    cell,
                    index,
                    session.player.worldObj.getBlock(cell.x, cell.y, cell.z),
                    session.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z));
            cells.add(
                new StructureSources.ValidationCell(
                    cell.element,
                    cell.x,
                    cell.y,
                    cell.z,
                    session.facingPins.getOrDefault(HologramValues.position(cell), -1),
                    cell.anchor,
                    air,
                    controller,
                    automatic,
                    matches,
                    target));
        }
        fresh &= authority != null;
        NBTTagCompound metadata = authority == null ? saved : authority.metadata();
        StructureSources.CaptureIntent intent = authority == null ? StructureSources.CaptureIntent.NEW_TEMPLATE
            : authority.intent;
        String failure = policy.validate(
            new StructureSources.ValidationPlan(
                policy.sourceId,
                type,
                structureId,
                controllerId,
                intent,
                savedPlan,
                fresh,
                metadata,
                saved,
                cells));
        return failure != null ? failure : fresh ? null : "SOURCE_RESCAN_REQUIRED";
    }

    /** A protected controller target must retain an enabled saved source with its exact identity. */
    private static String savedControllerTargetFailure(HologramSession session) {
        for (int index = 0; index < session.capture.cells.size(); index++) {
            HologramCapture.Cell cell = session.capture.cells.get(index);
            if (cell.anchor || cell.excluded || !HologramSessionPolicy.membershipEnabled(session, cell)) continue;
            ItemStack target = HologramCellPolicy.targetFor(session, cell, index);
            if (target == null || !(target.getItem() instanceof ItemMachines)) continue;
            IMetaTileEntity prototype = prototype(target.getItemDamage());
            if (prototype == null) continue;
            String type = prototype.getClass()
                .getName();
            if (StructureSources.policy(prototype.getClass()) == null
                && StructureSources.sourceFailure(prototype.getClass()) == null) continue;
            if (!savedControllerSourceMatches(session, cell, target.getItemDamage(), type, ""))
                return "SOURCE_SAVED_IDENTITY_MISMATCH";
        }
        return null;
    }

    private static boolean savedControllerSourceMatches(HologramSession session, HologramCapture.Cell cell,
        int controllerId, String type, String structureId) {
        HologramPresets.Preset preset = session.virtualPreset;
        if (preset.structures != null && !preset.structures.isEmpty()) {
            for (HologramPresets.Structure source : preset.structures) if (source != null && source.enabled
                && (structureId.isEmpty() || structureId.equals(source.id))
                && cell.belongsTo(source.id)
                && source.controllerId == controllerId
                && type.equals(HologramBlueprintData.sourceClass(preset, source))) return true;
            return false;
        }
        return structureId.isEmpty() && preset.controllerId == controllerId
            && type.equals(HologramBlueprintData.sourceClass(preset, null));
    }

    private static boolean templateController(HologramSession session, HologramCapture.Cell cell, int index,
        String type, int controllerId, String structureId) {
        if (cell.anchor || cell.unknown
            || cell.element != null
            || !"templateController".equals(cell.role)
            || !cell.sourceWarning.isEmpty()
            || !type.equals(cell.sourceClass)) return false;
        ItemStack target = HologramCellPolicy.targetFor(session, cell, index);
        if (target == null || !(target.getItem() instanceof ItemMachines)
            || target.getItemDamage() != controllerId
            || !savedControllerSourceMatches(session, cell, controllerId, type, structureId)) return false;
        if (!structureId.isEmpty()) {
            HologramPresets.Structure source = HologramBlueprintData
                .blueprintSource(session.virtualPreset, structureId);
            return source != null && cell.x == session.x + source.x
                && cell.y == session.y + source.y
                && cell.z == session.z + source.z;
        }
        return cell.memberships()
            .isEmpty() && cell.x == session.x
            && cell.y == session.y
            && cell.z == session.z;
    }

    private static IMetaTileEntity prototype(int id) {
        return id >= 0 && id < GregTechAPI.METATILEENTITIES.length ? GregTechAPI.METATILEENTITIES[id] : null;
    }
}
