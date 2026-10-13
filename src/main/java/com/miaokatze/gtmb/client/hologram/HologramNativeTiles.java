package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Exact optional native APIs; no recursive renderer field discovery or world tile reuse. */
final class HologramNativeTiles {

    private HologramNativeTiles() {}

    static boolean supported(Block block) {
        if (block == null) return false;
        if (HologramNativeRenderRegistry.adapter(block) != null) return true;
        String name = block.getClass()
            .getName();
        return name.startsWith("com.carpentersblocks.block.") || name.equals("codechicken.multipart.BlockMultipart")
            || name.equals("com.creativemd.littletiles.common.blocks.BlockTile")
            || name.equals("gcewing.architecture.common.block.BlockShape")
            || name.equals("crazypants.enderio.conduit.BlockConduitBundle")
            || name.equals("appeng.block.networking.BlockCableBus");
    }

    static TileEntity create(Block block, int meta, NBTTagCompound source, World world, int x, int y, int z)
        throws ReflectiveOperationException {
        if (!supported(block)) return null;
        NBTTagCompound tag = (NBTTagCompound) source.copy();
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        if (block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) return multipart(tag, world, x, y, z);
        HologramNativeRenderRegistry.Adapter adapter = HologramNativeRenderRegistry.adapter(block);
        TileEntity tile = adapter == null ? block.createTileEntity(world, meta)
            : adapter.createTile(block, meta, world);
        if (tile == null) return null;
        if (tile.getWorldObj() != null && tile.getWorldObj() != world)
            throw new IllegalArgumentException("native adapter must return a new detached tile");
        // LittleTiles builds its child tiles in readFromNBT, so the isolated world must be
        // present before deserialization. No validate, load/tick or placement hook runs.
        boolean conduitBundle = block.getClass()
            .getName()
            .equals("crazypants.enderio.conduit.BlockConduitBundle");
        // EnderIO's client NBT reader scans Minecraft.theWorld.playerEntities
        // when worldObj is remote. Its static renderer needs only the decoded
        // local conduit state; leave the new tile detached during that reader.
        tile.setWorldObj(conduitBundle ? null : world);
        tile.xCoord = x;
        tile.yCoord = y;
        tile.zCoord = z;
        if (adapter == null) tile.readFromNBT(tag);
        else adapter.loadTile(tile, tag);
        if (conduitBundle) tile.setWorldObj(world);
        if (block.getClass()
            .getName()
            .equals("appeng.block.networking.BlockCableBus")) restoreAeVisual(tile, tag);
        return tile;
    }

    private static void restoreAeVisual(Object host, NBTTagCompound tag) throws ReflectiveOperationException {
        if (tag.hasKey("gtmb.aeCableVisual", 10)) {
            Object container = host.getClass()
                .getMethod("getCableBus")
                .invoke(host);
            Object cable = container.getClass()
                .getMethod("getPart", net.minecraftforge.common.util.ForgeDirection.class)
                .invoke(container, net.minecraftforge.common.util.ForgeDirection.UNKNOWN);
            if (cable != null) {
                NBTTagCompound visual = tag.getCompoundTag("gtmb.aeCableVisual");
                io.netty.buffer.ByteBuf bytes = io.netty.buffer.Unpooled.buffer(5);
                try {
                    bytes.writeByte(visual.getInteger("connections"));
                    bytes.writeInt(visual.getInteger("channels"));
                    cable.getClass()
                        .getMethod("readFromStream", io.netty.buffer.ByteBuf.class)
                        .invoke(cable, bytes);
                } finally {
                    bytes.release();
                }
            }
        }
    }

    private static TileEntity multipart(NBTTagCompound tag, World world, int x, int y, int z)
        throws ReflectiveOperationException {
        Class<?> registry = Class.forName("codechicken.multipart.MultiPartRegistry");
        Class<?> bufferType = Class.forName("scala.collection.mutable.ListBuffer");
        Object parts = bufferType.getConstructor()
            .newInstance();
        Method append = bufferType.getMethod("$plus$eq", Object.class);
        NBTTagList rows = tag.getTagList("parts", 10);
        java.util.List<Object> nativeParts = new java.util.ArrayList<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound partTag = rows.getCompoundTagAt(i);
            String type = partTag.getString("id");
            // The deprecated createPart(name, true) passes a null MCDataInput to
            // modern factories. Microblocks read their material from that packet.
            // Load the full native NBT first, then create their real client trait.
            Object detachedPart = registry.getMethod("loadPart", String.class, NBTTagCompound.class)
                .invoke(null, type, partTag);
            if (detachedPart == null) throw new IllegalArgumentException("Missing native multipart type " + type);
            detachedPart.getClass()
                .getMethod("load", NBTTagCompound.class)
                .invoke(detachedPart, partTag);
            Object part;
            HologramNativeRenderRegistry.MultipartAdapter adapter = HologramNativeRenderRegistry
                .multipartAdapter(type, detachedPart);
            if (adapter != null) {
                part = adapter.create(type, detachedPart, (NBTTagCompound) partTag.copy());
            } else if (Class.forName("codechicken.microblock.Microblock")
                .isInstance(detachedPart)) {
                    Object microClass = detachedPart.getClass()
                        .getMethod("microClass")
                        .invoke(detachedPart);
                    int material = (Integer) detachedPart.getClass()
                        .getMethod("getMaterial")
                        .invoke(detachedPart);
                    part = microClass.getClass()
                        .getMethod("create", boolean.class, int.class)
                        .invoke(microClass, true, material);
                } else {
                    // These official legacy factories return the same class on both
                    // sides, including its static render methods. Unknown factories
                    // require an explicit adapter; no synthetic packet is supplied.
                    String name = detachedPart.getClass()
                        .getName();
                    boolean officialAeCableBus = type.equals("ae2_cablebus") && Class.forName("appeng.fmp.CableBusPart")
                        .isInstance(detachedPart)
                        && type.equals(
                            detachedPart.getClass()
                                .getMethod("getType")
                                .invoke(detachedPart));
                    if (!name.startsWith("codechicken.multipart.minecraft.") && !officialAeCableBus
                        && !name.equals("appeng.fmp.QuartzTorchPart"))
                        throw new IllegalArgumentException("Native multipart client adapter required for " + type);
                    part = detachedPart;
                }
            if (part == null || !Class.forName("codechicken.multipart.TMultiPart")
                .isInstance(part)) throw new IllegalArgumentException("Invalid native multipart client part " + type);
            part.getClass()
                .getMethod("load", NBTTagCompound.class)
                .invoke(part, partTag);
            append.invoke(parts, part);
            nativeParts.add(part);
        }
        if ((Integer) bufferType.getMethod("size")
            .invoke(parts) == 0) return null;
        Class<?> iterable = Class.forName("scala.collection.Iterable");
        Class<?> generator = Class.forName("codechicken.multipart.MultipartGenerator$");
        TileEntity tile = (TileEntity) generator
            .getMethod("generateCompositeTile", TileEntity.class, iterable, boolean.class)
            .invoke(
                generator.getField("MODULE$")
                    .get(null),
                null,
                parts,
                true);
        tile.xCoord = x;
        tile.yCoord = y;
        tile.zCoord = z;
        tile.readFromNBT(tag);
        Class<?> multipart = Class.forName("codechicken.multipart.TileMultipart");
        // loadParts calls onWorldJoin when a world is present. Load with no world,
        // then initialize only the local occlusion/render caches on this new tile.
        multipart.getMethod("loadParts", iterable)
            .invoke(tile, parts);
        tile.setWorldObj(world);
        multipart.getMethod("internalPartChange", Class.forName("codechicken.multipart.TMultiPart"))
            .invoke(tile, new Object[] { null });
        for (int i = 0; i < nativeParts.size(); i++) restoreAeVisual(nativeParts.get(i), rows.getCompoundTagAt(i));
        return tile;
    }
}
