package com.miaokatze.gtmb.client.hologram;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;

/** Client extension for audited static ISBRH families. Register in client initialization only. */
public final class HologramNativeRenderRegistry {

    /**
     * An adapter opts a family into snapshot rendering. Its factory must return a new tile,
     * and its renderer must only read the supplied access/tiles; world/player mutations,
     * tick/placement hooks and arbitrary TESRs are outside this static rendering contract.
     */
    public interface Adapter {

        boolean supports(Block block);

        /** Explicit native data-carrier policy; independent of missing/empty models and neighbor-owned geometry. */
        default boolean invisible(Block block, int metadata) {
            return false;
        }

        default boolean requiresTile(Block block, int metadata) {
            return block.hasTileEntity(metadata);
        }

        default TileEntity createTile(Block block, int metadata, World isolatedWorld) {
            return block.createTileEntity(isolatedWorld, metadata);
        }

        /** Decode only reviewed visual state. Override to avoid native inventory/event readers. */
        default void loadTile(TileEntity tile, NBTTagCompound payload) {
            tile.readFromNBT(payload);
        }

        /** Return a fresh instance here when the registered handler retains mutable world/tile context. */
        default ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
            return registered;
        }

        default boolean allowsEmptyGeometry(Block block, int metadata, IBlockAccess access, int x, int y, int z) {
            return false;
        }

        default boolean detachedRead(Block block) {
            return false;
        }

        default boolean doubleSided(Block block) {
            return false;
        }

        default RenderScope renderScope(Block block, TileEntity tile, ISimpleBlockRenderingHandler renderer) {
            return null;
        }
    }

    public interface RenderScope extends AutoCloseable {

        IBlockAccess access();

        @Override
        void close();
    }

    /**
     * Converts a newly loaded, detached multipart into its static client variant.
     * Implementations must never reuse a live part or invoke network/placement hooks.
     */
    public interface MultipartAdapter {

        boolean supports(String type, Object detachedPart);

        Object create(String type, Object detachedPart, NBTTagCompound payload) throws ReflectiveOperationException;

        /** Initialize local render caches after binding; do not dispatch native part/network events. */
        default void prepare(Object detachedPart) throws ReflectiveOperationException {}
    }

    private static final List<Adapter> ADAPTERS = new CopyOnWriteArrayList<>();
    private static final List<Adapter> BUILTINS = new CopyOnWriteArrayList<>();
    private static final List<MultipartAdapter> MULTIPART_ADAPTERS = new CopyOnWriteArrayList<>();

    private HologramNativeRenderRegistry() {}

    public static void register(Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("native renderer adapter is required");
        ADAPTERS.add(adapter);
    }

    /** Remove an adapter when its client integration is disabled or replaced. */
    public static boolean unregister(Adapter adapter) {
        return ADAPTERS.remove(adapter);
    }

    /** Built-in family modules remain below every registered community adapter. */
    public static void registerBuiltin(Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("native renderer adapter is required");
        BUILTINS.add(adapter);
    }

    public static boolean unregisterBuiltin(Adapter adapter) {
        return BUILTINS.remove(adapter);
    }

    static Adapter builtin(Block block) {
        for (Adapter adapter : BUILTINS) if (adapter.supports(block)) return adapter;
        return null;
    }

    static boolean detachedRead(Block block, Adapter selected) {
        if (selected != null && selected.detachedRead(block)) return true;
        Adapter family = builtin(block);
        return family != null && family.detachedRead(block);
    }

    static boolean doubleSided(Block block) {
        Adapter selected = adapter(block);
        if (selected != null && selected.doubleSided(block)) return true;
        Adapter family = builtin(block);
        return family != null && family.doubleSided(block);
    }

    /** Only the detached snapshot world's matching tile can acquire a temporary metadata override. */
    public static RenderScope metadataOverride(TileEntity tile) {
        return tile != null && tile.getWorldObj() instanceof HologramRenderWorld
            ? ((HologramRenderWorld) tile.getWorldObj()).metadataOverride(tile)
            : null;
    }

    public static void registerMultipart(MultipartAdapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("native multipart adapter is required");
        MULTIPART_ADAPTERS.add(adapter);
    }

    public static boolean unregisterMultipart(MultipartAdapter adapter) {
        return MULTIPART_ADAPTERS.remove(adapter);
    }

    static MultipartAdapter multipartAdapter(String type, Object part) {
        for (MultipartAdapter adapter : MULTIPART_ADAPTERS) if (adapter.supports(type, part)) return adapter;
        return null;
    }

    static Adapter adapter(Block block) {
        if (HologramQuantumNativeRender.INSTANCE.supports(block)) return HologramQuantumNativeRender.INSTANCE;
        String family = block == null ? ""
            : block.getClass()
                .getName();
        if ((family.equals("appeng.block.storage.BlockDrive") || family.equals("appeng.block.misc.BlockInterface")
            || family.equals("appeng.block.networking.BlockCableBus"))
            && HologramAeNativeRender.INSTANCE.supports(block)) return HologramAeNativeRender.INSTANCE;
        for (Adapter adapter : ADAPTERS) if (adapter.supports(block)) return adapter;
        return builtin(block);
    }
}
