package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.util.HatchElementBuilder;
import gtPlusPlus.core.block.base.BasicBlock;
import gtPlusPlus.core.block.base.BlockBaseModular;

final class HologramLazyDeclarationRegression {

    static void run() {
        try {
            ItemStack signal = new ItemStack(Items.feather);
            IStructureElement<Object> deferred = StructureUtility.lazy(
                context -> {
                    throw new AssertionError(
                        "deferred controller supplier must not execute during declaration capture");
                });
            SimpleAssert.that(
                HologramCasingFallback.resolve(deferred, signal) == null,
                "unreviewed uninitialized lazy declaration stays protected");
            HologramDeclarationCandidates unknown = new HologramDeclarationCandidates();
            unknown.collect(deferred, new Object(), null, 0, 0, 0, signal, null, 0);
            SimpleAssert.that(
                !unknown.complete && unknown.stacks.isEmpty(),
                "unreviewed lazy does not invent material candidates");
            HologramCapture capture = new HologramCapture(new Object(), signal);
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
            SimpleAssert.eq(
                false,
                hint.invoke(capture, deferred, null, 0, 0, 0, signal, 0),
                "hint capture never evaluates unreviewed deferred controller supplier");

            // StructureLib caches an already resolved declaration. Read that exact leaf without re-running its factory.
            Field initialized = deferred.getClass()
                .getDeclaredField("elem");
            initialized.setAccessible(true);
            initialized.set(deferred, StructureUtility.ofBlock(Blocks.stone, 0));
            IStructureElement<Object> casing = StructureUtility.onElementPass(
                context -> { throw new AssertionError("casing counter must not execute during declaration capture"); },
                deferred);
            IStructureElement<Object> hatch = HatchElementBuilder.<Object>builder()
                .adder((context, tile, texture) -> { throw new AssertionError("hatch adder must not execute"); })
                .casingIndex(69)
                .hint(1)
                .build();
            IStructureElement<Object> chain = StructureUtility.ofChain(hatch, casing);
            SimpleAssert.eq(
                Blocks.stone,
                HologramCasingFallback.resolve(chain, signal).block,
                "hatch chain recovers cached lazy casing despite first hatch hint");
            HologramDeclarationCandidates known = new HologramDeclarationCandidates();
            known.collect(chain, new Object(), null, 0, 0, 0, signal, null, 0);
            SimpleAssert.eq(1, known.stacks.size(), "cached lazy casing is included in chain material candidates");
            Object cached = initialized.get(deferred);
            SimpleAssert.that(
                HologramElementCatalog.parts(chain)
                    .stream()
                    .anyMatch(part -> part == cached),
                "catalog exposes lazy fixed declaration for construction and dismantling authority");
            SimpleAssert.eq(
                Blocks.dirt,
                HologramCasingFallback.resolve(StructureUtility.ofBlock(Blocks.dirt, 0), signal).block,
                "existing direct casing declarations retain their behavior");
            inertFrames(signal);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void inertFrames(ItemStack signal) throws ReflectiveOperationException {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        // Avoid the GT++ registration constructor; exercise the actual block implementation and native TE contract.
        BlockBaseModular frame = (BlockBaseModular) unsafe.allocateInstance(BlockBaseModular.class);
        frame.blockType = BasicBlock.BlockTypes.FRAME;
        Field provider = Block.class.getDeclaredField("isTileProvider");
        provider.setAccessible(true);
        provider.setBoolean(frame, true);
        SimpleAssert.that(
            frame.hasTileEntity(0) && frame.createNewTileEntity(null, 0) == null,
            "native GT++ frame advertises a tile provider but creates no tile");
        SimpleAssert.that(
            HologramFrameSupport.inertFrameBlock(frame, 0),
            "exact registered GT++ frame shape is an inert shell");
        IStructureElement<Object> declared = StructureUtility.ofBlock(frame, 0);
        SimpleAssert.eq(
            frame,
            HologramCasingFallback.resolve(declared, signal).block,
            "fixed declaration recovers the native inert GT++ frame");
        HologramCapture.Cell cell = new HologramCapture.Cell(declared, 0, 0, 0);
        cell.block = frame;
        Method destructive = HologramCellPolicy.class
            .getDeclaredMethod("destructive", HologramCapture.Cell.class, Block.class, int.class);
        destructive.setAccessible(true);
        SimpleAssert
            .eq(true, destructive.invoke(null, cell, frame, 0), "declared native inert frame can be dismantled");
        SimpleAssert.eq(false, destructive.invoke(null, cell, frame, 1), "undeclared frame metadata stays protected");
        BlockBaseModular standard = (BlockBaseModular) unsafe.allocateInstance(BlockBaseModular.class);
        standard.blockType = BasicBlock.BlockTypes.STANDARD;
        provider.setBoolean(standard, true);
        SimpleAssert.that(
            !HologramFrameSupport.inertFrameBlock(standard, 0),
            "ordinary GT++ BlockContainer does not gain frame authority");
        TileFrame subclass = (TileFrame) unsafe.allocateInstance(TileFrame.class);
        subclass.blockType = BasicBlock.BlockTypes.FRAME;
        provider.setBoolean(subclass, true);
        SimpleAssert.that(
            !HologramFrameSupport.inertFrameBlock(subclass, 0),
            "frame subclasses with a real tile remain protected");
        SimpleAssert.that(
            HologramCasingFallback.resolve(StructureUtility.ofBlock(standard, 0), signal) == null,
            "ordinary GT++ BlockContainer fallback stays protected");
        SimpleAssert.that(
            HologramCasingFallback.resolve(StructureUtility.ofBlock(subclass, 0), signal) == null,
            "arbitrary frame subclass fallback stays protected");
        // Item registry and ShellSource material extraction are exercised with fully constructed, registered GT++
        // Potin frames in the real industrial electrolyzer server smoke, including exact consumption and recovery.
    }

    private static final class TileFrame extends BlockBaseModular {

        private TileFrame() {
            super("test", "test", net.minecraft.block.material.Material.iron, BasicBlock.BlockTypes.FRAME, 0, 0);
        }

        @Override
        public net.minecraft.tileentity.TileEntity createNewTileEntity(World world, int meta) {
            return new net.minecraft.tileentity.TileEntity();
        }
    }
}
