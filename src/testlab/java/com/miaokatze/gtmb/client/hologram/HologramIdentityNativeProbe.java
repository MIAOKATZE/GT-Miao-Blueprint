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
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.google.gson.GsonBuilder;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Explicit incoming fixtures: real native modal, natural frames and synthetic MUI input; no server ACK/payment. */
public final class HologramIdentityNativeProbe {

    private final Path output = Paths.get(System.getProperty("gtmb.testlab.clientUiReceiptDir"))
        .toAbsolutePath();
    private final Map<String, Object> receipt = new LinkedHashMap<>();
    private final List<NBTTagCompound> requests = new ArrayList<>();
    private final long started = System.nanoTime();
    private GuiScreen previous;
    private HologramScreen screen;
    private Object previousMemory;
    private int previousScale, stage, frames, lastTransition;
    private boolean finished, memoryCaptured;

    static boolean install() {
        HologramIdentityNativeProbe probe = new HologramIdentityNativeProbe();
        FMLCommonHandler.instance()
            .bus()
            .register(probe);
        MinecraftForge.EVENT_BUS.register(probe);
        return true;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        try {
            if (System.nanoTime() - started > 480_000_000_000L)
                throw new IllegalStateException("Identity UI timeout stage " + stage);
            Minecraft mc = Minecraft.getMinecraft();
            if (stage == 0) {
                if (!HologramTestLabUiProfile.readyTitle(mc.currentScreen)) return;
                Files.createDirectories(output.resolve("screenshots"));
                previous = mc.currentScreen;
                previousScale = mc.gameSettings.guiScale;
                java.lang.reflect.Field memory = HologramDeidentificationMemory.class.getDeclaredField("client");
                memory.setAccessible(true);
                previousMemory = memory.get(null);
                memoryCaptured = true;
                memory.set(
                    null,
                    new HologramDeidentificationMemory(
                        output.resolve("identity-fixture-preferences.properties")
                            .toFile()));
                open(true, 0, true);
                stage = 1;
            } else if (stage == 3 && !screen.ui.identity.deidentificationDrawer()) {
                requireRequest(3);
                receipt.put("savedBlueprintApply", true);
                mc.gameSettings.guiScale = 3;
                mc.resize(mc.displayWidth, mc.displayHeight);
                open(false, 0, true);
                transition(4);
            } else if (stage == 5 && !screen.ui.identity.deidentificationDrawer()) {
                requireRequest(0);
                receipt.put("unsavedFinitePlacementEscape", true);
                open(false, 4, false);
                screen.ui.identity.openDeidentification(true);
                transition(6);
            } else if (stage == 7 && frames >= lastTransition + 3) {
                require(requests.size() == 2, "pending action must not dispatch");
                require(screen.ui.identity.deidentificationDrawer(), "pending action keeps identity context");
                screen.ui.session.state().data.setBoolean("capturePending", false);
                screen.ui.identity.openDeidentification(false);
                HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                transition(8);
            } else if (stage == 8 && !screen.ui.identity.deidentificationDrawer()) {
                require(requests.size() == 2, "save cancel must not submit");
                receipt.put("saveCancelNoRequest", true);
                receipt.put("pendingSubmissionBlocked", true);
                openAcceptedDraft();
                transition(10);
            } else if (stage == 11) {
                NBTTagCompound captured = (NBTTagCompound) HologramTestLabUiDiagnostics
                    .readDiagnosticField(screen.ui.diagnostics, "capturedRenderSmokeAction");
                if (captured == null) return;
                require(
                    "placeCurrentBlueprint".equals(captured.getString("op"))
                        && screen.ui.session.state().session.equals(captured.getString("session"))
                        && screen.ui.session.revision() == captured.getLong("planRevision")
                        && screen.ui.session.state().data.getLong("editorWorkspaceEpoch")
                            == captured.getLong("editorWorkspaceEpoch"),
                    "current draft intent retains authenticated context");
                Map<String, Object> intent = new LinkedHashMap<>();
                intent.put("op", captured.getString("op"));
                intent.put("session", captured.getString("session"));
                intent.put("planRevision", captured.getLong("planRevision"));
                intent.put("editorWorkspaceEpoch", captured.getLong("editorWorkspaceEpoch"));
                receipt.put("currentDraftNativeIntent", intent);
                receipt.put("currentDraftNativeClick", true);
                finish(true, "Native identity fixtures passed; server transactions/physical input NOT_RUN");
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(false, failure.toString());
        }
    }

    private void transition(int next) {
        stage = next;
        lastTransition = frames;
    }

    private void openAcceptedDraft() throws Exception {
        String file = System.getProperty("gtmb.testlab.currentDraftFixture", "");
        require(!file.isEmpty(), "explicit production-generated accepted attachment fixture is required");
        Path fixture = Paths.get(file)
            .toAbsolutePath();
        byte[] bytes = Files.readAllBytes(fixture);
        NBTTagCompound data = com.miaokatze.gtmb.hologram.HologramSnapshotCodec.decode(bytes);
        require(
            data.getBoolean("virtual") && data.getBoolean("blueprintDirty")
                && !data.getBoolean("virtualPlacement")
                && !data.getBoolean("attachmentPending")
                && data.getCompoundTag("textPlacementAck")
                    .getBoolean("accepted")
                && data.getTagList("cells", 10)
                    .tagCount() > 0,
            "accepted dirty draft fixture is ready for placement");
        receipt.put("currentDraftFixture", fixture.toString());
        StringBuilder hash = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        receipt.put("currentDraftFixtureSha256", hash.toString());
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.guiScale = previousScale;
        mc.resize(mc.displayWidth, mc.displayHeight);
        GuiScreen parent = mc.currentScreen;
        screen = new HologramScreen(new HologramState(data));
        java.lang.reflect.Field capture = HologramTestLabUiDiagnostics.class
            .getDeclaredField("captureRenderSmokeAction");
        capture.setAccessible(true);
        capture.setBoolean(screen.ui.diagnostics, true);
        screen.openFrom(parent);
        screen.ui.shell.selectDockPage(2);
        require(!screen.ui.session.isTutorial(), "current draft uses the normal production session gates");
    }

    private void open(boolean saved, int minimum, boolean offer) throws Exception {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "identity-fixture-" + (saved ? "saved" : "draft") + "-" + minimum);
        data.setLong("planRevision", 17);
        data.setLong("placementReviewNonce", 29 + minimum);
        data.setBoolean("virtual", true);
        data.setBoolean("virtualPlacement", true);
        data.setBoolean("virtualLocked", true);
        data.setBoolean("blueprintAwaitingConfirmation", true);
        data.setBoolean("placementDeidentificationRecommended", offer);
        data.setBoolean("supported", true);
        data.setBoolean("target", true);
        data.setBoolean("open", true);
        data.setInteger("selected", -1);
        data.setInteger("deidentificationMinimumLevel", minimum);
        data.setInteger("deidentificationRecommendedLevel", 1);
        data.setString("virtualPresetId", saved ? "identity-fixture-library-id" : "");
        data.setString("virtualPresetName", saved ? "已保存蓝图 · 校验夹具" : "临时有限蓝图 · 校验夹具");
        data.setString("blueprintSavedId", saved ? "identity-fixture-library-id" : "");
        data.setBoolean("blueprintSaved", saved);
        data.setBoolean("blueprintDirty", !saved);
        data.setString("blueprintSource", saved ? "saved" : "draft");
        NBTTagCompound cell = new NBTTagCompound();
        cell.setString("id", "minecraft:stone");
        cell.setString("wantId", "minecraft:stone");
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        data.setTag("cells", cells);
        GuiScreen parent = Minecraft.getMinecraft().currentScreen;
        screen = new HologramScreen(new HologramState(data));
        // Probe-only transport capture. No ACK is invented and no server result is applied to the snapshot.
        java.lang.reflect.Field sink = HologramUiSession.class.getDeclaredField("tutorialSink");
        sink.setAccessible(true);
        sink.set(screen.ui.session, (java.util.function.BiConsumer<String, NBTTagCompound>) (op, request) -> {
            if (!"configurePlacementDeidentification".equals(op)) return;
            NBTTagCompound copy = (NBTTagCompound) request.copy();
            copy.setString("op", op);
            requests.add(copy);
        });
        screen.openFrom(parent);
        lastTransition = frames;
    }

    @SubscribeEvent
    public void drawn(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (finished || screen == null || event.gui != screen) return;
        frames++;
        try {
            if (frames < lastTransition + 4) return;
            int[] panel = screen.ui.shell.targetBounds(stage == 10 ? "library.footer" : "identity");
            if (panel == null) return;
            require(
                panel[0] >= 0 && panel[1] >= 0
                    && panel[0] + panel[2] <= screen.width
                    && panel[1] + panel[3] <= screen.height,
                "native identity panel is inside GUI");
            if (stage == 1) {
                capture("gtmb-native-ui.png");
                receipt.put("savedPanelBounds", panel);
                click(22003);
                transition(2);
            } else if (stage == 2) {
                require(screen.ui.identity.selectedLevel() == 3, "native level click updates selection");
                capture("gtmb-identity-selected3.png");
                HologramMuiProbeInput.key(screen, '\n', Keyboard.KEY_RETURN);
                transition(3);
            } else if (stage == 4) {
                capture("gtmb-identity-narrow.png");
                receipt.put("draftPanelBounds", panel);
                receipt.put("narrowGuiSize", new int[] { screen.width, screen.height });
                HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                transition(5);
            } else if (stage == 6) {
                require(screen.ui.identity.selectedLevel() == 4, "minimum level clamps choice");
                require(!screen.ui.shell.controlEnabled(22003), "level below minimum is disabled");
                require(screen.ui.shell.controlEnabled(22004), "minimum level remains selectable");
                receipt.put("minimumLevelDisabled", true);
                capture("gtmb-identity-minimum.png");
                int[] viewport = screen.ui.shell.targetBounds("identity.viewport");
                require(viewport != null, "identity viewport is visible");
                // ScrollWidget reads the context's native wheel value, rather than the dispatch direction argument.
                // Set only this probe event's context and restore it immediately after synthetic MUI dispatch.
                java.lang.reflect.Field wheel = com.cleanroommc.modularui.screen.viewport.GuiContext.class
                    .getDeclaredField("mouseWheel");
                wheel.setAccessible(true);
                Object context = screen.getScreen()
                    .getContext();
                int previousWheel = wheel.getInt(context);
                try {
                    wheel.setInt(context, -120);
                    for (int i = 0; i < 12; i++) HologramMuiProbeInput
                        .scroll(screen, viewport[0] + viewport[2] / 2, viewport[1] + viewport[3] / 2, -1);
                } finally {
                    wheel.setInt(context, previousWheel);
                }
                transition(9);
            } else if (stage == 9) {
                if (frames < lastTransition + 8) return;
                Object targets = HologramTestLabUiDiagnostics.readDiagnosticField(screen.ui.shell, "targets");
                Object widget = ((java.util.List<?>) ((Map<?, ?>) targets).get("identity.viewport")).get(0);
                com.cleanroommc.modularui.widget.ScrollWidget<?> viewport = (com.cleanroommc.modularui.widget.ScrollWidget<?>) widget;
                int end = viewport.getScrollArea()
                    .getScrollY()
                    .getScrollSize() - viewport.getArea().height;
                require(end > 0 && viewport.getScrollY() == end, "narrow native viewport reaches content end");
                receipt.put("narrowScrollOffset", viewport.getScrollY());
                capture("gtmb-identity-scroll-bottom.png");
                receipt.put("narrowNativeScroll", true);
                screen.ui.session.state().data.setBoolean("capturePending", true);
                HologramMuiProbeInput.key(screen, '\n', Keyboard.KEY_RETURN);
                transition(7);
            } else if (stage == 10) {
                require(
                    screen.ui.library.nativeCanPlaceCurrent() && screen.ui.shell.controlEnabled(311),
                    "current accepted unsaved draft button is enabled through the production UI gate");
                int[] current = screen.ui.shell.controlBounds(311);
                int[] saved = screen.ui.shell.targetBounds("control.141");
                require(current != null, "current draft native button is visible");
                // The separate saved-selection action remains in the footer even when no saved row is selected.
                Object targets = HologramTestLabUiDiagnostics.readDiagnosticField(screen.ui.shell, "targets");
                List<?> savedWidgets = (List<?>) ((Map<?, ?>) targets).get("control.141");
                require(
                    savedWidgets != null && !savedWidgets.isEmpty(),
                    "saved selection retains its own native button");
                receipt.put("currentDraftButtonBounds", current);
                receipt.put("savedSelectionEnabled", screen.ui.shell.controlEnabled(141));
                receipt.put("savedSelectionBoundsWhenEnabled", saved);
                capture("gtmb-current-draft-placement.png");
                Map<String, Object> input = new LinkedHashMap<>();
                HologramMuiProbeInput.nativeTarget(screen, "control.311", input);
                receipt.put("currentDraftNativeInput", input);
                transition(11);
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(false, failure.toString());
        }
    }

    private void click(int id) {
        int[] bounds = screen.ui.shell.controlBounds(id);
        require(bounds != null, "native level control is visible");
        HologramMuiProbeInput.click(screen, bounds[0] + bounds[2] / 2, bounds[1] + bounds[3] / 2, 0);
        receipt.put("nativeInputDispatched", true);
    }

    private void requireRequest(int level) {
        require(!requests.isEmpty(), "identity command captured");
        NBTTagCompound request = requests.get(requests.size() - 1);
        require(
            request.getInteger("level") == level && request.getInteger("deidentificationLevel") == level
                && request.getLong("planRevision") == 17
                && request.getLong("placementReviewNonce") == 29,
            "identity choice retains level/revision/nonce");
    }

    private void capture(String name) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        ScreenShotHelper.saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        require(
            Files.isRegularFile(
                output.resolve("screenshots")
                    .resolve(name)),
            "actual PNG exists");
        receipt.put("clientRenderVerified", true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void finish(boolean passed, String detail) {
        if (finished) return;
        finished = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (previous != null) {
                mc.gameSettings.guiScale = previousScale;
                mc.resize(mc.displayWidth, mc.displayHeight);
            }
            if (screen != null && mc.currentScreen == screen) mc.displayGuiScreen(previous);
            if (memoryCaptured) {
                java.lang.reflect.Field memory = HologramDeidentificationMemory.class.getDeclaredField("client");
                memory.setAccessible(true);
                memory.set(null, previousMemory);
            }
            receipt.put("screenRestored", mc.currentScreen == previous);
            receipt.put("cleanupPending", false);
        } catch (Throwable failure) {
            passed = false;
            receipt.put("cleanupPending", true);
            detail += "; cleanup " + failure;
        }
        try {
            receipt.put("module", "gtmb");
            receipt.put("caseId", "client-ui");
            receipt.put("profile", "identity-native-incoming-fixtures");
            receipt.put("completed", true);
            receipt.put("passed", passed);
            receipt.put("outcome", passed ? "PASS" : "FAIL");
            receipt.put("detail", detail);
            receipt.put("controlKey", "identity.22003");
            receipt.put("inputKind", "synthetic-MUI2-dispatch");
            receipt.put("commandSink", "probe-only captured intent; no ACK or state result applied");
            receipt.put("actualNetty", false);
            receipt.put("paymentVerified", false);
            receipt.put("osInput", false);
            List<Map<String, Object>> intents = new ArrayList<>();
            for (NBTTagCompound request : requests) {
                Map<String, Object> intent = new LinkedHashMap<>();
                intent.put("op", request.getString("op"));
                intent.put("level", request.getInteger("level"));
                intent.put("planRevision", request.getLong("planRevision"));
                intent.put("placementReviewNonce", request.getLong("placementReviewNonce"));
                intents.add(intent);
            }
            receipt.put("capturedIntents", intents);
            receipt.put(
                "artifact",
                ArtifactIdentity.capture(getClass())
                    .asMap());
            Files.createDirectories(output);
            Files.write(
                output.resolve("gtmb-client-ui.json"),
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(receipt)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            failure.printStackTrace();
        }
    }
}
