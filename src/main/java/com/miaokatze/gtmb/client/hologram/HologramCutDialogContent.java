package com.miaokatze.gtmb.client.hologram;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.IntValue;
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
        confirmationActions(panel, allowSkip, 120, "confirm", finish);
    }

    static void demolitionConfirmation(ModularPanel panel, Supplier<String> title, Supplier<String> range,
        IntValue destination, BiConsumer<Boolean, Boolean> finish) {
        HologramMuiTheme.header(panel, title, 306);
        panel.child(
            HologramMuiTheme.label(range, 14, 41, 278, 36, HologramEditorTheme.PAPER_INFORMATION)
                .textAlign(Alignment.TopLeft)
                .name("demolition_range"))
            .child(
                HologramMuiTheme.label(() -> text("confirmDemolitionDetail"), 14, 83, 278, 27, HologramMuiTheme.ERROR)
                    .textAlign(Alignment.TopLeft)
                    .name("demolition_action"))
            .child(
                HologramMuiTheme.label(
                    () -> text("confirmDemolitionProtection") + (HologramClient.stargateRulesEnabled()
                        ? "\n" + HologramClient
                            .text("stargateRules.oreDrops", "Stargate rules: ores are destroyed without drops or XP")
                        : ""),
                    14,
                    116,
                    278,
                    27,
                    HologramMuiTheme.TITLE)
                    .textAlign(Alignment.TopLeft)
                    .name("demolition_protection"))
            .child(
                HologramMuiTheme
                    .label(() -> text("confirmDemolitionDestination"), 14, 149, 278, 12, HologramMuiTheme.TEXT)
                    .name("demolition_destination_label"))
            .child(
                HologramMuiTheme
                    .label(() -> text("confirmDemolitionDrops"), 14, 197, 278, 45, HologramEditorTheme.PAPER_NEXT)
                    .textAlign(Alignment.TopLeft)
                    .name("demolition_destination"));
        for (int value = 0; value < 2; value++) {
            final int target = value;
            String key = target == 0 ? "confirmDemolitionInventory" : "confirmDemolitionAe";
            panel.child(
                HologramMuiTheme
                    .button(() -> text(key), 12 + target * 150, 167, 132, 22, () -> destination.setIntValue(target))
                    .background(HologramMuiTheme.row(() -> destination.getIntValue() == target, false))
                    .hoverBackground(HologramMuiTheme.row(() -> destination.getIntValue() == target, true))
                    .overlay(
                        IKey.dynamic(() -> (destination.getIntValue() == target ? "▶ " : "") + text(key))
                            .color(
                                () -> destination.getIntValue() == target ? HologramMuiTheme.TITLE
                                    : HologramMuiTheme.TEXT)
                            .shadow(false))
                    .name(target == 0 ? "demolition_destination_inventory" : "demolition_destination_ae"));
        }
        confirmationActions(panel, false, 252, "confirmDemolitionAction", finish);
    }

    private static void confirmationActions(ModularPanel panel, boolean allowSkip, int y, String confirmKey,
        BiConsumer<Boolean, Boolean> finish) {
        if (!allowSkip) {
            panel.child(
                HologramMuiTheme
                    .primaryButton(() -> text(confirmKey), 162, y, 132, 22, () -> finish.accept(true, false))
                    .name("cut_confirm"));
            panel.child(
                HologramMuiTheme.button(() -> text("cancel"), 12, y, 132, 22, () -> finish.accept(false, false))
                    .name("cut_cancel"));
        } else {
            panel.child(
                HologramMuiTheme.button(() -> text("cancel"), 12, y, 56, 22, () -> finish.accept(false, false))
                    .name("cut_cancel"));
            panel.child(
                HologramMuiTheme.primaryButton(() -> text(confirmKey), 74, y, 56, 22, () -> finish.accept(true, false))
                    .name("cut_confirm"));
            panel.child(
                HologramMuiTheme
                    .button(
                        () -> text("placement.confirmWithoutPrompt"),
                        136,
                        y,
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
