// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Native editor issue regression. Only a new marker-owned integrated save is loaded. */
public final class HologramEditorIssuesProbe {

    private final Path output = Paths.get(System.getProperty("gtmb.testlab.clientUiReceiptDir"));
    private final String run = UUID.fromString(System.getProperty("miaotest.host.runId"))
        .toString();
    private final long started = System.nanoTime();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final Map<String, Object> receipt = new LinkedHashMap<>();
    private volatile MinecraftServer server;
    private volatile Thread serverThread;
    private volatile boolean exactWorldOwned;
    private volatile String serverFailure;
    private Path save;
    private GuiScreen originalMenu;
    private HologramScreen screen;
    private Map<String, String> preferencesBefore;
    private int stage;
    private long frame, postFrame = -1, worldFrames;
    private long stageStarted = System.nanoTime(), lastProgress;
    private boolean launchedWorld, unloadRequested, complete;
    private String failure;

    private final com.miaokatze.gtmb.hologram.HologramEditorIssueFixture fixture = new com.miaokatze.gtmb.hologram.HologramEditorIssueFixture();
    private volatile java.util.UUID actor;
    private volatile boolean prepareRequested, fixturePrepared, restoreRequested, actorRestored;
    private volatile NBTTagCompound serverObservation;
    private final Map<String, Object> checks = new LinkedHashMap<>();
    private long taskBefore, sequenceBefore;
    private int stepTicks;
    private boolean inputSeen;
    private boolean undoRequested;

    static boolean install() {
        if (!Boolean.getBoolean("gtmb.testlab.editorIssues")) return false;
        HologramEditorIssuesProbe probe = new HologramEditorIssuesProbe();
        FMLCommonHandler.instance()
            .bus()
            .register(probe);
        MinecraftForge.EVENT_BUS.register(probe);
        return true;
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (complete || stage == 0 || event.phase != TickEvent.Phase.END) return;
        try {
            MinecraftServer actual = FMLCommonHandler.instance()
                .getMinecraftServerInstance();
            if (actual == null || actual.worldServerForDimension(0) == null) return;
            Path actualSave = actual.worldServerForDimension(0)
                .getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .toRealPath();
            require(
                save != null && save.toRealPath()
                    .equals(actualSave),
                "Exact owned save required");
            require(actual.isSinglePlayer(), "Expected integrated server");
            require(
                Boolean.TRUE.equals(
                    Class.forName("com.miaokatze.miaokatzecore.testlab.LabWorldPolicy")
                        .getMethod("owns", net.minecraft.world.World.class)
                        .invoke(null, actual.worldServerForDimension(0))),
                "Core ownership required");
            server = actual;
            serverThread = Thread.currentThread();
            exactWorldOwned = true;
            if (actor == null) return;
            for (Object entry : actual.getConfigurationManager().playerEntityList) {
                net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) entry;
                if (!actor.equals(player.getUniqueID())) continue;
                if (restoreRequested && !actorRestored) {
                    fixture.restore(player);
                    actorRestored = true;
                } else if (prepareRequested && !fixturePrepared) {
                    fixture.prepare(player);
                    fixturePrepared = true;
                }
                if (fixturePrepared) serverObservation = fixture.observe(player);
            }
        } catch (Throwable problem) {
            serverFailure = problem.toString();
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (complete || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            progress(mc);
            if (System.nanoTime() - started > 600_000_000_000L)
                throw new IllegalStateException("Editor issue timeout stage=" + stage);
            if (serverFailure != null) throw new IllegalStateException(serverFailure);
            if (stage != 0 && stage != 90 && (System.nanoTime() - stageStarted) > 90_000_000_000L)
                throw new IllegalStateException("Stage timeout " + stage);
            stepTicks++;
            if (stage == 0) {
                if (!HologramTestLabUiProfile.readyTitle(mc.currentScreen)) return;
                require(mc.theWorld == null && mc.getIntegratedServer() == null, "Refusing existing world");
                originalMenu = mc.currentScreen;
                preferencesBefore = preferences(mc);
                Files.createDirectories(output.resolve("screenshots"));
                Path parent = mc.mcDataDir.toPath()
                    .resolve("saves")
                    .toRealPath();
                save = parent.resolve("MiaoHost-" + run);
                require(
                    run.equals(
                        read(
                            mc.mcDataDir.toPath()
                                .resolve(".testlab-host/lease.json")).get("runId")
                                    .getAsString()),
                    "Exact lease required");
                Files.createDirectory(save);
                Map<String, Object> marker = new LinkedHashMap<>();
                marker.put("runId", run);
                marker.put("parent", parent.toString());
                Files.write(save.resolve(".miaotest-host-owner.json"), json(marker));
                System.setProperty("miaotest.host.worldPath", save.toString());
                receipt.put("ownedSave", save.toString());
                launchedWorld = true;
                next(1);
                mc.launchIntegratedServer(
                    save.getFileName()
                        .toString(),
                    "GTMB editor issue owned regression",
                    new WorldSettings(61312138L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
            } else if (stage == 1) {
                if (!exactWorldOwned || mc.thePlayer == null || mc.thePlayer.ticksExisted < 60) return;
                require(!mc.thePlayer.capabilities.isCreativeMode, "Survival actor required");
                actor = mc.thePlayer.getUniqueID();
                prepareRequested = true;
                next(2);
            } else if (stage == 2) {
                HologramState incoming = HologramClient.state;
                if (!fixturePrepared || incoming == null
                    || incoming.cells.size() != 3
                    || incoming.data.getBoolean("areaImporting")
                    || incoming.data.getBoolean("capturePending")) return;
                screen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen
                    : new HologramScreen(incoming);
                if (mc.currentScreen != screen) screen.openFrom(null);
                next(3);
            } else if (stage == 3) {
                if (postFrame < 0 || screen.ui.canvas.renderGeneration() == 0
                    || stepTicks < 5
                    || !screen.ui.preview.previewInteractive()
                    || !screen.ui.session.editable()) return;
                observe("real-import-ready");
                screenshot("gtmb-native-ui.png");
                selectCells(1);
                click("launcher.0");
                next(31);
            } else if (stage == 31) {
                if (stepTicks < 5 || screen.ui.shell.targetBounds("control.61") == null) return;
                taskBefore = HologramBackgroundTasks.token();
                sequenceBefore = screen.ui.session.sequence();
                click("control.61");
                next(4);
            } else if (stage == 4) {
                if (stepTicks < 10) return;
                check("inventory-opened", screen.ui.preview.inventory());
                check(
                    "inventory-open-no-background-task",
                    HologramBackgroundTasks.token() == taskBefore && !HologramBackgroundTasks.busy());
                check("inventory-open-request-dispatched", screen.ui.session.sequence() > sequenceBefore);
                observe("inventory-idle-before-close");
                screenshot("inventory-idle.png");
                click("config.inventory.close");
                next(5);
            } else if (stage == 5) {
                if (stepTicks < 5) return;
                check("idle-X-closes-only-inventory", mc.currentScreen == screen && !screen.ui.preview.inventory());
                reopen();
                click("control.61");
                next(6);
            } else if (stage == 6) {
                if (stepTicks < 8) return;
                HologramMuiProbeInput.key(screen, '\0', org.lwjgl.input.Keyboard.KEY_ESCAPE);
                inputSeen = true;
                next(7);
            } else if (stage == 7) {
                if (stepTicks < 5) return;
                check("idle-Esc-closes-only-inventory", mc.currentScreen == screen && !screen.ui.preview.inventory());
                reopen();
                click("control.61");
                next(8);
            } else if (stage == 8) {
                if (stepTicks < 8 || screen.ui.session.pendingSequence() >= 0) return;
                // A genuine scan request, never a manufactured busy flag. It may finish before the next frame.
                screen.ui.session.send("scan", new NBTTagCompound(), true);
                check("real-scan-busy-observed-at-input", HologramBackgroundTasks.busy());
                observe("real-scan-before-close");
                click("config.inventory.close");
                next(9);
            } else if (stage == 9) {
                if (stepTicks < 5) return;
                check("busy-X-closes-only-inventory", mc.currentScreen == screen && !screen.ui.preview.inventory());
                if (HologramBackgroundTasks.busy() || screen.ui.session.pendingSequence() >= 0) return;
                reopen();
                if (!screen.ui.preview.previewInteractive() || !screen.ui.session.editable()) return;
                selectTwo();
                check(
                    "two-native-cell-selection",
                    screen.ui.preview.selection()
                        .size() == 2);
                click("control.61");
                next(10);
            } else if (stage == 10) {
                if (stepTicks < 8 || screen.ui.session.pendingSequence() >= 0) return;
                selectTwo();
                check(
                    "native-selection-while-inventory-open",
                    screen.ui.preview.inventory() && screen.ui.preview.selection()
                        .size() == 2);
                // Native inventory sample and target action; real C2S and server receipts remain enabled.
                click("config.inventory.sample.28");
                next(11);
            } else if (stage == 11) {
                if (stepTicks < 3) return;
                click("control.65");
                next(12);
            } else if (stage == 12) {
                if (stepTicks < 8 || screen.ui.session.pendingSequence() >= 0) return;
                observe("after-native-multi-pin");
                check("multi-pin-two-targets", countTarget("minecraft:cobblestone") == 2);
                click("control.66");
                next(13);
            } else if (stage == 13) {
                if (stepTicks < 8 || screen.ui.session.pendingSequence() >= 0) return;
                observe("after-native-multi-restore");
                check("multi-restore-two-targets", countTarget("minecraft:cobblestone") == 0);
                HologramMuiProbeInput.key(screen, '\0', org.lwjgl.input.Keyboard.KEY_ESCAPE);
                next(14);
            } else if (stage == 14) {
                if (stepTicks < 5) return;
                if (screen.ui.session.state().cells.size() < 3 && !undoRequested) {
                    controlUndo();
                    undoRequested = true;
                    return;
                }
                if (screen.ui.session.pendingSequence() >= 0 || screen.ui.session.state().cells.size() < 3) return;
                reopen();
                if (!screen.ui.preview.previewInteractive() || !screen.ui.session.editable()) return;
                selectTwo();
                HologramMuiProbeInput.key(screen, '\0', org.lwjgl.input.Keyboard.KEY_DELETE);
                inputSeen = true;
                next(15);
            } else if (stage == 15) {
                if (stepTicks < 10 || screen.ui.session.pendingSequence() >= 0) return;
                observe("after-native-multi-delete");
                check("multi-delete-two-targets", screen.ui.session.state().cells.size() == 1);
                check(
                    "source-world-stone-unchanged",
                    serverObservation != null && serverObservation.getInteger("sourceStoneCount") == 3);
                screenshot("final-editor.png");
                next(90);
            }
            if (stage == 90) closeWorld(mc);
        } catch (Throwable problem) {
            if (failure == null) failure = problem.toString();
            next(90);
            try {
                closeWorld(mc);
            } catch (Throwable cleanup) {
                receipt.put("cleanupFailure", cleanup.toString());
            }
        }
    }

    private int countTarget(String id) {
        int count = 0;
        for (HologramState.Cell cell : screen.ui.session.state().cells) if (id.equals(cell.wantId)) count++;
        return count;
    }

    private void check(String name, boolean passed) {
        checks.put(name, passed);
        if (!passed && failure == null) failure = "Assertion failed: " + name;
    }

    private void next(int next) {
        transition(next);
        stepTicks = 0;
    }

    private void reopen() {
        if (Minecraft.getMinecraft().currentScreen != screen || screen.ui.preview.inventory()) {
            receipt.put("explicitUiResetAtStage" + stage, true);
            Minecraft.getMinecraft()
                .displayGuiScreen(null);
            screen = new HologramScreen(HologramClient.state);
            screen.openFrom(null);
        }
    }

    private void observe(String label) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("label", label);
        value.put("stage", stage);
        value.put("frame", frame);
        value.put(
            "snapshot",
            screen.ui.session.state()
                .fullData()
                .toString());
        value.put(
            "task",
            HologramBackgroundTasks.actualTaskWitness()
                .toString());
        value.put("inventoryOpen", screen.ui.preview.inventory());
        value.put("server", serverObservation == null ? null : serverObservation.toString());
        steps.add(value);
    }

    private void click(String target) throws Exception {
        Map<String, Object> hit = new LinkedHashMap<>();
        HologramMuiProbeInput.nativeTarget(screen, target, hit);
        hit.put("busyAtDispatch", HologramBackgroundTasks.busy());
        hit.put("stage", stage);
        steps.add(hit);
        inputSeen = true;
    }

    private void selectTwo() throws Exception {
        selectCells(2);
    }

    private void selectCells(int requested) throws Exception {
        HologramTestLabUiDiagnostics d = (HologramTestLabUiDiagnostics) screen.ui.diagnostics;
        java.util.Map<Integer, int[]> points = new LinkedHashMap<>();
        int[] viewport = screen.ui.shell.targetBounds("viewport");
        require(viewport != null, "Native scene bounds required");
        for (int y = viewport[1] + 10; y < viewport[1] + viewport[3] - 10 && points.size() < requested; y += 2)
            for (int x = viewport[0] + 10; x < viewport[0] + viewport[2] - 10 && points.size() < requested; x += 2) {
                int[] dialog = screen.ui.shell.targetBounds("config.inventory");
                if (screen.ui.preview.inventory() && dialog != null
                    && x >= dialog[0]
                    && y >= dialog[1]
                    && x < dialog[0] + dialog[2]
                    && y < dialog[1] + dialog[3]) continue;
                int[] local = screen.ui.shell.sceneLocalPoint(x, y);
                if (local == null) continue;
                int picked = HologramRenderer.pick(local[0], local[1]);
                if (picked >= 0 && (HologramRenderer.pick(local[0] - 3, local[1]) != picked
                    || HologramRenderer.pick(local[0] + 3, local[1]) != picked
                    || HologramRenderer.pick(local[0], local[1] - 3) != picked
                    || HologramRenderer.pick(local[0], local[1] + 3) != picked)) continue;
                if (picked >= 0) points.putIfAbsent(picked, new int[] { x, y });
            }
        receipt.put("nativeViewportAtStage" + stage, viewport);
        receipt.put("nativePickPointsAtStage" + stage, points);
        require(points.size() == requested, "Distinct rendered native cells required: " + requested);
        java.lang.reflect.Field capture = d.getClass()
            .getDeclaredField("captureRenderSmokeAction");
        java.lang.reflect.Field additive = d.getClass()
            .getDeclaredField("smokeCtrlSelection");
        capture.setAccessible(true);
        additive.setAccessible(true);
        boolean previousCapture = capture.getBoolean(d), previousAdditive = additive.getBoolean(d);
        boolean handles = screen.ui.preview.moveHandles();
        try {
            capture.setBoolean(d, true);
            screen.ui.preview.moveHandles(false);
            int n = 0;
            for (int[] point : points.values()) {
                additive.setBoolean(d, n++ > 0);
                HologramMuiProbeInput.click(screen, point[0], point[1], 0);
                Map<String, Object> selection = new LinkedHashMap<>();
                selection.put("nativeScenePoint", point);
                selection.put(
                    "selectedIndices",
                    screen.ui.preview.selection()
                        .indices());
                steps.add(selection);
            }
        } finally {
            capture.setBoolean(d, previousCapture);
            additive.setBoolean(d, previousAdditive);
            screen.ui.preview.moveHandles(handles);
        }
        require(
            screen.ui.preview.selection()
                .size() == requested,
            "Native selection count differs from requested " + requested);
    }

    private void controlUndo() throws Exception {
        java.lang.reflect.Field state;
        int index = org.lwjgl.input.Keyboard.KEY_LCONTROL;
        try {
            state = org.lwjgl.input.Keyboard.class.getDeclaredField("keyDownBuffer");
        } catch (NoSuchFieldException lwjgl3) {
            ClassLoader loader = org.lwjgl.input.Keyboard.class.getClassLoader();
            state = Class.forName("org.lwjglx.input.Keyboard", false, loader)
                .getField("sdlKeyPressedArray");
            index = (Integer) Class.forName("org.lwjglx.input.KeyCodes", false, loader)
                .getMethod("lwjglToSdlScancode", int.class)
                .invoke(null, index);
        }
        state.setAccessible(true);
        java.nio.ByteBuffer keys = (java.nio.ByteBuffer) state.get(null);
        byte previous = keys.get(index);
        try {
            keys.put(index, (byte) 1);
            require(
                org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL),
                "Native control modifier missing");
            HologramMuiProbeInput.key(screen, (char) 26, org.lwjgl.input.Keyboard.KEY_Z);
            inputSeen = true;
        } finally {
            keys.put(index, previous);
        }
    }

    private void screenshot(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        ScreenShotHelper.saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }

    @SubscribeEvent
    public void guiPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!complete && event.gui == screen) postFrame = frame;
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (!complete && event.phase == TickEvent.Phase.END) frame++;
    }

    @SubscribeEvent
    public void worldRender(RenderWorldLastEvent event) {
        if (!complete && exactWorldOwned) worldFrames++;
    }

    private void closeWorld(Minecraft mc) throws Exception {
        if (exactWorldOwned && fixturePrepared && !actorRestored) {
            restoreRequested = true;
            return;
        }
        if (!unloadRequested) {
            if (screen != null) {
                receipt.put(
                    "terminalTutorialDiagnostic",
                    screen.ui.tutorial.diagnosticState()
                        .toString());
                screen.ui.tutorial.closeWithoutProgress();
            }
            if (mc.theWorld != null) {
                require(exactWorldOwned && server == mc.getIntegratedServer(), "Refusing to unload an unowned world");
                mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld(null);
            }
            if (server != null) server.initiateShutdown();
            mc.displayGuiScreen(originalMenu);
            unloadRequested = true;
        }
        JsonObject environment = Files.isRegularFile(output.resolve("environment.json"))
            ? read(output.resolve("environment.json"))
            : null;
        boolean noServerCreated = !launchedWorld && server == null && mc.getIntegratedServer() == null;
        boolean stopped = noServerCreated || serverThread != null && !serverThread.isAlive()
            && environment != null
            && run.equals(
                environment.get("runId")
                    .getAsString())
            && environment.has("serverStopped")
            && environment.get("serverStopped")
                .getAsBoolean()
            && !environment.get("registered")
                .getAsBoolean();
        if (!stopped || mc.theWorld != null || mc.thePlayer != null) return;
        complete = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        boolean cleanupPending = mc.currentScreen != originalMenu || HologramLocalPlacement.INSTANCE.active();
        if (cleanupPending && failure == null) failure = "Original menu or local preview cleanup was not restored";
        boolean preferencesUnchanged = preferencesBefore != null && preferencesBefore.equals(preferences(mc));
        if (!preferencesUnchanged && failure == null) failure = "Tutorial preference bytes changed";
        receipt.put("schema", "gtmb-editor-issues-native-world-tutorial-v1");
        receipt.put("runId", run);
        receipt.put("outcome", failure == null ? "PASS" : "FAIL");
        receipt.put("failure", failure);
        receipt.put("steps", steps);
        receipt.put("nativeWorldFrames", worldFrames);
        receipt.put("worldStopped", true);
        receipt.put("actorInventoryRestored", actorRestored || !fixturePrepared);
        receipt.put("noServerCreated", noServerCreated);
        receipt.put(
            "environmentUnregistered",
            noServerCreated || environment != null && !environment.get("registered")
                .getAsBoolean());
        receipt.put("exactWorldPolicyObserved", exactWorldOwned);
        receipt.put("serverThreadAlive", serverThread != null && serverThread.isAlive());
        receipt.put("screenRestored", mc.currentScreen == originalMenu);
        receipt.put("cleanupPending", cleanupPending);
        receipt.put("worldDirectoryCleanup", "RUNNER_PENDING; exact marker-owned new save only");
        receipt.put("preferenceBytesUnchanged", preferencesUnchanged);
        receipt.put("preferencesBefore", preferencesBefore);
        receipt.put("preferencesAfter", preferences(mc));
        receipt.put("osInput", false);
        receipt.put(
            "businessTransport",
            "Real owned integrated client/server area import and native editor actions; paid construction NOT_RUN");
        receipt.put("checks", checks);
        receipt.put("completed", true);
        receipt.put("passed", failure == null);
        receipt.put("nativeInputDispatched", inputSeen);
        receipt.put("clientRenderVerified", postFrame >= 0);
        receipt.put("controlKey", "control.61/config.inventory.close; native multi-pin/restore/Delete");
        receipt.put("paymentVerified", false);
        receipt.put("actualNetty", false);
        receipt.put(
            "artifact",
            ArtifactIdentity.capture(HologramEditorIssuesProbe.class)
                .asMap());
        Files.write(output.resolve("gtmb-editor-issues-tutorial.json"), json(receipt));
        Files.write(output.resolve("gtmb-client-ui.json"), json(receipt));
    }

    private void transition(int next) {
        stage = next;
        stageStarted = System.nanoTime();
        System.out.println("[GTMB-EDITOR-ISSUES] stage=" + stage);
    }

    private void progress(Minecraft mc) throws Exception {
        if (System.nanoTime() - lastProgress < 2_000_000_000L) return;
        lastProgress = System.nanoTime();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("runId", run);
        status.put("stage", stage);
        status.put("frame", frame);
        status.put("worldFrames", worldFrames);
        status.put("exactWorldOwned", exactWorldOwned);
        status.put("localPlacementActive", HologramLocalPlacement.INSTANCE.active());
        status.put("localPlacementLocked", HologramLocalPlacement.INSTANCE.locked());
        status.put(
            "localPlacementAnchor",
            HologramLocalPlacement.INSTANCE.locked() ? HologramLocalPlacement.INSTANCE.anchor() : null);
        status.put(
            "screen",
            mc.currentScreen == null ? "<none>"
                : mc.currentScreen.getClass()
                    .getName());
        status.put("failure", failure);
        if (screen != null && screen.ui.tutorial.active()) status.put(
            "tutorialDiagnostic",
            screen.ui.tutorial.diagnosticState()
                .toString());
        Files.write(output.resolve("gtmb-editor-issues-progress.json"), json(status));
    }

    private static Map<String, String> preferences(Minecraft mc) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : new String[] { "gtmb-editor-guide.properties", "gtmb-editor-guide.properties.pending" }) {
            Path path = mc.mcDataDir.toPath()
                .resolve("config")
                .resolve(name);
            if (!Files.exists(path)) result.put(name, "ABSENT");
            else {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path));
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) hex.append(String.format("%02x", value & 255));
                result.put(name, hex.toString());
            }
        }
        return result;
    }

    private static JsonObject read(Path path) throws Exception {
        return new Gson().fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), JsonObject.class);
    }

    private static byte[] json(Object value) {
        return new GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(value)
            .getBytes(StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
