package com.miaokatze.gtmb.client.hologram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.opengl.GL11;

import com.google.gson.GsonBuilder;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Explicit minimal native UI profile. Incoming fixture and synthetic MUI input are recorded honestly. */
public final class HologramTestLabUiProfile {

    private final long started = System.nanoTime();
    private final long timeout = Math.max(30, Math.min(1800, Long.getLong("gtmb.testlab.clientUiTimeoutSeconds", 600L)))
        * 1000000000L;
    private final Path output;
    private final Map<String, Object> receipt = new LinkedHashMap<String, Object>();
    private HologramScreen screen;
    private net.minecraft.client.gui.GuiScreen previous;
    private HologramState snapshot;
    private int stage;
    private long requestedGeneration, frameSerial, requestedFrame;
    private long openedAt;
    private long dispatchedAt;
    private boolean graphInitialized;
    private com.cleanroommc.modularui.widgets.Expandable drawer;
    private boolean drawerBefore;
    private int[] drawerBoundsBefore;
    private long lastDiagnostic;
    private String lastScreen = "";
    private int lastStage = -1;
    private boolean finished;

    private HologramTestLabUiProfile(Path output) {
        this.output = output;
    }

    public static boolean install() {
        if (!Boolean.getBoolean("gtmb.testlab.clientUi")) return false;
        String directory = System.getProperty("gtmb.testlab.clientUiReceiptDir", "");
        if (directory.isEmpty())
            throw new IllegalArgumentException("Explicit gtmb.testlab.clientUiReceiptDir required");
        HologramTestLabUiProfile profile = new HologramTestLabUiProfile(
            Paths.get(directory)
                .toAbsolutePath());
        FMLCommonHandler.instance()
            .bus()
            .register(profile);
        System.out.println("[GTMB-TESTLAB-UI] ACCEPTED receipt=" + profile.output.resolve("gtmb-client-ui.json"));
        return true;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            diagnostic(mc);
            if (System.nanoTime() - started > timeout)
                throw new IllegalStateException("Native UI profile timeout at stage " + stage);
            if (stage == 0) {
                boolean readyWorld = mc.theWorld != null && mc.thePlayer != null && mc.currentScreen == null;
                if (!readyTitle(mc.currentScreen) && !readyWorld) return;
                Files.createDirectories(output);
                previous = mc.currentScreen;
                receipt.put(
                    "entryScreenClass",
                    previous == null ? ""
                        : previous.getClass()
                            .getName());
                snapshot = fixture();
                screen = new HologramScreen(snapshot);
                screen.openFrom(previous);
                if (mc.currentScreen != screen)
                    throw new IllegalStateException("Normal MUI open lifecycle did not retain the requested screen");
                Object initialized = HologramTestLabUiDiagnostics.readDiagnosticField(screen, "initializedGraphs");
                graphInitialized = initialized instanceof java.util.Set
                    && ((java.util.Set<?>) initialized).contains(screen.ui)
                    && HologramTestLabUiDiagnostics.readDiagnosticField(screen, "workspaceMount") != null;
                if (!graphInitialized)
                    throw new IllegalStateException("Normal MUI open lifecycle did not build the workspace graph");
                receipt.put("openMethod", "HologramMuiScreen.openFrom");
                receipt.put("graphInitialized", true);
                requestedGeneration = screen.ui.canvas.renderGeneration();
                requestedFrame = frameSerial;
                openedAt = System.nanoTime();
                stage = 1;
            } else if (stage == 1) {
                if (mc.currentScreen != screen) throw new IllegalStateException("Native UI screen ownership changed");
                boolean attached = screen.panel()
                    .isValid()
                    && screen.getScreen()
                        .getPanelManager()
                        .isOpen()
                    && screen.panel()
                        .getArea().width > 0
                    && screen.panel()
                        .getArea().height > 0;
                if (!attached || frameSerial < requestedFrame + 2
                    || screen.ui.canvas.renderGeneration() <= requestedGeneration) {
                    if (System.nanoTime() - openedAt > 30000000000L) throw new IllegalStateException(
                        "No naturally drawn attached MUI workspace within 30 seconds; panelValid=" + screen.panel()
                            .isValid()
                            + " panelOpen="
                            + screen.getScreen()
                                .getPanelManager()
                                .isOpen()
                            + " size="
                            + screen.panel()
                                .getArea().width
                            + "x"
                            + screen.panel()
                                .getArea().height
                            + " graphInitialized="
                            + graphInitialized
                            + " generation="
                            + screen.ui.canvas.renderGeneration());
                    return;
                }
                receipt.put("panelAttached", true);
                receipt.put(
                    "panelWidth",
                    screen.panel()
                        .getArea().width);
                receipt.put(
                    "panelHeight",
                    screen.panel()
                        .getArea().height);
                drawer = (com.cleanroommc.modularui.widgets.Expandable) HologramTestLabUiDiagnostics
                    .readDiagnosticField(screen.ui.shell, "libraryDrawer");
                drawerBefore = drawer.isExpanded();
                drawerBoundsBefore = screen.ui.shell.targetBounds("library");
                if (drawerBoundsBefore == null || screen.ui.library.libraryDrawer() != drawerBefore)
                    throw new IllegalStateException(
                        "Native library drawer and presentation state disagree before input");
                receipt.put("drawerExpandedBefore", drawerBefore);
                receipt.put("drawerBoundsBefore", drawerBoundsBefore);
                Map<String, Object> input = new LinkedHashMap<String, Object>();
                receipt.put("nativeControl", input);
                HologramMuiProbeInput.nativeTarget(screen, "header.library", input);
                receipt.put("nativeInputDispatched", true);
                receipt.put("controlKey", "header.library");
                dispatchedAt = System.nanoTime();
                stage = 2;
            } else if (stage == 2) {
                if (mc.currentScreen != screen) throw new IllegalStateException("Native UI screen ownership changed");
                // The real header button defers its action until the next safe MUI tick.
                if (drawer.isExpanded() == drawerBefore || screen.ui.library.libraryDrawer() == drawerBefore) {
                    if (System.nanoTime() - dispatchedAt > 10000000000L) throw new IllegalStateException(
                        "Native header.library input did not toggle the drawer on a subsequent MUI tick");
                    return;
                }
                receipt.put("drawerExpandedAfter", drawer.isExpanded());
                receipt.put("deferredStateChangeObserved", true);
                requestedGeneration = screen.ui.canvas.renderGeneration();
                requestedFrame = frameSerial;
                stage = 3;
            }
        } catch (Throwable failure) {
            finish(false, failure.toString());
        }
    }

    /** Accept only a known ready title, never arbitrary custom screens, loading pages or error dialogs. */
    static boolean readyTitle(net.minecraft.client.gui.GuiScreen candidate) {
        if (candidate instanceof GuiMainMenu) return true;
        if (candidate == null || !"lumien.custommainmenu.gui.GuiCustom".equals(
            candidate.getClass()
                .getName()))
            return false;
        try {
            Object configuration = candidate.getClass()
                .getField("guiConfig")
                .get(candidate);
            return configuration != null && "mainmenu".equals(
                configuration.getClass()
                    .getField("name")
                    .get(configuration));
        } catch (ReflectiveOperationException unavailable) {
            return false;
        }
    }

    private void diagnostic(Minecraft mc) {
        String current = mc.currentScreen == null ? "<none>"
            : mc.currentScreen.getClass()
                .getName();
        long now = System.nanoTime();
        if (stage == lastStage && current.equals(lastScreen) && now - lastDiagnostic < 30000000000L) return;
        lastStage = stage;
        lastScreen = current;
        lastDiagnostic = now;
        System.out.println(
            "[GTMB-TESTLAB-UI] WAIT stage=" + stage
                + " screen="
                + current
                + " frames="
                + frameSerial
                + " generation="
                + (screen == null ? -1 : screen.ui.canvas.renderGeneration())
                + " panelValid="
                + (screen != null && screen.panel()
                    .isValid())
                + " panelOpen="
                + (screen != null && screen.getScreen()
                    .getPanelManager()
                    .isOpen())
                + " panelSize="
                + (screen == null ? "none"
                    : screen.panel()
                        .getArea().width + "x"
                        + screen.panel()
                            .getArea().height)
                + " graphInitialized="
                + graphInitialized);
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        frameSerial++;
        if (stage != 3 || frameSerial < requestedFrame + 2
            || screen.ui.canvas.renderGeneration() <= requestedGeneration) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.currentScreen != screen || screen.ui.session.state() != snapshot)
                throw new IllegalStateException("Post-input natural frame changed screen/snapshot identity");
            int[] after = screen.ui.shell.targetBounds("library");
            boolean resized = after != null
                && (drawerBefore ? after[2] < drawerBoundsBefore[2] : after[2] > drawerBoundsBefore[2]);
            if (!resized) {
                if (System.nanoTime() - dispatchedAt > 10000000000L) throw new IllegalStateException(
                    "Drawer state changed without visible native layout change; before="
                        + java.util.Arrays.toString(drawerBoundsBefore)
                        + " after="
                        + java.util.Arrays.toString(after));
                return;
            }
            if (drawer.isExpanded() == drawerBefore || screen.ui.library.libraryDrawer() == drawerBefore)
                throw new IllegalStateException("Native drawer state reverted before the post-input natural frame");
            receipt.put("drawerBoundsAfter", after);
            receipt.put("drawerVisibleLayoutChanged", true);
            GL11.glFinish();
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("Natural UI frame GL error: " + error);
            ScreenShotHelper.saveScreenshot(
                output.toFile(),
                "gtmb-native-ui.png",
                mc.displayWidth,
                mc.displayHeight,
                mc.getFramebuffer());
            Path png = output.resolve("screenshots/gtmb-native-ui.png");
            byte[] bytes = Files.readAllBytes(png);
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(png.toFile());
            if (bytes.length < 64 || image == null
                || image.getWidth() != mc.displayWidth
                || image.getHeight() != mc.displayHeight)
                throw new IllegalStateException("Native screenshot is not a decodable current framebuffer");
            StringBuilder hash = new StringBuilder();
            for (byte value : java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            receipt.put("pngPath", png.toString());
            receipt.put("pngSha256", hash.toString());
            receipt.put("pngWidth", image.getWidth());
            receipt.put("pngHeight", image.getHeight());
            receipt.put("captureEvent", "RenderTick.END");
            receipt.put("requestedGeneration", requestedGeneration);
            receipt.put("actualGeneration", screen.ui.canvas.renderGeneration());
            receipt.put("requestedFrameSerial", requestedFrame);
            receipt.put("actualFrameSerial", frameSerial);
            int[] viewport = screen.ui.shell.targetBounds("viewport");
            if (viewport == null) throw new IllegalStateException("Post-input native viewport is not visible");
            Map<String, Object> layout = new LinkedHashMap<String, Object>();
            layout.put("graph", "HologramEditorShell");
            layout.put("viewportBounds", viewport);
            layout.put("libraryBounds", after);
            layout.put(
                "panelBounds",
                new int[] { screen.panel()
                    .getArea().x,
                    screen.panel()
                        .getArea().y,
                    screen.panel()
                        .getArea().width,
                    screen.panel()
                        .getArea().height });
            receipt.put("layout", layout);
            receipt.put("clientRenderVerified", true);
            finish(true, "Real MUI2 screen and native control dispatch followed by a naturally rendered framebuffer");
        } catch (Throwable failure) {
            finish(false, failure.toString());
        }
    }

    private static HologramState fixture() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", "miaotest-client-ui-fixture");
        state.setString("status", "Explicit incoming snapshot fixture: native UI only");
        state.setBoolean("virtual", true);
        state.setBoolean("supported", true);
        state.setBoolean("target", true);
        state.setInteger("y", 64);
        state.setInteger("selected", -1);
        state.setIntArray("selectedIndices", new int[0]);
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 3; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("dx", i);
            cell.setInteger("x", i);
            cell.setInteger("y", 64);
            cell.setString("id", "minecraft:stone");
            cell.setString("wantId", "minecraft:stone");
            cell.setString("status", "missing");
            cells.appendTag(cell);
        }
        state.setTag("cells", cells);
        return new HologramState(state);
    }

    private void finish(boolean passed, String detail) {
        if (finished) return;
        finished = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen == screen && screen != null) mc.displayGuiScreen(previous);
            receipt.put("screenRestored", screen == null || mc.currentScreen != screen);
            receipt.put("cleanupPending", false);
        } catch (Throwable failure) {
            passed = false;
            detail = "UI cleanup failed: " + failure;
            receipt.put("cleanupPending", true);
        }
        try {
            receipt.put("schema", "gtmb-native-ui-profile-v1");
            receipt.put("module", "gtmb");
            receipt.put("caseId", "client-ui");
            receipt.put("completed", true);
            receipt.put("passed", passed);
            receipt.put("outcome", passed ? "PASS" : "FAIL");
            receipt.put("detail", detail);
            receipt.put("snapshotKind", "explicit-incoming-client-fixture");
            receipt.put("actorKind", "none-required");
            receipt.put("actualNetty", false);
            receipt.put("nettyDispatchVerified", false);
            receipt.put("inputKind", "native-MUI2-synthetic-dispatch");
            receipt.put("osInput", false);
            receipt.put("paymentVerified", false);
            receipt.put(
                "artifact",
                ArtifactIdentity.capture(HologramTestLabUiProfile.class)
                    .asMap());
            if (!receipt.containsKey("clientRenderVerified")) receipt.put("clientRenderVerified", false);
            Files.createDirectories(output);
            Path temporary = Files.createTempFile(output, "gtmb-client-ui-", ".tmp");
            Files.write(
                temporary,
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(receipt)
                    .getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                    temporary,
                    output.resolve("gtmb-client-ui.json"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, output.resolve("gtmb-client-ui.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable failure) {
            passed = false;
            detail = "Receipt publication failed: " + failure;
            failure.printStackTrace(System.err);
        }
        System.out.println("[GTMB-TESTLAB-UI] FINAL " + (passed ? "PASS" : "FAIL") + " " + detail);
    }
}
