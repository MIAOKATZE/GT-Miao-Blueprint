package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Audited native persistence and material callbacks on the main thread. No harvesting, ticking or extraction. */
public final class HologramNativePlacement {

    public interface Adapter {

        boolean supports(Block block);

        /** Must return a new tile, preserve every selected component, and never touch a real world. */
        TileEntity create(Block block, int metadata, NBTTagCompound tag, World detachedWorld) throws Exception;

        /** Complete physical part/cover costs, including exact material tags; never remove live contents. */
        List<ItemStack> materials(Block block, int metadata, TileEntity detachedTile) throws Exception;

        /** True only when materials also pays every physical inventory item in the selected payload. */
        default boolean includesInventoryDrops(Block block) {
            return false;
        }
    }

    private static final Map<String, Adapter> ADAPTERS = new LinkedHashMap<>();

    static {
        register("gtmb:native", new Builtin());
    }

    private HologramNativePlacement() {}

    /** Registration is explicit: unknown persistence formats never acquire support by field guessing. */
    public static synchronized void register(String id, Adapter adapter) {
        if (id == null || id.isEmpty() || adapter == null || ADAPTERS.containsKey(id))
            throw new IllegalArgumentException("native placement adapter");
        ADAPTERS.put(id, adapter);
    }

    public static synchronized Adapter adapter(Block block) {
        if (block == null) return null;
        List<Adapter> snapshot = new ArrayList<>(ADAPTERS.values());
        for (int i = snapshot.size() - 1; i >= 0; i--) if (snapshot.get(i)
            .supports(block)) return snapshot.get(i);
        return null;
    }

    public static boolean supported(Block block) {
        return adapter(block) != null;
    }

    public static ItemStack sample(Block block, int metadata, TileEntity tile) throws IOException {
        if (tile == null || !supported(block)) return null;
        NBTTagCompound tag = HologramNativeCapture.capture(tile);
        List<ItemStack> costs = materials(block, metadata, tag);
        ItemStack first = costs.get(0)
            .copy();
        first.stackSize = 1;
        return first;
    }

    public static List<ItemStack> materials(Block block, int metadata, NBTTagCompound payload) throws IOException {
        Adapter adapter = adapter(block);
        if (adapter == null) return new ArrayList<>();
        if (payload == null || payload.hasNoTags()) throw unsupported("native tile payload was not retained");
        try {
            NBTTagCompound tag = HologramBlueprintNbt.nativePayload(payload);
            HologramAeNativeResources.validateSelectedFinalPayload(tag);
            HologramResourceWorld world = HologramResourceWorld.create(block, metadata);
            tag.setInteger("x", 0);
            tag.setInteger("y", 64);
            tag.setInteger("z", 0);
            TileEntity tile = adapter.create(block, metadata, tag, world);
            if (tile == null) throw unsupported("native tile factory rejected payload");
            world.tile = tile;
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack stack : adapter.materials(block, metadata, tile)) {
                if (stack == null || stack.getItem() == null || stack.stackSize <= 0)
                    throw unsupported("native component has no payable material");
                result.add(stack.copy());
            }
            if (result.isEmpty()) throw unsupported("native component has no payable material");
            return result;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            throw new IOException(
                "Unsupported native blueprint materials: " + block.getClass()
                    .getName(),
                failure);
        }
    }

    /** Main sample is charged by the normal transaction; every remaining native component is extra. */
    public static List<ItemStack> additionalMaterials(Block block, int metadata, NBTTagCompound payload, ItemStack main)
        throws IOException {
        List<ItemStack> costs = materials(block, metadata, payload);
        if (costs.isEmpty()) return costs;
        for (int i = 0; i < costs.size(); i++) {
            ItemStack cost = costs.get(i);
            if (main != null && cost.isItemEqual(main) && ItemStack.areItemStackTagsEqual(cost, main)) {
                if (--cost.stackSize == 0) costs.remove(i);
                return costs;
            }
        }
        throw unsupported("main material does not match native components");
    }

    public static boolean matchesSample(Block block, int metadata, NBTTagCompound payload, ItemStack sample) {
        try {
            for (ItemStack cost : materials(block, metadata, payload))
                if (sample != null && cost.isItemEqual(sample) && ItemStack.areItemStackTagsEqual(cost, sample))
                    return true;
        } catch (IOException failure) {
            return false;
        }
        return false;
    }

    public static boolean matchesPlaced(Block block, int metadata, TileEntity actual, NBTTagCompound payload) {
        if (actual == null || payload == null) return false;
        try {
            HologramResourceWorld detached = HologramResourceWorld.create(block, metadata);
            TileEntity expected = adapter(block)
                .create(block, metadata, HologramBlueprintNbt.nativePayload(payload), detached);
            if (expected == null || !actual.getClass()
                .equals(expected.getClass())) return false;
            NBTTagCompound actualTag = HologramNativeCapture.capture(actual), expectedTag = new NBTTagCompound();
            expected.writeToNBT(expectedTag);
            for (String key : new String[] { "x", "y", "z" }) {
                actualTag.removeTag(key);
                expectedTag.removeTag(key);
            }
            return actualTag.equals(expectedTag);
        } catch (Exception | LinkageError invalid) {
            return false;
        }
    }

    /** Payment and Forge event rollback remain owned by HologramService. */
    public static TileEntity install(World world, int x, int y, int z, Block block, int metadata,
        NBTTagCompound payload) throws IOException {
        return install(world, x, y, z, block, metadata, payload, null);
    }

    public static TileEntity install(World world, int x, int y, int z, Block block, int metadata,
        NBTTagCompound payload, net.minecraft.entity.player.EntityPlayerMP actor) throws IOException {
        Adapter adapter = adapter(block);
        if (adapter == null) throw unsupported("unregistered native placement provider");
        try {
            NBTTagCompound tag = HologramBlueprintNbt.nativePayload(payload);
            if (tag == null) throw unsupported("native tile payload was not retained");
            tag.setInteger("x", x);
            tag.setInteger("y", y);
            tag.setInteger("z", z);
            // Deserialize with a read-only world before joining any real network or native lifecycle.
            HologramResourceWorld detached = HologramResourceWorld.create(block, metadata);
            TileEntity tile = adapter.create(block, metadata, tag, detached);
            if (tile == null || tile.getWorldObj() != null && tile.getWorldObj() != detached)
                throw unsupported("native factory did not create a detached tile");
            if (actor != null) {
                int playerId = appeng.core.worlddata.WorldData.instance()
                    .playerData()
                    .getPlayerID(actor.getGameProfile());
                boolean changed = false;
                if (block.getClass()
                    .getName()
                    .equals("appeng.block.networking.BlockCableBus")) changed = rewriteCableOwners(tile, tag, playerId);
                if (block.getClass()
                    .getName()
                    .equals("codechicken.multipart.BlockMultipart")) {
                    List<?> parts = (List<?>) call(tile, "jPartList", new Class<?>[0]);
                    for (int i = 0; i < parts.size(); i++) {
                        Object part = parts.get(i);
                        NBTTagCompound row = tag.getTagList("parts", 10)
                            .getCompoundTagAt(i);
                        if (row.getString("id")
                            .equals("ae2_cablebus"))
                            changed |= rewriteCableOwners(call(part, "getCableBus", new Class<?>[0]), row, playerId);
                    }
                }
                if (changed) tile = adapter.create(block, metadata, tag, detached);
            }
            if (actor != null && block.getClass()
                .getName()
                .startsWith("com.carpentersblocks.block.")) {
                Class<?> owner = Class.forName("com.carpentersblocks.util.protection.ProtectedObject");
                call(
                    tile,
                    "setOwner",
                    new Class<?>[] { owner },
                    owner.getConstructor(net.minecraft.entity.player.EntityPlayer.class)
                        .newInstance(actor));
            }
            world.removeTileEntity(x, y, z);
            tile.setWorldObj(world);
            tile.xCoord = x;
            tile.yCoord = y;
            tile.zCoord = z;
            world.setTileEntity(x, y, z, tile);
            finishJoin(world, block, tile);
            tile.markDirty();
            world.markBlockForUpdate(x, y, z);
            if (block.getClass()
                .getName()
                .equals("codechicken.multipart.BlockMultipart"))
                Class.forName("codechicken.multipart.MultipartHelper")
                    .getMethod("sendDescPacket", World.class, TileEntity.class)
                    .invoke(null, world, tile);
            return tile;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            throw new IOException("Native blueprint placement rejected", failure);
        }
    }

    static TileEntity rollbackTile(World world, Block block, int metadata, NBTTagCompound tag) {
        if (!supported(block)) return TileEntity.createAndLoadEntity(tag);
        try {
            return adapter(block).create(block, metadata, tag, HologramResourceWorld.create(block, metadata));
        } catch (Exception | LinkageError failure) {
            throw new IllegalStateException("Native recovery tile failed", failure);
        }
    }

    private static boolean rewriteCableOwners(Object host, NBTTagCompound rootTag, int playerId) throws Exception {
        boolean changed = false;
        for (int side = 0; side < 7; side++) {
            Object part = call(
                host,
                "getPart",
                new Class<?>[] { net.minecraftforge.common.util.ForgeDirection.class },
                net.minecraftforge.common.util.ForgeDirection.getOrientation(side));
            if (!(part instanceof appeng.me.helpers.IGridProxyable)) continue;
            appeng.me.helpers.AENetworkProxy proxy = ((appeng.me.helpers.IGridProxyable) part).getProxy();
            NBTTagCompound extra = rootTag.getCompoundTag("extra:" + side);
            String name = HologramBlueprintOwnership.nodeKey(proxy);
            NBTTagCompound node = extra.getCompoundTag(name);
            node.setInteger("p", playerId);
            extra.setTag(name, node);
            rootTag.setTag("extra:" + side, extra);
            changed = true;
        }
        return changed;
    }

    /** Chunk insertion only validates a tile; native multipart parts must also join their real world once. */
    static void finishJoin(World world, Block block, TileEntity tile) {
        if (world.isRemote || !block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) return;
        try {
            call(tile, "onChunkLoad", new Class<?>[0]);
        } catch (Exception | LinkageError failure) {
            throw new IllegalStateException("Native multipart world join rejected", failure);
        }
    }

    private static IOException unsupported(String detail) {
        return new IOException("Unsupported native blueprint: " + detail);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method method = target.getClass()
            .getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static final class Builtin implements Adapter {

        public boolean includesInventoryDrops(Block block) {
            return block.getClass()
                .getName()
                .equals("appeng.block.networking.BlockCableBus");
        }

        public boolean supports(Block block) {
            String type = block.getClass()
                .getName();
            return type.equals("codechicken.multipart.BlockMultipart") || type.startsWith("com.carpentersblocks.block.")
                || type.equals("com.creativemd.littletiles.common.blocks.BlockTile")
                || type.equals("gcewing.architecture.common.block.BlockShape")
                || type.equals("crazypants.enderio.conduit.BlockConduitBundle")
                || type.equals("appeng.block.networking.BlockCableBus");
        }

        public TileEntity create(Block block, int metadata, NBTTagCompound tag, World world) throws Exception {
            TileEntity tile;
            String type = block.getClass()
                .getName();
            if (type.equals("codechicken.multipart.BlockMultipart")) {
                for (int i = 0; i < tag.getTagList("parts", 10)
                    .tagCount(); i++) {
                    NBTTagCompound part = tag.getTagList("parts", 10)
                        .getCompoundTagAt(i);
                    if (part.getString("id")
                        .startsWith("mcr_")
                        && Class.forName("codechicken.microblock.MicroMaterialRegistry")
                            .getMethod("getMaterial", String.class)
                            .invoke(null, part.getString("material")) == null)
                        throw unsupported("unregistered multipart material");
                }
                Object loading = Class.forName("codechicken.multipart.handler.MultipartSaveLoad$")
                    .getField("MODULE$")
                    .get(null);
                Object previousWorld = call(loading, "loadingWorld", new Class<?>[0]);
                try {
                    tile = (TileEntity) Class.forName("codechicken.multipart.MultipartHelper")
                        .getMethod("createTileFromNBT", World.class, NBTTagCompound.class)
                        .invoke(null, null, tag.copy());
                } finally {
                    call(loading, "loadingWorld_$eq", new Class<?>[] { World.class }, previousWorld);
                }
                if (tile == null) throw unsupported("multipart factory rejected all parts");
                Object parts = call(tile, "partList", new Class<?>[0]);
                if ((Integer) call(parts, "size", new Class<?>[0]) != tag.getTagList("parts", 10)
                    .tagCount()) throw unsupported("unregistered multipart component");
            } else {
                if (type.equals("gcewing.architecture.common.block.BlockShape")) {
                    Object shape = Class.forName("gcewing.architecture.common.shape.Shape")
                        .getMethod("forId", int.class)
                        .invoke(null, tag.getInteger("Shape"));
                    if (!tag.hasKey("Shape", 99) || shape.getClass()
                        .getField("id")
                        .getInt(shape) != tag.getInteger("Shape"))
                        throw unsupported("unregistered ArchitectureCraft shape");
                    for (String material : new String[] { "BaseName", "Name2" }) {
                        if (material.equals("Name2") && !tag.hasKey(material)) continue;
                        Block nativeBlock = Block.getBlockFromName(tag.getString(material));
                        if (nativeBlock == null || nativeBlock == net.minecraft.init.Blocks.air)
                            throw unsupported("unregistered ArchitectureCraft material");
                    }
                }
                tile = block.createTileEntity(world, metadata);
                if (tile == null) throw unsupported("missing native tile factory");
                tile.setWorldObj(world);
                tile.xCoord = tag.getInteger("x");
                tile.yCoord = tag.getInteger("y");
                tile.zCoord = tag.getInteger("z");
                if (world instanceof HologramResourceWorld) ((HologramResourceWorld) world).tile = tile;
                tile.readFromNBT((NBTTagCompound) tag.copy());
                if (type.equals("com.creativemd.littletiles.common.blocks.BlockTile")
                    && ((List<?>) call(tile, "getTiles", new Class<?>[0])).size() != tag.getInteger("tilesCount"))
                    throw unsupported("unregistered LittleTiles component");
                if (type.startsWith("com.carpentersblocks.block.")) {
                    for (int i = 0; i < tag.getTagList("cbAttrList", 10)
                        .tagCount(); i++) {
                        byte attr = tag.getTagList("cbAttrList", 10)
                            .getCompoundTagAt(i)
                            .getByte("cbAttribute");
                        if (!(Boolean) call(tile, "hasAttribute", new Class<?>[] { byte.class }, attr))
                            throw unsupported("unregistered Carpenter material");
                    }
                }
                if (type.equals("crazypants.enderio.conduit.BlockConduitBundle")
                    && ((java.util.Collection<?>) call(tile, "getConduits", new Class<?>[0])).size()
                        != tag.getTagList("conduits", 10)
                            .tagCount())
                    throw unsupported("unregistered EnderIO conduit");
                if (type.equals("crazypants.enderio.conduit.BlockConduitBundle")) {
                    String facade = tag.getString("facadeId");
                    if (!facade.isEmpty() && !facade.equals("null")
                        && !(Boolean) call(tile, "hasFacade", new Class<?>[0]))
                        throw unsupported("unregistered EnderIO facade material");
                }
                if (type.equals("appeng.block.networking.BlockCableBus")) for (int side = 0; side < 7; side++) {
                    if (tag.hasKey("def:" + side, 10)) {
                        Object part = call(
                            tile,
                            "getPart",
                            new Class<?>[] { net.minecraftforge.common.util.ForgeDirection.class },
                            net.minecraftforge.common.util.ForgeDirection.getOrientation(side));
                        if (part == null) throw unsupported("unregistered AE2 part");
                    }
                    if (side < 6 && tag.hasKey("facade:" + side, 10)) {
                        Object facade = call(
                            call(tile, "getFacadeContainer", new Class<?>[0]),
                            "getFacade",
                            new Class<?>[] { net.minecraftforge.common.util.ForgeDirection.class },
                            net.minecraftforge.common.util.ForgeDirection.getOrientation(side));
                        if (facade == null) throw unsupported("unregistered AE2 facade");
                    }
                }
            }
            tile.setWorldObj(world);
            tile.xCoord = tag.getInteger("x");
            tile.yCoord = tag.getInteger("y");
            tile.zCoord = tag.getInteger("z");
            return tile;
        }

        @SuppressWarnings("unchecked")
        public List<ItemStack> materials(Block block, int metadata, TileEntity tile) throws Exception {
            List<ItemStack> costs = new ArrayList<>();
            String type = block.getClass()
                .getName();
            if (type.equals("codechicken.multipart.BlockMultipart")) {
                Object iterator = call(call(tile, "partList", new Class<?>[0]), "iterator", new Class<?>[0]);
                while ((Boolean) call(iterator, "hasNext", new Class<?>[0])) {
                    Object part = call(iterator, "next", new Class<?>[0]);
                    Object drops = call(call(part, "getDrops", new Class<?>[0]), "iterator", new Class<?>[0]);
                    int count = costs.size();
                    while ((Boolean) call(drops, "hasNext", new Class<?>[0]))
                        costs.add((ItemStack) call(drops, "next", new Class<?>[0]));
                    if (costs.size() == count) throw unsupported("multipart part has no material");
                }
            } else if (type.startsWith("com.carpentersblocks.block.")) {
                Item base = Item.getItemFromBlock(block);
                if (base == null) throw unsupported("Carpenter block item is missing");
                costs.add(new ItemStack(base, 1, block.damageDropped(metadata)));
                for (byte attr = 0; attr < 26; attr++)
                    if ((Boolean) call(tile, "hasAttribute", new Class<?>[] { byte.class }, attr)) {
                        ItemStack cost = (ItemStack) call(
                            tile,
                            "getAttributeForDrop",
                            new Class<?>[] { byte.class },
                            attr);
                        costs.add(cost);
                    }
            } else if (type.equals("appeng.block.networking.BlockCableBus")) {
                Object container = call(tile, "getCableBus", new Class<?>[0]);
                call(container, "getDrops", new Class<?>[] { List.class }, costs);
            } else if (type.equals("crazypants.enderio.conduit.BlockConduitBundle")) {
                for (Object conduit : (java.util.Collection<?>) call(tile, "getConduits", new Class<?>[0])) {
                    List<ItemStack> drops = (List<ItemStack>) call(conduit, "getDrops", new Class<?>[0]);
                    if (drops.isEmpty()) throw unsupported("conduit has no material");
                    costs.addAll(drops);
                }
                if ((Boolean) call(tile, "hasFacade", new Class<?>[0])) {
                    Item item = (Item) Class.forName("crazypants.enderio.EnderIO")
                        .getField("itemConduitFacade")
                        .get(null);
                    Enum<?> facade = (Enum<?>) call(tile, "getFacadeType", new Class<?>[0]);
                    ItemStack stack = new ItemStack(item, 1, facade.ordinal());
                    Class.forName("crazypants.enderio.machine.painter.PainterUtil")
                        .getMethod("setSourceBlock", ItemStack.class, Block.class, int.class)
                        .invoke(
                            null,
                            stack,
                            call(tile, "getFacadeId", new Class<?>[0]),
                            call(tile, "getFacadeMetadata", new Class<?>[0]));
                    costs.add(stack);
                }
                // Legacy FMP covers have independent persistence/materials; no free unpriced restoration.
                NBTTagCompound tag = new NBTTagCompound();
                tile.writeToNBT(tag);
                if (tag.hasKey("microblocks"))
                    throw unsupported("legacy conduit microblock covers require a registered provider");
            } else if (type.equals("com.creativemd.littletiles.common.blocks.BlockTile")) {
                for (Object little : (List<?>) call(tile, "getTiles", new Class<?>[0])) {
                    List<ItemStack> drops = (List<ItemStack>) call(little, "getDrops", new Class<?>[0]);
                    if (drops.isEmpty()) throw unsupported("LittleTiles child has no material");
                    costs.addAll(drops);
                }
            } else {
                costs.addAll(block.getDrops(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord, metadata, 0));
            }
            return costs;
        }
    }
}
