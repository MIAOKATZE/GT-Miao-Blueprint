package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.hologram.HologramV011Evidence;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Dedicated actual Netty input scenario; no synthetic snapshots or controller-memory writes. */
public final class HologramV011AreaClientSmoke {

    private final long installed = System.nanoTime();
    private int stage, sequence, afterObservation;
    private long stageStarted, slotChangedTick, frame;
    private String session;
    private NBTTagCompound observation;
    private boolean captured, finished;
    private HologramAreaOverlay.RenderWitness areaWitness;
    private final HologramActualUsePress press = new HologramActualUsePress();

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.followupAreaClient")) return;
        HologramV011AreaClientSmoke driver = new HologramV011AreaClientSmoke();
        FMLCommonHandler.instance()
            .bus()
            .register(driver);
        MinecraftForge.EVENT_BUS.register(driver);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void worldRendered(net.minecraftforge.client.event.RenderWorldLastEvent event) {
        areaWitness = HologramAreaOverlay.lastRenderWitness();
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.theWorld != null && press.tick(event, mc)) return;
            if (event.phase != TickEvent.Phase.END) return;
            require(System.nanoTime() - installed < 360000000000L, "socket scenario exceeds six minutes");
            if (stageStarted == 0) stageStarted = System.nanoTime();
            require(
                System.nanoTime() - stageStarted < (stage <= 1 ? 180000000000L : 20000000000L),
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
                    || mc.thePlayer.getHeldItem() == null) return;
                mc.displayGuiScreen(null);
                begin(mc);
                next(2);
                return;
            }
            if (stage == 21 && HologramV011Evidence.read("area-server-final.nbt") != null) {
                HologramV011Evidence.marker(
                    "area-client-pass.txt",
                    "PASS actual Socket input and END render evidence; eight corroborated stages");
                finished = true;
                mc.shutdown();
                return;
            }
            require(
                mc.theWorld != null && mc.thePlayer != null && HologramClient.state != null,
                "connected actual world and snapshot");
            NBTTagCompound state = HologramClient.state.data;
            if (session != null)
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
                    next(11);
                    break;
                case 11:
                    if (areaWitness == null || areaWitness.state != HologramClient.state) return;
                    require(
                        Arrays.equals(feet(mc), areaWitness.cursor()),
                        "Alt distance zero is the actual drawn red current target");
                    observe(mc, "alt-zero-current-red-target-feet", 12, true);
                    break;
                case 12:
                    corner(mc, 1, feet(mc), true);
                    key(Keyboard.KEY_LMENU, false);
                    next(13);
                    break;
                case 13:
                    if (!pendingRight(state, feet(mc))) return;
                    observe(mc, "alt-current-red-target-equals-server-corner", 14, true);
                    break;
                case 14:
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
                    NBTTagCompound result = new NBTTagCompound();
                    result.setBoolean("passed", true);
                    result.setInteger("observations", sequence);
                    result.setString("session", session);
                    result.setBoolean("actualNettyClient", true);
                    result.setBoolean("syntheticSnapshotsUsed", false);
                    HologramV011Evidence.write("area-client-final.nbt", result);
                    next(21);
                    break;
                case 21:
                    if (HologramV011Evidence.read("area-server-final.nbt") == null) return;
                    HologramV011Evidence.marker(
                        "area-client-pass.txt",
                        "PASS actual Socket input and END render evidence; eight corroborated stages");
                    finished = true;
                    mc.shutdown();
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
        request.setFloat("requestedCameraPitch", mc.thePlayer.rotationPitch);
        request.setFloat("requestedCameraYaw", mc.thePlayer.rotationYaw);
        request.setLong("requestedAfterRenderFrame", frame);
        request.setTag("nettySnapshot", HologramClient.state.data.copy());
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
        Minecraft mc = Minecraft.getMinecraft();
        try {
            HologramState actual = HologramClient.state;
            if (actual == null || !actual.session.equals(observation.getString("session"))
                || actual.data.getLong("planRevision") != observation.getLong("planRevision")
                || actual.data.getBoolean("areaSelecting") != observation.getBoolean("selecting")
                || !Arrays.equals(actual.data.getIntArray("areaPoints"), observation.getIntArray("points"))) return;
            if (observation.getBoolean("cursorRequired")
                && (areaWitness == null || areaWitness.state != actual || areaWitness.cursor() == null)) return;
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
            HologramWorldGuide.RenderWitness hud = HologramWorldGuide.lastRenderWitness();
            if (hud != null) {
                witness.setInteger("hudTop", hud.top);
                witness.setInteger("hudCenterX", hud.centerX);
                require(hud.top == 8, "conditional world guide is at the top");
            }
            HologramV011Evidence.write("area-client-step-" + sequence + ".nbt", witness);
            captured = true;
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    private void begin(Minecraft mc) throws Exception {
        key(Keyboard.KEY_LSHIFT, true);
        press.begin(mc, false);
        mc.playerController.onPlayerRightClick(
            mc.thePlayer,
            mc.theWorld,
            mc.thePlayer.getHeldItem(),
            50,
            80,
            44,
            2,
            Vec3.createVectorHelper(50.5, 80.5, 44));
        key(Keyboard.KEY_LSHIFT, false);
    }

    private void corner(Minecraft mc, int button, int[] point, boolean consumed) throws Exception {
        if (button == 1 && consumed) press.begin(mc, false);
        if (consumed) aimFloatingCorner(mc, point);
        mc.objectMouseOver = null;
        MouseEvent mouse = new MouseEvent();
        set(mouse, "button", button);
        set(mouse, "buttonstate", true);
        MinecraftForge.EVENT_BUS.post(mouse);
        require(mouse.isCanceled() == consumed, "actual registered mouse routing matches held context");
    }

    /** Real player mouse-look plus registered Alt-wheel input; no synthetic hit or preview-distance writes. */
    static void aimFloatingCorner(Minecraft mc, int[] point) throws Exception {
        require(
            mc.currentScreen == null && mc.thePlayer != null
                && HologramClient.areaProjectorWorld()
                && (mc.renderViewEntity == null || mc.renderViewEntity == mc.thePlayer),
            "floating corner uses the actual held projector and player camera");
        int[] originalFeet = feet(mc);
        int[] currentAnchor = HologramClient.areaSelectionAnchor();
        Field distanceField = HologramClient.class.getDeclaredField("previewDistance");
        distanceField.setAccessible(true);
        int currentDistance = ((HologramWorldFlow.PreviewDistance) distanceField.get(null)).distance();
        boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU);
        if (currentDistance == 0 && Arrays.equals(point, originalFeet)) {
            require(
                Arrays.equals(point, currentAnchor),
                "actual distance zero cursor retains player feet and the existing camera view independently of Alt");
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
            "actual floating selection anchor equals the requested corner independently of block mouse-over");
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
        Field field = Keyboard.class.getDeclaredField("keyDownBuffer");
        field.setAccessible(true);
        ((ByteBuffer) field.get(null)).put(key, (byte) (down ? 1 : 0));
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
            for (int value : new int[] { Keyboard.KEY_LSHIFT, Keyboard.KEY_LCONTROL, Keyboard.KEY_LMENU })
                key(value, false);
            NBTTagCompound result = new NBTTagCompound();
            result.setInteger("stage", stage);
            result.setString("failure", failure.toString());
            if (HologramClient.state != null) result.setTag("actualSnapshot", HologramClient.state.data.copy());
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
