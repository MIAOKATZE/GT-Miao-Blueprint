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
    private static final String ANIMATION_WITNESS = "gtmb.testlab.renderParityAnimationWitness";
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
    private final Map<String, Object> giantEvidence = new LinkedHashMap<>();
    private final List<Map<String, Object>> giantFrames = new ArrayList<>();
    private final List<Long> giantOrbitDrawNanos = new ArrayList<>();
    private final boolean animationWitness = Boolean.getBoolean(ANIMATION_WITNESS);
    private final List<Map<String, Object>> animationFrames = new ArrayList<>();
    private HologramScreen screen;
    private HologramState snapshot;
    private int scenario, phase, draws, poseDraws, orbitIndex;
    private long activated = System.nanoTime();
    private long beforeDraw, drawNanos, guiPosts, renderedPosts;
    private long giantStarted;
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
        if (!giantSource.isEmpty()) {
            giantEvidence.put("frames", giantFrames);
            giantEvidence.put("orbitEditorDrawNanos", giantOrbitDrawNanos);
            giantEvidence.put(
                "heapMaxBytes",
                Runtime.getRuntime()
                    .maxMemory());
            receipt.put("giant", giantEvidence);
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean(OPT_IN);
    }

    static boolean install(GuiScreen menu, Path output, String run, String tutorialFailure) throws Exception {
        if (!enabled()) return false;
        HologramRenderParityProbe probe = new HologramRenderParityProbe(menu, output, run);
        if (tutorialFailure != null) {
            probe.finish("Issue100 tutorial failed: " + tutorialFailure);
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
                if (scenario == CASES.length + (giantSource.isEmpty() ? 0 : 1)) {
                    verifyAeDelta();
                    finish(null);
                    return;
                }
                snapshot = giant() ? giantSnapshot() : new HologramState(fixture(scenario));
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
            if (phase == 1 && scenario == 0) {
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
                    mc.displayGuiScreen(menu);
                    screen = null;
                    scenario++;
                }
            }
        } catch (Exception | LinkageError failure) {
            finishFailed(failure);
        }
    }

    private boolean giant() {
        return scenario == CASES.length && !giantSource.isEmpty();
    }

    private boolean orbitalCase() {
        return scenario == CASES.length - 1 || giant();
    }

    private String scenarioName() {
        if (scenario < CASES.length) return CASES[scenario];
        return giant() ? "giant-private-blueprint" : "post-editor checks";
    }

    private HologramState giantSnapshot() throws Exception {
        giantStarted = System.nanoTime();
        Path path = Paths.get(giantSource);
        require(
            Files.isRegularFile(path) && Files.size(path) <= HologramBlueprintArtifact.MAX_JSON_BYTES,
            "Private blueprint source must be the local copy within the artifact byte limit");
        byte[] source = Files.readAllBytes(path);
        giantEvidence.put("sampleKind", "read-only copy of a private library blueprint");
        giantEvidence.put("sourceJsonSha256", HologramBlueprintArtifact.hash(source));
        giantEvidence.put("sourceBytes", source.length);
        NBTTagCompound data = HologramBlueprintPreview.snapshot(source);
        decorateFixture(data, scenarioName());
        HologramState state = new HologramState(data);
        require(state.cells.size() == GIANT_CELLS, "Expected the selected 123369-cell private structure");
        giantEvidence.put("cells", state.cells.size());
        giantEvidence.put("snapshotNanos", System.nanoTime() - giantStarted);
        return state;
    }

    private void setPose(double yaw) {
        screen.ui.preview.yaw(yaw);
        screen.ui.preview.pitch(0.58);
        screen.ui.preview.zoom(1.0);
        poseDraws = 0;
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
            boolean ready = HologramRenderer.previewMeshReady(snapshot);
            if (!ready && !giant()) return;
            require(screen.ui.session.state().identity == snapshot.identity, "The prepared fixture changed owners");
            HologramEditorSceneWidget scene = (HologramEditorSceneWidget) screen.ui.shell.scene();
            if (scene == null) return;
            if (giant()) observeGiant(ready);
            if (!ready) return;
            if (!scene.editFrameReady()) return;
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
                String file = "giant-first-full.png";
                captureFramebuffer(file);
                giantEvidence.put("firstMaterialScene", cropScene(output, file, "giant-first-scene.png", screen.ui));
            }
        }
        if (ready && !giantEvidence.containsKey("completeNanos")) {
            giantEvidence.put("completeNanos", elapsed);
            giantEvidence.put("completedCells", HologramRenderer.previewMeshCompleted(snapshot));
        }
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
        frame
            .put("pose", new double[] { screen.ui.preview.yaw(), screen.ui.preview.pitch(), screen.ui.preview.zoom() });
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
        return rendererCounters("previewResidentCaptures", "previewResidentDraws");
    }

    private static Map<String, Long> cameraCounters() throws IllegalAccessException {
        return rendererCounters("previewCameraReprojects", "previewCameraOrderReuses", "previewCameraOrderSorts");
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
            return Class.forName("com.miaokatze.gtmb.client.miaovisonlite.MiaoVisonLiteMaterialBatch")
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
        boolean restored = mc.currentScreen == menu && mc.theWorld == null && mc.getIntegratedServer() == null;
        Map<String, Long> counters = residentCounters();
        if (failure == null && Boolean.getBoolean("gtmb.testlab.renderParityResidentRequired")
            && (counters.getOrDefault("previewResidentCaptures", 0L) <= 0
                || counters.getOrDefault("previewResidentDraws", 0L)
                    <= counters.getOrDefault("previewResidentCaptures", 0L))) {
            failure = "Missing resident buffer captures or replays";
        }
        boolean giantComplete = giantSource.isEmpty()
            || giantFrames.size() == GIANT_ORBIT.length && giantEvidence.containsKey("firstMaterialNanos")
                && Integer.valueOf(GIANT_CELLS)
                    .equals(giantEvidence.get("completedCells"));
        boolean animationComplete = !animationWitness || animationFrames.size() == ANIMATION_SAMPLES;
        boolean passed = failure == null && restored
            && frames.size() == 7
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
