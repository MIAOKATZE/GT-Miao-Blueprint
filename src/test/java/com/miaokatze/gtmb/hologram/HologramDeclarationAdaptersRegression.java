package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IWithExtendedContext;
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
            nativeOutputProtocol();
            dynamicOutputProtocol();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void nativeOutputProtocol() {
        ItemStack signal = new ItemStack(Items.feather);
        HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
        candidates.collect(NativeOutput.STACKS, new Object(), null, 0, 0, 0, signal, null, 0);
        SimpleAssert.eq(1, candidates.stacks.size(), "an ordinary enum exposes its public native output");
        SimpleAssert.that(!candidates.complete, "a concrete native output is not exhaustive acceptance");
        SimpleAssert
            .that(candidates.allows(new ItemStack(Blocks.stone), true), "the exact native output has authority");
        SimpleAssert.that(!candidates.allows(new ItemStack(Blocks.dirt), false), "unadvertised materials stay unknown");
        SimpleAssert.eq(1, signal.stackSize, "a native output reader receives a disposable trigger");

        ChannelDataAccessor.setChannelData(signal, "native_output", 2);
        candidates = new HologramDeclarationCandidates();
        candidates.collect(
            StructureUtility.onElementPass(
                context -> { throw new AssertionError("pass callback executed during native output collection"); },
                StructureUtility.withChannel("native_output", NativeOutput.STACKS)),
            new Object(),
            null,
            0,
            0,
            0,
            signal,
            null,
            0);
        SimpleAssert.that(
            candidates.allows(new ItemStack(Blocks.dirt), true),
            "transparent wrappers retain their projected native signal");

        IStructureElement<Object> advisory = new DeclaredWrapper(NativeOutput.STACKS);
        HologramDeclarationAdapters
            .register(advisory, new HologramDeclarationAdapters.Declaration(() -> NativeOutput.STACKS, null, false));
        candidates = new HologramDeclarationCandidates();
        candidates.collect(advisory, new Object(), null, 0, 0, 0, signal, null, 0);
        SimpleAssert.eq(1, candidates.stacks.size(), "advisory backing retains visible native outputs");
        SimpleAssert.that(
            !candidates.allows(new ItemStack(Blocks.stone), false),
            "an advisory native output cannot grant placement authority");

        IStructureElement<Object> advisoryPure = new DeclaredWrapper(null);
        HologramDeclarationAdapters.register(
            advisoryPure,
            new HologramDeclarationAdapters.Declaration(
                () -> new HologramPureElementsRegression.Leaf(true),
                null,
                false));
        SimpleAssert.that(
            HologramElementCatalog.pure(advisoryPure) == null,
            "advisory backing cannot inherit a pure placement contract");
        candidates = new HologramDeclarationCandidates();
        candidates.collect(advisoryPure, new Object(), null, 0, 0, 0, signal, null, 0);
        SimpleAssert.that(!candidates.stacks.isEmpty(), "advisory pure data remains available for display");
        SimpleAssert.that(
            !candidates.allows(new ItemStack(Blocks.stone), false),
            "advisory pure data grants no placement authority");

        IStructureElement<Object> advisoryChain = StructureUtility.ofChain(StructureUtility.ofBlock(Blocks.stone, 0));
        HologramDeclarationAdapters.register(
            advisoryChain,
            new HologramDeclarationAdapters.Declaration(() -> StructureUtility.ofBlock(Blocks.dirt, 0), null, false));
        candidates = new HologramDeclarationCandidates();
        candidates.collect(advisoryChain, new Object(), null, 0, 0, 0, signal, null, 0);
        SimpleAssert
            .eq(2, candidates.stacks.size(), "advisory data supplements the original chain's visible candidates");
        SimpleAssert.that(
            candidates.allows(new ItemStack(Blocks.stone), true)
                && !candidates.allows(new ItemStack(Blocks.dirt), false),
            "the original chain retains its own proof without inheriting advisory placement authority");

        for (IStructureElement<Object> unknown : Arrays.asList(
            NativeOutput.PREDICATE_ONLY,
            NativeOutput.NO_OUTPUT,
            NativeOutput.FAIL,
            StructureUtility.onlyIf(context -> false, NativeOutput.STACKS),
            StructureUtility.defer(context -> { throw new IllegalStateException("deferred output unavailable"); }),
            StructureUtility.lazy(context -> { throw new IllegalStateException("lazy output unavailable"); }))) {
            candidates = new HologramDeclarationCandidates();
            candidates.collect(unknown, new Object(), null, 0, 0, 0, signal, null, 0);
            SimpleAssert.that(!candidates.complete && candidates.stacks.isEmpty(), "unresolved output remains unknown");
            SimpleAssert
                .that(!candidates.allows(new ItemStack(Blocks.stone), false), "unknown output grants no authority");
        }

        NativeOutputLeaf leaf = new NativeOutputLeaf();
        HologramDeclarationCollector collector = new HologramDeclarationCollector();
        HologramDeclarationCandidates first = collector.collect(leaf, null, null, 0, 0, 0, "", signal, null);
        SimpleAssert
            .that(first.allows(new ItemStack(Blocks.stone), true), "a named non-factory class exposes native output");
        SimpleAssert.that(
            first == collector.collect(leaf, null, null, 0, 0, 0, "", signal, null),
            "repeated reads reuse the existing contextual declaration cache");
        SimpleAssert.eq(1, leaf.reads, "one contextual output is collected once");
        SimpleAssert.that(
            collector.collect(leaf, null, null, 1, 0, 0, "", signal, null)
                .allows(new ItemStack(Blocks.dirt), true),
            "native output caches remain specific to their position");
        signal.stackSize = 2;
        collector.collect(leaf, null, null, 0, 0, 0, "", signal, null);
        SimpleAssert.eq(3, leaf.reads, "a new signal has its own native output cache entry");
    }

    private static void dynamicOutputProtocol() {
        ItemStack signal = new ItemStack(Items.feather);
        IStructureElement<Object> leaf = StructureUtility.onElementPass(
            context -> { throw new AssertionError("native output ran an element-pass callback"); },
            NativeOutput.STACKS);
        for (boolean lazy : new boolean[] { true, false }) {
            AtomicInteger reads = new AtomicInteger();
            java.util.function.Function<Object, IStructureElement<Object>> resolver = context -> {
                reads.incrementAndGet();
                return leaf;
            };
            IStructureElement<Object> dynamic = lazy ? StructureUtility.lazy(resolver)
                : StructureUtility.defer(resolver);
            HologramDeclarationCollector collector = new HologramDeclarationCollector();
            HologramDeclarationCandidates output = collector.collect(dynamic, null, null, 0, 0, 0, "", signal, null);
            SimpleAssert.that(output.allows(new ItemStack(Blocks.stone), true), "standard dynamic output is usable");
            SimpleAssert.that(!output.complete, "standard dynamic output remains partial");
            collector.collect(dynamic, null, null, 0, 0, 0, "", signal, null);
            SimpleAssert.eq(1, reads.get(), "standard native resolver is read once per cached output");
            if (lazy) SimpleAssert.that(
                HologramElementCatalog.parts(dynamic)
                    .contains(leaf),
                "public lazy initialization exposes its real backing declaration");
        }

        AtomicInteger predicates = new AtomicInteger();
        IStructureElement<Object> conditional = StructureUtility.onlyIf(context -> {
            predicates.incrementAndGet();
            return true;
        }, leaf);
        HologramDeclarationCollector collector = new HologramDeclarationCollector();
        HologramDeclarationCandidates output = collector.collect(conditional, null, null, 0, 0, 0, "", signal, null);
        SimpleAssert.that(
            output.allows(new ItemStack(Blocks.stone), true) && !output.complete,
            "onlyIf uses its native output contract");
        collector.collect(conditional, null, null, 0, 0, 0, "", signal, null);
        SimpleAssert.eq(1, predicates.get(), "onlyIf is evaluated only by the cached public native output read");

        AtomicInteger contexts = new AtomicInteger();
        Object extended = new Object();
        IStructureElement<Object> contextualLeaf = new NativeOutputLeaf() {

            @Override
            public BlocksToPlace getBlocksToPlace(Object context, World world, int x, int y, int z, ItemStack trigger,
                AutoPlaceEnvironment environment) {
                SimpleAssert.that(context == extended, "withContext passes its actual extended context");
                return BlocksToPlace.create(Blocks.stone, 0);
            }
        };
        IWithExtendedContext<Object> context = () -> {
            contexts.incrementAndGet();
            return extended;
        };
        output = new HologramDeclarationCandidates();
        output.collect(StructureUtility.withContext(contextualLeaf), context, null, 0, 0, 0, signal, null, 0);
        SimpleAssert.that(
            output.allows(new ItemStack(Blocks.stone), true) && !output.complete,
            "withContext retains native outputs");
        SimpleAssert.eq(1, contexts.get(), "withContext uses the public protocol's single context delegation");

        IStructureElement<Object> air = StructureUtility.lazy(ignored -> StructureUtility.isAir());
        output = new HologramDeclarationCandidates();
        output.collect(air, new Object(), null, 0, 0, 0, signal, null, 0);
        SimpleAssert.that(
            output.stacks.isEmpty() && !output.complete && HologramElementCatalog.nativeAir(air),
            "a null native output invents no material while retaining an initialized real air declaration");

        ChannelDataAccessor.setChannelData(signal, "first", 1);
        ChannelDataAccessor.setChannelData(signal, "second", 2);
        output = new HologramDeclarationCandidates();
        output.collect(
            StructureUtility.ofChain(
                StructureUtility.withChannel("first", NativeOutput.STACKS),
                StructureUtility.withChannel("second", NativeOutput.STACKS)),
            new Object(),
            null,
            0,
            0,
            0,
            signal,
            null,
            0);
        SimpleAssert.that(
            output.allows(new ItemStack(Blocks.stone), true) && output.allows(new ItemStack(Blocks.dirt), true),
            "one leaf reached with different channel inputs retains both native outputs");
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

    private static class NativeOutputLeaf extends DeclaredWrapper {

        int reads;

        NativeOutputLeaf() {
            super(null);
        }

        @Override
        public BlocksToPlace getBlocksToPlace(Object context, World world, int x, int y, int z, ItemStack signal,
            AutoPlaceEnvironment environment) {
            reads++;
            return BlocksToPlace.create(x == 0 ? Blocks.stone : Blocks.dirt, 0);
        }
    }

    private enum NativeOutput implements IStructureElement<Object> {

        STACKS,
        PREDICATE_ONLY,
        NO_OUTPUT,
        FAIL;

        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("native output collection called check");
        }

        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("native output collection called hints");
        }

        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("native output collection called placement");
        }

        public BlocksToPlace getBlocksToPlace(Object context, World world, int x, int y, int z, ItemStack signal,
            AutoPlaceEnvironment environment) {
            if (this == NO_OUTPUT) return null;
            if (this == FAIL) throw new IllegalStateException("native output unavailable");
            if (this == PREDICATE_ONLY)
                return BlocksToPlace.create(stack -> { throw new AssertionError("inventory predicate executed"); });
            Block block = signal.stackSize == 1 ? Blocks.stone : Blocks.dirt;
            signal.stackSize = 99;
            return BlocksToPlace.create(block, 0);
        }
    }
}
