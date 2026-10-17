package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.ModularUI;
import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.screen.ModularScreen;

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
