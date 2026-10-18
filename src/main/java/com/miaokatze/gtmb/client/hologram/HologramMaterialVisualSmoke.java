package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Default-off, menu-only real-MUI render fixture. No server, crafting CPU, desktop input or world writes. */
public final class HologramMaterialVisualSmoke {

    private final long installed = System.nanoTime();
    private final JsonArray frames = new JsonArray();
    private final JsonObject receipt = new JsonObject();
    private File output;
    private HologramScreen screen;
    private NBTTagCompound scene;
    private NBTTagCompound beforeVoucher;
    private NBTTagCompound wheelInput;
    private int stage;
    private long stageAt, requestedGeneration, frame, requestedFrame, taskToken, command;
    private long lastStartupDiagnostic, terminalNotificationsBefore;
    private long stageFrame, stageGeneration;
    private String capture;
    private boolean finished;
    private boolean orderRefusalCaptured;

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.materialVisualSmoke")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramMaterialVisualSmoke());
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            initializeOutput(mc);
            if (stage == 0) {
                startupDiagnostic(mc);
                boolean menu = mc.currentScreen instanceof GuiMainMenu
                    || mc.currentScreen != null && "lumien.custommainmenu.gui.GuiCustom".equals(
                        mc.currentScreen.getClass()
                            .getName());
                if (!menu) {
                    require(System.nanoTime() - installed < 1800_000_000_000L, "main menu startup timeout");
                    return;
                }
                require(mc.theWorld == null && mc.getNetHandler() == null, "fixture must start without a world/server");
                receipt.addProperty(
                    "acceptedStartupScreen",
                    mc.currentScreen.getClass()
                        .getName());
                mc.gameSettings.guiScale = Integer.getInteger("gtmb.hologram.materialVisualScale", 2);
                scene = fixture();
                show(mc, new HologramState(scene), true);
                advance();
                return;
            }
            require(System.nanoTime() - stageAt < 30_000_000_000L, "stage timeout " + stage);
            // A requested capture also has a deadline when the window stops producing frames.
            if (capture != null) return;
            if (System.nanoTime() - stageAt < 900_000_000L) return;
            // Wheel animation is queued on its first natural DrawScreenEvent.Pre and advanced on the next.
            // Dialog quote/toggle updates also run inside real draws; elapsed wall time alone proves neither.
            if (frame - stageFrame < 2 || screen.ui.canvas.renderGeneration() - stageGeneration < 2) return;
            if (stage == 1) {
                verifyQuietControls(false);
                request("01-normal-gt-machine-nbt-fluid-long-counts");
            } else if (stage == 2) {
                wheelInput = HologramMaterialConfirmationClientProbe.scroll(screen, -120);
                JsonObject wheel = new JsonObject();
                for (String key : new String[] { "mouseX", "mouseY", "before", "scrollSize", "viewportHeight",
                    "listLocalX", "listLocalY", "nativeAnimatingTo", "immediateScroll" })
                    wheel.addProperty(key, wheelInput.getInteger(key));
                for (String key : new String[] { "samePanelContext", "listInActualHitTest", "consumed",
                    "nativeAnimating" }) wheel.addProperty(key, wheelInput.getBoolean(key));
                wheel.addProperty(
                    "actualHovering",
                    wheelInput.getTagList("actualHovering", 8)
                        .toString());
                receipt.add("wheelInput", wheel);
                require(
                    wheelInput.getBoolean("samePanelContext") && wheelInput.getBoolean("listInActualHitTest")
                        && wheelInput.getBoolean("consumed"),
                    "ordinary wheel must traverse the actual material-list MUI hit route");
                require(
                    wheelInput.getBoolean("nativeAnimating")
                        && wheelInput.getInteger("nativeAnimatingTo") > wheelInput.getInteger("before"),
                    "ordinary wheel must enqueue forward movement in the native MUI animator");
                advance();
            } else if (stage == 3) {
                receipt.addProperty("wheelNaturalFrameDelta", frame - stageFrame);
                receipt
                    .addProperty("wheelNaturalGenerationDelta", screen.ui.canvas.renderGeneration() - stageGeneration);
                require(
                    HologramMaterialConfirmationClientProbe.observe(screen)
                        .getInteger("scroll") > 0,
                    "ordinary wheel must scroll the real MUI list");
                require(
                    HologramMaterialConfirmationClientProbe.observe(screen)
                        .getInteger("scroll") > wheelInput.getInteger("before"),
                    "natural MUI animation must move the scroll position beyond its pre-input value");
                request("02-virtual-list-scrolled");
            } else if (stage == 4) {
                beforeVoucher = HologramMaterialConfirmationClientProbe.observe(screen);
                HologramMaterialConfirmationClientProbe.click(screen, 245);
                NBTTagCompound sent = (NBTTagCompound) HologramUiDiagnostics
                    .readDiagnosticField(screen, "capturedRenderSmokeAction");
                require(
                    sent != null && "setDecorativeVoucher".equals(sent.getString("op")) && sent.getBoolean("enabled"),
                    "voucher click must emit the actual selected action");
                receipt.addProperty("voucherAction", sent.getString("op"));
                receipt.addProperty("voucherNonce", sent.getLong("placementReviewNonce"));
                require(
                    screen.ui.session.pendingSequence() < 0 && screen.ui.session.editable(),
                    "voucher update must not take the global command ticket");
                require(!screen.ui.review.materialControlEnabled(55), "final confirmation waits only for its quote");
                // Four immediate clicks retain only the final intent while the first request is in flight.
                for (int i = 0; i < 3; i++) HologramMaterialConfirmationClientProbe.click(screen, 245);
                require(!screen.ui.session.materialVoucherSelected(), "rapid toggles retain the last off intent");
                require(
                    screen.ui.session.sequence() == sent.getLong("uiSequence"),
                    "rapid toggles coalesce without flooding the server");
                quote(sent, true);
                NBTTagCompound deselect = (NBTTagCompound) HologramUiDiagnostics
                    .readDiagnosticField(screen, "capturedRenderSmokeAction");
                require(
                    !deselect.getBoolean("enabled") && deselect.getLong("uiSequence") > sent.getLong("uiSequence"),
                    "the selected receipt sends the retained off intent using its new nonce");
                quote(deselect, false);
                require(!screen.ui.session.materialReviewPending(), "the final off receipt releases its local ticket");
                HologramMaterialConfirmationClientProbe.click(screen, 245);
                NBTTagCompound select = (NBTTagCompound) HologramUiDiagnostics
                    .readDiagnosticField(screen, "capturedRenderSmokeAction");
                quote(select, true);
                receipt.addProperty("rapidVoucherLastIntentCoalesced", true);
                advance();
            } else if (stage == 5) {
                NBTTagCompound after = HologramMaterialConfirmationClientProbe.observe(screen);
                for (String key : new String[] { "panelBuilds", "listResizes", "listIdentity", "canvasIdentity",
                    "cellsIdentity", "firstRowIdentity", "rows", "scroll" })
                    require(
                        beforeVoucher.getInteger(key) == after.getInteger(key),
                        "voucher metadata retains the existing row geometry and samples: " + key);
                verifyQuietControls(true);
                receipt.addProperty("voucherRetainedPanelListCellsAndUnaffectedRow", true);
                request("03-voucher-selected-action-receipt");
            } else if (stage == 6) {
                if (!orderRefusalCaptured) {
                    HologramUiDiagnostics.writeDiagnosticField(screen, "capturedRenderSmokeAction", null);
                    HologramMaterialConfirmationClientProbe.click(screen, 162);
                    NBTTagCompound sent = (NBTTagCompound) HologramUiDiagnostics
                        .readDiagnosticField(screen, "capturedRenderSmokeAction");
                    require(
                        sent != null && "orderMissingMaterials".equals(sent.getString("op"))
                            && sent.getLong("planRevision") == scene.getLong("planRevision")
                            && sent.getLong("placementReviewNonce") == scene.getLong("placementReviewNonce"),
                        "the actual order button must submit the reviewed plan and nonce");
                    command = sent.getLong("uiSequence");
                    HologramClient.state = screen.ui.session.state();
                    HologramBackgroundTasks.command(HologramClient.state.session, command, "orderMissingMaterials");
                    require(HologramBackgroundTasks.busy(), "the actual order request acquires the material owner");
                    NBTTagCompound refusal = new NBTTagCompound();
                    refusal.setBoolean("materialQuoteOnly", true);
                    refusal.setString("session", scene.getString("session"));
                    refusal.setLong("planRevision", scene.getLong("planRevision"));
                    refusal.setLong("placementReviewNonce", scene.getLong("placementReviewNonce"));
                    refusal.setLong("uiSequence", command);
                    refusal.setTag("materialOrder", new NBTTagCompound());
                    refusal.setString("status", "视觉 fixture：模拟服务端拒绝本次 AE 下单，可立即重试。");
                    receiveQuote(refusal);
                    require(
                        !HologramBackgroundTasks.busy() && screen.ui.session.pendingSequence() < 0
                            && screen.ui.review.materialControlEnabled(162),
                        "the exact rejected quote releases both real owner and UI ticket for an immediate retry");
                    orderRefusalCaptured = true;
                    request("04a-order-rejected-ready-for-retry");
                    return;
                }
                HologramUiDiagnostics.writeDiagnosticField(screen, "capturedRenderSmokeAction", null);
                HologramMaterialConfirmationClientProbe.click(screen, 162);
                NBTTagCompound sent = (NBTTagCompound) HologramUiDiagnostics
                    .readDiagnosticField(screen, "capturedRenderSmokeAction");
                require(
                    sent != null && sent.getLong("uiSequence") > command,
                    "the same visible order button immediately emits a new request after refusal");
                command = sent.getLong("uiSequence");
                receipt.addProperty("orderRefusalReleasedAndImmediateRetry", true);
                receipt.addProperty("orderAction", sent.getString("op"));
                receipt.addProperty("orderActionSequence", command);
                terminalNotificationsBefore = HologramBackgroundTasks.actualTaskWitness()
                    .getLong("terminalNotifications");
                HologramClient.state = screen.ui.session.state();
                HologramBackgroundTasks.command(HologramClient.state.session, command, "orderMissingMaterials");
                taskToken = HologramBackgroundTasks.token();
                HologramUiDiagnostics.writeDiagnosticField(screen, "captureRenderSmokeAction", false);
                order(true, 1);
                require(HologramBackgroundTasks.busy(), "material command must own projector before and after its ACK");
                advance();
            } else if (stage == 7) request("04-order-modal-running");
            else if (stage == 8) {
                screen.ui.review.confirmation(false);
                screen.ui.review.tickMaterialDialog();
                advance();
            } else if (stage == 9) request("05-background-overall-progress-strip");
            else if (stage == 10) {
                boolean blueprintTab = screen.ui.library.blueprintTab(),
                    libraryDrawer = screen.ui.library.libraryDrawer();
                HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                require(
                    HologramBackgroundTasks.busy() && HologramBackgroundTasks.detached()
                        && !(mc.currentScreen instanceof HologramScreen),
                    "Escape must detach the real owner without cancelling");
                require(
                    HologramBackgroundTasks.reopen(),
                    "the normal background reopen path must rebuild the workstation");
                screen = (HologramScreen) mc.currentScreen;
                require(
                    screen.ui.session.state().session.equals(scene.getString("session"))
                        && HologramBackgroundTasks.token() == taskToken
                        && screen.ui.library.blueprintTab() == blueprintTab
                        && screen.ui.library.libraryDrawer() == libraryDrawer,
                    "reopen must retain original page/session/token");
                receipt.addProperty("escapeDetachedAndReopenedSameToken", true);
                advance();
            } else if (stage == 11) request("06-reopened-original-material-review");
            else if (stage == 12) {
                order(false, 2);
                require(
                    !HologramBackgroundTasks.busy() && mc.currentScreen == screen,
                    "completion must unlock while preserving the same visible screen");
                require(
                    HologramBackgroundTasks.actualTaskWitness()
                        .getLong("terminalNotifications") == terminalNotificationsBefore + 1,
                    "one material order must emit exactly one terminal notification");
                advance();
            } else if (stage == 13) request("07-order-complete-refresh-to-continue");
            else if (stage == 14) {
                NBTTagCompound disabled = screen.ui.session.state()
                    .fullData();
                disabled.getCompoundTag("decorativeVoucher")
                    .setBoolean("enabled", false);
                update(new HologramState(disabled));
                require(
                    !HologramMaterialConfirmationClientProbe.observe(screen)
                        .getBoolean("voucherEnabledAction"),
                    "disabled server configuration must disable the visible voucher action");
                advance();
            } else if (stage == 15) request("08-voucher-config-disabled");
            else if (stage == 16) {
                NBTTagCompound blocked = screen.ui.session.state()
                    .fullData();
                blocked.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .setString("status", "blocked");
                blocked.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .setString("operation", "PROTECT");
                update(new HologramState(blocked));
                advance();
            } else if (stage == 17) {
                require(
                    screen.ui.review.materialDialogProbeState()
                        .getInteger("accent215") == HologramUiAccent.WARNING,
                    "an actual blocked cell emphasizes only the obstruction action");
                request("09-blocked-obstruction-action");
            } else if (stage == 18) {
                NBTTagCompound creative = screen.ui.session.state()
                    .fullData();
                creative.getCompoundTag("materialQuote")
                    .setBoolean("creativePaymentsFree", true);
                creative.getCompoundTag("decorativeVoucher")
                    .setBoolean("enabled", true);
                creative.getCompoundTag("decorativeVoucher")
                    .setBoolean("selected", false);
                update(new HologramState(creative));
                advance();
            } else if (stage == 19) {
                require(
                    !screen.ui.review.materialControlEnabled(245) && !screen.ui.review.materialControlEnabled(162),
                    "creative ordinary payment exemption offers no ineffective voucher or AE request");
                request("10-creative-voucher-unneeded");
            } else if (stage == 20) {
                int beforeError = GL11.glGetError();
                receipt.addProperty("explicitFixtureGlInitialError", beforeError);
                require(beforeError == GL11.GL_NO_ERROR, "explicit GL fixture requires an error-free baseline");
                JsonObject before = glState();
                int depth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
                HologramMuiProbeInput.renderFixture(screen, 0, 0, 0);
                JsonObject after = glState();
                receipt.add("explicitFixtureGlBefore", before);
                receipt.add("explicitFixtureGlAfter", after);
                require(
                    depth == GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH),
                    "MUI fixture must balance matrix stack");
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "MUI/icons must not leave an OpenGL error");
                receipt.add("taskWitness", new com.google.gson.JsonParser().parse("{\"fixtureOnly\":true}"));
                try (FileOutputStream stream = new FileOutputStream(new File(output, "task-witness.nbt"))) {
                    CompressedStreamTools.writeCompressed(HologramBackgroundTasks.actualTaskWitness(), stream);
                }
                finish(
                    mc,
                    true,
                    "real MUI visual fixture and in-JVM owner/input paths; no AE CPU or Netty business proof");
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(mc, false, failure.toString());
        }
    }

    private void initializeOutput(Minecraft mc) throws Exception {
        if (output != null) return;
        output = new File(
            System.getProperty(
                "gtmb.hologram.materialVisualOutput",
                new File(mc.mcDataDir, "../../render-v15").getCanonicalPath()));
        Files.createDirectories(output.toPath());
        receipt.addProperty("fixtureInstalled", true);
        receipt.addProperty("materialVisualSmoke", Boolean.getBoolean("gtmb.hologram.materialVisualSmoke"));
        write(false, false, "waiting for a supported menu with no world or connection");
    }

    private void startupDiagnostic(Minecraft mc) throws Exception {
        long now = System.nanoTime();
        if (lastStartupDiagnostic != 0 && now - lastStartupDiagnostic < 10_000_000_000L) return;
        lastStartupDiagnostic = now;
        String name = mc.currentScreen == null ? "null"
            : mc.currentScreen.getClass()
                .getName();
        receipt.addProperty("startupCurrentScreen", name);
        receipt.addProperty("startupElapsedSeconds", Math.max(0, now - installed) / 1_000_000_000L);
        receipt.addProperty("startupHasWorld", mc.theWorld != null);
        receipt.addProperty("startupHasConnection", mc.getNetHandler() != null);
        System.out.println(
            "[GTMB-MATERIAL-VISUAL] STARTUP stage=" + stage
                + " currentScreen="
                + name
                + " world="
                + (mc.theWorld != null)
                + " connection="
                + (mc.getNetHandler() != null));
        write(false, false, "waiting for a supported menu with no world or connection");
    }

    private void show(Minecraft mc, HologramState state, boolean captureActions) throws Exception {
        HologramClient.state = state;
        screen = new HologramScreen(state);
        HologramUiDiagnostics.writeDiagnosticField(screen, "captureRenderSmokeAction", captureActions);
        mc.displayGuiScreen(screen);
    }

    private void update(HologramState state) {
        HologramClient.state = state;
        screen.updateState(state);
    }

    private void verifyQuietControls(boolean selected) {
        NBTTagCompound observed = HologramMaterialConfirmationClientProbe.observe(screen);
        for (int id : new int[] { 168, 196, 211, 215, 184, 247 })
            require(observed.getInteger("accent" + id) == 0, "routine entry uses the normal projector texture: " + id);
        for (int id : new int[] { 168, 196, 211, 215 }) require(
            !screen.ui.review.materialControlEnabled(id),
            "an empty context leaves its action visible but disabled: " + id);
        require(
            observed.getInteger("accent245") == (selected ? HologramUiAccent.SUGGESTION : 0),
            "only a selected voucher has a state accent");
        require(observed.getInteger("accent55") == HologramUiAccent.NEXT, "the explicit confirmation is the next step");
    }

    /** Synthetic receipt through the same metadata fence as production; no fabricated cell replacement. */
    private void quote(NBTTagCompound action, boolean selected) {
        NBTTagCompound event = new NBTTagCompound();
        event.setBoolean("materialQuoteOnly", true);
        event.setString("session", scene.getString("session"));
        event.setLong("planRevision", scene.getLong("planRevision"));
        event.setLong("uiSequence", action.getLong("uiSequence"));
        event.setLong("placementReviewNonce", screen.ui.session.state().data.getLong("placementReviewNonce") + 1);
        NBTTagCompound projected = (NBTTagCompound) scene.getCompoundTag("materialQuote")
            .copy();
        projected.setLong("placementReviewNonce", event.getLong("placementReviewNonce"));
        NBTTagList rows = projected.getTagList("rows", 10);
        if (selected) for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.getBoolean("voucherEligible")) {
                row.setLong("available", row.getLong("required"));
                row.setLong("missing", 0);
            }
        }
        event.setTag("materialQuote", projected);
        NBTTagCompound voucher = (NBTTagCompound) scene.getCompoundTag("decorativeVoucher")
            .copy();
        voucher.setBoolean("selected", selected);
        event.setTag("decorativeVoucher", voucher);
        receiveQuote(event);
        HologramState next = screen.ui.session.state();
        scene.setLong("placementReviewNonce", next.data.getLong("placementReviewNonce"));
        scene.setLong("uiSequence", next.data.getLong("uiSequence"));
    }

    private void receiveQuote(NBTTagCompound event) {
        HologramState next = HologramMaterialQuoteUpdates.apply(screen.ui.session.state(), event);
        require(next != null, "quote must pass the production session/revision/nonce fence");
        Minecraft mc = Minecraft.getMinecraft();
        next = HologramBackgroundTasks.materialQuoteReceipt(next, event, mc.theWorld, mc.getNetHandler());
        HologramClient.state = next;
        screen.ui.session.materialQuoteMetadata(next, event.getLong("uiSequence"));
    }

    private void order(boolean active, long sequence) {
        NBTTagCompound event = new NBTTagCompound();
        event.setBoolean("materialOrderOnly", true);
        event.setString("scope", "server");
        event.setString("phase", "material.order");
        event.setString("session", scene.getString("session"));
        event.setLong("planRevision", scene.getLong("planRevision"));
        event.setLong("placementReviewNonce", scene.getLong("placementReviewNonce"));
        event.setLong("uiSequence", command);
        event.setString("orderId", "v15-visual-fixture-order");
        event.setLong("orderSequence", sequence);
        NBTTagCompound order = new NBTTagCompound();
        order.setBoolean("active", active);
        order.setString("orderId", event.getString("orderId"));
        order.setLong("orderSequence", sequence);
        order.setLong("orderUiSequence", command);
        order.setLong("planRevision", scene.getLong("planRevision"));
        order.setLong("placementReviewNonce", scene.getLong("placementReviewNonce"));
        order.setLong("done", active ? 3_250_000_000L : 5_000_000_000L);
        order.setLong("total", 5_000_000_000L);
        order.setString("state", active ? "running" : "complete");
        order.setString("status", active ? "视觉 fixture：AE 总体合成 65%" : "视觉 fixture：已完成，请刷新材料后继续确认");
        event.setTag("materialOrder", order);
        Minecraft mc = Minecraft.getMinecraft();
        HologramState next = HologramBackgroundTasks
            .materialOrderProgress(HologramClient.state, event, mc.theWorld, mc.getNetHandler());
        require(next != null, "fixture order event must be accepted by the production fence");
        HologramClient.state = next;
        screen.ui.session.materialOrderMetadata(next, command);
    }

    private void advance() {
        stage++;
        stageAt = System.nanoTime();
        stageFrame = frame;
        stageGeneration = screen == null ? 0 : screen.ui.canvas.renderGeneration();
    }

    private void request(String name) {
        require(Minecraft.getMinecraft().currentScreen == screen, "capture must observe the retained screen");
        if (!"05-background-overall-progress-strip".equals(name)) {
            NBTTagCompound dialog = screen.ui.review.materialDialogProbeState();
            require(
                dialog.getBoolean("open") && dialog.getLong("drawnFrames") > 0,
                "capture requires a genuinely rendered material dialog: " + name);
        }
        capture = name;
        requestedFrame = frame;
        requestedGeneration = screen.ui.canvas.renderGeneration();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        frame++;
        if (capture == null || frame <= requestedFrame || screen.ui.canvas.renderGeneration() <= requestedGeneration)
            return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            require(mc.currentScreen == screen, "the requested screen must own the naturally rendered frame");
            // An actual vanilla 3D block-item sentinel is added after the UI, with its own restored GL scope.
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            try {
                GuiDraw.drawItem(new ItemStack(Blocks.cobblestone), 6, 6, 22, 22, 400);
            } finally {
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
            String name = capture + ".png";
            ScreenShotHelper.saveScreenshot(output, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            File file = new File(new File(output, "screenshots"), name);
            require(file.isFile() && file.length() > 0, "framebuffer screenshot must exist");
            JsonObject item = new JsonObject();
            item.addProperty("png", file.getCanonicalPath());
            item.addProperty("bytes", file.length());
            item.addProperty("sha256", sha(Files.readAllBytes(file.toPath())));
            item.addProperty("naturalRenderTickEnd", true);
            item.addProperty("frame", frame);
            item.addProperty("generation", screen.ui.canvas.renderGeneration());
            item.addProperty("stage", stage);
            item.addProperty(
                "materialRows",
                screen.ui.review.materialDialogProbeState()
                    .getInteger("rows"));
            item.addProperty(
                "scroll",
                screen.ui.review.materialDialogProbeState()
                    .getInteger("scroll"));
            item.add("gl", glState());
            frames.add(item);
            capture = null;
            if ("04a-order-rejected-ready-for-retry.png".equals(name)) {
                stageAt = System.nanoTime();
                stageFrame = frame;
                stageGeneration = screen.ui.canvas.renderGeneration();
            } else advance();
            write(false, false, "in progress");
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(mc, false, failure.toString());
        }
    }

    private void finish(Minecraft mc, boolean passed, String detail) {
        finished = true;
        try {
            if (output != null) write(true, passed, detail);
        } catch (Exception failure) {
            failure.printStackTrace();
        }
        System.out.println("[GTMB-MATERIAL-VISUAL] FINAL " + (passed ? "PASS " : "FAIL ") + detail);
        mc.shutdown();
    }

    private void write(boolean completed, boolean passed, String detail) throws Exception {
        receipt.addProperty("completed", completed);
        receipt.addProperty("passed", passed);
        receipt.addProperty("detail", detail);
        receipt.addProperty("stage", stage);
        receipt.addProperty("capturePending", capture == null ? "" : capture);
        receipt.addProperty("actualClientJvm", true);
        receipt.addProperty("actualMUI2", frames.size() > 0);
        receipt.addProperty("actualNetty", false);
        receipt.addProperty("actualCraftingCPU", false);
        receipt.addProperty("desktopInput", false);
        receipt.addProperty("minimumNaturalFramesBetweenStages", 2);
        receipt.addProperty("minimumNaturalGenerationDeltaBetweenStages", 2);
        receipt.addProperty("sourceKind", "synthetic-menu-fixture-with-real-registered-items");
        receipt.addProperty("blueprintMaterialCompletenessVerified", false);
        receipt.addProperty("soundPlaybackVerified", false);
        receipt.add("frames", frames);
        Files.write(
            new File(output, "material-visual-receipt.json").toPath(),
            new com.google.gson.GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(receipt)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject glState() {
        JsonObject result = new JsonObject();
        for (int capability : new int[] { GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_ALPHA_TEST, GL11.GL_TEXTURE_2D,
            GL11.GL_LIGHTING, GL11.GL_STENCIL_TEST })
            result.addProperty(Integer.toHexString(capability), GL11.glIsEnabled(capability));
        result.addProperty("depthWrite", GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        result.addProperty("matrixMode", GL11.glGetInteger(GL11.GL_MATRIX_MODE));
        result.addProperty("modelviewStack", GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH));
        // The pinned LWJGL2-compatible glGetFloat wrapper accepts a matrix-sized output buffer.
        java.nio.FloatBuffer color = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        JsonArray rgba = new JsonArray();
        for (int i = 0; i < 4; i++) rgba.add(new com.google.gson.JsonPrimitive(color.get(i)));
        result.add("color", rgba);
        return result;
    }

    private static String sha(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static NBTTagCompound fixture() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", "v15-material-visual-fixture");
        state.setString("generation", state.getString("session"));
        state.setString("virtualPresetName", "猫猫材料确认 · 真实物品视觉 fixture");
        state.setString("openPurpose", "placementReview");
        state.setLong("planRevision", 7);
        state.setLong("placementReviewNonce", 3);
        state.setLong("uiSequence", 10);
        for (String key : new String[] { "target", "supported", "virtual", "virtualPlacement", "virtualLocked",
            "blueprintAwaitingConfirmation", "placementDeidentificationChosen", "materialOrderingAvailable", "open" })
            state.setBoolean(key, true);
        NBTTagList cells = new NBTTagList();
        NBTTagCompound cell = new NBTTagCompound();
        cell.setString("wantId", "minecraft:stone");
        cell.setString("id", "minecraft:air");
        cell.setString("operation", "PLACE");
        cell.setString("status", "pending");
        cell.setBoolean("inScope", true);
        cells.appendTag(cell);
        state.setTag("cells", cells);
        NBTTagList rows = new NBTTagList();
        for (int id : new int[] { 51, 1000 }) {
            require(gregtech.api.GregTechAPI.METATILEENTITIES[id] != null, "registered GT machine required: " + id);
            ItemStack item = gregtech.api.GregTechAPI.METATILEENTITIES[id].getStackForm(1);
            rows.appendTag(HologramMaterialQuote.itemRow(item, 5_000_000_000L, 3_000_000_000L));
        }
        ItemStack variant = gregtech.api.GregTechAPI.METATILEENTITIES[51].getStackForm(1);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("gtmbVisualVariant", "precise-NBT-A");
        variant.setTagCompound(tag);
        rows.appendTag(HologramMaterialQuote.itemRow(variant, 5_000_000_001L, 0));
        variant = variant.copy();
        variant.getTagCompound()
            .setString("gtmbVisualVariant", "precise-NBT-B");
        rows.appendTag(HologramMaterialQuote.itemRow(variant, 5_000_000_002L, 0));
        FluidStack water = new FluidStack(FluidRegistry.WATER, 1);
        NBTTagCompound fluid = new NBTTagCompound();
        fluid.setString("kind", "fluid");
        fluid.setString("label", water.getLocalizedName());
        fluid.setString("unit", "mB");
        fluid.setTag("fluid", water.writeToNBT(new NBTTagCompound()));
        fluid.setLong("required", 5_000_000_000L);
        fluid.setLong("available", 1_000_000_000L);
        fluid.setLong("missing", 4_000_000_000L);
        rows.appendTag(fluid);
        for (int i = 0; i < 10000; i++) {
            ItemStack block = new ItemStack(Blocks.stained_hardened_clay, 1, i % 16);
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setInteger("gtmbVisualRow", i);
            block.setTagCompound(nbt);
            NBTTagCompound row = HologramMaterialQuote.itemRow(block, 5_000_000_000L + i, i * 31L);
            row.setBoolean("voucherEligible", true);
            rows.appendTag(row);
        }
        NBTTagCompound quote = new NBTTagCompound();
        quote.setBoolean("complete", true);
        quote.setString("session", state.getString("session"));
        quote.setLong("planRevision", 7);
        quote.setTag("rows", rows);
        state.setTag("materialQuote", quote);
        NBTTagCompound voucher = new NBTTagCompound();
        voucher.setBoolean("enabled", true);
        voucher.setLong("available", 2);
        state.setTag("decorativeVoucher", voucher);
        return state;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }
}
