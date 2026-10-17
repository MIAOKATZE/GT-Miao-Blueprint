package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Allocation must solve restricted-role collisions atomically rather than greedily truncating a combination. */
public final class HologramHatchProfilesRegression {

    private HologramHatchProfilesRegression() {}

    public static void run() {
        groupedCapacityAndWorkBudgets();
        minimumCostMatchesExhaustiveSmallAssignments();
        int[] assignment = HologramHatchProfiles
            .assign(Arrays.asList(Arrays.asList(0, 1), Collections.singletonList(0)), 2);
        SimpleAssert.that(
            assignment != null && assignment[0] == 1 && assignment[1] == 0,
            "a flexible first role moves aside for the second role's only legal slot");
        assignment = HologramHatchProfiles
            .assign(Arrays.asList(Arrays.asList(0, 1), Arrays.asList(1, 2), Collections.singletonList(0)), 3);
        SimpleAssert.that(
            assignment != null && assignment[0] == 2 && assignment[1] == 0 && assignment[2] == 1,
            "a chained reassignment preserves the complete mixed-role combination");
        SimpleAssert.eq(
            null,
            HologramHatchProfiles.assign(Arrays.asList(Collections.singletonList(0), Collections.singletonList(0)), 2),
            "two machines cannot share one declared slot");
        SimpleAssert.eq(
            null,
            HologramHatchProfiles
                .assign(Arrays.asList(Collections.singletonList(0), Collections.<Integer>emptyList()), 2),
            "an unsupported role rejects the entire combination");
        assignment = HologramHatchProfiles
            .assign(Collections.singletonList(Arrays.asList(0, 1)), 2, new int[] { 1, 0 });
        SimpleAssert.that(
            assignment != null && assignment[0] == -1 && assignment[1] == 0,
            "a hatch-only slot preserves the remaining mandatory casing even when the dual-use casing slot is listed first");
        assignment = HologramHatchProfiles.assign(
            Arrays.asList(Arrays.asList(0, 1), Arrays.asList(1, 2), Arrays.asList(0, 3)),
            4,
            new int[] { 0, 0, 0, 1 });
        SimpleAssert.that(
            assignment != null && assignment[0] == 2 && assignment[1] == 0 && assignment[2] == 1 && assignment[3] == -1,
            "a residual reassignment chain frees the scarce zero-cost slot instead of consuming the last casing");
    }

    private static void groupedCapacityAndWorkBudgets() {
        List<Integer> shared = new ArrayList<>();
        for (int slot = 0; slot < 512; slot++) shared.add(slot);
        List<List<Integer>> repeated = new ArrayList<>();
        for (int unit = 0; unit < 512; unit++) repeated.add(shared);
        HologramHatchProfiles.Budget budget = new HologramHatchProfiles.Budget(1000, 512, 5_000_000);
        int[] owners = HologramHatchProfiles.assign(repeated, 512, new int[512], budget);
        SimpleAssert.that(owners != null, "a capacity group assigns all 512 identical machines");
        SimpleAssert.eq(
            512,
            budget.graphEdges,
            "duplicate quantities share one group-to-slot edge rather than a 512-by-512 graph");
        SimpleAssert
            .eq(512, budget.edgeScans, "a single interchangeable group selects zero-cost slots in one bounded pass");
        boolean[] assigned = new boolean[512];
        for (int owner : owners) {
            SimpleAssert
                .that(owner >= 0 && !assigned[owner], "group capacity restores each original machine identity once");
            assigned[owner] = true;
        }
        List<List<Integer>> equalCopies = Arrays.asList(Arrays.asList(0, 1), Arrays.asList(0, 1));
        budget = new HologramHatchProfiles.Budget(1000, 2, 1000);
        SimpleAssert.that(
            HologramHatchProfiles.assign(equalCopies, 2, new int[2], budget) != null,
            "equal candidate lists share capacity even when they are not the same Java list");
        SimpleAssert.eq(2, budget.graphEdges, "content-equivalent entries use a single candidate edge set");

        budget = new HologramHatchProfiles.Budget(10, 2, 1000);
        HologramHatchProfiles.LimitKind reached = null;
        try {
            HologramHatchProfiles.assign(Collections.singletonList(Arrays.asList(0, 1, 2)), 3, new int[3], budget);
        } catch (HologramHatchProfiles.WorkLimitException limit) {
            reached = limit.kind;
        }
        SimpleAssert.eq(
            HologramHatchProfiles.LimitKind.GRAPH_EDGES,
            reached,
            "a graph budget is reported distinctly from a structurally impossible combination");
        SimpleAssert.eq(
            3,
            budget.graphEdges,
            "graph construction stops at the first edge beyond its budget before residual allocation");
        budget = new HologramHatchProfiles.Budget(10, 10, 1);
        reached = null;
        try {
            HologramHatchProfiles.assign(equalCopies, 2, new int[2], budget);
        } catch (HologramHatchProfiles.WorkLimitException limit) {
            reached = limit.kind;
        }
        SimpleAssert.eq(
            HologramHatchProfiles.LimitKind.EDGE_SCANS,
            reached,
            "path scanning terminates at its explicit work budget rather than continuing a long server tick");
        SimpleAssert.eq(2, budget.edgeScans, "the scan guard also bounds the single-group fast path immediately");
        SimpleAssert
            .eq(Arrays.asList(0, 1), equalCopies.get(0), "a failed bounded solve cannot mutate caller-owned choices");
        budget = new HologramHatchProfiles.Budget(10, 10, 1000);
        reached = null;
        try {
            for (int index = 0; index < 1000; index++) budget.evaluate();
        } catch (HologramHatchProfiles.WorkLimitException limit) {
            reached = limit.kind;
        }
        SimpleAssert.eq(
            HologramHatchProfiles.LimitKind.CANDIDATES,
            reached,
            "candidate evaluation has a separate explicit budget before graph construction");
        SimpleAssert.eq(11, budget.evaluations, "candidate work stops at the first excess evaluation");
    }

    private static void minimumCostMatchesExhaustiveSmallAssignments() {
        for (int graph = 0; graph < 256; graph++) {
            List<List<Integer>> choices = new ArrayList<>();
            for (int unit = 0; unit < 2; unit++) {
                List<Integer> legal = new ArrayList<>();
                for (int slot = 0; slot < 4; slot++) if ((graph & 1 << (unit * 4 + slot)) != 0) legal.add(slot);
                choices.add(legal);
            }
            for (int casingMask = 0; casingMask < 16; casingMask++) {
                int[] costs = new int[4];
                for (int slot = 0; slot < 4; slot++) costs[slot] = casingMask >> slot & 1;
                int expected = Integer.MAX_VALUE;
                // Independent exhaustive oracle: enumerate each legal pair rather than residual graph paths.
                for (int first : choices.get(0)) for (int second : choices.get(1))
                    if (first != second) expected = Math.min(expected, costs[first] + costs[second]);
                int[] assignment = HologramHatchProfiles.assign(choices, 4, costs);
                if (expected == Integer.MAX_VALUE) {
                    SimpleAssert.eq(null, assignment, "an impossible whole combination cannot return a partial result");
                    continue;
                }
                SimpleAssert
                    .that(assignment != null, "every feasible small declaration graph has a complete assignment");
                boolean[] matched = new boolean[2];
                int actual = 0;
                for (int slot = 0; slot < assignment.length; slot++) if (assignment[slot] >= 0) {
                    int unit = assignment[slot];
                    SimpleAssert.that(
                        !matched[unit] && choices.get(unit)
                            .contains(slot),
                        "each required role uses one of its own declared slots exactly once");
                    matched[unit] = true;
                    actual += costs[slot];
                }
                SimpleAssert.that(matched[0] && matched[1], "a returned assignment contains the entire combination");
                SimpleAssert.eq(expected, actual, "matching consumes the global minimum number of casing slots");
            }
        }
    }
}
