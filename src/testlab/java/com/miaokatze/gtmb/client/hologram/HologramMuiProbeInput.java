package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.ModularUI;
import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;

import me.eigenraven.lwjgl3ify.api.InputEvents;

/** Probe-only driver through the same MUI2 screen dispatch used by native input. */
final class HologramMuiProbeInput {

    private HologramMuiProbeInput() {}

    /** Explicit synchronous fixture rendering; never evidence of a naturally rendered frame. */
    static void renderFixture(HologramMuiScreen owner, int x, int y, float ticks) {
        ModularScreen screen = owner.getScreen();
        if (!screen.getMainPanel()
            .isValid())
            throw new IllegalStateException(
                "MUI2 render fixture requires a screen initialized by the normal open lifecycle");
        screen.getContext()
            .updateState(x, y, ticks);
        if (owner instanceof HologramScreen) ((HologramScreen) owner).ui.review.tickMaterialDialog();
        screen.onFrameUpdate();
        screen.drawScreen();
        screen.drawForeground();
    }

    static void press(HologramMuiScreen owner, int x, int y, int button) {
        press(owner.getScreen(), x, y, button);
    }

    static void press(ModularScreen screen, int x, int y, int button) {
        screen.getContext()
            .updateState(x, y, 0);
        screen.onFrameUpdate();
        if (!screen.onMouseInputPre(button, true)) screen.onMousePressed(button);
    }

    static void release(HologramMuiScreen owner, int x, int y, int button) {
        release(owner.getScreen(), x, y, button);
    }

    static void release(ModularScreen screen, int x, int y, int button) {
        screen.getContext()
            .updateState(x, y, 0);
        screen.onFrameUpdate();
        if (!screen.onMouseInputPre(button, false)) screen.onMouseRelease(button);
    }

    static void click(HologramMuiScreen owner, int x, int y, int button) {
        press(owner, x, y, button);
        release(owner, x, y, button);
    }

    static void click(ModularScreen screen, int x, int y, int button) {
        press(screen, x, y, button);
        release(screen, x, y, button);
    }

    static void control(HologramScreen owner, int id) {
        int[] point = owner.smokeControlPoint(id);
        click(owner, point[0], point[1], 0);
    }

    static void control(HologramQuantumGroupsScreen owner, int id) {
        int[] point = owner.smokeControlPoint(id);
        click(owner, point[0], point[1], 0);
    }

    /** Locate a current shell widget, prove the native hit, then use the screen's normal input dispatcher. */
    static void nativeTarget(HologramScreen owner, String key, java.util.Map<String, Object> evidence)
        throws ReflectiveOperationException {
        evidence.put("targetKey", key);
        int[] clipped = owner.ui.shell.targetBounds(key);
        evidence.put("clippedBounds", clipped);
        if (clipped == null || clipped[2] <= 0 || clipped[3] <= 0)
            throw new IllegalStateException("Native target is disabled, clipped or outside the attached graph: " + key);
        boolean modal = owner.ui.shell.hasModalPanel();
        evidence.put("modalBlocked", modal);
        if (modal) throw new IllegalStateException("A native modal panel blocks target " + key);
        Object registry = HologramTestLabUiDiagnostics.readDiagnosticField(owner.ui.shell, "targets");
        Object entries = ((java.util.Map<?, ?>) registry).get(key);
        IWidget target = null;
        if (entries instanceof java.util.List) {
            java.util.List<?> widgets = (java.util.List<?>) entries;
            for (int i = widgets.size() - 1; i >= 0; i--) {
                IWidget widget = (IWidget) widgets.get(i);
                if (java.util.Arrays.equals(clipped, owner.ui.shell.nativeWidgetBounds(widget))) {
                    target = widget;
                    break;
                }
            }
        }
        if (target == null) throw new IllegalStateException("No actual registered widget matches target " + key);
        evidence.put(
            "widgetClass",
            target.getClass()
                .getName());
        evidence.put("widgetName", target.getName());
        evidence.put(
            "widgetArea",
            new int[] { target.getArea().x, target.getArea().y, target.getArea().width, target.getArea().height });
        evidence.put(
            "panelName",
            target.getPanel()
                .getName());
        evidence.put("widgetEnabled", target.isEnabled() && target.areAncestorsEnabled());
        int x = clipped[0] + clipped[2] / 2, y = clipped[1] + clipped[3] / 2;
        evidence.put("pointer", new int[] { x, y });
        ModularScreen screen = owner.getScreen();
        screen.getContext()
            .updateState(x, y, 0);
        // Panel.onInit registers its real findHoveredWidgets callback with this native frame hook.
        screen.onFrameUpdate();
        boolean hit = false;
        java.util.List<String> hovered = new java.util.ArrayList<String>();
        for (LocatedWidget located : target.getPanel()
            .getHovering()) {
            IWidget widget = located.getElement();
            if (widget == target) hit = true;
            hovered.add(
                widget.getClass()
                    .getName() + ":"
                    + widget.getName());
        }
        evidence.put("actualHovering", hovered);
        evidence.put("targetInActualHovering", hit);
        if (!hit) throw new IllegalStateException(
            "Native hit test missed " + key
                + " at "
                + x
                + ","
                + y
                + "; clipped="
                + java.util.Arrays.toString(clipped)
                + "; hovering="
                + hovered);
        boolean consumed = screen.onMouseInputPre(0, true);
        boolean handled = false;
        try {
            if (!consumed) handled = screen.onMousePressed(0);
        } finally {
            if (!screen.onMouseInputPre(0, false)) screen.onMouseRelease(0);
        }
        evidence.put("pressPreConsumed", consumed);
        evidence.put("pressHandled", handled);
        evidence.put("dispatch", "ModularScreen.onMouseInputPre/onMousePressed/onMouseRelease");
        if (consumed || !handled)
            throw new IllegalStateException("Native input was intercepted or unhandled for target " + key);
    }

    static void scroll(HologramMuiScreen owner, int x, int y, int delta) {
        ModularScreen screen = owner.getScreen();
        screen.getContext()
            .updateState(x, y, 0);
        screen.onFrameUpdate();
        screen.onMouseScroll(delta > 0 ? UpOrDown.UP : UpOrDown.DOWN, Math.abs(delta));
    }

    static void key(HologramMuiScreen owner, char character, int key) {
        key(owner.getScreen(), character, key);
    }

    static void key(ModularScreen screen, char character, int key) {
        // SOH was sufficient for vanilla; MUI2 checks the real modifier state for Ctrl+A.
        if (character == 1 && key == Keyboard.KEY_A
            && !Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
            && !Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            try {
                Field state;
                int index = Keyboard.KEY_LCONTROL;
                try {
                    state = Keyboard.class.getDeclaredField("keyDownBuffer");
                } catch (NoSuchFieldException lwjgl3) {
                    ClassLoader loader = Keyboard.class.getClassLoader();
                    state = Class.forName("org.lwjglx.input.Keyboard", false, loader)
                        .getField("sdlKeyPressedArray");
                    Method translate = Class.forName("org.lwjglx.input.KeyCodes", false, loader)
                        .getMethod("lwjglToSdlScancode", int.class);
                    index = (Integer) translate.invoke(null, Keyboard.KEY_LCONTROL);
                }
                state.setAccessible(true);
                ByteBuffer keys = (ByteBuffer) state.get(null);
                byte previous = keys.get(index);
                try {
                    keys.put(index, (byte) 1);
                    if (!Keyboard.isKeyDown(Keyboard.KEY_LCONTROL))
                        throw new IllegalStateException("Probe Control press was not observed by native input");
                    screen.onKeyPressed(character, key);
                } finally {
                    keys.put(index, previous);
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot stage native Ctrl+A modifier", failure);
            }
            return;
        }
        screen.onKeyPressed(character, key);
        // lwjgl3ify separates printable text from key presses. Dispatch its public text event
        // once through the screen, without calling the text field or editing its value.
        if (ModularUI.Mods.LWJGL3IFY.isLoaded() && character >= 32
            && character != 127
            && !Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
            && !Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))
            screen.onTextEvent(new InputEvents.TextEvent(String.valueOf(character)));
    }
}
