package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Native raw restoration: establish the world before readers parse recipes or defer their network identity. */
final class HologramCutTiles {

    private HologramCutTiles() {}

    /** Read-only registry identity; never invokes an optional tile factory or reader. */
    static Class<?> registeredType(String id) {
        Object type = Registry.TYPES.get(id);
        return type instanceof Class<?> ? (Class<?>) type : null;
    }

    static TileEntity create(WorldServer world, Block block, int metadata, NBTTagCompound tag) throws IOException {
        try {
            Object selected = Registry.TYPES.get(tag.getString("id"));
            if (!(selected instanceof Class<?>) || !TileEntity.class.isAssignableFrom((Class<?>) selected))
                throw new IOException("原方块实体类型未注册，完整剪切记录已保留");
            Class<?> type = (Class<?>) selected;
            TileEntity tile;
            try {
                tile = block.createTileEntity(world, metadata);
            } catch (RuntimeException | LinkageError unavailableFactory) {
                // Ordinary chunk loading does not require the block factory to succeed.
                tile = null;
            }
            if (tile != null && tile.getWorldObj() != null && tile.getWorldObj() != world)
                throw new IOException("原生工厂复用了其他世界的方块实体");
            // The ordinary native chunk reader selects the registered NBT class, including compatibility
            // subclasses which differ from today's block factory. Preserve that native fallback exactly.
            if (tile == null || tile.getClass() != type) tile = (TileEntity) type.getConstructor()
                .newInstance();
            tile.setWorldObj(world);
            tile.xCoord = tag.getInteger("x");
            tile.yCoord = tag.getInteger("y");
            tile.zCoord = tag.getInteger("z");
            // Use a complete independent input: native readers may modify recipe/item NBT while loading.
            tile.readFromNBT((NBTTagCompound) tag.copy());
            return tile;
        } catch (IOException failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IOException("原生方块实体读取失败，完整剪切记录已保留", failure);
        }
    }

    /** No machine tick: only finish AE's deferred node read, after every restored tile has been attached. */
    static void ready(TileEntity tile) {
        if (tile instanceof appeng.tile.AEBaseTile) ((appeng.tile.AEBaseTile) tile).onReady();
        if (tile instanceof IGridProxyable) {
            AENetworkProxy proxy = ((IGridProxyable) tile).getProxy();
            // onReady creates a node only once. GT's later native first tick can safely call it again.
            if (proxy != null && !proxy.isReady()) proxy.onReady();
        }
    }

    private static final class Registry {

        // Keep the original map reference: later mod registrations remain visible without repeated reflection.
        static final Map<?, ?> TYPES = types();

        private static Map<?, ?> types() {
            try {
                return (Map<?, ?>) ReflectionHelper.findField(TileEntity.class, "nameToClassMap", "field_145855_i")
                    .get(null);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
