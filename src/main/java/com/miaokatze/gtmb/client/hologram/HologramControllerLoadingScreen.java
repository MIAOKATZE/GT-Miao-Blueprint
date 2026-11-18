// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/** Read-only foreground while the server prepares the controller workspace. */
final class HologramControllerLoadingScreen extends GuiScreen {

    private final TileEntity tile;
    private final int slot;
    private final long request;
    private final Object connection;
    private boolean painted, attemptedOutline, accepted;
    private HologramControllerOutline outline;

    HologramControllerLoadingScreen(TileEntity tile, long request) {
        this.tile = tile;
        this.request = request;
        Minecraft client = Minecraft.getMinecraft();
        slot = client.thePlayer.inventory.currentItem;
        connection = client.getNetHandler();
    }

    boolean current() {
        return mc.thePlayer != null && mc.theWorld != null && mc.getNetHandler() == connection
            && mc.theWorld == tile.getWorldObj() && mc.thePlayer.inventory.currentItem == slot
            && ItemNekoHologramProjector.isProjector(mc.thePlayer.getHeldItem())
            && mc.theWorld.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile;
    }

    void accept() {
        accepted = true;
    }

    boolean legacyReply(NBTTagCompound data) {
        return data.getBoolean("open") && "controllerStructure".equals(data.getString("openPurpose"))
            && data.getInteger("x") == tile.xCoord && data.getInteger("y") == tile.yCoord
            && data.getInteger("z") == tile.zCoord;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int cx = width / 2, cy = height / 2;
        String title = HologramClient.text("controllerOpening", "Reading controller") + "  ("
            + tile.xCoord + ", " + tile.yCoord + ", " + tile.zCoord + ")";
        drawCenteredString(fontRendererObj, title, cx, cy - 54, 0xFFFFFF);
        if (outline != null) {
            outline.draw(cx, cy - 5, 144, 60);
            drawCenteredString(fontRendererObj, HologramClient.text("controllerPiece", "Local structure piece"),
                cx, cy + 28, 0xA9E1ED);
        }
        drawCenteredString(fontRendererObj,
            HologramClient.text("controllerReading", "Checking the structure…  Esc to close"), cx, cy + 46, 0xCCCCCC);
        painted = true;
    }

    @Override
    public void updateScreen() {
        if (!current()) {
            mc.displayGuiScreen(null);
            return;
        }
        if (painted && !attemptedOutline) {
            attemptedOutline = true;
            outline = HologramControllerOutline.create(tile);
        }
    }

    @Override
    public void onGuiClosed() {
        if (!accepted) HologramClient.controllerOpen.dismiss(request);
    }
}
