package com.miaokatze.gtmb.client.hologram;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.StringValue;
import com.miaokatze.gtmb.hologram.HologramCutNaming;

/** The normal cut screens and isolated lessons share the same visible controls and callback boundaries. */
final class HologramCutDialogContent {

    private HologramCutDialogContent() {}

    static void name(ModularPanel panel, StringValue draft, Supplier<String> detail, Supplier<String> error,
        BiConsumer<Boolean, Boolean> finish) {
        HologramMuiTheme.header(panel, () -> text("name.title"), 306);
        panel.child(HologramMuiTheme.label(detail, 14, 39, 278, 35, HologramMuiTheme.TEXT))
            .child(HologramMuiTheme.label(() -> text("name.label"), 14, 80, 278, 12, HologramMuiTheme.MUTED))
            .child(
                HologramMuiTheme.textField(draft, 12, 96, 282, 22, HologramCutNaming.MAX_LENGTH)
                    .name("cut_name_draft"))
            .child(HologramMuiTheme.label(() -> text("name.hint"), 14, 124, 278, 20, HologramMuiTheme.MUTED))
            .child(HologramMuiTheme.label(error, 14, 147, 278, 18, HologramMuiTheme.ERROR))
            .child(
                HologramMuiTheme.button(() -> text("name.cancel"), 12, 168, 56, 22, () -> finish.accept(false, false))
                    .name("cut_name_cancel"))
            .child(
                HologramMuiTheme
                    .primaryButton(() -> text("name.save"), 74, 168, 56, 22, () -> finish.accept(true, false))
                    .name("cut_name_save"))
            .child(
                HologramMuiTheme
                    .button(() -> text("name.saveWithoutPrompt"), 136, 168, 158, 22, () -> finish.accept(true, true))
                    .name("cut_name_skip"));
    }

    static void confirmation(ModularPanel panel, Supplier<String> title, Supplier<String> detail, boolean allowSkip,
        BiConsumer<Boolean, Boolean> finish) {
        HologramMuiTheme.header(panel, title, 306);
        panel.child(HologramMuiTheme.label(detail, 14, 41, 278, 66, HologramMuiTheme.TEXT));
        if (!allowSkip) {
            panel.child(
                HologramMuiTheme
                    .primaryButton(() -> text("confirm"), 162, 120, 132, 22, () -> finish.accept(true, false))
                    .name("cut_confirm"));
            panel.child(
                HologramMuiTheme.button(() -> text("cancel"), 12, 120, 132, 22, () -> finish.accept(false, false))
                    .name("cut_cancel"));
        } else {
            panel.child(
                HologramMuiTheme.button(() -> text("cancel"), 12, 120, 56, 22, () -> finish.accept(false, false))
                    .name("cut_cancel"));
            panel.child(
                HologramMuiTheme.primaryButton(() -> text("confirm"), 74, 120, 56, 22, () -> finish.accept(true, false))
                    .name("cut_confirm"));
            panel.child(
                HologramMuiTheme
                    .button(
                        () -> text("placement.confirmWithoutPrompt"),
                        136,
                        120,
                        158,
                        22,
                        () -> finish.accept(true, true))
                    .name("cut_confirm_skip"));
        }
    }

    private static String text(String key) {
        return HologramCutClient.text(key);
    }
}
