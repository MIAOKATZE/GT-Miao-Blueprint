// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.IMultiblockInfoContainer;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementNoPlacement;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.metatileentity.BaseMetaTileEntity;

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

    static void runNativePreview() {
        try {
            nativePreview(false);
            nativePreview(true);
            gregTechPreview();
            blockRendererPortableHeight();
        } catch (IOException failure) {
            throw new AssertionError("detached native structure", failure);
        }
    }

    private static void blockRendererPortableHeight() throws IOException {
        int anchor = HologramBlockRendererPreview.CONTROLLER_Y;
        HologramBlockRendererPreview.requirePortableHeight(300, anchor);
        HologramBlockRendererPreview.requirePortableHeight(-120, anchor);
        for (int relative : new int[] { -257, 257 }) {
            try {
                HologramBlockRendererPreview.requirePortableHeight(anchor + relative, anchor);
                throw new AssertionError("accepted out-of-range relative Y " + relative);
            } catch (IOException expected) {
                SimpleAssert.that(expected.getMessage().endsWith(Integer.toString(relative)),
                    "the relative out-of-bounds row is reported");
            }
        }
    }

    private static void nativePreview(boolean structureCompatFallback) throws IOException {
        HologramResourceWorld source = HologramResourceWorld.declarations(Blocks.bookshelf, 0);
        TileEntity tile = new TileEntity();
        tile.yCoord = 128;
        tile.setWorldObj(source);
        source.tile = tile;
        PreviewFixture fixture = new PreviewFixture(tile, structureCompatFallback);
        ItemStack trigger = new ItemStack(Items.stick, 2);
        try (HologramNativeStructurePreview preview =
            new HologramNativeStructurePreview(tile, fixture, fixture, structureCompatFallback, trigger)) {
            boolean done = preview.advance(System.nanoTime() - 1);
            SimpleAssert.eq(structureCompatFallback, done, "a write requires another native survival pass");
            SimpleAssert.eq(source, tile.getWorldObj(), "tile is detached from the simulation between passes");
            SimpleAssert.that(!StructureLibAPI.isInstrumentEnabled(), "the StructureLib instrument is scoped");
            if (!done) {
                SimpleAssert.that(preview.advance(System.nanoTime() - 1), "the unchanged pass completes the preview");
            }
            SimpleAssert.eq(structureCompatFallback ? 1 : 2, fixture.rounds, "survival call count follows writes");
            SimpleAssert.eq(structureCompatFallback ? 1 : 0, fixture.constructs, "only the compat -2 status requests construct");
            SimpleAssert.eq("native preview fixture", preview.description().getStringTagAt(0), "native description preserved");
            var placements = preview.placements();
            HologramCapture.Cell observed = preview.cell(placements.next());
            SimpleAssert.eq(Blocks.stone, observed.block, "actual placed block is preserved");
            SimpleAssert.that(!placements.hasNext(), "controller anchor is excluded from native placements");
            SimpleAssert.eq(fixture.latest, observed.element, "latest instrumented XYZ element supersedes the previous visit");
            SimpleAssert.eq("", observed.prompt, "native element descriptions do not become player annotations");
            SimpleAssert.that(observed.candidateComplete && observed.candidates.size() == 1
                && observed.candidates.get(0).getItem() == new ItemStack(Blocks.cobblestone).getItem(),
                "native getBlocksToPlace supplies the final declaration candidates");
            SimpleAssert.eq(Blocks.air, source.getBlock(1, 128, 0), "source world never receives a placed block");
        }
    }

    private static void gregTechPreview() throws IOException {
        HologramResourceWorld source = HologramResourceWorld.declarations(Blocks.bookshelf, 0);
        TileEntity tile = new BaseMetaTileEntity();
        tile.yCoord = 128;
        tile.setWorldObj(source);
        source.tile = tile;
        PreviewFixture fixture = new PreviewFixture(tile, false, true);
        ItemStack trigger = new ItemStack(Items.stick, 2);
        ChannelDataAccessor.setChannelData(trigger, "gt_hatch", 9);
        try (HologramNativeStructurePreview preview =
            new HologramNativeStructurePreview(tile, fixture, fixture, false, trigger)) {
            SimpleAssert.that(!preview.advance(System.nanoTime() - 1), "GT survival waits for a stable pass");
            SimpleAssert.eq(1, fixture.previewsStarted, "pre-hook precedes the first survival pass");
            SimpleAssert.eq(0, fixture.previewsCompleted, "completion waits until survival is stable");
            SimpleAssert.that(preview.advance(System.nanoTime() - 1), "GT survival completes");
            SimpleAssert.eq(1, fixture.previewsStarted, "resuming a pass never reruns the pre-hook");
            SimpleAssert.eq(1, fixture.previewsCompleted, "post-hook contributes its geometry once");
            var placements = preview.placements();
            SimpleAssert.eq(Blocks.stone, placements.next().block(), "GT survival's placement was retained");
            SimpleAssert.eq(Blocks.glass, placements.next().block(), "GT completion hook's placement was retained");
            SimpleAssert.eq(9, ChannelDataAccessor.getChannelData(trigger, "gt_hatch"), "forcing hatch 1 leaves saved settings alone");
        }
    }

    private static final class PreviewFixture implements ISurvivalConstructable, INEIPreviewModifier {

        private final TileEntity tile;
        private final boolean fallback;
        private final boolean gregTech;
        private final IStructureElement<PreviewFixture> first = StructureUtility.ofBlock(Blocks.stone, 0);
        private final IStructureElement<PreviewFixture> latest = new IStructureElementNoPlacement<>() {

            @Override
            public boolean check(PreviewFixture fixture, World world, int x, int y, int z) {
                return world.getBlock(x, y, z) == Blocks.cobblestone;
            }

            @Override
            public boolean spawnHint(PreviewFixture fixture, World world, int x, int y, int z, ItemStack trigger) {
                return false;
            }

            @Override
            public BlocksToPlace getBlocksToPlace(PreviewFixture fixture, World world, int x, int y, int z,
                ItemStack trigger, AutoPlaceEnvironment environment) {
                return BlocksToPlace.create(Blocks.cobblestone, 0);
            }

            @Override
            public List<String> getDescription(PreviewFixture fixture) {
                return Collections.singletonList("GT5U.MBTT.InputHatch");
            }
        };
        private int rounds;
        private int constructs;
        private int previewsStarted;
        private int previewsCompleted;

        PreviewFixture(TileEntity tile, boolean fallback) {
            this(tile, fallback, false);
        }

        PreviewFixture(TileEntity tile, boolean fallback, boolean gregTech) {
            this.tile = tile;
            this.fallback = fallback;
            this.gregTech = gregTech;
        }

        @Override
        public int survivalConstruct(ItemStack trigger, int budget, ISurvivalBuildEnvironment environment) {
            World world = tile.getWorldObj();
            rounds++;
            SimpleAssert.eq(2, trigger.stackSize, "main signal reaches the native callback");
            SimpleAssert.eq(world, environment.getActor().worldObj, "preview actor belongs to the isolated world");
            SimpleAssert.eq(Integer.MAX_VALUE, budget, "each native pass receives the browser's full placement budget");
            if (gregTech) {
                SimpleAssert.eq(1, previewsStarted, "GT pre-hook runs before survival");
                SimpleAssert.eq(1, ChannelDataAccessor.getChannelData(trigger, "gt_hatch"), "native GT preview forces hatch 1");
            }
            if (fallback) return -2;
            visit(world, rounds == 1 ? first : latest);
            if (rounds == 1) {
                SimpleAssert.eq(
                    IStructureElement.PlaceResult.ACCEPT,
                    StructureUtility.survivalPlaceBlock(
                        Blocks.stone, 0, world, 1, 128, 0, environment.getSource(), environment.getActor()),
                    "detached creative source and actor can execute native survival placement");
                // The Large Solar Overpressure Array queries sky visibility inside its first build pass.
                SimpleAssert.that(world.canBlockSeeTheSky(1, 129, 0), "preview queries can see the empty sky");
                SimpleAssert.that(!world.canBlockSeeTheSky(1, -1, 0), "preview below the empty height map has no sky");
            }
            return 0;
        }

        @Override
        public void construct(ItemStack trigger, boolean hintsOnly) {
            SimpleAssert.that(!hintsOnly, "native compatibility fallback places concrete geometry");
            World world = tile.getWorldObj();
            visit(world, latest);
            world.setBlock(1, 128, 0, Blocks.stone, 0, 3);
            constructs++;
        }

        @Override
        public String[] getStructureDescription(ItemStack trigger) {
            return new String[] { "native preview fixture" };
        }

        @Override
        public void onPreviewConstruct(ItemStack trigger) {
            previewsStarted++;
        }

        @Override
        public void onPreviewStructureComplete(ItemStack trigger) {
            tile.getWorldObj().setBlock(2, 128, 0, Blocks.glass, 0, 2);
            previewsCompleted++;
        }

        private static void visit(World world, IStructureElement<?> element) {
            // The vanilla bootstrap does not install Forge's event constructor transformer.
            try {
                Constructor<StructureElementVisitedEvent> constructor = StructureElementVisitedEvent.class
                    .getDeclaredConstructor(
                        World.class, int.class, int.class, int.class, int.class, int.class, int.class,
                        IStructureElement.class);
                constructor.setAccessible(true);
                new HologramNativeStructurePreview.VisitedListener()
                    .elementVisited(constructor.newInstance(world, 1, 128, 0, 1, 0, 0, element));
            } catch (ReflectiveOperationException unavailable) {
                throw new AssertionError("StructureLib fixture event", unavailable);
            }
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
