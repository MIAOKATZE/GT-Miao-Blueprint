package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.miaokatze.miaokatzecore.api.camera.CameraIntersections;
import com.miaokatze.miaokatzecore.api.camera.CameraPose;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;
import com.miaokatze.miaokatzecore.api.camera.OrthographicProjection;
import com.miaokatze.miaokatzecore.client.camera.WorldCameraHooks;
import com.miaokatze.miaokatzecore.client.camera.WorldCameras;

import cpw.mods.fml.client.registry.ClientRegistry;

/** Blueprint display policy only; Core owns projection, camera hooks and input restoration. */
final class HologramIsometricCamera implements WorldCameras.Source {

    static final HologramIsometricCamera INSTANCE = new HologramIsometricCamera();
    private static final String OWNER = "gtmb:blueprint";
    private static final double DEFAULT_YAW = 45, DEFAULT_PITCH = 35.264389682754654;
    private static final double DEFAULT_SCALE = 12, MIN_SCALE = 3, MAX_SCALE = 64;
    private final KeyBinding toggle = binding("toggle", Keyboard.KEY_NUMPAD5);
    private final KeyBinding turnLeft = binding("left", Keyboard.KEY_NUMPAD4);
    private final KeyBinding turnRight = binding("right", Keyboard.KEY_NUMPAD6);
    private WorldCameras.Lease lease;
    private Object world;
    private double x, y, z, yaw = DEFAULT_YAW, scale = DEFAULT_SCALE;
    private boolean dragging, closing, displaced;
    private int mouseX, mouseY;
    private CameraRay tracedRay;
    private long tracedTick = Long.MIN_VALUE;
    private MovingObjectPosition hit;
    private boolean traceAvailable;

    private HologramIsometricCamera() {}

    private static KeyBinding binding(String name, int key) {
        return new KeyBinding("gtmb.hologram.camera." + name, key, "gtmb.hologram.camera.category");
    }

    void install() {
        ClientRegistry.registerKeyBinding(toggle);
        ClientRegistry.registerKeyBinding(turnLeft);
        ClientRegistry.registerKeyBinding(turnRight);
    }

    boolean active() {
        return lease != null && lease.active();
    }

    boolean blocksVanillaTarget() {
        return !active() && WorldCameraHooks.orthographicActive();
    }

    void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (active() && (mc.theWorld != world || mc.thePlayer == null
            || mc.thePlayer.isDead
            || mc.currentScreen != null
            || !HologramClient.projectorWorld())) close();
        while (toggle.isPressed()) {
            if (active()) close();
            else if (HologramClient.projectorWorld() && mc.thePlayer != null && !mc.thePlayer.isDead) open();
        }
        while (turnLeft.isPressed()) if (active()) yaw -= 90;
        while (turnRight.isPressed()) if (active()) yaw += 90;
        if (!active()) return;
        if (Mouse.isCreated()) {
            int nextX = Mouse.getX(), nextY = Mouse.getY();
            if (dragging && Mouse.isButtonDown(2)) pan(nextX - mouseX, nextY - mouseY);
            else dragging = false;
            mouseX = nextX;
            mouseY = nextY;
        }
    }

    private void open() {
        Minecraft mc = Minecraft.getMinecraft();
        x = mc.thePlayer.posX;
        y = mc.thePlayer.boundingBox.minY + mc.thePlayer.getEyeHeight();
        z = mc.thePlayer.posZ;
        yaw = DEFAULT_YAW;
        scale = DEFAULT_SCALE;
        displaced = false;
        world = mc.theWorld;
        lease = WorldCameras.tryAcquire(OWNER, world, 30, this, () -> {
            lease = null;
            world = null;
            dragging = false;
            tracedRay = null;
            hit = null;
            traceAvailable = false;
            displaced = !closing;
        });
        if (lease == null) {
            world = null;
            displaced = true;
            HologramClient.localPlacementNotice(
                HologramClient.text("camera.busy", "Another camera is active; try again after it closes"));
        }
    }

    void close() {
        closing = true;
        try {
            if (lease != null) lease.close();
        } finally {
            lease = null;
            world = null;
            dragging = false;
            closing = false;
            displaced = false;
        }
    }

    /** Returns true only for a camera gesture; Alt retains blueprint distance adjustment. */
    boolean mouse(MouseEvent event) {
        if (!active()) return false;
        if (event.button == 2) {
            dragging = event.buttonstate;
            mouseX = Mouse.getX();
            mouseY = Mouse.getY();
            return true;
        }
        if (event.dwheel != 0 && HologramClient.controlHeld() && !HologramClient.altHeld()) {
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * Math.pow(1.15, Math.signum(event.dwheel))));
            return true;
        }
        return false;
    }

    private void pan(int dx, int dy) {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        double units = resolution.getScaledWidth_double() / mc.displayWidth / scale;
        double angle = Math.toRadians(yaw), pitch = Math.toRadians(DEFAULT_PITCH);
        // Inverse view rotation: drag the visible plane with the pointer, without moving the player.
        x -= (Math.cos(angle) * dx + Math.sin(angle) * Math.sin(pitch) * dy) * units;
        y -= Math.cos(pitch) * dy * units;
        z -= (Math.sin(angle) * dx - Math.cos(angle) * Math.sin(pitch) * dy) * units;
    }

    CameraRay ray() {
        Minecraft mc = Minecraft.getMinecraft();
        return active() && Mouse.isCreated() ? WorldCameraHooks.mouseRay(OWNER, mc.theWorld, Mouse.getX(), Mouse.getY())
            : null;
    }

    HologramWorldTarget.Target target(boolean floating, boolean adjacent, int distance, int[] feet) {
        Minecraft mc = Minecraft.getMinecraft();
        CameraRay ray = ray();
        if (ray == null) return new HologramWorldTarget.Target(null, false);
        long tick = mc.theWorld.getTotalWorldTime();
        if (!sameRay(ray, tracedRay) || tick != tracedTick) {
            tracedRay = ray;
            tracedTick = tick;
            hit = null;
            traceAvailable = trace(ray);
        }
        if (!traceAvailable && !floating) return new HologramWorldTarget.Target(null, false);
        HologramWorldTarget.Target target = HologramWorldTarget.orthographic(
            hit,
            floating,
            adjacent,
            distance,
            feet,
            mc.thePlayer.boundingBox.minY + mc.thePlayer.getEyeHeight(),
            ray);
        int[] at = target.anchor;
        if (floating && distance != 0 && at != null) {
            double base = CameraIntersections
                .plane(ray, 0, 1, 0, mc.thePlayer.boundingBox.minY + mc.thePlayer.getEyeHeight(), 0, 8192);
            // Alt may ignore solid blocks, but it cannot jump an unloaded gap on its way to the floating point.
            if (!HologramLoadedRayTrace
                .trace(ray, 0, base + distance, mc.theWorld::blockExists, (bx, by, bz, from, to) -> null).available)
                return new HologramWorldTarget.Target(null, false);
        }
        if (at != null && at[1] >= 0 && at[1] < mc.theWorld.getHeight() && mc.theWorld.blockExists(at[0], at[1], at[2]))
            return target;
        return new HologramWorldTarget.Target(null, false);
    }

    private static boolean sameRay(CameraRay a, CameraRay b) {
        return b != null && a.originX() == b.originX()
            && a.originY() == b.originY()
            && a.originZ() == b.originZ()
            && a.directionX() == b.directionX()
            && a.directionY() == b.directionY()
            && a.directionZ() == b.directionZ();
    }

    /** Skip near-plane void, then trace only the first continuous loaded region of the displayed ray. */
    private boolean trace(CameraRay ray) {
        net.minecraft.world.World level = Minecraft.getMinecraft().theWorld;
        double start = ray.originY() < level.getHeight() ? 0
            : CameraIntersections.plane(ray, 0, 1, 0, level.getHeight() - .0001, 0, 8192);
        double end = CameraIntersections.plane(ray, 0, 1, 0, .0001, 0, 8192);
        if (Double.isNaN(start) || Double.isNaN(end) || end < start) return false;
        HologramLoadedRayTrace.Result<MovingObjectPosition> result = HologramLoadedRayTrace
            .trace(ray, start, end, level::blockExists, (bx, by, bz, from, to) -> {
                // Match vanilla's non-liquid block collision policy, without reading an unverified voxel.
                net.minecraft.block.Block block = level.getBlock(bx, by, bz);
                return block.canCollideCheck(level.getBlockMetadata(bx, by, bz), false)
                    ? block.collisionRayTrace(level, bx, by, bz, along(ray, from), along(ray, to))
                    : null;
            });
        hit = result.hit;
        return result.available;
    }

    private static Vec3 along(CameraRay ray, double distance) {
        return Vec3.createVectorHelper(
            ray.originX() + ray.directionX() * distance,
            ray.originY() + ray.directionY() * distance,
            ray.originZ() + ray.directionZ() * distance);
    }

    MovingObjectPosition hit() {
        Minecraft mc = Minecraft.getMinecraft();
        if (ray() == null) return null;
        target(false, false, 0, new int[] { 0, 0, 0 });
        return traceAvailable && mc.theWorld == world
            && hit != null
            && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? hit : null;
    }

    void guide(List<String> lines) {
        if (!HologramClient.projectorWorld()) return;
        String key = GameSettings.getKeyDisplayString(toggle.getKeyCode());
        if (!active()) {
            lines.add(
                String.format(
                    HologramClient.text(displaced ? "camera.displaced" : "camera.off", "%s: isometric camera"),
                    key));
            return;
        }
        lines.add(
            String.format(
                HologramClient.text("camera.on", "Isometric | %s / Esc: exit | Middle drag: pan | Ctrl+scroll: zoom"),
                key));
        lines.add(
            String.format(
                HologramClient
                    .text("camera.rotate", "%s / %s: rotate camera | R: blueprint | Alt+scroll: placement distance"),
                GameSettings.getKeyDisplayString(turnLeft.getKeyCode()),
                GameSettings.getKeyDisplayString(turnRight.getKeyCode())));
    }

    @Override
    public WorldCameras.View sample(float partialTicks, int scaledWidth, int scaledHeight) {
        return new WorldCameras.View(
            new CameraPose(x, y, z, yaw, DEFAULT_PITCH),
            OrthographicProjection.scaled(scaledWidth, scaledHeight, scale, 256),
            true);
    }

    @Override
    public WorldCameras.Options options() {
        return new WorldCameras.Options(true, true, true, true, false, true, WorldCameras.MovementPolicy.ALL, true);
    }

    @Override
    public WorldCameras.GuiPolicy guiPolicy() {
        return WorldCameras.GuiPolicy.RELEASE;
    }
}
