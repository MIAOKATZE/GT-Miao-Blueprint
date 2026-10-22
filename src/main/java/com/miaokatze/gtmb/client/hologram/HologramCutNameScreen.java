package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.UUID;
import java.util.function.BiConsumer;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.value.StringValue;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramCutNaming;

/** Naming and destructive cut confirmation share a single explicit save action. */
final class HologramCutNameScreen extends HologramMuiScreen {

    private final String detail;
    private final BiConsumer<String, Boolean> save;
    private final Runnable cancel;
    private final Object world, connection;
    private final UUID player;
    private final int dimension, slot;
    private final StringValue draft = new StringValue(text("default"));
    private boolean finished;
    private String error = "";

    HologramCutNameScreen(String detail, BiConsumer<String, Boolean> save, Runnable cancel) {
        super("cut_name", 306, 202);
        this.detail = detail;
        this.save = save;
        this.cancel = cancel;
        Minecraft minecraft = Minecraft.getMinecraft();
        world = minecraft.theWorld;
        connection = minecraft.getNetHandler();
        player = minecraft.thePlayer == null ? null : minecraft.thePlayer.getUniqueID();
        dimension = minecraft.thePlayer == null ? 0 : minecraft.thePlayer.dimension;
        slot = minecraft.thePlayer == null ? -1 : minecraft.thePlayer.inventory.currentItem;
    }

    @Override
    protected void buildPanel() {
        HologramCutDialogContent.name(panel(), draft, () -> detail, () -> error, this::finish);
    }

    private boolean validContext() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft.theWorld == world && minecraft.getNetHandler() == connection
            && minecraft.thePlayer != null
            && player != null
            && player.equals(minecraft.thePlayer.getUniqueID())
            && minecraft.thePlayer.dimension == dimension
            && minecraft.thePlayer.inventory.currentItem == slot
            && ItemNekoHologramProjector.isProjector(minecraft.thePlayer.getHeldItem());
    }

    private void finish(boolean accepted, boolean skipPrompt) {
        deferAction(() -> finishDeferred(accepted, skipPrompt));
    }

    private void finishDeferred(boolean accepted, boolean skipPrompt) {
        if (finished) return;
        accepted = accepted && validContext();
        String name = "";
        if (accepted) try {
            name = HologramCutNaming.checkedName(draft.getStringValue());
        } catch (IOException invalid) {
            error = text("invalid");
            return;
        }
        finished = true;
        Minecraft.getMinecraft()
            .displayGuiScreen(null);
        if (accepted) save.accept(name, skipPrompt);
        else cancel.run();
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        finished = true;
        HologramCutClient.INSTANCE.exitCutMode();
        return true;
    }

    @Override
    protected void onMuiClose() {
        if (!finished) {
            finished = true;
            cancel.run();
        }
    }

    private static String text(String key) {
        return HologramCutClient.text("name." + key);
    }
}
