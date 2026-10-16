package com.miaokatze.gtmb.testlab;

import net.minecraft.nbt.NBTTagCompound;

/** Immutable facility bounds; no caller can turn reset into an arbitrary world edit. */
final class TestLabZone {

    static final int SIZE = 32, HEIGHT = 16;
    final String id;
    final int x, y, z;

    TestLabZone(String id, int x, int y, int z) {
        this.id = id;
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
            && (long) px + sx <= (long) x + SIZE
            && (long) py + sy <= (long) y + HEIGHT
            && (long) pz + sz <= (long) z + SIZE;
    }

    int cells() {
        return SIZE * HEIGHT * SIZE;
    }

    int[] cell(int index) {
        if (index < 0 || index >= cells()) throw new IllegalArgumentException("facility cell index");
        return new int[] { x + index % SIZE, y + index / (SIZE * SIZE), z + index / SIZE % SIZE };
    }

    NBTTagCompound tag() {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("id", id);
        n.setInteger("x", x);
        n.setInteger("y", y);
        n.setInteger("z", z);
        n.setInteger("sizeX", SIZE);
        n.setInteger("sizeY", HEIGHT);
        n.setInteger("sizeZ", SIZE);
        return n;
    }
}
