package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Disjoint mask traversal. Each step visits one cell or skips one clipped empty chunk section. */
final class HologramAreaBlockCursor {

    interface Sections {

        /** Must reject missing chunks without loading them. */
        boolean empty(int chunkX, int sectionY, int chunkZ) throws IOException;
    }

    private final List<HologramAreaSelection.Region> boxes;
    private final long total;
    private int boxIndex, chunkX, chunkZ, sectionY;
    private int scanX, scanY, scanZ, left, bottom, back, right, top, front;
    private boolean sectionStarted;
    private long completed;
    int x, y, z;

    HologramAreaBlockCursor(List<HologramAreaSelection.Region> boxes) throws IOException {
        if (boxes.isEmpty()) throw new IOException("减选后没有可导入的区域");
        this.boxes = new ArrayList<>(boxes);
        long count = 0;
        for (HologramAreaSelection.Region box : boxes) count = Math.addExact(
            count,
            Math.multiplyExact(
                Math.multiplyExact((long) box.maxX - box.minX + 1, (long) box.maxY - box.minY + 1),
                (long) box.maxZ - box.minZ + 1));
        total = count;
        beginBox();
    }

    boolean done() {
        return boxIndex >= boxes.size();
    }

    long completed() {
        return completed;
    }

    long total() {
        return total;
    }

    /** False means a bounded empty-section skip, or completion; callers still charge the step to their quota. */
    boolean step(Sections sections) throws IOException {
        if (done()) return false;
        if (!sectionStarted) {
            HologramAreaSelection.Region box = boxes.get(boxIndex);
            left = Math.max(box.minX, chunkX << 4);
            right = Math.min(box.maxX, (chunkX << 4) + 15);
            back = Math.max(box.minZ, chunkZ << 4);
            front = Math.min(box.maxZ, (chunkZ << 4) + 15);
            bottom = Math.max(box.minY, sectionY << 4);
            top = Math.min(box.maxY, (sectionY << 4) + 15);
            if (sections.empty(chunkX, sectionY, chunkZ)) {
                completed += (long) (right - left + 1) * (top - bottom + 1) * (front - back + 1);
                nextSection();
                return false;
            }
            scanX = left;
            scanY = bottom;
            scanZ = back;
            sectionStarted = true;
        }
        x = scanX;
        y = scanY;
        z = scanZ;
        completed++;
        if (++scanX > right) {
            scanX = left;
            if (++scanZ > front) {
                scanZ = back;
                if (++scanY > top) nextSection();
            }
        }
        return true;
    }

    private void beginBox() {
        HologramAreaSelection.Region box = boxes.get(boxIndex);
        chunkX = box.minX >> 4;
        chunkZ = box.minZ >> 4;
        sectionY = box.minY >> 4;
        sectionStarted = false;
    }

    private void nextSection() {
        sectionStarted = false;
        HologramAreaSelection.Region box = boxes.get(boxIndex);
        if (++sectionY <= box.maxY >> 4) return;
        sectionY = box.minY >> 4;
        if (++chunkX <= box.maxX >> 4) return;
        chunkX = box.minX >> 4;
        if (++chunkZ <= box.maxZ >> 4) return;
        if (++boxIndex < boxes.size()) beginBox();
    }
}
