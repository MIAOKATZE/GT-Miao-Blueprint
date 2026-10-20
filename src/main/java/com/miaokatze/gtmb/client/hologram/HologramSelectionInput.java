package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

import cpw.mods.fml.client.registry.ClientRegistry;

/** Shared local-only selection shortcuts. All work is bounded by the number of boxes. */
final class HologramSelectionInput {

    enum CameraAction {
        NONE,
        CORNER_ONE,
        CORNER_TWO,
        CONTINUOUS_POINT,
        SUBMIT
    }

    /** Only selection routes call this; placement clicks and Core camera drags have separate owners. */
    static CameraAction cameraAction(int button, boolean control, boolean shift, boolean alt, boolean rotate,
        boolean continuous) {
        if (button != 0 && button != 1) return CameraAction.NONE;
        if (continuous && control) return CameraAction.NONE;
        if (control) return button == 0 ? CameraAction.CORNER_ONE : CameraAction.CORNER_TWO;
        if (button == 0 && rotate) return CameraAction.NONE;
        if (continuous) return button == 0 ? CameraAction.CONTINUOUS_POINT : CameraAction.NONE;
        return button == 0 && shift && !alt ? CameraAction.SUBMIT : CameraAction.NONE;
    }

    private static KeyBinding continuous;

    static void install() {
        continuous = new KeyBinding("gtmb.key.continuousSelection", Keyboard.KEY_B, "gtmb.hologram.camera.category");
        ClientRegistry.registerKeyBinding(continuous);
    }

    static String continuousLabel() {
        return continuous == null ? "B" : GameSettings.getKeyDisplayString(continuous.getKeyCode());
    }

    static int continuousKeyCode() {
        return continuous == null ? Keyboard.KEY_B : continuous.getKeyCode();
    }

    private final HologramCutNudge movement = new HologramCutNudge();
    private final HologramWorldFlow.KeyEdge undo = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge redo = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge mode = new HologramWorldFlow.KeyEdge();
    private final HologramWorldFlow.KeyEdge polygon = new HologramWorldFlow.KeyEdge();
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
        if (redo.sample(key(Keyboard.KEY_Y) && control && !alt, changed) && active) {
            selection.redoRegion();
            altered = true;
        }
        if (mode.sample(key(Keyboard.KEY_Z) && !control, changed) && active) {
            try {
                if (HologramClient.shiftHeld() && !alt) selection.mergeRegions();
                else if (!HologramClient.shiftHeld()) selection.toggleMode(alt ? "subtract" : "add");
            } catch (java.io.IOException invalid) {
                HologramClient.selectionNotice(invalid.getMessage());
            }
            altered = true;
        }
        if (polygon.sample(continuous != null && GameSettings.isKeyDown(continuous) && !control && !alt, changed)
            && active) {
            try {
                if (selection.continuousActive()) {
                    int[] cursor = HologramCutClient.INSTANCE.continuousActive() ? HologramClient.cutAnchor(false)
                        : HologramClient.areaSelectionAnchor();
                    selection.finishContinuous(cursor);
                } else selection.beginContinuous(HologramClient.shiftHeld());
            } catch (java.io.IOException invalid) {
                HologramClient.selectionNotice(invalid.getMessage());
            }
            altered = true;
        }
        if (delete.sample(key(Keyboard.KEY_DELETE), changed) && active) {
            selection.deleteCurrent();
            altered = true;
        }
        int[] keys = { Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT };
        boolean[] pressed = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) pressed[i] = key(keys[i]);
        int[] points = selection == null ? new int[0] : selection.points();
        int[] anchor = points.length == 6 ? new int[] { points[0], points[1], points[2] } : null;
        net.minecraft.entity.EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        int[] moved = movement.sample(
            anchor,
            active && selection.complete() && !selection.continuousActive() && !altered,
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
