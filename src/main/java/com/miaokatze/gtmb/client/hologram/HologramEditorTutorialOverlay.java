package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Transparent native input shield. The four small navigation widgets are its only actionable children. */
final class HologramEditorTutorialOverlay {

    private static final int NAVIGATION_HEIGHT = 20, BOTTOM_GAP = 6;
    // Shared dialog sizing also reads this value. Tutorials use the normal workspace and dialog dimensions.
    static final int RESERVED_HEIGHT = 0;

    interface View {

        boolean active();

        boolean canPrevious();

        boolean queuedNext();

        String nextLabel();

        void navigate(int offset);

        void frame(long now);

        void drawFocus(ModularGuiContext context, int width, int height);
    }

    private final HologramUiHost host;
    private final HologramUiModules ui;
    private final View view;
    private final ButtonWidget<?>[] navigation = new ButtonWidget<?>[4];
    private ModularPanel panel;
    private Flow navigationRow;

    HologramEditorTutorialOverlay(HologramUiModules ui, View view) {
        this.ui = ui;
        this.host = ui.host;
        this.view = view;
    }

    ModularPanel build() {
        if (panel != null) return panel;
        // A full-screen native shield consumes input. Avoid MUI's unrelated 49% modal scrim over the focus.
        panel = new Dialog<Void>(ui.panelName("gtmb_tutorial_overlay")).setDisablePanelsBelow(false)
            .setCloseOnOutOfBoundsClick(false)
            .setDraggable(false)
            .size(host.screenWidth(), host.screenHeight())
            .pos(0, 0)
            .background(IDrawable.NONE)
            .disableThemeBackground(true);
        panel.onUpdateListener(ignored -> {
            if (panel.getArea().width != host.screenWidth() || panel.getArea().height != host.screenHeight())
                panel.size(host.screenWidth(), host.screenHeight())
                    .scheduleResize();
        });
        panel.child(
            new Shield().sizeRel(1f)
                .name("tutorial.shield")
                .background(IDrawable.NONE)
                .disableThemeBackground(true));
        Flow row = Flow.row()
            .coverChildren()
            .childPadding(6)
            .mainAxisAlignment(Alignment.MainAxis.CENTER)
            .leftRel(.5f, 0, .5f)
            .bottom(BOTTOM_GAP)
            .background(IDrawable.NONE)
            .disableThemeBackground(true)
            .name("tutorial.navigation");
        navigationRow = row;
        String[] names = { "previous", "next", "skip", "restart" };
        int[] widths = { 68, 84, 68, 72 };
        for (int i = 0; i < navigation.length; i++) {
            final int index = i;
            ButtonWidget<?> button = HologramEditorNativeTheme
                .button(
                    () -> label(index),
                    null,
                    () -> {
                        if (view.active() && (index != 0 || view.canPrevious())) host.parent()
                            .deferAction(() -> view.navigate(index + 1));
                    },
                    () -> index != 0 || view.canPrevious(),
                    () -> false,
                    index == 1)
                .size(widths[i], NAVIGATION_HEIGHT)
                .name("tutorial." + names[i])
                .disableThemeBackground(true);
            if (index == 1) button.overlay(
                button.getOverlay(),
                (context, x, y, width, height, theme) -> drawClickCue(x, y, width, height));
            navigation[i] = button;
            row.child(button);
        }
        panel.child(row);
        return panel;
    }

    boolean navigationContains(int x, int y, int button) {
        if (button != 0 || !view.active()) return false;
        for (ButtonWidget<?> entry : navigation) {
            int[] area = entry == null ? null : ui.shell.nativeWidgetBounds(entry);
            if (area != null && entry.isEnabled()
                && x >= area[0]
                && y >= area[1]
                && x < area[0] + area[2]
                && y < area[1] + area[3]) return true;
        }
        return false;
    }

    int[] navigationBounds() {
        return navigationRow == null ? null : ui.shell.nativeWidgetBounds(navigationRow);
    }

    int[] footerBounds() {
        return navigationBounds();
    }

    private String label(int index) {
        return index == 0 ? "上一步" : index == 1 ? view.nextLabel() : index == 2 ? "跳过" : "从头开始";
    }

    private void drawClickCue(int x, int y, int width, int height) {
        if (view.queuedNext()) return;
        int phase = (int) (System.nanoTime() / 180_000_000L % 8);
        if (phase < 3 || phase > 6) return;
        int r = phase - 2, color = phase == 6 ? 0x88ffd692 : 0xfff2ce91;
        net.minecraft.client.gui.Gui.drawRect(x - r, y - r, x + 6, y - r + 1, color);
        net.minecraft.client.gui.Gui.drawRect(x + width - 6, y - r, x + width + r, y - r + 1, color);
        net.minecraft.client.gui.Gui.drawRect(x - r, y + height + r - 1, x + 6, y + height + r, color);
        net.minecraft.client.gui.Gui.drawRect(x + width - 6, y + height + r - 1, x + width + r, y + height + r, color);
    }

    private final class Shield extends Widget<Shield> implements Interactable {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            view.frame(System.nanoTime());
            if (view.active()) view.drawFocus(context, getArea().width, getArea().height);
        }

        @Override
        public Result onMousePressed(int button) {
            return Result.STOP;
        }

        @Override
        public Result onMouseTapped(int button) {
            return Result.STOP;
        }

        @Override
        public Result onKeyPressed(char character, int key) {
            return Result.STOP;
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            return true;
        }

        @Override
        public boolean canClickThrough() {
            return false;
        }
    }

}
