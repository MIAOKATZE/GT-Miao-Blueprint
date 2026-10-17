package com.miaokatze.gtmb.hologram;

import java.util.concurrent.atomic.AtomicLongArray;

import net.minecraft.nbt.NBTTagCompound;

/** Opt-in local diagnostics. Codec bytes exclude Forge/TCP framing; elapsed work time is not server TPS. */
public final class HologramRuntimeMetrics {

    enum Packet {

        ACTION("action"),
        STATE("state"),
        FRAGMENT("fragment"),
        LIBRARY_REQUEST("libraryRequest"),
        LIBRARY_REPLY("libraryReply"),
        LIBRARY_SNAPSHOT("librarySnapshot"),
        CUT_ACTION("cutAction"),
        CUT_FRAGMENT("cutFragment");

        final String key;

        Packet(String key) {
            this.key = key;
        }
    }

    enum Activity {

        SNAPSHOT_BUILD("snapshotBuild"),
        SNAPSHOT_COMPRESS("snapshotCompress"),
        SERVER_ACTIONS("serverActions"),
        WORKSPACE_TICK("workspaceTick"),
        TRANSPORT_TICK("transportTick"),
        PLAN_STEP("planStep"),
        PLAN_CLASSIFY("planClassify"),
        PLAN_RESOURCES("planResources"),
        CELL_ROW("cellRow");

        final String key;

        Activity(String key) {
            this.key = key;
        }
    }

    enum Counter {

        ACTION_DEPTH("actionDepth"),
        ACTION_HIGH_WATER("actionHighWater"),
        ACTION_REJECTED("actionRejected"),
        SNAPSHOT_COALESCED("snapshotCoalesced"),
        SNAPSHOT_DEFERRED("snapshotDeferred"),
        SNAPSHOT_QUEUED("snapshotQueued"),
        SNAPSHOT_DROPPED("snapshotDropped"),
        CUT_DECODER_DEPTH("cutDecoderDepth"),
        CUT_DECODER_REJECTED("cutDecoderRejected"),
        PROGRESS_SENT("progressSent"),
        TASK_DEPTH("taskDepth"),
        TASK_EXECUTED("taskExecuted"),
        TASK_DEFERRED("taskDeferred"),
        FULL_CELLS_BUILDS("fullCellsSnapshotBuilds"),
        FULL_CELLS_VISITED("fullCellsSnapshotCells"),
        FULL_CELLS_COMPRESSED_BYTES("fullCellsSnapshotCompressedBytes"),
        MATERIAL_WORLD_CREATED("materialWorldCreated"),
        MATERIAL_DESCRIPTOR_USES("materialDescriptorUses"),
        RUNNING_JOB_FULL_BUILDS("runningJobFullBuilds"),
        JOB_DELTA_PACKETS("jobDeltaPackets"),
        JOB_DELTA_CELLS("jobDeltaCells"),
        JOB_DELTA_ENCODED_BYTES("jobDeltaEncodedBytes"),
        LIBRARY_CALLBACK_DEPTH("libraryCallbackDepth"),
        LIBRARY_CALLBACK_HIGH_WATER("libraryCallbackHighWater"),
        LIBRARY_CALLBACK_REJECTED("libraryCallbackRejected");

        final String key;

        Counter(String key) {
            this.key = key;
        }
    }

    private static final AtomicLongArray NETWORK = new AtomicLongArray(Packet.values().length * 8);
    private static final AtomicLongArray ACTIVITIES = new AtomicLongArray(Activity.values().length * 3);
    private static final AtomicLongArray COUNTERS = new AtomicLongArray(Counter.values().length);
    private static volatile boolean enabled;

    private HologramRuntimeMetrics() {}

    /** Configuration owners explicitly enable collection. This does not register events or authorize any operation. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Scheduler instrumentation only; no queue contents or mutable world/session objects leave this boundary. */
    public static long serverTasksStarted() {
        return start();
    }

    public static void serverTasksFinished(long started, int executed, int remaining) {
        if (!enabled) return;
        finish(Activity.SERVER_ACTIONS, started);
        add(Counter.TASK_EXECUTED, executed);
        gauge(Counter.TASK_DEPTH, remaining);
        add(Counter.TASK_DEFERRED, remaining);
    }

    /** Call at the start of an isolated measurement; concurrent producers can continue after individual resets. */
    public static void reset() {
        for (int i = 0; i < NETWORK.length(); i++) NETWORK.set(i, 0);
        for (int i = 0; i < ACTIVITIES.length(); i++) ACTIVITIES.set(i, 0);
        for (int i = 0; i < COUNTERS.length(); i++) COUNTERS.set(i, 0);
    }

    /**
     * Detached local observation. Integrated clients expose distinct codec TX/RX endpoints, not double-counted traffic.
     */
    public static NBTTagCompound snapshot() {
        NBTTagCompound result = new NBTTagCompound(), network = new NBTTagCompound();
        result.setBoolean("enabled", enabled);
        result.setLong("sampledAtNanos", System.nanoTime());
        String[] endpoints = { "c2sTx", "c2sRx", "s2cTx", "s2cRx" };
        for (Packet packet : Packet.values()) {
            NBTTagCompound row = new NBTTagCompound();
            for (int i = 0; i < endpoints.length; i++) {
                row.setLong(endpoints[i] + "Packets", NETWORK.get(packet.ordinal() * 8 + i * 2));
                row.setLong(endpoints[i] + "Bytes", NETWORK.get(packet.ordinal() * 8 + i * 2 + 1));
            }
            network.setTag(packet.key, row);
        }
        result.setTag("network", network);
        NBTTagCompound activities = new NBTTagCompound();
        for (Activity activity : Activity.values()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setLong("calls", ACTIVITIES.get(activity.ordinal() * 3));
            row.setLong("totalNanos", ACTIVITIES.get(activity.ordinal() * 3 + 1));
            row.setLong("maxNanos", ACTIVITIES.get(activity.ordinal() * 3 + 2));
            activities.setTag(activity.key, row);
        }
        result.setTag("activity", activities);
        NBTTagCompound queues = new NBTTagCompound();
        for (Counter counter : Counter.values()) queues.setLong(counter.key, COUNTERS.get(counter.ordinal()));
        result.setTag("queues", queues);
        return result;
    }

    static void packet(Packet packet, boolean toServer, boolean transmitted, int bytes) {
        if (!enabled) return;
        int index = packet.ordinal() * 8 + (toServer ? 0 : 4) + (transmitted ? 0 : 2);
        NETWORK.incrementAndGet(index);
        NETWORK.addAndGet(index + 1, Math.max(0, bytes));
    }

    static long start() {
        return enabled ? System.nanoTime() : 0;
    }

    static void finish(Activity activity, long started) {
        if (!enabled || started == 0) return;
        long elapsed = Math.max(0, System.nanoTime() - started);
        int index = activity.ordinal() * 3;
        ACTIVITIES.incrementAndGet(index);
        ACTIVITIES.addAndGet(index + 1, elapsed);
        maximum(ACTIVITIES, index + 2, elapsed);
    }

    static void add(Counter counter, long count) {
        if (enabled) COUNTERS.addAndGet(counter.ordinal(), count);
    }

    static void gauge(Counter counter, long count) {
        if (enabled) COUNTERS.set(counter.ordinal(), count);
    }

    static void highWater(Counter counter, long count) {
        if (enabled) maximum(COUNTERS, counter.ordinal(), count);
    }

    private static void maximum(AtomicLongArray counters, int index, long value) {
        long before;
        do {
            before = counters.get(index);
            if (before >= value) return;
        } while (!counters.compareAndSet(index, before, value));
    }
}
