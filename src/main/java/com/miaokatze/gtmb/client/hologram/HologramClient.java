package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.IMultiblockInfoContainer;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramSnapshotAssembly;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Registered only by ClientProxy; the common network receiver never links client classes. */
public final class HologramClient {

    private static boolean installed;
    static volatile HologramState state;
    private static net.minecraft.world.World snapshotWorld;
    static boolean worldPreview = true;
    private static long receivedAt;
    private static long acceptedBatch = -1;
    private static final Set<String> retiredSessions = new LinkedHashSet<>();
    private static final long SNAPSHOT_TTL_NS = 300000000000L;
    private static HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();
    private static net.minecraft.world.World assemblyWorld;
    private static final HologramSnapshotInbox<NBTTagCompound> snapshotInbox = new HologramSnapshotInbox<>(
        2,
        HologramSnapshotAssembly.MAX_BYTES,
        HologramSnapshotCodec::decode);
    private static long lastDecodeNotice;
    private static final HologramRootCloseFence rootCloseFence = new HologramRootCloseFence();

    /** Explicit root exit only: an already queued opening snapshot must not reopen the editor. */
    static void dismissRootView(String session, long sequence) {
        rootCloseFence.close(session, sequence);
    }

    private HologramClient() {}

    public static void install() {
        if (installed) return;
        installed = true;
        HologramLibraryClient.INSTANCE.install();
        HologramCutClient.INSTANCE.install();
        HologramNetwork.setProgressReceiver(nbt -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            Object world = minecraft.theWorld, connection = minecraft.getNetHandler();
            if (world == null || connection == null) return;
            minecraft.func_152344_a(() -> {
                if (world != minecraft.theWorld || connection != minecraft.getNetHandler()
                    || retiredSessions.contains(nbt.getString("session"))) return;
                HologramClientProgress.receive(nbt);
                if (state != null && state.session.equals(nbt.getString("session"))
                    && HologramClientProgress.matches(nbt)
                    && !"rejected".equals(nbt.getString("outcome"))) receivedAt = System.nanoTime();
                if ("rejected".equals(nbt.getString("outcome")) || HologramClientProgress.matches(nbt))
                    HologramBackgroundTasks.progress(nbt);
                if (nbt.getBoolean("jobDelta") && HologramClientProgress.matches(nbt))
                    HologramRenderer.jobVisualChanged(state, HologramJobVisuals.accept(state, nbt));
                if ("rejected".equals(nbt.getString("outcome"))) {
                    HologramLibraryClient.INSTANCE.transportRejectedSave(nbt);
                    if ("cut".equals(nbt.getString("scope"))) {
                        HologramCutClient.INSTANCE.transportRejected(nbt);
                        return;
                    }
                    if (minecraft.currentScreen instanceof HologramScreen)
                        ((HologramScreen) minecraft.currentScreen).ui.session.transportRejected(nbt);
                    else if (minecraft.currentScreen instanceof HologramSettingsScreen)
                        ((HologramSettingsScreen) minecraft.currentScreen).transportRejected(nbt);
                }
            });
        });
        HologramNetwork.setClientReceiver(
            nbt -> Minecraft.getMinecraft()
                .func_152344_a(() -> receive(nbt)));
        HologramNetwork.setClientInteractionSender(HologramClient::use);
        HologramClient listener = new HologramClient();
        MinecraftForge.EVENT_BUS.register(listener);
        FMLCommonHandler.instance()
            .bus()
            .register(listener);
        HologramClientSmoke.install();
        HologramCutNativeClientProbe.install();
        HologramBackgroundNativeProbe.install();
    }

    private static long lastUseTick = Long.MIN_VALUE;
    private static net.minecraft.world.World lastUseWorld;
    private static final HologramWorldFlow.PreviewDistance previewDistance = new HologramWorldFlow.PreviewDistance();
    private static final HologramWorldFlow.UsePressFence usePressFence = new HologramWorldFlow.UsePressFence();

    private static void syncPreviewDistance() {
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack tool = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        previewDistance.syncHeldTool(
            tool == null ? null : tool.getItem(),
            tool == null ? 0 : tool.getItemDamage(),
            mc.thePlayer == null ? -1 : mc.thePlayer.inventory.currentItem,
            tool == null || !tool.hasTagCompound() ? ""
                : tool.getTagCompound()
                    .getString("gtitHologramToolId"),
            tool);
        previewDistance.sync(
            HologramCutClient.INSTANCE.aiming() ? projectorWorld()
                : worldStage() == HologramWorldFlow.Stage.AREA ? areaProjectorWorld()
                    : projectorWorld() && worldStage() == HologramWorldFlow.Stage.MOVING,
            mc.theWorld,
            state == null ? "" : state.session);
    }

    private static HologramWorldFlow.Stage worldStage() {
        return state == null ? HologramWorldFlow.Stage.WORKSPACE
            : HologramWorldFlow.stage(
                state.data.getBoolean("areaSelecting") || state.data.getBoolean("areaImporting"),
                state.data.getBoolean("virtual"),
                state.data.getBoolean("virtualPlacement"),
                state.data.getBoolean("virtualLocked"),
                state.data.getInteger("job"));
    }

    private static boolean projectorWorld() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.currentScreen == null && mc.thePlayer != null
            && mc.theWorld != null
            && com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector
                .isProjector(mc.thePlayer.getHeldItem());
    }

    static boolean areaProjectorWorld() {
        if (!projectorWorld() || !areaWorldContext()) return false;
        ItemStack held = Minecraft.getMinecraft().thePlayer.getHeldItem();
        return HologramInputContext.selectionTool(
            state.data.getString("areaToolId"),
            held.hasTagCompound() ? held.getTagCompound()
                .getString("gtitHologramToolId") : "");
    }

    private static boolean areaWorldContext() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.currentScreen == null && mc.thePlayer != null
            && mc.theWorld != null
            && mc.theWorld == snapshotWorld
            && state != null
            && mc.thePlayer.dimension == state.data.getInteger("dimension")
            && (state.data.getBoolean("areaSelecting") || state.data.getBoolean("areaImporting"));
    }

    private static void use(NBTTagCompound input) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!projectorWorld()) return;
        if (HologramBackgroundTasks.busy()) {
            if (usePressFence.consume()) HologramBackgroundTasks.reopen();
            return;
        }
        HologramBackgroundTasks.reopen();
        if (HologramCutClient.INSTANCE.interceptsUse()) return;
        if (worldStage() == HologramWorldFlow.Stage.AREA && !areaProjectorWorld()) return;
        boolean air = input.getBoolean("air");
        long tick = mc.theWorld.getTotalWorldTime();
        if (mc.theWorld == lastUseWorld && tick == lastUseTick) return;
        if (air && mc.objectMouseOver != null
            && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) return;
        lastUseWorld = mc.theWorld;
        lastUseTick = tick;
        boolean shift = shiftHeld() && (worldStage() != HologramWorldFlow.Stage.AREA || !controlHeld());
        boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
        HologramWorldFlow.Intent intent = HologramWorldFlow.intent(
            worldStage(),
            shift ? HologramWorldFlow.Gesture.SHIFT_RIGHT
                : alt ? HologramWorldFlow.Gesture.ALT_RIGHT : HologramWorldFlow.Gesture.RIGHT);
        if (intent == HologramWorldFlow.Intent.NONE) return;
        if (!usePressFence.consume()) return;
        if (intent == HologramWorldFlow.Intent.BEGIN_AREA) {
            input.setString("action", "beginAreaSelection");
        } else
            if (intent == HologramWorldFlow.Intent.FINISH_AREA || intent == HologramWorldFlow.Intent.UNLOCK_POSITION) {
                worldAction(
                    intent == HologramWorldFlow.Intent.FINISH_AREA ? "finishAreaSelection" : "unlockVirtualPosition");
                return;
            } else if (intent == HologramWorldFlow.Intent.QUICK_PLACE) {
                int[] anchor = movingAnchor(state);
                input.setString("action", "quickPlaceLastBlueprint");
                input.setInteger("x", anchor[0]);
                input.setInteger("y", anchor[1]);
                input.setInteger("z", anchor[2]);
                if (air) input.setInteger("side", ForgeDirection.UP.ordinal());
                if (HologramLibraryClient.INSTANCE.quickPlaceLast(input)) return;
            } else if (intent == HologramWorldFlow.Intent.CONFIRM_POSITION) {
                int[] anchor = movingAnchor(state);
                input.setString("action", "confirmVirtualPosition");
                input.setString("session", state.session);
                input.setLong("planRevision", state.data.getLong("planRevision"));
                input.setInteger("x", anchor[0]);
                input.setInteger("y", anchor[1]);
                input.setInteger("z", anchor[2]);
                input.setInteger("side", state.data.getInteger("side"));
            } else input.setString("action", air ? "useAir" : "useBlock");
        HologramNetwork.sendAction(input);
    }

    private final HologramWorldFlow.KeyEdge rotationKey = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge areaUndoKey = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyRepeat[] nudgeKeys = { new HologramWorldFlow.KeyRepeat(),
        new HologramWorldFlow.KeyRepeat(), new HologramWorldFlow.KeyRepeat(), new HologramWorldFlow.KeyRepeat() };
    private final HologramWorldFlow.NudgeDispatch nudgeDispatch = new HologramWorldFlow.NudgeDispatch();
    private int queuedRepeatKey = -1;
    private String nudgeInputSession = "";
    private final HologramInputContext inputContext = new HologramInputContext();
    private boolean inputScreenChanged;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void cutMouse(MouseEvent event) {
        if (projectorWorld() && HologramBackgroundTasks.busy() && event.button >= 0) {
            event.setCanceled(true);
            if (event.buttonstate && event.button == 1 && usePressFence.consume()) HologramBackgroundTasks.reopen();
            return;
        }
        HologramCutClient.INSTANCE.mouse(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void movingWheel(MouseEvent event) {
        if (HologramBackgroundTasks.busy()) return;
        if (event.dwheel == 0 || !projectorWorld()
            || !HologramCutClient.INSTANCE.aiming()
                && (worldStage() != HologramWorldFlow.Stage.MOVING && worldStage() != HologramWorldFlow.Stage.AREA
                    || worldStage() == HologramWorldFlow.Stage.AREA && !areaProjectorWorld())
            || !altHeld()) return;
        syncPreviewDistance();
        previewDistance.scroll(event.dwheel);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void areaMouse(MouseEvent event) {
        if (HologramBackgroundTasks.busy()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!event.buttonstate || (event.button != 0 && event.button != 1)
            || !areaProjectorWorld()
            || !state.data.getBoolean("areaSelecting")) return;
        boolean control = controlHeld(), shift = shiftHeld();
        if (event.button == 1 && shift && !control) {
            event.setCanceled(true);
            if (!usePressFence.consume()) return;
            lastUseWorld = mc.theWorld;
            lastUseTick = mc.theWorld.getTotalWorldTime();
            worldAction("finishAreaSelection");
            return;
        }
        NBTTagCompound request = new NBTTagCompound();
        if (event.button == 1 && !usePressFence.consume()) {
            event.setCanceled(true);
            return;
        }
        request.setString("action", "areaPoint");
        request.setInteger("point", event.button == 0 ? 1 : 2);
        request.setString("mode", areaSelectionMode(control, shift));
        syncPreviewDistance();
        HologramWorldTarget.Target target = worldTarget(false);
        int[] anchor = target.anchor;
        request.setBoolean("atPlayer", target.atPlayer);
        request.setInteger("x", anchor[0]);
        request.setInteger("y", anchor[1]);
        request.setInteger("z", anchor[2]);
        event.setCanceled(true);
        lastUseWorld = mc.theWorld;
        lastUseTick = mc.theWorld.getTotalWorldTime();
        request.setString("session", state.session);
        request.setLong("planRevision", state.data.getLong("planRevision"));
        HologramNetwork.sendAction(request);
    }

    @SubscribeEvent
    public void areaGuide(RenderGameOverlayEvent.Text event) {
        HologramWorldGuide.clearRenderWitness();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null || mc.theWorld == null) return;
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (projectorWorld() && HologramBackgroundTasks.busy()) {
            lines.add(HologramBackgroundTasks.status());
            lines.add("右键：返回进行中的任务");
            HologramWorldGuide.draw(lines, event.resolution);
            return;
        }
        HologramProgress worldProgress = HologramClientProgress.view("server", "");
        if (projectorWorld() && worldProgress != null)
            lines.add(HologramProgressView.summary(worldProgress, System.nanoTime()));
        HologramProgress snapshotProgress = HologramClientProgress.view("snapshot", "");
        if (projectorWorld() && snapshotProgress != null && (worldProgress == null || !snapshotProgress.complete))
            lines.add(HologramProgressView.summary(snapshotProgress, System.nanoTime()));
        if (projectorWorld() && HologramJobVisuals.waitingForFull())
            lines.add(HologramProgressView.phase("job.visualLag"));
        HologramProgress libraryProgress = HologramLibraryClient.INSTANCE.progress();
        if (projectorWorld() && libraryProgress != null)
            lines.add(HologramProgressView.summary(libraryProgress, System.nanoTime()));
        if (HologramCutClient.INSTANCE.guide(lines)) {
            HologramWorldGuide.draw(lines, event.resolution);
            return;
        }
        if (projectorWorld() && HologramLibraryClient.INSTANCE.quickInProgress()) lines.add(
            HologramLibraryClient.INSTANCE.status() + " · "
                + HologramLibraryClient.bytes(HologramLibraryClient.INSTANCE.progressBytes())
                + " / "
                + HologramLibraryClient.bytes(HologramLibraryClient.INSTANCE.totalBytes()));
        HologramWorldFlow.Stage stage = worldStage();
        if (stage == HologramWorldFlow.Stage.WORKSPACE) {
            HologramWorldGuide.draw(lines, event.resolution);
            return;
        }
        if (stage == HologramWorldFlow.Stage.AREA && areaWorldContext() && !areaProjectorWorld()) {
            lines.add(
                text(
                    "worldGuideAreaPaused",
                    "Area selection paused · Hold the same projector to continue · Esc exits selection"));
            HologramWorldGuide.draw(lines, event.resolution);
            return;
        }
        if (!projectorWorld() || state == null || mc.theWorld != snapshotWorld) return;
        String key = state != null && state.data.getBoolean("areaImporting") ? "worldGuideAreaImport"
            : stage == HologramWorldFlow.Stage.AREA ? "worldGuideArea"
                : stage == HologramWorldFlow.Stage.MOVING ? "worldGuideMoving"
                    : stage == HologramWorldFlow.Stage.LOCKED ? "worldGuideLocked"
                        : stage == HologramWorldFlow.Stage.JOB ? "worldGuideJob" : "worldGuideIdle";
        lines.add(text(key, "Right-click: projector workspace"));
        if (stage == HologramWorldFlow.Stage.AREA && !state.data.getBoolean("areaImporting")) {
            net.minecraft.nbt.NBTTagList regions = state.data.getTagList("areaRegions", 10);
            int subtracted = 0;
            for (int i = 0; i < regions.tagCount(); i++) if ("subtract".equals(
                regions.getCompoundTagAt(i)
                    .getString("mode")))
                subtracted++;
            lines.add(
                String.format(
                    java.util.Locale.ROOT,
                    text("worldGuideAreaRegions", "Regions %d, subtract %d"),
                    regions.tagCount() - subtracted,
                    subtracted));
            lines.add(
                text(
                    "worldGuideAreaOrder",
                    "Mark either corner first · Ctrl / Ctrl+Shift at the first mark locks the combination mode"));
            if (state.data.getInteger("areaPendingCorner") != 0) lines.add(
                String.format(
                    java.util.Locale.ROOT,
                    text("worldGuideAreaMode", "Locked mode: %s · Mark the other corner to complete"),
                    text(
                        "worldGuideAreaMode." + state.data.getString("areaPointMode"),
                        state.data.getString("areaPointMode"))));
        }
        if (stage == HologramWorldFlow.Stage.MOVING || stage == HologramWorldFlow.Stage.AREA) {
            int[] anchor = stage == HologramWorldFlow.Stage.AREA ? areaSelectionAnchor() : movingAnchor(state);
            lines.add(
                String.format(
                    java.util.Locale.ROOT,
                    text("worldGuideTarget", "Distance %d | Target %d, %d, %d | Alt + scroll: distance"),
                    previewDistance.distance(),
                    anchor[0],
                    anchor[1],
                    anchor[2]));
            String status = HologramUiText.reason(state.data.getString("status"));
            if (!status.isEmpty()) lines.add(status);
        } else if (stage == HologramWorldFlow.Stage.LOCKED) {
            lines.add(
                String.format(
                    java.util.Locale.ROOT,
                    text("worldGuideLockedTarget", "Locked target %d, %d, %d"),
                    state.data.getInteger("x"),
                    state.data.getInteger("y"),
                    state.data.getInteger("z")));
            String status = HologramUiText.reason(state.data.getString("status"));
            if (!status.isEmpty()) lines.add(status);
        }
        HologramWorldGuide.draw(lines, event.resolution);
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        syncSnapshotWorld();
        HologramBackgroundTasks.tick();
        installDecoded(snapshotInbox.poll());
        HologramLibraryClient.INSTANCE.tick();
        HologramCutClient.INSTANCE.tick();
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack tool = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        boolean changed = inputContext.observe(
            mc.theWorld,
            mc.currentScreen,
            tool == null ? null : tool.getItem(),
            tool == null ? 0 : tool.getItemDamage(),
            mc.thePlayer == null ? -1 : mc.thePlayer.inventory.currentItem,
            tool == null || !tool.hasTagCompound() ? ""
                : tool.getTagCompound()
                    .getString("gtitHologramToolId"),
            tool,
            inputScreenChanged);
        inputScreenChanged = false;
        usePressFence.observe(mc.gameSettings.keyBindUseItem.getIsKeyPressed(), changed);
        syncPreviewDistance();
        if (HologramBackgroundTasks.busy()) return;
        if (HologramCutClient.INSTANCE.active()) return;
        lockedNudge(mc, changed);
        boolean rotate = rotationKey.sample(Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_R), changed);
        boolean undoArea = areaUndoKey.sample(
            Keyboard.isCreated() && controlHeld() && Keyboard.isKeyDown(Keyboard.KEY_Z) && !shiftHeld(),
            changed);
        if (!projectorWorld() || state == null || mc.theWorld != snapshotWorld) return;
        if (undoArea && areaProjectorWorld()
            && state.data.getBoolean("areaSelecting")
            && state.data.getBoolean("areaUndoAvailable")) {
            NBTTagCompound undo = new NBTTagCompound();
            undo.setString("op", "undoAreaSelection");
            undo.setString("session", state.session);
            undo.setLong("planRevision", state.data.getLong("planRevision"));
            HologramNetwork.sendAction(undo);
        }
        if (!rotate
            || HologramWorldFlow.intent(worldStage(), HologramWorldFlow.Gesture.ROTATE)
                != HologramWorldFlow.Intent.ROTATE
            || state.data.getBoolean("capturePending")
            || state.cells.isEmpty()) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", state.session);
        request.setLong("planRevision", state.data.getLong("planRevision"));
        request.setString("op", "rotateVirtual");
        request.setBoolean(
            "vertical",
            Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
        HologramNetwork.sendAction(request);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void progressFrame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) HologramProgressView.beginFrame();
    }

    private void lockedNudge(Minecraft mc, boolean contextChanged) {
        boolean enabled = projectorWorld() && state != null
            && mc.theWorld == snapshotWorld
            && worldStage() == HologramWorldFlow.Stage.LOCKED
            && state.data.getInteger("job") == 0
            && !state.data.getBoolean("capturePending")
            && !state.cells.isEmpty();
        long tick = mc.theWorld == null ? 0 : mc.theWorld.getTotalWorldTime();
        String currentSession = enabled ? state.session : "";
        boolean changed = contextChanged || !nudgeInputSession.equals(currentSession);
        nudgeInputSession = currentSession;
        nudgeDispatch
            .sync(currentSession, state == null ? -1 : state.data.getLong("planRevision"), tick, changed || !enabled);
        if (changed || !enabled) queuedRepeatKey = -1;
        int[] keys = { Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT };
        int fired = -1, pressed = 0;
        boolean repeat = false;
        boolean repeatHeld = queuedRepeatKey < 0;
        for (int i = 0; i < keys.length; i++) {
            boolean held = Keyboard.isCreated() && Keyboard.isKeyDown(keys[i]);
            if (i == queuedRepeatKey) repeatHeld = held;
            if (held) pressed++;
            if (nudgeKeys[i].sample(held, enabled, changed, tick)) {
                fired = i;
                repeat = nudgeKeys[i].repeated();
            }
        }
        if (pressed == 0 || !repeatHeld) nudgeDispatch.releaseRepeats();
        if (!enabled) return;
        if (fired >= 0 && pressed == 1) {
            boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
            HologramWorldFlow.Nudge direction = fired == 0
                ? alt ? HologramWorldFlow.Nudge.UP : HologramWorldFlow.Nudge.FORWARD
                : fired == 1 ? alt ? HologramWorldFlow.Nudge.DOWN : HologramWorldFlow.Nudge.BACKWARD
                    : fired == 2 ? HologramWorldFlow.Nudge.LEFT : HologramWorldFlow.Nudge.RIGHT;
            net.minecraft.entity.EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer
                : mc.renderViewEntity;
            nudgeDispatch.queue(HologramWorldFlow.nudgeOffset(camera.rotationYaw, direction), repeat);
            queuedRepeatKey = repeat ? fired : -1;
        }
        int[] offset = nudgeDispatch.poll(state.data.getLong("planRevision"), tick);
        if (offset == null) return;
        queuedRepeatKey = -1;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "nudgeVirtualPosition");
        request.setString("session", state.session);
        request.setLong("planRevision", state.data.getLong("planRevision"));
        request.setInteger("dx", offset[0]);
        request.setInteger("dy", offset[1]);
        request.setInteger("dz", offset[2]);
        HologramNetwork.sendAction(request);
    }

    @SubscribeEvent
    public void cutWorldKey(InputEvent.KeyInputEvent event) {
        if (HologramBackgroundTasks.busy()) return;
        HologramCutClient.INSTANCE.keyInput();
    }

    /** Intercept only the world's pause menu; chat and other screens retain their own Escape behavior. */
    @SubscribeEvent
    public void worldEscape(GuiOpenEvent event) {
        inputScreenChanged = true;
        previewDistance.sync(false, null, "");
        if (HologramBackgroundTasks.busy()) return;
        if (HologramCutClient.INSTANCE.escape(event)) return;
        if (event.gui instanceof GuiIngameMenu && projectorWorld()
            && HologramLibraryClient.INSTANCE.cancelQuickPlacement()) {
            HologramWorldFlow.Intent pendingIntent = HologramWorldFlow
                .intent(worldStage(), HologramWorldFlow.Gesture.ESCAPE);
            if (state == null || pendingIntent != HologramWorldFlow.Intent.CANCEL_AREA
                && pendingIntent != HologramWorldFlow.Intent.CANCEL_PLACEMENT) {
                event.setCanceled(true);
                return;
            }
        }
        if (!(event.gui instanceof GuiIngameMenu) || state == null || !areaWorldContext() && !projectorWorld()) return;
        HologramWorldFlow.Intent intent = HologramWorldFlow.intent(worldStage(), HologramWorldFlow.Gesture.ESCAPE);
        if (intent != HologramWorldFlow.Intent.CANCEL_AREA && intent != HologramWorldFlow.Intent.CANCEL_PLACEMENT)
            return;
        event.setCanceled(true);
        if (intent == HologramWorldFlow.Intent.CANCEL_PLACEMENT)
            dismissRootView(state.session, state.data.getLong("uiSequence"));
        worldAction(intent == HologramWorldFlow.Intent.CANCEL_AREA ? "cancelAreaSelection" : "cancelVirtualPlacement");
    }

    private static void worldAction(String action) {
        if (HologramBackgroundTasks.busy()) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", action);
        if (state != null) {
            request.setString("session", state.session);
            request.setLong("planRevision", state.data.getLong("planRevision"));
        }
        if ("finishAreaSelection".equals(action) && state != null) {
            long sequence = Math
                .max(state.data.getLong("uiSequence"), HologramBackgroundTasks.commandSequence(state.session)) + 1;
            request.setLong("uiSequence", sequence);
            HologramBackgroundTasks.command(state.session, sequence, "importArea");
        }
        HologramNetwork.sendAction(request);
    }

    public static void beginAreaSelection() {
        if (HologramBackgroundTasks.busy()) return;
        Minecraft.getMinecraft()
            .displayGuiScreen(null);
        worldAction("beginAreaSelection");
    }

    public static void openSettings() {
        if (HologramBackgroundTasks.reopen()) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "openSettings");
        HologramNetwork.sendAction(request);
    }

    /** Only a render translation; no moving-preview world mutation packets are emitted. */
    static int[] movingAnchor(HologramState snapshot) {
        if (snapshot != null && snapshot.data.getBoolean("cutPreview")) return HologramCutClient.INSTANCE.anchor();
        return worldTarget(true).anchor;
    }

    static int[] cutAnchor(boolean adjacent) {
        return worldTarget(adjacent).anchor;
    }

    private static boolean altHeld() {
        return Keyboard.isCreated()
            && (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU));
    }

    private static boolean controlHeld() {
        return Keyboard.isCreated()
            && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
    }

    private static boolean shiftHeld() {
        return Keyboard.isCreated()
            && (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT));
    }

    static String areaSelectionMode(boolean control, boolean shift) {
        return control ? shift ? "subtract" : "add" : "replace";
    }

    private static int[] playerAnchor(Minecraft mc) {
        return new int[] { net.minecraft.util.MathHelper.floor_double(mc.thePlayer.posX),
            net.minecraft.util.MathHelper.floor_double(mc.thePlayer.boundingBox.minY),
            net.minecraft.util.MathHelper.floor_double(mc.thePlayer.posZ) };
    }

    private static HologramWorldTarget.Target worldTarget(boolean adjacent) {
        Minecraft mc = Minecraft.getMinecraft();
        syncPreviewDistance();
        net.minecraft.entity.EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        return HologramWorldTarget.resolve(
            mc.objectMouseOver,
            altHeld(),
            adjacent,
            previewDistance.distance(),
            playerAnchor(mc),
            net.minecraft.util.Vec3.createVectorHelper(camera.posX, camera.posY + camera.getEyeHeight(), camera.posZ),
            camera.getLookVec());
    }

    /** The red cursor and point packets use the same block or floating target. */
    static int[] areaSelectionAnchor() {
        return worldTarget(false).anchor;
    }

    private static void receive(NBTTagCompound nbt) {
        syncSnapshotWorld();
        if (Minecraft.getMinecraft().theWorld == null) return;
        if (nbt.getBoolean("transportError")) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(
                new net.minecraft.util.ChatComponentText(
                    text(
                        "transportError",
                        "Hologram snapshot exceeds the transfer budget; the previous preview was retained.")));
            return;
        }
        installDecoded(snapshotInbox.poll());
        if (nbt.getBoolean("transportFragment")) {
            byte[] complete = assembly.accept(
                nbt.getLong("transportSequence"),
                nbt.getInteger("transportTotal"),
                nbt.getInteger("transportIndex"),
                nbt.getByteArray("transportBytes"),
                System.nanoTime());
            if (complete == null) return;
            long transportSequence = nbt.getLong("transportSequence");
            HologramClientProgress.decoding(transportSequence);
            while (!snapshotInbox.offer(complete, transportSequence)) {
                decodeNotice(
                    "decodeBusy",
                    "Hologram snapshots are arriving faster than they can be decoded; waiting to preserve update order.");
                installDecoded(snapshotInbox.awaitNext());
            }
            return;
        }
        while (!snapshotInbox.offerValue(nbt)) {
            decodeNotice(
                "decodeBusy",
                "Hologram snapshots are arriving faster than they can be decoded; waiting to preserve update order.");
            installDecoded(snapshotInbox.awaitNext());
        }
        installDecoded(snapshotInbox.poll());
    }

    private static void syncSnapshotWorld() {
        net.minecraft.world.World world = Minecraft.getMinecraft().theWorld;
        if (world == assemblyWorld) return;
        previewDistance.sync(false, null, "");
        assembly = new HologramSnapshotAssembly();
        assemblyWorld = world;
        HologramClientProgress.clear();
        snapshotInbox.clear();
        if (state != null && snapshotWorld != world) clearProjection();
    }

    private static void decodeNotice(String key, String fallback) {
        long now = System.nanoTime();
        if (lastDecodeNotice != 0 && now - lastDecodeNotice < 5_000_000_000L) return;
        lastDecodeNotice = now;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null)
            mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(text(key, fallback)));
    }

    private static void installDecoded(HologramSnapshotInbox.Result<NBTTagCompound> result) {
        if (result == null) return;
        if (result.failure != null) {
            HologramClientProgress.decoded(result.transportSequence, false);
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Hologram snapshot decoding failed", result.failure);
            decodeNotice("decodeError", "Hologram snapshot decoding failed; the previous preview was retained.");
            return;
        }
        installSnapshot(result.value);
        HologramClientProgress.decoded(result.transportSequence, true);
    }

    /** Registry lookups, world access, GUI and rendering resources remain on the client thread. */
    private static void installSnapshot(NBTTagCompound nbt) {
        if (nbt.getBoolean("cancelled")) {
            HologramBackgroundTasks.cancelled(nbt.getString("session"));
            if (state != null && state.session.equals(nbt.getString("session"))) clearProjection(false);
            return;
        }
        String session = nbt.getString("session");
        String generation = nbt.getString("generation");
        if ((!generation.isEmpty() && !generation.equals(session)) || retiredSessions.contains(session)) return;
        // Progress never replaces the last complete editable structure or consumes an ACK ticket.
        if (nbt.getBoolean("capturePending")) {
            HologramClientProgress.snapshot(nbt);
            if (nbt.hasKey("progress", 10)) HologramBackgroundTasks.progress(nbt.getCompoundTag("progress"));
            return;
        }
        boolean same = state != null && state.session.equals(session);
        if (same && nbt.getLong("planRevision") < state.data.getLong("planRevision")) return;
        long batch = nbt.getLong("batchSeq");
        if (same && (batch < acceptedBatch || (batch == acceptedBatch && "ACK".equals(state.data.getString("phase"))
            && "PREPARE".equals(nbt.getString("phase"))))) return;
        // Reject stale packets before copying a large NBT tree or resolving thousands of block/item entries.
        HologramState next = new HologramState(nbt);
        HologramClientProgress.snapshot(nbt);
        if (!same) {
            rootCloseFence.clear();
            if (state != null) retire(state.session);
            HologramRenderer.release();
            acceptedBatch = -1;
        }
        long now = System.nanoTime();
        acceptedBatch = batch;
        receivedAt = now;
        HologramRenderer.release();
        HologramJobVisuals.bind(next);
        state = next;
        HologramUiConfiguration.observePresetSnapshot(next);
        HologramBackgroundTasks.snapshot(next);
        if ("blueprintPlacement".equals(nbt.getString("openPurpose"))) HologramScreen.rememberBlueprintPlacement(next);
        Minecraft mc = Minecraft.getMinecraft();
        snapshotWorld = mc.theWorld;
        if (HologramBackgroundTasks.detached() || mc.currentScreen instanceof HologramBackgroundScreen) return;
        if (rootCloseFence
            .suppress(next.session, nbt.getLong("uiSequence"), nbt.getBoolean("open"), nbt.getBoolean("areaImporting")))
            return;
        restorePlacementDeidentification(next);
        if (!same && mc.currentScreen instanceof HologramScreen
            && ((HologramScreen) mc.currentScreen).hasPendingAeReviewNavigation()) {
            ((HologramScreen) mc.currentScreen).updateState(next);
            return;
        }
        if (nbt.getBoolean("areaImporting")
            && (mc.currentScreen == null || mc.currentScreen instanceof HologramScreen)) {
            if (mc.currentScreen instanceof HologramScreen && same)
                ((HologramScreen) mc.currentScreen).updateState(next);
            else mc.displayGuiScreen(new HologramScreen(next));
            return;
        }
        if (nbt.getBoolean("areaSelecting") && !nbt.getBoolean("open")) {
            if (mc.currentScreen instanceof HologramScreen || mc.currentScreen instanceof HologramSettingsScreen)
                mc.displayGuiScreen(null);
            return;
        }
        if (nbt.getBoolean("hints")) hints(next);
        if (nbt.getBoolean("settingsOnly")) {
            if (mc.currentScreen instanceof HologramSettingsScreen && same)
                ((HologramSettingsScreen) mc.currentScreen).updateState(next);
            else mc.displayGuiScreen(new HologramSettingsScreen(next));
        } else if (nbt.getBoolean("virtual") && nbt.getBoolean("virtualPlacement") && !nbt.getBoolean("open")) {
            if (mc.currentScreen instanceof HologramScreen) {
                HologramScreen screen = (HologramScreen) mc.currentScreen;
                if (same && screen.retainsPlacementReview(next)) screen.updateState(next);
                else mc.displayGuiScreen(null);
            }
        } else if (HologramAeContainerScreen.activeForSession(next.session) != null && same) {
            // The MUI2 stack preserves this child while NEI temporarily owns the vanilla screen.
            HologramAeContainerScreen.activeForSession(next.session)
                .updateState(next);
        } else if (mc.currentScreen instanceof HologramScreen
            && (same || ((HologramScreen) mc.currentScreen).acceptsLibraryNavigation(next))) {
                ((HologramScreen) mc.currentScreen).updateState(next);
            } else if (!same || nbt.getBoolean("open")) {
                mc.displayGuiScreen(new HologramScreen(next));
            }
    }

    private static final HologramPlacementDeidentificationSync placementIdentitySync = new HologramPlacementDeidentificationSync();

    static void placementDeidentificationRequested(HologramState snapshot, int level) {
        placementIdentitySync.requested(snapshot.data, level);
    }

    private static void restorePlacementDeidentification(HologramState snapshot) {
        if (!snapshot.data.getBoolean("virtualPlacement")) {
            placementIdentitySync.clear();
            return;
        }
        HologramDeidentificationMemory memory = HologramDeidentificationMemory.client();
        Integer remembered = memory.choice(HologramDeidentificationMemory.source(snapshot.data));
        if (remembered == null) return;
        int level = HologramDeidentificationMemory.level(snapshot.data, true, remembered);
        if (!placementIdentitySync.shouldRestore(snapshot.data, level)) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("op", "configurePlacementDeidentification");
        request.setString("session", snapshot.session);
        request.setLong("planRevision", snapshot.data.getLong("planRevision"));
        request.setLong("placementReviewNonce", snapshot.data.getLong("placementReviewNonce"));
        request.setInteger("deidentificationLevel", level);
        request.setInteger("level", level);
        request.setBoolean("deidentificationChosen", true);
        HologramNetwork.sendAction(request);
    }

    private static void hints(HologramState snapshot) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null
            || mc.thePlayer.dimension != snapshot.data.getInteger("dimension")) return;
        TileEntity tile = mc.theWorld
            .getTileEntity(snapshot.data.getInteger("x"), snapshot.data.getInteger("y"), snapshot.data.getInteger("z"));
        IConstructable target = null;
        if (tile instanceof IConstructableProvider) target = ((IConstructableProvider) tile).getConstructable();
        else if (tile instanceof IConstructable) target = (IConstructable) tile;
        else if (tile != null && IMultiblockInfoContainer.contains(tile.getClass())) {
            ExtendedFacing facing = tile instanceof IAlignment ? ((IAlignment) tile).getExtendedFacing()
                : ExtendedFacing.of(ForgeDirection.getOrientation(snapshot.data.getInteger("side")));
            target = IMultiblockInfoContainer.<TileEntity>get(tile.getClass())
                .toConstructable(tile, facing);
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (target == null || held == null) return;
        ItemStack trigger = held.copy();
        trigger.stackSize = snapshot.data.getInteger("main");
        NBTTagCompound channels = snapshot.data.getCompoundTag("channels");
        for (Object key : channels.func_150296_c())
            ChannelDataAccessor.setChannelData(trigger, key.toString(), channels.getInteger(key.toString()));
        // hintsOnly=true is the native client hint path; this never requests a construction operation.
        StructureLibAPI.startHinting(mc.theWorld);
        try {
            target.construct(trigger, true);
        } catch (RuntimeException e) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Hologram native hints failed", e);
        } finally {
            StructureLibAPI.endHinting(mc.theWorld);
        }
    }

    private static void retire(String session) {
        retiredSessions.add(session);
        if (retiredSessions.size() > 32) retiredSessions.remove(
            retiredSessions.iterator()
                .next());
    }

    static void clearProjection() {
        clearProjection(true);
    }

    private static void clearProjection(boolean invalidatePending) {
        placementIdentitySync.clear();
        rootCloseFence.clear();
        if (invalidatePending) {
            assembly.clear();
            snapshotInbox.clear();
        }
        // A decoded server cancellation can precede an already arriving new-session snapshot.
        if (state != null) retire(state.session);
        HologramRenderer.release();
        HologramJobVisuals.bind(null);
        state = null;
        snapshotWorld = null;
        acceptedBatch = -1;
    }

    static String text(String key, String fallback) {
        String full = "gtmb.hologram." + key;
        return StatCollector.canTranslate(full) ? StatCollector.translateToLocal(full) : fallback;
    }

    @SubscribeEvent
    public void renderWorld(RenderWorldLastEvent event) {
        HologramAreaOverlay.clearRenderWitness();
        Minecraft mc = Minecraft.getMinecraft();
        assembly.expire(System.nanoTime());
        syncSnapshotWorld();
        if (HologramCutClient.INSTANCE.render(event.partialTicks)) return;
        if (state == null) return;
        if (mc.theWorld == null || mc.theWorld != snapshotWorld
            || mc.thePlayer == null
            || mc.thePlayer.dimension != state.data.getInteger("dimension")
            || System.nanoTime() - receivedAt > SNAPSHOT_TTL_NS && !HologramBackgroundTasks.busy()) {
            clearProjection();
            return;
        }
        if (state.data.getBoolean("areaSelecting")) HologramAreaOverlay.render(state, event.partialTicks);
        if (!worldPreview || state.data.getInteger("job") == 4) return;
        if (state.data.getBoolean("virtual") && !state.data.getBoolean("virtualPlacement")) return;
        // Avoid stale projection after disconnect/reconnect or travelling away from the target.
        if (state.distanceSquared(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ) > 4096
            && !(state.data.getBoolean("virtual") && state.data.getBoolean("moving"))) return;
        HologramRenderer.world(state, event.partialTicks);
    }
}
