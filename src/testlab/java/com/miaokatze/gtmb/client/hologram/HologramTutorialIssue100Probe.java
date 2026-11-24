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
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Explicit native world-render / tutorial ownership regression. Only a new marker-owned save is loaded. */
public final class HologramTutorialIssue100Probe {

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
    private volatile HologramLocalPreviewStagesProbe localPreviewStages;
    private final Map<String, Object> preparedStockReceipt = new LinkedHashMap<>();
    private volatile HologramEbfPreparedStock preparedStock;
    private Path save;
    private GuiScreen originalMenu;
    private HologramScreen screen;
    private HologramState background;
    private String inventoryBefore;
    private Map<String, String> preferencesBefore;
    private int stage, capturedStep = -1, dispatchedStep = -1;
    private long frame, postFrame = -1, worldFrames, tutorialWorldStart, startFrame;
    private long beforeGeneration, restoredGeneration;
    private long activeWorldFrames, activeWorldGeneration = -1, activeWorldLastGeneration = -1;
    private boolean activeWorldConflict;
    private RenderWorldLastEvent observedWorldEvent;
    private long worldGenerationBeforeHandler = -1;
    private long stageStarted = System.nanoTime(), lastProgress;
    private boolean launchedWorld, unloadRequested, complete;
    private String failure;

    static boolean install() {
        if (HologramEbfFixedControls.coilOnlyEnabled()) require(
            HologramEbfFixedControls.coilProfileAllowed(Boolean.getBoolean("gtmb.testlab.tutorialIssue100"),
                Boolean.getBoolean("miaotest.host.environment"), HologramLocalPreviewStagesProbe.enabled(),
                Boolean.getBoolean(HologramEbfPreparedStock.FLAG), HologramLocalPreviewStagesProbe.fixedNativeEbfEnabled(),
                System.getProperty("gtmb.testlab.localPreviewStagesNativeParity", ""),
                Boolean.getBoolean("gtmb.testlab.localPreviewStagesConstruction")
                    || Boolean.getBoolean("gtmb.testlab.localPreviewStagesPostFocus")
                    || Boolean.getBoolean("gtmb.testlab.localPreviewStagesWorkbenchStages")
                    || Boolean.getBoolean("gtmb.testlab.localPreviewStagesExerciseCandidates")
                    || !System.getProperty("gtmb.testlab.localPreviewStagesTiming", "").isEmpty()),
            "Coil-only native EBF requires the exact prepared-stock fixture profile and no alternate driver");
        if (Boolean.getBoolean(HologramEbfPreparedStock.FLAG)) require(
            Boolean.getBoolean("gtmb.testlab.tutorialIssue100") && Boolean.getBoolean("miaotest.host.environment")
                && HologramLocalPreviewStagesProbe.fixedNativeEbfEnabled() && HologramLocalPreviewStagesProbe.enabled(),
            "Prepared stock is only allowed in the exact isolated fixed EBF profile");
        if (!Boolean.getBoolean("gtmb.testlab.tutorialIssue100")) return false;
        require(!HologramLocalPreviewStagesProbe.fixedNativeEbfEnabled() || HologramLocalPreviewStagesProbe.enabled(),
            "Fixed native EBF requires the existing lease-owned local-preview route");
        HologramTutorialIssue100Probe probe = new HologramTutorialIssue100Probe();
        FMLCommonHandler.instance()
            .bus()
            .register(probe);
        MinecraftForge.EVENT_BUS.register(probe);
        return true;
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (complete || stage == 0 || event.phase != TickEvent.Phase.END) return;
        if (exactWorldOwned) {
            try {
                if (preparedStock != null && !preparedStock.verifyServer(server.worldServerForDimension(0))) return;
                HologramLocalPreviewStagesProbe local = localPreviewStages;
                if (local != null) local.setup(server);
            } catch (Exception problem) {
                serverFailure = problem.toString();
            }
            return;
        }
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
                "Integrated server save identity mismatch");
            require(actual.isSinglePlayer(), "Expected a native integrated server");
            require(
                Boolean.TRUE.equals(
                    Class.forName("com.miaokatze.miaokatzecore.testlab.LabWorldPolicy")
                        .getMethod("owns", net.minecraft.world.World.class)
                        .invoke(null, actual.worldServerForDimension(0))),
                "Core exact owned-save environment policy missing");
            server = actual;
            serverThread = Thread.currentThread();
            exactWorldOwned = true;
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
            if (stage < 6 && System.nanoTime() - started > 480_000_000_000L)
                throw new IllegalStateException("Issue100 native regression timed out at stage " + stage);
            long stageSeconds = (System.nanoTime() - stageStarted) / 1_000_000_000L;
            if ((stage == 2 || stage == 4) && stageSeconds > 30 || stage == 3 && stageSeconds > 120)
                throw new IllegalStateException("Issue100 stage readiness timeout: " + stage);
            if (serverFailure != null) throw new IllegalStateException(serverFailure);
            if (stage == 0) {
                if (!HologramTestLabUiProfile.readyTitle(mc.currentScreen)) return;
                require(mc.theWorld == null && mc.getIntegratedServer() == null, "Refusing a preexisting world");
                require(
                    !HologramLocalPlacement.INSTANCE.active() && HologramClient.state == null,
                    "Refusing an existing projection");
                originalMenu = mc.currentScreen;
                preferencesBefore = preferences(mc);
                Path parent = mc.mcDataDir.toPath()
                    .resolve("saves")
                    .toRealPath();
                save = parent.resolve("MiaoHost-" + run);
                if (Boolean.getBoolean(HologramEbfPreparedStock.FLAG)) {
                    receipt.put("preparedStock", preparedStockReceipt);
                    preparedStock = HologramEbfPreparedStock.read(mc.mcDataDir.toPath(), output,
                        Paths.get(System.getProperty("miaotest.host.receiptDir")), run,
                        System.getProperty("miaotest.host.actorUuid"), mc.getSession().getPlayerID(),
                        System.getProperty(HologramEbfPreparedStock.SHA_FLAG), ProjectorRegistry.projector);
                } else {
                    JsonObject lease = read(mc.mcDataDir.toPath().resolve(".testlab-host/lease.json"));
                    require(run.equals(lease.get("runId").getAsString()), "Exact host lease required");
                }
                Files.createDirectory(save);
                Map<String, Object> marker = new LinkedHashMap<>();
                marker.put("runId", run);
                marker.put("parent", parent.toString());
                Files.write(save.resolve(".miaotest-host-owner.json"), json(marker),
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                if (preparedStock != null) preparedStock.install(save, parent, preparedStockReceipt);
                // HostEnvironment reads this only in FMLServerAboutToStartEvent, after the exact host lease/marker.
                System.setProperty("miaotest.host.worldPath", save.toString());
                receipt.put("ownedSave", save.toString());
                receipt.put("actorMode", preparedStock == null ? "SURVIVAL; no OP or inventory fixture changes"
                    : "SURVIVAL; no OP; explicit pre-login vanilla playerdata fixture, no live inventory injection");
                transition(1);
                launchedWorld = true;
                mc.launchIntegratedServer(
                    save.getFileName()
                        .toString(),
                    "GTMB Issue100 owned regression",
                    new WorldSettings(61312138L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
            } else if (stage == 1) {
                if (!exactWorldOwned || mc.theWorld == null || mc.thePlayer == null || mc.thePlayer.ticksExisted < 60)
                    return;
                require(!mc.thePlayer.capabilities.isCreativeMode, "Business actor must remain survival");
                if (HologramLocalPreviewStagesProbe.enabled()) {
                    if (localPreviewStages == null) {
                        if (preparedStock != null && !preparedStock.verifyClient(mc.thePlayer, preparedStockReceipt)) return;
                        localPreviewStages = new HologramLocalPreviewStagesProbe(mc, output, receipt);
                    }
                    if (!localPreviewStages.step(mc)) return;
                    if (HologramLocalPreviewStagesProbe.fixedNativeEbfEnabled()
                        || HologramLocalPreviewStagesProbe.directConstructionEnabled()) {
                        transition(6);
                        return; // terminal owned-world profile; no unrelated Issue100 playback
                    }
                }
                inventoryBefore = inventory(mc);
                HologramLibraryClient.DisplaySource source = new HologramLibraryClient.DisplaySource(
                    "issue100-fixture",
                    "",
                    null,
                    null,
                    "issue100-fixture",
                    "",
                    "",
                    "");
                source.state = localFixture();
                HologramLocalPlacement.INSTANCE.install(source);
                background = HologramLocalPlacement.INSTANCE.display();
                mc.thePlayer.rotationPitch = 65;
                startFrame = frame;
                transition(2);
            } else if (stage == 2) {
                if (frame - startFrame < 20) return;
                if (!HologramLocalPlacement.INSTANCE.locked()) {
                    HologramLocalPlacement.INSTANCE.use(false);
                    require(HologramLocalPlacement.INSTANCE.locked(), "Native local placement could not lock a target");
                    return;
                }
                MiaoVisonLiteRenderer.WorldRenderWitness witness = MiaoVisonLiteRenderer.lastWorldRenderWitness();
                if (witness == null || witness.state != background || witness.lists <= 0) return;
                beforeGeneration = witness.generation;
                receipt.put("backgroundBeforeGeneration", beforeGeneration);
                receipt.put("backgroundBeforeDrawnLists", witness.lists);
                tutorialWorldStart = worldFrames;
                screen = HologramScreen.detachedTutorial("blueprint", true, () -> {});
                screen.openFrom(null);
                transition(3);
            } else if (stage == 3) {
                require(mc.currentScreen == screen, "Tutorial screen ownership changed");
                if (!validCourse(screen.ui.tutorial.diagnosticState())) return;
                if (capturedStep >= 0 && capturedStep < 2 && dispatchedStep != capturedStep) {
                    clickNext();
                    dispatchedStep = capturedStep;
                }
                if (capturedStep == 2) {
                    NBTTagCompound state = screen.ui.tutorial.diagnosticState();
                    NBTTagCompound engine = state.getCompoundTag("lessonEngine");
                    require(activeWorldFrames >= 20, "Insufficient active-tutorial native world-render frames");
                    require(
                        engine.getInteger("selectedCellCount") > 0 && engine.getInteger("presentedVisibleCellCount") > 0
                            && engine.getInteger("renderedCellCount") > 0,
                        "SELECT_CELL did not select visible rendered material");
                    MiaoVisonLiteRenderer.WorldRenderWitness witness = MiaoVisonLiteRenderer.lastWorldRenderWitness();
                    require(
                        witness != null && !activeWorldConflict && witness.generation == activeWorldGeneration,
                        "Background projection was submitted while the tutorial owned the native snapshot");
                    receipt.put("tutorialWorldFrames", activeWorldFrames);
                    receipt.put("backgroundGenerationAtActiveStart", activeWorldGeneration);
                    receipt.put("backgroundGenerationWhileTutorial", witness.generation);
                    receipt.put("selectedCellDiagnostic", state.toString());
                    screen.ui.tutorial.closeWithoutProgress();
                    mc.displayGuiScreen(null);
                    require(!screen.ui.tutorial.active(), "Tutorial did not close");
                    transition(4);
                }
            } else if (stage == 4) {
                MiaoVisonLiteRenderer.WorldRenderWitness witness = MiaoVisonLiteRenderer.lastWorldRenderWitness();
                if (witness == null || witness.state != background || witness.generation <= activeWorldLastGeneration)
                    return;
                restoredGeneration = witness.generation;
                require(
                    HologramLocalPlacement.INSTANCE.display() == background && HologramLocalPlacement.INSTANCE.locked(),
                    "Original local placement identity/lock was not retained");
                require(inventoryBefore.equals(inventory(mc)), "Inventory changed during the display-only scenario");
                receipt.put("backgroundAfterGeneration", restoredGeneration);
                receipt.put("backgroundRestored", true);
                receipt.put("inventoryUnchanged", true);
                HologramLocalPlacement.INSTANCE.clear();
                transition(6);
            }
            if (stage == 6) closeWorld(mc);
        } catch (Throwable problem) {
            if (failure == null) failure = problem.toString();
            if (stage != 6) transition(6);
            try {
                closeWorld(mc);
            } catch (Throwable cleanup) {
                receipt.put("cleanupFailure", cleanup.toString());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void worldBefore(RenderWorldLastEvent event) {
        observedWorldEvent = null;
        if (complete || stage != 3 || Minecraft.getMinecraft().currentScreen != screen || !screen.ui.tutorial.active())
            return;
        observedWorldEvent = event;
        MiaoVisonLiteRenderer.WorldRenderWitness witness = MiaoVisonLiteRenderer.lastWorldRenderWitness();
        worldGenerationBeforeHandler = witness == null || witness.state != background ? -1 : witness.generation;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void world(RenderWorldLastEvent event) {
        if (complete || stage < 2 || stage > 4) return;
        worldFrames++;
        if (observedWorldEvent == event) {
            MiaoVisonLiteRenderer.WorldRenderWitness witness = MiaoVisonLiteRenderer.lastWorldRenderWitness();
            if (witness == null || witness.state != background || worldGenerationBeforeHandler < 0)
                activeWorldConflict = true;
            else {
                if (activeWorldFrames == 0) activeWorldGeneration = worldGenerationBeforeHandler;
                activeWorldLastGeneration = witness.generation;
                if (witness.generation != worldGenerationBeforeHandler || witness.generation != activeWorldGeneration)
                    activeWorldConflict = true;
            }
            activeWorldFrames++;
            observedWorldEvent = null;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void guiPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (!complete && stage == 1 && localPreviewStages != null) localPreviewStages.guiPre(event.gui, frame);
    }

    @SubscribeEvent
    public void guiPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!complete && stage == 1 && localPreviewStages != null) localPreviewStages.guiPost(event.gui, frame);
        if (!complete && stage == 3 && event.gui == screen) postFrame = frame;
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (complete || event.phase != TickEvent.Phase.END) return;
        frame++;
        if (stage == 1 && localPreviewStages != null) {
            try {
                localPreviewStages.renderTail(frame);
            } catch (Exception problem) {
                if (failure == null) failure = problem.toString();
                transition(6);
            }
        }
        if (stage != 3 || Minecraft.getMinecraft().currentScreen != screen || postFrame != frame - 1) return;
        try {
            NBTTagCompound state = screen.ui.tutorial.diagnosticState();
            if (!validCourse(state)) return;
            if ("blocked".equals(state.getString("phase"))) {
                receipt.put("blockedDiagnostic", state.toString());
                Files.createDirectories(output.resolve("screenshots"));
                Minecraft mc = Minecraft.getMinecraft();
                ScreenShotHelper.saveScreenshot(
                    output.toFile(),
                    "gtmb-issue100-blocked.png",
                    mc.displayWidth,
                    mc.displayHeight,
                    mc.getFramebuffer());
            }
            require(!"blocked".equals(state.getString("phase")), "Tutorial blocked: " + state.getString("unavailable"));
            if (state.getInteger("step") == capturedStep || state.getBoolean("actionPending")
                || state.getInteger("action") != state.getInteger("actionCount")) return;
            if (state.getInteger("step") == 2 && activeWorldFrames < 20) return;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", state.getInteger("step"));
            step.put("diagnosticSnbt", state.toString());
            step.put("frame", frame);
            step.put("worldFramesSinceTutorial", worldFrames - tutorialWorldStart);
            step.put("activeTutorialWorldFrames", activeWorldFrames);
            step.put("backgroundGeneration", activeWorldLastGeneration);
            step.put("captureEvent", "RenderTick.END after exact-screen DrawScreenEvent.Post");
            String name = String.format("gtmb-issue100-step-%02d.png", state.getInteger("step"));
            Files.createDirectories(output.resolve("screenshots"));
            Minecraft mc = Minecraft.getMinecraft();
            ScreenShotHelper
                .saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            require(
                Files.isRegularFile(
                    output.resolve("screenshots")
                        .resolve(name)),
                "Natural frame PNG missing");
            step.put("png", "screenshots/" + name);
            if (HologramRenderParityProbe.enabled()) {
                try {
                    step.put(
                        "scene",
                        HologramRenderParityProbe.cropScene(
                            output,
                            name,
                            String.format("gtmb-issue100-step-%02d-scene.png", state.getInteger("step")),
                            screen.ui.tutorial.renderUi()));
                } catch (Exception cropFailure) {
                    step.put("sceneCaptureFailure", cropFailure.toString());
                }
            }
            steps.add(step);
            capturedStep = state.getInteger("step");
        } catch (Throwable problem) {
            if (failure == null) failure = problem.toString();
            transition(6);
        }
    }

    private void clickNext() throws ReflectiveOperationException {
        Object overlay = HologramTestLabUiDiagnostics.readDiagnosticField(screen.ui.tutorial, "overlay");
        IWidget next = ((IWidget[]) HologramTestLabUiDiagnostics.readDiagnosticField(overlay, "navigation"))[1];
        int[] bounds = screen.ui.tutorial.renderUi().shell.nativeWidgetBounds(next);
        require(bounds != null && bounds[2] > 0 && bounds[3] > 0 && next.isEnabled(), "Next native button unavailable");
        int x = bounds[0] + bounds[2] / 2, y = bounds[1] + bounds[3] / 2;
        screen.getScreen()
            .getContext()
            .updateState(x, y, 0);
        screen.getScreen()
            .onFrameUpdate();
        boolean hit = false;
        for (LocatedWidget located : next.getPanel()
            .getHovering()) if (located.getElement() == next) hit = true;
        require(hit, "Next native button missed hit test");
        HologramMuiProbeInput.click(screen, x, y, 0);
        steps.get(steps.size() - 1)
            .put("nextNativeHit", true);
    }

    private void closeWorld(Minecraft mc) throws Exception {
        if (!unloadRequested) {
            if (localPreviewStages != null && !localPreviewStages.finish(mc)) return;
            if (screen != null) {
                receipt.put(
                    "terminalTutorialDiagnostic",
                    screen.ui.tutorial.diagnosticState()
                        .toString());
                screen.ui.tutorial.closeWithoutProgress();
            }
            if (background != null && HologramLocalPlacement.INSTANCE.display() == background)
                HologramLocalPlacement.INSTANCE.clear();
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
        receipt.put("schema", "gtmb-issue100-native-world-tutorial-v1");
        receipt.put("runId", run);
        receipt.put("outcome", failure == null ? "PASS" : "FAIL");
        receipt.put("failure", failure);
        receipt.put("steps", steps);
        receipt.put("nativeWorldFrames", worldFrames);
        receipt.put("worldStopped", true);
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
        receipt.put("businessTransport", HologramLocalPreviewStagesProbe.directConstructionEnabled()
            ? failure == null ? "PASS; one fenced v4 placement, server state, actual block and debit"
                : "FAIL; direct v4 placement evidence incomplete"
            : "NOT_RUN; incoming local preview fixture, native rendering and MUI events");
        receipt.put(
            "artifact",
            ArtifactIdentity.capture(HologramTutorialIssue100Probe.class)
                .asMap());
        if (HologramLocalPreviewStagesProbe.fixedNativeEbfEnabled()) {
            boolean coil = HologramEbfFixedControls.coilOnlyEnabled();
            receipt.put("schema", coil ? HologramEbfFixedControls.COIL_SCHEMA : "gtmb-stock-ebf-native-controls-v1");
            Files.write(
                output.resolve(coil ? HologramEbfFixedControls.COIL_FILE : "gtmb-ebf-fixed-native.json"),
                json(receipt), java.nio.file.StandardOpenOption.CREATE_NEW);
        } else {
            Files.write(output.resolve("gtmb-issue100-tutorial.json"), json(receipt));
            if (!HologramRenderParityProbe.install(originalMenu, output, run, failure)) HologramTestLabUiProfile.install();
        }
    }

    private void transition(int next) {
        stage = next;
        stageStarted = System.nanoTime();
        System.out.println("[GTMB-ISSUE100] stage=" + stage);
    }

    private void progress(Minecraft mc) throws Exception {
        if (System.nanoTime() - lastProgress < 2_000_000_000L) return;
        lastProgress = System.nanoTime();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("runId", run);
        status.put("stage", stage);
        if (localPreviewStages != null) status.put("localPreviewStages", localPreviewStages.progress());
        status.put("frame", frame);
        status.put("worldFrames", worldFrames);
        status.put("activeTutorialWorldFrames", activeWorldFrames);
        status.put("backgroundGenerationAtActiveStart", activeWorldGeneration);
        status.put("backgroundGenerationAtLastActiveFrame", activeWorldLastGeneration);
        status.put("activeWorldConflict", activeWorldConflict);
        status.put("exactWorldOwned", exactWorldOwned);
        status.put("localPlacementActive", HologramLocalPlacement.INSTANCE.active());
        status.put("localPlacementLocked", HologramLocalPlacement.INSTANCE.locked());
        status.put(
            "localPlacementAnchor",
            HologramLocalPlacement.INSTANCE.locked() ? HologramLocalPlacement.INSTANCE.anchor() : null);
        MiaoVisonLiteRenderer.WorldRenderWitness witness = MiaoVisonLiteRenderer.lastWorldRenderWitness();
        status.put("worldWitnessGeneration", witness == null ? null : witness.generation);
        status.put("worldWitnessMatchesFixture", witness != null && witness.state == background);
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
        Files.write(output.resolve("gtmb-issue100-progress.json"), json(status));
    }

    private static HologramState localFixture() {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "issue100-local-origin-fixture");
        data.setBoolean("virtual", true);
        data.setBoolean("supported", true);
        data.setBoolean("target", true);
        NBTTagList cells = new NBTTagList();
        for (int x = 0; x < 3; x++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", x);
            cell.setInteger("dx", x);
            cell.setString("id", "minecraft:stone");
            cell.setString("wantId", "minecraft:stone");
            cell.setString("status", "missing");
            cells.appendTag(cell);
        }
        data.setTag("cells", cells);
        return new HologramState(data);
    }

    private static boolean validCourse(NBTTagCompound state) {
        return state.getBoolean("active") && "blueprint".equals(state.getString("topic"))
            && state.getInteger("stepCount") == 25;
    }

    private static String inventory(Minecraft mc) {
        return mc.thePlayer.inventory.writeToNBT(new NBTTagList())
            .toString();
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
