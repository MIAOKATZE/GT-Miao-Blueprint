package com.miaokatze.gtmb.hologram;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Conservative AE adjacency plus explicit quantum groups. Only changed connected components are rebuilt. */
final class HologramAnalysisComponents {

    private static final AtomicLong VERSIONS = new AtomicLong();
    final Map<Long, Node> nodes;
    final Map<Long, Component> membership;
    final Set<Long> affectedPages;
    private final Map<Long, Set<Long>> edges;
    private final long provider;

    static final class Node {

        final HologramSegmentDocument.Record input;
        final long id;
        final int x, y, z;
        final boolean ae, quantum, unknown;
        final String group, name;

        Node(HologramPresets.Cell cell, HologramSegmentDocument.Record input, boolean ae, boolean quantum, String group,
            String name) {
            this.input = input;
            id = cell.segmentCellId;
            x = cell.x;
            y = cell.y;
            z = cell.z;
            this.ae = ae;
            this.quantum = quantum;
            this.group = group;
            this.name = name;
            unknown = input == null || !input.coverageComplete();
        }

        boolean same(Node other) {
            return other != null && id == other.id
                && x == other.x
                && y == other.y
                && z == other.z
                && ae == other.ae
                && quantum == other.quantum
                && unknown == other.unknown
                && Objects.equals(group, other.group)
                && Objects.equals(name, other.name)
                && input.sameInput(other.input, HologramSegmentContract.Domain.RELATIONS);
        }
    }

    static final class Component {

        final long version = VERSIONS.incrementAndGet();
        final Set<Long> members;

        Component(Set<Long> members) {
            this.members = Collections.unmodifiableSet(members);
        }
    }

    HologramAnalysisComponents(HologramAnalysisComponents previous, Map<Long, Node> current) {
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(current));
        provider = HologramNativePlacement.generation();
        Map<Long, Set<Long>> edges = new HashMap<>();
        Map<String, Node> positions = new HashMap<>();
        Map<String, Long> groups = new HashMap<>();
        Long firstAe = null;
        boolean unknown = false;
        for (Node node : current.values()) {
            edges.put(node.id, new HashSet<>());
            if (node.ae) {
                positions.put(position(node.x, node.y, node.z), node);
                if (firstAe == null || node.id < firstAe) firstAe = node.id;
                unknown |= node.unknown;
            }
            if (node.quantum) groups.merge(node.group.isEmpty() ? "q1" : node.group, node.id, Math::min);
        }
        for (Node node : current.values()) {
            if (node.quantum) {
                String group = node.group.isEmpty() ? "q1" : node.group;
                connect(edges, groups.get(group), node.id);
            }
            if (!node.ae) continue;
            for (int[] offset : new int[][] { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 },
                { 0, 0, -1 } }) {
                Node neighbor = positions
                    .get(position((long) node.x + offset[0], (long) node.y + offset[1], (long) node.z + offset[2]));
                if (neighbor != null) connect(edges, node.id, neighbor.id);
            }
            // An undeclared network schema cannot claim page-local dependencies.
            if (unknown && firstAe != null) connect(edges, firstAe, node.id);
        }
        Map<Long, Set<Long>> frozenEdges = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : edges.entrySet())
            frozenEdges.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        this.edges = Collections.unmodifiableMap(frozenEdges);
        Set<Long> changed = new HashSet<>(current.keySet());
        if (previous != null) {
            changed.addAll(previous.nodes.keySet());
            changed.removeIf(
                id -> provider == previous.provider && current.containsKey(id)
                    && current.get(id)
                        .same(previous.nodes.get(id))
                    && Objects.equals(edges.get(id), previous.edges.get(id)));
        }
        Set<Long> affected = new HashSet<>(changed);
        Set<Long> affectedPages = new HashSet<>();
        if (previous != null) for (long id : changed) {
            Component component = previous.membership.get(id);
            if (component != null) affected.addAll(component.members);
        }
        for (long id : affected) affectedPages.add((id - 1) / HologramSegmentContract.PAGE_SIZE);
        Map<Long, Component> membership = new HashMap<>();
        if (previous != null) for (Map.Entry<Long, Component> entry : previous.membership.entrySet())
            if (!affected.contains(entry.getKey()) && current.containsKey(entry.getKey()))
                membership.put(entry.getKey(), entry.getValue());
        for (long start : affected) {
            if (!current.containsKey(start) || membership.containsKey(start)) continue;
            Set<Long> members = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                long id = queue.removeFirst();
                if (!members.add(id)) continue;
                queue.addAll(edges.get(id));
            }
            Component component = new Component(members);
            for (long id : members) {
                membership.put(id, component);
                affectedPages.add((id - 1) / HologramSegmentContract.PAGE_SIZE);
            }
        }
        this.membership = Collections.unmodifiableMap(membership);
        this.affectedPages = Collections.unmodifiableSet(affectedPages);
    }

    boolean sameComponents(HologramAnalysisComponents previous) {
        if (previous == null || membership.size() != previous.membership.size()) return false;
        for (Map.Entry<Long, Component> entry : membership.entrySet())
            if (previous.membership.get(entry.getKey()) != entry.getValue()) return false;
        return true;
    }

    private static void connect(Map<Long, Set<Long>> edges, long left, long right) {
        if (left == right) return;
        edges.get(left)
            .add(right);
        edges.get(right)
            .add(left);
    }

    private static String position(long x, long y, long z) {
        return x + "," + y + "," + z;
    }
}
