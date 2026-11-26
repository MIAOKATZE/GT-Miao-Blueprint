// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Read-only installed-shape selection for a reviewed native, variable-size declaration. */
final class HologramInstalledControllerShape {

    private static final String TURBINE = "com.miaokatze.gtsr.common.machine.MTEMegaSteamTurbineArray";
    private static final int TURBINE_MAX_STACKS = 4;
    private static final String TURBINE_LENGTH = "lenght"; // GTSR's native channel spelling.
    private static final int STRUCTURE_INCOMPLETE = 64; // GT's synchronized multiblock error bit.

    private final IStructureElement<?>[] declaration;
    private final HologramStructureCursor cursor;
    private final int groups;

    HologramInstalledControllerShape(IStructureElement<?>[] declaration, int a, int b, int c, int groups) {
        this.declaration = declaration;
        this.cursor = new HologramStructureCursor(declaration, a, b, c);
        this.groups = groups;
    }

    static HologramInstalledControllerShape read(Object context, ItemStack targetTrigger) {
        int groups = installedGroups(context);
        if (groups == 0 || groups == selectedGroups(ChannelDataAccessor.getChannelData(targetTrigger, TURBINE_LENGTH)))
            return null;
        MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) context;
        try {
            IStructureDefinition<?> definition = machine.getStructureDefinition();
            if (definition == null || !HologramStructureCursor.supports(definition)) return null;
            IStructureElement<?>[] elements = definition.getStructureFor("axial_" + (groups - 1));
            if (elements == null || elements.length == 0) return null;
            // GTSR's native HORIZONTAL_OFFSET, VERTICAL_OFFSET, DEPTH_OFFSET.
            return new HologramInstalledControllerShape(elements, 25, 22, 1, groups);
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static int selectedGroups(int channel) {
        return Math.max(1, Math.min(TURBINE_MAX_STACKS + 1, channel));
    }

    static int installedGroups(int nativeState, int errorDisplay) {
        int groups = 1 + ((nativeState & 0x70) >>> 4);
        return (errorDisplay & STRUCTURE_INCOMPLETE) == 0 && (nativeState & 15) > 0 && groups <= TURBINE_MAX_STACKS + 1
            ? groups
            : 0;
    }

    private static int installedGroups(Object context) {
        if (!(context instanceof MTEEnhancedMultiBlockBase<?>machine) || !TURBINE.equals(
            context.getClass()
                .getName()))
            return 0;
        // GTSR's onValueUpdate synchronizes casing and axial stack count, not the tool's selected length.
        return installedGroups(machine.getUpdateData() & 0xff, machine.getErrorDisplayID());
    }

    boolean matches(Object context) {
        return installedGroups(context) == groups;
    }

    int groups() {
        return groups;
    }

    boolean hasNext() {
        return cursor.done() < cursor.total();
    }

    /** Only native solid slots, never the required air or padding coordinates. */
    HologramStructureCursor.Offset next(ExtendedFacing facing) {
        IStructureElement<?> element = declaration[cursor.done()];
        HologramStructureCursor.Offset offset = cursor.declarationOffset(facing);
        return element == StructureUtility.isAir() ? null : offset;
    }
}
