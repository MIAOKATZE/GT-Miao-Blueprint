// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintCodec.CompactLayout;
import com.miaokatze.gtmb.hologram.HologramPresets.Cell;

/** The validated initial authoring rows; no mutable DTO list is retained. */
public final class HologramPackedAuthoring {

    private final CompactLayout layout;

    private HologramPackedAuthoring(CompactLayout layout) {
        this.layout = layout;
    }

    static HologramPackedAuthoring retain(CompactLayout layout, boolean enabled, boolean admittedView, String fileId,
        BooleanSupplier current) throws IOException {
        // A validated named import may retain its local rows before receiving a library ID.
        // This root grants authoring access only; placement still requires an exact persisted private source.
        if (!enabled || !admittedView || layout == null) return null;
        if (!current.getAsBoolean()) throw new IOException("蓝图读取已取消");
        return new HologramPackedAuthoring(layout);
    }

    CompactLayout layout() {
        return layout;
    }

    int size() {
        return layout.rows()
            .cells();
    }

    Cell read(int ordinal) {
        return HologramLocalBlueprintCodec.rowCopy(layout, ordinal);
    }

    /** Opaque worker result. Only this exact root may expose its converted list to the document owner. */
    public static final class Materialization {

        private final HologramPackedAuthoring root;
        private final long revision;
        private final List<Cell> cells;

        private Materialization(HologramPackedAuthoring root, long revision, List<Cell> cells) {
            this.root = root;
            this.revision = revision;
            this.cells = cells;
        }
    }

    Materialization prepare(HologramLocalBlueprintCodec codec, long revision, BooleanSupplier current)
        throws IOException {
        if (!current.getAsBoolean()) throw stale();
        List<Cell> cells = codec.detachedCells(layout, current);
        if (!current.getAsBoolean()) throw stale();
        return new Materialization(this, revision, cells);
    }

    List<Cell> accept(Materialization prepared, long revision, BooleanSupplier current) throws IOException {
        if (prepared == null || prepared.root != this || prepared.revision != revision || !current.getAsBoolean())
            throw stale();
        return prepared.cells;
    }

    static IOException stale() {
        return new IOException("蓝图已修改，请重新选择编辑目标");
    }
}
