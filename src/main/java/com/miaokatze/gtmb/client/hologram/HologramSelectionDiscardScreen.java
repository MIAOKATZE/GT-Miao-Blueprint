package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;

import org.lwjgl.input.Keyboard;

/** Escape dismisses this question and leaves the complete selection and its history untouched. */
final class HologramSelectionDiscardScreen extends HologramMuiScreen {

    private final HologramSelectionDiscardDecision decision;

    HologramSelectionDiscardScreen(BooleanSupplier currentSelection, Runnable discard) {
        super("selection_discard", 306, 116);
        decision = new HologramSelectionDiscardDecision(
            () -> currentSelection.getAsBoolean() && !HologramBackgroundTasks.busy(),
            () -> mc.displayGuiScreen(null),
            () -> HologramEditorPreferences.get(mc.mcDataDir)
                .suppressSelectionDiscard(),
            discard);
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> text("title", "Discard the selected area?"), 306);
        panel().child(
            HologramMuiTheme.label(
                () -> text("hint", "Delete removes the previous selection individually"),
                14,
                41,
                278,
                24,
                0xff6f6f6f));
        panel().child(
            HologramMuiTheme.button(() -> text("no", "No"), 12, 82, 56, 22, () -> finish(false, false))
                .name("selection_discard_no"));
        panel().child(
            HologramMuiTheme.primaryButton(() -> text("yes", "Yes"), 74, 82, 56, 22, () -> finish(true, false))
                .name("selection_discard_yes"));
        panel().child(
            HologramMuiTheme
                .button(
                    () -> text("yesWithoutPrompt", "Yes, and don't ask again"),
                    136,
                    82,
                    158,
                    22,
                    () -> finish(true, true))
                .name("selection_discard_skip"));
    }

    private static String text(String key, String fallback) {
        return HologramClient.text("selection.discard." + key, fallback);
    }

    private void finish(boolean accepted, boolean skipPrompt) {
        deferAction(() -> { if (mc.currentScreen == this) decision.answer(accepted, skipPrompt); });
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        finish(false, false);
        return true;
    }

    @Override
    protected void onMuiClose() {
        decision.retire();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
