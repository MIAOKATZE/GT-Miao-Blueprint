// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Real compiler coverage for custom cursor constructors without a definition or visit events. */
final class HologramNativeTargetPlanRegression {

    private static final int ID = 32666;

    static void run() {
        Thread thread = Thread.currentThread();
        String previousName = thread.getName();
        // Native GTUtility.isClient uses FML's effective thread side, not FMLRelaunchLog's bootstrap side.
        thread.setName("Server thread");
        try {
            SimpleAssert.eq(
                cpw.mods.fml.relauncher.Side.SERVER,
                cpw.mods.fml.common.FMLCommonHandler.instance()
                    .getEffectiveSide(),
                "native target fixtures execute with FML's actual server-thread semantics");
            HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                try {
                    verify();
                    verifyIncompleteCapture();
                    verifyRawChoices();
                    verifyWater();
                } catch (IOException | ReflectiveOperationException failure) {
                    throw new AssertionError("native target generation", failure);
                }
            });
        } catch (Exception failure) {
            throw new AssertionError("native target registry fixture", failure);
        } finally {
            thread.setName(previousName);
        }
    }

    private static HologramNativeTargetPlan compile(boolean footprint) throws IOException {
        ItemStack trigger = HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound());
        return footprint ? HologramNativeTargetPlan.compileFootprint(ID, ExtendedFacing.DEFAULT, trigger)
            : HologramNativeTargetPlan.compile(ID, ExtendedFacing.DEFAULT, trigger, new NBTTagCompound());
    }

    static void verifyAdoptedSiteMaterial() throws IOException {
        Block previousBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[ID];
        try {
            GregTechAPI.sBlockMachines = Blocks.iron_block;
            prototype(ID, new Controller(13));
            var shellChannel = new NBTTagCompound();
            shellChannel.setInteger("shell", 1);
            var signal = HologramNativeTargetPlan.canonicalTrigger(1, shellChannel);
            var initial = HologramNativeTargetPlan.compile(ID, ExtendedFacing.DEFAULT, signal, new NBTTagCompound());
            var original = initial.cells()
                .stream()
                .filter(cell -> cell.block() == Blocks.wool)
                .findFirst()
                .orElseThrow(() -> new AssertionError("native wool declaration missing from target"));
            SimpleAssert.that(original.element() != null, "adopted material fixture recorded a real native element");
            SimpleAssert.eq(Blocks.wool, Block.getBlockFromName("minecraft:wool"), "native wool registry identity");
            var adopted = new NBTTagCompound();
            var choices = new net.minecraft.nbt.NBTTagList();
            var row = new NBTTagCompound();
            row.setInteger("x", original.dx());
            row.setInteger("y", original.dy());
            row.setInteger("z", original.dz());
            row.setString("block", "minecraft:wool");
            row.setInteger("meta", 7);
            choices.appendTag(row);
            adopted.setTag("siteActualBlocks", choices);
            var target = HologramNativeTargetPlan.compile(ID, ExtendedFacing.DEFAULT, signal, adopted);
            var material = target.cells()
                .stream()
                .filter(cell -> cell.block() == Blocks.wool)
                .findFirst()
                .orElseThrow(() -> new AssertionError("adopted wool missing from target"));
            SimpleAssert.eq(7, material.metadata(), "native any-meta cell adopts the installed material");
            SimpleAssert.eq(
                7,
                material.material()
                    .getItemDamage(),
                "actual material reaches payment identity");
            SimpleAssert.that(
                !initial.targetDigest()
                    .equals(target.targetDigest()),
                "adopted material changes execution digest");
            verifyTypedSiteMaterial(original, signal);
            row.setString("block", "minecraft:stone");
            try {
                HologramNativeTargetPlan.compile(ID, ExtendedFacing.DEFAULT, signal, adopted);
                throw new AssertionError("native wool declaration must reject unrelated site block");
            } catch (IOException expected) {
                SimpleAssert.that(expected.getMessage() != null, "invalid site material fails explicitly");
            }
        } finally {
            GregTechAPI.METATILEENTITIES[ID] = previous;
            GregTechAPI.sBlockMachines = previousBlock;
        }
    }

    private static void verifyTypedSiteMaterial(HologramNativeTargetPlan.Cell original, ItemStack signal)
        throws IOException {
        var actual = new HologramNativeTargetPlan.Cell(
            original.dx(),
            original.dy(),
            original.dz(),
            Blocks.wool,
            7,
            new ItemStack(Blocks.wool, 1, 7),
            -1,
            null,
            null,
            original.element(),
            signal,
            false);
        var site = new NBTTagCompound();
        site.setBoolean("siteKnown", true);
        site.setBoolean("siteMainRecovered", true);
        site.setInteger("main", 1);
        var originalChannels = new NBTTagCompound();
        originalChannels.setInteger("shell", 1);
        site.setTag("channels", originalChannels);
        site.setBoolean("mainExplicit", true); // An old preference is not a new size edit.
        for (boolean recovered : new boolean[] { true, false }) {
            site.setBoolean("siteMainRecovered", recovered);
            for (String channel : new String[] { "", "unrelated", "shell" }) {
                var requested = (NBTTagCompound) site.copy();
                var channels = (NBTTagCompound) originalChannels.copy();
                if (!channel.isEmpty()) channels.setInteger(channel, 2);
                requested.setTag("channels", channels);
                try (var retained = HologramNativeTargetPlan.begin(
                    ID,
                    ExtendedFacing.DEFAULT,
                    HologramNativeTargetPlan.canonicalTrigger(1, channels),
                    requested,
                    java.util.Collections.singletonList(actual),
                    site)) {
                    SimpleAssert.that(retained.advance(Long.MAX_VALUE), "typed actual material edit completes");
                    SimpleAssert.eq(
                        1,
                        retained.cells()
                            .size(),
                        "typed baseline keeps exact footprint");
                    var material = retained.cells()
                        .get(0);
                    int expected = channel.equals("shell") ? 0 : 7;
                    SimpleAssert
                        .eq(expected, material.metadata(), "only the explicitly edited native material domain changes");
                    SimpleAssert.eq(
                        expected,
                        material.material()
                            .getItemDamage(),
                        "retained material reaches exact payment identity");
                }
            }
        }
        var outsideDefaultShape = new HologramNativeTargetPlan.Cell(
            actual.dx() + 1,
            actual.dy(),
            actual.dz(),
            Blocks.wool,
            7,
            actual.material(),
            -1,
            null,
            null,
            actual.element(),
            signal,
            false);
        var requested = (NBTTagCompound) site.copy();
        var channels = (NBTTagCompound) originalChannels.copy();
        channels.setInteger("unrelated", 2);
        requested.setTag("channels", channels);
        try (var guarded = HologramNativeTargetPlan.begin(
            ID,
            ExtendedFacing.DEFAULT,
            HologramNativeTargetPlan.canonicalTrigger(1, channels),
            requested,
            java.util.Arrays.asList(actual, outsideDefaultShape),
            site)) {
            try {
                guarded.advance(Long.MAX_VALUE);
                throw new AssertionError("unknown main must not silently shrink an actual larger shape");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("主尺寸"),
                    "shape guard reports the missing main evidence");
            }
        }
    }

    private static void verify() throws IOException {
        Block previousBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[ID];
        try {
            GregTechAPI.sBlockMachines = Blocks.iron_block;
            prototype(ID, new Controller(0));
            HologramNativeTargetPlan target = compile(false);
            SimpleAssert.eq(null, target.definition(), "custom constructor needs no definition");
            SimpleAssert.eq(
                2,
                target.cells()
                    .size(),
                "unvisited real writes form the complete target");
            Controller built = (Controller) target.context();
            SimpleAssert.eq(134, built.passes, "long read-only native verification reaches -1 without default replay");
            SimpleAssert.eq(0, built.started + built.completed, "ordinary targets never run NEI hooks");
            for (var cell : target.cells()) {
                SimpleAssert.eq(null, cell.element(), "custom targets retain optional element absence");
                SimpleAssert.that(!cell.requiredAir(), "sparse unvisited blank space is never required air");
                SimpleAssert.eq(
                    cell.dx() == 1 ? "replacement" : "second",
                    cell.material()
                        .getTagCompound()
                        .getString("nativeMaterial"),
                    "exact material NBT follows the write coordinate, including same-coordinate replacement");
            }
            SimpleAssert.eq(target.footprint(), compile(true).footprint(), "footprint reuses completed native output");
            SimpleAssert.that(
                !target.footprint()
                    .contains(new HologramNativeTargetPlan.Offset(7, 0, 0)),
                "hint-only writes cannot become destruction authority");

            prototype(ID, new Controller(1));
            HologramNativeTargetPlan creative = compile(false);
            SimpleAssert.eq(
                1,
                creative.cells()
                    .size(),
                "unsupported survival prefix is discarded");
            SimpleAssert.eq(
                Blocks.gold_block,
                creative.cells()
                    .get(0)
                    .block(),
                "fresh creative result is selected");
            SimpleAssert.eq(0, ((Controller) creative.context()).passes, "creative fallback owns a new controller");

            for (int mode : new int[] { 2, 3, 4, 10 }) {
                prototype(ID, new Controller(mode));
                Controller.nativeCalls = 0;
                try {
                    compile(false);
                    throw new AssertionError("accepted incomplete/invalid native mode " + mode);
                } catch (IOException expected) {
                    if (mode == 2 || mode == 10) SimpleAssert.eq(
                        2000,
                        Controller.nativeCalls,
                        "non-completing and self-removing constructors retain the existing total-call limit");
                    SimpleAssert.that(
                        !com.gtnewhorizon.structurelib.StructureLibAPI.isInstrumentEnabled(),
                        "failed generation restores the instrument scope");
                }
            }

            prototype(ID, new Controller(0));
            HologramResourceWorld base = HologramResourceWorld.declarations(Blocks.iron_block, 0);
            BaseMetaTileEntity tile = new BaseMetaTileEntity();
            tile.setWorldObj(base);
            tile.yCoord = 128;
            base.tile = tile;
            tile.setInitialValuesAsNBT(null, (short) ID);
            Controller context = (Controller) tile.getMetaTileEntity();
            try (var preview = new HologramNativeStructurePreview(
                tile,
                context,
                context,
                false,
                HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound()))) {
                SimpleAssert.that(preview.advance(Long.MAX_VALUE), "NEI-only completion creates display geometry");
                SimpleAssert.eq(1, context.started, "NEI start runs once");
                SimpleAssert.eq(1, context.completed, "NEI completion runs once");
                SimpleAssert.eq(
                    1,
                    context.passes,
                    "NEI finishes its native hook lifecycle without an extra unchanged survival pass");
                var shown = preview.placements();
                SimpleAssert.eq(
                    Blocks.glass,
                    shown.next()
                        .block(),
                    "completion creates the entire display geometry");
                SimpleAssert.that(!shown.hasNext(), "NEI examples never enter ordinary target generation");
                SimpleAssert.eq(base, tile.getWorldObj(), "display restores the detached base world");
            }
        } finally {
            GregTechAPI.sBlockMachines = previousBlock;
            GregTechAPI.METATILEENTITIES[ID] = previous;
        }
    }

    private static void verifyIncompleteCapture() throws IOException, ReflectiveOperationException {
        Block oldBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity old = GregTechAPI.METATILEENTITIES[ID];
        HologramResourceWorld world = HologramResourceWorld.declarations(Blocks.iron_block, 0);
        try {
            GregTechAPI.sBlockMachines = Blocks.iron_block;
            prototype(ID, new Controller(2));
            BaseMetaTileEntity tile = new BaseMetaTileEntity();
            tile.setWorldObj(world);
            tile.yCoord = 128;
            world.tile = tile;
            tile.setInitialValuesAsNBT(null, (short) ID);
            world.isRemote = true;
            ItemStack tool = new ItemStack(net.minecraft.init.Items.stick);
            try (var capture = new HologramLocalControllerCapture(
                tile,
                0,
                2,
                tool,
                HologramLocalControllerCapture.toolSettings(tool),
                "incomplete-target")) {
                SimpleAssert.eq(null, capture.stepTarget(1, 1), "first native stage remains pending");
                var field = HologramLocalControllerCapture.class.getDeclaredField("nativeTarget");
                field.setAccessible(true);
                var plan = (HologramNativeTargetPlan) field.get(capture);
                SimpleAssert.that(plan != null && !plan.ready(), "the original null-digest failure phase exists");
                SimpleAssert.eq(null, plan.targetDigest(), "the incomplete plan has no invented digest");
                assertUnavailableTarget(capture.opening());
                assertUnavailableTarget(capture.unavailable("fixture pending failure"));
                try {
                    capture.stepTarget(1, Long.MAX_VALUE / 2);
                    throw new AssertionError("non-completing native target became ready");
                } catch (IOException expected) {
                    SimpleAssert.eq("LOCAL_NATIVE_PREVIEW_UNSTABLE", expected.getMessage(), "original total limit");
                }
                assertUnavailableTarget(capture.unavailable("fixture native failure"));
                capture.close();
                assertUnavailableTarget(capture.unavailable("fixture cancelled target"));
            }
        } finally {
            world.isRemote = false;
            GregTechAPI.sBlockMachines = oldBlock;
            GregTechAPI.METATILEENTITIES[ID] = old;
        }
    }

    private static void assertUnavailableTarget(NBTTagCompound state) {
        SimpleAssert.that(!state.getBoolean("nativeTargetReady"), "pending/failed/cancelled target is not ready");
        SimpleAssert.that(!state.getBoolean("nativeExecutionSupported"), "empty cells never authorize execution");
        SimpleAssert
            .that(!state.hasKey("nativeTargetDigest"), "failure metadata never publishes a null or stale digest");
    }

    private static void verifyWater() throws IOException {
        Block oldBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity old = GregTechAPI.METATILEENTITIES[ID];
        try {
            GregTechAPI.sBlockMachines = Blocks.iron_block;
            prototype(ID, new Controller(7));
            var target = compile(false);
            SimpleAssert.that(
                target.cells()
                    .stream()
                    .noneMatch(cell -> cell.dx() == 1),
                "actual native air acceptance excludes optional structure water");
            SimpleAssert.that(
                target.cells()
                    .stream()
                    .anyMatch(cell -> cell.dx() == 2 && cell.block() == Blocks.water)
                    && target.cells()
                        .stream()
                        .anyMatch(cell -> cell.dx() == 3 && cell.block() == Blocks.water),
                "required literal water and raw water without air evidence remain in the geometry");
        } finally {
            GregTechAPI.sBlockMachines = oldBlock;
            GregTechAPI.METATILEENTITIES[ID] = old;
        }
    }

    private static void visited(World world, int x,
        com.gtnewhorizon.structurelib.structure.IStructureElement<?> element) {
        try {
            var constructor = com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent.class
                .getDeclaredConstructor(
                    World.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    com.gtnewhorizon.structurelib.structure.IStructureElement.class);
            constructor.setAccessible(true);
            new HologramNativeStructurePreview.VisitedListener()
                .elementVisited(constructor.newInstance(world, x, 128, 0, x, 0, 0, element));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void prototype(int id, IMetaTileEntity prototype) {
        // Mirror MetaTileEntity's native registration constructor while retaining clone constructors for the fixture.
        BaseMetaTileEntity base = new BaseMetaTileEntity();
        prototype.setBaseMetaTileEntity(base);
        base.setMetaTileID((short) id);
        GregTechAPI.METATILEENTITIES[id] = prototype;
    }

    private static final int RAW_ID = 32660, PORT_ID = 32661, REJECT_ID = 32662, MUFFLER_ID = 32663;

    private static void verifyRawChoices() throws IOException {
        Block oldBlock = GregTechAPI.sBlockMachines;
        java.util.Map<Class<?>, String> nativeNames;
        java.util.Map<String, Class<?>> nativeClasses;
        String fixtureName = "gtmb_native_target_fixture";
        boolean addedMapping;
        try {
            @SuppressWarnings("unchecked")
            var names = (java.util.Map<Class<?>, String>) cpw.mods.fml.relauncher.ReflectionHelper
                .findField(net.minecraft.tileentity.TileEntity.class, "classToNameMap", "field_145853_j")
                .get(null);
            @SuppressWarnings("unchecked")
            var classes = (java.util.Map<String, Class<?>>) cpw.mods.fml.relauncher.ReflectionHelper
                .findField(net.minecraft.tileentity.TileEntity.class, "nameToClassMap", "field_145855_i")
                .get(null);
            nativeNames = names;
            nativeClasses = classes;
            addedMapping = !names.containsKey(BaseMetaTileEntity.class);
            if (addedMapping) {
                var mapping = net.minecraft.tileentity.TileEntity.class
                    .getDeclaredMethod("addMapping", Class.class, String.class);
                mapping.setAccessible(true);
                mapping.invoke(null, BaseMetaTileEntity.class, fixtureName);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native tile registry fixture", failure);
        }
        IMetaTileEntity oldId = GregTechAPI.METATILEENTITIES[ID], oldController = GregTechAPI.METATILEENTITIES[RAW_ID],
            oldPort = GregTechAPI.METATILEENTITIES[PORT_ID], oldReject = GregTechAPI.METATILEENTITIES[REJECT_ID],
            oldMuffler = GregTechAPI.METATILEENTITIES[MUFFLER_ID];
        try {
            Block block = new Block(net.minecraft.block.material.Material.iron) {

                @Override
                public boolean hasTileEntity(int meta) {
                    return true;
                }

                @Override
                public net.minecraft.tileentity.TileEntity createTileEntity(World world, int meta) {
                    return new BaseMetaTileEntity();
                }
            };
            Block.blockRegistry.addObject(4061, "gtmb:projector_raw_fixture", block);
            net.minecraft.item.Item.itemRegistry
                .addObject(4061, "gtmb:projector_raw_fixture", new gregtech.common.blocks.ItemMachines(block));
            GregTechAPI.sBlockMachines = block;
            prototype(RAW_ID, new Controller(6));
            prototype(
                PORT_ID,
                new gregtech.api.metatileentity.implementations.MTEHatchOutput(
                    "raw_port",
                    1,
                    new String[0],
                    new ITexture[0][][]));
            prototype(
                REJECT_ID,
                new gregtech.api.metatileentity.implementations.MTEHatchEnergy(
                    "raw_reject",
                    1,
                    new String[0],
                    new ITexture[0][][]));
            prototype(
                MUFFLER_ID,
                new gregtech.api.metatileentity.implementations.MTEHatchMuffler(
                    "required_muffler",
                    1,
                    new String[0],
                    new ITexture[0][][]));
            NBTTagCompound channels = new NBTTagCompound();
            channels.setInteger("ore_center_sectors", 5);
            ItemStack signal = HologramNativeTargetPlan.canonicalTrigger(4, channels);
            HologramNativeTargetPlan raw = HologramNativeTargetPlan
                .compile(RAW_ID, ExtendedFacing.DEFAULT, signal, new NBTTagCompound());
            SimpleAssert.that(
                raw.cells()
                    .stream()
                    .anyMatch(cell -> cell.dx() == 7),
                "raw constructor receives sector five independently of main four");
            SimpleAssert.that(
                raw.cells()
                    .stream()
                    .allMatch(cell -> cell.element() == null),
                "raw geometry and hatch seeds need no visited element");
            SimpleAssert.that(
                raw.cells()
                    .stream()
                    .noneMatch(
                        cell -> com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                            .hasSubChannel(cell.trigger(), "gt_hatch")),
                "discovery hatch opt-in never leaks into published native target signals");
            SimpleAssert.that(
                raw.cells()
                    .stream()
                    .filter(cell -> cell.dx() == 1)
                    .allMatch(cell -> cell.block() == Blocks.stone),
                "NEI seed is restored to the exact native default casing");
            NBTTagCompound config = new NBTTagCompound(), choice = new NBTTagCompound();
            choice.setInteger("x", 1);
            choice.setInteger("id", PORT_ID);
            choice.setInteger("facing", -1);
            net.minecraft.nbt.NBTTagList choices = new net.minecraft.nbt.NBTTagList();
            choices.appendTag(choice);
            config.setTag("nativeHatchChoices", choices);
            var accepted = HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config);
            SimpleAssert.that(
                accepted.cells()
                    .stream()
                    .anyMatch(
                        cell -> cell.dx() == 1 && cell.material()
                            .getItemDamage() == PORT_ID),
                "raw native replay retains the positively tested selected hatch");
            choice.setInteger("facing", ForgeDirection.EAST.ordinal());
            var rotatedRaw = HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config);
            SimpleAssert.that(
                rotatedRaw.cells()
                    .stream()
                    .anyMatch(cell -> cell.dx() == 1 && cell.facing() == ForgeDirection.EAST.ordinal()),
                "raw native replay retains an explicit hatch rotation");
            prototype(RAW_ID, new Controller(9));
            try {
                HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config);
                throw new AssertionError("raw in-place model replacement kept stale paid material authority");
            } catch (IOException expected) {}
            prototype(RAW_ID, new Controller(6));
            choice.setInteger("facing", -1);
            choice.setInteger("id", REJECT_ID);
            try {
                HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config);
                throw new AssertionError("raw illegal hatch survived replay");
            } catch (IOException expected) {}
            prototype(RAW_ID, new Controller(12));
            choice.setInteger("id", PORT_ID);
            choice.setInteger("facing", -1);
            try {
                HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config);
                throw new AssertionError("creative-only raw hatch without a native seed was accepted");
            } catch (IOException expected) {}
            choice.setBoolean("siteNativeAccepted", true);
            try {
                HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config);
                throw new AssertionError("old site proof bypassed this raw replay's missing native -1 acceptance");
            } catch (IOException expected) {}
            choice.removeTag("siteNativeAccepted");
            prototype(RAW_ID, new Controller(11));
            choice.setInteger("id", PORT_ID);
            choice.setInteger("facing", ForgeDirection.SOUTH.ordinal());
            try (var directional = HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config)) {
                SimpleAssert.eq(
                    (1 << ForgeDirection.NORTH.ordinal()) | (1 << ForgeDirection.SOUTH.ordinal()),
                    directional.facingMask(1, 0, 0, new ItemStack(block, 1, PORT_ID)),
                    "selected position proves native legal directions without a declaration adapter");
                SimpleAssert.that(
                    directional.cells()
                        .stream()
                        .anyMatch(cell -> cell.dx() == 1 && cell.facing() == ForgeDirection.SOUTH.ordinal()),
                    "explicit direction is applied after the native constructor resets its default");
            }
            choice.setInteger("facing", ForgeDirection.UP.ordinal());
            try {
                HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config);
                throw new AssertionError("native coordinate rejected UP but the selected hatch rotated anyway");
            } catch (IOException expected) {}
            choice.setInteger("facing", -1);
            try (var restored = HologramNativeTargetPlan.compile(RAW_ID, ExtendedFacing.DEFAULT, signal, config)) {
                SimpleAssert.that(
                    restored.cells()
                        .stream()
                        .anyMatch(cell -> cell.dx() == 1 && cell.facing() == ForgeDirection.NORTH.ordinal()),
                    "restoring direction uses the native default");
            }
            prototype(RAW_ID, new Controller(8));
            var required = HologramNativeTargetPlan
                .compile(RAW_ID, ExtendedFacing.DEFAULT, signal, new NBTTagCompound());
            SimpleAssert.that(
                required.cells()
                    .stream()
                    .anyMatch(
                        cell -> cell.material() != null && cell.material()
                            .getItemDamage() == MUFFLER_ID),
                "EBF native Muffler.newAny remains mandatory without an optional-hatch preset");
            SimpleAssert.that(
                ((Controller) required.context()).passes <= 3,
                "required native muffler does not stall on zero placement rounds");
            NBTTagCompound preset = new NBTTagCompound();
            preset.setBoolean("presetActive", true);
            preset.setInteger("hatchTier", 1);
            prototype(ID, new Controller(0));
            var unformed = HologramNativeTargetPlan.compile(
                ID,
                ExtendedFacing.DEFAULT,
                HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound()),
                preset);
            SimpleAssert.that(
                !unformed.nativeFormed() && !unformed.cells()
                    .isEmpty(),
                "unformed diagnostics do not erase valid target geometry");
            verifyFrozenCasingChoices(block);
        } finally {
            GregTechAPI.sBlockMachines = oldBlock;
            GregTechAPI.METATILEENTITIES[ID] = oldId;
            GregTechAPI.METATILEENTITIES[RAW_ID] = oldController;
            GregTechAPI.METATILEENTITIES[PORT_ID] = oldPort;
            GregTechAPI.METATILEENTITIES[REJECT_ID] = oldReject;
            GregTechAPI.METATILEENTITIES[MUFFLER_ID] = oldMuffler;
            if (addedMapping) {
                nativeNames.remove(BaseMetaTileEntity.class);
                nativeClasses.remove(fixtureName);
            }
        }
    }

    private static void verifyFrozenCasingChoices(Block machines) throws IOException {
        prototype(RAW_ID, new Controller(14));
        prototype(
            REJECT_ID,
            new gregtech.api.metatileentity.implementations.MTEHatchOutput(
                "frozen_upgraded_output",
                2,
                new String[0],
                new ITexture[0][][]));
        ItemStack signal = HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound());
        java.util.List<HologramNativeTargetPlan.Cell> actual = new java.util.ArrayList<>();
        try (var generated = HologramNativeTargetPlan
            .compile(RAW_ID, ExtendedFacing.DEFAULT, signal, new NBTTagCompound())) {
            SimpleAssert.eq(
                2,
                generated.cells()
                    .size(),
                "native optional-hatch fixture has two casing positions");
            for (var cell : generated.cells()) {
                int meta = actual.isEmpty() ? 7 : 11;
                SimpleAssert.eq(Blocks.wool, cell.block(), "native default optional position is a casing");
                actual.add(
                    new HologramNativeTargetPlan.Cell(
                        cell.dx(),
                        cell.dy(),
                        cell.dz(),
                        Blocks.wool,
                        meta,
                        new ItemStack(Blocks.wool, 1, meta),
                        -1,
                        null,
                        null,
                        cell.element(),
                        signal,
                        false));
            }
        }
        var site = new NBTTagCompound();
        site.setBoolean("siteKnown", true);
        site.setInteger("main", 1);
        var at = actual.get(0);
        try (var target = frozenTarget(actual, site, new net.minecraft.nbt.NBTTagList())) {
            var offers = target.offers(
                target.cells()
                    .get(0));
            SimpleAssert.that(
                offers.stream()
                    .anyMatch(stack -> stack.getItemDamage() == PORT_ID),
                "ordinary frozen casing exposes its native hatch offers");
            var world = (HologramNativePreviewWorld) target.controller()
                .getWorldObj();
            int x = target.controller().xCoord + at.dx(), y = target.controller().yCoord + at.dy(),
                z = target.controller().zCoord + at.dz();
            ItemStack material = world.materialAt(x, y, z)
                .copy();
            SimpleAssert.that(
                target.facingMask(at.dx(), at.dy(), at.dz(), new ItemStack(machines, 1, PORT_ID)) != 0,
                "ordinary frozen casing proves candidate hatch directions before selection");
            SimpleAssert.eq(Blocks.wool, world.getBlock(x, y, z), "candidate proof restores casing block");
            SimpleAssert.eq(7, world.getBlockMetadata(x, y, z), "candidate proof restores casing metadata");
            SimpleAssert.eq(null, world.getTileEntity(x, y, z), "candidate proof removes its temporary hatch");
            SimpleAssert.that(
                ItemStack.areItemStacksEqual(material, world.materialAt(x, y, z)),
                "candidate proof restores exact paid material");
        }
        var choice = new NBTTagCompound();
        choice.setInteger("x", at.dx());
        choice.setInteger("y", at.dy());
        choice.setInteger("z", at.dz());
        choice.setInteger("id", PORT_ID);
        choice.setInteger("facing", -1);
        var choices = new net.minecraft.nbt.NBTTagList();
        choices.appendTag(choice);
        java.util.List<HologramNativeTargetPlan.Cell> installed;
        int originalFacing;
        try (var added = frozenTarget(actual, site, choices)) {
            var hatch = added.cells()
                .get(0);
            SimpleAssert.eq(
                PORT_ID,
                hatch.material()
                    .getItemDamage(),
                "frozen casing accepts a native placed hatch");
            SimpleAssert.that(
                hatch.facing() >= 0 && hatch.facing() < 6,
                "automatic casing-to-hatch uses the native placed facing");
            SimpleAssert.eq(
                11,
                added.cells()
                    .get(1)
                    .metadata(),
                "adding a hatch preserves the other mixed casing");
            installed = new java.util.ArrayList<>(added.cells());
            originalFacing = hatch.facing();
        }
        var installedSite = (NBTTagCompound) site.copy();
        installedSite.setTag("nativeHatchChoices", choices.copy());
        choice.setInteger("id", REJECT_ID);
        try (var upgraded = frozenTarget(installed, installedSite, choices)) {
            SimpleAssert.eq(
                REJECT_ID,
                upgraded.cells()
                    .get(0)
                    .material()
                    .getItemDamage(),
                "frozen installed hatch accepts a native new model");
            SimpleAssert.eq(
                11,
                upgraded.cells()
                    .get(1)
                    .metadata(),
                "model replacement preserves unrelated casing");
        }
        choice.setInteger("id", PORT_ID);
        choice.setInteger("facing", (originalFacing + 1) % 6);
        try (var rotated = frozenTarget(installed, installedSite, choices)) {
            SimpleAssert.eq(
                (originalFacing + 1) % 6,
                rotated.cells()
                    .get(0)
                    .facing(),
                "frozen native hatch rotates without changing its model");
            SimpleAssert.eq(
                PORT_ID,
                rotated.cells()
                    .get(0)
                    .material()
                    .getItemDamage(),
                "rotation keeps exact native model");
            SimpleAssert.eq(
                11,
                rotated.cells()
                    .get(1)
                    .metadata(),
                "rotation preserves unrelated casing");
        }
        try (var returned = frozenTarget(actual, site, new net.minecraft.nbt.NBTTagList())) {
            SimpleAssert.eq(
                Blocks.wool,
                returned.cells()
                    .get(0)
                    .block(),
                "removing an added choice returns the original casing");
            SimpleAssert.eq(
                7,
                returned.cells()
                    .get(0)
                    .metadata(),
                "return retains the actual mixed casing material");
            SimpleAssert.eq(
                11,
                returned.cells()
                    .get(1)
                    .metadata(),
                "return preserves the other actual casing");
        }
        SimpleAssert.eq(
            Blocks.wool,
            actual.get(0)
                .block(),
            "native edits never mutate the adopted source cells");
    }

    private static HologramNativeTargetPlan frozenTarget(java.util.List<HologramNativeTargetPlan.Cell> cells,
        NBTTagCompound site, net.minecraft.nbt.NBTTagList choices) throws IOException {
        var config = (NBTTagCompound) site.copy();
        config.setTag("nativeHatchChoices", choices.copy());
        HologramNativeTargetPlan target = HologramNativeTargetPlan.begin(
            RAW_ID,
            ExtendedFacing.DEFAULT,
            HologramNativeTargetPlan.canonicalTrigger(1, config.getCompoundTag("channels")),
            config,
            cells,
            site);
        try {
            target.advance(Long.MAX_VALUE);
            return target;
        } catch (IOException | RuntimeException | LinkageError failure) {
            target.close();
            throw failure;
        }
    }

    private static final class Controller extends MTEEnhancedMultiBlockBase<Controller>
        implements ISurvivalConstructable, INEIPreviewModifier {

        static int nativeCalls;
        final int mode;
        int passes, started, completed;
        private IStructureDefinition<Controller> mufflerDefinition;

        Controller(int mode) {
            super("gtmb_native_target_fixture");
            this.mode = mode;
        }

        @Override
        public IStructureDefinition<Controller> getStructureDefinition() {
            if (mode != 8 && mode != 13) return null;
            if (mufflerDefinition == null)
                mufflerDefinition = com.gtnewhorizon.structurelib.structure.StructureDefinition.<Controller>builder()
                    .addShape("required_muffler", new String[][] { { "m" } })
                    .addElement(
                        'm',
                        mode == 13
                            ? com.gtnewhorizon.structurelib.structure.StructureUtility.withChannel(
                                "shell",
                                com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlockAnyMeta(Blocks.wool))
                            : gregtech.api.enums.HatchElement.Muffler.<Controller>newAny(11, 2))
                    .build();
            return mufflerDefinition;
        }

        @Override
        public int survivalConstruct(ItemStack signal, int budget, ISurvivalBuildEnvironment env) {
            passes++;
            nativeCalls++;
            if (started > 0) return 0;
            World world = getBaseMetaTileEntity().getWorld();
            if (mode == 14) {
                var element = com.gtnewhorizon.structurelib.structure.StructureUtility.ofChain(
                    gregtech.api.enums.HatchElement.OutputHatch.<Controller>newAny(11, 2),
                    com.gtnewhorizon.structurelib.structure.StructureUtility.<Controller>ofBlockAnyMeta(Blocks.wool));
                for (int x : new int[] { 1, 2 }) {
                    if (world.isAirBlock(x, 128, 0)) world.setBlock(x, 128, 0, Blocks.wool, 0, 2);
                    visited(world, x, element);
                }
                return -1;
            }
            if (mode == 8 || mode == 13) {
                // Use StructureLib's actual V2 walker: it owns definition/piece/offset/facing in the element env.
                return getStructureDefinition().survivalBuild(
                    this,
                    signal,
                    "required_muffler",
                    world,
                    getExtendedFacing(),
                    1,
                    128,
                    0,
                    0,
                    0,
                    0,
                    budget,
                    env,
                    false);
            }
            if (mode == 7) {
                world.setBlock(1, 128, 0, Blocks.water, 0, 2);
                world.setBlock(2, 128, 0, Blocks.water, 0, 2);
                world.setBlock(3, 128, 0, Blocks.water, 0, 2);
                var water = com.gtnewhorizon.structurelib.structure.StructureUtility
                    .<Controller>ofBlock(Blocks.water, 0);
                visited(
                    world,
                    1,
                    com.gtnewhorizon.structurelib.structure.StructureUtility
                        .ofChain(water, com.gtnewhorizon.structurelib.structure.StructureUtility.<Controller>isAir()));
                visited(world, 2, water);
                return -1;
            }
            if (mode == 11) {
                world.setBlock(1, 128, 0, GregTechAPI.sBlockMachines, 0, 2);
                var port = (IGregTechTileEntity) world.getTileEntity(1, 128, 0);
                port.setInitialValuesAsNBT(null, (short) PORT_ID);
                port.setFrontFacing(ForgeDirection.NORTH);
                visited(
                    world,
                    1,
                    new com.gtnewhorizon.structurelib.structure.IStructureElementNoPlacement<Controller>() {

                        @Override
                        public boolean check(Controller context, World owned, int x, int y, int z) {
                            return owned.getTileEntity(x, y, z) instanceof IGregTechTileEntity hatch
                                && hatch.getMetaTileID() == PORT_ID
                                && (hatch.getFrontFacing() == ForgeDirection.NORTH
                                    || hatch.getFrontFacing() == ForgeDirection.SOUTH);
                        }

                        @Override
                        public boolean spawnHint(Controller context, World owned, int x, int y, int z,
                            ItemStack trigger) {
                            return false;
                        }
                    });
                return -1;
            }
            if (mode == 10) {
                world.setBlock(1, 128, 0, Blocks.stone, 0, 2);
                world.setBlockToAir(1, 128, 0);
                return 1;
            }
            if (mode == 6 || mode == 9) {
                var tile = world.getTileEntity(1, 128, 0);
                if (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileID() != PORT_ID)
                    world.setBlock(1, 128, 0, Blocks.stone, 0, 2);
                if (mode == 9 && tile instanceof IGregTechTileEntity gt && gt.getMetaTileID() == PORT_ID)
                    gt.setInitialValuesAsNBT(null, (short) REJECT_ID);
                int sectors = com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                    .getChannelData(signal, "ore_center_sectors");
                world.setBlock(sectors + 2, 128, 0, Blocks.stone, 0, 2);
                return -1;
            }
            if (mode == 2) return 0;
            if (mode == 12) return -2;
            if (mode == 3) return -3;
            if (mode == 4) throw new IllegalStateException("fixture failure");
            if (mode == 1) {
                world.setBlock(9, 128, 0, Blocks.stone, 0, 3);
                return -2;
            }
            if (passes == 1) {
                place(env, world, 1, "first");
                place(env, world, 2, "second");
                return 2;
            }
            if (passes <= 133) return 0;
            world.setBlockToAir(1, 128, 0);
            place(env, world, 1, "replacement");
            return -1;
        }

        private static void place(ISurvivalBuildEnvironment env, World world, int x, String label) {
            ItemStack material = new ItemStack(Blocks.stone, 1, 0);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("nativeMaterial", label);
            material.setTagCompound(tag);
            SimpleAssert.that(
                env.getSource()
                    .takeOne(material, true),
                "native source simulates the exact item");
            world.setBlock(x, 128, 0, Blocks.stone, 0, 3);
            SimpleAssert.that(
                env.getSource()
                    .takeOne(material, false),
                "native source confirms the exact item");
        }

        @Override
        public void construct(ItemStack signal, boolean hints) {
            if (mode == 12) {
                World world = getBaseMetaTileEntity().getWorld();
                // A creative constructor may refuse an installed tile and return void without replacing it.
                if (world.getTileEntity(1, 128, 0) == null) world.setBlock(1, 128, 0, Blocks.stone, 0, 2);
                return;
            }
            if (mode == 6 || mode == 7 || mode == 8 || mode == 9 || mode == 13 || mode == 14) return;
            getBaseMetaTileEntity().getWorld()
                .setBlock(hints ? 7 : 3, 128, 0, hints ? Blocks.glass : Blocks.gold_block, 0, 3);
        }

        @Override
        public void onPreviewConstruct(ItemStack signal) {
            started++;
        }

        @Override
        public void onPreviewStructureComplete(ItemStack signal) {
            completed++;
            if (mode == 6 || mode == 9) {
                World world = getBaseMetaTileEntity().getWorld();
                world.setBlock(1, 128, 0, GregTechAPI.sBlockMachines, 0, 2);
                BaseMetaTileEntity port = (BaseMetaTileEntity) world.getTileEntity(1, 128, 0);
                port.setInitialValuesAsNBT(null, (short) PORT_ID);
                return;
            }
            getBaseMetaTileEntity().getWorld()
                .setBlock(8, 128, 0, Blocks.glass, 0, 3);
        }

        @Override
        public boolean checkStructure(boolean reset, IGregTechTileEntity tile) {
            return mMachine = false;
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return new MultiblockTooltipBuilder();
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new Controller(mode);
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }
}
