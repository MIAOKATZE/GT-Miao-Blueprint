package com.miaokatze.gtmb.hologram;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

/**
 * Extension point for declaration-only adapters. Adapters must read immutable declaration data and return an
 * equivalent StructureLib declaration. They must never invoke check, adders, pass/fail callbacks, placement,
 * arbitrary deferred factories, or mutate a controller/world. Registration alone grants no placement authority:
 * the returned declaration still passes through the usual candidate and shared-cell intersection rules.
 */
public final class HologramDeclarationAdapters {

    private static final Map<IStructureElement<?>, Declaration> DECLARATIONS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, Declaration> RESOLVED = new WeakHashMap<>();
    private static final Map<Class<?>, Function<IStructureElement<?>, Declaration>> READERS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();

    private HologramDeclarationAdapters() {}

    /** A backing declaration and an optional reviewed, context-free trigger projection. */
    public static final class Declaration {

        private final Supplier<IStructureElement<?>> source;
        private final Function<ItemStack, ItemStack> signal;
        private IStructureElement<?> backing;

        public Declaration(IStructureElement<?> backing) {
            this(() -> backing, null);
        }

        public Declaration(Supplier<IStructureElement<?>> source, Function<ItemStack, ItemStack> signal) {
            if (source == null) throw new IllegalArgumentException("Missing declaration reader");
            this.source = source;
            this.signal = signal;
        }

        synchronized IStructureElement<?> backing() {
            if (backing == null) backing = source.get();
            return backing;
        }

        ItemStack trigger(ItemStack original) {
            // An adapter never receives the machine's mutable trigger instance.
            return signal == null || original == null ? original : signal.apply(original.copy());
        }
    }

    /** Register a declaration produced by a reviewed factory, without evaluating that factory's callbacks. */
    public static void register(IStructureElement<?> element, Declaration declaration) {
        if (element == null || declaration == null) throw new IllegalArgumentException("Missing declaration");
        synchronized (DECLARATIONS) {
            DECLARATIONS.put(element, declaration);
            GENERATION.incrementAndGet();
        }
    }

    /**
     * Register an adapter for an exact wrapper class. Subclasses are deliberately excluded: they may change
     * structural acceptance or carry private NBT requirements. The reader must obey this class's read-only contract.
     */
    public static <E extends IStructureElement<?>> void registerReader(Class<E> type, Function<E, Declaration> reader) {
        if (type == null || reader == null) throw new IllegalArgumentException("Missing declaration reader");
        synchronized (DECLARATIONS) {
            READERS.put(type, element -> reader.apply(type.cast(element)));
            RESOLVED.clear();
            GENERATION.incrementAndGet();
        }
    }

    /** Scoped exact-class reader for the public API. Removal invalidates all derived declaration caches. */
    public static <E extends IStructureElement<?>> AutoCloseable registerScopedReader(Class<E> type,
        Function<E, Declaration> reader) {
        if (type == null || reader == null) throw new IllegalArgumentException("Missing declaration reader");
        Function<IStructureElement<?>, Declaration> bridge = element -> reader.apply(type.cast(element));
        synchronized (DECLARATIONS) {
            if (READERS.putIfAbsent(type, bridge) != null)
                throw new IllegalArgumentException("Reader already registered");
            RESOLVED.clear();
            GENERATION.incrementAndGet();
        }
        return () -> {
            synchronized (DECLARATIONS) {
                if (READERS.remove(type, bridge)) {
                    RESOLVED.clear();
                    GENERATION.incrementAndGet();
                }
            }
        };
    }

    static long generation() {
        return GENERATION.get();
    }

    static Declaration declaration(IStructureElement<?> element) {
        if (element == null) return null;
        synchronized (DECLARATIONS) {
            Declaration known = DECLARATIONS.get(element);
            if (known != null) return known;
            known = RESOLVED.get(element);
            if (known != null) return known;
            Function<IStructureElement<?>, Declaration> reader = READERS.get(element.getClass());
            if (reader == null) return null;
            known = reader.apply(element);
            if (known != null) RESOLVED.put(element, known);
            return known;
        }
    }
}
