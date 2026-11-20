// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Paired evidence from the production editor widget on an owned client's real framebuffer. */
public final class HologramRenderParityProbe {

    private static final String OPT_IN = "gtmb.testlab.renderParity";
    private static final String GIANT_SOURCE = "gtmb.testlab.renderParityGiantSource";
    private static final String GIANT_LIBRARY_ROOT = "gtmb.testlab.renderParityGiantLibraryRoot";
    private static final String GIANT_LIBRARY_ID = "gtmb.testlab.renderParityGiantLibraryId";
    private static final String ANIMATION_WITNESS = "gtmb.testlab.renderParityAnimationWitness";
    private static final String GIANT_PERFORMANCE = "gtmb.testlab.renderParityGiantPerformance";
    private static final String GIANT_COLD_ONLY = "gtmb.testlab.renderParityGiantColdOnly";
    private static final String COMBINED = "gtmb.testlab.renderParityCombined";
    private static final String EDITOR_RECEIPT = "gtmb-render-parity-editor.json";
    private static final String CLIENT_RECEIPT = "gtmb-client-ui.json";
    private static final int ANIMATION_SAMPLES = 20;
    private static final String[] CONDUIT_SPRITES = { "enderio:itemConduitCore", "enderio:itemConduitCoreAdvanced" };
    private static final int GIANT_CELLS = 123_369;
    private static final String[] CASES = { "mixed", "ae-drive-interface", "native-six-mod", "large-structure" };
    private static final double[] ORBIT = { 0.6, 0.9, 1.4, 2.1, 2.8, 3.4, 4.1, 4.8, 5.5, 6.0 };
    private static final double[] GIANT_ORBIT = { 0.6, 0.61, 0.62, 1.4 };
    private final GuiScreen menu;
    private final Path output;
    private final List<Map<String, Object>> frames = new ArrayList<>();
    private final List<Long> orbitDrawNanos = new ArrayList<>();
    private final Map<String, Object> receipt = new LinkedHashMap<>();
    private final String giantSource = System.getProperty(GIANT_SOURCE, "");
    private final boolean giantPerformance = Boolean.getBoolean(GIANT_PERFORMANCE);
    private final boolean giantColdOnly = Boolean.getBoolean(GIANT_COLD_ONLY);
    private final String giantLibraryId = System.getProperty(GIANT_LIBRARY_ID, "");
    private final List<Map<String, Object>> giantLoads = new ArrayList<>();
    private Map<String, Object> giantEvidence;
    private List<Map<String, Object>> giantFrames;
    private List<Long> giantOrbitDrawNanos;
    private final boolean animationWitness = Boolean.getBoolean(ANIMATION_WITNESS);
    private final List<Map<String, Object>> animationFrames = new ArrayList<>();
    private HologramScreen screen;
    private HologramState snapshot;
    private int scenario, phase, draws, poseDraws, orbitIndex;
    private int completedGiantLoads;
    private boolean giantDrawerDispatched;
    private HologramClientLibrary originalLibrary, isolatedLibrary;
    private HologramState coldBrowseState;
    private long activated = System.nanoTime();
    private long beforeDraw, drawNanos, guiPosts, renderedPosts;
    private long giantStarted;
    private long lastGiantStatus;
    private long nextAnimationSample;
    private int[] clickPoint;
    private int clickCell = -1;
    private boolean inputDispatched, complete;

    private HologramRenderParityProbe(GuiScreen menu, Path output, String run) {
        this.menu = menu;
        this.output = output;
        receipt.put("runId", run);
        receipt.put("profile", "paired-render-parity-after-issue100");
        receipt.put("frames", frames);
        receipt.put("orbitEditorDrawNanos", orbitDrawNanos);
        if (animationWitness) receipt.put("nativeSixAnimationFrames", animationFrames);
        if (giantPerformance) {
            receipt.put("profile", "giant-performance-after-issue100");
            receipt.put("lightingAdmission", "Issue100 world rendered and unloaded; same mixed editor control");
            receipt.put("giantLoads", giantLoads);
            receipt.put("giantColdOnly", giantColdOnly);
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean(OPT_IN);
    }

    static boolean install(GuiScreen menu, Path output, String run, String tutorialFailure) throws Exception {
        if (!enabled()) return false;
        HologramRenderParityProbe probe = new HologramRenderParityProbe(menu, output, run);
        if (tutorialFailure != null || probe.giantPerformance && probe.giantSource.isEmpty()) {
            probe.finish(
                tutorialFailure == null ? "Giant performance requires an explicit sample"
                    : "Issue100 tutorial failed: " + tutorialFailure);
            return true;
        }
        FMLCommonHandler.instance()
            .bus()
            .register(probe);
        MinecraftForge.EVENT_BUS.register(probe);
        return true;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (complete || event.phase != TickEvent.Phase.END) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            require(mc.theWorld == null && mc.thePlayer == null && mc.getIntegratedServer() == null, "Menu-only probe");
            if (screen == null) {
                require(mc.currentScreen == menu, "Original menu identity was lost");
                if (scenario == CASES.length + requiredGiantLoads()) {
                    verifyAeDelta();
                    finish(null);
                    return;
                }
                if (giant()) beginGiantBrowse();
                snapshot = new HologramState(fixture(giant() ? 0 : scenario));
                screen = new HologramScreen(snapshot);
                screen.openFrom(menu);
                screen.ui.preview.moveHandles(false);
                setPose(0.6);
                phase = draws = orbitIndex = 0;
                activated = System.nanoTime();
                return;
            }
            require(mc.currentScreen == screen, "Expected production editor screen");
            long timeout = giant() ? 180_000_000_000L : 45_000_000_000L;
            require(System.nanoTime() - activated < timeout, scenarioName() + " did not finish rendering");
            if (giant()) {
                observeGiantStatus();
                if (giantStarted == 0) {
                    require(
                        System.nanoTime() - activated < 20_000_000_000L,
                        "Private browse never admitted its request");
                } else if (System.nanoTime() - giantStarted > 3_000_000_000L) {
                    HologramLibraryClient library = HologramLibraryClient.INSTANCE;
                    require(
                        library.busy() || library.browsePreviewState() != null,
                        "Private browse request retired without display: " + library.status());
                }
            }
            if (giant() && giantStarted == 0) {
                requestGiantBrowse();
            } else if (phase == 1 && scenario == 0) {
                require(clickPoint != null, "Mixed scene needs a visible native pick target");
                HologramMuiProbeInput.click(screen, clickPoint[0], clickPoint[1], 0);
                inputDispatched = true;
                phase = 2;
                poseDraws = 0;
            } else if (phase == 3) {
                double[] orbit = giant() ? GIANT_ORBIT : ORBIT;
                if (orbitalCase() && orbitIndex + 1 < orbit.length) {
                    setPose(orbit[++orbitIndex]);
                    phase = 4;
                } else {
                    if (giant() && giantFrames.size() == GIANT_ORBIT.length
                        && giantEvidence.containsKey("firstMaterialNanos")
                        && Integer.valueOf(GIANT_CELLS)
                            .equals(giantEvidence.get("completedCells"))) {
                        completedGiantLoads++;
                    }
                    if (giant()) HologramLibraryClient.INSTANCE.clearBrowsePreview();
                    mc.displayGuiScreen(menu);
                    screen = null;
                    scenario = giantPerformance && scenario == 0 ? CASES.length : scenario + 1;
                }
            }
        } catch (Exception | LinkageError failure) {
            finishFailed(failure);
        }
    }

    private boolean giant() {
        return scenario >= CASES.length && scenario < CASES.length + requiredGiantLoads();
    }

    private int requiredGiantLoads() {
        return giantSource.isEmpty() ? 0 : giantPerformance && !giantColdOnly ? 2 : 1;
    }

    private boolean orbitalCase() {
        return scenario == CASES.length - 1 || giant();
    }

    private String scenarioName() {
        if (scenario < CASES.length) return CASES[scenario];
        if (!giant()) return "post-editor checks";
        return giantPerformance ? "giant-private-blueprint-" + (scenario == CASES.length ? "cold" : "warm")
            : "giant-private-blueprint";
    }

    private void beginGiantBrowse() throws Exception {
        giantStarted = 0;
        lastGiantStatus = 0;
        giantDrawerDispatched = false;
        giantEvidence = new LinkedHashMap<>();
        giantFrames = new ArrayList<>();
        giantOrbitDrawNanos = new ArrayList<>();
        giantEvidence.put("frames", giantFrames);
        giantEvidence.put("orbitEditorDrawNanos", giantOrbitDrawNanos);
        giantEvidence.put(
            "heapMaxBytes",
            Runtime.getRuntime()
                .maxMemory());
        giantEvidence.put("heapUsedBytesAtAdmission", heapUsedBytes());
        giantEvidence.put(
            "availableProcessors",
            Runtime.getRuntime()
                .availableProcessors());
        giantEvidence.put("sampleKind", "isolated exact private library JSON/PNG pair");
        giantEvidence.put("pipeline", "native library drawer → previewLocal → worker → Preparation → browse scene");
        if (giantPerformance) {
            giantEvidence.put("label", scenario == CASES.length ? "cold" : "warm");
            giantLoads.add(giantEvidence);
        } else receipt.put("giant", giantEvidence);
        if (isolatedLibrary != null) {
            giantEvidence.put("canonicalLibraryId", giantLibraryId);
            giantEvidence.put("stagedPair", true);
            return;
        }
        String directory = System.getProperty(GIANT_LIBRARY_ROOT, "");
        require(
            !directory.isEmpty() && giantLibraryId.matches("local_[a-fA-F0-9-]{36}"),
            "Host must stage the exact private pair in an isolated library");
        Path root = Paths.get(directory)
            .toRealPath();
        Path game = Minecraft.getMinecraft().mcDataDir.toPath()
            .toRealPath();
        require(!root.startsWith(game), "The fixture library must be outside the registered client data directory");
        HologramClientLibrary stagingStore = new HologramClientLibrary(root);
        Path staged = stagingStore.savedPath(giantLibraryId);
        require(
            Files.isRegularFile(staged) && Files.isRegularFile(HologramBlueprintArtifact.previewPath(staged)),
            "Host-staged private blueprint pair is missing");
        Field store = diagnosticField(HologramLibraryClient.class, "store");
        originalLibrary = (HologramClientLibrary) store.get(HologramLibraryClient.INSTANCE);
        store.set(HologramLibraryClient.INSTANCE, stagingStore);
        isolatedLibrary = stagingStore;
        giantEvidence.put("canonicalLibraryId", giantLibraryId);
        giantEvidence.put("stagedPair", true);
    }

    private void requestGiantBrowse() throws Exception {
        if (!screen.ui.library.libraryDrawer() && !giantDrawerDispatched) {
            if (screen.ui.shell.targetBounds("header.library") != null) {
                HologramMuiProbeInput.nativeTarget(screen, "header.library", new LinkedHashMap<>());
                giantDrawerDispatched = true;
                giantEvidence.put("drawerAdmission", "native library header input");
                return;
            }
        }
        if (!screen.ui.library.libraryDrawer() && System.nanoTime() - activated >= 750_000_000L
            && screen.ui.shell.built()) {
            giantEvidence.put("nativeHeaderDispatchObserved", giantDrawerDispatched);
            screen.ui.shell.toggleLibrary();
            if (!screen.ui.library.libraryDrawer()) screen.ui.library.libraryDrawer(true);
            giantEvidence.put("drawerAdmission", "production drawer callback after header did not open this fixture");
        }
        if (!screen.ui.library.libraryDrawer()) return;
        giantEvidence.putIfAbsent("drawerAdmission", "already-open production library drawer");
        if (HologramLibraryClient.INSTANCE.busy() || HologramBackgroundTasks.busy()) return;
        java.lang.reflect.Method browse = HologramUiLibrary.class
            .getDeclaredMethod("browsePreset", String.class, boolean.class);
        browse.setAccessible(true);
        giantStarted = System.nanoTime();
        browse.invoke(screen.ui.library, giantLibraryId, false);
        require(screen.ui.library.browsePreviewActive(), "Production private browse did not acquire the scene");
    }

    private void observeGiantStatus() {
        long now = System.nanoTime();
        if (now - lastGiantStatus < 2_000_000_000L) return;
        lastGiantStatus = now;
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        HologramState using = library.browsePreviewState();
        String checkpoint = "phase=" + phase
            + " id="
            + giantLibraryId
            + " screen="
            + Minecraft.getMinecraft().currentScreen.getClass()
                .getSimpleName()
            + " drawer="
            + screen.ui.library.libraryDrawer()
            + " browse="
            + screen.ui.library.browsePreviewActive()
            + " requested="
            + (giantStarted != 0)
            + " busy="
            + library.busy()
            + " backgroundBusy="
            + HologramBackgroundTasks.busy()
            + " cells="
            + (using == null ? 0 : using.cells.size())
            + " progress="
            + library.browsePreviewStatus()
            + " status="
            + library.status();
        giantEvidence.put("lastCheckpoint", checkpoint);
        System.out.println("[GTMB-PRIVATE-PERF] " + scenarioName() + " " + checkpoint);
    }

    private static Field diagnosticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void setPose(double yaw) throws ReflectiveOperationException {
        HologramUiPreview preview = screen.ui.preview;
        if (giant() && screen.ui.library.browsePreviewActive()) {
            diagnosticField(HologramUiPreview.class, "browseYaw").setDouble(preview, yaw);
            diagnosticField(HologramUiPreview.class, "browsePitch").setDouble(preview, 0.58);
            diagnosticField(HologramUiPreview.class, "browseZoom").setDouble(preview, 1.0);
        } else {
            preview.yaw(yaw);
            preview.pitch(0.58);
            preview.zoom(1.0);
        }
        poseDraws = 0;
    }

    private double[] pose() throws ReflectiveOperationException {
        HologramUiPreview preview = screen.ui.preview;
        if (giant()) {
            return new double[] { diagnosticField(HologramUiPreview.class, "browseYaw").getDouble(preview),
                diagnosticField(HologramUiPreview.class, "browsePitch").getDouble(preview), preview.zoom() };
        }
        return new double[] { preview.yaw(), preview.pitch(), preview.zoom() };
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void before(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (!complete && event.gui == screen) beforeDraw = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void after(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (complete || event.gui != screen) return;
        drawNanos = System.nanoTime() - beforeDraw;
        guiPosts++;
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (complete || event.phase != TickEvent.Phase.END || screen == null || renderedPosts == guiPosts) return;
        renderedPosts = guiPosts;
        try {
            if (giant() && !readGiantPublication()) return;
            boolean ready = HologramRenderer.previewMeshReady(snapshot)
                && (!giant() || snapshot.cells.size() == GIANT_CELLS && !snapshot.data.getBoolean("displayPartial"));
            if (!ready && !giant()) return;
            if (!giant())
                require(screen.ui.session.state().identity == snapshot.identity, "The prepared fixture changed owners");
            HologramEditorSceneWidget scene = (HologramEditorSceneWidget) screen.ui.shell.scene();
            if (scene == null) return;
            if (giant()) observeGiant(ready);
            if (!ready) return;
            if (!giant() && !scene.editFrameReady()) return;
            draws++;
            poseDraws++;
            if (phase == 0 && poseDraws == 4) {
                if (scenario == 0) {
                    findClickTarget(scene);
                    phase = 1;
                } else if (orbitalCase()) {
                    recordOrbitNanos();
                }
                record(scenarioName(), scene, scenario == 0);
                if (scenario != 0) phase = scenario == 2 && animationWitness ? 5 : 3;
            } else if (phase == 2 && poseDraws == 3) {
                HologramSelection selection = screen.ui.preview.selection();
                require(
                    selection.size() == 1 && selection.contains(clickCell),
                    "Native click did not select its picked cell");
                record("mixed-selected", scene, false);
                phase = 3;
            } else if (phase == 4 && poseDraws == 1) {
                recordOrbitNanos();
                if (giant() || orbitIndex == 4 || orbitIndex == ORBIT.length - 1) {
                    record(scenarioName() + "-orbit-" + orbitIndex, scene, false);
                }
                phase = 3;
            } else if (phase == 5) {
                observeConduitAnimation();
                if (animationFrames.size() == ANIMATION_SAMPLES) phase = 3;
            }
        } catch (Exception | LinkageError failure) {
            finishFailed(failure);
        }
    }

    private void recordOrbitNanos() {
        if (giant()) giantOrbitDrawNanos.add(drawNanos);
        else orbitDrawNanos.add(drawNanos);
    }

    private void observeConduitAnimation() throws Exception {
        long now = System.nanoTime();
        boolean sample = now >= nextAnimationSample;
        Map<String, Object> frame = sample ? new LinkedHashMap<>() : null;
        for (String name : CONDUIT_SPRITES) {
            TextureAtlasSprite sprite = Minecraft.getMinecraft()
                .getTextureMapBlocks()
                .getAtlasSprite(name);
            require(name.equals(sprite.getIconName()) && sprite.getFrameCount() > 1, "Missing animated sprite " + name);
            if (sample) frame.put(name, spritePhase(sprite));
            // Angelica dry-runs inactive atlas sprites. Mark this known sprite as used for the next normal texture
            // tick.
            try {
                sprite.getClass()
                    .getMethod("angelica$markNeedsAnimationUpdate")
                    .invoke(sprite);
                receipt.put("nativeSixAnimationUsageMarked", true);
            } catch (NoSuchMethodException vanillaAnimation) {}
        }
        if (!sample) return;
        String name = String.format("native-six-animation-%02d", animationFrames.size());
        String file = name + "-full.png";
        captureFramebuffer(file);
        frame.put("scene", cropScene(output, file, name + "-scene.png", screen.ui));
        animationFrames.add(frame);
        nextAnimationSample = now + 205_000_000L;
    }

    private static Object spritePhase(TextureAtlasSprite sprite) throws ReflectiveOperationException {
        try {
            return sprite.getClass()
                .getMethod("getFrame")
                .invoke(sprite);
        } catch (NoSuchMethodException vanillaAnimation) {
            return "unavailable";
        }
    }

    private void observeGiant(boolean ready) throws Exception {
        long elapsed = System.nanoTime() - giantStarted;
        if (!giantEvidence.containsKey("firstMaterialNanos")) {
            NBTTagCompound inspection = HologramRenderer.lessonInspection(snapshot);
            int materialCells = inspection.getInteger("renderedCellCount");
            if (materialCells > 0) {
                giantEvidence.put("firstMaterialNanos", elapsed);
                giantEvidence.put("firstMaterialCells", materialCells);
                giantEvidence.put("firstMaterialWasComplete", ready);
                giantEvidence.put("firstPreparedCells", HologramRenderer.previewMeshCompleted(snapshot));
                giantEvidence.put("firstStageTiming", previewTiming());
                giantEvidence.put("firstWorkerCounters", workerCounters());
                giantEvidence.put("firstNativeModelCompiles", HologramBlockRender.compiled);
                if (!giantPerformance) {
                    String file = "giant-first-full.png";
                    captureFramebuffer(file);
                    giantEvidence
                        .put("firstMaterialScene", cropScene(output, file, "giant-first-scene.png", screen.ui));
                }
            }
        }
        if (ready && !giantEvidence.containsKey("completeNanos")) {
            giantEvidence.put("completeNanos", elapsed);
            giantEvidence.put("completedCells", HologramRenderer.previewMeshCompleted(snapshot));
            giantEvidence.put("completionStageTiming", previewTiming());
            giantEvidence.put("heapUsedBytesAfterComplete", heapUsedBytes());
            witnessGiantHydration();
        }
    }

    private boolean readGiantPublication() {
        if (giantStarted == 0 || !screen.ui.library.browsePreviewActive()) return false;
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        long elapsed = System.nanoTime() - giantStarted;
        String status = library.browsePreviewStatus();
        if (status != null && !status.isEmpty()) {
            giantEvidence.putIfAbsent("firstStatus", status);
            giantEvidence.put("currentStatus", status);
        }
        if (library.browsePreviewImage() != null) giantEvidence.putIfAbsent("thumbnailAvailableNanos", elapsed);
        snapshot = library.browsePreviewState();
        if (snapshot == null) return false;
        if (!giantEvidence.containsKey("firstStatePublishedNanos")) {
            giantEvidence.put("firstStatePublishedNanos", elapsed);
            giantEvidence.put("firstStateCells", snapshot.cells.size());
        }
        if (!snapshot.data.getBoolean("displayPartial") && snapshot.cells.size() == GIANT_CELLS) {
            giantEvidence.putIfAbsent("fullStatePublishedNanos", elapsed);
        }
        return true;
    }

    private void witnessGiantHydration() throws Exception {
        require(snapshot.cells.size() == GIANT_CELLS, "Expected the selected 123369-cell private structure");
        boolean warmCacheReuse = coldBrowseState == snapshot;
        if (coldBrowseState == null) coldBrowseState = snapshot;
        giantEvidence.put("warmCachedDisplayStateReused", warmCacheReuse);
        giantEvidence.put("cells", snapshot.cells.size());
        NBTTagCompound presented = HologramRenderer.lessonInspection(snapshot);
        int nativeModels = presented.getInteger("nativeModelCellCount");
        int stacks = 0;
        int tileData = 0;
        for (HologramState.Cell cell : snapshot.cells) {
            if (cell.targetStack != null) stacks++;
            if (cell.targetDisplay.hasKey("nativeTile", 10)) tileData++;
        }
        giantEvidence.put("targetStackCells", stacks);
        giantEvidence.put("targetNativeTileCells", tileData);
        giantEvidence.put("firstCompleteNativeModelCells", nativeModels);
        giantEvidence.put("completeWorkerCounters", workerCounters());
        giantEvidence.put("completeNativeModelCompiles", HologramBlockRender.compiled);
        require(stacks > 0 && tileData > 0 && nativeModels > 0, "Private browse did not hydrate native model inputs");
        Map<?, ?> local = (Map<?, ?>) diagnosticField(HologramLibraryClient.class, "localPreviews")
            .get(HologramLibraryClient.INSTANCE);
        HologramLibraryClient.DisplaySource source;
        synchronized (local) {
            source = (HologramLibraryClient.DisplaySource) local.get("local:" + giantLibraryId);
        }
        require(
            source != null && source.state == snapshot && source.thumbnail != null,
            "Private pair publication lost");
        giantEvidence.put("sourceJsonSha256", HologramBlueprintArtifact.hash(source.json));
        giantEvidence.put("sourceBytes", source.json.length);
        Path staged = HologramBlueprintArtifact.previewPath(isolatedLibrary.savedPath(giantLibraryId));
        giantEvidence.put("sourcePngSha256", HologramBlueprintArtifact.hash(Files.readAllBytes(staged)));
    }

    private static long heapUsedBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private void findClickTarget(HologramEditorSceneWidget scene) {
        int[] area = screen.ui.shell.nativeWidgetBounds(scene);
        require(area != null, "Native scene bounds missing");
        for (int y = area[1] + area[3] / 4; y < area[1] + area[3] * 3 / 4; y += 4) {
            for (int x = area[0] + area[2] / 4; x < area[0] + area[2] * 3 / 4; x += 4) {
                int[] local = screen.ui.shell.sceneLocalPoint(x, y);
                int index = local == null ? -1 : HologramRenderer.pick(local[0], local[1]);
                if (index < 0) continue;
                clickCell = index;
                clickPoint = new int[] { x, y };
                return;
            }
        }
    }

    private void record(String name, HologramEditorSceneWidget scene, boolean primary) throws Exception {
        String file = primary ? "gtmb-native-ui.png" : name + "-full.png";
        captureFramebuffer(file);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("name", name);
        frame.put("cellCount", snapshot.cells.size());
        frame.put("preparedDraws", draws);
        frame.put("pose", pose());
        frame.put("editorDrawNanos", drawNanos);
        frame.put(
            "selection",
            screen.ui.preview.selection()
                .indices());
        frame.put("clickCell", clickCell);
        frame.put("clickPoint", clickPoint);
        frame.put("captureEvent", "RenderTick.END after production editor DrawScreenEvent.Post");
        frame.put("openGlVendor", GL11.glGetString(GL11.GL_VENDOR));
        frame.put("openGlRenderer", GL11.glGetString(GL11.GL_RENDERER));
        List<Integer> picks = new ArrayList<>();
        int rows = giant() ? 10 : 24, columns = giant() ? 12 : 32;
        for (int row = 1; row < rows; row++) {
            for (int col = 1; col < columns; col++) {
                int x = scene.getArea().width * col / columns, y = scene.getArea().height * row / rows;
                picks.add(HologramRenderer.pick(x, y));
            }
        }
        require(
            picks.stream()
                .anyMatch(index -> index >= 0),
            name + " returned no valid native picks");
        frame.put("picks", picks);
        NBTTagCompound inspection = HologramRenderer.lessonInspection(snapshot);
        require(inspection.getBoolean("snapshotPresented"), name + " had no presented snapshot");
        int nativeCells = inspection.getInteger("nativeModelCellCount");
        if (scenario == 1 || scenario == 2) require(nativeCells > 0, name + " used no native tile-model draws");
        if (scenario == 1 || scenario == 2) {
            List<Map<String, Object>> adapters = new ArrayList<>();
            for (HologramState.Cell cell : snapshot.cells) {
                Map<String, Object> adapter = new LinkedHashMap<>();
                adapter.put("block", cell.wantId);
                adapter.put("vertices", HologramBlockRender.vertexCount(cell, false));
                adapter.put("missingTexture", HologramBlockRender.missingTexture(cell, false));
                adapter.put("failure", HologramBlockRender.failure(cell, false));
                adapters.add(adapter);
            }
            frame.put("nativeAdapterGeometry", adapters);
        }
        frame.put("nativeModelCellCount", nativeCells);
        frame.put("renderedCellCount", inspection.getInteger("renderedCellCount"));
        frame.put("visibleCellCount", inspection.getInteger("presentedVisibleCellCount"));
        frame.put("residentRendererCounters", residentCounters());
        frame.put("cameraRendererCounters", cameraCounters());
        if (giant()) {
            frame.put("rendererStages", previewTiming());
            frame.put("workerRendererCounters", workerCounters());
            frame.put("nativeModelCompiles", HologramBlockRender.compiled);
        }
        frame.put("scene", cropScene(output, file, name + "-scene.png", screen.ui));
        if (giant()) giantFrames.add(frame);
        else frames.add(frame);
    }

    private void captureFramebuffer(String file) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        Files.createDirectories(output.resolve("screenshots"));
        ScreenShotHelper.saveScreenshot(output.toFile(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }

    private static Map<String, Long> residentCounters() throws IllegalAccessException {
        return rendererCounters(
            "previewResidentCaptures",
            "previewResidentDraws",
            "previewDirectMeshUploads",
            "previewDirectMeshDraws",
            "worldDirectMeshUploads",
            "worldDirectMeshDraws");
    }

    private static Map<String, Long> cameraCounters() throws IllegalAccessException {
        return rendererCounters("previewCameraReprojects", "previewCameraOrderReuses", "previewCameraOrderSorts");
    }

    private static Map<String, Long> workerCounters() throws IllegalAccessException {
        return rendererCounters(
            "previewGeometryPublications",
            "previewWorkerCompletedRuns",
            "previewWorkerSpecialFallbacks",
            "previewWorkerProjectedCells");
    }

    private static Object previewTiming() throws ReflectiveOperationException {
        try {
            java.lang.reflect.Method method = HologramRenderer.class.getSuperclass()
                .getDeclaredMethod("previewTiming");
            method.setAccessible(true);
            return method.invoke(null);
        } catch (NoSuchMethodException expectedOnBaseline) {
            return java.util.Collections.emptyMap();
        }
    }

    private static Map<String, Long> rendererCounters(String... names) throws IllegalAccessException {
        Map<String, Long> counters = new LinkedHashMap<>();
        Class<?> renderer = HologramRenderer.class.getSuperclass();
        for (String name : names) {
            try {
                Field count = renderer.getDeclaredField(name);
                count.setAccessible(true);
                counters.put(name, count.getLong(null));
            } catch (NoSuchFieldException expectedOnBaseline) {}
        }
        return counters;
    }

    private static Object residentDiagnostics() throws ReflectiveOperationException {
        try {
            return Class.forName("com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteMaterialBatch")
                .getMethod("diagnostics")
                .invoke(null);
        } catch (ClassNotFoundException expectedOnBaseline) {
            return java.util.Collections.emptyMap();
        }
    }

    private void verifyAeDelta() throws ReflectiveOperationException {
        if (!Boolean.getBoolean("gtmb.testlab.renderParityNativeDelta")) return;
        String flag = "gtmb.hologram.extendedCompatibilitySmoke";
        String previous = System.setProperty(flag, "true");
        try {
            HologramAeNativePreviewSmoke.class.getDeclaredMethod("verifyNativeDelta")
                .invoke(null);
            receipt.put("aeNativeDelta", "PASS");
        } finally {
            if (previous == null) System.clearProperty(flag);
            else System.setProperty(flag, previous);
        }
    }

    static Map<String, Object> cropScene(Path output, String original, String target, HologramUiModules ui)
        throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        Path pictures = output.resolve("screenshots");
        BufferedImage full = ImageIO.read(
            pictures.resolve(original)
                .toFile());
        require(full != null, "Missing readable current framebuffer: " + original);
        int[] area = ui.shell.nativeWidgetBounds(ui.shell.scene());
        require(area != null, "Native scene is not attached or visible");
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        int x = Math.max(0, area[0] * scale), y = Math.max(0, area[1] * scale);
        int width = Math.min(full.getWidth() - x, (area[0] + area[2]) * scale - x);
        int height = Math.min(full.getHeight() - y, (area[1] + area[3]) * scale - y);
        require(width > 0 && height > 0, "Invalid native framebuffer viewport");
        BufferedImage scene = full.getSubimage(x, y, width, height);
        require(
            ImageIO.write(
                scene,
                "png",
                pictures.resolve(target)
                    .toFile()),
            "No PNG encoder found");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("png", "screenshots/" + target);
        evidence.put("fullPng", "screenshots/" + original);
        evidence.put("bounds", new int[] { x, y, width, height });
        return evidence;
    }

    private static NBTTagCompound fixture(int index) throws Exception {
        NBTTagCompound data = switch (index) {
            case 1 -> HologramAeNativePreviewSmoke.fixture(false);
            case 2 -> HologramNativeCompatibilitySmoke.fixture();
            default -> new NBTTagCompound();
        };
        decorateFixture(data, CASES[index]);
        data.setInteger("y", 64);
        if (index == 0 || index == 3) {
            NBTTagList cells = new NBTTagList();
            if (index == 0) {
                for (int z = -3; z <= 3; z++) {
                    for (int x = -3; x <= 3; x++) cell(cells, x, 0, z, "stonebrick", 0);
                }
                for (int y = 1; y <= 3; y++) {
                    cell(cells, -2, y, -1, "stained_glass", 14);
                    cell(cells, -2, y, 0, "stained_glass", 3);
                    cell(cells, -1, y, 0, "glass", 0);
                    cell(cells, 0, y, 1, "glowstone", 0);
                    cell(cells, 1, y, 1, "planks", 2);
                    cell(cells, 2, y, 0, "oak_stairs", y);
                }
            } else {
                largeStructure(cells);
            }
            data.setTag("cells", cells);
        }
        return data;
    }

    private static void decorateFixture(NBTTagCompound data, String name) {
        data.setString("session", "gtmb-render-parity-" + name);
        data.setString("editorWorkspace", "blueprint");
        data.setBoolean("virtual", true);
        data.setBoolean("supported", true);
        data.setBoolean("target", true);
        data.setInteger("selected", -1);
    }

    private static void largeStructure(NBTTagList cells) {
        for (int y = 0; y < 21; y++) {
            int extent = 35 - 3 * (y / 3);
            for (int z = -extent; z <= extent; z++) {
                for (int x = -extent; x <= extent; x++) {
                    int edge = Math.max(Math.abs(x), Math.abs(z));
                    if (y == 0 || y % 3 == 0 && edge >= extent - 1) {
                        cell(cells, x, y, z, "stonebrick", Math.floorMod(x + z, 3));
                    } else if (edge == extent && Math.floorMod(x + z, 13) < 4) {
                        cell(cells, x, y, z, "stained_glass", Math.floorMod(x - z, 16));
                    }
                }
            }
        }
    }

    private static void cell(NBTTagList cells, int x, int y, int z, String block, int meta) {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        cell.setInteger("y", y + 64);
        cell.setInteger("z", z);
        cell.setInteger("dx", x);
        cell.setInteger("dy", y);
        cell.setInteger("dz", z);
        cell.setString("id", "minecraft:" + block);
        cell.setString("wantId", "minecraft:" + block);
        cell.setInteger("meta", meta);
        cell.setInteger("wantMeta", meta);
        cell.setString("status", "missing");
        cells.appendTag(cell);
    }

    private void finishFailed(Throwable failure) {
        failure.printStackTrace(System.err);
        try {
            finish(scenarioName() + ": " + failure);
        } catch (Exception receiptFailure) {
            receiptFailure.printStackTrace(System.err);
        }
    }

    private void finish(String failure) throws Exception {
        complete = true;
        Minecraft mc = Minecraft.getMinecraft();
        if (screen != null && mc.currentScreen == screen) mc.displayGuiScreen(menu);
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        try {
            restoreGiantLibrary();
        } catch (ReflectiveOperationException | RuntimeException cleanup) {
            failure = "Private fixture store restoration failed: " + cleanup;
        }
        boolean restored = mc.currentScreen == menu && mc.theWorld == null && mc.getIntegratedServer() == null;
        Map<String, Long> counters = residentCounters();
        if (failure == null && Boolean.getBoolean("gtmb.testlab.renderParityResidentRequired")
            && (counters.getOrDefault("previewResidentCaptures", 0L) <= 0
                || counters.getOrDefault("previewResidentDraws", 0L)
                    <= counters.getOrDefault("previewResidentCaptures", 0L))) {
            failure = "Missing resident buffer captures or replays";
        }
        boolean giantComplete = completedGiantLoads == requiredGiantLoads();
        boolean animationComplete = !animationWitness || animationFrames.size() == ANIMATION_SAMPLES;
        boolean passed = failure == null && restored
            && frames.size() == (giantPerformance ? 2 : 7)
            && inputDispatched
            && giantComplete
            && animationComplete;
        receipt.put("completed", true);
        receipt.put("passed", passed);
        receipt.put("outcome", passed ? "PASS" : "FAIL");
        receipt.put("detail", failure);
        receipt.put("nativeInputDispatched", inputDispatched);
        receipt.put("controlKey", "production editor scene left click");
        receipt.put("clientRenderVerified", !frames.isEmpty());
        receipt.put("screenRestored", restored);
        receipt.put("cleanupPending", !restored);
        receipt.put("osInput", false);
        receipt.put("residentRendererCounters", counters);
        receipt.put("cameraRendererCounters", cameraCounters());
        receipt.put("residentRendererDiagnostics", residentDiagnostics());
        receipt.put(
            "artifact",
            ArtifactIdentity.capture(HologramRenderParityProbe.class)
                .asMap());
        if (passed && Boolean.getBoolean(COMBINED)) {
            publish(output, EDITOR_RECEIPT, receipt);
            if (HologramTutorialIssue95Probe.install()) return;
            receipt.put("passed", false);
            receipt.put("outcome", "FAIL");
            receipt.put("detail", "Combined profile requires the native Issue95 playback");
        }
        publish(output, CLIENT_RECEIPT, receipt);
    }

    private void restoreGiantLibrary() throws ReflectiveOperationException {
        if (isolatedLibrary == null) return;
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        library.clearBrowsePreview();
        Field store = diagnosticField(HologramLibraryClient.class, "store");
        require(store.get(library) == isolatedLibrary, "Private fixture store changed owners");
        store.set(library, originalLibrary);
        Map<?, ?> sources = (Map<?, ?>) diagnosticField(HologramLibraryClient.class, "localPreviews").get(library);
        synchronized (sources) {
            sources.remove("local:" + giantLibraryId);
        }
        coldBrowseState = null;
        isolatedLibrary = null;
        receipt.put("fixtureLibraryStoreRestored", true);
    }

    static boolean completeCombined(Path output, String failure) {
        if (!Boolean.getBoolean(COMBINED)) return false;
        try {
            String editor = new String(Files.readAllBytes(output.resolve(EDITOR_RECEIPT)), StandardCharsets.UTF_8);
            JsonObject aggregate = new JsonParser().parse(editor)
                .getAsJsonObject();
            aggregate.addProperty("combinedPlaybackAndRawExport", failure == null);
            if (failure != null) {
                aggregate.addProperty("passed", false);
                aggregate.addProperty("outcome", "FAIL");
                aggregate.addProperty("detail", failure);
            }
            publish(output, CLIENT_RECEIPT, aggregate);
            return true;
        } catch (IOException problem) {
            throw new IllegalStateException("Cannot publish combined client receipt", problem);
        }
    }

    private static void publish(Path output, String name, Object content) throws IOException {
        Path temporary = Files.createTempFile(output, "gtmb-render-parity-", ".json");
        Files.write(
            temporary,
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(content)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, output.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
