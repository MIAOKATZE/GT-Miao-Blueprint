package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Strict installed-mod seeds using official native schemas and registered material items. */
public final class HologramNativeCompatibilityFixture {

    private static final String[] TYPES = { "com.carpentersblocks.block.BlockCarpentersSlope",
        "codechicken.multipart.BlockMultipart", "com.creativemd.littletiles.common.blocks.BlockTile",
        "gcewing.architecture.common.block.BlockShape", "crazypants.enderio.conduit.BlockConduitBundle",
        "appeng.block.networking.BlockCableBus" };

    private HologramNativeCompatibilityFixture() {}

    public static List<Block> blocks() {
        List<Block> blocks = new ArrayList<>();
        for (String type : TYPES) blocks.add(find(type));
        return blocks;
    }

    private static Block find(String name) {
        for (Object entry : Block.blockRegistry) if (entry != null && entry.getClass()
            .getName()
            .equals(name)) return (Block) entry;
        throw new IllegalStateException("Strict native fixture requires installed block " + name);
    }

    public static NBTTagCompound seed(Block block, int x, int y, int z) throws ReflectiveOperationException {
        int kind = -1;
        for (int i = 0; i < TYPES.length; i++) if (block.getClass()
            .getName()
            .equals(TYPES[i])) kind = i;
        require(kind >= 0, "Unsupported native fixture block " + block);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        if (kind == 0) {
            tag.setInteger("cbMetadata", 8); // Carpenter positive north wedge, per Slope.ID_WEDGE_POS_N.
            NBTTagList attributes = new NBTTagList();
            attributes.appendTag(carpenterCover(Blocks.stone, 6));
            attributes.appendTag(carpenterCover(Blocks.planks, 5));
            tag.setTag("cbAttrList", attributes);
        } else if (kind == 1) {
            NBTTagList parts = new NBTTagList();
            parts.appendTag(microblock("mcr_face", 0x10, "minecraft:stone"));
            parts.appendTag(microblock("mcr_face", 0x25, "minecraft:planks"));
            tag.setTag("parts", parts);
        } else if (kind == 2) {
            tag.setInteger("tilesCount", 2);
            tag.setTag("t0", little("minecraft:planks", 0, 0, 0, 8, 4, 12));
            tag.setTag("t1", little("minecraft:stone", 8, 0, 4, 16, 12, 8));
        } else if (kind == 3) {
            tag.setInteger("Shape", 12); // Curved quarter cylinder proves this is the real architecture mesh.
            tag.setString("BaseName", "minecraft:planks");
            tag.setByte("side", (byte) 0);
            tag.setByte("turn", (byte) 0);
        } else if (kind == 4) {
            tag.setShort("nbtVersion", (short) 1);
            tag.setString("facadeId", "null");
            NBTTagList conduits = new NBTTagList();
            conduits.appendTag(conduit("crazypants.enderio.conduit.item.ItemConduit", new int[] { 2, 5 }));
            conduits.appendTag(conduit("crazypants.enderio.conduit.power.PowerConduit", new int[] { 2, 5 }));
            tag.setTag("conduits", conduits);
        } else {
            Object api = Class.forName("appeng.api.AEApi")
                .getMethod("instance")
                .invoke(null);
            Object definitions = api.getClass()
                .getMethod("definitions")
                .invoke(api);
            Object parts = definitions.getClass()
                .getMethod("parts")
                .invoke(definitions);
            Object glass = parts.getClass()
                .getMethod("cableGlass")
                .invoke(parts);
            Class<?> color = Class.forName("appeng.api.util.AEColor");
            Object red = color.getField("Red")
                .get(null);
            ItemStack stack = (ItemStack) glass.getClass()
                .getMethod("stack", color, int.class)
                .invoke(glass, red, 1);
            require(stack != null, "real AE glass cable must be enabled");
            NBTTagCompound item = new NBTTagCompound();
            stack.writeToNBT(item);
            tag.setTag("def:6", item);
            tag.setTag("extra:6", new NBTTagCompound());
            NBTTagCompound visual = new NBTTagCompound();
            visual.setInteger("connections", 1 << 2 | 1 << 5);
            visual.setInteger("channels", 0);
            tag.setTag("gtmb.aeCableVisual", visual);
        }
        return tag;
    }

    private static NBTTagCompound carpenterCover(Block block, int side) {
        NBTTagCompound row = new NBTTagCompound();
        new ItemStack(block).writeToNBT(row);
        row.setString("cbUniqueId", Block.blockRegistry.getNameForObject(block));
        row.setByte("cbAttribute", (byte) side);
        return row;
    }

    private static NBTTagCompound microblock(String type, int shape, String material) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", type);
        row.setByte("shape", (byte) shape);
        row.setString("material", material);
        return row;
    }

    private static NBTTagCompound little(String block, int x0, int y0, int z0, int x1, int y1, int z1) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("tID", "BlockTileBlock");
        row.setString("block", block);
        row.setInteger("bSize", 1);
        String[] names = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
        int[] values = { x0, y0, z0, x1, y1, z1 };
        for (int i = 0; i < names.length; i++) row.setInteger("bBox0" + names[i], values[i]);
        return row;
    }

    private static NBTTagCompound conduit(String name, int[] connections) throws ReflectiveOperationException {
        Object conduit = Class.forName(name)
            .getConstructor()
            .newInstance();
        NBTTagCompound row = new NBTTagCompound();
        Method save = Class.forName("crazypants.enderio.conduit.ConduitUtil")
            .getMethod("writeToNBT", Class.forName("crazypants.enderio.conduit.IConduit"), NBTTagCompound.class);
        save.invoke(null, conduit, row);
        row.getCompoundTag("conduit")
            .setIntArray("connections", connections);
        return row;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
