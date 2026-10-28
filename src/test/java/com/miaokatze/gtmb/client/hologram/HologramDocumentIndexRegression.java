package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Pure-Java persistent render-directory oracle; runnable without Minecraft, GL or Gradle. */
public final class HologramDocumentIndexRegression {

    private HologramDocumentIndexRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("HologramDocumentIndexRegression OK");
    }

    public static void run() {
        HologramDocumentIndex<Integer, Integer> index = new HologramDocumentIndex<>(
            Integer::compare,
            value -> new int[] { value, value, value, value, value, value });
        TreeMap<Integer, Integer> oracle = new TreeMap<>();
        Random random = new Random(7191701);
        for (int i = 0; i < 12000; i++) {
            int key = random.nextInt(3000);
            Integer value = random.nextBoolean() ? random.nextInt(100000) : null;
            HologramDocumentIndex<Integer, Integer> previous = index;
            Integer prior = index.get(key);
            index = index.with(key, value);
            if (value == null) oracle.remove(key);
            else oracle.put(key, value);
            check(java.util.Objects.equals(previous.get(key), prior), "old directory was mutated");
            check(index.size() == oracle.size(), "directory size disagrees");
            check(index.copiedNodes < 80, "point update copied an unbounded directory");
            if ((i & 63) == 0) {
                check(
                    new ArrayList<>(index.entrySet()).equals(new ArrayList<>(oracle.entrySet())),
                    "ordered entries disagree");
                int rank = 0;
                for (Map.Entry<Integer, Integer> entry : oracle.entrySet()) {
                    check(
                        index.get(entry.getKey())
                            .equals(entry.getValue()),
                        "lookup disagrees");
                    check(
                        index.at(rank++)
                            .equals(entry.getValue()),
                        "rank disagrees");
                }
                if (!oracle.isEmpty()) {
                    int min = java.util.Collections.min(oracle.values()),
                        max = java.util.Collections.max(oracle.values());
                    int[] bounds = index.bounds();
                    check(bounds[0] == min && bounds[3] == max, "incremental bounds disagree");
                }
            }
        }
        HologramDocumentIndex<Integer, Integer> ordered = new HologramDocumentIndex<>(Integer::compare);
        for (int i = 0; i < 65536; i++) ordered = ordered.with(i, i);
        HologramDocumentIndex<Integer, Integer> changed = ordered.with(32768, -1);
        check(changed.copiedNodes < 40, "65K directory point edit copies only an AVL path");
        check(ordered.get(32768) == 32768, "retained root was mutated");
        for (int i = 0; i < 65536; i++) changed = changed.with(i, null);
        check(changed.isEmpty(), "deletions leave obsolete directory nodes reachable");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
