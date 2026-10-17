package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Prepared incrementally alongside projection; changed list entries fall back to authoritative declaration scans. */
final class HologramStructureIndex {

    private final HologramPresets.Preset preset;
    private final List<HologramPresets.Structure> structures;
    private final Map<String, Entry> entries = new HashMap<>();
    private int count;
    private boolean duplicate;

    HologramStructureIndex(HologramPresets.Preset preset) {
        this.preset = preset;
        structures = preset.structures;
    }

    void add(int index) {
        HologramPresets.Structure structure = structures.get(index);
        duplicate |= entries.put(structure.id, new Entry(index, structure)) != null;
        count++;
    }

    /** Null requests the original scan, including malformed/missing memberships and externally replaced lists. */
    Boolean enabled(HologramPresets.Preset current, List<String> memberships, String active) {
        if (duplicate || current != preset || current.structures != structures || count != structures.size())
            return null;
        boolean found = false, enabled = false;
        for (String id : memberships) {
            if (!active.isEmpty() && !active.equals(id)) continue;
            Entry entry = entries.get(id);
            if (entry == null || structures.get(entry.index) != entry.structure || !id.equals(entry.structure.id))
                return null;
            found = true;
            enabled |= entry.structure.enabled;
        }
        return active.isEmpty() ? !found || enabled : found && enabled;
    }

    private static final class Entry {

        final int index;
        final HologramPresets.Structure structure;

        Entry(int index, HologramPresets.Structure structure) {
            this.index = index;
            this.structure = structure;
        }
    }
}
