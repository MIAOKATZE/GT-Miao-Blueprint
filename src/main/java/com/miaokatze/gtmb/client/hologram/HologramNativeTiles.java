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
            || name.equals("gcewing.architecture.common.block.BlockSawbench")
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
        boolean carpenters = block.getClass()
            .getName()
            .startsWith("com.carpentersblocks.block.");
        boolean littleTiles = block.getClass()
            .getName()
            .equals("com.creativemd.littletiles.common.blocks.BlockTile");
        if (littleTiles && adapter == null) checkLittleTiles(tag);
        if (adapter == null && block.getClass()
            .getName()
            .equals("gcewing.architecture.common.block.BlockSawbench")) {
            // Its world model uses block metadata and fixed wood/metal skins.
            // Inventory restoration would run onInventoryChanged callbacks and
            // is unrelated to the static model; retain it only in the snapshot.
            tag.removeTag("inventory");
        }
        // EnderIO's client NBT reader scans Minecraft.theWorld.playerEntities
        // when worldObj is remote. Its static renderer needs only the decoded
        // local conduit state; leave the new tile detached during that reader.
        // TEBase.readFromNBT calls updateWorldAndLighting. Keep Carpenter tiles
        // detached until decoding completes: the bounded access cannot publish a
        // half-decoded tile during that lighting query. Its renderer still gets
        // the isolated world afterwards, including neighbors and light covers.
        tile.setWorldObj(conduitBundle || carpenters ? null : world);
        tile.xCoord = x;
        tile.yCoord = y;
        tile.zCoord = z;
        if (adapter == null) tile.readFromNBT(tag);
        else adapter.loadTile(tile, tag);
        if (conduitBundle || carpenters) tile.setWorldObj(world);
        if (littleTiles && adapter == null) {
            java.util.List<?> loaded = (java.util.List<?>) tile.getClass()
                .getMethod("getTiles")
                .invoke(tile);
            if (loaded.size() != tag.getInteger("tilesCount"))
                throw new IllegalArgumentException("LittleTiles native reader discarded a visual child");
        }
        return tile;
    }

    /** Static block skins only; nested TileEntity readers/TESRs need an audited adapter. */
    private static void checkLittleTiles(NBTTagCompound tag) {
        int count = tag.getInteger("tilesCount");
        if (count <= 0) throw new IllegalArgumentException("LittleTiles snapshot has no visual children");
        for (int i = 0; i < count; i++) {
            NBTTagCompound child = tag.getCompoundTag("t" + i);
            String type = child.getString("tID");
            boolean legacyBlock = child.hasKey("tileID") && child.hasKey("block", 8);
            if (!legacyBlock && !type.equals("BlockTileBlock") && !type.equals("BlockTileColored"))
                throw new IllegalArgumentException("LittleTiles static preview adapter required for " + type);
            if (child.hasKey("tileEntity") || child.hasKey("tileentity"))
                throw new IllegalArgumentException("LittleTiles nested tile preview adapter required");
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
        java.util.List<HologramNativeRenderRegistry.MultipartAdapter> partAdapters = new java.util.ArrayList<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound partTag = rows.getCompoundTagAt(i);
            String type = partTag.getString("id");
            // The deprecated createPart(name, true) passes a null MCDataInput to
            // modern factories. Microblocks read their material from that packet.
            // Load the full native NBT first, then create their real client trait.
            boolean aeCableBus = type.equals("ae2_cablebus");
            Object detachedPart = aeCableBus ? Class.forName("appeng.fmp.CableBusPart")
                .getConstructor()
                .newInstance()
                : registry.getMethod("loadPart", String.class, NBTTagCompound.class)
                    .invoke(null, type, partTag);
            if (detachedPart == null) throw new IllegalArgumentException("Missing native multipart type " + type);
            if (aeCableBus) HologramAeNativeRender.loadCable(detachedPart, partTag);
            else detachedPart.getClass()
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
            if (!aeCableBus) part.getClass()
                .getMethod("load", NBTTagCompound.class)
                .invoke(part, partTag);
            append.invoke(parts, part);
            nativeParts.add(part);
            partAdapters.add(adapter);
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
        // loadParts calls onWorldJoin when a world is present. Bind with no world.
        multipart.getMethod("loadParts", iterable)
            .invoke(tile, parts);
        tile.setWorldObj(world);
        // A blanket internalPartChange would dispatch every part's onPartChanged,
        // including AE updateConnections/getGridNode. Only microblocks require
        // the reviewed local bounds/mask cache; vanilla static parts need no event.
        Class<?> microblock = Class.forName("codechicken.microblock.Microblock");
        for (int i = 0; i < nativeParts.size(); i++) {
            Object part = nativeParts.get(i);
            HologramNativeRenderRegistry.MultipartAdapter adapter = partAdapters.get(i);
            if (adapter != null) adapter.prepare(part);
            else if (microblock.isInstance(part)) part.getClass()
                .getMethod("recalcBounds")
                .invoke(part);
        }
        return tile;
    }
}
