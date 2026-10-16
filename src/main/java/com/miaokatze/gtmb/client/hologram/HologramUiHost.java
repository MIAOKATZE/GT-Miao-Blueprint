package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

interface HologramUiHost {

    Minecraft minecraft();

    FontRenderer font();

    int screenWidth();

    int screenHeight();

    HologramScreen parent();

    void drawDefaultBackground();

    void drawString(FontRenderer font, String text, int x, int y, int color);

    void drawCenteredString(FontRenderer font, String text, int x, int y, int color);

    void drawHoveringText(List<String> rows, int x, int y, FontRenderer font);
}
