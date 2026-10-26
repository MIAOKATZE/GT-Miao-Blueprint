package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Operation-owned relationship index. A staged replacement changes both directions before publication. */
final class HologramRelationIndex {

    static final class Source {

        final int x, y, z;
        final String payload;

        Source(int x, int y, int z, String payload) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.payload = payload;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z, payload);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Source)) return false;
            Source source = (Source) other;
            return x == source.x && y == source.y && z == source.z && Objects.equals(payload, source.payload);
        }
    }

    private final Map<Source, List<HologramCoordinatePolicies.Relation>> sources = new HashMap<>();
    private final Map<Target, Set<Source>> targets = new HashMap<>();
    private final Set<Target> ownedTargets = new java.util.HashSet<>();
    private int size;

    HologramRelationIndex(List<HologramCoordinatePolicies.Relation> relations) {
        for (HologramCoordinatePolicies.Relation relation : relations) {
            Source source = source(relation);
            sources.computeIfAbsent(source, ignored -> new ArrayList<>())
                .add(relation);
            addTarget(source, relation);
            size++;
        }
    }

    HologramRelationIndex copy() {
        HologramRelationIndex copy = new HologramRelationIndex(Collections.emptyList());
        copy.sources.putAll(sources);
        copy.targets.putAll(targets);
        copy.size = size;
        ownedTargets.clear();
        return copy;
    }

    Set<Source> sources() {
        return Collections.unmodifiableSet(sources.keySet());
    }

    boolean same(Source source, List<HologramCoordinatePolicies.Relation> candidates) {
        List<HologramCoordinatePolicies.Relation> existing = sources.get(source);
        if (existing == null) return candidates.isEmpty();
        if (existing.size() != candidates.size()) return false;
        for (int i = 0; i < existing.size(); i++) if (!same(existing.get(i), candidates.get(i))) return false;
        return true;
    }

    List<HologramCoordinatePolicies.Relation> relations(int x, int y, int z, String payload) {
        List<HologramCoordinatePolicies.Relation> found = sources.get(new Source(x, y, z, payload));
        return found == null ? Collections.emptyList() : Collections.unmodifiableList(found);
    }

    Set<Source> sourcesTargeting(int dimension, int x, int y, int z) {
        Set<Source> found = targets.get(new Target(dimension, x, y, z));
        return found == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(found));
    }

    /** Caller owns this staged index; failed/cancelled edits discard it rather than publishing half a delta. */
    void replace(Source source, List<HologramCoordinatePolicies.Relation> replacements) {
        for (HologramCoordinatePolicies.Relation relation : replacements)
            if (!source.equals(source(relation))) throw new IllegalArgumentException("Relationship source mismatch");
        List<HologramCoordinatePolicies.Relation> previous = sources.remove(source);
        if (previous != null) for (HologramCoordinatePolicies.Relation relation : previous) {
            Target target = target(relation);
            Set<Source> references = mutableTarget(target, false);
            if (references != null) {
                references.remove(source);
                if (references.isEmpty()) targets.remove(target);
                else targets.put(target, references);
            }
            size--;
        }
        if (!replacements.isEmpty()) sources.put(source, new ArrayList<>(replacements));
        for (HologramCoordinatePolicies.Relation relation : replacements) {
            addTarget(source, relation);
            size++;
        }
    }

    int size() {
        return size;
    }

    private void addTarget(Source source, HologramCoordinatePolicies.Relation relation) {
        if (!"clear".equals(relation.previousMode)) {
            Target target = target(relation);
            mutableTarget(target, true).add(source);
        }
    }

    private Set<Source> mutableTarget(Target target, boolean create) {
        Set<Source> references = targets.get(target);
        if (references == null && !create) return null;
        if (ownedTargets.add(target) || references == null) {
            references = references == null ? new LinkedHashSet<>() : new LinkedHashSet<>(references);
            targets.put(target, references);
        }
        return references;
    }

    private static boolean same(HologramCoordinatePolicies.Relation left, HologramCoordinatePolicies.Relation right) {
        return Objects.equals(left.id, right.id) && Objects.equals(left.label, right.label)
            && Objects.equals(left.adapter, right.adapter)
            && Objects.equals(left.payload, right.payload)
            && Objects.equals(left.previousMode, right.previousMode)
            && left.x == right.x
            && left.y == right.y
            && left.z == right.z
            && left.slot == right.slot
            && left.targetX == right.targetX
            && left.targetY == right.targetY
            && left.targetZ == right.targetZ
            && left.targetDimension == right.targetDimension
            && left.dx == right.dx
            && left.dy == right.dy
            && left.dz == right.dz
            && left.internal == right.internal;
    }

    private static Source source(HologramCoordinatePolicies.Relation relation) {
        return new Source(relation.x, relation.y, relation.z, relation.payload);
    }

    private static Target target(HologramCoordinatePolicies.Relation relation) {
        return new Target(relation.targetDimension, relation.targetX, relation.targetY, relation.targetZ);
    }

    private static final class Target {

        final int dimension, x, y, z;

        Target(int dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimension, x, y, z);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Target)) return false;
            Target target = (Target) other;
            return dimension == target.dimension && x == target.x && y == target.y && z == target.z;
        }
    }
}
