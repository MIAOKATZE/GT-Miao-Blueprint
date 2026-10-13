package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** Real world decor sources for the opt-in production capture/save/network preview probe. */
public final class HologramDecorSourceSmoke {

    private HologramDecorSourceSmoke() {}

    private static ForgeChunkManager.Ticket sourceTicket;
    private static WorldServer sourceWorld;
    private static final java.util.Set<ChunkCoordIntPair> sourceChunks = new java.util.LinkedHashSet<>();
    private static final NBTTagCompound sourceChunkReceipt = new NBTTagCompound();

    /** Own native ticket protects original tile identities while the separate network probe moves the player away. */
    public static void acquireNativeSourceChunks(WorldServer world) throws ReflectiveOperationException {
        require(
            Boolean.getBoolean("gtmb.hologram.productionPreviewSmoke")
                && Boolean.getBoolean("gtmb.hologram.networkInteractionSmoke"),
            "source keeper requires the actual network profile");
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-production-"),
            "isolated source keeper world");
        require(sourceTicket == null && sourceWorld == null, "source keeper may only acquire once");
        // Reflection keeps the old normal baseline independent of the new energy helper.
        Class.forName("com.miaokatze.gtmb.hologram.HologramEnergyNetworkSmoke")
            .getMethod("ensureFixtureChunkCallback")
            .invoke(null);
        sourceWorld = world;
        sourceTicket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        require(sourceTicket != null, "source keeper native ticket acquired");
        sourceTicket.getModData()
            .setBoolean("gtmbPreviewSourceProbe", true);
        for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) {
            ChunkCoordIntPair coordinate = new ChunkCoordIntPair(x, z);
            Chunk chunk = world.theChunkProviderServer.loadChunk(x, z);
            require(
                chunk != null && !(chunk instanceof EmptyChunk) && chunk.isChunkLoaded,
                "source keeper initial native chunk loaded");
            sourceChunks.add(coordinate);
            ForgeChunkManager.forceChunk(sourceTicket, coordinate);
        }
        sourceChunkReceipt.setString("ticketMarker", "gtmbPreviewSourceProbe");
        sourceChunkReceipt.setString(
            "world",
            world.getWorldInfo()
                .getWorldName());
        sourceChunkReceipt.setInteger("chunkCount", sourceChunks.size());
        sourceChunkReceipt.setLong("acquiredWorldTick", world.getTotalWorldTime());
        retainNativeSourceChunks();
    }

    public static void retainNativeSourceChunks() {
        require(sourceTicket != null, "source keeper ticket still owned");
        NBTTagList chunks = new NBTTagList();
        for (ChunkCoordIntPair coordinate : sourceChunks) {
            require(
                sourceWorld.theChunkProviderServer.chunkExists(coordinate.chunkXPos, coordinate.chunkZPos),
                "source keeper chunk remained loaded without reload");
            require(
                sourceWorld.getPersistentChunks()
                    .containsEntry(coordinate, sourceTicket),
                "source keeper own native ticket retained");
            Chunk chunk = sourceWorld.theChunkProviderServer.loadChunk(coordinate.chunkXPos, coordinate.chunkZPos);
            require(
                chunk != null && !(chunk instanceof EmptyChunk) && chunk.isChunkLoaded,
                "source keeper actual native chunk remains loaded");
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", coordinate.chunkXPos);
            row.setInteger("z", coordinate.chunkZPos);
            row.setBoolean("nativeLoaded", true);
            row.setBoolean("persistentOwnTicket", true);
            chunks.appendTag(row);
        }
        sourceChunkReceipt.setTag("chunks", chunks);
        sourceChunkReceipt.setInteger("retentionChecks", sourceChunkReceipt.getInteger("retentionChecks") + 1);
        sourceChunkReceipt.setLong("lastRetainedWorldTick", sourceWorld.getTotalWorldTime());
    }

    public static void recordStrictSourceProof(boolean afterNetwork) {
        sourceChunkReceipt.setBoolean(afterNetwork ? "strictProofAfterNetwork" : "strictProofBeforeNetwork", true);
    }

    public static NBTTagCompound sourceChunkEvidence() {
        return (NBTTagCompound) sourceChunkReceipt.copy();
    }

    public static void releaseNativeSourceChunks(boolean afterStrictProof) {
        if (sourceTicket == null) return;
        ForgeChunkManager.releaseTicket(sourceTicket);
        boolean removed = true;
        for (ChunkCoordIntPair coordinate : sourceChunks) removed &= !sourceWorld.getPersistentChunks()
            .containsEntry(coordinate, sourceTicket);
        sourceTicket = null;
        sourceChunkReceipt.setBoolean("ticketReleased", true);
        sourceChunkReceipt.setBoolean("ownTicketAbsentAfterRelease", removed);
        sourceChunkReceipt.setBoolean("releasedAfterStrictSourceProof", afterStrictProof);
        sourceChunkReceipt.setLong("releasedWorldTick", sourceWorld.getTotalWorldTime());
    }

    /** Adjacent native models and opaque casing neighbors for the separate v009 profile. */
    public static AdjacentSource createAdjacent(WorldServer world) throws ReflectiveOperationException {
        require(Boolean.getBoolean("gtmb.hologram.productionAdjacentPreview"), "adjacent profile opt-in required");
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-production-"),
            "isolated world required");
        java.util.List<int[]> positions = new java.util.ArrayList<>();
        Block casing = gregtech.api.GregTechAPI.sBlockCasings1;
        for (int x = 16; x <= 19; x++) put(world, positions, x, 79, 8, casing, 11);
        for (int x = 8; x <= 13; x++) for (int z = 11; z <= 16; z++) put(world, positions, x, 79, z, casing, 11);
        Class<?> carpenter = Class.forName("com.carpentersblocks.tileentity.TEBase");
        Class<?> slopes = Class.forName("com.carpentersblocks.data.Slope");
        byte[] covers = (byte[]) carpenter.getField("ATTR_COVER")
            .get(null);
        String[] directions = { "N", "S", "W", "E" };
        int[][] locations = { { 9, 12, 0, -1 }, { 12, 12, 0, 1 }, { 9, 15, -1, 0 }, { 12, 15, 1, 0 } };
        Block slope = registered("com.carpentersblocks.block.BlockCarpentersSlope");
        for (int i = 0; i < locations.length; i++) {
            int x = locations[i][0], z = locations[i][1];
            put(world, positions, x, 80, z, slope, 0);
            TileEntity tile = world.getTileEntity(x, 80, z);
            carpenter.getMethod("setData", int.class)
                .invoke(
                    tile,
                    (int) slopes.getField("ID_WEDGE_POS_" + directions[i])
                        .getByte(null));
            carpenter.getMethod("addAttribute", byte.class, ItemStack.class)
                .invoke(tile, covers[6], new ItemStack(Blocks.quartz_block));
            carpenter.getMethod("addAttribute", byte.class, ItemStack.class)
                .invoke(tile, covers[i < 2 ? 5 : 3], new ItemStack(Blocks.planks, 1, 3));
            put(world, positions, x + locations[i][2], 80, z + locations[i][3], casing, 11);
        }
        put(world, positions, 16, 79, 12, casing, 11);
        put(world, positions, 16, 80, 12, registered("gcewing.architecture.common.block.BlockSawbench"), 0);
        return new AdjacentSource(world, positions);
    }

    private static void put(WorldServer world, java.util.List<int[]> positions, int x, int y, int z, Block block,
        int metadata) {
        world.getChunkFromBlockCoords(x, z);
        require(world.isAirBlock(x, y, z), "adjacent source requires fresh air");
        require(world.setBlock(x, y, z, block, metadata, 3), "adjacent native world block placed");
        positions.add(new int[] { x, y, z });
    }

    /** Complete independent world inventory, including ordinary opaque neighbors. */
    public static final class AdjacentSource {

        private final WorldServer world;
        private final java.util.List<int[]> positions;
        private final Block[] blocks;
        private final TileEntity[] tiles;
        private final NBTTagCompound[] saved;
        private final int[] metadata;
        private boolean freezeAttempted, settledBaselineFrozen;

        private AdjacentSource(WorldServer world, java.util.List<int[]> positions) {
            this.world = world;
            this.positions = positions;
            blocks = new Block[positions.size()];
            tiles = new TileEntity[positions.size()];
            saved = new NBTTagCompound[positions.size()];
            metadata = new int[positions.size()];
            for (int i = 0; i < positions.size(); i++) {
                int[] p = positions.get(i);
                blocks[i] = world.getBlock(p[0], p[1], p[2]);
                metadata[i] = world.getBlockMetadata(p[0], p[1], p[2]);
                tiles[i] = world.getTileEntity(p[0], p[1], p[2]);
                saved[i] = tiles[i] == null ? null : Source.nativeNbt(tiles[i]);
            }
        }

        public int count() {
            return positions.size();
        }

        public int[] bounds() {
            return new int[] { 8, 79, 8, 19, 80, 16 };
        }

        /** Freeze once after native neighbor/drop events settle, before any capture or preview. */
        public void freezeSettledBaseline() {
            require(!freezeAttempted, "adjacent settled baseline may only be frozen once");
            freezeAttempted = true;
            for (int i = 0; i < positions.size(); i++) assertIdentity(i);
            NBTTagCompound[] settled = new NBTTagCompound[saved.length];
            for (int i = 0; i < saved.length; i++) if (tiles[i] != null) settled[i] = Source.nativeNbt(tiles[i]);
            System.arraycopy(settled, 0, saved, 0, saved.length);
            settledBaselineFrozen = true;
        }

        public NBTTagList checkpoint() {
            require(settledBaselineFrozen, "adjacent checkpoint requires the frozen settled baseline");
            NBTTagList rows = new NBTTagList();
            for (int i = 0; i < positions.size(); i++) {
                NBTTagCompound row = new NBTTagCompound();
                row.setIntArray(
                    "sourcePosition",
                    positions.get(i)
                        .clone());
                row.setString("block", Block.blockRegistry.getNameForObject(blocks[i]));
                row.setInteger("metadata", metadata[i]);
                if (saved[i] != null) row.setTag("nativeTile", saved[i].copy());
                rows.appendTag(row);
            }
            return rows;
        }

        public void assertUnchanged() {
            require(settledBaselineFrozen, "adjacent immutability requires the frozen settled baseline");
            for (int i = 0; i < positions.size(); i++) {
                assertIdentity(i);
                if (saved[i] != null)
                    require(saved[i].equals(Source.nativeNbt(tiles[i])), "adjacent native NBT unchanged");
            }
        }

        private void assertIdentity(int i) {
            int[] p = positions.get(i);
            require(world.getBlock(p[0], p[1], p[2]) == blocks[i], "adjacent source block unchanged");
            require(world.getBlockMetadata(p[0], p[1], p[2]) == metadata[i], "adjacent source metadata unchanged");
            require(world.getTileEntity(p[0], p[1], p[2]) == tiles[i], "adjacent source tile unchanged");
            if (tiles[i] != null) {
                require(tiles[i].getWorldObj() == world, "adjacent source tile world unchanged");
                require(
                    tiles[i].xCoord == p[0] && tiles[i].yCoord == p[1] && tiles[i].zCoord == p[2],
                    "adjacent source tile coordinates unchanged");
            }
        }
    }

    /** Construct native tiles through their public APIs, without supplying persistence or display NBT. */
    public static Source create(WorldServer world, int x, int y, int z) throws ReflectiveOperationException {
        if (!Boolean.getBoolean("gtmb.hologram.productionPreviewSmoke"))
            throw new IllegalStateException("production preview probe must be explicitly enabled");
        if (!world.getWorldInfo()
            .getWorldName()
            .startsWith("hologram-production-")) throw new IllegalStateException("isolated production world guard");
        Block slope = registered("com.carpentersblocks.block.BlockCarpentersSlope");
        Block architecture = registered("gcewing.architecture.common.block.BlockShape");
        for (int i = 0; i < 4; i++) {
            world.getChunkFromBlockCoords(x + 2 * i, z);
            require(world.isAirBlock(x + 2 * i, y, z), "decor source position must be fresh air");
        }
        Class<?> carpenter = Class.forName("com.carpentersblocks.tileentity.TEBase");
        int wedge = Class.forName("com.carpentersblocks.data.Slope")
            .getField("ID_WEDGE_POS_N")
            .getByte(null);
        byte[] covers = (byte[]) carpenter.getField("ATTR_COVER")
            .get(null);
        for (int i = 0; i < 2; i++) {
            int xx = x + 2 * i;
            require(world.setBlock(xx, y, z, slope, 0, 3), "native Carpenter block created");
            TileEntity tile = world.getTileEntity(xx, y, z);
            require(carpenter.isInstance(tile), "native Carpenter tile created");
            carpenter.getMethod("setData", int.class)
                .invoke(tile, wedge);
            if (i == 1) {
                carpenter.getMethod("addAttribute", byte.class, ItemStack.class)
                    .invoke(tile, covers[6], new ItemStack(Blocks.quartz_block, 1, 0));
                carpenter.getMethod("addAttribute", byte.class, ItemStack.class)
                    .invoke(tile, covers[5], new ItemStack(Blocks.planks, 1, 3));
            }
        }
        Class<?> shape = Class.forName("gcewing.architecture.common.shape.Shape");
        Object roof = shape.getField("RoofTile")
            .get(null);
        Class<?> tileShape = Class.forName("gcewing.architecture.common.tile.TileShape");
        for (int i = 0; i < 2; i++) {
            int xx = x + 4 + 2 * i;
            require(world.setBlock(xx, y, z, architecture, 0, 3), "native Architecture block created");
            TileEntity tile = (TileEntity) tileShape.getConstructor(shape, Block.class, int.class)
                .newInstance(roof, i == 0 ? Blocks.brick_block : Blocks.quartz_block, 0);
            tileShape.getMethod("setSide", int.class)
                .invoke(tile, 0);
            tileShape.getMethod("setTurn", int.class)
                .invoke(tile, i);
            world.setTileEntity(xx, y, z, tile);
            world.markBlockForUpdate(xx, y, z);
        }
        return new Source(world, x, y, z);
    }

    private static Block registered(String type) {
        for (Object entry : Block.blockRegistry) if (entry != null && entry.getClass()
            .getName()
            .equals(type)) return (Block) entry;
        throw new IllegalStateException("production probe requires installed block " + type);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** Independent source evidence only. The probe must obtain preview rows from HologramService. */
    public static final class Source {

        public final int x, y, z;
        private final WorldServer world;
        private final Block[] blocks = new Block[4];
        private final TileEntity[] tiles = new TileEntity[4];
        private final NBTTagCompound[] saved = new NBTTagCompound[4];
        private final int[] metadata = new int[4];

        private Source(WorldServer world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            for (int i = 0; i < 4; i++) {
                blocks[i] = world.getBlock(x + 2 * i, y, z);
                metadata[i] = world.getBlockMetadata(x + 2 * i, y, z);
                tiles[i] = world.getTileEntity(x + 2 * i, y, z);
                require(tiles[i] != null, "decor source native tile exists");
                saved[i] = nativeNbt(tiles[i]);
            }
            require(
                saved[0].getTagList("cbAttrList", 10)
                    .tagCount() == 0,
                "bare Carpenter has no skin");
            require(
                saved[1].getTagList("cbAttrList", 10)
                    .tagCount() == 2,
                "quartz and jungle native skins saved");
            require(
                saved[2].getString("BaseName")
                    .equals("minecraft:brick_block"),
                "native brick roof saved");
            require(
                saved[3].getString("BaseName")
                    .equals("minecraft:quartz_block"),
                "native quartz roof saved");
        }

        public int[] bounds() {
            return new int[] { x, y, z, x + 6, y, z };
        }

        public NBTTagCompound sourceNbt(int index) {
            return (NBTTagCompound) saved[index].copy();
        }

        public void assertUnchanged() {
            for (int i = 0; i < 4; i++) {
                int xx = x + 2 * i;
                require(world.getBlock(xx, y, z) == blocks[i], "decor source block unchanged " + i);
                require(world.getBlockMetadata(xx, y, z) == metadata[i], "decor source metadata unchanged " + i);
                require(world.getTileEntity(xx, y, z) == tiles[i], "decor source tile identity unchanged " + i);
                require(tiles[i].getWorldObj() == world, "decor source world identity unchanged " + i);
                require(
                    tiles[i].xCoord == xx && tiles[i].yCoord == y && tiles[i].zCoord == z,
                    "decor source coordinates unchanged " + i);
                require(saved[i].equals(nativeNbt(tiles[i])), "decor source native persistence unchanged " + i);
            }
        }

        private static NBTTagCompound nativeNbt(TileEntity tile) {
            NBTTagCompound tag = new NBTTagCompound();
            tile.writeToNBT(tag);
            return tag;
        }
    }
}
