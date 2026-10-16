package gtmb.v019.probe;

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

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Disposable real-world Save stimulus. The production background observer owns the verdict. */
@Mod(modid = "gtmb_v019_background_save_probe", name = "GTMB background real Save stimulus", version = "1")
public final class V019BackgroundSaveProbe {
    private final Properties proof = new Properties();
    private File output;
    private Object mc, actor, backend;
    private boolean client, server, done, fixtureReady, configRequested, restoreRequested, configured;
    private int phase, ticks, stage, choiceStep, placed, originalUp, originalDown, originalMax;
    private long started, phaseStarted, lastGeneration = -1, heartbeat, saveTaskToken, lastSaveDiagnostic;
    private int saveDiagnostics;
    private boolean saveWaitScreenshotPending, saveWaitScreenshotDone;
    private String verdict = "";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        client = Boolean.getBoolean("gtmb.v019.backgroundClient");
        server = Boolean.getBoolean("gtmb.v019.backgroundServer");
        if (!client && !server) return;
        check(client != server, "one explicit fixture side required");
        File fixture = new File(System.getProperty("gtmb.hologram.followupRoot", "")).getCanonicalFile();
        check(fixture.getPath().replace('\\', '/').endsWith("/build/hologram-packaged-v018-mui"), "exact existing owned fixture required");
        check(new File(".").getCanonicalFile().equals(new File(fixture, client ? "client" : "server").getCanonicalFile()), "exact fixture working directory required");
        output = new File(System.getProperty("gtmb.v019.output", "")).getCanonicalFile();
        check(output.toPath().startsWith(new File(fixture.getParentFile(), "verification/render-v019").getCanonicalFile().toPath()) && output.isDirectory(), "precreated owned evidence directory required");
        check(!file(client ? "background-client.properties" : "background-server.properties").exists(), "fresh receipts required");
        check(!file("background-save-native.nbt").exists(), "fresh observer receipt required");
        if (client) {
            check(Boolean.getBoolean("gtmb.hologram.backgroundProbe") && Boolean.getBoolean("gtmb.hologram.previewCaptureWitness"), "production observer and native capture witness required");
            check(new File(System.getProperty("gtmb.hologram.backgroundProbeDir", "")).getCanonicalFile().equals(output), "observer must use exact same evidence directory");
            for (String key : new String[] { "gtmb.hologram.clientSmoke", "gtmb.hologram.sulfurLineClient", "gtmb.v019.ctmClient", "gtmb.v019.downloadClient", "gtmb.hologram.followupWorkbenchClient", "gtmb.hologram.followupProductionFlow", "gtmb.hologram.productionPreviewSmoke" })
                check(!Boolean.getBoolean(key), "exclusive stimulus driver: " + key);
        }
        proof.setProperty("scope", "real dedicated world 16-cubed block area; production area command packets and server capture; actual six-stage MUI input and Save with optional production AE review; observer independently verifies real JSON+PNG/owner ACK and disposed background panels; no OS input");
        proof.setProperty("pid", ManagementFactory.getRuntimeMXBean().getName());
        started = phaseStarted = System.nanoTime();
        FMLCommonHandler.instance().bus().register(this);
        persist("RUNNING");
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (!client || done || event.phase != TickEvent.Phase.END) return;
        try {
            check(System.nanoTime() - started < 900000000000L, "client overall timeout");
            check(System.nanoTime() - phaseStarted < (phase <= 1 ? 600000000000L : 240000000000L), "client phase timeout " + phase);
            Properties peer = read("background-server.properties");
            check(!"FAIL".equals(peer.getProperty("status")), "paired server failed: " + peer.getProperty("failure", ""));
            if (mc == null) {
                mc = call(Class.forName("net.minecraft.client.Minecraft"), "func_71410_x");
                set(get(mc, "field_71474_y"), "field_82881_y", false);
            }
            ticks++;
            Object player = get(mc, "field_71439_g"), world = get(mc, "field_71441_e");
            Object gui = get(mc, "field_71462_r");
            pulse(gui);
            if (phase == 10) saveDiagnostic(gui);
            if (phase == 0) {
                if (ticks < 30 || gui == null) return;
                String address = System.getProperty("gtmb.v019.address", "127.0.0.1:25678");
                check(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "explicit loopback address required");
                Object fml = call(Class.forName("cpw.mods.fml.client.FMLClientHandler"), "instance");
                call(fml, "setupServerList");
                call(fml, "connectToServer", gui, construct("net.minecraft.client.multiplayer.ServerData", "GTMB real background Save", address));
                advance(1); return;
            }
            if (world == null || player == null) return;
            check(!(Boolean) call(call(call(mc, "func_147114_u"), "func_147298_b"), "func_150731_c"), "actual dedicated Netty socket required");
            if (phase == 1) {
                if (!file("background-fixture-ready.properties").isFile() || !"true".equals(peer.getProperty("configApplied"))) return;
                Object held = call(player, "func_70694_bm");
                if (held == null || !call(held, "func_77973_b").getClass().getName().endsWith("ItemNekoHologramProjector")) return;
                Object sourceChunk = call(world, "func_72964_e", 0, 0);
                Object actorChunk = call(world, "func_72964_e", 0, 1);
                if (sourceChunk.getClass().getName().endsWith("EmptyChunk") || actorChunk.getClass().getName().endsWith("EmptyChunk")) return;
                check((Integer) get(sourceChunk, "field_76635_g") == 0 && (Integer) get(sourceChunk, "field_76647_h") == 0
                    && (Integer) get(actorChunk, "field_76635_g") == 0 && (Integer) get(actorChunk, "field_76647_h") == 1,
                    "actual client source and actor chunk coordinates required");
                if (!call(call(world, "func_147439_a", 0, 80, 0), "func_149739_a").toString().contains("chest")) return;
                proof.setProperty("actualSourceAndActorChunksLoaded", "true");
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
            if (phase == 2) {
                if (!bool(data, "areaSelecting")) return;
                areaPoint(state, 1, 0, 80, 0); advance(3); return;
            }
            if (phase == 3) {
                if (!Arrays.equals((int[]) call(data, "func_74759_k", "areaPoints"), new int[] { 0, 80, 0 })) return;
                areaPoint(state, 2, 15, 95, 15); advance(4); return;
            }
            if (phase == 4) {
                if (!Arrays.equals((int[]) call(data, "func_74759_k", "areaPoints"), new int[] { 0, 80, 0, 15, 95, 15 })) return;
                call(clientType("HologramClient"), "worldAction", "finishAreaSelection"); advance(5); return;
            }
            if (phase == 5) {
                if (!bool(data, "areaImported") || bool(data, "areaImporting") || bool(data, "capturePending") || busy()) return;
                check(((List<?>) get(state, "cells")).size() == 4096, "actual server capture has exactly 4096 source blocks");
                proof.setProperty("actualCapturedCells", "4096");
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
                persist("PASS"); done = true; call(mc, "func_71400_g"); return;
            }
            if (gui == null || !gui.getClass().getName().equals("com.miaokatze.gtmb.client.hologram.HologramScreen")) return;
            Object ui = call(gui, "smokeLibraryUiState");
            long generation = (Long) call(ui, "func_74763_f", "renderGeneration");
            if (generation <= lastGeneration || generation == 0 || (Boolean) call(gui, "smokePending")) return;
            if (phase != 10 && busy()) return;
            if (phase == 6) {
                if (!bool(ui, "blueprintTab") || !bool(ui, "library")) { click(gui, ui, 149, generation); return; }
                if (!displayedSource(gui, ui, state)) return;
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
                typeName(gui, "V019 background real world save"); lastGeneration = generation; advance(9);
            } else if (phase == 9) {
                if (!displayedSource(gui, ui, state)) return;
                click(gui, ui, 140, generation);
                saveTaskToken = (Long) call(clientType("HologramBackgroundTasks"), "token");
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
            String name = "gtmb-" + output.getParentFile().getName().replaceAll("[^A-Za-z0-9_-]", "_") + "-save-wait.png";
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
            check(System.nanoTime() - started < 900000000000L, "server overall timeout");
            Object host = call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C");
            Object world = call(host, "func_71218_a", 0);
            String name = String.valueOf(call(call(world, "func_72912_H"), "func_76065_j"));
            check(name.startsWith("hologram-cut-v020-"), "owned fresh named fixture world required");
            call(world, "func_72877_b", 6000L);
            if (backend == null) backend = call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryFacade"), "backend");
            if (backend == null || !(Boolean) call(backend, "available")) return;
            Object config = call(backend, "config");
            if (config == null) return;
            if (!configRequested) {
                originalUp = (Integer) get(config, "uploadKiB"); originalDown = (Integer) get(config, "downloadKiB"); originalMax = (Integer) get(config, "maxUploads");
                proof.setProperty("originalUploadKiB", String.valueOf(originalUp)); proof.setProperty("originalDownloadKiB", String.valueOf(originalDown)); proof.setProperty("originalQuota", String.valueOf(originalMax));
                // Use the actual level-4 console command and its normal worker/revision checks.
                libraryCommand(host, originalUp, 1, originalMax); configRequested = true;
                persist("RUNNING"); return;
            }
            if (!configured) {
                if ((Integer) get(config, "downloadKiB") != 1) return;
                configured = true; proof.setProperty("configApplied", "true"); proof.setProperty("activeDownloadKiB", "1"); persist("RUNNING");
            }
            if (!fixtureReady) {
                call(Class.forName("com.miaokatze.gtmb.hologram.HologramCutChunks"), "load", world, 0, 0, 15, 23);
                Object blocks = get(Class.forName("net.minecraft.block.Block"), "field_149771_c");
                Object stone = call(blocks, "func_82594_a", "minecraft:stone");
                if (placed == 0) for (int x = 0; x < 16; x++) for (int z = 0; z < 24; z++) put(world, x, 79, z, stone, 0);
                Random random = new Random(19020L + placed);
                Object[] palette = { stone, call(blocks, "func_82594_a", "minecraft:wool"), call(blocks, "func_82594_a", "minecraft:stained_glass") };
                int end = Math.min(4096, placed + 256);
                while (placed < end) {
                    int n = placed++, x = n & 15, z = n >> 4 & 15, y = 80 + (n >> 8);
                    int kind = random.nextInt(3);
                    put(world, x, y, z, palette[kind], kind == 0 ? 0 : random.nextInt(16));
                }
                if (placed < 4096) return;
                put(world, 0, 80, 0, call(blocks, "func_82594_a", "minecraft:chest"), 2);
                call(call(world, "func_72912_H"), "func_76081_a", 8, 80, 21);
                proof.setProperty("fixtureStandardSpawn", "8,80,21; WorldInfo.setSpawnPosition");
                fixtureReady = true;
            }
            List<?> players = (List<?>) get(call(host, "func_71203_ab"), "field_72404_b");
            check(players.size() <= 1, "single owned actor required");
            if (!players.isEmpty() && actor == null) {
                actor = players.get(0);
                call(get(actor, "field_71135_a"), "func_147364_a", 8.5d, 80d, 21.5d, 180f, -20f);
                // The normal player-movement path updates PlayerManager watches and its chunk send queues.
                call(call(host, "func_71203_ab"), "func_72358_d", actor);
                proof.setProperty("fixturePostTeleportChunkWatchUpdate", "standard ServerConfigurationManager.func_72358_d");
                Object inventory = get(actor, "field_71071_by");
                Object item = call(get(Class.forName("net.minecraft.item.Item"), "field_150901_e"), "func_82594_a", "gtmb:neko_hologram_projector");
                Array.set(get(inventory, "field_70462_a"), 0, construct("net.minecraft.item.ItemStack", item)); set(inventory, "field_70461_c", 0);
                call(get(actor, "field_71069_bz"), "func_75142_b");
                proof.setProperty("world", name); proof.setProperty("actualSourceBlocks", "4096");
                store("background-fixture-ready.properties", proof);
            }
            Properties peer = read("background-client.properties");
            String status = peer.getProperty("status", "");
            if ("PASS".equals(status) || "FAIL".equals(status)) {
                if (verdict.isEmpty()) verdict = status;
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
    private void areaPoint(Object state, int point, int x, int y, int z) throws Exception {
        Object packet = construct("net.minecraft.nbt.NBTTagCompound");
        call(packet, "func_74778_a", "action", "areaPoint"); call(packet, "func_74778_a", "session", get(state, "session"));
        call(packet, "func_74772_a", "planRevision", call(get(state, "data"), "func_74763_f", "planRevision"));
        for (Object[] v : new Object[][] { { "point", point }, { "x", x }, { "y", y }, { "z", z } }) call(packet, "func_74768_a", v[0], v[1]);
        call(packet, "func_74778_a", "mode", "replace");
        call(Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork"), "sendAction", packet);
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
    private void advance(int next) throws Exception { phase = next; phaseStarted = System.nanoTime(); persist("RUNNING"); }
    private void pulse(Object gui) throws Exception {
        if (System.nanoTime() - heartbeat < 2000000000L) return;
        heartbeat = System.nanoTime(); proof.setProperty("phase", String.valueOf(phase)); proof.setProperty("phaseMillis", String.valueOf((heartbeat - phaseStarted) / 1000000));
        if (client) proof.setProperty("screen", gui == null ? "world" : gui.getClass().getName());
        persist("RUNNING");
    }
    private void fail(Throwable failure) {
        while (failure instanceof java.lang.reflect.InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        failure.printStackTrace();
        try { proof.setProperty("failure", failure.toString()); persist("FAIL"); } catch (Exception nested) { nested.printStackTrace(); }
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
    private void persist(String status) throws Exception { proof.setProperty("status", status); store(client ? "background-client.properties" : "background-server.properties", proof); }
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
