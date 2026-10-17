package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;

/** Bounded client intent queue; resolves positions again after each server acknowledgment. */
final class HologramEditQueue {

    static final int LIMIT = 8;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    boolean offer(String action, String session, HologramState.Cell cell) {
        return offer(action, session, cell, java.util.Collections.emptyList());
    }

    boolean offer(String action, String session, HologramState.Cell cell, java.util.List<HologramState.Cell> selected) {
        if (entries.size() >= LIMIT) return false;
        entries.addLast(new Entry(action, session, cell, selected));
        return true;
    }

    Entry removeFirst(HologramState snapshot) {
        Entry entry = entries.removeFirst();
        if (entry.selectionIn(snapshot) == -2 || entry.indicesIn(snapshot) == null) {
            clear();
            return null;
        }
        return entry;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
    }

    static final class Entry {

        final String action, session;
        final boolean hasCell;
        final int x, y, z;
        private final int[] positions;

        Entry(String action, String session, HologramState.Cell cell) {
            this(action, session, cell, java.util.Collections.emptyList());
        }

        Entry(String action, String session, HologramState.Cell cell, java.util.List<HologramState.Cell> selected) {
            this.action = action;
            this.session = session;
            hasCell = cell != null;
            x = cell == null ? 0 : cell.x;
            y = cell == null ? 0 : cell.y;
            z = cell == null ? 0 : cell.z;
            positions = new int[selected.size() * 3];
            for (int i = 0; i < selected.size(); i++) {
                HologramState.Cell member = selected.get(i);
                positions[i * 3] = member.x;
                positions[i * 3 + 1] = member.y;
                positions[i * 3 + 2] = member.z;
            }
        }

        int[] indicesIn(HologramState snapshot) {
            if (!session.equals(snapshot.session)) return null;
            int[] result = new int[positions.length / 3];
            for (int i = 0; i < result.length; i++) {
                HologramState.Cell cell = snapshot.regions
                    .at(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
                if (cell == null) return null;
                result[i] = cell.index;
            }
            return result;
        }

        int selectionIn(HologramState snapshot) {
            if (!session.equals(snapshot.session)) return -2;
            if (!hasCell) return -1;
            HologramState.Cell cell = snapshot.regions.at(x, y, z);
            return cell == null ? -2 : cell.index;
        }
    }
}
