package com.miaokatze.gtmb.api;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramCutTransforms;

/** Detached raw clipboard transformations. Callbacks must not access/mutate a live world or run loading hooks. */
public final class CutTransforms {

    private CutTransforms() {}

    public interface Adapter {

        boolean accepts(State state);

        /** Change only the schema's spatial fields; retain inventory, ownership and unknown native fields. */
        void transform(State state, Transform transform) throws IOException;
    }

    public static final class State {

        public final Block block;
        public int metadata;
        public NBTTagCompound tile;
        public final int sourceX, sourceY, sourceZ, targetX, targetY, targetZ;

        public State(Block block, int metadata, NBTTagCompound tile, int sourceX, int sourceY, int sourceZ, int targetX,
            int targetY, int targetZ) {
            this.block = block;
            this.metadata = metadata;
            this.tile = tile == null ? null : (NBTTagCompound) tile.copy();
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.sourceZ = sourceZ;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
        }

        public State copy() {
            return new State(block, metadata, tile, sourceX, sourceY, sourceZ, targetX, targetY, targetZ);
        }
    }

    /** Mirror in source-local axes, then X, Y, Z quarter turns. Positive Y matches the existing R rotation. */
    public static final class Transform {

        public final int rotation, turnX, turnZ, mirror;
        private final int[] matrix;

        public Transform(int rotation, int turnX, int turnZ, int mirror) {
            this.rotation = Math.floorMod(rotation, 4);
            this.turnX = Math.floorMod(turnX, 4);
            this.turnZ = Math.floorMod(turnZ, 4);
            this.mirror = mirror & 7;
            matrix = new int[9];
            for (int axis = 0; axis < 3; axis++) {
                int[] v = new int[3];
                v[axis] = (this.mirror & (1 << axis)) == 0 ? 1 : -1;
                for (int n = 0; n < this.turnX; n++) v = new int[] { v[0], -v[2], v[1] };
                for (int n = 0; n < this.rotation; n++) v = new int[] { -v[2], v[1], v[0] };
                for (int n = 0; n < this.turnZ; n++) v = new int[] { -v[1], v[0], v[2] };
                for (int row = 0; row < 3; row++) matrix[row * 3 + axis] = v[row];
            }
        }

        public static Transform read(NBTTagCompound tag) {
            return new Transform(
                tag.getInteger("rotation"),
                tag.getInteger("turnX"),
                tag.getInteger("turnZ"),
                tag.getInteger("mirror"));
        }

        public void write(NBTTagCompound tag) {
            tag.setInteger("rotation", rotation);
            tag.setInteger("turnX", turnX);
            tag.setInteger("turnZ", turnZ);
            tag.setInteger("mirror", mirror);
        }

        public int[] matrix() {
            return matrix.clone();
        }

        public int[] vector(int x, int y, int z) {
            return new int[] { matrix[0] * x + matrix[1] * y + matrix[2] * z,
                matrix[3] * x + matrix[4] * y + matrix[5] * z, matrix[6] * x + matrix[7] * y + matrix[8] * z };
        }

        public int direction(int face) {
            if (face < 0 || face >= 6) return face;
            ForgeDirection d = ForgeDirection.getOrientation(face);
            int[] v = vector(d.offsetX, d.offsetY, d.offsetZ);
            for (ForgeDirection next : ForgeDirection.VALID_DIRECTIONS)
                if (next.offsetX == v[0] && next.offsetY == v[1] && next.offsetZ == v[2]) return next.ordinal();
            return face;
        }

        public int[] size(int sx, int sy, int sz) {
            int[] v = vector(sx, sy, sz);
            return new int[] { Math.abs(v[0]), Math.abs(v[1]), Math.abs(v[2]) };
        }

        public int[] point(int x, int y, int z, int sx, int sy, int sz) {
            int[] v = vector(x, y, z), sizes = { sx, sy, sz };
            for (int row = 0; row < 3; row++)
                for (int axis = 0; axis < 3; axis++) if (matrix[row * 3 + axis] < 0) v[row] += sizes[axis] - 1;
            return v;
        }

        public boolean identity() {
            return java.util.Arrays.equals(matrix, new int[] { 1, 0, 0, 0, 1, 0, 0, 0, 1 });
        }
    }

    public static GTMBExtensions.Registration register(String id, Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        return GTMBExtensions.install(id, "cut-transform", () -> HologramCutTransforms.install(adapter));
    }
}
