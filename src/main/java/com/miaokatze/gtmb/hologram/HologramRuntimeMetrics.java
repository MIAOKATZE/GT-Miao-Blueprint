package com.miaokatze.gtmb.hologram;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLongArray;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Opt-in local diagnostics. Codec bytes exclude Forge/TCP framing; elapsed work time is not server TPS. */
public final class HologramRuntimeMetrics {

    /** Local clock markers only. Correlate endpoints by identity; never subtract clocks from different JVMs. */
    public enum LoadStage {
        SERVER_CANONICAL_BEGIN,
        SERVER_CANONICAL_END,
        SERVER_SOURCE_COPY_BEGIN,
        SERVER_SOURCE_COPY_END,
        SERVER_POPULATION_BEGIN,
        SERVER_POPULATION_END,
        SERVER_METADATA_BEGIN,
        SERVER_METADATA_END,
        SERVER_METADATA_WORKER_QUEUED,
        SERVER_METADATA_WORKER_BEGIN,
        SERVER_METADATA_WORKER_END,
        SERVER_METADATA_WORKER_REJECTED,
        SERVER_ROWS_BEGIN,
        SERVER_ROWS_END,
        SERVER_ENCODER_QUEUED,
        SERVER_ENCODER_BEGIN,
        SERVER_ENCODER_END,
        SERVER_ENCODER_FAILED,
        SERVER_ENCODER_CANCELLED,
        SERVER_ENCODER_REJECTED,
        SERVER_FRAGMENT_FIRST,
        SERVER_FRAGMENT_LAST,
        CLIENT_FRAGMENT_FIRST,
        CLIENT_FRAGMENT_LAST,
        CLIENT_DECODE_QUEUED,
        CLIENT_DECODE_BEGIN,
        CLIENT_DECODE_END,
        CLIENT_DECODE_FAILED,
        CLIENT_PREPARE_BEGIN,
        CLIENT_PREPARE_END,
        CLIENT_INSTALL,
        CLIENT_DISPLAY_STATE,
        CLIENT_OUTLINE_READY,
        CLIENT_FIRST_MATERIAL,
        CLIENT_RENDER_READY;
    }

    private static final int LOAD_TRACE_LIMIT = 256;
    private static volatile String loadClock = "";
    private static final Deque<LoadMark> LOAD_TRACE = new ArrayDeque<>();
    private static long loadTraceDropped;

    private static final class LoadMark {

        final LoadStage stage;
        final String session, previousSession;
        final long nanos, revision, request, transport, count, previousRevision;

        LoadMark(LoadStage stage, String session, long revision, long request, long transport, long count,
            String previousSession, long previousRevision) {
            this.stage = stage;
            this.session = session;
            this.revision = revision;
            this.request = request;
            this.transport = transport;
            this.count = count;
            this.previousSession = previousSession;
            this.previousRevision = previousRevision;
            nanos = System.nanoTime();
        }
    }

    public static void loadStage(LoadStage stage, String session, long revision, long requestSequence,
        long transportSequence, long count) {
        if (!enabled) return;
        loadStage(stage, session, revision, requestSequence, transportSequence, count, "", -1);
    }

    /** previous identity is the previously installed or actually displayed state, never its content/name. */
    public static void loadStage(LoadStage stage, String session, long revision, long requestSequence,
        long transportSequence, long count, String previousSession, long previousRevision) {
        if (!enabled) return;
        synchronized (LOAD_TRACE) {
            if (LOAD_TRACE.size() == LOAD_TRACE_LIMIT) {
                LOAD_TRACE.removeFirst();
                loadTraceDropped++;
            }
            LOAD_TRACE.addLast(
                new LoadMark(
                    stage,
                    session,
                    revision,
                    requestSequence,
                    transportSequence,
                    count,
                    previousSession,
                    previousRevision));
        }
    }

    public static void loadStage(LoadStage stage, NBTTagCompound state, long transportSequence, long count) {
        if (!enabled) return;
        loadStage(
            stage,
            state.getString("session"),
            state.getLong("planRevision"),
            state.getLong("uiSequence"),
            transportSequence,
            count);
    }

    static void loadStage(LoadStage stage, HologramSession session, long transportSequence, long count) {
        if (!enabled) return;
        loadStage(stage, session.id, session.planRevision, session.uiSequence, transportSequence, count);
    }

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
        LIBRARY_CALLBACK_REJECTED("libraryCallbackRejected"),
        PALETTE_SNAPSHOTS_ENCODED("paletteSnapshotsEncoded"),
        PALETTE_ROWS_ENCODED("paletteRowsEncoded"),
        PALETTE_DESCRIPTORS_ENCODED("paletteDescriptorsEncoded"),
        PALETTE_BYTES_ENCODED("paletteBytesEncoded"),
        PALETTE_SNAPSHOTS_DECODED("paletteSnapshotsDecoded");

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
        if (value && loadClock.isEmpty()) synchronized (LOAD_TRACE) {
            if (loadClock.isEmpty()) loadClock = java.util.UUID.randomUUID()
                .toString();
        }
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
        synchronized (LOAD_TRACE) {
            LOAD_TRACE.clear();
            loadTraceDropped = 0;
        }
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
        NBTTagList loadStages = new NBTTagList();
        LoadMark[] marks;
        long dropped;
        synchronized (LOAD_TRACE) {
            marks = LOAD_TRACE.toArray(new LoadMark[0]);
            dropped = loadTraceDropped;
        }
        for (LoadMark mark : marks) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("stage", mark.stage.name());
            row.setString(
                "endpoint",
                mark.stage.name()
                    .startsWith("SERVER_") ? "server" : "client");
            row.setString("session", mark.session);
            row.setLong("revision", mark.revision);
            row.setLong("requestSequence", mark.request);
            row.setLong("transportSequence", mark.transport);
            row.setLong("atNanos", mark.nanos);
            row.setLong("count", mark.count);
            row.setString("previousSession", mark.previousSession);
            row.setLong("previousRevision", mark.previousRevision);
            loadStages.appendTag(row);
        }
        result.setLong("loadTraceDropped", dropped);
        result.setString("loadClockId", loadClock);
        result.setInteger("loadTraceCapacity", LOAD_TRACE_LIMIT);
        result.setTag("loadStages", loadStages);
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
