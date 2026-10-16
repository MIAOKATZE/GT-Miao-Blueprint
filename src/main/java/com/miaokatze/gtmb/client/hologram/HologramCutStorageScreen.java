package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Server directory with selection, preview and restore only; there is no delete control. */
final class HologramCutStorageScreen extends GuiScreen {

    private int page;

    private java.util.List<String> hints() {
        return fontRendererObj
            .listFormattedStringToWidth(HologramCutClient.text("storageHint"), Math.max(40, width - 24));
    }

    private int listTop() {
        return 41 + hints().size() * 11;
    }

    private int rows() {
        return Math.max(1, (height - 104 - listTop()) / 26);
    }

    private HologramCutClient client() {
        return HologramCutClient.INSTANCE;
    }

    @Override
    public void initGui() {
        rebuild();
    }

    private void rebuild() {
        buttonList.clear();
        int count = client().entries()
            .size(), rows = rows();
        page = Math.max(0, Math.min(page, Math.max(0, (count - 1) / rows)));
        int w = Math.min(400, width - 24), left = (width - w) / 2;
        for (int row = 0; row < rows && page * rows + row < count; row++) {
            int index = page * rows + row;
            NBTTagCompound entry = client().entries()
                .get(index);
            String label = (client().selected()
                .equals(entry.getString("id")) ? "\u00a7b▶ " : "") + (index + 1)
                + ". "
                + entry.getString("name")
                + " · "
                + entry.getInteger("sizeX")
                + "×"
                + entry.getInteger("sizeY")
                + "×"
                + entry.getInteger("sizeZ")
                + " · DIM "
                + entry.getInteger("dimension");
            buttonList.add(
                new EntryButton(
                    100 + index,
                    left,
                    listTop() + row * 26,
                    w,
                    22,
                    fontRendererObj.trimStringToWidth(label, w - 12)));
        }
        int bw = Math.min(100, (width - 40) / 4), x = width / 2 - (bw * 4 + 24) / 2;
        buttonList.add(new GuiButton(1, x, height - 65, bw, 20, HologramCutClient.text("place")));
        buttonList.add(new GuiButton(2, x + bw + 8, height - 65, bw, 20, HologramCutClient.text("undo")));
        buttonList.add(new GuiButton(3, x + bw * 2 + 16, height - 65, bw, 20, HologramCutClient.text("sync")));
        buttonList.add(new GuiButton(0, x + bw * 3 + 24, height - 65, bw, 20, HologramCutClient.text("close")));
        for (Object raw : buttonList) {
            GuiButton button = (GuiButton) raw;
            if (button.id == 1 || button.id == 2) button.enabled = !client().selected()
                .isEmpty() && !client().busy();
            if (button.id == 3) button.enabled = !client().syncingDirectory();
        }
    }

    @Override
    public void updateScreen() {
        rebuild();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= 100) client().select(
            client().entries()
                .get(button.id - 100)
                .getString("id"));
        else if (button.id == 1) client().beginPreview();
        else if (button.id == 2) client().undo();
        else if (button.id == 3) client().refreshDirectory();
        else mc.displayGuiScreen(null);
        rebuild();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            page += wheel > 0 ? -1 : 1;
            rebuild();
        }
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(null);
        else if (HologramCutClient.alt() && (key == Keyboard.KEY_A || key == Keyboard.KEY_D)) {
            client().cycle(key == Keyboard.KEY_A ? -1 : 1);
            rebuild();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, HologramCutClient.text("storage"), width / 2, 15, 0xffffff);
        int y = 32;
        for (String row : hints()) {
            drawCenteredString(fontRendererObj, row, width / 2, y, 0xaec6d2);
            y += 11;
        }
        if (client().entries()
            .isEmpty())
            drawCenteredString(fontRendererObj, HologramCutClient.text("empty"), width / 2, listTop() + 8, 0xaabbcc);
        drawCenteredString(
            fontRendererObj,
            (page + 1) + " / "
                + Math.max(
                    1,
                    (client().entries()
                        .size() + rows() - 1) / rows()),
            width / 2,
            height - 91,
            0xaabbcc);
        HologramProgress progress = client().progress(true);
        if (progress != null) {
            int progressWidth = Math.min(400, width - 24);
            HologramProgressView
                .draw(fontRendererObj, progress, (width - progressWidth) / 2, height - 34, progressWidth, 3);
        } else drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(client().status(), width - 24),
            width / 2,
            height - 34,
            0xffd98a);
        super.drawScreen(mouseX, mouseY, ticks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Vanilla's 200-pixel button texture repeats its end caps on wide directory rows. */
    private static final class EntryButton extends GuiButton {

        EntryButton(int id, int x, int y, int width, int height, String label) {
            super(id, x, y, width, height, label);
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;
            field_146123_n = hover;
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, hover ? 0xff58adbc : 0xff526574);
            drawRect(
                xPosition + 1,
                yPosition + 1,
                xPosition + width - 1,
                yPosition + height - 1,
                hover ? 0xff304954 : 0xff24343f);
            drawCenteredString(
                minecraft.fontRenderer,
                displayString,
                xPosition + width / 2,
                yPosition + (height - 8) / 2,
                enabled ? 0xffdde7ed : 0xff82929d);
        }
    }
}
