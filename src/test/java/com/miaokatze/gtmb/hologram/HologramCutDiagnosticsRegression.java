package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Deterministic counters only, not a native-world timing or durability test. */
final class HologramCutDiagnosticsRegression {

    private HologramCutDiagnosticsRegression() {}

    static void run() {
        List<String> output = new ArrayList<>();
        AtomicLong clock = new AtomicLong(100);
        HologramCutDiagnostics trace = new HologramCutDiagnostics(output::add, clock::get);
        HologramCutDiagnostics.Scope phase = trace.phase(HologramCutDiagnostics.Phase.CAPTURE, 12);
        clock.set(125);
        phase.close();
        phase.close();
        trace.completed();
        trace.close();
        trace.close();
        SimpleAssert.that(output.size() == 1, "cut phase summary is emitted once");
        SimpleAssert.that(
            output.get(0)
                .contains("completed=true CAPTURE[calls=1,units=12,ns=25]"),
            "cut phase counts are exact");
        SimpleAssert.that(
            output.get(0)
                .length() < 200,
            "cut summary has no payload");
        output.clear();
        try (HologramCutDiagnostics failed = new HologramCutDiagnostics(output::add, clock::get)) {
            try (HologramCutDiagnostics.Scope ignored = failed.phase(HologramCutDiagnostics.Phase.JOURNAL_WRITE, 4)) {
                clock.addAndGet(8);
            }
        }
        SimpleAssert.that(
            output.get(0)
                .contains("completed=false JOURNAL_WRITE[calls=1,units=4,ns=8]"),
            "unfinished phases remain observable");
        // A failed sink must not change an already committed transaction's result.
        new HologramCutDiagnostics(message -> { throw new IllegalStateException("sink"); }, clock::get).close();
        try (HologramCutDiagnostics.Scope ignored = HologramCutDiagnostics.QUIET
            .phase(HologramCutDiagnostics.Phase.CAPTURE, 1)) {}
        HologramCutDiagnostics.QUIET.close();
    }
}
