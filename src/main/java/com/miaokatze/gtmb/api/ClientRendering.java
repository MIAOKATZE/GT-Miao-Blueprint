// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.api;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.miaokatze.gtmb.client.compat.botania.BotaniaClientCompatibility;
import com.miaokatze.gtmb.client.compat.cookingforblockheads.CookingForBlockheadsClientCompatibility;
import com.miaokatze.gtmb.client.compat.floodlights.FloodLightsClientCompatibility;
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

        /** Audited data carriers whose native representation intentionally has no visible model or material. */
        default boolean invisible(Block block, int metadata) {
            return false;
        }

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

        /** Explicitly accepts a cell whose geometry is supplied by another cell in the same isolated access. */
        default boolean allowsEmptyGeometry(Block block, int metadata, IBlockAccess access, int x, int y, int z) {
            return false;
        }
    }

    public interface MultipartAdapter {

        boolean supports(String type, Object detachedPart);

        Object create(String type, Object detachedPart, NBTTagCompound payload) throws ReflectiveOperationException;

        default void prepare(Object detachedPart) throws ReflectiveOperationException {}
    }

    private abstract static class NativeBridge implements HologramNativeRenderRegistry.Adapter {

        private final boolean deferredWorldOccupancy;

        private NativeBridge(Adapter adapter) {
            Class<?> type = adapter.getClass();
            // These exact final stock adapters identify blocks without reading world state. Their geometry
            // receives full snapshot access, independently of the renderer's deferred face/outline index.
            deferredWorldOccupancy = type == BotaniaClientCompatibility.class
                || type == CookingForBlockheadsClientCompatibility.class
                || type == FloodLightsClientCompatibility.class;
        }
    }

    /** Internal renderer query. Extensions cannot mark their registrations as audited. */
    public static boolean stockDeferredWorldBridge(HologramNativeRenderRegistry.Adapter adapter) {
        return adapter instanceof NativeBridge bridge && bridge.deferredWorldOccupancy;
    }

    public static GTMBExtensions.Registration register(String id, Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        return GTMBExtensions.install(id, "native-render", () -> {
            HologramNativeRenderRegistry.Adapter bridge = new NativeBridge(adapter) {

                public boolean supports(Block block) {
                    return adapter.supports(block);
                }

                public boolean invisible(Block block, int meta) {
                    return adapter.invisible(block, meta);
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

                public boolean allowsEmptyGeometry(Block block, int metadata, IBlockAccess access, int x, int y,
                    int z) {
                    return adapter.allowsEmptyGeometry(block, metadata, access, x, y, z);
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
