package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;

import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

/** Opens on the confirming click, before any server validation or native preview work. */
final class HologramPlacementLoadingScreen extends HologramMuiScreen {

    HologramPlacementLoadingScreen() {
        super("placement_validation", 430, 164);
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> "蓝图放置 · 正在核对", 430);
        panel().child(
            HologramMuiTheme
                .label(() -> HologramLibraryClient.INSTANCE.status(), 14, 42, 402, 32, HologramMuiTheme.TEXT));
        panel().child(
            new Progress().pos(14, 82)
                .size(402, 30));
        panel().child(
            HologramMuiTheme.button(
                () -> "返回世界，后台继续",
                112,
                128,
                206,
                20,
                () -> Minecraft.getMinecraft()
                    .displayGuiScreen(null)));
    }

    private static final class Progress extends Widget<Progress> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            HologramProgress progress = HologramBackgroundTasks.progress();
            if (progress == null) return;
            HologramProgressView.draw(Minecraft.getMinecraft().fontRenderer, progress, 0, 0, 402, 7);
            Minecraft.getMinecraft().fontRenderer
                .drawString(HologramProgressView.detail(progress), 0, 16, HologramMuiTheme.MUTED);
        }
    }
}
