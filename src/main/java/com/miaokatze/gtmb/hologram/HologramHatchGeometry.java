// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.BitSet;
import java.util.List;

import net.minecraftforge.common.util.ForgeDirection;

/** Air connected to the outside of the planned structure, excluding enclosed process cavities. */
final class HologramHatchGeometry {

    private HologramHatchGeometry() {}

    interface Air {

        boolean test(int x, int y, int z);
    }

    static Exterior exterior(List<int[]> cells, Air air) {
        HologramExteriorTask task = new HologramExteriorTask(cells, air);
        while (!task.done()) task.advance();
        return task.result();
    }

    static int choose(int x, int y, int z, int[] legal, Exterior exterior) {
        // Prefer a horizontal external air face; never fall through to an enclosed cavity.
        for (int pass = 0; pass < 2; pass++) for (int side : legal) {
            if (pass == 0 && side < 2) continue;
            ForgeDirection d = ForgeDirection.getOrientation(side);
            if (exterior.contains(x + d.offsetX, y + d.offsetY, z + d.offsetZ)) return side;
        }
        return -1;
    }

    /** Task-local bounded coordinates, never world identities or a persisted representation. */
    static final class Exterior {

        static final Exterior EMPTY = new Exterior(0, 0, 0, 0, 0, 0);
        final int minX, minY, minZ, width, height, depth;
        final BitSet positions;

        Exterior(int minX, int minY, int minZ, int width, int height, int depth) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            this.depth = depth;
            positions = new BitSet(width * height * depth);
        }

        int index(int x, int y, int z) {
            long dx = (long) x - minX, dy = (long) y - minY, dz = (long) z - minZ;
            if (dx < 0 || dx >= width || dy < 0 || dy >= height || dz < 0 || dz >= depth) return -1;
            return (int) ((dx * height + dy) * depth + dz);
        }

        boolean contains(int x, int y, int z) {
            int index = index(x, y, z);
            return index >= 0 && positions.get(index);
        }

        boolean isEmpty() {
            return positions.isEmpty();
        }
    }
}
