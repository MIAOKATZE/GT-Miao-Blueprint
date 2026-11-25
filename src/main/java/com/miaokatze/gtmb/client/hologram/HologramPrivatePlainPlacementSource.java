// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramPreviewPose;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Cursor;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Evidence;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Plain;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Source;

/** Client-thread binding to the exact confirmed private input; worker traversal reads only the existing display index. */
final class HologramPrivatePlainPlacementSource implements Source {

    private static final int OFFSET = HologramProjectedLayerEncoder.MAX_OFFSET;
    private static final long MAX_CELL_PROBES = 12_000_000;
    private final Evidence evidence;
    private final HologramRegionCache regions;
    private final int[] anchor, localLow, localHigh, low, high;
    private final int[] worldAxis = new int[3], sign = new int[3];
    private final int sourceXAxis;
    private final BooleanSupplier current;

    /**
     * Call only after consuming the existing material-confirmation ticket. Evidence must be the unchanged capture's
     * own, built at its compact reader and not synthesized from a loaded renderer or material forecast.
     */
    static HologramPrivatePlainPlacementSource bind(HologramLocalPlacement.Submission confirmed, Minecraft client,
        BooleanSupplier stillOwned) throws IOException {
        if (confirmed == null || client == null || client.theWorld == null
            || !confirmed.current(HologramLocalPlacement.INSTANCE, client)) throw unsupported();
        HologramLibraryClient.DisplaySource captured = confirmed.source();
        HologramState displayed = confirmed.display();
        Evidence evidence = captured == null ? null : captured.plainPlacementEvidence();
        if (captured == null || captured.draft != null || captured.json == null || !captured.compactSource
            || !captured.publicId.isEmpty() || !captured.key.equals("local:" + captured.localId)
            || !captured.localId.matches("local_[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
            || !captured.capturedIndexedMaterialSource() || evidence == null || !evidence.sameCapture(captured.json)
            || captured.state == null || displayed == null || displayed.cells != captured.state.cells
            || displayed.regions != captured.state.regions || captured.state.data.getBoolean("displayPartial")
            || displayed.data.getBoolean("displayPartial")
            || displayed.cells.size() != evidence.cells()) throw unsupported();
        NBTTagCompound frozen = confirmed.intent();
        if (!frozen.hasKey("x", 3) || !frozen.hasKey("y", 3) || !frozen.hasKey("z", 3)
            || !frozen.hasKey("previewPose", 11)) throw unsupported();
        return new HologramPrivatePlainPlacementSource(
            evidence,
            captured.json,
            displayed.regions,
            new int[] { frozen.getInteger("x"), frozen.getInteger("y"), frozen.getInteger("z") },
            frozen.getIntArray("previewPose"),
            client.theWorld.getHeight(),
            stillOwned);
    }

    /** Inputs below this boundary are immutable; available to the pure synthetic cursor fixture. */
    HologramPrivatePlainPlacementSource(Evidence evidence, byte[] exact, HologramRegionCache regions, int[] anchor,
        int[] recipe, int worldHeight, BooleanSupplier current) throws IOException {
        if (evidence == null || !evidence.sameCapture(exact) || regions == null || anchor == null
            || anchor.length != 3 || recipe == null || recipe.length > HologramPreviewPose.MAX_RECIPE_LENGTH
            || worldHeight < 1 || worldHeight > 4096 || current == null) throw unsupported();
        this.evidence = evidence;
        this.regions = regions;
        this.anchor = anchor.clone();
        this.current = current;
        HologramPreviewPose pose = new HologramPreviewPose();
        for (int turn : recipe) {
            if (turn != 0 && turn != 1) throw unsupported();
            pose = pose.turn(turn == 1);
        }
        int[] extents = regions.bounds();
        localLow = new int[] { extents[0], extents[1], extents[2] };
        localHigh = new int[] { extents[3], extents[4], extents[5] };
        low = new int[3];
        high = new int[3];
        int xAxis = -1;
        for (int axis = 0; axis < 3; axis++) {
            int[] basis = pose.apply(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
            int out = basis[0] != 0 ? 0 : basis[1] != 0 ? 1 : 2;
            int direction = basis[out];
            if (localLow[axis] < -OFFSET || localHigh[axis] > OFFSET || localLow[axis] > localHigh[axis])
                throw unsupported();
            worldAxis[axis] = out;
            sign[axis] = direction;
            if (out == 0) xAxis = axis;
            low[out] = direction == 1 ? localLow[axis] : -localHigh[axis];
            high[out] = direction == 1 ? localHigh[axis] : -localLow[axis];
        }
        sourceXAxis = xAxis;
        if (xAxis < 0 || !worldHorizontal(this.anchor[0], low[0], high[0])
            || !worldHorizontal(this.anchor[2], low[2], high[2])
            || !worldHeight(this.anchor[1], low[1], high[1], worldHeight)) throw unsupported();
        long volume = ((long) high[0] - low[0] + 1) * (high[1] - low[1] + 1) * (high[2] - low[2] + 1);
        long coarse = ((long) ((localHigh[0] >> 4) - (localLow[0] >> 4) + 1))
            * ((localHigh[1] >> 4) - (localLow[1] >> 4) + 1) * ((localHigh[2] >> 4) - (localLow[2] >> 4) + 1);
        // Bound worst candidate work before accepting a pathological sparse range. Never allocate volume-sized state.
        long occupied = Math.min(volume, (long) regions.regions.size() * 4096);
        long emptyChunkProbes = coarse * 16L * 16L;
        if (occupied + emptyChunkProbes > MAX_CELL_PROBES) throw unsupported();
    }

    int anchor(int axis) {
        return anchor[axis];
    }

    @Override
    public int cells() {
        return evidence.cells();
    }

    @Override
    public int count(Plain kind) {
        return evidence.count(kind);
    }

    @Override
    public Cursor open(boolean reverse) throws IOException {
        checkCurrent();
        return new Walk(reverse, new Occupancy());
    }

    private static boolean worldHorizontal(int anchor, int low, int high) {
        return anchor >= -30_000_000 && anchor < 30_000_000
            && (long) anchor + low >= -30_000_000L && (long) anchor + high < 30_000_000L;
    }

    private static boolean worldHeight(int anchor, int low, int high, int height) {
        return anchor >= 0 && anchor < height && (long) anchor + low >= 0 && (long) anchor + high < height;
    }

    private void checkCurrent() throws InterruptedIOException {
        if (!current.getAsBoolean()) throw new InterruptedIOException("分层施工准备已取消");
    }

    /** Coarse 16-cube existence only, at most 33³ booleans. This stores neither row positions nor material variants. */
    private final class Occupancy {

        private final int ox = localLow[0] >> 4, oy = localLow[1] >> 4, oz = localLow[2] >> 4;
        private final int width = (localHigh[0] >> 4) - ox + 1, height = (localHigh[1] >> 4) - oy + 1,
            depth = (localHigh[2] >> 4) - oz + 1;
        private final boolean[] occupied = new boolean[width * height * depth];

        Occupancy() throws IOException {
            int visited = 0;
            for (HologramRegionCache.Key key : regions.regions.keySet()) {
                if ((visited++ & 255) == 0) checkCurrent();
                if (key.x < ox || key.y < oy || key.z < oz || key.x - ox >= width || key.y - oy >= height
                    || key.z - oz >= depth) throw unsupported();
                occupied[index(key.x, key.y, key.z)] = true;
            }
        }

        private int index(int rx, int ry, int rz) {
            return (((ry - oy) * depth + rz - oz) * width) + rx - ox;
        }

        boolean at(int x, int y, int z) {
            return occupied[index(x >> 4, y >> 4, z >> 4)];
        }
    }

    private final class Walk implements Cursor {

        private final int step;
        private final Occupancy occupied;
        private final int[] first, last;
        private int x, y, z, visits, answerX, answerY, answerZ;
        private Plain kind;
        private boolean end;

        Walk(boolean reverse, Occupancy occupied) {
            step = reverse ? -1 : 1;
            first = reverse ? high : low;
            last = reverse ? low : high;
            x = first[0];
            y = first[1];
            z = first[2];
            this.occupied = occupied;
        }

        private void advanceX(int amount) {
            x += step * amount;
            if ((x - last[0]) * step <= 0) return;
            x = first[0];
            z += step;
            if (z != last[2] + step) return;
            z = first[2];
            y += step;
            end = y == last[1] + step;
        }

        private int local(int axis) {
            return sign[axis] * switch (worldAxis[axis]) {
                case 0 -> x;
                case 1 -> y;
                default -> z;
            };
        }

        @Override
        public boolean next() throws IOException {
            checkCurrent();
            while (!end) {
                if (++visits > MAX_CELL_PROBES) throw unsupported();
                if ((visits & 255) == 0) checkCurrent();
                int lx = local(0), ly = local(1), lz = local(2);
                if (!occupied.at(lx, ly, lz)) {
                    int along = sourceXAxis == 0 ? lx : sourceXAxis == 1 ? ly : lz;
                    advanceX(sign[sourceXAxis] * step > 0 ? 16 - (along & 15) : (along & 15) + 1);
                    continue;
                }
                HologramState.Cell row = regions.at(lx, ly, lz);
                if (row == null) {
                    advanceX(1);
                    continue;
                }
                kind = Plain.find(row.wantId);
                if (kind == null || row.x != lx || row.y != ly || row.z != lz || row.wantMeta != 0
                    || row.targetFacing != -1 || row.excluded || row.anchor || row.iconOnly || row.missingBlock
                    || row.hasDisplayTag(false, "nativeTile", 10)) throw unsupported();
                answerX = anchor[0] + x;
                answerY = anchor[1] + y;
                answerZ = anchor[2] + z;
                advanceX(1);
                return true;
            }
            return false;
        }

        @Override
        public int x() {
            return answerX;
        }

        @Override
        public int y() {
            return answerY;
        }

        @Override
        public int z() {
            return answerZ;
        }

        @Override
        public Plain kind() {
            return kind;
        }
    }

    private static IOException unsupported() {
        return new IOException("当前本地蓝图、保存来源或范围不支持普通分层施工，未开始。");
    }
}
