package com.miaokatze.gtmb.client.hologram;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.screen.GuiScreenWrapper;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.SecondaryPanel;
import com.cleanroommc.modularui.screen.UISettings;

/** Client-only MUI2 screen. Business packets stay with their existing authenticated handlers. */
abstract class HologramMuiScreen extends GuiScreenWrapper {

    private boolean built;
    private final HologramUiDeferredAction deferredAction = new HologramUiDeferredAction();
    private final Map<String, LocalDialog> dialogs = new HashMap<>();

    HologramMuiScreen(String name, int width, int height) {
        super(createScreen(name, width, height));
        ((ProjectorScreen) getScreen()).owner = this;
        ((ProjectorPanel) panel()).owner = this;
    }

    private static ProjectorScreen createScreen(String name, int width, int height) {
        ProjectorScreen screen = new ProjectorScreen(new ProjectorPanel(name, width, height));
        // MUI2 2.3.92 GuiManager.openScreen installs UISettings before constructing its wrapper.
        // Its client-only path always creates the stock wrapper and ignores UISettings.customGui,
        // so this custom wrapper performs that same real configuration step exactly once here.
        // GuiScreenWrapper then constructs the screen; ClientScreenHandler owns all subsequent
        // init, input, NEI suspension/reopen and resolution events for this same wrapper instance.
        screen.getContext()
            .setSettings(new UISettings());
        return screen;
    }

    protected final ModularPanel panel() {
        return getScreen().getMainPanel();
    }

    @Override
    public void initGui() {
        if (!built) {
            buildPanel();
            built = true;
        }
    }

    /** Build before Minecraft takes input ownership; an initialization failure must leave a usable parent. */
    final void openFrom(GuiScreen parent) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != parent) return;
        try {
            initGui();
            minecraft.displayGuiScreen(this);
        } catch (RuntimeException failure) {
            if (minecraft.currentScreen == this) minecraft.displayGuiScreen(parent);
            else onMuiClose();
            throw failure;
        }
        if (minecraft.currentScreen != this) onMuiClose();
    }

    protected abstract void buildPanel();

    /** The next MUI tick runs after input's panel/manager doSafe scopes have returned. */
    final void deferAction(Runnable action) {
        deferredAction.offer(action);
    }

    /** Return whether this tick accepted the intent, so a playback producer can retry without getting stuck. */
    final boolean tryDeferAction(Runnable action) {
        return deferredAction.tryOffer(action);
    }

    final void openDialog(String name, SecondaryPanel.IPanelBuilder builder) {
        deferAction(
            () -> dialogs.computeIfAbsent(name, ignored -> new LocalDialog(panel()))
                .open(builder));
    }

    /** PanelManager retains the first handler for a name for the entire screen lifetime. */
    static final class LocalDialog {

        private SecondaryPanel.IPanelBuilder builder;
        private final IPanelHandler handler;

        LocalDialog(ModularPanel parent) {
            handler = IPanelHandler.simple(parent, (panel, player) -> builder.build(panel, player), true);
        }

        void open(SecondaryPanel.IPanelBuilder next) {
            if (prepare(next)) handler.openPanel();
        }

        boolean prepare(SecondaryPanel.IPanelBuilder next) {
            if (handler.isPanelOpen()) return false;
            builder = next;
            // Local dialogs contain draft values/closures, not server inventory slots.
            handler.deleteCachedPanel();
            return true;
        }
    }

    boolean retainAttachmentEditor(HologramState next) {
        return false;
    }

    protected void onMuiClose() {}

    protected void onMuiActionFailure(RuntimeException failure) {
        if (Minecraft.getMinecraft().thePlayer != null) Minecraft.getMinecraft().thePlayer.addChatMessage(
            new net.minecraft.util.ChatComponentText(
                "GTMB 界面操作失败，请重试：" + (failure.getMessage() == null ? "窗口初始化失败" : failure.getMessage())));
    }

    protected boolean muiKey(char character, int key) {
        return false;
    }

    protected boolean muiKeyBefore(char character, int key) {
        return false;
    }

    protected boolean muiMouseBefore(int button) {
        return false;
    }

    protected boolean muiScrollBefore() {
        return false;
    }

    protected void muiActivity() {}

    private static final class ProjectorScreen extends ModularScreen {

        private HologramMuiScreen owner;

        ProjectorScreen(ModularPanel panel) {
            super("gtmb", panel);
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            try {
                owner.deferredAction.run(Minecraft.getMinecraft().currentScreen == owner && getPanelManager().isOpen());
            } catch (RuntimeException failure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Deferred UI action failed", failure);
                owner.onMuiActionFailure(failure);
            }
        }

        @Override
        public void onClose() {
            // Suspension (including NEI), replacement and final close retire queued input intent.
            owner.deferredAction.clear();
            super.onClose();
        }

        @Override
        public boolean onKeyPressed(char character, int key) {
            owner.muiActivity();
            if (owner.muiKeyBefore(character, key)) return true;
            if (key == Keyboard.KEY_ESCAPE && owner.muiKey(character, key)) return true;
            if (super.onKeyPressed(character, key)) return true;
            return owner.muiKey(character, key);
        }

        @Override
        public boolean onMousePressed(int button) {
            owner.muiActivity();
            return owner.muiMouseBefore(button) || super.onMousePressed(button);
        }

        @Override
        public boolean onKeyRelease(char character, int key) {
            owner.muiActivity();
            return super.onKeyRelease(character, key);
        }

        @Override
        public boolean onMouseRelease(int button) {
            owner.muiActivity();
            return super.onMouseRelease(button);
        }

        @Override
        public boolean onMouseDrag(int button, long elapsed) {
            owner.muiActivity();
            return super.onMouseDrag(button, elapsed);
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            owner.muiActivity();
            return owner.muiScrollBefore() || super.onMouseScroll(direction, amount);
        }

    }

    /** A temporary NEI screen closes the screen but retains its panel; disposal ends the UI session. */
    private static final class ProjectorPanel extends ModularPanel {

        private HologramMuiScreen owner;
        private boolean notified;

        ProjectorPanel(String name, int width, int height) {
            super("gtmb_" + name);
            size(width, height).background(HologramMuiTheme.CLOTH);
        }

        @Override
        public void transform(IViewportStack stack) {
            int screenWidth = getScreen().getScreenArea().width;
            int screenHeight = getScreen().getScreenArea().height;
            float scale = Math.min(
                1f,
                Math.min(
                    screenWidth / (float) Math.max(1, getArea().width),
                    screenHeight / (float) Math.max(1, getArea().height)));
            stack.translate(
                (screenWidth - getArea().width * scale) / 2f,
                (screenHeight - getArea().height * scale) / 2f);
            stack.scale(scale, scale);
        }

        @Override
        public void dispose() {
            super.dispose();
            if (!notified) {
                notified = true;
                owner.deferredAction.clear();
                owner.onMuiClose();
            }
        }
    }
}
