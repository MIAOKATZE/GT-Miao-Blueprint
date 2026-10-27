package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Ordered constructive selection expressed as x runs, without testing every bounding-box cell per region. */
final class HologramCutSelection {

    private HologramCutSelection() {}

    static BitSet mask(HologramAreaSelection selection, int x, int y, int z, int sx, int sy, int sz)
        throws IOException {
        Builder builder = new Builder(selection, x, y, z, sx, sy, sz);
        while (!builder.step(4096)) {}
        return builder.result();
    }

    /** A checkpoint is one selected region's yz row; callers can retain this before world access. */
    static final class Builder {

        private final List<HologramAreaSelection.Region> regions;
        private final int x, y, z, sx, sy, sz;
        private final BitSet mask;
        private int region, rowY, rowZ;
        private boolean entered, done;

        Builder(HologramAreaSelection selection, int x, int y, int z, int sx, int sy, int sz) throws IOException {
            HologramCutEntry.bounds(x, y, z, sx, sy, sz);
            regions = selection == null ? java.util.Collections.emptyList() : new ArrayList<>(selection.regions);
            this.x = x;
            this.y = y;
            this.z = z;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            mask = new BitSet(HologramCutEntry.volume(sx, sy, sz));
            if (selection == null) {
                mask.set(0, sx * sy * sz);
                done = true;
            }
        }

        boolean step(int rows) {
            if (rows <= 0) throw new IllegalArgumentException("rows");
            while (!done && rows-- > 0) {
                if (region >= regions.size()) {
                    done = true;
                    break;
                }
                HologramAreaSelection.Region box = regions.get(region);
                int minX = Math.max(x, box.minX), maxX = Math.min(x + sx - 1, box.maxX);
                int minY = Math.max(y, box.minY), maxY = Math.min(y + sy - 1, box.maxY);
                int minZ = Math.max(z, box.minZ), maxZ = Math.min(z + sz - 1, box.maxZ);
                if (minX > maxX || minY > maxY || minZ > maxZ) {
                    region++;
                    entered = false;
                    continue;
                }
                if (!entered) {
                    rowY = minY;
                    rowZ = minZ;
                    entered = true;
                }
                int start = ((rowY - y) * sz + rowZ - z) * sx;
                mask.set(start + minX - x, start + maxX - x + 1, !"subtract".equals(box.mode));
                if (++rowZ > maxZ) {
                    rowZ = minZ;
                    if (++rowY > maxY) {
                        region++;
                        entered = false;
                    }
                }
            }
            if (!done && region == regions.size()) done = true;
            return done;
        }

        BitSet result() throws IOException {
            if (!done) throw new IllegalStateException("selection still running");
            if (mask.isEmpty()) throw new IOException("减选后没有可剪切的区域");
            return mask;
        }
    }
}
