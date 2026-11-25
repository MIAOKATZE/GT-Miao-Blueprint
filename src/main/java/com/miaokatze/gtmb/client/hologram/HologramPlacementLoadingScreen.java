// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

/** Opens on the confirming click, before any server validation or native preview work. */
final class HologramPlacementLoadingScreen extends HologramMuiScreen {

    private final HologramOwnedPlacementProgress.Ticket owned;

    HologramPlacementLoadingScreen(HologramOwnedPlacementProgress.Ticket owned) {
        super("placement_validation", 430, 164);
        this.owned = java.util.Objects.requireNonNull(owned);
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        if (!HologramBackgroundTasks.cancelOwnedPlacement(owned)) HologramBackgroundTasks.leaveOwnedPlacement(owned);
        Minecraft.getMinecraft()
            .displayGuiScreen(null);
        return true;
    }

    private String placementStatus() {
        String result = HologramBackgroundTasks.ownedPlacementResult(owned);
        if (!result.isEmpty()) return result;
        String progress = HologramBackgroundTasks.ownedPlacementStatus(owned);
        return progress.isEmpty() ? HologramUiText.projector("library.placement_status_unavailable") : progress;
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> HologramUiText.projector("library.placement_title"), 430);
        panel().child(HologramMuiTheme.label(this::placementStatus, 14, 42, 402, 32, HologramMuiTheme.TEXT));
        panel().child(
            new Progress().pos(14, 82)
                .size(402, 38));
        panel().child(
            HologramMuiTheme.button(
                () -> HologramUiText.projector(
                    HologramBackgroundTasks.ownedPlacementContinuing(owned) ? "library.placement_background"
                        : "library.placement_return"),
                112,
                128,
                206,
                20,
                () -> {
                    HologramBackgroundTasks.leaveOwnedPlacement(owned);
                    Minecraft.getMinecraft()
                        .displayGuiScreen(null);
                }));
    }

    private final class Progress extends Widget<Progress> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            HologramProgress progress = HologramBackgroundTasks.ownedPlacementView(owned);
            if (progress == null) return;
            HologramProgressView.draw(Minecraft.getMinecraft().fontRenderer, progress, 0, 0, 402, 7, false);
            Minecraft.getMinecraft().fontRenderer
                .drawString(HologramProgressView.detail(progress), 0, 26, HologramEditorTheme.MUTED);
        }
    }
}
