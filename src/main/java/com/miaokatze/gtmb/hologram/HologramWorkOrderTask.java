package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable bottom-up merge ordering. Each advance emits at most one index, including initial copying. */
final class HologramWorkOrderTask {

    private final List<Integer> result;
    private final Comparator<Integer> order;
    private List<Integer> source = new ArrayList<>(), target = new ArrayList<>();
    private int cursor, width = 1, start, left, right, middle, end, write;
    private boolean copying = true, merging, done;
    private long completedWork;
    private final long totalWork;

    HologramWorkOrderTask(List<Integer> result, Comparator<Integer> order) {
        this.result = result;
        this.order = order;
        long size = result.size(), total = 2 * size + 2;
        for (long run = 1; run < size; run *= 2) total += size + 2 * ((size + 2 * run - 1) / (2 * run)) + 1;
        totalWork = total;
    }

    boolean done() {
        return done;
    }

    long completedWork() {
        return completedWork;
    }

    long totalWork() {
        return totalWork;
    }

    void advance() {
        if (done) return;
        completedWork++;
        if (copying) {
            if (cursor < result.size()) {
                source.add(result.get(cursor++));
                target.add(null);
            } else {
                copying = false;
                cursor = 0;
            }
            return;
        }
        if (width >= source.size()) {
            if (cursor < source.size()) result.set(cursor, source.get(cursor++));
            else done = true;
            return;
        }
        if (!merging) {
            if (start >= source.size()) {
                List<Integer> swap = source;
                source = target;
                target = swap;
                start = 0;
                width = width > source.size() / 2 ? source.size() : width * 2;
                return;
            }
            left = start;
            write = start;
            middle = Math.min(source.size(), start + width);
            right = middle;
            end = (int) Math.min(source.size(), (long) middle + width);
            merging = true;
            return;
        }
        if (left < middle || right < end) {
            if (right >= end || left < middle && order.compare(source.get(left), source.get(right)) <= 0)
                target.set(write++, source.get(left++));
            else target.set(write++, source.get(right++));
        } else {
            start = end;
            merging = false;
        }
    }
}
