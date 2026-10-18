package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.BlockTransforms;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.Declarations;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativeCapture;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Public-package registrations must drive actual capture/transform/persistence/authoring paths and retire cleanly. */
final class HologramCommunityApiRegression {

    private HologramCommunityApiRegression() {}

    static void run() throws Exception {
        int before = GTMBExtensions.descriptors()
            .size();
        declarationLifecycle();
        nativeLifecycle();
        processingLifecycle();
        legacyIdentityLifecycle();
        SimpleAssert.eq(
            before,
            GTMBExtensions.descriptors()
                .size(),
            "all public registrations retire without leaks");
    }

    private static void declarationLifecycle() {
        Wrapper wrapper = new Wrapper();
        HologramDeclarationCollector collector = new HologramDeclarationCollector();
        ItemStack signal = new ItemStack(Blocks.stone);
        SimpleAssert.that(
            collector.collect(wrapper, null, null, 0, 0, 0, "", signal, null).stacks.isEmpty(),
            "unknown wrapper has no placement candidates");
        GTMBExtensions.Registration handle = Declarations.registerReader(
            "gtmb_test:api-declaration",
            Wrapper.class,
            element -> new Declarations.Declaration(StructureUtility.ofBlock(Blocks.dirt, 0)));
        SimpleAssert.eq("declaration", handle.descriptor().kind, "registry describes extension kind");
        try {
            SimpleAssert.eq(
                1,
                collector.collect(wrapper, null, null, 0, 0, 0, "", signal, null).stacks.size(),
                "registration invalidates old empty candidate cache and uses public declaration");
            try {
                GTMBExtensions.nativeDisplay("gtmb_test:api-declaration", tile -> false);
                throw new AssertionError("duplicate extension ID accepted");
            } catch (IllegalArgumentException expected) {}
        } finally {
            handle.close();
        }
        handle.close();
        SimpleAssert.that(!handle.active(), "close is idempotent");
        SimpleAssert.that(
            collector.collect(wrapper, null, null, 0, 0, 0, "", signal, null).stacks.isEmpty(),
            "reader close invalidates both resolved declaration and collector caches");
    }

    private static void nativeLifecycle() throws Exception {
        boolean previouslySupported = HologramNativePlacement.supported(Blocks.stone);
        try (GTMBExtensions.Registration transform = BlockTransforms
            .register("gtmb_test:api-transform", new BlockTransforms.Adapter() {

                public boolean accepts(BlockTransforms.State state) {
                    state.metadata = 99;
                    return state.block == Blocks.stone;
                }

                public void rotate(BlockTransforms.State state, boolean vertical) {
                    state.metadata++;
                    if (state.tile != null) state.tile.setBoolean("turned", true);
                }
            });
            GTMBExtensions.Registration display = GTMBExtensions
                .nativeDisplay("gtmb_test:api-display", tile -> tile instanceof ApiTile);
            GTMBExtensions.Registration capture = NativeCapture
                .register("gtmb_test:api-capture", new NativeCapture.Adapter() {

                    public boolean supports(TileEntity tile) {
                        return tile instanceof ApiTile;
                    }

                    public NBTTagCompound capture(TileEntity tile) {
                        NBTTagCompound tag = new NBTTagCompound();
                        tag.setString("id", "api_native");
                        tag.setInteger("value", ((ApiTile) tile).value);
                        return tag;
                    }
                });
            GTMBExtensions.Registration placement = NativePlacement
                .register("gtmb_test:api-placement", new NativePlacement.Adapter() {

                    public boolean supports(Block block) {
                        return block == Blocks.stone;
                    }

                    public TileEntity create(Block block, int meta, NBTTagCompound tag, World world) {
                        return new ApiTile();
                    }

                    public java.util.List<ItemStack> materials(Block block, int meta, TileEntity tile) {
                        return new ArrayList<>();
                    }
                })) {
            NBTTagCompound original = new NBTTagCompound();
            HologramBlockTransformApi.RotationResult rotated = HologramBlockTransformApi
                .rotate(Blocks.stone, 0, original, false, 1);
            SimpleAssert.eq(1, rotated.metadata, "public transform enters production adapter chain");
            SimpleAssert.that(
                rotated.tile.getBoolean("turned") && !original.hasKey("turned"),
                "accepts and transform operate on detached data");
            ApiTile tile = new ApiTile();
            tile.value = 8;
            SimpleAssert
                .that(HologramNativeDisplayRegistry.supports(tile), "public display registration reaches preview gate");
            NBTTagCompound tag = HologramNativeCapture.capture(tile);
            SimpleAssert.eq(8, tag.getInteger("value"), "public capture is used by persistence boundary");
            SimpleAssert.eq(8, tile.value, "capture leaves source untouched");
            SimpleAssert.that(
                HologramNativePlacement.supported(Blocks.stone),
                "public placement adapter enters server resource registry");
        }
        SimpleAssert.eq(
            previouslySupported,
            HologramNativePlacement.supported(Blocks.stone),
            "placement handle restores prior registry");
        SimpleAssert.that(!HologramNativeDisplayRegistry.supports(new ApiTile()), "display family retires");
    }

    private static void legacyIdentityLifecycle() {
        java.util.function.Predicate<TileEntity> family = tile -> tile instanceof ApiTile;
        HologramNativeDisplayRegistry.register(family);
        try {
            GTMBExtensions.Registration handle = GTMBExtensions.nativeDisplay("gtmb_test:legacy-display", family);
            handle.close();
            SimpleAssert.that(
                HologramNativeDisplayRegistry.unregister(family),
                "public close retains legacy predicate identity");
            SimpleAssert
                .that(!HologramNativeDisplayRegistry.supports(new ApiTile()), "public close removes only its bridge");
        } finally {
            HologramNativeDisplayRegistry.unregister(family);
        }
        HologramNativePlacement.Adapter legacy = new HologramNativePlacement.Adapter() {

            public boolean supports(Block block) {
                return block == Blocks.dirt;
            }

            public TileEntity create(Block block, int meta, NBTTagCompound tag, World world) {
                return null;
            }

            public java.util.List<ItemStack> materials(Block block, int meta, TileEntity tile) {
                return new ArrayList<>();
            }
        };
        HologramNativePlacement.register("gtmb_test:legacy-placement", legacy);
        try {
            try {
                NativePlacement.register("gtmb_test:legacy-placement", new NativePlacement.Adapter() {

                    public boolean supports(Block block) {
                        return true;
                    }

                    public TileEntity create(Block block, int meta, NBTTagCompound tag, World world) {
                        return null;
                    }

                    public java.util.List<ItemStack> materials(Block block, int meta, TileEntity tile) {
                        return new ArrayList<>();
                    }
                });
                throw new AssertionError("public registration replaced legacy adapter");
            } catch (IllegalArgumentException expected) {}
            SimpleAssert
                .that(HologramNativePlacement.adapter(Blocks.dirt) == legacy, "legacy adapter remains registered");
            SimpleAssert.that(
                GTMBExtensions.descriptors()
                    .stream()
                    .noneMatch(entry -> entry.id.equals("gtmb_test:legacy-placement")),
                "failed legacy collision leaves no public descriptor");
        } finally {
            HologramNativePlacement.unregister("gtmb_test:legacy-placement", legacy);
        }
    }

    private static void processingLifecycle() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.schema = 2;
        source.name = "API processing fixture";
        source.controllerType = "area";
        HologramPresets.Cell saved = HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false);
        saved.block = String.valueOf(Block.blockRegistry.getNameForObject(Blocks.stone));
        NBTTagCompound tile = new NBTTagCompound();
        tile.setInteger("example", 7);
        saved.tileNbt = HologramBlueprintNbt.encodeCaptured(tile);
        source.cells.add(saved);
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        AtomicInteger called = new AtomicInteger();
        try (GTMBExtensions.Registration processor = BlueprintSnapshots
            .register("gtmb_test:api-processing", BlueprintSnapshots.Stage.AUTHORING_COMPLETE, snapshot -> {
                called.incrementAndGet();
                BlueprintSnapshots.Cell cell = snapshot.cells.get(0);
                NBTTagCompound copy = cell.tile();
                copy.setInteger("example", 99);
                SimpleAssert.eq(
                    7,
                    cell.tile()
                        .getInteger("example"),
                    "snapshot getters return independent NBT");
                try {
                    snapshot.cells.clear();
                    throw new AssertionError("mutable snapshot list");
                } catch (UnsupportedOperationException expected) {}
                NBTTagCompound report = new NBTTagCompound();
                report.setInteger("reviewed", snapshot.cells.size());
                return report;
            })) {
            HologramPresets.Preset processed = HologramBlueprintProcessing.process(source, policy, 0, 0, 64, 0);
            SimpleAssert.eq(1, called.get(), "authoring pipeline invokes public processor");
            SimpleAssert.eq(
                7,
                HologramBlueprintNbt.decode(processed.cells.get(0).tileNbt)
                    .getInteger("example"),
                "processor cannot mutate authoritative authoring output");
            SimpleAssert.eq(
                1,
                HologramBlueprintNbt.decode(processed.processingNbt)
                    .getCompoundTag("extensions")
                    .getCompoundTag("gtmb_test:api-processing")
                    .getInteger("reviewed"),
                "processing report is namespaced in receipt");
        }
        try (GTMBExtensions.Registration processor = BlueprintSnapshots.register(
            "gtmb_test:api-reject",
            BlueprintSnapshots.Stage.AUTHORING_COMPLETE,
            snapshot -> { throw new IOException("review needed"); })) {
            try {
                HologramBlueprintProcessing.process(source, policy, 0, 0, 64, 0);
                throw new AssertionError("processor rejection ignored");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("gtmb_test:api-reject"),
                    "rejection identifies extension");
            }
        }
        SimpleAssert.eq(
            7,
            HologramBlueprintNbt.decode(source.cells.get(0).tileNbt)
                .getInteger("example"),
            "failed processing preserves original source");
    }

    private static final class ApiTile extends TileEntity {

        int value;
    }

    private static final class Wrapper implements IStructureElement<Object> {

        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("check callback");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("hint callback");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("placement callback");
        }
    }
}
