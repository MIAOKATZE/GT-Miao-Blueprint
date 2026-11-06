package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.CommonProxy;
import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.casing.Casings;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.HatchElementBuilder;
import gregtech.common.blocks.BlockFrameBox;
import gregtech.common.tileentities.machines.multi.xlturbines.MTEXLTurbineBase;
import gregtech.common.tileentities.machines.multi.xlturbines.MTEXLTurbineHPSteam;
import gregtech.common.tileentities.machines.multi.xlturbines.MTEXLTurbineSteam;

/** Real cold GT factories consume the public native output protocol without structure checks. */
final class HologramColdGregTechDeclarationsRegression {

    private HologramColdGregTechDeclarationsRegression() {}

    static void run(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        Field cachedBlock = Casings.class.getDeclaredField("block");
        cachedBlock.setAccessible(true);
        Casings[] casings = { Casings.MVSolenoidSuperconductorCoil, Casings.SteelPipeCasing, Casings.TitaniumPipeCasing,
            Casings.TurbineShaft, Casings.ReinforcedSteamTurbineCasing, Casings.ReinforcedHPSteamTurbineCasing };
        Map<Casings, Object> previousCasings = new LinkedHashMap<>();
        Map<String, ItemStack> ores = GTOreDictUnificator.getName2StackMap();
        String steel = OrePrefixes.frameGt.get(Materials.Steel)
            .toString(),
            titanium = OrePrefixes.frameGt.get(Materials.Titanium)
                .toString();
        ItemStack previousSteel = ores.get(steel), previousTitanium = ores.get(titanium);
        Materials previous81 = GregTechAPI.sGeneratedMaterials[81], previous82 = GregTechAPI.sGeneratedMaterials[82];
        Block previousMachines = GregTechAPI.sBlockMachines;
        int hatchId = 32690;
        IMetaTileEntity previousHatch = GregTechAPI.METATILEENTITIES[hatchId];
        Field proxy = StructureLib.class.getDeclaredField("proxy"),
            marker = StructureLib.class.getDeclaredField("blockHint");
        proxy.setAccessible(true);
        marker.setAccessible(true);
        Object previousProxy = proxy.get(null), previousMarker = marker.get(null);
        Field lazyCache = HologramDeclarationWrappers.class.getDeclaredField("FIXED_LAZY");
        lazyCache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<IStructureElement<?>, IStructureElement<?>> fixed = (Map<IStructureElement<?>, IStructureElement<?>>) lazyCache
            .get(null);
        Map<IStructureElement<?>, IStructureElement<?>> previousFixed = new IdentityHashMap<>(fixed);
        Map<IStructureElement<?>, Object[]> originals = new IdentityHashMap<>();
        Field casingCount = MTEXLTurbineBase.class.getDeclaredField("casingAmount");
        casingCount.setAccessible(true);
        int previousCount = casingCount.getInt(null);
        try {
            for (Casings casing : casings) {
                previousCasings.put(casing, cachedBlock.get(casing));
                cachedBlock.set(casing, Blocks.wool);
            }
            Block frame = frame(unsafe);
            ores.put(steel, new ItemStack(frame, 1, 81));
            ores.put(titanium, new ItemStack(frame, 1, 82));
            GregTechAPI.sGeneratedMaterials[81] = Materials.Steel;
            GregTechAPI.sGeneratedMaterials[82] = Materials.Titanium;
            GregTechAPI.sBlockMachines = machines();
            SimpleAssert
                .that(
                    GregTechAPI.sBlockMachines != null
                        && Item
                            .getItemFromBlock(GregTechAPI.sBlockMachines) instanceof gregtech.common.blocks.ItemMachines
                        && Block.getBlockFromItem(Item.getItemFromBlock(GregTechAPI.sBlockMachines))
                            == GregTechAPI.sBlockMachines,
                    "cold native factories own a registered GT item fixture with an exact native block mapping");
            IMetaTileEntity hatch = (IMetaTileEntity) unsafe.allocateInstance(MTEHatchInput.class);
            BaseMetaTileEntity hatchBase = (BaseMetaTileEntity) unsafe.allocateInstance(BaseMetaTileEntity.class);
            hatchBase.setMetaTileID((short) hatchId);
            hatch.setBaseMetaTileEntity(hatchBase);
            GregTechAPI.METATILEENTITIES[hatchId] = hatch;
            proxy.set(null, unsafe.allocateInstance(Hints.class));
            marker.set(null, Blocks.glass);
            MTEXLTurbineSteam steam = new MTEXLTurbineSteam("cold_native_steam");
            @SuppressWarnings("unchecked")
            Map<Character, IStructureElement<MTEXLTurbineBase>> nativeElements = ((StructureDefinition<MTEXLTurbineBase>) steam
                .getStructureDefinition()).getElements();
            AtomicInteger unknownCalls = new AtomicInteger();
            IStructureElement<MTEXLTurbineBase> unknown = StructureUtility.lazy(context -> {
                unknownCalls.incrementAndGet();
                throw new IllegalStateException("native deferred output unavailable");
            });
            StructureDefinition.Builder<MTEXLTurbineBase> builder = StructureDefinition.<MTEXLTurbineBase>builder()
                .addShape("cold", new String[][] { { "BCDEFU" } });
            for (char symbol : "BCDEF".toCharArray()) {
                IStructureElement<MTEXLTurbineBase> element = nativeElements.get(symbol);
                SimpleAssert.that(element != null, "the official native definition contains factory " + symbol);
                Object[] state = lazyState(element);
                SimpleAssert.that(state[0] == null && state[1] != null, "official native factory starts cold");
                originals.put(element, state);
                builder.addElement(symbol, element);
            }
            builder.addElement('U', unknown);
            IStructureDefinition<MTEXLTurbineBase> definition = builder.build();
            HologramSession first = capture(
                unsafe,
                steam,
                definition,
                Casings.SteelPipeCasing,
                Materials.Steel,
                Casings.ReinforcedSteamTurbineCasing,
                hatchId);
            // Recreate a cold native source for the second context; native lazy initialization is owned by
            // StructureLib.
            restoreLazy(originals);
            HologramSession second = capture(
                unsafe,
                new MTEXLTurbineHPSteam("cold_native_hp"),
                definition,
                Casings.TitaniumPipeCasing,
                Materials.Titanium,
                Casings.ReinforcedHPSteamTurbineCasing,
                hatchId);
            SimpleAssert.that(
                first.capture.cells.get(4).block != second.capture.cells.get(4).block
                    || first.capture.cells.get(4).meta != second.capture.cells.get(4).meta,
                "separate native output contexts retain their own captured casing targets");
            SimpleAssert
                .that(unknownCalls.get() > 0, "unavailable native resolvers are read without inventing materials");
            SimpleAssert.eq(
                previousCount,
                casingCount.getInt(null),
                "native declaration reads never execute the onElementPass casing counter");
            int initialized = 0;
            for (IStructureElement<?> element : originals.keySet()) {
                Object[] after = lazyState(element);
                if (after[0] != null) {
                    initialized++;
                    SimpleAssert.that(after[1] == null, "native lazy initialization consumes its original resolver");
                }
            }
            SimpleAssert.that(initialized >= 3, "public native output initializes the real contextual declarations");
        } finally {
            restoreLazy(originals);
            for (Map.Entry<Casings, Object> entry : previousCasings.entrySet())
                cachedBlock.set(entry.getKey(), entry.getValue());
            if (previousSteel == null) ores.remove(steel);
            else ores.put(steel, previousSteel);
            if (previousTitanium == null) ores.remove(titanium);
            else ores.put(titanium, previousTitanium);
            GregTechAPI.sGeneratedMaterials[81] = previous81;
            GregTechAPI.sGeneratedMaterials[82] = previous82;
            GregTechAPI.sBlockMachines = previousMachines;
            GregTechAPI.METATILEENTITIES[hatchId] = previousHatch;
            fixed.clear();
            fixed.putAll(previousFixed);
            casingCount.setInt(null, previousCount);
            proxy.set(null, previousProxy);
            marker.set(null, previousMarker);
        }
    }

    private static HologramSession capture(sun.misc.Unsafe unsafe, MTEXLTurbineBase machine,
        IStructureDefinition<MTEXLTurbineBase> definition, Casings rotor, Materials material, Casings casing,
        int hatchId) throws ReflectiveOperationException {
        Method players = HologramControllerCaptureRegression.class.getDeclaredMethod("player", sun.misc.Unsafe.class);
        players.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) players.invoke(null, unsafe);
        player.posY = 100;
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) player.worldObj;
        HologramDynamicDefinitionRegression.NativeTile tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
            .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
        tile.setWorldObj(world);
        tile.yCoord = 100;
        machine.setBaseMetaTileEntity(tile);
        world.occupant = tile;
        world.occupied = GregTechAPI.sBlockMachines;
        HologramSession s = new HologramSession(player, 0, 100, 0, false, 2);
        s.target = tile;
        s.machine = machine;
        s.context = machine;
        s.facing = machine.getExtendedFacing()
            .ordinal();
        s.materialSettings.containers = s.materialSettings.me = false;
        try (GTMBExtensions.Registration source = StructureSources.register(
            "gtmb_test:cold_native",
            machine.getClass(),
            (controller, signal) -> new StructureSources.Plan(
                definition,
                Collections.singletonList(new StructureSources.Piece("cold", -1, 0, 0, signal))))) {
            ItemStack signal = HologramSessionPolicy.trigger(s);
            do {
                s.capture = HologramCapture
                    .collectProjection(machine, signal, machine.getExtendedFacing(), s.captureIntent, s.capture);
            } while (s.capture.pending);
            SimpleAssert.that(
                !s.capture.incomplete && s.capture.projectionOnly,
                "real cold native declaration traversal completes without operation preparation");
            for (HologramCapture.Cell cell : s.capture.cells) if (!cell.anchor) {
                HologramCellPolicy.declaredCandidates(s, cell);
                // Production records these builder arguments at factory RETURN, during native lazy resolution.
                mirrorHatchMixin(cell.element);
            }
            s.declarations.clear();
            HologramControllerCapture.FinishWork finish = new HologramControllerCapture.FinishWork(new HashMap<>());
            finish.beginFinish();
            int slices = 0;
            while (!finish.step(s, 3, Long.MAX_VALUE))
                SimpleAssert.that(++slices < 10000, "cold material finalization makes bounded native progress");
            if (rotor != null) verify(s, rotor, material, casing, hatchId);
        }
        return s;
    }

    private static void verify(HologramSession s, Casings rotor, Materials material, Casings casing, int hatchId) {
        ItemStack[] expected = { Casings.MVSolenoidSuperconductorCoil.toStack(1), rotor.toStack(1),
            GTOreDictUnificator.get(OrePrefixes.frameGt, material, 1), Casings.TurbineShaft.toStack(1),
            casing.toStack(1) };
        for (int index = 0; index < expected.length; index++) {
            HologramCapture.Cell cell = s.capture.cells.get(index);
            ItemStack target = HologramCellPolicy.targetFor(s, cell, index);
            SimpleAssert.that(
                HologramValues.same(expected[index], target),
                "cold native factory resolves its exact official material at position " + index);
            s.selected = index;
            NBTTagCompound row = HologramStructureProjection.row(s, cell, index);
            SimpleAssert.that(
                !row.getBoolean("missingBlock") && HologramValues
                    .same(expected[index], ItemStack.loadItemStackFromNBT(row.getCompoundTag("targetStack"))),
                "the projection row retains the cold native material and metadata " + index);
            SimpleAssert.that(!cell.candidates.isEmpty(), "native cold candidates remain available " + index);
        }
        HologramCapture.Cell hatch = s.capture.cells.get(4);
        SimpleAssert.that(
            "hatch".equals(hatch.role) && !HologramCellPolicy.hatchRequirements(s, hatch)
                .isEmpty() && s.presetAvailable,
            "the cold native hatch chain retains its role and declared quotas");
        ItemStack input = new ItemStack(GregTechAPI.sBlockMachines, 1, hatchId);
        SimpleAssert.that(
            HologramCellPolicy.declaredTarget(s, hatch, input, false),
            "the native hatch candidate retains positive declaration authority");
        s.pins.put(4, input);
        HologramResourcePlanner.PlanTask planner = HologramResourcePlanner.beginCapturePlan(s);
        while (!planner.step(3, Long.MAX_VALUE)) {}
        SimpleAssert.that(
            HologramHatchProfiles.capture(s)
                .getTagList("entries", 10)
                .tagCount() == 1,
            "a native hatch pin produces a real role-bearing profile entry");
    }

    private static Object[] lazyState(IStructureElement<?> element) throws ReflectiveOperationException {
        Field initialized = element.getClass()
            .getDeclaredField("elem"),
            deferred = element.getClass()
                .getDeclaredField("to");
        initialized.setAccessible(true);
        deferred.setAccessible(true);
        Object[] state = { initialized.get(element), deferred.get(element) };
        return state;
    }

    private static void restoreLazy(Map<IStructureElement<?>, Object[]> originals) throws ReflectiveOperationException {
        for (Map.Entry<IStructureElement<?>, Object[]> entry : originals.entrySet()) {
            Field initialized = entry.getKey()
                .getClass()
                .getDeclaredField("elem"),
                deferred = entry.getKey()
                    .getClass()
                    .getDeclaredField("to");
            initialized.setAccessible(true);
            deferred.setAccessible(true);
            initialized.set(entry.getKey(), entry.getValue()[0]);
            deferred.set(entry.getKey(), entry.getValue()[1]);
        }
    }

    /** The headless harness mirrors only the existing atLeast/build mixins' immutable argument recording. */
    @SuppressWarnings("unchecked")
    private static void mirrorHatchMixin(IStructureElement<?> element) throws ReflectiveOperationException {
        for (IStructureElement<?> part : HologramElementCatalog.parts(element)) {
            Method method = HologramDeclarationWrappers.factory(part.getClass());
            if (method == null || method.getDeclaringClass() != HatchElementBuilder.class
                || !method.getName()
                    .equals("build"))
                continue;
            Field enclosing = part.getClass()
                .getDeclaredField("this$0");
            enclosing.setAccessible(true);
            Object builder = enclosing.get(part);
            Field description = HatchElementBuilder.class.getDeclaredField("mDescriptionNames");
            description.setAccessible(true);
            Object supplier = description.get(builder);
            for (Field field : supplier.getClass()
                .getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Map<IHatchElement<?>, Number> requirements = (Map<IHatchElement<?>, Number>) field.get(supplier);
                HologramHatchPolicy.declare(builder, requirements.keySet(), true);
                HologramHatchPolicy.requirements(builder, requirements);
                HologramHatchPolicy.built(builder, part);
            }
        }
    }

    private static Block frame(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        String key = "gtmb:cold_native_frame";
        Object existing = Block.blockRegistry.getObject(key);
        if (existing instanceof Block && existing != Blocks.air) return (Block) existing;
        BlockFrameBox frame = (BlockFrameBox) unsafe.allocateInstance(BlockFrameBox.class);
        Field delegate = Block.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(
            frame,
            ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Block.blockRegistry)
                .getDelegate(frame, Block.class));
        frame.stepSound = Blocks.iron_block.stepSound;
        int id = 3898;
        while (Block.getBlockById(id) != null && Block.getBlockById(id) != Blocks.air || Item.getItemById(id) != null)
            id--;
        Block.blockRegistry.addObject(id, key, frame);
        Item.itemRegistry.addObject(id, key, new ItemBlock(frame) {

            @Override
            public int getMetadata(int damage) {
                return damage;
            }
        });
        return frame;
    }

    private static Block machines() {
        String key = "gtmb:cold_native_machines";
        Object existing = Block.blockRegistry.getObject(key);
        if (existing instanceof Block && existing != Blocks.air) return (Block) existing;
        Block block = new Block(net.minecraft.block.material.Material.iron) {};
        int id = 3897;
        while (Block.getBlockById(id) != null && Block.getBlockById(id) != Blocks.air || Item.getItemById(id) != null)
            id--;
        Block.blockRegistry.addObject(id, key, block);
        Item.itemRegistry.addObject(id, key, new gregtech.common.blocks.ItemMachines(block));
        return block;
    }

    private static final class Hints extends CommonProxy {

        @Override
        public void hintParticle(net.minecraft.world.World world, int x, int y, int z, Block block, int metadata) {
            HologramCapture.hint(world, x, y, z, block, metadata);
        }
    }
}
