package com.miaokatze.gtmb.client.hologram;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
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
    }

    /**
     * Converts a newly loaded, detached multipart into its static client variant.
     * Implementations must never reuse a live part or invoke network/placement hooks.
     */
    public interface MultipartAdapter {

        boolean supports(String type, Object detachedPart);

        Object create(String type, Object detachedPart, NBTTagCompound payload) throws ReflectiveOperationException;
    }

    private static final List<Adapter> ADAPTERS = new CopyOnWriteArrayList<>();
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
        if (HologramAeNativeRender.INSTANCE.supports(block)) return HologramAeNativeRender.INSTANCE;
        for (Adapter adapter : ADAPTERS) if (adapter.supports(block)) return adapter;
        return null;
    }
}
