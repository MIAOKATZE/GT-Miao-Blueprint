package com.miaokatze.gtmb.api;

import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.hologram.HologramDeclarationAdapters;

/**
 * Reviewed declaration data only. Never call checks, hatch adders, setters, placement or deferred controller factories.
 */
public final class Declarations {

    private Declarations() {}

    public static final class Declaration {

        private final Supplier<IStructureElement<?>> source;
        private final Function<ItemStack, ItemStack> signal;

        public Declaration(IStructureElement<?> backing) {
            this(() -> backing, null);
        }

        /** source must be context-free and side-effect-free; signal receives a disposable copy. */
        public Declaration(Supplier<IStructureElement<?>> source, Function<ItemStack, ItemStack> signal) {
            if (source == null) throw new IllegalArgumentException("source");
            this.source = source;
            this.signal = signal;
        }
    }

    /**
     * Exact wrapper classes only. Registrations affect capture, candidate intersection and authoritative validation.
     */
    public static <E extends IStructureElement<?>> GTMBExtensions.Registration registerReader(String id, Class<E> type,
        Function<E, Declaration> reader) {
        if (reader == null) throw new IllegalArgumentException("reader");
        return GTMBExtensions.install(id, "declaration", () -> {
            AutoCloseable handle = HologramDeclarationAdapters.registerScopedReader(type, element -> {
                Declaration result = reader.apply(element);
                return result == null ? null
                    : new HologramDeclarationAdapters.Declaration(result.source, result.signal);
            });
            return () -> close(handle);
        });
    }

    static void close(AutoCloseable handle) {
        try {
            handle.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Extension removal failed", failure);
        }
    }
}
