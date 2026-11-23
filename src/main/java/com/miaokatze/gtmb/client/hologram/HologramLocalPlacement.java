// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramPreviewPose;

/** Disposable local display intent. It never grants a server session, material quote or construction permission. */
final class HologramLocalPlacement {

    static final HologramLocalPlacement INSTANCE = new HologramLocalPlacement();
    private HologramState display;
    private HologramLibraryClient.DisplaySource source;
    private Object world, connection, player;
    private boolean locked, submitting;
    private long lockGeneration;
    private int[] anchor;
    private HologramPreviewPose pose = new HologramPreviewPose();
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
        if (com.miaokatze.gtmb.hologram.HologramWorldFlow.worldPosition(x, y, z, mc.theWorld.getHeight())) {
            anchor = new int[] { x, y, z };
            lockGeneration++;
        }
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

    HologramPreviewPose previewPose() {
        return pose;
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

    void install(HologramLibraryClient.DisplaySource prepared) {
        clear();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        source = prepared;
        world = mc.theWorld;
        connection = mc.getNetHandler();
        player = mc.thePlayer;
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setString("session", "local-preview");
        metadata.setBoolean("localPreview", true);
        metadata.setBoolean("virtual", true);
        metadata.setBoolean("virtualPlacement", true);
        metadata.setBoolean("moving", true);
        metadata.setInteger("dimension", mc.thePlayer.dimension);
        metadata.setInteger("x", 0);
        metadata.setInteger("y", 0);
        metadata.setInteger("z", 0);
        display = prepared.state.withMetadata(metadata);
        mc.displayGuiScreen(null);
    }

    void tick() {
        if (active() && !currentContext()) clear();
    }

    private boolean currentContext() {
        return currentContext(Minecraft.getMinecraft());
    }

    private boolean currentContext(Minecraft mc) {
        return mc != null && mc.theWorld != null && mc.thePlayer != null && mc.theWorld == world
            && mc.getNetHandler() == connection && mc.thePlayer == player;
    }

    void turn(boolean vertical) {
        if (!active() || submitting) return;
        pose = pose.turn(vertical);
        lockGeneration++;
    }

    void use(boolean shift) {
        if (display == null || submitting) return;
        if (shift && locked) {
            locked = false;
            anchor = null;
            lockGeneration++;
        } else if (!locked) {
            anchor = HologramClient.localPlacementAnchor();
            if (anchor == null) {
                HologramClient.invalidCameraTarget();
                return;
            }
            locked = true;
            lockGeneration++;
        } else {
            Minecraft client = Minecraft.getMinecraft();
            if (client.currentScreen != null) return;
            Submission frozen = freeze(reviewOptions());
            if (frozen == null) return;
            try {
                new HologramLibraryMaterialReviewScreen(this, frozen, client).openFrom(null);
            } catch (RuntimeException failure) {
                HologramClient.selectionNotice("未打开本地材料窗口，请返回预览重试。");
            }
        }
    }

    boolean submitFrozen(Submission submission) {
        if (submitting || !submission.current()) return false;
        submitting = HologramLibraryClient.INSTANCE.prepareLocalPlacement(submission);
        if (submitting) Minecraft.getMinecraft().displayGuiScreen(new HologramPlacementLoadingScreen());
        return submitting;
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

    Submission freeze(NBTTagCompound options) {
        if (!active() || !locked || anchor == null || !currentContext()) return null;
        NBTTagCompound request = (NBTTagCompound) options.copy();
        int[] at = anchor;
        request.setInteger("x", at[0]);
        request.setInteger("y", at[1]);
        request.setInteger("z", at[2]);
        request.setInteger("placementX", at[0]);
        request.setInteger("placementY", at[1]);
        request.setInteger("placementZ", at[2]);
        request.setInteger("side", 1);
        request.setIntArray("previewPose", pose.recipe());
        if (!options.hasKey("openReview", 1)) request.setBoolean("openReview", true);
        return new Submission(source, display, lockGeneration, request);
    }

    /** The exact source and locked intent outlive asynchronous validation only while this ghost remains current. */
    @Desugar
    record Submission(HologramLibraryClient.DisplaySource source, HologramState display, long lockGeneration,
        NBTTagCompound intent) {

        boolean current() {
            return current(INSTANCE, Minecraft.getMinecraft());
        }

        boolean current(HologramLocalPlacement owner, Minecraft client) {
            return owner.display == display && owner.source == source && owner.locked
                && owner.lockGeneration == lockGeneration && owner.currentContext(client)
                && display.data.getInteger("dimension") == client.thePlayer.dimension;
        }

        @Override
        public NBTTagCompound intent() {
            return (NBTTagCompound) intent.copy();
        }
    }

    void clear() {
        if (display != null) HologramRenderer.nativeSnapshotChanged();
        display = null;
        source = null;
        world = connection = player = null;
        locked = submitting = false;
        lockGeneration++;
        anchor = null;
        pose = new HologramPreviewPose();
        for (com.miaokatze.gtmb.hologram.HologramWorldFlow.KeyRepeat key : nudgeKeys) key.sample(false, false, true, 0);
    }
}
