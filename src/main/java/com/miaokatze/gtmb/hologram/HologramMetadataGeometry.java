package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import com.miaokatze.gtmb.api.CutTransforms;

/** Small native codebooks, independent of world callbacks and optional mod classes. */
final class HologramMetadataGeometry {

    private static final int[] STAIR_MISSING = { 4, 5, 2, 3 };
    private static final int[][] RAIL_ENDS = { { 2, 3 }, { 4, 5 }, { 5 }, { 4 }, { 2 }, { 3 }, { 3, 5 }, { 3, 4 },
        { 2, 4 }, { 2, 5 } };
    // Normal points away from the mounting surface. The second vector is the base's undirected long axis.
    private static final int[] LEVER_NORMAL = { 0, 5, 4, 3, 2, 1, 1, 0 };
    private static final int[] LEVER_AXIS = { 5, 1, 1, 1, 1, 3, 5, 3 };

    private HologramMetadataGeometry() {}

    static int stair(int metadata, CutTransforms.Transform pose) throws IOException {
        int a = pose.direction((metadata & 4) == 0 ? 1 : 0);
        int b = pose.direction(STAIR_MISSING[metadata & 3]);
        // A straight stair is a cube with one quarter removed. Its two missing-quadrant normals are unordered.
        if (a >= 2 && b < 2) {
            int swap = a;
            a = b;
            b = swap;
        }
        HologramCutTransformMetadata.require(a < 2 && b >= 2, "sideways stair profile is not representable");
        return (a == 0 ? 4 : 0) | HologramCutTransformMetadata.index(STAIR_MISSING, b);
    }

    static int rail(int metadata, boolean powered, CutTransforms.Transform pose) throws IOException {
        int shape = powered ? metadata & 7 : metadata;
        HologramCutTransformMetadata.require(shape < (powered ? 6 : 10), "invalid rail shape");
        int[] ends = RAIL_ENDS[shape];
        int a = pose.direction(ends[0]);
        int b = ends.length == 2 ? pose.direction(ends[1]) : -1;
        for (int i = 0; i < (powered ? 6 : 10); i++) {
            int[] candidate = RAIL_ENDS[i];
            if (candidate.length != ends.length || (shape >= 2 && shape <= 5) != (i >= 2 && i <= 5)) continue;
            if (ends.length == 1 ? candidate[0] == a
                : candidate[0] == a && candidate[1] == b || candidate[0] == b && candidate[1] == a)
                return (powered ? metadata & 8 : 0) | i;
        }
        throw new IOException("Native rail cannot represent this transformed shape");
    }

    static int lever(int metadata, CutTransforms.Transform pose) throws IOException {
        int old = metadata & 7, normal = pose.direction(LEVER_NORMAL[old]);
        int axis = pose.direction(LEVER_AXIS[old]) >> 1;
        for (int i = 0; i < 8; i++)
            if (LEVER_NORMAL[i] == normal && (LEVER_AXIS[i] >> 1) == axis) return (metadata & 8) | i;
        throw new IOException("Native lever has no mounting roll for this pose");
    }

    static int mushroom(int metadata, CutTransforms.Transform pose) throws IOException {
        if (metadata == 0 || metadata >= 11) return metadata;
        int wanted = faceMask(metadata), changed = 0;
        for (int side = 0; side < 6; side++) if ((wanted & (1 << side)) != 0) changed |= 1 << pose.direction(side);
        if (metadata == 10) {
            HologramCutTransformMetadata
                .require(changed == wanted, "sideways mushroom stem texture is not representable");
            return metadata;
        }
        for (int i = 1; i <= 9; i++) if (faceMask(i) == changed) return i;
        throw new IOException("Native mushroom cap cannot represent the transformed skin faces");
    }

    private static int faceMask(int metadata) {
        if (metadata == 10) return 60;
        int mask = 1 << 1;
        if (metadata <= 3) mask |= 1 << 2;
        if (metadata >= 7) mask |= 1 << 3;
        if ((metadata - 1) % 3 == 0) mask |= 1 << 4;
        if (metadata % 3 == 0) mask |= 1 << 5;
        return mask;
    }
}
