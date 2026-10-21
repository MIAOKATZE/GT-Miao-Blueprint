package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
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

    interface View {
        boolean active();
        boolean canPrevious();
        boolean queuedNext();
        String nextLabel();
        String explanation();
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
        panel = new Dialog<Void>("gtmb_tutorial_overlay").setDisablePanelsBelow(false)
            .setCloseOnOutOfBoundsClick(false)
            .setDraggable(false)
            .size(host.screenWidth(), host.screenHeight())
            .pos(0, 0)
            .background(IDrawable.NONE)
            .disableThemeBackground(true);
        panel.onUpdateListener(ignored -> {
            if (panel.getArea().width != host.screenWidth() || panel.getArea().height != host.screenHeight())
                panel.size(host.screenWidth(), host.screenHeight()).scheduleResize();
            navigation[0].setEnabled(view.canPrevious());
        });
        panel.child(new Shield().sizeRel(1f).name("tutorial.shield").background(IDrawable.NONE).disableThemeBackground(true));
        Flow row = Flow.row().coverChildren().childPadding(7).mainAxisAlignment(Alignment.MainAxis.CENTER)
            .background(IDrawable.NONE).disableThemeBackground(true).name("tutorial.navigation");
        navigationRow = row;
        String[] names = { "previous", "next", "skip", "restart" };
        for (int i = 0; i < navigation.length; i++) {
            final int index = i;
            ButtonWidget<?> button = new ButtonWidget<>().size(78, 20).name("tutorial." + names[i])
                .disableThemeBackground(true)
                .background((context, x, y, width, height, theme) -> {
                    HologramEditorNativeTheme.background(index != 0 || view.canPrevious(), false, false, index == 1)
                        .draw(x, y, width, height);
                    if (index == 1) drawClickCue(x, y, width, height);
                })
                .hoverBackground((context, x, y, width, height, theme) -> {
                    HologramEditorNativeTheme.background(index != 0 || view.canPrevious(), false, true, index == 1)
                        .draw(x, y, width, height);
                    if (index == 1) drawClickCue(x, y, width, height);
                })
                .overlay(IKey.dynamic(() -> label(index)).color(index == 1 ? HologramEditorNativeTheme.DARK_TEXT
                    : HologramEditorNativeTheme.TEXT).shadow(false))
                .onMousePressed(buttonIndex -> {
                    if (buttonIndex == 0 && view.active() && (index != 0 || view.canPrevious()))
                        host.parent().deferAction(() -> view.navigate(index + 1));
                    return true;
                });
            navigation[i] = button;
            row.child(button);
        }
        panel.child(Flow.col().widthRel(1f).coverChildrenHeight().bottom(10).childPadding(7)
            .background(IDrawable.NONE).disableThemeBackground(true).name("tutorial.footer")
            .child(new Explanation().widthRel(1f).height(28).name("tutorial.explanation"))
            .child(row));
        return panel;
    }

    boolean navigationContains(int x, int y, int button) {
        if (button != 0 || !view.active()) return false;
        for (ButtonWidget<?> entry : navigation) {
            int[] area = entry == null ? null : ui.shell.nativeWidgetBounds(entry);
            if (area != null && entry.isEnabled() && x >= area[0] && y >= area[1]
                && x < area[0] + area[2] && y < area[1] + area[3]) return true;
        }
        return false;
    }

    int[] navigationBounds() {
        return navigationRow == null ? null : ui.shell.nativeWidgetBounds(navigationRow);
    }

    int[] footerBounds() {
        int[] nav = navigationBounds();
        if (nav == null) return null;
        return new int[] { 8, nav[1] - 35, Math.max(1, host.screenWidth() - 16), nav[3] + 35 };
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

        @Override public Result onMousePressed(int button) { return Result.STOP; }
        @Override public Result onMouseTapped(int button) { return Result.STOP; }
        @Override public Result onKeyPressed(char character, int key) { return Result.STOP; }
        @Override public boolean onMouseScroll(UpOrDown direction, int amount) { return true; }
        @Override public boolean canClickThrough() { return false; }
    }

    private final class Explanation extends Widget<Explanation> {
        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            int width = Math.min(620, Math.max(1, getArea().width - 24));
            List<String> lines = host.font().listFormattedStringToWidth(view.explanation(), width);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                String line = lines.get(i);
                host.font().drawString(line, (getArea().width - host.font().getStringWidth(line)) / 2,
                    2 + i * 11, 0xffe2c39c);
            }
        }

        @Override public boolean canHover() { return false; }
        @Override public boolean canClickThrough() { return true; }
    }
}
