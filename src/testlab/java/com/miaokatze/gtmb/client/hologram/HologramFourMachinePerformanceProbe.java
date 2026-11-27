// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testlab.costs.ConstructionCosts;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Passive, testlab-only whole-server event-span observation; never calls a machine tick or changes a queue. */
public final class HologramFourMachinePerformanceProbe {

    static final String FLAG = "gtmb.testlab.fourMachinePerformance";
    private static final int MAX_SAMPLES = 8192;
    private final List<Map<String, Object>> windows = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private Window active;
    private long tickStart, lastEnd, ticks;
    private boolean closed;

    HologramFourMachinePerformanceProbe(Map<String, Object> parent) {
        this(parent, false);
    }

    HologramFourMachinePerformanceProbe(Map<String, Object> parent, boolean finiteBlueprint) {
        if (!Boolean.getBoolean(FLAG)) return;
        if ((!HologramFourMachineRuntimeProbe.enabled()
            && !(finiteBlueprint && HologramOwnedLargeBlueprintProbe.ENABLED))
            || !Boolean.getBoolean("miaotest.host.environment"))
            throw new IllegalStateException("Performance observation requires an exact owned construction fixture");
        report.put("schema", "gtmb-construction-performance-v1");
        report.put(
            "scope",
            "ServerTick HIGHEST START to LOWEST END event span and END-to-END wall interval; includes other mods, fixture observers and scheduling; not exclusive GTMB CPU or exact MinecraftServer.tick duration");
        report.put("postOperationTicks", 40);
        report.put("postDemolitionTicks", 200);
        report.put("rawSampleLimitPerWindow", MAX_SAMPLES);
        report.put("windows", windows);
        report.put("closed", false);
        parent.put("performance", report);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    synchronized void submitted(String machine, String facing, int phase, String operation, boolean unformed) {
        if (!Boolean.getBoolean(FLAG)) return;
        if (active != null) throw new IllegalStateException("Overlapping performance windows");
        active = new Window(machine, facing, phase, operation, unformed, ticks);
        if (Boolean.getBoolean(ConstructionCosts.FLAG) && Boolean.getBoolean("miaotest.host.environment"))
            ConstructionCosts.openWindow();
    }

    /** Called after the genuine matching COMPLETE; hold the next fixture mutation for actual server ticks. */
    synchronized boolean completed(String request) {
        if (!Boolean.getBoolean(FLAG)) return true;
        if (active == null) throw new IllegalStateException("Missing submitted performance window");
        if (active.terminalTick < 0) {
            active.terminalTick = ticks;
            active.terminalNanos = System.nanoTime();
            active.data.put("request", request);
            active.data.put("submitToObservedCompleteNanos", active.terminalNanos - active.started);
        }
        int post = "DEMOLISH".equals(active.operation) ? 200 : 40;
        if (ticks - active.terminalTick < post) return false;
        if (Boolean.getBoolean(ConstructionCosts.FLAG) && Boolean.getBoolean("miaotest.host.environment")) {
            Map<String, Object> costs = ConstructionCosts.finishWindow();
            if (costs == null) return false;
            active.data.put("methodCosts", costs);
        }
        active.data.put("postTerminalActualTicks", ticks - active.terminalTick);
        active.data.put("terminalToWindowEndNanos", System.nanoTime() - active.terminalNanos);
        active.data.put("gcEnd", gc());
        Map<String, Object> countersEnd = counters();
        active.data.put("countersEnd", countersEnd);
        Map<String, Object> differences = new LinkedHashMap<>();
        for (String key : countersEnd.keySet()) {
            Object before = active.countersStart.get(key), after = countersEnd.get(key);
            differences.put(
                key,
                before instanceof Number && after instanceof Number
                    ? ((Number) after).longValue() - ((Number) before).longValue()
                    : "unknown");
        }
        active.data.put("counterDifferences", differences);
        active.data.put(
            "counterScope",
            "Global cumulative counters over submit plus post-terminal window; no reset, unavailable baseline fields remain unknown");
        active.data.put("constructionEventSpan", summarize(active.construction));
        active.data.put("postTerminalEventSpan", summarize(active.post));
        active.data.put("constructionEndInterval", summarize(active.constructionGaps));
        active.data.put("postTerminalEndInterval", summarize(active.postGaps));
        active.data.put("constructionRawNanos", new ArrayList<>(active.construction));
        active.data.put("postTerminalRawNanos", new ArrayList<>(active.post));
        active.data.put("rawSamplesTruncated", active.truncated);
        active.data.put("observationComplete", !active.truncated);
        windows.add(active.data);
        active = null;
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public synchronized void start(TickEvent.ServerTickEvent event) {
        if (!closed && event.phase == TickEvent.Phase.START) {
            tickStart = System.nanoTime();
            if (Boolean.getBoolean(ConstructionCosts.FLAG) && Boolean.getBoolean("miaotest.host.environment"))
                ConstructionCosts.bindServerThread();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public synchronized void end(TickEvent.ServerTickEvent event) {
        if (closed || event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        ticks++;
        if (active != null && tickStart != 0) {
            boolean post = active.terminalTick >= 0;
            List<Long> spans = post ? active.post : active.construction;
            List<Long> gaps = post ? active.postGaps : active.constructionGaps;
            if (spans.size() < MAX_SAMPLES) {
                spans.add(now - tickStart);
                if (lastEnd != 0) gaps.add(now - lastEnd);
            } else active.truncated = true;
        }
        lastEnd = now;
        tickStart = 0;
    }

    synchronized void close() {
        if (!Boolean.getBoolean(FLAG) || closed) return;
        closed = true;
        report.put("closed", true);
        report.put("actualObservedServerTicks", ticks);
        if (active != null && Boolean.getBoolean(ConstructionCosts.FLAG)
            && Boolean.getBoolean("miaotest.host.environment")) {
            Map<String, Object> costs = ConstructionCosts.finishWindow();
            active.data.put("methodCosts", costs);
            active.data.put("methodCostsIncomplete", costs == null);
        }
        report.put("unfinishedWindow", active == null ? null : active.data);
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
    }

    private static Map<String, Object> summarize(List<Long> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", values.size());
        if (values.isEmpty()) return result;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        long total = 0, over50 = 0;
        for (long value : values) {
            total += value;
            if (value > 50_000_000L) over50++;
        }
        result.put("totalNanos", total);
        result.put("medianNanos", sorted.get((sorted.size() - 1) / 2));
        result.put("p95Nanos", sorted.get((int) Math.ceil(sorted.size() * .95) - 1));
        result.put("p99Nanos", sorted.get((int) Math.ceil(sorted.size() * .99) - 1));
        result.put("maxNanos", sorted.get(sorted.size() - 1));
        result.put("over50ms", over50);
        return result;
    }

    private static Map<String, Object> gc() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("count", bean.getCollectionCount());
            values.put("millis", bean.getCollectionTime());
            result.put(bean.getName(), values);
        }
        return result;
    }

    private static Map<String, Object> counters() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("machineUpdateRequestedSeeds", counter("HologramMachineUpdateBatch", "requestedSeeds"));
        result.put("machineUpdateCoalescedSeeds", counter("HologramMachineUpdateBatch", "coalescedSeeds"));
        result.put("journalPublications", counter("HologramConstructionJournal", "publications"));
        result.put("journalPublishNanos", counter("HologramConstructionJournal", "publishNanos"));
        result.put("journalPublicationCacheHits", counter("HologramConstructionJournal", "publicationCacheHits"));
        runtimeCounters(result);
        return result;
    }

    private static void runtimeCounters(Map<String, Object> result) {
        NBTTagCompound metrics = com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.snapshot();
        boolean enabled = metrics.getBoolean("enabled");
        result.put("runtimeMetricsEnabled", enabled);
        NBTTagCompound queues = metrics.getCompoundTag("queues");
        for (String key : new String[] { "jobDeltaPackets", "jobDeltaEncodedBytes",
            "fullCellsSnapshotCompressedBytes" })
            result.put(key, enabled && queues.hasKey(key, 4) ? queues.getLong(key) : "unknown");
        for (String key : new String[] { "snapshotBuild", "snapshotCompress" }) {
            NBTTagCompound activity = metrics.getCompoundTag("activity")
                .getCompoundTag(key);
            result.put(key + "Calls", enabled && activity.hasKey("calls", 4) ? activity.getLong("calls") : "unknown");
            result.put(
                key + "Nanos",
                enabled && activity.hasKey("totalNanos", 4) ? activity.getLong("totalNanos") : "unknown");
        }
        long c2s = 0, s2c = 0;
        NBTTagCompound network = metrics.getCompoundTag("network");
        boolean present = !network.hasNoTags();
        for (Object key : network.func_150296_c()) {
            NBTTagCompound row = network.getCompoundTag((String) key);
            present &= row.hasKey("c2sTxBytes", 4) && row.hasKey("s2cTxBytes", 4);
            c2s += row.getLong("c2sTxBytes");
            s2c += row.getLong("s2cTxBytes");
        }
        result.put("networkC2sTxPayloadBytes", enabled && present ? c2s : "unknown");
        result.put("networkS2cTxPayloadBytes", enabled && present ? s2c : "unknown");
        result.put("networkTxPayloadBytes", enabled && present ? c2s + s2c : "unknown");
    }

    private static Object counter(String type, String name) {
        try {
            var field = Class.forName("com.miaokatze.gtmb.hologram." + type)
                .getDeclaredField(name);
            field.setAccessible(true);
            return field.getLong(null);
        } catch (ClassNotFoundException | NoSuchFieldException absent) {
            return "unknown";
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read declared diagnostic counter " + type + "." + name, failure);
        }
    }

    private static final class Window {

        final Map<String, Object> data = new LinkedHashMap<>();
        final List<Long> construction = new ArrayList<>(), post = new ArrayList<>();
        final List<Long> constructionGaps = new ArrayList<>(), postGaps = new ArrayList<>();
        final String operation;
        final Map<String, Object> countersStart = counters();
        final long started = System.nanoTime();
        long terminalTick = -1, terminalNanos;
        boolean truncated;

        Window(String machine, String facing, int phase, String operation, boolean unformed, long tick) {
            this.operation = operation;
            data.put("machine", machine);
            data.put("facing", facing);
            data.put("phase", phase);
            data.put("operation", operation);
            data.put("unformedCycle", unformed);
            data.put("submittedAtObservedServerTick", tick);
            data.put("gcStart", gc());
            data.put("countersStart", countersStart);
        }
    }
}
