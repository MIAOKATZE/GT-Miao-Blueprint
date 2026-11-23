// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Primitive bounded frame gaps; analysis is deferred until the edit's fresh full END. */
final class HologramGiantWriteFrames {

    private final long[] before = new long[30], during = new long[2048];
    private long last;
    private int countBefore, countDuring;
    private boolean active;
    private long overflow;

    void resetBaseline() {
        countBefore = 0;
        last = 0;
    }

    boolean hasBaseline() {
        return countBefore >= before.length;
    }

    void begin() {
        active = true;
    }

    void frameEnd(long now) {
        if (last != 0) {
            long gap = now - last;
            if (!active) before[countBefore++ % before.length] = gap;
            else if (countDuring < during.length) during[countDuring++] = gap;
            else overflow++;
        }
        last = now;
    }

    Map<String, Object> receipt() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("baseline", summarize(before, Math.min(before.length, countBefore)));
        summary.put("edit", summarize(during, countDuring));
        summary.put("editDroppedGaps", overflow);
        summary.put("kind", "natural owned RenderTick.END intervals; first edit interval straddles apply");
        return summary;
    }

    private static Map<String, Object> summarize(long[] original, int count) {
        long[] samples = Arrays.copyOf(original, count);
        Arrays.sort(samples);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", count);
        summary.put("p95Nanos", count == 0 ? 0 : samples[(count * 95 + 99) / 100 - 1]);
        summary.put("maxNanos", count == 0 ? 0 : samples[count - 1]);
        summary.put("over33ms", over(samples, 33));
        summary.put("over50ms", over(samples, 50));
        summary.put("over100ms", over(samples, 100));
        summary.put("over250ms", over(samples, 250));
        return summary;
    }

    private static int over(long[] values, int millis) {
        int count = 0;
        for (long value : values) if (value > millis * 1_000_000L) count++;
        return count;
    }
}
