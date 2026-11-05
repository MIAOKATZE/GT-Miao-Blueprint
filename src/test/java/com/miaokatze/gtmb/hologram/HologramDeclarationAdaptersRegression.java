package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.util.CasingTier;
import gregtech.api.util.GTStructureUtility;

/** Real standard factories and callback tripwires; no private captured-field fixtures. */
final class HologramDeclarationAdaptersRegression {

    static void run() {
        try {
            factoryMetadata();
            ItemStack signal = new ItemStack(Items.feather, 2);
            IStructureElement<Object> tiered = StructureUtility.<Object, Integer>ofBlocksTiered(
                (block, meta) -> { throw new AssertionError("tier converter executed"); },
                Arrays.asList(Pair.of(Blocks.stone, 0), Pair.of(Blocks.dirt, 0)),
                -1,
                (machine, tier) -> { throw new AssertionError("tier setter executed"); },
                machine -> { throw new AssertionError("tier getter executed"); });
            IStructureElement<Object> decorated = StructureUtility.withDescription(
                Arrays.asList("fixture"),
                StructureUtility.onElementFail(
                    machine -> { throw new AssertionError("onFail executed"); },
                    StructureUtility.onElementPass(
                        machine -> { throw new AssertionError("onPass executed"); },
                        StructureUtility.withChannel("fixture_tier", tiered))));
            ChannelDataAccessor.setChannelData(signal, "fixture_tier", 1);
            SimpleAssert.eq(
                Blocks.stone,
                HologramCasingFallback.resolve(decorated, signal).block,
                "description/pass/fail wrappers preserve native channel declaration");
            HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
            candidates.collect(decorated, new Object(), null, 0, 0, 0, signal, null, 0);
            SimpleAssert.eq(2, candidates.stacks.size(), "transparent wrappers expose both actual tier entries");

            IStructureElement<Object> deferred = StructureUtility
                .defer(machine -> { throw new AssertionError("deferred controller factory executed"); });
            IStructureElement<Object> conditional = StructureUtility
                .onlyIf(machine -> { throw new AssertionError("conditional controller predicate executed"); }, tiered);
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
                "unadapted defer is not evaluated during hints");
            SimpleAssert.eq(
                false,
                hint.invoke(capture, conditional, null, 0, 0, 0, signal, 0),
                "unadapted conditional is not evaluated during hints");
            HologramDeclarationAdapters
                .register(deferred, new HologramDeclarationAdapters.Declaration(() -> tiered, copied -> {
                    copied.stackSize = 1;
                    return copied;
                }));
            SimpleAssert.eq(
                Blocks.stone,
                HologramCasingFallback.resolve(deferred, signal).block,
                "explicit provider makes declared leaf visible without invoking original factory");
            SimpleAssert.eq(2, signal.stackSize, "adapter signal projection cannot modify native trigger");
            HologramDeclarationAdapters.registerReader(
                DeclaredWrapper.class,
                wrapper -> new HologramDeclarationAdapters.Declaration(wrapper.declaration()));
            SimpleAssert.eq(
                Blocks.dirt,
                HologramCasingFallback
                    .resolve(new DeclaredWrapper(StructureUtility.ofBlock(Blocks.dirt, 0)), signal).block,
                "explicit wrapper API supplies immutable declaration without reflection guesses");
            SimpleAssert.that(
                HologramCasingFallback.resolve(new DifferentWrapper(StructureUtility.ofBlock(Blocks.dirt, 0)), signal)
                    == null,
                "subclass private acceptance cannot inherit declaration reader authority");
            nativeFactoryRecorder(signal);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void factoryMetadata() throws ReflectiveOperationException {
        IStructureElement<Object> stone = StructureUtility.ofBlock(Blocks.stone, 0);
        IStructureElement<Object> dirt = StructureUtility.ofBlock(Blocks.dirt, 0);
        IStructureElement<Object> first = StructureUtility.withChannel("first", stone);
        IStructureElement<Object> second = StructureUtility.withChannel("second", dirt);
        SimpleAssert.that(first.getClass() == second.getClass(), "real channel wrappers share their factory class");
        Method factory = HologramDeclarationWrappers.factory(first.getClass());
        SimpleAssert.that(
            factory == HologramDeclarationWrappers.factory(second.getClass()),
            "repeat factory lookup reuses resolved immutable metadata");
        SimpleAssert.that(
            HologramDeclarationWrappers.backing(first) == stone && HologramDeclarationWrappers.backing(second) == dirt,
            "class metadata never caches another instance's backing declaration");
        SimpleAssert.eq("first", HologramDeclarationWrappers.channel(first), "first wrapper retains its own channel");
        SimpleAssert
            .eq("second", HologramDeclarationWrappers.channel(second), "same-class wrapper keeps another channel");

        DifferentWrapper dynamic = new DifferentWrapper(stone);
        SimpleAssert.that(
            HologramDeclarationWrappers.factory(dynamic.getClass()) == null
                && HologramDeclarationWrappers.backing(dynamic) == null,
            "legitimate no-factory metadata does not invent backing authority");
        AutoCloseable reader = HologramDeclarationAdapters
            .registerScopedReader(DifferentWrapper.class, ignored -> new HologramDeclarationAdapters.Declaration(dirt));
        try {
            SimpleAssert.that(
                HologramDeclarationWrappers.backing(dynamic) == dirt,
                "a later exact-class reader takes precedence over cached negative factory metadata");
        } finally {
            try {
                reader.close();
            } catch (Exception failure) {
                throw new AssertionError("scoped metadata reader cleanup", failure);
            }
        }
        SimpleAssert.that(
            HologramDeclarationWrappers.backing(dynamic) == null,
            "removed reader cannot survive through class metadata cache");

        FactoryLoader incomplete = new FactoryLoader();
        Class<?> child = incomplete.loadClass(FactoryLoader.CHILD.replace('/', '.'));
        for (int attempt = 0; attempt < 2; attempt++) {
            int before = incomplete.missingAttempts;
            boolean failed = false;
            try {
                HologramDeclarationWrappers.factory(child);
            } catch (RuntimeException | LinkageError unavailableType) {
                failed = true;
            }
            SimpleAssert.that(
                failed && incomplete.missingAttempts > before,
                "unresolvable enclosing signature remains a failure and is retried, never cached as null");
        }
        incomplete.missingAvailable = true;
        Method recovered = HologramDeclarationWrappers.factory(child);
        SimpleAssert
            .eq("factory", recovered.getName(), "failed metadata resolves after its dependency becomes available");
        FactoryLoader other = new FactoryLoader();
        other.missingAvailable = true;
        Class<?> otherChild = other.loadClass(FactoryLoader.CHILD.replace('/', '.'));
        Method otherFactory = HologramDeclarationWrappers.factory(otherChild);
        SimpleAssert.that(
            child != otherChild && recovered.getDeclaringClass()
                .getClassLoader() == incomplete
                && otherFactory.getDeclaringClass()
                    .getClassLoader() == other,
            "same binary names from distinct classloaders never share factory metadata");
    }

    /** JVM signature resolution fixture, without native game objects or forced GC. */
    private static final class FactoryLoader extends ClassLoader {

        static final String OWNER = "gtmb0314/factory/Owner", CHILD = OWNER + "$Element",
            MISSING = "gtmb0314/factory/Missing";
        boolean missingAvailable;
        int missingAttempts;

        FactoryLoader() {
            super(HologramDeclarationAdaptersRegression.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            if (MISSING.equals(internal)) {
                missingAttempts++;
                if (!missingAvailable) throw new ClassNotFoundException(name);
            } else if (!OWNER.equals(internal) && !CHILD.equals(internal)) throw new ClassNotFoundException(name);
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
            String signature = "(L" + MISSING + ";)Ljava/lang/Object;";
            if (CHILD.equals(internal)) writer.visitOuterClass(OWNER, "factory", signature);
            writer.visitInnerClass(CHILD, null, "Element", 0);
            MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
            if (OWNER.equals(internal)) {
                MethodVisitor factory = writer
                    .visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", signature, null, null);
                factory.visitCode();
                factory.visitInsn(Opcodes.ACONST_NULL);
                factory.visitInsn(Opcodes.ARETURN);
                factory.visitMaxs(1, 1);
                factory.visitEnd();
            }
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static void nativeFactoryRecorder(ItemStack signal) {
        List<Pair<Block, Integer>> registry = CasingTier.getCasingList();
        List<Pair<Block, Integer>> saved = new ArrayList<>(registry);
        try {
            registry.clear();
            IStructureElement<Object> nativeFactory = GTStructureUtility.chainAllCasings(
                -1,
                (machine, tier) -> { throw new AssertionError("native casing setter executed"); },
                machine -> { throw new AssertionError("native casing getter executed"); });
            // Unit tests do not launch Mixin. This is precisely the production RETURN recorder call.
            HologramGregTechDeclarations.casings(nativeFactory);
            registry.add(Pair.of(Blocks.stone, 0));
            registry.add(Pair.of(Blocks.dirt, 0));
            ChannelDataAccessor.setChannelData(signal, "casing", 2);
            SimpleAssert.eq(
                Blocks.dirt,
                HologramCasingFallback.resolve(nativeFactory, signal).block,
                "factory provenance resolves registry after declaration construction");
            HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
            candidates.collect(nativeFactory, new Object(), null, 0, 0, 0, signal, null, 0);
            SimpleAssert.eq(2, candidates.stacks.size(), "native factory exposes only its declared registry entries");
            SimpleAssert.that(
                candidates.stacks.stream()
                    .noneMatch(
                        stack -> stack.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.cobblestone)),
                "unrelated registered blocks are never candidates");
        } finally {
            registry.clear();
            registry.addAll(saved);
        }
    }

    private static class DeclaredWrapper implements IStructureElement<Object> {

        private final IStructureElement<?> declared;

        DeclaredWrapper(IStructureElement<?> declared) {
            this.declared = declared;
        }

        IStructureElement<?> declaration() {
            return declared;
        }

        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("private check callback executed");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("private hint callback executed");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("private placement callback executed");
        }
    }

    private static final class DifferentWrapper extends DeclaredWrapper {

        DifferentWrapper(IStructureElement<?> declared) {
            super(declared);
        }
    }
}
