package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared exact occupancy and native cuboid packing for editable LittleTiles attachments. */
final class HologramMicroTiles {

    private final Map<String, Host> hosts = new LinkedHashMap<>();
    private long samples, voxels;

    void add(int x, int y, int z, String material, int metadata, int color) throws IOException {
        addBox(x, y, z, x + 1, y + 1, z + 1, material, metadata, color);
    }

    /** Split exact cuboids at native 16-unit boundaries instead of expanding scaled pixels voxel by voxel. */
    void addBox(int x, int y, int z, int endX, int endY, int endZ, String material, int metadata, int color)
        throws IOException {
        long volume = (long) (endX - x) * (endY - y) * (endZ - z);
        samples += volume;
        if (volume <= 0 || samples > 4L * HologramTextBox.MAX_VOXELS) throw new IOException("附加对象采样超过上限，请减小尺寸或厚度");
        for (int by = Math.floorDiv(y, 16); by <= Math.floorDiv(endY - 1, 16); by++)
            for (int bz = Math.floorDiv(z, 16); bz <= Math.floorDiv(endZ - 1, 16); bz++)
                for (int bx = Math.floorDiv(x, 16); bx <= Math.floorDiv(endX - 1, 16); bx++) {
                    String key = bx + "," + by + "," + bz;
                    Host host = hosts.get(key);
                    if (host == null) {
                        if (hosts.size() >= HologramTextBox.MAX_BLOCKS)
                            throw new IOException("附加对象方块数量超过上限，请减小像素尺寸或厚度");
                        host = new Host(bx, by, bz);
                        hosts.put(key, host);
                    }
                    String paint = material + ":" + metadata + ":" + color;
                    Layer layer = host.layers.computeIfAbsent(paint, ignored -> new Layer(material, metadata, color));
                    int minX = Math.max(x - bx * 16, 0), maxX = Math.min(endX - bx * 16, 16);
                    for (int yy = Math.max(y - by * 16, 0); yy < Math.min(endY - by * 16, 16); yy++)
                        for (int zz = Math.max(z - bz * 16, 0); zz < Math.min(endZ - bz * 16, 16); zz++) {
                            int row = yy << 8 | zz << 4;
                            for (int first = host.occupied.nextClearBit(row + minX); first
                                < row + maxX; first = host.occupied.nextClearBit(first)) {
                                int end = Math.min(host.occupied.nextSetBit(first), row + maxX);
                                if (end < 0) end = row + maxX;
                                host.occupied.set(first, end);
                                layer.occupied.set(first, end);
                                voxels += end - first;
                                first = end;
                            }
                        }
                    if (voxels > HologramTextBox.MAX_VOXELS) throw new IOException("附加对象体积超过上限，请减小像素尺寸或厚度");
                }
    }

    List<HologramTextBox.Cell> finish(int x, int y, int z) throws IOException {
        if (hosts.isEmpty()) throw new IOException("附加对象没有可见像素");
        List<HologramTextBox.Cell> result = new ArrayList<>();
        int total = hosts.size(), completed = 0;
        java.util.Iterator<Host> iterator = hosts.values()
            .iterator();
        while (iterator.hasNext()) {
            Host host = iterator.next();
            HologramAttachmentGeometry.checkpoint("packing", completed++, total);
            host.layers.values()
                .removeIf(layer -> layer.occupied.isEmpty());
            boolean full = host.occupied.cardinality() == 4096 && host.layers.size() == 1
                && host.layers.values()
                    .iterator()
                    .next().color == 0xffffff;
            String[] materials = new String[host.layers.size()];
            int[] metadata = new int[materials.length], colors = new int[materials.length];
            int[] boxes = new int[112];
            int count = 0, paint = 0;
            for (Layer layer : host.layers.values()) {
                materials[paint] = layer.material;
                metadata[paint] = layer.metadata;
                colors[paint] = layer.color;
                BitSet occupied = layer.occupied;
                for (int first = occupied.nextSetBit(0); first >= 0; first = occupied.nextSetBit(0)) {
                    int minX = first & 15, minY = first >>> 8, minZ = first >>> 4 & 15;
                    int maxX = minX + 1, maxZ = minZ + 1, maxY = minY + 1;
                    while (maxX < 16 && occupied.get(minY << 8 | minZ << 4 | maxX)) maxX++;
                    while (maxZ < 16 && solid(occupied, minX, maxX, minY, minY + 1, maxZ, maxZ + 1)) maxZ++;
                    while (maxY < 16 && solid(occupied, minX, maxX, maxY, maxY + 1, minZ, maxZ)) maxY++;
                    for (int yy = minY; yy < maxY; yy++) for (int zz = minZ; zz < maxZ; zz++)
                        occupied.clear(yy << 8 | zz << 4 | minX, (yy << 8 | zz << 4) + maxX);
                    if (count + 7 > boxes.length) boxes = java.util.Arrays.copyOf(boxes, boxes.length * 2);
                    for (int value : new int[] { paint, minX, minY, minZ, maxX, maxY, maxZ }) boxes[count++] = value;
                }
                paint++;
            }
            result.add(
                new HologramTextBox.Cell(
                    host.x + x,
                    host.y + y,
                    host.z + z,
                    java.util.Arrays.copyOf(boxes, count),
                    materials,
                    metadata,
                    colors,
                    full));
            iterator.remove(); // Release occupancy before retaining the next compact host.
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
