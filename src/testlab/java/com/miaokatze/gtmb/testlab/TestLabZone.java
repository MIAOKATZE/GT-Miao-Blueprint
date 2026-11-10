package com.miaokatze.gtmb.testlab;

import net.minecraft.nbt.NBTTagCompound;

/** Immutable facility bounds; no caller can turn reset into an arbitrary world edit. */
final class TestLabZone {

    static final int SIZE = 32, HEIGHT = 16;
    final String id;
    final int x, y, z, sizeX, sizeY, sizeZ;

    TestLabZone(String id, int x, int y, int z) {
        this(id, x, y, z, SIZE, HEIGHT, SIZE);
    }

    TestLabZone(String id, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        this.id = id;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    boolean contains(int px, int py, int pz, int sx, int sy, int sz) {
        return sx > 0 && sy > 0
            && sz > 0
            && px >= x
            && py >= y
            && pz >= z
            && (long) px + sx <= (long) x + sizeX
            && (long) py + sy <= (long) y + sizeY
            && (long) pz + sz <= (long) z + sizeZ;
    }

    int cells() {
        return sizeX * sizeY * sizeZ;
    }

    int[] cell(int index) {
        if (index < 0 || index >= cells()) throw new IllegalArgumentException("facility cell index");
        return new int[] { x + index % sizeX, y + index / (sizeX * sizeZ), z + index / sizeX % sizeZ };
    }

    NBTTagCompound tag() {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("id", id);
        n.setInteger("x", x);
        n.setInteger("y", y);
        n.setInteger("z", z);
        n.setInteger("sizeX", sizeX);
        n.setInteger("sizeY", sizeY);
        n.setInteger("sizeZ", sizeZ);
        return n;
    }
}
