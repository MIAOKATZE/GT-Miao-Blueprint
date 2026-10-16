package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.screen.GuiScreenWrapper;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;

/** Client-only MUI2 screen. Business packets stay with their existing authenticated handlers. */
abstract class HologramMuiScreen extends GuiScreenWrapper {

    private boolean built;

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
            built = true;
            buildPanel();
        }
    }

    protected abstract void buildPanel();

    protected void onMuiClose() {}

    protected boolean muiKey(char character, int key) {
        return false;
    }

    protected boolean muiKeyBefore(char character, int key) {
        return false;
    }

    protected boolean muiMouseBefore(int button) {
        return false;
    }

    private static final class ProjectorScreen extends ModularScreen {

        private HologramMuiScreen owner;

        ProjectorScreen(ModularPanel panel) {
            super("gtmb", panel);
        }

        @Override
        public boolean onKeyPressed(char character, int key) {
            if (owner.muiKeyBefore(character, key)) return true;
            if (key == Keyboard.KEY_ESCAPE && owner.muiKey(character, key)) return true;
            if (super.onKeyPressed(character, key)) return true;
            return owner.muiKey(character, key);
        }

        @Override
        public boolean onMousePressed(int button) {
            return owner.muiMouseBefore(button) || super.onMousePressed(button);
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
                owner.onMuiClose();
            }
        }
    }
}
