package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.miaokatze.miaokatzecore.api.camera.CameraIntersections;
import com.miaokatze.miaokatzecore.api.camera.CameraPose;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;
import com.miaokatze.miaokatzecore.api.camera.HeightSlice;
import com.miaokatze.miaokatzecore.api.camera.OrthographicProjection;
import com.miaokatze.miaokatzecore.api.camera.RayInterval;
import com.miaokatze.miaokatzecore.api.camera.ZoomMotion;
import com.miaokatze.miaokatzecore.client.camera.CameraControl;
import com.miaokatze.miaokatzecore.client.camera.CameraControls;
import com.miaokatze.miaokatzecore.client.camera.CameraFrame;
import com.miaokatze.miaokatzecore.client.camera.CameraSettings;
import com.miaokatze.miaokatzecore.client.camera.WorldCameraHooks;
import com.miaokatze.miaokatzecore.client.camera.WorldCameras;

import cpw.mods.fml.client.registry.ClientRegistry;

/** Blueprint display policy only; Core owns projection, camera hooks and input restoration. */
final class HologramIsometricCamera implements WorldCameras.Source {

    private static final String OWNER = "gtmb:blueprint";
    private static final double DEFAULT_YAW = 45, DEFAULT_PITCH = 35.264389682754654;
    private static final double DEFAULT_SCALE = 12, MIN_SCALE = 3, MAX_SCALE = 64;
    private static final double MIN_PITCH = 15, MAX_PITCH = 85;
    private static final CameraSettings.Render RENDER = CameraSettings.Render.builder()
        .hideHand(true)
        .disableFog(true)
        .disableCull(true)
        .disableVanillaFrustum(true)
        .build();
    private static final CameraSettings.Input INPUT = CameraSettings.Input.builder()
        .consumeHotbarScroll(false)
        .blockDebugReloads(true)
        .freeCursor(true)
        .movement(WorldCameras.MovementPolicy.ALL)
        .build();
    private static final CameraSettings.Hud HUD = CameraSettings.Hud.builder()
        .crosshair(CameraSettings.Hud.Crosshair.HIDDEN)
        .panelVisible(true)
        .panelPosition(12, 54)
        .panelWidth(196)
        .build();
    private static final CameraSettings.Navigation NAVIGATION = CameraSettings.Navigation.builder()
        .pan(true)
        .yaw(true)
        .pitch(true)
        .zoom(true)
        .reset(true)
        .pitchRange(MIN_PITCH, MAX_PITCH)
        .scaleRange(MIN_SCALE, MAX_SCALE)
        .basePixelsPerWorldUnit(DEFAULT_SCALE)
        .sliceRange(0, 256)
        .build();
    static final HologramIsometricCamera INSTANCE = new HologramIsometricCamera();
    private final KeyBinding toggle = binding("toggle", Keyboard.KEY_NUMPAD5);
    private final KeyBinding turnLeft = binding("left", Keyboard.KEY_NUMPAD4);
    private final KeyBinding turnRight = binding("right", Keyboard.KEY_NUMPAD6);
    private WorldCameras.Lease lease;
    private Object world;
    private double x, y, z, yaw = DEFAULT_YAW, pitch = DEFAULT_PITCH;
    private final ZoomMotion zoom = new ZoomMotion(DEFAULT_SCALE, MIN_SCALE, MAX_SCALE);
    private HeightSlice slice = HeightSlice.disabled();
    private CameraSettings settings = settingsFor(slice);
    private boolean closing, displaced;
    private CameraFrame tracedFrame;
    private int tracedPixelX, tracedPixelY;
    private long tracedTick = Long.MIN_VALUE;
    private MovingObjectPosition hit;
    private boolean traceAvailable;

    private final CameraControls controls = new CameraControls() {

        @Override
        public boolean supports(CameraControl control) {
            return true;
        }

        @Override
        public void panWorld(double dx, double dy, double dz) {
            if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return;
            x += dx;
            y += dy;
            z += dz;
        }

        @Override
        public void rotate(double yawDelta, double pitchDelta) {
            if (!Double.isFinite(yawDelta) || !Double.isFinite(pitchDelta)) return;
            yaw += yawDelta;
            pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + pitchDelta));
        }

        @Override
        public void zoom(double factor) {
            zoom.multiply(factor, 0);
            zoom.advance(1, 0);
        }

        @Override
        public void resetView() {
            resetCamera();
        }

        @Override
        public void requestSlice(HeightSlice requested) {
            if (requested == null || slice.equals(requested)) return;
            slice = requested;
            settings = settingsFor(slice);
        }
    };

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

    boolean blocksWorldInput() {
        return active() ? pickFrame() == null : blocksVanillaTarget();
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
        while (turnLeft.isPressed()) if (active()) controls.rotate(-90, 0);
        while (turnRight.isPressed()) if (active()) controls.rotate(90, 0);
    }

    private void open() {
        Minecraft mc = Minecraft.getMinecraft();
        resetCamera();
        displaced = false;
        world = mc.theWorld;
        lease = WorldCameras.tryAcquire(OWNER, world, 30, this, () -> {
            lease = null;
            world = null;
            tracedFrame = null;
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
            closing = false;
            displaced = false;
        }
    }

    private void resetCamera() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            x = mc.thePlayer.posX;
            y = mc.thePlayer.boundingBox.minY + mc.thePlayer.getEyeHeight();
            z = mc.thePlayer.posZ;
        }
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom.snap(DEFAULT_SCALE);
    }

    private CameraFrame pickFrame() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!active() || !Mouse.isCreated()
            || mc.currentScreen != null
            || !mc.inGameHasFocus
            || WorldCameraHooks.pointerCaptured()) return null;
        CameraFrame frame = WorldCameraHooks.frame(OWNER, mc.theWorld);
        return frame != null && frame.pickReady()
            && frame.requestedSlice()
                .equals(slice) ? frame : null;
    }

    HologramWorldTarget.Target target(boolean floating, boolean adjacent, int distance, int[] feet) {
        Minecraft mc = Minecraft.getMinecraft();
        CameraFrame frame = pickFrame();
        if (frame == null) return new HologramWorldTarget.Target(null, false);
        int pixelX = Mouse.getX(), pixelY = Mouse.getY();
        RayInterval interval = WorldCameraHooks.pixelRayInterval(OWNER, mc.theWorld, pixelX, pixelY);
        if (interval == null) return new HologramWorldTarget.Target(null, false);
        CameraRay ray = interval.ray();
        long tick = mc.theWorld.getTotalWorldTime();
        if (!sameFrame(frame, tracedFrame) || pixelX != tracedPixelX || pixelY != tracedPixelY || tick != tracedTick) {
            tracedFrame = frame;
            tracedPixelX = pixelX;
            tracedPixelY = pixelY;
            tracedTick = tick;
            hit = null;
            traceAvailable = trace(interval, frame.displayedSlice(), pixelX, pixelY);
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
        if ((floating || hit == null) && distance != 0 && at != null) {
            double base = CameraIntersections
                .plane(ray, 0, 1, 0, mc.thePlayer.boundingBox.minY + mc.thePlayer.getEyeHeight(), 0, 8192);
            // Alt may ignore solid blocks, but it cannot jump an unloaded gap on its way to the floating point.
            double targetDistance = base + distance;
            if (targetDistance < interval.minDistance() || targetDistance > interval.maxDistance())
                return new HologramWorldTarget.Target(null, false);
            if (!HologramLoadedRayTrace.trace(
                ray,
                interval.minDistance(),
                targetDistance,
                mc.theWorld::blockExists,
                (bx, by, bz, from, to) -> null).available) return new HologramWorldTarget.Target(null, false);
        }
        if (at != null && at[1] >= 0
            && at[1] < mc.theWorld.getHeight()
            && frame.displayedSlice()
                .containsBlockY(at[1])
            && mc.theWorld.blockExists(at[0], at[1], at[2])) return target;
        return new HologramWorldTarget.Target(null, false);
    }

    private static boolean sameFrame(CameraFrame a, CameraFrame b) {
        return b != null && a.ownerEpoch() == b.ownerEpoch()
            && a.transformRevision() == b.transformRevision()
            && a.sliceRevision() == b.sliceRevision()
            && a.geometryRevision() == b.geometryRevision();
    }

    /** Skip near-plane void, then trace only the first continuous loaded region of the displayed ray. */
    private boolean trace(RayInterval interval, HeightSlice displayedSlice, int pixelX, int pixelY) {
        net.minecraft.world.World level = Minecraft.getMinecraft().theWorld;
        interval = interval.clip(HeightSlice.between(0, level.getHeight(), 0));
        if (interval == null) return false;
        CameraRay ray = interval.ray();
        HologramLoadedRayTrace.Result<MovingObjectPosition> result = HologramLoadedRayTrace
            .trace(ray, interval.minDistance(), interval.maxDistance(), level::blockExists, (bx, by, bz, from, to) -> {
                // Match vanilla's non-liquid block collision policy, without reading an unverified voxel.
                if (!displayedSlice.containsBlockY(by)) return null;
                net.minecraft.block.Block block = level.getBlock(bx, by, bz);
                if (!block.canCollideCheck(level.getBlockMetadata(bx, by, bz), false)) return null;
                return HologramWorldTarget.firstVisibleNativeSurface(
                    ray,
                    from,
                    to,
                    displayedSlice,
                    pixelX,
                    pixelY,
                    (a, b) -> block.collisionRayTrace(level, bx, by, bz, along(ray, a), along(ray, b)));
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
        if (pickFrame() == null) return null;
        target(false, false, 0, new int[] { 0, 0, 0 });
        return traceAvailable && mc.theWorld == world
            && hit != null
            && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? hit : null;
    }

    void guide(List<String> lines) {
        if (!HologramClient.projectorWorld() || active()) return;
        String key = GameSettings.getKeyDisplayString(toggle.getKeyCode());
        lines.add(
            String.format(
                HologramClient.text(displaced ? "camera.displaced" : "camera.off", "%s: isometric camera"),
                key));
    }

    @Override
    public WorldCameras.View sample(float partialTicks, int scaledWidth, int scaledHeight) {
        return new WorldCameras.View(
            new CameraPose(x, y, z, yaw, pitch),
            OrthographicProjection.scaled(scaledWidth, scaledHeight, zoom.actual(), 256),
            true);
    }

    @Override
    public CameraSettings settings() {
        return settings;
    }

    @Override
    public CameraControls controls() {
        return controls;
    }

    private static CameraSettings settingsFor(HeightSlice slice) {
        return CameraSettings.builder()
            .render(RENDER)
            .input(INPUT)
            .hud(HUD)
            .navigation(NAVIGATION)
            .guiPolicy(WorldCameras.GuiPolicy.RELEASE)
            .slice(slice)
            .build();
    }

    @Override
    public WorldCameras.GuiPolicy guiPolicy() {
        return WorldCameras.GuiPolicy.RELEASE;
    }
}
