package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileOutputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Real Forge mouse routing, modal rendering and modifier release for the opt-in socket area fixtures. */
public final class HologramAreaSelectionClientSmoke {

    interface Keys {

        void set(int key, boolean down) throws Exception;
    }

    interface LegacyUse {

        void use(Minecraft mc) throws Exception;
    }

    private final File receipts;
    private final String label;
    private final Keys keys;
    private final LegacyUse legacyUse;
    private boolean active, holdShift, intercepted, released;
    private long started, renderSerial, modeSerial;
    private GuiScreen renderedMode;
    private Exception samplingFailure;

    HologramAreaSelectionClientSmoke(File receipts, String label, Keys keys, LegacyUse legacyUse) {
        this.receipts = receipts;
        this.label = label;
        this.keys = keys;
        this.legacyUse = legacyUse;
    }

    void begin(Minecraft mc, MovingObjectPosition hit) throws Exception {
        require(!active && mc.theWorld != null && mc.thePlayer != null, "one connected world press is required");
        require(mc.currentScreen == null, "area entry starts from the actual world");
        active = holdShift = true;
        started = System.nanoTime();
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        try {
            keys.set(Keyboard.KEY_LSHIFT, true);
            mc.objectMouseOver = hit;
            if (HologramClient.state != null) observe("before-state", HologramClient.state.data);
            intercepted = postRight(true);
            if (!intercepted) legacyUse.use(mc);
            // A cancelled Forge press owns the gesture. Never send an additional item callback for it.
        } catch (Exception | Error failure) {
            stop();
            throw failure;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void sampleShift(TickEvent.ClientTickEvent event) {
        if (!active) return;
        try {
            // START precedes GuiScreen.updateScreen; END precedes the production world input poll.
            keys.set(Keyboard.KEY_LSHIFT, holdShift);
        } catch (Exception failure) {
            samplingFailure = failure;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (!active || event.phase != TickEvent.Phase.END) return;
        renderSerial++;
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen instanceof HologramCutModeScreen) {
            renderedMode = screen;
            modeSerial = renderSerial;
        }
    }

    boolean tick(Minecraft mc) throws Exception {
        try {
            require(active, "area entry must be started exactly once");
            if (samplingFailure != null) throw samplingFailure;
            require(
                System.nanoTime() - started < 10000000000L,
                "actual area entry mouse/mode/server ACK exceeded ten seconds");
            NBTTagCompound state = HologramClient.state == null ? null : HologramClient.state.data;
            if (state != null && state.getBoolean("areaSelecting")) {
                require(mc.currentScreen == null, "server area selection must return to the world");
                require(
                    !intercepted || released && renderedMode != null && modeSerial > 0,
                    "intercepted Shift-right must render the selector and release Shift to choose blueprints");
                observe("ack-state", state);
                stop();
                return true;
            }
            if (intercepted && !released
                && mc.currentScreen instanceof HologramCutModeScreen
                && renderedMode == mc.currentScreen
                && modeSerial > 0) {
                NBTTagCompound observed = new NBTTagCompound();
                observed.setString(
                    "screen",
                    renderedMode.getClass()
                        .getName());
                observed.setString("capturePhase", "RenderTick.END");
                observed.setLong("renderSerial", modeSerial);
                observed.setBoolean("shiftHeld", Keyboard.isKeyDown(Keyboard.KEY_LSHIFT));
                observed.setInteger("width", renderedMode.width);
                observed.setInteger("height", renderedMode.height);
                observe("mode-render", observed);
                holdShift = false;
                keys.set(Keyboard.KEY_LSHIFT, false);
                postRight(false);
                released = true;
            }
            return false;
        } catch (Exception | Error failure) {
            stop();
            throw failure;
        }
    }

    private static boolean postRight(boolean down) throws Exception {
        net.minecraftforge.client.event.MouseEvent mouse = new net.minecraftforge.client.event.MouseEvent();
        java.lang.reflect.Field nativeButton = mouse.getClass()
            .getField("button");
        java.lang.reflect.Field nativeState = mouse.getClass()
            .getField("buttonstate");
        nativeButton.setAccessible(true);
        nativeState.setAccessible(true);
        nativeButton.setInt(mouse, 1);
        nativeState.setBoolean(mouse, down);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(mouse);
        return mouse.isCanceled();
    }

    private void stop() throws Exception {
        if (!active) return;
        active = holdShift = false;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        keys.set(Keyboard.KEY_LSHIFT, false);
    }

    private void observe(String phase, NBTTagCompound state) throws Exception {
        try (FileOutputStream output = new FileOutputStream(
            new File(receipts, label + "-area-entry-" + phase + ".nbt"))) {
            CompressedStreamTools.writeCompressed(state, output);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Actual area entry: " + message);
    }
}
