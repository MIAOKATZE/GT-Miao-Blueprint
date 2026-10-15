package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramV011Evidence;
import com.miaokatze.gtmb.hologram.HologramV011InteractionFixture;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/** Dedicated actual Netty input scenario; no synthetic snapshots or controller-memory writes. */
public final class HologramV011AreaClientSmoke {

    private static HologramV011AreaClientSmoke activeDriver;
    private final long installed = System.nanoTime();
    private final Set<Integer> heldModifiers = new LinkedHashSet<>();
    private int stage, sequence, afterObservation;
    private long stageStarted, slotChangedTick, frame;
    private long worldRenderSerial, targetChangedAfterFrame = -1, targetChangedAfterWorldRender = -1;
    private long witnessBeforeEndFrame;
    private String session;
    private NBTTagCompound observation;
    private boolean captured, finished;
    private HologramAreaOverlay.RenderWitness areaWitness;
    private HologramRenderer.WorldRenderWitness worldWitness;
    private NBTTagCompound aimAtWorldRender = new NBTTagCompound();
    private final HologramActualUsePress press = new HologramActualUsePress();
    private volatile NBTTagCompound lastAction;
    private volatile long actionCount;
    private NBTTagCompound lastInput = new NBTTagCompound();
    private NBTTagCompound lastWheel = new NBTTagCompound();
    private final NBTTagList uiInputs = new NBTTagList();
    private int[] desiredTarget, airCorner, forcedCorner;
    private String cursorKind = "area", savedLocalId = "";
    private boolean saveReviewConfirmed;

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.followupAreaClient")) return;
        HologramV011AreaClientSmoke driver = new HologramV011AreaClientSmoke();
        activeDriver = driver;
        FMLCommonHandler.instance()
            .bus()
            .register(driver);
        MinecraftForge.EVENT_BUS.register(driver);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void holdClientModifiers(TickEvent.ClientTickEvent event) {
        replayHeldModifiers();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void holdRenderModifiers(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) replayHeldModifiers();
    }

    private void replayHeldModifiers() {
        if (finished || heldModifiers.isEmpty()) return;
        try {
            // LWJGL polls the native keyboard between frames and replaces a one-time buffer write.
            // Replay only keys this isolated driver explicitly holds, until its matching key(false).
            ByteBuffer buffer = keyboardState();
            for (int key : heldModifiers) buffer.put(key, (byte) 1);
        } catch (Exception failure) {
            fail(Minecraft.getMinecraft(), failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void worldRendered(net.minecraftforge.client.event.RenderWorldLastEvent event) {
        worldRenderSerial++;
        witnessBeforeEndFrame = frame + 1;
        areaWitness = HologramAreaOverlay.lastRenderWitness();
        worldWitness = HologramRenderer.lastWorldRenderWitness();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) try {
            aimAtWorldRender = aimWitness(mc);
        } catch (Exception failure) {
            fail(mc, failure);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.theWorld != null && press.tick(event, mc)) return;
            if (event.phase != TickEvent.Phase.END) return;
            require(System.nanoTime() - installed < 600000000000L, "socket scenario exceeds ten minutes");
            if (stageStarted == 0) stageStarted = System.nanoTime();
            require(
                System.nanoTime() - stageStarted
                    < (stage <= 1 ? 180000000000L : stage == 45 ? 60000000000L : 20000000000L),
                "stage/ACK timeout " + stage);
            if (Files.isRegularFile(
                HologramV011Evidence.directory()
                    .resolve("area-server-fail.txt")))
                throw new AssertionError("server reported a fixture failure");
            if (observation != null) {
                NBTTagCompound proof = HologramV011Evidence.read("area-server-step-" + sequence + ".nbt");
                if (!captured || proof == null) return;
                require(
                    proof.getBoolean("passed") && proof.getString("session")
                        .equals(observation.getString("session"))
                        && proof.getLong("planRevision") == observation.getLong("planRevision")
                        && proof.getBoolean("selecting") == observation.getBoolean("selecting")
                        && Arrays.equals(proof.getIntArray("points"), observation.getIntArray("points")),
                    "independent actual server state corroborates Netty mirror");
                observation = null;
                next(afterObservation);
                return;
            }
            if (stage == 0) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                String address = System.getProperty("gtmb.hologram.followupAddress", "");
                require(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "explicit loopback address");
                mc.gameSettings.pauseOnLostFocus = false;
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .setupServerList();
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .connectToServer(mc.currentScreen, new ServerData("GTMB isolated follow-up", address));
                next(1);
                return;
            }
            if (stage == 1) {
                if (mc.theWorld == null || mc.thePlayer == null
                    || HologramV011Evidence.read("area-server-ready.nbt") == null
                    || mc.thePlayer.getHeldItem() == null
                    || mc.renderViewEntity != mc.thePlayer
                    || mc.theWorld.getBlock(50, 80, 44) != Blocks.stone
                    || mc.theWorld.getBlock(51, 80, 44) != Blocks.stone
                    || mc.theWorld.getBlock(50, 79, 46) != Blocks.stone) return;
                mc.displayGuiScreen(null);
                installPacketObserver();
                begin(mc);
                next(2);
                return;
            }
            if (stage == 60 && HologramV011Evidence.read("area-server-final.nbt") != null) {
                HologramV011Evidence.marker(
                    "area-client-pass.txt",
                    "PASS actual Socket input and END render evidence; 23 corroborated stages");
                finished = true;
                mc.shutdown();
                return;
            }
            require(
                mc.theWorld != null && mc.thePlayer != null && HologramClient.state != null,
                "connected actual world and snapshot");
            NBTTagCompound state = HologramClient.state.data;
            if (session != null && stage < 43)
                require(session.equals(HologramClient.state.session), "slot pause retains original session");
            switch (stage) {
                case 2:
                    if (!state.getBoolean("areaSelecting")) return;
                    session = HologramClient.state.session;
                    observe(mc, "begin-ack", 3, false);
                    break;
                case 3:
                    key(Keyboard.KEY_LCONTROL, true);
                    corner(mc, 1, new int[] { 51, 80, 44 }, true);
                    key(Keyboard.KEY_LCONTROL, false);
                    next(4);
                    break;
                case 4:
                    if (!pendingRight(state, new int[] { 51, 80, 44 })) return;
                    observe(mc, "right-corner-first-ack", 5, true);
                    break;
                case 5:
                    slot(mc, 1);
                    next(6);
                    break;
                case 6:
                    if (mc.theWorld.getTotalWorldTime() - slotChangedTick < 8) return;
                    require(
                        state.getBoolean("areaSelecting") && pendingRight(state, new int[] { 51, 80, 44 }),
                        "selection remains paused with other held item after eight actual world ticks");
                    require(!HologramClient.areaProjectorWorld(), "other item has no projector selection context");
                    corner(mc, 0, new int[] { 49, 80, 44 }, false);
                    observe(mc, "other-slot-paused-no-input-consumption", 7, false);
                    break;
                case 7:
                    slot(mc, 0);
                    next(8);
                    break;
                case 8:
                    if (!HologramClient.areaProjectorWorld()) return;
                    corner(mc, 0, new int[] { 50, 80, 44 }, true);
                    next(9);
                    break;
                case 9:
                    if (!Arrays.equals(state.getIntArray("areaPoints"), new int[] { 50, 80, 44, 51, 80, 44 })) return;
                    require(
                        state.getTagList("areaRegions", 10)
                            .tagCount() == 1 && "add".equals(
                                state.getTagList("areaRegions", 10)
                                    .getCompoundTagAt(0)
                                    .getString("mode")),
                        "right-first Ctrl mode remains add when plain left completes reversed pair");
                    observe(mc, "resume-left-completes-original-add-mode", 10, true);
                    break;
                case 10:
                    // Use vanilla mouse-look rotation so the unchanged feet target is visible in the real frame.
                    require(
                        Arrays.equals(feet(mc), new int[] { 50, 80, 46 }),
                        "floating corner aiming retains original feet");
                    mc.thePlayer.setAngles(0.0F, (mc.thePlayer.rotationPitch - 75.0F) / 0.15F);
                    key(Keyboard.KEY_LMENU, true);
                    wheel(-12000);
                    desiredTarget = feet(mc);
                    targetChangedAfterFrame = frame;
                    targetChangedAfterWorldRender = worldRenderSerial;
                    next(11);
                    break;
                case 11:
                    // Several client ticks may run before another world frame. Local Alt/wheel input
                    // retains the same snapshot object, so state identity alone cannot prove a fresh cursor.
                    if (frame <= targetChangedAfterFrame || worldRenderSerial <= targetChangedAfterWorldRender) return;
                    if (areaWitness == null || areaWitness.state != HologramClient.state) return;
                    require(
                        Arrays.equals(feet(mc), areaWitness.cursor()),
                        "Alt distance zero is the actual drawn red current target");
                    observe(mc, "alt-zero-current-red-target-feet", 12, true);
                    break;
                case 12:
                    corner(mc, 1, feet(mc), true);
                    next(13);
                    break;
                case 13:
                    if (!pendingRight(state, feet(mc))) return;
                    observe(mc, "alt-current-red-target-equals-server-corner", 14, true);
                    break;
                case 14:
                    key(Keyboard.KEY_LMENU, false);
                    escape(mc);
                    next(15);
                    break;
                case 15:
                    if (state.getBoolean("areaSelecting") || state.getBoolean("areaImporting")) return;
                    require(mc.currentScreen == null, "held-projector Esc suppresses pause menu");
                    observe(mc, "held-projector-escape-ack", 16, false);
                    break;
                case 16:
                    begin(mc);
                    next(17);
                    break;
                case 17:
                    if (!state.getBoolean("areaSelecting")) return;
                    slot(mc, 1);
                    next(18);
                    break;
                case 18:
                    if (mc.theWorld.getTotalWorldTime() - slotChangedTick < 8) return;
                    require(state.getBoolean("areaSelecting"), "second selection pauses before Esc");
                    escape(mc);
                    next(19);
                    break;
                case 19:
                    if (state.getBoolean("areaSelecting") || state.getBoolean("areaImporting")) return;
                    require(mc.currentScreen == null, "suspended selection Esc suppresses pause menu");
                    observe(mc, "other-slot-escape-ack", 20, false);
                    break;
                case 20:
                    slot(mc, 0);
                    begin(mc);
                    next(21);
                    break;
                case 21:
                    if (!state.getBoolean("areaSelecting") || !HologramClient.areaProjectorWorld()) return;
                    distance(6);
                    aimBlock(mc, 50, 80, 44);
                    desiredTarget = new int[] { 50, 80, 44 };
                    markActual(mc, 0);
                    next(22);
                    break;
                case 22:
                    if (!pending(state, 1, desiredTarget)) return;
                    observe(mc, "no-alt-hit-red-cursor-server-corner", 23, true);
                    break;
                case 23:
                    distance(0);
                    aimBlock(mc, 51, 80, 44);
                    desiredTarget = new int[] { 51, 80, 44 };
                    markActual(mc, 1);
                    next(24);
                    break;
                case 24:
                    if (!Arrays.equals(state.getIntArray("areaPoints"), new int[] { 50, 80, 44, 51, 80, 44 })) return;
                    require(
                        !lastInput.getCompoundTag("packet")
                            .getBoolean("atPlayer"),
                        "actual zero-distance block packet has atPlayer=false");
                    observe(mc, "zero-distance-no-alt-hit-retains-block", 25, true);
                    break;
                case 25:
                    distance(6);
                    aim(mc, 0, -90);
                    require(!blockHit(mc), "native sky ray has no block hit");
                    airCorner = floatingExpected(mc, 6);
                    desiredTarget = airCorner;
                    markActual(mc, 0);
                    next(26);
                    break;
                case 26:
                    if (!pending(state, 1, airCorner)) return;
                    observe(mc, "no-hit-default-air", 27, true);
                    break;
                case 27:
                    aimBlock(mc, 50, 80, 44);
                    key(Keyboard.KEY_LMENU, true);
                    forcedCorner = floatingExpected(mc, 6);
                    desiredTarget = forcedCorner;
                    require(
                        !Arrays.equals(forcedCorner, new int[] { 50, 80, 44 }),
                        "forced distance target differs from actual hit block");
                    markActual(mc, 1);
                    next(28);
                    break;
                case 28:
                    if (!Arrays.equals(state.getIntArray("areaPoints"), pair(airCorner, forcedCorner))) return;
                    observe(mc, "alt-hit-forced-air", 29, true);
                    break;
                case 29:
                    distance(0);
                    key(Keyboard.KEY_LMENU, true);
                    aim(mc, 180, 75);
                    require(blockHit(mc), "native downward ray hits the real footing stone");
                    desiredTarget = feet(mc);
                    markActual(mc, 0);
                    next(30);
                    break;
                case 30:
                    if (!pending(state, 1, feet(mc))) return;
                    observe(mc, "alt-zero-with-hit-feet", 31, true);
                    break;
                case 31:
                    key(Keyboard.KEY_LMENU, false);
                    require(blockHit(mc), "releasing Alt retains the real footing hit");
                    desiredTarget = new int[] { 50, 79, 46 };
                    markActual(mc, 1);
                    next(32);
                    break;
                case 32:
                    if (!Arrays.equals(state.getIntArray("areaPoints"), pair(feet(mc), desiredTarget))) return;
                    observe(mc, "release-alt-zero-restores-hit-block", 33, true);
                    break;
                case 33:
                    distance(6);
                    aimBlock(mc, 51, 80, 44);
                    key(Keyboard.KEY_LCONTROL, true);
                    key(Keyboard.KEY_LSHIFT, true);
                    desiredTarget = new int[] { 51, 80, 44 };
                    markActual(mc, 1);
                    key(Keyboard.KEY_LCONTROL, false);
                    key(Keyboard.KEY_LSHIFT, false);
                    next(34);
                    break;
                case 34:
                    if (!pendingRight(state, desiredTarget)) return;
                    require(
                        "subtract".equals(state.getString("areaPointMode")),
                        "Ctrl+Shift right-first locks subtract instead of finish");
                    observe(mc, "ctrl-shift-right-first-subtract-ack", 35, true);
                    break;
                case 35:
                    aimBlock(mc, 50, 80, 44);
                    desiredTarget = new int[] { 50, 80, 44 };
                    markActual(mc, 0);
                    next(36);
                    break;
                case 36:
                    if (!Arrays.equals(state.getIntArray("areaPoints"), new int[] { 50, 80, 44, 51, 80, 44 })) return;
                    require(
                        state.getTagList("areaRegions", 10)
                            .tagCount() == 2 && "subtract".equals(
                                state.getTagList("areaRegions", 10)
                                    .getCompoundTagAt(1)
                                    .getString("mode")),
                        "plain left completes the original subtract combination");
                    observe(mc, "plain-left-completes-subtract-mode", 37, true);
                    break;
                case 37:
                    ordinaryWheel(mc, -120);
                    next(38);
                    break;
                case 38:
                    if (mc.theWorld.getTotalWorldTime() - slotChangedTick < 8) return;
                    require(
                        mc.thePlayer.inventory.currentItem == 1 && !HologramClient.areaProjectorWorld(),
                        "ordinary wheel reaches the native stick slot and suspends selection");
                    observe(mc, "ordinary-wheel-pauses-selection", 39, false);
                    break;
                case 39:
                    ordinaryWheel(mc, 120);
                    next(40);
                    break;
                case 40:
                    if (!HologramClient.areaProjectorWorld()) return;
                    aimBlock(mc, 50, 80, 44);
                    desiredTarget = new int[] { 50, 80, 44 };
                    markActual(mc, 0);
                    next(41);
                    break;
                case 41:
                    if (!pending(state, 1, desiredTarget)) return;
                    aimBlock(mc, 51, 80, 44);
                    desiredTarget = new int[] { 51, 80, 44 };
                    markActual(mc, 1);
                    next(42);
                    break;
                case 42:
                    if (!Arrays.equals(state.getIntArray("areaPoints"), new int[] { 50, 80, 44, 51, 80, 44 })) return;
                    require(
                        state.getTagList("areaRegions", 10)
                            .tagCount() == 1,
                        "replacement corners reset the import to exactly two real source stones");
                    key(Keyboard.KEY_LSHIFT, true);
                    markActual(mc, 1);
                    key(Keyboard.KEY_LSHIFT, false);
                    next(43);
                    break;
                case 43:
                    if (!(mc.currentScreen instanceof HologramScreen) || !state.getBoolean("areaImported")
                        || state.getBoolean("areaImporting")
                        || state.getBoolean("capturePending")) return;
                    require(
                        HologramClient.state.cells.size() == 2 && state.getBoolean("blueprintDirectSave"),
                        "native two-stone import permits the real direct-save workflow");
                    if (!enabled((HologramScreen) mc.currentScreen, 149)) return;
                    nativeClick((HologramScreen) mc.currentScreen, 149);
                    next(44);
                    break;
                case 44:
                    if (!(mc.currentScreen instanceof HologramScreen)) return;
                    HologramScreen editor = (HologramScreen) mc.currentScreen;
                    if (!enabled(editor, 140)) return;
                    typeName(editor, "Area v0.1.2 native stones");
                    nativeClick(editor, 140);
                    next(45);
                    break;
                case 45:
                    if (!(mc.currentScreen instanceof HologramScreen)) return;
                    HologramScreen saving = (HologramScreen) mc.currentScreen;
                    if (saving.smokeLibraryUiState()
                        .getBoolean("aeReview")) {
                        if (!saveReviewConfirmed && enabled(saving, 181)) {
                            nativeClick(saving, 181);
                            saveReviewConfirmed = true;
                        }
                        return;
                    }
                    if (saving.smokePending() || HologramLibraryClient.INSTANCE.busy()
                        || state.getBoolean("blueprintSavePending")
                        || state.getBoolean("blueprintDirty")
                        || !state.getBoolean("blueprintSaved")
                        || !state.getBoolean("blueprintOperationSuccess")) return;
                    savedLocalId = HologramLibraryClient.INSTANCE.selected(false);
                    require(
                        savedLocalId.matches("local_[a-fA-F0-9-]{36}")
                            && savedLocalId.equals(state.getString("libraryLocalId")),
                        "actual private native save reached its own owner ACK");
                    if (!enabled(saving, 141)) return;
                    nativeClick(saving, 141);
                    next(50);
                    break;
                case 50:
                    if (!state.getBoolean("virtualPlacement") || state.getBoolean("virtualLocked")
                        || !state.getBoolean("moving")
                        || mc.currentScreen != null) return;
                    session = HologramClient.state.session;
                    cursorKind = "moving";
                    distance(6);
                    aimBlock(mc, 50, 80, 44);
                    desiredTarget = adjacentHit(mc);
                    observe(mc, "moving-no-alt-hit-face", 51, false);
                    break;
                case 51:
                    key(Keyboard.KEY_LMENU, true);
                    require(blockHit(mc), "Alt preview still has a real native source block hit");
                    forcedCorner = floatingExpected(mc, 6);
                    desiredTarget = forcedCorner;
                    observe(mc, "moving-alt-hit-forced-air", 52, false);
                    break;
                case 52:
                    useActual(mc, true);
                    next(53);
                    break;
                case 53:
                    if (!state.getBoolean("virtualLocked")) return;
                    require(
                        Arrays.equals(anchor(state), forcedCorner),
                        "actual Alt-right Netty ACK locks the previously rendered forced-air position");
                    cursorKind = "locked";
                    observe(mc, "moving-alt-right-locks-forced-air", 54, false);
                    break;
                case 54:
                    key(Keyboard.KEY_LMENU, false);
                    key(Keyboard.KEY_LSHIFT, true);
                    useActual(mc, false);
                    key(Keyboard.KEY_LSHIFT, false);
                    next(55);
                    break;
                case 55:
                    if (state.getBoolean("virtualLocked") || !state.getBoolean("moving")) return;
                    cursorKind = "moving";
                    distance(0);
                    aimBlock(mc, 50, 80, 44);
                    desiredTarget = adjacentHit(mc);
                    observe(mc, "moving-zero-distance-no-alt-hit-face", 56, false);
                    break;
                case 56:
                    key(Keyboard.KEY_LMENU, true);
                    aim(mc, 180, 75);
                    require(blockHit(mc), "Alt-zero green preview still has a real footing hit");
                    desiredTarget = feet(mc);
                    observe(mc, "moving-alt-zero-feet", 57, false);
                    break;
                case 57:
                    key(Keyboard.KEY_LMENU, false);
                    escape(mc);
                    next(58);
                    break;
                case 58:
                    if (state.getBoolean("virtualPlacement")) return;
                    cursorKind = "none";
                    desiredTarget = null;
                    observe(mc, "moving-escape-cancel-ack", 59, false);
                    break;
                case 59:
                    NBTTagCompound result = new NBTTagCompound();
                    result.setBoolean("passed", true);
                    result.setInteger("observations", sequence);
                    result.setString("session", session);
                    result.setBoolean("actualNettyClient", true);
                    result.setBoolean("syntheticSnapshotsUsed", false);
                    result.setString("savedLocalId", savedLocalId);
                    result.setTag("nativeUiInputs", uiInputs.copy());
                    bind(result);
                    HologramV011Evidence.write("area-client-final.nbt", result);
                    next(60);
                    break;
                case 60:
                    break;
                default:
                    throw new AssertionError("unknown area scenario stage " + stage);
            }
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    private void observe(Minecraft mc, String label, int following, boolean cursorRequired) throws Exception {
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("sequence", ++sequence);
        request.setString("label", label);
        request.setString("session", HologramClient.state.session);
        request.setLong("planRevision", HologramClient.state.data.getLong("planRevision"));
        request.setInteger("heldSlot", mc.thePlayer.inventory.currentItem);
        request.setBoolean("selecting", HologramClient.state.data.getBoolean("areaSelecting"));
        request.setIntArray("points", HologramClient.state.data.getIntArray("areaPoints"));
        request.setBoolean("cursorRequired", cursorRequired);
        request.setString("cursorKind", cursorKind);
        if (desiredTarget != null) request.setIntArray("expectedTarget", desiredTarget);
        request.setTag("nativeInput", lastInput.copy());
        request.setTag("nativeWheel", lastWheel.copy());
        request.setTag("nativeAim", aimWitness(mc));
        request.setLong("actionCount", actionCount);
        request.setBoolean("virtualPlacement", HologramClient.state.data.getBoolean("virtualPlacement"));
        request.setBoolean("virtualLocked", HologramClient.state.data.getBoolean("virtualLocked"));
        request.setBoolean("moving", HologramClient.state.data.getBoolean("moving"));
        request.setIntArray("serverAnchor", anchor(HologramClient.state.data));
        request.setLong("requestedAfterWorldGeneration", worldWitness == null ? 0 : worldWitness.generation);
        request.setFloat("requestedCameraPitch", mc.thePlayer.rotationPitch);
        request.setFloat("requestedCameraYaw", mc.thePlayer.rotationYaw);
        request.setLong("requestedAfterRenderFrame", frame);
        request.setLong("targetChangedAfterRenderFrame", targetChangedAfterFrame);
        request.setLong("targetChangedAfterWorldRender", targetChangedAfterWorldRender);
        request.setLong("requestedWorldRenderSerial", worldRenderSerial);
        request.setTag("nettySnapshot", HologramClient.state.data.copy());
        bind(request);
        observation = request;
        afterObservation = following;
        captured = false;
        HologramV011Evidence.write("area-client-observe.nbt", request);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        frame++;
        if (observation == null || captured || frame <= observation.getLong("requestedAfterRenderFrame")) return;
        // Every observation is requested after its camera/modifier/distance/slot input. A fresh
        // END alone may contain no world render; require the real world pass to advance as well.
        if (worldRenderSerial <= observation.getLong("requestedWorldRenderSerial")) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            HologramState actual = HologramClient.state;
            if (actual == null || !actual.session.equals(observation.getString("session"))
                || actual.data.getLong("planRevision") != observation.getLong("planRevision")
                || actual.data.getBoolean("areaSelecting") != observation.getBoolean("selecting")
                || !Arrays.equals(actual.data.getIntArray("areaPoints"), observation.getIntArray("points"))) return;
            if (observation.getBoolean("cursorRequired")
                && (areaWitness == null || areaWitness.state != actual || areaWitness.cursor() == null)) return;
            if (observation.getBoolean("cursorRequired") && observation.hasKey("expectedTarget", 11)) require(
                Arrays.equals(observation.getIntArray("expectedTarget"), areaWitness.cursor()),
                "the actual red target drawn in this END frame equals the requested native input target");
            String kind = observation.getString("cursorKind");
            if ("moving".equals(kind) || "locked".equals(kind)) {
                if (worldWitness == null || worldWitness.state != actual
                    || worldWitness.generation <= observation.getLong("requestedAfterWorldGeneration")) return;
                require(
                    Arrays.equals(
                        observation.getIntArray("expectedTarget"),
                        new int[] { worldWitness.anchorX, worldWitness.anchorY, worldWitness.anchorZ })
                        && worldWitness.moving == "moving".equals(kind)
                        && worldWitness.outlineColor == (worldWitness.moving ? 0x70e69a : 0x66d9ef)
                        && worldWitness.lists > 0,
                    "actual submitted world lists use the expected green or cyan preview anchor");
            }
            require(mc.currentScreen == null, "world evidence is captured from actual unobscured world frame");
            String name = "area-" + sequence + "-" + observation.getString("label") + ".png";
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            Path png = mc.mcDataDir.toPath()
                .resolve("screenshots")
                .resolve(name);
            require(Files.isRegularFile(png) && Files.size(png) > 0, "actual END framebuffer PNG exists");
            Files.copy(
                png,
                HologramV011Evidence.directory()
                    .resolve(name),
                StandardCopyOption.REPLACE_EXISTING);
            NBTTagCompound witness = (NBTTagCompound) observation.copy();
            witness.setBoolean("passed", true);
            witness.setString("capturePhase", "RenderTick.END");
            witness.setLong("actualRenderFrame", frame);
            witness.setLong("actualWorldRenderSerial", worldRenderSerial);
            witness.setInteger("stateObjectIdentity", System.identityHashCode(actual));
            witness.setInteger("framebufferObject", mc.getFramebuffer().framebufferObject);
            witness.setFloat("actualCameraPitch", mc.thePlayer.rotationPitch);
            witness.setFloat("actualCameraYaw", mc.thePlayer.rotationYaw);
            witness.setString("png", name);
            witness.setString(
                "pngSha256",
                hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(png))));
            witness.setString(
                "snapshotSha256",
                hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(CompressedStreamTools.compress(actual.data))));
            if (areaWitness != null && areaWitness.state == actual && areaWitness.cursor() != null)
                witness.setIntArray("drawnCurrentRedTarget", areaWitness.cursor());
            if ("moving".equals(kind) || "locked".equals(kind)) {
                NBTTagCompound drawn = new NBTTagCompound();
                drawn.setIntArray(
                    "anchor",
                    new int[] { worldWitness.anchorX, worldWitness.anchorY, worldWitness.anchorZ });
                drawn.setLong("generation", worldWitness.generation);
                drawn.setLong("planRevision", worldWitness.revision);
                drawn.setString("session", worldWitness.session);
                drawn.setBoolean("moving", worldWitness.moving);
                drawn.setInteger("outlineColor", worldWitness.outlineColor);
                drawn.setInteger("submittedLists", worldWitness.lists);
                drawn.setInteger("geometry", worldWitness.geometry);
                witness.setTag("drawnWorldPreview", drawn);
            }
            witness.setTag("actualNativeAim", aimWitness(mc));
            witness.setTag("nativeAimAtWorldRender", aimAtWorldRender.copy());
            HologramWorldGuide.RenderWitness hud = HologramWorldGuide.lastRenderWitness();
            if (hud != null) {
                witness.setInteger("hudTop", hud.top);
                witness.setInteger("hudCenterX", hud.centerX);
                boolean reserveBlockTooltip = cpw.mods.fml.common.Loader.isModLoaded("Waila")
                    && mc.objectMouseOver != null
                    && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
                require(
                    hud.top == (reserveBlockTooltip ? 40 : 8),
                    "conditional centered world guide reserves the block tooltip space");
            }
            HologramV011Evidence.write("area-client-step-" + sequence + ".nbt", witness);
            captured = true;
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    private void begin(Minecraft mc) throws Exception {
        key(Keyboard.KEY_LSHIFT, true);
        aimBlock(mc, 50, 80, 44);
        useActual(mc, false);
        key(Keyboard.KEY_LSHIFT, false);
    }

    private void corner(Minecraft mc, int button, int[] point, boolean consumed) throws Exception {
        if (button == 1 && consumed) press.begin(mc, false);
        mc.objectMouseOver = null;
        if (consumed) aimFloatingCorner(mc, point);
        if (consumed) desiredTarget = point.clone();
        long before = actionCount;
        NBTTagCompound input = aimWitness(mc);
        MouseEvent mouse = new MouseEvent();
        set(mouse, "button", button);
        set(mouse, "buttonstate", true);
        MinecraftForge.EVENT_BUS.post(mouse);
        require(mouse.isCanceled() == consumed, "actual registered mouse routing matches held context");
        if (consumed) recordInput(input, before, "areaPoint", button);
        else require(actionCount == before, "suspended mouse press emits no hologram action packet");
    }

    /** Real player mouse-look plus registered Alt-wheel input; no synthetic hit or preview-distance writes. */
    static void aimFloatingCorner(Minecraft mc, int[] point) throws Exception {
        require(
            mc.currentScreen == null && mc.thePlayer != null
                && HologramClient.areaProjectorWorld()
                && (mc.renderViewEntity == null || mc.renderViewEntity == mc.thePlayer),
            "floating corner uses the actual held projector and player camera");
        int[] originalFeet = feet(mc);
        mc.objectMouseOver = null; // Discard the previous frame's block hit before evaluating a requested air aim.
        int[] currentAnchor = HologramClient.areaSelectionAnchor();
        Field distanceField = HologramClient.class.getDeclaredField("previewDistance");
        distanceField.setAccessible(true);
        int currentDistance = ((HologramWorldFlow.PreviewDistance) distanceField.get(null)).distance();
        boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU);
        if (currentDistance == 0 && Arrays.equals(point, originalFeet)) {
            require(
                Arrays.equals(point, currentAnchor),
                "actual no-block distance zero cursor retains player feet and the existing camera view");
            return;
        }
        double eyeX = mc.thePlayer.posX, eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
            eyeZ = mc.thePlayer.posZ;
        double dx = point[0] + .5 - eyeX, dy = point[1] + .5 - eyeY, dz = point[2] + .5 - eyeZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.thePlayer.setAngles((yaw - mc.thePlayer.rotationYaw) / .15F, (mc.thePlayer.rotationPitch - pitch) / .15F);
        Vec3 look = mc.thePlayer.getLookVec();
        int distance = -1;
        for (int candidate = 1; candidate <= HologramWorldFlow.MAX_DISTANCE; candidate++) {
            int[] anchor = HologramWorldFlow
                .airAnchor(eyeX, eyeY, eyeZ, look.xCoord, look.yCoord, look.zCoord, candidate);
            if (Arrays.equals(anchor, point)) {
                distance = candidate;
                break;
            }
        }
        require(distance > 0, "actual player view ray reaches the requested source corner");
        try {
            key(Keyboard.KEY_LMENU, true);
            wheel(-120 * HologramWorldFlow.MAX_DISTANCE);
            wheel(120 * distance);
        } finally {
            key(Keyboard.KEY_LMENU, alt);
        }
        require(
            Arrays.equals(originalFeet, feet(mc)),
            "floating aiming changes only camera rotation and cursor distance");
        require(
            Arrays.equals(point, HologramClient.areaSelectionAnchor()),
            "actual no-block floating selection anchor equals the requested camera-ray corner");
    }

    private void installPacketObserver() throws Exception {
        Object wrapper = read(HologramNetwork.class, "channel");
        Channel channel = (Channel) ((java.util.Map<?, ?>) read(wrapper, "channels")).get(Side.CLIENT);
        require(channel != null, "actual client embedded Netty channel exists");
        Field payload = HologramNetwork.Packet.class.getDeclaredField("tag");
        payload.setAccessible(true);
        channel.pipeline()
            .addLast("gtmb-area-observe-only", new ChannelOutboundHandlerAdapter() {

                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
                    throws Exception {
                    if (message instanceof HologramNetwork.Action) {
                        lastAction = (NBTTagCompound) ((NBTTagCompound) payload.get(message)).copy();
                        actionCount++;
                    }
                    context.write(message, promise); // Forward the original message unchanged to the normal encoder.
                }
            });
    }

    private void recordInput(NBTTagCompound input, long before, String action, int button) {
        require(
            actionCount == before + 1 && lastAction != null && action.equals(lastAction.getString("action")),
            "native input emits exactly one actual Netty action: " + action);
        input.setInteger("button", button);
        input.setLong("actionCountBefore", before);
        input.setLong("actionCountAfter", actionCount);
        input.setTag("packet", lastAction.copy());
        lastInput = input;
    }

    private void markActual(Minecraft mc, int button) throws Exception {
        if (button == 1) press.begin(mc, false);
        NBTTagCompound input = aimWitness(mc);
        long before = actionCount;
        MouseEvent event = new MouseEvent();
        set(event, "button", button);
        set(event, "buttonstate", true);
        MinecraftForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "native area mouse event is consumed by the registered production listener");
        boolean finish = input.getBoolean("shift") && !input.getBoolean("control") && button == 1;
        recordInput(input, before, finish ? "finishAreaSelection" : "areaPoint", button);
    }

    private void useActual(Minecraft mc, boolean lock) throws Exception {
        press.begin(mc, lock);
        NBTTagCompound input = aimWitness(mc);
        long before = actionCount;
        if (blockHit(mc)) {
            MovingObjectPosition hit = mc.objectMouseOver;
            mc.playerController.onPlayerRightClick(
                mc.thePlayer,
                mc.theWorld,
                mc.thePlayer.getHeldItem(),
                hit.blockX,
                hit.blockY,
                hit.blockZ,
                hit.sideHit,
                hit.hitVec);
        } else mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
        String action = lock ? "confirmVirtualPosition"
            : input.getBoolean("shift")
                ? HologramClient.state != null && HologramClient.state.data.getBoolean("virtualLocked")
                    ? "unlockVirtualPosition"
                    : "beginAreaSelection"
                : "useBlock";
        recordInput(input, before, action, 1);
    }

    private static void aimBlock(Minecraft mc, int x, int y, int z) {
        double dx = x + .5 - mc.thePlayer.posX, dy = y + .5 - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight()),
            dz = z + .5 - mc.thePlayer.posZ;
        aim(
            mc,
            (float) Math.toDegrees(Math.atan2(-dx, dz)),
            (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        require(
            blockHit(mc) && Arrays.equals(new int[] { x, y, z }, hitBlock(mc)),
            "actual EntityRenderer ray hits the requested native source block");
    }

    private static void aim(Minecraft mc, float yaw, float pitch) {
        mc.thePlayer.setAngles((yaw - mc.thePlayer.rotationYaw) / .15F, (mc.thePlayer.rotationPitch - pitch) / .15F);
        mc.entityRenderer.getMouseOver(1.0F);
    }

    private static boolean blockHit(Minecraft mc) {
        return mc.objectMouseOver != null
            && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }

    private static int[] hitBlock(Minecraft mc) {
        MovingObjectPosition hit = mc.objectMouseOver;
        return new int[] { hit.blockX, hit.blockY, hit.blockZ };
    }

    private static int[] adjacentHit(Minecraft mc) {
        require(blockHit(mc), "face attachment requires a real native block hit");
        net.minecraftforge.common.util.ForgeDirection face = net.minecraftforge.common.util.ForgeDirection
            .getOrientation(mc.objectMouseOver.sideHit);
        int[] block = hitBlock(mc);
        return new int[] { block[0] + face.offsetX, block[1] + face.offsetY, block[2] + face.offsetZ };
    }

    private static int[] floatingExpected(Minecraft mc, int distance) {
        Vec3 look = mc.thePlayer.getLookVec();
        return new int[] { MathHelper.floor_double(mc.thePlayer.posX + look.xCoord * distance),
            MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.getEyeHeight() + look.yCoord * distance),
            MathHelper.floor_double(mc.thePlayer.posZ + look.zCoord * distance) };
    }

    private static int[] pair(int[] first, int[] second) {
        return new int[] { first[0], first[1], first[2], second[0], second[1], second[2] };
    }

    private static int[] anchor(NBTTagCompound data) {
        return new int[] { data.getInteger("x"), data.getInteger("y"), data.getInteger("z") };
    }

    private static boolean pending(NBTTagCompound data, int corner, int[] point) {
        return data.getInteger("areaPendingCorner") == corner && Arrays.equals(data.getIntArray("areaPoints"), point);
    }

    private static void distance(int value) throws Exception {
        boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU);
        key(Keyboard.KEY_LMENU, true);
        wheel(-120 * HologramWorldFlow.MAX_DISTANCE);
        if (value > 0) wheel(120 * value);
        key(Keyboard.KEY_LMENU, alt);
    }

    private void ordinaryWheel(Minecraft mc, int delta) throws Exception {
        require(
            !Keyboard.isKeyDown(Keyboard.KEY_LMENU) && !Keyboard.isKeyDown(Keyboard.KEY_RMENU),
            "ordinary wheel has no Alt modifier");
        MouseEvent event = new MouseEvent();
        set(event, "button", -1);
        set(event, "buttonstate", false);
        set(event, "dwheel", delta);
        MinecraftForge.EVENT_BUS.post(event);
        require(!event.isCanceled(), "production distance listener leaves ordinary wheel for the native hotbar");
        lastWheel = new NBTTagCompound();
        lastWheel.setInteger("delta", delta);
        lastWheel.setBoolean("alt", false);
        lastWheel.setBoolean("canceled", event.isCanceled());
        lastWheel.setInteger("slotBefore", mc.thePlayer.inventory.currentItem);
        mc.thePlayer.inventory.changeCurrentItem(delta); // The same uncanceled wheel branch as Minecraft.runTick.
        lastWheel.setInteger("slotAfter", mc.thePlayer.inventory.currentItem);
        lastWheel.setString("nativeBranch", "InventoryPlayer.changeCurrentItem");
        mc.playerController.updateController();
        slotChangedTick = mc.theWorld.getTotalWorldTime();
    }

    private static NBTTagCompound aimWitness(Minecraft mc) throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("alt", Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU));
        result.setBoolean(
            "control",
            Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
        result.setBoolean("shift", Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT));
        result.setBoolean("nativeBlockHit", blockHit(mc));
        result.setString("hitType", mc.objectMouseOver == null ? "null" : mc.objectMouseOver.typeOfHit.name());
        if (blockHit(mc)) {
            result.setIntArray("hitBlock", hitBlock(mc));
            result.setInteger("hitSide", mc.objectMouseOver.sideHit);
        }
        result.setIntArray("feet", feet(mc));
        result.setDouble("eyeX", mc.thePlayer.posX);
        result.setDouble("eyeY", mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        result.setDouble("eyeZ", mc.thePlayer.posZ);
        Vec3 look = mc.thePlayer.getLookVec();
        result.setDouble("lookX", look.xCoord);
        result.setDouble("lookY", look.yCoord);
        result.setDouble("lookZ", look.zCoord);
        result.setInteger(
            "distance",
            ((HologramWorldFlow.PreviewDistance) read(HologramClient.class, "previewDistance")).distance());
        return result;
    }

    private void nativeClick(HologramScreen screen, int id) {
        NBTTagCompound input = screen.smokeLibraryUiState();
        input.setInteger("controlId", id);
        input.setLong("renderGeneration", screen.smokeRenderGeneration());
        input.setString("session", HologramClient.state.session);
        input.setLong("planRevision", HologramClient.state.data.getLong("planRevision"));
        input.setTag("nettySnapshot", HologramClient.state.data.copy());
        int[] point = screen.smokeControlPoint(id);
        input.setIntArray("controlPoint", point);
        screen.mouseClicked(point[0], point[1], 0);
        screen.mouseMovedOrUp(point[0], point[1], 0);
        uiInputs.appendTag(input);
    }

    private void typeName(HologramScreen screen, String value) throws Exception {
        GuiTextField field = (GuiTextField) read(screen, "presetNameField");
        float scale = (Float) read(screen, "scale");
        int x = (Integer) read(screen, "originX") + Math.round((field.xPosition + 4) * scale);
        int y = (Integer) read(screen, "originY") + Math.round((field.yPosition + 7) * scale);
        screen.mouseClicked(x, y, 0);
        screen.mouseMovedOrUp(x, y, 0);
        require(field.isFocused(), "native mouse click focuses the actual blueprint name field");
        key(Keyboard.KEY_LCONTROL, true);
        screen.keyTyped('\u0001', Keyboard.KEY_A);
        require(
            field.getText()
                .equals(field.getSelectedText()),
            "native Ctrl+A selects the actual blueprint name");
        key(Keyboard.KEY_LCONTROL, false);
        for (char character : value.toCharArray()) screen.keyTyped(character, 0);
        require(value.equals(field.getText()), "native keyboard names the actual saved blueprint");
        NBTTagCompound input = new NBTTagCompound();
        input.setString("kind", "native-keyboard-name");
        input.setString("text", field.getText());
        input.setBoolean("focusedAfterNativeClick", field.isFocused());
        input.setBoolean("ctrlASelectedAll", true);
        input.setLong("renderGeneration", screen.smokeRenderGeneration());
        input.setString("session", HologramClient.state.session);
        input.setLong("planRevision", HologramClient.state.data.getLong("planRevision"));
        uiInputs.appendTag(input);
    }

    private static boolean enabled(HologramScreen screen, int id) {
        if (screen.smokePending()) return false;
        NBTTagList controls = screen.smokeLibraryUiState()
            .getTagList("drawnControls", 10);
        for (int index = 0; index < controls.tagCount(); index++) {
            NBTTagCompound control = controls.getCompoundTagAt(index);
            if (control.getInteger("id") == id) return control.getBoolean("enabled");
        }
        return false;
    }

    private static Object read(Object owner, String name) throws Exception {
        if (owner instanceof HologramScreen) return HologramUiDiagnostics.readDiagnosticField(owner, name);
        Field field = (owner instanceof Class ? (Class<?>) owner : owner.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner instanceof Class ? null : owner);
    }

    private static void bind(NBTTagCompound data) throws Exception {
        HologramV011InteractionFixture.bindEvidence(
            data,
            "client",
            HologramClient.state == null ? "" : HologramClient.state.session,
            HologramClient.state == null ? -1 : HologramClient.state.data.getLong("planRevision"));
    }

    private void slot(Minecraft mc, int value) {
        mc.thePlayer.inventory.currentItem = value; // Same vanilla hotbar state; updateController sends real C09.
        mc.playerController.updateController();
        slotChangedTick = mc.theWorld.getTotalWorldTime();
    }

    private static void escape(Minecraft mc) {
        mc.displayGuiScreen(new GuiIngameMenu()); // Normal Esc screen event is consumed by production routing.
        require(mc.currentScreen == null, "Esc routes through registered GuiOpenEvent");
    }

    private static boolean pendingRight(NBTTagCompound state, int[] value) {
        int[] points = state.getIntArray("areaPoints");
        return state.hasKey("areaPendingCorner", 3) && state.getInteger("areaPendingCorner") == 2
            && points.length == 3
            && Arrays.equals(points, value);
    }

    private static int[] feet(Minecraft mc) {
        return new int[] { MathHelper.floor_double(mc.thePlayer.posX),
            MathHelper.floor_double(mc.thePlayer.boundingBox.minY), MathHelper.floor_double(mc.thePlayer.posZ) };
    }

    private static void wheel(int value) throws Exception {
        MouseEvent wheel = new MouseEvent();
        set(wheel, "button", -1);
        set(wheel, "buttonstate", false);
        set(wheel, "dwheel", value);
        MinecraftForge.EVENT_BUS.post(wheel);
        require(wheel.isCanceled(), "actual Alt wheel handler owns distance input");
    }

    private static void key(int key, boolean down) throws Exception {
        keyboardState().put(key, (byte) (down ? 1 : 0));
        if (activeDriver != null) {
            if (down) activeDriver.heldModifiers.add(key);
            else activeDriver.heldModifiers.remove(key);
        }
    }

    private static ByteBuffer keyboardState() throws Exception {
        Field field = Keyboard.class.getDeclaredField("keyDownBuffer");
        field.setAccessible(true);
        return (ByteBuffer) field.get(null);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass()
            .getField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private void next(int value) {
        stage = value;
        stageStarted = System.nanoTime();
    }

    private void fail(Minecraft mc, Throwable failure) {
        failure.printStackTrace();
        finished = true;
        try {
            NBTTagCompound result = new NBTTagCompound();
            result.setInteger("stage", stage);
            result.setString("failure", failure.toString());
            result.setLong("actualRenderFrame", frame);
            result.setLong("actualWorldRenderSerial", worldRenderSerial);
            result.setLong("targetChangedAfterRenderFrame", targetChangedAfterFrame);
            result.setLong("targetChangedAfterWorldRender", targetChangedAfterWorldRender);
            result.setLong("actionCount", actionCount);
            result.setTag("lastNativeInput", lastInput.copy());
            result.setTag("nativeAimAtLastWorldRender", aimAtWorldRender.copy());
            result.setBoolean("fixtureHoldsAlt", heldModifiers.contains(Keyboard.KEY_LMENU));
            result.setBoolean("fixtureHoldsControl", heldModifiers.contains(Keyboard.KEY_LCONTROL));
            result.setBoolean("fixtureHoldsShift", heldModifiers.contains(Keyboard.KEY_LSHIFT));
            result.setLong("lastWitnessBeforeEndFrame", witnessBeforeEndFrame);
            if (observation != null) result.setTag("pendingObservation", observation.copy());
            if (desiredTarget != null) result.setIntArray("expectedTarget", desiredTarget);
            if (mc.thePlayer != null) {
                // Observe the real modifier and local distance before releasing fixture-owned keys.
                result.setTag("nativeAimBeforeRelease", aimWitness(mc));
                result.setFloat("actualCameraPitch", mc.thePlayer.rotationPitch);
                result.setFloat("actualCameraYaw", mc.thePlayer.rotationYaw);
                if (HologramClient.areaProjectorWorld())
                    result.setIntArray("liveAreaTargetBeforeRelease", HologramClient.areaSelectionAnchor());
            }
            if (areaWitness != null) {
                NBTTagCompound drawn = new NBTTagCompound();
                drawn.setString("session", areaWitness.state.session);
                drawn.setLong("planRevision", areaWitness.state.data.getLong("planRevision"));
                drawn.setInteger("stateObjectIdentity", System.identityHashCode(areaWitness.state));
                drawn.setLong("worldRenderSerial", worldRenderSerial);
                drawn.setLong("beforeEndFrame", witnessBeforeEndFrame);
                if (areaWitness.cursor() != null) drawn.setIntArray("cursor", areaWitness.cursor());
                result.setTag("lastAreaRenderWitness", drawn);
            }
            if (worldWitness != null) {
                NBTTagCompound drawn = new NBTTagCompound();
                drawn.setString("session", worldWitness.session);
                drawn.setLong("planRevision", worldWitness.revision);
                drawn.setLong("generation", worldWitness.generation);
                drawn.setBoolean("moving", worldWitness.moving);
                drawn.setInteger("outlineColor", worldWitness.outlineColor);
                drawn.setIntArray(
                    "anchor",
                    new int[] { worldWitness.anchorX, worldWitness.anchorY, worldWitness.anchorZ });
                result.setTag("lastWorldRenderWitness", drawn);
            }
            if (HologramClient.state != null)
                result.setInteger("liveStateObjectIdentity", System.identityHashCode(HologramClient.state));
            if (HologramClient.state != null) result.setTag("actualSnapshot", HologramClient.state.data.copy());
            for (int value : new int[] { Keyboard.KEY_LSHIFT, Keyboard.KEY_LCONTROL, Keyboard.KEY_LMENU })
                key(value, false);
            bind(result);
            HologramV011Evidence.write("area-client-failure.nbt", result);
            HologramV011Evidence.marker("area-client-fail.txt", "FAIL stage=" + stage + " " + failure);
        } catch (Exception evidenceFailure) {
            failure.addSuppressed(evidenceFailure);
        }
        mc.shutdown();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }
}
