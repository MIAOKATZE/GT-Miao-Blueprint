package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/** Shared local-only selection shortcuts. All work is bounded by the number of boxes. */
final class HologramSelectionInput {

    private final HologramCutNudge movement = new HologramCutNudge();
    private final HologramWorldFlow.KeyEdge undo = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge delete = new HologramWorldFlow.KeyEdge();
    private Object previous;
    private boolean wasActive;

    boolean tick(HologramAreaSelection selection, boolean enabled) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean active = enabled && selection != null && mc.currentScreen == null;
        boolean changed = previous != selection || wasActive != active;
        previous = selection;
        wasActive = active;
        boolean altered = false;
        boolean control = HologramClient.controlHeld(), alt = HologramClient.altHeld();
        if (undo.sample(key(Keyboard.KEY_Z) && control && !HologramClient.shiftHeld(), changed) && active) {
            selection.undoRegion();
            altered = true;
        }
        if (delete.sample(key(Keyboard.KEY_DELETE), changed) && active) {
            selection.deleteCurrent();
            altered = true;
        }
        int[] keys = { Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT, Keyboard.KEY_W,
            Keyboard.KEY_S };
        boolean[] pressed = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) pressed[i] = key(keys[i]);
        int[] points = selection == null ? new int[0] : selection.points();
        int[] anchor = points.length == 6 ? new int[] { points[0], points[1], points[2] } : null;
        net.minecraft.entity.EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        int[] moved = movement.sample(
            anchor,
            active && selection.complete() && !altered,
            selection == null ? 0 : (long) System.identityHashCode(selection) * 129 + selection.currentRegion(),
            pressed,
            alt,
            camera == null ? 0 : camera.rotationYaw,
            mc.theWorld == null ? 0 : mc.theWorld.getTotalWorldTime(),
            mc.theWorld == null ? 256 : mc.theWorld.getHeight());
        if (anchor != null && moved != anchor) {
            selection.move(moved[0] - anchor[0], moved[1] - anchor[1], moved[2] - anchor[2], mc.theWorld.getHeight());
            altered = true;
        }
        return altered;
    }

    private static boolean key(int code) {
        return Keyboard.isCreated() && Keyboard.isKeyDown(code);
    }
}
