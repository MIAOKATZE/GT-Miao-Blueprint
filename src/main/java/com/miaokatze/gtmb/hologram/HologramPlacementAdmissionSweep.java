// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** Bounded, ordered work staging only. The caller owns the scope, world proof, payment, and final job admission. */
final class HologramPlacementAdmissionSweep {

    enum Progress { PENDING, COMPLETE, RETIRED }

    @FunctionalInterface
    interface CellCheck {

        boolean verify(int index) throws IOException;
    }

    static final class Slice {

        private final long deadline;
        private final LongSupplier clock;
        private int remaining;

        Slice(int maxRows, long deadline, LongSupplier clock) {
            this.remaining = Math.max(0, maxRows);
            this.deadline = deadline;
            this.clock = clock;
        }

        private boolean take() {
            if (remaining == 0 || clock.getAsLong() >= deadline) return false;
            remaining--;
            return true;
        }
    }

    private final int cellCount;
    private final TreeMap<Long, Integer> ordered = new TreeMap<>();
    private int cursor;
    private boolean retired;
    private Iterator<Integer> work;

    HologramPlacementAdmissionSweep(int cellCount) {
        this.cellCount = cellCount;
    }

    Progress inspect(Slice slice, CellCheck check) throws IOException {
        if (retired) return Progress.RETIRED;
        try {
            while (cursor < cellCount && slice.take()) {
                if (!check.verify(cursor++)) {
                    retire();
                    return Progress.RETIRED;
                }
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            retire();
            throw failure;
        }
        return cursor == cellCount ? Progress.COMPLETE : Progress.PENDING;
    }

    private void retire() {
        retired = true;
        ordered.clear();
    }

    void stage(int index, int x, int y, int z) {
        if (retired || work != null) throw new IllegalStateException("Placement staging already closed");
        long order = ((long) y << 52) | ((long) (z + 30_000_000) << 26) | (x + 30_000_000L);
        ordered.put(order, index);
    }

    boolean isEmpty() {
        return ordered.isEmpty();
    }

    boolean drain(Slice slice, IntConsumer accept) {
        if (retired || cursor != cellCount) return false;
        if (work == null) work = ordered.values().iterator();
        while (work.hasNext() && slice.take()) accept.accept(work.next());
        return !work.hasNext();
    }
}
