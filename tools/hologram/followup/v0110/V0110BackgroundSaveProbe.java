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

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Disposable real-world Save stimulus. The production background observer owns the verdict. */
@Mod(modid = "gtmb_v0110_background_save_probe", name = "GTMB background real Save stimulus", version = "1")
public final class V0110BackgroundSaveProbe {
    private final Properties proof = new Properties();
    private File output;
    private Object mc, actor, backend;
    private boolean client, server, done, fixtureReady, configRequested, restoreRequested, configured;
    private int phase, ticks, stage, choiceStep, placed, originalUp, originalDown, originalMax;
    private long started, phaseStarted, lastGeneration = -1, heartbeat, saveTaskToken, lastSaveDiagnostic;
    private int saveDiagnostics;
    private boolean saveWaitScreenshotPending, saveWaitScreenshotDone;
    private String verdict = "";
    private int preflight;
    private boolean largeEdit, saveContextPrepared;
    private int side = 16, baselineTicks, maxParsed, maxDirtyRegions, maxBuiltRegions;
    private long baselineRevision;
    private Object baselineState;
    private java.util.Map<Object, Object> baselineRegions, baselinePreview;
    private java.util.function.Consumer<Object> originalReceiver;
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
        largeEdit = Boolean.getBoolean("gtmb.v0110.largeEdit"); side = largeEdit ? 32 : 16;
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
        proof.setProperty("scope", largeEdit ? "actual dedicated32768 source capture, production virtual rotateCell Netty command, observed delta and naturally rendered region reuse; no Save or FPS/TPS claim" : "real dedicated world 16-cubed block area; production area command packets and server capture; actual six-stage MUI input and Save with optional production AE review; observer independently verifies real JSON+PNG/owner ACK and disposed background panels; no OS input");
        proof.setProperty("mode", largeEdit ? "large32768-local-edit-no-save" : "real-background-save4096");
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
                preparePreferences();
            }
            ticks++;
            Object player = get(mc, "field_71439_g"), world = get(mc, "field_71441_e");
            Object gui = get(mc, "field_71462_r");
            pulse(gui);
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
            if (phase == 2) {
                if (!bool(data, "areaSelecting")) return;
                areaPoint(state, 1, 0, 80, 0); advance(3); return;
            }
            if (phase == 3) {
                if (!Arrays.equals((int[]) call(data, "func_74759_k", "areaPoints"), new int[] { 0, 80, 0 })) return;
                areaPoint(state, 2, side - 1, 79 + side, side - 1); advance(4); return;
            }
            if (phase == 4) {
                if (!Arrays.equals((int[]) call(data, "func_74759_k", "areaPoints"), new int[] { 0, 80, 0, side - 1, 79 + side, side - 1 })) return;
                call(clientType("HologramClient"), "worldAction", "finishAreaSelection"); advance(5); return;
            }
            if (phase == 5) {
                if (!bool(data, "areaImported") || bool(data, "areaImporting") || bool(data, "capturePending") || busy()) return;
                check(((List<?>) get(state, "cells")).size() == side * side * side, "actual server capture has exact source block count");
                proof.setProperty("actualCapturedCells", String.valueOf(side * side * side));
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
                click(gui, ui, 140, generation);
                proof.setProperty("saveName", "V0110 real background save " + output.getName());
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
            check(((List<?>) get(state, "cells")).size() == 32768, "actual large captured virtual blueprint required");
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
        check(beforeCells.get(32767) == afterCells.get(32767), "remote cell model object retained across actual Netty delta");
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
            check(integer(packet, "cellCount") == 32768 && str(packet, "generation").equals(get(state, "session")), "actual delta carries exact generation and full cell count");
            int rows = (Integer) call(call(packet, "func_150295_c", "cells", 10), "func_74745_c");
            check(rows <= 2, "single local edit serializes at most dirty and prior-selected rows"); wireRows += rows;
            call(packets, "func_74742_a", packet);
        }
        check(!packetEvidence.isEmpty() && wireRows > 0 && maxParsed <= 2, "real Netty dirty rows parsed without full-state reparse");
        if (!capture(gui, "large-local-edit-result")) return;
        call(receipt, "func_74782_a", "actualNettyReplies", packets);
        call(receipt, "func_74768_a", "remoteCellRegionListsReused", reusedRegions);
        call(receipt, "func_74768_a", "nativePreviewRegionsReused", reusedPreview);
        call(receipt, "func_74768_a", "maxObservedParsedCells", maxParsed);
        call(receipt, "func_74768_a", "maxObservedDirtyRegions", maxDirtyRegions);
        call(receipt, "func_74768_a", "maxObservedPreviewBuiltRegions", maxBuiltRegions);
        call(receipt, "func_74772_a", "baselineRevision", baselineRevision);
        call(receipt, "func_74772_a", "finalRevision", call(get(state, "data"), "func_74763_f", "planRevision"));
        call(receipt, "func_74778_a", "scope", "real32768-block server capture and virtual edit; actual dedicated Netty DTO observations; actual natural rendered preview region identity reuse; no FPS/TPS or world-list claim");
        call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74795_b", receipt, file("large-local-edit-native.nbt"));
        restoreReceiver(); restorePreferences(); proof.setProperty("largeEditReceipt", "large-local-edit-native.nbt"); persist("PASS"); done = true; call(mc, "func_71400_g");
    }

    private void restoreReceiver() throws Exception {
        if (originalReceiver != null) { member(Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork"), "receiver").set(null, originalReceiver); originalReceiver = null; }
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
            click(gui, ui, 140, generation);
            secondSaveTaskToken = (Long) call(clientType("HologramBackgroundTasks"), "token");
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
            restorePreferences(); persist("PASS"); done = true; call(mc, "func_71400_g");
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
                call(Class.forName("com.miaokatze.gtmb.hologram.HologramCutChunks"), "load", world, 0, 0, side - 1, side + 7);
                Object blocks = get(Class.forName("net.minecraft.block.Block"), "field_149771_c");
                Object stone = call(blocks, "func_82594_a", "minecraft:stone");
                if (placed == 0) for (int x = 0; x < side; x++) for (int z = 0; z < side + 8; z++) put(world, x, 79, z, stone, 0);
                Random random = new Random(19020L + placed);
                Object[] palette = { stone, call(blocks, "func_82594_a", "minecraft:wool"), call(blocks, "func_82594_a", "minecraft:stained_glass") };
                int end = Math.min(side * side * side, placed + 256);
                while (placed < end) {
                    int n = placed++, x = n % side, z = n / side % side, y = 80 + n / (side * side);
                    int kind = random.nextInt(3);
                    put(world, x, y, z, palette[kind], kind == 0 ? 0 : random.nextInt(16));
                }
                if (placed < side * side * side) return;
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
                proof.setProperty("world", name); proof.setProperty("actualSourceBlocks", String.valueOf(side * side * side));
                store("background-fixture-ready.properties", proof);
            }
            Properties peer = read("background-client.properties");
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
        try { if (client) { restoreReceiver(); restorePreferences(); } proof.setProperty("failure", failure.toString()); persist("FAIL"); } catch (Exception nested) { nested.printStackTrace(); }
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
