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
            SimpleAssert.eq(
                direct,
                HologramStructureDiscovery.localConstructable(tile, tile, ExtendedFacing.of(ForgeDirection.NORTH)),
                "local and native entry resolution keep the same provider priority");
        } finally {
            IMultiblockInfoContainer.MULTIBLOCK_MAP.remove(ProviderTile.class.getCanonicalName());
        }
    }

    static void runNativePreview() {
        try {
            nativePreview();
            incompletePreviewWithoutPristineDeclaration();
            zeroPassesAreNotCompletion();
            publicNativeQueryDoesNotRequireReviewedFactory();
            HologramNativeTargetPlanRegression.run();
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
                SimpleAssert.that(
                    expected.getMessage()
                        .endsWith(Integer.toString(relative)),
                    "the relative out-of-bounds row is reported");
            }
        }
    }

    private static void nativePreview() throws IOException {
        HologramResourceWorld source = HologramResourceWorld.declarations(Blocks.bookshelf, 0);
        TileEntity tile = new TileEntity();
        tile.yCoord = 128;
        tile.setWorldObj(source);
        source.tile = tile;
        PreviewFixture fixture = new PreviewFixture(tile, false);
        ItemStack trigger = new ItemStack(Items.stick, 2);
        try (HologramNativeStructurePreview preview = new HologramNativeStructurePreview(
            tile,
            fixture,
            fixture,
            false,
            trigger)) {
            SimpleAssert.that(!preview.advance(System.nanoTime() - 1), "a write requires another native survival pass");
            SimpleAssert.eq(source, tile.getWorldObj(), "tile is detached from the simulation between passes");
            SimpleAssert.that(!StructureLibAPI.isInstrumentEnabled(), "the StructureLib instrument is scoped");
            SimpleAssert.that(preview.advance(System.nanoTime() - 1), "the native terminal pass completes the preview");
            SimpleAssert.eq(2, fixture.rounds, "survival call count follows writes");
            SimpleAssert.eq(0, fixture.constructs, "completed native survival does not request construct");
            SimpleAssert.eq(
                "native preview fixture",
                preview.description()
                    .getStringTagAt(0),
                "native description preserved");
            var placements = preview.placements();
            HologramCapture.Cell observed = preview.cell(placements.next());
            SimpleAssert.eq(Blocks.stone, observed.block, "actual placed block is preserved");
            SimpleAssert.that(!placements.hasNext(), "controller anchor is excluded from native placements");
            SimpleAssert
                .eq(fixture.latest, observed.element, "latest instrumented XYZ element supersedes the previous visit");
            SimpleAssert.eq("", observed.prompt, "native element descriptions do not become player annotations");
            SimpleAssert.that(
                observed.candidateComplete && observed.candidates.size() == 1
                    && observed.candidates.get(0)
                        .getItem() == new ItemStack(Blocks.cobblestone).getItem(),
                "native getBlocksToPlace supplies the final declaration candidates");
            SimpleAssert.eq(Blocks.air, source.getBlock(1, 128, 0), "source world never receives a placed block");
        }
    }

    private static void incompletePreviewWithoutPristineDeclaration() throws IOException {
        HologramResourceWorld source = HologramResourceWorld.declarations(Blocks.bookshelf, 0);
        TileEntity tile = new TileEntity();
        tile.yCoord = 128;
        tile.setWorldObj(source);
        source.tile = tile;
        PreviewFixture fixture = new PreviewFixture(tile, true);
        ItemStack trigger = new ItemStack(Items.stick, 2);
        try (HologramNativeStructurePreview preview = new HologramNativeStructurePreview(
            tile,
            fixture,
            fixture,
            true,
            trigger)) {
            try {
                preview.advance(System.nanoTime() - 1);
                throw new AssertionError("stable unsupported survival cannot publish an unproven footprint");
            } catch (IOException expected) {
                SimpleAssert.eq("LOCAL_NATIVE_PREVIEW_INCOMPLETE", expected.getMessage(), "missing declaration proof");
            }
            SimpleAssert.eq(1, fixture.rounds, "-2 terminates survival without retrying the unsupported interface");
            SimpleAssert.eq(0, fixture.constructs, "fallback cannot reuse the incomplete controller");
            SimpleAssert.eq(source, tile.getWorldObj(), "failure restores the original world");
            SimpleAssert.that(!StructureLibAPI.isInstrumentEnabled(), "failure restores instrumentation scope");
        }
    }

    private static void publicNativeQueryDoesNotRequireReviewedFactory() {
        IStructureElement<Object> opaque = new IStructureElementNoPlacement<>() {

            @Override
            public boolean check(Object context, World world, int x, int y, int z) {
                return true;
            }

            @Override
            public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
                return false;
            }
        };
        int[] calls = { 0 };
        HologramNativeHatchChooser chooser = new HologramNativeHatchChooser(
            () -> { throw new AssertionError("a finite public item list needs no full registry scan"); },
            () -> (element, x, y, z, signal) -> {
                SimpleAssert.eq(opaque, element, "opaque element reaches the public native query intact");
                SimpleAssert
                    .eq(3 + calls[0]++, x, "coordinate-sensitive native offers are not shared by element identity");
                SimpleAssert.eq(7, signal.stackSize, "effective piece signal reaches the public query");
                return IStructureElement.BlocksToPlace.create(Blocks.stone, 0);
            });
        chooser.offers(opaque, 3, 128, 0, new ItemStack(Items.stick, 7));
        chooser.offers(opaque, 4, 128, 0, new ItemStack(Items.stick, 7));
        SimpleAssert.eq(2, calls[0], "reviewed factory metadata is never a public native query gate");
    }

    private static void zeroPassesAreNotCompletion() throws IOException {
        HologramResourceWorld source = HologramResourceWorld.declarations(Blocks.bookshelf, 0);
        TileEntity tile = new TileEntity();
        tile.yCoord = 128;
        tile.setWorldObj(source);
        source.tile = tile;
        final int[] passes = { 0 };
        ISurvivalConstructable fixture = new ISurvivalConstructable() {

            @Override
            public int survivalConstruct(ItemStack signal, int budget, ISurvivalBuildEnvironment environment) {
                if (++passes[0] <= 4) return 0;
                tile.getWorldObj()
                    .setBlock(3, 128, 0, Blocks.stone, 0, 3);
                return -1;
            }

            @Override
            public void construct(ItemStack signal, boolean hints) {
                throw new AssertionError("0 must continue survival, not fall back to creative");
            }

            @Override
            public String[] getStructureDescription(ItemStack signal) {
                return new String[0];
            }
        };
        try (HologramNativeStructurePreview preview = new HologramNativeStructurePreview(
            tile,
            fixture,
            fixture,
            false,
            new ItemStack(Items.stick))) {
            for (int n = 0; n < 4; n++)
                SimpleAssert.that(!preview.advance(System.nanoTime() - 1), "zero-write cursor pass remains pending");
            SimpleAssert.that(preview.advance(System.nanoTime() - 1), "-1 completes despite a final write");
            SimpleAssert.eq(5, passes[0], "no redundant stability call after -1");
            SimpleAssert.eq(
                Blocks.stone,
                preview.placements()
                    .next()
                    .block(),
                "unvisited native write survives");
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
        try (HologramNativeStructurePreview preview = new HologramNativeStructurePreview(
            tile,
            fixture,
            fixture,
            false,
            trigger)) {
            SimpleAssert.that(!preview.advance(System.nanoTime() - 1), "NEI world changes continue on another step");
            SimpleAssert.eq(1, fixture.previewsStarted, "NEI pre-hook runs once before its first native pass");
            SimpleAssert.eq(0, fixture.previewsCompleted, "NEI completion waits for a no-change display pass");
            SimpleAssert
                .that(preview.advance(System.nanoTime() - 1), "NEI no-change pass completes the display lifecycle");
            SimpleAssert.eq(2, fixture.rounds, "NEI display follows native world changes across steps");
            SimpleAssert.eq(0, fixture.constructs, "survival-capable NEI follows its native browser branch");
            SimpleAssert.eq(1, fixture.previewsStarted, "resuming NEI never repeats its start hook");
            SimpleAssert.eq(1, fixture.previewsCompleted, "NEI completion contributes geometry once");
            var placements = preview.placements();
            SimpleAssert.eq(
                Blocks.stone,
                placements.next()
                    .block(),
                "native survival display placement was retained");
            SimpleAssert.eq(
                Blocks.glass,
                placements.next()
                    .block(),
                "GT completion hook's placement was retained");
            SimpleAssert.eq(
                9,
                ChannelDataAccessor.getChannelData(trigger, "gt_hatch"),
                "forcing hatch 1 leaves saved settings alone");
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
                SimpleAssert
                    .eq(1, ChannelDataAccessor.getChannelData(trigger, "gt_hatch"), "native GT preview forces hatch 1");
            }
            if (fallback) return -2;
            visit(world, rounds == 1 ? first : latest);
            if (rounds == 1) {
                SimpleAssert.eq(
                    IStructureElement.PlaceResult.ACCEPT,
                    StructureUtility.survivalPlaceBlock(
                        Blocks.stone,
                        0,
                        world,
                        1,
                        128,
                        0,
                        environment.getSource(),
                        environment.getActor()),
                    "detached creative source and actor can execute native survival placement");
                // The Large Solar Overpressure Array queries sky visibility inside its first build pass.
                SimpleAssert.that(world.canBlockSeeTheSky(1, 129, 0), "preview queries can see the empty sky");
                SimpleAssert.that(!world.canBlockSeeTheSky(1, -1, 0), "preview below the empty height map has no sky");
            }
            return rounds == 1 ? 0 : -1;
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
            tile.getWorldObj()
                .setBlock(2, 128, 0, Blocks.glass, 0, 2);
            previewsCompleted++;
        }

        private static void visit(World world, IStructureElement<?> element) {
            // The vanilla bootstrap does not install Forge's event constructor transformer.
            try {
                Constructor<StructureElementVisitedEvent> constructor = StructureElementVisitedEvent.class
                    .getDeclaredConstructor(
                        World.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
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
