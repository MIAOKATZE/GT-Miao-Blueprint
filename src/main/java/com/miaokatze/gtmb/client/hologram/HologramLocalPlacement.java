package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramPreviewPose;

/** Disposable local display intent. It never grants a server session, material quote or construction permission. */
final class HologramLocalPlacement {

    static final HologramLocalPlacement INSTANCE = new HologramLocalPlacement();
    private HologramState display;
    private Object world;
    private boolean locked, submitting;
    private int[] anchor;
    private HologramPreviewPose pose = new HologramPreviewPose();
    private String localId = "", publicId = "", jsonHash = "", sourceRevision = "";
    private final com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat[] nudgeKeys = {
        new com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat(),
        new com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat(),
        new com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat(),
        new com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat() };

    void input(boolean inWorld, boolean contextChanged) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean enabled = inWorld && locked && !submitting && anchor != null;
        int[] keys = { org.lwjgl.input.Keyboard.KEY_UP, org.lwjgl.input.Keyboard.KEY_DOWN,
            org.lwjgl.input.Keyboard.KEY_LEFT, org.lwjgl.input.Keyboard.KEY_RIGHT };
        int fired = -1, pressed = 0;
        for (int i = 0; i < keys.length; i++) {
            boolean held = org.lwjgl.input.Keyboard.isCreated() && org.lwjgl.input.Keyboard.isKeyDown(keys[i]);
            if (held) pressed++;
            if (nudgeKeys[i]
                .sample(held, enabled, contextChanged, mc.theWorld == null ? 0 : mc.theWorld.getTotalWorldTime()))
                fired = i;
        }
        if (!enabled || fired < 0 || pressed != 1) return;
        boolean alt = HologramClient.altHeld();
        com.miaokatze.gtmb.hologram.HologramWorldFlow.Nudge direction = fired == 0
            ? alt ? com.miaokatze.gtmb.hologram.HologramWorldFlow.Nudge.UP
                : com.miaokatze.gtmb.hologram.HologramWorldFlow.Nudge.FORWARD
            : fired == 1
                ? alt ? com.miaokatze.gtmb.hologram.HologramWorldFlow.Nudge.DOWN
                    : com.miaokatze.gtmb.hologram.HologramWorldFlow.Nudge.BACKWARD
                : fired == 2 ? com.miaokatze.gtmb.hologram.HologramWorldFlow.Nudge.LEFT
                    : com.miaokatze.gtmb.hologram.HologramWorldFlow.Nudge.RIGHT;
        net.minecraft.entity.EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        int[] step = com.miaokatze.gtmb.hologram.HologramWorldFlow.nudgeOffset(camera.rotationYaw, direction);
        int x = anchor[0] + step[0], y = anchor[1] + step[1], z = anchor[2] + step[2];
        if (com.miaokatze.gtmb.hologram.HologramWorldFlow.worldPosition(x, y, z, mc.theWorld.getHeight()))
            anchor = new int[] { x, y, z };
    }

    boolean active() {
        return display != null;
    }

    boolean submitting() {
        return submitting;
    }

    HologramState display() {
        return display;
    }

    boolean locked() {
        return locked;
    }

    int[] anchor() {
        return locked && anchor != null ? anchor.clone() : HologramClient.localPlacementAnchor();
    }

    int[] transform(int x, int y, int z) {
        return pose.apply(x, y, z);
    }

    void rotateGl() {
        org.lwjgl.opengl.GL11.glTranslated(.5, .5, .5);
        int[] turns = pose.recipe();
        for (int i = turns.length - 1; i >= 0; i--)
            org.lwjgl.opengl.GL11.glRotatef(turns[i] == 1 ? 90 : -90, turns[i] == 1 ? 1 : 0, turns[i] == 0 ? 1 : 0, 0);
        org.lwjgl.opengl.GL11.glTranslated(-.5, -.5, -.5);
    }

    double[] bounds(int x, int y, int z) {
        double[] bounds = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        for (int dx = 0; dx <= 16; dx += 16) for (int dy = 0; dy <= 16; dy += 16) for (int dz = 0; dz <= 16; dz += 16) {
            int[] turned = transform(2 * (x + dx) - 1, 2 * (y + dy) - 1, 2 * (z + dz) - 1);
            for (int axis = 0; axis < 3; axis++) {
                double value = (turned[axis] + 1) * .5;
                bounds[axis] = Math.min(bounds[axis], value);
                bounds[axis + 3] = Math.max(bounds[axis + 3], value);
            }
        }
        return bounds;
    }

    void install(HologramState prepared, String localId, String publicId, String hash, String revision) {
        clear();
        this.localId = localId;
        this.publicId = publicId;
        this.jsonHash = hash;
        this.sourceRevision = revision;
        world = Minecraft.getMinecraft().theWorld;
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setString("session", "local-preview");
        metadata.setBoolean("localPreview", true);
        metadata.setBoolean("virtual", true);
        metadata.setBoolean("virtualPlacement", true);
        metadata.setBoolean("moving", true);
        metadata.setInteger("dimension", Minecraft.getMinecraft().thePlayer.dimension);
        metadata.setInteger("x", 0);
        metadata.setInteger("y", 0);
        metadata.setInteger("z", 0);
        display = prepared.withMetadata(metadata);
        Minecraft.getMinecraft()
            .displayGuiScreen(null);
    }

    void tick() {
        if (active() && (Minecraft.getMinecraft().theWorld != world || Minecraft.getMinecraft().thePlayer == null))
            clear();
    }

    void turn(boolean vertical) {
        if (!active() || submitting) return;
        pose = pose.turn(vertical);
    }

    void use(boolean shift) {
        if (display == null || submitting) return;
        if (shift && locked) {
            locked = false;
            anchor = null;
        } else if (!locked) {
            anchor = HologramClient.localPlacementAnchor();
            if (anchor == null) {
                HologramClient.invalidCameraTarget();
                return;
            }
            locked = true;
        } else {
            submit(reviewOptions());
        }
    }

    void submit(NBTTagCompound options) {
        if (!active() || submitting) return;
        submitting = HologramLibraryClient.INSTANCE.prepareLocalPlacement(this, options);
        if (submitting) Minecraft.getMinecraft()
            .displayGuiScreen(new HologramPlacementLoadingScreen());
    }

    void retry() {
        submitting = false;
    }

    static NBTTagCompound reviewOptions() {
        NBTTagCompound options = new NBTTagCompound();
        options.setBoolean("openReview", true);
        options.setBoolean("executeIntent", false);
        return options;
    }

    String localId() {
        return localId;
    }

    String publicId() {
        return publicId;
    }

    String hash() {
        return jsonHash;
    }

    String revision() {
        return sourceRevision;
    }

    void intent(NBTTagCompound request) {
        int[] at = anchor();
        request.setInteger("x", at[0]);
        request.setInteger("y", at[1]);
        request.setInteger("z", at[2]);
        request.setInteger("placementX", at[0]);
        request.setInteger("placementY", at[1]);
        request.setInteger("placementZ", at[2]);
        request.setInteger("side", 1);
        request.setIntArray("previewPose", pose.recipe());
        request.setString("jsonHash", jsonHash);
        request.setString("sourceHash", jsonHash);
        request.setString("sourceRevision", sourceRevision);
        request.setString("id", publicId);
        request.setString("publicPresetId", publicId);
        request.setBoolean("openReview", true);
    }

    void clear() {
        if (display != null) HologramRenderer.nativeSnapshotChanged();
        display = null;
        world = null;
        localId = publicId = jsonHash = sourceRevision = "";
        locked = submitting = false;
        anchor = null;
        pose = new HologramPreviewPose();
        for (com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat key : nudgeKeys) key.sample(false, false, true, 0);
    }
}
