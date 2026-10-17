package gtmb.v0110.probe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Disposable real-world Save stimulus. The production background observer owns the verdict. */
@Mod(modid = "gtmb_v0110_background_save_probe", name = "GTMB background real Save stimulus", version = "1")
public final class V0110BackgroundSaveProbe {
    private final Properties proof = new Properties();
    private int preLoginRetries;
    private boolean enteredFixtureWorld;
    private File output;
    private Object mc, actor, backend;
    private boolean client, server, done, fixtureReady, configRequested, restoreRequested, configured;
    private int phase, ticks, stage, choiceStep, placed, originalUp, originalDown, originalMax;
    private long started, phaseStarted, lastGeneration = -1, heartbeat, saveTaskToken, lastSaveDiagnostic;
    private int saveDiagnostics;
    private boolean saveWaitScreenshotPending, saveWaitScreenshotDone;
    private long lastImportClientDiagnostic, lastImportServerDiagnostic;
    private int importClientDiagnostics, importServerDiagnostics;
    private boolean importWaitScreenshotPending, importWaitScreenshotDone;
    private String verdict = "";
    private int preflight;
    private boolean largeEdit, saveContextPrepared;
    private int sourceCells = 4096, sourceWidth = 16, sourceHeight = 16, sourceDepth = 16, platformPlaced;
    private boolean chunksPrepared, safeLargeSpawnSet;
    private int baselineTicks, maxParsed, maxDirtyRegions, maxBuiltRegions;
    private long overallBudgetNanos = 900000000000L, importBudgetNanos = 240000000000L;
    private final Properties timings = new Properties();
    private final java.util.Map<String, long[]> serverTickWindows = new java.util.LinkedHashMap<String, long[]>();
    private final java.util.Map<String, Long> workStageDurations = new java.util.LinkedHashMap<String, Long>();
    private long serverTickStarted, serverTicks, workStageStarted;
    private static final long[] TICK_BUCKET_NANOS = { 1000000L, 5000000L, 10000000L, 20000000L, 50000000L,
        100000000L, 250000000L, 500000000L, 1000000000L, Long.MAX_VALUE };
    private String serverWorkStage = "fixture", previousWorkStage = "";
    private int serverClientPhase;
    private boolean runtimeMetricsAttempted, runtimeMetricsCaptured, runtimeMetricsOriginalEnabled, runtimeMetricsRestored;
    private int runtimeMetricSamples, runtimeStageSamples;
    private String runtimeMetricSession = "";
    private long runtimeMetricToken;

    /** Only detached atomic counters cross this boundary; no world/registry inspection or reset. */
    private void beginRuntimeMetrics() {
        if (runtimeMetricsAttempted) return;
        runtimeMetricsAttempted = true;
        try {
            Class<?> metrics = Class.forName("com.miaokatze.gtmb.hologram.HologramRuntimeMetrics");
            runtimeMetricsOriginalEnabled = (Boolean) call(metrics, "isEnabled");
            runtimeMetricsCaptured = true;
            proof.setProperty("runtimeMetricsOriginalEnabled", String.valueOf(runtimeMetricsOriginalEnabled));
            call(metrics, "setEnabled", true);
            runtimeMetricsSnapshot("main-thread-start");
        } catch (Exception failure) { proof.setProperty("runtimeMetricsFailure", failure.toString()); }
    }

    private void runtimeMetricsSnapshot(String checkpoint) {
        if (!runtimeMetricsCaptured || runtimeMetricsRestored) return;
        try {
            long before = System.nanoTime();
            Object sample = call(Class.forName("com.miaokatze.gtmb.hologram.HologramRuntimeMetrics"), "snapshot");
            long after = System.nanoTime();
            call(sample, "func_74778_a", "checkpoint", checkpoint);
            call(sample, "func_74778_a", "side", client ? "client" : "server");
            call(sample, "func_74778_a", "case", output.getName());
            call(sample, "func_74768_a", "driverPhase", client ? phase : serverClientPhase);
            call(sample, "func_74778_a", "serverWorkStage", serverWorkStage);
            call(sample, "func_74778_a", "session", runtimeMetricSession);
            call(sample, "func_74772_a", "taskToken", runtimeMetricToken);
            call(sample, "func_74757_a", "taskTokenAvailable", runtimeMetricToken > 0);
            call(sample, "func_74778_a", "identityScope", client ? "observed client session/task identity; zero token means unavailable or not yet issued" : "server session and last polled client task token; END-boundary observation may lag client");
            call(sample, "func_74772_a", "observerBeforeNanos", before);
            call(sample, "func_74772_a", "observerAfterNanos", after);
            call(sample, "func_74772_a", "observerWallMillis", System.currentTimeMillis());
            call(sample, "func_74772_a", "driverElapsedNanos", after - started);
            call(sample, "func_74778_a", "scope", "process-local cumulative atomic counters without reset; codec packet/bytes exclude Forge/TCP framing; concurrent fields are sampled separately; root derives monotonic counter differences, gauges/maxima are absolute observations");
            String name = String.format(java.util.Locale.ROOT, "metrics-%s-%03d-%s.nbt", client ? "client" : "server", ++runtimeMetricSamples, checkpoint);
            call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", sample, file(name));
            proof.setProperty("runtimeMetricsLastSample", name);
            proof.setProperty("runtimeMetricsSamples", String.valueOf(runtimeMetricSamples));
        } catch (Exception failure) { proof.setProperty("runtimeMetricsFailure", failure.toString()); }
    }

    private void finishRuntimeMetrics(String status) {
        try { runtimeMetricsSnapshot("terminal-" + status.toLowerCase(java.util.Locale.ROOT)); }
        finally {
            if (runtimeMetricsCaptured && !runtimeMetricsRestored) try {
                call(Class.forName("com.miaokatze.gtmb.hologram.HologramRuntimeMetrics"), "setEnabled", runtimeMetricsOriginalEnabled);
                runtimeMetricsRestored = true;
                proof.setProperty("runtimeMetricsRestored", "true");
            } catch (Exception failure) { proof.setProperty("runtimeMetricsRestoreFailure", failure.toString()); }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void measureServerTickStart(TickEvent.ServerTickEvent event) {
        if (server && !done && event.phase == TickEvent.Phase.START) serverTickStarted = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void measureServerTickEnd(TickEvent.ServerTickEvent event) {
        if (!server || serverTickStarted == 0 || event.phase != TickEvent.Phase.END) return;
        long elapsed = System.nanoTime() - serverTickStarted; serverTickStarted = 0; serverTicks++;
        long[] histogram = serverTickWindows.get(serverWorkStage);
        if (histogram == null) { histogram = new long[TICK_BUCKET_NANOS.length + 3]; serverTickWindows.put(serverWorkStage, histogram); }
        histogram[0]++; histogram[1] = Math.max(histogram[1], elapsed); histogram[2] += elapsed;
        for (int i = 0; i < TICK_BUCKET_NANOS.length; i++) if (elapsed <= TICK_BUCKET_NANOS[i]) { histogram[i + 3]++; break; }
        if (done || serverTicks % 100 == 0) try { writeMeasurements(); }
        catch (Exception failure) { importDiagnosticError("server-measurement", failure); }
    }

    private void observeServerWorkStages(Object world, List<?> livePlayers) {
        try {
            String current = "fixture";
            if (actor != null) {
                Object id = call(actor, "func_110124_au");
                Class<?> workspace = Class.forName("com.miaokatze.gtmb.hologram.HologramWorkspace");
                Object area = ((java.util.Map<?, ?>) get(workspace, "AREAS")).get(id);
                Object session = ((java.util.Map<?, ?>) get(workspace, "SESSIONS")).get(id);
                current = "client-phase-" + serverClientPhase;
                if (area != null && (Boolean) get(area, "importing")) {
                    current = "area." + get(area, "importPhase");
                    String detail = String.valueOf(call(area, "importDetail"));
                    if (!detail.isEmpty()) current += "." + detail;
                    timings.setProperty("server.work.done", String.valueOf(call(area, "importDone")));
                    timings.setProperty("server.work.total", String.valueOf(call(area, "importTotal")));
                    timings.setProperty("server.work." + current + ".lastObservedDone", String.valueOf(call(area, "importDone")));
                    timings.setProperty("server.work." + current + ".lastObservedTotal", String.valueOf(call(area, "importTotal")));
                }
                if (session != null) {
                    runtimeMetricSession = String.valueOf(get(session, "id"));
                    Object task = ((java.util.Map<?, ?>) get(Class.forName("com.miaokatze.gtmb.hologram.HologramSnapshotPreparation"), "TASKS")).get(session);
                    if (task != null) {
                        current = "snapshot.stage-" + get(task, "stage");
                        Object metadata = get(task, "metadata");
                        if (metadata != null) current += ".metadata-" + get(metadata, "stage");
                    }
                }
            }
            long now = System.nanoTime();
            if (!current.equals(previousWorkStage)) {
                if (!previousWorkStage.isEmpty()) workStageDurations.put(previousWorkStage,
                    (workStageDurations.containsKey(previousWorkStage) ? workStageDurations.get(previousWorkStage) : 0L) + now - workStageStarted);
                previousWorkStage = current; workStageStarted = now; serverWorkStage = current;
                if (runtimeStageSamples < 96) { runtimeStageSamples++; runtimeMetricsSnapshot("stage-transition"); }
                else proof.setProperty("runtimeMetricsStageLimitReached", "true");
                writeMeasurements();
            }
        } catch (Exception failure) { importDiagnosticError("server-measurement", failure); }
    }

    private void writeMeasurements() throws Exception {
        long now = System.nanoTime();
        if (client) {
            timings.setProperty("driver.currentPhase", String.valueOf(phase));
            timings.setProperty("driver.currentPhaseElapsedNanos", String.valueOf(now - phaseStarted));
            timings.setProperty("scope", "actual driver transition wall time; not pure CPU time or FPS");
            store("client-phase-timings.properties", timings);
        } else if (server) {
            Properties measured = new Properties();
            measured.setProperty("scope", "read-only FML ServerTick START(HIGHEST)..END(LOWEST) nanoseconds; includes game and addon listeners; bucket quantiles are upper bounds, not exact quantiles/TPS");
            measured.setProperty("serverTicksObserved", String.valueOf(serverTicks));
            measured.setProperty("currentStage", serverWorkStage);
            measured.setProperty("sourceCellsRequested", String.valueOf(sourceCells));
            measured.setProperty("sourceCellsWrittenAndReadBack", String.valueOf(placed));
            measured.setProperty("platformBlocksWritten", String.valueOf(largeEdit ? platformPlaced : fixtureReady ? sourceWidth * (sourceDepth + 8) : 0));
            measured.setProperty("chestReplacementWrites", fixtureReady ? "1" : "0");
            for (String key : timings.stringPropertyNames()) if (key.startsWith("server.work."))
                measured.setProperty(key, timings.getProperty(key));
            for (java.util.Map.Entry<String, long[]> entry : serverTickWindows.entrySet()) {
                String key = entry.getKey(); long[] h = entry.getValue();
                measured.setProperty(key + ".ticks", String.valueOf(h[0]));
                measured.setProperty(key + ".maxNanos", String.valueOf(h[1]));
                measured.setProperty(key + ".sumNanos", String.valueOf(h[2]));
                for (int q : new int[] { 50, 95, 99 }) {
                    long target = (h[0] * q + 99) / 100, count = 0;
                    for (int i = 0; i < TICK_BUCKET_NANOS.length; i++) if ((count += h[i + 3]) >= target) {
                        measured.setProperty(key + ".p" + q + "UpperBoundNanos",
                            TICK_BUCKET_NANOS[i] == Long.MAX_VALUE ? "overflow>1000000000" : String.valueOf(TICK_BUCKET_NANOS[i])); break;
                    }
                }
            }
            for (java.util.Map.Entry<String, Long> entry : workStageDurations.entrySet()) measured.setProperty("stageWall." + entry.getKey(), String.valueOf(entry.getValue()));
            if (!previousWorkStage.isEmpty()) measured.setProperty("stageWall." + previousWorkStage,
                String.valueOf((workStageDurations.containsKey(previousWorkStage) ? workStageDurations.get(previousWorkStage) : 0L) + now - workStageStarted));
            store("server-tick-and-stage-metrics.properties", measured);
        }
    }
    private long baselineRevision;
    private Object baselineState;
    private java.util.Map<Object, Object> baselineRegions, baselinePreview;
    private java.util.function.Consumer<Object> originalReceiver;
    private java.util.function.Consumer<Object> transportReceiverOriginal;
    private final java.util.List<Object> transportErrors = java.util.Collections.synchronizedList(new java.util.ArrayList<Object>());
    private long areaTaskToken, areaTaskCommand = -1;
    private String areaTaskSession = "";
    private final java.util.List<Object> observedPackets = java.util.Collections.synchronizedList(new java.util.ArrayList<Object>());
    private final java.util.List<Object> packetEvidence = new java.util.ArrayList<Object>();
    private byte[] originalPreferences;
    private File preferences;
    private boolean preferencesCaptured, preferencesExisted, preferencesRestored;
    private String screenshotPending = "", screenshotDone = "";
    private Object screenshotUi;
    private long guidanceGeneration, secondSaveTaskToken;


    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        client = Boolean.getBoolean("gtmb.v0110.backgroundClient");
        server = Boolean.getBoolean("gtmb.v0110.backgroundServer");
        if (!client && !server) return;
        largeEdit = Boolean.getBoolean("gtmb.v0110.largeEdit");
        sourceCells = Integer.getInteger("gtmb.v0110.cells", largeEdit ? 50000 : 4096);
        if (largeEdit) {
            check(sourceCells == 50000 || sourceCells == 100000 || sourceCells == 1000000, "explicit supported native scale required");
            sourceWidth = sourceDepth = sourceCells == 1000000 ? 100 : 50;
            sourceHeight = sourceCells / (sourceWidth * sourceDepth);
            overallBudgetNanos = sourceCells == 1000000 ? 3600000000000L : 1800000000000L;
            importBudgetNanos = sourceCells == 1000000 ? 2400000000000L : 900000000000L;
        } else check(sourceCells == 4096, "background fixture remains exactly4096 cells");
        check((long) sourceWidth * sourceHeight * sourceDepth == sourceCells && 80 + sourceHeight <= 256,
            "exact bounded source dimensions and height required");
        check(client != server, "one explicit fixture side required");
        File fixture = new File(System.getProperty("gtmb.hologram.followupRoot", "")).getCanonicalFile();
        check(fixture.getPath().replace('\\', '/').endsWith("/build/hologram-packaged-v018-mui"), "exact existing owned fixture required");
        check(new File(".").getCanonicalFile().equals(new File(fixture, client ? "client" : "server").getCanonicalFile()), "exact fixture working directory required");
        output = new File(System.getProperty("gtmb.v0110.output", "")).getCanonicalFile();
        check(output.toPath().startsWith(new File(fixture.getParentFile(), "verification/render-v0110").getCanonicalFile().toPath()) && output.isDirectory(), "precreated owned evidence directory required");
        check(output.getName().matches("[A-Za-z0-9_-]{1,20}"), "short fresh case basename required for unique native Save names");
        check(!file(client ? "background-client.properties" : "background-server.properties").exists(), "fresh receipts required");
        check(!file("background-save-native.nbt").exists(), "fresh observer receipt required");
        if (client) {
            if (!largeEdit) check(Boolean.getBoolean("gtmb.hologram.backgroundProbe") && Boolean.getBoolean("gtmb.hologram.previewCaptureWitness"), "production observer and native capture witness required");
            if (!largeEdit) check(new File(System.getProperty("gtmb.hologram.backgroundProbeDir", "")).getCanonicalFile().equals(output), "observer must use exact same evidence directory");
            for (String key : new String[] { "gtmb.hologram.clientSmoke", "gtmb.hologram.sulfurLineClient", "gtmb.v019.backgroundClient", "gtmb.v019.ctmClient", "gtmb.v019.downloadClient", "gtmb.v0110.ctmClient", "gtmb.v0110.downloadClient", "gtmb.hologram.followupWorkbenchClient", "gtmb.hologram.followupProductionFlow", "gtmb.hologram.productionPreviewSmoke" })
                check(!Boolean.getBoolean(key), "exclusive stimulus driver: " + key);
        }
        proof.setProperty("scope", largeEdit ? "actual dedicated source capture at selected exact scale, production virtual rotateCell Netty command, observed delta and naturally rendered region reuse; no Save or FPS/TPS claim" : "real dedicated world 16-cubed block area; production area command packets and server capture; actual six-stage MUI input and Save with optional production AE review; observer independently verifies real JSON+PNG/owner ACK and disposed background panels; no OS input");
        proof.setProperty("mode", largeEdit ? "large" + sourceCells + "-local-edit-no-save" : "real-background-save4096");
        proof.setProperty("requestedSourceCells", String.valueOf(sourceCells));
        proof.setProperty("sourceDimensionsXYZ", sourceWidth + "," + sourceHeight + "," + sourceDepth);
        proof.setProperty("sourceBounds", "0,80,0.." + (sourceWidth - 1) + "," + (79 + sourceHeight) + "," + (sourceDepth - 1));
        proof.setProperty("snapshotRawLimitBytes", "134217728"); proof.setProperty("artifactJsonLimitBytes", "8388608");
        proof.setProperty("limitScope", "area volume eligibility is not full snapshot or artifact support; no native PASS until actual delivery/render/edit assertions complete");
        proof.setProperty("pid", ManagementFactory.getRuntimeMXBean().getName());
        started = phaseStarted = System.nanoTime();
        FMLCommonHandler.instance().bus().register(this);
        persist("RUNNING");
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (!client || done || event.phase != TickEvent.Phase.END) return;
        try {
            beginRuntimeMetrics();
            check(System.nanoTime() - started < overallBudgetNanos, "client overall timeout");
            check(System.nanoTime() - phaseStarted < (phase <= 1 ? (largeEdit ? importBudgetNanos : 600000000000L) : largeEdit && (phase == 5 || phase == 70) ? importBudgetNanos : 240000000000L), "client phase timeout " + phase);
            Properties peer = read("background-server.properties");
            check(!"FAIL".equals(peer.getProperty("status")), "paired server failed: " + peer.getProperty("failure", ""));
            if (mc == null) {
                mc = call(Class.forName("net.minecraft.client.Minecraft"), "func_71410_x");
                set(get(mc, "field_71474_y"), "field_82881_y", false);
                preparePreferences();
            }
            ticks++;
            Object player = get(mc, "field_71439_g"), world = get(mc, "field_71441_e");
            Object gui = get(mc, "field_71462_r");
            pulse(gui);
            if (phase == 5) { checkOwnedAreaTransportError(); importClientDiagnostic(gui); }
            if (phase == 10) saveDiagnostic(gui);
            if (phase == 0) {
                if (ticks < 30 || gui == null) return;
                String address = System.getProperty("gtmb.v0110.address", "127.0.0.1:25678");
                check(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "explicit loopback address required");
                Object fml = call(Class.forName("cpw.mods.fml.client.FMLClientHandler"), "instance");
                call(fml, "setupServerList");
                call(fml, "connectToServer", gui, construct("net.minecraft.client.multiplayer.ServerData", "GTMB real background Save", address));
                advance(1); return;
            }
            if(recoverPreLogin(gui,world,player))return;
            if (world == null || player == null) return;
            check(!(Boolean) call(call(call(mc, "func_147114_u"), "func_147298_b"), "func_150731_c"), "actual dedicated Netty socket required");
            if (phase == 1) {
                if (!file("background-fixture-ready.properties").isFile() || !"true".equals(peer.getProperty("configApplied"))) return;
                Object held = call(player, "func_70694_bm");
                if (held == null || !call(held, "func_77973_b").getClass().getName().endsWith("ItemNekoHologramProjector")) return;
                Object sourceChunk = call(world, "func_72964_e", 0, 0);
                Object actorChunk = call(world, "func_72964_e", 0, largeEdit ? -1 : 1);
                if (sourceChunk.getClass().getName().endsWith("EmptyChunk") || actorChunk.getClass().getName().endsWith("EmptyChunk")) return;
                check((Integer) get(sourceChunk, "field_76635_g") == 0 && (Integer) get(sourceChunk, "field_76647_h") == 0
                    && (Integer) get(actorChunk, "field_76635_g") == 0 && (Integer) get(actorChunk, "field_76647_h") == (largeEdit ? -1 : 1),
                    "actual client source and actor chunk coordinates required");
                if (!call(call(world, "func_147439_a", 0, 80, 0), "func_149739_a").toString().contains("chest")) return;
                proof.setProperty("actualSourceAndActorChunksLoaded", "true");
                Object cut = get(clientType("HologramCutClient"), "INSTANCE");
                call(cut, "chooseCutMode");
                check((Boolean) call(cut, "cutMode") && (Boolean) call(cut, "selecting"), "real CUT selection entered");
                Object escape = construct("net.minecraftforge.client.event.GuiOpenEvent", construct("net.minecraft.client.gui.GuiIngameMenu"));
                check((Boolean) call(cut, "escape", escape), "actual production GuiOpen Escape handler consumed pause-screen event");
                check(!(Boolean) call(cut, "cutMode") && !(Boolean) call(cut, "active") && (Boolean) call(escape, "isCanceled"), "CUT Escape exits tool completely");
                proof.setProperty("cutEscapeScope", "production GuiOpenEvent handler; no OS/keyboard-event claim");
                call(clientType("HologramClient"), "beginAreaSelection");
                advance(2); return;
            }
            Object state = get(clientType("HologramClient"), "state");
            if (state == null) return;
            Object data = get(state, "data");
            if (phase == 10) {
                Object save = call(clientType("HologramLibraryClient"), "actualSaveWitness");
                Object head = call(save, "func_74775_l", "downloadBeginReply");
                if (saveTaskToken > 0 && (Long) call(head, "func_74763_f", "clientTaskToken") == saveTaskToken) {
                    proof.setProperty("saveReviewRoute", "production direct save without AE review");
                    proof.setProperty("uiControlReleasedToObserver", "true"); advance(11);
                } else if (file("background-save-native.nbt").isFile()) {
                    // The observer may already have emitted its terminal receipt before this listener runs.
                    // Phase 11 validates it; existence is never treated as success here.
                    proof.setProperty("saveReviewRoute", "observer terminal before optional AE review"); advance(11);
                }
            }
            Object selection = phase >= 2 && phase <= 4 ? call(clientType("HologramClient"), "localAreaSelection") : null;
            if (phase == 2) {
                if (selection == null || !bool(selection, "areaSelecting")) return;
                areaPoint(1, 0, 80, 0); advance(3); return;
            }
            if (phase == 3) {
                if (selection == null || !Arrays.equals((int[]) call(selection, "func_74759_k", "areaPoints"), new int[] { 0, 80, 0 })) return;
                areaPoint(2, sourceWidth - 1, 79 + sourceHeight, sourceDepth - 1); advance(4); return;
            }
            if (phase == 4) {
                if (selection == null || !Arrays.equals((int[]) call(selection, "func_74759_k", "areaPoints"), new int[] { 0, 80, 0, sourceWidth - 1, 79 + sourceHeight, sourceDepth - 1 })) return;
                proof.setProperty("areaSelectionStimulus", "production localAreaPoint and actual localAreaSelection geometry; real worldAction finish submits final selection once");
                installTransportFailureWatch();
                runtimeMetricSession = String.valueOf(get(state, "session")); runtimeMetricToken = 0;
                runtimeMetricsSnapshot("before-import");
                call(clientType("HologramClient"), "worldAction", "finishAreaSelection");
                Class<?> tasks = clientType("HologramBackgroundTasks");
                areaTaskToken = (Long) call(tasks, "token"); areaTaskCommand = (Long) get(tasks, "command");
                areaTaskSession = (String) get(tasks, "session");
                runtimeMetricSession = areaTaskSession; runtimeMetricToken = areaTaskToken;
                proof.setProperty("areaTaskToken", String.valueOf(areaTaskToken));
                proof.setProperty("areaTaskCommand", String.valueOf(areaTaskCommand));
                proof.setProperty("areaTaskSession", areaTaskSession);
                proof.setProperty("typedBackgroundFailureHook", "pending typed FAILURE/CANCELLED distinction; never infer from succeeded=false");
                advance(5); return;
            }
            if (phase == 5) {
                if (!bool(data, "areaImported") || bool(data, "areaImporting") || bool(data, "capturePending") || busy()) return;
                check(((List<?>) get(state, "cells")).size() == sourceCells, "actual server capture has exact source block count");
                proof.setProperty("actualCapturedCells", String.valueOf(sourceCells));
                runtimeMetricsSnapshot("after-import");
                advance(6);
            }
            // After final AE confirmation, never touch the UI again. The observer owns both reopen cycles.
            if (phase == 11) {
                if (!file("background-save-native.nbt").isFile()) return;
                Object receipt = call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74797_a", file("background-save-native.nbt"));
                check(receipt != null && bool(receipt, "completed"), "observer completed receipt required");
                check(saveTaskToken > 0 && (Long) call(receipt, "func_74763_f", "taskToken") == saveTaskToken,
                    "observer receipt belongs to this exact Save task");
                check(bool(receipt, "passed"), "production observer failed: " + str(receipt, "failure"));
                check(bool(receipt, "actualIoCompleted") && bool(receipt, "completedWhileUiClosed"), "actual background pair IO required");
                proof.setProperty("observerReceipt", file("background-save-native.nbt").getCanonicalPath());
                proof.setProperty("observerTaskToken", String.valueOf(call(receipt, "func_74763_f", "taskToken")));
                proof.setProperty("backgroundObservedPass", "true");
                runtimeMetricsSnapshot("after-first-save");
                advance(12); return;
            }
            if (gui == null || !gui.getClass().getName().equals("com.miaokatze.gtmb.client.hologram.HologramScreen")) return;
            Object ui = call(gui, "smokeLibraryUiState");
            long generation = (Long) call(ui, "func_74763_f", "renderGeneration");
            if (generation <= lastGeneration || generation == 0 || (Boolean) call(gui, "smokePending")) return;
            if (phase != 10 && phase != 14 && busy()) return;
            if (phase >= 70) { largeLocalEdit(gui, ui, state, generation); return; }
            if (phase >= 12) { postSave(gui, ui, generation); return; }
            if (phase == 6) {
                if (largeEdit) { advance(70); return; }
                if (!bool(ui, "blueprintTab") || !bool(ui, "library")) { click(gui, ui, 149, generation); return; }
                if (!displayedSource(gui, ui, state)) return;
                if (preflight < 6) { preflight(gui, ui, generation); return; }
                check(bool(data, "blueprintProcessingRequired"), "real imported source requires production processing");
                click(gui, ui, 220, generation); advance(7);
            } else if (phase == 7) {
                if (!bool(ui, "processingFlow")) return;
                if (!bool(ui, "flowFullReview")) { click(gui, ui, 296, generation); return; }
                check(integer(ui, "flowStage") == stage, "expected manual processing stage " + stage);
                if (choiceStep == 0) {
                    if (stage == 0) { click(gui, ui, 232, generation); choiceStep = 1; }
                    else choiceStep = 1;
                } else if (choiceStep == 1) {
                    int[] controls = { 235, 241, 260, 270, 280, 290 };
                    click(gui, ui, controls[stage], generation); choiceStep = 2;
                } else {
                    check(bool(ui, "flowChosen"), "explicit processing choice required");
                    click(gui, ui, 231, generation); stage++; choiceStep = 0;
                    if (stage == 6) advance(8);
                }
            } else if (phase == 8) {
                if (!bool(data, "blueprintProcessingComplete") || bool(ui, "processingFlow") || !displayedSource(gui, ui, state)) return;
                proof.setProperty("manualProcessingStages", "6");
                typeName(gui, "V0110 real background save " + output.getName()); lastGeneration = generation; advance(9);
            } else if (phase == 9) {
                if (!displayedSource(gui, ui, state)) return;
                Object preview = get(get(gui, "ui"), "preview");
                if (!saveContextPrepared) {
                    call(call(preview, "selection"), "click", 0, false); call(preview, "selected", 0);
                    call(preview, "yaw", 37.25d); call(preview, "pitch", -17.5d);
                    saveContextPrepared = true; lastGeneration = generation; return;
                }
                check((Double) call(preview, "yaw") == 37.25d && (Double) call(preview, "pitch") == -17.5d,
                    "actual Save begins with nondefault local camera");
                if (!capture(gui, "pre-save-workspace")) return;
                runtimeMetricToken = 0; runtimeMetricsSnapshot("before-first-save");
                click(gui, ui, 140, generation);
                proof.setProperty("saveName", "V0110 real background save " + output.getName());
                saveTaskToken = (Long) call(clientType("HologramBackgroundTasks"), "token");
                runtimeMetricToken = saveTaskToken;
                proof.setProperty("saveTaskTokenAfter140", String.valueOf(saveTaskToken)); advance(10);
            } else if (phase == 10) {
                if (!bool(ui, "aeReview")) return;
                Object review = call(data, "func_74775_l", "aeSaveReview");
                check(!str(review, "transactionId").isEmpty(), "actual AE review nonce required");
                proof.setProperty("aeReviewTransaction", str(review, "transactionId"));
                proof.setProperty("saveReviewRoute", "actual AE review confirmation 181");
                click(gui, ui, 181, generation); proof.setProperty("uiControlReleasedToObserver", "true"); advance(11);
            }
        } catch (Throwable failure) { fail(failure); }
    }

    /** One naturally rendered END framebuffer; this listener never drives, constructs, or reopens a screen. */
    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (!client || done || !saveWaitScreenshotPending || saveWaitScreenshotDone || mc == null
            || event.phase != TickEvent.Phase.END) return;
        try {
            int width = (Integer) get(mc, "field_71443_c"), height = (Integer) get(mc, "field_71440_d");
            File game = (File) get(mc, "field_71412_D");
            String name = "gtmb-v0110-" + output.getName().replaceAll("[^A-Za-z0-9_-]", "_") + "-save-wait.png";
            File actual = new File(new File(game, "screenshots"), name);
            check(!actual.exists(), "fresh real save-wait screenshot path required");
            call(Class.forName("net.minecraft.util.ScreenShotHelper"), "func_148259_a", game, name, width, height, call(mc, "func_147110_a"));
            check(actual.isFile() && javax.imageio.ImageIO.read(actual) != null, "real RenderTick.END framebuffer PNG decodes");
            Files.copy(actual.toPath(), file("save-wait-render-end.png").toPath());
            saveWaitScreenshotDone = true; saveWaitScreenshotPending = false;
            Object screen = get(mc, "field_71462_r");
            proof.setProperty("saveWaitScreenshot", file("save-wait-render-end.png").getCanonicalPath());
            proof.setProperty("saveWaitScreenshotPhase", "RenderTick.END; driver phase " + phase);
            proof.setProperty("saveWaitScreenshotScreen", screen == null ? "world" : screen.getClass().getName());
            persist("RUNNING");
        } catch (Throwable failure) { fail(failure); }
    }

    @SubscribeEvent
    public void evidenceRender(TickEvent.RenderTickEvent event) {
        if (!client || done || mc == null || screenshotPending.isEmpty() || event.phase != TickEvent.Phase.END) return;
        try {
            Object current = get(mc, "field_71462_r");
            check(current == screenshotUi, "evidence capture retains exact naturally rendered screen");
            Object ui = call(current, "smokeLibraryUiState");
            check((Long) call(ui, "func_74763_f", "renderGeneration") > 0, "natural rendered UI required");
            String name = "gtmb-v0110-" + output.getName().replaceAll("[^A-Za-z0-9_-]", "_") + "-" + screenshotPending + ".png";
            File game = (File) get(mc, "field_71412_D");
            File actual = new File(new File(game, "screenshots"), name);
            check(!actual.exists() && !file(screenshotPending + ".png").exists(), "fresh natural screenshot required");
            call(Class.forName("net.minecraft.util.ScreenShotHelper"), "func_148259_a", game, name,
                get(mc, "field_71443_c"), get(mc, "field_71440_d"), call(mc, "func_147110_a"));
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(actual);
            check(image != null, "actual framebuffer decodes");
            Files.copy(actual.toPath(), file(screenshotPending + ".png").toPath());
            Object evidence = construct("net.minecraft.nbt.NBTTagCompound");
            call(evidence, "func_74778_a", "scope", "actual RenderTick.END framebuffer; MUI dispatch stimulus; no OS input");
            call(evidence, "func_74782_a", "ui", ui);
            for (Object[] color : new Object[][] { { "warning", 0xff7777 }, { "suggestion", 0x62e8ef }, { "information", 0x86baff }, { "next", 0x8bf5a2 } }) {
                int count = 0, rgb = (Integer) color[1];
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                    if ((image.getRGB(x, y) & 0xffffff) == rgb) count++;
                call(evidence, "func_74768_a", color[0] + "ExactPixels", count);
                proof.setProperty(screenshotPending + "." + color[0] + "ExactPixels", String.valueOf(count));
            }
            semanticPixels(current, ui, image, evidence);
            call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", evidence, file(screenshotPending + ".nbt"));
            screenshotDone = screenshotPending; screenshotPending = ""; screenshotUi = null;
            proof.setProperty("lastNaturalScreenshot", screenshotDone); persist("RUNNING");
        } catch (Throwable failure) { fail(failure); }
    }

    private void semanticPixels(Object gui, Object ui, java.awt.image.BufferedImage image, Object evidence) throws Exception {
        Class<?> diagnostics = clientType("HologramUiDiagnostics");
        float canvas = (Float) call(diagnostics, "readDiagnosticField", gui, "scale");
        int ox = (Integer) call(diagnostics, "readDiagnosticField", gui, "originX");
        int oy = (Integer) call(diagnostics, "readDiagnosticField", gui, "originY");
        double sx = image.getWidth() / ((Integer) get(gui, "field_146294_l")).doubleValue();
        double sy = image.getHeight() / ((Integer) get(gui, "field_146295_m")).doubleValue();
        Object controls = call(ui, "func_150295_c", "drawnControls", 10), rows = construct("net.minecraft.nbt.NBTTagList");
        boolean cyan148 = false;
        for (int i = 0; i < (Integer) call(controls, "func_74745_c"); i++) {
            Object control = call(controls, "func_150305_b", i);
            int accent = integer(control, "accent"); if (accent == 0 || !bool(control, "enabled")) continue;
            int rgb = (Integer) call(clientType("HologramUiAccent"), "color", accent) & 0xffffff;
            int x = (int) Math.round((ox + integer(control, "x") * canvas) * sx);
            int y = (int) Math.round((oy + integer(control, "y") * canvas) * sy);
            int w = (int) Math.round(integer(control, "w") * canvas * sx);
            int h = (int) Math.round(integer(control, "h") * canvas * sy);
            int edgeX = Math.max(2, (int) Math.ceil(2 * canvas * sx) + 1);
            int edgeY = Math.max(2, (int) Math.ceil(2 * canvas * sy) + 1);
            int top = pixels(image, x - 1, y - 1, x + w + 1, y + edgeY, rgb);
            int bottom = pixels(image, x - 1, y + h - edgeY, x + w + 1, y + h + 1, rgb);
            int left = pixels(image, x - 1, y + edgeY, x + edgeX, y + h - edgeY, rgb);
            int right = pixels(image, x + w - edgeX, y + edgeY, x + w + 1, y + h - edgeY, rgb);
            int text = pixels(image, x + edgeX + 1, y + edgeY + 1, x + w - edgeX - 1, y + h - edgeY - 1, rgb);
            Object row = construct("net.minecraft.nbt.NBTTagCompound");
            call(row, "func_74768_a", "control", integer(control, "id"));
            call(row, "func_74768_a", "rgb", rgb);
            call(row, "func_74783_a", "framebufferBounds", new int[] { x, y, w, h });
            call(row, "func_74783_a", "edgePixelsTopBottomLeftRight", new int[] { top, bottom, left, right });
            call(row, "func_74768_a", "interiorTextPixels", text); call(rows, "func_74742_a", row);
            if (integer(control, "id") == 148 && rgb == 0x62e8ef)
                cyan148 = top > 0 && bottom > 0 && left > 0 && right > 0 && text > 0;
        }
        call(evidence, "func_74782_a", "actualSemanticControlPixels", rows);
        if (screenshotPending.startsWith("saved-workspace")) {
            check(cyan148, "actual naturally rendered cyan148 label and all four solid border edges have cyan pixels");
            call(evidence, "func_74757_a", "actualCyan148TextAndFourEdges", true);
        }
    }

    private static int pixels(java.awt.image.BufferedImage image, int x0, int y0, int x1, int y1, int rgb) {
        int count = 0;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++)
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++)
                if ((image.getRGB(x, y) & 0xffffff) == rgb) count++;
        return count;
    }

    private boolean capture(Object gui, String name) {
        if (name.equals(screenshotDone)) return true;
        if (screenshotPending.isEmpty()) { screenshotPending = name; screenshotUi = gui; }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void largeLocalEdit(Object gui, Object ui, Object state, long generation) throws Exception {
        Class<?> renderer = clientType("HologramRenderer");
        if (phase == 70) {
            if (get(clientType("HologramClient"), "preparingSnapshot") != null || get(renderer, "previewState") != state) { baselineTicks = 0; return; }
            if (++baselineTicks < 10) return;
            check(((List<?>) get(state, "cells")).size() == sourceCells, "actual selected-scale captured virtual blueprint required");
            baselineState = state; baselineRevision = (Long) call(get(state, "data"), "func_74763_f", "planRevision");
            baselineRegions = new java.util.LinkedHashMap<Object, Object>((java.util.Map<Object, Object>) get(get(state, "regions"), "regions"));
            baselinePreview = new java.util.LinkedHashMap<Object, Object>((java.util.Map<Object, Object>) get(renderer, "previewRegions"));
            check(baselineRegions.size() >= 8 && baselinePreview.size() >= 8, "large naturally rendered baseline spans at least8 native16-cubed regions");
            if (!capture(gui, "large-baseline")) return;
            Class<?> network = Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork");
            originalReceiver = (java.util.function.Consumer<Object>) get(network, "receiver");
            check(originalReceiver != null, "actual product Netty receiver installed");
            final java.util.function.Consumer<Object> forward = originalReceiver;
            member(network, "receiver").set(null, new java.util.function.Consumer<Object>() {
                @Override public void accept(Object actual) {
                    try { if (!bool(actual, "progressOnly")) synchronized (observedPackets) { check(observedPackets.size() < 32, "bounded read-only Netty observations"); observedPackets.add(call(actual, "func_74737_b")); } }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                    forward.accept(actual);
                }
            });
            check(String.valueOf(get(((List<?>) get(state, "cells")).get(0), "wantId")).contains("chest"), "actual first captured edit cell is the source chest");
            Object preview = get(get(gui, "ui"), "preview");
            call(call(preview, "selection"), "click", 0, false); call(preview, "selected", 0);
            runtimeMetricSession = String.valueOf(get(state, "session")); runtimeMetricToken = 0;
            runtimeMetricsSnapshot("before-large-edit");
            call(preview, "dispatchEdit", "rotateCell", 0, new int[] { 0 });
            proof.setProperty("localEditStimulus", "production UiPreview.dispatchEdit rotateCell0 -> UiSession.send -> actual dedicated Netty; read-only receiver decorator forwards exact actual packet");
            lastGeneration = generation; advance(71); return;
        }
        synchronized (observedPackets) { packetEvidence.addAll(observedPackets); observedPackets.clear(); }
        if (state == baselineState) return;
        maxParsed = Math.max(maxParsed, (Integer) get(state, "parsedCells"));
        maxDirtyRegions = Math.max(maxDirtyRegions, (Integer) get(renderer, "lastSnapshotDirtyRegions"));
        if (get(renderer, "previewState") != baselineState) maxBuiltRegions = Math.max(maxBuiltRegions, (Integer) get(renderer, "lastPreviewBuiltRegions"));
        if (state == baselineState || (Long) call(get(state, "data"), "func_74763_f", "planRevision") <= baselineRevision
            || bool(get(state, "data"), "localPlanPending") || get(renderer, "previewState") != state) return;
        List<?> beforeCells = (List<?>) get(baselineState, "cells"), afterCells = (List<?>) get(state, "cells");
        if (beforeCells.get(0) == afterCells.get(0)) return;
        check(beforeCells.get(sourceCells - 1) == afterCells.get(sourceCells - 1), "remote cell model object retained across actual Netty delta");
        int reusedRegions = 0, reusedPreview = 0;
        java.util.Map<Object, Object> regions = (java.util.Map<Object, Object>) get(get(state, "regions"), "regions");
        java.util.Map<Object, Object> previews = (java.util.Map<Object, Object>) get(renderer, "previewRegions");
        for (java.util.Map.Entry<Object, Object> entry : baselineRegions.entrySet()) if (regions.get(entry.getKey()) == entry.getValue()) reusedRegions++;
        for (java.util.Map.Entry<Object, Object> entry : baselinePreview.entrySet()) if (previews.get(entry.getKey()) == entry.getValue()) reusedPreview++;
        check(reusedRegions >= baselineRegions.size() - 1, "one edited cell retains unrelated cell-region lists");
        check(reusedPreview > 0 && maxDirtyRegions <= 8 && maxBuiltRegions <= 8, "actual native preview reuses remote partitions");
        Object receipt = construct("net.minecraft.nbt.NBTTagCompound"), packets = construct("net.minecraft.nbt.NBTTagList");
        int wireRows = 0;
        for (Object packet : packetEvidence) {
            check(bool(packet, "partial") && "cellDelta".equals(str(packet, "snapshotKind")), "post-baseline production replies are cellDelta, never full geometry");
            check(integer(packet, "cellCount") == sourceCells && str(packet, "generation").equals(get(state, "session")), "actual delta carries exact generation and full cell count");
            int rows = (Integer) call(call(packet, "func_150295_c", "cells", 10), "func_74745_c");
            check(rows <= 2, "single local edit serializes at most dirty and prior-selected rows"); wireRows += rows;
            call(packets, "func_74742_a", packet);
        }
        check(!packetEvidence.isEmpty() && wireRows > 0 && maxParsed <= 2, "real Netty dirty rows parsed without full-state reparse");
        if (!capture(gui, "large-local-edit-result")) return;
        call(receipt, "func_74782_a", "actualNettyReplies", packets);
        call(receipt, "func_74768_a", "sourceCells", sourceCells);
        call(receipt, "func_74768_a", "baselineCellRegions", baselineRegions.size());
        call(receipt, "func_74768_a", "baselinePreviewRegions", baselinePreview.size());
        call(receipt, "func_74778_a", "cacheMetricScope", "actual cell/preview partition object identities before/after real Netty delta; counts are retained partitions, not per-access cache lookup hit rates");
        call(receipt, "func_74757_a", "productCacheLookupCountersAvailable", false);
        call(receipt, "func_74768_a", "remoteCellRegionListsReused", reusedRegions);
        call(receipt, "func_74768_a", "nativePreviewRegionsReused", reusedPreview);
        call(receipt, "func_74768_a", "maxObservedParsedCells", maxParsed);
        call(receipt, "func_74768_a", "maxObservedDirtyRegions", maxDirtyRegions);
        call(receipt, "func_74768_a", "maxObservedPreviewBuiltRegions", maxBuiltRegions);
        call(receipt, "func_74772_a", "baselineRevision", baselineRevision);
        call(receipt, "func_74772_a", "finalRevision", call(get(state, "data"), "func_74763_f", "planRevision"));
        call(receipt, "func_74778_a", "scope", "real selected-scale block server capture and virtual edit; actual dedicated Netty DTO observations; actual natural rendered preview region identity reuse; no FPS/TPS or world-list claim");
        call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", receipt, file("large-local-edit-native.nbt"));
        runtimeMetricsSnapshot("after-large-edit");
        restoreReceiver(); restorePreferences(); proof.setProperty("largeEditReceipt", "large-local-edit-native.nbt"); persist("PASS"); done = true; call(mc, "func_71400_g");
    }

    private void restoreReceiver() throws Exception {
        if (originalReceiver != null) { member(Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork"), "receiver").set(null, originalReceiver); originalReceiver = null; }
        if (transportReceiverOriginal != null) { member(Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork"), "receiver").set(null, transportReceiverOriginal); transportReceiverOriginal = null; }
    }

    @SuppressWarnings("unchecked")
    private void installTransportFailureWatch() throws Exception {
        Class<?> network = Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork");
        transportReceiverOriginal = (java.util.function.Consumer<Object>) get(network, "receiver");
        check(transportReceiverOriginal != null, "real production receiver required for read-only error observation");
        final java.util.function.Consumer<Object> forward = transportReceiverOriginal;
        member(network, "receiver").set(null, new java.util.function.Consumer<Object>() {
            @Override public void accept(Object actual) {
                try {
                    if (bool(actual, "transportError")) synchronized (transportErrors) {
                        if (transportErrors.size() == 8) transportErrors.remove(0);
                        transportErrors.add(call(actual, "func_74737_b"));
                    }
                } catch (Exception observationFailure) { observationFailure.printStackTrace(); }
                forward.accept(actual);
            }
        });
    }

    private void checkOwnedAreaTransportError() throws Exception {
        java.util.List<Object> observed = new java.util.ArrayList<Object>();
        synchronized (transportErrors) { observed.addAll(transportErrors); transportErrors.clear(); }
        if (areaTaskToken <= 0 || areaTaskCommand < 0 || areaTaskSession.isEmpty()) return;
        Class<?> tasks = clientType("HologramBackgroundTasks");
        Object witness = call(tasks, "actualTaskWitness");
        long current = (Long) call(witness, "func_74763_f", "token"), completed = (Long) call(witness, "func_74763_f", "completedToken");
        if (current != areaTaskToken && !(current == 0 && completed == areaTaskToken)) return;
        Object state = get(clientType("HologramClient"), "state");
        if (state == null || !areaTaskSession.equals(get(state, "session"))) return;
        String generation = str(get(state, "data"), "generation");
        if (!generation.isEmpty() && !areaTaskSession.equals(generation)) return;
        for (Object error : observed) {
            if (!bool(error, "transportError") || !areaTaskSession.equals(str(error, "session"))
                || !(Boolean) call(error, "func_150297_b", "uiSequence", 4)
                || (Long) call(error, "func_74763_f", "uiSequence") != areaTaskCommand) continue;
            String code = str(error, "errorCode");
            if (!(code.equals("snapshot.prepare") || code.equals("snapshot.encode")
                || code.equals("snapshot.queue") || code.equals("snapshot.budget"))) continue;
            Object receipt = construct("net.minecraft.nbt.NBTTagCompound");
            call(receipt, "func_74782_a", "actualTransportError", error);
            scalar(receipt, "trackedLocalTaskToken", areaTaskToken); scalar(receipt, "trackedCommand", areaTaskCommand);
            scalar(receipt, "trackedSession", areaTaskSession); scalar(receipt, "currentLocalGeneration", generation);
            scalar(receipt, "scope", "actual production error packet matched exact session+typed command and local owner token; server sends no separate generation field");
            call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", receipt, file("area-owned-transport-failure.nbt"));
            throw new IllegalStateException("Production area transport failed for tracked task: " + code + ": " + str(error, "errorMessage"));
        }
    }

    private void preflight(Object gui, Object ui, long generation) throws Exception {
        Object modules = get(gui, "ui"), preview = get(modules, "preview");
        if (preflight == 0) {
            // Set a nontrivial local selection/camera through production client models. The control clicks below use MUI dispatch.
            call(call(preview, "selection"), "click", 0, false);
            call(preview, "selected", 0); call(preview, "yaw", 37.25d); call(preview, "pitch", -17.5d);
            preflight++; lastGeneration = generation; return;
        }
        if (preflight == 1) { click(gui, ui, 146, generation); preflight++; return; }
        if (preflight == 2) {
            check(bool(ui, "promptDrawer"), "actual selected-cell prompt opened from 146");
            Object annotations = get(modules, "annotations");
            check((Integer) get(annotations, "promptScope") == 1, "146 prompt defaults to selected-cell scope");
            if (!capture(gui, "selected-cell-prompt")) return;
            click(gui, ui, 151, generation); preflight++; return;
        }
        if (preflight == 3) { click(gui, ui, 134, generation); preflight++; return; }
        if (preflight == 4) {
            check(bool(ui, "resetReview"), "actual reset review opened from 134");
            if (!capture(gui, "reset-default-review")) return;
            click(gui, ui, 136, generation); preflight++; return;
        }
        check(!bool(ui, "resetReview") && !bool(ui, "promptDrawer"), "real review dismissal returned to workspace");
        check(((int[]) call(call(preview, "selection"), "indices")).length == 1 && (Integer) call(preview, "selected") == 0,
            "selected-cell survives prompt and reset review");
        proof.setProperty("controls146And134", "actual drawn enabled MUI controls; selected-cell prompt scope1; reset review opened and cancelled, no reset commit claim");
        preflight = 6;
    }

    private void postSave(Object gui, Object ui, long generation) throws Exception {
        Object library = get(get(gui, "ui"), "library");
        if (phase == 12) {
            if (!(Boolean) call(library, "saveGuidanceOpen")) return;
            check((Integer) call(library, "savedStructureAccent", 148) == 0xff62e8ef, "actual committed Save suggests structure148 in cyan");
            if (!capture(gui, "save-structure-guidance")) return;
            clickGuidance(gui, false);
            guidanceGeneration = generation; lastGeneration = generation; advance(13); return;
        }
        if (phase == 13) {
            if (generation <= guidanceGeneration) return;
            check(!(Boolean) call(library, "saveGuidanceOpen"), "actual Know MUI button dismisses guidance");
            check(!readPreferences().containsKey("saveGuidanceSuppressed"), "Know does not persist suppression");
            check((Integer) call(library, "savedStructureAccent", 148) == 0xff62e8ef, "Know retains cyan148 suggestion");
            if (!capture(gui, "saved-workspace-cyan148")) return;
            typeName(gui, "V0110 real background save " + output.getName() + " preferences");
            runtimeMetricToken = 0; runtimeMetricsSnapshot("before-second-save");
            click(gui, ui, 140, generation);
            secondSaveTaskToken = (Long) call(clientType("HologramBackgroundTasks"), "token");
            runtimeMetricToken = secondSaveTaskToken;
            check(secondSaveTaskToken > saveTaskToken, "second actual Save receives fresh owner token");
            proof.setProperty("secondSaveTaskTokenAfter140", String.valueOf(secondSaveTaskToken));
            advance(14); return;
        }
        if (phase == 14) {
            if (bool(ui, "aeReview")) { click(gui, ui, 181, generation); return; }
            if (busy()) return;
            Object task = call(clientType("HologramBackgroundTasks"), "actualTaskWitness");
            check((Long) call(task, "func_74763_f", "completedToken") == secondSaveTaskToken && (Boolean) call(clientType("HologramBackgroundTasks"), "succeeded"),
                "second real Save completed with exact owner token");
            Object witness = call(clientType("HologramLibraryClient"), "actualSaveWitness");
            check("saveCommitted".equals(str(call(witness, "func_74775_l", "finalOutcome"), "op")) && (Boolean) call(witness, "func_150297_b", "ownerDiskAck", 10),
                "second explanation follows real owner disk ACK and Save commit");
            call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", witness, file("second-save-actual-witness.nbt"));
            Object actualLibrary = get(clientType("HologramLibraryClient"), "INSTANCE");
            String localId = (String) call(actualLibrary, "selected", false);
            File pairRoot = new File((File) get(mc, "field_71412_D"), "MiaoBlueprints");
            check(!localId.isEmpty() && new File(pairRoot, localId + ".json").isFile() && new File(pairRoot, localId + ".png").isFile(), "second actual Save writes complete local pair");
            proof.setProperty("secondSaveLocalId", localId);
            if (!(Boolean) call(library, "saveGuidanceOpen")) return;
            if (!capture(gui, "save-guidance-second-commit")) return;
            runtimeMetricsSnapshot("after-second-save");
            clickGuidance(gui, true);
            guidanceGeneration = generation; lastGeneration = generation; advance(15); return;
        }
        if (phase == 15) {
            if (generation <= guidanceGeneration) return;
            check(!(Boolean) call(library, "saveGuidanceOpen"), "actual DontShow MUI button dismisses guidance");
            check("true".equals(readPreferences().getProperty("saveGuidanceSuppressed")), "DontShow persists on disk");
            Object reread = construct("com.miaokatze.gtmb.client.hologram.HologramUiTabMemory", preferences);
            check((Boolean) call(reread, "saveGuidanceSuppressed"), "new production preferences instance reads DontShow");
            check((Integer) call(library, "savedStructureAccent", 148) == 0xff62e8ef, "DontShow retains cyan148 suggestion");
            if (!capture(gui, "saved-workspace-persisted-preference")) return;
            Object styles = construct("net.minecraft.nbt.NBTTagCompound");
            for (int rgb : new int[] { 0xffff7777, 0xff62e8ef, 0xff86baff, 0xff8bf5a2 }) {
                int text = (Integer) call(clientType("HologramUiAccent"), "textColor", rgb, true);
                int border = (Integer) call(clientType("HologramUiAccent"), "borderColor", rgb, true);
                check(text == rgb && border == rgb, "production semantic text and border share exact color");
                call(styles, "func_74768_a", Integer.toHexString(rgb), border);
            }
            check((Integer) get(clientType("HologramUiAccent"), "BORDER_WIDTH") == 2, "semantic solid border width is2");
            call(styles, "func_74778_a", "scope", "production semantic component method assertions; actual framebuffer pixel counts are separate evidence, no four-color visual PASS implied");
            call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", styles, file("semantic-component.nbt"));
            proof.setProperty("guidance", "two actual committed Save140 operations; first Know MUI click; second DontShow MUI click; disk persistence reread; cyan148 retained");
            restoreReceiver(); restorePreferences(); persist("PASS"); done = true; call(mc, "func_71400_g");
        }
    }

    private void clickGuidance(Object gui, boolean suppress) throws Exception {
        Object screen = call(gui, "getScreen"), area = call(screen, "getScreenArea");
        int width = (Integer) get(area, "width"), height = (Integer) get(area, "height");
        float scale = Math.min(1f, Math.min(width / 440f, height / 236f));
        int x = Math.round((width - 428 * scale) / 2f + (suppress ? 315 : 113) * scale);
        int y = Math.round((height - 224 * scale) / 2f + 198 * scale);
        call(clientType("HologramMuiProbeInput"), "click", gui, x, y, 0);
    }

    private Properties readPreferences() throws Exception {
        Properties disk = new Properties();
        if (preferences.isFile()) try (FileInputStream in = new FileInputStream(preferences)) { disk.load(in); }
        return disk;
    }

    private void preparePreferences() throws Exception {
        preferences = new File((File) get(mc, "field_71412_D"), "config/gtmb-hologram-ui.properties");
        preferencesExisted = preferences.isFile();
        originalPreferences = preferencesExisted ? Files.readAllBytes(preferences.toPath()) : new byte[0];
        preferencesCaptured = true;
        Files.write(file("ui-preferences-original.bin").toPath(), originalPreferences);
        Properties active = new Properties();
        if (preferencesExisted) try (FileInputStream in = new FileInputStream(preferences)) { active.load(in); }
        active.remove("saveGuidanceSuppressed");
        preferences.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(preferences)) { active.store(out, "temporary v0110 native QA; complete original restored on terminal cleanup"); }
        member(clientType("HologramUiMemory"), "tabMemory").set(null, null);
        proof.setProperty("originalPreferencesExisted", String.valueOf(preferencesExisted));
    }

    private void restorePreferences() throws Exception {
        if (!preferencesCaptured || preferencesRestored) return;
        if (mc != null && get(mc, "field_71462_r") != null) call(mc, "func_147108_a", (Object) null);
        if (preferencesExisted) Files.write(preferences.toPath(), originalPreferences);
        else Files.deleteIfExists(preferences.toPath());
        member(clientType("HologramUiMemory"), "tabMemory").set(null, null);
        preferencesRestored = true; proof.setProperty("preferencesRestored", "true");
    }

    /** Read-only phase5 observations. Diagnostic errors never advance/cancel the production workflow. */
    private void importClientDiagnostic(Object gui) {
        long now = System.nanoTime();
        if (lastImportClientDiagnostic != 0 && now - lastImportClientDiagnostic < 5000000000L) return;
        lastImportClientDiagnostic = now;
        if (!importWaitScreenshotDone) importWaitScreenshotPending = true;
        try {
            Object diagnostic = diagnosticRoot("client", now), state = get(clientType("HologramClient"), "state");
            Object summary = describe(state, "session", "parsedCells");
            if (state != null) {
                Object data = get(state, "data");
                copyScalarTags(data, summary, "session", "generation", "planRevision", "uiSequence", "areaSelecting",
                    "areaImported", "areaImporting", "capturePending", "status", "open", "openPurpose",
                    "targetClosed", "localPlanPending", "localPlanGeneration", "localPlanError");
                scalar(summary, "cells", ((List<?>) get(state, "cells")).size());
            }
            call(diagnostic, "func_74782_a", "state", summary);
            call(diagnostic, "func_74778_a", "screen", gui == null ? "world" : gui.getClass().getName());
            Class<?> tasks = clientType("HologramBackgroundTasks");
            call(diagnostic, "func_74782_a", "backgroundWitness", call(tasks, "actualTaskWitness"));
            call(diagnostic, "func_74782_a", "backgroundContext", describe(tasks, "session", "operation", "command",
                "issuedSequence", "startRevision", "boundToken", "completedToken", "cancelling", "restorePending",
                "succeeded", "completion"));
            call(diagnostic, "func_74782_a", "backgroundOwner", describe(get(tasks, "OWNER"), "kind", "key", "label",
                "token", "detached", "announced"));
            Object tracker = get(tasks, "displayProgress"), current;
            synchronized (tracker) { current = get(tracker, "current"); }
            call(diagnostic, "func_74782_a", "backgroundDisplayProgress", progressFields(current));
            Object progresses = construct("net.minecraft.nbt.NBTTagList");
            java.util.Map<?, ?> views = (java.util.Map<?, ?>) get(clientType("HologramClientProgress"), "views");
            int limit = 0;
            for (java.util.Map.Entry<?, ?> entry : views.entrySet()) {
                if (++limit > 16) break;
                Object row = describe(entry.getValue(), "session", "request", "sequence", "command");
                scalar(row, "scope", String.valueOf(entry.getKey()));
                tracker = get(entry.getValue(), "tracker");
                synchronized (tracker) { current = get(tracker, "current"); }
                call(row, "func_74782_a", "rawProgress", progressFields(current));
                call(progresses, "func_74742_a", row);
            }
            call(diagnostic, "func_74782_a", "rawProgressViews", progresses);
            call(diagnostic, "func_74782_a", "preparingSnapshot", describe(
                get(clientType("HologramClient"), "preparingSnapshot"), "stage", "cellPosition", "materialPosition",
                "memberPosition", "lastStepWork", "rows", "materialRows", "pages", "members"));
            scalar(diagnostic, "deferredSnapshots", get(clientType("HologramClient"), "deferredSnapshots"));
            scalar(diagnostic, "deferredCellRows", get(clientType("HologramClient"), "deferredCellRows"));
            Properties quick = new Properties();
            quick.setProperty("screen", gui == null ? "world" : gui.getClass().getName());
            quick.setProperty("statePresent", String.valueOf(state != null));
            if (state != null) {
                Object data = get(state, "data");
                quick.setProperty("session", String.valueOf(get(state, "session")));
                quick.setProperty("planRevision", String.valueOf(call(data, "func_74763_f", "planRevision")));
                quick.setProperty("uiSequence", String.valueOf(call(data, "func_74763_f", "uiSequence")));
                quick.setProperty("cells", String.valueOf(((List<?>) get(state, "cells")).size()));
                for (String key : new String[] { "areaImporting", "areaImported", "capturePending" })
                    quick.setProperty(key, String.valueOf(bool(data, key)));
            }
            writeImportDiagnostic("client", ++importClientDiagnostics, diagnostic, quick);
            if (!importWaitScreenshotDone) importWaitScreenshotPending = true;
        } catch (Exception failure) { importDiagnosticError("client", failure); }
    }

    private void importServerDiagnostic(Object host, Object world, List<?> livePlayers) {
        long now = System.nanoTime();
        if (lastImportServerDiagnostic != 0 && now - lastImportServerDiagnostic < 5000000000L) return;
        lastImportServerDiagnostic = now;
        try {
            Object diagnostic = diagnosticRoot("server", now);
            scalar(diagnostic, "triggerClientPhase", 5);
            Class<?> workspace = Class.forName("com.miaokatze.gtmb.hologram.HologramWorkspace");
            Object id = actor == null ? null : call(actor, "func_110124_au");
            Object session = id == null ? null : ((java.util.Map<?, ?>) get(workspace, "SESSIONS")).get(id);
            Object area = id == null ? null : ((java.util.Map<?, ?>) get(workspace, "AREAS")).get(id);
            Object editor = id == null ? null : ((java.util.Map<?, ?>) get(workspace, "AREA_EDITORS")).get(id);
            scalar(diagnostic, "livePlayers", livePlayers.size());
            scalar(diagnostic, "actorPresent", actor != null);
            scalar(diagnostic, "actorLiveIdentity", actor != null && livePlayers.size() == 1 && livePlayers.get(0) == actor);
            call(diagnostic, "func_74782_a", "workspaceSession", sessionFields(session, world, livePlayers));
            call(diagnostic, "func_74782_a", "areaEditor", sessionFields(editor, world, livePlayers));
            Object areaState = describe(area, "selecting", "imported", "importing", "first", "second", "x", "y", "z",
                "minX", "minY", "minZ", "maxX", "maxY", "maxZ", "importPhase", "active", "discoveryIndex",
                "structureIndex", "membershipIndex", "declarationMemberDone", "declarationMemberTotal",
                "expandedNbt", "structures", "members", "edited", "discovery", "declarationSessions", "regions");
            if (area != null) {
                call(areaState, "func_74782_a", "blueprint", describe(get(area, "blueprint"), "schema", "cells", "palette", "structures"));
                Object preparation = get(area, "preparation");
                Object preparing = describe(preparation, "networkCursor");
                if (preparation != null) {
                    call(preparing, "func_74782_a", "progress", describe(get(preparation, "progress"), "phase", "done", "total"));
                    futureFields(preparing, "future", get(preparation, "task"));
                }
                call(areaState, "func_74782_a", "preparation", preparing);
                call(areaState, "func_74782_a", "population", describe(get(area, "population"), "sourceIndex", "cursor", "pass", "initialized"));
                call(areaState, "func_74782_a", "planTask", describe(get(area, "planTask"), "stage", "cursor", "lastWork", "needsExterior"));
                call(areaState, "func_74782_a", "stagingSession", sessionFields(get(area, "staging"), world, livePlayers));
            }
            call(diagnostic, "func_74782_a", "area", areaState);
            Object box = id == null ? null : ((java.util.Map<?, ?>) get(
                Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork"), "outgoing")).get(id);
            Object transport = describe(box, "session", "dimension", "pendingBytes", "snapshots");
            if (box != null) {
                Object head = ((java.util.Deque<?>) get(box, "snapshots")).peekFirst();
                Object headState = describe(head, "sequence", "libraryInitial", "failed", "offset", "index", "queueNotified");
                if (head != null) {
                    Object bytes = get(head, "bytes"); scalar(headState, "compressedBytes", bytes == null ? 0 : Array.getLength(bytes));
                    call(headState, "func_74782_a", "encoding", describe(get(head, "encoding"), "cancelled", "done", "failure"));
                }
                call(transport, "func_74782_a", "head", headState);
            }
            call(diagnostic, "func_74782_a", "transport", transport);
            Properties quick = new Properties();
            quick.setProperty("triggerClientPhase", "5");
            quick.setProperty("sessionPresent", String.valueOf(session != null));
            quick.setProperty("areaPresent", String.valueOf(area != null));
            if (session != null) for (String key : new String[] { "id", "status", "phase", "planRevision", "uiSequence", "capturePending", "cancelled" })
                quick.setProperty(key, String.valueOf(get(session, key)));
            if (area != null) for (String key : new String[] { "importPhase", "importing", "imported", "x", "y", "z", "structureIndex", "membershipIndex" })
                quick.setProperty("area." + key, String.valueOf(get(area, key)));
            writeImportDiagnostic("server", ++importServerDiagnostics, diagnostic, quick);
        } catch (Exception failure) { importDiagnosticError("server", failure); }
    }

    private Object sessionFields(Object session, Object world, List<?> livePlayers) throws Exception {
        Object row = describe(session, "id", "status", "phase", "progressPhase", "planRevision", "uiSequence", "open",
            "openPurpose", "cancelled", "capturePending", "captureAttempts", "virtual", "virtualPlacement", "slot",
            "toolId", "scope", "selected", "job", "cursor", "completed", "blueprintSource", "areaStructureId",
            "localPlanPending", "localPlanGeneration", "localPlanError", "blueprintClientTaskToken", "libraryInitialSnapshot");
        if (session == null) return row;
        Object player = get(session, "player");
        scalar(row, "ownerIsFixtureActor", player == actor);
        scalar(row, "ownerLiveIdentity", livePlayers.size() == 1 && livePlayers.get(0) == player);
        scalar(row, "ownerWorldIdentity", get(player, "field_70170_p") == world);
        scalar(row, "ownerDead", get(player, "field_70128_L"));
        scalar(row, "boundStackIsHeld", get(session, "boundTool") == call(player, "func_70694_bm"));
        call(row, "func_74782_a", "capture", describe(get(session, "capture"), "cells", "positions", "pending",
            "incomplete", "failure", "failureOrigin", "pieceCursor", "walkingComplete"));
        Object task = ((java.util.Map<?, ?>) get(Class.forName("com.miaokatze.gtmb.hologram.HologramSnapshotPreparation"), "TASKS")).get(session);
        Object snapshot = describe(task, "session", "phase", "revision", "ui", "batch", "size", "stage", "index",
            "encoding", "lastTick", "lastDetail", "cells", "materials", "recovery", "workTotal", "missing", "total");
        if (task != null) {
            Object metadata = get(task, "metadata");
            Object metadataState = describe(metadata, "stage", "cursor", "revision", "draftOnly", "keysCached", "sourceWarning");
            if (metadata != null) {
                call(metadataState, "func_74782_a", "source", describe(get(metadata, "source"), "stage", "cursor", "index"));
                call(metadataState, "func_74782_a", "workerProgress", describe(get(get(metadata, "workerProgress"), "value"), "phase", "done", "total"));
                futureFields(metadataState, "report", get(metadata, "report"));
            }
            call(snapshot, "func_74782_a", "metadata", metadataState);
        }
        call(row, "func_74782_a", "snapshotPreparation", snapshot);
        return row;
    }

    private Object diagnosticRoot(String sideName, long now) throws Exception {
        Object row = construct("net.minecraft.nbt.NBTTagCompound");
        scalar(row, "scope", "read-only phase5 diagnostic; scalar fields and collection counts only; no world reads, commands, ACK or native actions");
        scalar(row, "side", sideName); scalar(row, "observedAtNanos", now);
        scalar(row, "driverPhase", phase); scalar(row, "phaseMillis", (now - phaseStarted) / 1000000L);
        scalar(row, "thread", Thread.currentThread().getName()); return row;
    }

    private static Object progressFields(Object progress) throws Exception {
        return describe(progress, "operation", "phase", "detail", "done", "total", "unit", "complete", "startedAt", "observedAt");
    }

    private static Object describe(Object source, String... fields) throws Exception {
        Object row = construct("net.minecraft.nbt.NBTTagCompound");
        scalar(row, "present", source != null);
        if (source == null) return row;
        scalar(row, "class", source instanceof Class ? ((Class<?>) source).getName() : source.getClass().getName());
        for (String name : fields) {
            try { scalar(row, name, get(source, name)); }
            catch (NoSuchFieldException missing) { scalar(row, name + "Unavailable", true); }
        }
        return row;
    }

    private static void scalar(Object target, String key, Object value) throws Exception {
        if (value == null) { call(target, "func_74757_a", key + "Present", false); return; }
        if (value instanceof Boolean) call(target, "func_74757_a", key, value);
        else if (value instanceof Long) call(target, "func_74772_a", key, value);
        else if (value instanceof Number) call(target, "func_74768_a", key, ((Number) value).intValue());
        else if (value instanceof String || value instanceof Enum || value instanceof Throwable) {
            String text = String.valueOf(value); call(target, "func_74778_a", key, text.length() > 2048 ? text.substring(0, 2048) : text);
        } else if (value instanceof java.util.Collection) call(target, "func_74768_a", key + "Count", ((java.util.Collection<?>) value).size());
        else if (value instanceof java.util.Map) call(target, "func_74768_a", key + "Count", ((java.util.Map<?, ?>) value).size());
        else if (value instanceof int[] && ((int[]) value).length <= 64) call(target, "func_74783_a", key, ((int[]) value).clone());
        else if (value.getClass().getName().equals("net.minecraft.nbt.NBTTagList")) call(target, "func_74768_a", key + "Count", call(value, "func_74745_c"));
        else call(target, "func_74778_a", key + "Class", value.getClass().getName());
    }

    private static void copyScalarTags(Object source, Object target, String... keys) throws Exception {
        for (String key : keys) {
            Object tag = call(source, "func_74781_a", key);
            if (tag != null) {
                int type = ((Number) call(tag, "func_74732_a")).intValue();
                if (type >= 1 && type <= 8 && type != 7) call(target, "func_74782_a", key, call(tag, "func_74737_b"));
            }
        }
    }

    private static void futureFields(Object target, String key, Object future) throws Exception {
        scalar(target, key + "Present", future != null);
        if (future instanceof java.util.concurrent.Future) {
            scalar(target, key + "Done", ((java.util.concurrent.Future<?>) future).isDone());
            scalar(target, key + "Cancelled", ((java.util.concurrent.Future<?>) future).isCancelled());
        }
    }

    private void writeImportDiagnostic(String sideName, int index, Object diagnostic, Properties quick) throws Exception {
        String prefix = String.format(java.util.Locale.ROOT, "area-%s-wait-%03d", sideName, index);
        call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", diagnostic, file(prefix + ".nbt"));
        quick.setProperty("scope", "read-only phase5 diagnostic; no production mutation or network action");
        quick.setProperty("diagnostic", prefix + ".nbt"); quick.setProperty("observedAtNanos", String.valueOf(System.nanoTime()));
        store(prefix + ".properties", quick); store("area-" + sideName + "-wait-latest.properties", quick);
    }

    private void importDiagnosticError(String sideName, Exception failure) {
        failure.printStackTrace();
        try {
            Properties error = new Properties(); error.setProperty("scope", "diagnostic failure only; production workflow/assertions unchanged");
            error.setProperty("failure", failure.toString()); error.setProperty("driverPhase", String.valueOf(phase));
            store("area-" + sideName + "-diagnostic-error.properties", error);
        } catch (Exception outputFailure) { outputFailure.printStackTrace(); }
    }

    @SubscribeEvent
    public void importWaitRender(TickEvent.RenderTickEvent event) {
        if (!client || done || mc == null || !importWaitScreenshotPending || importWaitScreenshotDone || event.phase != TickEvent.Phase.END) return;
        importWaitScreenshotPending = false;
        try {
            File game = (File) get(mc, "field_71412_D");
            String name = "gtmb-v0110-" + output.getName() + "-area-import-wait.png";
            File actual = new File(new File(game, "screenshots"), name);
            check(!actual.exists() && !file("area-import-wait-render-end.png").exists(), "fresh real import-wait screenshot required");
            call(Class.forName("net.minecraft.util.ScreenShotHelper"), "func_148259_a", game, name,
                get(mc, "field_71443_c"), get(mc, "field_71440_d"), call(mc, "func_147110_a"));
            check(javax.imageio.ImageIO.read(actual) != null, "actual import-wait framebuffer decodes");
            Files.copy(actual.toPath(), file("area-import-wait-render-end.png").toPath());
            Object screenshot = diagnosticRoot("client-render", System.nanoTime()), gui = get(mc, "field_71462_r");
            scalar(screenshot, "capture", "actual RenderTick.END framebuffer; no GUI construction, refresh, or native input");
            scalar(screenshot, "screen", gui == null ? "world" : gui.getClass().getName());
            call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", screenshot, file("area-import-wait-render-end.nbt"));
            importWaitScreenshotDone = true;
        } catch (Exception failure) { importWaitScreenshotDone = true; importDiagnosticError("client-render", failure); }
    }

    private void saveDiagnostic(Object gui) throws Exception {
        long now = System.nanoTime();
        if (lastSaveDiagnostic != 0 && now - lastSaveDiagnostic < 5000000000L) return;
        lastSaveDiagnostic = now;
        Object diagnostic = construct("net.minecraft.nbt.NBTTagCompound");
        Object library = get(clientType("HologramLibraryClient"), "INSTANCE");
        Object task = call(clientType("HologramBackgroundTasks"), "actualTaskWitness");
        Object saveState = call(clientType("HologramLibraryClient"), "actualSaveState");
        Object save = call(clientType("HologramLibraryClient"), "actualSaveWitness");
        String label = (String) call(clientType("HologramBackgroundTasks"), "label");
        String libraryStatus = (String) call(library, "status");
        call(diagnostic, "func_74772_a", "observedAtNanos", now);
        call(diagnostic, "func_74778_a", "taskLabel", label);
        call(diagnostic, "func_74778_a", "libraryStatus", libraryStatus);
        call(diagnostic, "func_74782_a", "actualTask", task);
        call(diagnostic, "func_74782_a", "actualSaveState", saveState);
        call(diagnostic, "func_74782_a", "actualSaveWitness", save);
        if (gui != null && gui.getClass().getName().equals("com.miaokatze.gtmb.client.hologram.HologramScreen"))
            call(diagnostic, "func_74782_a", "actualUi", call(gui, "smokeLibraryUiState"));
        Object state = get(clientType("HologramClient"), "state");
        if (state != null) {
            Object source = get(state, "data"), summary = construct("net.minecraft.nbt.NBTTagCompound");
            // Copy actual source fields without duplicating all 4096 cells every five seconds.
            for (String key : new String[] { "status", "session", "planRevision", "uiSequence", "blueprintSavePending",
                "blueprintSaved", "blueprintDirty", "blueprintOperation", "blueprintOperationSuccess", "aeSaveReview",
                "aeReportError", "aeReportBlocking", "blueprintProcessingComplete", "processingPresetOperation" }) {
                Object tag = call(source, "func_74781_a", key);
                if (tag != null) call(summary, "func_74782_a", key, call(tag, "func_74737_b"));
            }
            call(diagnostic, "func_74782_a", "actualSourceSummary", summary);
        }
        String name = String.format(java.util.Locale.ROOT, "save-wait-%03d.nbt", ++saveDiagnostics);
        call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", diagnostic, file(name));
        proof.setProperty("saveWaitDiagnostic", name);
        proof.setProperty("saveWaitTaskBusy", String.valueOf(bool(task, "busy")));
        proof.setProperty("saveWaitTaskToken", String.valueOf(call(task, "func_74763_f", "token")));
        proof.setProperty("saveWaitTaskLabel", label);
        proof.setProperty("saveWaitLibraryStatus", libraryStatus);
        proof.setProperty("saveWaitFinalOutcome", str(call(save, "func_74775_l", "finalOutcome"), "op"));
        if (!saveWaitScreenshotDone) saveWaitScreenshotPending = true;
        persist("RUNNING");
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (!server || done || event.phase != TickEvent.Phase.END) return;
        try {
            beginRuntimeMetrics();
            check(System.nanoTime() - started < overallBudgetNanos, "server overall timeout");
            Object host = call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C");
            Object world = call(host, "func_71218_a", 0);
            String name = String.valueOf(call(call(world, "func_72912_H"), "func_76065_j"));
            check(name.startsWith("hologram-v0110-"), "owned fresh named fixture world required");
            call(world, "func_72877_b", 6000L);
            if (backend == null) backend = call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryFacade"), "backend");
            if (backend == null || !(Boolean) call(backend, "available")) return;
            Object config = call(backend, "config");
            if (config == null) return;
            if (!configRequested) {
                originalUp = (Integer) get(config, "uploadKiB"); originalDown = (Integer) get(config, "downloadKiB"); originalMax = (Integer) get(config, "maxUploads");
                proof.setProperty("originalUploadKiB", String.valueOf(originalUp)); proof.setProperty("originalDownloadKiB", String.valueOf(originalDown)); proof.setProperty("originalQuota", String.valueOf(originalMax));
                // Use the actual level-4 console command and its normal worker/revision checks.
                libraryCommand(host, originalUp, largeEdit ? originalDown : 1, originalMax); configRequested = true;
                persist("RUNNING"); return;
            }
            if (!configured) {
                if ((Integer) get(config, "downloadKiB") != (largeEdit ? originalDown : 1)) return;
                configured = true; proof.setProperty("configApplied", "true"); proof.setProperty("activeDownloadKiB", String.valueOf(largeEdit ? originalDown : 1)); persist("RUNNING");
            }
            if (!fixtureReady) {
                if (!largeEdit || !chunksPrepared) {
                    call(Class.forName("com.miaokatze.gtmb.hologram.HologramCutChunks"), "load", world,
                        largeEdit ? -16 : 0, largeEdit ? -64 : 0, largeEdit ? sourceWidth + 32 : sourceWidth - 1, largeEdit ? sourceDepth + 72 : sourceDepth + 7);
                    chunksPrepared = true;
                }
                Object blocks = get(Class.forName("net.minecraft.block.Block"), "field_149771_c");
                Object stone = call(blocks, "func_82594_a", "minecraft:stone");
                if (largeEdit) {
                    int platformWidth = sourceWidth + 32, platformTotal = platformWidth * (sourceDepth + 72);
                    int endPlatform = Math.min(platformTotal, platformPlaced + 256);
                    while (platformPlaced < endPlatform) {
                        int n = platformPlaced++; put(world, n % platformWidth - 16, 79, n / platformWidth - 64, stone, 0);
                    }
                    if (platformPlaced < platformTotal) return;
                    // Source preparation may outlast startup: keep vanilla spawn fuzz completely outside the tower.
                    if (!safeLargeSpawnSet) {
                        call(call(world, "func_72912_H"), "func_76081_a", 8, 80, -40);
                        proof.setProperty("fixturePreparationSpawn", "8,80,-40; real padded platform before source placement");
                        safeLargeSpawnSet = true;
                    }
                } else if (placed == 0) for (int x = 0; x < sourceWidth; x++) for (int z = 0; z < sourceDepth + 8; z++) put(world, x, 79, z, stone, 0);
                Random random = new Random(19020L + placed);
                Object[] palette = { stone, call(blocks, "func_82594_a", "minecraft:wool"), call(blocks, "func_82594_a", "minecraft:stained_glass") };
                int end = Math.min(sourceCells, placed + 256);
                while (placed < end) {
                    int n = placed++, x = n % sourceWidth, z = n / sourceWidth % sourceDepth, y = 80 + n / (sourceWidth * sourceDepth);
                    int kind = random.nextInt(3);
                    put(world, x, y, z, palette[kind], kind == 0 ? 0 : random.nextInt(16));
                }
                if (placed < sourceCells) return;
                put(world, 0, 80, 0, call(blocks, "func_82594_a", "minecraft:chest"), 2);
                call(call(world, "func_72912_H"), "func_76081_a", 8, 80, largeEdit ? -6 : 21);
                proof.setProperty("fixtureStandardSpawn", "8,80," + (largeEdit ? -6 : 21) + "; WorldInfo.setSpawnPosition");
                fixtureReady = true;
            }
            List<?> players = (List<?>) get(call(host, "func_71203_ab"), "field_72404_b");
            check(players.size() <= 1, "single owned actor required");
            if (!players.isEmpty() && actor == null) {
                actor = players.get(0);
                call(get(actor, "field_71135_a"), "func_147364_a", 8.5d, 80d, largeEdit ? -5.5d : 21.5d, 180f, -20f);
                // The normal player-movement path updates PlayerManager watches and its chunk send queues.
                call(call(host, "func_71203_ab"), "func_72358_d", actor);
                proof.setProperty("fixturePostTeleportChunkWatchUpdate", "standard ServerConfigurationManager.func_72358_d");
                Object inventory = get(actor, "field_71071_by");
                Object item = call(get(Class.forName("net.minecraft.item.Item"), "field_150901_e"), "func_82594_a", "gtmb:neko_hologram_projector");
                Array.set(get(inventory, "field_70462_a"), 0, construct("net.minecraft.item.ItemStack", item)); set(inventory, "field_70461_c", 0);
                call(get(actor, "field_71069_bz"), "func_75142_b");
                proof.setProperty("world", name); proof.setProperty("actualSourceBlocks", String.valueOf(sourceCells));
                store("background-fixture-ready.properties", proof);
            }
            Properties peer = read("background-client.properties");
            serverClientPhase = Integer.parseInt(peer.getProperty("phase", "0"));
            try {
                runtimeMetricToken = Long.parseLong(peer.getProperty(serverClientPhase <= 5 ? "areaTaskToken" : serverClientPhase >= 14 ? "secondSaveTaskTokenAfter140" : serverClientPhase >= 10 && serverClientPhase <= 13 ? "saveTaskTokenAfter140" : "unavailableTaskToken", "0"));
            } catch (Exception diagnosticFailure) { runtimeMetricToken = 0; proof.setProperty("runtimeMetricsFailure", diagnosticFailure.toString()); }
            observeServerWorkStages(world, players);
            if ("5".equals(peer.getProperty("phase"))) importServerDiagnostic(host, world, players);
            String status = peer.getProperty("status", "");
            if ("PASS".equals(status) || "FAIL".equals(status)) {
                if (verdict.isEmpty()) verdict = status;
                if (largeEdit) {
                    check((Integer) call(world, "func_72805_g", 0, 80, 0) == 2, "virtual rotation never changes actual source chest metadata");
                    proof.setProperty("actualSourceChestUnchanged", "true");
                }
                if (!restoreRequested) { libraryCommand(host, originalUp, originalDown, originalMax); restoreRequested = true; return; }
                if ((Integer) get(config, "uploadKiB") != originalUp || (Integer) get(config, "downloadKiB") != originalDown || (Integer) get(config, "maxUploads") != originalMax) return;
                proof.setProperty("configRestored", "true"); proof.setProperty("clientVerdict", verdict); persist("COMPLETE"); done = true; call(host, "func_71263_m"); return;
            }
            pulse(null);
        } catch (Throwable failure) { fail(failure); }
    }

    private static void libraryCommand(Object host, int up, int down, int quota) throws Exception {
        call(construct("com.miaokatze.gtmb.hologram.HologramLibraryCommand"), "func_71515_b", host,
            new String[] { "set", String.valueOf(up), String.valueOf(down), String.valueOf(quota) });
    }
    private void areaPoint(int point, int x, int y, int z) throws Exception {
        check((Boolean) call(clientType("HologramClient"), "localAreaPoint", point, x, y, z, "replace"),
            "production local area point accepted");
    }
    private boolean displayedSource(Object gui, Object ui, Object state) throws Exception {
        return call(clientType("HologramUiDiagnostics"), "readDiagnosticField", gui, "state") == state
            && get(clientType("HologramRenderer"), "previewState") == state
            && get(state, "session").equals(str(ui, "session"))
            && call(get(state, "data"), "func_74763_f", "planRevision").equals(call(ui, "func_74763_f", "planRevision"));
    }
    private void click(Object gui, Object ui, int id, long generation) throws Exception {
        Object controls = call(ui, "func_150295_c", "drawnControls", 10); boolean enabled = false;
        for (int i = 0, n = (Integer) call(controls, "func_74745_c"); i < n; i++) {
            Object c = call(controls, "func_150305_b", i);
            if (integer(c, "id") == id) { enabled = bool(c, "enabled"); break; }
        }
        check(enabled, "visible enabled production control " + id);
        call(gui, "smokeControlPoint", id);
        call(clientType("HologramMuiProbeInput"), "control", gui, id);
        proof.setProperty("lastControl", String.valueOf(id)); lastGeneration = generation;
    }
    private void typeName(Object gui, String name) throws Exception {
        Class<?> diagnostics = clientType("HologramUiDiagnostics");
        Object field = call(diagnostics, "readDiagnosticField", gui, "presetNameField");
        float scale = (Float) call(diagnostics, "readDiagnosticField", gui, "scale");
        int x = (Integer) call(diagnostics, "readDiagnosticField", gui, "originX") + Math.round(((Integer) get(field, "xPosition") + 4) * scale);
        int y = (Integer) call(diagnostics, "readDiagnosticField", gui, "originY") + Math.round(((Integer) get(field, "yPosition") + 7) * scale);
        call(clientType("HologramMuiProbeInput"), "click", gui, x, y, 0);
        check((Boolean) call(field, "isFocused"), "actual name field focused");
        call(clientType("HologramMuiProbeInput"), "key", gui, (char) 1, 30);
        for (char character : name.toCharArray()) call(clientType("HologramMuiProbeInput"), "key", gui, character, 0);
        check(name.equals(call(field, "getText")), "actual keyboard entered exact blueprint name");
    }
    private static void put(Object world, int x, int y, int z, Object block, int meta) throws Exception {
        call(world, "func_147465_d", x, y, z, block, meta, 3);
        check(call(world, "func_147439_a", x, y, z) == block, "actual source block applied");
    }
    private static Class<?> clientType(String name) throws Exception { return Class.forName("com.miaokatze.gtmb.client.hologram." + name); }
    private static boolean busy() throws Exception { return (Boolean) call(clientType("HologramBackgroundTasks"), "busy"); }
    private static boolean bool(Object nbt, String key) throws Exception { return (Boolean) call(nbt, "func_74767_n", key); }
    private static int integer(Object nbt, String key) throws Exception { return (Integer) call(nbt, "func_74762_e", key); }
    private static String str(Object nbt, String key) throws Exception { return (String) call(nbt, "func_74779_i", key); }
    private void advance(int next) throws Exception {
        long now = System.nanoTime();
        runtimeMetricsSnapshot("phase-" + phase + "-end");
        timings.setProperty("driver.phase." + phase + ".elapsedNanos", String.valueOf(now - phaseStarted));
        phase = next; phaseStarted = now;
        writeMeasurements(); persist("RUNNING");
    }
    private void pulse(Object gui) throws Exception {
        if (System.nanoTime() - heartbeat < 2000000000L) return;
        heartbeat = System.nanoTime(); proof.setProperty("phase", String.valueOf(phase)); proof.setProperty("phaseMillis", String.valueOf((heartbeat - phaseStarted) / 1000000));
        if (client) proof.setProperty("screen", gui == null ? "world" : gui.getClass().getName());
        persist("RUNNING");
    }
    private void fail(Throwable failure) {
        while (failure instanceof java.lang.reflect.InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        failure.printStackTrace();
        try { if (client) { restoreReceiver(); restorePreferences(); } proof.setProperty("failure", failure.toString()); persist("FAIL"); } catch (Exception nested) { nested.printStackTrace(); }
        finally { finishRuntimeMetrics("FAIL"); }
        done = true;
        try {
            if (client && mc != null) call(mc, "func_71400_g");
            if (server) {
                // Failure cleanup uses the same production config path before stopping the owned server.
                Object host = call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C");
                if (configured && backend != null) {
                    Object config = call(backend, "config");
                    call(backend, "changeConfig", true, get(config, "revision"), originalUp, originalDown, originalMax);
                    proof.setProperty("configRestored", "true"); persist("FAIL");
                }
                call(host, "func_71263_m");
            }
        } catch (Exception cleanup) { cleanup.printStackTrace(); }
    }
    private File file(String name) { return new File(output, name); }
    private Properties read(String name) throws Exception {
        Properties p = new Properties();
        // Only these addon-owned properties receipts are polled here. REPLACE_EXISTING can expose
        // a brief missing pathname on Windows between the existence check and opening the file.
        // Missing observations are pending, subject to the unchanged phase and overall deadlines.
        if (file(name).isFile()) try (FileInputStream in = new FileInputStream(file(name))) { p.load(in); }
        catch (java.io.FileNotFoundException transientMissingReceipt) { return new Properties(); }
        return p;
    }
    private void persist(String status) throws Exception {
        proof.setProperty("status", status);
        if (!"RUNNING".equals(status)) {
            finishRuntimeMetrics(status);
            try { writeMeasurements(); }
            catch (Exception diagnosticFailure) { proof.setProperty("measurementFailure", diagnosticFailure.toString()); }
        }
        store(client ? "background-client.properties" : "background-server.properties", proof);
    }
    private void store(String name, Properties p) throws Exception {
        File temporary = file(name + ".tmp"); try (FileOutputStream out = new FileOutputStream(temporary)) { p.store(out, "GTMB real background Save stimulus"); }
        for (int attempt = 0; attempt < 10; attempt++) {
            try { Files.move(temporary.toPath(), file(name).toPath(), StandardCopyOption.REPLACE_EXISTING); return; }
            catch (java.nio.file.FileSystemException sharing) {
                if (attempt == 9) throw sharing;
                Thread.sleep(10L); // Only retry Windows receipt-file sharing, never hold a product task open.
            }
        }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    /** Test-only: one bounded reconnect before world entry, with zero actual product codec requests. */
    private boolean recoverPreLogin(Object gui,Object world,Object player)throws Exception {
        if(world!=null||player!=null) {
            enteredFixtureWorld=true;
            if(preLoginRetries==1)proof.setProperty("loginRecoverySucceeded","true");
            return false;
        }
        if(phase!=1||enteredFixtureWorld||gui==null||!gui.getClass().getName().equals("net.minecraft.client.gui.GuiDisconnected"))return false;
        Object sample=call(Class.forName("com.miaokatze.gtmb.hologram.HologramRuntimeMetrics"),"snapshot");
        check((Boolean)call(sample,"func_74767_n","enabled"),"pre-login recovery requires enabled real codec observation");
        Object network=call(sample,"func_74775_l","network");long requests=0;boolean observed=false;
        for(Object key:(Set<?>)call(network,"func_150296_c")) {
            Object row=call(network,"func_74775_l",key.toString());
            if((Boolean)call(row,"func_74764_b","c2sTxPackets")){observed=true;requests+=(Long)call(row,"func_74763_f","c2sTxPackets");}
        }
        check(observed&&requests==0,"pre-login recovery forbidden after any real product C2S packet");
        Properties evidence=new Properties();
        evidence.setProperty("failureDomain","pre-login-transport");evidence.setProperty("productLatencySample","false");
        evidence.setProperty("attempt",String.valueOf(preLoginRetries+1));evidence.setProperty("driverPhase",String.valueOf(phase));
        evidence.setProperty("observedAtNanos",String.valueOf(System.nanoTime()));evidence.setProperty("observedWallMillis",String.valueOf(System.currentTimeMillis()));
        evidence.setProperty("guiClass",gui.getClass().getName());evidence.setProperty("guiIdentity",String.valueOf(System.identityHashCode(gui)));
        Object reason=get(gui,"field_146304_f");String text=String.valueOf(call(reason,"func_150260_c"));
        evidence.setProperty("reason",text);evidence.setProperty("reasonComponent",String.valueOf(reason));
        evidence.setProperty("worldEverEntered",String.valueOf(enteredFixtureWorld));evidence.setProperty("productC2STxPackets",String.valueOf(requests));
        store("pre-login-disconnect-"+(preLoginRetries+1)+".properties",evidence);
        proof.setProperty("preLoginRetryLimit","1");proof.setProperty("preLoginTransportFailures",String.valueOf(preLoginRetries+1));
        if(preLoginRetries!=0) {
            proof.setProperty("failureDomain","pre-login-transport");proof.setProperty("productLatencySample","false");
            throw new IllegalStateException("second pre-login transport disconnect; no further retry: "+text);
        }
        proof.setProperty("firstPreLoginGuiClass",gui.getClass().getName());proof.setProperty("firstPreLoginReason",text);
        preLoginRetries=1;proof.setProperty("preLoginRetries","1");persist("RUNNING");
        String address=System.getProperty("gtmb.v0110.address","127.0.0.1:25678");
        check(address.equals("127.0.0.1:25678"),"pre-login retry uses only the existing owned loopback endpoint");
        Object fml=call(Class.forName("cpw.mods.fml.client.FMLClientHandler"),"instance");call(fml,"setupServerList");
        call(fml,"connectToServer",gui,construct("net.minecraft.client.multiplayer.ServerData","GTMB bounded pre-login recovery",address));
        return true;
    }

    private static Field member(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Object get(Object o, String name) throws Exception { return member(o instanceof Class ? (Class<?>) o : o.getClass(), name).get(o instanceof Class ? null : o); }
    private static void set(Object o, String name, Object value) throws Exception { member(o.getClass(), name).set(o, value); }
    private static Object construct(String name, Object... args) throws Exception {
        for (Constructor<?> c : Class.forName(name).getDeclaredConstructors()) if (compatible(c.getParameterTypes(), args)) { c.setAccessible(true); return c.newInstance(args); }
        throw new NoSuchMethodException(name + " constructor " + args.length);
    }
    private static Object call(Object o, String name, Object... args) throws Exception {
        Class<?> type = o instanceof Class ? (Class<?>) o : o.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods())
            if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) { m.setAccessible(true); return m.invoke(o instanceof Class ? null : o, args); }
        throw new NoSuchMethodException(type.getName() + "." + name + " args=" + args.length);
    }
    private static boolean compatible(Class<?>[] types, Object[] values) {
        if (types.length != values.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) { if (types[i].isPrimitive()) return false; continue; }
            Class<?> t = types[i];
            if (t.isPrimitive()) t = t == int.class ? Integer.class : t == long.class ? Long.class : t == float.class ? Float.class : t == double.class ? Double.class : t == boolean.class ? Boolean.class : t == char.class ? Character.class : t;
            if (!t.isInstance(values[i])) return false;
        }
        return true;
    }
}
