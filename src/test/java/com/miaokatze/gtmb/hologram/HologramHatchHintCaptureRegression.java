// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.CommonProxy;
import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementNoPlacement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.util.HatchElementBuilder;

/** Real GT hatch factories reach the capture hook without evaluating controller callbacks. */
final class HologramHatchHintCaptureRegression {

    private HologramHatchHintCaptureRegression() {}

    @SuppressWarnings("unchecked")
    static void run() {
        try {
            Field activeField = HologramCapture.class.getDeclaredField("ACTIVE");
            Field proxyField = StructureLib.class.getDeclaredField("proxy");
            Field markerField = StructureLib.class.getDeclaredField("blockHint");
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            for (Field field : new Field[] { activeField, proxyField, markerField, unsafeField })
                field.setAccessible(true);
            ThreadLocal<HologramCapture> active = (ThreadLocal<HologramCapture>) activeField.get(null);
            HologramCapture previousCapture = active.get();
            Object previousProxy = proxyField.get(null), previousMarker = markerField.get(null);
            Method hint = HologramCapture.class.getDeclaredMethod(
                "spawnHint",
                IStructureElement.class,
                World.class,
                int.class,
                int.class,
                int.class,
                ItemStack.class,
                int.class);
            hint.setAccessible(true);
            try {
                // The headless suite has no mixins. Mirror only MixinHologramHints' forwarding to the real hook.
                proxyField.set(null, ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(CaptureProxy.class));
                markerField.set(null, Blocks.glass);
                for (boolean filtered : new boolean[] { false, true }) {
                    HatchElementBuilder<Object> builder = HatchElementBuilder.<Object>builder()
                        .adder((controller, tile, texture) -> { throw new AssertionError("hatch adder executed"); })
                        .casingIndex(1)
                        .hint(filtered ? 3 : 2)
                        .hint(() -> { throw new AssertionError("hatch label supplier executed"); })
                        .description(() -> { throw new AssertionError("hatch description executed"); });
                    if (filtered) builder.hatchItemFilter(
                        (controller, trigger) -> { throw new AssertionError("hatch filter executed"); });
                    IStructureElement<Object> element = builder.build();
                    ItemStack signal = new ItemStack(Items.feather);
                    HologramCapture capture = new HologramCapture(new Object(), signal);
                    capture.current = new HologramCapture.Cell(element, 0, 64, 0, signal);
                    active.set(capture);
                    SimpleAssert.eq(
                        true,
                        hint.invoke(capture, element, null, 0, 64, 0, signal, 0),
                        "each real builder branch emits its fixed preview marker through the production dispatcher");
                    SimpleAssert.eq(Blocks.glass, capture.current.block, "the capture hook retains the actual marker");
                    SimpleAssert.eq(filtered ? 2 : 1, capture.current.hintIndex, "the original marker index survives");
                    SimpleAssert.eq(1, capture.nativeHintCalls, "exactly one reviewed native hint is called");
                    SimpleAssert.that(
                        !capture.current.candidateComplete && capture.current.candidates == null,
                        "a visible marker supplies no material declaration or construction authority");
                    IStructureElement<Object> unknown = new IStructureElementNoPlacement<Object>() {

                        @Override
                        public boolean check(Object controller, World world, int x, int y, int z) {
                            throw new AssertionError("unknown check executed");
                        }

                        @Override
                        public boolean spawnHint(Object controller, World world, int x, int y, int z,
                            ItemStack trigger) {
                            throw new AssertionError("unknown hint executed");
                        }
                    };
                    SimpleAssert.eq(
                        false,
                        hint.invoke(capture, unknown, null, 0, 64, 0, signal, 0),
                        "unreviewed leaves remain outside the preview callback path");
                    SimpleAssert
                        .eq(1, capture.nativeHintCalls, "the exact factory exception does not admit unknown hints");
                }
                requiredMarkers((sun.misc.Unsafe) unsafeField.get(null), active);
                sheetMetal((sun.misc.Unsafe) unsafeField.get(null), active);
            } finally {
                if (previousCapture == null) active.remove();
                else active.set(previousCapture);
                proxyField.set(null, previousProxy);
                markerField.set(null, previousMarker);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("fixed hatch hint capture", failure);
        }
    }

    private static void requiredMarkers(sun.misc.Unsafe unsafe, ThreadLocal<HologramCapture> active)
        throws ReflectiveOperationException {
        HatchElementBuilder<Object> builder = HatchElementBuilder.<Object>builder()
            .adder((controller, tile, texture) -> { throw new AssertionError("mandatory hatch adder executed"); })
            .casingIndex(11)
            .hint(2);
        HologramHatchPolicy
            .declare(builder, java.util.Collections.singletonList(gregtech.api.enums.HatchElement.Muffler), false);
        IStructureElement<Object> declared = builder.build();
        HologramHatchPolicy.built(builder, declared);
        HologramCapture.Cell known = marker(unsafe, active, declared);
        SimpleAssert.that(
            !known.unknown && "hatch".equals(known.role),
            "a complete mandatory hatch declaration is not an unknown block");
        SimpleAssert
            .that(known.block == Blocks.glass && known.hintIndex == 1, "mandatory hatch keeps its native hint marker");
        SimpleAssert.that(
            !known.candidateComplete && known.candidates == null,
            "known hatch appearance does not preapprove candidate enumeration");
        SimpleAssert.that(
            !HologramCellPolicy.placeableSample(null, known, new ItemStack(Blocks.glass)),
            "a known marker is still not a payable placement item");
        IStructureElement<Object> wrapped = StructureUtility.withDescription(
            java.util.Collections.singletonList("mandatory hatch"),
            StructureUtility.withChannel("marker", declared));
        SimpleAssert
            .that(!marker(unsafe, active, wrapped).unknown, "transparent wrappers retain a complete hatch declaration");
        IStructureElement<Object> unknown = new IStructureElementNoPlacement<Object>() {

            public boolean check(Object controller, World world, int x, int y, int z) {
                throw new AssertionError("unreviewed marker check executed");
            }

            public boolean spawnHint(Object controller, World world, int x, int y, int z, ItemStack trigger) {
                throw new AssertionError("unreviewed marker hint executed");
            }
        };
        SimpleAssert.that(marker(unsafe, active, unknown).unknown, "an arbitrary leaf remains unknown");
        SimpleAssert.that(
            marker(unsafe, active, StructureUtility.ofChain(declared, unknown)).unknown,
            "a trusted hatch branch cannot certify an unknown fallback chain");
        HatchElementBuilder<Object> filtered = HatchElementBuilder.<Object>builder()
            .adder((controller, tile, texture) -> { throw new AssertionError("filtered hatch adder executed"); })
            .casingIndex(11)
            .hint(2);
        HologramHatchPolicy
            .declare(filtered, java.util.Collections.singletonList(gregtech.api.enums.HatchElement.Muffler), false);
        HologramHatchPolicy
            .andFunction(filtered, context -> { throw new AssertionError("arbitrary hatch filter executed"); });
        IStructureElement<Object> filteredLeaf = filtered.build();
        HologramHatchPolicy.built(filtered, filteredLeaf);
        SimpleAssert
            .that(marker(unsafe, active, filteredLeaf).unknown, "an unrecoverable filter keeps its marker unknown");
        HatchElementBuilder<Object> unrecorded = HatchElementBuilder.<Object>builder()
            .adder((controller, tile, texture) -> { throw new AssertionError("unrecorded hatch adder executed"); })
            .casingIndex(11)
            .hint(2);
        SimpleAssert.that(
            marker(unsafe, active, unrecorded.build()).unknown,
            "a marker with no recorded declaration stays unknown");
    }

    private static HologramCapture.Cell marker(sun.misc.Unsafe unsafe, ThreadLocal<HologramCapture> active,
        IStructureElement<Object> element) throws ReflectiveOperationException {
        World world = (World) unsafe.allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        return marker(active, element, world);
    }

    private static HologramCapture.Cell marker(ThreadLocal<HologramCapture> active, IStructureElement<Object> element,
        World world) throws ReflectiveOperationException {
        HologramCapture.Cell full = marker(active, element, world, false);
        HologramCapture.Cell projection = marker(active, element, world, true);
        SimpleAssert.that(
            full.block == projection.block && full.meta == projection.meta
                && full.unknown == projection.unknown
                && full.role.equals(projection.role),
            "required/optional hatch and extended sheet-metal declarations survive both native capture paths");
        return projection;
    }

    private static HologramCapture.Cell marker(ThreadLocal<HologramCapture> active, IStructureElement<Object> element,
        World world, boolean projection) throws ReflectiveOperationException {
        ItemStack signal = new ItemStack(Items.feather);
        java.lang.reflect.Constructor<HologramCapture> constructor = HologramCapture.class.getDeclaredConstructor(
            Object.class,
            ItemStack.class,
            com.miaokatze.gtmb.api.StructureSources.CaptureIntent.class,
            boolean.class);
        constructor.setAccessible(true);
        HologramCapture capture = constructor.newInstance(
            new Object(),
            signal,
            com.miaokatze.gtmb.api.StructureSources.CaptureIntent.NEW_TEMPLATE,
            projection);
        capture.pieces.add(new HologramCapture.Piece("main", 0, 0, 0, signal));
        IStructureDefinition<Object> definition = StructureDefinition.<Object>builder()
            .addShape("main", new String[][] { { "X" } })
            .addElement('X', element)
            .build();
        Method walk = HologramCapture.class.getDeclaredMethod(
            "walkPieces",
            HologramCapture.class,
            IStructureDefinition.class,
            World.class,
            ExtendedFacing.class,
            int.class,
            int.class,
            int.class);
        walk.setAccessible(true);
        active.set(capture);
        walk.invoke(null, capture, definition, world, ExtendedFacing.DEFAULT, 0, 64, 0);
        return capture.cells.get(0);
    }

    private static void sheetMetal(sun.misc.Unsafe unsafe, ThreadLocal<HologramCapture> active)
        throws ReflectiveOperationException {
        Block original = gregtech.api.GregTechAPI.sBlockSheetmetalGT;
        int originalMaterialId = gregtech.api.enums.Materials.Tungsten.mMetaItemSubID;
        try {
            // Vanilla bootstrap does not run Materials.init()/MaterialsIDMap.register(). This serial test owns
            // its material ID and registered metadata-preserving block/item pair, and restores the native inputs.
            Block sheetBlock = sheetBlock();
            gregtech.api.enums.Materials.Tungsten.mMetaItemSubID = 81;
            gregtech.api.GregTechAPI.sBlockSheetmetalGT = sheetBlock;
            IStructureElement<Object> sheet = gregtech.api.util.GTStructureUtility
                .ofSheetMetal(gregtech.api.enums.Materials.Tungsten);
            IStructureElement<?> delegate = HologramDeclarationWrappers.backing(sheet);
            SimpleAssert.that(
                delegate == ((gregtech.api.util.GTStructureUtility.ProxyStructureElement<?, ?>) sheet).proxiedElement,
                "sheet metal unwraps only its real native fixed-block delegate");
            int metadata = gregtech.api.enums.Materials.Tungsten.mMetaItemSubID;
            SimpleAssert.that(metadata > 15, "the native sheet-metal material uses extended metadata");
            SheetWorld world = (SheetWorld) unsafe.allocateInstance(SheetWorld.class);
            world.sheet = sheetBlock;
            world.metadata = metadata;
            HologramCapture.Cell cell = marker(active, sheet, world);
            SimpleAssert.that(
                !cell.unknown && cell.block == sheetBlock,
                "a reviewed sheet-metal proxy has known native appearance");
            SimpleAssert.eq(metadata, cell.meta, "sheet-metal hint metadata is not truncated to four bits");
            ItemStack material = HologramBlockIdentity.materialStack(cell.block, cell.meta);
            SimpleAssert.eq(
                metadata,
                sheetBlock.getDamageValue(world, 0, 64, 0),
                "the controlled sheet world preserves extended material metadata");
            SimpleAssert.eq(
                metadata,
                material.getItem()
                    .getMetadata(material.getItemDamage()),
                "the registered sheet item maps the complete metadata back to the world");
            SimpleAssert.eq(
                metadata,
                material.getItemDamage(),
                "sheet-metal payment identity retains its complete material metadata");
            HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
            candidates.collect(sheet, null, null, 0, 0, 0, new ItemStack(Items.feather), null, 0);
            SimpleAssert.that(
                candidates.complete && candidates.stacks.size() == 1
                    && candidates.allows(material, false)
                    && candidates.allows(material, true),
                "the exact sheet-metal contract is the native delegate's single fixed material");
            SimpleAssert.that(
                HologramCasingFallback.resolve(sheet, new ItemStack(Items.feather)) == null,
                "sheet-metal support does not widen the ordinary casing fallback metadata boundary");
            IStructureElement<Object> custom = new gregtech.api.util.GTStructureUtility.ProxyStructureElement<Object, IStructureElement<Object>>(
                StructureUtility.ofBlock(sheetBlock, metadata)) {

                @Override
                public boolean check(Object controller, World world, int x, int y, int z) {
                    throw new AssertionError("custom proxy check executed");
                }

                @Override
                public boolean spawnHint(Object controller, World world, int x, int y, int z, ItemStack trigger) {
                    throw new AssertionError("custom proxy hint executed");
                }
            };
            SimpleAssert.that(
                HologramDeclarationWrappers.backing(custom) == null && marker(unsafe, active, custom).unknown,
                "an arbitrary proxy cannot borrow the reviewed sheet-metal factory's authority");
        } finally {
            gregtech.api.GregTechAPI.sBlockSheetmetalGT = original;
            gregtech.api.enums.Materials.Tungsten.mMetaItemSubID = originalMaterialId;
        }
    }

    private static Block sheetBlock() {
        String name = "gtmb:sheetmetal_hint_fixture";
        Object existing = Block.blockRegistry.getObject(name);
        if (existing instanceof SheetBlock) return (Block) existing;
        int slot = 3960;
        while (slot > 0
            && (net.minecraft.item.Item.getItemById(slot) != null || Block.getBlockById(slot) != Blocks.air)) slot--;
        if (slot == 0) throw new AssertionError("No isolated sheet-metal fixture registry slot");
        SheetBlock block = new SheetBlock();
        Block.blockRegistry.addObject(slot, name, block);
        net.minecraft.item.Item.itemRegistry.addObject(slot, name, new net.minecraft.item.ItemBlock(block) {

            @Override
            public int getMetadata(int metadata) {
                return metadata;
            }
        }.setHasSubtypes(true));
        return block;
    }

    private static final class SheetBlock extends Block {

        private SheetBlock() {
            super(net.minecraft.block.material.Material.iron);
            setBlockName("gtmb_sheetmetal_hint_fixture");
        }

        @Override
        public int damageDropped(int metadata) {
            return metadata;
        }
    }

    private static final class SheetWorld extends net.minecraft.world.WorldServer {

        Block sheet;
        int metadata;

        private SheetWorld() {
            super(null, null, "sheet_fixture", 0, null, null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return sheet;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return metadata;
        }

        @Override
        public net.minecraft.tileentity.TileEntity getTileEntity(int x, int y, int z) {
            return null;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
            throw new AssertionError("sheet-metal declaration capture must not write the world");
        }
    }

    private static final class CaptureProxy extends CommonProxy {

        @Override
        public void hintParticle(World world, int x, int y, int z, Block block, int meta) {
            SimpleAssert
                .that(HologramCapture.hint(world, x, y, z, block, meta), "the active capture consumes the hint");
        }
    }
}
