package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.hologram.HologramProgressNativeBootstrap.require;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ScreenShotHelper;

import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramProgressNativeBootstrap;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.gtmb.testlab.TestLab;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Actual Netty actions and real native draw observations; never draws or injects a progress model. */
public final class HologramProgressNativeProbe {

    private static final int SIDE = 32, CELLS = SIDE * SIDE * SIDE;
    private final NBTTagList progressEvents = new NBTTagList(), frames = new NBTTagList(), actions = new NBTTagList();
    private final NBTTagCompound result = new NBTTagCompound();
    private final long started = System.nanoTime();
    private int stage, ticks, renders, postConfirmFrames, worldIndex, worldMatches, worldScans;
    private String session = "";
    private long revision, nonce, buildSequence, closeSequence, lastFrameSerial = -1;
    private boolean hooked, done, reviewDrawn, runningDrawn, completeDrawn, unknownAckDrawn, identitySelected,
        identitySubmitted, identityAcknowledged;
    private HologramWorldGuide.RenderWitness previousHud;

    public static void install() {
        HologramProgressNativeProbe probe = new HologramProgressNativeProbe();
        require(
            Paths.get("")
                .toAbsolutePath()
                .normalize()
                .equals(
                    HologramProgressNativeBootstrap.root()
                        .resolve("client")),
            "fresh paired client cwd");
        FMLCommonHandler.instance()
            .bus()
            .register(probe);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tick(TickEvent.ClientTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            require(
                ++ticks <= 4800 && System.nanoTime() - started < 240000000000L,
                "paired infrastructure watchdog; production budgets unchanged");
            if (stage == 0) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                require(
                    !TestLab.isEnabled() && !TestLab.status()
                        .getBoolean("configEnabled"),
                    "client lab configuration remains disabled");
                result.setTag("initialLabStatus", TestLab.status());
                mc.gameSettings.pauseOnLostFocus = false;
                HologramRuntimeMetrics.setEnabled(true);
                hookActualProgress();
                String address = System.getProperty("gtmb.progress.pairedAddress", "");
                require(
                    address.matches("127\\.0\\.0\\.1:[0-9]{4,5}") && !address.endsWith(":12134"),
                    "new isolated loopback address");
                FMLClientHandler.instance()
                    .setupServerList();
                FMLClientHandler.instance()
                    .connectToServer(mc.currentScreen, new ServerData("GTMB isolated paired progress", address));
                stage = 1;
                return;
            }
            if (mc.thePlayer == null || mc.theWorld == null) return;
            require(
                mc.thePlayer.dimension == 0 && mc.getNetHandler() != null
                    && mc.getNetHandler()
                        .getNetworkManager()
                        .isChannelOpen(),
                "actual client remains connected in overworld");
            require(
                !TestLab.isEnabled() && !TestLab.status()
                    .getBoolean("configEnabled"),
                "disabled client lab never enabled during server-enabled login");
            if (stage == 1) {
                if (!(mc.currentScreen instanceof HologramScreen) || HologramClient.state == null) return;
                HologramState state = HologramClient.state;
                if (state.cells.size() != CELLS || !state.data.getBoolean("blueprintAwaitingConfirmation")) return;
                require(
                    state.data.getBoolean("virtualPlacement") && state.data.getBoolean("virtualLocked")
                        && state.data.getLong("placementReviewNonce") > 0,
                    "actual server full state carries reviewed placement identity");
                session = state.session;
                revision = state.data.getLong("planRevision");
                nonce = state.data.getLong("placementReviewNonce");
                result.setString("session", session);
                result.setLong("planRevision", revision);
                result.setLong("placementReviewNonce", nonce);
                result.setString(
                    "playerUuid",
                    mc.thePlayer.getUniqueID()
                        .toString());
                result.setString("playerName", mc.thePlayer.getCommandSenderName());
                result.setInteger("loginDimension", mc.thePlayer.dimension);
                HologramProgressNativeBootstrap.write("client-initial-full-state", state.data);
                stage = 2;
            }
            if (stage >= 3) {
                HologramState state = HologramClient.state;
                require(
                    state != null && state.session.equals(session)
                        && state.data.getLong("planRevision") == revision
                        && state.data.getLong("placementReviewNonce") == nonce
                        && state.cells.size() == CELLS,
                    "only a complete same-source state remains editable during detached progress publications");
                require(
                    HologramJobVisuals.bufferedBatches() <= 8 && HologramJobVisuals.bufferedIndices() <= 65536,
                    "actual early-delta visual buffers remain bounded");
                if (state.data.getInteger("job") == 3 && stage < 5) {
                    HologramProgressNativeBootstrap.write("client-terminal-full-state", state.data);
                    stage = 5;
                }
                if (stage == 5 && runningDrawn && completeDrawn) verifyWorld(mc);
            }
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    /**
     * Forward every actual event to the unchanged production consumer, then observe its result on the client thread.
     */
    @SuppressWarnings("unchecked")
    private void hookActualProgress() throws Exception {
        require(!hooked, "install one observational progress tap");
        Field field = HologramNetwork.class.getDeclaredField("progressReceiver");
        field.setAccessible(true);
        Consumer<NBTTagCompound> production = (Consumer<NBTTagCompound>) field.get(null);
        require(production != null, "production progress consumer already installed");
        HologramNetwork.setProgressReceiver(
            tag -> Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    try {
                        HologramState before = HologramClient.state;
                        production.accept(tag);
                        if (tag.getBoolean("jobDelta")) {
                            require(
                                before == HologramClient.state,
                                "progressOnly job delta never installs editable state");
                            require(
                                !tag.hasKey("cells") && !tag.hasKey("nativePayload") && !tag.hasKey("quote"),
                                "delta contains no editable structure/resource quote");
                            NBTTagCompound observation = (NBTTagCompound) tag.copy();
                            observation.setBoolean("actualNetworkDelivery", true);
                            observation.setBoolean("editableStateIdentityUnchanged", true);
                            observation.setBoolean("productionFenceMatches", HologramClientProgress.matches(tag));
                            observation.setInteger("bufferedBatches", HologramJobVisuals.bufferedBatches());
                            observation.setInteger("bufferedIndices", HologramJobVisuals.bufferedIndices());
                            observation.setInteger("receivedVisualCells", HologramJobVisuals.receivedCells());
                            observation.setBoolean("waitingForFull", HologramJobVisuals.waitingForFull());
                            if (before != null) {
                                observation.setString("editableSession", before.session);
                                observation.setLong("editableRevision", before.data.getLong("planRevision"));
                                observation.setLong("editableNonce", before.data.getLong("placementReviewNonce"));
                                observation.setInteger("editableCells", before.cells.size());
                            }
                            require(progressEvents.tagCount() < 1024, "bounded actual delta receipt list");
                            progressEvents.appendTag(observation);
                        }
                    } catch (Throwable failure) {
                        fail(Minecraft.getMinecraft(), failure);
                    }
                }));
        hooked = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void render(TickEvent.RenderTickEvent event) {
        if (done) return;
        if (event.phase == TickEvent.Phase.START) {
            previousHud = HologramWorldGuide.lastRenderWitness();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || stage < 2) return;
        try {
            renders++;
            HologramScreen screen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen
                : null;
            if (stage == 2 && screen != null && screen.ui.review.confirmation()) {
                if (!identityAcknowledged) {
                    if (screen.ui.session.pendingSequence() >= 0) return;
                    if (identitySubmitted) {
                        NBTTagCompound acknowledged = screen.ui.session.state().data;
                        require(
                            acknowledged.getBoolean("placementDeidentificationChosen")
                                && acknowledged.getLong("uiSequence") >= actions.getCompoundTagAt(0)
                                    .getLong("uiSequence")
                                && !screen.ui.identity.deidentificationDrawer(),
                            "actual server ACK confirms native identity choice");
                        HologramProgressNativeBootstrap.write("client-identity-ack", acknowledged);
                        revision = acknowledged.getLong("planRevision");
                        nonce = acknowledged.getLong("placementReviewNonce");
                        result.setLong("planRevision", revision);
                        result.setLong("placementReviewNonce", nonce);
                        identityAcknowledged = true;
                        return;
                    }
                    if (!screen.ui.identity.deidentificationDrawer()) {
                        screen.ui.router.activate(211);
                        return;
                    }
                    if (!identitySelected) {
                        int minimum = HologramDeidentificationMemory.minimum(screen.ui.session.state().data);
                        boolean drawnChoice = false;
                        for (HologramUiCanvas.Control control : screen.ui.canvas.controls())
                            drawnChoice |= control.id == 22000 + minimum && control.enabled;
                        if (!drawnChoice) return;
                        screenshot(mc, "identity-modal", null, null, screen);
                        screen.ui.router.activate(22000 + minimum);
                        identitySelected = true;
                        return;
                    }
                    boolean drawnApply = false;
                    for (HologramUiCanvas.Control control : screen.ui.canvas.controls())
                        drawnApply |= control.id == 213 && control.enabled;
                    if (!drawnApply) return;
                    NBTTagCompound identity = action("configurePlacementDeidentification", screen);
                    screen.ui.router.activate(213);
                    require(
                        screen.ui.session.pendingSequence() == identity.getLong("uiSequence"),
                        "actual native identity apply dispatched a pending Netty request");
                    actions.appendTag(identity);
                    identitySubmitted = true;
                    return;
                }
                HologramUiCanvas.Control confirm = null;
                for (HologramUiCanvas.Control control : screen.ui.canvas.controls())
                    if (control.id == 55 && control.enabled) confirm = control;
                if (confirm == null) return;
                screenshot(mc, "review", null, null, screen);
                reviewDrawn = true;
                NBTTagCompound action = action("confirmBlueprintPlacement", screen);
                screen.ui.router.activate(55); // Real production command handler, no OS input or captured/fake sender.
                buildSequence = screen.ui.session.sequence();
                require(
                    buildSequence == action.getLong("uiSequence")
                        && screen.ui.session.pendingSequence() == buildSequence,
                    "native confirmation actually issued a pending production Netty command");
                action.setBoolean("nativeControl55", true);
                actions.appendTag(action);
                HologramProgressNativeBootstrap.write("client-issued-confirm", action);
                stage = 3;
                return;
            }
            List<HologramProgressView.RenderWitness> rendered = HologramProgressView.rendered();
            for (HologramProgressView.RenderWitness witness : rendered) {
                require(
                    witness.frameSerial >= lastFrameSerial && witness.width > 0 && witness.height > 0,
                    "actual current native draw witness has bounded positive geometry");
                lastFrameSerial = witness.frameSerial;
                HologramProgress p = witness.progress;
                validate(p);
                if (stage == 3 && "ack".equals(p.phase) && p.percent() == -1 && !unknownAckDrawn) {
                    screenshot(mc, "unknown-ack", p, witness, screen);
                    unknownAckDrawn = true;
                }
                if ("job".equals(p.phase) && !p.complete && !runningDrawn) {
                    screenshot(mc, "running-workstation", p, witness, screen);
                    runningDrawn = true;
                }
                if ("job".equals(p.phase) && p.complete && p.percent() == 100 && !completeDrawn) {
                    screenshot(mc, "complete-workstation", p, witness, screen);
                    completeDrawn = true;
                }
            }
            HologramProgress p = HologramClientProgress.view("server", session);
            HologramWorldGuide.RenderWitness hud = HologramWorldGuide.lastRenderWitness();
            if (mc.currentScreen == null && hud != null && hud != previousHud && p != null && "job".equals(p.phase)) {
                validate(p);
                boolean actualPercent = false;
                for (String row : hud.rows)
                    actualPercent |= row.contains(p.percent() + "%") && row.contains(HologramProgressView.phase("job"));
                if (actualPercent && !p.complete && !runningDrawn) {
                    screenshot(mc, "running-hud", p, null, null);
                    runningDrawn = true;
                }
                if (actualPercent && p.complete && p.percent() == 100 && !completeDrawn) {
                    screenshot(mc, "complete-hud", p, null, null);
                    completeDrawn = true;
                }
            }
            if (stage == 3 && ++postConfirmFrames >= 2 && screen != null) {
                NBTTagCompound close = action("close", screen);
                screen.ui.router.closeRoot();
                closeSequence = screen.ui.session.sequence();
                require(
                    mc.currentScreen == null && closeSequence == close.getLong("uiSequence")
                        && closeSequence > buildSequence,
                    "real production close command marks subsequent no-UI observation window");
                actions.appendTag(close);
                stage = 4;
            }
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    private static void validate(HologramProgress p) {
        require(
            p != null && p.done >= 0 && p.total >= 0 && p.observedAt >= p.startedAt,
            "actual immutable model has valid measured units/time");
        require(
            p.complete ? p.percent() == 100 : p.percent() >= -1 && p.percent() <= 99,
            "actual unfinished model never reports100");
        require(p.total > 0 || p.complete || p.percent() == -1, "unknown native work total has no invented percentage");
    }

    private NBTTagCompound action(String op, HologramScreen screen) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("op", op);
        n.setString("session", screen.ui.session.state().session);
        n.setLong("planRevision", screen.ui.session.revision());
        n.setLong("placementReviewNonce", screen.ui.session.state().data.getLong("placementReviewNonce"));
        n.setLong("uiSequence", screen.ui.session.sequence() + 1);
        n.setBoolean("actualProductionNettySender", true);
        n.setBoolean("notPhysicalOSInput", true);
        return n;
    }

    private void screenshot(Minecraft mc, String name, HologramProgress p, HologramProgressView.RenderWitness widget,
        HologramScreen screen) throws Exception {
        String filename = "gtmb-progress-" + name + ".png";
        File file = new File(new File(mc.mcDataDir, "screenshots"), filename);
        require(!file.exists(), "preserve prior native screenshot bytes");
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, filename, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        BufferedImage image = ImageIO.read(file);
        require(
            image != null && image.getWidth() == mc.displayWidth && image.getHeight() == mc.displayHeight,
            "real RenderTick.END framebuffer PNG dimensions");
        NBTTagCompound frame = new NBTTagCompound();
        frame.setString("name", name);
        frame.setString("filename", filename);
        frame.setString("renderPhase", "RenderTick.END");
        frame.setLong("renderObservation", renders);
        frame.setInteger("width", image.getWidth());
        frame.setInteger("height", image.getHeight());
        frame.setLong("bytes", file.length());
        frame.setString(
            "sha256",
            hex(
                MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file.toPath()))));
        frame.setString(
            "nativeScreenClass",
            mc.currentScreen == null ? "HUD"
                : mc.currentScreen.getClass()
                    .getName());
        int left = 0, top = 0, right = image.getWidth(), bottom = image.getHeight();
        if (p != null) frame.setTag("progress", progress(p));
        if (widget != null) {
            require(screen != null, "actual widget belongs to native workstation screen");
            frame.setString("drawKind", "production ProgressView text/footer widget; no invented bar");
            frame.setLong("frameSerial", widget.frameSerial);
            frame.setLong("drawSerial", widget.drawSerial);
            frame.setString("label", widget.label);
            frame.setString("metric", widget.metric);
            frame.setIntArray("logicalBounds", new int[] { widget.x, widget.y, widget.width, widget.height });
            float scale = screen.ui.canvas.scale();
            frame.setFloat("canvasScale", scale);
            frame.setIntArray("canvasOrigin", new int[] { screen.ui.canvas.originX(), screen.ui.canvas.originY() });
            double sx = (double) mc.displayWidth / screen.width, sy = (double) mc.displayHeight / screen.height;
            left = (int) Math.floor((screen.ui.canvas.originX() + widget.x * scale) * sx);
            top = (int) Math.floor((screen.ui.canvas.originY() + widget.y * scale) * sy);
            right = (int) Math.ceil((screen.ui.canvas.originX() + (widget.x + widget.width) * scale) * sx);
            bottom = (int) Math.ceil((screen.ui.canvas.originY() + (widget.y + widget.height) * scale) * sy);
        } else if (p != null) {
            HologramWorldGuide.RenderWitness hud = HologramWorldGuide.lastRenderWitness();
            require(
                hud != null && hud != previousHud,
                "actual fresh-frame native HUD rows, not a ProgressView bar witness");
            frame.setString("drawKind", "production WorldGuide HUD text rows; no bar or ProgressView witness");
            NBTTagList rows = new NBTTagList();
            for (String row : hud.rows) rows.appendTag(new NBTTagString(row));
            frame.setTag("hudRows", rows);
            net.minecraft.client.gui.ScaledResolution resolution = new net.minecraft.client.gui.ScaledResolution(
                mc,
                mc.displayWidth,
                mc.displayHeight);
            double sx = (double) mc.displayWidth / resolution.getScaledWidth(),
                sy = (double) mc.displayHeight / resolution.getScaledHeight();
            left = (int) Math.floor((hud.centerX - hud.width / 2.0 - 6) * sx);
            right = (int) Math.ceil((hud.centerX + hud.width / 2.0 + 6) * sx);
            top = (int) Math.floor((hud.top - 4) * sy);
            bottom = (int) Math.ceil((hud.top + hud.height + 2) * sy);
        }
        require(
            left >= 0 && top >= 0
                && right <= image.getWidth()
                && bottom <= image.getHeight()
                && right > left
                && bottom > top,
            "actual rendered progress bounds stay inside framebuffer");
        frame.setIntArray("pixelBounds", new int[] { left, top, right, bottom });
        int bright = 0, cyan = 0, warm = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            int rgb = image.getRGB(x, y), r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            if (r > 130 && g > 130 && b > 100) bright++;
            if (g > 150 && b > 150 && r < 210) cyan++;
            if (r > 180 && g > 140 && b < 200) warm++;
        }
        require(bright > 0, "real native screenshot region contains drawn text pixels");
        frame.setInteger("brightPixels", bright);
        frame.setInteger("cyanPixels", cyan);
        frame.setInteger("warmPixels", warm);
        frames.appendTag(frame);
        System.out.println("[GTMB-PROGRESS-CLIENT] FRAME " + name + " phase=RenderTick.END nativeDraw=true");
    }

    private static NBTTagCompound progress(HologramProgress p) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("operation", p.operation);
        n.setString("phase", p.phase);
        n.setString("unit", p.unit);
        n.setLong("done", p.done);
        n.setLong("total", p.total);
        n.setBoolean("complete", p.complete);
        n.setInteger("percent", p.percent());
        n.setLong("startedAt", p.startedAt);
        n.setLong("observedAt", p.observedAt);
        return n;
    }

    private void verifyWorld(Minecraft mc) throws Exception {
        if (!Files.exists(
            HologramProgressNativeBootstrap.root()
                .resolve("receipts/server-world-complete.nbt")))
            return;
        int stop = Math.min(CELLS, worldIndex + 512);
        while (worldIndex < stop) {
            int index = worldIndex++, x = 8192 + index % SIDE, z = 8192 + index / SIDE % SIDE,
                y = 96 + index / (SIDE * SIDE);
            if (mc.theWorld.blockExists(x, y, z) && mc.theWorld.getBlock(x, y, z) == Blocks.stone
                && mc.theWorld.getBlockMetadata(x, y, z) == 0
                && mc.theWorld.getTileEntity(x, y, z) == null) worldMatches++;
        }
        if (worldIndex < CELLS) return;
        worldScans++;
        if (worldMatches < CELLS) {
            require(worldScans < 8, "actual client block updates did not converge within bounded independent scans");
            worldIndex = worldMatches = 0;
            return;
        }
        require(
            reviewDrawn && runningDrawn
                && completeDrawn
                && identityAcknowledged
                && actions.tagCount() == 3
                && progressEvents.tagCount() > 0,
            "real confirmation/close/delta/native running and complete100 observations all exist");
        result.setBoolean("passed", true);
        result.setString("releaseVersion", Tags.RELEASE_VERSION);
        result.setBoolean("actualNettyClient", true);
        result.setBoolean("notPhysicalOSInput", true);
        result.setBoolean("fixtureCreativePressure", true);
        result.setBoolean("privateSaveExercised", false);
        result.setBoolean("unknownAckCaptured", unknownAckDrawn);
        result.setInteger("independentClientStoneCells", worldMatches);
        result.setInteger("independentClientWorldScans", worldScans);
        result.setLong("buildUiSequence", buildSequence);
        result.setLong("closeUiSequence", closeSequence);
        result.setInteger("actualPlayerDimension", mc.thePlayer.dimension);
        result.setTag("actions", actions);
        result.setTag("frames", frames);
        result.setTag("progressEvents", progressEvents);
        result.setTag("finalClientMetrics", HologramRuntimeMetrics.snapshot());
        result.setTag("finalLabStatus", TestLab.status());
        HologramProgressNativeBootstrap.write("client-complete", result);
        done = true;
        System.out.println(
            "[GTMB-PROGRESS-CLIENT] FINAL PASS actualNetty=true nativeProgress=true cells=" + worldMatches
                + " notPhysicalOSInput=true privateSave=false");
        mc.shutdown();
    }

    private void fail(Minecraft mc, Throwable failure) {
        if (done) return;
        done = true;
        failure.printStackTrace();
        System.err.println("[GTMB-PROGRESS-CLIENT] FINAL FAIL " + failure);
        try {
            result.setBoolean("passed", false);
            result.setString("failure", failure.toString());
            result.setTag("actions", actions);
            result.setTag("frames", frames);
            result.setTag("progressEvents", progressEvents);
            HologramProgressNativeBootstrap.write("client-failed", result);
        } catch (Throwable preserving) {
            preserving.printStackTrace();
        }
        mc.shutdown();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format("%02x", b & 255));
        return value.toString();
    }
}
