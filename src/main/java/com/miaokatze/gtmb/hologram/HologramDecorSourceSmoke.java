package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

/** Real world decor sources for the opt-in production capture/save/network preview probe. */
public final class HologramDecorSourceSmoke {

    private HologramDecorSourceSmoke() {}

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
