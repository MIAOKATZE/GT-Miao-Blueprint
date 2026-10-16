package com.miaokatze.gtmb.api;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.client.hologram.HologramNativeRenderRegistry;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Load only from client initialization. Static snapshot rendering must never dispatch ticks, events or native TESRs.
 */
@SideOnly(Side.CLIENT)
public final class ClientRendering {

    private ClientRendering() {}

    public interface Adapter {

        boolean supports(Block block);

        default boolean requiresTile(Block block, int metadata) {
            return block.hasTileEntity(metadata);
        }

        default TileEntity createTile(Block block, int metadata, World isolatedWorld) {
            return block.createTileEntity(isolatedWorld, metadata);
        }

        default void loadTile(TileEntity tile, NBTTagCompound payload) {
            tile.readFromNBT(payload);
        }

        default ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
            return registered;
        }
    }

    public interface MultipartAdapter {

        boolean supports(String type, Object detachedPart);

        Object create(String type, Object detachedPart, NBTTagCompound payload) throws ReflectiveOperationException;

        default void prepare(Object detachedPart) throws ReflectiveOperationException {}
    }

    public static GTMBExtensions.Registration register(String id, Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        return GTMBExtensions.install(id, "native-render", () -> {
            HologramNativeRenderRegistry.Adapter bridge = new HologramNativeRenderRegistry.Adapter() {

                public boolean supports(Block block) {
                    return adapter.supports(block);
                }

                public boolean requiresTile(Block block, int meta) {
                    return adapter.requiresTile(block, meta);
                }

                public TileEntity createTile(Block block, int meta, World world) {
                    return adapter.createTile(block, meta, world);
                }

                public void loadTile(TileEntity tile, NBTTagCompound payload) {
                    adapter.loadTile(tile, payload);
                }

                public ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler renderer) {
                    return adapter.renderer(block, renderer);
                }
            };
            HologramNativeRenderRegistry.register(bridge);
            return () -> HologramNativeRenderRegistry.unregister(bridge);
        });
    }

    public static GTMBExtensions.Registration registerMultipart(String id, MultipartAdapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        return GTMBExtensions.install(id, "native-multipart-render", () -> {
            HologramNativeRenderRegistry.MultipartAdapter bridge = new HologramNativeRenderRegistry.MultipartAdapter() {

                public boolean supports(String type, Object part) {
                    return adapter.supports(type, part);
                }

                public Object create(String type, Object part, NBTTagCompound payload)
                    throws ReflectiveOperationException {
                    return adapter.create(type, part, payload);
                }

                public void prepare(Object part) throws ReflectiveOperationException {
                    adapter.prepare(part);
                }
            };
            HologramNativeRenderRegistry.registerMultipart(bridge);
            return () -> HologramNativeRenderRegistry.unregisterMultipart(bridge);
        });
    }
}
