// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.IMultiblockInfoContainer;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.miaokatze.gtmb.testutil.SimpleAssert;

final class HologramStructureDiscoveryRegression {

    private HologramStructureDiscoveryRegression() {}

    static void run() {
        ProviderTile tile = new ProviderTile();
        DirectTile direct = new DirectTile();
        int side = ForgeDirection.NORTH.ordinal();
        SimpleAssert.eq(direct, HologramService.nativeConstructable(direct, side), "direct tile after null provider");
        SimpleAssert.eq(direct, HologramStructureDiscovery.constructable(tile, direct, side), "controller fallback");
        SimpleAssert.eq(null, HologramService.nativeConstructable(tile, side), "provider alone does not resolve");
        int[] hintCalls = { 0 };
        IMultiblockInfoContainer<ProviderTile> container = new IMultiblockInfoContainer<>() {

            @Override
            public void construct(ItemStack trigger, boolean hintsOnly, ProviderTile actual, ExtendedFacing facing) {
                SimpleAssert.eq(tile, actual, "registered container receives the original tile");
                SimpleAssert.eq(ExtendedFacing.of(ForgeDirection.NORTH), facing, "selected side reaches container");
                SimpleAssert.that(hintsOnly, "consumer still uses native hints");
                hintCalls[0]++;
            }

            @Override
            public int survivalConstruct(ItemStack trigger, int budget, ISurvivalBuildEnvironment environment,
                ProviderTile actual, ExtendedFacing facing) {
                throw new AssertionError("discovery started a build");
            }

            @Override
            public String[] getDescription(ItemStack trigger) {
                return new String[0];
            }
        };
        IMultiblockInfoContainer.registerTileClass(ProviderTile.class, container);
        try {
            IConstructable wrapped = HologramService.nativeConstructable(tile, side);
            SimpleAssert.that(wrapped != null, "null provider falls through to the registered StructureLib container");
            SimpleAssert.eq(0, hintCalls[0], "discovery never invokes construction or native hints");
            wrapped.construct(null, true);
            SimpleAssert.eq(1, hintCalls[0], "the native StructureLib wrapper invokes its own registered container");
            tile.supplied = direct;
            SimpleAssert
                .eq(direct, HologramService.nativeConstructable(tile, side), "non-null provider keeps priority");
        } finally {
            IMultiblockInfoContainer.MULTIBLOCK_MAP.remove(ProviderTile.class.getCanonicalName());
        }
    }

    private static class ProviderTile extends TileEntity implements IConstructableProvider {

        IConstructable supplied;

        @Override
        public IConstructable getConstructable() {
            return supplied;
        }
    }

    private static final class DirectTile extends ProviderTile implements IConstructable {

        @Override
        public void construct(ItemStack trigger, boolean hintsOnly) {
            throw new AssertionError("discovery started direct construction");
        }

        @Override
        public String[] getStructureDescription(ItemStack trigger) {
            return new String[0];
        }
    }
}
