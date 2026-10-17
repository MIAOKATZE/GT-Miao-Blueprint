package com.miaokatze.gtmb.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;

/** Alternative declaration sources for standard GT controllers. This API never initiates a world operation. */
public final class StructureSources {

    private static final Map<Class<?>, Source<?>> SOURCES = new ConcurrentHashMap<>();

    private StructureSources() {}

    public interface Source<C> {

        /** Read declarations only. Null delegates to the native construct/buildPiece capture path. */
        Plan describe(C controller, ItemStack detachedTrigger);
    }

    public static final class Piece {

        public final String name;
        public final int a, b, c;
        private final ItemStack trigger;

        public Piece(String name, int a, int b, int c, ItemStack trigger) {
            if (name == null || name.isEmpty() || trigger == null) throw new IllegalArgumentException("piece");
            this.name = name;
            this.a = a;
            this.b = b;
            this.c = c;
            this.trigger = trigger.copy();
        }

        public ItemStack trigger() {
            return trigger.copy();
        }
    }

    public static final class Plan {

        /** Treat the complete StructureLib declaration graph as immutable for this capture. */
        public final IStructureDefinition<?> definition;
        public final List<Piece> pieces;

        public Plan(IStructureDefinition<?> definition, List<Piece> pieces) {
            if (definition == null || pieces == null || pieces.isEmpty() || pieces.contains(null))
                throw new IllegalArgumentException("structure declaration");
            this.definition = definition;
            this.pieces = Collections.unmodifiableList(new ArrayList<>(pieces));
        }
    }

    /** Exact classes only: subclasses may have different acceptance rules. No global class-name heuristics. */
    public static <C> GTMBExtensions.Registration register(String id, Class<C> type, Source<C> source) {
        if (type == null || source == null) throw new IllegalArgumentException("source");
        return GTMBExtensions.install(id, "structure-source", () -> {
            if (SOURCES.putIfAbsent(type, source) != null)
                throw new IllegalArgumentException("Source already registered");
            return () -> SOURCES.remove(type, source);
        });
    }

    public static boolean hasSource(Class<?> type) {
        return type != null && SOURCES.containsKey(type);
    }

    /** Declaration query used by capture; result is scoped to that invocation and never cached across captures. */
    @SuppressWarnings("unchecked")
    public static Plan resolve(Object controller, ItemStack trigger) {
        if (controller == null || trigger == null) return null;
        Source<Object> source = (Source<Object>) SOURCES.get(controller.getClass());
        return source == null ? null : source.describe(controller, trigger.copy());
    }
}
