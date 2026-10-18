package com.miaokatze.gtmb.client.hologram;

import java.net.URI;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.gui.GuiYesNoCallback;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.common.util.BlueprintWebsite;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** Client navigation only. Called by explicit website buttons, never by saves or screen initialization. */
final class HologramBlueprintWebsite {

    private HologramBlueprintWebsite() {}

    static void open(HologramScreen parent) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != parent || parent.ui.session.pendingSequence() >= 0
            || HologramBackgroundTasks.busy()
            || HologramLibraryClient.INSTANCE.busy()) return;
        if (!minecraft.gameSettings.chatLinksPrompt) {
            browse();
            return;
        }
        GuiYesNoCallback callback = (confirmed, id) -> {
            if (id != 0) return;
            if (confirmed) browse();
            // Mark only the actual return; unrelated initialization must still follow its ordinary path.
            parent.resumeAfterWebsiteConfirmation();
            minecraft.displayGuiScreen(parent);
        };
        // MUI2 keeps its panel stack across a non-null vanilla screen. Resume without a library refresh.
        minecraft.displayGuiScreen(new WebsiteConfirmation(callback));
    }

    /** Exercise the real vanilla No-button callback in client smoke checks without opening a browser. */
    static boolean cancelConfirmationForSmoke() {
        if (!(Minecraft.getMinecraft().currentScreen instanceof WebsiteConfirmation confirmation)) return false;
        confirmation.cancel();
        return true;
    }

    private static final class WebsiteConfirmation extends GuiConfirmOpenLink {

        private final GuiYesNoCallback callback;

        WebsiteConfirmation(GuiYesNoCallback callback) {
            super(callback, BlueprintWebsite.URL, 0, false);
            this.callback = callback;
        }

        @Override
        protected void keyTyped(char character, int key) {
            // Vanilla Escape otherwise closes to null, which would dispose the retained MUI stack.
            if (key == Keyboard.KEY_ESCAPE) callback.confirmClicked(false, 0);
            else super.keyTyped(character, key);
        }

        void cancel() {
            super.actionPerformed(new GuiButton(1, 0, 0, ""));
        }
    }

    private static void browse() {
        try {
            // Same Desktop browsing path as Minecraft 1.7.10 GuiChat, after the player's explicit action.
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop")
                .invoke(null);
            desktopClass.getMethod("browse", URI.class)
                .invoke(desktop, URI.create(BlueprintWebsite.URL));
        } catch (Exception failure) {
            GTMiaoBlueprint.LOG.warn("Could not open blueprint website: " + BlueprintWebsite.URL, failure);
        }
    }
}
