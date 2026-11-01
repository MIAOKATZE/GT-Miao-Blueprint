package com.miaokatze.gtmb.api;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.hologram.HologramDeclarationAdapters;

/**
 * Reviewed declaration data only. Never call checks, hatch adders, setters, placement or deferred controller factories.
 */
public final class Declarations {

    private Declarations() {}

    /**
     * Explicit immutable leaf contract. GTMB consumes this data instead of invoking StructureLib check, hint or
     * placement callbacks. All methods must be context-free, side-effect-free and independent of mutable controller
     * state. Returned stacks and arrays must be disposable copies. The leaf describes only its own cell; it does not
     * certify a controller's geometry, whole-structure constraints or a saved blueprint's source. Transparent wrappers
     * can still be registered through the existing reader API.
     */
    public interface PureElement<C> extends IStructureElement<C> {

        ItemStack primary();

        List<ItemStack> candidates();

        /** Whether candidates enumerates every accepted item; individual targets still require accepts. */
        boolean complete();

        String role();

        boolean accepts(ItemStack sample);

        /** A pure automatic-placement decision, separate from acceptance of an explicit user selection. */
        boolean placementAllows(ItemStack sample);

        int targetMeta(ItemStack sample);

        boolean matches(WorldState state);

        boolean matchesTarget(WorldState state, ItemStack target);

        default String candidateBoundary() {
            return "仅列出已声明候选，其他扩展尚未取得自动施工资格";
        }

        default String candidateRoleId(ItemStack sample) {
            return "";
        }

        default String candidateRoleLabel(ItemStack sample) {
            return "hatch".equals(role()) ? "已声明仓室候选" : "外壳/方块";
        }

        /** Absolute ForgeDirection ordinals; an empty result grants no explicit facing change. */
        default int[] legalFacings(ItemStack sample) {
            return new int[0];
        }
    }

    /**
     * Only observations needed by a pure leaf; no mutable world, tile or controller reference is exposed. machineId
     * and materialDamage are -1 when an identity has not been proven. Metadata is the observed world metadata and
     * must not be substituted for an unknown complete material damage.
     */
    public static final class WorldState {

        public final boolean loaded;
        public final Block block;
        public final int metadata, machineId, materialDamage;
        public final Class<?> tileType;

        public WorldState(boolean loaded, Block block, int metadata, Class<?> tileType, int machineId,
            int materialDamage) {
            this.loaded = loaded;
            this.block = block;
            this.metadata = metadata;
            this.tileType = tileType;
            this.machineId = machineId;
            this.materialDamage = materialDamage;
        }
    }

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
