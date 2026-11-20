// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.IMultiblockInfoContainer;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.api.StructureSources;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Native controller identity and StructureLib provider resolution, independent of editing/session state. */
final class HologramStructureDiscovery {

    private HologramStructureDiscovery() {}

    static Object context(TileEntity tile) {
        return tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getMetaTileEntity() : tile;
    }

    static boolean supports(TileEntity tile) {
        Object controller = context(tile);
        return controller instanceof IConstructable || controller instanceof IConstructableProvider
            || tile instanceof IConstructable
            || tile instanceof IConstructableProvider
            || tile != null && IMultiblockInfoContainer.contains(tile.getClass())
            || controller instanceof MTEEnhancedMultiBlockBase && StructureSources.hasSource(controller.getClass());
    }

    static boolean captureSupported(MTEEnhancedMultiBlockBase<?> controller) {
        return controller != null
            && (controller instanceof IConstructable || StructureSources.hasSource(controller.getClass()));
    }

    static IConstructable constructable(TileEntity tile, Object controller, int side) {
        if (tile instanceof IConstructableProvider provider) {
            IConstructable supplied = provider.getConstructable();
            if (supplied != null) return supplied;
        }
        if (tile instanceof IConstructable) return (IConstructable) tile;
        IMultiblockInfoContainer<TileEntity> container = tile == null ? null
            : IMultiblockInfoContainer.get(tile.getClass());
        if (container != null) {
            ExtendedFacing facing = tile instanceof IAlignment ? ((IAlignment) tile).getExtendedFacing()
                : ExtendedFacing.of(ForgeDirection.getOrientation(side));
            IConstructable supplied = container.toConstructable(tile, facing);
            if (supplied != null) return supplied;
        }
        if (controller == tile) return null;
        if (controller instanceof IConstructableProvider provider) {
            IConstructable supplied = provider.getConstructable();
            if (supplied != null) return supplied;
        }
        return controller instanceof IConstructable ? (IConstructable) controller : null;
    }
}
