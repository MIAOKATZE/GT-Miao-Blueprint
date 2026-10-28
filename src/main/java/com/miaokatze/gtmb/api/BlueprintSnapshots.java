package com.miaokatze.gtmb.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

/** Detached snapshots for integration validation, analysis and namespaced processing reports. */
public final class BlueprintSnapshots {

    public enum Stage {
        CAPTURE_COMPLETE,
        AUTHORING_COMPLETE
    }

    public static final class Cell {

        public final int x, y, z, metadata;
        public final String block, role;
        public final boolean unknown, controller, excluded;
        private final NBTTagCompound tile, item;

        public Cell(int x, int y, int z, String block, int metadata, String role, boolean unknown, boolean controller,
            boolean excluded, NBTTagCompound tile, NBTTagCompound item) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block == null ? "" : block;
            this.metadata = metadata;
            this.role = role == null ? "" : role;
            this.unknown = unknown;
            this.controller = controller;
            this.excluded = excluded;
            this.tile = copy(tile);
            this.item = copy(item);
        }

        public NBTTagCompound tile() {
            return copy(tile);
        }

        public NBTTagCompound item() {
            return copy(item);
        }
    }

    public static final class Snapshot {

        public final Stage stage;
        public final String source;
        public final List<Cell> cells;

        public Snapshot(Stage stage, String source, List<Cell> cells) {
            if (stage == null || cells == null || cells.contains(null)) throw new IllegalArgumentException("snapshot");
            this.stage = stage;
            this.source = source == null ? "" : source;
            this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
        }
    }

    public interface Processor {

        /** Read only. Throw IOException to reject completion; returned data is a report, never an authorization. */
        NBTTagCompound inspect(Snapshot snapshot) throws IOException;
    }

    private static final class Entry {

        final Stage stage;
        final Processor processor;

        Entry(Stage stage, Processor processor) {
            this.stage = stage;
            this.processor = processor;
        }
    }

    private static final Map<String, Entry> PROCESSORS = new LinkedHashMap<>();

    private BlueprintSnapshots() {}

    public static GTMBExtensions.Registration register(String id, Stage stage, Processor processor) {
        if (stage == null || processor == null) throw new IllegalArgumentException("processor");
        return GTMBExtensions.install(id, "snapshot-processor", () -> {
            Entry entry = new Entry(stage, processor);
            synchronized (PROCESSORS) {
                PROCESSORS.put(id, entry);
            }
            return () -> {
                synchronized (PROCESSORS) {
                    PROCESSORS.remove(id, entry);
                }
            };
        });
    }

    public static boolean hasProcessors(Stage stage) {
        synchronized (PROCESSORS) {
            for (Entry entry : PROCESSORS.values()) if (entry.stage == stage) return true;
            return false;
        }
    }

    /** Explicit synchronous caller-thread inspection. Automatic capture/authoring invokes it on the server owner. */
    public static NBTTagCompound inspect(Snapshot snapshot) throws IOException {
        Map<String, Entry> entries;
        synchronized (PROCESSORS) {
            entries = new LinkedHashMap<>(PROCESSORS);
        }
        NBTTagCompound reports = new NBTTagCompound();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) if (entry.getValue().stage == snapshot.stage) {
            try {
                NBTTagCompound report = entry.getValue().processor.inspect(snapshot);
                if (report != null) reports.setTag(entry.getKey(), report.copy());
            } catch (IOException | RuntimeException | LinkageError failure) {
                throw new IOException("Snapshot processor rejected completion: " + entry.getKey(), failure);
            }
        }
        return reports;
    }

    private static NBTTagCompound copy(NBTTagCompound tag) {
        return tag == null ? null : (NBTTagCompound) tag.copy();
    }
}
