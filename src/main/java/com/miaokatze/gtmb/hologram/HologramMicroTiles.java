package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

/** Shared exact occupancy and native cuboid packing for editable LittleTiles attachments. */
final class HologramMicroTiles {

    private final Map<String, Host> hosts = new LinkedHashMap<>();
    private int samples, voxels;

    void add(int x, int y, int z, String material, int metadata, int color) throws IOException {
        if (++samples > HologramTextBox.MAX_VOXELS * 4) throw new IOException("附加对象采样超过上限，请减小尺寸或厚度");
        int bx = Math.floorDiv(x, 16), by = Math.floorDiv(y, 16), bz = Math.floorDiv(z, 16);
        String key = bx + "," + by + "," + bz;
        Host host = hosts.computeIfAbsent(key, ignored -> new Host(bx, by, bz));
        int voxel = Math.floorMod(y, 16) << 8 | Math.floorMod(z, 16) << 4 | Math.floorMod(x, 16);
        // Rounded rotations may map multiple samples to one voxel. First sample owns its material.
        if (!host.occupied.get(voxel)) {
            host.occupied.set(voxel);
            String paint = material + ":" + metadata + ":" + color;
            host.layers.computeIfAbsent(paint, ignored -> new Layer(material, metadata, color)).occupied.set(voxel);
            voxels++;
        }
        if (voxels > HologramTextBox.MAX_VOXELS || hosts.size() > HologramTextBox.MAX_BLOCKS)
            throw new IOException("附加对象瓷砖体积或方块数量超过上限");
    }

    List<HologramTextBox.Cell> finish(int x, int y, int z) throws IOException {
        if (hosts.isEmpty()) throw new IOException("附加对象没有可见像素");
        List<HologramTextBox.Cell> result = new ArrayList<>();
        for (Host host : hosts.values()) {
            NBTTagCompound tile = new NBTTagCompound();
            tile.setString("id", "LittleTilesTileEntity");
            boolean full = host.occupied.cardinality() == 4096 && host.layers.size() == 1
                && host.layers.values()
                    .iterator()
                    .next().color == 0xffffff;
            int count = 0;
            for (Layer layer : host.layers.values()) {
                BitSet occupied = layer.occupied;
                for (int first = occupied.nextSetBit(0); first >= 0; first = occupied.nextSetBit(0)) {
                    int minX = first & 15, minY = first >>> 8, minZ = first >>> 4 & 15;
                    int maxX = minX + 1, maxZ = minZ + 1, maxY = minY + 1;
                    while (maxX < 16 && occupied.get(minY << 8 | minZ << 4 | maxX)) maxX++;
                    while (maxZ < 16 && solid(occupied, minX, maxX, minY, minY + 1, maxZ, maxZ + 1)) maxZ++;
                    while (maxY < 16 && solid(occupied, minX, maxX, maxY, maxY + 1, minZ, maxZ)) maxY++;
                    for (int yy = minY; yy < maxY; yy++) for (int zz = minZ; zz < maxZ; zz++)
                        occupied.clear(yy << 8 | zz << 4 | minX, (yy << 8 | zz << 4) + maxX);
                    NBTTagCompound box = new NBTTagCompound();
                    box.setString("tID", layer.color == 0xffffff ? "BlockTileBlock" : "BlockTileColored");
                    box.setString("block", layer.material);
                    box.setInteger("meta", layer.metadata);
                    box.setInteger("color", layer.color);
                    box.setInteger("bSize", 1);
                    box.setInteger("cVecx", minX);
                    box.setInteger("cVecy", minY);
                    box.setInteger("cVecz", minZ);
                    box.setInteger("bBox0minX", minX);
                    box.setInteger("bBox0minY", minY);
                    box.setInteger("bBox0minZ", minZ);
                    box.setInteger("bBox0maxX", maxX);
                    box.setInteger("bBox0maxY", maxY);
                    box.setInteger("bBox0maxZ", maxZ);
                    tile.setTag("t" + count++, box);
                }
            }
            tile.setInteger("tilesCount", count);
            result.add(new HologramTextBox.Cell(host.x + x, host.y + y, host.z + z, tile, full));
        }
        return result;
    }

    private static boolean solid(BitSet occupied, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        for (int y = minY; y < maxY; y++) for (int z = minZ; z < maxZ; z++) {
            int row = y << 8 | z << 4;
            if (occupied.nextClearBit(row + minX) < row + maxX) return false;
        }
        return true;
    }

    private static final class Host {

        final int x, y, z;
        final BitSet occupied = new BitSet(4096);
        final Map<String, Layer> layers = new LinkedHashMap<>();

        Host(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Layer {

        final String material;
        final int metadata, color;
        final BitSet occupied = new BitSet(4096);

        Layer(String material, int metadata, int color) {
            this.material = material;
            this.metadata = metadata;
            this.color = color;
        }
    }
}
