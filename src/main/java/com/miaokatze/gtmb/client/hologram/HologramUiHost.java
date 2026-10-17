package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

/** Own-named ports must not inherit Minecraft method names: reobf remaps the screen's superclass methods. */
interface HologramUiHost {

    Minecraft minecraft();

    FontRenderer font();

    int screenWidth();

    int screenHeight();

    HologramScreen parent();

    void renderBackground();

    void renderText(FontRenderer font, String text, int x, int y, int color);

    void renderCenteredText(FontRenderer font, String text, int x, int y, int color);

    void renderTooltip(List<String> rows, int x, int y, FontRenderer font);
}
