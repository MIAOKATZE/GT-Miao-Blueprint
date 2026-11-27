// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab.costs;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in owned-window diagnostics. No product counters, CPU switches, business state or tick methods are changed. */
public final class ConstructionCosts {

    public static final String FLAG = "gtmb.testlab.constructionCosts";
    public static final int SAVED_JOB = 0, GT_END_TICK = 1, JOURNAL_PERSIST = 2;
    private static final String[] METHODS = { "savedJob.step", "gtMachineUpdate.endTick", "journal.persist" };
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static volatile Thread serverThread;
    private static volatile Window active;

    private ConstructionCosts() {}

    public static boolean enabled() {
        return Boolean.getBoolean(FLAG) && Boolean.getBoolean("miaotest.host.environment");
    }

    /** Called only by the exact owned probe's actual ServerTick listener, never from a simulated tick. */
    public static void bindServerThread() {
        if (!enabled()) return;
        Thread actual = Thread.currentThread();
        Thread previous = serverThread;
        if (previous != null && previous != actual)
            throw new IllegalStateException("Construction cost diagnostic server thread changed");
        serverThread = actual;
    }

    public static synchronized void openWindow() {
        if (!enabled()) return;
        if (active != null) throw new IllegalStateException("Overlapping construction cost windows");
        active = new Window();
    }

    /** Null means a method that began inside the window is still returning; the caller polls without a new window. */
    public static Map<String, Object> finishWindow() {
        Window window = active;
        if (window == null) return null;
        synchronized (window) {
            window.accepting = false;
            if (window.inFlight != 0) return null;
            if (active == window) active = null;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", true);
            result.put("windowComplete", true);
            result.put("serverThreadBound", serverThread != null);
            result.put("serverThreadName", serverThread == null ? "unknown" : serverThread.getName());
            result.put("serverThreadId", serverThread == null ? "unknown" : serverThread.getId());
            result.put("cpuSupportedAtWindowOpen", window.cpuSupported);
            result.put("cpuEnabledAtWindowOpen", window.cpuEnabled);
            result.put(
                "scope",
                "Methods entered on the bound real server thread between submitted and post-terminal window close; in-flight calls finish before freezing. Wall and CPU times are inclusive, nested rows must not be added. Diagnostic overhead is included; compare only matching opt-in runs.");
            result.put("timerFailures", window.timerFailures);
            Map<String, Object> methods = new LinkedHashMap<>();
            for (int i = 0; i < METHODS.length; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("calls", window.calls[i]);
                row.put("wallNanos", window.wall[i]);
                row.put("exceptionalReturns", window.exceptions[i]);
                row.put("cpuSamples", window.cpuSamples[i]);
                row.put(
                    "cpuNanos",
                    window.calls[i] > 0 && window.calls[i] == window.cpuSamples[i] ? window.cpu[i] : "unknown");
                methods.put(METHODS[i], row);
            }
            result.put("methods", methods);
            return result;
        }
    }

    public static Ticket begin(int method) {
        Window window = active;
        if (window == null || Thread.currentThread() != serverThread) return null;
        try {
            synchronized (window) {
                if (!window.accepting || active != window) return null;
                window.inFlight++;
            }
            return new Ticket(window, method, System.nanoTime(), cpuNow());
        } catch (RuntimeException failure) {
            synchronized (window) {
                window.timerFailures++;
                window.inFlight--;
            }
            return null;
        }
    }

    /**
     * Called in each wrapper's finally. Timer failure must never replace the product's original return or exception.
     */
    public static void finish(Ticket ticket, boolean returnedNormally) {
        if (ticket == null) return;
        long end = System.nanoTime(), cpuEnd = cpuNow();
        Window window = ticket.window;
        try {
            synchronized (window) {
                int method = ticket.method;
                window.calls[method]++;
                window.wall[method] += end - ticket.wall;
                if (!returnedNormally) window.exceptions[method]++;
                if (ticket.cpu >= 0 && cpuEnd >= ticket.cpu) {
                    window.cpu[method] += cpuEnd - ticket.cpu;
                    window.cpuSamples[method]++;
                }
            }
        } catch (RuntimeException diagnosticFailure) {
            synchronized (window) {
                window.timerFailures++;
            }
        } finally {
            synchronized (window) {
                window.inFlight--;
            }
        }
    }

    private static long cpuNow() {
        try {
            return CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled() ? CPU.getCurrentThreadCpuTime()
                : -1;
        } catch (RuntimeException unsupportedOrDenied) {
            return -1;
        }
    }

    private static boolean cpuSupported() {
        try {
            return CPU.isCurrentThreadCpuTimeSupported();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean cpuEnabled() {
        try {
            return CPU.isThreadCpuTimeEnabled();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static final class Ticket {

        private final Window window;
        private final int method;
        private final long wall, cpu;

        private Ticket(Window window, int method, long wall, long cpu) {
            this.window = window;
            this.method = method;
            this.wall = wall;
            this.cpu = cpu;
        }
    }

    private static final class Window {

        private final long[] calls = new long[METHODS.length], wall = new long[METHODS.length],
            cpu = new long[METHODS.length];
        private final long[] cpuSamples = new long[METHODS.length], exceptions = new long[METHODS.length];
        private final boolean cpuSupported = cpuSupported(), cpuEnabled = cpuEnabled();
        private boolean accepting = true;
        private int inFlight;
        private long timerFailures;
    }
}
