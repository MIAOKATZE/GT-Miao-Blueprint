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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.google.gson.GsonBuilder;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Explicit, bounded blueprint playback diagnosis. Never changes preferences or business state. */
public final class HologramTutorialIssue95Probe {

    private final Path output;
    private final long started = System.nanoTime();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private HologramScreen screen;
    private GuiScreen previous;
    private long frame, postFrame = -1;
    private int capturedStep = -1, dispatchedStep = -1;
    private boolean finished;
    private boolean displayPrepared;
    private DisplayMode previousDisplayMode;
    private int previousWidth, previousHeight, previousGuiScale;

    private HologramTutorialIssue95Probe(Path output) {
        this.output = output;
    }

    static boolean install() {
        if (!Boolean.getBoolean("gtmb.testlab.tutorialIssue95")) return false;
        String directory = System.getProperty("gtmb.testlab.clientUiReceiptDir", "");
        if (directory.isEmpty()) throw new IllegalArgumentException("Explicit clientUiReceiptDir required");
        HologramTutorialIssue95Probe probe = new HologramTutorialIssue95Probe(Paths.get(directory));
        FMLCommonHandler.instance()
            .bus()
            .register(probe);
        MinecraftForge.EVENT_BUS.register(probe);
        return true;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (System.nanoTime() - started > 480_000_000_000L)
                throw new IllegalStateException("Blueprint tutorial diagnosis timeout");
            if (screen == null) {
                if (!HologramTestLabUiProfile.readyTitle(mc.currentScreen)) return;
                if (!displayPrepared) {
                    displayPrepared = true;
                    previous = mc.currentScreen;
                    if (Boolean.getBoolean("gtmb.testlab.tutorialIssue95OriginalSize")) {
                        previousDisplayMode = Display.getDisplayMode();
                        previousWidth = mc.displayWidth;
                        previousHeight = mc.displayHeight;
                        previousGuiScale = mc.gameSettings.guiScale;
                        // The report includes a 30px window title; 3x toolbar pixels imply GUI scale 3.
                        // Use the native client resize path, confined to this run's private desktop.
                        Display.setDisplayMode(new DisplayMode(2500, 1410));
                        mc.gameSettings.guiScale = 3;
                        mc.resize(2500, 1410);
                        return;
                    }
                }
                if (Boolean.getBoolean("gtmb.testlab.tutorialIssue95RestoreOnly")) {
                    if (previousDisplayMode == null || mc.displayWidth != 2500
                        || mc.displayHeight != 1410
                        || mc.gameSettings.guiScale != 3
                        || Display.getWidth() != 2500
                        || Display.getHeight() != 1410)
                        throw new IllegalStateException(
                            "Restoration-only path did not reach the requested display size and GUI scale");
                    finish(
                        "NOT_RUN",
                        "Native title capture, original-size resize and restoration only; no tutorial playback");
                    return;
                }
                Files.createDirectories(output.resolve("screenshots"));
                screen = HologramScreen.detachedTutorial("blueprint", true, () -> {});
                screen.openFrom(previous);
                return;
            }
            if (mc.currentScreen != screen) throw new IllegalStateException("Tutorial screen ownership changed");
            if (capturedStep >= 0 && dispatchedStep != capturedStep) {
                NBTTagCompound state = screen.ui.tutorial.diagnosticState();
                if (capturedStep + 1 == state.getInteger("stepCount")) {
                    finish("PASS", "All blueprint step actions completed; final completion intentionally not clicked");
                    return;
                }
                clickNext();
                dispatchedStep = capturedStep;
            }
        } catch (Throwable failure) {
            finish("FAIL", failure.toString());
        }
    }

    private void clickNext() throws ReflectiveOperationException {
        Object overlay = HologramTestLabUiDiagnostics.readDiagnosticField(screen.ui.tutorial, "overlay");
        IWidget[] navigation = (IWidget[]) HologramTestLabUiDiagnostics.readDiagnosticField(overlay, "navigation");
        HologramUiModules sandbox = screen.ui.tutorial.renderUi();
        IWidget next = navigation[1];
        int[] bounds = sandbox.shell.nativeWidgetBounds(next);
        if (bounds == null || bounds[2] <= 0 || bounds[3] <= 0 || !next.isEnabled())
            throw new IllegalStateException("Native tutorial next button unavailable");
        int x = bounds[0] + bounds[2] / 2, y = bounds[1] + bounds[3] / 2;
        screen.getScreen()
            .getContext()
            .updateState(x, y, 0);
        screen.getScreen()
            .onFrameUpdate();
        boolean hit = false;
        for (LocatedWidget located : next.getPanel()
            .getHovering()) {
            if (located.getElement() == next) hit = true;
        }
        if (!hit) throw new IllegalStateException("Native tutorial next button hit test missed");
        steps.get(steps.size() - 1)
            .put("nextButtonHit", true);
        HologramMuiProbeInput.click(screen, x, y, 0);
    }

    @SubscribeEvent
    public void guiPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!finished && screen != null && event.gui == screen) postFrame = frame;
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        frame++;
        if (screen == null || Minecraft.getMinecraft().currentScreen != screen || postFrame != frame - 1) return;
        try {
            NBTTagCompound state = screen.ui.tutorial.diagnosticState();
            if (!state.getBoolean("active")) return;
            boolean blocked = "blocked".equals(state.getString("phase"));
            boolean complete = state.getInteger("action") == state.getInteger("actionCount")
                && !state.getBoolean("actionPending");
            if (!blocked && (!complete || state.getInteger("step") == capturedStep)) return;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", state.getInteger("step"));
            entry.put("diagnosticSnbt", state.toString());
            entry.put("frame", frame);
            entry.put("displayWidth", Minecraft.getMinecraft().displayWidth);
            entry.put("displayHeight", Minecraft.getMinecraft().displayHeight);
            entry.put("guiScale", Minecraft.getMinecraft().gameSettings.guiScale);
            entry.put("logicalWidth", screen.width);
            entry.put("logicalHeight", screen.height);
            entry.put("captureEvent", "RenderTick.END after exact-screen DrawScreenEvent.Post");
            String name = String
                .format("gtmb-tutorial-step-%02d%s.png", state.getInteger("step"), blocked ? "-blocked" : "");
            Minecraft mc = Minecraft.getMinecraft();
            ScreenShotHelper
                .saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            if (!Files.isRegularFile(
                output.resolve("screenshots")
                    .resolve(name)))
                throw new IllegalStateException("Natural tutorial frame PNG missing");
            entry.put("png", "screenshots/" + name);
            steps.add(entry);
            capturedStep = state.getInteger("step");
            System.out.println("[GTMB-ISSUE95] step=" + capturedStep + " phase=" + state.getString("phase"));
            if (blocked) finish("FAIL", state.getString("unavailable"));
        } catch (Throwable failure) {
            finish("FAIL", failure.toString());
        }
    }

    private void finish(String outcome, String detail) {
        if (finished) return;
        finished = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schema", "gtmb-issue95-blueprint-playback-v1");
        boolean restorationOnly = Boolean.getBoolean("gtmb.testlab.tutorialIssue95RestoreOnly");
        receipt.put("mode", restorationOnly ? "restoration-only" : "blueprint-playback");
        receipt.put("playbackOutcome", outcome);
        receipt.put("outcome", restorationOnly && "NOT_RUN".equals(outcome) ? "PASS" : outcome);
        receipt.put("detail", detail);
        receipt.put("steps", steps);
        receipt.put(
            "artifact",
            ArtifactIdentity.capture(HologramTutorialIssue95Probe.class)
                .asMap());
        receipt.put("realBusinessSession", false);
        receipt.put("osInput", false);
        receipt.put("preferenceWrites", false);
        receipt.put("originalSizeComparison", Boolean.getBoolean("gtmb.testlab.tutorialIssue95OriginalSize"));
        receipt
            .put("reportedSizeInference", "2500x1440 image minus 30px title; GUI scale 3 inferred from toolbar pixels");
        try {
            Minecraft mc = Minecraft.getMinecraft();
            receipt.put("displayWidthBeforeRestore", mc.displayWidth);
            receipt.put("displayHeightBeforeRestore", mc.displayHeight);
            receipt.put("guiScaleBeforeRestore", mc.gameSettings.guiScale);
            receipt.put("nativeDisplayWidthBeforeRestore", Display.getWidth());
            receipt.put("nativeDisplayHeightBeforeRestore", Display.getHeight());
            boolean ownedPresentation = screen != null ? mc.currentScreen == screen
                : previous != null && HologramTestLabUiProfile.readyTitle(mc.currentScreen);
            if (screen != null) screen.ui.tutorial.closeWithoutProgress();
            boolean displayRestored = true;
            if (previousDisplayMode != null) {
                Display.setDisplayMode(previousDisplayMode);
                mc.gameSettings.guiScale = previousGuiScale;
                mc.resize(previousWidth, previousHeight);
                displayRestored = mc.displayWidth == previousWidth && mc.displayHeight == previousHeight
                    && mc.gameSettings.guiScale == previousGuiScale;
            }
            // CMM chooses its normal/large menu in GuiOpenEvent using the current GUI scale.
            // Restore the scale before reopening, so it retains the exact original menu instance.
            if (ownedPresentation) mc.displayGuiScreen(previous);
            boolean screenRestored = mc.currentScreen == previous || previous == null && screen == null;
            receipt.put("displayRestored", displayRestored);
            receipt.put("screenRestored", screenRestored);
            receipt.put("cleanupPending", !screenRestored || !displayRestored);
            if (!screenRestored || !displayRestored) {
                receipt.put("outcome", "FAIL");
                receipt.put("cleanupError", "Original screen identity or display settings were not restored");
            }
        } catch (Throwable failure) {
            receipt.put("outcome", "FAIL");
            receipt.put("cleanupPending", true);
            receipt.put("cleanupError", failure.toString());
        }
        try {
            Files.createDirectories(output);
            Files.write(
                output.resolve("gtmb-issue95-tutorial.json"),
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(receipt)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot publish tutorial diagnostic receipt", failure);
        }
        // The existing profile retains its own unchanged assertions and separate receipt.
        HologramTestLabUiProfile.install();
    }
}
