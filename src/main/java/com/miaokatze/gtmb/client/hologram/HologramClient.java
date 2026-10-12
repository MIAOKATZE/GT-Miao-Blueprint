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
import cpw.mods.fml.common.gameevent.TickEvent;

/** Registered only by ClientProxy; the common network receiver never links client classes. */
public final class HologramClient {

    private static boolean installed;
    static HologramState state;
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

    private HologramClient() {}

    public static void install() {
        if (installed) return;
        installed = true;
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
    }

    private static long lastUseTick = Long.MIN_VALUE;
    private static net.minecraft.world.World lastUseWorld;
    private static final HologramWorldFlow.PreviewDistance previewDistance = new HologramWorldFlow.PreviewDistance();
    private static Object previewHeldTool;

    private static void syncPreviewDistance() {
        Minecraft mc = Minecraft.getMinecraft();
        Object tool = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (previewHeldTool != tool) {
            previewDistance.sync(false, null, "");
            previewHeldTool = tool;
        }
        previewDistance.sync(
            projectorWorld() && worldStage() == HologramWorldFlow.Stage.MOVING,
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

    private static void use(NBTTagCompound input) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!projectorWorld()) return;
        boolean air = input.getBoolean("air");
        long tick = mc.theWorld.getTotalWorldTime();
        if (mc.theWorld == lastUseWorld && tick == lastUseTick) return;
        if (air && mc.objectMouseOver != null
            && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) return;
        lastUseWorld = mc.theWorld;
        lastUseTick = tick;
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        HologramWorldFlow.Intent intent = HologramWorldFlow
            .intent(worldStage(), shift ? HologramWorldFlow.Gesture.SHIFT_RIGHT : HologramWorldFlow.Gesture.RIGHT);
        if (intent == HologramWorldFlow.Intent.NONE) return;
        if (intent == HologramWorldFlow.Intent.BEGIN_AREA) {
            input.setString("action", "beginAreaSelection");
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
    private final HologramWorldFlow.KeyEdge enterKey = new HologramWorldFlow.KeyEdge();
    private Object inputWorld, inputTool, inputScreen;
    private boolean inputScreenChanged;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void movingWheel(MouseEvent event) {
        if (event.dwheel == 0 || !projectorWorld() || worldStage() != HologramWorldFlow.Stage.MOVING) return;
        syncPreviewDistance();
        previewDistance.scroll(event.dwheel);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void areaMouse(MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!event.buttonstate || (event.button != 0 && event.button != 1)
            || mc.currentScreen != null
            || state == null
            || !state.data.getBoolean("areaSelecting")
            || mc.thePlayer == null
            || mc.theWorld == null
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector
                .isProjector(mc.thePlayer.getHeldItem()))
            return;
        boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
        NBTTagCompound request = new NBTTagCompound();
        MovingObjectPosition hit = mc.objectMouseOver;
        if (!alt && (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK)) {
            event.setCanceled(true);
            return;
        }
        request.setString("action", "areaPoint");
        request.setInteger("point", event.button == 0 ? 1 : 2);
        request.setBoolean("atPlayer", alt);
        request.setInteger("x", alt ? net.minecraft.util.MathHelper.floor_double(mc.thePlayer.posX) : hit.blockX);
        request.setInteger("y", alt ? net.minecraft.util.MathHelper.floor_double(mc.thePlayer.posY) : hit.blockY);
        request.setInteger("z", alt ? net.minecraft.util.MathHelper.floor_double(mc.thePlayer.posZ) : hit.blockZ);
        event.setCanceled(true);
        lastUseWorld = mc.theWorld;
        lastUseTick = mc.theWorld.getTotalWorldTime();
        request.setString("session", state.session);
        HologramNetwork.sendAction(request);
    }

    @SubscribeEvent
    public void areaGuide(RenderGameOverlayEvent.Text event) {
        if (!projectorWorld()) return;
        HologramWorldFlow.Stage stage = worldStage();
        String key = state != null && state.data.getBoolean("areaImporting") ? "worldGuideAreaImport"
            : stage == HologramWorldFlow.Stage.AREA ? "worldGuideArea"
                : stage == HologramWorldFlow.Stage.MOVING ? "worldGuideMoving"
                    : stage == HologramWorldFlow.Stage.LOCKED ? "worldGuideLocked"
                        : stage == HologramWorldFlow.Stage.JOB ? "worldGuideJob" : "worldGuideIdle";
        event.left.add(text(key, "Right-click: projector workspace"));
        if (stage == HologramWorldFlow.Stage.MOVING) {
            int[] anchor = movingAnchor(state);
            event.left.add(
                String.format(
                    java.util.Locale.ROOT,
                    text("worldGuideTarget", "Distance %d | Target %d, %d, %d | Scroll: distance"),
                    previewDistance.distance(),
                    anchor[0],
                    anchor[1],
                    anchor[2]));
            String status = state.data.getString("status");
            if (!status.isEmpty()) event.left.add(status);
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        syncSnapshotWorld();
        installDecoded(snapshotInbox.poll());
        Minecraft mc = Minecraft.getMinecraft();
        Object tool = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        boolean changed = inputScreenChanged || inputWorld != mc.theWorld
            || inputTool != tool
            || inputScreen != mc.currentScreen;
        inputScreenChanged = false;
        inputWorld = mc.theWorld;
        inputTool = tool;
        inputScreen = mc.currentScreen;
        syncPreviewDistance();
        boolean rotate = rotationKey.sample(Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_R), changed);
        boolean enter = enterKey.sample(
            Keyboard.isCreated()
                && (Keyboard.isKeyDown(Keyboard.KEY_RETURN) || Keyboard.isKeyDown(Keyboard.KEY_NUMPADENTER)),
            changed);
        if (!projectorWorld() || state == null || mc.theWorld != snapshotWorld) return;
        if (enter && HologramWorldFlow.intent(worldStage(), HologramWorldFlow.Gesture.ENTER)
            == HologramWorldFlow.Intent.FINISH_AREA) {
            worldAction("finishAreaSelection");
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

    /** Intercept only the world's pause menu; chat and other screens retain their own Escape behavior. */
    @SubscribeEvent
    public void worldEscape(GuiOpenEvent event) {
        inputScreenChanged = true;
        previewDistance.sync(false, null, "");
        if (!(event.gui instanceof GuiIngameMenu) || !projectorWorld() || state == null) return;
        HologramWorldFlow.Intent intent = HologramWorldFlow.intent(worldStage(), HologramWorldFlow.Gesture.ESCAPE);
        if (intent != HologramWorldFlow.Intent.CANCEL_AREA && intent != HologramWorldFlow.Intent.CANCEL_PLACEMENT)
            return;
        event.setCanceled(true);
        worldAction(intent == HologramWorldFlow.Intent.CANCEL_AREA ? "cancelAreaSelection" : "cancelVirtualPlacement");
    }

    private static void worldAction(String action) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", action);
        if (state != null) {
            request.setString("session", state.session);
            request.setLong("planRevision", state.data.getLong("planRevision"));
        }
        HologramNetwork.sendAction(request);
    }

    public static void beginAreaSelection() {
        Minecraft.getMinecraft()
            .displayGuiScreen(null);
        worldAction("beginAreaSelection");
    }

    public static void openSettings() {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "openSettings");
        HologramNetwork.sendAction(request);
    }

    /** Only a render translation; no moving-preview world mutation packets are emitted. */
    static int[] movingAnchor(HologramState snapshot) {
        Minecraft mc = Minecraft.getMinecraft();
        syncPreviewDistance();
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            ForgeDirection face = ForgeDirection.getOrientation(hit.sideHit);
            return new int[] { hit.blockX + face.offsetX, hit.blockY + face.offsetY, hit.blockZ + face.offsetZ };
        }
        net.minecraft.entity.EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        net.minecraft.util.Vec3 look = camera.getLookVec();
        return HologramWorldFlow.airAnchor(
            camera.posX,
            camera.posY + camera.getEyeHeight(),
            camera.posZ,
            look.xCoord,
            look.yCoord,
            look.zCoord,
            previewDistance.distance());
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
            while (!snapshotInbox.offer(complete)) {
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
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Hologram snapshot decoding failed", result.failure);
            decodeNotice("decodeError", "Hologram snapshot decoding failed; the previous preview was retained.");
            return;
        }
        installSnapshot(result.value);
    }

    /** Registry lookups, world access, GUI and rendering resources remain on the client thread. */
    private static void installSnapshot(NBTTagCompound nbt) {
        if (nbt.getBoolean("cancelled")) {
            if (state != null && state.session.equals(nbt.getString("session"))) clearProjection(false);
            return;
        }
        // Collection progress is not a complete editable structure; retain the last complete snapshot.
        if (nbt.getBoolean("capturePending")) return;
        String session = nbt.getString("session");
        String generation = nbt.getString("generation");
        if ((!generation.isEmpty() && !generation.equals(session)) || retiredSessions.contains(session)) return;
        boolean same = state != null && state.session.equals(session);
        if (same && nbt.getLong("planRevision") < state.data.getLong("planRevision")) return;
        long batch = nbt.getLong("batchSeq");
        if (same && (batch < acceptedBatch || (batch == acceptedBatch && "ACK".equals(state.data.getString("phase"))
            && "PREPARE".equals(nbt.getString("phase"))))) return;
        // Reject stale packets before copying a large NBT tree or resolving thousands of block/item entries.
        HologramState next = new HologramState(nbt);
        if (!same) {
            if (state != null) retire(state.session);
            HologramRenderer.release();
            acceptedBatch = -1;
        }
        long now = System.nanoTime();
        acceptedBatch = batch;
        receivedAt = now;
        HologramRenderer.release();
        state = next;
        Minecraft mc = Minecraft.getMinecraft();
        snapshotWorld = mc.theWorld;
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
        } else if (nbt.getBoolean("virtual") && nbt.getBoolean("moving") && !nbt.getBoolean("open")) {
            if (mc.currentScreen instanceof HologramScreen) mc.displayGuiScreen(null);
        } else if (mc.currentScreen instanceof HologramScreen && same) {
            ((HologramScreen) mc.currentScreen).updateState(next);
        } else if (!same || nbt.getBoolean("open")) {
            mc.displayGuiScreen(new HologramScreen(next));
        }
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
        if (invalidatePending) {
            assembly.clear();
            snapshotInbox.clear();
        }
        // A decoded server cancellation can precede an already arriving new-session snapshot.
        if (state != null) retire(state.session);
        HologramRenderer.release();
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
        Minecraft mc = Minecraft.getMinecraft();
        assembly.expire(System.nanoTime());
        syncSnapshotWorld();
        if (state == null) return;
        if (mc.theWorld == null || mc.theWorld != snapshotWorld
            || mc.thePlayer == null
            || mc.thePlayer.dimension != state.data.getInteger("dimension")
            || System.nanoTime() - receivedAt > SNAPSHOT_TTL_NS) {
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
