// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.miaovisonlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Background neighbor topology for detached, immutable region data. Publication and GL stay with the caller. */
public final class MiaoVisonLiteTopology<K, C, F> implements AutoCloseable {

    public static final int KNOWN = 1, VISIBLE = 2, OPAQUE = 4;

    private static final int[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };

    /** All methods use immutable inputs and may be called on the worker. */
    public interface View<C, F> {

        C at(int x, int y, int z);

        int x(C cell);

        int y(C cell);

        int z(C cell);

        int flags(C cell);

        /** Returns the projected-geometry input, or null if this visible cell adds no geometry. */
        F faceInput(C cell, int exposed, int sourceOrdinal);
    }

    public static final class Run<C, F> {

        public final boolean ordinary;
        public final int end;
        public final List<C> visible;
        public final List<F> faces;

        private Run(boolean ordinary, int end, List<C> visible, List<F> faces) {
            this.ordinary = ordinary;
            this.end = end;
            this.visible = Collections.unmodifiableList(visible);
            this.faces = Collections.unmodifiableList(faces);
        }
    }

    private final Map<K, List<Run<C, F>>> ready = new ConcurrentHashMap<>();
    private final FutureTask<Void> task;
    private final Consumer<FutureTask<Void>> remove;
    private volatile boolean closed;

    private MiaoVisonLiteTopology(Map<K, List<C>> regions, View<C, F> view, Consumer<FutureTask<Void>> remove) {
        this.remove = remove;
        task = new FutureTask<>(() -> {
            for (Map.Entry<K, List<C>> region : regions.entrySet()) {
                if (closed || Thread.currentThread()
                    .isInterrupted()) break;
                List<Run<C, F>> runs = prepareRegion(region.getValue(), view);
                if (closed) break;
                ready.put(region.getKey(), runs);
            }
            return null;
        });
    }

    /** The scheduler can decline work and leave the caller's normal render-thread cursor in charge. */
    public static <K, C, F> MiaoVisonLiteTopology<K, C, F> start(Map<K, List<C>> regions, View<C, F> view,
        Predicate<FutureTask<Void>> submit, Consumer<FutureTask<Void>> remove) {
        MiaoVisonLiteTopology<K, C, F> topology = new MiaoVisonLiteTopology<>(regions, view, remove);
        return submit.test(topology.task) ? topology : null;
    }

    public List<Run<C, F>> take(K region) {
        return closed ? null : ready.remove(region);
    }

    public boolean done() {
        return task.isDone();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        task.cancel(true);
        try {
            remove.accept(task);
        } finally {
            ready.clear();
        }
    }

    /** Admits ordinary cells in their original order. Unknown cells and their neighbors use the caller's cursor. */
    public static <C, F> List<Run<C, F>> prepareRegion(List<C> cells, View<C, F> view) {
        List<Run<C, F>> runs = new ArrayList<>();
        List<C> visible = Collections.emptyList();
        List<F> faces = Collections.emptyList();
        boolean ordinary = false;
        for (int i = 0; i < cells.size(); i++) {
            C cell = cells.get(i);
            int flags = view.flags(cell);
            int exposed = (flags & KNOWN) == 0 ? -1 : (flags & VISIBLE) != 0 ? exposed(cell, view) : 0;
            if (i == 0 || ordinary != (exposed >= 0)) {
                if (i != 0) runs.add(new Run<>(ordinary, i, visible, faces));
                ordinary = exposed >= 0;
                visible = ordinary ? new ArrayList<>() : Collections.emptyList();
                faces = ordinary ? new ArrayList<>() : Collections.emptyList();
            }
            if (!ordinary || (flags & VISIBLE) == 0) continue;
            visible.add(cell);
            F input = view.faceInput(cell, exposed, i + 1);
            if (input != null) faces.add(input);
        }
        if (!cells.isEmpty()) runs.add(new Run<>(ordinary, cells.size(), visible, faces));
        return Collections.unmodifiableList(runs);
    }

    private static <C, F> int exposed(C cell, View<C, F> view) {
        int exposed = 0;
        int x = view.x(cell), y = view.y(cell), z = view.z(cell);
        for (int side = 0; side < NORMALS.length; side++) {
            int[] normal = NORMALS[side];
            C neighbor = view.at(x + normal[0], y + normal[1], z + normal[2]);
            int flags = neighbor == null ? KNOWN : view.flags(neighbor);
            if ((flags & KNOWN) == 0) return -1;
            if ((flags & OPAQUE) == 0) exposed |= 1 << side;
        }
        return exposed;
    }
}
