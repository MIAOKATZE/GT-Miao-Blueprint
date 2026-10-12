package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** CarpentersBlocks 3.7.3 TEBase attribute slots and each block's actual cbMetadata schema. */
final class HologramBlockTransformCarpenters {

    private HologramBlockTransformCarpenters() {}

    static void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
        if (data.tile == null) return;
        if (data.block == null) throw new IOException("CarpentersBlocks target block is missing");
        String type = data.block.getClass()
            .getSimpleName();
        int value = data.tile.getInteger("cbMetadata"), rotated = metadata(type, value, vertical);
        data.tile.setInteger("cbMetadata", rotated);
        NBTTagList attributes = data.tile.getTagList("cbAttrList", 10);
        for (int i = 0; i < attributes.tagCount(); i++) {
            NBTTagCompound entry = attributes.getCompoundTagAt(i);
            int attribute = entry.getByte("cbAttribute") & 255;
            if (attribute < 21 && attribute % 7 < 6) entry.setByte(
                "cbAttribute",
                (byte) (attribute / 7 * 7 + HologramVirtualTransform.front(attribute % 7, vertical)));
        }
        HologramBlockTransformAdapters.sideKeys(data.tile, "cbChiselDesign_", false, vertical);
    }

    static int metadata(String type, int value, boolean vertical) throws IOException {
        if ("BlockCarpentersBlock".equals(type)) {
            int[] sides = { 4, 5, 0, 1, 2, 3 };
            if (value < 0 || value > 6) throw new IOException("Invalid Carpenter slab shape");
            if (value == 0) return 0;
            int side = HologramVirtualTransform.front(sides[value - 1], vertical);
            for (int i = 0; i < 6; i++) if (sides[i] == side) return i + 1;
        }
        if ("BlockCarpentersButton".equals(type) || "BlockCarpentersPressurePlate".equals(type))
            return direction(value, 7, 0, vertical);
        if ("BlockCarpentersDaylightSensor".equals(type)) return direction(value, 0x380, 7, vertical);
        if (vertical) throw new IOException("CarpentersBlocks shape cannot represent this vertical turn: " + type);
        if ("BlockCarpentersSlope".equals(type)) return slope(value);
        if ("BlockCarpentersStairs".equals(type)) {
            // Same directional family indices as Slope 0..27, verified against Stairs.stairsList.
            if (value < 0 || value >= 28) throw new IOException("Invalid Carpenter stair shape");
            return slope(value);
        }
        if ("BlockCarpentersBed".equals(type)) return quarter(value, 0x6000, 13);
        if ("BlockCarpentersSafe".equals(type)) return quarter(value, 3, 0);
        if ("BlockCarpentersDoor".equals(type)) return quarter(value, 0x30, 4);
        if ("BlockCarpentersHatch".equals(type)) {
            int[] turns = { 3, 2, 0, 1 };
            return (value & ~0x60) | turns[(value & 0x60) >> 5] << 5;
        }
        if ("BlockCarpentersGarageDoor".equals(type)) return direction(value, 0x70, 4, false);
        if ("BlockCarpentersTorch".equals(type)) return direction(value, 7, 0, false);
        if ("BlockCarpentersGate".equals(type)) return (value ^ 0x20) ^ ((value & 0x20) == 0 ? 0x10 : 0);
        if ("BlockCarpentersLadder".equals(type)) {
            int side = value & 7;
            return side < 2 ? value ^ 1 : direction(value, 7, 0, false);
        }
        if ("BlockCarpentersLever".equals(type)) {
            int side = value & 7;
            int result = direction(value, 7, 0, false);
            return side < 2 ? result ^ 0x40 : result;
        }
        if ("BlockCarpentersCollapsibleBlock".equals(type)) {
            int[] shifts = { 18, 13, 8, 3 };
            int[] next = { 2, 0, 3, 1 }; // XNZN->XPZN; XNZP->XNZN; XPZN->XPZP; XPZP->XNZP.
            int mask = (31 << 18) | (31 << 13) | (31 << 8) | (31 << 3), result = value & ~mask;
            for (int i = 0; i < 4; i++) result |= ((value >> shifts[i]) & 31) << shifts[next[i]];
            return result;
        }
        // Flower pot contents and designs, barrier material/type and fence connections are orientation invariant.
        if ("BlockCarpentersFlowerPot".equals(type) || "BlockCarpentersBarrier".equals(type)) return value;
        throw new IOException("Unsupported CarpentersBlocks shape transform: " + type);
    }

    private static int direction(int value, int mask, int shift, boolean vertical) throws IOException {
        int side = (value & mask) >> shift;
        if (side < 0 || side > 5) throw new IOException("Invalid Carpenter side");
        return (value & ~mask) | HologramVirtualTransform.front(side, vertical) << shift;
    }

    private static int quarter(int value, int mask, int shift) {
        return (value & ~mask) | ((((value & mask) >> shift) + 1) & 3) << shift;
    }

    private static int slope(int value) throws IOException {
        try {
            Class<?> slope = Class.forName("com.carpentersblocks.data.Slope");
            Field field = slope.getDeclaredField("ROTATION_Y");
            field.setAccessible(true);
            byte[][] table = (byte[][]) field.get(null);
            if (value < 0 || value >= table[1].length) throw new IOException("Invalid Carpenter slope shape");
            // Native Slope.rotate(..., UP) selects row 1: NORTH->EAST, matching the shared world turn.
            return table[1][value] & 255;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
            throw new IOException("CarpentersBlocks 3.7.3 slope rotation API unavailable", invalid);
        }
    }
}
