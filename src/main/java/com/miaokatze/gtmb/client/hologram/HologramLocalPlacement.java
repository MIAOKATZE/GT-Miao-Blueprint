// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramPreviewPose;

/** Disposable local display intent. It never grants a server session, material quote or construction permission. */
final class HologramLocalPlacement {

    static final HologramLocalPlacement INSTANCE = new HologramLocalPlacement();
    private HologramState display;
    private WorldClient world;
    private EntityClientPlayerMP player;
    private NetHandlerPlayClient connection;
    private boolean locked;
    private int[] anchor;
    private HologramPreviewPose pose = new HologramPreviewPose();
    private HologramLibraryClient.DisplaySource source;
    private String admittedHash;
    private Submission submission;
    private final com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat[] nudgeKeys = {
        new com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat(),
        new com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat(),
        new com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat(),
        new com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat() };

    void input(boolean inWorld, boolean contextChanged) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean enabled = inWorld && locked && !submitting() && anchor != null;
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
        return submission != null;
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

    void install(HologramLibraryClient.DisplaySource source, String admittedHash) {
        clear();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        this.source = source;
        this.admittedHash = admittedHash;
        world = mc.theWorld;
        player = mc.thePlayer;
        connection = mc.getNetHandler();
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setString("session", "local-preview");
        metadata.setBoolean("localPreview", true);
        metadata.setBoolean("virtual", true);
        metadata.setBoolean("virtualPlacement", true);
        metadata.setBoolean("moving", true);
        metadata.setInteger("dimension", player.dimension);
        metadata.setInteger("x", 0);
        metadata.setInteger("y", 0);
        metadata.setInteger("z", 0);
        display = source.state.withMetadata(metadata);
        mc.displayGuiScreen(null);
    }

    void tick() {
        if (active() && !currentContext()) clear();
    }

    private boolean currentContext() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld == world && mc.thePlayer == player && mc.getNetHandler() == connection && !player.isDead;
    }

    void turn(boolean vertical) {
        if (!active() || submitting()) return;
        pose = pose.turn(vertical);
    }

    void use(boolean shift) {
        if (display == null || submitting()) return;
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
        if (!active() || !locked || anchor == null || submitting() || !currentContext()) return;
        submission = new Submission(source, admittedHash, anchor, pose, options);
        if (!HologramLibraryClient.INSTANCE.prepareLocalPlacement(submission)) submission = null;
        if (submitting()) Minecraft.getMinecraft()
            .displayGuiScreen(new HologramPlacementLoadingScreen());
    }

    boolean owns(Submission candidate) {
        return candidate != null && submission == candidate && currentContext();
    }

    void retry(Submission candidate) {
        if (submission == candidate) submission = null;
    }

    static NBTTagCompound reviewOptions() {
        NBTTagCompound options = new NBTTagCompound();
        options.setBoolean("openReview", true);
        options.setBoolean("executeIntent", false);
        return options;
    }

    /** The review click freezes one source and world target across hashing, transfer and server validation. */
    static final class Submission {

        final HologramLibraryClient.DisplaySource source;
        final String admittedHash;
        private final NBTTagCompound intent;

        Submission(HologramLibraryClient.DisplaySource source, String admittedHash, int[] anchor,
            HologramPreviewPose pose, NBTTagCompound options) {
            this.source = source;
            this.admittedHash = admittedHash;
            intent = (NBTTagCompound) options.copy();
            intent.setInteger("x", anchor[0]);
            intent.setInteger("y", anchor[1]);
            intent.setInteger("z", anchor[2]);
            intent.setInteger("placementX", anchor[0]);
            intent.setInteger("placementY", anchor[1]);
            intent.setInteger("placementZ", anchor[2]);
            intent.setInteger("side", 1);
            intent.setIntArray("previewPose", pose.recipe());
            intent.setString("sourceRevision", source.revision);
            intent.setString("id", source.publicId);
            intent.setString("publicPresetId", source.publicId);
            if (!intent.hasKey("openReview", 1)) intent.setBoolean("openReview", true);
        }

        NBTTagCompound request(String hash) {
            NBTTagCompound request = (NBTTagCompound) intent.copy();
            request.setString("jsonHash", hash);
            request.setString("sourceHash", hash);
            return request;
        }
    }

    void clear() {
        if (display != null) HologramRenderer.nativeSnapshotChanged();
        display = null;
        world = null;
        player = null;
        connection = null;
        source = null;
        admittedHash = null;
        submission = null;
        locked = false;
        anchor = null;
        pose = new HologramPreviewPose();
        for (com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat key : nudgeKeys) key.sample(false, false, true, 0);
    }
}
