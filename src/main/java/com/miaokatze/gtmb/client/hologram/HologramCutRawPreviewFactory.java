package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Full native readers for a private raw cut cache; no blueprint schema, family admission, or lifecycle dispatch. */
final class HologramCutRawPreviewFactory {

    private HologramCutRawPreviewFactory() {}

    static TileEntity create(HologramCutRawPreviewWorld world, HologramCutRawPreviewWorld.Cell cell)
        throws ReflectiveOperationException {
        NBTTagCompound tag = cell.raw.hasKey("tile", 10) ? (NBTTagCompound) cell.raw.getCompoundTag("tile")
            .copy() : new NBTTagCompound();
        tag.setInteger("x", cell.x);
        tag.setInteger("y", cell.y);
        tag.setInteger("z", cell.z);
        if ("savedMultipart".equals(tag.getString("id"))) {
            recordInput(cell, tag, "multipart-native-nbt-desc");
            TileEntity tile = multipart(world, tag, cell);
            cell.nativeClass = tile.getClass()
                .getName();
            cell.nativeReaderCompleted = true;
            return tile;
        }
        TileEntity tile = cell.block.createTileEntity(world, cell.meta);
        if (tile == null && tag.hasKey("id", 8)) {
            java.util.Map<?, ?> types = (java.util.Map<?, ?>) cpw.mods.fml.relauncher.ReflectionHelper
                .findField(TileEntity.class, "nameToClassMap", "field_145855_i")
                .get(null);
            Class<?> type = (Class<?>) types.get(tag.getString("id"));
            if (type != null) tile = (TileEntity) type.getConstructor()
                .newInstance();
        }
        if (tile == null) return null;
        if (tile.getWorldObj() != null && tile.getWorldObj() != world)
            throw new IllegalStateException("Native factory reused a tile from another world");
        tile.setWorldObj(world);
        cell.tile = tile;
        cell.nativeClass = tile.getClass()
            .getName();
        tile.xCoord = cell.x;
        tile.yCoord = cell.y;
        tile.zCoord = cell.z;
        // No validate, onReady, onWorldJoin, grid connection, updateEntity, or description packet dispatch.
        if (cell.raw.hasKey("tile", 10)) {
            recordInput(cell, tag, "tile-readFromNBT");
            cell.nativeReaderCalls++;
            tile.readFromNBT(tag);
            cell.nativeReaderCompleted = true;
        }
        tile.xCoord = cell.x;
        tile.yCoord = cell.y;
        tile.zCoord = cell.z;
        return tile;
    }

    /** Native NBT factories first, then the exact native description codec produces every registered client trait. */
    private static TileEntity multipart(World world, NBTTagCompound tag, HologramCutRawPreviewWorld.Cell cell)
        throws ReflectiveOperationException {
        Class<?> registry = Class.forName("codechicken.multipart.MultiPartRegistry");
        Class<?> partType = Class.forName("codechicken.multipart.TMultiPart");
        Class<?> input = Class.forName("codechicken.lib.data.MCDataInput");
        Class<?> output = Class.forName("codechicken.lib.data.MCDataOutput");
        Class<?> bufferType = Class.forName("scala.collection.mutable.ListBuffer");
        Class<?> iterable = Class.forName("scala.collection.Iterable");
        Class<?> generatorType = Class.forName("codechicken.multipart.MultipartGenerator$");
        Object generator = generatorType.getField("MODULE$")
            .get(null);
        Method generate = generatorType.getMethod("generateCompositeTile", TileEntity.class, iterable, boolean.class);
        Method append = bufferType.getMethod("$plus$eq", Object.class);
        Method loadParts = Class.forName("codechicken.multipart.TileMultipart")
            .getMethod("loadParts", iterable);
        Object savedParts = bufferType.getConstructor()
            .newInstance();
        NBTTagList rows = tag.getTagList("parts", 10);
        List<Object> saved = new ArrayList<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound raw = (NBTTagCompound) rows.getCompoundTagAt(i)
                .copy();
            String id = raw.getString("id");
            Object part = registry.getMethod("loadPart", String.class, NBTTagCompound.class)
                .invoke(null, id, raw);
            if (part == null) throw new IllegalStateException("Missing native multipart factory: " + id);
            if (cell.factoryPayload != null) {
                if (cell.partReaderInputs == null) cell.partReaderInputs = new NBTTagList();
                cell.partReaderInputs.appendTag(raw.copy());
            }
            cell.nativeReaderCalls++;
            partType.getMethod("load", NBTTagCompound.class)
                .invoke(part, raw);
            append.invoke(savedParts, part);
            saved.add(part);
        }
        if (saved.isEmpty()) throw new IllegalStateException("Raw multipart has no parts");
        TileEntity savedTile = (TileEntity) generate.invoke(generator, null, savedParts, false);
        cell.nativeReaderCalls++;
        savedTile.readFromNBT((NBTTagCompound) tag.copy());
        // loadParts invokes join/neighbor hooks if world is present: bind first with no world.
        loadParts.invoke(savedTile, savedParts);
        savedTile.setWorldObj(world);
        Object clientParts = bufferType.getConstructor()
            .newInstance();
        try {
            for (Object part : saved) {
                Object clientPart = describePart(registry, partType, input, output, part);
                append.invoke(clientParts, clientPart);
            }
            TileEntity tile = (TileEntity) generate.invoke(generator, null, clientParts, true);
            tile.readFromNBT((NBTTagCompound) tag.copy());
            loadParts.invoke(tile, clientParts);
            tile.setWorldObj(world);
            // Bounds are a local visual cache, not an onPartChanged/onWorldJoin lifecycle event.
            Class<?> microblock = Class.forName("codechicken.microblock.Microblock");
            for (Object part : (List<?>) tile.getClass()
                .getMethod("jPartList")
                .invoke(tile)) if (microblock.isInstance(part))
                    part.getClass()
                        .getMethod("recalcBounds")
                        .invoke(part);
            return tile;
        } finally {
            savedTile.setWorldObj(null);
        }
    }

    private static void recordInput(HologramCutRawPreviewWorld.Cell cell, NBTTagCompound payload, String kind) {
        cell.factoryPayloadKind = kind;
        // Explicit native diagnostic opt-in only: ordinary 262k-cell previews keep no extra full payload receipt.
        if (Boolean.getBoolean("gtmb.hologram.cutNativeClient")) cell.factoryPayload = (NBTTagCompound) payload.copy();
    }

    private static Object describePart(Class<?> registry, Class<?> partType, Class<?> input, Class<?> output,
        Object part) throws ReflectiveOperationException {
        // Local byte streams only; no PacketCustom send path, channel, client connection, or synthetic world event.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bytes.write(1); // PacketCustom's local reader consumes a non-compressed type byte, not a network channel.
        Object writer = Class.forName("codechicken.lib.data.MCDataOutputWrapper")
            .getConstructor(java.io.DataOutput.class)
            .newInstance(new java.io.DataOutputStream(bytes));
        registry.getMethod("writePartID", output, partType)
            .invoke(null, writer, part);
        partType.getMethod("writeDesc", output)
            .invoke(part, writer);
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.wrappedBuffer(bytes.toByteArray());
        try {
            Object reader = Class.forName("codechicken.lib.packet.PacketCustom")
                .getConstructor(io.netty.buffer.ByteBuf.class)
                .newInstance(buffer);
            Object client = registry.getMethod("readPart", input)
                .invoke(null, reader);
            if (client == null) throw new IllegalStateException("Native multipart client factory returned null");
            partType.getMethod("readDesc", input)
                .invoke(client, reader);
            if (buffer.isReadable()) throw new IllegalStateException("Native multipart description left unread data");
            return client;
        } finally {
            buffer.release();
        }
    }
}
