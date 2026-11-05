package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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

/** Real cold GT factories, their capture-owned delegates and the unknown-callback boundary. */
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
                throw new AssertionError("unknown deferred factory was executed");
            });
            StructureDefinition.Builder<MTEXLTurbineBase> builder = StructureDefinition.<MTEXLTurbineBase>builder()
                .addShape("cold", new String[][] { { "BCDEFU" } });
            Map<IStructureElement<?>, Object[]> originals = new IdentityHashMap<>();
            for (char symbol : "BCDEF".toCharArray()) {
                IStructureElement<MTEXLTurbineBase> element = nativeElements.get(symbol);
                SimpleAssert.that(element != null, "the official native definition contains factory " + symbol);
                originals.put(element, lazyState(element));
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
            HologramSession second = capture(
                unsafe,
                new MTEXLTurbineHPSteam("cold_native_hp"),
                definition,
                Casings.TitaniumPipeCasing,
                Materials.Titanium,
                Casings.ReinforcedHPSteamTurbineCasing,
                hatchId);
            SimpleAssert.that(
                first.capture.cells.get(4).element != second.capture.cells.get(4).element,
                "one shared cold source gets a separate native hatch delegate for each controller context");
            MTEXLTurbineBase missingClient = signatureContext(false);
            capture(
                unsafe,
                missingClient,
                definition,
                Casings.SteelPipeCasing,
                Materials.Steel,
                Casings.ReinforcedSteamTurbineCasing,
                hatchId);
            MTEXLTurbineBase missingClientOverride = signatureContext(true);
            HologramSession deniedSignature = capture(
                unsafe,
                missingClientOverride,
                definition,
                null,
                null,
                null,
                hatchId);
            SimpleAssert.that(
                HologramCellPolicy.targetFor(deniedSignature, deniedSignature.capture.cells.get(1), 1) == null
                    && missingClientOverride.getClass()
                        .getDeclaredField("unknownCalls")
                        .getInt(null) == 0,
                "a missing client-only signature cannot hide an unknown metadata getter override");
            unknownGetterCalls.set(0);
            HologramSession rejected = capture(unsafe, new UnknownGetterSteam(), definition, null, null, null, hatchId);
            SimpleAssert.that(
                HologramCellPolicy.targetFor(rejected, rejected.capture.cells.get(1), 1) == null,
                "an unknown virtual getter override cannot inherit the official cold factory's target authority");
            SimpleAssert.eq(0, unknownGetterCalls.get(), "unknown context getters are rejected before invocation");
            SimpleAssert.eq(0, unknownCalls.get(), "unreviewed deferred factories remain outside native capture");
            SimpleAssert.eq(
                previousCount,
                casingCount.getInt(null),
                "cold metadata reconstruction never executes the native onElementPass casing counter");
            for (Map.Entry<IStructureElement<?>, Object[]> entry : originals.entrySet()) {
                Object[] after = lazyState(entry.getKey());
                SimpleAssert.that(
                    after[0] == null && after[1] == entry.getValue()[1],
                    "read-only cold capture leaves the original lazy elem and to untouched");
            }
        } finally {
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
            for (HologramCapture.Cell cell : s.capture.cells) mirrorHatchMixin(cell.element);
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
        SimpleAssert.that(state[0] == null && state[1] != null, "official lazy factory starts genuinely cold");
        return state;
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

    /** A real missing-client signature reproduces Class.getDeclaredMethod's unrelated signature resolution. */
    private static MTEXLTurbineBase signatureContext(boolean override) throws ReflectiveOperationException {
        String name = "gtmb_test/cold/" + (override ? "UnknownSignatureSteam" : "InheritedSignatureSteam");
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            name,
            null,
            Type.getInternalName(MTEXLTurbineSteam.class),
            null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitLdcInsn("missing_client_signature");
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            Type.getInternalName(MTEXLTurbineSteam.class),
            "<init>",
            "(Ljava/lang/String;)V",
            false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 1);
        constructor.visitEnd();
        MethodVisitor client = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "unusedClientHook",
            "(Lnet/minecraft/client/resources/IResourceManager;)V",
            null,
            null);
        client.visitCode();
        client.visitInsn(Opcodes.RETURN);
        client.visitMaxs(0, 2);
        client.visitEnd();
        if (override) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "unknownCalls", "I", null, null)
                .visitEnd();
            MethodVisitor getter = writer
                .visitMethod(Opcodes.ACC_PROTECTED, "getFrameMaterial", "()Lgregtech/api/enums/Materials;", null, null);
            getter.visitCode();
            getter.visitFieldInsn(Opcodes.GETSTATIC, name, "unknownCalls", "I");
            getter.visitInsn(Opcodes.ICONST_1);
            getter.visitInsn(Opcodes.IADD);
            getter.visitFieldInsn(Opcodes.PUTSTATIC, name, "unknownCalls", "I");
            getter.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
            getter.visitInsn(Opcodes.DUP);
            getter.visitLdcInsn("unknown metadata override executed");
            getter.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/AssertionError",
                "<init>",
                "(Ljava/lang/Object;)V",
                false);
            getter.visitInsn(Opcodes.ATHROW);
            getter.visitMaxs(3, 1);
            getter.visitEnd();
        }
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        class SignatureLoader extends ClassLoader {

            SignatureLoader() {
                super(MTEXLTurbineSteam.class.getClassLoader());
            }

            @Override
            protected Class<?> loadClass(String type, boolean resolve) throws ClassNotFoundException {
                if (type.equals("net.minecraft.client.resources.IResourceManager"))
                    throw new ClassNotFoundException(type);
                return super.loadClass(type, resolve);
            }

            @Override
            public InputStream getResourceAsStream(String resource) {
                return resource.equals(name + ".class") ? new ByteArrayInputStream(bytes)
                    : super.getResourceAsStream(resource);
            }

            Class<?> define() {
                return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
            }
        }
        Class<?> type = new SignatureLoader().define();
        try {
            type.getDeclaredMethod("getCasing");
            throw new AssertionError("reflective getter search did not resolve the missing client signature");
        } catch (NoClassDefFoundError expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("IResourceManager"),
                "the former reflective path loads an unrelated unavailable client resource signature");
        }
        return (MTEXLTurbineBase) type.getDeclaredConstructor()
            .newInstance();
    }

    private static final AtomicInteger unknownGetterCalls = new AtomicInteger();

    private static final class UnknownGetterSteam extends MTEXLTurbineSteam {

        UnknownGetterSteam() {
            super("unknown_cold_metadata");
        }

        @Override
        protected Materials getFrameMaterial() {
            unknownGetterCalls.incrementAndGet();
            throw new AssertionError("unknown metadata getter was executed");
        }
    }

    private static final class Hints extends CommonProxy {

        @Override
        public void hintParticle(net.minecraft.world.World world, int x, int y, int z, Block block, int metadata) {
            HologramCapture.hint(world, x, y, z, block, metadata);
        }
    }
}
