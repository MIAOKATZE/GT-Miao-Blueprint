package com.miaokatze.gtmb.client.hologram;

import org.lwjgl.input.Keyboard;

/** Dedicated raw-cut pose controls. Every preview request is derived from the immutable server original. */
final class HologramCutTransformScreen extends HologramMuiScreen {

    private final HologramCutClient cut = HologramCutClient.INSTANCE;

    HologramCutTransformScreen() {
        super("cut_transform", 292, 230);
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> HologramCutClient.text("transform"), 292);
        panel().child(HologramMuiTheme.label(cut::poseSummary, 14, 35, 264, 13, HologramMuiTheme.MUTED));
        for (int axis = 0; axis < 3; axis++) {
            final int selectedAxis = axis;
            String name = new String[] { "X", "Y", "Z" }[axis];
            action(10 + axis * 2, name + " -90°", 14, 52 + axis * 24, 82, () -> cut.rotate(selectedAxis, -1));
            action(11 + axis * 2, name + " +90°", 104, 52 + axis * 24, 82, () -> cut.rotate(selectedAxis, 1));
            action(
                20 + axis,
                HologramCutClient.text("mirror") + " " + name,
                194,
                52 + axis * 24,
                84,
                () -> cut.mirror(selectedAxis));
        }
        String[] faces = { "down", "up", "north", "south", "west", "east" };
        for (int face = 0; face < 6; face++) {
            final int selectedFace = face;
            action(
                30 + face,
                HologramCutClient.text("face." + faces[face]),
                14 + face % 3 * 90,
                132 + face / 3 * 24,
                84,
                () -> cut.face(selectedFace));
        }
        action(40, HologramCutClient.text("resetPose"), 14, 186, 128, cut::resetPose);
        panel().child(
            HologramMuiTheme
                .primaryButton(() -> HologramCutClient.text("done"), 150, 186, 128, 20, () -> mc.displayGuiScreen(null))
                .name("cut_pose_0"));
        panel().child(HologramMuiTheme.label(() -> {
            HologramProgress current = cut.progress(false);
            return current == null ? "" : HologramProgressView.summary(current, System.nanoTime());
        }, 14, 209, 264, 16, 0xffd1dee6));
    }

    private void action(int id, String text, int x, int y, int width, Runnable action) {
        panel().child(
            HologramMuiTheme.button(() -> text, x, y, width, 20, () -> { if (!cut.busy()) action.run(); })
                .setEnabledIf(widget -> !cut.busy())
                .name("cut_pose_" + id));
    }

    @Override
    public void updateScreen() {
        if (!cut.active()) mc.displayGuiScreen(null);
    }

    @Override
    protected boolean muiKey(char c, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        cut.exitCutMode();
        return true;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
